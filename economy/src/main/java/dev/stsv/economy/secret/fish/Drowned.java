package dev.stsv.economy.secret.fish;

import dev.stsv.economy.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * "내가 빠진 곳": where the server's first fisher drowned. The fishing records keep when each fish was first caught but
 * never where, so it is the deepest open water within search-radius of the main world's spawn (where everyone first came
 * in): found once, a few already generated chunks a tick (none generated for it), and kept in eco.kv {@link #KEY}. The
 * settings' final.place wins over it.
 */
final class Drowned {

    static final String KEY = "secret.fish.drowned";

    /** The place: world, the water's top block, how deep it is (-1 = set by hand). */
    record Place(String world, int x, int y, int z, int depth) {
        Optional<Location> location() {
            World w = Bukkit.getWorld(world);
            return w == null ? Optional.empty() : Optional.of(new Location(w, x + 0.5, y, z + 0.5));
        }

        /** Distance across the map from a block, -1 in another world. */
        double distance(Block b) {
            if (!b.getWorld().getName().equals(world)) return -1;
            return Math.hypot(b.getX() - x, b.getZ() - z);
        }

        @Override
        public String toString() {
            return world + " " + x + " " + y + " " + z + (depth >= 0 ? " (깊이 " + depth + ")" : " (설정)");
        }
    }

    private final EyelessFish s;
    private final EcoPlugin eco;
    private BukkitTask search;

    Drowned(EyelessFish s, EcoPlugin eco) {
        this.s = s;
        this.eco = eco;
    }

    /** The settings' place, else the one found and kept. */
    Optional<Place> place() {
        Object[] d = Waters.decode(s.cfg().getString("final.place", ""));
        if (d == null) d = Waters.decode(eco.kv().get(KEY));
        if (d == null) return Optional.empty();
        return Optional.of(new Place((String) d[0], (Integer) d[1], (Integer) d[2], (Integer) d[3], (Integer) d[4]));
    }

    boolean searching() {
        return search != null;
    }

    /** Forgets the kept place (the next search finds it again). */
    void forget() {
        eco.kv().remove(KEY);
    }

    void stop() {
        if (search != null) search.cancel();
        search = null;
    }

    /** Looks for the deepest water near the spawn, unless a place is known or a search is on; then runs done (main thread). */
    void find(Runnable done) {
        if (place().isPresent() || search != null || Bukkit.getWorlds().isEmpty()) {
            if (done != null && place().isPresent()) done.run();
            return;
        }
        World w = Bukkit.getWorlds().getFirst();
        Location spawn = w.getSpawnLocation();
        int r = Math.max(16, s.cfg().getInt("final.search-radius", 300));
        int sx = spawn.getBlockX(), sz = spawn.getBlockZ();
        Deque<int[]> chunks = new ArrayDeque<>();
        for (int cx = (sx - r) >> 4; cx <= (sx + r) >> 4; cx++)
            for (int cz = (sz - r) >> 4; cz <= (sz + r) >> 4; cz++) chunks.add(new int[]{cx, cz});
        int[] best = {Integer.MIN_VALUE, 0, 0, -1};   // y, x, z, depth
        double[] bestD = {Double.MAX_VALUE};
        int[] flying = {0};
        long rr = (long) r * r;
        eco.getLogger().info("[비밀] 눈 없는 물고기: 스폰 " + r + "칸 안의 가장 깊은 물을 찾는 중 (청크 " + chunks.size() + "개)");
        search = Bukkit.getScheduler().runTaskTimer(eco, () -> {
            while (flying[0] < 4 && !chunks.isEmpty()) {
                int[] c = chunks.poll();
                flying[0]++;
                w.getChunkAtAsync(c[0], c[1], false).whenComplete((ch, err) -> Bukkit.getScheduler().runTask(eco, () -> {
                    flying[0]--;
                    if (ch != null) scan(w, ch, sx, sz, rr, best, bestD);
                }));
            }
            if (!chunks.isEmpty() || flying[0] > 0) return;
            stop();
            int y = best[0], x = best[1], z = best[2], depth = best[3];
            if (depth < 0) {
                eco.getLogger().warning("[비밀] 눈 없는 물고기: 스폰 " + r + "칸 안에 물이 없어 스폰을 씀 (secrets/fish.yml final.place 로 정하세요)");
                x = sx;
                y = spawn.getBlockY();
                z = sz;
                depth = 0;
            }
            eco.kv().set(KEY, Waters.encode(w.getName(), x, y, z, depth));
            eco.getLogger().info("[비밀] 눈 없는 물고기: 내가 빠진 곳 = " + place().map(Place::toString).orElse("?"));
            if (done != null) done.run();
        }, 1L, 1L);
    }

    /** Every column of a chunk within the circle: open water on top, how far down to the floor. */
    private static void scan(World w, Chunk ch, int sx, int sz, long rr, int[] best, double[] bestD) {
        if (!ch.isLoaded()) return;
        int bx = ch.getX() << 4, bz = ch.getZ() << 4;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = bx + dx, z = bz + dz;
                long ddx = x - sx, ddz = z - sz;
                long d2 = ddx * ddx + ddz * ddz;
                if (d2 > rr) continue;
                int top = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
                if (w.getBlockAt(x, top, z).getType() != Material.WATER) continue;
                int depth = top - w.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR);
                if (Waters.deeper(depth, d2, best[3], bestD[0])) {
                    best[0] = top;
                    best[1] = x;
                    best[2] = z;
                    best[3] = depth;
                    bestD[0] = d2;
                }
            }
        }
    }
}
