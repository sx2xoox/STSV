package dev.stsv.economy.secret.scarecrow;

import dev.stsv.core.crop.Scarecrows;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The last night (scarecrow.yml {@code final}). After the seventh offering it stands right outside their nearest door
 * (or, with no door, a few blocks from them) and knocks, louder and louder, until their own sky reaches dawn. Their
 * friend's voice calls their name in chat; the handle rattles (the door seems to move for them only); a straw hand is
 * pressed on the window. The rule was said once, faintly, by the last riddle: do not open the door until the sun is up.
 * Open it — or step off their land — and it is right there, face to face, then black: "…다음 밤에." Last until dawn and
 * it is back in its field, everything around it ripe: "…고마워. 이제 네 밭이야." — solved.
 */
final class Knock {

    private final Scarecrow s;
    final Player p;
    final UUID u;
    private final Scarecrows.Scarecrow sc;
    private final Block home;
    private final boolean forced;
    private Effigy e;
    private Block door, window;
    private int t, total, nextKnock, round, outside;
    private long skyStart;
    private int calls, rattles;
    private boolean hand, over;
    private String friend;
    private TextDisplay words;
    private List<Block> withered = new ArrayList<>();
    private BukkitTask task;

    Knock(Scarecrow s, Player p, Scarecrows.Scarecrow sc, Block home, boolean forced) {
        this.s = s;
        this.p = p;
        this.u = p.getUniqueId();
        this.sc = sc;
        this.home = home;
        this.forced = forced;
    }

    private YamlConfiguration cfg() {
        return s.cfg();
    }

    boolean begin() {
        ItemDisplay real = s.field().real(sc).orElse(null);
        if (real == null) return false;
        e = Effigy.of(s.props(), p, real, s.field().yawOffset());
        s.returned(p);
        Location field = home.getLocation().add(0.5, 0, 0.5);
        door = s.field().door(field, u, cfg().getInt("night.door-radius", 24));
        if (door == null) door = s.field().door(p.getLocation(), u, cfg().getInt("night.door-radius", 24));
        Block stand;
        if (door != null) {
            Block out = s.field().outside(door, field);
            Block g = Field.ground(out.getWorld(), out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
            stand = g != null ? g : out;
            window = s.field().window(door);
        } else {
            double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2), d = cfg().getDouble("final.no-door-distance", 6);
            Location l = p.getLocation();
            Block g = Field.ground(l.getWorld(), l.getX() + Math.cos(a) * d, l.getBlockY(), l.getZ() + Math.sin(a) * d);
            stand = g != null ? g : l.getBlock();
        }
        // facing the door (or them): it has come for the house
        e.moveTo(stand, door != null ? Geometry.yawTo(door.getX() - stand.getX(), door.getZ() - stand.getZ())
                : Geometry.yawTo(p.getLocation().getX() - stand.getX() - 0.5, p.getLocation().getZ() - stand.getZ() - 0.5));
        World w = home.getWorld();
        boolean night = s.night(p, w) && !s.testing(u);
        long from = cfg().getLong("night.from", 13000), to = cfg().getLong("night.to", 23000);
        int sec = s.testing(u) ? cfg().getInt("final.test-seconds", 60)
                : Geometry.finalSeconds(night ? Geometry.nightLeft(w.getTime(), from, to) : 0, cfg().getInt("final.min-seconds", 150), cfg().getInt("final.max-seconds", 360));
        total = Math.max(10, sec) * 20;
        skyStart = night ? Math.floorMod(w.getTime(), 24000L) : 18000;
        if (skyStart > 22000) skyStart = 21000;
        p.setPlayerTime(skyStart, false);
        List<String> friends = s.memoryFriends(u);
        friend = friends.stream().filter(n -> !n.equalsIgnoreCase(p.getName())).findFirst().orElse(null);
        if (friend == null) {
            List<String> on = new ArrayList<>();
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p) on.add(o.getName());
            if (!on.isEmpty()) friend = on.get(ThreadLocalRandom.current().nextInt(on.size()));
        }
        // the field stays withered for them until dawn
        withered = s.fx().wither(p, s.field().crops(home, cfg().getInt("night.wither-radius", 5)));
        nextKnock = 100;
        task = s.props().every(p, 1, this::step);
        return true;
    }

    String describe() {
        return "마지막 밤 " + t / 20 + "/" + total / 20 + "초 · 하늘 " + Geometry.sky(skyStart, 23000, t / (double) total)
                + (door != null ? " · 문 " + door.getX() + " " + door.getY() + " " + door.getZ() : " · 문 없음 (땅만 지키면 됨)")
                + (window != null ? " · 창문 " + window.getX() + " " + window.getY() + " " + window.getZ() : "");
    }

    Block door() {
        return door;
    }

    private Location knockAt() {
        return door != null ? door.getLocation().add(0.5, 1.0, 0.5) : e.head();
    }

    private void step() {
        if (over) return;
        if (!p.isOnline() || p.isDead()) {
            s.endKnock(this, false);
            return;
        }
        t++;
        double share = t / (double) total;
        if (t % 20 == 0) p.setPlayerTime(Geometry.sky(skyStart, 23000, share), false);
        if (t % 10 == 0) {
            if (!s.field().allows(p.getLocation(), u)) {
                outside += 10;
                if (outside >= cfg().getInt("final.leave-grace-seconds", 2) * 20) {
                    caught(false);
                    return;
                }
            } else outside = 0;
        }
        if (door == null && t % 4 == 0) e.turn(e.yawTo(p.getLocation()), 2f);
        if (t >= total) {
            dawn();
            return;
        }
        if (share >= cfg().getDouble("final.silent-from", 0.9)) return;
        knocking(share);
        List<Double> callAt = cfg().getDoubleList("final.call-at");
        if (calls < callAt.size() && share >= callAt.get(calls)) {
            call(calls);
            calls++;
        }
        List<Double> rattleAt = cfg().getDoubleList("final.rattle-at");
        if (rattles < rattleAt.size() && share >= rattleAt.get(rattles)) {
            rattles++;
            if (door != null) s.fx().rattle(p, door);
        }
        if (!hand && share >= cfg().getDouble("final.hand-at", 0.55)) {
            hand = true;
            if (window != null) s.fx().hand(p, window, 120);
        }
    }

    /** Knocks in rounds: quiet and far apart at first, then louder and closer together, the last ones heavy. */
    private void knocking(double share) {
        if (t < nextKnock) return;
        float vol = (float) Math.min(1.0, 0.3 + share * 0.9);
        boolean heavy = share > 0.75 && round % 2 == 1;
        int n = share > 0.6 ? 5 : 3;
        s.fx().knocks(p, knockAt(), vol, n, heavy);
        round++;
        nextKnock = t + n * 7 + (int) Math.max(30, 150 - share * 130) + ThreadLocalRandom.current().nextInt(20);
    }

    private void call(int i) {
        List<String> lines = cfg().getStringList("final.calls");
        if (lines.isEmpty()) return;
        String line = lines.get(Math.min(i, lines.size() - 1)).replace("{me}", p.getName());
        if (friend != null) s.haunt().chat(p, friend, line);
        else s.haunt().whisper(p, "<dark_gray><i>" + line);
    }

    /** They opened the door (the click is refused; for them it swings open — and it is there). */
    void opened(Block clicked) {
        if (over || door == null) return;
        s.fx().open(p, door, door.getRelative(BlockFace.UP), true);
        caught(true);
    }

    /** Face to face, then black. That night is over; the knocking comes back another night. */
    private void caught(boolean viaDoor) {
        over = true;
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        Location feet = eye.clone().add(dir.clone().multiply(0.55)).subtract(0, Effigy.HEAD, 0);
        e.placeAt(feet, Geometry.yawTo(-dir.getX(), -dir.getZ()));
        s.fx().scare(p, eye);
        s.props().later(p, 14, () -> s.haunt().black(p, 4));
        s.props().later(p, 30, () -> {
            e.remove();
            if (door != null) {
                s.props().restore(p, door);
                s.props().restore(p, door.getRelative(BlockFace.UP));
            }
        });
        s.props().later(p, 85, () -> s.haunt().whisper(p, cfg().getString("lines.locked", "<dark_gray>…")));
        s.props().later(p, 90, () -> s.endKnock(this, false));
    }

    /** Dawn for them: back in its field, the crops around it ripe, and its thanks. */
    private void dawn() {
        over = true;
        p.setPlayerTime(23400, false);
        e.remove();
        s.props().restoreAll(p, withered);
        withered = List.of();
        Location head = home.getLocation().add(0.5, Effigy.HEAD + 0.75, 0.5);
        s.field().ripen(home, cfg().getInt("night.wither-radius", 5) + 1);
        s.fx().crows(p, home.getLocation(), 5);
        s.props().later(p, 40, () -> {
            words = s.haunt().text(p, head, "<gray><i>" + cfg().getString("lines.thanks", "…"));
            s.haunt().whisper(p, cfg().getString("lines.thanks-whisper", "<gray>…"));
        });
        s.props().later(p, 160, () -> {
            if (words != null) s.haunt().remove(p, words);
            s.endKnock(this, true);
        });
    }

    void stop() {
        over = true;
        if (task != null) task.cancel();
        if (e != null) e.remove();
        if (words != null && p.isOnline()) s.haunt().remove(p, words);
        if (p.isOnline()) {
            p.resetPlayerTime();
            s.props().restoreAll(p, withered);
            if (door != null) {
                s.props().restore(p, door);
                s.props().restore(p, door.getRelative(BlockFace.UP));
            }
        }
    }

    boolean forced() {
        return forced;
    }
}
