package dev.stsv.core.command;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Commands are Korean; the old English words keep working (unlisted). */
class KoreanCommandsTest {

    @Test
    void koreanWordsMapToTheHandlers() {
        assertArrayEquals(new String[]{"boss", "spawn", "executioner"}, StsvCommand.normalize(new String[]{"보스", "소환", "executioner"}));
        assertArrayEquals(new String[]{"recipe", "create", "my_recipe", "blasting"}, StsvCommand.normalize(new String[]{"레시피", "생성", "my_recipe", "용광로"}));
        assertArrayEquals(new String[]{"period", "end"}, StsvCommand.normalize(new String[]{"기간", "종료"}));
        assertArrayEquals(new String[]{"title", "give", "Steve", "stsv:test_title_orange"}, StsvCommand.normalize(new String[]{"칭호", "지급", "Steve", "stsv:test_title_orange"}));
        assertArrayEquals(new String[]{"boss", "spawn", "x"}, StsvCommand.normalize(new String[]{"BOSS", "Spawn", "x"}), "English still accepted");
        assertArrayEquals(new String[]{"cosmetic", "give", "Steve", "목록"}, StsvCommand.normalize(new String[]{"치장", "지급", "Steve", "목록"}),
                "only command words are translated, never names or ids");
        assertArrayEquals(new String[]{"economy", "revert", "Steve", "30"}, StsvCommand.normalize(new String[]{"경제", "되돌리기", "Steve", "30"}));
        assertArrayEquals(new String[]{"reset", "economy", "확인"}, StsvCommand.normalize(new String[]{"초기화", "경제", "확인"}));
        assertEquals(1000, EconomyCommand.parseAmount("1,000"));
        assertEquals(-1, EconomyCommand.parseAmount("-5"));
        assertEquals(-1, EconomyCommand.parseAmount("abc"));
    }

    @Test
    void commandNamesAreKorean() {
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
        var names = yaml.getConfigurationSection("commands").getKeys(false);
        assertEquals(java.util.Set.of("관리", "옷장", "프로필", "명예의전당", "돈", "송금", "수표"), names);
        for (String n : names) assertTrue(n.codePoints().allMatch(c -> c >= 0xAC00 && c <= 0xD7A3), n);
    }
}
