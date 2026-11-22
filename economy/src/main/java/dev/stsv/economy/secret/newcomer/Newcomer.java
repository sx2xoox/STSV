package dev.stsv.economy.secret.newcomer;

import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 새로 온 사람 (docs/admin/숨은_비밀/새로_온_사람.md, contents/economy/secrets/newcomer.yml). Every night someone new joins
 * and asks beginner questions in public chat. He is not in the tab list, nobody has met him, and the server has never said
 * that he left: he solved a secret here long ago, when nobody was watching and nothing was announced. The monument's last
 * stone, the one with nothing on it, is his. The first characters of his five questions ask what he really wants to know
 * ("내 비석 어디"). The answer is not typed: stand at that bare stone and whisper to him ({@link Whispers}); he hands the
 * stone on ({@link Farewell}), and for the only time ever the server says that he left.
 * <p>
 * Stage: 0 = never whispered to him; 1 = whispered at least once (anywhere); 2 = solved (the ending).
 * Saved, for everyone (eco.kv "secret.newcomer."): {@code seen} he has come at least once (before that a whisper to him is
 * the game's own "no such player"), {@code night} the night (date its window opened) of his last visit and {@code asked}
 * how many questions he got to that night (a restart mid-visit: he joins again and goes on from there).
 * Per player ("secret.newcomer.&lt;uuid&gt;."): {@code seen} he came for them alone (test mode, admin), {@code whispers}
 * how often they whispered, {@code replies} how many evasive answers they got, {@code late} told that someone was first.
 */
public final class Newcomer extends Secret {

    static final String K = "secret.newcomer.", SEEN = K + "seen", NIGHT = K + "night", ASKED = K + "asked";
    static final String DEFAULT_NAME = "sipsam_", DEFAULT_ACROSTIC = "내비석어디";
    static final List<String> QUESTIONS = List.of(
            "내일도 이 시간에 사람 많아요?",
            "비 오면 물고기가 더 잘 잡힌다던데 진짜예요?",
            "석탄은 어디 가야 많이 나와요?",
            "어제도 다들 계셨어요? 저만 못 본 건가",
            "디스코드 같은 건 따로 없죠? 그냥 여기서 물어봐도 되나");
    static final List<String> EVASIVE = List.of("…저 아세요?", "…죄송해요, 제가 아직 잘 몰라서.", "…거기 아니에요.", "…저는 계속 여기 있었는데요.");
    static final List<String> REACTIONS = List.of("아 감사합니다 ㅎㅎ", "오 그렇구나");
    static final List<String> ENDING = List.of(
            "…찾았네요.",
            "…그거, 제 거 맞아요.",
            "…저 예전에 여기서 뭘 하나 풀었거든요. 아무도 없을 때. 공지도 안 떴고, 비석에 이름도 안 새겨졌어요.",
            "…그래서 매일 다시 들어왔어요. 누가 물어봐 줄까 봐.",
            "…질문은 제가 했는데, 답은 당신이 했네요.",
            "…이제 당신이 열세 번째예요.",
            "…저는 이제 나가 볼게요. 처음으로.");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    /** A visit left alone this long (ms; the secret was off) is over. */
    private static final long STALE = 120_000L;

    private final Stones stones;
    /** his visit to everyone (one at a time), and his visits to one tester or admin each */
    private volatile Visit visit;
    private final Map<UUID, Visit> alone = new ConcurrentHashMap<>();
    /** endings playing, and the one player whose ending is the real one (nobody else gets an answer meanwhile) */
    private final Map<UUID, Farewell> farewells = new ConcurrentHashMap<>();
    private UUID heir;
    /** when a player last got an answer / last had a whisper counted (ms) */
    private final Map<UUID, Long> answered = new ConcurrentHashMap<>(), counted = new ConcurrentHashMap<>();
    private long lastJoin, lastTick;
    private int ticks;

    private String who = DEFAULT_NAME;
    private UUID whoId = fake(DEFAULT_NAME);
    private List<String> questions = QUESTIONS, evasive = EVASIVE, reactions = REACTIONS, ending = ENDING, labels = Whispers.VANILLA;
    private int from = 22, to = 2;
    private long seed = 13;

    public Newcomer(SecretModule m) {
        super(m);
        stones = new Stones(eco);
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
        whoId = fake(who);
        questions = lines("visit.questions", QUESTIONS);
        evasive = lines("whisper.evasive", EVASIVE);
        ending = lines("ending.lines", ENDING);
        reactions = cfg.isList("react.lines") ? clean(cfg.getStringList("react.lines")) : REACTIONS;   // an empty list: he never answers
        List<String> l = new ArrayList<>();
        for (String s : cfg.getStringList("whisper.commands")) if (!s.isBlank()) l.add(s.strip().toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        labels = l.isEmpty() ? Whispers.VANILLA : List.copyOf(l);
        int[] h = range("visit.hours", 22, 2);
        from = Schedule.hour(h[0]);
        to = Schedule.hour(h[1]);
        seed = cfg.getLong("visit.seed", 13);
        stones.forget();
        String word = cfg.getString("acrostic", DEFAULT_ACROSTIC);
        if (!Acrostic.spells(questions, word)) {
            eco.getLogger().warning("[비밀] newcomer.yml: 질문 첫 글자가 \"" + Acrostic.of(questions) + "\" — acrostic \"" + word
                    + "\" 과(와) 다릅니다. 이대로면 플레이어가 풀 단서가 없어요 (visit.questions 의 첫 글자를 맞추세요).");
        }
        if (!Whispers.account(who)) {
            eco.getLogger().warning("[비밀] newcomer.yml: name \"" + who + "\" 은(는) 마인크래프트 이름 꼴(영문 · 숫자 · _ 3~16자)이 아니에요. 진짜 접속자처럼 보이지 않습니다.");
        }
        try {
            if (Bukkit.getOfflinePlayerIfCached(who) != null) {
                eco.getLogger().warning("[비밀] newcomer.yml: name \"" + who + "\" 은(는) 이 서버에 실제로 들어온 적 있는 계정 이름이에요. "
                        + "그 사람이 접속한 밤에는 이 비밀이 쉽니다. 다른 이름으로 바꾸는 편이 좋아요.");
            }
        } catch (RuntimeException ignored) {
            // no server yet
        }
    }

    private static UUID fake(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> clean(List<String> l) {
        List<String> out = new ArrayList<>();
        for (String s : l) if (s != null && !s.isBlank()) out.add(s.strip());
        return List.copyOf(out);
    }

    private List<String> lines(String path, List<String> def) {
        List<String> l = clean(cfg.getStringList(path));
        return l.isEmpty() ? def : l;
    }

    /** A [low, high] pair (a single number is both). */
    private int[] range(String path, int lo, int hi) {
        List<Integer> l = cfg.getIntegerList(path);
        if (l.size() >= 2) return new int[]{l.get(0), l.get(1)};
        if (cfg.isInt(path)) return new int[]{cfg.getInt(path), cfg.getInt(path)};
        return new int[]{lo, hi};
    }

    private long between(String path, int lo, int hi) {
        int[] r = range(path, lo, hi);
        int a = Math.max(0, Math.min(r[0], r[1])), b = Math.max(a, Math.max(r[0], r[1]));
        return 1000L * (a + ThreadLocalRandom.current().nextInt(b - a + 1));
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
    // the lines, as the game itself would say them
    // ------------------------------------------------------------------------------------------

    /** His name as the game writes a player's: click to whisper, shift-click to paste, hover for who it is. */
    Component him() {
        try {
            return Component.text(who).insertion(who).clickEvent(ClickEvent.suggestCommand("/tell " + who + " "))
                    .hoverEvent(HoverEvent.showEntity(Key.key("minecraft", "player"), whoId, Component.text(who)));
        } catch (RuntimeException e) {
            return Component.text(who);
        }
    }

    /** "… joined the game" / "… left the game", yellow, in each client's own language. */
    Component joinLine(boolean join) {
        return Component.translatable(join ? "multiplayer.player.joined" : "multiplayer.player.left", him()).color(NamedTextColor.YELLOW);
    }

    /** A whisper from him as the game shows one coming in (grey, slanted). */
    Component incoming(String mm) {
        return Component.translatable("commands.message.display.incoming", him(), Text.mm(mm))
                .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC);
    }

    /** The sender's own whisper as the game echoes it. */
    private Component outgoing(String message) {
        return Component.translatable("commands.message.display.outgoing", him(), Component.text(message))
                .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC);
    }

    void toAll(Component c) {
        for (Player q : Bukkit.getOnlinePlayers()) q.sendMessage(c);
    }

    /** One chat line from him in the server's chat format; a format this cannot read falls back to the plain "<name> words". */
    private void chat(Player viewer, String words) {
        try {
            m.haunt().chat(viewer, who, words);
        } catch (RuntimeException e) {
            viewer.sendMessage(Component.text("<" + who + "> " + words));
        }
    }

    private void say(Visit v, String words) {
        if (v.viewer != null) {
            Player p = Bukkit.getPlayer(v.viewer);
            if (p != null) chat(p, words);
            return;
        }
        for (Player q : Bukkit.getOnlinePlayers()) chat(q, words);
        eco.getLogger().info("[비밀] 새로 온 사람 (" + who + ", 가짜 채팅): " + words);
    }

    // ------------------------------------------------------------------------------------------
    // his visit
    // ------------------------------------------------------------------------------------------

    /** One visit: the join line was said; the questions follow one by one. */
    private static final class Visit {
        /** the only one who sees it, or null for everyone */
        final UUID viewer;
        /** the night it counts for (kept in kv as it goes), or null when an admin called him */
        final String night;
        /** short waits (test mode, scenes) */
        final boolean quick;
        int next;
        long nextAt, askedAt, endAt;
        /** the one small answer to someone who answered him: rolled once a visit */
        boolean rolled;
        String reply;
        long replyAt;

        Visit(UUID viewer, String night, boolean quick, int next, long nextAt) {
            this.viewer = viewer;
            this.night = night;
            this.quick = quick;
            this.next = next;
            this.nextAt = nextAt;
        }
    }

    private long gap(Visit v, boolean first) {
        if (v.quick) return 1000L * Math.max(1, cfg.getInt("visit.test-gap-seconds", 5));
        return first ? between("visit.first-gap-seconds", 40, 90) : between("visit.gap-seconds", 70, 160);
    }

    private long reactWindow() {
        return 1000L * Math.max(0, cfg.getInt("react.window-seconds", 25));
    }

    /** Goes on with a visit; false when it is over (or cannot go on now). */
    private boolean step(Visit v, long now) {
        if (v.reply != null && now >= v.replyAt) {
            say(v, v.reply);
            v.reply = null;
        }
        if (v.next >= questions.size()) return v.reply != null || now < v.endAt;
        if (now < v.nextAt) return true;
        // nobody to ask: what is left waits in kv, and he joins again when someone is here (the same night only)
        if (v.viewer == null && Bukkit.getOnlinePlayers().size() < Math.max(1, cfg.getInt("visit.min-online", 1))) return false;
        say(v, questions.get(v.next));
        v.next++;
        v.askedAt = now;
        if (v.night != null) eco.kv().set(ASKED, v.next);
        if (v.next >= questions.size()) v.endAt = now + reactWindow() + 10_000L;
        else v.nextAt = now + gap(v, false);
        return true;
    }

    /** He joins, for everyone. night: the night it counts for (null: an admin called him), from: the question to go on from. */
    private void arrive(String night, int from) {
        long now = System.currentTimeMillis();
        Visit v = new Visit(null, night, false, Math.max(0, from), 0);
        v.nextAt = now + gap(v, true);
        if (night != null) {
            eco.kv().set(NIGHT, night);
            eco.kv().set(ASKED, v.next);
        }
        eco.kv().set(SEEN, 1);
        toAll(joinLine(true));
        eco.getLogger().info("[비밀] 새로 온 사람 (" + who + ") 이 들어온 것처럼 보였습니다 (가짜 입장 줄 · 접속 " + Bukkit.getOnlinePlayers().size()
                + "명 · " + (night == null ? "관리자 시작" : night + " 밤, 질문 " + v.next + "/" + questions.size() + "부터") + ")");
        visit = v;
    }

    /** He joins for one player alone (test mode, a scene): short waits, nobody else sees a thing. */
    private void arriveFor(Player p, boolean joinLine) {
        long now = System.currentTimeMillis();
        Visit v = new Visit(p.getUniqueId(), null, true, 0, 0);
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
        String key = Schedule.next(LocalDateTime.now(SecretModule.KST), from, to).toString();
        if (key.equals(eco.kv().get(NIGHT)) && eco.kv().getLong(ASKED, 0) >= questions.size()) return;
        eco.kv().set(NIGHT, key);
        eco.kv().set(ASKED, questions.size());
        eco.getLogger().warning("[비밀] 새로 온 사람: 이름이 같은 실제 플레이어(" + who + ")가 접속해 있어서 " + key + " 밤은 쉽니다. "
                + "계속 겹치면 secrets/newcomer.yml 의 name 을 바꾸세요.");
    }

    /** Whether tonight's visit is due, and if so, he joins. */
    private void plan(long now) {
        LocalDateTime t = ZonedDateTime.now(SecretModule.KST).toLocalDateTime();
        Optional<LocalDate> night = Schedule.night(t, from, to);
        if (night.isEmpty()) return;
        String key = night.get().toString();
        boolean begun = key.equals(eco.kv().get(NIGHT));
        int asked = begun ? (int) eco.kv().getLong(ASKED, 0) : 0;
        if (asked >= questions.size()) return;
        if (!begun && t.isBefore(Schedule.planned(night.get(), from, to, seed))) return;
        if (Bukkit.getOnlinePlayers().size() < Math.max(1, cfg.getInt("visit.min-online", 1))) return;
        if (now - lastJoin < 1000L * Math.max(0, cfg.getInt("visit.after-join-seconds", 45))) return;   // not on a real player's heels
        arrive(key, asked);
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        // it stood still (switched off for a while): he is not picked up mid-sentence. What is left of tonight waits in kv,
        // and he joins again for it
        if (lastTick != 0 && now - lastTick > STALE) {
            visit = null;
            alone.clear();
        }
        lastTick = now;
        for (Iterator<Map.Entry<UUID, Visit>> it = alone.entrySet().iterator(); it.hasNext(); ) {
            Visit v = it.next().getValue();
            if (Bukkit.getPlayer(v.viewer) == null || !step(v, now)) it.remove();
        }
        if (m.closed(this)) {
            visit = null;
            if (++ticks % 5 == 0) late();
            return;
        }
        if (impostor()) {
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

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        lastJoin = System.currentTimeMillis();
    }

    // ---- a word back to whoever answers him

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (visit == null && alone.isEmpty()) return;
        UUID u = e.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(eco, () -> heard(u));
    }

    private void heard(UUID by) {
        if (!on() || reactions.isEmpty()) return;
        Visit v = alone.get(by);
        if (v == null) v = visit;
        long now = System.currentTimeMillis();
        if (v == null || v.rolled || v.askedAt == 0 || now - v.askedAt > reactWindow()) return;
        v.rolled = true;
        double chance = cfg.getDouble("react.chance", 0.5);
        if (!(v.viewer != null ? m.roll(v.viewer, chance) : ThreadLocalRandom.current().nextDouble() < chance)) return;
        v.reply = reactions.get(ThreadLocalRandom.current().nextInt(reactions.size()));
        v.replyAt = now + between("react.delay-seconds", 3, 6);
        // his next question does not come in the same breath
        if (v.next < questions.size()) v.nextAt = Math.max(v.nextAt, v.replyAt + (v.quick ? 2000L : 8000L));
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
        return eco.guide() == null || !eco.guide().inGuide(p);
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

    @EventHandler(ignoreCancelled = true)
    public void onWhisper(PlayerCommandPreprocessEvent e) {
        Optional<Whispers.Whisper> w = Whispers.parse(e.getMessage(), labels);
        if (w.isEmpty() || !Whispers.to(w.get(), who)) return;
        Player p = e.getPlayer();
        if (!hears(p)) return;
        e.setCancelled(true);
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        p.sendMessage(outgoing(w.get().message()));
        if (now - counted.getOrDefault(u, 0L) >= 3000L) {   // a flood of whispers is one whisper
            counted.put(u, now);
            m.set(this, u, "whispers", m.getLong(this, u, "whispers", 0) + 1);
        }
        if (m.stage(this, u) < 1) m.stage(this, u, 1);
        if (farewells.containsKey(u)) return;            // he is already answering them
        boolean test = m.test(u);
        if (!test && heir != null) return;               // he is answering someone else: silence
        Stones.Where at = where(p);
        if (at == Stones.Where.HIS) {
            farewell(p, false);
            return;
        }
        if (now - answered.getOrDefault(u, 0L) < 1000L * Math.max(0, cfg.getInt("whisper.cooldown-seconds", 20))) return;
        answered.put(u, now);
        String line;
        if (at == Stones.Where.OTHER) {
            line = cfg.getString("whisper.wrong-stone", "…그건 제 게 아니에요.");
        } else {
            long n = m.getLong(this, u, "replies", 0);
            line = evasive.get((int) Math.floorMod(n, (long) evasive.size()));
            m.set(this, u, "replies", n + 1);
        }
        long wait = Math.max(1, between("whisper.reply-delay-seconds", 2, 4) / 50);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline() && open(u) && !farewells.containsKey(u)) p.sendMessage(incoming(line));
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
        boolean real = !preview && !m.test(u);
        if (real) heir = u;
        Location stone = stone().filter(l -> l.getWorld().equals(p.getWorld()) && l.distanceSquared(p.getLocation()) < 32 * 32).orElse(null);
        Location centre = stone == null ? null : centre().orElse(null);
        Farewell f = new Farewell(this, p, stone, centre, preview);
        farewells.put(u, f);
        f.begin();
    }

    /** The ending played to its last line: the only time the server says he left, and the stone is handed on. */
    void done(Farewell f, Player p) {
        UUID u = p.getUniqueId();
        farewells.remove(u, f);
        boolean real = !f.preview && u.equals(heir);
        if (u.equals(heir)) heir = null;
        if (!f.preview && !open(u)) return;   // switched off, or (an admin's doing) solved meanwhile: nothing is said
        Component left = joinLine(false);
        if (real) {
            toAll(left);
            eco.getLogger().info("[비밀] 새로 온 사람 (" + who + ") 이 나갔다고 모두에게 알렸습니다 (처음이자 마지막)");
            visit = null;
        } else {
            p.sendMessage(left);
        }
        if (!f.preview) m.solve(this, p);
    }

    /** Whether this player's own leaving is not announced (the solver; a tester who finished, while still in test mode). */
    private boolean neverLeaves(UUID u) {
        if (!cfg.getBoolean("perk.no-leave-line", true)) return false;
        if (m.solver(this).filter(u::equals).isPresent()) return true;
        return m.test(u) && m.stage(this, u) > stages();
    }

    /** After the nickname module (LOW) has rewritten the line: for the one who inherited "never leaving", there is no line. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        if (neverLeaves(e.getPlayer().getUniqueId())) e.quitMessage(null);
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    /** Test mode: he comes for that player alone, quickly. Otherwise: he comes now, for everyone, as on any night. */
    @Override
    public void start(Player p) {
        UUID u = p.getUniqueId();
        if (m.test(u)) {
            m.set(this, u, "seen", 1);
            arriveFor(p, true);
            return;
        }
        String no = m.closed(this) ? "이미 풀린 비밀이라 그는 다시 오지 않아요 (서버초기화 로 다시 열 수 있음)"
                : visit != null ? "지금 와 있는 중이에요 (질문이 끝난 뒤 다시)"
                : impostor() ? "같은 이름의 실제 플레이어가 접속해 있어요" : null;
        if (no != null) {
            eco.getLogger().info("[비밀] 새로 온 사람 시작: " + no);
            if (p.hasPermission("stsv.admin")) p.sendMessage(Text.msg("<red>새로온사람: " + no));
            return;
        }
        arrive(null, 0);
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

    @Override
    public String status(UUID u) {
        StringBuilder b = new StringBuilder();
        b.append("다녀감 ").append(eco.kv().has(SEEN) ? "<white>예</white>" : m.get(this, u, "seen") != null ? "<white>이 사람에게만</white>" : "<white>아직</white>");
        LocalDateTime t = LocalDateTime.now(SecretModule.KST);
        LocalDate night = Schedule.next(t, from, to);
        String key = night.toString();
        boolean begun = key.equals(eco.kv().get(NIGHT));
        long asked = begun ? eco.kv().getLong(ASKED, 0) : 0;
        b.append(" · ").append(key).append(" 밤 <white>").append(HM.format(Schedule.planned(night, from, to, seed))).append("</white> ")
                .append(m.closed(this) ? "(풀려서 안 옴)" : !begun ? "예정" : asked >= questions.size() ? "다녀감" : visit != null ? "와 있음 " + asked + "/" + questions.size()
                        : "끊김 " + asked + "/" + questions.size() + " (다시 들어옴)");
        Optional<Stones.Found> f = stones.find(id(), m.all());
        Optional<Location> st = stone();
        if (st.isPresent()) {
            Location l = st.get();
            b.append(" · 비석 <white>").append(l.getWorld().getName()).append(String.format(Locale.ROOT, " %.1f %.1f %.1f", l.getX(), l.getY(), l.getZ()))
                    .append("</white>").append(f.get().real() ? "" : " (계산한 자리: 비석이 아직 안 보임)");
        } else {
            b.append(stones.placed() ? " · <red>기념비의 월드가 없음: 끝낼 수 없음</red>" : " · <red>기념비 없음: 끝낼 수 없음 (/관리자 비밀 비석 세우기)</red>");
        }
        b.append(" · 귓속말 <white>").append(m.getLong(this, u, "whispers", 0)).append("</white>회");
        if (farewells.containsKey(u)) b.append(" · 엔딩 중");
        if (neverLeaves(u)) b.append(" · 퇴장 줄 숨김");
        return b.toString();
    }

    @Override
    public List<String> scenes() {
        return List.of("입장", "질문", "귓속말", "엔딩", "퇴장", "비석");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        switch (word) {
            case "입장" -> p.sendMessage(joinLine(true));
            case "질문" -> arriveFor(p, false);
            case "귓속말" -> p.sendMessage(incoming(evasive.get(ThreadLocalRandom.current().nextInt(evasive.size()))));
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
