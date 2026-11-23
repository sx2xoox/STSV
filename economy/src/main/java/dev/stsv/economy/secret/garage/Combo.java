package dev.stsv.economy.secret.garage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A look of the racer's car: a paint, a wheel design and an underglow colour (option ids of the tuning shop; null: not
 * chosen yet). Pure (unit-tested): the server's true look, how many of a guess are right, the notebook of guesses.
 */
record Combo(String paint, String wheel, String glow) {

    static final Combo NONE = new Combo(null, null, null);

    /** All three chosen. */
    boolean whole() {
        return paint != null && wheel != null && glow != null;
    }

    boolean empty() {
        return paint == null && wheel == null && glow == null;
    }

    /** kind: "paint", "wheel" or "glow". */
    Combo with(String kind, String id) {
        return switch (kind) {
            case "paint" -> new Combo(id, wheel, glow);
            case "wheel" -> new Combo(paint, id, glow);
            case "glow" -> new Combo(paint, wheel, id);
            default -> this;
        };
    }

    String get(String kind) {
        return switch (kind) {
            case "paint" -> paint;
            case "wheel" -> wheel;
            case "glow" -> glow;
            default -> null;
        };
    }

    /** "wine/gold/cyan" ("-" for a part not chosen). */
    String encode() {
        return part(paint) + "/" + part(wheel) + "/" + part(glow);
    }

    private static String part(String s) {
        return s == null ? "-" : s;
    }

    private static String id(String s) {
        return s == null || !s.matches("[a-z0-9_]{1,32}") ? null : s;
    }

    static Combo decode(String raw) {
        if (raw == null) return NONE;
        String[] w = raw.trim().split("/");
        if (w.length != 3) return NONE;
        return new Combo(id(w[0]), id(w[1]), id(w[2]));
    }

    /**
     * The look the car had on this server: worked out from the main world's id alone, so it is the same for everyone
     * here, stays the same for good, and differs from server to server. Null when a list is empty.
     */
    static Combo answer(UUID world, List<String> paints, List<String> wheels, List<String> glows) {
        if (world == null || paints.isEmpty() || wheels.isEmpty() || glows.isEmpty()) return null;
        long seed = world.getMostSignificantBits() ^ Long.rotateLeft(world.getLeastSignificantBits(), 31);
        return new Combo(paints.get(index(seed, 0x9a5fd0L, paints.size())), wheels.get(index(seed, 0x77ee1L, wheels.size())),
                glows.get(index(seed, 0x46e6ffL, glows.size())));
    }

    /** All three chosen, each one of the listed options. */
    static boolean listed(Combo c, List<String> paints, List<String> wheels, List<String> glows) {
        return c != null && c.whole() && paints.contains(c.paint()) && wheels.contains(c.wheel()) && glows.contains(c.glow());
    }

    /**
     * The server's true look, settled for good: the one written down before (kept) while its options are still listed;
     * else {@link #answer} — on a server where the secret was already live (it has its place) from the options of the
     * secret's first version (first*), so that options added since do not change a car players are already guessing; on
     * a new server from all of them. (When a first list is empty the whole list is used.)
     */
    static Combo settle(Combo kept, UUID world, boolean live, List<String> paints, List<String> wheels, List<String> glows,
                        List<String> firstPaints, List<String> firstWheels, List<String> firstGlows) {
        if (listed(kept, paints, wheels, glows)) return kept;
        if (live) {
            return answer(world, firstPaints.isEmpty() ? paints : firstPaints, firstWheels.isEmpty() ? wheels : firstWheels,
                    firstGlows.isEmpty() ? glows : firstGlows);
        }
        return answer(world, paints, wheels, glows);
    }

    /** splitmix64 of the seed and a salt, as a place in a list (written out so it never changes with the JDK). */
    private static int index(long seed, long salt, int size) {
        long z = seed + salt * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (int) Math.floorMod(z, (long) size);
    }

    /** How many of the three are as on the true car (0..3): all he ever tells. */
    static int right(Combo guess, Combo answer) {
        if (guess == null || answer == null) return 0;
        int n = 0;
        if (guess.paint != null && guess.paint.equals(answer.paint)) n++;
        if (guess.wheel != null && guess.wheel.equals(answer.wheel)) n++;
        if (guess.glow != null && guess.glow.equals(answer.glow)) n++;
        return n;
    }

    /** One line of the notebook: a look that was tried and how many of it were right. */
    record Try(Combo look, int right) {}

    /** "white/five/cyan=1;wine/gold/cyan=2" (the oldest first), keeping the last {@code keep}. */
    static String notebook(List<Try> tries, int keep) {
        StringBuilder b = new StringBuilder();
        for (Try t : tries.subList(Math.max(0, tries.size() - keep), tries.size())) {
            if (!b.isEmpty()) b.append(';');
            b.append(t.look().encode()).append('=').append(t.right());
        }
        return b.toString();
    }

    /** Reads {@link #notebook}; lines that cannot be read are left out. */
    static List<Try> notebook(String raw) {
        List<Try> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split(";")) {
            int eq = line.lastIndexOf('=');
            if (eq <= 0) continue;
            Combo c = decode(line.substring(0, eq));
            try {
                int n = Integer.parseInt(line.substring(eq + 1).trim());
                if (c.whole() && n >= 0 && n <= 3) out.add(new Try(c, n));
            } catch (NumberFormatException ignored) {
                // a broken line
            }
        }
        return out;
    }
}
