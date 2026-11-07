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

/** The player commands of the economy server (all Korean). The admin tools are in {@link AdminCommands}. */
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
            return tab == null ? List.of() : filter(tab.apply(s, a), a.length == 0 ? "" : a[a.length - 1]);
        }
    }

    static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(java.util.Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(java.util.Locale.ROOT).startsWith(p)).toList();
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
        eco.command("상점", new Simple(player((p, a) -> {
            String sub = a.length > 0 ? a[0] : "";
            switch (sub) {
                case "구매" -> eco.shop().openBuy(p);
                case "판매" -> eco.shop().openSell(p);
                case "전부판매" -> eco.shop().sellAll(p, null);
                default -> eco.shop().open(p);
            }
        }), (s, a) -> a.length == 1 ? List.of("구매", "판매", "전부판매") : List.of()));
        eco.command("시세", new Simple((s, a) -> {
            if (s instanceof Player p && a.length == 0) eco.shop().openSell(p);
            else eco.shop().priceLines(s instanceof Player viewer ? viewer.getUniqueId() : null, a.length > 0 ? String.join(" ", a) : null).forEach(s::sendMessage);
        }, (s, a) -> a.length == 1 ? eco.shop().priceWords(a[0]) : List.of()));
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
                    var spec = eco.jobs().spec();
                    var j = spec.jobs().get(r.job);
                    int lv = eco.jobs().level(p.getUniqueId());
                    double xp = r.xp(r.job);
                    p.sendMessage(Lang.msg("job.info", "color", j.color(), "job", j.name(), "level", lv, "cap", spec.maxLevel(),
                            "xp", String.format("%,.0f", xp), "next", lv >= spec.maxLevel() ? Lang.raw("job.max") : String.format("%,.0f", spec.xpFor(lv + 1)),
                            "mastery", String.format("%,.0f", spec.mastery(xp))));
                    p.sendMessage(Lang.msg("job.info-all", "levels", eco.jobs().levelsText(p.getUniqueId())));
                }
                default -> {
                    String id = jobId(eco, a[0]);
                    String refused = id == null ? "job.no-such" : eco.jobs().choose(p, id);
                    if (refused != null) p.sendMessage(Lang.msg(refused, "cost", dev.stsv.core.economy.EconomyModule.format(eco.jobs().spec().changeCost()),
                            "left", dev.stsv.economy.event.EventModule.hoursText(eco.jobs().changeWait(p.getUniqueId()))));
                }
            }
        }), (s, a) -> {
            if (a.length != 1 && !(a.length == 2 && a[0].equals("순위"))) return List.of();
            List<String> out = new java.util.ArrayList<>(a.length == 1 ? List.of("정보", "순위") : List.of());
            for (var j : eco.jobs().spec().jobs().values()) out.add(j.name());
            return out;
        }));
        eco.command("도감", new Simple(player((p, a) -> eco.codex().open(p)), (s, a) -> List.of()));
        eco.command("주식", new Simple(player((p, a) -> {
            var st = eco.stocks();
            if (a.length == 0) {
                st.open(p);
                return;
            }
            switch (a[0]) {
                case "뉴스" -> st.showNews(p);
                case "알림" -> st.toggleAlerts(p);
                case "사기", "매수" -> {
                    if (a.length < 3) p.sendMessage(Lang.msg("stock.usage"));
                    else st.tradeByCommand(p, a[1], a[2], true);
                }
                case "팔기", "매도" -> {
                    if (a.length < 3) p.sendMessage(Lang.msg("stock.usage"));
                    else st.tradeByCommand(p, a[1], a[2], false);
                }
                default -> {
                    var c = st.find(String.join("", a));
                    if (c == null) p.sendMessage(Lang.msg("stock.no-such"));
                    else st.quote(p, c);
                }
            }
        }), (s, a) -> {
            if (a.length == 1) {
                List<String> out = new java.util.ArrayList<>(List.of("뉴스", "알림", "사기", "팔기"));
                for (var c : eco.stocks().listed()) out.add(c.name);
                return out;
            }
            if (a.length == 2 && List.of("사기", "팔기", "매수", "매도").contains(a[0])) return eco.stocks().listed().stream().map(c -> c.name).toList();
            if (a.length == 3) return a[0].equals("팔기") || a[0].equals("매도") ? List.of("1", "10", "100", "전부") : List.of("1", "10", "100");
            return List.of();
        }));
        eco.command("땅", new Simple(player((p, a) -> {
            String sub = a.length == 0 ? "정보" : a[0];
            boolean confirm = a.length > 1 && a[a.length - 1].equals("확인");
            switch (sub) {
                case "구매" -> eco.land().buy(p, confirm);
                case "포기" -> eco.land().abandon(p);
                case "보기" -> eco.land().show(p);
                case "초대" -> {
                    if (a.length > 1) eco.land().invite(p, a[1]);
                    else p.sendMessage(Lang.msg("land.usage"));
                }
                case "수락" -> eco.land().accept(p);
                case "거절" -> eco.land().decline(p);
                case "추방" -> {
                    if (a.length > 1) eco.land().kick(p, a[1]);
                    else p.sendMessage(Lang.msg("land.usage"));
                }
                case "나가기" -> eco.land().leave(p);
                case "양도" -> {
                    if (a.length > 1) eco.land().transfer(p, a[1]);
                    else p.sendMessage(Lang.msg("land.usage"));
                }
                case "금고" -> {
                    if (a.length >= 3 && a[1].equals("입금")) eco.land().deposit(p, amount(a[2]));
                    else if (a.length >= 3 && a[1].equals("출금")) eco.land().withdraw(p, amount(a[2]));
                    else eco.land().vaultInfo(p);
                }
                case "해체" -> eco.land().disband(p, confirm);
                case "도움말" -> p.sendMessage(Lang.msg("land.usage"));
                default -> eco.land().info(p);
            }
        }), (s, a) -> {
            if (a.length == 1) return List.of("정보", "구매", "포기", "보기", "초대", "수락", "거절", "추방", "나가기", "양도", "금고", "해체", "도움말");
            if (a.length == 2 && (a[0].equals("초대") || a[0].equals("추방") || a[0].equals("양도"))) {
                return org.bukkit.Bukkit.getOnlinePlayers().stream().map(x -> x.getName()).toList();
            }
            if (a.length == 2 && a[0].equals("금고")) return List.of("입금", "출금");
            if (a.length == 2 && (a[0].equals("구매") || a[0].equals("해체"))) return List.of("확인");
            return List.of();
        }));
        eco.command("의뢰", new Simple(player((p, a) -> eco.requests().open(p)), null));
        eco.command("미션", new Simple(player((p, a) -> eco.missions().open(p)), null));
        eco.command("순위", new Simple((s, a) -> eco.rank().lines(s instanceof org.bukkit.entity.Player p ? p.getUniqueId() : null,
                a.length > 0 && a[0].equals("자산")).forEach(s::sendMessage), (s, a) -> a.length == 1 ? List.of("자산") : List.of()));
        eco.command("기념비", new Simple(player((p, a) -> {
            if (a.length == 0) {
                int rights = eco.vanity().monumentRights(p.getUniqueId());
                p.sendMessage(rights > 0 ? Lang.msg("vanity.monument-usage", "max", eco.vanity().maxLength(), "rights", rights) : Lang.msg("vanity.monument-no-right"));
                return;
            }
            eco.vanity().build(p, String.join(" ", a));
        }), null));
        eco.command("납품", new Simple(player((p, a) -> eco.shop().deliver(p)), null));
        eco.command("기부", new Simple(player((p, a) -> eco.vanity().donate(p, a.length > 0 ? amount(a[0]) : 0)), null));
    }
}
