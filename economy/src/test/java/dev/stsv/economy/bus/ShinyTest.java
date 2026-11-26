package dev.stsv.economy.bus;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 별난 손님 (bus.yml shiny): who can come shiny, their skins (drawn by tools/assetgen/economy_bus_skins.py) and HUD faces,
 * their lines, how often they come, and that nothing about money is configured for them.
 */
class ShinyTest {

    static final Path SKINS = Path.of("content/economy/assets/economy/textures/entity/bus");

    @Test
    void everyKindThatComesHasAShinyLookExceptTheSecretOne() {
        BusSpec s = BusTest.spec();
        BusSpec.Shiny sh = s.shiny();
        assertFalse(sh.eligible(s.types().get("shades")), "the secret ??? is never shiny");
        int n = 0;
        for (BusSpec.Type t : s.types().values()) {
            if (!sh.eligible(t)) continue;
            n++;
            String skin = sh.skin(t);
            assertNotNull(skin, t.id());
            String key = skin.endsWith(" slim") ? skin.substring(0, skin.length() - 5) : skin;
            assertEquals("economy:entity/bus/" + t.id() + "_s1", key);
            assertTrue(Files.exists(SKINS.resolve(t.id() + "_s1.png")), t.id() + "_s1.png");
            // the same arms as its first look (the skin is drawn from it)
            assertEquals(t.skins().getFirst().endsWith(" slim"), skin.endsWith(" slim"), t.id());
            // its own words getting on and off
            assertNotNull(sh.own().get(t.id()), t.id() + " shiny-talk");
            for (String k : List.of("board", "thanks")) assertFalse(sh.lines(t, k).isEmpty(), t.id() + " " + k);
        }
        assertEquals(s.types().size() - 1, n);
        assertFalse(Files.exists(SKINS.resolve("shades_s1.png")));
    }

    @Test
    void theSkinKeyFollowsTheFirstSkin() {
        assertEquals("economy:entity/bus/grandma_s1 slim", BusSpec.Shiny.shinySkin("economy:entity/bus/grandma_1 slim", "grandma"));
        assertEquals("economy:entity/bus/resident_s1", BusSpec.Shiny.shinySkin("economy:entity/bus/resident_1", "resident"));
        assertEquals("x:y_s1", BusSpec.Shiny.shinySkin("x:y_1", "y"));
    }

    @Test
    void generalLinesFillInForAKindWithoutItsOwn() {
        BusSpec s = BusTest.spec();
        BusSpec.Shiny sh = new BusSpec.Shiny(0.5, s.shiny().talk(), java.util.Map.of(), java.util.Map.of(), Set.of());
        BusSpec.Type t = s.types().get("resident");
        assertEquals(s.shiny().talk().get("board"), sh.lines(t, "board"));
        assertNull(sh.lines(t, "jolt"), "only getting on and off");
        assertEquals("오늘은 좀 꾸며 봤어요~ 반짝반짝하죠?", s.shiny().lines(t, "board").getFirst());
    }

    @Test
    void eachNewcomerRollsOnce() {
        BusSpec.Shiny sh = new BusSpec.Shiny(0.25, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), Set.of());
        double[] rolls = {0.1, 0.3, 0.24, 0.9, 0.25};
        int[] i = {0};
        assertEquals(2, sh.arrivals(5, () -> rolls[i[0]++]));
        assertEquals(5, i[0], "one roll a newcomer");
        assertEquals(0, sh.arrivals(0, () -> 0));
        assertEquals(0, sh.arrivals(-3, () -> 0));
    }

    /**
     * About one every 2-3 hours for a steady driver: stops every 700 blocks at 6 blocks a second (about 2 minutes apart: 25-30
     * stops an hour), each holding up to 6 who came one every 2 minutes, about 4 boarding a stop: about 100 an hour (the plan's
     * 4-7만 G an hour at about 420 G a fare). The roll is per newcomer at a stop, so shiny ones met = passengers met × chance.
     */
    @Test
    void aSteadyDriverMeetsOneEveryTwoToThreeHours() {
        BusSpec s = BusTest.spec();
        double perStopVisit = 4, stopsAnHour = 3600 / (s.stops().auto().every() / s.fare().onTimeSpeed());
        double met = perStopVisit * stopsAnHour;
        assertTrue(met > 80 && met < 140, "passengers an hour " + met);
        double hours = 1 / (met * s.shiny().chance());
        assertTrue(hours >= 2 && hours <= 3.2, "hours between shiny ones " + hours);
        // and a stop's refill gives that many: 2 minutes a passenger, capped at waiting-max
        assertTrue(s.stops().waitingMax() >= perStopVisit);
    }

    @Test
    void theOverlayHasEveryShinyFace() throws Exception {
        BusSpec s = BusTest.spec();
        var faces = JsonParser.parseString(Files.readString(Path.of("../plugin/src/main/resources/pack/hud/sheets.json")))
                .getAsJsonObject().getAsJsonObject("faces").getAsJsonArray("names");
        Set<String> names = new HashSet<>();
        faces.forEach(e -> names.add(e.getAsString()));
        for (BusSpec.Type t : s.types().values()) {
            for (String k : t.looks()) {
                String key = k.endsWith(" slim") ? k.substring(0, k.length() - 5) : k;
                assertTrue(names.contains(key.substring(key.lastIndexOf('/') + 1)), key);
            }
            if (s.shiny().eligible(t)) assertTrue(names.contains(t.id() + "_s1"), t.id() + "_s1");
            if (s.shiny().eligible(t) && t.pair()) assertTrue(names.contains(t.id() + "_s2"), t.id() + "_s2");
        }
    }

    @Test
    void thePreviewSaysTheShinyLines() {
        BusSpec s = BusTest.spec();
        BusSpec.Type t = s.types().get("grandma");
        List<String[]> script = PassengerPreview.script(s, t, null, 100, true);
        assertEquals("board", script.getFirst()[0]);
        assertTrue(s.shiny().lines(t, "board").contains(script.getFirst()[1]));
        assertTrue(s.shiny().lines(t, "thanks").contains(script.getLast()[1]));
        assertTrue(s.lines(t, "slow").contains(script.get(1)[1]), "the middle one is the kind's own");
    }

    @Test
    void forcedShinyKeepsItsKindAndNothingElse() {
        BusModule.Forced f = BusModule.Forced.shinyOne("grandma");
        assertTrue(f.shiny());
        assertEquals("grandma", f.type());
        assertNull(f.line());
        assertNull(f.special());
        assertEquals(0, f.team());
        assertFalse(new BusModule.Forced("grandma", 1, 2, true).shiny());
    }
}
