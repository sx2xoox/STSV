package dev.stsv.rpg.player;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.rpg.RpgPlugin;
import dev.stsv.rpg.balance.Balance;
import dev.stsv.rpg.stat.Stat;
import dev.stsv.rpg.stat.StatBlock;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Levels, experience, stats, health and stamina of players.
 *
 * Health: the vanilla health bar (20) stays the source of truth and the RPG health is its share of
 * the RPG maximum, so anything vanilla does to health (falling, fire, food regeneration, potions)
 * keeps working and scales with the RPG maximum by itself. Only mob and player attacks are replaced
 * (CombatModule). Experience: vanilla experience is switched off and the experience bar shows the RPG
 * level and progress instead.
 */
public final class PlayerModule implements StsvModule, Listener {

    /** Where an item keeps its RPG stats (written by the gear system). */
    public static final NamespacedKey ITEM_STATS = new NamespacedKey("stsv", "rpg_stats");
    private static final NamespacedKey NO_COOLDOWN = new NamespacedKey("stsv", "rpg_no_attack_cooldown");

    private final RpgPlugin rpg;
    private PlayerStore store;
    private final Map<UUID, long[]> preloaded = new ConcurrentHashMap<>();
    private final Map<UUID, RpgPlayer> online = new ConcurrentHashMap<>();
    private BukkitTask fast, slow;

    public PlayerModule(RpgPlugin rpg) {
        this.rpg = rpg;
    }

    @Override
    public String id() {
        return "rpg-players";
    }

    @Override
    public void enable(STSVPlugin core) {
        store = new PlayerStore(core.db());
        store.init();
        Bukkit.getPluginManager().registerEvents(this, rpg);
        for (Player p : Bukkit.getOnlinePlayers()) join(p, store.load(p.getUniqueId()));
        fast = Bukkit.getScheduler().runTaskTimer(rpg, this::tickFast, 5L, 5L);
        slow = Bukkit.getScheduler().runTaskTimer(rpg, this::tickSlow, 40L, 40L);
        core.hud().line("rpg", rpg.getConfig().getInt("hud.order"), this::hudLine);
    }

    @Override
    public void reload() {
        rpg.reloadSettings();
        STSVPlugin.get().hud().line("rpg", rpg.getConfig().getInt("hud.order"), this::hudLine);
        online.values().forEach(RpgPlayer::markStatsDirty);
    }

    @Override
    public void disable() {
        if (fast != null) fast.cancel();
        if (slow != null) slow.cancel();
        STSVPlugin core = STSVPlugin.get();
        if (core != null && core.hud() != null) core.hud().removeLine("rpg");
        for (Player p : Bukkit.getOnlinePlayers()) {
            removeNoCooldown(p);
            p.sendExperienceChange(p.getExp(), p.getLevel()); // the real (vanilla) bar back
        }
        online.clear();
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------------------------------
    // Join / quit
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        long[] row = store.load(e.getUniqueId());
        preloaded.put(e.getUniqueId(), row == null ? new long[]{1, 0} : row);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        long[] row = preloaded.remove(e.getPlayer().getUniqueId());
        join(e.getPlayer(), row != null ? row : store.load(e.getPlayer().getUniqueId()));
    }

    private void join(Player p, long[] row) {
        int level = row == null ? 1 : (int) Math.max(1, Math.min(balance().levelCap, row[0]));
        RpgPlayer rp = new RpgPlayer(p.getUniqueId(), level, row == null ? 0 : row[1]);
        online.put(p.getUniqueId(), rp);
        recompute(p, rp);
        rp.stamina = rp.stats.get(Stat.MAX_STAMINA);
        applyNoCooldown(p);
        Bukkit.getScheduler().runTask(rpg, () -> showExpBar(p));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        online.remove(e.getPlayer().getUniqueId());
        preloaded.remove(e.getPlayer().getUniqueId());
    }

    /** The RPG state of an online player (never null for an online player once joined). */
    public RpgPlayer get(Player p) {
        RpgPlayer rp = online.get(p.getUniqueId());
        if (rp == null) { // joined before the plugin enabled and missed: load now
            join(p, store.load(p.getUniqueId()));
            rp = online.get(p.getUniqueId());
        }
        return rp;
    }

    private Balance balance() {
        return rpg.balance();
    }

    // ------------------------------------------------------------------------------------------
    // Stats
    // ------------------------------------------------------------------------------------------

    /** Base stats of a level, before equipment. */
    public StatBlock baseStats(int level) {
        Balance b = balance();
        return new StatBlock()
                .set(Stat.ATTACK, b.attack(level))
                .set(Stat.MAX_HEALTH, b.health(level))
                .set(Stat.CRIT_CHANCE, b.baseCritChance)
                .set(Stat.CRIT_DAMAGE, b.baseCritDamage)
                .set(Stat.MAX_STAMINA, b.baseStamina)
                .set(Stat.STAMINA_REGEN, b.baseStaminaRegen);
    }

    /** Stats stored on an item (empty for ordinary items). */
    public static StatBlock itemStats(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return new StatBlock();
        String s = item.getPersistentDataContainer().get(ITEM_STATS, PersistentDataType.STRING);
        return StatBlock.parse(s);
    }

    private void recompute(Player p, RpgPlayer rp) {
        StatBlock total = baseStats(rp.level);
        var inv = p.getInventory();
        total.add(itemStats(inv.getItemInMainHand()));
        total.add(itemStats(inv.getItemInOffHand()));
        for (ItemStack armor : inv.getArmorContents()) total.add(itemStats(armor));
        rp.stats = total;
        rp.statsDirty = false;
        rp.stamina = Math.min(rp.stamina, total.get(Stat.MAX_STAMINA));
    }

    /** Recomputes on the next fast tick (equipment may still be moving this tick). */
    private void dirty(Player p) {
        RpgPlayer rp = online.get(p.getUniqueId());
        if (rp != null) rp.statsDirty = true;
    }

    @EventHandler public void onArmor(PlayerArmorChangeEvent e) { dirty(e.getPlayer()); }
    @EventHandler public void onHeld(PlayerItemHeldEvent e) { dirty(e.getPlayer()); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { dirty(e.getPlayer()); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { dirty(e.getPlayer()); }
    @EventHandler public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p) dirty(p); }
    @EventHandler public void onClose(InventoryCloseEvent e) { if (e.getPlayer() instanceof Player p) dirty(p); }

    // ------------------------------------------------------------------------------------------
    // Health and stamina
    // ------------------------------------------------------------------------------------------

    public double maxHealth(Player p) {
        return get(p).stats.get(Stat.MAX_HEALTH);
    }

    private static double vanillaMax(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    /** Current RPG health: the vanilla health's share of the RPG maximum. */
    public double health(Player p) {
        return p.getHealth() / vanillaMax(p) * maxHealth(p);
    }

    /**
     * Sets RPG health. Returns false (and does nothing) for 0 or less: the caller kills the player its
     * own way so the death has the right cause.
     */
    public boolean setHealth(Player p, double hp) {
        if (hp <= 0) return false;
        double max = maxHealth(p);
        p.setHealth(Math.max(0.0001, Math.min(vanillaMax(p), hp / max * vanillaMax(p))));
        return true;
    }

    /** Spends stamina; false (nothing spent) if there is not enough. */
    public boolean useStamina(Player p, double amount) {
        RpgPlayer rp = get(p);
        if (rp.stamina < amount) return false;
        rp.stamina -= amount;
        return true;
    }

    private void tickFast() {
        double seconds = 5 / 20.0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            RpgPlayer rp = online.get(p.getUniqueId());
            if (rp == null) continue;
            if (rp.statsDirty) recompute(p, rp);
            StatBlock s = rp.stats;
            rp.stamina = Math.min(s.get(Stat.MAX_STAMINA), rp.stamina + s.get(Stat.STAMINA_REGEN) * seconds);
            double regen = s.get(Stat.HEALTH_REGEN);
            if (regen > 0 && !p.isDead() && p.getHealth() > 0) setHealth(p, Math.min(maxHealth(p), health(p) + regen * seconds));
        }
    }

    private void tickSlow() {
        // vanilla resends the real experience bar now and then (respawn, world change, commands)
        for (Player p : Bukkit.getOnlinePlayers()) showExpBar(p);
    }

    private net.kyori.adventure.text.Component hudLine(Player p) {
        RpgPlayer rp = online.get(p.getUniqueId());
        if (rp == null) return null;
        return Lang.mm("rpg.hud",
                "hp", fmt(Math.ceil(health(p))), "max", fmt(maxHealth(p)),
                "sp", fmt(Math.floor(rp.stamina)), "maxsp", fmt(rp.stats.get(Stat.MAX_STAMINA)));
    }

    public static String fmt(double v) {
        return String.format(Locale.ROOT, "%,d", Math.round(v));
    }

    // ------------------------------------------------------------------------------------------
    // Vanilla attack cooldown: hidden (attack timing is ours), without touching saved player data
    // ------------------------------------------------------------------------------------------

    private void applyNoCooldown(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.ATTACK_SPEED);
        if (a == null || a.getModifier(NO_COOLDOWN) != null) return;
        a.addTransientModifier(new AttributeModifier(NO_COOLDOWN, 1000, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
    }

    private void removeNoCooldown(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.ATTACK_SPEED);
        if (a != null) a.removeModifier(NO_COOLDOWN);
    }

    // ------------------------------------------------------------------------------------------
    // Experience
    // ------------------------------------------------------------------------------------------

    /** Shows the RPG level and progress on the experience bar (client-side only). */
    public void showExpBar(Player p) {
        RpgPlayer rp = online.get(p.getUniqueId());
        if (rp == null) return;
        long need = balance().expToNext(rp.level);
        float progress = need == Long.MAX_VALUE ? 1f : (float) Math.max(0, Math.min(0.999, (double) rp.exp / need));
        p.sendExperienceChange(progress, rp.level);
    }

    /** Vanilla experience is off: orbs, smelting, breeding and fishing give nothing. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVanillaExp(PlayerExpChangeEvent e) {
        e.setAmount(0);
        Bukkit.getScheduler().runTask(rpg, () -> showExpBar(e.getPlayer()));
    }

    public void giveExp(Player p, long amount) {
        if (amount <= 0) return;
        RpgPlayer rp = get(p);
        Balance b = balance();
        if (rp.level >= b.levelCap) return;
        rp.exp += amount;
        int before = rp.level;
        while (rp.level < b.levelCap && rp.exp >= b.expToNext(rp.level)) {
            rp.exp -= b.expToNext(rp.level);
            rp.level++;
        }
        if (rp.level >= b.levelCap) rp.exp = 0;
        store.save(rp.id, rp.level, rp.exp);
        if (rp.level > before) levelUp(p, rp);
        showExpBar(p);
    }

    public void setLevel(Player p, int level) {
        RpgPlayer rp = get(p);
        rp.level = Math.max(1, Math.min(balance().levelCap, level));
        rp.exp = 0;
        store.save(rp.id, rp.level, rp.exp);
        levelUp(p, rp);
        showExpBar(p);
    }

    private void levelUp(Player p, RpgPlayer rp) {
        recompute(p, rp);
        setHealth(p, maxHealth(p));
        rp.stamina = rp.stats.get(Stat.MAX_STAMINA);
        p.showTitle(Title.title(Lang.mm("rpg.level-up-title"), Lang.mm("rpg.level-up-subtitle", "level", rp.level),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(500))));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.0f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.25);
        p.sendMessage(Lang.msg("rpg.level-up", "level", rp.level, "hp", fmt(maxHealth(p)), "atk", fmt(rp.stats.get(Stat.ATTACK))));
    }

    // ------------------------------------------------------------------------------------------
    // Death and respawn (plan: items kept, 5% of the level's experience lost)
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        RpgPlayer rp = get(p);
        long need = balance().expToNext(rp.level);
        if (need != Long.MAX_VALUE && rp.exp > 0) {
            rp.exp = Math.max(0, rp.exp - Math.round(need * rpg.getConfig().getDouble("death.exp-loss")));
            store.save(rp.id, rp.level, rp.exp);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        RpgPlayer rp = get(e.getPlayer());
        rp.stamina = rp.stats.get(Stat.MAX_STAMINA);
        Bukkit.getScheduler().runTask(rpg, () -> {
            applyNoCooldown(e.getPlayer());
            showExpBar(e.getPlayer());
        });
    }
}
