package dev.stsv.rpg.balance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageTest {

    final Balance b = BalanceTest.shipped();

    Damage.Hit hit(double atk, Element e) {
        return new Damage.Hit(atk, 1, 0.2, 1.5, 0, 0, e, 10, false);
    }

    @Test
    void plainHitAppliesOnlyTheDefense() {
        double def = b.defenseFor(0.2, 10);
        var r = Damage.compute(b, hit(100, Element.NONE), new Damage.Target(def, Element.NONE, 0), 0.99, 0, 1);
        assertEquals(80, r.amount(), 1e-9);
        assertFalse(r.crit());
        assertEquals(80, r.rounded());
    }

    @Test
    void critMultipliesAndIsCapped() {
        var t = new Damage.Target(0, Element.NONE, 0);
        assertTrue(Damage.compute(b, hit(100, Element.NONE), t, 0.1, 0, 1).crit());
        assertEquals(150, Damage.compute(b, hit(100, Element.NONE), t, 0.1, 0, 1).amount(), 1e-9);
        // chance above the 60% cap: a 0.7 roll never crits even with 100% chance
        var always = new Damage.Hit(100, 1, 1.0, 9.0, 0, 0, Element.NONE, 10, false);
        assertFalse(Damage.compute(b, always, t, 0.7, 0, 1).crit());
        assertEquals(250, Damage.compute(b, always, t, 0.1, 0, 1).amount(), 1e-9, "crit damage capped at 250%");
        var weak = new Damage.Hit(100, 1, 0, 1.5, 0, 0, Element.NONE, 10, true);
        assertTrue(Damage.compute(b, weak, t, 0.99, 0, 1).crit(), "weak points always crit");
    }

    @Test
    void elementsFollowTheTriangleAndLightDark() {
        var none = new Damage.Target(0, Element.NONE, 0);
        var grass = new Damage.Target(0, Element.GRASS, 0);
        assertEquals(125, Damage.compute(b, hit(100, Element.FIRE), grass, 1, 0, 1).amount(), 1e-9);
        assertEquals(80, Damage.compute(b, hit(100, Element.WATER), grass, 1, 0, 1).amount(), 1e-9);
        assertEquals(100, Damage.compute(b, hit(100, Element.LIGHT), grass, 1, 0, 1).amount(), 1e-9);
        assertEquals(125, Damage.compute(b, hit(100, Element.DARK), new Damage.Target(0, Element.LIGHT, 0), 1, 0, 1).amount(), 1e-9);
        assertEquals(125, Damage.compute(b, hit(100, Element.LIGHT), new Damage.Target(0, Element.DARK, 0), 1, 0, 1).amount(), 1e-9);
        assertEquals(100, Damage.compute(b, hit(100, Element.FIRE), none, 1, 0, 1).amount(), 1e-9, "a neutral target is neither");
        assertEquals(100, Damage.compute(b, hit(100, Element.NONE), grass, 1, 0, 1).amount(), 1e-9, "no element, no edge either way");
        assertTrue(Damage.compute(b, hit(100, Element.FIRE), grass, 1, 0, 1).advantage());
    }

    @Test
    void elementBonusAndResist() {
        var h = new Damage.Hit(100, 1, 0, 1.5, 0, 0.2, Element.FIRE, 10, false);
        assertEquals(120, Damage.compute(b, h, new Damage.Target(0, Element.NONE, 0), 1, 0, 1).amount(), 1e-9);
        assertEquals(60, Damage.compute(b, h, new Damage.Target(0, Element.NONE, 0.5), 1, 0, 1).amount(), 1e-9);
        assertEquals(60, Damage.compute(b, h, new Damage.Target(0, Element.NONE, 0.9), 1, 0, 1).amount(), 1e-9, "resist capped at 50%");
        var plain = new Damage.Hit(100, 1, 0, 1.5, 0, 0.2, Element.NONE, 10, false);
        assertEquals(100, Damage.compute(b, plain, new Damage.Target(0, Element.NONE, 0.5), 1, 0, 1).amount(), 1e-9,
                "element bonus and resist only touch elemental hits");
    }

    @Test
    void skillMultiplierSkillDamageVarianceAndExtra() {
        var h = new Damage.Hit(100, 2.0, 0, 1.5, 0.25, 0, Element.NONE, 10, false);
        var t = new Damage.Target(0, Element.NONE, 0);
        assertEquals(250, Damage.compute(b, h, t, 1, 0, 1).amount(), 1e-9);
        assertEquals(262.5, Damage.compute(b, h, t, 1, 1, 1).amount(), 1e-9, "+5% variance");
        assertEquals(237.5, Damage.compute(b, h, t, 1, -1, 1).amount(), 1e-9, "-5% variance");
        assertEquals(325, Damage.compute(b, h, t, 1, 0, 1.3).amount(), 1e-9, "break state x1.3");
    }

    @Test
    void neverNegative() {
        var r = Damage.compute(b, hit(0, Element.NONE), new Damage.Target(1e9, Element.NONE, 0), 1, -1, 1);
        assertEquals(0, r.amount());
        assertEquals(1, r.rounded(), "a landed hit always shows at least 1");
    }
}
