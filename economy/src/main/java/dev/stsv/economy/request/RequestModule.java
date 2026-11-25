package dev.stsv.economy.request;

import org.bukkit.Bukkit;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.bus.BusSpec;
import dev.stsv.economy.calendar.DailyListener;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.mission.MissionSpec;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주민의 부탁 /의뢰 (README "주민의 부탁"; requests.yml, see {@link RequestSpec}): a board of requests a day for each tier of
 * progress (beginners: raw things and 1세대 goods at 1.3x the shop's price; veterans: 4강 · 4세대 goods at the shop's price plus
 * 도장), each asked by a 주민 of the 인물 도감 with a one-line story; 3 per person a day. Fulfilling raises 친밀도 with that 주민
 * (gifts at thresholds, shown in the 인물 도감). Weekly: a 명인 의뢰 for veterans (hard, many stamps, the name kept in that 주민's
 * codex entry as a regular), a chained request in three steps, and the 공동 의뢰 (a server-wide goal; when reached everyone who
 * handed in at least the floor, RequestSpec.minShare, is paid gold and stamps).
 */
public final class RequestModule extends EcoModule implements DailyListener {

    private RequestSpec spec;
    private RequestStore store;
    /** today's board of each tier, drawn when first asked for */
    private final Map<Integer, List<RequestSpec.Request>> boards = new ConcurrentHashMap<>();
    private int boardsDay = -1;

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
        eco.calendar().onDaily(this);
        eco.onReset(store::reset);
    }

    @Override
    public void reload() {
        load();
        boards.clear();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = RequestSpec.parse(eco.data("requests.yml"), problems);
        for (RequestSpec.Entry e : spec.pool()) if (Items.create(e.item(), 1).isEmpty()) problems.add("pool: 없는 아이템 " + e.item());
        for (RequestSpec.Chain c : spec.chains()) for (RequestSpec.Step s : c.steps()) {
            if (Items.create(s.item(), 1).isEmpty()) problems.add("chains " + c.name() + ": 없는 아이템 " + s.item());
        }
        problems.forEach(p -> eco.getLogger().warning("[requests.yml] " + p));
    }

    private long seed() {
        long seed = eco.kv().getLong("request.seed", 0);
        if (seed == 0) {
            seed = System.nanoTime();
            eco.kv().set("request.seed", seed);
        }
        return seed;
    }

    private double price(String id) {
        ShopCatalog cat = eco.shop() == null ? null : eco.shop().catalog();
        return cat == null || !cat.sell().containsKey(id) ? 1 : cat.sell().get(id).base();
    }

    /** Who may ask: the 인물 도감's people (bus kinds that really come, never the secret ones). */
    private List<String> people() {
        return eco.people() == null ? List.of() : eco.people().people();
    }

    /** Today's board of a tier. */
    public List<RequestSpec.Request> board(int tier) {
        int day = eco.calendar().day();
        if (boardsDay != day) {
            boards.clear();
            boardsDay = day;
        }
        return boards.computeIfAbsent(tier, t -> spec.draw(t, day, seed(), this::price, people()));
    }

    @Override
    public void newDay(int day, long dayStart) {
        store.newDay(day);
        boards.clear();
        Bukkit.broadcast(Lang.msg("request.new-day", "count", spec.dailyCount()));
    }

    /** The board of the lowest tier (the old shared board: the stocks read today's items). */
    public List<RequestSpec.Request> today() {
        return board(1);
    }

    public int tierOf(UUID player) {
        return spec.tierFor(eco.jobs().topLevel(player));
    }

    public RequestSpec.Community community() {
        return spec.communityFor(eco.calendar().week());
    }

    public RequestSpec.Request master() {
        return spec.master(eco.calendar().week(), eco.calendar().day(), seed(), this::price, people());
    }

    public RequestStore store() {
        return store;
    }

    /** "<color>name</color>" of a 주민 (a bus kind), or a plain word if there is none. */
    private String personName(String person) {
        BusSpec.Type t = person == null || eco.bus() == null ? null : eco.bus().spec().types().get(person);
        if (t == null) return Lang.raw("request.someone");
        return "<" + t.color() + ">" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(t.name()) + "</" + t.color() + ">";
    }

    // ------------------------------------------------------------------------------------------
    // Delivering
    // ------------------------------------------------------------------------------------------

    /** Takes amount of item from the inventory; false (nothing taken) if there is not enough. */
    private boolean take(Player p, String item, int amount) {
        int have = Items.count(p.getInventory(), it -> Items.plain(it, item));
        if (have < amount) return false;
        Items.remove(p.getInventory(), it -> Items.plain(it, item), amount);
        return true;
    }

    /** Returns null on success, else the refusal key. index: the place on the player's board. */
    public String deliver(Player p, int index) {
        UUID u = p.getUniqueId();
        List<RequestSpec.Request> board = board(tierOf(u));
        if (index < 0 || index >= board.size()) return "request.no-such";
        RequestSpec.Request r = board.get(index);
        if (store.done(u).contains(r.index())) return "request.already";
        if (store.done(u).size() >= spec.perPlayer()) return "request.limit";
        if (!take(p, r.item(), r.amount())) return "request.missing";
        store.complete(u, r.index());
        // 장신구 의뢰 웃돈
        long reward = eco.accessories() == null ? r.reward() : Math.round(r.reward() * (1 + eco.accessories().value(u, "request_bonus")));
        core.economy().deposit(u, reward, "request");
        core.profiles().add(u, "requests_done", 1);
        eco.stocks().recordSale(u, r.item(), r.reward());
        if (eco.accessories() != null) eco.accessories().activity(u, "request", 1);
        if (r.job() != null && r.xp() > 0) eco.jobs().addXp(u, r.job(), r.xp());
        p.sendMessage(Lang.msg("request.done", "person", personName(r.person()), "item", eco.shop().itemName(r.item()), "count", r.amount(),
                "reward", EconomyModule.format(reward), "left", spec.perPlayer() - store.done(u).size()));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
        if (eco.stamps() != null) eco.stamps().giveCapped(u, "request", r.stamps(), spec.stampsDaily(), "stamp.why-request");
        bond(p, r.person(), spec.bond().request());
        return null;
    }

    /** This week's 명인 의뢰. */
    public String deliverMaster(Player p) {
        UUID u = p.getUniqueId();
        RequestSpec.Request r = master();
        int week = eco.calendar().week();
        if (r == null) return "request.master-none";
        if (tierOf(u) < spec.master().tier()) return "request.master-locked";
        if (store.masterDone(week, u)) return "request.master-done";
        if (!take(p, r.item(), r.amount())) return "request.missing";
        store.master(week, u, r.person(), now());
        core.economy().deposit(u, r.reward(), "request_master");
        core.profiles().add(u, "requests_done", 1);
        eco.stocks().recordSale(u, r.item(), r.reward());
        if (r.job() != null && r.xp() > 0) eco.jobs().addXp(u, r.job(), r.xp());
        if (eco.stamps() != null) eco.stamps().give(u, r.stamps(), "stamp.why-master");
        Bukkit.broadcast(Lang.msg("request.master-broadcast", "player", dev.stsv.core.nickname.Names.mini(p), "person", personName(r.person()),
                "item", eco.shop().itemName(r.item()), "count", r.amount()));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
        bond(p, r.person(), spec.bond().master());
        return null;
    }

    /** The next step of this week's chained request. */
    public String deliverChain(Player p) {
        UUID u = p.getUniqueId();
        int week = eco.calendar().week();
        RequestSpec.Chain c = spec.chainFor(week);
        if (c == null) return "request.chain-none";
        int step = store.chainStep(week, u);
        if (step >= c.steps().size()) return "request.chain-done";
        RequestSpec.Step s = c.steps().get(step);
        if (!take(p, s.item(), s.amount())) return "request.missing";
        store.chainStep(week, u, step + 1);
        long reward = spec.stepReward(s, this::price);
        core.economy().deposit(u, reward, "request_chain");
        core.profiles().add(u, "requests_done", 1);
        eco.stocks().recordSale(u, s.item(), reward);
        boolean last = step + 1 >= c.steps().size();
        p.sendMessage(Lang.msg(last ? "request.chain-finished" : "request.chain-step", "name", c.name(), "step", step + 1, "steps", c.steps().size(),
                "item", eco.shop().itemName(s.item()), "count", s.amount(), "reward", EconomyModule.format(reward)));
        p.playSound(p.getLocation(), last ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        if (eco.stamps() != null) {
            eco.stamps().give(u, spec.chainStepStamps(), "stamp.why-chain");
            if (last) eco.stamps().give(u, spec.chainDoneStamps(), "stamp.why-chain-done");
        }
        bond(p, c.person(), spec.bond().chainStep());
        return null;
    }

    /** Raises 친밀도 with a 주민; gifts at the thresholds crossed. */
    private void bond(Player p, String person, int n) {
        if (person == null || n <= 0) return;
        UUID u = p.getUniqueId();
        int before = store.bond(u, person);
        int after = store.addBond(u, person, n);
        for (int at : spec.giftsCrossed(before, after)) {
            RequestSpec.Gift g = spec.bond().gifts().get(at);
            List<String> got = new ArrayList<>();
            for (String i : g.items()) {
                MissionSpec.ItemReward ir = MissionSpec.item(i);
                if (ir == null) continue;
                Items.create(ir.id(), ir.amount()).ifPresent(it -> eco.mail().deliver(u, it, "request_gift"));
                got.add("<white>" + Items.mmName(ir.id()) + " ×" + ir.amount());
            }
            p.sendMessage(Lang.msg("request.gift", "person", personName(person), "bond", at, "gifts", String.join("<gray>, </gray>", got)));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_CELEBRATE, 0.8f, 1.2f);
            if (g.stamps() > 0 && eco.stamps() != null) eco.stamps().give(u, g.stamps(), "stamp.why-gift");
        }
    }

    /** Lines for a person's 인물 도감 entry: my 친밀도 (and the next gift), the 명인 의뢰's regulars. */
    public List<Component> peopleLines(UUID player, String person) {
        List<Component> out = new ArrayList<>();
        int points = store.bond(player, person);
        if (points > 0) {
            int next = spec.nextGift(points);
            out.add(Lang.item(next > 0 ? "request.bond-line" : "request.bond-line-max", "bond", points, "next", next));
        }
        List<UUID> regulars = store.regulars(person, spec.master().regulars());
        if (!regulars.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (UUID r : regulars) names.add(Players.name(r));
            out.add(Lang.item("request.regulars-line", "names", String.join("<gray>, </gray>", names)));
        }
        return out;
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

    /** The least a contributor hands in to be paid for the goal (RequestSpec.minShare at the shop's base price). */
    private long floor(RequestSpec.Community c) {
        ShopCatalog cat = eco.shop() == null ? null : eco.shop().catalog();
        double base = cat == null || !cat.sell().containsKey(c.item()) ? 0 : cat.sell().get(c.item()).base();
        return spec.minShare(c, base);
    }

    /** Contributors paid when the goal completes: at least the floor handed in, with an account. */
    private boolean earns(Map.Entry<UUID, Long> e, long min) {
        return e.getValue() >= min && core.economy().hasAccount(e.getKey());
    }

    private void complete(RequestSpec.Community c, int week) {
        store.markCompleted(week, now());
        long min = floor(c);
        int paid = 0;
        for (Map.Entry<UUID, Long> e : store.contributors(week).entrySet()) {
            if (!earns(e, min)) continue;
            core.economy().deposit(e.getKey(), c.reward(), "community");
            if (eco.stamps() != null) eco.stamps().community(e.getKey());
            paid++;
        }
        Bukkit.broadcast(Lang.msg("request.community-complete", "name", c.name(), "count", paid, "min", String.format("%,d", min),
                "reward", EconomyModule.format(c.reward())));
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
    }

    // ------------------------------------------------------------------------------------------
    // /의뢰: the chained request, the 명인 의뢰, then the day's board
    // ------------------------------------------------------------------------------------------

    /** Cards before the day's requests in the list: the chained request and the 명인 의뢰. */
    static final int CHAIN = 0, MASTER = 1, FIRST_DAILY = 2;

    public void open(Player p) {
        core.menus().open(p, "economy:requests", new View());
    }

    private final class View implements MenuHandler {
        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            return Map.of("done", String.valueOf(store.done(u).size()), "max", String.valueOf(spec.perPlayer()), "day", String.valueOf(eco.calendar().day()),
                    "tier", String.valueOf(tierOf(u)), "count", String.valueOf(spec.dailyCount()));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            Player p = v.player();
            UUID u = p.getUniqueId();
            List<ItemStack> out = new ArrayList<>();
            out.add(chainCard(p));
            out.add(masterCard(p));
            for (RequestSpec.Request r : board(tierOf(u))) {
                boolean done = store.done(u).contains(r.index());
                ItemStack it = Items.create(r.item(), Math.min(r.amount(), 64)).orElse(new ItemStack(Material.PAPER));
                int have = Items.count(p.getInventory(), x -> Items.plain(x, r.item()));
                String item = eco.shop().itemName(r.item());
                it.editMeta(meta -> {
                    meta.itemName(Lang.item("request.name", "person", personName(r.person()), "item", item, "count", r.amount()));
                    List<Component> lore = new ArrayList<>();
                    if (r.story() != null) lore.add(Lang.item("request.story", "story", RequestSpec.tell(r.story(), item, r.amount())));
                    lore.addAll(Lang.items("request.lore", "reward", EconomyModule.format(r.reward()), "have", have, "count", r.amount()));
                    if (r.stamps() > 0) lore.add(Lang.item("request.lore-stamps", "n", r.stamps()));
                    if (r.job() != null && r.xp() > 0) {
                        var j = eco.jobs().spec().jobs().get(r.job());
                        lore.addAll(Lang.items("request.lore-xp", "color", j == null ? "white" : j.color(), "job", j == null ? r.job() : j.name(), "xp", (long) r.xp()));
                    }
                    if (r.person() != null && store.bond(u, r.person()) > 0) lore.add(Lang.item("request.lore-bond", "bond", store.bond(u, r.person())));
                    lore.addAll(Lang.items(done ? "request.lore-done" : have >= r.amount() ? "request.lore-ready" : "request.lore-missing"));
                    meta.lore(lore);
                    meta.setEnchantmentGlintOverride(done);
                });
                out.add(it);
            }
            return out;
        }

        private ItemStack chainCard(Player p) {
            UUID u = p.getUniqueId();
            int week = eco.calendar().week();
            RequestSpec.Chain c = spec.chainFor(week);
            if (c == null) return named(Material.GRAY_DYE, "request.chain-none-name");
            int step = store.chainStep(week, u);
            boolean done = step >= c.steps().size();
            RequestSpec.Step s = done ? c.steps().getLast() : c.steps().get(step);
            ItemStack it = Items.create(s.item(), Math.min(s.amount(), 64)).orElse(new ItemStack(Material.PAPER));
            int have = Items.count(p.getInventory(), x -> Items.plain(x, s.item()));
            String item = eco.shop().itemName(s.item());
            it.editMeta(meta -> {
                meta.itemName(Lang.item("request.chain-name", "name", c.name(), "step", Math.min(step + 1, c.steps().size()), "steps", c.steps().size()));
                List<Component> lore = new ArrayList<>();
                lore.add(Lang.item("request.chain-person", "person", personName(c.person())));
                for (int i = 0; i < c.steps().size(); i++) {
                    RequestSpec.Step x = c.steps().get(i);
                    String key = i < step ? "request.chain-line-done" : i == step ? "request.chain-line-now" : "request.chain-line-locked";
                    lore.add(Lang.item(key, "n", i + 1, "item", eco.shop().itemName(x.item()), "count", x.amount()));
                }
                lore.add(Component.empty());
                if (done) lore.addAll(Lang.items("request.chain-lore-done"));
                else {
                    lore.add(Lang.item("request.story", "story", s.text()));
                    lore.addAll(Lang.items("request.chain-lore", "reward", EconomyModule.format(spec.stepReward(s, RequestModule.this::price)),
                            "stamps", spec.chainStepStamps(), "done_stamps", spec.chainDoneStamps(), "have", have, "count", s.amount(), "item", item));
                    lore.addAll(Lang.items(have >= s.amount() ? "request.lore-ready" : "request.lore-missing"));
                }
                meta.lore(lore);
                meta.setEnchantmentGlintOverride(done);
            });
            return it;
        }

        private ItemStack masterCard(Player p) {
            UUID u = p.getUniqueId();
            RequestSpec.Request r = master();
            if (r == null) return named(Material.GRAY_DYE, "request.master-none-name");
            boolean open = tierOf(u) >= spec.master().tier();
            boolean done = store.masterDone(eco.calendar().week(), u);
            ItemStack it = open ? Items.create(r.item(), Math.min(r.amount(), 64)).orElse(new ItemStack(Material.PAPER)) : new ItemStack(Material.IRON_BARS);
            int have = Items.count(p.getInventory(), x -> Items.plain(x, r.item()));
            String item = eco.shop().itemName(r.item());
            int from = spec.tier(spec.master().tier()).from();
            it.editMeta(meta -> {
                meta.itemName(Lang.item("request.master-name", "person", personName(r.person())));
                List<Component> lore = new ArrayList<>();
                if (!open) lore.addAll(Lang.items("request.master-lore-locked", "level", from));
                else {
                    if (r.story() != null) lore.add(Lang.item("request.story", "story", RequestSpec.tell(r.story(), item, r.amount())));
                    lore.addAll(Lang.items("request.master-lore", "item", item, "count", r.amount(), "reward", EconomyModule.format(r.reward()),
                            "stamps", r.stamps(), "have", have));
                    lore.addAll(Lang.items(done ? "request.lore-done" : have >= r.amount() ? "request.lore-ready" : "request.lore-missing"));
                }
                meta.lore(lore);
                meta.setEnchantmentGlintOverride(done || open);
            });
            return it;
        }

        private ItemStack named(Material m, String key) {
            ItemStack it = new ItemStack(m);
            it.editMeta(meta -> meta.itemName(Lang.item(key)));
            return it;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            String refused = switch (index) {
                case CHAIN -> deliverChain(v.player());
                case MASTER -> deliverMaster(v.player());
                default -> deliver(v.player(), index - FIRST_DAILY);
            };
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
            long min = floor(c);
            long paid = store.contributors(week).entrySet().stream().filter(e -> earns(e, min)).count();
            int stamps = eco.stamps() == null ? 0 : eco.stamps().spec().earn(dev.stsv.economy.stamp.StampSpec.COMMUNITY);
            it.editMeta(meta -> {
                meta.itemName(Lang.item("request.community-name", "name", c.name()));
                meta.lore(Lang.items(store.completed(week) ? "request.community-lore-done" : "request.community-lore",
                        "item", eco.shop().itemName(c.item()), "progress", String.format("%,d", store.progress(week)),
                        "target", String.format("%,d", c.target()), "pct", pct, "bar", bar(pct), "mine", String.format("%,d", mine),
                        "reward", EconomyModule.format(c.reward()), "contributors", store.contributors(week).size(),
                        "min", String.format("%,d", min), "paid", paid, "stamps", stamps));
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
