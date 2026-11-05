package dev.stsv.economy.market;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.TradeRules;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
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
 * 거래소: list an item at a price, anyone buys it at once, even while the seller is offline. 5% fee on
 * sale, 10 listings (20 with a stall), 48 hours, then the item comes back by mail.
 */
public final class MarketModule extends EcoModule {

    private MarketStore store;
    private TradeRules rules;

    public MarketModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-market";
    }

    @Override
    protected void start() {
        store = new MarketStore(core.db());
        store.init();
        rules = eco.rules();
        every(1200L, this::expire);
        eco.onReset(store::reset);
    }

    @Override
    public void reload() {
        rules = eco.rules();
    }

    public MarketStore store() {
        return store;
    }

    public int limit(UUID player) {
        return rules.marketMax() + (eco.stalls() != null && eco.stalls().owns(player) ? rules.stallBonus() : 0);
    }

    /** /거래소 등록 &lt;가격&gt;: lists the held stack. */
    public void list(Player p, long price) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            p.sendMessage(Lang.msg("market.hold-item"));
            return;
        }
        if (core.economy().isCheque(hand)) {
            p.sendMessage(Lang.msg("market.no-cheques"));
            return;
        }
        if (price <= 0) {
            p.sendMessage(Lang.msg("market.usage"));
            return;
        }
        int limit = limit(p.getUniqueId());
        if (store.count(p.getUniqueId()) >= limit) {
            p.sendMessage(Lang.msg("market.full", "limit", limit));
            return;
        }
        ItemStack item = hand.clone();
        long now = now();
        MarketStore.Listing l;
        try {
            l = store.add(p.getUniqueId(), Items.bytes(item), Items.key(item), Items.plainName(item), item.getAmount(), price,
                    now, now + (long) (rules.marketHours() * 3_600_000L));
        } catch (RuntimeException e) {
            eco.getLogger().warning("거래소 등록 실패: " + e);
            p.sendMessage(dev.stsv.core.util.Text.msg("<red>저장소 오류로 등록하지 못했습니다. 물건은 그대로입니다."));
            return;
        }
        p.getInventory().setItemInMainHand(null);   // only once it is safely listed
        p.sendMessage(Lang.msg("market.listed", "item", l.itemName(), "count", l.amount(), "price", EconomyModule.format(price),
                "fee", EconomyModule.format(TradeRules.fee(price, rules.marketFee())), "hours", (long) rules.marketHours()));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.2f);
    }

    /** Buys a listing: claimed in the database first, then paid, then delivered. */
    public void buy(Player p, MarketStore.Listing l) {
        if (l.seller().equals(p.getUniqueId())) {
            HudModule.flash(p, Lang.mm("market.own"));
            return;
        }
        if (!core.economy().has(p.getUniqueId(), l.price())) {
            HudModule.flash(p, Lang.mm("market.not-enough", "price", EconomyModule.format(l.price())));
            return;
        }
        store.close(l.id(), MarketStore.SOLD, p.getUniqueId(), now()).thenAccept(ok -> sync(() -> {
            if (!ok) {
                HudModule.flash(p, Lang.mm("market.gone"));
                return;
            }
            if (!core.economy().transfer(p.getUniqueId(), l.seller(), l.price(), "market_sale")) {
                store.reopen(l);
                HudModule.flash(p, Lang.mm("market.not-enough", "price", EconomyModule.format(l.price())));
                return;
            }
            long fee = TradeRules.fee(l.price(), rules.marketFee());
            if (fee > 0 && !core.economy().withdraw(l.seller(), fee, "market_fee")) {
                eco.getLogger().warning("거래소 수수료를 걷지 못함: 판매자 " + l.seller() + " 글 " + l.id());
            }
            eco.mail().deliver(p.getUniqueId(), Items.fromBytes(l.item()), "market_buy");
            p.sendMessage(Lang.msg("market.bought", "item", l.itemName(), "count", l.amount(), "price", EconomyModule.format(l.price()),
                    "seller", Players.name(l.seller())));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
            Player seller = Bukkit.getPlayer(l.seller());
            if (seller != null) {
                seller.sendMessage(Lang.msg("market.sold", "item", l.itemName(), "count", l.amount(), "price", EconomyModule.format(l.price()),
                        "fee", EconomyModule.format(fee), "buyer", p.getName()));
                seller.playSound(seller.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
                store.told(l.id());
            }
        }));
    }

    public void cancel(Player p, MarketStore.Listing l) {
        if (!l.seller().equals(p.getUniqueId()) && !p.hasPermission("stsv.economy.admin")) return;
        store.close(l.id(), MarketStore.CANCELLED, null, now()).thenAccept(ok -> sync(() -> {
            if (!ok) return;
            eco.mail().deliver(l.seller(), Items.fromBytes(l.item()), "market_cancel");
            HudModule.flash(p, Lang.mm("market.cancelled", "item", l.itemName()));
        }));
    }

    private void expire() {
        for (MarketStore.Listing l : store.expired(now())) {
            store.close(l.id(), MarketStore.EXPIRED, null, now()).thenAccept(ok -> sync(() -> {
                if (!ok) return;
                eco.mail().deliver(l.seller(), Items.fromBytes(l.item()), "market_expired");
                Player s = Bukkit.getPlayer(l.seller());
                if (s != null) s.sendMessage(Lang.msg("market.expired", "item", l.itemName()));
            }));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        store.untold(p.getUniqueId()).thenAccept(sold -> sync(() -> {
            if (sold.isEmpty() || !p.isOnline()) return;
            long total = sold.stream().mapToLong(MarketStore.Sold::price).sum();
            p.sendMessage(Lang.msg("market.away", "count", sold.size(), "amount", EconomyModule.format(total)));
            for (MarketStore.Sold s : sold.subList(0, Math.min(5, sold.size()))) {
                p.sendMessage(Lang.mm("market.away-line", "item", s.itemName(), "count", s.amount(), "price", EconomyModule.format(s.price()),
                        "buyer", Players.name(s.buyer())));
            }
        }));
    }

    // ------------------------------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------------------------------

    public void open(Player p, String search, boolean mine) {
        core.menus().open(p, "economy:market", new View(search, mine));
    }

    private final class View implements MenuHandler {
        private final String search;
        private final boolean mine;
        private List<MarketStore.Listing> shown = List.of();
        private long armed = -1, armedAt;

        View(String search, boolean mine) {
            this.search = search;
            this.mine = mine;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("mode", Lang.raw(mine ? "market.mode-mine" : search != null ? "market.mode-search" : "market.mode-all", "search", search == null ? "" : search),
                    "count", String.valueOf(shown.size()),
                    "mine", String.valueOf(store.count(v.player().getUniqueId())), "limit", String.valueOf(limit(v.player().getUniqueId())),
                    "balance", EconomyModule.format(core.economy().balance(v.player().getUniqueId())));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            UUID me = v.player().getUniqueId();
            List<MarketStore.Listing> all = new ArrayList<>();
            for (MarketStore.Listing l : store.active()) {
                if (mine && !l.seller().equals(me)) continue;
                if (search != null && !l.itemName().contains(search) && !l.itemKey().contains(search)) continue;
                all.add(l);
            }
            shown = all;
            boolean crash = eco.events().isActive("crash");
            List<ItemStack> out = new ArrayList<>();
            for (MarketStore.Listing l : all) {
                ItemStack it = Items.fromBytes(l.item());
                boolean isArmed = armed == l.id() && now() - armedAt < 5000;
                List<Component> extra = Lang.items(crash ? "market.lore-crash" : "market.lore",
                        "price", EconomyModule.format(l.price()), "unit", String.format("%,.1f", l.unit()),
                        "seller", Players.name(l.seller()), "left", dev.stsv.economy.event.EventModule.hoursText(l.expires() - now()));
                extra.addAll(Lang.items(l.seller().equals(me) ? "market.lore-own" : isArmed ? "market.lore-confirm" : "market.lore-buy"));
                it.editMeta(meta -> {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.addAll(extra);
                    meta.lore(lore);
                    if (isArmed) meta.setEnchantmentGlintOverride(true);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= shown.size()) return;
            MarketStore.Listing l = shown.get(index);
            Player p = v.player();
            if (l.seller().equals(p.getUniqueId()) || (click.isShiftClick() && p.hasPermission("stsv.economy.admin"))) {
                cancel(p, l);
                Bukkit.getScheduler().runTaskLater(eco, v::refresh, 2L);
                return;
            }
            // two clicks to buy: the first one only arms it
            if (armed != l.id() || now() - armedAt > 5000) {
                armed = l.id();
                armedAt = now();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                v.refresh();
                return;
            }
            armed = -1;
            buy(p, l);
            Bukkit.getScheduler().runTaskLater(eco, v::refresh, 2L);
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            switch (action) {
                case "market:mine" -> Bukkit.getScheduler().runTask(eco, () -> open(p, null, !mine));
                case "market:refresh" -> v.refresh();
                default -> { }
            }
        }
    }
}
