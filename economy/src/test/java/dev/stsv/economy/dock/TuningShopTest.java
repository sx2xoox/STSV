package dev.stsv.economy.dock;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tuning shop as a building (DockLayout.tuning): what /관리자 튜닝샵 설치 puts into the world. */
class TuningShopTest {

    private static String at(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    @Test
    void aGarageAVehicleDrivesInto() throws Exception {
        DockLayout.Layout l = DockLayout.of(DockLayout.Kind.TUNING, "본점");
        assertEquals(DockLayout.Kind.TUNING, l.kind());
        assertEquals("본점 튜닝샵", DockLayout.tuningTitle("본점"));
        Set<String> solid = new HashSet<>();
        for (DockLayout.Block b : l.blocks()) {
            assertTrue(l.bounds().contains(b.x(), b.y(), b.z()), "inside its bounds: " + b);
            if (b.y() >= 0 && !b.data().equals("minecraft:air")) solid.add(at(b.x(), b.y(), b.z()));
            // nothing a player could open or use but the counter: no containers, no anvils, no beds
            for (String no : List.of("chest", "barrel", "shulker", "anvil", "grindstone", "furnace", "hopper", "dispenser", "dropper", "crafting", "loom",
                    "stonecutter", "brewing", "bed", "door", "lever", "button")) {
                assertFalse(b.data().contains(no), b.data() + " at " + at(b.x(), b.y(), b.z()));
            }
        }
        // the way in: the door (11 wide, 5 high) and a lane from it to the far end of the bay, clear to the height of the tallest vehicle and more
        for (int x = -DockLayout.S_DOOR; x <= DockLayout.S_DOOR; x++) {
            for (int y = 0; y < DockLayout.S_DOOR; y++) assertFalse(solid.contains(at(x, y, 1)), "the door is open at " + at(x, y, 1));
        }
        for (int x = -4; x <= 4; x++) {
            for (int z = -3; z <= DockLayout.S_BAY.maxZ() + 1; z++) {
                for (int y = 0; y <= 4; y++) assertFalse(solid.contains(at(x, y, z)), "the lane is clear at " + at(x, y, z));
            }
        }
        assertTrue(DockLayout.S_DOOR >= 4, "the monster off-roader is 3.25 high");
        // walls all round but the door, a roof over all of it
        int walls = 0;
        for (int z = 2; z < DockLayout.S_DEPTH; z++) {
            for (int x : new int[]{-DockLayout.S_HALF, DockLayout.S_HALF}) if (solid.contains(at(x, 1, z))) walls++;
        }
        assertEquals(2 * (DockLayout.S_DEPTH - 2), walls, "both side walls are whole at knee height (the windows are higher)");
        for (int x = -DockLayout.S_HALF; x <= DockLayout.S_HALF; x++) {
            assertTrue(solid.contains(at(x, 1, DockLayout.S_DEPTH)), "the back wall");
            for (int z = 1; z <= DockLayout.S_DEPTH; z++) assertTrue(solid.contains(at(x, DockLayout.S_WALL + 1, z)), "the roof at " + x + ", " + z);
        }
        // lit inside: lights in the ceiling
        assertTrue(l.blocks().stream().filter(b -> b.y() == DockLayout.S_WALL + 1 && b.data().contains("sea_lantern")).count() >= 8);

        // the counter: the smith's table just inside the door, reachable from the lane, in the hall
        assertEquals("minecraft:smithing_table", l.counter().data());
        assertTrue(DockLayout.S_HALL.contains(l.counter().x(), l.counter().y(), l.counter().z()));
        assertTrue(solid.contains(at(l.counter().x(), 0, l.counter().z())));
        assertTrue(Math.abs(l.counter().x()) > 4 && l.counter().z() <= 6, "beside the lane, near the door");
        // the zone: the whole hall inside the walls, so a vehicle parked anywhere in it can be tuned; not the forecourt
        assertEquals(List.of(DockLayout.S_HALL), l.zones());
        assertTrue(DockLayout.S_HALL.contains(0, 0, 10) && DockLayout.S_HALL.contains(7, 0, 18) && !DockLayout.S_HALL.contains(0, 0, -1));
        assertTrue(DockLayout.S_HALL.minX() > -DockLayout.S_HALF && DockLayout.S_HALL.maxX() < DockLayout.S_HALF);
        // the work bay is in the hall, marked with hazard stripes
        assertTrue(DockLayout.S_HALL.contains(DockLayout.S_BAY.minX(), 0, DockLayout.S_BAY.minZ()) && DockLayout.S_HALL.contains(DockLayout.S_BAY.maxX(), 0, DockLayout.S_BAY.maxZ()));
        long stripes = l.blocks().stream().filter(b -> b.y() == -1 && DockLayout.S_BAY.contains(b.x(), -1, b.z())
                && (b.data().contains("yellow_concrete") || b.data().contains("black_concrete"))).count();
        assertTrue(stripes >= 20, stripes + " stripe blocks");
        // a sign outside, by the door, that says what to do
        assertEquals(1, l.signs().size());
        assertTrue(l.signs().getFirst().z() < 1 && String.join(" ", l.signs().getFirst().lines()).contains("튜닝"));
        // foundations under the whole lot, and its floor
        assertEquals((2 * DockLayout.S_LOT + 1) * (DockLayout.S_DEPTH + 5), l.columns().size());
        // small enough for one go: a few thousand blocks
        assertTrue(l.blocks().size() < 4000, l.blocks().size() + " blocks");
        DockTest.export(DockLayout.Kind.TUNING, l);   // for the preview (tools/assetgen/tuning_shop_preview.py)
    }

    @Test
    void itStandsWhereTheAdminStoodFacingIn() {
        // facing 0 = south (+z): the door is in front of the admin, the hall beyond it
        Dock shop = new Dock("본점", DockLayout.Kind.TUNING, "world", 100, 64, 200, 0, "", 0);
        assertTrue(shop.inZone("world", 100.5, 64, 210.5), "in the hall");
        assertFalse(shop.inZone("world", 100.5, 64, 199.5), "the forecourt is not the shop");
        assertFalse(shop.inZone("other", 100.5, 64, 210.5));
        int[] counter = shop.counter();
        assertTrue(shop.isCounter("world", counter[0], counter[1], counter[2]));
        assertFalse(shop.isCounter("world", counter[0] + 1, counter[1], counter[2]));
        // turned to face east (+x): the hall runs along x
        Dock east = new Dock("동쪽", DockLayout.Kind.TUNING, "world", 100, 64, 200, 3, "", 0);
        assertTrue(east.inZone("world", 110.5, 64, 200.5));
        assertFalse(east.inZone("world", 100.5, 64, 210.5));
        // it claims the chunks its lot touches: a lot 25 x 25 is at most 3 x 3 chunks
        assertTrue(shop.chunksTouched().size() >= 4 && shop.chunksTouched().size() <= 9, shop.chunksTouched().size() + " chunks");
    }
}
