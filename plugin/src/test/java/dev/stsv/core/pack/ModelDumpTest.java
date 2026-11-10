package dev.stsv.core.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.content.ContentPack;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Not a check: with the environment variable STSV_MODEL_DUMP set to a folder, writes every 3D model of the core and
 * economy packs as shipped ("after") and exactly as converted ("before"), in the preview JSON of
 * tools/assetgen/render/render.mjs, plus faces.csv (model, faces before, faces after) and packs.txt (zip sizes, build
 * times), to compare them in pictures and numbers.
 */
class ModelDumpTest {

    @Test
    void dump() throws Exception {
        String dir = System.getenv("STSV_MODEL_DUMP");
        assumeTrue(dir != null && !dir.isBlank(), "STSV_MODEL_DUMP not set");
        Path out = Path.of(dir);
        Path content = Path.of(System.getProperty("stsv.content"));
        Map<Path, String> roots = new java.util.LinkedHashMap<>();
        roots.put(content.resolve("stsv"), "stsv");
        roots.put(content.getParent().resolve("economy/content/economy"), "economy");
        StringBuilder csv = new StringBuilder("model,before,after\n"), stats = new StringBuilder();
        for (Map.Entry<Path, String> pack : roots.entrySet()) {
            String ns = pack.getValue();
            for (boolean reduce : new boolean[]{false, true}) {
                PackBuilder b = new PackBuilder().reduceFaces(reduce);
                long t0 = System.nanoTime();
                byte[] zip = b.build(List.of(new ContentPack(ns, ns, pack.getKey())), List.of(), 97, "t").zip();
                stats.append(ns).append(reduce ? " reduced" : " as converted").append(": zip ").append(zip.length).append(" bytes, built in ")
                        .append((System.nanoTime() - t0) / 1_000_000).append(" ms\n");
                Map<String, byte[]> files = unzip(zip);
                Path folder = out.resolve(reduce ? "after" : "before");
                Files.createDirectories(folder);
                String prefix = "assets/" + ns + "/models/item/";
                for (Map.Entry<String, byte[]> f : files.entrySet()) {
                    String k = f.getKey();
                    if (!k.startsWith(prefix) || !k.endsWith(".json") || k.substring(prefix.length()).contains("/")) continue;
                    String name = k.substring(prefix.length(), k.length() - 5);
                    JsonObject model = JsonParser.parseString(new String(f.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!model.has("elements")) continue;
                    Files.writeString(folder.resolve(ns + "__" + name + ".json"), preview(model, files).toString());
                }
                if (reduce) b.faceCounts().forEach((m, c) -> csv.append(m).append(',').append(c[0]).append(',').append(c[1]).append('\n'));
            }
        }
        Files.writeString(out.resolve("faces.csv"), csv.toString());
        Files.writeString(out.resolve("packs.txt"), stats.toString());
    }

    /** A pack model as render.mjs preview JSON (textures inline). */
    static JsonObject preview(JsonObject model, Map<String, byte[]> files) {
        JsonObject refs = model.getAsJsonObject("textures");
        JsonObject textures = new JsonObject();
        JsonArray elements = new JsonArray();
        for (JsonElement je : model.getAsJsonArray("elements")) {
            JsonObject e = je.getAsJsonObject();
            JsonObject o = new JsonObject();
            o.add("from", e.get("from"));
            o.add("to", e.get("to"));
            o.addProperty("glow", e.has("light_emission") && e.get("light_emission").getAsInt() > 0);
            o.addProperty("shade", !e.has("shade") || e.get("shade").getAsBoolean());
            if (e.has("rotation")) {
                JsonObject r = e.getAsJsonObject("rotation");
                JsonArray rot = new JsonArray();
                rot.add(r.get("axis"));
                rot.add(r.get("angle"));
                rot.add(r.has("origin") ? r.get("origin") : JsonParser.parseString("[8,8,8]"));
                o.add("rotation", rot);
            } else {
                o.add("rotation", com.google.gson.JsonNull.INSTANCE);
            }
            JsonObject faces = new JsonObject();
            for (Map.Entry<String, JsonElement> f : e.getAsJsonObject("faces").entrySet()) {
                JsonObject face = f.getValue().getAsJsonObject();
                String key = face.get("texture").getAsString().substring(1);
                while (refs.has(key) && refs.get(key).getAsString().startsWith("#")) key = refs.get(key).getAsString().substring(1);
                String ref = refs.get(key).getAsString();
                String path = "assets/" + ref.substring(0, ref.indexOf(':')) + "/textures/" + ref.substring(ref.indexOf(':') + 1) + ".png";
                if (!textures.has(ref)) textures.addProperty(ref, "data:image/png;base64," + Base64.getEncoder().encodeToString(frame(files.get(path), files.containsKey(path + ".mcmeta"))));
                JsonObject pf = new JsonObject();
                pf.add("uv", face.get("uv"));
                pf.addProperty("texture", ref);
                faces.add(f.getKey(), pf);
            }
            o.add("faces", faces);
            elements.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("textures", textures);
        out.add("elements", elements);
        return out;
    }

    /** An animated strip's first frame (the preview shows one picture). */
    private static byte[] frame(byte[] png, boolean animated) {
        if (!animated) return png;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img.getSubimage(0, 0, img.getWidth(), img.getWidth()), "png", o);
            return o.toByteArray();
        } catch (java.io.IOException e) {
            return png;
        }
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }
}
