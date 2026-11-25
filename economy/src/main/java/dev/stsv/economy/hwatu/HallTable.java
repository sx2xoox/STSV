package dev.stsv.economy.hwatu;

import dev.stsv.core.furniture.Furniture;
import dev.stsv.core.furniture.FurnitureModule;
import dev.stsv.core.hud.HudLayout;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One table furniture of the 도박장 bound to a game and room (README "도박장"): its {@link Table} (the rules, unchanged), who
 * sits in which chair (the furniture's seat index), its {@link TableScene} while the furniture is loaded, and a 시험 판 with
 * bots when an admin runs one here. Sitting in a chair is sitting at the table; standing up is leaving it (a fold mid-hand).
 *
 * <p>Someone whose turn ran out (the table played for them) sits out from the next hand, still in their chair, until
 * they press 준비 on the table (the old screen's "closed the screen: not dealt in").
 */
final class HallTable implements Table.Listener, Table.Bank {

    /** The refusal of a player sitting out (they keep their chair; {@link #kicked} sits them back at the table). */
    static final String SITTING_OUT = "hwatu.sitting-out";

    private final HwatuModule mod;
    private HallStore.Binding binding;
    /** from its furniture item once loaded (bound while unloaded: the kind its game would want) */
    private TableKind kind;
    private final Table.Game game;
    private final HwatuSpec.Room room;
    private Table table;
    /** who sits in which chair (players and 시험 bots) */
    private final Map<UUID, Integer> seats = new LinkedHashMap<>();
    private final Set<UUID> out = new HashSet<>();
    private final List<UUID> resit = new ArrayList<>();
    private TableScene scene;
    private Test test;
    private UUID lastTurn, actedBy;
    private int lostFor;

    HallTable(HwatuModule mod, HallStore.Binding binding, TableKind kind, Table.Game game, HwatuSpec.Room room) {
        this.mod = mod;
        this.binding = binding;
        this.kind = kind;
        this.game = game;
        this.room = room;
        this.table = new Table(mod.nextTableId(), game, room, mod.spec(), mod.rng());
    }

    static String fmt(long n) {
        return String.format("%,d", n);
    }

    // ------------------------------------------------------------------------------------------ reading

    UUID furniture() { return binding.furniture(); }
    HallStore.Binding binding() { return binding; }
    TableKind kind() { return kind; }
    Table.Game game() { return game; }
    HwatuSpec.Room room() { return room; }
    Table table() { return table; }
    HwatuSpec spec() { return mod.spec(); }
    boolean testing() { return test != null; }
    UUID testAdmin() { return test == null ? null : test.admin; }

    String gameName() {
        return mod.gameName(game);
    }

    String roomName() {
        return test != null ? Lang.raw("hwatu.test-room", "room", room.name()) : room.name();
    }

    String mini(UUID u) {
        return mod.mini(u);
    }

    Integer seatOf(UUID u) {
        return seats.get(u);
    }

    boolean sittingOut(UUID u) {
        return out.contains(u);
    }

    /** The real players in the chairs (not 시험 bots). */
    List<UUID> seatedPlayers() {
        List<UUID> l = new ArrayList<>();
        for (UUID u : seats.keySet()) if (!mod.isBot(u)) l.add(u);
        return l;
    }

    /** The chairs this game uses (홀짝 at a round table: two, face to face). */
    List<Integer> usableSeats() {
        return TableKind.usable(kind.seats(), table.max());
    }

    long secondsLeft() {
        return table.deadline() == 0 ? 0 : Math.max(0, (table.deadline() - System.currentTimeMillis() + 999) / 1000);
    }

    /** What the player holds here: real chips, or a 시험 판's pretend ones. */
    long held(UUID u) {
        return test != null ? test.chips(u) : mod.chips(u);
    }

    Optional<Furniture> furnitureEntity() {
        Entity e = Bukkit.getEntity(binding.furniture());
        if (!(e instanceof ItemDisplay d) || !d.isValid()) return Optional.empty();
        return mod.furniture().fromDisplay(d);
    }

    // ------------------------------------------------------------------------------------------ the scene

    /** Draws the table if its furniture is loaded, takes the drawing away if it is not (once a second). */
    private void keepScene() {
        Optional<Furniture> f = furnitureEntity();
        if (f.isEmpty()) {
            if (scene != null) {
                scene.remove();
                scene = null;
            }
            lookForMoved();
            return;
        }
        lostFor = 0;
        TableKind k = TableKind.of(f.get().item().key().asString());
        if (k != null) kind = k;
        if (scene == null) {
            Location anchor = f.get().display().getLocation();
            List<Location> spots = mod.furniture().seats(f.get());
            if (spots.size() < 2) return;
            scene = new TableScene(this, mod.plugin(), anchor.getWorld(), spots, anchor.getBlockY() + mod.hall().top(kind), kind);
            scene.spawn();
            for (Player p : Bukkit.getOnlinePlayers()) scene.showTo(p);
        }
    }

    /** The furniture's id is not loaded although its chunk is: picked up and placed again, or gone. */
    private void lookForMoved() {
        World w = Bukkit.getWorld(binding.world());
        if (w == null || !w.isChunkLoaded(binding.x() >> 4, binding.z() >> 4) || !w.getChunkAt(binding.x() >> 4, binding.z() >> 4).isEntitiesLoaded()) return;
        if (++lostFor != 5) return;   // give the chunk's entities a few seconds
        Optional<Furniture> there = mod.furniture().furnitureAt(w.getBlockAt(binding.x(), binding.y(), binding.z()));
        if (there.isPresent() && TableKind.of(there.get().item().key().asString()) == kind && mod.bound(there.get().display().getUniqueId()) == null) {
            UUID from = binding.furniture();
            binding = new HallStore.Binding(there.get().display().getUniqueId(), binding.world(), binding.x(), binding.y(), binding.z(),
                    binding.game(), binding.room());
            mod.moved(from, this);
        } else {
            mod.plugin().getLogger().warning("[도박장] 테이블 가구가 없음: " + binding.world() + " " + binding.x() + "," + binding.y() + "," + binding.z()
                    + " (" + gameName() + " " + room.name() + ") — /관리자 도박장 테이블 해제 로 지우거나 다시 놓고 묶어 주세요");
        }
    }

    void showTo(Player p) {
        if (scene != null) scene.showTo(p);
    }

    /** Every tick: the animations. */
    void frame() {
        if (scene != null) scene.tick();
    }

    private void redraw() {
        if (scene != null) scene.sync();
        mod.refreshBoard();
    }

    // ------------------------------------------------------------------------------------------ sitting

    /** The free chair of this game nearest to a point, or -1. */
    private int pickSeat(Furniture f, Location near) {
        List<Location> spots = mod.furniture().seats(f);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int k : usableSeats()) {
            if (k >= spots.size() || seats.containsValue(k) || mod.furniture().seatTaken(f, k)) continue;
            double d = spots.get(k).distanceSquared(near);
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }

    /** A player clicked the table: sits them in the nearest free chair. Null, or why not (a message key). */
    String sit(Player p, Furniture f) {
        UUID u = p.getUniqueId();
        if (seats.containsKey(u)) return null;
        if (test != null) return "hwatu.table-test";
        String why = mod.refusal(u, table.need());
        if (why != null) return why;
        if (table.full()) return "hwatu.table-full";
        int seat = pickSeat(f, p.getLocation());
        if (seat < 0 || !mod.furniture().sit(p, f, seat)) return "hwatu.table-full";
        seats.put(u, seat);
        out.remove(u);
        table.sit(u, System.currentTimeMillis());
        for (UUID o : seatedPlayers()) {
            Player op = Bukkit.getPlayer(o);
            if (op != null && !o.equals(u)) op.sendMessage(Lang.msg("hwatu.joined", "player", mini(u)));
        }
        p.sendMessage(Lang.msg("hwatu.sat", "game", gameName(), "room", room.name(), "ante", fmt(room.ante()), "need", fmt(table.need())));
        p.playSound(p.getLocation(), Sound.BLOCK_WOOD_PLACE, 0.6f, 1.2f);
        redraw();
        return null;
    }

    /** Someone stood up (dismounted, quit, was stood up): leaves the table (a fold in a running hand). */
    void stood(UUID u) {
        if (!seats.containsKey(u) || mod.isBot(u)) return;
        if (test != null && u.equals(test.admin)) {
            endTest(true);
            return;
        }
        boolean folding = table.inHand(u);
        seats.remove(u);
        out.remove(u);
        resit.remove(u);
        mod.unseat(u, this);
        if (scene != null) scene.dropPad(u);
        if (table.seats().contains(u)) table.leave(u, System.currentTimeMillis(), this);
        Player p = Bukkit.getPlayer(u);
        if (p != null) p.sendMessage(Lang.msg(folding ? "hwatu.left-fold" : "hwatu.left"));
        for (UUID o : seatedPlayers()) {
            Player op = Bukkit.getPlayer(o);
            if (op != null) op.sendMessage(Lang.msg("hwatu.someone-left", "player", mini(u)));
        }
        redraw();
    }

    /** Stands a player up out of their chair (the dismount leaves the table, see {@link #stood}). */
    private static void standUp(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p != null && p.isInsideVehicle()) p.leaveVehicle();
    }

    /** Whether this player still sits in their chair here. */
    private boolean inChair(UUID u, Furniture f) {
        Player p = Bukkit.getPlayer(u);
        if (p == null) return false;
        Optional<FurnitureModule.Seated> s = mod.furniture().seated(p);
        return s.isPresent() && s.get().furniture().display().getUniqueId().equals(f.display().getUniqueId()) && s.get().seat() == seats.get(u);
    }

    /** A button on the table was clicked by the one it is for. */
    void press(Player p, Controls.Button b) {
        UUID u = p.getUniqueId();
        if (!seats.containsKey(u)) return;
        if (scene != null) scene.pressed(u, b);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
        switch (b) {
            case LEAVE -> standUp(u);
            case READY -> {
                if (out.remove(u)) p.sendMessage(Lang.msg("hwatu.ready"));
                redraw();
            }
            default -> {
                actedBy = u;
                String why = table.act(u, b.move(), System.currentTimeMillis(), this);
                if (why != null) {
                    HudModule.flash(p, Lang.mm(why));
                    actedBy = null;
                    redraw();
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ time

    /** Once a second: the table's timers, chairs still sat in, the bots, the drawing. */
    void second(long now) {
        keepScene();
        Optional<Furniture> f = furnitureEntity();
        for (UUID u : new ArrayList<>(seats.keySet())) {
            if (mod.isBot(u)) continue;
            if (f.isEmpty() || !inChair(u, f.get())) {
                standUp(u);
                stood(u);
            }
        }
        if (test != null) test.keepBots(f.orElse(null));
        table.tick(now, this, this);
        if (!resit.isEmpty()) {
            for (UUID u : resit) if (seats.containsKey(u) && !table.seats().contains(u)) table.sit(u, now);
            resit.clear();
        }
        if (test != null) test.tick(now);
        if (scene != null) scene.sync();
    }

    // ------------------------------------------------------------------------------------------ the table's events

    @Override
    public String refusal(UUID u, long need) {
        if (test != null) return test.refusal(u, need);
        if (out.contains(u)) return SITTING_OUT;
        if (Bukkit.getPlayer(u) == null) return "hwatu.away";
        return mod.refusal(u, need);
    }

    @Override
    public void changed(Table t) {
        UUID turn = t.turn();
        if (!java.util.Objects.equals(turn, lastTurn)) {
            UUID before = lastTurn;
            // their turn ran out (the table moved for them): they sit out from the next hand until 준비
            if (before != null && !before.equals(actedBy) && seats.containsKey(before) && !mod.isBot(before) && test == null
                    && t.players().contains(before) && out.add(before)) {
                Player p = Bukkit.getPlayer(before);
                if (p != null) p.sendMessage(Lang.msg("hwatu.timed-out"));
            }
            actedBy = null;
            lastTurn = turn;
            if (turn != null && !mod.isBot(turn)) {
                Player p = Bukkit.getPlayer(turn);
                if (p != null) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.6f);
                    HudModule.flash(p, Lang.mm("hwatu.your-turn", "seconds", mod.spec().turnSeconds()));
                }
            }
        }
        redraw();
    }

    @Override
    public void kicked(Table t, UUID player, String reason) {
        if (reason.equals(SITTING_OUT) && seats.containsKey(player)) {
            resit.add(player);   // keeps the chair, not dealt in
            return;
        }
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            p.sendMessage(Lang.msg(reason, "need", fmt(t.need()), "cap", fmt(mod.spec().lossCap())));
            p.sendMessage(Lang.msg("hwatu.stood-up"));
        }
        seats.remove(player);
        out.remove(player);
        mod.unseat(player, this);
        if (scene != null) scene.dropPad(player);
        standUp(player);
    }

    @Override
    public void replay(Table t) {
        tell(Lang.msg("hwatu.replay"));
    }

    @Override
    public void finished(Table t, Table.Result r) {
        boolean big = test == null && r.pot() >= mod.hall().bigPot() && !r.winners().isEmpty();
        if (test != null) test.finished(r);
        else mod.settle(t, r);
        if (scene != null) scene.finished(r, big);
        if (big) mod.bigWin(this, r);
    }

    void tell(Component c) {
        for (UUID u : seatedPlayers()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(c);
        }
    }

    /** The table goes (unbound, its furniture broken, the hall stopping): the hand is void, everyone stands up. */
    void close(String why) {
        if (test != null) endTest(false);
        table.abandon();
        for (UUID u : new ArrayList<>(seats.keySet())) {
            seats.remove(u);
            mod.unseat(u, this);
            Player p = Bukkit.getPlayer(u);
            if (p != null && why != null) p.sendMessage(Lang.msg(why));
            standUp(u);
        }
        out.clear();
        if (scene != null) scene.remove();
        scene = null;
        mod.refreshBoard();
    }

    /** hwatu.yml reloaded. */
    void spec(HwatuSpec s) {
        table.spec(s);
    }

    // ------------------------------------------------------------------------------------------ the board (bottom right)

    HudLayout.Board board(Player viewer) {
        UUID me = viewer.getUniqueId();
        Table t = table;
        List<HudLayout.Board.Row> rows = new ArrayList<>();
        UUID turn = t.turn();
        long left = secondsLeft();
        switch (t.phase()) {
            case WAITING -> rows.add(new HudLayout.Board.Row(null, Lang.mm(t.seats().size() < 2 ? "hwatu.board-alone" : "hwatu.board-starting", "seconds", left)));
            case PLAYING -> {
                if (turn != null) rows.add(new HudLayout.Board.Row(mod.isBot(turn) ? null : turn,
                        Lang.mm(turn.equals(me) ? "hwatu.board-my-turn" : "hwatu.board-turn", "player", mini(turn), "seconds", left)));
                else rows.add(new HudLayout.Board.Row(null, Lang.mm(t.step() == Table.Step.ROLL ? "hwatu.board-rolling" : "hwatu.board-playing", "seconds", left)));
                rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.board-pot", "pot", fmt(t.pot()), "ante", fmt(t.ante()))));
                if (t.players().contains(me)) rows.add(new HudLayout.Board.Row(null, Lang.mm(t.folded(me) ? "hwatu.board-folded" : "hwatu.board-put", "n", fmt(t.put(me)))));
                if (game == Table.Game.SUTDA && t.betting() != null) {
                    rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.board-raises", "n", t.betting().raises(), "max", mod.spec().game(Table.Game.SUTDA).maxRaises())));
                }
            }
            case RESULT -> {
                Table.Result r = t.last();
                if (r != null && r.players().contains(me)) {
                    long net = r.net(me);
                    rows.add(new HudLayout.Board.Row(null, Lang.mm(net >= 0 ? "hwatu.board-won" : "hwatu.board-lost", "n", fmt(Math.abs(net)))));
                }
                rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.board-result", "seconds", left)));
            }
        }
        rows.add(new HudLayout.Board.Row(null, Lang.mm(test != null ? "hwatu.board-test-chips" : "hwatu.board-chips", "n", fmt(held(me) - t.committed(me)))));
        if (out.contains(me)) rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.board-sitting-out")));
        return new HudLayout.Board(Lang.mm("hwatu.board-title", "game", gameName(), "room", roomName()), rows);
    }

    // ------------------------------------------------------------------------------------------ 시험 판

    /**
     * Starts a 시험 판 here (README "도박장"): the admin in the chair nearest them, bots (봇1 …) in `players` − 1 others, all
     * on pretend chips (hwatu.yml test-chips a seat, filled again when short): no gold, no real chips, no hand log, nothing
     * for the ranking, stamps, missions or achievements. Null, or why not.
     */
    String startTest(Player admin, Furniture f, int players) {
        UUID u = admin.getUniqueId();
        for (UUID o : seatedPlayers()) if (!o.equals(u)) return "hwatu.test-table-busy";
        // the admin keeps their chair if they sit here already (standing up and sitting again in one tick does not work)
        Integer keep = seats.get(u);
        if (test != null) endTest(false, false);
        if (keep == null && admin.isInsideVehicle()) return "hwatu.test-seated";
        if (keep != null) {
            seats.remove(u);
            mod.unseat(u, this);
            if (scene != null) scene.dropPad(u);
        }
        table.abandon();
        table = new Table(mod.nextTableId(), game, room, mod.spec(), mod.rng());
        lastTurn = actedBy = null;
        out.clear();
        resit.clear();
        List<Integer> usable = usableSeats();
        int n = Math.clamp(players, 2, Math.min(table.max(), usable.size()));
        int seat = keep != null ? keep : pickSeat(f, admin.getLocation());
        if (keep == null && (seat < 0 || !mod.furniture().sit(admin, f, seat))) {
            redraw();
            return "hwatu.table-full";
        }
        test = new Test(u);
        seats.put(u, seat);
        table.sit(u, System.currentTimeMillis());
        mod.seat(u, this);
        List<Location> spots = mod.furniture().seats(f);
        for (int i = 1; i < n; i++) {
            int k = pickSeat(f, spots.get(Math.min(seat, spots.size() - 1)));
            if (k < 0) break;
            UUID b = UUID.randomUUID();
            mod.addBot(b, Lang.raw("hwatu.test-bot", "n", i));
            test.bots.add(b);
            seats.put(b, k);
            table.sit(b, System.currentTimeMillis());
            test.spawnBot(b, f, k);
        }
        admin.sendMessage(Lang.msg("hwatu.test-start", "game", gameName(), "room", room.name(), "n", test.bots.size() + 1, "bots", test.bots.size(),
                "chips", fmt(mod.spec().testChips())));
        redraw();
        return null;
    }

    /** Ends the 시험 판 (nothing to settle: the chips were pretend); the table plays for real again. */
    void endTest(boolean tell) {
        endTest(tell, true);
    }

    /** stand: the admin stands up too (else they stay in the chair, out of any table, for a new 시험 판 at once). */
    private void endTest(boolean tell, boolean stand) {
        Test x = test;
        if (x == null) return;
        test = null;
        table.abandon();
        for (UUID b : x.bots) {
            seats.remove(b);
            mod.removeBot(b);
        }
        x.removeBots();
        if (stand) {
            seats.remove(x.admin);
            mod.unseat(x.admin, this);
            if (scene != null) scene.dropPad(x.admin);
            standUp(x.admin);
        }
        table = new Table(mod.nextTableId(), game, room, mod.spec(), mod.rng());
        lastTurn = actedBy = null;
        Player p = Bukkit.getPlayer(x.admin);
        if (p != null && tell) p.sendMessage(Lang.msg("hwatu.test-end", "net", (x.net >= 0 ? "+" : "") + fmt(x.net)));
        if (scene != null) scene.sync();
        mod.refreshBoard();
    }

    /** The first bot of the 시험 판 (for 패 정하기), or null. */
    UUID firstBot() {
        return test == null || test.bots.isEmpty() ? null : test.bots.getFirst();
    }

    /** The bots of a 시험 판 and its pretend chips. */
    private final class Test {
        final UUID admin;
        final List<UUID> bots = new ArrayList<>();
        final Map<UUID, Long> chips = new HashMap<>();
        final Map<UUID, Entity> bodies = new HashMap<>();
        long net;
        private UUID thinking;
        private long actAt;

        Test(UUID admin) {
            this.admin = admin;
        }

        long chips(UUID u) {
            return chips.getOrDefault(u, mod.spec().testChips());
        }

        /** Everyone is dealt in; a seat short of a hand's worst case gets its pretend chips back. */
        String refusal(UUID u, long need) {
            if (chips(u) < need) chips.put(u, mod.spec().testChips());
            return null;
        }

        /** A bot in its chair: a mannequin sitting there (hall.bots), or a name tag over an empty chair. */
        void spawnBot(UUID b, Furniture f, int seat) {
            Location at = mod.furniture().seats(f).get(seat);
            Entity body;
            if (mod.hall().mannequins()) {
                Mannequin m = at.getWorld().spawn(at, Mannequin.class, x -> {
                    x.setPersistent(false);
                    x.setInvulnerable(true);
                    x.setSilent(true);
                    x.setImmovable(true);
                    x.setCollidable(false);
                    x.setGravity(false);
                    x.setRemoveWhenFarAway(false);
                    x.customName(Lang.mm("hwatu.bot-tag", "name", mod.mini(b)));
                    x.setCustomNameVisible(true);
                    x.setDescription(Lang.mm("hwatu.bot-description"));
                    var sc = x.getAttribute(Attribute.SCALE);
                    if (sc != null) sc.setBaseValue(0.95);
                    x.getPersistentDataContainer().set(TableScene.TAG, PersistentDataType.STRING, furniture().toString());
                });
                if (!mod.furniture().sit(m, f, seat)) m.teleport(at);
                body = m;
            } else {
                TextDisplay tag = scene != null ? scene.seatLabel(seat, Lang.mm("hwatu.bot-tag", "name", mod.mini(b))) : null;
                body = tag;
            }
            if (body != null) bodies.put(b, body);
        }

        /** A bot's body went (its chunk reloaded): it sits down again. */
        void keepBots(Furniture f) {
            if (f == null) return;
            for (UUID b : bots) {
                Entity e = bodies.get(b);
                if (e != null && e.isValid()) continue;
                if (e != null) e.remove();
                bodies.remove(b);
                Integer k = seats.get(b);
                if (k != null) spawnBot(b, f, k);
            }
        }

        void removeBots() {
            for (Entity e : bodies.values()) {
                if (e == null) continue;
                Entity seat = e.getVehicle();
                e.remove();
                if (seat != null) seat.remove();
            }
            bodies.clear();
        }

        void tick(long now) {
            UUID turn = table.turn();
            if (turn == null || !mod.isBot(turn)) {
                thinking = null;
                return;
            }
            if (!turn.equals(thinking)) {   // a bot's turn began: it thinks a little (well inside the turn timer)
                thinking = turn;
                actAt = now + HwatuBot.delay(mod.spec().turnSeconds(), mod.rng().nextDouble());
                return;
            }
            if (now < actAt) return;
            thinking = null;
            Table.Move m = botMove(turn);
            if (m != null) {
                Entity body = bodies.get(turn);
                if (body instanceof Mannequin mq) mq.swingMainHand();
                table.act(turn, m, now, HallTable.this);
            }
        }

        private Table.Move botMove(UUID bot) {
            if (table.step() == Table.Step.PICK) return HwatuBot.callsOdd(mod.rng().nextDouble()) ? Table.Move.ODD : Table.Move.EVEN;
            if (table.step() != Table.Step.BET) return null;
            Card[] c = table.cards(bot);
            Betting b = table.betting();
            int seat = table.players().indexOf(bot);
            if (c == null || b == null || seat < 0) return Table.Move.CALL;
            return switch (HwatuBot.sutda(Sutda.of(c[0], c[1]), b.toCall(seat), b.canRaise(), mod.rng().nextDouble())) {
                case DIE -> Table.Move.DIE;
                case CALL -> Table.Move.CALL;
                case RAISE -> Table.Move.RAISE;
            };
        }

        /** Only the pretend chips move: nothing is stored, logged, burned or counted. */
        void finished(Table.Result r) {
            for (UUID u : r.players()) chips.put(u, Math.max(0, chips(u) + r.net(u)));
            net += r.net(admin);
            mod.announce(table, r, this::chips, true);
        }
    }
}
