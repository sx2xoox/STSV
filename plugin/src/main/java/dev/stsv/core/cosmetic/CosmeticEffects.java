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
 *
 * Every nearby player who can see the wearer must see them, not only the wearer. Two things used to hide
 * them from everyone else:
 * <ul>
 *   <li>the particles were sent as ordinary ones: each client then drops them by its own particle setting
 *       (none at all on 최소/Minimal, a third fewer on 감소/Decreased) and beyond 32 blocks, so many players
 *       saw no trail (the wearer, with the default setting, did). They are sent "forced" now: the server
 *       already picks who gets them (players near the spot who can see the wearer);</li>
 *   <li>a trail mark was put where the wearer stands on the server. The wearer's own client is ahead of
 *       the server, so they saw it behind them; everyone else sees the wearer a few ticks late, so the mark
 *       came up under or in front of the wearer's legs and the short-lived particles (notes, flames,
 *       hearts) were gone, hidden by the body, before the wearer had walked past. The mark now goes
 *       {@link #TRAIL_BEHIND} blocks behind the wearer, along the way they walked.</li>
 * </ul>
 */
final class CosmeticEffects {

    private CosmeticEffects() {}

    /** How far behind the wearer (along the way they walked) a trail mark appears, in blocks. */
    static final double TRAIL_BEHIND = 0.8;
    /**
     * Particles are sent forced (the client's particle setting and its 32-block limit do not drop them):
     * who gets them is decided here, by {@link #shownTo}.
     */
    static final boolean FORCE = true;

    /**
     * Whether a player gets a wearer's trail or kill effect: the wearer always, anyone else who can see the
     * wearer (not hidden from them) within range of the effect.
     */
    static boolean shownTo(boolean isWearer, boolean canSeeWearer, double distanceSquared, double range) {
        return (isWearer || canSeeWearer) && distanceSquared <= range * range;
    }

    /**
     * Where a trail mark goes: behind the wearer's feet by {@link #TRAIL_BEHIND}, along their last step on the
     * ground (from last to here, sideways only). The feet themselves when there was no step to go by (no
     * earlier mark, another world, or straight up or down).
     */
    static Location trailMark(Location last, Location here) {
        Location at = here.clone();
        if (last == null || last.getWorld() != here.getWorld()) return at;
        double dx = here.getX() - last.getX(), dz = here.getZ() - last.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 1e-3) return at;
        return at.subtract(dx / len * TRAIL_BEHIND, 0, dz / len * TRAIL_BEHIND);
    }

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
                v.spawnParticle(pt, at, fx.count(), spread, kill ? spread : spread * 0.3, spread, fx.speed(), data, FORCE);
            }
        }
        if (fx.sound() != null) {
            for (Player v : viewers) v.playSound(at, fx.sound(), kill ? 0.8f : 0.25f, 1f);
        }
    }

    /** Ticks for "rainbow" to go once round the colours: short enough that one trail shows several. */
    static final int RAINBOW_TICKS = 30;

    /** "#ff8800", or "rainbow": the hue goes round every 1.5 seconds. */
    static Color color(String spec, long t) {
        if (spec == null) return Color.WHITE;
        String s = spec.trim().toLowerCase(Locale.ROOT);
        if (s.equals("rainbow")) {
            java.awt.Color c = java.awt.Color.getHSBColor((t % RAINBOW_TICKS) / (float) RAINBOW_TICKS, 0.75f, 1f);
            return Color.fromRGB(c.getRed(), c.getGreen(), c.getBlue());
        }
        try {
            return Color.fromRGB(Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16));
        } catch (NumberFormatException e) {
            return Color.WHITE;
        }
    }
}
