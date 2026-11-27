package dev.stsv.economy.secret.newcomer;

import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 새로 온 사람 (docs/admin/숨은_비밀/새로_온_사람.md, contents/economy/secrets/newcomer.yml). Every night someone new joins
 * and asks beginner questions in public chat. He is not in the tab list, nobody has met him, and the server has never said
 * that he left. Long ago, alone on the server one night, he solved a secret; that same day the server was rolled back and
 * his record — the join, the announcement, the stone — was lost. Only he stayed: the server does not know he ever came in,
 * so he cannot leave, and with his name gone it calls him by a number.
 * <p>
 * 0 · He wakes: his small talk is more and more written by a model ({@link Voice}, {@link NewcomerMind}), a night has an
 * end (he says he is going to sleep). 1 · The first characters of his five questions ask "내 비석 어디"; whoever stands at
 * the monument's bare stone and whispers to him ({@link Whispers}) is told that it was his, and from the first such whisper
 * the stone shows four broken lines to everyone. 2 · The record is put back by the whole server ({@link Restore},
 * {@link Shards}, {@link Record}): the day and what he solved from shards that turn up in broken traces, his name from his
 * own slips in chat ({@link Hanyoung}), engraved with a name tag. 3 · With all three lines back he stands at the stone for
 * all to see, and someone signs as his witness ({@link Vigil}): the announcement, dated that day; his leave line, the only
 * one ever. 4 · The night after, a letter for everyone who spoke to him ({@link Letters}, {@link Memories}).
 * <p>
 * He reads like a shy new player: each night's five questions come out of a pool per letter ({@link Questions}), he takes
 * his time typing ({@link Typing}), now and then slips and puts it right, and while he is "online" he answers a little of
 * what is said in chat, and whispers, by what they are about ({@link Talk}). How his name, chat line and whispers look is
 * built in {@link Look} alone.
 * <p>
 * Stage: 0 = never whispered to him; 1 = whispered (anywhere); 2 = whispered at his stone; done = signed as his witness.
 * Saved, for everyone (eco.kv "secret.newcomer."): {@code seen}, {@code night}, {@code asked}, {@code joined} as before
 * (one visit and one join line a night), {@code sulk} / {@code sulkby}, {@code tired} / {@code tiredline},
 * {@code stone.*} ({@link Record}), {@code ai.*} ({@link Voice}), {@code say} (lines he owes the chat: said when he is
 * next around), {@code typo.night} / {@code typo.n} (his slip with the 한/영 key), {@code truth.date}, {@code clue.*},
 * {@code letter.*} / {@code letters.*}. Per player ("secret.newcomer.&lt;uuid&gt;."): {@code seen}, {@code whispers},
 * {@code wnight} / {@code wnights}, {@code sulk}, {@code atstone} (whispered at his stone: traces show themselves),
 * {@code clue.day} / {@code clue.n}, {@code tag.night} / {@code tag.n} (tries at his name), {@code shard.detail}, and a
 * tester's own {@code stone.*}.
 * <p>
 * Sworn at ({@link Sulk}): one hurt line and he is gone for the rest of that night, without a leave line.
 */
public final class Newcomer extends Secret {

    static final String K = "secret.newcomer.", SEEN = K + "seen", NIGHT = K + "night", ASKED = K + "asked", JOINED = K + "joined",
            SULK = K + "sulk", SULK_BY = K + "sulkby",
            TIRED_NIGHT = K + "tired", TIRED_LINE = K + "tiredline", SAY = K + "say", TYPO_NIGHT = K + "typo.night", TYPO_N = K + "typo.n",
            DATE = K + "truth.date", TOLD = K + "stone.told", TWO = K + "stone.two";
    static final String DEFAULT_NAME = "sipsam_", DEFAULT_ACROSTIC = "내비석어디";
    /** What he whispers to whoever finds his stone (the first time; later one of {@link #AGAIN}). */
    static final List<String> FOUND = List.of("…찾으셨네요.", "…비어 있죠. 원래는 제 이름이 있었어요.",
            "…그날 다 날아갔어요. 조각은 아직 어딘가 남아 있을 거예요.");
    static final List<String> AGAIN = List.of("…또 오셨네요.", "…아직 비어 있죠.", "…조각은 어딘가 남아 있을 거예요.", "…여기 있으면 좀 나아요.");
    /** In public, when something came back to the stone; per line when it is whole; and when two of three are. */
    static final List<String> BACK = List.of("어.. 방금 뭔가 돌아왔어요", "어 방금 뭐가 돌아온 것 같아요", "아.. 뭔가 생각났어요 방금");
    static final String BACK_DATE = "날짜가 보여요. 그날 맞아요", WHOLE_DATE = "…그날 밤에 저 혼자였어요",
            WHOLE_WHAT = "…등불 하나 켰어요. 그게 다예요. 근데 되게 좋았어요", WHOLE_NAME = "…{player}님, 방금 제 이름 부르셨죠. 들렸어요",
            TWO_LINES = "…저 사실 나가는 법이 없어요. 들어온 기록이 없어서요.", ALL_BACK = "…다 돌아왔어요. 거기 있을게요.",
            FOUND_NEXT = "어제 누가 제 돌을 찾아 주셨어요";
    /** Asked his name: he cannot say it. */
    static final String NO_NAME = "…말해도 안 돼요. 제가 말하면 글자가 깨져서 나가요";
    static final String NAME_ASK = "이름|성함|닉네임|닉이|본명";
    /** Close to that night's last answer, and the last one (no "?"). */
    static final List<String> SLEEPY = List.of("하암..", "아 좀 졸리네요", "눈이 좀 감겨요 ㅎㅎ", "네에.. 하암");
    static final List<String> GOODNIGHT = List.of("저 이제 자러 갈게요", "저 이제 자러 갈게요 다들 좋은 밤 되세요", "저 먼저 자러 갈게요 ㅎㅎ");
    /** How he puts a slip right ({word}: the word as it should have been). */
    static final List<String> FIXES = List.of("*{word}", "*{word}", "{word}*", "아 오타", "오타 ㅋㅋ", "*{word} 오타..");
    static final List<String> TIME_WORDS = List.of("이 새벽에", "이 밤에", "이 시간에");
    /** His one tired line when the small-talk time after the fifth question ends (no "?": he does not leave, he only goes quiet). */
    static final List<String> TIRED = List.of("아 졸리다 ㅠ", "오늘은 여기까지 해야겠다 피곤하네요", "눈이 감기네요 ㅋㅋ",
            "저 잠깐 쉬어야겠어요 너무 피곤해요", "하암.. 오늘 수고하셨어요", "졸려서 손가락이 안움직여요ㅋㅋ", "아 벌써 시간이 이렇게 됐네",
            "오늘 많이 배웠어요 감사합니다 ㅎㅎ", "피곤하다.. 내일 또 물어볼게요", "하품 계속 나와요 ㅋㅋ", "다들 좋은 밤 되세요");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    /** A visit left alone this long (ms; the secret was off) is over. */
    private static final long STALE = 120_000L;

    private final Stones stones;
    private final Look look;
    /** his visit to everyone (one at a time), and his visits to one tester or admin each */
    private volatile Visit visit;
    private final Map<UUID, Visit> alone = new ConcurrentHashMap<>();
    /** the record on his stone (the server's; a tester has their own), what he remembers of people, who writes his words */
    private final Record.Keep keep;
    private final Voice voice;
    private final Memories memories;
    private final Restore restore = new Restore(this);
    private final Vigil vigil = new Vigil(this);
    private volatile Record.Truth truth = new Record.Truth(Record.Truth.NAME, Record.Truth.ALIAS, "", Record.Truth.WHAT);
    /** what real players said lately in public (for the model: read in one go, answered in one line) */
    private final Deque<NewcomerMind.Heard> overheard = new ArrayDeque<>();
    private List<String> sleepy = SLEEPY, goodnight = GOODNIGHT;
    /** when a player last got an answer / last had a whisper counted (ms) */
    private final Map<UUID, Long> answered = new ConcurrentHashMap<>(), counted = new ConcurrentHashMap<>();
    /** what he said lately, in chat and in whispers (not said again soon) */
    private final Deque<String> recent = new ArrayDeque<>(), recentWhispers = new ArrayDeque<>();
    private long lastJoin, lastTick;
    private int ticks;

    private String who = DEFAULT_NAME;
    private List<String> letters = Questions.letters(DEFAULT_ACROSTIC), labels = Whispers.VANILLA, fixes = FIXES,
            strange = Talk.STRANGE, timeWords = TIME_WORDS;
    private Map<String, List<Questions.Entry>> pools = Questions.DEFAULTS;
    private List<Talk.Intent> chat = Talk.CHAT, whisperTalk = Talk.WHISPER, talk = smallTalk(Talk.CHAT);
    /** swearing at him (rude's patterns, the allow list, what aims a line at him), his hurt lines, his sheepish word */
    private List<Pattern> swears = Sulk.patterns(Sulk.SWEARS), allow = Sulk.patterns(Sulk.ALLOW), aim = Sulk.patterns(Sulk.AIM);
    private List<String> hurt = Sulk.HURT, sorry = Sulk.SORRY, tired = TIRED;
    private int from = 22, to = 2;
    private long seed = 13;

    public Newcomer(SecretModule m) {
        super(m);
        keep = new Record.Keep() {
            @Override
            public String get(String key) {
                return eco.kv().get(key);
            }

            @Override
            public void set(String key, String value) {
                eco.kv().set(key, value);
            }

            @Override
            public void remove(String key) {
                eco.kv().remove(key);
            }
        };
        voice = new Voice(keep, K + "ai.");
        Memories mem;
        try {
            mem = new Memories(m.core().db());
            mem.init();
        } catch (RuntimeException e) {
            eco.getLogger().log(java.util.logging.Level.WARNING, "[비밀] newcomer: 기억 표를 열 수 없어요 (메모리에만 둠)", e);
            mem = new Memories(null);
        }
        memories = mem;
        stones = new Stones(eco);
        look = new Look(DEFAULT_NAME, () -> m.core().getConfig().getString("profiles.chat-format"));
    }

    @Override
    public String id() {
        return "newcomer";
    }

    @Override
    public String name() {
        return "새로온사람";
    }

    @Override
    public String stoneTitle() {
        return truth.name();
    }

    /** The stone writes itself: nothing, then four broken lines coming back, then whole with the witness. */
    @Override
    public String stoneText() {
        return record(null).text();
    }

    /** The announcement is his own, dated that day ({@link Vigil}). */
    @Override
    public String solvedLine() {
        return null;
    }

    /** Opened anew: the stone is bare again, no letters wait, the lamp goes; what he remembers of people stays. */
    @Override
    public void serverReset() {
        record(null).clear();
        for (String k : List.of(SAY, TOLD, TWO, TYPO_NIGHT)) eco.kv().remove(k);
        vigil.reset();
    }

    /** His stone says nothing until it is solved: it is the bare one. */
    @Override
    public boolean hiddenStone() {
        return true;
    }

    @Override
    public int stages() {
        return 2;
    }

    // ------------------------------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------------------------------

    @Override
    protected void loaded() {
        who = cfg.getString("name", DEFAULT_NAME);
        if (who == null || who.isBlank() || who.contains(" ")) who = DEFAULT_NAME;
        who = who.strip();
        look.name(who);
        String word = cfg.getString("acrostic", DEFAULT_ACROSTIC);
        if (word == null || word.isBlank()) word = DEFAULT_ACROSTIC;
        letters = Questions.letters(word);
        List<String> problems = new ArrayList<>();
        pools = Questions.read(cfg.getConfigurationSection("questions"), word, problems);
        chat = Talk.read(cfg.getConfigurationSection("chat.intents"), Talk.CHAT, who, true, problems);
        whisperTalk = Talk.read(cfg.getConfigurationSection("whisper.intents"), Talk.WHISPER, who, false, problems);
        talk = smallTalk(chat);
        Talk.Intent rude = chat.stream().filter(i -> i.id.equals("rude")).findFirst().orElse(null);
        swears = rude != null && !rude.patterns.isEmpty() ? rude.patterns : Sulk.patterns(Sulk.SWEARS);
        hurt = rude != null && !rude.lines.isEmpty() ? rude.lines : Sulk.HURT;
        if (rude == null) problems.add("chat.intents.rude 가 없어서 욕 · 삐짐은 코드의 기본 목록으로 읽어요");
        allow = Sulk.patterns(cfg.isList("sulk.allow") ? Talk.clean(cfg.getStringList("sulk.allow")) : Sulk.ALLOW);
        aim = Sulk.patterns(cfg.isList("sulk.aim") ? Talk.clean(cfg.getStringList("sulk.aim")) : Sulk.AIM);
        List<String> so = new ArrayList<>();
        for (String x : cfg.isList("sulk.sorry") ? Talk.clean(cfg.getStringList("sulk.sorry")) : Sulk.SORRY) {
            if (Talk.small(x)) so.add(x);
            else problems.add("sulk.sorry: \"" + x + "\" — \"?\" 를 쓰지 않아요 (빼고 읽음)");
        }
        sorry = List.copyOf(so);
        List<String> ti = new ArrayList<>();
        for (String x : cfg.isList("chat.tired") ? Talk.clean(cfg.getStringList("chat.tired")) : TIRED) {
            if (Talk.small(x)) ti.add(x);
            else problems.add("chat.tired: \"" + x + "\" — \"?\" 를 쓰지 않아요 (빼고 읽음)");
        }
        tired = List.copyOf(ti);
        List<String> f = new ArrayList<>();
        for (String s : cfg.isList("typo.fix") ? Talk.clean(cfg.getStringList("typo.fix")) : FIXES) {
            if (Talk.small(s)) f.add(s);
            else problems.add("typo.fix: \"" + s + "\" — \"?\" 를 쓰지 않아요 (빼고 읽음)");
        }
        fixes = f;
        strange = cfg.isList("whisper.strange.lines") ? Talk.clean(cfg.getStringList("whisper.strange.lines")) : Talk.STRANGE;
        timeWords = lines("visit.time-words", TIME_WORDS);
        if (timeWords.size() < 3) timeWords = TIME_WORDS;
        sleepy = small("ai.sleepy", SLEEPY, problems);
        goodnight = small("ai.goodnight", GOODNIGHT, problems);
        truth = readTruth();
        mind();
        restore.hook();
        List<String> l = new ArrayList<>();
        for (String s : cfg.getStringList("whisper.commands")) if (!s.isBlank()) l.add(s.strip().toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        labels = l.isEmpty() ? Whispers.VANILLA : List.copyOf(l);
        int[] h = range("visit.hours", 20, 2);
        from = Schedule.hour(h[0]);
        to = Schedule.hour(h[1]);
        seed = cfg.getLong("visit.seed", 13);
        stones.forget();
        for (String p : problems) eco.getLogger().warning("[비밀] newcomer.yml: " + p);
        if (cfg.contains("visit.questions")) {
            eco.getLogger().warning("[비밀] newcomer.yml: visit.questions 는 더 이상 읽지 않아요. 질문은 questions.<글자> 의 목록에서 밤마다 하나씩 골라요 (새 파일을 참고하세요).");
        }
        // what the five spell, whichever five a night takes: every pool is the acrostic's letter (checked above), in order
        List<String> firsts = new ArrayList<>();
        for (String letter : letters) {
            List<Questions.Entry> pool = pools.get(letter);
            if (pool != null && !pool.isEmpty()) firsts.add(pool.get(0).text());
        }
        if (!Acrostic.spells(firsts, word)) {
            eco.getLogger().warning("[비밀] newcomer.yml: 질문 첫 글자가 \"" + Acrostic.of(firsts) + "\" — acrostic \"" + word
                    + "\" 과(와) 다릅니다. 이대로면 플레이어가 풀 단서가 없어요 (questions 의 글자마다 질문을 넣으세요).");
        }
        if (!Whispers.account(who)) {
            eco.getLogger().warning("[비밀] newcomer.yml: name \"" + who + "\" 은(는) 마인크래프트 이름 꼴(영문 · 숫자 · _ 3~16자)이 아니에요. 진짜 접속자처럼 보이지 않습니다.");
        }
        try {
            if (Names.byNickname(who).isPresent()) {
                eco.getLogger().warning("[비밀] newcomer.yml: name \"" + who + "\" 은(는) 누군가의 닉네임이에요. 그 사람이 접속한 밤에는 이 비밀이 쉽니다. "
                        + "닉네임을 풀거나 (/관리자 닉네임 해제) 다른 이름으로 바꾸세요.");
            }
            if (Bukkit.getOfflinePlayerIfCached(who) != null) {
                eco.getLogger().warning("[비밀] newcomer.yml: name \"" + who + "\" 은(는) 이 서버에 실제로 들어온 적 있는 계정 이름이에요. "
                        + "그 사람이 접속한 밤에는 이 비밀이 쉽니다. 다른 이름으로 바꾸는 편이 좋아요.");
            }
        } catch (RuntimeException ignored) {
            // no server yet
        }
    }

    /** Lines said in public: none with a "?" (those are left out, with a warning). */
    private List<String> small(String path, List<String> def, List<String> problems) {
        List<String> out = new ArrayList<>();
        for (String x : cfg.isList(path) ? Talk.clean(cfg.getStringList(path)) : def) {
            if (Talk.small(x)) out.add(x);
            else problems.add(path + ": \"" + x + "\" — \"?\" 를 쓰지 않아요 (빼고 읽음)");
        }
        return out.isEmpty() ? def : List.copyOf(out);
    }

    /**
     * What was lost (truth.*). The day: the file's, else the one worked out once and kept (the earliest first join on
     * record and twelve days: the server's thirteenth day), else not known yet (then no shard of the day turns up).
     */
    private Record.Truth readTruth() {
        String name = cfg.getString("truth.name", Record.Truth.NAME);
        if (name == null || name.isBlank()) name = Record.Truth.NAME;
        List<String> alias = cfg.isList("truth.alias") ? Talk.clean(cfg.getStringList("truth.alias")) : Record.Truth.ALIAS;
        String what = cfg.getString("truth.what", Record.Truth.WHAT);
        if (what == null || what.isBlank()) what = Record.Truth.WHAT;
        String date = Record.Truth.date(cfg.getString("truth.date"));
        if (date.isEmpty()) {
            String configured = cfg.getString("truth.date");
            if (configured != null && !configured.isBlank()) {
                eco.getLogger().warning("[비밀] newcomer.yml: truth.date \"" + configured + "\" 은(는) 날짜 (yyyy-MM-dd) 가 아니에요. 가장 이른 첫 접속 날 + 12일로 정해요.");
            }
            date = Record.Truth.date(eco.kv().get(DATE));
        }
        if (date.isEmpty()) {
            try {
                long first = earliestJoin();
                if (first > 0) {
                    date = Record.Truth.dateFrom(first, SecretModule.KST);
                    eco.kv().set(DATE, date);
                    eco.getLogger().info("[비밀] 새로 온 사람: 그날을 " + date + " 로 정해 저장했습니다 (가장 이른 첫 접속 + 12일; 바꾸려면 newcomer.yml truth.date)");
                }
            } catch (RuntimeException e) {
                // no server, or nobody has ever joined: not known yet
            }
        }
        return new Record.Truth(name.strip(), alias, date, what.strip());
    }

    /** The earliest first join the server has on record (the core's profiles; else the world's player files). 0: nobody. */
    private long earliestJoin() {
        long best = 0;
        try {
            Long v = m.core().db().sync(c -> dev.stsv.core.data.Db.query(c, "SELECT MIN(first_seen) FROM profile", rs -> rs.getLong(1)).getFirst());
            if (v != null && v > 0) best = v;
        } catch (Exception e) {
            // no such table yet
        }
        if (best == 0) {
            for (org.bukkit.OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                long f = op.getFirstPlayed();
                if (f > 0 && (best == 0 || f < best)) best = f;
            }
        }
        return best;
    }

    /** Who writes his words (ai.*): the model when it is switched on and the server has a credential, else his scripts. */
    private void mind() {
        int[] cap = range("ai.max-per-night", 30, 40);
        Voice.Settings st = new Voice.Settings(cfg.getBoolean("ai.enabled", true), cfg.getDouble("ai.ratio-start", 0.5),
                cfg.getInt("ai.ratio-days", 2), cap[0], cap[1], seed, Math.max(0, cfg.getLong("ai.max-calls-per-day", 400)),
                1000L * Math.max(1, cfg.getInt("ai.timeout-seconds", 8)));
        NewcomerMind mind = new ScriptedMind();
        if (st.enabled()) {
            try {
                if (ClaudeMind.credential()) {
                    mind = new ClaudeMind(cfg.getString("ai.model", ClaudeMind.DEFAULT_MODEL), cfg.getLong("ai.max-tokens", 16000),
                            java.time.Duration.ofMillis(st.timeoutMs()), voice::error);
                    eco.getLogger().info("[비밀] 새로 온 사람: AI 켜짐 (모델 " + cfg.getString("ai.model", ClaudeMind.DEFAULT_MODEL) + ")");
                } else {
                    eco.getLogger().info("[비밀] 새로 온 사람: ai.enabled 이지만 서버 환경 변수 ANTHROPIC_API_KEY 가 없어서 대본으로만 말합니다");
                }
            } catch (RuntimeException | LinkageError e) {
                // the library is not there, or the client could not be made: his scripts will do
                eco.getLogger().warning("[비밀] 새로 온 사람: AI 를 켤 수 없어서 대본으로만 말합니다 (" + e.getClass().getSimpleName() + ")");
                mind = new ScriptedMind();
            }
        }
        voice.set(mind, st);
    }

    private List<String> lines(String path, List<String> def) {
        List<String> l = Talk.clean(cfg.getStringList(path));
        return l.isEmpty() ? def : l;
    }

    /** The intents he makes small talk with: all but rude (swearing at him is a sulk, not small talk). */
    private static List<Talk.Intent> smallTalk(List<Talk.Intent> all) {
        return all.stream().filter(i -> !i.id.equals("rude")).toList();
    }

    /** A [low, high] pair (a single number is both). */
    private int[] range(String path, int lo, int hi) {
        List<Integer> l = cfg.getIntegerList(path);
        if (l.size() >= 2) return new int[]{l.get(0), l.get(1)};
        if (cfg.isInt(path)) return new int[]{cfg.getInt(path), cfg.getInt(path)};
        return new int[]{lo, hi};
    }

    private double[] drange(String path, double lo, double hi) {
        List<Double> l = cfg.getDoubleList(path);
        if (l.size() >= 2) return new double[]{Math.min(l.get(0), l.get(1)), Math.max(l.get(0), l.get(1))};
        if (cfg.isDouble(path) || cfg.isInt(path)) return new double[]{cfg.getDouble(path), cfg.getDouble(path)};
        return new double[]{lo, hi};
    }

    private long between(String path, int lo, int hi) {
        int[] r = range(path, lo, hi);
        int a = Math.max(0, Math.min(r[0], r[1])), b = Math.max(a, Math.max(r[0], r[1]));
        return 1000L * (a + ThreadLocalRandom.current().nextInt(b - a + 1));
    }

    private long seconds(String path, int def) {
        return 1000L * Math.max(0, cfg.getInt(path, def));
    }

    /** How long he takes to type a line (typing.think-seconds, typing.chars-per-second). */
    private long typing(String line) {
        double[] think = drange("typing.think-seconds", 1.5, 4), cps = drange("typing.chars-per-second", 4, 7);
        return Typing.millis(line, ThreadLocalRandom.current(), think[0], think[1], cps[0], cps[1]);
    }

    String who() {
        return who;
    }

    SecretModule module() {
        return m;
    }

    org.bukkit.plugin.Plugin plugin() {
        return eco;
    }

    org.bukkit.configuration.file.YamlConfiguration cfg() {
        return cfg;
    }

    dev.stsv.economy.EcoPlugin eco() {
        return eco;
    }

    dev.stsv.economy.util.Kv kv() {
        return eco.kv();
    }

    boolean isOn() {
        return on();
    }

    /** Whether this player may still go on with it (on, and not closed; a tester always). */
    boolean openFor(UUID u) {
        return open(u);
    }

    boolean impostorNow() {
        return impostor();
    }

    /** Whether he is not to be reached by this player now (their 길라잡이, someone really named like him). */
    boolean busyWith(Player p) {
        return inGuide(p) || impostor();
    }

    Memories memories() {
        return memories;
    }

    Vigil vigil() {
        return vigil;
    }

    Voice voice() {
        return voice;
    }

    long seed() {
        return seed;
    }

    Record.Truth truth() {
        return truth;
    }

    /** The record on his stone: the server's (u null, or anyone not in test mode), or a tester's own. */
    Record record(UUID u) {
        return new Record(keep, u != null && m.test(u) ? "secret.newcomer." + u + ".stone." : K + "stone.", truth);
    }

    /** A player's name as he says it (their nickname when they have one). */
    String nameOf(Player p) {
        try {
            return Names.plain(p.getUniqueId());
        } catch (RuntimeException e) {
            return p.getName();
        }
    }

    /** The night that is open, or opens next, as a key. */
    String nightKey() {
        return thisNight().toString();
    }

    /** The night whose window is open now, if one is. */
    Optional<LocalDate> openNight() {
        return Schedule.night(nowKst(), from, to);
    }

    List<String> lastLines() {
        List<String> l = lines("vigil.lines", Vigil.LAST);
        return l.size() < 3 ? Vigil.LAST : l;
    }

    // ------------------------------------------------------------------------------------------
    // the follow-up 「다음 사람」 (NextSequel): what its visitors borrow from him
    // ------------------------------------------------------------------------------------------

    private final NextSequel sequel = new NextSequel(this);

    @Override
    public dev.stsv.economy.secret.Sequel sequel() {
        return sequel;
    }

    /** How someone of another name looks on screen (the same builders as his). */
    Look lookFor(String name) {
        return new Look(name, () -> m.core().getConfig().getString("profiles.chat-format"));
    }

    /** His small talk (all but rude), said by someone of another name. */
    List<Talk.Intent> smallTalkFor(String name) {
        return talk.stream().map(i -> i.named(name)).toList();
    }

    /** His whisper answers for someone of another name (without the stone: that was his alone). */
    List<Talk.Intent> whisperTalkFor(String name) {
        return whisperTalk.stream().filter(i -> !i.id.equals("stone")).map(i -> i.named(name)).toList();
    }

    List<Pattern> aim() {
        return aim;
    }

    List<String> hurtLines() {
        return hurt;
    }

    List<String> tiredLines() {
        return tired;
    }

    List<String> fixLines() {
        return fixes;
    }

    List<String> whisperLabels() {
        return labels;
    }

    long typingMillis(String line) {
        return typing(line);
    }

    /** The visiting window's hours [from, to). */
    int[] window() {
        return new int[]{from, to};
    }

    /**
     * Whether a name is somebody's: his own, an online player's account name or nickname; with {@code ever}, also the
     * name of an account that has ever joined and anyone's nickname (online or not). A visitor never takes such a name,
     * and says nothing while a real player of their name is online.
     */
    boolean somebody(String name, boolean ever) {
        if (name == null || name.equalsIgnoreCase(who)) return true;
        try {
            if (Bukkit.getPlayerExact(name) != null) return true;
            Optional<UUID> nick = Names.byNickname(name);
            if (nick.isPresent() && (ever || Bukkit.getPlayer(nick.get()) != null)) return true;
            return ever && Bukkit.getOfflinePlayerIfCached(name) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // the lines (how they look: Look)
    // ------------------------------------------------------------------------------------------

    Component joinLine(boolean join) {
        return look.joinLine(join);
    }

    /** A whisper from him (MiniMessage). */
    Component incoming(String mm) {
        return look.incoming(mm);
    }

    void toAll(Component c) {
        for (Player q : Bukkit.getOnlinePlayers()) q.sendMessage(c);
    }

    private static void remember(Deque<String> d, String line, int keep) {
        d.addLast(line);
        while (d.size() > Math.max(1, keep)) d.removeFirst();
    }

    private void say(Visit v, String words) {
        remember(recent, words, cfg.getInt("chat.recent", 8));
        v.saidAt = System.currentTimeMillis();
        Component line = look.chat(words);
        if (v.viewer != null) {
            Player p = Bukkit.getPlayer(v.viewer);
            if (p != null) p.sendMessage(line);
            return;
        }
        toAll(line);
        eco.getLogger().info("[비밀] 새로 온 사람 (" + who + ", 가짜 채팅): " + words);
    }

    // ------------------------------------------------------------------------------------------
    // tonight's questions
    // ------------------------------------------------------------------------------------------

    private int count() {
        return letters.size();
    }

    /** The five of a night (one per letter, chosen from its date). */
    List<Questions.Entry> tonight(LocalDate night) {
        return Questions.tonight(night, seed, letters, pools);
    }

    private LocalDate thisNight() {
        return Schedule.next(LocalDateTime.now(SecretModule.KST), from, to);
    }

    private boolean raining() {
        try {
            List<World> w = Bukkit.getWorlds();
            return !w.isEmpty() && w.get(0).hasStorm();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A question as he says it now: the rain words while it rains, {online} and {time} filled in. */
    private String words(Questions.Entry e) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("online", String.valueOf(Math.max(1, Bukkit.getOnlinePlayers().size())));
        vars.put("time", Questions.time(ZonedDateTime.now(SecretModule.KST).getHour(), timeWords.get(0), timeWords.get(1), timeWords.get(2)));
        return Questions.fill(e.words(raining()), vars);
    }

    // ------------------------------------------------------------------------------------------
    // his visit
    // ------------------------------------------------------------------------------------------

    /** A line he is typing: it appears at {@code at}. */
    private record Pending(String words, long at, Voice.Thought thought) {

        Pending(String words, long at) {
            this(words, at, null);
        }

        /** The words when they are due: the model's if it wrote some in time and they may be said, else the scripted ones. */
        String said() {
            return thought == null ? words : thought.or(words);
        }
    }

    /** One visit: the join line was said; the questions follow one by one, and he is around for a while after. */
    private static final class Visit {
        /** the only one who sees it, or null for everyone */
        final UUID viewer;
        /** the night it is the visit of (kept in kv as it goes), or null when only one player sees it */
        final String night;
        /** short waits (test mode, scenes) */
        final boolean quick;
        /** that night's five */
        final List<Questions.Entry> qs;
        int next;
        /** next question at; last question at; last line of his at; quiet from (0 while questions are left) */
        long nextAt, askedAt, saidAt, endAt;
        /** lines he is typing */
        final Deque<Pending> pending = new ArrayDeque<>();
        /** small talk: how much, when last and to whom, which question was thanked for, quiet until */
        int reactions, thanked = -1;
        /** his tired line was dealt with (said, or not to be said) */
        boolean tired;
        long lastReactAt, hushUntil;
        UUID lastBy;

        Visit(UUID viewer, String night, boolean quick, List<Questions.Entry> qs, int next) {
            this.viewer = viewer;
            this.night = night;
            this.quick = quick;
            this.qs = qs;
            this.next = next;
        }

        /** He is around (asking, or not yet gone quiet after the last question). */
        boolean around(long now) {
            return next < qs.size() || now < endAt || !pending.isEmpty();
        }

        long lastPending(long now) {
            return pending.isEmpty() ? now : pending.peekLast().at();
        }
    }

    private long gap(Visit v, boolean first) {
        if (v.quick) return 1000L * Math.max(1, cfg.getInt("visit.test-gap-seconds", 5));
        return first ? between("visit.first-gap-seconds", 40, 90) : between("visit.gap-seconds", 70, 160);
    }

    /** Goes on with a visit; false when it is over (or cannot go on now). */
    private boolean step(Visit v, long now) {
        while (!v.pending.isEmpty() && now >= v.pending.peekFirst().at()) say(v, v.pending.removeFirst().said());
        if (v.next >= v.qs.size()) {
            // that night's answers are used up and nothing is left to ask: good night (public chat is over; no leave line)
            if (!v.tired && v.viewer == null && v.night != null && v.pending.isEmpty() && voice.active()
                    && voice.mood(LocalDate.parse(v.night), 0) == Awakening.Mood.GOODNIGHT) {
                goodnight(v, now);
                return true;
            }
            if (!v.tired && v.endAt > 0 && now >= v.endAt && v.pending.isEmpty()) tired(v, now);
            return v.around(now);
        }
        if (now < v.nextAt || !v.pending.isEmpty()) return true;
        // nobody to ask: what is left waits in kv, and he goes on when someone is here (the same night only; no second join line)
        if (v.viewer == null && Bukkit.getOnlinePlayers().size() < Math.max(1, cfg.getInt("visit.min-online", 1))) return false;
        ask(v, now);
        if (v.next == Math.min(2, v.qs.size())) hanyoung(v, now);
        if (v.night != null && v.night.equals(eco.kv().get(NIGHT))) eco.kv().set(ASKED, v.next);
        if (v.next >= v.qs.size()) v.endAt = now + (v.quick ? seconds("chat.test-linger-seconds", 120) : seconds("chat.linger-seconds", 1800));
        else v.nextAt = now + gap(v, false) + typing(v.qs.get(v.next).text());
        return true;
    }

    /**
     * The small-talk time after the fifth question is over: one tired line in public, his last of the night (nothing is
     * answered after it, and no leave line follows). Not on a sulky night, not with no real player online, once a night
     * (kv tired = the night; tiredline = the line, not said again the next night).
     */
    private void tired(Visit v, long now) {
        v.tired = true;
        if (tired.isEmpty() || sulking(v.viewer)) return;
        if (v.viewer != null ? Bukkit.getPlayer(v.viewer) == null : Bukkit.getOnlinePlayers().isEmpty()) return;
        if (v.night != null && v.night.equals(eco.kv().get(TIRED_NIGHT))) return;
        List<String> avoid = new ArrayList<>(recent);
        String last = eco.kv().get(TIRED_LINE);
        if (last != null) avoid.add(last);
        String line = Talk.pick(tired, avoid, ThreadLocalRandom.current());
        if (v.night != null) {
            eco.kv().set(TIRED_NIGHT, v.night);
            eco.kv().set(TIRED_LINE, line);
        }
        v.pending.addLast(new Pending(line, now + typing(line)));
    }

    /** His good night: the last public line of the night (whispers are still heard), said once. */
    private void goodnight(Visit v, long now) {
        v.tired = true;
        voice.sleep(v.night);
        eco.kv().set(TIRED_NIGHT, v.night);
        if (sulking(null) || Bukkit.getOnlinePlayers().isEmpty()) return;
        String line = Talk.pick(goodnight, recent, ThreadLocalRandom.current());
        v.pending.addLast(new Pending(line, now + typing(line)));
    }

    /**
     * Once a night, after his second question, from the night someone first found his stone: one Latin letter in the
     * middle of a Korean line, put right at once. Night after night the letters spell his name.
     */
    private void hanyoung(Visit v, long now) {
        boolean own = v.viewer != null;
        if (!Clues.forAll(record(v.viewer).found()) || sulking(v.viewer)) return;
        if (!own && (v.night == null || v.night.equals(eco.kv().get(TYPO_NIGHT)))) return;
        long nth = eco.kv().getLong(TYPO_N, 0);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<String> pool = small("hanyoung.lines", Hanyoung.LINES, new ArrayList<>());
        String line = Talk.pick(pool, recent, r);
        String typed = Hanyoung.slip(line, Hanyoung.letter(truth.name(), nth), r);
        String fix = Talk.pick(small("hanyoung.fix", Hanyoung.FIXES, new ArrayList<>()), recent, r);
        long at = now + (v.quick ? 2500L : 12_000L + r.nextInt(14_000)) + typing(typed);
        queue(v, typed, at);
        queue(v, fix, at + 900 + Typing.millis(fix, r, 0.3, 1.0, 4, 7));
        if (v.next < v.qs.size()) v.nextAt = Math.max(v.nextAt, v.lastPending(now) + (v.quick ? 2000L : 8000L));
        if (!own) {
            eco.kv().set(TYPO_NIGHT, v.night);
            eco.kv().set(TYPO_N, nth + 1);
        }
    }

    /** One question, now and then with a slip in it that he puts right a moment later. */
    private void ask(Visit v, long now) {
        String q = words(v.qs.get(v.next));
        Optional<Typing.Slip> slip = ThreadLocalRandom.current().nextDouble() < cfg.getDouble("typo.chance", 0.08) && !fixes.isEmpty()
                ? Typing.slip(q, ThreadLocalRandom.current()) : Optional.empty();
        if (slip.isPresent()) {
            say(v, slip.get().typed());
            String fix = Talk.pick(fixes, recent, ThreadLocalRandom.current()).replace("{word}", slip.get().word());
            v.pending.addLast(new Pending(fix, now + 600 + Typing.millis(fix, ThreadLocalRandom.current(), 0.3, 1.2, 4, 7)));
        } else {
            say(v, q);
        }
        v.next++;
        v.askedAt = now;
    }

    /**
     * His visit to everyone begins, or goes on. night: the night it is the visit of, from: the question to go on from.
     * The join line is said once a night ({@link #JOINED}): whatever brings him back the same night (a restart, everyone
     * having left, the secret switched off and on, an admin's 시작), he only goes on asking, since he never left.
     * Returns whether the join line was said.
     */
    private boolean arrive(String night, int from, String why) {
        long now = System.currentTimeMillis();
        // everything is back on his stone: he asks nothing any more, he says where he will be and stands there (Vigil)
        boolean waiting = record(null).waiting(night);
        Visit v = new Visit(null, night, false, waiting ? List.of() : tonight(LocalDate.parse(night)), waiting ? 0 : Math.max(0, from));
        v.nextAt = now + gap(v, true) + (v.next < v.qs.size() ? typing(v.qs.get(v.next).text()) : 0);
        boolean join = !night.equals(eco.kv().get(JOINED));
        eco.kv().set(NIGHT, night);
        eco.kv().set(ASKED, waiting ? count() : v.next);
        if (waiting) v.endAt = now + seconds("chat.linger-seconds", 1800);
        eco.kv().set(SEEN, 1);
        if (join) {
            eco.kv().set(JOINED, night);
            toAll(joinLine(true));
            // the night after he sulked, now and then a sheepish word before his first question
            if (!sorry.isEmpty() && v.next < v.qs.size() && Sulk.dayAfter(eco.kv().get(SULK), night)
                    && ThreadLocalRandom.current().nextDouble() < cfg.getDouble("sulk.sorry-chance", 0.3)) {
                String line = Talk.pick(sorry, recent, ThreadLocalRandom.current());
                long at = now + gap(v, true) / 2 + typing(line);
                v.pending.addLast(new Pending(line, at));
                v.nextAt = Math.max(v.nextAt, at + 8000L + typing(v.qs.get(v.next).text()));
            }
        }
        owed(v, now, night, waiting && join);
        eco.getLogger().info("[비밀] 새로 온 사람 (" + who + ") " + (join ? "이 들어온 것처럼 보였습니다 (가짜 입장 줄"
                : "이 질문을 이어 갑니다 (입장 줄은 이 밤에 이미 떴으므로 없음") + " · 접속 " + Bukkit.getOnlinePlayers().size() + "명 · " + why
                + " · " + night + " 밤, 질문 " + v.next + "/" + count() + "부터)");
        visit = v;
        return join;
    }

    /**
     * What he owes the chat is said first, before his next question: that someone found his stone (the night after), what
     * came back while he was not around, and — once all is back — where he will be.
     */
    private void owed(Visit v, long now, String night, boolean allBack) {
        List<String> lines = new ArrayList<>();
        String told = eco.kv().get(TOLD);
        if (told != null && !told.equals("done") && told.compareTo(night) < 0) {
            lines.add(FOUND_NEXT);
            eco.kv().set(TOLD, "done");
        }
        String owed = eco.kv().get(SAY);
        if (owed != null) for (String l : owed.split("\n")) if (!l.isBlank() && lines.size() < 3) lines.add(l);
        eco.kv().remove(SAY);
        if (allBack) lines.add(cfg.getString("vigil.arrive", ALL_BACK));
        long at = Math.max(v.lastPending(now), now + (v.quick ? 1000L : gap(v, true) / 3));
        for (String l : lines) {
            if (!Talk.small(l)) continue;
            at += typing(l) + 2500L;
            v.pending.addLast(new Pending(l, at));
        }
        if (!lines.isEmpty() && v.next < v.qs.size()) v.nextAt = Math.max(v.nextAt, at + 8000L + typing(v.qs.get(v.next).text()));
    }

    /**
     * A line he says in public because of something that happened at his stone: now when he is around (and awake), else
     * the next time he comes. For a tester it is said to them alone.
     */
    void sayLater(Player to, String line) {
        if (line == null || !Talk.small(line)) return;
        if (to != null && m.test(to.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (to.isOnline()) to.sendMessage(look.chat(line));
            }, Math.max(1, typing(line) / 50));
            return;
        }
        Visit v = visit;
        long now = System.currentTimeMillis();
        if (v != null && v.viewer == null && v.around(now) && !v.tired && !sulking(null)) {
            queue(v, line, v.lastPending(now) + typing(line) + 1500L);
            return;
        }
        String owed = eco.kv().get(SAY);
        eco.kv().set(SAY, owed == null || owed.isBlank() ? line : owed + "\n" + line);
    }

    /**
     * Something came back to the stone (a shard's glyphs of a line, or — line null — his name): everyone sees it there, he
     * says so, a line that is whole now gets its own word, and with all three back the night is noted (he waits there from
     * the next).
     */
    void restored(Player by, Shards.Line line, Record r) {
        boolean test = m.test(by.getUniqueId());
        if (test) by.sendMessage(Text.msg("<yellow>[테스트] 비석 (나에게만):<newline>" + r.text()));
        else m.redraw();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String player = nameOf(by);
        if (line == null) sayLater(by, cfg.getString("react.name", WHOLE_NAME).replace("{player}", player));
        else if (r.whole(line)) sayLater(by, line == Shards.Line.DATE ? cfg.getString("react.date", WHOLE_DATE) : cfg.getString("react.what", WHOLE_WHAT));
        else sayLater(by, line == Shards.Line.DATE && rnd.nextInt(3) == 0 ? BACK_DATE : Talk.pick(small("react.back", BACK, new ArrayList<>()), recent, rnd));
        String twoKey = test ? null : TWO;
        if (r.lines() == 2 && (twoKey == null ? m.get(this, by.getUniqueId(), "two") == null : !eco.kv().has(twoKey))) {
            if (twoKey == null) m.set(this, by.getUniqueId(), "two", 1);
            else eco.kv().set(twoKey, 1);
            sayLater(by, cfg.getString("react.two", TWO_LINES));
        }
        if (r.restored() && r.wholeNight() == null) {
            LocalDateTime t = nowKst();
            // the night it came back in: the open one, else the one before the next to open (so "the next night" is the coming one)
            r.wholeNight(Schedule.night(t, from, to).orElse(Schedule.next(t, from, to).minusDays(1)).toString());
            eco.getLogger().info("[비밀] 새로 온 사람: 비석의 세 줄이 모두 돌아왔습니다" + (test ? " (테스트: " + by.getName() + " 에게만)" : " — 다음 밤부터 비석 앞에 서 있음"));
        }
    }

    /** He joins for one player alone (test mode, a scene): short waits, tonight's five, nobody else sees a thing. */
    private void arriveFor(Player p, boolean joinLine) {
        long now = System.currentTimeMillis();
        Visit v = new Visit(p.getUniqueId(), null, true, tonight(thisNight()), 0);
        v.nextAt = now + (joinLine ? gap(v, true) : 1000L);
        if (joinLine) p.sendMessage(joinLine(true));
        alone.put(p.getUniqueId(), v);
    }

    /** Someone really named like him (account or nickname) is online: he does not exist while they do. */
    private boolean impostor() {
        if (Bukkit.getPlayerExact(who) != null) return true;
        try {
            Optional<UUID> nick = Names.byNickname(who);
            return nick.isPresent() && Bukkit.getPlayer(nick.get()) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The whole night is given up (this one, or the one about to open): no visit, and one line for the owner. */
    private void standDown() {
        visit = null;
        String key = thisNight().toString();
        if (key.equals(eco.kv().get(NIGHT)) && eco.kv().getLong(ASKED, 0) >= count()) return;
        eco.kv().set(NIGHT, key);
        eco.kv().set(ASKED, count());
        eco.getLogger().warning("[비밀] 새로 온 사람: 이름이 같은 실제 플레이어(" + who + ")가 접속해 있어서 " + key + " 밤은 쉽니다. "
                + "계속 겹치면 secrets/newcomer.yml 의 name 을 바꾸세요.");
    }

    /**
     * Whether a visit is due, and if so, it begins or goes on: tonight's at its minute (or as soon after as someone is
     * here, inside the window), and one that was begun and broken off (also an admin's, called before the window opened)
     * whenever it can go on, until its night is over.
     */
    private void plan(long now) {
        LocalDateTime t = ZonedDateTime.now(SecretModule.KST).toLocalDateTime();
        long asked = eco.kv().getLong(ASKED, 0);
        Schedule.Due due = Schedule.due(t, from, to, seed, eco.kv().get(NIGHT), asked, count());
        if (due == Schedule.Due.NOTHING) return;
        if (Bukkit.getOnlinePlayers().size() < Math.max(1, cfg.getInt("visit.min-online", 1))) return;
        if (now - lastJoin < seconds("visit.after-join-seconds", 45)) return;   // not on a real player's heels
        String key = Schedule.next(t, from, to).toString();   // the open night, or the next to open
        if (due == Schedule.Due.GO_ON) arrive(key, (int) Math.max(0, asked), "이어서");
        else arrive(key, 0, "예정된 방문");
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        // it stood still (switched off for a while): he is not picked up mid-sentence. What is left of tonight waits in kv,
        // and he goes on with it (after a first gap, without another join line)
        if (lastTick != 0 && now - lastTick > STALE) {
            visit = null;
            alone.clear();
        }
        lastTick = now;
        for (Iterator<Map.Entry<UUID, Visit>> it = alone.entrySet().iterator(); it.hasNext(); ) {
            Visit v = it.next().getValue();
            if (Bukkit.getPlayer(v.viewer) == null || !step(v, now)) it.remove();
        }
        boolean real = impostor();
        restore.hook();
        restore.tick();
        vigil.tick();
        if (m.closed(this)) {
            visit = null;
            return;
        }
        if (real) {
            standDown();
            return;
        }
        Visit v = visit;
        if (v != null) {
            if (!step(v, now)) visit = null;
            return;
        }
        plan(now);
    }

    // ------------------------------------------------------------------------------------------
    // small talk: what real players say while he is around
    // ------------------------------------------------------------------------------------------

    private boolean inGuide(Player p) {
        return eco.guide() != null && eco.guide().inGuide(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        lastJoin = System.currentTimeMillis();
        Visit v = visit;
        if (v == null || !on() || !v.around(lastJoin) || sulking(null)) return;
        if (ThreadLocalRandom.current().nextDouble() >= cfg.getDouble("chat.join-chance", 0.3)) return;
        Optional<Talk.Intent> greet = chat.stream().filter(i -> i.id.equals("greet")).findFirst();
        if (greet.isEmpty() || greet.get().lines.isEmpty()) return;
        UUID u = e.getPlayer().getUniqueId();
        if (!free(v, u, false, lastJoin)) return;
        String line = Talk.pick(greet.get().lines, recent, ThreadLocalRandom.current());
        long at = lastJoin + between("chat.join-delay-seconds", 4, 10) + typing(line);
        queue(v, line, at);
        reacted(v, u, lastJoin);
    }

    /**
     * Real chat, read where it is said (off the main thread): only the words go on, to the main thread. NORMAL: after the
     * menus that take a chat line as typed input (and cancel it) and before anything that redraws the chat.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (visit == null && alone.isEmpty()) return;
        UUID u = e.getPlayer().getUniqueId();
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(eco, () -> heard(u, text));
    }

    /**
     * The limits on small talk: not while he is typing or keeping quiet, not more than chat.max-per-night a visit, at least
     * chat.min-gap-seconds apart, and not the same player twice in a row inside chat.same-player-seconds unless they named him.
     */
    private boolean free(Visit v, UUID by, boolean named, long now) {
        if (now < v.hushUntil || !v.pending.isEmpty()) return false;
        if (!v.quick && v.viewer == null && v.night != null && voice.active()) {
            // awake: the night has that night's number of answers; at the end he holds on (questions left) or says good night
            Awakening.Mood mood = voice.mood(LocalDate.parse(v.night), v.qs.size() - v.next);
            if (mood != Awakening.Mood.AWAKE && mood != Awakening.Mood.SLEEPY) return false;
        } else if (!v.quick && v.reactions >= cfg.getInt("chat.max-per-night", 6)) return false;
        long gap = v.quick ? seconds("chat.test-min-gap-seconds", 5) : seconds("chat.min-gap-seconds", 45);
        if (v.lastReactAt != 0 && now - v.lastReactAt < gap) return false;
        return named || !by.equals(v.lastBy) || now - v.lastReactAt >= seconds("chat.same-player-seconds", 120);
    }

    private void reacted(Visit v, UUID by, long now) {
        v.reactions++;
        if (v.viewer == null && v.night != null && voice.active()) voice.answered(v.night);
        v.lastReactAt = now;
        v.lastBy = by;
        // his next question does not come in the same breath
        if (v.next < v.qs.size()) v.nextAt = Math.max(v.nextAt, v.lastPending(now) + (v.quick ? 2000L : 8000L));
    }

    private void queue(Visit v, String line, long at) {
        v.pending.addLast(new Pending(line, Math.max(at, v.lastPending(at))));
    }

    /** What he would say to a line of chat (null: nothing), and to which intent; nothing is sent or counted. */
    private record Reaction(Talk.Intent intent, String line, String follow) {}

    private Reaction react(String text, boolean addressed, boolean afterQuestion) {
        Optional<Talk.Intent> found = Talk.match(talk, text, addressed, afterQuestion);
        if (found.isEmpty()) return null;
        Talk.Intent i = found.get();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean slip = !i.slip.isEmpty() && r.nextDouble() < i.slipChance;
        String line = Talk.pick(slip ? i.slip : i.lines, recent, r);
        String follow = !i.follow.isEmpty() && r.nextDouble() < i.followChance ? Talk.pick(i.follow, recent, r) : null;
        return new Reaction(i, line, follow);
    }

    private void heard(UUID by, String text) {
        if (!on() || text == null || text.isBlank()) return;
        Visit v = alone.get(by);
        if (v == null) v = visit;
        long now = System.currentTimeMillis();
        if (v == null || !v.around(now) || (v.viewer != null && !v.viewer.equals(by))) return;
        Player p = Bukkit.getPlayer(by);
        if (p == null || inGuide(p) || impostor()) return;
        if (v.tired || sulking(v.viewer)) return;   // his last line of the night was said (or is being typed), or gone for the night
        overheard.addLast(new NewcomerMind.Heard(nameOf(p), text));
        while (overheard.size() > 6) overheard.removeFirst();
        if (insult(text) && (Sulk.aimed(text, who, aim) || v.saidAt > 0 && now - v.saidAt <= seconds("sulk.window-seconds", 40))) {
            sulk(p, v, false);
            return;
        }
        boolean named = Talk.mentions(text, who);
        long window = seconds("chat.answer-window-seconds", 40);
        boolean afterQuestion = v.next > 0 && v.thanked != v.next && now - v.askedAt <= window;
        if (!free(v, by, named, now)) return;
        boolean addressed = named || v.saidAt > 0 && now - v.saidAt <= window;
        Reaction re = react(text, addressed, afterQuestion);
        if (re == null) return;
        if (re.intent().answer) v.thanked = v.next;   // one go at thanking for each question
        double chance = re.intent().chance;
        if (!(v.viewer != null ? m.roll(v.viewer, chance) : ThreadLocalRandom.current().nextDouble() < chance)) return;
        if (re.line() == null) return;
        String id = re.intent().id;
        if (id.equals("answer")) memories.update(by, nameOf(p), Memories.Note::plusAnswer);
        else if (id.equals("bot")) memories.update(by, nameOf(p), Memories.Note::plusTease);
        else if (named) memories.update(by, nameOf(p), n -> n);   // spoke to him: he knows them
        String line = re.line();
        boolean real = v.viewer == null && v.night != null;
        if (voice.active() && real && voice.mood(LocalDate.parse(v.night), v.qs.size() - v.next) == Awakening.Mood.SLEEPY
                && ThreadLocalRandom.current().nextBoolean()) line = Talk.pick(sleepy, recent, ThreadLocalRandom.current());
        long at = now + typing(line);
        Voice.Thought thought = voice.none();
        if (voice.active()) {
            long think = between("ai.think-seconds", 2, 5);
            thought = chatWords(by, line, typing(line) + think, v.viewer != null || m.test(by));
            if (thought.asked()) at += think;
        }
        v.pending.addLast(new Pending(line, Math.max(at, v.lastPending(at)), thought.asked() ? thought : null));
        if (re.follow() != null && !thought.asked()) queue(v, re.follow(), at + 1500 + typing(re.follow()));
        if (re.intent().hush > 0) v.hushUntil = at + 1000L * (v.quick ? Math.min(30, re.intent().hush) : re.intent().hush);
        reacted(v, by, now);
    }

    // ------------------------------------------------------------------------------------------
    // whispering to him
    // ------------------------------------------------------------------------------------------

    private boolean seen(UUID u) {
        return eco.kv().has(SEEN) || m.get(this, u, "seen") != null;
    }

    /** Whether a whisper from this player reaches him (else the game answers: there is no such player). */
    private boolean hears(Player p) {
        UUID u = p.getUniqueId();
        if (!open(u) || !seen(u) || impostor()) return false;
        return !inGuide(p);
    }

    private Stones.Where where(Player p) {
        Optional<Stones.Found> f = stones.find(id(), m.all());
        if (f.isEmpty() || !f.get().world().equals(p.getWorld())) return Stones.Where.NONE;
        Location l = p.getLocation();
        return Stones.standing(f.get().spots(), l.getX(), l.getY(), l.getZ(), cfg.getDouble("stone.range", 3.5), id(),
                cfg.getDouble("stone.slack", 0.4));
    }

    /** His stone, when the monument stands (in a loaded world). */
    Optional<Location> stone() {
        return stones.find(id(), m.all()).flatMap(f -> f.of(id()).map(s -> new Location(f.world(), s.x(), s.y(), s.z())));
    }

    /** The monument's own spot (the stones face it), when it stands. */
    Optional<Location> centre() {
        return stones.find(id(), m.all()).map(f -> new Location(f.world(), f.place().x(), f.place().y(), f.place().z()));
    }

    /**
     * His answer to a whisper (null: he says nothing), by what it is about. nights: on how many nights this player has
     * whispered to him; from whisper.strange.after-nights on, a few answers are quietly stranger (never about the stone).
     */
    private String whisperReply(String message, long nights, StringBuilder why) {
        Optional<Talk.Intent> found = Talk.match(whisperTalk, message, true, false);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (found.isEmpty()) {
            why.append("맞는 의도 없음 → 말 없음");
            return null;
        }
        Talk.Intent i = found.get();
        why.append(i.id);
        if (r.nextDouble() >= i.chance) {
            why.append(" (이번엔 말 없음)");
            return null;
        }
        boolean stoneTalk = i.id.equals("stone");
        if (!stoneTalk && !strange.isEmpty() && nights >= cfg.getInt("whisper.strange.after-nights", 3)
                && r.nextDouble() < cfg.getDouble("whisper.strange.chance", 0.15)) {
            why.append(" → 이상한 말 (").append(nights).append("번째 밤)");
            return Talk.pick(strange, recentWhispers, r);
        }
        return Talk.pick(i.lines, recentWhispers, r);
    }

    /** Counts the nights a player whispered on; returns how many. */
    private long whisperNight(UUID u) {
        String key = thisNight().toString();
        long n = m.getLong(this, u, "wnights", 0);
        if (!key.equals(m.get(this, u, "wnight"))) {
            m.set(this, u, "wnight", key);
            m.set(this, u, "wnights", ++n);
        }
        return n;
    }

    /** LOWEST: the whisper to him is taken before anything else (such as a plugin of whisper commands) reads it. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWhisper(PlayerCommandPreprocessEvent e) {
        Optional<Whispers.Whisper> w = Whispers.parse(e.getMessage(), labels);
        if (w.isEmpty() || !Whispers.to(w.get(), who)) return;
        Player p = e.getPlayer();
        if (!hears(p)) return;
        e.setCancelled(true);
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        p.sendMessage(look.outgoing(w.get().message()));
        boolean fresh = now - counted.getOrDefault(u, 0L) >= 3000L;
        if (fresh) {   // a flood of whispers is one whisper
            counted.put(u, now);
            m.set(this, u, "whispers", m.getLong(this, u, "whispers", 0) + 1);
        }
        long nights = whisperNight(u);
        if (m.stage(this, u) < 1) m.stage(this, u, 1);
        memories.update(u, nameOf(p), fresh ? Memories.Note::plusWhisper : n -> n);
        boolean test = m.test(u);
        boolean sulky = sulking(test ? u : null);
        if (insult(w.get().message())) {                 // sworn at, even at his stone: he sulks (once a night)
            if (!sulky) sulk(p, null, true);
            return;
        }
        Stones.Where at = where(p);
        if (at == Stones.Where.HIS) {                    // his stone is found on a sulky night too
            atStone(p);
            return;
        }
        if (sulky) return;                               // gone for the night: no answer, as from someone away
        if (now - answered.getOrDefault(u, 0L) < seconds("whisper.cooldown-seconds", 20)) return;
        answered.put(u, now);
        if (at != Stones.Where.OTHER && shardTalk(p)) return;
        if (at != Stones.Where.OTHER && Clues.forAll(record(u).found()) && nameAsked(w.get().message())) {
            // he cannot say it: what gets through is broken
            whisperLater(p, cfg.getString("whisper.no-name", NO_NAME), null, 0);
            whisperLater(p, Shards.garbled(truth.name()), null, typing(NO_NAME) + 2500L);
            return;
        }
        StringBuilder why = new StringBuilder();
        String line = at == Stones.Where.OTHER ? cfg.getString("whisper.wrong-stone", "…그건 제 게 아니에요.")
                : whisperReply(w.get().message(), nights, why);
        if (line == null || line.isBlank()) return;
        remember(recentWhispers, line, 4);
        // about the stone he never says more than his script (he never says yes); the rest the model may put in his words
        boolean scriptOnly = at == Stones.Where.OTHER || why.toString().startsWith("stone");
        Voice.Thought thought = scriptOnly ? null : whisperWords(p, NewcomerMind.Kind.WHISPER, line, w.get().message(), List.of(), typing(line) + 3000L);
        whisperLater(p, line, thought, thought != null && thought.asked() ? 3000L : 0);
    }

    private boolean nameAsked(String message) {
        try {
            return Pattern.compile(cfg.getString("whisper.name-ask", NAME_ASK)).matcher(message).find();
        } catch (java.util.regex.PatternSyntaxException e) {
            return Pattern.compile(NAME_ASK).matcher(message).find();
        }
    }

    /** One whisper from him after the time it takes to type it (plus extra ms); the model's words when there are some. */
    private void whisperLater(Player p, String line, Voice.Thought thought, long extra) {
        UUID u = p.getUniqueId();
        long wait = Math.max(1, (typing(line) + extra) / 50);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!p.isOnline() || !open(u) || impostor()) return;
            p.sendMessage(incoming(thought == null ? line : thought.or(line)));
        }, wait);
    }

    /**
     * Whispered to him at his stone. The first time he says that it was his and that the pieces must still be somewhere;
     * from then on the broken traces show themselves to this player, and (the first time anyone does it) the stone shows
     * its four broken lines to everyone and he begins to slip on the 한/영 key. It does not end here any more.
     */
    private void atStone(Player p) {
        UUID u = p.getUniqueId();
        boolean test = m.test(u);
        boolean first = m.get(this, u, "atstone") == null;
        m.set(this, u, "atstone", 1);
        if (m.stage(this, u) < 2) m.stage(this, u, 2);
        Record r = record(u);
        if (r.find(u, System.currentTimeMillis())) {
            if (test) {
                p.sendMessage(Text.msg("<yellow>[테스트] 비석 (나에게만):<newline>" + r.text()));
            } else {
                eco.kv().set(TOLD, nightKey());   // said in public the night after
                m.redraw();
                eco.getLogger().info("[비밀] 새로 온 사람: " + p.getName() + " 이(가) 처음으로 그의 비석 앞에서 귓속말했습니다 (비석에 깨진 글씨 · 한영키 오타 시작)");
            }
        }
        long now = System.currentTimeMillis();
        if (first) {
            answered.put(u, now + 12_000L);
            memories.update(u, nameOf(p), n -> n.note("비석을 찾아 줌"));
            long at = 0;
            for (String line : lines("stone.found-lines", FOUND)) {
                whisperLater(p, line, null, at);
                at += typing(line) + 3500L;
            }
            return;
        }
        if (now - answered.getOrDefault(u, 0L) < seconds("whisper.cooldown-seconds", 20)) return;
        answered.put(u, now);
        if (shardTalk(p)) return;
        String line = Talk.pick(lines("stone.again-lines", AGAIN), recentWhispers, ThreadLocalRandom.current());
        remember(recentWhispers, line, 4);
        whisperLater(p, line, null, 0);
    }

    /**
     * Whispered to while holding a shard: he reads it. What it is a piece of, what it lacks and whether the stone still
     * wants it is the code's knowledge; the model only puts it in his words. False when no shard is held.
     */
    private boolean shardTalk(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        Optional<Shards.Shard> s = Restore.read(hand);
        if (s.isEmpty()) return false;
        Record r = record(p.getUniqueId());
        boolean date = s.get().line() == Shards.Line.DATE;
        boolean useful = Shards.fill(r.have(s.get().line()), s.get().kind()) > 0;
        String lacks = Shards.lacks(s.get().kind());
        String place = Restore.look(hand).map(l -> switch (l) {
            case TICKET -> "버스에 두고 내린 것 같아요";
            case RECEIPT -> "계산대에서 나온 것 같아요";
            case LETTER -> "돌아온 편지네요";
            case BOTTLE -> "물에 오래 있었네요";
        }).orElse("");
        String line = "…이건 " + (date ? "날짜" : "제가 그날 한 일") + " 같아요. " + lacks + "이 없네요." + (place.isEmpty() ? "" : " " + place + ".")
                + (useful ? "" : " …그 자리는 이미 돌아왔어요.");
        List<String> facts = new ArrayList<>();
        facts.add("상대가 든 조각은 " + (date ? "어떤 날짜의 일부다" : "그가 그날 한 일을 적은 글의 일부다"));
        facts.add("그 조각에는 " + lacks + "이 없다");
        if (!place.isEmpty()) facts.add("겉모습으로 보아: " + place);
        facts.add(useful ? "비석에는 아직 이 조각이 채울 빈 자리가 있다" : "이 조각이 채울 자리는 비석에 이미 돌아와 있다");
        Voice.Thought thought = whisperWords(p, NewcomerMind.Kind.SHARD, line, "", facts, typing(line) + 3000L);
        whisperLater(p, line, thought, thought.asked() ? 3000L : 0);
        return true;
    }

    // ---- his words from the mind (Voice): what the code knows and may tell it

    private List<String> facts(UUID u) {
        Record r = record(u);
        List<String> f = new ArrayList<>();
        f.add("지금은 " + Questions.time(ZonedDateTime.now(SecretModule.KST).getHour(), "새벽", "밤", "낮") + ", 접속한 사람 " + Bukkit.getOnlinePlayers().size() + "명");
        if (r.found()) f.add("누군가 그의 비석을 찾아 주었다. 비석의 글씨는 깨져 있고, 세 줄 가운데 " + r.lines() + "줄이 돌아왔다");
        if (r.whole(Shards.Line.WHAT)) f.add("그가 그날 한 일: " + truth.what() + " (등불 하나를 켰다)");
        if (voice.active() && voice.mood(thisNight(), 1) == Awakening.Mood.SLEEPY) f.add("그는 지금 졸리다. 곧 자러 간다");
        return f;
    }

    private Predicate<String> swearing() {
        return this::insult;
    }

    /** An answer in public chat (the scripted line is the fallback). sure: a tester's (always asked, whatever the day's share). */
    private Voice.Thought chatWords(UUID to, String scripted, long budget, boolean sure) {
        Record r = record(to);
        NewcomerMind.Ask ask = new NewcomerMind.Ask(NewcomerMind.Kind.CHAT, scripted, List.copyOf(overheard), Memories.brief(memories.of(to)),
                List.copyOf(recent), facts(to), memories.of(to).name());
        return voice.think(ask, Filter.Rules.chat(truth, r.whole(Shards.Line.WHAT), r.found(), swearing()), budget,
                LocalDate.now(SecretModule.KST), () -> sure ? 0 : ThreadLocalRandom.current().nextDouble());
    }

    private Voice.Thought whisperWords(Player p, NewcomerMind.Kind kind, String scripted, String message, List<String> more, long budget) {
        UUID u = p.getUniqueId();
        List<String> f = new ArrayList<>(facts(u));
        f.addAll(more);
        NewcomerMind.Ask ask = new NewcomerMind.Ask(kind, scripted, message == null || message.isBlank() ? List.of() : List.of(new NewcomerMind.Heard(nameOf(p), message)),
                Memories.brief(memories.of(u)), List.copyOf(recentWhispers), f, nameOf(p));
        return voice.think(ask, Filter.Rules.whisper(truth, record(u).whole(Shards.Line.WHAT), swearing()), budget,
                LocalDate.now(SecretModule.KST), () -> m.test(u) ? 0 : ThreadLocalRandom.current().nextDouble());
    }

    /** One more line for the one who signs, out of what he remembers of them ("" when the model writes none). */
    Voice.Thought farewellWords(Player p) {
        return whisperWords(p, NewcomerMind.Kind.FAREWELL, "", "", List.of("이 사람이 방금 증인으로 서명해 그의 기록을 닫아 주었다. 그는 이제 처음으로 나갈 수 있다"), 9000L);
    }

    /** The body of someone's letter (the scripted body is the guide and the fallback). */
    Voice.Thought letterWords(UUID u, Memories.Note note, String scripted) {
        NewcomerMind.Ask ask = new NewcomerMind.Ask(NewcomerMind.Kind.LETTER, scripted, List.of(), Memories.brief(note), List.of(),
                List.of("그는 어젯밤 처음으로 서버에서 나갔다. 이제 다시 오지 않는다", "그가 그날 한 일: 등불 하나를 켰다"), note.name());
        Filter.Rules rules = new Filter.Rules(truth.name(), truth.alias(), true, true, false, Filter.MAX, swearing());
        return voice.think(ask, rules, 30_000L, LocalDate.now(SecretModule.KST), () -> 0);
    }

    /** He was signed for ({@link Vigil}): the title, the stone whole, and for the server it is closed. */
    void signed(Player p, boolean alone) {
        if (!alone) visit = null;
        m.solve(this, p);
        if (alone) {
            p.sendMessage(Text.msg("<yellow>[테스트] 비석 (나에게만):<newline>" + record(p.getUniqueId()).text()));
            return;
        }
        m.redraw();
        try {
            // /프로필 <his name>: a profile whose last day is today
            if (m.core().profiles() != null) m.core().profiles().store().seen(Look.fake(truth.name()), truth.name(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            // no profile is no loss
        }
    }

    // ------------------------------------------------------------------------------------------
    // sworn at: he sulks and is gone for the night
    // ------------------------------------------------------------------------------------------

    static LocalDateTime nowKst() {
        return ZonedDateTime.now(SecretModule.KST).toLocalDateTime();
    }

    boolean insult(String text) {
        return Sulk.insult(text, swears, allow);
    }

    /** Whether he is gone for the night: for everyone, or (u, not null) for that one player alone. */
    private boolean sulking(UUID u) {
        LocalDateTime t = nowKst();
        if (Sulk.sulking(eco.kv().get(SULK), t, from, to)) return true;
        return u != null && Sulk.sulking(m.get(this, u, "sulk"), t, from, to);
    }

    /**
     * Sworn at: one hurt line (in public chat after the typing time, or whispered back), and he is gone for the rest of the
     * night. The night is saved with all five asked, so nothing brings him back to it; no leave line is ever said.
     * Only for that player when it is their own visit (test mode, a scene) or a tester's whisper. v: the visit the public
     * line was said in (null: a whisper).
     */
    private void sulk(Player p, Visit v, boolean whisper) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        memories.update(u, nameOf(p), Memories.Note::plusRude);
        String key = Sulk.key(nowKst(), from, to);
        boolean own = whisper ? m.test(u) : v != null && v.viewer != null;
        Visit gone;
        if (own) {
            m.set(this, u, "sulk", key);
            gone = whisper ? alone.get(u) : v;
        } else {
            eco.kv().set(SULK, key);
            eco.kv().set(SULK_BY, p.getName());
            if (!key.equals(eco.kv().get(NIGHT)) || eco.kv().getLong(ASKED, 0) < count()) {
                eco.kv().set(NIGHT, key);
                eco.kv().set(ASKED, count());
            }
            eco.getLogger().info("[비밀] 새로 온 사람: " + p.getName() + " 의 욕에 삐져 오늘 밤은 말이 없음 (" + key + " 밤 · "
                    + (whisper ? "귓속말" : "공개 채팅") + ")");
            gone = whisper ? visit : v;
        }
        if (gone != null) {   // no more questions, no more small talk, nothing half typed
            gone.next = gone.qs.size();
            gone.endAt = 0;
            gone.pending.clear();
        }
        String line = Talk.pick(hurt, whisper ? recentWhispers : recent, ThreadLocalRandom.current());
        if (line == null || line.isBlank()) return;
        if (!whisper) {
            if (gone != null) queue(gone, line, now + typing(line));
            return;
        }
        remember(recentWhispers, line, 4);
        answered.put(u, now);
        long wait = Math.max(1, typing(line) / 50);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline() && open(u) && !impostor()) p.sendMessage(incoming(line));
        }, wait);
    }

    // ------------------------------------------------------------------------------------------
    // his stone, and the places a trace shows itself (Restore)
    // ------------------------------------------------------------------------------------------

    /** His stone has no hit box of its own: what is clicked is the unseen thing standing in it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTouch(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (!Restore.touch(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        restore.touched(e.getPlayer());
    }

    /** The float: a bite that twitches the wrong way, and what comes up with the catch. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(org.bukkit.event.player.PlayerFishEvent e) {
        switch (e.getState()) {
            case BITE -> restore.bite(e.getPlayer(), e.getHook().getLocation(), false);
            case CAUGHT_FISH -> restore.caught(e.getPlayer());
            case FISHING, FAILED_ATTEMPT, REEL_IN, IN_GROUND -> restore.slack(e.getPlayer().getUniqueId());
            default -> {
                // caught an entity, lured: nothing of his
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    /**
     * Test mode: he comes for that player alone, quickly. Otherwise he comes now, for everyone, as on any night, and this
     * is the visit of the night that is open (or opens next): he does not come again that night. If that night's join line
     * was already said (he came, or was called before), he only asks his questions again from the first.
     */
    @Override
    public void start(Player p) {
        UUID u = p.getUniqueId();
        if (!on()) {   // nothing moves while it is off: a join line with no questions after it would be all there is
            eco.getLogger().info("[비밀] 새로 온 사람 시작: 꺼져 있어서 시작하지 않았습니다 (/관리자 비밀 켜기 새로온사람 먼저)");
            if (p.hasPermission("stsv.admin")) p.sendMessage(Text.msg("<red>새로온사람: 꺼져 있어요. 먼저 /관리자 비밀 켜기 새로온사람"));
            return;
        }
        if (m.test(u)) {
            if (sulking(u)) {
                if (p.hasPermission("stsv.admin")) p.sendMessage(Text.msg("<red>새로온사람: 오늘 밤은 삐져서 오지 않아요 (/관리자 비밀 연출 새로온사람 삐짐풀기)"));
                return;
            }
            m.set(this, u, "seen", 1);
            arriveFor(p, true);
            return;
        }
        Visit v = visit;
        String no = m.closed(this) ? "이미 풀린 비밀이라 그는 다시 오지 않아요 (서버초기화 로 다시 열 수 있음)"
                : v != null && v.next < v.qs.size() ? "지금 와 있는 중이에요 (질문이 끝난 뒤 다시)"
                : impostor() ? "같은 이름의 실제 플레이어가 접속해 있어요"
                : sulking(null) ? "오늘 밤은 " + eco.kv().get(SULK_BY) + " 의 욕에 삐져서 오지 않아요 (/관리자 비밀 연출 새로온사람 삐짐풀기 로 풀 수 있음)" : null;
        if (no != null) {
            eco.getLogger().info("[비밀] 새로 온 사람 시작: " + no);
            if (p.hasPermission("stsv.admin")) p.sendMessage(Text.msg("<red>새로온사람: " + no));
            return;
        }
        String night = thisNight().toString();
        boolean join = arrive(night, 0, "관리자 시작");
        if (p.hasPermission("stsv.admin")) {
            p.sendMessage(Text.msg(join ? "<gray>새로온사람: " + night + " 밤의 방문을 지금 합니다 (모두에게 입장 줄 → 질문). 그 밤에 또 오지는 않아요"
                    : "<gray>새로온사람: " + night + " 밤에는 이미 들어왔으므로 입장 줄 없이 질문만 처음부터 다시 합니다 (입장 줄은 하룻밤에 한 번)"));
        }
    }

    @Override
    public void jump(Player p, int stage) {
        super.jump(p, stage);
        if (stage >= 1) m.set(this, p.getUniqueId(), "seen", 1);   // so that their whisper reaches him
        if (stage >= 2) {   // as if they had whispered at his stone: traces show themselves, the stone shows its lines
            m.set(this, p.getUniqueId(), "atstone", 1);
            if (record(p.getUniqueId()).find(p.getUniqueId(), System.currentTimeMillis()) && !m.test(p.getUniqueId())) m.redraw();
        } else {
            m.remove(this, p.getUniqueId(), "atstone");
        }
    }

    @Override
    public void cleanup(UUID u) {
        alone.remove(u);
        answered.remove(u);
        counted.remove(u);
        vigil.cleanup(u);
        restore.slack(u);
    }

    /** A player's 초기화: their own test record goes with the rest (it shares their prefix); what he remembers of them too. */
    @Override
    public void resetting(UUID u) {
        memories.forget(u);
    }

    private static String esc(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s);
    }

    /** Tonight's five, as an admin reads them ("1 내일도 … / 2 …"; rain words in brackets). */
    private String listed(LocalDate night) {
        StringBuilder b = new StringBuilder();
        List<Questions.Entry> qs = tonight(night);
        for (int i = 0; i < qs.size(); i++) {
            if (i > 0) b.append(" / ");
            b.append(i + 1).append(' ').append(esc(qs.get(i).text()));
            if (qs.get(i).rain() != null) b.append(" (비: ").append(esc(qs.get(i).rain())).append(')');
        }
        return b.toString();
    }

    @Override
    public String status(UUID u) {
        StringBuilder b = new StringBuilder();
        b.append("다녀감 ").append(eco.kv().has(SEEN) ? "<white>예</white>" : m.get(this, u, "seen") != null ? "<white>이 사람에게만</white>" : "<white>아직</white>");
        LocalDate night = thisNight();
        String key = night.toString();
        boolean begun = key.equals(eco.kv().get(NIGHT));
        long asked = begun ? eco.kv().getLong(ASKED, 0) : 0;
        Visit v = visit;
        b.append(" · ").append(key).append(" 밤 <white>").append(HM.format(Schedule.planned(night, from, to, seed))).append("</white> ")
                .append(m.closed(this) ? "(풀려서 안 옴)" : !begun ? "예정"
                        : asked >= count() && key.equals(eco.kv().get(SULK)) ? "삐져서 나감 (퇴장 줄 없음)"
                : asked >= count() ? (key.equals(eco.kv().get(JOINED))
                        ? (v != null && v.around(System.currentTimeMillis()) ? "다녀감 (아직 채팅을 읽는 중)" : "다녀감")
                        : "쉼 (이름이 같은 실제 플레이어가 접속했음)")
                        : v != null ? "와 있음 " + asked + "/" + count()
                        : "끊김 " + asked + "/" + count() + " (사람이 있으면 입장 줄 없이 이어서 물음)");
        if (sulking(null)) b.append(" · <yellow>삐짐</yellow> (").append(esc(String.valueOf(eco.kv().get(SULK_BY)))).append(" 의 욕, ")
                .append(eco.kv().get(SULK)).append(" 밤: 질문 · 잡담 · 귓속말 답 없음)");
        else if (sulking(u)) b.append(" · <yellow>이 사람에게만 삐짐</yellow>");
        b.append(" · 그 밤 질문 <white>").append(listed(night)).append("</white>");
        if (v != null) b.append(" · 잡담 ").append(v.reactions).append("번");
        Optional<Stones.Found> f = stones.find(id(), m.all());
        Optional<Location> st = stone();
        if (st.isPresent()) {
            Location l = st.get();
            b.append(" · 비석 <white>").append(l.getWorld().getName()).append(String.format(Locale.ROOT, " %.1f %.1f %.1f", l.getX(), l.getY(), l.getZ()))
                    .append("</white>").append(f.get().real() ? "" : " (계산한 자리: 비석이 아직 안 보임)");
        } else {
            b.append(stones.placed() ? " · <red>기념비의 월드가 없음: 끝낼 수 없음</red>" : " · <red>기념비 없음: 끝낼 수 없음 (/관리자 비밀 비석 세우기)</red>");
        }
        b.append(" · 귓속말 <white>").append(m.getLong(this, u, "whispers", 0)).append("</white>회 (")
                .append(m.getLong(this, u, "wnights", 0)).append("밤)");
        if (farewells.containsKey(u)) b.append(" · 엔딩 중");
        if (neverLeaves(u)) b.append(" · 퇴장 줄 숨김");
        return b.toString();
    }

    @Override
    public List<String> scenes() {
        return List.of("입장", "질문", "잡담:ㅎㅇ", "귓속말:누구세요", "삐짐", "삐짐풀기", "피곤", "엔딩", "퇴장", "비석");
    }

    /** "잡담:말_몇_마디" → "말 몇 마디" (the admin command takes one word: "_" for spaces). */
    private static String said(String word, String head) {
        String s = word.length() > head.length() ? word.substring(head.length() + 1) : "";
        return s.replace('_', ' ').strip();
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        String head = word.contains(":") ? word.substring(0, word.indexOf(':')) : word;
        switch (head) {
            case "입장" -> p.sendMessage(joinLine(true));
            case "질문" -> {
                arriveFor(p, false);
                admin.sendMessage(Text.msg("<gray>" + thisNight() + " 밤의 질문 (" + p.getName() + " 에게만, 5초 간격): <white>" + listed(thisNight())));
            }
            case "잡담" -> {
                String text = said(word, head);
                if (text.isEmpty()) {
                    admin.sendMessage(Text.msg("<gray>/관리자 비밀 연출 새로온사람 잡담:<말> [플레이어] — 띄어쓰기는 _ (예: 잡담:sipsam_봇이야, 잡담:석탄은_산에_많아요)"));
                    return true;
                }
                if (insult(text)) {
                    String line = Talk.pick(hurt, List.of(), ThreadLocalRandom.current());
                    admin.sendMessage(Text.msg("<gray>잡담 \"<white>" + esc(text) + "</white>\" → <yellow>욕</yellow> · 질문 직후 · 그가 말한 직후라면: <white>→ 삐져서 나감</white> · 그냥 지나가는 말이면: <white>"
                            + (Sulk.aimed(text, who, aim) ? "→ 삐져서 나감 (그를 가리킴)" : "반응 없음 (그를 가리키지 않음)") + "</white> · 그 줄: <white>" + esc(line) + "</white>"));
                    p.sendMessage(look.chat(line));
                    return true;
                }
                boolean named = Talk.mentions(text, who);
                Reaction now = react(text, true, true), plain = react(text, named, false);
                admin.sendMessage(Text.msg("<gray>잡담 \"<white>" + esc(text) + "</white>\" → 질문 직후라면: <white>"
                        + (now == null ? "반응 없음" : now.intent().id + " (" + Math.round(now.intent().chance * 100) + "%) " + esc(now.line())
                        + (now.follow() == null ? "" : " / " + esc(now.follow()))) + "</white> · 그냥 지나가는 말이면: <white>"
                        + (plain == null ? "반응 없음" : plain.intent().id + " " + esc(plain.line())) + "</white>"));
                if (now != null) {
                    p.sendMessage(look.chat(now.line()));
                    if (now.follow() != null) p.sendMessage(look.chat(now.follow()));
                }
            }
            case "귓속말" -> {
                String text = said(word, head);
                if (text.isEmpty()) text = "안녕하세요";
                if (insult(text)) {
                    String line = Talk.pick(hurt, List.of(), ThreadLocalRandom.current());
                    admin.sendMessage(Text.msg("<gray>귓속말 \"<white>" + esc(text) + "</white>\" → <yellow>욕</yellow> <white>→ 삐져서 나감</white> (어디서든, 그의 비석 앞이어도. 모두에게 그 밤은 끝)"));
                    p.sendMessage(look.outgoing(text));
                    p.sendMessage(incoming(line));
                    return true;
                }
                StringBuilder why = new StringBuilder();
                String line = whisperReply(text, m.getLong(this, p.getUniqueId(), "wnights", 0), why);
                admin.sendMessage(Text.msg("<gray>귓속말 \"<white>" + esc(text) + "</white>\" → <white>" + why + "</white>"
                        + " (비석 앞이 아닐 때. 그의 비석 앞이면 엔딩, 다른 비석 앞이면 wrong-stone)"));
                p.sendMessage(look.outgoing(text));
                if (line != null) p.sendMessage(incoming(line));
            }
            case "삐짐" -> {
                String line = Talk.pick(hurt, List.of(), ThreadLocalRandom.current());
                p.sendMessage(look.chat(line));
                admin.sendMessage(Text.msg("<gray>삐짐 (" + p.getName() + " 에게만, 아무것도 적지 않음): 공개 채팅에서 욕을 들으면 이 줄을 치고 (귓속말이면 귓속말 답으로), "
                        + "그 밤에는 질문 · 잡담 · 귓속말 답이 없어요. 퇴장 줄은 뜨지 않아요"));
            }
            case "피곤" -> {
                String line = Talk.pick(tired, List.of(), ThreadLocalRandom.current());
                CommandSender to = admin instanceof Player ? admin : p;
                to.sendMessage(look.chat(line));
                admin.sendMessage(Text.msg("<gray>피곤 (" + to.getName() + " 에게만, 아무것도 적지 않음): 다섯 번째 질문 뒤 잡담 시간("
                        + cfg.getInt("chat.linger-seconds", 1800) / 60 + "분)이 끝날 때 공개 채팅에 한 번. 그 뒤로는 말이 없고 퇴장 줄도 없어요"));
            }
            case "삐짐풀기" -> {
                boolean was = sulking(null), own = sulking(p.getUniqueId()) && !was;
                eco.kv().remove(SULK);
                eco.kv().remove(SULK_BY);
                m.remove(this, p.getUniqueId(), "sulk");
                admin.sendMessage(Text.msg(was || own ? "<gray>삐짐을 풀었어요. 그 밤의 방문은 끝난 것으로 적혀 있으니, 다시 보려면 /관리자 비밀 시작 새로온사람 (입장 줄은 그 밤에 이미 떴다면 없이)"
                        : "<gray>지금 삐져 있지 않아요 (기록만 지움: 다음 밤의 \"어제는 죄송했어요\" 도 없음)"));
            }
            case "엔딩" -> farewell(p, true);
            case "퇴장" -> p.sendMessage(joinLine(false));
            case "비석" -> {
                Optional<Location> st = stone();
                if (st.isEmpty()) {
                    admin.sendMessage(Text.msg("<red>기념비가 없어요 (또는 그 월드가 없음). 먼저 /관리자 비밀 비석 세우기 — 그 전에는 이 비밀을 끝낼 수 없어요"));
                } else {
                    Location l = st.get();
                    boolean real = stones.find(id(), m.all()).map(Stones.Found::real).orElse(false);
                    admin.sendMessage(Text.msg("<gray>그의 비석 (아무것도 안 적힌 돌): <white>" + l.getWorld().getName()
                            + String.format(Locale.ROOT, " %.1f %.1f %.1f", l.getX(), l.getY(), l.getZ()) + "</white>"
                            + (real ? "" : " <yellow>(계산한 자리: 그 청크가 안 불려 있거나 비석이 아직 안 그려짐)") + " <gray>· 이 돌에서 "
                            + cfg.getDouble("stone.range", 3.5) + "칸 안에서 귓속말"));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
