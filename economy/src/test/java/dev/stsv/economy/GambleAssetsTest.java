package dev.stsv.economy;

import dev.stsv.core.content.ContentModule;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.FurnitureSpec;
import dev.stsv.core.furniture.FurnitureShape;
import dev.stsv.core.pack.PackBuilder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 도박장 assets (docs/plans/도박장_기획.md §2, items/gamble.yml, tools/assetgen/models/gamble.py): every id of the contract is an
 * item with its model; the tables' seats are numbered from the front clockwise and nobody sits in a barrier; the tables
 * themselves block; the lamps give light from a cell without a barrier.
 */
class GambleAssetsTest {

    static final Path PACK = ContentTest.PACK;

    static List<String> contractIds() {
        List<String> ids = new ArrayList<>(List.of("gamble_table_round", "gamble_table_duel", "gamble_card_back", "gamble_die",
                "gamble_bowl", "gamble_cup", "gamble_whiteboard"));
        for (int m = 1; m <= 10; m++) for (String s : List.of("a", "b")) ids.add("gamble_card_" + m + s);
        for (int n = 1; n <= 5; n++) ids.add("gamble_chips_" + n);
        for (String b : List.of("die", "check", "ping", "call", "ddadang", "half", "odd", "even", "roll", "ready", "leave")) ids.add("gamble_btn_" + b);
        for (String p : List.of("cement", "rebar", "cone", "worklight", "drumfire", "sign", "pallet")) ids.add("gamble_prop_" + p);
        return ids;
    }

    static YamlConfiguration items() {
        return YamlConfiguration.loadConfiguration(PACK.resolve("items/gamble.yml").toFile());
    }

    @Test
    void everyContractIdIsAnItemWithItsModel() {
        YamlConfiguration y = items();
        List<String> ids = contractIds();
        assertEquals(50, ids.size());
        assertEquals(ids.size(), y.getKeys(false).size(), "items/gamble.yml holds the contract's ids and nothing else");
        for (String id : ids) {
            ConfigurationSection sec = y.getConfigurationSection(id);
            assertNotNull(sec, id);
            assertEquals(id, sec.getString("model"), id);
            assertTrue(Files.exists(PACK.resolve("models/" + id + ".bbmodel")), id);
        }
        assertTrue(Files.exists(PACK.resolve("assets/economy/textures/entity/bus/site_boss.png")), "현장 소장 skin");
    }

    @Test
    void seatsGoClockwiseFromTheFrontAndFaceTheMiddle() {
        YamlConfiguration y = items();
        ContentPack pack = new ContentPack("economy", "economy", PACK);
        FurnitureSpec round = ContentModule.furnitureSpec(y.getConfigurationSection("gamble_table_round.furniture"), pack);
        FurnitureSpec duel = ContentModule.furnitureSpec(y.getConfigurationSection("gamble_table_duel.furniture"), pack);
        assertEquals(6, round.seats().size());
        assertEquals(2, duel.seats().size());
        for (FurnitureSpec spec : List.of(round, duel)) {
            FurnitureSpec.Seat first = spec.seats().getFirst();
            assertEquals(0, first.cell()[0], "seat 0 is in front");
            assertTrue(first.cell()[2] < 0, "seat 0 is in front (-z)");
            double last = -1;
            for (FurnitureSpec.Seat s : spec.seats()) {
                // the angle round the middle seen from above (north up, east right), clockwise from north
                double a = (Math.toDegrees(Math.atan2(s.cell()[0], -s.cell()[2])) + 360) % 360;
                assertTrue(a > last, "clockwise: " + Arrays.toString(s.cell()));
                last = a;
                // facing (0 = -z, 90 = +x) looks at the middle
                double f = Math.toRadians(s.facing());
                double fx = Math.sin(f), fz = -Math.cos(f);
                double len = Math.hypot(s.cell()[0], s.cell()[2]);
                assertEquals(-s.cell()[0] / len, fx, 0.01, Arrays.toString(s.cell()));
                assertEquals(-s.cell()[2] / len, fz, 0.01, Arrays.toString(s.cell()));
                assertTrue(s.height() >= 0.45 && s.height() <= 0.55);
            }
        }
    }

    @Test
    void barriersFromTheModels() throws Exception {
        YamlConfiguration y = items();
        ContentPack pack = new ContentPack("economy", "economy", PACK);
        PackBuilder.Built built = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        for (String id : y.getKeys(false)) {
            ConfigurationSection f = y.getConfigurationSection(id + ".furniture");
            if (f == null) continue;
            FurnitureSpec spec = ContentModule.furnitureSpec(f, pack);
            PackBuilder.Model model = built.itemModel("economy:model/" + id);
            assertNotNull(model, id);
            FurnitureShape.Voxels v = FurnitureShape.voxelize(spec.cells(), model.json(), spec.translation(), spec.scale(), List.of(), 0);
            FurnitureShape.Shape shape = FurnitureShape.shape(v, spec.cells(), 0.30, spec.solid());
            for (FurnitureSpec.Seat s : spec.seats()) assertFalse(shape.barrier(s.cell()), id + " seat " + Arrays.toString(s.cell()));
            if (spec.light() != null) assertFalse(shape.barrier(spec.light().cell()), id + ": its light cell");
            if (id.startsWith("gamble_table_")) assertTrue(shape.barrier(new int[]{0, 0, 0}), id + ": the table blocks");
        }
    }
}
