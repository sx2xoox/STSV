package dev.stsv.core.content;

import org.joml.Vector3f;

import java.util.List;

/**
 * Furniture settings of a custom item.
 *
 * @param cells     occupied blocks relative to the anchor block, in model space
 *                  (+x = model east, +y = up, +z = model south), before rotation
 * @param solid     place barrier blocks in the cells (player collision)
 * @param rotation  CARDINAL (4 directions) or FREE (any yaw, no barriers recommended)
 * @param behavior  optional handler id registered by a module (e.g. "stsv:smelter")
 * @param translation / scale  fine-tuning of the display entity
 * @param brightness  fixed block light 0..15 for the model, or -1 for world lighting
 * @param seats     places a player can sit (empty = not sittable)
 * @param light     a light block the piece gives off (lamps, lanterns), or null
 */
public record FurnitureSpec(
        List<int[]> cells,
        boolean solid,
        Rotation rotation,
        String behavior,
        Vector3f translation,
        Vector3f scale,
        int brightness,
        List<Seat> seats,
        List<Part> parts,
        Light light
) {
    public enum Rotation { CARDINAL, FREE }

    /** Without a light (machines, tables, chairs...). */
    public FurnitureSpec(List<int[]> cells, boolean solid, Rotation rotation, String behavior, Vector3f translation,
                         Vector3f scale, int brightness, List<Seat> seats, List<Part> parts) {
        this(cells, solid, rotation, behavior, translation, scale, brightness, seats, parts, null);
    }

    /**
     * Real light, not just a bright model: an invisible light block (vanilla minecraft:light) of this level in
     * one of the piece's cells. That cell never gets a barrier (a block holds either), so a lamp's light cell is
     * the one the player may walk into: the lantern head of a tall stone lantern, the whole cell of a small lamp.
     *
     * @param level 1..15
     * @param cell  model-space cell (one of the cells) the light block goes in
     */
    public record Light(int level, int[] cell) {}

    /**
     * An extra model shown as its own display entity, e.g. a fan blade that spins.
     *
     * @param model    item model key of the part (a .bbmodel in the pack: "&lt;ns&gt;:model/&lt;name&gt;")
     * @param position where the part's centre goes, in the furniture's model coordinates (16 = 1 block)
     * @param spinAxis "x", "y" or "z" to spin around, or null for a still part
     * @param rpm      turns per minute (negative = other way)
     */
    public record Part(org.bukkit.NamespacedKey model, Vector3f position, String spinAxis, float rpm) {}

    /**
     * @param cell   model-space cell the seat is in
     * @param height height of the sitting surface above the cell's bottom, in blocks (cushion top of a chair ≈ 0.6)
     * @param facing where the sitter looks, in degrees turned from the piece's front (0 = the way the piece faces,
     *               180 = towards its back, 90 = towards its model east / +x, -90 = towards its west): cushions
     *               round a low table face the table
     */
    public record Seat(int[] cell, double height, float facing) {
        public Seat(int[] cell, double height) {
            this(cell, height, 0f);
        }
    }

    public boolean sittable() {
        return seats != null && !seats.isEmpty();
    }
}
