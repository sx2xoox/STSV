package dev.stsv.core.vehicle;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VehicleModule#ridingBoat (the economy's fisher bonus asks it): a rider of the 통통배, in any seat, rides a boat; someone not in
 * its seats does not, and nobody in a land vehicle does.
 */
class BoatRiderTest {

    static VehicleSpec car() {
        Path pack = Path.of(System.getProperty("stsv.content")).resolve("stsv");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pack.resolve("items/vehicles.yml").toFile());
        return VehicleSpec.parse(y.getConfigurationSection("police_car.vehicle"), "stsv",
                m -> Files.exists(pack.resolve("models/" + m + ".bbmodel")));
    }

    @Test
    void ridersOfTheFishingBoatRideABoat() {
        VehicleSpec boat = BoatPhysicsTest.boat();
        assertTrue(boat.floats());
        for (int seat = 0; seat < boat.seats().size(); seat++) assertTrue(VehicleModule.boatSeat(boat, seat), "seat " + seat);
        assertFalse(VehicleModule.boatSeat(boat, -1), "not one of its riders");
    }

    @Test
    void landVehiclesAreNotBoats() {
        VehicleSpec car = car();
        assertFalse(car.floats());
        assertFalse(VehicleModule.boatSeat(car, 0), "a car's driver");
        assertFalse(VehicleModule.boatSeat(null, 0), "no vehicle");
    }
}
