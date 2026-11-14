package dev.stsv.economy.fishing;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 원양 조업 (fish.yml {@code net:}): what a boat's net (the core's 작업기, vehicle_tools.yml kind net) brings up, and what
 * the catch is worth at a harbour. Pure: the numbers and the sums, nothing of the server.
 *
 * <p>A net does not bring up single fish but <b>crates of catch</b> (어획 상자): an item that only a harbour's fish auction
 * (항구 하역장의 위판) buys, from the hold of the boat that caught it. The sea is cut into zones by how far it is from the
 * nearest harbour: the further out, the more a crate is worth. Every boat's net has a sea it is made for (the 통통배 the
 * coast, the 연안 어선 the near sea, the 트롤 어선 the far sea): further out than that it still brings up crates of its own
 * sea. So a bigger boat earns more a trip, has to sail further for it, and has to come back to sell.
 *
 * @param zones     the seas, nearest first (the first starts at 0)
 * @param boats     boat item id ("stsv:trawler") -> the farthest zone its net works in (an index into zones)
 * @param crewBonus more crates for every rider beside the one who steers (they haul the net together): 0.08 = +8 % each
 * @param crewMax   how many riders count at most
 */
public record NetSpec(List<Zone> zones, Map<String, Integer> boats, double crewBonus, int crewMax) {

    /** No crates: the net brings up single fish as before (no {@code net:} section). */
    public static final NetSpec NONE = new NetSpec(List.of(), Map.of(), 0, 0);

    /**
     * One sea.
     *
     * @param id     its key in the file ("far")
     * @param name   its name ("원양")
     * @param from   how far from the nearest harbour it starts (blocks)
     * @param item   the crate its net brings up ("economy:catch_far")
     * @param value  what a harbour pays for one crate (gold)
     * @param weight what one crate weighs in the hold (payload units)
     */
    public record Zone(String id, String name, double from, String item, double value, double weight) {}

    public boolean enabled() {
        return !zones.isEmpty();
    }

    /** The zone a sea this far from the nearest harbour is (the farthest one that has begun), or null when there are none. */
    public Zone zoneAt(double distance) {
        Zone at = null;
        for (Zone z : zones) if (distance >= z.from()) at = z;
        return at;
    }

    /**
     * The crates this boat's net brings up this far out: the sea it is in, but never beyond the sea the net is made for.
     * null = this boat's net brings up no crates (not listed under boats).
     */
    public Zone zoneFor(String boat, double distance) {
        Integer best = boats.get(norm(boat));
        if (best == null || zones.isEmpty()) return null;
        Zone at = zoneAt(Math.max(0, distance));
        int i = at == null ? 0 : zones.indexOf(at);
        return zones.get(Math.min(i, best));
    }

    /** The sea a boat's net is made for, or null. */
    public Zone best(String boat) {
        Integer best = boats.get(norm(boat));
        return best == null || zones.isEmpty() ? null : zones.get(best);
    }

    /** How many crates one haul is worth with this many people aboard (the one who steers counts as 1.0): 1 + bonus x riders. */
    public double crew(int aboard) {
        return 1 + crewBonus * Math.clamp(aboard - 1, 0, Math.max(0, crewMax));
    }

    /** One haul's crates: the whole part of {@link #crew}, and one more by its fraction (roll in [0, 1)). */
    public int crates(int aboard, double roll) {
        double c = crew(aboard);
        int whole = (int) Math.floor(c);
        return whole + (roll < c - whole ? 1 : 0);
    }

    /** The zone a crate item belongs to, or null (not a crate). */
    public Zone ofItem(String item) {
        String id = norm(item);
        for (Zone z : zones) if (z.item().equals(id)) return z;
        return null;
    }

    /** What these crates fetch at a harbour (floored: the auction pays whole gold). */
    public long worth(Map<String, Integer> crates) {
        double sum = 0;
        for (Map.Entry<String, Integer> e : crates.entrySet()) {
            Zone z = ofItem(e.getKey());
            if (z != null) sum += z.value() * e.getValue();
        }
        return (long) Math.floor(sum + 1e-9);
    }

    // ------------------------------------------------------------------------------------------
    // The sums behind the numbers (the README's table, NetSpecTest): one trip of one boat
    // ------------------------------------------------------------------------------------------

    /**
     * One trip: sail out to the boat's own sea, trawl until the hold is full by weight, sail back, sell.
     *
     * @param crates   crates in a full hold
     * @param value    what they fetch (gold)
     * @param trawling minutes of trawling to fill it
     * @param sailing  minutes out and back (to where its sea begins)
     * @param perHour  gold an hour over the whole trip
     */
    public record Trip(int crates, long value, double trawling, double sailing, double perHour) {}

    /**
     * @param boat     the boat's item id
     * @param seconds  its net's net.seconds (one haul on average)
     * @param payload  what its hold carries
     * @param speed    its top speed (blocks/s): out and back at this
     * @param aboard   people aboard
     */
    public Trip trip(String boat, double seconds, double payload, double speed, int aboard) {
        Zone z = best(boat);
        if (z == null || seconds <= 0 || speed <= 0) return new Trip(0, 0, 0, 0, 0);
        int crates = (int) Math.floor(payload / z.weight() + 1e-9);
        long value = (long) Math.floor(crates * z.value() + 1e-9);
        double trawling = crates / crew(aboard) * seconds / 60.0;
        double sailing = 2 * z.from() / speed / 60.0;
        double hours = (trawling + sailing) / 60.0;
        return new Trip(crates, value, trawling, sailing, hours <= 0 ? 0 : value / hours);
    }

    static String norm(String id) {
        String s = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "stsv:" + s;
    }

    // ------------------------------------------------------------------------------------------
    // fish.yml net:
    // ------------------------------------------------------------------------------------------

    /** Reads {@code net:} (null or without zones = {@link #NONE}); what is wrong goes to problems and that line is left out. */
    public static NetSpec parse(ConfigurationSection c, List<String> problems) {
        if (c == null || !c.isConfigurationSection("zones")) return NONE;
        List<Zone> zones = new ArrayList<>();
        ConfigurationSection zs = c.getConfigurationSection("zones");
        for (String id : zs.getKeys(false)) {
            ConfigurationSection z = zs.getConfigurationSection(id);
            if (z == null) continue;
            String item = z.getString("item", "");
            double from = z.getDouble("from", 0), value = z.getDouble("value", 0), weight = z.getDouble("weight", 1);
            if (item.isBlank() || !item.contains(":")) {
                problems.add("net.zones." + id + ".item: 어획 상자 아이템 id (economy:catch_...)");
                continue;
            }
            if (from < 0 || value <= 0 || weight <= 0) {
                problems.add("net.zones." + id + ": from 은 0 이상, value · weight 는 0보다 커야 함");
                continue;
            }
            zones.add(new Zone(id, z.getString("name", id), from, item.trim().toLowerCase(Locale.ROOT), value, weight));
        }
        zones.sort(Comparator.comparingDouble(Zone::from));
        if (zones.isEmpty()) return NONE;
        if (zones.getFirst().from() > 0) {
            problems.add("net.zones: 가장 가까운 바다는 from: 0 이어야 함 (" + zones.getFirst().id() + " 을 0 으로 봄)");
            Zone f = zones.getFirst();
            zones.set(0, new Zone(f.id(), f.name(), 0, f.item(), f.value(), f.weight()));
        }
        for (int i = 1; i < zones.size(); i++) {
            if (zones.get(i).value() < zones.get(i - 1).value())
                problems.add("net.zones." + zones.get(i).id() + ": 더 먼 바다의 상자가 더 싸요 (" + zones.get(i).value() + " < " + zones.get(i - 1).value() + ")");
        }
        Map<String, Integer> boats = new LinkedHashMap<>();
        ConfigurationSection bs = c.getConfigurationSection("boats");
        if (bs != null) {
            for (String key : bs.getKeys(false)) {
                String zone = bs.getString(key, "");
                int at = -1;
                for (int i = 0; i < zones.size(); i++) if (zones.get(i).id().equals(zone)) at = i;
                if (at < 0) {
                    problems.add("net.boats." + key + ": 없는 바다 " + zone);
                    continue;
                }
                boats.put(norm(key.replace('.', ':')), at);
            }
        }
        double bonus = c.getDouble("crew-bonus", 0);
        int max = c.getInt("crew-max", 0);
        if (bonus < 0 || bonus > 1 || max < 0) {
            problems.add("net.crew-bonus 는 0 ~ 1, crew-max 는 0 이상");
            bonus = 0;
            max = 0;
        }
        return new NetSpec(List.copyOf(zones), Map.copyOf(boats), bonus, max);
    }
}
