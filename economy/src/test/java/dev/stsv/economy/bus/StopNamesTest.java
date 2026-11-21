package dev.stsv.economy.bus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Stop names with spaces in admin commands ("다람쥐 고개", "<하역장> 앞"): whole names, quotes, numbers, completion. */
class StopNamesTest {

    static BusStop stop(int id, String name) {
        return new BusStop(id, name, "world", 0, 0, 0, 1, 0, 1, 4, true, true, "");
    }

    static final List<BusStop> ALL = List.of(stop(3, "다람쥐 고개"), stop(4, "다람쥐 고개 2"), stop(12, "동항 앞"), stop(15, "Market"),
            stop(16, "갈매기 쉼터"));

    @Test
    void findsByNameQuotesNumberAndLoosely() {
        assertEquals(3, StopNames.find(ALL, "다람쥐 고개").id());
        assertEquals(4, StopNames.find(ALL, "다람쥐 고개 2").id());
        assertEquals(3, StopNames.find(ALL, "\"다람쥐 고개\"").id());
        assertEquals(12, StopNames.find(ALL, "#12").id());
        assertEquals(12, StopNames.find(ALL, "12").id());
        assertEquals(15, StopNames.find(ALL, "market").id());
        assertEquals(16, StopNames.find(ALL, "갈매기쉼터").id());
        assertEquals(12, StopNames.find(ALL, "  동항   앞 ").id());
        assertNull(StopNames.find(ALL, "#99"));
        assertNull(StopNames.find(ALL, "없는 곳"));
        assertNull(StopNames.find(ALL, ""));
    }

    @Test
    void theStopAndTheWordsAfterIt() {
        // 손님 <정류장> <수>: the longest name that fits, a number left
        StopNames.Match m = StopNames.split(ALL, new String[]{"손님", "다람쥐", "고개", "2", "5"}, 1, 1);
        assertEquals(4, m.stop().id());
        assertArrayEquals(new String[]{"5"}, m.rest());
        m = StopNames.split(ALL, new String[]{"손님", "다람쥐", "고개", "5"}, 1, 1);
        assertEquals(3, m.stop().id());
        assertArrayEquals(new String[]{"5"}, m.rest());
        // 이름 <정류장> <새 이름>: quoted, or by its number
        m = StopNames.split(ALL, new String[]{"이름", "\"다람쥐", "고개\"", "솔밭", "고개"}, 1, 1);
        assertEquals(3, m.stop().id());
        assertArrayEquals(new String[]{"솔밭", "고개"}, m.rest());
        m = StopNames.split(ALL, new String[]{"이름", "#12", "새", "이름"}, 1, 1);
        assertEquals(12, m.stop().id());
        m = StopNames.split(ALL, new String[]{"이름", "\"동항\"", "x"}, 1, 1);
        assertNull(m);
        // the whole name only (no word left for a new name): no stop
        assertEquals(3, StopNames.split(ALL, new String[]{"이름", "다람쥐", "고개"}, 1, 0).stop().id());
        assertNull(StopNames.split(ALL, new String[]{"이름", "다람쥐"}, 1, 1));
    }

    @Test
    void newNamesThatReadAsNumbersOrAreTaken() {
        assertTrue(StopNames.numberLike("12"));
        assertTrue(StopNames.numberLike("#7"));
        assertFalse(StopNames.numberLike("7번가"));
        assertTrue(StopNames.taken(ALL, "다람쥐고개", null));
        assertFalse(StopNames.taken(ALL, "다람쥐 고개", 3));
        assertFalse(StopNames.taken(ALL, "솔밭", null));
    }

    @Test
    void completionQuotesNamesWithSpacesAndOffersNumbers() {
        List<String> first = StopNames.complete(ALL, new String[]{"삭제", "다"}, 1);
        assertTrue(first.contains("\"다람쥐 고개\""), first.toString());
        assertTrue(first.contains("\"다람쥐 고개 2\""), first.toString());
        assertFalse(first.contains("\"동항 앞\""));
        List<String> all = StopNames.complete(ALL, new String[]{"삭제", ""}, 1);
        assertTrue(all.contains("Market"));
        assertTrue(all.contains("#12"));
        assertEquals(List.of("#12"), StopNames.complete(ALL, new String[]{"삭제", "#1"}, 1).stream().filter(s -> s.equals("#12")).toList());
        assertTrue(StopNames.complete(ALL, new String[]{"삭제", "#1"}, 1).contains("#15"));
        // the next word of a name typed without quotes
        assertEquals(List.of("고개"), StopNames.complete(ALL, new String[]{"삭제", "다람쥐", ""}, 1));
        // inside quotes: the last word closes them
        List<String> q = StopNames.complete(ALL, new String[]{"삭제", "\"다람쥐", "고"}, 1);
        assertTrue(q.contains("고개\""), q.toString());
        assertTrue(q.contains("고개"), q.toString());   // "다람쥐 고개 2" goes on
    }
}
