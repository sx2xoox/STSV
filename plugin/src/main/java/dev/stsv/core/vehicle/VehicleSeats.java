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

    /** How far a seated rider's head turns from the vehicle's facing before the body twists along (the client's limit). */
    public static final float HEAD_TURN = 85f;
    /** Past this, the body twists along by {@link #TWIST} of the head's turn. */
    public static final float TWIST_FROM = 50f, TWIST = 0.2f;

    /**
     * Where a rider's body faces, seen by every client, when they sit on a living mount facing mountYaw and look
     * towards headYaw (the client's LivingEntityRenderer.solveBodyRot): the mount's facing while the head is turned up to
     * 85° either way; beyond that the body turns just enough to keep the head 85° off; and past 50° it twists a fifth of
     * the way along. Vehicle seats are such mounts (a marker armor stand facing the car), so a rider faces where the
     * car faces and only their head follows the camera. Degrees, Minecraft yaw.
     */
    public static float riderBodyYaw(float mountYaw, float headYaw) {
        float turn = Math.clamp(signed(headYaw - mountYaw), -HEAD_TURN, HEAD_TURN);
        float body = headYaw - turn;
        if (Math.abs(turn) > TWIST_FROM) body += turn * TWIST;
        return signed(body);
    }

    /** An angle as -180..180 degrees. */
    static float signed(float deg) {
        float d = deg % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
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
