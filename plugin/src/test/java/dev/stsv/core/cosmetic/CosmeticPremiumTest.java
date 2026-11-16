package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.CustomItem.CosmeticSlot;
import org.bukkit.NamespacedKey;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Donation-grade cosmetics: footprint timing and placement, the orbit, set awakening and the owners' upgrade. */
class CosmeticPremiumTest {

    @Test
    void aPrintPopsUpStaysAndSinksAway() {
        int life = 36;
        List<CosmeticFx.Stage> seen = new ArrayList<>();
        for (int age = 0; age <= life; age++) seen.add(CosmeticFx.stage(age, life));
        assertEquals(CosmeticFx.Stage.BORN, seen.get(0));
        assertEquals(CosmeticFx.Stage.BORN, seen.get(1), "the clients get it tiny first");
        assertEquals(CosmeticFx.Stage.GROW, seen.get(2));
        assertEquals(CosmeticFx.Stage.FADE, seen.get(life - CosmeticFx.SINK));
        assertEquals(CosmeticFx.Stage.GONE, seen.get(life));
        assertEquals(1, seen.stream().filter(s -> s == CosmeticFx.Stage.GROW).count());
        assertEquals(1, seen.stream().filter(s -> s == CosmeticFx.Stage.FADE).count());
        // a very short life still grows before it fades
        assertEquals(CosmeticFx.Stage.FADE, CosmeticFx.stage(3, 10));
        assertEquals(CosmeticFx.Stage.GROW, CosmeticFx.stage(2, 10));
    }

    @Test
    void footprintsFollowTheWalkLeftAndRight() {
        assertNull(CosmeticFx.nextPrint(0, 0, 0, 0.5, 0.7, false, true), "not a step yet");
        // walking south (+z): yaw 0; the left of someone facing south is east (+x)
        CosmeticFx.Print a = CosmeticFx.nextPrint(0, 0, 0, 0.8, 0.7, false, true);
        assertNotNull(a);
        assertEquals(0f, a.yaw(), 0.01f);
        assertTrue(a.left());
        assertEquals(CosmeticFx.FOOT, a.x(), 1e-9);
        assertEquals(0.8, a.z(), 1e-9);
        CosmeticFx.Print b = CosmeticFx.nextPrint(0, 0.8, 0, 1.6, 0.7, a.left(), true);
        assertFalse(b.left(), "then the right foot");
        assertEquals(-CosmeticFx.FOOT, b.x(), 1e-9);
        // walking east (+x): yaw -90 (Minecraft: 90 = west)
        CosmeticFx.Print c = CosmeticFx.nextPrint(0, 0, 1, 0, 0.7, false, true);
        assertEquals(-90f, c.yaw(), 0.01f);
        // not alternating: always the same foot
        assertEquals(CosmeticFx.nextPrint(0, 0, 0, 1, 0.7, true, false).left(), true);
    }

    @Test
    void anOrbitGoesRoundInQuartersAndBobs() {
        CustomItem.Cosmetic c = new CustomItem.Cosmetic(CosmeticSlot.AURA, CustomItem.Rarity.LEGENDARY, null, new Vector(), 1f, false,
                null, true, "s", true, new CustomItem.Motion(CustomItem.MotionKind.ORBIT, 0.08f, 80),
                new CustomItem.Premium(new NamespacedKey("stsv", "model/x"), null, null, null));
        assertEquals(4, CosmeticAttachments.motionSteps(c.motion()));
        Vector3f anchor = CosmeticAttachments.DEFAULT_ANCHORS.get(CosmeticSlot.AURA);
        assertNotNull(anchor, "a model aura has an anchor");
        float y0 = CosmeticAttachments.transformation(anchor, c, false, 1f, 0, 0).getTranslation().y;
        for (int phase = 1; phase <= 4; phase++) {
            Transformation t = CosmeticAttachments.transformation(anchor, c, false, 1f, 0, phase);
            double angle = 2 * Math.acos(Math.min(1, Math.abs(t.getLeftRotation().w)));
            assertEquals(phase % 4 * Math.PI / 2 > Math.PI ? 2 * Math.PI - phase % 4 * Math.PI / 2 : phase % 4 * Math.PI / 2, angle, 1e-4, "phase " + phase);
            assertEquals(y0 + (phase % 2 == 1 ? 0.08f : -0.08f), t.getTranslation().y, 1e-5);
        }
        assertTrue(CosmeticAttachments.onBody(CosmeticSlot.AURA), "it turns with the body");
    }

    private static CustomItem piece(String id, CosmeticSlot slot, String set) {
        CustomItem.Cosmetic c = new CustomItem.Cosmetic(slot, CustomItem.Rarity.LEGENDARY, null, new Vector(), 1f, false, null, true, set, true, null);
        return new CustomItem(new NamespacedKey("stsv", id), null, org.bukkit.Material.PAPER, id, List.of(), null, null, null, false,
                null, null, null, null, null, c, null, null);
    }

    @Test
    void aWholeSetAwakensItsPieces() {
        CustomItem a = piece("a", CosmeticSlot.ABOVE, "dragon"), b = piece("b", CosmeticSlot.BACK, "dragon"), x = piece("x", CosmeticSlot.TAIL, null);
        CosmeticSets sets = new CosmeticSets(Map.of("dragon", new CosmeticSets.Set("dragon", "용", List.of("stsv:a", "stsv:b"), null)));
        assertTrue(sets.awake(List.of(a, x)).isEmpty(), "half a set: nothing awakens");
        assertEquals(Set.of("stsv:a", "stsv:b"), sets.awake(List.of(a, b, x)));
        assertFalse(sets.awake(List.of(a, b, x)).contains("stsv:x"));
    }

    @Test
    void ownersOfTheOldSetGetTheNewPiecesOnce() {
        CosmeticSets.Upgrade u = new CosmeticSets.Upgrade("azure_v2", List.of("stsv:a", "stsv:b"), List.of("stsv:c", "stsv:d"));
        assertEquals(List.of("stsv:c", "stsv:d"), u.grants(Set.of("stsv:a", "stsv:b"), Set.of()));
        assertEquals(List.of("stsv:d"), u.grants(Set.of("stsv:a", "stsv:b", "stsv:c"), Set.of()), "what they have already is not given again");
        assertEquals(List.of(), u.grants(Set.of("stsv:a"), Set.of()), "single pieces keep what they bought");
        assertEquals(List.of(), u.grants(Set.of("stsv:a", "stsv:b"), Set.of("azure_v2")), "never twice");
    }
}
