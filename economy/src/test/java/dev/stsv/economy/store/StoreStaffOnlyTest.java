package dev.stsv.economy.store;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Players do not shop at the 편의점 any more (only the NPC customers do): someone not on shift who clicks a shelf, the
 * counter, a fridge, the kettle … gets one short line and nothing else; the hanger, the tablet and the seats stay theirs.
 */
class StoreStaffOnlyTest {

    @Test
    void aNonWorkersClickOnTheStaffsThingsDoesNothing() {
        for (StoreLayout.Piece p : StoreLayout.of().pieces()) {
            StoreModule.Click c = StoreModule.click(p.role(), false);
            assertFalse(c == StoreModule.Click.WORK, p.role() + ": a worker's job for someone not working");
        }
        for (String role : List.of("shelf_snack", "shelf_noodle", "shelf_daily", "shelf_meal", "fridge_1", "fridge_2", "freezer", "cig",
                "counter", "kettle", "microwave", "waste", "mop")) {
            assertEquals(StoreModule.Click.STAFF_ONLY, StoreModule.click(role, false), role);
            assertEquals(StoreModule.Click.WORK, StoreModule.click(role, true), role + ": the worker still does their job");
        }
        assertEquals(StoreModule.Click.HANGER, StoreModule.click("hanger", false), "anyone may start a shift (or apply)");
        assertEquals(StoreModule.Click.TABLET, StoreModule.click("order", false));
        assertEquals(StoreModule.Click.SEAT, StoreModule.click("eatbar", false));
        assertEquals(StoreModule.Click.SEAT, StoreModule.click("parasol_0", true));
    }

    @Test
    void theLineIsShortAndFriendly() {
        YamlConfiguration m = YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml"));
        assertEquals("store.staff-only-hanger", StoreModule.staffLine(true, false), "a closed store they may work in: the hanger");
        assertEquals("store.staff-only", StoreModule.staffLine(true, true));
        assertEquals("store.staff-only", StoreModule.staffLine(false, false));
        for (String key : List.of("store.staff-only", "store.staff-only-hanger")) {
            String line = m.getString(key);
            assertNotNull(line, key);
            assertTrue(line.contains("직원만 쓸 수 있어요"), line);
            assertTrue(line.replaceAll("<[^>]+>", "").length() <= 40, "short: " + line);
        }
        // the player customer's messages are gone (no buying, no 무인 계산대, no 컵라면 to pour)
        for (String gone : List.of("pick", "picked", "basket-paid", "unmanned-paid", "unmanned-hint", "unpaid-name", "ramen-ready", "coffee-driver",
                "todo-player", "line-player")) {
            assertFalse(m.contains("store." + gone), gone);
        }
    }
}
