package dev.stsv.economy.store;

import dev.stsv.economy.bus.BusSpec;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * store_scenes.yml (README "편의점" 손님끼리 장면): it parses clean against store.yml and bus.yml, has about sixty scenes, a pair
 * is always a man and a woman speaking as a / a2, and the picking works like the bus's (chance, cooldown, unseen first).
 */
class StoreScenesTest {

    static StoreScenes scenes() {
        List<String> problems = new ArrayList<>();
        StoreScenes s = StoreScenes.parse(YamlConfiguration.loadConfiguration(new File("content/economy/store_scenes.yml")), StoreSpecTest.spec(),
                StoreSpecTest.bus(), problems);
        assertEquals(List.of(), problems);
        return s;
    }

    @Test
    void sixtyOrSoScenes() {
        StoreScenes s = scenes();
        assertTrue(s.scenes().size() >= 60, "scenes " + s.scenes().size());
        assertEquals(0.12, s.rules().chance(), 1e-9, "like the bus");
        assertEquals(600, s.rules().cooldown());
        assertEquals(4, s.rules().unseen(), 1e-9);
        BusSpec bus = StoreSpecTest.bus();
        Set<String> titles = new HashSet<>();
        for (StoreScenes.Scene sc : s.scenes().values()) {
            assertTrue(titles.add(sc.title()), "one title once: " + sc.title());
            assertTrue(sc.effect().any(), sc.id());
            for (StoreScenes.Line l : sc.lines()) {
                if (l.partner()) assertTrue(bus.types().get(sc.cast().get(l.who())).pair(), sc.id() + ": a2 only for a pair");
            }
        }
        // the plan's examples are there: the last 도시락, water for a 취객, the couple's "아무거나"
        assertNotNull(s.scenes().get("last-dosirak"));
        assertNotNull(s.scenes().get("grandma-drunk-water"));
        assertNotNull(s.scenes().get("couple-anything"));
        // a couple's lines come from both: him (a) and her (a2)
        StoreScenes.Scene c = s.scenes().get("couple-anything");
        assertTrue(c.lines().stream().anyMatch(StoreScenes.Line::partner));
        assertTrue(c.lines().stream().anyMatch(l -> l.who() == 0 && !l.partner()));
    }

    @Test
    void badScenesAreTold() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("scenes.x.title", "x");
        y.set("scenes.x.cast", List.of("office", "shades"));
        y.set("scenes.x.lines", List.of(java.util.Map.of("a", "가"), java.util.Map.of("b", "나"), java.util.Map.of("a2", "다")));
        y.set("scenes.x.effect.mood.a", 5);
        List<String> problems = new ArrayList<>();
        StoreScenes.parse(y, StoreSpecTest.spec(), StoreSpecTest.bus(), problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("shades")), "a kind the store never sees");
        assertTrue(problems.stream().anyMatch(p -> p.contains("a2")), "a2 for a kind that comes alone");
        YamlConfiguration z = new YamlConfiguration();
        z.set("scenes.y.title", "y");
        z.set("scenes.y.cast", List.of("ghost", "kid"));
        z.set("scenes.y.lines", List.of(java.util.Map.of("a", "가"), java.util.Map.of("b", "나"), java.util.Map.of("a", "다")));
        z.set("scenes.y.effect.calm", 40);
        problems.clear();
        StoreScenes.parse(z, StoreSpecTest.spec(), StoreSpecTest.bus(), problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("함께 올 수 없는")), "a ghost (01~05) and a child (09~21) never meet: " + problems);
    }

    @Test
    void pickingAScene() {
        StoreScenes s = scenes();
        StoreScenes.Now noon = new StoreScenes.Now(12, 5, false, false, false);
        List<StoreScenes.Guest> inside = List.of(new StoreScenes.Guest("student", 80, true), new StoreScenes.Guest("office", 80, true),
                new StoreScenes.Guest("resident", 80, true));
        StoreScenes.Pick p = s.pick(inside, noon, Set.of(), 0.5);
        assertNotNull(p);
        for (int i = 0; i < p.cast().length; i++) assertEquals(p.scene().cast().get(i), inside.get(p.cast()[i]).type());
        // nobody free: nothing
        List<StoreScenes.Guest> busy = List.of(new StoreScenes.Guest("student", 80, false), new StoreScenes.Guest("office", 80, false));
        assertNull(s.pick(busy, noon, Set.of(), 0.5));
        // conditions: a rain scene only in the rain, a summer one in summer
        List<StoreScenes.Guest> campers = List.of(new StoreScenes.Guest("camper", 80, true), new StoreScenes.Guest("hiker", 80, true));
        StoreScenes.Scene rain = s.scenes().get("camper-hiker-weather");
        assertTrue(s.candidates(campers, noon, Set.of()).stream().noneMatch(x -> x.scene() == rain));
        assertTrue(s.candidates(campers, new StoreScenes.Now(12, 5, true, false, false), Set.of()).stream().anyMatch(x -> x.scene() == rain));
        // the same cast twice: two different customers
        StoreScenes.Scene three = s.scenes().get("chatty-everyone");
        assertNull(StoreScenes.cast(three, List.of(new StoreScenes.Guest("chatty", 80, true), new StoreScenes.Guest("office", 80, true))));
    }

    @Test
    void chanceCooldownAndUnseen() {
        StoreScenes s = scenes();
        assertFalse(s.due(100, 0.0), "within the cooldown");
        assertTrue(s.due(-1, 0.05), "never had one");
        assertTrue(s.due(601, 0.1));
        assertFalse(s.due(601, 0.5), "the chance");
        StoreScenes.Scene a = s.scenes().get("last-dosirak");
        assertEquals(a.weight() * 4, s.weight(a, Set.of()), 1e-9);
        assertEquals(a.weight(), s.weight(a, Set.of("last-dosirak")), 1e-9);
        // seen ones are kept apart from the bus's in the codex
        assertEquals("store:last-dosirak", StoreScenes.seenId("last-dosirak"));
        assertEquals(Set.of("last-dosirak"), StoreScenes.seen(Set.of("store:last-dosirak", "thug-police")));
        // unseen first: with one of two seen, the other comes up ~4 times as often
        List<StoreScenes.Guest> inside = List.of(new StoreScenes.Guest("student", 80, true), new StoreScenes.Guest("office", 80, true),
                new StoreScenes.Guest("kid", 80, true), new StoreScenes.Guest("grandma", 80, true));
        List<StoreScenes.Pick> both = s.candidates(inside, new StoreScenes.Now(12, 5, false, false, false), Set.of());
        assertTrue(both.size() >= 2, "these four play more than one scene");
        String seen = both.getFirst().scene().id();
        Random r = new Random(1);
        int picked = 0;
        for (int i = 0; i < 2000; i++) if (s.pick(inside, new StoreScenes.Now(12, 5, false, false, false), Set.of(seen), r.nextDouble()).scene().id().equals(seen)) picked++;
        assertTrue(picked < 2000 / both.size(), "the seen one less often: " + picked);
        // how often in a shift: a check every 30 s, ~12%, 10 min apart → a few an hour at most
        Random q = new Random(7);
        int scenesAnHour = 0;
        double last = -1;
        for (int t = 0; t < 3600 * 10; t += s.rules().check()) {
            if (s.due(last < 0 ? -1 : t - last, q.nextDouble())) {
                scenesAnHour++;
                last = t;
            }
        }
        assertTrue(scenesAnHour / 10.0 >= 2 && scenesAnHour / 10.0 <= 6, "scenes an hour " + scenesAnHour / 10.0);
    }
}
