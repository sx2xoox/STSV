package dev.stsv.economy.road;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The sign model as the game draws it lines up with the words RoadSign puts on it: the panel's bottom 3 blocks and its top
 * 4.5 blocks over the road (0.51.0 lifted the model 0.5 too little, so the words looked pushed up off the panel); its back
 * is solid as deep as {@link RoadSign#BACK}, so words seen from behind are hidden.
 */
class RoadSignModelTest {

    @Test
    void thePanelStandsWhereTheWordsAre() throws Exception {
        panel("road_sign", false, RoadSign.PANEL_TOP);
    }

    /** The guide sign's (at junctions): 2 blocks taller than the road, lifted 2. */
    @Test
    void theGuidePanelStandsWhereItsLinesAre() throws Exception {
        panel("road_sign_guide", true, RoadSign.GUIDE_TOP);
        for (String a : java.util.List.of("up", "up_left", "up_right", "left", "right")) {
            assertEquals(true, Files.exists(Path.of(System.getProperty("stsv.content")).resolve("economy/models/road_arrow_" + a + ".bbmodel")), a);
        }
    }

    static void panel(String model, boolean guide, double top) throws Exception {
        Path f = Path.of(System.getProperty("stsv.content")).resolve("economy/models/" + model + ".bbmodel");
        JsonObject m = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        double bottom = Double.NaN, upTo = Double.NaN;
        JsonObject plate = null, backing = null;
        for (JsonElement e : m.getAsJsonArray("elements")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("name").getAsString().equals("backing")) backing = o;
            if (!o.get("name").getAsString().equals("plate")) continue;
            plate = o;
            bottom = o.getAsJsonArray("from").get(1).getAsDouble();
            upTo = o.getAsJsonArray("to").get(1).getAsDouble();
        }
        // the back is solid, as big as the panel and RoadSign.BACK deep behind its face: words seen from behind stay hidden
        assertEquals(true, plate != null && backing != null, model);
        for (int k = 0; k < 2; k++) {
            assertEquals(plate.getAsJsonArray("from").get(k).getAsDouble(), backing.getAsJsonArray("from").get(k).getAsDouble(), 1e-9, model + " backing " + k);
            assertEquals(plate.getAsJsonArray("to").get(k).getAsDouble(), backing.getAsJsonArray("to").get(k).getAsDouble(), 1e-9, model + " backing " + k);
        }
        assertEquals(plate.getAsJsonArray("to").get(2).getAsDouble(), backing.getAsJsonArray("from").get(2).getAsDouble(), 1e-9, "the backing on the plate");
        double deep = (backing.getAsJsonArray("to").get(2).getAsDouble() - plate.getAsJsonArray("from").get(2).getAsDouble()) / 16 * RoadSigns.SCALE;
        assertEquals(true, deep >= RoadSign.BACK - 1e-9, model + ": solid " + deep + " behind the face");
        float lift = RoadSigns.lift(guide);
        // FIXED draws model (8, 8, 8) on the entity, scaled round it, then the lift
        assertEquals(RoadSign.PANEL_BOTTOM, lift + (bottom - 8) / 16 * RoadSigns.SCALE, 1e-6);
        assertEquals(top, lift + (upTo - 8) / 16 * RoadSigns.SCALE, 1e-6);
    }
}
