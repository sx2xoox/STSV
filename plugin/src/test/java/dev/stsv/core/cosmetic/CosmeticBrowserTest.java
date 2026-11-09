package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem.CosmeticSlot;
import dev.stsv.core.content.CustomItem.Rarity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the cosmetic screens (옷장, 치장 뽑기) sort, count and label; and the cosmetic vouchers: one look, each
 * showing its own cosmetic, its first lore line naming rarity and category.
 */
class CosmeticBrowserTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");

    record C(String id, CosmeticSlot slot, Rarity rarity, String name) {}

    private static final List<C> SAMPLE = List.of(
            new C("crown", CosmeticSlot.HEAD, Rarity.LEGENDARY, "<#ffd34d>왕관"),
            new C("straw", CosmeticSlot.HEAD, Rarity.COMMON, "<#e8c66a>밀짚모자"),
            new C("cat", CosmeticSlot.HEAD, Rarity.COMMON, "<#f2a7c0>고양이 귀"),
            new C("frog", CosmeticSlot.HEAD, Rarity.RARE, "개구리 모자"),
            new C("puppy", CosmeticSlot.PET, Rarity.COMMON, "강아지"),
            new C("ghost", CosmeticSlot.PET, Rarity.EPIC, "꼬마 유령"),
            new C("petal", CosmeticSlot.TRAIL, Rarity.COMMON, "꽃잎 발자취"));

    @Test
    void tabsAreAllThenEverySlotInOrder() {
        assertEquals(CosmeticSlot.values().length + 1, CosmeticBrowser.TABS.size());
        assertEquals("all", CosmeticBrowser.TABS.getFirst());
        for (CosmeticSlot s : CosmeticSlot.values()) assertEquals(s.id, CosmeticBrowser.TABS.get(s.ordinal() + 1));
        assertTrue(CosmeticBrowser.inTab("all", CosmeticSlot.KILL));
        assertTrue(CosmeticBrowser.inTab("pet", CosmeticSlot.PET));
        assertFalse(CosmeticBrowser.inTab("pet", CosmeticSlot.HEAD));
        assertFalse(CosmeticBrowser.inTab("effect", CosmeticSlot.TRAIL), "the old 효과 tab is split into 발자취 and 처치 효과");
    }

    /** Dex order: by slot, then rarity (일반 first), then the name as read; owned or not does not move anything. */
    @Test
    void orderIsSlotThenRarityThenName() {
        List<String> ids = SAMPLE.stream().sorted(CosmeticBrowser.order(C::slot, C::rarity, C::name)).map(C::id).toList();
        assertEquals(List.of("cat", "straw", "frog", "crown", "puppy", "ghost", "petal"), ids);
    }

    @Test
    void everyTabCountsOwnedOverTotal() {
        Set<String> owned = Set.of("straw", "ghost");
        Map<String, String> n = CosmeticBrowser.counts(SAMPLE, C::slot, c -> owned.contains(c.id()));
        assertEquals("2/7", n.get("all"));
        assertEquals("1/4", n.get("head"));
        assertEquals("1/2", n.get("pet"));
        assertEquals("0/1", n.get("trail"));
        assertEquals("0/0", n.get("kill"));
        assertEquals(CosmeticBrowser.TABS, List.copyOf(n.keySet()));
    }

    @Test
    void whereANotOwnedCosmeticComesFrom() {
        assertEquals(CosmeticBrowser.Source.GACHA, CosmeticBrowser.source(Rarity.COMMON, "치장 뽑기 (/뽑기)", true));
        assertEquals(CosmeticBrowser.Source.ACHIEVEMENT, CosmeticBrowser.source(Rarity.EPIC, "업적 · 목장주 (동물 번식)", false));
        assertEquals(CosmeticBrowser.Source.LIMITED, CosmeticBrowser.source(Rarity.LEGENDARY, "주간 순위 1위 · 상인 행렬", false));
        assertEquals(CosmeticBrowser.Source.OTHER, CosmeticBrowser.source(Rarity.RARE, null, false));
        // every shipped cosmetic gets a real source (no 'other'), and legendary ones are never in the gacha
        YamlConfiguration items = YamlConfiguration.loadConfiguration(PACK.resolve("items/cosmetics.yml").toFile());
        YamlConfiguration gacha = YamlConfiguration.loadConfiguration(PACK.resolve("gacha.yml").toFile());
        Set<String> pool = Set.copyOf(gacha.getStringList("pool"));
        for (String key : items.getKeys(false)) {
            ConfigurationSection c = items.getConfigurationSection(key + ".cosmetic");
            if (c == null) continue;
            Rarity r = GachaRules.rarity(c.getString("rarity"));
            CosmeticBrowser.Source s = CosmeticBrowser.source(r, c.getString("obtain"), pool.contains("stsv:" + key));
            assertTrue(s != CosmeticBrowser.Source.OTHER, key);
            if (r == Rarity.LEGENDARY) assertEquals(CosmeticBrowser.Source.LIMITED, s, key);
        }
    }

    @Test
    void theViewFilterCycles() {
        CosmeticBrowser.View v = CosmeticBrowser.View.ALL;
        assertTrue(v.shows(true) && v.shows(false));
        v = v.next();
        assertEquals(CosmeticBrowser.View.OWNED, v);
        assertTrue(v.shows(true) && !v.shows(false));
        v = v.next();
        assertTrue(!v.shows(true) && v.shows(false));
        assertEquals(CosmeticBrowser.View.ALL, v.next());
    }

    @Test
    void rarityColoursAreDistinct() {
        Set<Integer> seen = new java.util.HashSet<>();
        for (Rarity r : Rarity.values()) assertTrue(seen.add(CosmeticBrowser.rgb(r)), r.name());
    }

    /** Keys built at run time (the Lang key test only sees literal ones). */
    @Test
    void dynamicMessageKeysExist() throws Exception {
        YamlConfiguration m;
        try (var in = getClass().getResourceAsStream("/messages.yml")) {
            m = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (CosmeticBrowser.Source s : CosmeticBrowser.Source.values()) {
            assertTrue(m.isString("wardrobe.source." + s.key()), s.key());
            assertTrue(m.isString("wardrobe.locked-hint." + s.key()), s.key());
        }
        for (CosmeticBrowser.View v : CosmeticBrowser.View.values()) assertTrue(m.isString("wardrobe.view." + v.key()), v.key());
        assertTrue(m.getString("wardrobe.locked-hint.gacha").contains("치장 뽑기"));
    }

    /**
     * Every voucher (교환권): its texture is its own id (a picture of its cosmetic, tools/assetgen/voucher_art.py),
     * the item exists, and the first lore line is "◆ &lt;rarity&gt; · &lt;category&gt; 치장" in the rarity's colour.
     */
    @Test
    void everyVoucherShowsItsCosmeticAndCategory() throws Exception {
        Map<String, ConfigurationSection> all = new HashMap<>();
        for (String file : List.of("items/cosmetics.yml", "items/cosmetic_vouchers.yml")) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve(file).toFile());
            for (String key : y.getKeys(false)) all.put(key, y.getConfigurationSection(key));
        }
        List<String> problems = new ArrayList<>();
        int vouchers = 0;
        for (Map.Entry<String, ConfigurationSection> e : all.entrySet()) {
            String target = e.getValue().getString("voucher.cosmetic");
            if (target == null) continue;
            vouchers++;
            String key = e.getKey();
            ConfigurationSection c = all.get(target).getConfigurationSection("cosmetic");
            Rarity r = GachaRules.rarity(c.getString("rarity"));
            CosmeticSlot slot = CosmeticSlot.parse(c.getString("slot"));
            if (!key.equals(e.getValue().getString("texture"))) problems.add(key + ": texture " + e.getValue().getString("texture"));
            if (!Files.exists(PACK.resolve("textures/" + key + ".png"))) problems.add(key + ": no picture");
            String first = e.getValue().getStringList("lore").getFirst();
            String want = r.color + "◆ " + r.label;
            if (!first.startsWith(want) || !first.endsWith(slot.label + " 치장")) problems.add(key + ": first lore line '" + first + "'");
        }
        assertEquals(45, vouchers, "35 + the 3 masks + 7 legendary");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertFalse(Files.exists(PACK.resolve("textures/cosmetic_voucher_common.png")), "the shared generic ticket is gone");
    }
}
