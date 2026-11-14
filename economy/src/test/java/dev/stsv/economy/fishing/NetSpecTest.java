package dev.stsv.economy.fishing;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 원양 조업 ({@link NetSpec}, fish.yml net:): the seas by distance from a harbour, each boat's net and the sea it is made
 * for, the crew, and the sums the README's table is made of: with the shipped numbers a bigger boat earns more a trip and
 * more an hour, sails further for it, and a trip of any of them is a sitting, not a day.
 */
class NetSpecTest {

    private static final Path ECONOMY = Path.of(System.getProperty("stsv.content")).resolve("economy");
    private static final List<String> BOATS = List.of("stsv:fishing_boat", "stsv:coastal_boat", "stsv:trawler");

    private static YamlConfiguration yaml(Path p) {
        return YamlConfiguration.loadConfiguration(p.toFile());
    }

    private static NetSpec shipped() {
        List<String> problems = new ArrayList<>();
        NetSpec n = NetSpec.parse(yaml(ECONOMY.resolve("fish.yml")).getConfigurationSection("net"), problems);
        assertEquals(List.of(), problems);
        return n;
    }

    /** The core's content beside the economy's (the same place the other tests find it). */
    private static Path core() {
        Path p = Path.of(System.getProperty("stsv.content")).getParent().getParent().resolve("content/stsv");
        assertTrue(Files.isDirectory(p), p + ": the core's content");
        return p;
    }

    @Test
    void theSeasByDistanceAndTheBoatsReach() {
        NetSpec n = shipped();
        assertTrue(n.enabled());
        assertEquals(List.of("coast", "near", "far"), n.zones().stream().map(NetSpec.Zone::id).toList());
        assertEquals(0, n.zones().getFirst().from(), 1e-9);
        assertEquals("coast", n.zoneAt(0).id());
        assertEquals("coast", n.zoneAt(599).id());
        assertEquals("near", n.zoneAt(600).id());
        assertEquals("far", n.zoneAt(50_000).id());
        // every boat hauls its own sea at most: the 통통배 the coast wherever it trawls, the trawler whatever sea it is in
        assertEquals("coast", n.zoneFor("stsv:fishing_boat", 9000).id());
        assertEquals("near", n.zoneFor("stsv:coastal_boat", 9000).id());
        assertEquals("coast", n.zoneFor("stsv:coastal_boat", 100).id());
        assertEquals("far", n.zoneFor("stsv:trawler", 9000).id());
        assertEquals("near", n.zoneFor("stsv:trawler", 1000).id(), "close in, even a trawler hauls what is there");
        assertEquals("coast", n.zoneFor("stsv:trawler", 0).id());
        assertEquals("far", n.zoneFor("trawler", 9000).id(), "the item's bare id is the core's");
        // the raft and the rowboat have no net: no crates
        assertNull(n.zoneFor("stsv:raft", 9000));
        assertNull(n.zoneFor("stsv:rowboat", 9000));
        // further out is worth more, crates are told apart
        for (int i = 1; i < n.zones().size(); i++) {
            assertTrue(n.zones().get(i).value() > n.zones().get(i - 1).value());
            assertTrue(n.zones().get(i).from() > n.zones().get(i - 1).from());
        }
        assertEquals(3, n.zones().stream().map(NetSpec.Zone::item).distinct().count());
        assertEquals("far", n.ofItem("economy:catch_far").id());
        assertNull(n.ofItem("economy:mackerel"), "a fish is not a crate");
    }

    @Test
    void theCrewHaulsTheNetTogether() {
        NetSpec n = shipped();
        assertEquals(1.0, n.crew(1), 1e-9, "alone");
        assertEquals(1.0, n.crew(0), 1e-9);
        assertEquals(1 + n.crewBonus(), n.crew(2), 1e-9);
        assertEquals(n.crew(1 + n.crewMax()), n.crew(99), 1e-9, "only so many count");
        assertTrue(n.crew(8) <= 1.5 + 1e-9, "a full trawler is not twice a lone skipper");
        // whole crates: one, and one more by the fraction
        assertEquals(1, n.crates(1, 0.0));
        assertEquals(1, n.crates(1, 0.99));
        assertEquals(2, n.crates(6, 0.1));
        assertEquals(1, n.crates(6, 0.9));
        // on average, exactly the crew's share
        double sum = 0;
        for (int i = 0; i < 1000; i++) sum += n.crates(4, (i + 0.5) / 1000.0);
        assertEquals(n.crew(4), sum / 1000, 1e-6);
    }

    @Test
    void whatAHoldFetches() {
        NetSpec n = shipped();
        Map<String, Integer> hold = new LinkedHashMap<>();
        hold.put("economy:catch_coast", 10);
        hold.put("economy:catch_far", 3);
        hold.put("economy:mackerel", 64);
        assertEquals((long) (10 * n.ofItem("economy:catch_coast").value() + 3 * n.ofItem("economy:catch_far").value()), n.worth(hold));
        assertEquals(0, n.worth(Map.of()));
    }

    /** The boats as shipped: {seconds of the net, payload, speed, seats} from the core's content. */
    private static Map<String, double[]> boats() {
        Path core = core();
        YamlConfiguration items = yaml(core.resolve("items/vehicles_misc.yml")), tools = yaml(core.resolve("vehicle_tools.yml"));
        Map<String, double[]> out = new LinkedHashMap<>();
        for (String id : BOATS) {
            String key = id.substring("stsv:".length());
            ConfigurationSection v = items.getConfigurationSection(key + ".vehicle");
            assertNotNull(v, key);
            List<Map<?, ?>> ts = tools.getMapList(key + ".tools");
            assertFalse(ts.isEmpty(), key + ": a net in vehicle_tools.yml");
            double seconds = ((Number) ((Map<?, ?>) ts.getFirst().get("net")).get("seconds")).doubleValue();
            out.put(id, new double[]{seconds, v.getDouble("cargo.payload"), v.getDouble("speed"), v.getMapList("seats").size()});
        }
        return out;
    }

    /**
     * The ladder of the trips, alone and with a full boat: every generation's full hold is worth more, takes about a sitting
     * to fill, and pays more an hour; a crew makes it quicker, never slower. Written out for the README (build/net-trips.txt).
     */
    @Test
    void aBiggerBoatEarnsMoreATripAndAnHour() throws IOException {
        NetSpec n = shipped();
        Map<String, double[]> boats = boats();
        StringBuilder table = new StringBuilder("boat | sea | crates | value | trawl min | sail min | G/h alone | G/h full crew\n");
        NetSpec.Trip before = null;
        for (String id : BOATS) {
            double[] b = boats.get(id);
            NetSpec.Trip alone = n.trip(id, b[0], b[1], b[2], 1), crew = n.trip(id, b[0], b[1], b[2], (int) b[3]);
            table.append(id).append(" | ").append(n.best(id).name()).append(" | ").append(alone.crates()).append(" | ").append(alone.value())
                    .append(" | ").append(Math.round(alone.trawling())).append(" | ").append(String.format(java.util.Locale.ROOT, "%.1f", alone.sailing()))
                    .append(" | ").append(Math.round(alone.perHour())).append(" | ").append(Math.round(crew.perHour())).append('\n');
            assertTrue(alone.crates() >= 50, id + ": a hold worth sailing for");
            assertTrue(alone.trawling() >= 30 && alone.trawling() <= 120, id + ": a hold fills in a sitting (" + alone.trawling() + " min)");
            assertTrue(alone.sailing() < alone.trawling() / 3, id + ": more fishing than sailing");
            assertTrue(crew.perHour() > alone.perHour(), id + ": a crew helps");
            assertTrue(crew.perHour() < alone.perHour() * 1.5, id + ": but does not double it");
            assertEquals(alone.value(), crew.value(), id + ": the same hold");
            if (before != null) {
                assertTrue(alone.value() > before.value() * 1.5, id + ": a full hold is worth much more than the boat before");
                assertTrue(alone.perHour() > before.perHour() * 1.5, id + ": and it pays more an hour");
            }
            before = alone;
        }
        // against a day's work with a rod (about 10,000 G an hour for a fisher: README 낚시): the 통통배 well under it (its
        // skipper's hands are free to fish as well), the trawler over it but not many times
        double[] t = boats.get("stsv:trawler"), f = boats.get("stsv:fishing_boat");
        assertTrue(n.trip("stsv:fishing_boat", f[0], f[1], f[2], 1).perHour() < 6000);
        assertTrue(n.trip("stsv:trawler", t[0], t[1], t[2], (int) t[3]).perHour() < 40_000);
        Path out = Path.of("build/net-trips.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table.toString());
    }

    @Test
    void aBadFileSaysWhatIsWrong() {
        YamlConfiguration y = new YamlConfiguration();
        assertEquals(NetSpec.NONE, NetSpec.parse(null, new ArrayList<>()));
        assertEquals(NetSpec.NONE, NetSpec.parse(y.createSection("net"), new ArrayList<>()), "no zones: single fish as before");
        y.set("net.zones.a.from", 100);
        y.set("net.zones.a.item", "economy:catch_coast");
        y.set("net.zones.a.value", 50);
        y.set("net.zones.b.from", 500);
        y.set("net.zones.b.item", "economy:catch_near");
        y.set("net.zones.b.value", 20);
        y.set("net.zones.c.item", "nothing");
        y.set("net.boats.stsv:fishing_boat", "zzz");
        y.set("net.crew-bonus", 3);
        List<String> problems = new ArrayList<>();
        NetSpec n = NetSpec.parse(y.getConfigurationSection("net"), problems);
        assertEquals(5, problems.size(), problems.toString());
        assertEquals(2, n.zones().size());
        assertEquals(0, n.zones().getFirst().from(), 1e-9, "the nearest sea starts at the harbour");
        assertTrue(n.boats().isEmpty());
        assertEquals(1.0, n.crew(8), 1e-9);
        assertFalse(NetSpec.NONE.enabled());
        assertNull(NetSpec.NONE.zoneFor("stsv:trawler", 5000));
    }
}
