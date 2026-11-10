package dev.stsv.economy.util;

import dev.stsv.core.nickname.Names;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Player lookups and names, through the core's {@link Names}: what someone typed may be an account name or a
 * nickname (online or not), and players are named by nickname (in its colour) when they have one.
 */
public final class Players {

    private Players() {}

    /** Account name or nickname: online, else anyone who ever joined. Callback on the main thread. */
    public static void find(String name, Consumer<Optional<UUID>> then) {
        Names.find(name, then);
    }

    /** An online player by account name or nickname (else a unique start of either). */
    public static Player online(String name) {
        return Names.online(name).orElse(null);
    }

    /** Tab completion: online players' nicknames and account names. */
    public static List<String> complete(CommandSender viewer, String typed) {
        return Names.complete(viewer, typed);
    }

    /** The player's nickname (or account name) in their colour, as MiniMessage for messages; "서버" for none. */
    public static String name(UUID id) {
        if (id == null) return "서버";
        return Names.mini(id);
    }

    /** The account name (things that must not change with a nickname, e.g. a maker tag that items stack by). */
    public static String accountName(UUID id) {
        return id == null ? "서버" : Names.real(id);
    }
}
