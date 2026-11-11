package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;

/**
 * The walking speed of someone wearing a 지게: one movement-speed modifier ({@link #KEY}), transient (never saved with the
 * player, so a crash or a missed event cannot leave anyone slow for good; the module also takes it off on join), on
 * while a 지게 is worn and off as soon as it is not. Pure apart from the attribute (unit-tested with a stand-in).
 */
public final class JigeSpeed {

    /** On the wearer's movement speed. */
    public static final NamespacedKey KEY = NamespacedKey.fromString("stsv:jige");

    private JigeSpeed() {}

    /** Puts it on (or changes it): x (1 - slowdown) of the total. true when something changed. */
    public static boolean apply(AttributeInstance speed, double slowdown) {
        if (speed == null) return false;
        double want = -Math.clamp(slowdown, 0, 0.9);
        AttributeModifier had = speed.getModifier(KEY);
        if (had != null && Math.abs(had.getAmount() - want) < 1e-9) return false;
        if (had != null) speed.removeModifier(KEY);
        speed.addTransientModifier(new AttributeModifier(KEY, want, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        return true;
    }

    /** Takes it off; true when it was on. */
    public static boolean remove(AttributeInstance speed) {
        if (speed == null || speed.getModifier(KEY) == null) return false;
        speed.removeModifier(KEY);
        return true;
    }
}
