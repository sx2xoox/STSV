package dev.stsv.economy.guide;

import dev.stsv.core.content.ContentPack;
import dev.stsv.core.gui.menu.MenuSpec;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 길라잡이: the topic files parse and name real menus, commands, items and stations; the steps advance only on their own goal;
 * the first-completion money of all topics together is about 30,000 G; the finisher's cosmetic is a common one they lack;
 * the world's plan has no overlapping stations.
 */
class GuideTest {

    static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    static final Path PACK = CONTENT.resolve("economy");
    static final Path CORE = CONTENT.resolve("../../content/stsv").normalize();

    static Set<String> stations() {
        Set<String> out = new HashSet<>();
        for (GuideLayout.Station s : GuideLayout.stations()) out.add(s.id());
        return out;
    }

    static List<GuideSpec.Topic> topics(List<String> problems) throws Exception {
        List<GuideSpec.Topic> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(PACK.resolve("guide/topics"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                String id = f.getFileName().toString().replace(".yml", "");
                GuideSpec.Topic t = GuideSpec.parse(id, YamlConfiguration.loadConfiguration(f.toFile()), stations(), problems);
                if (t != null) out.add(t);
            }
        }
        return GuideSpec.sorted(out);
    }

    static Set<String> keys(Path dir, String ns) throws Exception {
        Set<String> ids = new HashSet<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).toList())
                for (String k : YamlConfiguration.loadConfiguration(f.toFile()).getKeys(false)) ids.add(ns + ":" + k);
        }
        return ids;
    }

    static Set<String> commands() throws Exception {
        Set<String> out = new HashSet<>();
        YamlConfiguration eco;
        try (var in = GuideTest.class.getResourceAsStream("/plugin.yml")) {
            eco = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (YamlConfiguration y : List.of(eco, YamlConfiguration.loadConfiguration(Path.of("../plugin/src/main/resources/plugin.yml").toFile()))) {
            ConfigurationSection cs = y.getConfigurationSection("commands");
            out.addAll(cs.getKeys(false));
        }
        return out;
    }

    @Test
    void everyTopicParsesAndNamesRealThings() throws Exception {
        List<String> problems = new ArrayList<>();
        List<GuideSpec.Topic> topics = topics(problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertEquals(13, topics.size(), "13 topics");
        Set<String> menus = new HashSet<>();
        List<String> menuProblems = new ArrayList<>();
        for (MenuSpec m : MenuSpec.loadAll(List.of(new ContentPack("economy", "economy", PACK), new ContentPack("stsv", "stsv", CORE)), menuProblems)) menus.add(m.id());
        assertTrue(menus.contains(GuideModule.MENU), "the picker menu");
        Set<String> items = keys(PACK.resolve("items"), "economy");
        items.addAll(keys(CORE.resolve("items"), "stsv"));
        Set<String> cmds = commands();
        assertTrue(cmds.contains("길라잡이"));
        Set<Integer> orders = new HashSet<>();
        Set<String> usedStations = new HashSet<>();
        for (GuideSpec.Topic t : topics) {
            assertTrue(orders.add(t.order()), t.id() + ": order " + t.order() + " twice");
            assertTrue(usedStations.add(t.station()), t.id() + ": station " + t.station() + " twice");
            assertTrue(t.steps().size() >= 3 && t.steps().size() <= 6, t.id() + ": 3-6 steps, has " + t.steps().size());
            List<GuideSpec.Item> all = new ArrayList<>(t.items());
            all.add(new GuideSpec.Item(GuideSpec.normalize(t.icon()), 1));
            GuideLayout.Station st = GuideLayout.station(t.station());
            for (GuideSpec.Step s : t.steps()) {
                all.addAll(s.give());
                Map<String, String> p = s.goal().params();
                switch (s.goal().type()) {
                    case "menu" -> assertTrue(menus.contains(p.get("menu")), t.id() + ": no menu " + p.get("menu"));
                    case "command" -> assertTrue(cmds.contains(p.get("command")), t.id() + ": no command /" + p.get("command"));
                    case "drive_stop" -> assertNotNull(st.regions().get(p.get("zone")), t.id() + ": no zone " + p.get("zone"));
                    case "place_block" -> assertNotNull(st.regions().get(p.get("region")), t.id() + ": no region " + p.get("region"));
                    case "interact" -> assertNotNull(st.features().get(p.get("feature")), t.id() + ": no feature " + p.get("feature"));
                    case "have_item" -> all.add(new GuideSpec.Item(GuideSpec.normalize(p.get("item")), 1));
                    default -> { }
                }
                if (s.at() != null) {
                    double[] xz = st.point(s.at()[0], s.at()[2]);
                    assertTrue(st.onPad(xz[0], xz[1]), t.id() + ": the arrow points off the station");
                }
            }
            for (GuideSpec.Item i : all) {
                boolean ok = i.id().startsWith("minecraft:") ? Material.matchMaterial(i.id()) != null : items.contains(i.id());
                assertTrue(ok, t.id() + ": no item " + i.id());
            }
        }
    }

    /** The owner's budget: all first completions together about 30,000 G, each topic 2,000 - 2,800 G (longer ones more). */
    @Test
    void rewardsTotalAboutThirtyThousand() throws Exception {
        List<String> problems = new ArrayList<>();
        List<GuideSpec.Topic> topics = topics(problems);
        long total = GuideSpec.totalMoney(topics);
        assertTrue(total >= 29_000 && total <= 31_000, "total " + total);
        for (GuideSpec.Topic t : topics) {
            assertTrue(t.reward().money() >= 2_000 && t.reward().money() <= 2_800, t.id() + " pays " + t.reward().money());
            assertTrue(t.reward().xp() >= 0 && t.reward().xp() <= 50, t.id() + ": small job XP only");
        }
    }

    private static GuideSpec.Topic sample(List<String> problems) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("name", "시험");
        y.set("station", "jobs");
        y.set("reward.money", 1000);
        y.set("steps", List.of(
                Map.of("text", "메뉴 열기", "goal", Map.of("type", "menu", "menu", "economy:shop")),
                Map.of("text", "세 번 캐기", "goal", Map.of("type", "break_ore", "count", 3)),
                Map.of("text", "명령어", "goal", Map.of("type", "command", "command", "땅", "args", "보기"))));
        return GuideSpec.parse("test", y, stations(), problems);
    }

    @Test
    void stepsAdvanceOnlyOnTheirOwnGoal() {
        List<String> problems = new ArrayList<>();
        GuideSpec.Topic t = sample(problems);
        assertTrue(problems.isEmpty(), problems.toString());
        GuideProgress g = new GuideProgress(t);
        assertEquals("1/3", g.position());
        // the second step's goal does nothing while on the first
        assertEquals(GuideProgress.Result.NONE, g.signal("break_ore", Map.of()));
        assertEquals(GuideProgress.Result.NONE, g.signal("menu", Map.of("menu", "economy:market")));
        assertEquals(GuideProgress.Result.STEP_DONE, g.signal("menu", Map.of("menu", "economy:shop")));
        assertEquals(1, g.step());
        assertEquals(GuideProgress.Result.COUNTED, g.signal("break_ore", Map.of("item", "minecraft:iron_ore")));
        assertEquals(GuideProgress.Result.COUNTED, g.signal("break_ore", Map.of()));
        assertEquals(2, g.count());
        assertEquals(GuideProgress.Result.STEP_DONE, g.signal("break_ore", Map.of()));
        assertEquals(0, g.count());
        // a command: its name and the argument prefix
        assertEquals(GuideProgress.Result.NONE, g.signal("command", Map.of("command", "땅", "args", "")));
        assertEquals(GuideProgress.Result.NONE, g.signal("command", Map.of("command", "땅", "args", "보기싫어")));
        assertEquals(GuideProgress.Result.NONE, g.signal("command", Map.of("command", "땅땅", "args", "보기")));
        assertEquals(GuideProgress.Result.TOPIC_DONE, g.signal("command", Map.of("command", "땅", "args", "보기 extra")));
        assertTrue(g.done());
        assertNull(g.current());
        assertEquals(GuideProgress.Result.NONE, g.signal("menu", Map.of("menu", "economy:shop")));
    }

    @Test
    void goalsMatchIdsAndArguments() {
        GuideSpec.Goal item = new GuideSpec.Goal("have_item", Map.of("item", "wheat"), 1);
        assertTrue(item.matches("have_item", Map.of("item", "minecraft:wheat")));
        assertFalse(item.matches("have_item", Map.of("item", "minecraft:wheat_seeds")));
        assertFalse(item.matches("have_item", Map.of()));
        GuideSpec.Goal any = new GuideSpec.Goal("command", Map.of("command", "시세", "args", "*"), 1);
        assertTrue(any.matches("command", Map.of("command", "시세", "args", "루비")));
        assertFalse(any.matches("command", Map.of("command", "시세", "args", "")));
        GuideSpec.Goal bare = new GuideSpec.Goal("command", Map.of("command", "땅"), 1);
        assertTrue(bare.matches("command", Map.of("command", "땅", "args", "보기")));
        GuideSpec.Goal none = new GuideSpec.Goal("bus_rent", Map.of(), 1);
        assertTrue(none.matches("bus_rent", Map.of()));
        assertFalse(none.matches("bus_return", Map.of()));
    }

    @Test
    void badTopicsAreReported() {
        List<String> problems = new ArrayList<>();
        YamlConfiguration y = new YamlConfiguration();
        y.set("name", "깨짐");
        y.set("station", "nowhere");
        y.set("steps", List.of(
                Map.of("text", "x", "goal", Map.of("type", "fly")),
                Map.of("text", "y", "goal", Map.of("type", "menu")),
                Map.of("text", "z", "goal", Map.of("type", "drive_stop", "zone", "a"), "then", "teleport")));
        GuideSpec.parse("bad", y, stations(), problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("station")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("goal.type 'fly'")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("menu 가 필요")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("then 'teleport'")), problems.toString());
    }

    @Test
    void theFinisherGetsACommonCosmeticTheyLack() {
        List<String> commons = List.of("stsv:a", "stsv:b", "stsv:c", "stsv:d");
        Set<String> owned = Set.of("stsv:a", "stsv:c", "stsv:legend");
        Set<String> seen = new HashSet<>();
        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            String got = GuideRewards.pickCosmetic(commons, owned, random).orElseThrow();
            assertFalse(owned.contains(got), got);
            assertTrue(commons.contains(got), got);
            seen.add(got);
        }
        assertEquals(Set.of("stsv:b", "stsv:d"), seen, "both missing ones come up");
        assertTrue(GuideRewards.pickCosmetic(commons, Set.copyOf(commons), new Random(1)).isEmpty());
        assertTrue(GuideRewards.pickCosmetic(List.of(), Set.of(), new Random(1)).isEmpty());
    }

    @Test
    void allDoneAndTheStoredList() {
        Set<String> done = GuideRewards.parseDone("jobs, farming,,");
        assertEquals(Set.of("jobs", "farming"), done);
        assertEquals("jobs,farming", GuideRewards.formatDone(done));
        assertTrue(GuideRewards.parseDone(null).isEmpty());
        assertFalse(GuideRewards.allDone(List.of("jobs", "farming", "bus"), done));
        done.add("bus");
        assertTrue(GuideRewards.allDone(List.of("jobs", "farming", "bus"), done));
        assertFalse(GuideRewards.allDone(List.of(), done));
    }

    @Test
    void theWorldPlanHolds() {
        List<GuideLayout.Station> st = GuideLayout.stations();
        // no two pads share a block, and none touches the plaza or the avenue
        Set<Long> used = new HashSet<>();
        for (GuideLayout.Station s : st) {
            for (int lx = -s.half(); lx <= s.half(); lx++) {
                for (int lz = 0; lz < s.depth(); lz++) {
                    int[] c = s.block(lx, lz);
                    assertTrue(used.add(((long) c[0] << 32) ^ (c[1] & 0xffffffffL)), s.id() + " overlaps at " + c[0] + "," + c[1]);
                    boolean avenue = Math.abs(c[1]) <= GuideLayout.AVENUE_Z && Math.abs(c[0]) <= GuideLayout.AVENUE_X;
                    boolean plaza = Math.abs(c[0]) <= GuideLayout.PLAZA && Math.abs(c[1]) <= GuideLayout.PLAZA;
                    assertFalse(avenue || plaza, s.id() + " on the avenue or plaza at " + c[0] + "," + c[1]);
                    // block -> local -> block
                    double[] l = s.local(c[0] + 0.5, c[1] + 0.5);
                    assertEquals(lx, l[0], 1e-9);
                    assertEquals(lz, l[1], 1e-9);
                }
            }
            // its start, helper, regions and features are on its own pad
            for (double[] p : List.of(s.start(), s.helper())) assertTrue(s.onPad(p[0], p[1]), s.id());
            for (int[] r : s.regions().values()) {
                double[] a = s.point(r[0], r[1]), b = s.point(r[2], r[3]);
                assertTrue(s.onPad(a[0], a[1]) && s.onPad(b[0], b[1]), s.id() + " region off its pad");
            }
            for (int[] f : s.features().values()) {
                double[] a = s.point(f[0], f[2]);
                assertTrue(s.onPad(a[0], a[1]), s.id() + " feature off its pad");
            }
            assertEquals(s, GuideLayout.at(s.start()[0], s.start()[1]));
            // facing inward: a step forward goes deeper
            double yaw = Math.toRadians(s.yaw());
            double[] ahead = s.local(s.start()[0] - Math.sin(yaw), s.start()[1] + Math.cos(yaw));
            assertEquals(3, ahead[1], 1e-9, s.id() + " faces inward");
        }
        assertNull(GuideLayout.at(0.5, 0.5), "the plaza is no station");
        // the bus starts beside stop A and drives along the front into it; the truck's spot is on its lane
        GuideLayout.Station bus = GuideLayout.station("bus");
        int[] spot = bus.features().get("bus");
        double[] at = bus.point(spot[0], spot[2]);
        assertFalse(bus.inside(bus.regions().get("stop_a"), at[0], at[1]));
        double yaw = Math.toRadians(bus.yawRight());
        double[] ahead = bus.point(spot[0] + 3, spot[2]);
        assertEquals(ahead[0], at[0] - Math.sin(yaw) * 3, 1e-9);
        assertEquals(ahead[1], at[1] + Math.cos(yaw) * 3, 1e-9);
        assertTrue(bus.inside(bus.regions().get("stop_a"), ahead[0], ahead[1]));
        GuideLayout.Station dock = GuideLayout.station("dock");
        int[] truck = dock.features().get("truck");
        double[] t = dock.point(truck[0], truck[2]);
        assertTrue(dock.inside(dock.regions().get("lane"), t[0], t[1]));
        // the ore wall: only ores
        for (int lx = -3; lx <= 3; lx++) for (int ly = 0; ly <= 2; ly++) assertTrue(GuideLayout.ore(lx, ly).endsWith("_ORE"));
    }
}
