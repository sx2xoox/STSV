package dev.stsv.core.minigame.circuit;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.minigame.ReturnPoint;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.core.vehicle.VehicleModule;
import dev.stsv.core.vehicle.VehiclePhysics;
import dev.stsv.core.vehicle.VehicleSpec;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.FluidCollisionMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One race on one track: its waiting room (대기실: who joined, their karts and colours, the countdown), then the race
 * itself, run every tick by {@link Circuit}. See README "미니게임 · 서킷" for the rules as players see them.
 *
 * <h2>Safe in and out</h2> A racer's state is written onto them ({@link ReturnPoint}) before anything of theirs changes;
 * only then is the inventory cleared, the game mode set to adventure, and they are taken to the grid. Every way out
 * (finishing, leaving, quitting, being dropped by an admin, the server stopping) goes through {@link #out}, which takes
 * the kart away first and then puts the player back. The kart is a temporary vehicle ({@link VehicleModule#spawnTemporary}):
 * never an item, never saved, gone with the race.
 *
 * <h2>Ticks</h2> The karts drive themselves (VehicleModule). Every tick the race reads where each kart is on the centre
 * line ({@link TrackPath#locate}), passes that to its {@link RaceProgress} (gates, laps, the finish), fills and fires
 * its {@link BoostGauge}, launches it off jump pads, speeds it on boost pads, puts it back at its last gate when it fell
 * off or left the track, pushes karts that touch apart (the heavier pushes harder) and draws the boss bar.
 *
 * <h2>고스트</h2> Every racer's kart is sampled ({@link GhostRecorder}) and each lap becomes a {@link Ghost}: kept as their
 * best when it is ({@link GhostStore}). A racer who chose one races against a ghost (theirs or the track record's,
 * {@link GhostView}: only they and their watchers see it); it starts with each of their laps, and at every gate they
 * are told how far ahead or behind it they are.
 *
 * <h2>관전</h2> Watchers ({@link Watcher}): players in spectator mode in the race's world, their state written onto them
 * first ({@link ReturnPoint}) exactly as a racer's, taken back home by {@link #unwatch} (leaving, the race's end, a quit,
 * the server stopping). Their camera follows a racer: a display entity behind the kart (추격), the racer's own eyes
 * (1인칭), or free over the track (자유); A·D change the racer, space the view, sneaking twice leaves ({@link WatchRules}).
 */
final class Race {

    enum State { LOBBY, STARTING, RUNNING, FINISHING, DONE, CLOSED }

    /** Ticks of the 3-2-1 before GO. */
    static final int COUNTDOWN = 60;

    final class Racer {
        final UUID id;
        String name;
        KartSpec kart;
        String colour;
        boolean ready;
        boolean racing;
        boolean left;
        long vehicle;
        int slot;
        RaceProgress progress;
        final BoostGauge gauge = new BoostGauge();
        int place;
        int offTicks, stillTicks, spawnTries;
        int padUntil, respawnAfter;
        boolean jumped, spaceHeld;
        int forwardSince = -1;
        /** the start boost is judged this many ticks after GO (their ping: a W pressed at their GO reaches the server late) */
        int boostLate;
        int wrongTold;
        int lastLap;
        BossBar bar;
        // 고스트: recording this racer, and the ghost they race against
        Ghost.Choice ghostChoice = Ghost.Choice.NONE;
        GhostRecorder recorder;
        double[] splits;
        Ghost ghost;
        String ghostHolder, ghostKart;
        boolean ghostRecord;
        CompletableFuture<Ghost> ghostLoad;
        String loadHolder, loadKart;
        boolean loadRecord;
        GhostView ghostView;
        Component gap;
        int gapUntil;

        Racer(Player p, KartSpec kart, String colour) {
            this.id = p.getUniqueId();
            this.name = p.getName();
            this.kart = kart;
            this.colour = colour;
        }

        Player player() {
            return Bukkit.getPlayer(id);
        }
    }

    final Circuit game;
    final TrackSpec track;
    final TrackLayout layout;
    final TrackPath path;
    final World world;
    final int ox;
    final Map<UUID, Racer> racers = new LinkedHashMap<>();
    UUID host;
    State state = State.LOBBY;
    int lobbyTicks;
    int tick;
    int raceTicks;
    int finishLeft;
    int finished;
    private final Set<Chunk> held = new HashSet<>();
    final BossBar lobbyBar;
    private final List<String> results = new ArrayList<>();
    final Map<UUID, Watcher> watchers = new LinkedHashMap<>();

    Race(Circuit game, TrackSpec track, TrackLayout layout, World world, int lobbySeconds) {
        this.game = game;
        this.track = track;
        this.layout = layout;
        this.path = layout.path();
        this.world = world;
        this.ox = track.originX();
        this.lobbyTicks = lobbySeconds * 20;
        this.lobbyBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_20);
        // the grid's chunks: loaded now (the waiting room gives them time), held until the race is over
        for (TrackLayout.Slot s : layout.grid()) {
            int cx = Math.floorDiv((int) Math.floor(s.x()) + ox, 16), cz = Math.floorDiv((int) Math.floor(s.z()), 16);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                world.getChunkAtAsync(cx + dx, cz + dz, true).thenAccept(c -> Bukkit.getScheduler().runTask(game.plugin(), () -> {
                    if (state == State.CLOSED || !held.add(c)) return;
                    c.addPluginChunkTicket(game.plugin());
                }));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // The waiting room
    // ------------------------------------------------------------------------------------------

    boolean lobby() {
        return state == State.LOBBY;
    }

    boolean running() {
        return state == State.STARTING || state == State.RUNNING || state == State.FINISHING || state == State.DONE;
    }

    int lobbySeconds() {
        return (lobbyTicks + 19) / 20;
    }

    Racer join(Player p, KartSpec kart, String colour) {
        Racer r = new Racer(p, kart, colour);
        racers.put(p.getUniqueId(), r);
        if (host == null) host = p.getUniqueId();
        p.showBossBar(lobbyBar);
        drawLobby();
        return r;
    }

    void leaveLobby(Player p) {
        Racer r = racers.remove(p.getUniqueId());
        p.hideBossBar(lobbyBar);
        if (r != null && p.getUniqueId().equals(host)) host = racers.isEmpty() ? null : racers.keySet().iterator().next();
        drawLobby();
    }

    /** Everyone pressed ready: the countdown drops to 3 seconds. */
    void readyChanged() {
        boolean all = !racers.isEmpty();
        for (Racer r : racers.values()) all &= r.ready;
        if (all && lobbyTicks > 60) lobbyTicks = 60;
        drawLobby();
    }

    void startNow() {
        if (state == State.LOBBY) lobbyTicks = Math.min(lobbyTicks, 20);
    }

    private void drawLobby() {
        int ready = 0;
        for (Racer r : racers.values()) if (r.ready) ready++;
        lobbyBar.name(Lang.mm("circuit.lobby-bar", "track", track.name(), "seconds", lobbySeconds(), "count", racers.size(),
                "max", game.maxRacers(), "ready", ready));
        lobbyBar.progress(Math.clamp(lobbyTicks / (float) (game.lobbySeconds() * 20), 0f, 1f));
    }

    // ------------------------------------------------------------------------------------------
    // Every tick
    // ------------------------------------------------------------------------------------------

    void tick() {
        tick++;
        switch (state) {
            case LOBBY -> {
                lobbyTicks--;
                if (tick % 5 == 0) drawLobby();
                if (tick % 20 == 0) {
                    game.refreshLobbyMenus(this);
                    int s = lobbySeconds();
                    if (s <= 5 && s > 0) for (Racer r : racers.values()) {
                        Player p = r.player();
                        if (p != null) p.playSound(p, Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.MASTER, 0.6f, 1.4f);
                    }
                }
                if (lobbyTicks <= 0) begin();
            }
            case STARTING -> starting();
            case RUNNING, FINISHING -> racingTick();
            case DONE -> {
                if (--finishLeft <= 0) close(true);
                else if (finishLeft % 20 == 0) fireworks();
            }
            default -> { }
        }
        if (state != State.LOBBY && state != State.CLOSED && !watchers.isEmpty()) watchTick();
    }

    // ------------------------------------------------------------------------------------------
    // The start: everyone to the grid, into a kart, 3-2-1-GO
    // ------------------------------------------------------------------------------------------

    private void begin() {
        for (Racer r : racers.values()) {
            Player p = r.player();
            if (p != null) {
                p.hideBossBar(lobbyBar);
                game.closeLobbyMenu(p);
            }
        }
        state = State.STARTING;
        tick = 0;
        int slot = 0;
        List<String> problems = new ArrayList<>();
        for (Racer r : List.copyOf(racers.values())) {
            Player p = r.player();
            String why = p == null ? "offline" : game.cannotRace(p);
            if (why != null || slot >= layout.grid().size()) {
                racers.remove(r.id);
                if (p != null) p.sendMessage(Lang.msg(why == null ? "circuit.full" : why));
                continue;
            }
            r.slot = slot++;
            r.racing = true;
            TrackLayout.Slot s = layout.grid().get(r.slot);
            int at = path.locate(s.x(), s.y(), s.z(), -1, 0).index();
            r.progress = new RaceProgress(path.size(), layout.gates(), track.laps(), at);
            // written onto them first, then their things put away
            // whatever is on the cursor or in the crafting grid goes back into the inventory first (closing does it)
            p.closeInventory();
            if (p.isInsideVehicle()) p.leaveVehicle();
            ReturnPoint.save(p);
            p.getInventory().clear();
            p.setGameMode(GameMode.ADVENTURE);
            for (var e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
            var max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            p.setHealth(max == null ? 20 : max.getValue());
            p.setFoodLevel(20);
            p.setSaturation(20);
            p.setFireTicks(0);
            p.setFallDistance(0);
            p.setAllowFlight(false);
            p.setFlying(false);
            game.giveRaceItems(p);
            p.setPlayerTime(track.theme().time(), false);
            p.setPlayerWeather(WeatherType.CLEAR);
            Location to = new Location(world, s.x() + ox, TrackLayout.BASE_Y + s.y() + 0.3, s.z(), s.yaw(), 8);
            game.teleporting(p, true);
            boolean ok = p.teleport(to);
            game.teleporting(p, false);
            if (!ok) problems.add(r.name);
        }
        if (racers.isEmpty()) {
            close(false);
            return;
        }
        for (String n : problems) {
            Racer r = racerByName(n);
            if (r != null) out(r, "circuit.left-error");
        }
        for (Racer r : racers.values()) {
            r.bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            setUpGhost(r);
        }
    }

    private Racer racerByName(String n) {
        for (Racer r : racers.values()) if (r.name.equals(n)) return r;
        return null;
    }

    private void starting() {
        // the karts appear as soon as their place is ready (a few ticks), then the 3-2-1
        for (Racer r : List.copyOf(racers.values())) {
            if (!r.racing || r.left || r.vehicle != 0) continue;
            Player p = r.player();
            if (p == null) continue;
            TrackLayout.Slot s = layout.grid().get(r.slot);
            r.vehicle = spawnKart(r, p, s.x() + ox, TrackLayout.BASE_Y + s.y() + 0.05, s.z(), s.yaw());
            if (r.vehicle != 0) {
                game.vehicles().freezeTemporary(r.vehicle, true);
                p.showBossBar(r.bar);
            } else if (++r.spawnTries > 40) out(r, "circuit.left-error");
        }
        for (Racer r : active()) {
            Player p = r.player();
            if (p != null && r.vehicle != 0) ghostTick(r, p);
        }
        if (active().isEmpty()) {
            close(false);
            return;
        }
        if (tick < 10) return;
        int t = tick - 10;
        // who holds W through the countdown: pressing it just before GO is the start boost
        for (Racer r : active()) {
            Player p = r.player();
            if (p == null) continue;
            boolean fwd = p.getCurrentInput().isForward();
            if (fwd && r.forwardSince < 0) r.forwardSince = t;
            if (!fwd) r.forwardSince = -1;
            drawBar(r, p);
        }
        if (t == 0 || t == 20 || t == 40) {
            char pic = t == 0 ? CircuitHud.THREE : t == 20 ? CircuitHud.TWO : CircuitHud.ONE;
            for (Racer r : active()) {
                Player p = r.player();
                if (p == null) continue;
                p.showTitle(CircuitHud.title(pic, Lang.mm("circuit.countdown-sub"), 0, 18, 2));
                p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1f, 0.8f);
            }
            for (Player w : watcherPlayers()) {
                w.showTitle(CircuitHud.title(pic, Component.empty(), 0, 18, 2));
                w.playSound(w, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.6f, 0.8f);
            }
        }
        if (t == COUNTDOWN) {
            state = State.RUNNING;
            raceTicks = 0;
            for (Player w : watcherPlayers()) {
                w.showTitle(CircuitHud.title(CircuitHud.GO, Component.empty(), 0, 16, 6));
                w.playSound(w, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.6f, 1.6f);
            }
            for (Racer r : active()) {
                Player p = r.player();
                if (p == null) continue;
                game.vehicles().freezeTemporary(r.vehicle, false);
                p.showTitle(CircuitHud.title(CircuitHud.GO, Component.empty(), 0, 16, 6));
                p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1f, 1.6f);
                p.playSound(p, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.MASTER, 0.7f, 1.2f);
                // the start boost: W pressed in the last half second (not held all along), as the player saw it:
                // they see GO half their ping late and their W reaches the server half their ping late, so with a
                // high ping it is judged a few ticks after GO (startBoost)
                r.boostLate = startBoostDelay(p.getPing());
                if (r.boostLate == 0) startBoost(r, p);
            }
        }
    }

    /** How many ticks after GO a racer with this ping is judged for the start boost (their round trip, at most 4). */
    static int startBoostDelay(int pingMs) {
        return Math.clamp(Math.round(Math.max(0, pingMs) / 50f), 0, 4);
    }

    /** Whether W first pressed at countdown tick `since` earns the start boost for a racer judged `late` ticks after GO. */
    static boolean startBoostTimed(int since, int late) {
        return since >= COUNTDOWN - 12 + late && since <= COUNTDOWN + late;
    }

    private void startBoost(Racer r, Player p) {
        if (!startBoostTimed(r.forwardSince, r.boostLate)) return;
        r.gauge.free(1.25, 28);
        kick(r, 1.15);
        HudModule.flash(p, Lang.mm("circuit.start-boost"), 1500);
        p.playSound(p, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.MASTER, 0.6f, 1.4f);
    }

    private long spawnKart(Racer r, Player p, double x, double y, double z, float yaw) {
        CustomItem item = game.kartItem(r.kart);
        if (item == null || !world.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) return 0;
        if (!p.getWorld().equals(world)) return 0;
        return game.vehicles().spawnTemporary(item, world, x, y, z, yaw, r.kart.paint(r.colour), p);
    }

    List<Racer> active() {
        List<Racer> out = new ArrayList<>();
        for (Racer r : racers.values()) if (r.racing && !r.left) out.add(r);
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // The race
    // ------------------------------------------------------------------------------------------

    private double seconds() {
        return raceTicks * 0.05;
    }

    private void racingTick() {
        raceTicks++;
        VehicleModule vm = game.vehicles();
        List<Racer> drivers = active();
        if (drivers.isEmpty()) {
            done();
            return;
        }
        for (Racer r : drivers) {
            Player p = r.player();
            if (p == null) {
                out(r, null);
                continue;
            }
            if (r.boostLate > 0 && raceTicks <= r.boostLate) {
                // a high ping's start boost: their W keeps being followed for a few ticks after GO
                boolean fwd = p.getCurrentInput().isForward();
                if (fwd && r.forwardSince < 0) r.forwardSince = COUNTDOWN + raceTicks;
                if (!fwd) r.forwardSince = -1;
                if (raceTicks == r.boostLate && vm.temporaryAlive(r.vehicle)) startBoost(r, p);
            }
            if (!vm.temporaryAlive(r.vehicle)) {
                respawn(r, p);
                continue;
            }
            if (!p.equals(vm.temporaryDriver(r.vehicle)) && !vm.seatTemporary(r.vehicle, p)) {
                respawn(r, p);
                continue;
            }
            VehiclePhysics.State st = vm.temporaryState(r.vehicle);
            VehicleSpec spec = vm.temporarySpec(r.vehicle);
            double lx = st.x - ox, ly = st.y - TrackLayout.BASE_Y, lz = st.z;
            TrackPath.Spot spot = path.locate(lx, ly, lz, r.progress.at(), 90);
            // the ghost's samples: this tick's place, before the gates it may have crossed are counted
            if (r.recorder != null && !r.progress.finished()) r.recorder.sample(raceTicks, lx, ly, lz, st.yaw, r.gauge.boosting());
            RaceProgress.Event ev = r.progress.move(spot.index(), seconds(), 0.05);
            if (ev != RaceProgress.Event.NONE) passed(r, p, ev);
            ghostTick(r, p);
            if (r.progress.finished()) {
                vm.boostTemporary(r.vehicle, 1);
                drawBar(r, p);
                continue;
            }
            // off the track: fallen (not over a jump's gap, where flying is the point) or out past the barrier
            boolean gap = layout.gap(spot.index()) || layout.gap(spot.index() + 12) || layout.gap(spot.index() - 12);
            double road = layout.height(spot.index());
            // (over a jump's gap the kart flies above the road; in the canal under it, it is well below)
            boolean fell = ly < road - (gap ? 1.4 : 2.6) || ly < -6;
            boolean outside = Math.abs(spot.lateral()) > path.width(spot.index()) / 2 + 6.5;
            r.offTicks = fell || outside ? r.offTicks + 1 : 0;
            if (r.offTicks > 10) {
                respawn(r, p);
                continue;
            }
            // stuck: a hint after a while (the compass puts them back)
            r.stillTicks = Math.abs(st.speed) < 0.4 ? r.stillTicks + 1 : 0;
            if (r.stillTicks == 160) HudModule.flash(p, Lang.mm("circuit.stuck-hint"), 3000);
            // the gauge: drifting fills it, space fires a kept boost
            double slide = spec.extras().drift() == null ? 0 : Math.abs(st.slip) / spec.extras().drift().angle();
            if (r.gauge.tick(slide, st.speed > spec.speed() * 0.85 && st.onGround, r.kart.charge())) {
                p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.MASTER, 0.7f, 1.8f);
                HudModule.flash(p, Lang.mm("circuit.boost-ready"), 900);
            }
            boolean space = p.getCurrentInput().isJump();
            if (space && !r.spaceHeld && r.gauge.fire(r.kart.boost(), r.kart.boostTicks())) {
                kick(r, r.kart.boost() * 0.92);
                world.playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 0.8f, 1.3f);
                p.playSound(p, Sound.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.MASTER, 0.6f, 1.6f);
            }
            r.spaceHeld = space;
            // pads under the kart
            TrackLayout.Zone zone = zone(spot.index());
            if (zone != null && zone.type() == TrackSpec.FeatureType.BOOST && raceTicks >= r.padUntil && Math.abs(spot.lateral()) < path.width(spot.index()) / 2 - 1) {
                r.gauge.free(zone.power(), 30);
                kick(r, zone.power() * 0.95);
                r.padUntil = raceTicks + 20;
                p.playSound(p, Sound.ENTITY_BREEZE_JUMP, SoundCategory.MASTER, 0.8f, 1.4f);
            }
            if (zone != null && zone.type() == TrackSpec.FeatureType.JUMP) {
                if (!r.jumped && st.onGround && st.speed > 7) {
                    st.vy = zone.power();
                    st.onGround = false;
                    r.jumped = true;
                    world.playSound(p.getLocation(), Sound.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 1f, 0.8f);
                }
            } else r.jumped = false;
            vm.boostTemporary(r.vehicle, r.gauge.factor());
            if (r.gauge.boosting() && raceTicks % 2 == 0) {
                double[] back = VehiclePhysics.forward(st.yaw);
                world.spawnParticle(Particle.FLAME, st.x - back[0] * 1.3, st.y + 0.45, st.z - back[1] * 1.3, 2, 0.08, 0.05, 0.08, 0.01);
                world.spawnParticle(Particle.SMALL_FLAME, st.x - back[0] * 1.4, st.y + 0.4, st.z - back[1] * 1.4, 1, 0.05, 0.05, 0.05, 0.01);
            }
            // the wrong way
            if (r.progress.wrongWay()) {
                if (raceTicks - r.wrongTold > 30) {
                    r.wrongTold = raceTicks;
                    p.showTitle(CircuitHud.title(CircuitHud.WRONG, Lang.mm("circuit.wrong-way"), 0, 24, 6));
                    p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.MASTER, 0.8f, 0.6f);
                }
            }
            if (raceTicks % 2 == 0) drawBar(r, p);
        }
        if (game.bumping()) bump(drivers);
        if (state == State.FINISHING && --finishLeft <= 0) done();
        else if (state == State.FINISHING && finishLeft % 20 == 0 && finishLeft <= 200) {
            for (Racer r : active()) {
                Player p = r.player();
                if (p != null && !r.progress.finished()) HudModule.flash(p, Lang.mm("circuit.finish-left", "seconds", finishLeft / 20), 1100);
            }
        }
        boolean allDone = true;
        for (Racer r : active()) allDone &= r.progress.finished();
        if (allDone || raceTicks > game.maxRaceTicks()) done();
    }

    private TrackLayout.Zone zone(int i) {
        for (TrackLayout.Zone z : layout.zones()) {
            boolean in = z.from() <= z.to() ? i >= z.from() && i <= z.to() : i >= z.from() || i <= z.to();
            if (in && z.type() != TrackSpec.FeatureType.TUNNEL) return z;
        }
        return null;
    }

    /** A boost's push: at once to factor times its top speed, if slower. */
    private void kick(Racer r, double factor) {
        VehiclePhysics.State st = game.vehicles().temporaryState(r.vehicle);
        VehicleSpec spec = game.vehicles().temporarySpec(r.vehicle);
        if (st == null || spec == null) return;
        st.speed = Math.max(st.speed, Math.min(spec.speed() * factor, VehicleSpec.OVER_CAP_MAX * 1.6));
    }

    private void passed(Racer r, Player p, RaceProgress.Event ev) {
        switch (ev) {
            case GATE -> {
                p.playSound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.MASTER, 0.35f, 1.9f);
                gateForGhost(r, p);
            }
            case LAP -> {
                lapForGhost(r, p);
                List<Double> laps = r.progress.lapTimes();
                double last = laps.getLast();
                boolean finalLap = r.progress.lap() == r.progress.laps();
                p.showTitle(CircuitHud.title(CircuitHud.LAP, Lang.mm(finalLap ? "circuit.final-lap" : "circuit.lap-sub", "lap", r.progress.lap(),
                        "laps", r.progress.laps(), "time", RaceProgress.format(last)), 2, 30, 8));
                p.playSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 0.8f, finalLap ? 1.6f : 1.2f);
                long ms = Math.round(last * 1000), rec = game.store().best(track.id(), true);
                if (rec == 0 || ms < rec) HudModule.flash(p, Lang.mm("circuit.lap-fast", "time", RaceProgress.format(last)), 2500);
            }
            case FINISH -> {
                lapForGhost(r, p);
                finish(r, p);
            }
            default -> { }
        }
    }

    private void finish(Racer r, Player p) {
        r.place = ++finished;
        double time = r.progress.finishTime();
        p.showTitle(CircuitHud.title(CircuitHud.FINISH, Component.empty(), 0, 30, 4));
        Bukkit.getScheduler().runTaskLater(game.plugin(), () -> {
            if (p.isOnline() && racers.containsKey(r.id)) p.showTitle(CircuitHud.title(CircuitHud.place(r.place),
                    Lang.mm("circuit.finish-sub", "time", RaceProgress.format(time)), 0, 50, 10));
        }, 34L);
        p.playSound(p, r.place == 1 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.8f, 1f);
        for (Racer o : racers.values()) {
            Player op = o.player();
            if (op != null) op.sendMessage(Lang.msg("circuit.finished", "place", r.place, "player", Names.mini(p), "time", RaceProgress.format(time)));
        }
        for (Watcher w : watchers.values()) {
            Player wp = Bukkit.getPlayer(w.id);
            if (wp == null) continue;
            wp.sendMessage(Lang.msg("circuit.finished", "place", r.place, "player", Names.mini(p), "time", RaceProgress.format(time)));
            if (r.id.equals(w.target)) wp.showTitle(CircuitHud.title(CircuitHud.FINISH, Lang.mm("circuit.finish-sub", "time", RaceProgress.format(time)), 0, 30, 6));
        }
        if (r.place == 1) {
            fireworkAt(p.getLocation(), Color.fromRGB(0xffd36b));
            if (state == State.RUNNING && active().size() > 1) {
                state = State.FINISHING;
                finishLeft = game.finishGraceTicks();
            }
        }
    }

    /** Fell off, left the road, lost its kart: back at the last gate passed (the grid before the first), standing. */
    private void respawn(Racer r, Player p) {
        r.offTicks = 0;
        r.stillTicks = 0;
        r.gauge.stop();
        int gate = r.progress.lastGate();
        double x, y, z;
        float yaw;
        int index;
        if (gate < 0) {
            TrackLayout.Slot s = layout.grid().get(r.slot);
            x = s.x();
            y = s.y();
            z = s.z();
            yaw = s.yaw();
            index = path.locate(x, y, z, -1, 0).index();
        } else {
            // a little past the gate, on the middle of the road, its side shifted by the racer's slot (so two do not stack)
            index = path.wrap(gate + 4);
            double lat = ((r.slot % 3) - 1) * Math.min(2.5, path.width(index) / 4);
            x = path.x(index) - path.dirZ(index) * lat;
            z = path.z(index) + path.dirX(index) * lat;
            y = layout.height(index);
            yaw = path.yaw(index);
        }
        VehicleModule vm = game.vehicles();
        double wx = x + ox, wy = TrackLayout.BASE_Y + y + 0.05;
        if (vm.temporaryAlive(r.vehicle)) {
            vm.placeTemporary(r.vehicle, wx, wy, z, yaw);
            if (!p.equals(vm.temporaryDriver(r.vehicle))) vm.seatTemporary(r.vehicle, p);
        } else {
            vm.removeTemporary(r.vehicle);
            game.teleporting(p, true);
            p.teleport(new Location(world, wx, wy + 0.3, z, yaw, 8));
            game.teleporting(p, false);
            r.vehicle = spawnKart(r, p, wx, wy, z, yaw);
            if (r.vehicle == 0) {
                if (++r.spawnTries > 60) out(r, "circuit.left-error");
                return;
            }
        }
        r.spawnTries = 0;
        r.progress.teleported(index);
        p.playSound(p, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.MASTER, 0.5f, 1.4f);
        HudModule.flash(p, Lang.mm("circuit.respawned"), 1500);
    }

    /** The compass: back to the last gate (not more often than every 3 seconds). */
    void askRespawn(Player p) {
        Racer r = racers.get(p.getUniqueId());
        if (r == null || !r.racing || r.left || state != State.RUNNING && state != State.FINISHING || r.progress.finished()) return;
        if (raceTicks < r.respawnAfter) return;
        r.respawnAfter = raceTicks + 60;
        respawn(r, p);
    }

    /** Karts that touch are pushed apart: each by the other's weight's share (a heavy kart barely moves). */
    private void bump(List<Racer> drivers) {
        VehicleModule vm = game.vehicles();
        for (int a = 0; a < drivers.size(); a++) {
            Racer ra = drivers.get(a);
            VehiclePhysics.State sa = vm.temporaryState(ra.vehicle);
            if (sa == null) continue;
            for (int b = a + 1; b < drivers.size(); b++) {
                Racer rb = drivers.get(b);
                VehiclePhysics.State sb = vm.temporaryState(rb.vehicle);
                if (sb == null || Math.abs(sa.y - sb.y) > 1.2) continue;
                double dx = sb.x - sa.x, dz = sb.z - sa.z, d = Math.hypot(dx, dz);
                double reach = 1.7;
                if (d >= reach || d < 1e-4) continue;
                double push = (reach - d) * 0.5, wa = ra.kart.weight(), wb = rb.kart.weight();
                double ka = push * wb / (wa + wb) * 2, kb = push * wa / (wa + wb) * 2;
                vm.nudgeTemporary(ra.vehicle, -dx / d * ka, -dz / d * ka);
                vm.nudgeTemporary(rb.vehicle, dx / d * kb, dz / d * kb);
                // the lighter one loses more speed
                sa.speed *= 1 - 0.08 * wb / (wa + wb);
                sb.speed *= 1 - 0.08 * wa / (wa + wb);
                if (raceTicks % 6 == 0) world.playSound(new Location(world, (sa.x + sb.x) / 2, sa.y, (sa.z + sb.z) / 2), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR,
                        SoundCategory.PLAYERS, 0.35f, 1.8f);
            }
        }
    }

    /** The racers in their order now: finished by time, then the furthest round. */
    List<Racer> standings() {
        List<Racer> out = new ArrayList<>();
        for (Racer r : racers.values()) if (r.racing) out.add(r);
        out.sort(Comparator.<Racer>comparingInt(r -> r.left ? 1 : 0)
                .thenComparing(r -> r.progress == null || !r.progress.finished() ? 1 : 0)
                .thenComparingDouble(r -> r.progress != null && r.progress.finished() ? r.progress.finishTime() : 0)
                .thenComparing(Comparator.comparingLong((Racer r) -> r.progress == null ? Long.MIN_VALUE : r.progress.distance()).reversed()));
        return out;
    }

    private void drawBar(Racer r, Player p) {
        if (r.bar == null) return;
        int place = standings().indexOf(r) + 1;
        r.bar.name(hud(r));
        r.bar.progress(along(r));
        r.bar.color(r.gauge.boosting() ? BossBar.Color.RED : place == 1 ? BossBar.Color.YELLOW : BossBar.Color.BLUE);
    }

    /** A racer's boss bar line: laps, place, clock, boosts, the gauge, and the last gap to their ghost (a few seconds). */
    private Component hud(Racer r) {
        List<Racer> order = standings();
        int place = order.indexOf(r) + 1;
        RaceProgress pr = r.progress;
        double clock = pr.finished() ? pr.finishTime() : state == State.STARTING ? 0 : seconds();
        Component gap = r.gap != null && raceTicks < r.gapUntil ? r.gap : null;
        return CircuitHud.bar(Math.max(1, pr.lap()), pr.laps(), place, order.size(), RaceProgress.formatShort(clock), r.gauge.stored(),
                r.gauge.gauge(), r.gauge.boosting(), gap);
    }

    /** How far along the lap the racer is (the bar fills along it). */
    private float along(Racer r) {
        RaceProgress pr = r.progress;
        double along = pr.lap() == 0 ? 0 : (path.ahead(layout.gates()[0], pr.at()) / (double) path.size());
        return (float) Math.clamp(along, 0, 1);
    }

    // ------------------------------------------------------------------------------------------
    // The end
    // ------------------------------------------------------------------------------------------

    /** The race is over: results, records, fireworks; everyone goes back in a few seconds. */
    private void done() {
        if (state == State.DONE || state == State.CLOSED) return;
        state = State.DONE;
        finishLeft = 120;
        for (Racer r : active()) game.vehicles().freezeTemporary(r.vehicle, true);
        for (Racer r : racers.values()) dropGhostView(r);
        List<Racer> order = standings();
        List<Component> lines = new ArrayList<>();
        lines.add(Lang.mm("circuit.results-head", "track", track.name(), "laps", track.laps()));
        int k = 0;
        int finishers = 0;
        for (Racer r : order) if (r.progress != null && r.progress.finished()) finishers++;
        long now = System.currentTimeMillis();
        List<Component> records = new ArrayList<>();
        for (Racer r : order) {
            k++;
            RaceProgress pr = r.progress;
            boolean fin = pr != null && pr.finished();
            double best = pr == null ? Double.NaN : pr.bestLap();
            Player online = r.player();
            String name = online != null ? Names.mini(online) : r.name;
            lines.add(Lang.mm(fin ? "circuit.results-line" : "circuit.results-dnf", "place", k, "player", name,
                    "time", fin ? RaceProgress.format(pr.finishTime()) : "-", "lap", RaceProgress.format(best), "kart", r.kart.name()));
            if (pr == null || (Double.isNaN(best) && !fin)) continue;
            long lapMs = Double.isNaN(best) ? 0 : Math.round(best * 1000), raceMs = fin ? Math.round(pr.finishTime() * 1000) : 0;
            boolean won = fin && r.place == 1 && !r.left;
            CircuitStore.Result res = game.store().record(track.id(), r.id, r.name, lapMs, raceMs, won, r.kart.id(), now);
            Player p = r.player();
            if (p != null && (res.lapPb() || res.racePb()) && !(res.lapRecord() || res.raceRecord()))
                p.sendMessage(Lang.msg("circuit.personal-best", "lap", RaceProgress.format(res.row().bestLap() / 1000.0),
                        "race", RaceProgress.format(res.row().bestRace() / 1000.0)));
            if (res.raceRecord()) records.add(Lang.msg("circuit.record-race", "player", name, "track", track.name(), "time", RaceProgress.format(raceMs / 1000.0)));
            if (res.lapRecord()) records.add(Lang.msg("circuit.record-lap", "player", name, "track", track.name(), "time", RaceProgress.format(lapMs / 1000.0)));
            if ((res.lapRecord() || res.raceRecord()) && p != null) {
                p.showTitle(CircuitHud.title(CircuitHud.RECORD, Component.empty(), 4, 50, 10));
                p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1f, 1.2f);
            }
        }
        for (Racer r : racers.values()) {
            Player p = r.player();
            if (p == null) continue;
            for (Component c : lines) p.sendMessage(c);
        }
        for (Player w : watcherPlayers()) for (Component c : lines) w.sendMessage(c);
        for (Component c : records) Bukkit.broadcast(c);
        if (finishers > 0 && order.size() > 1) {
            Racer w = order.getFirst();
            Bukkit.broadcast(Lang.msg("circuit.winner", "player", w.name, "track", track.name(),
                    "time", RaceProgress.format(w.progress.finishTime())));
        }
        fireworks();
    }

    private void fireworks() {
        for (Racer r : standings()) {
            if (r.place != 1) continue;
            Player p = r.player();
            if (p != null && p.getWorld().equals(world)) fireworkAt(p.getLocation(), Color.fromRGB(0xffd36b));
        }
    }

    private void fireworkAt(Location at, Color c) {
        Firework f = world.spawn(at.clone().add(0, 1.5, 0), Firework.class, fw -> {
            FireworkMeta m = fw.getFireworkMeta();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(c, Color.WHITE).withFade(Color.ORANGE).flicker(true).build());
            m.setPower(1);
            fw.setFireworkMeta(m);
            fw.setPersistent(false);
        });
        f.getPersistentDataContainer().set(Circuit.SAFE_FIREWORK, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * A racer leaves the race (why: a message key for them, or null): their kart goes, they are put back as they were. The
     * race goes on without them (and ends when nobody is left in it).
     */
    void out(Racer r, String why) {
        if (r.left) return;
        r.left = true;
        dropGhostView(r);
        r.ghostLoad = null;
        // whoever watched them through their eyes lets go first (they go home, to another world)
        Player rp = r.player();
        for (Watcher w : watchers.values()) {
            Player wp = Bukkit.getPlayer(w.id);
            if (wp == null || rp == null || !rp.equals(wp.getSpectatorTarget())) continue;
            game.teleporting(wp, true);
            try {
                wp.setSpectatorTarget(null);
            } finally {
                game.teleporting(wp, false);
            }
        }
        game.vehicles().removeTemporary(r.vehicle);
        r.vehicle = 0;
        Player p = r.player();
        if (p != null) {
            if (r.bar != null) p.hideBossBar(r.bar);
            game.putBack(p);
            if (why != null) p.sendMessage(Lang.msg(why));
        }
    }

    /** Ends the race: everyone still in it is put back (results kept: true when it ended normally). */
    void close(boolean normal) {
        if (state == State.CLOSED) return;
        State was = state;
        state = State.CLOSED;
        for (Racer r : racers.values()) {
            Player p = r.player();
            if (p != null) p.hideBossBar(lobbyBar);
            if (r.racing) out(r, normal || was == State.LOBBY ? null : "circuit.stopped");
            else if (p != null && !normal) p.sendMessage(Lang.msg("circuit.stopped"));
            dropGhostView(r);
        }
        for (Watcher w : List.copyOf(watchers.values())) unwatch(w, normal ? "circuit.watch-ended" : "circuit.stopped", true);
        for (Chunk c : held) c.removePluginChunkTicket(game.plugin());
        held.clear();
        game.closed(this);
    }

    Racer racer(UUID u) {
        return racers.get(u);
    }

    // ------------------------------------------------------------------------------------------
    // 고스트
    // ------------------------------------------------------------------------------------------

    /** At the start: the racer is recorded (when ghosts are on) and the ghost they chose is asked for. */
    private void setUpGhost(Racer r) {
        if (!game.ghostsOn()) return;
        r.recorder = new GhostRecorder(game.ghostStep());
        r.splits = freshSplits();
        if (r.ghostChoice == Ghost.Choice.NONE) return;
        Player p = r.player();
        GhostStore.Meta m = r.ghostChoice == Ghost.Choice.MINE ? game.ghosts().mine(track.id(), r.id) : game.ghosts().fastest(track.id());
        if (m == null) {
            if (p != null) p.sendMessage(Lang.msg(r.ghostChoice == Ghost.Choice.MINE ? "circuit.ghost-none-mine" : "circuit.ghost-none-record"));
            return;
        }
        r.loadHolder = m.name();
        r.loadKart = m.kart();
        r.loadRecord = r.ghostChoice == Ghost.Choice.RECORD;
        r.ghostLoad = game.ghosts().load(m.track(), m.player());
        if (p != null) p.sendMessage(Lang.msg(r.loadRecord ? "circuit.ghost-vs-record" : "circuit.ghost-vs-mine", "player", m.name(),
                "time", RaceProgress.format(m.lapMs() / 1000.0)));
    }

    private double[] freshSplits() {
        double[] d = new double[Math.max(0, layout.gates().length - 1)];
        java.util.Arrays.fill(d, Double.NaN);
        return d;
    }

    /** Every tick: the ghost asked for arrives, and the ghost drives (from the line at each of the racer's laps). */
    private void ghostTick(Racer r, Player p) {
        if (r.ghostLoad != null && r.ghostLoad.isDone()) {
            Ghost g;
            try {
                g = r.ghostLoad.getNow(null);
            } catch (RuntimeException e) {
                g = null;
            }
            r.ghostLoad = null;
            if (g == null) p.sendMessage(Lang.msg("circuit.ghost-missing"));
            else if (r.ghost == null || g.lapMs() < r.ghost.lapMs()) useGhost(r, g, r.loadHolder, r.loadKart, r.loadRecord);
        }
        if (r.ghost == null) return;
        RaceProgress pr = r.progress;
        if (pr == null || pr.finished() || state == State.DONE) {
            dropGhostView(r);
            return;
        }
        // waiting on the line until the racer's lap begins; then as many seconds into its lap as they are into theirs
        double t = state == State.STARTING || pr.lap() == 0 || Double.isNaN(pr.lapStart()) ? 0 : seconds() - pr.lapStart();
        Ghost.Pose pose = r.ghost.at(t);
        if (r.ghostView != null && !r.ghostView.valid()) dropGhostView(r);
        if (r.ghostView == null) {
            int cx = (int) Math.floor(pose.x() + ox) >> 4, cz = (int) Math.floor(pose.z()) >> 4;
            if (!world.isChunkLoaded(cx, cz)) return;
            r.ghostView = game.ghostView(r, world, pose, ox);
            if (r.ghostView == null) return;
            r.ghostView.viewers(ghostViewers(r, p));
        } else if (tick % 10 == 0) r.ghostView.viewers(ghostViewers(r, p));
        r.ghostView.move(pose, ox);
    }

    /** Who sees a racer's ghost: the racer and whoever watches them. */
    private List<Player> ghostViewers(Racer r, Player p) {
        List<Player> out = new ArrayList<>();
        out.add(p);
        for (Watcher w : watchers.values()) {
            if (!r.id.equals(w.target)) continue;
            Player wp = Bukkit.getPlayer(w.id);
            if (wp != null) out.add(wp);
        }
        return out;
    }

    private void useGhost(Racer r, Ghost g, String holder, String kart, boolean record) {
        r.ghost = g;
        r.ghostHolder = holder;
        r.ghostKart = kart;
        r.ghostRecord = record;
        dropGhostView(r);      // drawn again (its kart, colour and name may differ)
    }

    void dropGhostView(Racer r) {
        if (r.ghostView != null) r.ghostView.remove();
        r.ghostView = null;
    }

    /** A gate past the line: its time this lap, and the gap to the ghost there. The line itself (lap 1 begins): recording starts. */
    private void gateForGhost(Racer r, Player p) {
        RaceProgress pr = r.progress;
        int k = pr.passedGate();
        if (k == 0) {
            if (r.recorder != null) r.recorder.lapStarted(pr.lapStart());
            r.splits = freshSplits();
            return;
        }
        if (r.splits == null || k < 1 || k > r.splits.length) return;
        double at = pr.passedAt() - pr.lapStart();
        r.splits[k - 1] = at;
        if (r.ghost != null) showGap(r, p, Ghost.gap(at, r.ghost.gate(k)), false);
    }

    /** A lap done: the gap to the ghost's lap, the lap kept as a ghost (the player's best is stored), a better ghost raced from now. */
    private void lapForGhost(Racer r, Player p) {
        RaceProgress pr = r.progress;
        List<Double> laps = pr.lapTimes();
        if (laps.isEmpty()) return;
        double lapTime = laps.getLast(), end = pr.passedAt(), start = end - lapTime;
        if (r.ghost != null) showGap(r, p, Ghost.gap(lapTime, r.ghost.lap()), true);
        if (r.recorder == null) return;
        double[] splits = r.splits == null ? freshSplits() : r.splits;
        r.splits = freshSplits();
        Ghost g;
        try {
            g = r.recorder.lap(start, end, splits);
        } catch (IllegalArgumentException e) {
            g = null;
        }
        if (g == null) return;
        boolean kept = game.ghosts().offer(track.id(), r.id, r.name, r.kart.id(), r.colour, g, System.currentTimeMillis(), game.maxGhosts());
        boolean faster = r.ghost == null || g.lapMs() < r.ghost.lapMs();
        if (pr.finished()) return;
        if (r.ghostChoice == Ghost.Choice.MINE && faster) {
            useGhost(r, g, r.name, r.kart.id(), false);
            HudModule.flash(p, Lang.mm("circuit.ghost-new-mine", "time", RaceProgress.format(g.lap())), 2500);
        } else if (r.ghostChoice == Ghost.Choice.RECORD && faster && kept) {
            GhostStore.Meta best = game.ghosts().fastest(track.id());
            if (best != null && best.player().equals(r.id)) {
                useGhost(r, g, r.name, r.kart.id(), true);
                HudModule.flash(p, Lang.mm("circuit.ghost-new-record", "time", RaceProgress.format(g.lap())), 2500);
            }
        }
    }

    /** "+0.42" in red (behind the ghost) or "-1.10" in green (ahead): flashed, and on the boss bar for a few seconds. */
    private void showGap(Racer r, Player p, double gap, boolean lap) {
        if (Double.isNaN(gap)) return;
        String key = gap <= 0 ? (lap ? "circuit.ghost-lap-ahead" : "circuit.ghost-gap-ahead") : (lap ? "circuit.ghost-lap-behind" : "circuit.ghost-gap-behind");
        r.gap = Lang.mm(gap <= 0 ? "circuit.ghost-bar-ahead" : "circuit.ghost-bar-behind", "gap", Ghost.gapText(gap));
        r.gapUntil = raceTicks + 80;
        HudModule.flash(p, Lang.mm(key, "gap", Ghost.gapText(gap)), lap ? 2600 : 1600);
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.MASTER, 0.35f, gap <= 0 ? 1.7f : 0.9f);
    }

    // ------------------------------------------------------------------------------------------
    // 관전
    // ------------------------------------------------------------------------------------------

    /** One watcher: whom the camera follows and how, the camera entity behind the kart, their keys of the last tick. */
    final class Watcher {
        final UUID id;
        UUID target;
        WatchRules.View view = WatchRules.View.CHASE;
        ItemDisplay camera;
        float camYaw = Float.NaN;
        int age, attachAt = 2, lastPlace, sneakAt = -1;
        boolean left, right, jump, sneak;
        BossBar bar, board;

        Watcher(UUID id) {
            this.id = id;
        }
    }

    /** The stage of the race as far as watching goes. */
    WatchRules.Stage watchStage() {
        return switch (state) {
            case LOBBY -> WatchRules.Stage.LOBBY;
            case STARTING, RUNNING, FINISHING -> WatchRules.Stage.RUNNING;
            case DONE -> WatchRules.Stage.ENDING;
            case CLOSED -> WatchRules.Stage.CLOSED;
        };
    }

    /** The racers in the race's order now (who the camera cycles through). */
    List<UUID> order() {
        List<UUID> out = new ArrayList<>();
        for (Racer r : standings()) if (r.racing && !r.left) out.add(r.id);
        return out;
    }

    List<Player> watcherPlayers() {
        List<Player> out = new ArrayList<>();
        for (Watcher w : watchers.values()) {
            Player p = Bukkit.getPlayer(w.id);
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * A player starts watching (fresh: from outside: their state is written onto them first and they become a
     * spectator; else they come from another race, already watching). target: the racer to follow first, or null.
     * Returns false when it could not be done (they are back as they were).
     */
    boolean watch(Player p, UUID target, boolean fresh) {
        if (fresh) {
            p.closeInventory();
            if (p.isInsideVehicle()) p.leaveVehicle();
            ReturnPoint.save(p);
            p.getInventory().clear();
            p.setFireTicks(0);
            p.setFallDistance(0);
            p.setGameMode(GameMode.SPECTATOR);
            if (p.getGameMode() != GameMode.SPECTATOR) {
                game.putBack(p);
                p.sendMessage(Lang.msg("circuit.watch-error"));
                return false;
            }
        }
        p.setPlayerTime(track.theme().time(), false);
        p.setPlayerWeather(WeatherType.CLEAR);
        Watcher w = new Watcher(p.getUniqueId());
        List<UUID> order = order();
        w.target = target != null && order.contains(target) ? target : WatchRules.next(order, null, 1);
        Location at = chaseSpot(w, w.target == null ? null : racers.get(w.target));
        if (at == null) {
            TrackLayout.Slot g = layout.grid().getFirst();
            at = new Location(world, g.x() + ox, TrackLayout.BASE_Y + g.y() + 6, g.z(), g.yaw(), 20);
        }
        boolean ok;
        game.teleporting(p, true);
        try {
            if (p.getSpectatorTarget() != null) p.setSpectatorTarget(null);
            ok = p.teleport(at);
        } finally {
            game.teleporting(p, false);
        }
        if (!ok) {
            if (fresh) game.putBack(p);
            p.sendMessage(Lang.msg("circuit.watch-error"));
            return false;
        }
        newCamera(w, p, at);
        w.bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        w.board = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        p.showBossBar(w.bar);
        p.showBossBar(w.board);
        watchers.put(p.getUniqueId(), w);
        p.sendMessage(Lang.msg("circuit.watch-start", "track", track.name()));
        p.sendMessage(Lang.msg("circuit.watch-keys"));
        HudModule.flash(p, Lang.mm("circuit.watch-hint-chase"), 5000);
        drawWatch(w, w.target == null ? null : racers.get(w.target));
        return true;
    }

    /** A watcher stops watching: off the camera, the camera gone, and (home) back where they were with everything. */
    void unwatch(Watcher w, String why, boolean home) {
        if (watchers.remove(w.id) == null) return;
        Player p = Bukkit.getPlayer(w.id);
        if (p != null) {
            if (w.bar != null) p.hideBossBar(w.bar);
            if (w.board != null) p.hideBossBar(w.board);
            if (p.getGameMode() == GameMode.SPECTATOR && p.getSpectatorTarget() != null) {
                game.teleporting(p, true);
                try {
                    p.setSpectatorTarget(null);
                } finally {
                    game.teleporting(p, false);
                }
            }
            for (Racer r : racers.values()) {
                Player rp = r.player();
                if (r.ghostView != null && rp != null) r.ghostView.viewers(List.of(rp));
            }
        }
        if (w.camera != null) w.camera.remove();
        if (p != null && home) {
            game.putBack(p);
            if (why != null) p.sendMessage(Lang.msg(why));
        }
    }

    Watcher watcher(UUID u) {
        return watchers.get(u);
    }

    /** A watcher clicked a racer in the free view (Paper's start-spectating): follow them through their eyes. */
    boolean watchClicked(Watcher w, Entity e) {
        if (!(e instanceof Player rp)) return false;
        Racer r = racers.get(rp.getUniqueId());
        if (r == null || !r.racing || r.left) return false;
        w.target = r.id;
        w.view = WatchRules.View.ONBOARD;
        return true;
    }

    private void watchTick() {
        List<UUID> order = order();
        for (Watcher w : List.copyOf(watchers.values())) {
            Player p = Bukkit.getPlayer(w.id);
            if (p == null) {
                // gone without a quit (should not happen): their way back takes them home when they log in
                unwatch(w, null, false);
                continue;
            }
            if (p.getGameMode() != GameMode.SPECTATOR || !p.getWorld().equals(world) || p.isDead()) {
                unwatch(w, "circuit.watch-lost", true);
                continue;
            }
            w.age++;
            UUID kept = WatchRules.keep(order, w.target, w.lastPlace);
            if (!Objects.equals(kept, w.target)) {
                w.target = kept;
                w.camYaw = Float.NaN;
                Racer nr = kept == null ? null : racers.get(kept);
                if (nr != null) HudModule.flash(p, Lang.mm("circuit.watch-target", "player", nr.name), 1500);
            }
            int idx = w.target == null ? -1 : order.indexOf(w.target);
            if (idx >= 0) w.lastPlace = idx;
            var in = p.getCurrentInput();
            boolean left = in.isLeft(), right = in.isRight(), jump = in.isJump(), sneak = in.isSneak();
            if (w.view.follows()) {
                if (left && !w.left) cycle(w, p, order, -1);
                if (right && !w.right) cycle(w, p, order, 1);
                if (sneak && !w.sneak) {
                    if (WatchRules.leaves(w.sneakAt, w.age)) {
                        unwatch(w, "circuit.watch-left", true);
                        continue;
                    }
                    w.sneakAt = w.age;
                    HudModule.flash(p, Lang.mm("circuit.watch-leave-confirm"), 3000);
                }
            }
            if (jump && !w.jump) {
                w.view = w.view.next();
                HudModule.flash(p, Lang.mm(switch (w.view) {
                    case CHASE -> "circuit.watch-hint-chase";
                    case ONBOARD -> "circuit.watch-hint-onboard";
                    case FREE -> "circuit.watch-hint-free";
                }), 3500);
                p.playSound(p, Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 1.4f);
            }
            w.left = left;
            w.right = right;
            w.jump = jump;
            w.sneak = sneak;
            Racer r = w.target == null ? null : racers.get(w.target);
            camera(w, p, r);
            if (w.age % 2 == 0) drawWatch(w, r);
        }
    }

    private void cycle(Watcher w, Player p, List<UUID> order, int dir) {
        UUID next = WatchRules.next(order, w.target, dir);
        if (next == null || next.equals(w.target)) return;
        w.target = next;
        w.camYaw = Float.NaN;
        Racer r = racers.get(next);
        if (r != null) HudModule.flash(p, Lang.mm("circuit.watch-target", "player", r.name), 1500);
        p.playSound(p, Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 1.8f);
    }

    /** Where the chase camera hangs behind a racer's kart now (walls pull it in), or null when there is no kart. */
    private Location chaseSpot(Watcher w, Racer r) {
        if (r == null) return null;
        VehiclePhysics.State st = r.vehicle == 0 ? null : game.vehicles().temporaryState(r.vehicle);
        double kx, ky, kz;
        float yaw;
        if (st != null) {
            kx = st.x;
            ky = st.y;
            kz = st.z;
            yaw = st.yaw;
        } else {
            Player rp = r.player();
            if (rp == null || !rp.getWorld().equals(world)) return null;
            Location l = rp.getLocation();
            kx = l.getX();
            ky = l.getY();
            kz = l.getZ();
            yaw = l.getYaw();
        }
        w.camYaw = Float.isNaN(w.camYaw) ? yaw : WatchRules.approach(w.camYaw, yaw, 0.2);
        double[] c = WatchRules.chase(kx, ky, kz, w.camYaw, WatchRules.CHASE_BACK, WatchRules.CHASE_UP);
        Location eye = new Location(world, kx, ky + 1.2, kz);
        Vector to = new Vector(c[0] - kx, c[1] - eye.getY(), c[2] - kz);
        double len = to.length();
        if (len > 1e-3) {
            Vector dir = to.clone().multiply(1 / len);
            RayTraceResult hit = world.rayTraceBlocks(eye, dir, len + 0.4, FluidCollisionMode.NEVER, true);
            if (hit != null) {
                Vector at = hit.getHitPosition().subtract(dir.clone().multiply(0.45));
                c = new double[]{at.getX(), at.getY(), at.getZ()};
            }
        }
        return new Location(world, c[0], c[1], c[2], w.camYaw, (float) WatchRules.CHASE_PITCH);
    }

    /** The chase camera: an empty display entity only this watcher knows of (spectating it puts them behind the kart). */
    private void newCamera(Watcher w, Player p, Location at) {
        if (w.camera != null) w.camera.remove();
        w.camera = world.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setTeleportDuration(Math.max(2, game.smoothing()));
            d.getPersistentDataContainer().set(GhostView.TAG, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 2);
        });
        p.showEntity(game.plugin(), w.camera);
        w.attachAt = w.age + 2;      // the client must know it before it can look through it
    }

    private void camera(Watcher w, Player p, Racer r) {
        Location spot = chaseSpot(w, r);
        if (w.camera == null || !w.camera.isValid()) newCamera(w, p, spot != null ? spot : p.getLocation());
        if (spot != null && w.camera != null && w.camera.isValid()) w.camera.teleport(spot);
        switch (w.view) {
            case CHASE -> follow(w, p, w.camera);
            case ONBOARD -> follow(w, p, r == null ? null : r.player());
            case FREE -> {
                follow(w, p, null);
                if (w.age % 10 == 0) {
                    Location l = p.getLocation();
                    double[] in = WatchRules.inside(l.getX(), l.getY(), l.getZ(), layout.minX() + ox, layout.maxX() + ox, TrackLayout.BASE_Y - 8,
                            TrackLayout.BASE_Y + layout.maxY() + 40, layout.minZ(), layout.maxZ(), 24);
                    if (in != null) {
                        game.teleporting(p, true);
                        try {
                            p.teleport(new Location(world, in[0], in[1], in[2], l.getYaw(), l.getPitch()));
                        } finally {
                            game.teleporting(p, false);
                        }
                        HudModule.flash(p, Lang.mm("circuit.watch-bounds"), 1500);
                    }
                }
            }
        }
    }

    /**
     * The watcher's camera on this entity (null: their own, flying free). A far one is reached first (a teleport, then
     * the camera a few ticks later, once the client knows the entity).
     */
    private void follow(Watcher w, Player p, Entity e) {
        Entity cur = p.getSpectatorTarget();
        // never anything outside the race's world (a racer on their way home)
        if (e != null && (!e.isValid() || !e.getWorld().equals(world))) e = null;
        if (e == null && w.view != WatchRules.View.FREE) return;     // nothing to follow this tick: stay where it is
        if (e == null ? cur == null : e.equals(cur)) return;
        game.teleporting(p, true);
        try {
            if (e == null) {
                p.setSpectatorTarget(null);
                return;
            }
            if (w.age < w.attachAt) return;
            if (!p.getWorld().equals(world)) return;
            if (p.getLocation().distanceSquared(e.getLocation()) > 24 * 24) {
                if (cur != null) p.setSpectatorTarget(null);
                p.teleport(e.getLocation().clone().add(0, 1.5, 0));
                w.attachAt = w.age + 3;
                return;
            }
            p.setSpectatorTarget(e);
        } finally {
            game.teleporting(p, false);
        }
    }

    /** A watcher's two bars: the followed racer's line (as their own), and the order of the race. */
    private void drawWatch(Watcher w, Racer r) {
        if (r == null || r.progress == null) {
            w.bar.name(Lang.mm("circuit.watch-bar-none"));
            w.bar.progress(0);
        } else {
            w.bar.name(Lang.mm("circuit.watch-bar", "player", r.name, "view", Lang.raw("circuit.watch-view." + w.view.id)).append(hud(r)));
            w.bar.progress(along(r));
        }
        StringBuilder line = new StringBuilder();
        int k = 0;
        for (Racer o : standings()) {
            if (o.left) continue;
            k++;
            if (k > 8) break;
            if (!line.isEmpty()) line.append(Lang.raw("circuit.watch-board-sep"));
            boolean fin = o.progress != null && o.progress.finished();
            line.append(Lang.raw(o.id.equals(w.target) ? "circuit.watch-board-me" : fin ? "circuit.watch-board-done" : "circuit.watch-board-one",
                    "place", k, "player", o.name));
        }
        w.board.name(line.isEmpty() ? Component.empty() : dev.stsv.core.util.Text.mm(line.toString()));
    }
}
