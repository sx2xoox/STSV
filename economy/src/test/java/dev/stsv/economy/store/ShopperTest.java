package dev.stsv.economy.store;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 편의점 손님 (README "편의점"): the walk round the shelves, the line, the counter, leaving; empty shelves and impatience. */
class ShopperTest {

    /** A store with stock and a line, as the module answers a shopper. */
    static final class Fake implements Shopper.Env {
        final StoreLayout.Layout l = StoreLayout.of();
        final Map<String, Integer> stock = new HashMap<>();
        final List<Shopper> line = new ArrayList<>();

        @Override
        public List<StoreLayout.Node> path(String from, String to) {
            return StoreLayout.path(l, from, to);
        }

        @Override
        public boolean take(String place) {
            Integer n = stock.get(place);
            if (n == null) return true;
            if (n <= 0) return false;
            stock.put(place, n - 1);
            return true;
        }

        @Override
        public int line(Shopper s) {
            int i = line.indexOf(s);
            if (i < 0 && s.inLine()) {
                line.add(s);
                i = line.size() - 1;
            }
            return i;
        }

        @Override
        public StoreLayout.Node queueSpot(int i) {
            return l.queue().get(Math.min(i, l.queue().size() - 1));
        }
    }

    private static final Map<String, String> PLACE = Map.of("samgak_tuna", "meal", "jelly", "snack", "banana_milk", "fridge", "gum", "counter",
            "ice_bar", "freezer");

    static Shopper shopper(Fake f, List<String> things, double sensitivity) {
        List<Shopper.Stop> round = Shopper.plan(f.l, things, PLACE::get, "door");
        Shopper s = new Shopper("student", false, round, Shopper.counterThings(things, PLACE::get), 80, 0.3, sensitivity, 10, f.l.nodes().get("out"));
        s.begin(f);
        return s;
    }

    /** Ticks until this event (or the limit), every event seen on the way. */
    static List<Shopper.Event> until(Shopper s, Fake f, Shopper.Event want, int limit) {
        List<Shopper.Event> seen = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            Shopper.Event e = s.tick(f);
            if (e != Shopper.Event.NONE) seen.add(e);
            if (e == want) return seen;
        }
        throw new AssertionError("no " + want + " in " + limit + " ticks: " + seen + " state " + s.state());
    }

    @Test
    void theRoundGoesShelfByShelfNearestFirst() {
        Fake f = new Fake();
        List<Shopper.Stop> round = Shopper.plan(f.l, List.of("banana_milk", "jelly", "samgak_tuna", "gum", "jelly"), PLACE::get, "door");
        assertEquals(3, round.size(), "the counter's gum is not a stop; two jellies one stop");
        assertEquals(List.of("jelly", "jelly"), round.stream().filter(r -> r.place().equals("snack")).findFirst().orElseThrow().things());
        // from the door (front right) the meal shelf's near side comes before the fridge at the back
        List<String> places = round.stream().map(Shopper.Stop::place).toList();
        assertTrue(places.indexOf("meal") < places.indexOf("fridge") || places.indexOf("snack") < places.indexOf("fridge"), places.toString());
        for (Shopper.Stop st : round) assertTrue(f.l.spots().get(st.place()).contains(st.node()));
        assertEquals(List.of("gum"), Shopper.counterThings(List.of("jelly", "gum"), PLACE::get));
    }

    @Test
    void inRoundTheShelvesIntoTheLineToTheCounterAndOut() {
        Fake f = new Fake();
        Shopper s = shopper(f, List.of("jelly", "banana_milk", "gum"), 1);
        assertEquals(Shopper.State.WALK, s.state());
        List<Shopper.Event> seen = until(s, f, Shopper.Event.AT_COUNTER, 3000);
        assertTrue(seen.contains(Shopper.Event.BROWSING));
        assertTrue(seen.contains(Shopper.Event.TOOK));
        assertEquals(Shopper.State.COUNTER, s.state());
        assertEquals(List.of("jelly", "banana_milk", "gum"), s.got(), "the counter's gum picked up at the till");
        StoreLayout.Node q0 = f.l.queue().getFirst();
        assertEquals(q0.x(), s.x(), 1e-9);
        assertEquals(q0.z(), s.z(), 1e-9);
        assertEquals(q0.yaw(), s.yaw(), 1e-6, "facing the counter");
        // paid: out through the door
        f.line.remove(s);
        s.leave(f, Shopper.Exit.PAID);
        until(s, f, Shopper.Event.LEFT, 3000);
        assertEquals(Shopper.State.GONE, s.state());
        assertEquals(Shopper.Exit.PAID, s.exit());
        StoreLayout.Node out = f.l.nodes().get("out");
        assertEquals(out.x(), s.x(), 1e-9);
        assertEquals(out.z(), s.z(), 1e-9);
    }

    @Test
    void theSecondInLineWaitsBehindTheFirst() {
        Fake f = new Fake();
        Shopper a = shopper(f, List.of("gum"), 1), b = shopper(f, List.of("gum"), 1);
        until(a, f, Shopper.Event.AT_COUNTER, 2000);
        for (int i = 0; i < 2000; i++) b.tick(f);
        assertEquals(Shopper.State.QUEUE, b.state(), "the counter is taken");
        StoreLayout.Node q1 = f.l.queue().get(1);
        assertEquals(q1.x(), b.x(), 1e-9);
        assertEquals(q1.z(), b.z(), 1e-9);
        f.line.remove(a);
        a.leave(f, Shopper.Exit.PAID);
        until(b, f, Shopper.Event.AT_COUNTER, 200);
    }

    @Test
    void anEmptyShelfSoursAndNothingAtAllSendsThemOut() {
        Fake f = new Fake();
        f.stock.put("snack", 0);
        f.stock.put("meal", 5);
        Shopper some = shopper(f, List.of("jelly", "samgak_tuna"), 1);
        List<Shopper.Event> seen = until(some, f, Shopper.Event.AT_COUNTER, 3000);
        assertTrue(seen.contains(Shopper.Event.EMPTY));
        assertEquals(List.of("samgak_tuna"), some.got());
        assertEquals(4, f.stock.get("meal"));

        Fake g = new Fake();
        g.stock.put("snack", 0);
        Shopper none = shopper(g, List.of("jelly"), 1);
        until(none, g, Shopper.Event.NOTHING_FOUND, 3000);
        assertEquals(Shopper.State.LEAVE, none.state());
        assertEquals(Shopper.Exit.EMPTY, none.exit());
        until(none, g, Shopper.Event.LEFT, 3000);
        assertTrue(g.line.isEmpty(), "never joined the line");
    }

    @Test
    void waitingSoursThemUntilTheyGiveUp() {
        StoreSpec.Patience p = new StoreSpec.Patience(20, 90, 20, 120);
        Fake f = new Fake();
        Shopper front = shopper(f, List.of("gum"), 1), patient = shopper(f, List.of("gum"), 0.5), hasty = shopper(f, List.of("gum"), 2);
        until(front, f, Shopper.Event.AT_COUNTER, 2000);
        boolean saidWait = false;
        for (int i = 0; i < 20 * 70 && !hasty.fedUp(p); i++) {
            patient.tick(f);
            hasty.tick(f);
            front.tick(f);
            if (hasty.waitLine(p)) saidWait = true;
        }
        assertTrue(saidWait, "the 'still waiting' line once past grace");
        assertTrue(hasty.fedUp(p), "twice as touchy: fed up after 60 s");
        assertFalse(patient.fedUp(p));
        assertTrue(hasty.mood(p) < patient.mood(p));
        hasty.giveUp(f);
        assertEquals(Shopper.Exit.ANGRY, hasty.exit());
        until(hasty, f, Shopper.Event.LEFT, 3000);
    }

    @Test
    void reactionsMoveTheMood() {
        StoreSpec.Patience p = new StoreSpec.Patience(20, 90, 20, 120);
        Fake f = new Fake();
        Shopper s = shopper(f, List.of("gum"), 1);
        assertEquals(80, s.mood(p), 1e-9);
        s.mood(-25);
        assertEquals(55, s.mood(p), 1e-9);
        s.mood(100);
        assertEquals(100, s.mood(p), 1e-9, "at most 100");
    }
}
