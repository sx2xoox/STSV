package dev.stsv.economy.stats;

import dev.stsv.core.economy.BalanceChangeEvent;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.calendar.DailyListener;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Who played today, for how long, what they earned; the 오늘 손익 status line. */
public final class ActivityModule extends EcoModule implements DailyListener {

    private ActivityStore store;
    private final Map<UUID, Long> mark = new ConcurrentHashMap<>();

    public ActivityModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-activity";
    }

    @Override
    protected void start() {
        store = new ActivityStore(core.db());
        store.init(eco.calendar().day());
        for (Player p : Bukkit.getOnlinePlayers()) joined(p);
        every(1200L, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) count(p);
            store.flush();
        });
        core.hud().line("eco-today", 10, p -> {
            if (!eco.getConfig().getBoolean("hud.today")) return null;
            long delta = core.economy().balance(p.getUniqueId()) - store.startBalance(p.getUniqueId(), core.economy().balance(p.getUniqueId()));
            return Lang.mm(delta >= 0 ? "activity.hud-up" : "activity.hud-down", "amount", String.format("%,d", Math.abs(delta)));
        });
        eco.calendar().onDaily(this);
    }

    @Override
    protected void stop() {
        for (Player p : Bukkit.getOnlinePlayers()) count(p);
        store.flush();
        core.hud().removeLine("eco-today");
    }

    public ActivityStore store() {
        return store;
    }

    @Override
    public void newDay(int day, long dayStart) {
        for (Player p : Bukkit.getOnlinePlayers()) count(p);
        store.newDay(day);
        for (Player p : Bukkit.getOnlinePlayers()) store.seen(p.getUniqueId(), core.economy().balance(p.getUniqueId()));
    }

    private void joined(Player p) {
        mark.put(p.getUniqueId(), now());
        store.seen(p.getUniqueId(), core.economy().balance(p.getUniqueId()));
    }

    private void count(Player p) {
        long now = now();
        Long since = mark.put(p.getUniqueId(), now);
        if (since != null) store.addSeconds(p.getUniqueId(), (now - since) / 1000);
    }

    // after the core opened the account (LOWEST) so the start money is in the start balance
    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent e) {
        joined(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        count(e.getPlayer());
        mark.remove(e.getPlayer().getUniqueId());
    }

    /** Earnings: money that came in from outside (shop, requests, rewards) or from other players. */
    @EventHandler
    public void onMoney(BalanceChangeEvent e) {
        if (e.delta() <= 0) return;
        if (e.kind() == EconomyStore.Kind.FAUCET || e.kind() == EconomyStore.Kind.TRANSFER) {
            if (!e.reason().equals("start")) store.addIncome(e.player(), e.delta());
        }
    }
}
