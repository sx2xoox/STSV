package dev.stsv.core.recipe;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentModule;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.module.StsvModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Registers recipes from contents/&lt;pack&gt;/recipes/*.yml.
 *
 * <pre>
 * smelter:
 *   type: shaped              # shaped | shapeless | furnace | blasting | smoking | campfire | stonecutting
 *   pattern: ["BBB", "BFB", "III"]
 *   keys: { B: minecraft:bricks, F: minecraft:blast_furnace, I: "#minecraft:iron_ores" }
 *   result: stsv:smelter      # or { item: stsv:smelter, amount: 2 }
 *
 * gold_chunk_to_ingot:
 *   type: blasting
 *   input: stsv:gold_chunk
 *   result: minecraft:gold_ingot
 *   experience: 0.7
 *   time: 100                 # ticks
 * </pre>
 * Ingredients: "stsv:id" = exact custom item, "minecraft:id" or "id" = vanilla material, "#ns:tag" = item tag.
 */
public final class RecipeModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    private ContentModule content;
    private final List<NamespacedKey> registered = new ArrayList<>();

    @Override
    public String id() {
        return "recipes";
    }

    @Override
    public void enable(STSVPlugin plugin) throws IOException {
        this.plugin = plugin;
        this.content = plugin.content();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        load();
    }

    @Override
    public void reload() throws IOException {
        load();
        Bukkit.getOnlinePlayers().forEach(p -> p.discoverRecipes(registered));
    }

    @Override
    public void disable() {
        unregisterAll();
        HandlerList.unregisterAll(this);
    }

    public List<NamespacedKey> registered() {
        return List.copyOf(registered);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipes(registered);
    }

    // ------------------------------------------------------------------------------------------
    // Guard: custom items are not their base material. Keep them out of vanilla/foreign recipes,
    // otherwise e.g. a custom item on paper could be crafted into books.
    // ------------------------------------------------------------------------------------------

    private boolean ours(NamespacedKey key) {
        return key != null && registered.contains(key);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof CraftingRecipe recipe) || ours(recipe.getKey())) return;
        for (ItemStack ingredient : e.getInventory().getMatrix()) {
            if (content.itemOf(ingredient).isPresent()) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent e) {
        if (e.getRecipe() != null && !ours(e.getRecipe().getKey()) && content.itemOf(e.getSource()).isPresent()) {
            e.setCancelled(true);
        }
    }

    private void unregisterAll() {
        if (!registered.isEmpty()) Bukkit.removeRecipes(registered);
        registered.clear();
    }

    private void load() throws IOException {
        unregisterAll();
        List<Recipe> parsed = new ArrayList<>();
        for (ContentPack pack : content.packs()) {
            if (!pack.has(pack.recipes())) continue;
            List<Path> files;
            try (Stream<Path> s = Files.walk(pack.recipes())) {
                files = s.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml")).sorted().toList();
            }
            for (Path file : files) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
                for (String id : yaml.getKeys(false)) {
                    ConfigurationSection sec = yaml.getConfigurationSection(id);
                    if (sec == null) continue;
                    NamespacedKey key = new NamespacedKey(pack.namespace(), id);
                    try {
                        if (registered.contains(key)) throw new IllegalArgumentException("중복된 레시피 id");
                        parsed.add(parse(key, sec));
                        registered.add(key);
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("레시피 '" + key + "' 읽기 실패 (" + file.getFileName() + "): " + ex.getMessage());
                    }
                }
            }
        }
        if (!parsed.isEmpty() && !Bukkit.addRecipes(parsed)) {
            plugin.getLogger().warning("일부 레시피 등록 실패 (다른 플러그인과 id 충돌?)");
        }
        plugin.getLogger().info("레시피 " + registered.size() + "개 등록");
    }

    private Recipe parse(NamespacedKey key, ConfigurationSection sec) {
        String type = sec.getString("type", "shaped").toLowerCase(Locale.ROOT);
        ItemStack result = result(sec);
        return switch (type) {
            case "shaped" -> {
                List<String> pattern = sec.getStringList("pattern");
                if (pattern.isEmpty() || pattern.size() > 3) throw new IllegalArgumentException("pattern은 1~3줄");
                ShapedRecipe r = new ShapedRecipe(key, result);
                r.shape(pattern.toArray(String[]::new));
                ConfigurationSection keys = sec.getConfigurationSection("keys");
                if (keys == null) throw new IllegalArgumentException("keys가 없음");
                for (String k : keys.getKeys(false)) {
                    if (k.length() != 1) throw new IllegalArgumentException("keys의 키는 한 글자: " + k);
                    r.setIngredient(k.charAt(0), choice(keys.getString(k)));
                }
                yield r;
            }
            case "shapeless" -> {
                ShapelessRecipe r = new ShapelessRecipe(key, result);
                List<String> ingredients = sec.getStringList("ingredients");
                if (ingredients.isEmpty() || ingredients.size() > 9) throw new IllegalArgumentException("ingredients는 1~9개");
                ingredients.forEach(i -> r.addIngredient(choice(i)));
                yield r;
            }
            case "furnace" -> new FurnaceRecipe(key, result, choice(input(sec)), xp(sec), time(sec, 200));
            case "blasting" -> new BlastingRecipe(key, result, choice(input(sec)), xp(sec), time(sec, 100));
            case "smoking" -> new SmokingRecipe(key, result, choice(input(sec)), xp(sec), time(sec, 100));
            case "campfire" -> new CampfireRecipe(key, result, choice(input(sec)), xp(sec), time(sec, 600));
            case "stonecutting" -> new StonecuttingRecipe(key, result, choice(input(sec)));
            default -> throw new IllegalArgumentException("알 수 없는 type: " + type);
        };
    }

    private static String input(ConfigurationSection sec) {
        String in = sec.getString("input");
        if (in == null) throw new IllegalArgumentException("input이 없음");
        return in;
    }

    private static float xp(ConfigurationSection sec) {
        return (float) sec.getDouble("experience", 0.1);
    }

    private static int time(ConfigurationSection sec, int def) {
        return Math.max(1, sec.getInt("time", def));
    }

    private ItemStack result(ConfigurationSection sec) {
        String id;
        int amount = 1;
        if (sec.isConfigurationSection("result")) {
            ConfigurationSection r = sec.getConfigurationSection("result");
            id = r.getString("item");
            amount = r.getInt("amount", 1);
        } else {
            id = sec.getString("result");
        }
        if (id == null) throw new IllegalArgumentException("result가 없음");
        return stack(id, amount);
    }

    /** Builds an ItemStack for a custom (any non-minecraft namespace) or vanilla id. */
    public ItemStack stack(String id, int amount) {
        if (isCustom(id)) {
            CustomItem item = content.item(id).orElseThrow(() -> new IllegalArgumentException("알 수 없는 커스텀 아이템: " + id));
            return item.create(amount);
        }
        Material m = Material.matchMaterial(id);
        if (m == null || !m.isItem()) throw new IllegalArgumentException("알 수 없는 아이템: " + id);
        return new ItemStack(m, amount);
    }

    private RecipeChoice choice(String id) {
        if (id == null) throw new IllegalArgumentException("재료가 비어 있음");
        if (id.startsWith("#")) {
            NamespacedKey tagKey = NamespacedKey.fromString(id.substring(1));
            Tag<Material> tag = tagKey == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, tagKey, Material.class);
            if (tag == null) throw new IllegalArgumentException("알 수 없는 태그: " + id);
            return new RecipeChoice.MaterialChoice(tag);
        }
        if (isCustom(id)) {
            // Match by STSV id, not by exact item: renaming an item or editing lore keeps old items valid.
            CustomItem item = content.item(id).orElseThrow(() -> new IllegalArgumentException("알 수 없는 커스텀 아이템: " + id));
            NamespacedKey want = item.key();
            return RecipeChoice.predicateChoice(
                    s -> content.itemOf(s).map(c -> c.key().equals(want)).orElse(false), item.create(1));
        }
        Material m = Material.matchMaterial(id);
        if (m == null || !m.isItem()) throw new IllegalArgumentException("알 수 없는 재료: " + id);
        return new RecipeChoice.MaterialChoice(m);
    }

    private static boolean isCustom(String id) {
        return id.contains(":") && !id.startsWith("minecraft:");
    }
}
