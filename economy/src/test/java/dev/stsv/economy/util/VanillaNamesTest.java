package dev.stsv.economy.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Korean names of vanilla items, for the text the server draws itself (사이드바: no "item.minecraft.diamond"). */
class VanillaNamesTest {

    @Test
    void namesParseAndKnowTheShopsVanillaItems() {
        Map<String, String> m = Items.parseNames("# x\ndiamond: \"다이아몬드\"\n\noak_log: \"참나무 원목\"\n");
        assertEquals("다이아몬드", m.get("diamond"));
        assertEquals("참나무 원목", m.get("oak_log"));
        assertEquals("다이아몬드", Items.koName("diamond"));
        assertEquals("철 주괴", Items.koName("iron_ingot"));
        assertNull(Items.koName("no_such_item"));
    }
}
