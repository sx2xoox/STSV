package dev.stsv.core.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.ModelRef;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the server resource pack in memory from all content packs.
 *
 * Output layout (per namespace):
 *   assets/&lt;ns&gt;/items/&lt;id&gt;.json                   item model definition (what item_model points at)
 *   assets/&lt;ns&gt;/models/item/&lt;model&gt;.json           converted .bbmodel or generated 2D model
 *   assets/&lt;ns&gt;/textures/item/shared/&lt;hash&gt;.png   textures of .bbmodels, stored once by content
 *   assets/&lt;ns&gt;/textures/item/&lt;name&gt;.png           2D item textures
 * plus every file under contents/&lt;pack&gt;/assets copied as-is.
 */
public final class PackBuilder {

    /** @param cosmeticViews item model keys that got a cosmetic self-view wrapper (CosmeticViews) */
    public record Built(byte[] zip, String sha1, int files, List<String> warnings, java.util.Set<String> cosmeticViews) {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create(); // compact: the client only parses it

    private final Map<String, byte[]> files = new TreeMap<>(); // sorted -> deterministic zip -> stable hash
    private final List<String> warnings = new ArrayList<>();

    public Built build(Collection<ContentPack> packs, Collection<CustomItem> items, int packFormat, String description)
            throws IOException {
        files.clear();
        warnings.clear();
        sharedTextures.clear();
        optimizedPngs.clear();
        zipCache = null;

        JsonObject pack = new JsonObject();
        pack.addProperty("description", description);
        pack.addProperty("min_format", packFormat);
        pack.addProperty("max_format", packFormat);
        JsonObject mcmeta = new JsonObject();
        mcmeta.add("pack", pack);
        json("pack.mcmeta", mcmeta);

        for (ContentPack cp : packs) {
            copyRawAssets(cp);
            convertModels(cp);
            wrapTextures(cp);
        }
        for (CustomItem item : items) {
            writeItemDefinition(item);
        }
        // cosmetics seen by their wearer: head items that look like themselves in the inventory
        java.util.Set<String> cosmeticViews = dev.stsv.core.cosmetic.CosmeticViews.write(items, this::readJson, this::json, warnings);
        // menu backgrounds: one generated font with a glyph per background (see MenuSpec)
        List<dev.stsv.core.gui.menu.MenuSpec> menus = dev.stsv.core.gui.menu.MenuSpec.loadAll(List.copyOf(packs), warnings);
        if (menus.stream().anyMatch(m -> m.background() != null)) {
            json("assets/stsv/font/menus.json", dev.stsv.core.gui.menu.MenuSpec.font(menus));
        }
        return new Built(zip(), sha1(zip()), files.size(), List.copyOf(warnings), cosmeticViews);
    }

    private void copyRawAssets(ContentPack cp) throws IOException {
        if (!cp.has(cp.assets())) return;
        try (Stream<Path> s = Files.walk(cp.assets())) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = "assets/" + cp.assets().relativize(p).toString().replace('\\', '/');
                put(rel, Files.readAllBytes(p), cp);
            }
        }
    }

    private void convertModels(ContentPack cp) throws IOException {
        if (!cp.has(cp.models())) return;
        try (Stream<Path> s = Files.list(cp.models())) {
            for (Path p : s.filter(f -> f.toString().endsWith(".bbmodel")).sorted().toList()) {
                String name = BBModelConverter.sanitize(p.getFileName().toString().replaceFirst("\\.bbmodel$", ""));
                String ns = cp.namespace();
                BBModelConverter.Result r = BBModelConverter.convert(p, ns + ":item/" + name);
                r.warnings().forEach(w -> warnings.add(p.getFileName() + ": " + w));
                // every 3D model also gets an item definition "<ns>:model/<name>", so skins, cosmetics and
                // furniture parts can point an item_model at any model without declaring an item for it
                JsonObject ref = new JsonObject();
                ref.addProperty("type", "minecraft:model");
                ref.addProperty("model", ns + ":item/" + name);
                JsonObject def = new JsonObject();
                def.add("model", ref);
                json("assets/" + ns + "/items/model/" + name + ".json", def);

                JsonObject model = r.model();
                JsonObject refs = model.getAsJsonObject("textures");
                String folder = ns + ":item/" + name + "/";
                // texture key -> png / mcmeta of this model
                Map<String, byte[]> pngOf = new java.util.HashMap<>(), metaOf = new java.util.HashMap<>();
                if (refs != null) {
                    for (String k : refs.keySet()) {
                        String v = refs.get(k).getAsString();
                        if (!v.startsWith(folder)) continue;
                        String tex = v.substring(folder.length());
                        if (r.textures().containsKey(tex + ".png")) {
                            pngOf.put(k, r.textures().get(tex + ".png"));
                            if (r.textures().containsKey(tex + ".png.mcmeta")) metaOf.put(k, r.textures().get(tex + ".png.mcmeta"));
                        }
                    }
                }
                // lossless clean-up: faces without area, then textures nothing uses (see ModelOptimizer)
                ModelOptimizer.removeZeroAreaFaces(model);
                ModelOptimizer.dropUnusedTextures(model);

                // identical textures (the same face on several models, e.g. the tiers of a machine) are stored
                // once, by content, under textures/item/shared: smaller pack, fewer atlas sprites. Each is re-encoded
                // losslessly to its smallest PNG first (PngOptimizer), so equal pictures also share one file.
                if (refs != null) {
                    for (String k : new ArrayList<>(refs.keySet())) {
                        byte[] png = pngOf.get(k);
                        if (png == null) continue;
                        byte[] meta = metaOf.get(k);
                        byte[] small = optimizedPngs.computeIfAbsent(hash(png, null), h -> PngOptimizer.optimize(png));
                        String shared = "shared/" + hash(small, meta);
                        if (sharedTextures.add(ns + ":" + shared)) {
                            put("assets/" + ns + "/textures/item/" + shared + ".png", small, cp);
                            if (meta != null) put("assets/" + ns + "/textures/item/" + shared + ".png.mcmeta", meta, cp);
                        }
                        refs.addProperty(k, ns + ":item/" + shared);
                    }
                }
                json("assets/" + ns + "/models/item/" + name + ".json", model);
            }
        }
    }

    /** original PNG (by content hash) -> its losslessly re-encoded form, so each picture is optimised once per build */
    private final Map<String, byte[]> optimizedPngs = new java.util.HashMap<>();

    private final java.util.Set<String> sharedTextures = new java.util.HashSet<>();

    private static String hash(byte[] png, byte[] meta) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(png);
            if (meta != null) md.update(meta);
            return java.util.HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void wrapTextures(ContentPack cp) throws IOException {
        if (!cp.has(cp.textures())) return;
        try (Stream<Path> s = Files.walk(cp.textures())) {
            for (Path p : s.filter(f -> f.toString().endsWith(".png")).sorted().toList()) {
                String name = cp.textures().relativize(p).toString().replace('\\', '/').replaceFirst("\\.png$", "");
                String ns = cp.namespace();
                put("assets/" + ns + "/textures/item/" + name + ".png", Files.readAllBytes(p), cp);
                Path mcmeta = Path.of(p + ".mcmeta"); // animated textures
                if (Files.exists(mcmeta)) put("assets/" + ns + "/textures/item/" + name + ".png.mcmeta", Files.readAllBytes(mcmeta), cp);
                for (String parent : List.of("generated", "handheld")) {
                    JsonObject model = new JsonObject();
                    model.addProperty("parent", "minecraft:item/" + parent);
                    JsonObject tex = new JsonObject();
                    tex.addProperty("layer0", ns + ":item/" + name);
                    model.add("textures", tex);
                    String suffix = parent.equals("handheld") ? "_handheld" : "";
                    json("assets/" + ns + "/models/item/" + name + suffix + ".json", model);
                }
            }
        }
    }

    private void writeItemDefinition(CustomItem item) {
        ModelRef ref = item.model();
        if (ref == null || ref.kind() == ModelRef.Kind.RAW) return;
        String ns = item.pack().namespace();
        String modelPath = ref.modelPath() + (ref.kind() == ModelRef.Kind.TEXTURE && ref.handheld() ? "_handheld" : "");
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", ns + ":" + modelPath);
        JsonObject def = new JsonObject();
        def.add("model", model);
        json("assets/" + item.key().getNamespace() + "/items/" + item.key().getKey() + ".json", def);
    }

    private JsonObject readJson(String path) {
        byte[] data = files.get(path);
        if (data == null) return null;
        try {
            return com.google.gson.JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            warnings.add("JSON 을 읽지 못함: " + path);
            return null;
        }
    }

    private void json(String path, JsonObject obj) {
        files.put(path, GSON.toJson(obj).getBytes(StandardCharsets.UTF_8));
    }

    private void put(String path, byte[] data, ContentPack from) {
        if (files.containsKey(path)) warnings.add("파일 충돌, " + from.id() + " 의 것으로 덮어씀: " + path);
        files.put(path, data);
    }

    private byte[] zipCache;

    private byte[] zip() throws IOException {
        if (zipCache != null) return zipCache;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(java.util.zip.Deflater.BEST_COMPRESSION); // smaller download, same files
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                ZipEntry e = new ZipEntry(f.getKey());
                e.setTime(315532800000L); // fixed timestamp (1980-01-01) so identical content -> identical hash
                zip.putNextEntry(e);
                zip.write(f.getValue());
                zip.closeEntry();
            }
        }
        zipCache = bytes.toByteArray();
        return zipCache;
    }

    private static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
