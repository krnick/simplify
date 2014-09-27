package org.cf.smalivm.opcode;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.List;

import org.cf.smalivm.MethodReflector;
import org.cf.smalivm.SideEffect;
import org.cf.smalivm.VirtualMachine;
import org.cf.smalivm.context.ExecutionContext;
import org.cf.smalivm.context.ExecutionGraph;
import org.cf.smalivm.context.ExecutionNode;
import org.cf.smalivm.context.MethodState;
import org.cf.smalivm.emulate.MethodEmulator;
import org.cf.smalivm.type.TypeUtil;
import org.cf.smalivm.type.UnknownValue;
import org.cf.util.SmaliClassUtils;
import org.cf.util.Utils;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction35c;
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.util.ReferenceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InvokeOp extends ExecutionContextOp {

    private static final Logger log = LoggerFactory.getLogger(InvokeOp.class.getSimpleName());

    private static boolean allArgumentsKnown(MethodState mstate) {
        Object[] registerValues = mstate.getRegisterToValue().values();
        for (Object value : registerValues) {
            if (value instanceof UnknownValue) {
                return false;
            }
        }

        return true;
    }

    private static Object getMutableParameterConsensus(TIntList addressList, ExecutionGraph graph, int parameterIndex) {
        ExecutionNode firstNode = graph.getNodePile(addressList.get(0)).get(0);
        Object value = firstNode.getMethodState().getMutableParameter(parameterIndex);
        int[] addresses = addressList.toArray();
        for (int address : addresses) {
            List<ExecutionNode> nodes = graph.getNodePile(address);
            for (ExecutionNode node : nodes) {
                Object otherValue = node.getMethodState().getMutableParameter(parameterIndex);

                if (value != otherValue) {
                    log.trace("No conensus value for parameterIndex #" + parameterIndex + ", returning unknown");

                    return new UnknownValue(TypeUtil.getValueType(value));
                }
            }

        }

        return value;
    }

    static InvokeOp create(Instruction instruction, int address, VirtualMachine vm) {
        int childAddress = address + instruction.getCodeUnits();
        String opName = instruction.getOpcode().name;

        int[] registers = null;
        MethodReference methodReference = null;
        if (opName.contains("/range")) {
            Instruction3rc instr = (Instruction3rc) instruction;
            int registerCount = instr.getRegisterCount();
            int start = instr.getStartRegister();
            int end = start + registerCount;

            registers = new int[registerCount];
            for (int i = start; i < end; i++) {
                registers[i - start] = i;
            }

            methodReference = (MethodReference) instr.getReference();
        } else {
            Instruction35c instr = (Instruction35c) instruction;
            int registerCount = instr.getRegisterCount();

            registers = new int[registerCount];
            switch (registerCount) {
            case 5:
                registers[4] = instr.getRegisterG();
            case 4:
                registers[3] = instr.getRegisterF();
            case 3:
                registers[2] = instr.getRegisterE();
            case 2:
                registers[1] = instr.getRegisterD();
            case 1:
                registers[0] = instr.getRegisterC();
                break;
            }

            methodReference = (MethodReference) instr.getReference();
        }

        String methodDescriptor = ReferenceUtil.getMethodDescriptor(methodReference);
        String returnType = methodReference.getReturnType();
        List<String> parameterTypes = new ArrayList<String>();
        boolean isStatic = opName.contains("-static");
        if (!isStatic) {
            parameterTypes.add(methodReference.getDefiningClass());
        }
        parameterTypes.addAll(Utils.getParameterTypes(methodDescriptor));

        TIntList parameterRegisters = new TIntArrayList();
        for (int i = 0; i < parameterTypes.size(); i++) {
            parameterRegisters.add(registers[i]);
            String type = parameterTypes.get(i);
            if (type.equals("J") || type.equals("D")) {
                i++;
            }
        }

        return new InvokeOp(address, opName, childAddress, methodDescriptor, returnType, parameterRegisters.toArray(),
                        parameterTypes, vm, isStatic);
    }

    private final boolean isStatic;

    private final String methodDescriptor;

    private final int[] parameterRegisters;
    private final List<String> parameterTypes;
    private final String returnType;
    private SideEffect.Level sideEffectType;
    private final VirtualMachine vm;

    private InvokeOp(int address, String opName, int childAddress, String methodDescriptor, String returnType,
                    int[] parameterRegisters, List<String> parameterTypes, VirtualMachine vm, boolean isStatic) {
        super(address, opName, childAddress);

        this.methodDescriptor = methodDescriptor;
        this.returnType = returnType;
        this.parameterRegisters = parameterRegisters;
        this.parameterTypes = parameterTypes;
        this.vm = vm;
        this.isStatic = isStatic;
        sideEffectType = SideEffect.Level.STRONG;
    }

    @Override
    public int[] execute(ExecutionContext ectx) {
        if (vm.isLocalMethod(methodDescriptor)) {
            executeLocalMethod(methodDescriptor, ectx);
        } else {
            MethodState mstate = ectx.getMethodState();
            executeNonLocalMethod(methodDescriptor, mstate);
        }

        return getPossibleChildren();
    }

    public String getReturnType() {
        return returnType;
    }

    @Override
    public SideEffect.Level sideEffectType() {
        return sideEffectType;
    }

    public int[] getParameterRegisters() {
        return parameterRegisters;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getOpName());
        sb.append(" {");
        if (getOpName().contains("/range")) {
            sb.append("r").append(parameterRegisters[0]).append(" .. r")
                            .append(parameterRegisters[parameterRegisters.length - 1]);
        } else {
            if (parameterRegisters.length > 0) {
                for (int register : parameterRegisters) {
                    sb.append("r").append(register).append(", ");
                }
                sb.setLength(sb.length() - 2);
            }
        }
        sb.append("}, ").append(methodDescriptor);

        return sb.toString();
    }

    private void assumeMaximumUnknown(MethodState mstate) {
        for (int i = 0; i < parameterTypes.size(); i++) {
            String type = parameterTypes.get(i);
            if (SmaliClassUtils.isImmutableClass(type)) {
                log.trace(type + " is immutable");
                continue;
            }

            // TODO: add option to mark all class states unknown instead of just method state

            log.debug(type + " is mutable and passed into unresolvable method execution, making Unknown");
            int register = parameterRegisters[i];
            Object value = new UnknownValue(type);
            mstate.pokeRegister(register, value);
        }

        if (!returnType.equals("V")) {
            Object value = new UnknownValue(returnType);
            mstate.assignResultRegister(value);
        }
    }

    private ExecutionContext buildLocalCalleeContext(ExecutionContext callerContext) {
        ExecutionContext result = vm.getRootExecutionContext(methodDescriptor);
        result.setCallDepth(callerContext.getCallDepth() + 1);
        MethodState calleeMethodState = result.getMethodState();
        MethodState callerMethodState = callerContext.getMethodState();
        assignCalleeContextParameters(callerMethodState, calleeMethodState);

        return result;
    }

    private MethodState buildNonLocalCalleeContext(MethodState callerContext) {
        ExecutionContext ectx = new ExecutionContext(vm);
        int parameterSize = VirtualMachine.getParameterSize(parameterTypes);
        int registerCount = parameterSize;
        MethodState calleeContext = new MethodState(ectx, registerCount, parameterSize);
        assignCalleeContextParameters(callerContext, calleeContext);

        return calleeContext;
    }

    private void assignCalleeContextParameters(MethodState callerContext, MethodState calleeContext) {
        for (int parameterIndex = 0; parameterIndex < parameterRegisters.length; parameterIndex++) {
            int callerRegister = parameterRegisters[parameterIndex];
            Object value = callerContext.readRegister(callerRegister);
            calleeContext.assignParameter(parameterIndex, value);
        }
    }

    private void executeLocalMethod(String methodDescriptor, ExecutionContext callerContext) {
        ExecutionContext calleeContext = buildLocalCalleeContext(callerContext);
        ExecutionGraph graph = vm.execute(methodDescriptor, calleeContext);
        if (graph == null) {
            // Problem executing the method. Maybe node visits or call depth exceeded?
            log.info("Problem executing " + methodDescriptor + ", propigating ambiguity.");
            assumeMaximumUnknown(callerContext.getMethodState());

            return;
        }

        updateInstanceAndMutableArguments(callerContext, graph);

        if (!returnType.equals("V")) {
            TIntList terminating = graph.getConnectedTerminatingAddresses();
            // TODO: use getTerminatingRegisterConsensus
            Object consensus = graph.getRegisterConsensus(terminating, MethodState.ReturnRegister);
            callerContext.getMethodState().assignResultRegister(consensus);
        }

        sideEffectType = graph.getStrongestSideEffectType();
    }

    private void executeNonLocalMethod(String methodDescriptor, MethodState callerContext) {
        MethodState calleeContext = buildNonLocalCalleeContext(callerContext);
        boolean allArgumentsKnown = allArgumentsKnown(calleeContext);
        if (allArgumentsKnown && MethodEmulator.canEmulate(methodDescriptor)) {
            sideEffectType = MethodEmulator.emulate(calleeContext, methodDescriptor, getParameterRegisters());
        } else if (allArgumentsKnown && MethodReflector.canReflect(methodDescriptor)) {
            MethodReflector reflector = new MethodReflector(methodDescriptor, returnType, parameterTypes, isStatic);
            reflector.reflect(calleeContext); // playa play

            // Only safe, non-side-effect methods are allowed to be reflected.
            sideEffectType = SideEffect.Level.NONE;
        } else {
            log.debug("Unknown argument(s) or can't find/emulate/reflect " + methodDescriptor
                            + ". Propigating ambiguity.");
            assumeMaximumUnknown(callerContext);

            return;
        }

        if (!isStatic) {
            // Handle updating the instance reference
            Object originalInstance = callerContext.peekRegister(parameterRegisters[0]);
            Object newInstance = calleeContext.getParameter(0);
            if (originalInstance != newInstance) {
                // Instance went from UninitializedInstance class to something else.
                callerContext.assignRegisterAndUpdateIdentities(parameterRegisters[0], newInstance);
            } else {
                // The instance reference could have changed, so mark it as assigned here.
                callerContext.assignRegister(parameterRegisters[0], newInstance);
            }
        }

        if (!returnType.equals("V")) {
            Object returnRegister = calleeContext.readReturnRegister();
            callerContext.assignResultRegister(returnRegister);
        }
    }

    private void updateInstanceAndMutableArguments(ExecutionContext callerContext, ExecutionGraph graph) {
        TIntList terminatingAddresses = graph.getConnectedTerminatingAddresses();
        MethodState mstate = callerContext.getMethodState();
        for (int parameterIndex = 0; parameterIndex < parameterRegisters.length; parameterIndex++) {
            String type = parameterTypes.get(parameterIndex);
            boolean mutable = !SmaliClassUtils.isImmutableClass(type);
            if (!mutable) {
                continue;
            }

            int register = parameterRegisters[parameterIndex];
            Object value = getMutableParameterConsensus(terminatingAddresses, graph, parameterIndex);
            mstate.assignRegister(register, value);
        }
    }

}
