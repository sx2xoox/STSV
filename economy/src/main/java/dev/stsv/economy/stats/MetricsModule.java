package dev.stsv.economy.stats;

import dev.stsv.core.data.Db;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.DailyListener;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The 05:00 report to the admins: money supply growth, sink/faucet ratio against the week's goal,
 * inequality, the bottom quarter's hourly income against the top's, and items stuck at the price floor.
 */
public final class MetricsModule extends EcoModule implements DailyListener {

    private Db db;

    public MetricsModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-metrics";
    }

    @Override
    protected void start() {
        db = core.db();
        db.migrate("eco_daily", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_daily (day INT NOT NULL PRIMARY KEY, supply BIGINT NOT NULL, faucet BIGINT NOT NULL, sink BIGINT NOT NULL, "
                        + "gini DOUBLE NOT NULL, low_hourly DOUBLE NOT NULL, top_hourly DOUBLE NOT NULL, floor_items INT NOT NULL, players INT NOT NULL)")));
        eco.season().onDaily(this);
        eco.onReset(() -> db.execute("DELETE FROM eco_daily"));
    }

    @Override
    public void newDay(int day, long dayStart) {
        if (!eco.season().running() || day <= 1) return;
        build(day - 1, dayStart - 86_400_000L, eco.shop().floorHitsYesterday()).thenAccept(r -> sync(() -> {
            db.execute(db.upsert("eco_daily", List.of("day"), List.of("supply", "faucet", "sink", "gini", "low_hourly", "top_hourly", "floor_items", "players")),
                    r.day(), r.supply(), r.faucet(), r.sink(), r.gini(), r.lowHourly(), r.topHourly(), r.floorItems(), r.players());
            List<net.kyori.adventure.text.Component> lines = lines(r);
            lines.forEach(l -> Bukkit.getConsoleSender().sendMessage(l));
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("stsv.economy.admin")) lines.forEach(p::sendMessage);
        }));
    }

    /** The report of a day (from its start for 24 hours). */
    public CompletableFuture<Report> build(int day, long from, int floorItems) {
        int week = (day - 1) / 7 + 1;
        CompletableFuture<Long> previous = db.async(c -> {
            List<Long> v = Db.query(c, "SELECT supply FROM eco_daily WHERE day = ?", rs -> rs.getLong(1), day - 1);
            return v.isEmpty() ? 0L : v.getFirst();
        });
        CompletableFuture<List<ActivityStore.Day>> activity = eco.activity().store().day(day);
        return core.economy().store().stats(from).thenCombine(previous, (st, prev) -> new Object[]{st, prev}).thenCombine(activity, (o, rows) -> {
            var st = (dev.stsv.core.economy.EconomyStore.Stats) o[0];
            List<long[]> rates = new ArrayList<>();
            for (ActivityStore.Day d : rows) rates.add(new long[]{d.income(), d.seconds()});
            double[] q = Report.quartiles(rates);
            return new Report(day, st.total(), (Long) o[1], st.faucet(), st.sink(), st.gini(), q[0], q[1], floorItems, rows.size(), Report.targetRatio(week));
        });
    }

    public List<net.kyori.adventure.text.Component> lines(Report r) {
        List<net.kyori.adventure.text.Component> out = new ArrayList<>();
        out.add(Lang.msg("report.header", "day", r.day()));
        out.add(Lang.mm("report.supply", "supply", String.format("%,d", r.supply()),
                "growth", r.previousSupply() > 0 ? String.format("%+.1f%%", r.growth() * 100) : "-"));
        out.add(Lang.mm("report.flow", "faucet", String.format("%,d", r.faucet()), "sink", String.format("%,d", r.sink()),
                "ratio", String.format("%.0f%%", r.ratio() * 100), "target", String.format("%.0f%%", r.targetRatio() * 100)));
        out.add(Lang.mm("report.gini", "gini", String.format("%.2f", r.gini()), "players", r.players()));
        out.add(Lang.mm("report.income", "low", String.format("%,.0f", r.lowHourly()), "top", String.format("%,.0f", r.topHourly())));
        out.add(Lang.mm("report.floor", "count", r.floorItems()));
        for (String w : r.warnings()) out.add(Lang.mm(w));
        if (r.warnings().isEmpty()) out.add(Lang.mm("report.ok"));
        return out;
    }

    public void show(CommandSender s, Integer day) {
        if (day == null) {
            int today = eco.season().day();
            build(today, eco.season().days().dayStart(now()), eco.shop().floorHitsToday())
                    .thenAccept(r -> sync(() -> lines(r).forEach(s::sendMessage)));
            return;
        }
        db.async(c -> Db.query(c, "SELECT day, supply, faucet, sink, gini, low_hourly, top_hourly, floor_items, players FROM eco_daily WHERE day IN (?, ?) ORDER BY day",
                rs -> new Object[]{rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getInt(8), rs.getInt(9)},
                day - 1, day)).thenAccept(rows -> sync(() -> {
            Object[] r = null, prev = null;
            for (Object[] x : rows) {
                if ((Integer) x[0] == day) r = x;
                else prev = x;
            }
            if (r == null) {
                s.sendMessage(Text.msg("<red>" + day + "일차 보고서가 없습니다."));
                return;
            }
            Report rep = new Report(day, (Long) r[1], prev == null ? 0 : (Long) prev[1], (Long) r[2], (Long) r[3], (Double) r[4],
                    (Double) r[5], (Double) r[6], (Integer) r[7], (Integer) r[8], Report.targetRatio((day - 1) / 7 + 1));
            lines(rep).forEach(s::sendMessage);
        }));
    }
}
