package dev.stsv.economy.secret.doll;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 낡은 인형's journey: seven different hands named by the riddles, wrong hands do not count, only the first owner ends it,
 * the final answer is the hands' first letters; the saved form; doll.yml has every line the code reads.
 */
class DollChainTest {

    static UUID id(int n) {
        return new UUID(1, n);
    }

    @Test
    void sevenHandsThenHomeToTheFirstOwner() {
        String[] names = {"민수", "Steve", "하늘", "지아", "alex", "도윤", "유나"};
        Chain c = new Chain(id(0), names[0]);
        assertEquals(1, c.index());
        for (int i = 1; i < Chain.HANDS; i++) {
            assertEquals(Chain.Result.WRONG, c.pass(id(100 + i), "틀린" + i, false));   // the wrong person giggles
            assertEquals(i, c.index());                                             // … and does not count
            assertEquals(Chain.Result.NEXT, c.pass(id(i), names[i], true));
            assertEquals(i + 1, c.index());
            assertEquals(id(i), c.holder);
        }
        assertTrue(c.last());
        // the seventh riddle: only the first owner; anyone else (even someone "matching") is wrong
        assertEquals(Chain.Result.WRONG, c.pass(id(3), names[3], true));
        assertEquals(Chain.Phase.CHAIN, c.phase);
        assertEquals(Chain.Result.HOME, c.pass(id(0), names[0], false));
        assertEquals(Chain.Phase.HOME, c.phase);
        assertEquals(Chain.HANDS, c.hands().size());
        assertEquals("민S하지a도유", c.answer());
        assertTrue(Chain.check("민 S 하 지 A 도 유", c.answer()));
        assertTrue(Chain.check("민,s,하,지,a,도,유", c.answer()));
        assertFalse(Chain.check("민S하지a도", c.answer()));
        assertFalse(Chain.check("", c.answer()));
        // someone takes it from the first owner at the end: it has to come back to them
        assertEquals(Chain.Result.WRONG, c.pass(id(5), names[5], true));
        assertEquals(Chain.Phase.CHAIN, c.phase);
        assertEquals(Chain.Result.HOME, c.pass(id(0), names[0], false));
        assertEquals("민S하지a도유", c.answer());   // wrong hands never change the answer
    }

    @Test
    void handsAreNeverCountedTwiceUnlessForced() {
        Chain c = new Chain(id(0), "a");
        assertEquals(Chain.Result.NONE, c.pass(id(0), "a", true));
        assertTrue(c.held(id(0)));
        // the caller never says "matches" for a counted hand; a forced pass (solo test) counts anyway
        assertEquals(Chain.Result.NEXT, c.force(id(0), "a"));
        assertEquals(2, c.index());
        for (int i = 0; i < 5; i++) c.force(id(0), "a");
        assertTrue(c.last());
        assertEquals(Chain.Result.HOME, c.force(id(0), "a"));
        assertEquals("aaaaaaa", c.answer());
    }

    @Test
    void goingHomeStartsAgainAndRemembersWhoLostIt() {
        Chain c = new Chain(id(0), "주인");
        c.riddle = Riddles.Kind.LAND;
        c.pass(id(1), "하나", true);
        c.riddle = Riddles.Kind.FISH;
        c.pass(id(9), "틀림", false);
        c.restart("틀림");
        assertEquals(1, c.index());
        assertEquals(id(0), c.holder);
        assertEquals(List.of("틀림"), c.lost);
        assertTrue(c.used.isEmpty());
        assertNull(c.riddle);
        assertEquals(1, c.trail.size());
    }

    @Test
    void savedFormRoundTrips() {
        Random r = new Random(3);
        for (int run = 0; run < 300; run++) {
            Chain c = new Chain(id(0), "처음;주인:,|");
            Set<Riddles.Kind> kinds = EnumSet.allOf(Riddles.Kind.class);
            List<Riddles.Kind> ks = new ArrayList<>(kinds);
            for (int step = 0; step < 12; step++) {
                c.riddle = ks.get(r.nextInt(ks.size()));
                c.snapshot.add(id(r.nextInt(30)));
                c.pass(id(1 + r.nextInt(20)), "n" + step + (r.nextBoolean() ? "한글" : ";,:"), r.nextBoolean());
                if (r.nextInt(10) == 0) c.restart("lost" + step);
                Chain d = Chain.decode(c.encode());
                assertNotNull(d);
                assertEquals(c.encode(), d.encode());
                assertEquals(c.answer(), d.answer());
                assertEquals(c.phase, d.phase);
                assertEquals(c.holder, d.holder);
            }
        }
        assertNull(Chain.decode(""));
        assertNull(Chain.decode("garbage"));
    }

    @Test
    void theListingIsWrittenBackwards() {
        assertEquals("<#8a7a6a>?래줄 가려데 좀 나 가누", Chain.backwards("<#8a7a6a>누가 나 좀 데려가 줄래?"));
        assertEquals("cba", Chain.backwards("abc"));
    }

    @Test
    void dollYmlHasEveryLine() {
        File content = new File(System.getProperty("stsv.content"), "economy");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(content, "secrets/doll.yml"));
        assertEquals(300000, y.getInt("rewards.money"));
        assertEquals("economy:returned_one", y.getString("rewards.title"));
        assertTrue(y.getDouble("start.chance") <= 0.05, "the listing must stay very rare");
        for (Riddles.Kind k : Riddles.Kind.values()) assertFalse(y.getString("riddles." + k.id(), "").isBlank(), k.id());
        for (String s : List.of("auction.seller", "auction.name", "auction.refuse", "auction.unsold", "mail-reason", "talk.whisper", "talk.riddle",
                "talk.taken", "talk.bye", "talk.giggle", "talk.giggle-giver", "talk.warn", "talk.warn-last", "talk.went-home", "talk.pocket",
                "talk.no-drop", "talk.dead", "talk.not-here", "talk.wrong", "talk.late", "talk.lay", "talk.thanks", "haunt.turn", "lore.hands",
                "lore.hand", "lore.smudged", "lore.wrong", "lore.riddle", "lore.lost", "final.place-hint", "companion.sleep", "companion.wake"))
            assertFalse(y.getString(s, "").isBlank(), s);
        for (String s : List.of("auction.lore", "talk.first", "talk.came-home", "talk.home", "talk.ask", "talk.forgot", "haunt.whispers",
                "haunt.night", "haunt.night-alone", "lore.head", "lore.home", "companion.given"))
            assertFalse(y.getStringList(s).isEmpty(), s);
        assertTrue(String.join("", y.getStringList("talk.home")).contains("{place}"));
        for (String l : y.getStringList("haunt.night")) assertTrue(l.contains("{name}"), l);
        // the doll item and the title exist
        YamlConfiguration items = YamlConfiguration.loadConfiguration(new File(content, "items/secrets.yml"));
        assertNotNull(items.getConfigurationSection("old_doll"));
        YamlConfiguration titles = YamlConfiguration.loadConfiguration(new File(content, "titles/secrets.yml"));
        assertTrue(titles.getBoolean("returned_one.hidden"));
    }
}
