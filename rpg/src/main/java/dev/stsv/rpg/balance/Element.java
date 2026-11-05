package dev.stsv.rpg.balance;

import java.util.Locale;

/** The five elements plus "none". Fire beats grass beats water beats fire; light and dark beat each other. */
public enum Element {
    NONE("무속성", "#d9dee2"),
    FIRE("불", "#ff8a3d"),
    WATER("물", "#4aa8ff"),
    GRASS("풀", "#5fd068"),
    LIGHT("빛", "#ffd95a"),
    DARK("어둠", "#b07cff");

    public final String label;
    public final String color;

    Element(String label, String color) {
        this.label = label;
        this.color = color;
    }

    /** true if an attack of this element is strong against {@code defender}. */
    public boolean beats(Element defender) {
        return switch (this) {
            case FIRE -> defender == GRASS;
            case GRASS -> defender == WATER;
            case WATER -> defender == FIRE;
            case LIGHT -> defender == DARK;
            case DARK -> defender == LIGHT;
            case NONE -> false;
        };
    }

    /** Damage multiplier of this attack element against a defender element. */
    public double against(Element defender, double advantage, double disadvantage) {
        if (this == NONE || defender == NONE) return 1;
        if (beats(defender)) return advantage;
        // light and dark have no weak side: only the triangle loses to its counter
        if (this != LIGHT && this != DARK && defender.beats(this)) return disadvantage;
        return 1;
    }

    public static Element parse(String s) {
        if (s == null || s.isBlank()) return NONE;
        for (Element e : values()) if (e.label.equals(s) || e.name().equalsIgnoreCase(s)) return e;
        throw new IllegalArgumentException("속성은 불/물/풀/빛/어둠/무속성 중 하나: " + s);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
