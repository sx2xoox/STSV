package dev.stsv.core.command;

import dev.stsv.core.util.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /자랑: shows the item in the main hand to everyone in chat. The item's name in [brackets] carries the item itself
 * as its hover, so pointing at it shows the item's tooltip (name, lore, enchantments) as in an inventory. Once every
 * {@link #COOLDOWN_MS} a player, so chat is not flooded.
 */
public final class Brag implements CommandExecutor {

    static final long COOLDOWN_MS = 30_000;

    private final Map<UUID, Long> last = new HashMap<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir()) {
            p.sendMessage(Lang.msg("brag.empty"));
            return true;
        }
        long now = System.currentTimeMillis();
        Long before = last.get(p.getUniqueId());
        if (before != null && now - before < COOLDOWN_MS && !p.hasPermission("stsv.admin")) {
            p.sendMessage(Lang.msg("brag.wait", "seconds", (COOLDOWN_MS - (now - before) + 999) / 1000));
            return true;
        }
        last.put(p.getUniqueId(), now);
        Component name = it.displayName();   // [이름], hover = the item's own tooltip
        if (it.getAmount() > 1) name = name.append(Component.text(" ×" + it.getAmount()));
        Component item = name;
        Component line = Lang.mm("brag.line", "player", p.getName())
                .replaceText(TextReplacementConfig.builder().matchLiteral("{item}").replacement(item).build());
        Bukkit.broadcast(line);
        return true;
    }
}
