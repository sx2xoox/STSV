package dev.stsv.economy;

import dev.stsv.core.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /경제관리 &lt;하위 명령&gt;: every economy admin tool. Modules add their own sub commands with
 * {@link #add}; the words are Korean (shown in help and tab completion).
 */
public final class AdminCommand implements TabExecutor {

    public interface Handler {
        void run(CommandSender sender, String[] args);
    }

    public interface Completer {
        List<String> complete(CommandSender sender, String[] args);
    }

    private record Sub(String name, String usage, String help, Handler handler, Completer completer) {}

    private final Map<String, Sub> subs = new LinkedHashMap<>();

    /** args given to the handler start after the sub command word. */
    public void add(String name, String usage, String help, Handler handler, Completer completer) {
        subs.put(name, new Sub(name, usage, help, handler, completer));
    }

    public void add(String name, String usage, String help, Handler handler) {
        add(name, usage, help, handler, null);
    }

    public void remove(String name) {
        subs.remove(name);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        Sub s = args.length == 0 ? null : subs.get(args[0]);
        if (s == null) {
            sender.sendMessage(Text.msg("<gold>경제 서버 관리 명령어"));
            for (Sub x : subs.values()) {
                sender.sendMessage(Text.mm(" <white>/" + label + " " + x.name() + (x.usage().isEmpty() ? "" : " " + x.usage())
                        + " <dark_gray>- " + x.help()));
            }
            return true;
        }
        try {
            s.handler().run(sender, Arrays.copyOfRange(args, 1, args.length));
        } catch (RuntimeException e) {
            sender.sendMessage(Text.msg("<red>오류: " + e.getMessage()));
            EcoPlugin.get().getLogger().log(java.util.logging.Level.WARNING, "/경제관리 " + String.join(" ", args), e);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) return filter(new ArrayList<>(subs.keySet()), args[0]);
        Sub s = subs.get(args[0]);
        if (s == null || s.completer() == null) return List.of();
        return filter(s.completer().complete(sender, Arrays.copyOfRange(args, 1, args.length)), args[args.length - 1]);
    }

    public static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }

    public List<String> names() {
        return List.copyOf(subs.keySet());
    }
}
