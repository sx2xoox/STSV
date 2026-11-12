package dev.stsv.economy.road;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which roads /관리자 국도 터널보수, the snow guard and 난간고치기 go by ({@link TunnelRepair}, {@link SavedRoads}): every road
 * that was built, as the builder built it. Each case the live server ran into is first shown as the old selection
 * (finished roads whose dock still stands, their parts worked out again: {@link RoadModule#guideRoads}) leaving it out.
 */
class RoadTunnelRepairTest {

    static final int N = 100;

    /** A straight road along x (100 points), flat at 64; ground 64 but a hill (75) over points 40 .. 60: a tunnel there. */
    static RoadJob road(String dock, RoadJob.Phase phase, boolean hill) {
        RoadJob j = new RoadJob();
        j.dock = dock;
        j.world = "world";
        j.corners.add(new double[]{0.5, 0.5});
        j.corners.add(new double[]{N - 0.5, 0.5});
        j.startX = 0.5;
        j.startZ = 0.5;
        j.endX = N - 0.5;
        j.endZ = 0.5;
        assertEquals(N, j.points().size());
        for (int i = 0; i < N; i++) {
            RoadJob.Ground g = new RoadJob.Ground();
            g.ground = hill && i >= 40 && i <= 60 ? 75 : 64;
            g.surface = g.ground;
            j.ground.add(g);
        }
        j.halves = new int[N];
        java.util.Arrays.fill(j.halves, 128);
        j.phase = phase;
        j.running = false;
        j.cursor = phase == RoadJob.Phase.DONE ? N : 0;
        j.route = 3;
        return j;
    }

    static Map<String, Map<String, RoadJob[]>> saved(RoadJob... jobs) {
        Map<String, Map<String, RoadJob[]>> out = new java.util.TreeMap<>();
        for (RoadJob j : jobs) out.computeIfAbsent(j.world, k -> new java.util.TreeMap<>()).computeIfAbsent(j.dock, k -> new RoadJob[2])[j.tail ? 1 : 0] = j;
        return out;
    }

    static TunnelRepair.Entry only(Map<String, Map<String, RoadJob[]>> saved, String running) {
        List<TunnelRepair.Entry> es = TunnelRepair.select(saved, running, w -> w.equals("world"));
        assertEquals(1, es.size());
        return es.getFirst();
    }

    /** The old selection: finished roads whose dock stands, for the guide. */
    static List<RoadGuide.Road> old(Map<String, Map<String, RoadJob[]>> saved, Set<String> docks) {
        RoadModule.Homes homes = new RoadModule.Homes(List.of(), null, docks);
        return RoadModule.guideRoads(homes, "world", saved.getOrDefault("world", Map.of()));
    }

    @Test
    void aRoadWhoseDockIsGoneIsStillMended() {
        Map<String, Map<String, RoadJob[]>> s = saved(road("서해", RoadJob.Phase.DONE, true));
        assertEquals(List.of(), old(s, Set.of("동해")), "before: a road whose dock was renamed or removed was left out");
        TunnelRepair.Entry e = only(s, null);
        assertEquals(TunnelRepair.Why.OK, e.why());
        assertEquals(N, e.road().length());
        assertEquals(1, e.road().tunnels());
        assertEquals(21, e.road().tunnelPoints());
    }

    @Test
    void aRoadStoppedPartWayIsMendedAsFarAsItWasBuilt() {
        // stopped at protected land (or by /관리자 국도 중지, or the server moved mid-build): never finished, its tunnel stands
        RoadJob j = road("서해", RoadJob.Phase.BUILD, true);
        j.cursor = 55;
        Map<String, Map<String, RoadJob[]>> s = saved(j);
        assertEquals(List.of(), old(s, Set.of("서해")), "before: only finished roads");
        TunnelRepair.Entry e = only(s, null);
        assertEquals(TunnelRepair.Why.OK, e.why());
        assertEquals(55, e.road().length(), "only what is built");
        assertEquals(15, e.road().tunnelPoints());
        assertEquals(55, e.pct());
        // while it is being built, the builder makes its own tunnels
        assertEquals(TunnelRepair.Why.RUNNING, only(s, "서해").why());
        // nothing built yet
        j.cursor = 0;
        assertEquals(TunnelRepair.Why.NOT_BUILT, only(s, null).why());
        assertEquals(TunnelRepair.Why.NOT_BUILT, only(saved(road("서해", RoadJob.Phase.PLAN, true)), null).why());
    }

    @Test
    void thePartsTheBuilderRecordedWin() {
        // the builder's parts disagree with what the saved ground works out to (a road resumed after a restart with other
        // roads built meanwhile, an older ground reading): it built a tunnel, the ground says road
        RoadJob j = road("서해", RoadJob.Phase.DONE, false);
        Map<String, Map<String, RoadJob[]>> s = saved(j);
        TunnelRepair.Entry before = only(s, null);
        assertEquals(TunnelRepair.Why.NO_TUNNEL, before.why(), "without a record: worked out again, no tunnel");
        assertTrue(!before.recorded());
        j.parts = new RoadProfile.Part[N];
        for (int i = 0; i < N; i++) j.parts[i] = i >= 30 && i < 50 ? RoadProfile.Part.TUNNEL : RoadProfile.Part.ROAD;
        TunnelRepair.Entry e = only(s, null);
        assertEquals(TunnelRepair.Why.OK, e.why());
        assertTrue(e.recorded());
        assertEquals(20, e.road().tunnelPoints());
        assertEquals(RoadProfile.Part.TUNNEL, RoadModule.partsOf(j)[30]);
    }

    @Test
    void aWorldNotOnThisServerIsNamedNotDropped() {
        RoadJob j = road("서해", RoadJob.Phase.DONE, true);
        j.world = "world_old";
        List<TunnelRepair.Entry> es = TunnelRepair.select(saved(j), null, w -> w.equals("world"));
        assertEquals(TunnelRepair.Why.NO_WORLD, es.getFirst().why());
        assertNotNull(es.getFirst().road());
        assertEquals("world", es.getFirst().road().in("world").world(), "터널보수 여기 takes it as the admin's world");
    }

    @Test
    void undoneAndGroundlessRoadsSayWhy() {
        RoadJob gone = road("서해", RoadJob.Phase.DONE, true);
        gone.removed = true;
        assertEquals(TunnelRepair.Why.REMOVED, only(saved(gone), null).why());
        RoadJob short_ = road("동해", RoadJob.Phase.DONE, true);
        short_.ground.subList(50, N).clear();
        assertEquals(TunnelRepair.Why.NO_GROUND, only(saved(short_), null).why());
        RoadJob flat = road("남해", RoadJob.Phase.DONE, false);
        TunnelRepair.Count c = TunnelRepair.count(TunnelRepair.select(saved(gone, short_, flat, road("북해", RoadJob.Phase.BUILD, true)), null, w -> true), 2);
        assertEquals(new TunnelRepair.Count(4, 2, 0, 0, 2), c, "saved 4, built: the flat one and the groundless one finished, none with a tunnel");
    }

    @Test
    void aPatchStandsInForTheEndOnceItIsFinished() {
        RoadJob main = road("서해", RoadJob.Phase.DONE, true);
        RoadJob tail = new RoadJob();
        tail.dock = "서해";
        tail.world = "world";
        tail.tail = true;
        tail.corners.add(new double[]{N - 1 - RoadModule.TAIL_BACK + 0.5, 0.5});
        tail.corners.add(new double[]{N + 9.5, 0.5});
        int n = tail.points().size();
        for (int i = 0; i < n; i++) {
            RoadJob.Ground g = new RoadJob.Ground();
            g.ground = 64;
            tail.ground.add(g);
        }
        tail.halves = new int[n];
        java.util.Arrays.fill(tail.halves, 128);
        tail.phase = RoadJob.Phase.BUILD;
        tail.cursor = 3;
        assertEquals(N, only(saved(main, tail), null).road().length(), "a patch cut short: the road as it was");
        tail.phase = RoadJob.Phase.DONE;
        tail.cursor = n;
        TunnelRepair.Road r = only(saved(main, tail), null).road();
        assertEquals(N - 1 - RoadModule.TAIL_BACK + n, r.length());
        assertEquals(21, r.tunnelPoints(), "the tunnel is the main road's");
    }

    @Test
    void aFileThatCannotBeReadLeavesTheOthers() {
        RoadJob j = road("서해", RoadJob.Phase.DONE, true);
        j.parts = RoadModule.partsOf(j);
        // moved by copying: saved again by an editor (a byte order mark, Windows line ends)
        String moved = "﻿" + j.write().replace("\n", "\r\n");
        Map<String, String> named = new LinkedHashMap<>();
        named.put("가.txt", "dock=가\nworld=world\nphase=BUILDING\n");   // sorts first: the old reading stopped here
        named.put("서해.txt", moved);
        named.put("빈.txt", "\n");
        List<String> warned = new ArrayList<>();
        SavedRoads s = SavedRoads.of(named, warned::add);
        assertEquals(List.of("가.txt", "빈.txt"), s.unreadable);
        assertEquals(1, s.roads());
        assertEquals(2, warned.size());
        RoadJob back = s.byWorld.get("world").get("서해")[0];
        assertEquals("서해", back.dock);
        assertEquals("world", back.world);
        assertEquals(RoadJob.Phase.DONE, back.phase);
        assertArrayEquals(j.parts, back.parts);
        assertArrayEquals(j.halves, back.halves);
        assertEquals(TunnelRepair.Why.OK, only(s.byWorld, null).why());
        assertEquals(1, s.built(null).get("world").size(), "the snow guard has it too");
        assertNull(s.built("서해").get("world"));
    }

    @Test
    void twoFilesOfOneDockKeepTheOneBuiltFurther() {
        RoadJob done = road("서해", RoadJob.Phase.DONE, true), fresh = road("서해", RoadJob.Phase.SURVEY, true);
        Map<String, String> named = new LinkedHashMap<>();
        named.put("서해 - 복사본.txt", done.write());
        named.put("서해.txt", fresh.write());
        assertEquals(RoadJob.Phase.DONE, SavedRoads.of(named, m -> { }).byWorld.get("world").get("서해")[0].phase);
    }

    @Test
    void partsAreSavedWithTheRoad() {
        RoadProfile.Part[] p = {RoadProfile.Part.ROAD, RoadProfile.Part.TUNNEL, RoadProfile.Part.BRIDGE};
        assertEquals("RTB", RoadJob.partsText(p));
        assertArrayEquals(p, RoadJob.partsOf("RTB"));
        assertNull(RoadJob.partsOf("RXB"));
        // a road resumed after a restart keeps what of it stands as it was built
        assertArrayEquals(new int[]{1, 2, 7, 8}, RoadModule.kept(new int[]{1, 2, 3, 4}, new int[]{5, 6, 7, 8}, 2));
        assertArrayEquals(new int[]{5, 6, 7}, RoadModule.kept(new int[]{1, 2, 3, 4}, new int[]{5, 6, 7}, 2), "another route: all new");
        assertArrayEquals(new RoadProfile.Part[]{RoadProfile.Part.TUNNEL, RoadProfile.Part.ROAD},
                RoadModule.kept(new RoadProfile.Part[]{RoadProfile.Part.TUNNEL, RoadProfile.Part.TUNNEL}, new RoadProfile.Part[]{RoadProfile.Part.ROAD, RoadProfile.Part.ROAD}, 1));
    }

    @Test
    void jobFilesListEveryRoadButPreviewsAndTheOrigin(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("west.txt"), road("west", RoadJob.Phase.DONE, true).write());
        Files.writeString(dir.resolve("west.preview.txt"), "dock=west\n");
        Files.writeString(dir.resolve("origin.txt"), "world=world\n");
        Files.writeString(dir.resolve("west.undo"), "x");
        JobFiles f = new JobFiles(dir, m -> { });
        assertEquals(List.of("west.txt"), List.copyOf(f.named(RoadModule.ORIGIN).keySet()));
        f.close();
        assertEquals("서해.txt", JobFiles.decode("%EC%84%9C%ED%95%B4.txt"));
        assertEquals("a%zz+b.txt", JobFiles.decode("a%zz+b.txt"));
    }

    /**
     * The new host: the server started without a UTF-8 locale (LANG unset: the JVM's file names are ASCII). Road files
     * named after Korean docks, copied from the old host, are listed with names the JVM cannot turn back into paths; the
     * old reading threw on the first one and found no road at all (터널보수: "굴이 있는 다 지은 국도가 없어요", the snow
     * guard: no road). They are read by their own bytes now. (On a UTF-8 JVM the same files simply read as before.)
     */
    @Test
    void koreanFileNamesCopiedFromTheOldHostAreReadWhateverTheLocale(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "a shell makes the file by its bytes");
        String text = road("서해", RoadJob.Phase.DONE, true).write();
        Files.writeString(dir.resolve("road.tmp"), text);
        // 서해.txt by its UTF-8 bytes, as the old host left it (a JVM without UTF-8 cannot even name it)
        Process pr = new ProcessBuilder("/bin/sh", "-c", "mv road.tmp \"$(printf '\\354\\204\\234\\355\\225\\264').txt\"").directory(dir.toFile())
                .inheritIO().start();
        assertEquals(0, pr.waitFor());
        if (JobFiles.encodingProblem() != null) {   // the old reading: the name as listed back into a path
            String listed;
            try (var l = Files.list(dir)) {
                listed = l.findFirst().orElseThrow().getFileName().toString();
            }
            org.junit.jupiter.api.Assertions.assertThrows(java.nio.file.InvalidPathException.class, () -> dir.resolve(listed));
        }
        List<String> warned = new ArrayList<>();
        JobFiles f = new JobFiles(dir, warned::add);
        Map<String, String> named = f.named(RoadModule.ORIGIN);
        assertEquals(List.of("서해.txt"), List.copyOf(named.keySet()));
        assertEquals(text, named.get("서해.txt"));
        SavedRoads s = SavedRoads.of(named, warned::add);
        assertEquals(TunnelRepair.Why.OK, only(s.byWorld, null).why());
        assertEquals(text, f.read("서해.txt"), "found by its bytes");
        assertTrue(f.exists("서해.txt"));
        assertTrue(!f.exists("서해.undo"), "never an exception for a name the JVM cannot spell");
        // a new file: made where the JVM can name it, else kept in memory (warned, with the fix)
        f.write("동해.txt", () -> "dock=동해\nworld=world\n");
        f.close();
        assertEquals("dock=동해\nworld=world\n", f.read("동해.txt"));
        if (JobFiles.encodingProblem() != null) assertTrue(warned.stream().anyMatch(w -> w.contains("LANG=C.UTF-8")), String.join("\n", warned));
        else assertTrue(warned.isEmpty(), String.join("\n", warned));
    }

    @Test
    void everyReasonHasItsWords() throws Exception {
        YamlConfiguration m;
        try (var in = getClass().getResourceAsStream("/messages.yml")) {
            m = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (TunnelRepair.Why w : TunnelRepair.Why.values()) assertTrue(m.contains("road.tunnel-why." + w.key), w.key);
        assertTrue(m.contains("road.tunnel-why.guessed"));
    }
}
