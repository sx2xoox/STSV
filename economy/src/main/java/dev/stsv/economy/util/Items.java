package dev.stsv.economy.util;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Optional;
import java.util.function.Predicate;

/** Item helpers: ids ("minecraft:iron_ingot" or "economy:ruby"), storage bytes, counting in inventories. */
public final class Items {

    private Items() {}

    /** Custom id if it is one of ours, else the vanilla material key. */
    public static String key(ItemStack item) {
        if (item == null || item.isEmpty()) return "minecraft:air";
        Optional<CustomItem> c = STSVPlugin.get().content().itemOf(item);
        return c.map(i -> i.key().asString()).orElse(item.getType().getKey().asString());
    }

    /** "iron_ingot", "minecraft:iron_ingot" and "economy:ruby" all work. */
    public static String normalize(String id) {
        String s = id.trim().toLowerCase(java.util.Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    /** A new stack of an id (custom or vanilla); empty if unknown. */
    public static Optional<ItemStack> create(String id, int amount) {
        String key = normalize(id);
        if (!key.startsWith("minecraft:")) return STSVPlugin.get().content().item(key).map(i -> i.create(amount));
        Material m = Material.matchMaterial(key);
        return m == null || !m.isItem() ? Optional.empty() : Optional.of(new ItemStack(m, Math.max(1, amount)));
    }

    /**
     * Does this stack count as plain "id" for selling and delivering? Custom items by id; vanilla only
     * when it is not a custom item and carries nothing special (no rename, no enchantments, full durability).
     */
    public static boolean plain(ItemStack item, String id) {
        if (item == null || item.isEmpty()) return false;
        String key = normalize(id);
        if (!key(item).equals(key)) return false;
        if (!key.startsWith("minecraft:")) return true;
        if (!item.hasItemMeta()) return true;
        var meta = item.getItemMeta();
        if (meta.hasCustomName() || meta.hasEnchants() || meta.hasLore()) return false;
        return !(meta instanceof org.bukkit.inventory.meta.Damageable d) || !d.hasDamage();
    }

    public static int count(PlayerInventory inv, Predicate<ItemStack> match) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) if (it != null && match.test(it)) n += it.getAmount();
        return n;
    }

    /** Removes up to amount matching items; returns how many were removed. */
    public static int remove(PlayerInventory inv, Predicate<ItemStack> match, int amount) {
        int left = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || !match.test(it)) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            left -= take;
            contents[i] = it.getAmount() <= 0 ? null : it;
        }
        inv.setStorageContents(contents);
        return amount - left;
    }

    public static byte[] bytes(ItemStack item) {
        return item.serializeAsBytes();
    }

    public static ItemStack fromBytes(byte[] data) {
        return ItemStack.deserializeBytes(data);
    }

    public static Component name(ItemStack item) {
        return item.effectiveName();
    }

    public static String plainName(ItemStack item) {
        return PlainTextComponentSerializer.plainText().serialize(item.effectiveName());
    }

    /** Puts items in the inventory; returns what did not fit. */
    public static java.util.List<ItemStack> give(Player p, ItemStack item) {
        return new java.util.ArrayList<>(p.getInventory().addItem(item.clone()).values());
    }

    /** Free room for this item in the storage slots (whole stacks and partial stacks). */
    public static int room(PlayerInventory inv, ItemStack item) {
        int room = 0;
        int max = item.getMaxStackSize();
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.isEmpty()) room += max;
            else if (it.isSimilar(item)) room += Math.max(0, max - it.getAmount());
        }
        return room;
    }
}
