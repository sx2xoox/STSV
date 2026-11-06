package dev.stsv.core.hud;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The always-on status line above the hotbar (action bar), the sidebar on the right and boss bars.
 *
 * Sidebar: contents add rows ({@link #sidebar}), each asked every refresh, null to hide; shown top to
 * bottom by their order under a title, on the player's own scoreboard with the score numbers hidden.
 *
 * Contents add lines ({@link #line}): each is asked every refresh and may answer null to stay hidden;
 * the visible ones are joined left to right by their order. A one-off action-bar message must go
 * through {@link #flash} so the status line steps aside for a few seconds instead of overwriting it.
 */
public final class HudModule implements StsvModule, Listener {

    private record Line(String id, int order, Function<Player, Component> render) {}

    private final Map<String, Line> rows = new LinkedHashMap<>();
    private final Map<UUID, org.bukkit.scoreboard.Scoreboard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, List<Component>> shown = new ConcurrentHashMap<>();

    private static HudModule instance;

    private STSVPlugin plugin;
    private final Map<String, Line> lines = new LinkedHashMap<>();
    private final Map<UUID, Long> pausedUntil = new ConcurrentHashMap<>();
    private final Map<String, BossBar> bars = new LinkedHashMap<>();
    private BukkitTask ticker;
    private long flashMillis;

    @Override
    public String id() {
        return "hud";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        start();
    }

    private void start() {
        flashMillis = plugin.getConfig().getLong("hud.flash-seconds") * 1000L;
        long every = Math.max(5, plugin.getConfig().getInt("hud.refresh-ticks"));
        if (ticker != null) ticker.cancel();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, every, every);
    }

    @Override
    public void reload() {
        start();
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) clearSidebar(p);
        for (BossBar bar : bars.values()) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        bars.clear();
        HandlerList.unregisterAll(this);
        instance = null;
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
        player.sendActionBar(message);
        HudModule hud = instance;
        if (hud != null) hud.pausedUntil.put(player.getUniqueId(), System.currentTimeMillis() + hud.flashMillis);
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
        List<Line> ordered = new ArrayList<>(lines.values());
        ordered.sort(Comparator.comparingInt(Line::order));
        long now = System.currentTimeMillis();
        Component separator = Lang.mm("hud.separator");
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long until = pausedUntil.get(p.getUniqueId());
            if (until != null) {
                if (until > now) continue;
                pausedUntil.remove(p.getUniqueId());
            }
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
            if (out != null) p.sendActionBar(out);
        }
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

    private static final String OBJECTIVE = "stsv";

    private void refreshSidebar(Player p) {
        List<Line> ordered = new ArrayList<>(rows.values());
        ordered.sort(Comparator.comparingInt(Line::order));
        List<Component> lines = new ArrayList<>();
        for (Line l : ordered) {
            Component c;
            try {
                c = l.render().apply(p);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("사이드바 줄 '" + l.id() + "' 오류: " + e.getMessage());
                continue;
            }
            if (c != null) lines.add(c);
        }
        // no gap at the ends, no two gaps in a row
        List<Component> tidy = new ArrayList<>();
        for (Component c : lines) {
            boolean gap = c.equals(Component.empty());
            if (gap && (tidy.isEmpty() || tidy.getLast().equals(Component.empty()))) continue;
            tidy.add(c);
        }
        while (!tidy.isEmpty() && tidy.getLast().equals(Component.empty())) tidy.removeLast();
        if (tidy.size() > 15) tidy = new ArrayList<>(tidy.subList(0, 15));
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

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pausedUntil.remove(e.getPlayer().getUniqueId());
        shown.remove(e.getPlayer().getUniqueId());
        boards.remove(e.getPlayer().getUniqueId());
    }
}
