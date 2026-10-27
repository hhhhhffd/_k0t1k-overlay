package dev.k0t1k.overlay.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * One frame of overlay geometry, built on the CPU and handed to whichever graphics API the game
 * uses: rounded rectangles and glyph quads in framebuffer pixels, split into draws by clip
 * rectangle, plus the glyph atlas pixels with the region changed since the last upload.
 *
 * <p>Vertex layout ({@link #STRIDE} bytes): position (2 floats), uv (2 floats), params (4 floats:
 * half width, half height, corner radius, mode) and colour (RGBA bytes). Mode 1 samples the atlas and
 * uv is a texture coordinate; mode 0 is a rounded box and uv is the offset from its centre. Quads are
 * drawn as the index pattern 0 1 2 2 3 0.
 */
final class Batch {
    static final int STRIDE = (2 + 2 + 4) * 4 + 4;
    static final int MAX_QUADS = 32768;
    static final int ATLAS = 2048;

    /** A run of quads sharing one clip; the clip is {x0, y0, x1, y1} in pixels, or null for none. */
    static final class Draw {
        final int firstQuad;
        int quads;
        final int[] clip;

        Draw(int firstQuad, int[] clip) {
            this.firstQuad = firstQuad;
            this.clip = clip;
        }
    }

    final ByteBuffer vertices = ByteBuffer.allocateDirect(MAX_QUADS * 4 * STRIDE).order(ByteOrder.nativeOrder());
    final List<Draw> draws = new ArrayList<>();
    int quads;
    int width;
    int height;
    private final ArrayDeque<int[]> clips = new ArrayDeque<>();
    private boolean overflowReported;

    /** Atlas pixels, one byte of coverage each, row-major {@link #ATLAS} x {@link #ATLAS}. */
    final ByteBuffer atlas = ByteBuffer.allocateDirect(ATLAS * ATLAS);
    /** The atlas region changed since the renderer last uploaded it, {x0, y0, x1, y1}; x0 > x1 when clean. */
    final int[] dirty = {ATLAS, ATLAS, 0, 0};

    void begin(int width, int height) {
        this.width = width;
        this.height = height;
        vertices.clear();
        draws.clear();
        clips.clear();
        quads = 0;
    }

    /** Finishes the frame: the vertex buffer is ready to read from position 0 to its limit. */
    void end() {
        vertices.flip();
    }

    boolean empty() {
        return quads == 0;
    }

    boolean atlasDirty() {
        return dirty[0] < dirty[2];
    }

    void atlasUploaded() {
        dirty[0] = dirty[1] = ATLAS;
        dirty[2] = dirty[3] = 0;
    }

    /** Copies a glyph bitmap ({@code w} x {@code h}, tightly packed) into the atlas at x, y. */
    void putGlyph(int x, int y, int w, int h, ByteBuffer pixels) {
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                atlas.put((y + row) * ATLAS + x + col, pixels.get(row * w + col));
            }
        }
        dirty[0] = Math.min(dirty[0], x);
        dirty[1] = Math.min(dirty[1], y);
        dirty[2] = Math.max(dirty[2], x + w);
        dirty[3] = Math.max(dirty[3], y + h);
    }

    /** Restricts drawing to a rectangle (framebuffer pixels), intersected with the enclosing clip. */
    void pushClip(float x, float y, float w, float h) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = (int) Math.ceil(x + w);
        int y1 = (int) Math.ceil(y + h);
        int[] outer = clips.peek();
        if (outer != null) {
            x0 = Math.max(x0, outer[0]);
            y0 = Math.max(y0, outer[1]);
            x1 = Math.min(x1, outer[2]);
            y1 = Math.min(y1, outer[3]);
        }
        clips.push(new int[] {x0, y0, Math.max(x0, x1), Math.max(y0, y1)});
    }

    void popClip() {
        clips.pop();
    }

    void rect(float x, float y, float w, float h, int argb) {
        roundRect(x, y, w, h, 0, argb);
    }

    void roundRect(float x, float y, float w, float h, float radius, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
            return;
        }
        float hw = w / 2;
        float hh = h / 2;
        float r = Math.min(radius, Math.min(hw, hh));
        // Grow by half a pixel so the anti-aliased edge is not cut off.
        float g = 0.5f;
        quad(x - g, y - g, x + w + g, y + h + g, -hw - g, -hh - g, hw + g, hh + g, hw, hh, r, 0, argb);
    }

    void glyph(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
        quad(x0, y0, x1, y1, u0, v0, u1, v1, 0, 0, 0, 1, argb);
    }

    private void quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
            float hw, float hh, float radius, float mode, int argb) {
        if (quads == MAX_QUADS) {
            if (!overflowReported) {
                overflowReported = true;
                Overlay.log("frame has more than " + MAX_QUADS + " quads; the rest is not drawn");
            }
            return;
        }
        int[] clip = clips.peek();
        Draw last = draws.isEmpty() ? null : draws.get(draws.size() - 1);
        if (last == null || last.clip != clip) {
            last = new Draw(quads, clip);
            draws.add(last);
        }
        last.quads++;
        vertex(x0, y0, u0, v0, hw, hh, radius, mode, argb);
        vertex(x1, y0, u1, v0, hw, hh, radius, mode, argb);
        vertex(x1, y1, u1, v1, hw, hh, radius, mode, argb);
        vertex(x0, y1, u0, v1, hw, hh, radius, mode, argb);
        quads++;
    }

    private void vertex(float x, float y, float u, float v, float hw, float hh, float radius, float mode, int argb) {
        vertices.putFloat(x).putFloat(y).putFloat(u).putFloat(v)
                .putFloat(hw).putFloat(hh).putFloat(radius).putFloat(mode)
                .put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
    }
}
