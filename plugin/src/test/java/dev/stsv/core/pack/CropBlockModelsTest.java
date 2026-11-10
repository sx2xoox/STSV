package dev.stsv.core.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An item model (as the crop display drew it) turned into the block model that looks the same. */
class CropBlockModelsTest {

    /** A box with a different uv on every face, a 45-degree crossed plane, a 22.5-degree tilted cube. */
    private static final String ITEM = """
            {"textures": {"0": "x:item/shared/aaa", "1": "#0", "particle": "x:item/shared/aaa"},
             "display": {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}},
             "elements": [
              {"from": [2, 0, 3], "to": [5, 4, 9], "shade": false, "faces": {
                "north": {"uv": [0, 0, 3, 4], "texture": "#0"}, "south": {"uv": [3, 0, 6, 4], "texture": "#0"},
                "east": {"uv": [0, 4, 6, 8], "texture": "#0"}, "west": {"uv": [6, 4, 12, 8], "texture": "#1", "rotation": 90},
                "up": {"uv": [0, 8, 3, 14], "texture": "#0"}, "down": {"uv": [3, 8, 6, 14], "texture": "#0", "rotation": 180}}},
              {"from": [0, -1, 8], "to": [16, 15, 8], "rotation": {"angle": 45, "axis": "y", "origin": [8, 8, 8]}, "faces": {
                "north": {"uv": [16, 0, 0, 16], "texture": "#0"}, "south": {"uv": [0, 0, 16, 16], "texture": "#0"}}},
              {"from": [-16, 0, 0], "to": [4, 32, 2], "rotation": {"angle": 22.5, "axis": "x", "origin": [0, 0, 0]}, "faces": {
                "up": {"uv": [0, 0, 4, 2], "texture": "#0"}}}
             ]}""";

    private static JsonObject item() {
        return JsonParser.parseString(ITEM).getAsJsonObject();
    }

    private static double[] vec(JsonArray a) {
        double[] v = new double[a.size()];
        for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsDouble();
        return v;
    }

    @Test
    void turnsAboutTheBlockCentre() {
        assertArrayEquals(new double[]{14, 1, 13}, CropBlockModels.turn(new double[]{2, 1, 3}, 2), 1e-9, "the display's 180: x -> 16-x, z -> 16-z");
        assertArrayEquals(new double[]{13, 1, 2}, CropBlockModels.turn(new double[]{2, 1, 3}, 1), "north -> east");
        assertArrayEquals(new double[]{2, 1, 3}, CropBlockModels.turn(new double[]{2, 1, 3}, 4));
        assertEquals("south", CropBlockModels.turnDir("north", 2));
        assertEquals("east", CropBlockModels.turnDir("north", 1));
        assertEquals("up", CropBlockModels.turnDir("up", 3));
    }

    /** Every corner of every face shows the same texel after turning: the picture moves with the geometry. */
    @Test
    void facesAndUvsTurnWithTheGeometry() {
        JsonObject item = item();
        for (int q = 0; q < 4; q++) {
            int turns = 2 + q;
            CropBlockModels.Converted c = CropBlockModels.convert(item, q, (ref, cls) -> ref + "|" + cls);
            JsonArray out = c.model().getAsJsonArray("elements");
            JsonArray in = item.getAsJsonArray("elements");
            for (int i = 0; i < in.size(); i++) {
                JsonObject a = in.get(i).getAsJsonObject(), b = out.get(i).getAsJsonObject();
                for (String f : a.getAsJsonObject("faces").keySet()) {
                    String nf = CropBlockModels.turnDir(f, turns);
                    JsonObject fb = b.getAsJsonObject("faces").getAsJsonObject(nf);
                    assertNotNull(fb, f + " -> " + nf);
                    double[][] cs = CropBlockModels.corners(f, vec(a.getAsJsonArray("from")), vec(a.getAsJsonArray("to")));
                    double[][] uv = CropBlockModels.faceUvs(a.getAsJsonObject("faces").getAsJsonObject(f));
                    double[][] ncs = CropBlockModels.corners(nf, vec(b.getAsJsonArray("from")), vec(b.getAsJsonArray("to")));
                    double[][] nuv = CropBlockModels.faceUvs(fb);
                    for (int k = 0; k < 4; k++) {
                        double[] moved = CropBlockModels.turn(cs[k], turns);
                        int at = -1;
                        for (int j = 0; j < 4; j++) if (java.util.Arrays.equals(moved, ncs[j])) at = j;
                        assertTrue(at >= 0, "corner kept");
                        assertArrayEquals(uv[k], nuv[at], 1e-9, "q" + q + " element " + i + " " + f + " corner " + k);
                    }
                }
            }
            // the 180 first: the box [2,0,3]-[5,4,9] of the item stands at [11,0,7]-[14,4,13] in the block (q = 0)
            if (q == 0) {
                assertArrayEquals(new double[]{11, 0, 7}, vec(out.get(0).getAsJsonObject().getAsJsonArray("from")));
                assertArrayEquals(new double[]{14, 4, 13}, vec(out.get(0).getAsJsonObject().getAsJsonArray("to")));
            }
        }
    }

    @Test
    void elementRotationsFollowTheTurn() {
        JsonObject tilted = item().getAsJsonArray("elements").get(2).getAsJsonObject();
        // 22.5 about +x: turned 180 -> about -x = -22.5 about x; turned 270 (q = 1) -> about -z
        JsonObject r2 = CropBlockModels.turnElement(tilted, 2).getAsJsonObject("rotation");
        assertEquals("x", r2.get("axis").getAsString());
        assertEquals(-22.5, r2.get("angle").getAsDouble());
        assertArrayEquals(new double[]{16, 0, 16}, vec(r2.getAsJsonArray("origin")));
        JsonObject r3 = CropBlockModels.turnElement(tilted, 3).getAsJsonObject("rotation");
        assertEquals("z", r3.get("axis").getAsString());
        assertEquals(-22.5, r3.get("angle").getAsDouble());
        JsonObject r1 = CropBlockModels.turnElement(tilted, 1).getAsJsonObject("rotation");
        assertEquals("z", r1.get("axis").getAsString());
        assertEquals(22.5, r1.get("angle").getAsDouble());
        JsonObject y = CropBlockModels.turnElement(item().getAsJsonArray("elements").get(1).getAsJsonObject(), 1).getAsJsonObject("rotation");
        assertEquals("y", y.get("axis").getAsString());
        assertEquals(45.0, y.get("angle").getAsDouble(), "turning about y leaves y rotations alone");
    }

    @Test
    void staysInsideTheBlockModelBounds() {
        for (int q = 0; q < 4; q++) {
            CropBlockModels.Converted c = CropBlockModels.convert(item(), q, (ref, cls) -> ref);
            assertTrue(c.warnings().isEmpty(), c.warnings().toString());
            for (JsonElement el : c.model().getAsJsonArray("elements")) {
                for (String k : List.of("from", "to")) {
                    for (double v : vec(el.getAsJsonObject().getAsJsonArray(k))) assertTrue(v >= -16 && v <= 32, k + " " + v);
                }
            }
        }
    }

    @Test
    void facesUseTexturesDarkenedForTheirDirection() {
        List<String> asked = new ArrayList<>();
        CropBlockModels.Converted c = CropBlockModels.convert(item(), 0, (ref, cls) -> {
            asked.add(ref + "|" + cls);
            return "x:block/crop/" + ref.substring(ref.lastIndexOf('/') + 1) + (cls == null ? "" : "_" + cls);
        });
        JsonObject m = c.model();
        assertFalse(m.get("ambientocclusion").getAsBoolean(), "flat, like the display");
        JsonObject tex = m.getAsJsonObject("textures");
        assertEquals("x:block/crop/aaa", tex.get("particle").getAsString());
        JsonObject box = m.getAsJsonArray("elements").get(0).getAsJsonObject();
        assertFalse(box.has("shade"));
        Map<String, String> faceTex = new HashMap<>();
        for (String f : box.getAsJsonObject("faces").keySet()) faceTex.put(f, box.getAsJsonObject("faces").getAsJsonObject(f).get("texture").getAsString());
        assertEquals("#0_ns", faceTex.get("north"));
        assertEquals("#0_ns", faceTex.get("south"));
        // turned 180: the item's west face (texture #1) is the block's east face
        assertEquals("#1_ew", faceTex.get("east"), "a #reference is followed to its texture");
        assertEquals("#0_ew", faceTex.get("west"));
        assertEquals("#0", faceTex.get("up"), "up is lit fully either way");
        assertEquals("#0_dn", faceTex.get("down"));
        assertEquals("x:block/crop/aaa_ew", tex.get("1_ew").getAsString());
        // the 45-degree plant plane is baked as north/south by the client's float maths, for every turn
        for (int q = 0; q < 4; q++) {
            JsonObject plane = CropBlockModels.convert(item(), q, (ref, cls) -> ref).model().getAsJsonArray("elements").get(1).getAsJsonObject();
            for (String f : plane.getAsJsonObject("faces").keySet()) {
                assertEquals("ns", CropBlockModels.shadeClass(CropBlockModels.bakedDirection(plane, f)), "q" + q + " " + f);
            }
        }
        assertTrue(asked.contains("x:item/shared/aaa|ns") && asked.contains("x:item/shared/aaa|dn") && asked.contains("x:item/shared/aaa|null"));
    }

    @Test
    void darkeningMatchesEntityOverBlockLight() {
        // entity light (north/south 0.7396, east/west 0.4970, down 0.4) / block light (0.8, 0.6, 0.5)
        assertEquals(0.9244, CropBlockModels.ratio("ns"), 1e-4);
        assertEquals(0.8284, CropBlockModels.ratio("ew"), 1e-4);
        assertEquals(0.8, CropBlockModels.ratio("dn"), 1e-9);
        assertEquals(1.0, CropBlockModels.ratio(null));
    }

    @Test
    void darkenedTextureKeepsAlpha() throws Exception {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFF64C8FF);
        img.setRGB(1, 0, 0x00FFFFFF);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(PackBuilder.darken(png.toByteArray(), 0.5)));
        assertEquals(0xFF326480, out.getRGB(0, 0), "100,200,255 x 0.5 = 50,100,128 (127.5 rounds to even 128)");
        assertEquals(0, out.getRGB(1, 0) >>> 24, "transparent stays transparent");
    }
}
