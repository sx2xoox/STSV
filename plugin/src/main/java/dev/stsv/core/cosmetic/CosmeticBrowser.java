package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.CustomItem.CosmeticSlot;
import dev.stsv.core.content.CustomItem.Rarity;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What the screens that list cosmetics share (옷장 /치장 and 치장 뽑기 /뽑기), so both read the same:
 * <ul>
 *   <li>tabs: "all" (전체) then one per slot, in slot order (menus: wardrobe.yml, gacha.yml); each tab's
 *       name shows "owned/total" ({@link #counts});</li>
 *   <li>order: by slot, then rarity (일반 → 전설), then name: owned and not-owned stay in place, like a dex;</li>
 *   <li>a cosmetic the player does not have is a dark card with a '?' in its rarity's colour
 *       ({@link #locked}: one shared item model, stsv:gui/locked, tinted through custom_model_data colours),
 *       its name greyed, and where it comes from ({@link #source}).</li>
 * </ul>
 */
public final class CosmeticBrowser {

    /** The shared not-owned card (tools/assetgen/gui.py: a dark card, its rim and '?' tinted by colors[0]). */
    static final Key LOCKED_MODEL = Key.key("stsv", "gui/locked");
    static final String ALL = "all";
    /** 전체, then every slot (the tab actions are "&lt;menu&gt;:tab:&lt;id&gt;"). */
    public static final List<String> TABS = tabs();

    private static final Collator KOREAN = Collator.getInstance(Locale.KOREAN);

    private CosmeticBrowser() {
    }

    private static List<String> tabs() {
        List<String> out = new ArrayList<>();
        out.add(ALL);
        for (CosmeticSlot s : CosmeticSlot.values()) out.add(s.id);
        return List.copyOf(out);
    }

    /** Is a cosmetic of this slot on the tab ("all" or a slot id)? Unknown tabs show nothing. */
    public static boolean inTab(String tab, CosmeticSlot slot) {
        return tab.equals(ALL) || slot.id.equals(tab);
    }

    /** The order of every cosmetic list: slot, rarity (common first), then the name as read (가나다). */
    public static <T> Comparator<T> order(Function<T, CosmeticSlot> slot, Function<T, Rarity> rarity, Function<T, String> name) {
        return Comparator.comparing(slot).thenComparing(rarity).thenComparing(t -> Text.plain(name.apply(t)), KOREAN);
    }

    public static Comparator<CustomItem> order() {
        return order(c -> c.cosmetic().slot(), c -> c.cosmetic().rarity(), CosmeticBrowser::name);
    }

    /** Tab id -> "owned/total" for every tab (the tab buttons' {n_&lt;tab&gt;}). */
    public static <T> Map<String, String> counts(Collection<T> all, Function<T, CosmeticSlot> slot, Predicate<T> owned) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String tab : TABS) {
            long total = all.stream().filter(t -> inTab(tab, slot.apply(t))).count();
            long have = all.stream().filter(t -> inTab(tab, slot.apply(t)) && owned.test(t)).count();
            out.put(tab, have + "/" + total);
        }
        return out;
    }

    /** Where a cosmetic comes from, for the not-owned hint. */
    public enum Source {
        /** in the 치장 뽑기 pool */
        GACHA,
        /** an achievement's reward */
        ACHIEVEMENT,
        /** legendary: weekly ranking, 상인 행렬, events */
        LIMITED,
        OTHER;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static Source source(Rarity rarity, String obtain, boolean inGachaPool) {
        String o = obtain == null ? "" : obtain;
        if (inGachaPool) return Source.GACHA;
        if (o.contains("업적")) return Source.ACHIEVEMENT;
        if (rarity == Rarity.LEGENDARY || o.contains("순위") || o.contains("상인") || o.contains("한정") || o.contains("이벤트")) return Source.LIMITED;
        return o.contains("뽑기") ? Source.GACHA : Source.OTHER;
    }

    /** The rarity's colour as RGB (the '?' card's tint; legendary: the gold end of its gradient). */
    public static int rgb(Rarity r) {
        return switch (r) {
            case COMMON -> 0xAAAAAA;
            case RARE -> 0x5AB0FF;
            case EPIC -> 0xC58CFF;
            case LEGENDARY -> 0xFFD36B;
        };
    }

    static String name(CustomItem c) {
        return c.name() == null ? c.key().getKey() : c.name();
    }

    /** The rarity and slot line every cosmetic's lore starts with. */
    static Component rarityLine(CustomItem c) {
        Rarity r = c.cosmetic().rarity();
        return Lang.item("wardrobe.lore-rarity", "color", r.color, "rarity", r.label, "slot", Lang.raw("cosmetic.slot." + c.cosmetic().slot().id));
    }

    /** The lines that say where a not-owned cosmetic comes from (the source, then the content's own words). */
    static List<Component> obtainLines(CustomItem c, Source source) {
        List<Component> lore = new ArrayList<>();
        lore.add(Lang.item("wardrobe.lore-source", "source", Lang.raw("wardrobe.source." + source.key())));
        String obtain = c.cosmetic().obtain();
        if (obtain != null && !obtain.isBlank()) lore.add(Lang.item("wardrobe.lore-obtain", "obtain", obtain));
        return lore;
    }

    /** A cosmetic the player does not have: the dark '?' card in its rarity's colour, the name greyed out. */
    @SuppressWarnings("UnstableApiUsage")
    static ItemStack locked(CustomItem c, List<Component> lore) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, LOCKED_MODEL);
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData()
                .addColor(Color.fromRGB(rgb(c.cosmetic().rarity()))).build());
        it.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        it.editMeta(m -> {
            m.itemName(Lang.item("wardrobe.locked-name", "cosmetic", Text.plain(name(c))));
            m.lore(lore);
        });
        return it;
    }

    /** The 옷장's 보기 filter. */
    public enum View {
        ALL, OWNED, MISSING;

        public View next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public boolean shows(boolean owned) {
            return this == ALL || (this == OWNED) == owned;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
