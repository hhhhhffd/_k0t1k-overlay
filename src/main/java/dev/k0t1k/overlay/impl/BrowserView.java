package dev.k0t1k.overlay.impl;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * The browser tab: a real Chromium page (see {@link Browser}) with back, forward, reload, an address
 * bar and quick links. Mouse and keyboard over the page go to Chromium; the page's pixels are drawn as
 * the batch's image, so this works where the overlay draws with OpenGL.
 */
final class BrowserView {
    static final String HOME = "https://minecraft.wiki/";
    private static final String[][] LINKS = {
        {"Minecraft Wiki", HOME},
        {"Modrinth", "https://modrinth.com/"},
        {"CurseForge", "https://www.curseforge.com/minecraft"},
        {"YouTube", "https://www.youtube.com/"},
    };

    private final Browser browser;
    private final Strings strings;
    private final Overlay overlay;
    private final TextEdit address = new TextEdit();
    private final Browser.Frame frame = new Browser.Frame();
    /** Stands for the page in {@link Ui#focus}: the page has the keyboard while it is focused. */
    private final Object pageFocus = new Object();
    private String shownUrl = "";
    private float lastX = -1;
    private float lastY = -1;
    private boolean pressed;

    BrowserView(Browser browser, Strings strings, Overlay overlay) {
        this.browser = browser;
        this.strings = strings;
        this.overlay = overlay;
    }

    void draw(Ui ui, float x, float y, float w, float h) {
        BrowserEngine engine = browser.engine();
        float barH = 34;
        float bx = x;
        String[] icons = {"←", "→", "↻"};
        for (int i = 0; i < icons.length; i++) {
            if (ui.button(bx, y, barH, barH, icons[i], false) && engine != null) {
                if (i == 0) {
                    engine.back();
                } else if (i == 1) {
                    engine.forward();
                } else {
                    engine.reload();
                }
            }
            bx += barH + 6;
        }
        if (engine != null && ui.focus != address && !engine.url().equals(shownUrl)) {
            shownUrl = engine.url();
            address.set(shownUrl);
        }
        float linksW = 0;
        for (String[] link : LINKS) {
            linksW += ui.measure(ui.bold, 13, link[0]) + 20 + 4;
        }
        boolean roomForLinks = w - (bx - x) - linksW > 260;
        float fieldW = x + w - bx - (roomForLinks ? linksW + 6 : 0);
        ui.field(address, bx, y, fieldW, barH, false, strings.get("browser.address"));
        if (ui.focus == address && pressed(ui, Keys.ENTER, Keys.KP_ENTER)) {
            open(target(address.value()));
            ui.focus = pageFocus;
        }
        if (roomForLinks) {
            float lx = bx + fieldW + 6;
            for (String[] link : LINKS) {
                float lw = ui.measure(ui.bold, 13, link[0]) + 20;
                if (ui.pill(lx, y + 3, lw, barH - 6, link[0], false)) {
                    open(link[1]);
                }
                lx += lw + 4;
            }
        }

        float py = y + barH + 10;
        float ph = h - barH - 10;
        ui.panel(x, py, w, ph, 10, Ui.BASE, Ui.LINE);
        if (engine == null || browser.state != Browser.State.READY) {
            drawSetup(ui, x, py, w, ph);
            return;
        }
        if (!overlay.imagesSupported()) {
            center(ui, x, py, w, ph, strings.get("browser.glOnly"), Ui.FAINT);
            return;
        }
        float vx = x + 1;
        float vy = py + 1;
        float vw = w - 2;
        float vh = ph - 2;
        engine.resize(Math.max(1, Math.round(vw * ui.scale)), Math.max(1, Math.round(vh * ui.scale)));
        long version = engine.frame(frame.version, frame.pixels, frame.size);
        if (version == -1) {
            frame.pixels = java.nio.ByteBuffer.allocateDirect(frame.size[0] * frame.size[1] * 4);
            version = engine.frame(frame.version, frame.pixels, frame.size);
        }
        if (version > 0) {
            frame.version = version;
            Batch batch = ui.batch;
            batch.image = frame.pixels;
            batch.imageWidth = frame.size[0];
            batch.imageHeight = frame.size[1];
            batch.imageVersion = version;
            ui.image(vx, vy, vw, vh);
        } else {
            center(ui, x, py, w, ph, strings.get("browser.loading"), Ui.FAINT);
        }
        input(ui, engine, vx, vy, vw, vh);
        if (engine.loading()) {
            ui.rect(vx, vy, vw * (0.3f + 0.4f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 400.0))), 2, Ui.ACCENT);
        }
    }

    private void drawSetup(Ui ui, float x, float y, float w, float h) {
        Browser.State state = browser.state;
        float cx = x + w / 2;
        float cy = y + h / 2;
        switch (state) {
            case UNSUPPORTED:
                center(ui, x, y, w, h, strings.get("browser.unsupported"), Ui.FAINT);
                return;
            case MISSING:
            case FAILED: {
                String text = state == Browser.State.FAILED ? strings.format("browser.failed", browser.error)
                        : strings.format("browser.install", browser.size());
                float tw = Math.min(ui.measure(ui.regular, 14, text), w - 60);
                ui.textFit(ui.regular, 14, cx - tw / 2, cy - 40, tw, state == Browser.State.FAILED ? Ui.DANGER : Ui.MUTED, text);
                String label = strings.get(state == Browser.State.FAILED ? "browser.retry" : "browser.download");
                float bw = ui.buttonWidth(label);
                if (ui.button(cx - bw / 2, cy - 6, bw, 34, label, true)) {
                    browser.start(HOME);
                }
                return;
            }
            case STARTING:
                if (browser.engine() == null) {
                    browser.start(HOME);
                }
                center(ui, x, y, w, h, strings.get("browser.starting"), Ui.FAINT);
                return;
            case INSTALLING:
            default: {
                String text = strings.format("browser.downloading", Math.round(browser.progress * 100));
                float tw = ui.measure(ui.regular, 14, text);
                ui.text(ui.regular, 14, cx - tw / 2, cy - 30, Ui.MUTED, text);
                float bw = Math.min(360, w - 80);
                ui.round(cx - bw / 2, cy, bw, 8, 4, Ui.RAISED);
                ui.round(cx - bw / 2, cy, bw * browser.progress, 8, 4, Ui.ACCENT);
            }
        }
    }

    private static void center(Ui ui, float x, float y, float w, float h, String text, int color) {
        float tw = Math.min(ui.measure(ui.regular, 14, text), w - 40);
        ui.textFit(ui.regular, 14, x + (w - tw) / 2, y + h / 2 - 10, tw, color, text);
    }

    private void open(String url) {
        BrowserEngine engine = browser.engine();
        if (engine != null) {
            engine.load(url);
            shownUrl = url;
        }
    }

    /** An address as typed: a URL (with or without https://), or else a web search. */
    static String target(String typed) {
        String value = typed.trim();
        if (value.isEmpty()) {
            return HOME;
        }
        if (value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            return value;
        }
        if (!value.contains(" ") && value.matches("^[^/]+\\.[a-zA-Z]{2,}(/.*)?$")) {
            return "https://" + value;
        }
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- input to the page ----

    private void input(Ui ui, BrowserEngine engine, float x, float y, float w, float h) {
        boolean over = ui.hover(x, y, w, h);
        int px = Math.round((ui.mouseX - x) * ui.scale);
        int py = Math.round((ui.mouseY - y) * ui.scale);
        if (over && (ui.mouseX != lastX || ui.mouseY != lastY)) {
            lastX = ui.mouseX;
            lastY = ui.mouseY;
            engine.mouse(pressed ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED, px, py,
                    pressed ? InputEvent.BUTTON1_DOWN_MASK : 0, 0, MouseEvent.NOBUTTON);
        }
        if (over && ui.clickedIn(x, y, w, h)) {
            ui.focus = pageFocus;
            engine.focus(true);
            pressed = true;
            engine.mouse(MouseEvent.MOUSE_PRESSED, px, py, InputEvent.BUTTON1_DOWN_MASK, ui.doubleClicked ? 2 : 1, MouseEvent.BUTTON1);
        }
        if (pressed && ui.released) {
            pressed = false;
            engine.mouse(MouseEvent.MOUSE_RELEASED, px, py, 0, 1, MouseEvent.BUTTON1);
        }
        float scroll = ui.takeScroll(x, y, w, h);
        if (scroll != 0) {
            engine.wheel(px, py, 0, -Math.round(scroll));
        }
        if (ui.focus != pageFocus) {
            return;
        }
        for (int[] key : ui.keys) {
            int code = awtKey(key[0]);
            if (code == KeyEvent.VK_UNDEFINED) {
                continue;
            }
            int mods = awtMods(key[1]);
            engine.key(KeyEvent.KEY_PRESSED, code, KeyEvent.CHAR_UNDEFINED, mods);
            if (code == KeyEvent.VK_ENTER) {
                engine.key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\r', mods);
            }
            engine.key(KeyEvent.KEY_RELEASED, code, KeyEvent.CHAR_UNDEFINED, mods);
        }
        ui.keys.clear();
        for (int i = 0; i < ui.chars.length(); i++) {
            engine.key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, ui.chars.charAt(i), 0);
        }
        ui.chars.setLength(0);
    }

    private static boolean pressed(Ui ui, int... wanted) {
        for (int[] key : ui.keys) {
            for (int k : wanted) {
                if (key[0] == k) {
                    return true;
                }
            }
        }
        return false;
    }

    /** GLFW key codes to AWT's: printable keys share ASCII codes, the rest are mapped. */
    private static int awtKey(int key) {
        if (key >= 32 && key < 128) {
            return key;
        }
        switch (key) {
            case Keys.ENTER:
            case Keys.KP_ENTER:
                return KeyEvent.VK_ENTER;
            case Keys.TAB:
                return KeyEvent.VK_TAB;
            case Keys.BACKSPACE:
                return KeyEvent.VK_BACK_SPACE;
            case Keys.DELETE:
                return KeyEvent.VK_DELETE;
            case Keys.LEFT:
                return KeyEvent.VK_LEFT;
            case Keys.RIGHT:
                return KeyEvent.VK_RIGHT;
            case Keys.UP:
                return KeyEvent.VK_UP;
            case Keys.DOWN:
                return KeyEvent.VK_DOWN;
            case Keys.HOME:
                return KeyEvent.VK_HOME;
            case Keys.END:
                return KeyEvent.VK_END;
            case Keys.PAGE_UP:
                return KeyEvent.VK_PAGE_UP;
            case Keys.PAGE_DOWN:
                return KeyEvent.VK_PAGE_DOWN;
            default:
                return KeyEvent.VK_UNDEFINED;
        }
    }

    private static int awtMods(int mods) {
        int out = 0;
        if ((mods & Keys.MOD_SHIFT) != 0) {
            out |= InputEvent.SHIFT_DOWN_MASK;
        }
        if ((mods & Keys.MOD_CONTROL) != 0) {
            out |= InputEvent.CTRL_DOWN_MASK;
        }
        if ((mods & Keys.MOD_ALT) != 0) {
            out |= InputEvent.ALT_DOWN_MASK;
        }
        if ((mods & Keys.MOD_SUPER) != 0) {
            out |= InputEvent.META_DOWN_MASK;
        }
        return out;
    }
}
