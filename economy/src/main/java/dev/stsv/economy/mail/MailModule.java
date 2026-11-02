package dev.stsv.economy.mail;

import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 우편함: items that could not be handed over (offline, full inventory, expired listing, auction win).
 * Nothing is ever dropped on the floor or lost.
 */
public final class MailModule extends EcoModule {

    private MailStore store;

    public MailModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-mail";
    }

    @Override
    protected void start() {
        store = new MailStore(core.db());
        store.init();
    }

    public MailStore store() {
        return store;
    }

    /** Gives the item now if the player is online and has room, otherwise it waits in the mailbox. */
    public void deliver(UUID player, ItemStack item, String reason) {
        if (item == null || item.isEmpty()) return;
        Player p = Bukkit.getPlayer(player);
        // dead (respawn screen, or inside PlayerDeathEvent: the inventory is about to be cleared) -> mailbox
        if (p != null && !p.isDead() && Items.room(p.getInventory(), item) >= item.getAmount()) {
            p.getInventory().addItem(item.clone());
            return;
        }
        store.send(player, Items.bytes(item), reason, now());
        if (p != null) p.sendMessage(Lang.msg("mail.arrived", "item", Items.plainName(item)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        store.count(p.getUniqueId()).thenAccept(n -> {
            if (n > 0) sync(() -> p.sendMessage(Lang.msg("mail.waiting", "count", n)));
        });
    }

    public void open(Player p) {
        store.list(p.getUniqueId()).thenAccept(mails -> sync(() -> {
            if (!p.isOnline()) return;
            core.menus().open(p, "economy:mail", new View(mails));
        }));
    }

    private final class View implements MenuHandler {
        private final List<MailStore.Mail> mails;
        private final List<ItemStack> items = new ArrayList<>();

        View(List<MailStore.Mail> mails) {
            this.mails = new ArrayList<>(mails);
            for (MailStore.Mail m : mails) items.add(Items.fromBytes(m.item()));
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("count", String.valueOf(mails.size()));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                ItemStack shown = items.get(i).clone();
                MailStore.Mail m = mails.get(i);
                shown.editMeta(meta -> {
                    List<net.kyori.adventure.text.Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.addAll(Lang.items("mail.lore", "reason", Lang.raw("mail.reason." + m.reason())));
                    meta.lore(lore);
                });
                out.add(shown);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= mails.size()) return;
            Player p = v.player();
            MailStore.Mail m = mails.get(index);
            ItemStack item = items.get(index);
            if (Items.room(p.getInventory(), item) < item.getAmount()) {
                HudModule.flash(p, Lang.mm("mail.no-room"));
                return;
            }
            mails.remove(index);
            items.remove(index);
            store.take(m.id(), p.getUniqueId()).thenAccept(ok -> sync(() -> {
                if (ok && !p.isOnline()) store.send(p.getUniqueId(), Items.bytes(item), m.reason(), now());   // left meanwhile: back in
                else if (ok) {
                    for (ItemStack left : p.getInventory().addItem(item).values()) store.send(p.getUniqueId(), Items.bytes(left), m.reason(), now());
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
                }
                v.refresh();
            }));
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (!action.equals("mail:all")) return;
            Player p = v.player();
            int taken = 0;
            for (int i = 0; i < mails.size(); ) {
                ItemStack item = items.get(i);
                if (Items.room(p.getInventory(), item) < item.getAmount()) {
                    i++;
                    continue;
                }
                MailStore.Mail m = mails.remove(i);
                items.remove(i);
                taken++;
                store.take(m.id(), p.getUniqueId()).thenAccept(ok -> sync(() -> {
                    if (ok && !p.isOnline()) store.send(p.getUniqueId(), Items.bytes(item), m.reason(), now());
                    else if (ok) for (ItemStack left : p.getInventory().addItem(item).values()) store.send(p.getUniqueId(), Items.bytes(left), m.reason(), now());
                }));
            }
            if (taken > 0) p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
            if (!mails.isEmpty()) HudModule.flash(p, Lang.mm("mail.no-room"));
            Bukkit.getScheduler().runTaskLater(eco, v::refresh, 2L);
        }
    }
}
