package dev.stsv.core.pack;

import dev.stsv.core.content.ContentPack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 치장 거리별 단순화: every heavy model a wearer shows ships a light "&lt;name&gt;_lod" model (tools/assetgen/cosmetics_lod.py),
 * as the pack sends it: at most 120 faces and at most a quarter of the full model (a single box for the smallest parts).
 * Heavy: everything a donation piece shows while worn (its model, awakened look, parts, the aura's worn model, the set's
 * awakened model) and any other worn cosmetic model over 150 faces.
 */
class CosmeticLodPackTest {

    static final int CAP = 120, HEAVY = 150, SINGLE_BOX = 12;

    @Test
    void everyHeavyWornModelHasALightOneWithinBudget() throws Exception {
        Path pack = Path.of(System.getProperty("stsv.content")).resolve("stsv");
        PackBuilder b = new PackBuilder();
        b.build(List.of(new ContentPack("stsv", "stsv", pack)), List.of(), 97, "t");
        Map<String, int[]> counts = b.faceCounts();

        Set<String> donation = new TreeSet<>(), shown = new TreeSet<>();
        try (var s = Files.list(pack.resolve("items"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                for (String id : y.getKeys(false)) {
                    ConfigurationSection c = y.getConfigurationSection(id + ".cosmetic");
                    if (c == null) continue;
                    String slot = c.getString("slot");
                    if ("trail".equals(slot) || "kill".equals(slot)) continue;   // menu looks; prints and bursts live a moment
                    List<String> names = new ArrayList<>();
                    names.add("aura".equals(slot) ? c.getString("worn") : y.getString(id + ".model"));
                    names.add(c.getString("awakened"));
                    for (Map<?, ?> part : c.getMapList("parts")) {
                        names.add((String) part.get("model"));
                        names.add((String) part.get("awakened"));
                    }
                    for (String n : names) {
                        if (n == null) continue;
                        String m = "stsv:" + n.replaceFirst("^.*:", "").replace("model/", "");
                        shown.add(m);
                        if (f.getFileName().toString().equals("cosmetics_donation.yml")) donation.add(m);
                    }
                }
            }
        }
        YamlConfiguration sets = YamlConfiguration.loadConfiguration(pack.resolve("cosmetic_sets.yml").toFile());
        for (String id : sets.getConfigurationSection("sets").getKeys(false)) {
            String m = sets.getString("sets." + id + ".awaken.model");
            if (m != null) donation.add("stsv:" + m.replaceFirst("^.*:", "").replace("model/", ""));
        }
        assertTrue(donation.contains("stsv:azure_dragon_wings_awakened") && donation.contains("stsv:azure_tail_seg_7")
                && donation.contains("stsv:galaxy_planet_ring") && donation.contains("stsv:azure_set_ring"), donation.toString());

        List<String> problems = new ArrayList<>();
        int checked = 0, full = 0, light = 0;
        for (String m : shown) {
            int[] c = counts.get(m);
            if (c == null || !(donation.contains(m) || c[1] > HEAVY)) continue;
            int[] l = counts.get(m + "_lod");
            if (l == null) {
                problems.add(m + ": " + c[1] + "면인데 단순 모델 " + m + "_lod 가 없음");
                continue;
            }
            checked++;
            full += c[1];
            light += l[1];
            if (l[1] > CAP || (l[1] > c[1] / 4 && l[1] > SINGLE_BOX)) problems.add(m + "_lod: " + l[1] + "면 (원래 " + c[1] + "면)");
        }
        for (String m : donation) if (!counts.containsKey(m)) problems.add(m + ": 모델이 팩에 없음");
        assertTrue(checked > 40, "checked " + checked);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        System.out.println("light models: " + checked + ", faces " + full + " -> " + light);

        // a whole 청룡 set (awakened: the heaviest), as one far-away viewer draws it: under a quarter
        List<String> azure = new ArrayList<>(List.of("azure_pearl_cloud_awakened", "azure_pearl_orb_awakened", "azure_dragon_wings_awakened",
                "azure_dragon_tail", "azure_shoulder_dragon", "azure_cloud_sash", "azure_baby_dragon", "azure_aura_dragon_awakened", "azure_set_ring"));
        for (int i = 1; i <= 12; i++) azure.add("azure_tail_seg_" + i);
        int near = 0, far = 0;
        for (String m : azure) {
            near += counts.get("stsv:" + m)[1];
            far += counts.get("stsv:" + m + "_lod")[1];
        }
        System.out.println("청룡 (각성) 한 명: 가까이 " + near + "면, 멀리 " + far + "면");
        assertTrue(far * 4 <= near, near + " -> " + far);
    }
}
