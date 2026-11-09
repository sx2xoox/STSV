package dev.stsv.core.achievement;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.achievement.AchievementSpec.Category;
import dev.stsv.core.achievement.AchievementSpec.Tier;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * 업적: goals read from contents/&lt;pack&gt;/achievements/*.yml (see {@link AchievementSpec}), measured by
 * all-time stats (ProfileModule) or by values content plugins provide ({@link #provide}). Checked every
 * 2 seconds for online players; a finished achievement is announced (to everyone when hard or very
 * hard) and its reward is taken in /업적 (so a full inventory never loses anything).
 */
public final class AchievementModule implements StsvModule, Listener {

    static final String MENU = "stsv:achievements", LIST_MENU = "stsv:achievement_list";

    private STSVPlugin plugin;
    private AchievementStore store;
    private BukkitTask ticker;
    private final Map<String, AchievementSpec> specs = new LinkedHashMap<>();
    private final Map<String, Category> categories = new LinkedHashMap<>();
    /** provider id -> (player, argument or null) -> value */
    private final Map<String, BiFunction<Player, String, Number>> providers = new HashMap<>();

    @Override
    public String id() {
        return "achievements";
    }

    @Override
    public void enable(STSVPlugin plugin) throws Exception {
        this.plugin = plugin;
        store = new AchievementStore(plugin.db());
        store.init();
        load();
        provide("stsv:achievements", (p, tier) -> done(p.getUniqueId()).filter(s -> tier == null || s.tier() == Tier.parse(tier)).count());
        provide("stsv:points", (p, a) -> points(p.getUniqueId()));
        provide("stsv:titles", (p, a) -> plugin.profiles().ownedTitles(p.getUniqueId()).size());
        provide("stsv:cosmetics", (p, a) -> plugin.cosmetics().owned(p).size());
        provide("stsv:balance", (p, a) -> plugin.economy().balance(p.getUniqueId()));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) store.load(p.getUniqueId());
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 100L, 40L);
        plugin.admin().add("STSV", "업적", "정보|지급|회수 <플레이어> [업적]", "업적 상태 / 달성 처리(보상은 본인이 받음) / 기록 삭제",
                this::admin, this::adminComplete);
        plugin.admin().reset("STSV", "업적", "모든 업적 달성 기록", by -> store.reset());
    }

    @Override
    public void reload() throws Exception {
        load();
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        HandlerList.unregisterAll(this);
    }

    /**
     * A value achievements can measure ("value: economy:job_level:fisher" = provider economy:job_level,
     * argument "fisher"). Main thread; must be cheap (called every 2 seconds per online player).
     */
    public void provide(String id, BiFunction<Player, String, Number> provider) {
        providers.put(id, provider);
    }

    /** Drops the providers whose id starts with this (a content plugin going away). */
    public void unprovide(String prefix) {
        providers.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public Map<String, AchievementSpec> specs() {
        return java.util.Collections.unmodifiableMap(specs);
    }

    private void load() throws Exception {
        specs.clear();
        categories.clear();
        List<String> problems = new ArrayList<>();
        for (ContentPack pack : plugin.content().packs()) {
            Path dir = pack.dir().resolve("achievements");
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(x -> x.toString().endsWith(".yml")).sorted().toList()) {
                    String cat = f.getFileName().toString().replaceFirst("\\.yml$", "");
                    specs.putAll(AchievementSpec.parse(pack.namespace(), cat, YamlConfiguration.loadConfiguration(f.toFile()),
                            categories, problems, pack.namespace() + "/achievements/" + f.getFileName()));
                }
            }
        }
        problems.forEach(m -> plugin.getLogger().warning("업적 " + m));
        if (!specs.isEmpty()) plugin.getLogger().info("업적 " + specs.size() + "개 (" + categories.size() + "개 분류)");
    }

    // ------------------------------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------------------------------

    /**
     * Current value towards the goal (0 when it cannot be measured now). A stat counts only from when the
     * achievement first applied to the player (its base); a value (a level, the net worth...) is what it is now.
     */
    public double progress(Player p, AchievementSpec a) {
        AchievementSpec.Condition c = a.condition();
        if (c.stat()) {
            Double base = store.base(p.getUniqueId(), a.id());
            return base == null ? 0 : Math.max(0, plugin.profiles().stat(p.getUniqueId(), c.key()) - base);
        }
        BiFunction<Player, String, Number> f = providers.get(c.key());
        if (f == null) return 0;
        try {
            Number n = f.apply(p, c.arg());
            return n == null ? 0 : n.doubleValue();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private Stream<AchievementSpec> done(UUID player) {
        Map<String, AchievementStore.Done> d = store.of(player);
        return specs.values().stream().filter(s -> d.containsKey(s.id()));
    }

    public int points(UUID player) {
        return done(player).mapToInt(s -> s.tier().points).sum();
    }

    private void check() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (!store.loaded(u) || !plugin.profiles().statsLoaded(u)) continue;
            Map<String, AchievementStore.Done> d = store.of(u);
            // a few passes: finishing one may finish another ("achievements" counts)
            for (int pass = 0; pass < 3; pass++) {
                boolean any = false;
                for (AchievementSpec a : specs.values()) {
                    if (d.containsKey(a.id())) continue;
                    if (a.condition().kind().equals("value") && !providers.containsKey(a.condition().key())) continue;
                    if (a.condition().stat() && store.base(u, a.id()) == null) {
                        // first time this achievement applies to the player: count from here on, not their past
                        store.startBase(u, a.id(), plugin.profiles().stat(u, a.condition().key()));
                        continue;
                    }
                    if (progress(p, a) + 1e-9 >= a.condition().goal() && store.complete(u, a.id(), now)) {
                        announce(p, a);
                        any = true;
                    }
                }
                if (!any) break;
            }
        }
    }

    private void announce(Player p, AchievementSpec a) {
        Tier t = a.tier();
        p.showTitle(Title.title(Lang.mm("achievement.done-title", "color", t.color),
                Text.mm(t.color + a.name()), Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(600))));
        p.playSound(p.getLocation(), t.ordinal() >= Tier.HARD.ordinal() ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
        Component msg = Lang.msg("achievement.done", "name", a.name(), "color", t.color, "tier", t.label, "points", t.points);
        if (!a.rewards().isEmpty()) {
            msg = msg.append(Text.mm(" ")).append(Lang.mm("achievement.claim-hint").clickEvent(ClickEvent.runCommand("/업적")));
        }
        p.sendMessage(msg);
        if (t.ordinal() >= Tier.HARD.ordinal()) {
            Component all = Lang.msg("achievement.broadcast", "player", p.getName(), "name", a.name(), "color", t.color, "tier", t.label);
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p) o.sendMessage(all);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rewards
    // ------------------------------------------------------------------------------------------

    /** Gives the reward; null on success, else the message key why not. */
    String claim(Player p, AchievementSpec a) {
        AchievementStore.Done d = store.of(p.getUniqueId()).get(a.id());
        if (d == null) return "achievement.not-done";
        if (d.claimed()) return "achievement.already-claimed";
        AchievementSpec.Rewards r = a.rewards();
        List<ItemStack> items = new ArrayList<>();
        for (AchievementSpec.ItemReward i : r.items()) {
            ItemStack it = stack(i.id(), i.amount());
            if (it != null) items.add(it);
        }
        int free = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) if (s == null || s.isEmpty()) free++;
        int need = 0;
        for (ItemStack it : items) need += (it.getAmount() + it.getMaxStackSize() - 1) / it.getMaxStackSize();
        if (need > free) {
            p.sendMessage(Lang.msg("achievement.no-room", "count", need));
            return "";
        }
        store.claimed(p.getUniqueId(), a.id(), System.currentTimeMillis());
        if (r.gold() > 0) plugin.economy().deposit(p.getUniqueId(), r.gold(), "achievement");
        for (ItemStack it : items) p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        if (r.title() != null) plugin.profiles().grantTitle(p.getUniqueId(), r.title());
        if (r.cosmetic() != null) plugin.content().item(r.cosmetic()).filter(c -> c.cosmetic() != null).ifPresent(c -> {
            if (plugin.cosmetics().grant(p, c, true)) plugin.cosmetics().celebrate(p, c);
        });
        for (String cmd : r.commands()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        p.sendMessage(Lang.msg("achievement.claimed", "name", a.name(), "color", a.tier().color, "rewards", String.join("<gray>, </gray>", rewardLines(r))));
        return null;
    }

    private ItemStack stack(String id, int amount) {
        Optional<CustomItem> c = plugin.content().item(id);
        if (c.isPresent()) return c.get().create(amount);
        Material m = Material.matchMaterial(id);
        return m == null || !m.isItem() ? null : new ItemStack(m, amount);
    }

    /** Reward parts as MiniMessage, for lore and chat. */
    List<String> rewardLines(AchievementSpec.Rewards r) {
        List<String> out = new ArrayList<>();
        if (r.gold() > 0) out.add(EconomyModule.format(r.gold()));   // format already says "G"
        for (AchievementSpec.ItemReward i : r.items()) out.add("<white>" + itemName(i.id()) + " ×" + i.amount());
        if (r.title() != null) out.add(Lang.raw("achievement.reward-title", "title", plugin.profiles().title(r.title()).map(t -> t.name()).orElse(r.title())));
        if (r.cosmetic() != null) out.add(Lang.raw("achievement.reward-cosmetic", "cosmetic",
                plugin.content().item(r.cosmetic()).map(c -> c.name() == null ? r.cosmetic() : c.name()).orElse(r.cosmetic())));
        if (!r.commands().isEmpty()) out.add(Lang.raw("achievement.reward-special"));
        return out;
    }

    private String itemName(String id) {
        Optional<CustomItem> c = plugin.content().item(id);
        if (c.isPresent()) return c.get().name() == null ? id : c.get().name();
        Material m = Material.matchMaterial(id);
        return m == null ? id : "<lang:" + m.translationKey() + ">";
    }

    // ------------------------------------------------------------------------------------------
    // /업적
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        if (!store.loaded(p.getUniqueId())) {
            p.sendMessage(Lang.msg("achievement.loading"));
            return;
        }
        plugin.menus().open(p, MENU, new CategoryMenu());
    }

    private List<Category> sortedCategories() {
        return categories.values().stream().filter(c -> specs.values().stream().anyMatch(s -> s.category().equals(c.id())))
                .sorted(Comparator.comparingInt(Category::order).thenComparing(Category::id)).toList();
    }

    private List<AchievementSpec> inCategory(String cat) {
        return specs.values().stream().filter(s -> s.category().equals(cat))
                .sorted(Comparator.comparing(AchievementSpec::tier).thenComparingInt(AchievementSpec::order)).toList();
    }

    private long unclaimed(UUID u, List<AchievementSpec> list) {
        Map<String, AchievementStore.Done> d = store.of(u);
        return list.stream().filter(s -> d.containsKey(s.id()) && !d.get(s.id()).claimed() && !s.rewards().isEmpty()).count();
    }

    private Map<String, String> summary(Player p) {
        Map<String, String> m = new HashMap<>();
        UUID u = p.getUniqueId();
        m.put("done", String.valueOf(done(u).count()));
        m.put("total", String.valueOf(specs.size()));
        m.put("points", String.format(Locale.ROOT, "%,d", points(u)));
        m.put("unclaimed", String.valueOf(unclaimed(u, List.copyOf(specs.values()))));
        return m;
    }

    /** Takes every reward waiting in the list; stops at the first one that does not fit. */
    private void claimAll(Player p, List<AchievementSpec> list) {
        Map<String, AchievementStore.Done> d = store.of(p.getUniqueId());
        int n = 0;
        for (AchievementSpec a : list) {
            AchievementStore.Done x = d.get(a.id());
            if (x == null || x.claimed() || a.rewards().isEmpty()) continue;
            String why = claim(p, a);
            if (why != null) break;
            n++;
        }
        if (n == 0) p.sendMessage(Lang.msg("achievement.nothing-to-claim"));
    }

    private ItemStack icon(String id) {
        Optional<CustomItem> c = plugin.content().item(id);
        if (c.isPresent()) return c.get().create(1);
        Material m = Material.matchMaterial(id);
        if (m != null && m.isItem()) return new ItemStack(m);
        ItemStack it = new ItemStack(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(id));
        return it;
    }

    private final class CategoryMenu implements MenuHandler {
        @Override
        public Map<String, String> placeholders(MenuView v) {
            return summary(v.player());
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            UUID u = v.player().getUniqueId();
            Map<String, AchievementStore.Done> d = store.of(u);
            List<ItemStack> out = new ArrayList<>();
            for (Category c : sortedCategories()) {
                List<AchievementSpec> list = inCategory(c.id());
                long have = list.stream().filter(s -> d.containsKey(s.id())).count();
                long waiting = unclaimed(u, list);
                ItemStack it = icon(c.icon());
                it.editMeta(m -> {
                    m.itemName(Text.item("<white>" + c.name()));
                    List<Component> lore = new ArrayList<>();
                    lore.add(Lang.item("achievement.category-progress", "done", have, "total", list.size(),
                            "bar", AchievementSpec.bar(list.isEmpty() ? 0 : (double) have / list.size(), 10)));
                    for (Tier t : Tier.values()) {
                        long tt = list.stream().filter(s -> s.tier() == t).count();
                        if (tt == 0) continue;
                        long td = list.stream().filter(s -> s.tier() == t && d.containsKey(s.id())).count();
                        lore.add(Lang.item("achievement.category-tier", "color", t.color, "tier", t.label, "done", td, "total", tt));
                    }
                    if (waiting > 0) lore.add(Lang.item("achievement.category-waiting", "count", waiting));
                    lore.add(Lang.item("achievement.category-click"));
                    m.lore(lore);
                    m.setEnchantmentGlintOverride(waiting > 0);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            List<Category> cats = sortedCategories();
            if (index >= cats.size()) return;
            Player p = v.player();
            String cat = cats.get(index).id();
            Bukkit.getScheduler().runTask(plugin, () -> plugin.menus().open(p, LIST_MENU, new ListMenu(cat)));
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (action.equals("achievements:claim_all")) {
                claimAll(v.player(), List.copyOf(specs.values()));
                v.refresh();
            } else if (action.equals("achievements:ranking")) {
                Player p = v.player();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    p.closeInventory();
                    showRanking(p);
                });
            }
        }
    }

    private final class ListMenu implements MenuHandler {
        private final String category;

        ListMenu(String category) {
            this.category = category;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            Map<String, String> m = summary(v.player());
            List<AchievementSpec> list = inCategory(category);
            Map<String, AchievementStore.Done> d = store.of(v.player().getUniqueId());
            m.put("category", Optional.ofNullable(categories.get(category)).map(Category::name).orElse(category));
            m.put("category_done", String.valueOf(list.stream().filter(s -> d.containsKey(s.id())).count()));
            m.put("category_total", String.valueOf(list.size()));
            return m;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            Player p = v.player();
            Map<String, AchievementStore.Done> d = store.of(p.getUniqueId());
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd");
            List<ItemStack> out = new ArrayList<>();
            for (AchievementSpec a : inCategory(category)) {
                AchievementStore.Done done = d.get(a.id());
                boolean secret = a.hidden() && done == null;
                Tier t = a.tier();
                ItemStack it = secret ? new ItemStack(Material.GRAY_DYE) : icon(a.icon());
                it.editMeta(m -> {
                    m.itemName(Text.item(secret ? Lang.raw("achievement.secret-name") : t.color + a.name()));
                    List<Component> lore = new ArrayList<>();
                    lore.add(Lang.item("achievement.lore-tier", "color", t.color, "tier", t.label, "points", t.points));
                    lore.add(Text.item("<gray>" + (secret ? Lang.raw("achievement.secret-description") : a.description())));
                    lore.add(Text.item(""));
                    if (done == null) {
                        double have = progress(p, a), goal = a.condition().goal();
                        if (!secret) lore.add(Lang.item("achievement.lore-progress", "bar", AchievementSpec.bar(have / goal, 10),
                                "have", amount(a, Math.min(have, goal)), "goal", amount(a, goal)));
                    } else {
                        lore.add(Lang.item("achievement.lore-done", "date", day.format(new Date(done.doneAt()))));
                    }
                    if (!a.rewards().isEmpty()) {
                        lore.add(Lang.item("achievement.lore-rewards"));
                        if (secret) lore.add(Text.item("<dark_gray> · ???"));
                        else for (String r : rewardLines(a.rewards())) lore.add(Text.item("<dark_gray> · </dark_gray>" + r));
                    }
                    lore.add(Text.item(""));
                    lore.add(Lang.item("achievement.lore-count", "count", store.count(a.id())));
                    if (done != null && !a.rewards().isEmpty()) {
                        lore.add(Lang.item(done.claimed() ? "achievement.lore-claimed" : "achievement.lore-claim"));
                    }
                    m.lore(lore);
                    m.setEnchantmentGlintOverride(done != null && !done.claimed() && !a.rewards().isEmpty());
                    if (it.getType() != Material.PAPER || secret) m.addItemFlags(org.bukkit.inventory.ItemFlag.values());
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            List<AchievementSpec> list = inCategory(category);
            if (index >= list.size()) return;
            AchievementSpec a = list.get(index);
            AchievementStore.Done d = store.of(v.player().getUniqueId()).get(a.id());
            if (d == null || d.claimed() || a.rewards().isEmpty()) return;
            claim(v.player(), a);
            v.refresh();
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (action.equals("achievements:claim_all")) {
                claimAll(v.player(), inCategory(category));
                v.refresh();
            }
        }
    }

    /** "1,234 / 5,000 마리" parts: stats format themselves (playtime), values get the unit. */
    private String amount(AchievementSpec a, double v) {
        if (a.condition().stat() && a.unit().isEmpty()) return dev.stsv.core.profile.ProfileModule.format(a.condition().key(), v);
        String n = v == Math.rint(v) ? String.format(Locale.ROOT, "%,d", (long) v) : String.format(Locale.ROOT, "%,.1f", v);
        return a.unit().isEmpty() ? n : n + " " + a.unit();
    }

    // ------------------------------------------------------------------------------------------
    // Points: profile summary, ranking
    // ------------------------------------------------------------------------------------------

    /** A player's achievements at a glance (/프로필). */
    public record Summary(int points, int done, int total, Map<Tier, int[]> perTier, List<AchievementSpec> recent) {}

    private Summary summary(Map<String, AchievementStore.Done> d) {
        Map<Tier, int[]> per = new java.util.EnumMap<>(Tier.class);
        for (AchievementSpec s : specs.values()) {
            int[] x = per.computeIfAbsent(s.tier(), k -> new int[2]);
            x[1]++;
            if (d.containsKey(s.id())) x[0]++;
        }
        List<AchievementSpec> mine = specs.values().stream().filter(s -> d.containsKey(s.id())).toList();
        List<AchievementSpec> recent = mine.stream()
                .sorted(Comparator.comparingLong((AchievementSpec s) -> d.get(s.id()).doneAt()).reversed()).limit(5).toList();
        return new Summary(mine.stream().mapToInt(s -> s.tier().points).sum(), mine.size(), specs.size(), per, recent);
    }

    /** From memory for online players, else read from the database. */
    public java.util.concurrent.CompletableFuture<Summary> summaryOf(UUID player) {
        if (store.loaded(player)) return java.util.concurrent.CompletableFuture.completedFuture(summary(store.of(player)));
        return store.read(player).thenApply(this::summary);
    }

    public record Rank(UUID player, String name, int points, int done) {}

    /** Everyone by achievement points (ties: more achievements, then the name). */
    public java.util.concurrent.CompletableFuture<List<Rank>> ranking() {
        return store.all().thenApply(rows -> {
            Map<UUID, int[]> sum = new HashMap<>();
            Map<UUID, String> names = new HashMap<>();
            for (AchievementStore.Row r : rows) {
                AchievementSpec s = specs.get(r.id());
                if (s == null) continue;
                int[] x = sum.computeIfAbsent(r.player(), k -> new int[2]);
                x[0] += s.tier().points;
                x[1]++;
                if (r.name() != null) names.put(r.player(), r.name());
            }
            return sum.entrySet().stream().map(e -> new Rank(e.getKey(), names.getOrDefault(e.getKey(), "?"), e.getValue()[0], e.getValue()[1]))
                    .sorted(Comparator.comparingInt(Rank::points).reversed().thenComparing(Comparator.comparingInt(Rank::done).reversed())
                            .thenComparing(Rank::name)).toList();
        });
    }

    /** /업적 순위: the top 10 and where the player stands. */
    public void showRanking(Player p) {
        ranking().thenAccept(list -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (list.isEmpty()) {
                p.sendMessage(Lang.msg("achievement.rank-empty"));
                return;
            }
            p.sendMessage(Lang.msg("achievement.rank-header"));
            for (int i = 0; i < Math.min(10, list.size()); i++) {
                Rank r = list.get(i);
                p.sendMessage(Lang.mm("achievement.rank-line", "rank", i + 1, "player", r.name(),
                        "points", String.format(Locale.ROOT, "%,d", r.points()), "done", r.done()));
            }
            for (int i = 10; i < list.size(); i++) {
                if (list.get(i).player().equals(p.getUniqueId())) {
                    p.sendMessage(Lang.mm("achievement.rank-mine", "rank", i + 1, "points", String.format(Locale.ROOT, "%,d", list.get(i).points())));
                }
            }
        }));
    }

    // ------------------------------------------------------------------------------------------
    // Join / quit, admin
    // ------------------------------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        store.load(u).thenRun(() -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(u);
            if (p == null) return;
            long waiting = unclaimed(u, List.copyOf(specs.values()));
            if (waiting > 0) p.sendMessage(Lang.msg("achievement.waiting", "count", waiting).clickEvent(ClickEvent.runCommand("/업적")));
        }, 60L));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        store.unload(e.getPlayer().getUniqueId());
    }

    private void admin(org.bukkit.command.CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Text.msg("<red>/관리자 업적 정보|지급|회수 <플레이어> [업적]"));
            return;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null || !store.loaded(p.getUniqueId())) {
            s.sendMessage(Text.msg("<red>접속 중인 플레이어만 됩니다."));
            return;
        }
        switch (args[0]) {
            case "정보" -> {
                s.sendMessage(Text.msg("<gold>" + p.getName() + "</gold> <gray>업적 " + done(p.getUniqueId()).count() + "/" + specs.size()
                        + " · 점수 " + points(p.getUniqueId()) + " · 보상 대기 " + unclaimed(p.getUniqueId(), List.copyOf(specs.values()))));
                if (args.length >= 3) Optional.ofNullable(specs.get(fullId(args[2]))).ifPresent(a ->
                        s.sendMessage(Text.msg("<gray>" + a.id() + ": " + amount(a, progress(p, a)) + " / " + amount(a, a.condition().goal())
                                + (store.of(p.getUniqueId()).containsKey(a.id()) ? " <green>달성" : ""))));
            }
            case "지급" -> {
                AchievementSpec a = args.length >= 3 ? specs.get(fullId(args[2])) : null;
                if (a == null) {
                    s.sendMessage(Text.msg("<red>그런 업적이 없습니다."));
                } else if (store.complete(p.getUniqueId(), a.id(), System.currentTimeMillis())) {
                    announce(p, a);
                    s.sendMessage(Text.msg("<green>달성 처리: " + a.id()));
                } else {
                    s.sendMessage(Text.msg("<yellow>이미 달성한 업적입니다."));
                }
            }
            case "회수" -> {
                AchievementSpec a = args.length >= 3 ? specs.get(fullId(args[2])) : null;
                if (a == null) {
                    s.sendMessage(Text.msg("<red>그런 업적이 없습니다."));
                } else {
                    store.revoke(p.getUniqueId(), a.id());
                    // a stat achievement counts again from now: else the next check finds it reached and its reward is paid twice
                    if (a.condition().stat()) store.restartBase(p.getUniqueId(), a.id(), plugin.profiles().stat(p.getUniqueId(), a.condition().key()));
                    s.sendMessage(Text.msg("<green>기록 삭제: " + a.id() + " <gray>(받은 보상은 그대로"
                            + (a.condition().stat() ? ", 지금부터 다시 셈)" : ". 조건을 지금도 채우면 곧 다시 달성됨)")));
                }
            }
            default -> s.sendMessage(Text.msg("<red>/관리자 업적 정보|지급|회수 <플레이어> [업적]"));
        }
    }

    private String fullId(String id) {
        return id.contains(":") ? id : specs.keySet().stream().filter(k -> k.endsWith(":" + id)).findFirst().orElse(id);
    }

    private List<String> adminComplete(org.bukkit.command.CommandSender s, String[] args) {
        if (args.length == 1) return List.of("정보", "지급", "회수");
        if (args.length == 2) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 3) return specs.keySet().stream().filter(k -> k.contains(args[2])).limit(50).toList();
        return List.of();
    }
}
