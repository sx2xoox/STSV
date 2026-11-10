package dev.stsv.core.gui;

import dev.stsv.core.content.ContentPack;
import dev.stsv.core.gui.menu.MenuSpec;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core's menus (content/stsv/menus): every button does something the code handles, every title fits the panel, the hub
 * keeps 'my info' in the middle of its top row, and the drawn player inventory sits where the client puts its items.
 */
class MenuActionsTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");
    private static final Pattern LITERAL = Pattern.compile("\"([a-z_]+:[^\"\\s]*)\"");

    /** Info tiles whose action names them but does nothing on a click (an empty action is the usual way). */
    private static final Set<String> DISPLAY_ONLY = Set.of("wardrobe:info");

    private static List<MenuSpec> menus() throws Exception {
        List<String> problems = new ArrayList<>();
        List<MenuSpec> out = MenuSpec.loadAll(List.of(new ContentPack("stsv", "stsv", PACK)), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return out;
    }

    @Test
    void everyButtonActionResolves() throws Exception {
        Set<String> literals = new HashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = LITERAL.matcher(Files.readString(f));
                while (m.find()) literals.add(m.group(1));
            }
        }
        Set<String> commands = HubTest.commands(HubTest.resource(getClass(), "/plugin.yml"));
        List<MenuSpec> menus = menus();
        Set<String> ids = new HashSet<>();
        for (MenuSpec m : menus) ids.add(m.id());
        List<String> problems = new ArrayList<>();
        for (MenuSpec m : menus) {
            for (MenuSpec.Button b : m.buttons().values()) {
                String a = b.action();
                String where = m.id() + " " + b.key() + " '" + a + "'";
                if (a.isEmpty() || a.equals("close") || a.equals("page:prev") || a.equals("page:next")) continue;
                if (DISPLAY_ONLY.contains(a)) continue;
                if (a.startsWith("menu:")) {
                    if (!ids.contains(a.substring(5).contains(":") ? a.substring(5) : "stsv:" + a.substring(5))) problems.add(where + ": no such menu");
                } else if (a.startsWith("command:") || a.startsWith("hub:")) {
                    List<String> cmds = HubModule.commands(a);
                    if (cmds == null || (a.startsWith("hub:") && !m.id().equals(HubModule.MENU))) problems.add(where + ": hub actions only work in the hub");
                    else for (String c : cmds) if (!commands.contains(c.split(" ")[0])) problems.add(where + ": no command /" + c);
                } else if (!literals.contains(a) && literals.stream().noneMatch(l -> l.endsWith(":") && a.startsWith(l))) {
                    problems.add(where + ": nothing in the code handles it");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theHubHasMyInfoInTheMiddleOfASymmetricTopRow() throws Exception {
        MenuSpec hub = menus().stream().filter(m -> m.id().equals(HubModule.MENU)).findFirst().orElseThrow();
        String top = hub.layout().getFirst();
        assertEquals("PLAYER_HEAD", hub.buttons().get(top.charAt(4)).on().material(), "my info in the middle");
        for (String row : hub.layout()) {
            String used = row.replaceAll("[^.]", "#");
            assertEquals(new StringBuilder(used).reverse().toString(), used, row + " is symmetric");
        }
        // 치장 no longer shows the wardrobe's 'take off' hanger
        for (MenuSpec.Button b : hub.buttons().values()) {
            if (b.action().equals("command:치장")) assertTrue(!String.valueOf(b.on().icon()).endsWith("take_off"), "치장 icon");
        }
    }

    static final Map<String, String> SAMPLES = Map.ofEntries(
            Map.entry("world", "야생 지옥"), Map.entry("done", "999"), Map.entry("total", "999"), Map.entry("points", "99,999"),
            Map.entry("category", "거래·투자"), Map.entry("category_done", "99"), Map.entry("category_total", "99"),
            Map.entry("owned", "999"), Map.entry("tab", "처치 효과"), Map.entry("current", "#ffb347 → #ff5e8a"), Map.entry("title", "<gold>[전설의 강태공]</gold> Abcdefghijklmnop"));

    @Test
    void everyTitleFitsThePanel() throws Exception {
        List<String> problems = new ArrayList<>();
        Pattern ph = Pattern.compile("\\{([a-z_]+)}");
        for (MenuSpec m : menus()) {
            Matcher x = ph.matcher(m.title());
            StringBuilder filled = new StringBuilder();
            while (x.find()) {
                String v = SAMPLES.get(x.group(1));
                if (v == null) problems.add(m.id() + ": no sample for {" + x.group(1) + "}");
                x.appendReplacement(filled, Matcher.quoteReplacement(v == null ? "" : v));
            }
            x.appendTail(filled);
            int w = MenuSpec.titleWidth(filled.toString());
            if (w > MenuSpec.TITLE_ROOM) problems.add(m.id() + ": title '" + filled + "' is about " + w + " px (room " + MenuSpec.TITLE_ROOM + ")");
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertEquals(9 * 2 + 4 + 6, MenuSpec.titleWidth("<gold>치장</gold> A"));
    }

    /** QA UI-1: the client draws the player's items at y = 31 + 18 rows, so their slot frames start 1 px above. */
    @Test
    void thePlayersInventoryIsDrawnWhereTheItemsAre() throws Exception {
        for (MenuSpec m : menus()) {
            if (m.background() == null) continue;
            BufferedImage im = ImageIO.read(PACK.resolve("assets/stsv/textures/" + m.background().getKey() + ".png").toFile());
            int frame = 30 + 18 * m.rows();
            // the frame's shadowed top row at x 8..23, the slot's inside just below
            int top = im.getRGB(12, frame), above = im.getRGB(12, frame - 1), inside = im.getRGB(12, frame + 4);
            assertTrue(top != above && top != inside, m.id() + ": a slot frame starts at y " + frame);
            int hotbar = frame + 58;
            assertTrue(im.getRGB(12, hotbar) != im.getRGB(12, hotbar - 1), m.id() + ": the hotbar frame starts at y " + hotbar);
        }
    }
}
