package dev.stsv.economy.fishing;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 낚시대회 (README "낚시대회"): the rules, pure (unit-tested; {@link TournamentModule} runs them). Every hour on the hour (the
 * server's time zone), 10 minutes, one event drawn at random but never one of the last two. Only a rod's catch counts. A catch
 * during a tournament gets a size (text only, never on the item) from the species' range by the old size curve, a grade by its
 * place in that range and a weight from the size. Prizes: config.yml fishing-tournament.
 */
public final class TournamentRules {

    private TournamentRules() {}

    /** The events (their names and descriptions are messages.yml tournament.kind.<id>). */
    public enum Kind {
        /** the biggest single fish (tournament size) */
        BIG("big"),
        /** the smallest single fish: the lowest wins */
        SMALL("small"),
        /** the sum of the tournament weights */
        WEIGHT("weight"),
        /** how many fish */
        COUNT("count"),
        /** how many of one announced species (a common or uncommon one that can bite now) */
        SPECIES("species"),
        /** how many fish from one announced water (the hook's habitat) */
        HABITAT("habitat"),
        /** how many different species */
        VARIETY("variety"),
        /** how many fish of one announced colour group (fish.yml colors) */
        COLOUR("colour"),
        /** how many fish that only bite at a time or in a weather (fish.yml when) */
        MOMENT("moment"),
        /** rare 1 point, legend 5 */
        RARE("rare");

        public final String id;

        Kind(String id) {
            this.id = id;
        }

        public static Kind of(String id) {
            for (Kind k : values()) if (k.id.equalsIgnoreCase(id) || k.name().equalsIgnoreCase(id)) return k;
            return null;
        }

        /** Whether the lowest score wins (작은 고기). */
        public boolean lowestWins() {
            return this == SMALL;
        }

        /** The unit its score is shown in (messages.yml tournament.unit.<unit>). */
        public String unit() {
            return switch (this) {
                case BIG, SMALL -> "cm";
                case WEIGHT -> "kg";
                case VARIETY -> "kinds";
                case RARE -> "points";
                default -> "fish";
            };
        }

        /** Whether the event names a species, a water or a colour. */
        public boolean targeted() {
            return this == SPECIES || this == HABITAT || this == COLOUR;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Settings (config.yml fishing-tournament)
    // ------------------------------------------------------------------------------------------

    /**
     * minutes: how long; noticeSeconds: the notice before; minParticipants: prizes only with this many (accounts sharing an IP
     * count once); minCatches: a prize needs this many catches; newAccountMs: no stars for an account younger than this;
     * dailyStarCap: stars a player may win a day (Korean day); stars / gold: the prize of each place (1st first); avoidRecent:
     * an event is not drawn again within this many tournaments; gradeCuts: where in a species' size range each grade above the
     * first starts (0..1, rising); skipHabitats: waters the 서식지 event never names.
     */
    public record Settings(int minutes, int noticeSeconds, int minParticipants, int minCatches, long newAccountMs, int dailyStarCap,
                           List<Integer> stars, List<Long> gold, int avoidRecent, List<Double> gradeCuts, Set<String> skipHabitats) {

        public long durationMs() {
            return minutes * 60_000L;
        }

        public int places() {
            return Math.max(stars.size(), gold.size());
        }

        public int starsFor(int place) {
            return place >= 1 && place <= stars.size() ? Math.max(0, stars.get(place - 1)) : 0;
        }

        public long goldFor(int place) {
            return place >= 1 && place <= gold.size() ? Math.max(0, gold.get(place - 1)) : 0;
        }
    }

    // ------------------------------------------------------------------------------------------
    // When
    // ------------------------------------------------------------------------------------------

    /** The start of the hour now is in (in that zone: an odd offset shifts the hour). */
    public static long hourStart(long now, ZoneId zone) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli();
    }

    /** The next hour's start after now (now itself when it is exactly on the hour). */
    public static long nextStart(long now, ZoneId zone) {
        long h = hourStart(now, zone);
        return h == now ? now : ZonedDateTime.ofInstant(Instant.ofEpochMilli(h), zone).plusHours(1).toInstant().toEpochMilli();
    }

    /** What the clock asks for now: a notice (the hour's tournament is noticeSeconds away), a start (it is time), or nothing. */
    public enum Due { NONE, NOTICE, START }

    /**
     * The scheduler's step, asked every second or so: the tournament of the hour whose start is next (or just passed) is
     * noticed once noticeSeconds before it and starts once when its hour has begun and less than its length has passed (a
     * server that comes up at :04 still runs the :00 one, for what is left of it; one at :12 waits for the next hour).
     * lastNoticed / lastStarted: the hour starts already handled (0 = none).
     */
    public static Due due(long now, ZoneId zone, Settings s, long lastNoticed, long lastStarted) {
        long h = hourStart(now, zone);
        if (now - h < s.durationMs() && lastStarted != h) return Due.START;
        long next = nextStart(now + 1, zone);
        if (next - now <= s.noticeSeconds() * 1000L && lastNoticed != next && lastStarted != next) return Due.NOTICE;
        return Due.NONE;
    }

    // ------------------------------------------------------------------------------------------
    // Which event
    // ------------------------------------------------------------------------------------------

    /** An event drawn among those that can be held now, never one of the last avoid (recent: newest last). */
    public static Kind pick(Collection<Kind> available, List<Kind> recent, int avoid, Random r) {
        Set<Kind> skip = new HashSet<>(recent.subList(Math.max(0, recent.size() - Math.max(0, avoid)), recent.size()));
        List<Kind> pool = new ArrayList<>();
        for (Kind k : available) if (!skip.contains(k)) pool.add(k);
        if (pool.isEmpty()) pool.addAll(available);   // (fewer events than can be avoided: any)
        return pool.isEmpty() ? Kind.COUNT : pool.get(r.nextInt(pool.size()));
    }

    /**
     * The events that can be held at this moment: the 지정 어종 needs a common or uncommon fish that bites now, the 색깔 needs
     * fish.yml colors, the 시간대 needs a fish of this moment (night, dawn, dusk, rain, thunder, full moon), the 서식지 a water
     * not skipped.
     */
    public static List<Kind> available(FishSpec spec, FishSpec.Moment m, Settings s) {
        List<Kind> out = new ArrayList<>();
        for (Kind k : Kind.values()) {
            boolean ok = switch (k) {
                case SPECIES -> !speciesNow(spec, m).isEmpty();
                case COLOUR -> !spec.colours().isEmpty();
                case MOMENT -> spec.species().values().stream().anyMatch(x -> x.when() != null && m.is(x.when()));
                case HABITAT -> !habitats(spec, s).isEmpty();
                default -> true;
            };
            if (ok) out.add(k);
        }
        return out;
    }

    /** The 지정 어종 candidates: common and uncommon fish (rarity index 0, 1) that bite at this moment somewhere. */
    public static List<FishSpec.Species> speciesNow(FishSpec spec, FishSpec.Moment m) {
        List<FishSpec.Species> out = new ArrayList<>();
        for (FishSpec.Species x : spec.species().values()) {
            FishSpec.Rarity r = spec.rarities().get(x.rarity());
            if (r != null && r.index() <= 1 && m.is(x.when())) out.add(x);
        }
        return out;
    }

    /** The 서식지 candidates: the codex groups that are waters (not the time-and-weather group), less the skipped ones. */
    public static List<String> habitats(FishSpec spec, Settings s) {
        List<String> out = new ArrayList<>();
        for (String g : spec.groups().keySet()) if (!g.equals("special") && !s.skipHabitats().contains(g)) out.add(g);
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Size, grade, weight
    // ------------------------------------------------------------------------------------------

    /** The grade of a size (0 잔챙이 · 1 보통 · 2 씨알 굵은 · 3 대물 · 4 월척): where it stands in the species' range, against the cuts. */
    public static int grade(double min, double max, double size, List<Double> cuts) {
        double k = max <= min ? 1 : Math.clamp((size - min) / (max - min), 0, 1);
        int g = 0;
        for (double c : cuts) if (k >= c) g++;
        return g;
    }

    /**
     * The tournament weight (kg) of a fish of this size (cm) and picture shape: a game weight, not a real one. (size / 10)^2 / 10
     * x the shape's build: a fish 1, a long one (eel, catfish) 0.5, a flat or round one 1.3, a crab or shrimp 1.5 (its size is
     * across the shell), an octopus, squid or jellyfish 0.6. 30 cm = 0.9 kg, 1 m = 10 kg, 3 m = 90 kg. Square, not cube: a
     * net of ordinary fish can still beat one giant.
     */
    public static double weight(double cm, String shape) {
        double build = switch (shape == null ? "f" : shape) {
            case "l" -> 0.5;
            case "p" -> 1.3;
            case "c" -> 1.5;
            case "q" -> 0.6;
            default -> 1.0;
        };
        return Math.round(cm * cm / 1000.0 * build * 100) / 100.0;
    }

    // ------------------------------------------------------------------------------------------
    // Scoring
    // ------------------------------------------------------------------------------------------

    /** What an event names: the species id (지정 어종), the habitat (서식지), the colour group (색깔); null when it names none. */
    public record Target(String species, String habitat, String colour) {
        public static final Target NONE = new Target(null, null, null);
    }

    /**
     * One rod catch: its species, rarity (0..3), the water it came from, its colour group, whether it only bites at a moment
     * (fish.yml when), its tournament size (cm) and weight (kg).
     */
    public record Catch(String species, int rarity, String water, String colour, boolean moment, double size, double weight) {}

    /** One player's standing: catches (any), the score, whether they have one, when it last changed, the species caught. */
    public static final class Entry {
        public final UUID id;
        public String name;
        public final Set<String> ips = new LinkedHashSet<>();
        public final Set<String> species = new LinkedHashSet<>();
        public int catches;
        public double score;
        public boolean scored;
        public long at;

        public Entry(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** What a catch adds to the score of the event (count events: 1 or 0; 희귀 사냥: rare 1, legend 5). */
    public static double points(Kind k, Target t, Catch c) {
        return switch (k) {
            case COUNT -> 1;
            case SPECIES -> c.species().equals(t.species()) ? 1 : 0;
            case HABITAT -> c.water() != null && c.water().equals(t.habitat()) ? 1 : 0;
            case COLOUR -> c.colour() != null && c.colour().equals(t.colour()) ? 1 : 0;
            case MOMENT -> c.moment() ? 1 : 0;
            case RARE -> c.rarity() == 2 ? 1 : c.rarity() >= 3 ? 5 : 0;
            case WEIGHT -> c.weight();
            default -> 0;   // BIG, SMALL, VARIETY: not a sum
        };
    }

    /** Adds a catch to a standing at a time; true when the score changed (the board and the order may move). */
    public static boolean add(Kind k, Target t, Entry e, Catch c, long now) {
        e.catches++;
        boolean newKind = e.species.add(c.species());
        double before = e.score;
        boolean had = e.scored;
        switch (k) {
            case BIG -> {
                if (!e.scored || c.size() > e.score) e.score = c.size();
                e.scored = true;
            }
            case SMALL -> {
                if (!e.scored || c.size() < e.score) e.score = c.size();
                e.scored = true;
            }
            case VARIETY -> {
                e.score = e.species.size();
                e.scored = true;
            }
            default -> {
                double p = points(k, t, c);
                if (p > 0) {
                    e.score = Math.round((e.score + p) * 100) / 100.0;
                    e.scored = true;
                }
            }
        }
        boolean changed = e.scored != had || e.score != before || (k == Kind.VARIETY && newKind);
        if (changed) e.at = now;
        return changed;
    }

    /** The order: who has a score, the best first (the highest, or the lowest for 작은 고기); a tie goes to whoever got there first. */
    public static List<Entry> rank(Kind k, Collection<Entry> entries) {
        Comparator<Entry> byScore = Comparator.comparingDouble(e -> e.score);
        if (!k.lowestWins()) byScore = byScore.reversed();
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.scored && (k.lowestWins() || e.score > 0)) out.add(e);
        out.sort(byScore.thenComparingLong(e -> e.at).thenComparing(e -> e.id));
        return out;
    }

    /** A score as shown (cm and kg to 0.1 / 0.01, counts whole). */
    public static String number(Kind k, double score) {
        return switch (k) {
            case BIG, SMALL -> String.format("%.1f", score);
            case WEIGHT -> score >= 100 ? String.format("%,.0f", score) : String.format("%.1f", score);
            default -> String.valueOf(Math.round(score));
        };
    }

    // ------------------------------------------------------------------------------------------
    // Prizes
    // ------------------------------------------------------------------------------------------

    /**
     * How many participants count for the "2 or more" rule: accounts that shared an IP address during the tournament (directly
     * or through a chain) count as one; an account with no address known counts alone.
     */
    public static int participants(Collection<Entry> entries) {
        List<Entry> l = new ArrayList<>(entries);
        int[] parent = new int[l.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        Map<String, Integer> firstWith = new HashMap<>();
        for (int i = 0; i < l.size(); i++) {
            for (String ip : l.get(i).ips) {
                Integer j = firstWith.putIfAbsent(ip, i);
                if (j != null) union(parent, i, j);
            }
        }
        Set<Integer> roots = new HashSet<>();
        for (int i = 0; i < parent.length; i++) roots.add(find(parent, i));
        return roots.size();
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) i = p[i] = p[p[i]];
        return i;
    }

    private static void union(int[] p, int a, int b) {
        p[find(p, a)] = find(p, b);
    }

    /** Why a place gets less than its prize: NONE, too few catches (no prize at all), a new account (no stars). */
    public enum Short { NONE, FEW_CATCHES, NEW_ACCOUNT }

    /** A place's prize: the standing, the place (1..), the stars and gold it gets, and why it is short. */
    public record Award(Entry entry, int place, int stars, long gold, Short reason) {}

    /**
     * The prizes of a finished tournament, or an empty list when it had too few participants ({@link #participants}). A place
     * goes to whoever holds it (a place is not passed down): with fewer than minCatches catches it gets nothing; an account
     * younger than newAccountMs gets its gold but no stars. The daily star cap is applied when the stars are paid
     * ({@link #capped}).
     */
    public static List<Award> awards(List<Entry> ranking, int participants, Settings s, Predicate<UUID> newAccount) {
        List<Award> out = new ArrayList<>();
        if (participants < s.minParticipants()) return out;
        for (int i = 0; i < Math.min(ranking.size(), s.places()); i++) {
            Entry e = ranking.get(i);
            int place = i + 1, stars = s.starsFor(place);
            long gold = s.goldFor(place);
            if (stars <= 0 && gold <= 0) continue;
            if (e.catches < s.minCatches()) out.add(new Award(e, place, 0, 0, Short.FEW_CATCHES));
            else if (stars > 0 && newAccount.test(e.id)) out.add(new Award(e, place, 0, gold, Short.NEW_ACCOUNT));
            else out.add(new Award(e, place, stars, gold, Short.NONE));
        }
        return out;
    }

    /** The stars that fit under the daily cap: what is won, at most what is left of the cap today (never below 0). */
    public static int capped(int won, long alreadyToday, int cap) {
        return (int) Math.max(0, Math.min(won, cap - alreadyToday));
    }
}
