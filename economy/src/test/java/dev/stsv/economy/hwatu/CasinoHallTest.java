package dev.stsv.economy.hwatu;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 도박장's new tables (README "도박장"): the poker and baccarat furniture, where things lie on them (세븐 포커's two
 * rows of cards, 바카라's hands · bets · dealer), the 주사위 cup and 홀짝 bowl set aside clear of the dice (the
 * 2026-11-26 fix), the buttons, the 세븐 포커 bots, the 족보 board and every new message.
 */
class CasinoHallTest {

    // ------------------------------------------------------------------------------------------ the furniture

    @Test
    void theNewTables() {
        assertEquals(TableKind.POKER, TableKind.of("economy:poker_table"));
        assertEquals(TableKind.BACCARAT, TableKind.of("economy:baccarat_table"));
        assertEquals(6, TableKind.POKER.seats());
        assertEquals(6, TableKind.BACCARAT.seats());
        assertTrue(TableKind.BACCARAT.fits(Table.Game.BACCARAT));
        assertFalse(TableKind.ROUND.fits(Table.Game.BACCARAT), "바카라 needs its dealer's table");
        assertFalse(TableKind.BACCARAT.fits(Table.Game.SUTDA));
        assertTrue(TableKind.POKER.fits(Table.Game.SUTDA));
        assertTrue(TableKind.ROUND.fits(Table.Game.POKER));
        assertEquals(TableKind.POKER, TableKind.forGame(Table.Game.POKER));
        assertEquals(TableKind.BACCARAT, TableKind.forGame(Table.Game.BACCARAT));
        assertEquals(TableKind.DUEL, TableKind.forGame(Table.Game.HOLJJAK));
        HallSpec h = HallSpec.parse(YamlConfiguration.loadConfiguration(Path.of(System.getProperty("stsv.content")).resolve("economy/hwatu.yml").toFile())
                .getConfigurationSection("hall"), new ArrayList<>());
        assertEquals(0.88, h.top(TableKind.POKER), 1e-9);
        assertEquals(0.88, h.top(TableKind.BACCARAT), 1e-9);
        assertEquals("economy:entity/bus/dealer", h.dealerSkin());
    }

    /** Six chairs 2 blocks round the middle, seat 0 in front (+z), clockwise. */
    private static List<double[]> roundSeats(int n, double r) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(i * 360.0 / n);
            out.add(new double[]{-Math.sin(a) * r, Math.cos(a) * r});
        }
        return out;
    }

    private static double dist(double ax, double az, double bx, double bz) {
        return Math.hypot(ax - bx, az - bz);
    }

    /** Distance from a point to the segment a-b. */
    private static double toSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
        double t = l2 == 0 ? 0 : Math.clamp(((px - ax) * dx + (pz - az) * dz) / l2, 0, 1);
        return dist(px, pz, ax + t * dx, az + t * dz);
    }

    // ------------------------------------------------------------------------------------------ the cup and the bowl (fix)

    /**
     * 주사위: once lifted, each cup is set down clear of every die on the table (its own player's and the neighbours'), of
     * the other cups, off the line from its player to their dice, and on the table top; at the duel crate (no room) it is
     * lifted away instead.
     */
    @Test
    void aLiftedCupNeverCoversTheDice() {
        assertEquals(TableArt.CUP_FOOT, 4 / 16.0 * TableScene.CUP_SCALE, 0.02, "the cup's footprint is its drawn size");
        assertTrue(TableArt.DIE_FOOT >= 2 / 16.0 * TableScene.DIE_SCALE * Math.sqrt(2) - 1e-9, "a die lands turned: its half diagonal");
        assertTrue(TableArt.BOWL_FOOT >= 5.5 / 16.0 * TableScene.BOWL_SCALE - 1e-9);
        for (int dice = 1; dice <= 3; dice++) {
            TableArt.Aside a = TableArt.cupAside(TableArt.ROUND_REACH, TableArt.ROUND_SIZE, dice);
            assertNotNull(a, "the round table has room beside the dice");
            List<double[]> seats = roundSeats(6, TableArt.ROUND_SIZE.seat());
            double[] mid = TableArt.center(seats);
            List<double[]> dieAt = new ArrayList<>(), cupAt = new ArrayList<>();
            for (double[] s : seats) {
                for (double side : TableArt.row(dice, TableArt.DIE_GAP)) {
                    TableArt.Spot d = TableArt.along(mid, s, TableArt.ROUND_REACH.dice(), side, true);
                    dieAt.add(new double[]{d.x(), d.z()});
                }
                TableArt.Spot c = TableArt.along(mid, s, a.reach(), a.side(), true);
                cupAt.add(new double[]{c.x(), c.z()});
            }
            for (int i = 0; i < cupAt.size(); i++) {
                double[] c = cupAt.get(i);
                for (double[] d : dieAt)
                    assertTrue(dist(c[0], c[1], d[0], d[1]) >= TableArt.CUP_FOOT + TableArt.DIE_FOOT + TableArt.CLEAR - 1e-9, "cup " + i + " clear of every die");
                for (int j = 0; j < cupAt.size(); j++)
                    if (j != i) assertTrue(dist(c[0], c[1], cupAt.get(j)[0], cupAt.get(j)[1]) >= 2 * TableArt.CUP_FOOT, "cups apart");
                assertTrue(Math.hypot(c[0], c[1]) + TableArt.CUP_FOOT <= TableArt.ROUND_SIZE.top() + 1e-9, "on the table top");
                // never between the player and their own dice
                double[] s = seats.get(i);
                for (int k = 0; k < dice; k++) {
                    double[] d = dieAt.get(i * dice + k);
                    assertTrue(toSegment(c[0], c[1], s[0], s[1], d[0], d[1]) >= TableArt.CUP_FOOT, "out of the player's sight line");
                }
            }
        }
        assertNull(TableArt.cupAside(TableArt.DUEL_REACH, TableArt.DUEL_SIZE, 2), "the duel crate: lifted away");
    }

    /** 홀짝: the bowl is put down across the line between the two players, clear of the die; the duel crate: lifted away. */
    @Test
    void aLiftedBowlNeverCoversTheDie() {
        TableArt.Aside a = TableArt.bowlAside(TableArt.ROUND_SIZE);
        assertNotNull(a);
        List<double[]> seats = roundSeats(6, 2);
        double[] mid = TableArt.center(seats);
        for (int seat0 : List.of(0, 1, 2)) {   // whichever pair of chairs face each other
            TableArt.Spot b = TableArt.along(mid, seats.get(seat0), 0, a.side(), true);
            assertTrue(dist(b.x(), b.z(), mid[0], mid[1]) >= TableArt.BOWL_FOOT + TableArt.BOWL_DIE_FOOT + TableArt.CLEAR - 1e-9, "clear of the die");
            for (int s : List.of(seat0, seat0 + 3))
                assertTrue(toSegment(b.x(), b.z(), seats.get(s)[0], seats.get(s)[1], mid[0], mid[1]) >= TableArt.BOWL_FOOT, "out of both players' sight");
            assertTrue(Math.hypot(b.x(), b.z()) + TableArt.BOWL_FOOT <= TableArt.ROUND_SIZE.top());
        }
        assertNull(TableArt.bowlAside(TableArt.DUEL_SIZE));
    }

    // ------------------------------------------------------------------------------------------ 세븐 포커's cards

    /** The poker table's chairs (items/gamble.yml poker_table: cells, seat 0 in front, clockwise) and its oval top. */
    static final List<double[]> POKER_SEATS = List.of(new double[]{0, -2}, new double[]{2, -1}, new double[]{2, 1}, new double[]{0, 2},
            new double[]{-2, 1}, new double[]{-2, -1});

    /** On the poker table's oval (models/gamble.py poker_table: a stadium 2 × 0.25 long with 1.25 round ends). */
    static boolean onOval(double x, double z) {
        double cx = Math.clamp(x, -0.25, 0.25);
        return Math.hypot(x - cx, z) <= 1.25 + 1e-9;
    }

    /** A card's corners lying at a spot (its width across the line to its seat, its depth along it). */
    static double[][] card(TableArt.Spot s, double[] seat, double[] mid) {
        double dx = seat[0] - mid[0], dz = seat[1] - mid[1], l = Math.hypot(dx, dz);
        double ux = dx / l, uz = dz / l, rx = uz, rz = -ux, hw = TableArt.PCARD_HALF_W, hd = TableArt.PCARD_HALF_D;
        double[][] out = new double[4][];
        int k = 0;
        for (int[] c : new int[][]{{-1, -1}, {1, -1}, {1, 1}, {-1, 1}})
            out[k++] = new double[]{s.x() + c[0] * hw * rx + c[1] * hd * ux, s.z() + c[0] * hw * rz + c[1] * hd * uz};
        return out;
    }

    /** Two convex quads overlap (separating axes). */
    static boolean overlap(double[][] a, double[][] b) {
        for (double[][] poly : new double[][][]{a, b}) {
            for (int i = 0; i < 4; i++) {
                double nx = poly[(i + 1) % 4][1] - poly[i][1], nz = poly[i][0] - poly[(i + 1) % 4][0];
                double amin = Double.MAX_VALUE, amax = -Double.MAX_VALUE, bmin = Double.MAX_VALUE, bmax = -Double.MAX_VALUE;
                for (double[] p : a) {
                    double v = nx * p[0] + nz * p[1];
                    amin = Math.min(amin, v);
                    amax = Math.max(amax, v);
                }
                for (double[] p : b) {
                    double v = nx * p[0] + nz * p[1];
                    bmin = Math.min(bmin, v);
                    bmax = Math.max(bmax, v);
                }
                if (amax < bmin - 1e-9 || bmax < amin - 1e-9) return false;
            }
        }
        return true;
    }

    @Test
    void pokerRowsStayApartOnThePokerTable() {
        // the slots: hidden 0 1 2 (the opened one moves to open 0, the 7th takes its hidden slot), open 1 2 3 for 4th ~ 6th
        assertEquals(List.of(0.0, 0.0), toList(TableArt.pokerSlot(0, -1)));
        assertEquals(List.of(1.0, 0.0), toList(TableArt.pokerSlot(1, 1)));
        assertEquals(List.of(1.0, 3.0), toList(TableArt.pokerSlot(5, 1)));
        assertEquals(List.of(0.0, 1.0), toList(TableArt.pokerSlot(6, 1)), "the 7th lies where the opened card was");
        for (int opened = 0; opened < 3; opened++) {
            Set<String> used = new java.util.HashSet<>();
            for (int k = 0; k < 7; k++) assertTrue(used.add(java.util.Arrays.toString(TableArt.pokerSlot(k, opened))), "no two cards in one slot");
        }
        double[] mid = TableArt.center(POKER_SEATS);
        assertEquals(0, Math.hypot(mid[0], mid[1]), 1e-9, "the chairs' middle is the table's");
        for (int opened = 0; opened < 3; opened++) {
            List<double[][]> all = new ArrayList<>();
            List<int[]> who = new ArrayList<>();
            for (int s = 0; s < 6; s++) {
                for (int k = 0; k < 7; k++) {
                    double[] slot = TableArt.pokerSlot(k, opened);
                    TableArt.Spot p = TableArt.pokerSpot(mid, POKER_SEATS.get(s), TableArt.POKER_REACH, (int) slot[0], (int) slot[1]);
                    double[][] c = card(p, POKER_SEATS.get(s), mid);
                    for (double[] corner : c) assertTrue(onOval(corner[0], corner[1]), "seat " + s + " card " + k + " on the table");
                    all.add(c);
                    who.add(new int[]{s, (int) slot[0]});
                }
            }
            for (int i = 0; i < all.size(); i++) for (int j = i + 1; j < all.size(); j++) {
                int[] a = who.get(i), b = who.get(j);
                if (a[0] != b[0]) assertFalse(overlap(all.get(i), all.get(j)), "seats " + a[0] + " · " + b[0] + " apart");
                else if (a[1] != b[1]) assertFalse(overlap(all.get(i), all.get(j)), "seat " + a[0] + ": the two rows apart");
            }
        }
        // the open row's cards overlap a little (their corners show), each one's rank corner clear of the next
        assertTrue(TableArt.POKER_OPEN_GAP < 2 * TableArt.PCARD_HALF_W && TableArt.POKER_OPEN_GAP > TableArt.PCARD_HALF_W);
        // the buttons stand past the hidden row, still over the table
        for (double[] seat : POKER_SEATS) {
            TableArt.Spot b = TableArt.along(mid, seat, TableArt.POKER_REACH.button(), 0, true);
            TableArt.Spot h = TableArt.pokerSpot(mid, seat, TableArt.POKER_REACH, 0, 1);
            assertTrue(Math.hypot(b.x(), b.z()) >= Math.hypot(h.x(), h.z()) + TableArt.PCARD_HALF_D - 0.02);
            assertTrue(onOval(b.x(), b.z()));
        }
    }

    private static List<Double> toList(double[] a) {
        List<Double> out = new ArrayList<>();
        for (double v : a) out.add(v);
        return out;
    }

    // ------------------------------------------------------------------------------------------ 바카라's table

    @Test
    void baccaratLayout() {
        // items/gamble.yml baccarat_table: six chairs round the curved side (-z), the dealer's cell (0, 2)
        List<double[]> seats = List.of(new double[]{-2, 0}, new double[]{-2, -1}, new double[]{-1, -2}, new double[]{1, -2}, new double[]{2, -1},
                new double[]{2, 0});
        double[] mid = {0, 0};
        double[] d = TableArt.toDealer(mid, seats, null);
        assertEquals(0, d[0], 1e-9);
        assertEquals(1, d[1], 1e-9, "away from the chairs: the straight edge (+z)");
        assertEquals(2.0, TableArt.DEALER_OUT, 1e-9, "the dealer's cell (0, 2)");
        assertEquals(1, Math.hypot(d[0], d[1]), 1e-9);
        double[] given = TableArt.toDealer(mid, seats, new double[]{3, 0});
        assertEquals(1, given[0], 1e-9, "a known dealer's spot wins");
        // the two hands either side of the dealer's line, towards them; the player's on the players' left
        double[] p0 = TableArt.bacCard(mid, d, true, 1), b0 = TableArt.bacCard(mid, d, false, 1);
        assertTrue(p0[1] > 0 && b0[1] > 0, "towards the dealer");
        // into the printed card boxes (pcard_art.BAC_BOX: 플레이어 (+0.5, +0.34) · 뱅커 (-0.5, +0.34), half 0.41 × 0.28)
        for (int k = 0; k < 3; k++) {
            double[] p = TableArt.bacCard(mid, d, true, k), b = TableArt.bacCard(mid, d, false, k);
            assertTrue(Math.abs(p[0] - 0.5) <= 0.41 - 0.11 && Math.abs(p[1] - 0.34) < 0.01, "player card " + k);
            assertTrue(Math.abs(b[0] + 0.5) <= 0.41 - 0.11 && Math.abs(b[1] - 0.34) < 0.01, "banker card " + k);
        }
        double[] right = TableArt.rightOf(d[0], d[1]);
        assertTrue((b0[0] - p0[0]) * right[0] + (b0[1] - p0[1]) * right[1] > 0, "뱅커 on the players' right");
        for (int k = 0; k < 3; k++) for (int j = 0; j < 3; j++) {
            double[] p = TableArt.bacCard(mid, d, true, k), b = TableArt.bacCard(mid, d, false, j);
            assertTrue(dist(p[0], p[1], b[0], b[1]) > 0.29, "the hands apart");
        }
        // each seat's three bets in a row before it, apart from the neighbours'
        List<double[]> spots = new ArrayList<>();
        for (double[] s : seats) for (Baccarat.Spot sp : Baccarat.Spot.values()) {
            TableArt.Spot x = TableArt.betSpot(mid, s, sp);
            spots.add(new double[]{x.x(), x.z()});
        }
        for (int i = 0; i < spots.size(); i++) for (int j = i + 1; j < spots.size(); j++)
            assertTrue(dist(spots.get(i)[0], spots.get(i)[1], spots.get(j)[0], spots.get(j)[1]) >= 0.19, "bet spots " + i + " · " + j);
    }

    // ------------------------------------------------------------------------------------------ buttons

    @Test
    void theNewButtons() {
        assertEquals("economy:gamble_btn_open", Controls.Button.OPEN2.model());
        assertEquals("economy:gamble_btn_player", Controls.Button.BAC_PLAYER.model());
        assertEquals("economy:gamble_btn_banker", Controls.Button.BAC_BANKER.model());
        assertEquals("economy:gamble_btn_tie", Controls.Button.BAC_TIE.model());
        assertEquals("economy:gamble_btn_bet", Controls.Button.BAC_DONE.model());
        assertEquals("economy:gamble_chips_3", Controls.Button.CHIP3.model());
        assertEquals(Table.Move.OPEN3, Controls.Button.OPEN3.move());
        assertEquals(2, Controls.Button.OPEN3.open());
        assertEquals(Baccarat.Spot.TIE, Controls.Button.BAC_TIE.spot());
        assertNull(Controls.Button.BAC_TIE.move(), "a bet on a spot goes through Table.wager with the picked chip");
        Set<Betting.Action> none = EnumSet.noneOf(Betting.Action.class);
        // 세븐 포커 opening: three 공개 until I opened
        Controls.State open = new Controls.State(Table.Game.POKER, Table.Phase.PLAYING, Table.Step.OPEN, true, true, false, none, false, false, 0);
        assertEquals(List.of(Controls.Button.OPEN1, Controls.Button.OPEN2, Controls.Button.OPEN3), Controls.buttons(open));
        assertEquals(List.of(), Controls.buttons(new Controls.State(Table.Game.POKER, Table.Phase.PLAYING, Table.Step.OPEN, true, true, false, none, false, true, 0)));
        assertEquals(List.of(), Controls.buttons(new Controls.State(Table.Game.POKER, Table.Phase.PLAYING, Table.Step.DEAL, true, true, false, none, false, true, 0)));
        // 바카라 with two chip kinds
        assertEquals(List.of(Controls.Button.CHIP1, Controls.Button.CHIP2, Controls.Button.BAC_PLAYER, Controls.Button.BAC_BANKER, Controls.Button.BAC_TIE,
                Controls.Button.BAC_DONE), Controls.buttons(new Controls.State(Table.Game.BACCARAT, Table.Phase.PLAYING, Table.Step.BETS, true, false, false, none, false, false, 2)));
        assertEquals(List.of(Controls.Button.LEAVE), Controls.buttons(new Controls.State(Table.Game.BACCARAT, Table.Phase.RESULT, null, true, false, false, none, false, false, 4)));
        assertEquals(List.of(), Controls.buttons(new Controls.State(Table.Game.BACCARAT, Table.Phase.PLAYING, Table.Step.BETS, false, false, false, none, false, false, 4)),
                "not seated: none");
    }

    // ------------------------------------------------------------------------------------------ bots

    @Test
    void pokerBotsOpenTheirLoneLowCardAndOnlyEverBetLegally() {
        assertEquals(2, HwatuBot.pokerOpen(PokerTest.cards("ks kd 4c")), "the pair stays hidden");
        assertEquals(1, HwatuBot.pokerOpen(PokerTest.cards("ks 3d 9c")));
        assertEquals(2, HwatuBot.pokerOpen(PokerTest.cards("2s 2d 2c")), "trips: any (the lowest: 2♣)");
        assertTrue(HwatuBot.pokerStrength(PokerTest.cards("as ks qs js 10s"), null) >= 0.99);
        assertTrue(HwatuBot.pokerStrength(PokerTest.cards("2s 7d 9c"), null) < 0.3);
        assertTrue(HwatuBot.pokerStrength(PokerTest.cards("2s 5s 9s ks"), null) > HwatuBot.pokerStrength(PokerTest.cards("2s 5d 9c kh"), null), "a flush draw");
        assertTrue(HwatuBot.pokerStrength(PokerTest.cards("as ad 3c"), PokerTest.hand("ks kd kh"))
                < HwatuBot.pokerStrength(PokerTest.cards("as ad 3c"), null), "an open triple over my pair: careful");
        // never folds a free check, and only ever a legal move, over many hands at a table
        Random r = new Random(5);
        for (int n = 0; n < 2000; n++) {
            Set<Betting.Action> legal = r.nextBoolean() ? EnumSet.of(Betting.Action.DIE, Betting.Action.CHECK, Betting.Action.PING, Betting.Action.HALF)
                    : EnumSet.of(Betting.Action.DIE, Betting.Action.CALL, Betting.Action.DDADANG, Betting.Action.HALF);
            long toCall = legal.contains(Betting.Action.CHECK) ? 0 : 1 + r.nextInt(200);
            Betting.Action a = HwatuBot.poker(r.nextDouble(), legal, toCall, 50 + r.nextInt(300), r.nextInt(4), r.nextDouble());
            assertTrue(legal.contains(a));
            if (toCall == 0) assertFalse(a == Betting.Action.DIE);
        }
        for (int hand = 0; hand < 200; hand++) {
            HwatuSpec s = HwatuTest.spec();
            Table t = new Table(1, Table.Game.POKER, s.rooms().get(0), s, new Random(hand));
            HwatuTest.Log log = new HwatuTest.Log();
            List<java.util.UUID> who = new ArrayList<>();
            for (int i = 0; i < 2 + hand % 5; i++) who.add(java.util.UUID.randomUUID());
            long now = 0;
            for (java.util.UUID u : who) t.sit(u, now);
            t.tick(now, HwatuTest.ALL, log);
            now = t.deadline();
            t.tick(now, HwatuTest.ALL, log);
            int guard = 0;
            while (t.phase() == Table.Phase.PLAYING && guard++ < 400) {
                if (t.step() == Table.Step.OPEN) {
                    for (java.util.UUID u : t.players()) {
                        if (t.openedIndex(u) >= 0) continue;
                        int k = HwatuBot.pokerOpen(t.pcards(u).subList(0, 3));
                        assertNull(t.act(u, List.of(Table.Move.OPEN1, Table.Move.OPEN2, Table.Move.OPEN3).get(k), now, log));
                    }
                    continue;
                }
                if (t.step() == Table.Step.DEAL) {
                    now = t.deadline();
                    t.tick(now, HwatuTest.ALL, log);
                    continue;
                }
                java.util.UUID u = t.turn();
                Poker.Hand threat = null;
                for (java.util.UUID o : t.players()) {
                    if (o.equals(u) || !t.inHand(o)) continue;
                    Poker.Hand h = t.showing(o);
                    if (h != null && (threat == null || h.beats(threat))) threat = h;
                }
                int seat = t.players().indexOf(u);
                Betting.Action a = HwatuBot.poker(HwatuBot.pokerStrength(t.pcards(u), threat), t.betting(), seat, r.nextDouble());
                assertTrue(t.legal(u).contains(a));
                assertNull(t.act(u, Table.move(a), now, log));
            }
            assertEquals(1, log.results.size(), "bot hand " + hand + " ended");
        }
    }

    // ------------------------------------------------------------------------------------------ the board and the messages

    @Test
    void theBoardAndEveryNewText() throws Exception {
        YamlConfiguration m;
        try (var in = getClass().getResourceAsStream("/messages.yml")) {
            m = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> missing = new ArrayList<>();
        for (List<String> page : Jokbo.POKER) {
            assertTrue(page.size() <= 6);
            for (String r : page) if (!m.isString("hwatu.poker-jokbo." + r)) missing.add("poker-jokbo." + r);
        }
        for (int i = 1; i <= Jokbo.POKER.size(); i++) if (!m.isString("hwatu.poker-jokbo-notes.p" + i)) missing.add("notes " + i);
        for (String code : Poker.CODES) {
            if (!m.isString("hwatu.poker-hand." + code)) missing.add("poker-hand." + code);
            assertTrue(Jokbo.pokerPageOf(code) >= 0, code);
            assertEquals(Jokbo.pokerPageOf(code), Jokbo.pokerPage(code, 99_999), "my hand's page stays");
        }
        for (int i = 1; i <= Jokbo.RULE_ROWS; i++) if (!m.isString("hwatu.rules.baccarat-" + i)) missing.add("rules.baccarat-" + i);
        for (Baccarat.Spot s : Baccarat.Spot.values())
            for (String k : List.of("bac.won.", "bac.spot-short.", "bac.road.", "dealer.won-", "btn."))
                if (!m.isString("hwatu." + k + s.key())) missing.add(k + s.key());
        assertTrue(missing.isEmpty(), String.join("\n", missing));
        // every hand a deal can make has its name
        List<PCard> d = PCard.deck();
        Random r = new Random(9);
        for (int i = 0; i < 2000; i++) {
            Collections.shuffle(d, r);
            assertTrue(m.isString("hwatu.poker-hand." + Poker.best(d.subList(0, 1 + r.nextInt(7))).code()));
        }
    }
}
