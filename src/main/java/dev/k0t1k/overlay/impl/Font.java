package dev.k0t1k.overlay.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.stb.STBTTFontinfo;

import static org.lwjgl.stb.STBTruetype.*;

/**
 * A TrueType font rasterised on demand with stb_truetype (shipped with every Minecraft since 1.13)
 * into the shared atlas of {@link Batch}. Glyphs are cached per pixel size, so any size and any
 * codepoint the font has can be drawn without baking a fixed range up front.
 */
final class Font {
    private final ByteBuffer data; // stb keeps pointers into it, so it must stay reachable
    private final STBTTFontinfo info = STBTTFontinfo.create();
    private final int ascent;
    private final int descent;
    private final int lineGap;
    private final Atlas atlas;
    private final Map<Long, Glyph> glyphs = new HashMap<>();
    private final IntBuffer a = ints();
    private final IntBuffer b = ints();
    private final IntBuffer c = ints();
    private final IntBuffer d = ints();

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

    private int generation;

    Font(String resource, Atlas atlas) throws IOException {
        this.atlas = atlas;
        byte[] bytes;
        try (InputStream in = Font.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing font " + resource);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            bytes = out.toByteArray();
        }
        data = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        data.put(bytes).flip();
        if (!stbtt_InitFont(info, data)) {
            throw new IOException("unreadable font " + resource);
        }
        stbtt_GetFontVMetrics(info, a, b, c);
        ascent = a.get(0);
        descent = b.get(0);
        lineGap = c.get(0);
    }

    private static IntBuffer ints() {
        return ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    private float scale(float px) {
        return stbtt_ScaleForPixelHeight(info, px);
    }

    /** Distance from the top of a line to its baseline. */
    float ascent(float px) {
        return ascent * scale(px);
    }

    float lineHeight(float px) {
        return (ascent - descent + lineGap) * scale(px);
    }

    Glyph glyph(int codepoint, float px) {
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

    /** Whether the font has an outline for the codepoint; others fall back to a placeholder. */
    boolean has(int codepoint) {
        return stbtt_FindGlyphIndex(info, codepoint) != 0;
    }

    private Glyph rasterise(int codepoint, float px) {
        float scale = scale(px);
        int index = stbtt_FindGlyphIndex(info, codepoint);
        stbtt_GetGlyphHMetrics(info, index, a, b);
        float advance = a.get(0) * scale;
        stbtt_GetGlyphBitmapBox(info, index, scale, scale, a, b, c, d);
        int x0 = a.get(0);
        int y0 = b.get(0);
        int w = c.get(0) - x0;
        int h = d.get(0) - y0;
        if (w <= 0 || h <= 0) {
            return new Glyph(advance, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        int[] at = atlas.allocate(w, h);
        if (at == null) {
            // Drawn as blank this frame; the overlay resets the atlas before the next one.
            return new Glyph(advance, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        ByteBuffer pixels = ByteBuffer.allocateDirect(w * h);
        stbtt_MakeGlyphBitmap(info, pixels, w, h, w, scale, scale, index);
        atlas.batch.putGlyph(at[0], at[1], w, h, pixels);
        float s = atlas.size;
        return new Glyph(advance, x0, y0, w, h, at[0] / s, at[1] / s, (at[0] + w) / s, (at[1] + h) / s);
    }

    float kern(int left, int right, float px) {
        return stbtt_GetCodepointKernAdvance(info, left, right) * scale(px);
    }
}
