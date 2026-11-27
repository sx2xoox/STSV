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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 편의점 assets (docs/plans/편의점_기획안.md §15 작업 계약, items/store.yml, tools/assetgen/models/store.py): every id of the
 * contract is an item with its model and nothing else is; the furniture keeps the contract's sizes; the seats sit where
 * there is no barrier and face the right way; the counter, shelves and cases block; the vest is worn in the chest slot
 * with its armour layer (and the 3차 점장 · 야간 vests); the 3차 licences, ID card, order tablet and NPC skins exist.
 */
class StoreAssetsTest {

    static final Path PACK = ContentTest.PACK;

    static final List<String> GOODS = List.of("samgak_tuna", "samgak_bibim", "samgak_bulgogi", "dosirak", "hotbar", "egg", "ramen_red",
            "ramen_mild", "ramen_big", "banana_milk", "coffee_can", "coffee_cup", "soda", "ion_drink", "water", "soju", "beer", "hangover",
            "chips", "shrimp", "choco_snack", "jelly", "chocolate", "candy", "gum", "ice_bar", "ice_cone", "umbrella", "battery", "tissue",
            "bandage", "lottery", "cigarette");

    /** The furniture of the contract and its cells' extent: {x size, y size, z size}. */
    static Map<String, int[]> furniture() {
        Map<String, int[]> out = new java.util.LinkedHashMap<>();
        out.put("store_counter", new int[]{3, 1, 1});
        out.put("store_cig_shelf", new int[]{3, 2, 1});
        for (String c : List.of("snack", "noodle", "daily", "meal"))
            for (String s : List.of("full", "half", "empty")) out.put("store_shelf_" + c + "_" + s, new int[]{1, 2, 3});
        for (String s : List.of("full", "half", "empty")) out.put("store_fridge_" + s, new int[]{2, 2, 1});
        out.put("store_freezer", new int[]{2, 1, 1});
        out.put("store_microwave", new int[]{1, 1, 1});
        out.put("store_kettle", new int[]{1, 1, 1});
        out.put("store_eat_bar", new int[]{3, 1, 1});
        out.put("store_parasol", new int[]{3, 1, 1});
        out.put("store_hanger", new int[]{1, 2, 1});
        out.put("store_waste", new int[]{1, 1, 1});
        out.put("store_mop", new int[]{1, 1, 1});
        out.put("store_sign", new int[]{5, 1, 1});
        out.put("store_order_tablet", new int[]{1, 1, 1});      // 3차 (§16): 발주 단말기, beside the counter
        return out;
    }

    static List<String> displayIds() {
        List<String> ids = new ArrayList<>();
        for (String g : GOODS) ids.add("store_item_" + g);
        for (String b : List.of("snack", "noodle", "daily", "meal", "drink")) ids.add("store_box_" + b);
        ids.addAll(List.of("store_bag", "store_spill", "store_trash_cup", "store_card", "store_scanner", "store_vest"));
        for (int n : new int[]{1000, 5000, 10000, 50000}) ids.add("store_cash_" + n);
        for (int n : new int[]{100, 500}) ids.add("store_coin_" + n);
        for (String b : List.of("card", "cash", "give", "bag", "nobag", "warm", "yes", "no", "wait", "id", "refuse", "sell", "wake", "point"))
            ids.add("store_btn_" + b);
        for (int n : new int[]{50000, 10000, 5000, 1000, 500, 100}) ids.add("store_btn_change_" + n);
        ids.addAll(List.of("store_id_card", "store_vest_manager", "store_vest_night"));   // 3차 (§16)
        return ids;
    }

    static YamlConfiguration items() {
        return YamlConfiguration.loadConfiguration(PACK.resolve("items/store.yml").toFile());
    }

    @Test
    void everyContractIdIsAnItemWithItsModel() {
        YamlConfiguration y = items();
        List<String> ids = new ArrayList<>(furniture().keySet());
        assertEquals(27, ids.size());
        assertEquals(33, GOODS.size());
        ids.addAll(displayIds());
        assertEquals(27 + 73, ids.size());
        assertEquals(ids.size(), y.getKeys(false).size(), "items/store.yml holds the contract's ids and nothing else");
        for (String id : ids) {
            ConfigurationSection sec = y.getConfigurationSection(id);
            assertNotNull(sec, id);
            assertEquals(id, sec.getString("model"), id);
            assertEquals("paper", sec.getString("material"), id);
            assertFalse(sec.getString("name", "").isBlank(), id);
            assertFalse(sec.getStringList("lore").isEmpty(), id);
            assertTrue(Files.exists(PACK.resolve("models/" + id + ".bbmodel")), id);
            assertEquals(furniture().containsKey(id), sec.isConfigurationSection("furniture"), id + ": furniture exactly for the furniture ids");
        }
    }

    @Test
    void theVestsAreWornInTheChestSlotWithTheirLayers() throws Exception {
        for (String v : List.of("store_vest", "store_vest_manager", "store_vest_night")) {
            ConfigurationSection eq = items().getConfigurationSection(v + ".equipment");
            assertNotNull(eq, v + ".equipment");
            assertEquals("chest", eq.getString("slot"), v);
            assertEquals("economy:" + v, eq.getString("asset"), v);
            Path asset = PACK.resolve("assets/economy/equipment/" + v + ".json");
            assertTrue(Files.readString(asset).contains("\"economy:" + v + "\""), v + ": the humanoid layer's texture");
            assertTrue(Files.exists(PACK.resolve("assets/economy/textures/entity/equipment/humanoid/" + v + ".png")), v);
        }
    }

    @Test
    void theLicencesWearTheirOwnModelsAndTheNpcSkinsExist() {
        // 3차 (§16): the 권리증 items live in items/store_extra.yml, their models are the store's
        YamlConfiguration extra = YamlConfiguration.loadConfiguration(PACK.resolve("items/store_extra.yml").toFile());
        for (String t : List.of("alley", "standard", "large")) {
            String id = "store_license_" + t;
            assertEquals(id, extra.getString(id + ".model"), id);
            assertTrue(Files.exists(PACK.resolve("models/" + id + ".bbmodel")), id);
            assertFalse(items().contains(id), id + " is defined once, in items/store_extra.yml");
        }
        for (String k : List.of("hq_inspector", "night_regular"))
            assertTrue(Files.exists(PACK.resolve("assets/economy/textures/entity/bus/" + k + ".png")), k + " skin");
    }

    @Test
    void furnitureKeepsTheContractsSizesAndSeats() {
        YamlConfiguration y = items();
        ContentPack pack = new ContentPack("economy", "economy", PACK);
        for (Map.Entry<String, int[]> e : furniture().entrySet()) {
            FurnitureSpec spec = ContentModule.furnitureSpec(y.getConfigurationSection(e.getKey() + ".furniture"), pack);
            int[] lo = {99, 99, 99}, hi = {-99, -99, -99};
            for (int[] c : spec.cells()) for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], c[a]); hi[a] = Math.max(hi[a], c[a]); }
            int[] size = {hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1};
            assertArrayEquals(e.getValue(), size, e.getKey());
            assertEquals(size[0] * size[1] * size[2], spec.cells().size(), e.getKey() + ": a full box of cells");
            assertTrue(spec.cells().stream().anyMatch(c -> c[0] == 0 && c[1] == 0 && c[2] == 0), e.getKey() + ": the anchor cell");
        }
        FurnitureSpec bar = ContentModule.furnitureSpec(y.getConfigurationSection("store_eat_bar.furniture"), pack);
        assertEquals(3, bar.seats().size());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(new int[]{i - 1, 0, 0}, bar.seats().get(i).cell(), "시식대 seats from -x");
            assertEquals(180, bar.seats().get(i).facing(), 0.01, "looking out of the window (+z)");
        }
        FurnitureSpec parasol = ContentModule.furnitureSpec(y.getConfigurationSection("store_parasol.furniture"), pack);
        assertEquals(2, parasol.seats().size());
        assertArrayEquals(new int[]{-1, 0, 0}, parasol.seats().get(0).cell());
        assertArrayEquals(new int[]{1, 0, 0}, parasol.seats().get(1).cell());
        for (FurnitureSpec.Seat s : parasol.seats()) {
            // facing (0 = -z, 90 = +x) looks at the table in the middle
            assertEquals(-Math.signum(s.cell()[0]), Math.sin(Math.toRadians(s.facing())), 0.01, Arrays.toString(s.cell()));
        }
        for (FurnitureSpec spec : List.of(bar, parasol)) for (FurnitureSpec.Seat s : spec.seats()) assertTrue(s.height() >= 0.45 && s.height() <= 0.55);
    }

    @Test
    void barriersFromTheModels() throws Exception {
        YamlConfiguration y = items();
        ContentPack pack = new ContentPack("economy", "economy", PACK);
        PackBuilder.Built built = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        for (String id : furniture().keySet()) {
            FurnitureSpec spec = ContentModule.furnitureSpec(y.getConfigurationSection(id + ".furniture"), pack);
            PackBuilder.Model model = built.itemModel("economy:model/" + id);
            assertNotNull(model, id);
            FurnitureShape.Voxels v = FurnitureShape.voxelize(spec.cells(), model.json(), spec.translation(), spec.scale(), List.of(), 0);
            FurnitureShape.Shape shape = FurnitureShape.shape(v, spec.cells(), 0.30, spec.solid());
            for (FurnitureSpec.Seat s : spec.seats()) assertFalse(shape.barrier(s.cell()), id + " seat " + Arrays.toString(s.cell()));
            if (spec.light() != null) assertFalse(shape.barrier(spec.light().cell()), id + ": its light cell");
            if (id.equals("store_counter") || id.equals("store_cig_shelf") || id.startsWith("store_shelf_") || id.startsWith("store_fridge_")
                    || id.equals("store_freezer")) {
                for (int[] c : spec.cells()) if (c[1] == 0) assertTrue(shape.barrier(c), id + ": blocks at " + Arrays.toString(c));
            }
            if (id.equals("store_parasol")) assertTrue(shape.barrier(new int[]{0, 0, 0}), "the parasol's table blocks");
        }
    }
}
