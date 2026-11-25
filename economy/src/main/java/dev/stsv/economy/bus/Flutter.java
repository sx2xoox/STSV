package dev.stsv.economy.bus;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 닭 든 할머니's hen (bus.yml types.*.hold-fly, README "마을버스"): a jolt or a bump sends what the passenger holds in the
 * main hand fluttering round the bus for about three seconds, then it comes back to the hands.
 *
 * <p>The hand is emptied and an item display of the `fly` model (the hen with her wings spread) rides on the mannequin
 * (so it moves with the bus for free); its hops are client-side interpolated transformations (two updates a hop), the wings
 * flap by swapping the display's item between the spread and the folded hen, a few white feathers fall, a cluck and a
 * flap now and then. Everything is cheap: one non-persistent display, about forty small metadata updates in all. When the
 * mannequin is gone (got off, the bus despawned, its chunk unloaded) the display goes with it within two ticks; the module's
 * stop() ends every one still flying.</p>
 */
final class Flutter extends BukkitRunnable {

    /** how long she flies (ticks), how long a hop takes, how often the runnable looks */
    static final int TICKS = 60, HOP = 10, STEP = 2;
    /** where the hands are from the mannequin's passenger point (the top of its head), blocks at scale 1: down, forward */
    static final float HANDS_DOWN = 1.02f, HANDS_FWD = 0.32f;
    /** the display's size (the held models are drawn twice their size; in the hand at 0.6) */
    static final float SIZE = 0.3f;

    private final Mannequin m;
    private final ItemDisplay d;
    private final ItemStack spread, folded;
    private final float scale;
    private final List<Vector3f> path;
    private final Set<Flutter> live;
    private int t;
    private boolean done;

    private Flutter(Mannequin m, ItemDisplay d, ItemStack spread, ItemStack folded, float scale, List<Vector3f> path,
                    Set<Flutter> live) {
        this.m = m;
        this.d = d;
        this.spread = spread;
        this.folded = folded;
        this.scale = scale;
        this.path = path;
        this.live = live;
    }

    /**
     * Sends what `m` holds in the main hand fluttering as the `fly` model (null when its hand is empty or it is not in a
     * world). `live` keeps it until it lands.
     */
    static Flutter start(Plugin plugin, Mannequin m, String fly, double scale, Set<Flutter> live) {
        EntityEquipment eq = m.getEquipment();
        if (eq == null || !m.isValid()) return null;
        ItemStack held = eq.getItemInMainHand();
        if (held == null || held.isEmpty()) return null;
        ItemStack spread = BusModule.prop(fly), folded = held.clone();
        float s = (float) Math.max(0.3, scale);
        List<Vector3f> path = path(s, ThreadLocalRandom.current());
        ItemDisplay d = m.getWorld().spawn(m.getLocation().add(0, 1.8 * s, 0), ItemDisplay.class, x -> {
            x.setPersistent(false);
            x.getPersistentDataContainer().set(BusModule.GUEST, PersistentDataType.BYTE, (byte) 1);
            x.setItemStack(spread);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            x.setBillboard(Display.Billboard.FIXED);
            x.setViewRange(0.6f);
            x.setTeleportDuration(0);
            x.setRotation(m.getLocation().getYaw(), 0);
            x.setTransformation(pose(path.getFirst(), 0, s));
        });
        if (!m.addPassenger(d)) {
            d.remove();
            return null;
        }
        eq.setItemInMainHand(null);
        Flutter f = new Flutter(m, d, spread, folded, s, path, live);
        live.add(f);
        Location at = f.where(path.getFirst());
        m.getWorld().playSound(at, Sound.ENTITY_CHICKEN_HURT, 0.9f, 1.15f);
        m.getWorld().playSound(at, Sound.ENTITY_PARROT_FLY, 0.7f, 1.3f);
        feathers(at, 6);
        f.runTaskTimer(plugin, 1, STEP);
        return f;
    }

    /**
     * The hops (in the mannequin's own frame from its passenger point, blocks: x to its left, y up, z forward): from the
     * hands round the seats and the aisle in front of and beside it, back to the hands. One point per hop, the first and
     * last the hands.
     */
    static List<Vector3f> path(float s, java.util.Random rnd) {
        Vector3f hands = new Vector3f(0, -HANDS_DOWN * s, HANDS_FWD * s);
        List<Vector3f> out = new ArrayList<>();
        out.add(hands);
        int hops = TICKS / HOP;
        for (int i = 1; i < hops; i++) {
            float side = (i % 2 == 0 ? -1 : 1) * (0.25f + rnd.nextFloat() * 0.55f);   // left and right in turn
            float fwd = -0.6f + rnd.nextFloat() * 1.9f;                                // behind the seat to well in front
            float up = -1.15f + rnd.nextFloat() * 0.75f;                               // the floor of the aisle to the shoulders
            out.add(new Vector3f(side * s, up * s, fwd * s));
        }
        out.add(new Vector3f(hands));
        return out;
    }

    @Override
    public void run() {
        if (done) return;
        if (!m.isValid() || !d.isValid() || m.getPassengers().stream().noneMatch(p -> p.equals(d))) {
            end(false);
            return;
        }
        int hop = t / HOP, in = t % HOP;
        if (hop >= path.size() - 1) {
            end(true);
            return;
        }
        Vector3f from = path.get(hop), to = path.get(hop + 1);
        float heading = (float) Math.atan2(to.x - from.x, to.z - from.z);
        if (in == 0) {
            // up to the hop's apex: half way, a little above both ends
            Vector3f apex = new Vector3f(from).lerp(to, 0.5f).add(0, 0.32f * scale, 0);
            move(apex, heading, HOP / 2);
            d.setRotation(m.getLocation().getYaw(), 0);
            if (ThreadLocalRandom.current().nextInt(3) == 0) m.getWorld().playSound(where(apex), Sound.ENTITY_CHICKEN_AMBIENT, 0.8f, 1.05f + ThreadLocalRandom.current().nextFloat() * 0.3f);
            m.getWorld().playSound(where(apex), Sound.ENTITY_PARROT_FLY, 0.35f, 1.4f);
        } else if (in == HOP / 2) {
            move(to, heading, HOP / 2);
            feathers(where(to), 1 + ThreadLocalRandom.current().nextInt(2));
        }
        // the wings: spread and folded in turn while she is up, folded for the landing's last moment
        boolean up = in < HOP - STEP;
        d.setItemStack(up && (t / STEP) % 2 == 0 ? spread : folded);
        t += STEP;
    }

    private void move(Vector3f to, float heading, int ticks) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(pose(to, heading, scale));
    }

    /** The display's transformation: at `at`, turned to `heading` (radians about up, 0 = forward), tipped a little. */
    static Transformation pose(Vector3f at, float heading, float scale) {
        Quaternionf turn = new Quaternionf().rotateY(heading).rotateX(-0.18f);
        float k = SIZE * scale * 2;
        return new Transformation(new Vector3f(at), turn, new Vector3f(k, k, k), new Quaternionf());
    }

    /** A point of the mannequin's frame in the world (about: the passenger point is the head's top). */
    private Location where(Vector3f p) {
        Location base = m.getLocation();
        double yaw = Math.toRadians(base.getYaw());
        // local +z forward = (-sin yaw, cos yaw), local +x its left = (cos yaw, sin yaw)
        double wx = p.x * Math.cos(yaw) - p.z * Math.sin(yaw), wz = p.x * Math.sin(yaw) + p.z * Math.cos(yaw);
        return base.add(wx, 1.8 * scale + p.y, wz);
    }

    private static void feathers(Location at, int n) {
        at.getWorld().spawnParticle(Particle.ITEM, at, n, 0.15, 0.1, 0.15, 0.02, new ItemStack(Material.FEATHER));
        at.getWorld().spawnParticle(Particle.WHITE_ASH, at, n * 2, 0.2, 0.15, 0.2, 0.0);
    }

    /** Lands: back in the hands (when it still has them), the display gone. */
    void end(boolean landed) {
        if (done) return;
        done = true;
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            // not scheduled yet
        }
        live.remove(this);
        if (d.isValid()) d.remove();
        if (m.isValid()) {
            EntityEquipment eq = m.getEquipment();
            if (eq != null && eq.getItemInMainHand().isEmpty()) eq.setItemInMainHand(folded);
            if (landed) {
                m.getWorld().playSound(m.getLocation(), Sound.ENTITY_CHICKEN_AMBIENT, 0.7f, 0.9f);
                feathers(where(path.getLast()), 2);
            }
        }
    }

    boolean done() {
        return done;
    }
}
