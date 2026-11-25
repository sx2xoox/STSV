package dev.stsv.economy.fishing;

import dev.stsv.economy.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The events at sea (README "사건"), from outside the fish schools' own code: 태풍 (school-factor 0) stops every net at sea
 * ({@link FishingModule}'s net hook hauls nothing), 만선 (school-factor 2) makes extra schools beside {@link SchoolModule}'s own,
 * as many again, as often again. EventModule sets the factor once a second ({@link #tick}).
 */
public final class SeaEvents {

    private static volatile double factor = 1;
    /** the extra schools made for 만선, by world (they scatter on their own like any other) */
    private static final Map<String, List<SchoolModule.School>> extra = new HashMap<>();
    private static final Map<String, Long> lastExtra = new HashMap<>();

    private SeaEvents() {}

    /** 태풍: no net hauls at sea. */
    public static boolean stormy() {
        return factor <= 0;
    }

    /** How many extra schools a world may have at once for this factor (만선 2: as many again as fish.yml allows). */
    public static int extraRoom(double factor, int maxPerWorld) {
        return factor <= 1 ? 0 : (int) Math.floor(maxPerWorld * (factor - 1));
    }

    /** Once a second: the factor of the running events; while it is over 1, an extra school now and then near boats and harbours. */
    public static void tick(EcoPlugin eco, double f, long now) {
        factor = f;
        SchoolModule schools = eco.schools();
        if (f <= 1 || schools == null || !schools.spec().enabled()) {
            extra.clear();
            return;
        }
        SchoolSpec spec = schools.spec();
        int room = extraRoom(f, spec.maxPerWorld());
        long every = (long) (spec.spawnEvery() * 1000L / (f - 1));
        for (World w : Bukkit.getWorlds()) {
            if (w.getPlayers().isEmpty()) continue;
            List<SchoolModule.School> mine = extra.computeIfAbsent(w.getName(), k -> new ArrayList<>());
            mine.removeIf(s -> s.until < now || s.left <= 0);
            if (mine.size() >= room || now - lastExtra.getOrDefault(w.getName(), 0L) < every) continue;
            lastExtra.put(w.getName(), now);
            SchoolModule.School s = schools.spawn(w, null);
            if (s != null) mine.add(s);
        }
    }

    /** The plugin stops: back to calm seas. */
    public static void clear() {
        factor = 1;
        extra.clear();
        lastExtra.clear();
    }
}
