package dev.k0t1k.overlay.impl;

/**
 * The settings tab: a list of sections on the left and one scrolling column on the right. To add a
 * section, put its title key in {@link #SECTIONS}, write a method {@code float section(Ui ui, float x,
 * float y, float w)} that starts with {@link #heading} and returns the y below its last row, and call
 * it from {@link #draw} in the same order.
 */
final class SettingsView {
    private static final String[] SECTIONS = {"settings.interface", "settings.hud", "settings.notify", "settings.cat",
            "settings.recording", "settings.hotkeys", "settings.browser", "settings.hardware"};
    private static final int INTERFACE = 0;
    private static final int HUD = 1;
    private static final int NOTIFY = 2;
    private static final int CAT = 3;
    private static final int RECORDING = 4;
    private static final int HOTKEYS = 5;
    private static final int BROWSER = 6;
    private static final int HARDWARE = 7;

    private final Overlay overlay;
    private final Options options;
    private final Strings strings;
    private final RecordingView recordingView;
    /** The percent shown while the scale slider is dragged; the scale itself changes on release. */
    private int draggedScale;
    private final Object hudSizeSlider = new Object();
    private final Object hudOpacitySlider = new Object();
    /** A HUD slider moved and the settings are not saved yet; they are on release or close. */
    private boolean hudSliderMoved;
    private final TextEdit fakeCpu;
    private final TextEdit fakeThreads;
    private final TextEdit fakeGpu;
    private final TextEdit fakeVendor;
    private final TextEdit fakeMemory;
    private float scroll;
    private final TextEdit homeEdit = new TextEdit();
    private long clearedAt;
    private boolean clearFailed;
    /** The y of the top of the column, so a section can tell where it starts within it. */
    private float viewTop;
    /** Where each section starts, as an offset from the top of the column; set while drawing. */
    private final float[] tops = new float[SECTIONS.length];

    SettingsView(Overlay overlay) {
        this.overlay = overlay;
        this.options = overlay.options();
        this.strings = options.strings;
        homeEdit.set(options.browserHome);
        fakeCpu = new TextEdit(options.fakeCpu);
        fakeThreads = new TextEdit(Integer.toString(options.fakeThreads));
        fakeGpu = new TextEdit(options.fakeGpu);
        fakeVendor = new TextEdit(options.fakeVendor);
        fakeMemory = new TextEdit(Integer.toString(options.fakeMemory));
        this.recordingView = new RecordingView(overlay);
    }

    void draw(Ui ui, float x, float y, float w, float h) {
        float listW = 150;
        float gap = 18;
        float cx = x + listW + gap;
        float cw = w - listW - gap;

        viewTop = y;
        float cy = y - scroll;
        ui.clip(cx, y, cw, h);
        cy = interfaceSection(ui, cx, cy, cw);
        cy = hudSection(ui, cx, cy, cw);
        cy = notifySection(ui, cx, cy, cw);
        cy = catSection(ui, cx, cy, cw);
        cy = recordingSection(ui, cx, cy, cw);
        cy = hotkeysSection(ui, cx, cy, cw);
        cy = browserSection(ui, cx, cy, cw);
        cy = hardwareSection(ui, cx, cy, cw);
        ui.unclip();
        float content = cy + scroll - y;

        scroll -= ui.takeScroll(x, y, w, h) * 40;
        float max = Math.max(0, content - h);
        scroll = Math.max(0, Math.min(scroll, max));

        ui.rect(x + listW + gap / 2 - 0.5f, y, 1, h, Ui.LINE);
        int current = 0;
        for (int i = 0; i < SECTIONS.length; i++) {
            if (tops[i] <= scroll + 8) {
                current = i;
            }
        }
        for (int i = 0; i < SECTIONS.length; i++) {
            if (ui.pill(x, y + i * 34, listW, 30, strings.get(SECTIONS[i]), i == current)) {
                scroll = Math.min(tops[i], max);
            }
        }
    }

    /** Records where a section starts and draws its title; returns the y of its first row. */
    private float heading(Ui ui, int section, float x, float y, float w) {
        tops[section] = y - viewTop + scroll;
        ui.text(ui.bold, 15, x, y, Ui.FG, strings.get(SECTIONS[section]));
        ui.rect(x, y + 26, w, 1, Ui.LINE);
        return y + 40;
    }

    private void changed() {
        overlay.saveSettings();
    }

    void flush() {
        if (hudSliderMoved) {
            changed();
            hudSliderMoved = false;
        }
    }

    private float interfaceSection(Ui ui, float x, float y, float w) {
        y = heading(ui, INTERFACE, x, y, w);
        int auto = overlay.autoScalePercent();
        ui.text(ui.regular, 13, x, y + 4, Ui.MUTED, strings.get("settings.scale"));
        y += 28;
        // Changing the scale moves the slider under the pointer, so while dragging only the label
        // follows the pointer and the scale is applied on release.
        boolean dragging = ui.dragging(this);
        int shown = dragging ? draggedScale : Math.max(Options.MIN_SCALE,
                Math.min(Options.MAX_SCALE, options.uiScale > 0 ? options.uiScale : auto));
        String autoLabel = strings.format("settings.scaleAuto", auto);
        float aw = ui.measure(ui.bold, 13, autoLabel) + 24;
        if (ui.pill(x, y, aw, 28, autoLabel, options.uiScale == 0 && !dragging)) {
            overlay.setUiScale(0);
        }
        float sx = x + aw + 16;
        float sw = Math.min(260, w - aw - 16 - 56);
        int value = Math.round(ui.slider(this, sx, y + 6, sw,
                Options.MIN_SCALE, Options.MAX_SCALE, 5, shown));
        if (ui.dragging(this)) {
            draggedScale = value;
        } else if (dragging) {
            overlay.setUiScale(draggedScale);
        } else if (value != shown) {
            overlay.setUiScale(value);
        }
        ui.text(ui.bold, 13, sx + sw + 12, y + 6, Ui.FG, ui.dragging(this) ? draggedScale + "%" : shown + "%");
        ui.textFit(ui.regular, 12, x, y + 40, w, Ui.FAINT, strings.get("settings.scaleHint"));
        return y + 72;
    }

    private float hudSection(Ui ui, float x, float y, float w) {
        y = heading(ui, HUD, x, y, w);
        boolean hud = ui.checkbox(x, y + 4, strings.get("perf.hud"), options.hud);
        if (hud != options.hud) {
            options.hud = hud;
            changed();
        }
        String presets = strings.get("hud.presets");
        float toggleW = 24 + ui.measure(ui.regular, 13, strings.get("perf.hud"));
        float presetsW = ui.measure(ui.regular, 13, presets) + 10 + 3 * 34 + 30;
        float cx = x + toggleW + 40;
        if (cx + presetsW > x + w) {
            cx = x;
            y += 34;
        }
        ui.text(ui.regular, 13, cx, y + 5, Ui.MUTED, presets);
        cx += ui.measure(ui.regular, 13, presets) + 10;
        String[] arrows = {"↖", "↗", "↙", "↘"};
        for (int i = 0; i < 4; i++) {
            boolean here = options.hudX == i % 2 && options.hudY == i / 2;
            if (ui.pill(cx + i * 34, y, 30, 26, arrows[i], here) && !here) {
                options.hudX = i % 2;
                options.hudY = i / 2;
                changed();
            }
        }
        String edit = strings.get("hud.edit");
        float ew = ui.buttonWidth(edit);
        float ex = cx + 3 * 34 + 30 + 16;
        if (ex + ew > x + w) {
            ex = x;
            y += 34;
        } else {
            ex = x + w - ew;
        }
        if (ui.button(ex, y - 1, ew, 28, edit, false)) {
            overlay.startHudEdit();
        }
        y += 44;

        float labelW = 150;
        float sw = Math.min(260, w - labelW - 60);
        ui.text(ui.regular, 13, x, y + 1, Ui.MUTED, strings.get("hud.size"));
        options.hudSize = hudSlider(ui, hudSizeSlider, x + labelW, y, sw, Options.MIN_HUD_SIZE, Options.MAX_HUD_SIZE,
                5, options.hudSize);
        y += 30;
        ui.text(ui.regular, 13, x, y + 1, Ui.MUTED, strings.get("hud.opacity"));
        options.hudOpacity = hudSlider(ui, hudOpacitySlider, x + labelW, y, sw, Options.MIN_HUD_OPACITY, 100, 5,
                options.hudOpacity);
        y += 30;
        if (hudSliderMoved && !ui.dragging(hudSizeSlider) && !ui.dragging(hudOpacitySlider)) {
            flush();
        }

        ui.text(ui.regular, 13, x, y + 1, Ui.MUTED, strings.get("hud.metrics"));
        float mx = x + labelW;
        float my = y;
        String[] keys = {"perf.fps", "perf.frame", "perf.low", "perf.heap", "perf.cpu", "perf.gc", "perf.graphShort"};
        boolean[] values = {options.hudFps, options.hudFrame, options.hudLow, options.hudHeap, options.hudCpu,
            options.hudGc, options.hudGraph};
        boolean[] next = values.clone();
        for (int i = 0; i < keys.length; i++) {
            String label = strings.get(keys[i]);
            float bw = 24 + ui.measure(ui.regular, 13, label) + 20;
            if (mx + bw > x + w && mx > x + labelW) {
                mx = x + labelW;
                my += 26;
            }
            next[i] = ui.checkbox(mx, my, label, values[i]);
            mx += bw;
        }
        if (!java.util.Arrays.equals(values, next)) {
            options.hudFps = next[0];
            options.hudFrame = next[1];
            options.hudLow = next[2];
            options.hudHeap = next[3];
            options.hudCpu = next[4];
            options.hudGc = next[5];
            options.hudGraph = next[6];
            changed();
        }
        return my + 36;
    }

    /** A percent slider with its value to the right; sets {@link #hudSliderMoved} when it changes. */
    private int hudSlider(Ui ui, Object id, float x, float y, float w, int min, int max, int step, int value) {
        int moved = Math.round(ui.slider(id, x, y, w, min, max, step, value));
        if (moved != value) {
            hudSliderMoved = true;
        }
        ui.text(ui.bold, 13, x + w + 12, y, Ui.FG, moved + "%");
        return moved;
    }

    private float notifySection(Ui ui, float x, float y, float w) {
        y = heading(ui, NOTIFY, x, y, w);
        ui.text(ui.regular, 13, x, y + 4, Ui.MUTED, strings.get("toast.position"));
        y += 30;
        String[] keys = {"toast.center", "toast.topLeft", "toast.topRight", "toast.bottomLeft", "toast.bottomRight"};
        float px = x;
        float py = y;
        for (int i = 0; i < keys.length; i++) {
            String label = strings.get(keys[i]);
            float pw = ui.measure(ui.bold, 13, label) + 24;
            if (px + pw > x + w && px > x) {
                px = x;
                py += 32;
            }
            if (ui.pill(px, py, pw, 28, label, options.toastPos == i) && options.toastPos != i) {
                options.toastPos = i;
                changed();
            }
            px += pw + 4;
        }
        y = py + 40;

        // A small screen with a mark where the toasts go.
        float dw = 112;
        float dh = 64;
        ui.panel(x, y, dw, dh, 6, Ui.BASE, Ui.LINE);
        float[][] marks = {{dw / 2 - 11, dh / 2 + 8}, {6, 6}, {dw - 28, 6}, {6, dh - 20}, {dw - 28, dh - 20}};
        for (int i = 0; i < marks.length; i++) {
            ui.round(x + marks[i][0], y + marks[i][1], 22, 14, 3, i == options.toastPos ? Ui.ACCENT : Ui.LINE);
        }
        String test = strings.get("toast.test");
        if (ui.button(x + dw + 20, y + (dh - 30) / 2, ui.buttonWidth(test), 30, test, false)) {
            overlay.toast(strings.get("toast.test"), strings.get("toast.testBody"));
        }
        return y + dh + 24;

    }

    private float catSection(Ui ui, float x, float y, float w) {
        y = heading(ui, CAT, x, y, w);
        boolean cat = ui.checkbox(x, y + 4, strings.get("cat.toggle"), options.cat);
        if (cat != options.cat) {
            options.cat = cat;
            changed();
        }
        return y + 48;
    }

    private float recordingSection(Ui ui, float x, float y, float w) {
        y = heading(ui, RECORDING, x, y, w);
        return recordingView.recording(ui, x, y, w) + 16;
    }

    private float hotkeysSection(Ui ui, float x, float y, float w) {
        y = heading(ui, HOTKEYS, x, y, w);
        return recordingView.hotkeys(ui, x, y, w) + 16;
    }

    private float browserSection(Ui ui, float x, float y, float w) {
        y = heading(ui, BROWSER, x, y, w);
        ui.text(ui.regular, 13, x, y + 4, Ui.MUTED, strings.get("settings.browserHome"));
        y += 26;
        if (ui.field(homeEdit, x, y, Math.min(420, w), 32, false, "https://www.google.com/")) {
            options.browserHome = homeEdit.value();
            changed();
        }
        y += 44;
        ui.text(ui.regular, 13, x, y + 4, Ui.MUTED, strings.get("settings.browserSearch"));
        float px = x + ui.measure(ui.regular, 13, strings.get("settings.browserSearch")) + 16;
        for (String[] engine : BrowserView.ENGINES) {
            float pw = ui.measure(ui.bold, 13, engine[1]) + 20;
            if (ui.pill(px, y, pw, 26, engine[1], engine[0].equals(options.browserSearch))) {
                options.browserSearch = engine[0];
                changed();
            }
            px += pw + 4;
        }
        y += 40;
        boolean bar = ui.checkbox(x, y, strings.get("settings.browserBar"), options.bookmarksBar);
        if (bar != options.bookmarksBar) {
            options.bookmarksBar = bar;
            changed();
        }
        y += 32;
        String clear = strings.get("settings.browserClear");
        float bw = ui.buttonWidth(clear);
        if (ui.button(x, y, bw, 32, clear, false)) {
            clearFailed = !overlay.clearBrowserCookies();
            clearedAt = System.currentTimeMillis();
        }
        if (clearedAt != 0 && System.currentTimeMillis() - clearedAt < 4000) {
            ui.text(ui.regular, 13, x + bw + 12, y + 7, clearFailed ? Ui.DANGER : Ui.OK,
                    strings.get(clearFailed ? "settings.browserStopped" : "settings.browserCleared"));
        }
        return y + 48;
    }

    private float hardwareSection(Ui ui, float x, float y, float w) {
        y = heading(ui, HARDWARE, x, y, w);
        boolean on = ui.checkbox(x, y + 2, strings.get("hw.toggle"), options.fakeHardware);
        if (on != options.fakeHardware) {
            options.fakeHardware = on;
            changed();
        }
        y += 40;
        float labelW = 150;
        float fw = Math.min(320, w - labelW);
        if (hardwareRow(ui, x, y, labelW, fw, "hw.cpu", fakeCpu)) {
            options.fakeCpu = fakeCpu.value();
            changed();
        }
        y += 40;
        if (hardwareRow(ui, x, y, labelW, fw, "hw.threads", fakeThreads)) {
            options.fakeThreads = number(fakeThreads, options.fakeThreads, 1024);
            changed();
        }
        y += 40;
        if (hardwareRow(ui, x, y, labelW, fw, "hw.gpu", fakeGpu)) {
            options.fakeGpu = fakeGpu.value();
            changed();
        }
        y += 40;
        if (hardwareRow(ui, x, y, labelW, fw, "hw.vendor", fakeVendor)) {
            options.fakeVendor = fakeVendor.value();
            changed();
        }
        y += 40;
        if (hardwareRow(ui, x, y, labelW, fw, "hw.memory", fakeMemory)) {
            options.fakeMemory = number(fakeMemory, options.fakeMemory, 16777216);
            changed();
        }
        y += 40;
        ui.textFit(ui.regular, 12, x, y, w, Ui.FAINT, strings.get("hw.hint"));
        return y + 40;
    }

    private boolean hardwareRow(Ui ui, float x, float y, float labelW, float fieldW, String label, TextEdit edit) {
        ui.text(ui.regular, 13, x, y + 8, Ui.MUTED, strings.get(label));
        return ui.field(edit, x + labelW, y, fieldW, 30, false, "");
    }

    /** The field's number when it is a whole number from 1 to {@code max}; otherwise the old value. */
    private static int number(TextEdit edit, int old, int max) {
        try {
            int value = Integer.parseInt(edit.value().trim());
            return value >= 1 && value <= max ? value : old;
        } catch (NumberFormatException e) {
            return old;
        }
    }
}
