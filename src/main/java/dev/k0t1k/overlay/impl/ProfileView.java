package dev.k0t1k.overlay.impl;

/** The profile tab: lifetime stats across every game with the overlay, and the launcher's achievements. */
final class ProfileView {
    private final Stats stats;
    private final Options options;
    private final Strings strings;
    private final Runnable saveSettings;
    private float scroll;

    ProfileView(Stats stats, Options options, Runnable saveSettings) {
        this.stats = stats;
        this.options = options;
        this.strings = options.strings;
        this.saveSettings = saveSettings;
    }

    void draw(Ui ui, float x, float y, float w, float h) {
        String[] keys = {"playMillis", "sessions", "deaths", "advancements", "screenshots", "crashes"};
        float gap = 12;
        float cardW = (w - gap * (keys.length - 1)) / keys.length;
        float cardH = 70;
        for (int i = 0; i < keys.length; i++) {
            long value = stats.get(keys[i]);
            String shown = "playMillis".equals(keys[i]) ? hours(value) : Long.toString(value);
            float cx = x + i * (cardW + gap);
            ui.panel(cx, y, cardW, cardH, 10, Ui.RAISED, Ui.LINE);
            ui.textFit(ui.regular, 12, cx + 12, y + 12, cardW - 24, Ui.MUTED, strings.get("stat." + keys[i]));
            ui.textFit(ui.bold, 20, cx + 12, y + 32, cardW - 24, Ui.FG, shown);
        }

        float ay = y + cardH + gap + 4;
        ui.text(ui.bold, 14, x, ay, Ui.FG, strings.format("ach.title", stats.unlocked.size(), Stats.ACHIEVEMENTS.length));
        boolean cat = ui.checkbox(x + w - 16 - ui.measure(ui.regular, 13, strings.get("cat.toggle")) - 24, ay, strings.get("cat.toggle"), options.cat);
        if (cat != options.cat) {
            options.cat = cat;
            saveSettings.run();
        }
        ay += 30;
        int columns = w > 640 ? 3 : 2;
        float tileW = (w - gap * (columns - 1)) / columns;
        float tileH = 50;
        float step = tileH + 8;
        int rows = (Stats.ACHIEVEMENTS.length + columns - 1) / columns;
        float viewH = y + h - ay;
        scroll -= ui.takeScroll(x, ay, w, viewH) * step;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows * step - 8 - viewH)));
        ui.clip(x, ay, w, viewH);
        for (int i = 0; i < Stats.ACHIEVEMENTS.length; i++) {
            Stats.Achievement a = Stats.ACHIEVEMENTS[i];
            float tx = x + (i % columns) * (tileW + gap);
            float ty = ay + (i / columns) * step - scroll;
            if (ty + tileH < ay || ty > y + h) {
                continue;
            }
            boolean done = stats.unlocked.contains(a.id);
            ui.panel(tx, ty, tileW, tileH, 10, done ? 0x26a259f7 : Ui.BASE, done ? 0xff6d3fc0 : Ui.LINE);
            ui.round(tx + 12, ty + 11, 28, 28, 8, done ? Ui.ACCENT : Ui.RAISED);
            if (done) {
                ui.round(tx + 21, ty + 20, 10, 10, 5, 0xffffffff);
            }
            ui.textFit(ui.bold, 13, tx + 52, ty + 8, tileW - 64, done ? Ui.FG : Ui.MUTED, strings.get("ach." + a.id));
            String detail = strings.get("ach." + a.id + ".d");
            if (!done && a.counter != null) {
                long have = stats.get(a.counter);
                detail += " · " + ("playMillis".equals(a.counter) ? hours(have) + " / " + hours(a.goal) : have + " / " + a.goal);
            }
            ui.textFit(ui.regular, 12, tx + 52, ty + 27, tileW - 64, done ? Ui.MUTED : Ui.FAINT, detail);
        }
        ui.unclip();
    }

    private String hours(long millis) {
        long minutes = millis / 60000;
        return strings.format("stat.hours", minutes / 60, minutes % 60);
    }
}
