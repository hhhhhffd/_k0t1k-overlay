package dev.k0t1k.overlay.impl;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * A font rasterised on demand into the shared atlas of {@link Batch}. Glyphs are cached per pixel
 * size, so any size and any codepoint the font has can be drawn without baking a fixed range up
 * front. {@link StbFont} rasterises with stb_truetype (shipped with every Minecraft since 1.13);
 * {@link AwtFont} with java.awt, for Minecraft versions on LWJGL 2, which has no stb.
 */
abstract class Font {
    private final Atlas atlas;
    private final Map<Long, Glyph> glyphs = new HashMap<>();
    private int generation;

    static final class Glyph {
        final float advance; // pixels
        final int x0;
        final int y0;
        final int w;
        final int h;
        final float u0;
        final float v0;
        final float u1;
        final float v1;

        Glyph(float advance, int x0, int y0, int w, int h, float u0, float v0, float u1, float v1) {
            this.advance = advance;
            this.x0 = x0;
            this.y0 = y0;
            this.w = w;
            this.h = h;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
        }
    }

    /** Shelf packer over the square atlas texture; shared by all fonts. */
    static final class Atlas {
        final int size;
        private final Batch batch;
        private int shelfX;
        private int shelfY;
        private int shelfH;
        private int generation;
        boolean full;

        Atlas(Batch batch) {
            this.batch = batch;
            this.size = Batch.ATLAS;
        }

        /** Returns {x, y} of a free w x h region, or null when the atlas is full. */
        int[] allocate(int w, int h) {
            if (shelfX + w + 1 > size) {
                shelfX = 0;
                shelfY += shelfH + 1;
                shelfH = 0;
            }
            if (shelfY + h + 1 > size) {
                full = true;
                return null;
            }
            int[] at = {shelfX, shelfY};
            shelfX += w + 1;
            shelfH = Math.max(shelfH, h);
            return at;
        }

        /** Forgets every glyph; called between frames when the atlas ran full. */
        void reset() {
            shelfX = 0;
            shelfY = 0;
            shelfH = 0;
            full = false;
            generation++;
        }
    }

    Font(Atlas atlas) {
        this.atlas = atlas;
    }

    /** Distance from the top of a line to its baseline. */
    abstract float ascent(float px);

    abstract float lineHeight(float px);

    /** Whether the font has an outline for the codepoint; others fall back to a placeholder. */
    abstract boolean has(int codepoint);

    abstract float kern(int left, int right, float px);

    /** Rasterises one glyph at an exact pixel size into the atlas. */
    abstract Glyph rasterise(int codepoint, float px);

    final Glyph glyph(int codepoint, float px) {
        if (generation != atlas.generation) {
            glyphs.clear();
            generation = atlas.generation;
        }
        int size = Math.round(px * 4); // quarter-pixel buckets
        long key = ((long) size << 32) | (codepoint & 0xffffffffL);
        Glyph glyph = glyphs.get(key);
        if (glyph == null) {
            glyph = rasterise(codepoint, size / 4f);
            glyphs.put(key, glyph);
        }
        return glyph;
    }

    /** Copies a rasterised glyph bitmap into the atlas; blank when the atlas is full. */
    final Glyph place(float advance, int x0, int y0, int w, int h, ByteBuffer pixels) {
        int[] at = atlas.allocate(w, h);
        if (at == null) {
            // Drawn as blank this frame; the overlay resets the atlas before the next one.
            return new Glyph(advance, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        atlas.batch.putGlyph(at[0], at[1], w, h, pixels);
        float s = atlas.size;
        return new Glyph(advance, x0, y0, w, h, at[0] / s, at[1] / s, (at[0] + w) / s, (at[1] + h) / s);
    }
}
