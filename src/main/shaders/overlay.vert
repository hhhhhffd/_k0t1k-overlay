#version 450
// Vulkan twin of the GL shader in GlRenderer; see Batch for the vertex layout.
layout(push_constant) uniform Frame {
    vec2 viewport;
    float srgb; // 1 when the swapchain stores sRGB, so colours must be written linear
} frame;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aParams;
layout(location = 3) in vec4 aColor;
layout(location = 0) out vec2 vUv;
layout(location = 1) out vec4 vColor;
layout(location = 2) out vec4 vParams;
void main() {
    vUv = aUv;
    vColor = aColor;
    vParams = aParams;
    // Vulkan's clip space has y pointing down, like the batch's pixels.
    gl_Position = vec4(aPos / frame.viewport * 2.0 - 1.0, 0.0, 1.0);
}
