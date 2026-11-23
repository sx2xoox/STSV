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
 * 주인 없는 제사: the judging of what lies on the vessels by the real rules (반서갱동 · 어동육서 · 좌포우혜 · 홍동백서, the
 * taboos), where the vessels stand and how what lies on one is kept, where the house may stand (never on water), and that
 * jesa.yml names only things that exist.
 */
class JesaTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");
    static final Table.Rules RULES = Table.Rules.defaults();
    static final int W = Table.WEST_AT, MID = Table.MID, E = Table.EAST_AT;

    static final String RICE = "economy:rice", JIRI = "economy:bogeo_jiri", EOMUK = "economy:eomuktang", CHEONGJU = "economy:cheongju",
            BULGOGI = "economy:bulgogi", GUI = "economy:godeungeo_gui", JEON = "economy:hobakjeon", BUGEO = "economy:dried_pollack",
            SIKHYE = "economy:sikhye", MELON = "economy:muskmelon", APPLE = "minecraft:apple";

    /** A right table: row, vessel (west, middle, east), id … */
    static Map<Integer, String> right() {
        Map<Integer, String> t = new TreeMap<>();
        t.put(Table.slot(0, W), RICE);
        t.put(Table.slot(0, MID), CHEONGJU);
        t.put(Table.slot(0, E), JIRI);
        t.put(Table.slot(1, W), BULGOGI);
        t.put(Table.slot(1, MID), JEON);
        t.put(Table.slot(1, E), GUI);
        t.put(Table.slot(2, MID), EOMUK);
        t.put(Table.slot(3, W), BUGEO);
        t.put(Table.slot(3, E), SIKHYE);
        t.put(Table.slot(4, W), MELON);
        t.put(Table.slot(4, E), APPLE);
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
        Map<Integer, String> sample = Table.sample(RULES);
        assertEquals(RICE, sample.get(Table.slot(0, W)), "rice in the west bowl");
        assertTrue(RULES.liquor().contains(sample.get(Table.slot(0, MID))), "liquor in the cup");
        assertTrue(RULES.soup().contains(sample.get(Table.slot(0, E))), "soup in the east bowl");
        assertNotEquals(sample.get(Table.slot(0, E)), sample.get(Table.slot(2, MID)), "the 탕 is another soup");
    }

    @Test
    void theOptionalPlacesMayBeEmptyOrFilled() {
        Map<Integer, String> t = right();
        t.put(Table.slot(3, MID), "economy:japchae");        // 나물 between 포 and 혜
        t.put(Table.slot(4, MID), "economy:injeolmi");       // a rice cake between the fruits
        t.put(Table.slot(2, W), "economy:kalguksu");         // three 탕, all different
        t.put(Table.slot(2, E), "economy:pollack_stew");
        assertTrue(Table.judge(t, RULES).ok(), Table.judge(t, RULES).toString());
        // 나물 outside the two is wrong
        t.remove(Table.slot(3, MID));
        t.put(Table.slot(3, W), "economy:japchae");
        t.put(Table.slot(3, MID), BUGEO);
        assertEquals(Set.of(Table.slot(3, W)), Table.judge(t, RULES).wrong());
    }

    @Test
    void theCupHoldsTheLiquorAndOnlyIt() {
        Map<Integer, String> t = right();
        t.put(Table.slot(0, W), CHEONGJU);
        t.put(Table.slot(0, MID), RICE);
        Table.Verdict v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(0, W), Table.slot(0, MID)), v.wrong(), "rice in the cup, the liquor in the rice bowl");
        assertTrue(v.missing().isEmpty(), "both are there, only misplaced");
        t = right();
        t.remove(Table.slot(0, MID));
        assertEquals(List.of(Table.slot(0, MID)), Table.judge(t, RULES).missing(), "an empty cup is owed");
        t.put(Table.slot(0, MID), JIRI);
        assertEquals(Set.of(Table.slot(0, MID)), Table.judge(t, RULES).wrong(), "soup in the cup");
    }

    @Test
    void fishEastMeatWest() {
        Map<Integer, String> t = right();
        t.put(Table.slot(1, W), GUI);
        t.put(Table.slot(1, E), BULGOGI);
        Table.Verdict v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(1, W), Table.slot(1, E)), v.wrong(), "both are on the wrong side");
        assertTrue(v.missing().isEmpty(), "they are there, only misplaced");
        // the same by order alone
        Table.Rules loose = new Table.Rules(RULES.rice(), RULES.soup(), RULES.liquor(), RULES.meat(), RULES.fish(), RULES.jeon(), RULES.dried(),
                RULES.sikhye(), RULES.namul(), RULES.pale(), RULES.red(), RULES.cake(), RULES.taboo(), false);
        assertEquals(Set.of(Table.slot(1, W), Table.slot(1, E)), Table.judge(t, loose).wrong());
        // a fish alone on the west vessel: wrong by sides, not by order alone (nothing of the west is east of it)
        t = right();
        t.remove(Table.slot(1, W));
        t.put(Table.slot(1, W), GUI);
        t.remove(Table.slot(1, E));
        assertEquals(Set.of(Table.slot(1, W)), Table.judge(t, RULES).wrong(), "the fish goes, the 전 may stay");
        assertEquals(Set.of(Table.slot(1, MID)), Table.judge(t, loose).wrong(), "by order alone the fish may stay; the 전 east of it may not");
        // the middle belongs to neither side: meat in the middle with the fish east of it is still meat west of fish
        t = right();
        t.remove(Table.slot(1, W));
        t.put(Table.slot(1, MID), BULGOGI);
        Table.Verdict mid = Table.judge(t, RULES);
        assertTrue(mid.wrong().isEmpty(), mid.toString());
        assertEquals(List.of(Table.slot(1, MID)), mid.missing(), "but the 전 has no place between them then");
    }

    @Test
    void theJeonLiesBetweenMeatAndFish() {
        Map<Integer, String> t = right();
        t.remove(Table.slot(1, MID));
        assertEquals(List.of(Table.slot(1, MID)), Table.judge(t, RULES).missing(), "no 전: the middle of that row is owed");
        t.put(Table.slot(1, MID), BULGOGI);
        t.put(Table.slot(1, W), JEON);
        Table.Verdict v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(1, W)), v.wrong(), "a 전 outside the two is misplaced");
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
        t.put(Table.slot(1, E), "economy:hairtail");
        Table.Verdict v = Table.judge(t, r);
        assertEquals(Set.of(Table.slot(1, E)), v.wrong());
        assertEquals(List.of(Table.slot(1, E)), v.missing(), "and the fish's vessel is owed a fish");
        t = right();
        t.put(Table.slot(4, MID), "economy:saury");   // wherever it lies
        assertEquals(Set.of(Table.slot(4, MID)), Table.judge(t, r).wrong());
        assertTrue(Table.endsIn(" 거대 가물치 ", "치"));
        assertFalse(Table.endsIn("참돔", "치"));
        assertFalse(Table.endsIn("참치", ""));
        assertFalse(Table.endsIn(null, "치"));
    }

    @Test
    void garlicAndChiliAndRedBeansAreTaboo() {
        Map<Integer, String> t = right();
        t.put(Table.slot(2, MID), "economy:miyeokguk");   // a soup, but with garlic
        Table.Verdict v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(2, MID)), v.wrong());
        assertEquals(List.of(Table.slot(2, MID)), v.missing(), "and the 탕 row has no soup then");
        t = right();
        t.put(Table.slot(0, E), "economy:kimchi_jjigae");
        v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(0, E)), v.wrong());
        assertEquals(List.of(Table.slot(0, E)), v.missing());
        t = right();
        t.put(Table.slot(4, MID), "economy:gosa_tteok");
        t.put(Table.slot(3, MID), "economy:kimchi");
        t.put(Table.slot(2, W), "economy:garlic");
        assertEquals(Set.of(Table.slot(4, MID), Table.slot(3, MID), Table.slot(2, W)), Table.judge(t, RULES).wrong());
    }

    @Test
    void redFruitEastPaleFruitWest() {
        Map<Integer, String> t = right();
        t.put(Table.slot(4, W), APPLE);
        t.put(Table.slot(4, E), MELON);
        assertEquals(Set.of(Table.slot(4, W), Table.slot(4, E)), Table.judge(t, RULES).wrong());
        t = right();
        t.put(Table.slot(4, W), "economy:strawberry");   // red on the west vessel
        t.put(Table.slot(4, MID), MELON);
        assertEquals(Set.of(Table.slot(4, W)), Table.judge(t, RULES).wrong(), "the red one goes; pale in the middle, red east is right");
        // the middle vessel is neither half: what stands on it is judged by its neighbour
        t = right();
        t.put(Table.slot(4, MID), APPLE);
        t.remove(Table.slot(4, E));
        assertTrue(Table.judge(t, RULES).ok(), "pale west, red in the middle");
        t = right();
        t.put(Table.slot(4, MID), MELON);
        t.remove(Table.slot(4, W));
        assertTrue(Table.judge(t, RULES).ok(), "pale in the middle, red east");
        t = right();
        t.remove(Table.slot(4, W));
        t.put(Table.slot(4, MID), APPLE);
        t.put(Table.slot(4, E), MELON);                  // but not the wrong way round about it
        assertEquals(Set.of(Table.slot(4, E)), Table.judge(t, RULES).wrong());
    }

    @Test
    void riceWestSoupEast() {
        Map<Integer, String> t = right();
        t.put(Table.slot(0, W), JIRI);
        t.put(Table.slot(0, E), RICE);   // as the living eat
        assertEquals(Set.of(Table.slot(0, W), Table.slot(0, E)), Table.judge(t, RULES).wrong());
    }

    @Test
    void theSameSoupTwiceIsWrong() {
        Map<Integer, String> t = right();
        t.put(Table.slot(2, MID), JIRI);   // the 갱 of row 0 again
        Table.Verdict v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(2, MID)), v.wrong());
        assertTrue(v.missing().isEmpty());
        t = right();
        t.put(Table.slot(2, E), EOMUK);    // the same 탕 twice in its own row: the second goes
        assertEquals(Set.of(Table.slot(2, E)), Table.judge(t, RULES).wrong());
        t = right();
        t.put(Table.slot(2, E), BULGOGI);
        assertEquals(Set.of(Table.slot(2, E)), Table.judge(t, RULES).wrong(), "only soups in the 탕 row");
    }

    @Test
    void aMisplacedSoupDoesNotTakeTheTangWithIt() {
        Map<Integer, String> t = right();
        t.put(Table.slot(0, W), EOMUK);   // the 탕's soup in the rice bowl: it goes, the same soup in the 탕 row stays
        Table.Verdict v = Table.judge(t, RULES);
        assertEquals(Set.of(Table.slot(0, W)), v.wrong());
        assertEquals(List.of(Table.slot(0, W)), v.missing(), "and the rice is owed");
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
        t.put(Table.slot(3, W), "economy:dried_fish");
        assertTrue(Table.judge(t, RULES).ok());
    }

    @Test
    void whatIsOwedIsPointedAt() {
        Map<Integer, String> t = right();
        t.remove(Table.slot(0, W));
        Table.Verdict v = Table.judge(t, RULES);
        assertTrue(v.wrong().isEmpty());
        assertEquals(List.of(Table.slot(0, W)), v.missing(), "no rice: the west bowl of the first row");
        assertFalse(v.ok());
        t.remove(Table.slot(0, MID));
        assertEquals(List.of(Table.slot(0, W), Table.slot(0, MID)), Table.judge(t, RULES).missing(), "and no liquor: the cup");
        Table.Verdict empty = Table.judge(Map.of(), RULES);
        assertTrue(empty.wrong().isEmpty());
        assertEquals(11, empty.missing().size(), "rice liquor soup · meat 전 fish · 탕 · 포 혜 · pale red");
        for (int slot : empty.missing()) assertTrue(slot >= 0 && slot < Vessel.COUNT, "a vessel the spoon can tap");
    }

    @Test
    void whatHasNoBusinessInARowIsWrong() {
        Map<Integer, String> t = right();
        t.put(Table.slot(2, W), "minecraft:dirt");
        t.put(Table.slot(3, MID), CHEONGJU);       // liquor belongs to the cup
        t.put(Table.slot(4, MID), SIKHYE);
        assertEquals(Set.of(Table.slot(2, W), Table.slot(3, MID), Table.slot(4, MID)), Table.judge(t, RULES).wrong());
        // gulbi is a fish in the 적 row and a 포 in its own
        t = right();
        t.put(Table.slot(1, E), "economy:gulbi");
        t.put(Table.slot(3, W), "economy:gulbi");
        assertTrue(Table.judge(t, RULES).ok());
    }

    @Test
    void theVesselsStandWestToEastAsTheOneWhoBowsSeesThem() {
        // row 0: lidded bowls either side of the cup; row 2: deep bowls; the rest pedestal plates
        assertEquals(Vessel.Kind.BOWL, Vessel.kind(Table.slot(0, W)));
        assertEquals(Vessel.Kind.CUP, Vessel.kind(Table.slot(0, MID)));
        assertEquals(Vessel.Kind.BOWL, Vessel.kind(Table.slot(0, E)));
        for (int c = 0; c < Table.COLS; c++) {
            assertEquals(Vessel.Kind.DEEP, Vessel.kind(Table.slot(2, c)));
            for (int r : new int[] {1, 3, 4}) assertEquals(Vessel.Kind.PLATE, Vessel.kind(Table.slot(r, c)));
        }
        assertTrue(Vessel.at(Table.slot(0, W))[0] < 0, "the west vessel is at -x");
        assertTrue(Vessel.at(Table.slot(0, E))[0] > 0, "the east vessel is at +x");
        assertEquals(0, Vessel.at(Table.slot(2, MID))[0], 1e-9);
        assertTrue(Vessel.at(Table.slot(0, MID))[1] < Vessel.at(Table.slot(4, MID))[1], "row 0 is the north row, by the screen");
        // from the south, where one bows, facing the screen: west on the left, east on the right
        for (int r = 0; r < Table.ROWS; r++) {
            assertEquals(-1, Vessel.seenFrom(Table.slot(r, W), 0, 3), "row " + r);
            assertEquals(0, Vessel.seenFrom(Table.slot(r, MID), 0, 3));
            assertEquals(1, Vessel.seenFrom(Table.slot(r, E), 0, 3));
            // from behind the screen it would be the other way round: the orientation is the puzzle
            assertEquals(1, Vessel.seenFrom(Table.slot(r, W), 0, -3));
            assertEquals(-1, Vessel.seenFrom(Table.slot(r, E), 0, -3));
        }
        assertEquals(-1, Vessel.rollTo(Table.slot(1, W)));
        assertEquals(1, Vessel.rollTo(Table.slot(1, E)));
        assertNotEquals(0, Vessel.rollTo(Table.slot(1, MID)));
        // every vessel on the table top, none touching its neighbour, and every click box apart
        for (int s = 0; s < Vessel.COUNT; s++) {
            double[] a = Vessel.at(s);
            double half = Vessel.width(Vessel.kind(s)) / 2;
            assertTrue(Math.abs(a[0]) + half <= Vessel.HALF_W - 0.05, "inside west/east edges: " + s);
            assertTrue(a[1] - half >= Vessel.NORTH && a[1] + half <= Vessel.SOUTH, "inside north/south edges: " + s);
            assertEquals(s, Table.slot(Table.row(s), Table.col(s)));
            for (int o = s + 1; o < Vessel.COUNT; o++) {
                double[] b = Vessel.at(o);
                double reach = Math.min(0.31, Vessel.width(Vessel.kind(s)) + 0.04) / 2 + Math.min(0.31, Vessel.width(Vessel.kind(o)) + 0.04) / 2;
                assertTrue(Math.abs(a[0] - b[0]) >= reach || Math.abs(a[1] - b[1]) >= reach, "click boxes overlap: " + s + " " + o);
            }
        }
    }

    @Test
    void whatLiesOnAVesselIsKeptWhole() {
        Random r = new Random(5);
        for (int n : new int[] {1, 2, 3, 57, 400, 4096}) {
            byte[] bytes = new byte[n];
            r.nextBytes(bytes);
            String saved = Vessel.pack(bytes);
            assertFalse(saved.contains("\n") || saved.contains(" "), "one kv value");
            assertArrayEquals(bytes, Vessel.unpack(saved));
        }
        assertNull(Vessel.unpack(null));
        assertNull(Vessel.unpack(""));
        assertNull(Vessel.unpack("@@not base64@@"));
        assertEquals("plate.0", Vessel.key(0));
        assertEquals("plate.14", Vessel.key(Vessel.COUNT - 1));
    }

    @Test
    void aVesselIsTouchedOnlyWithinReach() {
        // standing south of the table: every vessel is within a survival arm's reach of the eyes
        for (int v = 0; v < Vessel.COUNT; v++) {
            double[] at = Vessel.at(v);
            double dx = at[0], dy = 1.62 - House.TOP, dz = Vessel.SOUTH + 0.6 - at[1];
            assertTrue(Vessel.inReach(dx * dx + dy * dy + dz * dz), "vessel " + v);
        }
        assertTrue(Vessel.inReach(0));
        assertTrue(Vessel.inReach(Vessel.REACH * Vessel.REACH));
        assertFalse(Vessel.inReach(Vessel.REACH * Vessel.REACH + 0.01));
        assertFalse(Vessel.inReach(36), "six blocks off: no");
        assertFalse(Vessel.inReach(Double.NaN));
        assertFalse(Vessel.inReach(-1));
    }

    @Test
    void neverOnWater() {
        assertTrue(Place.wet("WATER", false, true));
        assertTrue(Place.wet("LAVA", false, true));
        assertTrue(Place.wet("ICE", false, false), "ice over water");
        assertTrue(Place.wet("KELP_PLANT", false, false));
        assertTrue(Place.wet("SEAGRASS", false, false));
        assertTrue(Place.wet("TALL_SEAGRASS", false, false));
        assertTrue(Place.wet("OAK_SLAB", true, false), "anything waterlogged");
        assertTrue(Place.wet("BRAIN_CORAL_BLOCK", false, false));
        assertFalse(Place.wet("DEAD_BRAIN_CORAL_BLOCK", false, false));
        for (String dry : List.of("GRASS_BLOCK", "DIRT", "SAND", "MUD", "SNOW_BLOCK", "AIR", "SHORT_GRASS", "STONE"))
            assertFalse(Place.wet(dry, false, false), dry);
        assertTrue(Place.WET_BELOW >= 2 && Place.WET_ABOVE >= 1, "the ground, just under it and just over it");
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
        assertFalse(Place.before(0.5, 1.3, 0.5, 0.5, 5), "still over the table's front half");
        assertTrue(Place.before(0.5, 1.6, 0.5, 0.5, 5), "just before its front edge");
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
                "lines.empty", "lines.bow-once", "lines.out", "lines.not-this", "lines.not-now", "lines.gone", "lines.returned",
                "lines.meal-1", "lines.meal-2", "lines.meal-3", "lines.meal-4", "lines.dawn", "tablets.father", "tablets.mother", "mail-reason",
                "jokbo.name", "jokbo.title", "jokbo.author"))
            assertTrue(y.isString(key) && !y.getString(key).isBlank(), "jesa.yml 에 없음: " + key);
        for (String key : List.of("lines.placed", "lines.taken")) assertTrue(y.isString(key), "jesa.yml 에 없음 (비워 둘 수는 있음): " + key);
        assertFalse(y.contains("gui"), "the window is gone");
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
