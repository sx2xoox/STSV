package dev.stsv.economy.secret.fish;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** Placeholder: being written. */
public final class EyelessFish extends Secret {

    public EyelessFish(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "fish";
    }

    @Override
    public String name() {
        return "눈없는물고기";
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
