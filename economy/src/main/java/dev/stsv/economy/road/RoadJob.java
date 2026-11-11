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
    /** the road it branches off at its start ({@link RoadNetwork#branch}): that road's dock and how far its gate is from here; null: none */
    public String branchOf;
    public double branchKm;
    /** the road's surface at each point, half blocks ({@link RoadProfile#halves}), once worked out (BUILD): what other roads keep clear of */
    public int[] halves;
    /** where the route is planned from and to (the end: just in front of the dock's gate, in line with it) */
    public double startX, startZ, endX, endZ;
    /**
     * The dock's gate (the middle of its front edge, y its floor): the road's last point, at the floor's level, so it runs
     * on into the dock. NaN / MIN_VALUE: none (a road saved before roads went up to the gate: it ends at end).
     */
    public double gateX = Double.NaN, gateZ = Double.NaN;
    /**
     * Where a road leaving a departure terminal's exit runs straight out to before its route turns (the start is the exit),
     * NaN: none.
     */
    public double leadX = Double.NaN, leadZ = Double.NaN;
    public int gateY = Integer.MIN_VALUE;
    /**
     * A patch (/관리자 국도 &lt;하역장&gt; 이어짓기): the missing piece from an older road's end to the gate, saved beside the road
     * (&lt;dock&gt;.tail.txt); the road's own file stays as it is.
     */
    public boolean tail;
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
    /** the road's number on its signs (국도 n호선): roads numbered in the order they were first made; 0 = not given yet */
    public int route;
    /** which set of signs is this road's: signs of an older one (or cleared: /관리자 국도 표지판 정리) go when their chunk loads */
    public int signs;

    public static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    public static int cx(long key) {
        return (int) (key >> 32);
    }

    public static int cz(long key) {
        return (int) key;
    }

    /** The file it is saved in (without .txt): the dock's name, a patch's with ".tail". */
    public String file() {
        return tail ? dock + ".tail" : dock;
    }

    /** Whether it leaves straight out of a terminal's exit first (not when it branches off another road). */
    public boolean led() {
        return !Double.isNaN(leadX) && branchOf == null;
    }

    /** Where the route is planned from: the end of the straight way out, else the start. */
    public double[] from() {
        return led() ? new double[]{leadX, leadZ} : new double[]{startX, startZ};
    }

    /** Whether it goes up to the dock's gate. */
    public boolean gated() {
        return !Double.isNaN(gateX) && gateY != Integer.MIN_VALUE;
    }

    /** Where the road ends: the gate, or (an old road) the planned end. */
    public double[] last() {
        return gated() ? new double[]{gateX, gateZ} : new double[]{endX, endZ};
    }

    /**
     * The route's corners from the planner's cells (chunk x, z: their middles; the first and last are replaced by the
     * start and the end), then the gate when there is one: the road always runs from the start up to the gate.
     */
    public void route(List<int[]> cells) {
        corners.clear();
        corners.add(new double[]{startX, startZ});
        if (led()) corners.add(new double[]{leadX, leadZ});
        for (int i = 1; i < cells.size() - 1; i++) corners.add(new double[]{cells.get(i)[0] * 16 + 8.5, cells.get(i)[1] * 16 + 8.5});
        corners.add(new double[]{endX, endZ});
        if (gated() && Math.hypot(gateX - endX, gateZ - endZ) > 0.5) corners.add(new double[]{gateX, gateZ});
        ground.clear();
        for (int i = 0; i < points().size(); i++) ground.add(new Ground());
        cursor = 0;
    }

    /** The surveyed corridor as the planner sees it. */
    public RoadPlanner.Field field() {
        return field(null);
    }

    /** The surveyed corridor and the roads already there ({@link RoadNetwork}; null: none). */
    public RoadPlanner.Field field(RoadNetwork net) {
        return new RoadPlanner.Field() {
            public boolean road(int x, int z) {
                return net != null && net.cell(x, z);
            }

            public boolean nearRoad(int x, int z) {
                return net != null && net.near(x, z);
            }

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

    /**
     * A copy to write off the main thread ({@link #write} on it gives the same text): every field and list copied, the
     * points' ground copied one by one (the job goes on changing them meanwhile).
     */
    public RoadJob snapshot() {
        RoadJob j = new RoadJob();
        j.dock = dock;
        j.world = world;
        j.branchOf = branchOf;
        j.branchKm = branchKm;
        j.halves = halves == null ? null : halves.clone();
        j.startX = startX;
        j.startZ = startZ;
        j.endX = endX;
        j.endZ = endZ;
        j.gateX = gateX;
        j.gateZ = gateZ;
        j.leadX = leadX;
        j.leadZ = leadZ;
        j.gateY = gateY;
        j.tail = tail;
        j.phase = phase;
        j.running = running;
        j.cells.putAll(cells);   // records: never changed
        for (double[] c : corners) j.corners.add(c.clone());
        for (Ground g : ground) {
            Ground c = new Ground();
            c.ground = g.ground;
            c.surface = g.surface;
            c.water = g.water;
            j.ground.add(c);
        }
        j.cursor = cursor;
        j.started = started;
        j.route = route;
        j.signs = signs;
        return j;
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
        b.append("route=").append(route).append('\n');
        b.append("signs=").append(signs).append('\n');
        if (gated()) b.append("gate=").append(num(gateX)).append(',').append(gateY).append(',').append(num(gateZ)).append('\n');
        if (tail) b.append("tail=true\n");
        if (!Double.isNaN(leadX)) b.append("lead=").append(num(leadX)).append(',').append(num(leadZ)).append('\n');
        if (branchOf != null) b.append("branch=").append(branchOf).append(',').append(num(branchKm)).append('\n');
        if (halves != null) {
            b.append("halves=");
            for (int i = 0; i < halves.length; i++) b.append(i > 0 ? "," : "").append(halves[i]);
            b.append('\n');
        }
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
                case "route" -> j.route = Integer.parseInt(v);
                case "signs" -> j.signs = Integer.parseInt(v);
                case "gate" -> {
                    j.gateX = Double.parseDouble(p[0]);
                    j.gateY = Integer.parseInt(p[1]);
                    j.gateZ = Double.parseDouble(p[2]);
                }
                case "tail" -> j.tail = Boolean.parseBoolean(v);
                case "lead" -> {
                    j.leadX = Double.parseDouble(p[0]);
                    j.leadZ = Double.parseDouble(p[1]);
                }
                case "branch" -> {
                    j.branchOf = p[0];
                    j.branchKm = Double.parseDouble(p[1]);
                }
                case "halves" -> {
                    j.halves = new int[p.length];
                    for (int i = 0; i < p.length; i++) j.halves[i] = Integer.parseInt(p[i].trim());
                }
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
