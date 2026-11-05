package dev.stsv.economy.request;

import org.bukkit.Bukkit;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.calendar.DailyListener;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.shop.ShopCatalog;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 의뢰 게시판: 8 delivery requests a day (reward 1.3x the shop's base price + job XP), 3 per person
 * per day, so everyone has a steady base income however late they join. Plus the week's 공동 의뢰:
 * a server-wide goal; when reached everyone who helped is paid and next week's openings come a day early.
 */
public final class RequestModule extends EcoModule implements DailyListener {

    private RequestSpec spec;
    private RequestStore store;
    private List<RequestSpec.Request> today = List.of();

    public RequestModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-requests";
    }

    @Override
    protected void start() {
        store = new RequestStore(core.db());
        load();
        store.init(eco.calendar().day());
        draw(eco.calendar().day());
        eco.calendar().onDaily(this);
        eco.onReset(store::reset);
    }

    @Override
    public void reload() {
        load();
        draw(eco.calendar().day());
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = RequestSpec.parse(eco.data("requests.yml"), problems);
        for (RequestSpec.Entry e : spec.pool()) if (Items.create(e.item(), 1).isEmpty()) problems.add("pool: 없는 아이템 " + e.item());
        problems.forEach(p -> eco.getLogger().warning("[requests.yml] " + p));
    }

    private void draw(int day) {
        long seed = eco.kv().getLong("request.seed", 0);
        if (seed == 0) {
            seed = System.nanoTime();
            eco.kv().set("request.seed", seed);
        }
        ShopCatalog cat = eco.shop() == null ? null : eco.shop().catalog();
        today = spec.draw(day, seed, id -> cat == null || !cat.sell().containsKey(id) ? 1 : cat.sell().get(id).base());
    }

    @Override
    public void newDay(int day, long dayStart) {
        store.newDay(day);
        draw(day);
        Bukkit.broadcast(Lang.msg("request.new-day", "count", today.size()));
    }

    public List<RequestSpec.Request> today() {
        return today;
    }

    public RequestSpec.Community community() {
        return spec.communityFor(eco.calendar().week());
    }

    public RequestStore store() {
        return store;
    }

    // ------------------------------------------------------------------------------------------

    /** Returns null on success, else the refusal key. */
    public String deliver(Player p, int index) {
        if (index < 0 || index >= today.size()) return "request.no-such";
        RequestSpec.Request r = today.get(index);
        UUID u = p.getUniqueId();
        if (store.done(u).contains(index)) return "request.already";
        if (store.done(u).size() >= spec.perPlayer()) return "request.limit";
        int have = Items.count(p.getInventory(), it -> Items.plain(it, r.item()));
        if (have < r.amount()) return "request.missing";
        Items.remove(p.getInventory(), it -> Items.plain(it, r.item()), r.amount());
        store.complete(u, index);
        core.economy().deposit(u, r.reward(), "request");
        eco.stocks().recordSale(u, r.item(), r.reward());
        if (r.job() != null && r.xp() > 0) eco.jobs().addXp(u, r.job(), r.xp());
        p.sendMessage(Lang.msg("request.done", "item", eco.shop().itemName(r.item()), "count", r.amount(), "reward", EconomyModule.format(r.reward()),
                "left", spec.perPlayer() - store.done(u).size()));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
        return null;
    }

    /** Hands in every matching item for the week's shared goal. */
    public String contribute(Player p) {
        RequestSpec.Community c = community();
        int week = eco.calendar().week();
        if (c == null) return "request.no-community";
        if (store.completed(week)) return "request.community-done";
        long need = c.target() - store.progress(week);
        int have = Items.count(p.getInventory(), it -> Items.plain(it, c.item()));
        if (have <= 0) return "request.community-missing";
        int give = (int) Math.min(have, need);
        Items.remove(p.getInventory(), it -> Items.plain(it, c.item()), give);
        store.contribute(week, p.getUniqueId(), give);
        p.sendMessage(Lang.msg("request.contributed", "count", give, "item", eco.shop().itemName(c.item()),
                "progress", String.format("%,d", store.progress(week)), "target", String.format("%,d", c.target())));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.3f);
        if (store.progress(week) >= c.target()) complete(c, week);
        return null;
    }

    private void complete(RequestSpec.Community c, int week) {
        store.markCompleted(week, now());
        Map<UUID, Long> who = store.contributors(week);
        for (UUID u : who.keySet()) if (core.economy().hasAccount(u)) core.economy().deposit(u, c.reward(), "community");
        Bukkit.broadcast(Lang.msg("request.community-complete",
                "name", c.name(), "count", who.size(), "reward", EconomyModule.format(c.reward())));
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
    }

    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        core.menus().open(p, "economy:requests", new View());
    }

    private final class View implements MenuHandler {
        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            return Map.of("done", String.valueOf(store.done(u).size()), "max", String.valueOf(spec.perPlayer()), "day", String.valueOf(eco.calendar().day()));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            UUID u = v.player().getUniqueId();
            List<ItemStack> out = new ArrayList<>();
            for (RequestSpec.Request r : today) {
                boolean done = store.done(u).contains(r.index());
                ItemStack it = Items.create(r.item(), Math.min(r.amount(), 64)).orElse(new ItemStack(Material.PAPER));
                int have = Items.count(v.player().getInventory(), x -> Items.plain(x, r.item()));
                it.editMeta(meta -> {
                    meta.itemName(Lang.item("request.name", "item", eco.shop().itemName(r.item()), "count", r.amount()));
                    List<Component> lore = new ArrayList<>(Lang.items("request.lore", "reward", EconomyModule.format(r.reward()),
                            "have", have, "count", r.amount()));
                    if (r.job() != null && r.xp() > 0) {
                        var j = eco.jobs().spec().jobs().get(r.job());
                        lore.addAll(Lang.items("request.lore-xp", "color", j == null ? "white" : j.color(), "job", j == null ? r.job() : j.name(), "xp", (long) r.xp()));
                    }
                    lore.addAll(Lang.items(done ? "request.lore-done" : have >= r.amount() ? "request.lore-ready" : "request.lore-missing"));
                    meta.lore(lore);
                    meta.setEnchantmentGlintOverride(done);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            String refused = deliver(v.player(), index);
            if (refused != null) HudModule.flash(v.player(), Lang.mm(refused, "max", spec.perPlayer()));
            v.refresh();
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (!action.equals("request:community")) return;
            String refused = contribute(v.player());
            if (refused != null) HudModule.flash(v.player(), Lang.mm(refused, "item", community() == null ? "" : eco.shop().itemName(community().item())));
            v.refresh();
        }

        @Override
        public ItemStack button(MenuView v, dev.stsv.core.gui.menu.MenuSpec.Button button, ItemStack base) {
            if (!button.action().equals("request:community")) return base;
            RequestSpec.Community c = community();
            if (c == null) return null;
            int week = eco.calendar().week();
            ItemStack it = Items.create(c.item(), 1).orElse(base);
            long mine = store.contributors(week).getOrDefault(v.player().getUniqueId(), 0L);
            int pct = (int) Math.min(100, store.progress(week) * 100 / Math.max(1, c.target()));
            it.editMeta(meta -> {
                meta.itemName(Lang.item("request.community-name", "name", c.name()));
                meta.lore(Lang.items(store.completed(week) ? "request.community-lore-done" : "request.community-lore",
                        "item", eco.shop().itemName(c.item()), "progress", String.format("%,d", store.progress(week)),
                        "target", String.format("%,d", c.target()), "pct", pct, "bar", bar(pct), "mine", String.format("%,d", mine),
                        "reward", EconomyModule.format(c.reward()), "contributors", store.contributors(week).size()));
                meta.setEnchantmentGlintOverride(true);
            });
            return it;
        }
    }

    private static String bar(int pct) {
        int full = Math.clamp(pct / 5, 0, 20);
        return "<green>" + "|".repeat(full) + "<dark_gray>" + "|".repeat(20 - full);
    }

    public String contributorsText(int week) {
        List<String> names = new ArrayList<>();
        store.contributors(week).entrySet().stream().sorted(Map.Entry.<UUID, Long>comparingByValue().reversed()).limit(5)
                .forEach(e -> names.add(Players.name(e.getKey()) + " " + e.getValue()));
        return String.join(", ", names);
    }
}
