package dev.stsv.core.minigame;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.minigame.circuit.Circuit;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 미니게임: games players play when they want a break (/미니게임: the hub's game picker; the first game is 서킷, see
 * {@link Circuit}). They take place in a world of their own (minigames.world, created empty on first start: every block
 * in it is built by the games), so nothing a game builds can be broken by the rest of the server, nothing of the rest
 * (land, jobs, the wild world's reset) reaches into it, and its chunks are loaded only while someone plays there.
 *
 * <h2>The world's rules</h2> Nobody breaks, places, empties a bucket or uses a block there (admins in creative mode may,
 * to fix things); nothing drops or is picked up; nobody gets hungry or hurt; no mob spawns; blocks never change by
 * themselves (no physics, flowing, melting, spreading, burning or decay: the game rules and the events both). Nobody
 * gets in except by a game (or an admin): a /tpa to a racer is refused. A racer cannot be teleported away, run commands
 * other than the game's own, swap hands, or move their items. A watcher (관전: a spectator of a running race) cannot be
 * teleported away either, nor use other commands, nor make their camera follow anything but the race's racers.
 *
 * <h2>The way back</h2> {@link ReturnPoint}: written onto the player before a game changes anything; whoever logs in or
 * respawns with it is put back, so a crash can never strand anyone or lose an inventory.
 */
public final class MinigameModule implements StsvModule, Listener, TabExecutor {

    public static final String ADMIN_OWNER = "STSV 미니게임";
    /** The hub's game picker page (a content plugin's hub may override it: economy:hub_minigame). */
    public static final String HUB_PAGE = "stsv:hub_minigame";
    /** Commands a racer may still use. */
    private static final Set<String> RACER_COMMANDS = Set.of("서킷", "circuit", "카트", "레이스", "미니게임", "minigame", "msg", "tell", "w", "r",
            "귓", "귓속말", "답장", "stsv:서킷", "stsv:미니게임");

    private STSVPlugin plugin;
    private Circuit circuit;
    private String worldName;
    private BukkitTask task;
    private boolean enabled;

    @Override
    public String id() {
        return "minigames";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        enabled = plugin.getConfig().getBoolean("minigames.enabled");
        worldName = plugin.getConfig().getString("minigames.world");
        for (String name : new String[]{"미니게임", "서킷"}) {
            PluginCommand c = plugin.getCommand(name);
            if (c != null) {
                c.setExecutor(this);
                c.setTabCompleter(this);
            }
        }
        if (!enabled) {
            plugin.getLogger().info("[미니게임] 꺼짐 (minigames.enabled)");
            return;
        }
        circuit = new Circuit(plugin, this);
        circuit.enable();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> circuit.tick(), 1L, 1L);
        // the world once every plugin has loaded its own (the first tick), then the tracks never built here
        Bukkit.getScheduler().runTask(plugin, () -> {
            World w = createWorld();
            if (w != null) circuit.buildChanged(w);
            for (Player p : Bukkit.getOnlinePlayers()) if (ReturnPoint.has(p)) circuit.putBack(p);
        });
        plugin.admin().add(ADMIN_OWNER, "서킷", "목록 | 재건설 [트랙|전체] | 시작 [트랙] | 중지 [트랙|전체] | 기록초기화 <트랙|전체> [플레이어] 확인 | 이동 <트랙>",
                "서킷 트랙 · 레이스 · 기록 관리", (s, a) -> circuit.admin(s, a), (s, a) -> circuit.adminComplete(a));
        plugin.admin().report(ADMIN_OWNER, (s, a) -> circuit.report(s));
        if (plugin.hub() != null) plugin.hub().provide("minigames", p -> circuit.hubValues(p));
    }

    @Override
    public void reload() {
        if (circuit != null) {
            circuit.load();
            World w = world();
            if (w != null) circuit.buildChanged(w);
        }
    }

    @Override
    public void disable() {
        if (task != null) task.cancel();
        if (circuit != null) circuit.disable();
        if (plugin.hub() != null) plugin.hub().unprovide("minigames");
        plugin.admin().removeAll(ADMIN_OWNER);
        HandlerList.unregisterAll(this);
    }

    private World createWorld() {
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            plugin.getLogger().info("[미니게임] 미니게임 월드 '" + worldName + "' 를 불러옵니다 (없으면 빈 월드로 새로 만듦)");
            w = Bukkit.createWorld(new WorldCreator(worldName).generator(new VoidGenerator()).generateStructures(false)
                    .environment(World.Environment.NORMAL));
        }
        if (w == null) {
            plugin.getLogger().warning("[미니게임] 미니게임 월드를 만들지 못했습니다: 미니게임을 쓸 수 없음");
            return null;
        }
        rule(w, GameRules.KEEP_INVENTORY, true);
        rule(w, GameRules.ADVANCE_TIME, false);
        rule(w, GameRules.ADVANCE_WEATHER, false);
        rule(w, GameRules.SPAWN_MOBS, false);
        rule(w, GameRules.SPAWN_MONSTERS, false);
        rule(w, GameRules.SPAWN_PATROLS, false);
        rule(w, GameRules.SPAWN_PHANTOMS, false);
        rule(w, GameRules.SPAWN_WANDERING_TRADERS, false);
        rule(w, GameRules.RANDOM_TICK_SPEED, 0);
        rule(w, GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        rule(w, GameRules.FALL_DAMAGE, false);
        rule(w, GameRules.MOB_GRIEFING, false);
        rule(w, GameRules.BLOCK_DROPS, false);
        rule(w, GameRules.SHOW_DEATH_MESSAGES, false);
        w.setTime(6000);
        w.setStorm(false);
        w.setThundering(false);
        w.setAutoSave(true);
        return w;
    }

    private static <T> void rule(World w, GameRule<T> rule, T value) {
        if (!value.equals(w.getGameRuleValue(rule))) w.setGameRule(rule, value);
    }

    public World world() {
        return Bukkit.getWorld(worldName);
    }

    private boolean in(World w) {
        return w != null && w.getName().equals(worldName);
    }

    /** Where someone goes when the world they came from is no more: the build world's spawn. */
    public Location fallback() {
        World w = plugin.worlds() != null ? plugin.worlds().buildWorld() : Bukkit.getWorlds().getFirst();
        return w.getSpawnLocation().add(0.5, 0, 0.5);
    }

    public Circuit circuit() {
        return circuit;
    }

    // ------------------------------------------------------------------------------------------
    // Commands: /미니게임, /서킷
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        if (circuit == null) {
            p.sendMessage(Lang.msg("minigame.disabled"));
            return true;
        }
        if (command.getName().equals("미니게임")) {
            openHub(p, HUB_PAGE);
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                var race = circuit.raceOf(p);
                if (race != null) circuit.join(p, null);
                else if (circuit.watching(p)) circuit.openWatchMenu(p);
                else openHub(p, "stsv:hub_circuit");
            }
            case "관전", "구경", "watch", "spectate" -> circuit.watch(p, args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : null);
            case "참가", "join" -> circuit.join(p, args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : null);
            case "나가기", "leave" -> circuit.leave(p);
            case "기록", "순위", "records" -> circuit.showRecords(p, args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : null);
            case "트랙", "tracks" -> circuit.showTracks(p);
            default -> p.sendMessage(Lang.msg("circuit.usage"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equals("미니게임") || circuit == null) return List.of();
        if (args.length == 1) return List.of("참가", "관전", "나가기", "기록", "트랙");
        if (args.length == 2 && (args[0].equals("참가") || args[0].equals("기록"))) return circuit.trackIds();
        if (args.length == 2 && args[0].equals("관전")) return circuit.watchTargets();
        return List.of();
    }

    private void openHub(Player p, String page) {
        if (circuit.inGame(p)) {
            p.sendMessage(Lang.msg("circuit.already"));
            return;
        }
        if (plugin.hub() != null) plugin.hub().openPage(p, page);
    }

    // ------------------------------------------------------------------------------------------
    // Coming and going
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        circuit.quit(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (ReturnPoint.has(p)) {
                circuit.putBack(p);
                p.sendMessage(Lang.msg("minigame.returned"));
            } else if (in(p.getWorld()) && !p.hasPermission("stsv.admin")) {
                circuit.teleporting(p, true);
                p.teleport(fallback());
                circuit.teleporting(p, false);
            }
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!ReturnPoint.has(p)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !p.isDead() && !circuit.inGame(p)) circuit.putBack(p);
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        // (nobody is hurt in the minigame world: only /kill and the like)
        if (circuit.inGame(e.getPlayer())) circuit.leave(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (circuit.ownTeleport(p)) return;
        if (circuit.racing(p) && e.getCause() != PlayerTeleportEvent.TeleportCause.DISMOUNT) {
            e.setCancelled(true);
            p.sendMessage(Lang.msg("circuit.no-teleport"));
            return;
        }
        if (circuit.watching(p)) {
            // the spectator menu's "teleport to player" and every other trip: only the race moves a watcher
            e.setCancelled(true);
            p.sendMessage(Lang.msg("circuit.watch-no-teleport"));
            return;
        }
        if (e.getTo() != null && in(e.getTo().getWorld()) && !in(e.getFrom().getWorld()) && !p.hasPermission("stsv.admin")) {
            e.setCancelled(true);
            p.sendMessage(Lang.msg("minigame.no-entry"));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!circuit.inGame(p) || p.hasPermission("stsv.admin")) return;
        String word = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!RACER_COMMANDS.contains(word)) {
            e.setCancelled(true);
            p.sendMessage(Lang.msg("circuit.no-command"));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Watchers' cameras
    // ------------------------------------------------------------------------------------------

    /** A watcher's camera leaves what it follows only when the race moves it (sneaking twice is read as leaving). */
    @EventHandler(priority = EventPriority.LOW)
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent e) {
        Player p = e.getPlayer();
        if (circuit.watching(p) && !circuit.ownTeleport(p)) e.setCancelled(true);
    }

    /** A watcher's click on an entity: only a racer of their race (followed through their eyes), never anything else. */
    @EventHandler(priority = EventPriority.LOW)
    public void onStartSpectating(com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent e) {
        Player p = e.getPlayer();
        if (!circuit.watching(p) || circuit.ownTeleport(p)) return;
        if (!circuit.watchClick(p, e.getNewSpectatorTarget())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------
    // Racers' hands and items
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!in(p.getWorld())) return;
        if (circuit.racing(p)) {
            e.setCancelled(true);
            if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                ItemStack it = e.getItem();
                String what = it == null || !it.hasItemMeta() ? null : it.getItemMeta().getPersistentDataContainer().get(Circuit.ITEM, PersistentDataType.STRING);
                if (what != null && e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) circuit.useItem(p, what);
            }
            return;
        }
        if (!bypass(p) && (e.getClickedBlock() != null || e.getAction() == Action.PHYSICAL)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseEntity(PlayerInteractEntityEvent e) {
        if (in(e.getPlayer().getWorld()) && !bypass(e.getPlayer()) && !dev.stsv.core.vehicle.VehicleModule.isVehiclePart(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (circuit.racing(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && circuit.racing(p) && e.getClickedInventory() == p.getInventory()) e.setCancelled(true);
        if (e.getWhoClicked() instanceof Player p && circuit.racing(p) && (e.getClick().isKeyboardClick() || e.isShiftClick())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && circuit.racing(p)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------
    // The world's protection
    // ------------------------------------------------------------------------------------------

    /** Admins in creative mode may change the minigame world (to fix it); nobody racing. */
    private boolean bypass(Player p) {
        return p.getGameMode() == GameMode.CREATIVE && p.hasPermission("stsv.admin") && !circuit.racing(p);
    }

    private void guard(Cancellable e, World w) {
        if (in(w)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (in(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (in(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (in(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (in(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (in(e.getPlayer().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) {
        guard(e, e.getEntity().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHunger(FoodLevelChangeEvent e) {
        if (in(e.getEntity().getWorld()) && e.getFoodLevel() < e.getEntity().getFoodLevel()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (in(e.getEntity().getWorld()) && e.getCause() != EntityDamageEvent.DamageCause.KILL) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!in(e.getLocation().getWorld())) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.CUSTOM && r != CreatureSpawnEvent.SpawnReason.DEFAULT && r != CreatureSpawnEvent.SpawnReason.COMMAND)
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhysics(BlockPhysicsEvent e) {
        guard(e, e.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFlow(BlockFromToEvent e) {
        guard(e, e.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFade(BlockFadeEvent e) {
        guard(e, e.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onForm(BlockFormEvent e) {
        guard(e, e.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpread(BlockSpreadEvent e) {
        guard(e, e.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onGrow(BlockGrowEvent e) {
        guard(e, e.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTree(StructureGrowEvent e) {
        guard(e, e.getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDecay(LeavesDecayEvent e) {
        guard(e, e.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBurn(BlockBurnEvent e) {
        guard(e, e.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onIgnite(BlockIgniteEvent e) {
        guard(e, e.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChange(EntityChangeBlockEvent e) {
        guard(e, e.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onExplode(EntityExplodeEvent e) {
        if (in(e.getLocation().getWorld())) e.blockList().clear();
        if (e.getEntity() instanceof Firework f && f.getPersistentDataContainer().has(Circuit.SAFE_FIREWORK)) e.blockList().clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunk(ChunkLoadEvent e) {
        if (in(e.getWorld())) circuit.chunkLoaded(e.getChunk());
    }
}
