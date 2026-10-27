package dev.k0t1k.overlay.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.glfw.GLFWCharCallbackI;
import org.lwjgl.glfw.GLFWCharModsCallbackI;
import org.lwjgl.glfw.GLFWCursorPosCallbackI;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.lwjgl.glfw.GLFWMouseButtonCallbackI;
import org.lwjgl.glfw.GLFWScrollCallbackI;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GLFW backend (Minecraft 1.13 to 26.2). The game's input callbacks are wrapped as it installs them;
 * each wrapper offers the event to the overlay first and forwards it to the game unless consumed.
 */
final class GlfwPlatform implements Platform {
    private final Overlay overlay;
    long window;
    private boolean ownCursorCall;
    private int savedMode = GLFW_CURSOR_NORMAL;
    /** The cursor mode the game asked for while the overlay held the cursor. */
    private int pendingMode = GLFW_CURSOR_NORMAL;
    private double savedX;
    private double savedY;
    /**
     * Nesting depth of the wrappers. A mod that chains to the callback it replaced calls a wrapper
     * from inside another one; only the outermost one may offer the event to the overlay.
     */
    private int depth;

    private final IntBuffer w = ints();
    private final IntBuffer h = ints();
    private final FloatBuffer sx = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final FloatBuffer sy = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final DoubleBuffer cx = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asDoubleBuffer();
    private final DoubleBuffer cy = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asDoubleBuffer();

    GlfwPlatform(Overlay overlay) {
        this.overlay = overlay;
    }

    private static IntBuffer ints() {
        return ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    @Override
    public boolean hasWindow() {
        return window != 0;
    }

    @Override
    public boolean hasGlContext() {
        return glfwGetCurrentContext() != 0;
    }

    @Override
    public void sizes(int[] out) {
        glfwGetFramebufferSize(window, w, h);
        out[0] = w.get(0);
        out[1] = h.get(0);
        glfwGetWindowSize(window, w, h);
        out[2] = w.get(0);
        out[3] = h.get(0);
    }

    @Override
    public float contentScale() {
        glfwGetWindowContentScale(window, sx, sy);
        return Math.max(1, sx.get(0));
    }

    @Override
    public String clipboard() {
        return glfwGetClipboardString(window);
    }

    @Override
    public void setClipboard(String text) {
        glfwSetClipboardString(window, text);
    }

    @Override
    public void showCursor() {
        savedMode = glfwGetInputMode(window, GLFW_CURSOR);
        pendingMode = savedMode;
        glfwGetCursorPos(window, cx, cy);
        savedX = cx.get(0);
        savedY = cy.get(0);
        setMode(GLFW_CURSOR_NORMAL);
        if (savedMode == GLFW_CURSOR_DISABLED) {
            glfwGetWindowSize(window, w, h);
            glfwSetCursorPos(window, w.get(0) / 2.0, h.get(0) / 2.0);
        }
    }

    @Override
    public void restoreCursor() {
        setMode(pendingMode);
        if (pendingMode == GLFW_CURSOR_DISABLED && savedMode == GLFW_CURSOR_DISABLED) {
            // Put the virtual cursor back where the game last saw it, so the camera does not jump.
            glfwSetCursorPos(window, savedX, savedY);
        } else if (pendingMode == GLFW_CURSOR_DISABLED) {
            // The game grabbed the mouse meanwhile and expects it centred, as its grab call put it.
            glfwGetWindowSize(window, w, h);
            glfwSetCursorPos(window, w.get(0) / 2.0, h.get(0) / 2.0);
        }
    }

    private void setMode(int mode) {
        ownCursorCall = true;
        try {
            glfwSetInputMode(window, GLFW_CURSOR, mode);
        } finally {
            ownCursorCall = false;
        }
    }

    /** Hook on {@code glfwSetInputMode}: returns true to skip the game's call. */
    boolean onInputMode(int mode, int value) {
        if (mode != GLFW_CURSOR || ownCursorCall || !overlay.isOpen()) {
            return false;
        }
        pendingMode = value;
        return true;
    }

    /**
     * Hook on the {@code glfwSet*Callback} methods: returns the callback to install instead. The setter
     * decides the type, since one object may implement several callback interfaces.
     */
    Object wrap(String setter, Object callback) {
        if ("glfwSetKeyCallback".equals(setter) && callback instanceof GLFWKeyCallbackI) {
            final GLFWKeyCallbackI game = (GLFWKeyCallbackI) callback;
            return (GLFWKeyCallbackI) (win, key, scancode, action, mods) -> {
                if (!offer(() -> overlay.key(key, action, mods))) {
                    pass(() -> game.invoke(win, key, scancode, action, mods));
                }
            };
        }
        if ("glfwSetCharModsCallback".equals(setter) && callback instanceof GLFWCharModsCallbackI) {
            final GLFWCharModsCallbackI game = (GLFWCharModsCallbackI) callback;
            return (GLFWCharModsCallbackI) (win, codepoint, mods) -> {
                if (!offer(() -> overlay.character(codepoint))) {
                    pass(() -> game.invoke(win, codepoint, mods));
                }
            };
        }
        if ("glfwSetCharCallback".equals(setter) && callback instanceof GLFWCharCallbackI) {
            final GLFWCharCallbackI game = (GLFWCharCallbackI) callback;
            return (GLFWCharCallbackI) (win, codepoint) -> {
                if (!offer(() -> overlay.character(codepoint))) {
                    pass(() -> game.invoke(win, codepoint));
                }
            };
        }
        if ("glfwSetMouseButtonCallback".equals(setter) && callback instanceof GLFWMouseButtonCallbackI) {
            final GLFWMouseButtonCallbackI game = (GLFWMouseButtonCallbackI) callback;
            return (GLFWMouseButtonCallbackI) (win, button, action, mods) -> {
                if (!offer(() -> overlay.button(button, action))) {
                    pass(() -> game.invoke(win, button, action, mods));
                }
            };
        }
        if ("glfwSetCursorPosCallback".equals(setter) && callback instanceof GLFWCursorPosCallbackI) {
            final GLFWCursorPosCallbackI game = (GLFWCursorPosCallbackI) callback;
            return (GLFWCursorPosCallbackI) (win, x, y) -> {
                if (!offer(() -> overlay.cursor(x, y))) {
                    pass(() -> game.invoke(win, x, y));
                }
            };
        }
        if ("glfwSetScrollCallback".equals(setter) && callback instanceof GLFWScrollCallbackI) {
            final GLFWScrollCallbackI game = (GLFWScrollCallbackI) callback;
            return (GLFWScrollCallbackI) (win, dx, dy) -> {
                if (!offer(() -> overlay.scroll(dy))) {
                    pass(() -> game.invoke(win, dx, dy));
                }
            };
        }
        return callback;
    }

    private interface Handler {
        boolean handle();
    }

    /** Offers an event to the overlay; returns true when it consumed it. Overlay failures never block input. */
    private boolean offer(Handler handler) {
        if (depth > 0) {
            return false;
        }
        try {
            return handler.handle();
        } catch (Throwable e) {
            overlay.fail(e);
            return false;
        }
    }

    private void pass(Runnable game) {
        depth++;
        try {
            game.run();
        } finally {
            depth--;
        }
    }
}
