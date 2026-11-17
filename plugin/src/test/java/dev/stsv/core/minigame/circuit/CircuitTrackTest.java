package dev.stsv.core.minigame.circuit;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled circuits (content/stsv/circuit/tracks): each reads, is long enough and never runs into itself, is built of
 * blocks that exist, has its start grid on the road behind the line, a barrier all round, and its hand-placed props off
 * the road. Writes build/circuit/&lt;track&gt;.json for tools/assetgen/circuit_preview.py (the overhead and 3D previews).
 */
class CircuitTrackTest {

    static final Path TRACKS = Path.of(System.getProperty("stsv.content")).resolve("stsv/circuit/tracks");

    static List<TrackSpec> tracks() throws Exception {
        List<TrackSpec> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(TRACKS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                String id = f.getFileName().toString().replace(".yml", "");
                out.add(TrackSpec.parse(id, YamlConfiguration.loadConfiguration(f.toFile())));
            }
        }
        return out;
    }

    @Test
    void theBundledTracksReadAndDiffer() throws Exception {
        List<TrackSpec> tracks = tracks();
        assertTrue(tracks.size() >= 3 && tracks.size() <= 5, "3 ~ 5 tracks: " + tracks.size());
        Set<Integer> slots = new HashSet<>();
        Set<String> themes = new HashSet<>();
        for (TrackSpec t : tracks) {
            assertTrue(slots.add(t.slot()), t.id() + ": a slot of its own");
            themes.add(t.themeId());
            double len = t.path().length();
            assertTrue(len > 600 && len < 1400, t.id() + ": a lap of 600 ~ 1400 blocks: " + len);
            assertTrue(t.features().stream().anyMatch(f -> f.type() == TrackSpec.FeatureType.JUMP), t.id() + " has a jump");
        }
        assertEquals(tracks.size(), themes.size(), "a theme each");
    }

    @Test
    void theLayoutsAreSoundAndWrittenForThePreview() throws Exception {
        Set<String> vanilla = new HashSet<>();
        try (InputStream in = getClass().getResourceAsStream("/vanilla-items-26.3.txt")) {
            vanilla.addAll(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")));
        }
        vanilla.addAll(List.of("water", "lava", "air"));
        Path out = Path.of("build/circuit");
        Files.createDirectories(out);
        for (TrackSpec t : tracks()) {
            TrackLayout l = new TrackLayout(t);
            assertEquals(List.of(), l.skippedDecor(), t.id() + ": hand-placed props on the road");
            Set<String> unknown = new HashSet<>();
            l.forEach((x, y, z, data) -> {
                String id = data.replace("minecraft:", "");
                if (id.contains("[")) id = id.substring(0, id.indexOf('['));
                if (!vanilla.contains(id)) unknown.add(id);
            });
            assertEquals(Set.of(), unknown, t.id() + ": blocks that do not exist");
            assertTrue(l.blockCount() > 20_000, t.id() + " is built of many blocks: " + l.blockCount());
            // the grid: 12 slots, on the road, behind the line, facing the way of the road
            TrackPath p = l.path();
            assertEquals(12, l.grid().size());
            for (TrackLayout.Slot s : l.grid()) {
                TrackPath.Spot spot = p.locate(s.x(), s.y(), s.z(), -1, 0);
                assertTrue(Math.abs(spot.lateral()) < p.width(spot.index()) / 2 - 1, t.id() + ": slot on the road");
                assertTrue(p.s(spot.index()) > p.length() / 2, t.id() + ": slot behind the line");
            }
            // a barrier on both sides all the way round (except over a jump's gap and in a tunnel)
            for (int i = 0; i < p.size(); i += 20) {
                if (l.gap(i)) continue;
                for (int side : new int[]{-1, 1}) {
                    boolean found = false;
                    int bx = 0, bz = 0;
                    for (double d = p.width(i) / 2 + 2.0; d <= p.width(i) / 2 + 3.7; d += 0.2) {
                        bx = (int) Math.floor(p.x(i) - p.dirZ(i) * side * d);
                        bz = (int) Math.floor(p.z(i) + p.dirX(i) * side * d);
                        // a wall, a fence, a tunnel's side or the gantry's pillar: something standing above the road's top
                        for (int y = (int) Math.ceil(l.height(i)); y <= (int) Math.ceil(l.height(i)) + 2; y++) {
                            String b = l.at(bx, y, bz);
                            if (b != null && !b.equals("minecraft:air")) found = true;
                        }
                    }
                    if (!found) {
                        StringBuilder col = new StringBuilder();
                        for (double d = p.width(i) / 2 - 1; d <= p.width(i) / 2 + 4; d += 0.5) {
                            int cx = (int) Math.floor(p.x(i) - p.dirZ(i) * side * d), cz = (int) Math.floor(p.z(i) + p.dirX(i) * side * d);
                            col.append(" | d=").append(d).append(" (").append(cx).append(',').append(cz).append(") ");
                            for (int y = -2; y <= 12; y++) if (l.at(cx, y, cz) != null) col.append(y).append('=').append(l.at(cx, y, cz)).append(' ');
                        }
                        org.junit.jupiter.api.Assertions.fail(t.id() + ": barrier at " + Math.round(p.s(i)) + " side " + side + " h " + l.height(i) + col);
                    }
                }
            }
            write(l, out.resolve(t.id() + ".json"));
        }
    }

    /** The preview's data: the field (its top per column) and every block, compactly. */
    private static void write(TrackLayout l, Path file) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"").append(l.spec().id()).append("\",\"name\":\"").append(l.spec().name()).append("\",\"theme\":\"")
                .append(l.spec().themeId()).append("\",\"bounds\":[").append(l.minX()).append(',').append(l.maxX()).append(',')
                .append(l.minZ()).append(',').append(l.maxZ()).append("],\"ground\":{");
        java.util.Map<String, List<Integer>> g = new java.util.LinkedHashMap<>();
        for (int x = l.minX(); x <= l.maxX(); x++) for (int z = l.minZ(); z <= l.maxZ(); z++) {
            g.computeIfAbsent(l.ground(x, z), k -> new ArrayList<>()).add((x - l.minX()) * 4096 + (z - l.minZ()));
        }
        boolean first = true;
        for (var e : g.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        sb.append("},\"blocks\":{");
        java.util.Map<String, StringBuilder> byData = new java.util.LinkedHashMap<>();
        l.forEach((x, y, z, data) -> byData.computeIfAbsent(data, k -> new StringBuilder()).append(x).append(',').append(y).append(',').append(z).append(','));
        first = true;
        for (var e : byData.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            String v = e.getValue().toString();
            sb.append('"').append(e.getKey().replace("\"", "")).append("\":[").append(v, 0, v.length() - 1).append(']');
        }
        sb.append("},\"grid\":[");
        for (int k = 0; k < l.grid().size(); k++) {
            TrackLayout.Slot s = l.grid().get(k);
            sb.append(k == 0 ? "" : ",").append('[').append(s.x()).append(',').append(s.y()).append(',').append(s.z()).append(',').append(s.yaw()).append(']');
        }
        sb.append("],\"path\":[");
        TrackPath p = l.path();
        for (int i = 0; i < p.size(); i += 4) sb.append(i == 0 ? "" : ",").append('[').append(Math.round(p.x(i) * 10) / 10.0).append(',')
                .append(Math.round(p.z(i) * 10) / 10.0).append(',').append(l.height(i)).append(']');
        sb.append("]}");
        Files.writeString(file, sb.toString());
    }

    @Test
    void brokenTracksAreRefusedWhenRead() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("name", "x");
        c.set("slot", 9);
        c.set("points", List.of(List.of(0, 0), List.of(100, 0), List.of(100, 100), List.of(0, 100)));
        assertThrows(IllegalArgumentException.class, () -> TrackSpec.parse("x", c), "four points are too few");
        // a pinched loop: two straights 8 blocks apart
        c.set("points", List.of(List.of(-150, 0), List.of(-50, 0), List.of(50, 0), List.of(150, 0), List.of(150, 8), List.of(50, 8),
                List.of(-50, 8), List.of(-150, 8)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TrackSpec.parse("x", c));
        assertTrue(e.getMessage().contains("가까움") || e.getMessage().contains("짧음"), e.getMessage());
        // too steep: 6 blocks up over one point
        c.set("points", List.of(List.of(-150, -60), List.of(0, -60, 0), List.of(20, -60, 8), List.of(150, -60), List.of(150, 60),
                List.of(-150, 60)));
        e = assertThrows(IllegalArgumentException.class, () -> TrackSpec.parse("x", c));
        assertTrue(e.getMessage().contains("가파름"), e.getMessage());
        c.set("points", List.of(List.of(-150, -60), List.of(0, -60), List.of(150, -60), List.of(150, 60), List.of(0, 60), List.of(-150, 60)));
        assertNotNull(TrackSpec.parse("x", c));
        c.set("theme", "moon");
        assertThrows(IllegalArgumentException.class, () -> TrackSpec.parse("x", c));
        c.set("theme", "city");
        c.set("decor", List.of(java.util.Map.of("kind", "castle", "at", List.of(0, 0))));
        assertThrows(IllegalArgumentException.class, () -> TrackSpec.parse("x", c));
    }

    @Test
    void theCentreLineIsEvenAndFindsKartsNearWhereTheyWere() {
        List<TrackPath.Point> pts = List.of(new TrackPath.Point(-100, -50, 0, 12, false), new TrackPath.Point(100, -50, 0, 12, false),
                new TrackPath.Point(120, 0, 0, 12, false), new TrackPath.Point(100, 50, 0, 12, false), new TrackPath.Point(-100, 50, 0, 12, false),
                new TrackPath.Point(-120, 0, 0, 12, false));
        TrackPath p = new TrackPath(pts);
        for (int i = 0; i < p.size(); i++) {
            double step = Math.hypot(p.x(i + 1) - p.x(i), p.z(i + 1) - p.z(i));
            assertTrue(Math.abs(step - TrackPath.STEP) < 0.05, "even steps: " + step);
        }
        // the first point is the start, the road runs towards the second (+x: yaw -90)
        assertEquals(-100, p.x(0), 0.01);
        assertEquals(-90, p.yaw(0), 20);
        // a point 3 blocks to the right of the start (south when heading east)
        TrackPath.Spot s = p.locate(p.x(0) - p.dirZ(0) * 3, 0, p.z(0) + p.dirX(0) * 3, 0, 20);
        assertEquals(0, s.index());
        assertEquals(3, s.lateral(), 0.01);
        assertEquals(p.size() - 1, p.ahead(1, 0));
        assertEquals(-1, p.delta(1, 0));
        assertNull(TrackSpec.tooClose(p));
    }
}
