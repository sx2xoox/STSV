package dev.stsv.economy.bus;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** /관리자 버스 손님: which situations a kind shows and says, and the names the admin sees for them. */
class PassengerPreviewTest {

    @Test
    void previewShowsBoardingATellingOneAndThanks() {
        BusSpec s = BusTest.spec();
        // their own most telling situation in the middle
        assertEquals(List.of("board", "chat", "thanks"), s.previewSequence(s.types().get("chatty")));
        assertEquals(List.of("board", "slow", "thanks"), s.previewSequence(s.types().get("grandma")));
        assertEquals(List.of("board", "cry", "thanks"), s.previewSequence(s.types().get("mom")));
        assertEquals(List.of("board", "bark", "thanks"), s.previewSequence(s.types().get("dogwalker")));
        // nothing telling of their own: their smooth-stop line, else a jolt (default lines fill in)
        assertEquals(List.of("board", "smooth", "thanks"), s.previewSequence(s.types().get("vip")));
        assertEquals(List.of("board", "jolt", "thanks"), s.previewSequence(s.types().get("resident")));
        assertEquals(List.of("board", "late", "thanks"), s.previewSequence(s.types().get("shades")));
        for (BusSpec.Type t : s.types().values()) {
            List<String> seq = s.previewSequence(t);
            assertEquals(3, seq.size(), t.id());
            for (String k : seq) assertNotNull(s.lines(t, k), t.id() + " " + k);
        }
    }

    @Test
    void situationsAreTheKindsOwnThenTheDefaults() {
        BusSpec s = BusTest.spec();
        List<String> g = s.situations(s.types().get("grandma"));
        assertEquals("board", g.getFirst());
        assertTrue(g.containsAll(List.of("slow", "give-up", "missed", "bell", "transfer")), g.toString());
        assertEquals(g.size(), g.stream().distinct().count());
        assertFalse(s.situations(s.types().get("resident")).contains("chat"));
        for (BusSpec.Type t : s.types().values()) for (String k : s.situations(t)) assertNotNull(s.lines(t, k));
    }

    @Test
    void everyNameTheAdminSeesIsInTheMessages() {
        BusSpec s = BusTest.spec();
        YamlConfiguration m = YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml"));
        for (BusSpec.Type t : s.types().values()) {
            for (String k : s.situations(t)) {
                assertTrue(m.contains("bus.preview-sit." + k), "bus.preview-sit." + k);
                assertNotNull(PassengerPreview.moodEmoji(k));
            }
            for (Map.Entry<String, String> e : PassengerPreview.traits(t)) assertTrue(m.contains("bus.preview-trait." + e.getKey()), e.getKey());
            assertTrue(m.contains(PassengerPreview.idlePath(s.queue().idleOf(t.id()))), t.id());
        }
    }

    @Test
    void traitsSayWhatSetsAKindApart() {
        BusSpec s = BusTest.spec();
        assertTrue(PassengerPreview.traits(s.types().get("resident")).isEmpty());
        List<String> ghost = PassengerPreview.traits(s.types().get("ghost")).stream().map(Map.Entry::getKey).toList();
        assertTrue(ghost.containsAll(List.of("ghost", "token", "hours")), ghost.toString());
        assertTrue(PassengerPreview.traits(s.types().get("shades")).stream().anyMatch(e -> e.getKey().equals("hidden")));
        assertTrue(PassengerPreview.traits(s.types().get("grandma")).contains(Map.entry("slow", "8")));
        assertTrue(PassengerPreview.traits(s.types().get("dogwalker")).stream().anyMatch(e -> e.getKey().equals("pet")));
        assertEquals("0.65", PassengerPreview.num(0.65));
        assertEquals("3", PassengerPreview.num(3.0));
    }

    @Test
    void aSituationAskedShowsItsLinesAsTheyFit() {
        BusSpec s = BusTest.spec();
        BusSpec.Type chatty = s.types().get("chatty");
        List<String[]> five = PassengerPreview.script(s, chatty, "chat", PassengerPreview.SECONDS * 20);
        assertEquals(PassengerPreview.SECONDS * 20 / PassengerPreview.MIN_BUBBLE, five.size());
        for (String[] l : five) assertEquals("chat", l[0]);
        assertEquals(3, PassengerPreview.script(s, chatty, null, 100).size());
        // the default lines when the kind has none of its own
        assertEquals(s.extra().talk().get("give-up").size(), PassengerPreview.script(s, s.types().get("resident"), "give-up", 200).size());
    }
}
