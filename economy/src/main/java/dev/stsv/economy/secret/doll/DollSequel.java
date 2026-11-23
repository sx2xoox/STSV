package dev.stsv.economy.secret.doll;

import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SequelState;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 후속 임무 「인형의 심부름」 (docs/admin/숨은_비밀/주인_없는_경매품.md "후속 임무"; doll.yml {@code sequel}). About once a week
 * the doll on the solver's shoulder ({@link Companion}) whispers who it would like to see, as one of the secret's riddles
 * ({@link Riddles}: "the one with the most land", "the one who was deepest just now" …) read off the players who are online
 * at that moment. The solver finds that player and stands beside them for a few seconds; the doll turns its head to them,
 * bows a little, says one line about them to the solver alone, and that is all. The other player does nothing and is told
 * nothing. If the answer changes while the solver is looking, the doll says so and the new answer counts.
 * <p>
 * Nothing is held and nothing is given but the reward, so there is nothing to give back. Notes of the going mission
 * ({@code sequel.x.}): kind (the riddle), told (who it named, ids), asked (ms: when it was last told; the hints count from
 * it), hint (0..2), turn (riddles re-asked this week), alone. Kept ({@code sequel.keep.}): recent (the last riddles),
 * recent-lines (the last remarks).
 */
public final class DollSequel extends Sequel {

    private static final String VOICE = "<#c9b8a6>";
    /** The riddles as the errand words them (doll.yml {@code sequel.riddles} has the same; a missing one falls back here). */
    private static final Map<String, String> RIDDLES = Map.ofEntries(
            Map.entry("land", "이 세상에서 땅을 가장 넓게 가진 사람. 그렇게 넓은 데서는 길을 안 잃을까."),
            Map.entry("late", "방금, 가장 마지막으로 문을 열고 들어온 사람. 바깥 냄새가 아직 묻어 있을 거야."),
            Map.entry("early", "오늘 가장 오래 여기 머문 사람. 졸린 얼굴이 보고 싶어."),
            Map.entry("friend", "네 곁에 가장 오래 붙어 있던 사람. 너한테서 그 사람 냄새가 나."),
            Map.entry("fish", "물속에서 가장 큰 것을 끌어 올린 사람. 그 손이 얼마나 큰지 보고 싶어."),
            Map.entry("long", "이름이 가장 긴 사람. 끝까지 한 번 불러 보고 싶어."),
            Map.entry("deep", "방금, 가장 깊은 곳에 있던 사람. 어두운 데서 뭘 봤는지 궁금해."),
            Map.entry("high", "방금, 가장 높은 곳에 있던 사람. 거기서는 내가 보였을까."),
            Map.entry("far", "방금 너에게서 가장 멀리 있던 사람. 멀리 있는 사람이 제일 궁금해."),
            Map.entry("rich", "돈을 가장 많이 가진 사람. 주머니가 무거우면 어깨가 어느 쪽으로 기우는지 보고 싶어."),
            Map.entry("poor", "가진 게 가장 적은 사람. 가벼운 사람은 걸음 소리가 좋아."),
            Map.entry("old", "이 세상에 가장 먼저 와 있던 사람. 나보다 오래된 사람이 있다니."),
            Map.entry("new", "이 세상에 가장 늦게 온 사람. 아직 길을 모르는 얼굴이 보고 싶어."),
            Map.entry("stranger", "누구든 좋아. 너 말고 다른 얼굴이 보고 싶어."));
    private static final List<String> OPENERS = List.of("…심부름 하나만 해 줘. 보고 싶은 사람이 있어.");
    private static final List<String> REMARKS = List.of("…{name}이(가) 지나간 자리는 한참 따뜻해.",
            "…{name}은(는) 나를 못 봤어. 그래도 괜찮아. 나는 봤으니까.");

    private final Doll d;
    /** solver -> the one they stand beside now, and for how many seconds in a row */
    private final Map<UUID, UUID> besideWho = new HashMap<>();
    private final Map<UUID, Integer> beside = new HashMap<>();
    /** solver -> who counts now (read off the records every other second) */
    private final Map<UUID, Set<UUID>> targets = new HashMap<>();
    /** solver -> when the doll last said the answer changed (ms), and whether its head is turned to someone in range */
    private final Map<UUID, Long> saidChanged = new HashMap<>();
    private final Map<UUID, Boolean> inRange = new HashMap<>();
    /** solver -> seconds of the going errand ticked (each player their own, so two errands at once do not share one beat) */
    private final Map<UUID, Integer> clocks = new HashMap<>();

    DollSequel(Doll d) {
        super(d);
        this.d = d;
    }

    @Override
    public String id() {
        return "인형의 심부름";
    }

    // ------------------------------------------------------------------------------------------
    // words
    // ------------------------------------------------------------------------------------------

    private static String esc(String s) {
        return MiniMessage.miniMessage().escapeTags(s == null ? "" : s);
    }

    /** A line of doll.yml {@code sequel.lines.<key>} as it is (no server prefix: the doll speaks, not the server). */
    private void say(Player p, String key, String def, String... pairs) {
        String v = line("lines." + key, def, pairs);
        if (!v.isEmpty()) p.sendMessage(Text.mm(v));
    }

    private List<String> pool(String key, List<String> def) {
        List<String> l = cfg().getStringList(key);
        return l.isEmpty() ? def : l;
    }

    private String riddleText(Riddles.Kind k) {
        return cfg().getString("riddles." + k.id(), RIDDLES.getOrDefault(k.id(), "…"));
    }

    private void creak(Player p, float volume, float pitch) {
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, SoundCategory.AMBIENT, volume, pitch);
    }

    // ------------------------------------------------------------------------------------------
    // the doll on the shoulder
    // ------------------------------------------------------------------------------------------

    /** Whether the doll can speak to them now: awake on its owner's shoulder (a tester who is not its owner borrows it). */
    private boolean ready(Player p) {
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return false;
        UUID u = p.getUniqueId();
        if (u.equals(d.companion().owner())) return d.companion().awake();
        return m.test(u);
    }

    /** A tester who is not the doll's owner sees it on their own shoulder while the errand goes on. */
    private void shoulder(Player p) {
        UUID owner = d.companion().owner();
        if (p.getUniqueId().equals(owner)) return;
        // never off its real owner's shoulder: a tester only borrows it while the owner is away or has it asleep
        if (owner != null && Bukkit.getPlayer(owner) != null && d.companion().awake()) return;
        d.companion().hold(p, 6);
    }

    // ------------------------------------------------------------------------------------------
    // beginning
    // ------------------------------------------------------------------------------------------

    @Override
    protected boolean offer(Player p) {
        if (!ready(p) || !chance(p, "chance", 0.004)) return false;
        if (d.others(p).isEmpty()) return false;   // nobody to want to see: it waits
        start(p);
        return true;
    }

    @Override
    protected void start(Player p) {
        shoulder(p);
        if (!ask(p, true)) say(p, "nobody", VOICE + "…보고 싶은 사람이 있는데, 지금은 아무도 없네. 누가 올 때까지 기다릴게.");
    }

    /**
     * Picks the riddle from who is online now and whispers it. intro: the first telling (the errand's opening line before
     * it). False when nobody else is online (nothing is picked: it waits).
     */
    private boolean ask(Player p, boolean intro) {
        UUID u = p.getUniqueId();
        List<Riddles.Fact> facts = d.others(p);
        List<Riddles.Kind> recent = Errand.kinds(kept(u, "recent", ""));
        long seed = Errand.seed(week(u), u) + getLong(u, "turn", 0) * 7919L;
        Riddles.Kind k = Errand.choose(seed, recent, facts);
        if (k == null) return false;
        Set<UUID> now = Riddles.answers(k, facts);
        set(u, "kind", k.name());
        set(u, "told", Errand.join(now));
        set(u, "asked", System.currentTimeMillis());
        remove(u, "hint");
        remove(u, "alone");
        keep(u, "recent", Errand.join(Errand.remember(recent, k, Math.max(0, cfg().getInt("recent", 4)))));
        targets.put(u, now);
        beside.remove(u);
        besideWho.remove(u);
        accept(p);
        creak(p, 0.35f, 0.7f);
        if (intro) {
            say(p, "lean", "<dark_gray><i>(어깨 위의 인형이 네 귀에 입을 댄다)");
            List<String> open = pool("openers", OPENERS);
            int at = Errand.line(seed, open.size(), List.of());
            if (at >= 0) p.sendMessage(Text.mm(VOICE + open.get(at)));
        }
        p.sendMessage(Text.mm(line("lines.riddle", VOICE + "…{riddle}", "riddle", riddleText(k))));
        if (intro) say(p, "how", "<dark_gray>(그 사람 곁에 잠시 서 있으면 된다. 그 사람은 아무것도 하지 않아도 된다)");
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // each second
    // ------------------------------------------------------------------------------------------

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        if (!ready(p)) {   // asleep (or dead): it says nothing and counts nothing until it is awake again
            beside.remove(u);
            besideWho.remove(u);
            return;
        }
        shoulder(p);
        int clock = clocks.merge(u, 1, Integer::sum);
        if (state(u) == SequelState.State.OFFERED) {   // begun with nobody else online: asked as soon as someone is
            if (clock % 3 == 0) ask(p, true);
            return;
        }
        Riddles.Kind k = Riddles.Kind.of(get(u, "kind"));
        if (k == null) {
            ask(p, true);
            return;
        }
        Set<UUID> t = targets.get(u);
        if (t == null || clock % 2 == 0) t = refresh(p, k);
        if (t.isEmpty()) {
            beside.remove(u);
            besideWho.remove(u);
            return;
        }
        hints(p, t);
        stand(p, t);
    }

    /** Reads who counts now; says so when the answer changed, asks another riddle when this one names nobody any more. */
    private Set<UUID> refresh(Player p, Riddles.Kind k) {
        UUID u = p.getUniqueId();
        List<Riddles.Fact> facts = d.others(p);
        if (facts.isEmpty()) {
            if (get(u, "alone") == null) {
                set(u, "alone", 1);
                say(p, "alone", VOICE + "…다 가 버렸네. 누가 올 때까지 기다릴게.");
            }
            targets.put(u, Set.of());
            return Set.of();
        }
        remove(u, "alone");
        Set<UUID> told = Errand.ids(get(u, "told"));
        Set<UUID> now = Errand.targets(k, told, facts);
        if (now.isEmpty()) {   // nobody answers this riddle any more (the only one with land left): another riddle
            set(u, "turn", getLong(u, "turn", 0) + 1);
            say(p, "gone", VOICE + "…그 사람, 가 버렸네. 그럼 다른 사람.");
            if (!ask(p, false)) targets.put(u, Set.of());
            return targets.getOrDefault(u, Set.of());
        }
        if (!now.equals(told)) {
            if (Errand.changed(told, now)) {
                long ms = System.currentTimeMillis();
                if (ms - saidChanged.getOrDefault(u, 0L) >= Math.max(0, cfg().getLong("changed-gap-seconds", 30)) * 1000L) {
                    saidChanged.put(u, ms);
                    say(p, "changed", VOICE + "…아, 달라졌어. 이제 그 사람이 아니야. 다시 찾아 줘.");
                    creak(p, 0.3f, 0.6f);
                }
                set(u, "asked", ms);   // the hints wait again: it is a new person
                remove(u, "hint");
            }
            set(u, "told", Errand.join(now));
        }
        targets.put(u, now);
        return now;
    }

    /** After a while it helps: the first letter of a name, later the name. */
    private void hints(Player p, Set<UUID> t) {
        UUID u = p.getUniqueId();
        int hint = (int) getLong(u, "hint", 0);
        if (hint >= 2) return;
        long waited = (System.currentTimeMillis() - getLong(u, "asked", since(u))) / 1000L;
        if (waited < seconds(u, hint == 0 ? "hint-first" : "hint-name", hint == 0 ? 240 : 540)) return;
        Player who = null;
        for (UUID x : t) if ((who = Bukkit.getPlayer(x)) != null) break;
        if (who == null) return;
        set(u, "hint", hint + 1);
        creak(p, 0.3f, 0.75f);
        if (hint == 0) say(p, "hint-first", VOICE + "…못 찾겠어? 이름이 <white>{letter}</white>(으)로 시작해.", "letter", esc(Errand.initial(Names.plain(who))));
        else say(p, "hint-name", VOICE + "…<white>{name}</white>. 그 사람이야.", "name", esc(Names.plain(who)));
    }

    /** Standing beside one of them: the doll looks at them, and after `stay` seconds in a row it bows. */
    private void stand(Player p, Set<UUID> t) {
        UUID u = p.getUniqueId();
        double radius = cfg().getDouble("radius", 4), look = cfg().getDouble("look-range", 20);
        Location me = p.getLocation();
        Player by = null, seen = null;
        double best = Double.MAX_VALUE;
        for (UUID x : t) {
            Player o = Bukkit.getPlayer(x);
            if (o == null || o == p || !o.getWorld().equals(p.getWorld()) || o.isDead() || o.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = o.getLocation();
            double dist = l.distanceSquared(me);
            if (dist >= best) continue;
            best = dist;
            seen = dist <= look * look ? o : null;
            by = Errand.beside(l.getX() - me.getX(), l.getY() - me.getY(), l.getZ() - me.getZ(), radius) ? o : null;
        }
        // near enough to be seen: its head turns that way (the only pointer it gives before the hints)
        if (seen != null) {
            if (d.companion().on(u)) d.companion().look(seen, 1600);
            if (!Boolean.TRUE.equals(inRange.put(u, true))) creak(p, 0.25f, 0.55f);
        } else inRange.remove(u);
        if (by == null) {
            beside.remove(u);
            besideWho.remove(u);
            return;
        }
        if (!by.getUniqueId().equals(besideWho.put(u, by.getUniqueId()))) beside.remove(u);
        int n = beside.merge(u, 1, Integer::sum);
        if (n == 1) m.haunt().whisper(p, line("lines.found", VOICE + "…저 사람이야. 조금만 더 있어 줘."));
        if (n >= seconds(u, "stay", 10)) finish(p, by);
    }

    /** It saw them: the head turns, the small bow, one line about them, the reward. */
    private void finish(Player p, Player who) {
        UUID u = p.getUniqueId();
        if (!live(u)) return;
        String name = esc(Names.plain(who));
        bow(p, who);
        say(p, "bow", "<dark_gray><i>(끼익. 인형이 {name} 쪽으로 고개를 돌리고, 아주 조금 숙인다)", "name", name);
        List<String> remarks = pool("remarks", REMARKS);
        List<Integer> recent = Errand.numbers(kept(u, "recent-lines", ""));
        int at = Errand.line(Errand.seed(week(u), u) + count(u), remarks.size(), recent);
        if (at >= 0) {
            String remark = VOICE + remarks.get(at).replace("{name}", name);
            p.sendMessage(Text.mm(remark));
            m.haunt().whisper(p, remark);
            keep(u, "recent-lines", Errand.join(Errand.remember(recent, at, Math.min(remarks.size() - 1, Math.max(0, cfg().getInt("recent-remarks", 6))))));
        }
        say(p, "thanks", VOICE + "…됐어. 봤어. 고마워.");
        forget(u);
        complete(p);
    }

    /** The head to them and the nod; the creak is the solver's alone (the doll itself is seen by everyone, as always). */
    private void bow(Player p, Player who) {
        if (d.companion().on(p.getUniqueId())) {   // only the doll on their own shoulder: a tester never moves the owner's
            d.companion().look(who, 5000);
            d.companion().bow();
        }
        creak(p, 0.5f, 0.5f);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) creak(p, 0.4f, 0.62f);
        }, 24L);
    }

    private void forget(UUID u) {
        beside.remove(u);
        besideWho.remove(u);
        targets.remove(u);
        clocks.remove(u);
        saidChanged.remove(u);
        inRange.remove(u);
    }

    // ------------------------------------------------------------------------------------------
    // join, quit, the week's end
    // ------------------------------------------------------------------------------------------

    @Override
    protected void resume(Player p) {
        UUID u = p.getUniqueId();
        Riddles.Kind k = Riddles.Kind.of(get(u, "kind"));
        if (state(u) != SequelState.State.ACTIVE || k == null || !ready(p)) return;
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!p.isOnline() || !live(u) || !k.name().equals(get(u, "kind"))) return;
            say(p, "again", "<dark_gray><i>(어깨 위의 인형이 다시 속삭인다)");
            p.sendMessage(Text.mm(line("lines.riddle", VOICE + "…{riddle}", "riddle", riddleText(k))));
        }, 60L);
    }

    @Override
    protected void cleanup(UUID u) {
        forget(u);   // the doll itself is the secret's lasting reward: it stays where it belongs
    }

    @Override
    protected void expired(UUID u) {
        // nothing is held: the doll simply stops wanting
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    private static String names(Set<UUID> ids) {
        List<String> out = new ArrayList<>();
        for (UUID x : ids) out.add(esc(Names.plain(x)));
        return out.isEmpty() ? "-" : String.join(", ", out);
    }

    @Override
    public String status(UUID u) {
        SequelState.State st = state(u);
        if (!st.live()) return "인형이 깨어 있고 다른 사람이 접속해 있으면 초마다 확률 " + cfg().getDouble("chance", 0.004);
        Player p = Bukkit.getPlayer(u);
        String asleep = p != null && !ready(p) ? " · <yellow>인형이 잠들어 있어 멈춤 (깨우면 이어짐)</yellow>" : "";
        if (st == SequelState.State.OFFERED) return "다른 사람이 아무도 없어 기다리는 중 (누가 접속하면 수수께끼를 속삭임)" + asleep;
        Riddles.Kind k = Riddles.Kind.of(get(u, "kind"));
        Set<UUID> t = targets.containsKey(u) ? targets.get(u) : Errand.ids(get(u, "told"));
        long stay = seconds(u, "stay", 10);
        return "수수께끼 " + (k == null ? "-" : k.id()) + " · 지금 맞는 사람: <white>" + names(t) + "</white> · 할 일: 그 사람 "
                + cfg().getDouble("radius", 4) + "칸 안에 " + stay + "초 서 있기 (지금 " + beside.getOrDefault(u, 0) + "초) · 힌트 " + getLong(u, "hint", 0) + "/2"
                + (get(u, "alone") != null ? " · 지금은 아무도 없음" : "") + asleep;
    }

    @Override
    public List<String> scenes() {
        return List.of("속삭임", "인사", "한마디");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        switch (word) {
            case "속삭임" -> {   // the opening and a riddle as it would be asked now (nothing is saved)
                List<Riddles.Fact> facts = d.others(p);
                Riddles.Kind k = Errand.choose(System.nanoTime(), List.of(), facts);
                if (k == null) k = Riddles.Kind.STRANGER;
                shoulderFor(p);
                creak(p, 0.35f, 0.7f);
                say(p, "lean", "<dark_gray><i>(어깨 위의 인형이 네 귀에 입을 댄다)");
                List<String> open = pool("openers", OPENERS);
                p.sendMessage(Text.mm(VOICE + open.get((int) (System.nanoTime() % open.size()))));
                p.sendMessage(Text.mm(line("lines.riddle", VOICE + "…{riddle}", "riddle", riddleText(k))));
                admin.sendMessage(Text.mm("<gray>" + k.id() + " → 지금 맞는 사람: " + names(Riddles.answers(k, facts))));
            }
            case "인사" -> {   // the bow toward the nearest other player (toward themselves when alone)
                Player who = p;
                double best = Double.MAX_VALUE;
                for (Player o : p.getWorld().getPlayers()) {
                    double dist = o.getLocation().distanceSquared(p.getLocation());
                    if (o != p && dist < best) {
                        best = dist;
                        who = o;
                    }
                }
                shoulderFor(p);
                bow(p, who);
                say(p, "bow", "<dark_gray><i>(끼익. 인형이 {name} 쪽으로 고개를 돌리고, 아주 조금 숙인다)", "name", esc(Names.plain(who)));
            }
            case "한마디" -> {
                List<String> remarks = pool("remarks", REMARKS);
                String remark = VOICE + remarks.get((int) (System.nanoTime() % remarks.size())).replace("{name}", esc(Names.plain(p)));
                p.sendMessage(Text.mm(remark));
                m.haunt().whisper(p, remark);
            }
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] 후속 " + word + " → " + p.getName() + (d.companion().on(u) ? "" : " <yellow>(어깨에 인형이 없어 몸짓은 안 보임)")));
        return true;
    }

    /** For a scene: the doll on their shoulder for half a minute when it is not there. */
    private void shoulderFor(Player p) {
        if (!d.companion().on(p.getUniqueId())) d.companion().preview(p, 30);
    }
}
