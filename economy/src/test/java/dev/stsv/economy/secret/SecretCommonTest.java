package dev.stsv.economy.secret;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared parts of the 숨은 비밀: which rumor comes next, when a monument stone is whole, which model it shows, what it
 * says, where the thirteen stand and stray stones.
 */
class SecretCommonTest {

    @Test
    void rumorNeverRepeatsWhileAnotherIsOpen() {
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) assertNotEquals("bus", Rumors.pick(List.of("bus", "doll", "fish"), "bus", r));
    }

    @Test
    void lastOpenSecretStillGetsRumors() {
        assertEquals("doll", Rumors.pick(List.of("doll"), "doll", new Random(2)));
        assertNull(Rumors.pick(List.of(), "doll", new Random(2)));
    }

    @Test
    void stoneIsWholeOnlyWithOneTextAndOneModel() {
        assertTrue(Monument.whole(1, 1, 0));
        assertFalse(Monument.whole(1, 0, 0));   // its model was killed: set again
        assertFalse(Monument.whole(2, 2, 0));   // doubled: set again (the extras go)
        assertFalse(Monument.whole(0, 1, 0));
        assertFalse(Monument.whole(1, 0, 2));   // a two-block stone from before the models: replaced
        assertFalse(Monument.whole(1, 1, 1));   // a model and a leftover block: set again
    }

    @Test
    void solvedStoneShowsItsOwnEmblem() {
        assertEquals("model/secret_stone", Monument.model("bus", false).getKey());
        List<String> ids = List.of("bus", "dongbang", "letter", "designer", "usher", "fish", "lostkey", "doll", "scarecrow",
                "jesa", "garage", "pager", "newcomer");
        assertEquals(ids.size(), Monument.EMBLEMS.size());
        Path models = Path.of(System.getProperty("stsv.content")).resolve("economy").resolve("models");
        for (String id : ids) {
            assertEquals("economy", Monument.model(id, true).getNamespace());
            assertEquals("model/secret_stone_solved_" + id, Monument.model(id, true).getKey());
            assertEquals("model/secret_stone", Monument.model(id, false).getKey());
            assertTrue(Files.isRegularFile(models.resolve("secret_stone_solved_" + id + ".bbmodel")), id + ": its solved stone is in the pack");
        }
        assertEquals(Monument.MODEL_SOLVED, Monument.model("someday", true));   // a secret without an emblem: the star
    }

    @Test
    void thirteenStonesOneForEachSecret() throws Exception {
        assertEquals(13, Monument.SLOTS);
        // the bus secret and every secrets/<id>.yml (common.yml is the shared settings): no blank stone is left
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("stsv.content")).resolve("economy").resolve("secrets"))) {
            long secrets = s.filter(p -> p.toString().endsWith(".yml") && !p.getFileName().toString().equals("common.yml")).count();
            assertEquals(Monument.SLOTS, 1 + secrets);
        }
    }

    @Test
    void stonesStandApartOnTheArcFacingThePlace() {
        int n = Monument.SLOTS;
        for (float yaw : new float[]{0f, 45f, -90f, 180f}) {
            double[] prev = null;
            for (int i = 0; i < n; i++) {
                double[] o = Monument.offset(i, n, yaw);
                assertEquals(Monument.RADIUS, Math.hypot(o[0], o[1]), 1e-9);
                // it looks back at the place: forward = (-sin yaw, cos yaw) points from the stone to the centre
                double fx = -Math.sin(Math.toRadians(o[2])), fz = Math.cos(Math.toRadians(o[2]));
                assertEquals(-o[0] / Monument.RADIUS, fx, 1e-9);
                assertEquals(-o[1] / Monument.RADIUS, fz, 1e-9);
                // a stone's plinth is 1.25 blocks wide: neighbours never touch
                if (prev != null) assertTrue(Math.hypot(o[0] - prev[0], o[1] - prev[1]) >= 1.4, "stones " + (i - 1) + " and " + i + " overlap");
                // within reach of clear() (16) and never a stray (12), its text included
                assertFalse(Monument.stray("world", 0.5, 64, 0.5, "world", 0.5 + o[0], 64 + Monument.TEXT_Y, 0.5 + o[1]));
                prev = o;
            }
        }
        // the middle stone stands straight ahead (yaw 0 looks to +z), the ends to either side of it
        double[] mid = Monument.offset(n / 2, n, 0f), first = Monument.offset(0, n, 0f), last = Monument.offset(n - 1, n, 0f);
        assertEquals(0, mid[0], 1e-9);
        assertEquals(Monument.RADIUS, mid[1], 1e-9);
        assertEquals(first[0], -last[0], 1e-9);
        assertEquals(first[1], last[1], 1e-9);
        assertTrue(first[1] > 0, "the arc is less than a half circle: every stone is in front");
        assertEquals(Monument.RADIUS, Monument.offset(0, 1, 0f)[1], 1e-9);   // one stone alone: straight ahead
    }

    @Test
    void tenStoneMonumentMovesOutToThirteen() {
        // where the ten stones of the old arc (radius 6, 100 degrees) stood, no stone of the new arc stands: each is seen as
        // out of place and set again (build() counts a display only at its stone's own spot)
        for (int i = 0; i < 10; i++) {
            double a = -Math.toRadians(100) / 2 + Math.toRadians(100) * i / 9;
            double ox = -Math.sin(a) * 6, oz = Math.cos(a) * 6;
            for (int j = 0; j < Monument.SLOTS; j++) {
                double[] o = Monument.offset(j, Monument.SLOTS, 0f);
                assertFalse(Monument.here(o[0], o[1], ox, oz), "old stone " + i + " on new stone " + j);
            }
            assertFalse(Monument.stray("world", 0, 64, 0, "world", ox, 64, oz));   // still seen by build(), not left to orphans()
        }
        double[] o = Monument.offset(3, Monument.SLOTS, 45f);
        assertTrue(Monument.here(o[0], o[1], o[0], o[1]));                       // its model
        assertTrue(Monument.here(o[0] + 100.5, o[1] - 7.5, o[0] + 100.5, o[1] - 7.5));   // its text, straight above
        assertFalse(Monument.here(o[0], o[1], o[0] + 0.5, o[1]));
        // the old blank stone belongs to no stone now: its tag's id is not a secret's
        assertEquals("blank1", Monument.idOf(Monument.key("blank1", "", Monument.MODEL)));
        assertEquals("newcomer", Monument.idOf(Monument.key("newcomer", "", Monument.MODEL)));
        assertEquals("old", Monument.idOf("old"));
    }

    @Test
    void hiddenStoneSaysNothingUntilSolved() {
        UUID who = UUID.randomUUID();
        Monument.Stone open = new Monument.Stone("fish", "눈 없는 물고기", Optional.empty(), 0, false);
        Monument.Stone hidden = new Monument.Stone("newcomer", "열세 번째", Optional.empty(), 0, true);
        Monument.Stone blank = new Monument.Stone("blank1", "", Optional.empty(), 0, true);
        assertEquals("<dark_gray>???", Monument.text(open, null, ""));
        assertEquals("", Monument.text(hidden, null, ""));                       // not even "???": nobody is told it exists
        assertEquals(Monument.text(blank, null, ""), Monument.text(hidden, null, ""));
        Monument.Stone solved = new Monument.Stone("newcomer", "열세 번째", Optional.of(who), 1, true);
        assertEquals("<gold>열세 번째\n<white>Steve\n<gray>2026.01.02", Monument.text(solved, "Steve", "2026.01.02"));
        assertEquals("<gold>열세 번째\n<white>Steve", Monument.text(solved, "Steve", ""));
        // unsolved it shows the same old stone as any other, and solving it changes its tag, so it is set again
        assertEquals(Monument.MODEL, Monument.model("newcomer", false));
        assertNotEquals(Monument.key("newcomer", Monument.text(hidden, null, ""), Monument.model("newcomer", false)),
                Monument.key("newcomer", Monument.text(solved, "Steve", ""), Monument.model("newcomer", true)));
    }

    @Test
    void stoneKeyChangesWithItsModel() {
        // a stone set with the generic star (or before models were in the key) is set again with its emblem
        String generic = Monument.key("fish", "x", Monument.MODEL_SOLVED), own = Monument.key("fish", "x", Monument.model("fish", true));
        assertNotEquals(generic, own);
        assertNotEquals("fish|" + "x".hashCode(), own);
        assertTrue(own.startsWith("fish|"));   // still cleared by build()'s id prefix
        assertEquals(own, Monument.key("fish", "x", Monument.model("fish", true)));
    }

    @Test
    void strayStones() {
        assertFalse(Monument.stray("world", 0.5, 64, 0.5, "world", 6.5, 64, -4.5));   // on the arc (radius 8)
        assertFalse(Monument.stray("world", 0.5, 64, 0.5, "world", 0.5, 64 + Monument.TEXT_Y, 8.5)); // its text
        assertTrue(Monument.stray("world", 0.5, 64, 0.5, "world", 200, 64, 0));       // the old place
        assertTrue(Monument.stray("world", 0.5, 64, 0.5, "world_nether", 0.5, 64, 0.5));
    }
}
