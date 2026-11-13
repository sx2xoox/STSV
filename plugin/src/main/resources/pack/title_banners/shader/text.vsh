#version 330
#extension GL_ARB_separate_shader_objects : require

// The game's core/text.vsh (Minecraft 26.3) plus STSV title banners: a glyph whose text colour is a banner marker
// (red 253, green and blue 252..255: frame = (green - 252) + 4 * (blue - 252)) is drawn white and tells the
// fragment shader its animation frame. A second kind of marker (red 252, green 240..255, blue 224..255: frame =
// 255 - green, delay = 255 - blue) is a part of a banner a gust runs along: the fragment shader plays it delay
// steps late, so the gust reaches the parts one after the other (text.fsh).
// Letters that glitch (the neon chat banner's; red 251, green 255 - place along the word in sixteenths, blue 255 -
// word: 0 title, 1 nickname) get their colour here (STSV_GLITCH_FROM .. _TO along the word) and, in the GUI, jump
// sideways in the two frames the banner glitches in. Their shadow (red 250, blue 255 - word) is no shadow but the
// glitch's ghost: hidden, but for those two frames, when it stands beside the letter in cyan or magenta. Marked glyphs are also pushed a hair away from the camera in the world, so the
// banner's letters (drawn over it) never fight with it. Everything else is the game's own shader, unchanged.
// Made by the STSV plugin (resources pack/title_banners/shader); config title-banners.animation: shader.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:globals.glsl>

#define STSV_BANNER_FRAME_MS 70.0
#define STSV_GLITCH_A 6.0
#define STSV_GLITCH_B 13.0
const vec3 STSV_GLITCH_FROM[2] = vec3[2](vec3(200.0, 250.0, 255.0) / 255.0, vec3(255.0, 180.0, 238.0) / 255.0);
const vec3 STSV_GLITCH_TO[2] = vec3[2](vec3(80.0, 214.0, 255.0) / 255.0, vec3(255.0, 70.0, 206.0) / 255.0);

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

    vec3 pos = Position;
    if ((c.r == 251 && c.g >= 240 && c.b >= 254) || (c.r == 250 && c.g >= 254 && c.b >= 254)) {
        int word = 255 - c.b;
        float frame = mod(floor(GameTime * 24000.0 * 50.0 / STSV_BANNER_FRAME_MS), 16.0);
        bool first = abs(frame - STSV_GLITCH_A) < 0.5, second = abs(frame - STSV_GLITCH_B) < 0.5;
        float jump = first ? (word == 0 ? 2.0 : 1.0) : second ? (word == 1 ? -2.0 : -1.0) : 0.0;
        if (c.r == 251) {
            color = vec4(mix(STSV_GLITCH_FROM[word], STSV_GLITCH_TO[word], float(255 - c.g) / 15.0), Color.a);
        } else {
            color = first ? vec4(60.0 / 255.0, 236.0 / 255.0, 1.0, Color.a * 0.85)
                : second ? vec4(1.0, 64.0 / 255.0, 206.0 / 255.0, Color.a * 0.85) : vec4(0.0);
            jump -= first ? 2.0 : second ? -1.0 : 0.0;
        }
#if defined(IS_GUI)
        pos.x += jump;
#endif
    }

    vec4 viewPos = ModelViewMat * vec4(pos, 1.0);
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
