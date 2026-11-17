package dev.stsv.core.gui.menu;

import dev.stsv.core.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One open menu for one player. Redrawn in place (no close/reopen flicker) with {@link #refresh()}. */
public final class MenuView implements InventoryHolder {

    private final MenuSpec spec;
    private final Player player;
    private final MenuHandler handler;
    private final Map<String, Object> data = new HashMap<>();
    final Map<Integer, Integer> listAt = new HashMap<>();
    final Map<Integer, MenuSpec.Button> buttonAt = new HashMap<>();
    final Map<Integer, Boolean> buttonOff = new HashMap<>();
    Inventory inventory;
    private int page;
    private int pages = 1;

    MenuView(MenuSpec spec, Player player, MenuHandler handler) {
        this.spec = spec;
        this.player = player;
        this.handler = handler;
    }

    public MenuSpec spec() { return spec; }
    public Player player() { return player; }
    public MenuHandler handler() { return handler; }
    public int page() { return page; }
    public int pages() { return pages; }

    /** Free storage for the handler (e.g. which item a list position stands for). */
    public Map<String, Object> data() { return data; }

    public void page(int page) {
        this.page = page;
        refresh();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** The handler's {placeholders} while the menu is being drawn: asked once per drawing, not once per line of text. */
    private Map<String, String> drawing;

    String fill(String s) {
        if (s.indexOf('{') < 0) return s;
        s = s.replace("{page}", String.valueOf(page + 1)).replace("{pages}", String.valueOf(pages));
        Map<String, String> ph = drawing != null ? drawing : handler.placeholders(this);
        for (Map.Entry<String, String> e : ph.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
        return s;
    }

    public void refresh() {
        listAt.clear();
        buttonAt.clear();
        buttonOff.clear();
        List<Integer> area = spec.list() == '\0' ? List.of() : spec.slots(spec.list());
        List<ItemStack> items = area.isEmpty() ? List.of() : handler.list(this);
        int per = Math.max(1, area.size());
        pages = Math.max(1, (items.size() + per - 1) / per);
        page = Math.clamp(page, 0, pages - 1);

        drawing = handler.placeholders(this);
        try {
            draw(area, items, per);
        } finally {
            drawing = null;
        }
    }

    private void draw(List<Integer> area, List<ItemStack> items, int per) {
        ItemStack filler = spec.filler().equals("glass") ? look(new MenuSpec.Look("GRAY_STAINED_GLASS_PANE", null, " ", List.of())) : null;
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
        for (int k = 0; k < area.size(); k++) {
            int index = page * per + k;
            int slot = area.get(k);
            inventory.setItem(slot, index < items.size() ? items.get(index) : null);
            if (index < items.size()) listAt.put(slot, index);
        }
        for (MenuSpec.Button b : spec.buttons().values()) {
            boolean off = (b.action().equals("page:prev") && page == 0) || (b.action().equals("page:next") && page >= pages - 1);
            MenuSpec.Look l = off ? b.off() : b.on();
            // made once: a button spread over many slots (a card) is the same item in each (the inventory keeps a copy)
            ItemStack item = l == null ? null : handler.button(this, b, look(l));
            for (int slot : spec.slots(b.key())) {
                buttonAt.put(slot, b);
                buttonOff.put(slot, off);
                inventory.setItem(slot, item);
            }
        }
    }

    ItemStack look(MenuSpec.Look l) {
        Material m = Material.matchMaterial(l.material());
        ItemStack it = new ItemStack(m == null || !m.isItem() ? Material.PAPER : m);
        it.editMeta(meta -> {
            if (l.icon() != null) meta.setItemModel(l.icon());
            meta.itemName(Text.item(fill(l.name())));
            meta.lore(Text.itemLines(l.lore().stream().map(this::fill).toList()));
        });
        return it;
    }

    public void close() {
        if (player.getOpenInventory().getTopInventory().getHolder(false) == this) player.closeInventory();
    }
}
