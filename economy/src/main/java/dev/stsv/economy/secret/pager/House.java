package dev.stsv.economy.secret.pager;

import dev.stsv.economy.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The house with the blue gate, as far as anyone ever sees it: its front, for one viewer. A low wall, the gate (two blue
 * leaves between two posts under a small roof), a basket hanging on the gate, a red mailbox on the wall, and above the
 * wall the side of the house with one small window. Block displays and two click boxes, nothing saved, nothing solid.
 * <p>
 * Everything is laid out in the house's own measure and turned by its yaw: x to the viewer's right, y up from the
 * ground, z towards the viewer (the road), the middle of the gate at 0.
 */
final class House {

    /** On a click box: "&lt;owner uuid&gt; basket|mail". */
    static final NamespacedKey TAG = new NamespacedKey("economy", "pager_house");

    enum Light { LIT, DARK, WARM }

    enum Click { BASKET, MAIL }

    private static final float LEAF_W = 0.79f, LEAF_H = 1.95f, LEAF_D = 0.06f;
    private static final Vector3f HANDLE_AT = new Vector3f(0.62f, 0.95f, LEAF_D), HANDLE = new Vector3f(0.07f, 0.14f, 0.05f);
    private static final Vector3f FLAG = new Vector3f(0.03f, 0.3f, 0.1f), BASKET = new Vector3f(0.4f, 0.3f, 0.36f);

    private static final class Shown {
        Location base;
        float yaw;
        double cos, sin;
        boolean preview, ending;
        Light light;
        final List<Entity> all = new ArrayList<>();
        BlockDisplay leaf, handle, pane, flag, basket;
        Interaction basketHit, mailHit;
        ItemDisplay inside;

        Location at(double lx, double ly, double lz) {
            return new Location(base.getWorld(), base.getX() + lx * cos - lz * sin, base.getY() + ly, base.getZ() + lx * sin + lz * cos, yaw, 0);
        }
    }

    private final Pager s;
    private final EcoPlugin eco;
    private final Map<UUID, Shown> shown = new HashMap<>();
    /** anchor and seed -> when the rings already loaded were all looked at in vain (the next, further ring was not loaded) */
    private final Map<String, Long> searched = new HashMap<>();

    House(Pager s, EcoPlugin eco) {
        this.s = s;
        this.eco = eco;
    }

    boolean showing(UUID u) {
        return shown.containsKey(u);
    }

    boolean ending(UUID u) {
        Shown x = shown.get(u);
        return x != null && x.ending;
    }

    boolean preview(UUID u) {
        Shown x = shown.get(u);
        return x != null && x.preview;
    }

    /** Whether what was shown is no longer there (its chunk was unloaded, or something removed the entities). */
    boolean gone(UUID u) {
        Shown x = shown.get(u);
        return x != null && !(x.leaf.isValid() && x.basketHit.isValid() && x.mailHit.isValid());
    }

    /** The middle of the gate, on the ground. */
    Location where(UUID u) {
        Shown x = shown.get(u);
        return x == null ? null : x.base.clone();
    }

    // ------------------------------------------------------------------------------------------
    // building it
    // ------------------------------------------------------------------------------------------

    private BlockData block(String key, Material def) {
        String v = s.cfg().getString("house.blocks." + key);
        if (v != null && !v.isBlank()) {
            try {
                return Bukkit.createBlockData(v.trim().toLowerCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                eco.getLogger().warning("[비밀] pager.yml house.blocks." + key + " 이 블록이 아님: " + v);
            }
        }
        return def.createBlockData();
    }

    private BlockDisplay box(Player p, Shown x, double lx, double ly, double lz, float sx, float sy, float sz, BlockData data) {
        Location at = x.at(lx, ly, lz);
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setBlock(data);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(sx, sy, sz), new Quaternionf()));
            e.setBrightness(new Display.Brightness(0, 15));   // lit like open ground: by the sky, day and night
        });
        p.showEntity(eco, d);
        x.all.add(d);
        return d;
    }

    private Interaction hit(Player p, Shown x, double lx, double ly, double lz, float w, float h, String kind) {
        Location at = x.at(lx, ly, lz);
        Interaction i = at.getWorld().spawn(at, Interaction.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setInteractionWidth(w);
            e.setInteractionHeight(h);
            e.setResponsive(true);
            e.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, p.getUniqueId() + " " + kind);
        });
        p.showEntity(eco, i);
        x.all.add(i);
        return i;
    }

    /** A part hanging on a hinge (the display stands at the hinge): turned by q about it, {@code offset} away from it. */
    private static Transformation hinged(Vector3f offset, Quaternionf q, Vector3f scale) {
        return new Transformation(q.transform(new Vector3f(offset)), new Quaternionf(q), new Vector3f(scale), new Quaternionf());
    }

    /** The house front for this viewer, the middle of its gate on the ground at {@code base}, facing {@code yaw}. */
    void show(Player p, Location base, float yaw, Light light, boolean preview) {
        hide(p.getUniqueId());
        Shown x = new Shown();
        x.base = base.clone();
        x.yaw = yaw;
        double r = Math.toRadians(yaw);
        x.cos = Math.cos(r);
        x.sin = Math.sin(r);
        x.preview = preview;
        x.light = light;
        BlockData wall = block("wall", Material.ANDESITE), cap = block("cap", Material.SMOOTH_STONE), post = block("post", Material.POLISHED_ANDESITE),
                roof = block("roof", Material.DEEPSLATE_TILES), gate = block("gate", Material.BLUE_CONCRETE), iron = block("handle", Material.IRON_BLOCK),
                wood = block("frame", Material.DARK_OAK_PLANKS), red = block("mailbox", Material.RED_CONCRETE), lid = block("mailbox-lid", Material.RED_TERRACOTTA),
                plaster = block("house", Material.WHITE_TERRACOTTA);
        // the wall, either side of the gate, and its coping
        for (int i = 0; i < 3; i++) {
            box(p, x, -4.1 + i, 0, -0.25, 1, 1.45f, 0.5f, wall);
            box(p, x, 1.1 + i, 0, -0.25, 1, 1.45f, 0.5f, wall);
        }
        box(p, x, -4.15, 1.45, -0.32, 3.1f, 0.12f, 0.64f, cap);
        box(p, x, 1.05, 1.45, -0.32, 3.1f, 0.12f, 0.64f, cap);
        // the gate: posts, the little roof over it, a step, two blue leaves
        box(p, x, -1.12, 0, -0.3, 0.32f, 2.25f, 0.6f, post);
        box(p, x, 0.8, 0, -0.3, 0.32f, 2.25f, 0.6f, post);
        box(p, x, -1.3, 2.25, -0.4, 2.6f, 0.16f, 0.8f, roof);
        box(p, x, -0.9, 0, 0.1, 1.8f, 0.07f, 0.5f, cap);
        x.leaf = box(p, x, -0.8, 0.06, -0.03, LEAF_W, LEAF_H, LEAF_D, gate);
        x.handle = box(p, x, -0.8, 0.06, -0.03, 1, 1, 1, iron);
        x.handle.setTransformation(hinged(HANDLE_AT, new Quaternionf(), HANDLE));
        box(p, x, 0.01, 0.06, -0.03, LEAF_W, LEAF_H, LEAF_D, gate);
        box(p, x, 0.11, 1.01, 0.03, HANDLE.x, HANDLE.y, HANDLE.z, iron);
        // the basket on the gate
        box(p, x, 0.49, 1.13, 0.03, 0.03f, 0.55f, 0.03f, wood);
        x.basket = box(p, x, 0.3, 0.85, 0.04, BASKET.x, BASKET.y, BASKET.z, block("basket", Material.COMPOSTER));
        // the mailbox on the wall
        box(p, x, 1.4, 0.95, 0.25, 0.5f, 0.36f, 0.3f, red);
        box(p, x, 1.37, 1.31, 0.23, 0.56f, 0.05f, 0.35f, lid);
        box(p, x, 1.5, 1.2, 0.55, 0.3f, 0.035f, 0.012f, block("slot", Material.BLACK_CONCRETE));
        x.flag = box(p, x, 1.91, 1.0, 0.37, FLAG.x, FLAG.y, FLAG.z, block("flag", Material.WHITE_CONCRETE));
        // behind the wall: the side of the house, its eaves, one small window
        box(p, x, -0.6, 0, -3.2, 5.2f, 3.0f, 0.3f, plaster);
        box(p, x, -0.9, 3.0, -3.5, 5.8f, 0.22f, 1.0f, roof);
        box(p, x, 2.2, 1.7, -2.92, 1.2f, 0.95f, 0.06f, wood);
        x.pane = box(p, x, 2.3, 1.78, -2.87, 1.0f, 0.79f, 0.03f, wall);
        box(p, x, 2.785, 1.78, -2.845, 0.03f, 0.79f, 0.02f, wood);
        box(p, x, 2.3, 2.16, -2.845, 1.0f, 0.03f, 0.02f, wood);
        x.basketHit = hit(p, x, 0.5, 0.78, 0.24, 0.6f, 0.55f, "basket");
        x.mailHit = hit(p, x, 1.66, 0.9, 0.4, 0.7f, 0.55f, "mail");
        shown.put(p.getUniqueId(), x);
        pane(x, light);
    }

    void hide(UUID u) {
        Shown x = shown.remove(u);
        if (x == null) return;
        for (Entity e : x.all) if (e.isValid()) e.remove();
        if (x.inside != null && x.inside.isValid()) x.inside.remove();
    }

    void clearAll() {
        for (UUID u : new HashSet<>(shown.keySet())) hide(u);
    }

    // ------------------------------------------------------------------------------------------
    // what it does
    // ------------------------------------------------------------------------------------------

    private void pane(Shown x, Light l) {
        x.light = l;
        if (!x.pane.isValid()) return;
        switch (l) {
            case LIT -> {
                x.pane.setBlock(block("window-lit", Material.OCHRE_FROGLIGHT));
                x.pane.setBrightness(new Display.Brightness(12, 12));
            }
            case WARM -> {
                x.pane.setBlock(block("window-warm", Material.SHROOMLIGHT));
                x.pane.setBrightness(new Display.Brightness(15, 15));
            }
            default -> {
                x.pane.setBlock(block("window-dark", Material.BLACK_CONCRETE));
                x.pane.setBrightness(new Display.Brightness(1, 1));
            }
        }
    }

    void light(UUID u, Light l) {
        Shown x = shown.get(u);
        if (x != null && x.light != l) pane(x, l);
    }

    /** The window goes out for a moment and comes back, once. */
    void flicker(Player p) {
        Shown x = shown.get(p.getUniqueId());
        if (x == null) return;
        Light was = x.light == Light.DARK ? Light.LIT : x.light;
        pane(x, Light.DARK);
        later(p, x, 5, () -> pane(x, was));
        later(p, x, 9, () -> pane(x, Light.DARK));
        later(p, x, 12, () -> pane(x, was));
    }

    /** The basket swings on its hook (it took nothing). */
    void swing(Player p) {
        Shown x = shown.get(p.getUniqueId());
        if (x == null || !x.basket.isValid()) return;
        tilt(x.basket, 0.2f, 3);
        later(p, x, 4, () -> tilt(x.basket, -0.13f, 4));
        later(p, x, 9, () -> tilt(x.basket, 0.06f, 4));
        later(p, x, 14, () -> tilt(x.basket, 0, 4));
        s.sound(p, x.at(0.5, 1.0, 0.2), "block.scaffolding.hit", 0.5f, 0.8f);
    }

    private static void tilt(BlockDisplay d, float angle, int ticks) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateZ(angle), new Vector3f(BASKET), new Quaternionf()));
    }

    /** Something lies in the basket (until {@link #empty}). */
    void fill(Player p, ItemStack it) {
        Shown x = shown.get(p.getUniqueId());
        if (x == null) return;
        empty(p.getUniqueId());
        ItemStack one = it.clone();
        one.setAmount(1);
        Location at = x.at(0.5, 1.08, 0.22);
        x.inside = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setItemStack(one);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateX((float) Math.toRadians(-70)), new Vector3f(0.3f, 0.3f, 0.3f), new Quaternionf()));
            e.setBrightness(new Display.Brightness(0, 15));
        });
        p.showEntity(eco, x.inside);
    }

    void empty(UUID u) {
        Shown x = shown.get(u);
        if (x == null || x.inside == null) return;
        if (x.inside.isValid()) x.inside.remove();
        x.inside = null;
    }

    /** The gate's latch, from inside. */
    void latch(Player p) {
        Shown x = shown.get(p.getUniqueId());
        if (x == null) return;
        s.sound(p, x.at(0, 1.0, -0.2), "block.iron_trapdoor.close", 0.45f, 1.4f);
    }

    /** The mailbox's lid, touched. */
    void tap(Player p) {
        Shown x = shown.get(p.getUniqueId());
        if (x == null) return;
        s.sound(p, x.at(1.65, 1.2, 0.4), "block.iron_trapdoor.close", 0.25f, 1.8f);
    }

    /** Which of this viewer's own click boxes was hit, or null (someone else's, or none of ours). */
    Click click(Player p, Interaction hit) {
        String tag = hit.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
        Shown x = shown.get(p.getUniqueId());
        if (tag == null || x == null || !tag.startsWith(p.getUniqueId().toString())) return null;
        return hit.equals(x.basketHit) ? Click.BASKET : hit.equals(x.mailHit) ? Click.MAIL : null;
    }

    /**
     * The end, about half a minute: the mailbox's flag drops, the window comes on warm, the gate opens a hand's width, a
     * long quiet, then one triple beep from inside and one line; the light goes out. Then {@code done}.
     */
    void ending(Player p, String line, Runnable done) {
        Shown x = shown.get(p.getUniqueId());
        if (x == null || x.ending) return;
        x.ending = true;
        empty(p.getUniqueId());
        s.sound(p, x.at(1.65, 1.2, 0.4), "block.iron_trapdoor.close", 0.6f, 0.8f);
        if (x.flag.isValid()) {
            x.flag.setInterpolationDelay(0);
            x.flag.setInterpolationDuration(8);
            x.flag.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateX((float) (Math.PI / 2)), new Vector3f(FLAG), new Quaternionf()));
        }
        later(p, x, 50, () -> pane(x, Light.WARM));
        later(p, x, 120, () -> {
            Quaternionf q = new Quaternionf().rotateY(0.17f);
            for (BlockDisplay d : new BlockDisplay[] {x.leaf, x.handle}) {
                if (!d.isValid()) continue;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(40);
            }
            if (x.leaf.isValid()) x.leaf.setTransformation(hinged(new Vector3f(), q, new Vector3f(LEAF_W, LEAF_H, LEAF_D)));
            if (x.handle.isValid()) x.handle.setTransformation(hinged(HANDLE_AT, q, HANDLE));
            s.sound(p, x.at(-0.8, 1.0, 0), "block.iron_door.open", 0.3f, 0.55f);
        });
        later(p, x, 300, () -> s.chirp(p, x.at(1.5, 1.2, -3.6), 0.9f));
        later(p, x, 380, () -> p.sendMessage(dev.stsv.core.util.Text.mm(line)));
        later(p, x, 480, () -> pane(x, Light.DARK));
        later(p, x, 560, () -> {
            x.ending = false;   // over: from here it is a house like any other (and goes when its story says so)
            done.run();
        });
    }

    private void later(Player p, Shown x, long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline() && shown.get(p.getUniqueId()) == x) r.run();
        }, ticks);
    }

    // ------------------------------------------------------------------------------------------
    // where it stands
    // ------------------------------------------------------------------------------------------

    /** The yaw (a quarter turn) that faces along (dx, dz). */
    static float quarter(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? -90 : 90;
        return dz >= 0 ? 0 : 180;
    }

    /** Whether everything within r blocks of (x, z) is loaded (so looking for ground loads nothing). */
    static boolean loaded(World w, double x, double z, int r) {
        for (int cx = ((int) Math.floor(x) - r) >> 4; cx <= ((int) Math.floor(x) + r) >> 4; cx++)
            for (int cz = ((int) Math.floor(z) - r) >> 4; cz <= ((int) Math.floor(z) + r) >> 4; cz++)
                if (!w.isChunkLoaded(cx, cz)) return false;
        return true;
    }

    /**
     * The top of the dry ground in a column near a height ({@link Dry}: a full, solid, dry block with three open blocks
     * over it, no water, lava or ice on the way down), or MIN_VALUE.
     */
    static int ground(World w, int x, int z, int around) {
        int y = Dry.ground(Dry.world(w), x, z, Math.min(w.getMaxHeight() - 4, around + 8), Math.max(w.getMinHeight(), around - 8));
        return Dry.found(y) ? y : Integer.MIN_VALUE;
    }

    /** The ground under a house front standing here (the middle of its gate on the ground at {@code base}, facing yaw), every column of it. The land must be loaded. */
    static Dry.Check under(Location base, float yaw) {
        World w = base.getWorld();
        int y = base.getBlockY();
        return Dry.footprint(Dry.world(w), Dry.house(base.getBlockX(), base.getBlockZ(), yaw),
                Math.min(w.getMaxHeight() - 4, y + 8), Math.max(w.getMinHeight(), y - 8));
    }

    /** Whether a house front may be put here: null when all its ground is dry, else why not (in words). The land must be loaded. */
    static String dry(Location base, float yaw) {
        return Dry.why(under(base, yaw));
    }

    /**
     * What must stay free for the house to be used: the gateway, head height where one stands before it, the basket, the
     * mailbox ({x, y, z} in its own measure). Only cells {@link #fits} found free when the place was chosen (the ground
     * one step before the gate may be a block higher).
     */
    private static final double[][] FREE = {{0, 0.2, 0.3}, {0, 1.2, 0.3}, {0, 1.2, 1.3}, {0.5, 1.05, 0.3}, {1.66, 1.15, 0.45}};

    /**
     * Whether a house standing here (the middle of its gate on the ground at {@code base}) has been built over since:
     * a block where the gateway, the basket or the mailbox is. A few block reads; the land must be loaded.
     */
    static boolean blocked(Location base, float yaw) {
        double r = Math.toRadians(yaw), cos = Math.cos(r), sin = Math.sin(r);
        World w = base.getWorld();
        for (double[] f : FREE) {
            int y = (int) Math.floor(base.getY() + f[1]);
            if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
            Block b = w.getBlockAt((int) Math.floor(base.getX() + f[0] * cos - f[2] * sin), y, (int) Math.floor(base.getZ() + f[0] * sin + f[2] * cos));
            if (!b.isPassable() || b.isLiquid()) return true;
        }
        return false;
    }

    /**
     * Whether the house front fits with the middle of its gate in this column: level ground along the wall (strict: all
     * of it at one height; else a block up or down), three clear blocks over it, no road, nobody's land. The ground's
     * top there, or MIN_VALUE.
     */
    private int fits(World w, int cx, int cz, int around, float yaw, boolean strict) {
        int gy = ground(w, cx, cz, around);
        if (gy == Integer.MIN_VALUE) return gy;
        double r = Math.toRadians(yaw), cos = Math.cos(r), sin = Math.sin(r);
        Material road = eco.roads() == null ? null : eco.roads().surfaceBlock();
        for (int lz = -3; lz <= 2; lz++) {
            for (int lx = -4; lx <= 4; lx++) {
                int x = (int) Math.floor(cx + 0.5 + lx * cos - lz * sin), z = (int) Math.floor(cz + 0.5 + lx * sin + lz * cos);
                int g = ground(w, x, z, gy);
                if (g == Integer.MIN_VALUE || Math.abs(g - gy) > (strict && lz >= -1 ? 0 : 1)) return Integer.MIN_VALUE;
                Material under = w.getBlockAt(x, g - 1, z).getType();
                if (road != null && under == road || under.name().endsWith("CONCRETE") || under == Material.DIRT_PATH) return Integer.MIN_VALUE;
                if (eco.land() != null && eco.land().playerLand(w.getName(), x, z)) return Integer.MIN_VALUE;
                if (lz < -1) continue;
                for (int dy = 0; dy < 3; dy++) {
                    Block b = w.getBlockAt(x, gy + dy, z);
                    if (dy == 0 && g > gy) continue;
                    if (!b.isPassable() || b.isLiquid()) return Integer.MIN_VALUE;
                }
            }
        }
        return gy;
    }

    /** {@link #locate}'s answer when all the land around was looked at and none of it is dry. */
    static final Location NOWHERE = new Location(null, 0, 0, 0);

    /**
     * A place for the house near an anchor (a stop's shelter; (nx, nz) points away from the road), its gate facing back
     * towards the road, its whole front on dry ground ({@link Dry}), level, no road, nobody's land. Looked for further
     * and further: 10 to 24 blocks behind the stop, then up to 42 behind it and 30 to either hand; {@code around} (the
     * spawn, which has no road) also on the other three sides, up to 48 out. Level ground first at each reach, then a
     * block up or down. The same search order for the same seed. Null when the land to look at next is not loaded yet
     * (ask again when they are nearer); {@link #NOWHERE} when all of it was looked at and nowhere is dry.
     */
    Location locate(World w, double ax, int ay, double az, double nx, double nz, long seed, boolean around) {
        List<double[]> dirs = new ArrayList<>();
        dirs.add(new double[] {nx, nz});
        int[][] reach = around ? new int[][] {{24, 12}, {42, 30}, {48, 48}} : new int[][] {{24, 12}, {42, 30}};
        Set<String> tried = new HashSet<>();
        String key = w.getName() + " " + Math.round(ax) + " " + Math.round(az) + " " + seed;
        Long nearFailed = searched.get(key);
        int from = nearFailed != null && System.currentTimeMillis() - nearFailed < 120_000L ? 1 : 0;   // (the near ring was all looked at: not again yet)
        for (int stage = 0; stage < reach.length; stage++) {
            if (stage == 2) {
                dirs.add(new double[] {-nx, -nz});
                dirs.add(new double[] {-nz, nx});
                dirs.add(new double[] {nz, -nx});
            }
            int far = reach[stage][0], wide = reach[stage][1];
            if (!loaded(w, ax, az, Math.max(far, wide) + 10)) {
                if (stage > 0) searched.put(key, System.currentTimeMillis());
                return null;
            }
            boolean skip = stage < from;
            for (int k = 0; k < dirs.size(); k++) {
                double[] d = dirs.get(k);
                float yaw = quarter(-d[0], -d[1]);
                List<int[]> spots = new ArrayList<>();
                for (int out = 10; out <= (stage == 0 ? 22 : far); out += stage == 0 ? 2 : 3)
                    for (int along = -wide; along <= wide; along += 3) {
                        double dist = Math.hypot(out, along);
                        if (dist < 10 || dist > far + 0.5) continue;
                        int x = (int) Math.floor(ax + d[0] * out - d[1] * along), z = (int) Math.floor(az + d[1] * out + d[0] * along);
                        if (tried.add(x + " " + z + " " + yaw) && !skip) spots.add(new int[] {x, z});
                    }
                Collections.shuffle(spots, new Random(seed + stage * 31L + k));
                for (boolean strict : new boolean[] {true, false}) {
                    for (int[] c : spots) {
                        int gy = fits(w, c[0], c[1], ay, yaw, strict);
                        if (gy != Integer.MIN_VALUE) return new Location(w, c[0] + 0.5, gy, c[1] + 0.5, yaw, 0);
                    }
                }
            }
        }
        searched.remove(key);
        return NOWHERE;   // never on whatever there is: it stands somewhere else
    }
}
