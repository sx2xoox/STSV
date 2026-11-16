package dev.stsv.economy.land;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a block is worth to the 땅 타임머신: its price, or what it turns into. */
class ValueGraphTest {

    @Test
    void worthWhatItCanBecome() {
        Map<String, Double> prices = Map.of("diamond", 60.0, "iron_ingot", 12.5, "oak_log", 2.0);
        ValueGraph g = new ValueGraph(id -> prices.getOrDefault(id, 0.0));
        g.edge("diamond_block", "diamond", 9);
        g.edge("raw_iron_block", "raw_iron", 9);
        g.edge("raw_iron", "iron_ingot", 1);                 // smelting
        g.edge("oak_log", "oak_planks", 4);                   // planks sell for nothing: the log keeps its own price
        g.edge("diamond", "diamond_block", 1 / 9.0);         // back again: no loop
        assertEquals(540, g.value("diamond_block"), 1e-9);
        assertEquals(112.5, g.value("raw_iron_block"), 1e-9, "two steps: 9 raw iron, each an ingot");
        assertEquals(2.0, g.value("oak_log"), 1e-9);
        assertEquals(0, g.value("oak_planks"), 1e-9);
        assertEquals(0, g.value("stone"), 1e-9);
        assertEquals(60, g.value("diamond"), 1e-9);
    }
}
