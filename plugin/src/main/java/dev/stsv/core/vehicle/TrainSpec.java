package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * An articulated vehicle's {@code vehicle.train:} section (content/stsv/items/vehicles_farm.yml: 소달구지, 경운기, 트랙터):
 * the driven unit (the vehicle's own size, wheels, seats, horn, engine: the ox, the tiller, the tractor) and one or more
 * towed units behind it in a chain, each on its own wheels, holding the cargo. Pure (unit-tested); {@link TrainPhysics}
 * moves it, {@link TrainView} draws it.
 *
 * <pre>
 * train:
 *   jackknife: 55            # the largest angle between two units (degrees)
 *   slow-from: 0.6           # steering slows down from this share of it on (0.6 = from 33 degrees), to nothing at it
 *   ground: { farmland: 0.6, path: 0.9, dirt: 0.85, grass: 0.95 }   # top speed share on that ground (loose ground -
 *                            sand, gravel, snow, mud - is every vehicle's ROUGH: VehiclePhysics, all-terrain: true skips it)
 *   trailers:                # in order: the first hangs on the driven unit, every next one on the one before it
 *     - model: ox_cart_cart  # its body (.bbmodel), body-scale like the vehicle's
 *       hitch: [0, 1.0, 0.6] # where it hangs: a point on the unit in front of it ([right, up, forward], that unit's frame)
 *       tongue: 2.6          # from its axle (its centre, between its wheels) forward to that point (blocks)
 *       size: [2.3, 1.5, 1.2]
 *       wheel: wheel_oxcart
 *       wheel-radius: 0.5625
 *       wheels: [ { at: [-0.8, 0.5625, 0] }, { at: [0.8, 0.5625, 0] } ]
 *       cargo: { slots: 270, at: [0, 0.75, 0], stages: [...] }   # its own bed (45 .. 540 slots)
 *   animal: ...              # a draft animal drawn as the driven unit (see {@link Animal})
 *   parts: ...               # spinning parts of the driven unit (see {@link Part})
 * seats:                     # (the vehicle's own) unit: 0 = the driven unit (default), 1 = the first trailer, ...
 *   - { at: [-0.3, 0.9, 0.7], unit: 1 }
 * </pre>
 *
 * <p>The beds of all trailers are one storage of {@link #slots} slots (the vehicle's {@link VehicleSpec#cargo}): trailer 1
 * holds its first pages, trailer 2 the next ones, and so on ({@link #firstPage}, {@link #unitOfPage}). Each trailer shows its
 * own load by how full its own pages are. A train may hold more than one bed's 540 slots: the limit is per trailer.
 *
 * @param jackknife degrees two units may stand at, at most
 * @param slowFrom  steering that bends the train further slows from this share of the limit on (0 .. 1)
 * @param trailers  the towed units, front to back
 * @param seatUnits per seat of the vehicle: the unit it is on (0 = the driven one)
 * @param ground    the top speed's share on each kind of ground (missing = 1)
 * @param animal    the draft animal's moving parts, or null
 * @param parts     spinning parts of the driven unit
 * @param reach     how far from the driven unit's centre the whole train may reach (blocks; for chunk loading)
 */
public record TrainSpec(double jackknife, double slowFrom, List<Trailer> trailers, List<Integer> seatUnits, Map<Surface, Double> ground,
                        Animal animal, List<Part> parts, double reach) {

    /** Kinds of ground the train cares about (see {@link TrainPhysics.Ground}). */
    public enum Surface { OTHER, FARMLAND, PATH, DIRT, GRASS }

    /** Without a {@code ground:} section: what a wheeled cart feels on rough ground. */
    public static final Map<Surface, Double> DEFAULT_GROUND = Map.of(Surface.FARMLAND, 0.6, Surface.PATH, 0.9, Surface.DIRT, 0.85,
            Surface.GRASS, 0.95);

    /**
     * One towed unit. unit: its collision box and wheels as a vehicle of their own (no seats, never driven), used for its
     * moves; its centre is its axle.
     */
    public record Trailer(NamespacedKey model, float bodyScale, VehicleSpec.Vec hitch, double tongue, VehicleSpec unit,
                          VehicleSpec.Cargo cargo) {}

    /**
     * A draft animal's moving parts. Its legs swing back and forth by {@code swing} degrees as it walks (one full step
     * every {@code stride} blocks, each leg at its own {@code phase} of it: a walk), its head bobs up and down by
     * {@code bob} blocks twice a stride. It walks as far as the vehicle moves (see {@link Gait}). steps: a hoof sound every
     * so many blocks, or null.
     */
    public record Animal(double stride, double swing, List<Leg> legs, NamespacedKey head, VehicleSpec.Vec headAt, double bob,
                         VehicleSpec.Roll steps) {}

    /** A leg model swung about its top (the model's (8, 8, 8)), at its hip, phase 0 .. 1 of the stride. */
    public record Leg(NamespacedKey model, VehicleSpec.Vec at, double phase) {}

    /**
     * A part of the driven unit that turns about its own (8, 8, 8): axis x (across) or z (along). Spin ENGINE: while
     * someone drives, idle degrees a tick standing, top at top speed (a flywheel); ROLL: rolls like a wheel of this
     * radius (a tractor's big rear wheels).
     */
    public record Part(NamespacedKey model, VehicleSpec.Vec at, char axis, Spin spin, double idle, double top, double radius) {}

    public enum Spin { ENGINE, ROLL }

    /** Reads {@code vehicle.train} (v: the vehicle section). */
    public static TrainSpec parse(ConfigurationSection v, String namespace, Predicate<String> modelExists) {
        ConfigurationSection t = v.getConfigurationSection("train");
        if (t == null) throw new IllegalArgumentException("vehicle.train 이 없음");
        double jack = t.getDouble("jackknife", 55);
        if (jack < 10 || jack > 89) throw new IllegalArgumentException("vehicle.train.jackknife 는 10 ~ 89 (도)");
        double slow = t.getDouble("slow-from", 0.6);
        if (slow < 0 || slow >= 1) throw new IllegalArgumentException("vehicle.train.slow-from 은 0 이상 1 미만");
        List<Trailer> trailers = new ArrayList<>();
        float scale = (float) v.getDouble("body-scale", 1);
        for (Map<?, ?> m : t.getMapList("trailers")) trailers.add(trailer(section(m), scale, namespace, modelExists, trailers.size() + 1));
        if (trailers.isEmpty()) throw new IllegalArgumentException("vehicle.train.trailers 가 비어 있음");
        if (trailers.size() > 4) throw new IllegalArgumentException("vehicle.train.trailers 는 4개까지");

        List<Integer> seatUnits = new ArrayList<>();
        for (Map<?, ?> m : v.getMapList("seats")) {
            int u = section(m).getInt("unit", 0);
            if (u < 0 || u > trailers.size()) throw new IllegalArgumentException("vehicle.seats.unit 은 0 ~ " + trailers.size() + " (0 = 끄는 쪽)");
            seatUnits.add(u);
        }

        Map<Surface, Double> ground = new EnumMap<>(Surface.class);
        if (t.isConfigurationSection("ground")) {
            ConfigurationSection g = t.getConfigurationSection("ground");
            for (String k : g.getKeys(false)) {
                Surface s;
                try {
                    s = Surface.valueOf(k.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("vehicle.train.ground: 모르는 땅 " + k + " (farmland, path, dirt, grass)");
                }
                double f = g.getDouble(k);
                if (f < 0.2 || f > 1) throw new IllegalArgumentException("vehicle.train.ground." + k + " 는 0.2 ~ 1");
                ground.put(s, f);
            }
        } else {
            ground.putAll(DEFAULT_GROUND);
        }

        Animal animal = t.isConfigurationSection("animal") ? animal(t.getConfigurationSection("animal"), namespace, modelExists) : null;
        List<Part> parts = new ArrayList<>();
        for (Map<?, ?> m : t.getMapList("parts")) parts.add(part(section(m), namespace, modelExists));

        // the farthest a unit reaches behind the driven one: every hitch and tongue in a line, plus the last unit's half length
        double reach = 0;
        for (Trailer tr : trailers) reach += Math.abs(tr.hitch().forward()) + tr.tongue();
        Trailer last = trailers.getLast();
        reach += Math.hypot(last.unit().length(), last.unit().width()) / 2 + 1;
        return new TrainSpec(jack, slow, List.copyOf(trailers), List.copyOf(seatUnits), Map.copyOf(ground), animal, List.copyOf(parts), reach);
    }

    private static Trailer trailer(ConfigurationSection s, float scale, String ns, Predicate<String> exists, int n) {
        String what = "vehicle.train.trailers[" + n + "]";
        NamespacedKey model = model(s.getString("model"), what + ".model", ns, exists);
        VehicleSpec.Vec hitch = vec(s, "hitch", what + ".hitch");
        double tongue = s.getDouble("tongue", 0);
        if (tongue < 0.5 || tongue > 6) throw new IllegalArgumentException(what + ".tongue 는 0.5 ~ 6 (블록, 바퀴 축에서 연결점까지)");
        if (hitch.forward() > 0 && n > 1) throw new IllegalArgumentException(what + ".hitch: 앞 트레일러의 뒤쪽 (forward < 0)");
        List<Double> size = s.getDoubleList("size");
        if (size.size() != 3 || size.stream().anyMatch(d -> d <= 0)) throw new IllegalArgumentException(what + ".size 는 [길이, 너비, 높이] (블록)");
        double radius = s.getDouble("wheel-radius", 0);
        if (radius <= 0) throw new IllegalArgumentException(what + ".wheel-radius 는 0보다 커야 함");
        NamespacedKey wheel = model(s.getString("wheel"), what + ".wheel", ns, exists);
        List<VehicleSpec.Wheel> wheels = new ArrayList<>();
        for (Map<?, ?> m : s.getMapList("wheels")) wheels.add(new VehicleSpec.Wheel(vec(section(m), "at", what + ".wheels.at"), false));
        if (wheels.isEmpty()) throw new IllegalArgumentException(what + ".wheels 가 비어 있음");
        double step = s.getDouble("step-height", 1.0);
        if (step < 0 || step > 1.5) throw new IllegalArgumentException(what + ".step-height 는 0 ~ 1.5");
        VehicleSpec.Cargo cargo = null;
        if (s.isConfigurationSection("cargo")) {
            ConfigurationSection c = s.getConfigurationSection("cargo");
            int slots = c.getInt("slots");
            if (slots < VehicleSpec.Cargo.PAGE || slots > VehicleSpec.Cargo.PAGE * VehicleSpec.Cargo.MAX_PAGES || slots % VehicleSpec.Cargo.PAGE != 0)
                throw new IllegalArgumentException(what + ".cargo.slots 는 45 의 배수, 45 ~ " + VehicleSpec.Cargo.PAGE * VehicleSpec.Cargo.MAX_PAGES);
            List<NamespacedKey> stages = new ArrayList<>();
            for (String st : c.getStringList("stages")) stages.add(model(st, what + ".cargo.stages", ns, exists));
            cargo = new VehicleSpec.Cargo(slots / VehicleSpec.Cargo.PAGE, 0, vec(c, "at", what + ".cargo.at"), List.copyOf(stages));
        }
        // the unit as a vehicle of its own: rolls to a stop like a pulled cart, never driven
        VehicleSpec unit = new VehicleSpec(scale, 1, 1, 1, 8, 8, 90, 1, step, size.get(0), size.get(1), size.get(2),
                s.getDouble("click-height", Math.min(1.2, size.get(2))), wheel, radius, List.copyOf(wheels), List.of(), null, null, cargo, null, null);
        return new Trailer(model, (float) s.getDouble("body-scale", scale), hitch, tongue, unit, cargo);
    }

    private static Animal animal(ConfigurationSection a, String ns, Predicate<String> exists) {
        double stride = a.getDouble("stride", 1.6);
        if (stride < 0.3 || stride > 6) throw new IllegalArgumentException("vehicle.train.animal.stride 는 0.3 ~ 6 (블록)");
        double swing = a.getDouble("swing", 25);
        if (swing < 0 || swing > 60) throw new IllegalArgumentException("vehicle.train.animal.swing 은 0 ~ 60 (도)");
        List<Leg> legs = new ArrayList<>();
        for (Map<?, ?> m : a.getMapList("legs")) {
            ConfigurationSection l = section(m);
            double phase = l.getDouble("phase", 0);
            if (phase < 0 || phase >= 1) throw new IllegalArgumentException("vehicle.train.animal.legs.phase 는 0 이상 1 미만");
            legs.add(new Leg(model(l.getString("model"), "vehicle.train.animal.legs.model", ns, exists), vec(l, "at", "vehicle.train.animal.legs.at"), phase));
        }
        NamespacedKey head = null;
        VehicleSpec.Vec headAt = null;
        double bob = 0;
        if (a.isConfigurationSection("head")) {
            ConfigurationSection h = a.getConfigurationSection("head");
            head = model(h.getString("model"), "vehicle.train.animal.head.model", ns, exists);
            headAt = vec(h, "at", "vehicle.train.animal.head.at");
            bob = h.getDouble("bob", 0.04);
            if (bob < 0 || bob > 0.3) throw new IllegalArgumentException("vehicle.train.animal.head.bob 은 0 ~ 0.3 (블록)");
        }
        VehicleSpec.Roll steps = null;
        if (a.isConfigurationSection("steps")) {
            ConfigurationSection r = a.getConfigurationSection("steps");
            String key = r.getString("key");
            if (key == null || NamespacedKey.fromString(key.trim()) == null) throw new IllegalArgumentException("vehicle.train.animal.steps.key 가 없음");
            steps = new VehicleSpec.Roll(key.trim(), (float) Math.clamp(r.getDouble("volume", 0.25), 0.01, 1),
                    (float) Math.clamp(r.getDouble("pitch", 1), VehicleSounds.MIN_PITCH, VehicleSounds.MAX_PITCH), Math.clamp(r.getDouble("every", 0.8), 0.3, 8));
        }
        return new Animal(stride, swing, List.copyOf(legs), head, headAt, bob, steps);
    }

    private static Part part(ConfigurationSection p, String ns, Predicate<String> exists) {
        String axis = p.getString("axis", "x");
        if (!axis.equals("x") && !axis.equals("z")) throw new IllegalArgumentException("vehicle.train.parts.axis 는 x 또는 z");
        Spin spin;
        try {
            spin = Spin.valueOf(p.getString("spin", "engine").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("vehicle.train.parts.spin 은 engine 또는 roll");
        }
        double idle = p.getDouble("idle", 20), top = p.getDouble("top", 45), radius = p.getDouble("radius", 0.5);
        if (idle < 0 || top < idle || top > 80) throw new IllegalArgumentException("vehicle.train.parts: 0 <= idle <= top <= 80 (도/틱)");
        if (radius <= 0) throw new IllegalArgumentException("vehicle.train.parts.radius 는 0보다 커야 함");
        return new Part(model(p.getString("model"), "vehicle.train.parts.model", ns, exists), vec(p, "at", "vehicle.train.parts.at"),
                axis.charAt(0), spin, idle, top, radius);
    }

    // ------------------------------------------------------------------------------------------
    // The beds of all trailers as one storage
    // ------------------------------------------------------------------------------------------

    /** All trailers' beds together, as the vehicle's storage (its pages, no load model of its own). */
    public VehicleSpec.Cargo cargo() {
        int pages = 0;
        for (Trailer t : trailers) if (t.cargo() != null) pages += t.cargo().pages();
        return pages == 0 ? null : new VehicleSpec.Cargo(pages, 0, new VehicleSpec.Vec(0, 0, 0), List.of());
    }

    /** Every slot of every trailer. */
    public int slots() {
        int n = 0;
        for (Trailer t : trailers) if (t.cargo() != null) n += t.cargo().slots();
        return n;
    }

    /** The first page of a unit's bed (unit 1 = the first trailer) in the storage; -1 if it has none. */
    public int firstPage(int unit) {
        if (unit < 1 || unit > trailers.size() || trailers.get(unit - 1).cargo() == null) return -1;
        int p = 0;
        for (int i = 0; i < unit - 1; i++) if (trailers.get(i).cargo() != null) p += trailers.get(i).cargo().pages();
        return p;
    }

    /** The unit whose bed shows this page (pages past the last bed: the last one with a bed). */
    public int unitOfPage(int page) {
        int p = 0, last = -1;
        for (int i = 0; i < trailers.size(); i++) {
            VehicleSpec.Cargo c = trailers.get(i).cargo();
            if (c == null) continue;
            last = i + 1;
            if (page < p + c.pages()) return i + 1;
            p += c.pages();
        }
        return last;
    }

    /** How many slots of a unit's bed hold something (storage: all beds, null = empty slots). */
    public int used(Object[] storage, int unit) {
        int first = firstPage(unit);
        if (first < 0 || storage == null) return 0;
        int from = first * VehicleSpec.Cargo.PAGE, to = Math.min(storage.length, from + trailers.get(unit - 1).cargo().slots());
        int n = 0;
        for (int i = from; i < to; i++) if (storage[i] != null) n++;
        return n;
    }

    /** The load model a unit's bed shows now (null = none): by how full its own pages are ({@link CargoStages}). */
    public NamespacedKey stage(Object[] storage, int unit) {
        if (unit < 1 || unit > trailers.size()) return null;
        VehicleSpec.Cargo c = trailers.get(unit - 1).cargo();
        if (c == null || c.stages().isEmpty()) return null;
        int s = CargoStages.stage(used(storage, unit), c.slots(), c.stages().size());
        return s <= 0 ? null : c.stages().get(s - 1);
    }

    /** The unit a seat is on. */
    public int seatUnit(int seat) {
        return seat >= 0 && seat < seatUnits.size() ? seatUnits.get(seat) : 0;
    }

    /** The top speed's share on this ground. */
    public double ground(Surface s) {
        return s == null ? 1 : ground.getOrDefault(s, 1.0);
    }

    /** Every model the train shows besides the driven unit's body and wheels. */
    public List<NamespacedKey> models() {
        List<NamespacedKey> out = new ArrayList<>();
        for (Trailer t : trailers) {
            out.add(t.model());
            out.add(t.unit().wheelModel());
            if (t.cargo() != null) out.addAll(t.cargo().stages());
        }
        if (animal != null) {
            for (Leg l : animal.legs()) out.add(l.model());
            if (animal.head() != null) out.add(animal.head());
        }
        for (Part p : parts) out.add(p.model());
        return out;
    }

    // ------------------------------------------------------------------------------------------

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

    private static VehicleSpec.Vec vec(ConfigurationSection s, String key, String what) {
        List<Double> l = s.getDoubleList(key);
        if (l.size() != 3) throw new IllegalArgumentException(what + " 는 [오른쪽, 위, 앞] (블록)");
        return new VehicleSpec.Vec(l.get(0), l.get(1), l.get(2));
    }

    private static ConfigurationSection section(Map<?, ?> map) {
        return new YamlConfiguration().createSection("entry", map);
    }
}
