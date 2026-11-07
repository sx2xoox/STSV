package dev.stsv.economy;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.command.AdminRegistry;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.event.ActiveEvent;
import dev.stsv.economy.event.EventModule;
import dev.stsv.economy.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The economy server's admin tools, as sub commands of the core's /관리자 (날짜, 사건, 위치, 경매,
 * 직업, 땅, 수요고시, 순위, 기념비), its part of /관리자 보고서 and /관리자 초기화 경제서버.
 */
final class AdminCommands {

    private AdminCommands() {}

    private static void add(String name, String usage, String help, AdminRegistry.Handler h, AdminRegistry.Completer c) {
        STSVPlugin.get().admin().add(EcoPlugin.ADMIN_OWNER, name, usage, help, h, c);
    }

    static void register(EcoPlugin eco) {
        AdminRegistry admin = STSVPlugin.get().admin();
        EventModule events = eco.events();

        add("날짜", "[다음날]", "오늘이 며칠째·몇 주째인지 / 테스트용으로 하루 넘기기 (05시 처리를 지금 실행)", (s, args) -> {
            if (args.length > 0 && args[0].equals("다음날")) eco.calendar().skipDay();
            var d = eco.calendar().days();
            long now = System.currentTimeMillis();
            String first = d.epoch() <= 0 ? "-" : DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(d.zone()).format(java.time.Instant.ofEpochMilli(d.epoch()));
            s.sendMessage(Text.msg("<white>" + d.day(now) + "일차 · " + d.week(now) + "주차 <gray>(" + d.date(now) + " " + Lang.raw("weekday." + d.weekday(now))
                    + ", 첫날 " + first + ", 다음 05시까지 " + EventModule.hoursText(d.nextDayStart(now) - now) + ")"));
        }, (s, args) -> List.of("다음날"));

        add("사건", "시작 <종류> [시간] [키=값...] | 종료 <종류> | 다음 [분] | 목록", "사건 바로 시작/종료, 다음 사건 시각 보기/바꾸기 (테스트)", (s, args) -> {
            String sub = args.length > 0 ? args[0] : "목록";
            switch (sub) {
                case "시작" -> {
                    if (args.length < 2 || !events.specs().containsKey(args[1])) {
                        s.sendMessage(Text.msg("<red>종류: " + String.join(", ", events.specs().keySet())));
                        return;
                    }
                    var es = events.specs().get(args[1]);
                    double hours = es.hours();
                    Map<String, String> params = new LinkedHashMap<>();
                    for (int i = 2; i < args.length; i++) {
                        int eq = args[i].indexOf('=');
                        if (eq > 0) params.put(args[i].substring(0, eq), args[i].substring(eq + 1));
                        else hours = Double.parseDouble(args[i]);
                    }
                    ActiveEvent e = events.start(args[1], hours, params);
                    s.sendMessage(Text.msg("<green>시작: " + es.name() + " <gray>" + e.params()));
                }
                case "종료" -> s.sendMessage(Text.msg("<gray>종료: " + (args.length > 1 ? events.end(args[1]) : 0) + "건"));
                case "다음" -> {
                    if (args.length > 1) events.scheduleIn(Double.parseDouble(args[1]));
                    long next = events.next();
                    s.sendMessage(Text.msg(next <= 0 ? "<gray>사건이 진행 중입니다. 끝나고 " + events.chain().gapHours() + "시간 뒤에 다음 사건."
                            : "<gray>다음 사건까지 <white>" + EventModule.hoursText(next - System.currentTimeMillis())
                            + "</white> <dark_gray>(" + java.time.Instant.ofEpochMilli(next).atZone(java.time.ZoneId.of(eco.getConfig().getString("time.zone")))
                            .toLocalTime().withNano(0) + ")"));
                }
                default -> {
                    s.sendMessage(Text.msg("<gray>진행 중인 사건 " + events.active().size() + "건 <dark_gray>(종류: "
                            + String.join(", ", events.specs().keySet()) + ")"));
                    for (ActiveEvent e : events.active()) {
                        s.sendMessage(Text.mm(" <white>" + events.name(e.type()) + " <gray>남은 시간 "
                                + EventModule.hoursText(e.ends() - System.currentTimeMillis()) + " " + e.params()));
                    }
                }
            }
        }, (s, args) -> args.length == 1 ? List.of("시작", "종료", "다음", "목록")
                : args.length == 2 && (args[0].equals("시작") || args[0].equals("종료")) ? new ArrayList<>(events.specs().keySet()) : List.of());

        add("위치", "<시세판|주식판|월드보스|기념비N>", "지금 서 있는 곳을 저장 (시세판, 주식판, 월드 보스, 기념비 자리)", (s, args) -> {
            if (!(s instanceof Player p) || args.length < 1) {
                s.sendMessage(Text.msg("<red>/관리자 위치 <시세판|월드보스|기념비1...>"));
                return;
            }
            String key = switch (args[0]) {
                case "시세판" -> "price-board";
                case "주식판" -> "stock-board";
                case "월드보스" -> "world-boss";
                default -> args[0].startsWith("기념비") ? "monument-" + args[0].substring(3) : args[0];
            };
            eco.setLocation(key, p.getLocation());
            s.sendMessage(Text.msg("<green>저장: " + key));
            if (key.equals("price-board")) eco.shop().updateBoard();
            if (key.equals("stock-board")) eco.stocks().updateBoard();
        }, (s, args) -> List.of("시세판", "주식판", "월드보스", "기념비1", "기념비2", "기념비3"));

        add("주식", "상장 <이름> <산업> <발행수> <시작가> | 상장폐지 <회사> | 가격 <회사> <가격> | 뉴스 <회사> <+/-%> [내용] | 정지 <회사> | 재개 <회사> | 성격 <회사> <변동성|민감도|배당> <값> | 움직이기 [횟수]",
                "주식 관리: 회사 상장·상장폐지(보유자에게 현재가로 정산), 주가 지정, 회사 뉴스, 거래 정지·재개, 성격 바꾸기, 주가를 N번(기본 6번 = 1시간어치) 움직이기 (테스트)", (s, args) -> {
            var st = eco.stocks();
            String sub = args.length > 0 ? args[0] : "";
            try {
                switch (sub) {
                    case "상장" -> {
                        if (args.length < 5) {
                            s.sendMessage(Text.msg("<red>/관리자 주식 상장 <이름> <산업> <발행수> <시작가>"));
                            return;
                        }
                        String err = st.list(args[1], args[2], Long.parseLong(args[3]), Double.parseDouble(args[4]));
                        s.sendMessage(Text.msg(err == null ? "<green>상장: " + args[1] : "<red>" + err));
                    }
                    case "움직이기" -> {
                        int n = args.length > 1 ? Math.max(1, Math.min(144, Integer.parseInt(args[1]))) : 6;
                        var movers = st.forceTicks(n);
                        s.sendMessage(Text.msg("<green>주가를 " + n + "번 움직였습니다 <gray>(10분씩, " + EventModule.hoursText(n * 600_000L) + "어치)"));
                        for (String line : movers) s.sendMessage(Text.mm(" <gray>" + line));
                    }
                    default -> {
                        var c = args.length > 1 ? st.find(args[1]) : null;
                        if (c == null) {
                            s.sendMessage(Text.msg("<red>없는 회사. /관리자 주식 " + (sub.isEmpty() ? "<상장|상장폐지|가격|뉴스|정지|재개|성격|움직이기>" : sub + " <회사> ...")));
                            return;
                        }
                        switch (sub) {
                            case "상장폐지" -> st.delist(c);
                            case "가격" -> st.setPrice(c, Double.parseDouble(args[2]));
                            case "뉴스" -> st.news(c, Double.parseDouble(args[2].replace("%", "")),
                                    args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : null);
                            case "정지" -> st.halt(c, true);
                            case "재개" -> st.halt(c, false);
                            case "성격" -> {
                                String f = switch (args[2]) {
                                    case "변동성" -> "volatility";
                                    case "민감도" -> "sensitivity";
                                    case "배당" -> "dividend";
                                    default -> args[2];
                                };
                                if (!st.personality(c, f, Double.parseDouble(args[3]))) {
                                    s.sendMessage(Text.msg("<red>변동성 | 민감도 | 배당"));
                                    return;
                                }
                            }
                            default -> {
                                s.sendMessage(Text.msg("<red>/관리자 주식 <상장|상장폐지|가격|뉴스|정지|재개|성격|움직이기>"));
                                return;
                            }
                        }
                        s.sendMessage(Text.msg("<green>" + c.name + ": " + sub));
                    }
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                s.sendMessage(Text.msg("<red>숫자나 인자가 잘못됐습니다."));
            }
        }, (s, args) -> {
            if (args.length == 1) return List.of("상장", "상장폐지", "가격", "뉴스", "정지", "재개", "성격", "움직이기");
            if (args.length == 2 && !args[0].equals("상장")) return eco.stocks().listed().stream().map(c -> c.name).toList();
            if (args.length == 3 && args[0].equals("상장")) return eco.stocks().spec().industries().values().stream().map(i -> i.name()).toList();
            if (args.length == 3 && args[0].equals("성격")) return List.of("변동성", "민감도", "배당");
            return List.of();
        });

        add("경매", "시작 <시작가> [분] | 종료 <번호> | 취소 <번호>", "서버 경매 (손에 든 물건, 주간 낙찰 제한 적용) / 바로 끝내기", (s, args) -> {
            String sub = args.length > 0 ? args[0] : "";
            switch (sub) {
                case "시작" -> {
                    if (!(s instanceof Player p) || args.length < 2 || p.getInventory().getItemInMainHand().isEmpty()) {
                        s.sendMessage(Text.msg("<red>손에 물건을 들고 /관리자 경매 시작 <시작가> [분]"));
                        return;
                    }
                    var item = p.getInventory().getItemInMainHand().clone();
                    p.getInventory().setItemInMainHand(null);
                    var au = eco.auction().startServer(p.getUniqueId(), item, Long.parseLong(args[1]),
                            args.length > 2 ? Double.parseDouble(args[2]) : eco.rules().auctionMinutes());
                    s.sendMessage(Text.msg("<green>서버 경매 #" + au.id + " 시작"));
                }
                case "종료" -> s.sendMessage(Text.msg(args.length > 1 && eco.auction().endNow(Long.parseLong(args[1])) ? "<green>종료" : "<red>없는 경매"));
                case "취소" -> {
                    if (!(s instanceof Player p) || args.length < 2) return;
                    String r = eco.auction().cancel(p, Long.parseLong(args[1]));
                    s.sendMessage(r == null ? Text.msg("<green>취소") : Lang.msg(r));
                }
                default -> s.sendMessage(Text.msg("<red>/관리자 경매 시작 <시작가> [분] | 종료 <번호> | 취소 <번호>"));
            }
        }, (s, args) -> args.length == 1 ? List.of("시작", "종료", "취소") : List.of());

        add("낚시", "등급 <흔함|보통|희귀|전설|진주> [플레이어] [횟수] | 물고기 <이름> [플레이어] [횟수] | 취소 [플레이어] | 확인 [플레이어]",
                "다음 입질을 그 등급·그 물고기로 (테스트, 보물·쓰레기 입질도 바꿈. 물고기는 사는 곳·때와 상관없이)", (s, args) -> {
            var fishing = eco.fishing();
            String sub = args.length > 0 ? args[0] : "확인";
            boolean needsArg = sub.equals("등급") || sub.equals("물고기");
            int at = needsArg ? 2 : 1;
            Player target = args.length > at ? Bukkit.getPlayerExact(args[at]) : s instanceof Player p ? p : null;
            if (target == null) {
                s.sendMessage(Text.msg("<red>" + (args.length > at ? "접속 중이 아닌 플레이어: " + args[at] : "플레이어를 적어 주세요")));
                return;
            }
            int times = 1;
            if (args.length > at + 1) {
                try {
                    times = Math.max(1, Integer.parseInt(args[at + 1]));
                } catch (NumberFormatException ex) {
                    s.sendMessage(Text.msg("<red>횟수는 숫자: " + args[at + 1]));
                    return;
                }
            }
            switch (sub) {
                case "등급" -> {
                    String rarity = args.length > 1 ? rarityId(fishing.spec(), args[1]) : null;
                    if (rarity == null) {
                        s.sendMessage(Text.msg("<red>등급: 흔함, 보통, 희귀, 전설, 진주"));
                        return;
                    }
                    fishing.force(target.getUniqueId(), rarity, null, times);
                    s.sendMessage(Text.msg("<green>" + target.getName() + " 다음 입질 " + times + "번: " + args[1]));
                }
                case "물고기" -> {
                    var fish = args.length > 1 ? speciesOf(fishing.spec(), args[1]) : null;
                    if (fish == null) {
                        s.sendMessage(Text.msg("<red>없는 물고기: " + (args.length > 1 ? args[1] : "") + " <gray>(이름 또는 id, 탭 완성)"));
                        return;
                    }
                    fishing.force(target.getUniqueId(), null, fish.id(), times);
                    s.sendMessage(Text.msg("<green>" + target.getName() + " 다음 입질 " + times + "번: " + fish.name()
                            + " <gray>(" + fishing.spec().rarities().get(fish.rarity()).name() + ")"));
                }
                case "취소" -> s.sendMessage(Text.msg(fishing.unforce(target.getUniqueId()) ? "<green>취소" : "<gray>정해 둔 입질 없음"));
                default -> {
                    var f = fishing.forced(target.getUniqueId());
                    String what = "";
                    if (f != null) {
                        // the species or rarity may be gone from fish.yml since (a reload): show its id, not an error
                        var sp = f.species() == null ? null : fishing.spec().species().get(f.species());
                        var ra = f.rarity() == null ? null : fishing.spec().rarities().get(f.rarity());
                        what = f.species() != null ? (sp == null ? f.species() + " (없음)" : sp.name())
                                : "pearl".equals(f.rarity()) ? "진주" : ra == null ? f.rarity() + " (없음)" : ra.name();
                    }
                    s.sendMessage(Text.msg(f == null ? "<gray>" + target.getName() + ": 정해 둔 입질 없음"
                            : "<white>" + target.getName() + ": 다음 " + f.left() + "번 " + what));
                }
            }
        }, (s, args) -> {
            if (args.length == 1) return List.of("등급", "물고기", "취소", "확인");
            if (args.length == 2 && args[0].equals("등급")) {
                List<String> out = new ArrayList<>();
                eco.fishing().spec().rarities().values().forEach(r -> out.add(r.name()));
                out.add("진주");
                return out;
            }
            if (args.length == 2 && args[0].equals("물고기")) {
                String a = args[1];
                return eco.fishing().spec().species().values().stream().map(f -> f.name().replace(" ", "_"))
                        .filter(n -> n.startsWith(a)).limit(40).toList();
            }
            int at = args[0].equals("등급") || args[0].equals("물고기") ? 3 : 2;
            if (args.length == at) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return List.of();
        });

        add("직업", "<플레이어> <직업> | 경험치 <플레이어> <값> | 레벨 <플레이어> <1~30>", "직업 지정(비용·대기 없음) / 지금 직업의 경험치·레벨 설정 (테스트)", (s, args) -> {
            if (args.length >= 3 && args[0].equals("레벨")) {
                Players.find(args[1], found -> {
                    if (found.isEmpty()) s.sendMessage(Text.msg("<red>없는 플레이어"));
                    else {
                        eco.jobs().setXp(found.get(), eco.jobs().spec().xpFor(Integer.parseInt(args[2])));
                        s.sendMessage(Text.msg("<green>" + args[1] + " Lv " + eco.jobs().level(found.get())));
                    }
                });
                return;
            }
            if (args.length >= 3 && args[0].equals("경험치")) {
                Players.find(args[1], found -> {
                    if (found.isEmpty()) s.sendMessage(Text.msg("<red>없는 플레이어"));
                    else {
                        eco.jobs().setXp(found.get(), Double.parseDouble(args[2]));
                        s.sendMessage(Text.msg("<green>" + args[1] + " 경험치 " + args[2] + " (Lv " + eco.jobs().level(found.get()) + ")"));
                    }
                });
                return;
            }
            if (args.length < 2) {
                s.sendMessage(Text.msg("<red>/관리자 직업 <플레이어> <직업> | 경험치 <플레이어> <값> | 레벨 <플레이어> <1~30>"));
                return;
            }
            String id = Commands.jobId(eco, args[1]);
            if (id == null) {
                s.sendMessage(Text.msg("<red>없는 직업"));
                return;
            }
            Players.find(args[0], found -> {
                if (found.isEmpty()) s.sendMessage(Text.msg("<red>없는 플레이어"));
                else {
                    eco.jobs().store().choose(found.get(), id, System.currentTimeMillis() - 30L * 86_400_000L);
                    s.sendMessage(Text.msg("<green>" + args[0] + " → " + args[1]));
                }
            });
        }, (s, args) -> {
            if (args.length == 1) {
                List<String> out = new ArrayList<>(List.of("경험치", "레벨"));
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                return out;
            }
            if (args.length == 2 && (args[0].equals("경험치") || args[0].equals("레벨"))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            if (args.length == 2) return eco.jobs().spec().jobs().values().stream().map(j -> j.name()).toList();
            return List.of();
        });

        add("땅", "서버 [반지름] | 해제 [반지름] | 정보", "운영자 땅(스폰 마을): 둘레 청크를 서버 땅으로 / 둘레 청크를 누구 땅이든 해제 / 서 있는 땅 정보. 반지름은 청크 수 (0 = 지금 청크)", (s, args) -> {
            if (!(s instanceof Player p) || args.length < 1) {
                s.sendMessage(Text.msg("<red>/관리자 땅 서버 [반지름] | 해제 [반지름] | 정보"));
                return;
            }
            int radius = args.length > 1 ? Math.clamp(Integer.parseInt(args[1]), 0, 32) : 0;
            switch (args[0]) {
                case "서버" -> s.sendMessage(Text.msg("<green>서버 땅으로: 청크 " + eco.land().claimServer(p, radius) + "개"));
                case "해제" -> s.sendMessage(Text.msg("<yellow>해제: 청크 " + eco.land().releaseAround(p, radius) + "개"));
                default -> eco.land().adminInfo(p);
            }
        }, (s, args) -> args.length == 1 ? List.of("서버", "해제", "정보") : List.of());

        add("수요고시", "", "이번 주 수요 고시를 지금 새로 뽑기", (s, args) -> {
            var picked = eco.shop().announceNotice(System.currentTimeMillis());
            s.sendMessage(Text.msg("<green>수요 고시: " + picked));
        }, null);

        add("순위", "결산", "테스트용: 이번 주 순위를 지금 결산 (1위에게 기념비 건립권, 새 주 기준점)", (s, args) -> {
            if (args.length < 1 || !args[0].equals("결산")) {
                s.sendMessage(Text.msg("<yellow>이번 주 순위를 지금 마감합니다. 정말이면 /관리자 순위 결산"));
                return;
            }
            eco.rank().settleNow();
        }, (s, args) -> List.of("결산"));

        add("기념비", "지급 <플레이어> | 목록", "기념비 건립권 지급 (보통은 주간 1위가 받음) / 세워진 기념비", (s, args) -> {
            String sub = args.length > 0 ? args[0] : "목록";
            if (sub.equals("지급")) {
                if (args.length < 2) {
                    s.sendMessage(Text.msg("<red>/관리자 기념비 지급 <플레이어>"));
                    return;
                }
                Players.find(args[1], found -> {
                    if (found.isEmpty()) s.sendMessage(Text.msg("<red>없는 플레이어"));
                    else s.sendMessage(Text.msg("<green>" + args[1] + " 기념비 건립권 " + eco.vanity().grantMonument(found.get()) + "장"));
                });
                return;
            }
            s.sendMessage(Text.msg("<gray>기념비 " + eco.vanity().monuments().size() + "개"));
            for (var m : eco.vanity().monuments()) s.sendMessage(Text.mm(" <white>#" + m.slot() + " " + Players.name(m.player()) + " <gray>\"" + m.text() + "\""));
        }, (s, args) -> args.length == 1 ? List.of("지급", "목록")
                : args.length == 2 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of());

        // /관리자 보고서 [일차]: the economy's health under the server's
        admin.report(EcoPlugin.ADMIN_OWNER, (s, args) -> {
            Integer day = null;
            if (args.length > 0) {
                try {
                    day = Integer.valueOf(args[0]);
                } catch (NumberFormatException e) {
                    s.sendMessage(Text.msg("<red>/관리자 보고서 [일차]"));
                    return;
                }
            }
            eco.metrics().show(s, day);
        });

        admin.reset(EcoPlugin.ADMIN_OWNER, "경제서버", "상점 시세, 거래소, 경매, 직업, 땅, 의뢰, 사건, 순위 기록. 1일차부터 다시", by -> {
            eco.resetAll();
            eco.getLogger().warning(by.getName() + " 님이 경제 서버 기록을 초기화했습니다.");
            return CompletableFuture.completedFuture(null);
        });
    }

    /** 흔함 / 보통 / 희귀 / 전설 (or the ids), 진주 = "pearl"; null if none. */
    static String rarityId(dev.stsv.economy.fishing.FishSpec spec, String q) {
        if (q.equals("진주") || q.equalsIgnoreCase("pearl")) return "pearl";
        for (var r : spec.rarities().values()) if (r.name().equals(q) || r.id().equalsIgnoreCase(q)) return r.id();
        return null;
    }

    /** A fish by its Korean name (spaces may be written as _) or its id. */
    static dev.stsv.economy.fishing.FishSpec.Species speciesOf(dev.stsv.economy.fishing.FishSpec spec, String q) {
        String n = q.replace("_", " ");
        for (var f : spec.species().values()) if (f.name().equals(n) || f.id().equalsIgnoreCase(q)) return f;
        return null;
    }
}
