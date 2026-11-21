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
        assertEquals(-90f, UsherPlan.DOWN_YAW, "landing faces down the stairs (east)");
        // walking down from the landing reaches the room, three wide: every step a west-facing stair with three blocks of
        // air over it, and nothing over a head (1.8) where the player straddles one step and the next (the old lintel)
        for (int z = -UsherPlan.WELL_Z; z <= UsherPlan.WELL_Z; z++) {
            for (int i = 4; i <= UsherPlan.STEPS; i++) {
                int x = -10 + i, y = UsherPlan.FLOOR - i;
                assertEquals("stair", at.get(UsherPlan.key(x, y, z)), "step " + i + " z " + z);
                for (int h = 1; h <= 3; h++) assertEquals("air", at.get(UsherPlan.key(x, y + h, z)), "step " + i + " z " + z + " +" + h);
                // standing at the top of this step (y + 1), a head reaches y + 2.8: clear in this column and the next
                for (int h = 1; h <= 2; h++) assertEquals("air", at.get(UsherPlan.key(x + 1, y + h, z)), "into step " + (i + 1) + " z " + z + " +" + h);
            }
            for (int y = UsherPlan.ROOM_Y + 1; y <= UsherPlan.ROOM_Y + 4; y++)
                assertEquals("air", at.get(UsherPlan.key(UsherPlan.ROOM_X1 + 1, y, z)), "just inside the door, z " + z + " y " + y);
            assertFalse(UsherPlan.underOpening(-6, UsherPlan.FLOOR - 3, z), "step 4 does not send them up");
            assertTrue(UsherPlan.underOpening(-7, UsherPlan.FLOOR - 2.5, z), "step 3 sends them up");
            assertTrue(UsherPlan.below(-5, UsherPlan.FLOOR - 4, z));
        }
        // walls either side of the stairs (nothing open to the void beside them)
        for (int x = UsherPlan.OPEN_X1; x <= UsherPlan.ROOM_X1; x++)
            for (int z : new int[]{-UsherPlan.WELL_Z - 1, UsherPlan.WELL_Z + 1})
                assertEquals("wall", at.get(UsherPlan.key(x, UsherPlan.ROOM_Y + 4, z)), "x " + x + " z " + z);
        assertTrue(UsherPlan.inRoom(0, UsherPlan.ROOM_Y + 1, 0));
        for (double[] who : new double[][]{UsherPlan.LEFT, UsherPlan.RIGHT}) {
            int x = (int) Math.floor(who[0]), z = (int) Math.floor(who[1]);
            assertTrue(UsherPlan.inRoom(x, UsherPlan.ROOM_Y + 1, z));
            assertEquals("air", at.get(UsherPlan.key(x, UsherPlan.ROOM_Y + 1, z)));
        }
        // the opening is over the first steps (all three wide), and the way up lands on the plaza beside it
        for (int x = UsherPlan.OPEN_X1; x <= UsherPlan.OPEN_X2; x++) {
            for (int z = UsherPlan.OPEN_Z1; z <= UsherPlan.OPEN_Z2; z++) {
                assertTrue(UsherPlan.onOpening(x, UsherPlan.FLOOR + 1, z));
                assertEquals("stair", at.get(UsherPlan.key(x, UsherPlan.FLOOR - (x + 10), z)));
            }
        }
        int ux = (int) Math.floor(UsherPlan.UP[0]), uz = (int) Math.floor(UsherPlan.UP[2]);
        assertFalse(UsherPlan.onOpening(ux, UsherPlan.UP[1], uz));
        assertTrue(Math.abs(ux) <= GuideLayout.PLAZA && Math.abs(uz) <= GuideLayout.PLAZA);
        // the statue's pedestal is on the plaza, not over the opening or the 안내원
        assertTrue(Math.abs(Statue.BX) <= GuideLayout.PLAZA && Math.abs(Statue.BZ) <= GuideLayout.PLAZA);
        assertFalse(Statue.BZ == UsherPlan.OPEN_Z);
    }
    @Test
    void answersAreCheckedLoosely() {
        List<String> name = List.of("원내안");
        assertTrue(UsherPlan.answers("원내안", name));
        assertTrue(UsherPlan.answers(" 원 내 안 이요?! ", name), "spaces, particles, punctuation");
        assertTrue(UsherPlan.answers("'원내안'이라고 쓰여 있었어요", name));
        assertFalse(UsherPlan.answers("안내원", name), "not reversed is not the answer");
        assertFalse(UsherPlan.answers("", name));
        assertFalse(UsherPlan.answers("음 잘 모르겠는데 원내안 아니면 뭐 다른 거겠죠 아마도", name), "not a whole paragraph around it");
        List<String> count = List.of("열세", "열셋", "13");
        assertTrue(UsherPlan.answers("열세 개", count));
        assertTrue(UsherPlan.answers("13개요", count));
        assertTrue(UsherPlan.answers("열셋!", count));
        assertFalse(UsherPlan.answers("열네 개", count));
        assertFalse(UsherPlan.answers("14", count));
        assertTrue(UsherPlan.answers("길라잡이 체험관", List.of("길라잡이체험관")));
        assertFalse(UsherPlan.answers("체험관", List.of("길라잡이체험관")));
        assertTrue(UsherPlan.answers("열다섯이 아니라 열여섯", List.of("열여섯", "16")));
        assertTrue(UsherPlan.answers("16x16", List.of("열여섯", "16")));
    }

    @Test
    void questionsRotate() {
        assertEquals(3, UsherPlan.nextQuestion(null, 6, 3), "first time: the first pick");
        assertEquals(4, UsherPlan.nextQuestion("3", 6, 0));
        assertEquals(0, UsherPlan.nextQuestion("5", 6, 2), "wraps");
        assertEquals(1, UsherPlan.nextQuestion("x", 6, 1));
        assertEquals(-1, UsherPlan.nextQuestion("1", 0, 0));
    }

    /** Every question in usher.yml is fair: its answer is something the guide (or the 안내원's own name) really shows. */
    @Test
    void theQuestionsAreFairAndTrue() throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("secrets/usher.yml").toFile());
        assertFalse(y.contains("chance"), "no luck: the way in is the question");
        assertEquals(30, y.getInt("question.seconds"));
        assertEquals(60, y.getInt("question.cooldown"));
        assertTrue(y.getString("lines.forget", "").contains("잊어 주세요"));
        List<Map<?, ?>> qs = y.getMapList("questions");
        assertTrue(qs.size() >= 4 && qs.size() <= 6, "four to six questions");
        Set<String> lines = new HashSet<>();
        for (Map<?, ?> q : qs) {
            String line = String.valueOf(q.get("line"));
            assertTrue(lines.add(line));
            List<String> ans = new ArrayList<>();
            for (Object a : (List<?>) q.get("answers")) ans.add(String.valueOf(a));
            assertFalse(ans.isEmpty(), line);
            for (String a : ans) assertTrue(UsherPlan.answers(a, ans), "its own answer: " + a);
            assertFalse(UsherPlan.answers(line, ans), "repeating the question is not the answer: " + line);
        }
        // the facts behind them
        List<String[]> topics = new ArrayList<>();
        try (Stream<Path> s = Files.list(PACK.resolve("guide/topics"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).toList()) {
                YamlConfiguration t = YamlConfiguration.loadConfiguration(f.toFile());
                topics.add(new String[]{String.valueOf(t.getInt("order")), t.getString("name"), f.getFileName().toString()});
            }
        }
        topics.sort(Comparator.comparingInt(r -> Integer.parseInt(r[0])));
        assertEquals(13, topics.size(), "길라잡이는 열세 개");
        assertEquals("1", topics.getFirst()[0]);
        assertEquals("13", topics.getLast()[0]);
        assertTrue(answerOf(qs, "1번").stream().anyMatch(a -> UsherPlan.answers(topics.getFirst()[1], List.of(a))), "1번 = " + topics.getFirst()[1]);
        assertTrue(answerOf(qs, "13번").stream().anyMatch(a -> UsherPlan.answers(topics.getLast()[1], List.of(a))), "13번 = " + topics.getLast()[1]);
        assertTrue(UsherPlan.answers("열세 개", answerOf(qs, "열세 개였나요")));
        YamlConfiguration land = YamlConfiguration.loadConfiguration(PACK.resolve("guide/topics/land.yml").toFile());
        assertTrue(land.saveToString().contains("16×16"), "땅은 16×16");
        assertTrue(UsherPlan.answers("열여섯", answerOf(qs, "열다섯")));
        assertTrue(UsherPlan.answers(new StringBuilder("안내원").reverse().toString(), answerOf(qs, "이름표")));
        String sign = plain(y.getString("lines.room-sign")).split("\n")[0];
        assertTrue(UsherPlan.answers(new StringBuilder(sign).reverse().toString(), answerOf(qs, "관험체")), "the room's sign read straight");
    }

    private static List<String> answerOf(List<Map<?, ?>> qs, String inLine) {
        for (Map<?, ?> q : qs)
            if (String.valueOf(q.get("line")).contains(inLine)) {
                List<String> out = new ArrayList<>();
                for (Object a : (List<?>) q.get("answers")) out.add(String.valueOf(a));
                return out;
            }
        throw new AssertionError("no question with " + inLine);
    }

    /** MiniMessage tags out (enough for the sign). */
    private static String plain(String mm) {
        return mm.replaceAll("<[^>]*>", "");
    }
}
