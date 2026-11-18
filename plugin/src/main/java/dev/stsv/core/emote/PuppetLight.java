package dev.stsv.core.emote;

import org.joml.Vector3d;

/**
 * How a player is lit, done to the puppet (pure: unit-tested). What the 26.3 client does, read from its code:
 *
 * <ul>
 *   <li><b>The light of a place.</b> An entity is lit by the block light and sky light of the one block its light
 *       probe is in (EntityRenderer.getPackedLightCoords: {@code Entity.getLightProbePosition}, the eye for a player and
 *       for a display: a display's eye is its origin), looked up in the light map (text.vsh and entity.vsh both
 *       multiply by {@code sample_lightmap(Sampler2, UV2)}), which darkens sky light with the time of day and the
 *       weather. A display's own brightness, when it has one, replaces both levels (DisplayRenderer.getBlockLightLevel /
 *       getSkyLightLevel). The puppet's displays stand at the player's head top (the carrier) or at a vehicle's seat,
 *       not at the eye: where that block's light is not the eye block's (a low ceiling, a lamp, a step, the seat a
 *       block under the eye), they are given the eye block's levels ({@link #override}).</li>
 *   <li><b>The light of a side.</b> A player's model is drawn with the entity shader, which shades every face by
 *       its direction in the world (entity.vsh {@code minecraft_mix_light}, light.glsl: two fixed lights,
 *       com.mojang.blaze3d.platform.Lighting {@code DIFFUSE_LIGHT_0/1}): a face looking up is at full light, one
 *       looking down at 0.4, the sides between 0.5 and 0.75 by their compass direction. Text has no normals (text.vsh:
 *       colour times light map, nothing else), so the puppet's faces get that factor baked into their colours
 *       ({@link #diffuse}).</li>
 * </ul>
 */
public final class PuppetLight {

    /** light.glsl: MINECRAFT_LIGHT_POWER, MINECRAFT_AMBIENT_LIGHT. */
    static final double POWER = 0.6, AMBIENT = 0.4;
    /** Lighting.DIFFUSE_LIGHT_0 / _1 (the overworld and the end), NETHER_DIFFUSE_LIGHT_1 (the nether: the second from below). */
    static final Vector3d LIGHT_0 = new Vector3d(0.2, 1, -0.7).normalize(), LIGHT_1 = new Vector3d(-0.2, 1, 0.7).normalize(),
            NETHER_LIGHT_1 = new Vector3d(-0.2, -1, 0.7).normalize();
    /** The height of a standing player's eye over their feet (Avatar.STANDING_DIMENSIONS): where they are lit. */
    public static final double EYE = 1.62;

    private PuppetLight() {}

    /**
     * The entity shader's light on a face whose outward normal (a unit vector, world axes: +x east, +y up, +z south)
     * is n: {@code min(1, (max(0, L0.n) + max(0, L1.n)) * 0.6 + 0.4)}.
     */
    public static double diffuse(Vector3d n, boolean nether) {
        Vector3d l1 = nether ? NETHER_LIGHT_1 : LIGHT_1;
        return Math.min(1, (Math.max(0, LIGHT_0.dot(n)) + Math.max(0, l1.dot(n))) * POWER + AMBIENT);
    }

    /** Block and sky light (0..15 each) in one number: block * 16 + sky. */
    public static int pack(int block, int sky) {
        return Math.clamp(block, 0, 15) << 4 | Math.clamp(sky, 0, 15);
    }

    public static int block(int packed) {
        return packed >> 4 & 15;
    }

    public static int sky(int packed) {
        return packed & 15;
    }

    /** No brightness of their own: the displays are lit by the block they stand in. */
    public static final int NATURAL = -1;

    /**
     * The brightness the displays need ({@link #pack}ed), or {@link #NATURAL}: none, when the block the clients light
     * them by (their origin's) has the light of the block the player would be lit by (the eye's). The light they then
     * get is the client's own, frame by frame, with nothing sent; only where the two differ is the eye's sent.
     *
     * @param probe the light of the block the displays' origin is in ({@link #pack}ed)
     * @param eye   the light of the block the eye is in
     */
    public static int override(int probe, int eye) {
        return probe == eye ? NATURAL : eye;
    }
}
