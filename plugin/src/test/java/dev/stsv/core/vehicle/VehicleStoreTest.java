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
