package dev.stsv.economy.road;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A 국도 being built (/관리자 국도), all of it that must survive a restart: where it goes, which phase it is in, the survey,
 * the route and how far the building got. Written as plain text lines ({@link #write}, {@link #read}) to
 * plugins/STSV-Economy/roads/&lt;dock&gt;.txt. Pure (unit-tested).
 *
 * <p>Phases: SURVEY (heightmaps of the corridor's chunks, loaded asynchronously) → PLAN (A* and smoothing:
 * {@link RoadPlanner}) → PREPARE (the chunks along the route and a few round it are generated, the ground under every
 * point read) → BUILD (cross-sections placed point by point: {@link RoadProfile}) → DONE.
 */
public final class RoadJob {

    public enum Phase { SURVEY, PLAN, PREPARE, BUILD, DONE }

    /** A surveyed cell (a chunk): its middle's ground height, water depth, unevenness, a player's land. */
    public record Cell(double height, double water, double rough, boolean blocked) {}

    /** A point of the route: its ground's top block, whether it is water and the water's top block (-1 = not read yet). */
    public static final class Ground {
        public int ground = Integer.MIN_VALUE, surface;
        public boolean water;

        public boolean read() {
            return ground != Integer.MIN_VALUE;
        }
    }

    public String dock, world;
    public double startX, startZ, endX, endZ;
    public Phase phase = Phase.SURVEY;
    /** false: stopped (/관리자 국도 중지); a restart does not resume it until it is started again */
    public boolean running = true;
    public final Map<Long, Cell> cells = new HashMap<>();
    /** the route's corners (block x, z) */
    public final List<double[]> corners = new ArrayList<>();
    /** the ground under each point of the route (one a block along it) */
    public final List<Ground> ground = new ArrayList<>();
    /** the next point to build */
    public int cursor;
    public long started;

    public static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    public static int cx(long key) {
        return (int) (key >> 32);
    }

    public static int cz(long key) {
        return (int) key;
    }

    /** The surveyed corridor as the planner sees it. */
    public RoadPlanner.Field field() {
        return new RoadPlanner.Field() {
            public boolean known(int x, int z) {
                return cells.containsKey(key(x, z));
            }

            public double height(int x, int z) {
                Cell c = cells.get(key(x, z));
                return c == null ? 0 : c.height();
            }

            public double water(int x, int z) {
                Cell c = cells.get(key(x, z));
                return c == null ? 0 : c.water();
            }

            public double rough(int x, int z) {
                Cell c = cells.get(key(x, z));
                return c == null ? 0 : c.rough();
            }

            public boolean blocked(int x, int z) {
                Cell c = cells.get(key(x, z));
                return c != null && c.blocked();
            }
        };
    }

    /** Every point of the route, one a block. */
    public List<double[]> points() {
        return RoadPlanner.densify(corners, 1);
    }

    /** How much of the route is built (0 .. 1). */
    public double progress() {
        return ground.isEmpty() ? 0 : Math.min(1, cursor / (double) ground.size());
    }

    private static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.3f", d);
    }

    public String write() {
        StringBuilder b = new StringBuilder();
        b.append("dock=").append(dock).append('\n');
        b.append("world=").append(world).append('\n');
        b.append("start=").append(num(startX)).append(',').append(num(startZ)).append('\n');
        b.append("end=").append(num(endX)).append(',').append(num(endZ)).append('\n');
        b.append("phase=").append(phase).append('\n');
        b.append("running=").append(running).append('\n');
        b.append("cursor=").append(cursor).append('\n');
        b.append("started=").append(started).append('\n');
        for (Map.Entry<Long, Cell> e : cells.entrySet()) {
            Cell c = e.getValue();
            b.append("cell=").append(cx(e.getKey())).append(',').append(cz(e.getKey())).append(',').append(num(c.height())).append(',')
                    .append(num(c.water())).append(',').append(num(c.rough())).append(',').append(c.blocked() ? 1 : 0).append('\n');
        }
        for (double[] c : corners) b.append("corner=").append(num(c[0])).append(',').append(num(c[1])).append('\n');
        for (Ground g : ground) {
            b.append("ground=").append(g.read() ? g.ground : "-").append(',').append(g.water ? 1 : 0).append(',').append(g.surface).append('\n');
        }
        return b.toString();
    }

    public static RoadJob read(String text) {
        RoadJob j = new RoadJob();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq), v = line.substring(eq + 1).trim();
            String[] p = v.split(",");
            switch (k) {
                case "dock" -> j.dock = v;
                case "world" -> j.world = v;
                case "start" -> {
                    j.startX = Double.parseDouble(p[0]);
                    j.startZ = Double.parseDouble(p[1]);
                }
                case "end" -> {
                    j.endX = Double.parseDouble(p[0]);
                    j.endZ = Double.parseDouble(p[1]);
                }
                case "phase" -> j.phase = Phase.valueOf(v);
                case "running" -> j.running = Boolean.parseBoolean(v);
                case "cursor" -> j.cursor = Integer.parseInt(v);
                case "started" -> j.started = Long.parseLong(v);
                case "cell" -> j.cells.put(key(Integer.parseInt(p[0]), Integer.parseInt(p[1])),
                        new Cell(Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]), p[5].equals("1")));
                case "corner" -> j.corners.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])});
                case "ground" -> {
                    Ground g = new Ground();
                    if (!p[0].equals("-")) g.ground = Integer.parseInt(p[0]);
                    g.water = p[1].equals("1");
                    g.surface = Integer.parseInt(p[2]);
                    j.ground.add(g);
                }
                default -> { }
            }
        }
        return j;
    }
}
