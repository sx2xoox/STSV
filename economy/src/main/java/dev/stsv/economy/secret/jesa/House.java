package dev.stsv.economy.secret.jesa;

import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The house under the persimmon tree (주인 없는 제사), there for one player only: four posts and a thatch of hay, a low
 * table, a folding screen behind it on the north with two paper tablets, two candles, an incense burner on its own small
 * stand before the table, a spoon and chopsticks, a click box, and the dishes the player laid (small item displays).
 * It also plays what happens on the table: a wrong table shaking its dishes off, and the meal of the dead.
 * Nothing here is saved; everything is a display (it stops nobody) and goes with {@link #hide}.
 * <p>
 * Measures are from the table's block (its north-west bottom corner): x east, z south. The screen is at z -0.6, the one
 * who bows stands at z 2 or more, so their right hand is the east.
 */
final class House {

    /** On the click box: whose table it is (" preview" after it for an admin's look). */
    static final NamespacedKey TAG = new NamespacedKey("economy", "jesa_table");
    /** The table top over the ground, and how big a dish lies on it. */
    static final float TOP = 0.69f, DISH = 0.24f;
    private static final float HALF_W = 1.35f;
    private static final float[] BURNER = {0.5f, 0.74f, 1.7f};
    private static final float[][] WICK = {{0.5f - 1.22f, TOP + 0.47f, -0.1f}, {0.5f + 1.22f, TOP + 0.47f, -0.1f}};
    private static final float[] SPOON = {0.5f - 0.1f, TOP + 0.012f, -0.13f}, STICKS = {0.5f + 0.1f, TOP + 0.012f, -0.13f};

    final class Shown {
        final UUID owner;
        /** the table's block (its corner) */
        final Location base;
        final boolean preview;
        private final List<Entity> haunted = new ArrayList<>(), own = new ArrayList<>();
        private final List<Display> warm = new ArrayList<>();
        private final BlockDisplay[] candles = new BlockDisplay[2];
        private final BlockDisplay[] slips = new BlockDisplay[2];
        private final TextDisplay[] names = new TextDisplay[2];
        private final ItemDisplay[] sticks = new ItemDisplay[2];
        private ItemDisplay spoon;
        Interaction hit;
        private final Map<Integer, ItemDisplay> food = new HashMap<>();
        private final Map<Integer, String> laid = new TreeMap<>();
        private final List<BukkitTask> tasks = new ArrayList<>();
        private final List<Location> cleared = new ArrayList<>();
        private BukkitTask pulse;
        private int beat;
        private long dimUntil;
        boolean lit, smoke, lean, bend, busy;

        Shown(UUID owner, Location base, boolean preview) {
            this.owner = owner;
            this.base = base;
            this.preview = preview;
        }

        Location at(double x, double y, double z) {
            return base.clone().add(x, y, z);
        }

        /** The table's middle, on its top. */
        Location table() {
            return at(0.5, TOP, 0.5);
        }

        boolean valid() {
            return hit != null && hit.isValid();
        }
    }

    private final Jesa jesa;
    private final SecretModule m;
    private final EcoPlugin eco;
    private final Map<UUID, Shown> shown = new HashMap<>();

    House(Jesa jesa, SecretModule m, EcoPlugin eco) {
        this.jesa = jesa;
        this.m = m;
        this.eco = eco;
    }

    Shown get(UUID u) {
        return shown.get(u);
    }

    // ------------------------------------------------------------------------------------------
    // building
    // ------------------------------------------------------------------------------------------

    private static Transformation box(double x, double y, double z, double sx, double sy, double sz) {
        return new Transformation(new Vector3f((float) x, (float) y, (float) z), new Quaternionf(), new Vector3f((float) sx, (float) sy, (float) sz),
                new Quaternionf());
    }

    /** A box of that size whose middle (in x and z) is at (cx, cz), standing on y, turned about its own middle. */
    private static Transformation turned(double cx, double y, double cz, double sx, double sy, double sz, double yawDeg, double tiltDeg) {
        Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(yawDeg)).rotateZ((float) Math.toRadians(tiltDeg));
        Vector3f half = q.transform(new Vector3f((float) sx / 2, 0, (float) sz / 2));
        return new Transformation(new Vector3f((float) cx - half.x, (float) y - half.y, (float) cz - half.z), q,
                new Vector3f((float) sx, (float) sy, (float) sz), new Quaternionf());
    }

    /** Lying flat on the table, its picture's top to the north, turned about the upright by yaw. */
    private static Quaternionf flat(double yawDeg) {
        return new Quaternionf().rotateY((float) Math.toRadians(yawDeg)).rotateX((float) -Math.PI / 2).rotateY((float) Math.PI);
    }

    private BlockDisplay block(Player p, Shown s, Material what, Transformation t) {
        return block(p, s, what.createBlockData(), t);
    }

    private BlockDisplay block(Player p, Shown s, BlockData data, Transformation t) {
        BlockDisplay d = m.haunt().block(p, s.base, data);
        d.setTransformation(t);
        s.haunted.add(d);
        return d;
    }

    private ItemDisplay item(Player p, Shown s, Location at, ItemStack it, Quaternionf turn, float scale) {
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setTransformation(new Transformation(new Vector3f(), turn, new Vector3f(scale, scale, scale), new Quaternionf()));
        });
        p.showEntity(eco, d);
        s.own.add(d);
        return d;
    }

    /**
     * Builds the house for this player with its table's block at base (the first air over flat ground). layout: what
     * they laid on it. Whatever they were shown before goes first.
     */
    Shown show(Player p, Location base, boolean preview, Map<Integer, String> layout) {
        hide(p.getUniqueId());
        Location b = base.getBlock().getLocation();
        Shown s = new Shown(p.getUniqueId(), b, preview);
        shown.put(p.getUniqueId(), s);
        clearPlants(p, s);
        // four posts, a thatch in three steps
        for (int[] c : new int[][] {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}})
            block(p, s, Material.SPRUCE_LOG, box(0.5 + c[0] * 2.0 - 0.2, 0, 0.5 + c[1] * 2.0 - 0.2, 0.4, 2.7, 0.4));
        for (int[] c : new int[][] {{-1, -1}, {0, -1}, {-1, 0}, {0, 0}})
            block(p, s, Material.HAY_BLOCK, box(0.5 + c[0] * 2.8, 2.7, 0.5 + c[1] * 2.8, 2.8, 0.45, 2.8));
        block(p, s, Material.HAY_BLOCK, box(0.5 - 1.6, 3.15, 0.5 - 1.6, 3.2, 0.4, 3.2));
        block(p, s, Material.HAY_BLOCK, box(0.5 - 0.7, 3.55, 0.5 - 0.7, 1.4, 0.3, 1.4));
        // the table: a top on two sides
        s.warm.add(block(p, s, Material.DARK_OAK_SLAB, box(0.5 - HALF_W, TOP - 0.15, -0.25, HALF_W * 2, 0.3, 1.5)));
        s.warm.add(block(p, s, Material.DARK_OAK_PLANKS, box(0.5 - 1.25, 0, -0.1, 0.12, TOP - 0.15, 1.2)));
        s.warm.add(block(p, s, Material.DARK_OAK_PLANKS, box(0.5 + 1.13, 0, -0.1, 0.12, TOP - 0.15, 1.2)));
        // the folding screen behind it, on the north: four leaves in a zigzag
        for (int i = 0; i < 4; i++)
            s.warm.add(block(p, s, Material.WHITE_TERRACOTTA, turned(0.5 + (i - 1.5) * 0.8, 0, -0.6, 0.82, 1.9, 0.05, i % 2 == 0 ? 14 : -14, 0)));
        // two paper tablets on it: the husband's on the west, and one more
        String[] written = {jesa.text("tablets.father"), jesa.text("tablets.mother")};
        for (int i = 0; i < 2; i++) {
            double x = 0.5 + (i == 0 ? -0.13 : 0.13);
            s.slips[i] = block(p, s, Material.CALCITE, turned(x, 1.12, -0.46, 0.13, 0.6, 0.02, 0, 0));
            s.warm.add(s.slips[i]);
            Location at = s.at(x, 1.15, -0.44);
            at.setYaw(0);
            at.setPitch(0);
            TextDisplay t = m.haunt().text(p, at, "<#2b2622>" + String.join("\n", written[i].split("")));
            t.setBillboard(Display.Billboard.FIXED);
            t.setRotation(0, 0);
            t.setShadowed(false);
            t.setLineWidth(40);
            t.setViewRange(0.25f);
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.2f, 0.2f, 0.2f), new Quaternionf()));
            s.names[i] = t;
            s.haunted.add(t);
            s.warm.add(t);
        }
        // candles at the table's far corners, the burner on its stand before the table
        for (int i = 0; i < 2; i++) {
            s.candles[i] = block(p, s, Material.WHITE_CANDLE, candleAt(i, 0));
            s.warm.add(s.candles[i]);
        }
        s.warm.add(block(p, s, Material.DARK_OAK_PLANKS, box(0.25, 0, 1.5, 0.5, 0.42, 0.4)));
        s.warm.add(block(p, s, Material.CAULDRON, box(BURNER[0] - 0.16, 0.42, BURNER[2] - 0.16, 0.32, 0.32, 0.32)));
        // a spoon and chopsticks, laid by the screen
        s.spoon = item(p, s, s.at(SPOON[0], SPOON[1], SPOON[2]), new ItemStack(Material.GOLDEN_SHOVEL), flat(35), 0.3f);
        for (int i = 0; i < 2; i++)
            s.sticks[i] = item(p, s, s.at(STICKS[0] + i * 0.05, STICKS[1], STICKS[2]), new ItemStack(Material.BLAZE_ROD), flat(45), 0.28f);
        s.warm.add(s.spoon);
        s.warm.add(s.sticks[0]);
        s.warm.add(s.sticks[1]);
        s.hit = b.getWorld().spawn(s.at(0.5, 0, 0.5), Interaction.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setInteractionWidth(2.7f);
            e.setInteractionHeight(1.3f);
            e.setResponsive(true);
            e.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, p.getUniqueId() + (preview ? " preview" : ""));
        });
        p.showEntity(eco, s.hit);
        s.own.add(s.hit);
        food(p, layout);
        s.pulse = Bukkit.getScheduler().runTaskTimer(eco, () -> pulse(s), 5L, 5L);
        return s;
    }

    private static Transformation candleAt(int i, double tiltSouthDeg) {
        // a candle's model stands in the middle of its block: turn it about its foot
        Quaternionf q = new Quaternionf().rotateX((float) Math.toRadians(tiltSouthDeg));
        Vector3f foot = q.transform(new Vector3f(0.5f, 0, 0.5f));
        return new Transformation(new Vector3f(WICK[i][0] - foot.x, TOP - foot.y, WICK[i][2] - foot.z), q, new Vector3f(1, 1, 1), new Quaternionf());
    }

    /** Grass and flowers where the table, the screen and the stand are: not there for them (put back when it goes). */
    private void clearPlants(Player p, Shown s) {
        BlockData air = Material.AIR.createBlockData();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    Location at = s.base.clone().add(dx, dy, dz);
                    if (!at.isChunkLoaded()) continue;   // never loads a chunk for it
                    Block b = at.getBlock();
                    if (b.isEmpty() || b.isLiquid() || !b.isPassable()) continue;
                    p.sendBlockChange(b.getLocation(), air);   // put back by hide (kept here, not with Haunt: these come and go)
                    s.cleared.add(b.getLocation());
                }
            }
        }
    }

    /** Takes the house away at once. */
    void hide(UUID u) {
        Shown s = shown.remove(u);
        if (s == null) return;
        if (s.pulse != null) s.pulse.cancel();
        for (BukkitTask t : s.tasks) t.cancel();
        s.tasks.clear();
        Player p = Bukkit.getPlayer(u);
        for (Entity e : s.haunted) {
            if (p != null) m.haunt().remove(p, e);
            else if (e.isValid()) e.remove();
        }
        for (Entity e : s.own) if (e.isValid()) e.remove();
        if (p != null && p.getWorld().equals(s.base.getWorld()))
            for (Location at : s.cleared) if (at.isChunkLoaded()) p.sendBlockChange(at, at.getBlock().getBlockData());
    }

    void hideAll() {
        for (UUID u : new HashSet<>(shown.keySet())) hide(u);
    }

    // ------------------------------------------------------------------------------------------
    // the dishes
    // ------------------------------------------------------------------------------------------

    private Location dishAt(Shown s, int slot) {
        double[] at = Table.at(slot);
        return s.at(0.5 + at[0], TOP + 0.012, 0.5 + at[1]);
    }

    /** Shows what is laid (place -> item id) as small dishes on the table; places that changed are redrawn. */
    void food(Player p, Map<Integer, String> layout) {
        Shown s = shown.get(p.getUniqueId());
        if (s == null || s.busy) return;
        for (Integer slot : new ArrayList<>(s.food.keySet())) {
            if (layout.containsKey(slot) && layout.get(slot).equals(s.laid.get(slot))) continue;
            ItemDisplay d = s.food.remove(slot);
            s.laid.remove(slot);
            s.own.remove(d);
            s.warm.remove(d);
            if (d.isValid()) d.remove();
        }
        for (Map.Entry<Integer, String> e : layout.entrySet()) {
            if (s.food.containsKey(e.getKey())) continue;
            ItemStack it = Items.create(e.getValue(), 1).orElse(null);
            if (it == null) continue;
            ItemDisplay d = item(p, s, dishAt(s, e.getKey()), it, flat(0), DISH);
            if (s.lit) d.setBrightness(new Display.Brightness(10, 0));
            s.food.put(e.getKey(), d);
            s.laid.put(e.getKey(), e.getValue());
            s.warm.add(d);
        }
    }

    // ------------------------------------------------------------------------------------------
    // candles and incense
    // ------------------------------------------------------------------------------------------

    /** The candles lit (night) or not; what stands by them is warmed by their light. */
    void light(Shown s, boolean lit) {
        if (s.lit == lit) return;
        s.lit = lit;
        for (BlockDisplay c : s.candles) {
            if (c == null || !c.isValid()) continue;
            BlockData data = c.getBlock().clone();
            if (data instanceof Lightable l) l.setLit(lit);
            c.setBlock(data);
        }
        Display.Brightness b = lit ? new Display.Brightness(10, 0) : null;
        for (Display d : s.warm) if (d.isValid()) d.setBrightness(b);
        Player p = Bukkit.getPlayer(s.owner);
        if (p != null && lit) m.haunt().sound(p, s.table(), Sound.ITEM_FLINTANDSTEEL_USE, 0.25f, 1.5f);
    }

    /** The incense burning (a thread of smoke) or out. */
    void smoke(Shown s, boolean on) {
        s.smoke = on;
    }

    private void pulse(Shown s) {
        Player p = Bukkit.getPlayer(s.owner);
        if (p == null || !p.getWorld().equals(s.base.getWorld())) return;
        s.beat++;
        if (s.beat % 2 != 0) return;
        if (s.lit && System.currentTimeMillis() >= s.dimUntil) {
            for (float[] w : WICK) {
                Location at = s.at(w[0], w[1], w[2] + (s.lean ? 0.05 : 0));
                if (s.lean) p.spawnParticle(Particle.SMALL_FLAME, at, 0, 0, 0.15, 1, 0.012);
                else p.spawnParticle(Particle.SMALL_FLAME, at, 1, 0, 0, 0, 0);
            }
        }
        if (s.smoke) {
            Location at = s.at(BURNER[0], BURNER[1], BURNER[2]);
            if (s.bend) p.spawnParticle(Particle.WHITE_SMOKE, at, 0, 0, 0.45, -1, 0.035);
            else p.spawnParticle(Particle.WHITE_SMOKE, at, 0, 0, 1, 0, 0.02);
        }
    }

    // ------------------------------------------------------------------------------------------
    // scenes
    // ------------------------------------------------------------------------------------------

    private void later(Shown s, long ticks, Runnable r) {
        s.tasks.add(Bukkit.getScheduler().runTaskLater(eco, () -> {
            Player p = Bukkit.getPlayer(s.owner);
            if (p != null && shown.get(s.owner) == s) r.run();
        }, Math.max(1, ticks)));
    }

    private static void glide(Display d, Vector3f to, Quaternionf turn, float scale, int ticks) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(to, turn, new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    private Vector3f from(float[] home, Location to) {
        return new Vector3f((float) (to.getX() - home[0]), (float) (to.getY() - home[1]), (float) (to.getZ() - home[2]));
    }

    /** From the spoon's own spot to a place on the table (as a translation of its display). */
    private Vector3f spoonTo(Shown s, int slot, float lift) {
        Location to = dishAt(s, slot);
        return new Vector3f((float) (to.getX() - s.base.getX() - SPOON[0]), lift, (float) (to.getZ() - s.base.getZ() - SPOON[2]));
    }

    /**
     * The table is wrong: the flames sink, every wrong dish rolls off its edge and drops, the spoon taps each place that
     * is owed a dish, and the incense goes out. `then` runs at the end (the dishes left are drawn again by the caller).
     */
    void refuse(Player p, Shown s, Table.Verdict v, Runnable then) {
        s.busy = true;
        s.dimUntil = System.currentTimeMillis() + 3500;
        m.haunt().sound(p, s.table(), Sound.BLOCK_CANDLE_EXTINGUISH, 0.35f, 0.6f);
        long t = 24;
        for (int slot : v.wrong()) {
            ItemDisplay d = s.food.get(slot);
            if (d == null) continue;
            int side = Table.rollTo(slot);
            double[] at = Table.at(slot);
            float toEdge = (float) (side * (HALF_W + 0.12) - at[0]);
            later(s, t, () -> {
                glide(d, new Vector3f(toEdge, 0.04f, 0), flat(0).rotateY((float) (side * Math.PI)), DISH, 9);
                m.haunt().sound(p, d.getLocation(), Sound.BLOCK_WOOD_STEP, 0.25f, 1.6f);
            });
            later(s, t + 9, () -> glide(d, new Vector3f(toEdge + side * 0.12f, -TOP + 0.02f, 0), flat(0).rotateY((float) (side * Math.PI * 1.5)), DISH, 6));
            later(s, t + 15, () -> m.haunt().sound(p, d.getLocation(), Sound.BLOCK_WOOL_FALL, 0.7f, 0.6f));
            later(s, t + 70, () -> {
                s.food.remove(slot, d);
                s.laid.remove(slot);
                s.own.remove(d);
                s.warm.remove(d);
                if (d.isValid()) d.remove();
            });
            t += 9;
        }
        t += v.wrong().isEmpty() ? 10 : 30;
        for (int slot : v.missing()) {
            later(s, t, () -> glide(s.spoon, spoonTo(s, slot, 0.07f), flat(35), 0.3f, 9));
            later(s, t + 10, () -> {
                glide(s.spoon, spoonTo(s, slot, 0.0f), flat(35), 0.3f, 2);
                Location at = dishAt(s, slot);
                m.haunt().sound(p, at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.5f, 1.7f);
                p.spawnParticle(Particle.WAX_OFF, at.clone().add(0, 0.05, 0), 2, 0.03, 0.01, 0.03, 0);
            });
            later(s, t + 14, () -> glide(s.spoon, spoonTo(s, slot, 0.07f), flat(35), 0.3f, 3));
            t += 26;
        }
        if (!v.missing().isEmpty()) later(s, t, () -> glide(s.spoon, new Vector3f(), flat(35), 0.3f, 9));
        later(s, t + 24, () -> {
            s.smoke = false;
            Location at = s.at(BURNER[0], BURNER[1], BURNER[2]);
            m.haunt().sound(p, at, Sound.BLOCK_FIRE_EXTINGUISH, 0.2f, 1.8f);
            p.spawnParticle(Particle.SMOKE, at, 4, 0.03, 0.05, 0.03, 0.005);
        });
        later(s, t + 50, () -> {
            s.busy = false;
            then.run();
        });
    }

    /** What the dead say during the meal: line (1..4) -> said at the table. */
    interface Voice {
        void say(int line, Location at);
    }

    /**
     * The meal: a door somewhere, the flames lean, the smoke bends to the table, the spoon stands itself in the rice, the
     * chopsticks tap a dish, the cup goes down — and two spoons are heard, not quite together. The dishes empty one by
     * one, the tablets stir. About 38 seconds; `then` runs at the end (dawn, and whether it counted, are the caller's).
     */
    void meal(Player p, Shown s, Map<Integer, String> layout, Table.Rules rules, Voice voice, Runnable then) {
        s.busy = true;
        Location north = s.at(0.5, 1.2, -7);
        m.haunt().sound(p, north, Sound.BLOCK_WOODEN_DOOR_OPEN, 0.3f, 0.55f);
        later(s, 12, () -> {
            s.lean = true;
            for (int i = 0; i < 2; i++) {
                BlockDisplay c = s.candles[i];
                if (c == null || !c.isValid()) continue;
                c.setInterpolationDelay(0);
                c.setInterpolationDuration(30);
                c.setTransformation(candleAt(i, 9));
            }
            m.haunt().sound(p, s.table(), Sound.BLOCK_WOOL_STEP, 0.3f, 0.5f);
        });
        later(s, 50, () -> s.bend = true);
        int rice = find(layout, rules.rice(), Table.slot(0, Table.WEST_AT));
        int dish = find(layout, rules.fish(), Table.slot(1, Table.EAST_AT));
        int cup = find(layout, rules.liquor(), -1);
        // the spoon stands in the rice
        later(s, 90, () -> glide(s.spoon, spoonTo(s, rice, 0.16f), flat(35), 0.3f, 14));
        later(s, 106, () -> {
            glide(s.spoon, spoonTo(s, rice, 0.1f), new Quaternionf().rotateY((float) Math.PI).rotateZ((float) Math.toRadians(160)), 0.3f, 8);
            m.haunt().sound(p, dishAt(s, rice), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.5f, 1.5f);
        });
        // the chopsticks cross to a dish and tap it
        later(s, 136, () -> {
            Location to = dishAt(s, dish);
            for (int i = 0; i < 2; i++) {
                Vector3f v = from(new float[] {(float) s.base.getX() + STICKS[0] + i * 0.05f, (float) s.base.getY() + STICKS[1],
                        (float) s.base.getZ() + STICKS[2]}, to);
                v.y = 0.03f;
                glide(s.sticks[i], v, flat(20), 0.28f, 12);
            }
        });
        later(s, 150, () -> {
            m.haunt().sound(p, dishAt(s, dish), Sound.BLOCK_BAMBOO_WOOD_HIT, 0.45f, 1.7f);
            later(s, 5, () -> m.haunt().sound(p, dishAt(s, dish), Sound.BLOCK_BAMBOO_WOOD_HIT, 0.3f, 1.9f));
        });
        // the cup goes down
        if (cup >= 0) later(s, 176, () -> {
            ItemDisplay d = s.food.get(cup);
            if (d != null) glide(d, new Vector3f(0, -0.008f, 0), flat(0), DISH * 0.68f, 34);
            m.haunt().sound(p, dishAt(s, cup), Sound.ENTITY_GENERIC_DRINK, 0.18f, 0.7f);
        });
        // two spoons, not quite together: his on the west, and one more
        Location his = s.at(0.5 - 0.5, TOP, 0.3), hers = s.at(0.5 + 0.5, TOP, 0.3);
        for (int i = 0; i < 10; i++) {
            later(s, 200 + i * 34L, () -> m.haunt().sound(p, his, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.4f, 1.45f));
            later(s, 213 + i * 34L + (i % 3), () -> m.haunt().sound(p, hers, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.3f, 1.75f));
        }
        later(s, 250, () -> voice.say(1, hers.clone().add(0, 0.9, 0)));
        later(s, 330, () -> voice.say(2, hers.clone().add(0, 0.9, 0)));
        // the dishes empty one by one
        List<Integer> order = new ArrayList<>(new TreeMap<>(s.food).keySet());
        long step = order.isEmpty() ? 0 : Math.clamp(250L / order.size(), 6, 24);
        for (int i = 0; i < order.size(); i++) {
            int slot = order.get(i);
            boolean chew = i % 2 == 0;
            later(s, 300 + i * step, () -> {
                ItemDisplay d = s.food.get(slot);
                if (d == null || !d.isValid()) return;
                glide(d, new Vector3f(0, -0.01f, 0), flat(0), 0.01f, 14);
                if (chew) m.haunt().sound(p, d.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.1f, 0.75f);
            });
            later(s, 300 + i * step + 16, () -> {
                ItemDisplay d = s.food.remove(slot);
                s.laid.remove(slot);
                if (d == null) return;
                s.own.remove(d);
                s.warm.remove(d);
                if (d.isValid()) d.remove();
            });
        }
        // the tablets stir, both of them
        for (int k = 0; k < 5; k++) {
            int tilt = k == 4 ? 0 : k % 2 == 0 ? 7 : -6;
            later(s, 590 + k * 7L, () -> {
                for (int i = 0; i < 2; i++) {
                    double x = 0.5 + (i == 0 ? -0.13 : 0.13);
                    BlockDisplay slip = s.slips[i];
                    if (slip != null && slip.isValid()) {
                        slip.setInterpolationDelay(0);
                        slip.setInterpolationDuration(7);
                        slip.setTransformation(turned(x, 1.12, -0.46, 0.13, 0.6, 0.02, 0, i == 0 ? tilt : -tilt));
                    }
                    TextDisplay name = s.names[i];
                    if (name != null && name.isValid()) {
                        name.setInterpolationDelay(0);
                        name.setInterpolationDuration(7);
                        name.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateZ((float) Math.toRadians(i == 0 ? tilt : -tilt)),
                                new Vector3f(0.2f, 0.2f, 0.2f), new Quaternionf()));
                    }
                }
            });
        }
        later(s, 590, () -> m.haunt().sound(p, s.at(0.5, 1.4, -0.4), Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 0.75f));
        later(s, 604, () -> m.haunt().sound(p, s.at(0.5, 1.4, -0.4), Sound.ITEM_BOOK_PAGE_TURN, 0.35f, 0.9f));
        later(s, 640, () -> voice.say(3, s.at(0.5, 1.9, -0.3)));
        later(s, 720, () -> voice.say(4, s.at(0.5, 1.9, -0.3)));
        later(s, 780, () -> {
            s.busy = false;
            then.run();
        });
    }

    private static int find(Map<Integer, String> layout, java.util.Set<String> of, int or) {
        for (Map.Entry<Integer, String> e : new TreeMap<>(layout).entrySet()) if (of.contains(e.getValue())) return e.getKey();
        return or;
    }

    /** It was never there: the flames go, the house thins to ash, and there is only grass. */
    void dissolve(Player p, Shown s) {
        s.busy = true;
        s.lit = false;
        s.smoke = false;
        World w = s.base.getWorld();
        m.haunt().sound(p, s.table(), Sound.BLOCK_CANDLE_EXTINGUISH, 0.4f, 0.8f);
        later(s, 6, () -> m.haunt().sound(p, s.table(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.35f, 0.5f));
        for (int i = 0; i < 4; i++) {
            later(s, 4 + i * 10L, () -> {
                p.spawnParticle(Particle.WHITE_ASH, s.at(0.5, 1.5, 0.5), 90, 2.2, 1.4, 2.2, 0.01);
                p.spawnParticle(Particle.WHITE_ASH, s.at(0.5, 3.1, 0.5), 60, 2.4, 0.3, 2.4, 0.01);
            });
        }
        later(s, 44, () -> {
            if (w != null) p.spawnParticle(Particle.WHITE_ASH, s.at(0.5, 0.6, 0.5), 40, 1.2, 0.3, 1.2, 0.01);
            hide(s.owner);
        });
    }
}
