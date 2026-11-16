#version 330
#extension GL_ARB_separate_shader_objects : require

// STSV core/item vertex shader for Minecraft 26.3 (written for the STSV plugin; config cosmetics.near-fade).
// Every item model the game draws (in the world, in hands, in menus) goes through the core/item program, with the
// pipeline's defines (ALPHA_CUTOUT, GLINT, GLINT_SPECIAL, OIT_ALPHA_ONLY, OIT_ACCUMULATE). An item is drawn as the
// game draws it, except a cosmetic display: its item definition tints it with the near-fade marker (red 253,
// green 254, blue 248 + band, see NearFade.java). Such a model keeps its own colours (the marker is read here and
// replaced by white) and tells the fragment shader its band and where each pixel is relative to the camera.

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef GLINT_SPECIAL
layout(location = 5) in vec2 UV3;
#endif
layout(location = 6) in vec3 Normal;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler1;   // overlay (hurt flash)
uniform sampler2D Sampler2;   // lightmap

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec4 overlayColor;
#endif
layout(location = 2) out vec4 vertexColor;
layout(location = 5) out vec2 texCoord0;
#ifdef GLINT
layout(location = 6) out vec2 texCoordGlint;
#endif
layout(location = 7) out vec3 stsvFromCamera;   // view space: the camera at the origin
layout(location = 8) flat out int stsvBand;     // -1: not a cosmetic

// The near-fade band of a marker tint (0 other players' cosmetics, 1 your own, 2 your pet), or -1.
int stsvBandOf(vec4 tint) {
    ivec3 c = ivec3(round(tint.rgb * 255.0));
    return (c.r == 253 && c.g == 254 && c.b >= 248 && c.b <= 250) ? c.b - 248 : -1;
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    stsvFromCamera = (ModelViewMat * vec4(Position, 1.0)).xyz;
    stsvBand = stsvBandOf(Color);
    vec4 tint = stsvBand < 0 ? Color : vec4(1.0, 1.0, 1.0, Color.a);

#ifndef OIT_ALPHA_ONLY
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
#endif
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, tint);
    texCoord0 = UV0;
#ifdef GLINT
#ifdef GLINT_SPECIAL
    texCoordGlint = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
#else
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
#endif
}
