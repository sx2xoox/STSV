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
 * @param cargo        a storage bed (the truck), or null
 * @param engine       its driving sound, or null (silent)
 */
public record VehicleSpec(
        float bodyScale,
        double speed, double reverseSpeed, double acceleration, double brake, double coast,
        double turnRate, double turnSpeed, double stepHeight,
        double length, double width, double height, double clickHeight,
        NamespacedKey wheelModel, double wheelRadius, List<Wheel> wheels,
        List<Vec> seats,
        Lights lights, Horn horn, Cargo cargo, Engine engine
) {

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
     * A storage bed: pages of 45 slots (a chest page, its bottom row the page buttons).
     *
     * @param behind clicks this far forward or further back open it (blocks, the bed's front edge)
     * @param stages load models by how full it is (see {@link CargoStages})
     */
    public record Cargo(int pages, double behind, Vec at, List<NamespacedKey> stages) {
        public static final int PAGE = 45;

        public int slots() {
            return pages * PAGE;
        }
    }

    /** Collision boxes (squares) along the length: as many as cover it, each this share of the width. */
    static final double SQUARE = 0.9;

    /**
     * Reads a vehicle section.
     *
     * @param namespace   the pack's namespace (model names without one are its .bbmodels: "ns:model/name")
     * @param modelExists whether the pack has models/&lt;name&gt;.bbmodel (a full "ns:id" model is not checked)
     */
    public static VehicleSpec parse(ConfigurationSection v, String namespace, Predicate<String> modelExists) {
        double speed = positive(v, "speed");
        double reverse = positive(v, "reverse-speed");
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
        if (seats.isEmpty()) throw new IllegalArgumentException("vehicle.seats 가 비어 있음 (첫 자리가 운전석)");

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
            int pages = c.getInt("pages", 1);
            if (pages < 1 || pages > 6) throw new IllegalArgumentException("vehicle.cargo.pages 는 1 ~ 6");
            List<NamespacedKey> stages = new ArrayList<>();
            for (String s : c.getStringList("stages")) stages.add(model(s, "vehicle.cargo.stages", namespace, modelExists));
            cargo = new Cargo(pages, c.getDouble("behind", 0), vec(c, "at", "vehicle.cargo.at"), List.copyOf(stages));
        }
        double bodyScale = v.getDouble("body-scale", 1);
        if (bodyScale <= 0 || bodyScale > 4) throw new IllegalArgumentException("vehicle.body-scale 은 0 초과 4 이하");
        double step = v.getDouble("step-height", 1.0);
        if (step < 0 || step > 1.5) throw new IllegalArgumentException("vehicle.step-height 는 0 ~ 1.5");
        return new VehicleSpec((float) bodyScale, speed, reverse, positive(v, "acceleration"), positive(v, "brake"),
                v.getDouble("coast", 2.0), positive(v, "turn-rate"), positive(v, "turn-speed"), step,
                size.get(0), size.get(1), size.get(2), v.getDouble("click-height", Math.min(1.2, size.get(2))),
                wheel, wheelRadius, List.copyOf(wheels), List.copyOf(seats), lights, horn, cargo, engine);
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

    /** Every model the vehicle shows besides its body (wheel, light bar looks, load stages), for the render budget. */
    public List<NamespacedKey> partModels() {
        List<NamespacedKey> out = new ArrayList<>();
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
