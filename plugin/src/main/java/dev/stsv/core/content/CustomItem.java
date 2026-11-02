package dev.stsv.core.content;

import dev.stsv.core.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * A custom item defined in a content pack's items/*.yml.
 */
public record CustomItem(
        NamespacedKey key,
        ContentPack pack,
        Material material,
        String name,
        List<String> lore,
        ModelRef model,
        Integer maxStack,
        Boolean glint,
        boolean unbreakable,
        FurnitureSpec furniture
) {

    /** Persistent tag that marks an ItemStack as this custom item. */
    public static NamespacedKey idKey() {
        return new NamespacedKey("stsv", "id");
    }

    /** Key of the item model definition (assets/&lt;ns&gt;/items/&lt;id&gt;.json) the client renders. */
    public NamespacedKey itemModelKey() {
        if (model == null) return null;
        if (model.kind() == ModelRef.Kind.RAW) return NamespacedKey.fromString(model.value());
        return key;
    }

    public boolean isFurniture() {
        return furniture != null;
    }

    public ItemStack create(int amount) {
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (name != null) meta.itemName(Text.item(name));
        if (lore != null && !lore.isEmpty()) meta.lore(Text.itemLines(lore));
        NamespacedKey modelKey = itemModelKey();
        if (modelKey != null) meta.setItemModel(modelKey);
        if (maxStack != null) meta.setMaxStackSize(maxStack);
        if (glint != null) meta.setEnchantmentGlintOverride(glint);
        if (unbreakable) meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(idKey(), PersistentDataType.STRING, key.asString());
        stack.setItemMeta(meta);
        return stack;
    }
}
