package dev.stsv.economy.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 손님마다 바로 받는 보수: 모두 더하면 퇴근 때 한 번에 받는 것과 같음. */
class ShiftLogSettleTest {
    @Test
    void perCustomerPayAddsUpToTheSingleClockOutPay() throws Exception {
        StoreSpec spec = StoreSpecTest.spec();
        ShiftLog a = new ShiftLog(), b = new ShiftLog();
        long paid = 0;
        for (int i = 0; i < 20; i++) {
            a.served(5, 700, 3000, true);
            b.served(5, 700, 3000, true);
            paid += a.settle(spec, 0.4, false);
        }
        a.overpaid(1000);
        b.overpaid(1000);
        paid += a.settle(spec, 0.4, false);
        assertTrue(paid > 0);
        paid += a.settle(spec, 0.4, true);
        assertEquals(b.payout(spec, 0.4).pay(), paid);
        assertEquals(paid, a.paid());
        assertEquals(0, a.settle(spec, 0.4, true));
    }
}
