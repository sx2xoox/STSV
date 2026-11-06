package dev.stsv.economy.mission;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.profile.ProfileModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.calendar.DailyListener;
import dev.stsv.economy.event.EventModule;
import dev.stsv.economy.job.JobSpec;
import dev.stsv.economy.mission.MissionSpec.Kind;
import dev.stsv.economy.mission.MissionSpec.Mission;
import dev.stsv.economy.mission.MissionSpec.Period;
import dev.stsv.economy.mission.MissionStore.Slot;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 미션 /미션: every player has their own daily and weekly missions (contents/economy/missions.yml,
 * see {@link MissionSpec}), measured by how much an all-time stat grew since the mission was given.
 * Checked every 2 seconds for online players; a finished mission pays at once (items that do not fit
 * go to the mailbox), finishing the whole set pays the bonus. A mission can be swapped a few times per
 * period (right click in /미션). The set of a day is given the first time the player is seen that day.
 */
public final class MissionModule extends EcoModule implements DailyListener {

    static final String MENU = "economy:missions";
    private static final String SIDEBAR = "eco-mission";

    private MissionSpec spec;
    private MissionStore store;
    /** online players: their current day and week (replaced when a period turns) */
    private final Map<UUID, MissionStore.State> states = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();

    public MissionModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-missions";
    }

    @Override
    protected void start() {
        store = new MissionStore(core.db());
        store.init();
        load();
        eco.calendar().onDaily(this);
        eco.onReset(() -> {
            store.reset();
            states.clear();
        });
        every(40L, this::check);
        core.hud().sidebar(SIDEBAR, 21, this::sidebar);
        core.admin().add(EcoPlugin.ADMIN_OWNER, "미션", "정보|완료|새로뽑기 <플레이어> [일일1~3|주간1~3]",
                "미션 보기 / 하나 완료 처리(보상 지급) / 오늘·이번 주 미션을 지우고 다시 뽑기", this::admin, this::adminComplete);
    }

    @Override
    protected void stop() {
        core.hud().removeSidebar(SIDEBAR);
        states.clear();
        loading.clear();
    }

    @Override
    public void reload() {
        load();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = MissionSpec.parse(eco.data("missions.yml"), problems);
        for (Kind k : List.of(spec.daily(), spec.weekly())) {
            for (Mission m : k.missions().values()) {
                if (m.job() != null && !eco.jobs().spec().jobs().containsKey(m.job())) problems.add(m.id() + ": 없는 직업 " + m.job());
                for (MissionSpec.ItemReward i : m.reward().items()) if (Items.create(i.id(), 1).isEmpty()) problems.add(m.id() + ": 없는 아이템 " + i.id());
            }
        }
        problems.forEach(p -> eco.getLogger().warning("[missions.yml] " + p));
    }

    public MissionSpec spec() {
        return spec;
    }

    private String key(Period p) {
        return p.key(p == Period.DAILY ? eco.calendar().day() : eco.calendar().week());
    }

    private MissionStore.Set set(MissionStore.State s, Period p) {
        return p == Period.DAILY ? s.daily() : s.weekly();
    }

    /** The mission of a slot (null if the pool no longer has it). */
    private Mission mission(Period p, Slot s) {
        return spec.kind(p).missions().get(s.mission);
    }

    /** How far the player is: the stat's growth since the mission was given. */
    public double progress(UUID u, Mission m, Slot s) {
        return Math.max(0, core.profiles().stat(u, m.stat()) - s.base);
    }

    // ------------------------------------------------------------------------------------------
    // The 2-second check: load the period, give a set, finish missions
    // ------------------------------------------------------------------------------------------

    @Override
    public void newDay(int day, long dayStart) {
        store.prune(Period.DAILY.key(day), Period.WEEKLY.key(eco.calendar().week()));
        check();
    }

    private void check() {
        String day = key(Period.DAILY), week = key(Period.WEEKLY);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            MissionStore.State s = states.get(u);
            if (s == null || !s.daily().period.equals(day) || !s.weekly().period.equals(week)) {
                if (loading.add(u)) {
                    store.load(u, day, week).whenComplete((st, err) -> sync(() -> {
                        loading.remove(u);
                        if (err != null) eco.getLogger().warning("미션 불러오기 실패 (" + p.getName() + "): " + err);
                        else if (Bukkit.getPlayer(u) != null) states.put(u, st);
                    }));
                }
                continue;
            }
            if (!core.profiles().statsLoaded(u)) continue;
            for (Period period : Period.values()) {
                MissionStore.Set set = set(s, period);
                if (set.slots.isEmpty()) give(p, period, set);
                finish(p, period, set);
            }
        }
    }

    /** The player's first visit in the period: draws their set, counting from now. */
    private void give(Player p, Period period, MissionStore.Set set) {
        List<Mission> drawn = MissionSpec.draw(spec.kind(period), eco.jobs().job(p.getUniqueId()), ThreadLocalRandom.current());
        if (drawn.isEmpty()) return;
        for (int i = 0; i < drawn.size(); i++) {
            Mission m = drawn.get(i);
            store.put(p.getUniqueId(), set, i, m.id(), core.profiles().stat(p.getUniqueId(), m.stat()));
        }
        p.sendMessage(Lang.msg(period == Period.DAILY ? "mission.new-daily" : "mission.new-weekly", "count", drawn.size())
                .clickEvent(ClickEvent.runCommand("/미션")));
    }

    private void finish(Player p, Period period, MissionStore.Set set) {
        UUID u = p.getUniqueId();
        for (Slot s : set.slots) {
            if (s.done()) continue;
            Mission m = mission(period, s);
            if (m == null || progress(u, m, s) + 1e-9 < m.goal()) continue;
            complete(p, period, set, s, m);
        }
        if (set.allDone() && set.bonusAt <= 0) bonus(p, period, set);
    }

    private void complete(Player p, Period period, MissionStore.Set set, Slot s, Mission m) {
        UUID u = p.getUniqueId();
        s.doneAt = now();
        store.save(u, set.period, s);
        core.profiles().add(u, period == Period.DAILY ? "missions_daily" : "missions_weekly", 1);
        List<String> got = pay(p, m.reward(), "mission");
        p.sendMessage(Lang.msg("mission.done", "kind", Lang.raw(period == Period.DAILY ? "mission.daily" : "mission.weekly"),
                "name", m.name(), "rewards", String.join("<gray>, </gray>", got), "done", set.doneCount(), "count", set.slots.size()));
        HudModule.flash(p, Lang.mm("mission.done-flash", "name", m.name()));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    private void bonus(Player p, Period period, MissionStore.Set set) {
        UUID u = p.getUniqueId();
        set.bonusAt = now();
        store.saveSet(u, set);
        core.profiles().add(u, period == Period.DAILY ? "missions_daily_all" : "missions_weekly_all", 1);
        List<String> got = pay(p, spec.kind(period).bonus(), "mission_bonus");
        boolean daily = period == Period.DAILY;
        p.showTitle(net.kyori.adventure.title.Title.title(Lang.mm(daily ? "mission.bonus-title-daily" : "mission.bonus-title-weekly"),
                Text.mm(String.join("<gray>, </gray>", got))));
        p.sendMessage(Lang.msg(daily ? "mission.bonus-daily" : "mission.bonus-weekly", "rewards", String.join("<gray>, </gray>", got)));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        if (!daily) Bukkit.broadcast(Lang.msg("mission.bonus-weekly-broadcast", "player", p.getName()));
    }

    /** Pays a reward; returns its parts as MiniMessage. */
    private List<String> pay(Player p, MissionSpec.Reward r, String reason) {
        UUID u = p.getUniqueId();
        if (r.gold() > 0) core.economy().deposit(u, r.gold(), reason);
        String job = eco.jobs().job(u);
        if (r.xp() > 0 && job != null) eco.jobs().addXp(u, job, r.xp(), false);
        for (MissionSpec.ItemReward i : r.items()) Items.create(i.id(), i.amount()).ifPresent(it -> eco.mail().deliver(u, it, reason));
        return rewardLines(r, job);
    }

    /** Reward parts as MiniMessage (the XP line names the job the player has now). */
    List<String> rewardLines(MissionSpec.Reward r, String job) {
        List<String> out = new ArrayList<>();
        if (r.gold() > 0) out.add("<gold>" + EconomyModule.format(r.gold()) + " G");
        if (r.xp() > 0) {
            JobSpec.Job j = job == null ? null : eco.jobs().spec().jobs().get(job);
            out.add(j == null ? Lang.raw("mission.reward-xp-none", "xp", fmt(r.xp()))
                    : Lang.raw("mission.reward-xp", "color", j.color(), "job", j.name(), "xp", fmt(r.xp())));
        }
        for (MissionSpec.ItemReward i : r.items()) out.add("<white>" + Items.mmName(i.id()) + " ×" + i.amount());
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%,d", (long) Math.floor(v + 1e-9));
    }

    /** "12 / 64개", playtime as hours and minutes. */
    static String amount(Mission m, double v) {
        if (m.stat().equals("playtime")) return ProfileModule.format("playtime", v);
        return fmt(v) + m.unit();
    }

    // ------------------------------------------------------------------------------------------
    // Swapping a mission
    // ------------------------------------------------------------------------------------------

    /** Returns null on success, else the refusal key. */
    String reroll(Player p, Period period, int index) {
        UUID u = p.getUniqueId();
        MissionStore.State st = states.get(u);
        if (st == null || !core.profiles().statsLoaded(u)) return "mission.loading";
        MissionStore.Set set = set(st, period);
        if (index >= set.slots.size()) return "mission.loading";
        Slot s = set.slots.get(index);
        if (s.done()) return "mission.reroll-done";
        Kind k = spec.kind(period);
        if (set.rerolls >= k.rerolls()) return "mission.reroll-none";
        List<Mission> current = new ArrayList<>();
        for (Slot x : set.slots) {
            Mission m = mission(period, x);
            // a mission the pool no longer has: stands in as itself so nothing else takes its place by accident
            current.add(m != null ? m : new Mission(x.mission, x.mission, "", 1, null, x.mission, "", "minecraft:paper", 1, MissionSpec.Reward.NONE));
        }
        Mission next = MissionSpec.reroll(k, current, index, ThreadLocalRandom.current());
        if (next == null) return "mission.reroll-empty";
        Mission old = current.get(index);
        set.rerolls++;
        store.saveSet(u, set);
        store.put(u, set, index, next.id(), core.profiles().stat(u, next.stat()));
        p.sendMessage(Lang.msg("mission.rerolled", "old", old.name(), "new", next.name(), "left", k.rerolls() - set.rerolls));
        p.playSound(p.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.7f, 1.2f);
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // /미션, sidebar
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        core.menus().open(p, MENU, new View());
    }

    private Component sidebar(Player p) {
        if (!eco.getConfig().getBoolean("hud.missions")) return null;
        MissionStore.State s = states.get(p.getUniqueId());
        if (s == null || s.daily().slots.isEmpty()) return null;
        return Lang.mm("mission.hud", "daily", s.daily().doneCount(), "daily_count", s.daily().slots.size(),
                "weekly", s.weekly().doneCount(), "weekly_count", s.weekly().slots.size());
    }

    private final class View implements MenuHandler {

        @Override
        public Map<String, String> placeholders(MenuView v) {
            Map<String, String> m = new HashMap<>();
            MissionStore.State s = states.get(v.player().getUniqueId());
            long now = now();
            var d = eco.calendar().days();
            m.put("daily_left", EventModule.hoursText(d.nextDayStart(now) - now));
            m.put("weekly_left", EventModule.hoursText(d.next(DayOfWeek.MONDAY, now) - now));
            for (Period p : Period.values()) {
                String n = p == Period.DAILY ? "daily" : "weekly";
                MissionStore.Set set = s == null ? null : set(s, p);
                m.put(n + "_done", String.valueOf(set == null ? 0 : set.doneCount()));
                m.put(n + "_count", String.valueOf(set == null || set.slots.isEmpty() ? spec.kind(p).count() : set.slots.size()));
                m.put(n + "_rerolls", String.valueOf(Math.max(0, spec.kind(p).rerolls() - (set == null ? 0 : set.rerolls))));
            }
            return m;
        }

        /** Daily missions fill the first row of the list, weekly the second. */
        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            UUID u = v.player().getUniqueId();
            MissionStore.State s = states.get(u);
            for (Period period : Period.values()) {
                int count = spec.kind(period).count();
                for (int i = 0; i < count; i++) {
                    Slot slot = s == null || i >= set(s, period).slots.size() ? null : set(s, period).slots.get(i);
                    out.add(slot == null ? waiting(s) : card(u, period, set(s, period), slot));
                }
            }
            return out;
        }

        private ItemStack waiting(MissionStore.State s) {
            ItemStack it = new ItemStack(Material.GRAY_DYE);
            it.editMeta(meta -> {
                meta.itemName(Lang.item(s == null ? "mission.loading-name" : "mission.empty-name"));
                meta.addItemFlags(ItemFlag.values());
            });
            return it;
        }

        private ItemStack card(UUID u, Period period, MissionStore.Set set, Slot slot) {
            Mission m = mission(period, slot);
            if (m == null) {
                ItemStack it = new ItemStack(Material.BARRIER);
                it.editMeta(meta -> meta.itemName(Lang.item("mission.gone-name", "id", slot.mission)));
                return it;
            }
            ItemStack it = Items.create(m.icon(), 1).orElseGet(() -> new ItemStack(Material.PAPER));
            double have = slot.done() ? m.goal() : Math.min(m.goal(), progress(u, m, slot));
            String job = eco.jobs().job(u);
            it.editMeta(meta -> {
                meta.itemName(Lang.item(slot.done() ? "mission.name-done" : "mission.name", "name", m.name()));
                List<Component> lore = new ArrayList<>();
                if (m.job() != null) {
                    JobSpec.Job j = eco.jobs().spec().jobs().get(m.job());
                    lore.add(Lang.item("mission.lore-job", "color", j == null ? "white" : j.color(), "job", j == null ? m.job() : j.name()));
                }
                lore.add(Lang.item("mission.lore-progress", "bar", bar(have / m.goal()), "have", amount(m, have), "goal", amount(m, m.goal())));
                lore.add(Text.item(""));
                lore.add(Lang.item("mission.lore-rewards"));
                for (String r : rewardLines(m.reward(), job)) lore.add(Text.item("<dark_gray> · </dark_gray>" + r));
                lore.add(Text.item(""));
                if (slot.done()) lore.add(Lang.item("mission.lore-done"));
                else {
                    int left = spec.kind(period).rerolls() - set.rerolls;
                    lore.add(Lang.item(left > 0 ? "mission.lore-reroll" : "mission.lore-reroll-none", "left", Math.max(0, left)));
                }
                meta.lore(lore);
                meta.setEnchantmentGlintOverride(slot.done());
                meta.addItemFlags(ItemFlag.values());
            });
            it.setAmount(1);
            return it;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (!click.isRightClick()) return;
            int daily = spec.daily().count();
            Period period = index < daily ? Period.DAILY : Period.WEEKLY;
            int i = index < daily ? index : index - daily;
            if (i >= spec.kind(period).count()) return;
            String refused = reroll(v.player(), period, i);
            if (refused != null) HudModule.flash(v.player(), Lang.mm(refused));
            v.refresh();
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button button, ItemStack base) {
            Period period = switch (button.action()) {
                case "mission:daily-bonus" -> Period.DAILY;
                case "mission:weekly-bonus" -> Period.WEEKLY;
                default -> null;
            };
            if (period == null) return base;
            MissionStore.State s = states.get(v.player().getUniqueId());
            MissionStore.Set set = s == null ? null : set(s, period);
            Kind k = spec.kind(period);
            ItemStack it = base.clone();
            it.editMeta(meta -> {
                List<Component> lore = new ArrayList<>(Lang.items("mission.bonus-lore", "count", set == null || set.slots.isEmpty() ? k.count() : set.slots.size()));
                for (String r : rewardLines(k.bonus(), eco.jobs().job(v.player().getUniqueId()))) lore.add(Text.item("<dark_gray> · </dark_gray>" + r));
                lore.add(Text.item(""));
                boolean got = set != null && set.bonusAt > 0;
                lore.add(got ? Lang.item("mission.bonus-lore-done")
                        : Lang.item("mission.bonus-lore-progress", "done", set == null ? 0 : set.doneCount(),
                        "count", set == null || set.slots.isEmpty() ? k.count() : set.slots.size()));
                meta.lore(lore);
                meta.setEnchantmentGlintOverride(got);
            });
            return it;
        }
    }

    static String bar(double progress) {
        int cells = 10;
        int full = (int) Math.floor(Math.max(0, Math.min(1, progress)) * cells + 1e-9);
        return "<green>" + "■".repeat(full) + "<dark_gray>" + "□".repeat(cells - full);
    }

    // ------------------------------------------------------------------------------------------
    // Quit, admin
    // ------------------------------------------------------------------------------------------

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    private void admin(org.bukkit.command.CommandSender s, String[] args) {
        String usage = "<red>/관리자 미션 정보|완료|새로뽑기 <플레이어> [일일1~3|주간1~3]";
        if (args.length < 2) {
            s.sendMessage(Text.msg(usage));
            return;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        MissionStore.State st = p == null ? null : states.get(p.getUniqueId());
        if (st == null) {
            s.sendMessage(Text.msg("<red>접속 중이고 미션을 불러온 플레이어만 됩니다."));
            return;
        }
        UUID u = p.getUniqueId();
        switch (args[0]) {
            case "정보" -> {
                for (Period period : Period.values()) {
                    MissionStore.Set set = set(st, period);
                    s.sendMessage(Text.msg("<gold>" + p.getName() + " " + (period == Period.DAILY ? "일일" : "주간") + " " + set.period
                            + " <gray>완료 " + set.doneCount() + "/" + set.slots.size() + " · 다시 뽑기 " + set.rerolls + "/" + spec.kind(period).rerolls()
                            + " · 보너스 " + (set.bonusAt > 0 ? "받음" : "아직")));
                    for (Slot x : set.slots) {
                        Mission m = mission(period, x);
                        s.sendMessage(Text.msg(" <gray>" + (x.index + 1) + ". " + (m == null ? x.mission + " (없음)"
                                : m.name() + " <white>" + amount(m, Math.min(m.goal(), progress(u, m, x))) + " / " + amount(m, m.goal()))
                                + (x.done() ? " <green>완료" : "")));
                    }
                }
            }
            case "완료" -> {
                Period period = args.length >= 3 && args[2].startsWith("주간") ? Period.WEEKLY : Period.DAILY;
                int i = args.length >= 3 ? parseIndex(args[2]) : -1;
                MissionStore.Set set = set(st, period);
                if (i < 0 || i >= set.slots.size()) {
                    s.sendMessage(Text.msg("<red>일일1~" + set.slots.size() + " 또는 주간1~" + st.weekly().slots.size()));
                    return;
                }
                Slot x = set.slots.get(i);
                Mission m = mission(period, x);
                if (x.done() || m == null) {
                    s.sendMessage(Text.msg("<yellow>이미 완료했거나 없는 미션입니다."));
                    return;
                }
                complete(p, period, set, x, m);
                if (set.allDone() && set.bonusAt <= 0) bonus(p, period, set);
                s.sendMessage(Text.msg("<green>완료 처리: " + m.name()));
            }
            case "새로뽑기" -> {
                // nothing is read or given until the rows are gone (the database may run writes side by side)
                states.remove(u);
                loading.add(u);
                store.clear(u, st.daily().period, st.weekly().period).whenComplete((x, err) -> sync(() -> {
                    loading.remove(u);
                    s.sendMessage(Text.msg(err == null ? "<green>" + p.getName() + "의 오늘·이번 주 미션을 지웠습니다. 곧 새로 뽑힙니다."
                            : "<red>지우지 못했습니다: " + err));
                }));
            }
            default -> s.sendMessage(Text.msg(usage));
        }
    }

    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", "")) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private List<String> adminComplete(org.bukkit.command.CommandSender s, String[] args) {
        if (args.length == 1) return List.of("정보", "완료", "새로뽑기");
        if (args.length == 2) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 3 && args[0].equals("완료")) return List.of("일일1", "일일2", "일일3", "주간1", "주간2", "주간3");
        return List.of();
    }
}
