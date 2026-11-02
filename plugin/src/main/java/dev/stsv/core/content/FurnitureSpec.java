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
        List<Part> parts
) {
    public enum Rotation { CARDINAL, FREE }

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
     */
    public record Seat(int[] cell, double height) {}

    public boolean sittable() {
        return seats != null && !seats.isEmpty();
    }
}
