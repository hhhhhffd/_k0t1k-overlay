package dev.k0t1k.overlay.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Immediate-mode widgets in logical units (framebuffer pixels divided by {@link #scale}). Input
 * gathered between frames is consumed by the widgets of the next frame.
 */
final class Ui {
    // The palette: the launcher's tokens plus the shades derived from them. It follows the launcher's
    // theme (see Theme), so it is swapped by apply() on the render thread, never changed elsewhere.
    static int BASE;
    static int SURFACE;
    static int RAISED;
    static int HOVER;
    static int LINE;
    static int FG;
    static int MUTED;
    static int FAINT;
    static int ACCENT;
    static int OK;
    static int WARN;
    static int DANGER;
    /** Text and marks on top of {@link #ACCENT}. */
    static int ACCENT_FG;
    static int ACCENT_HOVER;
    /** A tinted fill for the active tab or pill and for selected list items. */
    static int ACCENT_SOFT;
    static int ACCENT_SELECT;
    /** Border of a hovered control. */
    static int LINE_HOVER;
    /** Translucent fills: the content card and top bar, tooltips and toasts. */
    static int CARD;
    static int POPUP;
    static int DANGER_SOFT;
    /** Scrollbar thumb, resting and dragged; and faint chart gridlines. */
    static int THUMB;
    static int THUMB_HOT;
    static int GRID;
    /** What dims the game behind the open overlay, and the lighter dim of the HUD editor. */
    static int VEIL;
    static int VEIL_LIGHT;

    static {
        apply(Theme.DEFAULT);
    }

    /** Switches every colour to the theme. */
    static void apply(Theme t) {
        BASE = t.base;
        SURFACE = t.surface;
        RAISED = t.raised;
        HOVER = t.hover;
        LINE = t.line;
        FG = t.fg;
        MUTED = t.muted;
        FAINT = t.faint;
        ACCENT = t.accent;
        OK = t.ok;
        WARN = t.warn;
        DANGER = t.danger;
        ACCENT_FG = t.accentFg;
        ACCENT_HOVER = Theme.mix(t.accent, 0xffffffff, 0.14f);
        ACCENT_SOFT = Theme.alpha(t.accent, 0x2e);
        ACCENT_SELECT = Theme.alpha(t.accent, 0x66);
        LINE_HOVER = Theme.mix(t.line, t.accent, 0.4f);
        CARD = Theme.alpha(t.surface, 0xf5);
        POPUP = Theme.alpha(t.raised, 0xf2);
        DANGER_SOFT = Theme.alpha(t.danger, 0x29);
        THUMB = Theme.mix(t.faint, t.line, 0.45f);
        THUMB_HOT = t.faint;
        GRID = Theme.alpha(t.fg, 0x40);
        // A light theme still darkens the game: the veil is a deep tint of the theme's own base.
        int veil = Theme.mix(t.base, 0xff000000, t.dark ? 0.4f : 0.85f);
        VEIL = Theme.alpha(veil, t.dark ? 0xc0 : 0xb8);
        VEIL_LIGHT = Theme.alpha(veil, 0x80);
    }

    final Batch batch;
    final Font regular;
    final Font bold;
    Platform platform;
    float scale = 1;

    // Input of the current frame.
    float mouseX = -1;
    float mouseY = -1;
    /** The left button, which all widgets use; {@code clicked} and {@code released} last one frame. */
    boolean mouseDown;
    boolean clicked;
    boolean released;
    /**
     * The other buttons, for content that forwards them (the browser page). Like {@link #clicked},
     * the flags last one frame; {@link #pressedButton} and {@link #releasedButton} are the last
     * button (GLFW numbering, see {@link Keys#MOUSE_LEFT}) pressed or released this frame, or -1.
     */
    boolean rightClicked;
    boolean middleClicked;
    boolean backClicked;
    boolean forwardClicked;
    int pressedButton = -1;
    int releasedButton = -1;
    private final boolean[] buttonDown = new boolean[Keys.MOUSE_FORWARD + 1];
    boolean doubleClicked;
    float scrollY;
    /** Modifier bits of the latest key event, so wheel and mouse input can tell whether Ctrl is held. */
    int mods;
    final List<int[]> keys = new ArrayList<>(); // {key, mods} for presses and repeats
    final StringBuilder chars = new StringBuilder();
    private long lastClickTime;

    /** The slider being dragged (the id it was given), or null. */
    private Object dragging;
    private final ArrayDeque<float[]> clips = new ArrayDeque<>();
    /** The text field that receives keyboard input, or null. */
    Object focus;
    /** Set when a widget consumed the click, so clicking empty space can drop the focus. */
    private boolean clickHandled;

    Ui(Batch batch, Font regular, Font bold, Platform platform) {
        this.batch = batch;
        this.regular = regular;
        this.bold = bold;
        this.platform = platform;
    }

    // ---- input, fed by the overlay between frames ----

    void onMove(float x, float y) {
        mouseX = x;
        mouseY = y;
    }

    void onButton(int button, boolean press) {
        if (button < 0 || button >= buttonDown.length) {
            return;
        }
        buttonDown[button] = press;
        if (press) {
            pressedButton = button;
        } else {
            releasedButton = button;
        }
        switch (button) {
            case Keys.MOUSE_RIGHT:
                rightClicked |= press;
                return;
            case Keys.MOUSE_MIDDLE:
                middleClicked |= press;
                return;
            case Keys.MOUSE_BACK:
                backClicked |= press;
                return;
            case Keys.MOUSE_FORWARD:
                forwardClicked |= press;
                return;
            default:
                break;
        }
        if (press) {
            long now = System.currentTimeMillis();
            doubleClicked = now - lastClickTime < 350;
            lastClickTime = now;
            clicked = true;
            mouseDown = true;
        } else {
            released = true;
            mouseDown = false;
        }
    }

    /** Whether a button (GLFW numbering) is held. */
    boolean buttonDown(int button) {
        return button >= 0 && button < buttonDown.length && buttonDown[button];
    }

    void onScroll(double dy) {
        scrollY += (float) dy;
    }

    void onKey(int key, int mods) {
        keys.add(new int[] {key, mods});
    }

    void onChar(int codepoint) {
        chars.appendCodePoint(codepoint);
    }

    /** Clears per-frame input; called after the widgets of a frame ran. */
    void endFrame() {
        // A frame that threw between clip and unclip must not leave hit testing clipped for good.
        clips.clear();
        clearInput();
    }

    /**
     * Drops this frame's clicks, keys and wheel. Unlike {@link #endFrame} it keeps the clip stack, so a
     * widget may call it (through {@link #resetInput}) in the middle of drawing.
     */
    private void clearInput() {
        if (clicked && !clickHandled) {
            focus = null;
        }
        clicked = false;
        released = false;
        rightClicked = false;
        middleClicked = false;
        backClicked = false;
        forwardClicked = false;
        pressedButton = -1;
        releasedButton = -1;
        doubleClicked = false;
        clickHandled = false;
        scrollY = 0;
        keys.clear();
        chars.setLength(0);
    }

    /** Drops all input, e.g. when the overlay closes, so nothing leaks into the next opening. */
    void resetInput() {
        clearInput();
        mouseDown = false;
        dragging = null;
        java.util.Arrays.fill(buttonDown, false);
        focus = null;
    }

    boolean hover(float x, float y, float w, float h) {
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) {
            return false;
        }
        for (float[] clip : clips) {
            if (mouseX < clip[0] || mouseX >= clip[0] + clip[2]
                    || mouseY < clip[1] || mouseY >= clip[1] + clip[3]) {
                return false;
            }
        }
        return true;
    }

    boolean clickedIn(float x, float y, float w, float h) {
        if (clicked && hover(x, y, w, h)) {
            clickHandled = true;
            return true;
        }
        return false;
    }

    /**
     * Takes the wheel movement over a region, in lines (positive = towards the top). Ctrl+wheel is
     * left alone: the overlay uses it to change the interface scale.
     */
    float takeScroll(float x, float y, float w, float h) {
        return shortcut(mods) ? 0 : takeZoomScroll(x, y, w, h);
    }

    /** Like {@link #takeScroll}, but also takes Ctrl+wheel, for content that zooms by itself (the browser page). */
    float takeZoomScroll(float x, float y, float w, float h) {
        if (scrollY == 0 || !hover(x, y, w, h)) {
            return 0;
        }
        float value = scrollY;
        scrollY = 0;
        return value;
    }

    static boolean shortcut(int mods) {
        return (mods & (Keys.MOD_CONTROL | Keys.MOD_SUPER)) != 0;
    }

    // ---- drawing ----

    void rect(float x, float y, float w, float h, int color) {
        batch.rect(x * scale, y * scale, w * scale, h * scale, color);
    }

    void round(float x, float y, float w, float h, float r, int color) {
        batch.roundRect(x * scale, y * scale, w * scale, h * scale, r * scale, color);
    }

    /** A rounded box with a one unit border. */
    void panel(float x, float y, float w, float h, float r, int fill, int border) {
        round(x, y, w, h, r, border);
        round(x + 1, y + 1, w - 2, h - 2, r - 1, fill);
    }

    /** Draws the batch's current image (see {@link Batch#image}) into the rectangle. */
    void image(float x, float y, float w, float h) {
        batch.image(x * scale, y * scale, w * scale, h * scale, 0xffffffff);
    }

    void clip(float x, float y, float w, float h) {
        batch.pushClip(x * scale, y * scale, w * scale, h * scale);
        clips.push(new float[] {x, y, w, h});
    }

    void unclip() {
        batch.popClip();
        clips.pop();
    }

    float lineHeight(Font font, float size) {
        return font.lineHeight(size * scale) / scale;
    }

    private static int replacement(Font font, int cp) {
        return font.has(cp) ? cp : '?';
    }

    float measure(Font font, float size, CharSequence text, int start, int end) {
        float px = size * scale;
        float width = 0;
        int prev = -1;
        for (int i = start; i < end; ) {
            int cp = replacement(font, Character.codePointAt(text, i));
            i += Character.charCount(Character.codePointAt(text, i));
            if (prev >= 0) {
                width += font.kern(prev, cp, px);
            }
            width += font.glyph(cp, px).advance;
            prev = cp;
        }
        return width / scale;
    }

    float measure(Font font, float size, CharSequence text) {
        return measure(font, size, text, 0, text.length());
    }

    /** Draws one line with its top at {@code y}; returns its width. */
    float text(Font font, float size, float x, float y, int color, CharSequence text, int start, int end) {
        float px = size * scale;
        float pen = x * scale;
        float baseline = Math.round(y * scale + font.ascent(px));
        int prev = -1;
        for (int i = start; i < end; ) {
            int raw = Character.codePointAt(text, i);
            i += Character.charCount(raw);
            if (raw == '\n') {
                continue;
            }
            int cp = replacement(font, raw);
            if (prev >= 0) {
                pen += font.kern(prev, cp, px);
            }
            Font.Glyph g = font.glyph(cp, px);
            if (g.w > 0) {
                float gx = Math.round(pen) + g.x0;
                float gy = baseline + g.y0;
                batch.glyph(gx, gy, gx + g.w, gy + g.h, g.u0, g.v0, g.u1, g.v1, color);
            }
            pen += g.advance;
            prev = cp;
        }
        return (pen - x * scale) / scale;
    }

    float text(Font font, float size, float x, float y, int color, CharSequence text) {
        return text(font, size, x, y, color, text, 0, text.length());
    }

    /** Draws text cut with an ellipsis to fit {@code maxWidth}. */
    void textFit(Font font, float size, float x, float y, float maxWidth, int color, String text) {
        if (measure(font, size, text) <= maxWidth) {
            text(font, size, x, y, color, text);
            return;
        }
        float ellipsis = measure(font, size, "…");
        int end = text.length();
        while (end > 0 && measure(font, size, text, 0, end) + ellipsis > maxWidth) {
            end = text.offsetByCodePoints(end, -1);
        }
        text(font, size, x, y, color, text.substring(0, end) + "…");
    }

    /**
     * Breaks text into lines of at most {@code maxWidth}: at newlines, after the last space that fits,
     * or mid-word for words longer than a line. Returns {start, end} pairs; end excludes the break.
     */
    List<int[]> wrap(Font font, float size, CharSequence text, float maxWidth) {
        List<int[]> lines = new ArrayList<>();
        float px = size * scale;
        float limit = maxWidth * scale;
        int lineStart = 0;
        int lastSpace = -1;
        float width = 0;
        int prev = -1;
        int i = 0;
        while (i < text.length()) {
            int cp = Character.codePointAt(text, i);
            int next = i + Character.charCount(cp);
            if (cp == '\n') {
                lines.add(new int[] {lineStart, i});
                lineStart = next;
                lastSpace = -1;
                width = 0;
                prev = -1;
                i = next;
                continue;
            }
            int drawn = replacement(font, cp);
            float advance = font.glyph(drawn, px).advance + (prev >= 0 ? font.kern(prev, drawn, px) : 0);
            if (width + advance > limit && i > lineStart) {
                if (cp == ' ') {
                    // A space at the break point disappears into it.
                    lines.add(new int[] {lineStart, i});
                    lineStart = next;
                    i = next;
                } else if (lastSpace >= lineStart) {
                    lines.add(new int[] {lineStart, lastSpace});
                    lineStart = lastSpace + 1;
                    i = lineStart;
                } else {
                    lines.add(new int[] {lineStart, i});
                    lineStart = i;
                }
                lastSpace = -1;
                width = 0;
                prev = -1;
                continue;
            }
            if (cp == ' ') {
                lastSpace = i;
            }
            width += advance;
            prev = drawn;
            i = next;
        }
        lines.add(new int[] {lineStart, text.length()});
        return lines;
    }

    // ---- widgets ----

    /** A button; returns true when clicked. */
    boolean button(float x, float y, float w, float h, String label, boolean primary) {
        boolean hot = hover(x, y, w, h);
        int fill = primary ? (hot ? ACCENT_HOVER : ACCENT) : (hot ? HOVER : RAISED);
        panel(x, y, w, h, 8, fill, primary ? fill : (hot ? LINE_HOVER : LINE));
        float tw = measure(bold, 13, label);
        text(bold, 13, x + (w - tw) / 2, y + (h - lineHeight(bold, 13)) / 2, primary ? ACCENT_FG : FG, label);
        return clickedIn(x, y, w, h);
    }

    /** Width a {@link #button} needs for its label. */
    float buttonWidth(String label) {
        return measure(bold, 13, label) + 24;
    }

    /** A tab or toggle: accent when active. */
    boolean pill(float x, float y, float w, float h, String label, boolean active) {
        boolean hot = hover(x, y, w, h);
        if (active) {
            round(x, y, w, h, 7, ACCENT_SOFT);
        } else if (hot) {
            round(x, y, w, h, 7, HOVER);
        }
        float tw = measure(bold, 13, label);
        text(bold, 13, x + (w - tw) / 2, y + (h - lineHeight(bold, 13)) / 2, active ? ACCENT : (hot ? FG : MUTED), label);
        return clickedIn(x, y, w, h);
    }

    /**
     * A section link of the top bar, like the launcher's: upper case, accent with a 3 unit underline
     * when active, accent on hover. The underline sits on the bottom edge of the box.
     */
    boolean tab(float x, float y, float w, float h, String label, boolean active) {
        boolean hot = hover(x, y, w, h);
        float tw = measure(bold, 13, label);
        text(bold, 13, x + (w - tw) / 2, y + (h - lineHeight(bold, 13)) / 2, active || hot ? ACCENT : FG, label);
        if (active) {
            rect(x, y + h - 3, w, 3, ACCENT);
        }
        return clickedIn(x, y, w, h);
    }

    /** Whether the slider with this id is being dragged. */
    boolean dragging(Object id) {
        return dragging == id;
    }

    /**
     * A horizontal slider from {@code min} to {@code max} in multiples of {@code step}, 16 units
     * tall. {@code id} tells sliders apart while one is dragged. Returns the new value; the caller
     * draws the value's label.
     */
    float slider(Object id, float x, float y, float w, float min, float max, float step, float value) {
        float h = 16;
        float thumb = 14;
        boolean hot = hover(x, y, w, h);
        if (clickedIn(x, y, w, h)) {
            dragging = id;
        }
        if (dragging == id) {
            float t = Math.max(0, Math.min(1, (mouseX - x - thumb / 2) / (w - thumb)));
            value = min + Math.round(t * (max - min) / step) * step;
            value = Math.max(min, Math.min(max, value));
        }
        if (!mouseDown && dragging == id) {
            dragging = null;
        }
        float t = max > min ? (value - min) / (max - min) : 0;
        float tx = x + t * (w - thumb);
        round(x, y + h / 2 - 2, w, 4, 2, LINE);
        round(x, y + h / 2 - 2, tx - x + thumb / 2, 4, 2, ACCENT);
        round(tx, y + (h - thumb) / 2, thumb, thumb, thumb / 2, dragging == id || hot ? ACCENT_HOVER : ACCENT);
        round(tx + 4, y + (h - thumb) / 2 + 4, thumb - 8, thumb - 8, (thumb - 8) / 2, ACCENT_FG);
        return value;
    }

    boolean checkbox(float x, float y, String label, boolean value) {
        float box = 16;
        float tw = measure(regular, 13, label);
        boolean hot = hover(x, y, box + 8 + tw, box);
        panel(x, y, box, box, 4, value ? ACCENT : BASE, value ? ACCENT : (hot ? LINE_HOVER : LINE));
        if (value) {
            round(x + 4.5f, y + 4.5f, 7, 7, 2, ACCENT_FG);
        }
        text(regular, 13, x + box + 8, y + (box - lineHeight(regular, 13)) / 2, FG, label);
        return clickedIn(x, y, box + 8 + tw, box) ? !value : value;
    }

    /**
     * A text field editing {@code edit}. Single-line fields scroll horizontally, multi-line ones wrap
     * and scroll vertically. Returns true when the text changed this frame.
     */
    boolean field(TextEdit edit, float x, float y, float w, float h, boolean multiline, String placeholder) {
        boolean focused = focus == edit;
        boolean hot = hover(x, y, w, h);
        panel(x, y, w, h, 8, BASE, focused ? ACCENT : (hot ? LINE_HOVER : LINE));

        float size = 14;
        float lh = lineHeight(regular, size);
        float padX = 10;
        float padY = multiline ? 8 : (h - lh) / 2;
        float innerW = w - padX * 2;
        float innerH = h - padY * 2;

        boolean changed = false;
        if (focused) {
            changed = handleKeys(edit, multiline, innerW);
        }

        CharSequence text = edit.text;
        List<int[]> lines = multiline ? wrap(regular, size, text, innerW) : single(text);

        if (multiline) {
            float content = lines.size() * lh;
            edit.scroll -= takeScroll(x, y, w, h) * lh * 3;
            if (edit.caretMoved) {
                float caretY = lineOf(lines, edit.caret) * lh;
                edit.scroll = Math.max(Math.min(edit.scroll, caretY), caretY + lh - innerH);
            }
            edit.scroll = Math.max(0, Math.min(edit.scroll, Math.max(0, content - innerH)));
        } else if (edit.caretMoved) {
            float caretX = measure(regular, size, text, 0, edit.caret);
            edit.scroll = Math.max(Math.min(edit.scroll, caretX), caretX - innerW + 2);
            edit.scroll = Math.max(0, edit.scroll);
        }
        edit.caretMoved = false;

        float ox = x + padX - (multiline ? 0 : edit.scroll);
        float oy = y + padY - (multiline ? edit.scroll : 0);

        if (hot && clicked) {
            clickHandled = true;
            focus = edit;
            int at = hit(lines, text, size, lh, ox, oy, mouseX, mouseY);
            if (doubleClicked) {
                edit.selectWord(at);
            } else {
                edit.moveTo(at, false);
            }
            edit.dragging = true;
        } else if (edit.dragging && mouseDown) {
            edit.moveTo(hit(lines, text, size, lh, ox, oy, mouseX, mouseY), true);
        }
        if (!mouseDown) {
            edit.dragging = false;
        }

        clip(x + 2, y + 2, w - 4, h - 4);
        if (text.length() == 0 && !placeholder.isEmpty()) {
            text(regular, size, x + padX, y + padY, FAINT, placeholder);
        }
        int selStart = Math.min(edit.anchor, edit.caret);
        int selEnd = Math.max(edit.anchor, edit.caret);
        for (int i = 0; i < lines.size(); i++) {
            int[] line = lines.get(i);
            float ly = oy + i * lh;
            if (ly + lh < y || ly > y + h) {
                continue;
            }
            if (focused && selEnd > selStart && selEnd >= line[0] && selStart <= line[1]) {
                int a = Math.max(selStart, line[0]);
                int b = Math.min(selEnd, line[1]);
                float sx = ox + measure(regular, size, text, line[0], a);
                float sw = measure(regular, size, text, a, b) + (selEnd > line[1] && i < lines.size() - 1 ? 4 : 0);
                rect(sx, ly, sw, lh, ACCENT_SELECT);
            }
            text(regular, size, ox, ly, FG, text, line[0], line[1]);
        }
        if (focused && (System.currentTimeMillis() / 530) % 2 == 0) {
            int li = lineOf(lines, edit.caret);
            int[] line = lines.get(li);
            float cx = ox + measure(regular, size, text, line[0], Math.min(edit.caret, line[1]));
            rect(cx, oy + li * lh + 1, 1.5f, lh - 2, ACCENT);
        }
        unclip();
        return changed;
    }

    private static List<int[]> single(CharSequence text) {
        List<int[]> lines = new ArrayList<>(1);
        lines.add(new int[] {0, text.length()});
        return lines;
    }

    private static int lineOf(List<int[]> lines, int index) {
        for (int i = 0; i < lines.size() - 1; i++) {
            if (index < lines.get(i + 1)[0]) {
                return i;
            }
        }
        return lines.size() - 1;
    }

    private int hit(List<int[]> lines, CharSequence text, float size, float lh, float ox, float oy, float mx, float my) {
        int li = (int) Math.floor((my - oy) / lh);
        li = Math.max(0, Math.min(lines.size() - 1, li));
        return indexAtX(lines.get(li), text, size, mx - ox);
    }

    private int indexAtX(int[] line, CharSequence text, float size, float x) {
        float pen = 0;
        for (int i = line[0]; i < line[1]; ) {
            int next = i + Character.charCount(Character.codePointAt(text, i));
            float w = measure(regular, size, text, i, next);
            if (x < pen + w / 2) {
                return i;
            }
            pen += w;
            i = next;
        }
        return line[1];
    }

    private boolean handleKeys(TextEdit edit, boolean multiline, float innerW) {
        boolean changed = false;
        for (int[] k : keys) {
            int key = k[0];
            int mods = k[1];
            boolean shift = (mods & Keys.MOD_SHIFT) != 0;
            boolean word = shortcut(mods) || (mods & Keys.MOD_ALT) != 0;
            switch (key) {
                case Keys.LEFT:
                    edit.left(word, shift);
                    break;
                case Keys.RIGHT:
                    edit.right(word, shift);
                    break;
                case Keys.UP:
                case Keys.DOWN:
                    if (multiline) {
                        List<int[]> lines = wrap(regular, 14, edit.text, innerW);
                        int li = lineOf(lines, edit.caret) + (key == Keys.UP ? -1 : 1);
                        if (li < 0) {
                            edit.moveTo(0, shift);
                        } else if (li >= lines.size()) {
                            edit.moveTo(edit.text.length(), shift);
                        } else {
                            int[] cur = lines.get(lineOf(lines, edit.caret));
                            float cx = measure(regular, 14, edit.text, cur[0], Math.min(edit.caret, cur[1]));
                            edit.moveTo(indexAtX(lines.get(li), edit.text, 14, cx), shift);
                        }
                    }
                    break;
                case Keys.HOME:
                    edit.moveTo(shortcut(mods) || !multiline ? 0 : edit.lineStart(), shift);
                    break;
                case Keys.END:
                    edit.moveTo(shortcut(mods) || !multiline ? edit.text.length() : edit.lineEnd(), shift);
                    break;
                case Keys.BACKSPACE:
                    changed |= edit.backspace(word);
                    break;
                case Keys.DELETE:
                    changed |= edit.delete(word);
                    break;
                case Keys.ENTER:
                case Keys.KP_ENTER:
                    if (multiline) {
                        edit.insert("\n");
                        changed = true;
                    }
                    break;
                case Keys.TAB:
                    if (multiline) {
                        edit.insert("    ");
                        changed = true;
                    }
                    break;
                case Keys.A:
                    if (shortcut(mods)) {
                        edit.selectAll();
                    }
                    break;
                case Keys.C:
                case Keys.X:
                    if (shortcut(mods) && edit.hasSelection()) {
                        platform.setClipboard(edit.selected());
                        if (key == Keys.X) {
                            edit.insert("");
                            changed = true;
                        }
                    }
                    break;
                case Keys.V:
                    if (shortcut(mods)) {
                        String pasted = platform.clipboard();
                        if (pasted != null && !pasted.isEmpty()) {
                            pasted = pasted.replace("\r\n", "\n").replace('\r', '\n');
                            edit.insert(multiline ? pasted : pasted.replace('\n', ' '));
                            changed = true;
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        if (chars.length() > 0) {
            edit.insert(chars.toString());
            changed = true;
        }
        return changed;
    }
}
