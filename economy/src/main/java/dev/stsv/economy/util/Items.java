package dev.stsv.economy.util;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Optional;
import java.util.function.Predicate;

/** Item helpers: ids ("minecraft:iron_ingot" or "economy:ruby"), storage bytes, counting in inventories. */
public final class Items {

    /**
     * Marks goods bought from the admin shop: they cannot be sold back to it, handed in for requests or put into a
     * machine. Crafting with a marked item marks the result, and a young crop planted from one gives its seed back marked.
     */
    public static final org.bukkit.NamespacedKey SHOP_BOUGHT = new org.bukkit.NamespacedKey("economy", "shop_bought");

    private Items() {}

    /** Puts the admin-shop mark on a stack (bought goods, and what is crafted from them). Returns the same stack. */
    public static ItemStack markBought(ItemStack item) {
        if (item != null && !item.isEmpty())
            item.editMeta(m -> m.getPersistentDataContainer().set(SHOP_BOUGHT, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1));
        return item;
    }

    public static boolean shopBought(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(SHOP_BOUGHT, org.bukkit.persistence.PersistentDataType.BYTE);
    }

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
        if (!key(item).equals(key) || shopBought(item)) return false;
        if (!key.startsWith("minecraft:")) return true;
        if (!item.hasItemMeta()) return true;
        var meta = item.getItemMeta();
        if (meta.hasCustomName() || meta.hasEnchants() || meta.hasLore()) return false;
        return !(meta instanceof org.bukkit.inventory.meta.Damageable d) || !d.hasDamage();
    }

    /** A rare or legend fish with its size (and price factor) from the catch. */
    public static boolean sized(ItemStack item) {
        return item != null && !item.isEmpty() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(dev.stsv.economy.fishing.FishingModule.SIZE);
    }

    /**
     * The same fish without its size, priced as the smallest of its kind (FishSpec.STRIPPED_FACTOR) with one lore line: every stripped
     * fish of a species is like every other, so they stack in an input slot, and taking one back out never raises its price.
     * Anything else comes back as it is.
     */
    public static ItemStack stripSize(ItemStack item) {
        if (!sized(item)) return item;
        return create(key(item), item.getAmount()).map(plain -> {
            plain.editMeta(m -> {
                java.util.List<Component> lore = m.hasLore() ? new java.util.ArrayList<>(m.lore()) : new java.util.ArrayList<>();
                lore.add(dev.stsv.core.util.Lang.item("fishing.size-stripped-lore", "pct",
                        Math.round(dev.stsv.economy.fishing.FishSpec.STRIPPED_FACTOR * 100)));
                m.lore(lore);
                m.getPersistentDataContainer().set(dev.stsv.economy.fishing.FishingModule.FACTOR,
                        org.bukkit.persistence.PersistentDataType.DOUBLE, dev.stsv.economy.fishing.FishSpec.STRIPPED_FACTOR);
            });
            return plain;
        }).orElse(item);
    }

    /** A rare or legend fish's price factor by its size (0.7 - 1.5, set when caught; 0.7 once stripped in a machine); 1 for anything else. */
    public static double sizeFactor(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 1;
        Double f = item.getItemMeta().getPersistentDataContainer().get(dev.stsv.economy.fishing.FishingModule.FACTOR,
                org.bukkit.persistence.PersistentDataType.DOUBLE);
        return f == null ? 1 : f;
    }

    /** The average size factor of the first amount matching items (in the order {@link #remove} takes them). */
    public static double sizeFactor(PlayerInventory inv, Predicate<ItemStack> match, int amount) {
        return sizeFactor(inv, match, amount, -1);
    }

    /** The same, taking from storage slot first (a clicked slot; -1 = none) before the others. */
    public static double sizeFactor(PlayerInventory inv, Predicate<ItemStack> match, int amount, int first) {
        double sum = 0;
        int left = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (int i : order(contents.length, first)) {
            ItemStack it = contents[i];
            if (left <= 0) break;
            if (it == null || !match.test(it)) continue;
            int take = Math.min(left, it.getAmount());
            sum += sizeFactor(it) * take;
            left -= take;
        }
        int n = amount - left;
        return n <= 0 ? 1 : sum / n;
    }

    /** Slot indices 0..size-1, with first (if it is one of them) moved to the front. */
    public static int[] order(int size, int first) {
        int[] out = new int[size];
        int k = 0;
        if (first >= 0 && first < size) out[k++] = first;
        for (int i = 0; i < size; i++) if (i != first) out[k++] = i;
        return out;
    }

    public static int count(PlayerInventory inv, Predicate<ItemStack> match) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) if (it != null && match.test(it)) n += it.getAmount();
        return n;
    }

    /** Removes up to amount matching items; returns how many were removed. */
    public static int remove(PlayerInventory inv, Predicate<ItemStack> match, int amount) {
        return remove(inv, match, amount, -1);
    }

    /** The same, taking from storage slot first (a clicked slot; -1 = none) before the others. */
    public static int remove(PlayerInventory inv, Predicate<ItemStack> match, int amount, int first) {
        int left = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (int i : order(contents.length, first)) {
            if (left <= 0) break;
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

    /** Plain text (vanilla names in the server's language, English): for searching and storing, not for players. */
    public static String plainName(ItemStack item) {
        return PlainTextComponentSerializer.plainText().serialize(item.effectiveName());
    }

    /**
     * The name for a {item} placeholder in a message, as MiniMessage. Vanilla names stay translation keys
     * ({@code <lang:item.minecraft.lapis_lazuli>}), so every player reads them in their own game language.
     */
    public static String mmName(ItemStack item) {
        return mm(item.effectiveName());
    }

    /** "economy:ruby" / "minecraft:diamond" as a {item} placeholder; the id itself if there is no such item. */
    public static String mmName(String id) {
        return create(id, 1).map(Items::mmName).orElse(id);
    }

    /**
     * A name as MiniMessage with no colour or formatting (the message colours it, and an open colour tag
     * would run on into the rest of the line); text is escaped, so a renamed item cannot add tags.
     */
    public static String mm(Component name) {
        return MiniMessage.miniMessage().serialize(unstyled(name));
    }

    private static Component unstyled(Component c) {
        return c.style(Style.empty()).children(c.children().stream().map(Items::unstyled).toList());
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
