package dev.stsv.core.command;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /프로필 [플레이어] (records and titles) and /명예의전당 [기간]. */
public final class ProfileCommand implements TabExecutor {

    private final STSVPlugin plugin;

    public ProfileCommand(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (cmd.getName().equals("명예의전당")) {
            plugin.profiles().showHall(sender, args.length > 0 ? args[0] : null);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        if (args.length == 0) {
            plugin.profiles().openProfile(p, p.getUniqueId(), Names.mini(p));
            return true;
        }
        // account name or nickname, online or not
        Names.find(args[0], found -> {
            if (found.isEmpty()) p.sendMessage(Lang.msg("profile.not-found"));
            else plugin.profiles().openProfile(p, found.get(), Names.mini(found.get()));
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (cmd.getName().equals("프로필") && args.length == 1) {
            return Names.complete(sender, args[0]);
        }
        return List.of();
    }
}
