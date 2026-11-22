package dev.stsv.economy.secret.jesa;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 주인 없는 제사: the judging of the table by the real rules (반서갱동 · 어동육서 · 좌포우혜 · 홍동백서, the taboos), the
 * remembered layout, where the house may stand, and that jesa.yml names only things that exist.
 */
class JesaTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");
    static final Table.Rules RULES = Table.Rules.defaults();

    static final String RICE = "economy:rice", JIRI = "economy:bogeo_jiri", EOMUK = "economy:eomuktang", CHEONGJU = "economy:cheongju",
            BULGOGI = "economy:bulgogi", GUI = "economy:godeungeo_gui", JEON = "economy:hobakjeon", BUGEO = "economy:dried_pollack",
            SIKHYE = "economy:sikhye", MELON = "economy:muskmelon", APPLE = "minecraft:apple";

    /** A right table: row, column, id … */
    static Map<Integer, String> right() {
        Map<Integer, String> t = new TreeMap<>();
        t.put(Table.slot(0, 1), RICE);
        t.put(Table.slot(0, 4), CHEONGJU);
        t.put(Table.slot(0, 7), JIRI);
        t.put(Table.slot(1, 2), BULGOGI);
        t.put(Table.slot(1, 4), JEON);
        t.put(Table.slot(1, 6), GUI);
        t.put(Table.slot(2, 4), EOMUK);
        t.put(Table.slot(3, 0), BUGEO);
        t.put(Table.slot(3, 8), SIKHYE);
        t.put(Table.slot(4, 3), MELON);
        t.put(Table.slot(4, 5), APPLE);
        return t;
    }

    static YamlConfiguration yml() {
        return YamlConfiguration.loadConfiguration(PACK.resolve("secrets/jesa.yml").toFile());
    }

    @Test
    void aRightTableIsAccepted() {
        Table.Verdict v = Table.judge(right(), RULES);
        assertTrue(v.ok(), v.toString());
        assertTrue(Table.judge(Table.sample(RULES), RULES).ok(), "the sample table is a right one");
    }

    @Test
    void theOptionalPlacesMayBeEmptyOrFilled() {
        Map<Integer, String> t = right();
        t.put(Table.slot(3, 4), "economy:japchae");          // 나물 between 포 and 혜
        t.put(Table.slot(4, 4), "economy:injeolmi");         // a rice cake between the fruits
        t.put(Table.slot(1, 3), "economy:pajeon");           // a second 전
        t.put(Table.slot(0, 2), RICE);                       // two bowls (합설)
        t.put(Table.slot(0, 6), JIRI);
        t.put(Table.slot(0, 0), "economy:makgeolli");        // liquor may stand anywhere in its row
        t.put(Table.slot(2, 0), "economy:kalguksu");         // more than one 탕, all different
        assertTrue(Table.judge(t, RULES).ok(), Table.judge(t, RULES).toString());
        // 나물 outside the two is wrong
        t.put(Table.slot(3, 4), null);
        t.remove(Table.slot(3, 4));
        t.put(Table.slot(3, 0), "economy:japchae");
        t.put(Table.slot(3, 1), BUGEO);
        assertEquals(Set.of(Table.slot(3, 0)), Table.judge(t, RULES).wrong());
    }

    @Test
    void fishEastMeatWest() {
        Map<Integer, String> t = right();
        t.put(Table.slot(1, 2), GUI);
        t.put(Table.slot(1, 6), BULGOGI);
        Table.Verdict v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(1, 2), Table.slot(1, 6)), v.wrong(), "both are on the wrong side");
        assertTrue(v.missing().isEmpty(), "they are there, only misplaced");
        // the same by order alone
        Table.Rules loose = new Table.Rules(RULES.rice(), RULES.soup(), RULES.liquor(), RULES.meat(), RULES.fish(), RULES.jeon(), RULES.dried(),
                RULES.sikhye(), RULES.namul(), RULES.pale(), RULES.red(), RULES.cake(), RULES.taboo(), false);
        assertEquals(Set.of(Table.slot(1, 2), Table.slot(1, 6)), Table.judge(t, loose).wrong());
        // by order alone a table pushed to one end is fine; by halves its east dishes are not east
        Map<Integer, String> left = new TreeMap<>();
        int[] cols = {0, 1, 2};
        left.put(Table.slot(0, cols[0]), RICE);
        left.put(Table.slot(0, cols[1]), CHEONGJU);
        left.put(Table.slot(0, cols[2]), JIRI);
        left.put(Table.slot(1, cols[0]), BULGOGI);
        left.put(Table.slot(1, cols[1]), JEON);
        left.put(Table.slot(1, cols[2]), GUI);
        left.put(Table.slot(2, 0), EOMUK);
        left.put(Table.slot(3, cols[0]), BUGEO);
        left.put(Table.slot(3, cols[2]), SIKHYE);
        left.put(Table.slot(4, cols[0]), MELON);
        left.put(Table.slot(4, cols[2]), APPLE);
        assertTrue(Table.judge(left, loose).ok());
        assertEquals(Set.of(Table.slot(0, 2), Table.slot(1, 2), Table.slot(3, 2), Table.slot(4, 2)), Table.judge(left, RULES).wrong());
    }

    @Test
    void theJeonLiesBetweenMeatAndFish() {
        Map<Integer, String> t = right();
        t.remove(Table.slot(1, 4));
        assertEquals(List.of(Table.slot(1, Table.MID)), Table.judge(t, RULES).missing(), "no 전: the middle of that row is owed");
        t.put(Table.slot(1, 0), JEON);
        Table.Verdict v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(1, 0)), v.wrong(), "a 전 outside the two is misplaced");
        assertTrue(v.missing().isEmpty());
    }

    @Test
    void aFishWhoseNameEndsInChiIsNeverOffered() throws Exception {
        // worked out from the fish as fish.yml names them
        YamlConfiguration fish = YamlConfiguration.loadConfiguration(PACK.resolve("fish.yml").toFile());
        List<String> chi = new ArrayList<>();
        for (String id : fish.getConfigurationSection("species").getKeys(false))
            if (Table.endsIn(fish.getString("species." + id + ".name"), "치")) chi.add(Table.norm(fish.getString("species." + id + ".item", "economy:" + id)));
        for (String id : List.of("saury", "filefish", "hairtail", "marlin", "sailfish", "snakehead", "giant_snakehead"))
            assertTrue(chi.contains("economy:" + id), id);
        assertFalse(chi.contains("economy:croaker"));
        assertFalse(chi.contains("economy:pollack"));
        Table.Rules r = RULES.alsoTaboo(chi);
        for (String allowed : r.fish()) assertFalse(chi.contains(allowed), allowed + " is offered and is a 치 fish");
        Map<Integer, String> t = right();
        t.put(Table.slot(1, 7), "economy:hairtail");
        assertEquals(Set.of(Table.slot(1, 7)), Table.judge(t, r).wrong());
        t.remove(Table.slot(1, 7));
        t.put(Table.slot(4, 4), "economy:saury");   // wherever it lies
        assertEquals(Set.of(Table.slot(4, 4)), Table.judge(t, r).wrong());
        assertTrue(Table.endsIn(" 거대 가물치 ", "치"));
        assertFalse(Table.endsIn("참돔", "치"));
        assertFalse(Table.endsIn("참치", ""));
        assertFalse(Table.endsIn(null, "치"));
    }

    @Test
    void garlicAndChiliAndRedBeansAreTaboo() {
        Map<Integer, String> t = right();
        t.put(Table.slot(2, 4), "economy:miyeokguk");   // a soup, but with garlic
        Table.Verdict v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(2, 4)), v.wrong());
        assertEquals(List.of(Table.slot(2, Table.MID)), v.missing(), "and the 탕 row has no soup then");
        t = right();
        t.put(Table.slot(0, 7), "economy:kimchi_jjigae");
        v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(0, 7)), v.wrong());
        assertEquals(List.of(Table.slot(0, Table.EAST_AT)), v.missing());
        t = right();
        t.put(Table.slot(4, 4), "economy:gosa_tteok");
        t.put(Table.slot(3, 4), "economy:kimchi");
        t.put(Table.slot(1, 8), "economy:garlic");
        assertEquals(Set.of(Table.slot(4, 4), Table.slot(3, 4), Table.slot(1, 8)), Table.judge(t, RULES).wrong());
    }

    @Test
    void redFruitEastPaleFruitWest() {
        Map<Integer, String> t = right();
        t.put(Table.slot(4, 3), APPLE);
        t.put(Table.slot(4, 5), MELON);
        assertEquals(Set.of(Table.slot(4, 3), Table.slot(4, 5)), Table.judge(t, RULES).wrong());
        t = right();
        t.put(Table.slot(4, 1), "economy:strawberry");   // one red thing on the west, beside a right pair
        assertEquals(Set.of(Table.slot(4, 1)), Table.judge(t, RULES).wrong());
        // the middle place is neither half: what stands on it is judged by its neighbour (a pair one place off-centre is right)
        t = right();
        t.put(Table.slot(4, 4), APPLE);
        t.remove(Table.slot(4, 5));
        assertTrue(Table.judge(t, RULES).ok(), "pale on 3, red on 4");
        t = right();
        t.put(Table.slot(4, 4), MELON);
        t.remove(Table.slot(4, 3));
        assertTrue(Table.judge(t, RULES).ok(), "pale on 4, red on 5");
        t.put(Table.slot(4, 4), APPLE);
        t.put(Table.slot(4, 5), MELON);                  // but not the wrong way round about it
        assertEquals(Set.of(Table.slot(4, 5)), Table.judge(t, RULES).wrong());
        t = right();
        t.put(Table.slot(4, 4), APPLE);
        t.put(Table.slot(4, 5), MELON);
        t.remove(Table.slot(4, 3));
        assertFalse(Table.judge(t, RULES).ok());
        // rice on the middle with the soup beside it; meat and fish side by side leave the 전 no room between them
        t = right();
        t.remove(Table.slot(0, 1));
        t.remove(Table.slot(0, 7));
        t.put(Table.slot(0, 4), RICE);
        t.put(Table.slot(0, 5), JIRI);
        t.put(Table.slot(0, 3), CHEONGJU);
        assertTrue(Table.judge(t, RULES).ok(), Table.judge(t, RULES).toString());
        t.put(Table.slot(1, 2), JEON);
        t.put(Table.slot(1, 4), BULGOGI);
        t.put(Table.slot(1, 5), GUI);
        t.remove(Table.slot(1, 6));
        assertEquals(Set.of(Table.slot(1, 2)), Table.judge(t, RULES).wrong());
    }

    @Test
    void riceWestSoupEast() {
        Map<Integer, String> t = right();
        t.put(Table.slot(0, 1), JIRI);
        t.put(Table.slot(0, 7), RICE);   // as the living eat
        assertEquals(Set.of(Table.slot(0, 1), Table.slot(0, 7)), Table.judge(t, RULES).wrong());
    }

    @Test
    void theSameSoupTwiceIsWrong() {
        Map<Integer, String> t = right();
        t.put(Table.slot(2, 4), JIRI);   // the 갱 of row 0 again
        Table.Verdict v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(2, 4)), v.wrong());
        assertTrue(v.missing().isEmpty());
        t = right();
        t.put(Table.slot(2, 6), EOMUK);  // the same 탕 twice in its own row: the second goes
        assertEquals(Set.of(Table.slot(2, 6)), Table.judge(t, RULES).wrong());
        t = right();
        t.put(Table.slot(2, 6), BULGOGI);
        assertEquals(Set.of(Table.slot(2, 6)), Table.judge(t, RULES).wrong(), "only soups in the 탕 row");
    }

    @Test
    void aMisplacedSoupDoesNotTakeTheTangWithIt() {
        Map<Integer, String> t = right();
        t.put(Table.slot(0, 2), EOMUK);   // a second 갱 on the rice's side: it goes, the same soup in the 탕 row stays
        Table.Verdict v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(0, 2)), v.wrong());
        assertTrue(v.missing().isEmpty());
        t.put(Table.slot(0, 2), "economy:kimchi_jjigae");   // nor does a taboo one
        assertEquals(Set.of(Table.slot(0, 2)), Table.judge(t, RULES).wrong());
    }

    @Test
    void anythingUnknownIsWrongWhereverItLies() {
        for (int s = 0; s < Table.SLOTS; s++) {
            Map<Integer, String> t = right();
            t.put(s, "minecraft:diamond_sword");
            assertTrue(Table.judge(t, RULES).wrong().contains(s), "a thing no list names, at " + s);
            t.put(s, "economy:no_such_dish");
            assertTrue(Table.judge(t, RULES).wrong().contains(s), "an id that does not exist, at " + s);
        }
        // plain dried fish is a 포 like the dried pollack
        Map<Integer, String> t = right();
        t.put(Table.slot(3, 0), "economy:dried_fish");
        assertTrue(Table.judge(t, RULES).ok());
    }

    @Test
    void whatIsOwedIsPointedAt() {
        Map<Integer, String> t = right();
        t.remove(Table.slot(0, 1));
        Table.Verdict v = Table.judge(t, RULES);
        assertTrue(v.wrong().isEmpty());
        assertEquals(List.of(Table.slot(0, Table.WEST_AT)), v.missing(), "no rice: the west of the first row");
        assertFalse(v.ok());
        t.remove(Table.slot(0, 4));
        assertEquals(List.of(Table.slot(0, Table.WEST_AT), Table.slot(0, Table.MID)), Table.judge(t, RULES).missing(), "and no liquor");
        Table.Verdict empty = Table.judge(Map.of(), RULES);
        assertTrue(empty.wrong().isEmpty());
        assertEquals(11, empty.missing().size(), "rice liquor soup · meat 전 fish · 탕 · 포 혜 · pale red");
    }

    @Test
    void whatHasNoBusinessInARowIsWrong() {
        Map<Integer, String> t = right();
        t.put(Table.slot(0, 8), "minecraft:dirt");
        t.put(Table.slot(3, 4), CHEONGJU);       // liquor belongs to the first row
        t.put(Table.slot(4, 0), SIKHYE);
        assertEquals(Set.of(Table.slot(0, 8), Table.slot(3, 4), Table.slot(4, 0)), Table.judge(t, RULES).wrong());
        // gulbi is a fish in the 적 row and a 포 in its own
        t = right();
        t.put(Table.slot(1, 7), "economy:gulbi");
        t.put(Table.slot(3, 1), "economy:gulbi");
        assertTrue(Table.judge(t, RULES).ok());
    }

    @Test
    void theLayoutIsRemembered() {
        Map<Integer, String> t = right();
        String saved = Table.encode(t);
        assertEquals(t, Table.decode(saved));
        assertTrue(saved.startsWith("1=economy:rice;4=economy:cheongju"), saved);
        assertTrue(Table.decode(null).isEmpty());
        assertTrue(Table.decode("").isEmpty());
        assertEquals(Map.of(3, "minecraft:apple"), Table.decode("x=1;3=minecraft:apple;99=economy:rice;=;7="));
        Map<Integer, String> two = new TreeMap<>(t);
        two.put(Table.slot(0, 2), RICE);
        assertEquals(2, Table.needs(two).get(RICE));
        assertTrue(Table.lacking(two, Table.needs(two)).isEmpty());
        Map<String, Integer> have = new java.util.HashMap<>(Table.needs(two));
        have.put(RICE, 1);
        have.remove(APPLE);
        assertEquals(Map.of(RICE, 1, APPLE, 1), Table.lacking(two, have));
    }

    @Test
    void theTableReadsInTheWorldAsInTheWindow() {
        assertTrue(Table.at(Table.slot(0, 0))[0] < 0, "column 0 is the west end (-x)");
        assertTrue(Table.at(Table.slot(0, 8))[0] > 0, "column 8 is the east end (+x)");
        assertEquals(0, Table.at(Table.slot(2, Table.MID))[0], 1e-9);
        assertTrue(Table.at(Table.slot(0, 4))[1] < Table.at(Table.slot(4, 4))[1], "row 0 is the north row, by the screen");
        for (int s = 0; s < Table.SLOTS; s++) {
            double[] at = Table.at(s);
            assertTrue(Math.abs(at[0]) < 1.2 && Math.abs(at[1]) < 0.7, "on the table top: " + s);
            assertEquals(s, Table.slot(Table.row(s), Table.col(s)));
        }
        assertEquals(-1, Table.rollTo(Table.slot(1, 0)));
        assertEquals(1, Table.rollTo(Table.slot(1, 8)));
        assertNotEquals(0, Table.rollTo(Table.slot(1, 4)));
    }

    @Test
    void whereTheHouseMayStand() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a"), b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        List<int[]> one = Place.candidates(Place.seed(a, 7), 14, 30, 1, 0, 10), again = Place.candidates(Place.seed(a, 7), 14, 30, 1, 0, 10);
        assertFalse(one.isEmpty());
        assertEquals(one.size(), again.size());
        for (int i = 0; i < one.size(); i++) assertArrayEquals(one.get(i), again.get(i), "the same for one player at one stop");
        boolean differs = false;
        List<int[]> other = Place.candidates(Place.seed(b, 7), 14, 30, 1, 0, 10);
        for (int i = 0; i < Math.min(8, one.size()); i++) if (one.get(i)[0] != other.get(i)[0] || one.get(i)[1] != other.get(i)[1]) differs = true;
        assertTrue(differs, "another player, another order");
        assertNotEquals(Place.seed(a, 7), Place.seed(a, 8));
        for (int[] d : one) {
            double far = Math.hypot(d[0], d[1]);
            assertTrue(far >= 14 && far <= 30, "14..30 blocks from the stop");
            assertTrue(Math.abs(d[1]) >= 10, "off the road, which runs along x here");
        }
        assertTrue(Place.candidates(1, 14, 30, 0, 0, 10).size() > one.size(), "no road known: nothing is left out for it");
        assertTrue(Place.flat(new int[] {64, 64, 64}));
        assertFalse(Place.flat(new int[] {64, 65, 64}));
        assertFalse(Place.flat(new int[0]));
        String saved = Place.encode("world", -12, 70, 340);
        assertEquals("world", Place.world(saved));
        assertArrayEquals(new int[] {-12, 70, 340}, Place.decode(saved));
        assertNull(Place.decode("world 1 2"));
        assertNull(Place.decode(null));
        assertNull(Place.decode("world a b c"));
    }

    @Test
    void nightAndTheSideOneBowsFrom() {
        assertTrue(Place.within(13000, 13000, 23000));
        assertTrue(Place.within(18000, 13000, 23000));
        assertFalse(Place.within(23000, 13000, 23000));
        assertFalse(Place.within(6000, 13000, 23000));
        assertTrue(Place.within(24000 + 14000, 13000, 23000), "a world's full time, not only the time of day");
        assertTrue(Place.within(1000, 22000, 2000), "wrapping past the day's end");
        // the screen is north of the table: one bows from the south
        assertTrue(Place.before(0.5, 3.0, 0.5, 0.5, 5));
        assertFalse(Place.before(0.5, -2.0, 0.5, 0.5, 5), "from behind the screen it does not count");
        assertFalse(Place.before(0.5, 9.0, 0.5, 0.5, 5), "too far");
        assertFalse(Place.before(0.5, 0.6, 0.5, 0.5, 5), "standing in the table");
    }

    @Test
    void theBlessingsWait() {
        Random r = new Random(3);
        for (int i = 0; i < 200; i++) {
            long w = Blessing.between(List.of(360, 720), 540, r);
            assertTrue(w >= 360 && w <= 720);
        }
        assertEquals(30, Blessing.between(List.of(30), 540, r));
        assertEquals(540, Blessing.between(List.of(), 540, r));
        long w = Blessing.between(List.of(40, 20), 540, r);
        assertTrue(w >= 20 && w <= 40);
    }

    /** Every id jesa.yml names is an item that exists, the file's lists are the code's own, and a right table can be laid. */
    @Test
    void theFileNamesOnlyThingsThatExist() throws Exception {
        Set<String> items = new HashSet<>();
        try (Stream<Path> s = Files.list(PACK.resolve("items"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).toList())
                for (String k : YamlConfiguration.loadConfiguration(f.toFile()).getKeys(false)) items.add("economy:" + k);
        }
        YamlConfiguration y = yml();
        Table.Rules r = Table.Rules.read(y.getConfigurationSection("table"));
        for (String id : r.all()) assertTrue(id.startsWith("minecraft:") || items.contains(id), "없는 아이템: " + id);
        assertEquals(Set.of("minecraft:apple"), new HashSet<>(r.all().stream().filter(id -> id.startsWith("minecraft:")).toList()));
        assertEquals(RULES, r, "jesa.yml 의 목록과 코드의 기본값이 같아야 함");
        assertTrue(Table.judge(Table.sample(r), r).ok());
        assertTrue(Table.judge(right(), r).ok());
        // nothing that may be offered is itself taboo
        Set<String> offered = new HashSet<>(r.all());
        offered.removeAll(r.taboo());
        for (String id : r.taboo()) assertFalse(offered.contains(id) && (r.soup().contains(id) || r.fish().contains(id)), id);
        for (Set<String> kind : List.of(r.rice(), r.soup(), r.liquor(), r.meat(), r.fish(), r.jeon(), r.dried(), r.sikhye(), r.namul(), r.pale(),
                r.red(), r.cake())) for (String id : kind) assertFalse(r.taboo().contains(id), "올릴 수 있는 것이 금기에도 있음: " + id);
        assertTrue(items.contains(Jesa.JOKBO));
        assertEquals("economy:jesa_host", y.getString("rewards.title"));
        assertEquals(0, y.getLong("rewards.money"));
        assertTrue(y.getBoolean("enabled"));
        for (String key : List.of("solved-line", "too-late", "lines.overhear", "lines.house", "lines.day", "lines.incense-out", "lines.relit",
                "lines.empty", "lines.not-in-hand", "lines.laid", "lines.bow-once", "lines.out", "lines.no-more", "lines.not-this", "lines.gone",
                "lines.meal-1", "lines.meal-2", "lines.meal-3", "lines.meal-4", "lines.dawn", "tablets.father", "tablets.mother", "gui.title",
                "gui.screen", "gui.hand", "jokbo.name", "jokbo.title", "jokbo.author"))
            assertTrue(y.isString(key) && !y.getString(key).isBlank(), "jesa.yml 에 없음: " + key);
        // the second tablet is a wife's (顯妣): the quiet clue
        assertTrue(y.getString("tablets.father").startsWith("顯考"));
        assertTrue(y.getString("tablets.mother").startsWith("顯妣"));
        // the register ends with the one who set the table, and its lines fit a book page
        List<?> pages = y.getList("jokbo.pages");
        assertFalse(pages.isEmpty());
        List<?> last = (List<?>) pages.getLast();
        assertTrue(last.stream().anyMatch(l -> String.valueOf(l).contains("{player}")));
        assertTrue(last.stream().anyMatch(l -> String.valueOf(l).contains("{date}")));
        for (Object page : pages) assertTrue(((List<?>) page).size() <= 14, "a book page holds 14 lines");
        assertEquals(2, y.getStringList("blessing.skins").size());
        // the grandmother is a bus passenger kind, and the elders' skins are bus skins
        YamlConfiguration bus = YamlConfiguration.loadConfiguration(PACK.resolve("bus.yml").toFile());
        assertTrue(bus.isConfigurationSection("types." + y.getString("start.type")), "bus.yml 에 없는 손님 종류");
        Set<String> skins = new HashSet<>();
        for (String t : bus.getConfigurationSection("types").getKeys(false)) skins.addAll(bus.getStringList("types." + t + ".skins"));
        for (String skin : y.getStringList("blessing.skins")) assertTrue(skins.contains(skin), "bus.yml 에 없는 스킨: " + skin);
    }
}
