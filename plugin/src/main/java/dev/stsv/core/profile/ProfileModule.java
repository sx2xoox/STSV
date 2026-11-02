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
        store.add(player, stat, delta);
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
        if (since != null) store.add(p.getUniqueId(), "playtime", (now - since) / 1000.0);
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
    }

    private Optional<Title> worn(UUID player) {
        ProfileStore.Titles t = online.get(player);
        return t == null || t.equipped() == null ? Optional.empty() : title(t.equipped());
    }

    private void showName(Player p) {
        if (!plugin.getConfig().getBoolean("profiles.title-in-tab")) return;
        p.playerListName(worn(p.getUniqueId()).map(t -> Text.mm(t.name() + " ").append(Component.text(p.getName()))).orElse(null));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("profiles.title-in-chat")) return;
        Optional<Title> t = worn(e.getPlayer().getUniqueId());
        if (t.isEmpty()) return;
        Component title = Text.mm(t.get().name());
        e.renderer((source, name, message, viewer) -> Component.empty().append(title).append(Component.text(" <"))
                .append(name).append(Component.text("> ")).append(message));
    }

    // ------------------------------------------------------------------------------------------
    // Common stats
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        store.add(e.getPlayer().getUniqueId(), "deaths", 1);
        Player killer = e.getPlayer().getKiller();
        if (killer != null && killer != e.getPlayer()) store.add(killer.getUniqueId(), "player_kills", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        Player killer = e.getEntity().getKiller();
        if (killer != null) store.add(killer.getUniqueId(), "mob_kills", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        store.add(e.getPlayer().getUniqueId(), "blocks_broken", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        store.add(e.getPlayer().getUniqueId(), "blocks_placed", 1);
    }

    // ------------------------------------------------------------------------------------------
    // Titles
    // ------------------------------------------------------------------------------------------

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

    private void equip(Player p, String titleId) {
        ProfileStore.Titles cur = online.getOrDefault(p.getUniqueId(), new ProfileStore.Titles(java.util.Set.of(), null));
        online.put(p.getUniqueId(), new ProfileStore.Titles(cur.owned(), titleId));
        store.equipTitle(p.getUniqueId(), titleId);
        showName(p);
    }

    // ------------------------------------------------------------------------------------------
    // /프로필
    // ------------------------------------------------------------------------------------------

    public void openProfile(Player viewer, UUID target, String targetName) {
        String period = store.current().map(ProfileStore.Period::id).orElse(null);
        var all = store.stats(target, ProfileStore.ALL_TIME);
        var cur = period == null ? java.util.concurrent.CompletableFuture.completedFuture(Map.<String, Double>of()) : store.stats(target, period);
        var t = store.titles(target);
        all.thenCombine(cur, (a, c) -> new Object[]{a, c}).thenCombine(t, (ac, tt) -> {
            @SuppressWarnings("unchecked") Map<String, Double> a = (Map<String, Double>) ac[0];
            @SuppressWarnings("unchecked") Map<String, Double> c = (Map<String, Double>) ac[1];
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline()) plugin.menus().open(viewer, "stsv:profile", new ProfileMenu(target, targetName, a, c, tt));
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

        ProfileMenu(UUID target, String name, Map<String, Double> all, Map<String, Double> current, ProfileStore.Titles t) {
            this.target = target;
            this.name = name;
            this.all = all;
            this.current = current;
            this.titlesOf = t;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("player", name, "title", Lang.raw("profile.menu-title", "player", name));
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (b.key() != 'H') return base;
            OfflinePlayer op = Bukkit.getOfflinePlayer(target);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            head.editMeta(SkullMeta.class, m -> {
                m.setOwningPlayer(op);
                m.itemName(Lang.item("profile.head-name", "player", name));
                List<Component> lore = new ArrayList<>();
                lore.add(titlesOf.equipped() == null ? Lang.item("profile.title-none")
                        : Lang.item("profile.title-current", "title", title(titlesOf.equipped()).map(Title::name).orElse(titlesOf.equipped())));
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
                        to.sendMessage(Lang.mm("hall.line", "rank", r.rank(), "player", r.name(), "value", format(cat.getKey(), r.value())));
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
        String name = store.current().map(ProfileStore.Period::name).orElse("");
        for (Player p : Bukkit.getOnlinePlayers()) countPlaytime(p);
        Map<String, List<ProfileStore.Rank>> hall = store.end(System.currentTimeMillis(),
                plugin.getConfig().getStringList("profiles.hall-of-fame"), plugin.getConfig().getInt("profiles.hall-size"));
        Bukkit.broadcast(Lang.msg("hall.ended", "period", name));
        return hall;
    }
}
