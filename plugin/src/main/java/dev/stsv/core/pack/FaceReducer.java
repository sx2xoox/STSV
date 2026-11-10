package dev.stsv.core.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fewer faces for the same picture: what the pack builder does to every converted 3D model (furniture, machines,
 * cosmetics, pets) before it ships. A display entity's model is drawn quad by quad every frame, so faces are its cost.
 *
 * <ol>
 *   <li><b>Hidden faces go.</b> A face is hidden when the space just in front of it is, all over, inside closed opaque
 *       boxes (every face there, no see-through texel): buried in another part or pressed against one. A ray that reaches
 *       such a face would first cross the outside of those boxes, which stays. A rotated part's face is hidden only when one
 *       box holds all of it; rotated or open boxes never hide anything.</li>
 *   <li><b>Solids are re-cut.</b> Closed opaque boxes with the same shading and light make one solid; a round thing built of
 *       stepped slices (a drum, a pot, a lantern) is the same solid as a few long boxes crossing each other. The solid is
 *       covered again by as few (overlapping) boxes as a greedy search finds, each new face painted from the old surface at
 *       the same place: where two new faces overlap they show the same texels, so they cannot flicker, and faces inside
 *       the solid go. Kept only when it ends with fewer faces than step 3 alone.</li>
 *   <li><b>Neighbours merge.</b> Two boxes with the same cross-section side by side (same rotation, shading and light), the
 *       faces between them gone, become one box whose side faces are the old ones put together texel for texel.</li>
 * </ol>
 *
 * New faces get their picture in one atlas per model, copied texel for texel at the pack's 16 px a block. Where old faces
 * met inside a texel (parts on half-pixel edges, each painting its own half texel) the new picture has 2 or 4 pixels a
 * texel there, so even those half texels stay; parts of a face nobody can see take the nearest seen colour. Faces with a uv
 * rotation, cullface, an animated or stretched texture are never repainted (they can still be hidden). Faces that point at
 * the floor stay: a piece may be placed over air or glass. Spinning parts are models of their own. What can change: a
 * pixel on an edge where a hidden face used to show through a seam.
 */
public final class FaceReducer {

    /** What a reduction did: faces before and after, and the new faces' atlas (null when no face was repainted). */
    public record Result(int before, int after, BufferedImage atlas) {}

    static final String[] NAMES = {"north", "east", "south", "west", "up", "down"};
    /** Per face: normal axis, normal sign, u axis, u sign, v axis, v sign (the way the game lays a face's uv on it). */
    static final int[][] DIR = {
            {2, -1, 0, -1, 1, -1}, // north
            {0, 1, 2, -1, 1, -1},  // east
            {2, 1, 0, 1, 1, -1},   // south
            {0, -1, 2, 1, 1, -1},  // west
            {1, 1, 0, 1, 2, 1},    // up
            {1, -1, 0, 1, 2, -1},  // down
    };
    static final int[][] ORDERS = {{1, 0, 2}, {1, 2, 0}, {0, 2, 1}, {0, 1, 2}, {2, 0, 1}, {2, 1, 0}};
    private static final double EPS = 1e-4;
    /** Texel edges closer than this (in texels) are the same edge: uvs in the pack have four decimals of 0..16. */
    static final double GRID = 0.02;
    /** Finest picture of a new face, pixels a model unit (4: quarter-pixel edges stay exact). */
    static final int MAX_RES = 4;

    private FaceReducer() {}

    static int face(int axis, int sign) {
        for (int i = 0; i < 6; i++) if (DIR[i][0] == axis && DIR[i][1] == sign) return i;
        throw new IllegalArgumentException();
    }

    // ------------------------------------------------------------------------------------------
    // model
    // ------------------------------------------------------------------------------------------

    /** A face: its JSON as written, and how its texels lie on the model (when they do, at a whole number of pixels a unit). */
    static final class Face {
        final JsonObject json;      // null for a new face (written from img at the end)
        final BufferedImage img;    // its texture (or new picture); null if unknown
        final double pu, pv;        // pixel coordinate at the face's u / v start edge
        final double du, dv;        // pixels per unit along u / v (signed); 0: not on the grid
        final boolean opaque;
        final String tint;
        Integer uniform;            // the one colour of all its texels, if so (then any grid fits it)

        Face(JsonObject json, BufferedImage img, double pu, double pv, double du, double dv, boolean opaque, String tint) {
            this.json = json;
            this.img = img;
            this.pu = pu;
            this.pv = pv;
            this.du = du;
            this.dv = dv;
            this.opaque = opaque;
            this.tint = tint;
        }

        /** Its texels can be copied (on the grid, or all one colour). */
        boolean mappable() {
            return gridded() || uniform != null;
        }

        /** It says where texel edges fall (a one-colour face fits any grid). */
        boolean gridded() {
            return uniform == null && du != 0 && dv != 0 && img != null;
        }

        /** Its colour at a model point of face i of element e (given on the face's u and v axes). */
        int sample(El e, int i, double wu, double wv) {
            if (uniform != null) return uniform;
            int us = DIR[i][3], vs = DIR[i][5];
            int sx = (int) Math.floor(pu + du * us * (wu - start(e, DIR[i][2], us)) + 1e-6);
            int sy = (int) Math.floor(pv + dv * vs * (wv - start(e, DIR[i][4], vs)) + 1e-6);
            sx = Math.max(0, Math.min(img.getWidth() - 1, sx));
            sy = Math.max(0, Math.min(img.getHeight() - 1, sy));
            return img.getRGB(sx, sy);
        }

        /** Texel lattice on the face's u (k 0) or v (k 1) axis: {pixels a unit, where edges fall (model units mod 1 / that)}. */
        double[] lattice(El e, int i, int k) {
            int axis = DIR[i][2 + 2 * k], sign = DIR[i][3 + 2 * k];
            double p0 = k == 0 ? pu : pv, d = k == 0 ? du : dv;
            double res = Math.abs(d);
            return new double[]{res, mod(start(e, axis, sign) - sign * p0 / d, 1 / res)};
        }
    }

    static final class El {
        final double[] from, to;
        final JsonObject props;     // everything but from / to / faces
        final String sig;           // what a merge partner must have the same of
        final boolean rotated;
        final Face[] face = new Face[6];
        final boolean[] hidden = new boolean[6]; // the face was removed because it is covered (any colour may go there)
        final Face[] orig = new Face[6];          // the faces as converted (before hidden ones went)

        El(double[] from, double[] to, JsonObject props) {
            this.from = from;
            this.to = to;
            this.props = props;
            this.sig = props.toString();
            this.rotated = props.has("rotation");
        }

        El copy() {
            El e = new El(from.clone(), to.clone(), props);
            System.arraycopy(face, 0, e.face, 0, 6);
            System.arraycopy(hidden, 0, e.hidden, 0, 6);
            System.arraycopy(orig, 0, e.orig, 0, 6);
            return e;
        }

        int faces() {
            int n = 0;
            for (Face f : face) if (f != null) n++;
            return n;
        }
    }

    /**
     * Reduces the model in place.
     *
     * @param images   texture key (as in the model's "textures", without '#') -> picture; animated ones as their strip
     * @param animated keys of animated textures
     * @param atlasKey texture key the new faces' atlas gets (the caller stores {@link Result#atlas()} under it)
     */
    public static Result reduce(JsonObject model, Map<String, BufferedImage> images, Set<String> animated, String atlasKey) {
        JsonArray elements = model.getAsJsonArray("elements");
        if (elements == null) return new Result(0, 0, null);
        JsonObject refs = model.has("textures") ? model.getAsJsonObject("textures") : new JsonObject();
        List<El> els = read(model, refs, images, animated);
        int before = count(els);

        cull(els);
        remesh(els);
        List<El> best = bestMerge(els);

        // write back
        Atlas atlas = new Atlas();
        for (El e : best) for (Face f : e.face) if (f != null && f.json == null) atlas.add(f);
        atlas.pack();
        JsonArray out = new JsonArray();
        for (El e : best) {
            if (e.faces() == 0) continue;
            JsonObject o = new JsonObject();
            o.add("from", arr(e.from));
            o.add("to", arr(e.to));
            for (Map.Entry<String, JsonElement> p : e.props.entrySet()) o.add(p.getKey(), p.getValue());
            JsonObject faces = new JsonObject();
            for (int i = 0; i < 6; i++) {
                Face f = e.face[i];
                if (f == null) continue;
                if (f.json != null) {
                    faces.add(NAMES[i], f.json);
                    continue;
                }
                double lu = e.to[DIR[i][2]] - e.from[DIR[i][2]], lv = e.to[DIR[i][4]] - e.from[DIR[i][4]];
                int[] at = atlas.where(f);
                JsonObject j = new JsonObject();
                j.add("uv", arr(new double[]{
                        (at[0] + f.pu) * 16 / atlas.w, (at[1] + f.pv) * 16 / atlas.h,
                        (at[0] + f.pu + lu * f.du) * 16 / atlas.w, (at[1] + f.pv + lv * f.dv) * 16 / atlas.h}));
                j.addProperty("texture", "#" + atlasKey);
                if (!f.tint.isEmpty()) j.addProperty("tintindex", Integer.parseInt(f.tint));
                faces.add(NAMES[i], j);
            }
            o.add("faces", faces);
            out.add(o);
        }
        model.add("elements", out);
        return new Result(before, faces(model), atlas.image());
    }

    /** The model's elements with their faces' pictures. */
    static List<El> read(JsonObject model, JsonObject refs, Map<String, BufferedImage> images, Set<String> animated) {
        List<El> els = new ArrayList<>();
        for (JsonElement je : model.getAsJsonArray("elements")) {
            JsonObject o = je.getAsJsonObject();
            JsonObject props = new JsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (!e.getKey().equals("from") && !e.getKey().equals("to") && !e.getKey().equals("faces")) props.add(e.getKey(), e.getValue());
            }
            El el = new El(vec(o.getAsJsonArray("from")), vec(o.getAsJsonArray("to")), props);
            JsonObject faces = o.getAsJsonObject("faces");
            if (faces != null) {
                for (int i = 0; i < 6; i++) {
                    if (faces.has(NAMES[i])) el.face[i] = face(el, i, faces.getAsJsonObject(NAMES[i]), refs, images, animated);
                }
            }
            System.arraycopy(el.face, 0, el.orig, 0, 6);
            els.add(el);
        }
        return els;
    }

    /** A face of an element: its picture and how its texels lie on the model. */
    static Face face(El el, int i, JsonObject json, JsonObject refs, Map<String, BufferedImage> images, Set<String> animated) {
        String tint = json.has("tintindex") ? json.get("tintindex").getAsString() : "";
        String key = json.has("texture") ? json.get("texture").getAsString() : "";
        for (int hops = 0; key.startsWith("#") && hops < 8; hops++) {
            String k = key.substring(1);
            key = refs.has(k) && refs.get(k).getAsString().startsWith("#") ? refs.get(k).getAsString() : k;
        }
        BufferedImage img = images.get(key);
        if (img == null || !json.has("uv")) return new Face(json, null, 0, 0, 0, 0, false, tint);
        boolean anim = animated.contains(key);
        int w = img.getWidth(), h = anim ? img.getWidth() : img.getHeight();
        double[] uv = vec(json.getAsJsonArray("uv"));
        double u1 = uv[0] * w / 16, v1 = uv[1] * h / 16, u2 = uv[2] * w / 16, v2 = uv[3] * h / 16;
        int[] region = region(img, Math.min(u1, u2), Math.min(v1, v2), Math.max(u1, u2), Math.max(v1, v2));
        boolean opaque = !anim && opaque(img, region);
        double lu = el.to[DIR[i][2]] - el.from[DIR[i][2]], lv = el.to[DIR[i][4]] - el.from[DIR[i][4]];
        boolean turned = json.has("rotation") && json.get("rotation").getAsInt() % 360 != 0;
        boolean oneToOne = Math.abs(Math.abs(u2 - u1) - lu) < GRID && Math.abs(Math.abs(v2 - v1) - lv) < GRID && lu > EPS && lv > EPS
                && Math.min(u1, u2) > -GRID && Math.max(u1, u2) < w + GRID && Math.min(v1, v2) > -GRID && Math.max(v1, v2) < h + GRID;
        Face f = anim || turned || !oneToOne || json.has("cullface") ? new Face(json, img, 0, 0, 0, 0, opaque, tint)
                : new Face(json, img, u1, v1, u2 > u1 ? 1 : -1, v2 > v1 ? 1 : -1, opaque, tint);
        if (!anim && !json.has("cullface")) f.uniform = uniform(img, region);
        return f;
    }

    /** The pixels a uv rectangle shows: {x0, y0, x1, y1} (ends exclusive), a hair inside its edges (uvs are rounded). */
    private static int[] region(BufferedImage img, double x0, double y0, double x1, double y1) {
        int a = Math.max(0, Math.min(img.getWidth() - 1, (int) Math.floor(x0 + 0.05)));
        int c = Math.max(0, Math.min(img.getHeight() - 1, (int) Math.floor(y0 + 0.05)));
        int b = Math.min(img.getWidth(), Math.max((int) Math.ceil(x1 - 0.05), a + 1));
        int d = Math.min(img.getHeight(), Math.max((int) Math.ceil(y1 - 0.05), c + 1));
        return new int[]{a, c, b, d};
    }

    private static Integer uniform(BufferedImage img, int[] r) {
        int c = img.getRGB(r[0], r[1]);
        for (int y = r[1]; y < r[3]; y++) for (int x = r[0]; x < r[2]; x++) if (img.getRGB(x, y) != c) return null;
        return c;
    }

    private static boolean opaque(BufferedImage img, int[] r) {
        for (int y = r[1]; y < r[3]; y++) for (int x = r[0]; x < r[2]; x++) if ((img.getRGB(x, y) >>> 24) != 255) return false;
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // 1. hidden faces
    // ------------------------------------------------------------------------------------------

    /** Closed opaque boxes (as converted): every face there and not see-through, not rotated, not flat. */
    static boolean occluder(El e) {
        for (int a = 0; a < 3; a++) if (e.to[a] - e.from[a] < EPS) return false;
        for (Face f : e.orig) if (f == null || !f.opaque) return false;
        return true;
    }

    /** The frame an element is drawn in: "" for none, else its rotation (parts turned the same way share a frame). */
    static String frame(El e) {
        return e.rotated ? e.props.get("rotation").toString() : "";
    }

    /** Occluders by frame. */
    static Map<String, List<El>> occluders(List<El> els) {
        Map<String, List<El>> occ = new HashMap<>();
        for (El e : els) if (occluder(e)) occ.computeIfAbsent(frame(e), k -> new ArrayList<>()).add(e);
        return occ;
    }

    /**
     * Removes every hidden face (see the class doc): covered by occluders turned the same way (in that frame they are plain
     * boxes), or a turned face held by one unturned occluder.
     */
    static void cull(List<El> els) {
        Map<String, List<El>> occ = occluders(els);
        boolean[][] gone = new boolean[els.size()][6];
        for (int k = 0; k < els.size(); k++) {
            El e = els.get(k);
            List<El> same = occ.getOrDefault(frame(e), List.of());
            for (int i = 0; i < 6; i++) {
                if (e.face[i] == null) continue;
                gone[k][i] = hidden(e, i, same) || (e.rotated && hiddenRotated(e, i, occ.getOrDefault("", List.of())));
            }
        }
        for (int k = 0; k < els.size(); k++) {
            for (int i = 0; i < 6; i++) {
                if (gone[k][i]) {
                    els.get(k).face[i] = null;
                    els.get(k).hidden[i] = true;
                }
            }
        }
    }

    /** True if the slab just in front of the face is all inside occluders (other than e). */
    static boolean hidden(El e, int i, List<El> occ) {
        int na = DIR[i][0], ua = DIR[i][2], va = DIR[i][4];
        double p = DIR[i][1] > 0 ? e.to[na] : e.from[na];
        double out = p + DIR[i][1] * 1e-3;
        double u0 = e.from[ua], u1 = e.to[ua], v0 = e.from[va], v1 = e.to[va];
        if (u1 - u0 < EPS || v1 - v0 < EPS) return false;
        List<double[]> rects = new ArrayList<>();
        for (El o : occ) {
            if (o == e || !(o.from[na] < out && out < o.to[na])) continue;
            double a = Math.max(u0, o.from[ua]), b = Math.min(u1, o.to[ua]), c = Math.max(v0, o.from[va]), d = Math.min(v1, o.to[va]);
            if (b - a > EPS && d - c > EPS) rects.add(new double[]{a, c, b, d});
        }
        return covers(u0, v0, u1, v1, rects);
    }

    /** True if the rectangles {u0, v0, u1, v1} cover the whole rectangle (u0, v0)-(u1, v1). */
    static boolean covers(double u0, double v0, double u1, double v1, List<double[]> rects) {
        if (rects.isEmpty()) return false;
        double[] us = cuts(u0, u1, rects, 0), vs = cuts(v0, v1, rects, 1);
        for (int a = 0; a + 1 < us.length; a++) {
            double cu = (us[a] + us[a + 1]) / 2;
            for (int b = 0; b + 1 < vs.length; b++) {
                double cv = (vs[b] + vs[b + 1]) / 2;
                boolean in = false;
                for (double[] r : rects) {
                    if (r[0] <= cu && cu <= r[2] && r[1] <= cv && cv <= r[3]) {
                        in = true;
                        break;
                    }
                }
                if (!in) return false;
            }
        }
        return true;
    }

    private static double[] cuts(double lo, double hi, List<double[]> rects, int k) {
        double[] all = new double[2 + 2 * rects.size()];
        int n = 0;
        all[n++] = lo;
        all[n++] = hi;
        for (double[] r : rects) {
            all[n++] = r[k];
            all[n++] = r[k + 2];
        }
        Arrays.sort(all);
        double[] out = new double[all.length];
        int m = 0;
        for (double v : all) if (m == 0 || v - out[m - 1] > 1e-7) out[m++] = v;
        return Arrays.copyOf(out, m);
    }

    /** A rotated face is hidden when one occluder holds all of it (boxes are convex: its four corners suffice). */
    static boolean hiddenRotated(El e, int i, List<El> occ) {
        int na = DIR[i][0], ua = DIR[i][2], va = DIR[i][4];
        double[][] corners = new double[4][];
        int k = 0;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                double[] p = new double[3];
                p[na] = (DIR[i][1] > 0 ? e.to[na] : e.from[na]) + DIR[i][1] * 2e-3;
                p[ua] = a == 0 ? e.from[ua] + 1e-3 : e.to[ua] - 1e-3;
                p[va] = b == 0 ? e.from[va] + 1e-3 : e.to[va] - 1e-3;
                corners[k++] = rotate(e.props.getAsJsonObject("rotation"), p);
            }
        }
        for (El o : occ) {
            boolean all = true;
            for (double[] c : corners) {
                for (int a = 0; a < 3 && all; a++) all = o.from[a] < c[a] && c[a] < o.to[a];
                if (!all) break;
            }
            if (all) return true;
        }
        return false;
    }

    /** A point of a rotated element in model space, the way the game turns it (then rescales, if asked). */
    static double[] rotate(JsonObject rot, double[] p) {
        double angle = Math.toRadians(rot.get("angle").getAsDouble());
        int axis = "xyz".indexOf(rot.get("axis").getAsString());
        double[] o = rot.has("origin") ? vec(rot.getAsJsonArray("origin")) : new double[]{8, 8, 8};
        double x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
        double c = Math.cos(angle), s = Math.sin(angle);
        double[] r = switch (axis) {
            case 0 -> new double[]{x, y * c - z * s, y * s + z * c};
            case 1 -> new double[]{x * c + z * s, y, -x * s + z * c};
            default -> new double[]{x * c - y * s, x * s + y * c, z};
        };
        if (rot.has("rescale") && rot.get("rescale").getAsBoolean() && Math.abs(c) > 1e-6) {
            for (int a = 0; a < 3; a++) if (a != axis) r[a] /= Math.abs(c);
        }
        return new double[]{r[0] + o[0], r[1] + o[1], r[2] + o[2]};
    }

    // ------------------------------------------------------------------------------------------
    // painting new faces
    // ------------------------------------------------------------------------------------------

    /** What an old surface shows at a model point of a new face: a colour, {@link #ANY} (nobody sees it) or {@link #NONE}. */
    interface Look {
        long at(double wu, double wv);
    }

    static final long ANY = -1, NONE = -2;

    /** A painted face, or why not: null face + fail = two colours in one finest texel (at conflictU x conflictV). */
    record Painted(Face face, boolean fail, double[] conflictU, double[] conflictV) {}

    /**
     * Paints face i of box m from a look. latU / latV: the texel lattices of the old faces it is made of (each must fit in
     * the new picture's grid); cutsU / cutsV: model coordinates where the look may change. The picture gets 1 pixel a unit
     * (more where old texels are split, up to {@link #MAX_RES}). Face null and not failed: nobody can see any of it.
     */
    static Painted paint(El m, int i, List<double[]> latU, List<double[]> latV, double[] cutsU, double[] cutsV, Look look) {
        int ua = DIR[i][2], us = DIR[i][3], va = DIR[i][4], vs = DIR[i][5];
        double mu = start(m, ua, us), mv = start(m, va, vs);
        double lu = m.to[ua] - m.from[ua], lv = m.to[va] - m.from[va];
        int minU = 1, minV = 1;
        double[] cu = null, cv = null;
        while (true) {
            double[] fu = fit(latU, minU), fv = fit(latV, minV);
            if (fu == null || fv == null) return new Painted(null, true, cu, cv);
            int ru = (int) fu[0], rv = (int) fv[0];
            // the face starts inside a texel: its first column / row is cut, pu / pv pixels of it lie before the face
            double ou = mod(us * (fu[1] - mu), 1.0 / ru) * ru, ov = mod(vs * (fv[1] - mv), 1.0 / rv) * rv;
            double pu = ou > GRID && ou < 1 - GRID ? 1 - ou : 0, pv = ov > GRID && ov < 1 - GRID ? 1 - ov : 0;
            int nu = (int) Math.ceil(pu + lu * ru - GRID), nv = (int) Math.ceil(pv + lv * rv - GRID);
            if (nu <= 0 || nv <= 0 || nu > 512 || nv > 512) return new Painted(null, true, null, null);
            int[] px = new int[nu * nv];
            boolean[] set = new boolean[nu * nv];
            boolean conflict = false, any = false, none = false;
            for (int y = 0; y < nv && !conflict && !none; y++) {
                double sv0 = Math.max(0, (y - pv) / rv), sv1 = Math.min(lv, (y + 1 - pv) / rv);
                double[] bv = split(mv + vs * sv0, mv + vs * sv1, cutsV);
                for (int x = 0; x < nu && !conflict && !none; x++) {
                    double su0 = Math.max(0, (x - pu) / ru), su1 = Math.min(lu, (x + 1 - pu) / ru);
                    double[] bu = split(mu + us * su0, mu + us * su1, cutsU);
                    int at = y * nu + x;
                    for (int a = 0; a + 1 < bu.length && !conflict && !none; a++) {
                        if (bu[a + 1] - bu[a] < GRID / ru) continue; // a sliver from rounding
                        for (int b = 0; b + 1 < bv.length; b++) {
                            if (bv[b + 1] - bv[b] < GRID / rv) continue;
                            long c = look.at((bu[a] + bu[a + 1]) / 2, (bv[b] + bv[b + 1]) / 2);
                            if (c == ANY) continue;
                            if (c == NONE) {
                                none = true;
                                break;
                            }
                            if (set[at] && px[at] != (int) c) {
                                conflict = true;
                                cu = new double[]{bu[0], bu[bu.length - 1]};
                                cv = new double[]{bv[0], bv[bv.length - 1]};
                                break;
                            }
                            px[at] = (int) c;
                            set[at] = true;
                            any = true;
                        }
                    }
                }
            }
            if (none) return new Painted(null, true, null, null);
            if (conflict) {
                // finer pixels on the axis the texel was split on (both if it was split on both)
                boolean splitU = split(cu[0], cu[1], cutsU).length > 2, splitV = split(cv[0], cv[1], cutsV).length > 2;
                if (!splitU && !splitV) return new Painted(null, true, cu, cv);
                if (splitU) minU = ru * 2;
                if (splitV) minV = rv * 2;
                if (minU > MAX_RES || minV > MAX_RES) return new Painted(null, true, cu, cv);
                continue;
            }
            if (!any) return new Painted(null, false, null, null);
            fill(px, set, nu, nv);
            BufferedImage img = new BufferedImage(nu, nv, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, nu, nv, px, 0, nu);
            boolean opaque = true;
            for (int c : px) opaque &= (c >>> 24) == 255;
            return new Painted(new Face(null, img, pu, pv, ru, rv, opaque, ""), false, null, null);
        }
    }

    /**
     * The coarsest lattice of at least minRes pixels a unit (1, 2 or 4) that holds every given lattice {res, phase}:
     * {res, phase}, or null if there is none.
     */
    static double[] fit(List<double[]> lattices, int minRes) {
        for (int r = Math.max(1, minRes); r <= MAX_RES; r *= 2) {
            double per = 1.0 / r, ph = Double.NaN;
            boolean ok = true;
            for (double[] l : lattices) {
                double q = r / l[0];
                if (q < 1 - 1e-6 || Math.abs(q - Math.round(q)) > 1e-6) {
                    ok = false;
                    break;
                }
                double p = mod(l[1], per);
                if (Double.isNaN(ph)) ph = p;
                else if (circ(ph, p, per) * r > GRID) {
                    ok = false;
                    break;
                }
            }
            if (ok) return new double[]{r, Double.isNaN(ph) ? 0 : ph};
        }
        return null;
    }

    /** [lo, hi] (either way round) cut at the given coordinates inside it. */
    static double[] split(double a, double b, double[] cuts) {
        double lo = Math.min(a, b), hi = Math.max(a, b);
        double[] out = new double[cuts.length + 2];
        int n = 0;
        out[n++] = lo;
        for (double c : cuts) if (c > lo + 1e-5 && c < hi - 1e-5) out[n++] = c;
        out[n++] = hi;
        out = Arrays.copyOf(out, n);
        Arrays.sort(out);
        return out;
    }

    /** Texels nobody sees get their nearest seen neighbour's colour (so seams and mipmaps stay clean). */
    private static void fill(int[] px, boolean[] set, int w, int h) {
        boolean any = false;
        for (boolean s : set) any |= s;
        if (!any) return;
        boolean grew = true;
        while (grew) {
            grew = false;
            boolean[] now = set.clone();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int at = y * w + x;
                    if (set[at]) continue;
                    int from = x > 0 && set[at - 1] ? at - 1 : x + 1 < w && set[at + 1] ? at + 1
                            : y > 0 && set[at - w] ? at - w : y + 1 < h && set[at + w] ? at + w : -1;
                    if (from < 0) continue;
                    px[at] = px[from];
                    now[at] = true;
                    grew = true;
                }
            }
            System.arraycopy(now, 0, set, 0, set.length);
        }
    }

    // ------------------------------------------------------------------------------------------
    // 2. re-cut solids
    // ------------------------------------------------------------------------------------------

    /** Re-cuts every solid (see the class doc) where that leaves fewer faces. */
    static void remesh(List<El> els) {
        Map<String, List<El>> kinds = new LinkedHashMap<>();
        for (El e : els) {
            String g = solidKey(e);
            if (g != null) kinds.computeIfAbsent(g, k -> new ArrayList<>()).add(e);
        }
        // each solid on its own: the boxes of a kind that touch (share some area) or overlap
        List<List<El>> groups = new ArrayList<>();
        for (List<El> kind : kinds.values()) groups.addAll(touching(kind));
        for (List<El> group : groups) {
            if (group.size() < 3) continue;
            List<El> cut = cover(group);
            if (cut == null) continue;
            List<El> occ = new ArrayList<>();
            String frame = frame(group.get(0));
            for (El o : els) if (!group.contains(o) && occluder(o) && frame(o).equals(frame)) occ.add(o);
            occ.addAll(cut);
            // faces of the new boxes covered by other parts go too
            for (El c : cut) {
                for (int i = 0; i < 6; i++) {
                    if (c.face[i] != null && hidden(c, i, occ)) {
                        c.face[i] = null;
                        c.hidden[i] = true;
                    }
                }
            }
            if (count(bestMerge(cut)) >= count(bestMerge(group))) continue;
            int at = els.indexOf(group.get(0));
            els.removeAll(group);
            els.addAll(Math.min(at, els.size()), cut);
        }
    }

    /** The boxes split into groups that touch each other (share a face area) or overlap, in their order. */
    static List<List<El>> touching(List<El> boxes) {
        int n = boxes.size();
        int[] root = new int[n];
        for (int k = 0; k < n; k++) root[k] = k;
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                El p = boxes.get(a), q = boxes.get(b);
                int touch = 0, overlap = 0;
                for (int x = 0; x < 3; x++) {
                    double d = Math.min(p.to[x], q.to[x]) - Math.max(p.from[x], q.from[x]);
                    if (d > EPS) overlap++;
                    else if (d > -EPS) touch++;
                }
                if (overlap == 3 || (overlap == 2 && touch == 1)) {
                    int ra = find(root, a), rb = find(root, b);
                    if (ra != rb) root[Math.max(ra, rb)] = Math.min(ra, rb);
                }
            }
        }
        Map<Integer, List<El>> out = new LinkedHashMap<>();
        for (int k = 0; k < n; k++) out.computeIfAbsent(find(root, k), r -> new ArrayList<>()).add(boxes.get(k));
        return new ArrayList<>(out.values());
    }

    private static int find(int[] root, int k) {
        while (root[k] != k) k = root[k] = root[root[k]];
        return k;
    }

    /**
     * Which solid an element belongs to: its shading and light, and where its seen faces' texel edges fall (mod a quarter
     * unit: the finest new pictures hold those); null if it is no closed opaque box or a seen face is not on the grid.
     */
    static String solidKey(El e) {
        if (!occluder(e)) return null;
        double[] ph = {Double.NaN, Double.NaN, Double.NaN};
        for (int i = 0; i < 6; i++) {
            Face f = e.face[i];
            if (f == null) continue;
            if (!f.mappable()) return null;
            if (!f.gridded()) continue;
            for (int k = 0; k < 2; k++) {
                int a = DIR[i][2 + 2 * k];
                double p = mod(f.lattice(e, i, k)[1], 1.0 / MAX_RES);
                if (Double.isNaN(ph[a])) ph[a] = p;
                else if (circ(ph[a], p, 1.0 / MAX_RES) * MAX_RES > GRID) return null;
            }
        }
        StringBuilder key = new StringBuilder(e.sig);
        for (double p : ph) key.append('|').append(Double.isNaN(p) ? 0 : Math.round(p * 1024) % (1024 / MAX_RES));
        return key.toString();
    }

    /** The group's solid as few boxes as the greedy cover finds, painted; null if its look cannot be kept. */
    static List<El> cover(List<El> group) {
        double[][] cs = new double[3][];
        for (int a = 0; a < 3; a++) {
            TreeSet<Long> v = new TreeSet<>();
            for (El e : group) {
                v.add(Math.round(e.from[a] * 1e4));
                v.add(Math.round(e.to[a] * 1e4));
            }
            cs[a] = v.stream().mapToDouble(x -> x / 1e4).toArray();
        }
        int nx = cs[0].length - 1, ny = cs[1].length - 1, nz = cs[2].length - 1;
        if ((long) nx * ny * nz > 400_000) return null;
        boolean[] occ = new boolean[nx * ny * nz];
        for (El e : group) {
            int[] lo = new int[3], hi = new int[3];
            for (int a = 0; a < 3; a++) {
                lo[a] = cut(cs[a], e.from[a]);
                hi[a] = cut(cs[a], e.to[a]);
            }
            for (int x = lo[0]; x < hi[0]; x++) for (int y = lo[1]; y < hi[1]; y++) for (int z = lo[2]; z < hi[2]; z++) occ[(x * ny + y) * nz + z] = true;
        }
        Solid s = new Solid(cs, new int[]{nx, ny, nz}, occ, group);
        // a texel the old faces split finer than the finest picture: no new box may reach across there; again
        for (int attempt = 0; attempt < 64; attempt++) {
            List<int[]> best = null;
            int bestFaces = Integer.MAX_VALUE;
            for (int[] scan : ORDERS) {
                List<int[]> boxes = s.greedy(scan);
                int n = s.faces(boxes);
                if (n < bestFaces) {
                    bestFaces = n;
                    best = boxes;
                }
            }
            List<El> out = new ArrayList<>();
            boolean failed = false;
            for (int[] b : best) {
                El e = new El(new double[]{cs[0][b[0]], cs[1][b[1]], cs[2][b[2]]}, new double[]{cs[0][b[3]], cs[1][b[4]], cs[2][b[5]]}, group.get(0).props);
                for (int i = 0; i < 6 && !failed; i++) {
                    Painted p = s.paint(b, i, e);
                    if (p.fail()) {
                        failed = true;
                        if (p.conflictU() == null || !s.harden(DIR[i][2], p.conflictU()) & !s.harden(DIR[i][4], p.conflictV())) return null;
                        break;
                    }
                    e.face[i] = p.face();
                    e.hidden[i] = p.face() == null;
                    e.orig[i] = p.face();
                }
                if (failed) break;
                out.add(e);
            }
            if (!failed) return out;
        }
        return null;
    }

    /** The index of the cut at v. */
    private static int cut(double[] c, double v) {
        int k = Arrays.binarySearch(c, Math.round(v * 1e4) / 1e4);
        if (k >= 0) return k;
        k = -k - 1;
        if (k > 0 && (k >= c.length || Math.abs(c[k - 1] - v) < Math.abs(c[k] - v))) return k - 1;
        return Math.min(k, c.length - 1);
    }

    /** The cell (between cuts k and k + 1) holding v. */
    private static int cell(double[] c, double v) {
        int k = Arrays.binarySearch(c, v);
        if (k < 0) k = -k - 2;
        return Math.max(0, Math.min(c.length - 2, k));
    }

    /** The cells of a solid on its own (compressed) grid. */
    static final class Solid {
        final double[][] cs;
        final int[] n;
        final boolean[] occ;
        final List<El> parts;
        /** hard[a][k]: no box may reach across cut k on axis a */
        final boolean[][] hard;

        Solid(double[][] cs, int[] n, boolean[] occ, List<El> parts) {
            this.cs = cs;
            this.n = n;
            this.occ = occ;
            this.parts = parts;
            this.hard = new boolean[][]{new boolean[cs[0].length], new boolean[cs[1].length], new boolean[cs[2].length]};
        }

        /** Makes the cuts on axis a strictly inside the range hard; false if there were none to make. */
        boolean harden(int a, double[] range) {
            boolean grew = false;
            if (range == null) return false;
            for (int k = 0; k < cs[a].length; k++) {
                if (cs[a][k] > Math.min(range[0], range[1]) + 1e-5 && cs[a][k] < Math.max(range[0], range[1]) - 1e-5 && !hard[a][k]) {
                    hard[a][k] = true;
                    grew = true;
                }
            }
            return grew;
        }

        boolean in(int x, int y, int z) {
            return x >= 0 && y >= 0 && z >= 0 && x < n[0] && y < n[1] && z < n[2] && occ[(x * n[1] + y) * n[2] + z];
        }

        boolean full(int[] lo, int[] hi) {
            for (int x = lo[0]; x < hi[0]; x++) for (int y = lo[1]; y < hi[1]; y++) for (int z = lo[2]; z < hi[2]; z++) if (!in(x, y, z)) return false;
            return true;
        }

        /** Seeds in scan order; from each seed not yet covered, the box (grown in each axis order) covering most new cells. */
        List<int[]> greedy(int[] scan) {
            boolean[] cov = new boolean[occ.length];
            List<int[]> out = new ArrayList<>();
            int[] p = new int[3];
            for (p[scan[0]] = 0; p[scan[0]] < n[scan[0]]; p[scan[0]]++) {
                for (p[scan[1]] = 0; p[scan[1]] < n[scan[1]]; p[scan[1]]++) {
                    for (p[scan[2]] = 0; p[scan[2]] < n[scan[2]]; p[scan[2]]++) {
                        int at = (p[0] * n[1] + p[1]) * n[2] + p[2];
                        if (!occ[at] || cov[at]) continue;
                        int[] bestBox = null;
                        long bestNew = -1, bestVol = -1;
                        for (int[] order : ORDERS) {
                            int[] lo = p.clone(), hi = {p[0] + 1, p[1] + 1, p[2] + 1};
                            for (int a : order) {
                                while (hi[a] < n[a] && !hard[a][hi[a]]) {
                                    int[] l2 = lo.clone(), h2 = hi.clone();
                                    l2[a] = hi[a];
                                    h2[a] = hi[a] + 1;
                                    if (!full(l2, h2)) break;
                                    hi[a]++;
                                }
                                while (lo[a] > 0 && !hard[a][lo[a]]) {
                                    int[] l2 = lo.clone(), h2 = hi.clone();
                                    h2[a] = lo[a];
                                    l2[a] = lo[a] - 1;
                                    if (!full(l2, h2)) break;
                                    lo[a]--;
                                }
                            }
                            long nw = 0, vol = 0;
                            for (int x = lo[0]; x < hi[0]; x++) for (int y = lo[1]; y < hi[1]; y++) for (int z = lo[2]; z < hi[2]; z++) {
                                vol++;
                                if (!cov[(x * n[1] + y) * n[2] + z]) nw++;
                            }
                            if (nw > bestNew || (nw == bestNew && vol > bestVol)) {
                                bestNew = nw;
                                bestVol = vol;
                                bestBox = new int[]{lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]};
                            }
                        }
                        for (int x = bestBox[0]; x < bestBox[3]; x++) for (int y = bestBox[1]; y < bestBox[4]; y++) for (int z = bestBox[2]; z < bestBox[5]; z++) cov[(x * n[1] + y) * n[2] + z] = true;
                        out.add(bestBox);
                    }
                }
            }
            // boxes that later, bigger boxes cover entirely are not needed (smallest first)
            int[] times = new int[occ.length];
            for (int[] b : out) for (int x = b[0]; x < b[3]; x++) for (int y = b[1]; y < b[4]; y++) for (int z = b[2]; z < b[5]; z++) times[(x * n[1] + y) * n[2] + z]++;
            List<int[]> bySize = new ArrayList<>(out);
            bySize.sort((a, b) -> Long.compare(volume(a), volume(b)));
            for (int[] b : bySize) {
                boolean spare = true;
                for (int x = b[0]; x < b[3] && spare; x++) for (int y = b[1]; y < b[4] && spare; y++) for (int z = b[2]; z < b[5] && spare; z++) spare = times[(x * n[1] + y) * n[2] + z] > 1;
                if (!spare) continue;
                for (int x = b[0]; x < b[3]; x++) for (int y = b[1]; y < b[4]; y++) for (int z = b[2]; z < b[5]; z++) times[(x * n[1] + y) * n[2] + z]--;
                out.remove(b);
            }
            return out;
        }

        private static long volume(int[] b) {
            return (long) (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
        }

        /** Faces of a cover: a box's face counts unless every cell in front of it is in the solid. */
        int faces(List<int[]> boxes) {
            int total = 0;
            for (int[] b : boxes) for (int i = 0; i < 6; i++) if (!inside(b, i)) total++;
            return total;
        }

        /** True if every cell right in front of face i of box b is in the solid. */
        boolean inside(int[] b, int i) {
            int na = DIR[i][0];
            int[] lo = {b[0], b[1], b[2]}, hi = {b[3], b[4], b[5]};
            if (DIR[i][1] > 0) {
                lo[na] = hi[na];
                hi[na] = hi[na] + 1;
            } else {
                hi[na] = lo[na];
                lo[na] = lo[na] - 1;
            }
            if (lo[na] < 0 || hi[na] > n[na]) return false;
            return full(lo, hi);
        }

        /** Face i of a cover box painted from the solid's old surface (in front of solid cells: nobody's). */
        Painted paint(int[] b, int i, El box) {
            if (inside(b, i)) return new Painted(null, false, null, null);
            int na = DIR[i][0], ns = DIR[i][1], ua = DIR[i][2], va = DIR[i][4];
            double plane = ns > 0 ? box.to[na] : box.from[na];
            int front = ns > 0 ? b[3 + na] : b[na] - 1;
            List<double[]> latU = new ArrayList<>(), latV = new ArrayList<>();
            for (El e : parts) {
                Face f = e.face[i];
                if (f == null || !f.gridded() || Math.abs((ns > 0 ? e.to[na] : e.from[na]) - plane) > 1e-4) continue;
                latU.add(f.lattice(e, i, 0));
                latV.add(f.lattice(e, i, 1));
            }
            Look look = (wu, wv) -> {
                int[] c3 = new int[3];
                c3[na] = front;
                c3[ua] = cell(cs[ua], wu);
                c3[va] = cell(cs[va], wv);
                if (in(c3[0], c3[1], c3[2])) return ANY; // in front of the solid itself: nobody sees it
                boolean covered = false;
                for (El e : parts) {
                    double pl = ns > 0 ? e.to[na] : e.from[na];
                    if (Math.abs(pl - plane) > 1e-4 || wu < e.from[ua] || wu > e.to[ua] || wv < e.from[va] || wv > e.to[va]) continue;
                    if (e.face[i] != null) return e.face[i].sample(e, i, wu, wv) & 0xffffffffL;
                    covered |= e.hidden[i];
                }
                return covered ? ANY : NONE;
            };
            return FaceReducer.paint(box, i, latU, latV, cs[ua], cs[va], look);
        }
    }

    // ------------------------------------------------------------------------------------------
    // 3. merging neighbours
    // ------------------------------------------------------------------------------------------

    /** The elements merged in the axis order that leaves the fewest faces (copies; els stays as it is). */
    static List<El> bestMerge(List<El> els) {
        List<El> best = null;
        int bestFaces = Integer.MAX_VALUE;
        for (int[] order : ORDERS) {
            List<El> trial = new ArrayList<>();
            for (El e : els) trial.add(e.copy());
            merge(trial, order);
            int n = count(trial);
            if (n < bestFaces) {
                bestFaces = n;
                best = trial;
            }
        }
        return best;
    }

    static int count(List<El> els) {
        int n = 0;
        for (El e : els) n += e.faces();
        return n;
    }

    /** Greedy: merges neighbours along the axes in this order, again and again until nothing merges. */
    static void merge(List<El> els, int[] order) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int k : order) {
                Map<String, Integer> byLow = new HashMap<>();
                for (int n = 0; n < els.size(); n++) byLow.put(key(els.get(n), k, false), n);
                boolean[] dead = new boolean[els.size()];
                for (int n = 0; n < els.size(); n++) {
                    if (dead[n]) continue;
                    while (true) {
                        El a = els.get(n);
                        Integer bi = byLow.get(key(a, k, true));
                        if (bi == null || bi == n || dead[bi]) break;
                        El m = merged(a, els.get(bi), k);
                        if (m == null) break;
                        els.set(n, m);
                        dead[bi] = true;
                        changed = true;
                    }
                }
                List<El> keep = new ArrayList<>();
                for (int n = 0; n < els.size(); n++) if (!dead[n]) keep.add(els.get(n));
                els.clear();
                els.addAll(keep);
            }
        }
    }

    /** Where a merge partner along axis k must start (high: this box's far side) and what it must share. */
    private static String key(El e, int k, boolean high) {
        StringBuilder s = new StringBuilder().append(k).append('|').append(Math.round((high ? e.to[k] : e.from[k]) * 1e4));
        for (int a = 0; a < 3; a++) if (a != k) s.append('|').append(Math.round(e.from[a] * 1e4)).append('|').append(Math.round(e.to[a] * 1e4));
        return s.append('|').append(e.sig).toString();
    }

    /** a and b (b right after a along axis k) as one box, or null if that would change the look. */
    static El merged(El a, El b, int k) {
        int lowFace = face(k, -1), highFace = face(k, 1);
        if (a.face[highFace] != null || b.face[lowFace] != null) return null; // the faces between them must be gone
        double[] from = a.from.clone(), to = a.to.clone();
        to[k] = b.to[k];
        El m = new El(from, to, a.props);
        m.face[lowFace] = a.face[lowFace];
        m.hidden[lowFace] = a.hidden[lowFace];
        m.face[highFace] = b.face[highFace];
        m.hidden[highFace] = b.hidden[highFace];
        for (int i = 0; i < 6; i++) {
            if (DIR[i][0] == k) continue;
            Face fa = a.face[i], fb = b.face[i];
            if (fa == null && fb == null) {
                m.hidden[i] = a.hidden[i] && b.hidden[i];
                continue;
            }
            if ((fa == null && !a.hidden[i]) || (fb == null && !b.hidden[i])) return null; // an open side stays open
            Face f = joined(m, i, a, fa, b, fb);
            if (f == null) return null;
            m.face[i] = f;
        }
        return m;
    }

    /** Face i of the merged box m painted from a's and b's faces there (null: hidden, any colour); null if it cannot be. */
    static Face joined(El m, int i, El a, Face fa, El b, Face fb) {
        String tint = null;
        List<double[]> latU = new ArrayList<>(), latV = new ArrayList<>();
        for (Object[] p : new Object[][]{{a, fa}, {b, fb}}) {
            Face f = (Face) p[1];
            if (f == null) continue;
            if (!f.mappable() || (tint != null && !tint.equals(f.tint))) return null;
            tint = f.tint;
            if (f.gridded()) {
                latU.add(f.lattice((El) p[0], i, 0));
                latV.add(f.lattice((El) p[0], i, 1));
            }
        }
        int ua = DIR[i][2], va = DIR[i][4];
        double[] cutsU = {a.from[ua], a.to[ua], b.from[ua], b.to[ua]}, cutsV = {a.from[va], a.to[va], b.from[va], b.to[va]};
        Look look = (wu, wv) -> {
            for (Object[] p : new Object[][]{{a, fa}, {b, fb}}) {
                El e = (El) p[0];
                if (wu < e.from[ua] || wu > e.to[ua] || wv < e.from[va] || wv > e.to[va]) continue;
                Face f = (Face) p[1];
                return f == null ? ANY : f.sample(e, i, wu, wv) & 0xffffffffL;
            }
            return NONE;
        };
        Painted p = paint(m, i, latU, latV, cutsU, cutsV, look);
        if (p.face() == null) return null;
        Face f = p.face();
        return tint.isEmpty() ? f : new Face(null, f.img, f.pu, f.pv, f.du, f.dv, f.opaque, tint);
    }

    /** The world coordinate of a face's start edge along an axis (s = sign * (w - start)). */
    static double start(El e, int axis, int sign) {
        return sign > 0 ? e.from[axis] : e.to[axis];
    }

    static double mod(double v, double per) {
        double f = v - Math.floor(v / per) * per;
        return f > per - 1e-7 ? 0 : f;
    }

    private static double circ(double a, double b, double per) {
        double d = Math.abs(a - b) % per;
        return Math.min(d, per - d);
    }

    // ------------------------------------------------------------------------------------------
    // atlas of the new faces
    // ------------------------------------------------------------------------------------------

    /** The new faces' pictures, each once, 1 px gutter all round, shelf-packed, sides in multiples of 16. */
    static final class Atlas {
        private final Map<String, BufferedImage> pics = new LinkedHashMap<>();
        private final Map<Face, String> keyOf = new HashMap<>();
        private final Map<String, int[]> pos = new HashMap<>();
        int w, h;

        void add(Face f) {
            int[] px = pixels(f.img);
            String key = f.img.getWidth() + "x" + f.img.getHeight() + ":" + Arrays.hashCode(px);
            while (pics.containsKey(key) && !Arrays.equals(px, pixels(pics.get(key)))) key += "'";
            pics.putIfAbsent(key, f.img);
            keyOf.put(f, key);
        }

        void pack() {
            if (pics.isEmpty()) return;
            List<String> keys = new ArrayList<>(pics.keySet());
            int widest = 0;
            long area = 0;
            for (BufferedImage p : pics.values()) {
                widest = Math.max(widest, p.getWidth() + 2);
                area += (long) (p.getWidth() + 2) * (p.getHeight() + 2);
            }
            long bestArea = Long.MAX_VALUE;
            for (int W = 16; W <= 2048; W += 16) {
                if (W < widest) continue;
                Map<String, int[]> at = shelf(keys, W);
                int H = 0;
                for (String k : keys) H = Math.max(H, at.get(k)[1] + pics.get(k).getHeight() + 2);
                H = Math.max(16, (H + 15) / 16 * 16);
                if (H > W && W < 2048) continue;
                long a = (long) W * H;
                if (a < bestArea) {
                    bestArea = a;
                    w = W;
                    h = H;
                    pos.clear();
                    pos.putAll(at);
                }
                if ((long) W * W > 4 * area && bestArea < Long.MAX_VALUE) break;
            }
        }

        private Map<String, int[]> shelf(List<String> keys, int W) {
            List<String> order = new ArrayList<>(keys);
            Map<String, Integer> index = new HashMap<>();
            for (int k = 0; k < keys.size(); k++) index.put(keys.get(k), k);
            order.sort((x, y) -> {
                BufferedImage a = pics.get(x), b = pics.get(y);
                if (a.getHeight() != b.getHeight()) return b.getHeight() - a.getHeight();
                if (a.getWidth() != b.getWidth()) return b.getWidth() - a.getWidth();
                return Integer.compare(index.get(x), index.get(y));
            });
            Map<String, int[]> at = new HashMap<>();
            int x = 0, y = 0, shelf = 0;
            for (String k : order) {
                BufferedImage p = pics.get(k);
                int pw = p.getWidth() + 2, ph = p.getHeight() + 2;
                if (x + pw > W) {
                    y += shelf;
                    x = shelf = 0;
                }
                at.put(k, new int[]{x, y});
                x += pw;
                shelf = Math.max(shelf, ph);
            }
            return at;
        }

        /** Top-left of a face's picture (inside its gutter). */
        int[] where(Face f) {
            int[] p = pos.get(keyOf.get(f));
            return new int[]{p[0] + 1, p[1] + 1};
        }

        BufferedImage image() {
            if (pics.isEmpty()) return null;
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            boolean opaque = true;
            for (BufferedImage p : pics.values()) for (int c : pixels(p)) opaque &= (c >>> 24) == 255;
            if (opaque) {
                // spare space in the colour of the first texel: nothing see-through in an opaque model's texture
                int fill = pics.values().iterator().next().getRGB(0, 0);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) out.setRGB(x, y, fill);
            }
            for (Map.Entry<String, BufferedImage> e : pics.entrySet()) {
                int[] p = pos.get(e.getKey());
                BufferedImage im = e.getValue();
                int pw = im.getWidth(), ph = im.getHeight();
                for (int y = -1; y <= ph; y++) {
                    for (int x = -1; x <= pw; x++) {
                        int sx = Math.max(0, Math.min(pw - 1, x)), sy = Math.max(0, Math.min(ph - 1, y));
                        out.setRGB(p[0] + 1 + x, p[1] + 1 + y, im.getRGB(sx, sy));
                    }
                }
            }
            return out;
        }

        private static int[] pixels(BufferedImage p) {
            return p.getRGB(0, 0, p.getWidth(), p.getHeight(), null, 0, p.getWidth());
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Faces of a model: every face of every element (what the client draws, one quad each). */
    public static int faces(JsonObject model) {
        if (model == null || !model.has("elements") || !model.get("elements").isJsonArray()) return 0;
        int n = 0;
        for (JsonElement el : model.getAsJsonArray("elements")) {
            if (!el.isJsonObject()) continue;
            JsonObject faces = el.getAsJsonObject().getAsJsonObject("faces");
            if (faces != null) n += faces.size();
        }
        return n;
    }

    private static double[] vec(JsonArray a) {
        double[] v = new double[a.size()];
        for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsDouble();
        return v;
    }

    private static JsonArray arr(double[] v) {
        JsonArray a = new JsonArray();
        for (double f : v) {
            double r = Math.round(f * 1e5) / 1e5;
            if (r == Math.rint(r)) a.add((long) r);
            else a.add(r);
        }
        return a;
    }
}
