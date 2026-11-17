package dev.stsv.core.minigame.circuit;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A racing kart as the race sees it (contents/&lt;pack&gt;/circuit/karts.yml): the vehicle item that drives it (speed,
 * acceleration, turning and drifting are that item's {@code vehicle:} section), and what only a race has: how heavy it
 * is when karts bump, how fast drifting fills its boost gauge, how strong and long a boost is, and the colours it
 * comes in. Pure (unit-tested).
 *
 * @param item    the vehicle item (stsv:kart_...)
 * @param own     the colour id its model is drawn in (no paint), one of colours
 * @param colours the colours a racer picks from: tuning shop paint ids (vehicle_tuning.yml paints), own included
 * @param bars    how it compares, 1 .. 5, for the menu: speed, accel, handling, drift, weight, boost
 */
public record KartSpec(String id, String item, String name, String role, String blurb, double weight, double charge, double boost,
                       int boostTicks, String own, List<String> colours, Map<String, Integer> bars) {

    public static final List<String> BARS = List.of("speed", "accel", "handling", "drift", "weight", "boost");

    public static List<KartSpec> parseAll(ConfigurationSection root) {
        ConfigurationSection c = root.getConfigurationSection("karts");
        if (c == null) throw new IllegalArgumentException("karts: 가 없음");
        List<KartSpec> out = new ArrayList<>();
        for (String id : c.getKeys(false)) {
            try {
                out.add(parse(id, c.getConfigurationSection(id)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("karts." + id + ": " + e.getMessage(), e);
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("karts: 카트가 하나도 없음");
        return out;
    }

    static KartSpec parse(String id, ConfigurationSection k) {
        if (k == null) throw new IllegalArgumentException("설정이 없음");
        String item = k.getString("item");
        if (item == null || !item.contains(":")) throw new IllegalArgumentException("item 은 stsv:kart_... 형식");
        double weight = k.getDouble("weight", 1);
        if (weight < 0.5 || weight > 2) throw new IllegalArgumentException("weight 는 0.5 ~ 2");
        double charge = k.getDouble("charge", 1);
        if (charge < 0.3 || charge > 3) throw new IllegalArgumentException("charge 는 0.3 ~ 3");
        double boost = k.getDouble("boost", 1.3);
        if (boost < 1.1 || boost > 1.6) throw new IllegalArgumentException("boost 는 1.1 ~ 1.6 (최고 속도의 배수)");
        int ticks = k.getInt("boost-ticks", 30);
        if (ticks < 10 || ticks > 80) throw new IllegalArgumentException("boost-ticks 는 10 ~ 80");
        List<String> colours = k.getStringList("colours");
        String own = k.getString("own", colours.isEmpty() ? null : colours.getFirst());
        if (colours.isEmpty() || own == null || !colours.contains(own)) throw new IllegalArgumentException("colours 에 own(원래 색)이 있어야 함");
        if (colours.size() > 9) throw new IllegalArgumentException("colours 는 9개까지 (메뉴 한 줄)");
        Map<String, Integer> bars = new LinkedHashMap<>();
        ConfigurationSection b = k.getConfigurationSection("bars");
        for (String bar : BARS) {
            int v = b == null ? 0 : b.getInt(bar, 0);
            if (v < 1 || v > 5) throw new IllegalArgumentException("bars." + bar + " 는 1 ~ 5");
            bars.put(bar, v);
        }
        String name = k.getString("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 이 없음");
        return new KartSpec(id, item, name, k.getString("role", ""), k.getString("blurb", ""), weight, charge, boost, ticks, own,
                List.copyOf(colours), Map.copyOf(bars));
    }

    /** The paint to put on the kart for a colour id (null: its own colour, no paint). */
    public String paint(String colour) {
        return colour == null || colour.equals(own) || !colours.contains(colour) ? null : colour;
    }
}
