package dev.stsv.core.vehicle;

import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * /탈것: an owner lets other players use all their vehicles (drive, pull a hand cart, open a bed; never pick one up).
 *
 * <ul>
 *   <li>{@code /탈것 권한} lists who is allowed.</li>
 *   <li>{@code /탈것 권한 <플레이어>} allows a player: account name or nickname, online or not (as every other command
 *       finds players: {@link Names#find}).</li>
 *   <li>{@code /탈것 권한취소 <플레이어>} takes it back (also by the name they had when allowed).</li>
 * </ul>
 * Kept in the database ({@link VehicleStore}, stsv_vehicle_grant).
 */
final class VehicleCommand implements TabExecutor {

    static final String GRANT = "권한", REVOKE = "권한취소";

    private final VehicleModule module;

    VehicleCommand(VehicleModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        String sub = args.length == 0 ? "" : args[0];
        switch (sub) {
            case GRANT, "permit", "allow" -> {
                if (args.length < 2) list(p);
                else grant(p, args[1]);
            }
            case REVOKE, "unpermit", "revoke" -> {
                if (args.length < 2) p.sendMessage(Lang.msg("vehicle.grant-usage"));
                else revoke(p, args[1]);
            }
            default -> p.sendMessage(Lang.msg("vehicle.grant-usage"));
        }
        return true;
    }

    private static String typed(String s) {
        return MiniMessage.miniMessage().escapeTags(s);
    }

    private void list(Player p) {
        Map<UUID, String> g = module.grantsOf(p.getUniqueId());
        if (g.isEmpty()) {
            p.sendMessage(Lang.msg("vehicle.grant-none"));
            return;
        }
        p.sendMessage(Lang.msg("vehicle.grant-list", "count", g.size()));
        for (UUID id : g.keySet()) p.sendMessage(Lang.mm("vehicle.grant-line", "player", Names.mini(id)));
    }

    private void grant(Player owner, String name) {
        Names.find(name, found -> {
            if (!owner.isOnline()) return;
            if (found.isEmpty()) {
                owner.sendMessage(Lang.msg("vehicle.grant-no-player", "player", typed(name)));
                return;
            }
            UUID target = found.get();
            if (!VehicleAccess.mayGrant(owner.getUniqueId(), target)) {
                owner.sendMessage(Lang.msg("vehicle.grant-self"));
                return;
            }
            Map<UUID, String> had = module.grantsOf(owner.getUniqueId());
            if (had.containsKey(target)) {
                owner.sendMessage(Lang.msg("vehicle.grant-already", "player", Names.mini(target)));
                return;
            }
            if (had.size() >= VehicleModule.MAX_GRANTS) {
                owner.sendMessage(Lang.msg("vehicle.grant-full", "max", VehicleModule.MAX_GRANTS));
                return;
            }
            module.grant(owner.getUniqueId(), target, Names.real(target), added -> {
                if (added == null) {
                    owner.sendMessage(Lang.msg("vehicle.error"));
                    return;
                }
                owner.sendMessage(Lang.msg("vehicle.granted", "player", Names.mini(target)));
                Player to = org.bukkit.Bukkit.getPlayer(target);
                if (to != null && Boolean.TRUE.equals(added)) to.sendMessage(Lang.msg("vehicle.granted-you", "owner", Names.mini(owner.getUniqueId())));
            });
        });
    }

    private void revoke(Player owner, String name) {
        Optional<UUID> listed = listed(owner.getUniqueId(), name);
        if (listed.isPresent()) {
            revoke(owner, listed.get());
            return;
        }
        Names.find(name, found -> {
            if (!owner.isOnline()) return;
            if (found.isEmpty() || !module.grantsOf(owner.getUniqueId()).containsKey(found.get())) {
                owner.sendMessage(Lang.msg("vehicle.grant-not-listed", "player", typed(name)));
                return;
            }
            revoke(owner, found.get());
        });
    }

    private void revoke(Player owner, UUID target) {
        module.revoke(owner.getUniqueId(), target, removed -> {
            if (removed == null) {
                owner.sendMessage(Lang.msg("vehicle.error"));
                return;
            }
            owner.sendMessage(Lang.msg("vehicle.revoked", "player", Names.mini(target)));
            Player to = org.bukkit.Bukkit.getPlayer(target);
            if (to != null && Boolean.TRUE.equals(removed)) to.sendMessage(Lang.msg("vehicle.revoked-you", "owner", Names.mini(owner.getUniqueId())));
        });
    }

    /** One of the owner's allowed players by the name they had when allowed, their account name or nickname (any case). */
    private Optional<UUID> listed(UUID owner, String name) {
        String n = name.strip().toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, String> e : module.grantsOf(owner).entrySet()) {
            if (e.getValue().toLowerCase(Locale.ROOT).equals(n) || Names.plain(e.getKey()).toLowerCase(Locale.ROOT).equals(n)
                    || Names.real(e.getKey()).toLowerCase(Locale.ROOT).equals(n)) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) return starting(List.of(GRANT, REVOKE), args[0]);
        if (args.length != 2 || !(sender instanceof Player p)) return List.of();
        return switch (args[0]) {
            case GRANT, "permit", "allow" -> {
                Map<UUID, String> had = module.grantsOf(p.getUniqueId());
                List<String> out = new ArrayList<>();
                for (String n : Names.completeOthers(sender, args[1])) {
                    if (had.values().stream().noneMatch(n::equalsIgnoreCase)) out.add(n);
                }
                yield out;
            }
            case REVOKE, "unpermit", "revoke" -> {
                List<String> names = new ArrayList<>();
                for (UUID id : module.grantsOf(p.getUniqueId()).keySet()) names.add(Names.plain(id));
                yield starting(names, args[1]);
            }
            default -> List.of();
        };
    }

    private static List<String> starting(List<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).toList();
    }
}
