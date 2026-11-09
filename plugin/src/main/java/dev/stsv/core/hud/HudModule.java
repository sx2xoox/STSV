package dev.stsv.core.hud;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *       gold panel with the server mark and an icon per row, in the title of a boss bar whose bar the pack makes
 *       see-through; the hearts, hunger, armor, air bubbles and a mount's hearts are replaced by segmented bars
 *       with their numbers beside them on the action bar (status colours, absorption, saturation, a short flash
 *       when health drops or jumps), and one-off messages are shown on the same action bar above them, so both
 *       are always visible. Nothing touches the player's scoreboard, so other plugins' scoreboards and teams keep
 *       working. Players whose client refused the pack get the scoreboard sidebar instead and keep the game's
 *       hearts.</li>
 *   <li>scoreboard: the sidebar on the player's own scoreboard, the game's hearts.</li>
 * </ul>
 * Every player can hide the sidebar (/사이드바, kept on the player: HudToggle) and move it and change its size with
 * the mouse (/사이드바 위치: see the edit mode below; the place is kept on the player).
 *
 * Sidebar: contents add rows ({@link #sidebar}), each asked every refresh, null to hide; shown top to bottom by
 * their order under a title. Component.empty() is a gap (a divider line in the panel). A tab in a row separates
 * its label from its value (the panel puts the value against its right edge; the scoreboard shows a space). The
 * panel picks each row's icon by the row's id (hud.sidebar.icons).
 *
 * Contents add lines ({@link #line}): each is asked every refresh and may answer null to stay hidden; the visible
 * ones are joined left to right by their order. A one-off action-bar message must go through {@link #flash} so the
 * status line steps aside for a few seconds instead of overwriting it (and, in pack mode, so the health bars stay).
 *
 * Edit mode (/사이드바 위치, HudEdit): the player stands still (walk and fly speed 0, no jumping; nothing else about
 * them changes: no protection, no other movement) while turning the view moves the panel, drawn every tick with
 * corner brackets; the wheel changes its size; left click or F saves, sneaking or /사이드바 위치 취소 cancels, a
 * minute cancels. Being hurt, dying, leaving, being teleported, changing game mode or being pushed off the spot
 * cancels too, and every way out gives the speeds back (also after a crash: they are written on the player first).
 * Items, blocks and entities cannot be used meanwhile.
 */
public final class HudModule implements StsvModule, Listener, CommandExecutor, TabCompleter {

    private record Line(String id, int order, Function<Player, Component> render) {}

    private record Flash(Component message, long until) {}

    private record Sent(HudLayout.Health health, Component message, long tick) {}

    public enum Mode { PACK, SCOREBOARD }

    /** The panel's letter subsets keep at most this many syllables (the repository's texts are about 800). */
    static final int MAX_PANEL_LETTERS = 1024;

    private final Map<String, Line> rows = new LinkedHashMap<>();
    private final Map<UUID, org.bukkit.scoreboard.Scoreboard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, List<Component>> shown = new ConcurrentHashMap<>();

    private static HudModule instance;

    private STSVPlugin plugin;
    private final Map<String, Line> lines = new LinkedHashMap<>();
    private final Map<UUID, Long> pausedUntil = new ConcurrentHashMap<>();
    private final Map<String, BossBar> bars = new LinkedHashMap<>();
    private BukkitTask ticker, barTicker, editTicker;
    private long flashMillis;
    private long tick, editClock;

    // pack mode
    private Mode mode = Mode.SCOREBOARD;
    private HudSheets sheets;
    private HudLayout layout;
    private boolean sidebarOn = true, healthOn = true;
    private BossBar.Color panelColour = BossBar.Color.WHITE;
    private int resendTicks = 20;
    private final Map<String, String> icons = new HashMap<>();
    private NamespacedKey toggleKey, positionKey, placeKey, frozenKey, jumpKey;
    private final Map<UUID, BossBar> panels = new ConcurrentHashMap<>();
    private final Set<UUID> packReady = ConcurrentHashMap.newKeySet();
    private final Set<UUID> packRefused = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Flash> flashes = new ConcurrentHashMap<>();
    private final Map<UUID, Sent> sent = new ConcurrentHashMap<>();
    private final Map<UUID, Editing> editing = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "hud";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        toggleKey = new NamespacedKey(plugin, "hud_sidebar");
        positionKey = new NamespacedKey(plugin, "hud_sidebar_right");
        placeKey = new NamespacedKey(plugin, "hud_sidebar_place");
        frozenKey = new NamespacedKey(plugin, "hud_edit_speeds");
        jumpKey = new NamespacedKey(plugin, "hud_edit");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // a plugin reload with players online: they already have the pack
        for (Player p : Bukkit.getOnlinePlayers()) {
            packReady.add(p.getUniqueId());
            thaw(p);
        }
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
                layout = new HudLayout(HudLayout.panelSheets(sheets, panelLetters()), settings(cfg));
            } catch (Exception e) {
                plugin.getLogger().warning("리소스팩 HUD 를 준비하지 못해 스코어보드 사이드바를 씁니다: " + e);
                wanted = Mode.SCOREBOARD;
            }
        }
        if (wanted != Mode.PACK || !sidebarOn) {
            for (Player p : Bukkit.getOnlinePlayers()) endEdit(p, HudEdit.Outcome.CANCELLED, "hud.edit-cancelled", true);
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
        if (editTicker != null) editTicker.cancel();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, every, every);
        barTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshBars, 2L, 2L);
        editTicker = Bukkit.getScheduler().runTaskTimer(plugin, this::editTick, 1L, 1L);
    }

    static HudLayout.Settings settings(org.bukkit.configuration.Configuration cfg) {
        HudLayout.Settings d = HudLayout.Settings.defaults();
        return new HudLayout.Settings(cfg.getInt("hud.sidebar.right", d.sidebarRight()), cfg.getInt("hud.sidebar.y", d.sidebarY()),
                cfg.getInt("hud.sidebar.max-width", d.sidebarMaxWidth()), cfg.getInt("hud.sidebar.max-rows", d.sidebarRows()),
                cfg.getBoolean("hud.health.armor", d.armor()));
    }

    /**
     * The syllables the panel's letters keep: the repository's (bundled), the messages' and those of every yml
     * file of the STSV plugins on this server (contents, configs: a job or event added on the server shows), at
     * most MAX_PANEL_LETTERS (the most used ones first).
     */
    private Set<Integer> panelLetters() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (String s : Lang.allText()) count(s, counts);
        java.io.File parent = plugin.getDataFolder().getParentFile();
        java.io.File[] dirs = parent == null ? null : parent.listFiles(f -> f.isDirectory() && f.getName().startsWith("STSV"));
        long budget = 64L << 20;
        if (dirs != null) {
            for (java.io.File dir : dirs) {
                try (var files = Files.walk(dir.toPath())) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (budget <= 0) break;
                        if (!f.toString().endsWith(".yml") || f.toString().contains("generated") || !Files.isRegularFile(f)) continue;
                        long size = Files.size(f);
                        if (size > (4L << 20)) continue;
                        budget -= size;
                        count(Files.readString(f, StandardCharsets.UTF_8), counts);
                    }
                } catch (IOException | java.io.UncheckedIOException e) {
                    plugin.getLogger().warning("사이드바 글자를 모으지 못한 폴더: " + dir + " (" + e.getMessage() + ")");
                }
            }
        }
        return pickLetters(sheets.panelLetters(), counts, MAX_PANEL_LETTERS, n ->
                plugin.getLogger().warning("사이드바 패널 글꼴에 한글 " + n + "자를 넣지 못했습니다 (자주 쓰는 " + MAX_PANEL_LETTERS + "자만): 패널에서 ? 로 보임"));
    }

    static void count(String text, Map<Integer, Integer> counts) {
        text.codePoints().filter(c -> c >= 0xAC00 && c <= 0xD7A3).forEach(c -> counts.merge(c, 1, Integer::sum));
    }

    /** The base letters and then the most counted others, at most max in all; dropped(n) is told how many did not fit. */
    static Set<Integer> pickLetters(Set<Integer> base, Map<Integer, Integer> counts, int max, java.util.function.IntConsumer dropped) {
        Set<Integer> out = new java.util.TreeSet<>(base);
        List<Map.Entry<Integer, Integer>> rest = new ArrayList<>();
        for (var e : counts.entrySet()) if (!out.contains(e.getKey())) rest.add(e);
        rest.sort(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        int left = 0;
        for (var e : rest) {
            if (out.size() < max) out.add(e.getKey());
            else left++;
        }
        if (left > 0) dropped.accept(left);
        return out;
    }

    @Override
    public void reload() {
        start();
    }

    @Override
    public void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) endEdit(p, HudEdit.Outcome.CANCELLED, null, true);
        if (ticker != null) ticker.cancel();
        if (barTicker != null) barTicker.cancel();
        if (editTicker != null) editTicker.cancel();
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
        Map<String, byte[]> files = HudPack.files(layout, sidebarOn ? panelColour.name() : null, healthOn);
        plugin.getLogger().info("HUD 글꼴: 클라이언트 메모리 약 " + (HudPack.clientMemory(layout) * 10 / (1 << 20)) / 10.0 + "MB");
        return files;
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
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.isDead()) {
            flashTracks.remove(p.getUniqueId());
            return null;
        }
        AttributeInstance maxAttr = p.getAttribute(Attribute.MAX_HEALTH);
        AttributeInstance armorAttr = p.getAttribute(Attribute.ARMOR);
        double max = maxAttr == null ? 20 : maxAttr.getValue();
        int armor = armorAttr == null ? 0 : (int) Math.floor(armorAttr.getValue());
        HudLayout.Tint tint = p.hasPotionEffect(PotionEffectType.POISON) ? HudLayout.Tint.POISON
                : p.hasPotionEffect(PotionEffectType.WITHER) ? HudLayout.Tint.WITHER
                : p.isFrozen() ? HudLayout.Tint.FROZEN : HudLayout.Tint.NORMAL;
        HudLayout.Health h = new HudLayout.Health(p.getHealth(), max, p.getAbsorptionAmount(), p.getFoodLevel(), p.getSaturation(),
                armor, tint, p.hasPotionEffect(PotionEffectType.HUNGER));
        if (p.getVehicle() instanceof LivingEntity mount) {
            AttributeInstance m = mount.getAttribute(Attribute.MAX_HEALTH);
            h = h.withMount(new HudLayout.Mount(mount.getHealth(), m == null ? mount.getHealth() : m.getValue()));
        }
        int air = p.getRemainingAir(), maxAir = p.getMaximumAir();
        if (maxAir > 0 && (air < maxAir || p.isUnderWater())) {
            // one step per pixel of the bar: the bar is sent again only when it changes on screen
            int step = Math.max(1, maxAir / HudLayout.INNER);
            h = h.withAir(new HudLayout.Air(air <= 0 ? 0 : Math.min(maxAir, -Math.floorDiv(-air, step) * step), maxAir));
        }
        FlashTrack t = flashTracks.get(p.getUniqueId());
        double total = h.total();
        if (t == null) t = new FlashTrack(total, null, 0, 0);
        else if (total <= t.last() - 0.5) t = new FlashTrack(total, HudLayout.Flash.LOST, t.flash() == HudLayout.Flash.LOST ? t.from() : t.last(), tick + FLASH_TICKS);
        else if (total >= t.last() + 2) t = new FlashTrack(total, HudLayout.Flash.GAIN, t.flash() == HudLayout.Flash.GAIN ? t.from() : t.last(), tick + FLASH_TICKS);
        else t = new FlashTrack(total, t.flash(), t.from(), t.until());
        if (t.flash() != null && tick >= t.until()) t = new FlashTrack(total, null, 0, 0);
        flashTracks.put(p.getUniqueId(), t);
        return t.flash() == null ? h : h.withFlash(t.flash(), t.from());
    }

    /** The health last seen and the flash running (until the tick given). */
    private record FlashTrack(double last, HudLayout.Flash flash, double from, long until) {}

    private static final int FLASH_TICKS = 12;
    private final Map<UUID, FlashTrack> flashTracks = new ConcurrentHashMap<>();

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

    /** The panel's rows: icons by row id, a gap becomes a divider above the next row. */
    private List<HudLayout.Row> panelRows(List<Entry> tidy) {
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
        return out;
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
        Editing ed = editing.get(p.getUniqueId());
        if (ed != null && packFor(p)) {
            clearSidebar(p);
            ed.rows = editRows(p);
            drawEdit(p, ed);
            return;
        }
        boolean want = sidebarOn && sidebarShown(p);
        if (packFor(p)) {
            clearSidebar(p);
            List<HudLayout.Row> out = want ? panelRows(entries(p)) : List.of();
            Component panel = out.isEmpty() ? null : layout.panel(out, placement(p), false);
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
        if (want) for (Entry e : entries(p)) tidy.add(e.text().replaceText(TAB_TO_SPACE));
        scoreboardSidebar(p, tidy);
    }

    /** Sidebar rows put a tab between label and value (the panel's columns); the scoreboard shows a space. */
    private static final net.kyori.adventure.text.TextReplacementConfig TAB_TO_SPACE =
            net.kyori.adventure.text.TextReplacementConfig.builder().matchLiteral("\t").replacement(" ").build();

    /** This player's panel place: their own (/사이드바 위치) or the server's (hud.sidebar.right, hud.sidebar.y). */
    HudLayout.Placement placement(Player p) {
        var pdc = p.getPersistentDataContainer();
        return HudEdit.stored(pdc.get(placeKey, PersistentDataType.INTEGER_ARRAY), pdc.get(positionKey, PersistentDataType.INTEGER),
                layout.defaults());
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
    // /사이드바 [켜기|끄기|위치 [기본|취소|저장]]
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        if (args.length >= 1 && (args[0].equals("위치") || args[0].equalsIgnoreCase("pos") || args[0].equalsIgnoreCase("position"))) {
            position(p, args.length >= 2 ? args[1].trim().toLowerCase(Locale.ROOT) : "");
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

    /** /사이드바 위치 [기본|취소|저장]: the edit mode, or back to the server's place. */
    private void position(Player p, String arg) {
        switch (arg) {
            case "" -> {
                if (editing.containsKey(p.getUniqueId())) endEdit(p, HudEdit.Outcome.SAVED, "hud.edit-saved", true);
                else startEdit(p);
            }
            case "저장", "save" -> {
                if (editing.containsKey(p.getUniqueId())) endEdit(p, HudEdit.Outcome.SAVED, "hud.edit-saved", true);
                else p.sendMessage(Lang.msg("hud.edit-not-editing"));
            }
            case "취소", "cancel" -> {
                if (editing.containsKey(p.getUniqueId())) endEdit(p, HudEdit.Outcome.CANCELLED, "hud.edit-cancelled", true);
                else p.sendMessage(Lang.msg("hud.edit-not-editing"));
            }
            case "기본", "reset", "default", "초기화" -> {
                endEdit(p, HudEdit.Outcome.CANCELLED, null, true);
                var pdc = p.getPersistentDataContainer();
                pdc.remove(placeKey);
                pdc.remove(positionKey);
                p.sendMessage(Lang.msg("hud.position-reset"));
                refreshSidebar(p);
            }
            default -> p.sendMessage(Lang.msg("hud.position-usage"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options;
        if (args.length == 1) options = List.of("켜기", "끄기", "위치");
        else if (args.length == 2 && args[0].equals("위치")) options = List.of("기본", "취소", "저장");
        else return List.of();
        String typed = args[args.length - 1];
        return options.stream().filter(s -> s.startsWith(typed)).toList();
    }

    // ------------------------------------------------------------------------------------------
    // Edit mode: move the panel with the mouse
    // ------------------------------------------------------------------------------------------

    /** A player moving their panel: the edit, where they stand, their view and speeds before, the rows shown. */
    private static final class Editing {
        final HudEdit edit;
        final Location spot;
        final float yaw, pitch, walk, fly;
        List<HudLayout.Row> rows;
        boolean turning;
        long guided;

        Editing(HudEdit edit, Location spot, float yaw, float pitch, float walk, float fly, List<HudLayout.Row> rows) {
            this.edit = edit;
            this.spot = spot;
            this.yaw = yaw;
            this.pitch = pitch;
            this.walk = walk;
            this.fly = fly;
            this.rows = rows;
        }
    }

    /** How far (blocks) a player may be pushed while editing before it cancels. */
    private static final double MAX_DRIFT = 1.5;

    public boolean editing(Player p) {
        return editing.containsKey(p.getUniqueId());
    }

    private void startEdit(Player p) {
        if (mode != Mode.PACK || layout == null) {
            p.sendMessage(Lang.msg("hud.position-scoreboard"));
            return;
        }
        if (!sidebarOn) {
            p.sendMessage(Lang.msg("hud.edit-off"));
            return;
        }
        if (!packFor(p)) {
            p.sendMessage(Lang.msg("hud.edit-unavailable"));
            return;
        }
        if (p.isInsideVehicle() || p.isGliding() || p.isSleeping() || p.isDead()) {
            p.sendMessage(Lang.msg("hud.edit-busy"));
            return;
        }
        List<HudLayout.Row> rows = editRows(p);
        HudLayout.Placement from = placement(p);
        from = layout.fit(from, layout.lines(rows, from.size()));
        Location at = p.getLocation();
        Editing e = new Editing(new HudEdit(from, at.getYaw(), 0f, editClock), at.clone(), at.getYaw(), at.getPitch(),
                p.getWalkSpeed(), p.getFlySpeed(), rows);
        freeze(p, e);
        editing.put(p.getUniqueId(), e);
        turn(p, e, at.getYaw(), 0f);   // look straight ahead: the whole up-down turn is free for moving
        p.sendMessage(Lang.msg("hud.edit-start"));
        clearSidebar(p);
        drawEdit(p, e);
        guide(p, e);
    }

    /** The rows while editing: the real ones (even if the player hid the sidebar), or a sample row. */
    private List<HudLayout.Row> editRows(Player p) {
        List<HudLayout.Row> out = panelRows(entries(p));
        return out.isEmpty() ? List.of(new HudLayout.Row("star", Lang.mm("hud.edit-sample"), false)) : out;
    }

    private HudEdit.Fit fit(Editing e) {
        return want -> layout.fit(want, layout.lines(e.rows, want.size()));
    }

    private void drawEdit(Player p, Editing e) {
        Component panel = layout.panel(e.rows, e.edit.current(), true);
        if (panel == null) hidePanel(p);
        else showPanel(p, panel);
    }

    private void guide(Player p, Editing e) {
        e.guided = editClock;
        flash(p, Lang.mm("hud.edit-guide", "size", e.edit.size().label, "left", e.edit.secondsLeft(editClock)), 2500);
    }

    private void editTick() {
        editClock++;
        for (UUID u : List.copyOf(editing.keySet())) {
            Player p = Bukkit.getPlayer(u);
            Editing e = editing.get(u);
            if (e == null) continue;
            if (p == null || !p.isOnline()) {
                editing.remove(u);
                continue;
            }
            try {
                if (e.edit.timedOut(editClock)) {
                    endEdit(p, HudEdit.Outcome.TIMED_OUT, "hud.edit-timeout", true);
                    continue;
                }
                Location now = p.getLocation();
                if (now.getWorld() != e.spot.getWorld() || now.distanceSquared(e.spot) > MAX_DRIFT * MAX_DRIFT) {
                    endEdit(p, HudEdit.Outcome.CANCELLED, "hud.edit-moved", false);
                    continue;
                }
                if (e.edit.look(now.getYaw(), now.getPitch(), fit(e))) drawEdit(p, e);
                if (editClock - e.guided >= 20) guide(p, e);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("사이드바 편집 오류: " + ex);
                endEdit(p, HudEdit.Outcome.CANCELLED, "hud.edit-cancelled", false);
            }
        }
    }

    /**
     * Ends a player's edit (if any): speeds and view back, the place kept when saved, the message (null: none).
     * view: turn the player back to where they looked before (not when something else moves them).
     */
    private void endEdit(Player p, HudEdit.Outcome how, String message, boolean view) {
        Editing e = editing.remove(p.getUniqueId());
        if (e == null || e.edit.end(how) == null) return;
        thaw(p, e.walk, e.fly);
        if (view && p.isOnline() && !p.isDead()) turn(p, e, e.yaw, e.pitch);
        if (how == HudEdit.Outcome.SAVED) {
            var pdc = p.getPersistentDataContainer();
            pdc.set(placeKey, PersistentDataType.INTEGER_ARRAY, HudEdit.store(e.edit.result()));
            pdc.remove(positionKey);
        }
        if (p.isOnline()) {
            clearFlash(p);
            if (message != null) p.sendMessage(Lang.msg(message, "size", e.edit.result().size().label));
            refreshSidebar(p);
        }
    }

    /**
     * Stands the player still: walk and fly speed 0 (the game then also leaves the field of view alone) and no
     * jumping. The speeds before are written on the player first, so a crash cannot leave them frozen.
     */
    private void freeze(Player p, Editing e) {
        p.getPersistentDataContainer().set(frozenKey, PersistentDataType.STRING, e.walk + "," + e.fly);
        p.setWalkSpeed(0f);
        p.setFlySpeed(0f);
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null && jump.getModifier(jumpKey) == null) {
            jump.addTransientModifier(new AttributeModifier(jumpKey, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }

    private void thaw(Player p, float walk, float fly) {
        p.setWalkSpeed(Math.max(-1f, Math.min(1f, walk)));
        p.setFlySpeed(Math.max(-1f, Math.min(1f, fly)));
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) jump.removeModifier(jumpKey);
        p.getPersistentDataContainer().remove(frozenKey);
    }

    /** A player who left (or the server stopped) mid-edit without being thawed: the speeds written on them. */
    private void thaw(Player p) {
        String saved = p.getPersistentDataContainer().get(frozenKey, PersistentDataType.STRING);
        if (saved == null || editing.containsKey(p.getUniqueId())) return;
        float walk = 0.2f, fly = 0.1f;
        try {
            String[] v = saved.split(",");
            walk = Float.parseFloat(v[0]);
            fly = Float.parseFloat(v[1]);
        } catch (RuntimeException ignored) {
            // the game's defaults
        }
        thaw(p, walk, fly);
    }

    /**
     * Turns the player's view: a teleport to where they stand (they are standing still), keeping their speed and
     * fall distance.
     */
    private void turn(Player p, Editing e, float yaw, float pitch) {
        Location l = p.getLocation();
        l.setYaw(yaw);
        l.setPitch(pitch);
        float fall = p.getFallDistance();
        e.turning = true;
        try {
            p.teleport(l, PlayerTeleportEvent.TeleportCause.PLUGIN, TeleportFlag.Relative.VELOCITY_X,
                    TeleportFlag.Relative.VELOCITY_Y, TeleportFlag.Relative.VELOCITY_Z);
        } finally {
            e.turning = false;
        }
        p.setFallDistance(fall);
    }

    private Editing editingOf(Player p) {
        return p == null ? null : editing.get(p.getUniqueId());
    }

    private void confirm(Player p, Editing e) {
        if (e.edit.canConfirm(editClock)) endEdit(p, HudEdit.Outcome.SAVED, "hud.edit-saved", true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditSneak(PlayerToggleSneakEvent ev) {
        if (ev.isSneaking() && editingOf(ev.getPlayer()) != null) {
            endEdit(ev.getPlayer(), HudEdit.Outcome.CANCELLED, "hud.edit-cancelled", true);
        }
    }

    /** The scroll wheel: the size (the held slot stays). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditWheel(PlayerItemHeldEvent ev) {
        Editing e = editingOf(ev.getPlayer());
        if (e == null) return;
        ev.setCancelled(true);
        if (e.edit.scroll(HudEdit.wheel(ev.getPreviousSlot(), ev.getNewSlot()), fit(e))) {
            drawEdit(ev.getPlayer(), e);
            guide(ev.getPlayer(), e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditSwap(PlayerSwapHandItemsEvent ev) {
        Editing e = editingOf(ev.getPlayer());
        if (e == null) return;
        ev.setCancelled(true);
        confirm(ev.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditInteract(PlayerInteractEvent ev) {
        Editing e = editingOf(ev.getPlayer());
        if (e == null) return;
        ev.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        ev.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        if (ev.getAction() == Action.LEFT_CLICK_AIR || ev.getAction() == Action.LEFT_CLICK_BLOCK) confirm(ev.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditInteractEntity(PlayerInteractEntityEvent ev) {
        if (editingOf(ev.getPlayer()) != null) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditInteractAtEntity(PlayerInteractAtEntityEvent ev) {
        if (editingOf(ev.getPlayer()) != null) ev.setCancelled(true);
    }

    /** Hitting something while editing: no hit, it saves (a left click). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditAttack(EntityDamageByEntityEvent ev) {
        Player attacker = ev.getDamager() instanceof Player p ? p
                : ev.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        Editing e = editingOf(attacker);
        if (e == null) return;
        ev.setCancelled(true);
        if (ev.getDamager() == attacker) confirm(attacker, e);
    }

    /** No protection while editing: a real hit cancels the edit. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEditHurt(EntityDamageEvent ev) {
        if (ev.getEntity() instanceof Player p && editingOf(p) != null && ev.getFinalDamage() > 0) {
            endEdit(p, HudEdit.Outcome.CANCELLED, "hud.edit-hurt", false);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditDrop(PlayerDropItemEvent ev) {
        if (editingOf(ev.getPlayer()) != null) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditBreak(BlockBreakEvent ev) {
        if (editingOf(ev.getPlayer()) != null) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditPlace(BlockPlaceEvent ev) {
        if (editingOf(ev.getPlayer()) != null) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditConsume(PlayerItemConsumeEvent ev) {
        if (editingOf(ev.getPlayer()) != null) ev.setCancelled(true);
    }

    /** Something else moves the player (a command, a portal, another plugin): the edit ends, the teleport goes on. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEditTeleport(PlayerTeleportEvent ev) {
        Editing e = editingOf(ev.getPlayer());
        if (e != null && !e.turning) endEdit(ev.getPlayer(), HudEdit.Outcome.CANCELLED, "hud.edit-cancelled", false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEditGameMode(PlayerGameModeChangeEvent ev) {
        if (editingOf(ev.getPlayer()) != null) endEdit(ev.getPlayer(), HudEdit.Outcome.CANCELLED, "hud.edit-cancelled", false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditDeath(PlayerDeathEvent ev) {
        if (editingOf(ev.getPlayer()) != null) endEdit(ev.getPlayer(), HudEdit.Outcome.CANCELLED, null, false);
    }

    @EventHandler
    public void onEditWorld(PlayerChangedWorldEvent ev) {
        if (editingOf(ev.getPlayer()) != null) endEdit(ev.getPlayer(), HudEdit.Outcome.CANCELLED, null, false);
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
        thaw(e.getPlayer());
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
                endEdit(e.getPlayer(), HudEdit.Outcome.CANCELLED, "hud.edit-cancelled", true);
                packReady.remove(u);
                packRefused.add(u);
                hidePanel(e.getPlayer());
                refreshSidebar(e.getPlayer());
            }
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        endEdit(e.getPlayer(), HudEdit.Outcome.CANCELLED, null, false);
        UUID u = e.getPlayer().getUniqueId();
        pausedUntil.remove(u);
        shown.remove(u);
        boards.remove(u);
        panels.remove(u);
        packReady.remove(u);
        packRefused.remove(u);
        flashes.remove(u);
        sent.remove(u);
        flashTracks.remove(u);
    }
}
