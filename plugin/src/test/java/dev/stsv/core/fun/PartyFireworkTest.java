package dev.stsv.core.fun;

import org.bukkit.FireworkEffect;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The party firework (무한 폭죽): the wait between shots, what a shot looks like, the item and its settings. */
class PartyFireworkTest {

    private static final Path PACK = Path.of("..", "content", "stsv");

    @Test
    void theWaitBetweenShots() {
        assertEquals(0, PartyFireworkModule.wait(0, 100_000, 2.0), "never shot: at once");
        assertEquals(2000, PartyFireworkModule.wait(100_000, 100_000, 2.0));
        assertEquals(500, PartyFireworkModule.wait(100_000, 101_500, 2.0));
        assertEquals(0, PartyFireworkModule.wait(100_000, 102_000, 2.0));
        assertEquals(0, PartyFireworkModule.wait(100_000, 100_001, 0), "no wait set");
    }

    @Test
    void aShotIsBrightAndNeverTheSameTwice() {
        Random r = new Random(7);
        Set<FireworkEffect.Type> types = new HashSet<>();
        Set<Integer> colours = new HashSet<>();
        for (int k = 0; k < 400; k++) {
            FireworkEffect e = PartyFireworkModule.effect(r);
            types.add(e.getType());
            assertFalse(e.getColors().isEmpty());
            assertEquals(1, e.getFadeColors().size());
            e.getColors().forEach(c -> colours.add(c.asRGB()));
            assertTrue(e.getType() != FireworkEffect.Type.CREEPER, "no creeper face: it reads as a threat");
        }
        assertEquals(Set.of(PartyFireworkModule.TYPES), types, "every shape comes up");
        assertEquals(Set.copyOf(PartyFireworkModule.colours()), colours, "every colour comes up");
        for (int c : PartyFireworkModule.COLOURS) {
            int brightest = Math.max(c >> 16 & 255, Math.max(c >> 8 & 255, c & 255));
            assertTrue(brightest >= 200, Integer.toHexString(c) + " is too dark for a firework");
        }
    }

    @Test
    void theItemAndItsSettings() {
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        assertTrue(config.getBoolean("party-firework.enabled"));
        assertTrue(config.getDouble("party-firework.cooldown-seconds") >= 1.0, "no machine gun of fireworks (lag, noise)");
        String item = config.getString("party-firework.item");
        assertNotNull(item);
        assertTrue(item.startsWith("stsv:"));
        // the item: a custom item that is no rocket in the hand (a rocket would be placed, used up, and push a glider)
        String id = item.substring(5);
        YamlConfiguration found = null;
        for (java.io.File f : PACK.resolve("items").toFile().listFiles((d, n) -> n.endsWith(".yml"))) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            if (y.isConfigurationSection(id)) found = y;
        }
        assertNotNull(found, "no custom item " + item);
        assertFalse(found.getString(id + ".material", "").toLowerCase().contains("firework"), "not a rocket in the hand");
        assertEquals(1, found.getInt(id + ".max-stack"));
        var messages = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8));
        for (String key : List.of("wait", "not-now")) assertTrue(messages.isString("party-firework." + key), key);
    }
}
