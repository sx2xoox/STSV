package dev.stsv.economy;

import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** The player commands of the economy server (all Korean) and the /경제관리 admin root. */
final class Commands {

    private Commands() {}

    /** A command from two lambdas. */
    record Simple(BiConsumer<CommandSender, String[]> run, BiFunction<CommandSender, String[], List<String>> tab) implements TabExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) {
            try {
                run.accept(s, a);
            } catch (RuntimeException e) {
                s.sendMessage(Text.msg("<red>오류: " + e.getMessage()));
                EcoPlugin.get().getLogger().log(java.util.logging.Level.WARNING, "/" + l + " " + String.join(" ", a), e);
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) {
            return tab == null ? List.of() : AdminCommand.filter(tab.apply(s, a), a.length == 0 ? "" : a[a.length - 1]);
        }
    }

    /** Runs only for players. */
    static BiConsumer<CommandSender, String[]> player(BiConsumer<Player, String[]> body) {
        return (s, a) -> {
            if (s instanceof Player p) body.accept(p, a);
            else s.sendMessage(Lang.msg("common.players-only"));
        };
    }

    /** "1,000" -> 1000; bad numbers -> -1. */
    static long amount(String s) {
        try {
            return Long.parseLong(s.replace(",", "").replace("_", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A job by its Korean name or id. */
    static String jobId(EcoPlugin eco, String word) {
        for (var j : eco.jobs().spec().jobs().values()) if (j.name().equals(word) || j.id().equals(word)) return j.id();
        return null;
    }

    static void register(EcoPlugin eco) {
        eco.command("경제관리", eco.admin());
        eco.command("상점", new Simple(player((p, a) -> {
            if (a.length > 0 && a[0].equals("전부판매")) eco.shop().sellAll(p);
            else eco.shop().open(p);
        }), (s, a) -> a.length == 1 ? List.of("전부판매") : List.of()));
        eco.command("시세", new Simple((s, a) -> {
            if (s instanceof Player p && a.length == 0) eco.shop().openPrices(p);
            else eco.shop().priceLines(a.length > 0 ? a[0] : null).forEach(s::sendMessage);
        }, null));
        eco.command("우편함", new Simple(player((p, a) -> eco.mail().open(p)), null));
        eco.command("거래소", new Simple(player((p, a) -> {
            if (a.length == 0) eco.market().open(p, null, false);
            else if (a[0].equals("등록")) {
                if (a.length < 2) p.sendMessage(Lang.msg("market.usage"));
                else eco.market().list(p, amount(a[1]));
            } else if (a[0].equals("내목록")) eco.market().open(p, null, true);
            else if (a[0].equals("검색") && a.length > 1) eco.market().open(p, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)), false);
            else p.sendMessage(Lang.msg("market.help"));
        }), (s, a) -> a.length == 1 ? List.of("등록", "내목록", "검색") : List.of()));
        eco.command("경매", new Simple(player((p, a) -> {
            if (a.length == 0) {
                eco.auction().open(p);
                return;
            }
            switch (a[0]) {
                case "등록" -> {
                    if (a.length < 2) p.sendMessage(Lang.msg("auction.usage"));
                    else eco.auction().startPlayer(p, amount(a[1]));
                }
                case "입찰" -> {
                    if (a.length < 3) {
                        p.sendMessage(Lang.msg("auction.usage"));
                        return;
                    }
                    long id = amount(a[1]);
                    String refused = eco.auction().bid(p, id, amount(a[2]));
                    var au = eco.auction().store().get(id);
                    if (refused != null) p.sendMessage(Lang.msg(refused, "min", au == null ? "-" : dev.stsv.core.economy.EconomyModule.format(au.minNext(eco.rules().minRaise())),
                            "limit", eco.rules().rareWeeklyWins()));
                }
                case "취소" -> {
                    String refused = a.length < 2 ? "auction.usage" : eco.auction().cancel(p, amount(a[1]));
                    if (refused != null) p.sendMessage(Lang.msg(refused));
                }
                default -> p.sendMessage(Lang.msg("auction.usage"));
            }
        }), (s, a) -> a.length == 1 ? List.of("등록", "입찰", "취소") : a.length == 2 && !a[0].equals("등록")
                ? eco.auction().store().running().stream().map(x -> String.valueOf(x.id)).toList() : List.of()));
        eco.command("거래", new Simple(player((p, a) -> {
            if (a.length == 0) {
                p.sendMessage(Lang.msg("trade.usage"));
                return;
            }
            switch (a[0]) {
                case "수락" -> eco.trade().accept(p);
                case "거절" -> eco.trade().deny(p);
                default -> {
                    org.bukkit.entity.Player to = org.bukkit.Bukkit.getPlayerExact(a[0]);
                    if (to == null) p.sendMessage(Lang.msg("trade.not-online"));
                    else eco.trade().request(p, to);
                }
            }
        }), (s, a) -> {
            List<String> out = new java.util.ArrayList<>(List.of("수락", "거절"));
            for (var pl : org.bukkit.Bukkit.getOnlinePlayers()) out.add(pl.getName());
            return a.length == 1 ? out : List.of();
        }));
        eco.command("노점", new Simple(player((p, a) -> {
            String sub = a.length == 0 ? "정보" : a[0];
            switch (sub) {
                case "임대" -> {
                    if (a.length < 2) p.sendMessage(Lang.msg("stall.usage"));
                    else eco.stalls().rent(p, (int) amount(a[1]));
                }
                case "반납" -> eco.stalls().release(p);
                case "가격" -> {
                    if (a.length < 2) p.sendMessage(Lang.msg("stall.usage"));
                    else eco.stalls().price(p, a[1].equals("해제") ? 0 : amount(a[1]));
                }
                default -> {
                    var mine = eco.stalls().store().of(p.getUniqueId());
                    p.sendMessage(Lang.msg("stall.usage"));
                    long free = eco.stalls().store().all().values().stream().filter(x -> x.owner == null).count();
                    p.sendMessage(Lang.msg(mine == null ? "stall.status-none" : "stall.status", "number", mine == null ? 0 : mine.number,
                            "free", free, "total", eco.stalls().store().all().size(), "rent", dev.stsv.core.economy.EconomyModule.format(eco.rules().stallRent())));
                }
            }
        }), (s, a) -> a.length == 1 ? List.of("임대", "반납", "가격", "정보") : a.length == 2 && a[0].equals("가격") ? List.of("해제") : List.of()));
        eco.command("직업", new Simple(player((p, a) -> {
            if (a.length == 0) {
                eco.jobs().open(p);
                return;
            }
            switch (a[0]) {
                case "순위" -> eco.jobs().rankingLines(a.length > 1 ? jobId(eco, a[1]) : null).forEach(p::sendMessage);
                case "정보" -> {
                    var r = eco.jobs().store().get(p.getUniqueId());
                    if (r == null) {
                        p.sendMessage(Lang.msg("job.no-job"));
                        return;
                    }
                    var j = eco.jobs().spec().jobs().get(r.job);
                    int lv = eco.jobs().level(p.getUniqueId());
                    p.sendMessage(Lang.msg("job.info", "color", j.color(), "job", j.name(), "level", lv, "cap", eco.season().levelCap(),
                            "xp", String.format("%,.0f", r.xp), "next", lv >= eco.jobs().spec().maxLevel() ? "-" : String.format("%,.0f", eco.jobs().spec().xpFor(lv + 1)),
                            "mastery", String.format("%,.0f", r.mastery)));
                }
                default -> {
                    String id = jobId(eco, a[0]);
                    String refused = id == null ? "job.no-such" : eco.jobs().choose(p, id);
                    if (refused != null) p.sendMessage(Lang.msg(refused, "cost", dev.stsv.core.economy.EconomyModule.format(eco.jobs().spec().changeCost()), "left", "-"));
                }
            }
        }), (s, a) -> {
            if (a.length != 1 && !(a.length == 2 && a[0].equals("순위"))) return List.of();
            List<String> out = new java.util.ArrayList<>(a.length == 1 ? List.of("정보", "순위") : List.of());
            for (var j : eco.jobs().spec().jobs().values()) out.add(j.name());
            return out;
        }));
        eco.command("땅", new Simple(player((p, a) -> {
            String sub = a.length == 0 ? "정보" : a[0];
            switch (sub) {
                case "1" -> eco.land().corner(p, 0);
                case "2" -> eco.land().corner(p, 1);
                case "생성" -> eco.land().create(p, a.length > 1 ? (int) amount(a[1]) : -1, false);
                case "확장" -> eco.land().expand(p, a.length > 1 ? (int) amount(a[1]) : 0);
                case "삭제" -> eco.land().delete(p);
                case "구매" -> eco.land().buy(p, a.length > 1 ? amount(a[1]) : 0);
                case "신뢰" -> {
                    if (a.length > 1) eco.land().trust(p, a[1], true);
                    else p.sendMessage(Lang.msg("land.usage"));
                }
                case "불신" -> {
                    if (a.length > 1) eco.land().trust(p, a[1], false);
                    else p.sendMessage(Lang.msg("land.usage"));
                }
                case "보기" -> eco.land().show(p);
                case "도움말" -> p.sendMessage(Lang.msg("land.usage"));
                default -> eco.land().info(p);
            }
        }), (s, a) -> {
            if (a.length == 1) return List.of("정보", "1", "2", "생성", "확장", "삭제", "구매", "신뢰", "불신", "보기", "도움말");
            if (a.length == 2 && (a[0].equals("신뢰") || a[0].equals("불신"))) return org.bukkit.Bukkit.getOnlinePlayers().stream().map(x -> x.getName()).toList();
            return List.of();
        }));
        eco.command("의뢰", new Simple(player((p, a) -> eco.requests().open(p)), null));
        eco.command("순위", new Simple((s, a) -> eco.rank().lines(s instanceof org.bukkit.entity.Player p ? p.getUniqueId() : null).forEach(s::sendMessage), null));
        eco.command("기념비", new Simple(player((p, a) -> {
            if (a.length == 0) {
                p.sendMessage(Lang.msg("vanity.monument-usage", "max", 30, "price", dev.stsv.core.economy.EconomyModule.format(eco.vanity().nextPrice())));
                return;
            }
            eco.vanity().build(p, String.join(" ", a));
        }), null));
        eco.command("기부", new Simple(player((p, a) -> eco.vanity().donate(p, a.length > 0 ? amount(a[0]) : 0)), null));
        AdminCommands.register(eco);
    }
}
