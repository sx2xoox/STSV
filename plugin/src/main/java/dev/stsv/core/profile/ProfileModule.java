package dev.stsv.core.profile;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Player profiles and records on top of {@link ProfileStore}: counts the common stats, keeps titles
 * (defined in contents/&lt;pack&gt;/titles/*.yml), shows them in chat and the tab list, and offers
 * /프로필 and /명예의전당. Contents add their own stats with {@link #add}.
 */
public final class ProfileModule implements StsvModule, Listener {

    public record Title(String id, String name, String description) {}

    private STSVPlugin plugin;
    private ProfileStore store;
    private final Map<String, Title> titles = new LinkedHashMap<>();
    private final Map<UUID, ProfileStore.Titles> online = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playtimeMark = new ConcurrentHashMap<>();
    private BukkitTask ticker;

    @Override
    public String id() {
        return "profiles";
    }

    @Override
    public void enable(STSVPlugin plugin) throws Exception {
        this.plugin = plugin;
        store = new ProfileStore(plugin.db());
        store.init();
        loadTitles();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) joined(p);
        // playtime and batched writes once a minute
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) countPlaytime(p);
            store.flush();
        }, 1200L, 1200L);
        store.current().ifPresent(p -> plugin.getLogger().info("진행 중인 콘텐츠 기간: " + p.id() + " (" + p.name() + ")"));
    }

    @Override
    public void reload() throws Exception {
        loadTitles();
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) countPlaytime(p);
        store.flush().join();
        HandlerList.unregisterAll(this);
    }

    public ProfileStore store() {
        return store;
    }

    /** Counts a stat for a player (all-time and the running content period). Main thread. */
    public void add(UUID player, String stat, double delta) {
        count(player, stat, delta);
    }

    /** All-time stats of online players, kept in memory (achievements read them every few seconds). */
    private final Map<UUID, Map<String, Double>> allTime = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> allTimeLoaded = ConcurrentHashMap.newKeySet();

    private void count(UUID player, String stat, double delta) {
        store.add(player, stat, delta);
        Map<String, Double> m = allTime.get(player);
        if (m != null && delta != 0) m.merge(stat, delta, Double::sum);
    }

    /**
     * An online player's all-time stat (0 if never counted). Only meaningful once {@link #statsLoaded};
     * playtime is counted once a minute.
     */
    public double stat(UUID player, String stat) {
        Map<String, Double> m = allTime.get(player);
        return m == null ? 0 : m.getOrDefault(stat, 0.0);
    }

    public boolean statsLoaded(UUID player) {
        return allTimeLoaded.contains(player);
    }

    /**
     * Starts the in-memory copy: the database read flushes what was counted before this call, and what
     * is counted from now on goes into the map, so nothing is counted twice.
     */
    private void loadAllTime(UUID player) {
        java.util.concurrent.CompletableFuture<Map<String, Double>> stored = store.stats(player, ProfileStore.ALL_TIME);
        Map<String, Double> m = new ConcurrentHashMap<>();
        allTime.put(player, m);
        allTimeLoaded.remove(player);
        stored.thenAccept(db -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (allTime.get(player) != m) return;   // left (or rejoined) meanwhile
            db.forEach((k, v) -> m.merge(k, v, Double::sum));
            allTimeLoaded.add(player);
        }));
    }

    private void loadTitles() throws Exception {
        titles.clear();
        for (ContentPack pack : plugin.content().packs()) {
            Path dir = pack.dir().resolve("titles");
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                    YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                    for (String key : y.getKeys(false)) {
                        String id = pack.namespace() + ":" + key;
                        titles.put(id, new Title(id, y.getString(key + ".name", key), y.getString(key + ".description", "")));
                    }
                }
            }
        }
    }

    public Collection<Title> titles() {
        return titles.values();
    }

    public Optional<Title> title(String id) {
        return Optional.ofNullable(titles.get(id.contains(":") ? id : "stsv:" + id));
    }

    // ------------------------------------------------------------------------------------------
    // Join / quit, playtime, chat and tab
    // ------------------------------------------------------------------------------------------

    private void joined(Player p) {
        store.seen(p.getUniqueId(), p.getName(), System.currentTimeMillis());
        loadAllTime(p.getUniqueId());
        playtimeMark.put(p.getUniqueId(), System.currentTimeMillis());
        store.titles(p.getUniqueId()).thenAccept(t -> {
            online.put(p.getUniqueId(), t);
            Bukkit.getScheduler().runTask(plugin, () -> showName(p));
        });
    }

    /** Testing: wipes every record and title, then reloads the online players' (now empty) state. */
    public java.util.concurrent.CompletableFuture<Void> reset() {
        return store.reset().thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) joined(p);
        }));
    }

    private void countPlaytime(Player p) {
        long now = System.currentTimeMillis();
        Long since = playtimeMark.put(p.getUniqueId(), now);
        if (since != null) count(p.getUniqueId(), "playtime", (now - since) / 1000.0);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        joined(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        countPlaytime(e.getPlayer());
        playtimeMark.remove(e.getPlayer().getUniqueId());
        online.remove(e.getPlayer().getUniqueId());
        allTime.remove(e.getPlayer().getUniqueId());
        allTimeLoaded.remove(e.getPlayer().getUniqueId());
    }

    /** The name (MiniMessage) of the title an online player wears, if any. */
    public Optional<String> wornTitleName(UUID player) {
        return worn(player).map(Title::name);
    }

    private Optional<Title> worn(UUID player) {
        ProfileStore.Titles t = online.get(player);
        return t == null || t.equipped() == null ? Optional.empty() : title(t.equipped());
    }

    /** The tab list name again (title, nickname changed). */
    public void refreshName(Player p) {
        showName(p);
    }

    private void showName(Player p) {
        boolean titled = plugin.getConfig().getBoolean("profiles.title-in-tab");
        boolean nick = dev.stsv.core.nickname.Names.decorated(p.getUniqueId());
        Component name = nick && plugin.nicknames() != null ? plugin.nicknames().tabName(p) : Component.text(p.getName());
        Optional<Title> t = titled ? worn(p.getUniqueId()) : Optional.empty();
        p.playerListName(t.isPresent() ? Text.mm(t.get().name() + " ").append(name) : nick ? name : null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Optional<Title> t = plugin.getConfig().getBoolean("profiles.title-in-chat") ? worn(e.getPlayer().getUniqueId()) : Optional.empty();
        Component title = t.map(x -> Text.mm(x.name())).orElse(null);
        String format = plugin.getConfig().getString("profiles.chat-format");
        e.renderer((source, name, message, viewer) -> chatLine(format, title, name, message));
    }

    /** The vanilla "&lt;name&gt; message" without the angle brackets: "{name} » {message}" by default (config profiles.chat-format). */
    static final String DEFAULT_CHAT_FORMAT = "{title}{name} <dark_gray>»</dark_gray> {message}";

    /**
     * One chat line from the format: {title} (with a space after it, or nothing when no title is worn), {name} (the
     * player's display name, i.e. the nickname with its hover), {message}. Anything else in the format is MiniMessage.
     */
    static Component chatLine(String format, Component title, Component name, Component message) {
        String f = format == null || format.isBlank() ? DEFAULT_CHAT_FORMAT : format;
        boolean hasTitleTag = f.contains("{title}");
        Component out = Component.empty();
        if (title != null && !hasTitleTag) out = out.append(title).append(Component.text(" "));
        int i = 0;
        while (i < f.length()) {
            int open = f.indexOf('{', i);
            if (open < 0) {
                out = out.append(Text.mm(f.substring(i)));
                break;
            }
            int close = f.indexOf('}', open);
            String key = close < 0 ? "" : f.substring(open + 1, close);
            if (!key.equals("title") && !key.equals("name") && !key.equals("message")) {
                out = out.append(Text.mm(f.substring(i, close < 0 ? f.length() : close + 1)));
                i = close < 0 ? f.length() : close + 1;
                continue;
            }
            if (open > i) out = out.append(Text.mm(f.substring(i, open)));
            switch (key) {
                case "title" -> { if (title != null) out = out.append(title).append(Component.text(" ")); }
                case "name" -> out = out.append(name);
                default -> out = out.append(message);
            }
            i = close + 1;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Common stats
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        count(e.getPlayer().getUniqueId(), "deaths", 1);
        Player killer = e.getPlayer().getKiller();
        if (killer != null && killer != e.getPlayer()) count(killer.getUniqueId(), "player_kills", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        Player killer = e.getEntity().getKiller();
        if (killer != null) count(killer.getUniqueId(), "mob_kills", 1);
    }

    /** Placing and breaking on one spot over and over is not counted ({@link BlockCountGuard}): 10 minutes, 200k spots. */
    private final BlockCountGuard blockCounts = new BlockCountGuard(200_000, 10 * 60_000L);

    private record Spot(UUID world, int x, int y, int z) {}

    private static Object spot(org.bukkit.block.Block b) {
        return new Spot(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (blockCounts.breakBlock(spot(e.getBlock()), System.currentTimeMillis())) count(e.getPlayer().getUniqueId(), "blocks_broken", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        // furniture asks protection with a place event per cell before it is placed: that is not a block placed
        if (plugin.furniture() != null && plugin.furniture().probing()) return;
        if (blockCounts.place(spot(e.getBlock()), System.currentTimeMillis())) count(e.getPlayer().getUniqueId(), "blocks_placed", 1);
    }

    // ------------------------------------------------------------------------------------------
    // Titles
    // ------------------------------------------------------------------------------------------

    /** Titles an online player owns (from memory: no database call). Empty for offline players. */
    public java.util.Set<String> ownedTitles(UUID player) {
        ProfileStore.Titles t = online.get(player);
        return t == null ? java.util.Set.of() : t.owned();
    }

    /** Gives a title (with a message if the player is online). Unknown titles are refused. */
    public boolean grantTitle(UUID player, String titleId) {
        Optional<Title> t = title(titleId);
        if (t.isEmpty()) return false;
        store.titles(player).thenAccept(before -> {
            if (before.owned().contains(t.get().id())) return;
            store.grantTitle(player, t.get().id(), System.currentTimeMillis()).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(player);
                if (p == null) return;
                ProfileStore.Titles cur = online.get(player);
                if (cur != null) {
                    var owned = new java.util.HashSet<>(cur.owned());
                    owned.add(t.get().id());
                    online.put(player, new ProfileStore.Titles(owned, cur.equipped()));
                }
                p.sendMessage(Lang.msg("profile.title-got", "title", t.get().name()));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.3f);
            }));
        });
        return true;
    }

    public boolean revokeTitle(UUID player, String titleId) {
        Optional<Title> t = title(titleId);
        if (t.isEmpty()) return false;
        store.revokeTitle(player, t.get().id()).thenRun(() -> store.titles(player).thenAccept(now -> {
            if (online.containsKey(player)) online.put(player, now);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(player);
                if (p != null) showName(p);
            });
        }));
        return true;
    }

    /** Wears one of the player's titles (null takes it off). */
    public void equipTitle(Player p, String titleId) {
        equip(p, titleId);
    }

    private void equip(Player p, String titleId) {
        ProfileStore.Titles cur = online.getOrDefault(p.getUniqueId(), new ProfileStore.Titles(java.util.Set.of(), null));
        online.put(p.getUniqueId(), new ProfileStore.Titles(cur.owned(), titleId));
        store.equipTitle(p.getUniqueId(), titleId);
        showName(p);
    }

    // ------------------------------------------------------------------------------------------
    // /칭호: the player's titles first (worn, then owned), then the ones still to get and how
    // ------------------------------------------------------------------------------------------

    public void openTitles(Player p) {
        store.titles(p.getUniqueId()).thenAccept(t -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) plugin.menus().open(p, "stsv:titles", new TitlesMenu(t));
        }));
    }

    private final class TitlesMenu implements MenuHandler {
        ProfileStore.Titles mine;
        List<Title> shown = List.of();

        TitlesMenu(ProfileStore.Titles mine) {
            this.mine = mine;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            long have = titles.values().stream().filter(t -> mine.owned().contains(t.id())).count();
            return Map.of("owned", String.valueOf(have), "total", String.valueOf(titles.size()),
                    "worn", mine.equipped() == null ? Lang.raw("common.none") : title(mine.equipped()).map(Title::name).orElse(mine.equipped()));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<Title> l = new ArrayList<>(titles.values());
            l.sort(java.util.Comparator.comparingInt(t -> t.id().equals(mine.equipped()) ? 0 : mine.owned().contains(t.id()) ? 1 : 2));
            shown = l;
            List<ItemStack> out = new ArrayList<>();
            for (Title t : l) {
                boolean owned = mine.owned().contains(t.id());
                boolean worn = t.id().equals(mine.equipped());
                ItemStack it = new ItemStack(owned ? Material.NAME_TAG : Material.PAPER);
                it.editMeta(m -> {
                    m.itemName(owned ? Text.item(t.name()) : Text.item("<dark_gray>" + Text.plain(t.name())));
                    m.lore(Lang.items(worn ? "profile.title-worn-lore" : owned ? "profile.title-owned-lore" : "profile.title-locked-lore",
                            "description", t.description()));
                    m.setEnchantmentGlintOverride(worn);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            Player p = v.player();
            if (index >= shown.size()) return;
            Title t = shown.get(index);
            if (!mine.owned().contains(t.id())) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                return;
            }
            if (t.id().equals(mine.equipped())) {
                equip(p, null);
                dev.stsv.core.hud.HudModule.flash(p, Lang.mm("profile.title-removed"));
            } else {
                equip(p, t.id());
                dev.stsv.core.hud.HudModule.flash(p, Lang.mm("profile.title-equipped", "title", t.name()));
            }
            mine = online.getOrDefault(p.getUniqueId(), mine);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
            v.refresh();
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (!action.equals("titles:off")) return;
            Player p = v.player();
            equip(p, null);
            mine = online.getOrDefault(p.getUniqueId(), mine);
            dev.stsv.core.hud.HudModule.flash(p, Lang.mm("profile.title-removed"));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
            v.refresh();
        }
    }

    // ------------------------------------------------------------------------------------------
    // /프로필
    // ------------------------------------------------------------------------------------------

    public void openProfile(Player viewer, UUID target, String targetName) {
        String period = store.current().map(ProfileStore.Period::id).orElse(null);
        var all = store.stats(target, ProfileStore.ALL_TIME);
        var cur = period == null ? java.util.concurrent.CompletableFuture.completedFuture(Map.<String, Double>of()) : store.stats(target, period);
        var t = store.titles(target);
        var ach = plugin.achievements() == null ? java.util.concurrent.CompletableFuture.<dev.stsv.core.achievement.AchievementModule.Summary>completedFuture(null)
                : plugin.achievements().summaryOf(target);
        all.thenCombine(cur, (a, c) -> new Object[]{a, c}).thenCombine(t, (ac, tt) -> new Object[]{ac[0], ac[1], tt}).thenCombine(ach, (x, sum) -> {
            @SuppressWarnings("unchecked") Map<String, Double> a = (Map<String, Double>) x[0];
            @SuppressWarnings("unchecked") Map<String, Double> c = (Map<String, Double>) x[1];
            ProfileStore.Titles tt = (ProfileStore.Titles) x[2];
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline()) plugin.menus().open(viewer, "stsv:profile", new ProfileMenu(target, targetName, a, c, tt, sum));
            });
            return null;
        });
    }

    private final class ProfileMenu implements MenuHandler {
        final UUID target;
        final String name;
        final Map<String, Double> all, current;
        ProfileStore.Titles titlesOf;
        final List<Title> list = new ArrayList<>(titles.values());
        /** null when there is no achievement module */
        final dev.stsv.core.achievement.AchievementModule.Summary achievements;

        ProfileMenu(UUID target, String name, Map<String, Double> all, Map<String, Double> current, ProfileStore.Titles t,
                    dev.stsv.core.achievement.AchievementModule.Summary achievements) {
            this.target = target;
            this.name = name;
            this.all = all;
            this.current = current;
            this.titlesOf = t;
            this.achievements = achievements;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("player", name, "title", Lang.raw("profile.menu-title", "player", name));
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (b.key() == 'A') return achievementsButton(base);
            if (b.key() != 'H') return base;
            OfflinePlayer op = Bukkit.getOfflinePlayer(target);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            head.editMeta(SkullMeta.class, m -> {
                m.setOwningPlayer(op);
                m.itemName(Lang.item("profile.head-name", "player", name));
                List<Component> lore = new ArrayList<>();
                if (dev.stsv.core.nickname.Names.nickname(target).isPresent()) {
                    lore.add(Lang.item("profile.real-name", "name", dev.stsv.core.nickname.Names.real(target)));
                }
                lore.add(titlesOf.equipped() == null ? Lang.item("profile.title-none")
                        : Lang.item("profile.title-current", "title", title(titlesOf.equipped()).map(Title::name).orElse(titlesOf.equipped())));
                if (achievements != null) lore.add(Lang.item("profile.achievement-points", "points",
                        String.format(java.util.Locale.ROOT, "%,d", achievements.points()), "done", achievements.done(), "total", achievements.total()));
                lore.add(Text.item(""));
                Optional<ProfileStore.Period> p = store.current();
                if (p.isPresent()) {
                    lore.add(Lang.item("profile.section-period", "period", p.get().name()));
                    lines(lore, current);
                } else {
                    lore.add(Lang.item("profile.no-period"));
                }
                lore.add(Text.item(""));
                lore.add(Lang.item("profile.section-all"));
                lines(lore, all);
                m.lore(lore);
            });
            return head;
        }

        /** The target's achievements: points, per difficulty, the latest five. */
        private ItemStack achievementsButton(ItemStack base) {
            if (base == null || achievements == null) return base;
            var a = achievements;
            base.editMeta(m -> {
                m.itemName(Lang.item("profile.achievements-name", "points", String.format(java.util.Locale.ROOT, "%,d", a.points())));
                List<Component> lore = new ArrayList<>();
                lore.add(Lang.item("profile.achievements-done", "done", a.done(), "total", a.total()));
                a.perTier().forEach((t, x) -> lore.add(Lang.item("profile.achievements-tier", "color", t.color, "tier", t.label, "done", x[0], "total", x[1])));
                if (!a.recent().isEmpty()) {
                    lore.add(Text.item(""));
                    lore.add(Lang.item("profile.achievements-recent"));
                    for (var s : a.recent()) lore.add(Text.item("<dark_gray> · </dark_gray>" + s.tier().color + s.name()));
                }
                lore.add(Text.item(""));
                lore.add(Lang.item("profile.achievements-click"));
                m.lore(lore);
            });
            return base;
        }

        private void lines(List<Component> lore, Map<String, Double> stats) {
            for (String s : plugin.getConfig().getStringList("profiles.shown-stats")) {
                lore.add(Lang.item("profile.stat-line", "stat", statName(s), "value", format(s, stats.getOrDefault(s, 0.0))));
            }
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            for (Title t : list) {
                boolean owned = titlesOf.owned().contains(t.id());
                boolean worn = t.id().equals(titlesOf.equipped());
                ItemStack it = new ItemStack(owned ? Material.NAME_TAG : Material.PAPER);
                it.editMeta(m -> {
                    m.itemName(Text.item(t.name()));
                    m.lore(Lang.items(worn ? "profile.title-worn-lore" : owned ? "profile.title-owned-lore" : "profile.title-locked-lore",
                            "description", t.description()));
                    m.setEnchantmentGlintOverride(worn);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            Player p = v.player();
            if (index >= list.size()) return;
            if (!p.getUniqueId().equals(target)) {
                dev.stsv.core.hud.HudModule.flash(p, Lang.mm("profile.other-titles"));
                return;
            }
            Title t = list.get(index);
            if (!titlesOf.owned().contains(t.id())) return;
            if (t.id().equals(titlesOf.equipped())) {
                equip(p, null);
                dev.stsv.core.hud.HudModule.flash(p, Lang.mm("profile.title-removed"));
            } else {
                equip(p, t.id());
                dev.stsv.core.hud.HudModule.flash(p, Lang.mm("profile.title-equipped", "title", t.name()));
            }
            titlesOf = online.getOrDefault(target, titlesOf);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
            v.refresh();
        }
    }

    public static String statName(String stat) {
        String n = Lang.raw("stats." + stat);
        return n.startsWith("<red>[") ? stat : n;
    }

    public static String format(String stat, double v) {
        if (stat.equals("playtime")) {
            long minutes = (long) (v / 60);
            return minutes >= 60 ? Lang.raw("time.hours-minutes", "h", minutes / 60, "m", minutes % 60) : Lang.raw("time.minutes", "m", minutes);
        }
        return v == Math.rint(v) ? String.format("%,d", (long) v) : String.format("%,.1f", v);
    }

    // ------------------------------------------------------------------------------------------
    // /명예의전당
    // ------------------------------------------------------------------------------------------

    public void showHall(org.bukkit.command.CommandSender to, String periodId) {
        store.periods().thenAccept(periods -> {
            List<ProfileStore.Period> ended = periods.stream().filter(p -> p.ended() != null).toList();
            Optional<ProfileStore.Period> pick = periodId == null ? ended.stream().findFirst()
                    : periods.stream().filter(p -> p.id().equalsIgnoreCase(periodId) || p.name().equals(periodId)).findFirst();
            if (pick.isEmpty()) {
                to.sendMessage(Lang.msg("hall.empty"));
                return;
            }
            store.hall(pick.get().id()).thenAccept(hall -> {
                to.sendMessage(Lang.msg("hall.header", "period", pick.get().name()));
                if (hall.isEmpty()) to.sendMessage(Lang.mm("hall.empty"));
                for (var cat : hall.entrySet()) {
                    to.sendMessage(Lang.mm("hall.category", "stat", statName(cat.getKey())));
                    for (ProfileStore.Rank r : cat.getValue()) {
                        to.sendMessage(Lang.mm("hall.line", "rank", r.rank(), "player", dev.stsv.core.nickname.Names.mini(r.player()), "value", format(cat.getKey(), r.value())));
                    }
                }
                if (ended.size() > 1) {
                    to.sendMessage(Lang.mm("hall.periods", "list", String.join(", ", ended.stream().map(ProfileStore.Period::id).toList())));
                }
            });
        });
    }

    // ------------------------------------------------------------------------------------------
    // Content periods (admin)
    // ------------------------------------------------------------------------------------------

    public ProfileStore.Period startPeriod(String id, String name) {
        ProfileStore.Period p = store.start(id, name, System.currentTimeMillis());
        Bukkit.broadcast(Lang.msg("hall.started", "period", p.name()));
        return p;
    }

    public Map<String, List<ProfileStore.Rank>> endPeriod() {
        return endPeriod(List.of());
    }

    /** Ends the period; a content adds its own hall-of-fame categories first (e.g. "net_worth"). */
    public Map<String, List<ProfileStore.Rank>> endPeriod(List<String> extraCategories) {
        String name = store.current().map(ProfileStore.Period::name).orElse("");
        for (Player p : Bukkit.getOnlinePlayers()) countPlaytime(p);
        List<String> categories = new ArrayList<>(extraCategories);
        for (String c : plugin.getConfig().getStringList("profiles.hall-of-fame")) if (!categories.contains(c)) categories.add(c);
        Map<String, List<ProfileStore.Rank>> hall = store.end(System.currentTimeMillis(), categories, plugin.getConfig().getInt("profiles.hall-size"));
        Bukkit.broadcast(Lang.msg("hall.ended", "period", name));
        return hall;
    }
}
