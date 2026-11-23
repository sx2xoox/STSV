package dev.stsv.economy.secret.newcomer;

import dev.stsv.core.util.Text;
import dev.stsv.economy.bus.BusStop;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 후속 임무 「다음 사람」 (docs/admin/숨은_비밀/새로_온_사람.md "후속 임무"; newcomer.yml {@code sequel}). He is gone, but
 * on a night of each week someone else joins the same way: a different name ({@code sequel.names}), a join line, a few
 * ordinary beginner questions, a little small talk, a tired line, and never a leave line. Everyone sees them (unless
 * {@code sequel.public-chat} is off); only the solver is told, once, by the old stone: "…또 한 명 왔어요."
 * <p>
 * What that visitor really wants is in the questions, hidden another way than his acrostic: the first WORD of each
 * question, in order ({@link Wishes}). It is a small errand in the world. When the solver has done it, the visitor says
 * one last line of thanks in public and never speaks again; the solver alone gets the reward. Left alone, the visitor
 * fades with the week.
 * <p>
 * Steps: 0 = asking (over one night, or more when a night was cut short); 1 = every question was asked, the errand is
 * open; 2 = done, the visitor's thanks are being typed (then the reward).
 * Notes of the week ("sequel.x."): name, wish, solo (only the solver sees the visitor), joined (ms), told, asked,
 * sulk (the night the visitor was sworn at), tired, thanks (the line), said.
 * Kept ("sequel.keep."): names, wishes (the recent ones, not taken again soon).
 * <p>
 * Nothing of the story itself is touched: his stage, his name, the solver's own missing leave line. A whisper is only
 * taken when its target is exactly the week's visitor and no real player has that name.
 */
public final class NextSequel extends Sequel {

    private record Pending(String words, long at) {}

    /** The week's visitor, as far as it is not saved: what they are typing, when they last spoke, their small-talk limits. */
    private static final class Guest {
        final UUID solver;
        final String name;
        final boolean solo;
        final Look look;
        final List<Talk.Intent> talk, whisper;
        final Deque<Pending> pending = new ArrayDeque<>();
        final Deque<String> recent = new ArrayDeque<>(), recentWhispers = new ArrayDeque<>();
        final Map<UUID, Long> answered = new ConcurrentHashMap<>();
        long nextAt, askedAt, saidAt, endAt, lastReactAt, hushUntil, thanksAt;
        int reactions, thanked = -1, held;
        UUID lastBy;

        Guest(UUID solver, String name, boolean solo, Newcomer s) {
            this.solver = solver;
            this.name = name;
            this.solo = solo;
            this.look = s.lookFor(name);
            this.talk = s.smallTalkFor(name);
            this.whisper = s.whisperTalkFor(name);
        }

        long lastPending(long now) {
            return pending.isEmpty() ? now : pending.peekLast().at();
        }
    }

    private final Newcomer s;
    private List<Wishes.Wish> wishes = Wishes.DEFAULTS;
    /** solver -> their week's visitor (built again from the notes after a join or a reload) */
    private final Map<UUID, Guest> guests = new ConcurrentHashMap<>();
    private volatile long lastJoin;

    NextSequel(Newcomer s) {
        super(s);
        this.s = s;
    }

    @Override
    public String id() {
        return "다음 사람";
    }

    @Override
    protected void loaded() {
        List<String> problems = new ArrayList<>();
        wishes = Wishes.read(cfg().getConfigurationSection("wishes"), problems);
        for (String n : names()) if (!Whispers.account(n)) problems.add("sequel.names: \"" + n + "\" 은(는) 마인크래프트 이름 꼴(영문 · 숫자 · _ 3~16자)이 아니에요");
        for (String t : thanks(null)) if (t.contains("?")) problems.add("sequel.thanks: \"" + t + "\" — \"?\" 를 쓰지 않아요");
        for (String p : problems) eco.getLogger().warning("[비밀] newcomer.yml: " + p);
        guests.clear();
    }

    // ------------------------------------------------------------------------------------------
    // settings and small things
    // ------------------------------------------------------------------------------------------

    private YamlConfiguration main() {
        return s.cfg();
    }

    private List<String> names() {
        List<String> l = Wishes.clean(cfg().getStringList("names"));
        return l.isEmpty() ? Wishes.NAMES : l;
    }

    private List<String> thanks(Wishes.Wish w) {
        if (w != null && !w.thanks().isEmpty()) return w.thanks();
        List<String> l = new ArrayList<>();
        for (String x : Wishes.clean(cfg().getStringList("thanks"))) if (!x.contains("?")) l.add(x);
        return l.isEmpty() ? Wishes.THANKS : l;
    }

    private boolean publicChat() {
        return cfg().getBoolean("public-chat", true);
    }

    private static LocalDateTime nowKst() {
        return ZonedDateTime.now(SecretModule.KST).toLocalDateTime();
    }

    /** The night this moment belongs to (or the next to open), by his visiting window. */
    private String nightKey() {
        int[] w = s.window();
        return Sulk.key(nowKst(), w[0], w[1]);
    }

    private boolean windowOpen(UUID u) {
        int[] w = s.window();
        return m.hours(u, w[0], w[1]);
    }

    private boolean sulking(UUID u) {
        int[] w = s.window();
        return Sulk.sulking(get(u, "sulk"), nowKst(), w[0], w[1]);
    }

    private long between(String path, int lo, int hi) {
        List<Integer> l = main().getIntegerList(path);
        int a = l.size() >= 2 ? l.get(0) : main().getInt(path, lo), b = l.size() >= 2 ? l.get(1) : main().getInt(path, hi);
        int x = Math.max(0, Math.min(a, b)), y = Math.max(x, Math.max(a, b));
        return 1000L * (x + ThreadLocalRandom.current().nextInt(y - x + 1));
    }

    private long gap(UUID u, boolean first) {
        if (m.test(u)) return 1000L * Math.max(1, main().getInt("visit.test-gap-seconds", 5));
        return first ? between("visit.first-gap-seconds", 40, 90) : between("visit.gap-seconds", 70, 160);
    }

    private long typing(String line) {
        return s.typingMillis(line);
    }

    private boolean inGuide(Player p) {
        return eco.guide() != null && eco.guide().inGuide(p);
    }

    private Wishes.Wish wish(UUID u) {
        String id = get(u, "wish");
        return id == null ? null : Wishes.byId(wishes, id);
    }

    private static void remember(Deque<String> d, String line, int keep) {
        d.addLast(line);
        while (d.size() > Math.max(1, keep)) d.removeFirst();
    }

    /** The week's visitor of this solver (built again from the notes when it is not in memory). */
    private Guest guest(UUID u) {
        Guest g = guests.get(u);
        if (g != null) return g;
        String name = get(u, "name");
        if (name == null) return null;
        g = new Guest(u, name, get(u, "solo") != null, s);
        // back after a join, a restart or a reload: they never left, so they simply go on after a first pause
        Wishes.Wish w = wish(u);
        int asked = (int) getLong(u, "asked", 0);
        if (w != null && asked < w.questions().size()) g.nextAt = System.currentTimeMillis() + gap(u, true) + typing(w.questions().get(asked));
        guests.put(u, g);
        return g;
    }

    /** One public chat line of the visitor: to everyone (and the console, marked as fake), or to the solver alone. */
    private void say(Guest g, String words) {
        remember(g.recent, words, main().getInt("chat.recent", 8));
        g.saidAt = System.currentTimeMillis();
        Component line = g.look.chat(words);
        if (g.solo) {
            Player p = Bukkit.getPlayer(g.solver);
            if (p != null) p.sendMessage(line);
            return;
        }
        for (Player q : Bukkit.getOnlinePlayers()) q.sendMessage(line);
        eco.getLogger().info("[비밀] 다음 사람 (" + g.name + ", 가짜 채팅): " + words);
    }

    private void queue(Guest g, String line, long at) {
        g.pending.addLast(new Pending(line, Math.max(at, g.lastPending(at))));
    }

    // ------------------------------------------------------------------------------------------
    // someone joins
    // ------------------------------------------------------------------------------------------

    /** Whether the place a wish is about exists on this server now. */
    private boolean usable(Wishes.Wish w) {
        return switch (w.task().where()) {
            case STOP -> eco.bus() != null && !eco.bus().stops().isEmpty();
            case MONUMENT -> s.centre().isPresent();
            case STONE -> s.stone().isPresent();
            default -> true;
        };
    }

    private Wishes.Wish chooseWish(UUID u) {
        List<String> recent = Wishes.recent(kept(u, "wishes", ""));
        int i = Wishes.pick(wishes.size(), Wishes.seed(week(u), u.getLeastSignificantBits()), x -> usable(wishes.get(x)), x -> recent.contains(wishes.get(x).id()));
        return i < 0 ? null : wishes.get(i);
    }

    /** A name of the pool that is nobody's (no account that ever joined, no nickname, not his), and not a recent visitor's. */
    private String chooseName(UUID u) {
        List<String> pool = names(), recent = Wishes.recent(kept(u, "names", ""));
        int i = Wishes.pick(pool.size(), Wishes.seed(week(u), u.getMostSignificantBits() ^ 0x5DEECE66DL),
                x -> Whispers.account(pool.get(x)) && !s.somebody(pool.get(x), true), x -> recent.contains(pool.get(x)));
        return i < 0 ? null : pool.get(i);
    }

    @Override
    protected boolean offer(Player p) {
        UUID u = p.getUniqueId();
        if (!windowOpen(u)) return false;
        long now = System.currentTimeMillis();
        if (Bukkit.getOnlinePlayers().size() < Math.max(1, cfg().getInt("min-online", 1))) return false;
        if (now - lastJoin < 1000L * Math.max(0, main().getInt("visit.after-join-seconds", 45))) return false;   // not on a real player's heels
        if (!chance(p, "chance", 0.003)) return false;
        return begin(p, m.test(u) || !publicChat());
    }

    /** Admin 시작: now. Test mode: only that tester sees the visitor (quick gaps). Else everyone does, as on any week. */
    @Override
    protected void start(Player p) {
        UUID u = p.getUniqueId();
        if (begin(p, m.test(u) || !publicChat())) set(u, "forced", 1);   // (asks now, whatever the hour)
        else if (p.hasPermission("stsv.admin")) {
            p.sendMessage(Text.msg("<red>다음 사람: 쓸 수 있는 이름이나 소원이 없어요 (sequel.names 가 모두 실제 계정 · 닉네임과 겹치거나, sequel.wishes 가 비었어요)"));
        }
    }

    private boolean begin(Player p, boolean solo) {
        UUID u = p.getUniqueId();
        Wishes.Wish w = chooseWish(u);
        String name = chooseName(u);
        if (w == null || name == null) return false;
        long now = System.currentTimeMillis();
        guests.remove(u);
        set(u, "name", name);
        set(u, "wish", w.id());
        set(u, "asked", 0);
        set(u, "joined", now);
        if (solo) set(u, "solo", 1);
        else remove(u, "solo");
        keep(u, "names", Wishes.remember(kept(u, "names", ""), name, cfg().getInt("avoid-recent-names", 8)));
        keep(u, "wishes", Wishes.remember(kept(u, "wishes", ""), w.id(), cfg().getInt("avoid-recent-wishes", 4)));
        Guest g = new Guest(u, name, solo, s);
        g.nextAt = now + gap(u, true) + typing(w.questions().getFirst());
        guests.put(u, g);
        Component join = g.look.joinLine(true);
        if (solo) {
            p.sendMessage(join);
        } else {
            for (Player q : Bukkit.getOnlinePlayers()) q.sendMessage(join);
            eco.getLogger().info("[비밀] 다음 사람 (" + name + ") 이 들어온 것처럼 보였습니다 (가짜 입장 줄 · 후속 임무 · 푼 사람 " + p.getName()
                    + " · 접속 " + Bukkit.getOnlinePlayers().size() + "명 · 소원 " + w.id() + ")");
        }
        return true;
    }

    /** The one private line: the old stone tells its keeper that someone else has come. Looks like a whisper. */
    private Component told() {
        String from = line("told-from", "열세 번째 돌"), words = line("told", "…또 한 명 왔어요.");
        return Component.translatable("commands.message.display.incoming", Component.text(from),
                        Component.text(PlainTextComponentSerializer.plainText().serialize(Text.mm(words))))
                .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC);
    }

    // ------------------------------------------------------------------------------------------
    // every second
    // ------------------------------------------------------------------------------------------

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        Guest g = guest(u);
        Wishes.Wish w = wish(u);
        if (g == null || w == null) {   // nothing could begin (no free name), or the wish was taken out of the file
            guests.remove(u);
            fail(p, "none");
            return;
        }
        long now = System.currentTimeMillis();
        if (get(u, "told") == null && now - getLong(u, "joined", now) >= 1000L * Math.max(1, cfg().getInt("told-seconds", 12))) {
            set(u, "told", 1);
            p.sendMessage(told());
        }
        if (s.somebody(g.name, false)) return;   // a real player of that name is online: the visitor is not, while they are
        while (!g.pending.isEmpty() && now >= g.pending.peekFirst().at()) say(g, g.pending.removeFirst().words());
        if (step(u) == 2) {
            thank(p, g, w, now);
            return;
        }
        int n = w.questions().size(), asked = (int) getLong(u, "asked", 0);
        if (asked < n) {
            if (now >= g.nextAt && g.pending.isEmpty() && mayAsk(u, g, now)) ask(p, g, w, asked, now);
        } else {
            if (step(u) == 0) step(u, 1);
            rest(u, g, now);
        }
        if (step(u) == 1) errand(p, g, w);
    }

    /** Questions are only asked inside the visiting window, not on a night the visitor was sworn at, and to someone. */
    private boolean mayAsk(UUID u, Guest g, long now) {
        if (sulking(u) || !(windowOpen(u) || get(u, "forced") != null)) return false;
        if (g.solo) return true;
        return Bukkit.getOnlinePlayers().size() >= Math.max(1, cfg().getInt("min-online", 1));
    }

    /** One question, now and then with a slip in it (never in its first word) that is put right a moment later. */
    private void ask(Player p, Guest g, Wishes.Wish w, int asked, long now) {
        UUID u = p.getUniqueId();
        String q = w.questions().get(asked);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<String> fixes = s.fixLines();
        Optional<Typing.Slip> slip = r.nextDouble() < main().getDouble("typo.chance", 0.08) && !fixes.isEmpty() ? Typing.slip(q, r) : Optional.empty();
        if (slip.isPresent() && Wishes.keepsFirstWord(slip.get().typed(), q)) {
            say(g, slip.get().typed());
            String fix = Talk.pick(fixes, g.recent, r).replace("{word}", slip.get().word());
            g.pending.addLast(new Pending(fix, now + 600 + Typing.millis(fix, r, 0.3, 1.2, 4, 7)));
        } else {
            say(g, q);
        }
        asked++;
        set(u, "asked", asked);
        g.askedAt = now;
        if (asked >= w.questions().size()) {
            g.endAt = now + 1000L * (m.test(u) ? main().getInt("chat.test-linger-seconds", 120) : main().getInt("chat.linger-seconds", 1800));
            step(u, 1);
        } else {
            g.nextAt = now + gap(u, false) + typing(w.questions().get(asked));
        }
    }

    /**
     * After the last question: a while of small talk, then one tired line, the visitor's last of the night (no leave line,
     * ever). Once only; when the small-talk time was cut short (the solver left, a restart) they are simply quiet after it.
     */
    private void rest(UUID u, Guest g, long now) {
        if (get(u, "tired") != null) return;
        if (g.endAt == 0) {   // back after the last question (a join, a restart), or sworn at since: quiet from here on
            set(u, "tired", 1);
            return;
        }
        if (now < g.endAt || !g.pending.isEmpty()) return;
        set(u, "tired", 1);
        List<String> pool = s.tiredLines();
        if (pool.isEmpty() || sulking(u)) return;
        String line = Talk.pick(pool, g.recent, ThreadLocalRandom.current());
        g.pending.addLast(new Pending(line, now + typing(line)));
    }

    // ------------------------------------------------------------------------------------------
    // the errand
    // ------------------------------------------------------------------------------------------

    private static double flat(Location a, double x, double z) {
        return Math.hypot(a.getX() - x, a.getZ() - z);
    }

    /** Whether they are at the place the wish is about (not WATER: that is asked of the thing dropped). */
    private boolean there(Player p, Wishes.Task t) {
        Location l = p.getLocation();
        World w = l.getWorld();
        return switch (t.where()) {
            case SPAWN -> {
                World first = Bukkit.getWorlds().getFirst();
                Location sp = first.getSpawnLocation();
                yield first.equals(w) && flat(l, sp.getX(), sp.getZ()) <= t.range();
            }
            case STOP -> {
                if (eco.bus() == null) yield false;
                for (BusStop b : eco.bus().stops()) if (b.world().equals(w.getName()) && flat(l, b.x(), b.z()) <= t.range()) yield true;
                yield false;
            }
            case MONUMENT -> s.centre().filter(c -> c.getWorld().equals(w) && c.distanceSquared(l) <= t.range() * t.range()).isPresent();
            case STONE -> s.stone().filter(c -> c.getWorld().equals(w) && c.distanceSquared(l) <= t.range() * t.range()).isPresent();
            case HIGH -> w.getEnvironment() == World.Environment.NORMAL && l.getY() >= t.height() && w.getHighestBlockYAt(l) <= l.getBlockY();
            case ANY -> true;
            case WATER -> false;
        };
    }

    /** Whether a dropped thing lies in water at least this deep (the water above and below it, counted). */
    static boolean deep(Location at, int depth) {
        World w = at.getWorld();
        int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
        if (w == null || !w.isChunkLoaded(x >> 4, z >> 4) || w.getBlockAt(x, y, z).getType() != Material.WATER) return false;
        int n = 1;
        for (int yy = y - 1; yy >= w.getMinHeight() && n < depth && w.getBlockAt(x, yy, z).getType() == Material.WATER; yy--) n++;
        for (int yy = y + 1; yy < w.getMaxHeight() && n < depth && w.getBlockAt(x, yy, z).getType() == Material.WATER; yy++) n++;
        return n >= depth;
    }

    private static boolean nightIn(World w) {
        return w.getEnvironment() == World.Environment.NORMAL && Wishes.night(w.getTime());
    }

    private static boolean holds(Player p, List<String> items) {
        for (ItemStack it : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
            if (it != null && !it.isEmpty() && !Items.trial(it) && Wishes.listed(Items.key(it), items)) return true;
        }
        return false;
    }

    /** Staying / holding: counted while they are there, second by second (it starts over when they step away). */
    private void errand(Player p, Guest g, Wishes.Wish w) {
        Wishes.Task t = w.task();
        if (t.act() == Wishes.Act.DROP) return;   // (onDrop)
        boolean ok = !p.isDead() && there(p, t) && (!t.night() || nightIn(p.getWorld())) && (t.act() == Wishes.Act.STAY || holds(p, t.items()));
        if (!ok) {
            g.held = 0;
            return;
        }
        int need = m.test(p.getUniqueId()) ? Math.min(5, t.seconds()) : t.seconds();
        if (++g.held >= need) fulfilled(p, g, w);
    }

    /** Dropping: the thing is looked at where it was dropped (in water: where it lies two seconds later). Nothing is taken. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (guests.isEmpty()) return;
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        Guest g = guests.get(u);
        if (g == null || !may(p) || !live(u) || step(u) != 1) return;
        Wishes.Wish w = wish(u);
        if (w == null || w.task().act() != Wishes.Act.DROP) return;
        Wishes.Task t = w.task();
        ItemStack it = e.getItemDrop().getItemStack();
        if (Items.trial(it) || !Wishes.listed(Items.key(it), t.items())) return;
        if (t.night() && !nightIn(p.getWorld())) return;
        if (t.where() != Wishes.Where.WATER) {
            if (there(p, t)) fulfilled(p, g, w);
            return;
        }
        Item drop = e.getItemDrop();
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!drop.isValid() || !p.isOnline() || !may(p) || !live(u) || step(u) != 1 || guests.get(u) != g) return;
            if (deep(drop.getLocation(), t.depth())) fulfilled(p, g, w);
        }, 40L);
    }

    /** The solver did it: the visitor's thanks are on their way (their last words), then the reward. */
    private void fulfilled(Player p, Guest g, Wishes.Wish w) {
        UUID u = p.getUniqueId();
        if (step(u) != 1) return;
        String line = Talk.pick(thanks(w), g.recent, ThreadLocalRandom.current());
        set(u, "thanks", line == null ? "" : line);
        g.pending.clear();
        g.thanksAt = System.currentTimeMillis() + 1500L + (line == null ? 0 : typing(line));
        step(u, 2);
    }

    private void thank(Player p, Guest g, Wishes.Wish w, long now) {
        UUID u = p.getUniqueId();
        if (get(u, "said") == null) {
            String line = get(u, "thanks", "");
            if (g.thanksAt == 0) g.thanksAt = now + 1500L + typing(line);   // they left in between: said when they are back
            if (now < g.thanksAt) return;
            set(u, "said", 1);
            if (!line.isBlank()) say(g, line);
        }
        guests.remove(u);
        complete(p);
    }

    // ------------------------------------------------------------------------------------------
    // real chat while the visitor is around: small talk, and sulking when sworn at
    // ------------------------------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        lastJoin = System.currentTimeMillis();
    }

    /** Only the words go on, to the main thread (like his own reading of the chat). */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (guests.isEmpty()) return;
        UUID by = e.getPlayer().getUniqueId();
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(eco, () -> {
            for (Guest g : List.copyOf(guests.values())) heard(g, by, text);
        });
    }

    /** The visitor of a solver who is online and may play, whose week is still going on; else null. */
    private Player keeper(Guest g) {
        Player p = Bukkit.getPlayer(g.solver);
        return p != null && may(p) && live(g.solver) && guests.get(g.solver) == g ? p : null;
    }

    private boolean free(Guest g, UUID by, boolean named, long now, boolean quick) {
        if (now < g.hushUntil || !g.pending.isEmpty()) return false;
        if (!quick && g.reactions >= main().getInt("chat.max-per-night", 6)) return false;
        long gap = 1000L * (quick ? main().getInt("chat.test-min-gap-seconds", 5) : main().getInt("chat.min-gap-seconds", 45));
        if (g.lastReactAt != 0 && now - g.lastReactAt < gap) return false;
        return named || !by.equals(g.lastBy) || now - g.lastReactAt >= 1000L * main().getInt("chat.same-player-seconds", 120);
    }

    private void heard(Guest g, UUID by, String text) {
        if (text == null || text.isBlank() || keeper(g) == null) return;
        UUID u = g.solver;
        if (g.solo && !by.equals(u)) return;
        Player sp = Bukkit.getPlayer(by);
        if (sp == null || inGuide(sp) || s.somebody(g.name, false)) return;
        if (step(u) == 2 || get(u, "tired") != null || sulking(u) || g.saidAt == 0) return;   // gone quiet, or not here tonight
        long now = System.currentTimeMillis();
        Wishes.Wish w = wish(u);
        boolean asking = w != null && getLong(u, "asked", 0) < w.questions().size();
        if (!asking && now >= g.endAt && g.pending.isEmpty()) return;
        long sulkWindow = 1000L * main().getInt("sulk.window-seconds", 40);
        if (s.insult(text) && (Sulk.aimed(text, g.name, s.aim()) || now - g.saidAt <= sulkWindow)) {
            sulk(g, sp, null);
            return;
        }
        boolean named = Talk.mentions(text, g.name), quick = m.test(u);
        long window = 1000L * main().getInt("chat.answer-window-seconds", 40);
        int asked = (int) getLong(u, "asked", 0);
        boolean afterQuestion = asked > 0 && g.thanked != asked && now - g.askedAt <= window;
        if (!free(g, by, named, now, quick)) return;
        Optional<Talk.Intent> found = Talk.match(g.talk, text, named || now - g.saidAt <= window, afterQuestion);
        if (found.isEmpty()) return;
        Talk.Intent i = found.get();
        if (i.answer) g.thanked = asked;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (!quick && r.nextDouble() >= i.chance) return;
        String line = Talk.pick(!i.slip.isEmpty() && r.nextDouble() < i.slipChance ? i.slip : i.lines, g.recent, r);
        if (line == null) return;
        long at = now + typing(line);
        queue(g, line, at);
        if (!i.follow.isEmpty() && r.nextDouble() < i.followChance) {
            String follow = Talk.pick(i.follow, g.recent, r);
            queue(g, follow, at + 1500 + typing(follow));
        }
        g.reactions++;
        g.lastReactAt = now;
        g.lastBy = by;
        if (asking) g.nextAt = Math.max(g.nextAt, g.lastPending(now) + (quick ? 2000L : 8000L));
    }

    /**
     * Sworn at, like him: one hurt line (in public after the typing time; whisperTo: whispered back to that player), and
     * nothing more that night. What is left of the questions is asked on another night of the week. No leave line.
     */
    private void sulk(Guest g, Player by, Player whisperTo) {
        UUID u = g.solver;
        set(u, "sulk", nightKey());
        g.pending.clear();
        g.endAt = 0;
        eco.getLogger().info("[비밀] 다음 사람 (" + g.name + "): " + by.getName() + " 의 욕에 삐져 오늘 밤은 말이 없음 (" + (whisperTo == null ? "공개 채팅" : "귓속말") + ")");
        String line = Talk.pick(s.hurtLines(), whisperTo == null ? g.recent : g.recentWhispers, ThreadLocalRandom.current());
        if (line == null || line.isBlank()) return;
        long now = System.currentTimeMillis();
        if (whisperTo == null) {
            // (said through the queue: tick() flushes what is typed before it asks whether they sulk)
            g.pending.addLast(new Pending(line, now + typing(line)));
            return;
        }
        remember(g.recentWhispers, line, 4);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (whisperTo.isOnline() && keeper(g) != null) whisperTo.sendMessage(g.look.incoming(line));
        }, Math.max(1, typing(line) / 50));
    }

    /**
     * A whisper to the week's visitor (and to nobody else: the target must be exactly their name, and no real player may
     * have it). The sender sees their own words as the game shows them; the visitor answers a little, like him.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWhisper(PlayerCommandPreprocessEvent e) {
        if (guests.isEmpty()) return;
        Optional<Whispers.Whisper> w = Whispers.parse(e.getMessage(), s.whisperLabels());
        if (w.isEmpty()) return;
        Player p = e.getPlayer();
        UUID by = p.getUniqueId();
        for (Guest g : guests.values()) {
            if (!Whispers.to(w.get(), g.name) || keeper(g) == null) continue;
            if ((g.solo && !by.equals(g.solver)) || inGuide(p) || s.somebody(g.name, false)) return;
            e.setCancelled(true);
            p.sendMessage(g.look.outgoing(w.get().message()));
            UUID u = g.solver;
            long now = System.currentTimeMillis();
            if (step(u) == 2) return;                    // their last words are being typed: nothing else
            boolean sulky = sulking(u);
            if (s.insult(w.get().message())) {
                if (!sulky) sulk(g, p, p);
                return;
            }
            if (sulky) return;
            if (now - g.answered.getOrDefault(by, 0L) < 1000L * main().getInt("whisper.cooldown-seconds", 20)) return;
            g.answered.put(by, now);
            Optional<Talk.Intent> found = Talk.match(g.whisper, w.get().message(), true, false);
            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (found.isEmpty() || r.nextDouble() >= found.get().chance) return;
            String line = Talk.pick(found.get().lines, g.recentWhispers, r);
            if (line == null || line.isBlank()) return;
            remember(g.recentWhispers, line, 4);
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (p.isOnline() && keeper(g) != null && step(u) != 2 && !s.somebody(g.name, false)) p.sendMessage(g.look.incoming(line));
            }, Math.max(1, typing(line) / 50));
            return;
        }
    }

    // ------------------------------------------------------------------------------------------
    // coming and going
    // ------------------------------------------------------------------------------------------

    @Override
    protected void cleanup(UUID u) {
        guests.remove(u);
    }

    // (expired: nothing of the player's is ever held, and nothing was given; the visitor just fades with the week)

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    /** The errand in words, for the admin's status line. */
    static String describe(Wishes.Task t) {
        String where = switch (t.where()) {
            case SPAWN -> "첫 월드 스폰 " + (int) t.range() + "칸 안";
            case STOP -> "아무 버스 정류장 " + (int) t.range() + "칸 안";
            case MONUMENT -> "기념비 " + (int) t.range() + "칸 안";
            case STONE -> "열세 번째 돌 " + (int) t.range() + "칸 안";
            case WATER -> "깊이 " + t.depth() + "칸 이상의 물";
            case HIGH -> "Y " + t.height() + " 이상, 하늘이 트인 곳";
            case ANY -> "어디서든";
        };
        String what = switch (t.act()) {
            case DROP -> (t.where() == Wishes.Where.WATER ? "에 " : "에서 ") + String.join(" · ", t.items().size() > 4 ? t.items().subList(0, 4) : t.items())
                    + (t.items().size() > 4 ? " 등" : "") + " 중 하나를 버리기(Q)" + (t.where() == Wishes.Where.WATER ? " (2초 뒤 물속에 있어야 함)" : "");
            case HOLD -> "에서 " + String.join(" · ", t.items()) + " 중 하나를 손에 들고 " + t.seconds() + "초";
            case STAY -> "에 " + t.seconds() + "초 머물기";
        };
        return (t.night() ? "(월드 시간) 밤에 " : "") + where + what;
    }

    @Override
    public String status(UUID u) {
        if (!live(u)) return "소원 " + wishes.size() + "가지 · 최근 이름 " + kept(u, "names", "-") + " · 최근 소원 " + kept(u, "wishes", "-")
                + (publicChat() ? "" : " · 공개 채팅 끔 (푼 사람에게만 보임)");
        Wishes.Wish w = wish(u);
        String name = get(u, "name", "?");
        if (w == null) return "방문자 " + name + " · 소원 " + get(u, "wish", "?") + " (설정에서 사라짐: 다음 틱에 끝남)";
        int n = w.questions().size(), asked = (int) getLong(u, "asked", 0);
        Guest g = guests.get(u);
        long now = System.currentTimeMillis();
        String what = switch (step(u)) {
            case 2 -> "소원을 들어줌: 방문자의 마지막 한마디 뒤 보상";
            case 1 -> "할 일: " + describe(w.task());
            default -> s.somebody(name, false) ? "같은 이름의 실제 플레이어가 접속 중이라 쉼"
                    : sulking(u) ? "삐져서 오늘 밤은 말이 없음 (남은 질문은 다음 밤에)"
                    : !windowOpen(u) && get(u, "forced") == null ? "방문 시간대(visit.hours)를 기다림: 남은 질문은 그때"
                    : g == null ? "푼 사람이 접속하면 이어서 물음" : "다음 질문까지 " + Math.max(0, (g.nextAt - now) / 1000) + "초";
        };
        return "방문자 " + name + (get(u, "solo") != null ? " (이 사람에게만 보임)" : " (모두에게 보임)") + " · 소원 " + w.id() + " “" + w.says() + "” · 질문 " + asked + "/" + n
                + (get(u, "told") != null ? "" : " · 아직 귀띔 전") + " · " + what;
    }

    @Override
    public List<String> scenes() {
        List<String> out = new ArrayList<>(List.of("입장", "질문", "귀띔", "감사"));
        for (Wishes.Wish w : wishes) out.add("소원:" + w.id());
        return out;
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        String note = "";
        List<String> pool = names();
        String name = live(u) && get(u, "name") != null ? get(u, "name") : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        if (word.startsWith("소원:")) {
            Wishes.Wish w = Wishes.byId(wishes, word.substring(3));
            if (w == null) return false;
            if (!live(u) || get(u, "name") == null) {
                admin.sendMessage(Text.mm("<red>[다음 사람] 진행 중인 후속 임무가 없어요. 먼저 /관리자 비밀 후속 새로온사람 시작 " + p.getName()));
                return true;
            }
            for (String k : List.of("tired", "said", "thanks", "sulk")) remove(u, k);
            set(u, "wish", w.id());
            set(u, "asked", 0);
            step(u, 0);
            guests.remove(u);
            Guest g = guest(u);
            if (g != null) g.nextAt = System.currentTimeMillis() + 3000L;
            note = " (이번 주 방문자의 소원을 “" + w.says() + "” 로 바꿔 질문을 처음부터: " + describe(w.task()) + ")";
        } else switch (word) {
            case "입장" -> p.sendMessage(s.lookFor(name).joinLine(true));
            case "질문" -> {
                Wishes.Wish w = live(u) && wish(u) != null ? wish(u) : wishes.get(ThreadLocalRandom.current().nextInt(wishes.size()));
                Look look = s.lookFor(name);
                for (int i = 0; i < w.questions().size(); i++) {
                    String q = w.questions().get(i);
                    Bukkit.getScheduler().runTaskLater(eco, () -> {
                        if (p.isOnline()) p.sendMessage(look.chat(q));
                    }, 20L + i * 60L);
                }
                admin.sendMessage(Text.mm("<gray>[다음 사람] 소원 " + w.id() + ": “" + w.says() + "” (질문 첫 낱말) → " + describe(w.task())));
                note = " (3초 간격, 그 사람에게만; 아무것도 바뀌지 않음)";
            }
            case "귀띔" -> p.sendMessage(told());
            case "감사" -> {
                Wishes.Wish w = live(u) ? wish(u) : null;
                String line = Talk.pick(thanks(w), List.of(), ThreadLocalRandom.current());
                if (line != null) p.sendMessage(s.lookFor(name).chat(line));
            }
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] " + word + " → " + p.getName() + note));
        return true;
    }
}
