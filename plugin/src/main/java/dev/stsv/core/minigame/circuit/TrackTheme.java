package dev.stsv.core.minigame.circuit;

import java.util.List;
import java.util.Map;

/**
 * What a circuit is built of, by theme (a track's {@code theme:}; any block may be changed with the track's
 * {@code blocks:}). Block data as /setblock writes it. The road and its lines need a slab of the same look: slopes are
 * built in half blocks. Pure.
 *
 * @param ground     the field's top blocks (picked by a noise: the first most often)
 * @param under      under the field's top (one layer: the world below is empty)
 * @param road       the road (full block) and its slab
 * @param line       the dashed centre line (full) and its slab (slopes: the road's slab)
 * @param kerb       the kerb stones in corners, in turn
 * @param edge       the white edge line along straights
 * @param shoulder   the strip between the road and the barrier
 * @param walls      the barrier (walls or fences: too high to drive over), in turn every two blocks
 * @param fill       under a raised road (a bank, a ramp)
 * @param pillar     a bridge's pillars
 * @param tunnel     a tunnel's walls and roof
 * @param tunnelLamp lamps in a tunnel's roof
 * @param lamp       a lamp post: the post, the head
 * @param pit        the bottom of a jump's gap
 * @param boost      a boost pad (an arrow: glazed terracotta, turned the way the road runs)
 * @param hazard     the corner boards (outside a sharp corner), in turn
 * @param props      what stands about the field (TrackLayout#prop kinds), by how often
 * @param time       the time of day the racers see (player time: 6000 noon, 13000 dusk, 18000 night)
 */
public record TrackTheme(String id, List<String> ground, String under, String road, String roadSlab, String line, String lineSlab,
                         List<String> kerb, String edge, String shoulder, List<String> walls, String fill, String pillar,
                         String tunnel, String tunnelLamp, String lampPost, String lampHead, String pit, String boost,
                         List<String> hazard, Map<String, Integer> props, long time) {

    static final String Q = "minecraft:";

    /** The themes the content may name. */
    public static TrackTheme of(String id) {
        return switch (id) {
            case "village" -> new TrackTheme("village",
                    List.of(Q + "grass_block", Q + "grass_block", Q + "grass_block", Q + "moss_block", Q + "coarse_dirt"), Q + "dirt",
                    Q + "polished_andesite", Q + "polished_andesite_slab[type=bottom]", Q + "smooth_quartz", Q + "smooth_quartz_slab[type=bottom]",
                    List.of(Q + "red_concrete", Q + "white_concrete"), Q + "smooth_quartz", Q + "dirt_path",
                    List.of(Q + "oak_fence", Q + "oak_fence", Q + "spruce_fence", Q + "spruce_fence"), Q + "stone_bricks", Q + "stone_bricks",
                    Q + "stone_bricks", Q + "lantern[hanging=true]", Q + "dark_oak_fence", Q + "lantern",
                    Q + "water", Q + "magenta_glazed_terracotta", List.of(Q + "yellow_concrete", Q + "black_concrete"),
                    Map.of("house", 9, "tree", 12, "flowers", 14, "hay", 4, "well", 2, "bush", 8), 6000);
            case "canyon" -> new TrackTheme("canyon",
                    List.of(Q + "red_sand", Q + "red_sand", Q + "red_sand", Q + "terracotta", Q + "sand"), Q + "red_sandstone",
                    Q + "polished_tuff", Q + "polished_tuff_slab[type=bottom]", Q + "smooth_sandstone", Q + "smooth_sandstone_slab[type=bottom]",
                    List.of(Q + "orange_terracotta", Q + "white_terracotta"), Q + "smooth_sandstone", Q + "cut_red_sandstone",
                    List.of(Q + "red_sandstone_wall", Q + "red_sandstone_wall", Q + "sandstone_wall", Q + "sandstone_wall"), Q + "red_sandstone",
                    Q + "cut_red_sandstone", Q + "terracotta", Q + "lantern[hanging=true]", Q + "acacia_fence", Q + "lantern",
                    Q + "lava", Q + "orange_glazed_terracotta", List.of(Q + "yellow_terracotta", Q + "black_terracotta"),
                    Map.of("mesa", 10, "cactus", 16, "bones", 3, "deadbush", 14, "rock", 8, "arch", 2), 7000);
            case "glacier" -> new TrackTheme("glacier",
                    List.of(Q + "snow_block", Q + "snow_block", Q + "snow_block", Q + "white_concrete_powder", Q + "packed_ice"), Q + "packed_ice",
                    Q + "packed_ice", Q + "polished_diorite_slab[type=bottom]", Q + "white_concrete", Q + "smooth_quartz_slab[type=bottom]",
                    List.of(Q + "blue_concrete", Q + "white_concrete"), Q + "white_concrete", Q + "snow_block",
                    List.of(Q + "diorite_wall", Q + "diorite_wall", Q + "prismarine_wall", Q + "prismarine_wall"), Q + "packed_ice",
                    Q + "blue_ice", Q + "packed_ice", Q + "sea_lantern", Q + "spruce_fence", Q + "sea_lantern",
                    Q + "water", Q + "light_blue_glazed_terracotta", List.of(Q + "light_blue_concrete", Q + "white_concrete"),
                    Map.of("spike", 12, "pine", 16, "snowman", 5, "igloo", 2, "crystal", 8), 1000);
            case "forest" -> new TrackTheme("forest",
                    List.of(Q + "grass_block", Q + "grass_block", Q + "podzol", Q + "moss_block", Q + "grass_block"), Q + "dirt",
                    Q + "mud_bricks", Q + "mud_brick_slab[type=bottom]", Q + "mud_bricks", Q + "mud_brick_slab[type=bottom]",
                    List.of(Q + "red_concrete", Q + "white_concrete"), Q + "birch_planks", Q + "moss_block",
                    List.of(Q + "spruce_fence", Q + "spruce_fence", Q + "mossy_cobblestone_wall", Q + "mossy_cobblestone_wall"), Q + "mossy_stone_bricks",
                    Q + "spruce_log", Q + "mossy_stone_bricks", Q + "glowstone", Q + "spruce_fence", Q + "lantern",
                    Q + "water", Q + "lime_glazed_terracotta", List.of(Q + "yellow_concrete", Q + "black_concrete"),
                    Map.of("bigtree", 14, "tree", 14, "mushroom", 5, "log", 6, "flowers", 8, "bush", 12, "rock", 4), 12600);
            case "city" -> new TrackTheme("city",
                    List.of(Q + "gray_concrete", Q + "gray_concrete", Q + "polished_andesite", Q + "gray_concrete", Q + "light_gray_concrete"), Q + "stone",
                    Q + "polished_blackstone", Q + "polished_blackstone_slab[type=bottom]", Q + "yellow_concrete", Q + "smooth_sandstone_slab[type=bottom]",
                    List.of(Q + "red_concrete", Q + "white_concrete"), Q + "white_concrete", Q + "light_gray_concrete",
                    List.of(Q + "polished_blackstone_wall", Q + "polished_blackstone_wall", Q + "andesite_wall", Q + "andesite_wall"), Q + "deepslate_tiles",
                    Q + "polished_deepslate", Q + "deepslate_tiles", Q + "sea_lantern", Q + "iron_bars", Q + "end_rod[facing=down]",
                    Q + "water", Q + "purple_glazed_terracotta", List.of(Q + "yellow_concrete", Q + "black_concrete"),
                    Map.of("tower", 16, "neon", 10, "tree", 4, "bench", 4), 18000);
            default -> throw new IllegalArgumentException("알 수 없는 서킷 테마: " + id + " (village, canyon, glacier, forest, city)");
        };
    }

    /** The theme ids, in the order they are shown. */
    public static final List<String> IDS = List.of("village", "canyon", "glacier", "forest", "city");

    /** This theme with some blocks changed (a track's {@code blocks:}: road, line, edge, shoulder, fill, pillar, tunnel, pit). */
    public TrackTheme with(Map<String, String> blocks) {
        if (blocks.isEmpty()) return this;
        java.util.function.Function<String, String> g = k -> blocks.get(k);
        return new TrackTheme(id, ground, or(g.apply("under"), under), or(g.apply("road"), road), or(g.apply("road-slab"), roadSlab),
                or(g.apply("line"), line), or(g.apply("line-slab"), lineSlab), kerb, or(g.apply("edge"), edge), or(g.apply("shoulder"), shoulder),
                walls, or(g.apply("fill"), fill), or(g.apply("pillar"), pillar), or(g.apply("tunnel"), tunnel), tunnelLamp, lampPost, lampHead,
                or(g.apply("pit"), pit), boost, hazard, props, time);
    }

    private static String or(String a, String b) {
        return a == null ? b : (a.contains(":") ? a : Q + a);
    }
}
