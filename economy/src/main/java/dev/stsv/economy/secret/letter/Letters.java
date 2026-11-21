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
 * comes to the mailbox in the player's own name, dated tomorrow, telling them what they did. Five letters, one a day,
 * each asking for one small ritual that walks their past backwards (their first land, the thing they sold most, half an
 * hour of not answering to their name, the spot they first came in, a locked door at night). Do it that day and the next
 * letter comes the next day; miss it and the same letter comes again; miss three days running and the letters stop.
 * With every letter one more thing comes apart around them ({@link Omens}) and the signature is a little less theirs.
 * The last night it knocks ({@link Finale}); outlast it and it says "그럼 네가 계속 해" — solved: the title, the money,
 * and from then on others sometimes see the solver where they stood a second ago ({@link Shadow}).
 * <p>
 * Stage n (1..5) = letter n is theirs. Saved (eco.kv "secret.letter.<uuid>."): {@code day} the day the letter came,
 * {@code at} when (ms), {@code ok} the day its ritual was done, {@code failed} spoke during the silence, {@code read},
 * {@code miss} days missed in a row, {@code target} the ritual's place or item ({@link Target}), {@code quiet} silent
 * ms so far, {@code caught}/{@code tried} the last night it caught them, {@code rolled} the last day the start chance
 * was rolled, {@code seed} for the signature, {@code renamed} letters that already renamed a thing.
 */
public final class Letters extends Secret {

    private static final List<String> KEYS = List.of("day", "at", "ok", "failed", "read", "miss", "target", "quiet", "caught", "tried", "renamed");

    private final LetterBook book;
    private final Omens omens;
    private final Finale finale;
    private final Shadow shadow;
    /** when the waiting letter may come (ms), once its day has begun */
    private final Map<UUID, Long> due = new ConcurrentHashMap<>();
    /** seconds stood on the ritual's spot */
    private final Map<UUID, Integer> stay = new ConcurrentHashMap<>();
    /** silent ms so far (saved every few seconds) */
    private final Map<UUID, Long> quiet = new ConcurrentHashMap<>();
    /** seconds waited at home on the last night */
    private final Map<UUID, Integer> waited = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextPoll = new ConcurrentHashMap<>();

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
        deliver(p, 1, false);
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
        deliver(p, stage, false);
    }

    private void clear(UUID u) {
        for (String k : KEYS) m.remove(this, u, k);
        due.remove(u);
        stay.remove(u);
        quiet.remove(u);
        waited.remove(u);
        nextPoll.remove(u);
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
        StringBuilder b = new StringBuilder("편지 " + n + " (" + m.get(this, u, "day", "?") + ")");
        b.append(" · ").append(m.get(this, u, "read") != null ? "읽음" : "안 읽음");
        String ok = m.get(this, u, "ok");
        b.append(" · 의식 ").append(ok != null ? "완료 (" + ok + ", 다음 날 다음 편지)" : m.get(this, u, "failed") != null ? "실패 (말함, 내일 다시)" : "대기");
        b.append(" · 놓친 날 ").append(m.getLong(this, u, "miss", 0)).append("/").append(misses());
        Target t = Target.decode(m.get(this, u, "target"));
        if (t.kind() != Target.Kind.NONE) b.append(" · 목표 ").append(t.describe());
        if (n == 3) b.append(" · 침묵 ").append(quietMs(u) / 1000).append("/").append(silenceMs(u) / 1000).append("초");
        if (n == 5) {
            b.append(" · 기다림 ").append(waited.getOrDefault(u, 0)).append("/").append(waitSeconds(u)).append("초");
            long c = m.getLong(this, u, "caught", 0);
            if (c > 0) b.append(" · 마지막으로 잡힘 ").append((System.currentTimeMillis() - c) / 60_000).append("분 전");
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

    private int misses() {
        return Math.max(1, cfg.getInt("delivery.misses", 3));
    }

    long silenceMs(UUID u) {
        return m.test(u) ? cfg.getLong("rituals.silence.test-seconds", 40) * 1000L : cfg.getLong("rituals.silence.minutes", 30) * 60_000L;
    }

    private int waitSeconds(UUID u) {
        return (int) secs(u, "rituals.wait.seconds", "rituals.wait.test-seconds", 90);
    }

    private long quietMs(UUID u) {
        return quiet.computeIfAbsent(u, k -> m.getLong(this, u, "quiet", 0));
    }

    /**
     * How many "days" a letter stays good for. A real letter is for its own day; in test mode a day is a minute, which is
     * too short to fetch a letter from the mailbox, read it and do it, so a test letter keeps for a few test days.
     */
    private long deadline(UUID u) {
        return m.test(u) ? Math.max(1, cfg.getLong("delivery.test-days", 3)) : 1;
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

    /** True once the letter's wait (delivery delay) is over; starts the wait on the first ask. */
    private boolean due(UUID u) {
        long now = System.currentTimeMillis();
        Long d = due.get(u);
        if (d == null) {
            due.put(u, now + secs(u, "delivery.delay-seconds", "delivery.test-delay-seconds", 90) * 1000L);
            return false;
        }
        if (now < d) return false;
        due.remove(u);
        return true;
    }

    private void progress(Player p, int n) {
        UUID u = p.getUniqueId();
        String today = m.today(u);
        String day = m.get(this, u, "day");
        if (day == null) {
            if (due(u)) deliver(p, n, false);
            return;
        }
        long td = LetterText.dayNo(today), dd = LetterText.dayNo(day);
        String ok = m.get(this, u, "ok");
        if (ok != null) {
            // done: the next letter comes the next day
            if (n < stages() && td > LetterText.dayNo(ok) && due(u)) {
                m.stage(this, u, n + 1);
                deliver(p, n + 1, false);
            }
            return;
        }
        if (finale.running(u)) return;
        if (td - dd >= deadline(u)) {
            String tried = m.get(this, u, "tried");
            if (n == stages() && tried != null && LetterText.dayNo(tried) >= dd) {
                // it caught them that night: no letter, no miss — the next night, again
                m.set(this, u, "day", today);
                m.remove(this, u, "tried");
                return;
            }
            if (!due(u)) return;
            long miss = m.getLong(this, u, "miss", 0) + 1;
            if (miss >= misses()) {
                broken(p);
                return;
            }
            m.set(this, u, "miss", miss);
            deliver(p, n, true);
            return;
        }
        if (m.get(this, u, "failed") != null) return;
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
                    for (var sale : sales) {
                        if (sale.at() <= since) break;   // newest first
                        if (t.kind() == Target.Kind.ANY_ITEM || Items.normalize(sale.item()).equals(Items.normalize(t.item()))) {
                            if (p.isOnline() && m.stage(this, u) == 2) complete(p, 2);
                            return;
                        }
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

    /** The last letter: night, at home (or anywhere when they have no land), a while — then the knocking. */
    private void waitAtHome(Player p) {
        UUID u = p.getUniqueId();
        long retry = m.test(u) ? m.day(u) / 2 : cfg.getLong("chase.retry-hours", 12) * 3_600_000L;
        if (System.currentTimeMillis() - m.getLong(this, u, "caught", 0) < retry) return;
        List<Integer> h = cfg.getIntegerList("rituals.wait.hours");
        int from = h.isEmpty() ? 21 : h.getFirst(), to = h.size() > 1 ? h.get(1) : 4;
        if (!m.hours(u, from, to) || !atHome(p) || p.isDead()) {
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
        m.set(this, u, "ok", m.today(u));
        m.remove(this, u, "miss");
        m.remove(this, u, "quiet");
        stay.remove(u);
        quiet.remove(u);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!p.isOnline()) return;
            m.haunt().whisper(p, cfg.getString("whispers.done." + n, "<dark_gray>…"));
            m.haunt().sound(p, p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 0.6f);
        }, 30L);
    }

    boolean silenceActive(UUID u) {
        return m.stage(this, u) == 3 && m.get(this, u, "read") != null && m.get(this, u, "ok") == null && m.get(this, u, "failed") == null;
    }

    boolean finaleRunning(UUID u) {
        return finale.running(u);
    }

    // ------------------------------------------------------------------------------------------
    // letters
    // ------------------------------------------------------------------------------------------

    /** Letter n comes (again, when {@code repeat}): today's, with its ritual's target. */
    private void deliver(Player p, int n, boolean repeat) {
        UUID u = p.getUniqueId();
        due.remove(u);
        stay.remove(u);
        quiet.remove(u);
        waited.remove(u);
        nextPoll.remove(u);
        m.set(this, u, "day", m.today(u));
        m.set(this, u, "at", System.currentTimeMillis());
        for (String k : List.of("ok", "failed", "read", "quiet", "tried", "target")) m.remove(this, u, k);
        book.write(p, n, repeat, w -> {
            if (w.item() == null) return;
            m.set(this, u, "target", w.target().encode());
            send(u, w.item());
            Player now = Bukkit.getPlayer(u);
            if (now == null || repeat) return;
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

    /** Three days missed: one last short letter, and the chain is over (it may start again one day, by chance). */
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

    /** It touched them (or they left, or died, while it was out): the next night, again. */
    void caught(Player p) {
        UUID u = p.getUniqueId();
        m.set(this, u, "caught", System.currentTimeMillis());
        m.set(this, u, "tried", m.today(u));
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
            if (p == null || !on() || !silenceActive(u)) return;
            m.set(this, u, "failed", m.today(u));
            m.remove(this, u, "quiet");
            quiet.remove(u);
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
        if (finale.stop(u)) {
            m.set(this, u, "caught", System.currentTimeMillis());
            m.set(this, u, "tried", m.today(u));
        }
        omens.cleanup(u);
        shadow.cleanup(u);
        Long q = quiet.remove(u);
        if (q != null && q > 0 && m.stage(this, u) == 3) m.set(this, u, "quiet", q);
        due.remove(u);
        stay.remove(u);
        waited.remove(u);
        nextPoll.remove(u);
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
                book.write(p, n, false, w -> {
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
                for (int i = 0; i < 3; i++) Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (p.isOnline()) omens.call(p);
                }, 1L + i * 60L);
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
