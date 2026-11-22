package dev.stsv.economy.secret.scarecrow;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.crop.Scarecrows;
import dev.stsv.core.furniture.FurnitureInteractEvent;
import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Haunt;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 움직이는 허수아비 (docs/admin/숨은_비밀/움직이는_허수아비.md, contents/economy/secrets/scarecrow.yml). A player with a
 * scarecrow (stsv:scarecrow) on land they own or live in: some night, very rarely, while they are near it, it turns its
 * head to follow them, and a straw is at their feet: "…배고파." From then on, on any night they come near it, it asks for
 * seven things, one riddle at a time ({@link Night}); they answer by handing it the right crop. Each offering it is found
 * a little nearer their door. After the seventh it stands outside their door and knocks until dawn ({@link Knock}).
 * Don't open it. At dawn: "…고마워. 이제 네 밭이야." — solved: the title, the money, and from then on their scarecrows
 * watch strangers on their land ({@link Companion}). Everything is seen and heard by that player only; the real
 * scarecrow, crops and door are never touched (except the crops that ripen at dawn).
 * <p>
 * Stage: 0 = not started; 1..8 = (stage - 1) offerings given (8 = all seven: the knocking is next); 9 = solved.
 * Saved (eco.kv "secret.scarecrow.&lt;uuid&gt;."): {@code sc} its scarecrow ("world x y z owner"), {@code given} what it
 * has eaten (ids), {@code most} the answer of "what you planted most" (when asked), {@code deadline} when the current
 * riddle runs out (ms), {@code over} the night it last turned away (no more that night), {@code rolled} the last night
 * the start chance was rolled.
 */
public final class Scarecrow extends Secret {

    private static final List<String> KEYS = List.of("sc", "given", "most", "deadline", "over", "rolled");
    private static final int ASKS = 7;

    private final Map<UUID, Night> nights = new ConcurrentHashMap<>();
    private final Map<UUID, Knock> knocks = new ConcurrentHashMap<>();
    /** players an admin started or moved: their story runs whatever the hour (until it turns them away) */
    private final Set<UUID> forced = ConcurrentHashMap.newKeySet();
    /** no new night or knocking before this (ms): the pause between the seventh offering and the knock */
    private final Map<UUID, Long> hold = new ConcurrentHashMap<>();
    /** the real scarecrow kept hidden from them between the seventh offering and the knocking (it is not in the field) */
    private final Map<UUID, ItemDisplay> gone = new ConcurrentHashMap<>();
    private final Props props;
    private final Field field;
    private final Fx fx;
    private final Companion companion;
    private final Marks marks;
    private Riddles riddles = new Riddles(List.of());

    public Scarecrow(SecretModule m) {
        super(m);
        props = new Props(eco);
        field = new Field(m.core(), eco);
        fx = new Fx(() -> cfg, props);
        companion = new Companion(this);
        marks = new Marks(this);
    }

    @Override
    public String id() {
        return "scarecrow";
    }

    @Override
    public String name() {
        return "허수아비";
    }

    @Override
    public int stages() {
        return ASKS + 1;
    }

    @Override
    protected void loaded() {
        riddles = Riddles.parse(cfg.getMapList("asks"));
        if (riddles.size() != ASKS) eco.getLogger().warning("[비밀] scarecrow.yml asks 는 " + ASKS + "개여야 함 (지금 " + riddles.size() + ")");
    }

    // ------------------------------------------------------------------------------------------
    // for the parts
    // ------------------------------------------------------------------------------------------

    YamlConfiguration cfg() {
        return cfg;
    }

    Props props() {
        return props;
    }

    Field field() {
        return field;
    }

    Fx fx() {
        return fx;
    }

    Haunt haunt() {
        return m.haunt();
    }

    Marks marks() {
        return marks;
    }

    Riddles riddles() {
        return riddles;
    }

    boolean testing(UUID u) {
        return m.test(u);
    }

    /** Seconds from the file: "a.b-seconds", or "a.test-b-seconds" in test mode. */
    int secs(UUID u, String key, int def) {
        int real = cfg.getInt(key, def);
        if (!testing(u)) return Math.max(1, real);
        int dot = key.lastIndexOf('.');
        String test = key.substring(0, dot + 1) + "test-" + key.substring(dot + 1);
        return Math.max(1, cfg.getInt(test, real));
    }

    /** Whether it is night in that world (always, for a tester). */
    boolean night(Player p, World w) {
        return testing(p.getUniqueId()) || Geometry.night(w.getTime(), cfg.getLong("night.from", 13000), cfg.getLong("night.to", 23000));
    }

    /** Which night it is (a tester's test "day"). */
    String nightKey(UUID u, World w) {
        if (testing(u)) return m.today(u);
        return w.getName() + ":" + Geometry.nightNo(w.getFullTime(), cfg.getLong("night.from", 13000));
    }

    /** Offerings given. */
    int done(UUID u) {
        return Math.max(0, Math.min(ASKS, m.stage(this, u) - 1));
    }

    Set<String> given(UUID u) {
        return Riddles.split(m.get(this, u, "given"));
    }

    void gave(UUID u, String item, int done) {
        Set<String> g = new LinkedHashSet<>(given(u));
        g.add(Riddles.norm(item));
        m.set(this, u, "given", Riddles.join(g));
        m.remove(this, u, "deadline");
        m.stage(this, u, done + 1);
    }

    Set<String> most(UUID u) {
        return Riddles.split(m.get(this, u, "most"));
    }

    void setMost(UUID u, Set<String> ids) {
        if (ids.isEmpty()) m.remove(this, u, "most");
        else m.set(this, u, "most", Riddles.join(ids));
    }

    void asked(UUID u, int ticksLeft) {
        m.set(this, u, "deadline", System.currentTimeMillis() + ticksLeft * 50L);
    }

    List<String> memoryFriends(UUID u) {
        return m.memory() == null ? List.of() : m.memory().friends(u);
    }

    /** Whether this player solved it (their scarecrows keep watch). */
    boolean solvedBy(UUID u) {
        return m.stage(this, u) > stages();
    }

    /** Whether something of this secret is playing for them now. */
    boolean busy(UUID u) {
        return nights.containsKey(u) || knocks.containsKey(u);
    }

    private Scarecrows.Scarecrow bound(UUID u) {
        return Field.decode(m.get(this, u, "sc"));
    }

    private void bind(UUID u, Scarecrows.Scarecrow sc) {
        m.set(this, u, "sc", Field.encode(sc));
    }

    // ------------------------------------------------------------------------------------------
    // start, jump, the clock
    // ------------------------------------------------------------------------------------------

    private void clear(UUID u) {
        for (String k : KEYS) m.remove(this, u, k);
    }

    @Override
    public void start(Player p) {
        UUID u = p.getUniqueId();
        cleanup(u);
        clear(u);
        m.stage(this, u, 1);
        forced.add(u);
        Scarecrows.Scarecrow sc = field.near(p, Math.max(cfg.getDouble("start.radius", 32), 64));
        if (sc == null) {
            if (testing(u)) p.sendMessage(Text.msg("<yellow>[테스트] 64칸 안에 내 땅(소유 · 거주)의 허수아비가 없음 — 가까이 가면 시작"));
            return;
        }
        bind(u, sc);
        begin(p, sc, true);
    }

    /** The first night: it turns, the straw, then the asking. */
    private void begin(Player p, Scarecrows.Scarecrow sc, boolean intro) {
        Block home = Field.block(sc).orElse(null);
        if (home == null) return;
        Night n = new Night(this, p, sc, home, forced.contains(p.getUniqueId()), intro);
        if (n.begin()) nights.put(p.getUniqueId(), n);
    }

    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        cleanup(u);
        clear(u);
        if (stage <= 0) {
            m.stage(this, u, 0);
            forced.remove(u);
            return;
        }
        m.stage(this, u, Math.min(stages(), stage));
        forced.add(u);
        resume(p, Math.min(stages(), stage));
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (busy(u) || hold.getOrDefault(u, 0L) > now) continue;
            int st = m.stage(this, u);
            if (st > stages() || !open(u)) continue;
            if (st == 0) maybeStart(p);
            else resume(p, st);
        }
        companion.tick();
        marks.tick();
    }

    /** Once a night, the first time they are near a scarecrow of theirs: the (very small) chance. */
    private void maybeStart(Player p) {
        UUID u = p.getUniqueId();
        World w = p.getWorld();
        if (!night(p, w)) return;
        String key = nightKey(u, w);
        if (key.equals(m.get(this, u, "rolled"))) return;
        Scarecrows.Scarecrow sc = field.near(p, cfg.getDouble("start.radius", 32));
        if (sc == null) return;
        m.set(this, u, "rolled", key);
        if (!m.roll(u, cfg.getDouble("start.chance", 0.003))) return;
        m.stage(this, u, 1);
        bind(u, sc);
        begin(p, sc, true);
        eco.getLogger().info("[비밀] 허수아비가 " + p.getName() + " 쪽으로 고개를 돌렸습니다");
    }

    /** A started story goes on whenever it is night and they are near (any night but one it turned them away). */
    private void resume(Player p, int st) {
        UUID u = p.getUniqueId();
        Scarecrows.Scarecrow sc = bound(u);
        if (sc == null || !field.theirs(sc, u)) {
            sc = field.near(p, cfg.getDouble("night.near", 32));
            if (sc == null) return;
            bind(u, sc);
        }
        Block home = Field.block(sc).orElse(null);
        if (home == null || home.getWorld() != p.getWorld()) return;
        boolean f = forced.contains(u);
        if (!f && !night(p, home.getWorld())) return;
        if (nightKey(u, home.getWorld()).equals(m.get(this, u, "over"))) return;
        double d = p.getLocation().distance(home.getLocation());
        if (st <= ASKS) {
            if (d <= cfg.getDouble("night.near", 32)) begin(p, sc, false);
            return;
        }
        if (d > cfg.getDouble("night.leave", 64) || !field.allows(p.getLocation(), u)) return;
        Knock k = new Knock(this, p, sc, home, f);
        if (k.begin()) knocks.put(u, k);
    }

    void endNight(Night n, Night.End end) {
        nights.remove(n.u, n);
        UUID u = n.u;
        World w = Field.block(n.sc).map(Block::getWorld).orElse(n.p.getWorld());
        switch (end) {
            case FAIL -> {
                m.set(this, u, "over", nightKey(u, w));
                m.remove(this, u, "deadline");
                forced.remove(u);
            }
            case DONE -> {
                hold.put(u, System.currentTimeMillis() + secs(u, "night.gap-seconds", 60) * 1000L);
                field.real(n.sc).ifPresent(real -> {
                    if (!n.p.isOnline()) return;
                    props.hide(n.p, real);
                    ItemDisplay was = gone.put(u, real);
                    if (was != null) props.unhide(n.p, was);
                });
            }
            default -> m.remove(this, u, "deadline");
        }
        // after the hold on the real one (DONE), so it does not show for a tick in between
        n.stop();
    }

    /** The knocking has its own copy now: the hold on the real scarecrow from the seventh offering lets go. */
    void returned(Player p) {
        ItemDisplay was = gone.remove(p.getUniqueId());
        if (was != null) props.unhide(p, was);
    }

    void endKnock(Knock k, boolean solved) {
        knocks.remove(k.u, k);
        k.stop();
        UUID u = k.u;
        if (solved) {
            forced.remove(u);
            if (k.p.isOnline()) m.solve(this, k.p);
            return;
        }
        m.set(this, u, "over", nightKey(u, k.p.getWorld()));
        forced.remove(u);
    }

    @Override
    public void cleanup(UUID player) {
        Night n = nights.remove(player);
        if (n != null) n.stop();
        Knock k = knocks.remove(player);
        if (k != null) {
            // leaving in the middle of the knocking (quitting) does not escape it: that night is over
            k.stop();
            m.set(this, player, "over", nightKey(player, k.p.getWorld()));
            forced.remove(player);
        }
        companion.forget(player);
        hold.remove(player);
        gone.remove(player);
        props.clear(player);
    }

    // ------------------------------------------------------------------------------------------
    // listeners: offerings and the door
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onClickCopy(PlayerInteractEntityEvent e) {
        Night n = nights.get(e.getPlayer().getUniqueId());
        if (n == null || n.effigy() == null || !n.effigy().owns(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        n.offer(e.getPlayer().getInventory().getItemInMainHand());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClickReal(FurnitureInteractEvent e) {
        Night n = nights.get(e.getPlayer().getUniqueId());
        if (n == null || !n.atHome() || !e.getFurniture().item().key().asString().equals(Scarecrows.ITEM)) return;
        Block b = e.getFurniture().location().getBlock();
        if (b.getX() != n.sc.x() || b.getY() != n.sc.y() || b.getZ() != n.sc.z()) return;
        e.setCancelled(true);
        n.offer(e.getPlayer().getInventory().getItemInMainHand());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDoor(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !Tag.DOORS.isTagged(e.getClickedBlock().getType())) return;
        Knock k = knocks.get(e.getPlayer().getUniqueId());
        if (k == null || !Field.sameDoor(k.door(), e.getClickedBlock())) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) k.opened(e.getClickedBlock());
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    @Override
    public String status(UUID u) {
        int st = m.stage(this, u);
        if (st > stages()) return "풂 · 허수아비 지킴이 " + (cfg.getBoolean("companion.enabled", true) ? "켜짐" : "꺼짐");
        Scarecrows.Scarecrow sc = bound(u);
        StringBuilder b = new StringBuilder();
        if (st <= 0) {
            String r = m.get(this, u, "rolled");
            b.append(r == null ? "아직 굴린 적 없음" : "마지막 확률 굴림 " + r);
        } else {
            b.append("바친 것 ").append(done(u)).append("/").append(ASKS);
            Set<String> g = given(u);
            if (!g.isEmpty()) b.append(" (").append(String.join(", ", g)).append(")");
        }
        if (sc != null) {
            b.append(" · 허수아비 ").append(sc.world()).append(" ").append(sc.x()).append(" ").append(sc.y()).append(" ").append(sc.z());
            if (!field.theirs(sc, u)) b.append(" <red>(없음 · 일하지 않음 · 내 땅 아님)</red>");
            World w = Bukkit.getWorld(sc.world());
            if (w != null) {
                b.append(" · 월드 시간 ").append(w.getTime()).append(night(Bukkit.getPlayer(u) == null ? null : Bukkit.getPlayer(u), w, u) ? " (밤)" : " (낮)");
                if (nightKey(u, w).equals(m.get(this, u, "over"))) b.append(" · <red>오늘 밤은 끝 (다음 밤에)</red>");
            }
        } else if (st > 0) b.append(" · 허수아비 아직 안 정해짐 (내 땅 허수아비 32칸 안으로)");
        if (st >= 1 && st <= ASKS) {
            Riddles.Ask a = riddles.ask(st);
            b.append(" · 지금 부탁 ").append(st).append(" '").append(Text.plain(a.say().getFirst())).append("' = ")
                    .append(String.join(", ", Riddles.answers(a, most(u))));
            long dl = m.getLong(this, u, "deadline", 0);
            if (dl > 0) b.append(" · 기한 ").append(Math.max(0, (dl - System.currentTimeMillis()) / 1000)).append("초");
        }
        Night n = nights.get(u);
        if (n != null) b.append(" · <yellow>밤 진행 중: ").append(n.describe());
        Knock k = knocks.get(u);
        if (k != null) b.append(" · <red>").append(k.describe());
        if (forced.contains(u)) b.append(" · (관리자 시작: 시간 무관)");
        return b.toString();
    }

    private boolean night(Player p, World w, UUID u) {
        return testing(u) || (p != null ? night(p, w) : Geometry.night(w.getTime(), cfg.getLong("night.from", 13000), cfg.getLong("night.to", 23000)));
    }

    private static final List<String> SCENES = List.of("고개", "지푸라기", "부탁", "오답", "다가옴", "까마귀", "시든밭", "노크", "손잡이", "창문손", "문열림", "새벽", "지킴이");

    @Override
    public List<String> scenes() {
        return SCENES;
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        if (!SCENES.contains(word)) return false;
        Haunt h = haunt();
        switch (word) {
            case "고개" -> {
                Effigy e = sceneEffigy(p, 0);
                if (e == null) break;
                int[] t = {0};
                var task = props.every(p, 2, () -> {
                    t[0] += 2;
                    e.turn(e.yawTo(p.getLocation()), 0.9f);
                });
                props.later(p, 300, () -> {
                    task.cancel();
                    e.remove();
                });
            }
            case "지푸라기" -> fx.straw(p);
            case "부탁" -> {
                Effigy e = sceneEffigy(p, 0);
                if (e == null) break;
                e.face(e.yawTo(p.getLocation()));
                Riddles.Ask a = riddles.ask(1 + ThreadLocalRandom.current().nextInt(Math.max(1, riddles.size())));
                TextDisplay w = h.text(p, e.head().add(0, 0.75, 0), String.join("\n", a.say().stream().map(l -> "<gray><i>" + l).toList()));
                for (String l : a.say()) p.sendMessage(Text.mm("<dark_gray><i>" + l));
                fx.sound(p, e.head(), "rustle", "block.azalea_leaves.step", 0.7f, 0.5f);
                admin.sendMessage(Text.msg("<gray>부탁 '" + a.id() + "' 정답: <white>" + String.join(", ", Riddles.answers(a, Set.of()))));
                props.later(p, 240, () -> {
                    h.remove(p, w);
                    e.remove();
                });
            }
            case "오답" -> {
                Effigy e = sceneEffigy(p, 0);
                if (e == null) break;
                e.face(e.yawTo(p.getLocation()));
                props.later(p, 30, () -> {
                    e.face(e.yawTo(p.getLocation()) + 180);
                    List<String> l = cfg.getStringList("lines.wrong");
                    h.whisper(p, l.isEmpty() ? "<dark_gray>…" : l.getFirst());
                    fx.sound(p, e.head(), "straw", "block.grass.break", 0.5f, 0.6f);
                });
                props.later(p, 140, e::remove);
            }
            case "다가옴" -> {
                Effigy e = sceneEffigy(p, 9);
                if (e == null) break;
                e.face(e.yawTo(p.getLocation()));
                int[] moved = {0};
                var task = props.every(p, 10, () -> {
                    if (moved[0] >= 6 || e.seen()) return;
                    Location f = e.base();
                    Vector to = p.getLocation().toVector().subtract(f.toVector()).setY(0);
                    if (to.length() < 2.5) return;
                    to.normalize();
                    Block g = Field.ground(f.getWorld(), f.getX() + to.getX(), e.feet().getY(), f.getZ() + to.getZ());
                    if (g == null) return;
                    e.moveTo(g, Geometry.yawTo(p.getLocation().getX() - g.getX() - 0.5, p.getLocation().getZ() - g.getZ() - 0.5));
                    fx.sound(p, e.head(), "rustle", "block.azalea_leaves.step", 0.3f, 0.5f);
                    moved[0]++;
                });
                props.later(p, 600, () -> {
                    task.cancel();
                    e.remove();
                });
            }
            case "까마귀" -> fx.crows(p, p.getLocation(), 6);
            case "시든밭" -> {
                List<Block> w = fx.wither(p, field.crops(p.getLocation().getBlock(), cfg.getInt("night.wither-radius", 5)));
                admin.sendMessage(Text.msg("<gray>시든 작물 블록 " + w.size() + "개 (10초)"));
                props.later(p, 200, () -> props.restoreAll(p, w));
            }
            case "노크" -> {
                Block door = field.door(p.getLocation(), null, 10);
                Location at = door != null ? door.getLocation().add(0.5, 1, 0.5) : p.getLocation().add(p.getLocation().getDirection().multiply(-4));
                for (int i = 0; i < 4; i++) {
                    float vol = 0.4f + i * 0.2f;
                    boolean heavy = i == 3;
                    props.later(p, 1 + i * 60L, () -> fx.knocks(p, at, vol, heavy ? 5 : 3, heavy));
                }
                if (door == null) admin.sendMessage(Text.msg("<gray>10칸 안에 문이 없어 뒤쪽 허공에서"));
            }
            case "손잡이" -> {
                Block door = field.door(p.getLocation(), null, 10);
                if (door == null) admin.sendMessage(Text.msg("<red>10칸 안에 문이 없음"));
                else fx.rattle(p, door);
            }
            case "창문손" -> {
                Block win = field.window(p.getLocation().getBlock());
                if (win == null) admin.sendMessage(Text.msg("<red>6칸 안에 유리가 없음"));
                else fx.hand(p, win, 120);
            }
            case "문열림" -> {
                Effigy e = sceneEffigy(p, 3);
                if (e == null) break;
                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection().setY(0);
                if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
                dir.normalize();
                e.placeAt(eye.clone().add(dir.clone().multiply(0.55)).subtract(0, Effigy.HEAD, 0), Geometry.yawTo(-dir.getX(), -dir.getZ()));
                fx.scare(p, eye);
                props.later(p, 14, () -> h.black(p, 4));
                props.later(p, 30, e::remove);
                props.later(p, 85, () -> h.whisper(p, cfg.getString("lines.locked", "<dark_gray>…")));
            }
            case "새벽" -> {
                p.setPlayerTime(23400, false);
                Effigy e = sceneEffigy(p, 0);
                fx.crows(p, p.getLocation(), 5);
                TextDisplay[] w = {null};
                props.later(p, 40, () -> {
                    Location at = e != null ? e.head().add(0, 0.75, 0) : p.getEyeLocation().add(p.getLocation().getDirection().multiply(3));
                    w[0] = h.text(p, at, "<gray><i>" + cfg.getString("lines.thanks", "…"));
                    h.whisper(p, cfg.getString("lines.thanks-whisper", "<gray>…"));
                });
                props.later(p, 200, () -> {
                    if (w[0] != null) h.remove(p, w[0]);
                    if (e != null) e.remove();
                    p.resetPlayerTime();
                });
            }
            case "지킴이" -> {
                if (!companion.show(p)) admin.sendMessage(Text.msg("<red>48칸 안에 허수아비가 없음"));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * A scarecrow for a scene: the nearest real one within 32 blocks (a copy of it), or one made from the item a few
     * blocks in front of them (ahead blocks; 0 = 5).
     */
    private Effigy sceneEffigy(Player p, int ahead) {
        Scarecrows.Scarecrow best = null;
        double bd = 32 * 32;
        for (Scarecrows.Scarecrow sc : field.all()) {
            if (!sc.world().equals(p.getWorld().getName())) continue;
            double d = p.getLocation().distanceSquared(new Location(p.getWorld(), sc.x() + 0.5, sc.y(), sc.z() + 0.5));
            if (d < bd) {
                bd = d;
                best = sc;
            }
        }
        if (best != null && ahead == 0) {
            ItemDisplay real = field.real(best).orElse(null);
            if (real != null) return Effigy.of(props, p, real, field.yawOffset());
        }
        CustomItem item = m.core().content().item(Scarecrows.ITEM).orElse(null);
        if (item == null || !item.isFurniture()) {
            p.sendMessage(Text.msg("<red>허수아비 아이템(" + Scarecrows.ITEM + ")이 없음"));
            return null;
        }
        Location l = p.getLocation();
        Vector dir = l.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(ahead == 0 ? 5 : ahead);
        Block g = Field.ground(l.getWorld(), l.getX() + dir.getX(), l.getBlockY(), l.getZ() + dir.getZ());
        if (g == null) g = l.clone().add(dir).getBlock();
        float yaw = Geometry.yawTo(l.getX() - g.getX() - 0.5, l.getZ() - g.getZ() - 0.5);
        return Effigy.made(props, p, g, yaw, item.create(1), item.furniture().translation(), item.furniture().scale(), field.yawOffset());
    }
}
