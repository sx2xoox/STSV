package dev.stsv.economy.secret.scarecrow;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 후속 임무 「밭의 부탁」: the week's one ask against the real crops, its variety, and the week's gift. */
class ScarecrowFavorTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    private static YamlConfiguration yml(String rel) {
        return YamlConfiguration.loadConfiguration(PACK.resolve(rel).toFile());
    }

    private static List<Riddles.Ask> asks() {
        return Favor.usable(Riddles.parse(yml("secrets/scarecrow.yml").getMapList("sequel.asks")).all());
    }

    @Test
    void everyAskNamesRealCropsAndHasItsRiddle() {
        List<Riddles.Ask> asks = asks();
        assertTrue(asks.size() >= 8, "a pool of riddles: " + asks.size());
        YamlConfiguration items = yml("items/crops.yml");
        Set<String> ids = new HashSet<>();
        for (Riddles.Ask a : asks) {
            assertTrue(ids.add(a.id()), "twice: " + a.id());
            assertTrue(a.say().getFirst().startsWith("…"), a.id());
            assertFalse(a.most());
            boolean custom = false;
            for (String id : a.items()) {
                if (id.startsWith("economy:")) {
                    assertTrue(items.contains(id.substring(8)), a.id() + ": no such crop " + id);
                    assertFalse(id.endsWith("_seeds"), a.id() + ": a crop, not its seed");
                    custom = true;
                } else assertTrue(id.startsWith("minecraft:"), id);
            }
            assertTrue(custom || a.items().stream().anyMatch(i -> Riddles.VANILLA.values().stream().anyMatch(v -> v.contains(i))), a.id() + " can be grown");
        }
    }

    @Test
    void oneRightCropAnswersAndAnythingElseDoesNot() {
        Riddles.Ask teeth = asks().get(Favor.indexOf(asks(), "teeth"));
        assertEquals(Riddles.Verdict.RIGHT, Riddles.judge(teeth, "economy:corn", Set.of(), Set.of()));
        assertEquals(Riddles.Verdict.WRONG, Riddles.judge(teeth, "economy:corn_seeds", Set.of(), Set.of()));
        assertEquals(Riddles.Verdict.WRONG, Riddles.judge(teeth, "minecraft:stone", Set.of(), Set.of()));
        assertEquals(-1, Favor.indexOf(asks(), "no-such-ask"));
        // "what you planted most" and an ask without answers are never the week's ask
        List<Riddles.Ask> main = Riddles.parse(yml("secrets/scarecrow.yml").getMapList("asks")).all();
        assertEquals(main.size() - 1, Favor.usable(main).size());
    }

    @Test
    void theWeekFixesTheAskAndRecentOnesDoNotComeBack() {
        UUID me = new UUID(3, 4);
        int n = asks().size();
        assertEquals(Favor.seed("2026-W48", me), Favor.seed("2026-W48", me));
        assertNotEquals(Favor.seed("2026-W48", me), Favor.seed("2026-W49", me));
        assertEquals(Favor.pick(Favor.seed("2026-W48", me), n, List.of()), Favor.pick(Favor.seed("2026-W48", me), n, List.of()));
        List<Integer> recent = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int week = 1; week <= 30; week++) {
            int at = Favor.pick(Favor.seed("2026-W" + week, me), n, recent);
            assertTrue(at >= 0 && at < n);
            assertFalse(recent.contains(at), "week " + week + " repeats a recent ask");
            seen.add(at);
            recent = Favor.remember(recent, at, 5);
            assertTrue(recent.size() <= 5);
        }
        assertTrue(seen.size() >= 8, "variety over thirty weeks: " + seen.size());
        assertEquals(-1, Favor.pick(1, 0, List.of()));
        assertEquals(0, Favor.pick(99, 1, List.of(0)), "only one line: it is said again");
        assertEquals(List.of(1, 2), Favor.numbers(Favor.join(List.of(1, 2)) + ",x"));
    }

    @Test
    void theGiftLastsTheWeekAndStaysInItsChunk() {
        assertTrue(Favor.holds("2026-W48", "2026-W48"));
        assertFalse(Favor.holds("2026-W47", "2026-W48"));
        assertFalse(Favor.holds(null, "2026-W48"));
        assertTrue(Favor.inChunk(-1, 31, -1, 1));
        assertFalse(Favor.inChunk(16, 0, 0, 0));
        assertEquals(8, Favor.middle(0));
        assertEquals(-8, Favor.middle(-1));
        assertTrue(Favor.beat(120, 60));
        assertFalse(Favor.beat(121, 60));
        assertFalse(Favor.beat(5, 0));
    }

    /** The engine's square pulses: every column of the chunk is rolled exactly once, and none of a neighbour's. */
    @Test
    void theGrowthPulseCoversTheChunkOnceAndNothingBeyondIt() {
        for (int[] c : new int[][]{{0, 0}, {-1, 1}, {5, -7}}) {
            java.util.Map<Long, Integer> hits = new java.util.HashMap<>();
            for (int[] t : Favor.tiles(c[0], c[1])) {
                for (int x = t[0] - t[2]; x <= t[0] + t[2]; x++) for (int z = t[1] - t[2]; z <= t[1] + t[2]; z++) {
                    assertTrue(Favor.inChunk(x, z, c[0], c[1]), x + "," + z + " is outside chunk " + c[0] + "," + c[1]);
                    hits.merge(((long) x << 32) ^ (z & 0xffffffffL), 1, Integer::sum);
                }
            }
            assertEquals(256, hits.size());
            for (int n : hits.values()) assertEquals(1, n);
        }
    }

    @Test
    void theFileIsTheEasyTierWithAModestGiftAndEnoughThanks() {
        var s = yml("secrets/scarecrow.yml").getConfigurationSection("sequel");
        assertTrue(s != null && s.getBoolean("enabled"));
        List<?> money = s.getList("reward.money");
        assertEquals(30000, ((Number) money.get(0)).intValue());
        assertEquals(80000, ((Number) money.get(1)).intValue());
        List<String> thanks = s.getStringList("thanks");
        assertTrue(thanks.size() >= 8);
        for (String t : thanks) assertTrue(t.startsWith("…") && !t.contains("!"), t);
        assertTrue(s.getDouble("perk.chance") > 0 && s.getDouble("perk.chance") <= 0.1, "a little faster, not a farm machine");
        assertTrue(s.getLong("perk.every-seconds") >= 30);
    }
}
