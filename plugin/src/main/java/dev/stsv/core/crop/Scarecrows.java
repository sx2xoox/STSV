package dev.stsv.core.crop;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.furniture.FurnitureBreakEvent;
import dev.stsv.core.furniture.FurniturePlaceEvent;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * 허수아비 (stsv:scarecrow, a 후원 상점 item): crops in its chunk grow with nobody near, as if a player stood there.
 * Nothing is kept loaded: custom crops (CropModule) grow in the plugin's own state while their chunk is unloaded; vanilla
 * crops (real blocks) catch up when the chunk loads again, for all the time it was away (no limit; the time away is
 * clock time, kept across restarts).
 * One a chunk. Where it may stand (its owner's land) is the content plugin's rule ({@link #rule}); the economy plugin
 * puts it there. Kept in data/scarecrows.yml (furniture lives in loaded chunks only, so the list is its own).
 */
public final class Scarecrows implements Listener {

    public static final String ITEM = "stsv:scarecrow";
    /** blocks above and below the scarecrow searched for vanilla crops to catch up */
    private static final int REACH_Y = 6;

    /** A scarecrow: where it stands and whose it is. */
    public record Scarecrow(String world, int x, int y, int z, UUID owner) {
        public int cx() { return x >> 4; }
        public int cz() { return z >> 4; }
    }

    private final STSVPlugin plugin;
    private final File file;
    private final Map<String, Scarecrow> byChunk = new HashMap<>();
    /** chunk key -> when it went away (epoch millis); saved, so a restart does not lose it */
    private final Map<String, Long> awaySince = new HashMap<>();
    private volatile Predicate<Scarecrow> rule = s -> true;

    Scarecrows(STSVPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/scarecrows.yml");
        load();
    }

    static String key(String world, int cx, int cz) {
        return world + ";" + cx + ";" + cz;
    }

    /** Where a scarecrow may work (the economy: its owner's land). One that breaks the rule stands idle. */
    public void rule(Predicate<Scarecrow> rule) {
        this.rule = rule == null ? s -> true : rule;
    }

    /** The working scarecrow of a chunk, or null. */
    public Scarecrow at(String world, int cx, int cz) {
        Scarecrow s = byChunk.get(key(world, cx, cz));
        return s != null && rule.test(s) ? s : null;
    }

    public Collection<Scarecrow> all() {
        return List.copyOf(byChunk.values());
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String line : y.getStringList("scarecrows")) {
            String[] p = line.split(";");
            try {
                Scarecrow s = new Scarecrow(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), UUID.fromString(p[4]));
                byChunk.put(key(s.world(), s.cx(), s.cz()), s);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("[허수아비] 읽지 못한 줄: " + line);
            }
        }
        for (String line : y.getStringList("away")) {
            int i = line.lastIndexOf(';');
            try {
                if (i > 0) awaySince.put(line.substring(0, i), Long.parseLong(line.substring(i + 1)));
            } catch (NumberFormatException ignored) { }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> lines = new ArrayList<>();
        for (Scarecrow s : byChunk.values()) lines.add(String.join(";", s.world(), "" + s.x(), "" + s.y(), "" + s.z(), s.owner().toString()));
        y.set("scarecrows", lines);
        List<String> away = new ArrayList<>();
        awaySince.forEach((k, t) -> { if (byChunk.containsKey(k)) away.add(k + ";" + t); });
        y.set("away", away);
        try {
            file.getParentFile().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[허수아비] 저장 실패", e);
        }
    }

    private static boolean isScarecrow(dev.stsv.core.furniture.Furniture f) {
        return f.item().key().asString().equals(ITEM);
    }

    /** One a chunk. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceCheck(FurniturePlaceEvent e) {
        if (!isScarecrow(e.getFurniture())) return;
        Location l = e.getFurniture().location();
        if (byChunk.containsKey(key(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4))) {
            e.setCancelled(true);
            HudModule.flash(e.getPlayer(), Lang.mm("crop.scarecrow-one"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(FurniturePlaceEvent e) {
        if (!isScarecrow(e.getFurniture())) return;
        Location l = e.getFurniture().location();
        Scarecrow s = new Scarecrow(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), e.getPlayer().getUniqueId());
        byChunk.put(key(s.world(), s.cx(), s.cz()), s);
        save();
        HudModule.flash(e.getPlayer(), Lang.mm("crop.scarecrow-placed"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent e) {
        if (!isScarecrow(e.getFurniture())) return;
        Location l = e.getFurniture().location();
        String k = key(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
        awaySince.remove(k);
        if (byChunk.remove(k) != null) save();
    }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        String k = key(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ());
        if (!byChunk.containsKey(k)) return;
        awaySince.put(k, System.currentTimeMillis());
        save();
    }

    /** Server stop: every scarecrow chunk still open goes away now, so the next start catches up for the downtime. */
    void shutdown() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Scarecrow> en : byChunk.entrySet()) {
            Scarecrow s = en.getValue();
            World w = Bukkit.getWorld(s.world());
            if (w != null && w.isChunkLoaded(s.cx(), s.cz())) awaySince.putIfAbsent(en.getKey(), now);
        }
        save();
    }

    /** Vanilla crops near the scarecrow catch up for the time the chunk was away (after the load, a tick later). */
    @EventHandler
    public void onLoad(ChunkLoadEvent e) {
        String k = key(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ());
        Long since = awaySince.remove(k);
        if (since != null) save();
        Scarecrow s = since == null ? null : at(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ());
        if (s == null) return;
        long ticks = (System.currentTimeMillis() - since) / 50;   // clock time, as game ticks
        if (ticks <= 0) return;
        Chunk c = e.getChunk();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (c.isLoaded()) catchUp(c, s, ticks);
        });
    }

    private void catchUp(Chunk c, Scarecrow s, long ticks) {
        World w = c.getWorld();
        int speed = randomTickSpeed(w);
        Random r = ThreadLocalRandom.current();
        int minY = Math.max(w.getMinHeight(), s.y() - REACH_Y), maxY = Math.min(w.getMaxHeight() - 1, s.y() + REACH_Y);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block b = c.getBlock(x, y, z);
                    if (!Tag.CROPS.isTagged(b.getType()) || !(b.getBlockData() instanceof Ageable age) || age.getAge() >= age.getMaximumAge()) continue;
                    if (b.getLightLevel() < 9 && b.getLightFromSky() < 9) continue;
                    int n = CropGrowth.randomTicks(ticks, speed, r);
                    if (n == 0) continue;
                    int grown = CropGrowth.grow(age.getAge(), age.getMaximumAge(), n, CropGrowth.chance(speed(b)), r);
                    if (grown == age.getAge()) continue;
                    age.setAge(grown);
                    b.setBlockData(age, false);
                }
            }
        }
    }

    /** The vanilla growth speed of a crop block (CropGrowth.speed: the farmland under it, the same crop beside it). */
    private static double speed(Block b) {
        int[][] soil = new int[3][3];
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Block under = b.getRelative(dx, -1, dz);
            soil[dx + 1][dz + 1] = under.getBlockData() instanceof Farmland f ? (f.getMoisture() > 0 ? 2 : 1) : 0;
        }
        Material m = b.getType();
        boolean ns = b.getRelative(0, 0, -1).getType() == m || b.getRelative(0, 0, 1).getType() == m;
        boolean we = b.getRelative(-1, 0, 0).getType() == m || b.getRelative(1, 0, 0).getType() == m;
        boolean diag = b.getRelative(-1, 0, -1).getType() == m || b.getRelative(1, 0, -1).getType() == m
                || b.getRelative(1, 0, 1).getType() == m || b.getRelative(-1, 0, 1).getType() == m;
        return CropGrowth.speed(soil, ns, we, diag);
    }

    private static int randomTickSpeed(World w) {
        try {
            Integer v = w.getGameRuleValue(org.bukkit.GameRules.RANDOM_TICK_SPEED);
            return v == null ? 3 : v;
        } catch (RuntimeException e) {
            return 3;
        }
    }
}
