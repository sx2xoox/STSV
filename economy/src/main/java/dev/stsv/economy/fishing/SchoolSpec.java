package dev.stsv.economy.fishing;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 어군 조업 (fish.yml schools, README "어군 조업"): where fish schools come up, how big and of what, what each boat's 어군 탐지기
 * shows and how much its net takes from a school at once. Pure data and the maths of it (unit-tested: SchoolSpecTest).
 *
 * @param maxPerWorld schools at once in one world
 * @param spawnEvery  seconds between two tries to make a school in a world (while there are fewer than the most)
 * @param lifetime    seconds a school stays before it scatters by itself
 * @param radius      blocks: a net (the stern's water) this close to a school's middle fishes it
 * @param spawnMin    a school comes up this far from someone sitting in a boat (spawnMin .. spawnMax, in loaded chunks only)
 * @param harbourMin  ... or this far from a 항구 하역장 (harbourMin .. harbourMax) someone is near
 * @param apart       blocks between two schools, and from where a school scattered in the last 10 minutes (the core's spot rest)
 * @param biomes      biome key parts a school comes up in ("ocean": every sea)
 * @param seeDay      blocks a school is seen from by day (seeNight at night)
 * @param xpValue     G of catch (base price) a haul gives a fisher one 통발 fish of job experience for
 * @param sizeWords   the words for a small, middling and big school ({@link #size})
 * @param sizeCuts    G left (base price x fish) under which a school is small, then middling; above the last it is big
 * @param boardRange  blocks around a harbour its 어황판 lists schools from; boardLines = at most that many
 * @param compass     the board's eight ways, north first, clockwise ({@link #compass(double, double)})
 */
public record SchoolSpec(boolean enabled, int maxPerWorld, int spawnEvery, int lifetime, double radius, double spawnMin, double spawnMax,
                         double harbourMin, double harbourMax, double apart, List<String> biomes, double seeDay, double seeNight,
                         double xpValue, List<String> sizeWords, List<Double> sizeCuts, double boardRange, int boardLines,
                         List<String> compass, List<Band> bands, Map<String, Boat> boats) {

    /** What a boat's 어군 탐지기 shows: nothing (no detector), the way, the way and how far, all (size and fish too). */
    public enum Show { NONE, DIRECTION, DISTANCE, FULL }

    /**
     * A sea by its distance from the nearest 항구 하역장: from = blocks where it starts, bonus = a net takes this many times its haul
     * there, value = G (base price) a new school holds, [min, max]; fish = which 떼물고기 a school there is, by weight.
     */
    public record Band(String id, String name, double from, double bonus, double minValue, double maxValue, Map<String, Integer> fish) {}

    /**
     * A boat (the core's vehicle item id): its detector's range (0 = none: only the eye) and what it shows, and haul = G (base price)
     * its net takes from a school each time it comes up (x the sea's bonus). 0 = it does not fish schools.
     */
    public record Boat(String item, double detect, Show show, double haul) {}

    /** No schools (fish.yml without schools:). */
    public static final SchoolSpec NONE = new SchoolSpec(false, 0, 60, 600, 8, 48, 144, 64, 400, 64, List.of(), 64, 32, 50,
            List.of("", "", ""), List.of(0.0, 0.0), 0, 0, List.of("", "", "", "", "", "", "", ""), List.of(), Map.of());

    /** At most this many fish come up in one haul (one stack: the core's NetHaul puts one stack into the hold). */
    public static final int MAX_HAUL = 64;

    public static SchoolSpec parse(ConfigurationSection c, FishSpec fish, List<String> problems) {
        if (c == null) return NONE;
        for (String k : List.of("enabled", "max-per-world", "spawn-every", "lifetime", "radius", "spawn-distance", "harbour-distance", "apart",
                "biomes", "see-day", "see-night", "xp-value", "sizes", "size-cuts", "board-range", "board-lines", "compass", "bands", "boats")) {
            if (!c.contains(k)) problems.add("schools." + k + " 이 없음");
        }
        List<Double> spawn = c.getDoubleList("spawn-distance"), harbour = c.getDoubleList("harbour-distance");
        if (spawn.size() != 2 || spawn.get(0) > spawn.get(1)) {
            problems.add("schools.spawn-distance: [최소, 최대]");
            spawn = List.of(48.0, 144.0);
        }
        if (harbour.size() != 2 || harbour.get(0) > harbour.get(1)) {
            problems.add("schools.harbour-distance: [최소, 최대]");
            harbour = List.of(64.0, 400.0);
        }
        ConfigurationSection sz = c.getConfigurationSection("sizes");
        List<String> words = sz == null ? List.of("", "", "") : List.of(sz.getString("small", ""), sz.getString("medium", ""), sz.getString("big", ""));
        List<Double> cuts = c.getDoubleList("size-cuts");
        if (cuts.size() != 2 || cuts.get(0) > cuts.get(1)) {
            problems.add("schools.size-cuts: [작은 떼 아래, 보통 떼 아래]");
            cuts = List.of(0.0, 0.0);
        }
        List<String> compass = c.getStringList("compass");
        if (compass.size() != 8) {
            problems.add("schools.compass: 여덟 방향 (북쪽부터 시계 방향)");
            compass = NONE.compass();
        }
        List<Band> bands = new ArrayList<>();
        ConfigurationSection bs = c.getConfigurationSection("bands");
        if (bs != null) {
            for (String id : bs.getKeys(false)) {
                ConfigurationSection b = bs.getConfigurationSection(id);
                if (b == null) continue;
                List<Double> v = b.getDoubleList("value");
                Map<String, Integer> pool = new LinkedHashMap<>();
                ConfigurationSection fs = b.getConfigurationSection("fish");
                if (fs != null) {
                    for (String f : fs.getKeys(false)) {
                        FishSpec.Species s = fish == null ? null : fish.species().get(f);
                        if (fish != null && (s == null || !s.boatOnly())) problems.add("schools.bands." + id + ": 떼물고기(habitat school)가 아님 " + f);
                        else if (fs.getInt(f) > 0) pool.put(f, fs.getInt(f));
                    }
                }
                if (pool.isEmpty()) problems.add("schools.bands." + id + ": fish 가 없음");
                if (v.size() != 2 || v.get(0) <= 0 || v.get(1) < v.get(0)) problems.add("schools.bands." + id + ": value [최소, 최대]");
                double bonus = b.getDouble("bonus", 1);
                if (bonus <= 0) problems.add("schools.bands." + id + ": bonus 는 0보다 커야 함");
                bands.add(new Band(id, b.getString("name", id), b.getDouble("from", 0), bonus, v.size() == 2 ? v.get(0) : 1,
                        v.size() == 2 ? v.get(1) : 1, Map.copyOf(pool)));
            }
        }
        if (bands.isEmpty()) problems.add("schools.bands: 바다가 없음");
        for (int i = 1; i < bands.size(); i++) {
            if (bands.get(i).from() <= bands.get(i - 1).from()) problems.add("schools.bands." + bands.get(i).id() + ": from 은 앞의 것보다 멀어야 함");
        }
        if (!bands.isEmpty() && bands.getFirst().from() != 0) problems.add("schools.bands: 첫 바다는 from 0");
        Map<String, Boat> boats = new LinkedHashMap<>();
        ConfigurationSection os = c.getConfigurationSection("boats");
        if (os != null) {
            for (String key : os.getKeys(false)) {
                ConfigurationSection b = os.getConfigurationSection(key);
                if (b == null) continue;
                double detect = b.getDouble("detect", 0);
                Show show = detect <= 0 ? Show.NONE : switch (b.getString("show", "")) {
                    case "direction" -> Show.DIRECTION;
                    case "distance" -> Show.DISTANCE;
                    case "full" -> Show.FULL;
                    default -> {
                        problems.add("schools.boats." + key + ": show 는 direction · distance · full");
                        yield Show.DIRECTION;
                    }
                };
                double haul = b.getDouble("haul", 0);
                if (haul < 0) problems.add("schools.boats." + key + ": haul 은 0 이상");
                String id = key.toLowerCase(java.util.Locale.ROOT);
                boats.put(id, new Boat(id, Math.max(0, detect), show, Math.max(0, haul)));
            }
        }
        return new SchoolSpec(c.getBoolean("enabled", false), Math.max(0, c.getInt("max-per-world", 0)), Math.max(1, c.getInt("spawn-every", 60)),
                Math.max(1, c.getInt("lifetime", 600)), Math.max(1, c.getDouble("radius", 8)), spawn.get(0), spawn.get(1), harbour.get(0), harbour.get(1),
                Math.max(0, c.getDouble("apart", 64)), List.copyOf(c.getStringList("biomes")), c.getDouble("see-day", 64), c.getDouble("see-night", 32),
                Math.max(1, c.getDouble("xp-value", 50)), words, List.copyOf(cuts), c.getDouble("board-range", 0), Math.max(0, c.getInt("board-lines", 0)),
                List.copyOf(compass), List.copyOf(bands), Map.copyOf(boats));
    }

    // ------------------------------------------------------------------------------------------
    // The sea, the school
    // ------------------------------------------------------------------------------------------

    /** The sea at this distance from the nearest harbour (-1 = the world has none: the nearest sea). */
    public Band band(double harbourDistance) {
        Band out = bands.isEmpty() ? null : bands.getFirst();
        for (Band b : bands) if (harbourDistance >= b.from()) out = b;
        return out;
    }

    public Band band(String id) {
        for (Band b : bands) if (b.id().equals(id)) return b;
        return bands.isEmpty() ? null : bands.getFirst();
    }

    /** A 떼물고기 of that sea, by the pool's weights. */
    public static String pick(Band b, Random r) {
        int total = 0;
        for (int w : b.fish().values()) total += w;
        if (total <= 0) return null;
        int x = r.nextInt(total);
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(b.fish()).entrySet()) {
            x -= e.getValue();
            if (x < 0) return e.getKey();
        }
        return null;
    }

    /** How many fish of this price (G) a new school worth `value` G holds: at least one. */
    public static int amount(double value, double price) {
        return (int) Math.max(1, Math.round(value / Math.max(0.01, price)));
    }

    /**
     * How many fish one haul takes from a school: the boat's haul (G) x the sea's bonus at this fish's price, at least one, at most
     * {@link #MAX_HAUL} (one stack) and what the school has left.
     */
    public static int haulCount(double haul, double bonus, double price, int left) {
        if (left <= 0 || haul <= 0) return 0;
        long n = Math.max(1, Math.round(haul * bonus / Math.max(0.01, price)));
        return (int) Math.min(Math.min(n, MAX_HAUL), left);
    }

    /** 0 small, 1 middling, 2 big: by the G a school has left (fish x price) against {@link #sizeCuts}. */
    public int size(double valueLeft) {
        if (valueLeft < sizeCuts.get(0)) return 0;
        return valueLeft < sizeCuts.get(1) ? 1 : 2;
    }

    public String sizeWord(double valueLeft) {
        return sizeWords.get(size(valueLeft));
    }

    /** Is it night (Minecraft time 13000 .. 23000), when a school is seen from nearer? */
    public static boolean night(long time) {
        long t = Math.floorMod(time, 24000L);
        return t >= 13000 && t < 23000;
    }

    /** How far a school is seen from at this time of day. */
    public double see(long time) {
        return night(time) ? seeNight : seeDay;
    }

    /** Does a school come up in this biome (its key, "minecraft:ocean")? */
    public boolean biome(String key) {
        String k = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        for (String b : biomes) if (k.contains(b)) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Ways (the board's compass words, the detector's arrow)
    // ------------------------------------------------------------------------------------------

    /** The compass way from a place to (dx, dz) away: 0 north (-z), 1 north-east, 2 east (+x), ... 7 north-west. */
    public static int compass(double dx, double dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        return Math.floorMod((int) Math.round(a / 45.0), 8);
    }

    /**
     * The detector's arrow to (dx, dz) away for a boat heading `yaw` (Minecraft: 0 south, 90 west): 0 ahead, 1 ahead right,
     * 2 right, ... 4 behind, ... 7 ahead left.
     */
    public static int arrow(float yaw, double dx, double dz) {
        double to = Math.toDegrees(Math.atan2(-dx, dz));   // the yaw that would face it
        double rel = to - yaw;
        return Math.floorMod((int) Math.round(rel / 45.0), 8);
    }

    public static final List<String> ARROWS = List.of("↑", "↗", "→", "↘", "↓", "↙", "←", "↖");

    // ------------------------------------------------------------------------------------------
    // The income model (README "어군 조업"; SchoolSpecTest checks the content against its targets)
    // ------------------------------------------------------------------------------------------

    /**
     * Hauls an hour of a boat working schools all hour: its net comes up every `seconds` on average (core vehicle_tools.yml net.seconds)
     * and `spot` times in one place (48 blocks, net.spot) before it has to move: a school of its size is worked out in spot x seconds,
     * then `travel` seconds to the next one (the detector finds it, the net goes down again).
     */
    public static double haulsPerHour(double seconds, int spot, double travel) {
        return spot * 3600.0 / (spot * seconds + travel);
    }

    /** G (base price) an hour: hauls an hour x the boat's haul x the sea's bonus. */
    public static double hourly(double seconds, int spot, double travel, double haul, double bonus) {
        return haulsPerHour(seconds, spot, travel) * haul * bonus;
    }
}
