package dev.stsv.economy.secret.newcomer;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** 숨은 비밀 (stub: the real thing replaces this file). */
public final class Newcomer extends Secret {

    public Newcomer(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "newcomer";
    }

    @Override
    public String name() {
        return "새로온사람";
    }

    @Override
    public int stages() {
        return 1;
    }

    @Override
    public void start(Player p) {
    }
}
