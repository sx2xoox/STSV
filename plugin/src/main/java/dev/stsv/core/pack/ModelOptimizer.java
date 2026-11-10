package dev.stsv.core.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lossless clean-up of converted item models (output of {@link BBModelConverter}), done when the pack is built.
 * Nothing here may change a single rendered pixel:
 *
 * <ul>
 *   <li>{@link #removeZeroAreaFaces}: a face of a flat element that lies across the flat side is a line; its two
 *       triangles have coinciding corners and rasterise to nothing;</li>
 *   <li>{@link #dropUnusedTextures}: texture entries no face uses (the particle texture stays), so their files are
 *       not shipped and the client does not stitch them.</li>
 * </ul>
 *
 * Hidden faces and merged boxes (which change a pixel here and there on edges, never a texel) are {@link FaceReducer}'s,
 * run right after {@link #removeZeroAreaFaces}.
 */
public final class ModelOptimizer {

    /** Face name -> the axis it lies across (0 x, 1 y, 2 z). */
    private static final Map<String, Integer> AXIS = Map.of("north", 2, "south", 2, "east", 0, "west", 0, "up", 1, "down", 1);

    private ModelOptimizer() {}

    /**
     * Removes faces without area (the element is flat along one of the face's own two axes), then elements left
     * without faces.
     *
     * @return the number of faces removed
     */
    public static int removeZeroAreaFaces(JsonObject model) {
        JsonArray elements = model.getAsJsonArray("elements");
        if (elements == null) return 0;
        int removed = 0;
        for (Iterator<JsonElement> it = elements.iterator(); it.hasNext(); ) {
            JsonObject el = it.next().getAsJsonObject();
            JsonObject faces = el.getAsJsonObject("faces");
            if (faces == null || faces.isEmpty()) continue;
            JsonArray from = el.getAsJsonArray("from"), to = el.getAsJsonArray("to");
            for (String f : new ArrayList<>(faces.keySet())) {
                Integer a = AXIS.get(f);
                if (a == null) continue;
                for (int k = 0; k < 3; k++) {
                    if (k != a && from.get(k).getAsDouble() == to.get(k).getAsDouble()) {
                        faces.remove(f);
                        removed++;
                        break;
                    }
                }
            }
            if (faces.isEmpty()) it.remove();
        }
        return removed;
    }

    /** Texture keys (without '#') that faces use. */
    public static Set<String> usedTextureKeys(JsonObject model) {
        Set<String> used = new TreeSet<>();
        JsonArray elements = model.getAsJsonArray("elements");
        if (elements == null) return used;
        for (JsonElement je : elements) {
            JsonObject faces = je.getAsJsonObject().getAsJsonObject("faces");
            if (faces == null) continue;
            for (Map.Entry<String, JsonElement> f : faces.entrySet()) {
                JsonObject face = f.getValue().getAsJsonObject();
                if (face.has("texture")) used.add(face.get("texture").getAsString().replaceFirst("^#", ""));
            }
        }
        return used;
    }

    /** Removes texture entries no face uses; the particle texture and entries other entries point at stay. */
    public static void dropUnusedTextures(JsonObject model) {
        JsonObject refs = model.getAsJsonObject("textures");
        if (refs == null) return;
        Set<String> keep = usedTextureKeys(model);
        keep.add("particle");
        // "#key" references between entries: keep what a kept entry points at
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String k : new ArrayList<>(keep)) {
                if (refs.has(k) && refs.get(k).getAsString().startsWith("#")) grew |= keep.add(refs.get(k).getAsString().substring(1));
            }
        }
        for (String k : new ArrayList<>(refs.keySet())) if (!keep.contains(k)) refs.remove(k);
    }
}
