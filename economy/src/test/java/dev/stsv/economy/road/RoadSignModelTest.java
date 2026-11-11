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
 * 4.5 blocks over the road (0.51.0 lifted the model 0.5 too little, so the words looked pushed up off the panel).
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
        for (JsonElement e : m.getAsJsonArray("elements")) {
            JsonObject o = e.getAsJsonObject();
            if (!o.get("name").getAsString().equals("plate")) continue;
            bottom = o.getAsJsonArray("from").get(1).getAsDouble();
            upTo = o.getAsJsonArray("to").get(1).getAsDouble();
        }
        float lift = RoadSigns.lift(guide);
        // FIXED draws model (8, 8, 8) on the entity, scaled round it, then the lift
        assertEquals(RoadSign.PANEL_BOTTOM, lift + (bottom - 8) / 16 * RoadSigns.SCALE, 1e-6);
        assertEquals(top, lift + (upTo - 8) / 16 * RoadSigns.SCALE, 1e-6);
    }
}
