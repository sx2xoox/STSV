package dev.stsv.economy;

import dev.stsv.core.content.ContentPack;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.economy.job.JobSpec;
import dev.stsv.economy.job.MachineSpec;
import dev.stsv.economy.request.RequestSpec;
import dev.stsv.economy.event.EventSpec;
import dev.stsv.economy.shop.ShopCatalog;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The economy content pack is consistent: every item an YAML file names exists (vanilla or ours), every
 * item has its icon or model, menus load with correctly sized backgrounds whose cells match the layout,
 * every message the code uses is in messages.yml, every command is in plugin.yml.
 */
class ContentTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    static YamlConfiguration yaml(String file) {
        return YamlConfiguration.loadConfiguration(PACK.resolve(file).toFile());
    }

    static Set<String> ourItems() {
        Set<String> ids = new HashSet<>();
        try (Stream<Path> s = Files.list(PACK.resolve("items"))) {
            for (Path f : s.toList()) for (String k : YamlConfiguration.loadConfiguration(f.toFile()).getKeys(false)) ids.add("economy:" + k);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ids;
    }

    /** Items of the core content (stsv:...): cosmetics and the like. */
    static Set<String> coreItems() {
        Set<String> ids = new HashSet<>();
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("stsv.content")).resolve("../../content/stsv/items").normalize())) {
            for (Path f : s.toList()) for (String k : YamlConfiguration.loadConfiguration(f.toFile()).getKeys(false)) ids.add("stsv:" + k);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ids;
    }

    /** "#economy:<group>" inputs of machines.yml (filled when it is read) */
    static final Set<String> GROUPS = new HashSet<>();

    static boolean exists(String id, Set<String> ours) {
        if (id.startsWith("#economy:")) return GROUPS.contains(id);
        if (id.startsWith("economy:")) return ours.contains(id);
        if (id.startsWith("#")) return List.of("#logs", "#small_flowers", "#planks", "#minecraft:logs", "#minecraft:planks",
                "#saplings", "#leaves", "#stone_crafting_materials").contains(id);
        if (id.startsWith("stsv:")) return Files.exists(Path.of(System.getProperty("stsv.content")).resolve("../../content/stsv"));
        // a vanilla item with components (minecraft:enchanted_book[stored_enchantments={mending:1}]): its material
        Material m = Material.matchMaterial(id.indexOf('[') > 0 ? id.substring(0, id.indexOf('[')) : id);
        return m != null && !m.isLegacy() && m != Material.AIR;   // isItem() needs a running server
    }

    @Test
    void everyDataFileParsesAndNamesRealItems() {
        Set<String> ours = ourItems();
        List<String> problems = new ArrayList<>();

        Map<String, EventSpec> events = EventSpec.parse(yaml("events.yml"), problems);
        ShopCatalog shop = ShopCatalog.parse(yaml("shop.yml"), problems);
        JobSpec jobs = JobSpec.parse(yaml("jobs.yml"), problems);
        Map<String, MachineSpec> machines = MachineSpec.parse(yaml("machines.yml"), problems);
        // fish groups: every member exists, every group is used by a recipe, and a recipe's "#economy:x" is a group
        Map<String, MachineSpec.Group> groups = MachineSpec.groups(yaml("machines.yml"), problems);
        GROUPS.clear();
        GROUPS.addAll(groups.keySet().stream().map(k -> "#" + k).toList());
        RequestSpec requests = RequestSpec.parse(yaml("requests.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));

        for (String id : shop.sell().keySet()) if (!exists(id, ours)) problems.add("shop sell: " + id);
        for (var g : shop.goods()) {
            switch (g.kind()) {
                case "item" -> { if (!exists(g.id(), ours)) problems.add("shop buy: " + g.id()); }
                case "cosmetic" -> { if (!coreItems().contains(g.id())) problems.add("shop cosmetic: " + g.id()); }
                case "title" -> { if (!yaml("titles/titles.yml").contains(g.id().replace("economy:", ""))) problems.add("shop title: " + g.id()); }
                default -> problems.add("shop kind: " + g.kind());
            }
        }
        for (var m : shop.merchant().goods()) if (!exists(m.id(), ours)) problems.add("merchant: " + m.id());
        // 상점: 구매 · 판매 → 카테고리 → 품목. Every category fits the one row (구매 with the 상인 행렬's place),
        // has an icon and goods, and every item the shop buys is in exactly one 판매 category
        assertTrue(shop.sellCategories().size() <= ShopCatalog.MAX_SELL_CATEGORIES && shop.buyCategories().size() + 1 <= ShopCatalog.MAX_BUY_CATEGORIES);
        assertEquals(List.of("minerals", "farm", "fish", "materials", "crafts", "foodstuff", "dishes"),
                shop.sellCategories().stream().map(ShopCatalog.Category::id).toList());
        assertEquals(List.of("tools", "machines", "seeds", "supplies", "furniture", "vehicles", "cosmetics", "titles"),
                shop.buyCategories().stream().map(ShopCatalog.BuyCategory::id).toList());
        // 가구: every Japanese-style piece of the core (content/stsv/items/furniture_japanese.yml), in the file's order (room, light,
        // plants, props, garden), one at a time, on one page of the item menu (36 cells); the shop does not buy furniture back
        ShopCatalog.BuyCategory furniture = shop.buyCategories().stream().filter(c -> c.id().equals("furniture")).findFirst().orElseThrow();
        List<String> pieces = YamlConfiguration.loadConfiguration(Path.of(System.getProperty("stsv.content"))
                .resolve("../../content/stsv/items/furniture_japanese.yml").normalize().toFile()).getKeys(false).stream().map(k -> "stsv:" + k).toList();
        assertEquals(30, pieces.size());
        assertEquals(pieces, furniture.goods().stream().map(ShopCatalog.Good::id).toList());
        assertTrue(furniture.goods().stream().allMatch(g -> g.kind().equals("item") && g.amount() == 1), "one piece a purchase");
        assertTrue(furniture.single(), "no shift-click 8 at once: a 20,000 G piece by mistake");
        assertTrue(shop.buyCategories().stream().filter(c -> !c.id().equals("furniture") && !c.id().equals("vehicles"))
                .noneMatch(ShopCatalog.BuyCategory::single), "only the furniture and the vehicles");
        // vehicles: generations 1-3 are sold (the rest are admin-only), one at a time
        ShopCatalog.BuyCategory vehicles = shop.buyCategories().stream().filter(c -> c.id().equals("vehicles")).findFirst().orElseThrow();
        assertTrue(vehicles.single(), "one vehicle a purchase");
        assertEquals(List.of("stsv:jige", "stsv:hand_cart", "stsv:cargo_bicycle", "stsv:ox_cart", "stsv:delivery_scooter", "stsv:power_tiller"),
                vehicles.goods().stream().map(ShopCatalog.Good::id).toList());
        assertTrue(furniture.goods().size() <= 36, "one page");
        assertTrue(pieces.contains(furniture.icon()), "a piece is the icon");
        for (String id : pieces) if (shop.sell().containsKey(id)) problems.add("furniture is not sold to the shop: " + id);
        assertEquals(Map.of("stsv:torii", 20000L, "stsv:maneki_neko", 7777L, "stsv:stepping_stones", 500L, "stsv:kotatsu", 15000L),
                furniture.goods().stream().filter(g -> List.of("stsv:torii", "stsv:maneki_neko", "stsv:stepping_stones", "stsv:kotatsu").contains(g.id()))
                        .collect(java.util.stream.Collectors.toMap(ShopCatalog.Good::id, ShopCatalog.Good::price)));
        List<String> inCategories = new ArrayList<>();
        for (var c : shop.sellCategories()) {
            if (c.items().isEmpty() || !exists(c.icon(), ours)) problems.add("sell category " + c.id());
            inCategories.addAll(c.items());
        }
        assertEquals(List.copyOf(shop.sell().keySet()), inCategories, "each sold item in one category");
        // 치장: the core's 치장 뽑기 instead of goods; no cosmetic is sold directly any more
        assertTrue(shop.buyCategories().stream().anyMatch(c -> c.id().equals("cosmetics") && c.gacha() && c.goods().isEmpty()));
        assertTrue(shop.goods().stream().noneMatch(g -> g.kind().equals("cosmetic")), "cosmetics come from the gacha");
        for (var c : shop.buyCategories()) {
            if (c.gacha()) {
                if (!coreItems().contains(c.icon())) problems.add("buy category " + c.id() + " icon");
                continue;
            }
            if (c.goods().isEmpty() ||!(exists(c.icon(), ours) || coreItems().contains(c.icon()))) problems.add("buy category " + c.id());
            boolean vanity = c.goods().getFirst().vanity();
            if (c.goods().stream().anyMatch(g -> g.vanity() != vanity)) problems.add("buy category " + c.id() + " mixes items with cosmetics/titles");
        }
        if (!exists(shop.merchant().icon(), ours)) problems.add("merchant icon");
        for (var j : jobs.jobs().values()) {
            if (!exists(j.icon(), ours)) problems.add("job icon: " + j.icon());
            if (!machines.containsKey(j.machine())) problems.add("job machine: " + j.machine());
            if (j.finds() != null) {
                for (var d : j.finds().drops().values()) for (String id : d) if (!exists(id, ours)) problems.add("job find: " + id);
            }
        }
        for (var g : groups.values()) {
            for (String id : g.items()) if (!exists(id, ours)) problems.add("group " + g.id() + ": " + id);
            boolean used = machines.values().stream().flatMap(x -> x.recipes().stream()).anyMatch(r -> r.inputs().containsKey("#" + g.id()));
            if (!used) problems.add("group not used: " + g.id());
        }
        for (MachineSpec m : machines.values()) {
            if (!exists(m.item(), ours)) problems.add("machine item: " + m.item());
            // the six job machines have their job; the press is for everyone (no job)
            if (m.job() == null ? !m.id().equals("press") : !jobs.jobs().containsKey(m.job())) problems.add("machine job: " + m.job());
            for (var r : m.recipes()) {
                if (!exists(r.output(), ours)) problems.add(m.id() + " out: " + r.output());
                for (String in : r.inputs().keySet()) if (!exists(in, ours)) problems.add(m.id() + " in: " + in);
            }
            // every tier of the machine and its parts has a model
            for (int t = 0; t < 4; t++) {
                if (!Files.exists(PACK.resolve("models/" + m.model() + "_" + t + ".bbmodel"))) problems.add(m.id() + " model tier " + t);
                for (String part : m.parts()) if (!Files.exists(PACK.resolve("models/" + part + "_" + t + ".bbmodel"))) problems.add(m.id() + " part " + part + "_" + t);
            }
            // every good sells: the shop's base price is what machine XP is counted from
            for (var r : m.recipes()) if (!shop.sell().containsKey(r.output())) problems.add(m.id() + " good not in the shop: " + r.output());
        }
        // stocks: six industries of seven companies, each industry a job, news for every industry
        dev.stsv.economy.stock.StockSpec stocks = dev.stsv.economy.stock.StockSpec.parse(yaml("stocks.yml"), problems);
        dev.stsv.economy.stock.NewsSpec news = dev.stsv.economy.stock.NewsSpec.parse(yaml("news.yml"), problems);
        assertEquals(6, stocks.industries().size());
        assertEquals(42, stocks.companies().size());
        for (var i : stocks.industries().values()) {
            if (!jobs.jobs().containsKey(i.job())) problems.add("stock industry job: " + i.job());
            if (!exists(i.icon(), ours)) problems.add("stock industry icon: " + i.icon());
            long n = stocks.companies().values().stream().filter(c -> c.industry().equals(i.id())).count();
            if (n != 7) problems.add(i.id() + ": " + n + " companies");
            if (!news.causes().containsKey(i.id())) problems.add("news for " + i.id());
            else for (boolean up : new boolean[]{true, false}) if (news.causes().get(i.id()).get(up).size() < 12) problems.add("news " + i.id() + " " + up);
        }
        if (stocks.companies().values().stream().map(c -> c.name()).distinct().count() != 42) problems.add("company names must differ");
        for (String id : stocks.companies().keySet()) {
            if (!Files.exists(PACK.resolve("assets/economy/items/gui/logo_" + id + ".json"))) problems.add("company logo: " + id);
        }
        for (var e : requests.pool()) if (!exists(e.item(), ours)) problems.add("request: " + e.item());
        for (var c : requests.community()) if (!exists(c.item(), ours)) problems.add("community: " + c.item());
        assertTrue(problems.isEmpty(), String.join("\n", problems));

        // fishing: 98 fish (92 our own items, 3 vanilla, 3 kept from the old finds), each group's title exists
        dev.stsv.economy.fishing.FishSpec fish = dev.stsv.economy.fishing.FishSpec.parse(yaml("fish.yml"), problems);
        assertEquals(98, fish.species().size());
        YamlConfiguration titles = yaml("titles/titles.yml");
        for (var s : fish.species().values()) {
            if (!exists(s.item(), ours)) problems.add("fish item: " + s.item());
            if (!fish.groups().containsKey(s.group())) problems.add("fish group: " + s.id() + " " + s.group());
            if (!shop.sell().containsKey(s.item())) problems.add("fish not in the shop: " + s.item());
        }
        for (var g : fish.groups().values()) if (g.title() == null || !titles.contains(g.title().substring(g.title().indexOf(':') + 1))) problems.add("fish title: " + g.title());
        if (!titles.contains("master_angler")) problems.add("title master_angler");
        assertEquals(3, fish.species().values().stream().filter(x -> x.item().startsWith("minecraft:")).count(), "cod, salmon, pufferfish");

        // the shape: 6 jobs, 6 finds, 8 special materials, 157 job machine goods + 9 press goods (the fisher's 4 prepared fish, the
        // cook's 4 dishes of them and the mill's fish feed among them), 6 job machines + the press, the 통발 and the 가두리,
        // 32 crop items (15 seeds, 17 harvests), 92 fish
        assertEquals(6, jobs.jobs().size());
        assertEquals(6 + 8 + 166 + 7 + 2 + 32 + 92, ours.size(),
                "6 finds + 8 specials + 166 goods + 7 machines + 2 fishing gear + 32 crop items + 92 fish");
        assertEquals(7, machines.size());
        assertEquals(166, machines.values().stream().flatMap(x -> x.recipes().stream()).map(MachineSpec.Recipe::output).distinct().count());
        // every special material is an item of ours, and the shop does not buy it (players trade it)
        for (String sp : jobs.specials().keySet()) {
            if (!exists(sp, ours)) problems.add("special item: " + sp);
            if (shop.sell().containsKey(sp)) problems.add("the shop buys the special " + sp);
        }
        assertTrue(events.keySet().containsAll(List.of("shortage", "crash", "harvest", "merchant", "tax_audit")));
        assertFalse(Files.exists(PACK.resolve("season.yml")), "no season plan any more");
        // every job product can actually be made: by a recipe, a machine, a job drop or a fishing rod
        Set<String> makeable = new HashSet<>();
        for (var s : fish.species().values()) makeable.add(s.item());
        makeable.add("economy:pearl");
        for (MachineSpec m : machines.values()) for (var r : m.recipes()) makeable.add(r.output());
        for (var j : jobs.jobs().values()) {
            if (j.finds() == null) continue;
            for (var d : j.finds().drops().values()) makeable.addAll(d);
        }
        YamlConfiguration recipes = yaml("recipes/jobs.yml");
        for (String k : recipes.getKeys(false)) {
            ConfigurationSection r = recipes.getConfigurationSection(k);
            String result = r.isConfigurationSection("result") ? r.getString("result.item") : r.getString("result");
            makeable.add(JobSpec.norm(result));
            for (String in : r.getStringList("ingredients")) if (!exists(JobSpec.norm(in), ours)) problems.add("recipe " + k + ": " + in);
            if (r.contains("input") && !exists(JobSpec.norm(r.getString("input")), ours)) problems.add("recipe " + k + ": " + r.getString("input"));
            ConfigurationSection keys = r.getConfigurationSection("keys");
            if (keys != null) for (String key : keys.getKeys(false)) if (!exists(JobSpec.norm(keys.getString(key)), ours)) problems.add("recipe " + k + ": " + keys.getString(key));
        }
        // crops: harvests make things, seeds come from shops or drops; every crop has its models
        YamlConfiguration crops = yaml("crops/crops.yml");
        assertEquals(17, crops.getKeys(false).size(), "17 crops");
        for (String k : crops.getKeys(false)) {
            var spec = dev.stsv.core.crop.CropSpec.parse("economy", k, crops.getConfigurationSection(k));
            if (!exists(spec.seed(), ours)) problems.add("crop " + k + " seed: " + spec.seed());
            for (var d : spec.harvest()) {
                if (!exists(d.item(), ours)) problems.add("crop " + k + " harvest: " + d.item());
                makeable.add(d.item());
            }
            for (String model : spec.models()) {
                if (!Files.exists(PACK.resolve("models/" + model.substring(model.indexOf('/') + 1) + ".bbmodel"))) problems.add("crop model " + model);
            }
            // replanting needs a seed: a regrowing plant stays, anything else must drop at least one of its seed
            if (spec.regrow() < 0 && spec.harvest().stream().noneMatch(d -> d.item().equals(spec.seed()) && d.min() >= 1)) {
                problems.add("crop " + k + " cannot replant itself");
            }
            if (!spec.harvest().getFirst().item().equals(spec.produce())) problems.add("crop " + k + " produce");
            if (spec.extra().containsKey("job") && !jobs.jobs().containsKey(spec.extra().get("job"))) problems.add("crop job " + spec.extra().get("job"));
        }
        for (var g : shop.goods()) if (g.kind().equals("item")) makeable.add(g.id());
        makeable.addAll(jobs.specials().keySet());   // special materials: found by harvesting, fishing or mining (jobs.yml specials)
        for (String id : ours) if (!makeable.contains(id)) problems.add("nobody can make " + id);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyItemHasItsArt() throws Exception {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> s = Files.list(PACK.resolve("items"))) {
            for (Path f : s.toList()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                for (String k : y.getKeys(false)) {
                    String tex = y.getString(k + ".texture"), model = y.getString(k + ".model");
                    if (tex != null) {
                        Path png = PACK.resolve("textures/" + tex + ".png");
                        if (!Files.exists(png)) problems.add(k + ": textures/" + tex + ".png");
                        else {
                            BufferedImage im = ImageIO.read(png.toFile());
                            if (im.getWidth() != 16 || im.getHeight() != 16) problems.add(k + ": icon must be 16x16");
                        }
                    } else if (model != null) {
                        if (!Files.exists(PACK.resolve("models/" + model + ".bbmodel"))) problems.add(k + ": models/" + model + ".bbmodel");
                    } else {
                        problems.add(k + ": no texture or model");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void menusLoadAndTheirBackgroundsMatchTheLayout() throws Exception {
        List<String> problems = new ArrayList<>();
        List<MenuSpec> menus = MenuSpec.loadAll(List.of(new ContentPack("economy", "economy", PACK)), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertEquals(24, menus.size());
        for (MenuSpec m : menus) {
            // the button grid is mirror-symmetric, row by row (the machine's lanes are fixed by its code; their
            // empty cells hold arrows, so they read as full rows)
            if (!m.id().equals("economy:machine")) {
                for (String row : m.layout()) {
                    String used = row.replaceAll("[^.]", "#");
                    assertEquals(new StringBuilder(used).reverse().toString(), used, m.id() + " row " + row + " is symmetric");
                }
            }
            // the shop's category rows hold as many categories as ShopCatalog allows on that side
            if (m.id().equals("economy:shop_buy")) assertEquals(ShopCatalog.MAX_BUY_CATEGORIES, m.slots(m.list()).size());
            if (m.id().equals("economy:shop_sell")) assertEquals(ShopCatalog.MAX_SELL_CATEGORIES, m.slots(m.list()).size());
            assertTrue(m.background() != null, m.id() + " has a background");
            BufferedImage im = ImageIO.read(PACK.resolve("assets/economy/textures/" + m.background().getKey() + ".png").toFile());
            // every slot used by the layout is drawn as a cell (its centre differs from the felt around it)
            int felt = im.getRGB(4 + 1, 17 + 9 + 18 * (m.rows() - 1) - 9 + 3);
            for (int slot = 0; slot < m.rows() * 9; slot++) {
                char ch = m.layout().get(slot / 9).charAt(slot % 9);
                int x = 7 + 18 * (slot % 9), y = 17 + 18 * (slot / 9);
                int edge = im.getRGB(x, y + 9);
                if (ch == '.') continue;
                assertTrue(edge != im.getRGB(x + 9, y + 9) || edge != felt, m.id() + " slot " + slot + " is drawn");
            }
            for (MenuSpec.Button b : m.buttons().values()) {
                if (b.on().icon() == null) continue;
                String ns = b.on().icon().getNamespace(), key = b.on().icon().getKey();
                Path def = ns.equals("economy") ? PACK.resolve("assets/economy/items/" + key + ".json")
                        : Path.of(System.getProperty("stsv.content")).resolve("../../content/stsv/assets/stsv/items/" + key + ".json").normalize();
                // a custom item's own model works too (the pack builder writes items/<id>.json for every item)
                assertTrue(Files.exists(def) || (ns.equals("economy") && ourItems().contains("economy:" + key))
                        || (ns.equals("stsv") && coreItems().contains("stsv:" + key)), m.id() + " button icon " + b.on().icon());
            }
        }
    }

    /** The lane picker (자동 + every recipe) and the recipe book fit the list of economy:machine_recipes, or the menu pages it. */
    @Test
    void theRecipeBookShowsEveryRecipe() throws Exception {
        List<String> problems = new ArrayList<>();
        MenuSpec book = MenuSpec.loadAll(List.of(new ContentPack("economy", "economy", PACK)), problems).stream()
                .filter(m -> m.id().equals("economy:machine_recipes")).findFirst().orElseThrow();
        int slots = book.slots(book.list()).size();
        boolean pages = book.buttons().values().stream().anyMatch(b -> b.action().equals("page:next"))
                && book.buttons().values().stream().anyMatch(b -> b.action().equals("page:prev"));
        for (MachineSpec m : MachineSpec.parse(yaml("machines.yml"), problems).values()) {
            if (m.recipes().size() + 1 > slots && !pages)
                problems.add(m.id() + ": 자동 + " + m.recipes().size() + " recipes in " + slots + " list slots and no page buttons");
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static final Pattern KEY = Pattern.compile("Lang\\.(?:msg|mm|raw|item|items)\\(\\s*\"([a-z][a-z0-9.-]+)\"");
    private static final Pattern REFUSAL = Pattern.compile("return \"([a-z]+\\.[a-z0-9-]+)\";");

    @Test
    void everyMessageTheCodeUsesExists() throws Exception {
        YamlConfiguration messages;
        try (var in = getClass().getResourceAsStream("/messages.yml")) {
            messages = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        YamlConfiguration core;
        try (var in = dev.stsv.core.STSVPlugin.class.getResourceAsStream("/messages.yml")) {
            core = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> missing = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                for (Pattern p : List.of(KEY, REFUSAL)) {
                    Matcher m = p.matcher(src);
                    while (m.find()) {
                        found++;
                        String k = m.group(1);
                        if (!messages.contains(k) && !core.contains(k)) missing.add(f.getFileName() + ": " + k);
                    }
                }
            }
        }
        assertTrue(found > 200, "found " + found);
        // keys built at run time
        for (java.time.DayOfWeek w : java.time.DayOfWeek.values()) if (!messages.contains("weekday." + w)) missing.add("weekday." + w);
        for (String w : List.of("night", "dawn", "dusk", "rain", "thunder", "fullmoon")) if (!messages.contains("codex.when." + w)) missing.add("codex.when." + w);
        for (int r = 1; r <= 3; r++) if (!messages.contains("stock.risk." + r)) missing.add("stock.risk." + r);
        for (String r : List.of("market_buy", "market_cancel", "market_expired", "buy_order", "buy_order_return", "auction_unsold", "auction_win", "auction_cancel",
                "trade", "trade_cancel", "stall_release", "stall_evicted", "mission", "mission_bonus")) {
            if (!messages.contains("mail.reason." + r)) missing.add("mail.reason." + r);
        }
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }

    @Test
    void everyCommandIsDeclaredAndRegistered() throws Exception {
        YamlConfiguration plugin;
        try (var in = getClass().getResourceAsStream("/plugin.yml")) {
            plugin = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Set<String> declared = plugin.getConfigurationSection("commands").getKeys(false);
        String commands = Files.readString(Path.of("src/main/java/dev/stsv/economy/Commands.java"));
        Matcher m = Pattern.compile("eco\\.command\\(\"([^\"]+)\"").matcher(commands);
        Set<String> registered = new HashSet<>();
        while (m.find()) registered.add(m.group(1));
        assertEquals(declared, registered);
        for (String n : declared) assertTrue(n.codePoints().allMatch(c -> c >= 0xAC00 && c <= 0xD7A3), "Korean command: " + n);
    }

    @Test
    void configDefaultsLiveInConfigYml() throws Exception {
        YamlConfiguration defaults;
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Pattern read = Pattern.compile("eco\\.getConfig\\(\\)\\.get(?:String|Boolean|Int|Double|Long)\\(\"([a-z0-9.\\-]+)\"(,?)");
        List<String> problems = new ArrayList<>();
        int reads = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = read.matcher(Files.readString(f));
                while (m.find()) {
                    reads++;
                    if (!defaults.contains(m.group(1))) problems.add(f.getFileName() + ": " + m.group(1));
                    if (!m.group(2).isEmpty()) problems.add(f.getFileName() + ": code default for " + m.group(1));
                }
            }
        }
        assertTrue(reads >= 5, "reads " + reads);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * missions.yml: parses clean, counts only stats that are really counted, names real jobs and items,
     * every job has its own daily and weekly missions, and a full set can always be drawn.
     */
    @Test
    void missionsFitEveryJob() throws Exception {
        List<String> problems = new ArrayList<>();
        var spec = dev.stsv.economy.mission.MissionSpec.parse(yaml("missions.yml"), problems);
        JobSpec jobs = JobSpec.parse(yaml("jobs.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        Set<String> ours = ourItems();
        Set<String> stats = new HashSet<>(List.of("ores_mined", "crops_harvested", "animals_bred", "fish_caught", "rare_finds",
                "machine_made", "job_xp", "shop_sold", "shop_income", "requests_done", "stock_trades", "market_sold",
                "playtime", "mob_kills", "blocks_broken", "blocks_placed"));
        for (String j : jobs.jobs().keySet()) stats.add("made_" + j);
        for (var period : dev.stsv.economy.mission.MissionSpec.Period.values()) {
            var k = spec.kind(period);
            assertEquals(3, k.count(), period + " count");
            for (var m : k.missions().values()) {
                String at = period + " " + m.id();
                if (!stats.contains(m.stat())) problems.add(at + ": stat " + m.stat());
                if (m.job() != null && !jobs.jobs().containsKey(m.job())) problems.add(at + ": job " + m.job());
                if (!exists(m.icon(), ours)) problems.add(at + ": icon " + m.icon());
                if (m.reward().isEmpty()) problems.add(at + ": no reward");
                for (var i : m.reward().items()) if (!exists(i.id(), ours)) problems.add(at + ": item " + i.id());
                // a made_<job> mission belongs to that job; a job_xp mission is for one job and says which
                if (m.stat().startsWith("made_") && !m.stat().equals("made_" + m.job())) problems.add(at + ": made_ of another job");
                if (m.stat().equals("job_xp") && (m.job() == null || !m.name().startsWith(jobs.jobs().get(m.job()).name()))) problems.add(at + ": job_xp name");
            }
            for (var i : k.bonus().items()) if (!exists(i.id(), ours)) problems.add(period + " bonus item " + i.id());
            for (String j : jobs.jobs().keySet()) {
                if (k.ofJob(j).isEmpty()) problems.add(period + ": no mission for " + j);
                // whatever job mission comes first, the missions for everyone still fill the set
                for (var first : k.ofJob(j)) {
                    long open = k.general().stream().map(dev.stsv.economy.mission.MissionSpec.Mission::group).distinct()
                            .filter(g -> !g.equals(first.group())).count();
                    if (open < k.count() - 1) problems.add(period + " " + first.id() + ": too few missions for everyone");
                }
            }
            for (int seed = 0; seed < 200; seed++) {
                for (String j : new ArrayList<>(jobs.jobs().keySet()) {{ add(null); }}) {
                    var set = dev.stsv.economy.mission.MissionSpec.draw(k, j, new java.util.Random(seed));
                    if (set.size() != k.count()) problems.add(period + " " + j + ": drew " + set.size());
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        // the menu: one row of the list for each period
        MenuSpec menu = MenuSpec.loadAll(List.of(new ContentPack("economy", "economy", PACK)), problems).stream()
                .filter(m -> m.id().equals("economy:missions")).findFirst().orElseThrow();
        List<Integer> list = menu.slots(menu.list());
        assertEquals(spec.daily().count() + spec.weekly().count(), list.size());
        // daily missions fill the first row of the list, weekly the second
        assertEquals(List.of(list.get(0) / 9, list.get(spec.daily().count()) / 9 - 1), List.of(list.get(spec.daily().count() - 1) / 9, list.get(0) / 9));
    }

    /**
     * Every job machine can work alone: it has goods of its own made from raw things only (no other
     * machine's goods), and no good has a second recipe that only routes round another machine (new goods,
     * not new ways to old ones). The press takes only leftovers. Times range from quick to long, and the
     * still's aged drinks are its slowest.
     */
    @Test
    void machinesWorkWithoutTheOthers() {
        Map<String, MachineSpec> machines = MachineSpec.parse(yaml("machines.yml"), new ArrayList<>());
        Map<String, String> madeOn = new java.util.HashMap<>();
        for (MachineSpec m : machines.values()) for (var r : m.recipes()) madeOn.putIfAbsent(r.output(), m.id());
        List<String> problems = new ArrayList<>();
        for (MachineSpec m : machines.values()) {
            java.util.function.Predicate<MachineSpec.Recipe> alone = r -> r.inputs().keySet().stream()
                    .allMatch(in -> !madeOn.containsKey(in) || madeOn.get(in).equals(m.id()));
            long n = m.recipes().stream().filter(alone).count();
            if (n < 5) problems.add(m.id() + ": only " + n + " recipes without other machines");
            // a good made by two recipes of one machine: only the bulk kind (same inputs, more at once) is allowed
            Map<String, List<MachineSpec.Recipe>> same = new java.util.HashMap<>();
            for (var r : m.recipes()) same.computeIfAbsent(r.output(), k -> new ArrayList<>()).add(r);
            for (var e : same.entrySet()) {
                if (e.getValue().stream().map(r -> r.inputs().keySet()).distinct().count() > 1) problems.add(m.id() + " " + e.getKey() + ": two routes");
            }
            int quick = (int) m.recipes().stream().filter(r -> r.seconds() <= 60).count();
            int slow = (int) m.recipes().stream().filter(r -> r.seconds() >= 300).count();
            if (quick == 0 || (!m.id().equals("press") && slow == 0)) problems.add(m.id() + ": times " + quick + " quick, " + slow + " slow");
        }
        for (var r : machines.get("press").recipes()) {
            for (String in : r.inputs().keySet()) {
                if (!(in.startsWith("minecraft:") || in.startsWith("#") || madeOn.getOrDefault(in, "").equals("press"))) problems.add("press takes " + in);
            }
        }
        // every vanilla tag a recipe takes has a name in the messages (the recipe book shows it)
        YamlConfiguration messages = YamlConfiguration.loadConfiguration(
                java.nio.file.Path.of("src/main/resources/messages.yml").toFile());
        for (MachineSpec m : machines.values()) {
            for (var r : m.recipes()) {
                for (String in : r.inputs().keySet()) {
                    if (in.startsWith("#") && !in.startsWith("#economy:") && !messages.contains("machine.tag." + in.substring(1).replace("minecraft:", "")))
                        problems.add(m.id() + ": no name for the tag " + in + " (machine.tag." + in.substring(1) + ")");
                }
            }
        }
        MachineSpec still = machines.get("still");
        double agedMin = still.recipes().stream().filter(MachineSpec.Recipe::aging).mapToDouble(MachineSpec.Recipe::seconds).min().orElse(0);
        if (agedMin < 600) problems.add("still: an aged drink in " + agedMin + " s");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** A fish item's name has its rarity's colour (fish.yml rarities), the one the catch message uses, wherever the item is defined. */
    @Test
    void fishNamesWearTheirRarityColour() throws Exception {
        YamlConfiguration fish = yaml("fish.yml");
        Map<String, String> names = new java.util.HashMap<>();
        try (Stream<Path> s = Files.list(PACK.resolve("items"))) {
            for (Path f : s.toList()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                for (String k : y.getKeys(false)) names.put("economy:" + k, y.getString(k + ".name", ""));
            }
        }
        List<String> problems = new ArrayList<>();
        ConfigurationSection species = fish.getConfigurationSection("species");
        for (String id : species.getKeys(false)) {
            String item = species.getString(id + ".item", "economy:" + id);
            if (!item.startsWith("economy:")) continue;
            String colour = fish.getString("rarities." + species.getString(id + ".rarity") + ".color", "?");
            String name = names.getOrDefault(item, "");
            if (!name.toLowerCase(java.util.Locale.ROOT).startsWith("<" + colour.toLowerCase(java.util.Locale.ROOT) + ">"))
                problems.add(item + " " + name + " (want " + colour + ")");
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
