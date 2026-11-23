package dev.stsv.economy.rank;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankSpendingTest {

    @Test
    void spendingAtTheServerIsGivenBack() {
        assertTrue(RankModule.spendingAddsBack("shop_buy", false, List.of()));
        assertTrue(RankModule.spendingAddsBack("vanity_cosmetic", false, null));
        assertTrue(RankModule.spendingAddsBack("acc_enhance", false, List.of()));
    }

    @Test
    void taxesFeesAndLandStillCount() {
        for (String r : RankModule.KEPT) assertFalse(RankModule.spendingAddsBack(r, false, List.of()), r);
        assertFalse(RankModule.spendingAddsBack("shop_buy", false, List.of("shop_buy")));
    }

    @Test
    void theOldWayWhenAsked() {
        assertFalse(RankModule.spendingAddsBack("shop_buy", true, List.of()));
    }
}
