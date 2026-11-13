package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * What makes a sports car feel fast (vehicle_feel.yml, {@code sport:} under a vehicle): the driver's view widening
 * with speed, flames popping from the exhaust when they lift off at speed, tyre smoke when they floor it from a stop.
 * Pure apart from the attribute (unit-tested with a stand-in).
 *
 * <p><b>The view.</b> The game widens a player's field of view with their movement speed, riding or not
 * (fov x (speed / walking speed + 1) / 2), so a movement-speed modifier on the driver does it: one modifier
 * ({@link #KEY}), transient (never saved with the player), changed in a few steps as the car speeds up and taken off
 * when they get out. A rider cannot walk, so it changes nothing else. Players who set the game's "FOV effects" low see
 * less of it, as they chose.
 */
public final class SportFeel {

    /** On the driver's movement speed while they drive a sports car. */
    public static final NamespacedKey KEY = NamespacedKey.fromString("stsv:vehicle_sport_view");
    /** The view changes in this many steps between standing and the top speed (each one is an attribute update). */
    public static final int STEPS = 12;
    /** Ticks the throttle must have been down before lifting off pops the exhaust, and between two pops. */
    public static final int HELD = 16, COOLDOWN = 30;
    /** Ticks a pop's flames last, and the smoke of a launch. */
    public static final int FLAME_TICKS = 7, LAUNCH_TICKS = 14;

    private SportFeel() {}

    /**
     * @param fov           how much wider the driver sees at the top speed (0.3 = 30 %; 0 = no change)
     * @param exhaust       where the exhaust tips are (flames, smoke); empty = none
     * @param backfire      the pop when the driver lifts off at speed, or null
     * @param backfireSpeed only above this speed (blocks/s)
     * @param launchSmoke   smoke from the driven wheels when floored from a stop
     * @param skid          the tyres' squeal while it drifts (played again every {@link #SKID_TICKS}), or null
     */
    public record Spec(double fov, List<VehicleSpec.Vec> exhaust, VehicleFeel.Sound backfire, double backfireSpeed, boolean launchSmoke,
                       VehicleFeel.Sound skid) {}

    /** Ticks from one squeal to the next (the clip is a little longer: they fade into each other), and the slip (degrees) it starts at. */
    public static final int SKID_TICKS = 10;
    public static final double SKID_SLIP = 6;

    static Spec parse(ConfigurationSection s) {
        double fov = s.getDouble("fov", 0);
        if (fov < 0 || fov > 0.5) throw new IllegalArgumentException("sport.fov 는 0 ~ 0.5 (최고 속도에서 시야가 넓어지는 비율)");
        List<VehicleSpec.Vec> exhaust = new ArrayList<>();
        for (Object o : s.getList("exhaust", List.of())) {
            if (!(o instanceof List<?> l) || l.size() != 3 || !l.stream().allMatch(n -> n instanceof Number))
                throw new IllegalArgumentException("sport.exhaust 는 [오른쪽, 위, 앞] 의 목록");
            exhaust.add(new VehicleSpec.Vec(((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(), ((Number) l.get(2)).doubleValue()));
        }
        VehicleFeel.Sound pop = null;
        double popSpeed = 0;
        if (s.isConfigurationSection("backfire")) {
            ConfigurationSection b = s.getConfigurationSection("backfire");
            pop = VehicleFeel.sound(b, "sport.backfire");
            popSpeed = b.getDouble("min-speed", 5);
            if (popSpeed < 0) throw new IllegalArgumentException("sport.backfire.min-speed 는 0 이상 (블록/초)");
            if (exhaust.isEmpty()) throw new IllegalArgumentException("sport.backfire 에는 sport.exhaust (배기구 위치) 가 있어야 함");
        }
        VehicleFeel.Sound skid = s.isConfigurationSection("skid") ? VehicleFeel.sound(s.getConfigurationSection("skid"), "sport.skid") : null;
        return new Spec(fov, List.copyOf(exhaust), pop, popSpeed, s.getBoolean("launch-smoke"), skid);
    }

    // ------------------------------------------------------------------------------------------
    // The view
    // ------------------------------------------------------------------------------------------

    /**
     * The modifier's amount (multiplying the total movement speed) at this speed: 0 standing, 2 x fov at the top speed
     * (the game halves it: see the class), rising faster towards the top, in {@link #STEPS} steps.
     */
    public static double amount(double speed, double top, double fov) {
        if (fov <= 0 || top <= 0) return 0;
        double share = Math.clamp(Math.abs(speed) / top, 0, 1);
        double step = Math.round(Math.pow(share, 1.5) * STEPS) / (double) STEPS;
        return 2 * fov * step;
    }

    /** How much wider the game shows the view with this amount on (1 = as usual). */
    public static double view(double amount) {
        return (1 + amount + 1) / 2;
    }

    /** Puts the modifier on (or changes it, or takes it off at 0); true when something changed. */
    public static boolean apply(AttributeInstance speed, double amount) {
        if (speed == null) return false;
        AttributeModifier had = speed.getModifier(KEY);
        if (amount <= 0) return remove(speed);
        if (had != null && Math.abs(had.getAmount() - amount) < 1e-9) return false;
        if (had != null) speed.removeModifier(KEY);
        speed.addTransientModifier(new AttributeModifier(KEY, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        return true;
    }

    /** Takes the modifier off; true when it was on. */
    public static boolean remove(AttributeInstance speed) {
        if (speed == null || speed.getModifier(KEY) == null) return false;
        speed.removeModifier(KEY);
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // The throttle: backfire and launch
    // ------------------------------------------------------------------------------------------

    /** What the exhaust and the tyres do, from the driver's throttle tick by tick. */
    public static final class Throttle {
        int held, cooldown;
        /** ticks of flames left, ticks of launch smoke left */
        public int flames, smoke;

        /**
         * One tick. forward: the throttle is down (W, going forward). Returns true when the exhaust pops now: the
         * throttle was down for {@link #HELD} ticks and was let go above minSpeed (not again for {@link #COOLDOWN}).
         * A launch (floored below 1.5 blocks/s after standing) starts {@link #LAUNCH_TICKS} of smoke.
         */
        public boolean step(boolean forward, double speed, double minSpeed, boolean pops, boolean launches) {
            if (cooldown > 0) cooldown--;
            if (flames > 0) flames--;
            if (smoke > 0) smoke--;
            boolean pop = false;
            if (forward && speed >= 0) {
                if (held == 0 && launches && speed < 1.5) smoke = LAUNCH_TICKS;
                held++;
            } else {
                if (pops && held >= HELD && speed >= minSpeed && cooldown == 0) {
                    pop = true;
                    flames = FLAME_TICKS;
                    cooldown = COOLDOWN;
                }
                held = 0;
            }
            return pop;
        }

        public void reset() {
            held = cooldown = flames = smoke = 0;
        }
    }
}
