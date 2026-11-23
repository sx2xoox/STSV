package dev.stsv.economy.secret.newcomer;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The pure parts of the follow-up 「다음 사람」 ({@link NextSequel}; unit-tested): what each week's visitor really wants
 * (newcomer.yml {@code sequel.wishes}), how it is hidden in their questions, which wish and which name a week takes.
 * <p>
 * The hiding rule is not his acrostic: here the <b>first word</b> of each question (everything before its first space),
 * read in the order the questions are asked, is the wish — "밤에 …?", "정류장에서 …?", "기다려줄 …?", "사람 …?" says
 * "밤에 정류장에서 기다려줄 사람". As with him, only the questions end in "?"; nothing else a visitor says has one.
 */
final class Wishes {

    private Wishes() {}

    /** Where the errand is done. */
    enum Where {
        /** the first world's spawn */
        SPAWN,
        /** any bus stop */
        STOP,
        /** the monument of secrets */
        MONUMENT,
        /** the thirteenth stone (his; now the solver's) */
        STONE,
        /** water at least {@code depth} deep (the dropped thing must lie in it) */
        WATER,
        /** under the open sky at {@code height} or above */
        HIGH,
        ANY;

        static Where of(String s) {
            try {
                return s == null ? null : valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** What is done there. */
    enum Act {
        /** one of the items dropped (Q) there */
        DROP,
        /** one of the items held in a hand there for {@code seconds} */
        HOLD,
        /** simply being there for {@code seconds} */
        STAY;

        static Act of(String s) {
            try {
                return s == null ? null : valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** The errand as the game checks it. range: blocks around the place; depth / height: for WATER / HIGH. */
    record Task(Where where, Act act, List<String> items, boolean night, int seconds, double range, int depth, int height) {}

    /** One wish: what the first words spell, the questions in order, the errand, and what the visitor says when it is done. */
    record Wish(String id, String says, List<String> questions, Task task, List<String> thanks) {}

    static final List<String> FLOWERS = List.of("dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
            "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower", "sunflower", "lilac", "rose_bush", "peony");
    static final List<String> FISH = List.of("cod", "salmon", "tropical_fish", "pufferfish");
    static final List<String> LIGHTS = List.of("lantern", "soul_lantern", "candle", "torch");

    /** What a visitor says, in public, when the solver has done it (vague; no "?"). */
    static final List<String> THANKS = List.of("아 감사합니다 진짜로", "봤어요.. 고마워요", "와 진짜 해주셨네요 감사합니다", "고맙습니다 이제 됐어요 ㅎㅎ",
            "감사해요 덕분에 마음이 놓여요", "아.. 고마워요 저 이제 괜찮아요", "누군지 모르겠지만 감사합니다 ㅎㅎ", "고마워요. 그거면 돼요");

    /** Plausible account names for the visitors (never one that is somebody's: checked when one is taken). */
    static final List<String> NAMES = List.of("mandu_02", "haneul0412", "dotori__", "bori_cha", "ggomi99", "noeul_", "haru1004", "byeol2",
            "moongchi", "kimbap_3", "dalbit07", "nabi_ya", "podo_al", "jjangu_0", "baram_i", "hodu_gwaja", "minsu0309", "yuna_k", "gamja_tang", "sogeum_");

    private static Wish wish(String id, String says, List<String> questions, Where where, Act act, List<String> items, boolean night, int seconds, double range) {
        return new Wish(id, says, questions, new Task(where, act, items, night, seconds, range, 4, 150), List.of());
    }

    /** The code's own wishes (newcomer.yml says the same; NextSequelTest holds the two together). */
    static final List<Wish> DEFAULTS = List.of(
            wish("flower", "혹시 스폰에 꽃 하나만 부탁", List.of(
                    "혹시 여기 처음 오면 뭐부터 해야돼요?",
                    "스폰에 다시 가려면 어떻게 해요?",
                    "꽃 같은 것도 상점에 팔 수 있어요?",
                    "하나만 더 물어봐도 될까요?",
                    "부탁 같은 거 드려도 되는 서버에요?"), Where.SPAWN, Act.DROP, FLOWERS, false, 0, 12),
            wish("stop", "밤에 정류장에서 기다려줄 사람", List.of(
                    "밤에 몹 많이 나와요?",
                    "정류장에서 버스 타려면 돈 내야 돼요?",
                    "기다려줄 분 계시면 같이 가도 돼요?",
                    "사람 제일 많은 시간이 언제에요?"), Where.STOP, Act.STAY, List.of(), true, 30, 12),
            wish("fish", "물고기 한마리만 깊은 물에 놓아줄 사람", List.of(
                    "물고기 잡으면 어디다 팔아요?",
                    "한마리만 잡아도 도감에 올라가요?",
                    "깊은 데서 낚시하면 더 좋은거 나와요?",
                    "물에 빠지면 아이템 다 잃어요?",
                    "놓아줄 수도 있어요 잡은 물고기?",
                    "사람 없는 낚시터 추천 좀 해주실분?"), Where.WATER, Act.DROP, FISH, false, 0, 0),
            wish("candle", "기념비에 촛불 하나 들고 와줄 사람", List.of(
                    "기념비에 이름 적힌 분들은 뭐 하신 거에요?",
                    "촛불 같은 건 어떻게 만들어요?",
                    "하나 만들려면 재료 많이 들어요?",
                    "들고 있으면 밝아지는 아이템도 있어요?",
                    "와줄 분 있으면 구경시켜 주실래요?",
                    "사람 없을 때 가도 되는 곳이에요?"), Where.MONUMENT, Act.HOLD, LIGHTS, false, 15, 10),
            wish("stars", "밤에 산꼭대기에서 하늘 봐줄 사람", List.of(
                    "밤에 자면 아침 되는 거 맞아요?",
                    "산꼭대기에서 떨어지면 바로 죽어요?",
                    "하늘 날 수 있는 아이템도 있어요?",
                    "봐줄 분 있으면 제 집 평가 좀 해주실래요?",
                    "사람 얼굴 스킨은 어디서 바꿔요?"), Where.HIGH, Act.STAY, List.of(), true, 20, 0),
            wish("lamp", "바다에 등불 하나 띄워줄 사람", List.of(
                    "바다에 가려면 어느 쪽으로 가야돼요?",
                    "등불 이랑 횃불이랑 뭐가 더 밝아요?",
                    "하나 만드는데 철 얼마나 들어요?",
                    "띄워줄 수 있는 배는 어떻게 만들어요?",
                    "사람 태우는 배도 있어요?"), Where.WATER, Act.DROP, List.of("lantern", "soul_lantern", "candle"), false, 0, 0),
            wish("bread", "스폰에 빵 한개만 두고갈 사람", List.of(
                    "스폰에 상자 놓으면 안되죠?",
                    "빵 말고 배 채우기 좋은 거 뭐 있어요?",
                    "한개만 팔아도 상점에서 받아줘요?",
                    "두고갈 데 없으면 아이템 어디다 보관해요?",
                    "사람 많을 때는 렉 걸려요?"), Where.SPAWN, Act.DROP, List.of("bread", "economy:corn_bread"), false, 0, 12),
            wish("bench", "정류장에 꽃 한송이 두고갈 사람", List.of(
                    "정류장에 서있으면 버스가 그냥 와요?",
                    "꽃 심으면 옆으로 번져요?",
                    "한송이 씩 파는 것보다 묶어서 파는게 나아요?",
                    "두고갈 물건은 땅에 버리면 사라지죠?",
                    "사람 이름 누르면 뭐 나와요?"), Where.STOP, Act.DROP, FLOWERS, false, 0, 12),
            wish("dawn", "새벽에 스폰에서 같이 있어줄 사람", List.of(
                    "새벽에 접속하시는 분들도 계세요?",
                    "스폰에서 멀리 가면 다시 못 돌아와요?",
                    "같이 하면 경험치 더 줘요?",
                    "있어줄 분 계시면 저 길 좀 알려주실래요?",
                    "사람 때려도 되는 서버에요?"), Where.SPAWN, Act.STAY, List.of(), true, 30, 12));

    // ------------------------------------------------------------------------------------------
    // the hiding rule
    // ------------------------------------------------------------------------------------------

    /** The first word of a line: everything before its first space (a "?" at its end is not part of it). */
    static String firstWord(String line) {
        if (line == null) return "";
        String t = line.strip();
        int sp = t.indexOf(' ');
        String w = sp < 0 ? t : t.substring(0, sp);
        return w.replaceAll("\\?+$", "");
    }

    /** What the questions say, read by their first words. */
    static String spelled(List<String> questions) {
        List<String> w = new ArrayList<>();
        for (String q : questions) w.add(firstWord(q));
        return String.join(" ", w);
    }

    /** Whether a line may be one of a visitor's questions: more than one word, and a "?" at its end and nowhere else. */
    static boolean question(String line) {
        if (line == null) return false;
        String t = line.strip();
        if (!t.endsWith("?") || t.indexOf(' ') <= 0) return false;
        String body = t.replaceAll("\\?+$", "");
        return !body.isBlank() && !body.contains("?");
    }

    /** Why a wish cannot be used (null: it can): its questions, by their first words, must say exactly {@code says}. */
    static String problem(Wish w) {
        if (w.says() == null || w.says().isBlank()) return "says 가 비어 있음";
        if (w.questions().size() < 3) return "질문이 3개보다 적음";
        for (String q : w.questions()) if (!question(q)) return "\"" + q + "\" — 질문은 두 낱말 이상이고 끝에만 \"?\" 가 있어야 함";
        String got = spelled(w.questions()), want = String.join(" ", w.says().strip().split("\\s+"));
        if (!got.equals(want)) return "질문의 첫 낱말을 이으면 \"" + got + "\" — says \"" + want + "\" 과(와) 다름";
        Task t = w.task();
        if (t == null || t.where() == null || t.act() == null) return "task.where / task.do 를 읽을 수 없음";
        if (t.act() != Act.STAY && t.items().isEmpty()) return "task.items 가 비어 있음";
        if (t.where() == Where.WATER && t.act() != Act.DROP) return "where: water 는 do: drop 과만 씀";
        for (String s : w.thanks()) if (s.contains("?")) return "thanks \"" + s + "\" — \"?\" 를 쓰지 않음";
        return null;
    }

    /** Whether a typed line (a slip of the fingers in it) still begins with the same first word. */
    static boolean keepsFirstWord(String typed, String question) {
        return firstWord(typed).equals(firstWord(question));
    }

    // ------------------------------------------------------------------------------------------
    // reading the file
    // ------------------------------------------------------------------------------------------

    /** The wishes of {@code sequel.wishes} in the file's order (a missing section: the code's own); broken ones are left out and named. */
    static List<Wish> read(ConfigurationSection sec, List<String> problems) {
        if (sec == null) return DEFAULTS;
        List<Wish> out = new ArrayList<>();
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            ConfigurationSection t = s.getConfigurationSection("task");
            Task task = t == null ? null : new Task(Where.of(t.getString("where")), Act.of(t.getString("do")), clean(t.getStringList("items")),
                    t.getBoolean("night", false), Math.max(1, t.getInt("seconds", 20)), Math.max(1, t.getDouble("range", 12)),
                    Math.max(1, t.getInt("depth", 4)), t.getInt("height", 150));
            Wish w = new Wish(id, s.getString("says", ""), clean(s.getStringList("questions")), task, clean(s.getStringList("thanks")));
            String bad = problem(w);
            if (bad != null) problems.add("sequel.wishes." + id + ": " + bad + " (빼고 읽음)");
            else out.add(w);
        }
        if (out.isEmpty()) {
            problems.add("sequel.wishes 에 쓸 수 있는 소원이 하나도 없어서 코드의 기본 목록을 써요");
            return DEFAULTS;
        }
        return List.copyOf(out);
    }

    static List<String> clean(List<String> l) {
        List<String> out = new ArrayList<>();
        for (String s : l) if (s != null && !s.isBlank()) out.add(s.strip());
        return List.copyOf(out);
    }

    static Wish byId(List<Wish> wishes, String id) {
        for (Wish w : wishes) if (w.id().equals(id)) return w;
        return null;
    }

    /** Whether an item key ("minecraft:poppy", "economy:mackerel") is one of a list ("poppy", "economy:mackerel", "economy:*"). */
    static boolean listed(String key, Collection<String> ids) {
        if (key == null) return false;
        for (String id : ids) {
            String n = id.trim().toLowerCase(Locale.ROOT);
            if (!n.contains(":")) n = "minecraft:" + n;
            if (n.endsWith("*") ? key.startsWith(n.substring(0, n.length() - 1)) : n.equals(key)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // which wish, which name this week
    // ------------------------------------------------------------------------------------------

    static long seed(String week, long salt) {
        long z = (week == null ? 0 : week.hashCode()) * 0x9E3779B97F4A7C15L + salt * 0xD1B54A32D192ED03L + 0x2545F4914F6CDD1DL;
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
     * The week's pick out of n: the first in the week's own order that is usable and not a recent one; if all usable ones
     * were recent, the first usable; -1 when none is usable now.
     */
    static int pick(int n, long seed, Predicate<Integer> usable, Predicate<Integer> recent) {
        int[] o = order(n, seed);
        for (int i : o) if (usable.test(i) && !recent.test(i)) return i;
        for (int i : o) if (usable.test(i)) return i;
        return -1;
    }

    static List<String> recent(String saved) {
        List<String> out = new ArrayList<>();
        if (saved != null) for (String s : saved.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    static String remember(String saved, String id, int keep) {
        List<String> l = recent(saved);
        l.remove(id);
        l.add(id);
        while (l.size() > Math.max(0, keep)) l.removeFirst();
        return String.join(",", l);
    }

    /** Whether a world's time of day is night (13000..23000). */
    static boolean night(long timeOfDay) {
        long t = Math.floorMod(timeOfDay, 24000L);
        return t >= 13000 && t < 23000;
    }
}
