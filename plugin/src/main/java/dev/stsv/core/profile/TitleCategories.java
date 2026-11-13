package dev.stsv.core.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tabs of /칭호: every title, the title categories in order, and the title banners (admins only). A title's
 * category is its "category" key in contents/&lt;pack&gt;/titles/*.yml, or else follows its file (achievements.yml:
 * achievements, codex.yml: codex, anything else: events and the rest). No Bukkit types (unit-tested).
 */
public final class TitleCategories {

    public static final String ALL = "all";
    public static final String BANNER = "banner";
    public static final String ACHIEVEMENT = "achievement";
    public static final String JOB = "job";
    public static final String CODEX = "codex";
    public static final String RANKING = "ranking";
    public static final String SHOP = "shop";
    public static final String EVENT = "event";

    /** The categories, in tab order. */
    public static final List<String> ORDER = List.of(ACHIEVEMENT, JOB, CODEX, RANKING, SHOP, EVENT);

    private TitleCategories() {}

    /** A title's category: the explicit one when known, else by its file's name (without .yml). */
    public static String of(String explicit, String fileStem) {
        if (explicit != null) {
            String e = explicit.trim().toLowerCase(Locale.ROOT);
            if (ORDER.contains(e)) return e;
        }
        String f = fileStem == null ? "" : fileStem.toLowerCase(Locale.ROOT);
        if (f.startsWith("achievement")) return ACHIEVEMENT;
        if (f.startsWith("codex")) return CODEX;
        if (f.startsWith("job")) return JOB;
        if (f.startsWith("rank")) return RANKING;
        if (f.startsWith("shop")) return SHOP;
        return EVENT;
    }

    /** True when an explicit category is given but is not one of {@link #ORDER} (the loader warns). */
    public static boolean unknown(String explicit) {
        return explicit != null && !ORDER.contains(explicit.trim().toLowerCase(Locale.ROOT));
    }

    /** The tabs a viewer gets: all, every category, and the banner tab only for admins. */
    public static List<String> tabs(boolean admin) {
        List<String> out = new ArrayList<>();
        out.add(ALL);
        out.addAll(ORDER);
        if (admin) out.add(BANNER);
        return out;
    }

    /** A tab a viewer may open (an unknown or forbidden one falls back to {@link #ALL}). */
    public static String open(String tab, boolean admin) {
        return tab != null && tabs(admin).contains(tab) ? tab : ALL;
    }
}
