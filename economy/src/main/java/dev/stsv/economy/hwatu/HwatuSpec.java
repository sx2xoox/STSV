package dev.stsv.economy.hwatu;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * contents/economy/hwatu.yml: 화투방 (README "화투방"). Chips are bought only with gold and cashed only to gold, players only
 * play each other, every pot pays a 자릿세 that is destroyed. Pure (unit-tested).
 *
 * <pre>
 * chips: { buy-price: 100, cash-price: 95, daily-buy: 0 }   # daily-buy 0: no daily limit
 * rake-percent: 5               # of each pot, rounded down (a pot under 20 chips pays none)
 * hand-limit-antes: 100        # 한도: the most one player puts into one hand = ante × this (0: none)
 * daily-loss-cap: 0             # chips lost (net) in a day before the player can't sit until 05시; 0: no cap
 * min-account-hours: 0          # 0: no account-age rule
 * test-chips: 100000            # /관리자 화투방 시험: each seat's pretend chips (never real)
 * timers: { turn: 20, start: 10, roll: 10, reveal: 3, result: 6 }   # roll: 굴리기 (주사위 · 홀짝) before it rolls itself
 * rooms:  [ { id: beginner, name: "초보방", ante: 1 }, ... ]      # exactly three, in order
 * games:
 *   holjjak: { name: "홀짝", max: 2 }
 *   dice:    { name: "주사위", max: 6, dice: 2 }
 *   sutda:   { name: "섯다", max: 6, sit-antes: 3, replays: 1 }   # sit with ante × sit-antes; raise any number of times up to the 한도 (all-in)
 *   seven_poker: { name: "세븐 포커", max: 6, sit-antes: 3 }       # the same betting, five rounds (3 cards → open one → 4th · 5th · 6th open → 7th hidden)
 *   baccarat: { name: "바카라", max: 6 }                         # against the house (the dealer), works alone
 * timers: { …, open: 10 }        # 세븐 포커: picking the card to open (the timer opens the lowest)
 * baccarat: { min-bet: 10, max-bet: 1000, daily-wager-cap: 5000, chips: [10, 50, 100, 500], bet-seconds: 15,
 *             commission-percent: 5, tie-pays: 8, road: 12 }
 * </pre>
 */
public record HwatuSpec(long buyPrice, long cashPrice, long dailyBuy, int rakePercent, long lossCap, long minAgeMs,
                        int turnSeconds, int startSeconds, int revealSeconds, int resultSeconds, int rollSeconds,
                        List<Room> rooms, Map<Table.Game, GameSpec> games, long testChips, int handLimitAntes,
                        int openSeconds, BaccaratSpec baccarat) {

    public record Room(String id, String name, long ante) {}

    /**
     * 바카라 (the house game): the least a bet may be, the most one player bets in one round (all spots together), the
     * most one player bets in a day (all rounds, 05시 to 05시: the house pays from nowhere, so this caps what it can
     * pay out), the chip amounts a player picks from, how long the bets are open, the banker's commission (%), what a tie
     * pays (n:1) and how many results the road shows.
     */
    public record BaccaratSpec(long minBet, long maxBet, long dailyCap, List<Long> chips, int betSeconds, int commission, int tiePays, int road) {

        public static final BaccaratSpec DEFAULT = new BaccaratSpec(10, 1000, 5000, List.of(10L, 50L, 100L, 500L), 15, 5, 8, 12);

        static BaccaratSpec parse(ConfigurationSection c, List<String> problems) {
            if (c == null) {
                problems.add("baccarat 이 없음 (기본값을 씀)");
                return DEFAULT;
            }
            long min = c.getLong("min-bet", 10), max = c.getLong("max-bet", 1000), cap = c.getLong("daily-wager-cap", 5000);
            if (min < 1) problems.add("baccarat.min-bet: 1 이상");
            if (max < min) problems.add("baccarat.max-bet: min-bet 이상");
            if (cap < min) problems.add("baccarat.daily-wager-cap: min-bet 이상 (하루에 걸 수 있는 칩, 하우스가 칩을 만들어 내므로 꼭 둠)");
            List<Long> chips = new ArrayList<>();
            for (Object o : c.getList("chips", List.of(10, 50, 100, 500))) if (o instanceof Number n) chips.add(n.longValue());
            if (chips.isEmpty() || chips.size() > 4) problems.add("baccarat.chips: 1~4개 (칩 고르는 버튼)");
            for (int i = 0; i < chips.size(); i++) {
                if (chips.get(i) < min) problems.add("baccarat.chips: " + chips.get(i) + " 이 min-bet 보다 작음");
                if (i > 0 && chips.get(i) <= chips.get(i - 1)) problems.add("baccarat.chips: 커지는 순서로");
            }
            int bet = c.getInt("bet-seconds", 15), commission = c.getInt("commission-percent", 5), tie = c.getInt("tie-pays", 8), road = c.getInt("road", 12);
            if (bet < 5 || bet > 60) problems.add("baccarat.bet-seconds: 5~60");
            if (commission < 0 || commission > 50) problems.add("baccarat.commission-percent: 0~50");
            if (tie < 1 || tie > 20) problems.add("baccarat.tie-pays: 1~20");
            if (road < 1 || road > 30) problems.add("baccarat.road: 1~30");
            long m = Math.max(1, min);
            List<Long> ok = chips.stream().filter(x -> x >= m).distinct().sorted().limit(4).toList();
            return new BaccaratSpec(m, Math.max(m, max), Math.max(m, cap), ok.isEmpty() ? List.of(m) : ok, Math.clamp(bet, 5, 60),
                    Math.clamp(commission, 0, 50), Math.clamp(tie, 1, 20), Math.clamp(road, 1, 30));
        }
    }

    /** max players at a table; dice: how many dice a player rolls; sitAntes (chips to sit, in antes) · replays: 섯다. */
    public record GameSpec(String name, int max, int dice, int sitAntes, int replays) {}

    public GameSpec game(Table.Game g) {
        return games.get(g);
    }

    /** Chips a player must hold to be dealt in at this game and room. */
    public long need(Table.Game g, Room r) {
        if (g == Table.Game.BACCARAT) return baccarat.minBet();
        GameSpec s = game(g);
        return HwatuRules.need(g, r.ante(), s.sitAntes());
    }

    public static HwatuSpec parse(ConfigurationSection c, List<String> problems) {
        long buy = c.getLong("chips.buy-price"), cash = c.getLong("chips.cash-price"), daily = c.getLong("chips.daily-buy");
        if (buy <= 0) problems.add("chips.buy-price: 1 이상");
        if (cash <= 0 || cash > buy) problems.add("chips.cash-price: 1 이상, buy-price 이하 (칩으로 돈을 만들 수 없게)");
        if (daily < 0) problems.add("chips.daily-buy: 0 이상 (0 = 제한 없음)");
        int rake = c.getInt("rake-percent");
        if (rake < 1 || rake > 50) problems.add("rake-percent: 1~50");
        long cap = c.getLong("daily-loss-cap");
        if (cap < 0) problems.add("daily-loss-cap: 0 이상 (0 = 제한 없음)");
        int limit = c.getInt("hand-limit-antes", 100);
        if (limit < 0) problems.add("hand-limit-antes: 0 이상 (한 판에 한 사람이 넣을 수 있는 칩 = 판돈 × 이만큼, 0 = 제한 없음)");
        int hours = c.getInt("min-account-hours");
        if (hours < 0) problems.add("min-account-hours: 0 이상");
        int turn = c.getInt("timers.turn"), start = c.getInt("timers.start"), reveal = c.getInt("timers.reveal"), result = c.getInt("timers.result");
        if (turn < 5) problems.add("timers.turn: 5초 이상");
        if (start < 1 || reveal < 1 || result < 1) problems.add("timers: start · reveal · result 는 1초 이상");
        int roll = c.getInt("timers.roll", 10);
        if (roll < 3) problems.add("timers.roll: 3초 이상 (굴리기를 누를 시간)");
        List<Room> rooms = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("rooms")) {
            Object id = m.get("id"), name = m.get("name"), ante = m.get("ante");
            long a = ante instanceof Number num ? num.longValue() : 0;
            if (id == null || name == null || a <= 0) {
                problems.add("rooms: id · name · ante(1 이상) 가 필요 " + m);
                continue;
            }
            rooms.add(new Room(id.toString(), name.toString(), a));
        }
        if (rooms.size() != 3) problems.add("rooms: 방은 셋 (초보방 · 일반방 · 큰손방), 지금 " + rooms.size());
        for (int i = 1; i < rooms.size(); i++) if (rooms.get(i).ante() <= rooms.get(i - 1).ante()) problems.add("rooms: 판돈은 뒤로 갈수록 커야 함");
        Map<Table.Game, GameSpec> games = new EnumMap<>(Table.Game.class);
        for (Table.Game g : Table.Game.values()) {
            ConfigurationSection s = c.getConfigurationSection("games." + g.key());
            if (s == null) {
                problems.add("games." + g.key() + " 이 없음");
                continue;
            }
            int max = s.getInt("max");
            int min = g == Table.Game.BACCARAT ? 1 : 2, top = g == Table.Game.HOLJJAK ? 2 : 6;
            if (max < min || max > top) problems.add("games." + g.key() + ".max: " + min + "~" + top);
            int dice = g == Table.Game.DICE ? s.getInt("dice") : 0;
            if (g == Table.Game.DICE && (dice < 1 || dice > 3)) problems.add("games.dice.dice: 1~3");
            boolean bets = g == Table.Game.SUTDA || g == Table.Game.POKER;
            int sit = bets ? s.getInt("sit-antes", 3) : 1, replays = g == Table.Game.SUTDA ? s.getInt("replays") : 0;
            if (sit < 1) problems.add("games." + g.key() + ".sit-antes: 1 이상 (앉는 데 드는 칩 = 판돈 × 이만큼)");
            if (replays < 0) problems.add("games.sutda.replays: 0 이상");
            games.put(g, new GameSpec(s.getString("name", g.key()), Math.clamp(max, min, top), Math.clamp(dice, 0, 3), Math.max(1, sit), Math.max(0, replays)));
        }
        int open = c.getInt("timers.open", 10);
        if (open < 3) problems.add("timers.open: 3초 이상 (세븐 포커 오픈할 패 고르기)");
        BaccaratSpec bac = BaccaratSpec.parse(c.getConfigurationSection("baccarat"), problems);
        long test = c.getLong("test-chips", 100_000);
        // sitting at the biggest room's 섯다 must fit under the daily loss cap and the daily buy limit (when they are on),
        // and under the test table's pretend chips
        if (!rooms.isEmpty() && games.containsKey(Table.Game.SUTDA)) {
            long sit = HwatuRules.need(Table.Game.SUTDA, rooms.getLast().ante(), games.get(Table.Game.SUTDA).sitAntes());
            if (test < sit) problems.add("test-chips 가 큰손방 섯다에 앉는 칩(" + sit + "칩)보다 작음");
            if (cap > 0 && sit > cap) problems.add("daily-loss-cap 이 큰손방 섯다에 앉는 칩(" + sit + "칩)보다 작음");
            if (daily > 0 && sit > daily) problems.add("chips.daily-buy 가 큰손방 섯다에 앉는 칩(" + sit + "칩)보다 작음");
        }
        return new HwatuSpec(Math.max(1, buy), Math.clamp(cash, 1, Math.max(1, buy)), Math.max(0, daily), Math.clamp(rake, 1, 50), Math.max(0, cap),
                Math.max(0, hours) * 3_600_000L, Math.max(5, turn), Math.max(1, start), Math.max(1, reveal), Math.max(1, result), Math.max(3, roll), rooms, games,
                Math.max(1, test), Math.max(0, limit), Math.max(3, open), bac);
    }
}
