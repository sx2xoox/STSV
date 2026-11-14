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
        assertArrayEquals(new String[]{"fuel", "set", "200"}, StsvCommand.normalize(new String[]{"연료", "설정", "200"}));
        assertArrayEquals(new String[]{"recipe", "create", "my_recipe", "blasting"}, StsvCommand.normalize(new String[]{"레시피", "생성", "my_recipe", "용광로"}));
        assertArrayEquals(new String[]{"period", "end"}, StsvCommand.normalize(new String[]{"기간", "종료"}));
        assertArrayEquals(new String[]{"title", "give", "Steve", "stsv:test_title_orange"}, StsvCommand.normalize(new String[]{"칭호", "지급", "Steve", "stsv:test_title_orange"}));
        assertArrayEquals(new String[]{"cosmetic", "give", "x"}, StsvCommand.normalize(new String[]{"COSMETIC", "Give", "x"}), "English still accepted");
        assertArrayEquals(new String[]{"cosmetic", "give", "Steve", "목록"}, StsvCommand.normalize(new String[]{"치장", "지급", "Steve", "목록"}),
                "only command words are translated, never names or ids");
        assertArrayEquals(new String[]{"economy", "revert", "Steve", "30"}, StsvCommand.normalize(new String[]{"경제", "되돌리기", "Steve", "30"}));
        assertArrayEquals(new String[]{"reset", "economy", "확인"}, StsvCommand.normalize(new String[]{"초기화", "경제", "확인"}));
        assertArrayEquals(new String[]{"report"}, StsvCommand.normalize(new String[]{"보고서"}));
        assertArrayEquals(new String[]{"reset", "경제서버", "확인"}, StsvCommand.normalize(new String[]{"초기화", "경제서버", "확인"}),
                "a target a content plugin added stays as typed");
        assertEquals(1000, EconomyCommand.parseAmount("1,000"));
        assertEquals(-1, EconomyCommand.parseAmount("-5"));
        assertEquals(-1, EconomyCommand.parseAmount("abc"));
    }

    @Test
    void contentPluginsAddToTheAdminCommand() {
        AdminRegistry r = new AdminRegistry();
        r.add("STSV-Economy", "사건", "목록", "사건", (s, a) -> { });
        r.report("STSV-Economy", (s, a) -> { });
        r.reset("STSV-Economy", "경제서버", "경제 서버 기록", by -> java.util.concurrent.CompletableFuture.completedFuture(null));
        assertEquals("사건", r.sub("사건").name());
        assertEquals(1, r.reportList().size());
        assertEquals("경제서버", r.resetTarget("경제서버").word());
        r.removeAll("STSV-Economy");
        assertEquals(null, r.sub("사건"));
        assertTrue(r.reportList().isEmpty() && r.resetTargets().isEmpty());
    }

    @Test
    void commandNamesAreKorean() {
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
        var names = yaml.getConfigurationSection("commands").getKeys(false);
        assertEquals(java.util.Set.of("관리자", "치장", "뽑기", "칭호", "업적", "프로필", "명예의전당", "닉네임", "돈", "송금", "수표", "메뉴", "건축월드", "야생월드", "야간투시", "사이드바", "탈것", "지게", "감정표현", "튜닝", "후원", "스킨", "rtp", "ㅆ",
                "tpa", "tpahere", "tpaccept", "tpdeny", "tpacancel", "tpatoggle"), names);
        // Korean names, except the teleport requests: the owner wants the familiar tpa names as the main ones (Korean aliases);
        // and the two the owner asked for by these very names: /rtp, and /ㅆ (one key for the bin)
        for (String n : names) {
            if (dev.stsv.core.tpa.TpaModule.COMMANDS.contains(n) || n.equals("rtp") || n.equals("ㅆ")) continue;
            assertTrue(n.codePoints().allMatch(c -> c >= 0xAC00 && c <= 0xD7A3), n);
        }
        assertTrue(names.containsAll(dev.stsv.core.tpa.TpaModule.COMMANDS), "every tpa command is in plugin.yml");
        assertEquals("stsv.travel", yaml.getString("commands.rtp.permission"), "whoever may travel may /rtp");
        assertTrue(yaml.getStringList("commands.rtp.aliases").contains("랜덤이동"));
        assertEquals("stsv.trash", yaml.getString("commands.ㅆ.permission"));
        assertTrue(yaml.getStringList("commands.ㅆ.aliases").containsAll(java.util.List.of("쓰레기통", "trash")));
        assertEquals("true", yaml.getString("permissions.stsv.trash.default"), "everyone has a bin");
    }

    /** /rtp: only from inside the wild world, and no sooner than another /야생월드 trip. */
    @Test
    void rtpGoesFromInsideTheWildWorldOnly() {
        assertEquals(null, dev.stsv.core.world.WorldRules.rtpRefusal(true, true, false, 0));
        assertEquals(null, dev.stsv.core.world.WorldRules.rtpRefusal(true, true, false, -5));
        assertEquals("worlds.no-wild", dev.stsv.core.world.WorldRules.rtpRefusal(false, false, false, 0));
        assertEquals("worlds.rtp-elsewhere", dev.stsv.core.world.WorldRules.rtpRefusal(true, false, false, 0), "the build world, the wild nether and end");
        assertEquals("worlds.in-wardrobe", dev.stsv.core.world.WorldRules.rtpRefusal(true, true, true, 0));
        assertEquals("worlds.rtp-cooldown", dev.stsv.core.world.WorldRules.rtpRefusal(true, true, false, 1), "it is the wild trip: the same wait");
        var messages = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new InputStreamReader(getClass().getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8));
        for (String key : java.util.List.of("worlds.rtp-elsewhere", "worlds.rtp-cooldown", "worlds.no-wild", "worlds.in-wardrobe",
                "trash.title", "trash.thrown", "trash.kept")) {
            assertTrue(messages.isString(key), key);
        }
        assertTrue(messages.getString("worlds.rtp-cooldown").contains("{seconds}") && messages.getString("trash.thrown").contains("{count}"));
    }
}
