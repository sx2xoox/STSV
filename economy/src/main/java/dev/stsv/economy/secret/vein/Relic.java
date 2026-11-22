package dev.stsv.economy.secret.vein;

import dev.stsv.core.util.Text;
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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

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
 * <p>
 * Bound to the one who stopped the heart ({@link Vein#HEART_OWNER}): it beats only in their hands, and if anyone else ends up
 * holding it (a trade, a chest, a pickup, the mail) it crumbles in their hands and goes back to its owner — into their bag if
 * they are on, else (or if it is full) into their mailbox. Vein keeps it from being dropped or lost (see its events).
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

    private String id() {
        return vein.cfg().getString("relic.item", "economy:vein_heart");
    }

    /** Holds their own heart in either hand (someone else's does nothing for them). */
    boolean holds(Player p) {
        String id = id();
        UUID u = p.getUniqueId();
        ItemStack main = p.getInventory().getItemInMainHand(), off = p.getInventory().getItemInOffHand();
        return (is(main, id) && worksFor(owner(main), u)) || (is(off, id) && worksFor(owner(off), u));
    }

    private static boolean is(ItemStack it, String id) {
        return it != null && !it.isEmpty() && it.getType() == Material.PAPER && Items.key(it).equals(id);
    }

    /** Whether this stack is a 광맥의 심장. */
    boolean heart(ItemStack it) {
        return is(it, id());
    }

    /** The uuid (as written) of the one who stopped it, or null. */
    static String owner(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Vein.HEART_OWNER, PersistentDataType.STRING);
    }

    /** It beats only for the one who stopped it. */
    static boolean worksFor(String owner, UUID holder) {
        return owner != null && holder != null && owner.equals(holder.toString());
    }

    /** In someone else's keeping: it goes home. One without an owner (should not exist) is left alone. */
    static boolean stray(String owner, UUID holder) {
        return owner != null && holder != null && !owner.equals(holder.toString());
    }

    /** Every second (with the beat): a heart in the wrong hands crumbles and goes back to its owner. */
    void sweep() {
        String id = id();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (!is(it, id) || !stray(owner(it), u)) continue;
                inv.setItem(i, null);
                home(p, it);
            }
            ItemStack cur = p.getItemOnCursor();
            if (is(cur, id) && stray(owner(cur), u)) {
                p.setItemOnCursor(null);
                home(p, cur);
            }
        }
    }

    /** Crumbles in {@code from}'s hands (null: nobody saw it) and is back with its owner. */
    void home(Player from, ItemStack it) {
        UUID owner;
        try {
            owner = UUID.fromString(owner(it));
        } catch (RuntimeException e) {
            return;
        }
        if (from != null) {
            String t = vein.cfg().getString("relic.crumble", "");
            if (!t.isEmpty()) vein.haunt().whisper(from, t);
            vein.sound(from, from.getLocation(), "crumble", 1, 1.1f);
            from.spawnParticle(org.bukkit.Particle.DUST, from.getLocation().add(0, 1, 0), 18, 0.3, 0.4, 0.3, 0,
                    new org.bukkit.Particle.DustOptions(Vein.color(vein.cfg().getString("relic.glow", "#c22a2a"), Color.RED), 1.2f));
        }
        Player o = Bukkit.getPlayer(owner);
        if (o != null) {
            for (ItemStack left : o.getInventory().addItem(it).values()) mail(owner, left);
            String t = vein.cfg().getString("relic.returned", "");
            if (!t.isEmpty()) o.sendMessage(Text.msg(t));
        } else mail(owner, it);
        vein.plugin().getLogger().info("[비밀] 광맥의 심장: " + (from == null ? "주인 없는 자리" : from.getName()) + " → 주인 " + owner + " 에게 돌아감");
    }

    private void mail(UUID owner, ItemStack it) {
        vein.plugin().mail().store().send(owner, Items.bytes(it), "secret_vein", System.currentTimeMillis());
    }

    void tick() {
        sweep();
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
