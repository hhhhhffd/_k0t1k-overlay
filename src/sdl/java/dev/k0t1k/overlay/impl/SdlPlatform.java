package dev.k0t1k.overlay.impl;

import java.nio.IntBuffer;

import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_KeyboardEvent;
import org.lwjgl.sdl.SDL_MouseButtonEvent;
import org.lwjgl.sdl.SDL_MouseMotionEvent;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.sdl.SDLClipboard.SDL_GetClipboardText;
import static org.lwjgl.sdl.SDLClipboard.SDL_SetClipboardText;
import static org.lwjgl.sdl.SDLEvents.SDL_GetWindowFromEvent;
import static org.lwjgl.sdl.SDLKeyboard.SDL_StartTextInput;
import static org.lwjgl.sdl.SDLKeyboard.SDL_StopTextInput;
import static org.lwjgl.sdl.SDLKeyboard.SDL_TextInputActive;
import static org.lwjgl.sdl.SDLMouse.SDL_GetWindowRelativeMouseMode;
import static org.lwjgl.sdl.SDLMouse.SDL_SetWindowRelativeMouseMode;
import static org.lwjgl.sdl.SDLMouse.SDL_WarpMouseInWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowDisplayScale;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSize;
import static org.lwjgl.sdl.SDLVideo.SDL_GetWindowSizeInPixels;

/**
 * SDL3 backend (Minecraft 26.3+). The game drains events with {@code SDL_PollEvent}; after each one
 * returns, the overlay looks at the event and, when it takes it, rewrites its type to
 * {@code SDL_EVENT_USER}, which the game ignores. Cursor and text input calls the game makes while the
 * overlay is open are held back and applied when it closes.
 */
final class SdlPlatform implements SdlBackend {
    private static final int KEY_DOWN = 0x300;
    private static final int KEY_UP = 0x301;
    private static final int TEXT_EDITING = 0x302;
    private static final int TEXT_INPUT = 0x303;
    private static final int MOUSE_MOTION = 0x400;
    private static final int BUTTON_DOWN = 0x401;
    private static final int BUTTON_UP = 0x402;
    private static final int WHEEL = 0x403;
    /**
     * SDL_EVENT_LAST: in none of the game's event switches, so a rewritten event is dropped. Unlike
     * SDL_EVENT_USER it is never handed out by SDL_RegisterEvents, so it cannot pose as a custom event.
     */
    private static final int IGNORED = 0xffff;

    private static final int KMOD_SHIFT = 0x0003;
    private static final int KMOD_CTRL = 0x00c0;
    private static final int KMOD_ALT = 0x0300;
    private static final int KMOD_GUI = 0x0c00;

    private final Overlay overlay;
    private long window;
    private boolean ownCall;
    private boolean savedRelative;
    private boolean pendingRelative;
    private boolean pendingText;
    /** The game call the overlay just skipped; its exit hook reports success instead of SDL's false. */
    private String skipped;

    SdlPlatform(Overlay overlay) {
        this.overlay = overlay;
    }

    @Override
    public Object call(String method, Object[] args, Object result, boolean exit) {
        if ("SDL_PollEvent".equals(method)) {
            // SDL_PollEvent(NULL) only asks whether events are pending. Ixeris polls SDL on its own
            // thread and hands the events on; the overlay looks at them when the game reads them.
            return exit && Boolean.TRUE.equals(result) && args[0] != null && overlay.onRenderThread()
                    ? onEvent((SDL_Event) args[0]) : null;
        }
        if (exit) {
            if (method.equals(skipped)) {
                skipped = null;
                return Boolean.TRUE;
            }
            return null;
        }
        if (args.length > 0 && args[0] instanceof Long && (Long) args[0] != 0) {
            window = (Long) args[0];
        }
        switch (method) {
            case "SDL_GL_SwapWindow":
                overlay.glFrame();
                return null;
            case "SDL_SetWindowRelativeMouseMode":
                if (holdBack()) {
                    pendingRelative = (Boolean) args[1];
                    return skip(method);
                }
                return null;
            case "SDL_WarpMouseInWindow":
                return holdBack() ? Boolean.TRUE : null;
            case "SDL_StartTextInput":
            case "SDL_StopTextInput":
                if (holdBack()) {
                    pendingText = "SDL_StartTextInput".equals(method);
                    return skip(method);
                }
                return null;
            default:
                return null;
        }
    }

    private Object skip(String method) {
        skipped = method;
        return Boolean.TRUE;
    }

    /** Ixeris replays the render thread's calls, the overlay's own included, on its polling thread. */
    private boolean holdBack() {
        return !ownCall && overlay.isOpen() && overlay.onRenderThread();
    }

    private Object onEvent(SDL_Event event) {
        long from = SDL_GetWindowFromEvent(event);
        if (from != 0) {
            window = from;
        }
        boolean taken;
        int type = event.type();
        switch (type) {
            case KEY_DOWN:
            case KEY_UP: {
                SDL_KeyboardEvent key = event.key();
                int action = type == KEY_UP ? Keys.RELEASE : key.repeat() ? Keys.REPEAT : Keys.PRESS;
                taken = overlay.key(keyCode(key.scancode()), action, mods(key.mod()));
                break;
            }
            case TEXT_INPUT:
                taken = overlay.isOpen();
                if (taken) {
                    String text = event.text().textString();
                    if (text != null) {
                        text.codePoints().forEach(overlay::character);
                    }
                }
                break;
            case TEXT_EDITING:
                taken = overlay.isOpen();
                break;
            case MOUSE_MOTION: {
                SDL_MouseMotionEvent motion = event.motion();
                // Taken while open also stops the camera, which turns by xrel while the game thinks
                // the mouse is grabbed.
                taken = overlay.cursor(motion.x(), motion.y());
                break;
            }
            case BUTTON_DOWN:
            case BUTTON_UP: {
                SDL_MouseButtonEvent button = event.button();
                taken = overlay.button(mouseButton(button.button()), type == BUTTON_DOWN ? Keys.PRESS : Keys.RELEASE);
                break;
            }
            case WHEEL:
                taken = overlay.scroll(event.wheel().y());
                break;
            default:
                taken = false;
        }
        if (taken) {
            event.type(IGNORED);
        }
        return null;
    }

    /** SDL scancode (USB HID usage) to the overlay's key codes, which are GLFW's. */
    private static int keyCode(int scancode) {
        if (scancode >= 4 && scancode <= 29) {
            return Keys.A + scancode - 4;
        }
        switch (scancode) {
            case 40:
                return Keys.ENTER;
            case 41:
                return Keys.ESCAPE;
            case 42:
                return Keys.BACKSPACE;
            case 43:
                return Keys.TAB;
            case 59:
                return Keys.F2;
            case 74:
                return Keys.HOME;
            case 76:
                return Keys.DELETE;
            case 77:
                return Keys.END;
            case 79:
                return Keys.RIGHT;
            case 80:
                return Keys.LEFT;
            case 81:
                return Keys.DOWN;
            case 82:
                return Keys.UP;
            case 88:
                return Keys.KP_ENTER;
            default:
                // Keys the overlay has no use for, kept distinct so press/release pairs still match.
                return 100000 + scancode;
        }
    }

    private static int mods(short mod) {
        int mods = 0;
        if ((mod & KMOD_SHIFT) != 0) {
            mods |= Keys.MOD_SHIFT;
        }
        if ((mod & KMOD_CTRL) != 0) {
            mods |= Keys.MOD_CONTROL;
        }
        if ((mod & KMOD_ALT) != 0) {
            mods |= Keys.MOD_ALT;
        }
        if ((mod & KMOD_GUI) != 0) {
            mods |= Keys.MOD_SUPER;
        }
        return mods;
    }

    /** SDL buttons are 1 left, 2 middle, 3 right; GLFW's are 0 left, 1 right, 2 middle. */
    private static int mouseButton(byte button) {
        switch (button) {
            case 1:
                return 0;
            case 2:
                return 2;
            case 3:
                return 1;
            default:
                return button;
        }
    }

    @Override
    public boolean hasWindow() {
        return window != 0;
    }

    @Override
    public boolean hasGlContext() {
        return SDL_GL_GetCurrentContext() != 0;
    }

    @Override
    public void sizes(int[] out) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            SDL_GetWindowSizeInPixels(window, w, h);
            out[0] = w.get(0);
            out[1] = h.get(0);
            SDL_GetWindowSize(window, w, h);
            out[2] = w.get(0);
            out[3] = h.get(0);
        }
    }

    @Override
    public float contentScale() {
        return Math.max(1, SDL_GetWindowDisplayScale(window));
    }

    @Override
    public String clipboard() {
        return SDL_GetClipboardText();
    }

    @Override
    public void setClipboard(String text) {
        SDL_SetClipboardText(text);
    }

    @Override
    public void showCursor() {
        savedRelative = SDL_GetWindowRelativeMouseMode(window);
        pendingRelative = savedRelative;
        pendingText = SDL_TextInputActive(window);
        ownCall = true;
        try {
            SDL_SetWindowRelativeMouseMode(window, false);
            if (savedRelative) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer w = stack.mallocInt(1);
                    IntBuffer h = stack.mallocInt(1);
                    SDL_GetWindowSize(window, w, h);
                    SDL_WarpMouseInWindow(window, w.get(0) / 2f, h.get(0) / 2f);
                }
            }
            // The overlay's text fields need text input events, whatever the game had.
            SDL_StartTextInput(window);
        } finally {
            ownCall = false;
        }
    }

    @Override
    public void restoreCursor() {
        ownCall = true;
        try {
            if (!pendingText) {
                SDL_StopTextInput(window);
            }
            SDL_SetWindowRelativeMouseMode(window, pendingRelative);
        } finally {
            ownCall = false;
        }
    }
}
