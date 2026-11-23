package dev.stsv.economy.secret.lostkey;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 후속 임무 「주인 찾기」: the week's thing is fixed and does not repeat the last weeks', the clues always fit the stop they
 * were made from (and never the stop the thing turned up at by design of the target), what pulling into a stop means,
 * and lostkey.yml has the pool, the clue lines and a reward in the hard range.
 */
class LostKeySequelTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void variantIsFixedForAWeekAndSkipsRecentOnes() {
        int v = Owners.variant("2026-W48", A, 10, List.of());
        assertEquals(v, Owners.variant("2026-W48", A, 10, List.of()));
        assertNotEquals(v, Owners.variant("2026-W48", A, 10, List.of(v)));
        String recent = "";
        List<Integer> seen = new ArrayList<>();
        for (int w = 1; w <= 30; w++) {
            int x = Owners.variant("2026-W" + w, A, 10, Owners.recent(recent));
            assertTrue(x >= 0 && x < 10);
            List<Integer> last = seen.subList(Math.max(0, seen.size() - 4), seen.size());
            assertFalse(last.contains(x), "week " + w + " repeats " + x + " within 4 weeks");
            seen.add(x);
            recent = Owners.remember(recent, x, 4, 10);
            assertTrue(Owners.recent(recent).size() <= 4);
        }
        assertTrue(new HashSet<>(seen).size() >= 6);
        // a pool of one still works, and never remembers so much that nothing is left
        assertEquals(0, Owners.variant("w", A, 1, List.of(0)));
        assertEquals("", Owners.remember("0", 0, 4, 1));
        assertEquals("1", Owners.remember("0", 1, 4, 2));
        assertEquals(List.of(3, 5), Owners.recent("3, x,5"));
    }

    @Test
    void cluesFitTheirStop() {
        List<Clues.Stop> net = LostKeyTest.net();
        for (Clues.Stop target : net) {
            List<Clues.Clue> clues = Owners.clues(target, net, LostKeyTest.ORDER);
            assertTrue(clues.size() >= 1 && clues.size() <= 3, target.name() + " " + clues);
            assertTrue(Owners.fits(clues, target, 1), target.name() + " " + clues);
            assertTrue(Owners.fitting(clues, net).contains(target.id()));
            assertEquals(clues, Owners.decode(Owners.encode(clues)));
        }
        // 바닷가 (sea; 부두 is sea + port): the trait and the initial ㅂ fit both, neither is an end clue for 바닷가
        Clues.Stop sea = net.get(1);
        List<Clues.Clue> c = Owners.clues(sea, net, LostKeyTest.ORDER);
        assertEquals(List.of(new Clues.Clue(Clues.Kind.TAG, "sea"), new Clues.Clue(Clues.Kind.INITIAL, "ㅂ")), c);
        // 부두: its rarest trait is port; that and ㅂ fit only it, so no third clue
        assertEquals(List.of(new Clues.Clue(Clues.Kind.TAG, "port"), new Clues.Clue(Clues.Kind.INITIAL, "ㅂ")), Owners.clues(net.get(3), net, LostKeyTest.ORDER));
        assertEquals(List.of(4), Owners.fitting(Owners.clues(net.get(3), net, LostKeyTest.ORDER), net));
        // 시장: no trait at all
        assertEquals(Clues.Kind.PLAIN, Owners.clues(net.get(5), net, LostKeyTest.ORDER).getFirst().kind());
        assertFalse(Owners.fits(List.of(), sea, 1));
        assertFalse(Owners.fits(c, null, 1));
    }

    @Test
    void aThirdClueWhenTwoAreNotEnough() {
        List<Clues.Stop> net = List.of(
                new Clues.Stop(1, "솔밭", Set.of("forest"), true, false),
                new Clues.Stop(2, "숲길", Set.of("forest"), false, false),
                new Clues.Stop(3, "시장", Set.of(), false, true));
        List<Clues.Clue> c = Owners.clues(net.get(0), net, LostKeyTest.ORDER);
        assertEquals(3, c.size());
        assertEquals(Clues.Kind.FIRST, c.get(2).kind());
        assertEquals(List.of(1), Owners.fitting(c, net));
        // a nameless stop with no trait still gets something
        List<Clues.Stop> odd = List.of(new Clues.Stop(1, "", Set.of("x"), false, false), new Clues.Stop(2, "나", Set.of(), false, false));
        assertEquals(List.of(new Clues.Clue(Clues.Kind.ANY, "")), Owners.clues(odd.get(0), odd, LostKeyTest.ORDER));
    }

    @Test
    void theOwnerNeverWaitsWhereTheThingTurnedUp() {
        List<Clues.Stop> net = LostKeyTest.net();
        for (int seed = -5; seed < 40; seed++) {
            Clues.Stop t = Owners.target(net, 2, seed);
            assertNotNull(t);
            assertNotEquals(2, t.id());
        }
        assertEquals(Owners.target(net, 2, Owners.hash("2026-W48", A)), Owners.target(net, 2, Owners.hash("2026-W48", A)));
        assertNull(Owners.target(List.of(net.get(0)), 1, 3));
    }

    @Test
    void pullingIntoAStop() {
        assertEquals(Owners.Arrive.NOTHING, Owners.arrive(2, 2, true, List.of(), 3));
        assertEquals(Owners.Arrive.RIGHT, Owners.arrive(4, 2, true, List.of(1, 3), 3));
        assertEquals(Owners.Arrive.WRONG, Owners.arrive(1, 2, false, List.of(), 3));
        assertEquals(Owners.Arrive.WRONG, Owners.arrive(3, 2, false, List.of(1), 3));
        assertEquals(Owners.Arrive.AGAIN, Owners.arrive(1, 2, false, List.of(1, 3), 3));
        assertEquals(Owners.Arrive.SHELF, Owners.arrive(5, 2, false, List.of(1, 3), 3));
        assertEquals(Owners.Arrive.SHELF, Owners.arrive(5, 2, false, List.of(), 0));
    }

    @Test
    void ymlHasThePoolTheCluesAndAHardReward() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("secrets/lostkey.yml").toFile());
        List<Map<?, ?>> things = y.getMapList("sequel.things");
        assertTrue(things.size() >= 8, "sequel.things: " + things.size());
        Set<String> ids = new HashSet<>();
        YamlConfiguration items = YamlConfiguration.loadConfiguration(PACK.resolve("items/bus.yml").toFile());
        YamlConfiguration bus = YamlConfiguration.loadConfiguration(PACK.resolve("bus.yml").toFile());
        for (Map<?, ?> t : things) {
            assertTrue(ids.add(String.valueOf(t.get("id"))), "id twice: " + t.get("id"));
            for (String k : List.of("name", "item", "type", "owner", "murmur", "thanks")) assertNotNull(t.get(k), t.get("id") + "." + k);
            assertTrue(String.valueOf(t.get("murmur")).startsWith("…") && String.valueOf(t.get("thanks")).startsWith("…"), String.valueOf(t.get("id")));
            String item = String.valueOf(t.get("item"));
            assertTrue(item.startsWith("economy:") && items.contains(item.substring(8)), "items/bus.yml: " + item);
            assertTrue(bus.contains("types." + t.get("type")), "bus.yml types." + t.get("type"));
        }
        for (String tag : LostKeyTest.ORDER) assertTrue(y.isString("sequel.clues.tag." + tag), tag);
        for (String k : List.of("clues.plain", "clues.initial", "clues.first", "clues.end", "clues.any", "got", "got-later", "wrong", "wrong-murmur",
                "again", "drop", "back", "owner-name", "taken", "done", "shelf", "shelf-quiet", "item.name", "item.heard", "keepsake.line"))
            assertTrue(y.isString("sequel." + k), k);
        assertTrue(y.getString("sequel.clues.initial").contains("{jamo}{ro}"));
        List<Long> money = y.getLongList("sequel.reward.money");
        assertEquals(2, money.size());
        assertTrue(money.get(0) >= 150_000 && money.get(1) <= 300_000 && money.get(0) <= money.get(1));
        double gift = y.getDouble("sequel.keepsake.chance");
        assertTrue(gift > 0 && gift <= 0.5);
        // the keepsake's fallback kind really has gifts in bus.yml
        for (String type : y.getStringList("sequel.keepsake.types")) assertFalse(bus.getMapList("types." + type + ".gifts").isEmpty(), type);
        assertTrue(y.getInt("sequel.wrong-stops") >= 2);
    }
}
