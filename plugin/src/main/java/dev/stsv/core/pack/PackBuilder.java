package dev.stsv.core.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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

    /**
     * @param cosmeticViews item model keys that got a cosmetic self-view wrapper (CosmeticViews)
     * @param json          the pack's JSON files (item definitions and models), for {@link #itemModel}
     */
    public record Built(byte[] zip, String sha1, int files, List<String> warnings, java.util.Set<String> cosmeticViews,
                        Map<String, byte[]> json) {

        /** A pack without its JSON at hand (furniture then has no models to fit its click boxes to). */
        public Built(byte[] zip, String sha1, int files, List<String> warnings, java.util.Set<String> cosmeticViews) {
            this(zip, sha1, files, warnings, cosmeticViews, Map.of());
        }

        /**
         * The model an item model id ("ns:path", what an item's item_model points at) shows, exactly as sent to
         * players: assets/ns/items/path.json, when it is a plain "minecraft:model", and the model JSON it names
         * (e.g. a converted .bbmodel). Null if there is none, it has no cubes, or it is anything fancier.
         */
        public Model itemModel(String id) {
            int colon = id.indexOf(':');
            String ns = colon < 0 ? "minecraft" : id.substring(0, colon), path = id.substring(colon + 1);
            JsonObject def = parse(json.get("assets/" + ns + "/items/" + path + ".json"));
            if (def == null || !def.has("model") || !def.get("model").isJsonObject()) return null;
            JsonObject ref = placedModel(def.getAsJsonObject("model"));
            String type = ref.has("type") ? ref.get("type").getAsString() : "";
            if (!(type.equals("minecraft:model") || type.equals("model")) || !ref.has("model")) return null;
            String modelId = ref.get("model").getAsString();
            int c = modelId.indexOf(':');
            String mns = c < 0 ? "minecraft" : modelId.substring(0, c), mpath = modelId.substring(c + 1);
            JsonObject model = parse(json.get("assets/" + mns + "/models/" + mpath + ".json"));
            if (model == null || !model.has("elements") || model.getAsJsonArray("elements").isEmpty()) return null;
            return new Model(mns + ":" + mpath, model);
        }

        /**
         * What an item display (display context "fixed") shows of an item model: a select on the display context
         * (a 2D icon in the GUI, the 3D model elsewhere) is followed to its "fixed" case, or its fallback.
         */
        private static JsonObject placedModel(JsonObject ref) {
            String type = ref.has("type") ? ref.get("type").getAsString() : "";
            if (!(type.equals("minecraft:select") || type.equals("select")) || !ref.has("property")
                    || !ref.get("property").getAsString().replaceFirst("^minecraft:", "").equals("display_context")) return ref;
            if (ref.has("cases") && ref.get("cases").isJsonArray()) {
                for (com.google.gson.JsonElement c : ref.getAsJsonArray("cases")) {
                    if (!c.isJsonObject() || !c.getAsJsonObject().has("when") || !c.getAsJsonObject().has("model")) continue;
                    com.google.gson.JsonElement when = c.getAsJsonObject().get("when");
                    boolean fixed = when.isJsonArray() ? when.getAsJsonArray().contains(new com.google.gson.JsonPrimitive("fixed"))
                            : when.isJsonPrimitive() && when.getAsString().equals("fixed");
                    if (fixed && c.getAsJsonObject().get("model").isJsonObject()) return c.getAsJsonObject().getAsJsonObject("model");
                }
            }
            return ref.has("fallback") && ref.get("fallback").isJsonObject() ? ref.getAsJsonObject("fallback") : ref;
        }

        private static JsonObject parse(byte[] data) {
            if (data == null) return null;
            try {
                com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
                return e.isJsonObject() ? e.getAsJsonObject() : null;
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    /** A model of the pack: its id ("ns:item/name") and JSON. */
    public record Model(String id, JsonObject json) {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create(); // compact: the client only parses it

    private final Map<String, byte[]> files = new TreeMap<>(); // sorted -> deterministic zip -> stable hash
    private final List<String> warnings = new ArrayList<>();
    private Map<String, byte[]> extra = Map.of();
    private boolean cropBlocks = true;
    private boolean reduceFaces = true;
    /** item model ids ("ns:model/name") of crop stages: their models are left as converted (see {@link #convertModels}) */
    private final java.util.Set<String> cropModels = new java.util.HashSet<>();
    /** model name ("ns:name") -> {faces as converted, faces shipped} */
    private final Map<String, int[]> faceCounts = new TreeMap<>();

    /**
     * Hidden faces removed and neighbouring boxes merged in every 3D model ({@link FaceReducer}, on by default). Off only
     * to compare (the models exactly as converted).
     */
    public PackBuilder reduceFaces(boolean on) {
        this.reduceFaces = on;
        return this;
    }

    /** Faces of each converted model of the last build ("ns:name" -> {as converted, as shipped}). */
    public Map<String, int[]> faceCounts() {
        return java.util.Collections.unmodifiableMap(faceCounts);
    }

    /**
     * Custom crops as blocks (the default): every crop stage as a tripwire state (CropBlocks), with block models made from
     * the stage's item model. Off (crops.render: entities): vanilla string stays string, crops use their item models.
     */
    public PackBuilder cropBlocks(boolean on) {
        this.cropBlocks = on;
        return this;
    }

    /**
     * Files the core makes itself (the resource-pack HUD: fonts, glyph sheets, see-through vanilla sprites). They go
     * in before the content packs, so a content pack's own file of the same path wins (with a warning).
     */
    public PackBuilder extra(Map<String, byte[]> files) {
        this.extra = Map.copyOf(files);
        return this;
    }

    public Built build(Collection<ContentPack> packs, Collection<CustomItem> items, int packFormat, String description)
            throws IOException {
        files.clear();
        warnings.clear();
        sharedTextures.clear();
        optimizedPngs.clear();
        faceCounts.clear();
        cropModels.clear();
        zipCache = null;
        // crops are drawn as blocks from their models (CropBlockModels): they keep their faces exactly as made
        for (dev.stsv.core.crop.CropSpec spec : dev.stsv.core.crop.CropSpec.loadAll(packs, w -> {})) cropModels.addAll(spec.models());

        JsonObject pack = new JsonObject();
        pack.addProperty("description", description);
        pack.addProperty("min_format", packFormat);
        pack.addProperty("max_format", packFormat);
        JsonObject mcmeta = new JsonObject();
        mcmeta.add("pack", pack);
        json("pack.mcmeta", mcmeta);
        files.putAll(extra);

        for (ContentPack cp : packs) {
            copyRawAssets(cp);
            convertModels(cp);
            wrapTextures(cp);
        }
        for (CustomItem item : items) {
            writeItemDefinition(item);
        }
        if (cropBlocks) writeCropBlocks(packs);
        // cosmetics seen by their wearer: head items that look like themselves in the inventory
        java.util.Set<String> cosmeticViews = dev.stsv.core.cosmetic.CosmeticViews.write(items, this::readJson, this::json, warnings);
        // menu backgrounds: one generated font with a glyph per background (see MenuSpec)
        List<dev.stsv.core.gui.menu.MenuSpec> menus = dev.stsv.core.gui.menu.MenuSpec.loadAll(List.copyOf(packs), warnings);
        if (menus.stream().anyMatch(m -> m.background() != null)) {
            json("assets/stsv/font/menus.json", dev.stsv.core.gui.menu.MenuSpec.font(menus));
        }
        Map<String, byte[]> json = new java.util.HashMap<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            String k = f.getKey();
            if (k.endsWith(".json") && (k.contains("/items/") || k.contains("/models/"))) json.put(k, f.getValue());
        }
        return new Built(zip(), sha1(zip()), files.size(), List.copyOf(warnings), cosmeticViews, Map.copyOf(json));
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
                int converted = FaceReducer.faces(model);
                // fewer faces for the same picture (FaceReducer): hidden faces go, neighbouring boxes merge
                if (reduceFaces && refs != null && !cropModels.contains(ns + ":model/" + name)) {
                    String atlasKey = "merged";
                    while (refs.has(atlasKey)) atlasKey += "_";
                    Reduced reduced = reduced(model, pngOf, metaOf.keySet(), atlasKey);
                    model.add("elements", com.google.gson.JsonParser.parseString(reduced.elements()));
                    if (reduced.atlas() != null) {
                        pngOf.put(atlasKey, reduced.atlas());
                        refs.addProperty(atlasKey, folder + name + "_" + atlasKey);
                    }
                }
                faceCounts.put(ns + ":" + name, new int[]{converted, FaceReducer.faces(model)});
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

    /** A model's reduced elements (JSON text) and the atlas of its new faces (PNG, or null). */
    private record Reduced(String elements, byte[] atlas) {}

    /**
     * Reductions already worked out, by the model and its pictures (a hash of both): a reload (or the next build of the same
     * content) does not do them again. Bounded; the least recently used go first.
     */
    private static final Map<String, Reduced> REDUCED = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Reduced> eldest) {
            return size() > 4096;
        }
    });

    private static Reduced reduced(JsonObject model, Map<String, byte[]> pngOf, java.util.Set<String> animated, String atlasKey) throws IOException {
        String key;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(model.toString().getBytes(StandardCharsets.UTF_8));
            md.update(atlasKey.getBytes(StandardCharsets.UTF_8));
            for (String k : new java.util.TreeSet<>(pngOf.keySet())) {
                md.update((k + (animated.contains(k) ? "*" : "") + "|").getBytes(StandardCharsets.UTF_8));
                md.update(pngOf.get(k));
            }
            key = HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Reduced hit = REDUCED.get(key);
        if (hit != null) return hit;
        Map<String, java.awt.image.BufferedImage> images = new java.util.HashMap<>();
        for (Map.Entry<String, byte[]> t : pngOf.entrySet()) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(t.getValue()));
            if (img != null) images.put(t.getKey(), img);
        }
        JsonObject copy = model.deepCopy();
        FaceReducer.Result r = FaceReducer.reduce(copy, images, animated, atlasKey);
        byte[] atlas = null;
        if (r.atlas() != null) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(r.atlas(), "png", png);
            atlas = png.toByteArray();
        }
        Reduced out = new Reduced(copy.getAsJsonArray("elements").toString(), atlas);
        REDUCED.put(key, out);
        return out;
    }

    /** original PNG (by content hash) -> its losslessly re-encoded form, so each picture is optimised once per build */
    private final Map<String, byte[]> optimizedPngs = new java.util.HashMap<>();

    private final java.util.Set<String> sharedTextures = new java.util.HashSet<>();

    // ------------------------------------------------------------------------------------------
    // Crops as blocks
    // ------------------------------------------------------------------------------------------

    /** Where the tripwire's look is decided: every state, a crop stage's four turned models or vanilla string. */
    public static final String TRIPWIRE = "assets/minecraft/blockstates/tripwire.json";

    /**
     * The crop stages' block models (four turns each, picked at random per block), their textures in the block atlas
     * (darkened per face, CropBlockModels) and assets/minecraft/blockstates/tripwire.json. States that show no crop keep
     * vanilla's string models. Fails the build when the states run out.
     */
    private void writeCropBlocks(Collection<ContentPack> packs) throws IOException {
        List<dev.stsv.core.crop.CropSpec> specs = dev.stsv.core.crop.CropSpec.loadAll(packs, w -> warnings.add("[작물] " + w));
        if (specs.isEmpty()) return;
        dev.stsv.core.crop.CropBlocks.Allocation alloc;
        try {
            alloc = dev.stsv.core.crop.CropBlocks.load(packs, specs);
        } catch (dev.stsv.core.crop.CropBlocks.Full e) {
            throw new IOException(e.getMessage(), e);
        }
        alloc.problems().forEach(p -> warnings.add(dev.stsv.core.crop.CropBlocks.FILE + ": " + p));
        if (!alloc.added().isEmpty()) {
            warnings.add(dev.stsv.core.crop.CropBlocks.FILE + " 에 없는 작물 단계 " + alloc.added().size() + "개에 빈 상태를 줌: "
                    + String.join(", ", alloc.added()) + " (tools/assetgen/crop_blocks.py 로 적어 두면 다음에도 같은 상태)");
        }
        Map<String, dev.stsv.core.crop.CropSpec> byId = new java.util.HashMap<>();
        for (dev.stsv.core.crop.CropSpec s : specs) byId.put(s.id(), s);
        Map<String, String[]> written = new java.util.HashMap<>();   // item model id -> its four block models
        JsonObject variants = new JsonObject();
        for (int code = 0; code < dev.stsv.core.crop.CropBlocks.STATES; code++) {
            String key = alloc.keyOf(code);
            String[] models = null;
            if (key != null) {
                int slash = key.lastIndexOf('/');
                dev.stsv.core.crop.CropSpec spec = byId.get(key.substring(0, slash));
                int stage = Integer.parseInt(key.substring(slash + 1));
                if (spec != null && stage < spec.stages()) {
                    String itemModel = spec.models().get(stage);
                    models = written.get(itemModel);
                    if (models == null && !written.containsKey(itemModel)) {
                        models = cropBlockModels(itemModel);
                        written.put(itemModel, models);
                    }
                }
            }
            com.google.gson.JsonElement v;
            if (models != null) {
                JsonArray turns = new JsonArray();
                for (String m : models) {
                    JsonObject o = new JsonObject();
                    o.addProperty("model", m);
                    turns.add(o);
                }
                v = turns;
            } else {
                dev.stsv.core.crop.CropBlocks.Vanilla van = dev.stsv.core.crop.CropBlocks.vanilla(code);
                JsonObject o = new JsonObject();
                o.addProperty("model", van.model());
                if (van.y() != 0) o.addProperty("y", van.y());
                v = o;
            }
            variants.add(dev.stsv.core.crop.CropBlocks.properties(code), v);
        }
        JsonObject state = new JsonObject();
        state.add("variants", variants);
        if (files.containsKey(TRIPWIRE)) warnings.add("파일 충돌, 작물 블록의 것으로 덮어씀: " + TRIPWIRE);
        json(TRIPWIRE, state);
    }

    /** The four turned block models of a crop stage's item model ("ns:model/name"), or null (with a warning) if it has none. */
    private String[] cropBlockModels(String itemModelId) {
        int c = itemModelId.indexOf(':');
        String ns = itemModelId.substring(0, c), path = itemModelId.substring(c + 1);
        JsonObject def = readJson("assets/" + ns + "/items/" + path + ".json");
        String modelId = def != null && def.has("model") && def.get("model").isJsonObject() && def.getAsJsonObject("model").has("model")
                ? def.getAsJsonObject("model").get("model").getAsString() : null;
        JsonObject model = null;
        if (modelId != null) {
            int mc = modelId.indexOf(':');
            String mns = mc < 0 ? "minecraft" : modelId.substring(0, mc), mpath = modelId.substring(mc + 1);
            model = readJson("assets/" + mns + "/models/" + mpath + ".json");
        }
        if (model == null || !model.has("elements")) {
            warnings.add("[작물] 블록으로 만들 모델이 없음: " + itemModelId + " (실 모양으로 보임)");
            return null;
        }
        String name = BBModelConverter.sanitize(path.substring(path.lastIndexOf('/') + 1));
        String[] out = new String[4];
        for (int k = 0; k < 4; k++) {
            CropBlockModels.Converted conv = CropBlockModels.convert(model, k, this::cropBlockTexture);
            if (k == 0) conv.warnings().forEach(w -> warnings.add("[작물] " + itemModelId + ": " + w));
            String id = ns + ":block/crop/" + name + "_r" + (90 * k);
            json("assets/" + ns + "/models/block/crop/" + name + "_r" + (90 * k) + ".json", conv.model());
            out[k] = id;
        }
        return out;
    }

    /** A crop model's texture copied into the block atlas (textures/block/crop), darkened for its shade class. */
    private String cropBlockTexture(String ref, String cls) {
        int c = ref.indexOf(':');
        String ns = c < 0 ? "minecraft" : ref.substring(0, c), path = ref.substring(c + 1);
        byte[] png = files.get("assets/" + ns + "/textures/" + path + ".png");
        if (png == null) {
            if (!path.startsWith("block/") || cls != null) warnings.add("[작물] 블록에 쓸 수 없는 텍스처: " + ref);
            return ref;
        }
        String name = (path.startsWith("item/") ? path.substring(5) : path).replace('/', '_') + (cls == null ? "" : "_" + cls);
        String dst = "assets/" + ns + "/textures/block/crop/" + name + ".png";
        if (!files.containsKey(dst)) {
            files.put(dst, cls == null ? png : darken(png, CropBlockModels.ratio(cls)));
            byte[] meta = files.get("assets/" + ns + "/textures/" + path + ".png.mcmeta");
            if (meta != null) files.put(dst + ".mcmeta", meta);
        }
        return ns + ":block/crop/" + name;
    }

    /** The picture with its colour (not alpha) times this factor, rounded half to even. */
    static byte[] darken(byte[] png, double ratio) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (img == null) return png;
            int w = img.getWidth(), h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < argb.length; i++) {
                int a = argb[i] >>> 24, r = (argb[i] >> 16) & 0xFF, g = (argb[i] >> 8) & 0xFF, b = argb[i] & 0xFF;
                r = (int) Math.min(255, Math.rint(r * ratio));
                g = (int) Math.min(255, Math.rint(g * ratio));
                b = (int) Math.min(255, Math.rint(b * ratio));
                argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            out.setRGB(0, 0, w, h, argb, 0, w);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(out, "png", bytes);
            return PngOptimizer.optimize(bytes.toByteArray());
        } catch (IOException e) {
            return png;
        }
    }

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
        def.add("model", ref.kind() == ModelRef.Kind.BBMODEL && ref.icon() != null ? withIcon(model, ns + ":item/" + ref.icon()) : model);
        json("assets/" + item.key().getNamespace() + "/items/" + item.key().getKey() + ".json", def);
    }

    /** The 3D model, except in inventories and menus (display context "gui"): the flat icon there, like vanilla's trident. */
    static JsonObject withIcon(JsonObject model3d, String iconModel) {
        JsonObject icon = new JsonObject();
        icon.addProperty("type", "minecraft:model");
        icon.addProperty("model", iconModel);
        JsonObject gui = new JsonObject();
        gui.addProperty("when", "gui");
        gui.add("model", icon);
        JsonArray cases = new JsonArray();
        cases.add(gui);
        JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:display_context");
        select.add("cases", cases);
        select.add("fallback", model3d);
        return select;
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
        if (files.containsKey(path)) warnings.add("파일 충돌, " + from.id() + " 의 것으로 덮어씀: " + path
                + (extra.containsKey(path) ? " (코어 HUD 파일)" : ""));
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
