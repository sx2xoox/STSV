package dev.stsv.core.minigame.circuit;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Puts a {@link TrackLayout} into the minigame world, a few thousand blocks a tick (minigames.build-blocks-per-tick) so the
 * server never stalls: its chunks are loaded first (asynchronously, held by plugin chunk tickets while it builds), then,
 * when it is built again, everything the last build left is taken away (the old area up to its old height), then the
 * field (two layers: the world under it is empty), then the layout's blocks bottom up. Blocks are set without updates:
 * fences and walls come joined already (TrackLayout), water and lava stay where they are put.
 */
final class TrackBuilder {

    /** The last build's area (to clear), or null for a first build. */
    record Old(int minX, int maxX, int minZ, int maxZ, int maxY) {}

    private final Plugin plugin;
    private final World world;
    private final TrackLayout layout;
    private final int ox;
    private final Old old;
    private final int perTick;
    private final IntConsumer progress;
    private final Runnable done;
    private final Set<Chunk> held = new HashSet<>();
    private final Map<String, BlockData> parsed = new HashMap<>();
    private final Set<String> bad = new HashSet<>();
    private BukkitTask task;
    private int phase;          // 0 loading, 1 clearing, 2 field, 3 blocks
    private int pending;
    private long cursor;
    private long total, doneOps;
    private List<long[]> blocks;
    private int ticks;

    TrackBuilder(Plugin plugin, World world, TrackLayout layout, Old old, int perTick, IntConsumer progress, Runnable done) {
        this.plugin = plugin;
        this.world = world;
        this.layout = layout;
        this.ox = layout.spec().originX();
        this.old = old;
        this.perTick = Math.max(500, perTick);
        this.progress = progress;
        this.done = done;
    }

    void start() {
        // the blocks sorted bottom up (a lantern after the post it hangs from), as (x, y, z, index of its data)
        List<String> datas = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        blocks = new ArrayList<>(layout.blockCount());
        layout.forEach((x, y, z, data) -> blocks.add(new long[]{x, y, z, index.computeIfAbsent(data, d -> {
            datas.add(d);
            return datas.size() - 1;
        })}));
        blocks.sort((a, b) -> Long.compare(a[1], b[1]));
        for (String d : datas) {
            try {
                parsed.put(d, Bukkit.createBlockData(d));
            } catch (IllegalArgumentException e) {
                if (bad.add(d)) plugin.getLogger().warning("[서킷] " + layout.spec().id() + ": 알 수 없는 블록 " + d + " (건너뜀)");
            }
        }
        this.datas = datas;
        long field = (long) (layout.maxX() - layout.minX() + 1) * (layout.maxZ() - layout.minZ() + 1);
        long clear = old == null ? 0 : (long) (old.maxX() - old.minX() + 1) * (old.maxZ() - old.minZ() + 1) * (old.maxY() + 8) / 8;
        total = clear + field + blocks.size();
        // the chunks: the new area and the old one
        Set<Long> chunks = new HashSet<>();
        for (long[] c : layout.chunks()) chunks.add(c[0] << 32 | (c[1] & 0xFFFFFFFFL));
        if (old != null) for (int cx = Math.floorDiv(old.minX() + ox, 16); cx <= Math.floorDiv(old.maxX() + ox, 16); cx++)
            for (int cz = Math.floorDiv(old.minZ(), 16); cz <= Math.floorDiv(old.maxZ(), 16); cz++) chunks.add((long) cx << 32 | (cz & 0xFFFFFFFFL));
        pending = chunks.size();
        for (long k : chunks) {
            int cx = (int) (k >> 32), cz = (int) k;
            world.getChunkAtAsync(cx, cz, true).whenComplete((c, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (c != null && task != null) {
                    c.addPluginChunkTicket(plugin);
                    held.add(c);
                }
                pending--;
            }));
        }
        phase = 0;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private List<String> datas;

    void cancel() {
        if (task != null) task.cancel();
        task = null;
        for (Chunk c : held) c.removePluginChunkTicket(plugin);
        held.clear();
    }

    boolean running() {
        return task != null;
    }

    private void tick() {
        if (phase == 0) {
            if (pending > 0) return;
            phase = old == null ? 2 : 1;
            cursor = 0;
        }
        int budget = perTick;
        int base = TrackLayout.BASE_Y;
        while (budget > 0) {
            if (phase == 1) {
                // clearing the old build: a column at a time, from below the field up to its old height
                int w = old.maxX() - old.minX() + 1, d = old.maxZ() - old.minZ() + 1;
                if (cursor >= (long) w * d) {
                    phase = 2;
                    cursor = 0;
                    continue;
                }
                int x = old.minX() + (int) (cursor / d), z = old.minZ() + (int) (cursor % d);
                for (int y = base - 4; y <= base + old.maxY() + 2; y++) {
                    Block b = world.getBlockAt(x + ox, y, z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
                cursor++;
                budget -= Math.max(1, (old.maxY() + 8) / 8);
                doneOps += Math.max(1, (old.maxY() + 8) / 8);
            } else if (phase == 2) {
                int w = layout.maxX() - layout.minX() + 1, d = layout.maxZ() - layout.minZ() + 1;
                if (cursor >= (long) w * d) {
                    phase = 3;
                    cursor = 0;
                    continue;
                }
                int x = layout.minX() + (int) (cursor / d), z = layout.minZ() + (int) (cursor % d);
                set(x, -1, z, layout.ground(x, z));
                set(x, -2, z, layout.under());
                cursor++;
                budget -= 2;
                doneOps++;
            } else {
                if (cursor >= blocks.size()) {
                    finish();
                    return;
                }
                long[] b = blocks.get((int) cursor++);
                set((int) b[0], (int) b[1], (int) b[2], datas.get((int) b[3]));
                budget--;
                doneOps++;
            }
        }
        if (++ticks % 10 == 0) progress.accept((int) Math.min(99, doneOps * 100 / Math.max(1, total)));
    }

    private void set(int x, int y, int z, String data) {
        BlockData d = parsed.computeIfAbsent(data, s -> {
            try {
                return Bukkit.createBlockData(s);
            } catch (IllegalArgumentException e) {
                if (bad.add(s)) plugin.getLogger().warning("[서킷] " + layout.spec().id() + ": 알 수 없는 블록 " + s + " (건너뜀)");
                return null;
            }
        });
        if (d == null) return;
        int wy = TrackLayout.BASE_Y + y;
        if (wy < world.getMinHeight() || wy >= world.getMaxHeight()) return;
        world.getBlockAt(x + ox, wy, z).setBlockData(d, false);
    }

    private void finish() {
        cancel();
        progress.accept(100);
        done.run();
    }
}
