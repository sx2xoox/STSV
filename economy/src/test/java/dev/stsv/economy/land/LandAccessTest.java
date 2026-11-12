package dev.stsv.economy.land;

import dev.stsv.core.world.WorldRules.Kind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static dev.stsv.economy.land.LandAccess.Verdict.ALLOW;
import static dev.stsv.economy.land.LandAccess.Verdict.OTHERS_LAND;
import static dev.stsv.economy.land.LandAccess.Verdict.UNCLAIMED_BUILD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who may change or use what, in the 건축월드, the 야생월드 and other worlds. */
class LandAccessTest {

    private final UUID owner = UUID.randomUUID(), member = UUID.randomUUID(), stranger = UUID.randomUUID();
    private final Land land = new Land(1, owner, 0);
    private final Land town = new Land(2, null, 0);

    {
        land.members.put(member, 0L);
    }

    @Test
    void buildWorldOnlyYourOwnLand() {
        // inside a land: its people do everything, others nothing
        assertEquals(ALLOW, LandAccess.decide(Kind.BUILD, land, owner, false, true));
        assertEquals(ALLOW, LandAccess.decide(Kind.BUILD, land, member, false, true));
        assertEquals(OTHERS_LAND, LandAccess.decide(Kind.BUILD, land, stranger, false, true));
        assertEquals(OTHERS_LAND, LandAccess.decide(Kind.BUILD, land, stranger, false, false), "not even open their chests");
        assertEquals(OTHERS_LAND, LandAccess.decide(Kind.BUILD, town, owner, false, true), "the town is the server's");
        // outside lands: nobody breaks or places, everybody may use doors, chests and animals
        assertEquals(UNCLAIMED_BUILD, LandAccess.decide(Kind.BUILD, null, owner, false, true));
        assertEquals(ALLOW, LandAccess.decide(Kind.BUILD, null, owner, false, false));
        // operators always
        assertEquals(ALLOW, LandAccess.decide(Kind.BUILD, null, stranger, true, true));
        assertEquals(ALLOW, LandAccess.decide(Kind.BUILD, land, stranger, true, true));
    }

    @Test
    void wildWorldAndItsNetherAndEndAreFreeEvenOnOldClaims() {
        for (Kind k : new Kind[]{Kind.WILD, Kind.NETHER, Kind.END}) {
            assertEquals(ALLOW, LandAccess.decide(k, null, stranger, false, true), k.name());
            assertEquals(ALLOW, LandAccess.decide(k, land, stranger, false, true), "claims there are dormant: " + k);
            assertFalse(LandAccess.claimsCount(k));
            assertFalse(LandAccess.claimable(k));
            assertFalse(LandAccess.sheltered(k, land), "explosions and fire work there: " + k);
        }
    }

    @Test
    void otherWorldsKeepTheirOldClaimsButSellNoLand() {
        assertEquals(ALLOW, LandAccess.decide(Kind.OTHER, null, stranger, false, true), "an unclaimed other world (e.g. the unused default nether) is free");
        assertEquals(OTHERS_LAND, LandAccess.decide(Kind.OTHER, land, stranger, false, true), "an old claim still protects");
        assertEquals(ALLOW, LandAccess.decide(Kind.OTHER, land, member, false, true));
        assertTrue(LandAccess.claimsCount(Kind.OTHER));
        assertFalse(LandAccess.claimable(Kind.OTHER));
        assertTrue(LandAccess.claimable(Kind.BUILD));
    }

    @Test
    void natureChangesNothingInTheBuildWorldOrInLands() {
        assertTrue(LandAccess.sheltered(Kind.BUILD, null));
        assertTrue(LandAccess.sheltered(Kind.BUILD, land));
        assertTrue(LandAccess.sheltered(Kind.OTHER, land));
        assertFalse(LandAccess.sheltered(Kind.OTHER, null));
    }

    /** Vehicles: anywhere but another's land, the server's land (town, docks, terminals) and roads.avoid boxes. */
    @Test
    void vehiclesGoAnywhereButOthersAndTheServers() {
        assertEquals(LandAccess.VehicleSpot.ALLOW, LandAccess.vehicleSpot(null, stranger, false, false), "unclaimed (build world too) and the wild");
        assertEquals(LandAccess.VehicleSpot.ALLOW, LandAccess.vehicleSpot(land, owner, false, false), "my land");
        assertEquals(LandAccess.VehicleSpot.ALLOW, LandAccess.vehicleSpot(land, member, false, false), "the land I live on");
        assertEquals(LandAccess.VehicleSpot.OTHERS_LAND, LandAccess.vehicleSpot(land, stranger, false, false));
        assertEquals(LandAccess.VehicleSpot.SERVER_LAND, LandAccess.vehicleSpot(town, owner, false, false), "the town, a dock, a terminal");
        assertEquals(LandAccess.VehicleSpot.PROTECTED, LandAccess.vehicleSpot(null, owner, false, true), "a roads.avoid box");
        assertEquals(LandAccess.VehicleSpot.ALLOW, LandAccess.vehicleSpot(town, stranger, true, true), "operators");
        // blocks keep their rule: the unclaimed build world stays closed to building
        assertEquals(UNCLAIMED_BUILD, LandAccess.decide(Kind.BUILD, null, owner, false, true));
    }
}
