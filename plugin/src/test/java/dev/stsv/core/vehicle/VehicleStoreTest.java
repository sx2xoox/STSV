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
        assertTrue(store.delivered(id, 222, 3000).join());
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

        assertNull(store.place(id, 999, owner, "Owner", at(5, 5, 0), 444, 3000).join(), "a wrong token (a forged item)");
        VehicleStore.Row placed = store.place(id, 222, buyer, "Buyer", at(5, 5, 0), 444, 3000).join();
        assertNotNull(placed);
        assertEquals(buyer, placed.owner(), "whoever places the item owns the vehicle");
        assertNull(store.place(id, 222, owner, "Owner", at(6, 6, 0), 555, 3001).join(), "a copy of the same item: refused");
        assertNull(store.place(id, 444, owner, "Owner", at(6, 6, 0), 555, 3001).join(), "and it is out already");
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
        assertTrue(store.delivered(id, 77, 3000).join());
        assertFalse(store.delivered(id, 77, 3001).join(), "a second hand-over finds nothing");
        assertTrue(store.owed(owner).join().isEmpty());
        assertEquals(VehicleStore.State.ITEM, store.get(id).join().state());
    }

    @Test
    void reissuingMakesEveryOldItemWorthless() {
        long id = store.create("stsv:golf_cart", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
        store.takeIn(id, 10, false, 2000).join();
        assertTrue(store.reissue(id, 20, 3000).join(), "an admin re-issues a lost item");
        assertTrue(store.delivered(id, 20, 3001).join());
        assertNull(store.place(id, 10, owner, "Owner", at(0, 0, 0), 30, 4000).join(), "the lost item no longer works");
        assertNotNull(store.place(id, 20, owner, "Owner", at(0, 0, 0), 30, 4000).join());
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
            VehicleStore before = new VehicleStore(old);
            long id = before.create("stsv:light_truck", owner, "Owner", at(0, 0, 0), 1, 1000).join().id();
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
}
