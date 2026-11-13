#version 330
#extension GL_ARB_separate_shader_objects : require

// The game's core/text.fsh (Minecraft 26.3) plus STSV title banners. A banner part comes in 16 glyphs, one per
// frame, drawn on the same spot. Frame 0 is an ordinary glyph; frames 1-15 keep their alpha as 1..24 (under the
// game's cut-off of 0.1), so a client without this shader shows frame 0 only. Here a marked glyph (see text.vsh)
// is kept only while its frame is the current one (70 ms a step, from the game time) and frames 1-15 get their
// alpha back. A looping banner shows frames 0..15 in turn. A surging banner (frames 16..31 from the vertex shader)
// idles on its first frames (STSV_BANNER_CALM_FRAMES, each for STSV_BANNER_CALM_HOLD steps, for
// STSV_BANNER_CALM_STEPS steps), then plays the rest once (each for STSV_BANNER_SURGE_HOLD steps) and idles again.
// Everything else is the game's own shader, unchanged.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:oit.glsl>

#define STSV_BANNER_FRAMES 16.0
#define STSV_BANNER_FRAME_MS 70.0
#define STSV_BANNER_GHOST_LEVELS 24.0
#define STSV_BANNER_CALM_FRAMES 4.0
#define STSV_BANNER_CALM_HOLD 3.0
#define STSV_BANNER_CALM_STEPS 48.0
#define STSV_BANNER_SURGE_HOLD 2.0

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif

layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) flat in int bannerFrame;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif

    #if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)

    #ifdef OIT_ACCUMULATE
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
    #endif

    return color;
}

void main() {
    #ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
    #else
    vec4 texColor = texture(Sampler0, texCoord0);
    #endif

    if (bannerFrame >= 0) {
        // GameTime: the day's ticks / 24000 (a tick is 50 ms)
        float steps = floor(GameTime * 24000.0 * 50.0 / STSV_BANNER_FRAME_MS);
        int frame = bannerFrame;
        float now = mod(steps, STSV_BANNER_FRAMES);
        if (frame >= 16) {
            frame -= 16;
            float at = mod(steps, STSV_BANNER_CALM_STEPS + (STSV_BANNER_FRAMES - STSV_BANNER_CALM_FRAMES) * STSV_BANNER_SURGE_HOLD);
            now = at < STSV_BANNER_CALM_STEPS
                ? mod(floor(at / STSV_BANNER_CALM_HOLD), STSV_BANNER_CALM_FRAMES)
                : STSV_BANNER_CALM_FRAMES + floor((at - STSV_BANNER_CALM_STEPS) / STSV_BANNER_SURGE_HOLD);
        }
        if (frame != int(now)) {
            discard;
        }
        if (frame > 0) {
            texColor.a = min(1.0, texColor.a * (255.0 / STSV_BANNER_GHOST_LEVELS));
        }
    }

    vec4 color = texColor * vertexColor * ColorModulator;

    if (color.a < 0.1) {
        discard;
    }

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
