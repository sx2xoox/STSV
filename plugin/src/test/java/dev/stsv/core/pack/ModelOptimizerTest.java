package dev.stsv.core.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.content.ContentPack;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lossless model and texture clean-up, and that the real pack keeps every model exactly as converted. */
class ModelOptimizerTest {

    private static final List<String> ALL = List.of("north", "east", "south", "west", "up", "down");

    // ------------------------------------------------------------------------------------------
    // model clean-up
    // ------------------------------------------------------------------------------------------

    @Test
    void flatFacesWithoutAreaDisappear() {
        // a plane (zero thickness along x): east/west are the picture, the other four are lines
        JsonObject m = model(cube(8, 0, 0, 8, 16, 16, "h", ALL), cube(0, 0, 0, 4, 4, 4, "o", ALL));
        assertEquals(4, ModelOptimizer.removeZeroAreaFaces(m));
        assertEquals(Set.of("east", "west"), faces(m, 0).keySet());
        assertEquals(6, faces(m, 1).size(), "a solid box keeps every face");
        // a line (flat along two axes) has no face with area at all: the element goes
        JsonObject line = model(cube(8, 0, 8, 8, 16, 8, "o", ALL));
        assertEquals(6, ModelOptimizer.removeZeroAreaFaces(line));
        assertEquals(0, line.getAsJsonArray("elements").size());
    }

    @Test
    void buriedFacesAreKept() {
        // a box inside another: its faces can show through depth ties far away, so nothing is removed
        JsonObject m = model(cube(0, 0, 0, 16, 16, 16, "o", ALL), cube(4, 4, 4, 8, 8, 8, "o", ALL));
        String before = m.toString();
        assertEquals(0, ModelOptimizer.removeZeroAreaFaces(m));
        assertEquals(before, m.toString());
    }

    @Test
    void unusedTexturesAreDroppedButTheParticleAndAliasesStay() {
        JsonObject m = model(cube(0, 0, 0, 16, 16, 16, "o", ALL));
        m.getAsJsonObject("textures").addProperty("particle", "x:item/h");
        m.getAsJsonObject("textures").addProperty("alias", "#anim");
        m.getAsJsonObject("textures").addProperty("o", "#alias");
        ModelOptimizer.dropUnusedTextures(m);
        assertEquals(Set.of("o", "alias", "anim", "particle"), m.getAsJsonObject("textures").keySet());
        JsonObject plain = model(cube(0, 0, 0, 16, 16, 16, "o", ALL));
        ModelOptimizer.dropUnusedTextures(plain);
        assertEquals(Set.of("o"), plain.getAsJsonObject("textures").keySet(), "h and anim are used by no face");
    }

    // ------------------------------------------------------------------------------------------
    // PNG
    // ------------------------------------------------------------------------------------------

    @Test
    void pngsShrinkWithoutChangingAPixel() throws Exception {
        Random rnd = new Random(7);
        int[] palette = new int[12];
        for (int i = 0; i < palette.length; i++) palette[i] = rnd.nextInt();
        palette[0] = 0x00FFFFFF; // fully transparent, but its colour is kept too
        palette[1] = 0x00000000;
        palette[2] = 0x80FF0000; // translucent
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setRGB(x, y, palette[rnd.nextInt(palette.length)]);
        byte[] original = png(img);
        byte[] small = PngOptimizer.optimize(original);
        assertTrue(small.length < original.length, small.length + " < " + original.length);
        assertArrayEquals(pixels(original), pixels(small));
        assertEquals(3, small[25], "palette image");
        assertTrue(new String(small, StandardCharsets.ISO_8859_1).contains("tRNS"), "with tRNS, so it still decodes to RGBA");

        // > 256 colours: a re-compressed RGBA image, or the original
        BufferedImage many = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) many.setRGB(x, y, 0xFF000000 | (x * 32 + y) * 4099);
        byte[] manyPng = png(many);
        byte[] manySmall = PngOptimizer.optimize(manyPng);
        assertTrue(manySmall.length <= manyPng.length);
        assertArrayEquals(pixels(manyPng), pixels(manySmall));
    }

    @Test
    void pngsWithColourChunksOrWithoutAlphaAreLeftAlone() throws Exception {
        byte[] rgba = png(image(0xFF112233));
        // insert a gAMA chunk after IHDR (8 signature + 25 IHDR)
        byte[] gama = {0, 0, 0, 4, 'g', 'A', 'M', 'A', 0, 0, (byte) 0xB1, (byte) 0x8F, 0x0B, (byte) 0xFC, 0x61, 0x05};
        byte[] withGama = new byte[rgba.length + gama.length];
        System.arraycopy(rgba, 0, withGama, 0, 33);
        System.arraycopy(gama, 0, withGama, 33, gama.length);
        System.arraycopy(rgba, 33, withGama, 33 + gama.length, rgba.length - 33);
        assertSame(withGama, PngOptimizer.optimize(withGama));

        BufferedImage rgb = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        byte[] rgbPng = png(rgb);
        assertSame(rgbPng, PngOptimizer.optimize(rgbPng), "an RGB image decodes to 3 channels; it stays as it is");
        byte[] junk = {1, 2, 3};
        assertSame(junk, PngOptimizer.optimize(junk));
    }

    // ------------------------------------------------------------------------------------------
    // the real packs: every built model is its converted model, with the same texels behind every face
    // ------------------------------------------------------------------------------------------

    @Test
    void builtModelsAreTheConvertedModelsWithTheSameTexels() throws Exception {
        Path content = Path.of(System.getProperty("stsv.content")), fixtures = Path.of(System.getProperty("stsv.fixtures"));
        int faces = 0;
        // the core pack, the test fixtures and, next to the core in this repository, the economy pack (machines, crops)
        Map<Path, String> roots = new java.util.LinkedHashMap<>();
        roots.put(content.resolve("stsv"), "stsv");
        roots.put(fixtures.resolve("stsv"), "stsv");
        Path economy = content.getParent().resolve("economy/content/economy");
        if (Files.isDirectory(economy.resolve("models"))) roots.put(economy, "economy");
        for (Map.Entry<Path, String> pack : roots.entrySet()) {
            Path root = pack.getKey();
            String ns = pack.getValue();
            Map<String, byte[]> files = unzip(new PackBuilder().build(List.of(new ContentPack(ns, ns, root)), List.of(), 97, "t").zip());
            List<Path> models;
            try (var s = Files.list(root.resolve("models"))) {
                models = s.filter(p -> p.toString().endsWith(".bbmodel")).sorted().toList();
            }
            for (Path p : models) {
                String name = p.getFileName().toString().replace(".bbmodel", "");
                BBModelConverter.Result r = BBModelConverter.convert(p, ns + ":item/" + name);
                // through JSON text, as the client reads it (the converter holds floats, the pack text decimals)
                JsonObject before = JsonParser.parseString(r.model().toString()).getAsJsonObject();
                JsonObject after = JsonParser.parseString(new String(files.get("assets/" + ns + "/models/item/" + name + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
                for (String k : new TreeSet<>(before.keySet())) {
                    if (!k.equals("elements") && !k.equals("textures")) assertEquals(before.get(k), after.get(k), name + ": " + k);
                }
                assertEquals(new TreeSet<>(before.keySet()), new TreeSet<>(after.keySet()), name);
                assertArrayEquals(pixels(texture(r, before, "particle")), pixels(files.get(packTexture(after, "particle"))), name + ": particle");

                JsonArray be = before.getAsJsonArray("elements"), ae = after.getAsJsonArray("elements");
                assertEquals(be.size(), ae.size(), name + ": same elements");
                for (int i = 0; i < be.size(); i++) {
                    JsonObject b = be.get(i).getAsJsonObject(), a = ae.get(i).getAsJsonObject();
                    assertEquals(new TreeSet<>(b.keySet()), new TreeSet<>(a.keySet()), name);
                    for (String k : b.keySet()) if (!k.equals("faces")) assertEquals(b.get(k), a.get(k), name + " " + k);
                    assertEquals(b.getAsJsonObject("faces").keySet(), a.getAsJsonObject("faces").keySet(), name + ": same faces");
                    for (Map.Entry<String, JsonElement> f : b.getAsJsonObject("faces").entrySet()) {
                        JsonObject fb = f.getValue().getAsJsonObject(), fa = a.getAsJsonObject("faces").getAsJsonObject(f.getKey());
                        for (String k : fb.keySet()) if (!k.equals("texture")) assertEquals(fb.get(k), fa.get(k), name + " " + k);
                        assertEquals(fb.keySet(), fa.keySet(), name);
                        String key = fb.get("texture").getAsString().substring(1);
                        assertArrayEquals(pixels(texture(r, before, key)), pixels(files.get(packTexture(after, fa.get("texture").getAsString().substring(1)))), name + " texels");
                        faces++;
                    }
                }
            }
        }
        assertTrue(faces > 1000, "checked " + faces + " faces");
    }

    // ------------------------------------------------------------------------------------------

    private static JsonObject cube(double x0, double y0, double z0, double x1, double y1, double z1, String tex, List<String> faces, String... override) {
        JsonObject el = new JsonObject();
        el.add("from", JsonParser.parseString("[" + x0 + "," + y0 + "," + z0 + "]"));
        el.add("to", JsonParser.parseString("[" + x1 + "," + y1 + "," + z1 + "]"));
        JsonObject fs = new JsonObject();
        for (String f : faces) {
            String t = tex;
            for (int i = 0; i + 1 < override.length; i += 2) if (override[i].equals(f)) t = override[i + 1];
            fs.add(f, JsonParser.parseString("{\"uv\":[0,0,16,16],\"texture\":\"#" + t + "\"}"));
        }
        el.add("faces", fs);
        return el;
    }

    private static JsonObject model(JsonObject... elements) {
        JsonObject m = new JsonObject();
        JsonObject t = new JsonObject();
        for (String k : List.of("o", "h", "anim")) t.addProperty(k, "x:item/" + k);
        m.add("textures", t);
        JsonArray els = new JsonArray();
        for (JsonObject e : elements) els.add(e);
        m.add("elements", els);
        return m;
    }

    private static JsonObject faces(JsonObject m, int i) {
        return m.getAsJsonArray("elements").get(i).getAsJsonObject().getAsJsonObject("faces");
    }

    private static BufferedImage image(int argb) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setRGB(x, y, argb);
        return img;
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static int[] pixels(byte[] png) throws Exception {
        assertNotNull(png);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        return Arrays.copyOf(px, px.length);
    }

    /** The converter's own texture bytes behind a texture key of the converted model. */
    private static byte[] texture(BBModelConverter.Result r, JsonObject model, String key) {
        String ref = model.getAsJsonObject("textures").get(key).getAsString();
        return r.textures().get(ref.substring(ref.lastIndexOf('/') + 1) + ".png");
    }

    private static String packTexture(JsonObject model, String key) {
        String ref = model.getAsJsonObject("textures").get(key).getAsString();
        return "assets/" + ref.substring(0, ref.indexOf(':')) + "/textures/" + ref.substring(ref.indexOf(':') + 1) + ".png";
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
