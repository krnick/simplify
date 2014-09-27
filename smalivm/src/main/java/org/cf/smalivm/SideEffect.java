package org.cf.smalivm;

public class SideEffect {

    public enum Level {
        NONE, // reflected, emulated, white listed, or otherwise safe
        WEAK, // member variable modification
        STRONG, // not white listed, unknown
    };

}
