package dev.stsv.economy.secret.vein;

import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.secret.Haunt;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 속삭이는 광맥 (docs/admin/숨은_비밀/속삭이는_광맥.md; contents/economy/secrets/vein.yml). Deep miners said the vein breathes.
 * It was a heart, and it is still beating.
 * <p>
 * Stage 1 (trail): very rarely, mining an ore deep down, a player hears "…더 깊이…" from inside the stone. A trail of
 * impossible ores (fake blocks only they see, on real rock) leads down through solid stone; only the next one exists, and the
 * only guide is its heartbeat, played from its position (faster and louder when close). Breaking the rock under it reveals
 * the next. A light placed nearby wipes the trail ("…눈부셔.") and it starts over from the start point on a new path.
 * Stage 2 (heart): the last ore gives way to a beating heart stone (a block display). Struck on the beat 13 times
 * ({@link Rhythm}) it stops: silence, then the player's own heartbeat, "…이제 네가 뛰어." — solved, and the one 광맥의 심장
 * ({@link Relic}), bound to them. Three misses: the cave collapses and the trail starts over from the start point (same seed).
 * <p>
 * The echoing pickaxe and the miner at the edge of sight are the ones who followed the sound before and became the vein; they
 * hate light because it is what they never saw again. Stopping the heart lets them stop: "…우리 대신." "…이제 네가 뛰어."
 * <p>
 * Saved (eco.kv "secret.vein.&lt;uuid&gt;."): stage, start ("world x y z"), seed, attempt, heard (the day it last spoke),
 * heart ("world x y z" while at stage 2), collapses, lights. The heart's holder: "secret.vein.heart". The trail itself is
 * only in memory: after a quit or restart it starts again when they come back near the start point.
 */
public final class Vein extends Secret {

    /** On the reward: who stopped the heart. */
    public static final NamespacedKey HEART_OWNER = new NamespacedKey("economy", "vein_heart_owner");
    /** On a tester's reward. */
    public static final NamespacedKey HEART_TEST = new NamespacedKey("economy", "vein_heart_test");

    private enum Phase { TRAIL, HEART, COLLAPSE, END }

    /** REAL = the quest; PRACTICE = admin 박자 (judged, reported, nothing follows); SHOW = admin 심장원석 (only beats). */
    private enum Mode { REAL, PRACTICE, SHOW }

    /** One player's live trail or heart. */
    private final class Run {
        final UUID u;
        final Mode mode;
        String world;
        VeinPath.Pos start;
        long seed;
        int attempt;
        int length;
        final List<VeinPath.Pos> nodes = new ArrayList<>();
        int index;
        int[] heading;
        BlockData fake;
        Phase phase = Phase.TRAIL;
        int beatIn = 20;
        long lastEcho;
        BukkitTask task;
        BlockDisplay heart;
        VeinPath.Pos heartAt;
        Rhythm rhythm;
        CommandSender reportTo;
        int showTicks;
        final List<org.bukkit.entity.Entity> things = new ArrayList<>();

        Run(UUID u, Mode mode) {
            this.u = u;
            this.mode = mode;
        }

        VeinPath.Pos target() {
            return phase == Phase.TRAIL && index < nodes.size() ? nodes.get(index) : null;
        }
    }

    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    /** Players whose trail an admin put away (정리): it does not come back by itself until they rejoin. */
    private final Set<UUID> paused = ConcurrentHashMap.newKeySet();
    private final Relic relic;
    private final Set<Material> rock = EnumSet.noneOf(Material.class);
    private final Set<Material> deep = EnumSet.noneOf(Material.class);

    public Vein(SecretModule m) {
        super(m);
        relic = new Relic(this);
    }

    @Override
    public String id() {
        return "vein";
    }

    @Override
    public String name() {
        return "속삭이는광맥";
    }

    /** 1 = the trail, 2 = the heart. */
    @Override
    public int stages() {
        return 2;
    }

    @Override
    protected void loaded() {
        rock.clear();
        deep.clear();
        for (String s : cfg.getStringList("trail.rock")) Optional.ofNullable(Material.matchMaterial(s)).ifPresent(rock::add);
        for (String s : cfg.getStringList("trail.deep-rock")) Optional.ofNullable(Material.matchMaterial(s)).ifPresent(deep::add);
        if (rock.isEmpty()) rock.addAll(List.of(Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.ANDESITE, Material.DIORITE, Material.GRANITE));
        if (deep.isEmpty()) deep.addAll(List.of(Material.DEEPSLATE, Material.TUFF));
    }

    // ------------------------------------------------------------------------------------------
    // small things (also for Relic)
    // ------------------------------------------------------------------------------------------

    YamlConfiguration cfg() {
        return cfg;
    }

    Haunt haunt() {
        return m.haunt();
    }

    EcoPlugin plugin() {
        return eco;
    }

    private String text(String key) {
        return cfg.getString("text." + key, "");
    }

    private void whisper(Player p, String key) {
        String t = text(key);
        if (!t.isEmpty()) m.haunt().whisper(p, t);
    }

    /** A sound from vein.yml's sounds for one player, from a spot (volume and pitch × the given factors). */
    void sound(Player p, Location at, String key, float vol, float pitch) {
        String s = cfg.getString("sounds." + key + ".sound");
        if (s == null || at == null) return;
        p.playSound(at, s, SoundCategory.AMBIENT, (float) cfg.getDouble("sounds." + key + ".volume", 1) * vol,
                (float) Math.max(0.5, Math.min(2.0, cfg.getDouble("sounds." + key + ".pitch", 1) * pitch)));
    }

    static Color color(String hex, Color def) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16));
        } catch (RuntimeException e) {
            return def;
        }
    }

    /** A cube of size s centred in its block. */
    static Transformation centered(float s) {
        float o = (1 - s) / 2;
        return new Transformation(new Vector3f(o, o, o), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
    }

    private static BlockData data(String s, Material def) {
        try {
            return Bukkit.createBlockData(s);
        } catch (RuntimeException e) {
            return def.createBlockData();
        }
    }

    private Location loc(World w, VeinPath.Pos p) {
        return new Location(w, p.x(), p.y(), p.z());
    }

    private Location center(World w, VeinPath.Pos p) {
        return new Location(w, p.x() + 0.5, p.y() + 0.5, p.z() + 0.5);
    }

    private static VeinPath.Pos pos(Location l) {
        return new VeinPath.Pos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    private static String place(World w, VeinPath.Pos p) {
        return w.getName() + " " + p.encode();
    }

    private static String worldOf(String place) {
        return place == null ? null : place.trim().split("\\s+")[0];
    }

    private void later(long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(eco, r, ticks);
    }

    private int stage(UUID u) {
        return m.stage(this, u);
    }

    // ------------------------------------------------------------------------------------------
    // the start: a whisper from inside the stone
    // ------------------------------------------------------------------------------------------

    static boolean ore(Material m) {
        return m.name().endsWith("_ORE") && !m.name().startsWith("NETHER_");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!on()) return;
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        Block b = e.getBlock();
        Run run = runs.get(u);
        if (run != null && run.mode == Mode.REAL) {
            if (run.phase == Phase.TRAIL && b.getWorld().getName().equals(run.world)) {
                VeinPath.Pos at = pos(b.getLocation());
                if (at.equals(run.target())) {
                    e.setDropItems(false);
                    e.setExpToDrop(0);
                    broken(run, p, at);
                } else {
                    echo(run, p, b);
                }
            }
            return;
        }
        if (!ore(b.getType()) || b.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        boolean test = m.test(u);
        int below = test ? cfg.getInt("start.test-below-y", 40) : cfg.getInt("start.below-y", 0);
        if (b.getY() >= below || !open(u)) return;
        int st = stage(u);
        if (st > stages()) return;
        if (m.today(u).equals(m.get(this, u, "heard"))) return;
        if (!m.roll(u, cfg.getDouble("start.chance", 0.00005))) return;
        VeinPath.Pos at = pos(b.getLocation());
        // after this event the ore is gone: speak from the stone beyond it on the next tick
        later(1L, () -> {
            if (!p.isOnline()) return;
            if (st == 0) begin(p, b.getWorld(), at, true, "start");
            else begin(p, b.getWorld(), at, false, "relocate");   // a trail far from here: the vein found them again
        });
    }

    /**
     * Starts (fresh = a new seed) or moves the trail to start at a block. Remembers the day (one start a day).
     */
    private void begin(Player p, World w, VeinPath.Pos at, boolean fresh, String line) {
        UUID u = p.getUniqueId();
        stop(u);
        paused.add(u);   // no "resume" while the whisper plays
        long seed = fresh || m.get(this, u, "seed") == null ? ThreadLocalRandom.current().nextLong() : m.getLong(this, u, "seed", 1);
        int attempt = fresh ? 0 : (int) m.getLong(this, u, "attempt", 0) + 1;
        m.set(this, u, "seed", seed);
        m.set(this, u, "attempt", attempt);
        m.set(this, u, "start", place(w, at));
        m.set(this, u, "heard", m.today(u));
        m.remove(this, u, "heart");
        m.stage(this, u, 1);
        whisper(p, line);
        // a faint heartbeat from inside the stone, a few blocks on the way they were digging
        Vector dir = p.getLocation().getDirection().setY(Math.min(0, p.getLocation().getDirection().getY()));
        dir = (dir.lengthSquared() < 0.01 ? new Vector(0, -1, 0) : dir.normalize()).multiply(3);   // looking straight up: below them
        Location in = center(w, at).add(dir);
        sound(p, in, "whisper", 1, 1);
        later(10L, () -> sound(p, in, "heartbeat", 0.45f, 0.6f));
        later(30L, () -> sound(p, in, "heartbeat", 0.4f, 0.58f));
        later(60L, () -> {
            paused.remove(u);
            if (p.isOnline() && stage(u) == 1 && !runs.containsKey(u)) trail(p);
        });
    }

    @Override
    public void start(Player p) {
        begin(p, p.getWorld(), pos(p.getLocation()), true, "start");
    }

    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        stop(u);
        if (stage <= 0) {
            m.stage(this, u, 0);
            return;
        }
        if (stage == 1) {
            begin(p, p.getWorld(), pos(p.getLocation()), m.get(this, u, "seed") == null, "start");
            return;
        }
        if (m.get(this, u, "seed") == null) m.set(this, u, "seed", ThreadLocalRandom.current().nextLong());
        if (m.get(this, u, "start") == null) m.set(this, u, "start", place(p.getWorld(), pos(p.getLocation())));
        VeinPath.Pos at = front(p);
        m.set(this, u, "heart", place(p.getWorld(), at));
        m.stage(this, u, 2);
        Run run = newRun(p, Mode.REAL);
        if (run != null) heart(run, p, at, false);
    }

    /** An air block about two blocks in front of their eyes (for a heart put there by hand). */
    private VeinPath.Pos front(Player p) {
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection().normalize();
        for (double k = 2.5; k >= 1.0; k -= 0.5) {
            Location l = eye.clone().add(d.clone().multiply(k));
            if (l.getBlock().isPassable()) return pos(l);
        }
        return pos(eye.clone().add(d.multiply(1.2)));
    }

    // ------------------------------------------------------------------------------------------
    // the trail
    // ------------------------------------------------------------------------------------------

    /** A run from what is saved (start, seed, attempt); null when nothing is saved. */
    private Run newRun(Player p, Mode mode) {
        UUID u = p.getUniqueId();
        String start = m.get(this, u, "start");
        Optional<VeinPath.Pos> sp = VeinPath.Pos.decode(start);
        if (sp.isEmpty()) return null;
        Run run = new Run(u, mode);
        run.world = worldOf(start);
        run.start = sp.get();
        run.seed = m.getLong(this, u, "seed", 1);
        run.attempt = (int) m.getLong(this, u, "attempt", 0);
        int[] len = range("trail.length", 20, 30);
        run.length = Sense.length(run.seed, len[0], len[1], m.test(u), cfg.getInt("trail.test-length", 8));
        runs.put(u, run);
        run.task = Bukkit.getScheduler().runTaskTimer(eco, () -> frame(run), 1L, 1L);
        return run;
    }

    private int[] range(String key, int a, int b) {
        List<Integer> l = cfg.getIntegerList(key);
        return l.size() >= 2 ? new int[] {l.get(0), l.get(1)} : new int[] {a, b};
    }

    /** Starts the trail from its first ore. */
    private void trail(Player p) {
        Run run = newRun(p, Mode.REAL);
        if (run == null) return;
        run.index = 0;
        if (!advance(run, p)) run.beatIn = -100;   // no room yet: tick tries again each second
    }

    /** May a trail ore sit in this block? Rock, its chunk loaded, no water or lava touching it, land they may dig. */
    private boolean fits(World w, Player p, Run run, VeinPath.Pos c) {
        if (!w.isChunkLoaded(c.x() >> 4, c.z() >> 4)) return false;
        Block b = w.getBlockAt(c.x(), c.y(), c.z());
        if (!rock.contains(b.getType())) return false;
        for (BlockFace f : new BlockFace[] {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = b.getRelative(f);
            if (n.isLiquid() || n.getType() == Material.LAVA || n.getType() == Material.WATER) return false;
        }
        for (VeinPath.Pos o : run.nodes) if (o.equals(c)) return false;
        return eco.land() == null || eco.land().allowed(p, b.getLocation());
    }

    /** Lays the ore at run.index (from the start or the ore before it) and shows it. False when the rock gives no room. */
    private boolean advance(Run run, Player p) {
        World w = Bukkit.getWorld(run.world);
        if (w == null) return false;
        while (run.nodes.size() > run.index) run.nodes.removeLast();
        VeinPath.Pos from = run.index == 0 ? run.start : run.nodes.get(run.index - 1);
        int[] step = range("trail.step", 4, 7);
        int minY = w.getMinHeight() + Math.max(1, cfg.getInt("trail.floor-margin", 6));
        Optional<VeinPath.Step> s = VeinPath.next(run.seed, run.attempt, run.index, from, run.index == 0 ? null : run.heading, minY,
                step[0], step[1], c -> fits(w, p, run, c));
        if (s.isEmpty()) return false;
        VeinPath.Step st = s.get();
        run.heading = new int[] {st.hx(), st.hy(), st.hz()};
        run.nodes.add(st.at());
        Material real = w.getBlockAt(st.at().x(), st.at().y(), st.at().z()).getType();
        List<String> list = cfg.getStringList(deep.contains(real) ? "trail.ores.deep" : "trail.ores.stone");
        String pick = list.isEmpty() ? (deep.contains(real) ? "minecraft:deepslate_redstone_ore[lit=true]" : "minecraft:redstone_ore[lit=true]")
                : list.get(Math.floorMod(run.index + run.attempt, list.size()));
        run.fake = data(pick, Material.REDSTONE_ORE);
        p.sendBlockChange(loc(w, st.at()), run.fake);
        run.beatIn = 20;
        return true;
    }

    /** Puts the real block back where the fake ore is shown. */
    private void unshow(Run run, Player p) {
        VeinPath.Pos t = run.target();
        World w = Bukkit.getWorld(run.world);
        if (t == null || w == null || p == null) return;
        Location l = loc(w, t);
        p.sendBlockChange(l, l.getBlock().getBlockData());
    }

    /** They broke the rock under the shown ore. */
    private void broken(Run run, Player p, VeinPath.Pos at) {
        World w = Bukkit.getWorld(run.world);
        Location c = center(w, at);
        p.spawnParticle(Particle.DUST, c, 18, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(Color.fromRGB(0x6a0d0d), 1.2f));
        sound(p, c, "crumble", 1, 1);
        // something scrapes on the other side of the wall they just opened
        if (ThreadLocalRandom.current().nextDouble() < cfg.getDouble("dread.scrape-chance", 0.5)) {
            Vector away = c.toVector().subtract(p.getEyeLocation().toVector()).normalize().multiply(2.5);
            Location beyond = c.clone().add(away);
            long d = 40 + ThreadLocalRandom.current().nextInt(30);
            for (int i = 0; i < 3; i++) later(d + i * 5L, () -> {
                if (p.isOnline() && runs.get(p.getUniqueId()) == run) sound(p, beyond, "scrape", 1, 1);
            });
        }
        run.index++;
        if (run.index >= run.length) {
            // the last one: behind it, the heart
            later(2L, () -> {
                if (runs.get(run.u) != run || !p.isOnline()) return;
                m.set(this, run.u, "heart", place(w, at));
                m.stage(this, run.u, 2);
                heart(run, p, at, true);
            });
            return;
        }
        if (run.index >= run.length - Math.max(0, cfg.getInt("dread.dark-last", 6))) m.haunt().dark(p, 4);
        if (run.index >= cfg.getInt("dread.figure-from", 4) && ThreadLocalRandom.current().nextDouble() < cfg.getDouble("dread.figure-chance", 0.12))
            later(20L + ThreadLocalRandom.current().nextInt(60), () -> {
                if (p.isOnline() && runs.get(run.u) == run) figure(p);
            });
        later(1L, () -> {
            if (runs.get(run.u) != run) return;
            if (!advance(run, p)) run.beatIn = -100;   // no room yet: tried again each second (tick)
        });
    }

    /** Their pickaxe, once more, one beat late. */
    private void echo(Run run, Player p, Block b) {
        long now = System.currentTimeMillis();
        if (now - run.lastEcho < 3000 || ThreadLocalRandom.current().nextDouble() >= cfg.getDouble("dread.echo-chance", 0.3)) return;
        run.lastEcho = now;
        var group = b.getBlockData().getSoundGroup();
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        VeinPath.Pos t = run.target();
        long delay = t == null ? 16 : Sense.interval(center(b.getWorld(), t).distance(p.getEyeLocation()));
        later(delay, () -> {
            if (p.isOnline()) p.playSound(at, group.getBreakSound(), SoundCategory.BLOCKS, 0.55f, group.getPitch() * 0.8f);
        });
    }

    /** The per-tick beat of a run: the trail's heartbeat from the next ore, or the heart's beat. */
    private void frame(Run run) {
        Player p = Bukkit.getPlayer(run.u);
        if (p == null || runs.get(run.u) != run) {
            if (run.task != null) run.task.cancel();
            return;
        }
        if (!p.getWorld().getName().equals(run.world)) return;
        World w = p.getWorld();
        if (run.phase == Phase.TRAIL) {
            VeinPath.Pos t = run.target();
            if (t == null || --run.beatIn > 0) return;
            Location at = center(w, t);
            double d = at.distance(p.getEyeLocation());
            sound(p, at, "heartbeat", Sense.volume(d), Sense.pitch(d));
            run.beatIn = Sense.interval(d);
        } else if (run.phase == Phase.HEART && run.rhythm != null) {
            if (run.mode == Mode.SHOW && --run.showTicks <= 0) {
                stop(run.u);
                return;
            }
            long now = System.currentTimeMillis();
            if (!run.rhythm.due(now)) return;
            int n = run.rhythm.beat(now);
            Location at = center(w, run.heartAt);
            sound(p, at, "heartbeat", 1.4f, 0.9f);
            pulse(run);
            int dark = cfg.getInt("heart.dark-every", 4);
            if (dark > 0 && n % dark == 0) m.haunt().dark(p, 2);
        }
    }

    // ------------------------------------------------------------------------------------------
    // the heart
    // ------------------------------------------------------------------------------------------

    private void heart(Run run, Player p, VeinPath.Pos at, boolean speak) {
        World w = p.getWorld();
        run.world = w.getName();
        run.phase = Phase.HEART;
        run.heartAt = at;
        float s = (float) cfg.getDouble("heart.scale", 0.78);
        BlockDisplay d = m.haunt().block(p, loc(w, at), data(cfg.getString("heart.block", "minecraft:deepslate_redstone_ore[lit=true]"),
                Material.DEEPSLATE_REDSTONE_ORE));
        d.setTransformation(centered(s));
        d.setGlowing(true);
        d.setGlowColorOverride(color(cfg.getString("heart.glow", "#7a0f0f"), Color.fromRGB(0x7a0f0f)));
        d.setBrightness(new Display.Brightness(9, 9));
        run.heart = d;
        // a hitbox only they have: a strike lands on the heart, not on the wall behind it (a display has none)
        Interaction box = w.spawn(loc(w, at).add(0.5, 0.05, 0.5), Interaction.class, x -> {
            x.setInteractionWidth(0.9f);
            x.setInteractionHeight(0.9f);
            x.setPersistent(false);
            x.setVisibleByDefault(false);
        });
        p.showEntity(eco, box);
        run.things.add(box);
        double bpm = Math.max(20, cfg.getDouble("heart.bpm", 60));
        long interval = Math.round(60_000 / bpm);
        run.rhythm = new Rhythm(System.currentTimeMillis() + 1500, interval, cfg.getLong("heart.window-ms", 120), cfg.getDouble("heart.faster", 0.85),
                cfg.getLong("heart.min-interval-ms", 450), cfg.getInt("heart.hits", 13), cfg.getInt("heart.misses", 3),
                run.mode == Mode.SHOW ? 1_000_000 : cfg.getInt("heart.listen-beats", 4));
        if (speak) whisper(p, "heart");
    }

    /** The heart swells on the beat and sinks back. */
    private void pulse(Run run) {
        BlockDisplay d = run.heart;
        if (d == null || !d.isValid()) return;
        float s = (float) cfg.getDouble("heart.scale", 0.78), big = (float) cfg.getDouble("heart.beat-scale", 0.95);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(1);
        d.setTransformation(centered(big));
        later(2L, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration((int) Math.max(3, Math.min(14, run.rhythm.interval() / 50 - 4)));
            d.setTransformation(centered(s));
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Run run = runs.get(p.getUniqueId());
        if (run == null || run.phase != Phase.HEART || run.rhythm == null || run.mode == Mode.SHOW || run.heartAt == null) return;
        if (!p.getWorld().getName().equals(run.world)) return;
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection();
        Location c = center(p.getWorld(), run.heartAt);
        if (!Sense.aimed(eye.getX(), eye.getY(), eye.getZ(), d.getX(), d.getY(), d.getZ(), c.getX(), c.getY(), c.getZ(), 0.6,
                cfg.getDouble("heart.reach", 5))) return;
        long t = System.currentTimeMillis();
        if (cfg.getBoolean("heart.latency", true)) t -= Math.max(0, Math.min(400, p.getPing()));
        Rhythm.Hit h = run.rhythm.strike(t);
        if (h == Rhythm.Hit.IGNORED) return;
        if (run.mode == Mode.PRACTICE && run.reportTo != null) {
            long dl = run.rhythm.lastDelta();
            run.reportTo.sendMessage(Text.msg("<gray>[박자] " + switch (h) {
                case HIT -> "<green>맞음";
                case DONE -> "<green>맞음 (끝)";
                case MISS -> "<red>빗나감";
                case BROKEN -> "<red>빗나감 (무너짐)";
                default -> "";
            } + " <white>" + (dl >= 0 ? "+" : "") + dl + "ms <gray>(맞힘 " + run.rhythm.hits() + "/" + run.rhythm.need() + ", 빗나감 "
                    + run.rhythm.misses() + ", 간격 " + run.rhythm.interval() + "ms, 핑 " + p.getPing() + ")"));
        }
        switch (h) {
            case HIT -> {
                sound(p, c, "strike", 1, 1);
                p.spawnParticle(Particle.DUST, c, 10, 0.25, 0.25, 0.25, 0, new Particle.DustOptions(Color.fromRGB(0x8a0f0f), 1.0f));
            }
            case MISS -> { }   // nothing shown: the heart only beats faster
            case DONE -> {
                sound(p, c, "strike", 1.2f, 0.8f);
                if (run.mode == Mode.REAL) end(run, p, true);
                else end(run, p, false);
            }
            case BROKEN -> {
                if (run.mode == Mode.REAL) collapse(run, p, true);
                else collapse(run, p, false);
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------------------------------
    // light, collapse, the end
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!Sense.light(e.getBlockPlaced().getBlockData().getLightEmission())) return;
        lit(e.getBlockPlaced().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (e.getBucket() == Material.LAVA_BUCKET) lit(e.getBlock().getLocation());
    }

    /** A light was placed here: every real trail or heart near it is wiped. */
    private void lit(Location at) {
        if (!on()) return;
        double r = cfg.getDouble("light.radius", 24), r2 = r * r;
        for (Run run : List.copyOf(runs.values())) {
            if (run.mode != Mode.REAL || (run.phase != Phase.TRAIL && run.phase != Phase.HEART)) continue;
            Player p = Bukkit.getPlayer(run.u);
            if (p == null || !at.getWorld().getName().equals(run.world) || !p.getWorld().equals(at.getWorld())) continue;
            VeinPath.Pos t = run.phase == Phase.TRAIL ? run.target() : run.heartAt;
            boolean near = p.getLocation().distanceSquared(at) <= r2 || (t != null && center(at.getWorld(), t).distanceSquared(at) <= r2);
            if (near) glare(run, p);
        }
    }

    /** "…눈부셔." The trail is gone; a new one from the start point. */
    private void glare(Run run, Player p) {
        UUID u = run.u;
        stop(u);
        whisper(p, "glare");
        sound(p, p.getLocation(), "glare", 1, 1);
        m.haunt().dark(p, 3);
        m.set(this, u, "lights", m.getLong(this, u, "lights", 0) + 1);
        m.set(this, u, "attempt", m.getLong(this, u, "attempt", 0) + 1);
        m.remove(this, u, "heart");
        m.stage(this, u, 1);
        paused.add(u);
        later(60L, () -> {
            paused.remove(u);
            if (!p.isOnline() || runs.containsKey(u) || stage(u) != 1) return;
            whisper(p, "again");
            trail(p);
        });
    }

    /** Three misses: stone falls (only for them), the dark, a shove back — then the trail again (real) or nothing (practice). */
    private void collapse(Run run, Player p, boolean real) {
        run.phase = Phase.COLLAPSE;
        World w = p.getWorld();
        Location heart = run.heartAt == null ? p.getLocation() : center(w, run.heartAt);
        if (run.heart != null) m.haunt().remove(p, run.heart);
        run.heart = null;
        whisper(p, "collapse");
        sound(p, p.getLocation(), "collapse", 1, 1);
        m.haunt().dark(p, 6);
        m.haunt().black(p, 2);
        Vector push = p.getLocation().toVector().subtract(heart.toVector()).setY(0);
        if (push.lengthSquared() < 0.01) push = p.getLocation().getDirection().multiply(-1).setY(0);
        p.setVelocity(push.normalize().multiply(1.1).setY(0.35));
        fall(run, p);
        UUID u = run.u;
        if (real) {
            m.set(this, u, "collapses", m.getLong(this, u, "collapses", 0) + 1);
            m.remove(this, u, "heart");
            m.stage(this, u, 1);
        }
        later(70L, () -> {
            if (runs.get(u) != run) return;
            stop(u);
            if (!real || !p.isOnline()) return;
            whisper(p, "retry");
            Run again = newRun(p, Mode.REAL);
            if (again == null) return;
            int rewind = Math.max(0, cfg.getInt("collapse.rewind", 0));
            // the same seed: from the start, or (rewind n) only the last n ores again, from the ore before them
            if (rewind > 0 && run.nodes.size() >= run.length - rewind && run.length - rewind > 0) {
                again.nodes.addAll(run.nodes.subList(0, run.length - rewind));
                again.index = run.length - rewind;
                again.heading = run.heading;
            }
            if (!advance(again, p)) again.beatIn = -100;
        });
    }

    /** Fake rocks falling around them (block displays only they see, sliding down). */
    private void fall(Run run, Player p) {
        Location base = p.getLocation();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Material stone = deep.contains(base.clone().subtract(0, 1, 0).getBlock().getType()) ? Material.COBBLED_DEEPSLATE : Material.COBBLESTONE;
        for (int i = 0; i < 14; i++) {
            int dx = r.nextInt(5) - 2, dz = r.nextInt(5) - 2, dy = 3 + r.nextInt(3);
            Location at = base.clone().add(dx, dy, dz);
            BlockDisplay d = m.haunt().block(p, at, (r.nextBoolean() ? stone : Material.GRAVEL).createBlockData());
            d.setTransformation(centered(0.6f + r.nextFloat() * 0.35f));
            run.things.add(d);
            long delay = r.nextInt(12);
            later(1 + delay, () -> {
                if (!d.isValid()) return;
                float s = 0.6f;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(6 + r.nextInt(4));
                d.setTransformation(new Transformation(new Vector3f(0.2f, -dy + 0.2f, 0.2f), new AxisAngle4f(r.nextFloat() * 2, 0.3f, 1, 0.2f),
                        new Vector3f(s, s, s), new AxisAngle4f()));
                sound(p, at, "falling", 1, 0.8f + r.nextFloat() * 0.4f);
            });
        }
    }

    /** The 13th hit: the heart stops, silence, then their own heartbeat. Real = solved. */
    private void end(Run run, Player p, boolean real) {
        run.phase = Phase.END;
        UUID u = run.u;
        BlockDisplay d = run.heart;
        Location c = run.heartAt == null ? p.getLocation() : center(p.getWorld(), run.heartAt);
        if (d != null && d.isValid()) {
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(40);
            d.setTransformation(centered(0.55f));
            d.setGlowing(false);
        }
        p.stopAllSounds();
        p.removePotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS);
        // silence
        later(60L, () -> {
            if (d != null && d.isValid()) {
                p.spawnParticle(Particle.BLOCK, c, 30, 0.25, 0.25, 0.25, 0, d.getBlock());
                sound(p, c, "crumble", 0.6f, 0.9f);
                m.haunt().remove(p, d);
            }
        });
        // then theirs: from inside their own chest, slow, a little louder each time
        for (int i = 0; i < 5; i++) {
            int k = i;
            later(100L + i * 22L, () -> {
                if (!p.isOnline()) return;
                sound(p, p.getLocation().add(0, 1.2, 0), "heartbeat", 0.5f + k * 0.2f, 0.75f);
                // the miners who became the vein, one line before the last: they can stop now
                if (k == 1 && !text("end-before").isEmpty()) p.showTitle(Title.title(Component.empty(), Text.mm(text("end-before")),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(1200), Duration.ofMillis(300))));
                if (k == 3) p.showTitle(Title.title(Component.empty(), Text.mm(text("end")),
                        Title.Times.times(Duration.ofMillis(600), Duration.ofMillis(3500), Duration.ofMillis(1200))));
            });
        }
        later(100L + 5 * 22L + 30L, () -> {
            stop(u);
            if (!real || !p.isOnline()) return;
            boolean late = !m.test(u) && m.closed(this);
            m.remove(this, u, "heart");
            m.solve(this, p);
            if (!late) give(p);
        });
    }

    /** The one 광맥의 심장 (a tester's is marked as a test). */
    private void give(Player p) {
        String id = cfg.getString("relic.item", "economy:vein_heart");
        Optional<ItemStack> made = Items.create(id, 1);
        if (made.isEmpty()) {
            eco.getLogger().warning("[비밀] 없는 아이템: " + id);
            return;
        }
        ItemStack it = made.get();
        boolean test = m.test(p.getUniqueId());
        int radius = cfg.getInt("relic.radius", 12);
        it.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            for (String l : cfg.getStringList("relic.lore")) lore.add(Text.item(l.replace("{radius}", String.valueOf(radius))));
            lore.add(Text.item(cfg.getString("relic.owner-line", "<dark_gray>멈춘 사람: {player}").replace("{player}", p.getName())));
            String bound = cfg.getString("relic.bound-line", "");
            if (!bound.isEmpty()) lore.add(Text.item(bound));
            if (test) lore.add(Text.item(cfg.getString("relic.test-line", "<yellow>[테스트]")));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(HEART_OWNER, PersistentDataType.STRING, p.getUniqueId().toString());
            if (test) meta.getPersistentDataContainer().set(HEART_TEST, PersistentDataType.BYTE, (byte) 1);
        });
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
        p.sendMessage(Text.msg("<gray>받은 것: " + Items.mmName(it)));
        if (!test) eco.kv().set("secret.vein.heart", p.getUniqueId().toString());
    }

    // ------------------------------------------------------------------------------------------
    // the heart stays with the one who stopped it (Relic sends it home from anyone else's bag)
    // ------------------------------------------------------------------------------------------

    /** It does not leave their hand (a tester's may: they throw it away to clean up). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropHeart(PlayerDropItemEvent e) {
        ItemStack it = e.getItemDrop().getItemStack();
        if (!relic.heart(it) || it.getItemMeta().getPersistentDataContainer().has(HEART_TEST)) return;
        e.setCancelled(true);
        String t = cfg.getString("relic.keep", "");
        if (!t.isEmpty()) m.haunt().whisper(e.getPlayer(), t);
    }

    /** Dying does not part them from it either; someone else's that they somehow still carried goes home. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeathHeart(PlayerDeathEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        for (var it = e.getDrops().iterator(); it.hasNext(); ) {
            ItemStack d = it.next();
            if (!relic.heart(d) || Relic.owner(d) == null) continue;
            it.remove();
            if (Relic.worksFor(Relic.owner(d), u)) e.getItemsToKeep().add(d);
            else relic.home(null, d);
        }
    }

    /** On the ground (a broken chest, a dropper) it neither burns nor despawns; a tester's is an ordinary item. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawnHeart(ItemSpawnEvent e) {
        ItemStack it = e.getEntity().getItemStack();
        if (!relic.heart(it) || Relic.owner(it) == null || it.getItemMeta().getPersistentDataContainer().has(HEART_TEST)) return;
        e.getEntity().setInvulnerable(true);
        e.getEntity().setUnlimitedLifetime(true);
    }

    /** Should one still be about to despawn, it goes home instead. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDespawnHeart(ItemDespawnEvent e) {
        ItemStack it = e.getEntity().getItemStack();
        if (!relic.heart(it) || Relic.owner(it) == null || it.getItemMeta().getPersistentDataContainer().has(HEART_TEST)) return;
        e.getEntity().remove();
        relic.home(null, it);
    }

    // ------------------------------------------------------------------------------------------
    // the figure: a miner at the edge of their view
    // ------------------------------------------------------------------------------------------

    private boolean figure(Player p) {
        Location eye = p.getEyeLocation();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int tries = 0; tries < 24; tries++) {
            double yaw = Math.toRadians(eye.getYaw() + (r.nextBoolean() ? 1 : -1) * (38 + r.nextDouble() * 22));
            double dist = 8 + r.nextDouble() * 8;
            Location at = eye.clone().add(-Math.sin(yaw) * dist, 0, Math.cos(yaw) * dist);
            for (int dy = 2; dy >= -4; dy--) {
                Location feet = at.clone().add(0, dy - 1.62, 0);
                Block f = feet.getBlock();
                if (!f.isPassable() || !f.getRelative(BlockFace.UP).isPassable() || f.getRelative(BlockFace.DOWN).isPassable()) continue;
                Location stand = f.getLocation().add(0.5, 0, 0.5);
                if (!p.hasLineOfSight(stand.clone().add(0, 1.5, 0))) continue;
                Vector look = eye.toVector().subtract(stand.toVector());
                stand.setYaw((float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ())));
                showFigure(p, stand);
                return true;
            }
        }
        return false;
    }

    private void showFigure(Player p, Location at) {
        String skin = cfg.getString("dread.figure-skin", "");
        Mannequin f = m.haunt().figure(p, at, skin == null || skin.isBlank() ? null : skin, null);
        ItemStack hat = new ItemStack(Material.LEATHER_HELMET);
        hat.editMeta(LeatherArmorMeta.class, lm -> lm.setColor(color(cfg.getString("dread.figure-hat", "#d9b227"), Color.fromRGB(0xd9b227))));
        f.getEquipment().setHelmet(hat);
        f.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
        long until = System.currentTimeMillis() + 6000;
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(eco, () -> {
            boolean gone = !p.isOnline() || !f.isValid() || System.currentTimeMillis() > until || !p.getWorld().equals(f.getWorld());
            if (!gone) {
                // looked at: gone
                Vector to = f.getLocation().add(0, 1.2, 0).toVector().subtract(p.getEyeLocation().toVector()).normalize();
                gone = to.dot(p.getEyeLocation().getDirection().normalize()) > Math.cos(Math.toRadians(14));
            }
            if (gone) {
                if (p.isOnline()) m.haunt().remove(p, f);
                else if (f.isValid()) f.remove();
                task[0].cancel();
            }
        }, 2L, 1L);
    }

    // ------------------------------------------------------------------------------------------
    // every second
    // ------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        relic.tick();
        for (Run run : List.copyOf(runs.values())) {
            Player p = Bukkit.getPlayer(run.u);
            if (p == null) {
                stop(run.u);
                continue;
            }
            if (run.mode != Mode.REAL || run.phase != Phase.TRAIL) continue;
            World w = Bukkit.getWorld(run.world);
            if (w == null) continue;
            VeinPath.Pos t = run.target();
            if (t == null) {
                if (run.index < run.length && !advance(run, p)) run.beatIn = -100;
                continue;
            }
            Location l = loc(w, t);
            if (!w.isChunkLoaded(t.x() >> 4, t.z() >> 4)) continue;
            if (!rock.contains(l.getBlock().getType())) {
                if (p.getWorld().equals(w) && p.getLocation().distanceSquared(l) < 7 * 7 && l.getBlock().isPassable()) {
                    broken(run, p, t);   // gone while they stood by it (an area-mining tool breaks without an event): theirs
                } else if (!advance(run, p)) {
                    run.beatIn = -100;   // someone else dug it out, or it blew up: the vein moves on from the ore before it
                }
                continue;
            }
            // chunk resends forget the fake: send it again while it may be in view
            if (p.getWorld().equals(w) && p.getLocation().distanceSquared(l) < 96 * 96) p.sendBlockChange(l, run.fake);
            if (run.index >= run.length - Math.max(0, cfg.getInt("dread.dark-last", 6)) && !p.hasPotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS))
                m.haunt().dark(p, 4);
        }
        // a trail put away by a quit or restart comes back when they return near it
        double rr = cfg.getDouble("trail.resume-range", 40);
        for (Player p : m.active(this)) {
            UUID u = p.getUniqueId();
            if (runs.containsKey(u) || paused.contains(u) || !open(u)) continue;
            int st = stage(u);
            if (st < 1 || st > stages()) continue;
            String where = st == 2 && m.get(this, u, "heart") != null ? m.get(this, u, "heart") : m.get(this, u, "start");
            Optional<VeinPath.Pos> at = VeinPath.Pos.decode(where);
            if (at.isEmpty() || !p.getWorld().getName().equals(worldOf(where))) continue;
            if (center(p.getWorld(), at.get()).distanceSquared(p.getLocation()) > rr * rr) continue;
            whisper(p, "resume");
            if (st == 2) {
                Run run = newRun(p, Mode.REAL);
                if (run != null) heart(run, p, at.get(), false);
            } else {
                trail(p);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // cleanup, status, scenes
    // ------------------------------------------------------------------------------------------

    /** Ends a run: the fake ore put back, the heart and falling stones gone, its beat stopped. */
    private void stop(UUID u) {
        Run run = runs.remove(u);
        if (run == null) return;
        if (run.task != null) run.task.cancel();
        Player p = Bukkit.getPlayer(u);
        if (run.phase == Phase.TRAIL) unshow(run, p);
        for (org.bukkit.entity.Entity e : run.things) {
            if (p != null) m.haunt().remove(p, e);
            else if (e.isValid()) e.remove();
        }
        if (run.heart != null) {
            if (p != null) m.haunt().remove(p, run.heart);
            else if (run.heart.isValid()) run.heart.remove();
        }
    }

    @Override
    public void cleanup(UUID player) {
        stop(player);
        relic.clear(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        paused.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public String status(UUID u) {
        StringBuilder b = new StringBuilder();
        String start = m.get(this, u, "start");
        if (start != null) b.append("시작점 ").append(start);
        b.append(" · 시도 ").append(m.getLong(this, u, "attempt", 0)).append(" · 빛 ").append(m.getLong(this, u, "lights", 0))
                .append(" · 무너짐 ").append(m.getLong(this, u, "collapses", 0));
        String heard = m.get(this, u, "heard");
        if (heard != null) b.append(" · 마지막 속삭임 ").append(heard);
        Run run = runs.get(u);
        if (run == null) {
            if (stage(u) == 1 || stage(u) == 2) b.append(" · <yellow>꺼져 있음</yellow> (")
                    .append(paused.contains(u) ? "정리됨, 다시 접속하면" : "시작점" + (stage(u) == 2 ? "·심장" : "") + " " + (int) cfg.getDouble("trail.resume-range", 40) + "칸 안에 오면")
                    .append(" 이어짐)");
        } else if (run.phase == Phase.TRAIL) {
            VeinPath.Pos t = run.target();
            b.append(" · 흔적 ").append(run.index).append('/').append(run.length);
            if (t != null) {
                Player p = Bukkit.getPlayer(u);
                b.append(" · 다음 광석 ").append(t.encode());
                if (p != null && p.getWorld().getName().equals(run.world)) b.append(" (").append(Math.round(center(p.getWorld(), t).distance(p.getLocation()))).append("칸)");
            }
        } else if (run.rhythm != null) {
            b.append(" · 심장 ").append(run.heartAt == null ? "?" : run.heartAt.encode()).append(" · 맞힘 ").append(run.rhythm.hits()).append('/')
                    .append(run.rhythm.need()).append(" · 빗나감 ").append(run.rhythm.misses()).append(" · 간격 ").append(run.rhythm.interval()).append("ms")
                        .append(run.phase == Phase.COLLAPSE ? " (무너지는 중)" : run.phase == Phase.END ? " (멈추는 중)" : "");
        }
        Player online = Bukkit.getPlayer(u);
        if (online != null && relic.holds(online)) b.append(" · 광맥의 심장을 들고 있음");
        String holder = eco.kv().get("secret.vein.heart");
        if (holder != null) b.append(" · 심장 주인 ").append(Optional.ofNullable(Bukkit.getOfflinePlayer(UUID.fromString(holder)).getName()).orElse(holder));
        return b.toString().trim();
    }

    private static final List<String> SCENES = List.of("속삭임", "심장소리", "틀린광석", "빛", "메아리", "형체", "심장원석", "박자", "붕괴", "멈춤", "정리");

    @Override
    public List<String> scenes() {
        return SCENES;
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        switch (word) {
            case "속삭임" -> {
                whisper(p, "start");
                Location in = eye.clone().add(dir.clone().multiply(3));
                sound(p, in, "whisper", 1, 1);
                later(10L, () -> sound(p, in, "heartbeat", 0.45f, 0.6f));
                later(30L, () -> sound(p, in, "heartbeat", 0.4f, 0.58f));
            }
            case "심장소리" -> {
                // six beats from 12 blocks ahead, then from 3: the far and the near sound
                Location far = eye.clone().add(dir.clone().multiply(12)), near = eye.clone().add(dir.clone().multiply(3));
                long t = 0;
                for (int i = 0; i < 6; i++) {
                    Location at = i < 3 ? far : near;
                    double d = i < 3 ? 12 : 3;
                    later(t, () -> sound(p, at, "heartbeat", Sense.volume(d), Sense.pitch(d)));
                    t += Sense.interval(d);
                }
            }
            case "틀린광석" -> {
                Block b = p.getTargetBlockExact(8);
                if (b == null || !b.getType().isSolid()) {
                    admin.sendMessage(Text.msg("<red>8칸 안의 단단한 블록을 바라보세요"));
                    return true;
                }
                List<String> list = cfg.getStringList(deep.contains(b.getType()) ? "trail.ores.deep" : "trail.ores.stone");
                BlockData fake = data(list.isEmpty() ? "minecraft:redstone_ore[lit=true]" : list.get(ThreadLocalRandom.current().nextInt(list.size())), Material.REDSTONE_ORE);
                p.sendBlockChange(b.getLocation(), fake);
                later(200L, () -> {
                    if (p.isOnline()) p.sendBlockChange(b.getLocation(), b.getBlockData());
                });
                admin.sendMessage(Text.msg("<gray>10초 동안 그 블록이 " + fake.getMaterial().getKey().getKey() + " 로 보여요 (그 사람만)"));
            }
            case "빛" -> {
                whisper(p, "glare");
                sound(p, p.getLocation(), "glare", 1, 1);
                m.haunt().dark(p, 3);
                later(60L, () -> whisper(p, "again"));
            }
            case "메아리" -> {
                Block b = p.getLocation().subtract(0, 1, 0).getBlock();
                var group = b.getBlockData().getSoundGroup();
                Location at = b.getLocation().add(0.5, 0.5, 0.5);
                p.playSound(at, group.getBreakSound(), SoundCategory.BLOCKS, 1f, group.getPitch());
                later(14L, () -> p.playSound(at, group.getBreakSound(), SoundCategory.BLOCKS, 0.55f, group.getPitch() * 0.8f));
                Location beyond = eye.clone().add(dir.clone().multiply(4));
                for (int i = 0; i < 3; i++) later(50L + i * 5L, () -> sound(p, beyond, "scrape", 1, 1));
            }
            case "형체" -> {
                if (!figure(p)) admin.sendMessage(Text.msg("<red>시야 가장자리에 설 자리가 없어요 (굴 · 동굴 안에서)"));
            }
            case "심장원석", "박자" -> {
                stop(u);
                Run run = new Run(u, word.equals("박자") ? Mode.PRACTICE : Mode.SHOW);
                run.world = w.getName();
                run.reportTo = admin;
                run.showTicks = 20 * 20;
                runs.put(u, run);
                run.task = Bukkit.getScheduler().runTaskTimer(eco, () -> frame(run), 1L, 1L);
                heart(run, p, front(p), false);
                admin.sendMessage(Text.msg(word.equals("박자")
                        ? "<gray>앞에 심장 원석: 처음 " + cfg.getInt("heart.listen-beats", 4) + "박은 듣기만, 그다음 박자에 맞춰 바라보고 때리기 (판정이 여기에 나옴, 보상 없음)"
                        : "<gray>앞에 심장 원석 (20초, 판정 없음)"));
            }
            case "붕괴" -> {
                stop(u);
                Run run = new Run(u, Mode.SHOW);
                run.world = w.getName();
                runs.put(u, run);
                run.heartAt = front(p);
                collapse(run, p, false);
            }
            case "멈춤" -> {
                stop(u);
                Run run = new Run(u, Mode.SHOW);
                run.world = w.getName();
                runs.put(u, run);
                heart(run, p, front(p), false);
                end(run, p, false);
            }
            case "정리" -> {
                stop(u);
                relic.clear(u);
                paused.add(u);
                admin.sendMessage(Text.msg("<green>" + p.getName() + ": 가짜 광석 · 심장 · 연출을 치웠어요 (진행 기록은 그대로, 다시 접속하면 시작점 근처에서 이어짐)"));
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
