package dev.stsv.core.nickname;

import dev.stsv.core.STSVPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * How players are named to other players, for every module and content plugin: the nickname (/닉네임) in its
 * colour when there is one, else the account name. And how a typed name finds a player: a real name or a
 * nickname, online or not ({@link #find}), with tab completion of both ({@link #complete}).
 *
 * <ul>
 *   <li>{@link #mini}: MiniMessage, for any message, item name or lore ({player} placeholders). The colour
 *       overrides the text's own colour around it. Never inside a tag's argument such as a click command: use
 *       {@link #real} there.</li>
 *   <li>{@link #plain}: the name only, no colour (titles of menus that measure their text, logs for players).</li>
 *   <li>{@link #component}: coloured, the account name on hover (chat).</li>
 * </ul>
 * Without the nickname module (tests, a plugin loaded before the core) everything falls back to account names.
 */
public final class Names {

    private Names() {}

    private static volatile NicknameModule module;

    static void use(NicknameModule m) {
        module = m;
    }

    private static NameIndex index() {
        NicknameModule m = module;
        return m == null ? null : m.index();
    }

    /** The account name (online, else as last seen, else the server's cache), "?" if never known. */
    public static String real(UUID player) {
        if (player == null) return "?";
        Player p = Bukkit.getPlayer(player);
        if (p != null) return p.getName();
        NicknameModule m = module;
        String n = m == null ? null : m.lastName(player);
        if (n != null) return n;
        OfflinePlayer op = Bukkit.getOfflinePlayer(player);
        return op.getName() == null ? "?" : op.getName();
    }

    /** The nickname, if the player has one. */
    public static Optional<String> nickname(UUID player) {
        NameIndex i = index();
        return i == null || player == null ? Optional.empty() : Optional.ofNullable(i.nickname(player));
    }

    public static NickColor color(UUID player) {
        NameIndex i = index();
        return i == null || player == null ? null : i.color(player);
    }

    /** Nickname or account name, without colour. */
    public static String plain(UUID player) {
        return nickname(player).orElseGet(() -> real(player));
    }

    public static String plain(OfflinePlayer p) {
        return p == null ? "?" : nickname(p.getUniqueId()).orElseGet(() -> p.getName() == null ? real(p.getUniqueId()) : p.getName());
    }

    /** Nickname or account name in the player's colour, as MiniMessage. */
    public static String mini(UUID player) {
        return NickColor.mini(color(player), plain(player));
    }

    public static String mini(OfflinePlayer p) {
        return p == null ? "?" : NickColor.mini(color(p.getUniqueId()), plain(p));
    }

    /** Coloured; when it is a nickname, the account name shows on hover. */
    public static Component component(UUID player) {
        String nick = nickname(player).orElse(null);
        Component c = NickColor.component(color(player), nick == null ? real(player) : nick);
        if (nick == null) return c;
        return c.hoverEvent(HoverEvent.showText(dev.stsv.core.util.Lang.mm("nickname.hover", "name", real(player))));
    }

    /** Whether a player has a nickname or colour (their name is shown differently from the account name). */
    public static boolean decorated(UUID player) {
        NameIndex i = index();
        return i != null && i.get(player).map(e -> e.nickname() != null || e.color() != null).orElse(false);
    }

    // ------------------------------------------------------------------------------------------
    // Typed names
    // ------------------------------------------------------------------------------------------

    private static Map<UUID, String> onlineNames(CommandSender viewer) {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (viewer instanceof Player v && !v.canSee(o)) continue;
            out.put(o.getUniqueId(), o.getName());
        }
        return out;
    }

    /** Who has exactly this nickname (any case), online or not. */
    public static Optional<UUID> byNickname(String typed) {
        NameIndex i = index();
        return i == null || typed == null ? Optional.empty() : i.owner(typed);
    }

    /** An online player by account name or nickname (exact, any case), else a unique start of either. */
    public static Optional<Player> online(String typed) {
        if (typed == null || typed.isBlank()) return Optional.empty();
        Player exact = Bukkit.getPlayerExact(typed.strip());
        if (exact != null) return Optional.of(exact);
        NameIndex i = index();
        if (i == null) return Optional.ofNullable(Bukkit.getPlayer(typed.strip()));
        return i.resolve(typed, onlineNames(null)).map(Bukkit::getPlayer);
    }

    /**
     * Anyone by account name or nickname: online players first, then nicknames (online or not), then anyone who
     * ever joined by account name. The callback runs on the main thread.
     */
    public static void find(String typed, Consumer<Optional<UUID>> then) {
        if (typed == null || typed.isBlank()) {
            then.accept(Optional.empty());
            return;
        }
        Player exact = Bukkit.getPlayerExact(typed.strip());
        if (exact != null) {
            then.accept(Optional.of(exact.getUniqueId()));
            return;
        }
        NameIndex i = index();
        if (i != null) {
            Optional<UUID> nick = i.owner(typed);
            if (nick.isPresent()) {
                then.accept(nick);
                return;
            }
        }
        STSVPlugin core = STSVPlugin.get();
        core.profiles().store().findByName(typed.strip()).thenAccept(found -> Bukkit.getScheduler().runTask(core, () -> {
            if (found.isPresent()) {
                then.accept(found);
                return;
            }
            // a unique start of an online player's name or nickname
            then.accept(online(typed).map(Player::getUniqueId));
        }));
    }

    /** Tab completion: the nicknames and account names of the online players (that the viewer can see) starting with what was typed. */
    public static List<String> complete(CommandSender viewer, String typed) {
        NameIndex i = index();
        Map<UUID, String> on = onlineNames(viewer);
        if (i == null) {
            String low = typed == null ? "" : typed.toLowerCase(java.util.Locale.ROOT);
            return on.values().stream().filter(n -> n.toLowerCase(java.util.Locale.ROOT).startsWith(low)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        return i.complete(typed, on);
    }

    /** {@link #complete} without the viewer themselves. */
    public static List<String> completeOthers(CommandSender viewer, String typed) {
        if (!(viewer instanceof Player self)) return complete(viewer, typed);
        String nick = nickname(self.getUniqueId()).orElse(null);
        return complete(viewer, typed).stream().filter(n -> !n.equals(self.getName()) && !n.equals(nick)).toList();
    }
}
