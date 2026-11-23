package dev.stsv.economy.secret.newcomer;

import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * His questions: a pool for each letter of the acrostic (newcomer.yml {@code questions.<letter>}), and the five of one night.
 * Each night takes one question from each pool, in the acrostic's order, chosen from the night's date alone (a restart in
 * the middle of a visit goes on with the same five), and none of them was asked in the three nights before (when the
 * pool has at least seven). A question is {@code "text"} or {@code {text: "...", rain: "..."}}: the rain words are said
 * instead while it rains in the main world. {@code {online}} and {@code {time}} are filled in when he says it.
 * <p>
 * Every question starts with its letter and ends with "?"; nothing else he says in public has a "?" at all (so his five
 * stand apart from his small talk). Lines that break this are dropped when the file is read (with a warning).
 */
final class Questions {

    private Questions() {}

    /** One question; rain: what he says instead while it rains (null: the same). */
    record Entry(String text, String rain) {
        Entry(String text) {
            this(text, null);
        }

        String words(boolean raining) {
            return raining && rain != null ? rain : text;
        }
    }

    static final Map<String, List<Entry>> DEFAULTS = defaults();

    private static Map<String, List<Entry>> defaults() {
        Map<String, List<Entry>> d = new LinkedHashMap<>();
        d.put("내", List.of(
                new Entry("내일도 이 시간에 사람 있어요?"),
                new Entry("내일도 {time} 들어오시는 분 있어요?"),
                new Entry("내 땅 사려면 돈 얼마나 모아야돼요?"),
                new Entry("내구도 다 된 곡괭이 고칠수 있나요?"),
                new Entry("내 직업 레벨은 어디서 봐요?"),
                new Entry("내 닉네임 한글로 바꿀 수 있어요??"),
                new Entry("내가 캔 광석 그냥 상점에 팔면 되는거죠?"),
                new Entry("내려가는 엘레베이터 같은거 만들 수 있어요?"),
                new Entry("내일 아침에 뭐 초기화 되는거 있어요?"),
                new Entry("내 집 옆에 다른 사람이 땅 사면 어떻게 돼요?"),
                new Entry("내일 저녁엔 {online}명보다 많이 와요?")));
        d.put("비", List.of(
                new Entry("비 오면 낚시 더 잘돼요?", "비 오는데 지금 낚시하면 뭐 더 잘 잡혀요?"),
                new Entry("비 맞으면 작물 더 빨리 자라요?", "비 오는데 밭에 물 안 줘도 되나요?"),
                new Entry("비 오면 몹 더 많이 나와요?", "비 오니까 몹 갑자기 많아진거 같은데 원래 그래요?"),
                new Entry("비싼 물고기는 어디서 잡혀요?"),
                new Entry("비료 같은거 따로 팔아요?"),
                new Entry("비행기 같은 것도 탈 수 있어요??"),
                new Entry("비싸게 팔리는 작물 뭐에요?"),
                new Entry("비추하는 직업 있어요?"),
                new Entry("비상금 땅 금고에 넣어놔도 돼요?"),
                new Entry("비슷한 물고기 많던데 도감엔 다 따로 들어가요?"),
                new Entry("비트 씨앗은 어디서 구해요?")));
        d.put("석", List.of(
                new Entry("석탄 왜케 안보이죠 ㅠ 어디서 캐요?"),
                new Entry("석탄 상점에 팔면 얼마 쳐줘요?"),
                new Entry("석궁 쓰는 사람 있어요?"),
                new Entry("석재 많이 필요한데 상점에서 팔아요?"),
                new Entry("석영은 지옥 가야만 나와요?"),
                new Entry("석탄 캘때 곡괭이 뭐 써야 빨라요?"),
                new Entry("석류 같은 과일도 키울 수 있어요?"),
                new Entry("석탄 블록으로 만들어서 팔면 더 비싸요?"),
                new Entry("석양 질때쯤부터 몹 나와요?"),
                new Entry("석탄 말고 철은 몇층에서 많이 나와요?"),
                new Entry("석상 같은 장식도 살 수 있어요?")));
        d.put("어", List.of(
                new Entry("어디서 돈 제일 많이 벌어요??"),
                new Entry("어떤 직업이 초보한테 제일 좋아요?"),
                new Entry("어부 레벨 올리기 많이 힘들어요?"),
                new Entry("어 근데 여기 밤에 몹 나와요?"),
                new Entry("어떻게 하면 마을버스 기사 할 수 있어요?"),
                new Entry("어디서 길라잡이 다시 볼 수 있어요?"),
                new Entry("어 메뉴 여는 키가 뭐였죠?"),
                new Entry("어느 정도 모아야 땅 살 수 있어요?"),
                new Entry("어항에 물고기 넣어서 키울 수 있어요?"),
                new Entry("어부 하면 통발도 꼭 써야돼요?", "어 비 오네 이럴때 낚시하면 좋아요?"),
                new Entry("어디가 사람 제일 많이 모여요?"),
                new Entry("어제 들어오신 분들은 오늘 안 오세요?")));
        d.put("디", List.of(
                new Entry("디스코드 같은거 있나요?"),
                new Entry("디코 방 있으면 링크 받을 수 있어요?"),
                new Entry("디저트 같은 요리도 만들 수 있어요?"),
                new Entry("디버프 걸렸는데 이거 뭐에요??"),
                new Entry("디엠은 어떻게 보내요?"),
                new Entry("디펜스 같은 미니게임도 있어요?"),
                new Entry("디아블로처럼 장비 강화도 돼요?"),
                new Entry("디테일하게 집 지으려면 건축월드 가야돼요?"),
                new Entry("디자인 이쁜 집 구경할 만한 데 있어요?"),
                new Entry("디폴트 스킨이면 치장 안 보여요?")));
        return java.util.Collections.unmodifiableMap(d);
    }

    /** The acrostic's letters, in order (spaces do not count). */
    static List<String> letters(String acrostic) {
        List<String> out = new ArrayList<>();
        if (acrostic == null) return out;
        acrostic.replace(" ", "").codePoints().forEach(c -> out.add(new String(Character.toChars(c))));
        return out;
    }

    /** Whether a line may be one of his questions for that letter: starts with it, ends with "?", one line. */
    static boolean valid(String line, String letter) {
        if (line == null || letter == null || letter.isEmpty()) return false;
        String s = line.strip();
        return s.equals(line) && s.startsWith(letter) && s.endsWith("?") && !s.contains("\n") && s.length() > letter.length() + 1;
    }

    static boolean valid(Entry e, String letter) {
        return valid(e.text(), letter) && (e.rain() == null || valid(e.rain(), letter));
    }

    /**
     * The pools for the acrostic's letters, read from the file's {@code questions} section (null: none). A letter with no
     * usable question falls back to the code's own pool for it (when there is one). problems: one line per thing dropped.
     */
    static Map<String, List<Entry>> read(ConfigurationSection sec, String acrostic, List<String> problems) {
        Map<String, List<Entry>> out = new LinkedHashMap<>();
        for (String letter : letters(acrostic)) {
            if (out.containsKey(letter)) continue;
            List<Entry> pool = new ArrayList<>();
            List<?> raw = sec == null ? null : sec.getList(letter);
            if (raw != null) {
                for (Object o : raw) {
                    Entry e = entry(o);
                    if (e == null) continue;
                    if (valid(e, letter)) pool.add(e);
                    else problems.add("questions." + letter + ": \"" + e.text() + (e.rain() == null ? "" : "\" / \"" + e.rain())
                            + "\" — \"" + letter + "\" 로 시작하고 \"?\" 로 끝나야 해요 (빼고 읽음)");
                }
            }
            if (pool.isEmpty()) {
                List<Entry> def = DEFAULTS.get(letter);
                if (def != null) pool.addAll(def);
                problems.add("questions." + letter + ": 쓸 수 있는 질문이 없어요" + (def != null ? " → 기본 질문을 씀" : " → 이 글자는 비어 있음 (풀 수 없음)"));
            }
            out.put(letter, List.copyOf(pool));
        }
        return out;
    }

    private static Entry entry(Object o) {
        if (o instanceof String s) return s.isBlank() ? null : new Entry(s.strip());
        Object text = null, rain = null;
        if (o instanceof Map<?, ?> m) {
            text = m.get("text");
            rain = m.get("rain");
        } else if (o instanceof ConfigurationSection c) {
            text = c.getString("text");
            rain = c.getString("rain");
        }
        if (!(text instanceof String t) || t.isBlank()) return null;
        return new Entry(t.strip(), rain instanceof String r && !r.isBlank() ? r.strip() : null);
    }

    /** The night's five (one per letter, in order): their indexes in each letter's pool. */
    static int[] pick(LocalDate night, long seed, List<String> letters, Map<String, List<Entry>> pools) {
        int[] out = new int[letters.size()];
        for (int i = 0; i < out.length; i++) {
            List<Entry> pool = pools.get(letters.get(i));
            int n = pool == null ? 0 : pool.size();
            // a letter that comes twice in the word gets another salt, so it is not the same question twice
            out[i] = n == 0 ? -1 : index(night.toEpochDay(), seed * 31 + i, letters.get(i).codePointAt(0), n);
        }
        return out;
    }

    /** The night's five as entries (empty slots are left out). */
    static List<Entry> tonight(LocalDate night, long seed, List<String> letters, Map<String, List<Entry>> pools) {
        int[] ix = pick(night, seed, letters, pools);
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < ix.length; i++) if (ix[i] >= 0) out.add(pools.get(letters.get(i)).get(ix[i]));
        return out;
    }

    /**
     * The question of one day out of a pool of n: the days run through the pool in shuffled rounds of n days, so that no
     * question comes back inside a round, and the first three days of a round are kept clear of the last three of the
     * round before (only places that are not among a round's last three are swapped, so the round before's ending is
     * always its own shuffle: nothing needs to look further back). With n ≥ 7 no question repeats within four nights.
     */
    static int index(long day, long seed, int salt, int n) {
        if (n <= 1) return 0;
        long round = Math.floorDiv(day, n);
        int pos = (int) Math.floorMod(day, (long) n);
        return round(round, seed, salt, n)[pos];
    }

    static int[] round(long round, long seed, int salt, int n) {
        int[] p = shuffle(round, seed, salt, n), q = shuffle(round - 1, seed, salt, n);
        for (int i = 0; i < Math.min(3, n - 3); i++) {   // never a place among the last three
            if (!forbidden(p[i], i, q)) continue;
            for (int j = i + 1; j <= n - 4; j++) {
                if (!forbidden(p[j], i, q)) {
                    int t = p[i];
                    p[i] = p[j];
                    p[j] = t;
                    break;
                }
            }
        }
        return p;
    }

    /** Whether v at place i of a round was asked in the three nights before it (the end of the round before). */
    private static boolean forbidden(int v, int i, int[] before) {
        int n = before.length;
        for (int back = 1; back <= 3 - i; back++) if (n - back >= 0 && before[n - back] == v) return true;
        return false;
    }

    private static int[] shuffle(long round, long seed, int salt, int n) {
        SplittableRandom r = new SplittableRandom(round * 0x9E3779B97F4A7C15L ^ seed * 0xC2B2AE3D27D4EB4FL ^ (long) salt * 0x165667B19E3779F9L);
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        return p;
    }

    /** "{online}", "{time}" and any other {key} filled in. */
    static String fill(String line, Map<String, String> vars) {
        String s = line;
        for (Map.Entry<String, String> e : vars.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
        return s;
    }

    /** The words for {time} at an hour (Korean time): the small hours, the night, or any other time. */
    static String time(int hour, String dawn, String night, String other) {
        if (hour >= 0 && hour < 5) return dawn;
        if (hour >= 20) return night;
        return other;
    }
}
