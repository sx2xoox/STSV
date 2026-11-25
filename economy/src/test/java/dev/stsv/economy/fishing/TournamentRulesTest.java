package dev.stsv.economy.fishing;

import dev.stsv.economy.fishing.TournamentRules.Award;
import dev.stsv.economy.fishing.TournamentRules.Catch;
import dev.stsv.economy.fishing.TournamentRules.Due;
import dev.stsv.economy.fishing.TournamentRules.Entry;
import dev.stsv.economy.fishing.TournamentRules.Kind;
import dev.stsv.economy.fishing.TournamentRules.Settings;
import dev.stsv.economy.fishing.TournamentRules.Target;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 낚시대회: the clock, the event draw, scoring, ranking, prizes (TournamentRules). */
class TournamentRulesTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Settings S = new Settings(10, 60, 2, 3, 24 * 3_600_000L, 20, List.of(2, 1, 0), List.of(0L, 0L, 1000L), 2,
            List.of(0.2, 0.45, 0.7, 0.9), Set.of("mushroom"));

    static long at(int h, int m, int s) {
        return ZonedDateTime.of(2026, 11, 25, h, m, s, 0, SEOUL).toInstant().toEpochMilli();
    }

    @Test
    void everyHourOnTheHour() {
        assertEquals(at(14, 0, 0), TournamentRules.hourStart(at(14, 37, 12), SEOUL));
        assertEquals(at(15, 0, 0), TournamentRules.nextStart(at(14, 37, 12), SEOUL));
        assertEquals(at(15, 0, 0), TournamentRules.nextStart(at(15, 0, 0), SEOUL), "on the hour: that hour");
        // a minute before: the notice, once
        assertEquals(Due.NOTICE, TournamentRules.due(at(14, 59, 0), SEOUL, S, 0, at(14, 0, 0)));
        assertEquals(Due.NONE, TournamentRules.due(at(14, 59, 30), SEOUL, S, at(15, 0, 0), at(14, 0, 0)));
        assertEquals(Due.NONE, TournamentRules.due(at(14, 58, 59), SEOUL, S, 0, at(14, 0, 0)));
        // on the hour: the start, once; a server up at :04 still runs it, one at :10 or later does not
        assertEquals(Due.START, TournamentRules.due(at(15, 0, 0), SEOUL, S, at(15, 0, 0), at(14, 0, 0)));
        assertEquals(Due.NONE, TournamentRules.due(at(15, 0, 1), SEOUL, S, at(15, 0, 0), at(15, 0, 0)));
        assertEquals(Due.START, TournamentRules.due(at(15, 4, 0), SEOUL, S, 0, 0));
        assertEquals(Due.NONE, TournamentRules.due(at(15, 10, 0), SEOUL, S, 0, 0));
        assertEquals(Due.NONE, TournamentRules.due(at(15, 30, 0), SEOUL, S, 0, at(15, 0, 0)));
    }

    @Test
    void neverOneOfTheLastTwo() {
        List<Kind> all = List.of(Kind.values());
        Random r = new Random(3);
        for (int i = 0; i < 500; i++) {
            Kind k = TournamentRules.pick(all, List.of(Kind.BIG, Kind.COUNT, Kind.RARE), 2, r);
            assertNotEquals(Kind.COUNT, k);
            assertNotEquals(Kind.RARE, k);
        }
        // an older one may come back
        Set<Kind> seen = EnumSet.noneOf(Kind.class);
        for (int i = 0; i < 2000; i++) seen.add(TournamentRules.pick(all, List.of(Kind.BIG, Kind.COUNT, Kind.RARE), 2, r));
        assertTrue(seen.contains(Kind.BIG));
        assertEquals(Kind.VARIETY, TournamentRules.pick(List.of(Kind.VARIETY), List.of(Kind.VARIETY), 2, r), "nothing else: any");
    }

    @Test
    void whatCanBeHeldNow() {
        FishSpec f = FishSpec.parse(TrapSpecTest.yaml("fish.yml"), new ArrayList<>());
        FishSpec.Moment noon = new FishSpec.Moment(6000, false, false, 1), night = new FishSpec.Moment(18000, false, false, 0);
        assertFalse(TournamentRules.available(f, noon, S).contains(Kind.MOMENT), "no fish only bites at noon in clear weather");
        assertTrue(TournamentRules.available(f, night, S).contains(Kind.MOMENT));
        assertTrue(TournamentRules.available(f, noon, S).containsAll(List.of(Kind.SPECIES, Kind.COLOUR, Kind.HABITAT, Kind.BIG)));
        for (FishSpec.Species s : TournamentRules.speciesNow(f, noon)) {
            assertTrue(f.rarities().get(s.rarity()).index() <= 1, s.id());
            assertTrue(s.when() == null, s.id() + " bites at noon");
        }
        assertTrue(TournamentRules.speciesNow(f, night).stream().anyMatch(s -> "night".equals(s.when())));
        assertFalse(TournamentRules.habitats(f, S).contains("mushroom"));
        assertFalse(TournamentRules.habitats(f, S).contains("special"));
        // every fish has a colour of fish.yml colors
        for (FishSpec.Species s : f.species().values()) assertTrue(f.colours().containsKey(s.colour()), s.id());
    }

    @Test
    void gradeAndWeight() {
        List<Double> cuts = S.gradeCuts();
        assertEquals(0, TournamentRules.grade(20, 60, 20, cuts), "잔챙이");
        assertEquals(1, TournamentRules.grade(20, 60, 28, cuts), "보통");
        assertEquals(2, TournamentRules.grade(20, 60, 40, cuts), "씨알 굵은");
        assertEquals(3, TournamentRules.grade(20, 60, 50, cuts), "대물");
        assertEquals(4, TournamentRules.grade(20, 60, 60, cuts), "월척");
        assertEquals(0.9, TournamentRules.weight(30, "f"), 1e-9);
        assertEquals(10, TournamentRules.weight(100, "f"), 1e-9);
        assertEquals(5, TournamentRules.weight(100, "l"), 1e-9, "a long fish is lighter");
        assertTrue(TournamentRules.weight(100, "p") > TournamentRules.weight(100, "f"));
    }

    static Catch fish(String species, int rarity, String water, String colour, boolean moment, double size) {
        return new Catch(species, rarity, water, colour, moment, size, TournamentRules.weight(size, "f"));
    }

    static Entry entry(int n) {
        return new Entry(new UUID(0, n), "p" + n);
    }

    @Test
    void scoringPerEvent() {
        Target t = new Target("mackerel", "sea", "blue");
        Catch a = fish("mackerel", 0, "sea", "blue", false, 30), b = fish("carp", 1, "river", "yellow", false, 80),
                c = fish("marlin", 3, "deep", "blue", false, 300), d = fish("sweetfish", 2, "river", "silver", true, 20);
        double[][] want = {
                // BIG, SMALL, WEIGHT, COUNT, SPECIES, HABITAT, VARIETY, COLOUR, MOMENT, RARE
                {300, 20, 0.9 + 6.4 + 90 + 0.4, 4, 1, 1, 4, 2, 1, 6}};
        Kind[] kinds = Kind.values();
        for (int i = 0; i < kinds.length; i++) {
            Entry e = entry(1);
            for (Catch x : List.of(a, b, c, d)) TournamentRules.add(kinds[i], t, e, x, 1);
            assertEquals(want[0][i], e.score, 1e-9, kinds[i].name());
            assertEquals(4, e.catches);
        }
        // a catch that scores nothing in the event: a participant without a score, not ranked
        Entry e = entry(2);
        assertFalse(TournamentRules.add(Kind.SPECIES, t, e, b, 1));
        assertEquals(1, e.catches);
        assertTrue(TournamentRules.rank(Kind.SPECIES, List.of(e)).isEmpty());
    }

    @Test
    void rankingAndTies() {
        Entry a = entry(1), b = entry(2), c = entry(3);
        TournamentRules.add(Kind.BIG, Target.NONE, a, fish("x", 0, "sea", null, false, 50), 10);
        TournamentRules.add(Kind.BIG, Target.NONE, b, fish("x", 0, "sea", null, false, 70), 20);
        TournamentRules.add(Kind.BIG, Target.NONE, c, fish("x", 0, "sea", null, false, 70), 15);
        assertEquals(List.of(c, b, a), TournamentRules.rank(Kind.BIG, List.of(a, b, c)), "a tie: whoever got there first");
        // 작은 고기: the lowest wins
        Entry s1 = entry(4), s2 = entry(5);
        TournamentRules.add(Kind.SMALL, Target.NONE, s1, fish("x", 0, "sea", null, false, 12), 1);
        TournamentRules.add(Kind.SMALL, Target.NONE, s1, fish("x", 0, "sea", null, false, 9), 2);
        TournamentRules.add(Kind.SMALL, Target.NONE, s2, fish("x", 0, "sea", null, false, 10), 3);
        assertEquals(9, s1.score, 1e-9);
        assertEquals(List.of(s1, s2), TournamentRules.rank(Kind.SMALL, List.of(s2, s1)));
        assertEquals("70.0", TournamentRules.number(Kind.BIG, 70));
        assertEquals("12", TournamentRules.number(Kind.COUNT, 12));
    }

    @Test
    void sharedAddressesCountOnce() {
        Entry a = entry(1), b = entry(2), c = entry(3), d = entry(4);
        a.ips.add("1.1.1.1");
        b.ips.add("1.1.1.1");
        assertEquals(1, TournamentRules.participants(List.of(a, b)), "two accounts, one address");
        c.ips.addAll(List.of("2.2.2.2", "1.1.1.1"));   // linked through a's address
        d.ips.add("3.3.3.3");
        assertEquals(2, TournamentRules.participants(List.of(a, b, c, d)));
        assertEquals(1, TournamentRules.participants(List.of(entry(9))), "no address: alone");
        assertEquals(2, TournamentRules.participants(List.of(entry(9), entry(8))));
    }

    static List<Entry> ranked(int... catches) {
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < catches.length; i++) {
            Entry e = entry(i + 1);
            e.catches = catches[i];
            e.scored = true;
            e.score = 100 - i;
            out.add(e);
        }
        return out;
    }

    @Test
    void prizes() {
        List<Entry> r = ranked(5, 4, 3, 9);
        List<Award> w = TournamentRules.awards(r, 4, S, u -> false);
        assertEquals(3, w.size());
        assertEquals(new Award(r.get(0), 1, 2, 0, TournamentRules.Short.NONE), w.get(0));
        assertEquals(new Award(r.get(1), 2, 1, 0, TournamentRules.Short.NONE), w.get(1));
        assertEquals(new Award(r.get(2), 3, 0, 1000, TournamentRules.Short.NONE), w.get(2));
        // one participant (or two accounts on one address): no prizes
        assertTrue(TournamentRules.awards(ranked(5), 1, S, u -> false).isEmpty());
        // fewer than 3 catches: nothing, and the place is not passed down
        List<Entry> few = ranked(2, 5, 5);
        w = TournamentRules.awards(few, 3, S, u -> false);
        assertEquals(new Award(few.get(0), 1, 0, 0, TournamentRules.Short.FEW_CATCHES), w.get(0));
        assertEquals(1, w.get(1).stars());
        // a new account: no stars (gold still)
        List<Entry> fresh = ranked(5, 5, 5);
        w = TournamentRules.awards(fresh, 3, S, u -> true);
        assertEquals(TournamentRules.Short.NEW_ACCOUNT, w.get(0).reason());
        assertEquals(0, w.get(0).stars());
        assertEquals(1000, w.get(2).gold());
        assertEquals(TournamentRules.Short.NONE, w.get(2).reason(), "3rd: gold only, a new account takes it");
    }

    @Test
    void dailyStarCap() {
        assertEquals(2, TournamentRules.capped(2, 0, 20));
        assertEquals(2, TournamentRules.capped(2, 18, 20));
        assertEquals(1, TournamentRules.capped(2, 19, 20), "what fits");
        assertEquals(0, TournamentRules.capped(2, 20, 20));
        assertEquals(0, TournamentRules.capped(2, 25, 20));
    }
}
