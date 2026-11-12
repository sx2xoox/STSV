package dev.stsv.economy.road;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Every saved road (roads/&lt;dock&gt;.txt and its patch &lt;dock&gt;.tail.txt) read once, by world then dock: what the snow
 * guard, the other roads' network and the repairs (/관리자 국도 터널보수, 난간고치기) go by. A file that cannot be read is
 * left out on its own (named in {@link #unreadable}, warned of) and never takes the others with it; previews are not roads.
 * Pure (unit-tested: RoadTunnelRepairTest).
 */
final class SavedRoads {

    /** world -&gt; dock -&gt; {its road, its patch} (either may be null) */
    final Map<String, Map<String, RoadJob[]>> byWorld = new TreeMap<>();
    /** the files that could not be read (or are not a road: no dock or world) */
    final List<String> unreadable = new ArrayList<>();
    /** how many road files were read (previews left out) */
    int files;

    /** The roads in these files (file name -&gt; text, as {@link JobFiles#named} lists them). */
    static SavedRoads of(Map<String, String> named, Consumer<String> warn) {
        SavedRoads out = new SavedRoads();
        for (Map.Entry<String, String> e : named.entrySet()) {
            RoadJob o;
            try {
                o = RoadJob.read(e.getValue());
            } catch (RuntimeException ex) {
                out.unreadable.add(e.getKey());
                warn.accept("[국도] 길 파일을 읽지 못함 (건너뜀): " + e.getKey() + ": " + ex);
                continue;
            }
            if (o.preview) continue;
            if (o.dock == null || o.dock.isBlank() || o.world == null || o.world.isBlank()) {
                out.unreadable.add(e.getKey());
                warn.accept("[국도] 길 파일에 하역장이나 월드가 없음 (건너뜀): " + e.getKey());
                continue;
            }
            out.files++;
            RoadJob[] pair = out.byWorld.computeIfAbsent(o.world, k -> new TreeMap<>()).computeIfAbsent(o.dock, k -> new RoadJob[2]);
            int at = o.tail ? 1 : 0;
            if (pair[at] == null || more(o, pair[at])) pair[at] = o;   // two files of one dock (a copy left by the move): the one built further
        }
        return out;
    }

    /** Whether a is more of a road than b (finished before not, then further built). */
    static boolean more(RoadJob a, RoadJob b) {
        if (a.removed != b.removed) return !a.removed;
        boolean da = a.phase == RoadJob.Phase.DONE, db = b.phase == RoadJob.Phase.DONE;
        if (da != db) return da;
        return a.cursor > b.cursor;
    }

    /** How many saved roads (a road and its patch: one). */
    int roads() {
        int n = 0;
        for (Map<String, RoadJob[]> m : byWorld.values()) n += m.size();
        return n;
    }

    /**
     * Every road built in each world: what of each road and each patch stands (finished or not: {@link RoadModule#built}),
     * undone ones left out. What snow keeps off and what a new road keeps clear of. except: a dock whose roads are left out
     * (the road being planned), or null.
     */
    Map<String, List<RoadNetwork.Road>> built(String except) {
        Map<String, List<RoadNetwork.Road>> out = new HashMap<>();
        byWorld.forEach((w, docks) -> docks.forEach((dock, pair) -> {
            if (dock.equals(except)) return;
            for (RoadJob j : pair) {
                if (j == null || j.removed) continue;
                RoadNetwork.Road r = RoadModule.built(j);
                if (r != null) out.computeIfAbsent(w, k -> new ArrayList<>()).add(r);
            }
        }));
        return out;
    }
}
