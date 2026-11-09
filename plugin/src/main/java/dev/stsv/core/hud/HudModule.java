package dev.stsv.core.hud;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The always-on status line above the hotbar (action bar), the sidebar and boss bars.
 *
 * Two looks (config hud.mode):
 * <ul>
 *   <li>pack (default): drawn with the server resource pack's fonts (HudLayout). The sidebar is a charcoal and
 *       gold panel with an icon per row, in the title of a boss bar whose bar the pack makes see-through; the
 *       hearts, hunger and armor are replaced by bars with numbers ("18 / 20") on the action bar, and one-off
 *       messages are shown on the same action bar above them, so both are always visible. Nothing touches the
 *       player's scoreboard, so other plugins' scoreboards and teams keep working. Players whose client refused
 *       the pack get the scoreboard sidebar instead and keep the game's hearts.</li>
 *   <li>scoreboard: the sidebar on the player's own scoreboard, the game's hearts.</li>
 * </ul>
 * Every player can hide the sidebar (/사이드바, kept on the player: HudToggle).
 *
 * Sidebar: contents add rows ({@link #sidebar}), each asked every refresh, null to hide; shown top to bottom by
 * their order under a title. Component.empty() is a gap (a divider line in the panel). The panel picks each row's
 * icon by the row's id (hud.sidebar.icons).
 *
 * Contents add lines ({@link #line}): each is asked every refresh and may answer null to stay hidden; the visible
 * ones are joined left to right by their order. A one-off action-bar message must go through {@link #flash} so the
 * status line steps aside for a few seconds instead of overwriting it (and, in pack mode, so the health bars stay).
 */
public final class HudModule implements StsvModule, Listener, CommandExecutor, TabCompleter {

    private record Line(String id, int order, Function<Player, Component> render) {}

    private record Flash(Component message, long until) {}

    private record Sent(HudLayout.Health health, Component message, long tick) {}

    public enum Mode { PACK, SCOREBOARD }

    private final Map<String, Line> rows = new LinkedHashMap<>();
    private final Map<UUID, org.bukkit.scoreboard.Scoreboard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, List<Component>> shown = new ConcurrentHashMap<>();

    private static HudModule instance;

    private STSVPlugin plugin;
    private final Map<String, Line> lines = new LinkedHashMap<>();
    private final Map<UUID, Long> pausedUntil = new ConcurrentHashMap<>();
    private final Map<String, BossBar> bars = new LinkedHashMap<>();
    private BukkitTask ticker, barTicker;
    private long flashMillis;
    private long tick;

    // pack mode
    private Mode mode = Mode.SCOREBOARD;
    private HudSheets sheets;
    private HudLayout layout;
    private boolean sidebarOn = true, healthOn = true;
    private BossBar.Color panelColour = BossBar.Color.WHITE;
    private int resendTicks = 20;
    private final Map<String, String> icons = new HashMap<>();
    private NamespacedKey toggleKey;
    private final Map<UUID, BossBar> panels = new ConcurrentHashMap<>();
    private final Set<UUID> packReady = ConcurrentHashMap.newKeySet();
    private final Set<UUID> packRefused = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Flash> flashes = new ConcurrentHashMap<>();
    private final Map<UUID, Sent> sent = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "hud";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        toggleKey = new NamespacedKey(plugin, "hud_sidebar");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // a plugin reload with players online: they already have the pack
        for (Player p : Bukkit.getOnlinePlayers()) packReady.add(p.getUniqueId());
        start();
    }

    private void start() {
        var cfg = plugin.getConfig();
        flashMillis = cfg.getLong("hud.flash-seconds") * 1000L;
        Mode wanted = "scoreboard".equalsIgnoreCase(cfg.getString("hud.mode", "pack")) ? Mode.SCOREBOARD : Mode.PACK;
        if (wanted == Mode.PACK && !cfg.getBoolean("resource-pack.enabled", true)) {
            plugin.getLogger().warning("hud.mode 가 pack 이지만 리소스팩(resource-pack.enabled)이 꺼져 있어 스코어보드 사이드바를 씁니다");
            wanted = Mode.SCOREBOARD;
        }
        sidebarOn = cfg.getBoolean("hud.sidebar.enabled", true);
        healthOn = cfg.getBoolean("hud.health.enabled", true);
        resendTicks = Math.max(10, cfg.getInt("hud.health.resend-ticks", 20));
        try {
            panelColour = BossBar.Color.valueOf(cfg.getString("hud.sidebar.boss-bar-color", "white").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("hud.sidebar.boss-bar-color 를 알 수 없음, white 사용");
            panelColour = BossBar.Color.WHITE;
        }
        icons.clear();
        ConfigurationSection ic = cfg.getConfigurationSection("hud.sidebar.icons");
        if (ic != null) for (String k : ic.getKeys(false)) icons.put(k, ic.getString(k, ""));
        if (wanted == Mode.PACK) {
            try {
                if (sheets == null) sheets = HudSheets.load();
                layout = new HudLayout(sheets, settings(cfg));
            } catch (Exception e) {
                plugin.getLogger().warning("리소스팩 HUD 를 준비하지 못해 스코어보드 사이드바를 씁니다: " + e);
                wanted = Mode.SCOREBOARD;
            }
        }
        if (mode != wanted) {
            // switching looks: take the old one off everyone
            for (Player p : Bukkit.getOnlinePlayers()) {
                hidePanel(p);
                clearSidebar(p);
            }
            sent.clear();
        }
        mode = wanted;
        sent.clear();   // positions may have moved: send everything again
        long every = Math.max(5, cfg.getInt("hud.refresh-ticks"));
        if (ticker != null) ticker.cancel();
        if (barTicker != null) barTicker.cancel();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, every, every);
        barTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshBars, 2L, 2L);
    }

    static HudLayout.Settings settings(org.bukkit.configuration.Configuration cfg) {
        HudLayout.Settings d = HudLayout.Settings.defaults();
        return new HudLayout.Settings(cfg.getInt("hud.sidebar.x", d.sidebarX()), cfg.getInt("hud.sidebar.y", d.sidebarY()),
                cfg.getInt("hud.sidebar.max-width", d.sidebarMaxWidth()), cfg.getInt("hud.sidebar.max-rows", d.sidebarRows()),
                cfg.getBoolean("hud.health.armor", d.armor()));
    }

    @Override
    public void reload() {
        start();
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        if (barTicker != null) barTicker.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) {
            clearSidebar(p);
            hidePanel(p);
        }
        for (BossBar bar : bars.values()) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        bars.clear();
        HandlerList.unregisterAll(this);
        instance = null;
    }

    public Mode mode() {
        return mode;
    }

    /**
     * The HUD's resource-pack files (fonts, glyph sheets, see-through vanilla sprites), empty in scoreboard mode.
     * The pack module adds them to the server pack.
     */
    public Map<String, byte[]> packFiles() {
        if (mode != Mode.PACK || layout == null) return Map.of();
        return HudPack.files(sheets, layout, sidebarOn ? panelColour.name() : null, healthOn);
    }

    /** The pack HUD is drawn for this player (pack mode and their client loaded the pack). */
    private boolean packFor(Player p) {
        return mode == Mode.PACK && packReady.contains(p.getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // Action bar
    // ------------------------------------------------------------------------------------------

    /** Adds (or replaces) a status line. Lower order = further left. */
    public void line(String id, int order, Function<Player, Component> render) {
        lines.put(id, new Line(id, order, render));
    }

    public void removeLine(String id) {
        lines.remove(id);
    }

    /** Shows a one-off message on the action bar; the status line waits until it has been read. */
    public static void flash(Player player, Component message) {
        HudModule hud = instance;
        flash(player, message, hud == null ? 3000 : hud.flashMillis);
    }

    /**
     * A message on the action bar for the given time (a minigame's status every tick, say). In pack mode it is
     * drawn above the health bars instead of replacing them.
     */
    public static void flash(Player player, Component message, long millis) {
        HudModule hud = instance;
        if (hud != null && hud.healthFor(player) != null) {
            hud.flashes.put(player.getUniqueId(), new Flash(message, System.currentTimeMillis() + millis));
            hud.sendBars(player, true);
            return;
        }
        player.sendActionBar(message);
        if (hud != null) hud.pausedUntil.put(player.getUniqueId(), System.currentTimeMillis() + millis);
    }

    /** Takes a message shown with {@link #flash} off again. */
    public static void clearFlash(Player player) {
        HudModule hud = instance;
        if (hud != null && hud.flashes.remove(player.getUniqueId()) != null) {
            hud.sendBars(player, true);
            return;
        }
        if (hud != null) hud.pausedUntil.remove(player.getUniqueId());
        player.sendActionBar(Component.empty());
    }

    private Component statusLine(Player p) {
        if (lines.isEmpty()) return null;
        List<Line> ordered = new ArrayList<>(lines.values());
        ordered.sort(Comparator.comparingInt(Line::order));
        Component separator = Lang.mm("hud.separator");
        Component out = null;
        for (Line l : ordered) {
            Component c;
            try {
                c = l.render().apply(p);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("HUD 줄 '" + l.id() + "' 오류: " + e.getMessage());
                continue;
            }
            if (c == null) continue;
            out = out == null ? c : out.append(separator).append(c);
        }
        return out;
    }

    private void refresh() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                refreshSidebar(p);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("사이드바 오류: " + e.getMessage());
            }
        }
        if (lines.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (healthFor(p) != null) continue;   // drawn with the health bars (refreshBars)
            Long until = pausedUntil.get(p.getUniqueId());
            if (until != null) {
                if (until > now) continue;
                pausedUntil.remove(p.getUniqueId());
            }
            Component out = statusLine(p);
            if (out != null) p.sendActionBar(out);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Health bars (pack mode)
    // ------------------------------------------------------------------------------------------

    /** What the bars show for this player, or null when the pack HUD does not draw them (as the game hides hearts). */
    HudLayout.Health healthFor(Player p) {
        if (!healthOn || layout == null || !packFor(p)) return null;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.isDead()) return null;
        AttributeInstance maxAttr = p.getAttribute(Attribute.MAX_HEALTH);
        AttributeInstance armorAttr = p.getAttribute(Attribute.ARMOR);
        double max = maxAttr == null ? 20 : maxAttr.getValue();
        int armor = armorAttr == null ? 0 : (int) Math.floor(armorAttr.getValue());
        HudLayout.Tint tint = p.hasPotionEffect(PotionEffectType.POISON) ? HudLayout.Tint.POISON
                : p.hasPotionEffect(PotionEffectType.WITHER) ? HudLayout.Tint.WITHER
                : p.isFrozen() ? HudLayout.Tint.FROZEN : HudLayout.Tint.NORMAL;
        return new HudLayout.Health(p.getHealth(), max, p.getAbsorptionAmount(), p.getFoodLevel(), p.getSaturation(), armor, tint,
                p.hasPotionEffect(PotionEffectType.HUNGER), !(p.getVehicle() instanceof LivingEntity));
    }

    private void refreshBars() {
        tick += 2;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                sendBars(p, false);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("체력바 오류: " + e.getMessage());
            }
        }
    }

    /** Sends the bars (and the message above them) when they changed, or again before the game fades them out. */
    private void sendBars(Player p, boolean now) {
        UUID u = p.getUniqueId();
        HudLayout.Health h = healthFor(p);
        if (h == null) {
            if (sent.remove(u) != null && flashes.remove(u) == null) p.sendActionBar(Component.empty());
            return;
        }
        Flash f = flashes.get(u);
        if (f != null && f.until() < System.currentTimeMillis()) {
            flashes.remove(u);
            f = null;
        }
        Component message = f != null ? f.message() : statusLine(p);
        Sent last = sent.get(u);
        if (!now && last != null && last.health().equals(h) && java.util.Objects.equals(last.message(), message)
                && tick - last.tick() < resendTicks) return;
        p.sendActionBar(layout.actionBar(h, message));
        sent.put(u, new Sent(h, message, tick));
    }

    // ------------------------------------------------------------------------------------------
    // Sidebar
    // ------------------------------------------------------------------------------------------

    /** Adds (or replaces) a sidebar row. Lower order = higher up. Answer Component.empty() for a gap. */
    public void sidebar(String id, int order, Function<Player, Component> render) {
        rows.put(id, new Line(id, order, render));
    }

    public void removeSidebar(String id) {
        rows.remove(id);
    }

    private record Entry(String id, Component text) {}

    /** The rows to show now: no gap at the ends, no two gaps in a row. */
    private List<Entry> entries(Player p) {
        List<Line> ordered = new ArrayList<>(rows.values());
        ordered.sort(Comparator.comparingInt(Line::order));
        List<Entry> tidy = new ArrayList<>();
        for (Line l : ordered) {
            Component c;
            try {
                c = l.render().apply(p);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("사이드바 줄 '" + l.id() + "' 오류: " + e.getMessage());
                continue;
            }
            if (c == null) continue;
            boolean gap = c.equals(Component.empty());
            if (gap && (tidy.isEmpty() || tidy.getLast().text().equals(Component.empty()))) continue;
            tidy.add(new Entry(l.id(), c));
        }
        while (!tidy.isEmpty() && tidy.getLast().text().equals(Component.empty())) tidy.removeLast();
        return tidy;
    }

    /** Whether this player wants the sidebar (/사이드바). */
    public boolean sidebarShown(Player p) {
        return HudToggle.shown(store(p));
    }

    private HudToggle.Store store(Player p) {
        return new HudToggle.Store() {
            @Override
            public Byte get() {
                return p.getPersistentDataContainer().get(toggleKey, PersistentDataType.BYTE);
            }

            @Override
            public void set(Byte value) {
                if (value == null) p.getPersistentDataContainer().remove(toggleKey);
                else p.getPersistentDataContainer().set(toggleKey, PersistentDataType.BYTE, value);
            }
        };
    }

    private void refreshSidebar(Player p) {
        boolean want = sidebarOn && sidebarShown(p);
        if (packFor(p)) {
            clearSidebar(p);
            List<Entry> tidy = want ? entries(p) : List.of();
            List<HudLayout.Row> out = new ArrayList<>();
            boolean divider = false;
            for (Entry e : tidy) {
                if (e.text().equals(Component.empty())) {
                    divider = true;
                    continue;
                }
                out.add(new HudLayout.Row(icons.get(e.id()), e.text(), divider));
                divider = false;
            }
            Component panel = out.isEmpty() ? null : layout.panel(Lang.mm("hud.sidebar-title"), out);
            if (panel == null) hidePanel(p);
            else showPanel(p, panel);
            return;
        }
        hidePanel(p);
        if (mode == Mode.PACK && !packRefused.contains(p.getUniqueId())) {
            clearSidebar(p);   // the pack is on its way
            return;
        }
        List<Component> tidy = new ArrayList<>();
        if (want) for (Entry e : entries(p)) tidy.add(e.text());
        scoreboardSidebar(p, tidy);
    }

    private void showPanel(Player p, Component panel) {
        BossBar bar = panels.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(panel, 0f, panelColour, BossBar.Overlay.PROGRESS);
            panels.put(p.getUniqueId(), bar);
            // the panel is drawn from the first boss bar's line: put it before the event bars
            for (BossBar b : bars.values()) p.hideBossBar(b);
            p.showBossBar(bar);
            for (BossBar b : bars.values()) p.showBossBar(b);
        } else if (!bar.name().equals(panel)) {
            bar.name(panel);
        }
    }

    private void hidePanel(Player p) {
        BossBar bar = panels.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    private static final String OBJECTIVE = "stsv";

    private void scoreboardSidebar(Player p, List<Component> lines) {
        List<Component> tidy = lines.size() > 15 ? new ArrayList<>(lines.subList(0, 15)) : lines;
        UUID u = p.getUniqueId();
        if (tidy.isEmpty()) {
            clearSidebar(p);
            return;
        }
        if (tidy.equals(shown.get(u)) && boards.containsKey(u) && p.getScoreboard() == boards.get(u)) return;
        org.bukkit.scoreboard.Scoreboard sb = boards.computeIfAbsent(u, k -> Bukkit.getScoreboardManager().getNewScoreboard());
        org.bukkit.scoreboard.Objective o = sb.getObjective(OBJECTIVE);
        if (o == null) {
            o = sb.registerNewObjective(OBJECTIVE, org.bukkit.scoreboard.Criteria.DUMMY, Lang.mm("hud.sidebar-title"));
            o.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
            o.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        } else {
            o.displayName(Lang.mm("hud.sidebar-title"));
        }
        List<Component> before = shown.getOrDefault(u, List.of());
        for (int i = 0; i < tidy.size(); i++) {
            org.bukkit.scoreboard.Score score = o.getScore("r" + i);
            if (i >= before.size() || !tidy.get(i).equals(before.get(i)) || !score.isScoreSet()) {
                score.setScore(tidy.size() - i);
                score.customName(tidy.get(i));
            } else if (score.getScore() != tidy.size() - i) {
                score.setScore(tidy.size() - i);
            }
        }
        for (int i = tidy.size(); i < before.size(); i++) sb.resetScores("r" + i);
        shown.put(u, tidy);
        if (p.getScoreboard() != sb) p.setScoreboard(sb);
    }

    private void clearSidebar(Player p) {
        UUID u = p.getUniqueId();
        shown.remove(u);
        org.bukkit.scoreboard.Scoreboard sb = boards.remove(u);
        if (sb != null && p.getScoreboard() == sb) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    // ------------------------------------------------------------------------------------------
    // /사이드바 [켜기|끄기]
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        Boolean on = HudToggle.apply(store(p), args.length == 0 ? null : args[0]);
        if (on == null) {
            p.sendMessage(Lang.msg("hud.sidebar-usage"));
            return true;
        }
        p.sendMessage(Lang.msg(on ? "hud.sidebar-on" : "hud.sidebar-off"));
        refreshSidebar(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        return List.of("켜기", "끄기").stream().filter(s -> s.startsWith(args[0])).toList();
    }

    // ------------------------------------------------------------------------------------------
    // Boss bars (events: everyone online sees them, including players who join later)
    // ------------------------------------------------------------------------------------------

    /** Shows or updates an event bar. progress 0..1. */
    public BossBar bar(String id, Component title, float progress, BossBar.Color color) {
        float clamped = Math.max(0f, Math.min(1f, progress));
        BossBar bar = bars.get(id);
        if (bar == null) {
            bar = BossBar.bossBar(title, clamped, color, BossBar.Overlay.PROGRESS);
            bars.put(id, bar);
            for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
        } else {
            bar.name(title).progress(clamped).color(color);
        }
        return bar;
    }

    public void hideBar(String id) {
        BossBar bar = bars.remove(id);
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        for (BossBar bar : bars.values()) e.getPlayer().showBossBar(bar);
    }

    /** The pack HUD starts once the client has loaded the server pack (until then its glyphs would be boxes). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackStatus(PlayerResourcePackStatusEvent e) {
        if (!e.getID().equals(dev.stsv.core.pack.PackModule.PACK_ID)) return;
        UUID u = e.getPlayer().getUniqueId();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                packReady.add(u);
                packRefused.remove(u);
                sent.remove(u);
                refreshSidebar(e.getPlayer());
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                packReady.remove(u);
                packRefused.add(u);
                hidePanel(e.getPlayer());
                refreshSidebar(e.getPlayer());
            }
            default -> {}
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        pausedUntil.remove(u);
        shown.remove(u);
        boards.remove(u);
        panels.remove(u);
        packReady.remove(u);
        packRefused.remove(u);
        flashes.remove(u);
        sent.remove(u);
    }
}
