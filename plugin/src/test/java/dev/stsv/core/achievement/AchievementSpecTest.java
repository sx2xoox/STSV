package dev.stsv.core.achievement;

import dev.stsv.core.achievement.AchievementSpec.Tier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The achievement format, and every shipped achievement (core and economy packs) against it. */
class AchievementSpecTest {

    private static final Path REPO = Path.of(System.getProperty("stsv.content")).getParent();

    @Test
    void parsesConditionsAndRewards() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("category.name", "낚시");
        y.set("achievements.a.name", "첫 입질");
        y.set("achievements.a.tier", "easy");
        y.set("achievements.a.stat", "fish_caught");
        y.set("achievements.a.goal", 1);
        y.set("achievements.a.rewards.gold", 200);
        y.set("achievements.a.rewards.items", List.of("bread x8", "economy:gem_bench"));
        y.set("achievements.a.rewards.title", "economy:angler");
        y.set("achievements.b.tier", "매우 어려움");
        y.set("achievements.b.value", "economy:job_level:fisher");
        y.set("achievements.b.goal", 30);
        y.set("achievements.c.tier", "impossible");
        y.set("achievements.c.stat", "x");
        y.set("achievements.c.goal", 1);
        List<String> problems = new ArrayList<>();
        Map<String, AchievementSpec.Category> cats = new LinkedHashMap<>();
        Map<String, AchievementSpec> m = AchievementSpec.parse("economy", "fishing", y, cats, problems, "test");
        assertEquals(1, problems.size(), problems.toString());
        AchievementSpec a = m.get("economy:a");
        assertEquals("stat", a.condition().kind());
        assertEquals(200, a.rewards().gold());
        assertEquals(List.of(new AchievementSpec.ItemReward("minecraft:bread", 8), new AchievementSpec.ItemReward("economy:gem_bench", 1)), a.rewards().items());
        AchievementSpec b = m.get("economy:b");
        assertEquals(Tier.VERY_HARD, b.tier());
        assertEquals("economy:job_level", b.condition().key());
        assertEquals("fisher", b.condition().arg());
        assertEquals("낚시", cats.get("economy:fishing").name());
        assertNull(AchievementSpec.item("bread x0"));
        assertEquals("■■■□□", AchievementSpec.bar(0.6, 5));
        assertEquals("□□□□□", AchievementSpec.bar(-1, 5));
    }

    /** Every shipped achievement parses, has a known stat or provider, real items and titles, and each tier is used. */
    @Test
    void shippedAchievementsAreValid() throws Exception {
        Map<String, Path> packs = Map.of("stsv", REPO.resolve("content/stsv"), "economy", REPO.resolve("economy/content/economy"));
        Set<String> stats = Set.of("playtime", "deaths", "mob_kills", "blocks_broken", "blocks_placed", "boss_kills", "boss_damage",
                "ores_mined", "crops_harvested", "fish_caught", "rare_finds", "animals_bred", "machine_made", "shop_sold", "shop_income",
                "requests_done", "stock_trades", "market_sold", "auctions_won");
        Set<String> providers = Set.of("stsv:achievements", "stsv:points", "stsv:titles", "stsv:cosmetics", "stsv:balance",
                "economy:job_level", "economy:job_level_max", "economy:jobs_total", "economy:net_worth", "economy:land_chunks", "economy:fish_species", "economy:codex");
        Set<String> titles = new HashSet<>(), items = new HashSet<>();
        for (var e : packs.entrySet()) {
            try (Stream<Path> f = Files.list(e.getValue().resolve("titles"))) {
                for (Path t : f.toList()) for (String k : YamlConfiguration.loadConfiguration(t.toFile()).getKeys(false)) titles.add(e.getKey() + ":" + k);
            }
            try (Stream<Path> f = Files.list(e.getValue().resolve("items"))) {
                for (Path t : f.toList()) for (String k : YamlConfiguration.loadConfiguration(t.toFile()).getKeys(false)) items.add(e.getKey() + ":" + k);
            }
        }
        List<String> problems = new ArrayList<>();
        Map<String, AchievementSpec> all = new LinkedHashMap<>();
        Map<String, AchievementSpec.Category> cats = new LinkedHashMap<>();
        for (var e : packs.entrySet()) {
            try (Stream<Path> f = Files.list(e.getValue().resolve("achievements"))) {
                for (Path file : f.sorted().toList()) {
                    String cat = file.getFileName().toString().replace(".yml", "");
                    all.putAll(AchievementSpec.parse(e.getKey(), cat, YamlConfiguration.loadConfiguration(file.toFile()), cats, problems, file.toString()));
                }
            }
        }
        Map<Tier, Integer> perTier = new EnumMap<>(Tier.class);
        Set<String> usedTitles = new HashSet<>();
        for (AchievementSpec a : all.values()) {
            perTier.merge(a.tier(), 1, Integer::sum);
            if (a.condition().stat()) {
                if (!stats.contains(a.condition().key())) problems.add(a.id() + ": unknown stat " + a.condition().key());
            } else if (!providers.contains(a.condition().key())) {
                problems.add(a.id() + ": unknown provider " + a.condition().key());
            }
            for (AchievementSpec.ItemReward i : a.rewards().items()) {
                if (i.id().startsWith("minecraft:")) {
                    if (org.bukkit.Material.matchMaterial(i.id()) == null) problems.add(a.id() + ": unknown item " + i.id());
                } else if (!items.contains(i.id())) problems.add(a.id() + ": unknown item " + i.id());
            }
            if (a.rewards().title() != null) {
                if (!titles.contains(a.rewards().title())) problems.add(a.id() + ": unknown title " + a.rewards().title());
                if (!usedTitles.add(a.rewards().title())) problems.add(a.id() + ": title given twice " + a.rewards().title());
            }
            if (a.rewards().cosmetic() != null && !items.contains(a.rewards().cosmetic())) problems.add(a.id() + ": unknown cosmetic " + a.rewards().cosmetic());
            if (a.icon().startsWith("minecraft:") && org.bukkit.Material.matchMaterial(a.icon()) == null) problems.add(a.id() + ": unknown icon " + a.icon());
            if (!a.icon().startsWith("minecraft:") && !items.contains(a.icon())) problems.add(a.id() + ": unknown icon " + a.icon());
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertTrue(all.size() >= 90, "achievements: " + all.size());
        for (Tier t : Tier.values()) assertTrue(perTier.getOrDefault(t, 0) >= 15, t + ": " + perTier);
        // legendary cosmetics only come from rankings and events
        for (AchievementSpec a : all.values()) {
            assertTrue(a.rewards().cosmetic() == null || !Set.of("stsv:crown", "stsv:halo", "stsv:angel_wings", "stsv:demon_wings").contains(a.rewards().cosmetic()), a.id());
        }
    }
}
