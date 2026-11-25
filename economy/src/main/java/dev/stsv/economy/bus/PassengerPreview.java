package dev.stsv.economy.bus;

import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoPlugin;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /관리자 버스 손님 <종류|전체> [상황]: a bus passenger kind shown to an admin for a few seconds, as the bus shows it (the same
 * mannequin, skin and name tag; ghost, scale, 강아지 할아버지's dog), passing the time as at a stop (queue.idle) and saying
 * its lines (bus.yml talk) in a few situations or the one asked. Nothing is saved; all gone after, and on disable.
 */
final class PassengerPreview {

    /** How long one kind is shown (seconds), and all of them at once. */
    static final int SECONDS = 5, ALL_SECONDS = 8;
    /** 전체: this many a row, this far apart (blocks); the first row this far ahead, each next one this much further. */
    static final int ROW = 10;
    static final double APART = 2, AHEAD = 3, ROW_STEP = 3;
    /** A bubble stays at least this long (ticks); an idle beat every this many ticks. */
    static final int MIN_BUBBLE = 30, IDLE_EVERY = 10;

    private final BusModule bus;
    private final EcoPlugin eco;
    private final Set<Batch> batches = new HashSet<>();
    /** the word after the kind that shows the 별난 손님 look */
    static final String SHINY_WORD = "별난";

    PassengerPreview(BusModule bus, EcoPlugin eco) {
        this.bus = bus;
        this.eco = eco;
    }

    // ------------------------------------------------------------------------------------------
    // Pure bits (unit-tested)
    // ------------------------------------------------------------------------------------------

    /** How a passenger feels saying lines of this situation (an emoji for the admin's action bar). */
    static String moodEmoji(String situation) {
        return switch (situation) {
            case "board", "transfer", "bell" -> "🙂";
            case "smooth", "thanks", "smile" -> "😊";
            case "jolt", "bump", "turn" -> "😣";
            case "late", "waited" -> "😤";
            case "give-up" -> "😞";
            case "missed", "doze-missed" -> "😱";
            case "view" -> "😍";
            case "slow" -> "😅";
            case "chat" -> "💬";
            case "cry" -> "😭";
            case "sick" -> "🤢";
            case "bark" -> "🐶";
            case "dodge" -> "😏";
            case "haggle" -> "😠";
            case "prank" -> "😜";
            default -> "🙂";
        };
    }

    /**
     * What sets this kind apart (bus.yml types.*), as message keys under bus.preview-trait with their value (shown "" when it
     * takes none): slower boarding, touchier or calmer, tips, far trips, luggage, pairs, hours, the 3차 ways …
     */
    static List<Map.Entry<String, String>> traits(BusSpec.Type t) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        BusSpec.Traits x = t.x();
        if (t.weight() <= 0) add(out, "hidden", "");
        if (t.slow() != 1) add(out, "slow", num(t.slow()));
        if (t.mood() != 1) add(out, "mood", num(t.mood()));
        if (t.jolt() != 1) add(out, "jolt", num(t.jolt()));
        if (t.turnFactor() != 1) add(out, "turn", num(t.turnFactor()));
        if (t.tip() != 1) add(out, "tip", num(t.tip()));
        if (t.tipMood() > 0) add(out, "tip-mood", num(t.tipMood()));
        if (t.far() > 0) add(out, "far", num(t.far()));
        if (t.luggage() > 0) add(out, "luggage", num(t.luggage()));
        if (t.pair()) add(out, "pair", "");
        if (t.toEnd()) add(out, "to-end", "");
        if (t.wander() > 0) add(out, "wander", num(Math.round(t.wander() * 100)));
        if (t.late() > 0) add(out, "late", num(t.late()));
        if (t.scale() != 1) add(out, "scale", num(t.scale()));
        if (t.hourFrom() >= 0) add(out, "hours", t.hourFrom() + "~" + t.hourTo());
        if (!x.months().isEmpty()) add(out, "months", String.join(",", x.months().stream().sorted().map(String::valueOf).toList()));
        if (x.tagsOnly()) add(out, "tags-only", "");
        if (x.ghost()) add(out, "ghost", "");
        if (!x.pet().isEmpty()) add(out, "pet", "");
        if (x.chatter() > 0) add(out, "chatter", num(x.chatter()));
        if (x.chatter() > 0 && x.chatterMood() != 0) add(out, "chatter-mood", (x.chatterMood() > 0 ? "+" : "") + num(x.chatterMood()));
        if (x.dodge() > 0) add(out, "dodge", num(Math.round(x.dodge() * 100)));
        if (x.haggleAt() > 0) add(out, "haggle", num(x.haggleAt()));
        if (x.prank() > 0) add(out, "prank", num(Math.round(x.prank() * 100)));
        if (x.litter() != 0) add(out, "litter", num(x.litter()));
        if (x.treat() > 0) add(out, "treat", num(Math.round(x.treat() * 100)));
        if (x.moodFloor() > 0) add(out, "mood-floor", num(x.moodFloor()));
        if (x.sickAt() > 0) add(out, "sick", num(x.sickAt()));
        if (x.babyCry() != 0 || x.babySmile() != 0) add(out, "baby", "");
        if (x.onTime() != 1) add(out, "on-time", num(x.onTime()));
        if (x.bless() > 0) add(out, "bless", num(x.bless()));
        if (x.review() > 0) add(out, "review", num(x.review()));
        if (x.token() > 0) add(out, "token", "");
        if (!t.gifts().isEmpty()) add(out, "gifts", "");
        if (t.announce()) add(out, "announce", "");
        return out;
    }

    private static void add(List<Map.Entry<String, String>> out, String k, String v) {
        out.add(new AbstractMap.SimpleImmutableEntry<>(k, v));
    }

    static String num(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(Math.round(d * 100) / 100.0);
    }

    /** A situation's name for the admin (bus.preview-sit.<key>; the key itself when it has none). */
    static String sitName(String key) {
        String path = "bus.preview-sit." + key;
        String s = Lang.raw(path);
        return s.startsWith("<red>[") ? key : s;
    }

    /** The message key naming a queue.idle action (bus.preview-idle.<sit|phone|fidget|hop>, else none). */
    static String idlePath(String idle) {
        return "bus.preview-idle." + (List.of("sit", "phone", "fidget", "hop").contains(idle) ? idle : "none");
    }

    private static String traitText(BusSpec.Type t, int max) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : traits(t)) {
            if (max >= 0 && out.size() >= max) break;
            String path = "bus.preview-trait." + e.getKey();
            out.add(Lang.raw(path, "v", e.getValue()));
        }
        return String.join(" · ", out);
    }

    // ------------------------------------------------------------------------------------------
    // The command
    // ------------------------------------------------------------------------------------------

    void admin(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.mm("bus.preview-player-only"));
            return;
        }
        BusSpec spec = bus.spec();
        // 별난: the kinds' 별난 손님 look (their shiny skin, ✦, sparkle and their own lines getting on and off)
        List<String> rest = new ArrayList<>(List.of(args));
        boolean shine = rest.size() > 2 && rest.subList(2, rest.size()).remove(SHINY_WORD);
        args = rest.toArray(String[]::new);
        if (args.length < 2) {
            s.sendMessage(Lang.mm("bus.preview-usage"));
            return;
        }
        String sit = args.length > 2 ? args[2] : null;
        if (args[1].equals("전체")) {
            if (sit != null && !allSituations(spec).contains(sit)) {
                s.sendMessage(Lang.mm("bus.preview-no-sit", "type", "전체", "sit", sit, "sits", String.join(", ", allSituations(spec))));
                return;
            }
            showAll(p, sit, shine);
            return;
        }
        BusSpec.Type t = spec.types().get(args[1]);
        if (t == null) {
            s.sendMessage(Lang.mm("bus.preview-no-type", "type", args[1], "types", String.join(", ", spec.types().keySet())));
            return;
        }
        if (sit != null && !spec.situations(t).contains(sit)) {
            s.sendMessage(Lang.mm("bus.preview-no-sit", "type", bus.typeTag(t), "sit", sit, "sits", String.join(", ", spec.situations(t))));
            return;
        }
        showOne(p, t, sit, shine);
    }

    List<String> complete(String[] args) {
        BusSpec spec = bus.spec();
        if (args.length == 2) {
            List<String> out = new ArrayList<>(spec.types().keySet());
            out.add("전체");
            return out.stream().filter(k -> k.startsWith(args[1])).toList();
        }
        if (args.length == 3 || args.length == 4) {
            BusSpec.Type t = spec.types().get(args[1]);
            List<String> sits = new ArrayList<>(args[1].equals("전체") ? allSituations(spec) : t == null ? List.of() : spec.situations(t));
            if (args.length == 3) sits.addFirst(SHINY_WORD);
            else if (!args[2].equals(SHINY_WORD)) sits = new ArrayList<>(List.of(SHINY_WORD));
            String last = args[args.length - 1];
            return sits.stream().filter(k -> k.startsWith(last)).toList();
        }
        return List.of();
    }

    private static List<String> allSituations(BusSpec spec) {
        Set<String> out = new LinkedHashSet<>();
        for (BusSpec.Type t : spec.types().values()) out.addAll(spec.situations(t));
        return List.copyOf(out);
    }

    private void showOne(Player p, BusSpec.Type t, String sit, boolean shine) {
        BusSpec spec = bus.spec();
        Batch b = new Batch(p, SECONDS * 20, false);
        if (shine && !spec.shiny().eligible(t)) p.sendMessage(Lang.mm("bus.preview-no-shiny", "type", bus.typeTag(t)));
        Show sh = spawn(b, t, spot(p, 0, AHEAD), sit, 0, shine && spec.shiny().eligible(t));
        if (sh == null) return;
        String idle = spec.queue().idleOf(t.id());
        p.sendMessage(Lang.mm("bus.preview-start", "type", bus.typeTag(t), "secs", SECONDS, "fare", num(t.fare()),
                "idle", Lang.raw(idlePath(idle)),
                "sits", String.join(" → ", sh.script.stream().map(l -> sitName(l[0])).toList())));
        String all = traitText(t, -1);
        if (!all.isEmpty()) p.sendMessage(Lang.mm("bus.preview-traits", "traits", all));
        start(b);
    }

    private void showAll(Player p, String sit, boolean shine) {
        Batch b = new Batch(p, ALL_SECONDS * 20, true);
        List<BusSpec.Type> types = bus.spec().types().values().stream().filter(t -> !shine || bus.spec().shiny().eligible(t)).toList();
        for (int i = 0; i < types.size(); i++) {
            int row = i / ROW, col = i % ROW, inRow = Math.min(ROW, types.size() - row * ROW);
            double side = (col - (inRow - 1) / 2.0) * APART + (row % 2 == 1 ? APART / 2 : 0);
            spawn(b, types.get(i), spot(p, side, AHEAD + row * ROW_STEP), sit, ThreadLocalRandom.current().nextInt(20), shine);
        }
        if (b.shows.isEmpty()) return;
        p.sendMessage(Lang.mm("bus.preview-all", "n", b.shows.size(), "secs", ALL_SECONDS));
        start(b);
    }

    /** A spot this far ahead of the player and this far to their right, on the ground there (else at their feet' height). */
    private static Location spot(Player p, double right, double ahead) {
        Location eye = p.getLocation();
        double yaw = Math.toRadians(eye.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // forward
        double rx = -fz, rz = fx;                          // right
        double x = eye.getX() + fx * ahead + rx * right, z = eye.getZ() + fz * ahead + rz * right;
        World w = p.getWorld();
        Double y = BusModule.standY(w, x, z, eye.getBlockY());
        Location at = new Location(w, x, y == null ? eye.getY() : y, z);
        at.setYaw(BusModule.yawTo(at, eye.getX(), eye.getZ()));
        return at;
    }

    // ------------------------------------------------------------------------------------------
    // The show
    // ------------------------------------------------------------------------------------------

    /** One passenger shown: its mannequin (and seat, dog, the bubble now), what it does, what it says when. */
    private static final class Show {
        final BusSpec.Type type;
        final String act;
        final float face;
        final double[] up;
        /** [situation, line] in order, one every `step` ticks from `phase` */
        final List<String[]> script;
        int step, phase, said = -1;
        Mannequin m;
        Entity seat, dog;
        TextDisplay bubble;
        Location home;

        Show(BusSpec.Type type, String act, float face, double[] up, List<String[]> script) {
            this.type = type;
            this.act = act;
            this.face = face;
            this.up = up;
            this.script = script;
        }
    }

    private final class Batch extends BukkitRunnable {
        final Player admin;
        final int length;
        final boolean all;
        final List<Show> shows = new ArrayList<>();
        int t;

        Batch(Player admin, int length, boolean all) {
            this.admin = admin;
            this.length = length;
            this.all = all;
        }

        @Override
        public void run() {
            t += 2;
            if (t >= length) {
                end(true);
                return;
            }
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (Show sh : shows) {
                if (sh.m == null || !sh.m.isValid()) continue;
                if (t % IDLE_EVERY == 0) {
                    bus.idleBeat(sh.m, sh.act, sh.face, sh.up, sh.seat == null ? sh.home : null, rnd);
                    if (sh.dog != null && sh.dog.isValid() && rnd.nextDouble() < 0.3) sh.dog.setRotation(sh.face + (float) rnd.nextDouble(-40, 40), 0);
                }
                int k = t < sh.phase ? -1 : (t - sh.phase) / sh.step;
                if (k != sh.said && k >= 0 && k < sh.script.size()) {
                    sh.said = k;
                    say(sh, sh.script.get(k));
                    if (!all) bar(sh);
                }
            }
            if (t % 20 == 0) {
                if (!all && !shows.isEmpty() && shows.getFirst().said >= 0) bar(shows.getFirst());
                else if (all && admin.isOnline()) admin.sendActionBar(Lang.mm("bus.preview-all-bar", "n", shows.size(), "left", (length - t + 19) / 20));
            }
        }

        private void bar(Show sh) {
            if (!admin.isOnline()) return;
            String[] now = sh.script.get(Math.max(0, sh.said));
            String tr = traitText(sh.type, 3);
            admin.sendActionBar(Lang.mm("bus.preview-bar", "type", bus.typeTag(sh.type), "fare", num(sh.type.fare()),
                    "traits", tr.isEmpty() ? "" : " · " + tr, "sit", sitName(now[0]), "emoji", moodEmoji(now[0])));
        }

        void end(boolean poof) {
            try {
                cancel();
            } catch (IllegalStateException ignored) {   // not scheduled yet
            }
            batches.remove(this);
            for (Show sh : shows) clear(sh, poof);
            shows.clear();
        }
    }

    private void start(Batch b) {
        batches.add(b);
        b.runTaskTimer(eco, 2L, 2L);
    }

    /** One kind shown at this spot, facing the admin: added to the batch (null when nothing could be shown). */
    private Show spawn(Batch b, BusSpec.Type t, Location at, String sit, int phase, boolean shine) {
        BusSpec spec = bus.spec();
        List<String[]> script = script(spec, t, sit, b.length - phase, shine);
        String act = spec.queue().idleOf(t.id());
        double yaw = Math.toRadians(at.getYaw());
        // somewhere off to the side to glance at (fidget: down the road)
        double[] up = {at.getX() + Math.cos(yaw) * 30, at.getZ() + Math.sin(yaw) * 30};
        Show sh = new Show(t, act, at.getYaw(), up, script);
        sh.phase = phase;
        sh.step = script.isEmpty() ? Integer.MAX_VALUE : Math.max(MIN_BUBBLE, (b.length - phase) / script.size());
        boolean sits = "sit".equals(act);
        Location stand = sits ? at.clone().add(0, 0.5, 0) : at;
        sh.home = stand;
        String skin = shine && spec.shiny().skin(t) != null ? spec.shiny().skin(t) : BusModule.pickSkin(t, false);
        sh.m = bus.spawnGuest(stand, t, skin, shine, x -> {
            x.customName(dev.stsv.core.util.Text.mm(bus.typeTag(t, shine)));
            x.setCustomNameVisible(true);
            x.setDescription(Lang.mm("bus.preview-tag", "fare", num(t.fare())));
        });
        if (sits) {   // on an invisible seat, as on the stop's bench
            sh.seat = BusModule.spawnSeat(stand);
            if (!sh.seat.addPassenger(sh.m)) {
                sh.seat.remove();
                sh.seat = null;
                sh.m.teleport(at);
                sh.home = at;
            }
        }
        if ("wolf".equals(t.x().pet())) {   // 강아지 할아버지's dog beside him
            Location d = at.clone().add(Math.cos(yaw) * 0.9, 0, Math.sin(yaw) * 0.9);
            sh.dog = BusModule.spawnDog(d);
        }
        if (shine) BusModule.sparkle(sh.m, true);
        b.shows.add(sh);
        return sh;
    }

    /**
     * What a kind says in the preview: a line of each of its three situations (BusSpec.previewSequence), or every line of the
     * one asked (as many as fit in `ticks`, a bubble at least MIN_BUBBLE ticks).
     */
    static List<String[]> script(BusSpec spec, BusSpec.Type t, String sit, int ticks) {
        return script(spec, t, sit, ticks, false);
    }

    /** The same; a 별난 손님 (`shine`) says its own lines getting on and off (shiny.talk, types.*.shiny-talk). */
    static List<String[]> script(BusSpec spec, BusSpec.Type t, String sit, int ticks, boolean shine) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<String[]> out = new ArrayList<>();
        if (sit == null) {
            for (String k : spec.previewSequence(t)) {
                List<String> lines = shine && spec.shiny().lines(t, k) != null ? spec.shiny().lines(t, k) : spec.lines(t, k);
                out.add(new String[]{k, lines.get(rnd.nextInt(lines.size()))});
            }
            return out;
        }
        List<String> lines = shine && spec.shiny().lines(t, sit) != null ? spec.shiny().lines(t, sit) : spec.lines(t, sit);
        if (lines == null) return out;
        int fit = Math.max(1, ticks / MIN_BUBBLE);
        for (int i = 0; i < Math.min(fit, lines.size()); i++) out.add(new String[]{sit, lines.get(i)});
        return out;
    }

    private void say(Show sh, String[] line) {
        if (sh.bubble != null) sh.bubble.remove();
        Entity who = "bark".equals(line[0]) && sh.dog != null && sh.dog.isValid() ? sh.dog : sh.m;
        if (who == sh.dog) who.getWorld().playSound(who.getLocation(), Sound.ENTITY_WOLF_AMBIENT, 1f, 1.4f);
        if ("jolt".equals(line[0]) || "bump".equals(line[0])) sh.m.swingMainHand();
        // 닭 든 할머니: her hen flutters round as it would on the bus (bus.yml hold-fly)
        boolean shaken = "jolt".equals(line[0]) || "bump".equals(line[0]) || "cry".equals(line[0]);
        if (shaken && !sh.type.hold().fly().isEmpty() && sh.m.isValid()) Flutter.start(eco, sh.m, sh.type.hold().fly(), sh.type.scale(), bus.flutters());
        sh.bubble = bus.bubble(who, line[1], Math.max(10, Math.min(sh.step, 200) - 2));
    }

    private static void clear(Show sh, boolean poof) {
        if (sh.bubble != null) sh.bubble.remove();
        if (poof && sh.m != null && sh.m.isValid())
            sh.m.getWorld().spawnParticle(Particle.POOF, sh.m.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
        if (sh.m != null) {
            sh.m.leaveVehicle();
            sh.m.remove();
        }
        if (sh.seat != null) sh.seat.remove();
        if (sh.dog != null) {
            if (poof && sh.dog.isValid()) sh.dog.getWorld().spawnParticle(Particle.POOF, sh.dog.getLocation().add(0, 0.4, 0), 5, 0.2, 0.2, 0.2, 0.01);
            sh.dog.remove();
        }
    }

    /** Everything shown, gone at once (plugin disable). */
    void stop() {
        for (Batch b : List.copyOf(batches)) b.end(false);
        batches.clear();
    }
}
