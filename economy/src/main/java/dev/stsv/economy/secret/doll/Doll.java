package dev.stsv.economy.secret.doll;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** Placeholder: being written. */
public final class Doll extends Secret {

    public Doll(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "doll";
    }

    @Override
    public String name() {
        return "낡은인형";
    }

    @Override
    public int stages() {
        return 7;
    }

    @Override
    public void start(Player p) {
        m.stage(this, p.getUniqueId(), 1);
    }
}
