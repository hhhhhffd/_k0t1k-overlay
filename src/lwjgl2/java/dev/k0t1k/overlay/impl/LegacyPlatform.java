package dev.k0t1k.overlay.impl;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

/**
 * LWJGL 2 backend (Minecraft 1.12.2 and older, Java 8). {@code Display.update(boolean)} swaps the
 * buffers and then polls the devices, so the overlay draws on entry. The game reads input by
 * draining the event queues with {@code Keyboard.next()} and {@code Mouse.next()}: the overlay looks
 * at each event first and skips the ones it takes by reading on. Held keys and buttons are
 * reported as released while the overlay is open, and the mouse movement as zero.
 */
final class LegacyPlatform implements LegacyBackend {
    private static final int SHIFT = 1;
    private static final int CONTROL = 2;
    private static final int ALT = 4;
    private static final int SUPER = 8;

    private final Overlay overlay;
    private LegacyGlRenderer renderer;
    /** Set while the overlay itself reads events or changes the grab, so its hooks stay out of it. */
    private int ownCalls;
    /** Modifier keys currently held, as seen in the key events (the game's key state reads released). */
    private int heldMods;
    private boolean savedGrab;
    /** Whether the game wants the mouse grabbed, as of its last call while the overlay held the cursor. */
    private boolean pendingGrab;
    private boolean savedRepeat;

    LegacyPlatform(Overlay overlay) {
        this.overlay = overlay;
    }

    @Override
    public Object call(Class<?> type, String method, Object[] args, Object result, boolean exit) {
        if (ownCalls > 0) {
            return null;
        }
        switch (method) {
            case "update":
                frame();
                return null;
            case "setGrabbed":
                if (!overlay.isOpen()) {
                    return null;
                }
                pendingGrab = (Boolean) args[0];
                return Boolean.TRUE;
            case "next":
                return type == Keyboard.class ? drain(true, (Boolean) result) : drain(false, (Boolean) result);
            case "isKeyDown":
            case "isButtonDown":
                return overlay.isOpen() ? Boolean.FALSE : null;
            case "getDX":
            case "getDY":
            case "getDWheel":
                return overlay.isOpen() ? Integer.valueOf(0) : null;
            default:
                return null;
        }
    }

    private void frame() {
        if (!overlay.buildFrame(Display.getWidth(), Display.getHeight())) {
            return;
        }
        // A shared context (Forge's loading screen runs on its own) has none of our objects.
        if (renderer == null || renderer.caps != GLContext.getCapabilities()) {
            renderer = new LegacyGlRenderer();
            overlay.setRendererName(GL11.glGetString(GL11.GL_RENDERER) + " · OpenGL " + GL11.glGetString(GL11.GL_VERSION));
            int[] dirty = overlay.batch().dirty;
            dirty[0] = dirty[1] = 0;
            dirty[2] = dirty[3] = Batch.ATLAS;
        }
        renderer.draw(overlay.batch());
    }

    // ---- input ----

    /**
     * After the game's {@code next()} returned {@code more}: while the overlay takes the event, reads
     * the next one in its place.
     */
    private Object drain(boolean keyboard, boolean more) {
        ownCalls++;
        try {
            while (more && (keyboard ? takeKey() : takeMouse())) {
                more = keyboard ? Keyboard.next() : Mouse.next();
            }
        } finally {
            ownCalls--;
        }
        return more;
    }

    private boolean takeKey() {
        int code = Keyboard.getEventKey();
        boolean down = Keyboard.getEventKeyState();
        heldMods = withMod(heldMods, code, down);
        int mods = ((heldMods & 0x03) != 0 ? Keys.MOD_SHIFT : 0)
                | ((heldMods & 0x0c) != 0 ? Keys.MOD_CONTROL : 0)
                | ((heldMods & 0x30) != 0 ? Keys.MOD_ALT : 0)
                | ((heldMods & 0xc0) != 0 ? Keys.MOD_SUPER : 0);
        int action = !down ? Keys.RELEASE : Keyboard.isRepeatEvent() ? Keys.REPEAT : Keys.PRESS;
        boolean taken = overlay.key(glfwKey(code), action, mods);
        char typed = Keyboard.getEventCharacter();
        if (down && typed >= ' ' && typed != 127) {
            taken |= overlay.character(typed);
        }
        return taken;
    }

    /** Sets or clears the bit of a modifier key (left and right keys have a bit each). */
    private static int withMod(int held, int code, boolean down) {
        int bit;
        switch (code) {
            case Keyboard.KEY_LSHIFT: bit = 0x01; break;
            case Keyboard.KEY_RSHIFT: bit = 0x02; break;
            case Keyboard.KEY_LCONTROL: bit = 0x04; break;
            case Keyboard.KEY_RCONTROL: bit = 0x08; break;
            case Keyboard.KEY_LMENU: bit = 0x10; break;
            case Keyboard.KEY_RMENU: bit = 0x20; break;
            case Keyboard.KEY_LMETA: bit = 0x40; break;
            case Keyboard.KEY_RMETA: bit = 0x80; break;
            default: return held;
        }
        return down ? held | bit : held & ~bit;
    }

    /** The GLFW code for the keys the overlay uses; any other key gets a code of its own above 1000. */
    private static int glfwKey(int code) {
        switch (code) {
            case Keyboard.KEY_ESCAPE: return Keys.ESCAPE;
            case Keyboard.KEY_RETURN: return Keys.ENTER;
            case Keyboard.KEY_NUMPADENTER: return Keys.KP_ENTER;
            case Keyboard.KEY_TAB: return Keys.TAB;
            case Keyboard.KEY_BACK: return Keys.BACKSPACE;
            case Keyboard.KEY_DELETE: return Keys.DELETE;
            case Keyboard.KEY_RIGHT: return Keys.RIGHT;
            case Keyboard.KEY_LEFT: return Keys.LEFT;
            case Keyboard.KEY_DOWN: return Keys.DOWN;
            case Keyboard.KEY_UP: return Keys.UP;
            case Keyboard.KEY_HOME: return Keys.HOME;
            case Keyboard.KEY_END: return Keys.END;
            case Keyboard.KEY_A: return Keys.A;
            case Keyboard.KEY_C: return Keys.C;
            case Keyboard.KEY_V: return Keys.V;
            case Keyboard.KEY_X: return Keys.X;
            default: return 1000 + code;
        }
    }

    private boolean takeMouse() {
        // LWJGL 2 counts y from the bottom of the window.
        boolean moved = overlay.cursor(Mouse.getEventX(), Display.getHeight() - 1 - Mouse.getEventY());
        int button = Mouse.getEventButton();
        if (button >= 0) {
            return overlay.button(button, Mouse.getEventButtonState() ? Keys.PRESS : Keys.RELEASE);
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            return overlay.scroll(wheel / 120.0);
        }
        return moved;
    }

    // ---- Platform ----

    @Override
    public boolean hasWindow() {
        return Display.isCreated();
    }

    @Override
    public boolean hasGlContext() {
        return true;
    }

    @Override
    public void sizes(int[] out) {
        out[0] = out[2] = Display.getWidth();
        out[1] = out[3] = Display.getHeight();
    }

    @Override
    public float contentScale() {
        return 1;
    }

    @Override
    public String clipboard() {
        try {
            return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        } catch (UnsupportedFlavorException e) {
            return ""; // the clipboard holds something other than text
        } catch (IOException | IllegalStateException e) {
            overlay.report(e);
            return "";
        }
    }

    @Override
    public void setClipboard(String text) {
        try {
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        } catch (IllegalStateException e) {
            overlay.report(e);
        }
    }

    @Override
    public void showCursor() {
        ownCalls++;
        try {
            savedGrab = Mouse.isGrabbed();
            pendingGrab = savedGrab;
            if (savedGrab) {
                Mouse.setGrabbed(false);
                Mouse.setCursorPosition(Display.getWidth() / 2, Display.getHeight() / 2);
            }
            // The game turns key repeat on only for its own screens; the overlay's text fields want it.
            savedRepeat = Keyboard.areRepeatEventsEnabled();
            Keyboard.enableRepeatEvents(true);
        } finally {
            ownCalls--;
        }
    }

    @Override
    public void restoreCursor() {
        ownCalls++;
        try {
            Keyboard.enableRepeatEvents(savedRepeat);
            if (Mouse.isGrabbed() != pendingGrab) {
                Mouse.setGrabbed(pendingGrab);
            }
            // Movement made while the overlay was open must not turn the camera.
            Mouse.getDX();
            Mouse.getDY();
            Mouse.getDWheel();
        } finally {
            ownCalls--;
        }
    }
}
