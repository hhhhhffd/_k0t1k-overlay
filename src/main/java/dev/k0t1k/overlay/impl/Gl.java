package dev.k0t1k.overlay.impl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30.*;

/**
 * A tiny batched 2D renderer on OpenGL 3.2 core: rounded rectangles (signed distance in the fragment
 * shader) and glyphs from a single-channel atlas, all in framebuffer pixels.
 *
 * <p>Minecraft caches GL state in {@code GlStateManager}, so every piece of state touched here is read
 * in {@link #begin} and put back in {@link #end}; otherwise the game would render with wrong state.
 */
final class Gl {
    private static final String VERTEX = "#version 150\n"
            + "uniform vec2 uViewport;\n"
            + "in vec2 aPos; in vec2 aUv; in vec4 aColor; in vec4 aParams;\n"
            + "out vec2 vUv; out vec4 vColor; out vec4 vParams;\n"
            + "void main() {\n"
            + "  vUv = aUv; vColor = aColor; vParams = aParams;\n"
            + "  gl_Position = vec4(aPos.x / uViewport.x * 2.0 - 1.0, 1.0 - aPos.y / uViewport.y * 2.0, 0.0, 1.0);\n"
            + "}\n";
    // vParams = (half width, half height, corner radius, mode); mode 1 samples the glyph atlas and
    // vUv is a texture coordinate, mode 0 is a rounded box and vUv is the offset from its centre.
    private static final String FRAGMENT = "#version 150\n"
            + "uniform sampler2D uAtlas;\n"
            + "in vec2 vUv; in vec4 vColor; in vec4 vParams;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "  if (vParams.w > 0.5) {\n"
            + "    fragColor = vec4(vColor.rgb, vColor.a * texture(uAtlas, vUv).r);\n"
            + "    return;\n"
            + "  }\n"
            + "  vec2 q = abs(vUv) - vParams.xy + vParams.z;\n"
            + "  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - vParams.z;\n"
            + "  fragColor = vec4(vColor.rgb, vColor.a * clamp(0.5 - d, 0.0, 1.0));\n"
            + "}\n";

    private static final int STRIDE = (2 + 2 + 4) * 4 + 4; // pos, uv, params as floats + RGBA bytes
    private static final int MAX_QUADS = 16384;

    private final int program;
    private final int uViewport;
    private final int vao;
    private final int vbo;
    private final int ebo;
    final int atlas;
    private final boolean samplers;
    private final ByteBuffer vertices =
            ByteBuffer.allocateDirect(MAX_QUADS * 4 * STRIDE).order(ByteOrder.nativeOrder());
    private int quads;
    private final ArrayDeque<int[]> clips = new ArrayDeque<>();
    private int width;
    private int height;

    private final IntBuffer ints = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    private final int[] saved = new int[32];
    private final int[] savedViewport = new int[4];
    private final int[] savedScissor = new int[4];
    private final boolean[] savedColorMask = new boolean[4];
    private final ByteBuffer mask = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    /** A compatibility context (Minecraft 1.13-1.16), where fixed-function alpha testing still applies. */
    private final boolean legacy;

    Gl(int atlasSize) {
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
            glBufferData(GL_ARRAY_BUFFER, vertices.capacity(), GL_STREAM_DRAW);
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
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, atlasSize, atlasSize, 0, GL_RED, GL_UNSIGNED_BYTE,
                    ByteBuffer.allocateDirect(atlasSize * atlasSize));

            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "uAtlas"), 0);
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

    /** Starts a frame on the default framebuffer of {@code width} x {@code height} pixels. */
    void begin(int width, int height) {
        this.width = width;
        this.height = height;
        saveState();
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
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
        glUseProgram(program);
        glUniform2f(uViewport, width, height);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlas);
        if (samplers) {
            org.lwjgl.opengl.GL33.glBindSampler(0, 0);
        }
        resetUnpack();
        if (legacy) {
            glDisable(GL_ALPHA_TEST);
        }
        clips.clear();
        quads = 0;
        vertices.clear();
    }

    private static void resetUnpack() {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    }

    void end() {
        try {
            flush();
        } finally {
            restoreState();
        }
    }

    /** Uploads a glyph bitmap into the atlas; the atlas texture is bound while a frame is open. */
    void uploadGlyph(int x, int y, int w, int h, ByteBuffer pixels) {
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h, GL_RED, GL_UNSIGNED_BYTE, pixels);
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
        flush();
        clips.push(new int[] {x0, y0, Math.max(x0, x1), Math.max(y0, y1)});
        applyClip();
    }

    void popClip() {
        flush();
        clips.pop();
        applyClip();
    }

    private void applyClip() {
        int[] clip = clips.peek();
        if (clip == null) {
            glDisable(GL_SCISSOR_TEST);
        } else {
            glEnable(GL_SCISSOR_TEST);
            glScissor(clip[0], height - clip[3], clip[2] - clip[0], clip[3] - clip[1]);
        }
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
            flush();
        }
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

    private void flush() {
        if (quads == 0) {
            return;
        }
        vertices.flip();
        // A fresh store each time, so the driver does not wait for the previous draw.
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        glDrawElements(GL_TRIANGLES, quads * 6, GL_UNSIGNED_INT, 0);
        vertices.clear();
        quads = 0;
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
