package dev.stsv.economy.secret.scarecrow;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** Placeholder: being written. */
public final class Scarecrow extends Secret {

    public Scarecrow(SecretModule m) {
        super(m);
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
        return 7;
    }

    @Override
    public void start(Player p) {
        m.stage(this, p.getUniqueId(), 1);
    }
}
