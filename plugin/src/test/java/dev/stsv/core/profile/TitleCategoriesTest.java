package dev.stsv.core.profile;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleCategoriesTest {

    @Test
    void explicitCategoryElseByFile() {
        assertEquals("job", TitleCategories.of("job", "achievements"));
        assertEquals("job", TitleCategories.of(" JOB ", "titles"));
        assertEquals("achievement", TitleCategories.of(null, "achievements"));
        assertEquals("codex", TitleCategories.of(null, "codex"));
        assertEquals("event", TitleCategories.of(null, "titles"));
        assertEquals("achievement", TitleCategories.of("nonsense", "achievements"), "an unknown one falls back to the file");
        assertTrue(TitleCategories.unknown("nonsense"));
        assertFalse(TitleCategories.unknown(null));
    }

    @Test
    void bannerTabOnlyForAdmins() {
        assertFalse(TitleCategories.tabs(false).contains(TitleCategories.BANNER));
        assertTrue(TitleCategories.tabs(true).contains(TitleCategories.BANNER));
        assertEquals(TitleCategories.ALL, TitleCategories.open(TitleCategories.BANNER, false), "a player cannot open it");
        assertEquals(TitleCategories.BANNER, TitleCategories.open(TitleCategories.BANNER, true));
        assertEquals(TitleCategories.ALL, TitleCategories.open("whatever", true));
        assertEquals(TitleCategories.ALL, TitleCategories.tabs(false).getFirst());
    }

    @Test
    void tabListsItsTitlesWornFirst() {
        List<ProfileModule.Title> all = List.of(new ProfileModule.Title("a:x", "[x]", "", "job"), new ProfileModule.Title("a:y", "[y]", "", "job"),
                new ProfileModule.Title("a:z", "[z]", "", "codex"), new ProfileModule.Title("a:w", "[w]", "", "job"));
        assertEquals(List.of("a:w", "a:y", "a:x"), ProfileModule.inTab(all, "job", Set.of("a:y", "a:w"), "a:w").stream().map(ProfileModule.Title::id).toList());
        assertEquals(4, ProfileModule.inTab(all, TitleCategories.ALL, Set.of(), null).size());
        assertTrue(ProfileModule.inTab(all, "shop", Set.of(), null).isEmpty());
    }

    /** Every shipped title names a known category, and every category has titles. */
    @Test
    void shippedTitlesAreSorted() throws Exception {
        Map<String, Integer> per = new TreeMap<>();
        for (Path dir : List.of(Path.of("../content/stsv/titles"), Path.of("../economy/content/economy/titles"))) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                    YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                    for (String k : y.getKeys(false)) {
                        String c = y.getString(k + ".category");
                        assertTrue(c != null && !TitleCategories.unknown(c), f + ": " + k + " category " + c);
                        per.merge(c, 1, Integer::sum);
                    }
                }
            }
        }
        assertEquals(Set.copyOf(TitleCategories.ORDER), per.keySet(), "every tab has titles: " + per);
        assertEquals(11, per.get("job"));
    }

    /** The menu: a tab button per tab, the banner one not drawn on the background. */
    @Test
    void menuHasEveryTab() throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(Path.of("../content/stsv/menus/titles.yml").toFile());
        var buttons = y.getConfigurationSection("titles.buttons");
        java.util.Map<String, String> tabKey = new java.util.HashMap<>();
        for (String k : buttons.getKeys(false)) {
            String a = buttons.getString(k + ".action", "");
            if (a.startsWith("titles:tab:")) tabKey.put(a.substring("titles:tab:".length()), k);
        }
        assertEquals(Set.copyOf(TitleCategories.tabs(true)), tabKey.keySet());
        // the banners are no category of titles: their button is a tile in the bottom row, drawn for everybody
        assertFalse(y.getString("titles.tabs").contains(tabKey.get(TitleCategories.BANNER)));
        assertFalse(y.contains("titles.secret"));
        assertTrue(y.getStringList("titles.layout").getLast().contains(tabKey.get(TitleCategories.BANNER)));
    }
}
