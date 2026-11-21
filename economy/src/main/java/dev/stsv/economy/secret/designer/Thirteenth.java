package dev.stsv.economy.secret.designer;

import dev.stsv.economy.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The 13th machine: no art of its own, a group of block displays in the shape of a strange machine (an iron and copper
 * frame, a copper body ringed with bone and laced with redstone that slowly breathes, a heart gear that turns on top, and an
 * observer lens with a red pupil that turns to the nearest player). One on the server: its block is kept in eco.kv
 * {@link #KEY}; the displays are saved with the chunk and put back whenever they are missing (or doubled). Previews (admin
 * scenes) are the same shape, not saved, gone after a while.
 */
final class Thirteenth {

    static final String KEY = "secret.designer.machine13";
    /** On every saved piece: its index in {@link #PIECES} (-1 = the click box). */
    static final NamespacedKey TAG = new NamespacedKey("economy", "designer_13");

    enum Group { FRAME, BODY, GEAR, LENS, PUPIL }

    /** One block display: centre and size in blocks (origin = the block's corner), spin = a fixed turn about y (gear teeth). */
    record Piece(String block, Group group, float x, float y, float z, float sx, float sy, float sz, float spin) {}

    static final float[] BODY = {0.5f, 1.0f, 0.5f};
    static final float[] GEAR = {0.5f, 1.36f, 0.5f};
    static final float[] LENS = {0.5f, 1.6f, 0.5f};
    static final List<Piece> PIECES = pieces();

    private static List<Piece> pieces() {
        List<Piece> l = new ArrayList<>();
        // frame: a dark plate, four iron posts, two copper rings, iron rods holding the body in the middle
        l.add(new Piece("minecraft:polished_deepslate", Group.FRAME, 0.5f, 0.06f, 0.5f, 1.0f, 0.12f, 1.0f, 0));
        for (float[] c : new float[][]{{0.07f, 0.07f}, {0.93f, 0.07f}, {0.07f, 0.93f}, {0.93f, 0.93f}})
            l.add(new Piece("minecraft:iron_block", Group.FRAME, c[0], 0.98f, c[1], 0.1f, 1.72f, 0.1f, 0));
        for (float[] ring : new float[][]{{1.86f, 0}, {0.38f, 1}}) {
            String b = ring[1] == 0 ? "minecraft:weathered_cut_copper" : "minecraft:exposed_cut_copper";
            l.add(new Piece(b, Group.FRAME, 0.5f, ring[0], 0.07f, 0.96f, 0.08f, 0.08f, 0));
            l.add(new Piece(b, Group.FRAME, 0.5f, ring[0], 0.93f, 0.96f, 0.08f, 0.08f, 0));
            l.add(new Piece(b, Group.FRAME, 0.07f, ring[0], 0.5f, 0.08f, 0.08f, 0.96f, 0));
            l.add(new Piece(b, Group.FRAME, 0.93f, ring[0], 0.5f, 0.08f, 0.08f, 0.96f, 0));
        }
        l.add(new Piece("minecraft:iron_block", Group.FRAME, 0.5f, 1.0f, 0.5f, 0.86f, 0.04f, 0.04f, 0));
        l.add(new Piece("minecraft:iron_block", Group.FRAME, 0.5f, 1.0f, 0.5f, 0.04f, 0.04f, 0.86f, 0));
        // veins running down from the body into the plate
        l.add(new Piece("minecraft:redstone_block", Group.FRAME, 0.4f, 0.42f, 0.6f, 0.03f, 0.6f, 0.03f, 0));
        l.add(new Piece("minecraft:redstone_block", Group.FRAME, 0.6f, 0.42f, 0.4f, 0.03f, 0.6f, 0.03f, 0));
        // body: a copper core, ribs of bone, veins of redstone
        l.add(new Piece("minecraft:weathered_copper", Group.BODY, 0.5f, 1.0f, 0.5f, 0.46f, 0.58f, 0.46f, 0));
        for (float y : new float[]{0.84f, 0.98f, 1.12f})
            l.add(new Piece("minecraft:bone_block", Group.BODY, 0.5f, y, 0.5f, 0.52f, 0.05f, 0.52f, 0));
        l.add(new Piece("minecraft:redstone_block", Group.BODY, 0.42f, 1.0f, 0.735f, 0.035f, 0.5f, 0.03f, 0));
        l.add(new Piece("minecraft:redstone_block", Group.BODY, 0.58f, 0.97f, 0.735f, 0.035f, 0.42f, 0.03f, 0));
        l.add(new Piece("minecraft:redstone_block", Group.BODY, 0.46f, 1.0f, 0.265f, 0.035f, 0.5f, 0.03f, 0));
        l.add(new Piece("minecraft:redstone_block", Group.BODY, 0.735f, 1.02f, 0.5f, 0.03f, 0.46f, 0.035f, 0));
        l.add(new Piece("minecraft:redstone_block", Group.BODY, 0.265f, 0.98f, 0.5f, 0.03f, 0.5f, 0.035f, 0));
        // the heart gear on top: two crossed plates and a red hub, turning
        l.add(new Piece("minecraft:chiseled_copper", Group.GEAR, GEAR[0], GEAR[1], GEAR[2], 0.26f, 0.07f, 0.26f, 0));
        l.add(new Piece("minecraft:exposed_chiseled_copper", Group.GEAR, GEAR[0], GEAR[1], GEAR[2], 0.26f, 0.06f, 0.26f, (float) Math.PI / 4));
        l.add(new Piece("minecraft:redstone_block", Group.GEAR, GEAR[0], GEAR[1] + 0.02f, GEAR[2], 0.1f, 0.09f, 0.1f, 0));
        // the lens (its face looks along +z) and its pupil, just in front of the face
        l.add(new Piece("minecraft:observer[facing=south]", Group.LENS, LENS[0], LENS[1], LENS[2], 0.26f, 0.26f, 0.26f, 0));
        l.add(new Piece("minecraft:redstone_block", Group.PUPIL, LENS[0], LENS[1], LENS[2] + 0.135f, 0.08f, 0.08f, 0.02f, 0));
        return List.copyOf(l);
    }

    /** One standing machine (the saved one or a preview) and how it moves. */
    static final class Body {
        final Location origin;
        final BlockDisplay[] parts = new BlockDisplay[PIECES.size()];
        Interaction hit;
        int age, breaths;
        boolean in;
        float gear, yaw, pitch = 0.3f;
        long until;   // previews: when it goes (ms)

        Body(Location origin) {
            this.origin = origin;
        }

        boolean valid() {
            for (BlockDisplay d : parts) if (d == null || !d.isValid()) return false;
            return true;
        }

        void remove() {
            for (BlockDisplay d : parts) if (d != null && d.isValid()) d.remove();
            if (hit != null && hit.isValid()) hit.remove();
        }

        Location lensAt() {
            return origin.clone().add(LENS[0], LENS[1], LENS[2]);
        }
    }

    private final Designer d;
    private final EcoPlugin eco;
    private Body real;
    private final List<Body> previews = new ArrayList<>();

    Thirteenth(Designer d, EcoPlugin eco) {
        this.d = d;
        this.eco = eco;
    }

    // ------------------------------------------------------------------------------------------
    // where it is
    // ------------------------------------------------------------------------------------------

    /** Its block ("world x y z"), if one stands. */
    Optional<String> where() {
        return Optional.ofNullable(eco.kv().get(KEY));
    }

    /** Whether the one standing was put there by a tester (a real solver's replaces it). */
    boolean isTest() {
        return eco.kv().has(KEY + ".test");
    }

    /**
     * Puts it at a block (the one there before goes). A tester's never replaces a real one: false then.
     * birth = it puts itself together piece by piece.
     */
    boolean place(Location block, boolean test, boolean birth) {
        if (test && where().isPresent() && !isTest()) return false;
        remove();
        Location o = block.getBlock().getLocation();
        eco.kv().set(KEY, Parts.encode(o.getWorld().getName(), o.getBlockX(), o.getBlockY(), o.getBlockZ()));
        if (test) eco.kv().set(KEY + ".test", 1);
        else eco.kv().remove(KEY + ".test");
        real = spawn(o, true, birth);
        return true;
    }

    /** Takes it away (and forgets where it was). */
    void remove() {
        if (real != null) real.remove();
        real = null;
        Location o = origin();
        if (o != null && o.isChunkLoaded()) for (Entity e : tagged(o)) e.remove();
        eco.kv().remove(KEY);
        eco.kv().remove(KEY + ".test");
    }

    /** A copy that stands for a while and is not saved (admin scene). */
    void preview(Location block, int seconds) {
        Body b = spawn(block.getBlock().getLocation(), false, true);
        b.until = System.currentTimeMillis() + seconds * 1000L;
        previews.add(b);
    }

    void clearPreviews() {
        previews.forEach(Body::remove);
        previews.clear();
    }

    private Location origin() {
        Object[] w = Parts.decode(eco.kv().get(KEY));
        if (w == null) return null;
        World world = Bukkit.getWorld((String) w[0]);
        return world == null ? null : new Location(world, (Integer) w[1], (Integer) w[2], (Integer) w[3]);
    }

    private static List<Entity> tagged(Location o) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : o.getWorld().getNearbyEntities(o.clone().add(0.5, 1, 0.5), 2, 3, 2)) {
            if (e.getPersistentDataContainer().has(TAG, PersistentDataType.INTEGER)) out.add(e);
        }
        return out;
    }

    /** The saved one, found again after its chunk loaded, or put back when something is missing. */
    private void ensure() {
        Location o = origin();
        if (o == null) {
            if (real != null) real.remove();
            real = null;
            return;
        }
        if (real != null && real.valid() && real.origin.equals(o)) return;
        real = null;
        if (!o.getWorld().isChunkLoaded(o.getBlockX() >> 4, o.getBlockZ() >> 4)) return;
        if (!o.getWorld().getChunkAt(o.getBlockX() >> 4, o.getBlockZ() >> 4).isEntitiesLoaded()) return;
        List<Entity> found = tagged(o);
        Body b = new Body(o);
        boolean whole = true;
        Map<Integer, Integer> seen = new HashMap<>();
        for (Entity e : found) {
            int i = e.getPersistentDataContainer().getOrDefault(TAG, PersistentDataType.INTEGER, -2);
            if (seen.merge(i, 1, Integer::sum) > 1) whole = false;
            if (i == -1 && e instanceof Interaction x) b.hit = x;
            else if (i >= 0 && i < PIECES.size() && e instanceof BlockDisplay x) b.parts[i] = x;
            else whole = false;
        }
        if (whole && b.valid() && b.hit != null) {
            real = b;
            return;
        }
        for (Entity e : found) e.remove();
        real = spawn(o, true, false);
    }

    // ------------------------------------------------------------------------------------------
    // making one
    // ------------------------------------------------------------------------------------------

    private static BlockData data(String block) {
        try {
            return Bukkit.createBlockData(block);
        } catch (IllegalArgumentException e) {
            return Bukkit.createBlockData("minecraft:iron_block");
        }
    }

    private Body spawn(Location o, boolean persistent, boolean birth) {
        Body b = new Body(o);
        World w = o.getWorld();
        for (int i = 0; i < PIECES.size(); i++) {
            Piece p = PIECES.get(i);
            int idx = i;
            b.parts[i] = w.spawn(o, BlockDisplay.class, e -> {
                e.setBlock(data(p.block()));
                e.setPersistent(persistent);
                if (persistent) e.getPersistentDataContainer().set(TAG, PersistentDataType.INTEGER, idx);
                e.setTransformation(birth ? seed(p) : transform(b, idx, 1f));
                if (p.group() == Group.PUPIL) e.setBrightness(new Display.Brightness(15, 15));
                e.setViewRange(0.75f);
            });
        }
        b.hit = w.spawn(o.clone().add(0.5, 0, 0.5), Interaction.class, e -> {
            e.setInteractionWidth(1.0f);
            e.setInteractionHeight(1.95f);
            e.setResponsive(false);
            e.setPersistent(persistent);
            if (persistent) e.getPersistentDataContainer().set(TAG, PersistentDataType.INTEGER, -1);
        });
        if (!persistent) b.hit.getPersistentDataContainer().set(Designer.PREVIEW, PersistentDataType.BYTE, (byte) 1);
        if (birth) assemble(b);
        return b;
    }

    /** A piece before it is there: nothing, at its centre. */
    private static Transformation seed(Piece p) {
        return new Transformation(new Vector3f(p.x(), p.y(), p.z()), new Quaternionf(), new Vector3f(0.001f), new Quaternionf());
    }

    /** Frame first, then the body, the heart, and last the eye opens. */
    private void assemble(Body b) {
        int[] delay = {0};
        for (Group g : Group.values()) {
            for (int i = 0; i < PIECES.size(); i++) {
                if (PIECES.get(i).group() != g) continue;
                int idx = i;
                int at = g == Group.FRAME ? delay[0]++ * 2 : 0;
                long when = switch (g) {
                    case FRAME -> 5 + at;
                    case BODY -> 70;
                    case GEAR -> 95;
                    case LENS, PUPIL -> 125;
                };
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    BlockDisplay x = b.parts[idx];
                    if (x == null || !x.isValid()) return;
                    x.setInterpolationDelay(0);
                    x.setInterpolationDuration(g == Group.LENS || g == Group.PUPIL ? 30 : 18);
                    x.setTransformation(transform(b, idx, 1f));
                }, when);
            }
        }
        for (long t : new long[]{5, 30, 55, 70, 95}) Bukkit.getScheduler().runTaskLater(eco, () -> d.sound(b.origin.clone().add(0.5, 0.5, 0.5), "assemble"), t);
        Bukkit.getScheduler().runTaskLater(eco, () -> d.sound(b.origin.clone().add(0.5, 1, 0.5), "heartbeat"), 95);
        Bukkit.getScheduler().runTaskLater(eco, () -> d.sound(b.origin.clone().add(0.5, 1, 0.5), "breath"), 140);
        b.age = -8;   // no breathing or looking before it is whole
    }

    /** Where a piece is now: the body grown by f, the gear turned, the lens and pupil aimed. */
    private static Transformation transform(Body b, int i, float f) {
        Piece p = PIECES.get(i);
        float[] c = {p.x(), p.y(), p.z()};
        Vector3f size = new Vector3f(p.sx(), p.sy(), p.sz());
        Quaternionf q = new Quaternionf();
        switch (p.group()) {
            case BODY -> {
                c = Parts.breathe(c, BODY, f);
                size.mul(f);
            }
            case GEAR -> q.rotateY(b.gear + p.spin());
            case LENS -> q.rotateY(b.yaw).rotateX(b.pitch);
            case PUPIL -> {
                q.rotateY(b.yaw).rotateX(b.pitch);
                Vector3f off = q.transform(new Vector3f(c[0] - LENS[0], c[1] - LENS[1], c[2] - LENS[2]));
                c = new float[]{LENS[0] + off.x, LENS[1] + off.y, LENS[2] + off.z};
            }
            default -> { }
        }
        // rotate and scale about the piece's own centre
        Vector3f half = q.transform(new Vector3f(size).mul(0.5f));
        Vector3f t = new Vector3f(c[0], c[1], c[2]).sub(half);
        return new Transformation(t, q, size, new Quaternionf());
    }

    // ------------------------------------------------------------------------------------------
    // each second
    // ------------------------------------------------------------------------------------------

    void tick() {
        ensure();
        if (real != null) animate(real);
        long now = System.currentTimeMillis();
        previews.removeIf(b -> {
            if (b.until > now && b.valid()) {
                animate(b);
                return false;
            }
            b.remove();
            return true;
        });
    }

    private void animate(Body b) {
        if (++b.age <= 0) return;
        int breath = Math.max(1, d.cfg().getInt("thirteenth.breath-seconds", 3));
        float scale = (float) d.cfg().getDouble("thirteenth.breath-scale", 1.07);
        boolean turn = (b.age - 1) % breath == 0;
        if (turn) {
            b.in = !b.in;
            if (b.in) b.breaths++;
            int every = d.cfg().getInt("thirteenth.sound-every", 4);
            if (b.in && every > 0 && b.breaths % every == 0) d.sound(b.origin.clone().add(0.5, 1, 0.5), "breath");
        }
        // the heart turns slowly ("이제 내가 돌게"); the quaternion repeats every 4π, so wrap there to stay continuous
        b.gear = (float) ((b.gear + Math.PI / 9) % (4 * Math.PI));
        Player near = nearest(b, d.cfg().getDouble("thirteenth.look-range", 16));
        if (near != null) {
            Location eye = near.getEyeLocation(), lens = b.lensAt();
            float[] a = Parts.look(eye.getX() - lens.getX(), eye.getY() - lens.getY(), eye.getZ() - lens.getZ());
            b.yaw = closest(b.yaw, a[0]);
            b.pitch = a[1];
        } else {
            b.pitch = 0.3f;   // nobody: it looks at the floor
        }
        float f = b.in ? scale : 1f;
        for (int i = 0; i < PIECES.size(); i++) {
            BlockDisplay x = b.parts[i];
            if (x == null || !x.isValid()) continue;
            Group g = PIECES.get(i).group();
            if (g == Group.FRAME) continue;
            if (g == Group.BODY && !turn) continue;
            x.setInterpolationDelay(0);
            x.setInterpolationDuration(switch (g) {
                case BODY -> breath * 20;
                case GEAR -> 21;
                default -> 10;
            });
            x.setTransformation(transform(b, i, f));
        }
    }

    /** The same angle as want, moved by whole turns to be nearest to now (the lens turns the short way). */
    static float closest(float now, float want) {
        double tau = 2 * Math.PI;
        double k = Math.round((now - want) / tau);
        return (float) (want + k * tau);
    }

    private static Player nearest(Body b, double range) {
        Location lens = b.lensAt();
        Player best = null;
        double bd = range * range;
        for (Player p : lens.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            double ds = p.getEyeLocation().distanceSquared(lens);
            if (ds < bd) {
                bd = ds;
                best = p;
            }
        }
        return best;
    }
}
