package dev.stsv.economy.secret.newcomer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 후속 임무 「다음 사람」: every wish is spelled by the first words of its questions (and not by his old acrostic), only
 * the questions have a "?", a slip of the fingers never changes a first word, the file says what the code says, the names
 * look like accounts, and a week takes one wish and one name and keeps them.
 */
class NextSequelTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    static YamlConfiguration yml() {
        return YamlConfiguration.loadConfiguration(PACK.resolve("secrets/newcomer.yml").toFile());
    }

    @Test
    void firstWords() {
        assertEquals("밤에", Wishes.firstWord("밤에 몹 많이 나와요?"));
        assertEquals("사람", Wishes.firstWord("  사람 제일 많은 시간이 언제에요?"));
        assertEquals("네", Wishes.firstWord("네?"));
        assertEquals("", Wishes.firstWord(null));
        assertEquals("밤에 정류장에서 기다려줄 사람", Wishes.spelled(Wishes.byId(Wishes.DEFAULTS, "stop").questions()));
    }

    @Test
    void whatAQuestionIs() {
        assertTrue(Wishes.question("밤에 몹 많이 나와요?"));
        assertTrue(Wishes.question("이거 맞아요??"));
        assertFalse(Wishes.question("밤에 몹 많이 나와요"), "no ? at its end");
        assertFalse(Wishes.question("네?"), "one word: nothing to read a first word from");
        assertFalse(Wishes.question("이거 뭐? 저거 뭐?"), "a ? only at the end");
        assertFalse(Wishes.question(null));
    }

    @Test
    void everyWishIsSpelledByItsFirstWords() {
        assertTrue(Wishes.DEFAULTS.size() >= 8, "eight wishes or more");
        Set<String> ids = new HashSet<>(), says = new HashSet<>(), questions = new HashSet<>();
        for (Wishes.Wish w : Wishes.DEFAULTS) {
            assertNull(Wishes.problem(w), w.id());
            assertTrue(ids.add(w.id()));
            assertTrue(says.add(w.says()), "no two visitors want the same words");
            assertEquals(w.says(), Wishes.spelled(w.questions()));
            assertEquals(w.says().split(" ").length, w.questions().size());
            for (String q : w.questions()) {
                assertTrue(questions.add(q), "no question twice: " + q);
                assertTrue(q.length() <= 40, "a beginner's question is short: " + q);
            }
            // the old trick, tried blindly, reads nothing: the first characters are not his acrostic
            assertFalse(Acrostic.spells(w.questions(), Newcomer.DEFAULT_ACROSTIC), w.id());
        }
    }

    @Test
    void aBrokenWishIsNamed() {
        Wishes.Wish ok = Wishes.byId(Wishes.DEFAULTS, "stop");
        List<String> swapped = new ArrayList<>(ok.questions());
        swapped.set(0, ok.questions().get(1));
        swapped.set(1, ok.questions().get(0));
        assertNotNull(Wishes.problem(new Wishes.Wish("x", ok.says(), swapped, ok.task(), List.of())), "the order matters");
        assertNotNull(Wishes.problem(new Wishes.Wish("x", ok.says(), ok.questions().subList(0, 3), ok.task(), List.of())));
        assertNotNull(Wishes.problem(new Wishes.Wish("x", ok.says(), ok.questions(), ok.task(), List.of("고마워요?"))), "thanks never ask");
        Wishes.Task noItems = new Wishes.Task(Wishes.Where.SPAWN, Wishes.Act.DROP, List.of(), false, 0, 12, 4, 150);
        assertNotNull(Wishes.problem(new Wishes.Wish("x", ok.says(), ok.questions(), noItems, List.of())));
        Wishes.Task wet = new Wishes.Task(Wishes.Where.WATER, Wishes.Act.STAY, List.of(), false, 10, 12, 4, 150);
        assertNotNull(Wishes.problem(new Wishes.Wish("x", ok.says(), ok.questions(), wet, List.of())));
    }

    @Test
    void theFileSaysWhatTheCodeSays() throws Exception {
        YamlConfiguration y = yml();
        List<String> problems = new ArrayList<>();
        List<Wishes.Wish> read = Wishes.read(y.getConfigurationSection("sequel.wishes"), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(Wishes.DEFAULTS.size(), read.size());
        Set<String> items = new HashSet<>();
        try (Stream<Path> s = Files.list(PACK.resolve("items"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).toList())
                for (String k : YamlConfiguration.loadConfiguration(f.toFile()).getKeys(false)) items.add("economy:" + k);
        }
        for (int i = 0; i < read.size(); i++) {
            Wishes.Wish a = Wishes.DEFAULTS.get(i), b = read.get(i);
            assertEquals(a.id(), b.id());
            assertEquals(a.says(), b.says());
            assertEquals(a.questions(), b.questions());
            assertEquals(a.task().where(), b.task().where(), a.id());
            assertEquals(a.task().act(), b.task().act(), a.id());
            assertEquals(a.task().night(), b.task().night(), a.id());
            if (a.task().act() != Wishes.Act.DROP) assertEquals(a.task().seconds(), b.task().seconds(), a.id());
            // the file may know more items than the code (the server's own fish), never fewer
            for (String id : a.task().items()) assertTrue(b.task().items().contains(id), a.id() + ": " + id);
            for (String id : b.task().items()) assertTrue(id.contains(":") ? items.contains(id) : id.matches("[a-z_]+"), b.id() + ": " + id);
        }
        assertEquals(Wishes.THANKS, y.getStringList("sequel.thanks"));
        assertEquals(Wishes.NAMES, y.getStringList("sequel.names"));
        assertEquals(List.of(80000, 150000), y.getIntegerList("sequel.reward.money"), "NORMAL: 80,000-150,000 G");
        assertTrue(y.getBoolean("sequel.enabled"));
        assertTrue(y.isBoolean("sequel.public-chat"), "its own switch for the public fake chat");
        assertTrue(y.getString("sequel.done").contains("{reward}"));
        assertTrue(y.isString("sequel.none"));
        assertEquals("…또 한 명 왔어요.", y.getString("sequel.told"));
        assertTrue(y.getDouble("sequel.chance") > 0 && y.getDouble("sequel.chance") < 0.05);
    }

    @Test
    void onlyTheQuestionsAsk() {
        for (String t : Wishes.THANKS) assertTrue(Talk.small(t), t);
        for (String t : Newcomer.TIRED) assertTrue(Talk.small(t), t);
        assertEquals(Wishes.THANKS.size(), new HashSet<>(Wishes.THANKS).size());
    }

    @Test
    void namesLookLikeAccountsAndAreNotHis() {
        assertTrue(Wishes.NAMES.size() >= 12);
        assertEquals(Wishes.NAMES.size(), new HashSet<>(Wishes.NAMES).size());
        for (String n : Wishes.NAMES) {
            assertTrue(Whispers.account(n), n);
            assertFalse(n.equalsIgnoreCase(Newcomer.DEFAULT_NAME));
            assertFalse(Talk.mentions(n, Newcomer.DEFAULT_NAME), "nobody reads as him: " + n);
        }
    }

    @Test
    void aSlipNeverChangesAFirstWord() {
        Random r = new Random(13);
        int slips = 0, kept = 0;
        for (Wishes.Wish w : Wishes.DEFAULTS) {
            for (String q : w.questions()) {
                for (int i = 0; i < 40; i++) {
                    var slip = Typing.slip(q, r);
                    if (slip.isEmpty()) continue;
                    slips++;
                    String typed = slip.get().typed();
                    assertTrue(typed.endsWith("?"), typed);
                    // NextSequel says a slip only when this holds; else the question is said as it is
                    if (Wishes.keepsFirstWord(typed, q)) {
                        kept++;
                        assertEquals(Wishes.firstWord(q), Wishes.firstWord(typed));
                    }
                }
            }
        }
        assertTrue(slips > 100 && kept > 50, slips + " / " + kept);
        assertFalse(Wishes.keepsFirstWord("밤애 몹 많이 나와요?", "밤에 몹 많이 나와요?"));
        assertTrue(Wishes.keepsFirstWord("밤에 몹 많이 나와오?", "밤에 몹 많이 나와요?"));
    }

    @Test
    void items() {
        assertTrue(Wishes.listed("minecraft:poppy", List.of("poppy")));
        assertTrue(Wishes.listed("economy:mackerel", List.of("cod", "economy:mackerel")));
        assertTrue(Wishes.listed("economy:mackerel", List.of("economy:*")));
        assertFalse(Wishes.listed("minecraft:poppy", List.of("economy:*", "cod")));
        assertFalse(Wishes.listed(null, List.of("cod")));
    }

    @Test
    void aWeekTakesOneWishAndOneNameAndKeepsThem() {
        int n = Wishes.DEFAULTS.size();
        Set<Integer> seen = new HashSet<>();
        for (int w = 1; w <= 52; w++) {
            long seed = Wishes.seed(String.format("2026-W%02d", w), 77);
            int[] o = Wishes.order(n, seed);
            Set<Integer> all = new HashSet<>();
            for (int i : o) all.add(i);
            assertEquals(n, all.size());
            assertEquals(o[0], Wishes.pick(n, seed, x -> true, x -> false));
            assertEquals(Wishes.pick(n, seed, x -> true, x -> false), Wishes.pick(n, seed, x -> true, x -> false));
            seen.add(o[0]);
            // a taken name (a real account) and a recent one are passed over
            assertEquals(o[2], Wishes.pick(n, seed, x -> x != o[0], x -> x == o[1]));
        }
        assertTrue(seen.size() >= n - 2, "a year sees nearly every wish: " + seen.size());
        assertEquals(-1, Wishes.pick(n, 1, x -> false, x -> false));
        String s = null;
        for (String id : List.of("a", "b", "c", "a")) s = Wishes.remember(s, id, 2);
        assertEquals("c,a", s);
    }

    @Test
    void night() {
        assertFalse(Wishes.night(12999));
        assertTrue(Wishes.night(13000));
        assertTrue(Wishes.night(22999));
        assertFalse(Wishes.night(23000));
        assertTrue(Wishes.night(24000 + 18000));
    }

    @Test
    void theErrandInWords() {
        for (Wishes.Wish w : Wishes.DEFAULTS) assertFalse(NextSequel.describe(w.task()).isBlank(), w.id());
        assertTrue(NextSequel.describe(Wishes.byId(Wishes.DEFAULTS, "stop").task()).contains("밤"));
    }
}
