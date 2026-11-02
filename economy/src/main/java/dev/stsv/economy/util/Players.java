package dev.stsv.economy.util;

import dev.stsv.core.STSVPlugin;
import dev.stsv.economy.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Player lookups by name that also find people who are offline. */
public final class Players {

    private Players() {}

    /** Online by exact name, else anyone who ever joined. Callback on the main thread. */
    public static void find(String name, Consumer<Optional<UUID>> then) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            then.accept(Optional.of(online.getUniqueId()));
            return;
        }
        STSVPlugin.get().profiles().store().findByName(name)
                .thenAccept(found -> Bukkit.getScheduler().runTask(EcoPlugin.get(), () -> then.accept(found)));
    }

    public static String name(UUID id) {
        if (id == null) return "서버";
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }
}
