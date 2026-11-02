package dev.stsv.core.content;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression: nested settings inside list entries (parts[].spin, seats[]) must be readable. */
class YamlSectionTest {

    @Test
    void nestedMapInsideListEntryIsASection() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("dummy", null);
        String text = """
                parts:
                  - model: test_fan_blade
                    position: [16, 31, 8]
                    spin: { axis: y, rpm: 45 }
                """;
        try {
            yaml.loadFromString(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Object entry = yaml.getList("parts").getFirst();
        ConfigurationSection part = ContentModule.section((Map<?, ?>) entry);
        assertTrue(part.isConfigurationSection("spin"), "spin must be a section");
        assertEquals("y", part.getString("spin.axis"));
        assertEquals(45.0, part.getDouble("spin.rpm"));
        assertEquals(List.of(16, 31, 8), part.getIntegerList("position"));
    }
}
