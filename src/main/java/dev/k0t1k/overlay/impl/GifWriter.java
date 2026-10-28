package dev.k0t1k.overlay.impl;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A looping animated GIF writer. Every frame uses one fixed palette (6 x 7 x 6 levels of red, green and
 * blue) with ordered dithering: no per-frame colour search, so 30 seconds encode in a few seconds.
 */
final class GifWriter {
    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    private final OutputStream out;
    private final int width;
    private final int height;
    private final BufferedImage scaled;
    private final byte[] indices;

    GifWriter(OutputStream out, int width, int height) throws IOException {
        this.out = out;
        this.width = width;
        this.height = height;
        this.scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        this.indices = new byte[width * height];
        out.write("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        short16(width);
        short16(height);
        out.write(0xf7); // global colour table, 8 bits per channel, 256 entries
        out.write(0);
        out.write(0);
        for (int i = 0; i < 256; i++) {
            if (i < 252) {
                out.write(i / 42 * 255 / 5);
                out.write(i / 6 % 7 * 255 / 6);
                out.write(i % 6 * 255 / 5);
            } else {
                out.write(0);
                out.write(0);
                out.write(0);
            }
        }
        // NETSCAPE2.0: loop forever.
        out.write(new byte[] {0x21, (byte) 0xff, 0x0b});
        out.write("NETSCAPE2.0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(new byte[] {0x03, 0x01, 0x00, 0x00, 0x00});
    }

    /** Adds a frame shown for {@code delay} hundredths of a second, scaled to the GIF's size. */
    void frame(BufferedImage image, int delay) throws IOException {
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        int[] rgb = scaled.getRGB(0, 0, width, height, null, 0, width);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = rgb[y * width + x];
                // Threshold in [-0.5, 0.5) of one palette step.
                float t = BAYER[(y & 3) * 4 + (x & 3)] / 16f - 0.5f;
                int r = level((c >> 16) & 255, 5, t);
                int gr = level((c >> 8) & 255, 6, t);
                int b = level(c & 255, 5, t);
                indices[y * width + x] = (byte) (r * 42 + gr * 6 + b);
            }
        }
        // Graphic control extension: delay, no transparency.
        out.write(new byte[] {0x21, (byte) 0xf9, 0x04, 0x00});
        short16(delay);
        out.write(new byte[] {0x00, 0x00});
        // Image descriptor: full frame, global palette.
        out.write(0x2c);
        short16(0);
        short16(0);
        short16(width);
        short16(height);
        out.write(0);
        lzw(indices);
    }

    void finish() throws IOException {
        out.write(0x3b);
        out.flush();
    }

    private static int level(int value, int steps, float threshold) {
        int level = Math.round(value * steps / 255f + threshold);
        return Math.max(0, Math.min(steps, level));
    }

    private void short16(int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    // ---- LZW with 8-bit codes, variable code width up to 12 bits ----

    private final byte[] block = new byte[255];
    private int blockSize;
    private int bitBuffer;
    private int bitCount;

    private void lzw(byte[] data) throws IOException {
        final int minCodeSize = 8;
        final int clear = 1 << minCodeSize;
        final int end = clear + 1;
        out.write(minCodeSize);
        blockSize = 0;
        bitBuffer = 0;
        bitCount = 0;
        // Dictionary: key = prefix code << 8 | byte, open addressing.
        int tableSize = 5003;
        int[] keys = new int[tableSize];
        int[] codes = new int[tableSize];
        java.util.Arrays.fill(keys, -1);
        int nextCode = end + 1;
        int codeSize = minCodeSize + 1;
        write(clear, codeSize);
        int prefix = data[0] & 0xff;
        for (int i = 1; i < data.length; i++) {
            int c = data[i] & 0xff;
            int key = prefix << 8 | c;
            int slot = (key * 31 + 7) % tableSize;
            if (slot < 0) {
                slot += tableSize;
            }
            while (keys[slot] != -1 && keys[slot] != key) {
                slot = (slot + 1) % tableSize;
            }
            if (keys[slot] == key) {
                prefix = codes[slot];
                continue;
            }
            write(prefix, codeSize);
            if (nextCode < 4096) {
                keys[slot] = key;
                codes[slot] = nextCode++;
                if (nextCode > (1 << codeSize) && codeSize < 12) {
                    codeSize++;
                }
            } else {
                write(clear, codeSize);
                java.util.Arrays.fill(keys, -1);
                nextCode = end + 1;
                codeSize = minCodeSize + 1;
            }
            prefix = c;
        }
        write(prefix, codeSize);
        write(end, codeSize);
        if (bitCount > 0) {
            put(bitBuffer & 0xff);
        }
        if (blockSize > 0) {
            out.write(blockSize);
            out.write(block, 0, blockSize);
        }
        out.write(0);
    }

    private void write(int code, int size) throws IOException {
        bitBuffer |= code << bitCount;
        bitCount += size;
        while (bitCount >= 8) {
            put(bitBuffer & 0xff);
            bitBuffer >>>= 8;
            bitCount -= 8;
        }
    }

    private void put(int value) throws IOException {
        block[blockSize++] = (byte) value;
        if (blockSize == 255) {
            out.write(255);
            out.write(block, 0, 255);
            blockSize = 0;
        }
    }
}
