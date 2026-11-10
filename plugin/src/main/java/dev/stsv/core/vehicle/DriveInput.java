package dev.stsv.core.vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * The driver's keys this tick (Paper's player input: W/S/A/D, jump, sneak, sprint). Pure.
 *
 * <p>W = forward, S = backward (brake, then reverse), A / D = steer left / right, space (jump) = horn (sounds while
 * held: {@link VehicleSounds.HornState}), Ctrl (sprint) = the patrol car's siren on / off, sneak = get out (vanilla
 * dismounts the rider on its own). The siren toggles once per press ({@link #presses}): holding Ctrl does nothing more.
 */
public record DriveInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak,
                         boolean sprint) {

    public static final DriveInput NONE = new DriveInput(false, false, false, false, false, false, false);

    /** Without the sprint key. */
    public DriveInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak) {
        this(forward, backward, left, right, jump, sneak, false);
    }

    public static DriveInput of(org.bukkit.Input in) {
        return in == null ? NONE
                : new DriveInput(in.isForward(), in.isBackward(), in.isLeft(), in.isRight(), in.isJump(), in.isSneak(), in.isSprint());
    }

    /** +1 = turn left, -1 = right, 0 = straight (both or neither). */
    public int steer() {
        return (left ? 1 : 0) - (right ? 1 : 0);
    }

    /** A key went down now (it was up before). */
    public static boolean pressed(boolean before, boolean now) {
        return now && !before;
    }

    /** What a key press does (the horn is not a press: it sounds while space is held). */
    public enum Press { SIREN }

    /**
     * The actions of an input change: Ctrl pressed = SIREN (only on a vehicle with a light bar). A held key gives
     * nothing more (no toggling back and forth).
     */
    public static List<Press> presses(DriveInput before, DriveInput now, boolean hasSiren) {
        DriveInput b = before == null ? NONE : before;
        List<Press> out = new ArrayList<>(1);
        if (hasSiren && pressed(b.sprint(), now.sprint())) out.add(Press.SIREN);
        return out;
    }
}
