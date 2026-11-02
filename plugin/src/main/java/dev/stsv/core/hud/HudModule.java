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
 * The always-on status line above the hotbar (action bar) and event boss bars.
 *
 * Contents add lines ({@link #line}): each is asked every refresh and may answer null to stay hidden;
 * the visible ones are joined left to right by their order. A one-off action-bar message must go
 * through {@link #flash} so the status line steps aside for a few seconds instead of overwriting it.
 */
public final class HudModule implements StsvModule, Listener {

    private record Line(String id, int order, Function<Player, Component> render) {}

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
    }
}
