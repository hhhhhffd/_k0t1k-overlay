package dev.k0t1k.overlay.impl;

/** The performance tab and the small HUD shown while playing. */
final class PerfView {
    private final Perf perf;
    private final Options options;
    private final Strings strings;
    /** GL renderer and version, read once by the overlay on the render thread. */
    String renderer = "";
    private final String system;

    PerfView(Perf perf, Options options) {
        this.perf = perf;
        this.options = options;
        this.strings = options.strings;
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
        float gh = h - cardH - gap * 2 - 38;
        ui.panel(x, gy, w, gh, 10, Ui.BASE, Ui.LINE);
        int frames = Math.min(perf.count, 300);
        ui.text(ui.bold, 12, x + 14, gy + 10, Ui.MUTED, strings.format("perf.graph", frames));
        graph(ui, x + 14, gy + 32, w - 28, gh - 46, frames, true);

        float sy = gy + gh + gap;
        ui.textFit(ui.regular, 12, x, sy, w, Ui.FAINT, strings.get("perf.system") + ": " + system);
        ui.textFit(ui.regular, 12, x, sy + 18, w, Ui.FAINT, renderer);
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

    private static final float HUD_MARGIN = 8;
    private static final float HUD_WIDTH = 172;
    private static final float HUD_PAD = 8;
    private static final float HUD_ROW = 17;
    private static final float HUD_GRAPH = 30;
    private static final float SNAP = 12;

    /** Whether the HUD is being dragged in the edit mode, and where the pointer grabbed it. */
    private boolean dragging;
    private float grabX;
    private float grabY;

    /**
     * The HUD's box on a screen {@code screenW} by {@code screenH} units, written to {@code out} as
     * {x, y, width, height}. Drawing and the toasts that keep clear of it both use this.
     */
    void hudRect(float screenW, float screenH, float[] out) {
        float scale = options.hudSize / 100f;
        int rows = (options.hudFps ? 1 : 0) + (options.hudFrame ? 1 : 0) + (options.hudLow ? 1 : 0)
                + (options.hudHeap ? 1 : 0) + (options.hudCpu ? 1 : 0) + (options.hudGc ? 1 : 0);
        float h = HUD_PAD * 2 + rows * HUD_ROW + (options.hudGraph ? HUD_GRAPH + (rows > 0 ? 4 : 0) : 0);
        float w = HUD_WIDTH * scale;
        h *= scale;
        out[0] = HUD_MARGIN + options.hudX * Math.max(0, screenW - w - HUD_MARGIN * 2);
        out[1] = HUD_MARGIN + options.hudY * Math.max(0, screenH - h - HUD_MARGIN * 2);
        out[2] = w;
        out[3] = h;
    }

    void drawHud(Ui ui, float screenW, float screenH) {
        float[] r = new float[4];
        hudRect(screenW, screenH, r);
        drawHudAt(ui, r);
    }

    /** Lays the HUD out at 100% and draws it with the scale raised by the HUD's size. */
    private void drawHudAt(Ui ui, float[] r) {
        float k = options.hudSize / 100f;
        float outer = ui.scale;
        ui.scale = outer * k;
        try {
            drawHudBox(ui, r[0] / k, r[1] / k, r[2] / k, r[3] / k);
        } finally {
            ui.scale = outer;
        }
    }

    private void drawHudBox(Ui ui, float x, float y, float w, float h) {
        int alpha = Math.round(0xc0 * options.hudOpacity / 100f);
        ui.round(x, y, w, h, 8, alpha << 24 | 0x0b0910);
        float ty = y + HUD_PAD;
        float left = x + HUD_PAD + 2;
        float right = x + w - HUD_PAD - 2;
        if (options.hudFps) {
            row(ui, left, right, ty, "FPS", Integer.toString(perf.fps), Ui.FG, true);
            ty += HUD_ROW;
        }
        if (options.hudFrame) {
            row(ui, left, right, ty, strings.get("perf.frame"), String.format("%.1f ms", perf.average),
                    frameColor(perf.average), false);
            ty += HUD_ROW;
        }
        if (options.hudLow) {
            row(ui, left, right, ty, strings.get("perf.low"), String.format("%.1f ms", perf.worst),
                    frameColor(perf.worst), false);
            ty += HUD_ROW;
        }
        if (options.hudHeap) {
            float share = perf.heapMax > 0 ? perf.heapUsed / (float) perf.heapMax : 0;
            row(ui, left, right, ty, strings.get("perf.heap"), mb(perf.heapUsed) + " / " + mb(perf.heapMax),
                    share > 0.9f ? Ui.DANGER : Ui.FG, false);
            ty += HUD_ROW;
        }
        if (options.hudCpu) {
            row(ui, left, right, ty, "CPU", perf.cpu < 0 ? "—" : Math.round(perf.cpu * 100) + "%", Ui.FG, false);
            ty += HUD_ROW;
        }
        if (options.hudGc) {
            row(ui, left, right, ty, "GC", perf.gcRecentMillis + " ms", perf.gcRecentMillis > 50 ? Ui.WARN : Ui.FG,
                    false);
            ty += HUD_ROW;
        }
        if (options.hudGraph) {
            float gy = ty + (ty > y + HUD_PAD ? 4 : 0);
            graph(ui, x + HUD_PAD, gy, w - HUD_PAD * 2, y + h - HUD_PAD - gy, 120, false);
        }
    }

    /** A label on the left and its value on the right; the FPS value is drawn larger. */
    private static void row(Ui ui, float left, float right, float y, String label, String value, int color, boolean big) {
        Font font = big ? ui.bold : ui.regular;
        float size = big ? 15 : 12;
        ui.text(ui.regular, 12, left, y + 1, Ui.MUTED, label);
        ui.text(font, size, right - ui.measure(font, size, value), y + (big ? -1 : 1), color, value);
    }

    /**
     * The mode for placing the HUD: a veil over the game, the HUD as it looks in play, and a hint.
     * Dragging with the left button moves it, snapping to the screen edges and centre lines. Returns
     * true once the Done button is clicked (the overlay handles Esc and Enter itself).
     */
    boolean edit(Ui ui, float screenW, float screenH) {
        ui.rect(0, 0, screenW, screenH, 0x80060509);
        float[] r = new float[4];
        hudRect(screenW, screenH, r);
        if (ui.clicked && ui.hover(r[0], r[1], r[2], r[3])) {
            dragging = true;
            grabX = ui.mouseX - r[0];
            grabY = ui.mouseY - r[1];
        }
        if (dragging && !ui.mouseDown) {
            dragging = false;
        }
        if (dragging) {
            float freeX = screenW - r[2] - HUD_MARGIN * 2;
            float freeY = screenH - r[3] - HUD_MARGIN * 2;
            options.hudX = share(snap(ui.mouseX - grabX, HUD_MARGIN, freeX + HUD_MARGIN, (screenW - r[2]) / 2), freeX);
            options.hudY = share(snap(ui.mouseY - grabY, HUD_MARGIN, freeY + HUD_MARGIN, (screenH - r[3]) / 2), freeY);
            hudRect(screenW, screenH, r);
        }
        drawHudAt(ui, r);
        // Four bars outside the HUD: a filled outline would show through its translucent background.
        int outline = dragging || ui.hover(r[0], r[1], r[2], r[3]) ? Ui.ACCENT : Ui.FAINT;
        ui.rect(r[0] - 3, r[1] - 3, r[2] + 6, 2, outline);
        ui.rect(r[0] - 3, r[1] + r[3] + 1, r[2] + 6, 2, outline);
        ui.rect(r[0] - 3, r[1] - 1, 2, r[3] + 2, outline);
        ui.rect(r[0] + r[2] + 1, r[1] - 1, 2, r[3] + 2, outline);

        String hint = strings.get("perf.editHint");
        String done = strings.get("perf.editDone");
        float hw = ui.measure(ui.bold, 15, hint);
        float bw = ui.buttonWidth(done) + 24;
        float boxW = hw + 48;
        float boxX = (screenW - boxW) / 2;
        float boxY = screenH / 2 - 56;
        ui.panel(boxX, boxY, boxW, 104, 12, 0xf513101c, Ui.LINE);
        ui.text(ui.bold, 15, (screenW - hw) / 2, boxY + 18, Ui.FG, hint);
        return ui.button((screenW - bw) / 2, boxY + 54, bw, 32, done, true);
    }

    /** Drops a drag in progress when the mode ends. */
    void endEdit() {
        dragging = false;
    }

    private static float snap(float v, float min, float max, float middle) {
        if (Math.abs(v - min) < SNAP) {
            return min;
        }
        if (Math.abs(v - max) < SNAP) {
            return max;
        }
        return Math.abs(v - middle) < SNAP ? middle : v;
    }

    /** The offset {@code v} from the screen's edge as a share of the {@code free} space, clamped to 0 to 1. */
    private static float share(float v, float free) {
        return free <= 0 ? 0 : Math.max(0, Math.min(1, (v - HUD_MARGIN) / free));
    }
}
