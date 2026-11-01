package dev.k0t1k.overlay.impl;

/**
 * The settings tab: a list of sections on the left and one scrolling column on the right. To add a
 * section, put its title key in {@link #SECTIONS}, write a method {@code float section(Ui ui, float x,
 * float y, float w)} that starts with {@link #heading} and returns the y below its last row, and call
 * it from {@link #draw} in the same order.
 */
final class SettingsView {
    private static final String[] SECTIONS = {"settings.interface", "settings.hud", "settings.cat", "settings.replay", "settings.browser"};
    private static final int INTERFACE = 0;
    private static final int HUD = 1;
    private static final int CAT = 2;
    private static final int REPLAY = 3;
    private static final int BROWSER = 4;

    private final Overlay overlay;
    private final Options options;
    private final Strings strings;
    /** The percent shown while the scale slider is dragged; the scale itself changes on release. */
    private int draggedScale;
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
        cy = browserSection(ui, cx, cy, cw);
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
}
