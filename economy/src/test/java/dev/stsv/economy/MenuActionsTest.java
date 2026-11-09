package dev.stsv.economy;

import dev.stsv.core.content.ContentPack;
import dev.stsv.core.gui.HubModule;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.economy.auction.AuctionModule;
import dev.stsv.economy.market.MarketModule;
import dev.stsv.economy.market.PriceMenu;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Every button of every economy menu does something: a built-in action, a command that exists, another menu, or an action the
 * plugin's code handles. The new 판매 등록 / 경매 등록 / 입찰 menus use exactly the price picker's actions, and every title fits
 * the panel with long values in its placeholders.
 */
class MenuActionsTest {

    private static final Pattern LITERAL = Pattern.compile("\"([a-z_]+:[^\"\\s]*)\"");

    private static List<MenuSpec> menus() throws Exception {
        List<String> problems = new ArrayList<>();
        List<MenuSpec> out = MenuSpec.loadAll(List.of(new ContentPack("economy", "economy", ContentTest.PACK)), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return out;
    }

    /** "menu:x" style strings in the plugin's code: what its handlers compare actions with. */
    private static Set<String> codeLiterals() throws Exception {
        Set<String> out = new HashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = LITERAL.matcher(Files.readString(f));
                while (m.find()) out.add(m.group(1));
            }
        }
        return out;
    }

    private static Set<String> commands() throws Exception {
        Set<String> out = new HashSet<>();
        for (YamlConfiguration y : List.of(yml("/plugin.yml"), YamlConfiguration.loadConfiguration(Path.of("../plugin/src/main/resources/plugin.yml").toFile()))) {
            ConfigurationSection cs = y.getConfigurationSection("commands");
            for (String name : cs.getKeys(false)) {
                out.add(name);
                out.addAll(cs.getStringList(name + ".aliases"));
            }
        }
        return out;
    }

    private static YamlConfiguration yml(String name) throws Exception {
        try (var in = MenuActionsTest.class.getResourceAsStream(name)) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void everyButtonActionResolves() throws Exception {
        List<MenuSpec> menus = menus();
        Set<String> ids = new HashSet<>();
        for (MenuSpec m : menus) ids.add(m.id());
        Set<String> literals = codeLiterals();
        Set<String> commands = commands();
        List<String> problems = new ArrayList<>();
        for (MenuSpec m : menus) {
            for (MenuSpec.Button b : m.buttons().values()) {
                String a = b.action();
                String where = m.id() + " " + b.key() + " '" + a + "'";
                if (a.isEmpty() || a.equals("close") || a.equals("page:prev") || a.equals("page:next")) continue;
                if (a.startsWith("menu:")) {
                    String id = a.substring(5).contains(":") ? a.substring(5) : "economy:" + a.substring(5);
                    if (!ids.contains(id)) problems.add(where + ": no such menu");
                } else if (a.startsWith("command:") || a.startsWith("hub:")) {
                    List<String> cmds = HubModule.commands(a);
                    if (cmds == null || (a.startsWith("hub:") && !m.id().equals("economy:hub"))) problems.add(where + ": hub actions only work in the hub");
                    else for (String c : cmds) if (!commands.contains(c.split(" ")[0])) problems.add(where + ": no command /" + c);
                } else if (!literals.contains(a) && literals.stream().noneMatch(l -> l.endsWith(":") && a.startsWith(l))) {
                    problems.add(where + ": nothing in the code handles it");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void thePriceMenusUseThePricePickersActions() throws Exception {
        Map<String, Set<String>> handled = new HashMap<>();
        handled.put("economy:market", MarketModule.ACTIONS);
        handled.put("economy:auction", AuctionModule.ACTIONS);
        handled.put("economy:market_sell", PriceMenu.ACTIONS);
        handled.put("economy:auction_sell", PriceMenu.ACTIONS);
        handled.put("economy:auction_bid", PriceMenu.ACTIONS);
        List<String> problems = new ArrayList<>();
        for (MenuSpec m : menus()) {
            Set<String> ok = handled.get(m.id());
            if (ok == null) continue;
            for (MenuSpec.Button b : m.buttons().values()) {
                String a = b.action();
                if (a.isEmpty() || a.equals("close") || a.startsWith("page:") || a.startsWith("command:")) continue;
                if (!ok.contains(a)) problems.add(m.id() + " " + b.key() + ": " + a);
            }
            // every price menu: the item on top, five steps, a typed amount, the price, back and confirm
            if (ok == PriceMenu.ACTIONS) {
                Set<String> used = new HashSet<>();
                for (MenuSpec.Button b : m.buttons().values()) used.add(b.action());
                for (String need : List.of("price:step:1", "price:step:10", "price:step:100", "price:step:1000", "price:step:10000",
                        "price:type", "price:confirm", "price:back")) {
                    if (!used.contains(need)) problems.add(m.id() + " lacks " + need);
                }
                assertEquals(1, m.slots('S').size(), m.id() + " shows the item");
                for (MenuSpec.Button b : m.buttons().values()) {
                    if (!b.action().equals("close") && b.on().lore().isEmpty()) problems.add(m.id() + " " + b.key() + " has no lore");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        // the bid menu has the quick bids
        MenuSpec bid = menus().stream().filter(m -> m.id().equals("economy:auction_bid")).findFirst().orElseThrow();
        Set<String> used = new HashSet<>();
        for (MenuSpec.Button b : bid.buttons().values()) used.add(b.action());
        assertTrue(used.containsAll(List.of("price:min", "price:raise:5", "price:raise:10", "price:type")), used.toString());
    }

    /** Long but possible values for the placeholders of the titles. */
    static final Map<String, String> SAMPLES = Map.ofEntries(
            Map.entry("world", "야생 지옥"), Map.entry("mode", "검색: 다이아몬드곡괭이…"), Map.entry("count", "999"),
            Map.entry("tab", "원목·전리품"), Map.entry("have", "100"), Map.entry("all", "100"),
            Map.entry("machine", "가공대"), Map.entry("lane_text", " · 4번 줄"),
            Map.entry("daily_done", "3"), Map.entry("daily_count", "3"), Map.entry("weekly_done", "3"), Map.entry("weekly_count", "3"),
            Map.entry("day", "1234"), Map.entry("category", "보석·금속"), Map.entry("sort", "등락률순"),
            Map.entry("a", "Abcdefg…"), Map.entry("b", "Abcdefg…"), Map.entry("id", "9999"),
            // the stock window: the chart glyphs take no room, then the company, price and change
            Map.entry("graph", ""), Map.entry("name", "한빛제약"), Map.entry("price", "12,345 G"), Map.entry("change", "▲ 12.3%"));

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
            if (m.title().contains("dark_gray")) problems.add(m.id() + ": dark grey is unreadable in a title");
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
