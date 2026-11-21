package dev.stsv.economy.secret.designer;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** Placeholder: being written. */
public final class Designer extends Secret {

    public Designer(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "designer";
    }

    @Override
    public String name() {
        return "설계자";
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
