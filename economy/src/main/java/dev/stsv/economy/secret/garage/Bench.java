package dev.stsv.economy.secret.garage;

import dev.stsv.core.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The workbench's screen (a plain chest inventory, not a menu file: nothing of this secret is in the menus): a row of
 * paints, a row of wheels, a row of glows — each option a swatch in its colour under its tuning-shop name — and the
 * button that lets the shutter down. The button's lore is the notebook: every look tried so far and how many of it he
 * said were his.
 */
final class Bench implements InventoryHolder {

    /** The three kinds, in the screen's order: the word in {@link Combo}, the section of garage.yml options, its label when the file has none. */
    static final String[][] KINDS = {{"paint", "paints", "도색"}, {"wheel", "wheels", "휠"}, {"glow", "underglows", "밑의 불빛"}};

    /** One thing to choose (mirrors the tuning shop's vehicle_tuning.yml: the core does not hand its catalogue out). */
    record Option(String id, String name, int color, String clue) {}

    private final UUID viewer;
    /** An admin's look at it: nothing is kept, the button only says the number. */
    final boolean preview;
    final Map<Integer, String[]> slots = new HashMap<>();
    int button = -1;
    Combo look;
    private Inventory inv;

    private Bench(UUID viewer, boolean preview, Combo look) {
        this.viewer = viewer;
        this.preview = preview;
        this.look = look == null ? Combo.NONE : look;
    }

    UUID viewer() {
        return viewer;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inv;
    }

    /** The options of a kind as garage.yml lists them (in its order). */
    static Map<String, Option> options(YamlConfiguration cfg, String section) {
        Map<String, Option> out = new LinkedHashMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection("options." + section);
        if (sec == null) return out;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection o = sec.getConfigurationSection(id);
            if (o == null || !id.matches("[a-z0-9_]{1,32}")) continue;
            out.put(id, new Option(id, o.getString("name", id), color(o.getString("color")), o.getString("clue", "")));
        }
        return out;
    }

    static int color(String raw) {
        if (raw == null) return 0x9a9a9a;
        String s = raw.trim().startsWith("#") ? raw.trim().substring(1) : raw.trim();
        try {
            return s.length() == 6 ? Integer.parseInt(s, 16) : 0x9a9a9a;
        } catch (NumberFormatException e) {
            return 0x9a9a9a;
        }
    }

    /** "화이트 · 터빈 · 시안" for a look (a part not chosen: ?). */
    static String words(YamlConfiguration cfg, Combo c) {
        List<String> out = new ArrayList<>();
        for (String[] kind : KINDS) {
            String id = c.get(kind[0]);
            Option o = id == null ? null : options(cfg, kind[1]).get(id);
            out.add(id == null ? "?" : o == null ? id : o.name());
        }
        return String.join(" · ", out);
    }

    static Bench open(YamlConfiguration cfg, UUID viewer, boolean preview, Combo look, List<Combo.Try> notebook, long waitSeconds) {
        Bench b = new Bench(viewer, preview, look);
        b.inv = Bukkit.createInventory(b, 54, Text.mm(cfg.getString("gui.title", "<dark_gray>작업대")));
        b.draw(cfg, notebook, waitSeconds);
        return b;
    }

    /** Draws everything again (a choice changed). */
    void draw(YamlConfiguration cfg, List<Combo.Try> notebook, long waitSeconds) {
        inv.clear();
        slots.clear();
        ItemStack pane = named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), " ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);
        int row = 0;
        for (String[] kind : KINDS) {
            if (row > 4) break;
            Map<String, Option> opts = options(cfg, kind[1]);
            inv.setItem(row * 9, named(new ItemStack(Material.NAME_TAG), cfg.getString("gui.label-" + kind[0], "<white>" + kind[2]),
                    lines(cfg.getStringList("gui.label-" + kind[0] + "-lore"))));
            int col = 1;
            for (Option o : opts.values()) {
                if (col > 8) {
                    col = 1;
                    row++;
                }
                if (row > 4) break;
                int slot = row * 9 + col++;
                boolean on = o.id().equals(look.get(kind[0]));
                inv.setItem(slot, swatch(o, on, cfg.getString(on ? "gui.chosen" : "gui.choose", on ? "<green>지금 이 모습이다" : "<gray>누르면 이걸로 바꾼다")));
                slots.put(slot, new String[]{kind[0], o.id()});
            }
            row++;
        }
        button = 49;
        List<String> lore = new ArrayList<>(cfg.getStringList("gui.button-lore"));
        lore.add(cfg.getString("gui.now", "<gray>지금: <white>{look}").replace("{look}", words(cfg, look)));
        if (waitSeconds > 0) lore.add(cfg.getString("gui.wait", "<dark_gray>…셔터 너머가 조용하다. ({seconds}초)").replace("{seconds}", String.valueOf(waitSeconds)));
        if (!notebook.isEmpty()) lore.add(cfg.getString("gui.notebook", "<dark_gray>— 지금까지 —"));
        int from = Math.max(0, notebook.size() - 12);
        for (int i = from; i < notebook.size(); i++) {
            Combo.Try t = notebook.get(i);
            lore.add(cfg.getString("gui.notebook-line", "<gray>{look} <dark_gray>→ <white>{n}").replace("{look}", words(cfg, t.look()))
                    .replace("{n}", String.valueOf(t.right())));
        }
        inv.setItem(button, named(new ItemStack(Material.IRON_DOOR), cfg.getString("gui.button", "<white>셔터 내리기"), lines(lore)));
    }

    private static List<Component> lines(List<String> mm) {
        List<Component> out = new ArrayList<>();
        for (String s : mm) out.add(Text.item(s));
        return out;
    }

    private static ItemStack named(ItemStack it, String name, List<Component> lore) {
        it.editMeta(meta -> {
            meta.itemName(Text.item(name));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
        });
        return it;
    }

    private static ItemStack swatch(Option o, boolean on, String hint) {
        ItemStack it = new ItemStack(Material.FIREWORK_STAR);
        it.editMeta(meta -> {
            if (meta instanceof FireworkEffectMeta fm) fm.setEffect(FireworkEffect.builder().withColor(Color.fromRGB(o.color() & 0xffffff)).build());
            meta.itemName(Text.item("<#" + String.format("%06x", o.color() & 0xffffff) + ">" + o.name()));
            meta.lore(List.of(Text.item(hint)));
            meta.addItemFlags(ItemFlag.values());
            if (on) meta.setEnchantmentGlintOverride(true);
        });
        return it;
    }
}
