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
            overlay.hardware().bypass = true;
            try {
                overlay.setRendererName(GL11.glGetString(GL11.GL_RENDERER) + " · OpenGL " + GL11.glGetString(GL11.GL_VERSION));
            } finally {
                overlay.hardware().bypass = false;
            }
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

    /** The GLFW code for a LWJGL 2 key; a key with no counterpart gets a code of its own above 1000. */
    private static int glfwKey(int code) {
        switch (code) {
            case Keyboard.KEY_ESCAPE: return Keys.ESCAPE;
            case Keyboard.KEY_RETURN: return Keys.ENTER;
            case Keyboard.KEY_NUMPADENTER: return Keys.KP_ENTER;
            case Keyboard.KEY_TAB: return Keys.TAB;
            case Keyboard.KEY_BACK: return Keys.BACKSPACE;
            case Keyboard.KEY_DELETE: return Keys.DELETE;
            case Keyboard.KEY_INSERT: return Keys.INSERT;
            case Keyboard.KEY_RIGHT: return Keys.RIGHT;
            case Keyboard.KEY_LEFT: return Keys.LEFT;
            case Keyboard.KEY_DOWN: return Keys.DOWN;
            case Keyboard.KEY_UP: return Keys.UP;
            case Keyboard.KEY_HOME: return Keys.HOME;
            case Keyboard.KEY_END: return Keys.END;
            case Keyboard.KEY_PRIOR: return Keys.PAGE_UP;
            case Keyboard.KEY_NEXT: return Keys.PAGE_DOWN;
            case Keyboard.KEY_SPACE: return 32;
            case Keyboard.KEY_APOSTROPHE: return 39;
            case Keyboard.KEY_COMMA: return 44;
            case Keyboard.KEY_MINUS: return Keys.MINUS;
            case Keyboard.KEY_PERIOD: return 46;
            case Keyboard.KEY_SLASH: return 47;
            case Keyboard.KEY_SEMICOLON: return 59;
            case Keyboard.KEY_EQUALS: return Keys.EQUAL;
            case Keyboard.KEY_LBRACKET: return 91;
            case Keyboard.KEY_BACKSLASH: return 92;
            case Keyboard.KEY_RBRACKET: return 93;
            case Keyboard.KEY_GRAVE: return 96;
            case Keyboard.KEY_CAPITAL: return 280;
            case Keyboard.KEY_SCROLL: return 281;
            case Keyboard.KEY_NUMLOCK: return 282;
            case Keyboard.KEY_SYSRQ: return 283;
            case Keyboard.KEY_PAUSE: return 284;
            case Keyboard.KEY_DECIMAL: return 330;
            case Keyboard.KEY_DIVIDE: return 331;
            case Keyboard.KEY_MULTIPLY: return 332;
            case Keyboard.KEY_SUBTRACT: return Keys.KP_SUBTRACT;
            case Keyboard.KEY_ADD: return Keys.KP_ADD;
            case Keyboard.KEY_NUMPADEQUALS: return 336;
            case Keyboard.KEY_APPS: return Keys.MENU;
            case Keyboard.KEY_LSHIFT: return 340;
            case Keyboard.KEY_LCONTROL: return 341;
            case Keyboard.KEY_LMENU: return 342;
            case Keyboard.KEY_LMETA: return 343;
            case Keyboard.KEY_RSHIFT: return 344;
            case Keyboard.KEY_RCONTROL: return 345;
            case Keyboard.KEY_RMENU: return 346;
            case Keyboard.KEY_RMETA: return 347;
            case Keyboard.KEY_0: return 48;
            case Keyboard.KEY_NUMPAD0: return 320;
            case Keyboard.KEY_1: return 49;
            case Keyboard.KEY_NUMPAD1: return 321;
            case Keyboard.KEY_2: return 50;
            case Keyboard.KEY_NUMPAD2: return 322;
            case Keyboard.KEY_3: return 51;
            case Keyboard.KEY_NUMPAD3: return 323;
            case Keyboard.KEY_4: return 52;
            case Keyboard.KEY_NUMPAD4: return 324;
            case Keyboard.KEY_5: return 53;
            case Keyboard.KEY_NUMPAD5: return 325;
            case Keyboard.KEY_6: return 54;
            case Keyboard.KEY_NUMPAD6: return 326;
            case Keyboard.KEY_7: return 55;
            case Keyboard.KEY_NUMPAD7: return 327;
            case Keyboard.KEY_8: return 56;
            case Keyboard.KEY_NUMPAD8: return 328;
            case Keyboard.KEY_9: return 57;
            case Keyboard.KEY_NUMPAD9: return 329;
            case Keyboard.KEY_A: return 65;
            case Keyboard.KEY_B: return 66;
            case Keyboard.KEY_C: return 67;
            case Keyboard.KEY_D: return 68;
            case Keyboard.KEY_E: return 69;
            case Keyboard.KEY_F: return 70;
            case Keyboard.KEY_G: return 71;
            case Keyboard.KEY_H: return 72;
            case Keyboard.KEY_I: return 73;
            case Keyboard.KEY_J: return 74;
            case Keyboard.KEY_K: return 75;
            case Keyboard.KEY_L: return 76;
            case Keyboard.KEY_M: return 77;
            case Keyboard.KEY_N: return 78;
            case Keyboard.KEY_O: return 79;
            case Keyboard.KEY_P: return 80;
            case Keyboard.KEY_Q: return 81;
            case Keyboard.KEY_R: return 82;
            case Keyboard.KEY_S: return 83;
            case Keyboard.KEY_T: return 84;
            case Keyboard.KEY_U: return 85;
            case Keyboard.KEY_V: return 86;
            case Keyboard.KEY_W: return 87;
            case Keyboard.KEY_X: return 88;
            case Keyboard.KEY_Y: return 89;
            case Keyboard.KEY_Z: return 90;
            case Keyboard.KEY_F1: return 290;
            case Keyboard.KEY_F2: return 291;
            case Keyboard.KEY_F3: return 292;
            case Keyboard.KEY_F4: return 293;
            case Keyboard.KEY_F5: return 294;
            case Keyboard.KEY_F6: return 295;
            case Keyboard.KEY_F7: return 296;
            case Keyboard.KEY_F8: return 297;
            case Keyboard.KEY_F9: return 298;
            case Keyboard.KEY_F10: return 299;
            case Keyboard.KEY_F11: return 300;
            case Keyboard.KEY_F12: return 301;
            case Keyboard.KEY_F13: return 302;
            case Keyboard.KEY_F14: return 303;
            case Keyboard.KEY_F15: return 304;
            default: return 1000 + code;
        }
    }

    private boolean takeMouse() {
        // LWJGL 2 counts y from the bottom of the window.
        boolean moved = overlay.cursor(Mouse.getEventX(), Display.getHeight() - 1 - Mouse.getEventY());
        int button = Mouse.getEventButton();
        if (button >= 0) {
            // Left, right, middle, then the side buttons (back, forward): GLFW's numbering already.
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
    public int monitorWidth() {
        return Display.getDesktopDisplayMode().getWidth();
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
    public boolean gameCursorVisible() {
        return !Mouse.isGrabbed();
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
