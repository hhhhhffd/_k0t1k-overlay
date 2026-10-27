package dev.k0t1k.overlay.impl;

/**
 * The LWJGL 2 backend (Minecraft 1.12.2 and older), kept behind an interface because it is compiled
 * against LWJGL 2, whose classes differ from LWJGL 3, and is only loaded when the game uses it.
 */
interface LegacyBackend extends Platform {
    /**
     * A hooked LWJGL 2 call on {@code type} ({@code Display}, {@code Keyboard} or {@code Mouse}),
     * answered as {@link Overlay#apply} does; {@code result} is set on exit.
     */
    Object call(Class<?> type, String method, Object[] args, Object result, boolean exit);
}
