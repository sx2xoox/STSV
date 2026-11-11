package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A custom item's {@code vehicle:} section (content items/*.yml, see content/stsv/items/vehicles.yml): how it drives,
 * how big it is, where its wheels, seats, light bar and load go. Positions are {@link Vec} [right, up, forward] in blocks
 * from the vehicle's ground centre. Pure (unit-tested).
 *
 * @param bodyScale    the body model's display scale (bodies are drawn at half size: 2)
 * @param speed        top speed forward, blocks per second (the server caps it: vehicles.max-speed)
 * @param reverseSpeed top speed backward
 * @param acceleration blocks/s² while W is held
 * @param brake        blocks/s² while braking (S while going forward, W while reversing, both)
 * @param coast        blocks/s² lost with no key held
 * @param turnRate     degrees per second at turnSpeed or faster; slower turns less, standing still not at all
 * @param stepHeight   ledges it drives up without stopping (1 = a block; slabs and stairs are smooth)
 * @param length       collision size [length, width, height] (blocks)
 * @param clickHeight  how high its click boxes reach (below a seated rider's eyes, so they do not block the view)
 * @param seats        the first is the driver's
 * @param lights       the roof light bar (Ctrl = siren on / off), or null
 * @param horn         the horn (space), or null
 * @param cargo        a storage bed (the truck, the hand cart), or null
 * @param engine       its driving sound, or null (silent)
 * @param pull         pulled by a player on foot instead of driven (the hand cart: no seats), or null
 * @param extras       two-wheelers' lean, spinning parts (a pedal crank), all-terrain ({@link Extras#NONE}: none of them)
 */
public record VehicleSpec(
        float bodyScale,
        double speed, double reverseSpeed, double acceleration, double brake, double coast,
        double turnRate, double turnSpeed, double stepHeight,
        double length, double width, double height, double clickHeight,
        NamespacedKey wheelModel, double wheelRadius, List<Wheel> wheels,
        List<Vec> seats,
        Lights lights, Horn horn, Cargo cargo, Engine engine, Pull pull, Extras extras
) {

    /** Without extras (no lean, no spinning parts, slowed on rough ground like a car). */
    public VehicleSpec(float bodyScale, double speed, double reverseSpeed, double acceleration, double brake, double coast,
                       double turnRate, double turnSpeed, double stepHeight, double length, double width, double height, double clickHeight,
                       NamespacedKey wheelModel, double wheelRadius, List<Wheel> wheels, List<Vec> seats,
                       Lights lights, Horn horn, Cargo cargo, Engine engine, Pull pull) {
        this(bodyScale, speed, reverseSpeed, acceleration, brake, coast, turnRate, turnSpeed, stepHeight, length, width, height, clickHeight,
                wheelModel, wheelRadius, wheels, seats, lights, horn, cargo, engine, pull, Extras.NONE);
    }

    /**
     * What some vehicles have besides the usual (content/stsv/items/vehicles_road.yml).
     *
     * @param lean       degrees the body (with its wheels, load and spinning parts) leans into a full turn at turn-speed or
     *                   faster (a bicycle, a motorbike); 0 = it stays upright
     * @param spinners   parts that turn with the wheels (a bicycle's pedal crank)
     * @param allTerrain not slowed on rough ground (sand, gravel, snow, mud) or soul sand ({@link VehiclePhysics#slowdown})
     */
    public record Extras(double lean, List<Spinner> spinners, boolean allTerrain) {
        public static final Extras NONE = new Extras(0, List.of(), false);
    }

    /**
     * A part turned about its own x axis (across the vehicle) as the wheels roll: ratio turns for every wheel turn
     * (0.5 = a pedal crank geared 2:1). Drawn like a wheel: its axle at model (8, 8, 8).
     */
    public record Spinner(NamespacedKey model, Vec at, double ratio) {}

    /** A point on the vehicle: right, up, forward (blocks) from its ground centre. */
    public record Vec(double right, double up, double forward) {}

    public record Wheel(Vec at, boolean steer) {}

    /** dark: the light bar switched off; flash: the looks shown in turn while the siren is on. */
    public record Lights(NamespacedKey dark, List<NamespacedKey> flash, Vec at) {}

    /**
     * The horn (space, held: {@link VehicleSounds}). sound: the resource pack's hold clip (sounds while space is down, stopped
     * on release), release: its short tail played on release (null = none); volume 1 = heard about 16 blocks away (1.5 =
     * 24). Without the pack's sounds (config vehicles.horn.custom-sound: false) the vanilla sound key plays instead, once
     * per press: `times` times (빵 / 빵빵), `gap` ticks apart, at this pitch.
     */
    public record Horn(String sound, String release, float volume, String vanilla, float pitch, int times, int gap) {}

    /**
     * The driving sound: a resource pack sound of one segment (segmentTicks long at pitch 1, see tools/assetgen/sounds.py),
     * re-played back to back while someone drives. Pitch from idlePitch (standing) to topPitch (top speed), loudness from
     * idleVolume (0 = silent while standing) to volume (at most 1: heard 16 blocks away).
     */
    public record Engine(String sound, float volume, float idleVolume, float idlePitch, float topPitch, int segmentTicks) {}

    /**
     * A storage bed: pages of 45 slots (a chest page, its bottom row the page buttons). In the item file as
     * {@code slots} (whole rows of 9: 27 = a small chest, 180 = 4 pages) or {@code pages}. A last page that is not
     * full shows its missing slots locked (a one-page bed of fewer rows opens as a smaller chest: {@link CargoPages#viewSize}).
     *
     * @param pages  pages of 45 slots it is shown in (the last one may be part full)
     * @param behind clicks this far forward or further back open it (blocks, the bed's front edge)
     * @param stages load models by how full it is (see {@link CargoStages}); none for a closed box
     * @param slots  how many slots it holds
     */
    public record Cargo(int pages, double behind, Vec at, List<NamespacedKey> stages, int slots) {
        public static final int PAGE = 45;
        /** The most pages a bed may have (810 slots: the 탑차's 720 and some room). */
        public static final int MAX_PAGES = 18;
        /** Slots come in whole rows. */
        public static final int ROW = 9;

        /** A bed of whole pages. */
        public Cargo(int pages, double behind, Vec at, List<NamespacedKey> stages) {
            this(pages, behind, at, stages, pages * PAGE);
        }
    }

    /**
     * Pulled on foot (the hand cart, see {@link CartPull}): nobody rides it; a player takes its handles and it follows
     * them like a trailer, turning about its wheels.
     *
     * @param hitch    where the puller stands: this far forward of the cart's centre (its axle), blocks
     * @param slowdown the share of walking speed the puller loses (0.3 = 30 % slower); sprinting adds nothing to it
     * @param release  the puller lets go once they are this far from where its handles are (the cart stuck behind)
     * @param restTilt degrees it tips forward onto its handles when nobody holds them (0 = it stays level)
     * @param roll     a quiet rolling sound while it moves, or null
     */
    public record Pull(double hitch, double slowdown, double release, double restTilt, Roll roll) {}

    /** A sound played every {@code every} blocks the cart rolls (a vanilla or pack sound key). */
    public record Roll(String sound, float volume, float pitch, double every) {}

    /** Collision boxes (squares) along the length: as many as cover it, each this share of the width. */
    static final double SQUARE = 0.9;

    /**
     * Reads a vehicle section.
     *
     * @param namespace   the pack's namespace (model names without one are its .bbmodels: "ns:model/name")
     * @param modelExists whether the pack has models/&lt;name&gt;.bbmodel (a full "ns:id" model is not checked)
     */
    public static VehicleSpec parse(ConfigurationSection v, String namespace, Predicate<String> modelExists) {
        Pull pull = v.isConfigurationSection("pull") ? pull(v.getConfigurationSection("pull")) : null;
        // a pulled cart is not driven: its driving numbers only matter for rolling to a stop (defaults: a walk)
        boolean driven = pull == null;
        double speed = driven ? positive(v, "speed") : v.getDouble("speed", 4.3);
        double reverse = driven ? positive(v, "reverse-speed") : v.getDouble("reverse-speed", 2);
        List<Double> size = v.getDoubleList("size");
        if (size.size() != 3 || size.stream().anyMatch(d -> d <= 0)) throw new IllegalArgumentException("vehicle.size 는 [길이, 너비, 높이] (블록)");
        double wheelRadius = positive(v, "wheel-radius");
        NamespacedKey wheel = model(v.getString("wheel"), "vehicle.wheel", namespace, modelExists);
        List<Wheel> wheels = new ArrayList<>();
        for (Map<?, ?> m : v.getMapList("wheels")) {
            ConfigurationSection w = section(m);
            wheels.add(new Wheel(vec(w, "at", "vehicle.wheels.at"), w.getBoolean("steer")));
        }
        List<Vec> seats = new ArrayList<>();
        for (Map<?, ?> m : v.getMapList("seats")) seats.add(vec(section(m), "at", "vehicle.seats.at"));
        if (seats.isEmpty() && driven) throw new IllegalArgumentException("vehicle.seats 가 비어 있음 (첫 자리가 운전석)");
        if (!seats.isEmpty() && !driven) throw new IllegalArgumentException("vehicle.seats: 끄는 탈것(pull)에는 자리가 없음 (비워 두세요)");

        Lights lights = null;
        if (v.isConfigurationSection("lights")) {
            ConfigurationSection l = v.getConfigurationSection("lights");
            List<NamespacedKey> flash = new ArrayList<>();
            for (String f : l.getStringList("flash")) flash.add(model(f, "vehicle.lights.flash", namespace, modelExists));
            if (flash.isEmpty()) throw new IllegalArgumentException("vehicle.lights.flash 가 비어 있음");
            lights = new Lights(model(l.getString("dark"), "vehicle.lights.dark", namespace, modelExists), List.copyOf(flash),
                    vec(l, "at", "vehicle.lights.at"));
        }
        Horn horn = null;
        if (v.isConfigurationSection("horn")) {
            String sound = soundKey(v.getString("horn.sound"), "vehicle.horn.sound", true);
            String release = soundKey(v.getString("horn.release"), "vehicle.horn.release", false);
            String vanilla = soundKey(v.getString("horn.vanilla", "block.note_block.didgeridoo"), "vehicle.horn.vanilla", true);
            int times = v.getInt("horn.times", 1);
            if (times < 1 || times > 4) throw new IllegalArgumentException("vehicle.horn.times 는 1 ~ 4");
            horn = new Horn(sound, release, (float) v.getDouble("horn.volume", 1.5), vanilla, (float) v.getDouble("horn.pitch", 1.0), times,
                    Math.clamp(v.getInt("horn.gap", 4), 1, 20));
        }
        Engine engine = null;
        if (v.isConfigurationSection("engine")) {
            ConfigurationSection e = v.getConfigurationSection("engine");
            float volume = (float) e.getDouble("volume", 0.4), idleVolume = (float) e.getDouble("idle-volume", 0);
            if (volume <= 0 || volume > 1 || idleVolume < 0 || idleVolume > volume)
                throw new IllegalArgumentException("vehicle.engine: 0 <= idle-volume <= volume <= 1");
            float idlePitch = (float) e.getDouble("idle-pitch", 0.7), topPitch = (float) e.getDouble("top-pitch", 1.4);
            if (idlePitch < VehicleSounds.MIN_PITCH || topPitch > VehicleSounds.MAX_PITCH || idlePitch > topPitch)
                throw new IllegalArgumentException("vehicle.engine: 0.5 <= idle-pitch <= top-pitch <= 2");
            int segment = e.getInt("segment-ticks", 10);
            if (segment < 4 || segment > 100) throw new IllegalArgumentException("vehicle.engine.segment-ticks 는 4 ~ 100");
            engine = new Engine(soundKey(e.getString("sound"), "vehicle.engine.sound", true), volume, idleVolume, idlePitch, topPitch, segment);
        }
        Cargo cargo = null;
        if (v.isConfigurationSection("cargo")) {
            ConfigurationSection c = v.getConfigurationSection("cargo");
            int slots;
            if (c.contains("slots")) {
                slots = c.getInt("slots");
                if (slots < Cargo.ROW || slots > Cargo.PAGE * Cargo.MAX_PAGES || slots % Cargo.ROW != 0)
                    throw new IllegalArgumentException("vehicle.cargo.slots 는 9 의 배수, 9 ~ " + Cargo.PAGE * Cargo.MAX_PAGES + " (한 쪽 45칸)");
            } else {
                int pages = c.getInt("pages", 1);
                if (pages < 1 || pages > Cargo.MAX_PAGES) throw new IllegalArgumentException("vehicle.cargo.pages 는 1 ~ " + Cargo.MAX_PAGES);
                slots = pages * Cargo.PAGE;
            }
            List<NamespacedKey> stages = new ArrayList<>();
            for (String s : c.getStringList("stages")) stages.add(model(s, "vehicle.cargo.stages", namespace, modelExists));
            cargo = new Cargo(CargoPages.pages(slots), c.getDouble("behind", 0), vec(c, "at", "vehicle.cargo.at"), List.copyOf(stages), slots);
        }
        double bodyScale = v.getDouble("body-scale", 1);
        if (bodyScale <= 0 || bodyScale > 4) throw new IllegalArgumentException("vehicle.body-scale 은 0 초과 4 이하");
        double step = v.getDouble("step-height", 1.0);
        if (step < 0 || step > 1.5) throw new IllegalArgumentException("vehicle.step-height 는 0 ~ 1.5");
        double lean = v.getDouble("lean", 0);
        if (lean < 0 || lean > 25) throw new IllegalArgumentException("vehicle.lean 은 0 ~ 25 (도)");
        List<Spinner> spinners = new ArrayList<>();
        for (Map<?, ?> m : v.getMapList("spinners")) {
            ConfigurationSection sp = section(m);
            double ratio = sp.getDouble("ratio", 1);
            if (ratio <= 0 || ratio > 4) throw new IllegalArgumentException("vehicle.spinners.ratio 는 0 초과 4 이하 (바퀴 한 바퀴에 몇 바퀴)");
            spinners.add(new Spinner(model(sp.getString("model"), "vehicle.spinners.model", namespace, modelExists),
                    vec(sp, "at", "vehicle.spinners.at"), ratio));
        }
        Extras extras = lean == 0 && spinners.isEmpty() && !v.getBoolean("all-terrain")
                ? Extras.NONE : new Extras(lean, List.copyOf(spinners), v.getBoolean("all-terrain"));
        return new VehicleSpec((float) bodyScale, speed, reverse,
                driven ? positive(v, "acceleration") : v.getDouble("acceleration", 3), driven ? positive(v, "brake") : v.getDouble("brake", 8),
                v.getDouble("coast", driven ? 2.0 : 8.0), driven ? positive(v, "turn-rate") : v.getDouble("turn-rate", 90),
                driven ? positive(v, "turn-speed") : v.getDouble("turn-speed", 1), step,
                size.get(0), size.get(1), size.get(2), v.getDouble("click-height", Math.min(1.2, size.get(2))),
                wheel, wheelRadius, List.copyOf(wheels), List.copyOf(seats), lights, horn, cargo, engine, pull, extras);
    }

    private static Pull pull(ConfigurationSection p) {
        double hitch = p.getDouble("hitch", 0);
        if (hitch < 0.5 || hitch > 4) throw new IllegalArgumentException("vehicle.pull.hitch 는 0.5 ~ 4 (블록, 가운데에서 끄는 사람까지)");
        double slow = p.getDouble("slowdown", 0.3);
        if (slow < 0 || slow > 0.9) throw new IllegalArgumentException("vehicle.pull.slowdown 은 0 ~ 0.9 (0.3 = 30% 느리게)");
        double release = p.getDouble("release-distance", 4);
        if (release < 1.5 || release > 16) throw new IllegalArgumentException("vehicle.pull.release-distance 는 1.5 ~ 16 (블록)");
        double tilt = p.getDouble("rest-tilt", 0);
        if (tilt < 0 || tilt > 45) throw new IllegalArgumentException("vehicle.pull.rest-tilt 는 0 ~ 45 (도)");
        Roll roll = null;
        if (p.isConfigurationSection("sound")) {
            ConfigurationSection r = p.getConfigurationSection("sound");
            float volume = (float) r.getDouble("volume", 0.15), pitch = (float) r.getDouble("pitch", 1);
            double every = r.getDouble("every", 1.5);
            if (volume <= 0 || volume > 1) throw new IllegalArgumentException("vehicle.pull.sound.volume 은 0 초과 1 이하");
            if (pitch < VehicleSounds.MIN_PITCH || pitch > VehicleSounds.MAX_PITCH) throw new IllegalArgumentException("vehicle.pull.sound.pitch 는 0.5 ~ 2");
            if (every < 0.5 || every > 8) throw new IllegalArgumentException("vehicle.pull.sound.every 는 0.5 ~ 8 (블록)");
            roll = new Roll(soundKey(r.getString("key"), "vehicle.pull.sound.key", true), volume, pitch, every);
        }
        return new Pull(hitch, slow, release, tilt, roll);
    }

    /** Pulled on foot (no seats, no driver). */
    public boolean pulled() {
        return pull != null;
    }

    /** The point the cart tips about: its axle (the wheels' middle, at their hub height). */
    public Vec axle() {
        double f = 0;
        for (Wheel w : wheels) f += w.at().forward();
        return new Vec(0, wheelRadius, wheels.isEmpty() ? 0 : f / wheels.size());
    }

    /** A sound event key ("ns:path" or a vanilla "path"), checked; null when absent and not required. */
    private static String soundKey(String key, String what, boolean required) {
        if (key == null || key.isBlank()) {
            if (required) throw new IllegalArgumentException(what + " 가 없음");
            return null;
        }
        String k = key.trim();
        if (NamespacedKey.fromString(k) == null) throw new IllegalArgumentException(what + ": 잘못된 소리 이름 " + k);
        return k;
    }

    private static double positive(ConfigurationSection v, String key) {
        if (!v.contains(key)) throw new IllegalArgumentException("vehicle." + key + " 가 없음");
        double d = v.getDouble(key);
        if (!(d > 0)) throw new IllegalArgumentException("vehicle." + key + " 는 0보다 커야 함");
        return d;
    }

    private static NamespacedKey model(String name, String what, String namespace, Predicate<String> modelExists) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException(what + " 모델이 없음");
        if (name.contains(":")) {
            NamespacedKey k = NamespacedKey.fromString(name);
            if (k == null) throw new IllegalArgumentException(what + ": 잘못된 모델 id " + name);
            return k;
        }
        if (!modelExists.test(name)) throw new IllegalArgumentException(what + ": models/" + name + ".bbmodel 이 없음");
        return new NamespacedKey(namespace, "model/" + name);
    }

    private static Vec vec(ConfigurationSection s, String key, String what) {
        List<Double> l = s.getDoubleList(key);
        if (l.size() != 3) throw new IllegalArgumentException(what + " 는 [오른쪽, 위, 앞] (블록)");
        return new Vec(l.get(0), l.get(1), l.get(2));
    }

    private static ConfigurationSection section(Map<?, ?> map) {
        return new YamlConfiguration().createSection("entry", map);
    }

    /** Every model the vehicle shows besides its body (light bar looks, load stages, spinning parts), for the render budget. */
    public List<NamespacedKey> partModels() {
        List<NamespacedKey> out = new ArrayList<>();
        for (Spinner sp : extras.spinners()) out.add(sp.model());
        if (lights != null) {
            out.add(lights.dark());
            out.addAll(lights.flash());
        }
        if (cargo != null) out.addAll(cargo.stages());
        return out;
    }

    /** How many collision squares cover the length, and how wide each is (blocks). */
    public int squares() {
        return Math.max(1, (int) Math.ceil(length / (width * SQUARE) - 1e-9));
    }

    public double squareSize() {
        return width * SQUARE;
    }

    /** The forward offset of collision square i (0 = the rearmost). */
    public double squareOffset(int i) {
        int n = squares();
        if (n == 1) return 0;
        double reach = length / 2 - squareSize() / 2;
        return -reach + 2 * reach * i / (n - 1);
    }
}
