package dev.stsv.economy.secret.usher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The 안내원's plan, pure: the walk order (the guide's own numbering, backwards), stages, how close the 안내원 stands, the
 * stopped clock, rough places, what the false 안내원 says, and the hidden room under the plaza (block coordinates in the
 * tutorial world; players stand at y 64 there, the plaza floor is y 63).
 */
final class UsherPlan {

    private UsherPlan() {}

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** How many wrong pictures (one per station). */
    static final int PICTURES = 13;
    /** Found this many: the note. */
    static final int NOTE_AT = 7;

    // ------------------------------------------------------------------------------------------
    // order and stages
    // ------------------------------------------------------------------------------------------

    /**
     * The walk: the stations as the guide numbers them (its menu: topics by their order, each at its station), backwards
     * from the last. Stations no topic uses follow in the layout's order (so the list always holds every station once).
     */
    static List<String> order(List<String> menuStations, List<String> layoutStations) {
        Set<String> all = new LinkedHashSet<>();
        for (String s : menuStations) if (layoutStations.contains(s)) all.add(s);
        all.addAll(layoutStations);
        List<String> out = new ArrayList<>(all);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Saved stage for a number found: 1 = started (none found) … 14 = all found (the room). */
    static int stage(int found) {
        return Math.clamp(found, 0, PICTURES) + 1;
    }

    /** Found so far for a saved stage (0 before it starts). */
    static int found(int stage) {
        return Math.clamp(stage - 1, 0, PICTURES);
    }

    /** The station to find next, or null once all are found. */
    static String next(List<String> order, int found) {
        return found >= 0 && found < order.size() ? order.get(found) : null;
    }

    /** A click on this station's picture: the right one (next), or one out of turn, or one already found (ignored). */
    enum Click { RIGHT, OUT_OF_TURN, DONE }

    static Click click(List<String> order, int found, String station) {
        int i = order.indexOf(station);
        if (i >= 0 && i < found) return Click.DONE;
        return i == found ? Click.RIGHT : Click.OUT_OF_TURN;
    }

    /** How far the 안내원 stands once this many are found (none before the first): a block closer each time. */
    static double distance(int found) {
        return found <= 0 ? -1 : Math.max(4, 19 - found);
    }

    // ------------------------------------------------------------------------------------------
    // records in words
    // ------------------------------------------------------------------------------------------

    /** "03:17": the Korean clock time of a moment (the stopped clock shows when they first came). */
    static String clock(long ms) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(KST);
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    /** "2025.03.14". */
    static String date(long ms) {
        LocalDate d = Instant.ofEpochMilli(ms).atZone(KST).toLocalDate();
        return String.format("%d.%02d.%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }

    /** A coordinate rounded to tens (a rough place, not a confession of tracking). */
    static int rough(int v) {
        return Math.round(v / 10f) * 10;
    }

    /** When they left, in words: 아까 (today), 어제, 그저께, 며칠 전에. */
    static String when(long quitAt, long now) {
        if (quitAt <= 0) return "지난번에";
        long days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(quitAt).atZone(KST).toLocalDate(), Instant.ofEpochMilli(now).atZone(KST).toLocalDate());
        if (days <= 0) return "아까";
        if (days == 1) return "어제";
        if (days == 2) return "그저께";
        return "며칠 전에";
    }

    /**
     * What the false 안내원 says on a beat: the player's own words (today's, else this session's), in turn; with none, the
     * true one's line from the beat before (an echo one beat late), and nothing on the very first beat.
     */
    static String fakeLine(List<String> words, List<String> realLines, int beat) {
        if (!words.isEmpty()) return words.get(Math.floorMod(beat, words.size()));
        if (beat <= 0 || realLines.isEmpty()) return "…";
        return realLines.get(Math.floorMod(beat - 1, realLines.size()));
    }

    /** The true 안내원's line on a beat. */
    static String realLine(List<String> realLines, int beat) {
        return realLines.isEmpty() ? "…" : realLines.get(Math.floorMod(beat, realLines.size()));
    }

    /** Today's words as saved: "day\u0001line\u0002line…"; lines of another day are none. */
    static List<String> said(String raw, String today) {
        if (raw == null) return List.of();
        int cut = raw.indexOf('\u0001');
        if (cut < 0 || !raw.substring(0, cut).equals(today)) return List.of();
        String rest = raw.substring(cut + 1);
        if (rest.isEmpty()) return List.of();
        return List.of(rest.split("\u0002"));
    }

    /** Adds a line to today's words (the newest kept, at most max; a line of more than 40 letters is cut). */
    static String addSaid(String raw, String today, String line, int max) {
        List<String> now = new ArrayList<>(said(raw, today));
        String clean = line.replace('\u0001', ' ').replace('\u0002', ' ').strip();
        if (clean.isEmpty()) return raw == null ? today + "\u0001" : raw;
        if (clean.length() > 40) clean = clean.substring(0, 40) + "…";
        now.add(clean);
        while (now.size() > max) now.removeFirst();
        return today + "\u0001" + String.join("\u0002", now);
    }

    // ------------------------------------------------------------------------------------------
    // the room under the plaza (block coordinates; the plaza is |x|, |z| <= 10, so all of it stays under its floor)
    // ------------------------------------------------------------------------------------------

    /** The plaza floor's y (players stand one above). */
    static final int FLOOR = 63;
    /** The opening in the plaza floor: z 0, x -9..-7 (only its player sees it open). */
    static final int OPEN_Z = 0, OPEN_X1 = -9, OPEN_X2 = -7;
    /** Steps i = 1..7 at x = -10 + i, the stair block at y 63 - i (walking east, down). */
    static final int STEPS = 7;
    /** The room: floor y 55, air 56..59, ceiling 60; walls at x -3 (the door) and 7, z ±5. */
    static final int ROOM_Y = 55, ROOM_X1 = -3, ROOM_X2 = 7, ROOM_Z = 5;

    /**
     * On the opening (it looks open to them, so they may already be sinking onto the first step): down they go. The real
     * floor is still there for everyone else, so they are carried past it to the fourth step.
     */
    static boolean onOpening(int bx, double y, int bz) {
        return bz == OPEN_Z && bx >= OPEN_X1 && bx <= OPEN_X2 && y >= FLOOR - 0.5 && y <= FLOOR + 1.7;
    }

    /** Back up the stairs past the fourth step (under the real floor): up they go, beside the opening. */
    static boolean underOpening(int bx, double y, int bz) {
        return bz == OPEN_Z && bx >= OPEN_X1 - 1 && bx <= OPEN_X2 && y < FLOOR - 0.5 && y > ROOM_Y;
    }

    /** Inside the room. */
    static boolean inRoom(int bx, double y, int bz) {
        return bx > ROOM_X1 && bx < ROOM_X2 && Math.abs(bz) < ROOM_Z && y > ROOM_Y && y < ROOM_Y + 5;
    }

    /** Under the plaza at all (the stairwell or the room). */
    static boolean below(int bx, double y, int bz) {
        return bx >= OPEN_X1 && bx <= ROOM_X2 && Math.abs(bz) <= ROOM_Z && y < FLOOR && y > ROOM_Y - 1;
    }

    /** One block of the room: x, y, z and what it is ("air", "wall", "floor", "floor2", "ceiling", "stair", "lantern", "soul"). */
    record Cell(int x, int y, int z, String kind) {}

    /** Every block the room and its stairs set (air included, so the space is sure to be clear). */
    static List<Cell> room() {
        List<Cell> out = new ArrayList<>();
        // the shell and its air
        for (int x = ROOM_X1; x <= ROOM_X2; x++) {
            for (int z = -ROOM_Z; z <= ROOM_Z; z++) {
                boolean wall = Math.abs(z) == ROOM_Z || x == ROOM_X1 || x == ROOM_X2;
                out.add(new Cell(x, ROOM_Y, z, Math.floorMod(x + z, 2) == 0 ? "floor" : "floor2"));
                out.add(new Cell(x, ROOM_Y + 5, z, "ceiling"));
                for (int y = ROOM_Y + 1; y <= ROOM_Y + 4; y++) {
                    boolean door = z == 0 && x == ROOM_X1 && y <= ROOM_Y + 3;
                    out.add(new Cell(x, y, z, wall && !door ? "wall" : "air"));
                }
            }
        }
        // the stairwell: steps, its walls, the air above each step
        for (int i = 1; i <= STEPS; i++) {
            int x = -10 + i, sy = FLOOR - i;
            for (int y = ROOM_Y; y <= FLOOR - 1; y++) {
                for (int z : new int[]{-1, 1}) out.add(new Cell(x, y, z, "wall"));
                if (y < sy) out.add(new Cell(x, y, 0, "wall"));
                else if (y == sy) out.add(new Cell(x, y, 0, "stair"));
                else if (x != ROOM_X1 || y <= ROOM_Y + 3) out.add(new Cell(x, y, 0, "air"));
            }
        }
        // lights: lanterns at the near corners (as on the plaza), soul lanterns by the two at the back
        out.add(new Cell(ROOM_X1 + 1, ROOM_Y + 1, -ROOM_Z + 1, "lantern"));
        out.add(new Cell(ROOM_X1 + 1, ROOM_Y + 1, ROOM_Z - 1, "lantern"));
        out.add(new Cell(ROOM_X2 - 1, ROOM_Y + 1, -ROOM_Z + 1, "soul"));
        out.add(new Cell(ROOM_X2 - 1, ROOM_Y + 1, ROOM_Z - 1, "soul"));
        // later cells win (the stairwell's walls over the room's front wall, lights over air)
        java.util.Map<Long, Cell> last = new java.util.LinkedHashMap<>();
        for (Cell c : out) last.put(key(c.x, c.y, c.z), c);
        return new ArrayList<>(last.values());
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Where the two stand (x, z of their feet; y = ROOM_Y + 1), left and right as one walks in (east). */
    static final double[] LEFT = {5.5, -1.5}, RIGHT = {5.5, 2.5};
    /** Where someone lands going down (on step 4, facing east) and coming up (beside the opening). */
    static final double[] DOWN = {-5.5, FLOOR - 3, 0.5}, UP = {-7.5, FLOOR + 1, 2.5};
}
