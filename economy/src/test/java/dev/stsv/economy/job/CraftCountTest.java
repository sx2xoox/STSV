package dev.stsv.economy.job;

import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a click on a crafting result really makes, and a machine lane's saved progress, without a server. */
class CraftCountTest {

    @Test
    void aClickThatTakesNothingMakesNothing() {
        // a pickaxe on the cursor: the server still fires the event, with NOTHING
        assertEquals(0, JobModule.crafted(InventoryAction.NOTHING, false, 64, 0, 1));
        // Q with something on the cursor does nothing either
        assertEquals(0, JobModule.crafted(InventoryAction.DROP_ONE_SLOT, false, 64, 0, 1));
    }

    @Test
    void takingTheResultMakesOne() {
        assertEquals(1, JobModule.crafted(InventoryAction.PICKUP_ALL, true, 64, 0, 1));
        assertEquals(1, JobModule.crafted(InventoryAction.PICKUP_HALF, true, 64, 0, 1));
        assertEquals(1, JobModule.crafted(InventoryAction.HOTBAR_SWAP, true, 64, 0, 1));
        assertEquals(1, JobModule.crafted(InventoryAction.DROP_ONE_SLOT, true, 64, 0, 1));
    }

    @Test
    void aShiftClickMakesWhatBothIngredientsAndRoomAllow() {
        assertEquals(0, JobModule.crafted(InventoryAction.MOVE_TO_OTHER_INVENTORY, true, 64, 0, 1), "a full inventory: nothing made");
        assertEquals(3, JobModule.crafted(InventoryAction.MOVE_TO_OTHER_INVENTORY, true, 64, 3, 1), "three free slots for pickaxes");
        assertEquals(5, JobModule.crafted(InventoryAction.MOVE_TO_OTHER_INVENTORY, true, 5, 36 * 64, 4), "five sets of ingredients");
        assertEquals(2, JobModule.crafted(InventoryAction.MOVE_TO_OTHER_INVENTORY, true, 64, 9, 4), "room for 9 planks: two crafts of 4");
    }

    @Test
    void aLaneKeepsItsProgressOverARestart() {
        MachineModule.Lane[] lanes = {new MachineModule.Lane(), new MachineModule.Lane()};
        lanes[0].recipe = 3;
        lanes[0].operator = UUID.randomUUID();
        lanes[0].progress = 2400;
        lanes[0].making = 7;
        MachineModule.Lane[] back = {new MachineModule.Lane(), new MachineModule.Lane()};
        MachineModule.Lane.read(back, MachineModule.Lane.state(lanes));
        assertEquals(3, back[0].recipe);
        assertEquals(lanes[0].operator, back[0].operator);
        assertEquals(7, back[0].making);
        assertFalse(back[0].make(7), "the same recipe goes on");
        assertEquals(2400, back[0].progress, 1e-9);
        assertTrue(back[0].make(2), "another recipe starts over");
        assertEquals(0, back[0].progress, 1e-9);
    }

    @Test
    void anOlderSavedLaneCarriesOnWithWhatItMakes() {
        MachineModule.Lane[] lanes = {new MachineModule.Lane()};
        MachineModule.Lane.read(lanes, "-1,,1800.0;");
        assertEquals(MachineModule.Lane.UNKNOWN, lanes[0].making);
        assertFalse(lanes[0].make(4));
        assertEquals(1800, lanes[0].progress, 1e-9, "progress kept for the recipe it makes on the first tick");
        assertEquals(4, lanes[0].making);
        MachineModule.Lane[] idle = {new MachineModule.Lane()};
        MachineModule.Lane.read(idle, "-1,,1800.0;");
        assertTrue(idle[0].make(-1), "nothing to make: the progress goes");
    }
}
