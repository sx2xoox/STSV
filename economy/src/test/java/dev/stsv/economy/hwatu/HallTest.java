package dev.stsv.economy.hwatu;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 도박장 (README "도박장"): seats of the two tables, the buttons for each state, who sees a card's face, the chip pile for a
 * pot, the die's turn for a face, the spots on a table top, today's big pots, the table bindings in the database,
 * hwatu.yml hall, and every message key the code uses.
 */
class HallTest {

    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();

    // ------------------------------------------------------------------------------------------ seats

    @Test
    void theTwoTables() {
        assertEquals(TableKind.ROUND, TableKind.of("economy:gamble_table_round"));
        assertEquals(TableKind.DUEL, TableKind.of("economy:gamble_table_duel"));
        assertNull(TableKind.of("economy:hwatu_1a"));
        assertEquals(6, TableKind.ROUND.seats());
        assertEquals(2, TableKind.DUEL.seats());
    }

    @Test
    void usableSeatsSpreadRoundTheTable() {
        assertEquals(List.of(0, 1, 2, 3, 4, 5), TableKind.usable(6, 6), "섯다 · 주사위: every chair");
        assertEquals(List.of(0, 3), TableKind.usable(6, 2), "홀짝 at a round table: face to face");
        assertEquals(List.of(0, 2, 4), TableKind.usable(6, 3));
        assertEquals(List.of(0, 1), TableKind.usable(2, 2), "the duel table");
        assertEquals(List.of(0, 1), TableKind.usable(2, 6), "a 6-player game at the duel table still has its two chairs");
        assertEquals(List.of(), TableKind.usable(0, 6));
        for (int seats = 1; seats <= 8; seats++) {
            for (int max = 1; max <= 8; max++) {
                List<Integer> u = TableKind.usable(seats, max);
                assertEquals(Math.min(seats, max), u.size(), seats + " chairs, " + max + " players");
                assertEquals(u.size(), new HashSet<>(u).size(), "no chair twice");
                for (int k : u) assertTrue(k >= 0 && k < seats);
                assertEquals(0, u.getFirst(), "seat 0 (the table's front) is always used");
            }
        }
    }

    // ------------------------------------------------------------------------------------------ buttons

    private static final Set<Betting.Action> OPEN = EnumSet.of(Betting.Action.DIE, Betting.Action.CHECK, Betting.Action.PING, Betting.Action.HALF),
            FACING = EnumSet.of(Betting.Action.DIE, Betting.Action.CALL, Betting.Action.DDADANG, Betting.Action.HALF),
            SHORT = EnumSet.of(Betting.Action.DIE, Betting.Action.CALL), NONE = EnumSet.noneOf(Betting.Action.class);

    private static Controls.State state(Table.Game g, Table.Phase p, Table.Step s, boolean in, boolean turn, Set<Betting.Action> legal, boolean out) {
        return new Controls.State(g, p, s, true, in, turn, legal, out);
    }

    @Test
    void buttonsForEachState() {
        Controls.State none = new Controls.State(Table.Game.SUTDA, Table.Phase.PLAYING, Table.Step.BET, false, false, false, OPEN, false);
        assertEquals(List.of(), Controls.buttons(none), "not seated: nothing");
        assertEquals(List.of(Controls.Button.DIE, Controls.Button.CHECK, Controls.Button.PING, Controls.Button.HALF),
                Controls.buttons(state(Table.Game.SUTDA, Table.Phase.PLAYING, Table.Step.BET, true, true, OPEN, false)), "nobody bet yet");
        assertEquals(List.of(Controls.Button.DIE, Controls.Button.CALL, Controls.Button.DDADANG, Controls.Button.HALF),
                Controls.buttons(state(Table.Game.SUTDA, Table.Phase.PLAYING, Table.Step.BET, true, true, FACING, false)), "facing a bet");
        assertEquals(List.of(Controls.Button.DIE, Controls.Button.CALL),
                Controls.buttons(state(Table.Game.SUTDA, Table.Phase.PLAYING, Table.Step.BET, true, true, SHORT, false)), "short of chips: call all-in or fold");
        assertEquals(List.of(), Controls.buttons(state(Table.Game.SUTDA, Table.Phase.PLAYING, Table.Step.BET, true, false, NONE, false)),
                "someone else's turn: the timer shows, no buttons");
        assertEquals(List.of(Controls.Button.ODD, Controls.Button.EVEN),
                Controls.buttons(state(Table.Game.HOLJJAK, Table.Phase.PLAYING, Table.Step.PICK, true, true, NONE, false)));
        assertEquals(List.of(), Controls.buttons(state(Table.Game.DICE, Table.Phase.PLAYING, Table.Step.ROLL, true, false, NONE, false)));
        assertEquals(List.of(Controls.Button.LEAVE), Controls.buttons(state(Table.Game.DICE, Table.Phase.WAITING, null, false, false, NONE, false)));
        assertEquals(List.of(Controls.Button.LEAVE), Controls.buttons(state(Table.Game.SUTDA, Table.Phase.RESULT, null, false, false, NONE, false)));
        assertEquals(List.of(Controls.Button.LEAVE),
                Controls.buttons(state(Table.Game.SUTDA, Table.Phase.PLAYING, Table.Step.BET, false, false, NONE, false)), "folded / sat down mid-hand");
        assertEquals(List.of(Controls.Button.READY, Controls.Button.LEAVE),
                Controls.buttons(state(Table.Game.SUTDA, Table.Phase.WAITING, null, false, false, NONE, true)), "sitting out: 준비");
        // never more than Controls.MOST in front of a seat
        for (Set<Betting.Action> l : List.of(OPEN, FACING, SHORT, EnumSet.allOf(Betting.Action.class)))
            assertTrue(Controls.buttons(state(Table.Game.SUTDA, Table.Phase.PLAYING, Table.Step.BET, true, true, l, false)).size() <= Controls.MOST);
    }

    @Test
    void buttonsOfARealTable() {
        HwatuSpec spec = HwatuTest.spec();
        Table t = new Table(1, Table.Game.SUTDA, spec.rooms().getFirst(), spec, new Random(3));
        List<Controls.Button> seen = new ArrayList<>();
        Table.Listener quiet = new Table.Listener() {
            public void changed(Table x) {}
            public void kicked(Table x, UUID p, String r) {}
            public void finished(Table x, Table.Result r) {}
            public void replay(Table x) {}
        };
        t.sit(A, 0);
        t.sit(B, 0);
        assertEquals(List.of(Controls.Button.LEAVE), Controls.buttons(Controls.state(t, A, true, false)));
        t.tick(spec.startSeconds() * 1000L + 1, (u, n) -> null, quiet);
        assertEquals(Table.Phase.PLAYING, t.phase());
        UUID turn = t.turn(), other = turn.equals(A) ? B : A;
        seen.addAll(Controls.buttons(Controls.state(t, turn, true, false)));
        assertEquals(List.of(Controls.Button.DIE, Controls.Button.CHECK, Controls.Button.PING, Controls.Button.HALF), seen);
        assertEquals(List.of(), Controls.buttons(Controls.state(t, other, true, false)));
        assertNull(t.act(turn, Controls.Button.PING.move(), 0, quiet));
        seen.addAll(Controls.buttons(Controls.state(t, other, true, false)));
        assertEquals(List.of(Controls.Button.DIE, Controls.Button.CALL, Controls.Button.DDADANG, Controls.Button.HALF),
                Controls.buttons(Controls.state(t, other, true, false)), "after the 삥");
        for (Controls.Button b : Controls.buttons(Controls.state(t, other, true, false))) assertEquals(b.action(), Table.action(b.move()));
        for (Controls.Button b : seen) assertNotNull(b.move(), b + " plays a move");
        assertNull(Controls.Button.READY.move());
        assertNull(Controls.Button.LEAVE.move());
        for (Controls.Button b : Controls.Button.values()) {
            assertEquals(b, Controls.Button.of(b.key()));
            assertEquals("economy:gamble_btn_" + b.key(), b.model(), "the asset contract's ids");
        }
    }

    // ------------------------------------------------------------------------------------------ cards

    @Test
    void onlyTheOwnerSeesTheFaceUntilTheShowdown() {
        assertTrue(TableArt.seesFace(A, A, false));
        assertFalse(TableArt.seesFace(B, A, false));
        assertTrue(TableArt.seesFace(B, A, true));

        HwatuSpec spec = HwatuTest.spec();
        Table t = new Table(1, Table.Game.SUTDA, spec.rooms().getFirst(), spec, new Random(5));
        Table.Listener quiet = new Table.Listener() {
            public void changed(Table x) {}
            public void kicked(Table x, UUID p, String r) {}
            public void finished(Table x, Table.Result r) {}
            public void replay(Table x) {}
        };
        t.force(A, Card.parse("3광"), Card.parse("8광"));   // 38광땡: never a 재경기
        t.force(B, Card.parse("1광"), Card.parse("2띠"));
        t.force(C, Card.parse("5열끗"), Card.parse("6띠"));
        t.sit(A, 0);
        t.sit(B, 0);
        t.sit(C, 0);
        long now = spec.startSeconds() * 1000L + 1;
        t.tick(now, (u, n) -> null, quiet);
        for (UUID u : List.of(A, B, C)) assertFalse(TableArt.revealed(t, u), "dealt: face down");
        // C folds, the others call round
        for (int i = 0; i < 6 && t.phase() == Table.Phase.PLAYING; i++) {
            UUID turn = t.turn();
            assertNull(t.act(turn, turn.equals(C) ? Table.Move.DIE : HwatuTest.pass(t, turn), now, quiet));
        }
        assertEquals(Table.Phase.RESULT, t.phase());
        assertTrue(TableArt.revealed(t, A));
        assertTrue(TableArt.revealed(t, B));
        assertFalse(TableArt.revealed(t, C), "a folded hand stays face down");
    }

    @Test
    void aHandEveryoneFoldedFromIsNotShown() {
        HwatuSpec spec = HwatuTest.spec();
        Table t = new Table(1, Table.Game.SUTDA, spec.rooms().getFirst(), spec, new Random(5));
        Table.Listener quiet = new Table.Listener() {
            public void changed(Table x) {}
            public void kicked(Table x, UUID p, String r) {}
            public void finished(Table x, Table.Result r) {}
            public void replay(Table x) {}
        };
        t.sit(A, 0);
        t.sit(B, 0);
        long now = spec.startSeconds() * 1000L + 1;
        t.tick(now, (u, n) -> null, quiet);
        UUID first = t.turn(), second = first.equals(A) ? B : A;
        t.act(first, Table.Move.PING, now, quiet);
        t.act(second, Table.Move.DIE, now, quiet);
        assertEquals(Table.Phase.RESULT, t.phase());
        assertTrue(t.last().uncontested());
        assertFalse(TableArt.revealed(t, first), "won uncontested: the winner's cards are not shown");
    }

    // ------------------------------------------------------------------------------------------ the chip pile

    @Test
    void thePileGrowsWithThePot() {
        assertEquals(0, TableArt.chipLevel(0, 10));
        assertEquals(1, TableArt.chipLevel(20, 10), "two players' antes");
        assertEquals(1, TableArt.chipLevel(200, 100), "the same in every room");
        assertEquals(5, TableArt.chipLevel(10_000, 10));
        int last = 0;
        float lastScale = 0;
        for (long pot = 1; pot <= 400; pot++) {
            int level = TableArt.chipLevel(pot, 10);
            assertTrue(level >= last && level >= 1 && level <= TableArt.CHIP_LEVELS, "pot " + pot);
            float scale = TableArt.chipScale(pot, 10);
            assertTrue(scale >= 1f && scale <= 1.15f + 1e-6, "scale " + scale);
            if (level == last) assertTrue(scale >= lastScale - 1e-6, "grows within its model, pot " + pot);
            last = level;
            lastScale = scale;
        }
        // a six-player 섯다 with both raises (6 × 3 antes) is the tallest pile
        assertEquals(5, TableArt.chipLevel(6 * 30, 10));
        assertEquals(0f, TableArt.chipScale(0, 10));
    }

    // ------------------------------------------------------------------------------------------ the die

    @Test
    void theDieLandsOnItsFace() {
        Vector3f up = new Vector3f(0, 1, 0);
        for (int face = 1; face <= 6; face++) {
            for (float spin : new float[]{0, 37, 90, 211}) {
                Quaternionf q = TableArt.dieFace(face, spin);
                Vector3f top = q.transform(TableArt.drawn(TableArt.DIE_FACES[face - 1]));
                assertEquals(0, top.distance(up), 1e-4, "face " + face + " spin " + spin + " on top, got " + top);
                Vector3f bottom = q.transform(TableArt.drawn(TableArt.DIE_FACES[6 - face]));
                assertEquals(0, bottom.distance(new Vector3f(0, -1, 0)), 1e-4, "its opposite (7 - " + face + ") underneath");
            }
        }
        // the faces table is a real die: six different directions, opposite faces opposite
        Set<String> dirs = new HashSet<>();
        for (int f = 0; f < 6; f++) {
            Vector3f v = TableArt.DIE_FACES[f];
            assertEquals(1, v.length(), 1e-6);
            dirs.add(Math.round(v.x) + "," + Math.round(v.y) + "," + Math.round(v.z));
            assertEquals(0, new Vector3f(v).add(TableArt.DIE_FACES[5 - f]).length(), 1e-6, (f + 1) + " and " + (6 - f) + " opposite");
        }
        assertEquals(6, dirs.size());
    }

    @Test
    void facingPointsTheModelsNorthThatWay() {
        Vector3f north = new Vector3f(0, 0, -1);
        for (float yaw : new float[]{0, 90, 180, 270, 33}) {
            Vector3f v = TableArt.facing(yaw).transform(TableArt.drawn(north));
            double r = Math.toRadians(yaw);
            assertEquals(-Math.sin(r), v.x, 1e-4, "yaw " + yaw);
            assertEquals(Math.cos(r), v.z, 1e-4, "yaw " + yaw);
        }
    }

    // ------------------------------------------------------------------------------------------ the table top

    @Test
    void spotsRoundTheTable() {
        List<double[]> seats = List.of(new double[]{0, -2}, new double[]{2, 0}, new double[]{0, 2}, new double[]{-2, 0});
        double[] c = TableArt.center(seats);
        assertEquals(0, c[0], 1e-9);
        assertEquals(0, c[1], 1e-9);
        // seat 0 is to the north: its things lie north of the middle, its right hand (looking south at the table) is west
        TableArt.Spot card = TableArt.along(c, seats.get(0), 0.5, 0, false);
        assertEquals(0, card.x(), 1e-9);
        assertEquals(-1, card.z(), 1e-9);
        assertEquals(0, card.yaw(), 1e-3, "facing the middle (south, yaw 0)");
        TableArt.Spot button = TableArt.along(c, seats.get(0), 0.5, 0, true);
        assertEquals(180, button.yaw(), 1e-3, "cards and buttons face their seat (north): its sitter reads them upright");
        TableArt.Spot right = TableArt.along(c, seats.get(0), 0.5, 0.3, true);
        assertEquals(-0.3, right.x(), 1e-9, "the sitter's right");
        assertEquals(-1, right.z(), 1e-9);
        // every seat: its spots are nearer to it than to any other seat
        for (int k = 0; k < seats.size(); k++) {
            TableArt.Reach r = TableArt.ROUND_REACH;
            for (double reach : new double[]{r.card(), r.button(), r.dice(), r.timer()}) {
                for (double side : TableArt.row(3, TableArt.BUTTON_GAP)) {
                    TableArt.Spot s = TableArt.along(c, seats.get(k), reach, side, true);
                    int nearest = -1;
                    double best = Double.MAX_VALUE;
                    for (int j = 0; j < seats.size(); j++) {
                        double d = Math.hypot(seats.get(j)[0] - s.x(), seats.get(j)[1] - s.z());
                        if (d < best) {
                            best = d;
                            nearest = j;
                        }
                    }
                    assertEquals(k, nearest, "seat " + k + " reach " + reach + " side " + side);
                }
            }
        }
        // the duel table: two chairs a block either side of a crate a block across; its buttons stay on the crate
        List<double[]> duel = List.of(new double[]{0.5, -0.5}, new double[]{0.5, 1.5});
        double[] dc = TableArt.center(duel);
        for (int k = 0; k < 2; k++) {
            for (double side : TableArt.row(3, TableArt.BUTTON_GAP)) {
                TableArt.Spot s = TableArt.along(dc, duel.get(k), TableArt.reach(TableKind.DUEL).button(), side, true);
                assertTrue(Math.abs(s.x() - dc[0]) <= 0.5 + 1e-9 && Math.abs(s.z() - dc[1]) <= 0.5 + 1e-9, "on the crate: " + s);
            }
        }
        assertEquals(TableArt.ROUND_REACH, TableArt.reach(TableKind.ROUND));
        assertEquals(List.of(-0.2, 0.0, 0.2), TableArt.row(3, 0.2));
        assertEquals(List.of(-0.1, 0.1), TableArt.row(2, 0.2));
        assertEquals(90, TableArt.yawToward(-1, 0), 1e-3);
        assertEquals(270, TableArt.yawToward(1, 0), 1e-3);
    }

    @Test
    void aSeatsButtonsFitInFrontOfIt() {
        // the real round table (models/gamble.py ROUND_SEATS, cell centres) and the duel table
        List<double[]> round = new ArrayList<>();
        for (int[] c : new int[][]{{0, -2}, {2, -1}, {2, 1}, {0, 2}, {-2, 1}, {-2, -1}}) round.add(new double[]{c[0] + 0.5, c[1] + 0.5});
        double[] rc = TableArt.center(round);
        for (int n = 1; n <= Controls.MOST; n++) {
            List<TableArt.Slot> slots = TableArt.pad(n, TableArt.ROUND_REACH);
            assertEquals(n, slots.size());
            assertEquals(1, slots.stream().map(TableArt.Slot::scale).distinct().count(), "one row, one size");
            for (int k = 0; k < round.size(); k++) {
                List<TableArt.Spot> spots = new ArrayList<>();
                for (TableArt.Slot sl : slots) {
                    TableArt.Spot s = TableArt.along(rc, round.get(k), TableArt.ROUND_REACH.button(), sl.side(), true);
                    spots.add(s);
                    assertTrue(Math.hypot(s.x() - rc[0], s.z() - rc[1]) <= 1.55, "on the round top (r 1.5): seat " + k + " n " + n);
                    int nearest = -1;
                    double best = Double.MAX_VALUE;
                    for (int j = 0; j < round.size(); j++) {
                        double d = Math.hypot(round.get(j)[0] - s.x(), round.get(j)[1] - s.z());
                        if (d < best) {
                            best = d;
                            nearest = j;
                        }
                    }
                    assertEquals(k, nearest, "a button nearer its own seat: seat " + k + " n " + n);
                }
                for (int i = 0; i < spots.size(); i++) for (int j = i + 1; j < spots.size(); j++) {
                    double d = Math.hypot(spots.get(i).x() - spots.get(j).x(), spots.get(i).z() - spots.get(j).z());
                    assertTrue(d >= 0.3 * slots.get(i).scale() + 0.01, "buttons do not overlap: " + d);
                }
            }
        }
        // the duel crate (1 block): the four betting buttons in one row, smaller, still on the crate
        List<double[]> duel = List.of(new double[]{0.5, -0.5}, new double[]{0.5, 1.5});
        double[] dc = TableArt.center(duel);
        List<TableArt.Slot> four = TableArt.pad(4, TableArt.DUEL_REACH);
        assertTrue(four.stream().allMatch(sl -> sl.scale() < 1 && sl.scale() > 0.6));
        for (int k = 0; k < 2; k++) {
            for (TableArt.Slot sl : four) {
                TableArt.Spot s = TableArt.along(dc, duel.get(k), TableArt.DUEL_REACH.button(), sl.side(), true);
                double half = 0.15 * sl.scale();   // half a plate
                assertTrue(Math.abs(s.x() - dc[0]) + half <= 0.5 + 1e-9 && Math.abs(s.z() - dc[1]) <= 0.5 + 1e-9, "on the crate: " + s);
            }
        }
        assertTrue(TableArt.pad(4, TableArt.ROUND_REACH).stream().allMatch(sl -> sl.scale() == 1), "the round table has room for four");
    }

    // ------------------------------------------------------------------------------------------ the whiteboard

    @Test
    void todaysBigPots() {
        BigPots p = new BigPots(3);
        assertTrue(p.add(7, 1000, "a"));
        assertTrue(p.add(7, 3000, "b"));
        assertTrue(p.add(7, 2000, "c"));
        assertFalse(p.add(7, 500, "d"), "smaller than the three on the board");
        assertTrue(p.add(7, 2500, "e"));
        assertEquals(List.of("b", "e", "c"), p.entries(7).stream().map(BigPots.Entry::line).toList(), "biggest first");
        assertEquals(List.of(), p.entries(8), "a new day: an empty board");
        BigPots q = BigPots.decode(p.encode(), 3);
        assertEquals(p.entries(7), q.entries(7), "kept across a restart");
        assertTrue(q.add(8, 10, "f"));
        assertEquals(List.of("f"), q.entries(8).stream().map(BigPots.Entry::line).toList());
        assertEquals(List.of(), BigPots.decode(null, 3).entries(0));
        assertEquals(List.of(), BigPots.decode("x\n1\tbroken", 3).entries(0));
        BigPots r = new BigPots(2);
        r.add(1, 5, "two\nlines\tand tab");
        assertEquals(1, BigPots.decode(r.encode(), 2).entries(1).size(), "a line never breaks the stored form");
    }

    // ------------------------------------------------------------------------------------------ storage

    @Test
    void bindingsSurviveARestart(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        HallStore s = new HallStore(db);
        s.init();
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID(), t3 = UUID.randomUUID();
        s.bind(new HallStore.Binding(t1, "world", 1, 64, 2, "sutda", "beginner"));
        s.bind(new HallStore.Binding(t2, "world", 5, 64, 2, "holjjak", "normal"));
        s.bind(new HallStore.Binding(t2, "world", 5, 64, 2, "holjjak", "high"));   // rebound
        s.set(HallStore.WARP, "world;1;2;3;0;0");
        s.set(HallStore.RECORDS, "7\n100\tline");
        s.set(HallStore.COUNTER, "x");
        s.set(HallStore.COUNTER, null);
        db.close();

        db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        s = new HallStore(db);
        s.init();
        assertEquals(2, s.tables().size());
        assertEquals("high", s.table(t2).room());
        assertEquals("world;1;2;3;0;0", s.get(HallStore.WARP));
        assertEquals("7\n100\tline", s.get(HallStore.RECORDS));
        assertNull(s.get(HallStore.COUNTER));
        s.moved(t1, new HallStore.Binding(t3, "world", 1, 64, 2, "sutda", "beginner"));
        assertTrue(s.unbind(t2));
        assertFalse(s.unbind(t2));
        db.close();

        db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        s = new HallStore(db);
        s.init();
        assertEquals(1, s.tables().size());
        assertNull(s.table(t1));
        assertEquals("sutda", s.table(t3).game());
        db.close();
    }

    // ------------------------------------------------------------------------------------------ hwatu.yml hall

    @Test
    void hallSettings() {
        List<String> problems = new ArrayList<>();
        HallSpec h = HallSpec.parse(YamlConfiguration.loadConfiguration(Path.of(System.getProperty("stsv.content")).resolve("economy/hwatu.yml").toFile())
                .getConfigurationSection("hall"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertEquals(0.88, h.top(TableKind.ROUND), 1e-9);
        assertEquals(0.77, h.top(TableKind.DUEL), 1e-9);
        assertEquals(1000, h.bigPot());
        assertEquals(5, h.boardLines());
        assertTrue(h.mannequins());
        List<String> none = new ArrayList<>();
        HallSpec d = HallSpec.parse(null, none);
        assertEquals(1, none.size(), "missing: said, and the defaults used");
        assertEquals(1000, d.bigPot());
    }

    // ------------------------------------------------------------------------------------------ messages

    /** Every "hwatu.…" key the code names (the whole key, not a prefix it builds on) has its text. */
    @Test
    void everyMessageTheCodeUsesExists() throws Exception {
        YamlConfiguration m;
        try (var in = getClass().getResourceAsStream("/messages.yml")) {
            m = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Path src = Path.of("src/main/java/dev/stsv/economy/hwatu");
        Pattern key = Pattern.compile("\"(hwatu\\.[a-z0-9.-]+)\"");
        List<String> missing = new ArrayList<>();
        try (var files = Files.list(src)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                Matcher x = key.matcher(Files.readString(f));
                while (x.find()) {
                    String k = x.group(1);
                    if (k.endsWith(".") || k.endsWith("-") || k.equals("hwatu.yml")) continue;   // a prefix: HAND, "hwatu.btn.", "hwatu.test-"
                    if (!m.isString(k)) missing.add(f.getFileName() + ": " + k);
                }
            }
        }
        for (String k : List.of("my-win", "my-loss", "test-my-win", "test-my-loss")) if (!m.isString("hwatu." + k)) missing.add(k);
        assertTrue(missing.isEmpty(), String.join("\n", missing));
        // the old screens' texts are gone
        assertFalse(m.contains("hwatu.room-name"));
        assertFalse(m.contains("hwatu.btn-call"));
    }
}
