package dev.stsv.core.pack;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 렌더 예산: how many faces a shipped 3D model may have, as the pack sends it (after {@link FaceReducer}). Every face is a
 * quad the client draws again every frame for every placed copy, so these keep a big base light. A test
 * (RenderBudgetTest) fails when a model goes over, naming the model, its faces and its budget.
 *
 * <p>To change a budget, change the number here (docs/ART_STYLE.md, 렌더 예산, explains how they were chosen). A model that
 * really needs more gets a line in {@link #EXCEPTIONS} with the reason, instead of a higher budget for its whole kind.
 */
public final class RenderBudget {

    /** Kinds of 3D model and their face budgets. */
    public enum Category {
        FURNITURE_SMALL("1칸 가구·소품", 300),
        FURNITURE_LARGE("여러 칸 가구", 440),
        MACHINE_0("직업 기계 기본", 400),
        MACHINE_1("직업 기계 1강", 460),
        MACHINE_2("직업 기계 2강", 640),
        MACHINE_3("직업 기계 3강", 720),
        MACHINE_PART("기계 회전 부품", 160),
        COSMETIC("치장 (머리·등·꼬리…)", 300),
        PET("펫", 200),
        VEHICLE("탈것 몸체", 600),
        VEHICLE_WHEEL("탈것 바퀴", 80),
        VEHICLE_PART("탈것 부품 (경광등·짐·페달)", 240),
        VEHICLE_TRAILER("탈것 트레일러 몸체 (하나)", 400),
        VEHICLE_ANIMAL("탈것 끄는 동물의 다리·머리 (하나)", 160),
        CARRIER("등에 지는 것 (지게, 짐 단계 하나)", 300),
        OTHER("그 밖의 3D 모델", 300);

        public final String label;
        public final int faces;

        Category(String label, int faces) {
            this.label = label;
            this.faces = faces;
        }
    }

    /** Models allowed more than their kind's budget: model ("ns:name") -> {faces, why}. Empty is the goal. */
    public static final Map<String, Object[]> EXCEPTIONS = Map.of();

    private RenderBudget() {}

    /** The budget of a model: its exception, or its kind's. */
    public static int budget(String model, Category kind) {
        Object[] e = EXCEPTIONS.get(model);
        return e != null ? (Integer) e[0] : kind.faces;
    }

    /**
     * The kind of each model the items of a content pack show ("ns:name" -> kind): furniture by its cells (machines, with a
     * behavior or parts, by the tier in their model name "x_0".."x_3", their parts as parts), cosmetics as pets or worn,
     * vehicles' bodies, their wheel and their other parts (light bar looks, load stages, spinning parts).
     *
     * @param items every item of the pack (id -> its section)
     */
    public static Map<String, Category> categories(String namespace, Map<String, ConfigurationSection> items) {
        Map<String, Category> out = new LinkedHashMap<>();
        for (ConfigurationSection item : items.values()) {
            String model = item.getString("model");
            if (model == null) continue;
            ConfigurationSection f = item.getConfigurationSection("furniture");
            ConfigurationSection c = item.getConfigurationSection("cosmetic");
            ConfigurationSection v = item.getConfigurationSection("vehicle");
            ConfigurationSection j = item.getConfigurationSection("jige");
            if (j != null) {
                // a worn carrier (지게): its look on the back at each stage (the empty frame is also its item model)
                for (String s : j.getStringList("stages")) out.put(local(namespace, s), Category.CARRIER);
                out.putIfAbsent(namespace + ":" + model, Category.CARRIER);
            } else if (v != null) {
                out.put(namespace + ":" + model, Category.VEHICLE);
                if (v.getString("wheel") != null) out.put(local(namespace, v.getString("wheel")), Category.VEHICLE_WHEEL);
                List<String> shown = new java.util.ArrayList<>(v.getStringList("lights.flash"));
                if (v.getString("lights.dark") != null) shown.add(v.getString("lights.dark"));
                shown.addAll(v.getStringList("cargo.stages"));
                for (Map<?, ?> sp : v.getMapList("spinners")) if (sp.get("model") != null) shown.add(sp.get("model").toString());
                for (String p : shown) out.put(local(namespace, p), Category.VEHICLE_PART);
                trainCategories(namespace, v.getConfigurationSection("train"), out);
            } else if (f != null && (f.contains("behavior") || f.contains("parts")) && model.matches(".*_\\d+")) {
                // a machine (a behavior or parts, and a tier look in its model name): its tier looks <base>_0 .. <base>_3, and the
                // same for each spinning part; furniture with a behavior but one look (the economy's 통발) is furniture below
                String base = model.replaceFirst("_\\d+$", "");
                Category[] tiers = {Category.MACHINE_0, Category.MACHINE_1, Category.MACHINE_2, Category.MACHINE_3};
                for (int t = 0; t < tiers.length; t++) out.put(namespace + ":" + base + "_" + t, tiers[t]);
                for (Map<?, ?> part : f.getMapList("parts")) {
                    Object pm = part.get("model");
                    if (pm == null) continue;
                    String pb = pm.toString().replaceFirst("^[a-z0-9_.-]+:", "").replaceFirst("^model/", "").replaceFirst("_\\d+$", "");
                    for (int t = 0; t < tiers.length; t++) out.put(namespace + ":" + pb + "_" + t, Category.MACHINE_PART);
                }
            } else if (f != null) {
                List<?> cells = f.getList("cells", List.of());
                out.putIfAbsent(namespace + ":" + model, cells.size() <= 1 ? Category.FURNITURE_SMALL : Category.FURNITURE_LARGE);
            } else if (c != null) {
                out.putIfAbsent(namespace + ":" + model, "pet".equals(c.getString("slot")) ? Category.PET : Category.COSMETIC);
            }
        }
        return out;
    }

    /**
     * An articulated vehicle's towed units and moving parts (vehicle.train): each trailer's body, its wheel and load
     * stages; the draft animal's legs and head; spinning parts (a flywheel, a tractor's big rear wheels) as wheels.
     */
    private static void trainCategories(String namespace, ConfigurationSection train, Map<String, Category> out) {
        if (train == null) return;
        for (Map<?, ?> t : train.getMapList("trailers")) {
            if (t.get("model") != null) out.put(local(namespace, t.get("model").toString()), Category.VEHICLE_TRAILER);
            if (t.get("wheel") != null) out.put(local(namespace, t.get("wheel").toString()), Category.VEHICLE_WHEEL);
            if (t.get("cargo") instanceof Map<?, ?> c && c.get("stages") instanceof List<?> stages) {
                for (Object st : stages) out.put(local(namespace, st.toString()), Category.VEHICLE_PART);
            }
        }
        ConfigurationSection a = train.getConfigurationSection("animal");
        if (a != null) {
            for (Map<?, ?> l : a.getMapList("legs")) if (l.get("model") != null) out.put(local(namespace, l.get("model").toString()), Category.VEHICLE_ANIMAL);
            if (a.getString("head.model") != null) out.put(local(namespace, a.getString("head.model")), Category.VEHICLE_ANIMAL);
        }
        for (Map<?, ?> p : train.getMapList("parts")) if (p.get("model") != null) out.put(local(namespace, p.get("model").toString()), Category.VEHICLE_WHEEL);
    }

    /** "name" or "ns:name" or "ns:model/name" -> "ns:name". */
    private static String local(String namespace, String model) {
        String m = model.contains(":") ? model : namespace + ":" + model;
        return m.replaceFirst(":model/", ":");
    }

    /** A model's kind, or OTHER for a model no item shows as furniture, machine or cosmetic. */
    public static Category of(String model, Map<String, Category> categories) {
        return categories.getOrDefault(model, Category.OTHER);
    }

    /** One line per model over budget (empty when all fit). */
    public static List<String> over(Map<String, Integer> faces, Map<String, Category> categories, Collection<String> skip) {
        List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> m : faces.entrySet()) {
            if (skip.contains(m.getKey())) continue;
            Category k = of(m.getKey(), categories);
            int b = budget(m.getKey(), k);
            if (m.getValue() > b) {
                out.add(m.getKey() + ": 면 " + m.getValue() + "개 > 예산 " + b + " (" + k.label + "). 모델을 가볍게 하거나 "
                        + "RenderBudget 의 예산·예외를 고치세요 (docs/ART_STYLE.md 렌더 예산)");
            }
        }
        return out;
    }
}
