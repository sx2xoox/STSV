package dev.stsv.economy.secret.dawn;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 새벽단골 (docs/admin/숨은_비밀/새벽단골.md): who it comes to, when, its four visits and the last one, its settings. */
class DawnStoryTest {

    @Test
    void onlyForLongNightWorkersAtDawn() {
        assertTrue(DawnStory.dawn(3, 3, 5));
        assertTrue(DawnStory.dawn(4, 3, 5));
        assertFalse(DawnStory.dawn(5, 3, 5));
        assertFalse(DawnStory.dawn(14, 3, 5));
        assertTrue(DawnStory.dawn(1, 23, 2), "a window past midnight");
        assertTrue(DawnStory.starts(false, true, 4, 3, 5, 150, 150));
        assertFalse(DawnStory.starts(false, true, 4, 3, 5, 149, 150), "not enough nights behind them");
        assertFalse(DawnStory.starts(false, false, 4, 3, 5, 500, 150), "a day shift run late");
        assertFalse(DawnStory.starts(false, true, 12, 3, 5, 500, 150), "never by day");
        assertTrue(DawnStory.starts(true, false, 12, 3, 5, 0, 150), "test mode: always");
    }

    @Test
    void oneVisitADayThenTheBottleWaits() {
        assertFalse(DawnStory.comes(false, true, 4, 3, 5, "2026-11-27", "2026-11-27"), "once a day");
        assertTrue(DawnStory.comes(false, true, 4, 3, 5, "2026-11-26", "2026-11-27"));
        assertTrue(DawnStory.comes(false, true, 4, 3, 5, null, "2026-11-27"));
        assertFalse(DawnStory.comes(false, true, 10, 3, 5, null, "2026-11-27"));
        assertFalse(DawnStory.comes(true, false, 10, 3, 5, "t5", "t5"), "test: once a test day too");
        assertTrue(DawnStory.comes(true, false, 10, 3, 5, "t4", "t5"));
        assertEquals(2, DawnStory.afterPaid(1));
        assertEquals(4, DawnStory.afterPaid(3));
        assertEquals(DawnStory.WAITING, DawnStory.afterPaid(4), "the fourth leaves the bottle: she may come");
        assertEquals(DawnStory.VISITS, DawnStory.missed(DawnStory.WAITING), "the bottle gone: he comes again");
        assertEquals(2, DawnStory.missed(2));
        assertEquals("visits.1", DawnStory.key(1));
        assertEquals("visits.4", DawnStory.key(4));
        assertEquals("daughter", DawnStory.key(DawnStory.WAITING));
    }

    @Test
    void itsSettingsHaveEveryVisit() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("content/economy/secrets/dawn.yml"));
        assertFalse(y.getBoolean("stone", true), "no stone on the monument");
        for (int i = 1; i <= DawnStory.VISITS; i++) {
            for (String k : List.of("enter", "pay", "thanks")) assertFalse(y.getStringList("visits." + i + "." + k).isEmpty(), i + " " + k);
            assertFalse(y.getString("visits." + i + ".after", "").isEmpty(), i + " after");
        }
        assertFalse(y.getString("daughter.buttons.give", "").isEmpty());
        assertFalse(y.getString("daughter.buttons.greet", "").isEmpty());
        assertTrue(y.getStringList("daughter.give").size() >= 3);
        assertEquals("economy:entity/bus/night_regular", y.getString("skin"));
        YamlConfiguration store = YamlConfiguration.loadConfiguration(new File("content/economy/store.yml"));
        assertTrue(store.contains("products." + y.getString("item")), "what he buys is sold");
        assertTrue(store.contains("customers." + y.getString("kind")) || y.getString("kind").equals("worker"));
        String title = y.getString("rewards.title");
        YamlConfiguration titles = YamlConfiguration.loadConfiguration(new File("content/economy/titles/secrets.yml"));
        assertTrue(titles.getBoolean(title.substring(title.indexOf(':') + 1) + ".hidden"), "a hidden title");
        // never in the README
        String readme = new java.io.File("README.md").exists() ? readAll("README.md") : "";
        assertFalse(readme.contains("새벽 단골") || readme.contains("새벽단골") || readme.contains("night_regular"));
    }

    private static String readAll(String f) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(f));
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
