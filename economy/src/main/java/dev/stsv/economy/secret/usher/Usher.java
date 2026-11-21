package dev.stsv.economy.secret.usher;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** Placeholder: being written. */
public final class Usher extends Secret {

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
        return 3;
    }

    @Override
    public void start(Player p) {
        m.stage(this, p.getUniqueId(), 1);
    }
}
