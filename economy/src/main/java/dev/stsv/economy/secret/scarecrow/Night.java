package dev.stsv.economy.secret.scarecrow;

import dev.stsv.core.crop.Scarecrows;
import dev.stsv.core.util.Text;
import dev.stsv.economy.util.Items;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One night of offerings (scarecrow.yml {@code night}, {@code asks}). Only the player sees any of it: the real scarecrow
 * is hidden from them and a copy stands in its place ({@link Effigy}). It turns on its pole to follow them. Then it asks,
 * a riddle at a time; they answer by right-clicking it with the right crop. Every offering it is found a little nearer
 * their door (only ever moved while they are not looking), the crows gather, straw rustles behind them; after the fifth
 * it stands at the edge of the field looking at their house and the field withers around it (for them). Three wrong
 * things or a riddle left too long and it turns away: "…다음에." — that night is over. Dawn just pauses it.
 * After the seventh the night hands over to {@link Knock}.
 */
final class Night {

    enum End { FAIL, PAUSE, AWAY, DONE, STOP }

    private enum Phase { TURN, WAIT, ASK }

    private final Scarecrow s;
    final Player p;
    final UUID u;
    final Scarecrows.Scarecrow sc;
    private final Block home;
    private final boolean forced;
    private final boolean intro;
    private Effigy e;
    private Block door;
    private Phase phase;
    private int t, nextAsk, deadline, wrongs, nextCrow, nextRustle, awayUntil, done;
    private boolean hurried, closing;
    private TextDisplay words;
    private List<Block> withered = new ArrayList<>();
    private Block pending;
    private BukkitTask task;

    Night(Scarecrow s, Player p, Scarecrows.Scarecrow sc, Block home, boolean forced, boolean intro) {
        this.s = s;
        this.p = p;
        this.u = p.getUniqueId();
        this.sc = sc;
        this.home = home;
        this.forced = forced;
        this.intro = intro;
    }

    private YamlConfiguration cfg() {
        return s.cfg();
    }

    private int secs(String k, int def) {
        return s.secs(u, "night." + k, def);
    }

    /** Starts it (false when the real scarecrow cannot be found). */
    boolean begin() {
        ItemDisplay real = s.field().real(sc).orElse(null);
        if (real == null) return false;
        done = s.done(u);
        e = Effigy.of(s.props(), p, real, s.field().yawOffset());
        e.clickable();
        door = s.field().door(home.getLocation().add(0.5, 0, 0.5), u, cfg().getInt("night.door-radius", 24));
        if (s.testing(u)) p.setPlayerTime(18000, false);
        if (done > 0) {
            Block at = spot(done);
            if (at != null) e.moveTo(at, e.yawTo(p.getLocation()));
        }
        if (done >= cfg().getInt("night.step-edge", 5)) wither();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        nextCrow = 20 * (8 + r.nextInt(10));
        nextRustle = 20 * (15 + r.nextInt(15));
        if (intro) {
            phase = Phase.TURN;
            nextAsk = s.secs(u, "start.turn-seconds", 20) * 20;
        } else {
            phase = Phase.WAIT;
            nextAsk = secs("first-ask-seconds", 15) * 20;
        }
        task = s.props().every(p, 2, this::step);
        return true;
    }

    int done() {
        return done;
    }

    String describe() {
        StringBuilder b = new StringBuilder(phase == Phase.TURN ? "고개를 돌리는 중" : phase == Phase.WAIT ? "다음 부탁까지 " + Math.max(0, (nextAsk - t) / 20) + "초"
                : "부탁 " + (done + 1) + " · 기한 " + Math.max(0, (deadline - t) / 20) + "초");
        b.append(" · 틀림 ").append(wrongs).append("/").append(cfg().getInt("night.wrongs", 3));
        if (e != null) b.append(" · 서 있는 곳 ").append(e.feet().getX()).append(" ").append(e.feet().getY()).append(" ").append(e.feet().getZ());
        if (door != null) b.append(" · 문 ").append(door.getX()).append(" ").append(door.getY()).append(" ").append(door.getZ());
        return b.toString();
    }

    Effigy effigy() {
        return e;
    }

    /** Whether a click on the real scarecrow counts as a click on it (it still stands at home). */
    boolean atHome() {
        return e != null && e.feet().equals(home);
    }

    // ------------------------------------------------------------------------------------------
    // each tenth of a second
    // ------------------------------------------------------------------------------------------

    private void step() {
        if (closing) return;
        if (!p.isOnline() || p.isDead() || e == null || !e.valid()) {
            s.endNight(this, End.STOP);
            return;
        }
        t += 2;
        if (t % 20 == 0) {
            if (!forced && !s.night(p, home.getWorld())) {
                say(cfg().getString("lines.pause", "<dark_gray>…"));
                s.endNight(this, End.PAUSE);
                return;
            }
            if (!s.field().theirs(sc, u)) {
                s.endNight(this, End.STOP);
                return;
            }
        }
        double dist = p.getWorld() == home.getWorld() ? p.getLocation().distance(home.getLocation()) : 999;
        if (dist > cfg().getDouble("night.leave", 64) && phase != Phase.ASK) {
            s.endNight(this, End.AWAY);
            return;
        }
        look();
        if (pending != null && !e.seen()) {
            e.moveTo(pending, e.yawTo(p.getLocation()));
            pending = null;
            s.fx().sound(p, e.head(), "rustle", "block.azalea_leaves.step", 0.25f, 0.5f);
        }
        if (words != null && words.isValid()) words.teleport(e.head().add(0, 0.75, 0));
        ambient();
        switch (phase) {
            case TURN -> {
                if (t >= nextAsk) {
                    s.fx().straw(p);
                    phase = Phase.WAIT;
                    nextAsk = t + secs("gap-seconds", 60) * 20;
                }
            }
            case WAIT -> {
                if (t >= nextAsk && dist <= cfg().getDouble("night.near", 32)) speak();
            }
            case ASK -> {
                int hurry = secs("hurry-seconds", 30) * 20;
                if (!hurried && deadline - t <= hurry) {
                    hurried = true;
                    say(cfg().getString("lines.hurry", "<dark_gray>…"));
                    s.fx().sound(p, e.head(), "rustle", "block.azalea_leaves.step", 0.6f, 0.45f);
                }
                if (t >= deadline) fail();
            }
        }
    }

    /** Where its head points: at them (slowly, faster with every offering); after the fifth, at their door while watched. */
    private void look() {
        if (t < awayUntil) return;
        float toThem = e.yawTo(p.getLocation());
        if (done >= cfg().getInt("night.step-edge", 5) && door != null) {
            if (e.seen()) e.turn(e.yawTo(door.getLocation().add(0.5, 0, 0.5)), 1.2f);
            else e.face(toThem);
            return;
        }
        float step = phase == Phase.TURN ? 0.9f : 1.4f + done * 0.7f;
        e.turn(toThem, step);
    }

    /** Crows from the second offering (more each time, quiet on the sixth), straw behind them from the third. */
    private void ambient() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (done >= 2 && done != 6 && t >= nextCrow) {
            s.fx().crows(p, home.getLocation(), done - 1);
            nextCrow = t + (int) (20 * (20 + r.nextInt(20)) / (1 + done * 0.25));
        }
        if (done >= 3 && t >= nextRustle) {
            s.fx().rustleBehind(p);
            nextRustle = t + (int) (20 * (25 + r.nextInt(20)) / (1 + (done - 3) * 0.3));
        }
    }

    // ------------------------------------------------------------------------------------------
    // asking and answering
    // ------------------------------------------------------------------------------------------

    private Riddles.Ask current() {
        return s.riddles().ask(done + 1);
    }

    private void speak() {
        Riddles.Ask a = current();
        if (a.most()) s.setMost(u, s.field().mostPlanted(home, u, cfg().getInt("night.most-radius", 24)));
        showWords(String.join("\n", a.say().stream().map(l -> "<gray><i>" + l).toList()));
        for (String l : a.say()) p.sendMessage(Text.mm("<dark_gray><i>" + l));
        s.fx().sound(p, e.head(), "rustle", "block.azalea_leaves.step", 0.7f, 0.5f);
        phase = Phase.ASK;
        hurried = false;
        deadline = t + secs("ask-seconds", 180) * 20;
        s.asked(u, deadline - t);
    }

    private void showWords(String mm) {
        if (words != null) s.haunt().remove(p, words);
        words = s.haunt().text(p, e.head().add(0, 0.75, 0), mm);
    }

    private void say(String mm) {
        s.haunt().whisper(p, mm);
    }

    /** They held something out to it (right-click). */
    void offer(ItemStack hand) {
        if (phase != Phase.ASK) {
            s.fx().sound(p, e.head(), "rustle", "block.azalea_leaves.step", 0.3f, 0.5f);
            return;
        }
        Riddles.Ask a = current();
        if (hand == null || hand.isEmpty()) {
            say("<gray><i>" + a.say().getFirst());
            return;
        }
        String key = Items.key(hand);
        Set<String> given = s.given(u);
        switch (Riddles.judge(a, key, s.most(u), given)) {
            case RIGHT -> right(hand, key);
            case AGAIN -> wrong(cfg().getString("lines.again", "<dark_gray>…"));
            case WRONG -> {
                List<String> l = cfg().getStringList("lines.wrong");
                wrong(l.isEmpty() ? "<dark_gray>…" : l.get(Math.min(wrongs, l.size() - 1)));
            }
        }
    }

    private void right(ItemStack hand, String key) {
        ItemStack one = hand.asOne();
        hand.subtract(1);
        done++;
        s.gave(u, key, done);
        s.fx().eat(p, e.head(), one);
        showWords(cfg().getString("lines.eat", "<dark_gray>…"));
        TextDisplay w = words;
        s.props().later(p, 50, () -> {
            if (words == w) {
                s.haunt().remove(p, w);
                words = null;
            }
        });
        List<String> ate = cfg().getStringList("lines.ate");
        if (!ate.isEmpty()) say(ate.get(Math.min(done - 1, ate.size() - 1)));
        if (done >= s.riddles().size()) {
            // the last one eaten: it looks at their house, and a moment later it is gone from the field
            closing = true;
            if (door != null) e.face(e.yawTo(door.getLocation().add(0.5, 0, 0.5)));
            s.props().later(p, 60, () -> s.endNight(this, End.DONE));
            return;
        }
        phase = Phase.WAIT;
        nextAsk = t + secs("gap-seconds", 60) * 20;
        Block at = spot(done);
        if (at != null && !at.equals(e.feet())) pending = at;
        if (done == cfg().getInt("night.step-edge", 5)) {
            wither();
            s.fx().crows(p, home.getLocation(), 6);
        }
    }

    private void wrong(String line) {
        wrongs++;
        say(line);
        e.face(e.yawTo(p.getLocation()) + 180);
        awayUntil = t + 60;
        s.fx().sound(p, e.head(), "straw", "block.grass.break", 0.5f, 0.6f);
        if (wrongs >= cfg().getInt("night.wrongs", 3)) fail();
    }

    /** It turns its back on them, says so, and a few seconds later it is only a scarecrow again. */
    private void fail() {
        closing = true;
        e.face(e.yawTo(p.getLocation()) + 180);
        say(cfg().getString("lines.away", "<dark_gray>…"));
        s.fx().sound(p, e.head(), "straw", "block.grass.break", 0.4f, 0.5f);
        if (words != null) {
            s.haunt().remove(p, words);
            words = null;
        }
        s.props().later(p, 70, () -> s.endNight(this, End.FAIL));
    }

    // ------------------------------------------------------------------------------------------
    // where it is found
    // ------------------------------------------------------------------------------------------

    /** Where it stands after n offerings: along the way from its spot to their door (or to them, with no door). */
    private Block spot(int n) {
        World w = home.getWorld();
        Location from = home.getLocation().add(0.5, 0, 0.5);
        Location to = door != null ? door.getLocation().add(0.5, 0, 0.5) : p.getLocation();
        if (to.getWorld() != w) return null;
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1e-3) return null;
        double a = Geometry.along(n, dist, cfg().getInt("night.step-edge", 5), cfg().getDouble("night.edge", 0.6), cfg().getDouble("night.keep", 3));
        if (a <= 0) return home;
        Block b = Field.ground(w, from.getX() + dx / dist * a, home.getY(), from.getZ() + dz / dist * a);
        return b == null ? e.feet() : b;
    }

    private void wither() {
        if (!withered.isEmpty()) return;
        withered = s.fx().wither(p, s.field().crops(home, cfg().getInt("night.wither-radius", 5)));
    }

    /** Takes it all away (the copy, its words, the withered field, their sky). */
    void stop() {
        if (task != null) task.cancel();
        if (words != null) s.haunt().remove(p, words);
        if (e != null) e.remove();
        if (p.isOnline()) {
            s.props().restoreAll(p, withered);
            if (s.testing(u)) p.resetPlayerTime();
        }
        withered = List.of();
    }
}
