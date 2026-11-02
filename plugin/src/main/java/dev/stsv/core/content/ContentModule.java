package dev.stsv.core.content;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Loads content packs from plugins/STSV/contents and keeps the custom item registry.
 */
public final class ContentModule implements StsvModule, org.bukkit.event.Listener {

    private STSVPlugin plugin;
    private Logger log;
    private Path contentsDir;
    private final List<ContentPack> packs = new ArrayList<>();
    private final Map<NamespacedKey, CustomItem> items = new LinkedHashMap<>();

    @Override
    public String id() {
        return "content";
    }

    @Override
    public void enable(STSVPlugin plugin) throws IOException {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.contentsDir = plugin.getDataFolder().toPath().resolve("contents");
        syncDefaultContent();
        load();
        org.bukkit.Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void reload() throws IOException {
        load();
    }

    /** Food effects the data components cannot express (extra healing). */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onConsume(org.bukkit.event.player.PlayerItemConsumeEvent e) {
        itemOf(e.getItem()).map(CustomItem::food).filter(f -> f.heal() > 0)
                .ifPresent(f -> e.getPlayer().heal(f.heal()));
    }

    @Override
    public void disable() {
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------------------------------
    // Registry API
    // ------------------------------------------------------------------------------------------

    public Collection<CustomItem> items() {
        return Collections.unmodifiableCollection(items.values());
    }

    public List<ContentPack> packs() {
        return Collections.unmodifiableList(packs);
    }

    public Optional<CustomItem> item(NamespacedKey key) {
        return Optional.ofNullable(items.get(key));
    }

    /** Accepts "stsv:smelter" or just "smelter" (searched in every namespace). */
    public Optional<CustomItem> item(String id) {
        NamespacedKey key = id.contains(":") ? NamespacedKey.fromString(id.toLowerCase(Locale.ROOT)) : null;
        if (key != null) return item(key);
        return items.values().stream().filter(i -> i.key().getKey().equals(id)).findFirst();
    }

    public Optional<CustomItem> itemOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) return Optional.empty();
        ItemMeta meta = stack.getItemMeta();
        String id = meta.getPersistentDataContainer().get(CustomItem.idKey(), PersistentDataType.STRING);
        if (id == null) return Optional.empty();
        NamespacedKey key = NamespacedKey.fromString(id);
        return key == null ? Optional.empty() : item(key);
    }

    // ------------------------------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------------------------------

    private void load() throws IOException {
        packs.clear();
        items.clear();
        Files.createDirectories(contentsDir);
        try (Stream<Path> dirs = Files.list(contentsDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                packs.add(readPack(dir));
            }
        }
        for (ContentPack pack : packs) {
            if (!pack.has(pack.items())) continue;
            for (Path file : yamlFiles(pack.items())) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
                for (String id : yaml.getKeys(false)) {
                    ConfigurationSection sec = yaml.getConfigurationSection(id);
                    if (sec == null) continue;
                    try {
                        CustomItem item = parseItem(pack, id, sec);
                        if (items.putIfAbsent(item.key(), item) != null) {
                            log.warning("중복 아이템 id 무시: " + item.key() + " (" + file + ")");
                        }
                    } catch (IllegalArgumentException e) {
                        log.warning("아이템 '" + id + "' 읽기 실패 (" + file.getFileName() + "): " + e.getMessage());
                    }
                }
            }
        }
        log.info("콘텐츠 팩 " + packs.size() + "개, 커스텀 아이템 " + items.size() + "개 로드");
    }

    private ContentPack readPack(Path dir) {
        String id = dir.getFileName().toString();
        String namespace = id.toLowerCase(Locale.ROOT);
        Path packYml = dir.resolve("pack.yml");
        if (Files.exists(packYml)) {
            namespace = YamlConfiguration.loadConfiguration(packYml.toFile()).getString("namespace", namespace);
        }
        if (!namespace.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("잘못된 namespace '" + namespace + "' (" + dir + ")");
        }
        return new ContentPack(id, namespace, dir);
    }

    static List<Path> yamlFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".yml") || n.endsWith(".yaml");
            }).sorted().toList();
        }
    }

    private ContentPack currentPack; // pack whose item is being parsed (for relative model names)

    private CustomItem parseItem(ContentPack pack, String id, ConfigurationSection sec) {
        currentPack = pack;
        if (!id.matches("[a-z0-9_./-]+")) throw new IllegalArgumentException("id는 영문 소문자/숫자/_만 가능");
        NamespacedKey key = new NamespacedKey(pack.namespace(), id);

        String matName = sec.getString("material", "PAPER");
        Material material = Material.matchMaterial(matName);
        if (material == null || !material.isItem()) throw new IllegalArgumentException("알 수 없는 material: " + matName);

        ModelRef model = null;
        boolean handheld = sec.getBoolean("handheld", false);
        if (sec.isString("model")) {
            model = new ModelRef(ModelRef.Kind.BBMODEL, sec.getString("model"), handheld);
            if (!Files.exists(pack.models().resolve(model.value() + ".bbmodel"))) {
                throw new IllegalArgumentException("models/" + model.value() + ".bbmodel 파일이 없음");
            }
        } else if (sec.isString("texture")) {
            model = new ModelRef(ModelRef.Kind.TEXTURE, sec.getString("texture"), handheld);
            if (!Files.exists(pack.textures().resolve(model.value() + ".png"))) {
                throw new IllegalArgumentException("textures/" + model.value() + ".png 파일이 없음");
            }
        } else if (sec.isString("item-model")) {
            model = new ModelRef(ModelRef.Kind.RAW, sec.getString("item-model"), handheld);
        }

        Integer maxStack = sec.contains("max-stack") ? Math.clamp(sec.getInt("max-stack"), 1, 99) : null;
        Boolean glint = sec.contains("glint") ? sec.getBoolean("glint") : null;

        FurnitureSpec furniture = null;
        ConfigurationSection f = sec.getConfigurationSection("furniture");
        if (f != null) furniture = parseFurniture(f);

        Integer fuel = null;
        if (sec.contains("fuel")) {
            fuel = sec.getInt("fuel");
            if (fuel <= 0) throw new IllegalArgumentException("fuel 은 1 이상의 틱 수 (석탄 = 1600)");
            if (!material.isFuel()) {
                throw new IllegalArgumentException("fuel 을 쓰려면 material 이 화로 연료여야 함 (예: coal, charcoal, stick). "
                        + "현재: " + material.getKey().getKey());
            }
        }

        CustomItem.Equipment equipment = null;
        ConfigurationSection eq = sec.getConfigurationSection("equipment");
        if (eq != null) {
            org.bukkit.inventory.EquipmentSlot slot = switch (eq.getString("slot", "").toLowerCase(Locale.ROOT)) {
                case "head", "helmet" -> org.bukkit.inventory.EquipmentSlot.HEAD;
                case "chest", "chestplate" -> org.bukkit.inventory.EquipmentSlot.CHEST;
                case "legs", "leggings" -> org.bukkit.inventory.EquipmentSlot.LEGS;
                case "feet", "boots" -> org.bukkit.inventory.EquipmentSlot.FEET;
                default -> throw new IllegalArgumentException("equipment.slot 은 head / chest / legs / feet");
            };
            NamespacedKey asset = NamespacedKey.fromString(eq.getString("asset", ""));
            if (asset == null) throw new IllegalArgumentException("equipment.asset 은 '네임스페이스:이름' (예: stsv:steel)");
            equipment = new CustomItem.Equipment(slot, asset);
        }

        CustomItem.Food food = sec.isConfigurationSection("food") ? parseFood(sec.getConfigurationSection("food")) : null;
        CustomItem.Skin skin = sec.isConfigurationSection("skin") ? parseSkin(pack, sec.getConfigurationSection("skin")) : null;
        CustomItem.Cosmetic cosmetic = null;
        if (sec.isConfigurationSection("cosmetic")) {
            String slot = sec.getString("cosmetic.slot", "head").toLowerCase(Locale.ROOT);
            if (!slot.equals("head")) throw new IllegalArgumentException("cosmetic.slot 은 현재 head 만 지원");
            CustomItem.Rarity rarity;
            try {
                rarity = CustomItem.Rarity.valueOf(sec.getString("cosmetic.rarity", "common").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("cosmetic.rarity 는 common / rare / epic / legendary");
            }
            cosmetic = new CustomItem.Cosmetic(org.bukkit.inventory.EquipmentSlot.HEAD, rarity, sec.getString("cosmetic.obtain"));
        }

        CustomItem.Effects effects = sec.isConfigurationSection("effects")
                ? parseEffects(pack, sec.getConfigurationSection("effects")) : null;
        if (effects != null && effects.chargeTicks() > 0 && food != null) {
            throw new IllegalArgumentException("effects.charge 와 food 는 같이 쓸 수 없음 (둘 다 우클릭 사용)");
        }

        // voucher: { cosmetic: stsv:tomato_mask }  -> right-click to own the cosmetic (the voucher is used up)
        String voucher = null;
        if (sec.isConfigurationSection("voucher")) {
            voucher = sec.getString("voucher.cosmetic");
            if (voucher == null) throw new IllegalArgumentException("voucher.cosmetic 이 없음");
            if (!voucher.contains(":")) voucher = pack.namespace() + ":" + voucher;
        }

        return new CustomItem(key, pack, material, sec.getString("name"), sec.getStringList("lore"), model,
                maxStack, glint, sec.getBoolean("unbreakable", false), furniture, fuel, equipment, food, skin, cosmetic,
                effects, voucher);
    }

    /**
     * food:
     *   nutrition: 6          # hunger points restored (bread = 5)
     *   saturation: 7.2
     *   always-eat: false     # can be eaten with a full hunger bar
     *   seconds: 1.6          # time to eat
     *   animation: eat        # eat, drink, block, bow, spear, crossbow, spyglass, toot_horn, brush, bundle, none
     *   sound: entity.generic.eat
     *   remainder: minecraft:bowl
     *   cooldown: 0           # seconds
     *   effects: [ { effect: speed, seconds: 10, level: 1, chance: 1.0 } ]
     *   clear-effects: false  # like milk
     *   heal: 0               # extra half-hearts
     */
    private CustomItem.Food parseFood(ConfigurationSection f) {
        String anim = f.getString("animation", "eat").toLowerCase(Locale.ROOT);
        io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation animation = switch (anim) {
            case "eat" -> io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.EAT;
            case "drink" -> io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.DRINK;
            case "block" -> io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.BLOCK;
            case "bow" -> io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.BOW;
            case "spear" -> io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.SPEAR;
            case "crossbow" -> io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.CROSSBOW;
            case "spyglass" -> io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.SPYGLASS;
            case "toot_horn" -> io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.TOOT_HORN;
            case "brush" -> io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.BRUSH;
            case "bundle" -> io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.BUNDLE;
            case "none" -> io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.NONE;
            default -> throw new IllegalArgumentException("food.animation 을 알 수 없음: " + anim);
        };
        String soundId = f.getString("sound", animation == io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.DRINK
                ? "entity.generic.drink" : "entity.generic.eat");
        List<CustomItem.FoodEffect> effects = new ArrayList<>();
        for (Object o : f.getList("effects", List.of())) {
            if (!(o instanceof java.util.Map<?, ?> m)) throw new IllegalArgumentException("food.effects 항목 형식 오류");
            String id = String.valueOf(m.get("effect"));
            NamespacedKey ek = NamespacedKey.fromString(id.contains(":") ? id : "minecraft:" + id);
            org.bukkit.potion.PotionEffectType type = ek == null ? null : org.bukkit.Registry.EFFECT.get(ek);
            if (type == null) throw new IllegalArgumentException("알 수 없는 효과: " + id);
            int ticks = (int) Math.round(toDouble(m.get("seconds"), 5) * 20);
            int level = (int) toDouble(m.get("level"), 1);
            float chance = (float) toDouble(m.get("chance"), 1);
            effects.add(new CustomItem.FoodEffect(new org.bukkit.potion.PotionEffect(type, ticks, Math.max(0, level - 1)), chance));
        }
        int nutrition = f.getInt("nutrition", 4);
        if (nutrition < 0) throw new IllegalArgumentException("food.nutrition 은 0 이상");
        return new CustomItem.Food(nutrition, (float) f.getDouble("saturation", nutrition * 1.2),
                f.getBoolean("always-eat", false), (float) Math.max(0.05, f.getDouble("seconds", 1.6)), animation,
                net.kyori.adventure.key.Key.key(soundId), f.getString("remainder"),
                (float) Math.max(0, f.getDouble("cooldown", 0)), List.copyOf(effects),
                f.getBoolean("clear-effects", false), Math.max(0, f.getDouble("heal", 0)));
    }

    /**
     * effects:
     *   swing:  { model: test_slash, scale: 2.2, sound: entity.player.attack.sweep }
     *   charge: { seconds: 1.0, sound: item.trident.thunder }   # hold right-click, release = big slash
     */
    private CustomItem.Effects parseEffects(ContentPack pack, ConfigurationSection e) {
        NamespacedKey model = null;
        float scale = 2f;
        net.kyori.adventure.key.Key swingSound = net.kyori.adventure.key.Key.key("entity.player.attack.sweep");
        if (e.isConfigurationSection("swing")) {
            String m = e.getString("swing.model");
            if (m == null) throw new IllegalArgumentException("effects.swing.model 이 없음");
            if (m.contains(":")) {
                model = NamespacedKey.fromString(m);
            } else {
                if (!Files.exists(pack.models().resolve(m + ".bbmodel"))) throw new IllegalArgumentException("models/" + m + ".bbmodel 이 없음");
                model = new NamespacedKey(pack.namespace(), "model/" + m);
            }
            scale = (float) e.getDouble("swing.scale", 2.0);
            swingSound = net.kyori.adventure.key.Key.key(e.getString("swing.sound", "entity.player.attack.sweep"));
        }
        int charge = 0;
        net.kyori.adventure.key.Key chargeSound = net.kyori.adventure.key.Key.key("item.trident.thunder");
        if (e.isConfigurationSection("charge")) {
            if (model == null) throw new IllegalArgumentException("effects.charge 는 effects.swing 의 참격 모델을 씀");
            charge = (int) Math.max(1, Math.round(e.getDouble("charge.seconds", 1.0) * 20));
            chargeSound = net.kyori.adventure.key.Key.key(e.getString("charge.sound", "item.trident.thunder"));
        }
        return new CustomItem.Effects(model, scale, swingSound, charge, chargeSound);
    }

    private static double toDouble(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("숫자가 아님: " + o);
        }
    }

    /**
     * skin:
     *   model: test_sword_skin                 # .bbmodel in this pack, or a full item model id "ns:id"
     *   applies-to: ["#minecraft:swords", "stsv:test_sword"]
     *   equipment: stsv:test                   # optional, armor skins
     *   return-on-remove: true                 # give the book back when the skin is removed
     */
    private CustomItem.Skin parseSkin(ContentPack pack, ConfigurationSection s) {
        String m = s.getString("model");
        if (m == null) throw new IllegalArgumentException("skin.model 이 없음");
        NamespacedKey model;
        if (m.contains(":")) {
            model = NamespacedKey.fromString(m);
        } else {
            if (!Files.exists(pack.models().resolve(m + ".bbmodel"))) throw new IllegalArgumentException("models/" + m + ".bbmodel 이 없음");
            model = new NamespacedKey(pack.namespace(), "model/" + m);
        }
        List<String> applies = s.getStringList("applies-to");
        if (applies.isEmpty()) throw new IllegalArgumentException("skin.applies-to 가 비어 있음");
        NamespacedKey asset = s.isString("equipment") ? NamespacedKey.fromString(s.getString("equipment")) : null;
        return new CustomItem.Skin(model, List.copyOf(applies), asset, s.getBoolean("return-on-remove", true));
    }

    private FurnitureSpec parseFurniture(ConfigurationSection f) {
        List<int[]> cells = new ArrayList<>();
        for (Object o : f.getList("cells", List.of(List.of(0, 0, 0)))) {
            if (!(o instanceof List<?> l) || l.size() != 3) throw new IllegalArgumentException("cells 항목은 [x, y, z] 형식");
            cells.add(new int[]{num(l.get(0)), num(l.get(1)), num(l.get(2))});
        }
        FurnitureSpec.Rotation rotation;
        try {
            rotation = FurnitureSpec.Rotation.valueOf(f.getString("rotation", "cardinal").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rotation은 cardinal 또는 free");
        }
        // seat: { height: 0.6 }                       one seat in the anchor cell
        // seats: [ { cell: [0,0,0], height: 0.6 }, ...] benches / sofas
        List<FurnitureSpec.Seat> seats = new ArrayList<>();
        List<ConfigurationSection> seatSections = new ArrayList<>();
        if (f.isConfigurationSection("seat")) seatSections.add(f.getConfigurationSection("seat"));
        for (Object o : f.getList("seats", List.of())) {
            if (o instanceof java.util.Map<?, ?> map) seatSections.add(section(map));
        }
        for (ConfigurationSection s : seatSections) {
            int[] cell = {0, 0, 0};
            List<?> c = s.getList("cell");
            if (c != null) {
                if (c.size() != 3) throw new IllegalArgumentException("seat.cell 은 [x, y, z] 형식");
                cell = new int[]{num(c.get(0)), num(c.get(1)), num(c.get(2))};
            }
            final int[] fc = cell;
            if (cells.stream().noneMatch(x -> java.util.Arrays.equals(x, fc))) {
                throw new IllegalArgumentException("seat.cell " + java.util.Arrays.toString(cell) + " 이 cells 안에 없음");
            }
            seats.add(new FurnitureSpec.Seat(cell, s.getDouble("height", 0.5)));
        }
        // parts: [ { model: test_fan_blade, position: [16, 31, 8], spin: { axis: y, rpm: 40 } } ]
        List<FurnitureSpec.Part> parts = new ArrayList<>();
        for (Object o : f.getList("parts", List.of())) {
            if (!(o instanceof java.util.Map<?, ?> map)) throw new IllegalArgumentException("parts 항목 형식 오류");
            ConfigurationSection p = section(map);
            String m = p.getString("model");
            if (m == null) throw new IllegalArgumentException("parts.model 이 없음");
            NamespacedKey key = m.contains(":") ? NamespacedKey.fromString(m) : new NamespacedKey(currentPack.namespace(), "model/" + m);
            if (!m.contains(":") && !Files.exists(currentPack.models().resolve(m + ".bbmodel"))) {
                throw new IllegalArgumentException("parts: models/" + m + ".bbmodel 이 없음");
            }
            String axis = null;
            float rpm = 0;
            if (p.isConfigurationSection("spin")) {
                axis = p.getString("spin.axis", "y").toLowerCase(Locale.ROOT);
                if (!List.of("x", "y", "z").contains(axis)) throw new IllegalArgumentException("spin.axis 는 x, y, z");
                rpm = (float) p.getDouble("spin.rpm", 30);
            }
            parts.add(new FurnitureSpec.Part(key, vec(p.getDoubleList("position"), 8), axis, rpm));
        }
        return new FurnitureSpec(cells, f.getBoolean("solid", true), rotation, f.getString("behavior"),
                vec(f.getDoubleList("translation"), 0), vec(f.getDoubleList("scale"), 1),
                Math.clamp(f.getInt("brightness", -1), -1, 15), List.copyOf(seats), List.copyOf(parts));
    }

    /**
     * A list entry like "{ model: x, spin: { axis: y } }" arrives as a plain Map. createSection turns
     * nested maps into sections too (a plain set() would keep "spin" as a Map and isConfigurationSection
     * would say no, which silently dropped the fan's spin before).
     */
    static ConfigurationSection section(java.util.Map<?, ?> map) {
        return new YamlConfiguration().createSection("entry", map);
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        throw new IllegalArgumentException("숫자가 아님: " + o);
    }

    private static Vector3f vec(List<Double> l, float def) {
        if (l == null || l.size() != 3) return new Vector3f(def);
        return new Vector3f(l.get(0).floatValue(), l.get(1).floatValue(), l.get(2).floatValue());
    }

    /** Installs / updates the content bundled in the jar (see {@link DefaultContentSync}). */
    /**
     * Default content shipped inside another plugin's jar (a content plugin such as the economy server):
     * entries under jarPrefix are installed to contents/ like the core's own, with their own record file,
     * so they are upgraded on update and local edits are kept. Call from the plugin's onLoad (before the
     * core enables); a later call installs and reloads at once.
     */
    public record Bundle(String owner, java.io.File jar, String prefix, String historyResource, ClassLoader loader) {}

    private static final List<Bundle> BUNDLES = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void registerBundle(org.bukkit.plugin.java.JavaPlugin owner, java.io.File jar) {
        Bundle b = new Bundle(owner.getName(), jar, "default-content/", "default-content-history.txt", owner.getClass().getClassLoader());
        BUNDLES.removeIf(x -> x.owner().equals(b.owner()));
        BUNDLES.add(b);
        dev.stsv.core.STSVPlugin core = dev.stsv.core.STSVPlugin.get();
        if (core != null && core.content() != null && core.content().contentsDir != null) {
            try {
                core.content().syncBundle(b);
                core.modules().reloadAll();
            } catch (IOException e) {
                core.getLogger().warning(b.owner() + " 기본 콘텐츠 설치 실패: " + e.getMessage());
            }
        }
    }

    private void syncDefaultContent() throws IOException {
        sync("STSV", plugin.jarFile(), "default-content/", shippedHashes(plugin.getResource("default-content-history.txt")), ".default-content");
        for (Bundle b : BUNDLES) syncBundle(b);
    }

    private void syncBundle(Bundle b) throws IOException {
        Map<String, Set<String>> history;
        try (InputStream in = b.loader().getResourceAsStream(b.historyResource())) {
            history = shippedHashes(in);
        }
        sync(b.owner(), b.jar(), b.prefix(), history, ".default-content-" + b.owner().toLowerCase(Locale.ROOT));
    }

    private void sync(String owner, java.io.File jarFile, String prefix, Map<String, Set<String>> history, String manifest) throws IOException {
        Map<String, byte[]> bundled = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(jarFile)) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (!entry.getName().startsWith(prefix) || entry.isDirectory()) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    bundled.put(entry.getName().substring(prefix.length()), in.readAllBytes());
                }
            }
        }
        DefaultContentSync.Report r = DefaultContentSync.sync(contentsDir, bundled, history, manifest);
        if (r.installed() + r.updated() + r.removed() > 0) {
            log.info(owner + " 기본 콘텐츠: 새 파일 " + r.installed() + "개, 업데이트 " + r.updated() + "개, 삭제 " + r.removed() + "개");
        }
        for (String rel : r.kept()) {
            log.warning("기본 콘텐츠 '" + rel + "' 는 직접 수정된 파일이라 업데이트/삭제하지 않았습니다. "
                    + "새 버전으로 맞추려면 파일을 지우고 서버를 다시 시작하세요.");
        }
    }

    /** Hashes of every default file version ever shipped (generated by tools/content_history.py). */
    private static Map<String, Set<String>> shippedHashes(InputStream resource) throws IOException {
        Map<String, Set<String>> out = new HashMap<>();
        try (InputStream in = resource) {
            if (in == null) return out;
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                int sep = line.indexOf(' ');
                if (sep > 0) out.computeIfAbsent(line.substring(sep + 1).trim(), k -> new HashSet<>()).add(line.substring(0, sep));
            }
        }
        return out;
    }
}
