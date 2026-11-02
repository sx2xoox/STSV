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
 *   assets/&lt;ns&gt;/textures/item/&lt;model&gt;/&lt;tex&gt;.png   textures of a .bbmodel
 *   assets/&lt;ns&gt;/textures/item/&lt;name&gt;.png           2D item textures
 * plus every file under contents/&lt;pack&gt;/assets copied as-is.
 */
public final class PackBuilder {

    public record Built(byte[] zip, String sha1, int files, List<String> warnings) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Map<String, byte[]> files = new TreeMap<>(); // sorted -> deterministic zip -> stable hash
    private final List<String> warnings = new ArrayList<>();

    public Built build(Collection<ContentPack> packs, Collection<CustomItem> items, int packFormat, String description)
            throws IOException {
        files.clear();
        warnings.clear();
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
        return new Built(zip(), sha1(zip()), files.size(), List.copyOf(warnings));
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
                json("assets/" + ns + "/models/item/" + name + ".json", r.model());
                // every 3D model also gets an item definition "<ns>:model/<name>", so skins, cosmetics and
                // furniture parts can point an item_model at any model without declaring an item for it
                JsonObject ref = new JsonObject();
                ref.addProperty("type", "minecraft:model");
                ref.addProperty("model", ns + ":item/" + name);
                JsonObject def = new JsonObject();
                def.add("model", ref);
                json("assets/" + ns + "/items/model/" + name + ".json", def);
                for (Map.Entry<String, byte[]> t : r.textures().entrySet()) {
                    put("assets/" + ns + "/textures/item/" + name + "/" + t.getKey(), t.getValue(), cp);
                }
            }
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
