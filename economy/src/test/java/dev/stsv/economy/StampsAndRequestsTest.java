package dev.stsv.economy;

import dev.stsv.economy.bus.BusSpec;
import dev.stsv.economy.mission.MissionSpec;
import dev.stsv.economy.people.PeopleSpec;
import dev.stsv.economy.request.RequestSpec;
import dev.stsv.economy.stamp.StampSpec;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 미션 · 의뢰 리뉴얼 (README "도장", "미션", "주민의 부탁"): the stamps (stamps.yml: earning, the month's board, the shop), mission bands
 * and the "try something new" missions, and the tiers, 주민, stories, 명인 의뢰, chained request and 친밀도 of requests.yml.
 */
class StampsAndRequestsTest {

    static StampSpec stamps() {
        List<String> problems = new ArrayList<>();
        StampSpec s = StampSpec.parse(ContentTest.yaml("stamps.yml"), problems);
        assertEquals(List.of(), problems);
        return s;
    }

    static RequestSpec requests() {
        List<String> problems = new ArrayList<>();
        RequestSpec s = RequestSpec.parse(ContentTest.yaml("requests.yml"), problems);
        assertEquals(List.of(), problems);
        return s;
    }

    static List<String> people() {
        List<String> problems = new ArrayList<>();
        BusSpec bus = BusSpec.parse(ContentTest.yaml("bus.yml"), problems);
        PeopleSpec ps = PeopleSpec.parse(ContentTest.yaml("people.yml"), problems);
        assertEquals(List.of(), problems);
        return ps.people(bus);
    }

    // ------------------------------------------------------------------------------------------ 도장

    @Test
    void theStampContentIsWhole() throws Exception {
        StampSpec s = stamps();
        assertEquals(30, s.cells());
        YamlConfiguration titles = ContentTest.yaml("titles/stamps.yml");
        List<String> problems = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            StampSpec.Reward r = s.months().get(m);
            if (r == null || r.isEmpty()) problems.add("month " + m + " gives nothing");
            else if (r.title() != null && !titles.contains(r.title().substring(r.title().indexOf(':') + 1))) problems.add("month title " + r.title());
        }
        // the shop sells only what money cannot buy and earns nothing: titles, a few limited cosmetics, mission reroll tickets
        YamlConfiguration gacha = YamlConfiguration.loadConfiguration(java.nio.file.Path.of(System.getProperty("stsv.content")).resolve("../../content/stsv/gacha.yml").normalize().toFile());
        String donation = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("stsv.content")).resolve("../../content/stsv/donation_shop.yml").normalize());
        Set<String> core = ContentTest.coreItems();
        for (StampSpec.ShopItem i : s.shop().values()) {
            StampSpec.Reward r = i.reward();
            if (!r.items().isEmpty()) problems.add(i.id() + ": the shop gives no items (they could be sold)");
            if (r.title() != null && !titles.contains(r.title().substring(r.title().indexOf(':') + 1))) problems.add(i.id() + ": title " + r.title());
            if (r.cosmetic() != null) {
                if (!core.contains(r.cosmetic())) problems.add(i.id() + ": no cosmetic " + r.cosmetic());
                if (gacha.getStringList("pool").contains(r.cosmetic())) problems.add(i.id() + ": in the gacha (money buys it)");
                if (donation.contains(r.cosmetic() + "\"") || donation.contains(r.cosmetic() + "]")) problems.add(i.id() + ": sold for stars");
            }
        }
        assertTrue(s.shop().values().stream().anyMatch(i -> i.reward().rerolls() > 0), "mission reroll tickets");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        for (var r : s.cellRewards().values()) for (String i : r.items()) assertTrue(ContentTest.exists(MissionSpec.item(i).id(), ContentTest.ourItems()), i);
    }

    @Test
    void stampsFillTheMonthsBoard() {
        StampSpec s = stamps();
        assertEquals(3, s.fill(0, 3));
        assertEquals(30, s.fill(28, 5), "never past the last cell");
        assertEquals(List.of(5), s.crossed(4, 6));
        assertEquals(List.of(5, 10), s.crossed(0, 10));
        assertEquals(List.of(), s.crossed(10, 10));
        assertEquals(List.of(25, 30), s.crossed(24, 30), "the last cell is the month's reward");
        assertEquals(s.months().get(11), s.cellReward(30, 11));
        assertEquals("economy:stamp_month_11", s.cellReward(30, 11).title());
        assertTrue(s.cellReward(7, 11).isEmpty());
        // the Korean calendar month: 2026-11-30 23:30 KST is November, 30 minutes later December
        long nov30 = java.time.ZonedDateTime.of(2026, 11, 30, 23, 30, 0, 0, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();
        assertEquals("2026-11", StampSpec.month(nov30, ZoneId.of("Asia/Seoul")));
        assertEquals("2026-12", StampSpec.month(nov30 + 1_800_000, ZoneId.of("Asia/Seoul")));
        assertEquals(12, StampSpec.monthOf("2026-12"));
        // caps of a day, the shop's refusals
        assertEquals(1, StampSpec.capped(1, 0, 1));
        assertEquals(0, StampSpec.capped(1, 1, 1));
        assertEquals(1, StampSpec.capped(2, 1, 2));
        assertEquals(5, StampSpec.capped(5, 9, 0), "no cap");
        StampSpec.ShopItem title = s.shop().get("regular");
        assertEquals("stamp.shop-owned", StampSpec.canBuy(title, 999, true));
        assertEquals("stamp.shop-short", StampSpec.canBuy(title, title.price() - 1, false));
        assertNull(StampSpec.canBuy(title, title.price(), false));
        assertNull(StampSpec.canBuy(s.shop().get("reroll"), 99, true), "tickets are bought again and again");
    }

    // ------------------------------------------------------------------------------------------ 미션

    @Test
    void jobMissionsScaleWithTheJobLevel() {
        MissionSpec spec = MissionSpec.parse(ContentTest.yaml("missions.yml"), new ArrayList<>());
        MissionSpec.Kind d = spec.daily();
        assertEquals(0, d.band(1));
        assertEquals(0, d.band(9));
        assertEquals(1, d.band(10));
        assertEquals(3, d.band(50));
        MissionSpec.Mission ores = d.missions().get("ores");
        MissionSpec.Mission top = MissionSpec.scaled(ores, d.bandAt(3));
        assertTrue(top.goal() > ores.goal() * 3 && top.reward().gold() > ores.reward().gold(), "a veteran's goal and gold are bigger");
        assertEquals(ores.reward().stamps(), top.reward().stamps(), "stamps stay");
        assertEquals("광석 64개 캐기", MissionSpec.name(ores));
        assertEquals("광석 " + (long) top.goal() + "개 캐기", MissionSpec.name(top));
        assertSame(ores, MissionSpec.scaled(ores, d.bandAt(0)), "the first band is today's mission");
        // the slot keeps its band
        assertEquals("ores@3", MissionSpec.slotKey("ores", 3));
        assertEquals("ores", MissionSpec.slotKey("ores", -1));
        assertEquals("ores", MissionSpec.slotMission("ores@3"));
        assertEquals(3, MissionSpec.slotBand("ores@3"));
        assertEquals(-1, MissionSpec.slotBand("ores"));
        // stamps: the daily bonus and each weekly mission
        assertTrue(d.bonus().stamps() > 0);
        assertTrue(spec.weekly().missions().values().stream().allMatch(m -> m.reward().stamps() > 0));
    }

    @Test
    void theOtherDailyMissionsAreThingsYouDoNotUsuallyDo() {
        MissionSpec spec = MissionSpec.parse(ContentTest.yaml("missions.yml"), new ArrayList<>());
        MissionSpec.Kind d = spec.daily();
        Set<String> tryStats = Set.of("people_met", "people_new", "dock_sales", "codex_new", "trades_direct", "tournaments", "market_sold",
                "requests_done", "stock_trades");
        for (MissionSpec.Mission m : d.general()) assertTrue(tryStats.contains(m.stat()), m.id() + ": " + m.stat());
        // a beginner never draws what needs a bus or a far dock; everyone can still fill a set
        for (int seed = 0; seed < 300; seed++) {
            List<MissionSpec.Mission> lv1 = MissionSpec.draw(d, "miner", 1, new Random(seed));
            assertEquals(3, lv1.size());
            assertEquals("miner", lv1.getFirst().job(), "one job mission");
            for (MissionSpec.Mission m : lv1) assertEquals(0, m.minLevel(), m.id());
            List<MissionSpec.Mission> none = MissionSpec.draw(d, null, 1, new Random(seed));
            assertEquals(3, none.size());
            assertEquals(3, new HashSet<>(none.stream().map(MissionSpec.Mission::group).toList()).size());
        }
        Set<String> seen = new HashSet<>();
        for (int seed = 0; seed < 500; seed++) for (MissionSpec.Mission m : MissionSpec.draw(d, "fisher", 40, new Random(seed))) seen.add(m.id());
        assertTrue(seen.contains("bus") && seen.contains("dock"), "veterans do meet them: " + seen);
    }

    // ------------------------------------------------------------------------------------------ 주민의 부탁

    @Test
    void requestsComeInTiersAskedByPeople() {
        RequestSpec spec = requests();
        List<String> people = people();
        assertFalse(people.contains("shades"));
        assertEquals(1, spec.tierFor(1));
        assertEquals(2, spec.tierFor(10));
        assertEquals(3, spec.tierFor(25));
        assertEquals(4, spec.tierFor(50));
        Map<String, RequestSpec.Entry> byItem = new java.util.HashMap<>();
        for (RequestSpec.Entry e : spec.pool()) byItem.putIfAbsent(e.item() + "@" + e.tier(), e);
        for (int day : List.of(1, 5, 20, 60)) {
            for (int tier = 1; tier <= 4; tier++) {
                List<RequestSpec.Request> board = spec.draw(tier, day, 7, id -> 10, people);
                assertEquals(spec.dailyCount(), board.size(), "tier " + tier + " day " + day);
                assertEquals(board, spec.draw(tier, day, 7, id -> 10, people), "the same all day");
                assertEquals(board.size(), new HashSet<>(board.stream().map(RequestSpec.Request::item).toList()).size(), "no item twice");
                assertEquals(board.size(), new HashSet<>(board.stream().map(RequestSpec.Request::person).toList()).size(), "six different people");
                for (RequestSpec.Request r : board) {
                    assertTrue(people.contains(r.person()), r.person());
                    assertNotNull(r.story());
                    assertTrue(r.story().contains("{item}"));
                    assertEquals(tier * 100, r.index() - board.indexOf(r), "index = tier x 100 + place");
                    assertEquals(spec.tier(tier).stamps(), r.stamps());
                }
                // from day 20 every board is its own tier's goods: veterans see 4세대 goods, beginners 1세대
                if (day >= 20) for (RequestSpec.Request r : board) assertTrue(byItem.containsKey(r.item() + "@" + tier), "tier " + tier + ": " + r.item());
            }
        }
        // a veteran's board on day 1 (nothing of theirs yet): filled from the tiers below
        assertEquals(spec.dailyCount(), spec.draw(4, 1, 7, id -> 10, people).size());
        // gold: today's 1.3x for beginners, the shop's price for veterans (their value is stamps)
        RequestSpec.Request b = spec.draw(1, 20, 7, id -> 10, people).getFirst();
        RequestSpec.Entry be = byItem.get(b.item() + "@1");
        assertEquals(Math.max(1, Math.round((be.value() > 0 ? be.value() : 10) * b.amount() * 1.3)), b.reward());
        assertTrue(spec.tier(4).multiplier() <= 1.0 && spec.tier(4).stamps() > spec.tier(1).stamps());
        // stories: per person, else default; every story's person is a 주민 (never the secret one)
        for (String k : spec.stories().keySet()) assertTrue(k.equals("default") || people.contains(k), "stories." + k);
        assertTrue(spec.stories().size() > 40, "a good number of kinds have their own lines");
        assertEquals("할머니 손주 생일이라 미역국 3개", RequestSpec.tell("할머니 손주 생일이라 {item} {count}개", "미역국", 3));
    }

    @Test
    void theMasterRequestTheChainAndBonds() {
        RequestSpec spec = requests();
        List<String> people = people();
        Set<String> ours = ContentTest.ourItems();
        RequestSpec.Request m = spec.master(3, 20, 7, id -> 100, people);
        assertNotNull(m);
        RequestSpec.Entry e = spec.pool().stream().filter(x -> x.item().equals(m.item()) && x.tier() == spec.master().tier()).findFirst().orElseThrow();
        assertEquals(e.max() * spec.master().amount(), m.amount(), "hard: many of a veteran's goods");
        assertEquals(spec.master().stamps(), m.stamps());
        assertTrue(m.stamps() >= 5);
        assertEquals(m, spec.master(3, 20, 7, id -> 100, people), "one a week");
        assertTrue(people.contains(m.person()));
        // the chain goes round the weeks; three steps, each of another job's goods
        assertFalse(spec.chains().isEmpty());
        assertEquals(spec.chainFor(1), spec.chainFor(1 + spec.chains().size()));
        Map<String, String> jobOf = new java.util.HashMap<>();
        for (var x : ContentTest.yaml("machines.yml").getValues(false).entrySet()) {
            if (!(x.getValue() instanceof org.bukkit.configuration.ConfigurationSection c) || c.getString("job") == null) continue;
            for (Map<?, ?> r : c.getMapList("recipes")) jobOf.put(dev.stsv.economy.job.JobSpec.norm(String.valueOf(r.get("out"))), c.getString("job"));
        }
        for (RequestSpec.Chain c : spec.chains()) {
            assertEquals(3, c.steps().size(), c.name());
            assertTrue(people.contains(c.person()), c.name() + ": " + c.person());
            Set<String> jobs = new HashSet<>();
            for (RequestSpec.Step s : c.steps()) {
                assertTrue(ContentTest.exists(s.item(), ours), c.name() + ": " + s.item());
                assertFalse(s.text().isBlank());
                jobs.add(jobOf.getOrDefault(s.item(), "raw:" + s.item()));
            }
            assertEquals(3, jobs.size(), c.name() + ": three jobs' goods " + jobs);
        }
        // 친밀도: gifts at 5 / 20 / 50, each once
        assertEquals(List.of(5, 20, 50), List.copyOf(spec.bond().gifts().keySet()));
        assertEquals(List.of(5), spec.giftsCrossed(4, 5));
        assertEquals(List.of(), spec.giftsCrossed(5, 6));
        assertEquals(List.of(20, 50), spec.giftsCrossed(15, 55));
        assertEquals(20, spec.nextGift(5));
        assertEquals(-1, spec.nextGift(50));
        for (RequestSpec.Gift g : spec.bond().gifts().values()) for (String i : g.items()) assertTrue(ContentTest.exists(MissionSpec.item(i).id(), ours), i);
    }
}
