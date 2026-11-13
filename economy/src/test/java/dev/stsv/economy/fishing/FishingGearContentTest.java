package dev.stsv.economy.fishing;

import dev.stsv.economy.job.JobSpec;
import dev.stsv.economy.job.MachineSpec;
import dev.stsv.economy.shop.ShopCatalog;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The content of the fisher's update: the 통발 and 가두리 (water furniture, sold and crafted), the 손질대's tier processing (2026-11:
 * any fish of a rarity becomes that rarity's 생선살, the only fish goods the cooks' dishes take, so cooks buy from fishers), the
 * mill's fish feed (from crops: farmers sell it to fishers), round input counts, and the fisher's job effects.
 */
class FishingGearContentTest {

    /** 흔함 -> 생선살, 보통 -> 고급 생선살, 희귀 -> 특선 생선살, 전설 -> 진귀한 생선살, and the machine tier each opens at. */
    static final Map<String, String> TIER = Map.of("common", "economy:fish_meat", "uncommon", "economy:fine_fish_meat",
            "rare", "economy:prime_fish_meat", "legend", "economy:treasure_fish_meat");
    static final Map<String, Integer> OPENS = Map.of("common", 0, "uncommon", 0, "rare", 1, "legend", 2);
    /** The goods the 손질대 made before the tier processing: kept as items the shop still buys (shop.yml legacy), made by nothing. */
    static final List<String> LEGACY = List.of("economy:cod_fillet", "economy:salmon_fillet", "economy:dried_pollack", "economy:prepared_puffer",
            "economy:smoked_salmon", "economy:seabream_sashimi", "economy:sukhoe", "economy:tuna_sashimi", "economy:fishcake", "economy:caviar",
            "economy:seafood_feast", "economy:shrimp_meat", "economy:gwamegi", "economy:gulbi", "economy:hongeo", "economy:jeotgal",
            "economy:salted_mackerel", "economy:fish_sauce", "economy:flatfish_hoe", "economy:dried_fish", "economy:saeujeot", "economy:fish_paste");
    static final Set<Integer> ROUND = Set.of(1, 2, 4, 8, 16, 32, 64);

    static Map<String, MachineSpec> machines() {
        List<String> problems = new ArrayList<>();
        Map<String, MachineSpec> m = MachineSpec.parse(TrapSpecTest.yaml("machines.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return m;
    }

    static List<MachineSpec.Recipe> making(MachineSpec m, String out) {
        return m.recipes().stream().filter(r -> r.output().equals(out)).toList();
    }

    /** Each fish group's rarity (its members are all of one rarity: RulesTest). */
    static Map<String, String> groupRarity() {
        YamlConfiguration y = TrapSpecTest.yaml("machines.yml");
        FishSpec f = FishSpec.parse(TrapSpecTest.yaml("fish.yml"), new ArrayList<>());
        Map<String, String> byItem = new java.util.HashMap<>();
        f.species().values().forEach(sp -> byItem.put(sp.item(), sp.rarity()));
        byItem.put("minecraft:tropical_fish", "common");
        Map<String, String> out = new java.util.HashMap<>();
        for (var g : MachineSpec.groups(y, new ArrayList<>()).values()) {
            String r = byItem.get(g.items().getFirst());
            if (r != null) out.put("#" + g.id(), r);
        }
        return out;
    }

    @Test
    void theFishBenchProcessesAnyFishByRarity() {
        Map<String, MachineSpec> ms = machines();
        Map<String, String> rarity = groupRarity();
        MachineSpec bench = ms.get("fish_bench");
        for (MachineSpec.Recipe r : bench.recipes()) {
            if (r.output().equals("economy:polished_pearl")) {
                assertEquals(Map.of("economy:pearl", 1), r.inputs(), "연마 진주 stays as it was");
                continue;
            }
            assertEquals(1, r.inputs().size(), r.output() + ": one fish group");
            String g = r.inputs().keySet().iterator().next();
            assertTrue(rarity.containsKey(g), r.output() + " takes a fish group: " + g);
            assertEquals(TIER.get(rarity.get(g)), r.output(), g + " makes its rarity's 생선살");
            assertEquals(OPENS.get(rarity.get(g)), r.tier(), g + " opens at its rarity's tier");
        }
        // the tier items come from the 손질대 only; the old goods from nothing
        for (String t : TIER.values()) {
            assertFalse(making(bench, t).isEmpty(), t + " on the 손질대");
            for (MachineSpec m : ms.values()) if (!m.id().equals("fish_bench")) assertTrue(making(m, t).isEmpty(), t + " only on the 손질대");
        }
        for (String old : LEGACY) for (MachineSpec m : ms.values()) assertTrue(making(m, old).isEmpty(), old + " is not made any more");
    }

    @Test
    void cooksCookTheTierItemsAndTheOldGoodsStillSell() {
        Map<String, MachineSpec> ms = machines();
        Map<String, String> rarity = groupRarity();
        // no machine but the 손질대 takes a fish or a fish group; the kitchen's fish dishes take the tier items, each used somewhere
        Set<String> fishGroups = rarity.keySet();
        FishSpec f = FishSpec.parse(TrapSpecTest.yaml("fish.yml"), new ArrayList<>());
        Set<String> fish = new java.util.HashSet<>();
        f.species().values().forEach(sp -> fish.add(sp.item()));
        for (MachineSpec m : ms.values()) {
            if (m.id().equals("fish_bench")) continue;
            for (MachineSpec.Recipe r : m.recipes()) for (String in : r.inputs().keySet()) {
                assertFalse(fishGroups.contains(in) || fish.contains(in), m.id() + " " + r.output() + " takes " + in);
                assertFalse(LEGACY.contains(in), m.id() + " " + r.output() + " takes the old " + in);
            }
        }
        for (String t : TIER.values()) {
            assertTrue(ms.get("kitchen").recipes().stream().anyMatch(r -> r.inputs().containsKey(t)), t + " is cooked");
        }
        // the shop buys the tier items in their tabs, and the old goods only as 예전 물건 (no tab, no codex)
        List<String> problems = new ArrayList<>();
        ShopCatalog shop = ShopCatalog.parse(TrapSpecTest.yaml("shop.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        Set<String> tabbed = new java.util.HashSet<>();
        shop.sellCategories().forEach(c -> tabbed.addAll(c.items()));
        for (String t : TIER.values()) assertTrue(tabbed.contains(t), t + " sells in a tab");
        for (String old : LEGACY) {
            assertTrue(shop.sell().containsKey(old), old + " still sells");
            assertFalse(tabbed.contains(old), old + " is in no tab (예전 물건)");
        }
    }

    @Test
    void theNewRecipesTakeRoundCounts() {
        Map<String, MachineSpec> ms = machines();
        Set<String> tier = Set.copyOf(TIER.values());
        for (MachineSpec m : ms.values()) {
            for (MachineSpec.Recipe r : m.recipes()) {
                boolean fishy = tier.contains(r.output()) || r.inputs().keySet().stream().anyMatch(tier::contains)
                        || r.output().equals("economy:fish_feed");
                if (!fishy) continue;
                for (var in : r.inputs().entrySet()) {
                    // crops keep the counts the crop rework set (balance2): only the fish and the tier items are round
                    if (in.getKey().startsWith("#economy:fish_") || tier.contains(in.getKey()) || r.output().equals("economy:fish_feed")) {
                        assertTrue(ROUND.contains(in.getValue()), r.output() + ": " + in.getKey() + " x" + in.getValue());
                    }
                }
            }
        }
        // the feed is the farmer's: made on the mill from crops only, appended (the lanes keep their pinned recipes by number)
        List<MachineSpec.Recipe> feed = making(ms.get("mill"), "economy:fish_feed");
        assertEquals(1, feed.size());
        assertEquals(Set.of("economy:soybean", "economy:corn"), feed.getFirst().inputs().keySet());
        assertEquals("economy:fish_feed", ms.get("mill").recipes().getLast().output());
    }

    @Test
    void trapsAndPensAreWaterFurnitureSoldAndCrafted() {
        YamlConfiguration items = TrapSpecTest.yaml("items/jobs.yml");
        for (String[] t : new String[][]{{"fish_trap", TrapModule.TRAP, "1"}, {"fish_pen", TrapModule.PEN, "4"}}) {
            ConfigurationSection s = items.getConfigurationSection(t[0]);
            assertNotNull(s, t[0]);
            assertEquals(t[1], s.getString("furniture.behavior"));
            assertFalse(s.getBoolean("furniture.solid", true), t[0] + ": not solid, the water stays");
            assertEquals(Integer.parseInt(t[2]), s.getList("furniture.cells").size());
            assertTrue(Files.exists(TrapSpecTest.PACK.resolve("models/" + s.getString("model") + ".bbmodel")), t[0] + " model");
            assertTrue(Files.exists(TrapSpecTest.PACK.resolve("textures/" + s.getString("icon") + ".png")), t[0] + " icon");
        }
        List<String> problems = new ArrayList<>();
        ShopCatalog shop = ShopCatalog.parse(TrapSpecTest.yaml("shop.yml"), problems);
        long trap = price(shop, "economy:fish_trap"), pen = price(shop, "economy:fish_pen");
        assertTrue(trap > 0 && pen > 0, "both in the shop");
        assertTrue(pen >= 5 * trap, "the pen is the bigger investment");
        assertFalse(shop.sell().containsKey("economy:fish_trap") || shop.sell().containsKey("economy:fish_pen"), "not bought back");
        // only from the shop: no crafting recipe makes them (nor a machine)
        YamlConfiguration recipes = TrapSpecTest.yaml("recipes/jobs.yml");
        for (String k : recipes.getKeys(false)) {
            String result = recipes.isConfigurationSection(k + ".result") ? recipes.getString(k + ".result.item") : recipes.getString(k + ".result");
            assertFalse(List.of("economy:fish_trap", "economy:fish_pen").contains(result), "recipe " + k);
        }
    }

    static long price(ShopCatalog shop, String id) {
        return shop.goods().stream().filter(g -> g.id().equals(id)).mapToLong(ShopCatalog.Good::price).findFirst().orElse(0);
    }

    @Test
    void theFisherHasTheTrapAndBoatEffects() {
        List<String> problems = new ArrayList<>();
        JobSpec j = JobSpec.parse(TrapSpecTest.yaml("jobs.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        JobSpec.Job f = j.jobs().get("fisher");
        for (String k : List.of("trap-speed", "trap-uncommon", "trap-rare", "boat-rare")) {
            assertTrue(f.range(k, 30, 30) > f.range(k, 1, 30) && f.range(k, 1, 30) > 0, k + " grows with the level");
            for (JobSpec.Job o : j.jobs().values()) if (o != f) assertEquals(0, o.range(k, 30, 30), o.id() + " has no " + k);
        }
        assertTrue(f.xp("trap", "economy:mackerel") > 0 && f.xp("trap", "economy:mackerel") < f.xp("catch", "economy:mackerel"),
                "a trap's fish is worth less XP than one on the rod");
    }
}
