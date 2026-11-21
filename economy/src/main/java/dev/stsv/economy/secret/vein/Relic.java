package dev.stsv.economy.secret.vein;

import dev.stsv.economy.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 광맥의 심장 (the reward): held in either hand, the ores around the holder beat like a heart, glowing through the walls for
 * them alone. A held item rather than an accessory: an accessory effect would join the accessory boxes, drops and rerolls
 * (accessories.yml effects are all rollable), and the heart must stay the only one on the server.
 * <p>
 * Called once a second (one beat) from {@link Vein#tick}: every few beats it looks for ores again (main thread, a small
 * cube), and on each beat the shown ores swell and shrink.
 */
final class Relic {

    private final Vein vein;
    private final Map<UUID, List<BlockDisplay>> shown = new HashMap<>();
    private final Map<UUID, Integer> beats = new HashMap<>();

    Relic(Vein vein) {
        this.vein = vein;
    }

    static boolean ore(Material m) {
        return m == Material.ANCIENT_DEBRIS || m.name().endsWith("_ORE");
    }

    private static boolean precious(Material m) {
        return m == Material.ANCIENT_DEBRIS || m.name().contains("DIAMOND") || m.name().contains("EMERALD");
    }

    boolean holds(Player p) {
        String id = vein.cfg().getString("relic.item", "economy:vein_heart");
        return is(p.getInventory().getItemInMainHand(), id) || is(p.getInventory().getItemInOffHand(), id);
    }

    private static boolean is(ItemStack it, String id) {
        return it != null && !it.isEmpty() && it.getType() == Material.PAPER && Items.key(it).equals(id);
    }

    void tick() {
        for (UUID u : List.copyOf(shown.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !holds(p)) clear(u);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!holds(p)) continue;
            int n = beats.merge(p.getUniqueId(), 1, Integer::sum);
            int every = Math.max(1, vein.cfg().getInt("relic.rescan", 3));
            List<BlockDisplay> list = shown.get(p.getUniqueId());
            if (list == null || n % every == 1 || every == 1) scan(p);
            pulse(p);
        }
    }

    private void scan(Player p) {
        clear(p.getUniqueId());
        int r = Math.max(2, Math.min(24, vein.cfg().getInt("relic.radius", 12)));
        int max = Math.max(1, vein.cfg().getInt("relic.max", 60));
        World w = p.getWorld();
        Location at = p.getLocation();
        int cx = at.getBlockX(), cy = at.getBlockY(), cz = at.getBlockZ();
        int minY = Math.max(w.getMinHeight(), cy - r), maxY = Math.min(w.getMaxHeight() - 1, cy + r);
        List<int[]> found = new ArrayList<>();
        for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = minY; y <= maxY; y++) {
                int dx = x - cx, dy = y - cy, dz = z - cz;
                int d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > r * r) continue;
                Material m = w.getType(x, y, z);
                if (ore(m)) found.add(new int[] {x, y, z, m.ordinal(), d2});
            }
        }
        found.sort((a, b) -> Integer.compare(a[4], b[4]));
        Color glow = Vein.color(vein.cfg().getString("relic.glow", "#c22a2a"), Color.fromRGB(0xc22a2a));
        Material[] all = Material.values();
        List<BlockDisplay> list = new ArrayList<>();
        boolean rich = false;
        for (int[] f : found) {
            if (list.size() >= max) break;
            Material m = all[f[3]];
            rich |= precious(m);
            BlockDisplay d = vein.haunt().block(p, new Location(w, f[0], f[1], f[2]), m.createBlockData());
            d.setTransformation(Vein.centered(0.7f));
            d.setGlowing(true);
            d.setGlowColorOverride(glow);
            d.setBrightness(new Display.Brightness(15, 15));
            list.add(d);
        }
        shown.put(p.getUniqueId(), list);
        if (rich) vein.sound(p, p.getLocation(), "heartbeat", 0.25f, 0.7f);
    }

    private void pulse(Player p) {
        List<BlockDisplay> list = shown.get(p.getUniqueId());
        if (list == null || list.isEmpty()) return;
        for (BlockDisplay d : list) {
            if (!d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(2);
            d.setTransformation(Vein.centered(0.95f));
        }
        Bukkit.getScheduler().runTaskLater(vein.plugin(), () -> {
            for (BlockDisplay d : list) {
                if (!d.isValid()) continue;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(12);
                d.setTransformation(Vein.centered(0.7f));
            }
        }, 3L);
    }

    void clear(UUID u) {
        beats.remove(u);
        List<BlockDisplay> list = shown.remove(u);
        Player p = Bukkit.getPlayer(u);
        if (list == null) return;
        for (BlockDisplay d : list) {
            if (p != null) vein.haunt().remove(p, d);
            else if (d.isValid()) d.remove();
        }
    }

    void clearAll() {
        for (UUID u : List.copyOf(shown.keySet())) clear(u);
    }
}
