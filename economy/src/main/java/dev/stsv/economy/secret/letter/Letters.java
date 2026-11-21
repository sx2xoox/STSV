package dev.stsv.economy.secret.letter;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** Placeholder: being written. */
public final class Letters extends Secret {

    public Letters(SecretModule m) {
        super(m);
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

    @Override
    public void start(Player p) {
        m.stage(this, p.getUniqueId(), 1);
    }
}
