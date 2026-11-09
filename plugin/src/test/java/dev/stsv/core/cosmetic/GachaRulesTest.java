package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem.Rarity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 치장 뽑기: weighted pick, pity, the 10-pull guarantee, duplicates, the shipped pool (no legendary, every one has a voucher). */
class GachaRulesTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");

    private static GachaRules rules(int pity) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("price.single", 600);
        y.set("price.multi", 5400);
        y.set("weights.common", 56);
        y.set("weights.rare", 32);
        y.set("weights.epic", 12);
        y.set("pity.pulls", pity);
        List<String> problems = new ArrayList<>();
        GachaRules r = GachaRules.parse(y, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        return r;
    }

    private static final List<GachaRules.Entry> POOL = List.of(
            new GachaRules.Entry("c1", Rarity.COMMON), new GachaRules.Entry("c2", Rarity.COMMON),
            new GachaRules.Entry("r1", Rarity.RARE), new GachaRules.Entry("e1", Rarity.EPIC));

    /** A fixed sequence of "random" numbers, then the last one again. */
    private static java.util.function.DoubleSupplier seq(double... u) {
        int[] i = {0};
        return () -> u[Math.min(i[0]++, u.length - 1)];
    }

    @Test
    void weightedPickFollowsTheWeights() {
        List<Integer> w = List.of(56, 32, 12);
        assertEquals(0, GachaRules.weighted(w, 0.0));
        assertEquals(0, GachaRules.weighted(w, 0.5599));
        assertEquals(1, GachaRules.weighted(w, 0.56));
        assertEquals(1, GachaRules.weighted(w, 0.8799));
        assertEquals(2, GachaRules.weighted(w, 0.88));
        assertEquals(2, GachaRules.weighted(w, 0.99999));
        assertEquals(1, GachaRules.weighted(List.of(0, 5, 0), 0.99), "a weight of 0 never comes up");
        // over many draws, the shares match
        GachaRules r = rules(0);
        Random rnd = new Random(7);
        Map<Rarity, Integer> n = new EnumMap<>(Rarity.class);
        for (int i = 0; i < 20000; i++) {
            Rarity got = r.pull(POOL, 1, 0, Set.of(), rnd::nextDouble).draws().getFirst().entry().rarity();
            n.merge(got, 1, Integer::sum);
        }
        assertEquals(0.56, n.get(Rarity.COMMON) / 20000.0, 0.015);
        assertEquals(0.32, n.get(Rarity.RARE) / 20000.0, 0.015);
        assertEquals(0.12, n.get(Rarity.EPIC) / 20000.0, 0.01);
        assertEquals(0.12, r.chance(Rarity.EPIC, GachaRules.rarities(POOL)), 1e-9);
    }

    @Test
    void pityGivesAnEpicAfterTooManyWithout() {
        GachaRules r = rules(30);
        // always the lowest number: a common every time, until the pity
        GachaRules.Result res = r.pull(POOL, 1, 28, Set.of(), seq(0.0));
        assertEquals(Rarity.COMMON, res.draws().getFirst().entry().rarity());
        assertEquals(29, res.pityAfter());
        res = r.pull(POOL, 1, 29, Set.of(), seq(0.0));
        assertEquals(Rarity.EPIC, res.draws().getFirst().entry().rarity(), "the 30th without an epic is one");
        assertTrue(res.draws().getFirst().pity());
        assertEquals(0, res.pityAfter(), "the counter starts again");
        // an epic by luck resets it too
        assertEquals(0, r.pull(POOL, 1, 12, Set.of(), seq(0.95)).pityAfter());
        // within one 10-pull: the counter carries over from earlier pulls
        res = r.pull(POOL, 10, 25, Set.of(), seq(0.0));
        assertEquals(Rarity.EPIC, res.draws().get(4).entry().rarity(), "draw 5 is the 30th in a row");
        // never more than 30 in a row, whatever the luck
        Random rnd = new Random(3);
        int pity = 0, run = 0, longest = 0;
        for (int i = 0; i < 5000; i++) {
            GachaRules.Result one = r.pull(POOL, 10, pity, Set.of(), () -> rnd.nextDouble() * 0.5);   // unlucky player
            for (GachaRules.Draw d : one.draws()) {
                run = d.entry().rarity() == Rarity.EPIC ? 0 : run + 1;
                longest = Math.max(longest, run);
            }
            pity = one.pityAfter();
        }
        assertTrue(longest <= 29, "longest run without an epic " + longest);
    }

    @Test
    void tenPullHasARareOrBetter() {
        GachaRules r = rules(0);
        GachaRules.Result res = r.pull(POOL, 10, 0, Set.of(), seq(0.0));
        assertEquals(10, res.draws().size());
        for (int i = 0; i < 9; i++) assertEquals(Rarity.COMMON, res.draws().get(i).entry().rarity());
        assertTrue(res.draws().get(9).entry().rarity().compareTo(Rarity.RARE) >= 0, "the last one is lifted");
        assertTrue(res.draws().get(9).guaranteed());
        // no lift when one came already, and none for a single pull
        res = r.pull(POOL, 10, 0, Set.of(), seq(0.6, 0.0, 0.0));
        assertEquals(Rarity.RARE, res.draws().getFirst().entry().rarity());
        assertTrue(res.draws().stream().noneMatch(GachaRules.Draw::guaranteed));
        assertEquals(Rarity.COMMON, r.pull(POOL, 1, 0, Set.of(), seq(0.0)).draws().getFirst().entry().rarity());
        assertEquals(5400, r.price(10));
        assertEquals(600, r.price(1));
    }

    @Test
    void duplicatesAreMarked() {
        GachaRules r = rules(0);
        // owned already
        GachaRules.Result res = r.pull(POOL, 1, 0, Set.of("c1"), seq(0.0));
        assertEquals("c1", res.draws().getFirst().entry().id());
        assertTrue(res.draws().getFirst().duplicate(), "owned: comes as the voucher");
        assertFalse(r.pull(POOL, 1, 0, Set.of("c2"), seq(0.0)).draws().getFirst().duplicate());
        // the same one twice in one pull: the second is a duplicate
        res = r.pull(POOL, 10, 0, Set.of(), seq(0.0));
        assertFalse(res.draws().get(0).duplicate());
        assertTrue(res.draws().get(1).duplicate());
    }

    @Test
    void legendaryNeverEntersThePool() {
        List<String> problems = new ArrayList<>();
        YamlConfiguration y = new YamlConfiguration();
        y.set("weights.common", 1);
        y.set("weights.legendary", 50);
        y.set("pool", List.of("a", "crown", "nope", "novoucher"));
        GachaRules r = GachaRules.parse(y, problems);
        assertFalse(r.weights().containsKey(Rarity.LEGENDARY));
        Map<String, Rarity> rarity = Map.of("a", Rarity.COMMON, "crown", Rarity.LEGENDARY, "novoucher", Rarity.RARE);
        List<GachaRules.Entry> pool = r.entries(rarity::get, id -> !id.equals("novoucher"), problems);
        assertEquals(List.of(new GachaRules.Entry("a", Rarity.COMMON)), pool);
        assertTrue(problems.stream().anyMatch(p -> p.contains("crown")));
    }

    /**
     * The shipped gacha.yml: the 38 cosmetics the shop used to sell, none legendary, none an achievement
     * pet, each with a voucher item; the shop no longer sells them directly.
     */
    @Test
    void theShippedPool() throws Exception {
        List<String> problems = new ArrayList<>();
        GachaRules r = GachaRules.parse(YamlConfiguration.loadConfiguration(PACK.resolve("gacha.yml").toFile()), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        Map<String, Rarity> rarity = new HashMap<>();
        Map<String, String> obtain = new HashMap<>();
        Set<String> withVoucher = new HashSet<>();
        for (String file : List.of("items/cosmetics.yml", "items/cosmetic_vouchers.yml")) {
            YamlConfiguration items = YamlConfiguration.loadConfiguration(PACK.resolve(file).toFile());
            for (String key : items.getKeys(false)) {
                ConfigurationSection c = items.getConfigurationSection(key + ".cosmetic");
                if (c != null) {
                    rarity.put("stsv:" + key, GachaRules.rarity(c.getString("rarity")));
                    obtain.put("stsv:" + key, c.getString("obtain", ""));
                }
                String v = items.getString(key + ".voucher.cosmetic");
                if (v != null) {
                    withVoucher.add(v.contains(":") ? v : "stsv:" + v);
                    String texture = items.getString(key + ".texture");
                    assertTrue(PACK.resolve("textures/" + texture + ".png").toFile().exists(), key + ": texture " + texture);
                }
            }
        }
        List<GachaRules.Entry> pool = r.entries(rarity::get, withVoucher::contains, problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertEquals(38, pool.size());
        assertTrue(pool.stream().noneMatch(e -> e.rarity() == Rarity.LEGENDARY));
        for (String pet : List.of("stsv:mole", "stsv:lamb", "stsv:otter", "stsv:owl")) {
            assertFalse(pool.stream().anyMatch(e -> e.id().equals(pet)), pet + " stays an achievement reward");
        }
        // every cosmetic in the pool says so in the wardrobe; every legendary one is still not in it
        for (GachaRules.Entry e : pool) assertTrue(obtain.get(e.id()).contains("뽑기"), e.id() + ": " + obtain.get(e.id()));
        rarity.forEach((id, rr) -> {
            if (rr == Rarity.LEGENDARY) assertFalse(obtain.get(id).contains("뽑기"), id);
        });
        // the expected cost of one particular cosmetic by 10-pulls stays near the old shop prices
        Set<Rarity> in = GachaRules.rarities(pool);
        double perDraw = r.multiPrice() / (double) r.multiCount();
        Map<Rarity, Long> count = new EnumMap<>(Rarity.class);
        pool.forEach(e -> count.merge(e.rarity(), 1L, Long::sum));
        Map<Rarity, Double> shop = Map.of(Rarity.COMMON, 12_500.0, Rarity.RARE, 25_000.0, Rarity.EPIC, 50_000.0);
        for (Rarity x : in) {
            double cost = perDraw * count.get(x) / r.chance(x, in);
            assertEquals(shop.get(x), cost, shop.get(x) * 0.25, x + ": " + Math.round(cost));
        }
    }
}
