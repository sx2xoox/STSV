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
public final class ContentModule implements StsvModule {

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
    }

    @Override
    public void reload() throws IOException {
        load();
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

    private CustomItem parseItem(ContentPack pack, String id, ConfigurationSection sec) {
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

        return new CustomItem(key, pack, material, sec.getString("name"), sec.getStringList("lore"), model,
                maxStack, glint, sec.getBoolean("unbreakable", false), furniture, fuel, equipment);
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
            if (o instanceof java.util.Map<?, ?> map) {
                YamlConfiguration tmp = new YamlConfiguration();
                map.forEach((k, v) -> tmp.set(String.valueOf(k), v));
                seatSections.add(tmp);
            }
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
        return new FurnitureSpec(cells, f.getBoolean("solid", true), rotation, f.getString("behavior"),
                vec(f.getDoubleList("translation"), 0), vec(f.getDoubleList("scale"), 1),
                Math.clamp(f.getInt("brightness", -1), -1, 15), List.copyOf(seats));
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
    private void syncDefaultContent() throws IOException {
        Map<String, byte[]> bundled = new LinkedHashMap<>();
        String prefix = "default-content/";
        try (JarFile jar = new JarFile(plugin.jarFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (!entry.getName().startsWith(prefix) || entry.isDirectory()) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    bundled.put(entry.getName().substring(prefix.length()), in.readAllBytes());
                }
            }
        }
        DefaultContentSync.Report r = DefaultContentSync.sync(contentsDir, bundled, shippedHashes());
        if (r.installed() + r.updated() > 0) {
            log.info("기본 콘텐츠: 새 파일 " + r.installed() + "개, 업데이트 " + r.updated() + "개");
        }
        for (String rel : r.kept()) {
            log.warning("기본 콘텐츠 '" + rel + "' 는 직접 수정된 파일이라 업데이트하지 않았습니다. "
                    + "새 버전으로 바꾸려면 파일을 지우고 서버를 다시 시작하세요.");
        }
    }

    /** Hashes of every default file version ever shipped (generated by tools/content_history.py). */
    private Map<String, Set<String>> shippedHashes() throws IOException {
        Map<String, Set<String>> out = new HashMap<>();
        try (InputStream in = plugin.getResource("default-content-history.txt")) {
            if (in == null) return out;
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                int sep = line.indexOf(' ');
                if (sep > 0) out.computeIfAbsent(line.substring(sep + 1).trim(), k -> new HashSet<>()).add(line.substring(0, sep));
            }
        }
        return out;
    }
}
