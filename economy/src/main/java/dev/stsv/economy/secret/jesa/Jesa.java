package dev.stsv.economy.secret.jesa;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** 숨은 비밀 (stub: the real thing replaces this file). */
public final class Jesa extends Secret {

    public Jesa(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "jesa";
    }

    @Override
    public String name() {
        return "주인없는제사";
    }

    @Override
    public int stages() {
        return 4;
    }

    @Override
    public void start(Player p) {
    }
}
