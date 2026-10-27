package dev.k0t1k.overlay.impl;

/**
 * The Vulkan renderer, kept behind an interface because it is compiled against a newer LWJGL (3.3)
 * than the rest of the overlay (3.1, for Minecraft 1.13) and is only loaded when the game uses Vulkan.
 */
interface VulkanBackend {
    /**
     * A hooked Vulkan call, answered as {@link Overlay#apply} does. {@code result} is the return value
     * on exit and null before the call.
     */
    Object call(String method, Object[] args, Object result, boolean exit);

    /** Stops drawing after a failure; later presents pass through untouched. */
    void disable();
}
