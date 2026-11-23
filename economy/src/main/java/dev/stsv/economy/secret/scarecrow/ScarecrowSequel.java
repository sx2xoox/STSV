package dev.stsv.economy.secret.scarecrow;

import dev.stsv.core.crop.Scarecrows;
import dev.stsv.core.furniture.FurnitureInteractEvent;
import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SequelState;
import dev.stsv.economy.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 후속 임무 「밭의 부탁」 (docs/admin/숨은_비밀/움직이는_허수아비.md "후속 임무"; scarecrow.yml {@code sequel}). About once a
 * week, at night, when the solver is near one of their own scarecrows ({@link Field#near}), it turns to them and asks for
 * ONE crop, by a riddle ({@link Riddles}, its own pool {@code sequel.asks}). They hand it over (right-click holding it:
 * exactly one is taken, the moment it is accepted); it eats, thanks them with a line about what it keeps from the door,
 * the reward is paid, and for the rest of that week the crops in that scarecrow's chunk grow a little faster
 * ({@link #grow}: the crop engine's own public growth pulse, the one the farmer's job uses — no other module is touched).
 * A wrong thing is simply not taken. Everything is seen and heard by the solver only (the copy of the scarecrow, as in
 * the secret); the real scarecrow never moves.
 * <p>
 * Notes of the going mission ({@code sequel.x.}): ask (the ask's id), sc (the scarecrow it speaks from), perk (the
 * scarecrow whose chunk grows faster) and perk-week (the week it was earned in). Kept ({@code sequel.keep.}): recent (the
 * last asks), recent-thanks.
 */
public final class ScarecrowSequel extends Sequel {

    private static final List<String> THANKS = List.of("…잘 먹었어. 이걸로 이레는 서 있을 수 있어. 밤은 내가 볼게.");

    /** What one solver is shown while it asks: the copy of their scarecrow, its words, its slow turning. */
    private final class Scene {
        final Player p;
        final Scarecrows.Scarecrow sc;
        final Effigy e;
        final Riddles.Ask ask;
        /** false: an admin's look (it takes nothing and nothing counts) */
        final boolean mission;
        TextDisplay words;
        BukkitTask task;
        int t, awayUntil;
        boolean asked, closing;

        Scene(Player p, Scarecrows.Scarecrow sc, Effigy e, Riddles.Ask ask, boolean mission) {
            this.mission = mission;
            this.p = p;
            this.sc = sc;
            this.e = e;
            this.ask = ask;
        }

        void words(String mm) {
            if (words != null) s.haunt().remove(p, words);
            words = mm == null ? null : s.haunt().text(p, e.head().add(0, 0.75, 0), mm);
        }

        /** Every two ticks: it turns toward them, slowly (away for a moment after a wrong thing). */
        void step() {
            t += 2;
            if (!p.isOnline() || !e.valid() || closing) return;
            float to = e.yawTo(p.getLocation());
            e.turn(t < awayUntil ? to + 180 : to, 4f);
        }

        void stop() {
            if (task != null) task.cancel();
            if (words != null && p.isOnline()) s.haunt().remove(p, words);
            words = null;
            e.remove();
            if (p.isOnline()) s.marks().hideFor(p, sc, false);
        }
    }

    private final Scarecrow s;
    private final Map<UUID, Scene> scenes = new HashMap<>();
    /** solvers told once this session that it waits for the night */
    private final Map<UUID, Boolean> toldDay = new HashMap<>();
    private List<Riddles.Ask> asks = List.of();
    private long clock;

    ScarecrowSequel(Scarecrow s) {
        super(s);
        this.s = s;
    }

    @Override
    public String id() {
        return "밭의 부탁";
    }

    @Override
    protected void loaded() {
        asks = Favor.usable(Riddles.parse(cfg().getMapList("asks")).all());
        // no pool of its own: the secret's own riddles (but for "what you planted most")
        if (asks.isEmpty()) asks = Favor.usable(Riddles.parse(s.cfg().getMapList("asks")).all());
    }

    // ------------------------------------------------------------------------------------------
    // words
    // ------------------------------------------------------------------------------------------

    private String text(String key, String def) {
        return line("lines." + key, def);
    }

    private void whisper(Player p, String mm) {
        if (!mm.isEmpty()) s.haunt().whisper(p, mm);
    }

    private double near() {
        return cfg().getDouble("near", 24);
    }

    private Scarecrows.Scarecrow bound(UUID u) {
        return Field.decode(get(u, "sc"));
    }

    private Riddles.Ask ask(UUID u) {
        int at = Favor.indexOf(asks, get(u, "ask", ""));
        return at < 0 ? null : asks.get(at);
    }

    // ------------------------------------------------------------------------------------------
    // beginning
    // ------------------------------------------------------------------------------------------

    @Override
    protected boolean offer(Player p) {
        UUID u = p.getUniqueId();
        if (asks.isEmpty() || s.busy(u) || !s.night(p, p.getWorld())) return false;
        if (s.field().near(p, near()) == null) return false;
        if (!chance(p, "chance", 0.02)) return false;
        start(p);
        return true;
    }

    /** Fixes this week's ask and the scarecrow it speaks from (the nearest of theirs; none near: the first they come to). */
    @Override
    protected void start(Player p) {
        UUID u = p.getUniqueId();
        if (asks.isEmpty()) return;
        List<Integer> recent = Favor.numbers(kept(u, "recent", ""));
        int at = Favor.pick(Favor.seed(week(u), u), asks.size(), recent);
        set(u, "ask", asks.get(at).id());
        keep(u, "recent", Favor.join(Favor.remember(recent, at, Math.min(asks.size() - 1, Math.max(0, cfg().getInt("recent", 5))))));
        Scarecrows.Scarecrow sc = s.field().near(p, Math.max(near(), 64));
        if (sc != null) set(u, "sc", Field.encode(sc));
        toldDay.remove(u);
    }

    // ------------------------------------------------------------------------------------------
    // each second
    // ------------------------------------------------------------------------------------------

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        Scene sn = scenes.get(u);
        World w = p.getWorld();
        boolean night = s.night(p, w);
        if (sn != null) {
            if (sn.closing) return;
            Location at = sn.e.base();
            boolean far = at.getWorld() != w || at.distanceSquared(p.getLocation()) > sq(cfg().getDouble("leave", 48));
            if (!sn.e.valid() || far || !night || !s.field().theirs(sn.sc, u)) {
                if (!night && !far) whisper(p, text("dawn", "<dark_gray>…해가 뜨네. 밤에 다시 와."));
                end(u);
            }
            return;
        }
        if (s.busy(u)) return;
        Riddles.Ask a = ask(u);
        if (a == null) {   // the file changed under it: this week's ask again from what is there now
            if (asks.isEmpty()) return;
            start(p);
            a = ask(u);
            if (a == null) return;
        }
        Scarecrows.Scarecrow sc = bound(u);
        if (sc == null || !s.field().theirs(sc, u)) {   // broken or moved since: any of theirs they come near
            sc = s.field().near(p, near());
            if (sc == null) return;
            set(u, "sc", Field.encode(sc));
        }
        if (!sc.world().equals(w.getName())) return;
        Location base = new Location(w, sc.x() + 0.5, sc.y(), sc.z() + 0.5);
        if (base.distanceSquared(p.getLocation()) > sq(near())) return;
        if (!night) {
            if (toldDay.put(u, true) == null && state(u) == SequelState.State.ACTIVE) whisper(p, text("day", "<dark_gray>…밤에 와."));
            return;
        }
        ItemDisplay real = s.field().real(sc).orElse(null);
        if (real == null) return;
        show(p, sc, real, a, true);
    }

    private static double sq(double v) {
        return v * v;
    }

    /** The copy stands where the real one does, turns to them and says its riddle. mission: it counts (else an admin's look). */
    private Scene show(Player p, Scarecrows.Scarecrow sc, ItemDisplay real, Riddles.Ask a, boolean mission) {
        UUID u = p.getUniqueId();
        end(u);
        Effigy e = Effigy.of(s.props(), p, real, s.field().yawOffset());
        e.clickable();
        s.marks().hideFor(p, sc, true);   // the marks would not turn with the copy
        Scene sn = new Scene(p, sc, e, a, mission);
        scenes.put(u, sn);
        sn.task = s.props().every(p, 2, sn::step);
        long wait = mission && state(u) == SequelState.State.OFFERED ? Math.max(1, cfg().getLong("turn-seconds", 4)) * 20L : 30L;
        s.fx().sound(p, e.head(), "rustle", "block.azalea_leaves.step", 0.7f, 0.5f);
        s.props().later(p, wait, () -> {
            if (scenes.get(u) != sn || sn.closing) return;
            if (mission) accept(p);
            speak(sn);
        });
        return sn;
    }

    private void speak(Scene sn) {
        Player p = sn.p;
        sn.asked = true;
        sn.words(String.join("\n", sn.ask.say().stream().map(l -> "<gray><i>" + l).toList()));
        for (String l : sn.ask.say()) p.sendMessage(Text.mm("<dark_gray><i>" + l));
        String how = text("how", "<dark_gray>(그것을 손에 들고 허수아비를 우클릭 · 하나만 가져간다)");
        if (!how.isEmpty()) p.sendMessage(Text.mm(how));
        s.fx().sound(p, sn.e.head(), "rustle", "block.azalea_leaves.step", 0.7f, 0.5f);
        s.fx().crows(p, sn.e.base(), 2);
    }

    /** The copy goes and the real scarecrow is theirs to see again; nothing saved changes. */
    private void end(UUID u) {
        Scene sn = scenes.remove(u);
        if (sn != null) sn.stop();
    }

    // ------------------------------------------------------------------------------------------
    // handing it over
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onClickCopy(PlayerInteractEntityEvent e) {
        Scene sn = scenes.get(e.getPlayer().getUniqueId());
        if (sn == null || !sn.e.owns(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        handed(sn, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClickReal(FurnitureInteractEvent e) {
        Scene sn = scenes.get(e.getPlayer().getUniqueId());
        if (sn == null || !e.getFurniture().item().key().asString().equals(Scarecrows.ITEM)) return;
        Block b = e.getFurniture().location().getBlock();
        if (b.getX() != sn.sc.x() || b.getY() != sn.sc.y() || b.getZ() != sn.sc.z()) return;
        e.setCancelled(true);
        handed(sn, e.getPlayer());
    }

    /** They held something out to it. */
    private void handed(Scene sn, Player p) {
        UUID u = p.getUniqueId();
        if (sn.closing || !sn.asked) return;   // not asked yet, or already eating
        boolean mission = sn.mission && may(p) && state(u) == SequelState.State.ACTIVE && sn.ask == ask(u);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            whisper(p, "<gray><i>" + sn.ask.say().getFirst());
            return;
        }
        String key = Items.key(hand);
        if (Items.trial(hand) || Riddles.judge(sn.ask, key, Set.of(), Set.of()) != Riddles.Verdict.RIGHT) {
            List<String> wrong = cfg().getStringList("lines.wrong");
            whisper(p, wrong.isEmpty() ? "<dark_gray>…그건 아니야." : wrong.get(ThreadLocalRandom.current().nextInt(wrong.size())));
            sn.awayUntil = sn.t + 50;
            s.fx().sound(p, sn.e.head(), "straw", "block.grass.break", 0.5f, 0.6f);
            return;
        }
        if (!mission) {   // an admin's look (or no longer theirs to do): it only pretends, and takes nothing
            eat(sn, hand.asOne(), false);
            return;
        }
        // exactly one leaves the hand, and in this same tick the mission is done: nothing is taken for an end that does not come
        ItemStack one = hand.asOne();
        hand.subtract(1);
        set(u, "perk", Field.encode(sn.sc));
        set(u, "perk-week", week(u));
        eat(sn, one, true);
        complete(p);
    }

    /** It eats, thanks them, and a few seconds later is only a scarecrow again. */
    private void eat(Scene sn, ItemStack one, boolean mission) {
        Player p = sn.p;
        UUID u = p.getUniqueId();
        sn.closing = true;
        s.fx().eat(p, sn.e.head(), one);
        sn.words(text("eat", "<dark_gray>…"));
        List<String> thanks = cfg().getStringList("thanks");
        if (thanks.isEmpty()) thanks = THANKS;
        List<Integer> recent = Favor.numbers(kept(u, "recent-thanks", ""));
        int at = mission ? Favor.pick(Favor.seed(week(u), u) >>> 7, thanks.size(), recent) : ThreadLocalRandom.current().nextInt(thanks.size());
        String line = thanks.get(at);
        if (mission) keep(u, "recent-thanks", Favor.join(Favor.remember(recent, at, Math.min(thanks.size() - 1, Math.max(0, cfg().getInt("recent-thanks", 5))))));
        s.props().later(p, 36, () -> {
            if (scenes.get(u) != sn) return;
            sn.words("<gray><i>" + line);
            p.sendMessage(Text.mm("<dark_gray><i>" + line));
            String perk = mission && perkOn() ? text("perk", "<dark_gray>(이번 주 동안 그 허수아비가 선 청크의 작물이 조금 더 빨리 자란다)") : "";
            if (!perk.isEmpty()) p.sendMessage(Text.mm(perk));
        });
        s.props().later(p, 150, () -> {
            if (scenes.get(u) == sn) end(u);
        });
    }

    // ------------------------------------------------------------------------------------------
    // the week's gift: the crops of that chunk grow a little faster
    // ------------------------------------------------------------------------------------------

    private boolean perkOn() {
        return cfg().getBoolean("perk.enabled", true) && s.field() != null;
    }

    /**
     * Called by the secret each second. Every {@code perk.every-seconds}: for whoever earned the gift this week (the
     * solver, online or not; a tester while online), each unripe crop in that scarecrow's chunk grows one age with
     * {@code perk.chance} — custom crops through the crop engine's own pulse, vanilla ones by their block. Only while
     * that chunk is loaded (nothing is loaded for it), only while the scarecrow still stands on their land.
     */
    void grow() {
        clock++;
        if (!on() || !perkOn() || !Favor.beat(clock, Math.max(5, cfg().getLong("perk.every-seconds", 60)))) return;
        List<UUID> who = new ArrayList<>();
        m.solver(secret).ifPresent(who::add);
        for (Player p : Bukkit.getOnlinePlayers()) if (m.test(p.getUniqueId()) && !who.contains(p.getUniqueId())) who.add(p.getUniqueId());
        double chance = Math.max(0, Math.min(1, cfg().getDouble("perk.chance", 0.04)));
        for (UUID u : who) {
            if (!member(u) || state(u) != SequelState.State.DONE || !Favor.holds(get(u, "perk-week"), week(u))) continue;
            Scarecrows.Scarecrow sc = Field.decode(get(u, "perk"));
            if (sc == null || !s.field().theirs(sc, u)) continue;
            World w = Bukkit.getWorld(sc.world());
            if (w == null || !w.isChunkLoaded(sc.cx(), sc.cz())) continue;
            pulse(w, sc, chance);
        }
    }

    private void pulse(World w, Scarecrows.Scarecrow sc, double chance) {
        int reach = Math.max(1, cfg().getInt("perk.reach-y", 6));
        var crops = m.core().crops();
        if (crops != null) crops.growAround(w, Favor.middle(sc.cx()), sc.y(), Favor.middle(sc.cz()), 8, reach, reach, chance);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int x0 = sc.cx() << 4, z0 = sc.cz() << 4;
        for (int x = x0; x < x0 + 16; x++) for (int z = z0; z < z0 + 16; z++) for (int y = sc.y() - reach; y <= sc.y() + reach; y++) {
            if (y < w.getMinHeight() || y >= w.getMaxHeight()) continue;
            Block b = w.getBlockAt(x, y, z);
            Material t = b.getType();
            if (!(Tag.CROPS.isTagged(t) || t == Material.SWEET_BERRY_BUSH || t == Material.COCOA || t == Material.NETHER_WART)) continue;
            if (!(b.getBlockData() instanceof Ageable a) || a.getAge() >= a.getMaximumAge() || r.nextDouble() >= chance) continue;
            a.setAge(a.getAge() + 1);
            b.setBlockData(a, false);
        }
    }

    // ------------------------------------------------------------------------------------------
    // join, quit, the week's end
    // ------------------------------------------------------------------------------------------

    @Override
    protected void resume(Player p) {
        toldDay.remove(p.getUniqueId());   // the copy is shown again by the next tick near it, at night
    }

    @Override
    protected void cleanup(UUID u) {
        end(u);
        toldDay.remove(u);
    }

    @Override
    protected void expired(UUID u) {
        // nothing of theirs is ever held (the one crop is eaten the moment it is taken); the gift's note goes with the week
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    @Override
    public String status(UUID u) {
        SequelState.State st = state(u);
        if (st == SequelState.State.DONE) {
            Scarecrows.Scarecrow sc = Field.decode(get(u, "perk"));
            boolean holds = perkOn() && sc != null && Favor.holds(get(u, "perk-week"), week(u));
            return holds ? "이번 주 선물: " + sc.world() + " 청크 " + sc.cx() + "," + sc.cz() + " 작물이 " + cfg().getLong("perk.every-seconds", 60) + "초마다 "
                    + cfg().getDouble("perk.chance", 0.04) + " 확률로 한 단계 더 (청크가 불려 있을 때)" + (s.field().theirs(sc, u) ? "" : " <red>(허수아비 없음 · 멈춤)</red>") : "";
        }
        if (!st.live()) return "밤에 내 땅 허수아비 " + near() + "칸 안에 있으면 초마다 확률 " + cfg().getDouble("chance", 0.02) + " · 부탁 " + asks.size() + "가지";
        Riddles.Ask a = ask(u);
        Scarecrows.Scarecrow sc = bound(u);
        StringBuilder b = new StringBuilder("부탁 ").append(a == null ? "-" : a.id() + " (정답: " + String.join(", ", a.items()) + ")");
        b.append(" · 허수아비 ").append(sc == null ? "아직 없음 (내 땅 허수아비 근처로 가면 정해짐)" : sc.world() + " " + sc.x() + " " + sc.y() + " " + sc.z()
                + (s.field().theirs(sc, u) ? "" : " <red>(없음 · 일하지 않음 · 내 땅 아님 → 다른 허수아비 근처로)</red>"));
        b.append(scenes.containsKey(u) ? " · 지금 말하는 중 → 할 일: 그 작물을 들고 허수아비 우클릭" : " · 할 일: 밤에 그 허수아비 " + near() + "칸 안으로 가기");
        return b.toString();
    }

    @Override
    public List<String> scenes() {
        return List.of("부탁", "감사", "성장");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        switch (word) {
            case "부탁", "감사" -> {   // on the nearest scarecrow (anyone's): a random ask; nothing is taken, nothing saved
                if (asks.isEmpty()) {
                    admin.sendMessage(Text.mm("<red>scarecrow.yml sequel.asks 가 비어 있어요"));
                    return true;
                }
                Scarecrows.Scarecrow best = null;
                double bd = sq(48);
                for (Scarecrows.Scarecrow sc : s.field().all()) {
                    if (!sc.world().equals(p.getWorld().getName())) continue;
                    double dist = p.getLocation().distanceSquared(new Location(p.getWorld(), sc.x() + 0.5, sc.y(), sc.z() + 0.5));
                    if (dist < bd) {
                        bd = dist;
                        best = sc;
                    }
                }
                ItemDisplay real = best == null ? null : s.field().real(best).orElse(null);
                if (real == null) {
                    admin.sendMessage(Text.mm("<red>48칸 안에 허수아비가 없어요 (허수아비 옆에서 다시)"));
                    return true;
                }
                Riddles.Ask a = asks.get(ThreadLocalRandom.current().nextInt(asks.size()));
                Scene sn = show(p, best, real, a, false);
                admin.sendMessage(Text.mm("<gray>부탁 '" + a.id() + "' 정답: <white>" + String.join(", ", a.items()) + " <gray>(연출: 맞는 것을 줘도 가져가지 않음)"));
                if (word.equals("감사")) s.props().later(p, 60, () -> {
                    if (scenes.get(u) == sn && !sn.closing) eat(sn, new ItemStack(Material.WHEAT), false);
                });
                s.props().later(p, 600, () -> {
                    if (scenes.get(u) == sn) end(u);
                });
            }
            case "성장" -> {   // one growth pulse, at once, on the chunk of the nearest scarecrow of theirs (really grows)
                Scarecrows.Scarecrow sc = s.field().near(p, 64);
                if (sc == null) {
                    admin.sendMessage(Text.mm("<red>64칸 안에 그 사람 땅의 허수아비가 없어요"));
                    return true;
                }
                pulse(p.getWorld(), sc, Math.max(0, Math.min(1, cfg().getDouble("perk.chance", 0.04))));
                admin.sendMessage(Text.mm("<gray>청크 " + sc.cx() + "," + sc.cz() + " 에 성장 한 번 (확률 " + cfg().getDouble("perk.chance", 0.04) + ", <yellow>진짜로 자람</yellow>)"));
            }
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] 후속 " + word + " → " + p.getName()));
        return true;
    }
}
