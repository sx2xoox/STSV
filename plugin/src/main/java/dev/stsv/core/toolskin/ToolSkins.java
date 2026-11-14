package dev.stsv.core.toolskin;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The tool skins there are (tool_skins.yml): looks a tool takes in its owner's hands, sold in series ("장난감 시리즈").
 * A skin changes what the tool looks like and nothing else. No Bukkit types but the file reader (unit-tested).
 */
public record ToolSkins(Map<String, Series> series, Map<String, Skin> skins) {

    public static final ToolSkins EMPTY = new ToolSkins(Map.of(), Map.of());

    /** The kinds of tool a skin can be for, by what the game calls the item. */
    public enum Tool {
        SWORD("검"), PICKAXE("곡괭이"), SHOVEL("삽"), AXE("도끼"), HOE("괭이"), FISHING_ROD("낚싯대");

        public final String word;

        Tool(String word) {
            this.word = word;
        }

        /** The tool an item of this material is ("DIAMOND_PICKAXE" → PICKAXE), or null. */
        public static Tool of(String material) {
            String m = material.toUpperCase(Locale.ROOT);
            if (m.equals("FISHING_ROD")) return FISHING_ROD;
            if (m.endsWith("_SWORD")) return SWORD;
            if (m.endsWith("_PICKAXE")) return PICKAXE;
            if (m.endsWith("_SHOVEL")) return SHOVEL;
            if (m.endsWith("_AXE")) return AXE;
            if (m.endsWith("_HOE")) return HOE;
            return null;
        }

        public static Tool parse(String s) {
            if (s == null) return null;
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** @param model the item model the tool takes ("stsv:toy_sword") */
    public record Skin(String id, String series, String name, Tool tool, String model) {}

    public record Series(String id, String name, List<String> skins) {}

    public Skin skin(String id) {
        return id == null ? null : skins.get(id);
    }

    /** Reads the file: series → skins. A skin without a known tool or a model is left out (one line each in problems). */
    public static ToolSkins parse(ConfigurationSection file, Consumer<String> problems) {
        Map<String, Series> series = new LinkedHashMap<>();
        Map<String, Skin> skins = new LinkedHashMap<>();
        ConfigurationSection all = file.getConfigurationSection("series");
        for (String sid : all == null ? List.<String>of() : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(sid);
            ConfigurationSection in = s == null ? null : s.getConfigurationSection("skins");
            if (in == null || !sid.matches("[a-z0-9_]{1,32}")) {
                problems.accept("series." + sid + ": id 는 영문 소문자·숫자·_, 값은 { name, skins: { ... } }");
                continue;
            }
            List<String> mine = new java.util.ArrayList<>();
            for (String id : in.getKeys(false)) {
                ConfigurationSection k = in.getConfigurationSection(id);
                Tool tool = k == null ? null : Tool.parse(k.getString("tool"));
                String model = k == null ? null : k.getString("model");
                if (!id.matches("[a-z0-9_]{1,48}") || tool == null || model == null || !model.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                    problems.accept("series." + sid + ".skins." + id + ": { name, tool: sword|pickaxe|shovel|axe|hoe|fishing_rod, model: <네임스페이스>:<모델> }");
                    continue;
                }
                if (skins.containsKey(id)) {
                    problems.accept("series." + sid + ".skins." + id + ": 같은 id 의 스킨이 이미 있음");
                    continue;
                }
                skins.put(id, new Skin(id, sid, k.getString("name", id), tool, model));
                mine.add(id);
            }
            if (!mine.isEmpty()) series.put(sid, new Series(sid, s.getString("name", sid), List.copyOf(mine)));
        }
        return new ToolSkins(Collections.unmodifiableMap(series), Collections.unmodifiableMap(skins));
    }
}
