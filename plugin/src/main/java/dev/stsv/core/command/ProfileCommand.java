package dev.stsv.core.command;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
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
            plugin.profiles().openProfile(p, p.getUniqueId(), p.getName());
            return true;
        }
        Player online = Bukkit.getPlayerExact(args[0]);
        if (online != null) {
            plugin.profiles().openProfile(p, online.getUniqueId(), online.getName());
            return true;
        }
        plugin.profiles().store().findByName(args[0]).thenAccept(found -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (found.isEmpty()) p.sendMessage(Lang.msg("profile.not-found"));
            else plugin.profiles().openProfile(p, found.get(), args[0]);
        }));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (cmd.getName().equals("프로필") && args.length == 1) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
