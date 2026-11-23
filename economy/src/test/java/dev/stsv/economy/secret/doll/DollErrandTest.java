package dev.stsv.economy.secret.doll;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 후속 임무 「인형의 심부름」: this week's riddle, who counts now, when the answer has changed, the hints, and doll.yml's pools. */
class DollErrandTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    static UUID id(int n) {
        return new UUID(0, n);
    }

    static Riddles.Fact f(int n, String name, int land, int y, double dist, long money) {
        return new Riddles.Fact(id(n), name, land, 1000 * n, 100 * n, 0, y, dist, money, -1);
    }

    static final List<Riddles.Fact> THREE = List.of(f(1, "민수", 5, 64, 10, 100), f(2, "Sam", 0, -30, 200, 9_000), f(3, "하늘", 0, 120, 50, 9_000));

    @Test
    void theWeekFixesTheRiddleAndARestartKeepsIt() {
        UUID me = id(99);
        assertEquals(Errand.seed("2026-W48", me), Errand.seed("2026-W48", me));
        assertNotEquals(Errand.seed("2026-W48", me), Errand.seed("2026-W49", me));
        assertNotEquals(Errand.seed("2026-W48", me), Errand.seed("2026-W48", id(98)));
        long seed = Errand.seed("2026-W48", me);
        assertEquals(Errand.choose(seed, List.of(), THREE), Errand.choose(seed, List.of(), THREE));
    }

    @Test
    void onlyARiddleSomeoneOnlineAnswersIsAsked() {
        for (long seed = 0; seed < 300; seed++) {
            Riddles.Kind k = Errand.choose(seed, List.of(), THREE);
            assertFalse(Riddles.answers(k, THREE).isEmpty(), k + " names nobody");
            assertNotEquals(Riddles.Kind.HOME, k);
            assertNotEquals(Riddles.Kind.FISH, k, "nobody here ever fished");
            assertNotEquals(Riddles.Kind.FRIEND, k, "nobody here is in the friend list");
        }
        assertNull(Errand.choose(7, List.of(), List.of()), "alone: it waits");
    }

    @Test
    void recentRiddlesAreNotAskedAgainWhileAnotherIsLeft() {
        Set<Riddles.Kind> seen = new HashSet<>();
        List<Riddles.Kind> recent = new ArrayList<>();
        for (int week = 0; week < 6; week++) {
            Riddles.Kind k = Errand.choose(Errand.seed("2026-W" + (10 + week), id(5)), recent, THREE);
            assertFalse(recent.contains(k), "week " + week + " repeats " + k);
            seen.add(k);
            recent = Errand.remember(recent, k, 4);
            assertTrue(recent.size() <= 4);
        }
        assertTrue(seen.size() >= 5, "variety: " + seen);
        // every answerable riddle was recent: one of them rather than "any stranger"
        List<Riddles.Kind> all = new ArrayList<>(EnumSet.allOf(Riddles.Kind.class));
        Riddles.Kind k = Errand.choose(3, all, THREE);
        assertNotEquals(Riddles.Kind.STRANGER, k);
    }

    @Test
    void aJustNowRiddleKeepsWhoItNamedWhileTheyAreHere() {
        Set<UUID> told = Riddles.answers(Riddles.Kind.FAR, THREE);
        assertEquals(Set.of(id(2)), told);
        // the solver walked up to Sam: 하늘 is the farthest now, but Sam is still the one
        List<Riddles.Fact> later = List.of(f(1, "민수", 5, 64, 150, 100), f(2, "Sam", 0, -30, 3, 9_000), f(3, "하늘", 0, 120, 180, 9_000));
        assertEquals(Set.of(id(2)), Errand.targets(Riddles.Kind.FAR, told, later));
        // Sam left: whoever is the farthest now
        List<Riddles.Fact> gone = List.of(later.get(0), later.get(2));
        Set<UUID> now = Errand.targets(Riddles.Kind.FAR, told, gone);
        assertEquals(Set.of(id(3)), now);
        assertTrue(Errand.changed(told, now));
    }

    @Test
    void aRecordRiddleFollowsTheRecordAndSaysSoWhenSomeoneNewCounts() {
        Set<UUID> told = Riddles.answers(Riddles.Kind.RICH, THREE);
        assertEquals(Set.of(id(2), id(3)), told);
        // 하늘 spent it all: only Sam now — one of two dropping out is not news
        List<Riddles.Fact> spent = List.of(THREE.get(0), THREE.get(1), f(3, "하늘", 0, 120, 50, 10));
        Set<UUID> now = Errand.targets(Riddles.Kind.RICH, told, spent);
        assertEquals(Set.of(id(2)), now);
        assertFalse(Errand.changed(told, now));
        // 민수 struck gold: a new answer, said
        List<Riddles.Fact> gold = List.of(f(1, "민수", 5, 64, 10, 1_000_000), THREE.get(1), THREE.get(2));
        Set<UUID> rich = Errand.targets(Riddles.Kind.RICH, now, gold);
        assertEquals(Set.of(id(1)), rich);
        assertTrue(Errand.changed(now, rich));
        // the only landowner left: the riddle names nobody (the mission asks another)
        assertTrue(Errand.targets(Riddles.Kind.LAND, Set.of(id(1)), List.of(THREE.get(1), THREE.get(2))).isEmpty());
        assertFalse(Errand.changed(Set.of(id(1)), Set.of()));
    }

    @Test
    void standingBesideAndTheHints() {
        assertTrue(Errand.beside(3, 0, 2, 4));
        assertFalse(Errand.beside(4, 0, 2, 4));
        assertFalse(Errand.beside(1, 5, 1, 4), "a floor above is not beside");
        assertEquals("민", Errand.initial("민수"));
        assertEquals("S", Errand.initial(" Sam"));
        assertEquals("?", Errand.initial(""));
    }

    @Test
    void linesAreFixedByTheSeedAndDoNotRepeat() {
        assertEquals(-1, Errand.line(5, 0, List.of()));
        assertEquals(Errand.line(42, 12, List.of()), Errand.line(42, 12, List.of()));
        List<Integer> recent = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int at = Errand.line(1000 + i * 31L, 12, recent);
            assertFalse(recent.contains(at));
            recent = Errand.remember(recent, at, 11);
        }
        assertEquals(12, new HashSet<>(recent).size() + 1);
        assertEquals(List.of(2, 3, 1), Errand.remember(List.of(1, 2, 3), 1, 3));
        assertEquals(Set.of(id(1), id(2)), Errand.ids(Errand.join(List.of(id(1), id(2))) + ",x"));
        assertEquals(List.of(Riddles.Kind.DEEP, Riddles.Kind.RICH), Errand.kinds("DEEP,nope,RICH"));
    }

    @Test
    void theBowIsASmallNodThatComesUpAgain() {
        assertEquals(0f, Companion.bowPitch(-1, 24, 32f));
        assertEquals(0f, Companion.bowPitch(24, 24, 32f));
        assertEquals(32f, Companion.bowPitch(12, 24, 32f), 0.001f);
        assertTrue(Companion.bowPitch(3, 24, 32f) > 0 && Companion.bowPitch(3, 24, 32f) < Companion.bowPitch(8, 24, 32f));
    }

    @Test
    void theFileHasEveryRiddleAndEnoughVariety() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("secrets/doll.yml").toFile());
        var s = y.getConfigurationSection("sequel");
        assertTrue(s != null && s.getBoolean("enabled"));
        List<?> money = s.getList("reward.money");
        assertEquals(30000, ((Number) money.get(0)).intValue());   // the easy tier
        assertEquals(80000, ((Number) money.get(1)).intValue());
        for (Riddles.Kind k : Riddles.POOL) assertTrue(s.isString("riddles." + k.id()), "sequel.riddles." + k.id());
        assertTrue(s.isString("riddles.stranger"));
        assertTrue(s.getStringList("openers").size() >= 6);
        List<String> remarks = s.getStringList("remarks");
        assertTrue(remarks.size() >= 8);
        for (String r : remarks) {
            assertTrue(r.startsWith("…") && r.contains("{name}"), r);
            assertFalse(r.contains("!"), r);
        }
        assertTrue(s.getLong("stay") >= 5 && s.getDouble("radius") >= 3);
    }
}
