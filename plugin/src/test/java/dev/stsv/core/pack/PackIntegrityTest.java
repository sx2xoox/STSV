package dev.stsv.core.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.content.ContentPack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the real pack and checks that every reference resolves, so nothing shows up in game as the
 * purple/black "missing" texture: item definitions -> models -> parents -> textures, equipment assets
 * -> layer textures, and items.yml item-model / equipment references.
 * References into the minecraft namespace must exist in vanilla 26.3 (model list from the client).
 */
class PackIntegrityTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));

    private Map<String, byte[]> files;
    private final List<String> problems = new ArrayList<>();

    @Test
    void everyReferenceResolves() throws Exception {
        ContentPack pack = new ContentPack("stsv", "stsv", CONTENT.resolve("stsv"));
        files = unzip(new PackBuilder().build(List.of(pack), List.of(), 97, "test").zip());
        Set<String> vanillaModels = new HashSet<>();
        try (InputStream in = getClass().getResourceAsStream("/vanilla-models-26.3.txt")) {
            vanillaModels.addAll(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")));
        }

        int items = 0, models = 0;
        for (String path : files.keySet()) {
            if (path.matches("assets/[^/]+/items/.+\\.json")) {
                items++;
                walkItemModel(path, json(path).get("model"), vanillaModels);
            } else if (path.matches("assets/[^/]+/models/.+\\.json")) {
                models++;
                checkModel(path, json(path), vanillaModels);
            } else if (path.matches("assets/[^/]+/equipment/.+\\.json")) {
                checkEquipment(path, json(path));
            }
        }
        checkItemsYaml();
        assertTrue(items > 0 && models > 0, "nothing checked");
        assertTrue(problems.isEmpty(), "깨진 참조:\n" + String.join("\n", problems));
    }

    private void walkItemModel(String from, JsonElement node, Set<String> vanilla) {
        if (node == null) return;
        if (node.isJsonArray()) node.getAsJsonArray().forEach(n -> walkItemModel(from, n, vanilla));
        if (!node.isJsonObject()) return;
        JsonObject o = node.getAsJsonObject();
        for (String key : List.of("model", "base")) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) requireModel(from, o.get(key).getAsString(), vanilla);
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getValue().isJsonPrimitive()) walkItemModel(from, e.getValue(), vanilla);
        }
    }

    private void checkModel(String path, JsonObject model, Set<String> vanilla) {
        if (model.has("parent")) requireModel(path, model.get("parent").getAsString(), vanilla);
        if (model.has("textures")) {
            for (Map.Entry<String, JsonElement> t : model.getAsJsonObject("textures").entrySet()) {
                String ref = t.getValue().getAsString();
                if (ref.startsWith("#")) continue;
                String[] id = split(ref);
                if (id[0].equals("minecraft")) continue;
                require(path, "assets/" + id[0] + "/textures/" + id[1] + ".png");
            }
        }
    }

    private void checkEquipment(String path, JsonObject eq) {
        for (Map.Entry<String, JsonElement> layer : eq.getAsJsonObject("layers").entrySet()) {
            for (JsonElement l : layer.getValue().getAsJsonArray()) {
                String[] id = split(l.getAsJsonObject().get("texture").getAsString());
                require(path, "assets/" + id[0] + "/textures/entity/equipment/" + layer.getKey() + "/" + id[1] + ".png");
            }
        }
    }

    private void checkItemsYaml() throws Exception {
        List<Path> ymls;
        try (Stream<Path> s = Files.walk(CONTENT.resolve("stsv/items"))) {
            ymls = s.filter(p -> p.toString().endsWith(".yml")).toList();
        }
        for (Path file : ymls) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            for (String id : yaml.getKeys(false)) {
                ConfigurationSection sec = yaml.getConfigurationSection(id);
                String where = file.getFileName() + " → " + id;
                if (sec.isString("item-model")) {
                    String[] ref = split(sec.getString("item-model"));
                    if (!ref[0].equals("minecraft")) require(where, "assets/" + ref[0] + "/items/" + ref[1] + ".json");
                }
                if (sec.isString("skin.model")) requireItemModel(where, sec.getString("skin.model"));
                for (Object part : sec.getList("furniture.parts", List.of())) {
                    if (part instanceof Map<?, ?> m) requireItemModel(where, String.valueOf(m.get("model")));
                }
                if (sec.isConfigurationSection("equipment")) {
                    String[] ref = split(sec.getString("equipment.asset"));
                    require(where, "assets/" + ref[0] + "/equipment/" + ref[1] + ".json");
                }
            }
        }
    }

    /** A bare name means a .bbmodel in the pack, reachable as item model "stsv:model/<name>". */
    private void requireItemModel(String from, String ref) {
        String[] id = ref.contains(":") ? split(ref) : new String[]{"stsv", "model/" + ref};
        require(from, "assets/" + id[0] + "/items/" + id[1] + ".json");
    }

    private void requireModel(String from, String ref, Set<String> vanilla) {
        String[] id = split(ref);
        if (id[0].equals("minecraft")) {
            if (!vanilla.contains(id[1])) problems.add(from + ": 바닐라에 없는 모델 " + ref);
            return;
        }
        require(from, "assets/" + id[0] + "/models/" + id[1] + ".json");
    }

    private void require(String from, String file) {
        if (!files.containsKey(file)) problems.add(from + " → 없음: " + file);
    }

    private static String[] split(String ref) {
        int c = ref.indexOf(':');
        return c < 0 ? new String[]{"minecraft", ref} : new String[]{ref.substring(0, c), ref.substring(c + 1)};
    }

    private JsonObject json(String path) {
        return JsonParser.parseString(new String(files.get(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }
}
