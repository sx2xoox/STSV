package dev.stsv.core.gui;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static dev.stsv.core.gui.ItemCatalog.Category;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The admin item browser's categories and search, on the shipped content (core and economy packs). */
class ItemCatalogTest {

    private static List<ItemCatalog.Facts> shipped() throws Exception {
        List<ItemCatalog.Facts> out = new ArrayList<>();
        for (Path dir : List.of(Path.of("../content/stsv/items"), Path.of("../economy/content/economy/items"))) {
            String ns = dir.getParent().getFileName().toString();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                    String source = f.getFileName().toString().replace(".yml", "");
                    YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                    for (String id : y.getKeys(false)) {
                        ConfigurationSection s = y.getConfigurationSection(id);
                        if (s == null) continue;
                        out.add(new ItemCatalog.Facts(ns + ":" + id, s.getString("name", ""), source,
                                Material.matchMaterial(s.getString("material", "paper")), s.contains("furniture"), s.contains("cosmetic"),
                                s.contains("voucher"), s.contains("skin") || s.contains("equipment"), s.contains("food")));
                    }
                }
            }
        }
        return out;
    }

    private static Category of(List<ItemCatalog.Facts> all, String id) {
        return ItemCatalog.of(all.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id)));
    }

    @Test
    void shippedItemsFallIntoTheirCategories() throws Exception {
        List<ItemCatalog.Facts> all = shipped();
        assertTrue(all.size() > 300, "items: " + all.size());
        assertEquals(Category.MACHINE, of(all, "economy:forge"));
        assertEquals(Category.MACHINE, of(all, "economy:press"));
        assertEquals(Category.COSMETIC, of(all, "stsv:straw_hat"));
        assertEquals(Category.COSMETIC, of(all, "stsv:tomato_voucher"));
        assertEquals(Category.FOOD, of(all, "economy:kimchi"));
        assertEquals(Category.GOODS, of(all, "economy:ruby"));
        assertEquals(Category.GOODS, of(all, "economy:soju"));
        assertEquals(Category.OTHER, of(all, "stsv:cheque"));
        Map<Category, Integer> count = new EnumMap<>(Category.class);
        for (ItemCatalog.Facts f : all) count.merge(ItemCatalog.of(f), 1, Integer::sum);
        for (Category c : List.of(Category.MACHINE, Category.COSMETIC, Category.CROP, Category.FISH, Category.FOOD, Category.GOODS)) {
            assertTrue(count.getOrDefault(c, 0) > 0, c + " is empty: " + count);
        }
        assertTrue(Category.values().length + 1 <= 9, "전체 and every category fit the top row");
    }

    @Test
    void search() {
        ItemCatalog.Facts kimchi = new ItemCatalog.Facts("economy:kimchi", "<#e04a2f>배추 김치", "jobs", Material.PAPER, false, false, false, false, true);
        assertTrue(ItemCatalog.matches(kimchi, null));
        assertTrue(ItemCatalog.matches(kimchi, "김치"));
        assertTrue(ItemCatalog.matches(kimchi, "배추김치"), "spaces in the name do not matter");
        assertTrue(ItemCatalog.matches(kimchi, "KIMCHI"));
        assertTrue(ItemCatalog.matches(kimchi, "배추 kim"), "every word");
        assertFalse(ItemCatalog.matches(kimchi, "e04a2f"), "colour tags are not searched");
        assertFalse(ItemCatalog.matches(kimchi, "깍두기"));
        List<ItemCatalog.Facts> list = List.of(kimchi,
                new ItemCatalog.Facts("economy:forge", "대장간", "jobs", Material.PAPER, true, false, false, false, false));
        assertEquals(1, ItemCatalog.pick(list, f -> f, Category.MACHINE, null).size());
        assertEquals(2, ItemCatalog.pick(list, f -> f, null, "").size());
        assertEquals(List.of(kimchi), ItemCatalog.pick(list, f -> f, null, "김치"));
        assertTrue(ItemCatalog.pick(list, f -> f, Category.MACHINE, "김치").isEmpty());
    }
}
