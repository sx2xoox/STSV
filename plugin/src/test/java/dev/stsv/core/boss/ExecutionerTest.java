package dev.stsv.core.boss;

import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The executioner's hits must be where its sword actually is: for every timed hit, the blade (or the
 * grabbing hand) has to pass through the hit shape during the hit window, at a height that can touch a
 * player, and the shape may not reach much further than the blade does. Otherwise players get hit by
 * thin air (unfair) or walk through the sword (not scary).
 */
class ExecutionerTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content")).resolve("stsv");
    private static BossSpec spec;
    private static final Vector3f TIP = new Vector3f(11, -27, 0);     // sword_tip point
    private static final Vector3f HILT = new Vector3f(11, 12, 0);     // sword point near the guard

    @BeforeAll
    static void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(CONTENT.resolve("bosses/executioner.yml").toFile());
        spec = BossSpec.parse("executioner", y.getConfigurationSection("executioner"));
    }

    private static Vector3f at(String anim, float t, String bone, Vector3f point) {
        var model = Rig.modelSpace(spec, Rig.sample(spec.animations().get(anim), t), 0, 0);
        return Rig.worldOffset(spec, model, bone, point, 0, 1);     // yaw 0: the boss faces +z
    }

    /** Points along the blade, hilt to tip. */
    private static List<Vector3f> blade(String anim, float t) {
        List<Vector3f> out = new ArrayList<>();
        for (int i = 0; i <= 6; i++) {
            Vector3f p = new Vector3f(HILT).lerp(TIP, i / 6f);
            out.add(at(anim, t, p.y > -4 ? "sword" : "sword_tip", p));
        }
        return out;
    }

    private static boolean inside(BossSpec.Hit h, Vector3f p) {
        double d = Math.hypot(p.x, p.z);
        return switch (h.shape()) {
            case "cone" -> d <= h.range() + 0.3 && (d < 1.2 || Math.toDegrees(Math.atan2(Math.abs(p.x), p.z)) <= h.arc() / 2 + 5);
            case "line" -> p.z >= -0.5 && p.z <= h.range() + 0.3 && Math.abs(p.x) <= h.width() / 2 + 0.3;
            case "circle" -> Math.hypot(p.x, p.z - h.forward()) <= h.radius() + 0.3;
            default -> true;
        };
    }

    private static double reach(BossSpec.Hit h) {
        return switch (h.shape()) {
            case "cone", "line" -> h.range();
            case "circle" -> h.forward() + h.radius();
            default -> 0;
        };
    }

    @Test
    void everyHitFollowsTheSword() {
        int checked = 0;
        for (BossSpec.Skill s : spec.skills().values()) {
            for (BossSpec.Step step : s.steps()) {
                for (BossSpec.Hit h : step.hits()) {
                    if (h.shape().equals("held")) continue;
                    boolean passes = false;
                    double maxReach = 0;
                    for (int t = h.tick(); t <= h.until(); t++) {
                        for (Vector3f p : blade(step.animation(), t)) {
                            maxReach = Math.max(maxReach, Math.hypot(p.x, p.z));
                            if (inside(h, p) && p.y > -0.6 && p.y < 2.4) passes = true;
                        }
                    }
                    String where = s.id() + "/" + step.animation() + " @" + h.tick();
                    assertTrue(passes, where + ": the blade never enters the hit shape at player height");
                    assertTrue(reach(h) <= maxReach + 1.0, where + ": hit reaches " + reach(h) + " but the blade only " + maxReach);
                    checked++;
                }
            }
        }
        assertTrue(checked >= 8, "checked " + checked);
    }

    @Test
    void grabReachesWithTheHand() {
        BossSpec.Step grab = spec.skills().get("grab").steps().getFirst();
        Vector3f hand = at(grab.animation(), grab.grab().tick(), "forearm_l", spec.sockets().get("hand_l").at());
        double d = Math.hypot(hand.x, hand.z);
        assertTrue(hand.z > 0.8, "hand is in front");
        assertTrue(d <= grab.grab().range() && d >= grab.grab().range() - 2, "grab range " + grab.grab().range() + " vs hand " + d);
        assertTrue(hand.y > 0.6 && hand.y < 2.2, "grabs at body height, hand y " + hand.y);
    }

    @Test
    void definitionIsCompleteAndFair() throws Exception {
        for (BossSpec.Bone b : spec.bones().values()) {
            assertTrue(Files.exists(CONTENT.resolve("models/" + b.model().getKey().replace("model/", "") + ".bbmodel")), b.model().toString());
        }
        for (BossSpec.Phase p : spec.phases()) {
            p.models().values().forEach(m -> assertTrue(Files.exists(CONTENT.resolve("models/" + m.getKey().replace("model/", "") + ".bbmodel")), m.toString()));
        }
        for (BossSpec.Animation a : spec.animations().values()) {
            for (String snd : a.sounds().values()) {
                try {
                    org.bukkit.Sound.class.getField(snd.toUpperCase().replace('.', '_'));
                } catch (NoSuchFieldException e) {
                    throw new AssertionError(a.name() + ": unknown sound " + snd);
                }
            }
        }
        assertEquals(0, spec.meleeDamage(), "every attack is a readable pattern, no instant vanilla punch");
        // fairness: every delayed attack gives a cue, every unblockable grab is announced, sectors stay visible long enough
        for (BossSpec.Skill s : spec.skills().values()) {
            for (BossSpec.Step st : s.steps()) {
                if (st.hold() != null) assertTrue(st.hold().glint() >= 4, s.id() + ": glint at least 4 ticks before the hit");
                if (st.grab() != null) assertTrue(st.flash() != null && st.flash()[1] >= st.grab().tick() - 1, s.id() + ": grab must flash until it lands");
                if (st.sectors() != null) {
                    assertTrue(st.sectors().hide() - st.sectors().show() >= 20, "safe slices visible for at least 1 s");
                    assertTrue(st.sectors().safe() >= 1 && st.sectors().safe() < st.sectors().count());
                }
            }
        }
        Map<String, BossSpec.Skill> skills = spec.skills();
        assertTrue(skills.values().stream().anyMatch(s -> "heal".equals(s.when())));
        assertTrue(skills.values().stream().anyMatch(s -> "behind".equals(s.when())));
        assertTrue(spec.health() <= 600, "hard because of its patterns, not a health sponge");
    }
}
