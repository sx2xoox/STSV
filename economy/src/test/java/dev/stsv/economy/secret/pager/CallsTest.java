package dev.stsv.economy.secret.pager;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 후속 임무 「호출」: the pool of calls (the file says what the code says; every one can be done), which call a week takes
 * (the same all week, not a recent one, only a possible one), who "she" is when the pager answers, the evening of a
 * "come early" call, and the sequel's settings (reward tier, notes).
 */
class CallsTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    static YamlConfiguration yml() {
        return YamlConfiguration.loadConfiguration(PACK.resolve("secrets/pager.yml").toFile());
    }

    @Test
    void theFileSaysWhatTheCodeSays() {
        List<String> problems = new ArrayList<>();
        List<Calls.Call> read = Calls.read(yml().getConfigurationSection("sequel.calls"), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(Calls.DEFAULTS, read);
        assertTrue(read.size() >= 10, "ten calls or more");
        Set<String> ids = new HashSet<>(), codes = new HashSet<>();
        Set<Calls.Kind> kinds = new HashSet<>();
        for (Calls.Call c : read) {
            assertTrue(ids.add(c.id()), c.id());
            assertTrue(codes.add(c.code()), "one meaning for a number: " + c.code());
            assertNotNull(Codes.digits(c.code()), c.code());
            kinds.add(c.kind());
            if (c.kind() == Calls.Kind.REPLY) for (String a : c.answers()) assertNotNull(Codes.digits(a), a);
        }
        assertEquals(Set.of(Calls.Kind.values()), kinds, "every kind of errand is in the pool");
    }

    @Test
    void brokenCallsAreLeftOut() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("calls.ok.code", "045");
        y.set("calls.ok.kind", "basket");
        y.set("calls.ok.items", List.of("bread"));
        y.set("calls.letters.code", "빵");
        y.set("calls.letters.kind", "visit");
        y.set("calls.nokind.code", "100");
        y.set("calls.empty.code", "10288");
        y.set("calls.empty.kind", "basket");
        y.set("calls.mute.code", "7942");
        y.set("calls.mute.kind", "reply");
        y.set("calls.mute.answers", List.of("친구"));
        List<String> problems = new ArrayList<>();
        List<Calls.Call> read = Calls.read(y.getConfigurationSection("calls"), problems);
        assertEquals(1, read.size());
        assertEquals("ok", read.getFirst().id());
        assertEquals(4, problems.size(), problems.toString());
        assertEquals(Calls.DEFAULTS, Calls.read(null, problems));
    }

    @Test
    void everyBasketItemIsReal() throws Exception {
        Set<String> items = new HashSet<>();
        try (Stream<Path> s = Files.list(PACK.resolve("items"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).toList())
                for (String k : YamlConfiguration.loadConfiguration(f.toFile()).getKeys(false)) items.add("economy:" + k);
        }
        for (Calls.Call c : Calls.DEFAULTS)
            for (String id : c.items()) assertTrue(id.contains(":") ? items.contains(id) : id.matches("[a-z_]+"), c.id() + ": " + id);
    }

    @Test
    void aWeekTakesOneCallAndKeepsIt() {
        int n = Calls.DEFAULTS.size();
        long most = 0x1234_5678_9ABCL, least = 0x0FED_CBA9_8765L;
        Set<Integer> seen = new HashSet<>();
        for (int w = 1; w <= 52; w++) {
            long seed = Calls.seed(String.format("2026-W%02d", w), most, least);
            int[] o = Calls.order(n, seed);
            Set<Integer> all = new HashSet<>();
            for (int i : o) all.add(i);
            assertEquals(n, all.size(), "a permutation");
            int a = Calls.pick(n, seed, x -> true, x -> false);
            assertEquals(a, Calls.pick(n, seed, x -> true, x -> false), "the same all week");
            assertEquals(o[0], a);
            seen.add(a);
        }
        assertTrue(seen.size() >= n - 2, "a year sees nearly every call: " + seen.size());
        assertFalse(Calls.seed("2026-W48", most, least) == Calls.seed("2026-W49", most, least));
        assertFalse(Calls.seed("2026-W48", most, least) == Calls.seed("2026-W48", least, most));
    }

    @Test
    void recentAndImpossibleCallsArePassedOver() {
        long seed = Calls.seed("2026-W48", 1, 2);
        int n = 6;
        int[] o = Calls.order(n, seed);
        assertEquals(o[1], Calls.pick(n, seed, x -> true, x -> x == o[0]), "not the recent one");
        assertEquals(o[2], Calls.pick(n, seed, x -> x != o[1], x -> x == o[0]), "nor one that cannot be done now");
        assertEquals(o[0], Calls.pick(n, seed, x -> x == o[0], x -> true), "all recent: the first possible one");
        assertEquals(-1, Calls.pick(n, seed, x -> false, x -> false));
        assertEquals(-1, Calls.pick(0, seed, x -> true, x -> false));
    }

    @Test
    void recentListKeepsTheLastFew() {
        String s = null;
        for (String id : List.of("bread", "hurry", "back", "angel", "bread")) s = Calls.remember(s, id, 3);
        assertEquals("back,angel,bread", s);
        assertEquals(List.of("back", "angel", "bread"), Calls.recent(s));
        assertEquals("", Calls.remember("a,b", "c", 0));
        assertTrue(Calls.recent(null).isEmpty());
    }

    @Test
    void whoSheIs() {
        List<String> names = yml().getStringList("sequel.reply.names");
        assertFalse(names.isEmpty());
        assertTrue(Calls.her("파란대문", names));
        assertTrue(Calls.her("파란 대문", names), "spaces do not matter");
        assertTrue(Calls.her(" 파란 대문 집 ", names));
        assertTrue(Calls.her("딸", names));
        assertFalse(Calls.her("파란", names));
        assertFalse(Calls.her("", names));
        assertFalse(Calls.her(null, names));
        // what the live pager's prompt reads: the last word is the number, the rest (spaces and all) the name
        Codes.Page page = Codes.parsePage("파란 대문 집 1004");
        assertNotNull(page);
        assertTrue(Calls.her(page.name(), names));
        assertEquals("1004", page.digits());
    }

    @Test
    void onlyAReplyCallIsAnsweredAndOnlyByItsNumbers() {
        Calls.Call friends = Calls.byId(Calls.DEFAULTS, "friends"), bread = Calls.byId(Calls.DEFAULTS, "bread");
        assertTrue(Calls.answers(friends, "7942"));
        assertTrue(Calls.answers(friends, "1004"));
        assertFalse(Calls.answers(friends, "8282"));
        assertFalse(Calls.answers(friends, null));
        assertFalse(Calls.answers(bread, "1004"));
        assertFalse(Calls.answers(null, "1004"));
        assertNull(Calls.byId(Calls.DEFAULTS, "nope"));
    }

    @Test
    void comeEarlyMeansBeforeThatDaysNightfall() {
        assertTrue(Calls.dayEnough(0, 6000));
        assertTrue(Calls.dayEnough(5999, 6000));
        assertFalse(Calls.dayEnough(6000, 6000));
        assertFalse(Calls.dayEnough(14000, 20000), "never at night, whatever the setting");
        assertTrue(Calls.dayEnough(24000 + 100, 6000), "time of day, not full time");
        long day = 7;
        assertFalse(Calls.dusk(day * 24000 + 1000, day));
        assertFalse(Calls.dusk(day * 24000 + 12999, day));
        assertTrue(Calls.dusk(day * 24000 + 13000, day));
        assertTrue(Calls.dusk((day + 1) * 24000 + 10, day), "they slept: it is another day");
        assertTrue(Calls.dusk((day - 1) * 24000 + 10, day), "the clock was set back");
    }

    @Test
    void sequelSettings() {
        YamlConfiguration y = yml();
        assertTrue(y.getBoolean("sequel.enabled"));
        List<Integer> money = y.getIntegerList("sequel.reward.money");
        assertEquals(List.of(30000, 80000), money, "EASY: 30,000-80,000 G");
        for (String k : List.of("done", "missed", "screen", "screen-late", "note.title", "note.author", "note.page", "note.name"))
            assertTrue(y.isString("sequel." + k), k);
        assertTrue(y.getString("sequel.screen").contains("{code}"));
        assertTrue(y.getString("sequel.done").contains("{reward}"));
        assertNotNull(Codes.digits(y.getString("sequel.thanks")));
        assertTrue(y.getDouble("sequel.chance") > 0 && y.getDouble("sequel.chance") < 0.05);
        // a note for every kind of errand, each short enough for one page, and enough of them not to repeat soon
        int total = 0;
        for (Calls.Kind k : Calls.Kind.values()) {
            List<String> pool = y.getStringList("sequel.notes." + k.name().toLowerCase());
            assertFalse(pool.isEmpty(), "notes for " + k);
            total += pool.size();
        }
        List<String> any = y.getStringList("sequel.notes.any");
        total += any.size();
        assertTrue(total >= 10, "notes: " + total);
        for (String key : y.getConfigurationSection("sequel.notes").getKeys(false))
            for (String n : y.getStringList("sequel.notes." + key)) {
                assertTrue(n.replaceAll("<[^>]+>", "").length() <= 150, "too long for a page: " + n);
                assertFalse(n.matches(".*(빵 사 와|빨리빨리|천사|열이 펄펄|돌아와|이만).*"), "a note never explains a number: " + n);
            }
        assertEquals(PagerSequel.NOTES.size(), new HashSet<>(PagerSequel.NOTES).size());
    }
}
