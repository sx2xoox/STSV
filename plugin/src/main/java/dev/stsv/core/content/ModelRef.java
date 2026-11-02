package dev.stsv.core.content;

/**
 * Where an item's look comes from.
 *  - BBMODEL: models/&lt;name&gt;.bbmodel in the content pack
 *  - TEXTURE: textures/&lt;name&gt;.png in the content pack (flat 2D item)
 *  - RAW:     an existing item model definition id, e.g. "minecraft:diamond" or "stsv:custom/thing"
 */
public record ModelRef(Kind kind, String value, boolean handheld) {

    public enum Kind { BBMODEL, TEXTURE, RAW }

    /** Path of the generated block/item model inside the namespace (models/item/...). */
    public String modelPath() {
        return "item/" + value;
    }
}
