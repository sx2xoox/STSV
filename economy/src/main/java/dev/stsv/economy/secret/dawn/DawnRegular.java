package dev.stsv.economy.secret.dawn;

import dev.stsv.core.util.Text;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.store.StoreModule;
import dev.stsv.economy.store.StoreVisit;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 새벽 단골 (docs/admin/숨은_비밀/새벽단골.md; contents/economy/secrets/dawn.yml; never in the README). Only for a 편의점
 * worker with a long night-shift history: at dawn a quiet man comes in on a night shift and buys one 바나나우유, exact coins,
 * once a day; outside, the bottle is left on the parasol table, never drunk. Over four dawns he says a little more (his
 * daughter waited at that table when he came off his own night shift here, long ago). On the fourth he leaves the bottle on
 * the counter for "someone who will come"; then a woman comes off her own night shift: handing it to her ends it (she knew
 * it at once; he is seen a moment at the table outside, then gone). Solved: the title.
 * <p>
 * The store plays the visits ({@link StoreVisit}); this keeps the stage (eco.kv "secret.dawn.&lt;uuid&gt;.": stage, since,
 * day = the day he last came). No stone on the monument (the newcomer stays the thirteenth).
 */
public final class DawnRegular extends Secret implements StoreVisit.Visitor {

    /** who has the bottle waiting on the counter now (in memory: a shift that ends takes it away) */
    private final Set<UUID> waiting = new HashSet<>();
    private StoreModule registered;

    public DawnRegular(SecretModule m) {
        super(m);
        hook();
    }

    private void hook() {
        StoreModule s = eco.store();
        if (s != null && s != registered) {
            s.visitor(id(), this);
            registered = s;
        }
    }

    @Override
    public String id() {
        return "dawn";
    }

    @Override
    public String name() {
        return "새벽단골";
    }

    @Override
    public boolean hasStone() {
        return false;
    }

    /** 1..4 his visits, 5 the bottle waiting for her. */
    @Override
    public int stages() {
        return DawnStory.WAITING;
    }

    @Override
    public void tick() {
        hook();
    }

    private String text(String key) {
        return cfg.getString(key, "");
    }

    private int stage(UUID u) {
        return m.stage(this, u);
    }

    // ------------------------------------------------------------------------------------------ the visits

    @Override
    public StoreVisit.Visit due(Player worker, String store, int hour, long nightServed, boolean nightShift) {
        UUID u = worker.getUniqueId();
        if (!open(u)) return null;
        boolean test = m.test(u);
        int from = cfg.getInt("start.hours.from", 3), to = cfg.getInt("start.hours.to", 5);
        int st = stage(u);
        if (st > stages()) return null;   // solved
        if (st == 0) {
            if (!DawnStory.starts(test, nightShift, hour, from, to, nightServed, cfg.getLong("start.night-served", 150))) return null;
            if (!m.roll(u, cfg.getDouble("start.chance", 0.25))) return null;
            m.stage(this, u, 1);
            st = 1;
        } else if (st == DawnStory.WAITING) {
            if (!waiting.contains(u)) {   // the bottle went with the last shift: he comes again
                m.stage(this, u, DawnStory.missed(st));
                return null;
            }
            return visit(DawnStory.WAITING);
        } else {
            if (!DawnStory.comes(test, nightShift, hour, from, to, m.get(this, u, "day"), m.today(u))) return null;
            if (!m.roll(u, cfg.getDouble("visit-chance", 0.5))) return null;
        }
        return visit(st);
    }

    /** A visit of a stage as the store plays it (its settings' section: kind, skin, name, lines, buttons). */
    StoreVisit.Visit visit(int stage) {
        String key = DawnStory.key(stage);
        ConfigurationSection c = cfg.getConfigurationSection(key);
        boolean daughter = stage >= DawnStory.WAITING;
        Map<String, List<String>> talk = new LinkedHashMap<>();
        if (c != null) for (String s : List.of("enter", "browse", "pay", "thanks", "wait")) {
            List<String> l = c.getStringList(s);
            if (!l.isEmpty()) talk.put(s, l);
        }
        Map<String, String> buttons = new LinkedHashMap<>();
        if (daughter) {
            buttons.put("give", text("daughter.buttons.give"));
            buttons.put("greet", text("daughter.buttons.greet"));
        }
        String item = cfg.getString("item", "banana_milk");
        return new StoreVisit.Visit(key, daughter ? cfg.getString("daughter.kind", "nurse") : cfg.getString("kind", "worker"),
                daughter ? null : cfg.getString("skin", "economy:entity/bus/night_regular"), daughter ? text("daughter.name") : text("name"),
                daughter ? List.of() : List.of(item), true, talk, buttons,
                stage == DawnStory.VISITS ? "economy:store_item_" + item : null,
                stage < DawnStory.VISITS ? "economy:store_item_" + item : null);
    }

    @Override
    public void paid(Player worker, String store, StoreVisit.Visit v) {
        UUID u = worker.getUniqueId();
        int st = stage(u);
        if (st < 1 || st > DawnStory.VISITS || !v.id().equals(DawnStory.key(st))) return;
        m.set(this, u, "day", m.today(u));
        int next = DawnStory.afterPaid(st);
        if (next == DawnStory.WAITING) waiting.add(u);
        m.stage(this, u, next);
        String after = text(DawnStory.key(st) + ".after");
        if (!after.isEmpty()) Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (worker.isOnline()) worker.sendMessage(Text.msg(after));
        }, 100L);
    }

    @Override
    public StoreVisit.Reply answered(Player worker, String store, StoreVisit.Visit v, String choice) {
        UUID u = worker.getUniqueId();
        if (!v.id().equals("daughter")) return new StoreVisit.Reply(List.of(), false, null, true);
        waiting.remove(u);
        if (!choice.equals("give") || stage(u) != DawnStory.WAITING) {
            m.stage(this, u, DawnStory.missed(DawnStory.WAITING));
            return new StoreVisit.Reply(cfg.getStringList("daughter.greet"), false, null, true);
        }
        List<String> lines = cfg.getStringList("daughter.give");
        long end = 70L * lines.size() + 160L;
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!worker.isOnline() || stage(u) != DawnStory.WAITING) return;
            worker.playSound(worker.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 0.9f);
            String last = text("ending");
            if (!last.isEmpty()) worker.sendMessage(Text.msg(last));
            m.solve(this, worker);
        }, end);
        return new StoreVisit.Reply(lines, true, cfg.getString("skin", "economy:entity/bus/night_regular"), true);
    }

    @Override
    public void missed(Player worker, String store, StoreVisit.Visit v) {
        if (v.id().equals("daughter")) {
            UUID u = worker.getUniqueId();
            waiting.remove(u);
            if (stage(u) == DawnStory.WAITING) m.stage(this, u, DawnStory.missed(DawnStory.WAITING));
        }
    }

    // ------------------------------------------------------------------------------------------ the module's hooks

    @Override
    public void start(Player p) {
        m.stage(this, p.getUniqueId(), 1);
        m.remove(this, p.getUniqueId(), "day");
        waiting.remove(p.getUniqueId());
        p.sendMessage(Text.msg("<gray>[새벽단골] 1단계: 야간 근무 중 새벽에 (테스트 모드면 아무 때나) 30초 안에 옴"));
    }

    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        m.remove(this, u, "day");
        if (stage == DawnStory.WAITING) waiting.add(u);
        else waiting.remove(u);
        m.stage(this, u, Math.clamp(stage, 0, stages()));
    }

    @Override
    public void cleanup(UUID player) {
        waiting.remove(player);
    }

    @Override
    public void resetting(UUID player) {
        waiting.remove(player);
    }

    @Override
    public String status(UUID u) {
        StoreModule s = eco.store();
        long night = s == null ? 0 : s.nightServed(u);
        return " · 밤 손님 " + night + "/" + cfg.getLong("start.night-served", 150) + " · 마지막 방문 " + m.get(this, u, "day", "-")
                + (waiting.contains(u) ? " · 계산대에 병" : "");
    }

    @Override
    public List<String> scenes() {
        return List.of("그림자");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        if (!word.equals("그림자")) return false;
        p.sendMessage(Text.msg("<gray>[새벽단골] 단계를 정하고 (단계 새벽단골 <플레이어> n) 야간 근무 중이면 30초 안에 그 방문이 옴. 테스트 모드면 시각 · 확률 무시"));
        List<String> lines = new ArrayList<>(cfg.getStringList("visits.1.enter"));
        if (!lines.isEmpty()) p.sendMessage(Text.msg("<dark_gray>" + lines.getFirst()));
        return true;
    }
}
