package dev.stsv.economy.secret.usher;

import dev.stsv.core.util.Text;
import dev.stsv.economy.guide.GuideLayout;
import dev.stsv.economy.guide.GuideModule;
import dev.stsv.economy.guide.GuideSpec;
import dev.stsv.economy.secret.Memory;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 안내원 (docs/admin/숨은_비밀/안내원.md; contents/economy/secrets/usher.yml). The 길라잡이 체험관's 안내원 was a person once,
 * and has guided for so long that it no longer knows which of it is the real one.
 * <p>
 * Someone who finished every guide topic talks to a 안내원 at dawn (KST 1–4) and it says one slightly wrong thing, a question
 * with one word hidden in it ({@code questions} in the settings: its own name backwards, how many topics, the first and the
 * last number, the land's size, the 체험관's name backwards). Answered in chat within 30 s (loosely), it tells them their own
 * records, then asks them to look once more, backwards; a wrong answer or silence, "…아니에요. 잊어 주세요." and the usual
 * menu (another try the next time they talk to it, after a minute). They are let into the 체험관 (as a visitor: their things kept aside) and walk
 * the thirteen stations from the guide's last number to its first; each station has one thing wrong that only they see
 * ({@link Pictures}). Out of turn, the picture goes ("…아니에요.") until the right one is found. With each one found the
 * 안내원 stands a block closer, at the edge of their sight. The seventh gives a note: "진짜는 자기 말을 한다." All thirteen
 * open stairs in the plaza floor, to a room under it ({@link UsherPlan#room}) with two 안내원: one says its own lines, the
 * other says what the player typed in chat today. One choice. The true one: the statue ({@link Statue}), the title, the
 * money. The false one: "이제 제가 안내할게요" — failed for good, and from then on every 안내원 they meet wears their words.
 * <p>
 * Stage (eco.kv "secret.usher.&lt;uuid&gt;.stage") = found + 1: 1 = started, 14 = all found (the room). Also: asked (the
 * index of the last question), real (left/right), chose, failed, said (today's chat lines).
 */
public final class Usher extends Secret {

    static final NamespacedKey NPC = new NamespacedKey("economy", "guide_npc");

    /** One player's walk: what they see now. */
    private static final class Walk {
        final UUID u;
        final Map<String, Pictures.Picture> pics = new HashMap<>();
        /** Out-of-turn pictures taken away until the next right one. */
        final Set<String> hidden = new HashSet<>();
        Props shadow;
        Mannequin usher;
        int side = 1;
        /** The room: the opening, the two, their bubbles and click boxes. */
        Props room;
        Mannequin left, right;
        TextDisplay leftSays, rightSays;
        Interaction leftHit, rightHit;
        boolean inRoom, ending;
        int frame, beat, nextBeat;
        /** Admin preview: every picture shown, clicks only name them; the room's choice decides nothing. */
        boolean preview, roomPreview;
        /** Shown in the 체험관 at least once (a preview made outside waits for them to come in). */
        boolean visited;

        Walk(UUID u) {
            this.u = u;
        }
    }

    private final Map<UUID, Walk> walks = new ConcurrentHashMap<>();
    private final Set<UUID> intro = ConcurrentHashMap.newKeySet();

    /** A question waiting for its answer (in chat): which one, where the 안내원 stood, the silence timer. */
    private record Ask(int index, List<String> answers, Location npc, boolean preview, BukkitTask timeout) {}

    private final Map<UUID, Ask> asking = new ConcurrentHashMap<>();
    /** Not asked again before (millis): a wrong answer or silence waits a minute. */
    private final Map<UUID, Long> quietUntil = new ConcurrentHashMap<>();
    /** This session's chat lines (the fallback for today's). */
    private final Map<UUID, List<String>> session = new ConcurrentHashMap<>();
    /** Failed players: the 안내원 they see instead of each real one (real one's id -> the stand-in). */
    private final Map<UUID, Map<UUID, Mannequin>> masks = new ConcurrentHashMap<>();
    private final Map<UUID, Props> maskProps = new ConcurrentHashMap<>();
    /** Admin scenes' things, per player. */
    private final Map<UUID, Props> scenes = new ConcurrentHashMap<>();
    /** Blocks the room replaced (to put back): key -> what was there. */
    private final Map<Long, BlockData> roomWas = new LinkedHashMap<>();
    private boolean roomBuilt;
    private Statue statue;
    private Pictures pictures;
    private List<String> order = List.of();
    private BukkitTask task;
    private long ticks;

    public Usher(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "usher";
    }

    @Override
    public String name() {
        return "안내원";
    }

    @Override
    public int stages() {
        return UsherPlan.PICTURES + 1;
    }

    @Override
    protected void loaded() {
        statue = new Statue(eco);
        pictures = null;
        order = List.of();
    }

    // ------------------------------------------------------------------------------------------
    // settings and words
    // ------------------------------------------------------------------------------------------

    private String line(String key, String def) {
        return cfg.getString("lines." + key, def);
    }

    private List<String> lines(String key, List<String> def) {
        List<String> l = cfg.getStringList("lines." + key);
        return l.isEmpty() ? def : l;
    }

    private String skin() {
        String s = cfg.getString("skin", "");
        return s == null || s.isBlank() ? eco.getConfig().getString("guide.npc-skin") : s;
    }

    private Pictures pictures() {
        if (pictures == null) {
            String w = cfg.getString("worker-skin", "");
            if (w == null || w.isBlank()) w = eco.getConfig().getString("guide.passenger-skin");
            pictures = new Pictures(eco, m.haunt(), w);
        }
        return pictures;
    }

    /** The walk: the guide's numbering backwards. */
    private List<String> order() {
        if (order.size() != GuideLayout.stations().size() || ticks % 60 == 0) {
            List<String> menu = new ArrayList<>();
            GuideModule g = eco.guide();
            if (g != null) for (GuideSpec.Topic t : g.topics().values()) menu.add(t.station());
            List<String> layout = new ArrayList<>();
            for (GuideLayout.Station s : GuideLayout.stations()) layout.add(s.id());
            order = UsherPlan.order(menu, layout);
        }
        return order;
    }

    private World guideWorld() {
        GuideModule g = eco.guide();
        return g == null ? null : g.guideWorld();
    }

    private boolean inGuide(Player p) {
        World w = guideWorld();
        return w != null && p.getWorld().equals(w);
    }

    private static String esc(String s) {
        return MiniMessage.miniMessage().escapeTags(s);
    }

    /** The 안내원 speaking to them (a chat line only they see) with a soft sound from where it stands. */
    private void speak(Player p, String line, Location from) {
        p.sendMessage(Text.mm(cfg.getString("speaker", "<gold>길라잡이 안내원") + " <dark_gray>»</dark_gray> <white>" + line));
        Location at = from == null ? p.getLocation() : from;
        if (at.getWorld() == p.getWorld()) p.playSound(at, Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.AMBIENT, 0.35f, 0.6f);
    }

    /** Its own name (usher.yml name), and "이에요"/"예요" fitted to it: {name}, {이에요}. */
    private String named(String l) {
        String n = cfg.getString("name", "윤해담");
        return l.replace("{name}", esc(n)).replace("{이에요}", UsherPlan.copula(n));
    }

    /**
     * After it was found: now and then (remember-chance, at most once a day per player) it tells anyone who talks to it who
     * found it and its name, before the usual menu. Everyone once someone solved it for real; a tester who solved it in test
     * mode hears it themselves (always). True when it spoke (the menu follows).
     */
    private boolean remember(Player p, Location npc) {
        UUID u = p.getUniqueId();
        UUID by;
        if (m.closed(this)) by = m.solver(this).orElse(null);
        else if (m.test(u) && m.stage(this, u) > stages()) by = u;
        else return false;
        if (by == null) return false;
        String today = m.today(u);
        if (today.equals(m.get(this, u, "remember-day")) || !m.roll(u, cfg.getDouble("remember-chance", 0.1))) return false;
        String l = line("remember", "");
        if (l.isEmpty()) return false;
        m.set(this, u, "remember-day", today);
        String who = Optional.ofNullable(Bukkit.getOfflinePlayer(by).getName()).orElse("누군가");
        speak(p, named(l.replace("{player}", esc(who))), npc);
        GuideModule g = eco.guide();
        if (g != null) Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) g.open(p);
        }, 40L);
        return true;
    }

    private boolean failed(UUID u) {
        return m.get(this, u, "failed") != null;
    }

    private int found(UUID u) {
        return UsherPlan.found(m.stage(this, u));
    }

    /** Still walking: started, not done, not failed, and the secret still open to them. */
    private boolean walking(UUID u) {
        int st = m.stage(this, u);
        return st >= 1 && st <= stages() && !failed(u) && open(u);
    }

    // ------------------------------------------------------------------------------------------
    // what they said (the false 안내원's words)
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        UUID u = e.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(eco, () -> heard(u, text));
    }

    private void heard(UUID u, String text) {
        if (!on() || text.isBlank()) return;
        int st = m.stage(this, u);
        boolean listening = failed(u) || st >= 1 && st <= stages() || m.test(u) || eco.kv().has("guide.all." + u);
        if (!listening) return;
        List<String> mine = session.computeIfAbsent(u, k -> new ArrayList<>());
        mine.add(text.strip().length() > 40 ? text.strip().substring(0, 40) + "…" : text.strip());
        while (mine.size() > 12) mine.removeFirst();
        m.set(this, u, "said", UsherPlan.addSaid(m.get(this, u, "said"), m.today(u), text, 12));
    }

    /** Their words: today's, else this session's (maybe none). */
    private List<String> words(UUID u) {
        List<String> today = UsherPlan.said(m.get(this, u, "said"), m.today(u));
        if (!today.isEmpty()) return today;
        return List.copyOf(session.getOrDefault(u, List.of()));
    }

    // ------------------------------------------------------------------------------------------
    // the beginning: a 안내원 who knows them
    // ------------------------------------------------------------------------------------------

    /** Right-click on any 안내원 (before the guide opens its picker). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        Entity c = e.getRightClicked();
        // a failed player's stand-in
        Map<UUID, Mannequin> mine = masks.get(u);
        if (mine != null && c instanceof Mannequin && mine.containsValue(c)) {
            e.setCancelled(true);
            maskTalk(p, c.getLocation());
            return;
        }
        if (c instanceof Interaction hit) {
            if (clicked(p, hit)) e.setCancelled(true);
            return;
        }
        if (!c.getPersistentDataContainer().has(NPC) || !on()) return;
        if (intro.contains(u) || asking.containsKey(u)) {
            e.setCancelled(true);
            return;
        }
        if (failed(u)) {
            e.setCancelled(true);
            maskTalk(p, c.getLocation());
            return;
        }
        int st = m.stage(this, u);
        if (st >= 1 && st <= stages() && open(u)) {
            e.setCancelled(true);
            resume(p, c.getLocation());
            return;
        }
        if (st == 0 && eligible(p)) {
            if (ask(p, c.getLocation(), false)) e.setCancelled(true);
            return;
        }
        if (remember(p, c.getLocation())) e.setCancelled(true);
    }

    /** Every topic done (or testing), dawn (any hour in test mode), and not just asked (a minute after the last try). */
    private boolean eligible(Player p) {
        UUID u = p.getUniqueId();
        if (!open(u)) return false;
        if (cfg.getBoolean("require-guide", true) && !m.test(u) && !eco.kv().has("guide.all." + u)) return false;
        if (!m.hours(u, cfg.getInt("hours.from", 1), cfg.getInt("hours.to", 4))) return false;
        return System.currentTimeMillis() >= quietUntil.getOrDefault(u, 0L);
    }

    // ------------------------------------------------------------------------------------------
    // the question: one slightly wrong line, one hidden word
    // ------------------------------------------------------------------------------------------

    /** The questions from the settings (each a line and its answers; a line without answers is skipped). */
    private List<Map.Entry<String, List<String>>> questions() {
        List<Map.Entry<String, List<String>>> out = new ArrayList<>();
        for (Map<?, ?> q : cfg.getMapList("questions")) {
            Object line = q.get("line");
            Object ans = q.get("answers");
            List<String> answers = new ArrayList<>();
            if (ans instanceof List<?> l) {
                for (Object a : l) if (a != null && !String.valueOf(a).isBlank()) answers.add(String.valueOf(a));
            } else if (ans != null && !String.valueOf(ans).isBlank()) {
                answers.add(String.valueOf(ans));
            }
            if (line != null && !answers.isEmpty()) out.add(Map.entry(String.valueOf(line), List.copyOf(answers)));
        }
        return out;
    }

    /** Asks them the next question (rotating per player) and waits for the answer in chat. False if there is none to ask. */
    private boolean ask(Player p, Location npc, boolean preview) {
        UUID u = p.getUniqueId();
        List<Map.Entry<String, List<String>>> qs = questions();
        if (qs.isEmpty()) return false;
        int i = UsherPlan.nextQuestion(m.get(this, u, "asked"), qs.size(), ThreadLocalRandom.current().nextInt(qs.size()));
        m.set(this, u, "asked", i);
        Map.Entry<String, List<String>> q = qs.get(i);
        long wait = 20L * Math.max(5, cfg.getInt("question.seconds", 30));
        BukkitTask silence = Bukkit.getScheduler().runTaskLater(eco, () -> judge(u, null), wait);
        Ask old = asking.put(u, new Ask(i, q.getValue(), npc == null ? null : npc.clone(), preview, silence));
        if (old != null) old.timeout().cancel();
        speak(p, q.getKey(), npc);
        return true;
    }

    /** Whatever they say next in chat, while asked, is the answer: only the 안내원 hears it (and they see it, quietly). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAnswer(AsyncChatEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        if (!asking.containsKey(u)) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(eco, () -> {
            Player p = Bukkit.getPlayer(u);
            if (p != null && asking.containsKey(u)) p.sendMessage(Text.mm("<dark_gray><i>(작게) " + esc(text)));
            judge(u, text);
        });
    }

    /** The answer (null = silence): right, the records and the way in; else "…아니에요. 잊어 주세요." and the menu. */
    private void judge(UUID u, String typed) {
        Ask a = asking.remove(u);
        if (a == null) return;
        a.timeout().cancel();
        Player p = Bukkit.getPlayer(u);
        if (p == null) return;
        boolean right = typed != null && UsherPlan.answers(typed, a.answers());
        if (right && a.preview()) {
            p.sendMessage(Text.mm("<gray>[연출] 정답 — 실제라면 여기서 기록을 말하고 체험관으로 데려감 (진행은 안 바뀜)"));
            sceneRecords(p);
            return;
        }
        if (right && m.stage(this, u) == 0 && open(u) && !failed(u)) {
            intro.add(u);   // no second question in the pause
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                Player q = Bukkit.getPlayer(u);
                if (q != null && m.stage(this, u) == 0) begin(q, a.npc());
                else intro.remove(u);
            }, 20L);
            return;
        }
        if (!a.preview()) quietUntil.put(u, System.currentTimeMillis() + 1000L * Math.max(0, cfg.getInt("question.cooldown", 60)));
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            Player q = Bukkit.getPlayer(u);
            if (q == null) return;
            speak(q, line("forget", "…아니에요. 잊어 주세요."), a.npc());
            GuideModule g = eco.guide();
            if (g != null) Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (q.isOnline()) g.open(q);
            }, 30L);
        }, 15L);
    }

    @Override
    public void start(Player p) {
        begin(p, null);
    }

    /** It tells them their records, asks, and takes them in. */
    private void begin(Player p, Location npc) {
        UUID u = p.getUniqueId();
        m.stage(this, u, 1);
        m.remove(this, u, "chose");
        m.remove(this, u, "failed");
        intro.add(u);
        records(p, lines -> {
            List<String> all = new ArrayList<>(lines);
            all.addAll(lines("after", List.of("저는 다 기억해요. 안내하는 사람이니까요.", "그런데 하나가 기억이 안 나요.", "제가… 누구였는지.")));
            all.add(line("ask", "거꾸로 한 번만 더 봐 주세요. 제가 어디서 잘못됐는지."));
            say(p, all, npc, 0, () -> {
                intro.remove(u);
                enter(p, true);
            });
        });
    }

    /** Lines one by one (about 2.5 s apart), then next. */
    private void say(Player p, List<String> all, Location from, int i, Runnable next) {
        UUID u = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            Player q = Bukkit.getPlayer(u);
            if (q == null) {
                intro.remove(u);
                return;
            }
            if (i >= all.size()) {
                next.run();
                return;
            }
            speak(q, all.get(i), from);
            say(q, all, from, i + 1, next);
        }, i == 0 ? 10L : 50L);
    }

    /** What it knows of them: where they last left, who they are often with, what they sell most. */
    private void records(Player p, java.util.function.Consumer<List<String>> then) {
        UUID u = p.getUniqueId();
        Memory mem = m.memory();
        List<String> out = new ArrayList<>();
        out.add(line("open", "…아, 오셨네요."));
        Optional<Memory.Place> quit = mem.lastQuit(u);
        quit.ifPresent(q -> out.add(line("quit", "{when} {place} 근처에서 나가셨죠.")
                .replace("{when}", UsherPlan.when(mem.lastQuitAt(u), System.currentTimeMillis()))
                .replace("{place}", place(q))));
        List<String> friends = mem.friends(u);
        if (!friends.isEmpty()) out.add(line("friend", "{friend}님이랑 친하시죠. 늘 곁에 계시던데.").replace("{friend}", esc(friends.getFirst())));
        mem.mostSold(u, sold -> {
            sold.ifPresent(id -> out.add(line("sold", "{item}, 그걸 제일 많이 파시더라고요.").replace("{item}", Items.mmName(id) + "<white>")));
            if (out.size() == 1) out.add(line("none", "…처음 뵙는 것 같은데. 이상하네요. 다 아는 얼굴 같아요."));
            then.accept(out);
        });
    }

    /** "마을 x 120, z -40": the world's name from the settings (worlds.<name>), coordinates in tens. */
    private String place(Memory.Place q) {
        String w = cfg.getString("worlds." + q.world(), q.world());
        return w + " x " + UsherPlan.rough(q.x()) + ", z " + UsherPlan.rough(q.z());
    }

    /** Talked to it again mid-way: a word, then back in. */
    private void resume(Player p, Location npc) {
        UUID u = p.getUniqueId();
        intro.add(u);
        say(p, lines("resume", List.of("…거꾸로요.", "아직 다 안 보셨잖아요.")), npc, 0, () -> {
            intro.remove(u);
            enter(p, false);
        });
    }

    /** Into the 체험관 (a blink of black), at the plaza. */
    private void enter(Player p, boolean first) {
        GuideModule g = eco.guide();
        World w = guideWorld();
        if (g == null || w == null) {
            p.sendMessage(Text.mm(line("busy", "<dark_gray>안내원이 당신 너머 어딘가를 본다. (지금은 체험관에 들어갈 수 없어요)")));
            return;
        }
        m.haunt().black(p, 2);
        p.playSound(p.getLocation(), Sound.BLOCK_WOODEN_DOOR_CLOSE, SoundCategory.AMBIENT, 0.5f, 0.6f);
        UUID u = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            Player q = Bukkit.getPlayer(u);
            if (q == null) return;
            Location plaza = new Location(w, 0.5, GuideLayout.FLOOR_Y, 0.5, 180, 0);
            boolean in = g.visiting(q) && q.getLocation().distanceSquared(plaza) < 36 || g.visit(q, plaza);
            if (!in) {
                q.removePotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS);
                q.sendMessage(Text.mm(line("busy", "<dark_gray>안내원이 당신 너머 어딘가를 본다. (지금은 체험관에 들어갈 수 없어요)")));
                return;
            }
            m.haunt().whisper(q, first ? line("arrive", "<gray>체험관이… 조금 조용하다. 아무도 없다.")
                    : line("arrive-again", "<gray>…다시 체험관이다."));
        }, 20L);
    }

    // ------------------------------------------------------------------------------------------
    // every second: what each walker sees
    // ------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        ticks++;
        if (task == null || task.isCancelled()) task = Bukkit.getScheduler().runTaskTimer(eco, this::frame, 2L, 2L);
        World gw = guideWorld();
        boolean roomWanted = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            try {
                if (failed(u)) mask(p);
                Walk w = walks.get(u);
                boolean inside = gw != null && p.getWorld().equals(gw);
                if (inside && !intro.contains(u) && (walking(u) || w != null && (w.preview || w.roomPreview))) {
                    if (w == null) walks.put(u, w = new Walk(u));
                    if (!w.ending) show(p, w);
                    if (w.room != null) roomWanted = true;
                } else if (w != null && !w.ending && (w.visited || !w.preview && !w.roomPreview)) end(u);
            } catch (RuntimeException e) {
                eco.getLogger().warning("[비밀] 안내원 " + p.getName() + ": " + e);
            }
        }
        for (Walk w : walks.values()) if (w.ending && w.room != null) roomWanted = true;
        if (gw != null) {
            if (roomWanted && (!roomBuilt || ticks % 5 == 0)) buildRoom(gw);
            else if (!roomWanted && roomBuilt) tearRoom();
            if (ticks % 15 == 0) statue.ensure(gw);
        }
    }

    /** Pictures near them, the 안내원 at the edge of sight, and (all found) the opening and the room. */
    private void show(Player p, Walk w) {
        UUID u = p.getUniqueId();
        World gw = p.getWorld();
        w.visited = true;
        int found = w.preview ? 0 : found(u);
        boolean pics = w.preview || walking(u);
        List<String> ord = order();
        for (int i = 0; i < ord.size(); i++) {
            String id = ord.get(i);
            GuideLayout.Station s = GuideLayout.station(id);
            if (s == null) continue;
            double[] mid = s.point(0, s.depth() / 2.0);
            boolean near = Math.hypot(p.getX() - mid[0], p.getZ() - mid[1]) < 70;
            boolean want = pics && near && (w.preview || i >= found && !w.hidden.contains(id));
            Pictures.Picture pic = w.pics.get(id);
            if (want && (pic == null || !pic.props.intact())) {
                if (pic != null) pic.props.clear();
                Pictures.Picture made = pictures().build(p, s, gw);
                if (made == null) w.pics.remove(id);
                else w.pics.put(id, made);
            } else if (!want && pic != null) {
                pic.props.clear();
                w.pics.remove(id);
            }
        }
        if (!w.preview) shadow(p, w, found);
        if (found >= UsherPlan.PICTURES && !w.preview || w.roomPreview) room(p, w);
        else clearRoom(w);
    }

    /** Takes everything of a walk away (the saved stage stays). */
    private void end(UUID u) {
        Walk w = walks.remove(u);
        if (w == null) return;
        for (Pictures.Picture pic : w.pics.values()) pic.props.clear();
        w.pics.clear();
        if (w.shadow != null) w.shadow.clear();
        clearRoom(w);
    }

    // ------------------------------------------------------------------------------------------
    // the 안내원 at the edge of sight, a block closer for each one found
    // ------------------------------------------------------------------------------------------

    private void shadow(Player p, Walk w, int found) {
        if (w.shadow == null) w.shadow = new Props(eco, m.haunt(), p.getUniqueId());
        if (found <= 0 || w.inRoom || below(p)) {
            if (w.usher != null) {
                w.shadow.forget(w.usher);
                w.usher = null;
            }
            return;
        }
        Location eye = p.getLocation();
        if (found >= UsherPlan.PICTURES) {
            // by the opening, waiting
            Location at = new Location(p.getWorld(), UsherPlan.OPEN_X1 - 1.5, GuideLayout.FLOOR_Y, UsherPlan.OPEN_Z + 0.5);
            if (w.usher == null || !w.usher.isValid()) w.usher = w.shadow.add(m.haunt().figure(p, at, skin(), null));
            else if (w.usher.getLocation().distanceSquared(at) > 1) w.usher.teleport(at);
            return;
        }
        double dist = UsherPlan.distance(found);
        if (w.usher != null && w.usher.isValid()) {
            double off = offAngle(eye, w.usher.getLocation());
            double d = w.usher.getLocation().distance(eye);
            if (d < 2.5) {   // walked up to it: gone, back later where they are not looking
                w.shadow.forget(w.usher);
                w.usher = null;
                return;
            }
            // seen: it stays and watches; out of sight: it takes its place again, at the edge
            if (off < 95) return;
        }
        Location to = edge(p, dist, w);
        if (to == null) return;
        if (w.usher == null || !w.usher.isValid()) w.usher = w.shadow.add(m.haunt().figure(p, to, skin(), null));
        else w.usher.teleport(to);
    }

    /** Degrees between where they look (level) and the thing. */
    private static double offAngle(Location eye, Location thing) {
        double yaw = Math.toRadians(eye.getYaw());
        double lx = -Math.sin(yaw), lz = Math.cos(yaw);
        double dx = thing.getX() - eye.getX(), dz = thing.getZ() - eye.getZ();
        double d = Math.hypot(dx, dz);
        if (d < 0.01) return 0;
        return Math.toDegrees(Math.acos(Math.clamp((lx * dx + lz * dz) / d, -1, 1)));
    }

    /** A floor spot at this distance, just at the edge of their sight (either side), facing them. */
    private Location edge(Player p, double dist, Walk w) {
        Location eye = p.getLocation();
        for (int tries = 0; tries < 6; tries++) {
            int side = tries % 2 == 0 ? w.side : -w.side;
            double d = Math.max(3, dist - tries / 2 * 2);
            double yaw = Math.toRadians(eye.getYaw() + side * 52);
            double x = eye.getX() - Math.sin(yaw) * d, z = eye.getZ() + Math.cos(yaw) * d;
            Block under = p.getWorld().getBlockAt((int) Math.floor(x), GuideLayout.FLOOR_Y - 1, (int) Math.floor(z));
            Block feet = under.getRelative(BlockFace.UP), head = feet.getRelative(BlockFace.UP);
            if (!under.getType().isSolid() || feet.getType().isSolid() || head.getType().isSolid()) continue;
            Location to = new Location(p.getWorld(), Math.floor(x) + 0.5, GuideLayout.FLOOR_Y, Math.floor(z) + 0.5);
            to.setYaw(Pictures.yawTo(to, eye));
            w.side = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
            return to;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // clicks on the pictures and the two
    // ------------------------------------------------------------------------------------------

    /** A click on one of their boxes: true when it was theirs. */
    private boolean clicked(Player p, Interaction hit) {
        Walk w = walks.get(p.getUniqueId());
        if (w == null || w.ending) return false;
        if (hit.equals(w.leftHit) || hit.equals(w.rightHit)) {
            choose(p, w, hit.equals(w.leftHit));
            return true;
        }
        for (Pictures.Picture pic : List.copyOf(w.pics.values())) {
            if (!hit.equals(pic.answer)) continue;
            picture(p, w, pic);
            return true;
        }
        return false;
    }

    private void picture(Player p, Walk w, Pictures.Picture pic) {
        UUID u = p.getUniqueId();
        GuideLayout.Station s = GuideLayout.station(pic.station);
        if (w.preview) {
            int n = order().indexOf(pic.station) + 1;
            p.sendMessage(Text.mm("<gray>[미리보기] " + n + "번째 · " + (s == null ? pic.station : s.title()) + " · <white>"
                    + Pictures.WHAT.getOrDefault(pic.station, "?")));
            return;
        }
        int found = found(u);
        switch (UsherPlan.click(order(), found, pic.station)) {
            case DONE -> { }
            case OUT_OF_TURN -> {
                pic.props.clear();
                w.pics.remove(pic.station);
                w.hidden.add(pic.station);
                m.haunt().whisper(p, line("wrong", "<gray>…아니에요."));
                p.playSound(p.getLocation(), Sound.BLOCK_WOOL_BREAK, SoundCategory.AMBIENT, 0.4f, 0.5f);
            }
            case RIGHT -> {
                Location at = pic.answer.getLocation();
                pic.props.clear();
                w.pics.remove(pic.station);
                w.hidden.clear();
                int now = found + 1;
                m.stage(this, u, UsherPlan.stage(now));
                p.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.AMBIENT, 0.7f, 0.5f);
                m.haunt().behind(p, Sound.BLOCK_STONE_STEP, 0.5f, 0.8f);
                List<String> said = lines("found", List.of());
                String whisper = now - 1 < said.size() ? said.get(now - 1) : "<gray>…거기였구나.";
                m.haunt().whisper(p, whisper);
                if (now == UsherPlan.NOTE_AT) note(p);
                if (now >= UsherPlan.PICTURES) {
                    p.sendMessage(Text.mm(line("opened", "<dark_gray>광장 쪽에서 돌이 끌리는 소리가 났다.")));
                    p.playSound(new Location(p.getWorld(), 0, GuideLayout.FLOOR_Y, 0), Sound.BLOCK_GRINDSTONE_USE, SoundCategory.AMBIENT, 1f, 0.5f);
                }
            }
        }
    }

    /** The crumpled note: "진짜는 자기 말을 한다." */
    private void note(Player p) {
        Optional<ItemStack> it = Items.create("economy:usher_note", 1);
        if (it.isEmpty()) {
            eco.getLogger().warning("[비밀] 안내원: economy:usher_note 아이템이 없음");
            return;
        }
        ItemStack n = it.get();
        n.editMeta(meta -> meta.lore(Text.itemLines(lines("note-lore", List.of("<gray>삐뚤빼뚤한 글씨:", "<white>“진짜는 자기 말을 한다.”")))));
        for (ItemStack left : p.getInventory().addItem(n).values()) p.getWorld().dropItem(p.getLocation(), left);
        p.sendMessage(Text.mm(line("note-given", "<gray>손에 구겨진 쪽지가 쥐어져 있다. 언제부터였는지 모르겠다.")));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, SoundCategory.AMBIENT, 0.8f, 0.7f);
    }

    private static boolean hasNote(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (it != null && "economy:usher_note".equals(Items.key(it))) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // the room under the plaza
    // ------------------------------------------------------------------------------------------

    private static boolean below(Player p) {
        Location l = p.getLocation();
        return UsherPlan.below(l.getBlockX(), l.getY(), l.getBlockZ());
    }

    private static BlockData cell(String kind) {
        return switch (kind) {
            case "floor" -> Material.POLISHED_ANDESITE.createBlockData();
            case "floor2", "ceiling" -> Material.SMOOTH_STONE.createBlockData();
            case "wall" -> Material.STONE_BRICKS.createBlockData();
            case "stair" -> Material.POLISHED_ANDESITE_STAIRS.createBlockData(d -> ((Stairs) d).setFacing(BlockFace.WEST));
            case "lantern" -> Material.LANTERN.createBlockData();
            case "soul" -> Material.SOUL_LANTERN.createBlockData();
            default -> Material.AIR.createBlockData();
        };
    }

    /** Lays (or mends) the room for real: nobody else can see it, under the plaza floor of an empty world. */
    private void buildRoom(World w) {
        for (int cx = -1; cx <= 0; cx++) for (int cz = -1; cz <= 0; cz++) if (!w.isChunkLoaded(cx, cz)) return;
        for (UsherPlan.Cell c : UsherPlan.room()) {
            Block b = w.getBlockAt(c.x(), c.y(), c.z());
            BlockData want = cell(c.kind());
            if (b.getBlockData().matches(want)) continue;
            roomWas.putIfAbsent(UsherPlan.key(c.x(), c.y(), c.z()), b.getBlockData());
            b.setBlockData(want, false);
        }
        roomBuilt = true;
    }

    /** Puts back what the room replaced (air, in an empty world). */
    private void tearRoom() {
        World w = guideWorld();
        if (w != null) {
            for (UsherPlan.Cell c : UsherPlan.room()) {
                BlockData was = roomWas.get(UsherPlan.key(c.x(), c.y(), c.z()));
                if (was != null) w.getBlockAt(c.x(), c.y(), c.z()).setBlockData(was, false);
            }
        }
        roomWas.clear();
        roomBuilt = false;
    }

    private void clearRoom(Walk w) {
        if (w.room == null) return;
        w.room.clear();
        w.room = null;
        w.left = w.right = null;
        w.leftSays = w.rightSays = null;
        w.leftHit = w.rightHit = null;
        w.inRoom = false;
    }

    /** The opening only they see, and the two below. */
    private void room(Player p, Walk w) {
        UUID u = p.getUniqueId();
        World gw = p.getWorld();
        if (w.room == null) {
            w.room = new Props(eco, m.haunt(), u);
            openFloor(w.room, gw);
            if (m.get(this, u, "real") == null) m.set(this, u, "real", ThreadLocalRandom.current().nextBoolean() ? "left" : "right");
        } else w.room.refake(l -> Material.AIR.createBlockData());
        if (!roomBuilt) return;
        if (w.left == null || !w.left.isValid() || w.right == null || !w.right.isValid()) {
            w.room.clear();
            openFloor(w.room, gw);
            int y = UsherPlan.ROOM_Y + 1;
            Location l = new Location(gw, UsherPlan.LEFT[0], y, UsherPlan.LEFT[1], 90, 0);
            Location r = new Location(gw, UsherPlan.RIGHT[0], y, UsherPlan.RIGHT[1], 90, 0);
            w.left = w.room.add(m.haunt().figure(p, l, skin(), null));
            w.right = w.room.add(m.haunt().figure(p, r, skin(), null));
            w.leftSays = w.room.text(l.clone().add(0, 2.35, 0), "", Display.Billboard.CENTER, 0.8f, true);
            w.rightSays = w.room.text(r.clone().add(0, 2.35, 0), "", Display.Billboard.CENTER, 0.8f, true);
            w.leftHit = w.room.hit(l, 0.9f, 2.0f);
            w.rightHit = w.room.hit(r, 0.9f, 2.0f);
            Location sign = new Location(gw, UsherPlan.ROOM_X2 - 0.45, y + 2.4, 0.5, 90, 0);
            w.room.text(sign, line("room-sign", "<gold><b>관험체 이잡라길</b>\n<gray>요세보 라골 을것 은싶 고보 해"), Display.Billboard.FIXED, 0.9f, false)
                    .setRotation(90, 0);
        }
    }

    /** The opening: the plaza floor over the first three steps, air to them only. */
    private static void openFloor(Props room, World gw) {
        for (int x = UsherPlan.OPEN_X1; x <= UsherPlan.OPEN_X2; x++)
            for (int z = UsherPlan.OPEN_Z1; z <= UsherPlan.OPEN_Z2; z++)
                room.fake(new Location(gw, x, UsherPlan.FLOOR, z), Material.AIR.createBlockData());
    }

    /** Every frame (2 ticks): pictures move, the 안내원 turns to them, the stairs, the two speak. */
    private void frame() {
        if (!m.all().contains(this)) {   // a reload made a new one: this one stops
            if (task != null) task.cancel();
            return;
        }
        for (Walk w : walks.values()) {
            Player p = Bukkit.getPlayer(w.u);
            if (p == null || !inGuide(p)) continue;
            w.frame++;
            try {
                for (Pictures.Picture pic : List.copyOf(w.pics.values())) if (pic.frame != null) pic.frame.run(p, w.frame);
                if (w.usher != null && w.usher.isValid() && w.frame % 2 == 0)
                    w.usher.setRotation(Pictures.yawTo(w.usher.getLocation(), p.getLocation()), 0);
                if (w.room != null) roomFrame(p, w);
            } catch (RuntimeException e) {
                eco.getLogger().warning("[비밀] 안내원 " + p.getName() + ": " + e);
            }
        }
    }

    private void roomFrame(Player p, Walk w) {
        Location l = p.getLocation();
        int bx = l.getBlockX(), bz = l.getBlockZ();
        if (UsherPlan.onOpening(bx, l.getY(), bz) && !p.isFlying() && roomBuilt && !w.ending) {
            m.haunt().black(p, 1);
            double[] d = UsherPlan.DOWN;
            p.teleport(new Location(p.getWorld(), d[0], d[1], d[2], UsherPlan.DOWN_YAW, 20));
            p.playSound(p.getLocation(), Sound.BLOCK_STONE_STEP, SoundCategory.AMBIENT, 0.8f, 0.7f);
            return;
        }
        if (UsherPlan.underOpening(bx, l.getY(), bz) && !w.ending) {
            double[] up = UsherPlan.UP;
            p.teleport(new Location(p.getWorld(), up[0], up[1], up[2], UsherPlan.UP_YAW, 0));
            return;
        }
        boolean in = UsherPlan.inRoom(bx, l.getY(), bz);
        if (in && !w.inRoom) {
            w.inRoom = true;
            w.beat = 0;
            w.nextBeat = w.frame + 15;
            m.haunt().whisper(p, line("room-hint", "<dark_gray>둘 중 하나. 한 번만."));
            p.playSound(l, Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 0.5f, 0.6f);
        } else if (!in && w.inRoom && !UsherPlan.below(bx, l.getY(), bz)) w.inRoom = false;
        if (w.left == null || w.right == null) return;
        if (w.frame % 2 == 0 && !w.ending) {
            if (w.left.isValid()) w.left.setRotation(Pictures.yawTo(w.left.getLocation(), l), 0);
            if (w.right.isValid()) w.right.setRotation(Pictures.yawTo(w.right.getLocation(), l), 0);
        }
        if (w.inRoom && !w.ending && w.frame >= w.nextBeat) {
            beat(p, w);
            w.nextBeat = w.frame + 30;
        }
    }

    private boolean realLeft(UUID u) {
        return !"right".equals(m.get(this, u, "real"));
    }

    private List<String> realLines() {
        return lines("real", List.of("어서 오세요. 길라잡이 체험관이에요.", "해 보고 싶은 것을 골라 보세요.", "농사는 괭이로 흙을 가는 것부터예요.",
                "낚싯대는 연못 쪽으로 던지세요.", "땅 한 칸은 열여섯 칸 곱하기 열여섯 칸이에요.", "모르는 게 있으면 언제든 물어보세요.",
                "다 하셨어요? 잘하셨어요.", "저는 여기 있을게요. 늘 여기 있었어요."));
    }

    /** One beat: the true one says its own line; the false one says theirs. */
    private void beat(Player p, Walk w) {
        UUID u = p.getUniqueId();
        List<String> real = realLines();
        String mine = UsherPlan.realLine(real, w.beat);
        String fake = esc(UsherPlan.fakeLine(words(u), real, w.beat));
        boolean left = realLeft(u);
        TextDisplay r = left ? w.leftSays : w.rightSays, f = left ? w.rightSays : w.leftSays;
        if (r != null && r.isValid()) r.text(Text.mm("<white>" + mine));
        if (f != null && f.isValid()) f.text(Text.mm("<white>" + fake));
        Location at = p.getLocation();
        p.playSound(at, Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.AMBIENT, 0.3f, 0.6f);
        w.beat++;
    }

    /** The one choice. */
    private void choose(Player p, Walk w, boolean left) {
        UUID u = p.getUniqueId();
        boolean real = left == realLeft(u);
        if (w.roomPreview) {
            w.ending = true;
            if (real) trueEnd(p, w, true);
            else falseEnd(p, w, true);
            return;
        }
        if (m.get(this, u, "chose") != null) return;
        m.set(this, u, "chose", real ? "real" : "fake");
        w.ending = true;
        if (real) trueEnd(p, w, false);
        else {
            m.set(this, u, "failed", System.currentTimeMillis());
            m.stage(this, u, 0);
            falseEnd(p, w, false);
        }
    }

    private void later(UUID u, long delay, java.util.function.Consumer<Player> then) {
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            Player q = Bukkit.getPlayer(u);
            if (q != null) then.accept(q);
        }, delay);
    }

    /** It is found: the other goes, it says so, and up there is a statue. dry = an admin look (nothing saved). */
    private void trueEnd(Player p, Walk w, boolean dry) {
        UUID u = p.getUniqueId();
        boolean left = realLeft(u);
        Mannequin other = left ? w.right : w.left;
        TextDisplay says = left ? w.leftSays : w.rightSays, otherSays = left ? w.rightSays : w.leftSays;
        // it says its own name for the first time
        List<String> lines = new ArrayList<>();
        for (String l : lines("real-chosen", List.of("…아.", "찾았네요. 저를.", "제 이름은 {name}{이에요}.", "이제 올라가셔도 돼요. 저는 여기서 안내할게요. 제 말로요.")))
            lines.add(named(l));
        if (other != null) w.room.forget(other);
        if (otherSays != null) w.room.forget(otherSays);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.AMBIENT, 0.8f, 0.6f);
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            later(u, 10L + i * 50L, q -> {
                if (says != null && says.isValid()) says.text(Text.mm("<white>" + l));
            });
        }
        long out = 10L + lines.size() * 50L;
        later(u, out, q -> m.haunt().black(q, 3));
        later(u, out + 30, q -> {
            World gw = q.getWorld();
            end(u);
            q.teleport(new Location(gw, Statue.BX + 0.5, GuideLayout.FLOOR_Y, Statue.BZ + 4.5, 180, -10));
            if (dry || m.test(u)) {
                Props x = scenes.computeIfAbsent(u, k -> new Props(eco, m.haunt(), u));
                x.add(m.haunt().likeness(q, q, Statue.figureAt(gw), null));
                x.text(new Location(gw, Statue.BX + 0.5, GuideLayout.FLOOR_Y + 0.6, Statue.BZ + 1.03),
                        "<#d9e8b0>길잡이의 길잡이\n<white><b>" + esc(q.getName()) + "</b>", Display.Billboard.VERTICAL, 0.45f, false);
                x.block(new Location(gw, Statue.BX + 0.5, GuideLayout.FLOOR_Y, Statue.BZ + 0.5), Material.CHISELED_STONE_BRICKS.createBlockData(), 1f, 1f, 1f, 0);
                if (dry) q.sendMessage(Text.mm("<yellow>[미리보기] 진짜를 고른 끝 (아무것도 저장 안 함, 동상은 나만 잠시 보임)"));
                Bukkit.getScheduler().runTaskLater(eco, () -> clearScene(u), 20L * 40);
            } else statue.save(q);
            if (!dry) m.solve(this, q);
            if (!dry && !m.test(u)) statue.ensure(gw);
            q.sendMessage(Text.mm(named(line("after-solve", "<gray>광장에 못 보던 동상이 서 있다. 낯익은 얼굴이다."))));
        });
        if (!dry) later(u, out + 30 + 200, q -> {
            GuideModule g = eco.guide();
            if (g != null && g.visiting(q)) {
                q.sendMessage(Text.mm(named(line("goodbye", "<dark_gray>멀리서 안내원이 손을 흔든다. 처음 보는 표정이다."))));
                g.unvisit(q, true);
            }
        });
    }

    /** The false one is chosen: it smiles, and now it guides. dry = an admin look. */
    private void falseEnd(Player p, Walk w, boolean dry) {
        UUID u = p.getUniqueId();
        boolean left = realLeft(u);
        Mannequin real = left ? w.left : w.right, fake = left ? w.right : w.left;
        TextDisplay says = left ? w.rightSays : w.leftSays, realSays = left ? w.leftSays : w.rightSays;
        List<String> lines = lines("fake-chosen", List.of("후후.", "이제 제가 안내할게요."));
        if (real != null) w.room.forget(real);
        if (realSays != null) w.room.forget(realSays);
        World gw = p.getWorld();
        for (int[] c : new int[][]{{UsherPlan.ROOM_X1 + 1, -UsherPlan.ROOM_Z + 1}, {UsherPlan.ROOM_X1 + 1, UsherPlan.ROOM_Z - 1},
                {UsherPlan.ROOM_X2 - 1, -UsherPlan.ROOM_Z + 1}, {UsherPlan.ROOM_X2 - 1, UsherPlan.ROOM_Z - 1}})
            w.room.fake(new Location(gw, c[0], UsherPlan.ROOM_Y + 1, c[1]), Material.AIR.createBlockData());
        m.haunt().dark(p, 6);
        p.playSound(p.getLocation(), Sound.BLOCK_CANDLE_EXTINGUISH, SoundCategory.AMBIENT, 1f, 0.6f);
        if (fake != null) fake.setPose(Pose.SNEAKING, true);
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            later(u, 20L + i * 45L, q -> {
                if (says != null && says.isValid()) says.text(Text.mm("<white>" + l));
                q.playSound(q.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.AMBIENT, 0.4f, 0.5f);
            });
        }
        long out = 20L + lines.size() * 45L + 30;
        later(u, out, q -> m.haunt().black(q, 3));
        later(u, out + 30, q -> {
            end(u);
            q.teleport(new Location(q.getWorld(), 0.5, GuideLayout.FLOOR_Y, -2.5, 180, 0));
            if (dry) {
                q.sendMessage(Text.mm("<yellow>[미리보기] 가짜를 고른 끝 (실패로 저장하지 않음)"));
                return;
            }
            mask(q);
            q.sendMessage(Text.mm(line("after-fail", "<dark_gray>…안내원이 웃고 있다. 아까부터 계속.")));
        });
        if (!dry) later(u, out + 30 + 120, q -> {
            GuideModule g = eco.guide();
            if (g != null && g.visiting(q)) g.unvisit(q, true);
        });
    }

    // ------------------------------------------------------------------------------------------
    // after a wrong choice: every 안내원 wears their words
    // ------------------------------------------------------------------------------------------

    private String maskName(Player p) {
        List<String> said = words(p.getUniqueId());
        return "<gold>" + esc(said.isEmpty() ? p.getName() : said.getLast());
    }

    /** Hides each real 안내원 near them and stands a copy there, head a little down, named with their own words. */
    private void mask(Player p) {
        UUID u = p.getUniqueId();
        Map<UUID, Mannequin> mine = masks.computeIfAbsent(u, k -> new HashMap<>());
        Props bag = maskProps.computeIfAbsent(u, k -> new Props(eco, m.haunt(), u));
        String name = maskName(p);
        for (Entity e : p.getNearbyEntities(48, 16, 48)) {
            if (!(e instanceof Mannequin) || !e.getPersistentDataContainer().has(NPC)) continue;
            Mannequin stand = mine.get(e.getUniqueId());
            if (stand == null || !stand.isValid()) {
                p.hideEntity(eco, e);
                Location at = e.getLocation();
                at.setPitch(25);
                stand = bag.add(m.haunt().figure(p, at, skin(), name));
                mine.put(e.getUniqueId(), stand);
            } else if (!Text.plain(name).equals(PlainTextComponentSerializer.plainText().serialize(stand.customName() == null ? net.kyori.adventure.text.Component.empty() : stand.customName()))) {
                stand.customName(Text.mm(name));
            }
            Location me = p.getLocation();
            if (stand.getLocation().distanceSquared(me) < 400) stand.setRotation(Pictures.yawTo(stand.getLocation(), me), 25);
        }
    }

    private void unmask(UUID u) {
        Map<UUID, Mannequin> mine = masks.remove(u);
        Props bag = maskProps.remove(u);
        if (bag != null) bag.clear();
        Player p = Bukkit.getPlayer(u);
        if (mine != null && p != null) for (UUID id : mine.keySet()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) p.showEntity(eco, e);
        }
    }

    private void maskTalk(Player p, Location from) {
        List<String> said = words(p.getUniqueId());
        List<String> pool = lines("mask", List.of("이제 제가 안내할게요.", "{words}", "어서 오세요, {name}님. 저예요."));
        String l = pool.get(ThreadLocalRandom.current().nextInt(pool.size()))
                .replace("{words}", esc(said.isEmpty() ? p.getName() : said.get(ThreadLocalRandom.current().nextInt(said.size()))))
                .replace("{name}", esc(p.getName()));
        speak(p, l, from);
        GuideModule g = eco.guide();
        if (g != null) Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) g.open(p);
        }, 30L);
    }

    // ------------------------------------------------------------------------------------------
    // leaving, cleaning, admin
    // ------------------------------------------------------------------------------------------

    /** Left the 체험관 some other way (a command, a portal) while a visitor: their things come back where they are. */
    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        World gw = guideWorld();
        Player p = e.getPlayer();
        if (gw == null || !e.getFrom().equals(gw)) return;
        end(p.getUniqueId());
        GuideModule g = eco.guide();
        if (g != null) g.unvisit(p, false);
    }

    private void clearScene(UUID u) {
        Props x = scenes.remove(u);
        if (x != null) x.clear();
    }

    @Override
    public void cleanup(UUID player) {
        intro.remove(player);
        Ask a = asking.remove(player);
        if (a != null) a.timeout().cancel();
        end(player);
        unmask(player);
        clearScene(player);
        boolean anyRoom = false;
        for (Walk w : walks.values()) if (w.room != null) anyRoom = true;
        if (!anyRoom && roomBuilt) tearRoom();
    }

    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        if (stage <= 0) {
            m.reset(this, u);
            return;
        }
        m.remove(this, u, "failed");
        m.remove(this, u, "chose");
        m.stage(this, u, stage);
        int found = UsherPlan.found(stage);
        if (found >= UsherPlan.NOTE_AT && !hasNote(p)) note(p);
        String next = UsherPlan.next(order(), found);
        GuideLayout.Station s = next == null ? null : GuideLayout.station(next);
        p.sendMessage(Text.mm("<gray>[안내원] 찾은 그림 " + found + "/13 · " + (s == null ? "다음: 광장 바닥의 계단 (x -9..-7, z -1..1)" : "다음: " + s.title())));
        enter(p, false);
    }

    @Override
    public String status(UUID u) {
        List<String> parts = new ArrayList<>();
        int st = m.stage(this, u);
        if (failed(u)) parts.add("<red>실패 (가짜를 골랐음, 다시 못 함)");
        if (st > stages()) parts.add("<green>풀었음");
        if (st >= 1 && st <= stages()) {
            int found = UsherPlan.found(st);
            String next = UsherPlan.next(order(), found);
            GuideLayout.Station s = next == null ? null : GuideLayout.station(next);
            parts.add("찾음 " + found + "/13 · 다음 " + (s == null ? "숨은 방" : s.title()));
            if (m.get(this, u, "real") != null) parts.add("진짜 " + (realLeft(u) ? "왼쪽" : "오른쪽"));
        }
        if (st == 0) {
            if (asking.containsKey(u)) parts.add("질문에 대답 기다리는 중 (#" + (asking.get(u).index() + 1) + ")");
            int count = questions().size();
            String asked = m.get(this, u, "asked");
            if (asked != null && count > 0) {
                int next = UsherPlan.nextQuestion(asked, count, 0);
                parts.add("다음 질문 #" + (next + 1) + "/" + count);
            }
            long left = quietUntil.getOrDefault(u, 0L) - System.currentTimeMillis();
            if (left > 0) parts.add("다시 묻기까지 " + (left + 999) / 1000 + "초");
        }
        parts.add("길라잡이 " + (eco.kv().has("guide.all." + u) ? "모두 마침" : "덜 마침"));
        if (statue.saved()) parts.add("동상 있음");
        return String.join(" · ", parts);
    }

    /** Scene: the records it would tell them, and its ask (chat only, nothing saved). */
    private void sceneRecords(Player p) {
        records(p, l -> {
            List<String> all = new ArrayList<>(l);
            all.add(line("ask", "거꾸로 한 번만 더 봐 주세요. 제가 어디서 잘못됐는지."));
            say(p, all, null, 0, () -> { });
        });
    }

    @Override
    public List<String> scenes() {
        return List.of("질문", "기록", "틀린그림", "다가옴", "숨은방", "두안내원", "가짜엔딩", "동상", "쪽지", "틀린안내원", "이름", "동상치우기");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        switch (word) {
            case "질문" -> {
                admin.sendMessage(Text.mm("<gray>질문 (차례로 돌아감, 30초 안에 채팅으로 대답):"));
                List<Map.Entry<String, List<String>>> qs = questions();
                for (int i = 0; i < qs.size(); i++)
                    admin.sendMessage(Text.mm(" <white>" + (i + 1) + ". " + qs.get(i).getKey() + " <gray>→ " + esc(String.join(" / ", qs.get(i).getValue()))));
                if (!ask(p, null, true)) admin.sendMessage(Text.mm("<red>usher.yml 에 questions 가 없어요"));
            }
            case "기록" -> sceneRecords(p);
            case "틀린그림" -> {
                Walk w = walks.computeIfAbsent(u, Walk::new);
                w.preview = true;
                admin.sendMessage(Text.mm("<gray>순서 (거꾸로, 길라잡이 메뉴 번호의 반대):"));
                for (String l : Pictures.describe(order())) admin.sendMessage(Text.mm(" <white>" + l));
                admin.sendMessage(Text.mm("<gray>체험관에서 정거장 근처(70칸)에 가면 13개 모두 보입니다. 클릭 = 이름만. 끄기: /관리자 비밀 시작·단계·초기화 또는 체험관 나가기"));
                if (!inGuide(p)) enter(p, false);
            }
            case "다가옴" -> approach(p);
            case "숨은방" -> {
                Walk w = walks.computeIfAbsent(u, Walk::new);
                w.roomPreview = true;
                admin.sendMessage(Text.mm("<gray>광장 바닥 x -9..-7, z -1..1 이 열립니다 (그 사람에게만). 진짜는 "
                        + (realLeft(u) ? "왼쪽" : "오른쪽") + " — 골라도 저장 안 함"));
                if (m.get(this, u, "real") == null) m.set(this, u, "real", ThreadLocalRandom.current().nextBoolean() ? "left" : "right");
                if (!inGuide(p)) enter(p, false);
            }
            case "두안내원" -> twoInFront(p);
            case "가짜엔딩" -> fakeInFront(p);
            case "동상" -> {
                clearScene(u);
                Props x = scenes.computeIfAbsent(u, k -> new Props(eco, m.haunt(), u));
                Location at = ahead(p, 4);
                at.setYaw(Pictures.yawTo(at, p.getLocation()));
                x.block(at.clone().subtract(0, 1, 0), Material.CHISELED_STONE_BRICKS.createBlockData(), 1f, 1f, 1f, 0);
                x.add(m.haunt().likeness(p, p, at, null));
                x.text(at.clone().add(0, -0.4, 0), "<#d9e8b0>길잡이의 길잡이\n<white><b>" + esc(p.getName()) + "</b>", Display.Billboard.VERTICAL, 0.45f, false);
                Bukkit.getScheduler().runTaskLater(eco, () -> clearScene(u), 20L * 30);
            }
            case "쪽지" -> note(p);
            case "틀린안내원" -> {
                clearScene(u);
                Props x = scenes.computeIfAbsent(u, k -> new Props(eco, m.haunt(), u));
                Location at = ahead(p, 3);
                at.setYaw(Pictures.yawTo(at, p.getLocation()));
                at.setPitch(25);
                x.add(m.haunt().figure(p, at, skin(), maskName(p)));
                maskTalk(p, at);
                Bukkit.getScheduler().runTaskLater(eco, () -> clearScene(u), 20L * 20);
            }
            case "이름" -> {
                // what it tells anyone now and then once found (the solver's name, or theirs when no one has yet)
                String who = m.solver(this).map(id -> Bukkit.getOfflinePlayer(id).getName()).orElse(p.getName());
                speak(p, named(line("remember", "").replace("{player}", esc(who == null ? "누군가" : who))), null);
            }
            case "동상치우기" -> {
                statue.forget(guideWorld());
                admin.sendMessage(Text.mm("<green>체험관 광장의 동상을 치웠어요 (저장된 기록도 지움)"));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** A spot on the ground in front of them. */
    private static Location ahead(Player p, double d) {
        Location l = p.getLocation();
        Location at = l.clone().add(l.getDirection().setY(0).normalize().multiply(d));
        at.setY(l.getY());
        return at;
    }

    /** Scene: the 안내원 at the edge of sight, a block closer every second and a half, thirteen times. */
    private void approach(Player p) {
        UUID u = p.getUniqueId();
        clearScene(u);
        Props x = scenes.computeIfAbsent(u, k -> new Props(eco, m.haunt(), u));
        Mannequin[] fig = {null};
        for (int i = 1; i <= UsherPlan.PICTURES; i++) {
            int found = i;
            later(u, i * 30L, q -> {
                double d = UsherPlan.distance(found);
                Location eye = q.getLocation();
                double yaw = Math.toRadians(eye.getYaw() + 52);
                Location to = new Location(q.getWorld(), eye.getX() - Math.sin(yaw) * d, eye.getY(), eye.getZ() + Math.cos(yaw) * d);
                to.setYaw(Pictures.yawTo(to, eye));
                if (fig[0] == null || !fig[0].isValid()) fig[0] = x.add(m.haunt().figure(q, to, skin(), null));
                else fig[0].teleport(to);
                if (found % 3 == 0) m.haunt().behind(q, Sound.BLOCK_STONE_STEP, 0.4f, 0.8f);
            });
        }
        later(u, UsherPlan.PICTURES * 30L + 60, q -> clearScene(u));
    }

    /** Scene: the two, in front of them, speaking for half a minute. */
    private void twoInFront(Player p) {
        UUID u = p.getUniqueId();
        clearScene(u);
        Props x = scenes.computeIfAbsent(u, k -> new Props(eco, m.haunt(), u));
        Location mid = ahead(p, 4);
        Location eye = p.getLocation();
        double yaw = Math.toRadians(eye.getYaw() + 90);
        Location a = mid.clone().add(-Math.sin(yaw) * 1.3, 0, Math.cos(yaw) * 1.3), b = mid.clone().add(Math.sin(yaw) * 1.3, 0, -Math.cos(yaw) * 1.3);
        a.setYaw(Pictures.yawTo(a, eye));
        b.setYaw(Pictures.yawTo(b, eye));
        x.add(m.haunt().figure(p, a, skin(), null));
        x.add(m.haunt().figure(p, b, skin(), null));
        TextDisplay ta = x.text(a.clone().add(0, 2.35, 0), "", Display.Billboard.CENTER, 0.8f, true);
        TextDisplay tb = x.text(b.clone().add(0, 2.35, 0), "", Display.Billboard.CENTER, 0.8f, true);
        boolean realA = ThreadLocalRandom.current().nextBoolean();
        p.sendMessage(Text.mm("<gray>[미리보기] 진짜는 " + (realA ? "왼쪽" : "오른쪽") + " (자기 말), 가짜는 오늘 친 채팅을 따라 함"));
        for (int i = 0; i < 10; i++) {
            int beat = i;
            later(u, 20L + i * 60L, q -> {
                List<String> real = realLines();
                String mine = "<white>" + UsherPlan.realLine(real, beat), fake = "<white>" + esc(UsherPlan.fakeLine(words(u), real, beat));
                if (ta.isValid()) ta.text(Text.mm(realA ? mine : fake));
                if (tb.isValid()) tb.text(Text.mm(realA ? fake : mine));
            });
        }
        later(u, 20L + 10 * 60L, q -> clearScene(u));
    }

    /** Scene: the false one's smile, in front of them (nothing saved). */
    private void fakeInFront(Player p) {
        UUID u = p.getUniqueId();
        clearScene(u);
        Props x = scenes.computeIfAbsent(u, k -> new Props(eco, m.haunt(), u));
        Location at = ahead(p, 3);
        at.setYaw(Pictures.yawTo(at, p.getLocation()));
        Mannequin f = x.add(m.haunt().figure(p, at, skin(), null));
        TextDisplay says = x.text(at.clone().add(0, 2.35, 0), "", Display.Billboard.CENTER, 0.8f, true);
        m.haunt().dark(p, 6);
        f.setPose(Pose.SNEAKING, true);
        List<String> lines = lines("fake-chosen", List.of("후후.", "이제 제가 안내할게요."));
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            later(u, 20L + i * 45L, q -> {
                if (says.isValid()) says.text(Text.mm("<white>" + l));
                q.playSound(q.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.AMBIENT, 0.4f, 0.5f);
            });
        }
        later(u, 20L + lines.size() * 45L + 30, q -> m.haunt().black(q, 2));
        later(u, 20L + lines.size() * 45L + 60, q -> clearScene(u));
    }
}
