package dev.stsv.economy.stamp;

import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.calendar.DailyListener;
import dev.stsv.economy.mission.MissionSpec;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 도장 (README "도장"; stamps.yml, see {@link StampSpec}): earned by missions, 주민의 부탁, the community request, the fishing
 * tournament and 행사, never traded. /도장 shows this month's 도장판 (every stamp earned fills the next cell; a few cells give a
 * small gift, the last the month's title) and the 도장 가게 (titles, limited cosmetics, mission reroll tickets: nothing that
 * earns money).
 */
public final class StampModule extends EcoModule implements DailyListener {

    static final String MENU = "economy:stamps";
    static final String TOGGLE = "stamp:toggle";

    private StampSpec spec;
    private StampStore store;
    private ZoneId zone;

    public StampModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-stamps";
    }

    @Override
    protected void start() {
        load();
        store = new StampStore(core.db());
        store.init(eco.calendar().day());
        eco.calendar().onDaily(this);
        eco.onReset(store::reset);
        core.admin().add(dev.stsv.economy.EcoPlugin.ADMIN_OWNER, "도장", "정보|주기|빼기|뽑기권 <플레이어> [수]",
                "도장 보기 / 주기(도장판도 채움) / 빼기 / 미션 다시 뽑기권 주기", this::admin, this::adminComplete);
    }

    @Override
    public void reload() {
        load();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = StampSpec.parse(eco.data("stamps.yml"), problems);
        zone = ZoneId.of(eco.getConfig().getString("time.zone"));
        problems.forEach(p -> eco.getLogger().warning("[stamps.yml] " + p));
    }

    @Override
    public void newDay(int day, long dayStart) {
        store.newDay(day);
    }

    public StampSpec spec() {
        return spec;
    }

    public int balance(UUID player) {
        return store.account(player).balance;
    }

    public int rerolls(UUID player) {
        return store.account(player).rerolls;
    }

    /** Uses one mission reroll ticket (bought or from the board); false if there is none. */
    public boolean takeReroll(UUID player) {
        return store.takeReroll(player);
    }

    private String month() {
        return StampSpec.month(now(), zone);
    }

    // ------------------------------------------------------------------------------------------
    // Earning
    // ------------------------------------------------------------------------------------------

    /**
     * Gives n stamps (online or not): the balance, this month's board (and the gifts of the cells it fills). The player is told
     * if online; `why` is a message key naming the source (stamp.why-*).
     */
    public void give(UUID player, int n, String why) {
        if (n <= 0) return;
        store.earn(player, n);
        String month = month();
        int before = store.board(player, month);
        int after = spec.fill(before, n);
        if (after != before) store.setBoard(player, month, after);
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            p.sendMessage(Lang.msg("stamp.got", "n", n, "why", Lang.raw(why), "balance", balance(player), "filled", after, "cells", spec.cells()));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.6f);
        }
        for (int cell : spec.crossed(before, after)) {
            StampSpec.Reward r = spec.cellReward(cell, StampSpec.monthOf(month));
            List<String> got = grant(player, r, "stamp_board");
            if (p != null && !got.isEmpty()) {
                p.sendMessage(Lang.msg(cell == spec.cells() ? "stamp.board-done" : "stamp.board-cell", "cell", cell,
                        "rewards", String.join("<gray>, </gray>", got)));
                if (cell == spec.cells()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            }
        }
    }

    /** Gives stamps from a source capped per day (stamps.yml `<source>` and `<source>-daily`). */
    public void giveCapped(UUID player, String source, String why) {
        giveCapped(player, source, spec.earn(source), spec.earn(source + "-daily"), why);
    }

    /** Gives up to `want` stamps from a source that gives at most `cap` a day (cap <= 0: none). */
    public void giveCapped(UUID player, String source, int want, int cap, String why) {
        int fit = StampSpec.capped(want, store.capped(player, source), cap);
        if (fit <= 0) return;
        store.addCapped(player, source, fit);
        give(player, fit, why);
    }

    /** 낚시대회: the players who caught at least one fish in the tournament that just ended. */
    public void tournament(Collection<UUID> caught) {
        for (UUID u : caught) {
            core.profiles().add(u, "tournaments", 1);
            giveCapped(u, StampSpec.TOURNAMENT, "stamp.why-tournament");
        }
    }

    /** 행사 (긴급 납품): the first delivery of a player to a running one. */
    public void event(UUID player) {
        giveCapped(player, StampSpec.EVENT, "stamp.why-event");
    }

    /** The weekly community request reached: a contributor who was paid. */
    public void community(UUID player) {
        give(player, spec.earn(StampSpec.COMMUNITY), "stamp.why-community");
    }

    /** Gives what a reward holds (items to the inventory or the mailbox); returns its parts as MiniMessage. */
    public List<String> grant(UUID player, StampSpec.Reward r, String reason) {
        List<String> out = new ArrayList<>();
        for (String i : r.items()) {
            MissionSpec.ItemReward ir = MissionSpec.item(i);
            if (ir == null) continue;
            Items.create(ir.id(), ir.amount()).ifPresent(it -> eco.mail().deliver(player, it, reason));
            out.add("<white>" + Items.mmName(ir.id()) + " ×" + ir.amount());
        }
        if (r.title() != null && core.profiles().grantTitle(player, r.title())) out.add(titleName(r.title()));
        if (r.cosmetic() != null) {
            String name = Players.accountName(player);
            if (name != null && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "관리자 치장 지급 " + name + " " + r.cosmetic())) {
                out.add("<white>" + Items.mmName(r.cosmetic()));
            } else eco.getLogger().warning("[도장] " + player + " 에게 치장 " + r.cosmetic() + " 을(를) 주지 못함");
        }
        if (r.rerolls() > 0) {
            store.addRerolls(player, r.rerolls());
            out.add(Lang.raw("stamp.reward-rerolls", "n", r.rerolls()));
        }
        return out;
    }

    private String titleName(String id) {
        return core.profiles().title(id).map(t -> t.name()).orElse(id);
    }

    /** Reward parts as MiniMessage, without giving them (the menu). */
    List<String> describe(StampSpec.Reward r) {
        List<String> out = new ArrayList<>();
        for (String i : r.items()) {
            MissionSpec.ItemReward ir = MissionSpec.item(i);
            if (ir != null) out.add("<white>" + Items.mmName(ir.id()) + " ×" + ir.amount());
        }
        if (r.title() != null) out.add(Lang.raw("stamp.reward-title", "title", titleName(r.title())));
        if (r.cosmetic() != null) out.add(Lang.raw("stamp.reward-cosmetic", "name", Items.mmName(r.cosmetic())));
        if (r.rerolls() > 0) out.add(Lang.raw("stamp.reward-rerolls", "n", r.rerolls()));
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // 도장 가게
    // ------------------------------------------------------------------------------------------

    private boolean owned(Player p, StampSpec.ShopItem item) {
        UUID u = p.getUniqueId();
        if (item.reward().title() != null) {
            return core.profiles().title(item.reward().title()).map(t -> core.profiles().ownedTitles(u).contains(t.id())).orElse(false);
        }
        if (item.reward().cosmetic() != null) return core.cosmetics() != null && core.cosmetics().owned(u).contains(item.reward().cosmetic());
        return false;
    }

    /** Returns null on success, else the refusal key. */
    String buy(Player p, StampSpec.ShopItem item) {
        UUID u = p.getUniqueId();
        String refused = StampSpec.canBuy(item, balance(u), owned(p, item));
        if (refused != null) return refused;
        if (!store.spend(u, item.price())) return "stamp.shop-short";
        List<String> got = grant(u, item.reward(), "stamp_shop");
        p.sendMessage(Lang.msg("stamp.bought", "price", item.price(), "rewards", String.join("<gray>, </gray>", got), "balance", balance(u)));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1.2f);
        eco.getLogger().info("[도장 가게] " + p.getName() + ": " + item.id() + " (도장 " + item.price() + ")");
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // /도장
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        core.menus().open(p, MENU, new View(false));
    }

    public void openShop(Player p) {
        core.menus().open(p, MENU, new View(true));
    }

    private final class View implements MenuHandler {
        private boolean shop;

        View(boolean shop) {
            this.shop = shop;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            String month = month();
            Map<String, String> m = new HashMap<>();
            m.put("balance", String.valueOf(balance(u)));
            m.put("filled", String.valueOf(store.board(u, month)));
            m.put("cells", String.valueOf(spec.cells()));
            m.put("month", String.valueOf(StampSpec.monthOf(month)));
            m.put("rerolls", String.valueOf(rerolls(u)));
            m.put("count", String.valueOf(balance(u)));
            return m;
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (base == null || !b.action().equals(TOGGLE)) return base;
            ItemStack it = base.clone();
            it.editMeta(meta -> {
                meta.itemName(Lang.item(shop ? "stamp.toggle-board" : "stamp.toggle-shop"));
                meta.setEnchantmentGlintOverride(shop);
            });
            return it;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            return shop ? shopCards(v.player()) : boardCells(v.player().getUniqueId());
        }

        private List<ItemStack> boardCells(UUID u) {
            String month = month();
            int filled = store.board(u, month), mo = StampSpec.monthOf(month);
            List<ItemStack> out = new ArrayList<>();
            for (int n = 1; n <= spec.cells(); n++) {
                StampSpec.Reward r = spec.cellReward(n, mo);
                boolean done = n <= filled, gift = !r.isEmpty();
                Material mat = done ? Material.RED_DYE : n == spec.cells() ? Material.NAME_TAG : gift ? Material.CHEST : Material.GRAY_DYE;
                ItemStack it = new ItemStack(mat);
                int cell = n;
                it.editMeta(meta -> {
                    meta.itemName(Lang.item(done ? "stamp.cell-done" : "stamp.cell", "n", cell));
                    List<Component> lore = new ArrayList<>();
                    if (gift) {
                        lore.add(Lang.item(cell == spec.cells() ? "stamp.cell-last" : "stamp.cell-gift"));
                        for (String s : describe(r)) lore.add(Text.item("<dark_gray> · </dark_gray>" + s));
                    }
                    if (!done && cell == filled + 1) lore.add(Lang.item("stamp.cell-next"));
                    meta.lore(lore);
                    meta.setEnchantmentGlintOverride(done && gift);
                    meta.addItemFlags(ItemFlag.values());
                });
                out.add(it);
            }
            return out;
        }

        private List<ItemStack> shopCards(Player p) {
            int bal = balance(p.getUniqueId());
            List<ItemStack> out = new ArrayList<>();
            for (StampSpec.ShopItem item : spec.shop().values()) {
                StampSpec.Reward r = item.reward();
                String icon = item.icon() != null ? item.icon() : r.cosmetic() != null ? r.cosmetic() : r.title() != null ? "minecraft:name_tag" : "minecraft:paper";
                ItemStack it = Items.create(icon, 1).orElseGet(() -> new ItemStack(Material.PAPER));
                boolean own = owned(p, item);
                String name = item.name() != null ? item.name() : r.title() != null ? titleName(r.title()) : Items.mmName(icon);
                it.editMeta(meta -> {
                    meta.itemName(Text.item(name));
                    List<Component> lore = new ArrayList<>();
                    for (String s : describe(r)) lore.add(Text.item("<dark_gray> · </dark_gray>" + s));
                    lore.add(Component.empty());
                    lore.add(Lang.item("stamp.shop-price", "price", item.price()));
                    lore.add(Lang.item(own ? "stamp.shop-lore-owned" : bal >= item.price() ? "stamp.shop-lore-buy" : "stamp.shop-lore-short",
                            "need", item.price() - bal));
                    meta.lore(lore);
                    meta.setEnchantmentGlintOverride(own);
                    meta.addItemFlags(ItemFlag.values());
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (!shop) return;
            List<StampSpec.ShopItem> items = new ArrayList<>(spec.shop().values());
            if (index < 0 || index >= items.size()) return;
            String refused = buy(v.player(), items.get(index));
            if (refused != null) HudModule.flash(v.player(), Lang.mm(refused));
            v.refresh();
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (!action.equals(TOGGLE)) return;
            shop = !shop;
            v.player().playSound(v.player().getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
            v.page(0);
            v.refresh();
        }
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 도장
    // ------------------------------------------------------------------------------------------

    private void admin(CommandSender s, String[] a) {
        String usage = "<red>/관리자 도장 정보|주기|빼기|뽑기권 <플레이어> [수]";
        if (a.length < 2) {
            s.sendMessage(Text.msg(usage));
            return;
        }
        int n = 1;
        if (a.length >= 3) {
            try {
                n = Math.max(1, Integer.parseInt(a[2]));
            } catch (NumberFormatException e) {
                s.sendMessage(Text.msg(usage));
                return;
            }
        }
        int amount = n;
        Players.find(a[1], found -> sync(() -> {
            if (found.isEmpty()) {
                s.sendMessage(Text.msg("<red>그런 플레이어가 없습니다."));
                return;
            }
            UUID u = found.get();
            switch (a[0]) {
                case "정보" -> {
                    StampStore.Account acc = store.account(u);
                    s.sendMessage(Text.msg("<gold>" + a[1] + " <gray>도장 <white>" + acc.balance + "</white> · 모은 도장 " + acc.earned
                            + " · 이달 도장판 " + store.board(u, month()) + "/" + spec.cells() + " · 다시 뽑기권 " + acc.rerolls));
                }
                case "주기" -> {
                    give(u, amount, "stamp.why-admin");
                    s.sendMessage(Text.msg("<green>" + a[1] + " 에게 도장 " + amount + "개"));
                }
                case "빼기" -> s.sendMessage(Text.msg(store.spend(u, amount) ? "<green>" + a[1] + " 의 도장 " + amount + "개를 뺐습니다."
                        : "<red>도장이 모자랍니다."));
                case "뽑기권" -> {
                    store.addRerolls(u, amount);
                    s.sendMessage(Text.msg("<green>" + a[1] + " 에게 미션 다시 뽑기권 " + amount + "장"));
                }
                default -> s.sendMessage(Text.msg(usage));
            }
        }));
    }

    private List<String> adminComplete(CommandSender s, String[] a) {
        if (a.length == 1) return List.of("정보", "주기", "빼기", "뽑기권");
        if (a.length == 2) return Players.complete(s, a[1]);
        return List.of();
    }
}
