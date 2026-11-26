package dev.stsv.economy.store;

import java.util.ArrayList;
import java.util.List;

/**
 * One customer in a store (README "편의점" 손님): in through the door, round the shelves they want (along the layout's walk
 * graph, no pathfinding), into the line, at the counter until paid, out again. Pure (unit-tested): positions are the store's
 * own coordinates, {@link StoreModule} moves the mannequin to them each tick and answers {@link Env}.
 *
 * <pre>
 *  WALK (to a shelf) → BROWSE (a moment; takes the things, or finds the shelf empty) → … → WALK (to the line) → QUEUE
 *  → COUNTER (puts the things down, waits to be scanned and to pay) → LEAVE → GONE
 *  QUEUE / COUNTER waited too long → LEAVE (angry);  nothing found at all → LEAVE (empty-handed)
 * </pre>
 *
 * <p>Phase 2 hooks: {@link #flags} carries what a customer is up to besides shopping ("warm" now; later "id", "steal",
 * "drunk" …), and a response button pressed for them comes to {@link #mood(double)} / {@link #leave(Env, Exit)} like any
 * other reaction.
 */
public final class Shopper {

    public enum State { WALK, BROWSE, QUEUE, COUNTER, LEAVE, GONE }

    /** Why they leave. */
    public enum Exit { PAID, ANGRY, EMPTY }

    /** What happened this tick (the module says a line, plays a sound, updates the counter). */
    public enum Event { NONE, BROWSING, TOOK, EMPTY, QUEUED, AT_COUNTER, GAVE_UP, NOTHING_FOUND, LEFT }

    /** The store as a shopper sees it. */
    public interface Env {
        /** The walk between two nodes (both included). */
        List<StoreLayout.Node> path(String from, String to);

        /** Takes one thing off the shelf of this place: false if it is empty. */
        boolean take(String place);

        /** Their place in the line (0: at the counter), -1 when not in it (they join at the end). */
        int line(Shopper s);

        /** The spot of place i in the line. */
        StoreLayout.Node queueSpot(int i);
    }

    /** A stop on the round: browse from this node, take these things (product ids) of this place. */
    public record Stop(String node, String place, List<String> things) {}

    private final String type;
    private final boolean shiny;
    private final double speed;
    private final double sensitivity;
    private final List<Stop> round;
    private final List<String> atCounter;
    private final List<String> got = new ArrayList<>();
    private final List<String> flags = new ArrayList<>();
    private State state = State.WALK;
    private Exit exit;
    private double x, z;
    private float yaw;
    private final List<double[]> path = new ArrayList<>();
    private String goal;
    private int next;
    private int dwell;
    private final int browseTicks;
    private double base;
    private double waited;
    private boolean saidWait;

    /**
     * @param round       the shelves to visit, in order
     * @param atCounter   things they pick up at the till (gum, cigarettes …)
     * @param speed       blocks a tick
     * @param sensitivity how fast waiting sours them (the bus kind's mood factor)
     * @param browseTicks how long they look at a shelf
     */
    public Shopper(String type, boolean shiny, List<Stop> round, List<String> atCounter, double mood, double speed, double sensitivity,
                   int browseTicks, StoreLayout.Node start) {
        this.type = type;
        this.shiny = shiny;
        this.round = List.copyOf(round);
        this.atCounter = List.copyOf(atCounter);
        this.base = mood;
        this.speed = speed;
        this.sensitivity = Math.max(0.1, sensitivity);
        this.browseTicks = Math.max(1, browseTicks);
        this.x = start.x();
        this.z = start.z();
        this.yaw = start.yaw();
        this.goal = start.id();
    }

    /**
     * The round for these things: grouped by place (the counter's apart), each place browsed from the spot of it nearest to
     * where they are then (from the door), the nearest place next.
     */
    public static List<Stop> plan(StoreLayout.Layout l, List<String> things, java.util.function.Function<String, String> placeOf, String from) {
        java.util.LinkedHashMap<String, List<String>> by = new java.util.LinkedHashMap<>();
        for (String t : things) {
            String place = placeOf.apply(t);
            if (place == null || place.equals("counter")) continue;
            by.computeIfAbsent(place, k -> new ArrayList<>()).add(t);
        }
        List<Stop> out = new ArrayList<>();
        StoreLayout.Node at = l.nodes().get(from);
        while (!by.isEmpty()) {
            String bestPlace = null, bestNode = null;
            double best = Double.MAX_VALUE;
            for (String place : by.keySet()) {
                for (String n : l.spots().getOrDefault(place, List.of())) {
                    double d = StoreLayout.len(at, l.nodes().get(n));
                    if (d < best) {
                        best = d;
                        bestPlace = place;
                        bestNode = n;
                    }
                }
            }
            if (bestPlace == null) break;   // a place with no spot (none in the layout): skipped
            out.add(new Stop(bestNode, bestPlace, List.copyOf(by.remove(bestPlace))));
            at = l.nodes().get(bestNode);
        }
        return out;
    }

    /** The counter's things of a list (place "counter"). */
    public static List<String> counterThings(List<String> things, java.util.function.Function<String, String> placeOf) {
        List<String> out = new ArrayList<>();
        for (String t : things) if ("counter".equals(placeOf.apply(t))) out.add(t);
        return out;
    }

    // ------------------------------------------------------------------------------------------

    /** Starts the round (call once, after spawning at the door's outside): to the first shelf, or straight to the line. */
    public void begin(Env env) {
        if (round.isEmpty()) toLine(env);
        else walkTo(env, round.getFirst().node());
    }

    /** One tick (1/20 s). */
    public Event tick(Env env) {
        switch (state) {
            case WALK -> {
                if (!step()) return Event.NONE;
                if (next < round.size() && goal.equals(round.get(next).node())) {
                    state = State.BROWSE;
                    dwell = browseTicks;
                    yaw = node(env, goal).yaw();
                    return Event.BROWSING;
                }
                return Event.NONE;
            }
            case BROWSE -> {
                if (--dwell > 0) return Event.NONE;
                Stop s = round.get(next++);
                boolean any = false, missed = false;
                for (String t : s.things()) {
                    if (env.take(s.place())) {
                        got.add(t);
                        any = true;
                    } else missed = true;
                }
                if (next < round.size()) walkTo(env, round.get(next).node());
                else if (got.isEmpty() && atCounter.isEmpty()) {
                    leave(env, Exit.EMPTY);
                    return Event.NOTHING_FOUND;
                } else toLine(env);
                return missed ? Event.EMPTY : any ? Event.TOOK : Event.NONE;
            }
            case QUEUE, COUNTER -> {
                if (!path.isEmpty()) {   // still on the way to the line
                    step();
                    return Event.NONE;
                }
                waited += state == State.COUNTER ? 0.5 / 20.0 : 1 / 20.0;   // being served sours them half as fast
                int i = env.line(this);
                if (i < 0) return Event.NONE;
                StoreLayout.Node spot = env.queueSpot(i);
                boolean there = moveToward(spot.x(), spot.z());
                if (there) yaw = spot.yaw();
                if (state == State.QUEUE && i == 0 && there) {
                    state = State.COUNTER;
                    got.addAll(atCounter);
                    return Event.AT_COUNTER;
                }
                return Event.NONE;
            }
            case LEAVE -> {
                if (step()) {
                    state = State.GONE;
                    return Event.LEFT;
                }
                return Event.NONE;
            }
            default -> {
                return Event.NONE;
            }
        }
    }

    /** Whether they have waited past giving up (in line or at the counter). */
    public boolean fedUp(StoreSpec.Patience p) {
        return (state == State.QUEUE || state == State.COUNTER) && waited * sensitivity >= p.giveUp();
    }

    /** They had enough: out, angry. */
    public void giveUp(Env env) {
        leave(env, Exit.ANGRY);
    }

    /** Paid (or turned away): out through the door. */
    public void leave(Env env, Exit why) {
        if (state == State.LEAVE || state == State.GONE) return;
        exit = why;
        boolean lined = (state == State.QUEUE || state == State.COUNTER) && path.isEmpty();
        if (lined) {   // from their place in the line: past the counter's end, out
            path.clear();
            addPath(env.path(StoreLayout.COUNTER_TO, "out"), 0);
            goal = "out";
        } else {
            if (!path.isEmpty()) {   // on the way somewhere: from where that leg ends
                path.clear();
            }
            walkTo(env, "out");
        }
        state = State.LEAVE;
    }

    private void toLine(Env env) {
        path.clear();
        addPath(env.path(goal, StoreLayout.QUEUE_FROM), 0);
        goal = StoreLayout.QUEUE_FROM;
        state = State.QUEUE;
    }

    private void walkTo(Env env, String to) {
        path.clear();
        addPath(env.path(goal, to), 0);
        goal = to;
        state = State.WALK;
    }

    private void addPath(List<StoreLayout.Node> nodes, int skip) {
        for (int i = skip; i < nodes.size(); i++) path.add(new double[]{nodes.get(i).x(), nodes.get(i).z()});
    }

    private static StoreLayout.Node node(Env env, String id) {
        List<StoreLayout.Node> p = env.path(id, id);
        return p.isEmpty() ? new StoreLayout.Node(id, 0, 0, 0) : p.getFirst();
    }

    /** A step along the path: true when its end is reached. In the line it heads for its place in it. */
    private boolean step() {
        while (!path.isEmpty()) {
            double[] to = path.getFirst();
            if (moveToward(to[0], to[1])) path.removeFirst();
            else return false;
        }
        return true;
    }

    /** A step toward (tx, tz): true when there. */
    private boolean moveToward(double tx, double tz) {
        double dx = tx - x, dz = tz - z, len = Math.hypot(dx, dz);
        if (len <= speed + 1e-9) {
            x = tx;
            z = tz;
            return true;
        }
        x += dx / len * speed;
        z += dz / len * speed;
        yaw = (float) Math.toDegrees(Math.atan2(dx, dz));   // the layout's yaw: 0 = +z, 90 = +x
        return false;
    }

    // ------------------------------------------------------------------------------------------

    public String type() {
        return type;
    }

    public boolean shiny() {
        return shiny;
    }

    public State state() {
        return state;
    }

    public Exit exit() {
        return exit;
    }

    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    /** What they carry (the shelves' things, then the counter's at the till). */
    public List<String> got() {
        return got;
    }

    public List<Stop> round() {
        return round;
    }

    public List<String> flags() {
        return flags;
    }

    /** Seconds in line (at the counter, being served, at half weight). */
    public double waited() {
        return waited;
    }

    /** The mood now: their mood, soured by waiting (patience). */
    public double mood(StoreSpec.Patience p) {
        return Math.clamp(p.mood(base, waited * sensitivity), 0, 100);
    }

    /** A reaction: the mood up or down. */
    public void mood(double change) {
        base = Math.clamp(base + change, 0, 100);
    }

    /** In line (not yet at the counter) or at it. */
    public boolean inLine() {
        return state == State.QUEUE || state == State.COUNTER;
    }

    /** Once: whether the "still waiting" line is due (past grace). */
    public boolean waitLine(StoreSpec.Patience p) {
        if (saidWait || waited * sensitivity < p.grace()) return false;
        saidWait = true;
        return true;
    }
}
