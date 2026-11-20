package dev.stsv.economy.skin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkinBoxTest {

    static final Path ECO = Path.of(System.getProperty("stsv.content")).resolve("economy");
    static final Path CORE = Path.of(System.getProperty("stsv.content")).resolve("../../content/stsv").normalize();

    @Test
    void aRoundGivesEveryBookOnceThenStartsAgain() {
        List<String> books = List.of("a", "b", "c", "d", "e");
        Set<String> drawn = new HashSet<>();
        Set<String> got = new HashSet<>();
        Random r = new Random(7);
        for (int i = 0; i < books.size(); i++) assertTrue(got.add(SkinBox.draw(books, drawn, r)), "no book twice in a round");
        assertEquals(Set.copyOf(books), got);
        assertEquals(books.size(), drawn.size());
        String next = SkinBox.draw(books, drawn, r);
        assertEquals(Set.of(next), drawn, "the round starts again with the next box");
        // a book taken out of the box since was drawn: the others still come first
        Set<String> old = new HashSet<>(Set.of("a", "gone"));
        old.retainAll(books);
        assertNotEquals("a", SkinBox.draw(books, old, r));
    }

    @Test
    void theBoxHoldsTheThirtyGoldBooksAndTheShopSellsOnlyTheBox() throws Exception {
        YamlConfiguration box = YamlConfiguration.loadConfiguration(ECO.resolve("skin_box.yml").toFile());
        assertEquals("economy:skin_box", box.getString("item"));
        YamlConfiguration gold = YamlConfiguration.loadConfiguration(CORE.resolve("items/skin_books_gold.yml").toFile());
        Set<String> all = new HashSet<>();
        for (String id : gold.getKeys(false)) all.add("stsv:" + id);
        assertEquals(30, all.size());
        assertEquals(all, Set.copyOf(box.getStringList("books")), "every gold book, and only those");
        assertEquals(30, box.getStringList("books").size(), "each once");
        String shop = Files.readString(ECO.resolve("shop.yml"));
        assertTrue(shop.contains("{ item: economy:skin_box, price: 50000 }"));
        for (String id : all) assertFalse(shop.contains(id), id + " is sold only through the box");
        YamlConfiguration items = YamlConfiguration.loadConfiguration(ECO.resolve("items/accessories.yml").toFile());
        assertEquals("skin_box", items.getString("skin_box.texture"));
        assertTrue(Files.isRegularFile(ECO.resolve("textures/skin_box.png")));
    }
}
