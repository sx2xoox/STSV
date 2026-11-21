package dev.stsv.economy.secret.lostkey;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** Placeholder: being written. */
public final class LostKey extends Secret {

    public LostKey(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "lostkey";
    }

    @Override
    public String name() {
        return "분실물센터";
    }

    @Override
    public int stages() {
        return 5;
    }

    @Override
    public void start(Player p) {
        m.stage(this, p.getUniqueId(), 1);
    }
}
