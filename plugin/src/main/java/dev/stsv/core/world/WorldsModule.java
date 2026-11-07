package dev.stsv.core.world;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * The 건축월드 (build world: land and its protection, see the economy's LandModule) and the 야생월드
 * (wild world: free, a resource world the operator regenerates with /관리자 야생초기화). Inventories are
 * shared. /건축월드 and /야생월드 travel after a short stand-still warm-up: to the player's last spot in the
 * build world (kept on the player) or its spawn; to a random safe spot of the wild world (a ring around its
 * spawn, a cooldown between trips so it cannot be used to scout). Dying in the wild world without a bed or
 * anchor brings you back to the build world's spawn.
 */
public final class WorldsModule implements StsvModule, Listener {

    public static final String ADMIN_OWNER = "STSV 월드";

    private STSVPlugin plugin;
    private WorldRules rules = WorldRules.parse(null);
    private final Map<UUID, BukkitTask> pending = new ConcurrentHashMap<>();
    private final Map<UUID, WorldRules.Kind> diedIn = new ConcurrentHashMap<>();
    private final Map<String, Long> confirm = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWildTrip = new ConcurrentHashMap<>();
    private volatile boolean resetting;

    @Override
    public String id() {
        return "worlds";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.admin().add(ADMIN_OWNER, "야생초기화", "[확인]", "야생월드를 새 시드로 다시 만듦 (안의 플레이어는 건축월드로)", this::resetCommand,
                (s, a) -> a.length == 1 ? List.of("확인") : List.of());
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    public void disable() {
        pending.values().forEach(BukkitTask::cancel);
        pending.clear();
        plugin.admin().removeAll(ADMIN_OWNER);
        HandlerList.unregisterAll(this);
    }

    private void load() {
        rules = WorldRules.parse(plugin.getConfig().getConfigurationSection("worlds"));
        if (Bukkit.getWorld(rules.buildName(main())) == null) {
            plugin.getLogger().warning("[월드] 건축월드 '" + rules.build() + "' 가 없습니다. 기본 월드(" + main() + ")를 건축월드로 씁니다.");
            rules = new WorldRules("", rules.wild(), rules.warmup(), rules.radius(), rules.minDistance(), rules.tries(), rules.cooldown());
        }
        if (rules.hasWild(main()) && Bukkit.getWorld(rules.wild()) == null) createWild(new Random().nextLong());
    }

    private static String main() {
        return Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().getFirst().getName();
    }

    private World createWild(long seed) {
        plugin.getLogger().info("[월드] 야생월드 '" + rules.wild() + "' 를 불러옵니다 (없으면 새로 만듦)");
        World w = Bukkit.createWorld(new WorldCreator(rules.wild()).environment(World.Environment.NORMAL).seed(seed));
        if (w == null) plugin.getLogger().warning("[월드] 야생월드를 만들지 못했습니다: " + rules.wild());
        return w;
    }

    // ------------------------------------------------------------------------------------------

    public WorldRules rules() {
        return rules;
    }

    public WorldRules.Kind kind(World w) {
        return w == null ? WorldRules.Kind.OTHER : rules.kind(w.getName(), main());
    }

    public boolean isBuild(World w) {
        return kind(w) == WorldRules.Kind.BUILD;
    }

    public boolean isWild(World w) {
        return kind(w) == WorldRules.Kind.WILD;
    }

    public World buildWorld() {
        World w = Bukkit.getWorld(rules.buildName(main()));
        return w == null ? Bukkit.getWorlds().getFirst() : w;
    }

    /** null while there is none (unset, or being regenerated). */
    public World wildWorld() {
        return resetting || !rules.hasWild(main()) ? null : Bukkit.getWorld(rules.wild());
    }

    /** The Korean name of the world a player is in (건축월드, 야생월드 or the world's own name). */
    public String displayName(World w) {
        return switch (kind(w)) {
            case BUILD -> Lang.raw("worlds.build");
            case WILD -> Lang.raw("worlds.wild");
            case OTHER -> w == null ? "-" : switch (w.getEnvironment()) {
                case NETHER -> Lang.raw("worlds.nether");
                case THE_END -> Lang.raw("worlds.end");
                default -> w.getName();
            };
        };
    }

    // ------------------------------------------------------------------------------------------
    // Travel: /건축월드, /야생월드
    // ------------------------------------------------------------------------------------------

    public void travel(Player p, WorldRules.Kind to) {
        boolean wild = to == WorldRules.Kind.WILD;
        World target = wild ? wildWorld() : buildWorld();
        String name = Lang.raw(wild ? "worlds.wild" : "worlds.build");
        if (target == null) {
            p.sendMessage(Lang.msg(resetting ? "worlds.resetting" : "worlds.no-wild"));
            return;
        }
        if (p.getWorld().equals(target)) {
            p.sendMessage(Lang.msg("worlds.already", "world", name));
            return;
        }
        if (plugin.cosmetics() != null && plugin.cosmetics().inWardrobe(p)) {
            p.sendMessage(Lang.msg("worlds.in-wardrobe"));
            return;
        }
        boolean admin = p.hasPermission("stsv.admin");
        long wait = wild && !admin ? cooldownLeft(p) : 0;
        if (wait > 0) {
            p.sendMessage(Lang.msg("worlds.cooldown", "seconds", (wait + 999) / 1000));
            return;
        }
        cancel(p, null);
        int seconds = admin ? 0 : rules.warmup();
        if (seconds <= 0) {
            go(p, to);
            return;
        }
        Location start = p.getLocation();
        p.sendMessage(Lang.msg(wild ? "worlds.warmup-wild" : "worlds.warmup", "world", name, "seconds", seconds));
        int[] left = {seconds * 4};
        pending.put(p.getUniqueId(), Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                cancel(p, null);
                return;
            }
            Location now = p.getLocation();
            if (!now.getWorld().equals(start.getWorld()) || now.distanceSquared(start) > 0.36) {
                cancel(p, "worlds.moved");
                return;
            }
            if (left[0] % 4 == 0 && left[0] > 0) HudModule.flash(p, Lang.mm("worlds.countdown", "world", name, "seconds", left[0] / 4));
            if (left[0]-- <= 0) {
                BukkitTask t = pending.remove(p.getUniqueId());
                if (t != null) t.cancel();
                go(p, to);
            }
        }, 0L, 5L));
    }

    private void cancel(Player p, String why) {
        BukkitTask t = pending.remove(p.getUniqueId());
        if (t == null) return;
        t.cancel();
        if (why != null) p.sendMessage(Lang.msg(why));
    }

    private long cooldownLeft(Player p) {
        Long last = lastWildTrip.get(p.getUniqueId());
        return last == null ? 0 : last + rules.cooldown() * 1000L - System.currentTimeMillis();
    }

    private void go(Player p, WorldRules.Kind to) {
        if (to == WorldRules.Kind.WILD) {
            World wild = wildWorld();
            if (wild == null) {
                p.sendMessage(Lang.msg(resetting ? "worlds.resetting" : "worlds.no-wild"));
                return;
            }
            HudModule.flash(p, Lang.mm("worlds.searching"));
            findSpot(wild, rules.tries()).thenAccept(spot -> {
                if (!p.isOnline()) return;
                if (spot == null) {
                    p.sendMessage(Lang.msg("worlds.no-spot"));
                    return;
                }
                lastWildTrip.put(p.getUniqueId(), System.currentTimeMillis());
                arrive(p, spot);
            });
            return;
        }
        World build = buildWorld();
        Location dest = lastSpot(p, build);
        arrive(p, dest != null ? dest : build.getSpawnLocation().add(0.5, 0, 0.5));
    }

    private void arrive(Player p, Location dest) {
        p.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
            if (ok) {
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
                HudModule.flash(p, Lang.mm("worlds.arrived", "world", displayName(dest.getWorld())));
            }
        });
    }

    /**
     * A random safe spot in the wild world (see {@link SafeSpot}): a column between min-distance and radius from
     * its spawn, inside the world border; chunks are loaded asynchronously, the blocks read on the main thread.
     * Completes with null after the configured number of tries.
     */
    private CompletableFuture<Location> findSpot(World w, int triesLeft) {
        if (triesLeft <= 0 || resetting || !w.equals(wildWorld())) return CompletableFuture.completedFuture(null);
        Location spawn = w.getSpawnLocation();
        int[] xz = rules.randomColumn(ThreadLocalRandom.current(), spawn.getBlockX(), spawn.getBlockZ());
        if (!w.getWorldBorder().isInside(new Location(w, xz[0] + 0.5, spawn.getY(), xz[1] + 0.5))) return findSpot(w, triesLeft - 1);
        return w.getChunkAtAsync(xz[0] >> 4, xz[1] >> 4).thenCompose(chunk -> {
            Block ground = w.getHighestBlockAt(xz[0], xz[1], HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block feet = ground.getRelative(0, 1, 0), head = ground.getRelative(0, 2, 0);
            if (SafeSpot.safe(ground.getType(), ground.getType().isSolid(), feet.getType(), head.getType(), ground.getY(), w.getMinHeight(), w.getMaxHeight())) {
                return CompletableFuture.completedFuture(new Location(w, xz[0] + 0.5, ground.getY() + 1, xz[1] + 0.5,
                        ThreadLocalRandom.current().nextFloat() * 360 - 180, 0));
            }
            return findSpot(w, triesLeft - 1);
        });
    }

    // the last spot in the build world: "world-uid;x;y;z;yaw;pitch" (the wild world is always a random spot)
    private static final NamespacedKey LAST_BUILD = new NamespacedKey("stsv", "last_build");

    private void remember(Player p, Location at) {
        if (!isBuild(at.getWorld())) return;
        p.getPersistentDataContainer().set(LAST_BUILD, PersistentDataType.STRING, at.getWorld().getUID() + ";" + at.getX() + ";" + at.getY() + ";"
                + at.getZ() + ";" + at.getYaw() + ";" + at.getPitch());
    }

    private Location lastSpot(Player p, World w) {
        String s = p.getPersistentDataContainer().get(LAST_BUILD, PersistentDataType.STRING);
        if (s == null) return null;
        String[] a = s.split(";");
        try {
            if (a.length < 6 || !UUID.fromString(a[0]).equals(w.getUID())) return null;
            return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                    Float.parseFloat(a[4]), Float.parseFloat(a[5]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo().getWorld() != null && !e.getTo().getWorld().equals(e.getFrom().getWorld())) remember(e.getPlayer(), e.getFrom());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer(), null);
        remember(e.getPlayer(), e.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) cancel(p, "worlds.hurt");
    }

    // ------------------------------------------------------------------------------------------
    // Respawn: died in the wild world without a bed or anchor -> the build world's spawn
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        cancel(e.getPlayer(), null);
        diedIn.put(e.getPlayer().getUniqueId(), kind(e.getPlayer().getWorld()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        WorldRules.Kind where = diedIn.remove(e.getPlayer().getUniqueId());
        if (where != WorldRules.Kind.WILD || e.isBedSpawn() || e.isAnchorSpawn()) return;
        e.setRespawnLocation(buildWorld().getSpawnLocation().add(0.5, 0, 0.5));
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 야생초기화 [확인]
    // ------------------------------------------------------------------------------------------

    private void resetCommand(CommandSender sender, String[] args) {
        World wild = wildWorld();
        if (wild == null) {
            sender.sendMessage(Lang.msg(resetting ? "worlds.resetting" : "worlds.no-wild"));
            return;
        }
        String who = sender.getName();
        Long asked = confirm.get(who);
        if (args.length == 0 || !args[0].equals("확인") || asked == null || System.currentTimeMillis() - asked > 30_000) {
            confirm.put(who, System.currentTimeMillis());
            sender.sendMessage(Lang.msg("worlds.reset-confirm", "world", wild.getName(), "players", wild.getPlayers().size()));
            return;
        }
        confirm.remove(who);
        Path folder = wild.getWorldPath().toAbsolutePath().normalize();
        Path build = buildWorld().getWorldPath().toAbsolutePath().normalize();
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        if (build.startsWith(folder) || container.startsWith(folder) || folder.equals(build)) {
            sender.sendMessage(Lang.msg("worlds.reset-unsafe", "path", folder));
            return;
        }
        resetting = true;
        for (BukkitTask t : pending.values()) t.cancel();
        pending.clear();
        Location safe = buildWorld().getSpawnLocation().add(0.5, 0, 0.5);
        for (Player p : wild.getPlayers()) {
            p.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
            p.sendMessage(Lang.msg("worlds.reset-moved"));
        }
        if (!Bukkit.unloadWorld(wild, false)) {
            resetting = false;
            sender.sendMessage(Lang.msg("worlds.reset-failed"));
            return;
        }
        sender.sendMessage(Lang.msg("worlds.reset-started", "world", rules.wild()));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean deleted = delete(folder);
            Bukkit.getScheduler().runTask(plugin, () -> {
                World w = createWild(new Random().nextLong());
                resetting = false;
                if (w == null || !deleted) sender.sendMessage(Lang.msg("worlds.reset-failed"));
                else Bukkit.broadcast(Lang.msg("worlds.reset-done", "world", Lang.raw("worlds.wild")));
            });
        });
    }

    private boolean delete(Path folder) {
        if (!Files.exists(folder)) return true;
        try (Stream<Path> s = Files.walk(folder)) {
            for (Path f : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[월드] 야생월드 폴더를 지우지 못했습니다: " + e.getMessage());
            return false;
        }
    }
}
