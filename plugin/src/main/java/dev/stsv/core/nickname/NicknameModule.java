package dev.stsv.core.nickname;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Korean nicknames (/닉네임) and nickname colours (/닉네임 색). Account names cannot hold Hangul, so the server
 * shows a nickname instead: in chat (the account name on hover), join, quit, death and advancement messages, the
 * tab list, the name tag above the head (a text display, the game's tag hidden by a team), /프로필 and every
 * message and menu that names a player through {@link Names}. Commands that take a player accept the nickname
 * too ({@link Names#find}, {@link Names#online}).
 *
 * <p>Rules ({@link NickRules}, config nicknames): Hangul, English letters and digits, 2..8 letters, unique (any
 * case), never another player's account name, no banned word. The first nickname is free, a change costs money
 * (core money, /돈) unless no economy plugin runs; removing it is free. A colour (one colour or a gradient of two,
 * {@link NickColor}) is stored apart from the name; it must be bright enough to read on dark backgrounds.
 *
 * <p>Nicknames are kept in the database (table nickname) and in memory for everyone; they survive restarts and
 * every reset (/관리자 초기화 경제 or 기록).
 */
public final class NicknameModule implements StsvModule, Listener, TabExecutor {

    public static final String ADMIN_OWNER = "STSV 닉네임";
    private static final long CONFIRM_MILLIS = 15_000;
    /** words of the command itself, never nicknames */
    private static final java.util.Set<String> RESERVED = java.util.Set.of("해제", "색", "도움말", "정보", "목록", "설정", "기본", "확인", "강제");
    private static final Key SWATCH_MODEL = Key.key("stsv", "gui/swatch");

    /** A named colour of the menu (config nicknames.colors.palette / gradients). */
    public record Preset(String name, NickColor color) {}

    private STSVPlugin plugin;
    private NicknameStore store;
    private final NameIndex index = new NameIndex();
    /** account names as last seen (offline players with a nickname: hover, admin list) */
    private final Map<UUID, String> lastNames = new ConcurrentHashMap<>();
    /** player -> (what waits for its second entry, until when) */
    private final Map<UUID, Map.Entry<String, Long>> pending = new ConcurrentHashMap<>();

    private boolean enabled;
    private int minLength, maxLength;
    private long changeCost, cooldownMillis;
    private boolean freeWithoutEconomy, nameTag, tabRealName;
    private List<String> banned = List.of();
    private boolean colorsOn, firstColorFree;
    private long colorCost, gradientCost;
    private double minBrightness;
    private List<Preset> palette = List.of(), gradients = List.of();

    @Override
    public String id() {
        return "nicknames";
    }

    NameIndex index() {
        return index;
    }

    String lastName(UUID player) {
        return lastNames.get(player);
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        store = new NicknameStore(plugin.db());
        store.init();
        load();
        index.clear();
        store.loadAll().forEach(index::put);
        store.realNames().thenAccept(lastNames::putAll);
        Names.use(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.admin().add(ADMIN_OWNER, "닉네임", "<설정|해제|색|목록|정보> ...",
                "닉네임: 설정 <플레이어> <이름> [강제] (비용·대기 없음, 강제 = 다른 사람의 같은 닉네임을 빼앗음), 해제 <플레이어>, "
                        + "색 <플레이어> <#색|#색 #색|기본>, 목록, 정보 <플레이어>", this::admin, this::adminComplete);
        // teams of the main scoreboard are saved with the world: drop what a crash left, then the online players' again
        if (nameTag) NameTagTeams.removeAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            lastNames.put(p.getUniqueId(), p.getName());
            apply(p);
        }
        plugin.getLogger().info("닉네임 " + index.count() + "개");
    }

    @Override
    public void reload() {
        load();
        index.clear();
        store.loadAll().forEach(index::put);
        Bukkit.getOnlinePlayers().forEach(this::apply);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (plugin != null) plugin.admin().removeAll(ADMIN_OWNER);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.displayName(null);
            if (plugin.cosmetics() != null) plugin.cosmetics().nameTag(p, null);
        }
        if (nameTag) NameTagTeams.removeAll();
        Names.use(null);
    }

    private void load() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("nicknames");
        if (c == null) c = new org.bukkit.configuration.MemoryConfiguration();
        enabled = c.getBoolean("enabled", true);
        minLength = Math.max(1, c.getInt("min-length", 2));
        maxLength = Math.max(minLength, Math.min(16, c.getInt("max-length", 8)));
        changeCost = Math.max(0, c.getLong("change-cost", 5000));
        cooldownMillis = (long) (Math.max(0, c.getDouble("cooldown-hours", 0)) * 3_600_000L);
        freeWithoutEconomy = c.getBoolean("free-without-economy-plugin", true);
        banned = c.getStringList("banned-words");
        boolean tagBefore = nameTag;
        nameTag = c.getBoolean("name-tag", true);
        if (tagBefore && !nameTag) NameTagTeams.removeAll();
        tabRealName = c.getBoolean("tab-real-name", false);
        if (plugin.cosmetics() != null) plugin.cosmetics().nameTagHeight(c.getDouble("name-tag-height", 0.25));
        colorsOn = c.getBoolean("colors.enabled", true);
        colorCost = Math.max(0, c.getLong("colors.cost", 2000));
        gradientCost = Math.max(0, c.getLong("colors.gradient-cost", 5000));
        firstColorFree = c.getBoolean("colors.first-free", true);
        minBrightness = c.getDouble("colors.min-brightness", 0.35);
        List<String> problems = new ArrayList<>();
        palette = presets(dev.stsv.core.util.Cfg.listed(c.getConfigurationSection("colors.palette")), false, problems);
        gradients = presets(dev.stsv.core.util.Cfg.listed(c.getConfigurationSection("colors.gradients")), true, problems);
        problems.forEach(m -> plugin.getLogger().warning("config.yml nicknames.colors " + m));
    }

    /** palette: name -> "#rrggbb"; gradients: name -> ["#rrggbb", "#rrggbb"]. Broken or too dark entries are skipped. */
    private List<Preset> presets(ConfigurationSection s, boolean gradient, List<String> problems) {
        List<Preset> out = new ArrayList<>();
        if (s == null) return out;
        for (String name : s.getKeys(false)) {
            NickColor color;
            if (gradient) {
                List<String> two = s.getStringList(name);
                int a = two.size() == 2 ? NickColor.parseHex(two.get(0)) : -1, b = two.size() == 2 ? NickColor.parseHex(two.get(1)) : -1;
                color = a < 0 || b < 0 ? null : NickColor.gradient(a, b);
            } else {
                int a = NickColor.parseHex(s.getString(name));
                color = a < 0 ? null : NickColor.solid(a);
            }
            if (color == null) problems.add(name + ": " + (gradient ? "[\"#RRGGBB\", \"#RRGGBB\"]" : "\"#RRGGBB\""));
            else if (!color.readable(minBrightness)) problems.add(name + ": 너무 어두움 (min-brightness)");
            else out.add(new Preset(name, color));
        }
        return out;
    }

    public List<Preset> palette() {
        return palette;
    }

    public List<Preset> gradients() {
        return gradients;
    }

    /** Whether changes cost money here: always, unless the server has no economy plugin and that makes them free. */
    private boolean charged() {
        return !freeWithoutEconomy || Bukkit.getPluginManager().isPluginEnabled("STSV-Economy");
    }

    private EconomyModule eco() {
        return plugin.economy();
    }

    private NameIndex.Entry entry(UUID player) {
        return index.get(player).orElse(new NameIndex.Entry(player, null, null, 0, 0, 0));
    }

    // ------------------------------------------------------------------------------------------
    // How a player is shown
    // ------------------------------------------------------------------------------------------

    /** Puts the player's name everywhere it shows by itself: chat (display name), tab list, name tag. */
    public void apply(Player p) {
        UUID u = p.getUniqueId();
        boolean decorated = Names.decorated(u);
        p.displayName(decorated ? Names.component(u) : null);
        if (plugin.profiles() != null) plugin.profiles().refreshName(p);
        if (!nameTag) return;
        boolean ours = NameTagTeams.apply(p, decorated);
        if (plugin.cosmetics() != null) {
            plugin.cosmetics().nameTag(p, ours && decorated ? NickColor.component(index.color(u), Names.plain(u)) : null);
        }
    }

    /** The tab list name (without the title): the coloured nickname, optionally with the account name after it. */
    public Component tabName(Player p) {
        Component name = NickColor.component(index.color(p.getUniqueId()), Names.plain(p.getUniqueId()));
        if (tabRealName && index.nickname(p.getUniqueId()) != null) name = name.append(Lang.mm("nickname.tab-real", "name", p.getName()));
        return name;
    }

    /** The scoreboard of the HUD's scoreboard mode is new: give it the name tag teams. */
    public void decorate(org.bukkit.scoreboard.Scoreboard sb) {
        if (nameTag) NameTagTeams.copyTo(sb, o -> Names.decorated(o.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        lastNames.put(p.getUniqueId(), p.getName());
        // a new account named like someone's nickname: commands find the account first (it wins), tell the operators
        index.owner(p.getName()).filter(o -> !o.equals(p.getUniqueId())).ifPresent(o -> plugin.getLogger().warning(
                "[닉네임] 새로 들어온 계정 이름 " + p.getName() + " 이(가) " + Names.real(o) + " 의 닉네임과 같습니다. "
                        + "명령어는 계정 이름을 먼저 찾습니다. 필요하면 /관리자 닉네임 해제 " + Names.real(o)));
        // the others' teams on this player's scoreboard (if it is not the main one), then this player's
        decorate(p.getScoreboard());
        apply(p);
        Component msg = e.joinMessage();
        if (msg != null) e.joinMessage(replaceNames(msg, List.of(p)));
        // another server sharing the database may have changed it
        store.load(p.getUniqueId()).thenAccept(row -> Bukkit.getScheduler().runTask(plugin, () -> {
            NameIndex.Entry now = row.orElse(null), had = index.get(p.getUniqueId()).orElse(null);
            if (java.util.Objects.equals(now, had)) return;
            if (now == null) index.remove(p.getUniqueId());
            else index.put(now);
            if (p.isOnline()) apply(p);
        }));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent e) {
        Component msg = e.quitMessage();
        if (msg != null) e.quitMessage(replaceNames(msg, List.of(e.getPlayer())));
        pending.remove(e.getPlayer().getUniqueId());
        if (nameTag) NameTagTeams.remove(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Component msg = e.deathMessage();
        if (msg != null) e.deathMessage(replaceNames(msg, Bukkit.getOnlinePlayers()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        Component msg = e.message();
        if (msg != null) e.message(replaceNames(msg, List.of(e.getPlayer())));
    }

    /**
     * The game's own messages (join, quit, death, advancements) name players by account: those names become
     * nicknames (whole words only; the game's hover on the name stays and shows the account).
     */
    private Component replaceNames(Component msg, java.util.Collection<? extends Player> who) {
        for (Player p : who) {
            if (!Names.decorated(p.getUniqueId())) continue;
            msg = msg.replaceText(nameReplacement(p.getName(), NickColor.component(index.color(p.getUniqueId()), Names.plain(p.getUniqueId()))));
        }
        return msg;
    }

    /** Replaces a whole-word account name (letters, digits and _ are word characters) by the given component. */
    static TextReplacementConfig nameReplacement(String accountName, Component replacement) {
        return TextReplacementConfig.builder()
                .match(Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(accountName) + "(?![A-Za-z0-9_])"))
                .replacement(replacement).build();
    }

    // ------------------------------------------------------------------------------------------
    // /닉네임
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        if (!enabled) {
            p.sendMessage(Lang.msg("nickname.disabled"));
            return true;
        }
        if (args.length == 0 || args[0].equals("도움말")) {
            info(p);
            return true;
        }
        if (args[0].equals("해제")) {
            clearNickname(p);
            return true;
        }
        if (args[0].equals("색")) {
            if (!colorsOn) {
                p.sendMessage(Lang.msg("nickname.color-disabled"));
                return true;
            }
            if (args.length == 1) openColors(p);
            else typedColor(p, java.util.Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (args.length > 1) {
            p.sendMessage(Lang.msg("nickname.characters", "min", minLength, "max", maxLength));
            return true;
        }
        setNickname(p, args[0]);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) return filter(List.of("해제", "색", "도움말"), args[0]);
        if (args.length == 2 && args[0].equals("색")) {
            List<String> out = new ArrayList<>(List.of("기본"));
            for (Preset x : palette) out.add(x.name());
            for (Preset x : gradients) out.add(x.name());
            return filter(out, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String typed) {
        String low = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(low)).toList();
    }

    private void info(Player p) {
        NameIndex.Entry e = entry(p.getUniqueId());
        p.sendMessage(e.nickname() == null ? Lang.msg("nickname.none") : Lang.msg("nickname.current", "name", Names.mini(p.getUniqueId())));
        long next = NickRules.cost(e.selfSets() == 0, changeCost, charged());
        p.sendMessage(Lang.mm("nickname.usage", "min", minLength, "max", maxLength,
                "cost", next == 0 ? Lang.raw("nickname.free") : EconomyModule.format(next)));
        if (colorsOn) p.sendMessage(Lang.mm("nickname.usage-color", "cost", EconomyModule.format(colorCost), "gradient", EconomyModule.format(gradientCost)));
    }

    /** Big actions run only when the same one comes again within 15 seconds. */
    private boolean confirmed(Player p, String key) {
        long now = System.currentTimeMillis();
        Map.Entry<String, Long> last = pending.get(p.getUniqueId());
        if (last != null && last.getKey().equals(key) && last.getValue() > now) {
            pending.remove(p.getUniqueId());
            return true;
        }
        pending.put(p.getUniqueId(), Map.entry(key, now + CONFIRM_MILLIS));
        return false;
    }

    /** What is wrong with a nickname for this player, as a message key (null = fine). Same checks for the admin's set unless forced. */
    private String refusal(String nick, UUID self) {
        if (RESERVED.contains(nick)) return "nickname.reserved";
        NickRules.Problem pr = NickRules.check(nick, minLength, maxLength, banned);
        if (pr != null) return switch (pr) {
            case LENGTH -> "nickname.length";
            case CHARACTERS -> "nickname.characters";
            case BANNED -> "nickname.banned";
        };
        if (index.takenByOther(nick, self)) return "nickname.taken";
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.getUniqueId().equals(self) && o.getName().equalsIgnoreCase(nick)) return "nickname.taken-real";
        }
        return null;
    }

    private void setNickname(Player p, String typed) {
        UUID u = p.getUniqueId();
        String nick = NickRules.clean(typed);
        String why = refusal(nick, u);
        if (why != null) {
            p.sendMessage(Lang.msg(why, "min", minLength, "max", maxLength, "name", nick.replaceAll("[<>\\\\]", "")));
            return;
        }
        NameIndex.Entry cur = entry(u);
        if (nick.equals(cur.nickname())) {
            p.sendMessage(Lang.msg("nickname.same"));
            return;
        }
        long wait = NickRules.cooldownLeft(cur.changedAt(), cooldownMillis, System.currentTimeMillis());
        if (wait > 0) {
            p.sendMessage(Lang.msg("nickname.cooldown", "time", duration(wait)));
            return;
        }
        long cost = NickRules.cost(cur.selfSets() == 0, changeCost, charged());
        if (cost > 0 && !eco().has(u, cost)) {
            p.sendMessage(Lang.msg("nickname.not-enough", "cost", EconomyModule.format(cost), "balance", EconomyModule.format(eco().balance(u))));
            return;
        }
        if (cost > 0 && !confirmed(p, "nick " + nick)) {
            p.sendMessage(Lang.msg("nickname.confirm", "name", nick, "cost", EconomyModule.format(cost)));
            return;
        }
        store.isOthersRealName(nick, u).thenAccept(real -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (real) {
                p.sendMessage(Lang.msg("nickname.taken-real", "name", nick));
                return;
            }
            NameIndex.Entry now = entry(u);
            if (index.takenByOther(nick, u)) {
                p.sendMessage(Lang.msg("nickname.taken", "name", nick));
                return;
            }
            if (cost > 0 && !eco().withdraw(u, cost, "nickname")) {
                p.sendMessage(Lang.msg("nickname.not-enough", "cost", EconomyModule.format(cost), "balance", EconomyModule.format(eco().balance(u))));
                return;
            }
            NameIndex.Entry next = new NameIndex.Entry(u, nick, now.color(), System.currentTimeMillis(), now.selfSets() + 1, now.colorSets());
            save(p, now, next, cost, () -> {
                p.sendMessage(cost > 0 ? Lang.msg("nickname.set-paid", "name", Names.mini(u), "cost", EconomyModule.format(cost))
                        : Lang.msg("nickname.set", "name", Names.mini(u)));
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
            });
        }));
    }

    /**
     * Shows the change at once, writes it, and takes it back (money refunded) if the database refuses it (another
     * server gave the nickname away meanwhile).
     */
    private void save(Player p, NameIndex.Entry before, NameIndex.Entry next, long paid, Runnable done) {
        UUID u = p.getUniqueId();
        index.put(next);
        apply(p);
        store.save(next, false).whenComplete((saved, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err == null && saved.ok()) {
                done.run();
                return;
            }
            if (before.empty()) index.remove(u);
            else index.put(before);
            if (paid > 0) eco().deposit(u, paid, "nickname_refund");
            if (p.isOnline()) {
                apply(p);
                p.sendMessage(Lang.msg(err == null ? "nickname.taken" : "nickname.failed", "name", next.nickname() == null ? "" : next.nickname()));
            }
        }));
    }

    private void clearNickname(Player p) {
        UUID u = p.getUniqueId();
        NameIndex.Entry cur = entry(u);
        if (cur.nickname() == null) {
            p.sendMessage(Lang.msg("nickname.none"));
            return;
        }
        save(p, cur, cur.withNickname(null), 0, () -> p.sendMessage(Lang.msg("nickname.removed", "name", p.getName())));
    }

    private static String duration(long millis) {
        long minutes = Math.max(1, (millis + 59_999) / 60_000);
        return minutes >= 60 ? Lang.raw("time.hours-minutes", "h", minutes / 60, "m", minutes % 60) : Lang.raw("time.minutes", "m", minutes);
    }

    // ------------------------------------------------------------------------------------------
    // Colours
    // ------------------------------------------------------------------------------------------

    /**
     * A typed colour: "#RRGGBB", two of them (a gradient), or 기본/해제 (none). Null with a message when it cannot
     * be used.
     */
    private Optional<NickColor> parseColor(CommandSender to, String[] a) {
        if (a.length == 1 && (a[0].equals("기본") || a[0].equals("해제"))) return Optional.empty();
        if (a.length > 2) {
            to.sendMessage(Lang.msg("nickname.color-bad"));
            return null;
        }
        // a preset by name
        if (a.length == 1) {
            for (Preset x : palette) if (x.name().equals(a[0])) return Optional.of(x.color());
            for (Preset x : gradients) if (x.name().equals(a[0])) return Optional.of(x.color());
        }
        int c1 = NickColor.parseHex(a[0]), c2 = a.length == 2 ? NickColor.parseHex(a[1]) : c1;
        if (c1 < 0 || c2 < 0) {
            to.sendMessage(Lang.msg("nickname.color-bad"));
            return null;
        }
        NickColor c = a.length == 2 ? NickColor.gradient(c1, c2) : NickColor.solid(c1);
        if (!c.readable(minBrightness)) {
            to.sendMessage(Lang.msg("nickname.color-dark", "hex", NickColor.hex(NickColor.readable(c1, minBrightness) ? c2 : c1)));
            return null;
        }
        return Optional.of(c);
    }

    private void typedColor(Player p, String[] a) {
        Optional<NickColor> c = parseColor(p, a);
        if (c != null) chooseColor(p, c.orElse(null), null);
    }

    /** A colour chosen by command or menu (null = none). view: the menu to refresh, if any. */
    private void chooseColor(Player p, NickColor color, MenuView view) {
        UUID u = p.getUniqueId();
        NameIndex.Entry cur = entry(u);
        if (java.util.Objects.equals(color, cur.color())) {
            dev.stsv.core.hud.HudModule.flash(p, Lang.mm("nickname.color-same"));
            if (view == null) p.sendMessage(Lang.msg("nickname.color-same"));
            return;
        }
        long cost = NickRules.colorCost(color, cur.colorSets() == 0, firstColorFree, colorCost, gradientCost, charged());
        if (cost > 0 && !eco().has(u, cost)) {
            p.sendMessage(Lang.msg("nickname.not-enough", "cost", EconomyModule.format(cost), "balance", EconomyModule.format(eco().balance(u))));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            return;
        }
        if (cost > 0 && !confirmed(p, "color " + color.encode())) {
            p.sendMessage(Lang.msg(view == null ? "nickname.color-confirm" : "nickname.color-confirm-click",
                    "name", color.mini(Names.plain(u)), "cost", EconomyModule.format(cost)));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return;
        }
        if (cost > 0 && !eco().withdraw(u, cost, "nickname_color")) {
            p.sendMessage(Lang.msg("nickname.not-enough", "cost", EconomyModule.format(cost), "balance", EconomyModule.format(eco().balance(u))));
            return;
        }
        NameIndex.Entry next = new NameIndex.Entry(u, cur.nickname(), color, cur.changedAt(), cur.selfSets(),
                color == null ? cur.colorSets() : cur.colorSets() + 1);
        save(p, cur, next, cost, () -> {
            p.sendMessage(color == null ? Lang.msg("nickname.color-removed")
                    : cost > 0 ? Lang.msg("nickname.color-set-paid", "name", Names.mini(u), "cost", EconomyModule.format(cost))
                    : Lang.msg("nickname.color-set", "name", Names.mini(u)));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
            if (view != null && p.getOpenInventory().getTopInventory().getHolder(false) == view) view.refresh();
        });
    }

    public void openColors(Player p) {
        plugin.menus().open(p, "stsv:nick_colors", new ColorMenu());
    }

    /** A colour tile: the swatch model tinted with the colour (both halves the same for one colour). */
    @SuppressWarnings("UnstableApiUsage")
    static ItemStack swatch(NickColor c) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, SWATCH_MODEL);
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData()
                .addColor(Color.fromRGB(c.from())).addColor(Color.fromRGB(c.to())).build());
        it.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        return it;
    }

    private final class ColorMenu implements MenuHandler {
        List<Preset> shown = List.of();

        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            NickColor c = index.color(u);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", Names.mini(u));
            m.put("current", c == null ? Lang.raw("nickname.color-none") : presetName(c).orElse(c.encode().replace(">", " → ")));
            m.put("cost", EconomyModule.format(charged() ? colorCost : 0));
            m.put("gradient-cost", EconomyModule.format(charged() ? gradientCost : 0));
            return m;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            UUID u = v.player().getUniqueId();
            NameIndex.Entry cur = entry(u);
            String name = Names.plain(u);
            List<Preset> all = new ArrayList<>(palette);
            all.addAll(gradients);
            shown = all;
            List<ItemStack> out = new ArrayList<>();
            for (Preset x : all) {
                ItemStack it = swatch(x.color());
                boolean worn = x.color().equals(cur.color());
                long cost = NickRules.colorCost(x.color(), cur.colorSets() == 0, firstColorFree, colorCost, gradientCost, charged());
                it.editMeta(m -> {
                    m.itemName(Text.item(x.color().mini(name)));
                    List<Component> lore = new ArrayList<>(Lang.items(x.color().gradient() ? "nickname.tile-gradient" : "nickname.tile-color",
                            "label", x.name(), "hex", x.color().encode().replace(">", " → ")));
                    lore.add(worn ? Lang.item("nickname.tile-worn")
                            : Lang.item("nickname.tile-click", "cost", cost == 0 ? Lang.raw("nickname.free") : EconomyModule.format(cost)));
                    m.lore(lore);
                    m.setEnchantmentGlintOverride(worn);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= shown.size()) return;
            chooseColor(v.player(), shown.get(index).color(), v);
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (action.equals("nickcolor:clear")) chooseColor(v.player(), null, v);
        }
    }

    private Optional<String> presetName(NickColor c) {
        for (Preset x : palette) if (x.color().equals(c)) return Optional.of(x.name());
        for (Preset x : gradients) if (x.color().equals(c)) return Optional.of(x.name());
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 닉네임
    // ------------------------------------------------------------------------------------------

    private void admin(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0] : "";
        switch (sub) {
            case "설정" -> {
                if (a.length < 3) {
                    s.sendMessage(Text.msg("<red>/관리자 닉네임 설정 <플레이어> <이름> [강제]"));
                    return;
                }
                boolean force = a.length > 3 && a[3].equals("강제");
                String nick = NickRules.clean(a[2]);
                Names.find(a[1], found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Text.msg("<red>접속한 적 없는 플레이어: " + a[1]));
                        return;
                    }
                    adminSet(s, found.get(), nick, force);
                });
            }
            case "해제" -> {
                if (a.length < 2) {
                    s.sendMessage(Text.msg("<red>/관리자 닉네임 해제 <플레이어>"));
                    return;
                }
                Names.find(a[1], found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Text.msg("<red>접속한 적 없는 플레이어: " + a[1]));
                        return;
                    }
                    NameIndex.Entry cur = entry(found.get());
                    adminWrite(s, cur.withNickname(null), false, "<green>" + Names.real(found.get()) + " 닉네임 해제");
                });
            }
            case "색" -> {
                if (a.length < 3) {
                    s.sendMessage(Text.msg("<red>/관리자 닉네임 색 <플레이어> <#RRGGBB [#RRGGBB] | 이름 | 기본>"));
                    return;
                }
                Optional<NickColor> c = parseColor(s, java.util.Arrays.copyOfRange(a, 2, a.length));
                if (c == null) return;
                Names.find(a[1], found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Text.msg("<red>접속한 적 없는 플레이어: " + a[1]));
                        return;
                    }
                    NameIndex.Entry cur = entry(found.get());
                    adminWrite(s, cur.withColor(c.orElse(null)), false, "<green>" + Names.real(found.get()) + " 색: " + c.map(NickColor::encode).orElse("기본"));
                });
            }
            case "목록" -> {
                List<NameIndex.Entry> all = index.all().stream().filter(e -> e.nickname() != null)
                        .sorted(java.util.Comparator.comparing(NameIndex.Entry::nickname)).toList();
                s.sendMessage(Text.msg("<gray>닉네임 <white>" + all.size() + "</white>개"));
                for (NameIndex.Entry e : all) {
                    s.sendMessage(Text.mm(" <dark_gray>-</dark_gray> " + NickColor.mini(e.color(), e.nickname()) + " <gray>(" + Names.real(e.player())
                            + (Bukkit.getPlayer(e.player()) != null ? ", 접속 중" : "") + ")"));
                }
            }
            case "정보" -> {
                if (a.length < 2) {
                    s.sendMessage(Text.msg("<red>/관리자 닉네임 정보 <플레이어>"));
                    return;
                }
                Names.find(a[1], found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Text.msg("<red>접속한 적 없는 플레이어: " + a[1]));
                        return;
                    }
                    UUID u = found.get();
                    NameIndex.Entry e = entry(u);
                    var time = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());
                    s.sendMessage(Text.msg("<white>" + Names.real(u) + " <gray>(" + u + ")"));
                    s.sendMessage(Text.mm(" <gray>닉네임: " + (e.nickname() == null ? "<dark_gray>없음" : Names.mini(u))
                            + " <gray>· 색: <white>" + (e.color() == null ? "기본" : e.color().encode())));
                    s.sendMessage(Text.mm(" <gray>직접 바꾼 횟수: <white>" + e.selfSets() + "</white> · 색 바꾼 횟수: <white>" + e.colorSets()
                            + "</white> · 마지막 변경: <white>" + (e.changedAt() == 0 ? "-" : time.format(java.time.Instant.ofEpochMilli(e.changedAt())))));
                    Player online = Bukkit.getPlayer(u);
                    if (online != null) s.sendMessage(Text.mm(" <gray>이름표: " + (!nameTag ? "꺼짐 (config)" : NameTagTeams.foreign(online)
                            ? "<yellow>다른 플러그인의 팀에 있어 바닐라 이름표 그대로" : "디스플레이 " + plugin.cosmetics().nameTagDisplay(online))));
                });
            }
            default -> s.sendMessage(Text.msg("<red>/관리자 닉네임 <설정|해제|색|목록|정보>"));
        }
    }

    private void adminSet(CommandSender s, UUID target, String nick, boolean force) {
        if (force) {
            if (nick.isEmpty() || !nick.codePoints().allMatch(NickRules::allowed) || NickRules.length(nick) > 16) {
                s.sendMessage(Text.msg("<red>강제라도 한글·영문·숫자 1~16자만 가능합니다."));
                return;
            }
        } else {
            String why = refusal(nick, target);
            if (why != null) {
                s.sendMessage(Lang.msg(why, "min", minLength, "max", maxLength, "name", nick.replaceAll("[<>\\\\]", "")));
                s.sendMessage(Text.msg("<gray>규칙을 무시하려면 끝에 <white>강제"));
                return;
            }
        }
        store.isOthersRealName(nick, target).thenAccept(real -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (real && !force) {
                s.sendMessage(Text.msg("<red>다른 플레이어의 실제 이름입니다: " + nick + " <gray>(무시하려면 끝에 강제)"));
                return;
            }
            NameIndex.Entry cur = entry(target);
            adminWrite(s, cur.withNickname(nick), force, "<green>" + Names.real(target) + " 닉네임: <white>" + nick);
        }));
    }

    private void adminWrite(CommandSender s, NameIndex.Entry next, boolean force, String done) {
        store.save(next, force).whenComplete((saved, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                s.sendMessage(Text.msg("<red>저장 실패: " + err.getMessage()));
                return;
            }
            if (!saved.ok()) {
                s.sendMessage(Text.msg("<red>다른 플레이어가 쓰는 닉네임입니다. <gray>(빼앗으려면 끝에 강제)"));
                return;
            }
            if (next.empty()) index.remove(next.player());
            else index.put(next);
            Player p = Bukkit.getPlayer(next.player());
            if (p != null) {
                apply(p);
                p.sendMessage(Lang.msg("nickname.admin-changed", "name", Names.mini(next.player())));
            }
            if (saved.tookFrom() != null) {
                Player loser = Bukkit.getPlayer(saved.tookFrom());
                if (loser != null) {
                    apply(loser);
                    loser.sendMessage(Lang.msg("nickname.admin-taken"));
                }
                s.sendMessage(Text.msg("<yellow>" + Names.real(saved.tookFrom()) + " 의 같은 닉네임은 해제했습니다."));
            }
            s.sendMessage(Text.msg(done));
        }));
    }

    private List<String> adminComplete(CommandSender s, String[] a) {
        if (a.length == 1) return List.of("설정", "해제", "색", "목록", "정보");
        if (a.length == 2 && !a[0].equals("목록")) return Names.complete(s, a[1]);
        if (a.length == 4 && a[0].equals("설정")) return List.of("강제");
        if (a.length == 3 && a[0].equals("색")) return List.of("기본", "#");
        return List.of();
    }
}
