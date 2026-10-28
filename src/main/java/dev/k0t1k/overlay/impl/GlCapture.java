package dev.k0t1k.overlay.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;

/**
 * Reads the game's finished frame back for the replay buffer without stalling it: the frame is scaled
 * down into a small framebuffer, then read into one of a few pixel buffers, which is mapped a couple of
 * frames later when its fence says the copy is done. Every piece of GL state touched is put back.
 */
final class GlCapture {
    private static final int SLOTS = 3;

    private int width;
    private int height;
    private int fbo;
    private int texture;
    private final int[] pbo = new int[SLOTS];
    private final long[] fence = new long[SLOTS];
    private final long[] taken = new long[SLOTS];
    private int next;

    /**
     * Queues a read of the current frame ({@code fw} x {@code fh} pixels, default framebuffer) at
     * {@code w} x {@code h}, and hands any earlier read that finished to {@code done} as bottom-up RGBA.
     */
    void capture(int fw, int fh, int w, int h, long time, Consumer<Frame> done) {
        int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int pack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int packAlignment = glGetInteger(GL_PACK_ALIGNMENT);
        int packRowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int texture2d = glGetInteger(GL_TEXTURE_BINDING_2D);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        try {
            if (w != width || h != height) {
                resize(w, h);
            }
            collect(done);
            if (fence[next] != 0) {
                return; // all slots busy; skip this frame rather than wait
            }
            glDisable(GL_SCISSOR_TEST);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
            glBlitFramebuffer(0, 0, fw, fh, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_LINEAR);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[next]);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            fence[next] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            taken[next] = time;
            next = (next + 1) % SLOTS;
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pack);
            glPixelStorei(GL_PACK_ALIGNMENT, packAlignment);
            glPixelStorei(GL_PACK_ROW_LENGTH, packRowLength);
            glBindTexture(GL_TEXTURE_2D, texture2d);
            if (scissor) {
                glEnable(GL_SCISSOR_TEST);
            }
        }
    }

    /** A finished read: bottom-up RGBA rows, owned by the receiver. */
    static final class Frame {
        final ByteBuffer pixels;
        final int width;
        final int height;
        final long time;

        Frame(ByteBuffer pixels, int width, int height, long time) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.time = time;
        }
    }

    private void collect(Consumer<Frame> done) {
        for (int k = 0; k < SLOTS; k++) {
            int slot = (next + k) % SLOTS;
            if (fence[slot] == 0) {
                continue;
            }
            int status = glClientWaitSync(fence[slot], 0, 0);
            if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED) {
                continue;
            }
            glDeleteSync(fence[slot]);
            fence[slot] = 0;
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[slot]);
            ByteBuffer mapped = glMapBuffer(GL_PIXEL_PACK_BUFFER, GL_READ_ONLY, width * height * 4L, null);
            if (mapped != null) {
                ByteBuffer copy = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
                copy.put(mapped).flip();
                glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
                done.accept(new Frame(copy, width, height, taken[slot]));
            }
        }
    }

    private void resize(int w, int h) {
        release();
        width = w;
        height = h;
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        for (int i = 0; i < SLOTS; i++) {
            pbo[i] = glGenBuffers();
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[i]);
            glBufferData(GL_PIXEL_PACK_BUFFER, (long) w * h * 4, GL_STREAM_READ);
        }
    }

    /** Frees the GL objects; the next capture makes new ones. */
    void release() {
        for (int i = 0; i < SLOTS; i++) {
            if (fence[i] != 0) {
                glDeleteSync(fence[i]);
                fence[i] = 0;
            }
            if (pbo[i] != 0) {
                glDeleteBuffers(pbo[i]);
                pbo[i] = 0;
            }
        }
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
            fbo = 0;
        }
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
        width = 0;
        height = 0;
    }
}
