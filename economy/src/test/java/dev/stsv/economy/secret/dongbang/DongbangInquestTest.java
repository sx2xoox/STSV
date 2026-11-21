package dev.stsv.economy.secret.dongbang;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 동방해운's boardroom deduction: every generated run has exactly one answer, and it is the planted one. */
class DongbangInquestTest {

    @Test
    void everyRunHasExactlyOneAnswer() {
        Map<Inquest.Kind, Integer> liarKinds = new HashMap<>();
        int[] seats = new int[Inquest.SEATS];
        for (long seed = 0; seed < 3000; seed++) {
            Inquest q = Inquest.make(seed * 0x9E3779B97F4A7C15L, Inquest.Sizes.DEFAULT);
            assertEquals(Inquest.SEATS, q.claims.size());
            assertEquals(List.of(q.liar), Inquest.solve(q.claims), "seed " + seed);
            // everyone aboard tells the truth, the one not aboard does not
            for (int s = 0; s < Inquest.SEATS; s++) assertEquals(s != q.liar, Inquest.truth(q.claims.get(s), s, q.liar));
            // no one names themselves, no truthful line points straight at the answer alone
            for (int s = 0; s < Inquest.SEATS; s++) {
                Inquest.Claim c = q.claims.get(s);
                assertNotEquals(s, c.a());
                assertNotEquals(s, c.b());
            }
            liarKinds.merge(q.claims.get(q.liar).kind(), 1, Integer::sum);
            seats[q.liar]++;
        }
        // variety: the one not aboard says different kinds of things, and sits anywhere
        assertTrue(liarKinds.size() >= 4, liarKinds.toString());
        for (int n : seats) assertTrue(n > 100);
    }

    @Test
    void sameSeedSamePuzzleNextRunDiffers() {
        Inquest a = Inquest.make(42, Inquest.Sizes.DEFAULT), b = Inquest.make(42, Inquest.Sizes.DEFAULT), c = Inquest.make(43, Inquest.Sizes.DEFAULT);
        assertEquals(a.liar, b.liar);
        assertEquals(a.claims, b.claims);
        assertNotEquals(a.claims, c.claims);
    }

    @Test
    void mostRunsNeedCrossChecking() {
        int hard = 0;
        for (long seed = 0; seed < 1000; seed++) {
            Inquest q = Inquest.make(seed, Inquest.Sizes.DEFAULT);
            if (Inquest.crossChecks(q.claims, q.liar) >= 3) hard++;
        }
        assertTrue(hard > 600, "hard runs " + hard);
    }

    /** A worked example (the doc's): solved by hand, checked here. */
    @Test
    void docExample() {
        List<Inquest.Claim> cl = new ArrayList<>();
        // seat 0..11; the one not aboard is 4 (row 0, next to the empty chair's neighbour)
        cl.add(new Inquest.Claim(Inquest.Kind.ABOARD, 7, -1, 0));          // 0: 7 was aboard
        cl.add(new Inquest.Claim(Inquest.Kind.EITHER, 4, 9, 0));           // 1: 4 or 9 was not aboard
        cl.add(new Inquest.Claim(Inquest.Kind.SELF, -1, -1, 0));           // 2: I was aboard
        cl.add(new Inquest.Claim(Inquest.Kind.OTHER_ROW_ALL, -1, -1, 0));  // 3: the other row were all aboard
        cl.add(new Inquest.Claim(Inquest.Kind.MY_ROW_ALL, -1, -1, 0));     // 4: (lie) my row were all aboard
        cl.add(new Inquest.Claim(Inquest.Kind.ACROSS, -1, -1, 0));         // 5: 11 (across) was aboard
        cl.add(new Inquest.Claim(Inquest.Kind.ABOARD, 2, -1, 0));          // 6: 2 was aboard
        cl.add(new Inquest.Claim(Inquest.Kind.FACT_TRUE, -1, -1, 0));      // 7: 03:33
        cl.add(new Inquest.Claim(Inquest.Kind.MY_ROW_ALL, -1, -1, 0));     // 8: my row (6..11) were all aboard
        cl.add(new Inquest.Claim(Inquest.Kind.CHAIR_SIDE, -1, -1, 0));     // 9: 10 was aboard
        cl.add(new Inquest.Claim(Inquest.Kind.EITHER, 4, 1, 0));           // 10: 4 or 1 was not aboard
        cl.add(new Inquest.Claim(Inquest.Kind.DOOR_SIDE, -1, -1, 0));      // 11: 10 was aboard
        assertEquals(List.of(4), Inquest.solve(cl));
    }

    @Test
    void wordsUseNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 12; i++) names.add("P" + i);
        Map<String, List<String>> t = Map.of("either", List.of("{a} 아니면 {b}"));
        assertEquals("P3 아니면 P9", Inquest.text(new Inquest.Claim(Inquest.Kind.EITHER, 3, 9, 5), t, names));
        assertEquals("…", Inquest.text(new Inquest.Claim(Inquest.Kind.SELF, -1, -1, 0), t, names));
    }
}
