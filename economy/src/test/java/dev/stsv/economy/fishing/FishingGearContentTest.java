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
 * The content of the fisher's update: the 통발 and 가두리 (water furniture, sold and crafted), the 손질대's prepared fish that only
 * the fisher's machine makes and the 조리대 dishes that need them (so cooks buy from fishers), the mill's fish feed (from crops:
 * farmers sell it to fishers), round input counts, and the fisher's job effects.
 */
class FishingGearContentTest {

    static final List<String> PREPARED = List.of("economy:flatfish_hoe", "economy:dried_fish", "economy:saeujeot", "economy:fish_paste");
    static final List<String> DISHES = List.of("economy:mulhoe", "economy:eomuktang", "economy:bossam", "economy:dried_fish_stirfry");
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

    @Test
    void theFishersPreparedFishComeFromTheFishBenchOnly() {
        Map<String, MachineSpec> ms = machines();
        for (String p : PREPARED) {
            assertEquals(1, making(ms.get("fish_bench"), p).size(), p + " on the 손질대");
            for (MachineSpec m : ms.values()) if (!m.id().equals("fish_bench")) assertTrue(making(m, p).isEmpty(), p + " only on the 손질대");
        }
        // they take fish (the 새우젓: the bench's own shrimp meat; the 어묵 반죽: white fish and flour)
        assertTrue(making(ms.get("fish_bench"), "economy:flatfish_hoe").getFirst().inputs().containsKey("economy:flatfish"));
        assertTrue(making(ms.get("fish_bench"), "economy:dried_fish").getFirst().inputs().containsKey("economy:filefish"));
        assertTrue(making(ms.get("fish_bench"), "economy:saeujeot").getFirst().inputs().containsKey("economy:shrimp_meat"));
        assertTrue(making(ms.get("fish_bench"), "economy:fish_paste").getFirst().inputs().containsKey("#economy:white_fish"));
    }

    @Test
    void goodDishesNeedThemSoCooksBuyFromFishers() {
        Map<String, MachineSpec> ms = machines();
        MachineSpec kitchen = ms.get("kitchen");
        for (String d : DISHES) {
            List<MachineSpec.Recipe> rs = making(kitchen, d);
            assertEquals(1, rs.size(), d + " on the 조리대");
            assertTrue(rs.getFirst().inputs().keySet().stream().anyMatch(PREPARED::contains), d + " takes one of the fisher's prepared fish");
        }
        // every prepared fish is wanted by at least one dish
        for (String p : PREPARED) {
            assertTrue(DISHES.stream().anyMatch(d -> making(kitchen, d).getFirst().inputs().containsKey(p)), p + " is used by a dish");
        }
        // the dishes are worth cooking: each sells for more than the 손질감 it takes (shop.yml)
        List<String> problems = new ArrayList<>();
        ShopCatalog shop = ShopCatalog.parse(TrapSpecTest.yaml("shop.yml"), problems);
        for (String d : DISHES) assertTrue(shop.sell().containsKey(d), d + " sells");
        for (String p : PREPARED) assertTrue(shop.sell().containsKey(p), p + " sells");
    }

    @Test
    void theNewRecipesTakeRoundCounts() {
        Map<String, MachineSpec> ms = machines();
        List<String> outs = new ArrayList<>(PREPARED);
        outs.addAll(DISHES);
        outs.add("economy:fish_feed");
        for (MachineSpec m : ms.values()) {
            for (MachineSpec.Recipe r : m.recipes()) {
                if (!outs.contains(r.output())) continue;
                for (var in : r.inputs().entrySet()) assertTrue(ROUND.contains(in.getValue()), r.output() + ": " + in.getKey() + " x" + in.getValue());
                assertTrue(ROUND.contains(r.amount()), r.output() + " amount " + r.amount());
            }
        }
        // the feed is the farmer's: made on the mill from crops only
        List<MachineSpec.Recipe> feed = making(ms.get("mill"), "economy:fish_feed");
        assertEquals(1, feed.size());
        assertEquals(Set.of("economy:soybean", "economy:corn"), feed.getFirst().inputs().keySet());
        // appended: the lanes keep their pinned recipes by number
        for (String mid : List.of("fish_bench", "kitchen", "mill")) {
            List<MachineSpec.Recipe> rs = ms.get(mid).recipes();
            int first = rs.size();
            for (int i = 0; i < rs.size(); i++) if (outs.contains(rs.get(i).output())) first = Math.min(first, i);
            for (int i = first; i < rs.size(); i++) assertTrue(outs.contains(rs.get(i).output()), mid + ": new recipes at the end");
        }
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
        YamlConfiguration recipes = TrapSpecTest.yaml("recipes/jobs.yml");
        assertEquals("economy:fish_trap", recipes.getString("fish_trap.result"));
        assertEquals("economy:fish_pen", recipes.getString("fish_pen.result"));
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
