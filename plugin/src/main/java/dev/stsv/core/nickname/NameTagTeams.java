package dev.stsv.core.nickname;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Hides the game's name tag of players with a nickname (their nickname is drawn by a text display instead).
 *
 * <p>The game decides a name tag's visibility by the team the player is on in the viewer's scoreboard. Every
 * online player gets a team of their own ({@link #PREFIX} + account name in lower case), name tag hidden only
 * when they have a nickname. One team per player and not one shared team: team mates see each other's
 * invisibility and share collision rules, and the tab list sorts players by team name before account name, so
 * per-player teams named after the account keep the tab list in the order the game gives it (by account name).
 *
 * <p>Other plugins' teams win: a player already on a team that is not ours (on the main scoreboard) is left
 * alone and keeps the game's tag (with the account name), and no text display is drawn for them.
 *
 * <p>Teams go on the main scoreboard and on every other scoreboard an online player looks at (the scoreboard
 * sidebar of hud.mode: scoreboard gives each player their own).
 */
final class NameTagTeams {

    static final String PREFIX = "stsv_n_";

    static String teamName(String accountName) {
        return PREFIX + accountName.toLowerCase(Locale.ROOT);
    }

    private static Set<Scoreboard> boards() {
        Set<Scoreboard> out = new LinkedHashSet<>();
        out.add(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player o : Bukkit.getOnlinePlayers()) out.add(o.getScoreboard());
        return out;
    }

    /** True if another plugin has the player on a team of its own (we then leave the game's tag alone). */
    static boolean foreign(Player p) {
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(p.getName());
        return t != null && !t.getName().startsWith(PREFIX);
    }

    /** Puts the player on their team on every scoreboard; hide = the game's name tag hidden. False when foreign. */
    static boolean apply(Player p, boolean hide) {
        if (foreign(p)) return false;
        for (Scoreboard sb : boards()) apply(sb, p, hide);
        return true;
    }

    static void apply(Scoreboard sb, Player p, boolean hide) {
        Team other = sb.getEntryTeam(p.getName());
        if (other != null && !other.getName().startsWith(PREFIX)) return;
        String name = teamName(p.getName());
        Team t = sb.getTeam(name);
        if (t == null) {
            t = sb.registerNewTeam(name);
            t.setCanSeeFriendlyInvisibles(false);
        }
        t.setOption(Team.Option.NAME_TAG_VISIBILITY, hide ? Team.OptionStatus.NEVER : Team.OptionStatus.ALWAYS);
        if (!t.hasEntry(p.getName())) t.addEntry(p.getName());
    }

    /** Copies every online player's team onto a new scoreboard (the HUD's own scoreboards). */
    static void copyTo(Scoreboard sb, java.util.function.Predicate<Player> hidden) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (foreign(o)) continue;
            apply(sb, o, hidden.test(o));
        }
    }

    static void remove(Player p) {
        String name = teamName(p.getName());
        for (Scoreboard sb : boards()) {
            Team t = sb.getTeam(name);
            if (t != null) t.unregister();
        }
    }

    /** Every team of ours (plugin disabled). */
    static void removeAll() {
        for (Scoreboard sb : boards()) {
            for (Team t : Set.copyOf(sb.getTeams())) if (t.getName().startsWith(PREFIX)) t.unregister();
        }
    }
}
