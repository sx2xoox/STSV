package dev.stsv.economy.dock;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import dev.stsv.economy.job.MachineSpec;
import dev.stsv.economy.shop.ShopCatalog;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 하역장 and 적재 무게 against the real content: what a dock buys (finished goods) and for how much (the base x the global
 * docks.price-ratio, 90 % by default, no curve; a dock's own price: TerminalTest), the weights (every finished good about 500 G per weight unit) and what a payload carries, the dock's layout and its zone in the world, the
 * docks and their sales kept in the database, the route statistics of the scout.
 */
class DockTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    @TempDir
    Path dir;

    static YamlConfiguration yaml(String f) {
        return YamlConfiguration.loadConfiguration(PACK.resolve(f).toFile());
    }

    static ShopCatalog shop() {
        List<String> problems = new ArrayList<>();
        ShopCatalog c = ShopCatalog.parse(yaml("shop.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return c;
    }

    static Map<String, MachineSpec> machines() {
        List<String> problems = new ArrayList<>();
        Map<String, MachineSpec> m = MachineSpec.parse(yaml("machines.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return m;
    }

    static DockGoods goods() {
        return DockGoods.of(machines(), shop(), config().getDouble("docks.price-ratio"));
    }

    static YamlConfiguration config() {
        try (var in = DockTest.class.getResourceAsStream("/config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static WeightTable weights() {
        List<String> problems = new ArrayList<>();
        WeightTable w = WeightTable.parse(yaml("weights.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return w.withGoods(goods());   // as DockModule.load
    }

    // ------------------------------------------------------------------------------------------
    // What a dock buys and for how much
    // ------------------------------------------------------------------------------------------

    @Test
    void theOwnersNumbersAreTheDefaults() {
        YamlConfiguration c = config();
        assertEquals(6000, c.getDouble("docks.min-distance"), "the owner's 6,000 blocks");
        assertEquals(0.9, c.getDouble("docks.price-ratio"), 1e-9);
        assertEquals(1.2, c.getDouble("roads.speed-bonus"), 1e-9);
        for (String k : List.of("docks", "roads")) {
            for (String key : c.getConfigurationSection(k).getKeys(true)) {
                String last = key.substring(key.lastIndexOf('.') + 1);
                assertFalse(List.of("on", "off", "yes", "no").contains(last), "YAML key " + k + "." + key);
            }
        }
    }

    @Test
    void docksBuyFinishedGoodsOnly() {
        ShopCatalog shop = shop();
        Map<String, MachineSpec> machines = machines();
        Set<String> finished = DockGoods.finished(machines, shop);
        Set<String> outputs = new HashSet<>();
        for (MachineSpec m : machines.values()) for (MachineSpec.Recipe r : m.recipes()) outputs.add(r.output());
        // exactly: recipe outputs the shop buys
        for (String id : shop.sell().keySet()) assertEquals(outputs.contains(id), finished.contains(id), id);
        assertTrue(finished.size() >= 140, "every job's products: " + finished.size());
        for (String id : List.of("economy:kimchi", "economy:ruby_ring", "economy:jeweled_crown", "economy:wine", "economy:oak_barrel",
                "economy:steel_ingot", "economy:compressed_stone", "economy:hanjeongsik", "economy:treasure_fish_meat"))
            assertTrue(finished.contains(id), id);
        // the 손질대's old goods (shop.yml legacy: still sold to the shop, made by nothing) are not finished goods any more
        for (String id : List.of("economy:seafood_feast", "economy:caviar", "economy:cod_fillet")) {
            assertTrue(shop.sell().containsKey(id), id);
            assertFalse(finished.contains(id), id);
        }
        // raw materials: ore, crops, fish, logs, mob drops, finds and specials are not finished goods
        for (String id : List.of("minecraft:diamond", "minecraft:iron_ingot", "minecraft:wheat", "minecraft:oak_log", "minecraft:bone",
                "economy:rough_ruby", "economy:tomato", "economy:mackerel", "economy:pearl", "minecraft:cod"))
            assertFalse(finished.contains(id), id);
        // every shop category of products has some, the raw ones none
        Map<String, ShopCatalog.Category> cats = new java.util.LinkedHashMap<>();
        for (ShopCatalog.Category c : shop.sellCategories()) cats.put(c.id(), c);
        for (String raw : List.of("minerals", "farm", "fish")) for (String id : cats.get(raw).items()) assertFalse(finished.contains(id), raw + " " + id);
        for (String made : List.of("crafts", "foodstuff", "dishes")) assertTrue(cats.get(made).items().stream().anyMatch(finished::contains), made);
    }

    @Test
    void theDockPaysNinetyPercentOfTheBaseWhateverWasSold() {
        ShopCatalog shop = shop();
        DockGoods g = goods();
        double kimchi = shop.sell().get("economy:kimchi").base(), ring = shop.sell().get("economy:ruby_ring").base();
        assertEquals(kimchi * 0.9, g.unit("economy:kimchi"), 1e-9);
        assertEquals(0, g.unit("minecraft:diamond"), "not bought");
        List<DockGoods.Slot> bed = List.of(
                new DockGoods.Slot(0, "economy:kimchi", 64, true),
                new DockGoods.Slot(3, "economy:ruby_ring", 10, true),
                new DockGoods.Slot(4, "economy:kimchi", 36, true),
                new DockGoods.Slot(5, "minecraft:diamond", 32, true),        // raw: stays
                new DockGoods.Slot(6, "economy:ruby_ring", 5, false));       // bought from the shop or renamed: stays
        DockGoods.Plan p = g.plan(bed);
        assertEquals(Set.of(0, 3, 4), p.slots());
        assertEquals(2, p.kept());
        assertEquals(2, p.lines().size());
        assertEquals(100, p.lines().getFirst().count());
        assertEquals((long) Math.floor(100 * kimchi * 0.9 + 10 * ring * 0.9 + 1e-6), p.total());
        // no curve: the same load again (and ten times as much) pays the same per item
        assertEquals(p.total(), g.plan(bed).total());
        List<DockGoods.Slot> big = new ArrayList<>();
        for (int i = 0; i < 1000; i++) big.add(new DockGoods.Slot(i, "economy:kimchi", 64, true));
        assertEquals((long) Math.floor(64_000 * kimchi * 0.9 + 1e-6), g.plan(big).total(), "64,000 kimchi at the same price");
        assertTrue(g.plan(List.of(new DockGoods.Slot(0, "minecraft:diamond", 64, true))).empty());
    }

    @Test
    void aDockSaleNeverTouchesTheShopsRecordsOrTax() throws Exception {
        // the shop curve (ShopStore.record), the market price (events, notices), the income tax and the stock market's
        // activity are moved only by ShopModule.sell: the dock pays straight from the base price and calls none of them
        String src = Files.readString(Path.of("src/main/java/dev/stsv/economy/dock/DockModule.java"));
        for (String touch : List.of("shop().sell(", "shop().store()", "recordSale", "incomeTax", "addIncome", "multiplier(", "marketPrice("))
            assertFalse(src.contains(touch), "the dock must not call " + touch);
        assertTrue(src.contains("EconomyStore.Kind.FAUCET, \"dock_sell\""), "paid as its own kind of income");
        String goods = Files.readString(Path.of("src/main/java/dev/stsv/economy/dock/DockGoods.java"));
        assertFalse(goods.contains("PriceModel"), "no curve");
    }

    // ------------------------------------------------------------------------------------------
    // 적재 무게
    // ------------------------------------------------------------------------------------------

    @Test
    void everyFinishedGoodIsWorthAbout500GPerWeight() {
        WeightTable w = weights();
        DockGoods g = goods();
        Set<String> ours = new HashSet<>();
        try (Stream<Path> s = Files.list(PACK.resolve("items"))) {
            for (Path f : s.toList()) for (String k : YamlConfiguration.loadConfiguration(f.toFile()).getKeys(false)) ours.add("economy:" + k);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // own lines (items): real items, finished goods only (anything else weighs raw), and none by default
        for (String id : w.items().keySet()) {
            if (id.startsWith("economy:")) assertTrue(ours.contains(id), "weights.yml: " + id);
            else assertNotNull(Material.matchMaterial(id), "weights.yml: " + id);
            assertTrue(g.buys(id), "weights.yml lists " + id + ", which no dock buys: it is raw");
        }
        assertTrue(w.items().isEmpty(), "every finished good weighs by its price: " + w.items());
        assertEquals(500, w.valuePerWeight(), 1e-9, "the owner's 500 G per weight unit");
        // every finished good: what one fetches at the default dock price / its weight = 500 G (+-1 %), but the clamped
        int clamped = 0;
        double lo = Double.MAX_VALUE, hi = 0;
        for (String id : g.finished()) {
            double unit = w.weight(id);
            assertTrue(unit >= WeightTable.MIN, id);
            assertFalse(w.isRaw(id), "a finished good has a real weight: " + id);
            if (unit <= WeightTable.MIN + 1e-12) {
                clamped++;
                assertTrue(g.unit(id) / unit <= 500 * 1.01, id + ": clamped, so worth less per weight, never more");
                continue;
            }
            double perWeight = g.unit(id) / unit;
            assertEquals(500, perWeight, 5, id + ": " + g.unit(id) + " G / " + unit);
            lo = Math.min(lo, perWeight);
            hi = Math.max(hi, perWeight);
        }
        assertTrue(clamped <= 3, "only a very cheap good is clamped: " + clamped);
        System.out.println("[적재 무게] 완제품 " + g.finished().size() + "종, 무게 1 에 " + lo + " ~ " + hi + " G, 최소 무게에 걸린 것 " + clamped);
        // the default ratio (docks.price-ratio 0.9), not a dock's own: base x 0.9 / 500
        assertEquals(0.9, g.ratio(), 1e-9);
        assertEquals(179 * 0.9 / 500, w.weight("economy:polished_pearl"), 1e-9, "연마 진주 (was 0.2: 805 G per weight)");
        assertEquals(68 * 0.9 / 500, w.weight("economy:stone_lantern"), 1e-9, "석등 (was 8: 8 G per weight)");
        assertEquals(231 * 0.9 / 500, w.weight("economy:ruby_ring"), 1e-9, "루비 반지");
        assertEquals(77 * 0.9 / 500, w.weight("economy:kimchi"), 1e-9, "김치");
        assertEquals(151 * 0.9 / 500, w.weight("economy:oak_barrel"), 1e-9, "참나무통");
        assertEquals(2836 * 0.9 / 500, w.weight("economy:jeweled_crown"), 1e-9, "보석 왕관: dear, so heavy");
        assertTrue(w.weight("economy:flour") > 0 && !w.isRaw("economy:flour"), "flour: a recipe input, but the dock buys it");
        assertTrue(w.weight("economy:cut_diamond") > 0 && !w.isRaw("economy:cut_diamond"), "an intermediate the dock buys");
        // a dock paying more (/관리자 하역장 가격 110) pays more per weight; the weights stay as they are (one table for every dock)
        assertEquals(500 * 1.1 / 0.9, g.unit("economy:kimchi", 1.1) / w.weight("economy:kimchi"), 1e-6);
        // raw: everything a dock does not buy, a stack of 64 = 1
        assertEquals(1.0 / 64, w.raw(), 1e-12);
        for (String id : List.of("minecraft:wheat", "minecraft:wheat_seeds", "economy:tomato", "economy:tomato_seeds", "minecraft:potato",
                "minecraft:cod", "economy:mackerel", "economy:marlin", "economy:bluefin_tuna", "minecraft:iron_ore", "minecraft:raw_iron",
                "minecraft:iron_ingot", "minecraft:diamond", "minecraft:emerald", "economy:rough_ruby", "economy:pearl", "minecraft:oak_log",
                "minecraft:oak_planks", "minecraft:stone", "minecraft:cobblestone", "minecraft:dirt", "minecraft:bone", "minecraft:string",
                "minecraft:iron_pickaxe", "minecraft:diamond_chestplate", "minecraft:bread", "minecraft:shulker_box")) {
            assertFalse(g.buys(id), id);
            assertTrue(w.isRaw(id), id);
            assertEquals(1.0 / 64, w.weight(id), 1e-12, id);
        }
        assertEquals(1, 64 * w.weight("minecraft:wheat"), 1e-9, "a stack of wheat weighs 1");
    }

    /** Every vehicle with a bed in the core's content: id -> {payload, slots} (a train: all its trailers' slots). */
    static Map<String, double[]> vehicles() {
        Map<String, double[]> out = new java.util.LinkedHashMap<>();
        Path items = PACK.getParent().getParent().getParent().resolve("content/stsv/items");
        for (String file : List.of("vehicles.yml", "vehicles_road.yml", "vehicles_farm.yml", "vehicles_misc.yml")) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(items.resolve(file).toFile());
            for (String id : y.getKeys(false)) {
                var v = y.getConfigurationSection(id + ".vehicle");
                var jige = y.getConfigurationSection(id + ".jige");
                if (jige != null) {
                    out.put(id, new double[]{jige.getDouble("payload"), jige.getInt("slots")});
                    continue;
                }
                if (v == null) continue;
                int slots = v.getInt("cargo.slots");
                for (Map<?, ?> t : v.getMapList("train.trailers"))
                    if (t.get("cargo") instanceof Map<?, ?> c && c.get("slots") instanceof Number n) slots += n.intValue();
                if (slots == 0) continue;
                double payload = v.contains("cargo.payload") ? v.getDouble("cargo.payload") : v.getDouble("payload");
                assertTrue(payload > 0, id + ": payload");
                out.put(id, new double[]{payload, slots});
            }
        }
        return out;
    }

    /** How many stacks of 64 of this item a bed of this payload and these slots takes. */
    static double stacks(String id, double[] bed, WeightTable w) {
        return Math.min(bed[1], Math.floor(bed[0] / (64 * w.weight(id)) + 1e-9));
    }

    @Test
    void rawHarvestsFillTheSlotsNotThePayload() {
        WeightTable w = weights();
        Map<String, double[]> all = vehicles();
        double[] cart = all.get("hand_cart"), tiller = all.get("power_tiller"), truck = all.get("light_truck");
        assertEquals(150, cart[0], 1e-9);
        assertEquals(135, cart[1], 1e-9);
        assertEquals(500, tiller[0], 1e-9);
        assertEquals(360, tiller[1], 1e-9);
        assertEquals(1000, truck[0], 1e-9);
        assertEquals(450, truck[1], 1e-9);
        for (String crop : List.of("minecraft:wheat", "minecraft:potato", "economy:tomato", "minecraft:cod", "minecraft:oak_log", "minecraft:cobblestone")) {
            // the hand cart full of 64-stacks: every slot used, weight to spare
            assertEquals(135, stacks(crop, cart, w), crop + ": a cart takes a full stack in each of its 135 slots");
            assertTrue(135 * 64 * w.weight(crop) <= 150, crop);
            assertEquals(360, stacks(crop, tiller, w), crop + " on a tiller");
            assertEquals(450, stacks(crop, truck, w), crop + " on a truck");
        }
        // every vehicle is slot-limited for raw hauls, but the two smallest (짐자전거 20, 지게 30: one stack per 1)
        for (Map.Entry<String, double[]> e : all.entrySet()) {
            double[] bed = e.getValue();
            boolean small = e.getKey().equals("cargo_bicycle") || e.getKey().equals("jige");
            assertEquals(!small, stacks("minecraft:wheat", bed, w) == bed[1], e.getKey() + " " + bed[0] + " / " + bed[1]);
        }
        assertEquals(30, stacks("minecraft:wheat", all.get("jige"), w), "a 지게: 30 stacks of 54 slots");
        assertEquals(20, stacks("minecraft:wheat", all.get("cargo_bicycle"), w));
        assertEquals(135 * 64, stacks("minecraft:wheat", cart, w) * 64, 1e-9);
        // a shulker box counts what it holds at its own weight (DockModule.weigh): 27 stacks of wheat + the box itself
        assertEquals(27 + 1.0 / 64, w.weight("minecraft:shulker_box") + 27 * 64 * w.weight("minecraft:wheat"), 1e-9);
    }

    record Haul(String id, int count, long value, double weight) {}

    /** The most valuable load of one good a vehicle of this payload and these slots carries (stacks of 64). */
    static Haul best(String id, double payload, int slots, DockGoods g, WeightTable w) {
        double unit = w.weight(id);
        int n = (int) Math.min(Math.floor(payload / unit + 1e-9), slots * 64L);
        return new Haul(id, n, (long) Math.floor(n * g.unit(id)), n * unit);
    }

    @Test
    void aPayloadCarriesAtMostAbout500GPerWeightOfAnyFinishedGood() {
        DockGoods g = goods();
        WeightTable w = weights();
        Map<String, double[]> all = vehicles();
        // every vehicle, every finished good: a full load is worth at most payload x 500 G (+1 %)
        for (Map.Entry<String, double[]> e : all.entrySet()) {
            double payload = e.getValue()[0];
            int slots = (int) e.getValue()[1];
            long most = 0;
            for (String id : g.finished()) {
                Haul h = best(id, payload, slots, g, w);
                assertTrue(h.weight() <= payload + 1e-6, e.getKey() + " " + h);
                assertTrue(h.value() <= payload * 500 * 1.01, e.getKey() + " " + h);
                most = Math.max(most, h.value());
            }
            // and a load of a good that is not slot-limited comes close to it
            assertTrue(most >= payload * 500 * 0.98, e.getKey() + ": " + most + " of " + payload * 500);
        }
        // 연마 진주 (0.32) and 석등 (0.12): before 805 G and 8 G per weight, now the same on a 배달 오토바이 (60, 54 slots)
        Haul scooterPearls = best("economy:polished_pearl", 60, 54, g, w), scooterLanterns = best("economy:stone_lantern", 60, 54, g, w);
        assertEquals(186, scooterPearls.count(), "60 / 0.3222");
        assertEquals(490, scooterLanterns.count(), "60 / 0.1224");
        for (Haul h : List.of(scooterPearls, scooterLanterns)) assertEquals(30_000, h.value(), 300, h.toString());
        // 1톤 트럭 (1000, 450 slots) and 탑차 (2500, 720): 500,000 G and 1,250,000 G of anything dear enough
        Haul truckBarrels = best("economy:oak_barrel", 1000, 450, g, w), boxKimchi = best("economy:kimchi", 2500, 720, g, w);
        assertEquals(500_000, truckBarrels.value(), 5_000, truckBarrels.toString());
        assertEquals(1_250_000, boxKimchi.value(), 12_500, boxKimchi.toString());
        // a very cheap good may fill the slots before the payload: 씨앗 기름 (8 G, 0.0144) in a 리어카 (150, 135 slots)
        Haul cartOil = best("economy:seed_oil", 150, 135, g, w);
        assertEquals(135 * 64, cartOil.count(), "slot-limited");
        assertTrue(cartOil.weight() < 150 && cartOil.value() < 150 * 500, cartOil.toString());
        System.out.println("[적재 무게] 오토바이: 연마 진주 " + scooterPearls + " / 석등 " + scooterLanterns
                + " · 1톤: 참나무통 " + truckBarrels + " · 탑차: 김치 " + boxKimchi + " · 리어카: 씨앗 기름 " + cartOil);
    }

    @Test
    void weightsFollowThePrice() {
        List<String> problems = new ArrayList<>();
        YamlConfiguration y = new YamlConfiguration();
        y.set("finished-value-per-weight", 250);
        y.set("items.minecraft:gold_ingot", 0.9);
        WeightTable parsed = WeightTable.parse(y, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(WeightTable.RAW, parsed.weight("iron_ingot"), 1e-12, "no goods given: raw");
        assertEquals(0.9, parsed.weight("gold_ingot"), 1e-9, "its own line over raw");
        // as if these were finished goods, each fetching this much at the default dock price
        WeightTable w = parsed.withGoods(Map.of("iron_ingot", 100.0, "minecraft:gold_ingot", 1000.0, "economy:tofu", 25.0, "economy:dust", 0.0));
        assertEquals(WeightTable.RAW, w.weight("minecraft:wheat"), 1e-12, "not a good: raw");
        assertEquals(0.4, w.weight("minecraft:iron_ingot"), 1e-9, "100 G / 250");
        assertEquals(0.1, w.weight("economy:tofu"), 1e-9);
        assertEquals(0.9, w.weight("minecraft:gold_ingot"), 1e-9, "its own line wins over the price");
        assertTrue(w.overridden("gold_ingot") && !w.overridden("iron_ingot"));
        assertEquals(WeightTable.MIN, w.weight("economy:dust"), 1e-12, "nothing weighs 0");
        assertFalse(w.isRaw("economy:dust"));
        assertEquals(500, WeightTable.parse(new YamlConfiguration(), problems).valuePerWeight(), 1e-9, "500 G when weights.yml does not say");
        y.set("raw", 0.02);
        assertEquals(0.02, WeightTable.parse(y, problems).weight("minecraft:wheat"), 1e-12, "raw as weights.yml says");
        assertTrue(problems.isEmpty(), problems.toString());
        y.set("raw", 0);
        WeightTable.parse(y, problems);
        assertFalse(problems.isEmpty(), "raw must be over 0");
        problems.clear();
        y.set("raw", null);
        y.set("finished-value-per-weight", 0);
        assertEquals(500, WeightTable.parse(y, problems).valuePerWeight(), 1e-9);
        assertFalse(problems.isEmpty(), "finished-value-per-weight must be over 0");
        problems.clear();
        y.set("finished-value-per-weight", null);
        y.set("categories.gems.weight", 0.2);
        WeightTable.parse(y, problems);
        assertEquals(1, problems.size(), "the old categories are reported, not used: " + problems);
    }

    // ------------------------------------------------------------------------------------------
    // The structure and its zone
    // ------------------------------------------------------------------------------------------

    @Test
    void theLayoutStaysInItsBoundsAndKeepsTheZoneClear() throws Exception {
        for (DockLayout.Kind kind : DockLayout.Kind.docks()) {
            DockLayout.Layout l = DockLayout.of(kind, "서해");
            DockLayout.Box b = l.bounds();
            for (DockLayout.Block x : l.blocks()) {
                assertTrue(b.contains(x.x(), x.y(), x.z()), kind + " block out of bounds " + x);
                assertTrue(x.data().startsWith("minecraft:"), x.data());
                assertNotNull(Material.matchMaterial(x.data()), x.data());
            }
            for (DockLayout.Column c : l.columns()) assertTrue(b.contains(c.x(), c.fromY(), c.z()) && c.fromY() - c.depth() >= b.minY(), "column " + c);
            for (DockLayout.Prop p : l.props()) assertTrue(b.contains(p.x(), p.y(), p.z()), "prop " + p);
            for (DockLayout.Box c : l.clear()) assertTrue(b.contains(c.minX(), c.minY(), c.minZ()) && b.contains(c.maxX(), c.maxY(), c.maxZ()), "clear " + c);
            // the zone: inside the pad, nothing standing in it (only paint and lights in its floor), the crane's hook high above
            DockLayout.Box z = DockLayout.ZONE;
            for (DockLayout.Block x : l.blocks()) {
                if (x.y() >= 0 && x.y() <= 5 && x.x() >= z.minX() && x.x() <= z.maxX() && x.z() >= z.minZ() && x.z() <= z.maxZ())
                    throw new AssertionError(kind + ": something stands in the unloading zone: " + x);
            }
            assertTrue(l.blocks().stream().anyMatch(x -> x.y() == -1 && x.x() == z.minX() && x.z() == z.minZ() + 2 && x.data().equals("minecraft:yellow_concrete")),
                    "a painted yellow border");
            for (DockLayout.Prop p : l.props()) if (p.model().equals("dock_crane_hook")) assertTrue(p.y() >= 6, "the hook over the trucks");
            // the counter is right beside the zone and is a real block of the layout
            DockLayout.Block c = l.counter();
            assertTrue(c.x() >= z.minX() - 2 && c.x() <= z.maxX() + 2 && c.z() >= z.minZ() - 1 && c.z() <= z.maxZ(), "counter by the zone");
            assertTrue(l.blocks().stream().anyMatch(x -> x.x() == c.x() && x.y() == c.y() && x.z() == c.z() && x.data().equals(c.data())));
            assertTrue(l.blocks().stream().anyMatch(x -> x.data().equals("minecraft:lantern")), "lights");
            assertTrue(l.props().stream().anyMatch(p -> p.model().equals("dock_forklift")));
            assertTrue(l.props().stream().anyMatch(p -> p.model().startsWith("dock_container")));
            assertEquals(1, l.signs().size());
            for (DockLayout.Prop p : l.props()) assertTrue(Files.exists(PACK.resolve("models/" + p.model() + ".bbmodel")), "models/" + p.model());
            if (kind == DockLayout.Kind.PORT) {
                assertEquals(3, l.zones().size(), "the pad's zone and the water on both sides of the pier");
                assertTrue(l.blocks().stream().anyMatch(x -> x.z() > DockLayout.DEPTH && x.data().equals("minecraft:spruce_planks")), "a pier");
                for (DockLayout.Box m : List.of(DockLayout.MOORING_LEFT, DockLayout.MOORING_RIGHT))
                    for (DockLayout.Block x : l.blocks()) assertFalse(m.contains(x.x(), x.y(), x.z()) && x.y() >= -1, "mooring water is clear: " + x);
            } else {
                assertEquals(1, l.zones().size());
                for (DockLayout.Block x : l.blocks()) assertTrue(x.z() <= DockLayout.DEPTH, "a land dock has no pier");
            }
            export(kind, l);
        }
    }

    /** The layout for the preview (tools/assetgen/dock_preview.py → docs/previews/docks.png). */
    static void export(DockLayout.Kind kind, DockLayout.Layout l) throws Exception {
        StringBuilder b = new StringBuilder("{\"kind\":\"").append(kind.name()).append("\",\"blocks\":[");
        boolean first = true;
        for (DockLayout.Block x : l.blocks()) {
            if (!first) b.append(',');
            first = false;
            b.append('[').append(x.x()).append(',').append(x.y()).append(',').append(x.z()).append(",\"").append(x.data()).append("\"]");
        }
        b.append("],\"columns\":[");
        first = true;
        for (DockLayout.Column c : l.columns()) {
            if (!first) b.append(',');
            first = false;
            b.append('[').append(c.x()).append(',').append(c.fromY()).append(',').append(c.z()).append(",\"").append(c.data()).append("\"]");
        }
        b.append("],\"props\":[");
        first = true;
        for (DockLayout.Prop p : l.props()) {
            if (!first) b.append(',');
            first = false;
            b.append('[').append(p.x()).append(',').append(p.y()).append(',').append(p.z()).append(",\"").append(p.model()).append("\",")
                    .append(p.scale()).append(',').append(p.yaw()).append(']');
        }
        b.append("],\"signs\":[");
        first = true;
        for (DockLayout.Sign s : l.signs()) {
            if (!first) b.append(',');
            first = false;
            b.append('[').append(s.x()).append(',').append(s.y()).append(',').append(s.z()).append(']');
        }
        b.append("],\"zones\":[");
        first = true;
        for (DockLayout.Box z : l.zones()) {
            if (!first) b.append(',');
            first = false;
            b.append('[').append(z.minX()).append(',').append(z.minZ()).append(',').append(z.maxX()).append(',').append(z.maxZ()).append(']');
        }
        b.append("]}");
        Path out = Path.of("build/docks/" + kind.name().toLowerCase(java.util.Locale.ROOT) + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, b.toString());
    }

    @Test
    void theZoneTurnsWithTheDockAndAnyUnitOfATrainCounts() {
        for (int facing = 0; facing < 4; facing++) {
            Dock d = new Dock("동해", DockLayout.Kind.PORT, "world", 1000, 63, -2000, facing, "", 0);
            double[] c = d.centre();
            assertTrue(d.inZone("world", c[0], c[1] + 0.5, c[2]), "facing " + facing);
            assertFalse(d.inZone("other", c[0], c[1], c[2]));
            double[] gate = d.world(0, 0, -6);
            assertFalse(d.inZone("world", gate[0], gate[1], gate[2]), "the road's end is outside the zone");
            // a tractor with its front outside and its last trailer inside
            assertTrue(d.anyInZone("world", List.of(new double[]{gate[0], gate[1], gate[2]}, new double[]{c[0], c[1], c[2]})));
            // a boat moored beside the pier, on the water (a block under the pad's floor)
            double[] boat = d.world(-8, -1.6, 55);
            assertTrue(d.inZone("world", boat[0], boat[1], boat[2]), "a moored boat, facing " + facing);
            // the counter and the dock's turned blocks
            int[] counter = d.counter();
            int[] again = d.block(-9, 0, 4);
            assertEquals(List.of(again[0], again[1], again[2]), List.of(counter[0], counter[1], counter[2]));
            assertEquals(facing, Dock.facing(Dock.yaw(facing)));
        }
        assertEquals(0, Dock.facing(10));
        assertEquals(1, Dock.facing(95));
        assertEquals(3, Dock.facing(-80));
        assertTrue(Dock.validName("서해_1"));
        assertFalse(Dock.validName("a b"));
        assertFalse(Dock.validName("x".repeat(17)));
        // a land dock has no water zone
        Dock land = new Dock("내륙", DockLayout.Kind.LAND, "world", 0, 64, 0, 0, "", 0);
        double[] boat = land.world(-8, -1.6, 55);
        assertFalse(land.inZone("world", boat[0], boat[1], boat[2]));
    }

    // ------------------------------------------------------------------------------------------
    // Kept in the database
    // ------------------------------------------------------------------------------------------

    @Test
    void docksAndTheirSalesAreKept() throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("d.db"), Logger.getLogger("test"));
        try {
            DockStore s = new DockStore(db);
            s.init();
            Dock a = new Dock("서해", DockLayout.Kind.PORT, "world", 7000, 62, 10, 3, "437,0;437,1", 100);
            s.save(a).join();
            s.save(new Dock("내륙", DockLayout.Kind.LAND, "world", -5000, 70, 5000, 1, "", 200)).join();
            UUID owner = UUID.randomUUID(), seller = UUID.randomUUID();
            s.log(new DockStore.Sale(1000, "서해", 12, owner, seller, "economy:kimchi x100", 6930, 7012.5, 7400)).join();
            s.log(new DockStore.Sale(2000, "내륙", -3, seller, seller, "economy:ruby_ring x10", 2079, 6100, 6200)).join();
            DockStore again = new DockStore(db);
            again.init();
            List<Dock> all = again.all();
            assertEquals(2, all.size());
            assertEquals(a, all.getFirst());
            assertEquals(List.of(new long[]{437, 0}[0], new long[]{437, 1}[1]), List.of(a.claimed().get(0)[0], a.claimed().get(1)[1]));
            assertTrue(again.delete("내륙").join());
            assertEquals(1, again.all().size());
            List<DockStore.Sale> sales = again.sales(null, 10).join();
            assertEquals(2, sales.size());
            assertEquals(2079, sales.getFirst().paid(), "newest first");
            assertEquals(1, again.sales("서해", 10).join().size());
            assertEquals(owner, again.sales("서해", 10).join().getFirst().owner());
        } finally {
            db.close();
        }
    }

    // ------------------------------------------------------------------------------------------
    // The scout's numbers
    // ------------------------------------------------------------------------------------------

    @Test
    void theScoutCountsWaterAndSteepness() {
        int[] h = {64, 64, 70, 70, 50, 50, 50, 64, 64};
        boolean[] w = {false, false, false, true, true, false, true, false, false};
        RouteStats s = RouteStats.of(h, w, 32, new int[]{64, 66, 65}, new boolean[]{false, false, false}, new boolean[]{true, false});
        assertEquals(2, s.crossings());
        assertEquals(96, s.water());
        assertEquals(64, s.longestWater());
        assertEquals(6, s.climb(), "only between two dry samples");
        assertEquals(2, s.siteRange());
        assertTrue(s.shore());
        assertFalse(s.siteWater());
        RouteStats flat = RouteStats.of(new int[]{64, 64, 64}, new boolean[3], 32, new int[]{64}, new boolean[]{false}, new boolean[0]);
        assertTrue(flat.score() < s.score());
    }
}
