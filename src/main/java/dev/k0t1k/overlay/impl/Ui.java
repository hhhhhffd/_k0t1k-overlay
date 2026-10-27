package dev.k0t1k.overlay.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * Immediate-mode widgets in logical units (framebuffer pixels divided by {@link #scale}). Input
 * gathered between frames is consumed by the widgets of the next frame.
 */
final class Ui {
    // Palette of the launcher's default dark theme (apps/app/src/style.css).
    static final int BASE = 0xff0b0910;
    static final int SURFACE = 0xff13101c;
    static final int RAISED = 0xff1b1728;
    static final int HOVER = 0xff251f37;
    static final int LINE = 0xff2b2542;
    static final int FG = 0xffece8f6;
    static final int MUTED = 0xffa199ba;
    static final int FAINT = 0xff6d6588;
    static final int ACCENT = 0xffa259f7;
    static final int OK = 0xff4ade80;
    static final int WARN = 0xfffbbf24;
    static final int DANGER = 0xfff87171;

    final Gl gl;
    final Font regular;
    final Font bold;
    final Platform platform;
    float scale = 1;

    // Input of the current frame.
    float mouseX = -1;
    float mouseY = -1;
    boolean mouseDown;
    boolean clicked;
    boolean released;
    boolean doubleClicked;
    float scrollY;
    final List<int[]> keys = new ArrayList<>(); // {key, mods} for presses and repeats
    final StringBuilder chars = new StringBuilder();
    private long lastClickTime;

    /** The text field that receives keyboard input, or null. */
    Object focus;
    /** Set when a widget consumed the click, so clicking empty space can drop the focus. */
    private boolean clickHandled;

    Ui(Gl gl, Font regular, Font bold, Platform platform) {
        this.gl = gl;
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
        if (button != 0) {
            return;
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
        if (clicked && !clickHandled) {
            focus = null;
        }
        clicked = false;
        released = false;
        doubleClicked = false;
        clickHandled = false;
        scrollY = 0;
        keys.clear();
        chars.setLength(0);
    }

    /** Drops all input, e.g. when the overlay closes, so nothing leaks into the next opening. */
    void resetInput() {
        endFrame();
        mouseDown = false;
        focus = null;
    }

    boolean hover(float x, float y, float w, float h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    boolean clickedIn(float x, float y, float w, float h) {
        if (clicked && hover(x, y, w, h)) {
            clickHandled = true;
            return true;
        }
        return false;
    }

    /** Takes the wheel movement over a region, in lines (positive = towards the top). */
    float takeScroll(float x, float y, float w, float h) {
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
        gl.rect(x * scale, y * scale, w * scale, h * scale, color);
    }

    void round(float x, float y, float w, float h, float r, int color) {
        gl.roundRect(x * scale, y * scale, w * scale, h * scale, r * scale, color);
    }

    /** A rounded box with a one unit border. */
    void panel(float x, float y, float w, float h, float r, int fill, int border) {
        round(x, y, w, h, r, border);
        round(x + 1, y + 1, w - 2, h - 2, r - 1, fill);
    }

    void clip(float x, float y, float w, float h) {
        gl.pushClip(x * scale, y * scale, w * scale, h * scale);
    }

    void unclip() {
        gl.popClip();
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
                gl.glyph(gx, gy, gx + g.w, gy + g.h, g.u0, g.v0, g.u1, g.v1, color);
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
        int fill = primary ? (hot ? 0xffb678ff : ACCENT) : (hot ? HOVER : RAISED);
        panel(x, y, w, h, 7, fill, primary ? fill : LINE);
        float tw = measure(bold, 13, label);
        text(bold, 13, x + (w - tw) / 2, y + (h - lineHeight(bold, 13)) / 2, primary ? 0xffffffff : FG, label);
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
            round(x, y, w, h, h / 2, 0x33a259f7);
        } else if (hot) {
            round(x, y, w, h, h / 2, HOVER);
        }
        float tw = measure(bold, 13, label);
        text(bold, 13, x + (w - tw) / 2, y + (h - lineHeight(bold, 13)) / 2, active ? 0xffd4b0ff : MUTED, label);
        return clickedIn(x, y, w, h);
    }

    boolean checkbox(float x, float y, String label, boolean value) {
        float box = 16;
        float tw = measure(regular, 13, label);
        boolean hot = hover(x, y, box + 8 + tw, box);
        panel(x, y, box, box, 4, value ? ACCENT : (hot ? HOVER : RAISED), value ? ACCENT : LINE);
        if (value) {
            round(x + 4.5f, y + 4.5f, 7, 7, 2, 0xffffffff);
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
        panel(x, y, w, h, 7, focused ? BASE : RAISED, focused ? 0xff7c4fc0 : (hot ? 0xff3a3256 : LINE));

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
                rect(sx, ly, sw, lh, 0x666d3fc0);
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
