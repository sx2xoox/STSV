package dev.stsv.economy.secret.usher;

import dev.stsv.economy.guide.GuideLayout;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 안내원: the walk is the guide's numbering backwards (every station once), stages map to pictures found, the false
 * 안내원's words, and the room under the plaza stays under the plaza floor with a way down and back up.
 */
class UsherTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    static List<String> layout() {
        List<String> out = new ArrayList<>();
        for (GuideLayout.Station s : GuideLayout.stations()) out.add(s.id());
        return out;
    }

    /** The guide's menu order (topics by their order), as stations. */
    static List<String> menu() throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (Stream<Path> s = Files.list(PACK.resolve("guide/topics"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).toList()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                rows.add(new String[]{String.valueOf(y.getInt("order")), y.getString("station")});
            }
        }
        rows.sort(Comparator.comparingInt(r -> Integer.parseInt(r[0])));
        List<String> out = new ArrayList<>();
        for (String[] r : rows) out.add(r[1]);
        return out;
    }

    @Test
    void theWalkIsTheGuidesNumbersBackwards() throws Exception {
        List<String> menu = menu();
        List<String> order = UsherPlan.order(menu, layout());
        assertEquals(UsherPlan.PICTURES, order.size());
        assertEquals(new HashSet<>(layout()), new HashSet<>(order));
        assertEquals(menu.getLast(), order.getFirst(), "starts at the guide's last number");
        assertEquals(menu.getFirst(), order.getLast(), "ends at its first");
        for (String id : order) assertTrue(Pictures.WHAT.containsKey(id), "a wrong picture for " + id);
        assertEquals(13, new HashSet<>(Pictures.WHAT.values()).size(), "thirteen different pictures");
        // a station no topic uses still gets its turn
        List<String> partial = UsherPlan.order(List.of("jobs", "farming"), layout());
        assertEquals(13, partial.size());
        assertEquals(List.of("farming", "jobs"), partial.subList(11, 13));
    }

    @Test
    void stagesAndClicks() {
        List<String> order = List.of("c", "b", "a");
        assertEquals(1, UsherPlan.stage(0));
        assertEquals(14, UsherPlan.stage(13));
        assertEquals(0, UsherPlan.found(0));
        assertEquals(0, UsherPlan.found(1));
        assertEquals(13, UsherPlan.found(14));
        assertEquals(13, UsherPlan.found(15));
        assertEquals("c", UsherPlan.next(order, 0));
        assertNull(UsherPlan.next(order, 3));
        assertEquals(UsherPlan.Click.RIGHT, UsherPlan.click(order, 1, "b"));
        assertEquals(UsherPlan.Click.OUT_OF_TURN, UsherPlan.click(order, 1, "a"));
        assertEquals(UsherPlan.Click.DONE, UsherPlan.click(order, 1, "c"));
        assertEquals(-1, UsherPlan.distance(0));
        for (int i = 1; i < 13; i++) assertEquals(1, UsherPlan.distance(i) - UsherPlan.distance(i + 1), 1e-9, "a block closer each time");
    }

    @Test
    void theFalseOneSaysTheirWords() {
        List<String> real = List.of("r0", "r1", "r2");
        assertEquals("내 말", UsherPlan.fakeLine(List.of("내 말", "또"), real, 0));
        assertEquals("또", UsherPlan.fakeLine(List.of("내 말", "또"), real, 1));
        // nothing said: the true one's line, one beat late
        assertEquals("…", UsherPlan.fakeLine(List.of(), real, 0));
        assertEquals("r0", UsherPlan.fakeLine(List.of(), real, 1));
        assertEquals("r1", UsherPlan.realLine(real, 1));
        assertEquals("r2", UsherPlan.fakeLine(List.of(), real, 3));

        String raw = null;
        for (int i = 0; i < 15; i++) raw = UsherPlan.addSaid(raw, "2026-11-21", "말 " + i, 12);
        List<String> today = UsherPlan.said(raw, "2026-11-21");
        assertEquals(12, today.size());
        assertEquals("말 14", today.getLast());
        assertTrue(UsherPlan.said(raw, "2026-11-22").isEmpty(), "another day's words are not today's");
        assertEquals("새 날", UsherPlan.said(UsherPlan.addSaid(raw, "2026-11-22", "새 날", 12), "2026-11-22").getFirst());
        assertEquals(41, UsherPlan.said(UsherPlan.addSaid(null, "d", "가".repeat(60), 12), "d").getFirst().length());
    }

    @Test
    void recordsInWords() {
        // 2026-11-20 18:17 UTC = 2026-11-21 03:17 KST
        long t = java.time.ZonedDateTime.of(2026, 11, 20, 18, 17, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals("03:17", UsherPlan.clock(t));
        assertEquals("2026.11.21", UsherPlan.date(t));
        assertEquals(120, UsherPlan.rough(118));
        assertEquals(-40, UsherPlan.rough(-41));
        long day = 86_400_000L;
        assertEquals("아까", UsherPlan.when(t, t + 3_600_000L));
        assertEquals("어제", UsherPlan.when(t, t + day));
        assertEquals("그저께", UsherPlan.when(t, t + 2 * day));
        assertEquals("며칠 전에", UsherPlan.when(t, t + 5 * day));
    }

    @Test
    void theRoomStaysUnderThePlaza() {
        Map<Long, String> at = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        for (UsherPlan.Cell c : UsherPlan.room()) {
            assertTrue(seen.add(UsherPlan.key(c.x(), c.y(), c.z())), "one kind per block " + c);
            at.put(UsherPlan.key(c.x(), c.y(), c.z()), c.kind());
            assertTrue(Math.abs(c.x()) <= GuideLayout.PLAZA && Math.abs(c.z()) <= GuideLayout.PLAZA, "under the plaza: " + c);
            assertTrue(c.y() < UsherPlan.FLOOR && c.y() > GuideLayout.FLOOR_Y - 12, "below the floor, above the fall line: " + c);
        }
        // the landing: a stair to stand on, air for the head
        int[] down = {(int) Math.floor(UsherPlan.DOWN[0]), (int) UsherPlan.DOWN[1], (int) Math.floor(UsherPlan.DOWN[2])};
        assertEquals("stair", at.get(UsherPlan.key(down[0], down[1] - 1, down[2])));
        assertEquals("air", at.get(UsherPlan.key(down[0], down[1], down[2])));
        assertEquals("air", at.get(UsherPlan.key(down[0], down[1] + 1, down[2])));
        assertFalse(UsherPlan.onOpening(down[0], UsherPlan.DOWN[1], down[2]), "landing does not send them down again");
        assertFalse(UsherPlan.underOpening(down[0], UsherPlan.DOWN[1], down[2]), "landing does not send them up");
        // walking down from the landing reaches the room; every step has room for a head
        for (int i = 4; i <= UsherPlan.STEPS; i++) {
            int x = -10 + i, y = UsherPlan.FLOOR - i;
            assertEquals("stair", at.get(UsherPlan.key(x, y, 0)));
            assertEquals("air", at.get(UsherPlan.key(x, y + 1, 0)), "step " + i);
            assertEquals("air", at.get(UsherPlan.key(x, y + 2, 0)), "step " + i);
        }
        assertTrue(UsherPlan.inRoom(0, UsherPlan.ROOM_Y + 1, 0));
        for (double[] who : new double[][]{UsherPlan.LEFT, UsherPlan.RIGHT}) {
            int x = (int) Math.floor(who[0]), z = (int) Math.floor(who[1]);
            assertTrue(UsherPlan.inRoom(x, UsherPlan.ROOM_Y + 1, z));
            assertEquals("air", at.get(UsherPlan.key(x, UsherPlan.ROOM_Y + 1, z)));
        }
        // the opening is over the first steps, and the way up lands on the plaza beside it
        for (int x = UsherPlan.OPEN_X1; x <= UsherPlan.OPEN_X2; x++) {
            assertTrue(UsherPlan.onOpening(x, UsherPlan.FLOOR + 1, UsherPlan.OPEN_Z));
            assertEquals("stair", at.get(UsherPlan.key(x, UsherPlan.FLOOR - (x + 10), 0)));
        }
        int ux = (int) Math.floor(UsherPlan.UP[0]), uz = (int) Math.floor(UsherPlan.UP[2]);
        assertFalse(UsherPlan.onOpening(ux, UsherPlan.UP[1], uz));
        assertTrue(Math.abs(ux) <= GuideLayout.PLAZA && Math.abs(uz) <= GuideLayout.PLAZA);
        // the statue's pedestal is on the plaza, not over the opening or the 안내원
        assertTrue(Math.abs(Statue.BX) <= GuideLayout.PLAZA && Math.abs(Statue.BZ) <= GuideLayout.PLAZA);
        assertFalse(Statue.BZ == UsherPlan.OPEN_Z);
    }
}
