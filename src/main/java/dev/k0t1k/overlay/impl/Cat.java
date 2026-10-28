package dev.k0t1k.overlay.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The launcher's cat in a corner of the screen. It reacts to what happens in the game (a death, an
 * advancement, a long AFK, a timer) with a speech bubble; while the overlay is closed it only shows up
 * for those moments. Drawn from {@code cat.txt}, a pixel map of the mascot, as runs of rectangles.
 */
final class Cat {
    private static final long SHOW = 6000;
    private static final long SLIDE = 250;

    /** Horizontal runs {x, y, length, colour} of the pixel map. */
    private final List<int[]> runs = new ArrayList<>();
    /** The white pixels of the eyes, drawn closed while blinking. */
    private final List<int[]> eyes = new ArrayList<>();
    final int width;
    final int height;
    private final Random random = new Random();

    private String bubble;
    private long since;
    private long nextBlink = System.currentTimeMillis() + 3000;

    Cat() throws IOException {
        List<String> rows = new ArrayList<>();
        try (InputStream in = Cat.class.getResourceAsStream("cat.txt")) {
            if (in == null) {
                throw new IOException("cat.txt is missing from the overlay jar");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String row; (row = reader.readLine()) != null; ) {
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        }
        int w = 0;
        for (int y = 0; y < rows.size(); y++) {
            String row = rows.get(y);
            w = Math.max(w, row.length());
            int x = 0;
            while (x < row.length()) {
                char c = row.charAt(x);
                int end = x + 1;
                while (end < row.length() && row.charAt(end) == c) {
                    end++;
                }
                int colour = colour(c);
                if (colour != 0) {
                    int[] run = {x, y, end - x, colour};
                    runs.add(run);
                    // The eyes are the white runs in the lower half; the ears' insides are above.
                    if (c == 'w' && y > rows.size() / 2) {
                        eyes.add(run);
                    }
                }
                x = end;
            }
        }
        width = w;
        height = rows.size();
    }

    private static int colour(char c) {
        switch (c) {
            case 'k':
                return 0xff0c0c0c;
            case 'p':
                return 0xffb200ff;
            case 'w':
                return 0xffffffff;
            default:
                return 0;
        }
    }

    /** Shows one of the phrases in a bubble for a few seconds. */
    void say(String... phrases) {
        bubble = phrases[random.nextInt(phrases.length)];
        since = System.currentTimeMillis();
    }

    /** Whether the cat has something to say right now. */
    boolean talking(long now) {
        return bubble != null && now - since < SHOW;
    }

    /**
     * Draws the cat with its bottom left corner at (x, bottom). While closed it slides in from below
     * the screen edge only while talking.
     */
    void draw(Ui ui, float x, float bottom, float pixel, boolean always, long now) {
        boolean talking = talking(now);
        if (!talking && !always) {
            return;
        }
        float offset = 0;
        if (!always) {
            long age = now - since;
            float t = Math.min(1, Math.min(age, SHOW - age) / (float) SLIDE);
            offset = (1 - t * t * (3 - 2 * t)) * (height * pixel + 20);
        }
        // A small hop when it starts talking.
        long age = now - since;
        if (talking && age < 400) {
            offset -= (float) Math.sin(age / 400.0 * Math.PI) * 6;
        }
        float top = bottom - height * pixel + offset;
        if (now >= nextBlink + 140) {
            nextBlink = now + 2500 + random.nextInt(4000);
        }
        boolean blinking = now >= nextBlink;
        for (int[] run : runs) {
            int colour = blinking && eyes.contains(run) ? 0xff0c0c0c : run[3];
            ui.rect(x + run[0] * pixel, top + run[1] * pixel, run[2] * pixel + 0.01f, pixel + 0.01f, colour);
        }
        if (talking) {
            drawBubble(ui, x + width * pixel * 0.55f, top - 6, ui.measure(ui.regular, 13, bubble));
        }
    }

    private void drawBubble(Ui ui, float x, float bottom, float textW) {
        float w = Math.min(textW, 260) + 24;
        List<int[]> lines = ui.wrap(ui.regular, 13, bubble, w - 24);
        float lh = ui.lineHeight(ui.regular, 13);
        float h = lines.size() * lh + 16;
        float y = bottom - h;
        ui.panel(x, y, w, h, 10, 0xf21b1728, 0xffb200ff);
        ui.round(x + 14, bottom - 2, 8, 8, 2, 0xffb200ff);
        for (int i = 0; i < lines.size(); i++) {
            int[] span = lines.get(i);
            ui.text(ui.regular, 13, x + 12, y + 8 + i * lh, Ui.FG, bubble, span[0], span[1]);
        }
    }
}
