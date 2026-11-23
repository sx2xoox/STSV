package dev.stsv.economy.secret.pager;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The pure parts of the follow-up 「호출」 ({@link PagerSequel}; unit-tested): the numbers the grown daughter sends to the
 * live pager (pager.yml {@code sequel.calls}), which one a week takes, who "she" is when the pager answers her, and the
 * evening a "come early" call must be kept by.
 */
final class Calls {

    private Calls() {}

    /** What a number asks for, as the game can check it. */
    enum Kind {
        /** something of {@code items} into the basket */
        BASKET,
        /** it comes while they are far: at the basket before the time is up */
        RUSH,
        /** it comes by day: at the basket before that day's nightfall */
        DUSK,
        /** come by, whenever */
        VISIT,
        /** one of {@code answers} paged back to her with the live pager */
        REPLY;

        static Kind of(String s) {
            if (s == null) return null;
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** Whether the errand ends at the place where the house stood. */
        boolean there() {
            return this != REPLY;
        }
    }

    /** One call: its id (kv, admin), the digits on the screen, what it asks, and what answers it. */
    record Call(String id, String code, Kind kind, List<String> items, List<String> answers) {}

    private static final List<String> BREAD = List.of("bread", "economy:corn_bread");
    private static final List<String> WARM = List.of("economy:abalone_porridge", "economy:samgyetang", "economy:ginseng_samgyetang",
            "economy:miyeokguk", "economy:kalguksu", "economy:eomuktang", "economy:bogeo_jiri", "mushroom_stew", "rabbit_stew", "beetroot_soup");

    /** The code's own pool (pager.yml says the same; CallsTest holds the two together). */
    static final List<Call> DEFAULTS = List.of(
            new Call("bread", "045", Kind.BASKET, BREAD, List.of()),          // 빵 사 와
            new Call("fever", "10288", Kind.BASKET, WARM, List.of()),         // 열이 펄펄
            new Call("hurry", "8282", Kind.RUSH, List.of(), List.of()),       // 빨리빨리
            new Call("urgent", "981", Kind.RUSH, List.of(), List.of()),       // 급한 일
            new Call("depart", "8578", Kind.RUSH, List.of(), List.of()),      // 바로 출발
            new Call("early", "175", Kind.DUSK, List.of(), List.of()),        // 일찍 와
            new Call("back", "100", Kind.VISIT, List.of(), List.of()),        // 돌아와
            new Call("here", "1254", Kind.VISIT, List.of(), List.of()),       // 이리 오소
            new Call("friends", "7942", Kind.REPLY, List.of(), List.of("7942", "1004")),   // 친구 사이
            new Call("angel", "1004", Kind.REPLY, List.of(), List.of("1004")),             // 천사
            new Call("bye", "20000", Kind.REPLY, List.of(), List.of("20000", "1004")));    // 이만

    /**
     * The calls of {@code sequel.calls} (a section of id → code / kind / items / answers), in the file's order; a missing
     * section is the code's own pool. A call that could never be done (no digits, an unknown kind, a basket that takes
     * nothing, a reply with no answer) is left out and named in problems.
     */
    static List<Call> read(ConfigurationSection sec, List<String> problems) {
        if (sec == null) return DEFAULTS;
        List<Call> out = new ArrayList<>();
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            String code = Codes.digits(s.getString("code", ""));
            Kind kind = Kind.of(s.getString("kind"));
            List<String> items = clean(s.getStringList("items")), answers = new ArrayList<>();
            for (String a : clean(s.getStringList("answers"))) if (Codes.digits(a) != null) answers.add(a);
            String bad = code == null ? "code 는 숫자 1~12자리" : kind == null ? "kind 는 basket · rush · dusk · visit · reply 중 하나"
                    : kind == Kind.BASKET && items.isEmpty() ? "basket 인데 items 가 비어 있음" : kind == Kind.REPLY && answers.isEmpty() ? "reply 인데 answers (숫자) 가 비어 있음" : null;
            if (bad != null) {
                problems.add("sequel.calls." + id + ": " + bad + " (빼고 읽음)");
                continue;
            }
            out.add(new Call(id, code, kind, items, List.copyOf(answers)));
        }
        if (out.isEmpty()) {
            problems.add("sequel.calls 에 쓸 수 있는 호출이 하나도 없어서 코드의 기본 목록을 써요");
            return DEFAULTS;
        }
        return List.copyOf(out);
    }

    private static List<String> clean(List<String> l) {
        List<String> out = new ArrayList<>();
        for (String s : l) if (s != null && !s.isBlank()) out.add(s.trim());
        return List.copyOf(out);
    }

    static Call byId(List<Call> calls, String id) {
        for (Call c : calls) if (c.id().equals(id)) return c;
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // which one this week
    // ------------------------------------------------------------------------------------------

    /** A number that is the same for the same week and player (and nothing like its neighbours'). */
    static long seed(String week, long most, long least) {
        long z = (week == null ? 0 : week.hashCode()) * 0x9E3779B97F4A7C15L + most * 0xD1B54A32D192ED03L + least * 0x2545F4914F6CDD1DL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 0..n-1 in an order that is always the same for the same seed. */
    static int[] order(int n, long seed) {
        int[] a = new int[Math.max(0, n)];
        for (int i = 0; i < a.length; i++) a[i] = i;
        long z = seed;
        for (int i = a.length - 1; i > 0; i--) {
            z = z * 6364136223846793005L + 1442695040888963407L;
            int j = (int) Math.floorMod(z >>> 33, (long) (i + 1));
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        return a;
    }

    /**
     * This week's pick out of n: the first in the week's own order that is possible now and was not one of the recent
     * ones; if every possible one was recent, the first possible one; -1 when nothing is possible now (asked again later:
     * the order stays the same all week, so a restart goes on with the same choice).
     */
    static int pick(int n, long seed, Predicate<Integer> possible, Predicate<Integer> recent) {
        int[] o = order(n, seed);
        for (int i : o) if (possible.test(i) && !recent.test(i)) return i;
        for (int i : o) if (possible.test(i)) return i;
        return -1;
    }

    /** The saved list of recent ids with one more at its end, keeping the last {@code keep} ("a,b,c"). */
    static String remember(String saved, String id, int keep) {
        List<String> l = recent(saved);
        l.remove(id);
        l.add(id);
        int k = Math.max(0, keep);
        while (l.size() > k) l.removeFirst();
        return String.join(",", l);
    }

    static List<String> recent(String saved) {
        List<String> out = new ArrayList<>();
        if (saved != null) for (String s : saved.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // paging her back
    // ------------------------------------------------------------------------------------------

    /** A name as it is compared: no spaces, lower case ("파란 대문 집" = "파란대문집"). */
    static String plain(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** Whether the name typed after the pager's prompt is hers (one of {@code sequel.reply.names}). */
    static boolean her(String typed, Collection<String> names) {
        String t = plain(typed);
        if (t.isEmpty()) return false;
        for (String n : names) if (plain(n).equals(t)) return true;
        return false;
    }

    /** Whether these digits answer this call. */
    static boolean answers(Call c, String digits) {
        return c != null && c.kind() == Kind.REPLY && digits != null && c.answers().contains(digits);
    }

    // ------------------------------------------------------------------------------------------
    // "come early": before that day's nightfall
    // ------------------------------------------------------------------------------------------

    /** The world's time of day at which it is night (the main story's 505 waits for the same hour). */
    static final long NIGHTFALL = 13000;

    /** Whether a "come early" call may come now: by day, early enough that the walk is possible. */
    static boolean dayEnough(long timeOfDay, long latest) {
        long t = Math.floorMod(timeOfDay, 24000L);
        return t < Math.min(latest, NIGHTFALL);
    }

    /**
     * Whether the evening of the day the call came on is here: night has fallen on that day, or the world is already on
     * another day (they slept, an admin set the time). day: the world's full time / 24000 when it came.
     */
    static boolean dusk(long fullTime, long day) {
        return Math.floorDiv(fullTime, 24000L) != day || Math.floorMod(fullTime, 24000L) >= NIGHTFALL;
    }
}
