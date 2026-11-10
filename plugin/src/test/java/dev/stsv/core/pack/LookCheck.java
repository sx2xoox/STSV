package dev.stsv.core.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test helper: does a reduced model look like the original? Every face of the original is sampled on a fine grid of model
 * points; at each, the reduced model must show the same texel on a face in the same plane facing the same way (every such
 * face, so overlapping new faces cannot flicker), or nothing there must be visible in the original either (the point is
 * covered by a closed opaque box).
 */
final class LookCheck {

    /** A model with its pictures by texture key (animated ones: their strip). */
    record Model(JsonObject json, Map<String, BufferedImage> images, Set<String> animated) {}

    private LookCheck() {}

    /** The problems found (empty: same look), at most `limit`. step: model units between sample points. */
    static List<String> compare(String name, Model before, Model after, double step, int limit) {
        List<String> problems = new ArrayList<>();
        List<FaceReducer.El> occ = FaceReducer.read(before.json(), refs(before.json()), before.images(), before.animated());
        occ.removeIf(e -> !FaceReducer.occluder(e));
        JsonArray be = before.json().getAsJsonArray("elements"), ae = after.json().getAsJsonArray("elements");
        Map<String, List<JsonObject>> beforeAt = planes(be), afterAt = planes(ae);
        int points = 0;
        for (JsonElement je : be) {
            JsonObject e = je.getAsJsonObject();
            double[] from = vec(e.getAsJsonArray("from")), to = vec(e.getAsJsonArray("to"));
            String frame = e.has("rotation") ? e.get("rotation").toString() : "";
            for (Map.Entry<String, JsonElement> fe : e.getAsJsonObject("faces").entrySet()) {
                int i = List.of(FaceReducer.NAMES).indexOf(fe.getKey());
                int na = FaceReducer.DIR[i][0], ns = FaceReducer.DIR[i][1], ua = FaceReducer.DIR[i][2], va = FaceReducer.DIR[i][4];
                double plane = ns > 0 ? to[na] : from[na];
                List<JsonObject> sameBefore = beforeAt.getOrDefault(plane(frame, i, plane), List.of());
                List<JsonObject> sameAfter = afterAt.getOrDefault(plane(frame, i, plane), List.of());
                for (double u = from[ua] + 0.43 * step; u < to[ua] - 1e-3; u += step) {
                    for (double v = from[va] + 0.43 * step; v < to[va] - 1e-3; v += step) {
                        Integer c0 = colour(before, e, i, fe.getValue().getAsJsonObject(), u, v);
                        if (c0 == null) continue;
                        // other original faces here with another colour: the original flickers there, nothing to keep
                        if (ambiguous(before, sameBefore, i, u, v, c0)) continue;
                        points++;
                        double[] p = new double[3];
                        p[na] = plane;
                        p[ua] = u;
                        p[va] = v;
                        List<Integer> shown = new ArrayList<>();
                        for (JsonObject a : sameAfter) {
                            JsonObject af = a.getAsJsonObject("faces").getAsJsonObject(fe.getKey());
                            double[] f2 = vec(a.getAsJsonArray("from")), t2 = vec(a.getAsJsonArray("to"));
                            if (u < f2[ua] - 1e-6 || u > t2[ua] + 1e-6 || v < f2[va] - 1e-6 || v > t2[va] + 1e-6) continue;
                            Integer c = colour(after, a, i, af, u, v);
                            if (c != null) shown.add(c);
                        }
                        boolean same = !shown.isEmpty();
                        for (int c : shown) same &= c == c0 || (alpha(c) < 26 && alpha(c0) < 26);
                        if (same || hidden(occ, frame, e, i, p)) continue;
                        problems.add(name + " " + fe.getKey() + " at " + fmt(p) + (shown.isEmpty() ? ": face gone" : ": "
                                + Integer.toHexString(c0) + " became " + shown.stream().map(Integer::toHexString).toList()));
                        if (problems.size() >= limit) return problems;
                    }
                }
            }
        }
        if (points == 0 && be.size() > 0) problems.add(name + ": no point checked");
        return problems;
    }

    private static boolean ambiguous(Model m, List<JsonObject> same, int i, double u, double v, int c0) {
        int ua = FaceReducer.DIR[i][2], va = FaceReducer.DIR[i][4];
        for (JsonObject e : same) {
            double[] from = vec(e.getAsJsonArray("from")), to = vec(e.getAsJsonArray("to"));
            if (u < from[ua] || u > to[ua] || v < from[va] || v > to[va]) continue;
            Integer c = colour(m, e, i, e.getAsJsonObject("faces").getAsJsonObject(FaceReducer.NAMES[i]), u, v);
            if (c != null && c != c0) return true;
        }
        return false;
    }

    /** Elements by the planes their faces lie in (frame, face, plane). */
    private static Map<String, List<JsonObject>> planes(JsonArray els) {
        Map<String, List<JsonObject>> out = new HashMap<>();
        for (JsonElement je : els) {
            JsonObject e = je.getAsJsonObject();
            String frame = e.has("rotation") ? e.get("rotation").toString() : "";
            double[] from = vec(e.getAsJsonArray("from")), to = vec(e.getAsJsonArray("to"));
            for (String f : e.getAsJsonObject("faces").keySet()) {
                int i = List.of(FaceReducer.NAMES).indexOf(f);
                double pl = FaceReducer.DIR[i][1] > 0 ? to[FaceReducer.DIR[i][0]] : from[FaceReducer.DIR[i][0]];
                out.computeIfAbsent(plane(frame, i, pl), k -> new ArrayList<>()).add(e);
            }
        }
        return out;
    }

    private static String plane(String frame, int i, double p) {
        return frame + "|" + i + "|" + Math.round(p * 1000);
    }

    /** The point just in front of face i at p is inside a closed opaque box of the original (other than e itself). */
    private static boolean hidden(List<FaceReducer.El> occ, String frame, JsonObject e, int i, double[] p) {
        double[] q = p.clone();
        q[FaceReducer.DIR[i][0]] += FaceReducer.DIR[i][1] * 0.01;
        double[] world = frame.isEmpty() ? q : FaceReducer.rotate(e.getAsJsonObject("rotation"), q);
        for (FaceReducer.El o : occ) {
            String of = FaceReducer.frame(o);
            double[] at = of.equals(frame) ? q : of.isEmpty() ? world : null;
            if (at == null) continue;
            boolean in = true;
            for (int a = 0; a < 3 && in; a++) in = o.from[a] < at[a] && at[a] < o.to[a];
            if (in) return true;
        }
        return false;
    }

    /** The texel a face shows at a model point (u, v on its u / v axes); null if it cannot be told (uv rotation). */
    static Integer colour(Model m, JsonObject el, int i, JsonObject face, double u, double v) {
        if (face.has("rotation") && face.get("rotation").getAsInt() % 360 != 0) return null;
        String key = face.get("texture").getAsString();
        JsonObject refs = refs(m.json());
        for (int hops = 0; key.startsWith("#") && hops < 8; hops++) {
            String k = key.substring(1);
            key = refs.has(k) && refs.get(k).getAsString().startsWith("#") ? refs.get(k).getAsString() : k;
        }
        BufferedImage img = m.images().get(key);
        if (img == null) return null;
        int w = img.getWidth(), h = m.animated().contains(key) ? img.getWidth() : img.getHeight();
        double[] from = vec(el.getAsJsonArray("from")), to = vec(el.getAsJsonArray("to"));
        int ua = FaceReducer.DIR[i][2], us = FaceReducer.DIR[i][3], va = FaceReducer.DIR[i][4], vs = FaceReducer.DIR[i][5];
        double lu = to[ua] - from[ua], lv = to[va] - from[va];
        double s = us > 0 ? u - from[ua] : to[ua] - u, t = vs > 0 ? v - from[va] : to[va] - v;
        double[] uv = vec(face.getAsJsonArray("uv"));
        double x = (uv[0] + (uv[2] - uv[0]) * s / lu) * w / 16, y = (uv[1] + (uv[3] - uv[1]) * t / lv) * h / 16;
        int px = Math.max(0, Math.min(w - 1, (int) Math.floor(x))), py = Math.max(0, Math.min(h - 1, (int) Math.floor(y)));
        return img.getRGB(px, py);
    }

    private static JsonObject refs(JsonObject model) {
        return model.has("textures") ? model.getAsJsonObject("textures") : new JsonObject();
    }

    private static int alpha(int c) {
        return c >>> 24;
    }

    private static double[] vec(JsonArray a) {
        double[] r = new double[a.size()];
        for (int k = 0; k < r.length; k++) r[k] = a.get(k).getAsDouble();
        return r;
    }

    private static String fmt(double[] p) {
        return String.format("(%.3f, %.3f, %.3f)", p[0], p[1], p[2]);
    }

    static Map<String, BufferedImage> map(Object... keyImage) {
        Map<String, BufferedImage> m = new HashMap<>();
        for (int k = 0; k + 1 < keyImage.length; k += 2) m.put((String) keyImage[k], (BufferedImage) keyImage[k + 1]);
        return m;
    }
}
