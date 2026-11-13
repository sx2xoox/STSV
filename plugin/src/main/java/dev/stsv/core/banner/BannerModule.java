package dev.stsv.core.banner;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Title banners (칭호 배너), an admin-only test feature: an animated plate "칭호 ◆ 닉네임" in place of the title and
 * name in chat, the tab list and the name tag. Admins (op or {@link #PERMISSION}) pick one in /칭호's banner tab;
 * nobody else sees the tab, and a banner of someone who lost the permission is not shown.
 *
 * <p>The themes, their glyphs and the shader come from the plugin resources pack/title_banners (made by
 * tools/assetgen/title_banners.py: a new theme is a module there and a line in its THEMES). How they move:
 * {@link BannerPack.Animation} (config title-banners.animation).
 */
public final class BannerModule implements StsvModule, Listener {

    public static final String PERMISSION = "stsv.title.banner";

    private STSVPlugin plugin;
    private BannerStore store;
    private BannerCatalog catalog;
    private final Map<UUID, String> worn = new ConcurrentHashMap<>();
    private BukkitTask ticker;
    private volatile int frame;

    @Override
    public String id() {
        return "title-banners";
    }

    @Override
    public void enable(STSVPlugin plugin) throws Exception {
        this.plugin = plugin;
        catalog = BannerCatalog.get();
        store = new BannerStore(plugin.db());
        store.init();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
        startTicker();
    }

    @Override
    public void reload() {
        startTicker();
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------------------------------
    // Settings, who may
    // ------------------------------------------------------------------------------------------

    public boolean enabled() {
        return catalog != null && store != null && plugin.getConfig().getBoolean("title-banners.enabled");
    }

    public BannerPack.Animation animation() {
        return BannerPack.Animation.parse(plugin.getConfig().getString("title-banners.animation"));
    }

    /** Admins: op, or the permission. */
    public static boolean allowed(Player p) {
        return p.isOp() || p.hasPermission(PERMISSION);
    }

    /** May open the banner tab (the feature is on and the player is an admin). */
    public boolean usable(Player p) {
        return enabled() && allowed(p);
    }

    /** The pack files (none when the feature is off). */
    public Map<String, byte[]> packFiles() {
        return enabled() ? BannerPack.files(catalog, animation()) : Map.of();
    }

    public BannerCatalog catalog() {
        return catalog;
    }

    public List<BannerCatalog.Theme> themes() {
        return List.copyOf(catalog.themes().values());
    }

    // ------------------------------------------------------------------------------------------
    // Wearing
    // ------------------------------------------------------------------------------------------

    /** The banner a player has on (whether or not it is shown now). */
    public Optional<String> wornId(UUID player) {
        return Optional.ofNullable(worn.get(player));
    }

    /** The banner shown for a player: worn, a known theme, the feature on, and the player still an admin. */
    public Optional<BannerCatalog.Theme> shown(Player p) {
        String id = worn.get(p.getUniqueId());
        if (id == null || !usable(p)) return Optional.empty();
        return Optional.ofNullable(catalog.theme(id));
    }

    /** Puts a banner on (null: off), keeps it, and shows it at once. Unknown themes are refused. */
    public boolean wear(Player p, String themeId) {
        if (themeId != null && catalog.theme(themeId) == null) return false;
        if (themeId == null) worn.remove(p.getUniqueId());
        else worn.put(p.getUniqueId(), themeId);
        store.wear(p.getUniqueId(), themeId, System.currentTimeMillis());
        refresh(p);
        return true;
    }

    private void load(Player p) {
        UUID u = p.getUniqueId();
        store.worn(u).thenAccept(id -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (id.isPresent()) worn.put(u, id.get());
            else worn.remove(u);
            if (id.isPresent()) refresh(p);
        }));
    }

    /** Tab list and name tag again (the title or nickname changed, or the banner). */
    public void refresh(Player p) {
        if (plugin.nicknames() != null) plugin.nicknames().apply(p);
        else if (plugin.profiles() != null) plugin.profiles().refreshName(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        worn.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // What is drawn
    // ------------------------------------------------------------------------------------------

    /** The frame to draw: every frame for the shader, the server's current one, or frame 0. */
    int frame() {
        return switch (animation()) {
            case SHADER -> BannerText.ALL_FRAMES;
            case SERVER -> frame;
            case STATIC -> 0;
        };
    }

    /** The banner's words: the worn title without its brackets, and the nickname (or account name). */
    public BannerLayout layout(Player p, BannerCatalog.Theme t) {
        String title = plugin.profiles() == null ? "" : plugin.profiles().wornTitleName(p.getUniqueId()).map(Text::plain).map(BannerModule::bare).orElse("");
        return BannerLayout.of(catalog, t, title, dev.stsv.core.nickname.Names.plain(p.getUniqueId()));
    }

    /** "[단골]" → "단골": the plate is the frame. */
    static String bare(String title) {
        String s = title.strip();
        if (s.startsWith("[") && s.endsWith("]") && s.length() > 2) s = s.substring(1, s.length() - 1).strip();
        return s;
    }

    /** The chat name (title and name in one), null when no banner is shown or chat is off for banners. */
    public Component chatName(Player p) {
        if (!plugin.getConfig().getBoolean("title-banners.chat")) return null;
        return shown(p).map(t -> inChat(p, t)).orElse(null);
    }

    /**
     * A theme as chat shows it, and the tab list and the name tag with it: its chat banner (one line, letters as high
     * as ordinary text), else the small size.
     */
    private Component inChat(Player p, BannerCatalog.Theme t) {
        if (t.chat() == null) return BannerText.component(catalog, layout(p, t), BannerText.Size.MINI, frame());
        int f = frame();
        // a banner a gust runs along, animated by the server: the parts cannot be played one after the other, so it
        // only idles (its first four frames)
        if (f >= 0 && t.chat().flow() > 0) f %= 4;
        return BannerText.component(catalog, layout(p, t.chat()), BannerText.Size.CHAT, f);
    }

    /** The tab list name, null when none. */
    public Component tabName(Player p) {
        if (!plugin.getConfig().getBoolean("title-banners.tab")) return null;
        return shown(p).map(t -> inChat(p, t)).orElse(null);
    }

    /**
     * The name tag, its size and lift; null when none. A theme with a chat banner shows that one over the head too
     * (the same banner everywhere); else the full banner.
     */
    public Tag tag(Player p) {
        if (!plugin.getConfig().getBoolean("title-banners.name-tag")) return null;
        Optional<BannerCatalog.Theme> t = shown(p);
        if (t.isEmpty()) return null;
        float size = (float) plugin.getConfig().getDouble("title-banners.name-tag-size");
        if (t.get().chat() != null) {
            // a chat banner's pixel is half a text pixel: twice the size shows a picture pixel as large as the full
            // banner's (and its plate as high as an ordinary name tag's line at the default 0.5); its plate ends one
            // row under the baseline
            return new Tag(inChat(p, t.get()), size * 2, 1 / 40f);
        }
        // the plate reaches (height - ascent) px below the baseline; a text display puts its line's bottom at its origin
        float lift = (t.get().height() - t.get().mid() - 4) / 40f;
        return new Tag(BannerText.component(catalog, layout(p, t.get()), BannerText.Size.BIG, frame()), size, lift);
    }

    public record Tag(Component text, float size, float lift) {}

    /** A theme with the player's own words, as chat shows it (the menu's preview). */
    public Component preview(Player p, BannerCatalog.Theme t) {
        return inChat(p, t);
    }

    // ------------------------------------------------------------------------------------------
    // Server-side animation (no shader): the name tags and tab list names of wearers, frame by frame
    // ------------------------------------------------------------------------------------------

    private void startTicker() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        if (!enabled() || animation() != BannerPack.Animation.SERVER) return;
        long every = Math.max(1, plugin.getConfig().getInt("title-banners.server-frame-ticks"));
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (worn.isEmpty()) return;
            frame = (frame + 1) % catalog.frames();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!worn.containsKey(p.getUniqueId()) || shown(p).isEmpty()) continue;
                if (plugin.nicknames() != null) plugin.nicknames().bannerFrame(p);
                Component tab = tabName(p);
                if (tab != null) p.playerListName(tab);
            }
        }, every, every);
    }
}
