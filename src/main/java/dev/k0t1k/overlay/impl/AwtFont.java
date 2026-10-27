package dev.k0t1k.overlay.impl;

import java.awt.Color;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A font rasterised with java.awt, for Minecraft versions on LWJGL 2, which has no stb_truetype.
 * Sizes follow {@link StbFont}: a pixel size is the height from descent to ascent, not the em size.
 */
final class AwtFont extends Font {
    private static final float REFERENCE = 1000;
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, true);

    private final java.awt.Font base;
    /** Ascent and line height per unit of the pixel size. */
    private final float ascent;
    private final float lineHeight;
    /** Em size per unit of the pixel size. */
    private final float em;

    AwtFont(String resource, Atlas atlas) throws IOException {
        super(atlas);
        try {
            base = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new ByteArrayInputStream(Resources.read(resource)));
        } catch (FontFormatException e) {
            throw new IOException("unreadable font " + resource, e);
        }
        LineMetrics metrics = base.deriveFont(REFERENCE).getLineMetrics("", CONTEXT);
        float height = metrics.getAscent() + metrics.getDescent();
        ascent = metrics.getAscent() / height;
        lineHeight = (height + metrics.getLeading()) / height;
        em = REFERENCE / height;
    }

    @Override
    float ascent(float px) {
        return ascent * px;
    }

    @Override
    float lineHeight(float px) {
        return lineHeight * px;
    }

    @Override
    boolean has(int codepoint) {
        return base.canDisplay(codepoint);
    }

    /** No kerning table is read; pairs are spaced by their advances alone. */
    @Override
    float kern(int left, int right, float px) {
        return 0;
    }

    @Override
    Glyph rasterise(int codepoint, float px) {
        java.awt.Font sized = base.deriveFont(px * em);
        GlyphVector vector = sized.createGlyphVector(CONTEXT, new String(Character.toChars(codepoint)));
        float advance = vector.getGlyphMetrics(0).getAdvanceX();
        java.awt.Rectangle box = vector.getGlyphPixelBounds(0, CONTEXT, 0, 0);
        int w = box.width;
        int h = box.height;
        if (w <= 0 || h <= 0) {
            return new Glyph(advance, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.WHITE);
            g.drawGlyphVector(vector, -box.x, -box.y);
        } finally {
            g.dispose();
        }
        ByteBuffer pixels = ByteBuffer.allocateDirect(w * h);
        pixels.put(((DataBufferByte) image.getRaster().getDataBuffer()).getData(), 0, w * h).flip();
        return place(advance, box.x, box.y, w, h, pixels);
    }
}
