package dev.stsv.economy.dock;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import dev.stsv.economy.road.RoadOrigin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 출발 터미널: its layout, the inspected trip (manifest, seal, cancel, void, the odometer rule), kept over a restart. */
class TerminalTest {

    @TempDir
    Path dir;

    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    static List<DockGoods.Line> lines(Object... idCount) {
        List<DockGoods.Line> out = new java.util.ArrayList<>();
        for (int i = 0; i < idCount.length; i += 2) out.add(new DockGoods.Line((String) idCount[i], (int) idCount[i + 1], 10, 10.0 * (int) idCount[i + 1]));
        return out;
    }

    static CargoTrip sealedTrip(String dock, String manifest, double straight) {
        return new CargoTrip(7, 42, OWNER, "본사", dock, manifest, 6930, 1000L, 120, straight, 5000L, CargoTrip.Status.SEALED, 0);
    }

    @Test
    void theManifestIsTheGoodsInAFixedOrder() {
        String m = CargoTrip.manifest(lines("economy:kimchi", 64, "economy:apple_jam", 10, "economy:kimchi", 36));
        assertEquals("economy:apple_jam:10;economy:kimchi:100", m, "merged and sorted");
        assertEquals(Map.of("economy:apple_jam", 10, "economy:kimchi", 100), CargoTrip.lines(m));
        assertEquals("economy:apple_jam x10, economy:kimchi x100", CargoTrip.words(m));
        assertEquals("", CargoTrip.manifest(List.of()));
        assertTrue(CargoTrip.lines("").isEmpty());
    }

    @Test
    void itSellsOnlyAtItsDockUnchangedAndDrivenFarEnough() {
        String m = CargoTrip.manifest(lines("economy:kimchi", 100));
        CargoTrip t = sealedTrip("서해", m, 7000);
        assertEquals(5600, t.needed(), 1e-9, "80 % of the straight way");
        // at another dock: never (multiple docks: the one it was sealed for)
        assertEquals(CargoTrip.Check.OTHER_DOCK, t.check("동해", 1000L, m, 120 + 9000));
        // the load changed since inspection (another time of change, or other goods): void
        assertEquals(CargoTrip.Check.MISMATCH, t.check("서해", 2000L, m, 120 + 9000));
        assertEquals(CargoTrip.Check.MISMATCH, t.check("서해", 1000L, CargoTrip.manifest(lines("economy:kimchi", 99)), 120 + 9000));
        // the odometer: from where it was at inspection
        assertEquals(CargoTrip.Check.SHORT, t.check("서해", 1000L, m, 120 + 5599));
        assertEquals(5599, t.driven(120 + 5599), 1e-9);
        assertEquals(CargoTrip.Check.OK, t.check("서해", 1000L, m, 120 + 5600));
        assertEquals(CargoTrip.Check.OK, t.check("서해해".substring(0, 2), 1000L, m, 120 + 8000), "the dock's name in any case");
        assertEquals(0, t.driven(50), 1e-9, "never negative");
    }

    @Test
    void aTripIsSealedUntilSoldCancelledOrVoid() {
        CargoTrip t = sealedTrip("서해", "economy:kimchi:100", 7000);
        assertTrue(t.sealedNow());
        for (CargoTrip.Status s : List.of(CargoTrip.Status.DONE, CargoTrip.Status.CANCELLED, CargoTrip.Status.VOID)) {
            CargoTrip e = t.end(s, 9000);
            assertFalse(e.sealedNow(), s + ": unsealed");
            assertEquals(9000, e.ended());
            assertEquals(t.manifest(), e.manifest(), "what was sealed stays on record");
        }
    }

    @Test
    void tripsAreKeptOverARestart() throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("t.db"), Logger.getLogger("test"));
        try {
            TripStore s = new TripStore(db);
            s.init();
            CargoTrip a = s.open(new CargoTrip(0, 42, OWNER, "본사", "서해", "economy:kimchi:100", 6930, 1000L, 120.5, 7012.25, 5000L, CargoTrip.Status.SEALED, 0));
            CargoTrip b = s.open(new CargoTrip(0, -3, OWNER, "북부", "동해", "economy:ruby_ring:10", 2079, 2000L, 0, 6100, 6000L, CargoTrip.Status.SEALED, 0));
            assertTrue(a.id() > 0 && b.id() > a.id(), "ids given");
            s.end(b.end(CargoTrip.Status.CANCELLED, 7000)).join();
            TripStore again = new TripStore(db);
            again.init();
            List<CargoTrip> open = again.sealed();
            assertEquals(List.of(a), open, "the sealed one comes back exactly (a vehicle parked half way, a restart)");
            List<CargoTrip> all = again.latest(10);
            assertEquals(2, all.size());
            assertEquals(CargoTrip.Status.CANCELLED, all.getFirst().status(), "newest first");
            assertEquals(-3, all.getFirst().vehicle(), "a 지게's id (negative)");
            // terminals are kept beside the docks, a table of their own
            DockStore ds = new DockStore(db);
            ds.init();
            Dock t = new Dock("본사", DockLayout.Kind.TERMINAL, "world", 100, 64, -20, 2, "6,-2;6,-3", 10);
            ds.saveTerminal(t).join();
            ds.save(new Dock("서해", DockLayout.Kind.PORT, "world", 7000, 62, 10, 3, "", 20)).join();
            assertEquals(List.of(t), ds.terminals());
            assertEquals(1, ds.all().size(), "not a dock");
            assertTrue(ds.deleteTerminal("본사").join());
            assertTrue(ds.terminals().isEmpty());
        } finally {
            db.close();
        }
    }

    @Test
    void theTerminalHasItsBayBoothAndExit() throws Exception {
        DockLayout.Layout l = DockLayout.of(DockLayout.Kind.TERMINAL, "본사");
        assertEquals(DockLayout.Kind.TERMINAL, l.kind());
        assertNull(DockLayout.Kind.of("출발 터미널"), "never a dock of that kind (/관리자 하역장 설치)");
        assertEquals(List.of(DockLayout.Kind.LAND, DockLayout.Kind.PORT), DockLayout.Kind.docks());
        DockLayout.Box b = l.bounds(), bay = DockLayout.BAY;
        for (DockLayout.Block x : l.blocks()) assertTrue(b.contains(x.x(), x.y(), x.z()), "block " + x);
        assertEquals(List.of(bay), l.zones());
        // the bay: clear to the canopy (6 up), painted lines round it, lights in the canopy, a lift gate at its exit
        for (DockLayout.Block x : l.blocks()) {
            boolean in = x.x() >= bay.minX() && x.x() <= bay.maxX() && x.z() >= bay.minZ() && x.z() <= bay.maxZ();
            if (in && x.y() >= 0 && x.y() <= 5) throw new AssertionError("something stands in the bay: " + x);
        }
        assertTrue(l.blocks().stream().anyMatch(x -> x.y() == 6 && x.data().equals("minecraft:sea_lantern")), "canopy lights");
        assertTrue(l.blocks().stream().anyMatch(x -> x.y() == -1 && x.x() == bay.minX() && x.z() == bay.minZ() + 1 && x.data().equals("minecraft:yellow_concrete")));
        assertTrue(l.blocks().stream().anyMatch(x -> x.data().equals("minecraft:red_concrete") && x.z() < bay.minZ()), "the lift gate's arm");
        // the counter beside the bay, at the booth
        DockLayout.Block c = l.counter();
        assertTrue(c.x() < bay.minX() && c.x() >= bay.minX() - 3 && c.z() >= bay.minZ() && c.z() <= bay.maxZ(), "counter by the bay");
        assertTrue(l.blocks().stream().anyMatch(x -> x.x() == c.x() && x.y() == c.y() && x.z() == c.z() && x.data().equals(c.data())));
        assertTrue(l.blocks().stream().anyMatch(x -> x.data().equals("minecraft:glass") && x.x() == -8), "the booth's window to the bay");
        // the exit: open at the front middle (the 국도 starts there), the sign beside it
        for (DockLayout.Block x : l.blocks()) assertFalse(x.z() == -1 && x.y() == 0 && Math.abs(x.x()) <= 6, "the exit is open: " + x);
        assertEquals(1, l.signs().size());
        assertTrue(l.signs().getFirst().lines().contains("본사"));
        DockTest.export(DockLayout.Kind.TERMINAL, l);   // for the preview (tools/assetgen/terminal_preview.py)
        // the road starts at its gate, straight out of it
        Dock t = new Dock("본사", DockLayout.Kind.TERMINAL, "world", 100, 64, -20, 0, "", 0);
        int[] g = t.gate();
        assertEquals(List.of(100, 63, -21), List.of(g[0], g[1], g[2]), "the front edge, the floor's level");
        int[] f = Dock.forward(t.facing());
        RoadOrigin o = new RoadOrigin("world", g[0] + 0.5, g[1], g[2] + 0.5, -f[0], -f[1]);
        assertTrue(o.directed());
        assertEquals(o, RoadOrigin.read(o.write()), "kept with its way out");
        assertEquals(-1, o.dz(), 1e-9, "out of the exit: away from the bay");
    }

    @Test
    void severalTerminalsAndDocksMeasureEachWay() {
        Dock north = new Dock("북부", DockLayout.Kind.TERMINAL, "world", 0, 64, -3000, 0, "", 0);
        Dock south = new Dock("남부", DockLayout.Kind.TERMINAL, "world", 0, 64, 3000, 2, "", 0);
        Dock sea = new Dock("서해", DockLayout.Kind.PORT, "world", -7000, 62, 0, 1, "", 0);
        Dock far = new Dock("다른곳", DockLayout.Kind.LAND, "nether", 0, 64, 0, 0, "", 0);
        double a = DockModule.straight(north, sea), b = DockModule.straight(south, sea);
        assertTrue(a > 7000 && a < 8000 && b > 7000 && b < 8000, a + ", " + b);
        assertTrue(Double.isInfinite(DockModule.straight(north, far)), "another world: never");
        assertTrue(north.anyInZone("world", List.of(DockModule.bay(north))), "its bay's middle is in its bay");
        assertFalse(south.anyInZone("world", List.of(DockModule.bay(north))));
    }
}
