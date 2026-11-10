package dev.stsv.core.furniture;

import com.google.gson.JsonParser;
import dev.stsv.core.content.FurnitureSpec;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayViewTest {

    private static FurnitureSpec spec(List<int[]> cells, String behavior, List<FurnitureSpec.Part> parts, Vector3f translation, Vector3f scale) {
        return new FurnitureSpec(cells, true, FurnitureSpec.Rotation.CARDINAL, behavior, translation, scale, -1, List.of(), parts);
    }

    private static FurnitureSpec spec(int[]... cells) {
        return spec(List.of(cells), null, List.of(), new Vector3f(), new Vector3f(1));
    }

    private static YamlConfiguration bundledConfig() throws Exception {
        try (var in = DisplayViewTest.class.getResourceAsStream("/config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void defaultsAreShortForCropsAndSmallPropsLongerForBigThings() throws Exception {
        DisplayView.Settings s = DisplayView.Settings.of(bundledConfig());
        assertEquals(32, s.blocks(DisplayView.Kind.CROP));
        assertEquals(32, s.blocks(DisplayView.Kind.SMALL));
        assertEquals(48, s.blocks(DisplayView.Kind.LARGE));
        assertEquals(64, s.blocks(DisplayView.Kind.MACHINE));
        // view_range is in units of 64 blocks
        assertEquals(0.5f, s.viewRange(DisplayView.Kind.CROP));
        assertEquals(0.75f, s.viewRange(DisplayView.Kind.LARGE));
        assertEquals(1.0f, s.viewRange(DisplayView.Kind.MACHINE));
        assertTrue(s.culling());
        assertEquals(32, s.animateRange());
    }

    @Test
    void oldMultiplierStillScalesEverythingAndZeroMeansVanilla() {
        DisplayView.Settings s = new DisplayView.Settings(32, 0, 48, -5, 1.5, true, 0);
        assertEquals(48, s.blocks(DisplayView.Kind.CROP));
        assertEquals(96, s.blocks(DisplayView.Kind.SMALL));     // 0 -> vanilla 64, x 1.5
        assertEquals(96, s.blocks(DisplayView.Kind.MACHINE));
        assertEquals(0.75f, s.viewRange(DisplayView.Kind.CROP));
        // a broken multiplier (0) is ignored rather than hiding everything
        assertEquals(32, new DisplayView.Settings(32, 32, 48, 64, 0, true, 32).blocks(DisplayView.Kind.CROP));
    }

    @Test
    void kinds() {
        FurnitureSpec.Part wheel = new FurnitureSpec.Part(NamespacedKey.fromString("economy:model/gem_bench_wheel_0"), new Vector3f(8, 24, 8), "z", 40);
        assertEquals(DisplayView.Kind.SMALL, DisplayView.kindOf(spec(new int[]{0, 0, 0})));
        assertEquals(DisplayView.Kind.LARGE, DisplayView.kindOf(spec(new int[]{0, 0, 0}, new int[]{1, 0, 0})));
        assertEquals(DisplayView.Kind.MACHINE, DisplayView.kindOf(spec(List.of(new int[]{0, 0, 0}, new int[]{1, 0, 0}), "economy:machine/forge",
                List.of(), new Vector3f(), new Vector3f(1))));
        assertEquals(DisplayView.Kind.MACHINE, DisplayView.kindOf(spec(List.of(new int[]{0, 0, 0}), null, List.of(wheel), new Vector3f(), new Vector3f(1))));
        assertEquals(DisplayView.Kind.LARGE, DisplayView.kindOf(null));
    }

    /** Every corner of every cell, turned to any yaw and moved / scaled like the display does, lies in the culling box. */
    @Test
    void furnitureBoxHoldsTheCellsAtAnyYaw() {
        List<FurnitureSpec> specs = List.of(
                spec(new int[]{0, 0, 0}),
                spec(new int[]{-1, 0, 0}, new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{-1, 1, 0}, new int[]{0, 1, 0}, new int[]{1, 1, 0}),
                spec(new int[]{0, 0, 0}, new int[]{1, 0, 1}, new int[]{0, 2, 0}, new int[]{1, 2, 1}),
                spec(new int[]{0, 0, 0}, new int[]{0, 0, -1}, new int[]{0, 0, 1}, new int[]{1, 0, 0}, new int[]{-1, 0, 0}),
                // a torii: shown 1.25x, moved half a block sideways and one up
                spec(List.of(new int[]{-1, 0, 0}, new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{-1, 3, 0}, new int[]{1, 3, 0}), null, List.of(),
                        new Vector3f(-0.5f, 1, 0), new Vector3f(1.25f)));
        for (FurnitureSpec s : specs) {
            float[] box = DisplayView.furnitureBox(s);
            double half = box[0] / 2.0, height = box[1];
            double scale = Math.max(1, s.scale().x);
            Vector3f t = s.translation();
            for (int deg = 0; deg < 360; deg += 15) {
                double r = Math.toRadians(deg);
                for (int[] c : s.cells()) {
                    for (double cx : new double[]{c[0] - 0.5, c[0] + 0.5}) {
                        for (double cz : new double[]{c[2] - 0.5, c[2] + 0.5}) {
                            for (double cy : new double[]{c[1] - 0.5, c[1] + 0.5}) {
                                // model point (relative to the anchor centre), scaled and moved, then turned with the entity
                                double mx = cx * scale + t.x, my = cy * scale + t.y, mz = cz * scale + t.z;
                                double wx = mx * Math.cos(r) - mz * Math.sin(r), wz = mx * Math.sin(r) + mz * Math.cos(r);
                                assertTrue(Math.abs(wx) <= half && Math.abs(wz) <= half, "x/z " + deg + " " + List.of(box[0], box[1]));
                                // the client widens the box by 0.5 each way: the lower half of the anchor cell is covered by that
                                assertTrue(my <= height && my >= -0.5 - 1e-9, "y " + my + " " + height);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void noBoxBelowTheAnchorAndNoneForNoCells() {
        assertArrayEquals(new float[]{0, 0}, DisplayView.furnitureBox(spec(new int[]{0, 0, 0}, new int[]{0, -1, 0})));
        assertArrayEquals(new float[]{0, 0}, DisplayView.furnitureBox(spec(List.of(), null, List.of(), new Vector3f(), new Vector3f(1))));
        assertArrayEquals(new float[]{0, 0}, DisplayView.furnitureBox(null));
        float[] one = DisplayView.furnitureBox(spec(new int[]{0, 0, 0}));
        assertEquals(2 * (Math.hypot(0.5, 0.5) + DisplayView.MARGIN), one[0], 1e-5);
        assertEquals(0.5 + DisplayView.MARGIN, one[1], 1e-5);
    }

    @Test
    void cropBoxCoversTallPlants() {
        float[] low = DisplayView.cropBox(0.5);
        assertTrue(low[0] >= 1 && low[1] >= 1, "a crop model fills its block, whatever its listed height");
        // corn: 1.8 blocks tall from the block's floor; the display stands half a block up
        float[] corn = DisplayView.cropBox(1.8);
        assertTrue(corn[1] >= 1.8 - 0.5);
    }

    @Test
    void animationOnlyNearPlayers() {
        List<double[]> players = List.of(new double[]{0, 64, 0}, new double[]{100, 64, 100});
        assertTrue(DisplayView.anyoneWithin(10, 64, 10, players, 32));
        assertFalse(DisplayView.anyoneWithin(50, 64, 50, players, 32));
        assertTrue(DisplayView.anyoneWithin(32, 64, 0, players, 32));      // on the edge counts
        assertFalse(DisplayView.anyoneWithin(0, 64, 0, List.of(), 32));    // nobody online
        assertTrue(DisplayView.anyoneWithin(0, 64, 0, List.of(), 0));      // 0 = always
    }

    /** A recording ItemDisplay: only the view range and culling box are used. */
    private static ItemDisplay fake(Map<String, Object> state, List<String> sets) {
        return (ItemDisplay) Proxy.newProxyInstance(ItemDisplay.class.getClassLoader(), new Class<?>[]{ItemDisplay.class}, (proxy, m, args) -> {
            String n = m.getName();
            return switch (n) {
                case "getViewRange" -> state.getOrDefault("range", 1f);
                case "getDisplayWidth" -> state.getOrDefault("w", 0f);
                case "getDisplayHeight" -> state.getOrDefault("h", 0f);
                case "setViewRange" -> {
                    sets.add(n);
                    state.put("range", args[0]);
                    yield null;
                }
                case "setDisplayWidth" -> {
                    sets.add(n);
                    state.put("w", args[0]);
                    yield null;
                }
                case "setDisplayHeight" -> {
                    sets.add(n);
                    state.put("h", args[0]);
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(n);
            };
        });
    }

    @Test
    void applySendsOnlyWhatChanged() {
        Map<String, Object> state = new HashMap<>();
        List<String> sets = new ArrayList<>();
        ItemDisplay d = fake(state, sets);
        DisplayView.apply(d, 0.5f, new float[]{2f, 1f}, true);
        assertEquals(List.of("setViewRange", "setDisplayWidth", "setDisplayHeight"), sets);
        sets.clear();
        DisplayView.apply(d, 0.5f, new float[]{2f, 1f}, true);   // a chunk loaded again: nothing to send
        assertEquals(List.of(), sets);
        DisplayView.apply(d, 0.5f, new float[]{2f, 1f}, false);  // culling switched off: the box goes back to 0 x 0
        assertEquals(0f, state.get("w"));
        assertEquals(0f, state.get("h"));
    }

    @Test
    void censusCountsWhatAClientDraws() {
        DisplayCensus.Tally t = new DisplayCensus.Tally();
        for (int i = 0; i < 100; i++) t.add(DisplayCensus.Group.CROP, i, 0.5f, true, 8);   // crops at 0..99 blocks, drawn within 32
        t.add(DisplayCensus.Group.MACHINE, 60, 1f, true, 800);
        t.add(DisplayCensus.Group.PART, 60, 1f, false, 150);
        t.add(DisplayCensus.Group.CROP_HIT, 1, 0, false, 0);
        assertEquals(100, t.get(DisplayCensus.Group.CROP).all);
        assertEquals(33, t.get(DisplayCensus.Group.CROP).inRange);
        assertEquals(33 * 8, t.get(DisplayCensus.Group.CROP).faces);
        assertEquals(1, t.get(DisplayCensus.Group.PART).noBox);
        assertEquals(1, t.get(DisplayCensus.Group.CROP_HIT).all);
        assertEquals(0, t.get(DisplayCensus.Group.CROP_HIT).inRange);       // click boxes are never drawn
        assertArrayEquals(new long[]{102, 35, 1, 33 * 8 + 950}, t.drawnTotal());
        assertEquals(0, t.get(DisplayCensus.Group.BOARD).all);
    }

    @Test
    void facesOfAModel() {
        var model = JsonParser.parseString("{\"elements\":[{\"faces\":{\"north\":{},\"south\":{}}},{\"faces\":{\"up\":{}}},{}]}").getAsJsonObject();
        assertEquals(3, DisplayCensus.faces(model));
        assertEquals(0, DisplayCensus.faces(JsonParser.parseString("{}").getAsJsonObject()));
    }
}
