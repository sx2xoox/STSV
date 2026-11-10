package dev.stsv.core.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** /닉네임 색 was empty on a server whose config.yml predates the nickname palette: the jar's palette must be used. */
class CfgTest {

    @Test
    void anOlderConfigFileFallsBackToTheJarsSection() throws Exception {
        YamlConfiguration jar = new YamlConfiguration();
        jar.loadFromString("nicknames:\n  colors:\n    palette:\n      벚꽃: \"#ffb7c5\"\n      하늘: \"#8fd3ff\"\n");
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("profiles:\n  title-in-chat: true\n");   // written before nicknames existed
        file.setDefaults(jar);
        var c = file.getConfigurationSection("nicknames");
        assertEquals(Set.of(), c.getConfigurationSection("colors.palette").getKeys(false), "what Bukkit hands back: no keys");
        assertEquals(Set.of("벚꽃", "하늘"), Cfg.listed(c.getConfigurationSection("colors.palette")).getKeys(false));
        // the owner's own palette wins when it is in the file
        YamlConfiguration own = new YamlConfiguration();
        own.loadFromString("nicknames:\n  colors:\n    palette:\n      노을: \"#ff9a5c\"\n");
        own.setDefaults(jar);
        assertEquals(Set.of("노을"), Cfg.listed(own.getConfigurationSection("nicknames.colors.palette")).getKeys(false));
    }
}
