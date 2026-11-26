package dev.stsv.economy.hwatu;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 바카라 (README "도박장" 바카라): card values, naturals, the whole third-card tableau, dealt coups, payouts and the banker's
 * commission, the round at a table (bets, 베팅 끝, the deal, the pay), the daily wager cap, the road, hwatu.yml baccarat.
 */
class BaccaratTest {

    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    static PCard c(String s) {
        return PCard.parse(s);
    }

    @Test
    void theSettings() {
        HwatuSpec s = HwatuTest.spec();
        HwatuSpec.BaccaratSpec b = s.baccarat();
        assertEquals(10, b.minBet());
        assertEquals(1000, b.maxBet());
        assertEquals(5000, b.dailyCap());
        assertEquals(List.of(10L, 50L, 100L, 500L), b.chips());
        assertEquals(15, b.betSeconds());
        assertEquals(5, b.commission());
        assertEquals(8, b.tiePays());
        assertEquals(12, b.road());
        assertEquals(10, s.need(Table.Game.BACCARAT, s.rooms().get(2)), "sit with any chips ≥ the least bet, whatever the room");
        assertEquals(1, Table.Game.BACCARAT.least(), "alone against the house");
        assertFalse(Table.Game.BACCARAT.bets());
        // a bad section is reported and falls back
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        y.set("min-bet", 0);
        y.set("max-bet", -1);
        y.set("chips", List.of(5, 50, 20, 100, 500));
        List<String> problems = new ArrayList<>();
        HwatuSpec.BaccaratSpec bad = HwatuSpec.BaccaratSpec.parse(y, problems);
        String all = String.join("\n", problems);
        for (String want : List.of("min-bet", "max-bet", "chips: 1~4개", "커지는 순서")) assertTrue(all.contains(want), want + " in " + all);
        assertTrue(bad.minBet() >= 1 && bad.maxBet() >= bad.minBet() && !bad.chips().isEmpty());
    }

    // ------------------------------------------------------------------------------------------ the rules

    @Test
    void valuesAndTotals() {
        assertEquals(1, c("as").baccarat());
        assertEquals(9, c("9d").baccarat());
        for (String s : List.of("10h", "jh", "qc", "ks")) assertEquals(0, c(s).baccarat());
        assertEquals(5, Baccarat.total(List.of(c("7s"), c("8d"))), "7 + 8 = 15 → 5");
        assertEquals(0, Baccarat.total(List.of(c("ks"), c("10d"))));
        assertEquals(9, Baccarat.total(List.of(c("4s"), c("5d"))));
        assertEquals(3, Baccarat.total(List.of(c("9s"), c("9d"), c("5h"))));
        assertTrue(Baccarat.natural(8, 0));
        assertTrue(Baccarat.natural(0, 9));
        assertFalse(Baccarat.natural(7, 7));
        for (int p = 0; p <= 9; p++) assertEquals(p <= 5, Baccarat.playerDraws(p), "player " + p);
    }

    /** The whole banker tableau: rows = the banker's two-card total 0 ~ 7, columns = the player's third card (- stood, 0 ~ 9). */
    @Test
    void theBankersThirdCardTable() {
        String[] table = {
                //   -  0123456789
                "0: D DDDDDDDDDD",
                "1: D DDDDDDDDDD",
                "2: D DDDDDDDDDD",
                "3: D DDDDDDDDSD",
                "4: D SSDDDDDDSS",
                "5: D SSSSDDDDSS",
                "6: S SSSSSSDDSS",
                "7: S SSSSSSSSSS",
        };
        for (String row : table) {
            int banker = row.charAt(0) - '0';
            assertEquals(row.charAt(3) == 'D', Baccarat.bankerDraws(banker, -1), "banker " + banker + ", player stood");
            for (int p3 = 0; p3 <= 9; p3++)
                assertEquals(row.charAt(5 + p3) == 'D', Baccarat.bankerDraws(banker, p3), "banker " + banker + ", player's third " + p3);
        }
    }

    private static Baccarat.Coup deal(String... cards) {
        Baccarat.Shoe shoe = new Baccarat.Shoe(new Random(1));
        List<PCard> next = new ArrayList<>();
        for (String s : cards) next.add(c(s));
        shoe.stack(next);
        return Baccarat.deal(shoe);
    }

    @Test
    void dealtCoups() {
        // P B P B order; a natural stops both
        Baccarat.Coup natural = deal("4s", "7d", "5h", "kc", "9s", "9d");
        assertEquals(List.of(c("4s"), c("5h")), natural.player());
        assertEquals(2, natural.banker().size());
        assertEquals(9, natural.playerTotal());
        assertTrue(natural.natural());
        assertEquals(Baccarat.Spot.PLAYER, natural.winner());
        // player 6 stands; banker 5 draws (player stood: 0 ~ 5 draw)
        Baccarat.Coup stood = deal("3s", "2d", "3h", "3c", "4s");
        assertEquals(2, stood.player().size());
        assertEquals(3, stood.banker().size());
        assertEquals(9, stood.bankerTotal());
        assertEquals(Baccarat.Spot.BANKER, stood.winner());
        // player 3 draws an 8; banker 3 stands on a player's 8
        Baccarat.Coup eight = deal("as", "ad", "2h", "2c", "8s", "9d");
        assertEquals(3, eight.player().size());
        assertEquals(2, eight.banker().size(), "banker 3 stands on a third 8");
        assertEquals(1, eight.playerTotal());
        assertEquals(3, eight.bankerTotal());
        // banker 6 draws on a player's third 6 or 7
        Baccarat.Coup six = deal("2s", "3d", "2h", "3c", "7s", "kd");
        assertEquals(3, six.player().size());
        assertEquals(3, six.banker().size());
        assertEquals(Baccarat.Spot.TIE, deal("ks", "kd", "qh", "qc", "10s", "10d").winner(), "0 : 0 after both drew");
        // the order the dealer deals them: P1 B1 P2 B2 P3 B3
        assertEquals(List.of(1, 0, 1, 0, 1, 0), six.order().stream().map(o -> o[0]).toList());
        assertEquals(List.of(0, 0, 1, 1, 2, 2), six.order().stream().map(o -> o[1]).toList());
    }

    @Test
    void theShoeIsEightDecksAndReshufflesAtTheCut() {
        Baccarat.Shoe shoe = new Baccarat.Shoe(new Random(3));
        assertEquals(8 * 52, shoe.left());
        assertFalse(shoe.ready());
        while (shoe.left() >= Baccarat.CUT) shoe.draw();
        assertTrue(shoe.ready(), "the cut card came: a new shoe");
        assertEquals(8 * 52, shoe.left());
        assertEquals(2, shoe.shuffles());
        // many coups: every one follows the tableau
        for (int i = 0; i < 5000; i++) {
            shoe.ready();
            Baccarat.Coup k = Baccarat.deal(shoe);
            int p2 = Baccarat.total(k.player().subList(0, 2)), b2 = Baccarat.total(k.banker().subList(0, 2));
            if (Baccarat.natural(p2, b2)) {
                assertEquals(2, k.player().size());
                assertEquals(2, k.banker().size());
                continue;
            }
            assertEquals(Baccarat.playerDraws(p2) ? 3 : 2, k.player().size());
            int third = k.player().size() == 3 ? k.player().get(2).baccarat() : -1;
            assertEquals(Baccarat.bankerDraws(b2, third) ? 3 : 2, k.banker().size());
        }
    }

    @Test
    void payoutsAndTheCommission() {
        assertEquals(200, Baccarat.payout(Baccarat.Spot.PLAYER, 100, Baccarat.Spot.PLAYER, 5, 8), "player 1:1");
        assertEquals(0, Baccarat.payout(Baccarat.Spot.PLAYER, 100, Baccarat.Spot.BANKER, 5, 8));
        assertEquals(195, Baccarat.payout(Baccarat.Spot.BANKER, 100, Baccarat.Spot.BANKER, 5, 8), "banker 0.95:1");
        assertEquals(19, Baccarat.payout(Baccarat.Spot.BANKER, 10, Baccarat.Spot.BANKER, 5, 8), "9.5 won → 9, down");
        assertEquals(29, Baccarat.payout(Baccarat.Spot.BANKER, 15, Baccarat.Spot.BANKER, 5, 8), "14.25 → 14");
        assertEquals(97, Baccarat.payout(Baccarat.Spot.BANKER, 50, Baccarat.Spot.BANKER, 5, 8), "47.5 → 47");
        assertEquals(975, Baccarat.payout(Baccarat.Spot.BANKER, 500, Baccarat.Spot.BANKER, 5, 8));
        assertEquals(90, Baccarat.payout(Baccarat.Spot.TIE, 10, Baccarat.Spot.TIE, 5, 8), "tie 8:1");
        assertEquals(0, Baccarat.payout(Baccarat.Spot.TIE, 10, Baccarat.Spot.PLAYER, 5, 8));
        assertEquals(100, Baccarat.payout(Baccarat.Spot.PLAYER, 100, Baccarat.Spot.TIE, 5, 8), "player · banker push on a tie");
        assertEquals(100, Baccarat.payout(Baccarat.Spot.BANKER, 100, Baccarat.Spot.TIE, 5, 8));
        assertEquals(0, Baccarat.payout(Baccarat.Spot.BANKER, 0, Baccarat.Spot.BANKER, 5, 8));
        for (long bet = 10; bet <= 1000; bet += 10) {
            long won = Baccarat.payout(Baccarat.Spot.BANKER, bet, Baccarat.Spot.BANKER, 5, 8) - bet;
            assertTrue(won * 100 <= bet * 95 && (won + 1) * 100 > bet * 95, "the winnings are 95% rounded down: " + bet);
        }
    }

    @Test
    void theRoadKeepsTheLastResults() {
        String r = "";
        for (int i = 0; i < 20; i++) r = Baccarat.road(r, i % 5 == 0 ? Baccarat.Spot.TIE : i % 2 == 0 ? Baccarat.Spot.BANKER : Baccarat.Spot.PLAYER, 12);
        assertEquals(12, r.length());
        assertTrue(r.matches("[PBT]+"));
        assertEquals('P', r.charAt(r.length() - 1), "the newest last");
        assertEquals(Baccarat.Spot.TIE, Baccarat.Spot.of('T'));
        assertEquals(30, TableScene.betOf("P30,B0,T10", Baccarat.Spot.PLAYER));
        assertEquals(10, TableScene.betOf("P30,B0,T10", Baccarat.Spot.TIE));
        assertEquals(0, TableScene.betOf(null, Baccarat.Spot.TIE));
    }

    // ------------------------------------------------------------------------------------------ at a table

    /** A bank: chips per player, what each bet today before. */
    static final class House implements Table.Bank {
        final Map<UUID, Long> chips = new HashMap<>(), today = new HashMap<>();

        public String refusal(UUID p, long need) {
            return chips.getOrDefault(p, 0L) < need ? "hwatu.need-chips" : null;
        }

        public long stack(UUID p) {
            return chips.getOrDefault(p, 0L);
        }

        public long wagered(UUID p) {
            return today.getOrDefault(p, 0L);
        }

        /** The round paid: chips move, today's bets grow. */
        void settle(Table.Result r) {
            for (UUID u : r.players()) {
                chips.merge(u, r.net(u), Long::sum);
                today.merge(u, r.put().get(u), Long::sum);
            }
        }
    }

    static final class Seen implements Table.Listener {
        final List<Table.Result> results = new ArrayList<>();
        int opened, dealt;

        public void changed(Table t) {}

        public void kicked(Table t, UUID p, String r) {}

        public void finished(Table t, Table.Result r) {
            results.add(r);
            assertEquals(0, r.burned(), "no 자릿세 at the house's game");
            assertTrue(r.pots().isEmpty());
            assertNotNull(r.coup());
            long put = 0;
            for (UUID u : r.players()) put += r.put().get(u);
            assertEquals(r.pot(), put);
            HwatuSpec.BaccaratSpec b = t == null ? null : HwatuTest.spec().baccarat();
            for (UUID u : r.players()) {
                long back = 0;
                for (Baccarat.Spot s : Baccarat.Spot.values())
                    back += Baccarat.payout(s, TableScene.betOf(r.detail().get(u), s), r.coup().winner(), b.commission(), b.tiePays());
                assertEquals(back, (long) r.payout().getOrDefault(u, 0L), "the house pays every bet by the coup");
            }
        }

        public void replay(Table t) {}

        public void dealt(Table t) {
            dealt++;
        }

        public void betsOpen(Table t) {
            opened++;
        }
    }

    private static Table table(long seed) {
        HwatuSpec s = HwatuTest.spec();
        return new Table(1, Table.Game.BACCARAT, s.rooms().get(0), s, new Random(seed));
    }

    @Test
    void aRoundAloneFromTheBetToThePay() {
        Table t = table(1);
        House h = new House();
        Seen l = new Seen();
        h.chips.put(A, 1000L);
        long now = 0;
        assertTrue(t.sit(A, now));
        t.tick(now, h, l);
        assertEquals(Table.Phase.PLAYING, t.phase(), "alone: the bets open at once");
        assertEquals(Table.Step.BETS, t.step());
        assertEquals(1, l.opened);
        assertNull(t.turn());
        assertEquals(List.of(Controls.Button.CHIP1, Controls.Button.CHIP2, Controls.Button.CHIP3, Controls.Button.CHIP4, Controls.Button.BAC_PLAYER,
                Controls.Button.BAC_BANKER, Controls.Button.BAC_TIE, Controls.Button.BAC_DONE), Controls.buttons(Controls.state(t, A, true, false)));
        assertEquals("hwatu.bac.min", t.wager(A, Baccarat.Spot.PLAYER, 5, now, h, l));
        assertNull(t.wager(A, Baccarat.Spot.PLAYER, 100, now, h, l));
        assertNull(t.wager(A, Baccarat.Spot.PLAYER, 50, now, h, l));
        assertNull(t.wager(A, Baccarat.Spot.TIE, 10, now, h, l));
        assertEquals(160, t.put(A));
        assertEquals(1000, t.committed(A), "the chips held when the bets open are held until the pay");
        assertEquals(List.of(150L, 0L, 10L), java.util.Arrays.stream(t.bets(A)).boxed().toList());
        assertEquals(Table.Step.BETS, t.step());
        assertNull(t.act(A, Table.Move.BAC_DONE, now, l));
        assertEquals(Table.Step.DEAL, t.step(), "everyone seated pressed 베팅 끝: no more bets");
        assertEquals("hwatu.bac.closed", t.wager(A, Baccarat.Spot.BANKER, 10, now, h, l));
        assertNotNull(t.coup());
        assertEquals(List.of(), Controls.buttons(Controls.state(t, A, true, false)), "the cards are coming");
        assertEquals(Table.dealMillis(t.coup()), t.deadline() - now);
        now = t.deadline();
        t.tick(now, h, l);
        assertEquals(Table.Phase.RESULT, t.phase());
        Table.Result r = l.results.getFirst();
        h.settle(r);
        Baccarat.Spot won = r.coup().winner();
        long expect = switch (won) {
            case PLAYER -> 300;
            case BANKER -> 0;
            case TIE -> 150 + 90;
        };
        assertEquals(expect, (long) r.payout().getOrDefault(A, 0L));
        assertEquals(String.valueOf(won.letter()), t.road());
        assertEquals(List.of(Controls.Button.LEAVE), Controls.buttons(Controls.state(t, A, true, false)));
        // the result shows, then the next window opens by itself
        now = t.deadline();
        t.tick(now, h, l);
        t.tick(now, h, l);
        assertEquals(Table.Step.BETS, t.step());
        assertEquals(2, l.opened);
        assertEquals(0, t.put(A));
    }

    @Test
    void noBetsNoDeal() {
        Table t = table(2);
        House h = new House();
        Seen l = new Seen();
        h.chips.put(A, 1000L);
        t.sit(A, 0);
        t.tick(0, h, l);
        long end = t.deadline();
        t.tick(end, h, l);
        assertEquals(Table.Step.BETS, t.step(), "nobody bet: the window opens again");
        assertEquals(2, l.opened);
        assertEquals(0, l.dealt);
        assertNull(t.act(A, Table.Move.BAC_DONE, end, l));
        assertEquals(Table.Step.BETS, t.step(), "베팅 끝 without a bet: still nothing to deal");
        assertEquals(0, l.dealt);
    }

    @Test
    void theLimitsOfABet() {
        Table t = table(3);
        House h = new House();
        Seen l = new Seen();
        h.chips.put(A, 100_000L);
        h.chips.put(B, 60L);
        t.sit(A, 0);
        t.sit(B, 0);
        t.tick(0, h, l);
        // the most a round: 1,000 chips, all spots together
        assertNull(t.wager(A, Baccarat.Spot.BANKER, 500, 0, h, l));
        assertNull(t.wager(A, Baccarat.Spot.PLAYER, 500, 0, h, l));
        assertEquals("hwatu.bac.over-max", t.wager(A, Baccarat.Spot.TIE, 10, 0, h, l));
        assertTrue(t.betsDone(A), "nothing more can go on: as good as 베팅 끝");
        // no more than they hold
        assertNull(t.wager(B, Baccarat.Spot.PLAYER, 50, 0, h, l));
        assertEquals("hwatu.bac.short", t.wager(B, Baccarat.Spot.PLAYER, 50, 0, h, l));
        assertNull(t.wager(B, Baccarat.Spot.PLAYER, 10, 0, h, l));
        assertEquals(Table.Step.DEAL, t.step(), "both can bet no more: dealt");
        // a player short of the least bet cannot sit in
        Table u = table(4);
        House poor = new House();
        poor.chips.put(A, 9L);
        u.sit(A, 0);
        u.tick(0, poor, l);
        assertEquals("hwatu.need-chips", u.wager(A, Baccarat.Spot.PLAYER, 10, 0, poor, l));
    }

    @Test
    void theDailyWagerCap() {
        House h = new House();
        Seen l = new Seen();
        h.chips.put(A, 1_000_000L);
        long now = 0;
        int rounds = 0;
        // 1,000 a round: five rounds reach 5,000, then nothing more today
        while (rounds < 10) {
            Table t = table(10 + rounds);
            t.sit(A, now);
            t.tick(now, h, l);
            String why = t.wager(A, Baccarat.Spot.BANKER, 500, now, h, l);
            if (why != null) {
                assertEquals("hwatu.bac.over-cap", why);
                break;
            }
            assertNull(t.wager(A, Baccarat.Spot.BANKER, 500, now, h, l));
            assertEquals(Table.Step.DEAL, t.step());
            t.tick(t.deadline(), h, l);
            h.settle(l.results.getLast());
            rounds++;
        }
        assertEquals(5, rounds);
        assertEquals(5000, h.wagered(A));
        // the cap is what was bet, won or lost: 4,990 bet leaves room for 10 only
        h.today.put(A, 4990L);
        Table t = table(99);
        t.sit(A, now);
        t.tick(now, h, l);
        assertEquals("hwatu.bac.over-cap", t.wager(A, Baccarat.Spot.PLAYER, 50, now, h, l));
        assertNull(t.wager(A, Baccarat.Spot.PLAYER, 10, now, h, l));
        assertEquals(0, t.betRoom(A, h.wagered(A), h.stack(A)));
        assertTrue(t.betsDone(A));
    }

    @Test
    void leavingWhileBetsAreOpenTakesThemBack() {
        Table t = table(5);
        House h = new House();
        Seen l = new Seen();
        h.chips.put(A, 1000L);
        h.chips.put(B, 1000L);
        t.sit(A, 0);
        t.sit(B, 0);
        t.tick(0, h, l);
        assertNull(t.wager(A, Baccarat.Spot.PLAYER, 100, 0, h, l));
        assertNull(t.wager(B, Baccarat.Spot.BANKER, 100, 0, h, l));
        t.leave(A, 0, l);
        assertEquals(0, t.put(A), "nothing moved yet: their bets are gone with them");
        assertFalse(t.players().contains(A));
        assertNull(t.act(B, Table.Move.BAC_DONE, 0, l));
        assertEquals(Table.Step.DEAL, t.step());
        t.leave(B, 1, l);   // once dealt, the bet stays and is paid
        t.tick(t.deadline(), h, l);
        assertEquals(List.of(B), l.results.getFirst().players());
        assertEquals(Table.Phase.RESULT, t.phase());
        t.tick(t.deadline(), h, l);
        t.tick(t.deadline(), h, l);
        assertEquals(Table.Phase.WAITING, t.phase(), "nobody seated: no window");
    }

    @Test
    void manyRoundsTheHouseKeepsItsEdge() {
        // banker bets only: the house keeps about 1.06% of what is bet (over enough rounds, never a fortune either way)
        House h = new House();
        Seen l = new Seen();
        h.chips.put(A, 10_000_000L);
        Table t = table(77);
        t.sit(A, 0);
        long now = 0;
        long bet = 0;
        for (int i = 0; i < 4000; i++) {
            t.tick(now, h, l);
            assertEquals(Table.Step.BETS, t.step());
            h.today.put(A, 0L);
            assertNull(t.wager(A, Baccarat.Spot.BANKER, 100, now, h, l));
            assertNull(t.act(A, Table.Move.BAC_DONE, now, l));
            now = t.deadline();
            t.tick(now, h, l);
            h.settle(l.results.getLast());
            bet += 100;
            now = t.deadline();
            t.tick(now, h, l);
        }
        long net = h.chips.get(A) - 10_000_000L;
        assertTrue(net < 0 && -net < bet * 4 / 100, "lost " + (-net) + " of " + bet);
        assertEquals(12, t.road().length());
    }

    // ------------------------------------------------------------------------------------------ the daily count is kept

    @Test
    void todaysWagerIsStoredAndANewDayStartsOver(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        ChipStore s = new ChipStore(db);
        s.init();
        s.wagered(A, 300, 7);
        s.wagered(A, 200, 7);
        assertEquals(500, s.account(A, 7).wagered);
        db.close();
        db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        ChipStore again = new ChipStore(db);
        again.init();
        assertEquals(500, again.account(A, 7).wagered, "kept over a restart");
        assertEquals(0, again.account(A, 8).wagered, "05시: a new day");
        again.wagered(A, 10, 8);
        assertEquals(10, again.account(A, 8).wagered);
        db.close();
    }
}
