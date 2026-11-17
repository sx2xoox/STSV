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
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
        int wrongTold;
        int lastLap;
        BossBar bar;

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
        for (Racer r : racers.values()) r.bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
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
        }
        if (t == COUNTDOWN) {
            state = State.RUNNING;
            raceTicks = 0;
            for (Racer r : active()) {
                Player p = r.player();
                if (p == null) continue;
                game.vehicles().freezeTemporary(r.vehicle, false);
                p.showTitle(CircuitHud.title(CircuitHud.GO, Component.empty(), 0, 16, 6));
                p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1f, 1.6f);
                p.playSound(p, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.MASTER, 0.7f, 1.2f);
                // the start boost: W pressed in the last half second (not held all along)
                if (r.forwardSince >= COUNTDOWN - 12 && r.forwardSince <= COUNTDOWN) {
                    r.gauge.free(1.25, 28);
                    kick(r, 1.15);
                    HudModule.flash(p, Lang.mm("circuit.start-boost"), 1500);
                    p.playSound(p, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.MASTER, 0.6f, 1.4f);
                }
            }
        }
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
            RaceProgress.Event ev = r.progress.move(spot.index(), seconds(), 0.05);
            if (ev != RaceProgress.Event.NONE) passed(r, p, ev);
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
            case GATE -> p.playSound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.MASTER, 0.35f, 1.9f);
            case LAP -> {
                List<Double> laps = r.progress.lapTimes();
                double last = laps.getLast();
                boolean finalLap = r.progress.lap() == r.progress.laps();
                p.showTitle(CircuitHud.title(CircuitHud.LAP, Lang.mm(finalLap ? "circuit.final-lap" : "circuit.lap-sub", "lap", r.progress.lap(),
                        "laps", r.progress.laps(), "time", RaceProgress.format(last)), 2, 30, 8));
                p.playSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 0.8f, finalLap ? 1.6f : 1.2f);
                long ms = Math.round(last * 1000), rec = game.store().best(track.id(), true);
                if (rec == 0 || ms < rec) HudModule.flash(p, Lang.mm("circuit.lap-fast", "time", RaceProgress.format(last)), 2500);
            }
            case FINISH -> finish(r, p);
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
        List<Racer> order = standings();
        int place = order.indexOf(r) + 1;
        RaceProgress pr = r.progress;
        double clock = pr.finished() ? pr.finishTime() : state == State.STARTING ? 0 : seconds();
        r.bar.name(CircuitHud.bar(Math.max(1, pr.lap()), pr.laps(), place, order.size(), RaceProgress.formatShort(clock), r.gauge.stored(),
                r.gauge.gauge(), r.gauge.boosting()));
        // the bar fills along the lap
        double along = pr.lap() == 0 ? 0 : (path.ahead(layout.gates()[0], pr.at()) / (double) path.size());
        r.bar.progress((float) Math.clamp(along, 0, 1));
        r.bar.color(r.gauge.boosting() ? BossBar.Color.RED : place == 1 ? BossBar.Color.YELLOW : BossBar.Color.BLUE);
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
        }
        for (Chunk c : held) c.removePluginChunkTicket(game.plugin());
        held.clear();
        game.closed(this);
    }

    Racer racer(UUID u) {
        return racers.get(u);
    }
}
