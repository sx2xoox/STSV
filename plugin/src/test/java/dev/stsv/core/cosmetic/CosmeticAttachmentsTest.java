package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.CustomItem.CosmeticSlot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the worn models sit: the anchor frame, offsets, sneaking (turning about the neck), slot names. */
class CosmeticAttachmentsTest {

    private static final float EPS = 1e-4f;

    private static void near(Vector3f want, Vector3f got) {
        assertTrue(want.distance(got) < EPS, "want " + want + " got " + got);
    }

    @Test
    void standingTheModelSitsOnTheAnchor() {
        Vector3f back = CosmeticAttachments.DEFAULT_ANCHORS.get(CosmeticSlot.BACK);
        near(back, CosmeticAttachments.translation(back, new Vector(), false, 1f, 0));
        // offset in pixels: x right (= -x left), y up, z back (= -z front)
        near(new Vector3f(back).add(-1 / 16f, 2 / 16f, -3 / 16f), CosmeticAttachments.translation(back, new Vector(1, 2, 3), false, 1f, 0));
        // a scaled player: the whole frame scales (the client scales the passenger point the same way)
        near(new Vector3f(back).mul(2), CosmeticAttachments.translation(back, new Vector(), false, 2f, 0));
        near(new Vector3f(back).add(0, 0.05f, 0), CosmeticAttachments.translation(back, new Vector(), false, 1f, 0.05f));
    }

    /**
     * Sneaking: the display turns by the body's lean about its own origin (the hitbox top); the model must
     * end up where the body point is when the body turns about the neck instead.
     */
    @Test
    void sneakingTurnsAboutTheNeck() {
        double a = Math.toRadians(CosmeticAttachments.SNEAK_TILT);
        assertEquals(0.5, a, 1e-6, "HumanoidModel crouching lean");
        for (CosmeticSlot slot : List.of(CosmeticSlot.SHOULDER, CosmeticSlot.BACK, CosmeticSlot.TAIL)) {
            Vector3f anchor = CosmeticAttachments.DEFAULT_ANCHORS.get(slot);
            Vector3f model = new Vector3f(0.1f, -0.2f, -0.05f);   // some point of the model, relative to its anchor
            // where it is on the body, from the standing neck
            Vector3f fromNeck = new Vector3f(anchor).add(model).add(0, (float) CosmeticAttachments.NECK_STAND, 0);
            // sneaking body: the neck drops, the body leans forward (positive pitch: front down, bottom back)
            Vector3f want = new Vector3f(fromNeck).rotateX((float) a).add(0, (float) -CosmeticAttachments.NECK_SNEAK, 0);
            // the display: origin at the hitbox top, frame pitched by the lean, then translation + model
            Vector3f t = CosmeticAttachments.translation(anchor, new Vector(), true, 1f, 0);
            Vector3f got = new Vector3f(t).add(model).rotateX((float) a);
            near(want, got);
        }
    }

    /** The body turn the clients draw (LivingEntity.tick + tickHeadTurn), followed tick by tick. */
    @Test
    void bodyTurnsLikeOnTheClients() {
        // standing still, head turned 30 degrees: the body stays (within 50)
        assertEquals(0f, CosmeticAttachments.nextBody(0f, 0, 0, 30f, false, false), 1e-4);
        // head turned 80: the body is pulled to 50 behind the head
        assertEquals(30f, CosmeticAttachments.nextBody(0f, 0, 0, 80f, false, false), 1e-4);
        // blocking with a shield: 15
        assertEquals(65f, CosmeticAttachments.nextBody(0f, 0, 0, 80f, false, true), 1e-4);
        // walking south (+z) with the head south too: yaw 0 is south, the body turns 30% of the way per tick
        float body = 40f;
        for (int i = 0; i < 40; i++) body = CosmeticAttachments.nextBody(body, 0, 0.2, 0f, false, false);
        assertEquals(0f, body, 0.01);
        // strafing east (-90 = east) while looking south: the body turns towards the walk, up to 50 from the head
        body = 0f;
        for (int i = 0; i < 40; i++) body = CosmeticAttachments.nextBody(body, 0.2, 0, 0f, false, false);
        assertEquals(-50f, body, 0.01);
        // walking backwards (north) while looking south: the body keeps facing south
        body = 0f;
        for (int i = 0; i < 40; i++) body = CosmeticAttachments.nextBody(body, 0, -0.2, 0f, false, false);
        assertEquals(0f, body, 0.01);
        // swinging: towards the head even while walking elsewhere
        body = -50f;
        for (int i = 0; i < 40; i++) body = CosmeticAttachments.nextBody(body, 0.2, 0, 0f, true, false);
        assertEquals(0f, body, 0.01);
    }

    @Test
    void neckHeights() {
        // standing: 1.8 hitbox, neck at 24 px x 15/16; sneaking: 1.5 hitbox, neck 3.2 px (x 15/16) + 2 px lower
        assertEquals(1.8 - 1.40625, CosmeticAttachments.NECK_STAND, 1e-9);
        assertEquals(1.5 - 1.09375, CosmeticAttachments.NECK_SNEAK, 1e-9);
    }

    @Test
    void onlyBodySlotsLeanWithTheBody() {
        assertTrue(CosmeticAttachments.onBody(CosmeticSlot.BACK));
        assertTrue(CosmeticAttachments.onBody(CosmeticSlot.SHOULDER));
        assertTrue(CosmeticAttachments.onBody(CosmeticSlot.TAIL));
        assertFalse(CosmeticAttachments.onBody(CosmeticSlot.ABOVE));
        assertFalse(CosmeticAttachments.onBody(CosmeticSlot.HEAD));
    }

    @Test
    void anchorsFromTheConfigKeepDefaultsForBrokenEntries() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("back", List.of(0.0, -0.5, -0.2));
        y.set("tail", List.of(1.0));
        List<String> problems = new ArrayList<>();
        Map<CosmeticSlot, Vector3f> a = CosmeticAttachments.anchorsFrom(y, problems);
        near(new Vector3f(0, -0.5f, -0.2f), a.get(CosmeticSlot.BACK));
        near(CosmeticAttachments.DEFAULT_ANCHORS.get(CosmeticSlot.TAIL), a.get(CosmeticSlot.TAIL));
        near(CosmeticAttachments.DEFAULT_ANCHORS.get(CosmeticSlot.SHOULDER), a.get(CosmeticSlot.SHOULDER));
        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    void slotNamesInEnglishOrKorean() {
        assertEquals(CosmeticSlot.ABOVE, CosmeticSlot.parse("above"));
        assertEquals(CosmeticSlot.ABOVE, CosmeticSlot.parse("머리 위"));
        assertEquals(CosmeticSlot.ABOVE, CosmeticSlot.parse("머리위"));
        assertEquals(CosmeticSlot.BACK, CosmeticSlot.parse(" 등 "));
        assertEquals(CosmeticSlot.TAIL, CosmeticSlot.parse("TAIL"));
        assertNull(CosmeticSlot.parse("chest"));
        assertEquals(1f, new CustomItem.Cosmetic(CosmeticSlot.HEAD, CustomItem.Rarity.COMMON, null).scale());
    }
}
