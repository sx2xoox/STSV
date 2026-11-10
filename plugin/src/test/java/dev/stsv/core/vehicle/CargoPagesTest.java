package dev.stsv.core.vehicle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bed of N pages of 45 (the hand cart's 270 = 6 pages, the truck's 90 = 2): paging, putting back, resizing. */
class CargoPagesTest {

    @Test
    void pagesOf45() {
        assertEquals(6, CargoPages.pages(270), "the hand cart");
        assertEquals(2, CargoPages.pages(90), "the truck");
        assertEquals(1, CargoPages.pages(1));
        assertEquals(1, CargoPages.pages(0));
        assertEquals(3, CargoPages.pages(91), "a leftover slot gets its own page");
        assertEquals(0, CargoPages.clamp(-1, 6));
        assertEquals(5, CargoPages.clamp(9, 6), "past the last page: the last");
        assertEquals(3, CargoPages.clamp(3, 6));
        assertEquals(0, CargoPages.clamp(2, 0));
        assertEquals(269, CargoPages.index(5, 44), "the last slot of the sixth page");
        assertEquals(45, CargoPages.index(1, 0));
    }

    @Test
    void everyPageOf270ShowsItsOwnSlotsAndPutsThemBack() {
        String[] bed = new String[270];
        for (int i = 0; i < bed.length; i += 7) bed[i] = "item" + i;
        for (int page = 0; page < 6; page++) {
            List<String> shown = CargoPages.page(bed, page);
            assertEquals(45, shown.size());
            for (int i = 0; i < 45; i++) assertEquals(bed[page * 45 + i], shown.get(i), "page " + page + " slot " + i);
        }
        // a viewer changes page 4: only its 45 slots change, the rest stays
        String[] before = bed.clone();
        int page = 4;
        assertTrue(CargoPages.store(bed, page, i -> i == 0 ? "new" : i == 7 ? null : CargoPages.page(before, page).get(i)));
        assertEquals("new", bed[180]);
        assertNull(bed[180 + 7], "taken out");
        for (int i = 0; i < 270; i++) if (i != 180 && i != 187) assertEquals(before[i], bed[i], "slot " + i + " untouched");
        // the same view again: nothing changed, nothing to save
        String[] now = bed.clone();
        assertFalse(CargoPages.store(bed, page, i -> CargoPages.page(now, page).get(i)));
        assertEquals(CargoPages.used(now), CargoPages.used(bed));
    }

    @Test
    void aFullBedOf270() {
        String[] bed = new String[270];
        for (int p = 0; p < 6; p++) {
            int page = p;
            CargoPages.store(bed, page, i -> "p" + page + "s" + i);
        }
        assertEquals(270, CargoPages.used(bed), "all six pages hold 45 each");
        assertEquals("p5s44", bed[269]);
        assertEquals("p0s0", bed[0]);
        assertEquals(1.0, CargoStages.stage(CargoPages.used(bed), 270, 4) / 4.0, 1e-9, "the full, tarped load shows");
        assertEquals(1, CargoStages.stage(30, 270, 4), "a few stacks: the first stage");
    }

    @Test
    void theBedIsSizedForItsVehicleWithoutLosingALoad() {
        assertEquals(270, CargoPages.resized(null, 270, String[]::new).length, "a new cart: 270 empty slots");
        assertNull(CargoPages.resized(null, 0, String[]::new), "no bed, nothing in it");
        String[] old = new String[90];
        old[89] = "x";
        String[] grown = CargoPages.resized(old, 270, String[]::new);
        assertEquals(270, grown.length, "raised in the file: more pages");
        assertEquals("x", grown[89]);
        String[] big = new String[270];
        big[200] = "kept";
        String[] shrunk = CargoPages.resized(big, 90, String[]::new);
        assertEquals(201, shrunk.length, "lowered in the file: it keeps up to its last filled slot (reachable on extra pages)");
        assertEquals("kept", shrunk[200]);
        assertEquals(5, CargoPages.pages(shrunk.length));
        String[] same = new String[270];
        same[3] = "a";
        assertArrayEquals(same, CargoPages.resized(same, 270, String[]::new));
        assertSame(null, CargoPages.resized(new String[45], 0, String[]::new), "an empty bed on a vehicle without one: gone");
    }
}
