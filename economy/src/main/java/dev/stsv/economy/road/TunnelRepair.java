package dev.stsv.economy.road;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * /관리자 국도 터널보수: which saved roads have tunnels to mend, as they were built, and why the others are left out (README
 * "국도", 터널보수). Pure (unit-tested: RoadTunnelRepairTest).
 *
 * <p>A road counts as soon as any of it is built: a finished road, one stopped or cut short while building (what of it is
 * built), its patch (/관리자 국도 이어짓기) in place of its last stretch. Its parts are the ones the builder recorded
 * ({@link RoadJob#parts}; worked out again only for a road saved before they were). Its dock need not stand any more (a
 * dock renamed or removed leaves its road in the world), and a road whose world is not on this server (the world renamed
 * moving the server) is still listed, with that reason.
 */
final class TunnelRepair {

    private TunnelRepair() {}

    /** A built road (or what of it is built) as the builder made it: what a repair goes by. */
    record Road(String world, String dock, List<double[]> points, int[] halves, RoadProfile.Part[] parts, int route) {

        /** How many points are in a tunnel. */
        int tunnelPoints() {
            int n = 0;
            for (RoadProfile.Part p : parts) if (p == RoadProfile.Part.TUNNEL) n++;
            return n;
        }

        /** How many tunnels (runs of tunnel points). */
        int tunnels() {
            int n = 0;
            for (int i = 0; i < parts.length; i++) if (parts[i] == RoadProfile.Part.TUNNEL && (i == 0 || parts[i - 1] != RoadProfile.Part.TUNNEL)) n++;
            return n;
        }

        /** How many points of it are built. */
        int length() {
            return points.size();
        }

        /** The same road in another world (the one it was saved in is not on this server: renamed). */
        Road in(String w) {
            return new Road(w, dock, points, halves, parts, route);
        }

        /** Its tunnel points within r blocks (across) of (x, z). */
        List<Integer> tunnelsNear(double x, double z, double r) {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] != RoadProfile.Part.TUNNEL) continue;
                double[] p = points.get(i);
                if (Math.hypot(p[0] - x, p[1] - z) <= r) out.add(i);
            }
            return out;
        }
    }

    /** Why a saved road is (or is not) mended. */
    enum Why {
        /** it has tunnels: mended */
        OK("ok"),
        /** built, without a tunnel */
        NO_TUNNEL("no-tunnel"),
        /** nothing of it built yet (planned, being surveyed) */
        NOT_BUILT("not-built"),
        /** being built right now: the builder makes its tunnels high enough itself */
        RUNNING("running"),
        /** undone (되돌리기) */
        REMOVED("removed"),
        /** the ground under its points is not all saved */
        NO_GROUND("no-ground"),
        /** its world is not on this server */
        NO_WORLD("no-world");

        /** its words: road.tunnel-why.&lt;key&gt; */
        final String key;

        Why(String key) {
            this.key = key;
        }
    }

    /**
     * A saved road: its world, dock, phase, how much of it is built (percent), why, the road to mend (null: nothing built),
     * whether its parts are the ones the builder recorded.
     */
    record Entry(String world, String dock, RoadJob.Phase phase, int pct, Why why, Road road, boolean recorded) {}

    /** How many saved roads, finished, partly built, with tunnels (to mend), and files that could not be read. */
    record Count(int saved, int done, int partial, int tunnels, int unreadable) {}

    /**
     * Every saved road (by world, then dock: its job and its patch or null), why it is or is not mended. running: the dock
     * of the road being built now, or null; world: whether a world is on this server.
     */
    static List<Entry> select(Map<String, Map<String, RoadJob[]>> saved, String running, Predicate<String> world) {
        List<Entry> out = new ArrayList<>();
        for (Map<String, RoadJob[]> byDock : saved.values()) for (RoadJob[] pair : byDock.values()) out.add(entry(pair[0], pair[1], running, world));
        return out;
    }

    /** One saved road and its patch (either may be null, not both). */
    static Entry entry(RoadJob main, RoadJob tail, String running, Predicate<String> world) {
        RoadJob j = main != null ? main : tail;
        int pct = (int) Math.round(j.progress() * 100);
        if (j.phase == RoadJob.Phase.DONE) pct = 100;
        if (main == null) return new Entry(j.world, j.dock, j.phase, pct, Why.NOT_BUILT, null, false);
        if (main.removed) return new Entry(main.world, main.dock, main.phase, 0, Why.REMOVED, null, false);
        if (running != null && running.equals(main.dock)) return new Entry(main.world, main.dock, main.phase, pct, Why.RUNNING, null, false);
        boolean building = main.phase == RoadJob.Phase.BUILD || main.phase == RoadJob.Phase.DONE;
        Road r = building ? road(main, tail) : null;
        if (r == null) {
            Why why = building && (main.phase == RoadJob.Phase.DONE || main.cursor > 0) ? Why.NO_GROUND : Why.NOT_BUILT;
            return new Entry(main.world, main.dock, main.phase, building ? pct : 0, why, null, false);
        }
        boolean recorded = main.parts != null && main.parts.length == main.ground.size();
        if (r.tunnelPoints() == 0) return new Entry(main.world, main.dock, main.phase, pct, Why.NO_TUNNEL, r, recorded);
        if (!world.test(main.world)) return new Entry(main.world, main.dock, main.phase, pct, Why.NO_WORLD, r, recorded);
        return new Entry(main.world, main.dock, main.phase, pct, Why.OK, r, recorded);
    }

    /**
     * What of a road is built, as it was built: its points up to where building got, their heights and parts (recorded, or
     * worked out again for an older road), its patch in place of its last stretch once the road is finished. Null: nothing
     * of it built (or its ground not saved).
     */
    static Road road(RoadJob main, RoadJob tail) {
        RoadNetwork.Road m = RoadModule.built(main);
        if (m == null) return null;
        int upTo = m.points().size();
        RoadProfile.Part[] parts = java.util.Arrays.copyOf(RoadModule.partsOf(main), upTo);
        RoadModule.Spliced s = new RoadModule.Spliced(m.points(), m.halves(), parts);
        // its patch once that is finished too (a patch cut short: the road as it was, its old end still standing)
        RoadNetwork.Road t = tail != null && main.phase == RoadJob.Phase.DONE && tail.phase == RoadJob.Phase.DONE && !tail.removed ? RoadModule.built(tail) : null;
        if (t != null) s = RoadModule.splice(s, t, java.util.Arrays.copyOf(RoadModule.partsOf(tail), t.points().size()));
        return new Road(main.world, main.dock, s.points(), s.halves(), s.parts(), main.route);
    }

    /** The counts of a selection (unreadable: the files that could not be read). */
    static Count count(Collection<Entry> entries, int unreadable) {
        int done = 0, partial = 0, tunnels = 0;
        for (Entry e : entries) {
            if (e.why() == Why.REMOVED) continue;
            if (e.phase() == RoadJob.Phase.DONE) done++;
            else if (e.road() != null) partial++;
            if (e.road() != null && e.road().tunnelPoints() > 0) tunnels++;
        }
        return new Count(entries.size(), done, partial, tunnels, unreadable);
    }
}
