package dev.stsv.economy.secret.dongbang;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** Placeholder: being written. */
public final class Dongbang extends Secret {

    public Dongbang(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "dongbang";
    }

    @Override
    public String name() {
        return "동방해운";
    }

    @Override
    public int stages() {
        return 4;
    }

    @Override
    public void start(Player p) {
        m.stage(this, p.getUniqueId(), 1);
    }
}
