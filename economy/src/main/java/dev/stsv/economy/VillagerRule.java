package dev.stsv.economy;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * Villagers and wandering traders do not trade unless config.yml villagers.trading is on: their trades (emeralds for crops,
 * books, diamond gear) would bypass the shop's prices and limits. Curing, breeding and leading them still work.
 */
final class VillagerRule implements Listener {

    private final EcoPlugin eco;

    VillagerRule(EcoPlugin eco) {
        this.eco = eco;
    }

    private boolean allowed(Player p) {
        return eco.getConfig().getBoolean("villagers.trading") || p.hasPermission("stsv.economy.villager.trade");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof AbstractVillager) || allowed(e.getPlayer())) return;
        // a name tag, lead or golden apple in hand keeps its vanilla use (the trade screen is refused in onOpen anyway)
        var hand = e.getPlayer().getInventory().getItem(e.getHand()).getType();
        if (hand == org.bukkit.Material.NAME_TAG || hand == org.bukkit.Material.LEAD || hand == org.bukkit.Material.GOLDEN_APPLE) return;
        e.setCancelled(true);
        HudModule.flash(e.getPlayer(), Lang.mm("villager.trade-off"));
    }

    /** Any other way to the trade screen (another plugin, a command) is refused too. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() != InventoryType.MERCHANT || !(e.getInventory().getHolder() instanceof AbstractVillager)) return;
        if (!(e.getPlayer() instanceof Player p) || allowed(p)) return;
        e.setCancelled(true);
        HudModule.flash(p, Lang.mm("villager.trade-off"));
    }
}
