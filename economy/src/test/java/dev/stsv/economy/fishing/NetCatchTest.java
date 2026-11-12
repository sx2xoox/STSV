package dev.stsv.economy.fishing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 통통배's net (the core's 작업기, {@link FishingModule#netCatch}): commons and uncommons of the sea only. */
class NetCatchTest {

    @Test
    void theNetsUncommonShareIsARodsPlusTheFishersTrapBonus() {
        assertEquals(0.35, FishingModule.netUncommon(0), 1e-9, "a rod's common 65 : uncommon 35");
        assertEquals(0.55, FishingModule.netUncommon(0.2), 1e-9);
        assertEquals(0.35, FishingModule.netUncommon(-1), 1e-9, "never below");
        assertEquals(1, FishingModule.netUncommon(2), 1e-9);
    }

    @Test
    void onlyTheSeaIsTrawled() {
        // the habitats the net works in (FishSpec.SALT): every sea, no river, lake, swamp or cave
        for (String h : FishSpec.SALT) assertTrue(!h.equals("river") && !h.equals("cave"), h);
        assertTrue(FishSpec.SALT.contains("sea") && FishSpec.SALT.contains("deep"));
    }
}
