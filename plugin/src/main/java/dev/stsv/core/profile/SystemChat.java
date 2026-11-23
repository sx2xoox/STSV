package dev.stsv.core.profile;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.nickname.Names;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Chat and whispers as plain server (system) messages instead of signed player chat (config profiles.system-chat).
 * Then real lines and lines the server makes up (economy's hidden secret) travel the same way and look the same in
 * the client; Mojang chat reports stop working on this server. Off: signed chat as before.
 */
final class SystemChat implements Listener {

    /** The vanilla whisper commands (with or without "minecraft:"). */
    static final Set<String> WHISPER_COMMANDS = Set.of("msg", "tell", "w");

    /** "/w Name hello there" → target "Name", message "hello there". */
    record Whisper(String command, String target, String message) {}

    private final STSVPlugin plugin;

    SystemChat(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean on() {
        return plugin.getConfig().getBoolean("profiles.system-chat");
    }

    // ------------------------------------------------------------------------------------------
    // Public chat
    // ------------------------------------------------------------------------------------------

    /**
     * Each player viewer gets the line as a system message, rendered exactly as the event's renderer would, and
     * leaves the viewers so Paper does not also send the signed message. The console (and any other non-player
     * audience) stays, so the log line is unchanged; the event is not cancelled, so MONITOR listeners still see it.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!on()) return;
        Set<Audience> viewers = e.viewers();
        List<Player> players = new ArrayList<>();
        for (Audience a : viewers) if (a instanceof Player p) players.add(p);
        if (players.isEmpty()) return;
        try {
            viewers.removeAll(players);
        } catch (UnsupportedOperationException ex) {
            return;   // a set we may not change: leave the signed chat as it is rather than send every line twice
        }
        ChatRenderer renderer = e.renderer();
        Player source = e.getPlayer();
        Component name = source.displayName();
        Component message = e.message();
        for (Player viewer : players) viewer.sendMessage(renderer.render(source, name, message, viewer));
    }

    // ------------------------------------------------------------------------------------------
    // Whispers
    // ------------------------------------------------------------------------------------------

    /**
     * /msg, /tell, /w (and minecraft:...) to one plain player name, as system messages. HIGH and ignoreCancelled:
     * whoever cancelled before (economy's fake player at NORMAL) wins. Anything vanilla answers differently
     * (selectors, quotes, no message, nobody found) is left to vanilla.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!on()) return;
        Optional<Whisper> w = parse(e.getMessage());
        if (w.isEmpty()) return;
        Player sender = e.getPlayer();
        if (!vanilla(w.get().command()) || !sender.hasPermission("minecraft.command.msg")) return;
        Player target = target(w.get().target());
        if (target == null || !sender.canSee(target)) return;
        e.setCancelled(true);
        String message = w.get().message();
        sender.sendMessage(ChatLook.outgoing(ChatLook.name(target.displayName(), target.getName(), target.getUniqueId()), message));
        target.sendMessage(ChatLook.incoming(ChatLook.name(sender.displayName(), sender.getName(), sender.getUniqueId()), message));
    }

    /** The command is still the game's own (not replaced by another plugin's /msg). */
    private static boolean vanilla(String command) {
        Command c = Bukkit.getCommandMap().getCommand(command);
        return !(c instanceof PluginIdentifiableCommand);
    }

    /** An online player by account name (any case), else by core nickname (exact, any case). */
    private static Player target(String typed) {
        Player p = Bukkit.getPlayerExact(typed);
        if (p != null) return p;
        return Names.byNickname(typed).map(Bukkit::getPlayer).orElse(null);
    }

    /**
     * "/&lt;msg|tell|w|minecraft:...&gt; &lt;one plain name&gt; &lt;message&gt;"; empty for anything else: another
     * command, a selector (@...), a quoted name, no target, an empty message. The command comes back without
     * "minecraft:", in lower case. Runs of spaces between the words are fine; the message keeps its inner spaces.
     */
    static Optional<Whisper> parse(String line) {
        if (line == null || !line.startsWith("/")) return Optional.empty();
        String rest = line.substring(1).stripLeading();
        int sp = firstSpace(rest);
        if (sp < 0) return Optional.empty();
        String cmd = rest.substring(0, sp).toLowerCase(Locale.ROOT);
        if (cmd.startsWith("minecraft:")) cmd = cmd.substring("minecraft:".length());
        if (!WHISPER_COMMANDS.contains(cmd)) return Optional.empty();
        rest = rest.substring(sp).stripLeading();
        sp = firstSpace(rest);
        if (sp <= 0) return Optional.empty();
        String target = rest.substring(0, sp);
        if (target.startsWith("@") || target.startsWith("\"") || target.startsWith("'")) return Optional.empty();
        String message = rest.substring(sp).strip();
        if (message.isEmpty()) return Optional.empty();
        return Optional.of(new Whisper(cmd, target, message));
    }

    private static int firstSpace(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }
}
