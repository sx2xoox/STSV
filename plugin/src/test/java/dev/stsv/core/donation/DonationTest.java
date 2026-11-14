package dev.stsv.core.donation;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import dev.stsv.core.donation.DonationStore.Kind;
import dev.stsv.core.donation.DonationStore.Outcome;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The donation shop: its books (DonationStore), its catalogue (DonationShop), the shipped file, the command. */
class DonationTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final Path PACK = Path.of("..", "content", "stsv");

    private static DonationStore open(Db db) {
        DonationStore s = new DonationStore(db);
        s.init();
        return s;
    }

    @Test
    void theBooks(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        DonationStore s = open(db);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals(DonationStore.Account.NONE, s.account(a).get(), "nobody has anything to begin with");

        // an administrator credits a donation
        var g = s.change(a, "Steve", 500, Kind.GRANT, null, "계좌이체 11/14", "admin", false, 1000).get();
        assertEquals(Outcome.DONE, g.outcome());
        assertEquals(500, g.balance());
        assertTrue(g.entry() > 0);
        // a purchase: the price off, written undelivered
        var buy = s.change(a, "Steve", -300, Kind.BUY, "hyper_car", "하이퍼카", "Steve", false, 2000).get();
        assertEquals(Outcome.DONE, buy.outcome());
        assertEquals(200, buy.balance());
        assertEquals(List.of(buy.entry()), s.undelivered(a).get().stream().map(DonationStore.Entry::id).toList());
        // more than there is: refused, nothing written, the balance as it was
        var shortOf = s.change(a, "Steve", -300, Kind.BUY, "grand_sedan", null, "Steve", false, 3000).get();
        assertEquals(Outcome.SHORT, shortOf.outcome());
        assertEquals(200, shortOf.balance());
        assertEquals(new DonationStore.Account(200, 500, 300), s.account(a).get());
        assertEquals(2, s.history(a, 50).get().size(), "the refused purchase left no line");
        // taking back more than is held is refused too
        assertEquals(Outcome.SHORT, s.change(a, "Steve", -201, Kind.REVOKE, null, null, "admin", false, 3500).get().outcome());
        assertEquals(Outcome.DONE, s.change(a, "Steve", -50, Kind.REVOKE, null, "잘못 지급", "admin", false, 3600).get().outcome());
        assertEquals(new DonationStore.Account(150, 450, 300), s.account(a).get());

        // handed over once: the first caller gets a yes, nobody after
        assertTrue(s.delivered(buy.entry()).get());
        assertFalse(s.delivered(buy.entry()).get(), "never handed over twice");
        assertTrue(s.undelivered(a).get().isEmpty());

        // a product sold once: the second purchase is refused, by someone else it is not
        s.change(b, "Alex", 1000, Kind.GRANT, null, null, "console", false, 4000).get();
        assertEquals(Outcome.DONE, s.change(b, "Alex", -100, Kind.BUY, "badge", null, "Alex", true, 4100).get().outcome());
        assertEquals(Outcome.ALREADY, s.change(b, "Alex", -100, Kind.BUY, "badge", null, "Alex", true, 4200).get().outcome());
        assertEquals(900, s.account(b).get().balance(), "the refused one cost nothing");
        assertEquals(Set.of("badge"), s.owned(b).get());
        assertEquals(Set.of("hyper_car"), s.owned(a).get());
        // a product not marked once can be bought again
        assertEquals(Outcome.DONE, s.change(b, "Alex", -300, Kind.BUY, "hyper_car", null, "Alex", false, 4300).get().outcome());
        assertEquals(Outcome.DONE, s.change(b, "Alex", -300, Kind.BUY, "hyper_car", null, "Alex", false, 4400).get().outcome());

        // undoing a purchase: its price back, once; one that was not handed over yet never will be
        long badge = s.history(b, 50).get().stream().filter(e -> "badge".equals(e.product())).findFirst().orElseThrow().id();
        var undone = s.refund(badge, "마음이 바뀜", "admin", 5000).get();
        assertEquals(Outcome.DONE, undone.result().outcome());
        assertTrue(undone.wasPending());
        assertEquals(300 + 100, undone.result().balance());
        assertEquals(Outcome.ALREADY, s.refund(badge, null, "admin", 5100).get().result().outcome(), "not twice");
        assertEquals(400, s.account(b).get().balance());
        assertFalse(s.owned(b).get().contains("badge"), "refunded: no longer theirs, so it can be bought again");
        assertEquals(Outcome.DONE, s.change(b, "Alex", -100, Kind.BUY, "badge", null, "Alex", true, 5200).get().outcome());
        assertNull(s.refund(999_999, null, "admin", 5300).get().entry(), "no such purchase");
        assertNull(s.refund(g.entry(), null, "admin", 5300).get().entry(), "a credit is no purchase");
        assertEquals(3, s.undelivered(b).get().size(), "the two cars and the new badge wait; the refunded badge does not");

        // everything is still there after a restart, newest first
        db.close();
        db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        s = open(db);
        assertEquals(new DonationStore.Account(150, 450, 300), s.account(a).get());
        List<DonationStore.Entry> lines = s.history(a, 50).get();
        assertEquals(List.of(Kind.REVOKE, Kind.BUY, Kind.GRANT), lines.stream().map(DonationStore.Entry::kind).toList());
        assertEquals("계좌이체 11/14", lines.getLast().note());
        assertEquals("admin", lines.getLast().actor());
        assertEquals(200, lines.get(1).balance(), "each line keeps the balance it left");
        long[] totals = s.totals().get();
        assertEquals(450 + 1000, totals[0]);
        assertEquals(2, totals[3]);
        assertEquals(s.account(a).get().balance() + s.account(b).get().balance(), totals[2]);
        db.close();
    }

    @Test
    void theCatalogue() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("""
                currency: { name: "별", symbol: "★" }
                notice: ["<gray>후원 안내", "<dark_gray>공식 Minecraft 제품이 아닙니다"]
                categories:
                  cars: { name: "차량", products: [car, ghost, car] }
                  looks: { name: "꾸미기", products: [hat, car] }
                  empty: { name: "빈 분류", products: [] }
                products:
                  car: { name: "차", price: 300, item: "stsv:car", description: "<gray>빠르지 않음" }
                  hat: { name: "모자", price: 50, once: true, commands: ["/관리자 치장 지급 {player} stsv:hat", "  "] }
                  free: { name: "공짜", price: 0, item: "stsv:car" }
                  nothing: { name: "아무것도", price: 10 }
                  missing: { name: "없는 아이템", price: 10, item: "stsv:nope" }
                  loose: { name: "분류 없음", price: 10, commands: ["say hi"] }
                """);
        List<String> problems = new ArrayList<>();
        DonationShop shop = DonationShop.parse(y, id -> id.equals("stsv:car"), problems::add);
        assertEquals("300★", shop.amount(300));
        assertEquals("1,200★", shop.amount(1200));
        assertEquals(2, shop.notice().size());
        assertEquals(List.of("cars", "looks"), List.copyOf(shop.categories().keySet()), "a category with nothing to show is not shown");
        assertEquals(List.of("car"), shop.categories().get("cars").products(), "a product stands in the shop once");
        assertEquals(List.of("hat"), shop.categories().get("looks").products());
        assertEquals(Set.of("car", "hat", "loose"), shop.products().keySet());
        DonationShop.Product hat = shop.products().get("hat");
        assertTrue(hat.once());
        assertEquals(List.of("관리자 치장 지급 {player} stsv:hat"), hat.commands(), "the slash is not part of a console command; blank lines are dropped");
        assertNull(hat.item());
        assertFalse(DonationShop.affords(49, hat));
        assertTrue(DonationShop.affords(50, hat));
        String log = String.join("\n", problems);
        for (String word : List.of("products.free", "products.nothing", "products.missing", "products.loose", "모르는 상품 ghost", "이미 다른 분류")) {
            assertTrue(log.contains(word), word + " in:\n" + log);
        }
        // the game's own currency is not imitated
        y.set("currency.name", "Minecoins");
        assertEquals("별", DonationShop.parse(y, id -> true, p -> { }).currency());
    }

    /** The shipped shop: it reads cleanly, it sells looks, and what it sells exists. */
    @Test
    void theShippedShop() {
        List<String> problems = new ArrayList<>();
        Set<String> items = new java.util.HashSet<>();
        for (java.io.File f : PACK.resolve("items").toFile().listFiles((d, n) -> n.endsWith(".yml"))) {
            items.addAll(YamlConfiguration.loadConfiguration(f).getKeys(false));
        }
        DonationShop shop = DonationShop.parse(YamlConfiguration.loadConfiguration(PACK.resolve("donation_shop.yml").toFile()),
                id -> id.startsWith("stsv:") && items.contains(id.substring(5)), problems::add);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertFalse(shop.categories().isEmpty());
        assertFalse(shop.notice().isEmpty(), "how to donate, and that this is no official product, is said in the shop");
        assertTrue(String.join(" ", shop.notice()).contains("Minecraft"), "the notice the game's guidelines ask for");
        assertTrue(String.join(" ", shop.notice()).contains("100원"), "what a star costs is said in the shop: 1 star = 100 won");
        // the owner's prices at that rate: 30,000 won, 25,000 won, 25,000 won
        assertEquals(300, shop.products().get("hyper_car").price());
        assertEquals(250, shop.products().get("grand_sedan").price());
        assertEquals(250, shop.products().get("luxury_suv").price());
        for (String car : List.of("hyper_car", "grand_sedan", "luxury_suv")) {
            DonationShop.Product p = shop.products().get(car);
            assertEquals("stsv:" + car, p.item(), "the premium cars are what the shop is for");
            assertFalse(p.once(), "a car can be bought again (another one to tune differently)");
        }
        for (DonationShop.Product p : shop.products().values()) {
            assertTrue(p.price() >= 10, p.id());
            assertFalse(p.name().isBlank() || p.description().isBlank(), p.id() + ": a name and what it is");
            // nothing that is worth game money or strength: no money, no gear, no experience
            for (String c : p.commands()) {
                assertFalse(c.contains("경제") || c.contains("economy") || c.startsWith("give ") || c.contains("xp ") || c.contains("experience"), p.id() + ": " + c);
            }
        }
    }

    /** Products that give what is owned for good: banners, emotes, tool skins. */
    @Test
    void thingsOwnedForGood() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("""
                categories:
                  a: { name: "배너", icon: "stsv:gui/flag", products: [fire, set, both, bad, none] }
                products:
                  fire: { name: "화염 배너", price: 50, description: ["한 줄", "두 줄"], unlocks: ["banner:fire"] }
                  set:  { name: "세트", price: 90, unlocks: ["tool_skin:toy_sword", "tool_skin:toy_axe"], icon: "stsv:toy_sword" }
                  both: { name: "차와 배너", price: 300, item: "stsv:car", unlocks: ["banner:frost"] }
                  bad:  { name: "틀린 것", price: 10, unlocks: ["fire"] }
                  none: { name: "빈 것", price: 10 }
                """);
        List<String> problems = new ArrayList<>();
        DonationShop shop = DonationShop.parse(y, id -> id.equals("stsv:car"), problems::add);
        assertEquals(Set.of("fire", "set", "both"), shop.products().keySet());
        assertEquals(4, problems.size(), String.join("\n", problems));      // bad, none, and both left out of the category
        DonationShop.Product fire = shop.products().get("fire");
        assertEquals(List.of("banner:fire"), fire.unlocks());
        assertTrue(fire.once(), "what is owned for good is bought once");
        assertEquals("한 줄\n두 줄", fire.description(), "a list of lines is the tile's lines");
        assertEquals(2, shop.products().get("set").unlocks().size(), "a set: several things in one product");
        assertEquals("stsv:toy_sword", shop.products().get("set").icon());
        assertFalse(shop.products().get("both").once(), "with an item it can be bought again (the item is wanted twice)");
        assertEquals("stsv:gui/flag", shop.categories().get("a").icon());
    }

    /** The shipped shop's banners and emotes are ones there are, and the menu has a tab for every category. */
    @Test
    void theShippedShopsUnlocks() throws Exception {
        DonationShop shop = DonationShop.parse(YamlConfiguration.loadConfiguration(PACK.resolve("donation_shop.yml").toFile()), id -> true, p -> { });
        Set<String> banners = dev.stsv.core.banner.BannerCatalog.get().themes().keySet();
        YamlConfiguration emotes = YamlConfiguration.loadConfiguration(PACK.resolve("emotes.yml").toFile());
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        Set<String> sold = new java.util.HashSet<>();
        for (DonationShop.Product p : shop.products().values()) {
            for (String u : p.unlocks()) {
                String[] thing = dev.stsv.core.unlock.UnlockModule.split(u);
                // a thing is sold once on its own (and may be in a set as well)
                if (p.unlocks().size() == 1) assertTrue(sold.add(u), u + " is sold twice");
                switch (thing[0]) {
                    case "banner" -> assertTrue(banners.contains(thing[1]), p.id() + ": no banner theme " + thing[1]);
                    case "emote" -> {
                        assertTrue(emotes.contains("emotes." + thing[1]), p.id() + ": no emote " + thing[1]);
                        assertFalse(config.getStringList("emotes.free").contains(thing[1]), p.id() + ": a free emote is not sold");
                    }
                    case "tool_skin" -> { }      // ToolSkinsTest: every skin sold is a skin there is
                    default -> throw new AssertionError(p.id() + ": unknown kind " + thing[0]);
                }
            }
            // a tile's lines fit beside the menu: short lines, a few of them
            for (String line : p.description().split("\n")) assertTrue(dev.stsv.core.util.Text.plain(line).length() <= 40, p.id() + ": " + line);
        }
        for (String theme : banners) assertTrue(sold.contains("banner:" + theme), "the " + theme + " banner is not for sale");
        // the menu: a tab a category
        YamlConfiguration menu = YamlConfiguration.loadConfiguration(PACK.resolve("menus/donation.yml").toFile());
        long tabs = menu.getConfigurationSection("donation.buttons").getKeys(false).stream()
                .filter(k -> menu.getString("donation.buttons." + k + ".action", "").matches("donation:tab:\\d+")).count();
        assertEquals(7, tabs);
        assertTrue(shop.categories().size() <= tabs, "more categories than the menu has tabs");
        for (DonationShop.Category c : shop.categories().values()) {
            if (c.icon() != null && c.icon().startsWith("stsv:")) {
                assertTrue(java.nio.file.Files.isRegularFile(PACK.resolve("assets/stsv/items/" + c.icon().substring(5) + ".json")), c.id() + ": " + c.icon());
            }
        }
        assertTrue(menu.contains("donation_buy.buttons.Y"), "a purchase is confirmed on a screen of its own");
        assertEquals("donation:confirm", menu.getString("donation_buy.buttons.Y.action"));
    }

    @Test
    void theCommandAndItsWords() {
        var plugin = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
        assertEquals(DonationModule.PERMISSION, plugin.getString("commands.후원.permission"));
        assertEquals("true", plugin.getString("permissions." + DonationModule.PERMISSION + ".default"));
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        assertTrue(config.getBoolean("donation.enabled"));
        var messages = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8));
        for (Kind k : Kind.values()) assertTrue(messages.isString("donation.kind." + k.name().toLowerCase()), k.name());
        for (String key : List.of("tab-history", "tile-name", "history-tile-name", "not-sellable", "balance", "bought", "short", "already", "delivered", "pending-room", "no-room", "received", "admin-given",
                "admin-taken", "admin-refunded", "admin-refunded-pending", "admin-refunded-before", "history-entry", "history-pending")) {
            assertTrue(messages.isString("donation." + key), key);
        }
    }
}
