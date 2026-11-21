package dev.stsv.economy.secret.dongbang;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 동방해운's news warp: a player's copy only, the real lines untouched, the same page through one offer. */
class DongbangNewsTest {

    private static String real(int i) {
        return "<gray>[속보]</gray> <white>바다농산 " + i + "</white><gray>, 갱도 침수로 작업 중단… 주가 급락</gray> <#4d9bff>▼9.4%";
    }

    private static List<String> reals(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(real(i));
        return List.copyOf(l);
    }

    private static final NewsWarp.Offer OFFER = new NewsWarp.Offer("HEAD", "FRAG", "03:33 ", "물", 2, 1, 10);

    @Test
    void offerPage() {
        List<String> in = reals(12);
        List<String> out = NewsWarp.offer(in, OFFER, 42);
        assertEquals(10, out.size());
        assertEquals("HEAD", out.getFirst());
        assertNotEquals("FRAG", out.get(1));
        assertNotEquals("FRAG", out.getLast());
        assertEquals(1, out.stream().filter("FRAG"::equals).count());
        // the newest 8 real lines, all stamped, in order
        List<String> stamped = out.stream().filter(l -> l.startsWith("03:33 ")).toList();
        assertEquals(8, stamped.size());
        assertEquals(2, stamped.stream().filter(l -> l.contains("물")).count());
        assertEquals(1, stamped.stream().filter(l -> l.contains("13.0%")).count());
        assertTrue(stamped.getLast().contains("바다농산 11"));
        // same seed, same page; the input is not touched
        assertEquals(out, NewsWarp.offer(in, OFFER, 42));
        assertEquals(reals(12), in);
    }

    @Test
    void offerWithoutNews() {
        assertEquals(List.of("HEAD", "FRAG"), NewsWarp.offer(List.of(), OFFER, 1));
    }

    @Test
    void soakKeepsNameAndTags() {
        Random r = new Random(3);
        for (int i = 0; i < 50; i++) {
            String s = NewsWarp.soak(real(0), "물", r);
            assertTrue(s.contains("<white>바다농산 0</white>"), s);
            assertTrue(s.startsWith("<gray>[속보]</gray>"), s);
            assertTrue(s.contains("물"), s);
            assertFalse(s.contains("물를") || s.contains("물가 "), s);
        }
        assertEquals("<gray>갱도 물로 작업", NewsWarp.soak("<gray>갱도 침수로 작업", "물", new Random() {
            @Override
            public int nextInt(int bound) {
                return 1;
            }
        }));
        assertEquals("<white>회사</white> 1%", NewsWarp.soak("<white>회사</white> 1%", "물", new Random(1)));
    }

    @Test
    void thirteen() {
        assertEquals("x <#ff5f57>▲13.0% y", NewsWarp.thirteen("x <#ff5f57>▲6.2% y"));
        assertEquals("no move", NewsWarp.thirteen("no move"));
    }

    @Test
    void amongAndLast() {
        List<String> in = reals(10);
        Random r = new Random(5);
        for (int i = 0; i < 30; i++) {
            List<String> out = NewsWarp.among(in, "X", r, 10);
            assertEquals(10, out.size());
            assertNotEquals("X", out.getLast());
            assertTrue(out.contains("X"));
        }
        assertEquals(List.of("X"), NewsWarp.among(List.of(), "X", r, 10));
        List<String> last = NewsWarp.last(in, "END", 10);
        assertEquals(10, last.size());
        assertEquals("END", last.getLast());
        assertEquals(real(1), last.getFirst());
    }
}
