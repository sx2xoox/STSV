package dev.stsv.economy.secret;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * The smallest 후속 임무 an author could write (the same shape as the author guide's example): it only has to compile
 * against {@link Sequel}. A real one lives in its secret's package and is returned from {@link Secret#sequel()}.
 */
final class ExampleSequel extends Sequel {

    ExampleSequel(Secret secret) {
        super(secret);
    }

    @Override
    public String id() {
        return "남은 쪽지";
    }

    @Override
    protected boolean offer(Player p) {
        if (!m.hours(p.getUniqueId(), 0, 5) || !chance(p, "chance", 0.0005)) return false;
        start(p);
        return true;
    }

    @Override
    protected void start(Player p) {
        set(p.getUniqueId(), "place", p.getLocation().getBlockX() + "," + p.getLocation().getBlockZ());
        tell(p, "begin", "<dark_gray>…아직 남은 게 있다.");
    }

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        if (state(u) == SequelState.State.OFFERED && p.isSneaking()) accept(p);
        else if (state(u) == SequelState.State.ACTIVE && elapsed(u) >= seconds(u, "wait", 600)) complete(p);
    }

    @Override
    protected void resume(Player p) {
        tell(p, "resume", "");
    }

    @Override
    protected void cleanup(UUID player) {
        m.haunt().clear(player);
    }

    @Override
    protected void expired(UUID player) {}

    @Override
    public String status(UUID player) {
        return "자리 " + get(player, "place", "-");
    }
}
