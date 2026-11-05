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

    static boolean exists(String id, Set<String> ours) {
        if (id.startsWith("economy:")) return ours.contains(id);
        if (id.startsWith("stsv:")) return Files.exists(Path.of(System.getProperty("stsv.content")).resolve("../../content/stsv"));
        Material m = Material.matchMaterial(id);
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
        RequestSpec requests = RequestSpec.parse(yaml("requests.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));

        for (String id : shop.sell().keySet()) if (!exists(id, ours)) problems.add("shop sell: " + id);
        for (var b : shop.buy()) if (!exists(b.id(), ours)) problems.add("shop buy: " + b.id());
        for (var m : shop.merchant()) if (!exists(m.id(), ours)) problems.add("merchant: " + m.id());
        for (var v : shop.vanity()) if (!v.kind().equals("cosmetic") && !v.kind().equals("title")) problems.add("vanity kind: " + v.kind());
        for (String id : jobs.products().keySet()) if (!exists(id, ours)) problems.add("product: " + id);
        for (var j : jobs.jobs().values()) {
            if (!exists(j.icon(), ours)) problems.add("job icon: " + j.icon());
            for (var d : j.drops().values()) for (String id : d) if (!exists(id, ours)) problems.add("job drop: " + id);
        }
        for (MachineSpec m : machines.values()) {
            if (!exists(m.item(), ours)) problems.add("machine item: " + m.item());
            if (!jobs.jobs().containsKey(m.job())) problems.add("machine job: " + m.job());
            for (var r : m.recipes()) {
                if (!exists(r.output(), ours)) problems.add(m.id() + " out: " + r.output());
                for (String in : r.inputs().keySet()) if (!exists(in, ours)) problems.add(m.id() + " in: " + in);
                if (r.level() > jobs.maxLevel()) problems.add(m.id() + " level " + r.level());
            }
        }
        for (var e : requests.pool()) if (!exists(e.item(), ours)) problems.add("request: " + e.item());
        for (var c : requests.community()) if (!exists(c.item(), ours)) problems.add("community: " + c.item());
        assertTrue(problems.isEmpty(), String.join("\n", problems));

        // the plan's shape: 6 jobs, 54 products (+ 8 machines), the events the shop and jobs hook
        assertEquals(6, jobs.jobs().size());
        assertEquals(54 + 8 + 14, ours.size(), "54 job products + 8 machines + 14 crop items");
        assertTrue(ours.containsAll(jobs.products().keySet()));
        assertEquals(8, machines.size());
        assertTrue(events.keySet().containsAll(List.of("shortage", "crash", "harvest", "merchant", "tax_audit")));
        assertFalse(Files.exists(PACK.resolve("season.yml")), "no season plan any more");
        // every job product can actually be made: by a recipe, a machine or a job drop
        Set<String> makeable = new HashSet<>();
        for (MachineSpec m : machines.values()) for (var r : m.recipes()) makeable.add(r.output());
        for (var j : jobs.jobs().values()) for (var d : j.drops().values()) makeable.addAll(d);
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
        for (String k : crops.getKeys(false)) {
            if (k.equals("tools")) {
                var tools = new dev.stsv.core.crop.CropTools();
                tools.read(crops.getConfigurationSection("tools"));
                for (String id : tools.allItems()) if (!exists(id, ours)) problems.add("crop tool: " + id);
                continue;
            }
            var spec = dev.stsv.core.crop.CropSpec.parse("economy", k, crops.getConfigurationSection(k));
            if (!exists(spec.seed(), ours)) problems.add("crop " + k + " seed: " + spec.seed());
            for (var d : spec.harvest()) {
                if (!exists(d.item(), ours)) problems.add("crop " + k + " harvest: " + d.item());
                makeable.add(d.item());
            }
            for (String model : spec.models()) {
                if (!Files.exists(PACK.resolve("models/" + model.substring(model.indexOf('/') + 1) + ".bbmodel"))) problems.add("crop model " + model);
            }
            String w = spec.witheredModel();
            if (!Files.exists(PACK.resolve("models/" + w.substring(w.indexOf('/') + 1) + ".bbmodel"))) problems.add("withered model " + w);
            if (spec.extra().containsKey("job") && !jobs.jobs().containsKey(spec.extra().get("job"))) problems.add("crop job " + spec.extra().get("job"));
        }
        for (var b : shop.buy()) makeable.add(b.id());
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
        assertEquals(12, menus.size());
        for (MenuSpec m : menus) {
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
                assertTrue(Files.exists(def), m.id() + " button icon " + b.on().icon());
            }
        }
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
        for (String r : List.of("market_buy", "market_cancel", "market_expired", "auction_unsold", "auction_win", "auction_cancel",
                "trade", "trade_cancel", "stall_release", "stall_evicted")) {
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
}
