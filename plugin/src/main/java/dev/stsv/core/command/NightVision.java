package dev.stsv.core.command;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * /야간투시: night vision on or off for yourself. The choice is kept on the player (survives logout, death and a milk bucket):
 * the effect is put back after anything clears it, until the player turns it off again.
 */
public final class NightVision implements CommandExecutor, Listener {

    private final STSVPlugin plugin;
    private final NamespacedKey key;
    /** Kept on the player (namespace = the plugin STSV). */
    private static final NamespacedKey KEY = new NamespacedKey("stsv", "night_vision");

    public NightVision(STSVPlugin plugin) {
        this.plugin = plugin;
        this.key = KEY;
    }

    private boolean on(Player p) {
        return p.getPersistentDataContainer().has(key);
    }

    /** Whether the player has night vision turned on (for the hub's button). */
    public static boolean isOn(Player p) {
        return p.getPersistentDataContainer().has(KEY);
    }

    private static void apply(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, true, false, true));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        if (on(p)) {
            p.getPersistentDataContainer().remove(key);
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            p.sendMessage(Lang.msg("night-vision.disabled"));
        } else {
            p.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            apply(p);
            p.sendMessage(Lang.msg("night-vision.enabled"));
        }
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (on(e.getPlayer())) apply(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (on(p)) Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && on(p)) apply(p); });
    }

    /** Milk, a totem, /effect clear or a beacon running out: put it back next tick while the player has it on. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCleared(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || !on(p)) return;
        if (e.getAction() != EntityPotionEffectEvent.Action.REMOVED && e.getAction() != EntityPotionEffectEvent.Action.CLEARED) return;
        if (e.getModifiedType() != PotionEffectType.NIGHT_VISION) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && on(p) && !p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) apply(p); });
    }
}
