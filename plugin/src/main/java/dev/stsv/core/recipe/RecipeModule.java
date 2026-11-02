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
import java.util.Optional;
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
    /** "stsv:coke" / "minecraft:stick" -> burn time in ticks */
    private final java.util.Map<String, Integer> fuels = new java.util.LinkedHashMap<>();

    /** File the in-game editor writes recipes to (inside the pack that owns the namespace). */
    public static final String EDITOR_FILE = "editor.yml";
    /** File (pack root) holding fuel burn times. */
    public static final String FUELS_FILE = "fuels.yml";

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

    // ------------------------------------------------------------------------------------------
    // Fuels: custom burn times. Custom items only burn if they have a fuel time (their base
    // material being coal does not make them coal).
    // ------------------------------------------------------------------------------------------

    /** "stsv:id" for custom items, "minecraft:id" for vanilla. */
    public String idOf(ItemStack stack) {
        return content.itemOf(stack).map(i -> i.key().asString())
                .orElse(stack.getType().getKey().asString());
    }

    public java.util.Map<String, Integer> fuels() {
        return java.util.Collections.unmodifiableMap(fuels);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(org.bukkit.event.inventory.FurnaceBurnEvent e) {
        ItemStack fuel = e.getFuel();
        Integer ticks = fuels.get(idOf(fuel));
        if (ticks != null) {
            e.setBurnTime(ticks);
        } else if (content.itemOf(fuel).isPresent()) {
            e.setCancelled(true);
        }
    }

    private void loadFuels() {
        fuels.clear();
        for (dev.stsv.core.content.CustomItem item : content.items()) {
            if (item.fuel() != null) fuels.put(item.key().asString(), item.fuel());
        }
        for (ContentPack pack : content.packs()) {
            Path file = pack.dir().resolve(FUELS_FILE);
            if (!Files.exists(file)) continue;
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            for (String id : yaml.getKeys(false)) {
                String full = id.contains(":") ? id : "minecraft:" + id;
                int ticks = yaml.getInt(id);
                if (ticks <= 0) {
                    plugin.getLogger().warning("연료 '" + id + "' 의 시간은 1 이상이어야 합니다 (" + file.getFileName() + ")");
                    continue;
                }
                if (!full.startsWith("minecraft:") && content.item(full).isEmpty()) {
                    plugin.getLogger().warning("연료 '" + id + "': 알 수 없는 커스텀 아이템");
                    continue;
                }
                fuels.put(full, ticks);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Editor storage (used by the in-game recipe editor and /stsv fuel)
    // ------------------------------------------------------------------------------------------

    public Optional<ContentPack> packOf(String namespace) {
        return content.packs().stream().filter(p -> p.namespace().equals(namespace)).findFirst();
    }

    /** Writes (or replaces) a recipe in &lt;pack&gt;/recipes/editor.yml and reloads recipes. */
    public void saveRecipe(NamespacedKey key, java.util.Map<String, Object> data) throws IOException {
        ContentPack pack = packOf(key.getNamespace())
                .orElseThrow(() -> new IllegalArgumentException("'" + key.getNamespace() + "' 네임스페이스의 콘텐츠 팩이 없음"));
        Path file = pack.recipes().resolve(EDITOR_FILE);
        Files.createDirectories(file.getParent());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        yaml.options().setHeader(List.of("게임 안 레시피 편집기(/stsv recipe)가 관리하는 파일입니다. 직접 고쳐도 됩니다."));
        yaml.set(key.getKey(), null);
        data.forEach((k, v) -> yaml.set(key.getKey() + "." + k, v));
        yaml.save(file.toFile());
        reload();
    }

    /** Removes a recipe written by the editor. Returns false if it is not in editor.yml. */
    public boolean deleteRecipe(NamespacedKey key) throws IOException {
        Optional<ContentPack> pack = packOf(key.getNamespace());
        if (pack.isEmpty()) return false;
        Path file = pack.get().recipes().resolve(EDITOR_FILE);
        if (!Files.exists(file)) return false;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        if (!yaml.contains(key.getKey())) return false;
        yaml.set(key.getKey(), null);
        yaml.save(file.toFile());
        reload();
        return true;
    }

    /** Sets (ticks > 0) or removes (null) a fuel entry in &lt;pack&gt;/fuels.yml. */
    public void setFuel(String id, Integer ticks, String namespace) throws IOException {
        ContentPack pack = packOf(namespace)
                .orElseThrow(() -> new IllegalArgumentException("'" + namespace + "' 네임스페이스의 콘텐츠 팩이 없음"));
        Path file = pack.dir().resolve(FUELS_FILE);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        yaml.options().setHeader(List.of("화로 연료 시간 (틱, 20틱 = 1초, 석탄 = 1600). /stsv fuel 로도 편집됩니다."));
        yaml.set(id, ticks);
        yaml.save(file.toFile());
        loadFuels();
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
        loadFuels();
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
