#version 330
#extension GL_ARB_separate_shader_objects : require

// STSV core/item fragment shader for Minecraft 26.3 (written for the STSV plugin; config cosmetics.near-fade).
// Items are coloured as the game colours them (texture x tint x light, hurt overlay, enchantment glint, fog, the
// order-independent transparency phases). A cosmetic (stsvBand >= 0, see item.vsh) is in addition dissolved by an
// ordered dither as the camera comes near: nothing within its band's inner distance, everything beyond the outer
// one, and between them a share of the pixels that grows with the distance (a fixed Bayer 4x4 pattern on the
// screen, so the dissolve is steady, not noisy).

#ifdef GLINT
#include <minecraft:globals.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;
#ifdef GLINT
uniform sampler2D GlintSampler;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 3) in vec4 lightMapColor;
layout(location = 4) in vec4 overlayColor;
#endif
layout(location = 2) in vec4 vertexColor;
layout(location = 5) in vec2 texCoord0;
#ifdef GLINT
layout(location = 6) in vec2 texCoordGlint;
#endif
layout(location = 7) in vec3 stsvFromCamera;
layout(location = 8) flat in int stsvBand;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// Per band (0 other players' cosmetics, 1 your own worn ones, 2 your pet): (inner, outer) in blocks. Written by the
// plugin from config cosmetics.near-fade.
const vec2 STSV_FADE_BANDS[3] = vec2[3](vec2(0.6, 1.2), vec2(2.0, 2.5), vec2(2.5, 3.0));
const float STSV_BAYER[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);

// Whether this pixel of a cosmetic is left out at its distance from the camera.
bool stsvDissolved() {
    if (stsvBand < 0) return false;
    vec2 band = STSV_FADE_BANDS[stsvBand];
    float shown = clamp((length(stsvFromCamera) - band.x) / (band.y - band.x), 0.0, 1.0);
    ivec2 cell = ivec2(gl_FragCoord.xy) & 3;
    return shown < (STSV_BAYER[cell.y * 4 + cell.x] + 0.5) / 16.0;
}

#ifndef OIT_ALPHA_ONLY
// Overlay, light, glint and fog on the surface colour.
vec4 stsvShade(vec4 color) {
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
#ifdef GLINT
    vec4 glint = GlintAlpha * texture(GlintSampler, texCoordGlint);
    color.rgb += glint.rgb * glint.rgb;   // the glint's blend
#endif
#ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fog = vec4(FogColor.rgb * color.a, FogColor.a);
#else
    vec4 fog = FogColor;
#endif
    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, fog);
}
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (stsvDissolved()) {
        discard;
    }
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    color *= vertexColor * ColorModulator;
#ifdef GLINT
    color.a = max(color.a, GlintAlpha);
#endif
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
    fragColor = stsvShade(color);
#endif
}
