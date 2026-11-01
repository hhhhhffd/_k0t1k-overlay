package dev.k0t1k.overlay.impl;

/**
 * The settings tab: a list of sections on the left and one scrolling column on the right. To add a
 * section, put its title key in {@link #SECTIONS}, write a method {@code float section(Ui ui, float x,
 * float y, float w)} that starts with {@link #heading} and returns the y below its last row, and call
 * it from {@link #draw} in the same order.
 */
final class SettingsView {
    private static final String[] SECTIONS = {"settings.interface", "settings.hud", "settings.cat", "settings.replay",
        "settings.hardware"};
    private static final int INTERFACE = 0;
    private static final int HUD = 1;
    private static final int CAT = 2;
    private static final int REPLAY = 3;
    private static final int HARDWARE = 4;

    private final Overlay overlay;
    private final Options options;
    private final Strings strings;
    /** The percent shown while the scale slider is dragged; the scale itself changes on release. */
    private int draggedScale;
    private final TextEdit fakeCpu;
    private final TextEdit fakeThreads;
    private final TextEdit fakeGpu;
    private final TextEdit fakeVendor;
    private final TextEdit fakeMemory;
    private float scroll;
    /** The y of the top of the column, so a section can tell where it starts within it. */
    private float viewTop;
    /** Where each section starts, as an offset from the top of the column; set while drawing. */
    private final float[] tops = new float[SECTIONS.length];

    SettingsView(Overlay overlay) {
        this.overlay = overlay;
        this.options = overlay.options();
        this.strings = options.strings;
        fakeCpu = new TextEdit(options.fakeCpu);
        fakeThreads = new TextEdit(Integer.toString(options.fakeThreads));
        fakeGpu = new TextEdit(options.fakeGpu);
        fakeVendor = new TextEdit(options.fakeVendor);
        fakeMemory = new TextEdit(Integer.toString(options.fakeMemory));
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
        cy = catSection(ui, cx, cy, cw);
        cy = replaySection(ui, cx, cy, cw);
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
        String corner = strings.get("perf.corner");
        float cx = x + 40 + 24 + ui.measure(ui.regular, 13, strings.get("perf.hud"));
        ui.text(ui.regular, 13, cx, y + 5, Ui.MUTED, corner);
        cx += ui.measure(ui.regular, 13, corner) + 10;
        String[] arrows = {"↖", "↗", "↙", "↘"};
        for (int i = 0; i < 4; i++) {
            if (ui.pill(cx + i * 34, y, 30, 26, arrows[i], options.hudCorner == i) && options.hudCorner != i) {
                options.hudCorner = i;
                changed();
            }
        }
        return y + 48;
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

    private float replaySection(Ui ui, float x, float y, float w) {
        y = heading(ui, REPLAY, x, y, w);
        boolean on = ui.checkbox(x, y + 6, strings.get("replay.toggle"), options.replay);
        if (on != options.replay) {
            options.replay = on;
            changed();
        }
        float fx = x + ui.measure(ui.regular, 13, strings.get("replay.toggle")) + 40;
        for (String format : new String[] {"video", "gif"}) {
            String label = strings.get("replay." + format);
            float pw = ui.measure(ui.bold, 13, label) + 20;
            if (ui.pill(fx, y + 1, pw, 26, label, format.equals(options.replayFormat))) {
                options.replayFormat = format;
                changed();
            }
            fx += pw + 4;
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
