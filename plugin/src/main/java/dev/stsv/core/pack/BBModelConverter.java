package dev.stsv.core.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a Blockbench project (.bbmodel, "Java Block/Item" format) into a vanilla model JSON plus PNG textures.
 *
 * Supported: cubes, per-face UV/rotation/tint, single-axis element rotation, inflate, shade, light_emission,
 * embedded or relative-path textures, display transforms. Groups are flattened (vanilla has no bones).
 */
public final class BBModelConverter {

    public record Result(JsonObject model, Map<String, byte[]> textures, List<String> warnings) {}

    private BBModelConverter() {}

    /**
     * @param file          the .bbmodel file
     * @param textureFolder resource location prefix for textures, e.g. "stsv:item/smelter"
     */
    public static Result convert(Path file, String textureFolder) throws IOException {
        JsonObject bb = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> warnings = new ArrayList<>();

        float resW = 16, resH = 16;
        if (bb.has("resolution")) {
            JsonObject res = bb.getAsJsonObject("resolution");
            resW = res.get("width").getAsFloat();
            resH = res.get("height").getAsFloat();
        }

        // --- textures --------------------------------------------------------------------------
        Map<String, byte[]> textures = new LinkedHashMap<>();
        JsonObject texRefs = new JsonObject();
        List<float[]> uvSize = new ArrayList<>();
        JsonArray texArr = bb.has("textures") ? bb.getAsJsonArray("textures") : new JsonArray();
        for (int i = 0; i < texArr.size(); i++) {
            JsonObject t = texArr.get(i).getAsJsonObject();
            String name = sanitize(str(t, "name", "texture_" + i).replaceFirst("\\.png$", ""));
            byte[] png = readTexture(file, t);
            if (png == null) {
                warnings.add("텍스처 '" + name + "'를 읽을 수 없음");
            } else {
                textures.put(name + ".png", png);
                // animated texture: frames stacked vertically (height = n * width) -> .mcmeta
                int w = t.has("width") ? t.get("width").getAsInt() : 0;
                int h = t.has("height") ? t.get("height").getAsInt() : 0;
                if (w > 0 && h > w && h % w == 0) {
                    int frameTime = t.has("frame_time") ? Math.max(1, t.get("frame_time").getAsInt()) : 1;
                    String mcmeta = "{\"animation\":{\"frametime\":" + frameTime
                            + (t.has("frame_interpolate") && t.get("frame_interpolate").getAsBoolean() ? ",\"interpolate\":true" : "")
                            + "}}";
                    textures.put(name + ".png.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));
                }
            }
            texRefs.addProperty(String.valueOf(i), textureFolder + "/" + name);
            if (t.has("particle") && t.get("particle").getAsBoolean()) {
                texRefs.addProperty("particle", textureFolder + "/" + name);
            }
            float uw = t.has("uv_width") ? t.get("uv_width").getAsFloat() : resW;
            float uh = t.has("uv_height") ? t.get("uv_height").getAsFloat() : resH;
            uvSize.add(new float[]{uw, uh});
        }
        if (!texRefs.has("particle") && texRefs.has("0")) texRefs.add("particle", texRefs.get("0"));

        // --- elements --------------------------------------------------------------------------
        JsonArray elements = new JsonArray();
        for (JsonElement je : bb.getAsJsonArray("elements")) {
            JsonObject el = je.getAsJsonObject();
            String type = str(el, "type", "cube");
            if (!type.equals("cube")) {
                warnings.add("'" + str(el, "name", "?") + "' 는 " + type + " 이라 건너뜀 (큐브만 지원)");
                continue;
            }
            if (el.has("export") && !el.get("export").getAsBoolean()) continue;

            float inflate = el.has("inflate") ? el.get("inflate").getAsFloat() : 0;
            float[] from = vec(el.getAsJsonArray("from"));
            float[] to = vec(el.getAsJsonArray("to"));
            for (int a = 0; a < 3; a++) {
                from[a] -= inflate;
                to[a] += inflate;
                if (from[a] < -16 || to[a] > 32) {
                    warnings.add("'" + str(el, "name", "?") + "' 가 바닐라 범위(-16~32)를 벗어남");
                }
            }

            JsonObject out = new JsonObject();
            out.add("from", arr(from));
            out.add("to", arr(to));

            if (el.has("rotation")) {
                float[] rot = vec(el.getAsJsonArray("rotation"));
                int axis = -1;
                for (int a = 0; a < 3; a++) {
                    if (rot[a] != 0) {
                        if (axis != -1) warnings.add("'" + str(el, "name", "?") + "' 는 여러 축 회전 → 첫 축만 적용");
                        else axis = a;
                    }
                }
                if (axis != -1) {
                    JsonObject r = new JsonObject();
                    r.addProperty("angle", rot[axis]);
                    r.addProperty("axis", "xyz".substring(axis, axis + 1));
                    r.add("origin", el.has("origin") ? el.get("origin") : arr(new float[]{8, 8, 8}));
                    if (el.has("rescale") && el.get("rescale").getAsBoolean()) r.addProperty("rescale", true);
                    out.add("rotation", r);
                }
            }
            if (el.has("shade") && !el.get("shade").getAsBoolean()) out.addProperty("shade", false);
            if (el.has("light_emission") && el.get("light_emission").getAsInt() > 0) {
                out.addProperty("light_emission", el.get("light_emission").getAsInt());
            }

            JsonObject faces = new JsonObject();
            JsonObject bbFaces = el.getAsJsonObject("faces");
            for (String f : List.of("north", "east", "south", "west", "up", "down")) {
                if (bbFaces == null || !bbFaces.has(f)) continue;
                JsonObject face = bbFaces.getAsJsonObject(f);
                if (!face.has("texture") || face.get("texture").isJsonNull()) continue;
                int tex = face.get("texture").getAsInt();
                float[] size = tex < uvSize.size() ? uvSize.get(tex) : new float[]{resW, resH};
                float[] uv = vec(face.getAsJsonArray("uv"));
                JsonObject of = new JsonObject();
                of.add("uv", arr(new float[]{
                        uv[0] * 16 / size[0], uv[1] * 16 / size[1], uv[2] * 16 / size[0], uv[3] * 16 / size[1]}));
                of.addProperty("texture", "#" + tex);
                if (face.has("rotation") && face.get("rotation").getAsInt() != 0) of.add("rotation", face.get("rotation"));
                if (face.has("tint") && face.get("tint").getAsInt() >= 0) of.add("tintindex", face.get("tint"));
                if (face.has("cullface") && !face.get("cullface").getAsString().isEmpty()) of.add("cullface", face.get("cullface"));
                faces.add(f, of);
            }
            out.add("faces", faces);
            elements.add(out);
        }

        JsonObject model = new JsonObject();
        if (bb.has("ambientocclusion") && !bb.get("ambientocclusion").getAsBoolean()) {
            model.addProperty("ambientocclusion", false);
        }
        model.add("textures", texRefs);
        model.add("elements", elements);
        if (bb.has("display")) model.add("display", bb.get("display"));
        if (bb.has("front_gui_light") && bb.get("front_gui_light").getAsBoolean()) model.addProperty("gui_light", "front");
        return new Result(model, textures, warnings);
    }

    private static byte[] readTexture(Path bbFile, JsonObject t) throws IOException {
        String source = str(t, "source", "");
        int comma = source.indexOf(',');
        if (source.startsWith("data:image/png;base64,") && comma > 0) {
            return Base64.getDecoder().decode(source.substring(comma + 1));
        }
        for (String key : List.of("relative_path", "path")) {
            String p = str(t, key, "");
            if (p.isEmpty()) continue;
            Path candidate = bbFile.getParent().resolve(p).normalize();
            if (Files.isRegularFile(candidate)) return Files.readAllBytes(candidate);
        }
        // fall back to a png with the same name next to the model
        Path sibling = bbFile.getParent().resolve(str(t, "name", ""));
        return Files.isRegularFile(sibling) ? Files.readAllBytes(sibling) : null;
    }

    static String sanitize(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        return r.isEmpty() ? "texture" : r;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static float[] vec(JsonArray a) {
        float[] v = new float[a.size()];
        for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsFloat();
        return v;
    }

    private static JsonArray arr(float[] v) {
        JsonArray a = new JsonArray();
        for (float f : v) {
            if (f == Math.rint(f)) a.add((int) f);
            else a.add(Math.round(f * 10000f) / 10000f);
        }
        return a;
    }
}
