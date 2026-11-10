package dev.stsv.core.vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * Which seat a player gets, and where they step out. Pure (unit-tested).
 */
public final class VehicleSeats {

    private VehicleSeats() {}

    /**
     * The driver's seat (0) if it is free and they may drive, otherwise the first free passenger seat; -1 = full.
     * Someone who may drive but finds the driver's seat taken rides along.
     */
    public static int pick(boolean[] taken, boolean mayDrive) {
        if (taken.length == 0) return -1;
        if (mayDrive && !taken[0]) return 0;
        for (int i = 1; i < taken.length; i++) if (!taken[i]) return i;
        return -1;
    }

    /**
     * Where a rider of this seat tries to step out, in order, as vehicle positions [right, up, forward]: beside their own
     * seat (on its side: left seats to the left), the other side, beside the middle on both sides, in front, behind, and
     * last on top of it.
     */
    public static List<VehicleSpec.Vec> exits(VehicleSpec s, VehicleSpec.Vec seat) {
        double side = s.width() / 2 + 0.6;
        double own = seat.right() <= 0 ? -side : side;
        List<VehicleSpec.Vec> out = new ArrayList<>();
        out.add(new VehicleSpec.Vec(own, 0, seat.forward()));
        out.add(new VehicleSpec.Vec(-own, 0, seat.forward()));
        out.add(new VehicleSpec.Vec(own, 0, 0));
        out.add(new VehicleSpec.Vec(-own, 0, 0));
        out.add(new VehicleSpec.Vec(0, 0, s.length() / 2 + 0.7));
        out.add(new VehicleSpec.Vec(0, 0, -s.length() / 2 - 0.7));
        out.add(new VehicleSpec.Vec(0, s.height(), 0));
        return out;
    }
}
