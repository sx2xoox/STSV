package dev.stsv.core.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A crop stage's item model (what its item display showed) as a block model that looks the same (CropBlocks).
 *
 * <ul>
 *   <li>An item display in the "fixed" context turns its item 180 degrees about Y before drawing it, so the block model has
 *   that turn baked in (x -> 16 - x, z -> 16 - z, faces and their UVs turned with it), plus 0/90/180/270 more for the
 *   four random variants (a blockstate "y" would turn the faces after their shading was chosen, see below).</li>
 *   <li>Light: an item is lit by the entity light (two fixed directions: north/south faces 0.74, east/west 0.50,
 *   down 0.40, up 1.0), a block face by its direction (0.8 / 0.6 / 0.5 / 1.0). Each face therefore uses a copy of its
 *   texture darkened by entity / block for the direction the game bakes the face with (×0.924 north/south, ×0.828
 *   east/west, ×0.8 down), so a crop block looks like the crop display did. Ambient occlusion off, like the display.</li>
 * </ul>
 * Pure (unit-tested). Directions are found the way the client's face baker does (float maths, the closest of
 * down, up, north, south, west, east), so a 45-degree plant plane gets the same shading the client gives it.
 */
public final class CropBlockModels {

    private CropBlockModels() {}

    public static final List<String> DIRS = List.of("down", "up", "north", "south", "west", "east");
    private static final Map<String, int[]> VEC = Map.of("down", new int[]{0, -1, 0}, "up", new int[]{0, 1, 0}, "north", new int[]{0, 0, -1},
            "south", new int[]{0, 0, 1}, "west", new int[]{-1, 0, 0}, "east", new int[]{1, 0, 0});
    /** block face light by direction (CardinalLighting.DEFAULT) */
    private static final Map<String, Double> BLOCK = Map.of("down", 0.5, "up", 1.0, "north", 0.8, "south", 0.8, "west", 0.6, "east", 0.6);

    /** Entity / item light of a face with this normal: two lights, (0.2, 1, -0.7) and (-0.2, 1, 0.7), 0.6 each + 0.4 ambient. */
    static double entityLight(int[] n) {
        double l = Math.sqrt(0.2 * 0.2 + 1 + 0.7 * 0.7);
        double a = (0.2 * n[0] + n[1] - 0.7 * n[2]) / l, b = (-0.2 * n[0] + n[1] + 0.7 * n[2]) / l;
        return Math.min(1.0, 0.6 * (Math.max(0, a) + Math.max(0, b)) + 0.4);
    }

    /** The darkened copy a face baked with this direction uses: "ns", "ew", "dn", or null (up: the texture itself). */
    public static String shadeClass(String dir) {
        return switch (dir) {
            case "north", "south" -> "ns";
            case "west", "east" -> "ew";
            case "down" -> "dn";
            default -> null;
        };
    }

    /** What a texture of this class is multiplied by (entity light / block light). */
    public static double ratio(String cls) {
        if (cls == null) return 1.0;
        String d = switch (cls) {
            case "ns" -> "north";
            case "ew" -> "east";
            case "dn" -> "down";
            default -> throw new IllegalArgumentException(cls);
        };
        return entityLight(VEC.get(d)) / BLOCK.get(d);
    }

    /** Makes (or finds) the block-atlas texture for an item texture reference and a shade class; returns its reference. */
    @FunctionalInterface
    public interface Textures {
        String block(String itemTexture, String shadeClass);
    }

    public record Converted(JsonObject model, List<String> warnings) {}

    /**
     * @param item    the item model (elements, textures, display)
     * @param quarter extra quarter turns about Y (0..3), clockwise seen from above (north -> east), on top of the display's 180
     */
    public static Converted convert(JsonObject item, int quarter, Textures textures) {
        List<String> warnings = new ArrayList<>();
        JsonObject fixed = item.has("display") && item.getAsJsonObject("display").has("fixed") ? item.getAsJsonObject("display").getAsJsonObject("fixed") : null;
        if (fixed != null && !(identity(fixed, "rotation", 0) && identity(fixed, "translation", 0) && identity(fixed, "scale", 1))) {
            warnings.add("display.fixed 가 기본값이 아님 (블록은 그 변환 없이 그려짐)");
        }
        JsonObject itemTextures = item.has("textures") ? item.getAsJsonObject("textures") : new JsonObject();
        JsonObject outTextures = new JsonObject();
        JsonArray outElements = new JsonArray();
        int turns = 2 + quarter;
        for (JsonElement je : item.getAsJsonArray("elements")) {
            JsonObject el = je.getAsJsonObject();
            JsonObject ne = turnElement(el, turns);
            for (String a : List.of("from", "to")) {
                for (JsonElement v : ne.getAsJsonArray(a)) {
                    if (v.getAsDouble() < -16 || v.getAsDouble() > 32) warnings.add("블록 모델 범위(-16~32)를 벗어남: " + ne.getAsJsonArray(a));
                }
            }
            JsonObject faces = ne.getAsJsonObject("faces");
            String override = el.has("shade_direction_override") ? el.get("shade_direction_override").getAsString() : null;
            for (String f : new ArrayList<>(faces.keySet())) {
                JsonObject face = faces.getAsJsonObject(f);
                String dir = bakedDirection(ne, f);
                if (dir == null) {
                    faces.remove(f);   // no area: the game drops it too
                    continue;
                }
                String cls = shadeClass(override != null ? override : dir);
                String key = face.get("texture").getAsString().substring(1);
                String ref = resolve(itemTextures, key);
                if (ref == null) {
                    warnings.add("텍스처 #" + key + " 없음");
                    continue;
                }
                String newKey = cls == null ? key : key + "_" + cls;
                if (!outTextures.has(newKey)) outTextures.addProperty(newKey, textures.block(ref, cls));
                face.addProperty("texture", "#" + newKey);
            }
            outElements.add(ne);
        }
        String particleRef = itemTextures.has("particle") ? resolve(itemTextures, "particle") : resolve(itemTextures, "0");
        if (particleRef != null) outTextures.addProperty("particle", textures.block(particleRef, null));
        JsonObject model = new JsonObject();
        model.addProperty("ambientocclusion", false);
        model.add("textures", outTextures);
        model.add("elements", outElements);
        return new Converted(model, warnings);
    }

    private static boolean identity(JsonObject o, String key, double v) {
        if (!o.has(key)) return true;
        for (JsonElement e : o.getAsJsonArray(key)) if (e.getAsDouble() != v) return false;
        return true;
    }

    private static String resolve(JsonObject textures, String key) {
        String ref = null;
        for (int i = 0; i < 8 && textures.has(key); i++) {
            ref = textures.get(key).getAsString();
            if (!ref.startsWith("#")) return ref;
            key = ref.substring(1);
        }
        return ref == null || ref.startsWith("#") ? null : ref;
    }

    // ------------------------------------------------------------------------------------------
    // Turning an element about the block's vertical axis
    // ------------------------------------------------------------------------------------------

    /** One quarter turn about Y through the block centre, `turns` times: (x, z) -> (16 - z, x). */
    static double[] turn(double[] p, int turns) {
        double x = p[0], y = p[1], z = p[2];
        for (int i = 0; i < ((turns % 4) + 4) % 4; i++) {
            double nx = 16 - z, nz = x;
            x = nx;
            z = nz;
        }
        return new double[]{x, y, z};
    }

    /** A direction vector turned (no translation). */
    private static int[] turnVec(int[] v, int turns) {
        int x = v[0], y = v[1], z = v[2];
        for (int i = 0; i < ((turns % 4) + 4) % 4; i++) {
            int nx = -z, nz = x;
            x = nx;
            z = nz;
        }
        return new int[]{x, y, z};
    }

    static String turnDir(String dir, int turns) {
        int[] v = turnVec(VEC.get(dir), turns);
        for (String d : DIRS) if (java.util.Arrays.equals(VEC.get(d), v)) return d;
        throw new IllegalStateException(dir);
    }

    /** The four corners of a face in the order the game emits them (pixels). */
    static double[][] corners(String f, double[] a, double[] b) {
        double x1 = a[0], y1 = a[1], z1 = a[2], x2 = b[0], y2 = b[1], z2 = b[2];
        return switch (f) {
            case "north" -> new double[][]{{x2, y2, z1}, {x1, y2, z1}, {x1, y1, z1}, {x2, y1, z1}};
            case "south" -> new double[][]{{x1, y2, z2}, {x2, y2, z2}, {x2, y1, z2}, {x1, y1, z2}};
            case "east" -> new double[][]{{x2, y2, z2}, {x2, y2, z1}, {x2, y1, z1}, {x2, y1, z2}};
            case "west" -> new double[][]{{x1, y2, z1}, {x1, y2, z2}, {x1, y1, z2}, {x1, y1, z1}};
            case "up" -> new double[][]{{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}};
            case "down" -> new double[][]{{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}};
            default -> throw new IllegalArgumentException(f);
        };
    }

    /** UV of each corner of a face (uv rect and rotation). */
    static double[][] faceUvs(JsonObject face) {
        JsonArray uv = face.getAsJsonArray("uv");
        double u1 = uv.get(0).getAsDouble(), v1 = uv.get(1).getAsDouble(), u2 = uv.get(2).getAsDouble(), v2 = uv.get(3).getAsDouble();
        double[][] uvs = {{u1, v1}, {u2, v1}, {u2, v2}, {u1, v2}};
        int k = ((face.has("rotation") ? face.get("rotation").getAsInt() : 0) / 90) % 4;
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) out[i] = uvs[((i - k) % 4 + 4) % 4];
        return out;
    }

    /** A uv rect and rotation that put target[i] on corner i (rotation 0 preferred; reversed rects allowed). */
    static JsonObject solveFace(double[][] target) {
        for (int k = 0; k < 4; k++) {
            double[][] u = new double[4][];
            for (int j = 0; j < 4; j++) u[j] = target[(j + k) % 4];
            if (u[0][0] == u[3][0] && u[1][0] == u[2][0] && u[0][1] == u[1][1] && u[2][1] == u[3][1]) {
                JsonObject face = new JsonObject();
                face.add("uv", nums(u[0][0], u[0][1], u[2][0], u[2][1]));
                if (k != 0) face.addProperty("rotation", 90 * k);
                return face;
            }
        }
        throw new IllegalArgumentException("face uv cannot be turned");
    }

    private static String key(double[] p) {
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f", p[0], p[1], p[2]);
    }

    static JsonObject turnElement(JsonObject el, int turns) {
        double[] from = vec(el.getAsJsonArray("from")), to = vec(el.getAsJsonArray("to"));
        double[] a = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, b = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double x : new double[]{from[0], to[0]}) for (double y : new double[]{from[1], to[1]}) for (double z : new double[]{from[2], to[2]}) {
            double[] p = turn(new double[]{x, y, z}, turns);
            for (int i = 0; i < 3; i++) {
                a[i] = Math.min(a[i], p[i]);
                b[i] = Math.max(b[i], p[i]);
            }
        }
        JsonObject ne = new JsonObject();
        ne.add("from", nums(a));
        ne.add("to", nums(b));
        if (el.has("rotation")) {
            JsonObject r = el.getAsJsonObject("rotation");
            String axis = r.get("axis").getAsString();
            int[] av = switch (axis) {
                case "x" -> new int[]{1, 0, 0};
                case "y" -> new int[]{0, 1, 0};
                default -> new int[]{0, 0, 1};
            };
            int[] rv = turnVec(av, turns);
            int i = rv[0] != 0 ? 0 : rv[1] != 0 ? 1 : 2;
            JsonObject nr = new JsonObject();
            double angle = r.get("angle").getAsDouble() * (rv[i] > 0 ? 1 : -1);
            nr.add("angle", nums(angle).get(0));
            nr.addProperty("axis", "xyz".substring(i, i + 1));
            nr.add("origin", nums(turn(vec(r.getAsJsonArray("origin")), turns)));
            if (r.has("rescale")) nr.add("rescale", r.get("rescale"));
            ne.add("rotation", nr);
        }
        if (el.has("light_emission")) ne.add("light_emission", el.get("light_emission"));
        if (el.has("shade_direction_override")) {
            ne.addProperty("shade_direction_override", turnDir(el.get("shade_direction_override").getAsString(), turns));
        }
        JsonObject faces = new JsonObject();
        JsonObject old = el.getAsJsonObject("faces");
        for (String f : DIRS) {
            if (old == null || !old.has(f)) continue;
            JsonObject face = old.getAsJsonObject(f);
            double[][] cs = corners(f, from, to);
            double[][] uv = faceUvs(face);
            Map<String, double[]> moved = new HashMap<>();
            for (int i = 0; i < 4; i++) moved.put(key(turn(cs[i], turns)), uv[i]);
            String nf = turnDir(f, turns);
            double[][] target = new double[4][];
            double[][] ncs = corners(nf, a, b);
            for (int i = 0; i < 4; i++) {
                target[i] = moved.get(key(ncs[i]));
                if (target[i] == null) throw new IllegalStateException("corner lost turning " + f);
            }
            JsonObject nface = solveFace(target);
            nface.add("texture", face.get("texture"));
            if (face.has("tintindex")) nface.add("tintindex", face.get("tintindex"));
            if (face.has("cullface")) nface.addProperty("cullface", turnDir(face.get("cullface").getAsString(), turns));
            faces.add(nf, nface);
        }
        ne.add("faces", faces);
        return ne;
    }

    // ------------------------------------------------------------------------------------------
    // The direction the game bakes a face with
    // ------------------------------------------------------------------------------------------

    /**
     * The direction the client gives a face of this element (after the element's rotation), null if it has no area:
     * the face's normal from its corners in float maths, then the closest of down, up, north, south, west, east (a later
     * one only when strictly closer).
     */
    public static String bakedDirection(JsonObject el, String face) {
        double[] from = vec(el.getAsJsonArray("from")), to = vec(el.getAsJsonArray("to"));
        double[][] cs = corners(face, from, to);
        float[][] p = new float[4][3];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 3; j++) p[i][j] = (float) cs[i][j] / 16f;
        if (el.has("rotation")) {
            JsonObject r = el.getAsJsonObject("rotation");
            float[][] m = matrix(r.get("axis").getAsString(), r.get("angle").getAsFloat());
            double[] o = vec(r.getAsJsonArray("origin"));
            float[] of = {(float) o[0] / 16f, (float) o[1] / 16f, (float) o[2] / 16f};
            for (int i = 0; i < 4; i++) {
                float[] d = {p[i][0] - of[0], p[i][1] - of[1], p[i][2] - of[2]};
                float[] q = new float[3];
                for (int r1 = 0; r1 < 3; r1++) q[r1] = m[r1][0] * d[0] + m[r1][1] * d[1] + m[r1][2] * d[2] + of[r1];
                p[i] = q;
            }
        }
        float[] e1 = {p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]};
        float[] e2 = {p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]};
        float[] n = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
        float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (len == 0) return null;
        for (int i = 0; i < 3; i++) n[i] /= len;
        float best = 0;
        String bestDir = null;
        for (String d : DIRS) {
            int[] v = VEC.get(d);
            float dot = n[0] * v[0] + n[1] * v[1] + n[2] * v[2];
            if (dot >= 0 && dot > best) {
                best = dot;
                bestDir = d;
            }
        }
        return bestDir;
    }

    private static float[][] matrix(String axis, float angleDeg) {
        float a = angleDeg * 0.017453292f;
        float s = (float) Math.sin(a), c = (float) Math.cos(a);
        return switch (axis) {
            case "x" -> new float[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
            case "y" -> new float[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
            default -> new float[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
        };
    }

    // ------------------------------------------------------------------------------------------

    private static double[] vec(JsonArray a) {
        double[] v = new double[a.size()];
        for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsDouble();
        return v;
    }

    private static JsonArray nums(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) {
            double r = Math.round(d * 10000.0) / 10000.0;
            if (r == Math.rint(r)) a.add((long) r);
            else a.add(r);
        }
        return a;
    }
}
