package dev.stsv.core.content;

/**
 * Where an item's look comes from.
 *  - BBMODEL: models/&lt;name&gt;.bbmodel in the content pack
 *  - TEXTURE: textures/&lt;name&gt;.png in the content pack (flat 2D item)
 *  - RAW:     an existing item model definition id, e.g. "minecraft:diamond" or "stsv:custom/thing"
 *
 * icon: for a BBMODEL, textures/&lt;icon&gt;.png in the content pack shown as a flat 2D item in inventories and
 * menus (the "gui" display context) while hands, the ground and placed furniture show the 3D model; null = the
 * model everywhere.
 */
public record ModelRef(Kind kind, String value, boolean handheld, String icon) {

    public enum Kind { BBMODEL, TEXTURE, RAW }

    public ModelRef(Kind kind, String value, boolean handheld) {
        this(kind, value, handheld, null);
    }

    /** Path of the generated block/item model inside the namespace (models/item/...). */
    public String modelPath() {
        return "item/" + value;
    }
}
