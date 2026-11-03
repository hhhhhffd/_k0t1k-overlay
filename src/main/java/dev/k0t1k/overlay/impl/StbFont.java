package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.lwjgl.stb.STBTTFontinfo;

import static org.lwjgl.stb.STBTruetype.*;

/** A font rasterised with stb_truetype, which LWJGL 3 (Minecraft 1.13 and newer) ships. */
final class StbFont extends Font {
    private final ByteBuffer data; // stb keeps pointers into it, so it must stay reachable
    private final STBTTFontinfo info = STBTTFontinfo.create();
    private final int ascent;
    private final int descent;
    private final int lineGap;
    private final IntBuffer a = ints();
    private final IntBuffer b = ints();
    private final IntBuffer c = ints();
    private final IntBuffer d = ints();
    /**
     * Small direct-mapped caches in front of stb, which every glyph drawn or measured asks each frame:
     * a codepoint lookup and a kerning pair (Inter keeps its kerning in GPOS, a slow walk) each cost
     * about a hundred nanoseconds through JNI. A slot holds its key (-1 when empty) and the answer.
     */
    private static final int CACHE = 4096;
    private final long[] hasKeys = emptyKeys();
    private final boolean[] hasValues = new boolean[CACHE];
    private final long[] kernKeys = emptyKeys();
    private final int[] kernValues = new int[CACHE];

    StbFont(String resource, Atlas atlas) throws IOException {
        super(atlas);
        byte[] bytes = Resources.read(resource);
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

    private static long[] emptyKeys() {
        long[] keys = new long[CACHE];
        java.util.Arrays.fill(keys, -1);
        return keys;
    }

    private static int slot(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 52) & (CACHE - 1);
    }

    /** {@code stbtt_ScaleForPixelHeight}, computed here from the same vertical metrics. */
    private float scale(float px) {
        return px / (ascent - descent);
    }

    @Override
    float ascent(float px) {
        return ascent * scale(px);
    }

    @Override
    float lineHeight(float px) {
        return (ascent - descent + lineGap) * scale(px);
    }

    @Override
    boolean has(int codepoint) {
        int i = slot(codepoint);
        if (hasKeys[i] != codepoint) {
            hasValues[i] = stbtt_FindGlyphIndex(info, codepoint) != 0;
            hasKeys[i] = codepoint;
        }
        return hasValues[i];
    }

    @Override
    Glyph rasterise(int codepoint, float px) {
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
        ByteBuffer pixels = ByteBuffer.allocateDirect(w * h);
        stbtt_MakeGlyphBitmap(info, pixels, w, h, w, scale, scale, index);
        return place(advance, x0, y0, w, h, pixels);
    }

    @Override
    float kern(int left, int right, float px) {
        // Codepoints fit in 21 bits, so the pair packs into one non-negative key.
        long key = ((long) left << 21) | (right & 0x1fffff);
        int i = slot(key);
        if (kernKeys[i] != key) {
            kernValues[i] = stbtt_GetCodepointKernAdvance(info, left, right);
            kernKeys[i] = key;
        }
        return kernValues[i] * scale(px);
    }
}
