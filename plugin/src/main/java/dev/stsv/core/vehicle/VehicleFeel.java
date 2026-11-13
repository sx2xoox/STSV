package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 탈것의 느낌 (contents/&lt;pack&gt;/vehicle_feel.yml): how a load and a slope change a vehicle's pace, what human effort
 * costs, which lamps and engine sounds it has. Pure (unit-tested); {@link VehicleFeelView} shows it.
 *
 * <ul>
 *   <li><b>Load</b> ({@link #topFactor}, {@link #accelFactor}): the share of the payload carried ({@link #share}: weight
 *       over payload, or slots used over slots where nothing is weighed) takes up to {@link Profile#topLoss} off the top
 *       speed and {@link Profile#accelLoss} off the acceleration. Muscle (a cart, a bicycle, a 지게) feels it more than an
 *       ox, an ox more than an engine.</li>
 *   <li><b>Slope</b> ({@link #grade}): how much the vehicle rose or sank over the last few blocks it went. Uphill takes
 *       {@link Profile#uphill} per unit of grade off both (half of it empty, all of it full); downhill a wheeled vehicle
 *       runs a little faster, at most {@link Profile#downhillMax}.</li>
 *   <li><b>Effort</b> ({@link #exhaustion}): pulling a cart, riding a bicycle, carrying a 지게 costs a little food per block,
 *       more with a load and uphill.</li>
 * </ul>
 */
public final class VehicleFeel {

    /** Never slower than this share of the top speed / acceleration on a slope (a steep road still climbs). */
    public static final double MIN_SLOPE = 0.55;
    /** Never less than this share of the acceleration under a load. */
    public static final double MIN_ACCEL = 0.25;
    /** The grade is the rise over about this many blocks of way (blocks). */
    public static final double GRADE_WINDOW = 2.5;
    /** A move rising or falling more than this in a tick is a fall or a jump, not a slope (blocks). */
    public static final double GRADE_MAX_DY = 1.2;

    private VehicleFeel() {}

    /** What moves the vehicle. */
    public enum Power { ENGINE, MUSCLE, ANIMAL }

    /**
     * How a kind of power feels a load and a slope.
     *
     * @param topLoss     the top speed's share lost at a full load (0.07 = 7 %)
     * @param accelLoss   the acceleration's share lost at a full load
     * @param uphill      the share lost per unit of uphill grade (half of it empty, all of it at a full load)
     * @param downhill    the top speed's share gained per unit of downhill grade (wheeled only)
     * @param downhillMax the most it gains downhill
     */
    public record Profile(double topLoss, double accelLoss, double uphill, double downhill, double downhillMax) {
        public Profile {
            topLoss = Math.clamp(topLoss, 0, 0.5);
            accelLoss = Math.clamp(accelLoss, 0, 0.8);
            uphill = Math.clamp(uphill, 0, 1);
            downhill = Math.clamp(downhill, 0, 1);
            downhillMax = Math.clamp(downhillMax, 0, 0.3);
        }

        public static final Profile NONE = new Profile(0, 0, 0, 0, 0);
    }

    /** The default profiles (the file's power: section overrides them). */
    public static Profile defaultProfile(Power p) {
        return switch (p) {
            case ENGINE -> new Profile(0.07, 0.3, 0.3, 0.15, 0.08);
            case MUSCLE -> new Profile(0.15, 0.5, 0.5, 0.2, 0.1);
            case ANIMAL -> new Profile(0.12, 0.4, 0.4, 0.1, 0.05);
        };
    }

    /**
     * Human effort: exhaustion (Minecraft's: 4 = half a food point, sprinting is 0.1 a block) per block of way, empty
     * and extra at a full load; uphill times (1 + uphill x grade).
     */
    public record Effort(double perBlock, double perBlockLoaded, double uphill) {
        public static final Effort NONE = new Effort(0, 0, 0);

        public boolean none() {
            return perBlock <= 0 && perBlockLoaded <= 0;
        }
    }

    /** A sound played once: key, loudness and (an engine start) the ticks before the engine's own hum starts. */
    public record Sound(String key, float volume, int ticks) {}

    /** The lamp models (null = the vehicle has no such lamp). */
    public record Lamps(NamespacedKey head, NamespacedKey tail, NamespacedKey brake, NamespacedKey reverse) {
        public static final Lamps NONE = new Lamps(null, null, null, null);

        public boolean front() {
            return head != null;
        }

        public boolean rear() {
            return tail != null || brake != null || reverse != null;
        }
    }

    /**
     * One vehicle's feel (vehicles.&lt;id&gt; in the file).
     *
     * @param idleShake how far its body shakes while idling (blocks, 0 = still)
     * @param bounce    the suspension's share (1 = the file's suspension as it is, 0 = none)
     * @param pedalBob  how far the rider rises and sinks with each pedal stroke (blocks: the bicycle)
     * @param sport     a sports car's feel of speed ({@link SportFeel}), or null
     */
    public record Spec(Power power, Profile profile, Effort effort, Lamps lamps, boolean reverseBeep, Sound start, Sound stop,
                       double idleShake, double bounce, double pedalBob, SportFeel.Spec sport) {
        public static Spec plain(Power p, Profile profile) {
            return new Spec(p, profile, Effort.NONE, Lamps.NONE, false, null, null, 0, 1, 0, null);
        }

        /** The engine starts and stops with its own sounds (it runs while anyone rides). */
        public boolean ignition() {
            return start != null || stop != null;
        }
    }

    /**
     * What is the same for every vehicle.
     *
     * @param dust       wheel effects on the ground (dust, mud, splashes, snow)
     * @param dustEvery  ticks between two
     * @param dustSpeed  only above this speed (blocks/s)
     * @param range      only with a player this close (blocks)
     * @param onBelow    the lamps come on at this light or darker (0..15)
     * @param offAbove   ... and go off at this light or brighter
     * @param brakeHold  the brake lamps stay on at least this long (ticks)
     * @param beep       the reverse beep, or null
     * @param beepEvery  ticks between two beeps
     * @param suspension the body bounces over steps (and the load sways)
     * @param bounce     how far a step drops the body (blocks)
     * @param pitch      how far a step or a hard brake nods it (degrees)
     * @param sway       the most a load sways (degrees)
     * @param showRange  bounces and shakes are sent only with a player this close (blocks)
     */
    public record Settings(boolean dust, int dustEvery, double dustSpeed, double range, int onBelow, int offAbove, int brakeHold,
                           Sound beep, int beepEvery, boolean suspension, double bounce, double pitch, double sway, double showRange) {
        public static final Settings DEFAULT = new Settings(true, 3, 1.5, 32, 7, 10, 4, null, 14, true, 0.05, 2.0, 2.5, 40);
    }

    /** Everything in a vehicle_feel.yml: the shared settings and each vehicle's feel by item id ("ns:id"). */
    public record File(Settings settings, Map<String, Spec> vehicles, Map<Power, Profile> profiles, Map<String, SkidMarks.Style> marks) {
        public static final File EMPTY = new File(Settings.DEFAULT, Map.of(), Map.of(), Map.of());

        public File(Settings settings, Map<String, Spec> vehicles, Map<Power, Profile> profiles) {
            this(settings, vehicles, profiles, Map.of());
        }

        /** A vehicle's feel: its own, else a plain one for its power (an engine). */
        public Spec spec(String itemKey, Power fallback) {
            Spec s = vehicles.get(itemKey);
            if (s != null) return s;
            return Spec.plain(fallback, profiles.getOrDefault(fallback, defaultProfile(fallback)));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reading the file
    // ------------------------------------------------------------------------------------------

    /**
     * Reads a vehicle_feel.yml. namespace: the pack's (item ids and model names without one are its own); modelExists:
     * whether the pack has models/&lt;name&gt;.bbmodel. A vehicle whose section is wrong is left out and reported to problems.
     */
    public static File parse(ConfigurationSection y, String namespace, Predicate<String> modelExists, java.util.function.Consumer<String> problems) {
        Map<Power, Profile> profiles = new HashMap<>();
        for (Power p : Power.values()) {
            Profile d = defaultProfile(p);
            ConfigurationSection s = y.getConfigurationSection("power." + p.name().toLowerCase(java.util.Locale.ROOT));
            profiles.put(p, s == null ? d : new Profile(s.getDouble("top-loss", d.topLoss()), s.getDouble("accel-loss", d.accelLoss()),
                    s.getDouble("uphill", d.uphill()), s.getDouble("downhill", d.downhill()), s.getDouble("downhill-max", d.downhillMax())));
        }
        Settings d = Settings.DEFAULT;
        int onBelow = Math.clamp(y.getInt("lights.on-below", d.onBelow()), 0, 15);
        int offAbove = Math.clamp(y.getInt("lights.off-above", d.offAbove()), onBelow + 1, 16);
        Sound beep = null;
        if (y.isConfigurationSection("lights.reverse-beep")) {
            ConfigurationSection b = y.getConfigurationSection("lights.reverse-beep");
            beep = sound(b, "lights.reverse-beep");
        }
        Settings settings = new Settings(y.getBoolean("ground-effects.enabled", d.dust()),
                Math.clamp(y.getInt("ground-effects.every", d.dustEvery()), 1, 40),
                Math.max(0, y.getDouble("ground-effects.min-speed", d.dustSpeed())),
                Math.clamp(y.getDouble("ground-effects.range", d.range()), 4, 96),
                onBelow, offAbove, Math.clamp(y.getInt("lights.brake-hold", d.brakeHold()), 0, 40), beep,
                Math.clamp(y.getInt("lights.reverse-beep.every", d.beepEvery()), 4, 100),
                y.getBoolean("suspension.enabled", d.suspension()),
                Math.clamp(y.getDouble("suspension.bounce", d.bounce()), 0, 0.3),
                Math.clamp(y.getDouble("suspension.pitch", d.pitch()), 0, 10),
                Math.clamp(y.getDouble("suspension.sway", d.sway()), 0, 15),
                Math.clamp(y.getDouble("suspension.range", d.showRange()), 8, 128));

        // the looks of skid marks, by name (a vehicle names its own: sport.mark)
        Map<String, SkidMarks.Style> marks = new HashMap<>();
        ConfigurationSection ms = y.getConfigurationSection("skid-marks");
        if (ms != null) {
            for (String name : ms.getKeys(false)) {
                if (!ms.isConfigurationSection(name)) continue;
                try {
                    marks.put(name, SkidMarks.style(name, ms.getConfigurationSection(name)));
                } catch (IllegalArgumentException ex) {
                    problems.accept(ex.getMessage());
                }
            }
        }

        Map<String, Spec> vehicles = new HashMap<>();
        ConfigurationSection vs = y.getConfigurationSection("vehicles");
        if (vs != null) {
            for (String id : vs.getKeys(false)) {
                ConfigurationSection v = vs.getConfigurationSection(id);
                if (v == null) continue;
                String key = id.contains(":") ? id : namespace + ":" + id;
                try {
                    vehicles.put(key, spec(v, namespace, modelExists, profiles, marks));
                } catch (IllegalArgumentException ex) {
                    problems.accept(key + ": " + ex.getMessage());
                }
            }
        }
        return new File(settings, Map.copyOf(vehicles), Map.copyOf(profiles), Map.copyOf(marks));
    }

    static Spec spec(ConfigurationSection v, String namespace, Predicate<String> modelExists, Map<Power, Profile> profiles) {
        return spec(v, namespace, modelExists, profiles, Map.of());
    }

    static Spec spec(ConfigurationSection v, String namespace, Predicate<String> modelExists, Map<Power, Profile> profiles,
                     Map<String, SkidMarks.Style> marks) {
        String powerName = v.getString("power", "engine").trim().toUpperCase(java.util.Locale.ROOT);
        Power power;
        try {
            power = Power.valueOf(powerName);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("power 는 engine / muscle / animal");
        }
        Effort effort = Effort.NONE;
        if (v.isConfigurationSection("effort")) {
            ConfigurationSection e = v.getConfigurationSection("effort");
            double per = e.getDouble("per-block", 0), loaded = e.getDouble("per-block-loaded", 0), up = e.getDouble("uphill", 0);
            if (per < 0 || per > 0.5 || loaded < 0 || loaded > 0.5) throw new IllegalArgumentException("effort.per-block(-loaded) 는 0 ~ 0.5");
            if (up < 0 || up > 5) throw new IllegalArgumentException("effort.uphill 은 0 ~ 5");
            effort = new Effort(per, loaded, up);
        }
        Lamps lamps = Lamps.NONE;
        if (v.isConfigurationSection("lights")) {
            ConfigurationSection l = v.getConfigurationSection("lights");
            lamps = new Lamps(model(l.getString("head"), "lights.head", namespace, modelExists),
                    model(l.getString("tail"), "lights.tail", namespace, modelExists),
                    model(l.getString("brake"), "lights.brake", namespace, modelExists),
                    model(l.getString("reverse"), "lights.reverse", namespace, modelExists));
        }
        Sound start = v.isConfigurationSection("engine-start") ? sound(v.getConfigurationSection("engine-start"), "engine-start") : null;
        Sound stop = v.isConfigurationSection("engine-stop") ? sound(v.getConfigurationSection("engine-stop"), "engine-stop") : null;
        double shake = v.getDouble("idle-shake", 0), bounce = v.getDouble("bounce", 1), bob = v.getDouble("pedal-bob", 0);
        if (shake < 0 || shake > 0.1) throw new IllegalArgumentException("idle-shake 는 0 ~ 0.1 (블록)");
        if (bounce < 0 || bounce > 3) throw new IllegalArgumentException("bounce 는 0 ~ 3");
        if (bob < 0 || bob > 0.2) throw new IllegalArgumentException("pedal-bob 은 0 ~ 0.2 (블록)");
        SportFeel.Spec sport = v.isConfigurationSection("sport") ? SportFeel.parse(v.getConfigurationSection("sport"), marks) : null;
        return new Spec(power, profiles.getOrDefault(power, defaultProfile(power)), effort, lamps, v.getBoolean("reverse-beep"),
                start, stop, shake, bounce, bob, sport);
    }

    static Sound sound(ConfigurationSection s, String what) {
        String key = s.getString("sound");
        if (key == null || key.isBlank() || NamespacedKey.fromString(key.trim()) == null)
            throw new IllegalArgumentException(what + ".sound 가 없거나 잘못됨");
        double volume = s.getDouble("volume", 0.6);
        if (!(volume > 0) || volume > 2) throw new IllegalArgumentException(what + ".volume 은 0 초과 2 이하");
        int ticks = s.getInt("ticks", 0);
        if (ticks < 0 || ticks > 100) throw new IllegalArgumentException(what + ".ticks 는 0 ~ 100");
        return new Sound(key.trim(), (float) volume, ticks);
    }

    private static NamespacedKey model(String name, String what, String namespace, Predicate<String> modelExists) {
        if (name == null || name.isBlank()) return null;
        if (name.contains(":")) {
            NamespacedKey k = NamespacedKey.fromString(name.trim());
            if (k == null) throw new IllegalArgumentException(what + ": 잘못된 모델 id " + name);
            return k;
        }
        if (!modelExists.test(name.trim())) throw new IllegalArgumentException(what + ": models/" + name + ".bbmodel 이 없음");
        return new NamespacedKey(namespace, "model/" + name.trim());
    }

    // ------------------------------------------------------------------------------------------
    // Load and slope
    // ------------------------------------------------------------------------------------------

    /** The share of the payload carried, 0..1 (weight over payload; nothing to weigh: 0). */
    public static double share(double weight, double payload) {
        if (!(payload > 0) || !(weight > 0)) return 0;
        return Math.min(1, weight / payload);
    }

    /** The share lost on an uphill grade (0..1 - MIN_SLOPE): half of it empty, all of it at a full load. */
    static double uphillLoss(Profile p, double share, double grade) {
        if (grade <= 0) return 0;
        return Math.min(1 - MIN_SLOPE, p.uphill() * Math.min(1, grade) * (0.5 + 0.5 * Math.clamp(share, 0, 1)));
    }

    /**
     * The top speed's factor: less with a load (up to topLoss at a full one) and uphill; a little more downhill when
     * wheeled (up to downhillMax). The caller keeps it under the server's top speed.
     */
    public static double topFactor(Profile p, double share, double grade, boolean wheeled) {
        double f = 1 - p.topLoss() * Math.clamp(share, 0, 1);
        if (grade > 0) f *= 1 - uphillLoss(p, share, grade);
        else if (grade < 0 && wheeled) f *= 1 + Math.min(p.downhillMax(), p.downhill() * Math.min(1, -grade));
        return f;
    }

    /** The acceleration's factor: less with a load (up to accelLoss) and uphill; never under {@link #MIN_ACCEL}. */
    public static double accelFactor(Profile p, double share, double grade) {
        double f = (1 - p.accelLoss() * Math.clamp(share, 0, 1)) * (1 - uphillLoss(p, share, grade));
        return Math.max(MIN_ACCEL, f);
    }

    /**
     * The grade after a move of dist blocks (horizontally) rising dy: the rise per block over about {@link #GRADE_WINDOW}
     * blocks of way (+ = uphill whichever way it goes). Standing still keeps it; a fall or a jump (more than
     * {@link #GRADE_MAX_DY} in a tick) or a move in the air does not count.
     */
    public static double grade(double was, double dy, double dist, boolean onGround) {
        if (dist < 1e-3 || !onGround || Math.abs(dy) > GRADE_MAX_DY) return was;
        double now = Math.clamp(dy / dist, -1, 1);
        double a = Math.min(1, dist / GRADE_WINDOW);
        double g = was + (now - was) * a;
        return Math.abs(g) < 1e-4 ? 0 : g;
    }

    /** The puller's (or wearer's) slowdown: the base slowdown, then the factor of what is left (load, slope). */
    public static double slowdown(double base, double factor) {
        return Math.clamp(1 - (1 - Math.clamp(base, 0, 0.9)) * Math.max(0, factor), 0, 0.9);
    }

    /** Exhaustion for blocks of way with this share of the payload on this grade. */
    public static double exhaustion(double blocks, double share, double grade, Effort e) {
        if (e == null || e.none() || !(blocks > 0)) return 0;
        double per = e.perBlock() + e.perBlockLoaded() * Math.clamp(share, 0, 1);
        return blocks * per * (1 + e.uphill() * Math.clamp(grade, 0, 1));
    }
}
