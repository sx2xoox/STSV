package dev.stsv.core.vehicle;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * 적재 무게 in the bed's chest view: what a click or a drag would put into the bed and take out of it, so one that would
 * carry the vehicle past its payload is refused before it happens ({@link VehicleModule}). Every way in is covered: a
 * click with the cursor (all, some, one, swap), a number key or F swap, shift-click from the player's inventory, a drag.
 * Clicks on the bed that do something else (none of vanilla's do) are refused outright. Hoppers cannot reach a bed: it is
 * not a block.
 */
final class CargoGuard {

    private CargoGuard() {}

    /** A click's move: what goes into the bed (null = nothing) and what comes out of it (null = nothing). */
    record Move(ItemStack in, int inAmount, ItemStack out) {
        static final Move NONE = new Move(null, 0, null);
        static final Move REFUSE = new Move(null, -1, null);

        boolean refused() {
            return inAmount < 0;
        }
    }

    /** What a click on a bed view (top) would move into and out of the bed. */
    static Move click(InventoryClickEvent e, Inventory top) {
        InventoryAction a = e.getAction();
        ItemStack cursor = e.getCursor(), current = e.getCurrentItem();
        boolean onBed = e.getClickedInventory() == top;
        if (!onBed) {
            // shift-click in the player's own inventory sends the stack into the bed
            if (a == InventoryAction.MOVE_TO_OTHER_INVENTORY && present(current)) return new Move(current, current.getAmount(), null);
            return Move.NONE;
        }
        return switch (a) {
            case NOTHING, PICKUP_ALL, PICKUP_HALF, PICKUP_SOME, PICKUP_ONE, DROP_ALL_SLOT, DROP_ONE_SLOT, DROP_ALL_CURSOR,
                 DROP_ONE_CURSOR, CLONE_STACK, MOVE_TO_OTHER_INVENTORY -> Move.NONE;
            case PLACE_ALL -> present(cursor) ? new Move(cursor, cursor.getAmount(), null) : Move.NONE;
            case PLACE_SOME -> present(cursor)
                    ? new Move(cursor, Math.max(0, Math.min(cursor.getAmount(), cursor.getMaxStackSize() - (present(current) ? current.getAmount() : 0))), null)
                    : Move.NONE;
            case PLACE_ONE -> present(cursor) ? new Move(cursor, 1, null) : Move.NONE;
            case SWAP_WITH_CURSOR -> present(cursor) ? new Move(cursor, cursor.getAmount(), present(current) ? current : null) : Move.NONE;
            case HOTBAR_SWAP -> {
                ItemStack from = hotbar(e.getWhoClicked(), e.getHotbarButton());
                yield present(from) ? new Move(from, from.getAmount(), present(current) ? current : null) : Move.NONE;
            }
            default -> Move.REFUSE;
        };
    }

    /** What a drag would add to the bed: the item and how many more of it end up in the bed's slots (raw < top's size). */
    static int dragged(InventoryDragEvent e, Inventory top) {
        int added = 0;
        for (Map.Entry<Integer, ItemStack> en : e.getNewItems().entrySet()) {
            int raw = en.getKey();
            if (raw >= top.getSize()) continue;
            ItemStack before = top.getItem(raw), after = en.getValue();
            int was = present(before) && after != null && before.isSimilar(after) ? before.getAmount() : 0;
            added += Math.max(0, (after == null ? 0 : after.getAmount()) - was);
        }
        return added;
    }

    private static ItemStack hotbar(HumanEntity p, int button) {
        if (button < 0) return p.getInventory().getItemInOffHand();   // F: the off hand
        return p.getInventory().getItem(button);
    }

    static boolean present(ItemStack it) {
        return it != null && !it.isEmpty();
    }
}
