package dev.stsv.economy.secret.garage;

import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.road.RoadCharacter;
import dev.stsv.economy.road.RoadModule;
import dev.stsv.economy.road.RoadNetwork;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Where it happened on this server: the bend he died on and his garage at the end of the same 국도. Worked out once from
 * the built roads ({@link Roadway#pick}: the sharpest bend of a hard road, else of any road), kept in eco.kv
 * ("secret.garage.place") so a sharper bend built later moves nothing, and worked out again only when that road is gone
 * or changed (looked at every {@code places.recheck-seconds}) or an admin asks. Without a road long enough there is no
 * place and the secret sleeps.
 * <p>
 * "Home" is the road's last point: a 국도 runs from the terminal (or the road it branches off) to its dock's gate, and he
 * was driving away from town. The garage stands {@code garage.back} points before that end, off the roadside; which side
 * and exactly where is fitted to the ground the first time someone comes near ({@link #fit}: the flattest of a few
 * spots), and kept too.
 */
final class Places {

    static final String KEY = "secret.garage.place";
    static final int UNKNOWN = Integer.MIN_VALUE, NONE = Integer.MIN_VALUE + 1;

    /**
     * @param outside the bend's outer side (+1: right of the way the road runs, -1: left): where he went off, where the flowers are
     * @param side    the garage's side of the road (0: not fitted to the ground yet)
     * @param floor   the garage's floor (the y to stand on)
     */
    record Site(String world, String dock, List<double[]> pts, int[] halves, boolean hard, int bend, int outside, double degrees,
                int garage, int side, int floor) {

        World bukkit() {
            return Bukkit.getWorld(world);
        }

        String signature() {
            double[] a = pts.getFirst(), b = pts.getLast();
            return pts.size() + ";" + Math.round(a[0]) + ";" + Math.round(a[1]) + ";" + Math.round(b[0]) + ";" + Math.round(b[1]);
        }

        String encode() {
            return world + ";" + signature() + ";" + bend + ";" + outside + ";" + garage + ";" + side + ";" + floor + ";" + dock;
        }

        Site fitted(int garage, int side, int floor) {
            return new Site(world, dock, pts, halves, hard, bend, outside, degrees, garage, side, floor);
        }

        boolean fitted() {
            return side != 0;
        }
    }

    private final EcoPlugin eco;
    private YamlConfiguration cfg = new YamlConfiguration();
    private Site site;
    private boolean loaded;
    private long checkedAt;
    private String why = "";

    Places(EcoPlugin eco) {
        this.eco = eco;
    }

    void settings(YamlConfiguration cfg) {
        this.cfg = cfg;
        loaded = false;   // read again with the new numbers (the kept place stays as long as its road does)
    }

    /** Why there is no place (for the status line). */
    String why() {
        return why;
    }

    /** The place as last worked out (null: none). */
    Site site() {
        return site;
    }

    /**
     * Looks at the roads again when that is due (or was never done). True when the place changed (also to or from none):
     * whatever is shown at the old place must go.
     */
    boolean check() {
        long now = System.currentTimeMillis();
        if (loaded && now - checkedAt < Math.max(30, cfg.getLong("places.recheck-seconds", 300)) * 1000L) return false;
        return refresh(false);
    }

    /** recompute: forget the kept place and choose anew. True when the place changed. */
    boolean refresh(boolean recompute) {
        loaded = true;
        checkedAt = System.currentTimeMillis();
        Site before = site;
        site = find(recompute);
        if (recompute && site == null) eco.kv().remove(KEY);
        if (before == null || site == null) return before != site;
        return !before.encode().equals(site.encode());
    }

    private Site find(boolean recompute) {
        RoadModule roads = eco.roads();
        if (roads == null) {
            why = "국도 모듈이 꺼져 있음";
            return null;
        }
        int window = Math.max(8, cfg.getInt("places.window", 30));
        int lead = Math.max(10, cfg.getInt("drive.lead", 30)) + 30;
        int back = Math.max(8, cfg.getInt("garage.back", 24));
        int tail = back + Math.max(30, cfg.getInt("places.min-run", 60));
        String kept = recompute ? null : eco.kv().get(KEY);
        String[] k = kept == null ? null : kept.split(";", 12);
        List<String> worlds = new ArrayList<>();
        List<RoadNetwork.Road> all = new ArrayList<>();
        List<List<double[]>> lines = new ArrayList<>();
        List<Boolean> hard = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            for (RoadNetwork.Road r : roads.builtRoads(w.getName())) {
                if (r.points() == null || r.halves() == null || r.points().size() < 2 || r.halves().length < r.points().size()) continue;
                worlds.add(w.getName());
                all.add(r);
                lines.add(r.points());
                hard.add(r.character() == RoadCharacter.HARD);
            }
        }
        if (all.isEmpty()) {
            why = "지어진 국도가 없음";
            return null;
        }
        if (k != null && k.length == 12) {   // the kept place, as long as its road is as it was
            for (int i = 0; i < all.size(); i++) {
                Site s = new Site(worlds.get(i), all.get(i).dock(), all.get(i).points(), all.get(i).halves(), hard.get(i), 0, 1, 0, 0, 0, 0);
                if (!s.world().equals(k[0]) || !s.dock().equals(k[11]) || !s.signature().equals(k[1] + ";" + k[2] + ";" + k[3] + ";" + k[4] + ";" + k[5])) continue;
                try {
                    int bend = Integer.parseInt(k[6]), outside = Integer.parseInt(k[7]) < 0 ? -1 : 1, garage = Integer.parseInt(k[8]);
                    int side = Integer.signum(Integer.parseInt(k[9])), floor = Integer.parseInt(k[10]), n = s.pts().size();
                    if (bend < 1 || bend >= n - 1 || garage <= bend || garage >= n) break;
                    if (site != null && site.encode().equals(kept)) return site;   // nothing changed: the copy we hold
                    return copy(s, bend, outside, Math.abs(Roadway.turn(s.pts(), bend, window / 2)), garage, side, floor);
                } catch (NumberFormatException e) {
                    break;
                }
            }
        }
        Roadway.Pick pick = Roadway.pick(lines, hard, window, lead, tail);
        if (pick == null) {
            why = "충분히 긴 국도가 없음 (" + (lead + tail + window) + "칸 이상)";
            return null;
        }
        Site s = new Site(worlds.get(pick.road()), all.get(pick.road()).dock(), all.get(pick.road()).points(), all.get(pick.road()).halves(),
                hard.get(pick.road()), 0, 1, 0, 0, 0, 0);
        Site found = copy(s, pick.bend().index(), pick.bend().outside(), pick.bend().degrees(), s.pts().size() - 1 - back, 0, 0);
        eco.kv().set(KEY, found.encode());
        eco.getLogger().info("[비밀] 폐차고: 커브 " + found.world() + " " + coords(found, found.bend()) + " (" + Math.round(found.degrees())
                + "도, " + (found.hard() ? "어려운길" : "어려운길이 없어 다른 길") + "), 차고 " + coords(found, found.garage()));
        return found;
    }

    /** Its own copy of the points (the road module makes its lists anew each time; ours stays for the drive). */
    private static Site copy(Site s, int bend, int outside, double degrees, int garage, int side, int floor) {
        List<double[]> pts = new ArrayList<>(s.pts().size());
        for (double[] p : s.pts()) pts.add(new double[]{p[0], p[1]});
        return new Site(s.world(), s.dock(), List.copyOf(pts), java.util.Arrays.copyOf(s.halves(), pts.size()), s.hard(), bend, outside, degrees,
                garage, side, floor);
    }

    static String coords(Site s, int index) {
        double[] p = s.pts().get(Math.clamp(index, 0, s.pts().size() - 1));
        return Math.round(p[0]) + ", " + Roadway.surfaceY(s.halves(), index) + ", " + Math.round(p[1]);
    }

    // ------------------------------------------------------------------------------------------
    // the ground
    // ------------------------------------------------------------------------------------------

    /**
     * The y to stand on at a column, looking from {@code up} over nearY down to {@code down} under it: over the first
     * solid block (no leaves) with two free blocks on it. {@link #UNKNOWN} while its chunk is not loaded, {@link #NONE}
     * when there is no such ground.
     */
    static int ground(World w, double x, double z, int nearY, int up, int down) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return UNKNOWN;
        for (int y = Math.min(w.getMaxHeight() - 3, nearY + up); y >= Math.max(w.getMinHeight(), nearY - down); y--) {
            Block b = w.getBlockAt(bx, y, bz);
            Material t = b.getType();
            if (!t.isSolid() || Tag.LEAVES.isTagged(t)) continue;
            Block over = b.getRelative(0, 1, 0), over2 = b.getRelative(0, 2, 0);
            if (over.isPassable() && over2.isPassable() && !over.isLiquid()) return y + 1;
        }
        return NONE;
    }

    /** The garage's side of the road and its floor, fitted to the ground; the same site while that ground is not loaded. */
    Site fit() {
        Site s = site;
        if (s == null || s.fitted()) return s;
        World w = s.bukkit();
        if (w == null) return s;
        double off = cfg.getDouble("garage.side", 8);
        Material road = eco.roads() == null ? null : eco.roads().surfaceBlock();
        int n = s.pts().size();
        double best = Double.MAX_VALUE;
        int bestAt = -1, bestSide = 0, bestFloor = 0;
        for (int shift : new int[]{0, -8, 8, -16, 16, -24}) {
            int g = s.garage() + shift;
            if (g <= s.bend() + 20 || g > n - 1 - 6) continue;
            int roadY = Roadway.surfaceY(s.halves(), g);
            for (int side : new int[]{s.outside(), -s.outside()}) {
                double[] t = Roadway.tangent(s.pts(), g, 4);
                double[] front = Roadway.beside(s.pts(), g, side * off, 4);
                double ax = -t[1] * side, az = t[0] * side;   // away from the road
                int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
                double score = Math.abs(shift) * 0.05;
                boolean known = true;
                for (double along : new double[]{-3, 0, 3}) {
                    for (double deep : new double[]{0.5, 3.5, 6.5}) {
                        double x = front[0] + t[0] * along + ax * deep, z = front[1] + t[1] * along + az * deep;
                        int y = ground(w, x, z, roadY, 6, 8);
                        if (y == UNKNOWN) known = false;
                        else if (y == NONE) score += 6;
                        else {
                            min = Math.min(min, y);
                            max = Math.max(max, y);
                            if (road != null && w.getBlockAt((int) Math.floor(x), y - 1, (int) Math.floor(z)).getType() == road) score += 10;
                        }
                    }
                }
                if (!known) {
                    if (shift == 0) return s;   // the ground at the place itself cannot be read yet: later
                    continue;
                }
                if (max == Integer.MIN_VALUE) continue;
                score += (max - min) + Math.abs(max - (roadY + 1)) * 0.5;
                if (score < best) {
                    best = score;
                    bestAt = g;
                    bestSide = side;
                    bestFloor = max;
                }
            }
        }
        if (bestAt < 0) {   // no ground at all near the road there (a bridge): on the road's own level, on the bend's outer side
            bestAt = s.garage();
            bestSide = s.outside();
            bestFloor = Roadway.surfaceY(s.halves(), s.garage()) + 1;
        }
        site = s.fitted(bestAt, bestSide, bestFloor);
        eco.kv().set(KEY, site.encode());
        return site;
    }

    /** The garage's frame: the middle of its front on the floor, facing the road. Null before it is fitted. */
    Location garage() {
        Site s = site;
        World w = s == null ? null : s.bukkit();
        if (w == null || !s.fitted()) return null;
        double[] t = Roadway.tangent(s.pts(), s.garage(), 4);
        double[] front = Roadway.beside(s.pts(), s.garage(), s.side() * cfg.getDouble("garage.side", 8), 4);
        return new Location(w, front[0], s.floor(), front[1], Roadway.yaw(t[1] * s.side(), -t[0] * s.side()), 0);
    }

    /** Roughly where the garage is, fitted or not (to tell how near someone is). */
    Location garageNear() {
        Site s = site;
        World w = s == null ? null : s.bukkit();
        if (w == null) return null;
        double[] p = s.pts().get(s.garage());
        return new Location(w, p[0], Roadway.height(s.halves(), s.garage()), p[1]);
    }

    /** The middle of the road at the bend. */
    Location bend() {
        Site s = site;
        World w = s == null ? null : s.bukkit();
        if (w == null) return null;
        double[] p = s.pts().get(s.bend());
        return new Location(w, p[0], Roadway.height(s.halves(), s.bend()), p[1]);
    }

    /** A spot on the bend's outer roadside (a frame on the ground, facing the road), and the top of the guardrail it stands against (null: no rail there). */
    record Edge(Location at, Location rail) {}

    private static boolean rail(Material t) {
        return Tag.WALLS.isTagged(t) || Tag.FENCES.isTagged(t) || t == Material.YELLOW_TERRACOTTA || t == Material.BLACK_TERRACOTTA || t == Material.END_ROD;
    }

    /**
     * The bend's outer roadside {@code along} points past the bend: just inside the guardrail there (the first wall, fence
     * or striped block found going out from the kerb), or {@code side} blocks from the road's middle where there is no
     * rail. Null while its chunk is not loaded.
     */
    Edge edge(double along, double side) {
        Site s = site;
        World w = s == null ? null : s.bukkit();
        if (w == null) return null;
        double at = s.bend() + along;
        double[] t = Roadway.tangent(s.pts(), at, 4);
        int roadY = Roadway.surfaceY(s.halves(), (int) Math.round(at));
        float yaw = Roadway.yaw(t[1] * s.outside(), -t[0] * s.outside());
        double lateral = side;
        Location top = null;
        find:
        for (double l = 3.5; l <= 8.01; l += 0.5) {
            double[] c = Roadway.beside(s.pts(), at, s.outside() * l, 4);
            int bx = (int) Math.floor(c[0]), bz = (int) Math.floor(c[1]);
            if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
            for (int y = roadY + 2; y >= roadY - 1; y--) {
                if (!rail(w.getBlockAt(bx, y, bz).getType())) continue;
                lateral = Math.max(3.4, l - 0.9);
                top = new Location(w, bx + 0.5, y + 1, bz + 0.5, yaw, 0);
                break find;
            }
        }
        double[] p = Roadway.beside(s.pts(), at, s.outside() * lateral, 4);
        int y = ground(w, p[0], p[1], roadY, 0, 2);
        if (y == UNKNOWN) return null;
        return new Edge(new Location(w, p[0], y == NONE ? roadY + 1 : y, p[1], yaw, 0), top);
    }
}
