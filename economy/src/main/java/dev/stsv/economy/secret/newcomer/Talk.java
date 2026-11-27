package dev.stsv.economy.secret.newcomer;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.random.RandomGenerator;

/**
 * What a line of real chat (or a whisper to him) is about, and his words for it. The intents are checked in order and the
 * first that fits is the one (newcomer.yml {@code chat.intents} / {@code whisper.intents}, in the file's order). An intent
 * fits when one of its patterns is found in the line (regular expressions, any case; {@code {name}} is his name without
 * the "_"), and only when it may: {@code addressed} intents only for a line said to him (his name in it, or just after he
 * spoke), {@code answer} intents only just after one of his questions.
 */
final class Talk {

    private Talk() {}

    /** One kind of line and what he says to it. */
    static final class Intent {
        final String id;
        final List<String> match;
        final List<Pattern> patterns;
        final boolean addressed, answer;
        /** the share of fitting lines he says something to at all (test mode: always) */
        final double chance;
        final List<String> lines;
        /** said a little after the line, now and then (answer: "지금 해봐야겠다") */
        final List<String> follow;
        final double followChance;
        /** said instead of a line, rarely (a slip that is not quite right) */
        final List<String> slip;
        final double slipChance;
        /** seconds he says nothing more after this (rudeness) */
        final int hush;

        Intent(String id, List<String> match, boolean addressed, boolean answer, double chance, List<String> lines,
               List<String> follow, double followChance, List<String> slip, double slipChance, int hush, String name) {
            this.id = id;
            this.match = List.copyOf(match);
            this.addressed = addressed;
            this.answer = answer;
            this.chance = chance;
            this.lines = List.copyOf(lines);
            this.follow = List.copyOf(follow);
            this.followChance = followChance;
            this.slip = List.copyOf(slip);
            this.slipChance = slipChance;
            this.hush = hush;
            List<Pattern> p = new ArrayList<>();
            for (String m : match) {
                try {
                    p.add(Pattern.compile(m.replace("{name}", Pattern.quote(base(name))), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                } catch (PatternSyntaxException e) {
                    p.add(Pattern.compile(Pattern.quote(m), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                }
            }
            this.patterns = List.copyOf(p);
        }

        boolean finds(String text) {
            for (Pattern p : patterns) if (p.matcher(text).find()) return true;
            return false;
        }

        /** The same intent for another name (its {name} patterns). */
        Intent named(String name) {
            return new Intent(id, match, addressed, answer, chance, lines, follow, followChance, slip, slipChance, hush, name);
        }
    }

    /** His name as people type it: "sipsam_" → "sipsam". */
    static String base(String name) {
        if (name == null) return "";
        String b = name.toLowerCase(Locale.ROOT).replaceAll("^_+|_+$", "");
        return b.isEmpty() ? name.toLowerCase(Locale.ROOT) : b;
    }

    /** Whether a line names him (directly addressed). */
    static boolean mentions(String text, String name) {
        String b = base(name);
        return text != null && b.length() >= 3 && text.toLowerCase(Locale.ROOT).contains(b);
    }

    /**
     * The intent of a line, or empty. addressed: the line is said to him (his name is in it, or it came just after he
     * spoke); afterQuestion: it came just after one of his questions (and he has not thanked anyone for that one yet).
     */
    static Optional<Intent> match(List<Intent> intents, String text, boolean addressed, boolean afterQuestion) {
        if (text == null || text.isBlank()) return Optional.empty();
        String t = text.strip();
        for (Intent i : intents) {
            if (i.answer && !afterQuestion) continue;
            if (i.addressed && !addressed) continue;
            if (i.finds(t)) return Optional.of(i);
        }
        return Optional.empty();
    }

    /** One line of a pool, not one of those he said lately when it can be helped. Null for an empty pool. */
    static String pick(List<String> pool, Collection<String> recent, RandomGenerator r) {
        if (pool.isEmpty()) return null;
        List<String> fresh = new ArrayList<>();
        for (String s : pool) if (!recent.contains(s)) fresh.add(s);
        List<String> from = fresh.isEmpty() ? pool : fresh;
        return from.get(r.nextInt(from.size()));
    }

    // ------------------------------------------------------------------------------------------
    // reading the file
    // ------------------------------------------------------------------------------------------

    /**
     * The intents of a section (chat.intents / whisper.intents), in the file's order; what an intent leaves out is taken
     * from the code's intent of the same id. A missing section: the code's own. problems: lines that break a rule.
     */
    static List<Intent> read(ConfigurationSection sec, List<Intent> defaults, String name, boolean publicLines, List<String> problems) {
        if (sec == null) {
            List<Intent> out = new ArrayList<>();
            for (Intent d : defaults) out.add(d.named(name));
            return out;
        }
        List<Intent> out = new ArrayList<>();
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            Intent d = defaults.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
            String where = sec.getCurrentPath() + "." + id;
            Intent i = new Intent(id,
                    s.isList("match") ? clean(s.getStringList("match")) : d != null ? d.match : List.of(),
                    s.getBoolean("addressed", d != null && d.addressed),
                    s.getBoolean("answer", d != null && d.answer),
                    s.getDouble("chance", d != null ? d.chance : 1.0),
                    lines(s, "lines", d != null ? d.lines : List.of(), publicLines, where, problems),
                    lines(s, "follow", d != null ? d.follow : List.of(), publicLines, where, problems),
                    s.getDouble("follow-chance", d != null ? d.followChance : 0),
                    lines(s, "slip", d != null ? d.slip : List.of(), publicLines, where, problems),
                    s.getDouble("slip-chance", d != null ? d.slipChance : 0),
                    s.getInt("hush-seconds", d != null ? d.hush : 0),
                    name);
            if (i.match.isEmpty()) problems.add(where + ": match 가 비어 있어서 어떤 말에도 맞지 않아요");
            out.add(i);
        }
        return out;
    }

    private static List<String> lines(ConfigurationSection s, String key, List<String> def, boolean publicLines, String where, List<String> problems) {
        if (!s.isList(key)) return def;
        List<String> out = new ArrayList<>();
        for (String l : clean(s.getStringList(key))) {
            if (publicLines && !small(l)) {
                problems.add(where + "." + key + ": \"" + l + "\" — 질문이 아닌 말에는 \"?\" 를 쓰지 않아요 (빼고 읽음)");
                continue;
            }
            out.add(l);
        }
        return List.copyOf(out);
    }

    /** Whether a line may be said in public as small talk: no "?" (only his five questions have one). */
    static boolean small(String line) {
        return line != null && !line.contains("?") && !line.isBlank();
    }

    static List<String> clean(List<String> l) {
        List<String> out = new ArrayList<>();
        for (String s : l) if (s != null && !s.isBlank()) out.add(s.strip());
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------------------------------
    // the code's own words (newcomer.yml says the same; NewcomerTest holds the two together)
    // ------------------------------------------------------------------------------------------

    private static Intent def(String id, List<String> match, boolean addressed, boolean answer, double chance, List<String> lines) {
        return new Intent(id, match, addressed, answer, chance, lines, List.of(), 0, List.of(), 0, 0, Newcomer.DEFAULT_NAME);
    }

    private static Intent with(Intent i, List<String> follow, double followChance, List<String> slip, double slipChance, int hush) {
        return new Intent(i.id, i.match, i.addressed, i.answer, i.chance, i.lines, follow, followChance, slip, slipChance, hush, Newcomer.DEFAULT_NAME);
    }

    static final List<Intent> CHAT = List.of(
            // swearing at him: not small talk but a sulk (Sulk; Newcomer leaves it out of the small talk). lines: his hurt line
            def("rude", Sulk.SWEARS, true, false, 1, Sulk.HURT),
            with(def("bot", List.of("봇|bot|가짜|npc|엔피씨|귀신|유령|매크로|인공지능|\\bai\\b|사람 맞|사람이야|사람임"), true, false, 0.7,
                    List.of("ㅋㅋㅋ 아니에요", "사람이에요 ㅠ", "ㅋㅋㅋㅋ 봇 아님", "저 사람인데요 ㅠㅠ", "아닌데 ㅋㅋ", "ㅋㅋㅋ 왜요")),
                    List.of(), 0, List.of("ㅋㅋ 그 말 예전에도 들었어요", "그런 말 진짜 오랜만에 듣네요", "ㅋㅋ 저 여기 꽤 오래 있었는데", "저 예전엔 좀 이상했죠 ㅋㅋ"), 0.1, 0),
            def("meet", List.of("좌표|어디야|어디세요|어디 ?계세요|어디 ?계신|어디심|어디임|어디에 ?있어|tpa|티피|텔포|같이 ?하|같이 ?놀|같이 ?갈|놀러|찾아 ?갈|만나요|만날래|데리러"),
                    true, false, 0.8,
                    List.of("아 저 지금 좀 멀리 있어요", "나중에 같이해요 ㅎㅎ", "저 길 잃어서 ㅋㅋ 여기가 어딘지 모르겠어요",
                            "좀 이따가요 ㅠ 지금 뭐 하는중이라", "아 괜찮아요 혼자 해볼게요 ㅎㅎ", "저 아직 많이 헤매는 중이라 ㅠ")),
            def("name", List.of("{name}", "십삼", "뉴비", "누구세요|누구야|누구지|누구임|누구심|누군데", "새로 ?오신|새로 ?온|처음 ?보는|처음 ?오셨"),
                    false, false, 0.6,
                    List.of("저 들어온지 얼마 안됐어요", "그냥 뉴비에요 ㅎㅎ", "아 저요 ㅋㅋ 그냥 구경중이에요", "아직 아무것도 몰라요 ㅠ",
                            "새로 왔어요 잘 부탁드려요", "저 그냥 지나가다 들어왔어요 ㅎㅎ")),
            def("greet", List.of("^(ㅎㅇ|ㅎㅇㅎㅇ|하이|하잉|안녕|안냐|ㅂㄱ|반가|hi\\b|hello)", "안녕하세요|반가워|반갑습니다|하이요"), false, false, 0.35,
                    List.of("안녕하세요 ㅎㅎ", "ㅎㅇ", "ㅎㅇㅎㅇ", "하이요", "안녕하세요", "반가워요 ㅎㅎ")),
            with(def("answer", List.of("[가-힣a-z0-9]"), false, true, 0.6,
                    List.of("아 감사합니다 ㅎㅎ", "오 그렇구나", "헐 몰랐어요", "ㅇㅋ 해볼게요", "아하", "오오 감사요", "아 그렇군요 ㅎㅎ",
                            "넵 감사합니당", "아 그거였구나", "ㄳㄳ", "오 ㄱㅅㄱㅅ")),
                    List.of("지금 해봐야겠다", "아까부터 그거 찾고 있었어요 ㅋㅋ", "물어보길 잘했다", "메모해둠", "와 하나도 몰랐네"), 0.3,
                    List.of(), 0, 0),
            def("laugh", List.of("ㅋㅋㅋ|ㅎㅎㅎ|ㅋㅌㅋ|zzz|ㄱㅋㄱㅋ"), false, false, 0.15,
                    List.of("ㅋㅋㅋ", "ㅋㅋㅋㅋ", "ㅋㅋ", "ㅎㅎ")));

    static final List<Intent> WHISPER = List.of(
            def("stone", List.of("비석|기념비|돌|무덤|묘|열세|13번|이름 없"), false, false, 0.6,
                    List.of("…돌이요?", "…잘 모르겠어요.")),
            def("bot", List.of("봇|bot|가짜|npc|엔피씨|귀신|유령|매크로|\\bai\\b|사람 맞|사람이야|사람이에요|살아|죽었"), false, false, 1,
                    List.of("ㅋㅋ 아니에요", "사람이에요 ㅠ", "저 봇 아니에요 ㅠㅠ", "…왜 다들 그렇게 물어보세요.")),
            def("where", List.of("어디|좌표|위치|tpa|티피|텔포|같이|만나|찾아"), false, false, 1,
                    List.of("아 저 지금 좀 멀리 있어요", "여기가 어딘지 잘 모르겠어요 ㅠ", "나중에 같이해요 ㅎㅎ")),
            def("who", List.of("누구|뉴비|정체|이름|뭐하는|뭐해|뭐 해"), false, false, 1,
                    List.of("그냥 새로 온 사람이에요", "아 저 뉴비에요 ㅎㅎ", "저 들어온지 얼마 안됐어요")),
            def("greet", List.of("안녕|ㅎㅇ|하이|반가|hello|hi\\b"), false, false, 1,
                    List.of("아 안녕하세요", "앗 안녕하세요 ㅎㅎ", "…안녕하세요.")),
            def("other", List.of("."), false, false, 1,
                    List.of("아 죄송해요 제가 아직 잘 몰라서요", "앗 네네", "음 잘 모르겠어요 ㅠ", "넵", "아 그렇구나", "ㅎㅎ", "…네.")));

    /** Whispered to him on enough nights: now and then one of these instead. */
    static final List<String> STRANGE = List.of("…저 계속 여기 있었는데요.", "…어제도 물어보셨잖아요.", "…매일 오시네요.",
            "…저 아직 여기 있어요.", "…그 말 전에도 하셨어요.");
}
