#version 450
layout(push_constant) uniform Frame {
    vec2 viewport;
    float srgb;
} frame;
layout(set = 0, binding = 0) uniform sampler2D atlas;
// The batch's image (the browser's page); points at the atlas until there is one.
layout(set = 0, binding = 1) uniform sampler2D image;
layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec4 vParams;
layout(location = 0) out vec4 fragColor;
void main() {
    vec3 rgb = vColor.rgb;
    float coverage = 1.0;
    if (vParams.w > 2.5) {
        vec4 icon = texture(image, vUv);
        rgb = icon.rgb;
        coverage = icon.a;
    } else if (vParams.w > 1.5) {
        rgb = texture(image, vUv).rgb;
    } else if (vParams.w > 0.5) {
        coverage = texture(atlas, vUv).r;
    } else {
        vec2 q = abs(vUv) - vParams.xy + vParams.z;
        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - vParams.z;
        coverage = clamp(0.5 - d, 0.0, 1.0);
    }
    if (frame.srgb > 0.5) {
        rgb = mix(rgb / 12.92, pow((rgb + 0.055) / 1.055, vec3(2.4)), step(0.04045, rgb));
    }
    fragColor = vec4(rgb, vColor.a * coverage);
}
