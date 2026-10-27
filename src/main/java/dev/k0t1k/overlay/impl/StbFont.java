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

    private float scale(float px) {
        return stbtt_ScaleForPixelHeight(info, px);
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
        return stbtt_FindGlyphIndex(info, codepoint) != 0;
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
        return stbtt_GetCodepointKernAdvance(info, left, right) * scale(px);
    }
}
