package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Trails and kill effects: particles (and a sound) shown to the given players only, so whoever cannot
 * see the wearer does not see their effects either.
 */
final class CosmeticEffects {

    private CosmeticEffects() {}

    static void play(List<Player> viewers, Location at, CustomItem.Fx fx, long t, boolean kill) {
        play(viewers, at, fx, t, kill, false);
    }

    /**
     * @param kill    a kill effect: a burst (and the lightning, if any) rather than a trail mark
     * @param preview the wardrobe mannequin: lightning is shown as sparks and thunder to the viewers only
     */
    static void play(List<Player> viewers, Location at, CustomItem.Fx fx, long t, boolean kill, boolean preview) {
        if (viewers.isEmpty()) return;
        if (fx.lightning()) {
            if (preview) {
                for (Player v : viewers) {
                    v.spawnParticle(Particle.ELECTRIC_SPARK, at, 30, 0.3, 0.8, 0.3, 0.05);
                    v.playSound(at, "entity.lightning_bolt.thunder", 0.4f, 1.4f);
                }
            } else {
                at.getWorld().strikeLightningEffect(at);
            }
        }
        if (fx.particle() != null) {
            Particle pt = Particle.valueOf(fx.particle());
            Object data = pt.getDataType() == Particle.DustOptions.class ? new Particle.DustOptions(color(fx.color(), t), kill ? 1.4f : 1.0f) : null;
            double spread = fx.spread();
            for (Player v : viewers) {
                if (data == null) v.spawnParticle(pt, at, fx.count(), spread, kill ? spread : spread * 0.3, spread, fx.speed());
                else v.spawnParticle(pt, at, fx.count(), spread, kill ? spread : spread * 0.3, spread, fx.speed(), data);
            }
        }
        if (fx.sound() != null) {
            for (Player v : viewers) v.playSound(at, fx.sound(), kill ? 0.8f : 0.25f, 1f);
        }
    }

    /** "#ff8800", or "rainbow": the hue goes round every few seconds. */
    static Color color(String spec, long t) {
        if (spec == null) return Color.WHITE;
        String s = spec.trim().toLowerCase(Locale.ROOT);
        if (s.equals("rainbow")) {
            java.awt.Color c = java.awt.Color.getHSBColor((t % 120) / 120f, 0.75f, 1f);
            return Color.fromRGB(c.getRed(), c.getGreen(), c.getBlue());
        }
        try {
            return Color.fromRGB(Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16));
        } catch (NumberFormatException e) {
            return Color.WHITE;
        }
    }
}
