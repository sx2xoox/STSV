package dev.stsv.economy.store;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a customer who paid goes follows how the visit went: outcome and mood → the parting, never a happy thanks after a bad one. */
class PartingTest {

    @Test
    void theThanksOnlyAfterAGoodVisit() {
        assertEquals(Parting.Kind.THANKS, Parting.kind(false, 5, -1));
        assertEquals(Parting.Kind.THANKS, Parting.kind(false, 4, -1));
        assertEquals(Parting.Kind.PLAIN, Parting.kind(false, 3, -1), "a middling mood: a plain word or nothing");
        assertEquals(Parting.Kind.SOUR, Parting.kind(false, 2, -1), "under ★3: no thanks");
        assertEquals(Parting.Kind.SOUR, Parting.kind(false, 1, -1));
        for (int stars = 1; stars <= 5; stars++) {
            assertEquals(Parting.Kind.SOUR, Parting.kind(true, stars, -1), "handled badly: whatever the mood, no thanks (★" + stars + ")");
            assertEquals(Parting.Kind.SOUR, Parting.kind(true, stars, 60_000), "a complaint long ago: one sour word");
            assertEquals(Parting.Kind.SILENT, Parting.kind(true, stars, 500), "they have just said what they think: nothing over it");
        }
        assertEquals(Parting.Kind.SILENT, Parting.kind(false, 1, Parting.JUST_SAID - 1));
        assertEquals(Parting.Kind.SOUR, Parting.kind(false, 1, Parting.JUST_SAID));
        assertEquals(Parting.Kind.THANKS, Parting.kind(false, 5, 100), "a grumble that was made good (the mood is high again, nothing was the worker's doing)");
    }

    @Test
    void whatShowsOverThemNeverContradictsTheLine() {
        for (Parting.Kind k : Parting.Kind.values()) {
            boolean good = k == Parting.Kind.THANKS;
            assertEquals(good, Parting.hearts(k, 100), k + ": hearts only with the thanks");
            assertFalse(Parting.hearts(k, 89), "and only when delighted");
            assertEquals(k == Parting.Kind.THANKS || k == Parting.Kind.PLAIN, Parting.stars(k), k + ": no ★ over a sour face");
            assertEquals(k == Parting.Kind.SOUR || k == Parting.Kind.SILENT, Parting.puff(k));
            assertFalse(Parting.puff(k) && (Parting.stars(k) || Parting.hearts(k, 100)), "never both");
        }
    }

    @Test
    void whichAnswersAreABadEnd() {
        for (String bad : new String[]{"scold-bad", "refuse-bad", "firm-bad", "wake-bad", "guess-bad"}) assertTrue(Parting.bad(bad, 0), bad);
        for (String ok : new String[]{"scold-ok", "refund", "behave", "allow", "start", "self"}) assertFalse(Parting.bad(ok, -5), ok);
        assertTrue(Parting.bad("anything", -20), "a hard knock to their mood is one too");
        assertFalse(Parting.bad(null, 0));
        // every "-bad" outcome the rules know is caught
        for (Trouble.Kind k : Trouble.Kind.values()) {
            for (String choice : new String[]{"scold", "guess", "refuse", "firm", "wake", "ask", "refund", "behave", "allow", "self", "hangover"}) {
                for (double mood : new double[]{0, 50, 100}) {
                    for (double roll : new double[]{0.01, 0.99}) {
                        Trouble.Outcome o;
                        try {
                            o = Trouble.resolve(k, choice, new Trouble.Context(mood, false, true, 3000, 1000, 0), Trouble.Numbers.DEFAULT, roll);
                        } catch (RuntimeException e) {
                            continue;
                        }
                        if (o != null && o.talk().endsWith("-bad")) assertTrue(Parting.bad(o.talk(), o.mood()), k + " " + o.talk());
                    }
                }
            }
        }
    }

    @Test
    void theSourAndPlainWordsAreInTheFile() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(Path.of(System.getProperty("stsv.content")).resolve("economy/store.yml").toFile());
        assertFalse(y.getStringList("talk." + Parting.SOUR_LINES).isEmpty());
        assertFalse(y.getStringList("talk." + Parting.PLAIN_LINES).isEmpty());
        for (String key : new String[]{Parting.SOUR_LINES, Parting.PLAIN_LINES}) {
            for (String line : y.getStringList("talk." + key)) {
                assertTrue(line.length() <= 14, "short: " + line);
                assertFalse(line.contains("감사") || line.contains("♥") || line.contains("또 올게"), "nothing cheerful: " + line);
            }
        }
        var customers = y.getConfigurationSection("customers");
        int own = 0;
        for (String id : customers.getKeys(false)) {
            for (String line : customers.getStringList(id + ".talk." + Parting.SOUR_LINES)) {
                own++;
                assertTrue(line.length() <= 16, id + ": " + line);
                assertFalse(line.contains("감사") || line.contains("고마"), id + ": " + line);
            }
        }
        assertTrue(own >= 10, "several kinds have their own sour word");
    }
}
