package dev.stsv.economy.accessory;

import dev.stsv.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 광맥 투시: while a player sneaks, the ores within the accessory's radius glow through the walls for them alone (block displays
 * only they see, coloured by the ore), refreshed every 1.5 s. The scan reads chunk snapshots off the main thread. From grade A the
 * action bar points at the nearest diamond and emerald, from S the nearest ancient debris too.
 */
final class VeinSight {

    private static final int MAX_SHOWN = 80;
    private static final Map<Material, Color> ORES = new HashMap<>();

    static {
        put(Color.fromRGB(0x3a3a3a), Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE);
        put(Color.fromRGB(0xd8a07a), Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE);
        put(Color.fromRGB(0xe07a4a), Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE);
        put(Color.fromRGB(0xffd700), Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE);
        put(Color.fromRGB(0xff2a2a), Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE);
        put(Color.fromRGB(0x2a5aff), Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE);
        put(Color.fromRGB(0x5affff), Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE);
        put(Color.fromRGB(0x2aff6a), Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
        put(Color.fromRGB(0xf0f0f0), Material.NETHER_QUARTZ_ORE);
        put(Color.fromRGB(0x8a5a3a), Material.ANCIENT_DEBRIS);
    }

    private static void put(Color c, Material... ms) {
        for (Material m : ms) ORES.put(m, c);
    }

    private final AccessoryModule acc;
    private final Map<UUID, List<BlockDisplay>> shown = new HashMap<>();
    private final java.util.Set<UUID> scanning = java.util.concurrent.ConcurrentHashMap.newKeySet();

    VeinSight(AccessoryModule acc) {
        this.acc = acc;
    }

    void tick() {
        for (UUID u : List.copyOf(shown.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isSneaking()) clear(u);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            double r = acc.value(p.getUniqueId(), "vein_sight");
            if (r <= 0 || !p.isSneaking() || !scanning.add(p.getUniqueId())) continue;
            scan(p, (int) Math.min(48, r));
        }
    }

    private void scan(Player p, int r) {
        World w = p.getWorld();
        Location at = p.getLocation();
        int cx = at.getBlockX(), cy = at.getBlockY(), cz = at.getBlockZ();
        Map<Long, ChunkSnapshot> snaps = new HashMap<>();
        for (int x = (cx - r) >> 4; x <= (cx + r) >> 4; x++) for (int z = (cz - r) >> 4; z <= (cz + r) >> 4; z++) {
            if (w.isChunkLoaded(x, z)) snaps.put(((long) x << 32) ^ (z & 0xffffffffL), w.getChunkAt(x, z).getChunkSnapshot(false, false, false));
        }
        int minY = Math.max(w.getMinHeight(), cy - r), maxY = Math.min(w.getMaxHeight() - 1, cy + r);
        Bukkit.getScheduler().runTaskAsynchronously(acc.eco(), () -> {
            List<int[]> found = new ArrayList<>();
            for (ChunkSnapshot c : snaps.values()) {
                int bx = c.getX() << 4, bz = c.getZ() << 4;
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    int wx = bx + x, wz = bz + z;
                    if (Math.abs(wx - cx) > r || Math.abs(wz - cz) > r) continue;
                    for (int y = minY; y <= maxY; y++) {
                        Material m = c.getBlockType(x, y, z);
                        if (!ORES.containsKey(m)) continue;
                        int dx = wx - cx, dy = y - cy, dz = wz - cz;
                        if (dx * dx + dy * dy + dz * dz <= r * r) found.add(new int[] {wx, y, wz, m.ordinal(), dx * dx + dy * dy + dz * dz});
                    }
                }
            }
            found.sort((a, b) -> Integer.compare(a[4], b[4]));
            Bukkit.getScheduler().runTask(acc.eco(), () -> {
                scanning.remove(p.getUniqueId());
                if (p.isOnline() && p.isSneaking()) show(p, found);
            });
        });
    }

    private void show(Player p, List<int[]> found) {
        clear(p.getUniqueId());
        World w = p.getWorld();
        List<BlockDisplay> list = new ArrayList<>();
        Material[] all = Material.values();
        int[] nearestDiamond = null, nearestEmerald = null, nearestDebris = null;
        for (int[] f : found) {
            Material m = all[f[3]];
            if (nearestDiamond == null && (m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE)) nearestDiamond = f;
            if (nearestEmerald == null && (m == Material.EMERALD_ORE || m == Material.DEEPSLATE_EMERALD_ORE)) nearestEmerald = f;
            if (nearestDebris == null && m == Material.ANCIENT_DEBRIS) nearestDebris = f;
            if (list.size() >= MAX_SHOWN) continue;
            Location l = new Location(w, f[0] + 0.05, f[1] + 0.05, f[2] + 0.05);
            BlockDisplay d = w.spawn(l, BlockDisplay.class, e -> {
                e.setBlock(m.createBlockData());
                e.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                        new org.joml.Vector3f(0.9f, 0.9f, 0.9f), new org.joml.AxisAngle4f()));
                e.setGlowing(true);
                e.setGlowColorOverride(ORES.get(m));
                e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                e.setVisibleByDefault(false);
                e.setPersistent(false);
            });
            p.showEntity(acc.eco(), d);
            list.add(d);
        }
        shown.put(p.getUniqueId(), list);
        String grade = bestGrade(p.getUniqueId());
        List<String> parts = new ArrayList<>();
        if (grade != null && !grade.equals("common") && !grade.equals("fine")) {
            if (nearestDiamond != null) parts.add("<aqua>다이아 " + direction(p, nearestDiamond));
            if (nearestEmerald != null) parts.add("<green>에메랄드 " + direction(p, nearestEmerald));
        }
        if ("treasure".equals(grade) && nearestDebris != null) parts.add("<#c08a5a>고대 잔해 " + direction(p, nearestDebris));
        if (!parts.isEmpty()) p.sendActionBar(Text.item(String.join(" <dark_gray>· ", parts)));
    }

    /** The grade of the worn 광맥 투시 accessory. */
    private String bestGrade(UUID u) {
        for (var it : acc.store().worn(u)) {
            Accessory a = AccessoryModule.read(it);
            if (a != null && a.effect.equals("vein_sight")) return a.grade;
        }
        return null;
    }

    private static String direction(Player p, int[] f) {
        Location l = p.getLocation();
        double dx = f[0] + 0.5 - l.getX(), dz = f[2] + 0.5 - l.getZ(), dy = f[1] - l.getY();
        double ang = Math.toDegrees(Math.atan2(-dx, dz)) - l.getYaw();
        ang = ((ang % 360) + 540) % 360 - 180;
        String arrow = Math.abs(ang) < 22.5 ? "↑" : Math.abs(ang) > 157.5 ? "↓" : ang > 0 ? (ang < 67.5 ? "↗" : ang < 112.5 ? "→" : "↘")
                : (ang > -67.5 ? "↖" : ang > -112.5 ? "←" : "↙");
        return "<white>" + arrow + " " + Math.round(Math.sqrt(dx * dx + dz * dz + dy * dy)) + "칸" + (dy > 2 ? " 위" : dy < -2 ? " 아래" : "");
    }

    private void clear(UUID u) {
        List<BlockDisplay> list = shown.remove(u);
        if (list != null) list.forEach(BlockDisplay::remove);
    }

    void clear() {
        for (UUID u : List.copyOf(shown.keySet())) clear(u);
    }
}
