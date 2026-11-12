package dev.stsv.core.vehicle;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 지게: its load in the database by id and token (a copy of the item opens nothing, a stale wearer saves nothing), its
 * spec and look stages, the 10 % slower walk on and off, and the rules for wearing, taking off, opening and dying.
 */
class JigeTest {

    @TempDir
    Path dir;
    private Db db;
    private JigeStore store;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        db = DatabaseModule.sqlite(dir.resolve("t.db"), Logger.getLogger("test"));
        store = new JigeStore(db);
        store.init();
    }

    @AfterEach
    void close() {
        db.close();
    }

    // ------------------------------------------------------------------------------------------
    // Storage and tokens
    // ------------------------------------------------------------------------------------------

    @Test
    void theLoadStaysWithTheJigeNotTheItem() {
        JigeStore.Row a = store.create(owner, "Owner", 111, 1000).join();
        assertTrue(a.id() > 0);
        assertNull(a.contents(), "a new 지게 is empty");
        byte[] load = {1, 2, 3, 4};
        assertTrue(store.save(a.id(), 111, load, 1100).join(), "the holder saves");
        // taken off, put on again: claimed with the token on the item, the load is there
        JigeStore.Row again = store.claim(a.id(), 111, 222, 1200).join();
        assertNotNull(again);
        assertArrayEquals(load, again.contents(), "the load comes back with the same 지게");
        assertEquals(222, again.token(), "a new token");
    }

    @Test
    void aCopiedItemOpensNothing() {
        JigeStore.Row a = store.create(owner, "Owner", 111, 1000).join();
        store.save(a.id(), 111, new byte[]{9}, 1001).join();
        // two copies of the item, both with token 111: the first one put on claims it, the other is dead
        assertNotNull(store.claim(a.id(), 111, 222, 1002).join(), "the first copy");
        assertNull(store.claim(a.id(), 111, 333, 1003).join(), "the second copy: old token");
        assertFalse(store.save(a.id(), 111, new byte[]{}, 1004).join(), "and it can never write over the load");
        assertArrayEquals(new byte[]{9}, store.get(a.id()).join().contents(), "the load is untouched");
        assertTrue(store.save(a.id(), 222, null, 1005).join(), "the holder empties it");
        assertNull(store.get(a.id()).join().contents());
    }

    @Test
    void twoClaimsAtOnceOnlyOneWins() {
        JigeStore.Row a = store.create(owner, "Owner", 5, 1000).join();
        CompletableFuture<JigeStore.Row> x = store.claim(a.id(), 5, 6, 1001), y = store.claim(a.id(), 5, 7, 1001);
        int won = (x.join() != null ? 1 : 0) + (y.join() != null ? 1 : 0);
        assertEquals(1, won, "one conditional update: never both");
        assertNull(store.claim(a.id() + 99, 5, 8, 1002).join(), "no such 지게");
    }

    // ------------------------------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------------------------------

    @Test
    void wearingAndTakingOff() {
        assertEquals(JigeRules.Worn.NONE, JigeRules.worn(false, 4, 5, 0, 0), "no 지게 in the chest slot");
        assertEquals(JigeRules.Worn.NEW, JigeRules.worn(true, 0, 0, 0, 0), "an admin-made item: a new 지게 for it");
        assertEquals(JigeRules.Worn.CLAIM, JigeRules.worn(true, 4, 5, 0, 0), "put on: claim it");
        assertEquals(JigeRules.Worn.SAME, JigeRules.worn(true, 4, 5, 4, 5), "the one they hold");
        assertEquals(JigeRules.Worn.CLAIM, JigeRules.worn(true, 4, 9, 4, 5), "another copy of it: claim (and fail)");
        assertEquals(JigeRules.Worn.CLAIM, JigeRules.worn(true, 7, 1, 4, 5), "swapped for another 지게");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(JigeRules.mayClaim(a, null));
        assertTrue(JigeRules.mayClaim(a, a));
        assertFalse(JigeRules.mayClaim(b, a), "held by someone else on this server: never two people on one load");
        assertTrue(JigeRules.mayTakeOff(0) && JigeRules.mayTakeOff(54), "taken off full or empty: the load stays bound to its id");
    }

    /** A teleport carries the load off the road: further than a step, or into another world, its trip is broken off. */
    @Test
    void aTeleportBreaksTheTripOffButSteppingOutOfASeatDoesNot() {
        assertFalse(JigeRules.teleportBreaks(true, 0), "nowhere");
        assertFalse(JigeRules.teleportBreaks(true, 2.5), "out of a vehicle's seat, beside it");
        assertFalse(JigeRules.teleportBreaks(true, JigeRules.TELEPORT_STEP), "a step");
        assertTrue(JigeRules.teleportBreaks(true, JigeRules.TELEPORT_STEP + 0.01), "further: a teleport");
        assertTrue(JigeRules.teleportBreaks(true, 7000), "/tpa to the dock");
        assertTrue(JigeRules.teleportBreaks(false, 0), "another world, wherever");
        assertTrue(JigeRules.teleportBreaks(true, Double.NaN), "not known: broken off");
    }

    /** /관리자 지게 회수: a new token for the 지게 whatever the old one was (every old item dies), the load stays. */
    @Test
    void anAdminRecallKillsEveryOldItemAndKeepsTheLoad() {
        JigeStore.Row a = store.create(owner, "Owner", 111, 1000).join();
        byte[] load = {1, 2, 3};
        assertTrue(store.save(a.id(), 111, load, 1100).join());
        JigeStore.Row b = store.create(UUID.randomUUID(), "Other", 5, 1000).join();
        assertEquals(2, store.list(null, 100).join().size());
        assertEquals(List.of(a.id()), store.list(owner, 100).join().stream().map(JigeStore.Row::id).toList(), "one owner's");
        JigeStore.Row again = store.reissue(a.id(), 222, 1200).join();
        assertNotNull(again);
        assertEquals(222, again.token());
        assertArrayEquals(load, again.contents(), "the load stays with the number");
        assertNull(store.claim(a.id(), 111, 333, 1300).join(), "the old item (and every copy of it) opens nothing");
        assertFalse(store.save(a.id(), 111, new byte[]{9}, 1300).join(), "nor saves over the load");
        assertNotNull(store.claim(a.id(), 222, 333, 1300).join(), "the new item does");
        assertNull(store.reissue(99999, 1, 1300).join(), "no such 지게");
        assertEquals(5, store.get(b.id()).join().token(), "the others are untouched");
    }

    @Test
    void openingAndDying() {
        assertEquals(JigeRules.Open.NOT_WORN, JigeRules.open(false, false, false));
        assertEquals(JigeRules.Open.OK, JigeRules.open(true, true, false));
        assertEquals(JigeRules.Open.LOADING, JigeRules.open(true, false, true));
        assertEquals(JigeRules.Open.STALE, JigeRules.open(true, false, false), "worn but not claimed: a dead copy");
        assertTrue(JigeRules.openClick(true, true, true, false), "sneak + right click, empty hand");
        assertFalse(JigeRules.openClick(false, true, true, false), "not sneaking");
        assertFalse(JigeRules.openClick(true, false, true, false), "something in the hand");
        assertFalse(JigeRules.openClick(true, true, false, false), "a left click");
        assertFalse(JigeRules.openClick(true, true, true, true), "a door or a chest opens as usual");
        assertTrue(JigeRules.keptOnDeath(true), "a 지게 is kept on death (never dropped)");
        assertFalse(JigeRules.keptOnDeath(false), "everything else as usual");
    }

    // ------------------------------------------------------------------------------------------
    // Walking speed
    // ------------------------------------------------------------------------------------------

    @Test
    void tenPercentSlowerWhileWornAndBackAfter() {
        PullSpeedTest.Speed speed = new PullSpeedTest.Speed();
        assertTrue(JigeSpeed.apply(speed, 0.1));
        AttributeModifier m = speed.getModifier(JigeSpeed.KEY);
        assertNotNull(m);
        assertEquals(AttributeModifier.Operation.MULTIPLY_SCALAR_1, m.getOperation());
        assertTrue(speed.saved.isEmpty(), "transient: never saved with the player");
        assertEquals(0.09, speed.getValue(), 1e-12, "10 % slower");
        assertFalse(JigeSpeed.apply(speed, 0.1), "again: no second modifier");
        assertEquals(1, speed.getModifiers().size());
        // with a hand cart's handles too: both apply, each its own key
        PullSpeed.apply(speed, 0.3, false);
        assertEquals(2, speed.getModifiers().size());
        PullSpeed.remove(speed);
        assertTrue(JigeSpeed.remove(speed), "taken off");
        assertEquals(0.1, speed.getValue(), 1e-12, "full speed");
        assertFalse(JigeSpeed.remove(speed));
        assertFalse(JigeSpeed.apply(null, 0.1));
    }

    // ------------------------------------------------------------------------------------------
    // The shipped 지게
    // ------------------------------------------------------------------------------------------

    static JigeSpec shipped() {
        Path pack = Path.of(System.getProperty("stsv.content")).resolve("stsv");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pack.resolve("items/vehicles_misc.yml").toFile());
        return JigeSpec.parse(y.getConfigurationSection("jige.jige"), "stsv", m -> Files.exists(pack.resolve("models/" + m + ".bbmodel")));
    }

    @Test
    void theShippedJigeAndItsLoadStages() {
        JigeSpec s = shipped();
        assertEquals(54, s.slots(), "54 slots: one large chest view");
        assertEquals(0.1, s.slowdown(), 1e-9);
        assertEquals(4, s.stages().size(), "the empty frame and three loads");
        assertEquals("stsv:model/jige", s.stages().getFirst().asString(), "the empty frame is the item's own model");
        assertEquals(0, s.stage(0), "empty: the bare frame");
        assertEquals(1, s.stage(1), "a few bundles");
        assertEquals(1, s.stage(18));
        assertEquals(2, s.stage(19));
        assertEquals(2, s.stage(36));
        assertEquals(3, s.stage(37), "full under the mat");
        assertEquals(3, s.stage(54));

        Path pack = Path.of(System.getProperty("stsv.content")).resolve("stsv");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pack.resolve("items/vehicles_misc.yml").toFile());
        assertEquals("chest", y.getString("jige.equipment.slot"), "worn in the chestplate slot");
        assertEquals(1, y.getInt("jige.max-stack"));
        assertTrue(Files.isRegularFile(pack.resolve("assets/stsv/equipment/jige.json")), "its equipment asset (draws nothing on the body)");
        String lore = String.join("\n", y.getStringList("jige.lore"));
        assertTrue(lore.contains("1세대"), "its generation");
        assertTrue(lore.contains("겉날개") && lore.contains("가슴"), "why no elytra or chest armour meanwhile");
        assertTrue(lore.contains("/지게") && lore.contains("웅크리고"), "how to open it");
        assertFalse(y.contains("jige.vehicle"), "worn, not placed");
    }

    @Test
    void aBrokenSectionSaysWhat() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("j.slots", 50);
        y.set("j.stages", List.of("a", "b"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JigeSpec.parse(y.getConfigurationSection("j"), "t", m -> true))
                .getMessage().startsWith("jige.slots"));
        y.set("j.slots", 27);
        y.set("j.stages", List.of("a"));
        assertThrows(IllegalArgumentException.class, () -> JigeSpec.parse(y.getConfigurationSection("j"), "t", m -> true), "frame + a load at least");
        y.set("j.stages", List.of("a", "b"));
        y.set("j.slowdown", 0.8);
        assertThrows(IllegalArgumentException.class, () -> JigeSpec.parse(y.getConfigurationSection("j"), "t", m -> true));
        y.set("j.slowdown", 0.1);
        JigeSpec ok = JigeSpec.parse(y.getConfigurationSection("j"), "t", m -> true);
        assertEquals(27, ok.slots());
    }

    /** A 지게 whose item is lost is hidden from the lists, comes back when seen, and is given away with /탈것 양도. */
    @Test
    void aLostJigeIsHiddenAndAGivenOneIsTheNewOwners() {
        UUID other = UUID.randomUUID();
        JigeStore.Row a = store.create(owner, "Owner", 111, 1000).join();
        JigeStore.Row b = store.create(owner, "Owner", 222, 1000).join();
        store.seen(java.util.Map.of(a.id(), 111L, b.id(), 222L), owner.toString()).join();
        assertEquals(List.of(a.id()), store.missing(owner, java.util.Set.of(b.id()), 5000).join(), "/clear: a is not on them any more");
        assertEquals(List.of(b.id()), store.list(owner, 100).join().stream().map(JigeStore.Row::id).toList());
        assertEquals(1, store.goneCount(owner).join());
        assertNotNull(store.claim(a.id(), 111, 112, 6000).join(), "it turns up and is worn: it works");
        assertEquals(2, store.list(owner, 100).join().size(), "and is listed again");
        assertTrue(store.gone(b.id(), 222).join(), "b fell into the void");
        assertEquals(List.of(a.id()), store.list(null, 100).join().stream().map(JigeStore.Row::id).toList());
        assertEquals(List.of(a.id()), store.sweep(java.util.Map.of(), 7000).join(), "/관리자 탈것 정리: found nowhere");

        assertEquals(VehicleStore.Transfer.NOT_OWNER, store.transfer(a.id(), 112, other, other, "Other", 113, 8000).join());
        assertEquals(VehicleStore.Transfer.STALE, store.transfer(a.id(), 111, owner, other, "Other", 113, 8000).join(), "an old copy");
        byte[] load = {4, 5, 6};
        assertTrue(store.save(a.id(), 112, load, 8000).join());
        assertEquals(VehicleStore.Transfer.DONE, store.transfer(a.id(), 112, owner, other, "Other", 113, 8000).join());
        JigeStore.Row given = store.get(a.id()).join();
        assertEquals(other, given.owner());
        assertEquals(113, given.token(), "the item given away opens nothing; the new one does");
        assertArrayEquals(load, given.contents(), "the load goes with it");
        assertFalse(given.gone());
    }
}
