#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

layout(std140) uniform DofSettings {
    vec4 Lens0;
    vec4 Lens1;
    vec4 Effect0;
    vec4 Effect1;
    vec4 Flags;
};

const int MAX_SAMPLES = 64;
const float PI = 3.14159265358979323846;
const float GOLDEN_ANGLE = 2.39996322972865332;

float linearizeDepth(float d) {
    float n = Lens1.y;
    float f = Lens1.z;
    if (Flags.w > 0.5) return (n * f) / max(0.00001, f - d * (f - n));
    float z = d * 2.0 - 1.0;
    return (2.0 * n * f) / max(0.00001, f + n - z * (f - n));
}
float sceneDepthAt(vec2 uv) {
    float raw = texture(SceneDepth, clamp(uv, vec2(0.0), vec2(1.0))).r;
    if (raw >= 0.999999) return Lens1.z;
    return linearizeDepth(raw);
}
float blurRadiusPixels(float depth) {
    float focus = max(Lens0.z, 0.5);
    float delta = depth - focus;
    if (delta < 0.0 && Flags.x < 0.5) return 0.0;
    if (delta > 0.0 && Flags.y < 0.5) return 0.0;
    float relativeDefocus = abs(delta) / focus;
    float apertureStrength = 1.8 / max(Lens0.w, 0.7);
    float focalStrength = pow(clamp(Lens1.w / 50.0, 0.36, 2.7), 0.62);
    return clamp(relativeDefocus * apertureStrength * focalStrength * Lens1.x, 0.0, Lens1.x);
}
float polygonScale(float angle, int blades) {
    if (blades < 3) return 1.0;
    float n = float(blades);
    float sector = (2.0 * PI) / n;
    float local = mod(angle + sector * 0.5, sector) - sector * 0.5;
    return cos(PI / n) / max(0.15, cos(local));
}
vec3 boostedSample(vec3 color) {
    float peak = max(color.r, max(color.g, color.b));
    float highlight = max(0.0, peak - 0.55) / 0.45;
    return color * (1.0 + highlight * Effect1.x);
}
void main() {
    vec2 uv = clamp(texCoord, vec2(0.0), vec2(1.0));
    float lensBreathing = sqrt(clamp(Lens1.w / 50.0, 0.36, 2.7));
    float breathing = Effect0.x * lensBreathing * (1.0 / (1.0 + max(Lens0.z, 0.5) * 0.12)) * 0.035;
    float scale = 1.0 + clamp(breathing, 0.0, 0.045);
    vec2 sourceUv = clamp((uv - vec2(0.5)) / scale + vec2(0.5), vec2(0.0), vec2(1.0));
    vec4 center = texture(SceneColor, sourceUv);
    float depth = sceneDepthAt(sourceUv);
    float radiusPx = blurRadiusPixels(depth);
    if (radiusPx < 0.30) { fragColor = center; return; }
    float signedDistance = depth - Lens0.z;
    float sideQuality = signedDistance < 0.0 ? Effect1.z : Effect1.w;
    int wanted = int(clamp(Effect1.y * sideQuality, 8.0, 64.0));
    vec2 texel = 1.0 / max(Lens0.xy, vec2(1.0));
    vec3 sum = center.rgb;
    float weight = 1.0;
    int blades = int(Effect0.y + 0.5);
    for (int i = 0; i < MAX_SAMPLES; ++i) {
        if (i >= wanted) break;
        float fi = float(i) + 0.5;
        float rr = sqrt(fi / float(wanted));
        float angle = fi * GOLDEN_ANGLE;
        vec2 dir = vec2(cos(angle), sin(angle));
        float shape = polygonScale(angle, blades);
        vec2 offset = dir * Effect0.zw * rr * shape * radiusPx * texel;
        vec2 suv = clamp(sourceUv + offset, vec2(0.0), vec2(1.0));
        float sampleDepth = sceneDepthAt(suv);
        float gap = abs(sampleDepth - depth);
        float w = 1.0 / (1.0 + gap * 0.16);
        if (Flags.z > 0.5) {
            bool sampleForeground = sampleDepth + 0.12 < depth;
            bool centerForeground = depth + 0.12 < sampleDepth;
            if (sampleForeground && sampleDepth < Lens0.z) w *= 0.10;
            else if (centerForeground && depth < Lens0.z) w *= 0.35;
        }
        sum += boostedSample(texture(SceneColor, suv).rgb) * w;
        weight += w;
    }
    vec3 blurred = sum / max(weight, 0.0001);
    float mixAmount = smoothstep(0.5, 3.0, radiusPx);
    fragColor = vec4(mix(center.rgb, blurred, mixAmount), center.a);
}
