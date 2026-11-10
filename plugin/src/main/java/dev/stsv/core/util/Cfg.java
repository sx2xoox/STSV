package dev.stsv.core.util;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

/** Small config helpers. */
public final class Cfg {
    private Cfg() {}

    /**
     * A section whose KEYS are listed (palettes, anchors...): on a server whose config.yml was written by an older version,
     * the section is not in the file, and Bukkit then hands back an empty section that lists no keys even though config.yml
     * in the jar has them. In that case the jar's section is used.
     */
    public static ConfigurationSection listed(ConfigurationSection s) {
        if (s == null || !s.getKeys(false).isEmpty()) return s;
        Configuration root = s.getRoot();
        Configuration defaults = root == null ? null : root.getDefaults();
        if (defaults == null) return s;
        ConfigurationSection d = defaults.getConfigurationSection(s.getCurrentPath());
        return d != null ? d : s;
    }
}
