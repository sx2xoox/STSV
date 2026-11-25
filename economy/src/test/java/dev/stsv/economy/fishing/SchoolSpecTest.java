package dev.stsv.economy.fishing;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 어군 조업 ({@link SchoolSpec}, fish.yml schools): the shipped seas, 떼물고기 and boats parse clean; the rod, a 통발 and a net
 * outside a school never bring up a 떼물고기; the sums of a haul, the board's compass and the detector's arrow; and the income the
 * README's "어군 조업" table is made of (with the core's net seconds and spot).
 */
class SchoolSpecTest {

    private static final Path ECONOMY = Path.of(System.getProperty("stsv.content")).resolve("economy");

    /**
     * A fisher of Lv20 fishing with a rod for an hour, at base price (tools/parity jobs_model.fishing_hour on the best three waters,
     * Lure III, x ROD_HOUR 0.75: 21,402 G of stopwatch fishing an hour = 16,051 G). The 통통배 working schools earns about this.
     */
    static final double ROD_LV20_HOUR = 16_051;
    /**
     * Seconds from one worked-out school to the next (finding it with the detector or by eye, sailing there, the net going down
     * again): the slower boats with the shorter (or no) detector take longer. The income model's only guess (README "어군 조업").
     */
    static final Map<String, Double> TRAVEL = Map.of("stsv:raft", 120.0, "stsv:rowboat", 105.0, "stsv:fishing_boat", 90.0,
            "stsv:coastal_boat", 75.0, "stsv:trawler", 60.0);
    /** The sea each boat is made for (README): where the model counts its hour. */
    static final Map<String, String> HOME = Map.of("stsv:raft", "near", "stsv:rowboat", "near", "stsv:fishing_boat", "near",
            "stsv:coastal_boat", "mid", "stsv:trawler", "far");

    private static YamlConfiguration yaml(Path p) {
        return YamlConfiguration.loadConfiguration(p.toFile());
    }

    private static FishSpec fish() {
        List<String> problems = new ArrayList<>();
        FishSpec f = FishSpec.parse(yaml(ECONOMY.resolve("fish.yml")), problems);
        assertEquals(List.of(), problems);
        return f;
    }

    private static SchoolSpec shipped(FishSpec f) {
        List<String> problems = new ArrayList<>();
        SchoolSpec s = SchoolSpec.parse(yaml(ECONOMY.resolve("fish.yml")).getConfigurationSection("schools"), f, problems);
        assertEquals(List.of(), problems);
        return s;
    }

    /** {net seconds, spot} of a boat from the core's vehicle_tools.yml (its net tool). */
    private static double[] net(String item) {
        Path core = Path.of(System.getProperty("stsv.content")).getParent().getParent().resolve("content/stsv");
        assertTrue(Files.isDirectory(core), core + ": the core's content");
        YamlConfiguration tools = yaml(core.resolve("vehicle_tools.yml"));
        String id = item.substring(item.indexOf(':') + 1);
        for (Map<?, ?> t : tools.getMapList(id + ".tools")) {
            if (!"net".equals(t.get("kind"))) continue;
            Map<?, ?> n = (Map<?, ?>) t.get("net");
            return new double[]{((Number) n.get("seconds")).doubleValue(), ((Number) n.get("spot")).doubleValue()};
        }
        throw new AssertionError(item + ": no net in vehicle_tools.yml");
    }

    /** G (base price) a haul of this boat in this sea brings up on average: each fish of the pool by its weight, one stack at most. */
    private static double haulValue(SchoolSpec s, FishSpec f, SchoolSpec.Boat b, SchoolSpec.Band band) {
        double total = 0, w = 0;
        for (var e : band.fish().entrySet()) {
            double price = f.species().get(e.getKey()).price();
            total += e.getValue() * SchoolSpec.haulCount(b.haul(), band.bonus(), price, Integer.MAX_VALUE) * price;
            w += e.getValue();
        }
        return total / w;
    }

    private static double hourly(SchoolSpec s, FishSpec f, String item) {
        double[] n = net(item);
        SchoolSpec.Boat b = s.boats().get(item);
        return SchoolSpec.haulsPerHour(n[0], (int) n[1], TRAVEL.get(item)) * haulValue(s, f, b, s.band(HOME.get(item)));
    }

    @Test
    void theShippedSchoolsParseAndUseOnlyTheBoatsFish() {
        FishSpec f = fish();
        SchoolSpec s = shipped(f);
        assertTrue(s.enabled());
        assertEquals(List.of("near", "mid", "far"), s.bands().stream().map(SchoolSpec.Band::id).toList());
        Set<String> pooled = new HashSet<>();
        for (SchoolSpec.Band b : s.bands()) pooled.addAll(b.fish().keySet());
        Set<String> boatOnly = new HashSet<>();
        for (FishSpec.Species x : f.species().values()) if (x.boatOnly()) boatOnly.add(x.id());
        assertEquals(Set.of("anchovy", "squid", "spanish_mackerel", "flying_fish", "skipjack", "school_tuna"), boatOnly);
        assertEquals(boatOnly, pooled, "every 떼물고기 comes up in some sea, and only they do");
        // further out: bigger schools, a bigger haul, dearer fish on average
        for (int i = 1; i < s.bands().size(); i++) {
            SchoolSpec.Band a = s.bands().get(i - 1), b = s.bands().get(i);
            assertTrue(b.from() > a.from() && b.bonus() > a.bonus() && b.minValue() > a.minValue() && b.maxValue() > a.maxValue(), b.id());
            assertTrue(meanPrice(f, b) > meanPrice(f, a), b.id() + " dearer");
        }
        assertEquals("near", s.band(-1).id(), "a world with no harbour: the nearest sea");
        assertEquals("near", s.band(399).id());
        assertEquals("mid", s.band(400).id());
        assertEquals("far", s.band(50_000).id());
        // the 떼물고기 are the codex's 어선 page, with its title
        assertTrue(f.groups().containsKey(FishSpec.SCHOOL));
        // the detectors, as the plan has them: none on the raft and the rowboat, then further and in more detail
        assertEquals(SchoolSpec.Show.NONE, s.boats().get("stsv:raft").show());
        assertEquals(SchoolSpec.Show.NONE, s.boats().get("stsv:rowboat").show());
        assertEquals(150, s.boats().get("stsv:fishing_boat").detect(), 1e-9);
        assertEquals(SchoolSpec.Show.DIRECTION, s.boats().get("stsv:fishing_boat").show());
        assertEquals(300, s.boats().get("stsv:coastal_boat").detect(), 1e-9);
        assertEquals(SchoolSpec.Show.DISTANCE, s.boats().get("stsv:coastal_boat").show());
        assertEquals(500, s.boats().get("stsv:trawler").detect(), 1e-9);
        assertEquals(SchoolSpec.Show.FULL, s.boats().get("stsv:trawler").show());
        assertEquals(8, s.compass().size());
        assertEquals(64, s.seeDay(), 1e-9);
        assertEquals(32, s.seeNight(), 1e-9);
    }

    private static double meanPrice(FishSpec f, SchoolSpec.Band b) {
        double t = 0, w = 0;
        for (var e : b.fish().entrySet()) {
            t += e.getValue() * f.species().get(e.getKey()).price();
            w += e.getValue();
        }
        return t / w;
    }

    @Test
    void theRodATrapAndANetOutsideASchoolNeverBringUpABoatsFish() {
        FishSpec f = fish();
        Random r = new Random(7);
        List<FishSpec.Moment> moments = List.of(new FishSpec.Moment(6000, false, false, 4), new FishSpec.Moment(18000, true, true, 0),
                new FishSpec.Moment(23500, false, false, 0), new FishSpec.Moment(12500, true, false, 2));
        for (String h : List.of("sea", "deep", "warm", "cold", "river", "coldriver", "swamp", "mangrove", "jungle", "cave", "mushroom")) {
            for (FishSpec.Moment m : moments) {
                for (FishSpec.Rarity tier : f.rarities().values()) {
                    for (int i = 0; i < 200; i++) {
                        FishSpec.Species s = f.pick(h, m, tier, r);
                        assertFalse(s != null && s.boatOnly(), h + ": " + (s == null ? "" : s.id()));
                    }
                }
                // a 통발 picks at any time (TrapSpec.ANY_TIME): the same pick
                FishSpec.Species t = f.pick(h, TrapSpec.ANY_TIME, f.rarities().get("uncommon"), r);
                assertFalse(t != null && t.boatOnly());
            }
            assertFalse(FishSpec.habitat("minecraft:" + h, false).equals(FishSpec.SCHOOL));
        }
        // nor the 낚시대회: never a target species, never a 서식지 event
        for (FishSpec.Moment m : moments) assertTrue(TournamentRules.speciesNow(f, m).stream().noneMatch(FishSpec.Species::boatOnly));
    }

    @Test
    void aHaulASchoolAndTheWays() {
        assertEquals(64, SchoolSpec.haulCount(200, 1.0, 3, 1000), "one stack at most");
        assertEquals(33, SchoolSpec.haulCount(200, 1.0, 6, 1000));
        assertEquals(9, SchoolSpec.haulCount(300, 1.4, 45, 1000));
        assertEquals(5, SchoolSpec.haulCount(300, 1.4, 45, 5), "no more than the school has left");
        assertEquals(1, SchoolSpec.haulCount(10, 1.0, 45, 5), "at least one");
        assertEquals(0, SchoolSpec.haulCount(300, 1.4, 45, 0));
        assertEquals(500, SchoolSpec.amount(1500, 3));
        assertEquals(1, SchoolSpec.amount(10, 45));
        // the board's compass: north is -z
        assertEquals(0, SchoolSpec.compass(0, -100));
        assertEquals(1, SchoolSpec.compass(70, -70));
        assertEquals(2, SchoolSpec.compass(100, 0));
        assertEquals(4, SchoolSpec.compass(0, 100));
        assertEquals(6, SchoolSpec.compass(-100, 3));
        assertEquals(7, SchoolSpec.compass(-70, -70));
        // the detector's arrow, from the boat's heading (yaw 0 = south): ahead, right (west when facing south), behind
        assertEquals(0, SchoolSpec.arrow(0, 0, 50));
        assertEquals(2, SchoolSpec.arrow(0, -50, 0));
        assertEquals(6, SchoolSpec.arrow(0, 50, 0));
        assertEquals(4, SchoolSpec.arrow(0, 0, -50));
        assertEquals(0, SchoolSpec.arrow(180, 0, -50), "facing north, north is ahead");
        assertEquals(0, SchoolSpec.arrow(-90, 50, 0), "facing east");
        assertEquals(1, SchoolSpec.arrow(0, -40, 40));
        // day and night
        assertFalse(SchoolSpec.night(6000));
        assertTrue(SchoolSpec.night(18000));
        assertTrue(SchoolSpec.night(24000L * 5 + 13000));
        SchoolSpec s = shipped(fish());
        assertEquals(64, s.see(1000), 1e-9);
        assertEquals(32, s.see(15000), 1e-9);
        assertTrue(s.biome("minecraft:deep_lukewarm_ocean"));
        assertFalse(s.biome("minecraft:river"));
        assertFalse(s.biome("minecraft:plains"));
        assertEquals(0, s.size(500));
        assertEquals(2, s.size(99_999));
        // the pick is by the pool's weights
        SchoolSpec.Band near = s.band("near");
        Map<String, Integer> seen = new java.util.HashMap<>();
        Random r = new Random(3);
        for (int i = 0; i < 10_000; i++) seen.merge(SchoolSpec.pick(near, r), 1, Integer::sum);
        assertEquals(near.fish().keySet(), seen.keySet());
        assertTrue(seen.get("anchovy") > seen.get("squid") && seen.get("squid") > seen.get("spanish_mackerel"));
    }

    /**
     * The README's arithmetic: a boat working schools all hour in its own sea. The 통통배 earns what a Lv20 fisher's rod does
     * (within 10%), the 연안 어선 and the 트롤 어선 well above it, every boat above the one before; and a new school of a sea is about
     * what that sea's boat takes from one place before its net's spot is spent (net.spot hauls), so a boat works one school out.
     */
    @Test
    void theIncomeTheReadmeTableIsMadeOf() {
        FishSpec f = fish();
        SchoolSpec s = shipped(f);
        List<String> ladder = List.of("stsv:raft", "stsv:rowboat", "stsv:fishing_boat", "stsv:coastal_boat", "stsv:trawler");
        double before = 0;
        for (String b : ladder) {
            double h = hourly(s, f, b);
            assertTrue(h > before, b + ": " + Math.round(h) + " after " + Math.round(before));
            before = h;
        }
        double boat = hourly(s, f, "stsv:fishing_boat"), coastal = hourly(s, f, "stsv:coastal_boat"), trawler = hourly(s, f, "stsv:trawler");
        assertEquals(1, boat / ROD_LV20_HOUR, 0.10, "통통배 " + Math.round(boat) + " G/h vs the Lv20 rod's " + ROD_LV20_HOUR);
        assertEquals(40_000, coastal, 4_000, "연안 어선");
        assertEquals(78_000, trawler, 7_800, "트롤 어선");
        assertTrue(coastal >= 2 * boat && trawler >= 1.8 * coastal);
        // a raft or a rowboat in a school: a little on top of fishing, never a living (under a non-fisher's rod hour, 2,636 G)
        assertTrue(hourly(s, f, "stsv:rowboat") < 6_000);
        for (String b : List.of("stsv:fishing_boat", "stsv:coastal_boat", "stsv:trawler")) {
            SchoolSpec.Band home = s.band(HOME.get(b));
            double spot = net(b)[1];
            double hauls = (home.minValue() + home.maxValue()) / 2 / haulValue(s, f, s.boats().get(b), home);
            assertTrue(hauls >= 0.6 * spot && hauls <= 1.4 * spot, b + ": a school is " + Math.round(hauls) + " hauls, its spot " + spot);
        }
    }
}
