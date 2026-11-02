package dev.stsv.core.boss;

import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test boss definition must be complete (every model, animation, sound and drop it names exists),
 * and the bone maths must put things where the animations say: front = facing direction, "arm forward"
 * really swings forward, and the display transform shows exactly the posed point.
 */
class BossTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content")).resolve("stsv");
    private static BossSpec golem;

    @BeforeAll
    static void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(CONTENT.resolve("bosses/test.yml").toFile());
        golem = BossSpec.parse("test_golem", y.getConfigurationSection("test_golem"));
    }

    @Test
    void definitionIsComplete() throws Exception {
        assertEquals(8, golem.bones().size());
        for (BossSpec.Bone b : golem.bones().values()) {
            assertTrue(Files.exists(CONTENT.resolve("models/" + b.model().getKey().replace("model/", "") + ".bbmodel")), b.model().toString());
        }
        for (BossSpec.Phase p : golem.phases()) {
            p.models().values().forEach(m -> assertTrue(Files.exists(CONTENT.resolve("models/" + m.getKey().replace("model/", "") + ".bbmodel")), m.toString()));
        }
        Set<String> types = Set.of("slam", "charge", "throw");
        for (BossSpec.Skill s : golem.skills().values()) {
            assertTrue(types.contains(s.type()), s.id());
            assertTrue(golem.animations().containsKey(s.animation()), s.id());
            if (s.s("model") != null) {
                assertTrue(Files.exists(CONTENT.resolve("models/" + s.s("model").replace("stsv:model/", "") + ".bbmodel")), s.s("model"));
            }
        }
        for (String needed : List.of("idle", "walk", "punch", "death", "spawn")) assertTrue(golem.animations().containsKey(needed), needed);
        assertTrue(golem.sockets().containsKey("hand_r"), "throw holds the rock in hand_r");

        // sound keys: every one must be a vanilla sound (checked against the API's sound constants)
        for (BossSpec.Animation a : golem.animations().values()) {
            for (String s : a.sounds().values()) {
                String field = s.toUpperCase().replace('.', '_');
                assertTrue(hasField(org.bukkit.Sound.class, field), a.name() + ": unknown sound " + s);
            }
        }

        YamlConfiguration items = YamlConfiguration.loadConfiguration(CONTENT.resolve("items/test.yml").toFile());
        for (BossSpec.Drop d : golem.drops()) {
            assertTrue(items.contains(d.item().replace("stsv:", "")), "drop " + d.item());
            assertTrue(d.min() >= 1 && d.max() >= d.min());
        }
        assertEquals(0.5, golem.phases().getFirst().below());
    }

    private static boolean hasField(Class<?> c, String name) {
        try {
            c.getField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    @Test
    void keyframesEaseAndHold() {
        TreeMap<Integer, Vector3f> k = new TreeMap<>(Map.of(10, new Vector3f(0), 20, new Vector3f(100, 0, 0)));
        assertEquals(0, BossSpec.Animation.sample(k, 0).x, 1e-4);          // before the first key: hold it
        assertEquals(50, BossSpec.Animation.sample(k, 15).x, 1e-3);        // ease-in-out is symmetric
        assertTrue(BossSpec.Animation.sample(k, 11).x < 10);               // starts slowly
        assertEquals(100, BossSpec.Animation.sample(k, 30).x, 1e-4);       // after the last key: hold it
        assertNull(BossSpec.Animation.sample(new TreeMap<>(), 5));
    }

    private static Vector3f at(Map<String, Rig.Pose> pose, String bone, Vector3f point, float yaw, float lookYaw) {
        return Rig.worldOffset(golem, Rig.modelSpace(golem, pose, lookYaw, 0), bone, point, yaw, 1);
    }

    @Test
    void frontFacesTheBossDirection() {
        Vector3f visor = new Vector3f(0, 52, -8);
        // yaw 0 = facing south (+z), yaw 90 = facing west (-x)
        assertTrue(at(Map.of(), "head", visor, 0, 0).z > 0.4);
        assertTrue(at(Map.of(), "head", visor, 90, 0).x < -0.4);
        // rest pose puts the visor at its modelled height
        assertEquals(52 / 16f, at(Map.of(), "head", visor, 0, 0).y, 1e-4);
    }

    @Test
    void rotationConventionsMatchTheDefinitionComments() {
        Vector3f fist = new Vector3f(18, 8, 0);
        Vector3f rest = at(Map.of(), "forearm_r", fist, 0, 0);
        // arm +X = forward (towards the facing, +z at yaw 0) and up
        Vector3f raised = at(Map.of("arm_r", new Rig.Pose(new Vector3f(90, 0, 0), new Vector3f())), "forearm_r", fist, 0, 0);
        assertTrue(raised.z > rest.z + 1, "arm swings forward");
        assertTrue(raised.y > rest.y + 1, "and up");
        // right arm +Z = outwards: the right side of a boss facing south is west (-x)
        assertTrue(rest.x < 0, "right arm is on the boss's right");
        Vector3f out = at(Map.of("arm_r", new Rig.Pose(new Vector3f(0, 0, 40), new Vector3f())), "forearm_r", fist, 0, 0);
        assertTrue(out.x < rest.x - 0.5, "right arm +Z moves outwards");
        // body -X = lean forward: the head goes forward
        Vector3f lean = at(Map.of("body", new Rig.Pose(new Vector3f(-30, 0, 0), new Vector3f())), "head", new Vector3f(0, 52, -8), 0, 0);
        assertTrue(lean.z > at(Map.of(), "head", new Vector3f(0, 52, -8), 0, 0).z + 0.5);
        // look: Minecraft yaw grows clockwise from above, so +50 turns a south-facing head to the west
        assertTrue(at(Map.of(), "head", new Vector3f(0, 52, -8), 0, 50).x < -0.2);
    }

    /** The split sent to the client (translation, rotation, scale) must rebuild exactly the same matrix. */
    @Test
    void displayPartsRebuildTheMatrix() {
        Map<String, Rig.Pose> pose = new HashMap<>();
        pose.put("body", new Rig.Pose(new Vector3f(-30, 348, 12), new Vector3f(0, -4, 1)));
        pose.put("arm_r", new Rig.Pose(new Vector3f(174, -10, 85), new Vector3f()));
        Map<String, Matrix4f> model = Rig.modelSpace(golem, pose, 40, -20);
        for (String bone : List.of("body", "head", "arm_r", "forearm_r", "leg_l")) {
            for (float yaw : new float[]{0, 91, -179.5f}) {
                Matrix4f m = Rig.display(model.get(bone), yaw, 1.3f, 3.2f);
                Rig.DisplayParts p = Rig.displayParts(model.get(bone), yaw, 1.3f, 3.2f);
                Matrix4f rebuilt = new Matrix4f().translate(p.translation()).rotate(p.rotation()).scale(p.scale());
                assertTrue(m.equals(rebuilt, 1e-4f), bone + " @" + yaw + "\n" + m + "\n" + rebuilt);
            }
        }
    }

    @Test
    void displayTransformShowsThePosedPoint() {
        Map<String, Rig.Pose> pose = new HashMap<>();
        pose.put("body", new Rig.Pose(new Vector3f(-12, 8, 3), new Vector3f(0, -1, 0.5f)));
        pose.put("arm_r", new Rig.Pose(new Vector3f(70, 10, 20), new Vector3f()));
        pose.put("forearm_r", new Rig.Pose(new Vector3f(30, 0, 0), new Vector3f()));
        float yaw = 37, rider = 3.2f;
        Map<String, Matrix4f> model = Rig.modelSpace(golem, pose, 20, -10);
        Vector3f point = new Vector3f(18, 8, -2);
        Vector3f q = new Vector3f(point).sub(golem.bones().get("forearm_r").pivot());
        // the item renderer shows bone-local q at C*q (180° turn, 1/16 scale); the transform then moves it
        Vector3f itemLocal = new Vector3f(-q.x / 16, q.y / 16, -q.z / 16);
        Vector3f shown = Rig.display(model.get("forearm_r"), yaw, 1, rider).transformPosition(itemLocal);
        Vector3f expected = Rig.worldOffset(golem, model, "forearm_r", point, yaw, 1).sub(0, rider, 0);
        assertEquals(expected.x, shown.x, 1e-4);
        assertEquals(expected.y, shown.y, 1e-4);
        assertEquals(expected.z, shown.z, 1e-4);
    }
}
