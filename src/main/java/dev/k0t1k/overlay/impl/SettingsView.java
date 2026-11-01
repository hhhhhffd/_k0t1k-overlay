package dev.k0t1k.overlay.impl;

/**
 * The settings tab: a list of sections on the left and one scrolling column on the right. To add a
 * section, put its title key in {@link #SECTIONS}, write a method {@code float section(Ui ui, float x,
 * float y, float w)} that starts with {@link #heading} and returns the y below its last row, and call
 * it from {@link #draw} in the same order.
 */
final class SettingsView {
    private static final String[] SECTIONS = {"settings.hud", "settings.cat", "settings.replay"};
    private static final int HUD = 0;
    private static final int CAT = 1;
    private static final int REPLAY = 2;

    private final Options options;
    private final Strings strings;
    private final Runnable saveSettings;
    private float scroll;
    /** The y of the top of the column, so a section can tell where it starts within it. */
    private float viewTop;
    /** Where each section starts, as an offset from the top of the column; set while drawing. */
    private final float[] tops = new float[SECTIONS.length];

    SettingsView(Options options, Runnable saveSettings) {
        this.options = options;
        this.strings = options.strings;
        this.saveSettings = saveSettings;
    }

    void draw(Ui ui, float x, float y, float w, float h) {
        float listW = 150;
        float gap = 18;
        float cx = x + listW + gap;
        float cw = w - listW - gap;

        viewTop = y;
        float cy = y - scroll;
        ui.clip(cx, y, cw, h);
        cy = hudSection(ui, cx, cy, cw);
        cy = catSection(ui, cx, cy, cw);
        cy = replaySection(ui, cx, cy, cw);
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
        saveSettings.run();
    }

    private float hudSection(Ui ui, float x, float y, float w) {
        y = heading(ui, HUD, x, y, w);
        boolean hud = ui.checkbox(x, y + 4, strings.get("perf.hud"), options.hud);
        if (hud != options.hud) {
            options.hud = hud;
            changed();
        }
        String corner = strings.get("perf.corner");
        float cx = x + 300;
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
}
