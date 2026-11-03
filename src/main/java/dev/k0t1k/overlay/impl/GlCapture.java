package dev.k0t1k.overlay.impl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;

/**
 * Reads the game's finished frame back for the recorder without stalling it, already converted to NV12
 * (BT.709, limited range) on the GPU: the frame is scaled into a texture, a shader writes the Y plane
 * and the interleaved UV plane into one single-channel texture of {@code w x 1.5h}, and that is read
 * into one of a few pixel buffers, mapped a couple of frames later when its fence says the copy is done.
 * The CPU then only copies {@code w * h * 1.5} bytes. Every piece of GL state touched is put back.
 */
final class GlCapture {
    private static final int SLOTS = 3;

    private static final String VERTEX = "#version 150\n"
            + "void main() {\n"
            + "  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n"
            + "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
            + "}\n";
    // Rows are written bottom-up like glReadPixels returns them, so memory order is top-down: the
    // output row r holds the picture's row r from the top. Pass 0 is the Y plane, pass 1 the UV plane,
    // whose texel pairs (U, V) cover 2 x 2 pixels; sampling the middle of the block averages them.
    private static final String FRAGMENT = "#version 150\n"
            + "uniform sampler2D uSource;\n"
            + "uniform vec2 uSize;\n"
            + "uniform int uPass;\n"
            + "out vec4 fragColor;\n"
            + "const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n"
            + "void main() {\n"
            + "  if (uPass == 0) {\n"
            + "    vec3 c = texture(uSource, vec2(gl_FragCoord.x / uSize.x, 1.0 - gl_FragCoord.y / uSize.y)).rgb;\n"
            + "    fragColor = vec4((16.0 + 219.0 * dot(c, LUMA)) / 255.0, 0.0, 0.0, 1.0);\n"
            + "  } else {\n"
            + "    float row = gl_FragCoord.y - uSize.y;\n"
            + "    float pair = floor(gl_FragCoord.x * 0.5);\n"
            + "    vec3 c = texture(uSource, vec2((pair * 2.0 + 1.0) / uSize.x, 1.0 - 2.0 * row / uSize.y)).rgb;\n"
            + "    float y = dot(c, LUMA);\n"
            + "    float v = (gl_FragCoord.x - pair * 2.0 < 1.0) ? (c.b - y) / 1.8556 : (c.r - y) / 1.5748;\n"
            + "    fragColor = vec4((128.0 + 224.0 * v) / 255.0, 0.0, 0.0, 1.0);\n"
            + "  }\n"
            + "}\n";

    private int width;
    private int height;
    private int program;
    private int uSize;
    private int uPass;
    private int vao;
    private int sourceTexture;
    private int sourceFbo;
    private int planeTexture;
    private int planeFbo;
    private final int[] pbo = new int[SLOTS];
    private final long[] fence = new long[SLOTS];
    private int next;
    private final IntBuffer ints = BufferUtils.createIntBuffer(16);
    private final ByteBuffer mask = BufferUtils.createByteBuffer(16);
    private final int[] viewport = new int[4];
    private final boolean samplers;
    private final boolean compatibility;

    GlCapture() {
        GLCapabilities caps = GL.getCapabilities();
        samplers = caps.OpenGL33 || caps.GL_ARB_sampler_objects;
        compatibility = GlRenderer.compatibility(caps);
    }

    /**
     * Queues a read of the current frame ({@code fw} x {@code fh} pixels, default framebuffer) at the
     * recorder's size, and hands any earlier read that finished to the recorder.
     */
    void capture(int fw, int fh, Recorder recorder) {
        int w = recorder.width();
        int h = recorder.height();
        int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int pack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int packAlignment = glGetInteger(GL_PACK_ALIGNMENT);
        int packRowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int usedProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int usedVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int texture2d = glGetInteger(GL_TEXTURE_BINDING_2D);
        int sampler = samplers ? glGetInteger(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING) : 0;
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean depth = glIsEnabled(GL_DEPTH_TEST);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean stencil = glIsEnabled(GL_STENCIL_TEST);
        boolean logicOp = glIsEnabled(GL_COLOR_LOGIC_OP);
        boolean offset = glIsEnabled(GL_POLYGON_OFFSET_FILL);
        ints.clear();
        glGetIntegerv(GL_VIEWPORT, ints);
        ints.get(viewport);
        ints.clear();
        glGetIntegerv(GL_POLYGON_MODE, ints);
        int polygonFront = ints.get(0);
        int polygonBack = ints.get(1);
        mask.clear();
        glGetBooleanv(GL_COLOR_WRITEMASK, mask);
        try {
            if (w != width || h != height) {
                resize(w, h);
            }
            collect(recorder);
            if (fence[next] != 0) {
                return; // all slots busy; skip this frame rather than wait
            }
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_COLOR_LOGIC_OP);
            glDisable(GL_POLYGON_OFFSET_FILL);
            glColorMask(true, true, true, true);
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sourceFbo);
            glBlitFramebuffer(0, 0, fw, fh, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_LINEAR);

            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, planeFbo);
            glUseProgram(program);
            glBindVertexArray(vao);
            glBindTexture(GL_TEXTURE_2D, sourceTexture);
            if (samplers) {
                org.lwjgl.opengl.GL33.glBindSampler(0, 0);
            }
            glUniform2f(uSize, w, h);
            glUniform1i(uPass, 0);
            glViewport(0, 0, w, h);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glUniform1i(uPass, 1);
            glViewport(0, h, w, h / 2);
            glDrawArrays(GL_TRIANGLES, 0, 3);

            glBindFramebuffer(GL_READ_FRAMEBUFFER, planeFbo);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[next]);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glReadPixels(0, 0, w, h * 3 / 2, GL_RED, GL_UNSIGNED_BYTE, 0L);
            fence[next] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            next = (next + 1) % SLOTS;
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pack);
            glPixelStorei(GL_PACK_ALIGNMENT, packAlignment);
            glPixelStorei(GL_PACK_ROW_LENGTH, packRowLength);
            glUseProgram(usedProgram);
            glBindVertexArray(usedVao);
            glBindTexture(GL_TEXTURE_2D, texture2d);
            if (samplers) {
                org.lwjgl.opengl.GL33.glBindSampler(0, sampler);
            }
            glActiveTexture(activeTexture);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            glColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
            GlRenderer.restorePolygonMode(compatibility, polygonFront, polygonBack);
            toggle(GL_SCISSOR_TEST, scissor);
            toggle(GL_BLEND, blend);
            toggle(GL_DEPTH_TEST, depth);
            toggle(GL_CULL_FACE, cull);
            toggle(GL_STENCIL_TEST, stencil);
            toggle(GL_COLOR_LOGIC_OP, logicOp);
            toggle(GL_POLYGON_OFFSET_FILL, offset);
        }
    }

    private static void toggle(int capability, boolean enabled) {
        if (enabled) {
            glEnable(capability);
        } else {
            glDisable(capability);
        }
    }

    /** Hands every finished read to the recorder as an NV12 frame; a read it has no buffer for is dropped. */
    private void collect(Recorder recorder) {
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
            int bytes = width * height * 3 / 2;
            ByteBuffer mapped = glMapBuffer(GL_PIXEL_PACK_BUFFER, GL_READ_ONLY, bytes, null);
            if (mapped != null) {
                Recorder.Frame frame = recorder.acquire(false);
                if (frame != null) {
                    mapped.get(frame.data, 0, bytes);
                    recorder.submit(frame);
                }
                glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
            }
        }
    }

    private void resize(int w, int h) {
        release();
        width = w;
        height = h;
        if (program == 0) {
            program = link();
            uSize = glGetUniformLocation(program, "uSize");
            uPass = glGetUniformLocation(program, "uPass");
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "uSource"), 0);
            vao = glGenVertexArrays();
        }
        sourceTexture = texture(w, h, GL_RGBA8, GL_RGBA);
        sourceFbo = framebuffer(sourceTexture);
        planeTexture = texture(w, h * 3 / 2, GL_R8, GL_RED);
        planeFbo = framebuffer(planeTexture);
        for (int i = 0; i < SLOTS; i++) {
            pbo[i] = glGenBuffers();
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[i]);
            glBufferData(GL_PIXEL_PACK_BUFFER, (long) w * h * 3 / 2, GL_STREAM_READ);
        }
    }

    private static int texture(int w, int h, int internalFormat, int format) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return texture;
    }

    private static int framebuffer(int texture) {
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        return fbo;
    }

    private static int link() {
        int vertex = compile(GL_VERTEX_SHADER, VERTEX);
        int fragment = compile(GL_FRAGMENT_SHADER, FRAGMENT);
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("capture program: " + glGetProgramInfoLog(program));
        }
        return program;
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("capture shader: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    /** Frees the buffers and textures; the next capture makes new ones. */
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
        if (sourceFbo != 0) {
            glDeleteFramebuffers(sourceFbo);
            glDeleteFramebuffers(planeFbo);
            glDeleteTextures(sourceTexture);
            glDeleteTextures(planeTexture);
            sourceFbo = planeFbo = sourceTexture = planeTexture = 0;
        }
        width = 0;
        height = 0;
    }
}
