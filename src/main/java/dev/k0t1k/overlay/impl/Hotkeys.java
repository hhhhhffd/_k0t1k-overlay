package dev.k0t1k.overlay.impl;

/**
 * Key combinations the player can rebind. A combination is {@code modifiers << 16 | key} with the
 * {@link Keys} codes (GLFW's), and 0 means unbound.
 */
final class Hotkeys {
    private static final int MODS = Keys.MOD_SHIFT | Keys.MOD_CONTROL | Keys.MOD_ALT | Keys.MOD_SUPER;
    private static final int F1 = 290;
    private static final int LEFT_SHIFT = 340;
    private static final int RIGHT_SUPER = 347;
    private static final int MENU = 348;

    static final int OPEN = Keys.MOD_SHIFT << 16 | Keys.TAB;
    static final int RECORD = Keys.MOD_SHIFT << 16 | (F1 + 9);
    static final int SAVE = Keys.MOD_SHIFT << 16 | Keys.F2;

    private Hotkeys() {
    }

    static int of(int key, int mods) {
        return (mods & MODS) << 16 | key;
    }

    static boolean matches(int combo, int key, int mods) {
        return combo != 0 && combo == of(key, mods);
    }

    /** Shift, Control, Alt and Super on their own cannot be a combination. */
    static boolean isModifier(int key) {
        return key >= LEFT_SHIFT && key <= RIGHT_SUPER || key == MENU;
    }

    /** Whether a key may be bound with these modifiers: a plain letter or digit would break typing. */
    static boolean allowed(int key, int mods) {
        return !isModifier(key) && ((mods & MODS) != 0 || key >= F1);
    }

    static String name(int combo) {
        if (combo == 0) {
            return "—";
        }
        int mods = combo >>> 16;
        StringBuilder out = new StringBuilder();
        if ((mods & Keys.MOD_CONTROL) != 0) {
            out.append("Ctrl+");
        }
        if ((mods & Keys.MOD_ALT) != 0) {
            out.append("Alt+");
        }
        if ((mods & Keys.MOD_SHIFT) != 0) {
            out.append("Shift+");
        }
        if ((mods & Keys.MOD_SUPER) != 0) {
            out.append("Super+");
        }
        return out.append(keyName(combo & 0xffff)).toString();
    }

    private static String keyName(int key) {
        if (key >= F1 && key <= F1 + 24) {
            return "F" + (key - F1 + 1);
        }
        if (key >= 'A' && key <= 'Z' || key >= '0' && key <= '9') {
            return String.valueOf((char) key);
        }
        switch (key) {
            case Keys.TAB: return "Tab";
            case Keys.ENTER: return "Enter";
            case Keys.BACKSPACE: return "Backspace";
            case Keys.DELETE: return "Delete";
            case Keys.RIGHT: return "Right";
            case Keys.LEFT: return "Left";
            case Keys.DOWN: return "Down";
            case Keys.UP: return "Up";
            case Keys.PAGE_UP: return "Page Up";
            case Keys.PAGE_DOWN: return "Page Down";
            case Keys.HOME: return "Home";
            case Keys.END: return "End";
            case 32: return "Space";
            case 260: return "Insert";
            case 283: return "Print Screen";
            case 284: return "Pause";
            default: return "Key " + key;
        }
    }
}
