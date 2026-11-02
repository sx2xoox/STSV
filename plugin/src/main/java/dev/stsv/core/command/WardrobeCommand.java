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
            sender.sendMessage(dev.stsv.core.util.Lang.msg("common.players-only"));
            return true;
        }
        var cos = plugin.cosmetics();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!sub.isEmpty() && !cos.inWardrobe(p)) {
            p.sendMessage(dev.stsv.core.util.Lang.msg("wardrobe.enter-first", "command", label));
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
