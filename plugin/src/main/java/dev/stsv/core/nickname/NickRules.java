package dev.stsv.core.nickname;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;

/**
 * The rules of a nickname, without Bukkit (unit-tested): which letters, how long, the banned words, how two
 * nicknames are compared (the "key"), and what a change costs and when it is allowed again.
 *
 * <ul>
 *   <li>Letters: Hangul syllables (가..힣), English letters and digits. No spaces, signs, colour codes or
 *       MiniMessage tags: a nickname is plain text, its colour is stored apart ({@link NickColor}).</li>
 *   <li>Length: counted in letters (a Hangul syllable is one).</li>
 *   <li>Key: NFC, lower case. Two nicknames with the same key are the same nickname (unique on the server).</li>
 * </ul>
 */
public final class NickRules {

    private NickRules() {}

    public enum Problem {
        /** fewer or more letters than allowed */
        LENGTH,
        /** a space, sign, colour code, tag or a letter that is not Hangul, English or a digit */
        CHARACTERS,
        /** contains a banned word */
        BANNED
    }

    /** NFC (Hangul typed as separate jamo becomes syllables), surrounding spaces trimmed. */
    public static String clean(String typed) {
        return typed == null ? "" : Normalizer.normalize(typed.strip(), Normalizer.Form.NFC);
    }

    /** What two nicknames are compared by: NFC, lower case (Hangul has no case). */
    public static String key(String nickname) {
        return clean(nickname).toLowerCase(Locale.ROOT);
    }

    /** Letters, a Hangul syllable counting as one. */
    public static int length(String nickname) {
        return nickname.codePointCount(0, nickname.length());
    }

    public static boolean hangul(int cp) {
        return cp >= 0xAC00 && cp <= 0xD7A3;
    }

    /** Hangul syllables, A-Z, a-z, 0-9. */
    public static boolean allowed(int cp) {
        return hangul(cp) || (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9');
    }

    /**
     * What is wrong with a nickname (already {@link #clean cleaned}), or null when it is fine.
     *
     * @param banned words no nickname may contain (compared by key, so case does not matter)
     */
    public static Problem check(String nickname, int min, int max, Collection<String> banned) {
        if (nickname == null || nickname.isEmpty()) return Problem.LENGTH;
        if (!nickname.codePoints().allMatch(NickRules::allowed)) return Problem.CHARACTERS;
        int n = length(nickname);
        if (n < min || n > max) return Problem.LENGTH;
        if (bannedWord(nickname, banned) != null) return Problem.BANNED;
        return null;
    }

    /** The banned word a nickname contains, or null. Blank entries are ignored. */
    public static String bannedWord(String nickname, Collection<String> banned) {
        if (banned == null) return null;
        String k = key(nickname);
        for (String b : banned) {
            if (b == null) continue;
            String bk = key(b);
            if (!bk.isEmpty() && k.contains(bk)) return b;
        }
        return null;
    }

    /**
     * What a player pays to set this nickname.
     *
     * @param firstTime    they never set a nickname themselves before (the first is free)
     * @param changeCost   the price of every later change
     * @param moneyCharged false when this server does not charge for nicknames (no economy plugin)
     */
    public static long cost(boolean firstTime, long changeCost, boolean moneyCharged) {
        if (!moneyCharged || firstTime) return 0;
        return Math.max(0, changeCost);
    }

    /**
     * What a colour costs: none for "no colour", a gradient always the gradient price, a single colour the colour
     * price unless it is the player's first colour and the first one is free.
     */
    public static long colorCost(NickColor color, boolean firstColor, boolean firstFree, long colorCost, long gradientCost, boolean moneyCharged) {
        if (!moneyCharged || color == null) return 0;
        if (color.gradient()) return Math.max(0, gradientCost);
        if (firstColor && firstFree) return 0;
        return Math.max(0, colorCost);
    }

    /** Milliseconds until the nickname may be changed again (0: now). cooldown 0 = no waiting. */
    public static long cooldownLeft(long changedAt, long cooldownMillis, long now) {
        if (cooldownMillis <= 0 || changedAt <= 0) return 0;
        return Math.max(0, changedAt + cooldownMillis - now);
    }
}
