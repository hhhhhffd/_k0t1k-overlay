#version 450
layout(push_constant) uniform Frame {
    vec2 viewport;
    float srgb;
} frame;
layout(set = 0, binding = 0) uniform sampler2D atlas;
layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec4 vParams;
layout(location = 0) out vec4 fragColor;
void main() {
    float coverage;
    if (vParams.w > 0.5) {
        coverage = texture(atlas, vUv).r;
    } else {
        vec2 q = abs(vUv) - vParams.xy + vParams.z;
        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - vParams.z;
        coverage = clamp(0.5 - d, 0.0, 1.0);
    }
    vec3 rgb = vColor.rgb;
    if (frame.srgb > 0.5) {
        rgb = mix(rgb / 12.92, pow((rgb + 0.055) / 1.055, vec3(2.4)), step(0.04045, rgb));
    }
    fragColor = vec4(rgb, vColor.a * coverage);
}
