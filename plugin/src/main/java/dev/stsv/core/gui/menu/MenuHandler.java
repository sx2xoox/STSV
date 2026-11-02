package dev.stsv.core.gui.menu;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * What a module plugs into a menu. Everything is optional: a menu with only buttons and built-in
 * actions (page:prev, page:next, close, menu:&lt;id&gt;, command:&lt;command&gt;) needs no handler at all.
 */
public interface MenuHandler {

    MenuHandler NONE = new MenuHandler() {};

    /** Values for {name} placeholders in the title, button names and lore ({page} and {pages} are built in). */
    default Map<String, String> placeholders(MenuView view) {
        return Map.of();
    }

    /** The items of the paged list area, in order (the menu pages them). */
    default List<ItemStack> list(MenuView view) {
        return List.of();
    }

    /** index = position in {@link #list}. */
    default void onListClick(MenuView view, int index, ClickType click) {}

    /** Actions that are not built in (e.g. "wardrobe:exit"). */
    default void onAction(MenuView view, String action, ClickType click) {}

    /** Last chance to change a button's item (e.g. a dynamic icon); base is the YAML look. */
    default ItemStack button(MenuView view, MenuSpec.Button button, ItemStack base) {
        return base;
    }

    /** The menu closed (Esc, another menu opened, or closed by code). */
    default void onClose(MenuView view) {}
}
