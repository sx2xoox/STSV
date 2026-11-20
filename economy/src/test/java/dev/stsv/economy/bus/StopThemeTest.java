package dev.stsv.economy.bus;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StopThemeTest {

    @Test
    void themeFollowsTheTraitsInOrder() {
        assertEquals(StopBuilder.Theme.COUNTRY, StopBuilder.theme(Set.of()));
        assertEquals(StopBuilder.Theme.COUNTRY, StopBuilder.theme(null));
        assertEquals(StopBuilder.Theme.COUNTRY, StopBuilder.theme(Set.of("desert")));
        assertEquals(StopBuilder.Theme.PORT, StopBuilder.theme(Set.of("port", "sea", "mountain", "forest", "field", "village")));
        assertEquals(StopBuilder.Theme.SEA, StopBuilder.theme(Set.of("sea", "mountain", "village")));
        assertEquals(StopBuilder.Theme.MOUNTAIN, StopBuilder.theme(Set.of("forest", "mountain")));
        assertEquals(StopBuilder.Theme.FOREST, StopBuilder.theme(Set.of("field", "forest")));
        assertEquals(StopBuilder.Theme.FIELD, StopBuilder.theme(Set.of("village", "field")));
        assertEquals(StopBuilder.Theme.VILLAGE, StopBuilder.theme(Set.of("village")));
    }

    @Test
    void theCountryStopKeepsItsLook() {
        StopBuilder.Look k = StopBuilder.Theme.COUNTRY.look();
        assertEquals(org.bukkit.Material.WHITE_TERRACOTTA, k.wall());
        assertEquals(org.bukkit.Material.COBBLESTONE, k.footing());
        assertEquals(StopBuilder.TIN, k.roof());
        assertEquals(StopBuilder.TIN_STAIRS, k.roofStairs());
        assertEquals(org.bukkit.Material.HAY_BLOCK, k.propLow());
        assertEquals(org.bukkit.Material.POTTED_POPPY, k.propHigh());
        assertEquals(org.bukkit.Material.SPRUCE_FENCE, k.pole());
    }

    @Test
    void everyThemeHasAStairRoofAndBench() {
        for (StopBuilder.Theme t : StopBuilder.Theme.values()) {
            StopBuilder.Look k = t.look();
            assertTrue(k.roofStairs().name().endsWith("_STAIRS"), t + " roof steps");
            assertTrue(k.bench().name().endsWith("_STAIRS"), t + " bench");
            assertTrue(k.roof().isBlock() && k.wall().isBlock() && k.pole().isBlock(), t.name());
        }
    }

    @Test
    void lampIsLitAtNight() {
        assertFalse(StopBuilder.night(6000));
        assertTrue(StopBuilder.night(18000));
        assertTrue(StopBuilder.night(24000 + 13000));
        assertFalse(StopBuilder.night(0));
    }
}
