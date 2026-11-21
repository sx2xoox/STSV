package dev.stsv.core.util;

import com.google.gson.JsonParser;
import dev.stsv.core.content.CustomItem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hidden-until-owned rewards (the secret agent set): out of a player's lists and counts until they have them. */
class HiddenUntilOwnedTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");
    private static final Path ECONOMY = Path.of("../economy/content/economy");

    @Test
    void hiddenEntriesShowOnlyToOwners() {
        assertTrue(HiddenUntilOwned.shows(false, false));
        assertTrue(HiddenUntilOwned.shows(false, true));
        assertFalse(HiddenUntilOwned.shows(true, false), "hidden and not owned");
        assertTrue(HiddenUntilOwned.shows(true, true), "hidden but theirs");
        List<String> all = List.of("fire", "secret_agent", "neon");
        Set<String> hidden = Set.of("secret_agent");
        assertEquals(List.of("fire", "neon"), HiddenUntilOwned.filter(all, hidden::contains, id -> false));
        assertEquals(all, HiddenUntilOwned.filter(all, hidden::contains, Set.of("secret_agent")::contains), "the owner sees it, in place");
    }

    @Test
    void cosmeticsAreNotHiddenByDefault() {
        assertFalse(new CustomItem.Cosmetic(CustomItem.CosmeticSlot.HEAD, CustomItem.Rarity.COMMON, "").hidden());
        CustomItem.Cosmetic c = new CustomItem.Cosmetic(CustomItem.CosmeticSlot.HEAD, CustomItem.Rarity.LEGENDARY, "", new org.bukkit.util.Vector(),
                1f, false, null, false, null, false, null, null, true);
        assertTrue(c.hidden());
    }

    /** The secret agent set is marked hidden everywhere it is listed. */
    @Test
    void theSecretAgentSetIsHidden() throws Exception {
        YamlConfiguration titles = YamlConfiguration.loadConfiguration(ECONOMY.resolve("titles/titles.yml").toFile());
        assertTrue(titles.getBoolean("secret_agent.hidden"), "economy:secret_agent");

        YamlConfiguration agent = YamlConfiguration.loadConfiguration(PACK.resolve("items/cosmetics_agent.yml").toFile());
        for (String id : List.of("agent_sunglasses", "agent_earpiece", "agent_coat")) {
            assertTrue(agent.getBoolean(id + ".cosmetic.hidden"), "stsv:" + id);
        }

        YamlConfiguration config;
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> hiddenBanners = config.getStringList("title-banners.hidden");
        assertTrue(hiddenBanners.contains("secret_agent"));
        Set<String> themes = new TreeSet<>();
        try (var in = getClass().getResourceAsStream("/pack/title_banners/banners.json")) {
            for (var e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("themes")) {
                themes.add(e.getAsJsonObject().get("id").getAsString());
            }
        }
        assertTrue(themes.containsAll(hiddenBanners), "hidden banners are real themes: " + hiddenBanners + " of " + themes);
    }

    /** A hidden cosmetic is never offered where everybody browses (the 치장 뽑기 pool, the donation shop, the economy shop). */
    @Test
    void hiddenCosmeticsAreNotOnSale() throws Exception {
        Set<String> hidden = new TreeSet<>();
        try (var files = Files.list(PACK.resolve("items"))) {
            for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".yml")).toList()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                for (String key : y.getKeys(false)) {
                    ConfigurationSection c = y.getConfigurationSection(key + ".cosmetic");
                    if (c != null && c.getBoolean("hidden")) hidden.add(key);
                }
            }
        }
        assertEquals(Set.of("agent_coat", "agent_earpiece", "agent_sunglasses"), hidden);
        List<String> problems = new ArrayList<>();
        for (Path f : List.of(PACK.resolve("gacha.yml"), PACK.resolve("donation_shop.yml"), ECONOMY.resolve("shop.yml"))) {
            String text = Files.readString(f);
            for (String id : hidden) if (text.contains("stsv:" + id) || text.matches("(?s).*\\b" + id + "\\b.*")) problems.add(f.getFileName() + ": " + id);
        }
        assertTrue(problems.isEmpty(), problems.toString());
    }
}
