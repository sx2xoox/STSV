package dev.stsv.economy.secret.usher;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 후속 임무 「윤해담의 부탁」: the week's picture is the same for a week and a player, every memory is heard once before any
 * comes again, and usher.yml has a memory for each of the thirteen pictures and a reward in the easy range.
 */
class UsherSequelTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void thirteenStationsWithAPicture() {
        List<String> all = UsherSequel.stations();
        assertEquals(UsherPlan.PICTURES, all.size());
        assertEquals(Pictures.WHAT.keySet(), new HashSet<>(all));
    }

    @Test
    void sameWeekSamePicture() {
        List<String> all = UsherSequel.stations();
        String a = UsherAsk.pick("2026-W48", A, all, List.of());
        assertEquals(a, UsherAsk.pick("2026-W48", A, all, List.of()));
        assertTrue(all.contains(a));
        assertNull(UsherAsk.pick("2026-W48", A, List.of(), List.of()));
    }

    @Test
    void everyMemoryOnceThenRoundAgain() {
        List<String> all = UsherSequel.stations();
        String heard = "";
        Set<String> seen = new HashSet<>();
        String last = null;
        for (int w = 1; w <= all.size(); w++) {
            String s = UsherAsk.pick("2026-W" + w, A, all, UsherAsk.heard(heard));
            assertTrue(seen.add(s), "week " + w + " repeats " + s);
            assertEquals(w - 1, UsherAsk.count(heard, all));
            heard = UsherAsk.addHeard(heard, s, all);
            last = s;
        }
        assertEquals(all.size(), seen.size());
        // the round is over: only the last one is remembered, and the next round does not begin with it
        assertEquals(last, heard);
        String next = UsherAsk.pick("2027-W01", A, all, UsherAsk.heard(heard));
        assertNotEquals(last, next);
        assertFalse(UsherAsk.heard(UsherAsk.addHeard(heard, next, all)).isEmpty());
    }

    @Test
    void heardListIsTidy() {
        assertEquals(List.of("a", "b"), UsherAsk.heard(" a,b,,a "));
        assertEquals(List.of(), UsherAsk.heard(null));
        assertEquals("b,a", UsherAsk.addHeard("a,b", "a", List.of("a", "b", "c")));
        // a station that no longer exists does not hold the round up
        assertEquals("c", UsherAsk.addHeard("gone,a,b", "c", List.of("a", "b", "c")));
    }

    @Test
    void ymlHasEveryMemoryAndAnEasyReward() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("secrets/usher.yml").toFile());
        for (String s : UsherSequel.stations()) {
            String line = y.getString("sequel.memories." + s, "");
            assertTrue(line.startsWith("…"), "usher.yml sequel.memories." + s);
        }
        assertEquals(UsherPlan.PICTURES, y.getConfigurationSection("sequel.memories").getKeys(false).size());
        List<Long> money = y.getLongList("sequel.reward.money");
        assertEquals(2, money.size());
        assertTrue(money.get(0) >= 30_000 && money.get(1) <= 80_000 && money.get(0) <= money.get(1));
        assertTrue(y.getStringList("sequel.ask").stream().anyMatch(l -> l.contains("체험관 그림이 또 하나 틀어졌어요")));
        for (String k : List.of("ask-dawn", "ask-end", "go", "thanks")) assertFalse(y.getStringList("sequel." + k).isEmpty(), k);
        for (String k : List.of("hint", "arrive", "found", "goodbye", "done")) assertTrue(y.isString("sequel." + k), k);
    }
}
