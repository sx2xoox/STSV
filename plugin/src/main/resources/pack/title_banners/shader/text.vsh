#version 330
#extension GL_ARB_separate_shader_objects : require

// The game's core/text.vsh (Minecraft 26.3) plus STSV title banners: a glyph whose text colour is a banner marker
// (red 253, green and blue 252..255: frame = (green - 252) + 4 * (blue - 252)) is drawn white and tells the
// fragment shader its animation frame. A second kind of marker (red 252, green 240..255, blue 224..255: frame =
// 255 - green, delay = 255 - blue) is a part of a banner a gust runs along: the fragment shader plays it delay
// steps late, so the gust reaches the parts one after the other (text.fsh). Marked glyphs are also pushed a hair away from the camera in the world, so the
// banner's letters (drawn over it) never fight with it. Everything else is the game's own shader, unchanged.
// Made by the STSV plugin (resources pack/title_banners/shader); config title-banners.animation: shader.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) flat out int bannerFrame;
layout(location = 5) flat out int bannerDelay;

void main() {
    ivec3 c = ivec3(round(Color.rgb * 255.0));
    bannerFrame = (c.r == 253 && c.g >= 252 && c.b >= 252) ? (c.g - 252) + 4 * (c.b - 252) : -1;
    bannerDelay = -1;
    if (c.r == 252 && c.g >= 240 && c.b >= 224) {
        bannerFrame = 255 - c.g;
        bannerDelay = 255 - c.b;
    }
    vec4 color = bannerFrame >= 0 ? vec4(1.0, 1.0, 1.0, Color.a) : Color;

    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
#if !defined(IS_GUI)
    if (bannerFrame >= 0) viewPos.xyz *= 1.0005;
#endif
    gl_Position = ProjMat * viewPos;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = color;
#endif
    texCoord0 = UV0;
}
