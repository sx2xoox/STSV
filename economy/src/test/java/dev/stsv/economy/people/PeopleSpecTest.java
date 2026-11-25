package dev.stsv.economy.people;

import dev.stsv.economy.bus.BusSpec;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 인물 도감 (people.yml): who is in it, what a meeting records, when intros open, the hints and the completions. */
class PeopleSpecTest {

    static BusSpec bus() {
        List<String> problems = new ArrayList<>();
        BusSpec s = BusSpec.parse(YamlConfiguration.loadConfiguration(new File("content/economy/bus.yml")), problems);
        assertEquals(List.of(), problems);
        return s;
    }

    static PeopleSpec spec() {
        List<String> problems = new ArrayList<>();
        PeopleSpec s = PeopleSpec.parse(YamlConfiguration.loadConfiguration(new File("content/economy/people.yml")), problems);
        assertEquals(List.of(), problems);
        return s;
    }

    @Test
    void everyPassengerKindIsAPersonButTheSecretOnes() {
        BusSpec bus = bus();
        PeopleSpec s = spec();
        assertEquals(List.of(), s.check(bus));
        List<String> people = s.people(bus);
        assertFalse(people.contains("shades"), "the secret ??? is never listed");
        for (BusSpec.Type t : bus.types().values()) assertEquals(t.weight() > 0 && !t.id().equals("shades"), people.contains(t.id()), t.id());
        assertEquals(bus.types().size() - 1, people.size());
        assertEquals("resident", people.getFirst(), "bus.yml's order");
        for (String id : people) {
            List<String> intro = s.intros().get(id);
            assertEquals(3, intro.size(), id);
            for (String line : intro) assertFalse(line.isBlank(), id);
        }
        assertEquals(List.of(1, 10, 50), s.milestones());
        assertEquals("버스", s.places().get(PeopleSpec.PLACE_BUS));
        assertEquals("economy:people_all", s.titleAll());
        assertEquals("economy:people_shiny_all", s.titleShiny());
    }

    @Test
    void theTitlesExist() {
        YamlConfiguration titles = YamlConfiguration.loadConfiguration(new File("content/economy/titles/codex.yml"));
        PeopleSpec s = spec();
        for (String t : List.of(s.titleAll(), s.titleShiny())) assertTrue(titles.contains(t.substring(t.indexOf(':') + 1)), t);
    }

    @Test
    void introsOpenAtTheMilestones() {
        PeopleSpec s = spec();
        assertEquals(0, s.introsOpen(0));
        assertEquals(1, s.introsOpen(1));
        assertEquals(1, s.introsOpen(9));
        assertEquals(2, s.introsOpen(10));
        assertEquals(3, s.introsOpen(50));
        assertEquals(10, s.nextMilestone(1));
        assertEquals(50, s.nextMilestone(10));
        assertEquals(-1, s.nextMilestone(50));
    }

    @Test
    void aMeetingRecordsCountDateAndPlaces() {
        PeopleSpec s = spec();
        PeopleSpec.Entry e = new PeopleSpec.Entry();
        assertFalse(e.registered());
        PeopleSpec.Meet m = e.meet(s, 1000, false, "bus");
        assertTrue(m.first());
        assertTrue(e.registered());
        assertEquals(0, m.opened(), "the first intro line");
        assertTrue(m.newPlace());
        assertEquals(1, e.met);
        assertEquals(1000, e.first);
        for (int i = 2; i <= 9; i++) assertEquals(-1, e.meet(s, 1000 + i, false, "bus").opened());
        m = e.meet(s, 5000, false, "bus");
        assertEquals(1, m.opened(), "the tenth meeting opens the second line");
        assertFalse(m.first());
        assertFalse(m.newPlace());
        assertEquals(1000, e.first, "first met stays");
        // the places, saved and read again (a set: later systems add theirs)
        e.meet(s, 7000, false, "restaurant");
        assertEquals("bus,restaurant", e.placesText());
        assertEquals(e.places, PeopleSpec.Entry.places("bus,restaurant"));
        assertTrue(PeopleSpec.Entry.places("").isEmpty());
        assertTrue(PeopleSpec.Entry.places(null).isEmpty());
    }

    @Test
    void aShinyEntryHasItsOwnLineThenTheNormalIntro() {
        PeopleSpec s = spec();
        for (String id : s.intros().keySet()) {
            String own = s.shinyIntros().get(id);
            assertNotNull(own, id);
            assertFalse(own.isBlank(), id);
            List<String> shiny = s.intro(id, true);
            assertEquals(own, shiny.getFirst());
            assertEquals(s.intros().get(id), shiny.subList(1, shiny.size()));
            assertEquals(s.intros().get(id), s.intro(id, false));
        }
        assertEquals(0, s.introsOpen(0, true));
        assertEquals(2, s.introsOpen(1, true), "the shiny line and the first normal one");
        assertEquals(3, s.introsOpen(10, true));
        assertEquals(4, s.introsOpen(50, true));
        // a shiny entry is its own: its own count, date and intro progress
        PeopleSpec.Entry e = new PeopleSpec.Entry();
        PeopleSpec.Meet m = e.meet(s, 42, true, "bus");
        assertTrue(m.first());
        assertEquals(1, m.opened(), "two lines open at once: the last one opened is index 1");
        assertEquals(42, e.first);
        // a bad file: a person without a shiny line is reported
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("content/economy/people.yml"));
        y.set("people.grandma.shiny", null);
        List<String> problems = new ArrayList<>();
        PeopleSpec.parse(y, problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("grandma.shiny")), problems.toString());
    }

    @Test
    void onlyARideToTheirStopAllTheWayRegisters() {
        assertTrue(PeopleSpec.registers(true, 800, 800));
        assertFalse(PeopleSpec.registers(false, 800, 800), "not where they were going");
        assertFalse(PeopleSpec.registers(true, 0, 800), "the bus did not drive them (공정 운행)");
        assertFalse(PeopleSpec.registers(true, 300, 800), "a short hop after turning round");
    }

    @Test
    void completionsNeedEveryPersonInThatCollection() {
        List<String> people = List.of("a", "b");
        Map<String, PeopleSpec.Entry> normal = new HashMap<>(), shiny = new HashMap<>();
        PeopleSpec s = spec();
        assertFalse(PeopleSpec.complete(people, normal));
        PeopleSpec.Entry a = new PeopleSpec.Entry(), b = new PeopleSpec.Entry(), bs = new PeopleSpec.Entry();
        a.meet(s, 1, false, "bus");
        normal.put("a", a);
        bs.meet(s, 1, true, "bus");   // b met only as 별난: counts for the 별난 collection only
        shiny.put("b", bs);
        assertFalse(PeopleSpec.complete(people, normal));
        assertEquals(1, PeopleSpec.count(people, normal));
        assertEquals(1, PeopleSpec.count(people, shiny));
        b.meet(s, 2, false, "bus");
        normal.put("b", b);
        assertTrue(PeopleSpec.complete(people, normal));
        assertFalse(PeopleSpec.complete(people, shiny));
        normal.put("c", new PeopleSpec.Entry());   // not listed, never met: changes nothing
        assertEquals(2, PeopleSpec.count(people, normal));
        assertFalse(PeopleSpec.complete(List.of(), normal));
    }

    /** The one-time split of the old store (one row a person with a shiny flag) into the two collections. */
    @Test
    void oldShinyFlagsBecomeShinyEntries(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        dev.stsv.core.data.Db db = dev.stsv.core.data.DatabaseModule.sqlite(dir.resolve("stsv.db"), java.util.logging.Logger.getLogger("test"));
        String create = "CREATE TABLE IF NOT EXISTS eco_people (player CHAR(36) NOT NULL, person VARCHAR(64) NOT NULL, met INT NOT NULL, "
                + "first_at BIGINT NOT NULL, normal_at BIGINT NOT NULL, shiny_at BIGINT NOT NULL, places VARCHAR(255) NOT NULL, PRIMARY KEY (player, person))";
        db.migrate("eco_people", List.of(List.of(create)));
        String p = "00000000-0000-0000-0000-000000000001";
        String ins = "INSERT INTO eco_people (player, person, met, first_at, normal_at, shiny_at, places) VALUES (?, ?, ?, ?, ?, ?, ?)";
        db.sync(c -> {
            dev.stsv.core.data.Db.update(c, ins, p, "grandma", 12, 100, 100, 500, "bus");    // normal first, a shiny later
            dev.stsv.core.data.Db.update(c, ins, p, "santa", 3, 200, 0, 200, "bus");         // only ever met shiny
            dev.stsv.core.data.Db.update(c, ins, p, "monk", 5, 300, 400, 300, "bus");         // shiny first, normal later
            dev.stsv.core.data.Db.update(c, ins, p, "farmer", 7, 50, 50, 0, "bus");           // never shiny
            return null;
        });
        List<List<String>> steps = List.of(List.of(create), PeopleSpec.splitShinyMigration());
        db.migrate("eco_people", steps);
        db.migrate("eco_people", steps);   // a second start: nothing moves again
        // and the steps themselves are safe to run twice
        db.sync(c -> {
            for (String sql : PeopleSpec.splitShinyMigration()) dev.stsv.core.data.Db.update(c, sql);
            return null;
        });
        Map<String, long[]> normal = new HashMap<>(), shiny = new HashMap<>();
        db.sync(c -> {
            for (Object[] r : dev.stsv.core.data.Db.query(c, "SELECT person, met, first_at, shiny_at FROM eco_people",
                    rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}))
                normal.put((String) r[0], new long[]{(Long) r[1], (Long) r[2], (Long) r[3]});
            for (Object[] r : dev.stsv.core.data.Db.query(c, "SELECT person, met, first_at, places FROM eco_people_shiny",
                    rs -> new Object[]{rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4)})) {
                shiny.put((String) r[0], new long[]{(Long) r[1], (Long) r[2]});
                assertEquals("bus", r[3]);
            }
            return null;
        });
        assertEquals(java.util.Set.of("grandma", "monk", "farmer"), normal.keySet(), "santa was only ever shiny: no 일반 entry");
        assertEquals(java.util.Set.of("grandma", "santa", "monk"), shiny.keySet());
        assertArrayEquals(new long[]{11, 100, 0}, normal.get("grandma"), "one meeting moved to the shiny entry");
        assertArrayEquals(new long[]{1, 500}, shiny.get("grandma"));
        assertArrayEquals(new long[]{3, 200}, shiny.get("santa"), "every meeting was shiny");
        assertArrayEquals(new long[]{4, 400, 0}, normal.get("monk"), "first met (일반) = when the normal look was first met");
        assertArrayEquals(new long[]{1, 300}, shiny.get("monk"));
        assertArrayEquals(new long[]{7, 50, 0}, normal.get("farmer"));
        db.close();
    }

    @Test
    void hintsSayWhereAndWhen() {
        BusSpec bus = bus();
        List<String[]> hiker = PeopleSpec.hint(bus.types().get("hiker"));
        assertArrayEquals(new String[]{"tags", "mountain,forest"}, hiker.get(0));
        assertArrayEquals(new String[]{"hours", "5~17"}, hiker.get(1));
        List<String[]> santa = PeopleSpec.hint(bus.types().get("santa"));
        assertTrue(santa.stream().anyMatch(h -> h[0].equals("months") && h[1].equals("12")));
        assertEquals("anywhere", PeopleSpec.hint(bus.types().get("resident")).getFirst()[0]);
        assertTrue(PeopleSpec.hint(bus.types().get("couple")).stream().anyMatch(h -> h[0].equals("pair")));
        YamlConfiguration m = YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml"));
        for (BusSpec.Type t : bus.types().values()) {
            for (String[] h : PeopleSpec.hint(t)) assertTrue(m.contains("people.hint-" + h[0]), h[0]);
            for (String tag : t.tags().keySet()) assertTrue(bus.extra().tags().containsKey(tag), tag);
        }
    }
}
