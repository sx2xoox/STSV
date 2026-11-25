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
    void aMeetingRecordsCountDateLooksAndPlaces() {
        PeopleSpec s = spec();
        PeopleSpec.Entry e = new PeopleSpec.Entry();
        assertFalse(e.registered());
        PeopleSpec.Meet m = e.meet(s, 1000, false, "bus");
        assertTrue(m.first());
        assertFalse(m.firstShiny());
        assertEquals(0, m.opened(), "the first intro line");
        assertTrue(m.newPlace());
        assertEquals(1, e.met);
        assertEquals(1000, e.first);
        assertEquals(1000, e.normal);
        assertEquals(0, e.shiny);
        for (int i = 2; i <= 9; i++) assertEquals(-1, e.meet(s, 1000 + i, false, "bus").opened());
        m = e.meet(s, 5000, true, "bus");
        assertEquals(1, m.opened(), "the tenth meeting opens the second line");
        assertTrue(m.firstShiny());
        assertFalse(m.first());
        assertFalse(m.newPlace());
        assertEquals(5000, e.shiny);
        assertEquals(1000, e.first, "first met stays");
        assertFalse(e.meet(s, 6000, true, "bus").firstShiny(), "once");
        assertEquals(5000, e.shiny);
        // the places, saved and read again (a set: later systems add theirs)
        e.meet(s, 7000, false, "restaurant");
        assertEquals("bus,restaurant", e.placesText());
        assertEquals(e.places, PeopleSpec.Entry.places("bus,restaurant"));
        assertTrue(PeopleSpec.Entry.places("").isEmpty());
        assertTrue(PeopleSpec.Entry.places(null).isEmpty());
        // a shiny first: registered with no normal look yet
        PeopleSpec.Entry f = new PeopleSpec.Entry();
        f.meet(s, 1, true, "bus");
        assertTrue(f.registered());
        assertEquals(0, f.normal);
    }

    @Test
    void onlyARideToTheirStopAllTheWayRegisters() {
        assertTrue(PeopleSpec.registers(true, 800, 800));
        assertFalse(PeopleSpec.registers(false, 800, 800), "not where they were going");
        assertFalse(PeopleSpec.registers(true, 0, 800), "the bus did not drive them (공정 운행)");
        assertFalse(PeopleSpec.registers(true, 300, 800), "a short hop after turning round");
    }

    @Test
    void completionsNeedEveryPerson() {
        List<String> people = List.of("a", "b");
        Map<String, PeopleSpec.Entry> mine = new HashMap<>();
        PeopleSpec s = spec();
        assertFalse(PeopleSpec.complete(people, mine, false));
        PeopleSpec.Entry a = new PeopleSpec.Entry(), b = new PeopleSpec.Entry();
        a.meet(s, 1, false, "bus");
        mine.put("a", a);
        b.meet(s, 1, true, "bus");
        mine.put("b", b);
        assertFalse(PeopleSpec.complete(people, mine, false), "b only shiny");
        assertEquals(1, PeopleSpec.count(people, mine, false));
        assertEquals(1, PeopleSpec.count(people, mine, true));
        b.meet(s, 2, false, "bus");
        assertTrue(PeopleSpec.complete(people, mine, false));
        assertFalse(PeopleSpec.complete(people, mine, true));
        a.meet(s, 3, true, "bus");
        assertTrue(PeopleSpec.complete(people, mine, true));
        assertFalse(PeopleSpec.complete(List.of(), mine, false));
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
