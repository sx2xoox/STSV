package dev.stsv.core.boss;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A boss definition from contents/&lt;pack&gt;/bosses/*.yml (see content/stsv/bosses/test.yml for the format).
 * Coordinates are model units (16 = 1 block) with the origin on the ground between the feet and the
 * front towards -z, the same space the bone models are built in.
 */
public record BossSpec(
        String id,
        String name,
        double health,
        double armor,
        double speed,
        double meleeDamage,
        double scale,
        BossBar.Color barColor,
        double barRange,
        double leash,
        int think,
        int globalCooldown,
        float modelScale,
        Map<String, Bone> bones,
        Map<String, Socket> sockets,
        Map<String, Animation> animations,
        Map<String, Skill> skills,
        List<Phase> phases,
        List<Drop> drops,
        String rewardTitle
) {

    /** @param look turns towards the target (head) */
    public record Bone(String name, NamespacedKey model, Vector3f pivot, String parent, boolean look) {}

    /** A point fixed to a bone (e.g. the hand that holds a thrown rock), in assembly coordinates at rest. */
    public record Socket(String bone, Vector3f at) {}

    /** rot = degrees around X, Y, Z (applied in that order), pos = offset in model units. */
    public record Track(TreeMap<Integer, Vector3f> rot, TreeMap<Integer, Vector3f> pos) {}

    public record Animation(String name, int length, boolean loop, Map<String, Track> bones, TreeMap<Integer, String> sounds) {

        /** Keyframes are joined with ease-in-out, so motions start and stop softly. */
        public static Vector3f sample(TreeMap<Integer, Vector3f> keys, float t) {
            if (keys == null || keys.isEmpty()) return null;
            Map.Entry<Integer, Vector3f> lo = keys.floorEntry((int) Math.floor(t));
            Map.Entry<Integer, Vector3f> hi = keys.ceilingEntry((int) Math.floor(t) + 1);
            if (lo == null) return new Vector3f(keys.firstEntry().getValue());
            if (hi == null) return new Vector3f(keys.lastEntry().getValue());
            float k = (t - lo.getKey()) / (float) (hi.getKey() - lo.getKey());
            k = Math.clamp(k, 0f, 1f);
            k = k * k * (3 - 2 * k);
            return new Vector3f(lo.getValue()).lerp(hi.getValue(), k);
        }
    }

    public record Skill(String id, String type, String animation, int cooldown, double minRange, double maxRange,
                        Map<String, Object> params, String when, int minPhase, List<Step> steps) {
        public double d(String key, double def) {
            return params.get(key) instanceof Number n ? n.doubleValue() : def;
        }

        public int i(String key, int def) {
            return params.get(key) instanceof Number n ? n.intValue() : def;
        }

        public String s(String key) {
            return params.get(key) instanceof String v ? v : null;
        }
    }

    public record Phase(double below, String animation, String message, BossBar.Color barColor, double speedMultiplier,
                        double cooldownMultiplier, double shockRadius, double shockKnockback, Map<String, NamespacedKey> models,
                        String then) {}

    // ------------------------------------------------------------------------------------------
    // Sequence patterns (see the comment block above "skills:" in content/stsv/bosses/executioner.yml)
    // ------------------------------------------------------------------------------------------

    public record Step(String animation, double chance, int minPhase, String condition, int trackUntil, double turn,
                       Hold hold, Move move, Leap leap, List<Hit> hits, List<Wave> waves, int[] flash, Grab grab,
                       Held held, Sectors sectors) {}

    public record Hold(int tick, int min, int max, int glint) {}

    public record Move(int from, int to, double speed) {}

    public record Leap(int from, int to, double height, int trackUntil, int markerFrom, double markerRadius) {}

    public record Hit(int tick, int until, String shape, double range, double arc, double width, double radius, double forward,
                      double damage, double knockback, double up, boolean guardBreak, boolean unblockable, int minPhase) {}

    public record Wave(int tick, double forward, double speed, double max, double width, double damage, int minPhase) {}

    public record Grab(int tick, double range, double arc) {}

    public record Held(String socket, int until, double rescue) {}

    public record Sectors(int show, int hide, int tick, int count, int safe, double radius, double damage) {}

    private static Step step(ConfigurationSection s, String where, Map<String, Animation> animations, Map<String, Socket> sockets) {
        String anim = s.getString("animation");
        if (anim == null || !animations.containsKey(anim)) throw new IllegalArgumentException(where + ": 애니메이션 '" + anim + "' 없음");
        ConfigurationSection h = s.getConfigurationSection("hold");
        ConfigurationSection mv = s.getConfigurationSection("move");
        ConfigurationSection lp = s.getConfigurationSection("leap");
        ConfigurationSection gb = s.getConfigurationSection("grab");
        ConfigurationSection hd = s.getConfigurationSection("held");
        ConfigurationSection sc = s.getConfigurationSection("sectors");
        if (hd != null && !sockets.containsKey(hd.getString("socket"))) {
            throw new IllegalArgumentException(where + ": held 의 소켓 '" + hd.getString("socket") + "' 없음");
        }
        List<Hit> hits = new ArrayList<>();
        for (Map<?, ?> m : s.getMapList("hits")) {
            ConfigurationSection x = section(m);
            String shape = x.getString("shape", "cone").toLowerCase(Locale.ROOT);
            if (!List.of("cone", "circle", "line", "held").contains(shape)) throw new IllegalArgumentException(where + ": 알 수 없는 판정 모양 " + shape);
            int tick = x.getInt("tick");
            hits.add(new Hit(tick, x.getInt("until", tick), shape, x.getDouble("range", 4), x.getDouble("arc", 120),
                    x.getDouble("width", 1.2), x.getDouble("radius", 3), x.getDouble("forward", 0), x.getDouble("damage", 6),
                    x.getDouble("knockback", 0.8), x.getDouble("up", 0.45), x.getBoolean("guard-break"), x.getBoolean("unblockable"),
                    x.getInt("min-phase", 0)));
        }
        List<Wave> waves = new ArrayList<>();
        for (Map<?, ?> m : s.getMapList("waves")) {
            ConfigurationSection x = section(m);
            waves.add(new Wave(x.getInt("tick"), x.getDouble("forward", 0), x.getDouble("speed", 0.4), x.getDouble("max", 8),
                    x.getDouble("width", 0.7), x.getDouble("damage", 6), x.getInt("min-phase", 0)));
        }
        List<Integer> flash = s.getIntegerList("flash");
        return new Step(anim, s.getDouble("chance", 1), s.getInt("min-phase", 0), s.getString("if"),
                s.getInt("track-until", 0), s.getDouble("turn", 10),
                h == null ? null : new Hold(h.getInt("tick"), h.getInt("min"), Math.max(h.getInt("min"), h.getInt("max")), h.getInt("glint", 4)),
                mv == null ? null : new Move(mv.getInt("from"), mv.getInt("to"), mv.getDouble("speed", 0.3)),
                lp == null ? null : new Leap(lp.getInt("from"), lp.getInt("to"), lp.getDouble("height", 5), lp.getInt("track-until", lp.getInt("to")),
                        lp.getInt("marker-from", lp.getInt("from")), lp.getDouble("marker-radius", 3)),
                hits, waves, flash.size() == 2 ? new int[]{flash.get(0), flash.get(1)} : null,
                gb == null ? null : new Grab(gb.getInt("tick"), gb.getDouble("range", 3), gb.getDouble("arc", 80)),
                hd == null ? null : new Held(hd.getString("socket"), hd.getInt("until"), hd.getDouble("rescue", 0.05)),
                sc == null ? null : new Sectors(sc.getInt("show"), sc.getInt("hide"), sc.getInt("tick"), Math.max(2, sc.getInt("count", 8)),
                        Math.max(1, sc.getInt("safe", 2)), sc.getDouble("radius", 16), sc.getDouble("damage", 20)));
    }

    private static ConfigurationSection section(Map<?, ?> m) {
        return new org.bukkit.configuration.MemoryConfiguration().createSection("x", m);
    }

    public record Drop(String item, int min, int max, double chance) {}

    // ------------------------------------------------------------------------------------------

    /** Parses one boss. Problems are thrown as IllegalArgumentException with a readable (Korean) message. */
    public static BossSpec parse(String id, ConfigurationSection c) {
        Map<String, Bone> bones = new LinkedHashMap<>();
        ConfigurationSection bs = need(c, "model.bones", id);
        for (String b : bs.getKeys(false)) {
            ConfigurationSection s = need(bs, b, id);
            NamespacedKey model = key(s.getString("model"), id + " 관절 " + b);
            String parent = s.getString("parent");
            if (parent != null && !bones.containsKey(parent)) {
                throw new IllegalArgumentException(id + ": 관절 '" + b + "' 의 parent '" + parent + "' 는 먼저 정의돼야 합니다");
            }
            bones.put(b, new Bone(b, model, vec(s.getDoubleList("pivot"), id + " 관절 " + b + " pivot"), parent, s.getBoolean("look")));
        }
        Map<String, Socket> sockets = new LinkedHashMap<>();
        ConfigurationSection ss = c.getConfigurationSection("model.sockets");
        if (ss != null) {
            for (String k : ss.getKeys(false)) {
                String bone = ss.getString(k + ".bone");
                if (!bones.containsKey(bone)) throw new IllegalArgumentException(id + ": 소켓 '" + k + "' 의 관절 '" + bone + "' 없음");
                sockets.put(k, new Socket(bone, vec(ss.getDoubleList(k + ".at"), id + " 소켓 " + k)));
            }
        }

        Map<String, Animation> animations = new LinkedHashMap<>();
        ConfigurationSection as = c.getConfigurationSection("animations");
        if (as != null) {
            for (String a : as.getKeys(false)) {
                ConfigurationSection s = need(as, a, id);
                Map<String, Track> tracks = new LinkedHashMap<>();
                ConfigurationSection tb = s.getConfigurationSection("bones");
                if (tb != null) {
                    for (String b : tb.getKeys(false)) {
                        if (!bones.containsKey(b)) throw new IllegalArgumentException(id + ": 애니메이션 '" + a + "' 의 관절 '" + b + "' 없음");
                        tracks.put(b, new Track(keys(tb.getConfigurationSection(b + ".rot"), id + " " + a + "." + b),
                                keys(tb.getConfigurationSection(b + ".pos"), id + " " + a + "." + b)));
                    }
                }
                TreeMap<Integer, String> sounds = new TreeMap<>();
                ConfigurationSection snd = s.getConfigurationSection("sounds");
                if (snd != null) for (String t : snd.getKeys(false)) sounds.put(Integer.parseInt(t), snd.getString(t));
                animations.put(a, new Animation(a, Math.max(1, s.getInt("length", 20)), s.getBoolean("loop"), tracks, sounds));
            }
        }

        Map<String, Skill> skills = new LinkedHashMap<>();
        ConfigurationSection ks = c.getConfigurationSection("skills");
        if (ks != null) {
            for (String k : ks.getKeys(false)) {
                ConfigurationSection s = need(ks, k, id);
                String anim = s.getString("animation");
                if (anim != null && !animations.containsKey(anim)) {
                    throw new IllegalArgumentException(id + ": 패턴 '" + k + "' 의 애니메이션 '" + anim + "' 없음");
                }
                List<Double> range = s.getDoubleList("range");
                Map<String, Object> params = new LinkedHashMap<>(s.getValues(false));
                List<Step> steps = new ArrayList<>();
                List<Map<?, ?>> raw = s.getMapList("steps");
                for (int i = 0; i < raw.size(); i++) steps.add(step(section(raw.get(i)), id + " 패턴 " + k + " 단계 " + (i + 1), animations, sockets));
                String type = s.getString("type", k).toLowerCase(Locale.ROOT);
                if (type.equals("sequence") && steps.isEmpty()) throw new IllegalArgumentException(id + ": 패턴 '" + k + "' 에 steps 가 없음");
                String when = s.getString("when");
                if (when != null && !List.of("behind", "above", "heal").contains(when)) {
                    throw new IllegalArgumentException(id + ": 패턴 '" + k + "' 의 when '" + when + "' 을 모름 (behind, above, heal)");
                }
                skills.put(k, new Skill(k, type, anim, s.getInt("cooldown", 100),
                        range.size() > 0 ? range.get(0) : 0, range.size() > 1 ? range.get(1) : 64, params, when, s.getInt("min-phase", 0), steps));
            }
        }

        List<Phase> phases = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("phases")) {
            ConfigurationSection s = new org.bukkit.configuration.MemoryConfiguration().createSection("p", m);
            String anim = s.getString("animation");
            if (anim != null && !animations.containsKey(anim)) {
                throw new IllegalArgumentException(id + ": 페이즈 애니메이션 '" + anim + "' 없음");
            }
            Map<String, NamespacedKey> models = new LinkedHashMap<>();
            ConfigurationSection ms = s.getConfigurationSection("models");
            if (ms != null) {
                for (String b : ms.getKeys(false)) {
                    if (!bones.containsKey(b)) throw new IllegalArgumentException(id + ": 페이즈 모델의 관절 '" + b + "' 없음");
                    models.put(b, key(ms.getString(b), id + " 페이즈 모델 " + b));
                }
            }
            phases.add(new Phase(s.getDouble("below"), anim, s.getString("message"), color(s.getString("bar-color"), null),
                    s.getDouble("speed-multiplier", 1), s.getDouble("cooldown-multiplier", 1),
                    s.getDouble("shockwave.radius", 0), s.getDouble("shockwave.knockback", 0), models, s.getString("then")));
        }
        phases.sort((a, b) -> Double.compare(b.below(), a.below()));
        for (Phase p : phases) {
            if (p.then() != null && !skills.containsKey(p.then())) throw new IllegalArgumentException(id + ": 페이즈의 then '" + p.then() + "' 패턴 없음");
        }

        List<Drop> drops = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("drops")) {
            ConfigurationSection s = new org.bukkit.configuration.MemoryConfiguration().createSection("d", m);
            List<Integer> amount = s.getIntegerList("amount");
            int min = amount.isEmpty() ? s.getInt("amount", 1) : amount.get(0);
            int max = amount.size() > 1 ? amount.get(1) : min;
            drops.add(new Drop(s.getString("item"), min, max, s.getDouble("chance", 1)));
        }

        return new BossSpec(id, c.getString("name", id), c.getDouble("health", 300), c.getDouble("armor", 0),
                c.getDouble("speed", 0.23), c.getDouble("melee-damage", 6), c.getDouble("scale", 1),
                color(c.getString("bar-color"), BossBar.Color.RED), c.getDouble("bar-range", 48), c.getDouble("leash", 40),
                Math.max(1, c.getInt("think", 20)), c.getInt("global-cooldown", 40), (float) c.getDouble("model.scale", 1),
                bones, sockets, animations, skills, phases, drops, c.getString("title"));
    }

    private static ConfigurationSection need(ConfigurationSection c, String path, String id) {
        ConfigurationSection s = c.getConfigurationSection(path);
        if (s == null) throw new IllegalArgumentException(id + ": '" + path + "' 가 없습니다");
        return s;
    }

    private static NamespacedKey key(String s, String where) {
        NamespacedKey k = s == null ? null : NamespacedKey.fromString(s);
        if (k == null) throw new IllegalArgumentException(where + ": 모델 키가 잘못됨 (" + s + ")");
        return k;
    }

    private static Vector3f vec(List<Double> l, String where) {
        if (l.size() != 3) throw new IllegalArgumentException(where + ": [x, y, z] 가 필요합니다");
        return new Vector3f(l.get(0).floatValue(), l.get(1).floatValue(), l.get(2).floatValue());
    }

    private static TreeMap<Integer, Vector3f> keys(ConfigurationSection s, String where) {
        TreeMap<Integer, Vector3f> out = new TreeMap<>();
        if (s == null) return out;
        for (String t : s.getKeys(false)) {
            out.put(Integer.parseInt(t), vec(s.getDoubleList(t), where + " " + t + "틱"));
        }
        return out;
    }

    private static BossBar.Color color(String s, BossBar.Color def) {
        if (s == null) return def;
        try {
            return BossBar.Color.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
