package dev.stsv.core.vehicle;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 탈것의 느낌, shown ({@link VehicleFeel}, contents/&lt;pack&gt;/vehicle_feel.yml): the load and the slope in a vehicle's pace,
 * dust from its wheels, its lamps, its engine starting and stopping, its body on its springs, and what pulling or pedalling
 * costs the player. {@link VehicleModule} calls in from its tick; everything here is rate-limited and does nothing for a
 * vehicle nobody is near (the physics factors excepted: they are a few multiplications).
 *
 * <ul>
 *   <li><b>Lamps</b>: two parts drawn like the body (same model space and transformation, so they lean and bob with it),
 *       full-bright always ({@code 15, 15}: never given the felt light, so they cannot flicker), their model swapped
 *       ({@link VehicleLamps}) only when the look changes: empty when off.</li>
 *   <li><b>Springs</b> ({@link Suspension}): kicked by steps, hard brakes, starts and turns; while they swing, the body,
 *       its lamps and the load glide to their new pose every other tick (only with a player in range). An idling engine
 *       makes the body tremble the same way.</li>
 *   <li><b>Ground</b> ({@link GroundFx}): every few ticks, at the rear wheels (and a train's trailers'), particles of the
 *       block under them; a few block reads, only near players and above a walking pace.</li>
 * </ul>
 */
final class VehicleFeelView {

    /** Ticks between two checks of whether a player is near a vehicle. */
    static final int WATCH_TICKS = 20;
    /** Ticks between two recounts of a bed's load (when it changed). */
    static final int SHARE_TICKS = 20;
    /** Ticks between two bites of hunger (the way since then at once). */
    static final int EFFORT_TICKS = 20;

    private final VehicleModule m;
    private VehicleFeel.File file = VehicleFeel.File.EMPTY;
    private final Map<Material, BlockData> blockData = new HashMap<>();

    VehicleFeelView(VehicleModule m) {
        this.m = m;
    }

    /** What the feel of one vehicle is now. */
    static final class State {
        VehicleFeel.Spec spec;
        /** the lamp parts (null: none), what they show */
        ItemDisplay head, rear;
        boolean headShown;
        VehicleLamps.Rear rearShown = VehicleLamps.Rear.OFF;
        final VehicleLamps.State lamps = new VehicleLamps.State();
        final Suspension springs = new Suspension();
        /** the idle tremble's offset drawn now (blocks), and whether the body is drawn off its rest pose */
        double shake;
        boolean posed;
        /** the slope (rise per block, + = up), the share of the payload carried and the bed version it was counted at */
        double grade, share;
        long shareVersion = Long.MIN_VALUE, shareEpoch = Long.MIN_VALUE, shareAt = Long.MIN_VALUE;
        /** the way pulled or pedalled since the last bite of hunger */
        double effortWay;
        /** the engine runs (started when the driver got in; stops when the last rider gets out) */
        boolean engineRunning;
        /** a player is near (checked every WATCH_TICKS); near, driven or pulled now */
        boolean near, watched;
        double speedBefore;
    }

    // ------------------------------------------------------------------------------------------
    // The file
    // ------------------------------------------------------------------------------------------

    /** Reads every pack's vehicle_feel.yml (later packs' vehicles over earlier ones; the first file's settings). */
    void load(STSVPlugin plugin) {
        VehicleFeel.File merged = null;
        Map<String, VehicleFeel.Spec> vehicles = new HashMap<>();
        for (ContentPack pack : plugin.content().packs()) {
            Path f = pack.dir().resolve("vehicle_feel.yml");
            if (!Files.isRegularFile(f)) continue;
            VehicleFeel.File one = VehicleFeel.parse(YamlConfiguration.loadConfiguration(f.toFile()), pack.namespace(),
                    name -> Files.exists(pack.models().resolve(name + ".bbmodel")),
                    problem -> plugin.getLogger().warning("[탈것 느낌] " + problem));
            if (merged == null) merged = one;
            vehicles.putAll(one.vehicles());
        }
        file = merged == null ? VehicleFeel.File.EMPTY : new VehicleFeel.File(merged.settings(), Map.copyOf(vehicles), merged.profiles());
    }

    VehicleFeel.Settings settings() {
        return file.settings();
    }

    /** A vehicle's feel by its item ("ns:id"); without its own: a plain one of this power. */
    VehicleFeel.Spec spec(String itemKey, VehicleFeel.Power fallback) {
        return file.spec(itemKey, fallback);
    }

    private VehicleFeel.Spec spec(LiveVehicle v) {
        if (v.feel.spec == null) v.feel.spec = spec(v.item.key().asString(), v.spec.pulled() ? VehicleFeel.Power.MUSCLE : VehicleFeel.Power.ENGINE);
        return v.feel.spec;
    }

    // ------------------------------------------------------------------------------------------
    // Parts
    // ------------------------------------------------------------------------------------------

    /** The lamp parts (drawn like the body, full-bright), when the vehicle has lamps. */
    void spawn(LiveVehicle v) {
        State f = v.feel;
        f.spec = null;
        VehicleFeel.Lamps l = spec(v).lamps();
        f.headShown = false;
        f.rearShown = VehicleLamps.Rear.OFF;
        Transformation t = VehicleModule.body(v, v.tiltShown);
        if (l.front()) f.head = lamp(v, t);
        if (l.rear()) f.rear = lamp(v, t);
    }

    /**
     * The lamp models are drawn this many times smaller than their body (their beams reach past a model's bounds:
     * tools/assetgen/models/vehicle_lights.py LAMP_SCALE) and shown this much larger.
     */
    static final float LAMP_SCALE = 2f;

    /** A lamp part's transformation: the body's, shown LAMP_SCALE times larger about the same origin. */
    static Transformation lampTransform(Transformation body) {
        return new Transformation(body.getTranslation(), body.getLeftRotation(), new Vector3f(body.getScale()).mul(LAMP_SCALE),
                body.getRightRotation());
    }

    private ItemDisplay lamp(LiveVehicle v, Transformation t) {
        ItemDisplay d = m.display(v, ItemStack.empty(), lampTransform(t));
        d.setBrightness(new Display.Brightness(15, 15));
        return d;
    }

    /** The lamp parts go (the vehicle's other parts go with it); the engine is off (its sounds were stopped). */
    void remove(LiveVehicle v) {
        State f = v.feel;
        if (f.head != null) f.head.remove();
        if (f.rear != null) f.rear.remove();
        f.head = f.rear = null;
        f.engineRunning = false;
        f.springs.reset();
        f.shake = 0;
        f.posed = false;
    }

    static void entities(LiveVehicle v, List<Entity> out) {
        if (v.feel.head != null) out.add(v.feel.head);
        if (v.feel.rear != null) out.add(v.feel.rear);
    }

    /** Whether a lamp part went missing (the vehicle's parts are made again). */
    static boolean broken(LiveVehicle v) {
        return (v.feel.head != null && !v.feel.head.isValid()) || (v.feel.rear != null && !v.feel.rear.isValid());
    }

    void teleport(LiveVehicle v, org.bukkit.Location at) {
        if (v.feel.head != null) v.feel.head.teleport(at);
        if (v.feel.rear != null) v.feel.rear.teleport(at);
    }

    /** The lamps glide with the body to its new pose. */
    void glideLamps(LiveVehicle v, Transformation body, int ticks) {
        if (v.feel.head == null && v.feel.rear == null) return;
        Transformation t = lampTransform(body);
        for (ItemDisplay d : new ItemDisplay[]{v.feel.head, v.feel.rear}) {
            if (d == null) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(t);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The pose on the springs (VehicleModule#body, #load use it)
    // ------------------------------------------------------------------------------------------

    /**
     * A drawn part's transformation with the springs' pose: nodded by the pitch about the vehicle's ground centre, lifted
     * by the bob and the idle tremble; a load (load = true) also sways about where it sits.
     */
    static Transformation posed(LiveVehicle v, Transformation t, VehicleSpec.Vec loadAt) {
        State f = v.feel;
        if (!f.posed) return t;
        Suspension s = f.springs;
        Quaternionf q = new Quaternionf();
        if (s.pitch() != 0) q.rotateX((float) Math.toRadians(s.pitch()));   // display +x rotation lowers the front
        Vector3f tr = new Vector3f(t.getTranslation()).rotate(q).add(0, (float) (s.bob() + f.shake), 0);
        Quaternionf left = new Quaternionf(q).mul(t.getLeftRotation());
        if (loadAt != null && s.sway() != 0) {
            Quaternionf sw = new Quaternionf().rotateZ((float) -Math.toRadians(s.sway()));   // display +x is the vehicle's left
            Vector3f pivot = new Vector3f((float) -loadAt.right(), (float) loadAt.up(), (float) loadAt.forward()).rotate(q)
                    .add(0, (float) (s.bob() + f.shake), 0);
            tr.sub(pivot).rotate(sw).add(pivot);
            left = new Quaternionf(sw).mul(left);
        }
        return new Transformation(tr, left, t.getScale(), t.getRightRotation());
    }

    // ------------------------------------------------------------------------------------------
    // Physics: the load and the slope
    // ------------------------------------------------------------------------------------------

    /** The share of its payload the vehicle carries (counted again at most every SHARE_TICKS, when its bed changed). */
    private double share(LiveVehicle v, long ticks, long epoch) {
        State f = v.feel;
        if (v.cargo == null) return 0;
        if ((f.shareVersion != v.cargoVersion || f.shareEpoch != epoch) && ticks - f.shareAt >= SHARE_TICKS) {
            f.share = m.loadShare(v);
            f.shareVersion = v.cargoVersion;
            f.shareEpoch = epoch;
            f.shareAt = ticks;
        }
        return f.share;
    }

    /** {top, accel}: the factors this tick's physics runs with. */
    double[] factors(LiveVehicle v, long ticks, long epoch) {
        VehicleFeel.Spec s = spec(v);
        double share = share(v, ticks, epoch);
        boolean wheeled = !v.spec.floats();
        return new double[]{VehicleFeel.topFactor(s.profile(), share, wheeled ? v.feel.grade : 0, wheeled),
                VehicleFeel.accelFactor(s.profile(), share, wheeled ? v.feel.grade : 0)};
    }

    /** The puller's slowdown: the cart's own, deeper with its load and uphill (in steps of 2 %: the modifier changes seldom). */
    double pullSlowdown(LiveVehicle v, long ticks, long epoch) {
        VehicleFeel.Spec s = spec(v);
        double f = VehicleFeel.topFactor(s.profile(), share(v, ticks, epoch), v.feel.grade, false);
        return Math.round(VehicleFeel.slowdown(v.spec.pull().slowdown(), f) * 50) / 50.0;
    }

    // ------------------------------------------------------------------------------------------
    // After a move
    // ------------------------------------------------------------------------------------------

    /**
     * The vehicle moved this tick from (x, y, z), turning by turn degrees: the slope, the springs' kicks, the way for
     * hunger (the puller or a pedalling driver), dust from the wheels.
     */
    void moved(LiveVehicle v, double x, double y, double z, float turn, Player effortBy, long ticks) {
        State f = v.feel;
        VehicleFeel.Spec s = spec(v);
        double dist = Math.hypot(v.st.x - x, v.st.z - z), dy = v.st.y - y;
        if (!v.spec.floats()) f.grade = VehicleFeel.grade(f.grade, dy, dist, v.st.onGround);
        VehicleFeel.Settings set = settings();
        if (set.suspension() && s.bounce() > 0 && !v.spec.floats() && f.watched) {
            f.springs.drive(v.st.onGround ? dy : 0, v.st.speed, f.speedBefore, turn,
                    set.bounce() * s.bounce(), set.pitch() * s.bounce(), v.spec.cargo() != null && v.load != null ? set.sway() * s.bounce() : 0);
            if (v.st.bumped) f.springs.kick(0, Math.signum(v.st.speed) * set.pitch() * s.bounce() * 0.4, 0);
        }
        if (effortBy != null && !s.effort().none()) f.effortWay += dist;
        if (set.dust() && f.watched && !v.spec.floats() && Math.floorMod(ticks + v.id, set.dustEvery()) == 0
                && Math.abs(v.st.speed) >= set.dustSpeed()) dust(v, set);
    }

    /** Particles from the rear wheels (a train's trailers too) of what they roll on. */
    private void dust(LiveVehicle v, VehicleFeel.Settings set) {
        double top = Math.max(1, v.spec.speed());
        dustUnder(v.world, v.st, v.spec, top);
        if (v.train != null) {
            for (int i = 0; i < v.train.units.size(); i++)
                dustUnder(v.world, v.train.units.get(i), v.spec.train().trailers().get(i).unit(), top);
        }
    }

    private void dustUnder(World w, VehiclePhysics.State st, VehicleSpec unit, double top) {
        if (!st.onGround || unit.wheels().isEmpty()) return;
        double back = Double.MAX_VALUE;
        for (VehicleSpec.Wheel wh : unit.wheels()) back = Math.min(back, wh.at().forward());
        for (VehicleSpec.Wheel wh : unit.wheels()) {
            if (wh.at().forward() > back + 0.05) continue;   // the rear axle only
            double behind = Math.signum(st.speed) * unit.wheelRadius() * 0.6;
            double[] c = VehiclePhysics.world(st.x, st.y, st.z, st.yaw, new VehicleSpec.Vec(wh.at().right(), 0, wh.at().forward() - behind));
            int bx = (int) Math.floor(c[0]), bz = (int) Math.floor(c[2]);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            Block in = w.getBlockAt(bx, (int) Math.floor(c[1] + 0.05), bz);
            Block under = w.getBlockAt(bx, (int) Math.floor(c[1] - 0.05), bz);
            GroundFx.Kind k = GroundFx.of(under.getType(), in.getType());
            int n = GroundFx.count(k, st.speed, top);
            switch (k) {
                case DUST -> w.spawnParticle(Particle.BLOCK, c[0], c[1] + 0.1, c[2], n, 0.12, 0.05, 0.12, 0, under.getBlockData());
                case MUD -> w.spawnParticle(Particle.BLOCK, c[0], c[1] + 0.15, c[2], n + 1, 0.1, 0.12, 0.1, 0.1, under.getBlockData());
                case SPLASH -> w.spawnParticle(Particle.SPLASH, c[0], c[1] + 0.2, c[2], n * 2, 0.15, 0.05, 0.15, 0.2);
                case SNOW -> w.spawnParticle(Particle.BLOCK, c[0], c[1] + 0.15, c[2], n + 1, 0.15, 0.08, 0.15, 0.05, snow());
                case NONE -> { }
            }
        }
    }

    private BlockData snow() {
        return blockData.computeIfAbsent(Material.SNOW_BLOCK, Material::createBlockData);
    }

    // ------------------------------------------------------------------------------------------
    // Every tick
    // ------------------------------------------------------------------------------------------

    /**
     * After the vehicle's tick: who is near, the lamps, the reverse beep, the engine stopping when the last rider got out,
     * hunger for the way pulled or pedalled, the springs and the idle tremble.
     */
    void tick(LiveVehicle v, Player driver, Player effortBy, DriveInput in, long ticks, boolean engineSound) {
        State f = v.feel;
        VehicleFeel.Spec s = spec(v);
        VehicleFeel.Settings set = settings();
        if (Math.floorMod(ticks + v.id, WATCH_TICKS) == 0) f.near = VehicleModule.seen(v, Math.max(set.range(), set.showRange()));
        f.watched = driver != null || effortBy != null || f.near;

        // the engine: running from the driver's start until the last rider is out (VehicleModule#engine plays its hum)
        if (f.engineRunning && driver == null && !v.hasRiders()) engineStop(v, engineSound);

        lamps(v, s, set, driver, in, ticks);

        if (effortBy != null && f.effortWay > 0 && Math.floorMod(ticks + v.id, EFFORT_TICKS) == 0) {
            double ex = VehicleFeel.exhaustion(f.effortWay, f.share, f.grade, s.effort());
            f.effortWay = 0;
            if (ex > 0 && effortBy.getGameMode() != org.bukkit.GameMode.CREATIVE && effortBy.getGameMode() != org.bukkit.GameMode.SPECTATOR)
                effortBy.setExhaustion((float) Math.min(40, effortBy.getExhaustion() + ex));
        } else if (effortBy == null) {
            f.effortWay = 0;
        }

        // the springs swing (and the idle tremble): the drawn parts follow every other tick, only with a player near
        boolean swinging = f.springs.step();
        boolean idle = f.engineRunning && engineSound && s.idleShake() > 0 && Math.abs(v.st.speed) < VehicleLamps.STILL;
        if (Math.floorMod(ticks + v.id, 2) == 0) {
            if (f.watched && (swinging || idle || f.posed)) {
                f.shake = idle ? Suspension.shake(ticks, v.id, s.idleShake()) : 0;
                f.posed = true;
                pose(v);
                f.posed = !f.springs.settled() || f.shake != 0;
            } else if (!f.watched && !f.springs.settled()) {
                f.springs.reset();
            }
        }
        f.speedBefore = v.st.speed;
    }

    /** Draws the body, its lamps and the load in their pose now (gliding there over two ticks). */
    private void pose(LiveVehicle v) {
        Transformation body = VehicleModule.body(v, v.tiltShown);
        glide(v.body, body);
        glideLamps(v, body, 2);
        if (v.load != null && v.spec.cargo() != null) glide(v.load, VehicleModule.load(v, v.tiltShown));
    }

    private static void glide(ItemDisplay d, Transformation t) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(t);
    }

    private void lamps(LiveVehicle v, VehicleFeel.Spec s, VehicleFeel.Settings set, Player driver, DriveInput in, long ticks) {
        State f = v.feel;
        VehicleFeel.Lamps l = s.lamps();
        if (f.head == null && f.rear == null && !s.reverseBeep()) return;
        int light = -1;
        if (driver != null && !v.light.unset()) {
            World w = v.world;
            int darken = w.getEnvironment() == World.Environment.NORMAL ? VehicleLamps.skyDarken(w.getTime(), w.hasStorm() ? 1 : 0, w.isThundering() ? 1 : 0) : 0;
            light = VehicleLamps.seen(v.light.block(), v.light.sky(), darken);
        }
        VehicleLamps.Rear rear = f.lamps.tick(ticks, driver != null, light, in, v.st.speed, set.onBelow(), set.offAbove(), set.brakeHold());
        boolean head = f.lamps.head();
        if (f.head != null && head != f.headShown) {
            f.headShown = head;
            f.head.setItemStack(VehicleModule.model(head ? l.head() : null));
        }
        if (f.rear != null) {
            org.bukkit.NamespacedKey want = rearModel(l, rear);
            VehicleLamps.Rear shown = want == null ? VehicleLamps.Rear.OFF : rear;
            if (shown != f.rearShown) {
                f.rearShown = shown;
                f.rear.setItemStack(VehicleModule.model(want));
            }
        }
        if (s.reverseBeep() && set.beep() != null && f.lamps.beep(ticks, driver != null && VehicleLamps.reversing(in, v.st.speed), set.beepEvery()))
            m.play(v, set.beep().key(), set.beep().volume(), 1f);
    }

    /** The rear's model for a look (a missing lamp falls back: no reverse lamp = the tail lamps or nothing). */
    static org.bukkit.NamespacedKey rearModel(VehicleFeel.Lamps l, VehicleLamps.Rear r) {
        return switch (r) {
            case OFF -> null;
            case TAIL -> l.tail();
            case BRAKE -> l.brake() != null ? l.brake() : l.tail();
            case REVERSE -> l.reverse();
        };
    }

    // ------------------------------------------------------------------------------------------
    // The engine starting and stopping
    // ------------------------------------------------------------------------------------------

    /**
     * The driver got in: an engine that is not running starts (its start sound; the hum begins after it). Returns the
     * ticks until the hum, or -1 when nothing started (no start sound, or it runs already).
     */
    int driverIn(LiveVehicle v, boolean engineSound) {
        State f = v.feel;
        VehicleFeel.Spec s = spec(v);
        if (!s.ignition() || f.engineRunning) return -1;
        f.engineRunning = true;
        if (!engineSound || s.start() == null) return 0;
        m.play(v, s.start().key(), s.start().volume(), 1f);
        return s.start().ticks();
    }

    /** Whether the engine keeps running without a driver (an ignition vehicle with riders left in it). */
    boolean runsWithout(LiveVehicle v) {
        return v.feel.engineRunning && spec(v).ignition() && v.hasRiders();
    }

    /** The last rider got out: the engine stops (its stop sound). */
    void engineStop(LiveVehicle v, boolean engineSound) {
        State f = v.feel;
        if (!f.engineRunning) return;
        f.engineRunning = false;
        VehicleFeel.Spec s = spec(v);
        if (engineSound && s.stop() != null) m.play(v, s.stop().key(), s.stop().volume(), 1f);
    }

    /** How far the driver of a pedalled vehicle is lifted now: up and down with each pedal stroke. */
    static double seatLift(LiveVehicle v, int seat) {
        VehicleFeel.Spec s = v.feel.spec;
        if (seat != 0 || s == null || s.pedalBob() <= 0 || v.spec.extras().spinners().isEmpty()) return 0;
        double crank = v.spin * v.spec.extras().spinners().getFirst().ratio();
        return s.pedalBob() * (0.5 - 0.5 * Math.cos(crank * 2));
    }
}
