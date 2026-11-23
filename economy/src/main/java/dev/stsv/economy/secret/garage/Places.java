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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where it happened on this server: the bend he died on and his garage at the end of the same 국도. Worked out once from
 * the built roads ({@link Roadway#pick}: the sharpest bend of a hard road, else of any road), kept in eco.kv
 * ("secret.garage.place") so a sharper bend built later moves nothing, and worked out again only when that road is gone
 * or changed (looked at every {@code places.recheck-seconds}) or an admin asks. Without a road long enough there is no
 * place and the secret sleeps. A road still being built (it had fewer points at the last look) is not chosen until it
 * stops growing, and a kept road that is missing at one look only (a file that could not be read) moves nothing.
 * <p>
 * "Home" is the road's last point: a 국도 runs from the terminal (or the road it branches off) to its dock's gate, and he
 * was driving away from town. The garage stands {@code garage.back} points before that end, off the roadside; which side
 * and exactly where is fitted to the ground the first time someone comes near ({@link #fit}: the flattest spot whose
 * every column is dry ground, {@link Dry}; never over water), and kept too.
 */
final class Places {

    static final String KEY = "secret.garage.place";
    static final int UNKNOWN = Integer.MIN_VALUE, NONE = Integer.MIN_VALUE + 1;

    /**
     * @param outside the bend's outer side (+1: right of the way the road runs, -1: left): where he went off, where the flowers are
     * @param side    the garage's side of the road (0: not fitted to the ground yet), and how far from the road's middle its
     *                front stands (±1, as kept before: {@code garage.side})
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
    /** why the garage could not be fitted (no dry ground anywhere near the road): for the status line; "" when it could */
    private String dryWhy = "";
    /** the place ({@link Site#encode}) last found with no dry ground, and when (looked for again ten minutes later) */
    private String dryFor = "";
    private long dryAt;
    /** the place last looked at further along the road while some of that land was not loaded, and when */
    private String waitFor = "";
    private long waitAt;
    /** the fitted place last found still dry, and when (looked at again ten minutes later) */
    private String verifiedFor = "";
    private long verifiedAt;
    /** every road as it was at the last look at all of them ("world;dock;start;points"): one that grew since is still being built */
    private Set<String> seen = Set.of();
    /** looks in a row at which the kept place's road was not there */
    private int misses;

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

    /** Why the garage has no spot yet although there is a place ("" when nothing is wrong). */
    String dryWhy() {
        Site s = site;
        return s == null || s.fitted() ? "" : dryWhy;
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

    /** The next look at the roads comes in half a minute (something was not sure at this one). */
    private void soon() {
        checkedAt = System.currentTimeMillis() - Math.max(30, cfg.getLong("places.recheck-seconds", 300)) * 1000L + 30_000L;
    }

    private static boolean usable(RoadNetwork.Road r) {
        return r.points() != null && r.halves() != null && r.points().size() >= 2 && r.halves().length >= r.points().size();
    }

    /** The kept place ({@link Site#encode}, split) on one of these roads of its world, when that road is as it was; else null. */
    private Site kept(String[] k, String kept, List<RoadNetwork.Road> roads, int window) {
        for (RoadNetwork.Road r : roads) {
            if (!usable(r) || !String.valueOf(r.dock()).equals(k[11])) continue;
            Site s = new Site(k[0], r.dock(), r.points(), r.halves(), r.character() == RoadCharacter.HARD, 0, 1, 0, 0, 0, 0);
            if (!s.signature().equals(k[1] + ";" + k[2] + ";" + k[3] + ";" + k[4] + ";" + k[5])) continue;
            try {
                int bend = Integer.parseInt(k[6]), outside = Integer.parseInt(k[7]) < 0 ? -1 : 1, garage = Integer.parseInt(k[8]);
                int side = Math.clamp(Integer.parseInt(k[9]), -64, 64), floor = Integer.parseInt(k[10]), n = s.pts().size();
                if (bend < 1 || bend >= n - 1 || garage <= bend || garage >= n) return null;
                if (site != null && site.encode().equals(kept)) return site;   // nothing changed: the copy we hold
                return copy(s, bend, outside, Math.abs(Roadway.turn(s.pts(), bend, window / 2)), garage, side, floor);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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
        if (k != null && k.length != 12) k = null;
        // the kept place, as long as its road is as it was (only its own world's roads are read: the usual look)
        if (k != null && Bukkit.getWorld(k[0]) != null) {
            Site s = kept(k, kept, roads.builtRoads(k[0]), window);
            if (s != null) {
                misses = 0;
                return s;
            }
        }
        // every road there is
        List<String> worlds = new ArrayList<>();
        List<RoadNetwork.Road> all = new ArrayList<>();
        List<Boolean> settled = new ArrayList<>();
        Set<String> now = new HashSet<>();
        for (World w : Bukkit.getWorlds()) {
            for (RoadNetwork.Road r : roads.builtRoads(w.getName())) {
                if (!usable(r)) continue;
                double[] a = r.points().getFirst();
                String id = w.getName() + ";" + r.dock() + ";" + Math.round(a[0]) + ";" + Math.round(a[1]) + ";" + r.points().size();
                worlds.add(w.getName());
                all.add(r);
                settled.add(recompute || seen.contains(id));   // (an admin's 위치다시: the roads as they are now)
                now.add(id);
            }
        }
        boolean first = seen.isEmpty();   // nothing to tell a growing road by yet: the second look comes soon
        seen = now;
        if (k != null && ++misses < 2) {
            // its road is not there at this look: once may be a file that could not be read. Nothing moves yet
            why = "저장된 길이 보이지 않음 (곧 다시 확인)";
            soon();
            return site;
        }
        if (all.isEmpty()) {
            why = "지어진 국도가 없음";
            return null;
        }
        List<Integer> index = new ArrayList<>();
        List<List<double[]>> lines = new ArrayList<>();
        List<Boolean> hard = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (!settled.get(i)) continue;
            index.add(i);
            lines.add(all.get(i).points());
            hard.add(all.get(i).character() == RoadCharacter.HARD);
        }
        Roadway.Pick pick = Roadway.pick(lines, hard, window, lead, tail);
        if (pick == null) {
            if (index.size() < all.size()) {   // roads seen for the first time (looked at again soon), or still growing (at the usual look)
                why = "국도를 한 번 더 확인하는 중 (짓고 있는 길은 다 지어질 때까지 기다림)";
                if (first) soon();
            } else why = "충분히 긴 국도가 없음 (" + (lead + tail + window) + "칸 이상)";
            return null;
        }
        misses = 0;
        int at = index.get(pick.road());
        Site s = new Site(worlds.get(at), all.get(at).dock(), all.get(at).points(), all.get(at).halves(), hard.get(pick.road()), 0, 1, 0, 0, 0, 0);
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
     * full, solid, dry block with three open blocks over it ({@link Dry}: never water, lava, ice or a sea floor).
     * {@link #UNKNOWN} while its chunk is not loaded, {@link #NONE} when there is no such ground.
     */
    static int ground(World w, double x, double z, int nearY, int up, int down) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return UNKNOWN;
        int y = Dry.ground(Dry.world(w), bx, bz, Math.min(w.getMaxHeight() - 4, nearY + up), Math.max(w.getMinHeight(), nearY - down));
        return Dry.found(y) ? y : NONE;
    }

    /** How far from the road's middle the garage's front stands at the usual place ({@code garage.side}). */
    private double baseOff() {
        return Math.max(4, cfg.getDouble("garage.side", 8));
    }

    /** How far from the road's middle this site's garage front stands. */
    double off(Site s) {
        return Math.abs(s.side()) > 1 ? Math.abs(s.side()) : baseOff();
    }

    /** The garage's frame: the middle of its front, on the road's level, facing the road. */
    private static Location frame(World w, Site s, int g, int side, double off) {
        double[] t = Roadway.tangent(s.pts(), g, 4);
        double[] front = Roadway.beside(s.pts(), g, side * off, 4);
        return new Location(w, front[0], Roadway.surfaceY(s.halves(), g), front[1], Roadway.yaw(t[1] * side, -t[0] * side), 0);
    }

    /** The ground under a garage (its floor, the apron before its door) and what that ground is. */
    private record Foot(List<int[]> cols, Dry.Check check) {}

    /** Every column under a garage at this frame, against the dry-ground rule near nearY; null while any of it is not loaded. */
    private static Foot footprint(World w, double x, double z, float yaw, int nearY) {
        List<int[]> cols = Dry.cells(x, z, yaw, -3.45, 3.45, -6.95, 1.55);
        for (int[] c : cols) if (!w.isChunkLoaded(c[0] >> 4, c[1] >> 4)) return null;
        return new Foot(cols, Dry.footprint(Dry.world(w), cols, Math.min(w.getMaxHeight() - 4, nearY + 6), Math.max(w.getMinHeight(), nearY - 8)));
    }

    private static Foot footprint(World w, Site s, int g, int side, double off) {
        Location f = frame(w, s, g, side, off);
        return footprint(w, f.getX(), f.getZ(), f.getYaw(), f.getBlockY());
    }

    /** The best spot of a search so far, and how many spots could not be read. */
    private static final class Best {
        double score = Double.MAX_VALUE;
        int at = -1, side, floor, unknown;
        double off;
    }

    /** Looks at a garage at road point g on this side, off blocks out: dry everywhere under it, the flatter the better. */
    private void consider(Best b, World w, Site s, int g, int side, double off, Material road, double extra) {
        Foot f = footprint(w, s, g, side, off);
        if (f == null) {
            b.unknown++;
            return;
        }
        if (!f.check().ok()) return;
        int roadY = Roadway.surfaceY(s.halves(), g), onRoad = 0;
        if (road != null)
            for (int i = 0; i < f.cols().size(); i++)
                if (w.getBlockAt(f.cols().get(i)[0], f.check().ys()[i] - 1, f.cols().get(i)[1]).getType() == road) onRoad++;
        double score = extra + (f.check().max() - f.check().min()) + Math.abs(f.check().max() - (roadY + 1)) * 0.5 + 90.0 * onRoad / f.cols().size();
        if (score < b.score) {
            b.score = score;
            b.at = g;
            b.side = side;
            b.off = off;
            b.floor = f.check().max();
        }
    }

    /**
     * The garage's side of the road, how far out and its floor, fitted to the ground: the flattest spot whose every column
     * is dry ground ({@link Dry}). Looked for near the road's end first (as always: ±24 points, both sides), then further
     * (±60 points, up to 16 blocks from the road), then back along the road towards the bend (never closer than 20 points
     * past it). The same site while the ground there is not loaded. When there is no dry ground anywhere on that stretch
     * it is not fitted at all (nothing is shown; {@link #dryWhy}) and looked for again ten minutes later.
     */
    Site fit() {
        Site s = site;
        if (s == null || s.fitted()) return s;
        World w = s.bukkit();
        if (w == null) return s;
        long now = System.currentTimeMillis();
        if (s.encode().equals(dryFor) && now - dryAt < 600_000L) return s;
        if (s.encode().equals(waitFor) && now - waitAt < 15_000L) return s;   // looked further a moment ago, some of it not loaded
        Material road = eco.roads() == null ? null : eco.roads().surfaceBlock();
        int n = s.pts().size();
        double off0 = baseOff();
        // near the road's end, at the usual distance
        Best b = new Best();
        for (int shift : new int[]{0, -8, 8, -16, 16, -24}) {
            int g = s.garage() + shift;
            if (g <= s.bend() + 20 || g > n - 1 - 6) continue;
            for (int side : new int[]{s.outside(), -s.outside()}) {
                int before = b.unknown;
                consider(b, w, s, g, side, off0, road, Math.abs(shift) * 0.05);
                if (shift == 0 && b.unknown > before) return s;   // the ground at the place itself cannot be read yet: later
            }
        }
        double[] offs = off0 >= 16 ? new double[]{off0}
                : java.util.stream.DoubleStream.of(off0, Math.round(off0) + 3, Math.round(off0) + 6, 16).map(o -> Math.min(16, o)).distinct().toArray();
        // further along and further out
        if (b.at < 0) {
            for (int shift = 0; shift <= 60; shift += 4)
                for (int sign : shift == 0 ? new int[]{1} : new int[]{-1, 1}) {
                    int g = s.garage() + sign * shift;
                    if (g <= s.bend() + 20 || g > n - 1 - 6) continue;
                    for (int side : new int[]{s.outside(), -s.outside()})
                        for (double off : offs) consider(b, w, s, g, side, off, road, shift * 0.05 + (off - off0) * 0.3);
                }
            if (b.at < 0 && b.unknown > 0) {   // some of it is not loaded yet: later, when someone is nearer
                waitFor = s.encode();
                waitAt = now;
                return s;
            }
        }
        // back towards the bend
        int unread = 0;
        if (b.at < 0) {
            b.unknown = 0;
            for (int g = s.garage() - 64; g > s.bend() + 20; g -= 4) {
                if (g > n - 1 - 6) continue;
                for (int side : new int[]{s.outside(), -s.outside()})
                    for (double off : offs) consider(b, w, s, g, side, off, road, (s.garage() - g) * 0.05 + (off - off0) * 0.3);
                if (b.at >= 0) break;   // the first stretch with dry ground, nearest the road's end
            }
            unread = b.unknown;
        }
        if (b.at < 0) {
            dryWhy = "차고를 세울 마른 땅이 없음 (길 끝 ±60칸 · 길가 " + Math.round(off0) + "~16칸, 커브 뒤 20칸까지 물 · 얼음뿐"
                    + (unread > 0 ? "; 커브 쪽 " + unread + "곳은 땅이 아직 불러와지지 않아 못 봄" : "") + ". 10분 뒤 다시 찾음)";
            if (!s.encode().equals(dryFor))
                eco.getLogger().warning("[비밀] 폐차고: " + dryWhy + " — 차고 " + coords(s, s.garage()) + ". `연출 폐차고 차고여기` 로 직접 정할 수 있음");
            dryFor = s.encode();
            dryAt = now;
            return s;
        }
        dryWhy = "";
        dryFor = "";
        int off = (int) Math.round(b.off);
        site = s.fitted(b.at, b.side * (Math.abs(b.off - baseOff()) < 1e-6 ? 1 : Math.max(2, off)), b.floor);
        eco.kv().set(KEY, site.encode());
        return site;
    }

    /**
     * Whether the fitted garage still stands on dry ground: no column of it wet (looked at again every ten minutes while
     * someone is near; never loads land). When one is (water was poured, the sea came, it was fitted before this rule, an
     * admin put it on water) it is unfitted, to be fitted again: false, and whatever is shown of it must go.
     */
    boolean stillDry() {
        Site s = site;
        World w = s == null ? null : s.bukkit();
        if (w == null || !s.fitted()) return true;
        long now = System.currentTimeMillis();
        if (s.encode().equals(verifiedFor) && now - verifiedAt < 600_000L) return true;
        Foot f = footprint(w, s, s.garage(), Integer.signum(s.side()), off(s));
        if (f == null) return true;   // not all of it loaded: another time
        verifiedFor = s.encode();
        verifiedAt = now;
        if (!f.check().wet()) return true;   // (a column with no ground under it, as fitted before this rule, may stay: only water moves it)
        eco.getLogger().warning("[비밀] 폐차고: 저장된 차고 자리 " + coords(s, s.garage()) + " 가 마른 땅이 아님 (" + Dry.why(f.check())
                + "). 다시 땅에 맞춤");
        site = s.fitted(s.garage(), 0, 0);
        eco.kv().set(KEY, site.encode());
        return false;
    }

    /**
     * Admin (연출 폐차고 커브여기): the bend at the road point nearest this spot, on whichever built road of its world is
     * nearest. The garage stays where it was when it is on the same road and still far enough on; else it goes back to
     * that road's end (fitted to the ground when someone comes near). Returns null when done, else why not.
     */
    String setBend(Location at) {
        RoadModule roads = eco.roads();
        if (roads == null || at.getWorld() == null) return "국도 모듈이 꺼져 있어요";
        int window = Math.max(8, cfg.getInt("places.window", 30));
        int lead = Math.max(10, cfg.getInt("drive.lead", 30)) + 30;
        int back = Math.max(8, cfg.getInt("garage.back", 24));
        RoadNetwork.Road best = null;
        int bestAt = -1;
        double bestD = Double.MAX_VALUE;
        for (RoadNetwork.Road r : roads.builtRoads(at.getWorld().getName())) {
            if (!usable(r)) continue;
            int i = (int) Math.round(Roadway.project(r.points(), at.getX(), at.getZ(), 0, r.points().size() - 1));
            double[] q = r.points().get(i);
            double d = Math.hypot(q[0] - at.getX(), q[1] - at.getZ());
            if (d < bestD) {
                bestD = d;
                best = r;
                bestAt = i;
            }
        }
        if (best == null || bestD > 24) return "가까운 국도가 없어요 (길 가운데에서 24칸 안에 서 주세요)";
        int n = best.points().size();
        if (bestAt < lead) return "길의 시작에 너무 가까워요 (차가 커브 " + (lead - 30) + "칸 앞에서 기다려야 해서, 시작에서 " + lead + "칸 넘게 들어간 곳이어야 해요)";
        if (bestAt > n - 1 - 6 - 21) return "길의 끝에 너무 가까워요 (커브 뒤로 차고까지 30칸 넘게 남아야 해요)";
        Site road = new Site(at.getWorld().getName(), best.dock(), best.points(), best.halves(), best.character() == RoadCharacter.HARD, 0, 1, 0, 0, 0, 0);
        double t = Roadway.turn(road.pts(), bestAt, window / 2);
        Site old = site;
        boolean same = old != null && old.world().equals(road.world()) && String.valueOf(old.dock()).equals(String.valueOf(road.dock()))
                && old.signature().equals(road.signature());
        boolean keepGarage = same && old.garage() > bestAt + 20;
        int garage = keepGarage ? old.garage() : Math.max(bestAt + 21, n - 1 - back);
        Site s = copy(road, bestAt, t > 0 ? -1 : 1, Math.abs(t), garage, keepGarage ? old.side() : 0, keepGarage ? old.floor() : 0);
        return keep(s);
    }

    /**
     * Admin (연출 폐차고 차고여기): the garage at the road point nearest this spot, on the side of the road this spot is
     * on, its floor fitted to the ground there. Only on the bend's road, after the bend, and only on dry ground (every
     * column under it, {@link Dry}): never on water. Null when done, else why not.
     */
    String setGarage(Location at) {
        Site s = site;
        if (s == null) return "커브가 아직 없어요 (" + why + ")";
        if (at.getWorld() == null || !at.getWorld().getName().equals(s.world())) return "커브가 있는 월드(" + s.world() + ")에서 해 주세요";
        int n = s.pts().size();
        int i = (int) Math.round(Roadway.project(s.pts(), at.getX(), at.getZ(), 0, n - 1));
        double[] q = s.pts().get(i);
        if (Math.hypot(q[0] - at.getX(), q[1] - at.getZ()) > 40) return "커브가 있는 그 국도 옆(길 가운데에서 40칸 안)에 서 주세요";
        if (i <= s.bend() + 20) return "커브보다 길 끝 쪽으로 20칸 넘게 지난 곳이어야 해요 (유령이 커브에서 차고까지 달림)";
        if (i > n - 1 - 6) return "길의 맨 끝에 너무 가까워요 (끝에서 6칸 넘게 안쪽)";
        double[] t = Roadway.tangent(s.pts(), i, 4);
        double lateral = (at.getX() - q[0]) * -t[1] + (at.getZ() - q[1]) * t[0];
        int side = lateral >= 0 ? 1 : -1;
        Foot f = footprint(at.getWorld(), s, i, side, baseOff());
        String where = " (차고 자리: 내가 선 쪽 길가, 길 가운데에서 " + Math.round(baseOff()) + "~" + Math.round(baseOff() + 7) + "칸)";
        if (f == null) return "그 자리 땅이 아직 불러와지지 않았어요" + where;
        if (!f.check().ok()) return "여기에는 차고를 세울 수 없어요: " + Dry.why(f.check()) + where;
        return keep(s.fitted(i, side, f.check().max()));
    }

    private String keep(Site s) {
        site = s;
        loaded = true;
        checkedAt = System.currentTimeMillis();
        misses = 0;
        why = "";
        eco.kv().set(KEY, s.encode());
        eco.getLogger().info("[비밀] 폐차고: 관리자가 자리를 옮김 — 커브 " + s.world() + " " + coords(s, s.bend()) + ", 차고 " + coords(s, s.garage()));
        return null;
    }

    /** The garage's frame: the middle of its front on the floor, facing the road. Null before it is fitted. */
    Location garage() {
        Site s = site;
        World w = s == null ? null : s.bukkit();
        if (w == null || !s.fitted()) return null;
        Location f = frame(w, s, s.garage(), Integer.signum(s.side()), off(s));
        f.setY(s.floor());
        return f;
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

    /** A rail's block here (a hard bend's striped block stands on the verge: terracotta lying on terracotta is the badlands' own ground). */
    private static boolean railAt(World w, int bx, int y, int bz) {
        Material t = w.getBlockAt(bx, y, bz).getType();
        if (!rail(t)) return false;
        return !Tag.TERRACOTTA.isTagged(t) || !Tag.TERRACOTTA.isTagged(w.getBlockAt(bx, y - 1, bz).getType());
    }

    /** How far behind its frame (away from the road) the memorial reaches: this much is kept free before a rail. */
    private static final double BACK = 0.8;

    /** The top of what one stands on at y in this column (a slab, a carpet: lower or higher than the whole block). */
    private static double top(World w, int bx, int y, int bz) {
        org.bukkit.util.BoundingBox under = w.getBlockAt(bx, y - 1, bz).getBoundingBox(), in = w.getBlockAt(bx, y, bz).getBoundingBox();
        if (in.getHeight() > 1e-6 && in.getMaxY() <= y + 0.51) return in.getMaxY();   // a carpet, snow
        return under.getHeight() > 1e-6 && under.getMaxY() > y - 1 && under.getMaxY() < y ? under.getMaxY() : y;
    }

    /**
     * The bend's outer roadside {@code along} points past the bend: just inside the guardrail there (the first wall, fence
     * or striped block found going out from the kerb; the memorial's back stays clear of its near face), or {@code side}
     * blocks from the road's middle where there is no rail. Only on dry ground ({@link Dry}: the spot and a block to
     * either hand): where it is not (a bridge over water), nearer the road, down to the kerb; nowhere dry: no spot
     * ({@link Edge#at} null, the rail's top may still be there). Null while its chunk is not loaded.
     */
    Edge edge(double along, double side) {
        Site s = site;
        World w = s == null ? null : s.bukkit();
        if (w == null) return null;
        double at = s.bend() + along;
        double[] t = Roadway.tangent(s.pts(), at, 4), mid = Roadway.at(s.pts(), at);
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
                if (!railAt(w, bx, y, bz)) continue;
                // (a hard road's narrow shoulder: never further in than the kerb's middle, whatever is lost of its back then)
                lateral = Math.max(2.9, Roadway.nearFace(mid[0], mid[1], -t[1] * s.outside(), t[0] * s.outside(), bx, bz) - BACK);
                // its top: over whatever lies on it (a cap, a carpet); a post taller than a rail (a light, a snow pole) has none to reach
                int ty = y;
                while (ty < roadY + 4 && rail(w.getBlockAt(bx, ty + 1, bz).getType())) ty++;
                if (ty <= roadY + 2) {
                    org.bukkit.util.BoundingBox cap = w.getBlockAt(bx, ty + 1, bz).getBoundingBox();
                    top = new Location(w, bx + 0.5, cap.getHeight() > 1e-6 ? Math.max(ty + 1, cap.getMaxY()) : ty + 1, bz + 0.5, yaw, 0);
                }
                break find;
            }
        }
        for (double l = lateral; l >= 2.9 - 1e-9; l -= 0.5) {
            double[] p = Roadway.beside(s.pts(), at, s.outside() * l, 4);
            int y = ground(w, p[0], p[1], roadY, 1, 2);
            if (y == UNKNOWN) return null;
            boolean dry = y != NONE;
            for (int hand = -1; hand <= 1 && dry; hand += 2) {
                int h = ground(w, p[0] + t[0] * hand, p[1] + t[1] * hand, roadY, 1, 2);
                if (h == UNKNOWN) return null;
                dry = h != NONE;
            }
            if (dry) return new Edge(new Location(w, p[0], top(w, (int) Math.floor(p[0]), y, (int) Math.floor(p[1])), p[1], yaw, 0), top);
        }
        return new Edge(null, top);
    }
}
