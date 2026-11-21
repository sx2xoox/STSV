package dev.stsv.economy.secret.fish;

import dev.stsv.economy.fishing.FishSpec;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The eyeless fish's pure rules ({@link Waters}) and its settings file (the riddles, the hidden first syllables). */
class EyelessFishTest {

    private static YamlConfiguration file() {
        return YamlConfiguration.loadConfiguration(new File(System.getProperty("stsv.content"), "economy/secrets/fish.yml"));
    }

    @Test
    void stagesAskForTheSevenWatersInOrder() {
        assertNull(Waters.forStage(0));
        assertEquals("sea", Waters.forStage(1));
        assertEquals("cave", Waters.forStage(3));
        assertEquals("drowned", Waters.forStage(7));
        assertNull(Waters.forStage(8));
        assertEquals(7, Waters.ORDER.size());
        assertEquals(Waters.DROWNED, Waters.ORDER.getLast());
    }

    @Test
    void aPerfectReelIsWithinTheWindowAfterTheBite() {
        assertTrue(Waters.perfect(1000, 1000, 8));
        assertTrue(Waters.perfect(1000, 1008, 8));
        assertFalse(Waters.perfect(1000, 1009, 8), "too late");
        assertFalse(Waters.perfect(0, 5, 8), "no bite seen");
        assertFalse(Waters.perfect(1000, 990, 8), "a bite from the future (a restart)");
    }

    @Test
    void itTellsOneMemoryPerRelease() {
        assertEquals(0, Waters.told(0, false));
        assertEquals(0, Waters.told(1, false), "caught, still in hand");
        assertEquals(1, Waters.told(1, true));
        assertEquals(3, Waters.told(4, false));
        assertEquals(7, Waters.told(7, true));
        assertEquals(7, Waters.told(8, false), "done");
    }

    @Test
    void omensComeOneMorePerRelease() {
        assertEquals(List.of(), Waters.omens(0));
        assertEquals(List.of("bubbles"), Waters.omens(1));
        assertEquals(List.of("bubbles", "heavy", "footprints"), Waters.omens(3));
        assertEquals(Waters.OMENS, Waters.omens(7));
        assertEquals("bubbles", Waters.newOmen(1));
        assertEquals("name", Waters.newOmen(5));
        assertNull(Waters.newOmen(6));
        assertNull(Waters.newOmen(0));
    }

    @Test
    void theDrownedPlaceEndsItAtTheLastStageOrForTheOnesWhoReadAhead() {
        assertTrue(Waters.mayEnd(7, true, 4));
        assertFalse(Waters.mayEnd(7, false, 4), "still in hand");
        assertTrue(Waters.mayEnd(4, true, 4), "처음온곳 read: the shortcut");
        assertFalse(Waters.mayEnd(3, true, 4));
        assertFalse(Waters.mayEnd(3, true, 99) || Waters.mayEnd(6, true, 99), "no shortcut when set past the end");
        assertTrue(Waters.mayEnd(7, true, 99));
        assertFalse(Waters.mayEnd(8, true, 1), "done");
    }

    @Test
    void nearnessToTheDrownedPlace() {
        assertEquals(Waters.Near.HERE, Waters.near(0, 20, 64, 300));
        assertEquals(Waters.Near.HERE, Waters.near(20, 20, 64, 300));
        assertEquals(Waters.Near.CLOSE, Waters.near(21, 20, 64, 300));
        assertEquals(Waters.Near.NEAR, Waters.near(200, 20, 64, 300));
        assertEquals(Waters.Near.FAR, Waters.near(301, 20, 64, 300));
        assertEquals(Waters.Near.FAR, Waters.near(-1, 20, 64, 300), "another world");
    }

    @Test
    void theDeeperWaterWinsThenTheNearer() {
        assertTrue(Waters.deeper(12, 900, 11, 4));
        assertFalse(Waters.deeper(10, 1, 11, 4));
        assertTrue(Waters.deeper(11, 3, 11, 4));
        assertFalse(Waters.deeper(11, 5, 11, 4));
        assertTrue(Waters.deeper(0, 1e9, -1, Double.MAX_VALUE), "any water beats none");
    }

    @Test
    void firstSyllablesSkipTagsDotsAndQuotes() {
        assertEquals('처', Waters.first("<#8fa9b8><i>…처음"));
        assertEquals('음', Waters.first("  “음… 얼음"));
        assertEquals(' ', Waters.first("<i>…</i>"));
        assertEquals("가나", Waters.acrostic(List.of("가다", "<b>…나")));
    }

    @Test
    void placesRoundTrip() {
        assertArrayEquals(new Object[]{"world", 10, 62, -300, 17}, Waters.decode(Waters.encode("world", 10, 62, -300, 17)));
        assertArrayEquals(new Object[]{"world", 1, 2, 3, -1}, Waters.decode("world 1 2 3"), "set by hand: no depth");
        assertNull(Waters.decode(""));
        assertNull(Waters.decode("world 1 x 3"));
    }

    @Test
    void theHabitatsAreTheFishingModules() {
        assertEquals("cave", FishSpec.habitat("minecraft:lush_caves", false));
        assertEquals("mushroom", FishSpec.habitat("minecraft:mushroom_fields", false));
        assertEquals("warm", FishSpec.habitat("minecraft:warm_ocean", false));
        assertEquals("cold", FishSpec.habitat("minecraft:frozen_ocean", false));
        assertEquals("coldriver", FishSpec.habitat("minecraft:frozen_river", false));
        assertEquals("mangrove", FishSpec.habitat("minecraft:mangrove_swamp", false));
    }

    @Test
    void theSettingsFileHasWhatTheCodeReads() {
        YamlConfiguration y = file();
        for (String k : EyelessFish.keys()) assertTrue(y.contains(k), "fish.yml: " + k);
        assertEquals(0.0005, y.getDouble("start.chance"), 1e-9);
        assertEquals(300000, y.getLong("rewards.money"));
        assertEquals("economy:water_name", y.getString("rewards.title"));
        assertEquals(7, y.getMapList("item.stages").size(), "one look per stage");
        for (Object o : y.getList("final.rise")) assertTrue(o instanceof List<?> l && l.size() == 2, "final.rise: [초, 줄]");
        for (Object o : y.getList("final.given")) assertTrue(o instanceof List<?> l && l.size() == 2, "final.given: [초, 줄]");
    }

    @Test
    void theSevenMemoriesNameTheWatersAndSpellTheLastOne() {
        List<Map<?, ?>> ws = file().getMapList("waters");
        assertEquals(7, ws.size());
        Set<String> known = Set.of("sea", "deep", "warm", "cold", "mangrove", "mushroom", "swamp", "jungle", "coldriver", "river", "cave");
        List<String> memories = new ArrayList<>();
        for (int i = 0; i < ws.size(); i++) {
            Map<?, ?> w = ws.get(i);
            assertEquals(Waters.ORDER.get(i), w.get("id"), "waters[" + i + "] id");
            memories.add(String.valueOf(w.get("memory")));
            if (i < 6) {
                List<?> hs = (List<?>) w.get("habitats");
                assertFalse(hs == null || hs.isEmpty(), w.get("id") + ": habitats");
                for (Object h : hs) assertTrue(known.contains(String.valueOf(h)), w.get("id") + ": 없는 서식지 " + h);
            }
        }
        assertEquals(Waters.ACROSTIC, Waters.acrostic(memories), "첫 글자 = 처음 온 곳 깊은 물");
        // each memory must fit the water it names: a word of its riddle
        assertTrue(memories.get(0).contains("짠물"));
        assertTrue(memories.get(1).contains("얼음"));
        assertTrue(memories.get(2).contains("해가 들지 않는"));
        assertTrue(memories.get(3).contains("뿌리"));
        assertTrue(memories.get(4).contains("버섯"));
        assertTrue(memories.get(5).contains("따뜻한 바다"));
        assertTrue(memories.get(6).contains("처음 이 세계에 온 곳") && memories.get(6).contains("가장 깊은 물"));
    }

    @Test
    void consecutiveWatersAreDifferentPlaces() {
        // the next water's habitats must not all be the one just left, or the riddle would be no step at all
        List<Map<?, ?>> ws = file().getMapList("waters");
        for (int i = 1; i < 6; i++) {
            List<?> here = (List<?>) ws.get(i).get("habitats");
            assertFalse(here.contains("sea") || here.contains("river"), ws.get(i).get("id") + ": 흔한 물이면 수수께끼가 아님");
        }
    }
}
