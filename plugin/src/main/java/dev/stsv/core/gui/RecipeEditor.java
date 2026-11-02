package dev.stsv.core.gui;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-game recipe editor: /stsv recipe create &lt;id&gt; &lt;type&gt; [time] [xp]
 *
 * Players put real items into the grid / input slot and the result slot, then press save.
 * The editor only reads the items (it never consumes them) and writes &lt;pack&gt;/recipes/editor.yml.
 */
public final class RecipeEditor implements Listener {

    public static final List<String> TYPES =
            List.of("shaped", "shapeless", "furnace", "blasting", "smoking", "campfire", "stonecutting");
    private static final Set<String> COOKING = Set.of("furnace", "blasting", "smoking", "campfire");

    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int INPUT = 20;
    private static final int RESULT = 25;
    private static final int SAVE = 49, CANCEL = 45, INFO = 4;

    private final STSVPlugin plugin;

    public RecipeEditor(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Session implements InventoryHolder {
        final NamespacedKey key;
        final String type;
        final int time;
        final float xp;
        Inventory inventory;
        boolean saved;

        Session(NamespacedKey key, String type, int time, float xp) {
            this.key = key;
            this.type = type;
            this.time = time;
            this.xp = xp;
        }

        boolean grid() {
            return type.equals("shaped") || type.equals("shapeless");
        }

        boolean editable(int slot) {
            if (slot == RESULT) return true;
            if (grid()) {
                for (int g : GRID) if (g == slot) return true;
                return false;
            }
            return slot == INPUT;
        }

        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    /** Default cooking times (ticks), same as vanilla. */
    public static int defaultTime(String type) {
        return switch (type) {
            case "furnace" -> 200;
            case "campfire" -> 600;
            default -> 100;
        };
    }

    public void open(Player player, NamespacedKey key, String type, Integer time, Float xp) {
        type = type.toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new IllegalArgumentException("종류: " + String.join(", ", TYPES));
        Session s = new Session(key, type, time != null ? time : defaultTime(type), xp != null ? xp : 0.1f);
        Inventory inv = Bukkit.createInventory(s, 54, Text.mm("<dark_gray>레시피 편집: <gold>" + key.asString()));
        s.inventory = inv;

        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) if (!s.editable(i)) inv.setItem(i, pane);
        inv.setItem(INFO, button(Material.BOOK, "<gold>" + key.asString(), info(s)));
        inv.setItem(23, button(Material.ARROW, "<gray>→ 결과", List.of()));
        if (COOKING.contains(type)) inv.setItem(29, button(Material.CAMPFIRE, "<gray>연료는 화로에서", List.of()));
        inv.setItem(CANCEL, button(Material.BARRIER, "<red>취소", List.of("<gray>넣은 아이템은 돌려받습니다.")));
        inv.setItem(SAVE, button(Material.LIME_CONCRETE, "<green>저장", List.of("<gray>넣은 아이템은 소모되지 않습니다.")));
        player.openInventory(inv);
    }

    private static List<String> info(Session s) {
        List<String> lines = new ArrayList<>();
        lines.add("<gray>종류: <white>" + s.type);
        if (s.grid()) lines.add("<gray>왼쪽 3×3 칸에 재료, 오른쪽 칸에 결과물");
        else lines.add("<gray>왼쪽 칸에 재료 1개, 오른쪽 칸에 결과물");
        if (COOKING.contains(s.type)) lines.add("<gray>시간 <white>" + s.time + "틱</white>, 경험치 <white>" + s.xp);
        lines.add("<gray>결과물 개수 = 넣은 개수");
        return lines;
    }

    private static ItemStack button(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.itemName(Text.item(name));
        meta.lore(Text.itemLines(lore));
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Session s)) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true); // double-click would also collect the editor's decoration items
            return;
        }
        boolean top = e.getClickedInventory() == e.getInventory();
        if (!top) {
            // moving from the player inventory: only plain clicks (shift-click would pick a random slot)
            if (e.isShiftClick()) e.setCancelled(true);
            return;
        }
        int slot = e.getSlot();
        if (s.editable(slot)) return;
        e.setCancelled(true);
        if (slot == CANCEL) {
            player.closeInventory();
        } else if (slot == SAVE) {
            save(player, s);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Session s)) return;
        for (int raw : e.getRawSlots()) {
            if (raw < 54 && !s.editable(raw)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Session s)) return;
        // always give the items back: the editor only copies what it sees
        List<ItemStack> back = new ArrayList<>();
        for (int i = 0; i < 54; i++) {
            ItemStack it = e.getInventory().getItem(i);
            if (s.editable(i) && it != null && !it.isEmpty()) back.add(it);
        }
        e.getInventory().clear();
        if (e.getPlayer() instanceof Player p) {
            p.getInventory().addItem(back.toArray(ItemStack[]::new)).values()
                    .forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
            if (!s.saved) p.sendMessage(Text.msg("<gray>레시피 편집을 취소했습니다."));
        }
    }

    private void save(Player player, Session s) {
        Inventory inv = s.inventory;
        ItemStack result = inv.getItem(RESULT);
        if (result == null || result.isEmpty()) {
            player.sendMessage(Text.msg("<red>결과물 칸이 비어 있습니다."));
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", s.type);
        List<String> warnings = new ArrayList<>();
        try {
            switch (s.type) {
                case "shaped" -> shaped(inv, data, warnings);
                case "shapeless" -> {
                    List<String> ingredients = new ArrayList<>();
                    for (int g : GRID) {
                        ItemStack it = inv.getItem(g);
                        if (it != null && !it.isEmpty()) ingredients.add(id(it, warnings));
                    }
                    if (ingredients.isEmpty()) throw new IllegalArgumentException("재료가 없습니다.");
                    data.put("ingredients", ingredients);
                }
                default -> {
                    ItemStack in = inv.getItem(INPUT);
                    if (in == null || in.isEmpty()) throw new IllegalArgumentException("재료 칸이 비어 있습니다.");
                    data.put("input", id(in, warnings));
                    if (COOKING.contains(s.type)) {
                        data.put("experience", (double) s.xp);
                        data.put("time", s.time);
                    }
                }
            }
            String resultId = id(result, warnings);
            if (result.getAmount() > 1) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("item", resultId);
                r.put("amount", result.getAmount());
                data.put("result", r);
            } else {
                data.put("result", resultId);
            }
            plugin.recipes().saveRecipe(s.key, data);
        } catch (IllegalArgumentException ex) {
            player.sendMessage(Text.msg("<red>" + ex.getMessage()));
            return;
        } catch (Exception ex) {
            player.sendMessage(Text.msg("<red>저장 실패: " + ex.getMessage()));
            plugin.getLogger().warning("레시피 저장 실패: " + ex);
            return;
        }
        s.saved = true;
        warnings.forEach(w -> player.sendMessage(Text.msg("<yellow>" + w)));
        boolean ok = plugin.recipes().registered().contains(s.key);
        player.sendMessage(ok
                ? Text.msg("<green>레시피 <white>" + s.key.asString() + "</white> 저장 및 등록 완료")
                : Text.msg("<red>저장은 됐지만 등록에 실패했습니다. 콘솔을 확인하세요."));
        player.playSound(player.getLocation(), ok ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.4f);
        player.closeInventory();
    }

    private void shaped(Inventory inv, Map<String, Object> data, List<String> warnings) {
        String[][] ids = new String[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                ItemStack it = inv.getItem(GRID[r * 3 + c]);
                if (it != null && !it.isEmpty()) ids[r][c] = id(it, warnings);
            }
        }
        data.putAll(buildShaped(ids));
    }

    /** 3x3 grid of ids (null = empty) -> trimmed pattern + letter keys, as written to YAML. */
    static Map<String, Object> buildShaped(String[][] ids) {
        int minR = 3, maxR = -1, minC = 3, maxC = -1;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (ids[r][c] == null) continue;
                minR = Math.min(minR, r); maxR = Math.max(maxR, r);
                minC = Math.min(minC, c); maxC = Math.max(maxC, c);
            }
        }
        if (maxR < 0) throw new IllegalArgumentException("재료가 없습니다.");
        Map<String, Character> letters = new LinkedHashMap<>();
        List<String> pattern = new ArrayList<>();
        for (int r = minR; r <= maxR; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = minC; c <= maxC; c++) {
                String id = ids[r][c];
                row.append(id == null ? ' ' : letters.computeIfAbsent(id, k -> (char) ('A' + letters.size())));
            }
            pattern.add(row.toString());
        }
        Map<String, Object> keys = new LinkedHashMap<>();
        letters.forEach((id, ch) -> keys.put(String.valueOf(ch), id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pattern", pattern);
        out.put("keys", keys);
        return out;
    }

    private String id(ItemStack it, List<String> warnings) {
        String id = plugin.recipes().idOf(it);
        if (id.startsWith("minecraft:") && it.hasItemMeta()) {
            ItemMeta m = it.getItemMeta();
            if (m.hasCustomName() || m.hasEnchants() || m.hasLore()) {
                warnings.add(id + " 의 이름/인챈트/설명은 레시피에 포함되지 않습니다 (종류만 비교).");
            }
        }
        return id;
    }
}
