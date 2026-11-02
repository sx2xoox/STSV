package dev.stsv.core.command;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/** /wardrobe (/옷장): open the wardrobe in a wardrobe zone; sub commands are used by the chat buttons. */
public final class WardrobeCommand implements TabExecutor {

    private final STSVPlugin plugin;

    public WardrobeCommand(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Text.msg("<red>플레이어만 사용할 수 있습니다."));
            return true;
        }
        var cos = plugin.cosmetics();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!sub.isEmpty() && !cos.inWardrobe(p)) {
            p.sendMessage(Text.msg("<red>먼저 옷장 구역에서 /" + label + " 를 입력하세요."));
            return true;
        }
        switch (sub) {
            case "exit" -> cos.exitWardrobe(p, true);
            default -> cos.enterWardrobe(p); // inside: reopens the menu
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        return List.of();
    }
}
