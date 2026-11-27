package dev.stsv.economy.store;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 기획안 §17: the steps the worker's actions show (spill · shelf · kettle · tablet · microwave) and the held-thing bookkeeping. */
class StoreMotionTest {

    @Test
    void aSpillTakesThreeStrokes() {
        assertEquals("economy:store_spill", StoreMotion.spillModel(1));
        assertEquals("economy:store_spill_2", StoreMotion.spillModel(2));
        assertEquals("economy:store_spill_3", StoreMotion.spillModel(3));
        assertNull(StoreMotion.spillModel(0));
        assertEquals(List.of(2, 3, 0), List.of(StoreMotion.wiped(1), StoreMotion.wiped(2), StoreMotion.wiped(3)));
        assertEquals(0, StoreMotion.wiped(0));
        assertTrue(StoreMotion.spillScale(1) > StoreMotion.spillScale(2) && StoreMotion.spillScale(2) > StoreMotion.spillScale(3));

        Chores c = new Chores(StoreSpecTest.spec());
        assertTrue(c.spill("fm", 3));
        assertEquals(1, c.stage("fm"));
        assertEquals(-1, c.wipeOnce("fm"), "no mop");
        c.mop(true);
        assertEquals(2, c.wipeOnce("fm"));
        assertEquals(1, c.messes(), "still a mess while wet");
        assertEquals(3, c.wipeOnce("fm"));
        assertEquals(0, c.wipeOnce("fm"), "gone");
        assertEquals(0, c.messes());
        assertEquals(0, c.stage("fm"));
        assertEquals(-1, c.wipeOnce("fm"));
        assertTrue(c.spill("fm", 3));
        assertEquals(1, c.stage("fm"), "a new spill starts big");
    }

    @Test
    void theShelfFillsInSteps() {
        StoreSpec.Shelf snack = StoreSpecTest.spec().shelves().get("snack");   // 14
        List<Integer> steps = StoreMotion.fillSteps(0, snack.capacity(), StoreMotion.POPS);
        assertEquals(StoreMotion.POPS, steps.size());
        assertEquals(snack.capacity(), steps.getLast());
        for (int i = 1; i < steps.size(); i++) assertTrue(steps.get(i) >= steps.get(i - 1));
        List<String> states = StoreMotion.fillStates(snack, 0, StoreMotion.POPS);
        assertTrue(states.contains("half"), "empty → half → full: " + states);
        assertEquals("full", states.getLast());
        assertEquals(states.indexOf("half") < states.indexOf("full"), true);
        assertEquals(List.of(14), StoreMotion.fillSteps(14, 14, 6), "nothing to add: one step");
        assertEquals(List.of(14), StoreMotion.fillSteps(20, 14, 6));
        assertEquals(List.of("a", "b", "a"), StoreMotion.popItems(List.of("a", "b"), 3));
        assertEquals(List.of(), StoreMotion.popItems(List.of(), 3));
    }

    @Test
    void theKettleShowsItsWater() {
        assertEquals(StoreMotion.Level.FULL, StoreMotion.level(8, 8));
        assertEquals(StoreMotion.Level.FULL, StoreMotion.level(3, 8));
        assertEquals(StoreMotion.Level.LOW, StoreMotion.level(2, 8));
        assertEquals(StoreMotion.Level.EMPTY, StoreMotion.level(0, 8));
        assertEquals("economy:store_kettle", StoreMotion.kettleModel(StoreMotion.Level.FULL));
        assertEquals("economy:store_kettle_low", StoreMotion.kettleModel(StoreMotion.Level.LOW));
        assertEquals("economy:store_kettle_empty", StoreMotion.kettleModel(StoreMotion.Level.EMPTY));
        List<Integer> pours = StoreMotion.refillSteps(0, 8, 3);
        assertEquals(3, pours.size());
        assertEquals(8, pours.getLast());
        List<StoreMotion.Level> seen = new ArrayList<>();
        for (int w : pours) seen.add(StoreMotion.level(w, 8));
        assertEquals(StoreMotion.Level.FULL, seen.getLast(), "the level goes up as it is poured: " + seen);
    }

    @Test
    void theTabletTurnsItsPages() {
        StoreLife l = StoreLifeTest.life();
        StoreSpec spec = StoreSpecTest.spec();
        Map<String, String> boxOf = new HashMap<>();
        for (StoreSpec.Shelf s : spec.shelves().values()) boxOf.put(s.place(), s.box());
        Map<String, String> kinds = new HashMap<>();
        for (StoreLife.Group g : l.order().groups().values())
            kinds.put(g.id(), StoreMotion.kind(g, id -> spec.products().get(id).place(), boxOf));
        assertEquals(Map.of("rain", "noodle", "market", "meal", "cold", "drink", "night", "drink", "snack", "snack"), kinds);
        // one page after another, round again
        String cur = null;
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < l.order().groups().size() + 1; i++) {
            cur = l.order().after(cur).id();
            pages.add(cur);
        }
        assertEquals(pages.getFirst(), pages.getLast(), "back to the first page");
        assertEquals(l.order().groups().size(), new java.util.HashSet<>(pages).size());
        assertEquals("economy:store_order_tablet_snack", StoreMotion.tabletModel("snack", false));
        assertEquals("economy:store_order_tablet_sent", StoreMotion.tabletModel("snack", true));
        assertEquals("economy:store_order_tablet", StoreMotion.tabletModel(null, false));
    }

    @Test
    void theOrderedBoxComesOnTheTruck() {
        StoreSpec spec = StoreSpecTest.spec();
        Chores c = new Chores(spec);
        assertEquals(List.of(), c.delivery(spec, 1, "drink"), "the first slot only remembers");
        List<String> came = c.delivery(spec, 2, "drink");
        assertEquals(List.of("drink"), came, "every shelf full: only the ordered box");
        assertTrue(c.pick("drink"));
        assertTrue(c.restock(spec, "fridge"));
        assertEquals(List.of(), c.delivery(spec, 3, null));
    }

    @Test
    void theOvenShowsItsDoorAndLight() {
        assertEquals("economy:store_microwave_open", StoreMotion.ovenModel(Chores.Oven.IDLE, true));
        assertEquals("economy:store_microwave_run", StoreMotion.ovenModel(Chores.Oven.WARMING, false));
        assertEquals("economy:store_microwave_done", StoreMotion.ovenModel(Chores.Oven.DONE, false));
        assertEquals("economy:store_microwave", StoreMotion.ovenModel(Chores.Oven.IDLE, false));
    }

    @Test
    void cupsGoOnTheTrayThenIntoTheBasket() {
        Chores c = new Chores(StoreSpecTest.spec());
        assertTrue(c.leaveCup(0, 3) && c.leaveCup(2, 3));
        assertTrue(c.trayCup(0));
        assertFalse(c.trayCup(0));
        assertTrue(c.trayCup(2));
        assertEquals(0, c.messes());
        assertEquals(2, c.carriedCups());
        assertEquals(2, c.dumpCups());
        assertEquals(0, c.carriedCups());
    }

    @Test
    void heldThingsGiveTheScannerBack() {
        StoreMotion.HeldBook b = new StoreMotion.HeldBook();
        UUID a = UUID.randomUUID(), z = UUID.randomUUID();
        assertNull(b.hold(a, StoreMotion.MOP, 3, 1000));
        assertTrue(b.holds(a, StoreMotion.MOP));
        assertEquals(a, b.who(StoreMotion.MOP));
        // swapped for something else: the scanner's slot stays the first one
        StoreMotion.HeldBook.Held before = b.hold(a, StoreMotion.JUG, 7, 0);
        assertEquals(StoreMotion.MOP, before.id());
        assertEquals(3, b.get(a).slot());
        assertNull(b.who(StoreMotion.MOP));
        // brief things go back by themselves, the others only by an action
        b.hold(z, StoreMotion.PDA, 1, 500);
        assertEquals(List.of(z), b.due(600));
        assertEquals(List.of(), b.due(400));
        b.touch(z, 900);
        assertEquals(List.of(), b.due(600));
        assertTrue(StoreMotion.brief(StoreMotion.MOP) && StoreMotion.brief(StoreMotion.PDA) && StoreMotion.brief(StoreMotion.HELD_ID));
        assertFalse(StoreMotion.brief(StoreMotion.heldBox("snack")) || StoreMotion.brief(StoreMotion.WASTE_BAG) || StoreMotion.brief(StoreMotion.TRAY));
        // put down: the slot to give the scanner back to; nothing held after
        assertEquals(3, b.release(a).slot());
        assertNull(b.release(a));
        assertNull(b.id(a));
        b.release(z);
        assertTrue(b.isEmpty());
        assertEquals("economy:store_held_box_meal", StoreMotion.heldBox("meal"));
        assertEquals("economy:store_box_meal_open", StoreMotion.openBox("meal"));
    }

    // ---- 점검 규칙-14: a shelf left empty between two trucks

    @Test
    void anEmptyShelfGetsOneBoxAtOnceButNotASecond() {
        StoreSpec spec = StoreSpec.parse(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File("content/economy/store.yml")),
                new java.util.ArrayList<>());
        Chores ch = new Chores(spec);
        assertNull(ch.quick(spec), "nothing is empty");
        StoreSpec.Shelf snack = spec.shelves().get("snack");
        for (int i = 0; i < snack.capacity(); i++) assertTrue(ch.take("snack"));
        assertEquals(List.of("snack"), ch.empty());
        assertEquals(snack.box(), ch.quick(spec));
        assertTrue(ch.boxes().contains(snack.box()));
        assertNull(ch.quick(spec), "its box is already waiting");
        assertTrue(ch.pick(snack.box()));
        assertNull(ch.quick(spec), "…or in the worker's arms");
    }
}
