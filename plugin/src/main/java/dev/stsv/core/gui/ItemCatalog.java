package dev.stsv.core.gui;

import dev.stsv.core.content.CustomItem;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * How the admin item browser (/관리자 아이템목록) groups the custom items: by what they are (machines and furniture,
 * vehicles, cosmetics and their vouchers, gear and tools, food) and by the content file they come from (crops, fish, job goods),
 * and a search over the id and the shown name. Pure (unit-tested).
 */
public final class ItemCatalog {

    private ItemCatalog() {}

    /** Browser tabs, in order (with "전체" before them: the top row, then the free slots of the bottom row). */
    public enum Category {
        MACHINE("기계·가구", Material.FURNACE),
        VEHICLE("탈것", Material.MINECART),
        COSMETIC("치장·교환권", Material.LEATHER_HELMET),
        GEAR("장비·도구·스킨", Material.IRON_PICKAXE),
        CROP("작물·씨앗", Material.WHEAT),
        FISH("물고기", Material.COD),
        FOOD("음식", Material.BREAD),
        GOODS("직업 생산품·재료", Material.EMERALD),
        OTHER("기타", Material.PAPER);

        public final String label;
        public final Material icon;

        Category(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    /** What the grouping looks at; source = the items file it came from without ".yml" ("crops", "fish", "jobs", ...). */
    public record Facts(String id, String name, String source, Material material, boolean furniture, boolean cosmetic,
                        boolean voucher, boolean gear, boolean food, boolean vehicle) {
        public Facts(String id, String name, String source, Material material, boolean furniture, boolean cosmetic,
                     boolean voucher, boolean gear, boolean food) {
            this(id, name, source, material, furniture, cosmetic, voucher, gear, food, false);
        }
    }

    public static Facts facts(CustomItem i, String source) {
        return new Facts(i.key().asString(), i.name() == null ? "" : i.name(), source == null ? "" : source, i.material(),
                i.isFurniture(), i.cosmetic() != null, i.voucher() != null, i.skin() != null || i.equipment() != null, i.food() != null,
                i.isVehicle());
    }

    private static final String[] TOOLS = {"_SWORD", "_AXE", "_PICKAXE", "_SHOVEL", "_HOE", "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS",
            "BOW", "TRIDENT", "FISHING_ROD", "SHIELD", "MACE", "SHEARS", "SPEAR", "BRUSH"};

    static boolean tool(Material m) {
        if (m == null) return false;
        String n = m.name();
        for (String t : TOOLS) if (n.endsWith(t)) return true;
        return false;
    }

    public static Category of(Facts f) {
        if (f.vehicle()) return Category.VEHICLE;
        if (f.furniture()) return Category.MACHINE;
        if (f.cosmetic() || f.voucher()) return Category.COSMETIC;
        if (f.gear() || tool(f.material())) return Category.GEAR;
        String file = f.source().toLowerCase(Locale.ROOT);
        String last = file.substring(file.lastIndexOf('/') + 1);
        if (last.startsWith("crop") || last.startsWith("seed")) return Category.CROP;
        if (last.startsWith("fish")) return Category.FISH;
        if (f.food()) return Category.FOOD;
        if (last.startsWith("job") || last.startsWith("good") || last.startsWith("material")) return Category.GOODS;
        return Category.OTHER;
    }

    /** The name without MiniMessage tags, for searching. */
    static String plain(String miniMessage) {
        return miniMessage == null ? "" : miniMessage.replaceAll("<[^>]*>", "");
    }

    /** Search: every word of the query is in the id or the shown name (ignoring case and spaces in the name). */
    public static boolean matches(Facts f, String query) {
        if (query == null || query.isBlank()) return true;
        String id = f.id().toLowerCase(Locale.ROOT);
        String name = plain(f.name()).toLowerCase(Locale.ROOT);
        String compact = name.replace(" ", "");
        for (String w : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!id.contains(w) && !name.contains(w) && !compact.contains(w)) return false;
        }
        return true;
    }

    /** The items of a category (null = all) that match the query, in content order. */
    public static <T> List<T> pick(Collection<T> items, Function<T, Facts> facts, Category category, String query) {
        List<T> out = new ArrayList<>();
        for (T t : items) {
            Facts f = facts.apply(t);
            if ((category == null || of(f) == category) && matches(f, query)) out.add(t);
        }
        return out;
    }
}
