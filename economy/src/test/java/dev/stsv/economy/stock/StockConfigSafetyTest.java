package dev.stsv.economy.stock;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A broken or empty stocks.yml must not replace the running one (it would delist every company and pay holders out). */
class StockConfigSafetyTest {

    @Test
    void onlyAParsedFileWithCompaniesIsUsed() throws Exception {
        assertFalse(StockModule.usable(null), "did not parse");
        assertFalse(StockModule.usable(StockSpec.parse(new YamlConfiguration(), new ArrayList<>())), "parsed, but no companies");
        YamlConfiguration shipped = YamlConfiguration.loadConfiguration(
                Path.of(System.getProperty("stsv.content")).resolve("economy/stocks.yml").toFile());
        assertTrue(StockModule.usable(StockSpec.parse(shipped, new ArrayList<>())));
    }

    @Test
    void aTabInTheFileIsAnErrorNotAnEmptyFile() {
        // YamlConfiguration.loadConfiguration (EcoPlugin.data) would log this and hand back an empty configuration;
        // EcoPlugin.dataStrict loads with load(), which throws, and the stock module keeps what it had
        YamlConfiguration y = new YamlConfiguration();
        assertThrows(InvalidConfigurationException.class, () -> y.loadFromString("companies:\n\tbroken: {price: 10}\n"));
    }
}
