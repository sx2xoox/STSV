package dev.stsv.economy.store;

import dev.stsv.economy.bus.BusSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 진상 응대 12종 (README "편의점" 진상 손님) and 신분증 확인: each is a bus kind that comes to the store, has its buttons and lines,
 * and what an answer does depends on the customer's mood and who else is there.
 */
class TroubleTest {

    static final Trouble.Numbers N = Trouble.Numbers.DEFAULT;

    static Trouble.Context ctx(double mood) {
        return new Trouble.Context(mood, false, false, 5000, 1500, 0);
    }

    @Test
    void twelveTroublesOfTheBusPeople() {
        assertEquals(12, Trouble.Kind.values().length);
        StoreRules r = StoreRulesTest.rules();
        StoreSpec s = StoreSpecTest.spec();
        BusSpec bus = StoreSpecTest.bus();
        for (Trouble.Kind k : Trouble.Kind.values()) {
            assertNotNull(bus.types().get(k.type), k.type + " is a bus kind");
            assertTrue(bus.types().get(k.type).weight() > 0, k.type);
            assertTrue(s.customers().containsKey(k.type), k.type + " comes to the store");
            StoreRules.TroubleText t = r.troubles().get(k);
            assertNotNull(t, k.id);
            for (String key : k.talk) assertFalse(t.lines(key).isEmpty(), k.id + " " + key);
            if (k.choices.size() > 0 && k != Trouble.Kind.TOURIST) for (String c : k.choices) assertNotNull(t.buttons().get(c), k.id + " " + c);
            if (k.stage != Trouble.Stage.NONE && k != Trouble.Kind.STEAL) assertTrue(k.choices.size() >= 2 && k.choices.size() <= 3, "2~3 buttons: " + k.id);
            assertEquals(k, Trouble.Kind.of(k.id));
        }
        assertEquals(Trouble.Kind.BANMAL, Trouble.Kind.forType("rude"));
        assertNull(Trouble.Kind.forType("ghost"), "the 새벽 bell is not a customer");
        assertNull(Trouble.Kind.forType("office"));
        assertNull(Trouble.resolve(Trouble.Kind.REFUND, "allow", ctx(50), N, 0), "not one of its choices");
    }

    /** 환불 손님: refusing is right (a bonus); how they take it depends on their mood; giving in costs the worker. */
    @Test
    void refusingARefund() {
        Trouble.Outcome calm = Trouble.resolve(Trouble.Kind.REFUND, "refuse", ctx(60), N, 0);
        assertEquals(Trouble.Result.LEAVE_HANDLED, calm.result(), "out, but not a lost customer");
        assertEquals(N.good(), calm.bonus());
        assertEquals(0, calm.won());
        assertEquals("refuse-ok", calm.talk());
        Trouble.Outcome cross = Trouble.resolve(Trouble.Kind.REFUND, "refuse", ctx(20), N, 0);
        assertEquals("refuse-bad", cross.talk());
        assertTrue(cross.others() < 0, "she makes a scene: the others feel it");
        assertEquals(N.good(), cross.bonus(), "still the right answer");
        Trouble.Outcome gave = Trouble.resolve(Trouble.Kind.REFUND, "refund", ctx(20), N, 0);
        assertEquals(1500, gave.won(), "the refund is the worker's money");
        assertTrue(gave.mood() > 0);
        assertEquals(0, gave.bonus());
    }

    @Test
    void theThugBehavesWhenGrandmaIsThere() {
        Trouble.Context alone = new Trouble.Context(30, false, false, 5000, 0, 0), withGrandma = new Trouble.Context(30, true, false, 5000, 0, 0);
        assertFalse(Trouble.skip(Trouble.Kind.CREDIT, alone));
        assertTrue(Trouble.skip(Trouble.Kind.CREDIT, withGrandma), "he pays like anyone");
        assertFalse(Trouble.skip(Trouble.Kind.BANMAL, withGrandma), "only the 건달");
        // alone: firm works when he is in a good mood, else he walks out
        assertEquals(Trouble.Result.GO_ON, Trouble.resolve(Trouble.Kind.CREDIT, "firm", new Trouble.Context(60, false, false, 5000, 0, 0), N, 0).result());
        assertEquals(Trouble.Result.LEAVE_LOST, Trouble.resolve(Trouble.Kind.CREDIT, "firm", alone, N, 0).result());
        Trouble.Outcome credit = Trouble.resolve(Trouble.Kind.CREDIT, "allow", alone, N, 0);
        assertEquals(Trouble.Result.FREE, credit.result());
        assertEquals(5000, credit.won(), "외상 = the bill on the worker");
    }

    @Test
    void personalityDecides() {
        // 반말 아저씨: asking is always fine; scolding only when he is in a good mood
        assertEquals(Trouble.Result.GO_ON, Trouble.resolve(Trouble.Kind.BANMAL, "ask", ctx(10), N, 0).result());
        assertEquals(Trouble.Result.GO_ON, Trouble.resolve(Trouble.Kind.BANMAL, "scold", ctx(70), N, 0).result());
        assertEquals(Trouble.Result.LEAVE_LOST, Trouble.resolve(Trouble.Kind.BANMAL, "scold", ctx(40), N, 0).result());
        assertEquals("guess-ok", Trouble.resolve(Trouble.Kind.BANMAL, "guess", ctx(50), N, 0.2).talk());
        assertEquals("guess-bad", Trouble.resolve(Trouble.Kind.BANMAL, "guess", ctx(50), N, 0.8).talk());
        // 초등학생: 100원 off is the worker's; refusing puts a thing back
        Trouble.Outcome off = Trouble.resolve(Trouble.Kind.KID, "discount", ctx(50), N, 0);
        assertEquals(100, off.won());
        assertEquals(Trouble.Result.EXACT, off.result());
        assertEquals(Trouble.Result.PUT_BACK, Trouble.resolve(Trouble.Kind.KID, "refuse", ctx(50), N, 0).result());
        // 할머니: waiting earns a candy
        assertEquals("candy", Trouble.resolve(Trouble.Kind.GRANDMA, "wait", ctx(50), N, 0).gift());
        assertNull(Trouble.resolve(Trouble.Kind.GRANDMA, "hurry", ctx(50), N, 0).gift());
        // 취객: a 숙취해소제 is a tip; waking him depends on his mood
        assertEquals(N.hangover(), Trouble.resolve(Trouble.Kind.DRUNK, "hangover", ctx(10), N, 0).bonus());
        assertEquals("wake-ok", Trouble.resolve(Trouble.Kind.DRUNK, "wake", ctx(60), N, 0).talk());
        assertEquals("wake-bad", Trouble.resolve(Trouble.Kind.DRUNK, "wake", ctx(30), N, 0).talk());
        assertEquals(Trouble.Result.LEAVE_HANDLED, Trouble.slept(Trouble.Kind.DRUNK).result(), "nobody saw to him: he goes by himself");
        // 공시생: only a 폐기 that is there can be given
        Trouble.Context waste = new Trouble.Context(50, false, true, 1200, 0, 0);
        assertTrue(Trouble.resolve(Trouble.Kind.EXAM, "give", waste, N, 0).waste());
        assertEquals("none", Trouble.resolve(Trouble.Kind.EXAM, "give", ctx(50), N, 0).talk());
        // 관광객 · 택배
        assertTrue(Trouble.resolve(Trouble.Kind.TOURIST, "right", ctx(50), N, 0).mood() > 0);
        assertTrue(Trouble.resolve(Trouble.Kind.TOURIST, "wrong", ctx(50), N, 0).mood() < 0);
        assertEquals(N.parcel(), Trouble.resolve(Trouble.Kind.PARCEL, "accept", ctx(50), N, 0).bonus());
        List<String> wrong = Trouble.wrong("snack", List.of("snack", "noodle", "fridge"), new Random(1));
        assertEquals(1, wrong.size());
        assertFalse(wrong.contains("snack"));
    }

    @Test
    void theLotteryAtTheCounter() {
        assertEquals(2, Trouble.scratch(N, 0.01));
        assertEquals(1, Trouble.scratch(N, 0.1));
        assertEquals(0, Trouble.scratch(N, 0.9));
        Trouble.Outcome big = Trouble.resolve(Trouble.Kind.LOTTERY, "cheer", new Trouble.Context(50, false, false, 1000, 0, 2), N, 0);
        assertTrue(big.fanfare(), "the store cheers");
        assertTrue(big.tip() > 1 && big.others() > 0);
        assertEquals("cheer-none", Trouble.resolve(Trouble.Kind.LOTTERY, "cheer", ctx(50), N, 0).talk());
        assertTrue(Trouble.resolve(Trouble.Kind.LOTTERY, "shrug", ctx(50), N, 0).mood() < 0, "no comfort for a 꽝");
        // a rare win: about 3 in 100
        Random r = new Random(4);
        int wins = 0;
        for (int i = 0; i < 10_000; i++) if (Trouble.scratch(N, r.nextDouble()) == 2) wins++;
        assertTrue(wins > 200 && wins < 400, "big wins " + wins);
    }

    /** 슬쩍 손님: caught before he is out, he pays (a bonus); a wrong suspicion hurts; gone, it is the worker's loss. */
    @Test
    void catchingTheShoplifter() {
        Trouble.Outcome caught = Trouble.accuse(true, N);
        assertEquals("caught", caught.talk());
        assertEquals(N.catchBonus(), caught.bonus());
        Trouble.Outcome wrong = Trouble.accuse(false, N);
        assertEquals("wrong", wrong.talk());
        assertEquals(0, wrong.bonus());
        assertTrue(wrong.mood() <= -20, "an honest customer accused");
        assertEquals(1500, Trouble.escaped(1500));
        StoreRules r = StoreRulesTest.rules();
        assertEquals(Trouble.Stage.BROWSE, Trouble.Kind.STEAL.stage, "he pockets it at the shelf");
        assertTrue(r.troubles().get(Trouble.Kind.STEAL).chance() > 0);
        assertTrue(Trouble.comes(0.5, 0.2) && !Trouble.comes(0.5, 0.7));
    }

    // ------------------------------------------------------------------------------------------ 신분증

    @Test
    void theIdCheck() {
        assertTrue(IdCheck.adult(2007, 2026, 19), "연 나이 19");
        assertFalse(IdCheck.adult(2008, 2026, 19));
        IdCheck.Card own = new IdCheck.Card("김민준", 2009, false, true);
        IdCheck.Card sibling = new IdCheck.Card("김민준", 2001, true, true);
        IdCheck.Card adult = new IdCheck.Card("이서연", 2001, false, false);
        assertFalse(own.saysAdult(2026, 19));
        assertTrue(sibling.saysAdult(2026, 19), "the sibling's card says adult…");
        assertEquals(IdCheck.Verdict.SOLD_MINOR, IdCheck.judge(sibling, true), "…but it is not theirs");
        assertEquals(IdCheck.Verdict.REFUSED_MINOR, IdCheck.judge(sibling, false));
        assertEquals(IdCheck.Verdict.SOLD_MINOR, IdCheck.judge(own, true), "a wrong sale");
        assertEquals(IdCheck.Verdict.SOLD_ADULT, IdCheck.judge(adult, true));
        assertEquals(IdCheck.Verdict.REFUSED_ADULT, IdCheck.judge(adult, false), "a needless refusal");
        assertTrue(IdCheck.Verdict.SOLD_ADULT.right() && IdCheck.Verdict.REFUSED_MINOR.right());
        assertFalse(IdCheck.Verdict.SOLD_MINOR.right() || IdCheck.Verdict.REFUSED_ADULT.right());
        assertTrue(IdCheck.restricted(List.of("jelly", "beer"), Set.of("soju", "beer", "cigarette")));
        assertFalse(IdCheck.restricted(List.of("jelly"), Set.of("soju", "beer", "cigarette")));
    }

    @Test
    void studentsShowTheirOwnCardOrASiblings() {
        StoreRules r = StoreRulesTest.rules();
        IdCheck.Kind student = r.id().kinds().get("student"), univ = r.id().kinds().get("univ");
        assertNotNull(student);
        assertEquals(1, student.minor(), "a student is a minor");
        Random rnd = new Random(9);
        int siblings = 0, adults = 0;
        for (int i = 0; i < 2000; i++) {
            IdCheck.Card c = IdCheck.card(student, 2026, 19, r.id().names(), rnd);
            assertTrue(c.minor());
            if (c.sibling()) {
                siblings++;
                assertTrue(c.saysAdult(2026, 19), "a sibling's card: an adult year");
            } else assertFalse(c.saysAdult(2026, 19), "their own: under age");
            IdCheck.Card u = IdCheck.card(univ, 2026, 19, r.id().names(), rnd);
            if (!u.minor()) {
                adults++;
                assertTrue(u.saysAdult(2026, 19) && !u.sibling());
            }
            assertTrue(r.id().names().contains(c.name()));
        }
        assertTrue(siblings > 800 && siblings < 1200, "about half show a sibling's: " + siblings);
        assertTrue(adults > 1500, "most 대학생 are adults: " + adults);
        assertTrue(r.id().fine() > r.id().bonus(), "a wrong sale costs more than a right refusal earns");
        assertTrue(r.id().refusedAdult() < r.id().refusedMinor(), "a needless refusal angers more");
        for (String k : StoreRules.Id.TALK) assertFalse(r.id().lines(k).isEmpty(), k);
        for (String item : r.id().items()) assertFalse(r.tier("standard").sells(item), "only the 대형점 (and the 직영점) ask for IDs");
    }
}
