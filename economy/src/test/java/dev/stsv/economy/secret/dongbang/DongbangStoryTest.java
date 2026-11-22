package dev.stsv.economy.secret.dongbang;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 동방해운's ending and rewards: no money, no dividend; the memorial row names the liquidator; the 미련 is told. */
class DongbangStoryTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    @Test
    void rewardsAreOneOfAKind() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("secrets/dongbang.yml").toFile());
        assertEquals(0, y.getLong("rewards.money", -1), "no money");
        assertNull(y.getConfigurationSection("dividend"), "the daily dividend is gone");
        assertTrue(y.getString("memorial.name", "").contains("{player}"), "청산인 ○○");
        assertTrue(y.getStringList("memorial.lore").stream().anyMatch(l -> l.contains("{date}")));
        assertTrue(y.getStringList("memorial.lore").stream().anyMatch(l -> l.contains("{player}")));
        assertFalse(String.join("\n", y.getStringList("meeting.certificate.lore")).contains("{dividend}"));
        assertTrue(y.getString("meeting.faded", "").contains("미련"), "the accused is the survivor's lingering seat");
        assertTrue(String.join("\n", y.getStringList("meeting.released")).contains("정족수"));
    }

    @Test
    void memorialFill() {
        Dongbang.Memorial m = new Dongbang.Memorial("Steve", "2026. 11. 22.");
        assertEquals("청산인 Steve · 2026. 11. 22.", Dongbang.fillMemorial("청산인 {player} · {date}", m));
        assertEquals(List.of("a Steve", "b 2026. 11. 22."), Dongbang.fillMemorial(List.of("a {player}", "b {date}"), m));
    }
}
