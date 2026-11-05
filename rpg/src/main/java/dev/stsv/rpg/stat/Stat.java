package dev.stsv.rpg.stat;

import dev.stsv.rpg.balance.Element;

import java.util.Locale;

/**
 * The fixed stat list (plan: 14 stats, element resist counted once). Percent stats are stored as
 * fractions (0.05 = 5%); crit damage is the total multiplier (1.5 = 150%).
 */
public enum Stat {
    ATTACK("공격력", Kind.FLAT, 0),
    CRIT_CHANCE("치명타 확률", Kind.PERCENT, 0.60),
    CRIT_DAMAGE("치명타 피해", Kind.MULTIPLIER, 2.50),
    ATTACK_SPEED("공격 속도", Kind.PERCENT, 0.50),
    SKILL_DAMAGE("스킬 피해", Kind.PERCENT, 0),
    ELEMENT_DAMAGE("속성 피해", Kind.PERCENT, 0),
    MAX_HEALTH("최대 체력", Kind.FLAT, 0),
    DEFENSE("방어력", Kind.FLAT, 0),
    RESIST_FIRE("불 저항", Kind.PERCENT, 0.50),
    RESIST_WATER("물 저항", Kind.PERCENT, 0.50),
    RESIST_GRASS("풀 저항", Kind.PERCENT, 0.50),
    RESIST_LIGHT("빛 저항", Kind.PERCENT, 0.50),
    RESIST_DARK("어둠 저항", Kind.PERCENT, 0.50),
    HEALTH_REGEN("체력 재생", Kind.PER_SECOND, 0),
    MAX_STAMINA("최대 기력", Kind.FLAT, 0),
    STAMINA_REGEN("기력 재생", Kind.PER_SECOND, 0),
    COOLDOWN("재사용 대기시간 감소", Kind.PERCENT, 0.40),
    MOVE_SPEED("이동 속도", Kind.PERCENT, 0.30);

    public enum Kind { FLAT, PERCENT, MULTIPLIER, PER_SECOND }

    public final String label;
    public final Kind kind;
    /** 0 = no cap */
    public final double cap;

    Stat(String label, Kind kind, double cap) {
        this.label = label;
        this.kind = kind;
        this.cap = cap;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Stat byId(String id) {
        for (Stat s : values()) if (s.id().equals(id) || s.label.equals(id)) return s;
        return null;
    }

    /** The resist stat against an element, or null for no element. */
    public static Stat resist(Element e) {
        return switch (e) {
            case FIRE -> RESIST_FIRE;
            case WATER -> RESIST_WATER;
            case GRASS -> RESIST_GRASS;
            case LIGHT -> RESIST_LIGHT;
            case DARK -> RESIST_DARK;
            case NONE -> null;
        };
    }

    /** The value as players read it: "12", "5%", "150%", "3/초". */
    public String format(double v) {
        return switch (kind) {
            case FLAT -> String.format(Locale.ROOT, "%,d", Math.round(v));
            case PERCENT, MULTIPLIER -> trim(v * 100) + "%";
            case PER_SECOND -> trim(v) + "/초";
        };
    }

    private static String trim(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
