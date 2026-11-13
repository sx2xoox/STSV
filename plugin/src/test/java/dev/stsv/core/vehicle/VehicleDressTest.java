package dev.stsv.core.vehicle;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the premium cars wear (VehicleDress): doors that open for the rider getting in or out, the hyper car's wing,
 * the sedan's mascot, the owner's name on the plates. The geometry is tested on the shipped cars: every door opens
 * outwards (or upwards), about its hinge, and lies exactly on the body when it is shut.
 */
class VehicleDressTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");
    private static final Transformation REST = new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(2, 2, 2), new Quaternionf());

    static VehicleSpec car(String id) {
        ConfigurationSection s = YamlConfiguration.loadConfiguration(PACK.resolve("items/vehicles.yml").toFile()).getConfigurationSection(id + ".vehicle");
        return VehicleSpec.parse(s, "stsv", m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel")));
    }

    /** Where a point of the vehicle [right, up, forward] is drawn when it belongs to a part with this transformation. */
    private static double[] drawn(Transformation t, double right, double up, double forward) {
        // the part's model is shrunk by the body's scale: a real point is the model's point times the scale
        Vector3f p = new Vector3f((float) -right, (float) up, (float) forward).div(t.getScale().x).mul(t.getScale())
                .rotate(t.getLeftRotation()).add(t.getTranslation());
        return new double[]{-p.x, p.y, p.z};
    }

    private static VehicleSpec.Mover mover(VehicleSpec car, String model) {
        return car.extras().dress().movers().stream().filter(m -> m.model().getKey().endsWith(model)).findFirst().orElseThrow();
    }

    @Test
    void aShutPartLiesOnTheBodyAndItsHingeNeverMoves() {
        for (String id : List.of("hyper_car", "grand_sedan", "luxury_suv")) {
            for (VehicleSpec.Mover mv : car(id).extras().dress().movers()) {
                Transformation shut = VehicleDress.mover(REST, mv, 0, 0);
                assertEquals(REST, shut, id + " " + mv.model() + ": at rest it is the body's own transformation");
                if (mv.turn() == 0) continue;
                Transformation open = VehicleDress.mover(REST, mv, 1, 0);
                double[] h = drawn(open, mv.hinge().right(), mv.hinge().up(), mv.hinge().forward());
                assertEquals(mv.hinge().right(), h[0], 1e-5, id + " " + mv.model());
                assertEquals(mv.hinge().up(), h[1], 1e-5);
                assertEquals(mv.hinge().forward(), h[2], 1e-5);
            }
        }
    }

    @Test
    void theHyperCarsDoorsSwingUpAndItsWingRisesAndBrakes() {
        VehicleSpec car = car("hyper_car");
        assertEquals(3, car.extras().dress().movers().size(), "two doors and the wing");
        for (String side : List.of("door_l", "door_r")) {
            VehicleSpec.Mover door = mover(car, side);
            assertEquals(VehicleSpec.Channel.DOOR, door.when());
            assertEquals(List.of(side.equals("door_l") ? 0 : 1), door.seats(), "its own seat's rider opens it");
            // the door's rear lower corner: up it goes, and it stays on its own side of the car
            double x = side.equals("door_l") ? -1.0 : 1.0;
            double[] rear = drawn(VehicleDress.mover(REST, door, 1, 0), x, 0.2, -0.375);
            assertTrue(rear[1] > 1.2, side + " swings up: its rear corner is at " + rear[1]);
            assertEquals(x, rear[0], 1e-5, "straight up, not outwards");
        }
        VehicleSpec.Mover wing = mover(car, "wing");
        assertEquals(VehicleSpec.Channel.BRAKE, wing.when());
        assertEquals(VehicleSpec.Channel.SPEED, wing.slideWhen());
        double[] rest = drawn(VehicleDress.mover(REST, wing, 0, 0), 0, 0.9, -2.3);
        double[] up = drawn(VehicleDress.mover(REST, wing, 0, 1), 0, 0.9, -2.3);
        double[] brake = drawn(VehicleDress.mover(REST, wing, 1, 1), 0, 0.9, -2.3);
        assertTrue(up[1] > rest[1] + 0.15 && up[2] == rest[2], "it rises with the speed");
        assertTrue(brake[1] > up[1] + 0.15, "and its trailing edge stands up as an air brake");
    }

    @Test
    void theSedansDoorsOpenFromTheMiddleAndTheSuvsFromTheirFrontEdge() {
        VehicleSpec sedan = car("grand_sedan"), suv = car("luxury_suv");
        // a point on each door's free edge (the edge away from its hinge), on the body's side: it goes outwards
        Object[][] doors = {
                {sedan, "door_fl", -1.0, -0.05}, {sedan, "door_fr", 1.0, -0.05}, {sedan, "door_rl", -1.0, -0.07}, {sedan, "door_rr", 1.0, -0.07},
                {suv, "door_fl", -0.94, 0.02}, {suv, "door_fr", 0.94, 0.02}, {suv, "door_rl", -0.94, -1.05}, {suv, "door_rr", 0.94, -1.05}};
        for (Object[] d : doors) {
            VehicleSpec car = (VehicleSpec) d[0];
            VehicleSpec.Mover door = mover(car, (String) d[1]);
            double side = (double) d[2], forward = (double) d[3];
            double[] edge = drawn(VehicleDress.mover(REST, door, 1, 0), side, 0.6, forward);
            assertTrue(Math.abs(edge[0]) > Math.abs(side) + 0.5 && Math.signum(edge[0]) == Math.signum(side),
                    d[1] + " opens outwards: its free edge is at " + edge[0]);
            assertEquals(0.6, edge[1], 1e-5, "about an upright hinge");
            assertEquals(VehicleSpec.Channel.DOOR, door.when());
            assertEquals(1, door.seats().size());
        }
        // the sedan's pairs: the front door is hinged ahead of its free edge, the rear door behind it
        assertTrue(mover(sedan, "door_fl").hinge().forward() > 0.5 && mover(sedan, "door_rl").hinge().forward() < -0.5);
        assertTrue(mover(suv, "door_fl").hinge().forward() > mover(suv, "door_rl").hinge().forward());
        // the mascot comes up with the engine and turns about nothing
        VehicleSpec.Mover mascot = mover(sedan, "mascot");
        assertEquals(VehicleSpec.Channel.ENGINE, mascot.slideWhen());
        assertEquals(VehicleSpec.Channel.NONE, mascot.when());
        assertTrue(mascot.slide().up() > 0.1);
    }

    @Test
    void thePartsFollowTheBodysPose() {
        VehicleSpec.Mover door = mover(car("grand_sedan"), "door_fl");
        // the body pitched and lifted on its springs: the shut door is the body's transformation still, and the open
        // door's hinge is where the body has it
        Quaternionf pitch = new Quaternionf().rotateX(0.05f);
        Transformation body = new Transformation(new Vector3f(0, 0.04f, 0), pitch, new Vector3f(2, 2, 2), new Quaternionf());
        assertEquals(body, VehicleDress.mover(body, door, 0, 0));
        double[] onBody = drawn(body, door.hinge().right(), door.hinge().up(), door.hinge().forward());
        double[] onDoor = drawn(VehicleDress.mover(body, door, 1, 0), door.hinge().right(), door.hinge().up(), door.hinge().forward());
        for (int k = 0; k < 3; k++) assertEquals(onBody[k], onDoor[k], 1e-5);
    }

    @Test
    void triggersAndEasing() {
        VehicleSpec.Mover wing = mover(car("hyper_car"), "wing"), door = mover(car("hyper_car"), "door_l");
        // a door: open while its rider got in or out lately and the car stands
        assertEquals(1f, VehicleDress.want(VehicleSpec.Channel.DOOR, door, 0, 14, false, false, true));
        assertEquals(0f, VehicleDress.want(VehicleSpec.Channel.DOOR, door, 3, 14, false, true, true), "it shuts when the car moves off");
        assertEquals(0f, VehicleDress.want(VehicleSpec.Channel.DOOR, door, 0, 14, false, false, false));
        // the wing: down at low speed, out from 30 % of the top speed, fully at 60 %
        assertEquals(0f, VehicleDress.want(VehicleSpec.Channel.SPEED, wing, 4, 14, false, true, false));
        float half = VehicleDress.want(VehicleSpec.Channel.SPEED, wing, 14 * 0.45, 14, false, true, false);
        assertTrue(half > 0.4f && half < 0.6f);
        assertEquals(1f, VehicleDress.want(VehicleSpec.Channel.SPEED, wing, 12, 14, false, true, false));
        // the air brake: braking at speed, not when crawling
        assertEquals(1f, VehicleDress.want(VehicleSpec.Channel.BRAKE, wing, 10, 14, true, true, false));
        assertEquals(0f, VehicleDress.want(VehicleSpec.Channel.BRAKE, wing, 2, 14, true, true, false));
        assertEquals(0f, VehicleDress.want(VehicleSpec.Channel.BRAKE, wing, 10, 14, false, true, false));
        assertEquals(1f, VehicleDress.want(VehicleSpec.Channel.ENGINE, wing, 0, 14, false, true, false));
        // easing: the whole way in its ticks, never past the end, and shaped slow at both ends
        float a = 0;
        for (int i = 0; i < 9; i++) a = VehicleDress.ease(a, 1, 9);
        assertEquals(1f, a, 1e-6);
        assertEquals(1f, VehicleDress.ease(1f, 1, 9));
        assertEquals(0.5f, VehicleDress.shape(0.5f), 1e-6);
        assertTrue(VehicleDress.shape(0.1f) < 0.1f && VehicleDress.shape(0.9f) > 0.9f);
        assertEquals(0f, VehicleDress.shape(0f));
        assertEquals(1f, VehicleDress.shape(1f));
    }

    @Test
    void theOwnersNameFitsThePlate() {
        for (String id : List.of("hyper_car", "grand_sedan", "luxury_suv")) {
            List<VehicleSpec.Plate> plates = car(id).extras().dress().plates();
            assertEquals(2, plates.size(), id + ": a plate at the nose and one at the tail");
            assertTrue(!plates.get(0).back() && plates.get(1).back());
            assertTrue(plates.get(0).at().forward() > 2 && plates.get(1).at().forward() < -2, id + ": on the ends of the car");
            for (VehicleSpec.Plate p : plates) {
                for (String name : List.of("Bo", "Steve", "Long_Name_12345x")) {
                    float s = VehicleDress.plateScale(p, name);
                    assertTrue(Math.max(3, name.length()) * VehicleDress.LETTER * s <= p.width() + 1e-6, id + " " + name + " fits the plate's width");
                    assertTrue(VehicleDress.LINE * s <= p.height() + 1e-6, "and its height");
                }
                assertTrue(VehicleDress.plateScale(p, "Steve") > VehicleDress.plateScale(p, "Long_Name_12345x"), "a long name is written smaller");
                // it stands on the body's face, a hair proud of it, facing out
                Transformation t = VehicleDress.plate(REST, p, "Steve");
                Vector3f facing = new Vector3f(0, 0, 1).rotate(t.getLeftRotation());
                assertEquals(p.back() ? -1 : 1, facing.z, 1e-5);
                assertEquals(p.at().forward() + (p.back() ? -1 : 1) * VehicleDress.PROUD, t.getTranslation().z, 1e-5);
                assertEquals(p.at().up(), t.getTranslation().y + VehicleDress.LINE * t.getScale().y / 2, 1e-5, "its middle at the plate's place");
            }
        }
        // no other vehicle wears any of it
        for (String id : List.of("police_car", "sports_car", "tuner_car", "light_truck"))
            assertEquals(VehicleSpec.Dress.NONE, car(id).extras().dress(), id);
    }

    @Test
    void aBrokenEntrySaysWhat() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("items/vehicles.yml").toFile());
        ConfigurationSection v = y.getConfigurationSection("hyper_car.vehicle");
        v.set("movers", List.of(java.util.Map.of("model", "hyper_car_wing", "turn", 30, "axis", List.of(1, 0, 0), "hinge", List.of(0, 1, 0))));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(v, "stsv", m -> true)).getMessage().contains("when"));
        v.set("movers", List.of(java.util.Map.of("model", "hyper_car_wing", "when", "wind", "turn", 30, "axis", List.of(1, 0, 0), "hinge", List.of(0, 1, 0))));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(v, "stsv", m -> true)).getMessage().contains("door"));
        v.set("movers", List.of(java.util.Map.of("model", "hyper_car_wing", "when", "speed")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(v, "stsv", m -> true)).getMessage().contains("turn"));
        v.set("movers", List.of());
        v.set("plates", List.of(java.util.Map.of("at", List.of(0, 0.3, 2.3), "width", 9)));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(v, "stsv", m -> true)).getMessage().contains("width"));
        assertFalse(car("hyper_car").partModels().isEmpty(), "the parts' models count for the render budget");
    }
}
