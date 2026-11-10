package dev.stsv.core.crop;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Crops as blocks borrow the tripwire (string) block, so real tripwire must not show up or change by itself:
 * <ul>
 *   <li>String cannot be placed as a block (the item keeps every other use); a tripwire hook that would hang string over
 *   a crop cannot be placed. A short note on the action bar now and then.</li>
 *   <li>Tripwire that is not a crop (string placed before, jungle temple traps, new chunks) is turned into air, or plain
 *   string (config crops.stray-tripwire), once per chunk (a mark in the chunk).</li>
 *   <li>Crop blocks do not change: walking through, neighbours, pistons, water, explosions, mobs, buckets, dispensers.
 *   Paper's {@code block-updates.disable-tripwire-updates: true} freezes the tripwire in the game itself (recommended,
 *   a warning at start without it); the listeners here hold it as far as events allow, and CropModule puts a block
 *   back in its state when it finds it changed.</li>
 *   <li>The client reshapes a tripwire when it predicts a change next to it (a block it breaks or places), which would
 *   show another crop there: the crops around such a change are sent to that player again a tick later.</li>
 *   <li>Picking a crop block (middle click) gives nothing (never string).</li>
 * </ul>
 */
final class CropTripwire implements Listener {

    /** Chunk mark: stray tripwire in it already handled (version). */
    private static final NamespacedKey CHECKED = new NamespacedKey("stsv", "tripwire_checked");

    private final CropModule crops;
    private final STSVPlugin plugin;
    private CropBlocks.Stray stray;
    private final Listener unfrozen = new Unfrozen();
    /** Is Paper's disable-tripwire-updates on? null = could not tell. */
    private Boolean frozen;
    private final Map<UUID, Long> noticed = new HashMap<>();
    /** blocks to send again next tick: player (null = everyone who sees the chunk) -> positions */
    private final Map<UUID, Set<Block>> resend = new HashMap<>();
    private final Set<Block> resendAll = new HashSet<>();
    private boolean resendQueued;
    private boolean active;

    CropTripwire(CropModule crops, STSVPlugin plugin, CropBlocks.Stray stray) {
        this.crops = crops;
        this.plugin = plugin;
        this.stray = stray;
    }

    void stray(CropBlocks.Stray s) {
        this.stray = s;
    }

    void start() {
        active = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        frozen = paperFreezesTripwire();
        if (!Boolean.TRUE.equals(frozen)) {
            // without it the game itself changes tripwire (walking through, neighbours): hold what events allow
            Bukkit.getPluginManager().registerEvents(unfrozen, plugin);
            plugin.getLogger().warning("[작물] Paper 설정 block-updates.disable-tripwire-updates 가 " + (frozen == null ? "확인되지 않습니다" : "꺼져 있습니다")
                    + ". 작물 블록(트립와이어)이 밟히거나 옆 블록이 바뀔 때 잠깐 다른 모양으로 보일 수 있습니다. "
                    + "config/paper-global.yml 에서 block-updates: disable-tripwire-updates: true 로 바꾸고 서버를 다시 시작하세요 (README 참고).");
        }
    }

    void stop() {
        active = false;
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(unfrozen);
    }

    /** Reads config/paper-global.yml (where Paper keeps it), from the server folder. */
    private static Boolean paperFreezesTripwire() {
        for (File f : List.of(new File("config/paper-global.yml"), new File(Bukkit.getWorldContainer(), "config/paper-global.yml"))) {
            if (!f.isFile()) continue;
            var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            if (y.contains("block-updates.disable-tripwire-updates")) return y.getBoolean("block-updates.disable-tripwire-updates");
        }
        return null;
    }

    private CropState crop(Block b) {
        return crops.at(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    private boolean isCrop(Block b) {
        return b.getType() == Material.TRIPWIRE && crop(b) != null;
    }

    // ------------------------------------------------------------------------------------------
    // No string as a block
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Player p = e.getPlayer();
        if (CropBlocks.stringPlacement(b.getType(), e.getItemInHand().getType())) {
            // string on anything (farmland, a crop, the ground): nothing is placed, the string stays in the hand
            e.setCancelled(true);
            notice(p, "crop.string-blocked");
        } else if (b.getType() == Material.TRIPWIRE_HOOK && hookFacesCrop(b)) {
            e.setCancelled(true);
            notice(p, "crop.hook-blocked");
        }
        fixView(p, b);
    }

    /** A hook pulls the tripwire in front of it (up to 41 blocks) into its line: no hook may face a crop. */
    private boolean hookFacesCrop(Block hook) {
        if (!(hook.getBlockData() instanceof Directional d)) return false;
        BlockFace f = d.getFacing();
        for (int i = 1; i <= 41; i++) {
            Block b = hook.getRelative(f, i);
            if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) break;
            if (isCrop(b)) return true;
            if (b.getType() == Material.TRIPWIRE_HOOK) break;
        }
        return false;
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        noticed.remove(e.getPlayer().getUniqueId());
        resend.remove(e.getPlayer().getUniqueId());
    }

    private void notice(Player p, String key) {
        long now = System.currentTimeMillis();
        Long last = noticed.get(p.getUniqueId());
        if (last != null && now - last < 8000) return;
        noticed.put(p.getUniqueId(), now);
        HudModule.flash(p, Lang.mm(key));
    }

    // ------------------------------------------------------------------------------------------
    // What the client changes on its own: send the crops around again
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClickMonitor(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getAction() == Action.PHYSICAL) return;
        fixView(e.getPlayer(), b);
        fixView(e.getPlayer(), b.getRelative(e.getBlockFace()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakMonitor(BlockBreakEvent e) {
        fixView(e.getPlayer(), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonMonitor(BlockPistonExtendEvent e) {
        piston(e.getBlocks(), e.getDirection(), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonMonitor(BlockPistonRetractEvent e) {
        piston(e.getBlocks(), e.getDirection(), e.getBlock());
    }

    private void piston(List<Block> moved, BlockFace dir, Block piston) {
        List<Block> around = new ArrayList<>(moved);
        for (Block b : moved) around.add(b.getRelative(dir));
        around.add(piston.getRelative(dir));
        // after the move has played out on the client (2 ticks)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Block b : around) fixView(null, b);
        }, 3L);
    }

    /**
     * The crops within a block of this one (their tripwire) are sent again on the next tick: to this player, or to every
     * player who sees the chunk. Cheap when there are none (a lookup per neighbour).
     */
    void fixView(Player p, Block center) {
        if (!active || center == null) return;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            Block b = center.getRelative(dx, dy, dz);
            if (crops.at(b.getWorld(), b.getX(), b.getY(), b.getZ()) == null) continue;
            if (p == null) resendAll.add(b);
            else resend.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(b);
        }
        if ((!resend.isEmpty() || !resendAll.isEmpty()) && !resendQueued) {
            resendQueued = true;
            Bukkit.getScheduler().runTask(plugin, this::flushResend);
        }
    }

    private void flushResend() {
        resendQueued = false;
        for (Map.Entry<UUID, Set<Block>> e : resend.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            for (Block b : e.getValue()) send(p, b);
        }
        resend.clear();
        for (Block b : resendAll) {
            World w = b.getWorld();
            if (!w.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
            for (Player p : w.getPlayersSeeingChunk(b.getX() >> 4, b.getZ() >> 4)) send(p, b);
        }
        resendAll.clear();
    }

    private static void send(Player p, Block b) {
        if (p.getWorld() != b.getWorld() || !b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) return;
        if (p.getLocation().distanceSquared(b.getLocation()) > 160 * 160) return;
        p.sendBlockChange(b.getLocation(), b.getBlockData());
    }

    // ------------------------------------------------------------------------------------------
    // Crop blocks stay as they are
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        // the plant stays (as its display did); soil blown away makes it pop as its seed on the next growth check
        e.blockList().removeIf(this::isCrop);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isCrop);
    }

    /** Paper: anything about to destroy a block (physics, fluids, pistons, commands with destroy): not a crop block. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDestroy(com.destroystokyo.paper.event.block.BlockDestroyEvent e) {
        if (isCrop(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (isCrop(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        // water poured where a crop stands would wash its block away
        if (isCrop(e.getBlock())) {
            e.setCancelled(true);
            fixView(e.getPlayer(), e.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (!(e.getBlock().getBlockData() instanceof Directional d)) return;
        if (!e.getItem().getType().name().endsWith("_BUCKET")) return;
        if (isCrop(e.getBlock().getRelative(d.getFacing()))) e.setCancelled(true);
    }

    /** Stepping on tripwire (a player: PHYSICAL; a mob: EntityInteractEvent; only for "attached" states). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onStep(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL && e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.TRIPWIRE) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStep(EntityInteractEvent e) {
        if (e.getBlock().getType() == Material.TRIPWIRE) e.setCancelled(true);
    }

    /** Never string from a crop: middle click on one gives nothing. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPick(io.papermc.paper.event.player.PlayerPickBlockEvent e) {
        if (e.getBlock().getType() == Material.TRIPWIRE && crop(e.getBlock()) != null) e.setCancelled(true);
    }

    /** Only while Paper's disable-tripwire-updates is off: the game's own tripwire updates on crop blocks. */
    private final class Unfrozen implements Listener {

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onInside(io.papermc.paper.event.entity.EntityInsideBlockEvent e) {
            if (isCrop(e.getBlock())) e.setCancelled(true);
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onPhysics(BlockPhysicsEvent e) {
            Block b = e.getBlock();
            if (b.getType() == Material.TRIPWIRE && crop(b) != null) e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Stray tripwire (not a crop): once per chunk
    // ------------------------------------------------------------------------------------------

    /**
     * Looks through a loaded chunk for tripwire that is not a crop (once: the chunk keeps a mark), off the main thread on a
     * snapshot, and turns what it finds into air or plain string (crops.stray-tripwire). New chunks too (temple traps).
     */
    void scan(Chunk c) {
        if (!active) return;
        var pdc = c.getPersistentDataContainer();
        if (!CropBlocks.needsCheck(pdc.get(CHECKED, PersistentDataType.INTEGER))) return;
        World w = c.getWorld();
        int cx = c.getX(), cz = c.getZ(), minY = w.getMinHeight(), maxY = w.getMaxHeight();
        ChunkSnapshot snap = c.getChunkSnapshot(false, false, false);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<int[]> found = new ArrayList<>();
            int minSection = minY >> 4;
            for (int sy = minSection; sy < (maxY + 15) >> 4; sy++) {
                // the snapshot's sections are numbered from the world's bottom (0 = minY), not by section Y
                if (CropBlocks.sectionEmpty(snap::isSectionEmpty, sy, minSection)) continue;
                for (int y = Math.max(minY, sy << 4); y < Math.min(maxY, (sy + 1) << 4); y++) {
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                        if (snap.getBlockType(x, y, z) == Material.TRIPWIRE) found.add(new int[]{x, y, z});
                    }
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> finishScan(w, cx, cz, found));
        });
    }

    private void finishScan(World w, int cx, int cz, List<int[]> found) {
        if (!active || !w.isChunkLoaded(cx, cz)) return;   // checked again on its next load
        Chunk c = w.getChunkAt(cx, cz);
        int changed = 0;
        for (int[] f : found) {
            Block b = c.getBlock(f[0], f[1], f[2]);
            if (b.getType() != Material.TRIPWIRE || crop(b) != null) continue;
            if (straighten(b)) changed++;
        }
        c.getPersistentDataContainer().set(CHECKED, PersistentDataType.INTEGER, CropBlocks.CHECK_VERSION);
        if (changed > 0) {
            plugin.getLogger().info("[작물] 작물이 아닌 트립와이어(실) " + changed + "개를 " + (stray == CropBlocks.Stray.AIR ? "없앰" : "평범한 실로 바꿈")
                    + " (" + w.getName() + " 청크 " + cx + ", " + cz + ")");
        }
    }

    /** A tripwire that is not a crop: air or plain string. True if it changed. */
    private boolean straighten(Block b) {
        if (!(b.getBlockData() instanceof Tripwire t)) return false;
        int code = CropBlocks.code(t.isAttached(), t.isDisarmed(), t.hasFace(BlockFace.EAST), t.hasFace(BlockFace.NORTH), t.isPowered(),
                t.hasFace(BlockFace.SOUTH), t.hasFace(BlockFace.WEST));
        int to = CropBlocks.stray(code, stray);
        if (to == code) return false;
        BlockData data = to < 0 ? Material.AIR.createBlockData() : crops.stateData(to);
        b.setBlockData(data, false);
        fixView(null, b);
        return true;
    }
}
