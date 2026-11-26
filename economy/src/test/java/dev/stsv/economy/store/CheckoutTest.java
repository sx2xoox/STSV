package dev.stsv.economy.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 편의점 계산 (README "편의점"): scanning, the money a cash customer hands over, the change buttons and [건네기]. */
class CheckoutTest {

    private static final List<Integer> BILLS = List.of(1000, 5000, 10000, 50000);
    private static final List<Integer> CHANGE = List.of(50000, 10000, 5000, 1000, 500, 100);

    @Test
    void scanningAddsUpOnlyWhatIsScanned() {
        Checkout c = new Checkout(List.of("samgak_tuna", "banana_milk", "gum"), List.of(1200, 1700, 1000), Checkout.Method.CASH);
        assertEquals(3900, c.bill());
        assertEquals(0, c.total());
        assertTrue(c.scan(1));
        assertFalse(c.scan(1), "삑 once a thing");
        assertFalse(c.scan(7));
        assertEquals(1700, c.total());
        assertFalse(c.allScanned());
        c.scan(0);
        c.scan(2);
        assertTrue(c.allScanned());
        assertEquals(3900, c.total());
    }

    @Test
    void whatACashCustomerHandsOver() {
        assertEquals(5000, Checkout.tender(3700, BILLS, false), "the smallest bill that covers it");
        assertEquals(1000, Checkout.tender(900, BILLS, false));
        assertEquals(10000, Checkout.tender(5300, BILLS, false));
        assertEquals(100000, Checkout.tender(61000, BILLS, false), "more 50,000s past the biggest bill");
        assertEquals(4000, Checkout.tender(3700, BILLS, true), "rounded up in 1,000s");
        assertEquals(3000, Checkout.tender(3000, BILLS, true), "whole thousands: exact");
        assertEquals(List.of(1000, 1000, 1000, 1000), Checkout.notes(4000, BILLS));
        assertEquals(List.of(5000), Checkout.notes(5000, BILLS));
        assertEquals(List.of(1000, 500, 100, 100, 100), Checkout.breakdown(1800, CHANGE));
    }

    @Test
    void exactChangePays() {
        Checkout c = cash(3700, 5000);
        assertEquals(1300, c.change(), "shown big: nobody does the sum");
        c.press(1000);
        c.press(100);
        c.press(100);
        c.press(100);
        assertEquals(1300, c.inHand());
        assertEquals(Checkout.Given.EXACT, c.give());
        assertTrue(c.paid());
        assertFalse(c.mistake());
        assertEquals(0, c.overpaid());
        assertTrue(c.done());
    }

    @Test
    void shortChangeIsAMistakeAndTheRestIsStillOwed() {
        Checkout c = cash(3700, 5000);
        c.press(1000);
        assertEquals(Checkout.Given.SHORT, c.give());
        assertFalse(c.paid());
        assertTrue(c.mistake());
        assertEquals(300, c.change(), "what was given is theirs; 300 still owed");
        c.press(100);
        c.press(100);
        c.press(100);
        assertEquals(Checkout.Given.EXACT, c.give());
        assertTrue(c.paid());
        assertTrue(c.mistake(), "no accuracy bonus for this one");
    }

    @Test
    void overChangeComesOutOfThePay() {
        Checkout c = cash(3700, 5000);
        c.press(5000);
        assertEquals(Checkout.Given.OVER, c.give());
        assertTrue(c.paid());
        assertEquals(3700, c.overpaid());
        assertTrue(c.mistake());
    }

    @Test
    void clearStartsTheHandAgain() {
        Checkout c = cash(3700, 5000);
        c.press(10000);
        c.clear();
        assertEquals(0, c.inHand());
        c.press(1000);
        c.press(100);
        c.press(100);
        c.press(100);
        assertEquals(Checkout.Given.EXACT, c.give());
    }

    @Test
    void cardNeedsEverythingScannedAndAnAutoTillIsAlwaysRight() {
        Checkout card = new Checkout(List.of("water"), List.of(900), Checkout.Method.CARD);
        assertFalse(card.card(), "scan first");
        card.scan(0);
        assertTrue(card.card());
        assertTrue(card.paid());
        assertEquals(Checkout.Given.NOTHING, card.give());
        assertEquals(0, card.change());

        Checkout auto = cash(3700, 5000);
        auto.autoChange();
        assertTrue(auto.paid());
        assertFalse(auto.mistake());
    }

    @Test
    void anAskKeepsThemAtTheCounterTillAnswered() {
        Checkout c = new Checkout(List.of("dosirak"), List.of(4500), Checkout.Method.CARD);
        c.ask("warm");
        c.ask("warm");
        assertEquals(List.of("warm"), c.asks());
        c.scan(0);
        c.card();
        assertFalse(c.done(), "paid, the food still in the microwave");
        assertTrue(c.answer("warm"));
        assertTrue(c.done());
    }

    private static Checkout cash(int price, int tendered) {
        Checkout c = new Checkout(List.of("x"), List.of(price), Checkout.Method.CASH);
        c.scan(0);
        c.tender(tendered);
        return c;
    }
}
