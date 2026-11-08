package dev.stsv.core.world;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 엘리베이터 (config.yml "elevator"): standing on an elevator block (default the bone block), jumping takes the player
 * up to the next elevator block above in the same column, sneaking down to the next one below, within
 * elevator.max-distance blocks ({@link ElevatorShaft}: targets whose two blocks above are not free are skipped). The
 * player keeps where they look and where they stood inside the block, lands with no speed and no fall damage. Every
 * world; not while flying, gliding, riding or spectating. A short cooldown so holding jump does not chain rides.
 * Nothing is shown when there is no target (jumping on a bone block is also just jumping).
 */
public final class ElevatorModule implements StsvModule, Listener {

    /** Passable blocks a player must not be put into. */
    private static final Set<Material> HARMFUL = EnumSet.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
            Material.POWDER_SNOW, Material.COBWEB, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY);

    private STSVPlugin plugin;
    private boolean enabled;
    private Material block;
    private int maxDistance;
    private long cooldownMs;
    private final Map<UUID, Long> lastRide = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "elevator";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        lastRide.clear();
    }

    private void load() {
        String name = plugin.getConfig().getString("elevator.block");
        block = name == null ? null : Material.matchMaterial(name.trim());
        enabled = plugin.getConfig().getBoolean("elevator.enabled");
        if (enabled && (block == null || !block.isBlock() || !block.isSolid())) {
            plugin.getLogger().warning("[엘리베이터] elevator.block '" + name + "' 은(는) 올라설 수 있는 블록이 아닙니다. 엘리베이터를 끕니다");
            enabled = false;
        }
        maxDistance = Math.clamp(plugin.getConfig().getInt("elevator.max-distance"), 2, 384);
        cooldownMs = Math.max(0, Math.round(plugin.getConfig().getDouble("elevator.cooldown-seconds") * 1000));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent e) {
        Player p = e.getPlayer();
        Location target = target(p, e.getFrom(), true);
        // after this move is handled (the jump has started): the teleport then replaces it cleanly
        if (target != null) Bukkit.getScheduler().runTask(plugin, () -> ride(p, target));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Location target = target(e.getPlayer(), e.getPlayer().getLocation(), false);
        if (target != null) ride(e.getPlayer(), target);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastRide.remove(e.getPlayer().getUniqueId());
    }

    /** Where the ride goes (feet on top of the next elevator block), or null: no ride now. Starts the cooldown. */
    private Location target(Player p, Location from, boolean up) {
        if (!enabled || !canRide(p) || from.getWorld() == null) return null;
        Block under = standingOn(from);
        if (under == null) return null;
        long now = System.currentTimeMillis();
        Long last = lastRide.get(p.getUniqueId());
        if (last != null && now - last < cooldownMs) return null;
        World w = from.getWorld();
        int x = under.getX(), z = under.getZ();
        OptionalInt y = ElevatorShaft.next(under.getY(), up, maxDistance, w.getMinHeight(), w.getMaxHeight(),
                yy -> w.getBlockAt(x, yy, z).getType() == block, yy -> free(w.getBlockAt(x, yy, z)));
        if (y.isEmpty()) return null;
        lastRide.put(p.getUniqueId(), now);
        return new Location(w, ElevatorShaft.inside(from.getX(), x), y.getAsInt() + 1, ElevatorShaft.inside(from.getZ(), z));
    }

    private void ride(Player p, Location target) {
        if (!p.isOnline() || !canRide(p) || !target.getWorld().equals(p.getWorld())) return;
        Location now = p.getLocation();
        Location to = target.clone();
        to.setYaw(now.getYaw());
        to.setPitch(now.getPitch());
        Location from = now.clone();
        if (!p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN)) return;
        p.setVelocity(new Vector(0, 0, 0));
        p.setFallDistance(0);
        World w = to.getWorld();
        w.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.35f, 1.6f);
        w.spawnParticle(Particle.PORTAL, from.add(0, 1, 0), 12, 0.25, 0.5, 0.25, 0.1);
        w.spawnParticle(Particle.PORTAL, to.clone().add(0, 1, 0), 16, 0.25, 0.5, 0.25, 0.1);
    }

    private static boolean canRide(Player p) {
        return p.getGameMode() != GameMode.SPECTATOR && !p.isFlying() && !p.isGliding() && !p.isInsideVehicle()
                && !p.isDead() && p.hasPermission("stsv.elevator");
    }

    /** The elevator block right under the feet, or null (in the air, or another block). */
    private Block standingOn(Location feet) {
        int y = (int) Math.floor(feet.getY() - 0.05);   // feet more than 0.05 above the block: the feet's own block (not it)
        Block b = feet.getWorld().getBlockAt(feet.getBlockX(), y, feet.getBlockZ());
        return b.getType() == block ? b : null;
    }

    private static boolean free(Block b) {
        return b.isPassable() && !HARMFUL.contains(b.getType());
    }
}
