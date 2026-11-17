package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * 특별 탈것 (content/stsv/items/vehicles_fun.yml): a vehicle's {@code fun:} section, what it does besides driving. Every
 * part is optional and absent = off, so a vehicle without the section (every other vehicle) drives as before. Pure
 * (unit-tested); {@link FunPhysics} moves by it, {@link FunView} shows it.
 *
 * @param hop        it bounces all the time it is ridden, higher when space is pressed as it lands (the pogo stick), or null
 * @param ollie      space hops it a little while the board spins once round (the skateboard), or null
 * @param hover      it floats over the ground (or water) at a height the rider sets (the broom, the balloon chair), or null
 * @param toss       a hard turn at speed may throw the riders behind the driver off (the banana boat), or null
 * @param surface    its top speed by what it slides on (the sled: fast on snow and ice), or null
 * @param buck       it bucks and spins until it throws its rider (the rodeo bull), or null
 * @param amphibious it floats on water and walks on land (the toy duck), or null
 * @param pivot      it turns on the spot (A / D standing still: the segway)
 * @param tilt       degrees it tips forward speeding up and back braking (the segway; 0 = it stays level)
 * @param waddle     degrees it rocks side to side walking on land (the toy duck; 0 = it does not)
 * @param toot       a sound once per press of space (the duck's quack), or null
 * @param roll       a sound every few blocks it rolls (wheels on the road, sleigh bells), or null
 * @param lookSteer  it turns towards where the rider looks instead of with A / D (the pogo stick, the balloon chair)
 * @param trail      particles it leaves while it moves
 * @param part       a separate model moved by the behaviour (the bull on its stand, the balloons over the chair), or null
 */
public record FunSpec(Hop hop, Ollie ollie, Hover hover, Toss toss, Surface surface, Buck buck, Amphibious amphibious,
                      boolean pivot, double tilt, double waddle, Toot toot, VehicleSpec.Roll roll, boolean lookSteer, Trail trail, Part part) {

    /**
     * The pogo stick's bounce: it springs back to {@code base} blocks high every landing; space pressed in the last
     * {@code window} ticks of the fall (or as it touches down) adds {@code gain} to the next bounce, up to {@code max};
     * a landing without it loses {@code decay} again. {@code sound} plays at every landing.
     */
    public record Hop(double base, double max, double gain, double decay, int window, String sound) {}

    /** The skateboard's ollie: space on the ground hops it {@code height} blocks while it spins {@code spin} degrees round. */
    public record Ollie(double height, double spin, String sound) {}

    /**
     * Floating over the ground under it (water counts as ground): {@code start} blocks up once someone sits on it, space
     * raises that by {@code climb} blocks/s up to {@code max}, Ctrl or looking down lowers it by {@code sink} blocks/s to
     * {@code min}. Nobody on it, it settles onto the ground. {@code pop}: a projectile hitting it pops it (the balloon chair):
     * it floats down slowly and cannot rise until it has stood on the ground a while.
     */
    public record Hover(double start, double min, double max, double climb, double sink, boolean pop) {}

    /**
     * Turning hard at speed throws the riders behind the driver off: each tick the driver turns at least {@code minTurn}
     * degrees a second at {@code minSpeed} blocks/s or faster, each of them falls off with this {@code chance} (more the
     * faster and harder; none in the first seconds after they got on).
     */
    public record Toss(double minSpeed, double minTurn, double chance) {}

    /** The top speed's share on snow, on ice and on anything else; downhill it runs {@code downhill} faster per unit of grade. */
    public record Surface(double snow, double ice, double other, double downhill) {}

    /**
     * The rodeo bull: it starts bucking at {@code start} (0..1) and gets {@code growth} stronger every second ridden; the
     * rider keeps their balance with A / D. Thrown off, they fly {@code launch} blocks/tick away.
     */
    public record Buck(double start, double growth, double launch) {}

    /** Floats with its bottom {@code draft} blocks under the water and paddles at its speed; on land it waddles at {@code landSpeed}. */
    public record Amphibious(double draft, double landSpeed) {}

    /** A sound played once per press of space (rate-limited). */
    public record Toot(String sound, float volume, float pitch) {}

    /**
     * A separate model at a vehicle point ({@link VehicleSpec.Vec}), turned about that point by the behaviour; shown
     * {@code scale} times as large (a model too big to draw at its size is drawn smaller, as bodies are).
     */
    public record Part(NamespacedKey model, VehicleSpec.Vec at, double scale) {}

    /** Particles left behind while it moves. */
    public enum Trail { NONE, GLITTER, SNOW, BUBBLES }

    /** Reads a {@code fun:} section. */
    static FunSpec parse(ConfigurationSection f, String namespace, Predicate<String> modelExists) {
        Hop hop = null;
        if (f.isConfigurationSection("hop")) {
            ConfigurationSection h = f.getConfigurationSection("hop");
            hop = new Hop(h.getDouble("base", 0.8), h.getDouble("max", 5), h.getDouble("gain", 1), h.getDouble("decay", 0.6),
                    h.getInt("window", 6), sound(h.getString("sound", "entity.slime.jump"), "vehicle.fun.hop.sound"));
            if (hop.base() < 0.3 || hop.base() > 2) throw new IllegalArgumentException("vehicle.fun.hop.base 는 0.3 ~ 2 (블록)");
            if (hop.max() < hop.base() || hop.max() > FunPhysics.HOP_MAX) throw new IllegalArgumentException("vehicle.fun.hop.max 는 base ~ " + FunPhysics.HOP_MAX + " (블록)");
            if (hop.gain() <= 0 || hop.gain() > 3 || hop.decay() < 0 || hop.decay() > 3)
                throw new IllegalArgumentException("vehicle.fun.hop.gain 은 0 초과 3 이하, decay 는 0 ~ 3 (블록)");
            if (hop.window() < 1 || hop.window() > 20) throw new IllegalArgumentException("vehicle.fun.hop.window 는 1 ~ 20 (틱)");
        }
        Ollie ollie = null;
        if (f.isConfigurationSection("ollie")) {
            ConfigurationSection o = f.getConfigurationSection("ollie");
            ollie = new Ollie(o.getDouble("height", 1.1), o.getDouble("spin", 360), sound(o.getString("sound", "block.wood.place"), "vehicle.fun.ollie.sound"));
            if (ollie.height() < 0.3 || ollie.height() > 2) throw new IllegalArgumentException("vehicle.fun.ollie.height 는 0.3 ~ 2 (블록)");
            if (ollie.spin() < 0 || ollie.spin() > 720) throw new IllegalArgumentException("vehicle.fun.ollie.spin 은 0 ~ 720 (도)");
        }
        Hover hover = null;
        if (f.isConfigurationSection("hover")) {
            ConfigurationSection h = f.getConfigurationSection("hover");
            hover = new Hover(h.getDouble("start", 1.5), h.getDouble("min", 0.5), h.getDouble("max", 3), h.getDouble("climb", 1.5),
                    h.getDouble("sink", 2), h.getBoolean("pop"));
            if (hover.min() < 0.3 || hover.max() < hover.min() || hover.max() > FunPhysics.HOVER_MAX)
                throw new IllegalArgumentException("vehicle.fun.hover: 0.3 <= min <= max <= " + FunPhysics.HOVER_MAX + " (블록, 땅 위 높이)");
            if (hover.start() < hover.min() || hover.start() > hover.max()) throw new IllegalArgumentException("vehicle.fun.hover.start 는 min ~ max");
            if (hover.climb() <= 0 || hover.climb() > 5 || hover.sink() <= 0 || hover.sink() > 5)
                throw new IllegalArgumentException("vehicle.fun.hover.climb · sink 는 0 초과 5 이하 (블록/초)");
        }
        Toss toss = null;
        if (f.isConfigurationSection("toss")) {
            ConfigurationSection t = f.getConfigurationSection("toss");
            toss = new Toss(t.getDouble("min-speed", 4), t.getDouble("min-turn", 35), t.getDouble("chance", 0.05));
            if (toss.minSpeed() < 1 || toss.minTurn() < 5 || toss.chance() <= 0 || toss.chance() > 0.5)
                throw new IllegalArgumentException("vehicle.fun.toss: min-speed 1 이상, min-turn 5 이상 (도/초), chance 0 초과 0.5 이하");
        }
        Surface surface = null;
        if (f.isConfigurationSection("surface")) {
            ConfigurationSection s = f.getConfigurationSection("surface");
            surface = new Surface(s.getDouble("snow", 1), s.getDouble("ice", 1), s.getDouble("other", 0.4), s.getDouble("downhill", 0));
            for (double d : new double[]{surface.snow(), surface.ice(), surface.other()})
                if (d < 0.1 || d > 1) throw new IllegalArgumentException("vehicle.fun.surface.snow · ice · other 는 0.1 ~ 1 (최고 속도의 비율)");
            if (surface.downhill() < 0 || surface.downhill() > 1) throw new IllegalArgumentException("vehicle.fun.surface.downhill 은 0 ~ 1");
        }
        Buck buck = null;
        if (f.isConfigurationSection("buck")) {
            ConfigurationSection b = f.getConfigurationSection("buck");
            buck = new Buck(b.getDouble("start", 0.3), b.getDouble("growth", 0.03), b.getDouble("launch", 0.7));
            if (buck.start() <= 0 || buck.start() > 1 || buck.growth() <= 0 || buck.growth() > 0.5 || buck.launch() < 0 || buck.launch() > 1.5)
                throw new IllegalArgumentException("vehicle.fun.buck: start 0 초과 1 이하, growth 0 초과 0.5 이하 (초마다), launch 0 ~ 1.5");
        }
        Amphibious amphibious = null;
        if (f.isConfigurationSection("amphibious")) {
            ConfigurationSection a = f.getConfigurationSection("amphibious");
            amphibious = new Amphibious(a.getDouble("draft", 0.3), a.getDouble("land-speed", 1.2));
            if (amphibious.draft() < 0.05 || amphibious.draft() > 0.8) throw new IllegalArgumentException("vehicle.fun.amphibious.draft 는 0.05 ~ 0.8 (블록)");
            if (amphibious.landSpeed() <= 0 || amphibious.landSpeed() > 6) throw new IllegalArgumentException("vehicle.fun.amphibious.land-speed 는 0 초과 6 이하");
        }
        double tilt = f.getDouble("tilt", 0), waddle = f.getDouble("waddle", 0);
        if (tilt < 0 || tilt > 25) throw new IllegalArgumentException("vehicle.fun.tilt 는 0 ~ 25 (도)");
        if (waddle < 0 || waddle > 25) throw new IllegalArgumentException("vehicle.fun.waddle 은 0 ~ 25 (도)");
        Toot toot = null;
        if (f.isConfigurationSection("toot")) {
            ConfigurationSection t = f.getConfigurationSection("toot");
            toot = new Toot(sound(t.getString("sound"), "vehicle.fun.toot.sound"), (float) t.getDouble("volume", 1), (float) t.getDouble("pitch", 1));
            if (toot.volume() <= 0 || toot.volume() > 2) throw new IllegalArgumentException("vehicle.fun.toot.volume 은 0 초과 2 이하");
            if (toot.pitch() < VehicleSounds.MIN_PITCH || toot.pitch() > VehicleSounds.MAX_PITCH) throw new IllegalArgumentException("vehicle.fun.toot.pitch 는 0.5 ~ 2");
        }
        VehicleSpec.Roll roll = null;
        if (f.isConfigurationSection("roll")) {
            ConfigurationSection r = f.getConfigurationSection("roll");
            roll = new VehicleSpec.Roll(sound(r.getString("key"), "vehicle.fun.roll.key"), (float) r.getDouble("volume", 0.15),
                    (float) r.getDouble("pitch", 1), r.getDouble("every", 1.5));
            if (roll.volume() <= 0 || roll.volume() > 1) throw new IllegalArgumentException("vehicle.fun.roll.volume 은 0 초과 1 이하");
            if (roll.pitch() < VehicleSounds.MIN_PITCH || roll.pitch() > VehicleSounds.MAX_PITCH) throw new IllegalArgumentException("vehicle.fun.roll.pitch 는 0.5 ~ 2");
            if (roll.every() < 0.5 || roll.every() > 8) throw new IllegalArgumentException("vehicle.fun.roll.every 는 0.5 ~ 8 (블록)");
        }
        Trail trail;
        try {
            trail = Trail.valueOf(f.getString("trail", "none").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("vehicle.fun.trail 은 none · glitter · snow · bubbles 중 하나");
        }
        Part part = null;
        if (f.isConfigurationSection("part")) {
            ConfigurationSection p = f.getConfigurationSection("part");
            String model = p.getString("model");
            if (model == null || model.isBlank()) throw new IllegalArgumentException("vehicle.fun.part.model 이 없음");
            NamespacedKey key;
            if (model.contains(":")) {
                key = NamespacedKey.fromString(model.trim());
                if (key == null) throw new IllegalArgumentException("vehicle.fun.part.model: 잘못된 모델 id " + model);
            } else {
                if (!modelExists.test(model.trim())) throw new IllegalArgumentException("vehicle.fun.part.model: models/" + model + ".bbmodel 이 없음");
                key = new NamespacedKey(namespace, "model/" + model.trim());
            }
            java.util.List<Double> at = p.getDoubleList("at");
            if (at.size() != 3) throw new IllegalArgumentException("vehicle.fun.part.at 은 [오른쪽, 위, 앞] (블록)");
            double scale = p.getDouble("scale", 1);
            if (scale <= 0 || scale > 4) throw new IllegalArgumentException("vehicle.fun.part.scale 은 0 초과 4 이하");
            part = new Part(key, new VehicleSpec.Vec(at.get(0), at.get(1), at.get(2)), scale);
        }
        // what cannot go together: one way of moving up and down at a time, a boat's own ways on water
        int vertical = (hop != null ? 1 : 0) + (ollie != null ? 1 : 0) + (hover != null ? 1 : 0) + (amphibious != null ? 1 : 0) + (buck != null ? 1 : 0);
        if (vertical > 1) throw new IllegalArgumentException("vehicle.fun: hop · ollie · hover · amphibious · buck 은 하나만");
        if (hover != null && hover.pop() && part == null) throw new IllegalArgumentException("vehicle.fun.hover.pop: 터지는 풍선(part)이 있어야 함");
        if (buck != null && part == null) throw new IllegalArgumentException("vehicle.fun.buck: 날뛰는 몸(part)이 있어야 함");
        return new FunSpec(hop, ollie, hover, toss, surface, buck, amphibious, f.getBoolean("pivot"), tilt, waddle, toot, roll,
                f.getBoolean("look-steer"), trail, part);
    }

    private static String sound(String key, String what) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException(what + " 가 없음");
        String k = key.trim();
        if (NamespacedKey.fromString(k) == null) throw new IllegalArgumentException(what + ": 잘못된 소리 이름 " + k);
        return k;
    }

    /** Space is taken by the behaviour (hop, ollie, rise, quack): it is no horn on this vehicle. */
    public boolean usesSpace() {
        return hop != null || ollie != null || hover != null || toot != null;
    }

    /** It moves up and down by itself (in the air between bounces, hovering): the physics runs every tick. */
    public boolean airborne() {
        return hop != null || hover != null;
    }
}
