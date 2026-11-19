package dev.stsv.economy.accessory;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** accessories.yml as the plan says (docs/plans/장신구_시스템_기획안.md): ranges from the targets, the enhance table, levels, the draw. */
class AccessorySpecTest {

    static AccessorySpec spec() {
        List<String> problems = new ArrayList<>();
        AccessorySpec s = AccessorySpec.parse(YamlConfiguration.loadConfiguration(java.nio.file.Path.of("content/economy/accessories.yml").toFile()), problems);
        assertEquals(List.of(), problems);
        return s;
    }

    @Test
    void theTargetsAreReachedOnlyByAPerfectTreasureAtPlusTen() {
        AccessorySpec s = spec();
        assertEquals(4, s.grades().size());
        assertEquals(29, s.effects().size());
        assertEquals(1.6, s.multiplier(10), 1e-9, "the enhance steps add up to x1.6");
        assertEquals(1.0, s.multiplier(0), 1e-9);
        assertEquals(1.075, s.multiplier(3), 1e-9);
        AccessorySpec.Grade treasure = s.grades().get("treasure");
        for (AccessorySpec.Effect e : s.effects().values()) {
            if (!e.ranges().isEmpty()) continue;
            double top = s.amount(e, treasure, 1, 10);
            double want = e.cap() > 0 ? Math.min(e.max(), e.cap()) : e.max();
            assertEquals(want, top, 1e-9, e.id() + ": a perfect treasure at +10 is the target");
        }
        AccessorySpec.Effect mining = s.effects().get("mining_speed");
        assertEquals(0.7125, s.amount(mining, s.grades().get("masterwork"), 0, 0), 1e-9, "명품 채굴 속도 +71~122%");
        assertEquals(1.21875, s.amount(mining, s.grades().get("masterwork"), 1, 0), 1e-9);
        // the grades meet: a fine one well enhanced reaches the masterwork's middle, no more
        assertTrue(s.amount(mining, s.grades().get("common"), 1, 10) < s.amount(mining, s.grades().get("masterwork"), 0, 0) * 1.0 + 1e-9);
        // 장인의 배움: 보통 averages about 10%, a perfect 국보 at +10 +200%
        AccessorySpec.Effect learn = s.effects().get("craft_learning");
        assertEquals(0.10, s.amount(learn, s.grades().get("common"), 0.5, 0), 1e-9);
        assertEquals(2.0, s.amount(learn, treasure, 1, 10), 1e-9);
        // the bag: a common's least is one big chest, a perfect treasure at +10 nine
        AccessorySpec.Effect bag = s.effects().get("bag");
        assertEquals(6, s.amount(bag, s.grades().get("common"), 0, 0), 1e-9);
        assertEquals(54, s.amount(bag, treasure, 1, 10), 1e-9);
        // 원격 기계 is not enhanced; a treasure's is anywhere (-1)
        assertEquals(-1, s.amount(s.effects().get("remote_machine"), treasure, 0.5, 10), 1e-9);
    }

    @Test
    void enhanceCostsScaleByGradeAndLevelsUnlockSteps() {
        AccessorySpec s = spec();
        assertEquals(10, s.steps().size());
        assertEquals(17000, s.enhanceCost(s.grades().get("masterwork"), 1));
        assertEquals(1350000L * 3, s.enhanceCost(s.grades().get("treasure"), 10));
        assertEquals(Math.round(1350000 * 0.4), s.enhanceCost(s.grades().get("fine"), 10));
        assertEquals(81, s.enhanceStones(s.grades().get("treasure"), 10));
        assertEquals(6, s.dropFrom());
        assertEquals(3, s.enhanceCap(1));
        assertEquals(6, s.enhanceCap(10));
        assertEquals(10, s.enhanceCap(29));
        assertEquals(1, s.levelFor(0));
        assertEquals(10, s.levelFor(s.xpFor(10)));
        assertEquals(30, s.levelFor(1e12));
        assertEquals(0, s.subsOpen(s.grades().get("common"), 19));
        assertEquals(1, s.subsOpen(s.grades().get("common"), 20));
        assertEquals(3, s.subsOpen(s.grades().get("treasure"), 30));
        assertEquals(Math.round(600000 * 1.44), s.rerollCost(s.grades().get("treasure"), 2));
    }

    @Test
    void theDrawFollowsTheTableAndTheAppraiser() {
        AccessorySpec s = spec();
        // u counts from the top grade down: the first 0.4% of the box is 국보
        assertEquals("treasure", s.draw(true, 1, 0.003).id());
        assertEquals("masterwork", s.draw(true, 1, 0.01).id());
        assertEquals("fine", s.draw(true, 1, 0.1).id());
        assertEquals("common", s.draw(true, 1, 0.5).id());
        assertEquals("treasure", s.draw(true, 2, 0.007).id(), "the appraiser's x2 doubles the treasure share");
        assertEquals("common", s.draw(false, 1, 0.99).id());
    }

    @Test
    void anAccessoryKeepsItsData() {
        Accessory a = new Accessory(UUID.randomUUID(), "vein_burst", "treasure", 0.987654);
        a.enhance = 7;
        a.refines = 2;
        a.rerolls = 3;
        a.xp = 12345.5;
        a.subs.add(new Accessory.Sub("move_speed", 0.25));
        a.subs.add(new Accessory.Sub("magnet", 1));
        Accessory b = Accessory.decode(a.encode());
        assertNotNull(b);
        assertEquals(a.serial, b.serial);
        assertEquals("vein_burst", b.effect);
        assertEquals(0.987654, b.roll, 1e-6);
        assertEquals(7, b.enhance);
        assertEquals(2, b.refines);
        assertEquals(3, b.rerolls);
        assertEquals(12345.5, b.xp, 1e-6);
        assertEquals(2, b.subs.size());
        assertEquals("magnet", b.subs.get(1).effect());
        assertNull(Accessory.decode("nope"));
        assertTrue(new Accessory(UUID.randomUUID(), "x", "common", 1).perfect());
        assertEquals("+300%", AccessorySpec.text(spec().effects().get("mining_speed"), 3.0));
        assertEquals("×2", AccessorySpec.text(spec().effects().get("appraiser"), 1.0));
        assertEquals("어디서나", AccessorySpec.text(spec().effects().get("remote_machine"), -1));
        assertTrue(AccessoryModule.isAccessoryKey("economy:accessory"));
        assertTrue(AccessoryModule.isAccessoryKey("economy:acc_ring_treasure"));   // old listings
        assertFalse(AccessoryModule.isAccessoryKey("economy:acc_box"));
    }

    /** /순위 장신구: the grade's weight, times the enhance multiplier, times 0.5 .. 1 by the roll. */
    @Test
    void theRankingScoreGrowsWithGradeEnhanceAndRoll() {
        AccessorySpec s = spec();
        assertEquals(40 * 0.5, s.score("treasure", 0, 0), 1e-9, "an S at the bottom of its range, +0");
        assertEquals(40, s.score("treasure", 1, 0), 1e-9, "a perfect S, +0");
        assertEquals(40 * s.multiplier(10), s.score("treasure", 1, 10), 1e-9, "a perfect S at +10");
        assertTrue(s.score("common", 1, 10) < s.score("masterwork", 0, 0), "a C at its best stays under a plain A");
        assertTrue(s.score("fine", 0.5, 3) > s.score("fine", 0.5, 2), "enhancing raises it");
    }
}
