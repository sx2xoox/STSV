package dev.stsv.rpg.balance;

import java.util.Locale;

/** What kind of opponent a mob is; decides its health, damage and experience through the balance targets. */
public enum Role {
    /** animals and other harmless creatures */
    CRITTER("동물"),
    NORMAL("일반"),
    ELITE("엘리트"),
    /** a region lord */
    BOSS("군주"),
    /** the season's final boss */
    FINAL("최종 보스");

    public final String label;

    Role(String label) {
        this.label = label;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role parse(String s) {
        String key = s == null ? "" : s.replace(" ", "");
        for (Role r : values()) if (r.label.replace(" ", "").equals(key) || r.name().equalsIgnoreCase(key)) return r;
        throw new IllegalArgumentException("역할은 동물/일반/엘리트/군주/최종 보스 중 하나: " + s);
    }
}
