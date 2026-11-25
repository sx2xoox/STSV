package dev.stsv.economy.fishing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.hud.HudLayout;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.fishing.TournamentRules.Catch;
import dev.stsv.economy.fishing.TournamentRules.Entry;
import dev.stsv.economy.fishing.TournamentRules.Kind;
import dev.stsv.economy.fishing.TournamentRules.Target;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 낚시대회 (README "낚시대회"): every hour on the hour (config.yml time.zone), {@link TournamentRules.Settings#minutes} minutes,
 * one event ({@link Kind}) noticed a minute before. Only a rod's catch counts (FishingModule calls {@link #caught}; never a
 * trap, pen or net, never a 길라잡이 trial fish); it gets a size in its message only. While one runs every online player sees
 * the standings on the core's board (bottom right): the event, the top 5, their own place, the time left. At the end the
 * results go to chat and the prizes are paid (config.yml fishing-tournament: stars through the core's DonationModule under
 * the actor {@value #STAR_ACTOR}, capped a day; gold as a faucet "fishing_tournament").
 * <p>
 * A restart: the standings are saved to kv {@value #KV_CURRENT} (every few seconds while they change, and when the plugin
 * stops). A server back before the tournament's end resumes it for the time left; one back after it cancels it (no prizes:
 * the standings may miss the last minutes, and a crash must not be a way to end one early), noted in the history.
 */
public final class TournamentModule extends EcoModule {

    static final String KV_CURRENT = "fish.tournament.current", KV_HISTORY = "fish.tournament.history", KV_RECENT = "fish.tournament.recent",
            KV_LAST = "fish.tournament.last-hour";
    public static final String STAR_ACTOR = "system:fishing";
    static final String BOARD = "eco-fishing-tournament";
    /** The board's top places. */
    static final int TOP = 5;
    /** Results kept for /낚시대회. */
    static final int HISTORY = 5;
    /** How often changed standings are written to kv (ms). */
    static final long SAVE_MS = 5_000;

    private TournamentRules.Settings settings;
    private boolean enabled;
    private ZoneId zone;
    private final Random rnd = new Random();
    private Running cur;
    private Planned planned;
    private long lastNoticed, lastStarted;
    private boolean dirty;
    private long savedAt;

    /** The next hour's event, drawn at its notice. */
    private record Planned(long hour, Kind kind, Target target) {}

    /** The tournament running: its event, when (hour = the hour it belongs to, 0 for one an admin started), the standings. */
    private static final class Running {
        Kind kind;
        Target target;
        long start, end, hour;
        final Map<UUID, Entry> entries = new LinkedHashMap<>();
        List<Entry> ranking = List.of();
    }

    /** A catch's part in a tournament: the size grade (for the title) and the chat line (size, score, place). */
    public record Scored(String grade, Component line) {}

    public TournamentModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-fishing-tournament";
    }

    @Override
    protected void start() {
        load();
        lastStarted = eco.kv().getLong(KV_LAST, 0);
        restore();
        if (core.hud() != null) core.hud().board(BOARD, this::board);
        every(20L, this::tick);
        eco.onReset(() -> {
            cur = null;
            planned = null;
            eco.kv().remove(KV_CURRENT);
            eco.kv().remove(KV_HISTORY);
            eco.kv().remove(KV_RECENT);
            if (core.hud() != null) core.hud().refreshBoard();
        });
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    protected void stop() {
        if (cur != null) save();
        if (core.hud() != null) core.hud().removeBoard(BOARD);
    }

    private void load() {
        var c = eco.getConfig();
        enabled = c.getBoolean("fishing-tournament.enabled");
        zone = ZoneId.of(c.getString("time.zone"));
        List<Long> gold = new ArrayList<>();
        for (Object o : c.getList("fishing-tournament.gold", List.of())) gold.add(((Number) o).longValue());
        settings = new TournamentRules.Settings(Math.max(1, c.getInt("fishing-tournament.minutes")),
                Math.max(0, c.getInt("fishing-tournament.notice-seconds")), c.getInt("fishing-tournament.min-participants"),
                c.getInt("fishing-tournament.min-catches"), Math.round(c.getDouble("fishing-tournament.new-account-hours") * 3_600_000),
                c.getInt("fishing-tournament.daily-star-cap"), c.getIntegerList("fishing-tournament.stars"), gold,
                c.getInt("fishing-tournament.avoid-recent"), c.getDoubleList("fishing-tournament.grade-cuts"),
                new HashSet<>(c.getStringList("fishing-tournament.skip-habitats")));
    }

    public TournamentRules.Settings settings() {
        return settings;
    }

    public boolean running() {
        return cur != null;
    }

    private FishSpec fish() {
        return eco.fishing().spec();
    }

    // ------------------------------------------------------------------------------------------
    // The clock
    // ------------------------------------------------------------------------------------------

    private void tick() {
        long now = now();
        if (cur != null && now >= cur.end) finish();
        if (dirty && now - savedAt >= SAVE_MS) save();
        if (!enabled) return;
        switch (TournamentRules.due(now, zone, settings, lastNoticed, lastStarted)) {
            case NOTICE -> {
                long hour = TournamentRules.nextStart(now + 1, zone);
                lastNoticed = hour;
                if (cur != null && cur.end > hour) return;   // an admin's tournament still running then: that hour is skipped
                planned = plan(hour, null);
                Bukkit.broadcast(Lang.msg("tournament.notice", "title", title(planned.kind(), planned.target()),
                        "goal", goal(planned.kind(), planned.target()), "minutes", settings.minutes()));
            }
            case START -> {
                long hour = TournamentRules.hourStart(now, zone);
                markStarted(hour);
                if (cur != null) return;   // one is running (an admin's): this hour's is skipped
                Planned p = planned != null && planned.hour() == hour ? planned : plan(hour, null);
                begin(p.kind(), p.target(), now, hour + settings.durationMs(), hour);
            }
            default -> { }
        }
    }

    private void markStarted(long hour) {
        lastStarted = hour;
        eco.kv().set(KV_LAST, hour);
    }

    /** The moment of the main world (the event must be one that can be held now). */
    private FishSpec.Moment moment() {
        World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (w == null) return new FishSpec.Moment(6000, false, false, 1);
        return new FishSpec.Moment(w.getTime(), w.hasStorm(), w.isThundering(), (int) ((w.getFullTime() / 24000) % 8));
    }

    /** The event of a tournament: the one asked for (an admin's), else drawn (never one of the last ones); its target drawn. */
    private Planned plan(long hour, Kind asked) {
        FishSpec.Moment m = moment();
        List<Kind> avail = TournamentRules.available(fish(), m, settings);
        Kind k = asked != null ? asked : TournamentRules.pick(avail, recent(), settings.avoidRecent(), rnd);
        Target t = Target.NONE;
        switch (k) {
            case SPECIES -> {
                var l = TournamentRules.speciesNow(fish(), m);
                if (!l.isEmpty()) t = new Target(l.get(rnd.nextInt(l.size())).id(), null, null);
            }
            case HABITAT -> {
                var l = TournamentRules.habitats(fish(), settings);
                if (!l.isEmpty()) t = new Target(null, l.get(rnd.nextInt(l.size())), null);
            }
            case COLOUR -> {
                var l = new ArrayList<>(fish().colours().keySet());
                if (!l.isEmpty()) t = new Target(null, null, l.get(rnd.nextInt(l.size())));
            }
            default -> { }
        }
        if (k.targeted() && t == Target.NONE) k = Kind.COUNT;   // (nothing to name: an admin asked for it at a bad moment)
        return new Planned(hour, k, t);
    }

    private List<Kind> recent() {
        List<Kind> out = new ArrayList<>();
        for (String s : eco.kv().get(KV_RECENT, "").split(",")) {
            Kind k = Kind.of(s.trim());
            if (k != null) out.add(k);
        }
        return out;
    }

    private void begin(Kind kind, Target target, long now, long end, long hour) {
        Running r = new Running();
        r.kind = kind;
        r.target = target;
        r.start = now;
        r.end = end;
        r.hour = hour;
        cur = r;
        planned = null;
        List<Kind> rec = recent();
        rec.add(kind);
        while (rec.size() > 5) rec.removeFirst();
        eco.kv().set(KV_RECENT, String.join(",", rec.stream().map(k -> k.id).toList()));
        dirty = true;
        save();
        Bukkit.broadcast(Lang.msg("tournament.started", "title", title(kind, target), "goal", goal(kind, target),
                "minutes", Math.max(1, Math.round((end - now) / 60_000.0))));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.2f);
        if (core.hud() != null) core.hud().refreshBoard();
    }

    // ------------------------------------------------------------------------------------------
    // A catch
    // ------------------------------------------------------------------------------------------

    /**
     * A rod's catch (FishingModule, never a trap, pen, net or trial fish) while a tournament runs: it gets a tournament size
     * (the species' range, the old size curve, never stored on the item), counts for the event, and the standings move. null
     * when no tournament runs.
     */
    public Scored caught(Player p, FishSpec.Species s, String water) {
        Running r = cur;
        long now = now();
        if (r == null || now >= r.end) return null;
        double size = FishSpec.size(s, rnd.nextDouble());
        int g = TournamentRules.grade(s.min(), s.max(), size, settings.gradeCuts());
        String grade = Lang.raw("tournament.grade." + g);
        double weight = TournamentRules.weight(size, s.shape());
        FishSpec.Rarity rar = fish().rarities().get(s.rarity());
        Catch c = new Catch(s.id(), rar == null ? 0 : rar.index(), water, s.colour(), s.when() != null, size, weight);
        UUID u = p.getUniqueId();
        Entry e = r.entries.computeIfAbsent(u, k -> new Entry(u, p.getName()));
        e.name = p.getName();
        if (p.getAddress() != null && p.getAddress().getAddress() != null) e.ips.add(p.getAddress().getAddress().getHostAddress());
        UUID leader = r.ranking.isEmpty() ? null : r.ranking.getFirst().id;
        boolean first = e.catches == 0;
        boolean changed = TournamentRules.add(r.kind, r.target, e, c, now);
        if (changed) r.ranking = TournamentRules.rank(r.kind, r.entries.values());
        if (changed || first) {
            dirty = true;
            if (core.hud() != null) core.hud().refreshBoard();
        }
        UUID now1 = r.ranking.isEmpty() ? null : r.ranking.getFirst().id;
        if (now1 != null && !now1.equals(leader)) {
            Player top = Bukkit.getPlayer(now1);
            if (top != null) top.playSound(top.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.8f);   // only the new leader hears it
        }
        int place = r.ranking.indexOf(e) + 1;
        String mine = e.scored && place > 0 ? Lang.raw("tournament.catch-score", "score", score(r.kind, e.score), "place", place)
                : Lang.raw("tournament.catch-no-score");
        String key = switch (r.kind) {
            case BIG, SMALL -> "tournament.catch-cm";
            case WEIGHT -> "tournament.catch-kg";
            default -> "tournament.catch-grade";
        };
        return new Scored(grade, Lang.msg(key, "size", String.format("%.1f", size), "grade", grade,
                "weight", TournamentRules.number(Kind.WEIGHT, weight), "mine", mine));
    }

    // ------------------------------------------------------------------------------------------
    // The board (core HUD, bottom right) and the texts
    // ------------------------------------------------------------------------------------------

    private HudLayout.Board board(Player viewer) {
        Running r = cur;
        if (r == null) return null;
        List<Component> lines = new ArrayList<>();
        List<Entry> rank = r.ranking;
        for (int i = 0; i < Math.min(TOP, rank.size()); i++) {
            Entry e = rank.get(i);
            lines.add(Lang.mm(e.id.equals(viewer.getUniqueId()) ? "tournament.board-me-line" : "tournament.board-line",
                    "place", i + 1, "name", Names.plain(e.id), "score", score(r.kind, e.score)));
        }
        if (rank.isEmpty()) lines.add(Lang.mm("tournament.board-empty"));
        Entry mine = r.entries.get(viewer.getUniqueId());
        int place = mine == null ? 0 : rank.indexOf(mine) + 1;
        if (mine == null) lines.add(Lang.mm("tournament.board-join"));
        else if (place == 0) lines.add(Lang.mm("tournament.board-me-none", "catches", mine.catches));
        else if (place > TOP) lines.add(Lang.mm("tournament.board-me", "place", place, "score", score(r.kind, mine.score)));
        return new HudLayout.Board(Lang.mm("tournament.board-title", "title", title(r.kind, r.target)), lines,
                Lang.mm("tournament.board-footer", "time", clock(r.end - now())));
    }

    /** m:ss */
    static String clock(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private String score(Kind k, double score) {
        return Lang.raw("tournament.unit." + k.unit(), "n", TournamentRules.number(k, score));
    }

    private String targetName(Kind k, Target t) {
        return switch (k) {
            case SPECIES -> {
                var s = fish().species().get(t.species());
                yield s == null ? String.valueOf(t.species()) : s.name();
            }
            case HABITAT -> {
                var g = fish().groups().get(t.habitat());
                yield g == null ? String.valueOf(t.habitat()) : g.name();
            }
            case COLOUR -> fish().colours().getOrDefault(t.colour(), String.valueOf(t.colour()));
            default -> "";
        };
    }

    /** The event's name, with what it names (지정 어종 · 고등어). */
    private String title(Kind k, Target t) {
        return Lang.raw("tournament.kind." + k.id, "target", targetName(k, t));
    }

    /** What wins (가장 큰 한 마리). */
    private String goal(Kind k, Target t) {
        return Lang.raw("tournament.goal." + k.id, "target", targetName(k, t));
    }

    private String time(long at) {
        return DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(at));
    }

    // ------------------------------------------------------------------------------------------
    // The end
    // ------------------------------------------------------------------------------------------

    /** Ends the running tournament now: the results to chat, the prizes paid, the history kept. */
    public void finish() {
        Running r = cur;
        if (r == null) return;
        cur = null;
        dirty = false;
        eco.kv().remove(KV_CURRENT);
        if (core.hud() != null) core.hud().refreshBoard();
        List<Entry> rank = TournamentRules.rank(r.kind, r.entries.values());
        int participants = TournamentRules.participants(r.entries.values());
        long now = now();
        List<TournamentRules.Award> awards = TournamentRules.awards(rank, participants, settings,
                u -> newAccount(u, now));
        Map<UUID, TournamentRules.Award> byId = new LinkedHashMap<>();
        for (var a : awards) byId.put(a.entry().id, a);
        List<Component> out = new ArrayList<>();
        out.add(Lang.msg("tournament.result-header", "title", title(r.kind, r.target), "count", r.entries.size()));
        if (rank.isEmpty()) out.add(Lang.mm("tournament.result-nobody"));
        boolean prizes = participants >= settings.minParticipants();
        for (int i = 0; i < Math.min(3, rank.size()); i++) {
            Entry e = rank.get(i);
            var a = byId.get(e.id);
            out.add(Lang.mm("tournament.result-line", "place", i + 1, "name", Names.mini(e.id), "score", score(r.kind, e.score),
                    "prize", prizes ? prizeText(a, i + 1) : ""));
        }
        if (!r.entries.isEmpty() && !prizes) out.add(Lang.mm("tournament.result-too-few", "min", settings.minParticipants()));
        out.add(Lang.mm("tournament.result-next", "time", time(TournamentRules.nextStart(now + 1, zone))));
        for (Component c : out) Bukkit.broadcast(c);
        for (var a : awards) pay(a, r);
        history(r, rank, prizes, false);
    }

    private String prizeText(TournamentRules.Award a, int place) {
        if (a == null) return "";
        return switch (a.reason()) {
            case FEW_CATCHES -> Lang.raw("tournament.prize-few", "min", settings.minCatches());
            case NEW_ACCOUNT -> a.gold() > 0 ? Lang.raw("tournament.prize-gold", "amount", EconomyModule.format(a.gold())) + Lang.raw("tournament.prize-new")
                    : Lang.raw("tournament.prize-new");
            case NONE -> a.stars() > 0 ? Lang.raw("tournament.prize-stars", "n", a.stars())
                    + (a.gold() > 0 ? Lang.raw("tournament.prize-gold", "amount", EconomyModule.format(a.gold())) : "")
                    : Lang.raw("tournament.prize-gold", "amount", EconomyModule.format(a.gold()));
        };
    }

    /** An account whose first join was less than new-account-hours ago gets no stars. */
    private boolean newAccount(UUID u, long now) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(u);
        long first = o.getFirstPlayed();
        return first <= 0 || now - first < settings.newAccountMs();
    }

    private void pay(TournamentRules.Award a, Running r) {
        UUID u = a.entry().id;
        String note = Lang.raw("tournament.prize-note", "place", a.place(), "title", Text.plain(title(r.kind, r.target)));
        if (a.gold() > 0) core.economy().move(null, u, a.gold(), EconomyStore.Kind.FAUCET, "fishing_tournament", a.place() + "등");
        if (a.stars() <= 0) return;
        var donation = core.donation();
        if (donation == null) {
            eco.getLogger().warning("[낚시대회] 후원(별) 모듈이 없어 " + a.entry().name + " 님에게 별 " + a.stars() + "개를 주지 못했습니다");
            return;
        }
        long dayStart = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now()), zone).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        donation.starsGrantedSince(u, STAR_ACTOR, dayStart).thenAccept(already -> sync(() -> {
            int fit = TournamentRules.capped(a.stars(), already == null ? 0 : already, settings.dailyStarCap());
            Player p = Bukkit.getPlayer(u);
            if (fit < a.stars() && p != null) p.sendMessage(Lang.msg("tournament.cap-reached", "cap", settings.dailyStarCap(), "n", fit));
            if (fit <= 0) return;
            donation.grantStars(u, a.entry().name, fit, note, STAR_ACTOR).thenAccept(ok -> {
                if (!Boolean.TRUE.equals(ok)) eco.getLogger().warning("[낚시대회] " + a.entry().name + " 님에게 별 " + fit + "개 지급 실패");
            });
        }));
    }

    // ------------------------------------------------------------------------------------------
    // Saving and the history
    // ------------------------------------------------------------------------------------------

    private void save() {
        Running r = cur;
        savedAt = now();
        dirty = false;
        if (r == null) {
            eco.kv().remove(KV_CURRENT);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("kind", r.kind.id);
        if (r.target.species() != null) o.addProperty("species", r.target.species());
        if (r.target.habitat() != null) o.addProperty("habitat", r.target.habitat());
        if (r.target.colour() != null) o.addProperty("colour", r.target.colour());
        o.addProperty("start", r.start);
        o.addProperty("end", r.end);
        o.addProperty("hour", r.hour);
        JsonArray es = new JsonArray();
        for (Entry e : r.entries.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", e.id.toString());
            j.addProperty("name", e.name);
            j.addProperty("catches", e.catches);
            j.addProperty("score", e.score);
            j.addProperty("scored", e.scored);
            j.addProperty("at", e.at);
            JsonArray ips = new JsonArray();
            e.ips.forEach(ips::add);
            j.add("ips", ips);
            JsonArray sp = new JsonArray();
            e.species.forEach(sp::add);
            j.add("species", sp);
            es.add(j);
        }
        o.add("entries", es);
        eco.kv().set(KV_CURRENT, o.toString());
    }

    /** A tournament saved at the last stop: resumed when its end is still ahead, cancelled (no prizes, noted) when it is past. */
    private void restore() {
        String s = eco.kv().get(KV_CURRENT, "");
        if (s.isEmpty()) return;
        try {
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            Running r = new Running();
            r.kind = Kind.of(o.get("kind").getAsString());
            if (r.kind == null) throw new IllegalStateException("kind " + o.get("kind"));
            r.target = new Target(str(o, "species"), str(o, "habitat"), str(o, "colour"));
            r.start = o.get("start").getAsLong();
            r.end = o.get("end").getAsLong();
            r.hour = o.get("hour").getAsLong();
            for (var el : o.getAsJsonArray("entries")) {
                JsonObject j = el.getAsJsonObject();
                Entry e = new Entry(UUID.fromString(j.get("id").getAsString()), str(j, "name"));
                e.catches = j.get("catches").getAsInt();
                e.score = j.get("score").getAsDouble();
                e.scored = j.get("scored").getAsBoolean();
                e.at = j.get("at").getAsLong();
                j.getAsJsonArray("ips").forEach(x -> e.ips.add(x.getAsString()));
                j.getAsJsonArray("species").forEach(x -> e.species.add(x.getAsString()));
                r.entries.put(e.id, e);
            }
            r.ranking = TournamentRules.rank(r.kind, r.entries.values());
            if (now() < r.end) {
                cur = r;
                if (r.hour > 0) lastStarted = Math.max(lastStarted, r.hour);
                eco.getLogger().info("[낚시대회] 재시작 전 대회를 이어 갑니다 (" + r.kind.id + ", " + r.entries.size() + "명, 남은 " + clock(r.end - now()) + ")");
            } else {
                eco.kv().remove(KV_CURRENT);
                eco.getLogger().info("[낚시대회] 서버가 꺼진 사이에 끝난 대회를 취소했습니다 (" + r.kind.id + ", " + r.entries.size() + "명, 상 없음)");
                history(r, r.ranking, false, true);
            }
        } catch (RuntimeException ex) {
            eco.kv().remove(KV_CURRENT);
            eco.getLogger().warning("[낚시대회] 저장된 대회를 읽지 못해 지웠습니다: " + ex);
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private void history(Running r, List<Entry> rank, boolean prizes, boolean cancelled) {
        JsonObject h = new JsonObject();
        h.addProperty("at", r.start);
        h.addProperty("title", title(r.kind, r.target));
        h.addProperty("players", r.entries.size());
        h.addProperty("prizes", prizes);
        h.addProperty("cancelled", cancelled);
        JsonArray top = new JsonArray();
        for (int i = 0; i < Math.min(3, rank.size()); i++) {
            JsonObject t = new JsonObject();
            t.addProperty("name", rank.get(i).name);
            t.addProperty("id", rank.get(i).id.toString());
            t.addProperty("score", score(r.kind, rank.get(i).score));
            top.add(t);
        }
        h.add("top", top);
        JsonArray all = history();
        all.add(h);
        while (all.size() > HISTORY) all.remove(0);
        eco.kv().set(KV_HISTORY, all.toString());
    }

    private JsonArray history() {
        try {
            String s = eco.kv().get(KV_HISTORY, "");
            return s.isEmpty() ? new JsonArray() : JsonParser.parseString(s).getAsJsonArray();
        } catch (RuntimeException e) {
            return new JsonArray();
        }
    }

    // ------------------------------------------------------------------------------------------
    // /낚시대회 and the admin's
    // ------------------------------------------------------------------------------------------

    /** /낚시대회: the tournament now (event, time left, standings, my score), the next start, the last results. */
    public void info(CommandSender s) {
        Running r = cur;
        long now = now();
        if (r == null) s.sendMessage(Lang.msg("tournament.info-none"));
        else {
            s.sendMessage(Lang.msg("tournament.info-now", "title", title(r.kind, r.target), "goal", goal(r.kind, r.target),
                    "time", clock(r.end - now), "count", r.entries.size()));
            for (int i = 0; i < Math.min(TOP, r.ranking.size()); i++) {
                Entry e = r.ranking.get(i);
                s.sendMessage(Lang.mm("tournament.info-line", "place", i + 1, "name", Names.mini(e.id), "score", score(r.kind, e.score)));
            }
            if (s instanceof Player p) {
                Entry mine = r.entries.get(p.getUniqueId());
                int place = mine == null ? 0 : r.ranking.indexOf(mine) + 1;
                s.sendMessage(mine == null ? Lang.mm("tournament.info-me-none")
                        : place == 0 ? Lang.mm("tournament.info-me-unranked", "catches", mine.catches)
                        : Lang.mm("tournament.info-me", "place", place, "score", score(r.kind, mine.score), "catches", mine.catches));
            }
        }
        if (enabled) {
            long next = TournamentRules.nextStart(r == null ? now + 1 : Math.max(now + 1, r.end), zone);
            Planned p = planned;
            s.sendMessage(p != null && p.hour() == next ? Lang.mm("tournament.info-next-planned", "time", time(next), "title", title(p.kind(), p.target()))
                    : Lang.mm("tournament.info-next", "time", time(next)));
        } else s.sendMessage(Lang.mm("tournament.info-off"));
        JsonArray h = history();
        if (h.isEmpty()) return;
        s.sendMessage(Lang.mm("tournament.info-history"));
        for (int i = h.size() - 1; i >= 0; i--) {
            JsonObject o = h.get(i).getAsJsonObject();
            List<String> names = new ArrayList<>();
            int place = 1;
            for (var el : o.getAsJsonArray("top")) {
                JsonObject t = el.getAsJsonObject();
                names.add(Lang.raw("tournament.history-place", "place", place++, "name", t.get("name").getAsString(), "score", t.get("score").getAsString()));
            }
            String key = o.get("cancelled").getAsBoolean() ? "tournament.history-cancelled" : names.isEmpty() ? "tournament.history-nobody" : "tournament.history-line";
            s.sendMessage(Lang.mm(key, "time", time(o.get("at").getAsLong()), "title", o.get("title").getAsString(),
                    "top", String.join(Lang.raw("tournament.history-sep"), names)));
        }
    }

    /** /관리자 낚시대회 시작 [종목] · 끝 · 상태 (testing). */
    public void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "상태" : a[0];
        switch (sub) {
            case "시작" -> {
                if (cur != null) {
                    s.sendMessage(Lang.msg("tournament.admin-running"));
                    return;
                }
                Kind k = null;
                if (a.length > 1) {
                    k = kindByName(a[1]);
                    if (k == null) {
                        s.sendMessage(Lang.msg("tournament.admin-no-kind", "kinds", String.join(", ", kindNames())));
                        return;
                    }
                }
                Planned p = plan(0, k);
                long now = now();
                begin(p.kind(), p.target(), now, now + settings.durationMs(), 0);
                s.sendMessage(Lang.msg("tournament.admin-started", "title", title(p.kind(), p.target())));
            }
            case "끝" -> {
                if (cur == null) s.sendMessage(Lang.msg("tournament.admin-none"));
                else finish();
            }
            default -> {
                Running r = cur;
                s.sendMessage(Lang.msg("tournament.admin-status", "enabled", Lang.raw(enabled ? "tournament.admin-on" : "tournament.admin-off"),
                        "now", r == null ? "-" : title(r.kind, r.target) + " (" + clock(r.end - now()) + ", " + r.entries.size() + "명)",
                        "next", time(TournamentRules.nextStart(now() + 1, zone)), "recent", String.join(", ", recent().stream().map(k -> Lang.raw("tournament.kind-name." + k.id)).toList())));
            }
        }
    }

    public List<String> adminComplete(CommandSender s, String[] a) {
        if (a.length == 1) return List.of("시작", "끝", "상태");
        if (a.length == 2 && a[0].equals("시작")) return kindNames();
        return List.of();
    }

    private List<String> kindNames() {
        List<String> out = new ArrayList<>();
        for (Kind k : Kind.values()) out.add(Lang.raw("tournament.kind-name." + k.id).replace(' ', '_'));
        return out;
    }

    private Kind kindByName(String word) {
        String w = word.replace('_', ' ');
        for (Kind k : Kind.values()) if (Lang.raw("tournament.kind-name." + k.id).equals(w) || k.id.equalsIgnoreCase(word)) return k;
        return null;
    }
}
