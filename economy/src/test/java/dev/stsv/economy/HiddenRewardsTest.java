package dev.stsv.economy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hidden-until-owned titles ({@code hidden: true}, the secret mission's [비밀 요원]): the core leaves them out of a
 * player's /칭호 and /프로필 lists and counts until they have one, so nothing else may show them off either: not the
 * 칭호 shop, not an achievement's reward preview. Only the secret mission and the 숨은 비밀 (secrets/*.yml) give them.
 */
class HiddenRewardsTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    @Test
    void onlyTheSecretMissionGivesHiddenTitles() throws Exception {
        Set<String> hidden = new TreeSet<>();
        try (Stream<Path> s = Files.list(PACK.resolve("titles"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).toList()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                for (String k : y.getKeys(false)) if (y.getBoolean(k + ".hidden")) hidden.add("economy:" + k);
            }
        }
        assertEquals(Set.of("economy:secret_agent", "economy:last_shareholder", "economy:yesterday_me", "economy:designer_heir",
                "economy:guide_of_guides", "economy:water_name", "economy:heart_miner", "economy:lost_finder"), hidden);

        List<String> problems = new ArrayList<>();
        try (Stream<Path> s = Files.walk(PACK)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).toList()) {
                if (f.getParent().getFileName().toString().equals("titles") && f.getFileName().toString().equals("titles.yml")) continue;
                if (f.getFileName().toString().equals("bus.yml")) continue;   // the secret mission itself
                if (f.getParent().getFileName().toString().equals("secrets")) continue;   // the 숨은 비밀 themselves
                if (f.getParent().getFileName().toString().equals("titles") && f.getFileName().toString().equals("secrets.yml")) continue;
                String text = Files.readString(f);
                for (String id : hidden) if (text.contains(id)) problems.add(PACK.relativize(f) + ": " + id);
            }
        }
        assertTrue(problems.isEmpty(), "hidden titles shown elsewhere: " + problems);

        String bus = Files.readString(PACK.resolve("bus.yml"));
        assertTrue(bus.contains("title: economy:secret_agent"), "the secret mission still gives it");
        for (String[] s : new String[][]{{"dongbang", "last_shareholder"}, {"letter", "yesterday_me"}, {"designer", "designer_heir"}, {"usher", "guide_of_guides"}, {"fish", "water_name"}, {"vein", "heart_miner"}, {"lostkey", "lost_finder"}}) {
            String y = Files.readString(PACK.resolve("secrets").resolve(s[0] + ".yml"));
            assertTrue(y.contains("title: economy:" + s[1]), s[0] + ".yml gives economy:" + s[1]);
        }
    }
}
