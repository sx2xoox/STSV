package dev.stsv.economy.secret.pager;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** 숨은 비밀 (stub: the real thing replaces this file). */
public final class Pager extends Secret {

    public Pager(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "pager";
    }

    @Override
    public String name() {
        return "삐삐";
    }

    @Override
    public int stages() {
        return 6;
    }

    @Override
    public void start(Player p) {
    }
}
