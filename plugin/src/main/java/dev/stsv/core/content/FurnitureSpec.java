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
 */
public record FurnitureSpec(
        List<int[]> cells,
        boolean solid,
        Rotation rotation,
        String behavior,
        Vector3f translation,
        Vector3f scale,
        int brightness
) {
    public enum Rotation { CARDINAL, FREE }
}
