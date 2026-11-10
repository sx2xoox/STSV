package dev.stsv.core.vehicle;

import net.kyori.adventure.key.Key;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The puller's speed (PullSpeed): 30 % slower while pulling, sprinting gains nothing, all back on letting go. */
class PullSpeedTest {

    /** A player's movement speed as the game computes it (base 0.1, modifiers multiplying the total), minus a server. */
    static final class Speed implements AttributeInstance {
        final Map<Key, AttributeModifier> mods = new LinkedHashMap<>();
        final Set<Key> saved = new HashSet<>();
        double base = 0.1;

        @Override
        public Attribute getAttribute() {
            return null;
        }

        @Override
        public double getBaseValue() {
            return base;
        }

        @Override
        public void setBaseValue(double v) {
            base = v;
        }

        @Override
        public Collection<AttributeModifier> getModifiers() {
            return List.copyOf(mods.values());
        }

        @Override
        public AttributeModifier getModifier(Key key) {
            return mods.get(key);
        }

        @Override
        public void removeModifier(Key key) {
            mods.remove(key);
            saved.remove(key);
        }

        @Override
        public AttributeModifier getModifier(UUID uuid) {
            return null;
        }

        @Override
        public void removeModifier(UUID uuid) {
        }

        @Override
        public void addModifier(AttributeModifier m) {
            if (mods.containsKey(m.getKey())) throw new IllegalArgumentException("already applied: " + m.getKey());
            mods.put(m.getKey(), m);
            saved.add(m.getKey());
        }

        @Override
        public void addTransientModifier(AttributeModifier m) {
            if (mods.containsKey(m.getKey())) throw new IllegalArgumentException("already applied: " + m.getKey());
            mods.put(m.getKey(), m);
        }

        @Override
        public void removeModifier(AttributeModifier m) {
            removeModifier(m.getKey());
        }

        @Override
        public double getValue() {
            double v = base;
            for (AttributeModifier m : mods.values()) {
                if (m.getOperation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1) v *= 1 + m.getAmount();
                else throw new IllegalStateException("only total multipliers here");
            }
            return v;
        }

        @Override
        public double getDefaultValue() {
            return 0.1;
        }
    }

    @Test
    void walkingIsThirtyPercentSlowerAndSprintingAddsNothing() {
        assertEquals(-0.3, PullSpeed.amount(0.3, false), 1e-12);
        assertEquals(0.7, PullSpeed.factor(PullSpeed.amount(0.3, false), false), 1e-12, "walking: 70 %");
        assertEquals(0.7, PullSpeed.factor(PullSpeed.amount(0.3, true), true), 1e-12, "sprinting (+30 % on the client): still 70 %");
        assertEquals(1.0, PullSpeed.factor(PullSpeed.amount(0, false), false), 1e-12, "slowdown 0: walking as usual");
        assertTrue(PullSpeed.factor(PullSpeed.amount(0, true), true) < 1.0 + 1e-12, "and even then no sprinting faster");
        assertEquals(0.05, PullSpeed.factor(PullSpeed.amount(2, false), false), 1e-12, "never stopped dead");
    }

    @Test
    void theModifierGoesOnAndComesOff() {
        Speed speed = new Speed();
        assertTrue(PullSpeed.apply(speed, 0.3, false), "taking the handles");
        AttributeModifier m = speed.getModifier(PullSpeed.KEY);
        assertNotNull(m);
        assertEquals(AttributeModifier.Operation.MULTIPLY_SCALAR_1, m.getOperation());
        assertTrue(speed.saved.isEmpty(), "transient: never saved with the player (a crash leaves nobody slow)");
        assertEquals(0.07, speed.getValue(), 1e-12, "30 % slower");
        assertFalse(PullSpeed.apply(speed, 0.3, false), "again: nothing changes, no second modifier");
        assertEquals(1, speed.getModifiers().size());

        assertTrue(PullSpeed.apply(speed, 0.3, true), "they sprint: the modifier deepens");
        assertEquals(1, speed.getModifiers().size(), "still one");
        assertEquals(0.07, speed.getValue() * (1 + PullSpeed.SPRINT), 1e-12, "with the client's sprint bonus: still 70 %");
        assertTrue(PullSpeed.apply(speed, 0.3, false), "they stop sprinting");
        assertEquals(0.07, speed.getValue(), 1e-12);

        assertTrue(PullSpeed.remove(speed), "letting go");
        assertNull(speed.getModifier(PullSpeed.KEY));
        assertEquals(0.1, speed.getValue(), 1e-12, "full speed again");
        assertFalse(PullSpeed.remove(speed), "nothing left to take off");
        assertFalse(PullSpeed.apply(null, 0.3, false), "no attribute (not a player): nothing");
        assertFalse(PullSpeed.remove(null));
    }

    @Test
    void anotherPluginsModifierIsLeftAlone() {
        Speed speed = new Speed();
        AttributeModifier other = new AttributeModifier(org.bukkit.NamespacedKey.fromString("other:boots"), 0.2,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1);
        speed.addModifier(other);
        PullSpeed.apply(speed, 0.3, false);
        assertEquals(0.1 * 1.2 * 0.7, speed.getValue(), 1e-12);
        PullSpeed.remove(speed);
        assertEquals(other, speed.getModifier(other.getKey()), "theirs stays");
        assertEquals(0.12, speed.getValue(), 1e-12);
    }
}
