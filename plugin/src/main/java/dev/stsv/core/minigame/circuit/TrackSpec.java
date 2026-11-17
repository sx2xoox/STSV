package dev.stsv.core.minigame.circuit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One circuit as data (contents/&lt;pack&gt;/circuit/tracks/&lt;id&gt;.yml, explained at the top of those files): its name and
 * theme, where in the minigame world it is ({@code slot}: tracks are 1024 blocks apart), the centre line's control
 * points, laps, checkpoints and what is on and about it. Pure (unit-tested): {@link #parse} checks everything a track
 * needs, so a broken file is reported when it is read, never half built.
 *
 * @param points      the centre line ({@link TrackPath.Point}), driven in their order, the start / finish line at the first
 * @param checkpoints gates along a lap that must be passed in order (evenly spaced, the first is the finish line)
 * @param features    jumps, boost pads and tunnels, at shares of the lap (0 .. 1 from the finish line)
 * @param decor       props placed by hand (houses, trees ...: {@link TrackLayout} kinds) at track coordinates
 * @param scatter     how many of the theme's props are strewn about the field (seeded: the same every build)
 */
public record TrackSpec(String id, String name, String subtitle, String themeId, TrackTheme theme, int slot, int laps, int checkpoints,
                        double width, List<TrackPath.Point> points, List<Feature> features, List<Decor> decor, int scatter,
                        int difficulty, String accent) {

    /** Blocks between two track areas along x in the minigame world. */
    public static final int SLOT_SPACING = 1024;
    /** The farthest a control point may lie from the track's centre (the area is 2 x this + the field's margin wide). */
    public static final int MAX_REACH = 180;
    public static final int MIN_LAPS = 1, MAX_LAPS = 9;

    public enum FeatureType { JUMP, BOOST, TUNNEL }

    /** at / to: shares of the lap; power: a jump's lift (blocks a tick upward) or a boost pad's top speed factor; gap: a jump's gap. */
    public record Feature(FeatureType type, double at, double to, double power, int gap) {}

    /** A prop: kind (TrackLayout), at track (x, z), turned yaw (0, 90, 180, 270), size 1 .. 3. */
    public record Decor(String kind, int x, int z, int yaw, int size) {}

    public TrackPath path() {
        return new TrackPath(points);
    }

    public static TrackSpec parse(String id, ConfigurationSection c) {
        if (!id.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("트랙 id 는 영문 소문자·숫자·_ (파일 이름): " + id);
        String name = c.getString("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 이 없음");
        String themeId = c.getString("theme", "village");
        Map<String, String> blocks = new LinkedHashMap<>();
        ConfigurationSection b = c.getConfigurationSection("blocks");
        if (b != null) for (String k : b.getKeys(false)) blocks.put(k, b.getString(k));
        TrackTheme theme = TrackTheme.of(themeId).with(blocks);
        int slot = c.getInt("slot", -1);
        if (slot < 0 || slot > 63) throw new IllegalArgumentException("slot 은 0 ~ 63 (트랙마다 다르게: 미니게임 월드에서 " + SLOT_SPACING + "블록씩 떨어진 자리)");
        int laps = c.getInt("laps", 3);
        if (laps < MIN_LAPS || laps > MAX_LAPS) throw new IllegalArgumentException("laps 는 " + MIN_LAPS + " ~ " + MAX_LAPS);
        double width = c.getDouble("width", 11);
        if (width < 6 || width > 24) throw new IllegalArgumentException("width 는 6 ~ 24 (블록)");
        List<TrackPath.Point> points = new ArrayList<>();
        for (Object o : c.getList("points", List.of())) points.add(point(o, width));
        if (points.size() < 6) throw new IllegalArgumentException("points 는 6개 이상");
        for (TrackPath.Point p : points) {
            if (Math.abs(p.x()) > MAX_REACH || Math.abs(p.z()) > MAX_REACH)
                throw new IllegalArgumentException("points: 중심에서 " + MAX_REACH + "블록 안쪽 (" + p.x() + ", " + p.z() + ")");
            if (p.y() < 0 || p.y() > 24) throw new IllegalArgumentException("points: 높이(y)는 0 ~ 24");
            if (p.width() < 6 || p.width() > 24) throw new IllegalArgumentException("points: 폭은 6 ~ 24");
        }
        TrackPath path = new TrackPath(points);
        if (path.length() < 250) throw new IllegalArgumentException("한 바퀴가 너무 짧음 (" + Math.round(path.length()) + "블록, 250 이상)");
        String close = tooClose(path);
        if (close != null) throw new IllegalArgumentException(close);
        int checkpoints = c.getInt("checkpoints", Math.max(6, (int) (path.length() / 60)));
        if (checkpoints < 4 || checkpoints > 64) throw new IllegalArgumentException("checkpoints 는 4 ~ 64");
        // the road never climbs or drops more than a block every three (the karts step half blocks)
        for (int i = 0; i < path.size(); i++) {
            double rise = Math.abs(path.y(i + 6) - path.y(i));
            if (rise > 1.0 + 1e-9) throw new IllegalArgumentException("너무 가파름 (" + Math.round(path.s(i)) + "블록 지점): 3블록에 1블록까지");
        }
        List<Feature> features = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("features")) {
            ConfigurationSection f = section(m);
            FeatureType type;
            try {
                type = FeatureType.valueOf(f.getString("type", "").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("features.type 은 jump, boost, tunnel");
            }
            double at = f.getDouble("at", -1), to = f.getDouble("to", at);
            if (at <= 0.02 || at >= 0.98 || to < at || to >= 0.98) throw new IllegalArgumentException("features.at / to 는 0.02 ~ 0.98 (바퀴의 몫), to ≥ at");
            double power = f.getDouble("power", type == FeatureType.JUMP ? 0.55 : 1.35);
            if (type == FeatureType.JUMP && (power < 0.3 || power > 0.9)) throw new IllegalArgumentException("jump.power 는 0.3 ~ 0.9");
            if (type == FeatureType.BOOST && (power < 1.1 || power > 1.6)) throw new IllegalArgumentException("boost.power 는 1.1 ~ 1.6");
            int gap = f.getInt("gap", type == FeatureType.JUMP ? 6 : 0);
            if (gap < 0 || gap > 12) throw new IllegalArgumentException("jump.gap 은 0 ~ 12");
            features.add(new Feature(type, at, to, power, gap));
        }
        List<Decor> decor = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("decor")) {
            ConfigurationSection d = section(m);
            List<?> at = d.getList("at", List.of());
            if (at.size() != 2) throw new IllegalArgumentException("decor.at 은 [x, z]");
            String kind = d.getString("kind", "");
            if (!TrackLayout.PROPS.contains(kind)) throw new IllegalArgumentException("decor.kind 를 모름: " + kind + " " + TrackLayout.PROPS);
            int yaw = Math.floorMod(d.getInt("yaw", 0), 360);
            if (yaw % 90 != 0) throw new IllegalArgumentException("decor.yaw 는 0, 90, 180, 270");
            decor.add(new Decor(kind, num(at.get(0)), num(at.get(1)), yaw, Math.clamp(d.getInt("size", 2), 1, 3)));
        }
        int scatter = c.getInt("scatter", 120);
        if (scatter < 0 || scatter > 600) throw new IllegalArgumentException("scatter 는 0 ~ 600");
        int difficulty = Math.clamp(c.getInt("difficulty", 2), 1, 5);
        return new TrackSpec(id, name, c.getString("subtitle", ""), themeId, theme, slot, laps, checkpoints, width, List.copyOf(points),
                List.copyOf(features), List.copyOf(decor), scatter, difficulty, c.getString("accent", "#ffd36b"));
    }

    /**
     * Two parts of the road that are far apart along the lap but near each other on the ground (and on one level): their
     * barriers would touch or the road of one would run into the other's (a shortcut). null when none.
     */
    static String tooClose(TrackPath path) {
        int n = path.size(), skip = (int) (40 / TrackPath.STEP);
        for (int i = 0; i < n; i += 2) {
            for (int j = i + skip; j < n; j += 2) {
                if (path.ahead(j, i) < skip) continue;
                if (Math.abs(path.y(i) - path.y(j)) >= 5) continue;   // an overpass
                double need = path.width(i) / 2 + path.width(j) / 2 + 6.4;
                double d = Math.hypot(path.x(i) - path.x(j), path.z(i) - path.z(j));
                if (d < need) return "도로가 너무 가까움: " + Math.round(path.s(i)) + "블록 지점과 " + Math.round(path.s(j)) + "블록 지점 ("
                        + String.format(java.util.Locale.ROOT, "%.1f", d) + "블록, " + String.format(java.util.Locale.ROOT, "%.1f", need) + " 이상)";
            }
        }
        return null;
    }

    private static TrackPath.Point point(Object o, double width) {
        if (o instanceof List<?> l) {
            if (l.size() < 2 || l.size() > 4) throw new IllegalArgumentException("points: [x, z] / [x, z, 높이] / [x, z, 높이, 폭]");
            return new TrackPath.Point(dbl(l.get(0)), dbl(l.get(1)), l.size() > 2 ? dbl(l.get(2)) : 0, l.size() > 3 ? dbl(l.get(3)) : width, false);
        }
        if (o instanceof Map<?, ?> m) {
            ConfigurationSection p = section(m);
            List<?> at = p.getList("at", List.of());
            if (at.size() != 2) throw new IllegalArgumentException("points: {at: [x, z], y, width, bridge}");
            return new TrackPath.Point(dbl(at.get(0)), dbl(at.get(1)), p.getDouble("y", 0), p.getDouble("width", width), p.getBoolean("bridge"));
        }
        throw new IllegalArgumentException("points 의 한 줄을 읽을 수 없음: " + o);
    }

    private static double dbl(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("숫자가 아님: " + o);
    }

    private static int num(Object o) {
        return (int) Math.round(dbl(o));
    }

    static ConfigurationSection section(Map<?, ?> m) {
        MemoryConfiguration c = new MemoryConfiguration();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> sub) c.set(String.valueOf(e.getKey()), section(sub));
            else c.set(String.valueOf(e.getKey()), v);
        }
        return c;
    }

    /** The world x of this track's centre (its z is 0). */
    public int originX() {
        return slot * SLOT_SPACING;
    }
}
