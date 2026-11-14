package dev.stsv.core.emote;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.util.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The emote wheel's two screens (menus of the pack): the wheel (a place clicked plays its emote, right-clicked asks
 * what to put there) and the list a place is filled from.
 */
final class EmoteScreens {

    private final STSVPlugin plugin;
    private final EmoteModule emotes;

    EmoteScreens(STSVPlugin plugin, EmoteModule emotes) {
        this.plugin = plugin;
        this.emotes = emotes;
    }

    void openWheel(Player p) {
        plugin.menus().open(p, "stsv:emote_wheel", new Wheel());
    }

    private void later(Player p, Runnable then) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) then.run();
        });
    }

    /** An emote as an item to look at: the pack's icon of it. */
    static ItemStack icon(String emoteId) {
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> m.setItemModel(new NamespacedKey("stsv", "gui/" + (emoteId == null ? EmoteWheel.ICON_EMPTY : EmoteWheel.icon(emoteId)))));
        return it;
    }

    private static Object[] facts(EmoteSpec e) {
        return new Object[]{"name", e.name(), "seconds", String.format("%.1f", e.length() / 20.0),
                "kind", Lang.raw(e.loop() ? "emote.menu-loop" : "emote.menu-once")};
    }

    private final class Wheel implements MenuHandler {

        @Override
        public Map<String, String> placeholders(MenuView view) {
            Player p = view.player();
            String playing = emotes.playing(p).map(EmoteSpec::name).orElse(Lang.raw("common.none"));
            return Map.of("owned", String.valueOf(emotes.owned(p).size()), "total", String.valueOf(emotes.all().size()), "playing", playing);
        }

        @Override
        public ItemStack button(MenuView view, MenuSpec.Button button, ItemStack base) {
            String action = button.action();
            Player p = view.player();
            if (action.equals("emote:hub")) {
                // the dial's hub: what is playing now (nothing: the hint how the wheel is used)
                EmoteSpec now = emotes.playing(p).orElse(null);
                ItemStack hub = icon(now == null ? null : now.id());
                hub.editMeta(m -> {
                    m.itemName(now == null ? Lang.item("emote.wheel-hub") : Lang.item("emote.wheel-name", facts(now)));
                    m.lore(Lang.items(now == null ? "emote.wheel-hub-lore" : "emote.wheel-hub-playing"));
                });
                return hub;
            }
            if (!action.startsWith("emote:slot:")) return base;
            int slot = Integer.parseInt(action.substring("emote:slot:".length()));
            String id = emotes.wheel(p)[slot];
            EmoteSpec e = id == null ? null : emotes.all().get(id);
            ItemStack it = icon(e == null ? null : e.id());
            it.editMeta(m -> {
                if (e == null) {
                    m.itemName(Lang.item("emote.wheel-empty"));
                    m.lore(Lang.items("emote.wheel-empty-lore"));
                } else {
                    m.itemName(Lang.item("emote.wheel-name", facts(e)));
                    m.lore(Lang.items("emote.wheel-lore", facts(e)));
                    if (emotes.playing(p).filter(e::equals).isPresent()) m.setEnchantmentGlintOverride(true);
                }
            });
            return it;
        }

        @Override
        public void onAction(MenuView view, String action, ClickType click) {
            Player p = view.player();
            if (action.equals("emote:stop")) {
                if (emotes.emoting(p)) {
                    view.close();
                    emotes.stop(p);
                } else {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                }
                return;
            }
            if (!action.startsWith("emote:slot:")) return;
            int slot = Integer.parseInt(action.substring("emote:slot:".length()));
            String id = emotes.wheel(p)[slot];
            EmoteSpec e = id == null ? null : emotes.all().get(id);
            if (e == null || click.isRightClick() || click.isShiftClick()) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                later(p, () -> plugin.menus().open(p, "stsv:emote_pick", new Pick(slot)));
                return;
            }
            view.close();
            later(p, () -> emotes.play(p, e));
        }
    }

    /** The emotes there are, the player's own first: one clicked goes into the place the wheel asked for. */
    private final class Pick implements MenuHandler {
        private final int slot;
        private List<EmoteSpec> shown = List.of();

        Pick(int slot) {
            this.slot = slot;
        }

        @Override
        public Map<String, String> placeholders(MenuView view) {
            return Map.of("slot", String.valueOf(slot + 1));
        }

        @Override
        public List<ItemStack> list(MenuView view) {
            Player p = view.player();
            List<EmoteSpec> order = new ArrayList<>(emotes.all().values());
            order.sort(java.util.Comparator.comparingInt(e -> emotes.may(p, e) ? 0 : 1));
            shown = order;
            String here = emotes.wheel(p)[slot];
            List<ItemStack> out = new ArrayList<>();
            for (EmoteSpec e : order) {
                boolean mine = emotes.may(p, e), on = mine && e.id().equals(here);
                ItemStack it = icon(e.id());
                it.editMeta(m -> {
                    m.itemName(Lang.item(mine ? "emote.wheel-name" : "emote.pick-name-locked", facts(e)));
                    List<Component> lore = Lang.items(on ? "emote.pick-lore-here" : mine ? "emote.pick-lore" : "emote.pick-lore-locked", facts(e));
                    m.lore(lore);
                    m.setEnchantmentGlintOverride(on);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView view, int index, ClickType click) {
            Player p = view.player();
            if (index < 0 || index >= shown.size()) return;
            EmoteSpec e = shown.get(index);
            if (!emotes.may(p, e)) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                return;
            }
            emotes.place(p, slot, e.id());
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.5f);
            later(p, () -> openWheel(p));
        }

        @Override
        public void onAction(MenuView view, String action, ClickType click) {
            Player p = view.player();
            if (action.equals("emote:empty")) {
                emotes.place(p, slot, null);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                later(p, () -> openWheel(p));
            } else if (action.equals("emote:wheel")) {
                later(p, () -> openWheel(p));
            }
        }
    }
}
