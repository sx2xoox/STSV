package dev.stsv.core.world;

import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * config.yml "worlds": which world is the 건축월드 (build world: land, protection), which the 야생월드
 * (wild world: a free resource world the operator can regenerate) and its own nether and end (reached by the
 * wild world's portals, free like it), how /야생월드 scatters players, and in which of those worlds dying keeps
 * items and XP. Pure (unit-tested).
 *
 * @param build     the build world's name; empty = the server's main world (level-name)
 * @param wild      the wild world's name (created on start if missing); empty = no wild world
 * @param warmup    seconds to stand still before /건축월드 or /야생월드 moves you (0 = at once)
 * @param radius    /야생월드 lands at most this many blocks from the wild world's spawn
 * @param minDistance and at least this many (so players spread out)
 * @param tries     random spots tried before giving up
 * @param cooldown  seconds between two trips to the wild world
 * @param wildNether the wild world's nether; empty = "&lt;wild&gt;_nether"
 * @param wildEnd   the wild world's end; empty = "&lt;wild&gt;_the_end"
 * @param keepInventory the worlds, by role, where the keepInventory game rule is turned on
 */
public record WorldRules(String build, String wild, int warmup, int radius, int minDistance, int tries, int cooldown,
                         String wildNether, String wildEnd, Set<Kind> keepInventory) {

    /**
     * What a world is for. NETHER and END are the wild world's own nether and end; the server's default
     * nether and end ({@link #retired}) are OTHER.
     */
    public enum Kind { BUILD, WILD, NETHER, END, OTHER }

    /** worlds.keep-inventory when the key is missing (a config.yml from before it existed). */
    public static final Set<Kind> DEFAULT_KEEP = Set.copyOf(EnumSet.of(Kind.NETHER, Kind.END));

    public WorldRules {
        build = build == null ? "" : build.trim();
        wild = wild == null ? "" : wild.trim();
        wildNether = wildNether == null ? "" : wildNether.trim();
        wildEnd = wildEnd == null ? "" : wildEnd.trim();
        keepInventory = keepInventory == null ? Set.of() : Set.copyOf(keepInventory);
    }

    public static WorldRules parse(ConfigurationSection c) {
        if (c == null) return new WorldRules("", "", 0, 5000, 300, 12, 60, "", "", DEFAULT_KEEP);
        int radius = Math.max(16, c.getInt("wild-random.radius"));
        Set<Kind> keep = c.contains("keep-inventory") ? roles(c.getStringList("keep-inventory")) : DEFAULT_KEEP;
        return new WorldRules(c.getString("build-world"), c.getString("wild-world"),
                Math.clamp(c.getInt("travel-warmup-seconds"), 0, 60), radius,
                Math.clamp(c.getInt("wild-random.min-distance"), 0, radius - 1),
                Math.clamp(c.getInt("wild-random.tries"), 1, 50), Math.max(0, c.getInt("wild-random.cooldown-seconds")),
                c.getString("wild-nether"), c.getString("wild-end"), keep);
    }

    /** A role in worlds.keep-inventory ("nether", "지옥", ...); null = not a role. */
    public static Kind role(String word) {
        if (word == null) return null;
        return switch (word.trim().toLowerCase(Locale.ROOT)) {
            case "build", "건축", "건축월드" -> Kind.BUILD;
            case "wild", "야생", "야생월드" -> Kind.WILD;
            case "nether", "the_nether", "네더", "지옥" -> Kind.NETHER;
            case "end", "the_end", "엔드", "엔더" -> Kind.END;
            default -> null;
        };
    }

    /** The roles of a worlds.keep-inventory list; words that are not roles are left out. */
    public static Set<Kind> roles(List<String> words) {
        Set<Kind> out = EnumSet.noneOf(Kind.class);
        for (String w : words) {
            Kind k = role(w);
            if (k != null) out.add(k);
        }
        return out;
    }

    /** The same rules with another build world (the configured one is not loaded). */
    public WorldRules withBuild(String build) {
        return new WorldRules(build, wild, warmup, radius, minDistance, tries, cooldown, wildNether, wildEnd, keepInventory);
    }

    /** The build world's name, given the server's main world. */
    public String buildName(String mainWorld) {
        return build.isEmpty() ? mainWorld : build;
    }

    /** Is there a wild world at all? Not when it is unset or names the build world itself. */
    public boolean hasWild(String mainWorld) {
        return !wild.isEmpty() && !wild.equals(buildName(mainWorld));
    }

    /** The wild world's nether; null without a wild world. */
    public String wildNetherName(String mainWorld) {
        return !hasWild(mainWorld) ? null : wildNether.isEmpty() ? wild + "_nether" : wildNether;
    }

    /** The wild world's end; null without a wild world. */
    public String wildEndName(String mainWorld) {
        return !hasWild(mainWorld) ? null : wildEnd.isEmpty() ? wild + "_the_end" : wildEnd;
    }

    public Kind kind(String world, String mainWorld) {
        if (world == null) return Kind.OTHER;
        if (world.equals(buildName(mainWorld))) return Kind.BUILD;
        if (!hasWild(mainWorld)) return Kind.OTHER;
        if (world.equals(wild)) return Kind.WILD;
        if (world.equals(wildNetherName(mainWorld))) return Kind.NETHER;
        if (world.equals(wildEndName(mainWorld))) return Kind.END;
        return Kind.OTHER;
    }

    /** The wild world, its nether or its end: free, and where a death there respawns. */
    public static boolean wildSet(Kind kind) {
        return kind == Kind.WILD || kind == Kind.NETHER || kind == Kind.END;
    }

    /**
     * The server's default nether and end ("&lt;level-name&gt;_nether", "&lt;level-name&gt;_the_end"), which
     * nobody plays in while there is a wild world (its own nether and end replace them): a player found there is
     * moved to the wild world. Kept loaded (vanilla needs them for portals) and never deleted.
     */
    public boolean retired(String world, String mainWorld) {
        return world != null && hasWild(mainWorld) && kind(world, mainWorld) == Kind.OTHER
                && (world.equals(mainWorld + "_nether") || world.equals(mainWorld + "_the_end"));
    }

    /** Does dying in a world of this kind keep items and XP (keepInventory turned on by the plugin)? */
    public boolean keeps(Kind kind) {
        return keepInventory.contains(kind);
    }

    /** A random column between minDistance and radius blocks from (cx, cz), evenly spread over that ring's area. */
    public int[] randomColumn(java.util.random.RandomGenerator random, int cx, int cz) {
        double r = Math.sqrt(minDistance * (double) minDistance + random.nextDouble() * (radius * (double) radius - minDistance * (double) minDistance));
        double a = random.nextDouble() * Math.PI * 2;
        return new int[]{cx + (int) Math.round(Math.cos(a) * r), cz + (int) Math.round(Math.sin(a) * r)};
    }
}
