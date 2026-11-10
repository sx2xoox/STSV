package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;

/**
 * The puller's walking speed while they hold a cart's handles: one movement-speed modifier ({@link #KEY}), transient
 * (never saved with the player, so a crash cannot leave anyone slow), taken off whenever they let go.
 *
 * <p>Walking: x (1 - slowdown). Sprinting: the game adds its own +30 % on the client, which a server cannot switch off,
 * so while they sprint the modifier is deeper by that much and sprinting gains nothing. Pure apart from the attribute
 * (unit-tested with a stand-in).
 */
public final class PullSpeed {

    /** On the player's movement speed while pulling. */
    public static final NamespacedKey KEY = NamespacedKey.fromString("stsv:vehicle_pull");
    /** The game's sprint bonus (multiplies the total). */
    public static final double SPRINT = 0.3;

    private PullSpeed() {}

    /** The modifier's amount (multiplying the total) for this slowdown, sprinting or not. */
    public static double amount(double slowdown, boolean sprinting) {
        double keep = 1 - Math.clamp(slowdown, 0, 0.95);
        return sprinting ? keep / (1 + SPRINT) - 1 : keep - 1;
    }

    /** The speed factor the puller ends up with (their own sprint bonus included). */
    public static double factor(double amount, boolean sprinting) {
        return (1 + amount) * (sprinting ? 1 + SPRINT : 1);
    }

    /** Puts the modifier on (or changes it); true when something changed. */
    public static boolean apply(AttributeInstance speed, double slowdown, boolean sprinting) {
        if (speed == null) return false;
        double want = amount(slowdown, sprinting);
        AttributeModifier had = speed.getModifier(KEY);
        if (had != null && Math.abs(had.getAmount() - want) < 1e-9) return false;
        if (had != null) speed.removeModifier(KEY);
        speed.addTransientModifier(new AttributeModifier(KEY, want, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        return true;
    }

    /** Takes the modifier off; true when it was on. */
    public static boolean remove(AttributeInstance speed) {
        if (speed == null || speed.getModifier(KEY) == null) return false;
        speed.removeModifier(KEY);
        return true;
    }
}
