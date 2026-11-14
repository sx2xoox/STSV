package dev.stsv.economy.dock;

import dev.stsv.economy.fishing.NetSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 위판 ({@link FishAuction}): what a boat's hold has to sell at a harbour: only the crates that boat hauled. */
class FishAuctionTest {

    private static final NetSpec NET = new NetSpec(List.of(
            new NetSpec.Zone("coast", "연안", 0, "economy:catch_coast", 50, 10),
            new NetSpec.Zone("far", "원양", 2000, "economy:catch_far", 110, 10)), Map.of("stsv:trawler", 1), 0.08, 5);

    private static Object[] slot(int slot, String id, int count, long vessel) {
        return new Object[]{slot, id, count, vessel};
    }

    @Test
    void onlyTheCratesThisBoatHauledAreSold() {
        List<Object[]> hold = List.of(
                slot(0, "economy:catch_far", 64, 7L),
                slot(1, "economy:catch_far", 20, 7L),
                slot(2, "economy:catch_coast", 5, 7L),
                slot(3, "economy:catch_far", 64, 8L),      // hauled by another boat, carried over
                slot(4, "economy:catch_coast", 3, 0L),     // handed out by an admin: nobody hauled it
                slot(5, "economy:mackerel", 64, 0L),       // a fish from a rod: not the auction's
                slot(9, "minecraft:cod", 10, 7L));
        FishAuction.Lot lot = FishAuction.lot(NET, 7L, hold);
        assertEquals(Set.of(0, 1, 2), lot.slots());
        assertEquals(Map.of("economy:catch_far", 84, "economy:catch_coast", 5), lot.crates());
        assertEquals(67, lot.foreign());
        assertEquals(84 * 110 + 5 * 50, NET.worth(lot.crates()));
        // the same hold in the other boat: only its own
        FishAuction.Lot other = FishAuction.lot(NET, 8L, hold);
        assertEquals(Set.of(3), other.slots());
        assertEquals(64 * 110, NET.worth(other.crates()));
        // nothing of its own: nothing to sell (and it says the rest is foreign)
        FishAuction.Lot none = FishAuction.lot(NET, 99L, hold);
        assertTrue(none.empty());
        assertEquals(84 + 5 + 64 + 3, none.foreign());
        assertTrue(FishAuction.lot(NET, 7L, List.of()).empty());
        // without the seas (no net: section) nothing is a crate
        assertTrue(FishAuction.lot(NetSpec.NONE, 7L, hold).empty());
    }
}
