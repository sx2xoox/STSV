package dev.stsv.economy;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players cannot hurt each other unless config.yml pvp.always is on. Not by hand, arrows, tridents or fireworks, not
 * through their wolves, not with splash or lingering potions, and not with what blows up or burns without a name on
 * it: an end crystal (counted for whoever hit it), TNT (lit by a player, by redstone or a dispenser), a TNT minecart,
 * a bed or respawn anchor blown up where it does not work (only the player who used it is hurt). Lava and fire are
 * not poured or lit right next to another player, by hand or by a dispenser. A fishing rod does not pull players.
 */
final class PvpRule implements Listener {

    /** Effects that hurt or hinder (in a holder: the effect registry is not touched until a potion is thrown). */
    private static final class Harmful {
        static final Set<PotionEffectType> SET = Set.of(PotionEffectType.INSTANT_DAMAGE, PotionEffectType.POISON,
                    PotionEffectType.WEAKNESS, PotionEffectType.SLOWNESS, PotionEffectType.WITHER, PotionEffectType.BLINDNESS,
                    PotionEffectType.NAUSEA, PotionEffectType.MINING_FATIGUE, PotionEffectType.HUNGER, PotionEffectType.LEVITATION,
                    PotionEffectType.DARKNESS, PotionEffectType.INFESTED, PotionEffectType.OOZING, PotionEffectType.WEAVING,
                    PotionEffectType.WIND_CHARGED);
    }

    /** Lava and fire are not put this close (blocks, centre to centre) to another player. */
    static final double HAZARD_RADIUS = 3.5;

    private final EcoPlugin eco;
    /** Beds and respawn anchors a player just used where they blow up: who, where, when (the blast has no name on it). */
    private final Map<UUID, Use> respawnBlockUse = new ConcurrentHashMap<>();

    private record Use(Location at, long when) {}

    PvpRule(EcoPlugin eco) {
        this.eco = eco;
    }

    private boolean allowed() {
        return eco.getConfig().getBoolean("pvp.always");
    }

    /** The player behind a hit: the attacker, the shooter, who lit the TNT, the owner of the wolf, who threw the cloud. */
    static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        if (damager instanceof AreaEffectCloud c && c.getSource() instanceof Player p) return p;
        if (damager instanceof Tameable t && t.isTamed() && t.getOwner() instanceof Player p) return p;
        return null;
    }

    /** Things that blow up with nobody's name on them when a player sets them off by redstone, a rail or a chain. */
    static boolean unnamedBlast(Entity damager) {
        return damager instanceof TNTPrimed || damager instanceof ExplosiveMinecart || damager instanceof EnderCrystal;
    }

    private void told(Player by) {
        if (by != null) HudModule.flash(by, Lang.mm("pvp.disabled"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || allowed()) return;
        Player by = attacker(e.getDamager());
        if (by == null && e.getDamageSource().getCausingEntity() instanceof Player p) by = p;   // a crystal hit by a player, ...
        if (by != null) {
            if (by.equals(victim)) return;
            e.setCancelled(true);
            if (e.getDamager() instanceof Projectile pr) pr.remove();
            told(by);
            return;
        }
        // TNT by redstone or a dispenser, a TNT minecart, a crystal set off by something else: nobody's, so nobody is hurt
        if (unnamedBlast(e.getDamager())) e.setCancelled(true);
    }

    /** A bed or respawn anchor blown up (in the nether / end, an anchor outside the nether): only its user is hurt. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBlast(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim) || allowed()) return;
        if (e.getDamageSource().getDamageType() != DamageType.BAD_RESPAWN_POINT) return;
        long now = System.currentTimeMillis();
        respawnBlockUse.values().removeIf(u -> now - u.when() > 3000);
        for (Map.Entry<UUID, Use> u : respawnBlockUse.entrySet()) {
            Location at = u.getValue().at();
            if (u.getKey().equals(victim.getUniqueId()) && at.getWorld().equals(victim.getWorld()) && at.distanceSquared(victim.getLocation()) < 400) return;
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseRespawnBlock(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || b == null) return;
        if (!b.getType().name().endsWith("_BED") && b.getType() != Material.RESPAWN_ANCHOR) return;
        respawnBlockUse.put(e.getPlayer().getUniqueId(), new Use(b.getLocation().add(0.5, 0.5, 0.5), System.currentTimeMillis()));
    }

    /** Flame arrows, fire aspect: no burning either. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || allowed()) return;
        Player by = attacker(e.getCombuster());
        if (by != null && !by.equals(victim)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player by) || allowed()) return;
        if (e.getPotion().getEffects().stream().noneMatch(fx -> Harmful.SET.contains(fx.getType()))) return;
        for (var hit : e.getAffectedEntities()) if (hit instanceof Player victim && !victim.equals(by)) e.setIntensity(victim, 0);
    }

    /** Lingering potions: the cloud passes over other players (the thrower's own cloud still works on the thrower). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent e) {
        if (allowed()) return;
        AreaEffectCloud cloud = e.getEntity();
        if (!(cloud.getSource() instanceof Player by)) return;
        List<PotionEffect> effects = new ArrayList<>(cloud.getCustomEffects());
        if (cloud.getBasePotionType() != null) effects.addAll(cloud.getBasePotionType().getPotionEffects());
        if (effects.stream().noneMatch(fx -> Harmful.SET.contains(fx.getType()))) return;
        e.getAffectedEntities().removeIf(le -> le instanceof Player victim && !victim.equals(by));
    }

    /** No pulling another player with a fishing rod (into lava, off a cliff). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (allowed() || e.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) return;
        if (e.getCaught() instanceof Player victim && !victim.equals(e.getPlayer())) {
            e.setCancelled(true);
            told(e.getPlayer());
        }
    }

    /** Another player (not the one placing, not in creative or spectator) this close to where lava or fire would go? */
    private static boolean someoneNear(Location at, Player except) {
        double r2 = HAZARD_RADIUS * HAZARD_RADIUS;
        for (Player p : at.getWorld().getPlayers()) {
            if (p.equals(except) || p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (near(p.getLocation(), at, r2)) return true;
        }
        return false;
    }

    /** The player's feet or head within r² of the block's centre. */
    static boolean near(Location player, Location blockCorner, double r2) {
        double cx = blockCorner.getBlockX() + 0.5, cy = blockCorner.getBlockY() + 0.5, cz = blockCorner.getBlockZ() + 0.5;
        double dx = player.getX() - cx, dz = player.getZ() - cz;
        double feet = player.getY() - cy, head = player.getY() + 1.6 - cy;
        return dx * dx + dz * dz + Math.min(feet * feet, head * head) <= r2;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPourLava(PlayerBucketEmptyEvent e) {
        if (allowed() || e.getBucket() != Material.LAVA_BUCKET) return;
        if (!someoneNear(e.getBlock().getLocation(), e.getPlayer())) return;
        e.setCancelled(true);
        HudModule.flash(e.getPlayer(), Lang.mm("pvp.hazard"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (allowed()) return;
        Player by = e.getPlayer();
        if (by == null && e.getIgnitingEntity() != null) by = attacker(e.getIgnitingEntity());
        if (by == null || !someoneNear(e.getBlock().getLocation(), by)) return;
        e.setCancelled(true);
        HudModule.flash(by, Lang.mm("pvp.hazard"));
    }

    /** A dispenser does not pour lava or light fire next to a player. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (allowed()) return;
        Material m = e.getItem().getType();
        if (m != Material.LAVA_BUCKET && m != Material.FLINT_AND_STEEL && m != Material.FIRE_CHARGE) return;
        if (!(e.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional d)) return;
        if (someoneNear(e.getBlock().getRelative(d.getFacing()).getLocation(), null)) e.setCancelled(true);
    }
}
