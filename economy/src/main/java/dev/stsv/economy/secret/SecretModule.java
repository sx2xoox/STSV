package dev.stsv.economy.secret;

import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * 숨은 비밀 (docs/admin/숨은_비밀.md; contents/economy/secrets/*.yml; never in the README). Holds the secrets, their
 * saved stages (eco.kv "secret.<id>.<uuid>.*"), on/off switches, who solved each (one per server), test mode, the
 * player's own records ({@link Memory}) and the things only one player sees ({@link Haunt}).
 * <p>
 * Test mode (/관리자 비밀 테스트): for that player a "day" lasts {@code test-day-seconds}, every chance is 1, hour windows are
 * ignored, and solving neither closes the secret nor tells the server (money is not paid either).
 */
public final class SecretModule extends EcoModule {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Map<String, Secret> secrets = new LinkedHashMap<>();
    private Memory memory;
    private Haunt haunt;
    private long testDay = 60_000L;

    public SecretModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-secret";
    }

    EcoPlugin eco() {
        return eco;
    }

    public dev.stsv.core.STSVPlugin core() {
        return core;
    }

    @Override
    protected void start() {
        memory = new Memory(this);
        haunt = new Haunt(this);
        secrets.clear();
        for (Function<SecretModule, Secret> f : List.<Function<SecretModule, Secret>>of(
                dev.stsv.economy.secret.dongbang.Dongbang::new,
                dev.stsv.economy.secret.letter.Letters::new,
                dev.stsv.economy.secret.designer.Designer::new,
                dev.stsv.economy.secret.usher.Usher::new)) {
            Secret s = f.apply(this);
            secrets.put(s.id(), s);
        }
        var common = eco.data("secrets/common.yml");
        testDay = Math.max(10, common.getLong("test-day-seconds", 60)) * 1000L;
        for (Secret s : secrets.values()) {
            try {
                s.load(eco.data("secrets/" + s.id() + ".yml"));
            } catch (RuntimeException e) {
                eco.getLogger().warning("[비밀] " + s.id() + " 설정 오류: " + e);
            }
            Bukkit.getPluginManager().registerEvents(s, eco);
        }
        Bukkit.getPluginManager().registerEvents(memory, eco);
        memory.start();
        every(20L, () -> {
            for (Secret s : secrets.values()) {
                if (!enabled(s)) continue;
                try {
                    s.tick();
                } catch (RuntimeException e) {
                    eco.getLogger().log(java.util.logging.Level.WARNING, "[비밀] " + s.id() + " 오류", e);
                }
            }
        });
    }

    @Override
    protected void stop() {
        for (Secret s : secrets.values()) {
            HandlerList.unregisterAll(s);
            for (Player p : Bukkit.getOnlinePlayers()) s.cleanup(p.getUniqueId());
        }
        if (memory != null) {
            memory.save();
            HandlerList.unregisterAll(memory);
        }
        if (haunt != null) haunt.clearAll();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        for (Secret s : secrets.values()) s.cleanup(e.getPlayer().getUniqueId());
        haunt.clear(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // state
    // ------------------------------------------------------------------------------------------

    public Memory memory() {
        return memory;
    }

    public Haunt haunt() {
        return haunt;
    }

    public java.util.Collection<Secret> all() {
        return secrets.values();
    }

    public Optional<Secret> find(String word) {
        if (word == null) return Optional.empty();
        for (Secret s : secrets.values()) if (s.id().equalsIgnoreCase(word) || s.name().replace(" ", "").equals(word.replace(" ", ""))) return Optional.of(s);
        return Optional.empty();
    }

    private static String key(Secret s, UUID u, String k) {
        return "secret." + s.id() + "." + u + "." + k;
    }

    public String get(Secret s, UUID u, String k) {
        return eco.kv().get(key(s, u, k));
    }

    public String get(Secret s, UUID u, String k, String def) {
        return eco.kv().get(key(s, u, k), def);
    }

    public long getLong(Secret s, UUID u, String k, long def) {
        return eco.kv().getLong(key(s, u, k), def);
    }

    public void set(Secret s, UUID u, String k, Object v) {
        eco.kv().set(key(s, u, k), v);
    }

    public void remove(Secret s, UUID u, String k) {
        eco.kv().remove(key(s, u, k));
    }

    public int stage(Secret s, UUID u) {
        return (int) getLong(s, u, "stage", 0);
    }

    public void stage(Secret s, UUID u, int stage) {
        if (stage <= 0) remove(s, u, "stage");
        else set(s, u, "stage", stage);
        set(s, u, "since", System.currentTimeMillis());
    }

    /** When the current stage began (ms). */
    public long since(Secret s, UUID u) {
        return getLong(s, u, "since", 0);
    }

    /** Forgets everything saved for this player in this secret. */
    public void reset(Secret s, UUID u) {
        s.cleanup(u);
        eco.kv().removePrefix("secret." + s.id() + "." + u + ".");
    }

    /** Players with a stage in this secret (online only). */
    public List<Player> active(Secret s) {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (stage(s, p.getUniqueId()) > 0) out.add(p);
        return out;
    }

    public boolean enabled(Secret s) {
        String sw = eco.kv().get("secret." + s.id() + ".switch");
        if (sw != null) return sw.equals("on");
        return s.cfg.getBoolean("enabled", true);
    }

    public boolean closed(Secret s) {
        return eco.kv().has("secret." + s.id() + ".first");
    }

    public Optional<UUID> solver(Secret s) {
        String v = eco.kv().get("secret." + s.id() + ".first");
        try {
            return v == null ? Optional.empty() : Optional.of(UUID.fromString(v));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------------------------------
    // test mode and time
    // ------------------------------------------------------------------------------------------

    public boolean test(UUID u) {
        return eco.kv().has("secret.test." + u);
    }

    /** A "day" for this player: a real day, or test-day-seconds in test mode. */
    public long day(UUID u) {
        return test(u) ? testDay : 86_400_000L;
    }

    /** A chance (0..1) for this player: 1 in test mode. */
    public boolean roll(UUID u, double chance) {
        return test(u) || ThreadLocalRandom.current().nextDouble() < chance;
    }

    /** Whether now (Korean time) is within [from, to) hours (wrapping past midnight); always true in test mode. */
    public boolean hours(UUID u, int from, int to) {
        if (test(u)) return true;
        int h = ZonedDateTime.now(KST).getHour();
        return from <= to ? h >= from && h < to : h >= from || h < to;
    }

    /** Today's date (Korean time) as a key; in test mode, the test "day" number. */
    public String today(UUID u) {
        return test(u) ? "t" + System.currentTimeMillis() / testDay : LocalDate.now(KST).toString();
    }

    // ------------------------------------------------------------------------------------------
    // the end
    // ------------------------------------------------------------------------------------------

    /**
     * Solved: the rewards in the secret's {@code rewards} section, the anonymous line to everyone, and the secret closes for
     * everyone else. A tester gets the rewards (not the money) and nothing closes.
     */
    public void solve(Secret s, Player p) {
        UUID u = p.getUniqueId();
        boolean test = test(u);
        if (!test && closed(s)) {
            p.sendMessage(Text.msg(s.cfg.getString("too-late", "<dark_gray>…누군가 먼저였다.")));
            return;
        }
        rewards(p, s.cfg.getConfigurationSection("rewards"), test);
        stage(s, u, s.stages() + 1);
        set(s, u, "done", System.currentTimeMillis());
        if (test) {
            p.sendMessage(Text.msg("<yellow>[테스트] " + s.name() + " 완료 (서버는 닫히지 않고 공지도 없음, 돈은 안 줌)"));
            return;
        }
        eco.kv().set("secret." + s.id() + ".first", u.toString());
        String line = s.cfg.getString("solved-line", "<dark_gray>누군가 " + s.name() + "의 비밀을 풀었다…");
        Bukkit.getScheduler().runTaskLater(eco, () -> Bukkit.broadcast(Text.msg(line)), 60L);
        eco.getLogger().info("[비밀] " + s.name() + " 을(를) " + p.getName() + " 이(가) 처음 풀었습니다");
    }

    /** money, title, cosmetics: [ids], items: ["id amount"] */
    public void rewards(Player p, ConfigurationSection r, boolean test) {
        if (r == null) return;
        UUID u = p.getUniqueId();
        List<String> got = new ArrayList<>();
        long money = r.getLong("money", 0);
        if (money > 0 && !test) {
            core.economy().deposit(u, money, "secret");
            got.add(String.format("%,d G", money));
        }
        String title = r.getString("title", "");
        if (!title.isEmpty() && core.profiles() != null) {
            core.profiles().grantTitle(u, title);
            got.add(core.profiles().title(title).map(t -> t.name()).orElse(title));
        }
        for (String id : r.getStringList("cosmetics")) {
            var c = core.content().item(id).filter(i -> i.cosmetic() != null);
            if (c.isEmpty() || core.cosmetics() == null) {
                eco.getLogger().warning("[비밀] 없는 치장: " + id);
                continue;
            }
            core.cosmetics().grant(p, c.get(), true);
            core.cosmetics().celebrate(p, c.get());
            got.add(c.get().name() == null ? id : c.get().name());
        }
        for (String line : r.getStringList("items")) {
            String[] w = line.trim().split("\\s+");
            int n = w.length > 1 ? Integer.parseInt(w[1]) : 1;
            Items.create(w[0], n).ifPresentOrElse(it -> {
                for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
                got.add(Items.mmName(it));
            }, () -> eco.getLogger().warning("[비밀] 없는 아이템: " + w[0]));
        }
        if (!got.isEmpty()) {
            p.sendMessage(Text.msg("<gray>받은 것: " + String.join("<gray>, </gray>", got)));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 0.7f);
        }
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 비밀
    // ------------------------------------------------------------------------------------------

    public static final String USAGE = "목록 | 켜기·끄기 <비밀> | 시작 <비밀> [플레이어] | 단계 <비밀> <플레이어> <n> | 연출 <비밀> <장면> [플레이어] | "
            + "상태 <플레이어> | 초기화 <비밀> <플레이어> | 서버초기화 <비밀> | 테스트 [켜기|끄기] [플레이어]";

    public void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "목록" : a[0];
        switch (sub) {
            case "목록" -> {
                s.sendMessage(Text.msg("<gold>숨은 비밀 <gray>(/관리자 비밀 " + USAGE + ")"));
                for (Secret x : secrets.values()) {
                    String who = solver(x).map(u -> Optional.ofNullable(Bukkit.getOfflinePlayer(u).getName()).orElse(u.toString())).orElse("-");
                    s.sendMessage(Text.msg(" <white>" + x.name() + " <dark_gray>(" + x.id() + ") " + (enabled(x) ? "<green>켜짐" : "<red>꺼짐")
                            + " <gray>· 푼 사람 <white>" + who + " <gray>· 진행 중(접속) <white>" + active(x).size()));
                }
            }
            case "켜기", "끄기" -> withSecret(s, a, 1, x -> {
                eco.kv().set("secret." + x.id() + ".switch", sub.equals("켜기") ? "on" : "off");
                if (sub.equals("끄기")) for (Player p : Bukkit.getOnlinePlayers()) x.cleanup(p.getUniqueId());
                s.sendMessage(Text.msg("<green>" + x.name() + ": " + (sub.equals("켜기") ? "켰어요" : "껐어요 (진행 기록은 그대로, 다시 켜면 이어서)")));
            });
            case "시작" -> withSecret(s, a, 1, x -> online(s, a.length > 2 ? a[2] : null, p -> {
                x.cleanup(p.getUniqueId());
                x.start(p);
                s.sendMessage(Text.msg("<green>" + p.getName() + ": " + x.name() + " 시작 (단계 " + stage(x, p.getUniqueId()) + "/" + x.stages() + ")"));
            }));
            case "단계" -> withSecret(s, a, 1, x -> {
                if (a.length < 4) {
                    s.sendMessage(Text.msg("<red>/관리자 비밀 단계 <비밀> <플레이어> <n>"));
                    return;
                }
                int n;
                try {
                    n = Integer.parseInt(a[3]);
                } catch (NumberFormatException e) {
                    s.sendMessage(Text.msg("<red>숫자로"));
                    return;
                }
                online(s, a[2], p -> {
                    x.cleanup(p.getUniqueId());
                    x.jump(p, Math.max(0, Math.min(x.stages(), n)));
                    s.sendMessage(Text.msg("<green>" + p.getName() + ": " + x.name() + " 단계 " + stage(x, p.getUniqueId()) + "/" + x.stages()));
                });
            });
            case "연출" -> withSecret(s, a, 1, x -> {
                if (a.length < 3) {
                    s.sendMessage(Text.msg("<gray>" + x.name() + " 장면: <white>" + String.join(", ", x.scenes())));
                    return;
                }
                online(s, a.length > 3 ? a[3] : null, p -> {
                    if (!x.scene(s, p, a[2])) s.sendMessage(Text.msg("<red>없는 장면. 있는 것: " + String.join(", ", x.scenes())));
                });
            });
            case "상태" -> {
                if (a.length < 2) {
                    s.sendMessage(Text.msg("<red>/관리자 비밀 상태 <플레이어>"));
                    return;
                }
                Players.find(a[1], found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Text.msg("<red>없는 플레이어"));
                        return;
                    }
                    UUID u = found.get();
                    s.sendMessage(Text.msg("<gold>" + a[1] + " <gray>테스트 모드 " + (test(u) ? "<yellow>켜짐" : "<white>꺼짐")));
                    for (Secret x : secrets.values()) {
                        int st = stage(x, u);
                        String extra = x.status(u);
                        s.sendMessage(Text.msg(" <white>" + x.name() + " <gray>단계 <white>" + (st > x.stages() ? "완료" : st + "/" + x.stages())
                                + (extra.isEmpty() ? "" : " <gray>· " + extra)));
                    }
                });
            }
            case "초기화" -> withSecret(s, a, 1, x -> {
                if (a.length < 3) {
                    s.sendMessage(Text.msg("<red>/관리자 비밀 초기화 <비밀> <플레이어>"));
                    return;
                }
                Players.find(a[2], found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Text.msg("<red>없는 플레이어"));
                        return;
                    }
                    reset(x, found.get());
                    s.sendMessage(Text.msg("<green>" + a[2] + ": " + x.name() + " 기록을 지웠어요 (처음부터)"));
                });
            });
            case "서버초기화" -> withSecret(s, a, 1, x -> {
                eco.kv().remove("secret." + x.id() + ".first");
                s.sendMessage(Text.msg("<green>" + x.name() + ": 다시 열었어요 (푼 사람 기록만 지움, 각자의 진행은 그대로)"));
            });
            case "테스트" -> {
                String mode = a.length > 1 && (a[1].equals("켜기") || a[1].equals("끄기")) ? a[1] : null;
                String who = mode == null ? (a.length > 1 ? a[1] : null) : (a.length > 2 ? a[2] : null);
                online(s, who, p -> {
                    boolean on = mode == null ? !test(p.getUniqueId()) : mode.equals("켜기");
                    if (on) eco.kv().set("secret.test." + p.getUniqueId(), 1);
                    else eco.kv().remove("secret.test." + p.getUniqueId());
                    s.sendMessage(Text.msg("<green>" + p.getName() + ": 비밀 테스트 모드 " + (on ? "<yellow>켜짐</yellow> (하루 = " + testDay / 1000
                            + "초, 확률 100%, 시간 조건 없음, 풀어도 서버에 안 닫힘)" : "꺼짐")));
                });
            }
            default -> s.sendMessage(Text.msg("<red>/관리자 비밀 " + USAGE));
        }
    }

    public List<String> adminComplete(CommandSender s, String[] a) {
        List<String> names = secrets.values().stream().map(Secret::name).map(n -> n.replace(" ", "")).toList();
        if (a.length == 1) return List.of("목록", "켜기", "끄기", "시작", "단계", "연출", "상태", "초기화", "서버초기화", "테스트");
        String sub = a[0];
        if (a.length == 2) return switch (sub) {
            case "상태" -> Players.complete(s, a[1]);
            case "테스트" -> List.of("켜기", "끄기");
            case "목록" -> List.of();
            default -> names;
        };
        if (a.length == 3) {
            if (sub.equals("연출")) return find(a[1]).map(Secret::scenes).orElse(List.of());
            return Players.complete(s, a[2]);
        }
        if (a.length == 4 && sub.equals("연출")) return Players.complete(s, a[3]);
        if (a.length == 4 && sub.equals("단계")) return find(a[1]).map(x -> {
            List<String> n = new ArrayList<>();
            for (int i = 0; i <= x.stages(); i++) n.add(String.valueOf(i));
            return n;
        }).orElse(List.of());
        return List.of();
    }

    private void withSecret(CommandSender s, String[] a, int at, java.util.function.Consumer<Secret> then) {
        Optional<Secret> x = a.length > at ? find(a[at]) : Optional.empty();
        if (x.isEmpty()) {
            s.sendMessage(Text.msg("<red>비밀 이름: " + String.join(", ", secrets.values().stream().map(Secret::name).toList())));
            return;
        }
        then.accept(x.get());
    }

    private static void online(CommandSender s, String name, java.util.function.Consumer<Player> then) {
        Player p = name == null ? (s instanceof Player me ? me : null) : Bukkit.getPlayerExact(name);
        if (p == null) {
            s.sendMessage(Text.msg("<red>" + (name == null ? "플레이어 이름을 쓰세요" : "접속 중인 플레이어가 아니에요")));
            return;
        }
        then.accept(p);
    }
}
