package dev.stsv.core.emote;

import dev.stsv.core.emote.EmoteRig.Channel;
import dev.stsv.core.emote.EmoteRig.Pose;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * An emote as data (contents/&lt;pack&gt;/emotes.yml, explained at the top of that file): keyframes of joint angles
 * over a base pose, eased, looping or played once. The preview tool (tools/assetgen/emotes.py) reads the same file
 * the same way.
 *
 * @param length  ticks of one round
 * @param step    ticks between two poses sent to the clients (they blend in between)
 * @param planted the legs are solved so the feet stay on their spots ({@link EmoteRig#solveLeg})
 * @param props   small signs the puppet carries ({@link Prop})
 */
public record EmoteSpec(String id, String name, int length, int step, boolean loop, boolean planted, Camera camera,
                        List<Keyframe> keyframes, List<Prop> props) {

    public EmoteSpec(String id, String name, int length, int step, boolean loop, boolean planted, Camera camera, List<Keyframe> keyframes) {
        this(id, name, length, step, loop, planted, camera, keyframes, List.of());
    }

    /**
     * A sign the puppet carries: a little picture of one colour that moves with one of its joints and faces the way
     * the puppet faces (the yellow L held to the forehead). {@code grid[row][col]} is the colour (0xRRGGBB) or
     * {@link PuppetModel#NONE}; (x, y, z) is the picture's middle in the puppet's rest pose, skin pixels; a cell of
     * the picture is {@code size} skin pixels.
     */
    public record Prop(EmoteRig.Joint joint, double x, double y, double z, double size, int[][] grid) {}

    /**
     * Where the watching camera stands: degrees around the puppet from its front towards its left (180 = straight
     * behind), degrees above the horizon, blocks away.
     */
    public record Camera(double around, double up, double distance) {
        public static final Camera DEFAULT = new Camera(150, 14, 3.6);
    }

    /** How a keyframe is reached from the one before it. */
    public enum Ease {
        LINEAR, SINE, IN, OUT;

        public double at(double u) {
            return switch (this) {
                case LINEAR -> u;
                case SINE -> (1 - Math.cos(Math.PI * u)) / 2;
                case IN -> u * u;
                case OUT -> 1 - (1 - u) * (1 - u);
            };
        }

        static Ease of(String s) {
            for (Ease e : values()) if (e.name().equalsIgnoreCase(s)) return e;
            return null;
        }
    }

    public record Keyframe(int at, Ease ease, Pose pose) {}

    /** Both sides at once: the left one as written, the right one mirrored (rotations: yaw and roll; vectors: x). */
    private static final Map<String, Object[]> PAIRS = Map.of(
            "arms", new Object[]{Channel.ARM_L, Channel.ARM_R, true},
            "thighs", new Object[]{Channel.THIGH_L, Channel.THIGH_R, true},
            "shins", new Object[]{Channel.SHIN_L, Channel.SHIN_R, true},
            "feet", new Object[]{Channel.FOOT_L, Channel.FOOT_R, false},
            "knees", new Object[]{Channel.KNEE_L, Channel.KNEE_R, false});

    /** Every emote of a file's {@code emotes:} section, by id, in the file's order. Problems are reported, not thrown. */
    public static Map<String, EmoteSpec> parse(ConfigurationSection file, Consumer<String> problems) {
        Map<String, EmoteSpec> out = new LinkedHashMap<>();
        ConfigurationSection emotes = file == null ? null : file.getConfigurationSection("emotes");
        if (emotes == null) return out;
        for (String id : emotes.getKeys(false)) {
            ConfigurationSection e = emotes.getConfigurationSection(id);
            if (e == null) continue;
            if (!id.matches("[a-z0-9_]+")) {
                problems.accept(id + ": 감정표현 id 는 영문 소문자·숫자·_ 만");
                continue;
            }
            int length = Math.max(1, e.getInt("length", 20)), step = Math.max(1, e.getInt("step", 2));
            Pose base = pose(values(e.getConfigurationSection("pose")), Pose.REST, id + ".pose", problems);
            List<Keyframe> frames = new ArrayList<>();
            for (Map<?, ?> k : e.getMapList("keyframes")) {
                int at = k.get("at") instanceof Number n ? n.intValue() : 0;
                if (at < 0 || at >= length || (!frames.isEmpty() && at <= frames.getLast().at())) {
                    problems.accept(id + ": 장면 at " + at + " 은 0 이상 " + length + " 미만, 올라가는 순서여야 함");
                    continue;
                }
                Ease ease = k.get("ease") == null ? Ease.LINEAR : Ease.of(String.valueOf(k.get("ease")));
                if (ease == null) {
                    problems.accept(id + ": 모르는 ease '" + k.get("ease") + "' (linear, sine, in, out)");
                    ease = Ease.LINEAR;
                }
                frames.add(new Keyframe(at, ease, pose(k, base, id + " at " + at, problems)));
            }
            if (frames.isEmpty()) frames.add(new Keyframe(0, Ease.LINEAR, base));
            ConfigurationSection cam = e.getConfigurationSection("camera");
            Camera camera = cam == null ? Camera.DEFAULT : new Camera(cam.getDouble("around", Camera.DEFAULT.around()),
                    Math.clamp(cam.getDouble("up", Camera.DEFAULT.up()), -10, 80),
                    Math.clamp(cam.getDouble("distance", Camera.DEFAULT.distance()), 1.5, 8));
            String legs = e.getString("legs", "free");
            if (!legs.equals("free") && !legs.equals("planted")) problems.accept(id + ": legs 는 planted 또는 free");
            List<Prop> props = new ArrayList<>();
            for (Map<?, ?> m : e.getMapList("props")) {
                Prop prop = prop(m, id, problems);
                if (prop != null) props.add(prop);
            }
            out.put(id, new EmoteSpec(id, e.getString("name", id), length, step, e.getBoolean("loop", true), legs.equals("planted"),
                    camera, List.copyOf(frames), List.copyOf(props)));
        }
        return out;
    }

    /** A prop entry: joint, at [x, y, z], size, color "#rrggbb", pixels (rows of the picture, '#' = a pixel). */
    private static Prop prop(Map<?, ?> m, String id, Consumer<String> problems) {
        String where = id + ".props";
        EmoteRig.Joint joint = null;
        for (EmoteRig.Joint j : EmoteRig.Joint.values()) if (j.key().equals(String.valueOf(m.get("joint")).toLowerCase(Locale.ROOT))) joint = j;
        double[] at = three(m.get("at"));
        String colour = String.valueOf(m.get("color"));
        if (joint == null || joint == EmoteRig.Joint.ROOT || at == null || !colour.matches("#[0-9a-fA-F]{6}") || !(m.get("pixels") instanceof List<?> rows) || rows.isEmpty()) {
            problems.accept(where + ": joint (head, chest, arm_l ...), at [x, y, z], color \"#rrggbb\", pixels [줄, ...] 이 있어야 함");
            return null;
        }
        double size = m.get("size") instanceof Number n ? n.doubleValue() : 1;
        int cols = String.valueOf(rows.getFirst()).length(), rgb = Integer.parseInt(colour.substring(1), 16);
        if (size <= 0 || size > 4 || rows.size() > 16 || cols < 1 || cols > 16) {
            problems.accept(where + ": size 는 0 초과 4 이하, 그림은 16 x 16 칸까지");
            return null;
        }
        int[][] grid = new int[rows.size()][cols];
        for (int r = 0; r < rows.size(); r++) {
            String line = String.valueOf(rows.get(r));
            if (line.length() != cols) {
                problems.accept(where + ": pixels 의 줄 길이가 서로 다름");
                return null;
            }
            for (int c = 0; c < cols; c++) grid[r][c] = line.charAt(c) == '#' ? rgb : PuppetModel.NONE;
        }
        return new Prop(joint, at[0], at[1], at[2], size, grid);
    }

    private static Map<?, ?> values(ConfigurationSection s) {
        return s == null ? Map.of() : s.getValues(false);
    }

    /** A pose section over a base pose: channels by name, and the both-sides shorthands. */
    private static Pose pose(Map<?, ?> section, Pose base, String where, Consumer<String> problems) {
        Pose pose = base;
        for (Map.Entry<?, ?> en : section.entrySet()) {
            String key = String.valueOf(en.getKey()).toLowerCase(Locale.ROOT);
            if (key.equals("at") || key.equals("ease")) continue;
            double[] v = three(en.getValue());
            if (v == null) {
                problems.accept(where + ": " + key + " 는 숫자 세 개 [a, b, c]");
                continue;
            }
            Object[] pair = PAIRS.get(key);
            Channel c = Channel.of(key);
            if (pair != null) {
                boolean rot = (Boolean) pair[2];
                pose = pose.with((Channel) pair[0], v[0], v[1], v[2])
                        .with((Channel) pair[1], rot ? v[0] : -v[0], rot ? -v[1] : v[1], rot ? -v[2] : v[2]);
            } else if (c != null) {
                pose = pose.with(c, v[0], v[1], v[2]);
            } else {
                problems.accept(where + ": 모르는 항목 '" + key + "'");
            }
        }
        return pose;
    }

    private static double[] three(Object o) {
        if (!(o instanceof List<?> l) || l.size() != 3) return null;
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            if (!(l.get(i) instanceof Number n)) return null;
            v[i] = n.doubleValue();
        }
        return v;
    }

    /**
     * The pose at a tick (may be a fraction): eased between the keyframes around it. A loop goes on from its last
     * keyframe to its first; anything else holds its first and last keyframes.
     */
    public Pose poseAt(double tick) {
        double t = loop ? ((tick % length) + length) % length : Math.clamp(tick, 0, length);
        int next = -1;
        for (int i = 0; i < keyframes.size() && next < 0; i++) if (keyframes.get(i).at() > t) next = i;
        Keyframe a, b;
        double t0, t1;
        if (next > 0) {
            a = keyframes.get(next - 1);
            b = keyframes.get(next);
            t0 = a.at();
            t1 = b.at();
        } else if (!loop) {
            return next == 0 ? keyframes.getFirst().pose() : keyframes.getLast().pose();
        } else {
            a = keyframes.getLast();
            b = keyframes.getFirst();
            t0 = next == 0 ? a.at() - length : a.at();
            t1 = next == 0 ? b.at() : b.at() + length;
        }
        return a.pose().mix(b.pose(), t1 > t0 ? b.ease().at((t - t0) / (t1 - t0)) : 1);
    }

    /** Whether a played-once emote is over at this tick (a loop never is). */
    public boolean finished(long tick) {
        return !loop && tick >= length;
    }
}
