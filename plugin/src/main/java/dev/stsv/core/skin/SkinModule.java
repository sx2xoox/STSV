package dev.stsv.core.skin;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Skin books: right-click a skin book to open a small window, put a matching tool in the middle slot,
 * and its look changes. Only the item model (and for armor the worn look) changes: enchantments,
 * durability, name and stats stay exactly as they were. The original look is remembered so the
 * skin can be removed again (/stsv skin remove).
 */
public final class SkinModule implements StsvModule, Listener {

    private static final int TOOL_SLOT = 13, INFO_SLOT = 4;

    private STSVPlugin plugin;
    private NamespacedKey keySkin, keyPrevModel, keyPrevAsset, keyLore;

    @Override
    public String id() {
        return "skins";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        keySkin = new NamespacedKey(plugin, "skin");
        keyPrevModel = new NamespacedKey(plugin, "skin_prev_model");
        keyPrevAsset = new NamespacedKey(plugin, "skin_prev_asset");
        keyLore = new NamespacedKey(plugin, "skin_lore");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------------------------------

    /** Whether the skin may be put on this item. Vanilla ids/tags match vanilla items, custom ids custom items. */
    public boolean accepts(CustomItem.Skin skin, ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        Optional<CustomItem> custom = plugin.content().itemOf(item);
        if (custom.isPresent() && (custom.get().skin() != null || custom.get().isFurniture())) return false;
        for (String rule : skin.appliesTo()) {
            if (rule.startsWith("#")) {
                NamespacedKey tagKey = NamespacedKey.fromString(rule.substring(1));
                Tag<Material> tag = tagKey == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, tagKey, Material.class);
                if (tag != null && custom.isEmpty() && tag.isTagged(item.getType())) return true;
            } else if (rule.startsWith("minecraft:") || !rule.contains(":")) {
                Material m = Material.matchMaterial(rule);
                if (m == item.getType() && custom.isEmpty()) return true;
            } else if (custom.isPresent() && custom.get().key().asString().equals(rule)) {
                return true;
            }
        }
        return false;
    }

    public Optional<String> skinOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return Optional.empty();
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(keySkin, PersistentDataType.STRING));
    }

    /** Applies the skin in place. Returns the id of a skin that was replaced, if any. */
    public Optional<String> apply(ItemStack item, CustomItem book) {
        Optional<String> replaced = skinOf(item);
        if (replaced.isPresent()) remove(item);
        CustomItem.Skin skin = book.skin();
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keySkin, PersistentDataType.STRING, book.key().asString());
        pdc.set(keyPrevModel, PersistentDataType.STRING, meta.hasItemModel() ? meta.getItemModel().asString() : "");
        meta.setItemModel(skin.model());
        if (skin.asset() != null && meta.hasEquippable()) {
            var eq = meta.getEquippable();
            pdc.set(keyPrevAsset, PersistentDataType.STRING, eq.getModel() == null ? "" : eq.getModel().asString());
            eq.setModel(skin.asset());
            meta.setEquippable(eq);
        }
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Text.item("<dark_purple>✦ 스킨: </dark_purple>" + (book.name() == null ? book.key().getKey() : book.name())));
        meta.lore(lore);
        pdc.set(keyLore, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return replaced;
    }

    /** Restores the original look. Returns the removed skin's id. */
    public Optional<String> remove(ItemStack item) {
        Optional<String> skin = skinOf(item);
        if (skin.isEmpty()) return skin;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String prev = pdc.getOrDefault(keyPrevModel, PersistentDataType.STRING, "");
        meta.setItemModel(prev.isEmpty() ? null : NamespacedKey.fromString(prev));
        if (pdc.has(keyPrevAsset) && meta.hasEquippable()) {
            String prevAsset = pdc.get(keyPrevAsset, PersistentDataType.STRING);
            var eq = meta.getEquippable();
            eq.setModel(prevAsset == null || prevAsset.isEmpty() ? null : NamespacedKey.fromString(prevAsset));
            meta.setEquippable(eq);
        }
        if (pdc.has(keyLore) && meta.hasLore()) {
            List<Component> lore = new ArrayList<>(meta.lore());
            if (!lore.isEmpty()) lore.removeLast();
            meta.lore(lore.isEmpty() ? null : lore);
        }
        for (NamespacedKey k : List.of(keySkin, keyPrevModel, keyPrevAsset, keyLore)) pdc.remove(k);
        item.setItemMeta(meta);
        return skin;
    }

    // ------------------------------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------------------------------

    private static final class Window implements InventoryHolder {
        final CustomItem book;
        Inventory inventory;

        Window(CustomItem book) {
            this.book = book;
        }

        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    public void open(Player player, CustomItem book) {
        Window w = new Window(book);
        Inventory inv = Bukkit.createInventory(w, 27, Text.mm("<dark_purple>스킨 적용: </dark_purple>"
                + (book.name() == null ? book.key().getKey() : book.name())));
        w.inventory = inv;
        ItemStack pane = new ItemStack(Material.PURPLE_STAINED_GLASS_PANE);
        pane.editMeta(m -> m.itemName(Text.item(" ")));
        for (int i = 0; i < 27; i++) if (i != TOOL_SLOT) inv.setItem(i, pane);
        ItemStack info = book.create(1);
        info.editMeta(m -> {
            List<Component> lore = new ArrayList<>(m.hasLore() ? m.lore() : List.of());
            lore.add(Text.item(""));
            lore.add(Text.item("<gray>가운데 칸에 <white>적용할 도구</white>를 넣으면 바로 바뀝니다."));
            lore.add(Text.item("<gray>인챈트, 내구도, 이름은 그대로 유지됩니다."));
            lore.add(Text.item("<dark_gray>대상: " + String.join(", ", book.skin().appliesTo())));
            m.lore(lore);
        });
        inv.setItem(INFO_SLOT, info);
        player.openInventory(inv);
    }

    @EventHandler
    public void onUseBook(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Optional<CustomItem> book = plugin.content().itemOf(e.getItem()).filter(i -> i.skin() != null);
        if (book.isEmpty()) return;
        e.setCancelled(true);
        open(e.getPlayer(), book.get());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Window w)) return;
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        boolean top = e.getClickedInventory() == e.getInventory();
        if (top && e.getSlot() != TOOL_SLOT) {
            e.setCancelled(true);
            return;
        }
        if (!top && e.isShiftClick()) {
            // shift-click from the inventory: move it into the tool slot ourselves
            e.setCancelled(true);
            ItemStack moving = e.getCurrentItem();
            ItemStack inSlot = e.getInventory().getItem(TOOL_SLOT);
            if (moving != null && !moving.isEmpty() && (inSlot == null || inSlot.isEmpty())) {
                ItemStack one = moving.clone();
                one.setAmount(1);
                moving.setAmount(moving.getAmount() - 1);
                e.getInventory().setItem(TOOL_SLOT, one);
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> tryApply((Player) e.getWhoClicked(), w));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Window w)) return;
        for (int raw : e.getRawSlots()) {
            if (raw < 27 && raw != TOOL_SLOT) {
                e.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> tryApply((Player) e.getWhoClicked(), w));
    }

    private void tryApply(Player player, Window w) {
        ItemStack tool = w.inventory.getItem(TOOL_SLOT);
        if (tool == null || tool.isEmpty()) return;
        if (skinOf(tool).filter(id -> id.equals(w.book.key().asString())).isPresent()) return; // already this skin
        if (tool.getAmount() > 1 || !accepts(w.book.skin(), tool)) {
            dev.stsv.core.hud.HudModule.flash(player, dev.stsv.core.util.Lang.mm("skin.not-applicable"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
            return;
        }
        if (!consumeBook(player, w.book)) {
            dev.stsv.core.hud.HudModule.flash(player, dev.stsv.core.util.Lang.mm("skin.no-book"));
            return;
        }
        Optional<String> replaced = apply(tool, w.book);
        w.inventory.setItem(TOOL_SLOT, tool);
        replaced.flatMap(id -> plugin.content().item(id)).filter(b -> b.skin().returnOnRemove())
                .ifPresent(b -> give(player, b.create(1)));
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.3f);
        player.spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1.2, 0), 40, 0.4, 0.4, 0.4, 0.5);
        dev.stsv.core.hud.HudModule.flash(player, dev.stsv.core.util.Lang.mm("skin.applied"));
    }

    private boolean consumeBook(Player player, CustomItem book) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return true;
        for (ItemStack it : player.getInventory().getContents()) {
            if (plugin.content().itemOf(it).filter(i -> i.key().equals(book.key())).isPresent()) {
                it.subtract(1);
                return true;
            }
        }
        return false;
    }

    private static void give(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Window)) return;
        ItemStack tool = e.getInventory().getItem(TOOL_SLOT);
        e.getInventory().clear();
        if (tool != null && !tool.isEmpty() && e.getPlayer() instanceof Player p) give(p, tool);
    }

    /** /stsv skin remove: restores the held item's look and returns the book if configured. */
    public void removeHeld(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<String> removed = remove(hand);
        if (removed.isEmpty()) {
            player.sendMessage(dev.stsv.core.util.Lang.msg("skin.none-held"));
            return;
        }
        plugin.content().item(removed.get()).filter(b -> b.skin().returnOnRemove())
                .ifPresent(b -> give(player, b.create(1)));
        player.sendMessage(dev.stsv.core.util.Lang.msg("skin.removed"));
    }
}
