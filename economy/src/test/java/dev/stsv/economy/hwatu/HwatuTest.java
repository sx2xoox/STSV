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
        assertEquals(10, s.rollSeconds());
        assertEquals(List.of("초보방", "일반방", "큰손방"), s.rooms().stream().map(HwatuSpec.Room::name).toList());
        assertEquals(List.of(1L, 10L, 100L), s.rooms().stream().map(HwatuSpec.Room::ante).toList());
        assertEquals(100, s.handLimitAntes(), "한도: the most one player puts into one hand = 100 antes");
        assertEquals(2, s.game(Table.Game.HOLJJAK).max());
        assertEquals(6, s.game(Table.Game.DICE).max());
        assertEquals(6, s.game(Table.Game.SUTDA).max());
        assertEquals(3, s.game(Table.Game.SUTDA).sitAntes());
        assertEquals(1, s.game(Table.Game.SUTDA).replays());
        // 섯다 at 큰손방: ante × 3 (a player short of chips goes all-in)
        assertEquals(300, s.need(Table.Game.SUTDA, s.rooms().get(2)));
        assertEquals(3, s.need(Table.Game.SUTDA, s.rooms().get(0)));
        assertEquals(100, s.need(Table.Game.DICE, s.rooms().get(2)));
        // the hand limit of each room: 초보방 100 · 일반방 1,000 · 큰손방 10,000
        for (int k = 0; k < 3; k++)
            assertEquals(List.of(100L, 1000L, 10_000L).get(k), new Table(1, Table.Game.SUTDA, s.rooms().get(k), s, new Random(1)).handLimit());
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
        assertEquals(10, HwatuRules.need(Table.Game.HOLJJAK, 10, 3));
        assertEquals(10, HwatuRules.need(Table.Game.DICE, 10, 3));
        assertEquals(30, HwatuRules.need(Table.Game.SUTDA, 10, 3));
        assertEquals(10, HwatuRules.need(Table.Game.SUTDA, 10, 1), "1 ante is safe too (all-in)");
        assertEquals(10, HwatuRules.need(Table.Game.SUTDA, 10, 0), "never below the ante");
    }

    @Test
    void theRakeIsFivePercentRoundedDownAndASplitNeverMakesChips() {
        // rounded down: with 1-chip antes a 2-chip pot pays nothing (rounded up it would lose half); 20 chips and up pay
        assertEquals(0, HwatuRules.rake(1, 5));
        assertEquals(0, HwatuRules.rake(2, 5));
        assertEquals(0, HwatuRules.rake(19, 5));
        assertEquals(1, HwatuRules.rake(20, 5));
        assertEquals(1, HwatuRules.rake(39, 5));
        assertEquals(2, HwatuRules.rake(40, 5));
        assertEquals(5, HwatuRules.rake(100, 5));
        assertEquals(0, HwatuRules.rake(0, 5));
        assertEquals(new HwatuRules.Split(2, 0), HwatuRules.split(2, 5, 1), "a 1-chip 홀짝: the winner gets both chips");
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
                assertTrue(HwatuRules.rake(pot, 5) * 100 <= pot * 5 && (HwatuRules.rake(pot, 5) + 1) * 100 > pot * 5, "5%, rounded down");
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

    // the betting round itself: BettingTest

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
            long pots = 0, rakes = 0;
            for (Table.PotResult p : r.pots()) {
                pots += p.amount();
                rakes += HwatuRules.rake(p.amount(), 5);
                assertTrue(p.burned() >= HwatuRules.rake(p.amount(), 5), "each pot pays its own 자릿세 (5%, down)");
            }
            assertTrue(r.burned() >= rakes);
            assertTrue(rakes * 100 <= r.pot() * 5, "never more than 5% of the hand");
            assertEquals(r.pot(), pots, "the pots hold the whole pot");
            for (UUID u : r.players()) assertTrue(r.put().get(u) >= 0);
        }

        @Override
        public void replay(Table t) {
            replays++;
        }
    }

    static final Table.Bank ALL = (u, need) -> null;

    /** Everyone dealt in, each with these chips to bet. */
    static Table.Bank stacks(Map<UUID, Long> chips) {
        return new Table.Bank() {
            public String refusal(UUID player, long need) {
                return null;
            }

            public long stack(UUID player) {
                return chips.get(player);
            }
        };
    }

    /** 체크 if nobody bet yet, else 콜. */
    static Table.Move pass(Table t, UUID u) {
        return t.legal(u).contains(Betting.Action.CHECK) ? Table.Move.CHECK : Table.Move.CALL;
    }

    private static Table table(Table.Game g, int room, long seed) {
        HwatuSpec s = spec();
        return new Table(1, g, s.rooms().get(room), s, new Random(seed));
    }

    @Test
    void holjjakFromSittingToTheResult() {
        Table t = table(Table.Game.HOLJJAK, 1, 1);
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
        // the same player now presses 굴리기: the die is not rolled by itself
        assertEquals(caller, t.turn());
        assertEquals(10, t.moveSeconds());
        assertEquals("hwatu.not-your-turn", t.act(other, Table.Move.ROLL, now + 11_500, log));
        assertEquals("hwatu.not-your-turn", t.act(caller, Table.Move.ODD, now + 11_500, log), "called already");
        t.tick(now + 20_999, ALL, log);
        assertEquals(Table.Step.ROLL, t.step(), "waits for the press (10 s)");
        assertEquals(0, t.die());
        assertNull(t.act(caller, Table.Move.ROLL, now + 12_000, log));
        assertEquals(Table.Step.SHOW, t.step());
        assertTrue(t.die() >= 1 && t.die() <= 6);
        assertNull(t.turn());
        t.tick(now + 14_999, ALL, log);
        assertTrue(log.results.isEmpty(), "the die shows for timers.reveal first");
        t.tick(now + 15_000, ALL, log);
        assertEquals(Table.Phase.RESULT, t.phase());
        Table.Result r = log.results.getFirst();
        assertEquals(List.of(HwatuRules.callerWins(false, r.die()) ? caller : other), r.winners());
        assertEquals(20, r.pot());
        assertEquals(1, r.burned());
        assertEquals(19, r.payout().get(r.winners().getFirst()));
        assertEquals(-10, r.net(r.winners().getFirst().equals(a) ? b : a));
        // the next hand after the result's pause, the other player calls first
        t.tick(now + 21_000, ALL, log);
        assertEquals(Table.Phase.WAITING, t.phase());
        t.tick(now + 22_000, ALL, log);
        assertEquals(Table.Phase.PLAYING, t.phase());
        assertEquals(other, t.turn(), "the first move goes round");
        // the turn timer calls for a player who does not, then rolls for them
        t.tick(now + 22_000 + 20_000, ALL, log);
        assertEquals(Table.Step.ROLL, t.step());
        assertNotNull(t.calledOdd());
        t.tick(now + 42_000 + 10_000, ALL, log);
        assertEquals(Table.Step.SHOW, t.step(), "not pressed in 10 s: rolled for them");
        t.tick(now + 52_000 + 3_000, ALL, log);
        assertEquals(2, log.results.size());
    }

    @Test
    void diceRollOneAfterAnotherClockwiseAndTheTimerRollsForTheIdle() {
        Table t = table(Table.Game.DICE, 1, 21);
        Log log = new Log();
        List<UUID> rolled = new ArrayList<>();
        Table.Listener l = new Table.Listener() {
            public void changed(Table x) {}
            public void kicked(Table x, UUID p, String r) {}
            public void finished(Table x, Table.Result r) { log.finished(x, r); }
            public void replay(Table x) {}
            public void rolled(Table x, UUID p) { rolled.add(p); }
        };
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        // they sat down in another order than their chairs go round
        Map<UUID, Integer> chair = Map.of(a, 3, b, 0, c, 5, d, 1);
        for (UUID u : List.of(a, b, c, d)) t.sit(u, 0);
        Table.Bank bank = new Table.Bank() {
            public String refusal(UUID p, long need) { return null; }
            public int chair(UUID p) { return chair.get(p); }
        };
        long now = 10_000;
        t.tick(now, bank, l);
        assertEquals(List.of(b, d, a, c), t.players(), "clockwise round the chairs from the dealer");
        assertEquals(Table.Step.ROLL, t.step());
        assertEquals(b, t.turn());
        assertEquals("hwatu.not-your-turn", t.act(d, Table.Move.ROLL, now, l));
        assertNull(t.act(b, Table.Move.ROLL, now, l));
        assertEquals(2, t.rolls(b).length);
        assertNull(t.rolls(d));
        assertEquals(d, t.turn());
        // d does nothing: rolled for them after timers.roll
        t.tick(now + 9_999, bank, l);
        assertEquals(d, t.turn());
        t.tick(now + 10_000, bank, l);
        assertNotNull(t.rolls(d));
        assertEquals(a, t.turn());
        // a, whose turn it is, leaves: c rolls next with a fresh timer
        t.leave(a, now + 11_000, l);
        assertEquals(c, t.turn());
        assertEquals(now + 11_000 + 10_000, t.deadline());
        assertNull(t.act(c, Table.Move.ROLL, now + 12_000, l));
        assertEquals(List.of(b, d, c), rolled);
        assertEquals(Table.Step.SHOW, t.step());
        assertTrue(log.results.isEmpty());
        t.tick(now + 15_000, bank, l);
        Table.Result r = log.results.getFirst();
        assertEquals("die", r.detail().get(a));
        assertFalse(r.winners().contains(a));
        int best = 0;
        for (UUID u : List.of(b, d, c)) best = Math.max(best, t.rolls(u)[0] + t.rolls(u)[1]);
        for (UUID u : List.of(b, d, c)) assertEquals(t.rolls(u)[0] + t.rolls(u)[1] == best, r.winners().contains(u));
    }

    @Test
    void leavingMidHandLosesWhatWasPut() {
        Table t = table(Table.Game.HOLJJAK, 2, 2);
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
        Table t = table(Table.Game.DICE, 1, 3);
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
        Table u = table(Table.Game.DICE, 1, 3);
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
            Table t = table(Table.Game.DICE, 1, seed);
            Log log = new Log();
            List<UUID> ps = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            for (UUID p : ps) t.sit(p, 0);
            t.tick(10_000, ALL, log);
            assertEquals(Table.Step.ROLL, t.step());
            for (int k = 0; k < 4; k++) assertNull(t.act(t.turn(), Table.Move.ROLL, 10_000, log));
            assertEquals(Table.Step.SHOW, t.step());
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
        Table t = table(Table.Game.SUTDA, 1, 4);
        Log log = new Log();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        for (UUID p : List.of(a, b, c)) t.sit(p, 0);
        long now = 10_000;
        t.tick(now, ALL, log);
        assertEquals(Table.Step.BET, t.step());
        List<UUID> order = t.players();
        java.util.Set<Card> dealt = new java.util.HashSet<>();
        assertEquals(1, t.street());
        for (UUID p : order) {
            assertEquals(1, t.cards(p).length, "one card on the first street");
            assertNull(t.hand(p));
            dealt.addAll(List.of(t.cards(p)));
        }
        assertEquals(3, dealt.size(), "three different cards");
        assertEquals(30, t.pot());
        assertEquals(order.getFirst(), t.turn());
        assertEquals("hwatu.bad-bet", t.act(order.get(0), Table.Move.CALL, now, log), "nothing to call yet");
        assertEquals("hwatu.bad-bet", t.act(order.get(0), Table.Move.DDADANG, now, log), "nothing to double yet");
        assertNull(t.act(order.get(0), Table.Move.PING, now, log));
        assertEquals(new Table.Bet(order.get(0), Betting.Action.PING, 10, false), t.lastBet());
        assertNull(t.act(order.get(1), Table.Move.DDADANG, now, log));
        assertEquals(20, t.lastBet().added());
        assertEquals("hwatu.bad-bet", t.act(order.get(2), Table.Move.CHECK, now, log));
        assertEquals("hwatu.bad-bet", t.act(order.get(2), Table.Move.PING, now, log));
        // 하프: call 20, then half of the pot after that call ((30 + 10 + 20) + 20) / 2 = 40
        assertNull(t.act(order.get(2), Table.Move.HALF, now, log));
        assertEquals(60, t.lastBet().added());
        assertEquals(30 + 10 + 20 + 60, t.pot());
        // order 0 owes 50: the timer folds them; order 1 calls 40
        t.tick(now + 20_000, ALL, log);
        assertTrue(t.folded(order.get(0)));
        assertEquals(Betting.Action.DIE, t.lastBet().action());
        assertNull(t.act(order.get(1), Table.Move.CALL, now + 20_000, log));
        // the first street is over: no showdown yet, the second card, a new round where 체크 is allowed again
        assertTrue(log.results.isEmpty(), "never over after one street");
        assertEquals(2, t.street());
        assertNull(t.lastBet());
        assertEquals(1, t.cards(order.get(0)).length, "a folded player gets no second card");
        for (UUID p : List.of(order.get(1), order.get(2))) {
            assertEquals(2, t.cards(p).length);
            assertNotNull(t.hand(p));
            dealt.add(t.cards(p)[1]);
        }
        assertEquals(5, dealt.size());
        assertEquals(0, t.betting().bet(), "a fresh round: nothing to match");
        assertEquals(order.get(1), t.turn(), "the first still in acts first");
        assertTrue(t.legal(order.get(1)).contains(Betting.Action.CHECK));
        assertNull(t.act(order.get(1), Table.Move.CHECK, now + 21_000, log));
        assertNull(t.act(order.get(2), Table.Move.CHECK, now + 21_000, log));
        Table.Result r = log.results.isEmpty() ? null : log.results.getFirst();
        if (r == null) {   // 구사 replay: the pot stays, a new round
            assertEquals(1, log.replays);
            assertEquals(160, t.pot());
            return;
        }
        assertEquals(160, r.pot());
        assertEquals(1, r.pots().size(), "nobody all-in: one pot");
        assertEquals("die", r.detail().get(order.get(0)));
        assertNotNull(r.detail().get(order.get(1)));
        assertEquals(-20, r.net(order.get(0)));   // the ante and the 삥
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
        t.act(order.get(0), Table.Move.PING, 10_000, log);
        t.leave(order.get(2), 10_500, log);   // out of turn
        assertEquals(order.get(1), t.turn());
        t.act(order.get(1), Table.Move.DIE, 11_000, log);
        Table.Result r = log.results.getFirst();
        assertTrue(r.uncontested());
        assertEquals(List.of(order.get(0)), r.winners());
        // the 삥 nobody answered goes back: the pot is the three antes
        assertEquals(100 * 3, r.pot());
        assertEquals(100, r.put().get(order.get(0)));
        assertEquals(HwatuRules.rake(300, 5), r.burned());
        assertEquals(300 - 15 - 100, r.net(order.get(0)));
        assertEquals(-100, r.net(order.get(1)));
    }

    /** Plays a 섯다 hand with set cards and stacks: each player shoves (하프 / 콜 until all-in) or as `moves` say. */
    private static Table rigged(Map<UUID, Card[]> cards, Map<UUID, Long> chips, long seed) {
        Table t = table(Table.Game.SUTDA, 1, seed);
        for (Map.Entry<UUID, Card[]> e : cards.entrySet()) {
            assertTrue(t.force(e.getKey(), e.getValue()[0], e.getValue()[1]));
            t.sit(e.getKey(), 0);
        }
        return t;
    }

    private static void shove(Table t, Log log, long now) {
        int steps = 0;
        while (t.phase() == Table.Phase.PLAYING) {
            UUID u = t.turn();
            java.util.Set<Betting.Action> l = t.legal(u);
            Table.Move m = l.contains(Betting.Action.HALF) ? Table.Move.HALF : l.contains(Betting.Action.CALL) ? Table.Move.CALL : Table.Move.CHECK;
            assertNull(t.act(u, m, now, log));
            assertTrue(++steps < 200);
        }
    }

    @Test
    void sutdaSidePotsWithThreeAllIns() {
        // A has the best hand but the fewest chips; B second best, C third, D the worst and the most chips
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        Map<UUID, Card[]> cards = new java.util.LinkedHashMap<>();
        cards.put(a, new Card[]{Card.parse("3광"), Card.parse("8광")});     // 38광땡
        cards.put(b, new Card[]{Card.parse("10열끗"), Card.parse("10띠")});  // 장땡
        cards.put(c, new Card[]{Card.parse("1띠"), Card.parse("2띠")});     // 알리
        cards.put(d, new Card[]{Card.parse("5열끗"), Card.parse("6띠")});   // 1끗
        Map<UUID, Long> chips = Map.of(a, 50L, b, 120L, c, 200L, d, 1000L);
        Table t = rigged(cards, chips, 1);
        Log log = new Log();
        t.tick(10_000, stacks(chips), log);
        assertEquals(Table.Phase.PLAYING, t.phase());
        for (UUID u : List.of(a, b, c, d)) assertEquals(chips.get(u), t.committed(u), "the whole stake is held by the hand");
        shove(t, log, 10_000);
        Table.Result r = log.results.getFirst();
        assertFalse(r.uncontested());
        assertEquals(50, r.put().get(a));
        assertEquals(120, r.put().get(b));
        assertEquals(200, r.put().get(c));
        assertEquals(200, r.put().get(d), "D's chips nobody matched went back");
        assertEquals(570, r.pot());
        assertEquals(3, r.pots().size());
        // main 4 × 50 = 200 → A; side 1: 3 × 70 = 210 → B; side 2: 2 × 80 = 160 → C (D loses everything it put in)
        assertEquals(new Table.PotResult(200, List.of(a), 190, 10), r.pots().get(0));
        assertEquals(new Table.PotResult(210, List.of(b), 200, 10), r.pots().get(1));
        assertEquals(new Table.PotResult(160, List.of(c), 152, 8), r.pots().get(2));
        assertEquals(190 - 50, r.net(a));
        assertEquals(200 - 120, r.net(b));
        assertEquals(152 - 200, r.net(c));
        assertEquals(-200, r.net(d));
        assertEquals(List.of(a, b, c), r.winners());
        for (UUID u : List.of(a, b, c, d)) assertTrue(chips.get(u) + r.net(u) >= 0, "nobody below 0");
    }

    @Test
    void sutdaSidePotTiesSplit() {
        // A and B hold the same 9땡 rank? No: two 땡 of the same month cannot both be dealt. Equal 끗 split instead.
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Map<UUID, Card[]> cards = new java.util.LinkedHashMap<>();
        cards.put(a, new Card[]{Card.parse("1광"), Card.parse("7열끗")});   // 8끗
        cards.put(b, new Card[]{Card.parse("2열끗"), Card.parse("6띠")});   // 8끗
        cards.put(c, new Card[]{Card.parse("3띠"), Card.parse("4띠")});     // 7끗
        Map<UUID, Long> chips = Map.of(a, 40L, b, 500L, c, 500L);
        Table t = rigged(cards, chips, 2);
        Log log = new Log();
        t.tick(10_000, stacks(chips), log);
        shove(t, log, 10_000);
        Table.Result r = log.results.getFirst();
        assertEquals(2, r.pots().size());
        Table.PotResult main = r.pots().get(0), side = r.pots().get(1);
        assertEquals(120, main.amount());
        assertEquals(java.util.Set.of(a, b), java.util.Set.copyOf(main.winners()), "the main pot splits between the two 8끗");
        assertEquals(HwatuRules.split(120, 5, 2), new HwatuRules.Split(main.share(), main.burned()));
        assertEquals(List.of(b), side.winners(), "A put in only 40: the side pot is B's against C");
        assertEquals(920, side.amount());
        assertEquals(main.share() + side.share(), r.payout().get(b));
        assertEquals(main.share(), r.payout().get(a));
        assertEquals(main.burned() + side.burned(), r.burned());
    }

    @Test
    void sutdaAllInCannotBeOutbetByLeaving() {
        // A goes all-in for 30 (its ante + 20); C raised past it, then C and B leave: A takes the pot
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Table t = table(Table.Game.SUTDA, 1, 9);
        for (UUID u : List.of(a, b, c)) t.sit(u, 0);
        Map<UUID, Long> chips = Map.of(a, 30L, b, 1000L, c, 1000L);
        Log log = new Log();
        t.tick(10_000, stacks(chips), log);
        assertEquals(List.of(a, b, c), t.players());
        assertNull(t.act(a, Table.Move.HALF, 10_000, log));      // 15
        assertNull(t.act(b, Table.Move.CALL, 10_000, log));
        assertNull(t.act(c, Table.Move.DDADANG, 10_000, log));   // 30
        assertEquals(java.util.Set.of(Betting.Action.DIE, Betting.Action.CALL), t.legal(a), "5 left against 15 owed: only 콜 (all-in) or 다이");
        assertNull(t.act(a, Table.Move.CALL, 10_000, log));
        assertTrue(t.lastBet().allIn());
        assertTrue(t.allIn(a));
        assertEquals(0, t.left(a));
        assertEquals(b, t.turn());
        t.leave(c, 11_000, log);
        assertTrue(log.results.isEmpty(), "B still owes: the round goes on");
        t.leave(b, 11_000, log);
        Table.Result r = log.results.getFirst();
        assertTrue(r.uncontested());
        assertEquals(List.of(a), r.winners());
        assertEquals(30, r.put().get(c), "C's 10 nobody matched went back");
        assertEquals(85, r.pot());
        assertEquals(85 - 4, r.payout().get(a), "5% of 85 rounded down");
        assertEquals(51, r.net(a));
    }

    @Test
    void aLongRaiseWarAtTheTableStopsAtTheHandLimit() {
        // two deep stacks 따당 each other: the bet doubles until the 한도 (일반방: 100 antes = 1,000 chips a hand), then only 콜
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Table t = table(Table.Game.SUTDA, 1, 12);
        t.sit(a, 0);
        t.sit(b, 0);
        Map<UUID, Long> chips = Map.of(a, 100_000L, b, 70_000L);
        Log log = new Log();
        t.tick(10_000, stacks(chips), log);
        assertNull(t.act(t.turn(), Table.Move.PING, 10_000, log));
        int raises = 1, steps = 0;
        while (t.phase() == Table.Phase.PLAYING && t.replays() == 0) {
            UUID u = t.turn();
            boolean d = t.legal(u).contains(Betting.Action.DDADANG);
            assertNull(t.act(u, d ? Table.Move.DDADANG : Table.Move.CALL, 10_000, log));
            if (d) raises++;
            assertTrue(++steps < 100);
        }
        assertTrue(raises >= 6, "raised until the limit: " + raises);
        // both at the limit: the second street (and a 구사 재경기) has nothing left to bet, straight to the showdown
        assertEquals(1, log.results.size());
        Table.Result r = log.results.getFirst();
        assertEquals(1000, r.put().get(a));
        assertEquals(1000, r.put().get(b), "nobody puts more than the 한도 into one hand, however deep");
        assertEquals(2000, r.pot());
    }

    @Test
    void theHandLimitWithAnAllInAndSidePots() {
        // 초보방 (ante 1, 한도 100): a and b 따당 up to the limit, c (60 chips) goes all-in short of it
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Table t = table(Table.Game.SUTDA, 0, 31);
        for (UUID u : List.of(a, b, c)) t.sit(u, 0);
        Map<UUID, Long> chips = Map.of(a, 5_000L, b, 5_000L, c, 60L);
        Log log = new Log();
        List<Table.Bet> bets = new ArrayList<>();
        Table.Listener l = new Table.Listener() {
            public void changed(Table x) {}
            public void kicked(Table x, UUID p, String r) {}
            public void finished(Table x, Table.Result r) { log.finished(x, r); }
            public void replay(Table x) { log.replay(x); }
            public void bet(Table x, Table.Bet bet) { bets.add(bet); }
        };
        t.tick(10_000, stacks(chips), l);
        assertEquals(List.of(a, b, c), t.players());
        assertEquals(100, t.handLimit());
        assertEquals(3, t.pot());
        assertNull(t.act(a, Table.Move.PING, 10_000, l));          // 1 (put 2)
        assertNull(t.act(b, Table.Move.HALF, 10_000, l));          // call 1 + half of 5 = 3 (put 4)
        assertNull(t.act(c, Table.Move.CALL, 10_000, l));          // 3 (put 4)
        int steps = 0;
        while (t.phase() == Table.Phase.PLAYING) {
            UUID u = t.turn();
            java.util.Set<Betting.Action> legal = t.legal(u);
            assertNull(t.act(u, legal.contains(Betting.Action.DDADANG) ? Table.Move.DDADANG : Table.Move.CALL, 10_000, l));
            for (UUID x : List.of(a, b, c)) assertTrue(t.phase() != Table.Phase.PLAYING || t.put(x) <= 100, "never past the limit");
            assertTrue(++steps < 50);
        }
        assertTrue(bets.stream().anyMatch(Table.Bet::limit), "a bet reached the 한도");
        assertTrue(bets.stream().anyMatch(x -> x.allIn() && x.player().equals(c)), "c's all-in");
        assertTrue(bets.stream().noneMatch(x -> x.limit() && x.allIn()), "올인 and 한도 never both");
        // a and b at the limit, c all-in: nothing more to bet, the second card and the showdown follow by themselves
        Table.Result r = log.results.getFirst();
        assertEquals(100, r.put().get(a));
        assertEquals(100, r.put().get(b));
        assertEquals(60, r.put().get(c), "c went all-in short of the limit");
        assertEquals(260, r.pot());
        assertEquals(2, r.pots().size(), "the main pot (3 × 60) and the side pot (a and b's 40 more each)");
        assertEquals(180, r.pots().get(0).amount());
        assertEquals(3, r.players().size());
        assertEquals(80, r.pots().get(1).amount());
        assertFalse(r.pots().get(1).winners().contains(c), "c cannot win the side pot");
    }

    @Test
    void theHandLimitCountsBothStreets() {
        // 일반방 (ante 10, 한도 1,000): 삥 · 콜 on the first street (20 each), then a 따당 war on the second until a 따당
        // asks for more than the limit leaves: it is cut to the limit (한도), and the other can only 콜 or 다이
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Table t = table(Table.Game.SUTDA, 1, 40);
        t.sit(a, 0);
        t.sit(b, 0);
        Log log = new Log();
        t.tick(10_000, stacks(Map.of(a, 50_000L, b, 50_000L)), log);
        assertEquals(List.of(a, b), t.players());
        assertEquals(1000, t.handLimit());
        assertNull(t.act(a, Table.Move.PING, 10_000, log));
        assertNull(t.act(b, Table.Move.CALL, 10_000, log));
        assertEquals(2, t.street());
        assertEquals(20, t.put(a));
        assertNull(t.act(a, Table.Move.PING, 10_000, log));
        int steps = 0;
        UUID u = t.turn();
        while (!t.betting().limitBy(t.players().indexOf(u), Betting.Action.DDADANG)) {
            assertNull(t.act(u, Table.Move.DDADANG, 10_000, log));
            assertFalse(t.lastBet().limit());
            u = t.turn();
            assertTrue(++steps < 20);
        }
        int seat = t.players().indexOf(u);
        Betting bet = t.betting();
        assertEquals(b, u);
        assertEquals(340, t.put(b));
        assertEquals(660, bet.amount(seat, Betting.Action.DDADANG), "따당 would be +960: cut to the 660 the limit leaves");
        assertTrue(bet.limitBy(seat, Betting.Action.HALF), "하프 too");
        assertFalse(bet.allInBy(seat, Betting.Action.DDADANG));
        assertFalse(t.limitReached());
        assertNull(t.act(b, Table.Move.DDADANG, 10_000, log));
        assertEquals(1000, t.put(b));
        assertTrue(t.lastBet().limit(), "the chat and the sign say 한도");
        assertTrue(t.atLimit(b));
        assertTrue(t.limitReached());
        assertTrue(t.betting().capped());
        assertEquals(java.util.Set.of(Betting.Action.DIE, Betting.Action.CALL), t.legal(a), "at the limit: only 콜 · 다이");
        assertEquals(340, t.betting().toCall(0));
        assertNull(t.act(a, Table.Move.CALL, 10_000, log));
        // a 구사 재경기 would keep the same pot: both are at the limit, so nothing more is bet either way
        assertEquals(1, log.results.size());
        Table.Result r = log.results.getFirst();
        assertEquals(1000, r.put().get(a));
        assertEquals(1000, r.put().get(b));
    }

    @Test
    void theReplayCannotPassTheLimit() {
        // a 구사 재경기 bets on top of what the first deal put in: the limit is for the whole hand
        int seen = 0;
        for (long seed = 0; seed < 4000 && seen < 3; seed++) {
            Table t = table(Table.Game.SUTDA, 0, seed);   // 초보방: ante 1, 한도 100
            Log log = new Log();
            UUID a = UUID.randomUUID(), b = UUID.randomUUID();
            t.sit(a, 0);
            t.sit(b, 0);
            t.tick(10_000, stacks(Map.of(a, 1000L, b, 1000L)), log);
            // street 1: 삥 · 콜 (put 2 each), street 2: 체크 · 체크
            assertNull(t.act(t.turn(), Table.Move.PING, 10_000, log));
            assertNull(t.act(t.turn(), Table.Move.CALL, 10_000, log));
            assertNull(t.act(t.turn(), Table.Move.CHECK, 10_000, log));
            assertNull(t.act(t.turn(), Table.Move.CHECK, 10_000, log));
            if (log.replays == 0) continue;
            seen++;
            assertEquals(4, t.pot());
            int steps = 0;
            while (t.phase() == Table.Phase.PLAYING) {
                UUID u = t.turn();
                java.util.Set<Betting.Action> l = t.legal(u);
                assertNull(t.act(u, l.contains(Betting.Action.DDADANG) ? Table.Move.DDADANG : l.contains(Betting.Action.PING) ? Table.Move.PING : Table.Move.CALL, 10_000, log));
                assertTrue(t.phase() != Table.Phase.PLAYING || t.put(a) <= 100 && t.put(b) <= 100);
                assertTrue(++steps < 100);
            }
            Table.Result r = log.results.getFirst();
            assertEquals(100, r.put().get(a), "the first deal's 2 and the replay's 98: 100 in all");
            assertEquals(100, r.put().get(b));
        }
        assertTrue(seen > 0, "a 구사 replay happens in 4,000 deals");
    }

    @Test
    void sutdaChecksAroundByTheTimer() {
        Table t = table(Table.Game.SUTDA, 1, 6);
        Log log = new Log();
        t.sit(UUID.randomUUID(), 0);
        t.sit(UUID.randomUUID(), 0);
        t.tick(10_000, ALL, log);
        t.tick(30_000, ALL, log);   // nothing to match: 체크
        t.tick(50_000, ALL, log);
        assertTrue(log.results.isEmpty());
        assertEquals(2, t.street());
        t.tick(70_000, ALL, log);
        t.tick(90_000, ALL, log);
        if (log.replays == 0) {
            assertEquals(1, log.results.size());
            assertFalse(log.results.getFirst().uncontested());
            assertEquals(20, log.results.getFirst().pot());
        }
    }

    @Test
    void twoStreetsAlwaysHappenUnlessAllButOneFold() {
        Random r = new Random(5);
        int folded = 0, shown = 0;
        for (long seed = 0; seed < 300; seed++) {
            Table t = table(Table.Game.SUTDA, 1, seed);
            Log log = new Log();
            int n = 2 + (int) (seed % 5);
            for (int k = 0; k < n; k++) t.sit(UUID.randomUUID(), 0);
            t.tick(10_000, ALL, log);
            int lastStreet = 1, steps = 0;
            boolean sawSecond = false;
            while (t.phase() == Table.Phase.PLAYING) {
                UUID u = t.turn();
                assertNotNull(u);
                assertEquals(t.street(), t.cards(u).length, "as many cards as streets");
                if (t.street() != lastStreet) {
                    assertTrue(t.street() == 2 || t.replays() > 0);
                    if (t.street() == 2) {
                        sawSecond = true;
                        assertEquals(0, t.betting().bet(), "each street's betting starts afresh");
                        for (int i = 0; i < t.players().size(); i++) assertEquals(0, t.betting().put(i));
                    }
                    lastStreet = t.street();
                }
                java.util.Set<Betting.Action> l = t.legal(u);
                List<Betting.Action> opts = new ArrayList<>(l);
                Betting.Action a = opts.get(r.nextInt(opts.size()));
                if (a == Betting.Action.DIE && r.nextInt(3) > 0) a = l.contains(Betting.Action.CHECK) ? Betting.Action.CHECK : Betting.Action.CALL;
                assertNull(t.act(u, Table.move(a), 10_000, log));
                assertTrue(++steps < 500);
            }
            Table.Result res = log.results.getFirst();
            if (res.uncontested()) folded++;
            else {
                shown++;
                assertTrue(sawSecond || t.street() == 2, "a showdown always comes after the second street");
                for (UUID u : res.players()) if (!"die".equals(res.detail().get(u))) assertEquals(2, t.cards(u).length);
            }
        }
        assertTrue(folded > 0 && shown > 0, folded + " / " + shown);
    }

    @Test
    void sidePotsAcrossStreets() {
        // A short: calls on the first street, all-in on the second; B and C keep betting past it
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Map<UUID, Card[]> cards = new java.util.LinkedHashMap<>();
        cards.put(a, new Card[]{Card.parse("3광"), Card.parse("8광")});     // 38광땡
        cards.put(b, new Card[]{Card.parse("10열끗"), Card.parse("10띠")});  // 장땡
        cards.put(c, new Card[]{Card.parse("5열끗"), Card.parse("6띠")});   // 1끗
        Map<UUID, Long> chips = Map.of(a, 40L, b, 1000L, c, 1000L);
        Table t = rigged(cards, chips, 3);
        Log log = new Log();
        long now = 10_000;
        t.tick(now, stacks(chips), log);
        assertEquals(List.of(a, b, c), t.players());
        assertNull(t.act(a, Table.Move.CHECK, now, log));
        assertNull(t.act(b, Table.Move.PING, now, log));    // 10
        assertNull(t.act(c, Table.Move.CALL, now, log));
        assertNull(t.act(a, Table.Move.CALL, now, log));
        assertEquals(2, t.street());
        assertEquals(60, t.pot());
        assertEquals(20, t.left(a));
        assertNull(t.act(a, Table.Move.HALF, now, log));    // all it has: 20
        assertTrue(t.allIn(a));
        assertNull(t.act(b, Table.Move.DDADANG, now, log)); // 40
        assertNull(t.act(c, Table.Move.CALL, now, log));
        Table.Result r = log.results.getFirst();
        assertEquals(40, r.put().get(a));
        assertEquals(60, r.put().get(b));
        assertEquals(60, r.put().get(c));
        assertEquals(2, r.pots().size());
        assertEquals(List.of(a), r.pots().get(0).winners(), "the main pot: 3 × 40, A's 38광땡");
        assertEquals(120, r.pots().get(0).amount());
        assertEquals(List.of(b), r.pots().get(1).winners(), "the side pot: B and C's 20 more each, B's 장땡");
        assertEquals(40, r.pots().get(1).amount());
    }

    @Test
    void allInOnTheAnteStillDealsBothStreets() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Map<UUID, Long> chips = Map.of(a, 10L, b, 10L);
        Table t = table(Table.Game.SUTDA, 1, 8);
        t.sit(a, 0);
        t.sit(b, 0);
        Log log = new Log();
        t.tick(10_000, stacks(chips), log);
        if (log.replays > 0) return;
        Table.Result r = log.results.getFirst();
        assertFalse(r.uncontested());
        for (UUID u : List.of(a, b)) assertEquals(2, t.cards(u).length, "both cards dealt before the showdown");
    }

    @Test
    void gusaReplaysWithTheSamePotOnce() {
        int seen = 0;
        for (long seed = 0; seed < 4000 && seen < 5; seed++) {
            Table t = table(Table.Game.SUTDA, 1, seed);
            Log log = new Log();
            for (int k = 0; k < 4; k++) t.sit(UUID.randomUUID(), 0);
            long now = 10_000;
            t.tick(now, ALL, log);
            for (int k = 0; k < 8; k++) assertNull(t.act(t.turn(), Table.Move.CHECK, now, log));
            if (log.replays == 0) {
                assertEquals(1, log.results.size());
                continue;
            }
            seen++;
            assertEquals(Table.Phase.PLAYING, t.phase());
            assertEquals(40, t.pot(), "no new ante");
            assertEquals(1, t.replays());
            assertEquals(1, t.street(), "dealt anew: the first street again");
            for (UUID p : t.players()) assertEquals(1, t.cards(p).length, "dealt again, one card");
            for (int k = 0; k < 8; k++) assertNull(t.act(t.turn(), Table.Move.CHECK, now, log), "체크 again in the 재경기, both streets");
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
        // what a bet says (HallTable.act: chat, the sign, the board, over the seat)
        for (Betting.Action a : Betting.Action.values()) if (!m.isString("hwatu.act." + a.key())) missing.add("act." + a.key());
        // 올인 · 한도 (chat · the sign · the buttons · the action bar) and the menu's rooms
        for (String k : List.of("act.allin", "act.limit", "btn-allin", "btn-limit", "sign-limit", "sign-limit-reached", "line.allin", "line.limit",
                "line.to-call", "line.to-call-limit", "room-ante"))
            if (!m.isString("hwatu." + k)) missing.add(k);
        assertFalse(m.contains("hwatu.btn.raise"), "레이즈 is retired");
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }
}
