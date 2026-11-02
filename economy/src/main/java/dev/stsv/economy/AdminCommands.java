package dev.stsv.economy;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.util.Text;
import dev.stsv.economy.season.ActiveEvent;
import dev.stsv.economy.season.SeasonModule;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /경제관리: season, events, places, regions, resets. Other modules add their own sub commands. */
final class AdminCommands {

    private AdminCommands() {}

    static void register(EcoPlugin eco) {
        AdminCommand a = eco.admin();
        SeasonModule season = eco.season();

        a.add("시작", "<이름>", "시즌 시작 (콘텐츠 기간 시작, 1일차)", (s, args) -> {
            if (args.length < 1) {
                s.sendMessage(Text.msg("<red>/경제관리 시작 <이름...>  <gray>예: 경제 서버 1기"));
                return;
            }
            int n = 1;
            while (true) {
                final String candidate = "economy-" + n;
                boolean used = STSVPlugin.get().profiles().store().periods().join().stream().anyMatch(p -> p.id().equals(candidate));
                if (!used) break;
                n++;
            }
            var p = STSVPlugin.get().profiles().startPeriod("economy-" + n, String.join(" ", args));
            eco.kv().set("season.last-reset", 0);
            s.sendMessage(Text.msg("<green>시즌 시작: <white>" + p.id() + " (" + p.name() + ")"));
        });
        a.add("일차", "[숫자]", "지금 일차 보기 / 테스트용으로 일차 바꾸기", (s, args) -> {
            if (args.length > 0) {
                int want = Integer.parseInt(args[0]);
                long offset = eco.kv().getLong("season.offset", 0);
                eco.kv().set("season.offset", offset + (want - season.day()));
                eco.kv().set("season.last-reset", 0);   // run the daily reset for the new day
            }
            s.sendMessage(Text.msg("<gray>" + (season.running() ? "" : "(시즌 시작 전) ") + "<white>" + season.day() + "일차 · "
                    + season.week() + "주차 <gray>(직업 Lv 상한 " + season.levelCap() + ", 땅값 ㎡당 " + season.landPrice() + "G)"));
            List<String> open = new ArrayList<>();
            for (String f : season.spec().opens().keySet()) open.add((season.isOpen(f) ? "<green>" : "<red>") + f + "(" + season.openDay(f) + ")");
            s.sendMessage(Text.mm(" <gray>개방: " + String.join("<gray>, ", open)));
        });
        a.add("사건", "시작 <종류> [시간] [키=값...] | 종료 <종류> | 목록", "사건(품귀, 폭락, 무법지대 등) 바로 시작/종료", (s, args) -> {
            String sub = args.length > 0 ? args[0] : "목록";
            switch (sub) {
                case "시작" -> {
                    if (args.length < 2 || !season.spec().events().containsKey(args[1])) {
                        s.sendMessage(Text.msg("<red>종류: " + String.join(", ", season.spec().events().keySet())));
                        return;
                    }
                    var es = season.spec().events().get(args[1]);
                    double hours = es.hours();
                    Map<String, String> params = new LinkedHashMap<>();
                    for (int i = 2; i < args.length; i++) {
                        int eq = args[i].indexOf('=');
                        if (eq > 0) params.put(args[i].substring(0, eq), args[i].substring(eq + 1));
                        else hours = Double.parseDouble(args[i]);
                    }
                    long now = System.currentTimeMillis();
                    ActiveEvent e = season.startEvent(args[1], now, now + (long) (hours * 3_600_000L), params);
                    s.sendMessage(Text.msg("<green>시작: " + es.name() + " <gray>" + e.params()));
                }
                case "종료" -> s.sendMessage(Text.msg("<gray>종료: " + (args.length > 1 ? season.endEvent(args[1]) : 0) + "건"));
                default -> {
                    s.sendMessage(Text.msg("<gray>진행 중인 사건 " + season.active().size() + "건 <dark_gray>(종류: "
                            + String.join(", ", season.spec().events().keySet()) + ")"));
                    for (ActiveEvent e : season.active()) {
                        s.sendMessage(Text.mm(" <white>" + season.eventName(e.type()) + " <gray>남은 시간 "
                                + SeasonModule.hoursText(e.ends() - System.currentTimeMillis()) + " " + e.params()));
                    }
                }
            }
        }, (s, args) -> args.length == 1 ? List.of("시작", "종료", "목록")
                : args.length == 2 ? new ArrayList<>(season.spec().events().keySet()) : List.of());
        a.add("위치", "<이름>", "지금 서 있는 곳을 저장 (시세판, 무법지대, 월드보스, 기념비1~)", (s, args) -> {
            if (!(s instanceof Player p) || args.length < 1) {
                s.sendMessage(Text.msg("<red>/경제관리 위치 <시세판|무법지대|월드보스|기념비1...>"));
                return;
            }
            String key = switch (args[0]) {
                case "시세판" -> "price-board";
                case "무법지대" -> "lawless";
                case "월드보스" -> "world-boss";
                default -> args[0].startsWith("기념비") ? "monument-" + args[0].substring(3) : args[0];
            };
            eco.setLocation(key, p.getLocation());
            s.sendMessage(Text.msg("<green>저장: " + key));
            if (key.equals("price-board")) eco.shop().updateBoard();
        }, (s, args) -> List.of("시세판", "무법지대", "월드보스", "기념비1", "기념비2", "기념비3"));
        a.add("지역", "생성 <이름> <개방일> <x1> <z1> <x2> <z2> [최대높이] | 삭제 <이름> | 목록", "개방 전 출입 금지 지역", (s, args) -> {
            String sub = args.length > 0 ? args[0] : "목록";
            switch (sub) {
                case "생성" -> {
                    if (args.length < 7) {
                        s.sendMessage(Text.msg("<red>/경제관리 지역 생성 <이름> <개방일> <x1> <z1> <x2> <z2> [최대높이] <gray>(최대높이: 그 아래만 잠금, 예: 심층 0)"));
                        return;
                    }
                    String world = s instanceof Player p ? p.getWorld().getName() : org.bukkit.Bukkit.getWorlds().getFirst().getName();
                    season.putRegion(new SeasonModule.Region(args[1], world, Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                            Integer.parseInt(args[5]), Integer.parseInt(args[6]), args.length > 7 ? Integer.valueOf(args[7]) : null, Integer.parseInt(args[2])));
                    s.sendMessage(Text.msg("<green>지역 저장: " + args[1]));
                }
                case "삭제" -> s.sendMessage(Text.msg(args.length > 1 && season.removeRegion(args[1]) ? "<green>삭제" : "<red>없는 지역"));
                default -> {
                    s.sendMessage(Text.msg("<gray>지역 " + season.regions().size() + "개"));
                    for (var r : season.regions().values()) {
                        s.sendMessage(Text.mm(" <white>" + r.name() + " <gray>" + r.world() + " (" + r.x1() + "," + r.z1() + ")~(" + r.x2() + "," + r.z2() + ")"
                                + (r.maxY() == null ? "" : " Y" + r.maxY() + " 아래") + ", " + r.openDay() + "일차 개방"));
                    }
                }
            }
        }, (s, args) -> args.length == 1 ? List.of("생성", "삭제", "목록") : List.of());
        a.add("노점", "생성 <번호> | 삭제 <번호> | 목록", "노점 상자 등록 (상자를 바라보고)", (s, args) -> {
            String sub = args.length > 0 ? args[0] : "목록";
            switch (sub) {
                case "생성" -> {
                    if (!(s instanceof Player p) || args.length < 2) s.sendMessage(Text.msg("<red>/경제관리 노점 생성 <번호> (상자를 바라보고)"));
                    else s.sendMessage(Text.msg(eco.stalls().create(p, Integer.parseInt(args[1]))));
                }
                case "삭제" -> s.sendMessage(Text.msg(args.length < 2 ? "<red>/경제관리 노점 삭제 <번호>" : eco.stalls().delete(Integer.parseInt(args[1]))));
                default -> {
                    s.sendMessage(Text.msg("<gray>노점 " + eco.stalls().store().all().size() + "개"));
                    for (var st : eco.stalls().store().all().values()) {
                        s.sendMessage(Text.mm(" <white>#" + st.number + " <gray>" + st.world + " " + st.x + "," + st.y + "," + st.z + " - "
                                + (st.owner == null ? "<green>빈 노점" : "<white>" + dev.stsv.economy.util.Players.name(st.owner))));
                    }
                }
            }
        }, (s, args) -> args.length == 1 ? List.of("생성", "삭제", "목록") : List.of());
        a.add("경매", "시작 <시작가> [분] | 종료 <번호> | 취소 <번호>", "서버 경매 (손에 든 물건, 주간 낙찰 제한 적용) / 바로 끝내기", (s, args) -> {
            String sub = args.length > 0 ? args[0] : "";
            switch (sub) {
                case "시작" -> {
                    if (!(s instanceof Player p) || args.length < 2 || p.getInventory().getItemInMainHand().isEmpty()) {
                        s.sendMessage(Text.msg("<red>손에 물건을 들고 /경제관리 경매 시작 <시작가> [분]"));
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
                    s.sendMessage(r == null ? Text.msg("<green>취소") : dev.stsv.core.util.Lang.msg(r));
                }
                default -> s.sendMessage(Text.msg("<red>/경제관리 경매 시작 <시작가> [분] | 종료 <번호> | 취소 <번호>"));
            }
        }, (s, args) -> args.length == 1 ? List.of("시작", "종료", "취소") : List.of());
        a.add("직업", "<플레이어> <직업> | 경험치 <플레이어> <값>", "직업 지정(비용·대기 없음) / 경험치 설정 (테스트)", (s, args) -> {
            if (args.length >= 3 && args[0].equals("경험치")) {
                dev.stsv.economy.util.Players.find(args[1], found -> {
                    if (found.isEmpty()) s.sendMessage(Text.msg("<red>없는 플레이어"));
                    else {
                        eco.jobs().setXp(found.get(), Double.parseDouble(args[2]));
                        s.sendMessage(Text.msg("<green>" + args[1] + " 경험치 " + args[2] + " (Lv " + eco.jobs().level(found.get()) + ")"));
                    }
                });
                return;
            }
            if (args.length < 2) {
                s.sendMessage(Text.msg("<red>/경제관리 직업 <플레이어> <직업> | 경험치 <플레이어> <값>"));
                return;
            }
            String id = Commands.jobId(eco, args[1]);
            if (id == null) {
                s.sendMessage(Text.msg("<red>없는 직업"));
                return;
            }
            dev.stsv.economy.util.Players.find(args[0], found -> {
                if (found.isEmpty()) s.sendMessage(Text.msg("<red>없는 플레이어"));
                else {
                    eco.jobs().store().choose(found.get(), id, System.currentTimeMillis() - 30L * 86_400_000L);
                    s.sendMessage(Text.msg("<green>" + args[0] + " → " + args[1]));
                }
            });
        }, (s, args) -> {
            if (args.length == 1) {
                List<String> out = new ArrayList<>(List.of("경험치"));
                org.bukkit.Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                return out;
            }
            if (args.length == 2 && !args[0].equals("경험치")) return eco.jobs().spec().jobs().values().stream().map(j -> j.name()).toList();
            return List.of();
        });
        a.add("땅", "서버 [반지름] | 삭제", "운영자 구역(스폰 마을): 선택한 두 모서리(/땅 1, /땅 2) 또는 반지름 / 서 있는 구역 삭제", (s, args) -> {
            if (!(s instanceof Player p) || args.length < 1) {
                s.sendMessage(Text.msg("<red>/경제관리 땅 서버 [반지름] | 삭제"));
                return;
            }
            if (args[0].equals("삭제")) eco.land().delete(p);
            else eco.land().create(p, args.length > 1 ? Integer.parseInt(args[1]) : -1, true);
        }, (s, args) -> args.length == 1 ? List.of("서버", "삭제") : List.of());
        a.add("보고", "[일차]", "경제 건강 보고서 (매일 05시 자동). 일차 없이 = 지금까지의 오늘", (s, args) ->
                eco.metrics().show(s, args.length > 0 ? Integer.valueOf(args[0]) : null));
        a.add("수요고시", "", "지금 수요 고시 새로 뽑기", (s, args) -> {
            var picked = eco.shop().announceNotice(System.currentTimeMillis());
            s.sendMessage(Text.msg("<green>수요 고시: " + picked));
        });
        a.add("마감", "확인", "시즌 마감 (순자산 확정, 칭호, 명예의 전당). 일정표의 28일차 21시에 자동", (s, args) -> {
            if (args.length < 1 || !args[0].equals("확인")) {
                s.sendMessage(Text.msg("<yellow>시즌을 끝냅니다. 정말이면 /경제관리 마감 확인"));
                return;
            }
            eco.rank().finale();
        }, (s, args) -> List.of("확인"));
        a.add("초기화", "확인", "테스트용: 경제 서버의 모든 기록(상점, 거래소, 경매, 직업, 땅, 의뢰, 사건) 초기화. 돈은 /관리 초기화 경제", (s, args) -> {
            if (args.length < 1 || !args[0].equals("확인")) {
                s.sendMessage(Text.msg("<yellow>되돌릴 수 없습니다. 정말이면 <white>/경제관리 초기화 확인 <gray>(돈은 /관리 초기화 경제 확인 으로 따로)"));
                return;
            }
            eco.resetAll(s);
        }, (s, args) -> List.of("확인"));
    }
}
