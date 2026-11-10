package dev.stsv.economy.market;

import dev.stsv.core.nickname.Names;
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
 * sale, 10 listings, 48 hours, then the item comes back by mail.
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
        return rules.marketMax();
    }

    /** /거래소 등록 &lt;가격&gt;: lists the held stack. */
    public void list(Player p, long price) {
        list(p, p.getInventory().getHeldItemSlot(), price);
    }

    /**
     * Why the stack in this inventory slot cannot be listed now (a message key), or null. The 판매 등록 menu shows it on its
     * 확인 button before anything is tried.
     */
    public String problem(Player p, int slot) {
        ItemStack it = slot < 0 ? null : p.getInventory().getItem(slot);
        if (it == null || it.isEmpty()) return "market.hold-item";
        if (core.economy().isCheque(it)) return "market.no-cheques";
        if (store.count(p.getUniqueId()) >= limit(p.getUniqueId())) return "market.full";
        return null;
    }

    /** Lists the stack in one slot of the player's inventory (0-8 hotbar, 9-35 the rest); true when it is listed. */
    public boolean list(Player p, int slot, long price) {
        String problem = problem(p, slot);
        if (problem != null) {
            p.sendMessage(Lang.msg(problem, "limit", limit(p.getUniqueId())));
            return false;
        }
        if (price <= 0) {
            p.sendMessage(Lang.msg("market.usage"));
            return false;
        }
        ItemStack item = p.getInventory().getItem(slot).clone();
        long now = now();
        MarketStore.Listing l;
        try {
            l = store.add(p.getUniqueId(), Items.bytes(item), Items.key(item), Items.plainName(item), item.getAmount(), price,
                    now, now + (long) (rules.marketHours() * 3_600_000L));
        } catch (RuntimeException e) {
            eco.getLogger().warning("거래소 등록 실패: " + e);
            p.sendMessage(dev.stsv.core.util.Text.msg("<red>저장소 오류로 등록하지 못했습니다. 물건은 그대로입니다."));
            return false;
        }
        p.getInventory().setItem(slot, null);   // only once it is safely listed
        p.sendMessage(Lang.msg("market.listed", "item", name(l), "count", l.amount(), "price", EconomyModule.format(price),
                "fee", EconomyModule.format(fee(price)), "hours", (long) rules.marketHours()));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.2f);
        return true;
    }

    /** The 거래소 fee on a sale at this price (0 during 수수료 면제). */
    public long fee(long price) {
        return eco.feesWaived() ? 0 : TradeRules.fee(price, rules.marketFee());
    }

    /** What the shop pays for this many of an item at its base price (0: the shop does not buy it). For the 시세 button and credit. */
    public double shopValue(String itemKey, int amount) {
        var e = eco.shop().catalog().sell().get(itemKey);
        return e == null ? 0 : e.base() * Math.max(1, amount);
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
            long fee = fee(l.price());
            if (fee > 0 && !core.economy().withdraw(l.seller(), fee, "market_fee")) {
                eco.getLogger().warning("거래소 수수료를 걷지 못함: 판매자 " + l.seller() + " 글 " + l.id());
            }
            eco.mail().deliver(p.getUniqueId(), Items.fromBytes(l.item()), "market_buy");
            // achievements and missions: only at a meaningful price, once a day per buyer (no wash trades)
            if (eco.credit().credit(dev.stsv.economy.TradeCredit.MARKET, l.seller(), p.getUniqueId(), l.price(),
                    shopValue(l.itemKey(), l.amount()), rules, now())) {
                core.profiles().add(l.seller(), "market_sold", 1);
            }
            p.sendMessage(Lang.msg("market.bought", "item", name(l), "count", l.amount(), "price", EconomyModule.format(l.price()),
                    "seller", Players.name(l.seller())));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
            Player seller = Bukkit.getPlayer(l.seller());
            if (seller != null) {
                seller.sendMessage(Lang.msg("market.sold", "item", name(l), "count", l.amount(), "price", EconomyModule.format(l.price()),
                        "fee", EconomyModule.format(fee), "buyer", Names.mini(p)));
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
            HudModule.flash(p, Lang.mm("market.cancelled", "item", name(l)));
        }));
    }

    private void expire() {
        for (MarketStore.Listing l : store.expired(now())) {
            store.close(l.id(), MarketStore.EXPIRED, null, now()).thenAccept(ok -> sync(() -> {
                if (!ok) return;
                eco.mail().deliver(l.seller(), Items.fromBytes(l.item()), "market_expired");
                Player s = Bukkit.getPlayer(l.seller());
                if (s != null) s.sendMessage(Lang.msg("market.expired", "item", name(l)));
            }));
        }
    }

    /** From the listed item itself, so it reads in each player's language (item_name is kept for search). */
    private static String name(MarketStore.Listing l) {
        return Items.mmName(Items.fromBytes(l.item()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        store.untold(p.getUniqueId()).thenAccept(sold -> sync(() -> {
            if (sold.isEmpty() || !p.isOnline()) return;
            long total = sold.stream().mapToLong(MarketStore.Sold::price).sum();
            p.sendMessage(Lang.msg("market.away", "count", sold.size(), "amount", EconomyModule.format(total)));
            for (MarketStore.Sold s : sold.subList(0, Math.min(5, sold.size()))) {
                p.sendMessage(Lang.mm("market.away-line", "item", Items.mmName(Items.fromBytes(s.item())), "count", s.amount(), "price", EconomyModule.format(s.price()),
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
            return Map.of("mode", Lang.raw(mine ? "market.mode-mine" : search != null ? "market.mode-search" : "market.mode-all", "search", search == null ? "" : titleText(search, 8)),
                    "search", search == null ? Lang.raw("market.no-search") : titleText(search, 16),
                    "mine_toggle", Lang.raw(mine ? "market.show-all" : "market.show-mine"),
                    "fee", String.valueOf(Math.round(rules.marketFee() * 100)), "hours", String.valueOf((long) rules.marketHours()),
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
                case "market:sell" -> Bukkit.getScheduler().runTask(eco, () -> openSell(p));
                case "market:search" -> Bukkit.getScheduler().runTask(eco, () -> {
                    if (click.isRightClick()) {
                        open(p, null, false);
                        return;
                    }
                    core.menus().prompt(p, Lang.msg("market.search-ask"), text -> {
                        if (p.isOnline()) open(p, text == null || text.isBlank() ? search : text.trim(), false);
                    });
                });
                default -> { }
            }
        }
    }

    /** The actions of the 거래소 list (economy:market) besides the built-in ones. */
    public static final java.util.Set<String> ACTIONS = java.util.Set.of("market:mine", "market:refresh", "market:sell", "market:search");

    /** Short and without tags, for a menu title (a search word is the player's own text). */
    static String titleText(String s, int max) {
        String t = s.length() > max ? s.substring(0, max) + "…" : s;
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(t);
    }

    // ------------------------------------------------------------------------------------------
    // 판매 등록 (economy:market_sell)
    // ------------------------------------------------------------------------------------------

    /** The 판매 등록 menu: the held stack first (any other one by clicking it in the inventory below), then the price. */
    public void openSell(Player p) {
        new SellMenu(p).open(p);
    }

    private final class SellMenu extends PriceMenu {
        private int slot;

        SellMenu(Player p) {
            super(eco, 100);
            int held = p.getInventory().getHeldItemSlot();
            ItemStack hand = p.getInventory().getItem(held);
            slot = hand == null || hand.isEmpty() ? -1 : held;
            price = suggested(p);
        }

        private ItemStack item(Player p) {
            ItemStack it = slot < 0 ? null : p.getInventory().getItem(slot);
            return it == null || it.isEmpty() ? null : it;
        }

        /** The shop's value of the stack (rounded up), else 100 G. */
        private long suggested(Player p) {
            long shop = shopPrice(p);
            return shop > 0 ? shop : 100;
        }

        private long shopPrice(Player p) {
            ItemStack it = item(p);
            return it == null ? 0 : (long) Math.ceil(shopValue(Items.key(it), it.getAmount()) - 1e-9);
        }

        @Override
        protected long shopPrice(MenuView v) {
            return shopPrice(v.player());
        }

        @Override
        protected long min(MenuView v) {
            return 1;
        }

        @Override
        protected String menuId() {
            return "economy:market_sell";
        }

        @Override
        protected Map<String, String> values(MenuView v) {
            Player p = v.player();
            ItemStack it = item(p);
            long fee = fee(price);
            Map<String, String> m = new java.util.HashMap<>();
            m.put("item", it == null ? Lang.raw("price.no-item") : Items.mmName(it) + " ×" + it.getAmount());
            m.put("unit", it == null ? "-" : EconomyModule.format(Math.round((double) price / it.getAmount())));
            m.put("fee_rate", String.valueOf(Math.round(rules.marketFee() * 100)));
            m.put("fee", EconomyModule.format(fee));
            m.put("receive", EconomyModule.format(price - fee));
            m.put("hours", String.valueOf((long) rules.marketHours()));
            m.put("mine", String.valueOf(store.count(p.getUniqueId())));
            m.put("limit", String.valueOf(limit(p.getUniqueId())));
            long shop = shopPrice(p);
            m.put("shop", shop > 0 ? EconomyModule.format(shop) : Lang.raw("price.no-shop"));
            m.put("credit", EconomyModule.format(rules.creditFloor(it == null ? 0 : shopValue(Items.key(it), it.getAmount()))));
            return m;
        }

        @Override
        public ItemStack button(MenuView v, dev.stsv.core.gui.menu.MenuSpec.Button b, ItemStack base) {
            Player p = v.player();
            if (b.key() == 'S') {
                ItemStack it = item(p);
                if (it == null) return base;
                ItemStack show = it.clone();
                show.editMeta(meta -> {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.addAll(Lang.items("price.picked-lore"));
                    meta.lore(lore);
                });
                return show;
            }
            if (b.key() == 'C') {
                String problem = problem(p, slot);
                if (problem != null) return PriceMenu.blocked(base, problem.equals("market.hold-item") ? "price.pick-item" : problem,
                        "limit", limit(p.getUniqueId()));
            }
            return base;
        }

        @Override
        public void onPlayerInventoryClick(MenuView v, int clicked, ClickType click) {
            Player p = v.player();
            if (clicked < 0 || clicked > 35) return;
            ItemStack it = p.getInventory().getItem(clicked);
            if (it == null || it.isEmpty()) return;
            slot = clicked;
            if (!edited) price = suggested(p);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.6f);
            v.refresh();
        }

        @Override
        protected void confirm(MenuView v) {
            Player p = v.player();
            if (problem(p, slot) != null) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                return;
            }
            long at = price;
            Bukkit.getScheduler().runTask(eco, () -> {
                if (MarketModule.this.list(p, slot, at)) MarketModule.this.open(p, null, true);
            });
        }

        @Override
        protected void back(Player p) {
            MarketModule.this.open(p, null, false);
        }
    }
}
