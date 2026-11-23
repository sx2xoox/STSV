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
 * that he left: he solved a secret here long ago, when nobody was watching and nothing was announced. The monument's last
 * stone, the one with nothing on it, is his. The first characters of his five questions ask what he really wants to know
 * ("내 비석 어디"). The answer is not typed: stand at that bare stone and whisper to him ({@link Whispers}); he hands the
 * stone on ({@link Farewell}), and for the only time ever the server says that he left.
 * <p>
 * He reads like a shy new player: each night's five questions come out of a pool per letter ({@link Questions}), he takes
 * his time typing ({@link Typing}), now and then slips and puts it right, and while he is "online" (his visit and a while
 * after) he answers a little of what is said in chat, and whispers, by what they are about ({@link Talk}). How his name,
 * chat line and whispers look is built in {@link Look} alone.
 * <p>
 * Stage: 0 = never whispered to him; 1 = whispered at least once (anywhere); 2 = solved (the ending).
 * Saved, for everyone (eco.kv "secret.newcomer."): {@code seen} he has come at least once (before that a whisper to him is
 * the game's own "no such player"), {@code night} the night (date its window opened) of his last visit, {@code asked}
 * how many questions he got to that night, and {@code joined} the night his join line was last said: it is said once a
 * night and never again that night (he never leaves, so after a restart, or when everyone had gone, he just goes on asking).
 * An admin's 시작 is the visit of the night that is open or opens next, made now: he does not come a second time that night.
 * Per player ("secret.newcomer.&lt;uuid&gt;."): {@code seen} he came for them alone (test mode, admin), {@code whispers}
 * how often they whispered, {@code wnight} / {@code wnights} the last night they whispered and on how many nights,
 * {@code late} told that someone was first, {@code sulk} the night he sulked at them alone (test mode, a visit only they saw).
 * <p>
 * Sworn at ({@link Sulk}): in public chat when it is aimed at him, or in any whisper to him, he says one hurt line and is
 * gone for the rest of that night, without a leave line. Saved: {@code sulk} the night, {@code sulkby} who it was; that
 * night's {@code asked} is set to all five, so nothing (a restart, the min-online pause, an admin's 시작) brings him back.
 */
public final class Newcomer extends Secret {

    static final String K = "secret.newcomer.", SEEN = K + "seen", NIGHT = K + "night", ASKED = K + "asked", JOINED = K + "joined",
            SULK = K + "sulk", SULK_BY = K + "sulkby";
    static final String DEFAULT_NAME = "sipsam_", DEFAULT_ACROSTIC = "내비석어디";
    static final List<String> ENDING = List.of(
            "…찾았네요.",
            "…그거, 제 거 맞아요.",
            "…저 예전에 여기서 뭘 하나 풀었거든요. 아무도 없을 때. 공지도 안 떴고, 비석에 이름도 안 새겨졌어요.",
            "…그래서 매일 다시 들어왔어요. 누가 물어봐 줄까 봐.",
            "…질문은 제가 했는데, 답은 {player}님이 해 주셨네요.",
            "…이제 {player}님이 열세 번째예요.",
            "…저는 이제 나가 볼게요. 처음으로.");
    /** How he puts a slip right ({word}: the word as it should have been). */
    static final List<String> FIXES = List.of("*{word}", "*{word}", "{word}*", "아 오타", "오타 ㅋㅋ", "*{word} 오타..");
    static final List<String> TIME_WORDS = List.of("이 새벽에", "이 밤에", "이 시간에");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    /** A visit left alone this long (ms; the secret was off) is over. */
    private static final long STALE = 120_000L;

    private final Stones stones;
    private final Look look;
    /** his visit to everyone (one at a time), and his visits to one tester or admin each */
    private volatile Visit visit;
    private final Map<UUID, Visit> alone = new ConcurrentHashMap<>();
    /** endings playing, and the one player whose ending is the real one (nobody else gets an answer meanwhile) */
    private final Map<UUID, Farewell> farewells = new ConcurrentHashMap<>();
    private UUID heir;
    /** when a player last got an answer / last had a whisper counted (ms) */
    private final Map<UUID, Long> answered = new ConcurrentHashMap<>(), counted = new ConcurrentHashMap<>();
    /** what he said lately, in chat and in whispers (not said again soon) */
    private final Deque<String> recent = new ArrayDeque<>(), recentWhispers = new ArrayDeque<>();
    private long lastJoin, lastTick;
    private int ticks;

    private String who = DEFAULT_NAME;
    private List<String> letters = Questions.letters(DEFAULT_ACROSTIC), ending = ENDING, labels = Whispers.VANILLA, fixes = FIXES,
            strange = Talk.STRANGE, timeWords = TIME_WORDS;
    private Map<String, List<Questions.Entry>> pools = Questions.DEFAULTS;
    private List<Talk.Intent> chat = Talk.CHAT, whisperTalk = Talk.WHISPER, talk = smallTalk(Talk.CHAT);
    /** swearing at him (rude's patterns, the allow list, what aims a line at him), his hurt lines, his sheepish word */
    private List<Pattern> swears = Sulk.patterns(Sulk.SWEARS), allow = Sulk.patterns(Sulk.ALLOW), aim = Sulk.patterns(Sulk.AIM);
    private List<String> hurt = Sulk.HURT, sorry = Sulk.SORRY;
    private int from = 22, to = 2;
    private long seed = 13;

    public Newcomer(SecretModule m) {
        super(m);
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

    /** The solved stone reads as handed on: his place in the row and his name, then the solver's under it. */
    @Override
    public String stoneTitle() {
        return cfg.getString("stone-name", "열세 번째 · " + who);
    }

    /** His stone says nothing until it is solved: it is the bare one. */
    @Override
    public boolean hiddenStone() {
        return true;
    }

    @Override
    public int stages() {
        return 1;
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
        List<String> f = new ArrayList<>();
        for (String s : cfg.isList("typo.fix") ? Talk.clean(cfg.getStringList("typo.fix")) : FIXES) {
            if (Talk.small(s)) f.add(s);
            else problems.add("typo.fix: \"" + s + "\" — \"?\" 를 쓰지 않아요 (빼고 읽음)");
        }
        fixes = f;
        strange = cfg.isList("whisper.strange.lines") ? Talk.clean(cfg.getStringList("whisper.strange.lines")) : Talk.STRANGE;
        timeWords = lines("visit.time-words", TIME_WORDS);
        if (timeWords.size() < 3) timeWords = TIME_WORDS;
        ending = lines("ending.lines", ENDING);
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
        String stone = cfg.getString("stone-name");
        if (stone != null && !stone.contains(who)) {
            eco.getLogger().warning("[비밀] newcomer.yml: stone-name \"" + stone + "\" 에 그의 이름 (" + who + ") 이 없어요. name 을 바꿨다면 stone-name 도 같이 바꾸세요.");
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

    List<String> ending() {
        return ending;
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
    private record Pending(String words, long at) {}

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
        while (!v.pending.isEmpty() && now >= v.pending.peekFirst().at()) say(v, v.pending.removeFirst().words());
        if (v.next >= v.qs.size()) return v.around(now);
        if (now < v.nextAt || !v.pending.isEmpty()) return true;
        // nobody to ask: what is left waits in kv, and he goes on when someone is here (the same night only; no second join line)
        if (v.viewer == null && Bukkit.getOnlinePlayers().size() < Math.max(1, cfg.getInt("visit.min-online", 1))) return false;
        ask(v, now);
        if (v.night != null && v.night.equals(eco.kv().get(NIGHT))) eco.kv().set(ASKED, v.next);
        if (v.next >= v.qs.size()) v.endAt = now + (v.quick ? seconds("chat.test-linger-seconds", 120) : seconds("chat.linger-seconds", 600));
        else v.nextAt = now + gap(v, false) + typing(v.qs.get(v.next).text());
        return true;
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
        Visit v = new Visit(null, night, false, tonight(LocalDate.parse(night)), Math.max(0, from));
        v.nextAt = now + gap(v, true) + (v.next < v.qs.size() ? typing(v.qs.get(v.next).text()) : 0);
        boolean join = !night.equals(eco.kv().get(JOINED));
        eco.kv().set(NIGHT, night);
        eco.kv().set(ASKED, v.next);
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
        eco.getLogger().info("[비밀] 새로 온 사람 (" + who + ") " + (join ? "이 들어온 것처럼 보였습니다 (가짜 입장 줄"
                : "이 질문을 이어 갑니다 (입장 줄은 이 밤에 이미 떴으므로 없음") + " · 접속 " + Bukkit.getOnlinePlayers().size() + "명 · " + why
                + " · " + night + " 밤, 질문 " + v.next + "/" + count() + "부터)");
        visit = v;
        return join;
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
        // he is not there while someone really named like him is: nothing half said goes on (a tester's ending too)
        if (real) for (Farewell f : List.copyOf(farewells.values())) broken(f, null);
        if (m.closed(this)) {
            visit = null;
            if (++ticks % 5 == 0) late();
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

    /** Someone else was first: whoever had whispered to him is told once. */
    private void late() {
        UUID first = m.solver(this).orElse(null);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            int st = m.stage(this, u);
            if (st < 1 || st > stages() || u.equals(first) || m.test(u) || m.get(this, u, "late") != null) continue;
            m.set(this, u, "late", 1);
            p.sendMessage(Text.mm(cfg.getString("too-late", "<dark_gray>…그 사람은 이제 없어요. 누군가 먼저 찾아 줬어요.")));
        }
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
        if (!v.quick && v.reactions >= cfg.getInt("chat.max-per-night", 6)) return false;
        long gap = v.quick ? seconds("chat.test-min-gap-seconds", 5) : seconds("chat.min-gap-seconds", 45);
        if (v.lastReactAt != 0 && now - v.lastReactAt < gap) return false;
        return named || !by.equals(v.lastBy) || now - v.lastReactAt >= seconds("chat.same-player-seconds", 120);
    }

    private void reacted(Visit v, UUID by, long now) {
        v.reactions++;
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
        if (sulking(v.viewer)) return;   // gone for the night
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
        long at = now + typing(re.line());
        queue(v, re.line(), at);
        if (re.follow() != null) queue(v, re.follow(), at + 1500 + typing(re.follow()));
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
        if (now - counted.getOrDefault(u, 0L) >= 3000L) {   // a flood of whispers is one whisper
            counted.put(u, now);
            m.set(this, u, "whispers", m.getLong(this, u, "whispers", 0) + 1);
        }
        long nights = whisperNight(u);
        if (m.stage(this, u) < 1) m.stage(this, u, 1);
        if (farewells.containsKey(u)) return;            // he is already answering them
        boolean test = m.test(u);
        if (!test && heir != null) return;               // he is answering someone else: silence
        boolean sulky = sulking(test ? u : null);
        if (insult(w.get().message())) {                 // sworn at, even at his stone: he sulks (once a night)
            if (!sulky) sulk(p, null, true);
            return;
        }
        Stones.Where at = where(p);
        if (at == Stones.Where.HIS) {                    // the ending works on a sulky night too
            farewell(p, false);
            return;
        }
        if (sulky) return;                               // gone for the night: no answer, as from someone away
        if (now - answered.getOrDefault(u, 0L) < seconds("whisper.cooldown-seconds", 20)) return;
        answered.put(u, now);
        String line = at == Stones.Where.OTHER ? cfg.getString("whisper.wrong-stone", "…그건 제 게 아니에요.")
                : whisperReply(w.get().message(), nights, new StringBuilder());
        if (line == null || line.isBlank()) return;
        remember(recentWhispers, line, 4);
        long wait = Math.max(1, typing(line) / 50);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline() && open(u) && !farewells.containsKey(u) && !impostor()) p.sendMessage(incoming(line));
        }, wait);
    }

    // ------------------------------------------------------------------------------------------
    // sworn at: he sulks and is gone for the night
    // ------------------------------------------------------------------------------------------

    private static LocalDateTime nowKst() {
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
            if (p.isOnline() && open(u) && !farewells.containsKey(u) && !impostor()) p.sendMessage(incoming(line));
        }, wait);
    }

    // ------------------------------------------------------------------------------------------
    // the end
    // ------------------------------------------------------------------------------------------

    private void farewell(Player p, boolean preview) {
        UUID u = p.getUniqueId();
        Farewell old = farewells.remove(u);
        if (old != null) old.stop();
        if (u.equals(heir)) heir = null;
        if (!preview && !m.test(u)) heir = u;   // the one real ending: nobody else gets an answer while it plays
        Location stone = stone().filter(l -> l.getWorld().equals(p.getWorld()) && l.distanceSquared(p.getLocation()) < 32 * 32).orElse(null);
        Location centre = stone == null ? null : centre().orElse(null);
        Farewell f = new Farewell(this, p, stone, centre, preview);
        farewells.put(u, f);
        f.begin();
    }

    /**
     * The ending played to its last line: the only time the server says he left, and the stone is handed on. A preview
     * and a tester's ending say it to that player alone (whether they are a tester is asked now, not when it began: the
     * line to everyone and the closing of the secret always go together).
     */
    void done(Farewell f, Player p) {
        UUID u = p.getUniqueId();
        farewells.remove(u, f);
        if (u.equals(heir)) heir = null;
        Component left = joinLine(false);
        if (f.preview) {
            p.sendMessage(left);
            return;
        }
        if (!open(u) || impostor()) return;   // switched off, or (an admin's doing) solved meanwhile: nothing is said
        if (m.test(u)) {
            p.sendMessage(left);
        } else {
            toAll(left);
            eco.getLogger().info("[비밀] 새로 온 사람 (" + who + ") 이 나갔다고 모두에게 알렸습니다 (처음이자 마지막 · 푼 사람 " + p.getName() + ")");
            visit = null;
        }
        m.solve(this, p);
    }

    /**
     * An ending that stops before its last line (they walked away from the stone, changed world, died; someone really
     * named like him came on; an error): nothing was saved and nothing is said to the server, so whispering at the stone
     * again begins it anew. p: whoever is told that he stopped (null: nobody).
     */
    void broken(Farewell f, Player p) {
        UUID u = f.who();
        f.stop();
        if (!farewells.remove(u, f)) return;
        if (u.equals(heir)) heir = null;
        if (p == null || !p.isOnline()) return;
        String line = cfg.getString("ending.left", "…아, 가시네요.");
        if (line != null && !line.isBlank()) p.sendMessage(incoming(line));
    }

    /** Whether this player's own leaving is not announced (the solver; a tester who finished, while still in test mode). */
    private boolean neverLeaves(UUID u) {
        if (!cfg.getBoolean("perk.no-leave-line", true)) return false;
        if (m.solver(this).filter(u::equals).isPresent()) return true;
        return m.test(u) && m.stage(this, u) > stages();
    }

    /**
     * After the nickname module (LOW) has rewritten the line: for the one who inherited "never leaving", there is no line.
     * Being kicked or banned is not leaving: that line stays for everyone to see (perk.show-kick), and what is hidden is
     * written to the console, where the game's own "lost connection" line stays as well.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        if (e.quitMessage() == null || !neverLeaves(e.getPlayer().getUniqueId())) return;
        if (e.getReason() == PlayerQuitEvent.QuitReason.KICKED && cfg.getBoolean("perk.show-kick", true)) return;
        e.quitMessage(null);
        eco.getLogger().info("[비밀] " + e.getPlayer().getName() + " 이(가) 나갔습니다 (새로 온 사람 특전: 퇴장 줄은 아무에게도 보이지 않음)");
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
    }

    @Override
    public void cleanup(UUID u) {
        alone.remove(u);
        answered.remove(u);
        counted.remove(u);
        Farewell f = farewells.remove(u);
        if (f != null) f.stop();
        if (u.equals(heir)) heir = null;
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
        return List.of("입장", "질문", "잡담:ㅎㅇ", "귓속말:누구세요", "삐짐", "삐짐풀기", "엔딩", "퇴장", "비석");
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
