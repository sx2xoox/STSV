package dev.stsv.economy.secret.garage;

import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.entity.Player;

/** 숨은 비밀 (stub: the real thing replaces this file). */
public final class Garage extends Secret {

    public Garage(SecretModule m) {
        super(m);
    }

    @Override
    public String id() {
        return "garage";
    }

    @Override
    public String name() {
        return "폐차고";
    }

    @Override
    public int stages() {
        return 4;
    }

    @Override
    public void start(Player p) {
    }
}
