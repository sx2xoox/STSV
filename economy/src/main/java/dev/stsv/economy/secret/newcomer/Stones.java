package dev.stsv.economy.secret.newcomer;

import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.secret.Secret;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Where the monument's stones stand (secret/Monument, which this package cannot see into): the stones themselves are
 * read from the world — every stone is an item display tagged economy:secret_stone = "&lt;id&gt;|…" near the place saved in
 * eco.kv "secret.monument" — so a changed layout is followed. Only when his stone is not there to be read (its chunk is
 * not loaded, or the stones are not built yet) its place is worked out the way the monument lays them out.
 */
final class Stones {

    /** The monument's tag on each of its displays, and where its place is kept. */
    static final NamespacedKey TAG = new NamespacedKey("economy", "secret_stone");
    static final String PLACE = "secret.monument";
    /** The monument's arc as it is drawn (Monument.offset): 130 degrees wide, 8 blocks out, at least this many stones. */
    static final double SPREAD = 130, RADIUS = 8;
    static final int SLOTS = 13;
    /** A stone on other ground (a floor above or below) is not the one stood at. */
    static final double HEIGHT = 4;
    private static final long KEEP = 5_000L;

    private final EcoPlugin eco;
    private String seenPlace;
    private long seenAt;
    private Found seen;

    Stones(EcoPlugin eco) {
        this.eco = eco;
    }

    /** One stone: whose it is and where it stands (its foot). */
    record Spot(String id, double x, double y, double z) {}

    /** The saved place: world, the spot the admin stood on, the way they looked. */
    record Place(String world, double x, double y, double z, float yaw) {}

    /** Where someone stands among the stones. */
    enum Where { HIS, OTHER, NONE }

    /** The stones of a monument: its world, every stone read (or worked out), and whether his was really read. */
    record Found(World world, Place place, List<Spot> spots, boolean real) {

        Optional<Spot> of(String id) {
            for (Spot s : spots) if (s.id().equals(id)) return Optional.of(s);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------------------------------
    // pure
    // ------------------------------------------------------------------------------------------

    /** "world x y z yaw" as the monument saves it. */
    static Optional<Place> place(String saved) {
        if (saved == null) return Optional.empty();
        String[] w = saved.split(" ");
        if (w.length < 5) return Optional.empty();
        try {
            return Optional.of(new Place(w[0], Double.parseDouble(w[1]), Double.parseDouble(w[2]), Double.parseDouble(w[3]), Float.parseFloat(w[4])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The secret a stone's tag names ("newcomer|123|model/secret_stone" → "newcomer"). */
    static String idOf(String tag) {
        int bar = tag.indexOf('|');
        return bar < 0 ? tag : tag.substring(0, bar);
    }

    /** Where stone i of n stands on the arc in front of (x, z) looking along yaw: {x, z}. */
    static double[] arc(double x, double z, float yaw, int i, int n) {
        double spread = Math.toRadians(SPREAD);
        double a = n <= 1 ? 0 : -spread / 2 + spread * i / (n - 1);
        double rad = Math.toRadians(yaw);
        return new double[]{x - Math.sin(rad + a) * RADIUS, z + Math.cos(rad + a) * RADIUS};
    }

    private static double flat(Spot s, double x, double z) {
        return Math.hypot(s.x() - x, s.z() - z);
    }

    /** The nearest stone within range (flat distance; a stone on another floor does not count). */
    static Optional<Spot> nearest(List<Spot> spots, double x, double y, double z, double range) {
        Spot best = null;
        double d = Double.MAX_VALUE;
        for (Spot s : spots) {
            if (Math.abs(s.y() - y) > HEIGHT) continue;
            double f = flat(s, x, z);
            if (f <= range && f < d) {
                d = f;
                best = s;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * At his stone, at another, or at none. The stones stand close together, so his counts when it is in range and no
     * other stone is nearer by more than the slack (standing between his and its neighbour is standing at his).
     */
    static Where standing(List<Spot> spots, double x, double y, double z, double range, String his, double slack) {
        Optional<Spot> near = nearest(spots, x, y, z, range);
        if (near.isEmpty()) return Where.NONE;
        if (near.get().id().equals(his)) return Where.HIS;
        for (Spot s : spots) {
            if (!s.id().equals(his) || Math.abs(s.y() - y) > HEIGHT) continue;
            double f = flat(s, x, z);
            if (f <= range && f <= flat(near.get(), x, z) + Math.max(0, slack)) return Where.HIS;
        }
        return Where.OTHER;
    }

    // ------------------------------------------------------------------------------------------
    // the world
    // ------------------------------------------------------------------------------------------

    /** Whether a monument place is saved at all (its world may still be missing). */
    boolean placed() {
        return eco.kv().has(PLACE);
    }

    /**
     * The stones as they stand now (read at most every few seconds; a moved monument is read anew). Empty when no monument
     * is placed or its world is not loaded. {@code order}: the secrets in the monument's order (after the bus stone).
     */
    Optional<Found> find(String his, Collection<Secret> order) {
        String saved = eco.kv().get(PLACE);
        long now = System.currentTimeMillis();
        if (saved != null && saved.equals(seenPlace) && now - seenAt < KEEP) return Optional.ofNullable(seen);
        seenPlace = saved;
        seenAt = now;
        seen = null;
        Optional<Place> pl = place(saved);
        World world = pl.map(p -> Bukkit.getWorld(p.world())).orElse(null);
        if (world == null) return Optional.empty();
        Place p = pl.get();
        List<Spot> spots = new ArrayList<>();
        Location base = new Location(world, p.x(), p.y(), p.z());
        if (world.isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) {
            for (Entity e : world.getNearbyEntities(base, 24, 12, 24)) {
                if (!(e instanceof ItemDisplay)) continue;
                String tag = e.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
                if (tag != null) spots.add(new Spot(idOf(tag), e.getX(), e.getY(), e.getZ()));
            }
        }
        boolean real = false;
        for (Spot s : spots) if (s.id().equals(his)) real = true;
        if (!real) {
            List<String> ids = new ArrayList<>(List.of("bus"));
            for (Secret s : order) if (s.hasStone()) ids.add(s.id());   // (a secret without a stone stands nowhere)
            int i = ids.indexOf(his);
            if (i >= 0) {
                double[] at = arc(p.x(), p.z(), p.yaw(), i, Math.max(SLOTS, ids.size()));
                spots.add(new Spot(his, at[0], p.y(), at[1]));
            }
        }
        seen = new Found(world, p, List.copyOf(spots), real);
        return Optional.of(seen);
    }

    /** Forgets what was read (settings reloaded). */
    void forget() {
        seenPlace = null;
        seen = null;
    }
}
