package dev.stsv.economy.bus;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 선글라스 남자 (SecretAgent) and the 3차 passenger kinds: the pure rules. */
class SecretAgentTest {

    static SecretAgent.Config config() {
        return SecretAgent.Config.parse(YamlConfiguration.loadConfiguration(new File("content/economy/bus.yml")).getConfigurationSection("secret"));
    }

    @Test
    void configReadsFromBusYml() {
        SecretAgent.Config c = config();
        assertEquals(5, c.notes().size());
        assertEquals("shades", c.type());
        assertTrue(BusTest.spec().types().containsKey(c.type()));
        assertEquals(0, BusTest.spec().types().get("shades").weight(), 1e-9);   // never an ordinary passenger
        assertEquals(420, c.windowFrom());
        assertEquals(450, c.windowTo());
        assertEquals(3, c.finalHour());
        assertEquals(33, c.finalMinute());
        assertEquals(3, c.honks());
        assertEquals(1_000_000, c.money());
        assertEquals("stsv:agent_sedan", c.vehicle());
        assertEquals("economy:secret_agent", c.title());
        // the final riddle across the notes
        StringBuilder r = new StringBuilder();
        for (SecretAgent.Note n : c.notes()) r.append(n.line()).append(" / ");
        assertEquals("새벽 3시 33분 / 종점 터미널 / 경적을 / 세 번 / 울려라 / ", r.toString());
        assertTrue(c.line("mission5", 0, "a", "가", "b", "나").contains("가"));
    }

    @Test
    void missionPrefersAMissingNote() {
        for (double roll = 0; roll < 1; roll += 0.05) {
            int m = SecretAgent.pickMission(Set.of(1, 2, 4, 5), 5, roll);
            assertEquals(3, m);
            int k = SecretAgent.pickMission(Set.of(1, 3), 5, roll);
            assertTrue(Set.of(2, 4, 5).contains(k), "" + k);
        }
        // every note already earned: any of the five (a big tip only)
        Set<Integer> seen = new HashSet<>();
        for (double roll = 0; roll < 1; roll += 0.01) seen.add(SecretAgent.pickMission(Set.of(1, 2, 3, 4, 5), 5, roll));
        assertEquals(Set.of(1, 2, 3, 4, 5), seen);
        assertEquals(5, SecretAgent.pickMission(Set.of(), 5, 0.999999));
    }

    @Test
    void sevenMinuteWindow() {
        assertEquals(-1, SecretAgent.window(419.9, 420, 450));
        assertEquals(0, SecretAgent.window(420, 420, 450));
        assertEquals(0, SecretAgent.window(449, 420, 450));
        assertEquals(0, SecretAgent.window(450, 420, 450));
        assertEquals(1, SecretAgent.window(450.1, 420, 450));
    }

    @Test
    void sameStopThreeTimes() {
        int a = 10, b = 20, other = 30;
        int p = 0;
        p = SecretAgent.aba(p, other, a, b);   // before A: does not count
        assertEquals(0, p);
        p = SecretAgent.aba(p, a, a, b);
        assertEquals(1, p);
        p = SecretAgent.aba(p, b, a, b);
        assertEquals(2, p);
        p = SecretAgent.aba(p, a, a, b);
        assertEquals(3, p);
        // a stop between A and B fails it
        assertEquals(-1, SecretAgent.aba(SecretAgent.aba(0, a, a, b), other, a, b));
        assertEquals(-1, SecretAgent.aba(2, other, a, b));
        // the same stop again is no harm
        assertEquals(1, SecretAgent.aba(1, a, a, b));
        assertEquals(2, SecretAgent.aba(2, b, a, b));
    }

    @Test
    void notesBelongToWhoEarnedThem() {
        Set<Integer> e = SecretAgent.parseNotes("");
        assertTrue(e.isEmpty());
        e = SecretAgent.parseNotes("3,1, 5,x");
        assertEquals(Set.of(1, 3, 5), e);
        assertEquals("1,3,5", SecretAgent.formatNotes(e));
        assertFalse(SecretAgent.hasAll(e, 5));
        Set<Integer> all = new HashSet<>(e);
        all.add(2);
        all.add(4);
        assertTrue(SecretAgent.hasAll(all, 5));
        assertFalse(SecretAgent.hasAll(Set.of(), 0));
        assertEquals("1,2,3,4,5", SecretAgent.formatNotes(all));
    }

    @Test
    void closedStopFromTheNoteNumbers() {
        List<double[]> road = new ArrayList<>();
        for (int i = 0; i <= 500; i++) road.add(new double[]{i * 10, 0});   // a straight road 5 km east
        long seed = SecretAgent.seed(config().notes());
        assertEquals(47192, seed);
        double[] c = SecretAgent.closedStop(seed, road, 0, 0, 1000, 3000);
        assertNotNull(c);
        double d = Math.hypot(c[0], c[1]);
        assertTrue(d >= 1000 && d <= 3000, "" + d);
        assertEquals(1, c[2], 1e-9);   // along the road
        // the same numbers, the same place; other numbers, most likely another
        assertArrayEquals(c, SecretAgent.closedStop(seed, road, 0, 0, 1000, 3000));
        double[] o = SecretAgent.closedStop(12345, road, 0, 0, 1000, 3000);
        assertNotEquals(c[0], o[0]);
        // nothing that far: the point nearest half way
        List<double[]> shortRoad = road.subList(0, 50);
        double[] n = SecretAgent.closedStop(seed, shortRoad, 0, 0, 1000, 3000);
        assertEquals(490, n[0], 1e-9);
        assertNull(SecretAgent.closedStop(seed, List.of(), 0, 0, 1000, 3000));
    }

    @Test
    void finalTimeHonksAndRiddles() {
        assertTrue(SecretAgent.finalTime(3, 33, 3, 33, 2));
        assertTrue(SecretAgent.finalTime(3, 34, 3, 33, 2));
        assertFalse(SecretAgent.finalTime(3, 35, 3, 33, 2));
        assertFalse(SecretAgent.finalTime(15, 33, 3, 33, 2));
        ArrayDeque<Long> h = new ArrayDeque<>();
        assertFalse(SecretAgent.honked(h, 0, 3, 5000));
        assertFalse(SecretAgent.honked(h, 1000, 3, 5000));
        assertTrue(SecretAgent.honked(h, 4000, 3, 5000));
        h.clear();
        SecretAgent.honked(h, 0, 3, 5000);
        SecretAgent.honked(h, 1000, 3, 5000);
        assertFalse(SecretAgent.honked(h, 7000, 3, 5000));   // too slow
        assertEquals("앞대등", SecretAgent.reversed("등대 앞"));
    }

    @Test
    void thirdKinds() {
        BusSpec s = BusTest.spec();
        BusSpec.Type santa = s.types().get("santa"), monk = s.types().get("monk"), ghost = s.types().get("ghost"),
                chatty = s.types().get("chatty"), rider = s.types().get("rider"), dog = s.types().get("dogwalker");
        // 산타: December only, more on Christmas
        assertEquals(0, santa.weightAt(Set.of(), 12, false, false, false, 11, 20));
        assertTrue(santa.weightAt(Set.of(), 12, false, false, false, 12, 10) > 0);
        assertEquals(6, santa.weightAt(Set.of(), 12, false, false, false, 12, 24) / santa.weightAt(Set.of(), 12, false, false, false, 12, 10), 1e-9);
        // 스님: mountain (and forest) stops only
        assertEquals(0, monk.weightAt(Set.of("sea"), 12, false, false, false));
        assertTrue(monk.weightAt(Set.of("mountain"), 12, false, false, false) > 0);
        assertEquals(80, monk.floor(10), 1e-9);
        // 유령: 02-04 only, no fare
        assertEquals(0, ghost.weightAt(Set.of(), 12, false, false, false));
        assertTrue(ghost.weightAt(Set.of(), 3, false, false, false) > 0);
        assertEquals(0, s.fare(1000, ghost), 1e-9);
        assertTrue(ghost.x().ghost());
        // 수다쟁이: never below 80; 배달 라이더: 60% of the time
        assertEquals(80, chatty.floor(50), 1e-9);
        assertEquals(95, chatty.floor(95), 1e-9);
        assertEquals(s.onTime(600) * 0.6, s.onTime(600, rider), 1e-9);
        assertEquals(2, dog.x().seats());
        assertEquals(1.5, dog.fare(), 1e-9);
        // every new kind talks
        for (String k : List.of("chatty", "motionsick", "mom", "dogwalker", "santa", "ghost", "rider", "monk", "youtuber", "shades")) {
            assertNotNull(s.lines(s.types().get(k), "board"), k);
            assertEquals(3, s.types().get(k).skins().size(), k);
        }
        assertNotNull(s.lines(chatty, "chat"));
        assertNotNull(s.lines(s.types().get("mom"), "cry"));
        assertNotNull(s.lines(s.types().get("motionsick"), "sick"));
    }
}
