package dev.stsv.core.world;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.PortalType;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * The 건축월드 (build world: land and its protection, see the economy's LandModule) and the 야생월드
 * (wild world: free, a resource world the operator regenerates with /관리자 야생초기화) with its own 지옥 and
 * 엔드. Inventories are shared. /건축월드 and /야생월드 travel after a short stand-still warm-up: to the player's
 * last spot in the build world (kept on the player) or its spawn; to a random safe spot of the wild world (a ring
 * around its spawn, a cooldown between trips so it cannot be used to scout). Wild spots come ready from a pool
 * filled in the background while the server is healthy ({@link WildSpotFiller}), so a trip does not generate terrain.
 * <p>
 * Portals ({@link PortalLinks}): the wild world's nether portals lead to its nether and back, its end portals to
 * its end (a full vanilla end: dragon, gateways, cities) and out again to the wild world; the build world's
 * portals lead nowhere (and no nether portal is lit there). The server's default nether and end are left unused:
 * whoever logs in or respawns there is moved to the wild world. Dying anywhere in the wild set (wild world, its
 * nether, its end) without a bed or anchor respawns at the wild world's spawn. The worlds named by role in
 * worlds.keep-inventory (default: the wild nether and end) get the keepInventory game rule on start and whenever
 * they load; other worlds' rules are left alone. Without a wild world everything is vanilla.
 */
public final class WorldsModule implements StsvModule, Listener {

    public static final String ADMIN_OWNER = "STSV 월드";

    private STSVPlugin plugin;
    private WorldRules rules = WorldRules.parse(null);
    /** A running stand-still warm-up: its timer, and who to tell when it is cancelled (null = nobody). */
    private record Warmup(BukkitTask task, java.util.function.Consumer<String> onCancel) {}

    private final Map<UUID, Warmup> pending = new ConcurrentHashMap<>();
    private final Map<UUID, WorldRules.Kind> diedIn = new ConcurrentHashMap<>();
    private final Map<String, Long> confirm = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWildTrip = new ConcurrentHashMap<>();
    private final Map<UUID, Long> portalTold = new ConcurrentHashMap<>();
    private volatile boolean resetting;
    private WildSpotFiller spots;

    @Override
    public String id() {
        return "worlds";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        spots = new WildSpotFiller(plugin, this::wildWorld, () -> rules);
        load();
        spots.start();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.admin().add(ADMIN_OWNER, "야생초기화", "[확인]", "야생월드와 그 지옥·엔드를 새 시드로 다시 만듦 (안의 플레이어는 건축월드로)", this::resetCommand,
                (s, a) -> a.length == 1 ? List.of("확인") : List.of());
        plugin.admin().add(ADMIN_OWNER, "야생스팟", "[채우기 <개수>|비우기]", "/야생월드 의 준비된 자리(렉 방지) 상태 / 서버가 한가할 때 빠르게 더 채우기 / 모두 버리기",
                this::spotsCommand, (s, a) -> a.length == 1 ? List.of("채우기", "비우기") : a.length == 2 && a[0].equals("채우기") ? List.of("50", "100") : List.of());
        plugin.admin().report(ADMIN_OWNER, (s, a) -> s.sendMessage(spotsStatus()));
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    public void disable() {
        pending.values().forEach(w -> w.task().cancel());
        pending.clear();
        if (spots != null) spots.stop();
        plugin.admin().removeAll(ADMIN_OWNER);
        HandlerList.unregisterAll(this);
    }

    private void load() {
        var section = plugin.getConfig().getConfigurationSection("worlds");
        rules = WorldRules.parse(section);
        spots.settings(WildSpotRules.parse(section));
        if (section != null) for (String word : section.getStringList("keep-inventory")) {
            if (WorldRules.role(word) == null) plugin.getLogger().warning("[월드] worlds.keep-inventory 의 '" + word + "' 는 알 수 없는 월드입니다 (build, wild, nether, end)");
        }
        if (Bukkit.getWorld(rules.buildName(main())) == null) {
            plugin.getLogger().warning("[월드] 건축월드 '" + rules.build() + "' 가 없습니다. 기본 월드(" + main() + ")를 건축월드로 씁니다.");
            rules = rules.withBuild("");
        }
        if (rules.hasWild(main())) {
            World wild = Bukkit.getWorld(rules.wild());
            createWild(wild != null ? wild.getSeed() : new Random().nextLong());
        }
        for (World w : Bukkit.getWorlds()) keepInventory(w);
    }

    private static String main() {
        return Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().getFirst().getName();
    }

    /** The wild world's three worlds by name (overworld, nether, end); empty without a wild world. */
    private Map<String, World.Environment> wildSet() {
        if (!rules.hasWild(main())) return Map.of();
        Map<String, World.Environment> out = new LinkedHashMap<>();
        out.put(rules.wild(), World.Environment.NORMAL);
        out.put(rules.wildNetherName(main()), World.Environment.NETHER);
        out.put(rules.wildEndName(main()), World.Environment.THE_END);
        return out;
    }

    /** Loads (creates when missing) the wild world, its nether and its end, all with the same seed. false = one failed. */
    private boolean createWild(long seed) {
        boolean ok = true;
        for (var e : wildSet().entrySet()) {
            if (Bukkit.getWorld(e.getKey()) != null) continue;
            plugin.getLogger().info("[월드] 야생월드 '" + e.getKey() + "' (" + e.getValue() + ") 를 불러옵니다 (없으면 새로 만듦)");
            if (Bukkit.createWorld(new WorldCreator(e.getKey()).environment(e.getValue()).seed(seed)) == null) {
                plugin.getLogger().warning("[월드] 야생월드를 만들지 못했습니다: " + e.getKey());
                ok = false;
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------------------------------
    // keepInventory: on in the worlds whose role worlds.keep-inventory lists (default: the wild nether and end)
    // ------------------------------------------------------------------------------------------

    /** Turn keepInventory on in a world whose role is listed; other worlds are left alone. */
    private void keepInventory(World w) {
        if (w == null || !rules.keeps(kind(w)) || Boolean.TRUE.equals(w.getGameRuleValue(GameRules.KEEP_INVENTORY))) return;
        if (w.setGameRule(GameRules.KEEP_INVENTORY, true)) {
            plugin.getLogger().info("[월드] " + displayName(w) + " '" + w.getName() + "': 죽어도 아이템·경험치 유지 (keepInventory 켬)");
        } else plugin.getLogger().warning("[월드] '" + w.getName() + "' 에 keepInventory 를 켜지 못했습니다");
    }

    /** Dying here keeps items and XP? (the role is listed and the game rule is on) */
    public boolean keepsInventory(World w) {
        return w != null && rules.keeps(kind(w)) && Boolean.TRUE.equals(w.getGameRuleValue(GameRules.KEEP_INVENTORY));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent e) {
        keepInventory(e.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        World w = e.getPlayer().getWorld();
        if (keepsInventory(w)) e.getPlayer().sendMessage(Lang.msg("worlds.keep-inventory", "world", displayName(w)));
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

    /** The world a portal destination names; null while there is none (unset, not loaded, or being regenerated). */
    private World world(PortalLinks.Dest d) {
        if (resetting || !rules.hasWild(main())) return null;
        return switch (d) {
            case WILD -> Bukkit.getWorld(rules.wild());
            case NETHER -> Bukkit.getWorld(rules.wildNetherName(main()));
            case END -> Bukkit.getWorld(rules.wildEndName(main()));
            default -> null;
        };
    }

    /** The Korean name of the world a player is in (건축월드, 야생월드, 지옥, 엔드 or the world's own name). */
    public String displayName(World w) {
        return switch (kind(w)) {
            case BUILD -> Lang.raw("worlds.build");
            case WILD -> Lang.raw("worlds.wild");
            case NETHER -> Lang.raw("worlds.wild-nether");
            case END -> Lang.raw("worlds.wild-end");
            case OTHER -> w == null ? "-" : switch (w.getEnvironment()) {
                case NETHER -> Lang.raw("worlds.nether");
                case THE_END -> Lang.raw("worlds.end");
                default -> w.getName();
            };
        };
    }

    // ------------------------------------------------------------------------------------------
    // Portals (see PortalLinks): the wild world <-> its nether, the wild world -> its end -> out to the wild world;
    // nothing from the build world. Nether portals: Paper's EntityPortalReadyEvent picks the world, then Paper
    // scales the coordinates and finds or builds the portal there as vanilla does; the portal events only correct
    // a target that is still wrong. End portals: the target is moved to the wild end, after building its platform.
    // End gateways (inside an end) are left to vanilla.
    // ------------------------------------------------------------------------------------------

    private PortalLinks.Dest dest(World from, PortalLinks.Portal portal) {
        return PortalLinks.target(kind(from), portal, rules.hasWild(main()));
    }

    /** No travel: a player is told why on the action bar (at most every 3 seconds: portals fire every tick). */
    private void blocked(Entity who, PortalLinks.Dest d) {
        if (!(who instanceof Player p)) return;
        Long last = portalTold.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < 3000) return;
        portalTold.put(p.getUniqueId(), System.currentTimeMillis());
        HudModule.flash(p, Lang.mm(d == PortalLinks.Dest.BLOCKED ? "worlds.portal-build" : resetting ? "worlds.resetting" : "worlds.portal-unavailable"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalReady(EntityPortalReadyEvent e) {
        if (e.getPortalType() != PortalType.NETHER) return;
        PortalLinks.Dest d = dest(e.getEntity().getWorld(), PortalLinks.Portal.NETHER);
        if (d == PortalLinks.Dest.VANILLA) return;
        World to = world(d);
        if (to == null) {
            e.setCancelled(true);
            blocked(e.getEntity(), d);
            return;
        }
        e.setTargetWorld(to);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        PortalLinks.Portal portal = switch (e.getCause()) {
            case NETHER_PORTAL -> PortalLinks.Portal.NETHER;
            case END_PORTAL -> PortalLinks.Portal.END;
            default -> null;
        };
        portal(e.getPlayer(), e.getFrom(), e.getTo(), portal, e::setTo, () -> e.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        PortalLinks.Portal portal = switch (e.getPortalType()) {
            case NETHER -> PortalLinks.Portal.NETHER;
            case ENDER -> PortalLinks.Portal.END;
            default -> null;
        };
        portal(e.getEntity(), e.getFrom(), e.getTo(), portal, e::setTo, () -> e.setCancelled(true));
    }

    private void portal(Entity who, Location from, Location to, PortalLinks.Portal portal,
                        java.util.function.Consumer<Location> setTo, Runnable cancel) {
        if (portal == null || from.getWorld() == null) return;
        PortalLinks.Dest d = dest(from.getWorld(), portal);
        if (d == PortalLinks.Dest.VANILLA) return;
        World target = world(d);
        if (target == null) {
            cancel.run();
            blocked(who, d);
            return;
        }
        if (to != null && target.equals(to.getWorld())) return;   // already right (the ready event)
        if (portal == PortalLinks.Portal.NETHER) {
            // the ready event did not run or was overridden: scale like vanilla, Paper finds or builds the portal there
            double s = PortalLinks.scale(kind(from.getWorld()), PortalLinks.kind(d));
            var border = target.getWorldBorder();
            double half = Math.max(0, border.getSize() / 2 - 16);
            double x = Math.clamp(from.getX() * s, border.getCenter().getX() - half, border.getCenter().getX() + half);
            double z = Math.clamp(from.getZ() * s, border.getCenter().getZ() - half, border.getCenter().getZ() + half);
            double y = Math.clamp(from.getY(), target.getMinHeight() + 1, target.getMaxHeight() - 3);
            setTo.accept(new Location(target, x, y, z, from.getYaw(), from.getPitch()));
        } else if (d == PortalLinks.Dest.END) {
            endPlatform(target);
            setTo.accept(new Location(target, PortalLinks.ARRIVE_X, who instanceof Player ? PortalLinks.ARRIVE_Y : PortalLinks.ARRIVE_Y + 1,
                    PortalLinks.ARRIVE_Z, PortalLinks.ARRIVE_YAW, 0));
        } else {
            setTo.accept(wildSpawn(target));   // things leaving the end (a player leaving it respawns: onRespawn)
        }
    }

    /** The vanilla end spawn platform in the wild end (obsidian 5x5, three layers of air above), rebuilt where it is missing. */
    private void endPlatform(World end) {
        int h = PortalLinks.PLATFORM_HALF;
        for (int x = PortalLinks.PLATFORM_X - h; x <= PortalLinks.PLATFORM_X + h; x++) {
            for (int z = PortalLinks.PLATFORM_Z - h; z <= PortalLinks.PLATFORM_Z + h; z++) {
                for (int y = PortalLinks.PLATFORM_Y; y <= PortalLinks.PLATFORM_Y + PortalLinks.PLATFORM_AIR; y++) {
                    Block b = end.getBlockAt(x, y, z);
                    Material want = PortalLinks.platformBlock(x, y, z);
                    if (!PortalLinks.needsFix(want, b.getType())) continue;
                    if (!b.getType().isAir()) b.breakNaturally();
                    b.setType(want);
                }
            }
        }
    }

    private static Location wildSpawn(World wild) {
        return wild.getSpawnLocation().add(0.5, 0, 0.5);
    }

    /** Where a player goes who must leave a world: the wild world's spawn, else the build world's. */
    private Location fallbackSpawn() {
        World wild = world(PortalLinks.Dest.WILD);
        return wild != null ? wildSpawn(wild) : buildWorld().getSpawnLocation().add(0.5, 0, 0.5);
    }

    /** No nether portal is lit in the build world while there is a wild world (it would lead nowhere). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent e) {
        if (e.getReason() != PortalCreateEvent.CreateReason.FIRE || dest(e.getWorld(), PortalLinks.Portal.NETHER) != PortalLinks.Dest.BLOCKED) return;
        e.setCancelled(true);
        blocked(e.getEntity(), PortalLinks.Dest.BLOCKED);
    }

    // ------------------------------------------------------------------------------------------
    // The server's default nether and end are not played in while there is a wild world: whoever logs in there
    // goes to the wild world's spawn (the worlds and their data stay; vanilla still needs them loaded)
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!rules.retired(p.getWorld().getName(), main())) return;
        Location to = fallbackSpawn();
        p.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
            if (ok) p.sendMessage(Lang.msg("worlds.retired-moved", "world", displayName(to.getWorld())));
        });
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
        int seconds = admin ? 0 : rules.warmup();
        if (seconds > 0) p.sendMessage(Lang.msg(wild ? "worlds.warmup-wild" : "worlds.warmup", "world", name, "seconds", seconds));
        warmup(p, seconds, left -> Lang.mm("worlds.countdown", "world", name, "seconds", left), () -> go(p, to), null);
    }

    /** Seconds a player stands still before a trip (/건축월드, /야생월드, /텔포): worlds.travel-warmup-seconds, 0 for admins. */
    public int travelWarmup(Player p) {
        return p.hasPermission("stsv.admin") ? 0 : rules.warmup();
    }

    /**
     * Runs go after the player stood still for the given seconds (0 or less: at once), the countdown flashed on the
     * action bar every second. Moving 0.6 blocks or more, changing world, taking damage, dying or leaving cancels it:
     * the player is told why (worlds.moved, worlds.hurt), then onCancel (may be null) gets that key, or null when it
     * ended without a message (left, died, replaced). A player has one warm-up at a time: a new one (world travel or
     * tpa) replaces the old one, which counts as cancelled.
     */
    public void warmup(Player p, int seconds, java.util.function.IntFunction<net.kyori.adventure.text.Component> countdown,
                       Runnable go, java.util.function.Consumer<String> onCancel) {
        cancel(p, null);
        if (seconds <= 0) {
            go.run();
            return;
        }
        Location start = p.getLocation();
        int[] left = {seconds * 4};
        pending.put(p.getUniqueId(), new Warmup(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                cancel(p, null);
                return;
            }
            Location now = p.getLocation();
            if (!now.getWorld().equals(start.getWorld()) || now.distanceSquared(start) > 0.36) {
                cancel(p, "worlds.moved");
                return;
            }
            if (left[0] % 4 == 0 && left[0] > 0) HudModule.flash(p, countdown.apply(left[0] / 4));
            if (left[0]-- <= 0) {
                Warmup w = pending.remove(p.getUniqueId());
                if (w != null) w.task().cancel();
                go.run();
            }
        }, 0L, 5L), onCancel));
    }

    private void cancel(Player p, String why) {
        Warmup w = pending.remove(p.getUniqueId());
        if (w == null) return;
        w.task().cancel();
        if (why != null) p.sendMessage(Lang.msg(why));
        if (w.onCancel() != null) w.onCancel().accept(why);
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
            spotFor(wild, POOL_CHECKS).exceptionally(err -> {
                plugin.getLogger().warning("[월드] 야생월드 자리 찾기 실패: " + err.getMessage());
                return null;
            }).thenAccept(spot -> {
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

    /** Ready spots taken from the pool before giving up on it for one trip (each that turned unsafe is dropped). */
    private static final int POOL_CHECKS = 5;

    /**
     * A safe spot in the wild world for a trip: a ready spot from the pool ({@link WildSpotFiller}: found ahead of
     * time, the chunks around it generated), checked again on the main thread right before the teleport (a spot
     * someone built on or dug out is dropped and the next one taken); with an empty pool, {@link #searchSpot}.
     */
    private CompletableFuture<Location> spotFor(World w, int checksLeft) {
        if (resetting || !w.equals(wildWorld())) return CompletableFuture.completedFuture(null);
        WildSpotPool.Spot s = checksLeft > 0 ? spots.take(w) : null;
        if (s == null) return searchSpot(w);
        // generated already: a disk load at most, never generation (gen = false), urgent: a player is waiting
        return w.getChunkAtAsync(s.x() >> 4, s.z() >> 4, false, true).thenCompose(chunk -> {
            if (chunk != null && w.equals(wildWorld()) && WildSpotFiller.stillSafe(w, s)) {
                return CompletableFuture.completedFuture(new Location(w, s.x() + 0.5, s.y(), s.z() + 0.5,
                        ThreadLocalRandom.current().nextFloat() * 360 - 180, 0));
            }
            spots.dropped();
            return spotFor(w, checksLeft - 1);
        });
    }

    /**
     * No ready spot: random columns of the ring (between min-distance and radius from the spawn, inside the world
     * border), those in already generated chunks first; at most worlds.wild-spots.fallback-generate-tries
     * never-generated chunks are tried (each generates terrain), worlds.wild-random.tries in all. Chunks are loaded
     * asynchronously, the blocks read on the main thread. Completes with null when none was safe.
     */
    private CompletableFuture<Location> searchSpot(World w) {
        Location spawn = w.getSpawnLocation();
        List<int[]> candidates = new ArrayList<>();
        for (int i = 0; i < rules.tries() * 2; i++) {
            int[] xz = rules.randomColumn(ThreadLocalRandom.current(), spawn.getBlockX(), spawn.getBlockZ());
            if (w.getWorldBorder().isInside(new Location(w, xz[0] + 0.5, spawn.getY(), xz[1] + 0.5))) candidates.add(xz);
        }
        List<int[]> plan = WildSpotRules.plan(candidates, xz -> w.isChunkGenerated(xz[0] >> 4, xz[1] >> 4), rules.tries(),
                spots.settings().fallbackGenerate());
        return tryColumns(w, plan, 0);
    }

    private CompletableFuture<Location> tryColumns(World w, List<int[]> plan, int i) {
        if (i >= plan.size() || resetting || !w.equals(wildWorld())) return CompletableFuture.completedFuture(null);
        int[] xz = plan.get(i);
        return w.getChunkAtAsync(xz[0] >> 4, xz[1] >> 4).thenCompose(chunk -> {
            if (resetting || !w.equals(wildWorld())) return CompletableFuture.completedFuture(null);
            Location at = WildSpotFiller.surface(w, xz[0], xz[1]);
            return at != null ? CompletableFuture.completedFuture(at) : tryColumns(w, plan, i + 1);
        });
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 야생스팟 [채우기 <개수>|비우기]: the ready spots (also a line of /관리자 보고서)
    // ------------------------------------------------------------------------------------------

    private void spotsCommand(CommandSender sender, String[] args) {
        String what = args.length == 0 ? "" : args[0];
        switch (what) {
            case "채우기" -> {
                int n;
                try {
                    n = args.length > 1 ? Integer.parseInt(args[1]) : spots.settings().pool();
                } catch (NumberFormatException e) {
                    n = -1;
                }
                if (n <= 0) {
                    sender.sendMessage(Lang.msg("worlds.spots-fill-usage"));
                    return;
                }
                if (wildWorld() == null) {
                    sender.sendMessage(Lang.msg(resetting ? "worlds.resetting" : "worlds.no-wild"));
                    return;
                }
                sender.sendMessage(Lang.msg("worlds.spots-filling", "target", spots.fill(n)));
            }
            case "비우기" -> {
                spots.clear();
                sender.sendMessage(Lang.msg("worlds.spots-cleared"));
            }
            default -> sender.sendMessage(spotsStatus());
        }
    }

    private net.kyori.adventure.text.Component spotsStatus() {
        WildSpotFiller.Status st = spots.status();
        String state = switch (st.gate()) {
            case NO_WORLD -> Lang.raw(resetting ? "worlds.spots-state-resetting" : "worlds.spots-state-no-world");
            case OFF -> Lang.raw("worlds.spots-state-off");
            case FULL -> Lang.raw("worlds.spots-state-full");
            case BUSY -> Lang.raw("worlds.spots-state-busy");
            case LAG -> Lang.raw("worlds.spots-state-lag");
            case PACE, RUN -> Lang.raw(st.fast() ? "worlds.spots-state-fast" : "worlds.spots-state-working");
        };
        String candidate = st.candidateTotal() == 0 ? "" : Lang.raw("worlds.spots-candidate", "done", st.candidateDone(), "total", st.candidateTotal());
        WildSpotRules set = spots.settings();
        return Lang.msg("worlds.spots-status", "size", st.size(), "target", st.target(), "state", state, "candidate", candidate,
                "chunks", st.chunks(), "found", st.found(), "rejected", st.rejected(), "used", st.used(), "dropped", st.dropped(),
                "tps", String.format(java.util.Locale.ROOT, "%.0f", set.minTps()), "mspt", String.format(java.util.Locale.ROOT, "%.0f", set.maxMspt()),
                "seconds", String.format(java.util.Locale.ROOT, "%.2f", (st.fast() ? set.fastIntervalMs() : set.intervalMs()) / 1000.0));
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
    // Respawn: died in the wild set (wild world, its nether, its end) without a bed or anchor -> the wild world's
    // spawn; leaving the wild end -> the respawn point if it is in the wild set, else the wild world's spawn;
    // never into the server's unused default nether / end
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        cancel(e.getPlayer(), null);
        diedIn.put(e.getPlayer().getUniqueId(), kind(e.getPlayer().getWorld()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        WorldRules.Kind where = diedIn.remove(e.getPlayer().getUniqueId());
        boolean ownPoint = e.isBedSpawn() || e.isAnchorSpawn();
        World wild = world(PortalLinks.Dest.WILD);
        if (e.getRespawnReason() == PlayerRespawnEvent.RespawnReason.END_PORTAL) {
            if (wild == null || kind(e.getPlayer().getWorld()) != WorldRules.Kind.END) return;
            if (ownPoint && WorldRules.wildSet(kind(e.getRespawnLocation().getWorld()))) return;
            e.setRespawnLocation(wildSpawn(wild));
            return;
        }
        if (where != null && WorldRules.wildSet(where) && !ownPoint) {
            e.setRespawnLocation(fallbackSpawn());
            return;
        }
        World at = e.getRespawnLocation().getWorld();
        if (at != null && rules.retired(at.getName(), main())) e.setRespawnLocation(fallbackSpawn());
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 야생초기화 [확인]: the wild world, its nether and its end together
    // ------------------------------------------------------------------------------------------

    private void resetCommand(CommandSender sender, String[] args) {
        World wild = wildWorld();
        if (wild == null) {
            sender.sendMessage(Lang.msg(resetting ? "worlds.resetting" : "worlds.no-wild"));
            return;
        }
        List<World> worlds = wildSet().keySet().stream().map(Bukkit::getWorld).filter(Objects::nonNull).toList();
        String who = sender.getName();
        Long asked = confirm.get(who);
        if (args.length == 0 || !args[0].equals("확인") || asked == null || System.currentTimeMillis() - asked > 30_000) {
            confirm.put(who, System.currentTimeMillis());
            sender.sendMessage(Lang.msg("worlds.reset-confirm", "world", String.join(", ", wildSet().keySet()),
                    "players", worlds.stream().mapToInt(w -> w.getPlayers().size()).sum()));
            return;
        }
        confirm.remove(who);
        Path build = buildWorld().getWorldPath().toAbsolutePath().normalize();
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        List<Path> folders = new ArrayList<>();
        for (World w : worlds) {
            Path folder = w.getWorldPath().toAbsolutePath().normalize();
            if (build.startsWith(folder) || container.startsWith(folder) || folder.equals(build)) {
                sender.sendMessage(Lang.msg("worlds.reset-unsafe", "path", folder));
                return;
            }
            folders.add(folder);
        }
        long oldSeed = wild.getSeed();
        resetting = true;
        spots.clear();   // the new world gets a new pool, refilled in the background
        for (Warmup w : pending.values()) w.task().cancel();
        pending.clear();
        Location safe = buildWorld().getSpawnLocation().add(0.5, 0, 0.5);
        for (World w : worlds) {
            for (Player p : w.getPlayers()) {
                p.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
                p.sendMessage(Lang.msg("worlds.reset-moved"));
            }
        }
        boolean unloaded = true;
        for (World w : worlds) unloaded &= Bukkit.unloadWorld(w, false);
        if (!unloaded) {
            createWild(oldSeed);   // load back whatever was unloaded, unchanged
            resetting = false;
            sender.sendMessage(Lang.msg("worlds.reset-failed"));
            return;
        }
        sender.sendMessage(Lang.msg("worlds.reset-started", "world", rules.wild()));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean deleted = true;
            for (Path f : folders) deleted &= delete(f);
            boolean done = deleted;
            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean created = createWild(new Random().nextLong());
                resetting = false;
                if (!created || !done) sender.sendMessage(Lang.msg("worlds.reset-failed"));
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
