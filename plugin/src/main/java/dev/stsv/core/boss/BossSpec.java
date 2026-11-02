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
        List<Drop> drops
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
                        Map<String, Object> params) {
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
                        double cooldownMultiplier, double shockRadius, double shockKnockback, Map<String, NamespacedKey> models) {}

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
                skills.put(k, new Skill(k, s.getString("type", k).toLowerCase(Locale.ROOT), anim, s.getInt("cooldown", 100),
                        range.size() > 0 ? range.get(0) : 0, range.size() > 1 ? range.get(1) : 64, params));
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
                    s.getDouble("shockwave.radius", 0), s.getDouble("shockwave.knockback", 0), models));
        }
        phases.sort((a, b) -> Double.compare(b.below(), a.below()));

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
                bones, sockets, animations, skills, phases, drops);
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
