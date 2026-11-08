package dev.stsv.core.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The 야생월드's ready spots: safe columns found ahead of time (with the chunks around them already generated),
 * so /야생월드 never has to generate terrain while a player waits. Bound to one wild world (its name and seed):
 * after /관리자 야생초기화 the seed changes and the old spots no longer mean anything. Saved as a small text file
 * so a restart does not start empty. Pure (unit-tested); used on the main thread only.
 * <pre>
 * # comment
 * world &lt;name&gt; &lt;seed&gt;
 * &lt;x&gt; &lt;y&gt; &lt;z&gt;        (y = the block the player's feet are in)
 * </pre>
 */
public final class WildSpotPool {

    /** A ready spot: the player stands at (x + 0.5, y, z + 0.5); y - 1 is the ground. */
    public record Spot(int x, int y, int z) {}

    private final List<Spot> spots = new ArrayList<>();
    private final Set<Spot> known = new HashSet<>();
    private String world = "";
    private long seed;

    /** Are these spots for this world (same name and seed)? */
    public boolean matches(String world, long seed) {
        return this.world.equals(world) && this.seed == seed;
    }

    public String world() {
        return world;
    }

    public long seed() {
        return seed;
    }

    /** Empties the pool and binds it to a world (a new or regenerated wild world). */
    public void reset(String world, long seed) {
        clear();
        this.world = world == null ? "" : world;
        this.seed = seed;
    }

    public void clear() {
        spots.clear();
        known.clear();
    }

    public int size() {
        return spots.size();
    }

    public boolean isEmpty() {
        return spots.isEmpty();
    }

    /** Adds a spot; false if it is already there. */
    public boolean add(Spot s) {
        if (s == null || !known.add(s)) return false;
        spots.add(s);
        return true;
    }

    /** Takes a random spot out of the pool; null when empty. */
    public Spot take(RandomGenerator random) {
        if (spots.isEmpty()) return null;
        int i = random.nextInt(spots.size());
        Spot s = spots.get(i);
        Spot last = spots.removeLast();
        if (i < spots.size()) spots.set(i, last);
        known.remove(s);
        return s;
    }

    public List<Spot> spots() {
        return List.copyOf(spots);
    }

    /** The file's text. */
    public String write() {
        StringBuilder b = new StringBuilder("# STSV: /야생월드 준비된 자리 (자동으로 관리됨. 지워도 되며 다시 채워짐)\n");
        b.append("world ").append(world).append(' ').append(seed).append('\n');
        for (Spot s : spots) b.append(s.x()).append(' ').append(s.y()).append(' ').append(s.z()).append('\n');
        return b.toString();
    }

    /** Reads a file's text; broken lines are skipped, a file without a world line gives an empty, unbound pool. */
    public static WildSpotPool read(String text) {
        WildSpotPool p = new WildSpotPool();
        if (text == null) return p;
        boolean bound = false;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] a = line.split("\\s+");
            try {
                if (a[0].equals("world")) {
                    if (a.length < 3) continue;
                    String name = line.substring("world".length(), line.lastIndexOf(' ')).trim();
                    p.reset(name, Long.parseLong(a[a.length - 1]));
                    bound = true;
                } else if (bound && a.length == 3) {
                    p.add(new Spot(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2])));
                }
            } catch (NumberFormatException ignored) {
                // a broken line: skip it
            }
        }
        return p;
    }
}
