package dev.stsv.core.hud;

/**
 * Moving the sidebar panel with the mouse (/사이드바 위치), without the game: what HudModule feeds in each tick and
 * what comes out. The server never sees the cursor, but it sees where the player looks, and the mouse turns the
 * view: the panel follows the turn since the last tick (PX_PER_DEGREE GUI px per degree, right = right, up = up),
 * stops at the edges of where it can go and comes straight back when the turn reverses (the turn is added up, not
 * the angle, so the view never has to be wound back). The scroll wheel steps the size. Left click or F saves,
 * sneaking cancels, and after TIMEOUT_TICKS it cancels itself. Clicks in the first GRACE_TICKS do not count (the
 * hand may still be busy from before).
 *
 * Also the codec of the stored place: [size code, right, top] (the place of a player who used the old
 * /사이드바 위치 1920x1080 form was only a right edge).
 */
public final class HudEdit {

    /** GUI px the panel moves for each degree the view turns: a 1 cm stroke of a usual mouse is about 90 px. */
    static final double PX_PER_DEGREE = 2.0;
    static final long TIMEOUT_TICKS = 60 * 20L;
    static final long GRACE_TICKS = 10;

    public enum Outcome { SAVED, CANCELLED, TIMED_OUT }

    /** Fits a wanted place to what the panel can take (HudLayout.fit with the rows it shows at that size). */
    @FunctionalInterface
    public interface Fit {
        HudLayout.Placement fit(HudLayout.Placement wanted);
    }

    private final HudLayout.Placement original;
    private final long started;
    private HudLayout.Size size;
    private double x, y;
    private float lastYaw, lastPitch;
    private HudLayout.Placement current;
    private Outcome outcome;

    /** Starts from a place (as drawn) with the player looking at yaw / pitch at the given tick. */
    public HudEdit(HudLayout.Placement original, float yaw, float pitch, long tick) {
        this.original = original;
        this.current = original;
        this.size = original.size();
        this.x = original.right();
        this.y = original.top();
        this.lastYaw = yaw;
        this.lastPitch = pitch;
        this.started = tick;
    }

    public HudLayout.Placement original() {
        return original;
    }

    public HudLayout.Placement current() {
        return current;
    }

    /** The degrees between two yaws the short way round (-180 .. 180). */
    static double turn(float from, float to) {
        double d = (to - from) % 360.0;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    /** Where the player looks now: moves the panel by the turn since the last look. True when it moved. */
    public boolean look(float yaw, float pitch, Fit fit) {
        if (outcome != null) return false;
        double dx = turn(lastYaw, yaw) * PX_PER_DEGREE, dy = (pitch - lastPitch) * PX_PER_DEGREE;
        lastYaw = yaw;
        lastPitch = pitch;
        if (dx == 0 && dy == 0) return false;
        x += dx;
        y += dy;
        return place(fit);
    }

    /** One size bigger (dir > 0) or smaller (dir < 0). True when the size changed. */
    public boolean scroll(int dir, Fit fit) {
        if (outcome != null || dir == 0) return false;
        HudLayout.Size next = size.step(dir);
        if (next == size) return false;
        size = next;
        place(fit);
        y = current.top();
        return true;
    }

    /** Fits (x, y) and pulls them back to where the panel stopped, so a reversed turn moves it at once. */
    private boolean place(Fit fit) {
        HudLayout.Placement p = fit.fit(new HudLayout.Placement(size, (int) Math.round(x), (int) Math.round(y)));
        if (p.right() != (int) Math.round(x)) x = p.right();
        double half = p.size().row() / 2.0;
        if (y < p.top() - half || y > p.top() + half) y = p.top();
        boolean moved = !p.equals(current);
        current = p;
        return moved;
    }

    public HudLayout.Size size() {
        return size;
    }

    /** Whether a click now saves (not in the first moments). */
    public boolean canConfirm(long tick) {
        return tick - started >= GRACE_TICKS;
    }

    public boolean timedOut(long tick) {
        return tick - started >= TIMEOUT_TICKS;
    }

    /** Whole seconds left before it cancels itself. */
    public long secondsLeft(long tick) {
        return Math.max(0, (TIMEOUT_TICKS - (tick - started) + 19) / 20);
    }

    /** Ends the edit; only the first end counts (null afterwards). */
    public Outcome end(Outcome how) {
        if (outcome != null) return null;
        outcome = how;
        return how;
    }

    public boolean ended() {
        return outcome != null;
    }

    /** The place to keep if it ended now: the new one when saved, the old one otherwise. */
    public HudLayout.Placement result() {
        return outcome == Outcome.SAVED ? current : original;
    }

    // ------------------------------------------------------------------------------------------
    // Stored place
    // ------------------------------------------------------------------------------------------

    /** What is kept on the player: [size code, right, top]. */
    public static int[] store(HudLayout.Placement p) {
        return new int[]{p.size().code, p.right(), p.top()};
    }

    /**
     * The player's place: stored ([size code, right, top]), else the old right edge alone at the default size and
     * height, else the defaults.
     */
    public static HudLayout.Placement stored(int[] values, Integer legacyRight, HudLayout.Placement defaults) {
        if (values != null && values.length >= 3) {
            return new HudLayout.Placement(HudLayout.Size.of(values[0]), values[1], values[2]);
        }
        if (legacyRight != null) return new HudLayout.Placement(defaults.size(), legacyRight, defaults.top());
        return defaults;
    }

    /** A held-slot change as a wheel step: up (to the slot on the left) = bigger (+1), down = smaller (-1). */
    public static int wheel(int previousSlot, int newSlot) {
        int d = Math.floorMod(newSlot - previousSlot + 4, 9) - 4;
        return d == 0 ? 0 : d < 0 ? 1 : -1;
    }
}
