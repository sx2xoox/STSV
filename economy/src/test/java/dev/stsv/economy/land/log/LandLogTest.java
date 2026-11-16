package dev.stsv.economy.land.log;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.economy.land.log.LandLogStore.Action;
import dev.stsv.economy.land.log.LandLogStore.Row;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 땅 기록 · 테러 되돌리기: the undo rule, container diffs, times, the money plan, the store. */
class LandLogTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void aBlockGoesBackOnlyIfItIsStillWhatTheyLeft() {
        // they broke a chest, then placed dirt there, then broke a door elsewhere
        List<UndoPlan.Change> changes = List.of(
                new UndoPlan.Change(1, "world", 0, 64, 0, "minecraft:chest[facing=north]", "minecraft:air"),
                new UndoPlan.Change(2, "world", 0, 64, 0, "minecraft:air", "minecraft:dirt"),
                new UndoPlan.Change(3, "world", 5, 64, 5, "minecraft:oak_door[half=lower]", "minecraft:air"),
                // placed a block and broke it again: nothing to undo there
                new UndoPlan.Change(4, "world", 9, 64, 9, "minecraft:air", "minecraft:stone"),
                new UndoPlan.Change(5, "world", 9, 64, 9, "minecraft:stone", "minecraft:cave_air"));
        List<UndoPlan.Step> steps = UndoPlan.fold(changes);
        assertEquals(2, steps.size(), steps.toString());
        UndoPlan.Step chest = steps.getFirst();
        assertEquals("minecraft:chest[facing=north]", chest.restore(), "back to what was there before their first change");
        assertEquals("minecraft:dirt", chest.expect(), "only if it is still what they left last");
        assertEquals(1, chest.first(), "the first change carries the chest's contents");
        assertEquals(List.of(1L, 2L), chest.ids());
        assertTrue(UndoPlan.applies(chest, "minecraft:dirt"));
        assertFalse(UndoPlan.applies(chest, "minecraft:stone_bricks"), "the owner rebuilt it since: their work wins");
        UndoPlan.Step door = steps.get(1);
        assertTrue(UndoPlan.applies(door, "minecraft:cave_air"), "air is air");
    }

    private static Confiscation.Unit whole(long id, Map<String, Integer> gain) {
        return new Confiscation.Unit(id, Confiscation.Kind.WHOLE, gain);
    }

    private static Confiscation.Unit count(long id, String key, int n) {
        return new Confiscation.Unit(id, Confiscation.Kind.COUNT, Map.of(key, n));
    }

    @Test
    void nothingGainedGoesBackFreely() {
        // a block broken in creative / with no drops, a block placed, items put in: no gain, nothing to take back
        var r = Confiscation.plan(List.of(whole(1, Map.of()), count(2, "dirt", 0)), Map.of(), false);
        assertTrue(r.stream().allMatch(Confiscation.Result::restore), r.toString());
        assertTrue(r.stream().allMatch(x -> x.take().isEmpty() && x.owed().isEmpty()));
    }

    @Test
    void onlyWhatIsTakenBackGoesBack() {
        // the colluding member broke two diamond blocks (one drop each) and took 10 diamonds from the chest
        List<Confiscation.Unit> units = List.of(
                whole(1, Map.of("diamond_block", 1)),
                whole(2, Map.of("diamond_block", 1)),
                count(3, "diamond", 10));
        // they still hold one diamond block and 4 diamonds (the rest went to the owner, or elsewhere)
        var r = Confiscation.plan(units, Map.of("diamond_block", 1, "diamond", 4), false);
        assertTrue(r.get(0).restore(), "the first block: its drop is taken back, so it goes back");
        assertEquals(Map.of("diamond_block", 1), r.get(0).take());
        assertFalse(r.get(1).restore(), "the second block: nothing left to take back, so it does not come back");
        assertTrue(r.get(1).take().isEmpty());
        assertEquals(Map.of("diamond_block", 1), r.get(1).owed(), "it stays owed (아직 회수 못 함)");
        assertTrue(r.get(2).restore());
        assertEquals(4, r.get(2).restoreCount(), "4 diamonds taken back, 4 go back into the chest");
        assertEquals(Map.of("diamond", 4), r.get(2).take());
        assertEquals(Map.of("diamond", 6), r.get(2).owed());
        // with nothing held (offline, or gave it all away) nothing that gained something goes back
        var none = Confiscation.plan(units, Map.of(), false);
        assertTrue(none.stream().noneMatch(Confiscation.Result::restore));
        assertEquals(12, none.stream().mapToInt(x -> x.owed().values().stream().mapToInt(Integer::intValue).sum()).sum());
    }

    @Test
    void aWholeUnitIsNeverTakenInPart() {
        // an animal's drops: 2 beef and 1 leather; they ate one beef
        var r = Confiscation.plan(List.of(whole(1, Map.of("beef", 2, "leather", 1))), Map.of("beef", 1, "leather", 1), false);
        assertFalse(r.getFirst().restore());
        assertTrue(r.getFirst().take().isEmpty(), "taking part of it would only punish: nothing is taken");
        // units draw from one pool, oldest first: the second cannot use what the first took
        var two = Confiscation.plan(List.of(whole(1, Map.of("gold", 2)), whole(2, Map.of("gold", 2))), Map.of("gold", 3), false);
        assertTrue(two.get(0).restore());
        assertFalse(two.get(1).restore());
    }

    @Test
    void forcedPutsEverythingBackButStillTakesWhatItCan() {
        var r = Confiscation.plan(List.of(whole(1, Map.of("diamond_block", 2)), count(2, "diamond", 10)), Map.of("diamond", 3), true);
        assertTrue(r.get(0).restore(), "강제: the administrator's call");
        assertTrue(r.get(0).owed().isEmpty());
        assertEquals(10, r.get(1).restoreCount());
        assertEquals(Map.of("diamond", 3), r.get(1).take(), "what they hold is still taken");
    }

    @Test
    void whatWasTakenAndWhatWasPut() {
        Map<String, Integer> d = ContainerDiff.diff(Map.of("diamond", 10, "dirt", 64, "bread", 3), Map.of("diamond", 2, "dirt", 64, "cobble", 30));
        assertEquals(Map.of("diamond", -8, "bread", -3, "cobble", 30), d);
        assertTrue(ContainerDiff.diff(Map.of("a", 1), Map.of("a", 1)).isEmpty());
    }

    @Test
    void times() {
        assertEquals(30 * 60_000L, Durations.parse("30m"));
        assertEquals(6 * 3_600_000L, Durations.parse("6h"));
        assertEquals(2 * 86_400_000L, Durations.parse("2d"));
        assertEquals(7 * 86_400_000L, Durations.parse("1w"));
        assertEquals(86_400_000L + 12 * 3_600_000L, Durations.parse("1d12h"));
        assertEquals(90 * 60_000L, Durations.parse("90"), "a bare number is minutes");
        assertEquals(3 * 3_600_000L, Durations.parse("3시간"));
        assertEquals(-1, Durations.parse("어제"));
        assertEquals(-1, Durations.parse("6x"));
        assertEquals(-1, Durations.parse("0h"));
        assertEquals("1일 2시간", Durations.text(26 * 3_600_000L));
        assertEquals("1시간 30분", Durations.text(90 * 60_000L));
    }

    @Test
    void moneyGoesBackNewestFirstAsFarAsItGoes() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        List<MoneyReturn.Payment> pays = List.of(
                new MoneyReturn.Payment(1, 100, a, 5_000, "pay"),
                new MoneyReturn.Payment(2, 200, b, 3_000, "trade"),
                new MoneyReturn.Payment(3, 300, a, 1_000, "pay"));
        List<MoneyReturn.Back> plan = MoneyReturn.plan(pays, 6_000);
        assertEquals(List.of(3L, 2L, 1L), plan.stream().map(x -> x.payment().entry()).toList(), "newest first");
        assertEquals(List.of(1_000L, 3_000L, 2_000L), plan.stream().map(MoneyReturn.Back::returned).toList());
        assertEquals(3_000, plan.get(2).shortBy(), "what does not fit is reported, not invented");
        assertTrue(MoneyReturn.plan(pays, -50).stream().allMatch(x -> x.returned() == 0), "never below nothing");
    }

    @Test
    void theStore(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        new EconomyStore(db).init();      // the ledger the money part reads
        LandLogStore s = new LandLogStore(db);
        s.init();
        UUID owner = UUID.randomUUID(), griefer = UUID.randomUUID(), friend = UUID.randomUUID();
        long t = 1_000_000;
        s.write(List.of(
                new Row(0, t, 7, owner, "world", 1, 64, 1, griefer, "Griefer", Action.BREAK, "minecraft:chest", "minecraft:air", new byte[]{1, 2}, 0),
                new Row(0, t + 10, 7, owner, "world", 2, 64, 2, griefer, "Griefer", Action.TAKE, null, null, new byte[]{3}, 12),
                new Row(0, t + 20, 7, owner, "world", 3, 64, 3, friend, "Friend", Action.PLACE, "minecraft:air", "minecraft:stone", null, 0),
                new Row(0, t + 30, 9, null, "world", 99, 64, 99, griefer, "Griefer", Action.PLACE, "minecraft:air", "minecraft:tnt", null, 0))).get();
        var tally = s.tally(7, 0).get();
        assertEquals(3, tally.size(), tally.toString());
        assertEquals(12, tally.stream().filter(x -> x.action() == Action.TAKE).findFirst().orElseThrow().count(), "items count by amount");
        List<Row> mine = s.toUndo(griefer, 7, 0).get();
        assertEquals(2, mine.size(), "only that land");
        assertEquals(3, s.toUndo(griefer, -1, 0).get().size(), "everywhere");
        assertEquals(1, s.toUndo(griefer, -1, t + 15).get().size(), "since a time");
        assertEquals(new byte[]{1, 2}.length, mine.getFirst().item().length);
        s.undone(mine.stream().map(Row::id).toList()).get();
        assertEquals(0, s.toUndo(griefer, 7, 0).get().size(), "nothing is undone twice");
        assertEquals(1, s.tally(7, 0).get().size(), "the friend's line stays");
        assertEquals(3, s.prune(t + 25).get(), "the retention takes every line older than the cut (undone ones too)");
        assertEquals(1, s.toUndo(griefer, -1, 0).get().size(), "the newer line stays");

        // money: what others paid the griefer, returned once only
        db.sync(c -> {
            Db.update(c, "INSERT INTO ledger (at, from_player, to_player, amount, kind, reason, note, reverted) VALUES (?, ?, ?, ?, 'TRANSFER', 'pay', NULL, 0)",
                    t, friend.toString(), griefer.toString(), 5_000L);
            Db.update(c, "INSERT INTO ledger (at, from_player, to_player, amount, kind, reason, note, reverted) VALUES (?, ?, ?, ?, 'FAUCET', 'shop', NULL, 0)",
                    t, null, griefer.toString(), 9_000L);
            Db.update(c, "INSERT INTO ledger (at, from_player, to_player, amount, kind, reason, note, reverted) VALUES (?, ?, ?, ?, 'TRANSFER', 'pay', NULL, 1)",
                    t, owner.toString(), griefer.toString(), 7_000L);
            return null;
        });
        var pays = s.payments(griefer, 0).get();
        assertEquals(1, pays.size(), "player to player only, not undone ones, not the shop");
        assertEquals(friend, pays.getFirst().from());
        s.returned(MoneyReturn.plan(pays, 10_000), griefer, t + 100).get();
        assertTrue(s.payments(griefer, 0).get().isEmpty(), "returned once only");
        s.audit("admin", griefer, "Griefer", 0, "banned", t + 200).get();
        db.close();
    }

    @Test
    void owedLinesWaitAndSurviveTheRetention(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        LandLogStore s = new LandLogStore(db);
        s.init();
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
        byte[] drops = {9, 9, 9};
        s.write(List.of(
                new Row(0, 100, 7, owner, "world", 1, 64, 1, member, "M", Action.BREAK, "minecraft:diamond_block", "minecraft:air", null, 1, drops, false),
                new Row(0, 110, 7, owner, "world", 2, 64, 2, member, "M", Action.TAKE, null, null, new byte[]{3}, 10))).get();
        List<Row> rows = s.toUndo(member, 7, 0).get();
        assertEquals(3, rows.getFirst().gain().length, "the real drops are kept with the line");
        assertFalse(s.hasWaiting(member).get());
        s.waiting(List.of(rows.getFirst().id())).get();
        s.owe(rows.get(1).id(), 6).get();
        assertTrue(s.hasWaiting(member).get());
        List<Row> waiting = s.waitingOf(member, -1).get();
        assertEquals(2, waiting.size());
        assertTrue(waiting.stream().allMatch(Row::waiting));
        assertEquals(6, waiting.get(1).amount(), "a container take partly undone: what is still owed");
        assertEquals(2, s.waitingIn(7).get().size());
        assertEquals(0, s.prune(1_000).get(), "what is still owed is not forgotten by the retention");
        s.undone(List.of(rows.getFirst().id(), rows.get(1).id())).get();
        assertFalse(s.hasWaiting(member).get(), "done: no longer waiting");
        db.close();
    }

    @Test
    void everyWordIsThere() {
        var msgs = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8));
        for (Action a : Action.values()) assertTrue(msgs.isString("land-log.action." + a.name().toLowerCase()), a.name());
        for (String k : List.of("not-owner", "empty", "head", "person", "detail-head", "line", "more", "bad-time", "not-self", "nothing", "since-all", "preview",
                "preview-buttons", "expired", "cancelled", "no-preview", "undoing", "undone", "result", "admin-usage", "admin-head", "admin-land", "grief-usage",
                "grief-summary", "grief-confirm", "ban-reason", "kick", "money-back", "grief-done", "owner-told", "error", "person-owed", "line-owed",
                "preview-owed", "preview-offline", "still-owed", "confiscated-you", "retried", "admin-owed", "grief-online", "grief-offline", "grief-owed")) {
            assertTrue(msgs.isString("land-log." + k), k);
        }
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        for (String k : List.of("enabled", "keep-days", "owner", "preview-seconds", "preview-max-blocks", "undo-blocks-per-tick")) {
            assertTrue(config.contains("land-log." + k), k);
        }
    }
}
