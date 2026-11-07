package dev.stsv.economy.shop;

import dev.stsv.economy.event.ActiveEvent;
import dev.stsv.economy.util.Items;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The 상인 행렬's rare pick, and the clicked slot sold first, without a server. */
class MerchantPickTest {

    @Test
    void theNewsAndTheStallPickTheSameRareEntry() {
        Map<String, String> params = new java.util.LinkedHashMap<>(Map.of("rare", "123456"));
        ActiveEvent draft = new ActiveEvent(0, "merchant", 0, 1, params);   // what the start hook sees
        ActiveEvent stored = new ActiveEvent(57, "merchant", 0, 1, params);   // what the menu sees later
        assertEquals(ShopModule.rareKey(draft), ShopModule.rareKey(stored));
        assertEquals("57", ShopModule.rareKey(new ActiveEvent(57, "merchant", 0, 1, Map.of())), "an event from before keeps its id pick");
    }

    @Test
    void theClickedSlotComesFirst() {
        assertArrayEquals(new int[]{11, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12}, Items.order(13, 11));
        assertArrayEquals(new int[]{0, 1, 2}, Items.order(3, -1));
        assertArrayEquals(new int[]{0, 1, 2}, Items.order(3, 40), "the offhand is not a storage slot");
    }
}
