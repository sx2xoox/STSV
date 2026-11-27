package dev.stsv.economy.secret.lostkey;

import dev.stsv.economy.secret.Memory;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 분실물센터: the five clues come from the network as it is (and from the player), each points at stops that exist, every
 * stop that fits counts, the fallbacks; the paper leaves out what is not known; lostkey.yml has every line the code reads.
 */
class LostKeyTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");
    static final List<String> ORDER = List.of("sea", "mountain", "forest", "field", "village", "port");
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 1 터미널(first) — 2 바닷가 — 3 산골 — 4 부두(last); 5 솔밭 (line 2 first) — 6 시장 (line 2 last) */
    static List<Clues.Stop> net() {
        return List.of(
                new Clues.Stop(1, "터미널", Set.of("village"), true, false),
                new Clues.Stop(2, "바닷가", Set.of("sea"), false, false),
                new Clues.Stop(3, "산골", Set.of("mountain", "forest"), false, false),
                new Clues.Stop(4, "부두", Set.of("sea", "port"), false, true),
                new Clues.Stop(5, "솔밭", Set.of("forest"), true, false),
                new Clues.Stop(6, "시장", Set.of(), false, true));
    }

    @Test
    void firstAndEnd() {
        assertEquals(List.of(1, 5), Clues.matching(Clues.pick(1, net(), A, "Steve", 2, ORDER), net()));
        assertEquals(List.of(1, 4, 5, 6), Clues.matching(Clues.pick(4, net(), A, "Steve", 2, ORDER), net()));
    }

    @Test
    void tagTellsSomeStopsFromTheRestAndIsTheSameForThePlayer() {
        Clues.Clue c = Clues.pick(2, net(), A, "Steve", 2, ORDER);
        assertEquals(Clues.Kind.TAG, c.kind());
        assertEquals(c, Clues.pick(2, net(), A, "Steve", 2, ORDER));
        List<Integer> ids = Clues.matching(c, net());
        assertFalse(ids.isEmpty());
        assertTrue(ids.size() < net().size());
        // every player gets a trait that exists; over many players more than one trait comes up
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 64; i++) {
            Clues.Clue x = Clues.pick(2, net(), UUID.randomUUID(), "x", null, ORDER);
            assertFalse(Clues.matching(x, net()).isEmpty());
            seen.add(x.word());
        }
        assertTrue(seen.size() > 1);
    }

    @Test
    void tagFallsBackToPlainStops() {
        List<Clues.Stop> bare = List.of(new Clues.Stop(1, "가", Set.of(), true, false), new Clues.Stop(2, "나", Set.of(), false, true));
        Clues.Clue c = Clues.pick(2, bare, A, "x", null, ORDER);
        assertEquals(Clues.Kind.PLAIN, c.kind());
        assertEquals(List.of(1, 2), Clues.matching(c, bare));
    }

    @Test
    void initialSoundsLikeTheName() {
        // S → ㅅ: 산골, 솔밭, 시장
        Clues.Clue s = Clues.pick(3, net(), A, "Steve", 2, ORDER);
        assertEquals(new Clues.Clue(Clues.Kind.INITIAL, "ㅅ"), s);
        assertEquals(List.of(3, 5, 6), Clues.matching(s, net()));
        // P → ㅍ: no ㅍ stop, its kin ㅂ (바닷가, 부두) is told instead
        Clues.Clue p = Clues.pick(3, net(), A, "Pat", 2, ORDER);
        assertEquals("ㅂ", p.word());
        assertEquals(List.of(2, 4), Clues.matching(p, net()));
        // no letter of "xom" (ㅇ ㅇ ㅁ) starts a stop: the initial most stops share (ㅅ)
        Clues.Clue none = Clues.pick(3, net(), A, "xom", 2, ORDER);
        assertEquals(new Clues.Clue(Clues.Kind.INITIAL, "ㅅ"), none);
        assertFalse(Clues.matching(none, net()).isEmpty());
        // a Korean letter in the name is its own initial; Latin stop names are matched by their letter
        assertEquals("ㅂ", Clues.pick(3, net(), A, "부엉이", 2, ORDER).word());
        List<Clues.Stop> latin = List.of(new Clues.Stop(1, "Sunny Hill", Set.of(), true, true), new Clues.Stop(2, "Market", Set.of(), false, false));
        assertEquals(List.of(1), Clues.matching(Clues.pick(3, latin, A, "Steve", null, ORDER), latin));
    }

    @Test
    void initialsAndParticles() {
        assertEquals("ㅅ", Clues.initialOf("  솔밭 정류장"));
        assertEquals("ㄲ", Clues.initialOf("까치골"));
        assertEquals("M", Clues.initialOf("market"));
        assertEquals("", Clues.initialOf("123"));
        assertEquals("으로", Clues.ro("ㅅ"));
        assertEquals("로", Clues.ro("ㄹ"));
        assertEquals("로", Clues.ro("S"));
        assertEquals("으로", Clues.ro("M"));
        assertEquals(List.of("ㅋ", "ㄱ", "ㄲ"), Clues.family("ㅋ"));
        assertEquals(List.of("ㅎ"), Clues.family("ㅎ"));
    }

    @Test
    void foundStopElseTheOldest() {
        assertEquals(List.of(2), Clues.matching(Clues.pick(5, net(), A, "x", 2, ORDER), net()));
        Clues.Clue gone = Clues.pick(5, net(), A, "x", 99, ORDER);
        assertEquals(Clues.Kind.OLDEST, gone.kind());
        assertEquals(List.of(1), Clues.matching(gone, net()));
    }

    @Test
    void anyWhenNothingFits() {
        List<Clues.Stop> noLines = List.of(new Clues.Stop(7, "가", Set.of(), false, false), new Clues.Stop(8, "나", Set.of(), false, false));
        Clues.Clue c = Clues.pick(1, noLines, A, "x", null, ORDER);
        assertEquals(Clues.Kind.ANY, c.kind());
        assertEquals(List.of(7, 8), Clues.matching(c, noLines));
        assertEquals(Clues.Kind.ANY, Clues.pick(1, List.of(), A, "x", null, ORDER).kind());
    }

    @Test
    void clueSavesAndReadsBack() {
        for (Clues.Clue c : List.of(new Clues.Clue(Clues.Kind.TAG, "sea"), new Clues.Clue(Clues.Kind.FIRST, ""), new Clues.Clue(Clues.Kind.FOUND, "12")))
            assertEquals(c, Clues.Clue.decode(c.encode()));
        assertEquals(null, Clues.Clue.decode("NOPE x"));
    }

    @Test
    void paperLeavesOutWhatIsNotKnown() {
        Map<String, String> v = new HashMap<>();
        v.put("player", "Steve");
        v.put("joined", null);
        v.put("guess", "");
        List<String> out = Paper.fill(List.of("주인: {player}", "맡긴 날: {joined}", "곳{guess}", "{other} 그대로"), v);
        assertEquals(List.of("주인: Steve", "곳", "{other} 그대로"), out);
        List<Memory.Sale> newestFirst = List.of(new Memory.Sale("b", 2, 300), new Memory.Sale("a", 5, 100), new Memory.Sale("c", 1, 200));
        assertEquals("a", Paper.oldest(newestFirst).item());
        assertEquals(null, Paper.oldest(List.of()));
        assertEquals("2026년 1월 1일", Paper.date(java.time.ZonedDateTime.of(2026, 1, 1, 9, 5, 0, 0, java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()));
        assertEquals("오전 9시 5분", Paper.time(java.time.ZonedDateTime.of(2026, 1, 1, 9, 5, 0, 0, java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()));
    }

    @Test
    void theFirstLostItemGivenBackIsWrittenWhenKept() {
        String kept = Paper.encodeBack(1000, "지갑", "할머니");
        assertEquals("할머니의 지갑", Paper.firstBack(kept, "{owner}의 {thing}", "누군가의 {thing}"));
        assertEquals("누군가의 우산", Paper.firstBack(Paper.encodeBack(1, "우산", ""), "{owner}의 {thing}", "누군가의 {thing}"));
        assertEquals(null, Paper.firstBack(null, "{owner}의 {thing}", "누군가의 {thing}"), "given back before it was kept: the line goes");
        assertEquals(null, Paper.firstBack("garbage", "{owner}의 {thing}", "{thing}"));
        Map<String, String> v = new HashMap<>();
        v.put("first-back", null);
        assertEquals(List.of("a"), Paper.fill(List.of("a", "처음 돌려준 것: {first-back}"), v));
    }

    @Test
    void theCharmHangsAheadAndToTheRightOfTheSeat() {
        double[] south = Charm.mirror(0, 64, 0, 0, 1, 1.5, 0.5);   // yaw 0 faces +z; the right-hand side is -x
        assertEquals(-0.5, south[0], 1e-9);
        assertEquals(65.5, south[1], 1e-9);
        assertEquals(1, south[2], 1e-9);
        double[] west = Charm.mirror(0, 64, 0, 90, 1, 0, 0);   // yaw 90 faces -x
        assertEquals(-1, west[0], 1e-9);
        assertEquals(0, west[2], 1e-9);
    }

    @Test
    void settingsHaveEveryLine() throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("secrets/lostkey.yml").toFile());
        assertTrue(y.getDouble("start.chance") > 0 && y.getDouble("start.chance") <= 0.05, "start.chance stays rare (2026-11 주인: 0.2% → 5%)");
        assertEquals(30, y.getInt("start.returns"));
        assertEquals(0, y.getLong("rewards.money"), "one of a kind, not money");
        assertTrue(y.getBoolean("charm.enabled"));
        assertEquals(3, y.getDoubleList("charm.offset").size(), "charm.offset: [앞, 위, 오른쪽]");
        assertTrue(y.getString("paper.first-back").contains("{owner}") && y.getString("paper.first-back").contains("{thing}"));
        assertTrue(y.getList("paper.pages").toString().contains("{first-back}"), "the paper names the first lost item given back");
        assertEquals("economy:lost_finder", y.getString("rewards.title"));
        for (int n = 0; n <= 5; n++) assertFalse(y.getStringList("whispers." + n).isEmpty(), "whispers." + n);
        assertTrue(String.join("", y.getStringList("whispers.1")).contains("{clue}"));
        assertTrue(String.join("", y.getStringList("whispers.2")).contains("{jamo}"));
        assertTrue(String.join("", y.getStringList("whispers.4")).contains("{clue}"));
        for (String t : ORDER) assertNotNull(y.getString("tags." + t), "tags." + t);
        for (String k : List.of("plain", "found", "oldest", "any", "plaque", "key.done-name", "lines.got", "lines.rattle", "lines.home",
                "lines.locked", "lines.locker-tag", "lines.fade", "paper.title", "paper.never-sold", "paper.sold"))
            assertFalse(y.getString(k, "").isBlank(), k);
        assertEquals(2, y.getList("paper.pages").size());
        assertFalse(y.getStringList("lines.end").isEmpty());
        assertFalse(y.getStringList("key.lore").isEmpty());
        // the key and the title exist
        YamlConfiguration items = YamlConfiguration.loadConfiguration(PACK.resolve("items/secrets.yml").toFile());
        assertTrue(items.contains(LostKey.ITEM.substring("economy:".length())));
        YamlConfiguration titles = YamlConfiguration.loadConfiguration(PACK.resolve("titles/secrets.yml").toFile());
        assertTrue(titles.getBoolean("lost_finder.hidden"));
        List<String> keys = new ArrayList<>(y.getConfigurationSection("tags").getKeys(false));
        assertEquals(ORDER, keys);
    }
}
