package dev.stsv.economy.bus;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.vehicle.VehicleModule;
import dev.stsv.economy.EcoPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 버스 내비게이션 (README "마을버스", 내비게이션): while driving a bus, `/버스 내비` picks a stop (a menu: where the riders get off,
 * event stops, favourites, every stop by line) and the driver is guided there along the shortest way over the 국도 network
 * ({@link BusRoutes#tree}: the graph fares are counted on, junctions and level crossings included; the bus snaps to its
 * nearest road point, the target is the stop's road point). Every 10 ticks, for navigating drivers only: where the bus is on
 * the way (a window around the last place), a new way when it is more than {@link #OFF_ROUTE} blocks off it, a ding and a
 * voice line before a turn, a few particles on the road ahead that only the driver sees, the arrival. The driver's action bar
 * line ({@link #hud}) replaces the line's while navigating. Nothing is saved but the favourites (eco.kv); a route lives in
 * memory and ends when the driver gets out, leaves or the plugin stops. The ways are worked out off the main thread.
 */
final class BusNavigator {

    static final String MENU = "economy:bus_nav";
    /** More than this off the way (blocks): a new way is found. */
    static final double OFF_ROUTE = 40;
    /** This near the target stop: "목적지 …입니다". */
    static final double ARRIVE = 40;
    /** This far past the stop after arriving (blocks): passed it. */
    static final double PASSED = 60;
    /** The voice lines before a turn: at about this far, then "잠시 후". */
    static final double CALL_FAR = 120, CALL_NEAR = 40;
    /** Where the particles go on the road ahead (blocks along the way). */
    static final double[] GUIDE = {6, 12, 18, 24, 30};
    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(0x4f, 0xd1, 0xff), 1.0f);

    private final BusModule bus;
    private final EcoPlugin eco;
    private final STSVPlugin core;
    private final Map<UUID, Nav> navs = new HashMap<>();
    /** the stop a driver was last guided to (/버스 내비 다시) */
    private final Map<UUID, Integer> last = new HashMap<>();
    /** a road graph -> stop id -> its road point (worked out once a graph) */
    private final Map<BusRoutes, Map<Integer, Integer>> stopNodes = Collections.synchronizedMap(new WeakHashMap<>());

    /** A driver's navigation. */
    static final class Nav {
        /** the target stop (-1: 손님 순서 before its first pick) */
        int stop;
        /** 손님 순서대로: the next target is the nearest stop a rider gets off at */
        final boolean riders;
        NavRoute route;
        String world;
        int idx = -1, gen;
        boolean routing, arrived, standing;
        long failAt, rerouteAt, rerouteSaidAt;
        final Set<Long> said = new HashSet<>();

        Nav(int stop, boolean riders) {
            this.stop = stop;
            this.riders = riders;
        }
    }

    BusNavigator(BusModule bus, EcoPlugin eco, STSVPlugin core) {
        this.bus = bus;
        this.eco = eco;
        this.core = core;
    }

    void stop() {
        navs.clear();
        last.clear();
    }

    boolean navigating(UUID id) {
        return navs.containsKey(id);
    }

    /** The bus this player drives, or null. */
    private VehicleModule.Driven driven(Player p) {
        VehicleModule vm = core.vehicles();
        VehicleModule.Driven d = vm == null ? null : vm.drivenBy(p);
        return d == null || !bus.spec().vehicles().contains(d.itemKey()) ? null : d;
    }

    private double eta(double blocks) {
        return blocks / Math.max(0.1, bus.spec().fare().onTimeSpeed());
    }

    private String etaText(double blocks) {
        return Double.isFinite(blocks) ? BusModule.clock(eta(blocks)) : "-";
    }

    // ------------------------------------------------------------------------------------------
    // /버스 내비 …
    // ------------------------------------------------------------------------------------------

    /** /버스 내비 [정류장 이름 | 손님 | 끄기 | 다시]: the menu, a stop, 손님 순서대로, off, the last target again. */
    void command(Player p, String[] args) {
        String word = String.join(" ", args).trim();
        if (word.equals("끄기")) {
            off(p, true);
            return;
        }
        if (driven(p) == null) {
            p.sendMessage(Lang.msg("bus.nav-not-driving"));
            return;
        }
        if (word.isEmpty()) {
            openMenu(p);
            return;
        }
        if (word.equals("손님")) {
            start(p, -1, true);
            return;
        }
        if (word.equals("다시")) {
            Integer s = last.get(p.getUniqueId());
            if (s == null || !bus.stopMap().containsKey(s)) p.sendMessage(Lang.msg("bus.nav-no-last"));
            else start(p, s, false);
            return;
        }
        BusStop s = bus.stop(word);   // its name (with spaces, or in quotes) or its number (#12)
        if (s == null) {
            p.sendMessage(Lang.msg("bus.nav-no-stop", "name", word));
            return;
        }
        if (!s.world().equals(p.getWorld().getName())) {
            p.sendMessage(Lang.msg("bus.nav-other-world", "stop", s.name()));
            return;
        }
        start(p, s.id(), false);
    }

    /** Tab completion of /버스 내비 …: args after 내비 (a stop's name, quoted when it has spaces, or its number #12: StopNames). */
    List<String> complete(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length <= 1) for (String k : List.of("손님", "끄기", "다시")) if (args.length == 0 || k.startsWith(args[0])) out.add(k);
        out.addAll(StopNames.complete(bus.stops(), args, 0));
        return out.stream().distinct().toList();
    }

    void off(Player p, boolean say) {
        Nav n = navs.remove(p.getUniqueId());
        if (n != null && n.stop >= 0) last.put(p.getUniqueId(), n.stop);
        if (say) p.sendMessage(Lang.msg(n == null ? "bus.nav-not-on" : "bus.nav-off"));
    }

    private void start(Player p, int stop, boolean riders) {
        Nav n = new Nav(stop, riders);
        n.world = p.getWorld().getName();
        navs.put(p.getUniqueId(), n);
        if (riders) p.sendMessage(Lang.msg("bus.nav-riders-on"));
        route(p, n, false);
    }

    /** The road points of the stops of a graph (one look each, then kept). */
    private Integer node(BusRoutes routes, BusStop s) {
        Map<Integer, Integer> m = stopNodes.computeIfAbsent(routes, r -> Collections.synchronizedMap(new HashMap<>()));
        Integer k = m.get(s.id());
        if (k == null) {
            k = routes.attach(s.x(), s.z());
            m.put(s.id(), k);
        }
        return k < 0 ? null : k;
    }

    /** Where the riders get off (a stop id each: not the 선글라스 남자, not "the next stop"). */
    private static List<Integer> dests(BusModule.Run run) {
        List<Integer> out = new ArrayList<>();
        if (run != null) for (BusModule.Rider r : run.riders) if (!r.secret && r.dest >= 0) out.add(r.dest);
        return out;
    }

    private BusModule.Run run(VehicleModule.Driven d) {
        return d == null ? null : bus.runMap().get(d.id());
    }

    private enum Result {OK, NO_NET, NO_ROAD, NO_DROPS, NO_WAY}

    /**
     * A way for this navigation from where the bus is (worked out off the main thread): to its stop, or (손님 순서대로) to the
     * nearest stop a rider gets off at (not the one it stands at). `again`: a new way for the same trip (off the way).
     */
    private void route(Player p, Nav n, boolean again) {
        BusModule.Net net = bus.net(p.getWorld().getName());
        UUID id = p.getUniqueId();
        if (net == null) {
            finish(p, n, Result.NO_NET);
            return;
        }
        BusRoutes routes = net.routes();
        double x = p.getX(), z = p.getZ();
        List<Integer> dests = n.riders ? dests(run(driven(p))) : List.of();
        Integer except = n.riders && n.standing ? n.stop : null;
        Map<Integer, BusStop> all = new HashMap<>(bus.stopMap());
        int gen = ++n.gen, fixed = n.stop;
        n.routing = true;
        n.world = p.getWorld().getName();
        Bukkit.getScheduler().runTaskAsynchronously(eco, () -> {
            Result res;
            NavRoute r = null;
            int target = fixed;
            int from = routes.attach(x, z);
            if (from < 0) res = Result.NO_ROAD;
            else {
                BusRoutes.Tree t = routes.tree(from);
                if (n.riders) {
                    Integer pick = NavRoute.nearestDrop(dests, s -> {
                        BusStop st = all.get(s);
                        Integer k = st == null ? null : node(routes, st);
                        return k == null ? Double.POSITIVE_INFINITY : t.dist()[k];
                    }, except);
                    target = pick == null ? -1 : pick;
                }
                BusStop st = all.get(target);
                Integer to = st == null ? null : node(routes, st);
                int[] path = to == null ? new int[0] : t.path(to);
                if (n.riders && target < 0) res = Result.NO_DROPS;
                else if (path.length == 0) res = Result.NO_WAY;
                else {
                    res = Result.OK;
                    r = NavRoute.of(routes, path);
                }
            }
            Result fres = res;
            NavRoute fr = r;
            int ft = target;
            Bukkit.getScheduler().runTask(eco, () -> {
                if (navs.get(id) != n || n.gen != gen) return;   // ended or asked again meanwhile
                n.routing = false;
                Player q = Bukkit.getPlayer(id);
                if (q == null) {
                    navs.remove(id);
                    return;
                }
                if (fres != Result.OK) {
                    finish(q, n, fres);
                    return;
                }
                boolean changed = ft != n.stop || !again;
                n.stop = ft;
                n.route = fr;
                n.idx = -1;
                n.said.clear();
                n.arrived = false;
                n.standing = false;
                BusStop s = bus.stopMap().get(ft);
                if (changed && s != null) {
                    q.sendMessage(Lang.msg(n.riders ? "bus.nav-to-riders" : "bus.nav-to", "stop", s.name(),
                            "km", BusModule.km(fr.length()), "eta", etaText(fr.length()), "n", dests(run(driven(q))).stream().filter(d -> d == ft).count()));
                    q.playSound(q.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.4f);
                }
            });
        });
    }

    private void finish(Player p, Nav n, Result why) {
        n.routing = false;
        switch (why) {
            case NO_ROAD -> {   // off the roads: tried again in a little while
                n.failAt = System.currentTimeMillis();
                if (n.failAt - n.rerouteSaidAt > 10_000) {
                    n.rerouteSaidAt = n.failAt;
                    p.sendMessage(Lang.msg("bus.nav-no-road"));
                }
            }
            case NO_DROPS -> {
                navs.remove(p.getUniqueId());
                p.sendMessage(Lang.msg("bus.nav-riders-done"));
            }
            case NO_NET -> {
                navs.remove(p.getUniqueId());
                p.sendMessage(Lang.msg("bus.nav-no-net"));
            }
            default -> {
                navs.remove(p.getUniqueId());
                BusStop s = bus.stopMap().get(n.stop);
                p.sendMessage(Lang.msg("bus.nav-no-way", "stop", s == null ? "?" : s.name()));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Guiding (every 10 ticks, navigating drivers only)
    // ------------------------------------------------------------------------------------------

    void tick() {
        if (navs.isEmpty()) return;
        long now = System.currentTimeMillis();
        BusSpec.Stops cfg = bus.spec().stops();
        for (Iterator<Map.Entry<UUID, Nav>> it = navs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Nav> e = it.next();
            Nav n = e.getValue();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {   // left
                it.remove();
                continue;
            }
            VehicleModule.Driven d = driven(p);
            if (d == null) {   // got out
                it.remove();
                if (n.stop >= 0) last.put(p.getUniqueId(), n.stop);
                p.sendMessage(Lang.msg("bus.nav-off"));
                continue;
            }
            if (!d.world().getName().equals(n.world)) {
                if (!n.routing) route(p, n, true);
                continue;
            }
            if (n.route == null) {
                if (!n.routing && now - n.failAt > 3000) route(p, n, true);
                continue;
            }
            BusStop s = bus.stopMap().get(n.stop);
            if (s == null) {   // the stop was removed
                it.remove();
                p.sendMessage(Lang.msg("bus.nav-off"));
                continue;
            }
            NavRoute r = n.route;
            double[] off = new double[1];
            n.idx = r.nearest(d.x(), d.z(), n.idx, off);
            BusModule.Run run = run(d);
            List<Integer> dests = dests(run);
            // 손님 순서대로: nobody gets off at the target any more (missed it, got off elsewhere): the next nearest
            if (n.riders && !n.standing && !n.routing && !dests.contains(n.stop)) {
                route(p, n, true);
                continue;
            }
            double toStop = Math.hypot(s.x() - d.x(), s.z() - d.z());
            boolean inBay = Math.abs(d.speed()) < 0.5 && s.standing(d.x(), d.z(), cfg.stopReach(), cfg.bayWidth());
            if (inBay) {
                if (!n.riders) {
                    it.remove();
                    last.put(p.getUniqueId(), n.stop);
                    p.sendMessage(Lang.msg("bus.nav-done", "stop", s.name()));
                    continue;
                }
                n.standing = true;   // 손님 순서대로: once those getting off here are off, on to the next
                if (!dests.contains(n.stop) && !n.routing) route(p, n, true);
                continue;
            }
            if (n.standing && !n.routing) {   // drove off before everyone got off: the next nearest
                route(p, n, true);
                continue;
            }
            if (!n.arrived && toStop <= ARRIVE) {
                n.arrived = true;
                p.sendMessage(Lang.msg("bus.nav-arrive", "stop", s.name()));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 1.2f);
                Bukkit.getScheduler().runTaskLater(eco, () -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 1.6f), 4L);
            }
            if (n.arrived && toStop > PASSED) {   // went past it
                if (n.riders) {
                    p.sendMessage(Lang.msg("bus.nav-passed-riders", "stop", s.name()));
                    route(p, n, true);
                } else {
                    it.remove();
                    last.put(p.getUniqueId(), n.stop);
                    p.sendMessage(Lang.msg("bus.nav-passed", "stop", s.name()));
                }
                continue;
            }
            if (!n.arrived && off[0] > OFF_ROUTE && !n.routing && now - n.rerouteAt > 3000) {
                n.rerouteAt = now;
                if (now - n.rerouteSaidAt > 10_000) {
                    n.rerouteSaidAt = now;
                    HudModule.flash(p, Lang.mm("bus.nav-reroute"), 1500);
                    p.sendMessage(Lang.msg("bus.nav-reroute"));
                }
                route(p, n, true);
                continue;
            }
            // a turn coming: a ding and a voice line at about 120 blocks, then just before it
            NavRoute.Ahead m = r.nextManoeuvre(n.idx, CALL_FAR + 10);
            if (m != null) {
                boolean near = m.distance() <= CALL_NEAR;
                long key = m.turn().index() * 2L + (near ? 1 : 0);
                if ((near || m.distance() > CALL_NEAR + 20) && n.said.add(key)) {
                    String turn = Lang.raw(m.turn().turn() < 0 ? "bus.nav-left" : "bus.nav-right");
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, near ? 1.7f : 1.4f);
                    p.sendMessage(near ? Lang.mm("bus.nav-voice-near", "turn", turn)
                            : Lang.mm("bus.nav-voice-far", "turn", turn, "m", Math.round(m.distance() / 10.0) * 10));
                }
            }
            guide(p, r, n.idx, s, n.arrived);
        }
    }

    /** The road ahead lit for the driver only: a few small dots along the way 6–30 blocks on, the bay when arrived. */
    private void guide(Player p, NavRoute r, int idx, BusStop s, boolean arrived) {
        int lastK = -1;
        for (double a : GUIDE) {
            int k = r.indexAhead(idx, a);
            if (k == lastK || k <= idx) continue;
            lastK = k;
            double[] pt = r.point(k);
            p.spawnParticle(Particle.DUST, pt[0], pt[2] + 0.25, pt[1], 2, 0.15, 0.03, 0.15, 0, DUST);
        }
        if (arrived && s.bay()) {
            double[] bay = s.at(0, BusStop.ROAD_HALF + bus.spec().stops().bayWidth() / 2.0);
            p.spawnParticle(Particle.END_ROD, bay[0], s.top() + 0.6, bay[1], 3, 0.6, 0.3, 0.6, 0);
        }
    }

    /**
     * The driver's action bar line while navigating (else null): the target, an arrow to the way ~24 blocks on, the road
     * distance left and when, the next junction within 300 blocks and which way, the riders and the events part.
     */
    Component hud(Player p, BusModule.Run r, String extra) {
        Nav n = navs.get(p.getUniqueId());
        if (n == null) return null;
        String mode = n.riders ? Lang.raw("bus.nav-hud-riders") : "";
        BusStop s = n.stop < 0 ? null : bus.stopMap().get(n.stop);
        if (n.route == null || s == null)
            return Lang.mm("bus.nav-hud-finding", "mode", mode, "riders", r.riders.size(), "seats", r.seats, "extra", extra);
        if (n.arrived) return Lang.mm("bus.nav-hud-arrived", "mode", mode, "stop", s.name(), "riders", r.riders.size(), "seats", r.seats, "extra", extra);
        NavRoute route = n.route;
        int idx = Math.max(0, n.idx);
        double left = route.remaining(idx);
        double[] to = left > 24 ? route.point(route.indexAhead(idx, 24)) : new double[]{s.x(), s.z()};
        String arrow = NavRoute.arrow(p.getX(), p.getZ(), p.getLocation().getYaw(), to[0], to[1]);
        NavRoute.Ahead t = route.nextTurn(idx, 300);
        String turn = t == null ? "" : Lang.raw("bus.nav-hud-turn", "m", Math.round(t.distance() / 10.0) * 10,
                "turn", Lang.raw(t.turn().turn() < 0 ? "bus.turn-left" : t.turn().turn() > 0 ? "bus.turn-right" : "bus.turn-straight"));
        int offN = 0;
        for (BusModule.Rider x : r.riders) if (x.dest == s.id()) offN++;
        return Lang.mm("bus.nav-hud", "mode", mode, "arrow", arrow, "stop", s.name(), "km", BusModule.km(left), "eta", etaText(left),
                "turn", turn, "off", offN > 0 ? Lang.raw("bus.hud-off-n", "n", offN) : "", "riders", r.riders.size(), "seats", r.seats,
                "extra", extra);
    }

    // ------------------------------------------------------------------------------------------
    // The stop picker (economy:bus_nav)
    // ------------------------------------------------------------------------------------------

    private String favKey(UUID id) {
        return "bus.nav.fav." + id;
    }

    Set<Integer> favourites(UUID id) {
        Set<Integer> out = new LinkedHashSet<>();
        for (String s : eco.kv().get(favKey(id), "").split(",")) {
            try {
                if (!s.isBlank()) out.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // a bad entry: skipped
            }
        }
        return out;
    }

    private void toggleFavourite(Player p, int stop) {
        Set<Integer> f = favourites(p.getUniqueId());
        boolean on = f.add(stop);
        if (!on) f.remove(stop);
        f.removeIf(s -> !bus.stopMap().containsKey(s));
        if (f.isEmpty()) eco.kv().remove(favKey(p.getUniqueId()));
        else eco.kv().set(favKey(p.getUniqueId()), String.join(",", f.stream().map(String::valueOf).toList()));
        BusStop s = bus.stopMap().get(stop);
        HudModule.flash(p, Lang.mm(on ? "bus.nav-fav-on" : "bus.nav-fav-off", "stop", s == null ? "?" : s.name()), 1500);
    }

    /** The menu: every stop's road distance from the bus worked out off the main thread, then opened. */
    private void openMenu(Player p) {
        BusModule.Net net = bus.net(p.getWorld().getName());
        if (net == null) {
            p.sendMessage(Lang.msg("bus.nav-no-net"));
            return;
        }
        BusRoutes routes = net.routes();
        double x = p.getX(), z = p.getZ();
        String world = p.getWorld().getName();
        List<BusStop> here = new ArrayList<>();
        for (BusStop s : bus.stops()) if (s.world().equals(world)) here.add(s);
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(eco, () -> {
            Map<Integer, Double> dist = new HashMap<>();
            int from = routes.attach(x, z);
            BusRoutes.Tree t = from < 0 ? null : routes.tree(from);
            for (BusStop s : here) {
                Integer k = node(routes, s);
                dist.put(s.id(), t == null || k == null ? Double.POSITIVE_INFINITY : t.dist()[k]);
            }
            Bukkit.getScheduler().runTask(eco, () -> {
                Player q = Bukkit.getPlayer(id);
                if (q == null || !q.isOnline()) return;
                core.menus().open(q, MENU, new Picker(net, here, dist));
            });
        });
    }

    /** The stop picker: sections (손님이 내릴 곳 · 이벤트 · 즐겨찾기 · 노선마다 전체), each from the start of a row. */
    private final class Picker implements MenuHandler {
        private final BusModule.Net net;
        private final List<BusStop> here;
        private final Map<Integer, Double> dist;
        /** list position -> stop id (absent: a header or a gap) */
        private final Map<Integer, Integer> at = new HashMap<>();

        Picker(BusModule.Net net, List<BusStop> here, Map<Integer, Double> dist) {
            this.net = net;
            this.here = here;
            this.dist = dist;
        }

        private double d(int stop) {
            return dist.getOrDefault(stop, Double.POSITIVE_INFINITY);
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            Nav n = navs.get(v.player().getUniqueId());
            BusStop s = n == null || n.stop < 0 ? null : bus.stopMap().get(n.stop);
            return Map.of("count", String.valueOf(here.size()),
                    "target", n == null ? Lang.raw("bus.nav-menu-none") : n.riders ? Lang.raw("bus.nav-menu-riders") + (s == null ? "" : " · " + s.name())
                            : s == null ? "?" : s.name());
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            Player p = v.player();
            at.clear();
            List<ItemStack> out = new ArrayList<>();
            Map<Integer, BusStop> byId = new HashMap<>();
            for (BusStop s : here) byId.put(s.id(), s);
            Set<Integer> fav = favourites(p.getUniqueId());
            Nav n = navs.get(p.getUniqueId());
            int target = n == null ? -1 : n.stop;
            Comparator<Integer> near = Comparator.comparingDouble(this::d);
            // (a) where the riders get off: how many, who
            BusModule.Run run = run(driven(p));
            Map<Integer, List<BusModule.Rider>> drops = new LinkedHashMap<>();
            if (run != null) for (BusModule.Rider r : run.riders) if (!r.secret && r.dest >= 0 && byId.containsKey(r.dest))
                drops.computeIfAbsent(r.dest, k -> new ArrayList<>()).add(r);
            if (!drops.isEmpty()) {
                header(out, Material.LIME_STAINED_GLASS_PANE, Lang.raw("bus.nav-sec-drop"), Lang.raw("bus.nav-sec-drop-lore"));
                for (int sid : drops.keySet().stream().sorted(near).toList()) {
                    List<String> extra = new ArrayList<>();
                    StringBuilder who = new StringBuilder();
                    for (BusModule.Rider r : drops.get(sid)) who.append(r.special != null ? "<gold>[" + r.special.name() + "]</gold>" : bus.typeTag(r.type)).append(' ');
                    extra.add(Lang.raw("bus.nav-drop-n", "n", drops.get(sid).size(), "who", who.toString().trim()));
                    entry(out, byId.get(sid), Material.BELL, extra, fav, target);
                }
            }
            // (b) events: the stop to rescue, event teams, a 특급 손님, where a lost item goes back
            Map<Integer, List<String>> ev = new LinkedHashMap<>();
            bus.eventStops().forEach((sid, why) -> {
                if (byId.containsKey(sid)) ev.computeIfAbsent(sid, k -> new ArrayList<>()).addAll(why);
            });
            long now = System.currentTimeMillis();
            for (ItemStack it : p.getInventory().getStorageContents()) {
                BusEvents.LostTag tag = BusModule.lostTag(it);
                if (tag == null || tag.expired(now) || !tag.finder().equals(p.getUniqueId()) || !byId.containsKey(tag.stop())) continue;
                ev.computeIfAbsent(tag.stop(), k -> new ArrayList<>()).add(Lang.raw("bus.nav-ev-lost", "thing", bus.lostName(tag), "min", tag.minutesLeft(now)));
            }
            if (!ev.isEmpty()) {
                header(out, Material.YELLOW_STAINED_GLASS_PANE, Lang.raw("bus.nav-sec-event"), Lang.raw("bus.nav-sec-event-lore"));
                for (int sid : ev.keySet().stream().sorted(near).toList()) {
                    List<String> extra = new ArrayList<>();
                    for (String w : ev.get(sid)) extra.add(Lang.raw("bus.nav-ev-line", "what", w));
                    entry(out, byId.get(sid), Material.NETHER_STAR, extra, fav, target);
                }
            }
            // (c) favourites
            header(out, Material.ORANGE_STAINED_GLASS_PANE, Lang.raw("bus.nav-sec-fav"), Lang.raw("bus.nav-sec-fav-lore"));
            List<Integer> favs = fav.stream().filter(byId::containsKey).sorted(near).toList();
            if (favs.isEmpty()) out.add(plain(Material.PAPER, Lang.raw("bus.nav-fav-empty"), Lang.raw("bus.nav-fav-empty-lore")));
            for (int sid : favs) entry(out, byId.get(sid), Material.SUNFLOWER, List.of(), fav, target);
            // (d) every stop, line by line, nearest first
            for (BusLine l : net.lines()) {
                List<Integer> on = l.stops().stream().filter(byId::containsKey).sorted(near).toList();
                if (on.isEmpty()) continue;
                header(out, Material.LIGHT_BLUE_STAINED_GLASS_PANE, Lang.raw("bus.nav-sec-line", "line", l.name()), Lang.raw("bus.nav-sec-line-lore", "n", on.size()));
                for (int sid : on) entry(out, byId.get(sid), Material.OAK_SIGN, List.of(), fav, target);
            }
            return out;
        }

        /** A section's title, from the start of a row. */
        private void header(List<ItemStack> out, Material m, String name, String lore) {
            while (out.size() % 9 != 0) out.add(null);
            out.add(plain(m, name, lore));
        }

        private ItemStack plain(Material m, String name, String lore) {
            ItemStack it = new ItemStack(m);
            it.editMeta(meta -> {
                meta.itemName(dev.stsv.core.util.Text.item(name));
                meta.lore(List.of(dev.stsv.core.util.Text.item(lore)));
            });
            return it;
        }

        private void entry(List<ItemStack> out, BusStop s, Material m, List<String> extra, Set<Integer> fav, int target) {
            double road = d(s.id());
            List<String> lines = new ArrayList<>();
            for (BusLine l : net.linesOf(s.id())) lines.add(l.name());
            ItemStack it = new ItemStack(m);
            boolean f = fav.contains(s.id()), now = s.id() == target;
            it.editMeta(meta -> {
                meta.itemName(Lang.item("bus.nav-entry-name", "stop", s.name(), "fav", f ? Lang.raw("bus.nav-entry-fav") : "",
                        "now", now ? Lang.raw("bus.nav-entry-now") : ""));
                List<Component> lore = new ArrayList<>(Lang.items("bus.nav-entry-lore", "lines", lines.isEmpty() ? "-" : String.join(" · ", lines),
                        "km", BusModule.km(road), "eta", etaText(road)));
                for (String x : extra) lore.add(dev.stsv.core.util.Text.item(x));
                lore.addAll(Lang.items("bus.nav-entry-click", "fav", Lang.raw(f ? "bus.nav-fav-remove" : "bus.nav-fav-add")));
                meta.lore(lore);
                if (now) meta.setEnchantmentGlintOverride(true);
            });
            at.put(out.size(), s.id());
            out.add(it);
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            Integer sid = at.get(index);
            if (sid == null) return;
            Player p = v.player();
            if (click.isRightClick()) {
                toggleFavourite(p, sid);
                v.refresh();
                return;
            }
            v.close();
            if (driven(p) == null) {
                p.sendMessage(Lang.msg("bus.nav-not-driving"));
                return;
            }
            start(p, sid, false);
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            switch (action) {
                case "busnav:riders" -> {
                    v.close();
                    if (driven(p) == null) p.sendMessage(Lang.msg("bus.nav-not-driving"));
                    else start(p, -1, true);
                }
                case "busnav:off" -> {
                    if (!navs.containsKey(p.getUniqueId())) return;
                    off(p, true);
                    v.refresh();
                }
                default -> {
                }
            }
        }
    }
}
