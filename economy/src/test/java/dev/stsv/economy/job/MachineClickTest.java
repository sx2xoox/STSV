package dev.stsv.economy.job;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which hand a click into a machine slot takes its item from, without a server. */
class MachineClickTest {

    /** A view that only answers what the event's constructor asks (convertSlot). */
    private static final InventoryView VIEW = (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
            new Class<?>[]{InventoryView.class}, (proxy, method, args) -> method.getName().equals("convertSlot") ? args[0] : null);

    private static InventoryClickEvent click(ClickType click, InventoryAction action) {
        return new InventoryClickEvent(VIEW, InventoryType.SlotType.CONTAINER, MachineModule.input(0, 0), click, action);
    }

    @Test
    void numberKeySwapsWithItsHotbarSlot() {
        InventoryClickEvent e = new InventoryClickEvent(VIEW, InventoryType.SlotType.CONTAINER, MachineModule.input(0, 0),
                ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 3);
        assertEquals(3, MachineModule.swapSlot(e));
    }

    @Test
    void offhandSwapTakesTheOffhandThoughTheEventHasNoButton() {
        InventoryClickEvent e = click(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP);
        assertEquals(-1, e.getHotbarButton(), "the server only passes the button for NUMBER_KEY");
        assertEquals(40, MachineModule.swapSlot(e), "F: the offhand (slot 40 of the player inventory), not the empty cursor");
    }

    @Test
    void otherClicksUseTheCursor() {
        assertEquals(-1, MachineModule.swapSlot(click(ClickType.LEFT, InventoryAction.PLACE_ALL)));
        assertEquals(-1, MachineModule.swapSlot(click(ClickType.RIGHT, InventoryAction.SWAP_WITH_CURSOR)));
        assertEquals(-1, MachineModule.swapSlot(click(ClickType.SWAP_OFFHAND, InventoryAction.NOTHING)));
    }
}
