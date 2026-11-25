package dev.stsv.economy.hwatu;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 화투방 (README "화투방"): the money rules, hwatu.yml, the 섯다 betting round, whole hands at a table, the chip store. */
class HwatuTest {

    static HwatuSpec spec() {
        List<String> problems = new ArrayList<>();
        HwatuSpec s = HwatuSpec.parse(YamlConfiguration.loadConfiguration(Path.of(System.getProperty("stsv.content")).resolve("economy/hwatu.yml").toFile()), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return s;
    }

    // ------------------------------------------------------------------------------------------ hwatu.yml

    @Test
    void theNumbersAreTheOwnersDefaults() {
        HwatuSpec s = spec();
        assertEquals(100, s.buyPrice());
        assertEquals(95, s.cashPrice());
        // "칩이나 게임은 제한 없게": no daily buy limit, no daily loss cap, no account age (each 0 = off, can be set again)
        assertEquals(0, s.dailyBuy());
        assertEquals(5, s.rakePercent());
        assertEquals(0, s.lossCap());
        assertEquals(0, s.minAgeMs());
        assertEquals(100_000, s.testChips());
        assertEquals(20, s.turnSeconds());
        assertEquals(List.of("초보방", "일반방", "큰손방"), s.rooms().stream().map(HwatuSpec.Room::name).toList());
        assertEquals(List.of(10L, 100L, 500L), s.rooms().stream().map(HwatuSpec.Room::ante).toList());
        assertEquals(2, s.game(Table.Game.HOLJJAK).max());
        assertEquals(6, s.game(Table.Game.DICE).max());
        assertEquals(6, s.game(Table.Game.SUTDA).max());
        assertEquals(2, s.game(Table.Game.SUTDA).maxRaises());
        assertEquals(1, s.game(Table.Game.SUTDA).replays());
        // 섯다 at 큰손방: 500 ante + 2 raises × 500 in the round and again in one 재경기
        assertEquals(2500, s.need(Table.Game.SUTDA, s.rooms().get(2)));
        assertEquals(500, s.need(Table.Game.DICE, s.rooms().get(2)));
        // chips never make gold: cashing gives back less than buying cost
        assertTrue(s.cashPrice() < s.buyPrice());
    }

    @Test
    void aBadFileIsReported() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("chips.buy-price", 100);
        y.set("chips.cash-price", 120);   // more than buying: chips would print money
        y.set("chips.daily-buy", 100);
        y.set("rake-percent", 0);
        y.set("daily-loss-cap", 50);
        y.set("timers.turn", 1);
        y.set("rooms", List.of(Map.of("id", "a", "name", "A", "ante", 100), Map.of("id", "b", "name", "B", "ante", 50)));
        List<String> problems = new ArrayList<>();
        HwatuSpec s = HwatuSpec.parse(y, problems);
        String all = String.join("\n", problems);
        for (String want : List.of("cash-price", "rake-percent", "timers.turn", "rooms: 방은 셋", "판돈은 뒤로", "games.sutda", "timers: start"))
            assertTrue(all.contains(want), want + " in\n" + all);
        assertTrue(s.cashPrice() <= s.buyPrice());
        assertTrue(s.rakePercent() >= 1);
    }

    // ------------------------------------------------------------------------------------------ money rules

    @Test
    void buyingAndCashing() {
        assertNull(HwatuRules.canBuy(10, 0, 5000, 1000, 100));
        assertEquals("hwatu.buy-gold", HwatuRules.canBuy(11, 0, 5000, 1000, 100));
        assertEquals("hwatu.buy-limit", HwatuRules.canBuy(1, 5000, 5000, 1_000_000, 100));
        assertNull(HwatuRules.canBuy(1, 4999, 5000, 1_000_000, 100));
        assertEquals("hwatu.buy-limit", HwatuRules.canBuy(2, 4999, 5000, 1_000_000, 100));
        assertEquals("hwatu.bad-amount", HwatuRules.canBuy(0, 0, 5000, 1_000_000, 100));
        assertEquals("hwatu.bad-amount", HwatuRules.canBuy(-5, 0, 5000, 1_000_000, 100));
        assertEquals(0, HwatuRules.buyLeft(6000, 5000));
        assertEquals(300, HwatuRules.buyLeft(4700, 5000));
        assertNull(HwatuRules.canCash(5, 5));
        assertEquals("hwatu.cash-short", HwatuRules.canCash(6, 5));
        assertEquals("hwatu.bad-amount", HwatuRules.canCash(0, 5));
    }

    @Test
    void zeroMeansNoLimit() {
        assertNull(HwatuRules.canBuy(1_000_000, 50_000_000, 0, 100_000_000_000L, 100), "no daily buy limit");
        assertEquals("hwatu.buy-gold", HwatuRules.canBuy(1_000_000, 0, 0, 1000, 100), "gold is still needed");
        assertEquals(Long.MAX_VALUE, HwatuRules.buyLeft(123_456, 0));
        assertTrue(HwatuRules.oldEnough(0, 1000, 0), "no age rule: even a never-seen account");
        assertTrue(HwatuRules.oldEnough(999, 1000, 0));
        assertFalse(HwatuRules.resting(-1_000_000_000L, 0), "no loss cap");
        assertNull(HwatuRules.canPlay(0, 1000, 0, -1_000_000_000L, 0, 50, 50));
        assertEquals("hwatu.need-chips", HwatuRules.canPlay(0, 1000, 0, 0, 0, 49, 50), "a hand still has to be covered");
        // the parsed file accepts 0 and leaves the 섯다 checks against the limits out
        YamlConfiguration y = YamlConfiguration.loadConfiguration(Path.of(System.getProperty("stsv.content")).resolve("economy/hwatu.yml").toFile());
        y.set("chips.daily-buy", 0);
        y.set("daily-loss-cap", 0);
        y.set("min-account-hours", 0);
        List<String> problems = new ArrayList<>();
        HwatuSpec s = HwatuSpec.parse(y, problems);
        assertEquals(List.of(), problems);
        assertEquals(0, s.dailyBuy());
        assertEquals(0, s.lossCap());
        // and the limits can be turned back on
        y.set("chips.daily-buy", 5000);
        y.set("daily-loss-cap", 3000);
        y.set("min-account-hours", 24);
        s = HwatuSpec.parse(y, problems);
        assertEquals(List.of(), problems);
        assertEquals(5000, s.dailyBuy());
        assertEquals(3000, s.lossCap());
        assertEquals(24 * 3_600_000L, s.minAgeMs());
        y.set("daily-loss-cap", -1);
        HwatuSpec.parse(y, problems);
        assertTrue(String.join("\n", problems).contains("daily-loss-cap"));
    }

    @Test
    void whoMayPlay() {
        long now = 10_000_000_000L, day = 86_400_000L;
        assertEquals("hwatu.too-new", HwatuRules.canPlay(now - day + 1, now, day, 0, 3000, 10_000, 100));
        assertEquals("hwatu.too-new", HwatuRules.canPlay(0, now, day, 0, 3000, 10_000, 100), "never seen: too new");
        assertNull(HwatuRules.canPlay(now - day, now, day, 0, 3000, 10_000, 100));
        assertEquals("hwatu.resting", HwatuRules.canPlay(now - day, now, day, -3000, 3000, 10_000, 100));
        assertNull(HwatuRules.canPlay(now - day, now, day, -2999, 3000, 10_000, 100));
        assertNull(HwatuRules.canPlay(now - day, now, day, 50_000, 3000, 10_000, 100), "a winning day never rests");
        assertEquals("hwatu.need-chips", HwatuRules.canPlay(now - day, now, day, 0, 3000, 99, 100));
        assertEquals(10, HwatuRules.need(Table.Game.HOLJJAK, 10, 2, 1));
        assertEquals(10, HwatuRules.need(Table.Game.DICE, 10, 2, 1));
        assertEquals(50, HwatuRules.need(Table.Game.SUTDA, 10, 2, 1));
        assertEquals(30, HwatuRules.need(Table.Game.SUTDA, 10, 2, 0));
        assertEquals(10, HwatuRules.need(Table.Game.SUTDA, 10, 0, 3));
    }

    @Test
    void theRakeIsFivePercentRoundedUpAndASplitNeverMakesChips() {
        assertEquals(1, HwatuRules.rake(20, 5));
        assertEquals(2, HwatuRules.rake(21, 5));
        assertEquals(5, HwatuRules.rake(100, 5));
        assertEquals(1, HwatuRules.rake(1, 5));
        assertEquals(0, HwatuRules.rake(0, 5));
        HwatuRules.Split s = HwatuRules.split(200, 5, 1);
        assertEquals(190, s.share());
        assertEquals(10, s.burned());
        s = HwatuRules.split(300, 5, 2);   // rake 15, 285 / 2 = 142 each, the odd chip goes too
        assertEquals(142, s.share());
        assertEquals(16, s.burned());
        for (long pot = 0; pot <= 3000; pot += 7) {
            for (int w = 1; w <= 6; w++) {
                HwatuRules.Split x = HwatuRules.split(pot, 5, w);
                assertEquals(pot, x.share() * w + x.burned());
                assertTrue(x.burned() * 100 >= pot * 5, "at least 5% is destroyed");
                assertTrue(x.burned() < HwatuRules.rake(pot, 5) + w);
            }
        }
        assertEquals(new HwatuRules.Split(0, 50), HwatuRules.split(50, 5, 0));
    }

    @Test
    void holjjakAndDice() {
        for (int die = 1; die <= 6; die++) {
            assertEquals(die % 2 == 1, HwatuRules.callerWins(true, die));
            assertEquals(die % 2 == 0, HwatuRules.callerWins(false, die));
        }
        assertEquals(List.of(1), HwatuRules.highest(new int[]{7, 11, 3}));
        assertEquals(List.of(0, 2), HwatuRules.highest(new int[]{9, 4, 9}));
        assertEquals(List.of(0, 1, 2), HwatuRules.highest(new int[]{6, 6, 6}));
    }

    // ------------------------------------------------------------------------------------------ the betting round

    @Test
    void aBettingRound() {
        Betting b = new Betting(3, 10, 2);
        assertEquals(0, b.turn());
        assertEquals(0, b.act(0, Betting.Action.CALL));   // a check
        assertEquals(10, b.act(1, Betting.Action.RAISE));
        assertEquals(2, b.turn());
        assertEquals(20, b.act(2, Betting.Action.RAISE));
        assertFalse(b.canRaise());
        assertThrows(IllegalStateException.class, () -> b.act(0, Betting.Action.RAISE));
        assertEquals(20, b.toCall(0));
        assertEquals(20, b.act(0, Betting.Action.CALL));
        assertEquals(Betting.Action.DIE, b.timeout(), "seat 1 owes 10");
        b.act(1, Betting.Action.DIE);
        assertTrue(b.done());
        assertEquals(List.of(0, 2), b.in());
        assertEquals(20, b.put(0));
        assertEquals(10, b.put(1));
        assertEquals(20, b.put(2));
        assertEquals(20, Betting.most(10, 2));
        assertThrows(IllegalStateException.class, () -> b.act(0, Betting.Action.CALL), "the round is over");
    }

    @Test
    void everyoneChecksOrFolds() {
        Betting b = new Betting(2, 10, 2);
        assertEquals(Betting.Action.CALL, b.timeout(), "nothing to match: the timer calls");
        b.act(0, Betting.Action.CALL);
        assertFalse(b.done());
        b.act(1, Betting.Action.CALL);
        assertTrue(b.done());
        assertEquals(2, b.active());

        Betting c = new Betting(4, 10, 2);
        assertThrows(IllegalStateException.class, () -> c.act(2, Betting.Action.CALL), "out of turn");
        c.fold(2);   // left the table out of turn
        c.act(0, Betting.Action.RAISE);
        assertEquals(1, c.turn());
        c.act(1, Betting.Action.DIE);
        assertEquals(3, c.turn(), "seat 2 is skipped");
        c.fold(3);   // left on their turn
        assertTrue(c.done());
        assertEquals(1, c.active());
        assertEquals(List.of(0), c.in());
    }

    @Test
    void aBettingRoundAlwaysEnds() {
        Random r = new Random(7);
        for (int k = 0; k < 2000; k++) {
            int n = 2 + r.nextInt(5);
            Betting b = new Betting(n, 10, 2);
            int steps = 0;
            while (!b.done()) {
                int seat = b.turn();
                Betting.Action a = Betting.Action.values()[r.nextInt(3)];
                if (a == Betting.Action.RAISE && !b.canRaise()) a = Betting.Action.CALL;
                b.act(seat, a);
                assertTrue(++steps < 100);
            }
            for (int s : b.in()) assertEquals(b.bet(), b.put(s), "everyone still in matched the bet");
            for (int s = 0; s < n; s++) assertTrue(b.put(s) <= 20);
        }
    }

    // ------------------------------------------------------------------------------------------ a table

    /** Records what the table tells the module. */
    static final class Log implements Table.Listener {
        final List<Table.Result> results = new ArrayList<>();
        final Map<UUID, String> kicked = new HashMap<>();
        int replays;

        @Override
        public void changed(Table t) {}

        @Override
        public void kicked(Table t, UUID player, String reason) {
            kicked.put(player, reason);
        }

        @Override
        public void finished(Table t, Table.Result r) {
            results.add(r);
            long paid = 0, net = 0;
            for (long v : r.payout().values()) paid += v;
            for (UUID u : r.players()) net += r.net(u);
            assertEquals(r.pot(), paid + r.burned(), "every chip of the pot is paid or destroyed");
            assertEquals(-r.burned(), net, "the players lose exactly what is destroyed");
            assertTrue(r.burned() >= HwatuRules.rake(r.pot(), 5));
        }

        @Override
        public void replay(Table t) {
            replays++;
        }
    }

    static final Table.Bank ALL = (u, need) -> null;

    private static Table table(Table.Game g, int room, long seed) {
        HwatuSpec s = spec();
        return new Table(1, g, s.rooms().get(room), s, new Random(seed));
    }

    @Test
    void holjjakFromSittingToTheResult() {
        Table t = table(Table.Game.HOLJJAK, 0, 1);
        Log log = new Log();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        long now = 1_000_000;
        assertTrue(t.sit(a, now));
        assertFalse(t.sit(a, now), "already seated");
        t.tick(now + 60_000, ALL, log);
        assertEquals(Table.Phase.WAITING, t.phase(), "alone: no hand");
        assertTrue(t.sit(b, now));
        assertFalse(t.sit(c, now), "1:1 only");
        t.tick(now + 9_000, ALL, log);
        assertEquals(Table.Phase.WAITING, t.phase(), "10 seconds before the first hand");
        t.tick(now + 10_000, ALL, log);
        assertEquals(Table.Phase.PLAYING, t.phase());
        assertEquals(Table.Step.PICK, t.step());
        assertEquals(20, t.pot());
        UUID caller = t.turn(), other = caller.equals(a) ? b : a;
        assertEquals("hwatu.not-your-turn", t.act(other, Table.Move.ODD, now + 10_500, log));
        assertNull(t.act(caller, Table.Move.EVEN, now + 11_000, log));
        assertEquals(Table.Step.ROLL, t.step());
        assertFalse(t.calledOdd());
        t.tick(now + 13_999, ALL, log);
        assertTrue(log.results.isEmpty());
        t.tick(now + 14_000, ALL, log);
        assertEquals(Table.Phase.RESULT, t.phase());
        Table.Result r = log.results.getFirst();
        assertEquals(List.of(HwatuRules.callerWins(false, r.die()) ? caller : other), r.winners());
        assertEquals(20, r.pot());
        assertEquals(1, r.burned());
        assertEquals(19, r.payout().get(r.winners().getFirst()));
        assertEquals(-10, r.net(r.winners().getFirst().equals(a) ? b : a));
        // the next hand after the result's pause, the other player calls first
        t.tick(now + 20_000, ALL, log);
        assertEquals(Table.Phase.WAITING, t.phase());
        t.tick(now + 21_000, ALL, log);
        assertEquals(Table.Phase.PLAYING, t.phase());
        assertEquals(other, t.turn(), "the first move goes round");
        // the turn timer calls for a player who does not
        t.tick(now + 21_000 + 20_000, ALL, log);
        assertEquals(Table.Step.ROLL, t.step());
        assertNotNull(t.calledOdd());
    }

    @Test
    void leavingMidHandLosesWhatWasPut() {
        Table t = table(Table.Game.HOLJJAK, 1, 2);
        Log log = new Log();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        t.sit(a, 0);
        t.sit(b, 0);
        t.tick(10_000, ALL, log);
        assertEquals(Table.Phase.PLAYING, t.phase());
        t.leave(a, 11_000, log);
        Table.Result r = log.results.getFirst();
        assertTrue(r.uncontested());
        assertEquals(List.of(b), r.winners());
        assertEquals(-100, r.net(a));
        assertEquals(200 - 10 - 100, r.net(b));
        assertEquals("die", r.detail().get(a));
        assertEquals(List.of(b), t.seats());
    }

    @Test
    void aPlayerWhoCannotPlayStandsUp() {
        Table t = table(Table.Game.DICE, 0, 3);
        Log log = new Log();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        t.sit(a, 0);
        t.sit(b, 0);
        t.sit(c, 0);
        t.tick(10_000, (u, need) -> u.equals(c) ? "hwatu.resting" : null, log);
        assertEquals("hwatu.resting", log.kicked.get(c));
        assertEquals(List.of(a, b), t.seats());
        assertEquals(Table.Phase.PLAYING, t.phase());
        // everyone stands up: nobody left to play
        Table u = table(Table.Game.DICE, 0, 3);
        u.sit(a, 0);
        u.sit(b, 0);
        u.tick(10_000, (x, need) -> "hwatu.away", log);
        assertEquals(Table.Phase.WAITING, u.phase());
        assertTrue(u.empty());
    }

    @Test
    void diceHighestTotalWinsAndTiesSplit() {
        int splits = 0;
        for (long seed = 0; seed < 300; seed++) {
            Table t = table(Table.Game.DICE, 0, seed);
            Log log = new Log();
            List<UUID> ps = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            for (UUID p : ps) t.sit(p, 0);
            t.tick(10_000, ALL, log);
            assertEquals(Table.Step.ROLL, t.step());
            t.tick(13_000, ALL, log);
            Table.Result r = log.results.getFirst();
            assertEquals(40, r.pot());
            int best = 0;
            Map<UUID, Integer> totals = new HashMap<>();
            for (UUID p : ps) {
                int[] roll = t.rolls(p);
                assertEquals(2, roll.length);
                totals.put(p, roll[0] + roll[1]);
                best = Math.max(best, roll[0] + roll[1]);
            }
            for (UUID p : ps) assertEquals(totals.get(p) == best, r.winners().contains(p));
            if (r.winners().size() > 1) splits++;
        }
        assertTrue(splits > 0);
    }

    @Test
    void sutdaBettingAndShowdown() {
        Table t = table(Table.Game.SUTDA, 0, 4);
        Log log = new Log();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        for (UUID p : List.of(a, b, c)) t.sit(p, 0);
        long now = 10_000;
        t.tick(now, ALL, log);
        assertEquals(Table.Step.BET, t.step());
        List<UUID> order = t.players();
        java.util.Set<Card> dealt = new java.util.HashSet<>();
        for (UUID p : order) {
            assertEquals(2, t.cards(p).length);
            dealt.addAll(List.of(t.cards(p)));
        }
        assertEquals(6, dealt.size(), "six different cards");
        assertEquals(30, t.pot());
        assertEquals(order.getFirst(), t.turn());
        assertNull(t.act(order.get(0), Table.Move.RAISE, now, log));
        assertNull(t.act(order.get(1), Table.Move.RAISE, now, log));
        assertEquals("hwatu.no-raise", t.act(order.get(2), Table.Move.RAISE, now, log));
        assertNull(t.act(order.get(2), Table.Move.CALL, now, log));
        assertEquals(30 + 10 + 20 + 20, t.pot());
        // order 0 owes 10: the timer folds them
        t.tick(now + 20_000, ALL, log);
        assertTrue(t.folded(order.get(0)));
        Table.Result r = log.results.isEmpty() ? null : log.results.getFirst();
        if (r == null) {   // 구사 replay: the pot stays, a new round
            assertEquals(1, log.replays);
            assertEquals(80, t.pot());
            return;
        }
        assertEquals(80, r.pot());
        assertEquals("die", r.detail().get(order.get(0)));
        assertNotNull(r.detail().get(order.get(1)));
        assertEquals(-20, r.net(order.get(0)));   // the ante and the first raise
        assertTrue(r.winners().stream().allMatch(w -> !w.equals(order.get(0))));
    }

    @Test
    void sutdaEveryoneElseFolds() {
        Table t = table(Table.Game.SUTDA, 2, 5);
        Log log = new Log();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        for (UUID p : List.of(a, b, c)) t.sit(p, 0);
        t.tick(10_000, ALL, log);
        List<UUID> order = t.players();
        t.act(order.get(0), Table.Move.RAISE, 10_000, log);
        t.leave(order.get(2), 10_500, log);   // out of turn
        assertEquals(order.get(1), t.turn());
        t.act(order.get(1), Table.Move.DIE, 11_000, log);
        Table.Result r = log.results.getFirst();
        assertTrue(r.uncontested());
        assertEquals(List.of(order.get(0)), r.winners());
        assertEquals(500 * 3 + 500, r.pot());
        assertEquals(HwatuRules.rake(2000, 5), r.burned());
    }

    @Test
    void sutdaChecksAroundByTheTimer() {
        Table t = table(Table.Game.SUTDA, 0, 6);
        Log log = new Log();
        t.sit(UUID.randomUUID(), 0);
        t.sit(UUID.randomUUID(), 0);
        t.tick(10_000, ALL, log);
        t.tick(30_000, ALL, log);   // nothing to match: 콜
        t.tick(50_000, ALL, log);
        if (log.replays == 0) {
            assertEquals(1, log.results.size());
            assertFalse(log.results.getFirst().uncontested());
            assertEquals(20, log.results.getFirst().pot());
        }
    }

    @Test
    void gusaReplaysWithTheSamePotOnce() {
        int seen = 0;
        for (long seed = 0; seed < 4000 && seen < 5; seed++) {
            Table t = table(Table.Game.SUTDA, 0, seed);
            Log log = new Log();
            for (int k = 0; k < 4; k++) t.sit(UUID.randomUUID(), 0);
            long now = 10_000;
            t.tick(now, ALL, log);
            for (int k = 0; k < 4; k++) t.act(t.turn(), Table.Move.CALL, now, log);
            if (log.replays == 0) {
                assertEquals(1, log.results.size());
                continue;
            }
            seen++;
            assertEquals(Table.Phase.PLAYING, t.phase());
            assertEquals(40, t.pot(), "no new ante");
            assertEquals(1, t.replays());
            for (UUID p : t.players()) assertEquals(2, t.cards(p).length, "dealt again");
            for (int k = 0; k < 4; k++) t.act(t.turn(), Table.Move.CALL, now, log);
            assertEquals(1, log.replays, "only one replay a hand (hwatu.yml replays: 1)");
            assertEquals(1, log.results.size());
            assertEquals(40, log.results.getFirst().pot());
        }
        assertTrue(seen > 0, "a 구사 replay happens in 4,000 deals");
    }

    // ------------------------------------------------------------------------------------------ the chip store

    @Test
    void chipsSurviveARestartAndTheDayStartsOver(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        ChipStore s = new ChipStore(db);
        s.init();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        s.bought(a, 500, 3);
        s.settle(a, -120, 3);
        s.settle(b, 0, 3);
        assertFalse(s.cash(a, 381, 0, 3));
        assertFalse(s.cash(a, 300, 100, 3), "100 are in a running hand");
        assertTrue(s.cash(a, 280, 100, 3));
        s.log(1000, "sutda", "normal", List.of(a, b), 200, 10, "A 100→0 · B 100→190");
        s.log(2000, "dice", "beginner", List.of(b), 20, 1, "B");
        db.close();

        db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        s = new ChipStore(db);
        s.init();
        ChipStore.Account acc = s.account(a, 3);
        assertEquals(100, acc.chips);
        assertEquals(500, acc.bought);
        assertEquals(-120, acc.net);
        acc = s.account(a, 4);   // the next day: the counters start again, the chips stay
        assertEquals(100, acc.chips);
        assertEquals(0, acc.bought);
        assertEquals(0, acc.net);
        s.bought(a, 10, 4);
        assertEquals(10, s.account(a, 4).bought);
        assertEquals(110, s.total());
        assertEquals(2, s.hands(null, 10).size());
        assertEquals(1, s.hands(a, 10).size());
        assertEquals("sutda", s.hands(a, 10).getFirst().game());
        assertEquals(2, s.hands(b, 10).size());
        assertEquals("dice", s.hands(b, 10).getFirst().game(), "newest first");
        s.settle(a, -1000, 4);
        assertEquals(0, s.account(a, 4).chips, "never below 0");
        db.close();
    }

    // ------------------------------------------------------------------------------------------ messages

    @Test
    void everyHandAndButtonHasItsText() throws Exception {
        YamlConfiguration m;
        try (var in = getClass().getResourceAsStream("/messages.yml")) {
            m = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> missing = new ArrayList<>();
        List<Card> d = Card.deck();
        for (int i = 0; i < d.size(); i++) for (int j = i + 1; j < d.size(); j++) {
            String k = HwatuModule.HAND + Sutda.of(d.get(i), d.get(j)).code();
            if (!m.isString(k)) missing.add(k);
        }
        for (String k : List.of("odd", "even", "die")) if (!m.isString(HwatuModule.HAND + k)) missing.add(k);
        // the buttons on the tables (TableScene: hwatu.btn.<key>, a free call "call-free")
        for (Controls.Button b : Controls.Button.values()) if (!m.isString("hwatu.btn." + b.key())) missing.add("btn." + b.key());
        if (!m.isString("hwatu.btn.call-free")) missing.add("btn.call-free");
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }
}
