package dev.stsv.economy.rank;

import dev.stsv.core.data.Db;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.auction.Auction;
import dev.stsv.economy.calendar.WeeklyListener;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 순위. The weekly ranking is what each player earned this week: their net worth now minus their net
 * worth when the week started (Monday 05:00, or when they first joined that week). On Monday the
 * week is settled: the top 5 are announced and the winner gets a 기념비 건립권. Net worth = cash +
 * money held in auction bids + money spent on land.
 */
public final class RankModule extends EcoModule implements WeeklyListener {

    public record Entry(UUID player, long amount) {}

    public record Winner(int week, UUID player, long earned) {}

    private Db db;
    private int week;
    private final Map<UUID, Long> base = new ConcurrentHashMap<>();

    public RankModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-rank";
    }

    @Override
    protected void start() {
        db = core.db();
        db.migrate("eco_week", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_week_base (week INT NOT NULL, player VARCHAR(36) NOT NULL, base BIGINT NOT NULL, PRIMARY KEY (week, player))",
                "CREATE TABLE IF NOT EXISTS eco_week_winner (week INT NOT NULL PRIMARY KEY, player VARCHAR(36) NOT NULL, earned BIGINT NOT NULL, at BIGINT NOT NULL)")));
        // the week the stored starting points belong to: after a stop over a Monday that week still gets settled
        loadWeek((int) eco.kv().getLong("rank.week", eco.calendar().week()));
        for (Player p : Bukkit.getOnlinePlayers()) baseOf(p.getUniqueId());
        eco.calendar().onWeekly(this);
        eco.onReset(() -> {
            db.execute("DELETE FROM eco_week_base");
            db.execute("DELETE FROM eco_week_winner");
            base.clear();
            week = 1;   // the calendar starts again at day 1
        });
    }

    private void loadWeek(int w) {
        week = w;
        eco.kv().set("rank.week", w);
        base.clear();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT player, base FROM eco_week_base WHERE week = ?",
                rs -> new Object[]{rs.getString(1), rs.getLong(2)}, w))) {
            base.put(UUID.fromString((String) r[0]), (Long) r[1]);
        }
    }

    // ------------------------------------------------------------------------------------------

    public long worth(UUID player) {
        long w = core.economy().balance(player);
        for (Auction a : eco.auction().store().running()) if (player.equals(a.bidder)) w += a.bid;
        w += eco.land().store().spent(player);
        return w;
    }

    /** Net worth at the start of this week; someone new this week starts from what they have now. */
    private long baseOf(UUID player) {
        Long b = base.get(player);
        if (b != null) return b;
        long now = worth(player);
        base.put(player, now);
        db.execute(db.upsert("eco_week_base", List.of("week", "player"), List.of("base")), week, player.toString(), now);
        return now;
    }

    public long earned(UUID player) {
        return worth(player) - baseOf(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        baseOf(e.getPlayer().getUniqueId());
    }

    /** This week's earnings, highest first. */
    public List<Entry> weekly() {
        List<Entry> out = new ArrayList<>();
        for (UUID u : core.economy().balances().keySet()) out.add(new Entry(u, earned(u)));
        out.sort(Comparator.comparingLong(Entry::amount).reversed());
        return out;
    }

    /** Net worth, highest first. */
    public List<Entry> byWorth() {
        List<Entry> out = new ArrayList<>();
        for (UUID u : core.economy().balances().keySet()) out.add(new Entry(u, worth(u)));
        out.sort(Comparator.comparingLong(Entry::amount).reversed());
        return out;
    }

    public Winner lastWinner() {
        List<Winner> w = db.sync(c -> Db.query(c, "SELECT week, player, earned FROM eco_week_winner ORDER BY week DESC LIMIT 1",
                rs -> new Winner(rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getLong(3))));
        return w.isEmpty() ? null : w.getFirst();
    }

    /** /순위 (this week's earnings) or /순위 자산 (net worth). */
    public List<Component> lines(UUID me, boolean worth) {
        List<Entry> all = worth ? byWorth() : weekly();
        List<Component> out = new ArrayList<>();
        out.add(worth ? Lang.msg("rank.worth-header", "count", all.size())
                : Lang.msg("rank.header", "week", week, "left", dev.stsv.economy.event.EventModule.hoursText(
                        eco.calendar().days().next(java.time.DayOfWeek.MONDAY, now()) - now())));
        for (int i = 0; i < Math.min(10, all.size()); i++) {
            Entry e = all.get(i);
            out.add(Lang.mm(e.player().equals(me) ? "rank.line-me" : "rank.line", "rank", i + 1, "player", Players.name(e.player()),
                    "amount", EconomyModule.format(e.amount())));
        }
        if (me != null) {
            for (int i = 10; i < all.size(); i++) {
                if (all.get(i).player().equals(me)) {
                    out.add(Lang.mm("rank.mine", "rank", i + 1, "amount", EconomyModule.format(all.get(i).amount())));
                    break;
                }
            }
        }
        if (!worth) {
            Winner w = lastWinner();
            if (w != null) out.add(Lang.mm("rank.last-winner", "week", w.week(), "player", Players.name(w.player()), "amount", EconomyModule.format(w.earned())));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Monday 05:00: the week is settled
    // ------------------------------------------------------------------------------------------

    @Override
    public void newWeek(int newWeek, long weekStart) {
        if (week >= newWeek) return;
        announce(week);
        loadWeek(newWeek);
        startBases();
    }

    /** /관리자 순위 결산 (testing): settles this week now and starts counting again. */
    public void settleNow() {
        announce(week);
        startBases();
    }

    /** Announces a week's top 5, records the winner and gives them a 기념비 건립권. */
    private void announce(int endedWeek) {
        List<Entry> all = weekly();
        if (all.isEmpty() || all.getFirst().amount() <= 0) {
            Bukkit.broadcast(Lang.msg("rank.weekly-none", "week", endedWeek));
        } else {
            Bukkit.broadcast(Lang.msg("rank.weekly-header", "week", endedWeek));
            for (int i = 0; i < Math.min(5, all.size()); i++) {
                if (all.get(i).amount() <= 0) break;
                Bukkit.broadcast(Lang.mm("rank.line", "rank", i + 1, "player", Players.name(all.get(i).player()),
                        "amount", EconomyModule.format(all.get(i).amount())));
            }
            Entry top = all.getFirst();
            db.execute(db.upsert("eco_week_winner", List.of("week"), List.of("player", "earned", "at")), endedWeek, top.player().toString(), top.amount(), now());
            int rights = eco.vanity().grantMonument(top.player());
            Player winner = Bukkit.getPlayer(top.player());
            if (winner != null) winner.sendMessage(Lang.msg("rank.monument-right", "count", rights));
            weeklyLegendary(top.player(), endedWeek);
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(Title.title(Lang.mm("rank.weekly-title", "week", endedWeek),
                        Lang.mm("rank.weekly-subtitle", "player", Players.name(top.player()), "amount", EconomyModule.format(top.amount())),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(4000), Duration.ofMillis(800))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            }
        }
    }

    /**
     * The week's legendary cosmetic for the winner: config rank.weekly-legendary, then every other legendary
     * cosmetic (one added later reaches servers whose config.yml still has the old list), starting at the
     * week's place in the list and skipping what they already own; gold if they own them all.
     */
    private void weeklyLegendary(UUID winner, int endedWeek) {
        List<String> list = new java.util.ArrayList<>(eco.getConfig().getStringList("rank.weekly-legendary"));
        core.cosmetics().cosmetics().stream()
                .filter(c -> c.cosmetic().rarity() == dev.stsv.core.content.CustomItem.Rarity.LEGENDARY)
                .map(c -> c.key().asString()).sorted()
                .filter(id -> !list.contains(id)).forEach(list::add);
        java.util.Set<String> owned = core.cosmetics().owned(winner);
        for (int k = 0; k < list.size(); k++) {
            String id = list.get(Math.floorMod(endedWeek + k, list.size()));
            var c = core.content().item(id).filter(i -> i.cosmetic() != null);
            if (c.isEmpty() || owned.contains(c.get().key().asString())) continue;
            core.cosmetics().grant(winner, c.get());
            String name = c.get().name() == null ? id : c.get().name();
            Bukkit.broadcast(Lang.msg("rank.weekly-legendary", "player", Players.name(winner), "cosmetic", name));
            Player p = Bukkit.getPlayer(winner);
            if (p != null) core.cosmetics().celebrate(p, c.get());
            return;
        }
        long gold = eco.getConfig().getLong("rank.weekly-legendary-fallback-gold");
        if (gold > 0) {
            core.economy().deposit(winner, gold, "rank_reward");
            Bukkit.broadcast(Lang.msg("rank.weekly-legendary-gold", "player", Players.name(winner), "amount", EconomyModule.format(gold)));
        }
    }

    /** Everyone's net worth now becomes the start of this week's count. */
    private void startBases() {
        base.clear();
        for (UUID u : core.economy().balances().keySet()) {
            long w = worth(u);
            base.put(u, w);
            db.execute(db.upsert("eco_week_base", List.of("week", "player"), List.of("base")), week, u.toString(), w);
        }
    }
}
