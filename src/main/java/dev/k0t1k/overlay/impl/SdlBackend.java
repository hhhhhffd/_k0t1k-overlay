package dev.k0t1k.overlay.impl;

/**
 * The SDL window backend (Minecraft 26.3+), kept behind an interface because it is compiled against
 * LWJGL 3.4 (the first with SDL bindings) and is only loaded when the game uses SDL.
 */
interface SdlBackend extends Platform {
    /** A hooked SDL call, answered as {@link Overlay#apply} does; {@code result} is set on exit. */
    Object call(String method, Object[] args, Object result, boolean exit);
}
