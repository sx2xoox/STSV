package dev.stsv.economy.secret.designer;

import dev.stsv.economy.job.MachineSpec;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 설계도 의뢰 (the 후속 임무 of 설계자): the orders against the real recipes, the week's pick, what the machine takes. */
class DesignerSequelTest {

    private static Map<String, Blueprint.Order> orders() {
        return DesignerSequel.orders(YamlConfiguration.loadConfiguration(new File("content/economy/secrets/designer.yml")).getConfigurationSection("sequel"));
    }

    private static String norm(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    @Test
    void everyOrderIsARealRecipeOfFiveToFifteenMinutes() {
        List<String> problems = new ArrayList<>();
        Map<String, MachineSpec> machines = MachineSpec.parse(YamlConfiguration.loadConfiguration(new File("content/economy/machines.yml")), problems);
        Map<String, Blueprint.Order> orders = orders();
        assertTrue(orders.size() >= 10, "ten orders or more: " + orders.size());
        Set<String> kinds = new HashSet<>();
        for (Blueprint.Order o : orders.values()) {
            MachineSpec spec = machines.get(o.machine());
            assertNotNull(spec, o.id() + ": machine " + o.machine());
            kinds.add(o.machine());
            double slowest = 0;
            for (MachineSpec.Recipe r : spec.recipes()) {
                if (r.tier() != 0 || r.aging() || !norm(r.output()).equals(o.item())) continue;
                slowest = Math.max(slowest, Blueprint.minutes(o.need(), r.amount(), r.seconds()));
            }
            assertTrue(slowest > 0, o.id() + ": " + o.item() + " is not a plain recipe of " + o.machine());
            assertTrue(slowest >= 4.5 && slowest <= 15, o.id() + ": " + slowest + " minutes at the machine's own pace");
            assertTrue(o.lines().size() >= 3, o.id() + ": a blueprint says a few lines");
            assertTrue(o.lines().size() <= 12, o.id() + ": it fits one book page with the count under it");
        }
        assertTrue(kinds.size() >= 6, "orders across the machines: " + kinds);
    }

    @Test
    void aWeekAlwaysGetsTheSameOrder() {
        List<String> ids = new ArrayList<>(orders().keySet());
        for (int w = 1; w <= 52; w++) {
            String week = String.format("2026-W%02d", w);
            assertEquals(Blueprint.pick(13L, week, ids, List.of()), Blueprint.pick(13L, week, ids, List.of()));
        }
        assertNull(Blueprint.pick(13L, "2026-W01", List.of(), List.of()));
    }

    @Test
    void recentOrdersDoNotComeBack() {
        List<String> ids = new ArrayList<>(orders().keySet());
        List<String> recent = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int w = 1; w <= 80; w++) {
            String id = Blueprint.pick(77L, "2027-W" + w, ids, recent);
            assertFalse(recent.contains(id), "week " + w);
            recent = Blueprint.remember(recent, id, 5);
            seen.add(id);
        }
        assertTrue(seen.size() >= ids.size() - 2, "over time nearly every order comes: " + seen.size() + "/" + ids.size());
    }

    @Test
    void withFewOrdersTheOldestRecentComesBack() {
        assertEquals("a", Blueprint.pick(1L, "w", List.of("a"), List.of("a")));
        assertEquals("a", Blueprint.pick(1L, "w", List.of("a", "b"), List.of("a", "b")), "the newest stays away");
        assertEquals("b", Blueprint.pick(1L, "w", List.of("a", "b"), List.of("gone", "a")));
        assertEquals(List.of("b", "c"), Blueprint.remember(List.of("a", "b"), "c", 2));
        assertEquals(List.of("a", "b"), Blueprint.words("a, b,"));
    }

    @Test
    void itTakesOnlyWhatIsMissing() {
        assertEquals(5, Blueprint.take(5, 0, 8));
        assertEquals(3, Blueprint.take(64, 5, 8), "never more than the order");
        assertEquals(0, Blueprint.take(64, 8, 8));
        assertEquals(0, Blueprint.take(64, 9, 8));
        assertEquals(0, Blueprint.take(0, 0, 8));
        assertEquals(8, Blueprint.take(64, -3, 8), "a broken count takes no more than the order");
        assertFalse(Blueprint.filled(7, 8));
        assertTrue(Blueprint.filled(8, 8));
    }

    @Test
    void whatItTookGoesBackInStacks() {
        assertEquals(List.of(64, 36), Blueprint.stacks(100, 64));
        assertEquals(List.of(16, 16, 3), Blueprint.stacks(35, 16));
        assertEquals(List.of(), Blueprint.stacks(0, 64));
        assertEquals(List.of(1, 1), Blueprint.stacks(2, 0));
    }

    @Test
    void minutesAtTheMachinesOwnPace() {
        assertEquals(5.0, Blueprint.minutes(4, 1, 75), 1e-9);
        assertEquals(6.0, Blueprint.minutes(24, 4, 60), 1e-9);
        assertEquals(1.0, Blueprint.minutes(1, 3, 60), 1e-9, "a part of a batch is a whole batch");
        assertEquals(0, Blueprint.turn(8, 8));
        assertEquals(-1, Blueprint.turn(3, 0));
    }
}
