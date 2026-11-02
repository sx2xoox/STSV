package dev.stsv.economy.rank;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.auction.Auction;
import dev.stsv.economy.job.JobStore;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 순위: net worth (cash + money held in bids + money spent on land), the 21:00 daily top 5, the weekly
 * announcements and the finale (net worth into the hall of fame, titles for the top 3, period end).
 */
public final class RankModule extends EcoModule {

    public record Entry(UUID player, long worth) {}

    public RankModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-rank";
    }

    @Override
    protected void start() {
        eco.season().daily("rank", eco.season().spec().dailyRank(), this::evening);
        eco.season().onAction("finale", e -> finale());
    }

    public long worth(UUID player) {
        long w = core.economy().balance(player);
        for (Auction a : eco.auction().store().running()) if (player.equals(a.bidder)) w += a.bid;
        w += eco.land().store().account(player).spent;
        return w;
    }

    public List<Entry> ranking() {
        List<Entry> out = new ArrayList<>();
        for (UUID u : core.economy().balances().keySet()) out.add(new Entry(u, worth(u)));
        out.sort(Comparator.comparingLong(Entry::worth).reversed());
        return out;
    }

    public List<Component> lines(UUID me) {
        List<Entry> all = ranking();
        List<Component> out = new ArrayList<>();
        out.add(Lang.msg("rank.header", "count", all.size()));
        for (int i = 0; i < Math.min(10, all.size()); i++) {
            Entry e = all.get(i);
            out.add(Lang.mm(e.player().equals(me) ? "rank.line-me" : "rank.line", "rank", i + 1, "player", Players.name(e.player()),
                    "worth", EconomyModule.format(e.worth())));
        }
        if (me != null) {
            for (int i = 10; i < all.size(); i++) {
                if (all.get(i).player().equals(me)) {
                    out.add(Lang.mm("rank.mine", "rank", i + 1, "worth", EconomyModule.format(all.get(i).worth())));
                    break;
                }
            }
        }
        return out;
    }

    private void evening() {
        if (!eco.season().running()) return;
        int day = eco.season().day();
        boolean weekly = eco.season().spec().weeklyRankDays().contains(day);
        List<Entry> all = ranking();
        eco.season().broadcast(Lang.msg(weekly ? "rank.weekly-header" : "rank.daily-header", "day", day, "week", eco.season().week()));
        for (int i = 0; i < Math.min(5, all.size()); i++) {
            eco.season().broadcast(Lang.mm("rank.line", "rank", i + 1, "player", Players.name(all.get(i).player()),
                    "worth", EconomyModule.format(all.get(i).worth())));
        }
        if (weekly && !all.isEmpty()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(Title.title(Lang.mm("rank.weekly-title", "week", eco.season().week()),
                        Lang.mm("rank.weekly-subtitle", "player", Players.name(all.getFirst().player()), "worth", EconomyModule.format(all.getFirst().worth())),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(4000), Duration.ofMillis(800))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            }
        }
    }

    /** The last day: net worth and mastery go into the records, titles for the top 3, the period ends. */
    public void finale() {
        if (!eco.season().running()) return;
        List<Entry> all = ranking();
        for (Entry e : all) core.profiles().add(e.player(), "net_worth", e.worth());
        for (JobStore.Row r : eco.jobs().store().ranking(null, Integer.MAX_VALUE)) core.profiles().add(r.player, "job_mastery", Math.round(r.mastery));
        for (String type : eco.season().spec().events().keySet()) eco.season().endEvent(type);
        String[] titles = {"economy:tycoon_1", "economy:tycoon_2", "economy:tycoon_3"};
        for (int i = 0; i < Math.min(3, all.size()); i++) core.profiles().grantTitle(all.get(i).player(), titles[i]);
        eco.season().broadcast(Lang.msg("rank.finale-header"));
        for (int i = 0; i < Math.min(5, all.size()); i++) {
            eco.season().broadcast(Lang.mm("rank.line", "rank", i + 1, "player", Players.name(all.get(i).player()),
                    "worth", EconomyModule.format(all.get(i).worth())));
        }
        if (!all.isEmpty()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(Title.title(Lang.mm("rank.finale-title"), Lang.mm("rank.finale-subtitle", "player", Players.name(all.getFirst().player())),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(6000), Duration.ofMillis(1000))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.9f);
            }
        }
        Map<String, ?> hall = core.profiles().endPeriod(List.of("net_worth", "job_mastery"));
        eco.getLogger().info("시즌 종료: 명예의 전당 " + hall.size() + "개 부문");
    }
}
