package dev.stsv.core.world;

import org.bukkit.configuration.ConfigurationSection;

/**
 * config.yml "worlds": which world is the 건축월드 (build world: land, protection) and which the
 * 야생월드 (wild world: a free resource world the operator can regenerate), and how /야생월드 scatters
 * players. Pure (unit-tested).
 *
 * @param build     the build world's name; empty = the server's main world (level-name)
 * @param wild      the wild world's name (created on start if missing); empty = no wild world
 * @param warmup    seconds to stand still before /건축월드 or /야생월드 moves you (0 = at once)
 * @param radius    /야생월드 lands at most this many blocks from the wild world's spawn
 * @param minDistance and at least this many (so players spread out)
 * @param tries     random spots tried before giving up
 * @param cooldown  seconds between two trips to the wild world
 */
public record WorldRules(String build, String wild, int warmup, int radius, int minDistance, int tries, int cooldown) {

    /** What a world is for. */
    public enum Kind { BUILD, WILD, OTHER }

    public static WorldRules parse(ConfigurationSection c) {
        if (c == null) return new WorldRules("", "", 0, 5000, 300, 12, 60);
        String build = c.getString("build-world");
        String wild = c.getString("wild-world");
        int radius = Math.max(16, c.getInt("wild-random.radius"));
        return new WorldRules(build == null ? "" : build.trim(), wild == null ? "" : wild.trim(),
                Math.clamp(c.getInt("travel-warmup-seconds"), 0, 60), radius,
                Math.clamp(c.getInt("wild-random.min-distance"), 0, radius - 1),
                Math.clamp(c.getInt("wild-random.tries"), 1, 50), Math.max(0, c.getInt("wild-random.cooldown-seconds")));
    }

    /** The build world's name, given the server's main world. */
    public String buildName(String mainWorld) {
        return build.isEmpty() ? mainWorld : build;
    }

    /** Is there a wild world at all? Not when it is unset or names the build world itself. */
    public boolean hasWild(String mainWorld) {
        return !wild.isEmpty() && !wild.equals(buildName(mainWorld));
    }

    public Kind kind(String world, String mainWorld) {
        if (world == null) return Kind.OTHER;
        if (world.equals(buildName(mainWorld))) return Kind.BUILD;
        if (hasWild(mainWorld) && world.equals(wild)) return Kind.WILD;
        return Kind.OTHER;
    }

    /** A random column between minDistance and radius blocks from (cx, cz), evenly spread over that ring's area. */
    public int[] randomColumn(java.util.random.RandomGenerator random, int cx, int cz) {
        double r = Math.sqrt(minDistance * (double) minDistance + random.nextDouble() * (radius * (double) radius - minDistance * (double) minDistance));
        double a = random.nextDouble() * Math.PI * 2;
        return new int[]{cx + (int) Math.round(Math.cos(a) * r), cz + (int) Math.round(Math.sin(a) * r)};
    }
}
