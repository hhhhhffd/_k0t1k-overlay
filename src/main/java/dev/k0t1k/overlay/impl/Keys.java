package dev.k0t1k.overlay.impl;

/**
 * Key codes and modifier bits the overlay works with. They are GLFW's values; a backend for another
 * window library translates its own codes into these.
 */
final class Keys {
    static final int A = 65;
    static final int C = 67;
    static final int V = 86;
    static final int X = 88;
    static final int ESCAPE = 256;
    static final int ENTER = 257;
    static final int TAB = 258;
    static final int BACKSPACE = 259;
    static final int DELETE = 261;
    static final int RIGHT = 262;
    static final int LEFT = 263;
    static final int DOWN = 264;
    static final int UP = 265;
    static final int HOME = 268;
    static final int END = 269;
    static final int KP_ENTER = 335;

    static final int MOD_SHIFT = 1;
    static final int MOD_CONTROL = 2;
    static final int MOD_ALT = 4;
    static final int MOD_SUPER = 8;

    static final int RELEASE = 0;

    private Keys() {
    }
}
