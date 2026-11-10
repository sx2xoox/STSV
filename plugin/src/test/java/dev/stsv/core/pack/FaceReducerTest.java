package dev.stsv.core.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.content.ContentPack;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fewer faces, same look: hidden faces, merging, re-cutting solids, and every real model checked texel by texel. */
class FaceReducerTest {

    private static final String[] ALL = FaceReducer.NAMES;

    /** A model under construction: boxes whose faces each show their own patch of a noise texture, 1:1 on the grid. */
    private static final class M {
        final JsonArray elements = new JsonArray();
        final BufferedImage noise = noise(256, 7);
        int next;

        JsonObject box(double x0, double y0, double z0, double x1, double y1, double z1, String... faces) {
            JsonObject el = new JsonObject();
            el.add("from", JsonParser.parseString("[" + x0 + "," + y0 + "," + z0 + "]"));
            el.add("to", JsonParser.parseString("[" + x1 + "," + y1 + "," + z1 + "]"));
            double[] d = {x1 - x0, y1 - y0, z1 - z0};
            JsonObject fs = new JsonObject();
            for (String f : faces.length == 0 ? ALL : faces) {
                int i = List.of(ALL).indexOf(f);
                double lu = d[FaceReducer.DIR[i][2]], lv = d[FaceReducer.DIR[i][4]];
                // each face its own spot of the noise (whole pixels, 1 px a unit), so a wrong texel shows
                int px = (next * 37) % 200, py = (next * 53) % 200;
                next++;
                fs.add(f, JsonParser.parseString("{\"uv\":[" + px / 16.0 + "," + py / 16.0 + "," + (px + lu) / 16.0 + "," + (py + lv) / 16.0
                        + "],\"texture\":\"#n\"}"));
            }
            el.add("faces", fs);
            elements.add(el);
            return el;
        }

        LookCheck.Model model() {
            JsonObject m = new JsonObject();
            JsonObject t = new JsonObject();
            t.addProperty("n", "x:item/n");
            t.addProperty("particle", "x:item/n");
            m.add("textures", t);
            m.add("elements", JsonParser.parseString(elements.toString()));
            return new LookCheck.Model(m, new HashMap<>(Map.of("n", noise)), Set.of());
        }

        /** Reduced copy, checked to look the same; returns it. */
        LookCheck.Model reduced() {
            LookCheck.Model before = model(), after = model();
            FaceReducer.Result r = FaceReducer.reduce(after.json(), after.images(), Set.of(), "m");
            if (r.atlas() != null) {
                after.images().put("m", r.atlas());
                after.json().getAsJsonObject("textures").addProperty("m", "x:item/m");
                assertEquals(0, r.atlas().getWidth() % 16, "atlas width in 16s");
                assertEquals(0, r.atlas().getHeight() % 16, "atlas height in 16s");
            }
            assertEquals(List.of(), LookCheck.compare("test", before, after, 0.125, 10));
            assertEquals(FaceReducer.faces(after.json()), r.after());
            return after;
        }
    }

    private static BufferedImage noise(int size, long seed) {
        Random rnd = new Random(seed);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setRGB(x, y, 0xff000000 | rnd.nextInt(0x1000000));
        return img;
    }

    private static int faces(LookCheck.Model m) {
        return FaceReducer.faces(m.json());
    }

    private static int elements(LookCheck.Model m) {
        return m.json().getAsJsonArray("elements").size();
    }

    // ------------------------------------------------------------------------------------------
    // hidden faces
    // ------------------------------------------------------------------------------------------

    @Test
    void twoTouchingCubesLoseTheSharedFacesAndBecomeOne() {
        M m = new M();
        m.box(0, 0, 0, 4, 4, 4);
        m.box(4, 0, 0, 8, 4, 4);
        LookCheck.Model r = m.reduced();
        assertEquals(1, elements(r));
        assertEquals(6, faces(r));
        assertEquals("[0,0,0]", r.json().getAsJsonArray("elements").get(0).getAsJsonObject().get("from").toString());
        assertEquals("[8,4,4]", r.json().getAsJsonArray("elements").get(0).getAsJsonObject().get("to").toString());
    }

    @Test
    void touchingCubesThatCannotMergeStillLoseTheFacesBetweenThem() {
        M m = new M();
        m.box(0, 0, 0, 4, 4, 4);
        m.box(4, 0, 0, 8, 4, 4).addProperty("shade", false); // shaded differently: two boxes
        LookCheck.Model r = m.reduced();
        assertEquals(2, elements(r));
        assertEquals(10, faces(r));
    }

    @Test
    void aBoxBuriedInAnotherGoes() {
        M m = new M();
        m.box(0, 0, 0, 16, 16, 16);
        m.box(4, 4, 4, 8, 8, 8);
        LookCheck.Model r = m.reduced();
        assertEquals(1, elements(r));
        assertEquals(6, faces(r));
    }

    @Test
    void seeThroughOrOpenBoxesHideNothing() {
        M open = new M();
        open.box(0, 0, 0, 16, 16, 16, "north", "east", "south", "west", "up"); // no bottom: one can look in
        open.box(4, 4, 4, 8, 8, 8);
        assertEquals(11, faces(open.reduced()));

        M glass = new M();
        glass.box(0, 0, 0, 16, 16, 16);
        glass.box(4, 4, 4, 8, 8, 8);
        LookCheck.Model g = glass.model();
        // one see-through texel on the big box's north face
        JsonArray uv = g.json().getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("north").getAsJsonArray("uv");
        int px = (int) Math.round(uv.get(0).getAsDouble() * 16), py = (int) Math.round(uv.get(1).getAsDouble() * 16);
        glass.noise.setRGB(px + 3, py + 3, 0x00000000);
        assertEquals(12, faces(glass.reduced()));
    }

    @Test
    void facesOnTheFloorStay() {
        M m = new M();
        m.box(4, 0, 4, 12, 6, 12);
        LookCheck.Model r = m.reduced();
        assertEquals(6, faces(r));
        assertTrue(r.json().getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces").has("down"));
    }

    @Test
    void rotatedPartsAreHandled() {
        // a turned box inside a big plain box: all of it is hidden
        M inside = new M();
        inside.box(0, 0, 0, 16, 16, 16);
        inside.box(6, 6, 6, 10, 10, 10).add("rotation", JsonParser.parseString("{\"angle\":22.5,\"axis\":\"y\",\"origin\":[8,8,8]}"));
        LookCheck.Model a = inside.reduced();
        assertEquals(1, elements(a));
        assertEquals(6, faces(a));

        // a turned box hides nothing unturned (its corners stick out of the plain box's faces' planes)
        M turnedBig = new M();
        turnedBig.box(0, 0, 0, 16, 16, 16).add("rotation", JsonParser.parseString("{\"angle\":45,\"axis\":\"y\",\"origin\":[8,8,8]}"));
        turnedBig.box(6, 6, 6, 10, 10, 10);
        assertEquals(12, faces(turnedBig.reduced()));

        // two boxes turned the same way touch in their own frame: the faces between them go, and they merge
        M pair = new M();
        String rot = "{\"angle\":-22.5,\"axis\":\"z\",\"origin\":[8,8,8]}";
        pair.box(2, 2, 2, 6, 6, 6).add("rotation", JsonParser.parseString(rot));
        pair.box(6, 2, 2, 10, 6, 6).add("rotation", JsonParser.parseString(rot));
        LookCheck.Model p = pair.reduced();
        assertEquals(6, faces(p));
        assertEquals(rot, p.json().getAsJsonArray("elements").get(0).getAsJsonObject().get("rotation").toString());

        // turned different ways: they hide nothing of each other
        M twoWays = new M();
        twoWays.box(2, 2, 2, 6, 6, 6).add("rotation", JsonParser.parseString(rot));
        twoWays.box(6, 2, 2, 10, 6, 6).add("rotation", JsonParser.parseString("{\"angle\":22.5,\"axis\":\"z\",\"origin\":[8,8,8]}"));
        assertEquals(12, faces(twoWays.reduced()));
    }

    @Test
    void theGameTurnsPointsTheWayVanillaDoes() {
        JsonObject rot = JsonParser.parseString("{\"angle\":90,\"axis\":\"y\",\"origin\":[8,8,8]}").getAsJsonObject();
        double[] p = FaceReducer.rotate(rot, new double[]{16, 8, 8});
        // +x turns to -z about y (right-handed, like the game's Axis.YP)
        assertEquals(8, p[0], 1e-9);
        assertEquals(0, p[2], 1e-9);
        // rescale: the turned box stretched back to its old width (1 / cos 45 on the two turned axes)
        JsonObject rescaled = JsonParser.parseString("{\"angle\":45,\"axis\":\"z\",\"origin\":[8,8,8],\"rescale\":true}").getAsJsonObject();
        double[] q = FaceReducer.rotate(rescaled, new double[]{16, 8, 8});
        assertEquals(16, q[0], 1e-9);
        assertEquals(16, q[1], 1e-9);
    }

    // ------------------------------------------------------------------------------------------
    // merging and re-cutting
    // ------------------------------------------------------------------------------------------

    @Test
    void aMergedStripShowsTheSameTexels() {
        M m = new M();
        for (int k = 0; k < 4; k++) m.box(2, 0, 2 + 3 * k, 5, 2, 5 + 3 * k);
        LookCheck.Model r = m.reduced(); // reduced() checks every texel of the old faces against the new ones
        assertEquals(1, elements(r));
        assertEquals(6, faces(r));
        JsonObject el = r.json().getAsJsonArray("elements").get(0).getAsJsonObject();
        // the long side shows 12 texels for 12 units: 1 px a unit, like every other face of the pack
        JsonArray uv = el.getAsJsonObject("faces").getAsJsonObject("east").getAsJsonArray("uv");
        int w = r.images().get("m").getWidth();
        assertEquals(12.0, Math.abs(uv.get(2).getAsDouble() - uv.get(0).getAsDouble()) * w / 16, 1e-3);
    }

    @Test
    void halfPixelSeamsKeepBothHalves() {
        // parts meeting at x = 2.5: the texel there is half one part's, half the other's; the new picture keeps both
        M m = new M();
        m.box(0, 0, 0, 2.5, 3, 3);
        m.box(2.5, 0, 0, 5, 3, 3);
        LookCheck.Model r = m.reduced();
        assertEquals(6, faces(r));
    }

    @Test
    void aSteppedDiskIsRecutIntoFewerBoxes() {
        // a disk of diameter 10 as stepped slices (the way the generators make round things)
        M m = new M();
        int[] half = {2, 3, 4, 4, 5, 5, 4, 4, 3, 2};
        for (int z = 0; z < half.length; z++) m.box(8 - half[z], 0, 3 + z, 8 + half[z], 4, 4 + z);
        int before = faces(m.model());
        LookCheck.Model r = m.reduced();
        assertTrue(faces(r) <= before / 2, before + " -> " + faces(r));
    }

    @Test
    void facesThatCannotBeCopiedStay() {
        // a picture stretched over a face (not 1 px a unit) is never repainted: the boxes stay apart
        M m = new M();
        m.box(0, 0, 0, 4, 4, 4);
        JsonObject b = m.box(4, 0, 0, 8, 4, 4);
        b.getAsJsonObject("faces").getAsJsonObject("up").add("uv", JsonParser.parseString("[0,0,16,16]"));
        LookCheck.Model r = m.reduced();
        assertEquals(10, faces(r));
    }

    // ------------------------------------------------------------------------------------------
    // the real packs
    // ------------------------------------------------------------------------------------------

    @Test
    void everyShippedModelLooksTheSameWithFewerFaces() throws Exception {
        Path content = Path.of(System.getProperty("stsv.content")), fixtures = Path.of(System.getProperty("stsv.fixtures"));
        Map<Path, String> roots = new java.util.LinkedHashMap<>();
        roots.put(content.resolve("stsv"), "stsv");
        roots.put(fixtures.resolve("stsv"), "stsv");
        Path economy = content.getParent().resolve("economy/content/economy");
        if (Files.isDirectory(economy.resolve("models"))) roots.put(economy, "economy");
        int before = 0, after = 0, models = 0;
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Path, String> pack : roots.entrySet()) {
            String ns = pack.getValue();
            PackBuilder builder = new PackBuilder();
            Map<String, byte[]> files = unzip(builder.build(List.of(new ContentPack(ns, ns, pack.getKey())), List.of(), 97, "t").zip());
            Set<String> crops = new HashSet<>();
            for (var spec : dev.stsv.core.crop.CropSpec.loadAll(List.of(new ContentPack(ns, ns, pack.getKey())), w -> {})) {
                for (String id : spec.models()) crops.add(id.replace(":model/", ":"));
            }
            List<Path> paths;
            try (var s = Files.list(pack.getKey().resolve("models"))) {
                paths = s.filter(p -> p.toString().endsWith(".bbmodel")).sorted().toList();
            }
            for (Path p : paths) {
                String name = p.getFileName().toString().replace(".bbmodel", "");
                BBModelConverter.Result r = BBModelConverter.convert(p, ns + ":item/" + name);
                JsonObject b = JsonParser.parseString(r.model().toString()).getAsJsonObject();
                ModelOptimizer.removeZeroAreaFaces(b);
                JsonObject a = JsonParser.parseString(new String(files.get("assets/" + ns + "/models/item/" + name + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
                for (String k : b.keySet()) if (!k.equals("elements") && !k.equals("textures")) assertEquals(b.get(k), a.get(k), name + ": " + k);
                if (crops.contains(ns + ":" + name)) {
                    // crops (drawn as blocks) are shipped exactly as converted
                    assertEquals(FaceReducer.faces(b), FaceReducer.faces(a), name);
                    assertEquals(b.getAsJsonArray("elements").size(), a.getAsJsonArray("elements").size(), name);
                    continue;
                }
                problems.addAll(LookCheck.compare(name, model(b, r, null), model(a, null, files), 0.5, 3));
                before += FaceReducer.faces(b);
                after += FaceReducer.faces(a);
                models++;
            }
        }
        assertEquals(List.of(), problems);
        assertTrue(models > 100, models + " models");
        assertTrue(after < before * 0.8, "faces " + before + " -> " + after);
    }

    /** A model with its pictures: from the converter (r) or from the built pack's files. */
    private static LookCheck.Model model(JsonObject json, BBModelConverter.Result r, Map<String, byte[]> files) throws Exception {
        Map<String, BufferedImage> images = new HashMap<>();
        Set<String> animated = new HashSet<>();
        JsonObject refs = json.getAsJsonObject("textures");
        for (String k : refs.keySet()) {
            String ref = refs.get(k).getAsString();
            if (ref.startsWith("#")) continue;
            byte[] png;
            boolean anim;
            if (r != null) {
                String file = ref.substring(ref.lastIndexOf('/') + 1) + ".png";
                png = r.textures().get(file);
                anim = r.textures().containsKey(file + ".mcmeta");
            } else {
                String path = "assets/" + ref.substring(0, ref.indexOf(':')) + "/textures/" + ref.substring(ref.indexOf(':') + 1) + ".png";
                png = files.get(path);
                anim = files.containsKey(path + ".mcmeta");
            }
            assertNotNull(png, ref);
            images.put(k, ImageIO.read(new ByteArrayInputStream(png)));
            if (anim) animated.add(k);
        }
        return new LookCheck.Model(json, images, animated);
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
