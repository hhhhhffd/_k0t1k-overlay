package dev.k0t1k.overlay.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30.*;

/**
 * Draws a {@link Batch} with OpenGL 3.2 core (or a compatibility context of at least 3.2): rounded
 * rectangles as a signed distance in the fragment shader, glyphs from a single-channel atlas.
 *
 * <p>Minecraft caches GL state in {@code GlStateManager}, so every piece of state touched here is read
 * before drawing and put back afterwards; otherwise the game would render with wrong state.
 */
final class GlRenderer {
    private static final String VERTEX = "#version 150\n"
            + "uniform vec2 uViewport;\n"
            + "in vec2 aPos; in vec2 aUv; in vec4 aColor; in vec4 aParams;\n"
            + "out vec2 vUv; out vec4 vColor; out vec4 vParams;\n"
            + "void main() {\n"
            + "  vUv = aUv; vColor = aColor; vParams = aParams;\n"
            + "  gl_Position = vec4(aPos.x / uViewport.x * 2.0 - 1.0, 1.0 - aPos.y / uViewport.y * 2.0, 0.0, 1.0);\n"
            + "}\n";
    // vParams = (half width, half height, corner radius, mode); mode 1 samples the glyph atlas and
    // vUv is a texture coordinate, mode 2 samples the image the same way, mode 0 is a rounded box and
    // vUv is the offset from its centre.
    private static final String FRAGMENT = "#version 150\n"
            + "uniform sampler2D uAtlas;\n"
            + "uniform sampler2D uImage;\n"
            + "in vec2 vUv; in vec4 vColor; in vec4 vParams;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "  if (vParams.w > 1.5) {\n"
            + "    fragColor = vec4(texture(uImage, vUv).rgb, vColor.a);\n"
            + "    return;\n"
            + "  }\n"
            + "  if (vParams.w > 0.5) {\n"
            + "    fragColor = vec4(vColor.rgb, vColor.a * texture(uAtlas, vUv).r);\n"
            + "    return;\n"
            + "  }\n"
            + "  vec2 q = abs(vUv) - vParams.xy + vParams.z;\n"
            + "  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - vParams.z;\n"
            + "  fragColor = vec4(vColor.rgb, vColor.a * clamp(0.5 - d, 0.0, 1.0));\n"
            + "}\n";

    private static final int STRIDE = Batch.STRIDE;
    private static final int MAX_QUADS = Batch.MAX_QUADS;

    private final int program;
    private final int uViewport;
    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int atlas;
    private final int image;
    private long imageVersion = -1;
    private final boolean samplers;
    private int height;

    private final IntBuffer ints = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    private final int[] saved = new int[32];
    private final int[] savedViewport = new int[4];
    private final int[] savedScissor = new int[4];
    private final boolean[] savedColorMask = new boolean[4];
    private final ByteBuffer mask = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    /** A compatibility context (Minecraft 1.13-1.16), where fixed-function alpha testing still applies. */
    private final boolean legacy;

    GlRenderer() {
        GLCapabilities caps = GL.getCapabilities();
        samplers = caps.OpenGL33 || caps.GL_ARB_sampler_objects;
        legacy = !caps.OpenGL32
                || (glGetInteger(org.lwjgl.opengl.GL32.GL_CONTEXT_PROFILE_MASK) & org.lwjgl.opengl.GL32.GL_CONTEXT_CORE_PROFILE_BIT) == 0;
        saveState();
        try {
            program = link(compile(GL_VERTEX_SHADER, VERTEX), compile(GL_FRAGMENT_SHADER, FRAGMENT));
            uViewport = glGetUniformLocation(program, "uViewport");

            vao = glGenVertexArrays();
            glBindVertexArray(vao);
            vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            int pos = glGetAttribLocation(program, "aPos");
            int uv = glGetAttribLocation(program, "aUv");
            int params = glGetAttribLocation(program, "aParams");
            int color = glGetAttribLocation(program, "aColor");
            glEnableVertexAttribArray(pos);
            glVertexAttribPointer(pos, 2, GL_FLOAT, false, STRIDE, 0);
            glEnableVertexAttribArray(uv);
            glVertexAttribPointer(uv, 2, GL_FLOAT, false, STRIDE, 8);
            glEnableVertexAttribArray(params);
            glVertexAttribPointer(params, 4, GL_FLOAT, false, STRIDE, 16);
            glEnableVertexAttribArray(color);
            glVertexAttribPointer(color, 4, GL_UNSIGNED_BYTE, true, STRIDE, 32);

            ByteBuffer indices = ByteBuffer.allocateDirect(MAX_QUADS * 6 * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < MAX_QUADS; i++) {
                int v = i * 4;
                indices.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
            }
            indices.flip();
            ebo = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

            atlas = glGenTextures();
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, atlas);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            // The game leaves its own unpack state behind; with a large row length the upload would read
            // past the end of our buffer.
            resetUnpack();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, Batch.ATLAS, Batch.ATLAS, 0, GL_RED, GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);

            image = glGenTextures();
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, image);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
            glActiveTexture(GL_TEXTURE0);

            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "uAtlas"), 0);
            glUniform1i(glGetUniformLocation(program, "uImage"), 1);
        } finally {
            restoreState();
        }
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("shader: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static int link(int vertex, int fragment) {
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        // Some compatibility drivers draw nothing unless generic attribute 0 is in use.
        glBindAttribLocation(program, 0, "aPos");
        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("program: " + glGetProgramInfoLog(program));
        }
        return program;
    }

    /** Draws the batch over the default framebuffer. */
    void draw(Batch batch) {
        height = batch.height;
        saveState();
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glViewport(0, 0, batch.width, batch.height);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_CULL_FACE);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_COLOR_LOGIC_OP);
            glDisable(GL_POLYGON_OFFSET_FILL);
            glEnable(GL_BLEND);
            glBlendEquation(GL_FUNC_ADD);
            // Leave destination alpha alone: some compositors use the window's alpha channel.
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
            glColorMask(true, true, true, true);
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            if (legacy) {
                glDisable(GL_ALPHA_TEST);
            }
            glUseProgram(program);
            glUniform2f(uViewport, batch.width, batch.height);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, atlas);
            if (samplers) {
                org.lwjgl.opengl.GL33.glBindSampler(0, 0);
            }
            resetUnpack();
            if (batch.atlasDirty()) {
                int[] d = batch.dirty;
                glPixelStorei(GL_UNPACK_ROW_LENGTH, Batch.ATLAS);
                ByteBuffer region = batch.atlas.duplicate();
                region.position(d[1] * Batch.ATLAS + d[0]);
                glTexSubImage2D(GL_TEXTURE_2D, 0, d[0], d[1], d[2] - d[0], d[3] - d[1], GL_RED, GL_UNSIGNED_BYTE, region);
                glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
                batch.atlasUploaded();
            }
            if (batch.image != null && batch.imageVersion != imageVersion) {
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, image);
                if (samplers) {
                    org.lwjgl.opengl.GL33.glBindSampler(1, 0);
                }
                ByteBuffer pixels = batch.image.duplicate();
                pixels.position(0).limit(batch.imageWidth * batch.imageHeight * 4);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, batch.imageWidth, batch.imageHeight, 0, GL_BGRA, GL_UNSIGNED_BYTE, pixels);
                imageVersion = batch.imageVersion;
                glActiveTexture(GL_TEXTURE0);
            }
            if (batch.image != null) {
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, image);
                if (samplers) {
                    org.lwjgl.opengl.GL33.glBindSampler(1, 0);
                }
                glActiveTexture(GL_TEXTURE0);
            }
            // A fresh store each time, so the driver does not wait for the previous draw.
            glBufferData(GL_ARRAY_BUFFER, batch.vertices, GL_STREAM_DRAW);
            for (Batch.Draw d : batch.draws) {
                if (d.clip == null) {
                    glDisable(GL_SCISSOR_TEST);
                } else {
                    glEnable(GL_SCISSOR_TEST);
                    glScissor(d.clip[0], height - d.clip[3], d.clip[2] - d.clip[0], d.clip[3] - d.clip[1]);
                }
                glDrawElements(GL_TRIANGLES, d.quads * 6, GL_UNSIGNED_INT, (long) d.firstQuad * 6 * 4);
            }
        } finally {
            restoreState();
        }
    }

    private static void resetUnpack() {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    }

    private int get(int name) {
        return glGetInteger(name);
    }

    private void saveState() {
        int i = 0;
        saved[i++] = get(GL_CURRENT_PROGRAM);
        saved[i++] = get(GL_VERTEX_ARRAY_BINDING);
        saved[i++] = get(GL_ARRAY_BUFFER_BINDING);
        saved[i++] = get(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE1);
        saved[i++] = get(GL_TEXTURE_BINDING_2D);
        saved[i++] = samplers ? get(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING) : 0;
        glActiveTexture(GL_TEXTURE0);
        saved[i++] = get(GL_TEXTURE_BINDING_2D);
        saved[i++] = samplers ? get(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING) : 0;
        saved[i++] = get(GL_DRAW_FRAMEBUFFER_BINDING);
        saved[i++] = glIsEnabled(GL_BLEND) ? 1 : 0;
        saved[i++] = get(GL_BLEND_SRC_RGB);
        saved[i++] = get(GL_BLEND_DST_RGB);
        saved[i++] = get(GL_BLEND_SRC_ALPHA);
        saved[i++] = get(GL_BLEND_DST_ALPHA);
        saved[i++] = get(GL_BLEND_EQUATION_RGB);
        saved[i++] = get(GL_BLEND_EQUATION_ALPHA);
        saved[i++] = glIsEnabled(GL_DEPTH_TEST) ? 1 : 0;
        saved[i++] = glIsEnabled(GL_CULL_FACE) ? 1 : 0;
        saved[i++] = glIsEnabled(GL_STENCIL_TEST) ? 1 : 0;
        saved[i++] = glIsEnabled(GL_SCISSOR_TEST) ? 1 : 0;
        saved[i++] = glIsEnabled(GL_COLOR_LOGIC_OP) ? 1 : 0;
        saved[i++] = glIsEnabled(GL_POLYGON_OFFSET_FILL) ? 1 : 0;
        saved[i++] = get(GL_UNPACK_ALIGNMENT);
        saved[i++] = get(GL_UNPACK_ROW_LENGTH);
        saved[i++] = get(GL_UNPACK_SKIP_ROWS);
        saved[i++] = get(GL_UNPACK_SKIP_PIXELS);
        saved[i++] = get(GL_PIXEL_UNPACK_BUFFER_BINDING);
        saved[i++] = legacy && glIsEnabled(GL_ALPHA_TEST) ? 1 : 0;
        ints.clear();
        glGetIntegerv(GL_POLYGON_MODE, ints);
        saved[i] = ints.get(0);
        ints.clear();
        glGetIntegerv(GL_VIEWPORT, ints);
        ints.get(savedViewport);
        ints.clear();
        glGetIntegerv(GL_SCISSOR_BOX, ints);
        ints.get(savedScissor);
        mask.clear();
        glGetBooleanv(GL_COLOR_WRITEMASK, mask);
        for (int c = 0; c < 4; c++) {
            savedColorMask[c] = mask.get(c) != 0;
        }
    }

    private void restoreState() {
        int i = 0;
        glUseProgram(saved[i++]);
        glBindVertexArray(saved[i++]);
        glBindBuffer(GL_ARRAY_BUFFER, saved[i++]);
        int activeTexture = saved[i++];
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, saved[i++]);
        int sampler1 = saved[i++];
        if (samplers) {
            org.lwjgl.opengl.GL33.glBindSampler(1, sampler1);
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, saved[i++]);
        int sampler = saved[i++];
        if (samplers) {
            org.lwjgl.opengl.GL33.glBindSampler(0, sampler);
        }
        glActiveTexture(activeTexture);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, saved[i++]);
        toggle(GL_BLEND, saved[i++]);
        glBlendFuncSeparate(saved[i], saved[i + 1], saved[i + 2], saved[i + 3]);
        i += 4;
        glBlendEquationSeparate(saved[i], saved[i + 1]);
        i += 2;
        toggle(GL_DEPTH_TEST, saved[i++]);
        toggle(GL_CULL_FACE, saved[i++]);
        toggle(GL_STENCIL_TEST, saved[i++]);
        toggle(GL_SCISSOR_TEST, saved[i++]);
        toggle(GL_COLOR_LOGIC_OP, saved[i++]);
        toggle(GL_POLYGON_OFFSET_FILL, saved[i++]);
        glPixelStorei(GL_UNPACK_ALIGNMENT, saved[i++]);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, saved[i++]);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, saved[i++]);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, saved[i++]);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, saved[i++]);
        if (legacy) {
            toggle(GL_ALPHA_TEST, saved[i]);
        }
        i++;
        glPolygonMode(GL_FRONT_AND_BACK, saved[i]);
        glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        glScissor(savedScissor[0], savedScissor[1], savedScissor[2], savedScissor[3]);
        glColorMask(savedColorMask[0], savedColorMask[1], savedColorMask[2], savedColorMask[3]);
    }

    private static void toggle(int cap, int enabled) {
        if (enabled != 0) {
            glEnable(cap);
        } else {
            glDisable(cap);
        }
    }
}
