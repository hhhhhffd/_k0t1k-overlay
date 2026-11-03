package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

/** The vertex layout the renderers read: eight floats, then the colour as the bytes R, G, B, A. */
class BatchTest {
    @Test
    void verticesHoldPositionUvParamsAndColourBytes() {
        Batch batch = new Batch();
        batch.begin(100, 100);
        batch.glyph(1, 2, 11, 22, 0.25f, 0.5f, 0.75f, 1, 0x80112233);
        batch.end();

        ByteBuffer v = batch.vertices;
        assertEquals(4 * Batch.STRIDE, v.limit());
        // The third vertex is the bottom right corner.
        int third = 2 * Batch.STRIDE;
        assertEquals(11f, v.getFloat(third));
        assertEquals(22f, v.getFloat(third + 4));
        assertEquals(0.75f, v.getFloat(third + 8));
        assertEquals(1f, v.getFloat(third + 12));
        assertEquals(1f, v.getFloat(third + 28)); // mode 1: a glyph
        assertEquals(0x11, v.get(third + 32) & 0xff);
        assertEquals(0x22, v.get(third + 33) & 0xff);
        assertEquals(0x33, v.get(third + 34) & 0xff);
        assertEquals(0x80, v.get(third + 35) & 0xff);
    }
}
