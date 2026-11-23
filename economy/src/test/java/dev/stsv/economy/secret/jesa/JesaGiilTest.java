package dev.stsv.economy.secret.jesa;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 후속 임무 「기일」: each family's circumstance as a change to the rite table (no fish, the cup of one who did not drink,
 * a favourite, three soups, one tablet), the week's family and circumstance, the lines the 족보 gains, and that jesa.yml's
 * pools name only things that exist.
 */
class JesaGiilTest {

    static final Table.Rules RULES = JesaTest.RULES;
    static final int W = Table.WEST_AT, MID = Table.MID, E = Table.EAST_AT;

    static YamlConfiguration yml() {
        return JesaTest.yml();
    }

    static List<Variant> variants() {
        return Variant.parse(yml().getMapList("sequel.variants"));
    }

    static Variant variant(String id) {
        for (Variant v : variants()) if (v.id().equals(id)) return v;
        throw new AssertionError("jesa.yml sequel.variants 에 없음: " + id);
    }

    static Set<String> items() throws Exception {
        Set<String> items = new HashSet<>();
        try (Stream<Path> s = Files.list(JesaTest.PACK.resolve("items"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).toList())
                for (String k : YamlConfiguration.loadConfiguration(f.toFile()).getKeys(false)) items.add("economy:" + k);
        }
        return items;
    }

    static Map<Integer, String> right() {
        return new TreeMap<>(JesaTest.right());
    }

    static Map<Integer, String> with(Map<Integer, String> t, int row, int col, String id) {
        Map<Integer, String> out = new TreeMap<>(t);
        if (id == null) out.remove(Table.slot(row, col));
        else out.put(Table.slot(row, col), id);
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // the circumstances
    // ------------------------------------------------------------------------------------------

    @Test
    void aTabletAloneChangesNothingOnTheTable() {
        for (String id : List.of("mother-alone", "father-alone")) {
            Variant v = variant(id);
            assertEquals(RULES, v.apply(RULES));
            assertTrue(v.judge(right(), RULES).ok());
            assertEquals(1, v.tablets().size());
        }
        assertTrue(variant("mother-alone").tablet("mother"));
        assertFalse(variant("mother-alone").tablet("father"));
        assertTrue(variant("no-fish").tablet("father") && variant("no-fish").tablet("mother"), "both stand unless the family says which");
    }

    @Test
    void whereNoFishIsEatenTheFishPlaceTakesAJeon() {
        Variant v = variant("no-fish");
        // the usual right table has a fish on it: it goes, and its place is owed
        Table.Verdict usual = v.judge(right(), RULES);
        assertEquals(Set.of(Table.slot(1, E)), usual.wrong());
        // the 전 east of the meat, nothing between: right
        Map<Integer, String> t = with(with(right(), 1, MID, null), 1, E, JesaTest.JEON);
        assertTrue(v.judge(t, RULES).ok());
        // two 전: the middle and the east
        assertTrue(v.judge(with(t, 1, MID, "economy:gamjajeon"), RULES).ok());
        // only meat: the east vessel is pointed at
        assertEquals(List.of(Table.slot(1, E)), v.judge(with(with(right(), 1, MID, null), 1, E, null), RULES).missing());
        // the 전 on the middle vessel with the meat west of it stands where the fish would (the middle is neither side)
        assertTrue(v.judge(with(right(), 1, E, null), RULES).ok());
        // a fish 전 is fish too
        assertFalse(v.judge(with(with(right(), 1, MID, null), 1, E, "economy:saengseonjeon"), RULES).ok());
        // the dried fish of row 3 stays (a 포 is not what they could not eat), also when the 포 is a 굴비
        assertTrue(v.judge(with(t, 3, W, "economy:gulbi"), RULES).ok());
        // the 전 on the west vessel is on the wrong side
        assertFalse(v.judge(with(with(with(right(), 1, MID, null), 1, E, null), 1, W, JesaTest.JEON), RULES).ok());
        // the secret's own table is not changed by any of this
        assertTrue(Table.judge(right(), RULES).ok());
        assertFalse(Table.judge(t, RULES).ok(), "the usual table still wants its fish and a 전 between");
    }

    @Test
    void theCupOfOneWhoDidNotDrink() {
        Variant v = variant("no-liquor");
        Table.Verdict usual = v.judge(right(), RULES);
        assertEquals(Set.of(Table.slot(0, MID)), usual.wrong(), "liquor has no business in that cup");
        assertTrue(v.judge(with(right(), 0, MID, JesaTest.SIKHYE), RULES).ok(), "식혜 in the cup, and still 식혜 east of the 포");
        assertTrue(v.judge(with(right(), 0, MID, "economy:flower_tea"), RULES).ok());
        // an empty cup is owed
        assertEquals(List.of(Table.slot(0, MID)), v.judge(with(right(), 0, MID, null), RULES).missing());
        // the other way round: one who only drank the cloudy kind
        Variant mak = variant("makgeolli");
        assertFalse(mak.judge(right(), RULES).ok());
        assertTrue(mak.judge(with(right(), 0, MID, "economy:makgeolli"), RULES).ok());
    }

    @Test
    void aFavouriteMustBeThereInItsOwnRow() {
        Variant v = variant("fav-japchae");
        Table.Verdict none = v.judge(right(), RULES);
        assertTrue(none.wrong().isEmpty());
        assertEquals(List.of(Table.slot(3, MID)), none.missing(), "the empty middle of row 4 is pointed at");
        assertTrue(v.judge(with(right(), 3, MID, "economy:japchae"), RULES).ok());
        // in another row it has no business, and is still owed in its own
        Table.Verdict elsewhere = v.judge(with(right(), 4, MID, "economy:japchae"), RULES);
        assertEquals(Set.of(Table.slot(4, MID)), elsewhere.wrong());
        assertEquals(List.of(Table.slot(3, MID)), elsewhere.missing());
        // the 전 they liked: another 전 does not do
        Variant jeon = variant("fav-hobakjeon");
        assertTrue(jeon.judge(right(), RULES).ok(), "the usual right table already has 호박전");
        assertFalse(jeon.judge(with(right(), 1, MID, "economy:pajeon"), RULES).ok());
        // the fruit they loved: an apple is not it
        Variant red = variant("strawberry");
        assertFalse(red.judge(right(), RULES).ok());
        assertTrue(red.judge(with(right(), 4, E, "economy:strawberry"), RULES).ok());
        for (String id : List.of("fav-gyeranmari", "fav-injeolmi", "fav-grapes")) {
            assertFalse(variant(id).judge(right(), RULES).ok(), id);
            assertTrue(variant(id).judge(variant(id).sample(RULES), RULES).ok(), id);
        }
    }

    @Test
    void threeSoupsOnAGreatRite() {
        Variant v = variant("samtang");
        Table.Verdict one = v.judge(right(), RULES);
        assertTrue(one.wrong().isEmpty());
        assertEquals(List.of(Table.slot(2, W), Table.slot(2, E)), one.missing(), "two more deep bowls are owed");
        Map<Integer, String> three = with(with(right(), 2, W, "economy:doenjang_jjigae"), 2, E, "economy:kalguksu");
        assertTrue(v.judge(three, RULES).ok());
        // the same soup twice is still wrong, and then one is owed again
        Table.Verdict twice = v.judge(with(three, 2, E, "economy:doenjang_jjigae"), RULES);
        assertEquals(1, twice.wrong().size());
        assertEquals(1, twice.missing().size());
        // no soup at all: all three are pointed at, the middle first
        Table.Verdict empty = v.judge(with(right(), 2, MID, null), RULES);
        assertEquals(List.of(Table.slot(2, MID), Table.slot(2, W), Table.slot(2, E)), empty.missing());
    }

    @Test
    void whatTheServerDoesNotHaveIsNeverAsked() throws Exception {
        Set<String> items = items();
        Predicate<String> exists = id -> id.startsWith("minecraft:") || items.contains(id);
        List<Variant> all = variants();
        List<Variant> usable = all.stream().filter(v -> v.usable(RULES, exists)).toList();
        assertTrue(usable.size() >= 8, "a pool of circumstances: " + usable.size());
        for (Variant v : usable) {
            Variant here = v.existing(exists);
            Map<Integer, String> sample = here.sample(RULES);
            assertTrue(here.judge(sample, RULES).ok(), v.id());
            for (String id : sample.values()) assertTrue(exists.test(id), v.id() + ": " + id);
            for (String id : here.all()) assertTrue(exists.test(id), v.id() + " 에 없는 아이템: " + id);
            assertFalse(v.say().isEmpty(), v.id());
            for (String line : v.say()) {
                assertTrue(line.startsWith("…"), v.id() + ": " + line);
                assertFalse(line.contains("!"), line);
            }
            for (String kind : v.replace().keySet()) assertTrue(Variant.KINDS.contains(kind), v.id() + ": 없는 종류 " + kind);
            for (int row : v.must().values()) assertTrue(row >= 0 && row < Table.ROWS, v.id());
            for (String t : v.tablets()) assertTrue(t.equals("father") || t.equals("mother"), v.id());
        }
        // 설 · 한가위 wait for a 떡국 and a 송편 that this server does not have (yet)
        Set<String> skipped = new HashSet<>();
        for (Variant v : all) if (!usable.contains(v)) skipped.add(v.id());
        assertEquals(Set.of("seol", "chuseok"), skipped);
        // …and come alive when it does
        Predicate<String> withTteokguk = exists.or("economy:tteokguk"::equals);
        Variant seol = variant("seol");
        assertTrue(seol.usable(RULES, withTteokguk));
        assertTrue(seol.judge(with(right(), 0, W, "economy:tteokguk"), RULES).ok());
        assertEquals(Set.of(Table.slot(0, W)), seol.judge(right(), RULES).wrong(), "plain rice at 설");
        // every circumstance is a different one, and no usual right table is refused for a reason the family did not give
        Set<String> ids = new HashSet<>();
        for (Variant v : all) assertTrue(ids.add(v.id()), "twice: " + v.id());
    }

    @Test
    void parsingIsForgiving() {
        List<Variant> v = Variant.parse(List.of(Map.of("say", "…말.", "must", List.of("economy:japchae 4", "nonsense"), "soups", 9)));
        assertEquals("variant1", v.getFirst().id());
        assertEquals(Map.of("economy:japchae", 3), v.getFirst().must());
        assertEquals(3, v.getFirst().soups(), "never more soups than deep bowls");
        assertFalse(Variant.parse(List.of(Map.of("id", "mute"))).getFirst().usable(RULES, id -> true), "a family that says nothing is not asked");
    }

    // ------------------------------------------------------------------------------------------
    // the week
    // ------------------------------------------------------------------------------------------

    @Test
    void theWeekFixesTheFamilyAndRecentOnesDoNotComeBack() {
        UUID me = new UUID(7, 9);
        List<String> ids = Giil.families(yml().getMapList("sequel.families")).stream().map(Giil.Family::id).toList();
        assertTrue(ids.size() >= 8);
        assertEquals(Giil.seed("2026-W48", me), Giil.seed("2026-W48", me));
        assertNotEquals(Giil.seed("2026-W48", me), Giil.seed("2026-W49", me));
        assertEquals(Giil.pick(Giil.seed("2026-W48", me), ids, List.of()), Giil.pick(Giil.seed("2026-W48", me), ids, List.of()));
        String recent = "";
        Set<String> seen = new HashSet<>();
        for (int week = 1; week <= 24; week++) {
            String id = Giil.pick(Giil.seed("2026-W" + week, me), ids, Giil.list(recent));
            assertFalse(Giil.list(recent).contains(id), "week " + week + " repeats " + id);
            seen.add(id);
            recent = Giil.remember(recent, id, 5);
            assertTrue(Giil.list(recent).size() <= 5);
        }
        assertEquals(ids.size(), seen.size(), "every family comes round");
        assertNull(Giil.pick(1, List.of(), List.of()));
        assertEquals("a", Giil.pick(5, List.of("a"), List.of("a")), "only one: it comes again");
    }

    @Test
    void theTwoHousesNeverStandAtOnce() {
        // the weekly house only when the secret's own cannot stand: solved (4 of 3), or never begun
        assertTrue(Giil.free(4, 3));
        assertTrue(Giil.free(0, 3));
        for (int stage = 1; stage <= 3; stage++) assertFalse(Giil.free(stage, 3));
        // and their saved keys differ: "plate.3" under the secret, "sequel.x.plate.3" under the mission's notes
        for (int v = 0; v < Vessel.COUNT; v++) {
            assertNotEquals(Vessel.key(v), JesaSequel.PRE + Vessel.key(v));
            assertTrue((JesaSequel.PRE + Vessel.key(v)).startsWith("sequel.x."), "the mission's notes: wiped with the mission, after its things went back");
            assertFalse(Vessel.key(v).startsWith("sequel."));
        }
        assertEquals(0, Giil.ASKED);
        assertTrue(Giil.HOUSE < Giil.ACCEPTED);
    }

    @Test
    void theRegisterGainsALineAFamily() {
        List<Giil.Family> families = Giil.families(yml().getMapList("sequel.families"));
        Map<String, Giil.Family> byId = new HashMap<>();
        for (Giil.Family f : families) byId.put(f.id(), f);
        String kept = "";
        for (int i = 0; i < 6; i++) kept = Giil.add(kept, new Giil.Entry(families.get(i).id(), "2026년 11월 " + (i + 1) + "일"), 40);
        List<Giil.Entry> entries = Giil.entries(kept);
        assertEquals(6, entries.size());
        assertEquals(families.getFirst().id(), entries.getFirst().family());
        assertEquals("2026년 11월 6일", entries.getLast().date());
        List<String> header = yml().getStringList("sequel.jokbo.header");
        List<List<String>> pages = Giil.pages(entries, byId, header, 3);
        assertEquals(2, pages.size());
        assertEquals(header.getFirst(), pages.getFirst().getFirst());
        for (List<String> page : pages) {
            assertTrue(page.size() <= 14, "fits a book page: " + page.size());
            for (String l : page) assertFalse(l.contains("{date}"));
        }
        assertTrue(String.join("\n", pages.getLast()).contains("2026년 11월 6일"));
        assertEquals(3, yml().getInt("sequel.jokbo.per-page"), "three families a page keep it within fourteen lines");
        assertTrue(String.join("\n", pages.getFirst()).contains(families.getFirst().name()));
        // a family the file no longer has is still a line; the oldest go past the limit
        assertTrue(String.join("\n", Giil.pages(List.of(new Giil.Entry("gone", "어느 날")), byId, List.of(), 4).getFirst()).contains("gone"));
        String few = "";
        for (int i = 0; i < 5; i++) few = Giil.add(few, new Giil.Entry("f" + i, "d"), 3);
        assertEquals(List.of("f2", "f3", "f4"), Giil.entries(few).stream().map(Giil.Entry::family).toList());
        assertTrue(Giil.entries("").isEmpty());
    }

    @Test
    void theFileIsTheNormalTierWithEightFamilies() throws Exception {
        var s = yml().getConfigurationSection("sequel");
        assertTrue(s != null && s.getBoolean("enabled"));
        List<?> money = s.getList("reward.money");
        assertEquals(80000, ((Number) money.get(0)).intValue());
        assertEquals(150000, ((Number) money.get(1)).intValue());
        assertTrue(s.getStringList("types").contains(yml().getString("start.type")), "the grandmother's kind is one of them");
        YamlConfiguration bus = YamlConfiguration.loadConfiguration(JesaTest.PACK.resolve("bus.yml").toFile());
        String flat = Files.readString(JesaTest.PACK.resolve("bus.yml"));
        for (String t : s.getStringList("types")) assertTrue(flat.contains("\n  " + t + ":"), "bus.yml 에 없는 손님 종류: " + t + " (" + bus.getKeys(false).size() + ")");
        assertTrue(s.getStringList("asks").size() >= 3);
        for (String a : s.getStringList("asks")) assertTrue(a.contains("우리 집도 한 번만"), a);
        List<Giil.Family> families = Giil.families(s.getMapList("families"));
        assertTrue(families.size() >= 8);
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (Giil.Family f : families) {
            assertTrue(ids.add(f.id()) && names.add(f.name()), f.id());
            assertTrue(f.father().startsWith("顯考") && f.mother().startsWith("顯妣"), f.id());
            assertNotEquals(yml().getString("tablets.mother"), f.mother(), "another family's tablet");
            for (String line : List.of(f.house(), f.meal(), f.thanks())) assertTrue(line.startsWith("…") && !line.contains("!"), f.id() + ": " + line);
            assertTrue(f.jokbo().stream().anyMatch(l -> l.contains(f.name())), f.id());
            assertTrue(f.jokbo().size() <= 3);
        }
        assertTrue(s.getLong("retry") <= yml().getLong("retry-seconds"));
        List<String> made = new ArrayList<>(s.getStringList("lines.book"));
        assertTrue(made.isEmpty() && s.isString("lines.book"));
    }
}
