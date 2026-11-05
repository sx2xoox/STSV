package dev.stsv.core.command;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.profile.ProfileModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** /칭호: the title menu; /칭호 해제 takes the title off; /칭호 &lt;이름&gt; wears one of yours by its name. */
public final class TitleCommand implements TabExecutor {

    private final STSVPlugin plugin;

    public TitleCommand(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        ProfileModule profiles = plugin.profiles();
        if (args.length == 0) {
            profiles.openTitles(p);
            return true;
        }
        String q = String.join(" ", args);
        if (q.equals("해제") || q.equalsIgnoreCase("off")) {
            profiles.equipTitle(p, null);
            p.sendMessage(Lang.msg("profile.title-removed"));
            return true;
        }
        Optional<ProfileModule.Title> t = find(p, q);
        if (t.isEmpty()) {
            p.sendMessage(Lang.msg("profile.title-not-owned", "name", q));
            return true;
        }
        profiles.equipTitle(p, t.get().id());
        p.sendMessage(Lang.msg("profile.title-equipped", "title", t.get().name()));
        return true;
    }

    /** One of the player's own titles by its shown name ("[강태공]" or "강태공") or its id. */
    private Optional<ProfileModule.Title> find(Player p, String q) {
        String want = bare(q);
        var owned = plugin.profiles().ownedTitles(p.getUniqueId());
        for (ProfileModule.Title t : plugin.profiles().titles()) {
            if (!owned.contains(t.id())) continue;
            if (bare(Text.plain(t.name())).equals(want) || t.id().equalsIgnoreCase(q) || t.id().endsWith(":" + q.toLowerCase(Locale.ROOT))) return Optional.of(t);
        }
        return Optional.empty();
    }

    private static String bare(String s) {
        return s.replace("[", "").replace("]", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (args.length != 1 || !(sender instanceof Player p)) return List.of();
        List<String> out = new ArrayList<>(List.of("해제"));
        var owned = plugin.profiles().ownedTitles(p.getUniqueId());
        for (ProfileModule.Title t : plugin.profiles().titles()) if (owned.contains(t.id())) out.add(bare(Text.plain(t.name())));
        String a = args[0].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(a)).toList();
    }
}
