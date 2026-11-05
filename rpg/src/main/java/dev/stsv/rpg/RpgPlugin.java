package dev.stsv.rpg;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.rpg.balance.Balance;
import dev.stsv.rpg.combat.CombatModule;
import dev.stsv.rpg.player.PlayerModule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The RPG server: a content plugin on top of the STSV core. Its modules are registered with the
 * core, so /관리 리로드 reloads them together with the core's.
 */
public final class RpgPlugin extends JavaPlugin {

    private static RpgPlugin instance;

    private final List<StsvModule> modules = new ArrayList<>();
    private volatile Balance balance;
    private PlayerModule players;
    private CombatModule combat;

    public static RpgPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadSettings();
        add(players = new PlayerModule(this));
        add(combat = new CombatModule(this));
        Commands.register(this);
    }

    /** Re-reads config.yml and messages.yml (the first module's reload calls this). */
    public void reloadSettings() {
        reloadConfig();
        ConfigurationSection b = getConfig().getConfigurationSection("balance");
        if (b == null) throw new IllegalStateException("config.yml 에 balance 가 없습니다");
        balance = new Balance(b);
        Lang.register(this);
    }

    private void add(StsvModule m) {
        STSVPlugin.get().modules().register(m);
        modules.add(m);
    }

    @Override
    public void onDisable() {
        STSVPlugin core = STSVPlugin.get();
        List<StsvModule> reversed = new ArrayList<>(modules);
        Collections.reverse(reversed);
        if (core != null) for (StsvModule m : reversed) core.modules().unregister(m.id());
        modules.clear();
    }

    public Balance balance() {
        return balance;
    }

    public PlayerModule players() {
        return players;
    }

    public CombatModule combat() {
        return combat;
    }
}
