package dev.stsv.economy;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * A part of the economy server. Registered with the core's module manager (so /관리 리로드 reaches it),
 * but its listeners and tasks belong to the economy plugin, so they stop when that plugin stops.
 */
public abstract class EcoModule implements StsvModule, Listener {

    protected final EcoPlugin eco;
    protected STSVPlugin core;
    private final List<BukkitTask> tasks = new ArrayList<>();

    protected EcoModule(EcoPlugin eco) {
        this.eco = eco;
    }

    @Override
    public final void enable(STSVPlugin core) throws Exception {
        this.core = core;
        start();
        Bukkit.getPluginManager().registerEvents(this, eco);
    }

    protected abstract void start() throws Exception;

    protected void stop() {}

    @Override
    public final void disable() {
        HandlerList.unregisterAll(this);
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        stop();
    }

    protected void every(long ticks, Runnable r) {
        tasks.add(Bukkit.getScheduler().runTaskTimer(eco, () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                eco.getLogger().log(java.util.logging.Level.WARNING, id() + " 작업 오류", e);
            }
        }, ticks, ticks));
    }

    /** Runs on the main thread (from a database callback). */
    protected void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else if (!eco.isEnabled()) {
            // shutting down: the scheduler refuses new tasks; finish the work rather than lose it
            try {
                r.run();
            } catch (RuntimeException e) {
                eco.getLogger().warning(id() + " 종료 중 작업 실패: " + e);
            }
        } else Bukkit.getScheduler().runTask(eco, r);
    }

    protected long now() {
        return System.currentTimeMillis();
    }
}
