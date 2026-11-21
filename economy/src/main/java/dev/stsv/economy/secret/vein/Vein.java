package dev.stsv.economy.secret.vein;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** Placeholder: being written. */
public final class Vein extends Secret {

    public Vein(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "vein";
    }

    @Override
    public String name() {
        return "속삭이는광맥";
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
