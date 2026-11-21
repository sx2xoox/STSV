package dev.stsv.economy.secret.letter;

import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Haunt;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The things that come apart, one more with every letter, only for the one player (letter.yml {@code omens}):
 * footsteps behind them when they stand still (1), their own name a second time under the tab list (2), one of their
 * things renamed "내 것" and someone calling their name in chat (3), themselves glimpsed at the edge of sight, gone when
 * looked at (4), and "(name)님이 들어왔습니다" when the last letter comes (5). The whole chain is one night, so the waits
 * shrink with every letter ({@code omens.pace}) and each new letter brings its newest omen soon after it arrives.
 * During the silence (letter 3) the calls close in: anyone at first, then the people they are closest to, then their
 * own name, typing to them.
 */
final class Omens {

    static final NamespacedKey MINE = new NamespacedKey("economy", "letter_mine");

    private final Letters s;
    private final SecretModule m;
    private final Map<UUID, Long> nextStep = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextGlimpse = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextCall = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> still = new ConcurrentHashMap<>();
    private final Map<UUID, Location> last = new ConcurrentHashMap<>();
    /** the tab footer line each player is shown now (to resend only on change) */
    private final Map<UUID, String> footer = new ConcurrentHashMap<>();
    private final Map<UUID, List<BukkitTask>> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, Mannequin> glimpse = new ConcurrentHashMap<>();

    Omens(Letters s, SecretModule m) {
        this.s = s;
        this.m = m;
    }

    private Haunt haunt() {
        return m.haunt();
    }

    /** A random wait (ms) from "omens.<what>.<key>" — or its "test-<key>" twin in test mode — shortened by letter n's pace. */
    private long every(UUID u, String what, String key, int n) {
        String base = "omens." + what + ".";
        List<Integer> r = m.test(u) ? s.cfg().getIntegerList(base + "test-" + key) : List.of();
        if (r.isEmpty()) r = s.cfg().getIntegerList(base + key);
        long w = LetterText.between(r, 120, ThreadLocalRandom.current()) * 1000L;
        return Math.max(3000, Math.round(w * Riddle.pace(s.cfg().getDoubleList("omens.pace"), n)));
    }

    /** A new letter came: its newest omen comes soon (not after a whole wait). */
    void arrived(Player p, int n) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        long soon = Math.max(3, s.cfg().getLong(m.test(u) ? "omens.test-first-after-seconds" : "omens.first-after-seconds", 25)) * 1000L;
        switch (n) {
            case 1 -> nextStep.put(u, now + soon);
            case 4 -> nextGlimpse.put(u, now + soon);
            default -> {
                nextStep.put(u, Math.min(nextStep.getOrDefault(u, Long.MAX_VALUE), now + soon * 2));
                if (n >= 3) nextCall.put(u, now + soon);
            }
        }
    }

    /** Each second, for someone holding letter n (1..5). */
    void tick(Player p, int n) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Location here = p.getLocation();
        Location was = last.put(u, here);
        boolean stillNow = was != null && was.getWorld() == here.getWorld() && was.distanceSquared(here) < 0.04;
        int st = stillNow ? still.merge(u, 1, Integer::sum) : 0;
        if (!stillNow) still.put(u, 0);
        if (s.finaleRunning(u)) return;

        if (n >= 1 && st >= s.cfg().getInt("omens.footsteps.still-seconds", 4)) {
            long at = nextStep.computeIfAbsent(u, k -> now + every(u, "footsteps", "every", n));
            if (now >= at) {
                footsteps(p);
                nextStep.put(u, now + every(u, "footsteps", "every", n));
            }
        }
        if (n >= 2) tab(p, n);
        if (n >= 3) {
            boolean quiet = s.silenceActive(u);
            double prog = s.silenceProgress(u);
            long at = nextCall.computeIfAbsent(u, k -> now + (quiet ? callEvery(u, prog) : every(u, "calls", "after-every", n)));
            if (now >= at) {
                call(p, quiet ? Riddle.callTier(prog) : 0);
                nextCall.put(u, now + (quiet ? callEvery(u, prog) : every(u, "calls", "after-every", n)));
            }
        }
        if (n >= 4 && !glimpse.containsKey(u)) {
            long at = nextGlimpse.computeIfAbsent(u, k -> now + every(u, "glimpse", "every", n));
            // no ground beside them (a cave wall, open water): try again a little later
            if (now >= at) nextGlimpse.put(u, glimpse(p) ? now + every(u, "glimpse", "every", n) : now + 5000);
        }
    }

    /** The wait between calls during the silence: from call-every at its start down to call-every-end as it runs out. */
    private long callEvery(UUID u, double progress) {
        String b = m.test(u) ? "rituals.silence.test-" : "rituals.silence.";
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long from = LetterText.between(s.cfg().getIntegerList(b + "call-every"), 50, r) * 1000L;
        long to = LetterText.between(s.cfg().getIntegerList(b + "call-every-end"), 12, r) * 1000L;
        return Riddle.callGap(progress, from, to);
    }

    /** The silence just began: the first call comes soon. */
    void silenceBegan(UUID u) {
        nextCall.put(u, System.currentTimeMillis() + callEvery(u, 0) / 2);
    }

    // ------------------------------------------------------------------------------------------
    // the omens
    // ------------------------------------------------------------------------------------------

    /** Four or five steps on whatever they stand on, coming up from behind and stopping just short. */
    void footsteps(Player p) {
        Block under = p.getLocation().getBlock().getRelative(BlockFace.DOWN);
        Sound step = under.getType().isAir() ? Sound.BLOCK_STONE_STEP : under.getBlockData().getSoundGroup().getStepSound();
        int n = 4 + ThreadLocalRandom.current().nextInt(2);
        for (int i = 0; i < n; i++) {
            final int k = i;
            later(p, 2 + i * 7L, () -> {
                double d = 3.4 - k * (2.2 / n);
                Location at = p.getLocation();
                Vector back = at.getDirection().setY(0);
                if (back.lengthSquared() < 1e-4) back = new Vector(0, 0, 1);
                at.subtract(back.normalize().multiply(d));
                haunt().sound(p, at, step, 0.35f + k * 0.08f, 0.85f + ThreadLocalRandom.current().nextFloat() * 0.1f);
            });
        }
    }

    /** Their name once more, under the tab list (a little wrong from letter 4). */
    void tab(Player p, int n) {
        UUID u = p.getUniqueId();
        String name = n >= 4 ? LetterText.drift(p.getName(), 1, s.seed(u)) : p.getName();
        String line = s.cfg().getString("omens.tab.line", "<white>{name}").replace("{name}", name);
        if (line.equals(footer.get(u))) return;
        footer.put(u, line);
        p.sendPlayerListFooter(Text.mm(line));
    }

    void untab(Player p) {
        if (footer.remove(p.getUniqueId()) != null) p.sendPlayerListFooter(Component.empty());
    }

    /**
     * Someone says their name in chat. Tier 0: anyone (someone online, else a name they know a little); tier 1: the people
     * they spend the most time with; tier 2: themselves — their own name, typing to them (with a breath behind them).
     * Each tier has its own lines ({@code omens.calls.lines}, {@code lines-near}, {@code lines-self}).
     */
    void call(Player p, int tier) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<String> friends = new ArrayList<>();
        for (String f : m.memory().friends(p.getUniqueId())) if (!f.equalsIgnoreCase(p.getName())) friends.add(f);
        List<String> anyone = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (o != p) anyone.add(o.getName());
        for (String f : friends) if (!anyone.contains(f)) anyone.add(f);
        String speaker;
        String key;
        if (tier >= 2 || anyone.isEmpty()) {
            speaker = p.getName();
            key = "lines-self";
            haunt().behind(p, Sound.ENTITY_PLAYER_BREATH, 0.35f, 0.6f);
        } else if (tier == 1 && !friends.isEmpty()) {
            speaker = friends.get(r.nextInt(Math.min(friends.size(), 2)));
            key = "lines-near";
        } else {
            speaker = anyone.get(r.nextInt(anyone.size()));
            key = "lines";
        }
        List<String> lines = s.cfg().getStringList("omens.calls." + key);
        if (lines.isEmpty()) lines = s.cfg().getStringList("omens.calls.lines");
        if (lines.isEmpty()) lines = List.of("{name}");
        String line = lines.get(r.nextInt(lines.size())).replace("{name}", p.getName());
        haunt().chat(p, speaker, line);
    }

    /**
     * Renames one of their harmless things "내 것" for real (an unstackable vanilla thing with no name of its own: a tool,
     * a weapon, armour — never a container, a book or a map, never something from the shop). False when there is none.
     */
    boolean rename(Player p) {
        List<ItemStack> pick = new ArrayList<>();
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (ItemStack it : inv) {
            if (it == null || it.isEmpty() || it.getMaxStackSize() != 1) continue;
            if (!Items.key(it).startsWith("minecraft:") || Items.shopBought(it) || Items.trial(it)) continue;
            Material t = it.getType();
            String k = t.getKey().getKey();
            if (k.contains("shulker_box") || k.contains("bundle") || k.contains("book") || k.contains("map") || t == Material.ELYTRA) continue;
            ItemMeta meta = it.getItemMeta();
            if (meta == null || meta.hasCustomName() || meta.getPersistentDataContainer().has(MINE)) continue;
            pick.add(it);
        }
        if (pick.isEmpty()) return false;
        Collections.shuffle(pick);
        ItemStack it = pick.getFirst();
        ItemMeta meta = it.getItemMeta();
        meta.customName(Text.mm(s.cfg().getString("omens.rename.name", "<gray>내 것")));
        meta.getPersistentDataContainer().set(MINE, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return true;
    }

    /** "(their name)님이 들어왔습니다", a while after the last letter came. */
    void joinLine(Player p) {
        long d = Math.max(1, s.cfg().getLong("omens.join-line-delay-seconds", 8)) * 20L;
        later(p, d, () -> haunt().leaveLine(p, p.getName(), true));
    }

    /**
     * Themselves, standing at the edge of their sight on the ground 10-16 blocks off, facing them; gone the moment they
     * look at it, or when they come close, or after a few seconds. False when no ground was found (try again soon).
     */
    boolean glimpse(Player p) {
        UUID u = p.getUniqueId();
        Random r = ThreadLocalRandom.current();
        List<Integer> dist = s.cfg().getIntegerList("omens.glimpse.distance");
        Location eye = p.getEyeLocation();
        Location at = null;
        for (int tries = 0; tries < 6 && at == null; tries++) {
            double side = (r.nextBoolean() ? 1 : -1) * (58 + r.nextInt(18));
            double yaw = Math.toRadians(eye.getYaw() + side);
            double d = LetterText.between(dist, 12, r);
            double x = eye.getX() - Math.sin(yaw) * d, z = eye.getZ() + Math.cos(yaw) * d;
            at = ground(p.getWorld(), x, p.getLocation().getBlockY(), z);
        }
        if (at == null) return false;
        Vector look = p.getLocation().toVector().subtract(at.toVector());
        at.setDirection(look);
        at.setPitch(0);
        Mannequin twin = haunt().likeness(p, p, at, null);
        twin.setBodyYaw(at.getYaw());
        glimpse.put(u, twin);
        long life = Math.max(2, s.cfg().getLong("omens.glimpse.seconds", 8)) * 20L;
        int[] age = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = Bukkit.getScheduler().runTaskTimer(s.plugin(), () -> {
            age[0] += 2;
            boolean gone = !p.isOnline() || !twin.isValid() || twin.getWorld() != p.getWorld() || age[0] >= life;
            if (!gone) {
                Vector to = twin.getLocation().add(0, 1.4, 0).toVector().subtract(p.getEyeLocation().toVector());
                gone = to.lengthSquared() < 16 || p.getEyeLocation().getDirection().angle(to) < Math.toRadians(24);
            }
            if (gone) {
                if (p.isOnline()) haunt().remove(p, twin);
                else if (twin.isValid()) twin.remove();
                glimpse.remove(u, twin);
                self[0].cancel();
            }
        }, 2L, 2L);
        keep(u, self[0]);
        return true;
    }

    /** Standing room on solid ground near (x, y, z): two free blocks over a solid one, searched a few blocks up and down. */
    static Location ground(org.bukkit.World w, double x, int y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int dy = 0; dy <= 6; dy++) {
            for (int sgn : new int[]{1, -1}) {
                int yy = y + dy * sgn;
                Block feet = w.getBlockAt(bx, yy, bz);
                if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(BlockFace.UP).isPassable()
                        && feet.getRelative(BlockFace.DOWN).getType().isSolid()) {
                    return new Location(w, bx + 0.5, yy, bz + 0.5);
                }
            }
        }
        return null;
    }

    private void later(Player p, long ticks, Runnable r) {
        BukkitTask t = Bukkit.getScheduler().runTaskLater(s.plugin(), () -> {
            if (p.isOnline()) r.run();
        }, ticks);
        keep(p.getUniqueId(), t);
    }

    private void keep(UUID u, BukkitTask t) {
        List<BukkitTask> l = tasks.computeIfAbsent(u, k -> new ArrayList<>());
        l.removeIf(BukkitTask::isCancelled);
        l.add(t);
    }

    /** Forgets everything in memory and takes the shown things away (the renamed item stays renamed). */
    void cleanup(UUID u) {
        List<BukkitTask> l = tasks.remove(u);
        if (l != null) l.forEach(BukkitTask::cancel);
        Mannequin g = glimpse.remove(u);
        Player p = Bukkit.getPlayer(u);
        if (g != null) {
            if (p != null) haunt().remove(p, g);
            else if (g.isValid()) g.remove();
        }
        if (p != null) untab(p);
        footer.remove(u);
        nextStep.remove(u);
        nextGlimpse.remove(u);
        nextCall.remove(u);
        still.remove(u);
        last.remove(u);
    }
}
