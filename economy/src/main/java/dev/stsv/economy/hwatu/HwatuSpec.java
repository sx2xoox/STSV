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
 * </pre>
 */
public record HwatuSpec(long buyPrice, long cashPrice, long dailyBuy, int rakePercent, long lossCap, long minAgeMs,
                        int turnSeconds, int startSeconds, int revealSeconds, int resultSeconds, int rollSeconds,
                        List<Room> rooms, Map<Table.Game, GameSpec> games, long testChips, int handLimitAntes) {

    public record Room(String id, String name, long ante) {}

    /** max players at a table; dice: how many dice a player rolls; sitAntes (chips to sit, in antes) · replays: 섯다. */
    public record GameSpec(String name, int max, int dice, int sitAntes, int replays) {}

    public GameSpec game(Table.Game g) {
        return games.get(g);
    }

    /** Chips a player must hold to be dealt in at this game and room. */
    public long need(Table.Game g, Room r) {
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
            int min = 2, top = g == Table.Game.HOLJJAK ? 2 : 6;
            if (max < min || max > top) problems.add("games." + g.key() + ".max: " + min + "~" + top);
            int dice = g == Table.Game.DICE ? s.getInt("dice") : 0;
            if (g == Table.Game.DICE && (dice < 1 || dice > 3)) problems.add("games.dice.dice: 1~3");
            int sit = g == Table.Game.SUTDA ? s.getInt("sit-antes", 3) : 1, replays = g == Table.Game.SUTDA ? s.getInt("replays") : 0;
            if (sit < 1) problems.add("games.sutda.sit-antes: 1 이상 (앉는 데 드는 칩 = 판돈 × 이만큼)");
            if (replays < 0) problems.add("games.sutda.replays: 0 이상");
            games.put(g, new GameSpec(s.getString("name", g.key()), Math.clamp(max, min, top), Math.clamp(dice, 0, 3), Math.max(1, sit), Math.max(0, replays)));
        }
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
                Math.max(1, test), Math.max(0, limit));
    }
}
