package dev.k0t1k.overlay.impl;

/** The performance tab and the small HUD shown while playing. */
final class PerfView {
    private final Perf perf;
    private final Options options;
    private final Strings strings;
    private final Runnable saveSettings;
    /** GL renderer and version, read once by the overlay on the render thread. */
    String renderer = "";
    private final String system;

    PerfView(Perf perf, Options options, Runnable saveSettings) {
        this.perf = perf;
        this.options = options;
        this.strings = options.strings;
        this.saveSettings = saveSettings;
        system = "Java " + System.getProperty("java.version") + " · " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + " · " + Runtime.getRuntime().availableProcessors() + " CPU";
    }

    static int frameColor(float ms) {
        return ms <= 20 ? Ui.OK : ms <= 40 ? Ui.WARN : Ui.DANGER;
    }

    static String mb(long bytes) {
        return bytes >= 1L << 30 ? String.format("%.1f GB", bytes / (double) (1L << 30)) : (bytes >> 20) + " MB";
    }

    void draw(Ui ui, float x, float y, float w, float h) {
        float gap = 12;
        float cardW = (w - gap * 5) / 6;
        float cardH = 74;
        card(ui, x, y, cardW, cardH, strings.get("perf.fps"), Integer.toString(perf.fps), Ui.FG, null);
        card(ui, x + (cardW + gap), y, cardW, cardH, strings.get("perf.frame"),
                String.format("%.1f ms", perf.average), frameColor(perf.average), null);
        card(ui, x + (cardW + gap) * 2, y, cardW, cardH, strings.get("perf.low"),
                String.format("%.1f ms", perf.worst), frameColor(perf.worst), null);
        float heapShare = perf.heapMax > 0 ? perf.heapUsed / (float) perf.heapMax : 0;
        card(ui, x + (cardW + gap) * 3, y, cardW, cardH, strings.get("perf.heap"), mb(perf.heapUsed),
                heapShare > 0.9f ? Ui.DANGER : Ui.FG, "/ " + mb(perf.heapMax));
        card(ui, x + (cardW + gap) * 4, y, cardW, cardH, strings.get("perf.gc"),
                perf.gcRecentMillis + " ms", perf.gcRecentMillis > 50 ? Ui.WARN : Ui.FG,
                strings.format("perf.gcBody", perf.gcCount, perf.gcMillis));
        card(ui, x + (cardW + gap) * 5, y, cardW, cardH, strings.get("perf.cpu"),
                perf.cpu < 0 ? "—" : Math.round(perf.cpu * 100) + "%", Ui.FG, strings.format("perf.threads", perf.threads));

        float gy = y + cardH + gap;
        float gh = h - cardH - gap - 90;
        ui.panel(x, gy, w, gh, 10, Ui.BASE, Ui.LINE);
        int frames = Math.min(perf.count, 300);
        ui.text(ui.bold, 12, x + 14, gy + 10, Ui.MUTED, strings.format("perf.graph", frames));
        graph(ui, x + 14, gy + 32, w - 28, gh - 46, frames, true);

        float sy = gy + gh + gap;
        boolean hud = ui.checkbox(x, sy + 4, strings.get("perf.hud"), options.hud);
        if (hud != options.hud) {
            options.hud = hud;
            saveSettings.run();
        }
        String corner = strings.get("perf.corner");
        float cx = x + 300;
        ui.text(ui.regular, 13, cx, sy + 5, Ui.MUTED, corner);
        cx += ui.measure(ui.regular, 13, corner) + 10;
        String[] arrows = {"↖", "↗", "↙", "↘"};
        for (int i = 0; i < 4; i++) {
            if (ui.pill(cx + i * 34, sy, 30, 26, arrows[i], options.hudCorner == i) && options.hudCorner != i) {
                options.hudCorner = i;
                saveSettings.run();
            }
        }
        ui.textFit(ui.regular, 12, x, sy + 40, w, Ui.FAINT, strings.get("perf.system") + ": " + system);
        ui.textFit(ui.regular, 12, x, sy + 58, w, Ui.FAINT, renderer);
    }

    private static void card(Ui ui, float x, float y, float w, float h, String label, String value, int color, String sub) {
        ui.panel(x, y, w, h, 10, Ui.RAISED, Ui.LINE);
        ui.textFit(ui.regular, 12, x + 12, y + 10, w - 24, Ui.MUTED, label);
        ui.textFit(ui.bold, 22, x + 12, y + 28, w - 24, color, value);
        if (sub != null) {
            float vw = Math.min(w - 24, ui.measure(ui.bold, 22, value));
            ui.textFit(ui.regular, 11, x + 12 + vw + 6, y + 38, w - 30 - vw, Ui.FAINT, sub);
        }
    }

    /** Bars of the last {@code frames} frame times, newest on the right, scaled to the slowest one. */
    void graph(Ui ui, float x, float y, float w, float h, int frames, boolean guides) {
        if (frames == 0) {
            return;
        }
        float max = 16.7f;
        for (int i = 0; i < frames; i++) {
            max = Math.max(max, perf.frame(i));
        }
        max = Math.min(max * 1.1f, 250);
        float bar = w / frames;
        for (int i = 0; i < frames; i++) {
            float ms = perf.frame(i);
            float bh = Math.max(1, h * Math.min(ms, max) / max);
            ui.rect(x + w - (i + 1) * bar, y + h - bh, Math.max(bar - (bar > 3 ? 1 : 0), 0.6f), bh, frameColor(ms));
        }
        // Guides go over the bars so they stay readable.
        if (guides) {
            for (float ms : new float[] {16.7f, 33.3f}) {
                if (ms < max) {
                    float gy = y + h - h * ms / max;
                    ui.rect(x, gy, w, 1, 0x60ece8f6);
                    ui.text(ui.regular, 10, x + 4, gy - 13, Ui.FG, ms < 20 ? "60 FPS" : "30 FPS");
                }
            }
        }
    }

    void drawHud(Ui ui, float screenW, float screenH) {
        float w = 196;
        float h = 64;
        float m = 8;
        float x = options.hudCorner % 2 == 0 ? m : screenW - w - m;
        float y = options.hudCorner < 2 ? m : screenH - h - m;
        ui.round(x, y, w, h, 8, 0xc00b0910);
        float tw = ui.text(ui.bold, 15, x + 10, y + 7, Ui.FG, Integer.toString(perf.fps));
        ui.text(ui.regular, 11, x + 10 + tw + 4, y + 10, Ui.MUTED, "FPS");
        String frame = String.format("%.1f ms", perf.average);
        ui.text(ui.regular, 12, x + 82, y + 9, frameColor(perf.average), frame);
        String heap = mb(perf.heapUsed);
        ui.text(ui.regular, 12, x + w - 10 - ui.measure(ui.regular, 12, heap), y + 9, Ui.MUTED, heap);
        graph(ui, x + 10, y + 30, w - 20, h - 38, 120, false);
    }
}
