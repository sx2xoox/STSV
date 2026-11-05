package dev.stsv.economy;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.Set;

/** Players cannot hurt each other unless config.yml pvp.always is on. */
final class PvpRule implements Listener {

    private static final Set<PotionEffectType> HARMFUL = Set.of(PotionEffectType.INSTANT_DAMAGE, PotionEffectType.POISON,
            PotionEffectType.WEAKNESS, PotionEffectType.SLOWNESS, PotionEffectType.WITHER, PotionEffectType.BLINDNESS);

    private final EcoPlugin eco;

    PvpRule(EcoPlugin eco) {
        this.eco = eco;
    }

    private boolean allowed() {
        return eco.getConfig().getBoolean("pvp.always");
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || allowed()) return;
        Player by = attacker(e.getDamager());
        if (by == null || by.equals(victim)) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Projectile pr) pr.remove();
        HudModule.flash(by, Lang.mm("pvp.disabled"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player by) || allowed()) return;
        if (e.getPotion().getEffects().stream().noneMatch(fx -> HARMFUL.contains(fx.getType()))) return;
        for (var hit : e.getAffectedEntities()) if (hit instanceof Player victim && !victim.equals(by)) e.setIntensity(victim, 0);
    }
}
