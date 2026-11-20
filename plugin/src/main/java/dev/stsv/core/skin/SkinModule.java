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
 *
 * <p>A fishing rod's skin may bring a float of its own ({@link CustomItem.Bobber}): while such a rod is cast, a display
 * of that model rides the fishing hook (the clients carry it along: it bobs with the hook) and goes when the hook goes.
 */
public final class SkinModule implements StsvModule, Listener {

    private static final int TOOL_SLOT = 13, INFO_SLOT = 4;

    private STSVPlugin plugin;
    private NamespacedKey keySkin, keyPrevModel, keyPrevAsset, keyLore, keyFloat;
    /** the floats on the hooks that are out now, by the hook's id */
    private final java.util.Map<java.util.UUID, org.bukkit.entity.ItemDisplay> floats = new java.util.HashMap<>();
    /** floats a hook would not take as a rider: moved to it every tick instead */
    private final java.util.Map<java.util.UUID, org.bukkit.entity.FishHook> followed = new java.util.HashMap<>();
    private org.bukkit.scheduler.BukkitTask floatTicker, fxTicker;
    /** player -> the tick of their last burst sound (fast mining does not ring every block) */
    private final java.util.Map<java.util.UUID, Long> lastBurst = new java.util.HashMap<>();
    private long fxTicks;

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
        keyFloat = new NamespacedKey(plugin, "skin_float");
        // floats left by a crash or a reload (they are not saved, but be safe)
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity e : w.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class)) if (e.getPersistentDataContainer().has(keyFloat)) e.remove();
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        floatTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFloats, 1L, 1L);
        fxTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHeld, 20L, 6L);
    }

    @Override
    public void disable() {
        if (floatTicker != null) floatTicker.cancel();
        floatTicker = null;
        if (fxTicker != null) fxTicker.cancel();
        fxTicker = null;
        floats.values().forEach(org.bukkit.entity.Entity::remove);
        floats.clear();
        followed.clear();
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------------------------------
    // A skinned rod's float
    // ------------------------------------------------------------------------------------------

    /** The float a rod's skin brings, if the stack carries a skin that has one. */
    public Optional<CustomItem.Bobber> bobberOf(ItemStack rod) {
        return skinOf(rod).flatMap(id -> plugin.content().item(id)).map(CustomItem::skin).map(CustomItem.Skin::bobber);
    }

    /** A rod was cast: its skin's float goes onto the hook. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onCast(org.bukkit.event.player.PlayerFishEvent e) {
        if (e.getState() != org.bukkit.event.player.PlayerFishEvent.State.FISHING) return;
        Player p = e.getPlayer();
        ItemStack rod = p.getInventory().getItem(e.getHand() == null ? EquipmentSlot.HAND : e.getHand());
        Optional<CustomItem.Bobber> bobber = bobberOf(rod);
        if (bobber.isEmpty()) return;
        org.bukkit.entity.FishHook hook = e.getHook();
        CustomItem.Bobber b = bobber.get();
        ItemStack shown = new ItemStack(Material.PAPER);
        shown.editMeta(m -> m.setItemModel(b.model()));
        org.bukkit.entity.ItemDisplay d = hook.getWorld().spawn(hook.getLocation(), org.bukkit.entity.ItemDisplay.class, it -> {
            it.setPersistent(false);
            it.getPersistentDataContainer().set(keyFloat, PersistentDataType.BYTE, (byte) 1);
            it.setItemStack(shown);
            it.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);
            it.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, b.lift(), 0), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(b.scale(), b.scale(), b.scale()), new org.joml.Quaternionf()));
            it.setTeleportDuration(1);
            it.setViewRange(1.5f);
        });
        floats.put(hook.getUniqueId(), d);
        // riding the hook, the clients carry it along; a hook that takes no rider is followed tick by tick instead
        if (!hook.addPassenger(d)) followed.put(hook.getUniqueId(), hook);
    }

    /** The hook went (reeled in, lost, its fisher left): its float goes with it. */
    @EventHandler
    public void onHookGone(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent e) {
        org.bukkit.entity.ItemDisplay d = floats.remove(e.getEntity().getUniqueId());
        followed.remove(e.getEntity().getUniqueId());
        if (d != null) d.remove();
    }

    /** Every tick: floats that do not ride are moved to their hooks; floats whose hook is gone go (should the event have been missed). */
    private void tickFloats() {
        if (floats.isEmpty()) return;
        followed.forEach((id, hook) -> {
            org.bukkit.entity.ItemDisplay d = floats.get(id);
            if (d != null && hook.isValid()) d.teleport(hook.getLocation().add(0, 0.25, 0));
        });
        floats.entrySet().removeIf(en -> {
            org.bukkit.entity.Entity hook = Bukkit.getEntity(en.getKey());
            if (hook != null && hook.isValid() && en.getValue().isValid()) return false;
            en.getValue().remove();
            followed.remove(en.getKey());
            return true;
        });
    }

    // ------------------------------------------------------------------------------------------
    // A donation skin's effects (CustomItem.SkinFx)
    // ------------------------------------------------------------------------------------------

    /** The effects of the skin on this item, or null. */
    public CustomItem.SkinFx fxOf(ItemStack item) {
        return skinOf(item).flatMap(id -> plugin.content().item(id)).map(CustomItem::skin).map(CustomItem.Skin::fx).orElse(null);
    }

    /** Every 6 ticks: a held donation tool sheds a particle or two at the hand. */
    private void tickHeld() {
        fxTicks += 6;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.isInvisible()) continue;
            CustomItem.SkinFx fx = fxOf(p.getInventory().getItemInMainHand());
            if (fx == null || fx.held() == null) continue;
            org.bukkit.Location eye = p.getEyeLocation();
            org.bukkit.util.Vector dir = eye.getDirection().setY(0);
            if (dir.lengthSquared() < 1e-4) dir = new org.bukkit.util.Vector(0, 0, 1);
            dir.normalize();
            org.bukkit.util.Vector right = new org.bukkit.util.Vector(-dir.getZ(), 0, dir.getX());
            org.bukkit.Location hand = eye.clone().add(right.multiply(0.38)).add(dir.multiply(0.35)).add(0, -0.55, 0);
            spawn(fx.held(), fx, hand, 1, 0.12, (int) (fxTicks / 6));
        }
    }

    /** A burst of the skin's use particle (and its sound) at a spot. */
    private void burst(Player p, CustomItem.SkinFx fx, org.bukkit.Location at) {
        org.bukkit.Particle particle = fx.use() != null ? fx.use() : fx.held();
        for (int i = 0; i < 3; i++) spawn(particle, fx, at, 3, 0.3, i);
        if (fx.sound() == null) return;
        long now = Bukkit.getCurrentTick();
        Long last = lastBurst.get(p.getUniqueId());
        if (last != null && now - last < 4) return;
        lastBurst.put(p.getUniqueId(), now);
        at.getWorld().playSound(at, fx.sound(), org.bukkit.SoundCategory.PLAYERS, 0.35f, fx.pitch());
    }

    private void spawn(org.bukkit.Particle particle, CustomItem.SkinFx fx, org.bukkit.Location at, int count, double spread, int k) {
        spawn(null, particle, fx, at, count, spread, k);
    }

    /** {@code viewer}: only that player sees it (a preview); null: everyone around. */
    private void spawn(Player viewer, org.bukkit.Particle particle, CustomItem.SkinFx fx, org.bukkit.Location at, int count, double spread, int k) {
        Class<?> data = particle.getDataType();
        org.bukkit.Color c = fx.colors().isEmpty() ? org.bukkit.Color.WHITE : fx.colors().get(Math.floorMod(k, fx.colors().size()));
        Object extra = data == org.bukkit.Particle.DustOptions.class ? new org.bukkit.Particle.DustOptions(c, 0.8f)
                : data == org.bukkit.Color.class ? c : null;
        if (extra == null && data != Void.class) return;
        double speed = data == Void.class ? 0.01 : 0;
        if (viewer != null) viewer.spawnParticle(particle, at, count, spread, spread, spread, speed, extra);
        else at.getWorld().spawnParticle(particle, at, count, spread, spread, spread, speed, extra);
    }

    /** A preview's held-tool sparkle, for one player only (the wardrobe mannequin holding a donation skin). */
    public void previewHeld(Player viewer, ItemStack tool, org.bukkit.Location at, int k) {
        CustomItem.SkinFx fx = fxOf(tool);
        if (fx == null || fx.held() == null) return;
        spawn(viewer, fx.held(), fx, at, 2, 0.15, k);
        if (k % 8 == 0 && fx.use() != null) spawn(viewer, fx.use(), fx, at, 3, 0.3, k);
    }

    /** A tool wearing a skin book's skin, to show (never handed out): a diamond tool of the kind the book fits. */
    public Optional<ItemStack> previewTool(CustomItem book) {
        if (book == null || book.skin() == null) return Optional.empty();
        for (String rule : book.skin().appliesTo()) {
            Material m = null;
            if (rule.startsWith("#")) {
                NamespacedKey tagKey = NamespacedKey.fromString(rule.substring(1));
                Tag<Material> tag = tagKey == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, tagKey, Material.class);
                if (tag != null) for (Material t : tag.getValues()) if (m == null || t.name().startsWith("DIAMOND_")) m = t;
            } else if (rule.startsWith("minecraft:") || !rule.contains(":")) m = Material.matchMaterial(rule);
            if (m == null || !m.isItem()) continue;
            ItemStack it = new ItemStack(m);
            apply(it, book);
            return Optional.of(it);
        }
        return Optional.empty();
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onFxBreak(org.bukkit.event.block.BlockBreakEvent e) {
        CustomItem.SkinFx fx = fxOf(e.getPlayer().getInventory().getItemInMainHand());
        if (fx != null) burst(e.getPlayer(), fx, e.getBlock().getLocation().add(0.5, 0.5, 0.5));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onFxHit(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        CustomItem.SkinFx fx = fxOf(p.getInventory().getItemInMainHand());
        if (fx != null) burst(p, fx, e.getEntity().getLocation().add(0, e.getEntity().getHeight() / 2, 0));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onFxCatch(org.bukkit.event.player.PlayerFishEvent e) {
        if (e.getState() != org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH) return;
        ItemStack rod = e.getPlayer().getInventory().getItemInMainHand();
        if (rod.getType() != Material.FISHING_ROD) rod = e.getPlayer().getInventory().getItemInOffHand();
        CustomItem.SkinFx fx = fxOf(rod);
        if (fx != null) burst(e.getPlayer(), fx, e.getHook().getLocation());
    }

    @EventHandler
    public void onFxQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        lastBurst.remove(e.getPlayer().getUniqueId());
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
        String skinName = book.name() == null ? book.key().getKey() : book.name();
        lore.add(Text.item(skin.fx() != null ? "<gold>★ 후원 스킨: </gold>" + skinName : "<dark_purple>✦ 스킨: </dark_purple>" + skinName));
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
