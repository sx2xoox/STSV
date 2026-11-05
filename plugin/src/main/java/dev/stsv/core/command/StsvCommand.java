package dev.stsv.core.command;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.gui.RecipeEditor;
import dev.stsv.core.recipe.RecipeModule;
import org.bukkit.NamespacedKey;
import dev.stsv.core.pack.PackBuilder;
import dev.stsv.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class StsvCommand implements TabExecutor {

    private final STSVPlugin plugin;

    public StsvCommand(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    // Korean command words (shown everywhere) -> internal names. The English words still work, unlisted.
    private static final Map<String, String> ROOT = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> SUB = new LinkedHashMap<>();
    private static final Map<String, String> RECIPE_TYPES = new LinkedHashMap<>();

    static {
        String[][] root = {{"지급", "give"}, {"아이템목록", "browse"}, {"목록", "list"}, {"정보", "info"}, {"레시피", "recipe"},
                {"연료", "fuel"}, {"스킨", "skin"}, {"치장", "cosmetic"}, {"보스", "boss"},
                {"기간", "period"}, {"칭호", "title"}, {"통계", "stat"}, {"경제", "economy"}, {"보고서", "report"}, {"초기화", "reset"}, {"리로드", "reload"}, {"리소스팩", "pack"}, {"도움말", "help"}};
        for (String[] r : root) ROOT.put(r[0], r[1]);
        sub("recipe", "생성", "create", "삭제", "delete", "목록", "list");
        sub("fuel", "설정", "set", "제거", "remove", "목록", "list");
        sub("skin", "해제", "remove");
        sub("cosmetic", "지급", "give", "회수", "take", "정보", "info");
        sub("boss", "소환", "spawn", "목록", "list", "제거", "remove", "패턴", "force", "디버그", "debug", "좌표소환", "spawnat");
        sub("period", "시작", "start", "종료", "end", "정보", "info");
        sub("title", "지급", "give", "회수", "take", "목록", "list");
        sub("stat", "추가", "add");
        sub("economy", "지급", "give", "회수", "take", "설정", "set", "기록", "history", "되돌리기", "revert", "통계", "stats");
        sub("reset", "경제", "economy", "기록", "records");
        String[][] types = {{"모양조합", "shaped"}, {"무형조합", "shapeless"}, {"화로", "furnace"}, {"용광로", "blasting"},
                {"훈연기", "smoking"}, {"모닥불", "campfire"}, {"석재절단기", "stonecutting"}};
        for (String[] t : types) RECIPE_TYPES.put(t[0], t[1]);
    }

    private static void sub(String root, String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        SUB.put(root, m);
    }

    /** Turns the Korean words of a command line into the internal names the handlers below use. */
    static String[] normalize(String[] args) {
        String[] a = args.clone();
        if (a.length > 0) a[0] = ROOT.getOrDefault(a[0], a[0].toLowerCase(Locale.ROOT));
        if (a.length > 1 && SUB.containsKey(a[0])) a[1] = SUB.get(a[0]).getOrDefault(a[1], a[1].toLowerCase(Locale.ROOT));
        if (a.length > 3 && a[0].equals("recipe") && a[1].equals("create")) a[3] = RECIPE_TYPES.getOrDefault(a[3], a[3]);
        return a;
    }

    private static List<String> korean(String root) {
        return List.copyOf(SUB.get(root).keySet());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] rawArgs) {
        // a sub command a content plugin added (the core's own words win a clash)
        AdminRegistry.Sub added = rawArgs.length == 0 || ROOT.containsKey(rawArgs[0]) ? null : plugin.admin().sub(rawArgs[0]);
        if (added != null) {
            try {
                added.handler().run(sender, java.util.Arrays.copyOfRange(rawArgs, 1, rawArgs.length));
            } catch (RuntimeException e) {
                sender.sendMessage(Text.msg("<red>오류: " + e.getMessage()));
                plugin.getLogger().log(java.util.logging.Level.WARNING, "/" + label + " " + String.join(" ", rawArgs), e);
            }
            return true;
        }
        String[] args = normalize(rawArgs);
        String sub = args.length == 0 ? "help" : args[0];
        switch (sub) {
            case "give" -> give(sender, args);
            case "browse" -> {
                if (sender instanceof Player p) plugin.itemBrowser().open(p, 0);
                else sender.sendMessage(Text.msg("<red>플레이어만 사용할 수 있습니다."));
            }
            case "list" -> {
                sender.sendMessage(Text.msg("<gray>커스텀 아이템 <white>" + plugin.content().items().size() + "</white>개"));
                for (CustomItem i : plugin.content().items()) {
                    sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + i.key().asString()
                            + "</white>" + (i.isFurniture() ? " <gold>[가구]" : "")));
                }
            }
            case "info" -> {
                if (!(sender instanceof Player p)) return true;
                Optional<CustomItem> item = plugin.content().itemOf(p.getInventory().getItemInMainHand());
                sender.sendMessage(item.map(i -> Text.msg("<white>" + i.key().asString() + " <gray>(팩: " + i.pack().id()
                                + ", 모델: " + (i.itemModelKey() == null ? "없음" : i.itemModelKey().asString()) + ")"))
                        .orElse(Text.msg("<gray>손에 든 아이템은 커스텀 아이템이 아닙니다.")));
            }
            case "reload" -> {
                long t = System.currentTimeMillis();
                plugin.reloadConfig();
                dev.stsv.core.util.Lang.load(plugin);
                List<String> failed = plugin.modules().reloadAll();
                if (failed.isEmpty()) {
                    sender.sendMessage(Text.msg("<green>리로드 완료 <gray>(" + (System.currentTimeMillis() - t) + "ms)"));
                } else {
                    sender.sendMessage(Text.msg("<red>일부 모듈 리로드 실패: " + String.join(", ", failed) + " <gray>(콘솔 확인)"));
                }
            }
            case "pack" -> {
                PackBuilder.Built b = plugin.pack().current();
                if (b != null) {
                    sender.sendMessage(Text.msg("<gray>리소스팩: 파일 " + b.files() + "개, " + b.zip().length / 1024
                            + "KB, 경고 " + b.warnings().size() + "개"));
                }
                if (sender instanceof Player p) plugin.pack().send(p);
            }
            case "recipe" -> recipe(sender, args);
            case "skin" -> {
                if (sender instanceof Player p && args.length > 1 && args[1].equalsIgnoreCase("remove")) plugin.skins().removeHeld(p);
                else sender.sendMessage(Text.msg("<red>/관리자 스킨 해제 <gray>(손에 든 아이템의 스킨 해제)"));
            }
            case "cosmetic" -> cosmetic(sender, args);
            case "fuel" -> fuel(sender, args);
            case "boss" -> boss(sender, args);
            case "period" -> period(sender, args);
            case "title" -> title(sender, args);
            case "stat" -> stat(sender, args);
            case "economy" -> economy(sender, args);
            case "reset" -> reset(sender, args);
            case "report" -> report(sender, java.util.Arrays.copyOfRange(rawArgs, 1, rawArgs.length));
            default -> {
                sender.sendMessage(Text.msg("<gray>명령어 목록"));
                for (String line : List.of(
                        "지급 <플레이어> <아이템> [개수] <dark_gray>- 아이템 지급",
                        "아이템목록 <dark_gray>- 아이템 목록 GUI",
                        "목록 | 정보 <dark_gray>- 아이템 목록 / 손에 든 아이템 정보",
                        "레시피 생성 <id> <종류> [시간] [경험치] <dark_gray>- 레시피 편집기",
                        "레시피 삭제 <id> | 레시피 목록",
                        "연료 설정 <틱> | 연료 제거 | 연료 목록 <dark_gray>- 손에 든 아이템의 화로 연료 시간",
                        "스킨 해제 <dark_gray>- 손에 든 아이템의 스킨 해제",
                        "치장 지급|회수 <플레이어> <치장> | 치장 정보 [플레이어] <dark_gray>- 치장 지급/회수/상태",
                        "보스 소환 <보스> | 보스 목록 | 보스 제거 <dark_gray>- 보스 소환/목록/모두 제거",
                        "보스 패턴 <패턴> | 보스 디버그 | 보스 좌표소환 <보스> <x> <y> <z> [월드] <dark_gray>- 보스 테스트",
                        "기간 시작 <id> <이름> | 기간 종료 | 기간 정보 <dark_gray>- 콘텐츠 기간 (기록, 명예의 전당)",
                        "칭호 지급|회수 <플레이어> <칭호> | 칭호 목록 <dark_gray>- 칭호",
                        "통계 추가 <플레이어> <통계> <값> <dark_gray>- 기록 보정",
                        "경제 지급|회수|설정 <플레이어> <금액> [메모] <dark_gray>- 돈 지급/회수/지정",
                        "경제 기록 <플레이어> [개수] | 경제 되돌리기 <플레이어> <분> <dark_gray>- 거래 기록 / 최근 N분 거래 취소",
                        "경제 통계 <dark_gray>- 최근 24시간 생성·소멸, 총 통화량, 빈부격차",
                        "보고서 <dark_gray>- 서버 상태(TPS, 렉, 메모리, 청크, 엔티티)와 콘텐츠 보고서",
                        "초기화 " + String.join("|", resetWords()) + " <dark_gray>- 테스트용 초기화 (확인 필요)",
                        "리로드 | 리소스팩")) {
                    sender.sendMessage(Text.mm(" <white>/" + label + " " + line));
                }
                String owner = null;
                for (AdminRegistry.Sub x : plugin.admin().subs()) {
                    if (ROOT.containsKey(x.name())) continue;
                    if (!x.owner().equals(owner)) {
                        owner = x.owner();
                        sender.sendMessage(Text.msg("<gray>" + owner));
                    }
                    sender.sendMessage(Text.mm(" <white>/" + label + " " + x.name() + (x.usage().isEmpty() ? "" : " " + x.usage())
                            + " <dark_gray>- " + x.help()));
                }
            }
        }
        return true;
    }

    private void period(CommandSender sender, String[] args) {
        var profiles = plugin.profiles();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
        try {
            switch (sub) {
                case "start" -> {
                    if (args.length < 4) {
                        sender.sendMessage(Text.msg("<red>/관리자 기간 시작 <id> <이름...> <gray>(id 예: economy-1)"));
                        return;
                    }
                    var p = profiles.startPeriod(args[2], String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
                    sender.sendMessage(Text.msg("<green>기간 시작: <white>" + p.id() + " <gray>(" + p.name() + ")"));
                }
                case "end" -> {
                    var hall = profiles.endPeriod();
                    sender.sendMessage(Text.msg("<green>기간 종료. 명예의 전당 " + hall.values().stream().mapToInt(List::size).sum() + "명 기록"));
                }
                default -> {
                    sender.sendMessage(Text.msg(profiles.store().current()
                            .map(p -> "<gray>진행 중: <white>" + p.id() + " <gray>(" + p.name() + ")")
                            .orElse("<gray>진행 중인 콘텐츠 기간 없음")));
                    profiles.store().periods().thenAccept(list -> sender.sendMessage(Text.mm("<gray>전체 기간: <white>"
                            + String.join(", ", list.stream().map(p -> p.id() + (p.ended() == null ? "(진행 중)" : "")).toList()))));
                }
            }
        } catch (RuntimeException e) {
            sender.sendMessage(Text.msg("<red>" + e.getMessage()));
        }
    }

    private void title(CommandSender sender, String[] args) {
        var profiles = plugin.profiles();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        if (sub.equals("give") || sub.equals("take")) {
            if (args.length < 4) {
                sender.sendMessage(Text.msg("<red>/관리자 칭호 " + (sub.equals("give") ? "지급" : "회수") + " <플레이어> <칭호>"));
                return;
            }
            profiles.store().findByName(args[2]).thenAccept(found -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (found.isEmpty()) {
                    sender.sendMessage(Text.msg("<red>접속한 적 없는 플레이어: " + args[2]));
                    return;
                }
                boolean ok = sub.equals("give") ? profiles.grantTitle(found.get(), args[3]) : profiles.revokeTitle(found.get(), args[3]);
                sender.sendMessage(Text.msg(ok ? "<green>완료" : "<red>알 수 없는 칭호: " + args[3]));
            }));
            return;
        }
        sender.sendMessage(Text.msg("<gray>칭호 " + profiles.titles().size() + "개"));
        for (var t : profiles.titles()) sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + t.id() + " " + t.name()));
    }

    private void stat(CommandSender sender, String[] args) {
        if (args.length < 5 || !args[1].equalsIgnoreCase("add")) {
            sender.sendMessage(Text.msg("<red>/관리자 통계 추가 <플레이어> <통계> <값> <gray>(음수로 빼기)"));
            return;
        }
        double value;
        try {
            value = Double.parseDouble(args[4]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.msg("<red>값은 숫자"));
            return;
        }
        plugin.profiles().store().findByName(args[2]).thenAccept(found -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (found.isEmpty()) {
                sender.sendMessage(Text.msg("<red>접속한 적 없는 플레이어: " + args[2]));
                return;
            }
            plugin.profiles().add(found.get(), args[3], value);
            sender.sendMessage(Text.msg("<green>" + args[2] + " " + args[3] + " " + (value >= 0 ? "+" : "") + value));
        }));
    }

    private static final Map<String, String> KIND_NAMES = Map.of("FAUCET", "생성", "SINK", "소멸", "TRANSFER", "거래",
            "CHEQUE", "수표", "ADMIN", "관리", "ESCROW", "보관");

    private void economy(CommandSender sender, String[] args) {
        var eco = plugin.economy();
        String sub = args.length > 1 ? args[1] : "";
        switch (sub) {
            case "give", "take", "set" -> {
                long amount = args.length > 3 ? EconomyCommand.parseAmount(args[3]) : -1;
                if (args.length > 3 && sub.equals("set") && args[3].equals("0")) amount = 0;
                if (amount < 0) {
                    sender.sendMessage(Text.msg("<red>/관리자 경제 " + (sub.equals("give") ? "지급" : sub.equals("take") ? "회수" : "설정")
                            + " <플레이어> <금액> [메모]"));
                    return;
                }
                long value = amount;
                String note = sender.getName() + (args.length > 4 ? ": " + String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : "");
                findPlayer(sender, args[2], uuid -> {
                    if (!eco.hasAccount(uuid)) {
                        sender.sendMessage(Text.msg("<red>계좌가 없는 플레이어: " + args[2]));
                        return;
                    }
                    switch (sub) {
                        case "give" -> eco.move(null, uuid, value, dev.stsv.core.economy.EconomyStore.Kind.ADMIN, "admin_give", note);
                        case "take" -> eco.set(uuid, eco.balance(uuid) - value, note);
                        default -> eco.set(uuid, value, note);
                    }
                    sender.sendMessage(Text.msg("<green>" + args[2] + " 잔액: </green>" + dev.stsv.core.economy.EconomyModule.format(eco.balance(uuid))));
                });
            }
            case "history" -> {
                if (args.length < 3) {
                    sender.sendMessage(Text.msg("<red>/관리자 경제 기록 <플레이어> [개수]"));
                    return;
                }
                int limit = args.length > 3 ? Math.max(1, (int) Math.min(100, EconomyCommand.parseAmount(args[3]))) : 15;
                findPlayer(sender, args[2], uuid -> eco.store().history(uuid, limit).thenAccept(list -> {
                    var time = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());
                    sender.sendMessage(Text.msg("<gray>" + args[2] + " 거래 기록 (최근 " + list.size() + "건)"));
                    for (var e : list) {
                        boolean in = uuid.equals(e.to());
                        UUID other = in ? e.from() : e.to();
                        String otherName = other == null ? "" : " <gray>" + (in ? "← " : "→ ") + Optional.ofNullable(Bukkit.getOfflinePlayer(other).getName()).orElse("?");
                        sender.sendMessage(Text.mm((e.reverted() ? "<st>" : "") + " <dark_gray>" + time.format(java.time.Instant.ofEpochMilli(e.at()))
                                + "</dark_gray> " + (in ? "<green>+" : "<red>-") + String.format("%,d", e.amount()) + "</green></red>"
                                + " <gray>[" + KIND_NAMES.getOrDefault(e.kind().name(), e.kind().name()) + "] <white>" + e.reason()
                                + otherName + (e.note() == null ? "" : " <dark_gray>(" + e.note() + ")")));
                    }
                }));
            }
            case "revert" -> {
                long minutes = args.length > 3 ? EconomyCommand.parseAmount(args[3]) : -1;
                if (minutes <= 0) {
                    sender.sendMessage(Text.msg("<red>/관리자 경제 되돌리기 <플레이어> <분> <gray>(그 플레이어가 낀 최근 N분 거래를 모두 취소, 상대방 쪽도 함께)"));
                    return;
                }
                long now = System.currentTimeMillis();
                findPlayer(sender, args[2], uuid -> eco.store().revert(uuid, now - minutes * 60_000L, now).thenAccept(n ->
                        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Text.msg("<green>" + args[2] + ": 거래 " + n
                                + "건 취소. 현재 잔액 </green>" + dev.stsv.core.economy.EconomyModule.format(eco.balance(uuid)))))));
            }
            case "stats" -> eco.store().stats(System.currentTimeMillis() - 86_400_000L).thenAccept(st -> {
                sender.sendMessage(Text.msg("<gray>경제 통계 (최근 24시간)"));
                sender.sendMessage(Text.mm(" <gray>생성: <green>+" + String.format("%,d", st.faucet()) + "</green>  소멸: <red>-"
                        + String.format("%,d", st.sink()) + "</red>  순증가: <white>" + String.format("%+,d", st.faucet() - st.sink())));
                sender.sendMessage(Text.mm(" <gray>플레이어 간 거래: <white>" + String.format("%,d", st.transfer())));
                sender.sendMessage(Text.mm(" <gray>총 통화량: <gold>" + String.format("%,d", st.total()) + " G</gold> (계좌 " + st.accounts()
                        + "개, 평균 " + String.format("%,d", st.accounts() == 0 ? 0 : st.total() / st.accounts()) + ")"));
                sender.sendMessage(Text.mm(" <gray>빈부격차(지니): <white>" + String.format("%.2f", st.gini()) + " <dark_gray>(0 평등 ~ 1 독점)"));
            });
            default -> sender.sendMessage(Text.msg("<red>/관리자 경제 <지급|회수|설정|기록|되돌리기|통계>"));
        }
    }

    /** /관리자 보고서: how the server is running, then what each content plugin reports. */
    private void report(CommandSender sender, String[] args) {
        double[] tps = Bukkit.getTPS();
        double mspt = Bukkit.getAverageTickTime();
        long[] ticks = Bukkit.getTickTimes();
        long worst = 0;
        for (long t : ticks) worst = Math.max(worst, t);
        Runtime rt = Runtime.getRuntime();
        long mb = 1024 * 1024, used = (rt.totalMemory() - rt.freeMemory()) / mb, max = rt.maxMemory() / mb;
        long upMin = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 60_000;
        sender.sendMessage(Text.msg("<gold>서버 상태 <gray>(켜진 지 " + (upMin >= 60 ? upMin / 60 + "시간 " : "") + upMin % 60 + "분)"));
        sender.sendMessage(Text.mm(" <gray>TPS <white>" + tpsText(tps[0]) + " <dark_gray>/ <white>" + tpsText(tps[1]) + " <dark_gray>/ <white>" + tpsText(tps[2])
                + " <dark_gray>(1분/5분/15분)"));
        sender.sendMessage(Text.mm(" <gray>틱 시간 <white>평균 " + msptText(mspt) + " <dark_gray>· <white>최근 최대 " + msptText(worst / 1_000_000.0)
                + " <dark_gray>(50ms를 넘으면 렉)"));
        sender.sendMessage(Text.mm(" <gray>메모리 <white>" + used + " / " + max + "MB <dark_gray>(" + Math.round(100.0 * used / Math.max(1, max)) + "%)"));
        int chunks = 0, entities = 0, tiles = 0;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            int c = w.getLoadedChunks().length, e = w.getEntityCount(), t = w.getTileEntityCount();
            chunks += c; entities += e; tiles += t;
            sender.sendMessage(Text.mm("  <dark_gray>- <white>" + w.getName() + " <gray>청크 " + c + ", 엔티티 " + e + ", 블록 엔티티 " + t
                    + ", 플레이어 " + w.getPlayerCount()));
        }
        sender.sendMessage(Text.mm(" <gray>합계 <white>청크 " + chunks + ", 엔티티 " + entities + ", 블록 엔티티 " + tiles + ", 접속 " + Bukkit.getOnlinePlayers().size() + "명"));
        for (AdminRegistry.ReportSection r : plugin.admin().reportList()) {
            try {
                r.write(sender, args);
            } catch (RuntimeException e) {
                sender.sendMessage(Text.msg("<red>보고서 오류: " + e.getMessage()));
                plugin.getLogger().log(java.util.logging.Level.WARNING, "보고서", e);
            }
        }
    }

    private static String tpsText(double t) {
        double v = Math.min(20.0, t);
        String col = v >= 19.5 ? "<green>" : v >= 17 ? "<yellow>" : "<red>";
        return col + String.format(Locale.ROOT, "%.1f", v) + "<white>";
    }

    private static String msptText(double ms) {
        String col = ms <= 40 ? "<green>" : ms <= 50 ? "<yellow>" : "<red>";
        return col + String.format(Locale.ROOT, "%.1fms", ms) + "<white>";
    }

    private List<String> resetWords() {
        List<String> out = new java.util.ArrayList<>(List.of("경제", "기록"));
        for (AdminRegistry.ResetTarget t : plugin.admin().resetTargets()) out.add(t.word());
        return out;
    }

    private void reset(CommandSender sender, String[] args) {
        String what = args.length > 1 ? args[1] : "";
        AdminRegistry.ResetTarget added = plugin.admin().resetTarget(what);
        if (added != null) {
            if (args.length < 3 || !(args[2].equals("확인") || args[2].equalsIgnoreCase("confirm"))) {
                sender.sendMessage(Text.msg("<yellow>되돌릴 수 없습니다. 정말 초기화하려면 <white>/관리자 초기화 " + what + " 확인 <gray>(" + added.help() + ")"));
                return;
            }
            added.reset().run(sender).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Text.msg(err == null
                    ? "<green>" + what + " 초기화 완료" : "<red>초기화 실패: " + err.getMessage()))));
            plugin.getLogger().warning(sender.getName() + " 님이 " + what + "을(를) 초기화했습니다.");
            return;
        }
        if (!what.equals("economy") && !what.equals("records")) {
            sender.sendMessage(Text.msg("<red>/관리자 초기화 경제 <gray>(모든 잔액·거래 기록·수표)</gray> | /관리자 초기화 기록 <gray>(모든 통계·기간·명예의 전당·칭호)"));
            for (AdminRegistry.ResetTarget t : plugin.admin().resetTargets()) {
                sender.sendMessage(Text.msg("<red>/관리자 초기화 " + t.word() + " <gray>(" + t.help() + ")"));
            }
            return;
        }
        String word = what.equals("economy") ? "경제" : "기록";
        if (args.length < 3 || !(args[2].equals("확인") || args[2].equalsIgnoreCase("confirm"))) {
            sender.sendMessage(Text.msg("<yellow>되돌릴 수 없습니다. 정말 초기화하려면 <white>/관리자 초기화 " + word + " 확인"));
            return;
        }
        var done = what.equals("economy") ? plugin.economy().reset() : plugin.profiles().reset();
        done.whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Text.msg(err == null
                ? "<green>" + word + " 초기화 완료" + (what.equals("economy") ? " (접속 중인 플레이어는 시작 금액을 다시 받음)" : "")
                : "<red>초기화 실패: " + err.getMessage()))));
        plugin.getLogger().warning(sender.getName() + " 님이 " + word + "을(를) 초기화했습니다.");
    }

    /** Online player by name, else anyone who ever joined; reports unknown names. Callback on the main thread. */
    private void findPlayer(CommandSender sender, String name, java.util.function.Consumer<UUID> then) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            then.accept(online.getUniqueId());
            return;
        }
        plugin.profiles().store().findByName(name).thenAccept(found -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (found.isEmpty()) sender.sendMessage(Text.msg("<red>접속한 적 없는 플레이어: " + name));
            else then.accept(found.get());
        }));
    }

    private void boss(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "spawn" -> {
                if (!(sender instanceof Player p) || args.length < 3) {
                    sender.sendMessage(Text.msg("<red>/관리자 보스 소환 <보스> <gray>(바라보는 곳에 소환)"));
                    return;
                }
                org.bukkit.block.Block aim = p.getTargetBlockExact(24);
                org.bukkit.Location at = aim != null ? aim.getLocation().add(0.5, 1, 0.5)
                        : p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(5));
                at.setYaw(p.getLocation().getYaw() + 180);
                plugin.bosses().spawn(args[2], at).ifPresentOrElse(
                        b -> sender.sendMessage(Text.msg("<green>소환: <white>" + args[2])),
                        () -> sender.sendMessage(Text.msg("<red>알 수 없는 보스: " + args[2])));
            }
            case "list" -> {
                sender.sendMessage(Text.msg("<gray>보스 " + plugin.bosses().specs().size() + "종, 살아있는 보스 "
                        + plugin.bosses().active().size() + "마리"));
                for (dev.stsv.core.boss.BossSpec s : plugin.bosses().specs()) {
                    sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + s.id() + "</white> " + s.name()
                            + " <gray>(체력 " + (int) s.health() + ", 패턴 " + String.join(", ", s.skills().keySet()) + ")"));
                }
            }
            case "remove" -> sender.sendMessage(Text.msg("<gray>보스 " + plugin.bosses().removeAll() + "마리 제거"));
            // admin testing (also from the console)
            case "spawnat" -> {
                if (args.length < 6) {
                    sender.sendMessage(Text.msg("<red>/관리자 보스 좌표소환 <보스> <x> <y> <z> [월드]"));
                    return;
                }
                org.bukkit.World w = args.length > 6 ? org.bukkit.Bukkit.getWorld(args[6]) : org.bukkit.Bukkit.getWorlds().getFirst();
                org.bukkit.Location at = new org.bukkit.Location(w, Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5]));
                sender.sendMessage(Text.msg(plugin.bosses().spawn(args[2], at).isPresent() ? "<green>소환" : "<red>알 수 없는 보스"));
            }
            case "force" -> sender.sendMessage(Text.msg("<gray>패턴 시작: " + (args.length > 2 ? plugin.bosses().force(args[2]) : 0) + "마리"));
            case "debug" -> plugin.bosses().debug().forEach(line -> sender.sendMessage(Text.mm("<gray>" + line)));
            default -> sender.sendMessage(Text.msg("<red>/관리자 보스 <소환|목록|제거|패턴|디버그|좌표소환>"));
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.msg("<red>/관리자 지급 <플레이어> <아이템> [개수]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.msg("<red>플레이어를 찾을 수 없습니다: " + args[1]));
            return;
        }
        Optional<CustomItem> item = plugin.content().item(args[2]);
        if (item.isEmpty()) {
            sender.sendMessage(Text.msg("<red>알 수 없는 아이템: " + args[2]));
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.clamp(Integer.parseInt(args[3]), 1, 6400);
            } catch (NumberFormatException e) {
                sender.sendMessage(Text.msg("<red>개수는 숫자여야 합니다."));
                return;
            }
        }
        int left = amount;
        while (left > 0) {
            ItemStack stack = item.get().create(1);
            int n = Math.min(left, stack.getMaxStackSize());
            stack.setAmount(n);
            target.getInventory().addItem(stack).values()
                    .forEach(rest -> target.getWorld().dropItem(target.getLocation(), rest));
            left -= n;
        }
        sender.sendMessage(Text.msg("<white>" + target.getName() + "</white><gray>에게 </gray><white>"
                + item.get().key().asString() + " x" + amount + "</white><gray> 지급"));
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 레시피 ...
    // ------------------------------------------------------------------------------------------

    private NamespacedKey key(String id) {
        String ns = plugin.getConfig().getString("editor.namespace");
        NamespacedKey key = id.contains(":") ? NamespacedKey.fromString(id.toLowerCase(Locale.ROOT))
                : NamespacedKey.fromString(ns + ":" + id.toLowerCase(Locale.ROOT));
        if (key == null) throw new IllegalArgumentException("id는 영문 소문자, 숫자, _ 만 가능: " + id);
        return key;
    }

    private void recipe(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        try {
            switch (action) {
                case "create" -> {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage(Text.msg("<red>플레이어만 사용할 수 있습니다."));
                        return;
                    }
                    if (args.length < 4) {
                        sender.sendMessage(Text.msg("<red>/관리자 레시피 생성 <id> <" + String.join("|", RECIPE_TYPES.keySet())
                                + "> [시간(틱)] [경험치]"));
                        return;
                    }
                    Integer time = args.length > 4 ? Integer.valueOf(args[4]) : null;
                    Float xp = args.length > 5 ? Float.valueOf(args[5]) : null;
                    plugin.recipeEditor().open(p, key(args[2]), args[3], time, xp);
                }
                case "delete" -> {
                    if (args.length < 3) {
                        sender.sendMessage(Text.msg("<red>/관리자 레시피 삭제 <id>"));
                        return;
                    }
                    NamespacedKey k = key(args[2]);
                    sender.sendMessage(plugin.recipes().deleteRecipe(k)
                            ? Text.msg("<green>" + k.asString() + " 삭제 완료")
                            : Text.msg("<red>편집기로 만든 레시피(recipes/" + RecipeModule.EDITOR_FILE
                                    + ")만 삭제할 수 있습니다. 다른 파일은 직접 지우고 /관리자 리로드 하세요."));
                }
                case "list" -> {
                    List<NamespacedKey> keys = plugin.recipes().registered();
                    sender.sendMessage(Text.msg("<gray>등록된 레시피 <white>" + keys.size() + "</white>개"));
                    keys.forEach(k -> sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + k.asString())));
                }
                default -> sender.sendMessage(Text.msg("<red>/관리자 레시피 <생성|삭제|목록>"));
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.msg("<red>시간과 경험치는 숫자여야 합니다."));
        } catch (Exception e) {
            sender.sendMessage(Text.msg("<red>" + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 치장
    // ------------------------------------------------------------------------------------------

    private void cosmetic(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("info")) {
            Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
            if (target == null) {
                sender.sendMessage(Text.msg("<red>/관리자 치장 정보 [플레이어]"));
                return;
            }
            sender.sendMessage(Text.msg("<gray>" + target.getName() + " 치장 상태"));
            plugin.cosmetics().info(target).forEach(l -> sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + l)));
            return;
        }
        if (args.length < 4 || !(args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("take"))) {
            sender.sendMessage(Text.msg("<red>/관리자 치장 <지급|회수> <플레이어> <치장>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        Optional<CustomItem> c = plugin.content().item(args[3]).filter(i -> i.cosmetic() != null);
        if (target == null || c.isEmpty()) {
            sender.sendMessage(Text.msg("<red>플레이어 또는 치장을 찾을 수 없습니다."));
            return;
        }
        boolean give = args[1].equalsIgnoreCase("give");
        boolean changed = plugin.cosmetics().grant(target, c.get(), give);
        if (give && changed) plugin.cosmetics().celebrate(target, c.get());
        sender.sendMessage(Text.msg(changed
                ? "<green>" + target.getName() + " 에게 " + c.get().key().asString() + (give ? " 지급" : " 회수")
                : "<yellow>변화 없음 (" + (give ? "이미 보유" : "보유하지 않음") + ")"));
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 연료 ...
    // ------------------------------------------------------------------------------------------

    private void fuel(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        try {
            if (action.equals("list")) {
                var fuels = plugin.recipes().fuels();
                sender.sendMessage(Text.msg("<gray>커스텀 연료 <white>" + fuels.size() + "</white>개 (석탄 = 1600틱)"));
                fuels.forEach((id, t) -> sender.sendMessage(Text.mm(" <dark_gray>-</dark_gray> <white>" + id
                        + "</white> <gray>" + t + "틱 (" + (t / 20.0) + "초, 아이템 " + String.format("%.1f", t / 200.0) + "개 굽기)")));
                return;
            }
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Text.msg("<red>플레이어만 사용할 수 있습니다."));
                return;
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.isEmpty()) {
                sender.sendMessage(Text.msg("<red>연료로 쓸 아이템을 손에 드세요."));
                return;
            }
            String id = plugin.recipes().idOf(hand);
            String ns = plugin.content().itemOf(hand).map(i -> i.key().getNamespace())
                    .orElse(plugin.getConfig().getString("editor.namespace"));
            switch (action) {
                case "set" -> {
                    if (args.length < 3) {
                        sender.sendMessage(Text.msg("<red>/관리자 연료 설정 <틱>  (석탄 = 1600, 막대기 = 100)"));
                        return;
                    }
                    int ticks = Integer.parseInt(args[2]);
                    if (ticks <= 0) throw new IllegalArgumentException("1 이상이어야 합니다.");
                    if (!hand.getType().isFuel()) {
                        sender.sendMessage(Text.msg("<red>" + id + " 은(는) 기본 재료(" + hand.getType().getKey().getKey()
                                + ")가 연료가 아니라 화로 연료 칸에 들어가지 않습니다. 커스텀 아이템이라면 material 을 coal 등으로 바꾸세요."));
                        return;
                    }
                    plugin.recipes().setFuel(id, ticks, ns);
                    sender.sendMessage(Text.msg("<green>" + id + " 연료 시간 = " + ticks + "틱 (" + ticks / 20.0 + "초)"));
                }
                case "remove" -> {
                    plugin.recipes().setFuel(id, null, ns);
                    sender.sendMessage(Text.msg("<green>" + id + " 연료 설정 삭제 (아이템 정의의 fuel: 은 그대로)"));
                }
                default -> sender.sendMessage(Text.msg("<red>/관리자 연료 <설정|제거|목록>"));
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Text.msg("<red>틱은 숫자여야 합니다."));
        } catch (Exception e) {
            sender.sendMessage(Text.msg("<red>" + e.getMessage()));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] raw) {
        if (raw.length == 1) {
            List<String> roots = new java.util.ArrayList<>(ROOT.keySet());
            for (AdminRegistry.Sub x : plugin.admin().subs()) if (!roots.contains(x.name())) roots.add(x.name());
            return filter(roots, raw[0]);
        }
        AdminRegistry.Sub added = ROOT.containsKey(raw[0]) ? null : plugin.admin().sub(raw[0]);
        if (added != null) {
            if (added.completer() == null) return List.of();
            return filter(added.completer().complete(sender, java.util.Arrays.copyOfRange(raw, 1, raw.length)), raw[raw.length - 1]);
        }
        String[] args = normalize(raw);
        String root = args[0];
        if (raw.length == 2 && root.equals("reset")) return filter(resetWords(), raw[1]);
        if (raw.length == 2) return SUB.containsKey(root) ? filter(korean(root), raw[1]) : players(root, raw[1]);
        switch (root) {
            case "recipe" -> {
                if (raw.length == 3 && args[1].equals("delete")) {
                    return filter(plugin.recipes().registered().stream().map(NamespacedKey::asString).toList(), raw[2]);
                }
                if (raw.length == 4 && args[1].equals("create")) return filter(List.copyOf(RECIPE_TYPES.keySet()), raw[3]);
            }
            case "title" -> {
                if (raw.length == 3) return filter(onlineNames(), raw[2]);
                if (raw.length == 4) return filter(plugin.profiles().titles().stream().map(t -> t.id()).toList(), raw[3]);
            }
            case "stat", "economy" -> {
                if (raw.length == 3) return filter(onlineNames(), raw[2]);
            }
            case "reset" -> {
                if (raw.length == 3) return filter(List.of("확인"), raw[2]);
            }
            case "boss" -> {
                if (raw.length == 3 && (args[1].equals("spawn") || args[1].equals("spawnat"))) {
                    return filter(plugin.bosses().specs().stream().map(dev.stsv.core.boss.BossSpec::id).toList(), raw[2]);
                }
                if (raw.length == 3 && args[1].equals("force")) {
                    return filter(plugin.bosses().specs().stream().flatMap(b -> b.skills().keySet().stream()).distinct().toList(), raw[2]);
                }
            }
            case "cosmetic" -> {
                if (raw.length == 3) return filter(onlineNames(), raw[2]);
                if (raw.length == 4) return filter(plugin.cosmetics().cosmetics().stream().map(i -> i.key().asString()).toList(), raw[3]);
            }
            case "give" -> {
                if (raw.length == 3) return filter(plugin.content().items().stream().map(i -> i.key().asString()).toList(), raw[2]);
            }
            default -> { }
        }
        return List.of();
    }

    private List<String> players(String root, String prefix) {
        return root.equals("give") ? filter(onlineNames(), prefix) : List.of();
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p) || o.contains(":" + p)).toList();
    }
}
