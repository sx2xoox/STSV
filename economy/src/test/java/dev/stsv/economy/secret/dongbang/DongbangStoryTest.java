package dev.stsv.economy.secret.dongbang;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 동방해운's ending and rewards: no money, no dividend; the memorial row names the liquidator; the 미련 is told only by the one who fades. */
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
        String faded = y.getString("meeting.faded", "");
        assertTrue(faded.contains("타지 않았") && !faded.contains("—"), "the accused says it themself; no narrator's gloss after it");
        String agenda = String.join("\n", y.getStringList("meeting.agenda"));
        assertTrue(agenda.contains("정족수가 맞지 않습니다") && !agenda.contains("미련") && !agenda.contains("명부"), "the agenda does not explain the twist");
        assertTrue(agenda.contains("지목하십시오") && agenda.contains("거짓을 말하지 않습니다") && agenda.contains("겪은 척"), "the rule the puzzle needs");
        assertEquals(4, y.getLong("offer.minutes"));
        assertTrue(String.join("\n", y.getStringList("meeting.released")).contains("정족수"));
    }

    @Test
    void theTermFollowsTheYear() throws Exception {
        assertEquals("제39기 정기 주주총회", Dongbang.term("제{term}기 정기 주주총회", 2026));
        assertEquals("제40기", Dongbang.term("제{term}기", 2027));
        assertEquals("안건 제1호", Dongbang.term("안건 제1호", 2026));
        assertFalse(java.nio.file.Files.readString(PACK.resolve("secrets/dongbang.yml")).contains("제38기"), "no count written out to go stale");
    }

    @Test
    void memorialFill() {
        Dongbang.Memorial m = new Dongbang.Memorial("Steve", "2026. 11. 22.");
        assertEquals("청산인 Steve · 2026. 11. 22.", Dongbang.fillMemorial("청산인 {player} · {date}", m));
        assertEquals(List.of("a Steve", "b 2026. 11. 22."), Dongbang.fillMemorial(List.of("a {player}", "b {date}"), m));
    }
}
