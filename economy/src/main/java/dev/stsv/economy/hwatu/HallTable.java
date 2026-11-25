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
    private UUID actedBy;
    /** whose move it was and which (a change is a new move: a sound, a flash; the old one may have timed out) */
    private String lastMove;
    private UUID lastTurn;
    private long lastDeadline;
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
            scene = new TableScene(this, mod.plugin(), anchor.getWorld(), spots, anchor.getBlockY(), anchor.getBlockY() + mod.hall().top(kind), kind);
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
    public long stack(UUID u) {
        return test != null ? test.chips(u) : mod.available(u);
    }

    @Override
    public int chair(UUID u) {
        Integer k = seats.get(u);
        return k == null ? Integer.MAX_VALUE : k;
    }

    /** What a 섯다 bet was, as MiniMessage: 하프! +120칩, with 올인 when it left them nothing. */
    static String act(Table.Bet b) {
        String s = Lang.raw("hwatu.act." + b.action().key(), "n", fmt(b.added()));
        return b.allIn() ? s + " " + Lang.raw("hwatu.act.allin") : s;
    }

    @Override
    public void bet(Table t, Table.Bet b) {
        String what = act(b);
        tell(Lang.msg("hwatu.bet", "player", mini(b.player()), "act", what));
        if (scene != null) scene.bet(b, Lang.mm("hwatu.bet-pop", "player", mini(b.player()), "act", what));
    }

    @Override
    public void street(Table t) {
        tell(Lang.msg("hwatu.street-two"));
        if (scene != null) scene.street();
    }

    @Override
    public void rolled(Table t, UUID player) {
        if (scene != null) scene.rolled(player);
    }

    /** Whose move it is now and which (null: nobody's). */
    private static String move(Table t) {
        UUID turn = t.turn();
        return turn == null ? null : turn + ":" + t.step() + ":" + t.street() + ":" + t.deals();
    }

    @Override
    public void changed(Table t) {
        UUID turn = t.turn();
        String now = move(t);
        if (!java.util.Objects.equals(now, lastMove)) {
            UUID before = lastTurn;
            // their turn ran out (the table moved for them at its deadline, not someone leaving): they sit out from the
            // next hand until 준비
            if (before != null && !before.equals(actedBy) && System.currentTimeMillis() >= lastDeadline && seats.containsKey(before)
                    && !mod.isBot(before) && test == null && t.players().contains(before) && out.add(before)) {
                Player p = Bukkit.getPlayer(before);
                if (p != null) p.sendMessage(Lang.msg("hwatu.timed-out"));
            }
            actedBy = null;
            lastTurn = turn;
            lastMove = now;
            lastDeadline = t.deadline();
            if (turn != null && !mod.isBot(turn)) {
                Player p = Bukkit.getPlayer(turn);
                if (p != null) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.6f);
                    String key = t.step() == Table.Step.ROLL ? "hwatu.your-roll" : t.step() == Table.Step.PICK ? "hwatu.your-pick" : "hwatu.your-turn";
                    HudModule.flash(p, Lang.mm(key, "seconds", t.moveSeconds()));
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

    // ------------------------------------------------------------------------------------------ the HUD

    /**
     * The board in the bottom right corner (README "도박장"): 섯다's 족보 (pages turning every few seconds, the page of my own
     * hand while I hold two cards, its row lit), or the rules of 주사위 / 홀짝. The hand as it stands is on the action bar
     * ({@link #actionLine}).
     */
    HudLayout.Board board(Player viewer) {
        List<HudLayout.Board.Row> rows = new ArrayList<>();
        if (game == Table.Game.SUTDA) {
            Sutda.Hand h = table.phase() == Table.Phase.WAITING || table.folded(viewer.getUniqueId()) ? null : table.hand(viewer.getUniqueId());
            String code = h == null ? null : h.code();
            int page = Jokbo.page(code, System.currentTimeMillis());
            String mine = Jokbo.row(code);
            for (String r : Jokbo.SUTDA.get(page))
                rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.jokbo." + r, "c", r.equals(mine) ? Lang.raw("hwatu.jokbo-mine") : Lang.raw("hwatu.jokbo-row"))));
            if (code != null) return new HudLayout.Board(Lang.mm("hwatu.jokbo-title-mine", "hand", Lang.raw(HwatuModule.HAND + code)), rows);
            String note = Lang.raw("hwatu.jokbo-notes." + "p" + (page + 1));
            return new HudLayout.Board(Lang.mm("hwatu.jokbo-title", "page", page + 1, "pages", Jokbo.SUTDA.size(), "note", note), rows);
        }
        String key = game.key();
        for (int i = 1; i <= Jokbo.RULE_ROWS; i++)
            rows.add(new HudLayout.Board.Row(null, Lang.mm("hwatu.rules." + key + "-" + i, "ante", fmt(table.ante()), "rake", mod.spec().rakePercent(),
                    "roll", mod.spec().rollSeconds(), "dice", mod.spec().game(Table.Game.DICE).dice())));
        return new HudLayout.Board(Lang.mm("hwatu.rules-title", "game", gameName(), "room", roomName()), rows);
    }

    /**
     * The action bar's line for a seated player: one compact line of the hand as it stands for them (whose move and the
     * time left, the pot, what I put in, what calling costs, my cards / dice / call, my chips), or the wait / the result.
     */
    Component actionLine(Player viewer) {
        UUID me = viewer.getUniqueId();
        Table t = table;
        List<ActionLine.Part> parts = new ArrayList<>();
        long left = secondsLeft();
        boolean in = t.players().contains(me);
        switch (t.phase()) {
            case WAITING -> parts.add(part(t.seats().size() < 2 ? "hwatu.line.alone" : "hwatu.line.starting", 0, "seconds", left));
            case PLAYING -> {
                UUID turn = t.turn();
                if (turn != null && turn.equals(me))
                    parts.add(part(t.step() == Table.Step.ROLL ? "hwatu.line.my-roll" : t.step() == Table.Step.PICK ? "hwatu.line.my-pick" : "hwatu.line.my-turn", 0, "seconds", left));
                else if (turn != null) parts.add(part("hwatu.line.turn", 0, "seconds", left));   // whose: the timer over their seat
                else parts.add(part("hwatu.line.showing", 0, "seconds", left));
                parts.add(part("hwatu.line.pot", 2, "pot", fmt(t.pot())));
                if (in && t.folded(me)) parts.add(part("hwatu.line.folded", 0));
                else if (in) {
                    int seat = t.players().indexOf(me);
                    if (t.allIn(me)) parts.add(part("hwatu.line.allin", 0));
                    else if (game == Table.Game.SUTDA && t.betting() != null && seat >= 0 && t.betting().toCall(seat) > 0)
                        parts.add(part("hwatu.line.to-call", 0, "n", fmt(t.betting().toCall(seat))));
                    String mine = mine(t, me);
                    if (mine != null) parts.add(new ActionLine.Part(mine, plain(mine), 0));
                } else parts.add(part("hwatu.line.watching", 0));
            }
            case RESULT -> {
                Table.Result r = t.last();
                if (r != null && r.players().contains(me)) {
                    long net = r.net(me);
                    parts.add(part(net >= 0 ? "hwatu.line.won" : "hwatu.line.lost", 0, "n", fmt(Math.abs(net))));
                }
                parts.add(part("hwatu.line.next", 0, "seconds", left));
            }
        }
        parts.add(part(test != null ? "hwatu.line.test-chips" : "hwatu.line.chips", 3, "n", fmt(held(me) - (t.phase() == Table.Phase.PLAYING ? t.put(me) : 0))));
        if (out.contains(me)) parts.add(part("hwatu.line.sitting-out", 0));
        String sep = Lang.raw("hwatu.line.sep");
        List<String> kept = new ArrayList<>();
        for (ActionLine.Part p : ActionLine.fit(parts, ActionLine.MOST, ActionLine.width(plain(sep)))) kept.add(p.mm());
        return Lang.mm("hwatu.line.all", "line", String.join(sep, kept));
    }

    private static ActionLine.Part part(String key, int drop, Object... pairs) {
        String mm = Lang.raw(key, pairs);
        return new ActionLine.Part(mm, plain(mm), drop);
    }

    private static String plain(String mm) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(dev.stsv.core.util.Text.mm(mm));
    }

    /** My cards (one: the card, two: the 족보), my dice or my call, as MiniMessage; null if nothing yet. */
    private String mine(Table t, UUID me) {
        switch (game) {
            case SUTDA -> {
                Card[] c = t.cards(me);
                if (c == null || c.length == 0) return null;
                Sutda.Hand h = t.hand(me);
                return h != null ? Lang.raw("hwatu.line.hand", "hand", Lang.raw(HwatuModule.HAND + h.code()))
                        : Lang.raw("hwatu.line.one-card", "card", c[0].label());
            }
            case DICE -> {
                int[] r = t.rolls(me);
                if (r == null) return null;
                int total = 0;
                StringBuilder b = new StringBuilder();
                for (int x : r) {
                    total += x;
                    if (!b.isEmpty()) b.append('+');
                    b.append(x);
                }
                return Lang.raw("hwatu.line.dice", "roll", b, "total", total);
            }
            case HOLJJAK -> {
                Boolean odd = t.calledOdd();
                if (odd == null) return null;
                boolean caller = t.players().getFirst().equals(me);
                return Lang.raw("hwatu.line.call", "call", Lang.raw(HwatuModule.HAND + ((odd == caller) ? "odd" : "even")));
            }
        }
        return null;
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
        lastMove = null;
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
        lastMove = null;
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
        private String thinking;
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
            String m0 = move(table);
            if (!m0.equals(thinking)) {   // a bot's move began: it thinks a little (well inside the timer)
                thinking = m0;
                actAt = now + HwatuBot.delay(table.moveSeconds(), mod.rng().nextDouble());
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
            if (table.step() == Table.Step.ROLL) return Table.Move.ROLL;
            if (table.step() != Table.Step.BET) return null;
            Card[] c = table.cards(bot);
            Betting b = table.betting();
            int seat = table.players().indexOf(bot);
            if (c == null || c.length == 0 || b == null || seat < 0 || b.turn() != seat) return null;
            double roll = mod.rng().nextDouble();
            return Table.move(c.length < 2 ? HwatuBot.sutdaFirst(c[0], b, seat, roll) : HwatuBot.sutda(Sutda.of(c[0], c[1]), b, seat, roll));
        }

        /** Only the pretend chips move: nothing is stored, logged, burned or counted. */
        void finished(Table.Result r) {
            for (UUID u : r.players()) chips.put(u, Math.max(0, chips(u) + r.net(u)));
            net += r.net(admin);
            mod.announce(table, r, this::chips, true);
        }
    }
}
