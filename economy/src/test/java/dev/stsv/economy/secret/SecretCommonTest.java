package dev.stsv.economy.secret;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared parts of the 숨은 비밀: which rumor comes next, when a monument stone is whole, and stray stones. */
class SecretCommonTest {

    @Test
    void rumorNeverRepeatsWhileAnotherIsOpen() {
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) assertNotEquals("bus", Rumors.pick(List.of("bus", "doll", "fish"), "bus", r));
    }

    @Test
    void lastOpenSecretStillGetsRumors() {
        assertEquals("doll", Rumors.pick(List.of("doll"), "doll", new Random(2)));
        assertNull(Rumors.pick(List.of(), "doll", new Random(2)));
    }

    @Test
    void stoneIsWholeOnlyWithOneTextAndOneModel() {
        assertTrue(Monument.whole(1, 1, 0));
        assertFalse(Monument.whole(1, 0, 0));   // its model was killed: set again
        assertFalse(Monument.whole(2, 2, 0));   // doubled: set again (the extras go)
        assertFalse(Monument.whole(0, 1, 0));
        assertFalse(Monument.whole(1, 0, 2));   // a two-block stone from before the models: replaced
        assertFalse(Monument.whole(1, 1, 1));   // a model and a leftover block: set again
    }

    @Test
    void strayStones() {
        assertFalse(Monument.stray("world", 0.5, 64, 0.5, "world", 4.5, 64, -3.5));   // on the arc (radius 6)
        assertFalse(Monument.stray("world", 0.5, 64, 0.5, "world", 0.5, 64 + Monument.TEXT_Y, 6.5)); // its text
        assertTrue(Monument.stray("world", 0.5, 64, 0.5, "world", 200, 64, 0));       // the old place
        assertTrue(Monument.stray("world", 0.5, 64, 0.5, "world_nether", 0.5, 64, 0.5));
    }
}
