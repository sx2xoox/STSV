package dev.stsv.core.vehicle;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vehicles in the database: placed, moved, picked up with a token, copies refused, the bed's load kept. */
class VehicleStoreTest {

    @TempDir
    Path dir;
    private Db db;
    private VehicleStore store;
    private final UUID world = UUID.randomUUID(), owner = UUID.randomUUID(), buyer = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        db = DatabaseModule.sqlite(dir.resolve("t.db"), Logger.getLogger("test"));
        store = new VehicleStore(db);
        store.init();
    }

    @AfterEach
    void close() {
        db.close();
    }

    private VehicleStore.Pos at(double x, double z, float yaw) {
        return new VehicleStore.Pos(world, "world", x, 64, z, yaw);
    }

    @Test
    void placedVehiclesComeBackAfterARestart() {
        VehicleStore.Row a = store.create("stsv:police_car", owner, "Owner", at(10.5, -3.25, 90), 111, 1000).join();
        assertEquals(VehicleStore.State.WORLD, a.state());
        store.move(a.id(), at(42.75, 7.5, 181.5f), 2000).join();
        // a "restart": a new store over the same database
        List<VehicleStore.Row> placed = new VehicleStore(db).placed();
        assertEquals(1, placed.size());
        VehicleStore.Row b = placed.getFirst();
        assertEquals("stsv:police_car", b.item());
        assertEquals(owner, b.owner());
        assertEquals(world, b.pos().world());
        assertEquals(42.75, b.pos().x(), 1e-9);
        assertEquals(64, b.pos().y(), 1e-9);
        assertEquals(7.5, b.pos().z(), 1e-9);
        assertEquals(181.5f, b.pos().yaw(), 1e-6);
    }

    /** Picked up while its owner quit (or filled their inventory): owed, so it is handed over on their next join, once. */
    @Test
    void aPickUpNobodyReceivedIsOwedNotLost() {
        long id = store.create("stsv:light_truck", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        assertTrue(store.takeIn(id, 222, false, 2000).join(), "an item now (state ITEM)");
        assertTrue(store.owed(owner).join().isEmpty(), "an ITEM row is never handed over by itself");
        assertFalse(store.owe(id, 999, 2001).join(), "another token: not this item");
        assertTrue(store.owe(id, 222, 2001).join(), "nobody got the item: owed");
        assertFalse(store.owe(id, 222, 2002).join(), "once");
        assertEquals(1, store.owed(owner).join().size(), "handed over on their next join");
        assertTrue(store.delivered(id, 222, null, 3000).join());
        assertFalse(store.owe(store.create("stsv:light_truck", owner, "Owner", at(1, 1, 0), 5, 1000).join().id(), 5, 3001).join(),
                "a vehicle in the world is never owed this way");
    }

    /** A wild reset makes its worlds again with the same names and new ids: what stood there is gone, not merely unloaded. */
    @Test
    void aWorldIsGoneWhenMadeAgainOrDeleted() {
        assertFalse(VehicleStore.worldGone(true, true, true), "its world is loaded");
        assertTrue(VehicleStore.worldGone(false, true, true), "a world of that name with another id: made again");
        assertTrue(VehicleStore.worldGone(false, false, false), "no such world and no folder: deleted");
        assertFalse(VehicleStore.worldGone(false, false, true), "only not loaded now (its folder is there): it waits");
    }

    @Test
    void pickedUpIsOneItemAndOnlyItsLatestCopyPlacesIt() {
        long id = store.create("stsv:light_truck", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        assertTrue(store.takeIn(id, 222, false, 2000).join(), "picked up: token 222 on its item");
        assertFalse(store.takeIn(id, 333, false, 2001).join(), "a second pick-up of the same vehicle does nothing");
        assertTrue(new VehicleStore(db).placed().isEmpty(), "not in the world any more (no copy left in a chunk)");

        assertEquals(VehicleStore.Placing.STALE, store.place(id, 999, owner, false, at(5, 5, 0), 444, 3000).join().outcome(),
                "a wrong token (a forged item)");
        VehicleStore.Placed other = store.place(id, 222, buyer, false, at(5, 5, 0), 444, 3000).join();
        assertEquals(VehicleStore.Placing.NOT_OWNER, other.outcome(), "귀속: someone else holding the item cannot place it");
        assertEquals("Owner", other.row().ownerName(), "and is told whose it is");
        VehicleStore.Row placed = store.place(id, 222, owner, false, at(5, 5, 0), 444, 3000).join().row();
        assertNotNull(placed);
        assertEquals(owner, placed.owner(), "its owner places it");
        assertEquals(VehicleStore.Placing.STALE, store.place(id, 222, owner, false, at(6, 6, 0), 555, 3001).join().outcome(),
                "a copy of the same item: refused");
        assertEquals(VehicleStore.Placing.STALE, store.place(id, 444, owner, false, at(6, 6, 0), 555, 3001).join().outcome(),
                "and it is out already");
    }

    @Test
    void theBedsLoadStaysWithTheVehicle() {
        long id = store.create("stsv:light_truck", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        byte[] load = {1, 2, 3, 4, 5, 0, 127, -128};
        store.cargo(id, load, 2000).join();
        assertArrayEquals(load, new VehicleStore(db).placed().getFirst().cargo(), "round trip");
        assertTrue(store.takeIn(id, 7, true, 3000).join(), "sent back by an admin");
        assertArrayEquals(load, store.get(id).join().cargo(), "the load went with it");
        store.cargo(id, null, 4000).join();
        assertNull(store.get(id).join().cargo(), "emptied");
    }

    @Test
    void anOwedVehicleIsHandedOverOnce() {
        long id = store.create("stsv:golf_cart", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        assertTrue(store.takeIn(id, 77, true, 2000).join());
        List<VehicleStore.Row> owed = store.owed(owner).join();
        assertEquals(1, owed.size());
        assertEquals(77, owed.getFirst().token());
        assertTrue(store.owed(buyer).join().isEmpty());
        assertTrue(store.delivered(id, 77, null, 3000).join());
        assertFalse(store.delivered(id, 77, null, 3001).join(), "a second hand-over finds nothing");
        assertTrue(store.owed(owner).join().isEmpty());
        assertEquals(VehicleStore.State.ITEM, store.get(id).join().state());
    }

    @Test
    void reissuingMakesEveryOldItemWorthless() {
        long id = store.create("stsv:golf_cart", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        store.takeIn(id, 10, false, 2000).join();
        assertTrue(store.reissue(id, 20, 3000).join(), "an admin re-issues a lost item");
        assertTrue(store.delivered(id, 20, null, 3001).join());
        assertEquals(VehicleStore.Placing.STALE, store.place(id, 10, owner, false, at(0, 0, 0), 30, 4000).join().outcome(),
                "the lost item no longer works");
        assertEquals(VehicleStore.Placing.PLACED, store.place(id, 20, owner, false, at(0, 0, 0), 30, 4000).join().outcome());
        assertFalse(store.reissue(id, 40, 5000).join(), "not while it is in the world (send it back instead)");
    }

    /** The hand cart's 270 slots: a big load (stacks with names, books, shulker contents) saved whole and read back. */
    @Test
    void aFull270SlotBedIsKeptWhole() {
        long id = store.create("stsv:hand_cart", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        byte[] load = new byte[270 * 2048];   // ~2 KB per slot, far more than plain stacks take
        new java.util.Random(7).nextBytes(load);
        store.cargo(id, load, 2000).join();
        assertArrayEquals(load, new VehicleStore(db).placed().getFirst().cargo(), "after a restart, byte for byte");
        assertTrue(store.takeIn(id, 5, false, 3000).join(), "picked up (an admin's 회수 keeps the load the same way)");
        assertArrayEquals(load, store.get(id).join().cargo(), "the load stays with the row in any state");
    }

    /** The biggest bed a vehicle may have (810 slots, the box truck's 720 and room): saved whole and read back. */
    @Test
    void aFull810SlotBedIsKeptWhole() {
        long id = store.create("stsv:box_truck", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        byte[] load = new byte[VehicleSpec.Cargo.PAGE * VehicleSpec.Cargo.MAX_PAGES * 2048];   // ~2 KB per slot: 1.6 MB
        new java.util.Random(8).nextBytes(load);
        store.cargo(id, load, 2000).join();
        assertArrayEquals(load, new VehicleStore(db).placed().getFirst().cargo(), "after a restart, byte for byte");
    }

    /** /탈것 권한: allowed players are kept per owner, survive a restart, and are taken back. */
    @Test
    void grantsRoundTrip() {
        UUID friend = UUID.randomUUID(), other = UUID.randomUUID();
        assertTrue(store.grant(owner, friend, "Friend", 1000).join(), "newly allowed");
        assertFalse(store.grant(owner, friend, "Friend2", 1500).join(), "already allowed: only the name is refreshed");
        assertTrue(store.grant(owner, other, "Other", 2000).join());
        assertTrue(store.grant(buyer, friend, "Friend", 2500).join(), "another owner's list is its own");

        List<VehicleStore.Grant> mine = store.grants(owner).join();
        assertEquals(List.of(friend, other), mine.stream().map(VehicleStore.Grant::player).toList(), "oldest first");
        assertEquals("Friend2", mine.getFirst().playerName());
        assertEquals(1000, mine.getFirst().created());

        // a restart: everything loads again
        VehicleStore again = new VehicleStore(db);
        again.init();
        List<VehicleStore.Grant> all = again.grants();
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(g -> g.owner().equals(buyer) && g.player().equals(friend)));

        assertTrue(store.revoke(owner, friend).join());
        assertFalse(store.revoke(owner, friend).join(), "already taken back");
        assertEquals(List.of(other), store.grants(owner).join().stream().map(VehicleStore.Grant::player).toList());
        assertEquals(1, store.grants(buyer).join().size(), "the other owner's grant stays");
    }

    /** A database from before /탈것 권한 (vehicles schema 1) gets the grant table on start; its vehicles stay. */
    @Test
    void anOlderDatabaseGetsTheGrantTable(@TempDir Path older) throws Exception {
        Db old = DatabaseModule.sqlite(older.resolve("old.db"), Logger.getLogger("test"));
        try {
            old.migrate("vehicles", List.of(List.of("CREATE TABLE IF NOT EXISTS stsv_vehicle (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "item VARCHAR(128) NOT NULL, owner CHAR(36) NOT NULL, owner_name VARCHAR(16) NOT NULL, state VARCHAR(8) NOT NULL, "
                    + "token BIGINT NOT NULL, world CHAR(36), world_name VARCHAR(64), x DOUBLE, y DOUBLE, z DOUBLE, yaw DOUBLE, cargo BLOB, "
                    + "created BIGINT NOT NULL, updated BIGINT NOT NULL)")));
            long id = old.sync(c -> Db.insert(c, "INSERT INTO stsv_vehicle (item, owner, owner_name, state, token, world, world_name, x, y, z, yaw, "
                    + "created, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "stsv:light_truck", owner.toString(), "Owner", "world", 1L,
                    world.toString(), "world", 0.0, 64.0, 0.0, 0.0, 1000L, 1000L));
            VehicleStore after = new VehicleStore(old);
            after.init();
            assertTrue(after.grant(owner, buyer, "Buyer", 2000).join());
            assertEquals(1, after.grants().size());
            assertEquals(id, after.placed().getFirst().id(), "the vehicles are still there");
        } finally {
            old.close();
        }
    }

    @Test
    void listAndDelete() {
        long a = store.create("stsv:police_car", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        store.create("stsv:golf_cart", buyer, "Buyer", at(0, 0, 0), 1, 1000).join();
        assertEquals(2, store.list(null, 50).join().size());
        assertEquals(1, store.list(owner, 50).join().size());
        assertTrue(store.delete(a).join());
        assertFalse(store.delete(a).join());
        assertEquals(1, store.list(null, 50).join().size());
    }

    /** An admin places anyone's item for its owner (it stays theirs). */
    @Test
    void anAdminPlacesItForItsOwner() {
        long id = store.create("stsv:golf_cart", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        store.takeIn(id, 2, false, 2000).join();
        VehicleStore.Placed p = store.place(id, 2, buyer, true, at(1, 1, 0), 3, 3000).join();
        assertEquals(VehicleStore.Placing.PLACED, p.outcome());
        assertEquals(owner, p.row().owner(), "placing never changes the owner");
    }

    /**
     * The bug: test vehicles deleted (an item thrown away, /clear, deleted in creative) stayed in every list. An item no
     * longer on the player it was last seen with is GONE (hidden), but comes back the moment it is seen, and still places.
     */
    @Test
    void anItemMissingFromItsHolderIsHiddenButComesBack() {
        long a = store.create("stsv:golf_cart", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        long b = store.create("stsv:golf_cart", owner, "Owner", at(1, 0, 0), 1, 1000).join().id();
        store.takeIn(a, 10, false, 2000).join();
        store.takeIn(b, 20, false, 2000).join();
        assertEquals(2, store.seen(java.util.Map.of(a, 10L, b, 20L), owner.toString()).join(), "both seen on the owner");
        assertEquals(owner.toString(), store.get(a).join().holder());
        // a look at the owner later finds only b: a is gone (deleted in creative, /clear)
        assertEquals(List.of(a), store.missing(owner, java.util.Set.of(b), 10_000).join());
        assertEquals(VehicleStore.State.GONE, store.get(a).join().state());
        assertEquals(List.of(b), store.list(owner, 100).join().stream().map(VehicleStore.Row::id).toList(), "the list shows only what is there");
        assertEquals(1, store.goneCount(owner).join());
        assertEquals(List.of(a), store.listGone(null, 100).join().stream().map(VehicleStore.Row::id).toList());
        // it was in a chest after all: seen again, listed again
        assertEquals(1, store.seen(java.util.Map.of(a, 10L), VehicleStore.ELSEWHERE).join());
        assertEquals(VehicleStore.State.ITEM, store.get(a).join().state());
        assertEquals(2, store.list(owner, 100).join().size());
        // a lost one still places when it turns up (its token is kept)
        store.missing(owner, java.util.Set.of(), 10_000).join();
        assertEquals(VehicleStore.State.GONE, store.get(b).join().state());
        assertEquals(VehicleStore.Placing.PLACED, store.place(b, 20, owner, false, at(3, 3, 0), 21, 11_000).join().outcome());
    }

    @Test
    void missingSparesItemsElsewhereRecentOnesAndOldCopies() {
        long id = store.create("stsv:golf_cart", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        store.takeIn(id, 10, false, 5000).join();
        store.seen(java.util.Map.of(id, 10L), owner.toString()).join();
        assertTrue(store.missing(owner, java.util.Set.of(), 4000).join().isEmpty(), "changed after `before`: on its way, not missing");
        store.seen(java.util.Map.of(id, 10L), VehicleStore.ELSEWHERE).join();
        assertTrue(store.missing(owner, java.util.Set.of(), 10_000).join().isEmpty(), "put in a chest: not the player's to lose");
        assertEquals(0, store.seen(java.util.Map.of(id, 99L), owner.toString()).join(), "an old copy says nothing about the vehicle");
        assertEquals(VehicleStore.ELSEWHERE, store.get(id).join().holder());
        assertFalse(store.gone(id, 99).join(), "an old copy burning does not hide it");
        assertTrue(store.gone(id, 10).join(), "its item burnt in lava: gone");
        assertTrue(store.list(null, 100).join().isEmpty());
    }

    /** /관리자 탈것 정리: items found nowhere are hidden; placed and owed vehicles, and items found, stay. */
    @Test
    void sweepHidesWhatIsNowhere() {
        long placed = store.create("stsv:golf_cart", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        long found = store.create("stsv:golf_cart", owner, "Owner", at(1, 0, 0), 1, 1000).join().id();
        long lost = store.create("stsv:golf_cart", owner, "Owner", at(2, 0, 0), 1, 1000).join().id();
        long owed = store.create("stsv:golf_cart", owner, "Owner", at(3, 0, 0), 1, 1000).join().id();
        long fresh = store.create("stsv:golf_cart", owner, "Owner", at(4, 0, 0), 1, 1000).join().id();
        store.takeIn(found, 2, false, 2000).join();
        store.takeIn(lost, 3, false, 2000).join();
        store.takeIn(owed, 4, true, 2000).join();
        store.takeIn(fresh, 5, false, 9000).join();
        assertEquals(List.of(lost), store.sweep(java.util.Map.of(found, 2L), 5000).join());
        assertEquals(List.of(placed, found, owed, fresh), store.list(owner, 100).join().stream().map(VehicleStore.Row::id).toList());
    }

    /** /탈것 양도: a placed vehicle stays where it is and is the new owner's; only its owner (or an admin) gives it. */
    @Test
    void givingAPlacedVehicle() {
        long id = store.create("stsv:golf_cart", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        assertEquals(VehicleStore.Transfer.NOT_OWNER, store.transfer(id, buyer, buyer, "Buyer", 2000).join());
        assertEquals(VehicleStore.Transfer.DONE, store.transfer(id, owner, buyer, "Buyer", 2000).join());
        VehicleStore.Row r = store.get(id).join();
        assertEquals(buyer, r.owner());
        assertEquals("Buyer", r.ownerName());
        assertEquals(VehicleStore.State.WORLD, r.state());
        assertEquals(VehicleStore.Transfer.DONE, store.transfer(id, null, owner, "Owner", 3000).join(), "an admin");
        store.takeIn(id, 9, false, 4000).join();
        assertEquals(VehicleStore.Transfer.STALE, store.transfer(id, owner, buyer, "Buyer", 5000).join(), "not in the world: give the item");
    }

    /** /탈것 양도 of an item: the old item stops working, a new one (owed) is the new owner's; copies do nothing. */
    @Test
    void givingAnItem() {
        long id = store.create("stsv:golf_cart", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        store.takeIn(id, 10, false, 2000).join();
        assertEquals(VehicleStore.Transfer.NOT_OWNER, store.transferItem(id, 10, buyer, owner, "Owner", 11, 3000).join(),
                "holding someone else's item gives nothing");
        assertEquals(VehicleStore.Transfer.STALE, store.transferItem(id, 99, owner, buyer, "Buyer", 11, 3000).join(), "an old copy");
        assertEquals(VehicleStore.Transfer.DONE, store.transferItem(id, 10, owner, buyer, "Buyer", 11, 3000).join());
        VehicleStore.Row r = store.get(id).join();
        assertEquals(buyer, r.owner());
        assertEquals(VehicleStore.State.OWED, r.state(), "handed over to them (mailbox, inventory, next join)");
        assertEquals(1, store.owed(buyer).join().size());
        assertEquals(VehicleStore.Placing.STALE, store.place(id, 10, owner, true, at(1, 1, 0), 12, 4000).join().outcome(),
                "the item given away no longer works");
        assertTrue(store.delivered(id, 11, VehicleStore.ELSEWHERE, 5000).join());
        assertEquals(VehicleStore.Placing.NOT_OWNER, store.place(id, 11, owner, false, at(1, 1, 0), 12, 6000).join().outcome());
        assertEquals(VehicleStore.Placing.PLACED, store.place(id, 11, buyer, false, at(1, 1, 0), 12, 6000).join().outcome());
        // a shop-bought item (no row yet) given away: a new row, owed to them
        VehicleStore.Row fresh = store.createOwed("stsv:hand_cart", buyer, "Buyer", 50, 7000).join();
        assertEquals(VehicleStore.State.OWED, fresh.state());
        assertEquals(buyer, fresh.owner());
        assertEquals(50, fresh.token());
    }

    /** A database from before the holder column gets it (and its old rows keep working). */
    @Test
    void anOlderDatabaseGetsTheHolderColumn(@TempDir Path older) throws Exception {
        Db old = DatabaseModule.sqlite(older.resolve("o.db"), Logger.getLogger("test"));
        try {
            old.migrate("vehicles", List.of(List.of(
                    "CREATE TABLE IF NOT EXISTS stsv_vehicle (id INTEGER PRIMARY KEY AUTOINCREMENT, item VARCHAR(128) NOT NULL, owner CHAR(36) NOT NULL, "
                            + "owner_name VARCHAR(16) NOT NULL, state VARCHAR(8) NOT NULL, token BIGINT NOT NULL, world CHAR(36), "
                            + "world_name VARCHAR(64), x DOUBLE, y DOUBLE, z DOUBLE, yaw DOUBLE, cargo BLOB, "
                            + "created BIGINT NOT NULL, updated BIGINT NOT NULL)")));
            old.sync(c -> Db.update(c, "INSERT INTO stsv_vehicle (item, owner, owner_name, state, token, created, updated) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    "stsv:golf_cart", owner.toString(), "Owner", "item", 5L, 1L, 1L));
            VehicleStore after = new VehicleStore(old);
            after.init();
            VehicleStore.Row r = after.list(owner, 10).join().getFirst();
            assertNull(r.holder(), "never seen yet");
            assertTrue(after.missing(owner, java.util.Set.of(), 10_000).join().isEmpty(), "an item never seen with them is never missing");
        } finally {
            old.close();
        }
    }
}
