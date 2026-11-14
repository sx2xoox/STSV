package dev.stsv.core.cosmetic;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.CustomItem.CosmeticSlot;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cosmetics: purely visual items worn on top of the real equipment.
 *
 * Showing: the real helmet, its protection and the helmet slot are untouched; only what the clients are
 * told changes (CosmeticPackets rewrites the outgoing packets):
 *  - other players get the cosmetic as the wearer's head item;
 *  - the wearer's own helmet slot is sent as a self-view item (CosmeticViews) that looks exactly like the
 *    real helmet (or the empty slot) in the inventory but is drawn as the cosmetic on the head, so it
 *    shows in third person (F5) and on the inventory paper doll. Only after the resource pack loaded.
 * If packets cannot be rewritten (unknown server internals), others get the look as equipment changes,
 * re-sent whenever the server would overwrite it, and the wearer sees it on the wardrobe mannequin.
 * The other slots (above the head, shoulder, back, tail) are models carried along with the player
 * (CosmeticAttachments). Nothing is shown while the player is invisible, dead or spectating.
 *
 * Wardrobe: /치장 anywhere (standing on the ground, not hurt in the last 10 seconds, not burning, not in
 * water or a vehicle) opens the menu. A mannequin wearing the player's skin stands where they stood and
 * a spectator camera looks at it from a few blocks ahead. Clicking a cosmetic wears it (or takes it off
 * if worn, or only previews it if not owned), closes the menu and shows the camera (nothing else on
 * screen); sneaking reopens the menu, the exit button leaves (back to the same spot, nothing changed).
 * Tabs on top show one slot at a time; one cosmetic per slot is worn.
 */
public final class CosmeticModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    private NamespacedKey keyEquipped, keyOwned, keyReturn, keyWardrobeEntity, keyInvisible, keyAttachment, keySelfView;
    /** when each player last took damage (no wardrobe within 10 seconds of a hit: no escape from a fight) */
    private final Map<UUID, Long> lastHurt = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private CosmeticStore store;
    /** null = packet rewriting unavailable (fallback: equipment changes to other players only). */
    private CosmeticPackets packets;
    /**
     * Per player: the self-view wrappers in the resource pack their client has loaded (snapshot taken
     * when it reported SUCCESSFULLY_LOADED; a rebuilt pack only counts once the client has it).
     */
    private final Map<UUID, Set<String>> packViews = new HashMap<>();
    /** players currently shown their own cosmetic */
    private final Set<UUID> selfShown = new java.util.HashSet<>();
    private final Map<UUID, Boolean> pendingSync = new HashMap<>();
    /** the models worn outside the head slot */
    private CosmeticAttachments attachments;
    /** pets following their owners */
    private CosmeticPets pets;
    /** where each player last left a trail mark */
    private final Map<UUID, Location> trailAt = new HashMap<>();
    /** per player: whether their cosmetics were hidden (invisible, dead, spectator) at the last check */
    private final Map<UUID, Boolean> concealed = new HashMap<>();
    private long ticks;

    @Override
    public String id() {
        return "cosmetics";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        keyEquipped = new NamespacedKey(plugin, "cosmetic_head");
        keyOwned = new NamespacedKey(plugin, "cosmetics_owned");
        keyReturn = new NamespacedKey(plugin, "wardrobe_return");
        keyWardrobeEntity = new NamespacedKey(plugin, "wardrobe_entity");
        keyInvisible = new NamespacedKey(plugin, "wardrobe_invisible");
        keyAttachment = new NamespacedKey(plugin, "cosmetic_attachment");
        keySelfView = new NamespacedKey(plugin, "cosmetic_self_view");
        store = new CosmeticStore(plugin.db());
        try {
            store.load(plugin.getDataFolder().toPath().resolve("data").resolve("cosmetics.yml"));
        } catch (java.io.IOException e) {
            throw new RuntimeException("치장 데이터 가져오기 실패", e);
        }
        try {
            packets = new CosmeticPackets(plugin.getLogger());
        } catch (Throwable t) {
            packets = null;
            plugin.getLogger().warning("치장 패킷 처리를 쓸 수 없어 다른 사람에게만 보입니다: " + t);
        }
        // leftovers from a crash or a reload (preview and worn models are not saved, but be safe)
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(keyWardrobeEntity) || e.getPersistentDataContainer().has(keyAttachment)) e.remove();
            }
        }
        attachments = new CosmeticAttachments(plugin, keyAttachment, packets, this::selfView);
        pets = new CosmeticPets(plugin, keyAttachment);
        loadAnchors();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (packets == null) {
            // safety net: whatever overwrote the look (resyncs we cannot see), fix it within 5 seconds
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) if (equipped(p).isPresent()) refresh(p);
            }, 100L, 100L);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (packets != null) packets.inject(p);
            migrate(p);
            restoreIfStuck(p);
            makeVisible(p);
            refreshLater(p);
        }
        // enabled with players online (plugin reload): they keep the pack they have; the pack module is
        // enabled after this one, so look at its build on the next tick
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getResourcePackStatus() == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
                    packViews.put(p.getUniqueId(), currentViews());
                    refresh(p);
                }
            }
        });
    }

    @Override
    public void reload() {
        loadAnchors();
        Bukkit.getOnlinePlayers().forEach(this::refresh);
    }

    private void loadAnchors() {
        List<String> problems = new ArrayList<>();
        attachments.anchors(CosmeticAttachments.anchorsFrom(dev.stsv.core.util.Cfg.listed(plugin.getConfig().getConfigurationSection("cosmetics.anchors")), problems));
        problems.forEach(m -> plugin.getLogger().warning("config.yml " + m));
    }

    /** Every tick: the worn models follow their wearers; every 5 ticks: hide or show with invisibility etc. */
    private void tick() {
        attachments.tick();
        pets.tick();
        if (++ticks % 3 == 0) trails();
        if (ticks % 5 != 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean now = concealed(p);
            Boolean was = concealed.put(p.getUniqueId(), now);
            if (was != null && was != now) refresh(p);
        }
    }

    /** No cosmetics are shown on an invisible, dead or spectating player (the real helmet shows as usual). */
    private static boolean concealed(Player p) {
        return p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.isInvisible()
                || p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
    }

    /** Lying, swimming, crawling, gliding, spinning, sleeping: nothing made for a standing body fits. */
    public static boolean lying(Player p) {
        return CosmeticAttachments.badPose(p);
    }

    /**
     * A display of another module that rides the player on the clients, as the worn models do: carried along with
     * the player every frame, no packets and no lag (the emote puppet's carrier). null: no more. Show it to its
     * viewers, the player among them, after this call.
     *
     * @return whether the clients carry it (false: the caller has to move it itself)
     */
    public boolean carry(Player p, org.bukkit.entity.Entity display) {
        return attachments != null && attachments.carry(p, display == null ? null : new int[]{display.getEntityId()});
    }

    /** Several displays at once (their entity ids; null: no more): the emote wheel on the player's own screen. One set a player. */
    public boolean carryAll(Player p, int[] ids) {
        return attachments != null && attachments.carry(p, ids);
    }

    /**
     * The name tag drawn above a player instead of the game's own (nicknames): a text display that follows them
     * like the worn models. null removes it. The game's tag must be hidden by the caller (scoreboard team).
     */
    public void nameTag(Player p, net.kyori.adventure.text.Component text) {
        if (attachments != null) attachments.nameTag(p, text);
    }

    /**
     * A name tag that is a picture of its own (a title banner): no background, the game's text shadow, not seen
     * through walls; drawn at {@code size} and lifted by {@code lift} blocks (at size 1) above the usual place.
     */
    public void pictureTag(Player p, net.kyori.adventure.text.Component text, float size, float lift) {
        if (attachments != null) attachments.nameTag(p, text, new CosmeticAttachments.TagStyle(false, size, lift));
    }

    /** Blocks above the top of the hitbox for the bottom of the name tag. */
    public void nameTagHeight(double blocks) {
        if (attachments != null) attachments.tagHeight((float) blocks);
    }

    /** Debug: the entity id of the player's name tag display, -1 if none. */
    public int nameTagDisplay(Player p) {
        return attachments == null ? -1 : attachments.tagDisplay(p);
    }

    /** Whether the player sees their own models above the head, on the shoulder, back and tail. */
    public boolean selfView(Player p) {
        return p.getPersistentDataContainer().getOrDefault(keySelfView, PersistentDataType.BYTE, (byte) 0) == 1;
    }

    public void selfView(Player p, boolean on) {
        if (on) p.getPersistentDataContainer().set(keySelfView, PersistentDataType.BYTE, (byte) 1);
        else p.getPersistentDataContainer().remove(keySelfView);
        attachments.selfViewChanged(p);
    }

    @Override
    public void disable() {
        new ArrayList<>(sessions.keySet()).forEach(id -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) exitWardrobe(p, false);
        });
        if (attachments != null) attachments.removeAll();
        if (pets != null) pets.removeAll();
        if (packets != null) {
            packets.clear();
            for (Player p : Bukkit.getOnlinePlayers()) {
                packets.selfView(p, null, Set.of());
                packets.uninject(p);
            }
            // put the real helmet back on every screen (also with a menu open, unlike updateInventory)
            for (Player p : Bukkit.getOnlinePlayers()) {
                ItemStack helmet = helmet(p);
                p.sendEquipmentChange(p, EquipmentSlot.HEAD, helmet);
                for (Player v : p.getTrackedBy()) if (v != p) v.sendEquipmentChange(p, EquipmentSlot.HEAD, helmet);
            }
            packets = null;
        }
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------------------------------
    // Ownership & equipping
    // ------------------------------------------------------------------------------------------

    /** Every cosmetic, by slot, then rarity, then name (CosmeticBrowser.order). */
    public List<CustomItem> cosmetics() {
        return plugin.content().items().stream().filter(i -> i.cosmetic() != null)
                .sorted(CosmeticBrowser.order())
                .toList();
    }

    /**
     * Owned = in the account's list. Admins can own everything for testing (cosmetics.op-owns-all), and a
     * rank can be given a specific cosmetic with the permission stsv.cosmetic.&lt;id&gt; (only if explicitly set,
     * so operators do not silently own everything).
     */
    public boolean owns(Player p, CustomItem c) {
        if (plugin.getConfig().getBoolean("cosmetics.op-owns-all") && p.hasPermission("stsv.cosmetic.*")) return true;
        String node = "stsv.cosmetic." + c.key().getKey();
        if (p.isPermissionSet(node) && p.hasPermission(node)) return true;
        return store.owned(p.getUniqueId()).contains(c.key().asString());
    }

    public Set<String> owned(Player p) {
        return store.owned(p.getUniqueId());
    }

    /** Owned by account (also for players who are not online; without the permission-based ones). */
    public Set<String> owned(UUID player) {
        return store.owned(player);
    }

    /** Gives a cosmetic to an account, online or not (rewards). Returns true if it was new. */
    public boolean grant(UUID player, CustomItem c) {
        boolean added = store.grant(player, c.key().asString());
        if (added) save();
        return added;
    }

    /** Grants or revokes. Returns true if something changed. */
    public boolean grant(Player p, CustomItem c, boolean give) {
        boolean changed = give ? store.grant(p.getUniqueId(), c.key().asString())
                : store.revoke(p.getUniqueId(), c.key().asString());
        if (changed) save();
        if (!give) refresh(p);
        return changed;
    }

    /**
     * What a player carries on their back for real, shown in the back slot in place of their back cosmetic (the 지게,
     * vehicle.JigeModule): player -> a look with a cosmetic placement (not a real cosmetic item), or null. Call
     * {@link #refresh} when it changes.
     */
    private java.util.function.Function<Player, CustomItem> backOverride;

    public void backOverride(java.util.function.Function<Player, CustomItem> f) {
        this.backOverride = f;
    }

    /** The head slot. */
    public Optional<CustomItem> equipped(Player p) {
        return Optional.ofNullable(worn(p).get(CosmeticSlot.HEAD));
    }

    /**
     * What the player wears, by slot. A cosmetic whose slot was changed in the content since it was put on
     * moves to its new slot (if that one is free).
     */
    public Map<CosmeticSlot, CustomItem> worn(Player p) {
        Map<CosmeticSlot, CustomItem> out = new EnumMap<>(CosmeticSlot.class);
        List<CustomItem> moved = new ArrayList<>();
        store.worn(p.getUniqueId()).forEach((slotId, id) -> plugin.content().item(id).filter(i -> i.cosmetic() != null).ifPresent(i -> {
            if (i.cosmetic().slot().id.equals(slotId)) out.put(i.cosmetic().slot(), i);
            else moved.add(i);
        }));
        for (CustomItem i : moved) out.putIfAbsent(i.cosmetic().slot(), i);
        return out;
    }

    /** Wears the cosmetic in its slot (replacing what was there). */
    public void equip(Player p, CustomItem c) {
        store.setWorn(p.getUniqueId(), c.cosmetic().slot().id, c.key().asString());
        save();
        refresh(p);
    }

    /** Takes off the slot (null: every slot). */
    public void takeOff(Player p, CosmeticSlot slot) {
        for (CosmeticSlot s : slot == null ? CosmeticSlot.values() : new CosmeticSlot[]{slot}) store.setWorn(p.getUniqueId(), s.id, null);
        save();
        refresh(p);
    }

    /** Changes are written to the database as they happen (CosmeticStore); nothing to flush. */
    private void save() {
    }

    /** Older versions kept ownership in the player's data file; move it into the store once. */
    private void migrate(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        boolean changed = false;
        String owned = pdc.get(keyOwned, PersistentDataType.STRING);
        if (owned != null) {
            for (String id : owned.split(",")) if (!id.isBlank()) changed |= store.grant(p.getUniqueId(), id);
            pdc.remove(keyOwned);
        }
        String eq = pdc.get(keyEquipped, PersistentDataType.STRING);
        if (eq != null) {
            if (store.equipped(p.getUniqueId()).isEmpty()) store.setEquipped(p.getUniqueId(), eq);
            pdc.remove(keyEquipped);
            changed = true;
        }
        if (changed) save();
    }

    // ------------------------------------------------------------------------------------------
    // Vouchers
    // ------------------------------------------------------------------------------------------

    @EventHandler
    public void onVoucher(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || inWardrobe(e.getPlayer())) return;
        Optional<CustomItem> v = plugin.content().itemOf(e.getItem()).filter(i -> i.voucher() != null);
        if (v.isEmpty()) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Optional<CustomItem> c = plugin.content().item(v.get().voucher()).filter(i -> i.cosmetic() != null);
        if (c.isEmpty()) {
            p.sendMessage(Lang.msg("cosmetic.voucher-unknown"));
            return;
        }
        if (store.owned(p.getUniqueId()).contains(c.get().key().asString())) {
            dev.stsv.core.hud.HudModule.flash(p, Lang.mm("cosmetic.already-owned"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f);
            return;
        }
        e.getItem().subtract(1);
        store.grant(p.getUniqueId(), c.get().key().asString());
        save();
        celebrate(p, c.get());
    }

    /** "New cosmetic!" moment: title in the rarity colour, toast sound, particles. */
    public void celebrate(Player p, CustomItem c) {
        CustomItem.Rarity r = c.cosmetic().rarity();
        p.showTitle(net.kyori.adventure.title.Title.title(
                Lang.mm("cosmetic.got-title", "color", r.color),
                Lang.mm("cosmetic.got-subtitle", "cosmetic", displayName(c), "color", r.color, "rarity", r.label),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200),
                        java.time.Duration.ofSeconds(3), java.time.Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        p.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.4, 0.6, 0.4, 0.4);
        p.sendMessage(Lang.msg("cosmetic.got", "cosmetic", displayName(c)));
    }

    /** Updates what the player's head looks like, to everyone who sees them and to themselves. */
    public void refresh(Player p) {
        if (!p.isOnline() || sessions.containsKey(p.getUniqueId())) return;
        Map<CosmeticSlot, CustomItem> all = concealed(p) ? Map.of() : worn(p);
        java.util.function.Function<Player, CustomItem> over = backOverride;
        CustomItem back = over == null || concealed(p) ? null : over.apply(p);
        if (back != null) {
            Map<CosmeticSlot, CustomItem> with = new EnumMap<>(CosmeticSlot.class);
            with.putAll(all);
            with.put(CosmeticSlot.BACK, back);   // something carried on the back for real (the 지게) goes over the back cosmetic
            all = with;
        }
        attachments.set(p, all);
        pets.set(p, all.get(CosmeticSlot.PET));
        Optional<CustomItem> worn = Optional.ofNullable(all.get(CosmeticSlot.HEAD));
        if (packets != null) {
            packets.othersView(p.getEntityId(), worn.map(c -> c.create(1)).orElse(null));
            // re-send the real helmet to the others: the packets are rewritten on the way out
            ItemStack helmet = helmet(p);
            for (Player v : p.getTrackedBy()) if (v != p) v.sendEquipmentChange(p, EquipmentSlot.HEAD, helmet);
            syncSelf(p);
            return;
        }
        ItemStack look = look(p);
        for (Player v : p.getTrackedBy()) if (v != p) v.sendEquipmentChange(p, EquipmentSlot.HEAD, look);
    }

    private static ItemStack helmet(Player p) {
        ItemStack h = p.getInventory().getHelmet();
        return h == null ? ItemStack.empty() : h;
    }

    private Set<String> currentViews() {
        dev.stsv.core.pack.PackBuilder.Built built = plugin.pack() == null ? null : plugin.pack().current();
        return built == null ? Set.of() : built.cosmeticViews();
    }

    /**
     * Tells the packet layer what the player sees on their own head and puts it in their helmet slot
     * now. Sent as an equipment change of their own entity: the client stores it straight in the slot,
     * also while a chest or menu is open (updateInventory only re-sends the open menu).
     */
    private void syncSelf(Player p) {
        if (packets == null || !p.isOnline() || sessions.containsKey(p.getUniqueId())) return;
        Set<String> views = packViews.getOrDefault(p.getUniqueId(), Set.of());
        Optional<CustomItem> worn = shownHead(p);
        String id = worn.isPresent() && !views.isEmpty() ? worn.get().key().asString() : null;
        packets.selfView(p, id, views);
        ItemStack helmet = helmet(p);
        ItemStack shown = id == null ? null : CosmeticViews.selfView(helmet, id, views);
        boolean wasShown = id != null ? !selfShown.add(p.getUniqueId()) : selfShown.remove(p.getUniqueId());
        if (shown != null || wasShown) p.sendEquipmentChange(p, EquipmentSlot.HEAD, shown != null ? shown : helmet);
    }

    /**
     * The client predicts some changes itself and the server only corrects what it thinks differs, so
     * the client can be left with the real helmet (or nothing) in the slot: put the view back next tick.
     *
     * @param inventory also re-send the inventory (the client may hold a copy of the view in its hand)
     */
    private void syncSelfLater(Player p, boolean inventory) {
        if (packets == null || !selfShown.contains(p.getUniqueId())) return;
        Boolean pending = pendingSync.put(p.getUniqueId(), inventory || Boolean.TRUE.equals(pendingSync.get(p.getUniqueId())));
        if (pending != null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Boolean inv = pendingSync.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (Boolean.TRUE.equals(inv) && p.getOpenInventory().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                p.updateInventory();
            }
            syncSelf(p);
        });
    }

    private ItemStack look(Player p) {
        return shownHead(p).map(c -> c.create(1)).orElseGet(() -> p.getInventory().getHelmet());
    }

    /** The head cosmetic as others see it now (none while concealed). */
    private Optional<CustomItem> shownHead(Player p) {
        return concealed(p) ? Optional.empty() : equipped(p);
    }

    private void refreshLater(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> refresh(p));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onJoinInject(PlayerJoinEvent e) {
        if (packets != null) packets.inject(e.getPlayer());
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { migrate(e.getPlayer()); restoreIfStuck(e.getPlayer()); makeVisible(e.getPlayer()); refreshLater(e.getPlayer()); }
    /** A respawned player is a new entity on their own client: the models riding the old one are made anew. */
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { attachments.remove(e.getPlayer()); pets.remove(e.getPlayer()); refreshLater(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { attachments.remove(e.getPlayer()); pets.remove(e.getPlayer()); refreshLater(e.getPlayer()); }
    @EventHandler public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) { attachments.remove(e.getPlayer()); pets.remove(e.getPlayer()); }

    // ------------------------------------------------------------------------------------------
    // Trails and kill effects
    // ------------------------------------------------------------------------------------------

    /** Every 3 ticks: a mark of the trail where each wearer has walked on (not standing still). */
    private void trails() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (inWardrobe(p) || concealed(p)) continue;
            CustomItem c = worn(p).get(CosmeticSlot.TRAIL);
            if (c == null || c.cosmetic().fx() == null) {
                trailAt.remove(p.getUniqueId());
                continue;
            }
            Location here = p.getLocation();
            Location last = trailAt.get(p.getUniqueId());
            if (last != null && last.getWorld() == here.getWorld() && last.distanceSquared(here) < 0.15 * 0.15) continue;
            trailAt.put(p.getUniqueId(), here);
            // behind the wearer: others see them a few ticks late, a mark at their feet would be under the legs
            Location mark = CosmeticEffects.trailMark(last, here).add(0, c.cosmetic().fx().height(), 0);
            CosmeticEffects.play(viewersOf(p, mark, 48), mark, c.cosmetic().fx(), ticks, false);
        }
    }

    /** A wearer killed something: their effect where it died. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onKill(org.bukkit.event.entity.EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null || killer == e.getEntity() || inWardrobe(killer) || concealed(killer)) return;
        CustomItem c = worn(killer).get(CosmeticSlot.KILL);
        if (c == null || c.cosmetic().fx() == null) return;
        Location at = e.getEntity().getLocation().add(0, e.getEntity().getHeight() / 2, 0);
        CosmeticEffects.play(viewersOf(killer, at, 64), at, c.cosmetic().fx(), ticks, true);
    }

    /** Everyone near the effect who can see the wearer, and the wearer (CosmeticEffects.shownTo). */
    private static List<Player> viewersOf(Player p, Location at, double range) {
        List<Player> out = new ArrayList<>();
        for (Player v : at.getWorld().getPlayers()) {
            if (CosmeticEffects.shownTo(v == p, v.canSee(p), v.getLocation().distanceSquared(at), range)) out.add(v);
        }
        return out;
    }
    /** Swinging turns the body to the head on every client: the worn models turn with it. */
    @EventHandler public void onSwing(io.papermc.paper.event.player.PlayerArmSwingEvent e) { attachments.swung(e.getPlayer()); }

    /**
     * The self view uses models from the server resource pack: only with the pack the client has loaded.
     * A failed (re)load may leave the client without them, so the view is withdrawn.
     */
    @EventHandler
    public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> packViews.put(p.getUniqueId(), currentViews());
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> packViews.remove(p.getUniqueId());
            default -> {
                return;
            }
        }
        if (shownHead(p).isPresent() || selfShown.contains(p.getUniqueId())) refreshLater(p);
    }

    @EventHandler
    public void onEquipment(EntityEquipmentChangedEvent e) {
        if (!(e.getEntity() instanceof Player p) || equipped(p).isEmpty()) return;
        if (packets == null) {
            refreshLater(p);
            return;
        }
        // packets are rewritten as they go out, except when the client already predicted the new helmet
        // (it put it in the slot itself, so the server sends nothing)
        if (e.getEquipmentChanges().containsKey(EquipmentSlot.HEAD)) syncSelfLater(p, false);
    }

    /** e.g. picking up the empty-slot view: the server only corrects the cursor, the slot stays empty. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onClickView(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) syncSelfLater(p, false);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onDragView(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p) syncSelfLater(p, false);
    }

    /**
     * Right-clicking a helmet: the client swaps it with the view (they differ) while the server may
     * refuse (same as the real one) and send nothing back, leaving the view in the client's hand.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onUseHeadItem(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null || item.isEmpty()) return;
        @SuppressWarnings("UnstableApiUsage")
        io.papermc.paper.datacomponent.item.Equippable eq = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.EQUIPPABLE);
        if (eq != null && eq.slot() == EquipmentSlot.HEAD) syncSelfLater(e.getPlayer(), true);
    }

    /** Creative clients send whole items back: never let a self-view item become a real one. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onCreativeView(org.bukkit.event.inventory.InventoryCreativeEvent e) {
        ItemStack item = e.getCursor();
        ItemStack real = CosmeticViews.restore(item);
        if (real != item) e.setCursor(real);
    }

    /** A player starts seeing someone: give them the cosmetic look right after the vanilla packets. */
    @EventHandler
    public void onTrack(PlayerTrackEntityEvent e) {
        if (packets != null) return; // the spawn packets are rewritten (a head item is added if missing)
        if (!(e.getEntity() instanceof Player target) || shownHead(target).isEmpty()) return;
        Player viewer = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline() && target.isOnline() && !sessions.containsKey(target.getUniqueId())) {
                viewer.sendEquipmentChange(target, EquipmentSlot.HEAD, look(target));
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Wardrobe session, two states:
    //  - PREVIEW: spectator camera fixed on a mannequin wearing the player's own skin. Only the mannequin
    //    is seen: no body, no hand, no hotbar.
    //  - MENU: the cosmetics menu. Spectators' inventory clicks are ignored by the server, so while the
    //    menu is open the player is in creative mode (frozen, invisible, item powers off) at the camera spot.
    // Clicking a cosmetic (wear / take off / preview) or closing the menu goes to PREVIEW;
    // sneaking in PREVIEW (which would normally leave the camera) opens the MENU; the exit button leaves.
    // ------------------------------------------------------------------------------------------

    private static final class Session {
        Location origin;
        Location spot;
        boolean wasInvulnerable;
        boolean addedInvisibility;
        Mannequin mannequin;
        BukkitTask spin;
        /** the last cosmetic clicked (glint in the menu) */
        String previewing;
        /** what the mannequin wears, by slot: what the player wears, with tried-on cosmetics in their slots */
        final Map<CosmeticSlot, String> look = new EnumMap<>(CosmeticSlot.class);
        /** the mannequin's models outside the head slot */
        final Map<CosmeticSlot, ItemDisplay> models = new EnumMap<>(CosmeticSlot.class);
        /** ticks since the wardrobe opened (effect previews) */
        long previewTicks;
        /** tab: "all", "worn" or a slot id */
        String tab = "all";
        /** 보기: everything, only what the player has, or only what they lack */
        CosmeticBrowser.View view = CosmeticBrowser.View.ALL;
        /** the menu is being reopened on another tab (its page must not be kept) */
        boolean tabbing;
        dev.stsv.core.gui.menu.MenuView menu;
        boolean closing;
        GameMode gameMode;
        ItemDisplay camera;
        boolean switching;
        boolean toMenu;
        int page;
    }


    public boolean inWardrobe(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    public void enterWardrobe(Player p) {
        if (inWardrobe(p)) {
            Session s = sessions.get(p.getUniqueId());
            // menu clicks only count outside spectator mode: go through the camera -> menu switch
            if (p.getGameMode() == GameMode.SPECTATOR) {
                if (p.getSpectatorTarget() != s.camera) p.setSpectatorTarget(s.camera);
                toMenu(p);
            } else {
                openMenu(p);
            }
            return;
        }
        String refusal = refusal(p);
        if (refusal != null) {
            p.sendMessage(Lang.msg(refusal));
            return;
        }
        Location stand = p.getLocation().clone();
        stand.setPitch(0);
        Vector forward = stand.getDirection().setY(0).normalize();
        Location spot = null;
        double want = plugin.getConfig().getDouble("cosmetics.wardrobe-distance");
        for (double d : new double[]{want, want - 0.5, want - 1.0}) {
            Location c = stand.clone().add(forward.clone().multiply(d));
            if (c.getBlock().isPassable() && c.clone().add(0, 1, 0).getBlock().isPassable()) {
                spot = c;
                break;
            }
        }
        if (spot == null) {
            p.sendMessage(Lang.msg("wardrobe.no-room", "blocks", (int) Math.ceil(want)));
            return;
        }
        // look straight at the mannequin's middle: the menu is closed while previewing, so it is centred
        spot.setDirection(stand.clone().add(0, 1.0, 0).toVector().subtract(spot.clone().add(0, 1.62, 0).toVector()));

        Session s = new Session();
        s.origin = p.getLocation().clone();
        s.spot = spot;
        s.wasInvulnerable = p.isMarkedInvulnerable();
        s.gameMode = p.getGameMode();
        worn(p).forEach((slot, c) -> s.look.put(slot, c.key().asString()));
        attachments.remove(p);
        pets.remove(p);
        Location o = s.origin;
        p.getPersistentDataContainer().set(keyReturn, PersistentDataType.STRING, String.join(";",
                o.getWorld().getName(), "" + o.getX(), "" + o.getY(), "" + o.getZ(), "" + o.getYaw(), "" + o.getPitch(),
                "" + s.wasInvulnerable, s.gameMode.name()));

        s.mannequin = p.getWorld().spawn(stand, Mannequin.class, m -> {
            m.setVisibleByDefault(false);
            m.setPersistent(false);
            m.setInvulnerable(true);
            m.setGravity(false);
            m.setSilent(true);
            m.setImmovable(true);
            m.setProfile(ResolvableProfile.resolvableProfile(p.getPlayerProfile()));
            m.setDescription(Text.mm("<gray>치장 미리보기"));
            m.getPersistentDataContainer().set(keyWardrobeEntity, PersistentDataType.BYTE, (byte) 1);
            var eq = m.getEquipment();
            eq.setArmorContents(p.getInventory().getArmorContents());
            eq.setItemInMainHand(p.getInventory().getItemInMainHand());
        });
        // the camera: at eye height of the viewing spot, looking at the mannequin
        Location cam = spot.clone().add(0, 1.62, 0);
        s.camera = p.getWorld().spawn(cam, ItemDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.getPersistentDataContainer().set(keyWardrobeEntity, PersistentDataType.BYTE, (byte) 1);
        });
        p.showEntity(plugin, s.mannequin);
        p.showEntity(plugin, s.camera);
        sessions.put(p.getUniqueId(), s);
        showOnMannequin(p, s);

        for (Player other : Bukkit.getOnlinePlayers()) if (other != p) other.hidePlayer(plugin, p);
        if (!p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY,
                    org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false, false));
            s.addedInvisibility = true;
            p.getPersistentDataContainer().set(keyInvisible, PersistentDataType.BYTE, (byte) 1);
        }
        p.setInvulnerable(true);
        s.spin = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!s.mannequin.isValid()) return;
            s.mannequin.setRotation(s.mannequin.getYaw() + 1.5f, 0);
            float body = s.mannequin.getBodyYaw();
            s.models.forEach((slot, d) -> {
                if (slot != CosmeticSlot.PET && d.isValid()) d.setRotation(CosmeticAttachments.onBody(slot) ? body : s.mannequin.getYaw(), 0);
            });
            // trail and kill effect on the mannequin, for this player only
            s.previewTicks++;
            previewEffect(p, s, CosmeticSlot.TRAIL, 6, s.mannequin.getLocation());
            previewEffect(p, s, CosmeticSlot.KILL, 50, s.mannequin.getLocation().add(0, 1.0, 0));
        }, 20L, 1L);
        p.playSound(spot, Sound.BLOCK_WOODEN_DOOR_OPEN, 0.8f, 1.2f);
        // attach the camera first, then open the menu: while the client's camera is another entity it
        // draws no hotbar/health, so the menu shows over the mannequin view without any HUD
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(s.camera.getLocation());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!inWardrobe(p)) return;
            p.setSpectatorTarget(s.camera);
            Bukkit.getScheduler().runTaskLater(plugin, () -> toMenu(p), 1L);
        }, 2L);
    }

    /**
     * Why the wardrobe cannot open here and now (message key), or null. The player is frozen and safe
     * inside it and comes back to the same spot, so it must not cut a fall, a fight or a fire short.
     */
    private String refusal(Player p) {
        if (p.isInsideVehicle()) return "wardrobe.dismount";
        if (p.getGameMode() == GameMode.SPECTATOR) return "wardrobe.not-now";
        if (p.isGliding() || p.isSleeping() || p.isSwimming() || p.isRiptiding()) return "wardrobe.not-now";
        if (p.isInWater() || p.isInLava()) return "wardrobe.in-water";
        if (p.getFireTicks() > 0) return "wardrobe.burning";
        @SuppressWarnings("deprecation") boolean ground = p.isOnGround();
        if (!ground || p.getFallDistance() > 0.5f) return "wardrobe.not-ground";
        Long hurt = lastHurt.get(p.getUniqueId());
        if (hurt != null && System.currentTimeMillis() - hurt < 10_000) return "wardrobe.in-combat";
        return null;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) lastHurt.put(p.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * MENU state: creative mode with the camera still attached (leaving spectator would detach it; that
     * event is cancelled while switching), then the menu.
     *  - not spectator: the server ignores spectators' inventory clicks
     *  - not adventure: the client draws the held item and the experience bar in adventure/survival even
     *    while its camera is another entity; creative draws neither (and no hearts)
     *  - creative's item powers are shut off for wardrobe players (onCreativeSlot), and the held item is
     *    shown as empty to the player only (hideHands), so no hand appears beside the menu
     */
    private void toMenu(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        s.toMenu = true;
        p.setGameMode(GameMode.CREATIVE);
        s.toMenu = false;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!inWardrobe(p)) return;
            openMenu(p);
            hideHands(p);
        }, 1L);
    }

    /** Client-side only: the player's own hands look empty (their invisible arm is then not drawn). */
    private void hideHands(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!inWardrobe(p)) return;
            ItemStack air = new ItemStack(org.bukkit.Material.AIR);
            p.sendEquipmentChange(p, java.util.Map.of(org.bukkit.inventory.EquipmentSlot.HAND, air,
                    org.bukkit.inventory.EquipmentSlot.OFF_HAND, air));
        });
    }

    /** Creative mode can create items (hacked clients send creative slot packets): never in the wardrobe. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onCreativeSlot(org.bukkit.event.inventory.InventoryCreativeEvent e) {
        if (e.getWhoClicked() instanceof Player p && inWardrobe(p)) e.setCancelled(true);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onWardrobeDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (inWardrobe(e.getPlayer())) e.setCancelled(true);
    }

    /** PREVIEW state: spectator camera on the mannequin (no body, hand or hotbar on screen). */
    private void toPreview(Player p, Component title, Component subtitle) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(s.camera.getLocation());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!inWardrobe(p)) return;
            p.setSpectatorTarget(s.camera);
            p.showTitle(net.kyori.adventure.title.Title.title(title, subtitle,
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(150),
                            java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(400))));
        }, 2L);
    }

    private void showOnMannequin(Player p, Session s) {
        ItemStack head = Optional.ofNullable(s.look.get(CosmeticSlot.HEAD)).flatMap(id -> plugin.content().item(id))
                .map(c -> c.create(1)).orElse(p.getInventory().getHelmet());
        s.mannequin.getEquipment().setHelmet(head);
        s.models.values().forEach(Entity::remove);
        s.models.clear();
        Location top = s.mannequin.getLocation().add(0, s.mannequin.getHeight(), 0);
        s.look.forEach((slot, id) -> {
            if (slot == CosmeticSlot.HEAD || slot == CosmeticSlot.TRAIL || slot == CosmeticSlot.KILL) return;   // effects: previewEffect
            plugin.content().item(id).filter(c -> c.cosmetic() != null).ifPresent(c -> {
                ItemDisplay d = slot == CosmeticSlot.PET ? pets.spawnPreview(s.mannequin.getLocation(), c)
                        : attachments.spawnPreview(top, s.mannequin.getBodyYaw(), slot, c);
                p.showEntity(plugin, d);
                s.models.put(slot, d);
            });
        });
    }

    /** The trail or kill effect the mannequin shows, every `every` ticks, to this player only. */
    private void previewEffect(Player p, Session s, CosmeticSlot slot, int every, Location at) {
        if (s.previewTicks % every != 0) return;
        String id = s.look.get(slot);
        if (id == null) return;
        plugin.content().item(id).filter(c -> c.cosmetic() != null && c.cosmetic().fx() != null)
                .ifPresent(c -> CosmeticEffects.play(List.of(p), slot == CosmeticSlot.TRAIL ? at.clone().add(0, c.cosmetic().fx().height(), 0) : at,
                        c.cosmetic().fx(), s.previewTicks, slot == CosmeticSlot.KILL, true));
    }

    /** Slots a tab shows: one slot, or null (all, worn). */
    private static List<CosmeticSlot> tabSlots(String tab) {
        CosmeticSlot slot = CosmeticSlot.parse(tab);
        return slot == null ? null : List.of(slot);
    }

    private static boolean isWorn(Map<CosmeticSlot, CustomItem> worn, CustomItem c) {
        CustomItem w = worn.get(c.cosmetic().slot());
        return w != null && w.key().equals(c.key());
    }

    /** Cosmetics of the open tab (every one, owned or not), before the 보기 filter. */
    private List<CustomItem> onTab(Player p, Session s) {
        Map<CosmeticSlot, CustomItem> worn = worn(p);
        return cosmetics().stream().filter(c -> s.tab.equals("worn") ? isWorn(worn, c)
                : CosmeticBrowser.inTab(s.tab, c.cosmetic().slot())).toList();
    }

    /** Cosmetics shown on the open tab: in dex order, owned and not-owned in place, less what 보기 hides. */
    private List<CustomItem> shown(Player p, Session s) {
        return onTab(p, s).stream().filter(c -> s.view.shows(owns(p, c))).toList();
    }

    private static String slotName(CosmeticSlot slot) {
        return Lang.raw("cosmetic.slot." + slot.id);
    }

    private String tabName(String tab) {
        CosmeticSlot slot = CosmeticSlot.parse(tab);
        return slot != null ? slotName(slot) : tab.equals("worn") ? Lang.raw("wardrobe.tab-worn") : Lang.raw("wardrobe.tab-all");
    }

    /** Where a cosmetic comes from (the 치장 뽑기 pool, an achievement, a limited reward). */
    private CosmeticBrowser.Source source(CustomItem c) {
        boolean pool = plugin.gacha() != null && plugin.gacha().inPool(c.key().asString());
        return CosmeticBrowser.source(c.cosmetic().rarity(), c.cosmetic().obtain(), pool);
    }

    private static String names(java.util.Collection<CustomItem> items) {
        return items.isEmpty() ? Lang.raw("common.none")
                : String.join("<gray>, </gray>", items.stream().map(CosmeticModule::displayName).toList());
    }

    static final String WARDROBE_MENU = "stsv:wardrobe";

    /** The cosmetics menu: layout, look and texts are in content/stsv/menus/wardrobe.yml. */
    public void openMenu(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        plugin.menus().open(p, WARDROBE_MENU, new WardrobeMenu(s)).ifPresent(v -> {
            s.menu = v;
            if (s.page > 0) v.page(s.page);
        });
    }

    private final class WardrobeMenu implements dev.stsv.core.gui.menu.MenuHandler {
        private final Session s;

        WardrobeMenu(Session s) {
            this.s = s;
        }

        @Override
        public Map<String, String> placeholders(dev.stsv.core.gui.menu.MenuView v) {
            Player p = v.player();
            Map<CosmeticSlot, CustomItem> worn = worn(p);
            Map<String, String> out = new HashMap<>();
            List<CustomItem> all = cosmetics();
            List<CustomItem> tab = onTab(p, s);
            out.put("owned", String.valueOf(tab.stream().filter(c -> owns(p, c)).count()));
            out.put("total", String.valueOf(tab.size()));
            out.put("owned_all", String.valueOf(all.stream().filter(c -> owns(p, c)).count()));
            out.put("total_all", String.valueOf(all.size()));
            CosmeticBrowser.counts(all, c -> c.cosmetic().slot(), c -> owns(p, c)).forEach((k, n) -> out.put("n_" + k, n));
            out.put("n_worn", worn.size() + "/" + CosmeticSlot.values().length);
            out.put("view", Lang.raw("wardrobe.view." + s.view.key()));
            for (CosmeticBrowser.View each : CosmeticBrowser.View.values()) {
                out.put("view_" + each.key(), Lang.raw(each == s.view ? "wardrobe.view-on" : "wardrobe.view-off",
                        "view", Lang.raw("wardrobe.view." + each.key())));
            }
            out.put("worn", names(worn.values()));
            out.put("previewing", Optional.ofNullable(s.previewing).flatMap(id -> plugin.content().item(id))
                    .map(CosmeticModule::displayName).orElse(Lang.raw("common.none")));
            out.put("tab", tabName(s.tab));
            for (CosmeticSlot slot : CosmeticSlot.values()) {
                out.put("worn_" + slot.id, Optional.ofNullable(worn.get(slot)).map(CosmeticModule::displayName).orElse(Lang.raw("common.none")));
            }
            List<CosmeticSlot> tabSlots = tabSlots(s.tab);
            out.put("take_off", tabSlots == null ? Lang.raw("wardrobe.take-off-all") : Lang.raw("wardrobe.take-off-slot", "slot", tabName(s.tab)));
            out.put("self", Lang.raw(selfView(p) ? "wardrobe.self-on" : "wardrobe.self-off"));
            return out;
        }

        /** The open tab glows; the "worn" tab shows the player's own head. */
        @Override
        public ItemStack button(dev.stsv.core.gui.menu.MenuView v, dev.stsv.core.gui.menu.MenuSpec.Button b, ItemStack base) {
            if (base == null) return null;
            String action = b.action();
            if (action.startsWith("wardrobe:tab:")) {
                String tab = action.substring("wardrobe:tab:".length());
                if (tab.equals("worn") && base.getType() == org.bukkit.Material.PLAYER_HEAD) {
                    base.setData(io.papermc.paper.datacomponent.DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(v.player().getPlayerProfile()));
                }
                if (tab.equals(s.tab)) base.editMeta(m -> m.setEnchantmentGlintOverride(true));
            } else if (action.equals("wardrobe:self") && selfView(v.player())) {
                base.editMeta(m -> m.setEnchantmentGlintOverride(true));
            }
            return base;
        }

        @Override
        public List<ItemStack> list(dev.stsv.core.gui.menu.MenuView v) {
            Player p = v.player();
            Map<CosmeticSlot, CustomItem> worn = worn(p);
            List<ItemStack> out = new ArrayList<>();
            for (CustomItem c : shown(p, s)) {
                CustomItem w = worn.get(c.cosmetic().slot());
                boolean isWorn = isWorn(worn, c);
                List<Component> lore = new ArrayList<>();
                lore.add(CosmeticBrowser.rarityLine(c));
                lore.add(Text.item(""));
                if (!owns(p, c)) {
                    // not owned: the dark '?' card of its rarity, where it comes from, and what a click does
                    CosmeticBrowser.Source src = source(c);
                    lore.addAll(Lang.items("wardrobe.lore-locked"));
                    lore.addAll(CosmeticBrowser.obtainLines(c, src));
                    lore.add(Lang.item("wardrobe.lore-locked-click"));
                    if (src == CosmeticBrowser.Source.GACHA) lore.add(Lang.item("wardrobe.lore-locked-gacha"));
                    ItemStack it = CosmeticBrowser.locked(c, lore);
                    if (c.key().asString().equals(s.previewing)) it.editMeta(m -> m.setEnchantmentGlintOverride(true));
                    out.add(it);
                    continue;
                }
                if (isWorn) {
                    lore.addAll(Lang.items("wardrobe.lore-worn"));
                } else {
                    lore.addAll(Lang.items("wardrobe.lore-owned"));
                    if (w != null) lore.add(Lang.item("wardrobe.lore-replaces", "cosmetic", displayName(w)));
                }
                ItemStack it = c.create(1);
                it.editMeta(m -> {
                    m.lore(lore);
                    m.setEnchantmentGlintOverride(isWorn || c.key().asString().equals(s.previewing));
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(dev.stsv.core.gui.menu.MenuView v, int index, org.bukkit.event.inventory.ClickType click) {
            Player p = v.player();
            List<CustomItem> all = shown(p, s);
            if (index >= all.size() || !inWardrobe(p)) return;
            CustomItem c = all.get(index);
            String id = c.key().asString();
            CosmeticSlot slot = c.cosmetic().slot();
            Optional<CustomItem> before = Optional.ofNullable(worn(p).get(slot));
            if (before.filter(w -> w.key().equals(c.key())).isPresent()) {
                store.setWorn(p.getUniqueId(), slot.id, null);
                s.look.remove(slot);
                s.previewing = null;
                p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 0.8f);
                dev.stsv.core.hud.HudModule.flash(p, Lang.mm("wardrobe.took-off", "cosmetic", displayName(c)));
            } else if (owns(p, c)) {
                store.setWorn(p.getUniqueId(), slot.id, id);
                s.look.put(slot, id);
                s.previewing = id;
                p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.2f);
                dev.stsv.core.hud.HudModule.flash(p, before.isPresent()
                        ? Lang.mm("wardrobe.wearing-replace", "cosmetic", displayName(c), "old", displayName(before.get()))
                        : Lang.mm("wardrobe.wearing", "cosmetic", displayName(c)));
            } else {
                CosmeticBrowser.Source src = source(c);
                if (click.isShiftClick() && src == CosmeticBrowser.Source.GACHA && plugin.gacha() != null) {
                    // shift-click on a cosmetic of the 치장 뽑기: straight to the 뽑기 (out of the wardrobe first)
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.gacha().open(p, null));
                    return;
                }
                s.look.put(slot, id);
                s.previewing = id;
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
                dev.stsv.core.hud.HudModule.flash(p, Lang.mm("wardrobe.locked-hint." + src.key()));
            }
            showOnMannequin(p, s);
            showPreview(p, s, c);
        }

        @Override
        public void onAction(dev.stsv.core.gui.menu.MenuView v, String action, org.bukkit.event.inventory.ClickType click) {
            Player p = v.player();
            if (action.startsWith("wardrobe:tab:")) {
                String tab = action.substring("wardrobe:tab:".length());
                if (tab.equals(s.tab)) return;
                s.tab = tab;
                s.page = 0;
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.6f);
                // the title names the tab: open the menu again (next tick, never inside its own click)
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!inWardrobe(p)) return;
                    s.switching = true;
                    s.tabbing = true;
                    s.page = 0;
                    openMenu(p);
                    s.tabbing = false;
                    s.switching = false;
                    hideHands(p);
                });
                return;
            }
            switch (action) {
                case "wardrobe:take_off" -> {
                    List<CosmeticSlot> slots = tabSlots(s.tab);
                    for (CosmeticSlot each : slots == null ? List.of(CosmeticSlot.values()) : slots) {
                        store.setWorn(p.getUniqueId(), each.id, null);
                        s.look.remove(each);
                    }
                    s.previewing = null;
                    p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 0.8f);
                    showOnMannequin(p, s);
                    showPreview(p, s, null);
                }
                case "wardrobe:view" -> {
                    s.view = s.view.next();
                    s.page = 0;
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
                    v.page(0);
                }
                case "wardrobe:self" -> {
                    boolean on = !selfView(p);
                    selfView(p, on);
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, on ? 1.5f : 1.0f);
                    dev.stsv.core.hud.HudModule.flash(p, Lang.mm(on ? "wardrobe.self-turned-on" : "wardrobe.self-turned-off"));
                    v.refresh();
                }
                case "wardrobe:exit" -> Bukkit.getScheduler().runTask(plugin, () -> exitWardrobe(p, true));
                case "wardrobe:gacha" -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.gacha() != null) plugin.gacha().open(p, null);
                });
                default -> { }
            }
        }

        /** Esc on the menu: go to the camera view (not out of the wardrobe). */
        @Override
        public void onClose(dev.stsv.core.gui.menu.MenuView v) {
            if (!s.tabbing) s.page = v.page();
            Player p = v.player();
            if (s.menu != v || s.closing || s.switching) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (inWardrobe(p) && !menuOpen(p)) toPreview(p, Lang.mm("wardrobe.title"), Lang.mm("wardrobe.hint"));
            });
        }
    }

    /** Any click in the wardrobe menu makes the client resync the real held item: hide it again. */
    @EventHandler
    public void onWardrobeClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder(false) instanceof dev.stsv.core.gui.menu.MenuView v && v.spec().id().equals(WARDROBE_MENU)
                && e.getWhoClicked() instanceof Player p) hideHands(p);
    }

    /** After a click: close the menu and switch to the camera, with the cosmetic's state as a title. */
    private void showPreview(Player p, Session s, CustomItem c) {
        String state;
        CustomItem wornThere = c == null ? null : worn(p).get(c.cosmetic().slot());
        if (c == null) state = Lang.raw("wardrobe.state-none");
        else if (wornThere != null && wornThere.key().equals(c.key())) state = Lang.raw("wardrobe.state-worn");
        else if (owns(p, c)) state = Lang.raw("wardrobe.state-removed");
        else state = Lang.raw("wardrobe.state-locked");
        // never close an inventory inside its own click event: do it on the next tick
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!inWardrobe(p)) return;
            s.switching = true;
            p.closeInventory();
            s.switching = false;
            toPreview(p, c == null ? Lang.mm("wardrobe.state-none") : Text.mm(displayName(c)),
                    Lang.mm("wardrobe.subtitle", "state", state, "hint", Lang.raw("wardrobe.hint")));
        });
    }

    /**
     * The camera must stay attached for the whole session: sneaking in the preview would detach it (then
     * open the menu instead), and switching to creative for the menu would too (just keep it).
     */
    /**
     * In the preview the player is a spectator, and a spectator's click on an entity makes them watch it
     * from inside (the mannequin): never anything but the camera; a click opens the menu instead.
     */
    @EventHandler
    public void onStartSpectating(com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.closing || e.getNewSpectatorTarget() == s.camera) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!s.toMenu && !menuOpen(p)) Bukkit.getScheduler().runTask(plugin, () -> {
            if (inWardrobe(p) && p.getGameMode() == GameMode.SPECTATOR && !menuOpen(p)) toMenu(p);
        });
    }

    @EventHandler
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.closing) return;
        if (e.getSpectatorTarget() != s.camera) {
            // watching something else after all (should not happen): back onto the camera, then the menu
            Player p = e.getPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!inWardrobe(p) || p.getGameMode() != GameMode.SPECTATOR) return;
                p.setSpectatorTarget(s.camera);
                if (!menuOpen(p)) toMenu(p);
            });
            return;
        }
        e.setCancelled(true);
        if (!s.toMenu && e.getPlayer().getGameMode() == GameMode.SPECTATOR && !menuOpen(e.getPlayer())) {
            Bukkit.getScheduler().runTask(plugin, () -> toMenu(e.getPlayer()));
        }
    }

    public void exitWardrobe(Player p, boolean sound) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        s.closing = true;
        if (s.spin != null) s.spin.cancel();
        if (menuOpen(p)) p.closeInventory();
        // detach the camera (it may still be attached in creative mode), then restore the game mode
        p.setGameMode(GameMode.SPECTATOR);
        p.setSpectatorTarget(null);
        p.setGameMode(s.gameMode);
        if (s.mannequin != null) s.mannequin.remove();
        s.models.values().forEach(Entity::remove);
        if (s.camera != null) s.camera.remove();
        p.teleport(s.origin);
        p.setInvulnerable(s.wasInvulnerable);
        for (Player other : Bukkit.getOnlinePlayers()) if (other != p) other.showPlayer(plugin, p);
        makeVisible(p);
        p.updateInventory();
        // the hands were sent as empty to the player only: send the real ones back
        p.sendEquipmentChange(p, java.util.Map.of(org.bukkit.inventory.EquipmentSlot.HAND, p.getInventory().getItemInMainHand(),
                org.bukkit.inventory.EquipmentSlot.OFF_HAND, p.getInventory().getItemInOffHand()));
        // and again a little later: the game recomputes visibility from effects on its own tick
        for (long delay : new long[]{1, 5, 20}) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !inWardrobe(p)) makeVisible(p);
        }, delay);
        p.getPersistentDataContainer().remove(keyReturn);
        if (sound) p.playSound(s.origin, Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.8f, 1.2f);
        for (long delay : new long[]{1, 10, 40}) Bukkit.getScheduler().runTaskLater(plugin, () -> refresh(p), delay);
        Map<CosmeticSlot, CustomItem> worn = worn(p);
        if (!worn.isEmpty()) {
            p.sendMessage(Lang.msg("wardrobe.left", "cosmetic", names(worn.values())));
            if (!selfView(p) && worn.keySet().stream().anyMatch(slot -> slot != CosmeticSlot.HEAD)) p.sendMessage(Lang.msg("wardrobe.left-self-hint"));
        }
    }

    /**
     * Undoes the wardrobe's invisibility for good: the invisibility we added (marked on the player, or the
     * infinite hidden one an older version left without a mark), and the invisible flag spectator mode sets.
     */
    private void makeVisible(Player p) {
        if (inWardrobe(p)) return;
        var type = org.bukkit.potion.PotionEffectType.INVISIBILITY;
        org.bukkit.potion.PotionEffect inv = p.getPotionEffect(type);
        boolean marked = p.getPersistentDataContainer().has(keyInvisible);
        boolean ours = inv != null && inv.isInfinite() && !inv.hasIcon() && !inv.hasParticles() && inv.getAmplifier() == 0;
        if (inv != null && (marked || ours)) p.removePotionEffect(type);
        p.getPersistentDataContainer().remove(keyInvisible);
        if (!p.hasPotionEffect(type) && p.getGameMode() != GameMode.SPECTATOR && p.isInvisible()) p.setInvisible(false);
    }

    /** After a crash the player may still be at the viewing spot: put them back. */
    private void restoreIfStuck(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        String raw = pdc.get(keyReturn, PersistentDataType.STRING);
        if (raw == null || inWardrobe(p)) return;
        String[] a = raw.split(";");
        try {
            World w = Bukkit.getWorld(a[0]);
            if (w != null) p.teleport(new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]),
                    Double.parseDouble(a[3]), Float.parseFloat(a[4]), Float.parseFloat(a[5])));
            p.setInvulnerable(a.length > 6 && Boolean.parseBoolean(a[6]));
            if (a.length > 7) p.setGameMode(GameMode.valueOf(a[7]));
            else if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(Bukkit.getDefaultGameMode());
        } catch (RuntimeException ignored) {
            p.setInvulnerable(false);
            if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(Bukkit.getDefaultGameMode());
        }
        // the invisibility we add never outlives the session (infinite, no icon): remove it if it is ours
        org.bukkit.potion.PotionEffect inv = p.getPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        if (inv != null && inv.isInfinite() && !inv.hasIcon()) p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        pdc.remove(keyReturn);
    }

    /** Frozen in place (looking around is fine). */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || !e.hasChangedPosition() || e.getPlayer().getGameMode() == GameMode.SPECTATOR) return;
        Location to = s.camera.getLocation().clone();
        to.setYaw(e.getTo().getYaw());
        to.setPitch(e.getTo().getPitch());
        e.setTo(to);
    }

    private boolean menuOpen(Player p) {
        return p.getOpenInventory().getTopInventory().getHolder(false) instanceof dev.stsv.core.gui.menu.MenuView v
                && v.spec().id().equals(WARDROBE_MENU);
    }

    @EventHandler
    public void onInteractWhileInWardrobe(PlayerInteractEvent e) {
        if (inWardrobe(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onClickMannequin(PlayerInteractEntityEvent e) {
        if (inWardrobe(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        exitWardrobe(e.getPlayer(), false);
        packViews.remove(e.getPlayer().getUniqueId());
        selfShown.remove(e.getPlayer().getUniqueId());
        pendingSync.remove(e.getPlayer().getUniqueId());
        lastHurt.remove(e.getPlayer().getUniqueId());
        concealed.remove(e.getPlayer().getUniqueId());
        trailAt.remove(e.getPlayer().getUniqueId());
        attachments.remove(e.getPlayer());
        pets.remove(e.getPlayer());
        if (packets != null) packets.forget(e.getPlayer(), e.getPlayer().getEntityId());
    }

    @EventHandler
    public void onJoinHide(PlayerJoinEvent e) {
        for (UUID id : sessions.keySet()) {
            Player in = Bukkit.getPlayer(id);
            if (in != null && in != e.getPlayer()) e.getPlayer().hidePlayer(plugin, in);
        }
    }

    private static String displayName(CustomItem c) {
        return c.name() == null ? c.key().getKey() : c.name();
    }

    /** /stsv cosmetic info: what the plugin thinks the player is wearing (for debugging). */
    public List<String> info(Player p) {
        List<String> out = new ArrayList<>();
        Map<CosmeticSlot, CustomItem> worn = worn(p);
        out.add("착용 중: " + (worn.isEmpty() ? "없음" : String.join(", ", worn.entrySet().stream()
                .map(e -> e.getKey().id + "=" + e.getValue().key().asString()).toList())));
        out.add("붙은 모델(디스플레이 id): " + attachments.displays(p) + " / 내 화면 표시: " + selfView(p) + " / 숨김(투명·사망·관전): " + concealed(p));
        if (packets != null) out.add("몸에 붙이기 패킷(서버 전체): 스폰 때 " + packets.linkedSpawns.get() + " / 탑승 패킷 보강 "
                + packets.extendedPassengers.get() + " / 재전송 " + packets.resent.get());
        out.add("보유(직접 지급): " + (owned(p).isEmpty() ? "없음" : String.join(", ", owned(p))));
        out.add("OP 전체 보유 설정(cosmetics.op-owns-all): " + plugin.getConfig().getBoolean("cosmetics.op-owns-all")
                + " / 권한: " + p.hasPermission("stsv.cosmetic.*"));
        out.add("보는 사람 수: " + p.getTrackedBy().size());
        out.add("패킷 처리: " + (packets == null ? "사용 불가 (다른 사람에게만 보임)" : packets.installed(p) ? "적용됨" : "미적용")
                + " / 리소스팩 적용: " + packViews.containsKey(p.getUniqueId())
                + " / 본인 화면 표시: " + selfShown.contains(p.getUniqueId()));
        out.add("치장 화면 안: " + inWardrobe(p));
        if (plugin.gacha() != null) out.add(plugin.gacha().info(p.getUniqueId()));
        return out;
    }
}
