package dev.stsv.core.vehicle;

/**
 * The driver's keys this tick (Paper's player input: W/S/A/D, jump, sneak). Pure.
 *
 * <p>W = forward, S = backward (brake, then reverse), A / D = steer left / right, jump = siren (patrol car) or horn,
 * sneak = get out (vanilla dismounts the rider on its own).
 */
public record DriveInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak) {

    public static final DriveInput NONE = new DriveInput(false, false, false, false, false, false);

    public static DriveInput of(org.bukkit.Input in) {
        return in == null ? NONE : new DriveInput(in.isForward(), in.isBackward(), in.isLeft(), in.isRight(), in.isJump(), in.isSneak());
    }

    /** +1 = turn left, -1 = right, 0 = straight (both or neither). */
    public int steer() {
        return (left ? 1 : 0) - (right ? 1 : 0);
    }

    /** The jump key went down now (it was up before): one siren toggle or honk per press. */
    public static boolean pressed(boolean before, boolean now) {
        return now && !before;
    }
}
