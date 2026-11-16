package dev.stsv.core.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.CustomItem.CosmeticSlot;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Cosmetics fade out near the camera (config cosmetics.near-fade), so a player never sees their own worn models in first
 * person (they hang round the camera there) and sees them all in third person (F5, the camera four blocks away).
 *
 * <p>How: the pack's own core/item shader (resources pack/near_fade, the program every item model goes through in 26.3)
 * drops the pixels of a marked model by an ordered dither (Bayer 4x4) as the distance from the camera to that pixel goes
 * from a band's outer distance (all drawn) to its inner one (nothing drawn). The mark is a tint: every model a cosmetic
 * display shows gets item definitions "stsv:near_fade/&lt;band&gt;/&lt;ns&gt;/&lt;path&gt;" with a constant tint
 * (red 253, green 254, blue 248 + band) on a copy of the model whose faces all take tint 0. The shader reads the tint
 * from the vertex colour (exact: a vertex attribute, not a texture, so no atlas or mipmap can blur it) and draws the
 * model white-tinted, i.e. in its own colours. Nothing else in the game carries that tint: held items, blocks, the
 * inventory and other entities go through the shader unchanged.
 *
 * <p>Three bands: {@link Band#OTHERS} for everybody's displays (only right at the camera, a wing poking into it),
 * {@link Band#SELF} for the wearer's own worn displays and {@link Band#PET} for their own pet: the server gives every
 * viewer the OTHERS definitions and CosmeticPackets swaps them for SELF / PET in the packets to the wearer alone.
 *
 * <p>A shader pack (Iris, OptiFine) replaces the game's shaders and with them this one: then cosmetics are drawn as
 * always (also in first person). Turning cosmetics.near-fade.enabled off leaves the item shader and the extra
 * definitions out of the pack.
 */
public final class NearFade {

    /** Which distances a marked model fades at, and its tint's blue (248 + index). */
    public enum Band {
        OTHERS("near", 0), SELF("self", 1), PET("pet", 2);

        public final String id;
        public final int index;

        Band(String id, int index) {
            this.id = id;
            this.index = index;
        }
    }

    /** The marker tint's red and green, and the blue of band 0 (the shader checks exactly these). */
    static final int MARK_RED = 253, MARK_GREEN = 254, MARK_BLUE = 248;

    /** The ordered dither (Bayer 4x4, row by row): a pixel is drawn when the band's coverage is above (n + 0.5) / 16. */
    static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    /** Where the shader goes in the pack (26.3: one core/item program for every item pipeline, by defines). */
    public static final String VSH = "assets/minecraft/shaders/core/item.vsh";
    public static final String FSH = "assets/minecraft/shaders/core/item.fsh";
    /** the line of the fragment shader replaced by the bands */
    static final String BANDS_LINE = "const vec2 STSV_FADE_BANDS[3] = vec2[3](vec2(0.6, 1.2), vec2(2.0, 2.5), vec2(2.5, 3.0));";

    static final String PREFIX = "near_fade/";

    private NearFade() {}

    // ------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------

    /** A band's distances in blocks from the camera: nothing drawn up to inner, all drawn from outer. */
    public record Distances(double inner, double outer) {}

    public record Settings(boolean enabled, Distances others, Distances self, Distances pet) {

        public Distances of(Band b) {
            return switch (b) {
                case OTHERS -> others;
                case SELF -> self;
                case PET -> pet;
            };
        }

        /** cosmetics.near-fade (a band with outer not above inner gets outer = inner + 0.1, reported in problems). */
        public static Settings read(Configuration c, List<String> problems) {
            return new Settings(c.getBoolean("cosmetics.near-fade.enabled"),
                    distances("others", c.getDouble("cosmetics.near-fade.others.inner"), c.getDouble("cosmetics.near-fade.others.outer"), problems),
                    distances("self", c.getDouble("cosmetics.near-fade.self.inner"), c.getDouble("cosmetics.near-fade.self.outer"), problems),
                    distances("pet", c.getDouble("cosmetics.near-fade.pet.inner"), c.getDouble("cosmetics.near-fade.pet.outer"), problems));
        }

        private static Distances distances(String name, double inner, double outer, List<String> problems) {
            double in = Math.max(0, inner);
            if (outer <= in) {
                problems.add("cosmetics.near-fade." + name + ": outer(" + outer + ")는 inner(" + inner + ")보다 커야 함, " + (in + 0.1) + " 으로 씀");
                outer = in + 0.1;
            }
            return new Distances(in, outer);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The fade (the shader does exactly this per pixel; here for tests and previews)
    // ------------------------------------------------------------------------------------------

    /** Share of the pixels drawn at this distance from the camera: 0 up to inner, 1 from outer, linear between. */
    public static double coverage(double distance, Distances d) {
        return Math.clamp((distance - d.inner()) / (d.outer() - d.inner()), 0.0, 1.0);
    }

    /** Whether the pixel at window position (x, y) (gl_FragCoord, whole pixels) is drawn at this distance. */
    public static boolean drawn(double distance, Distances d, int x, int y) {
        return coverage(distance, d) >= (BAYER[(y & 3) * 4 + (x & 3)] + 0.5) / 16.0;
    }

    /** The marker tint (0xRRGGBB) of a band. */
    public static int tint(Band b) {
        return MARK_RED << 16 | MARK_GREEN << 8 | (MARK_BLUE + b.index);
    }

    // ------------------------------------------------------------------------------------------
    // Item model keys
    // ------------------------------------------------------------------------------------------

    /** "ns:path" -> "stsv:near_fade/&lt;band&gt;/ns/path": the model's definition with the band's tint. */
    public static String key(Band b, String modelKey) {
        int colon = modelKey.indexOf(':');
        String ns = colon < 0 ? "minecraft" : modelKey.substring(0, colon), path = modelKey.substring(colon + 1);
        return "stsv:" + PREFIX + b.id + "/" + ns + "/" + path;
    }

    /** A display's item model as sent to everyone ("stsv:near_fade/near/...") -> the band's one; null if it is no such key. */
    static String swap(String key, Band to) {
        String from = "stsv:" + PREFIX + Band.OTHERS.id + "/";
        return key != null && key.startsWith(from) ? "stsv:" + PREFIX + to.id + "/" + key.substring(from.length()) : null;
    }

    /** model keys ("ns:path") that have near-fade definitions in the pack built last (empty: off, or not built yet) */
    private static volatile Set<String> available = Set.of();

    /**
     * After a pack build: which models got near-fade definitions (read off the built pack's item definitions).
     *
     * @return whether that changed (displays already out should take their items again)
     */
    public static boolean packBuilt(Map<String, byte[]> json) {
        String dir = "assets/stsv/items/" + PREFIX + Band.OTHERS.id + "/";
        Set<String> out = new TreeSet<>();
        for (String f : json.keySet()) {
            if (!f.startsWith(dir) || !f.endsWith(".json")) continue;
            String rest = f.substring(dir.length(), f.length() - ".json".length());
            int slash = rest.indexOf('/');
            if (slash > 0) out.add(rest.substring(0, slash) + ":" + rest.substring(slash + 1));
        }
        Set<String> now = Set.copyOf(out);
        boolean changed = !now.equals(available);
        available = now;
        return changed;
    }

    /** The item model a cosmetic display shows: the model's near-fade definition (band OTHERS) when the pack has one. */
    static NamespacedKey shown(NamespacedKey model) {
        if (model == null || !available.contains(model.asString())) return model;
        return NamespacedKey.fromString(key(Band.OTHERS, model.asString()));
    }

    // ------------------------------------------------------------------------------------------
    // Pack
    // ------------------------------------------------------------------------------------------

    /** Slots whose models are worn on (or walk beside) the player as item displays. */
    private static final Set<CosmeticSlot> DISPLAYED = EnumSet.of(CosmeticSlot.ABOVE, CosmeticSlot.SHOULDER, CosmeticSlot.BACK,
            CosmeticSlot.TAIL, CosmeticSlot.NECK, CosmeticSlot.WAIST, CosmeticSlot.AURA, CosmeticSlot.PET);

    /**
     * Every item model a cosmetic display can show: the worn and pet cosmetics' own models, model auras, awakened looks,
     * parts, and the sets' awakening models (cosmetic_sets.yml awaken.model, as "name" or "ns:model/name").
     */
    public static Set<String> models(Collection<CustomItem> items, Collection<String> setModels) {
        Set<String> out = new TreeSet<>();
        for (CustomItem c : items) {
            if (c.cosmetic() == null || !DISPLAYED.contains(c.cosmetic().slot())) continue;
            CustomItem.Premium pr = c.cosmetic().premium();
            if (c.cosmetic().slot() != CosmeticSlot.AURA && c.itemModelKey() != null) out.add(c.itemModelKey().asString());
            if (pr == null) continue;
            if (pr.worn() != null) out.add(pr.worn().asString());
            if (pr.awakened() != null) out.add(pr.awakened().asString());
            for (CustomItem.Part part : pr.parts()) {
                out.add(part.model().asString());
                if (part.awakened() != null) out.add(part.awakened().asString());
            }
        }
        for (String m : setModels) out.add(m.contains(":") ? m : "stsv:model/" + m);
        return out;
    }

    /**
     * Writes, for every model key, a copy of its item definition per band (each "minecraft:model" in it pointing at a
     * tint-0 copy of its model and carrying the band's tint) and the copies of the models. Models without elements of
     * their own (a flat item: the game's generated layers take tint 0 by themselves) are pointed at as they are.
     *
     * @param read  reads an already generated pack file (assets/...) as JSON, or null if absent
     * @param write writes a pack file
     * @return the model keys that got definitions
     */
    public static Set<String> write(Collection<String> modelKeys, Function<String, JsonObject> read, BiConsumer<String, JsonObject> write,
                                    List<String> warnings) {
        Set<String> done = new TreeSet<>();
        Map<String, String> copies = new TreeMap<>();   // model id -> its tint-0 copy's id (or itself)
        for (String key : modelKeys) {
            JsonObject def = read.apply(definitionPath(key));
            if (def == null || !def.has("model") || !def.get("model").isJsonObject()) {
                warnings.add("치장 근접 페이드: 모델 정의가 없음: " + key);
                continue;
            }
            for (Band b : Band.values()) {
                JsonObject copy = def.deepCopy();
                mark(copy.getAsJsonObject("model"), tint(b), id -> copies.computeIfAbsent(id, m -> tintedCopy(m, read, write, warnings)));
                write.accept(definitionPath(key(b, key)), copy);
            }
            done.add(key);
        }
        return done;
    }

    /** Every "minecraft:model" in an item model (selects, conditions, composites ...): its tinted copy and the tint. */
    private static void mark(JsonObject node, int tint, Function<String, String> copyOf) {
        String type = node.has("type") && node.get("type").isJsonPrimitive() ? node.get("type").getAsString() : "";
        if ((type.equals("minecraft:model") || type.equals("model")) && node.has("model") && node.get("model").isJsonPrimitive()) {
            node.addProperty("model", copyOf.apply(node.get("model").getAsString()));
            JsonArray tints = new JsonArray();
            JsonObject constant = new JsonObject();
            constant.addProperty("type", "minecraft:constant");
            constant.addProperty("value", tint);
            tints.add(constant);
            node.add("tints", tints);
            return;
        }
        for (Map.Entry<String, JsonElement> e : node.entrySet()) visit(e.getValue(), tint, copyOf);
    }

    private static void visit(JsonElement e, int tint, Function<String, String> copyOf) {
        if (e.isJsonObject()) mark(e.getAsJsonObject(), tint, copyOf);
        else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) visit(x, tint, copyOf);
    }

    /** "ns:path" of a model -> "stsv:item/near_fade/ns/path", a copy with every face on tint 0 (or the model itself). */
    private static String tintedCopy(String modelId, Function<String, JsonObject> read, BiConsumer<String, JsonObject> write, List<String> warnings) {
        int colon = modelId.indexOf(':');
        String ns = colon < 0 ? "minecraft" : modelId.substring(0, colon), path = modelId.substring(colon + 1);
        JsonObject model = read.apply("assets/" + ns + "/models/" + path + ".json");
        if (model == null) {
            warnings.add("치장 근접 페이드: 모델 파일이 없음: " + modelId);
            return modelId;
        }
        if (!model.has("elements")) return modelId;   // a flat item: its generated layer 0 takes tint 0 by itself
        JsonObject copy = model.deepCopy();
        for (JsonElement el : copy.getAsJsonArray("elements")) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("faces")) continue;
            for (Map.Entry<String, JsonElement> face : el.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
                if (face.getValue().isJsonObject()) face.getValue().getAsJsonObject().addProperty("tintindex", 0);
            }
        }
        String copyPath = "item/" + PREFIX + ns + "/" + path.replaceFirst("^item/", "");
        write.accept("assets/stsv/models/" + copyPath + ".json", copy);
        return "stsv:" + copyPath;
    }

    static String definitionPath(String modelKey) {
        int colon = modelKey.indexOf(':');
        String ns = colon < 0 ? "minecraft" : modelKey.substring(0, colon), path = modelKey.substring(colon + 1);
        return "assets/" + ns + "/items/" + path + ".json";
    }

    /** The two shader files, the bands written into the fragment shader. */
    public static Map<String, byte[]> shaders(Settings s) {
        String fsh = new String(resource("item.fsh"), StandardCharsets.UTF_8);
        if (!fsh.contains(BANDS_LINE)) throw new IllegalStateException("pack/near_fade/item.fsh: 구간 줄이 없음");
        StringBuilder bands = new StringBuilder("const vec2 STSV_FADE_BANDS[3] = vec2[3](");
        for (Band b : Band.values()) {
            Distances d = s.of(b);
            if (b.index > 0) bands.append(", ");
            bands.append("vec2(").append(glsl(d.inner())).append(", ").append(glsl(d.outer())).append(')');
        }
        bands.append(");");
        Map<String, byte[]> out = new TreeMap<>();
        out.put(VSH, resource("item.vsh"));
        out.put(FSH, fsh.replace(BANDS_LINE, bands).getBytes(StandardCharsets.UTF_8));
        return out;
    }

    /** A GLSL float literal ("2.0", "0.65"). */
    static String glsl(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v).replaceFirst("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }

    static byte[] resource(String name) {
        try (InputStream in = NearFade.class.getResourceAsStream("/pack/near_fade/" + name)) {
            if (in == null) throw new IOException("pack/near_fade/" + name + " 없음");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
