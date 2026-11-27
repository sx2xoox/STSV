package dev.stsv.core.painting;

import dev.stsv.core.util.Lang;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The palette (a chest menu, 6 rows) opened with the brush: four rows of colours (a page of 9 bases, a column each,
 * shades lightest to darkest), a row of tools and brush sizes, and a row with the pages, the colour in use, 완성 and
 * close. Clicks are handled by {@link PaintingModule}; this only lays the items out.
 */
final class PaletteMenu implements InventoryHolder {

    static final int TOOL_PEN = 36, TOOL_ERASER = 37, TOOL_FILL = 38, TOOL_PICK = 39;
    static final int SIZE_1 = 40, SIZE_2 = 41, SIZE_3 = 42, UNDO = 43, TAKE_OFF = 44;
    static final int PREV = 45, CURRENT = 46, PAGE = 49, NEXT = 50, FINISH = 52, CLOSE = 53;

    final Player player;
    /** the painting (map id) the palette was opened for; -1 = none (only colours and tools) */
    final int mapId;
    int page;
    private Inventory inventory;

    PaletteMenu(Player player, int mapId, int page) {
        this.player = player;
        this.mapId = mapId;
        this.page = Math.floorMod(page, MapColors.PAGES.size());
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    void open(PaintingModule.Brush brush, ItemStack brushIcon) {
        inventory = Bukkit.createInventory(this, 54, Lang.mm("painting.menu.title"));
        draw(brush, brushIcon);
        player.openInventory(inventory);
    }

    /** The colour of a colour slot on this page, or null. */
    Byte colourAt(int slot) {
        if (slot < 0 || slot >= 36) return null;
        int[] bases = MapColors.PAGES.get(page);
        int col = slot % 9, row = slot / 9;
        if (col >= bases.length) return null;
        return MapColors.id(bases[col], MapColors.SHADE_ROWS[row]);
    }

    void draw(PaintingModule.Brush brush, ItemStack brushIcon) {
        inventory.clear();
        for (int slot = 0; slot < 36; slot++) {
            Byte c = colourAt(slot);
            if (c != null) inventory.setItem(slot, swatch(c, c == brush.color && brush.tool != PaintingModule.Tool.ERASER));
        }
        ItemStack pen = brushIcon.clone();
        pen.setAmount(1);
        inventory.setItem(TOOL_PEN, button(pen, "painting.menu.pen", brush.tool == PaintingModule.Tool.PEN));
        inventory.setItem(TOOL_ERASER, button(new ItemStack(Material.SPONGE), "painting.menu.eraser", brush.tool == PaintingModule.Tool.ERASER));
        inventory.setItem(TOOL_FILL, button(new ItemStack(Material.WATER_BUCKET), "painting.menu.fill", brush.tool == PaintingModule.Tool.FILL));
        inventory.setItem(TOOL_PICK, button(new ItemStack(Material.GLASS_BOTTLE), "painting.menu.pick", brush.tool == PaintingModule.Tool.PICK));
        int[] sizes = PaintCanvas.BRUSH_SIZES;
        int[] sizeSlots = {SIZE_1, SIZE_2, SIZE_3};
        for (int i = 0; i < sizes.length; i++) {
            ItemStack s = button(new ItemStack(Material.BLACK_DYE, sizes[i]), "painting.menu.size", brush.size == sizes[i], "size", sizes[i]);
            inventory.setItem(sizeSlots[i], s);
        }
        if (mapId >= 0) {
            inventory.setItem(UNDO, button(new ItemStack(Material.CLOCK), "painting.menu.undo", false));
            inventory.setItem(TAKE_OFF, button(new ItemStack(Material.ITEM_FRAME), "painting.menu.take-off", false));
            inventory.setItem(FINISH, button(new ItemStack(Material.NAME_TAG), "painting.menu.finish", false));
        }
        inventory.setItem(PREV, button(new ItemStack(Material.ARROW), "painting.menu.prev", false));
        inventory.setItem(NEXT, button(new ItemStack(Material.SPECTRAL_ARROW), "painting.menu.next", false));
        ItemStack page = button(new ItemStack(Material.PAPER, this.page + 1), "painting.menu.page", false,
                "page", this.page + 1, "pages", MapColors.PAGES.size());
        inventory.setItem(PAGE, page);
        byte now = brush.tool == PaintingModule.Tool.ERASER ? MapColors.CANVAS : brush.color;
        ItemStack current = swatch(now, false);
        current.editMeta(m -> {
            m.itemName(Lang.item("painting.menu.current", "swatch", PaintingModule.swatch(now)));
            m.lore(Lang.items("painting.menu.current-lore", "tool", Lang.raw("painting.tool." + brush.tool.key()), "size", brush.size));
        });
        inventory.setItem(CURRENT, current);
        inventory.setItem(CLOSE, button(new ItemStack(Material.BARRIER), "painting.menu.close", false));
    }

    private static ItemStack swatch(byte colour, boolean selected) {
        ItemStack it = new ItemStack(Material.LEATHER_HORSE_ARMOR);
        it.editMeta(LeatherArmorMeta.class, m -> {
            m.setColor(Color.fromRGB(MapColors.rgb(colour)));
            m.itemName(Lang.item("painting.menu.colour", "swatch", PaintingModule.swatch(colour), "id", colour & 0xFF));
            m.lore(Lang.items("painting.menu.colour-lore"));
            if (selected) m.setEnchantmentGlintOverride(true);
        });
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.DYED_COLOR, DataComponentTypes.ATTRIBUTE_MODIFIERS).build());
        return it;
    }

    private static ItemStack button(ItemStack it, String key, boolean selected, Object... pairs) {
        it.editMeta(m -> {
            m.itemName(Lang.item(key, pairs));
            List<Component> lore = Lang.items(key + "-lore", pairs);
            if (!lore.isEmpty()) m.lore(lore);
            m.setEnchantmentGlintOverride(selected ? Boolean.TRUE : null);
        });
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS).build());
        return it;
    }
}
