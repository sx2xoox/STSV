package dev.stsv.economy.secret.letter;

import dev.stsv.core.util.Text;
import dev.stsv.economy.land.Land;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 내일의 나 (docs/admin/숨은_비밀/내일의_나.md, contents/economy/secrets/letter.yml). One day, very rarely, a letter
 * comes to the mailbox in the player's own name, dated tomorrow, telling them what they did. Five letters in one night:
 * each asks for one small ritual, written as a riddle drawn from the player's own past (the first earth they called
 * theirs, the thing they let go of most, five minutes of not answering to their name, the spot they first opened their
 * eyes in this world, a locked door). Do it and the next letter comes a few minutes later ({@code delivery.gap-seconds});
 * log off and it comes soon after they are back. Leave a letter undone for three Korean days and the letters stop.
 * With every letter one more thing comes apart around them ({@link Omens}), faster each time, and the signature is a
 * little less theirs. After the last one it knocks ({@link Finale}); outlast it and it says "그럼 네가 계속 해" —
 * solved: the title, the money, and from then on others sometimes see the solver where they stood a second ago
 * ({@link Shadow}).
 * <p>
 * Stage n (1..5) = letter n is theirs. Saved (eco.kv "secret.letter.<uuid>."): {@code day} the Korean day the letter came
 * (or they were last caught), {@code at} when it came (ms), {@code ok} when its ritual was done (ms), {@code next} when the
 * next letter is due (ms), {@code read}, {@code target} the ritual's place or item ({@link Target}), {@code quiet} silent
 * ms so far, {@code caught} when it last caught them, {@code rolled} the last day the start chance was rolled,
 * {@code seed} for the signature, {@code renamed} letters that already renamed a thing.
 */
public final class Letters extends Secret {

    private static final List<String> KEYS = List.of("day", "at", "ok", "next", "read", "target", "quiet", "caught", "renamed", "failed", "miss", "tried");

    private final LetterBook book;
    private final Omens omens;
    private final Finale finale;
    private final Shadow shadow;
    /** when the waiting letter may come (ms) this session */
    private final Map<UUID, Long> due = new ConcurrentHashMap<>();
    /** seconds stood on the ritual's spot */
    private final Map<UUID, Integer> stay = new ConcurrentHashMap<>();
    /** silent ms so far (saved every few seconds) */
    private final Map<UUID, Long> quiet = new ConcurrentHashMap<>();
    /** seconds waited at home on the last night */
    private final Map<UUID, Integer> waited = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextPoll = new ConcurrentHashMap<>();
    /** the newest sale already answered with "not that" (letter 2) */
    private final Map<UUID, Long> wrongSale = new ConcurrentHashMap<>();
    /** when the "you are close" whisper may come again */
    private final Map<UUID, Long> nextNear = new ConcurrentHashMap<>();

    public Letters(SecretModule m) {
        super(m);
        book = new LetterBook(this, m, eco);
        omens = new Omens(this, m);
        finale = new Finale(this, m);
        shadow = new Shadow(this);
    }

    @Override
    public String id() {
        return "letter";
    }

    @Override
    public String name() {
        return "내일의나";
    }

    @Override
    public int stages() {
        return 5;
    }

    YamlConfiguration cfg() {
        return cfg;
    }

    JavaPlugin plugin() {
        return eco;
    }

    // ------------------------------------------------------------------------------------------
    // start, jump, status
    // ------------------------------------------------------------------------------------------

    @Override
    public void start(Player p) {
        UUID u = p.getUniqueId();
        clear(u);
        seed(u);
        m.stage(this, u, 1);
        deliver(p, 1);
    }

    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        clear(u);
        if (stage <= 0) {
            m.stage(this, u, 0);
            return;
        }
        seed(u);
        m.stage(this, u, stage);
        deliver(p, stage);
    }

    private void clear(UUID u) {
        for (String k : KEYS) m.remove(this, u, k);
        due.remove(u);
        stay.remove(u);
        quiet.remove(u);
        waited.remove(u);
        nextPoll.remove(u);
        wrongSale.remove(u);
        nextNear.remove(u);
    }

    /** A number for this player's signature drift (made once, kept). */
    long seed(UUID u) {
        String s = m.get(this, u, "seed");
        if (s != null) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                // hand-edited: make a new one
            }
        }
        long v = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        m.set(this, u, "seed", v);
        return v;
    }

    @Override
    public String status(UUID u) {
        int n = m.stage(this, u);
        if (n > stages()) return "그림자 분신 " + (shadow.enabled() ? "켜짐" : "꺼짐 (letter.yml shadow.enabled)");
        if (n <= 0) {
            String r = m.get(this, u, "rolled");
            return r == null ? "" : "마지막 시작 확률 굴림 " + r;
        }
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder("편지 " + n + " (" + m.get(this, u, "day", "?") + ")");
        b.append(" · ").append(m.get(this, u, "read") != null ? "읽음" : "안 읽음");
        if (m.get(this, u, "ok") != null) {
            long next = m.getLong(this, u, "next", now);
            b.append(" · 의식 완료 · 다음 편지 ").append(n >= stages() ? "-" : next > now ? (next - now) / 1000 + "초 뒤" : "접속하면 곧");
        } else {
            b.append(" · 의식 대기");
            long gone = LetterText.dayNo(m.today(u)) - LetterText.dayNo(m.get(this, u, "day"));
            b.append(" · 방치 ").append(gone).append("/").append(lostDays(u)).append("일");
        }
        Target t = Target.decode(m.get(this, u, "target"));
        if (t.kind() != Target.Kind.NONE) b.append(" · 목표 ").append(t.describe());
        if (n == 3) b.append(" · 침묵 ").append(quietMs(u) / 1000).append("/").append(silenceMs(u) / 1000).append("초");
        if (n == 5) {
            b.append(" · 기다림 ").append(waited.getOrDefault(u, 0)).append("/").append(waitSeconds(u)).append("초");
            long c = m.getLong(this, u, "caught", 0);
            if (c > 0) {
                long left = c + retryMs(u) - now;
                b.append(" · 마지막으로 잡힘 ").append((now - c) / 1000).append("초 전").append(left > 0 ? " (다시까지 " + left / 1000 + "초)" : "");
            }
            if (finale.running(u)) b.append(" · <red>지금 진행 중");
        }
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------
    // time and numbers
    // ------------------------------------------------------------------------------------------

    private long secs(UUID u, String real, String test, long def) {
        return m.test(u) && cfg.contains(test) ? cfg.getLong(test) : cfg.getLong(real, def);
    }

    /** Korean days (test days in test mode) a letter may lie undone before the letters stop. */
    private long lostDays(UUID u) {
        return Math.max(1, secs(u, "delivery.lost-days", "delivery.test-lost-days", 3));
    }

    long silenceMs(UUID u) {
        return m.test(u) ? cfg.getLong("rituals.silence.test-seconds", 40) * 1000L : cfg.getLong("rituals.silence.minutes", 5) * 60_000L;
    }

    /** How far the silence has gone, 0..1 (0 when it is not running). */
    double silenceProgress(UUID u) {
        return silenceActive(u) ? Math.min(1.0, quietMs(u) / (double) Math.max(1, silenceMs(u))) : 0;
    }

    private int waitSeconds(UUID u) {
        return (int) secs(u, "rituals.wait.seconds", "rituals.wait.test-seconds", 30);
    }

    private long retryMs(UUID u) {
        return m.test(u) ? cfg.getLong("chase.test-retry-seconds", 20) * 1000L : cfg.getLong("chase.retry-minutes", 10) * 60_000L;
    }

    private long quietMs(UUID u) {
        return quiet.computeIfAbsent(u, k -> m.getLong(this, u, "quiet", 0));
    }

    // ------------------------------------------------------------------------------------------
    // each second
    // ------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            int n = m.stage(this, u);
            if (n == 0) {
                maybeStart(p);
                continue;
            }
            if (n > stages()) {
                shadow.tick(p);
                continue;
            }
            if (!open(u)) continue;
            progress(p, n);
            if (m.stage(this, u) == n) omens.tick(p, n);
        }
    }

    /** Once a day, after a while online: the very small chance the first letter comes. */
    private void maybeStart(Player p) {
        UUID u = p.getUniqueId();
        if (!open(u)) return;
        String today = m.today(u);
        if (today.equals(m.get(this, u, "rolled"))) return;
        long online = System.currentTimeMillis() - m.memory().sessionStart(u);
        if (!m.test(u) && online < cfg.getLong("start.after-online-minutes", 10) * 60_000L) return;
        m.set(this, u, "rolled", today);
        if (m.roll(u, cfg.getDouble("start.chance", 0.005))) start(p);
    }

    /**
     * True once the letter may come: at {@code until} when it was set this session (the gap after a ritual), else a short
     * while after they came back ({@code delivery.login-seconds}) — a letter is never pushed into the first breath of a login.
     */
    private boolean due(UUID u, long until) {
        long now = System.currentTimeMillis();
        Long d = due.get(u);
        if (d == null) {
            due.put(u, Math.max(until, now + secs(u, "delivery.login-seconds", "delivery.test-login-seconds", 30) * 1000L));
            return false;
        }
        if (now < d) return false;
        due.remove(u);
        return true;
    }

    private void progress(Player p, int n) {
        UUID u = p.getUniqueId();
        String day = m.get(this, u, "day");
        if (day == null) {
            // the letter never got written (it was cut off): write it now
            if (due(u, 0)) deliver(p, n);
            return;
        }
        if (m.get(this, u, "ok") != null) {
            // done: the next letter comes a few minutes later (or soon after they are back)
            if (n < stages() && due(u, m.getLong(this, u, "next", 0))) {
                m.stage(this, u, n + 1);
                deliver(p, n + 1);
            }
            return;
        }
        if (finale.running(u)) return;
        String today = m.today(u);
        if (today.startsWith("t") != day.startsWith("t")) {
            // test mode was switched on or off: count the days from now
            m.set(this, u, "day", today);
            return;
        }
        if (LetterText.dayNo(today) - LetterText.dayNo(day) >= lostDays(u)) {
            broken(p);
            return;
        }
        ritual(p, n);
    }

    private void ritual(Player p, int n) {
        UUID u = p.getUniqueId();
        switch (n) {
            case 1, 4 -> {
                Target t = Target.decode(m.get(this, u, "target"));
                if (t.kind() == Target.Kind.NONE) return;
                Location l = p.getLocation();
                boolean in;
                if (t.kind() == Target.Kind.LAND) {
                    Land here = eco.land() == null ? null : eco.land().at(l);
                    in = here != null && here.id == t.land();
                } else in = t.contains(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
                if (!in) {
                    stay.remove(u);
                    near(p, n, t, l);
                    return;
                }
                int need = cfg.getInt(n == 1 ? "rituals.land.stay-seconds" : "rituals.place.stay-seconds", 5);
                if (stay.merge(u, 1, Integer::sum) >= need) complete(p, n);
            }
            case 2 -> {
                long now = System.currentTimeMillis();
                if (now < nextPoll.getOrDefault(u, 0L)) return;
                nextPoll.put(u, now + Math.max(3, cfg.getLong("rituals.sell.poll-seconds", 10)) * 1000L);
                Target t = Target.decode(m.get(this, u, "target"));
                if (t.kind() != Target.Kind.ITEM && t.kind() != Target.Kind.ANY_ITEM) return;
                long since = m.getLong(this, u, "at", now);
                m.memory().sales(u, sales -> {
                    if (!p.isOnline() || m.stage(this, u) != 2 || m.get(this, u, "ok") != null) return;
                    long newest = 0;
                    for (var sale : sales) {
                        if (sale.at() <= since) break;   // newest first
                        newest = Math.max(newest, sale.at());
                        if (t.kind() == Target.Kind.ANY_ITEM || Items.normalize(sale.item()).equals(Items.normalize(t.item()))) {
                            complete(p, 2);
                            return;
                        }
                    }
                    // they let go of something, but not that
                    if (newest > wrongSale.getOrDefault(u, since)) {
                        wrongSale.put(u, newest);
                        m.haunt().whisper(p, cfg.getString("whispers.wrong.2", "<dark_gray>…그게 아니었다."));
                    }
                });
            }
            case 3 -> {
                if (m.get(this, u, "read") == null) return;
                long q = quietMs(u) + 1000;
                quiet.put(u, q);
                if (q >= silenceMs(u)) {
                    complete(p, 3);
                    return;
                }
                if ((q / 1000) % 15 == 0) m.set(this, u, "quiet", q);
            }
            default -> waitAtHome(p);
        }
    }

    /** Close to the place a riddle means, but not on it: now and then, a hint that they are warm. */
    private void near(Player p, int n, Target t, Location l) {
        UUID u = p.getUniqueId();
        if (t.world() == null || !t.world().equals(l.getWorld().getName())) return;
        double dx = l.getX() - t.x(), dz = l.getZ() - t.z();
        double r = cfg.getDouble("rituals.near-blocks", 40);
        if (dx * dx + dz * dz > r * r) return;
        long now = System.currentTimeMillis();
        if (now < nextNear.getOrDefault(u, 0L)) return;
        nextNear.put(u, now + Math.max(5, cfg.getLong("rituals.near-every-seconds", 25)) * 1000L);
        m.haunt().whisper(p, cfg.getString("whispers.near." + n, "<dark_gray>…가까웠다."));
    }

    /** The last letter, read: at home (or anywhere when they have no land), a little while — then the knocking. Any hour. */
    private void waitAtHome(Player p) {
        UUID u = p.getUniqueId();
        if (m.get(this, u, "read") == null) return;
        if (System.currentTimeMillis() - m.getLong(this, u, "caught", 0) < retryMs(u)) return;
        if (!atHome(p) || p.isDead()) {
            waited.remove(u);
            return;
        }
        if (waited.merge(u, 1, Integer::sum) < waitSeconds(u)) return;
        waited.remove(u);
        finale.begin(p, Finale.Mode.FULL);
    }

    /** Whether they have a land of their own or one they live on. */
    boolean hasHome(UUID u) {
        if (eco.land() == null) return false;
        var idx = eco.land().store().index();
        return idx.home(u) != null || idx.ownedBy(u) != null;
    }

    /** In a land they may live in — or anywhere, when they have none. */
    private boolean atHome(Player p) {
        if (!hasHome(p.getUniqueId())) return true;
        Land here = eco.land().at(p.getLocation());
        return here != null && !here.server() && here.allows(p.getUniqueId());
    }

    private void complete(Player p, int n) {
        UUID u = p.getUniqueId();
        if (m.get(this, u, "ok") != null) return;
        long now = System.currentTimeMillis();
        long next = now + secs(u, "delivery.gap-seconds", "delivery.test-gap-seconds", 180) * 1000L;
        m.set(this, u, "ok", now);
        m.set(this, u, "next", next);
        m.remove(this, u, "quiet");
        stay.remove(u);
        quiet.remove(u);
        due.put(u, next);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!p.isOnline()) return;
            m.haunt().whisper(p, cfg.getString("whispers.done." + n, "<dark_gray>…"));
            m.haunt().sound(p, p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 0.6f);
        }, 30L);
    }

    boolean silenceActive(UUID u) {
        return m.stage(this, u) == 3 && m.get(this, u, "read") != null && m.get(this, u, "ok") == null;
    }

    boolean finaleRunning(UUID u) {
        return finale.running(u);
    }

    // ------------------------------------------------------------------------------------------
    // letters
    // ------------------------------------------------------------------------------------------

    /** Letter n comes, with its ritual's target. */
    private void deliver(Player p, int n) {
        UUID u = p.getUniqueId();
        due.remove(u);
        stay.remove(u);
        quiet.remove(u);
        waited.remove(u);
        nextPoll.remove(u);
        wrongSale.remove(u);
        nextNear.remove(u);
        m.set(this, u, "day", m.today(u));
        m.set(this, u, "at", System.currentTimeMillis());
        for (String k : List.of("ok", "next", "read", "quiet", "target", "caught", "failed", "miss", "tried")) m.remove(this, u, k);
        book.write(p, n, w -> {
            if (w.item() == null) return;
            m.set(this, u, "target", w.target().encode());
            send(u, w.item());
            Player now = Bukkit.getPlayer(u);
            if (now == null) return;
            omens.arrived(now, n);
            String renamed = m.get(this, u, "renamed", "");
            if ((n == 3 || n == 5) && !renamed.contains(String.valueOf(n))) {
                // a thing of theirs is "mine" now — from tomorrow's point of view
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (now.isOnline() && omens.rename(now)) m.set(this, u, "renamed", renamed + n);
                }, 200L);
            }
            if (n == 5) omens.joinLine(now);
        });
    }

    /** Into the mailbox (always the mailbox: a letter is not handed over), with the mailbox's own notice. */
    private void send(UUID u, ItemStack item) {
        eco.mail().store().send(u, Items.bytes(item), "secret_letter", System.currentTimeMillis());
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            p.sendMessage(Text.msg(cfg.getString("delivery.notice", "<gray>우편함에 편지가 한 통 와 있습니다. <white>/우편함")));
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
        }
    }

    /** A letter left undone for three days: one last short letter, and the chain is over (it may start again one day, by chance). */
    private void broken(Player p) {
        UUID u = p.getUniqueId();
        cleanup(u);
        clear(u);
        m.stage(this, u, 0);
        m.set(this, u, "rolled", m.today(u));
        ItemStack last = book.broken(p);
        if (last != null) send(u, last);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) m.haunt().whisper(p, cfg.getString("whispers.broken", "<dark_gray>…"));
        }, 60L);
    }

    // ------------------------------------------------------------------------------------------
    // the last night
    // ------------------------------------------------------------------------------------------

    /** It touched them (or they left, or died, while it was out): again, a little later (chase.retry-minutes). */
    void caught(Player p) {
        caught(p.getUniqueId());
    }

    private void caught(UUID u) {
        m.set(this, u, "caught", System.currentTimeMillis());
        m.set(this, u, "day", m.today(u));   // they did not ignore it: the three days start again
    }

    /** Three minutes without being touched. */
    void survived(Player p) {
        UUID u = p.getUniqueId();
        if (m.stage(this, u) != stages()) return;
        m.solve(this, p);
        if (m.stage(this, u) <= stages()) return;   // too late: someone else was first
        omens.cleanup(u);
        clear(u);
        ItemStack back = book.epilogue(p);
        if (back != null) Bukkit.getScheduler().runTaskLater(eco, () -> send(u, back), 200L);
        if (shadow.enabled()) Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) p.sendMessage(Text.mm(cfg.getString("shadow.perk-line", "")));
        }, 120L);
    }

    // ------------------------------------------------------------------------------------------
    // events
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(eco, () -> {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !on() || !silenceActive(u) || quietMs(u) <= 0) return;
            // they answered: the silence starts over
            m.remove(this, u, "quiet");
            quiet.put(u, 0L);
            omens.silenceBegan(u);
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (p.isOnline()) m.haunt().whisper(p, cfg.getString("whispers.silence-broken", "<dark_gray>…"));
            }, 40L);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        int n = m.stage(this, u);
        if (n < 1 || n > stages() || !on()) return;
        ItemStack it = e.getItem();
        if (it != null && it.hasItemMeta()) {
            Integer no = it.getItemMeta().getPersistentDataContainer().get(LetterBook.NUMBER, PersistentDataType.INTEGER);
            if (no != null && no == n && m.get(this, u, "read") == null && m.get(this, u, "day") != null) {
                m.set(this, u, "read", 1);
                if (n == 3) omens.silenceBegan(u);
            }
        }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && Tag.DOORS.isTagged(e.getClickedBlock().getType())
                && e.useInteractedBlock() != Event.Result.DENY) {
            finale.doorOpened(p, e.getClickedBlock());
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        finale.swing(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMount(EntityMountEvent e) {
        if (e.getEntity() instanceof Player p && finale.chasing(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p && finale.chasing(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (finale.stop(p.getUniqueId())) caught(p);
    }

    @Override
    public void cleanup(UUID u) {
        if (finale.stop(u)) caught(u);
        omens.cleanup(u);
        shadow.cleanup(u);
        Long q = quiet.remove(u);
        if (q != null && q > 0 && m.stage(this, u) == 3) m.set(this, u, "quiet", q);
        due.remove(u);
        stay.remove(u);
        waited.remove(u);
        nextPoll.remove(u);
        wrongSale.remove(u);
        nextNear.remove(u);
    }

    // ------------------------------------------------------------------------------------------
    // admin scenes
    // ------------------------------------------------------------------------------------------

    @Override
    public List<String> scenes() {
        return List.of("편지1", "편지2", "편지3", "편지4", "편지5", "끊긴편지", "마지막편지", "서명",
                "발소리", "탭이름", "이름부름", "아이템이름", "그림자", "입장메시지",
                "노크", "도플갱어", "퇴장메시지", "작별", "분신");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        switch (word) {
            case "편지1", "편지2", "편지3", "편지4", "편지5" -> {
                int n = word.charAt(2) - '0';
                book.write(p, n, w -> {
                    if (w.item() != null) Items.give(p, w.item()).forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
                    admin.sendMessage(Text.msg("<gray>편지 " + n + " 을(를) " + p.getName() + " 의 인벤토리에 넣었어요 (진행은 그대로). 목표: <white>" + w.target().describe()));
                });
            }
            case "끊긴편지", "마지막편지" -> {
                ItemStack it = word.equals("끊긴편지") ? book.broken(p) : book.epilogue(p);
                if (it != null) Items.give(p, it).forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
            }
            case "서명" -> {
                for (int n = 1; n <= 5; n++) {
                    admin.sendMessage(Text.mm("<gray>편지 " + n + ":"));
                    admin.sendMessage(Text.mm(book.signature(p, n)));
                }
            }
            case "발소리" -> omens.footsteps(p);
            case "탭이름" -> {
                omens.tab(p, 2);
                admin.sendMessage(Text.msg("<gray>" + p.getName() + " 의 탭 목록 아래에 이름을 하나 더 (30초). 탭 키로 확인"));
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (p.isOnline() && m.stage(this, p.getUniqueId()) < 2) omens.untab(p);
                }, 600L);
            }
            case "이름부름" -> {
                // the silence's five minutes in fifteen seconds: anyone, then the people closest, then themselves
                long[] at = {1, 70, 130, 180, 220, 250, 275, 295};
                for (int i = 0; i < at.length; i++) {
                    int tier = Riddle.callTier(i / (double) (at.length - 1));
                    Bukkit.getScheduler().runTaskLater(eco, () -> {
                        if (p.isOnline()) omens.call(p, tier);
                    }, at[i]);
                }
            }
            case "아이템이름" -> admin.sendMessage(Text.msg(omens.rename(p) ? "<gray>물건 하나의 이름을 바꿨어요 (진짜로 바뀜)"
                    : "<red>바꿀 만한 물건이 없어요 (도구 · 무기 · 갑옷처럼 한 개짜리, 이름 없는 바닐라 물건)"));
            case "그림자" -> {
                if (!omens.glimpse(p)) admin.sendMessage(Text.msg("<red>옆쪽에 설 땅을 못 찾았어요. 트인 곳에서 다시"));
            }
            case "입장메시지" -> m.haunt().leaveLine(p, p.getName(), true);
            case "노크" -> finale.begin(p, Finale.Mode.KNOCK);
            case "도플갱어" -> finale.begin(p, Finale.Mode.CHASE);
            case "퇴장메시지" -> {
                m.haunt().black(p, 4);
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (p.isOnline()) m.haunt().leaveLine(p, p.getName(), false);
                }, 12L);
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (p.isOnline()) m.haunt().whisper(p, cfg.getString("chase.caught-whisper", "<dark_gray>…"));
                }, 100L);
            }
            case "작별" -> farewell(p);
            case "분신" -> {
                Player viewer = admin instanceof Player a ? a : p;
                shadow.play(p, viewer);
                admin.sendMessage(Text.msg("<gray>" + p.getName() + " 의 그림자 분신을 " + (viewer == p ? "본인" : "나") + "에게 보여 줘요. 움직여야 1초 뒤의 자리가 보여요"));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The end alone: it stands in front of them, says its line, and is gone. */
    private void farewell(Player p) {
        Location eye = p.getLocation();
        Vector f = eye.getDirection().setY(0);
        if (f.lengthSquared() < 1e-4) f = new Vector(0, 0, 1);
        Location at = eye.clone().add(f.normalize().multiply(3));
        Location g = Omens.ground(p.getWorld(), at.getX(), eye.getBlockY(), at.getZ());
        if (g != null) at = g;
        at.setDirection(p.getEyeLocation().toVector().subtract(at.clone().add(0, 1.62, 0).toVector()));
        Mannequin twin = m.haunt().likeness(p, p, at, null);
        twin.setBodyYaw(at.getYaw());
        String words = cfg.getString("chase.last-words", "그럼 네가 계속 해");
        Location top = at.clone().add(0, 2.25, 0);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!p.isOnline()) return;
            TextDisplay t = m.haunt().text(p, top, "<gray>" + words);
            m.haunt().chat(p, p.getName(), words);
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (!p.isOnline()) return;
                p.spawnParticle(Particle.SMOKE, twin.getLocation().add(0, 1, 0), 30, 0.25, 0.6, 0.25, 0.01);
                m.haunt().remove(p, twin);
                m.haunt().remove(p, t);
            }, 60L);
        }, 30L);
    }
}
