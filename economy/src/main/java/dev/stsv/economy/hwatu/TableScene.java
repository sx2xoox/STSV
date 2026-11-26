package dev.stsv.economy.hwatu;

import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * What a 도박장 table shows (README "도박장"): the sign over it, the cards before each seat (the owner sees the face, everyone
 * else the back: two displays a card, hidden per viewer; 섯다 deals them one street at a time), the chip pile in the
 * middle, each 주사위 player's cup over their dice (shaken and lifted when they roll), the 홀짝 bowl (shaken and lifted
 * when the caller rolls), the turn timer and the buttons of whoever's turn it is (seen and clicked by them alone). Every entity here is not saved
 * (setPersistent(false)) and tagged {@link #TAG}: a chunk unloading or the server stopping takes them away, and
 * {@link HallTable} draws the table again when its furniture is back. Animations are interpolated transformations, stepped
 * by {@link #tick()} every tick. Where things go: {@link TableArt}.
 */
final class TableScene {

    /** Every entity of a scene: the furniture entity it belongs to (purged when found without its scene). */
    static final NamespacedKey TAG = new NamespacedKey("economy", "hwatu_scene");
    /** A button's click box: "viewer;button". */
    static final NamespacedKey BUTTON = new NamespacedKey("economy", "hwatu_button");

    /** The pile's scale on this table (the poker oval is narrower than the round table: a smaller pile). */
    private float pileScale() {
        return kind == TableKind.POKER ? CHIPS_SCALE * 0.7f : CHIPS_SCALE;
    }

    /** Sizes of the models on the table (scale of a display; the models are drawn at their own size, 16 px a block). */
    static final float CARD_SCALE = 0.75f, BUTTON_SCALE = 0.4f, DIE_SCALE = 0.6f, CHIPS_SCALE = 0.45f, BOWL_SCALE = 0.8f, CUP_SCALE = 0.9f;
    /** How long a cup or the bowl is shaken before it is lifted off the roll (ticks). */
    static final int SHAKE_TICKS = 18;
    /** Half of the centred models (the die is 4 px, a button plate 6 px tall): how high their middle stands. */
    static final double DIE_HALF = 0.125 * DIE_SCALE, BUTTON_HALF = 0.1875 * BUTTON_SCALE;
    /** Buttons lean back (degrees round their own left-right axis) to face someone sitting at the table. */
    static final float BUTTON_TILT = -30f;
    /** How far displays are drawn (times the entity distance), like the core's furniture. */
    static final float VIEW = 0.6f;

    private final HallTable host;
    private final Plugin plugin;
    private final World world;
    private final List<double[]> seats = new ArrayList<>();
    private final double[] center;
    private final double top, floor;
    private final TableArt.Reach reach;
    private final TableArt.Size size;
    private final TableKind kind;
    /** 바카라: the way from the middle to the dealer (a unit vector), and where the dealer stands */
    private final double[] toDealer, dealerAt;
    private final Random rng = new Random();
    private final List<Entity> owned = new ArrayList<>();

    private TextDisplay sign, potLabel, timer;
    private ItemDisplay pile;
    private int pileLevel;
    private final Map<UUID, Hand> hands = new LinkedHashMap<>();
    /** the deal the cards on the table are from (Table.deals; a new hand or a 구사 재경기 sweeps them) */
    private int dealt = -1;
    /** 주사위: each player's cup and dice, of the hand {@link #cupHand} */
    private final Map<UUID, Cup> cups = new HashMap<>();
    private int cupHand = -1;
    private ItemDisplay bowl, bowlDie;
    /** 홀짝: the bowl is being shaken until this tick, then lifted (the die is out once lifted) */
    private long shakeUntil = -1;
    private boolean bowlUp;
    private TextDisplay call;
    private final Map<UUID, Pad> pads = new HashMap<>();
    /** 세븐 포커: each player's cards */
    private final Map<UUID, PHand> phands = new LinkedHashMap<>();
    /** 바카라: the dealer, what they say, the shoe, the coup's cards and totals, every seat's bets on the felt */
    private org.bukkit.entity.Mannequin dealer;
    private TextDisplay speech, playerTotal, bankerTotal;
    private long speechUntil = -1;
    private final List<ItemDisplay> coupCards = new ArrayList<>();
    private int coupShown = -1, betRound = -1;
    private final Map<String, ItemDisplay> betPiles = new HashMap<>();
    private final Map<String, TextDisplay> betLabels = new HashMap<>();
    private final Map<String, Long> betShown = new HashMap<>();
    /** queued animation steps: tick → what */
    private final List<Object[]> queue = new ArrayList<>();
    private long tick;

    /** One player's cards (up to two, one a street): faces (seen by the owner) and backs (seen by everyone else). */
    private static final class Hand {
        int seat;
        final ItemDisplay[] face = new ItemDisplay[2], back = new ItemDisplay[2];
        boolean folded, revealed;
        /** the tick its last card is in place */
        long ready;
    }

    /** One 주사위 player's cup and, once rolled, their dice under it. */
    private static final class Cup {
        int seat;
        ItemDisplay cup;
        final List<ItemDisplay> dice = new ArrayList<>();
        boolean rolled;
    }

    /** One 세븐 포커 hand: up to seven cards, each a face (the owner, or everyone once open) and a back (everyone else). */
    private static final class PHand {
        int seat;
        final ItemDisplay[] face = new ItemDisplay[7], back = new ItemDisplay[7];
        /** where each card's display stands (its spot when dealt), and how far it was slid since (to its row) */
        final TableArt.Spot[] spot = new TableArt.Spot[7];
        final Vector3f[] moved = new Vector3f[7];
        final boolean[] shown = new boolean[7];
        int opened = -1;
        boolean folded, revealed;
        long ready;
    }

    /** A viewer's buttons: what they are (to see a change) and their entities. */
    private static final class Pad {
        String key = "";
        final List<Entity> entities = new ArrayList<>();
        final Map<Controls.Button, ItemDisplay> displays = new HashMap<>();
    }

    /**
     * floor: the y the table stands on; top: its top's (where cards and chips lie); anchor: the furniture's own spot (the
     * 바카라 table's middle: its chairs stand round one side only); dealer: where the 바카라 dealer stands (null: behind the
     * flat edge, worked out from the chairs).
     */
    TableScene(HallTable host, Plugin plugin, World world, List<Location> seatSpots, double floor, double top, TableKind kind, Location anchor,
               Location dealer) {
        this.host = host;
        this.plugin = plugin;
        this.world = world;
        for (Location l : seatSpots) seats.add(new double[]{l.getX(), l.getZ()});
        this.kind = kind;
        this.center = kind == TableKind.BACCARAT && anchor != null ? new double[]{anchor.getX(), anchor.getZ()} : TableArt.center(seats);
        this.floor = floor;
        this.top = top;
        this.reach = TableArt.reach(kind);
        this.size = TableArt.size(kind);
        double[] d = dealer == null ? null : new double[]{dealer.getX(), dealer.getZ()};
        this.toDealer = TableArt.toDealer(center, seats, d);
        this.dealerAt = d != null ? d : new double[]{center[0] + toDealer[0] * TableArt.DEALER_OUT, center[1] + toDealer[1] * TableArt.DEALER_OUT};
    }

    // ------------------------------------------------------------------------------------------ entities

    static ItemStack model(String id) {
        ItemStack it = Items.create(id, 1).orElse(null);
        if (it != null) return it;
        // before the content pack has the item: its model of the same id (economy:model/<id>)
        ItemStack paper = new ItemStack(Material.PAPER);
        NamespacedKey key = NamespacedKey.fromString(id.replace("economy:", "economy:model/"));
        if (key != null) paper.editMeta(m -> m.setItemModel(key));
        return paper;
    }

    private void tag(Entity e) {
        e.setPersistent(false);
        e.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, host.furniture().toString());
        owned.add(e);
    }

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z, 0, 0);
    }

    private Location at(TableArt.Spot s, double up) {
        return at(s.x(), top + up, s.z());
    }

    /** A model on the table: floor at the point, turned by `rot`, drawn `offset` away first (slid in later). */
    private ItemDisplay item(Location at, String model, float scale, Quaternionf rot, Vector3f offset, boolean everyone) {
        ItemDisplay d = world.spawn(at, ItemDisplay.class, x -> {
            x.setItemStack(model(model));
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            x.setTransformation(pose(scale, rot, offset));
            x.setViewRange(VIEW);
            x.setVisibleByDefault(everyone);
            tag(x);
        });
        return d;
    }

    /** The display's pose: scaled, turned, `offset` away from its position (null: at it). */
    private static Transformation pose(float scale, Quaternionf rot, Vector3f offset) {
        Vector3f t = offset == null ? new Vector3f() : new Vector3f(offset);
        return new Transformation(t, new Quaternionf(rot), new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static void animate(Display d, Transformation t, int ticks) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(t);
    }

    private TextDisplay text(Location at, Component c, float scale, boolean everyone) {
        return world.spawn(at, TextDisplay.class, t -> {
            t.text(c);
            t.setBillboard(Display.Billboard.CENTER);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            t.setShadowed(true);
            t.setLineWidth(200);
            t.setViewRange(VIEW);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
            t.setVisibleByDefault(everyone);
            tag(t);
        });
    }

    private void kill(Entity e) {
        if (e == null) return;
        owned.remove(e);
        if (e.isValid()) e.remove();
    }

    private void later(int ticks, Runnable r) {
        queue.add(new Object[]{tick + Math.max(1, ticks), r});
    }

    private Vector3f offsetTo(double[] from, double x, double z, double dy) {
        return new Vector3f((float) (from[0] - x), (float) dy, (float) (from[1] - z));
    }

    private double[] seat(int k) {
        return seats.get(Math.clamp(k, 0, seats.size() - 1));
    }

    private Location middle(double up) {
        return at(center[0], top + up, center[1]);
    }

    /** The sign's place: over the middle, {@link TableArt#SIGN_UP} over the floor (under the ceiling, over the bulb). */
    private Location signSpot() {
        return at(center[0], floor + TableArt.SIGN_UP, center[1]);
    }

    private void sound(Location l, Sound s, float volume, float pitch) {
        world.playSound(l, s, volume, pitch);
    }

    // ------------------------------------------------------------------------------------------ the life of a scene

    /** Draws the table as it is now (spawned with its furniture, or again after a reload). */
    void spawn() {
        sign = text(signSpot(), Component.empty(), SIGN_SCALE, true);
        sync();
    }

    /** The sign's text size (three lines: about 0.38 block tall). */
    static final float SIGN_SCALE = 0.5f;

    /** Takes everything away (the furniture went, or the hall stops). */
    void remove() {
        for (Entity e : new ArrayList<>(owned)) if (e.isValid()) e.remove();
        owned.clear();
        hands.clear();
        dealt = -1;
        cups.clear();
        cupHand = -1;
        pads.clear();
        queue.clear();
        phands.clear();
        dealer = null;
        speech = playerTotal = bankerTotal = null;
        speechUntil = -1;
        coupCards.clear();
        coupShown = betRound = -1;
        betPiles.clear();
        betLabels.clear();
        betShown.clear();
        sign = potLabel = timer = call = null;
        pile = bowl = bowlDie = null;
        pileLevel = 0;
        shakeUntil = -1;
        bowlUp = false;
    }

    /** Every tick: due animation steps and the shaken bowl. */
    void tick() {
        tick++;
        if (!queue.isEmpty()) {
            List<Object[]> due = new ArrayList<>();
            for (Iterator<Object[]> it = queue.iterator(); it.hasNext(); ) {
                Object[] q = it.next();
                if ((long) q[0] <= tick) {
                    due.add(q);
                    it.remove();
                }
            }
            for (Object[] q : due) ((Runnable) q[1]).run();
        }
        if (speechUntil >= 0 && tick >= speechUntil) {
            speechUntil = -1;
            kill(speech);
            speech = null;
        }
        if (shakeUntil >= 0) {
            if (tick < shakeUntil) {
                if (tick % 3 == 0) shake();
            } else {
                shakeUntil = -1;
                liftBowl();
            }
        }
    }

    /** Brings the displays in line with the table (after every change, and once a second for the timer). */
    void sync() {
        Table t = host.table();
        drawSign(t);
        drawCards(t);
        drawPoker(t);
        drawPile(t);
        drawDice(t);
        drawBowl(t);
        drawBaccarat(t);
        drawTimer(t);
        drawButtons(t);
    }

    // ------------------------------------------------------------------------------------------ the sign

    private void drawSign(Table t) {
        if (sign == null || !sign.isValid()) sign = text(signSpot(), Component.empty(), SIGN_SCALE, true);
        if (t.game() == Table.Game.BACCARAT) {
            drawBaccaratSign(t);
            return;
        }
        String state = switch (t.phase()) {
            case WAITING -> t.seats().size() < 2 ? Lang.raw("hwatu.sign-alone")
                    : Lang.raw("hwatu.sign-starting", "seconds", host.secondsLeft());
            case PLAYING -> t.step() == Table.Step.OPEN ? Lang.raw("hwatu.sign-opening", "seconds", host.secondsLeft())
                    : t.step() == Table.Step.DEAL ? Lang.raw("hwatu.sign-dealing", "n", t.street())
                    : t.game() == Table.Game.POKER && t.lastBet() == null ? Lang.raw("hwatu.sign-poker-street", "n", t.street())
                    : t.step() == Table.Step.ROLL ? Lang.raw("hwatu.sign-rolling", "player", t.turn() == null ? "" : host.mini(t.turn()))
                    : t.step() == Table.Step.SHOW ? Lang.raw("hwatu.sign-showing")
                    : t.lastBet() != null ? Lang.raw("hwatu.sign-bet", "player", host.mini(t.lastBet().player()), "act", HallTable.act(t.lastBet()))
                    : t.game() == Table.Game.SUTDA ? Lang.raw("hwatu.sign-street", "n", t.street())
                    : Lang.raw("hwatu.sign-playing");
            case RESULT -> Lang.raw("hwatu.sign-result", "seconds", host.secondsLeft());
        };
        String limit = !t.game().bets() || t.handLimit() >= Betting.MOST ? ""
                : Lang.raw(t.limitReached() ? "hwatu.sign-limit-reached" : "hwatu.sign-limit", "n", HallTable.fmt(t.handLimit()));
        sign.text(Lang.mm("hwatu.sign", "game", host.gameName(), "room", host.roomName(), "ante", HallTable.fmt(t.ante()), "limit", limit,
                "n", t.seats().size(), "max", Math.min(t.max(), host.usableSeats().size()), "state", state));
    }

    // ------------------------------------------------------------------------------------------ cards (섯다)

    private void drawCards(Table t) {
        if (t.game() != Table.Game.SUTDA) return;   // 세븐 포커: drawPoker
        boolean show = t.game() == Table.Game.SUTDA && t.phase() != Table.Phase.WAITING;
        if (!show || t.deals() != dealt) {   // a new hand or a 구사 재경기: the old cards go
            for (Hand h : hands.values()) sweep(h);
            hands.clear();
            dealt = show ? t.deals() : -1;
        }
        if (!show) return;
        int order = 0;
        for (UUID u : t.players()) {
            Card[] c = t.cards(u);
            Integer seat = host.seatOf(u);
            if (c == null || seat == null) continue;
            Hand h = hands.get(u);
            if (h == null) {
                h = new Hand();
                h.seat = seat;
                hands.put(u, h);
            }
            boolean dealtNow = false;
            for (int k = 0; k < c.length && k < 2; k++) {
                if (h.face[k] != null) continue;
                deal(u, h, k, c[k], order, dealtNow ? 2 : 0);   // a street's card: one each, round the table
                dealtNow = true;
            }
            if (dealtNow) order++;
            if (t.folded(u) && !h.folded) fold(h);
            if (TableArt.revealed(t, u) && !h.revealed) reveal(h);
        }
    }

    private TableArt.Spot cardSpot(int seat, int k) {
        double side = TableArt.row(2, TableArt.CARD_GAP).get(k);
        return TableArt.along(center, seat(seat), reach.card(), side, true);
    }

    /** Deals card k of a player's hand: it slides from the middle to its spot (the owner sees its face). */
    private void deal(UUID owner, Hand h, int k, Card card, int order, int extra) {
        Player p = Bukkit.getPlayer(owner);
        TableArt.Spot s = cardSpot(h.seat, k);
        Location l = at(s, 0.002 + k * 0.004);
        Quaternionf rot = TableArt.facing(s.yaw());
        Vector3f from = offsetTo(center, s.x(), s.z(), 0.05);
        String faceModel = "economy:gamble_card_" + card.item().substring("economy:hwatu_".length());
        h.face[k] = item(l, faceModel, CARD_SCALE, rot, from, false);
        h.back[k] = item(l, "economy:gamble_card_back", CARD_SCALE, rot, from, true);
        if (p != null) {
            p.showEntity(plugin, h.face[k]);
            p.hideEntity(plugin, h.back[k]);
        }
        ItemDisplay f = h.face[k], b = h.back[k];
        int delay = 2 + order * 4 + extra;
        h.ready = Math.max(h.ready, tick + delay + 5);
        later(delay, () -> {
            animate(f, pose(CARD_SCALE, rot, null), 5);
            animate(b, pose(CARD_SCALE, rot, null), 5);
            sound(l, Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 1.3f + rng.nextFloat() * 0.3f);
        });
    }

    /** 다이: the cards slide a little towards the middle, still face down. */
    private void fold(Hand h) {
        h.folded = true;
        for (int k = 0; k < 2; k++) {
            if (h.face[k] == null) continue;
            TableArt.Spot s = cardSpot(h.seat, k);
            Quaternionf rot = TableArt.facing(s.yaw()).rotateY((float) Math.toRadians(k == 0 ? 12 : -9));
            Vector3f in = offsetTo(center, s.x(), s.z(), 0).mul(0.25f);
            animate(h.face[k], pose(CARD_SCALE, rot, in), 6);
            animate(h.back[k], pose(CARD_SCALE, rot, in), 6);
        }
        sound(middle(0), Sound.ITEM_BOOK_PUT, 0.5f, 0.8f);
    }

    /** The showdown: everyone's back flips over to the face (lifted, edge on, swapped, down again), after the last deal. */
    private void reveal(Hand h) {
        h.revealed = true;
        int wait = (int) Math.max(0, h.ready - tick);
        for (int k = 0; k < 2; k++) {
            if (h.face[k] == null) continue;
            TableArt.Spot s = cardSpot(h.seat, k);
            Quaternionf flat = TableArt.facing(s.yaw());
            Vector3f axis = new Vector3f((float) (center[0] - s.x()), 0, (float) (center[1] - s.z())).normalize();
            Quaternionf edge = new Quaternionf().rotateAxis((float) Math.toRadians(90), axis.x, axis.y, axis.z).mul(flat);
            Quaternionf edgeBack = new Quaternionf().rotateAxis((float) Math.toRadians(-90), axis.x, axis.y, axis.z).mul(flat);
            ItemDisplay back = h.back[k], face = h.face[k];
            ItemStack faceItem = face.getItemStack();
            int delay = wait + 1 + h.seat * 2 + k * 3;
            float pitch = 1.0f + k * 0.2f;
            Vector3f up = new Vector3f(0, 0.18f, 0);
            later(delay, () -> {
                animate(back, pose(CARD_SCALE, edge, up), 3);
                animate(face, pose(CARD_SCALE, edge, up), 3);
            });
            later(delay + 3, () -> {
                if (back.isValid()) back.setItemStack(faceItem);
                if (back.isValid()) back.setTransformation(pose(CARD_SCALE, edgeBack, up));
                if (face.isValid()) face.setTransformation(pose(CARD_SCALE, edgeBack, up));
            });
            later(delay + 4, () -> {
                animate(back, pose(CARD_SCALE, flat, null), 3);
                animate(face, pose(CARD_SCALE, flat, null), 3);
                sound(middle(0), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, pitch);
            });
        }
    }

    /** The hand is over: the cards slide into the middle and go. */
    private void sweep(Hand h) {
        for (int k = 0; k < 2; k++) {
            if (h.face[k] == null) continue;
            TableArt.Spot s = cardSpot(h.seat, k);
            Vector3f in = offsetTo(center, s.x(), s.z(), 0.01);
            Quaternionf rot = TableArt.facing(s.yaw());
            ItemDisplay f = h.face[k], b = h.back[k];
            animate(f, pose(CARD_SCALE * 0.9f, rot, in), 6);
            animate(b, pose(CARD_SCALE * 0.9f, rot, in), 6);
            later(7, () -> {
                kill(f);
                kill(b);
            });
        }
    }

    /** 섯다's second card is coming (the cards themselves are dealt by the next sync): a shuffle of the deck. */
    void street() {
        sound(middle(0), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 0.9f);
    }

    // ------------------------------------------------------------------------------------------ cards (세븐 포커)

    /**
     * 세븐 포커: every player's cards before their seat (two rows: hidden nearer them, open further in; TableArt.pokerSlot),
     * dealt one by one round the table (the first three, then a card a street), the opened card sliding over to the open
     * row and turning face up for everyone, folds pushed in, the hidden ones flipped at the showdown.
     */
    private void drawPoker(Table t) {
        boolean show = t.game() == Table.Game.POKER && t.phase() != Table.Phase.WAITING;
        if (!show || t.deals() != dealt) {
            if (t.game() == Table.Game.POKER) {
                for (PHand h : phands.values()) sweepPoker(h);
                phands.clear();
                dealt = show ? t.deals() : -1;
            }
        }
        if (!show) return;
        // the new cards, in the order they are dealt: card by card, round the table
        List<Object[]> fresh = new ArrayList<>();
        for (UUID u : t.players()) {
            Integer seat = host.seatOf(u);
            List<PCard> cards = t.pcards(u);
            if (seat == null || cards.isEmpty()) continue;
            PHand h = phands.computeIfAbsent(u, x -> {
                PHand n = new PHand();
                n.seat = seat;
                return n;
            });
            for (int k = 0; k < cards.size() && k < 7; k++) if (h.face[k] == null) fresh.add(new Object[]{u, k});
        }
        fresh.sort(java.util.Comparator.comparingInt(o -> (int) o[1]));
        int order = 0;
        for (Object[] f : fresh) {
            UUID u = (UUID) f[0];
            int k = (int) f[1];
            dealPoker(t, u, phands.get(u), k, t.pcards(u).get(k), order++);
        }
        if (!fresh.isEmpty()) sound(middle(0), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 0.9f);
        for (UUID u : t.players()) {
            PHand h = phands.get(u);
            if (h == null) continue;
            int opened = t.openedIndex(u);
            if (opened >= 0 && h.opened < 0) openPoker(t, u, h, opened);
            if (t.folded(u) && !h.folded) foldPoker(h);
            if (TableArt.revealed(t, u) && !h.revealed) revealPoker(t, u, h);
        }
    }

    private TableArt.Spot pokerSpot(int seat, int k, int opened) {
        double[] slot = TableArt.pokerSlot(k, opened);
        return TableArt.pokerSpot(center, seat(seat), reach, (int) slot[0], (int) slot[1]);
    }

    /** Card k of a player slides in from the middle (open cards face up for all, hidden ones face up for the owner alone). */
    private void dealPoker(Table t, UUID owner, PHand h, int k, PCard card, int order) {
        TableArt.Spot s = pokerSpot(h.seat, k, t.openedIndex(owner));
        h.spot[k] = s;
        h.moved[k] = new Vector3f();
        // open rows a little over the hidden ones and each card over the one before (overlapped in a row)
        Location l = at(s, 0.002 + (TableArt.pokerSlot(k, t.openedIndex(owner))[0] > 0 ? 0.006 : 0) + k * 0.0015);
        Quaternionf rot = TableArt.facing(s.yaw());
        Vector3f from = offsetTo(center, s.x(), s.z(), 0.05);
        boolean open = t.isOpen(owner, k);
        h.face[k] = item(l, card.item(), TableArt.PCARD_SCALE, rot, from, open);
        h.shown[k] = open;
        if (!open) {
            h.back[k] = item(l, PCard.BACK, TableArt.PCARD_SCALE, rot, from, true);
            Player p = Bukkit.getPlayer(owner);
            if (p != null) {
                p.showEntity(plugin, h.face[k]);
                p.hideEntity(plugin, h.back[k]);
            }
        }
        ItemDisplay f = h.face[k], b = h.back[k];
        int delay = 2 + order * 4;
        h.ready = Math.max(h.ready, tick + delay + 5);
        later(delay, () -> {
            animate(f, pose(TableArt.PCARD_SCALE, rot, null), 5);
            animate(b, pose(TableArt.PCARD_SCALE, rot, null), 5);
            sound(l, Sound.ITEM_BOOK_PAGE_TURN, 0.45f, 1.4f + rng.nextFloat() * 0.3f);
        });
    }

    /** The opened card slides from the hidden row to the open row and turns face up for everyone. */
    private void openPoker(Table t, UUID owner, PHand h, int k) {
        h.opened = k;
        if (h.face[k] == null || h.spot[k] == null) return;
        TableArt.Spot from = h.spot[k], to = pokerSpot(h.seat, k, k);
        Vector3f slide = new Vector3f((float) (to.x() - from.x()), 0.006f, (float) (to.z() - from.z()));
        h.moved[k] = slide;
        int wait = (int) Math.max(0, h.ready - tick);
        Quaternionf rot = TableArt.facing(from.yaw());
        ItemDisplay f = h.face[k], b = h.back[k];
        later(wait + 1, () -> {
            animate(f, pose(TableArt.PCARD_SCALE, rot, slide), 6);
            animate(b, pose(TableArt.PCARD_SCALE, rot, slide), 6);
            sound(at(to, 0), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.2f);
        });
        if (b != null) flip(b, f, f.getItemStack(), from, slide, wait + 8, TableArt.PCARD_SCALE, 1.1f);
        h.shown[k] = true;
    }

    /** 다이: the cards slide a little towards the middle and the open ones turn face down. */
    private void foldPoker(PHand h) {
        h.folded = true;
        for (int k = 0; k < 7; k++) {
            if (h.face[k] == null || h.spot[k] == null) continue;
            TableArt.Spot s = h.spot[k];
            Vector3f in = offsetTo(center, s.x(), s.z(), 0).mul(0.2f).add(h.moved[k]);
            Quaternionf rot = TableArt.facing(s.yaw()).rotateY((float) Math.toRadians((k % 2 == 0 ? 8 : -6)));
            animate(h.face[k], pose(TableArt.PCARD_SCALE, rot, in), 6);
            animate(h.back[k], pose(TableArt.PCARD_SCALE, rot, in), 6);
            if (h.shown[k]) {   // the open ones turn face down for everyone else (the owner still sees theirs)
                ItemDisplay x = h.back[k] != null ? h.back[k] : h.face[k];
                later(6, () -> {
                    if (x.isValid()) x.setItemStack(model(PCard.BACK));
                });
            }
        }
        sound(middle(0), Sound.ITEM_BOOK_PUT, 0.5f, 0.8f);
    }

    /** The showdown: the hidden cards flip over for everyone, seat by seat, after the last deal. */
    private void revealPoker(Table t, UUID owner, PHand h) {
        h.revealed = true;
        int wait = (int) Math.max(0, h.ready - tick);
        int n = 0;
        for (int k = 0; k < 7; k++) {
            if (h.face[k] == null || h.back[k] == null || h.shown[k]) continue;
            flip(h.back[k], h.face[k], h.face[k].getItemStack(), h.spot[k], h.moved[k], wait + 1 + h.seat * 3 + n * 3, TableArt.PCARD_SCALE, 1.0f + n * 0.15f);
            h.shown[k] = true;
            n++;
        }
    }

    /**
     * A card turned over where it lies (`base`: how far it was slid from its display's spot): lifted, edge on, the back
     * swapped for the face, down again.
     */
    private void flip(ItemDisplay back, ItemDisplay face, ItemStack faceItem, TableArt.Spot s, Vector3f base, int delay, float scale, float pitch) {
        Quaternionf flat = TableArt.facing(s.yaw());
        Vector3f axis = new Vector3f((float) (center[0] - s.x()), 0, (float) (center[1] - s.z())).normalize();
        Quaternionf edge = new Quaternionf().rotateAxis((float) Math.toRadians(90), axis.x, axis.y, axis.z).mul(flat);
        Quaternionf edgeBack = new Quaternionf().rotateAxis((float) Math.toRadians(-90), axis.x, axis.y, axis.z).mul(flat);
        Vector3f up = new Vector3f(base).add(0, 0.16f, 0), rest = new Vector3f(base);
        later(delay, () -> {
            animate(back, pose(scale, edge, up), 3);
            animate(face, pose(scale, edge, up), 3);
        });
        later(delay + 3, () -> {
            if (back.isValid()) back.setItemStack(faceItem);
            if (back.isValid()) back.setTransformation(pose(scale, edgeBack, up));
            if (face.isValid()) face.setTransformation(pose(scale, edgeBack, up));
        });
        later(delay + 4, () -> {
            animate(back, pose(scale, flat, rest), 3);
            animate(face, pose(scale, flat, rest), 3);
            sound(middle(0), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, pitch);
        });
    }

    private void sweepPoker(PHand h) {
        for (int k = 0; k < 7; k++) {
            if (h.face[k] == null || h.spot[k] == null) continue;
            TableArt.Spot s = h.spot[k];
            Vector3f in = offsetTo(center, s.x(), s.z(), 0.01);
            Quaternionf rot = TableArt.facing(s.yaw());
            ItemDisplay f = h.face[k], b = h.back[k];
            animate(f, pose(TableArt.PCARD_SCALE * 0.9f, rot, in), 6);
            animate(b, pose(TableArt.PCARD_SCALE * 0.9f, rot, in), 6);
            later(7, () -> {
                kill(f);
                kill(b);
            });
        }
    }

    // ------------------------------------------------------------------------------------------ the chip pile

    private void drawPile(Table t) {
        long pot = t.phase() == Table.Phase.PLAYING && t.game() != Table.Game.BACCARAT ? t.pot() : 0;   // 바카라: each seat's bets lie before it
        int level = TableArt.chipLevel(pot, t.ante());   // 0 in the result: it slid to the winners (finished)
        if (level != pileLevel || (level > 0 && (pile == null || !pile.isValid()))) {
            if (level == 0) kill(pile);
            if (level == 0) pile = null;
            else if (pile == null || !pile.isValid()) {
                pile = item(middle(0.001), "economy:gamble_chips_" + level, pileScale(), new Quaternionf(), new Vector3f(0, 0.25f, 0), true);
                ItemDisplay p = pile;
                float sc = pileScale() * TableArt.chipScale(pot, t.ante());
                later(2, () -> animate(p, pose(sc, new Quaternionf(), null), 5));
                sound(middle(0), Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.6f);
            } else {
                pile.setItemStack(model("economy:gamble_chips_" + level));
                ItemDisplay p = pile;
                float sc = pileScale() * TableArt.chipScale(pot, t.ante());
                p.setTransformation(pose(sc * 1.12f, new Quaternionf(), null));
                later(1, () -> animate(p, pose(sc, new Quaternionf(), null), 4));
                sound(middle(0), Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.2f + level * 0.1f);
            }
            pileLevel = level;
        } else if (pile != null && level > 0) {
            animate(pile, pose(pileScale() * TableArt.chipScale(pot, t.ante()), new Quaternionf(), null), 4);
        }
        Component label = pot > 0 ? Lang.mm("hwatu.pot-label", "pot", HallTable.fmt(pot)) : null;
        if (label == null) {
            kill(potLabel);
            potLabel = null;
        } else if (potLabel == null || !potLabel.isValid()) potLabel = text(middle(0.62), label, 0.35f, true);
        else potLabel.text(label);
    }

    // ------------------------------------------------------------------------------------------ dice (주사위)

    private TableArt.Spot dieSpot(int seat, int k, int n) {
        double side = TableArt.row(n, TableArt.DIE_GAP).get(k);
        return TableArt.along(center, seat(seat), reach.dice(), side, true);
    }

    /** Where a player's cup stands over their dice (the middle of the row). */
    private TableArt.Spot cupSpot(int seat) {
        return TableArt.along(center, seat(seat), reach.dice(), 0, true);
    }

    /** Where it is set down once lifted (beside the dice, never between them and anyone), or null: lifted away. */
    private TableArt.Spot asideSpot(int seat) {
        TableArt.Aside a = TableArt.cupAside(reach, size, host.spec().game(Table.Game.DICE).dice());
        return a == null ? null : TableArt.along(center, seat(seat), a.reach(), a.side(), true);
    }

    /** 주사위: a cup before each player in the hand (put down when the hand starts); a rolled player's dice beside it. */
    private void drawDice(Table t) {
        boolean on = t.game() == Table.Game.DICE && t.phase() != Table.Phase.WAITING;
        if (!on || t.hands() != cupHand) {
            for (Cup c : cups.values()) {
                kill(c.cup);
                for (ItemDisplay d : c.dice) kill(d);
            }
            cups.clear();
            cupHand = on ? t.hands() : -1;
        }
        if (!on) return;
        int n = host.spec().game(Table.Game.DICE).dice();
        int order = 0;
        for (UUID u : t.players()) {
            Integer seat = host.seatOf(u);
            if (seat == null || cups.containsKey(u)) continue;
            Cup c = new Cup();
            c.seat = seat;
            TableArt.Spot s = cupSpot(seat);
            Quaternionf rot = TableArt.facing(s.yaw());
            c.cup = item(at(s, 0.001), "economy:gamble_cup", CUP_SCALE, rot, new Vector3f(0, 0.4f, 0), true);
            ItemDisplay cup = c.cup;
            later(2 + order++ * 3, () -> {
                animate(cup, pose(CUP_SCALE, rot, null), 5);
                sound(at(s, 0), Sound.BLOCK_BAMBOO_WOOD_PLACE, 0.6f, 1.2f);
            });
            cups.put(u, c);
            int[] roll = t.rolls(u);
            if (roll != null) {   // drawn again mid-hand (a reload): already rolled, the dice out, the cup aside
                c.rolled = true;
                showDice(c, roll, n, 0);
                setAside(c, 0);
            }
        }
        for (Map.Entry<UUID, Cup> e : cups.entrySet()) {
            if (t.folded(e.getKey()) && e.getValue().cup != null && !e.getValue().rolled) {   // left: their cup goes
                kill(e.getValue().cup);
                e.getValue().cup = null;
            }
        }
    }

    private Quaternionf randomTurn() {
        return new Quaternionf().rotationXYZ(rng.nextFloat() * 6.283f, rng.nextFloat() * 6.283f, rng.nextFloat() * 6.283f);
    }

    /** Someone pressed 굴리기 (or the timer did): their cup rattles, then lifts off their dice; or the 홀짝 bowl does. */
    void rolled(UUID player) {
        Table t = host.table();
        if (t.game() == Table.Game.HOLJJAK) {
            shakeUntil = tick + SHAKE_TICKS;
            sound(middle(0), Sound.BLOCK_DECORATED_POT_HIT, 0.8f, 1.2f);
            return;
        }
        Cup c = cups.get(player);
        int[] roll = t.rolls(player);
        if (c == null || roll == null || c.rolled) return;
        c.rolled = true;
        TableArt.Spot s = cupSpot(c.seat);
        Quaternionf rot = TableArt.facing(s.yaw());
        ItemDisplay cup = c.cup;
        // the shake: the cup jumps and tips side to side, rattling
        for (int i = 0; i < SHAKE_TICKS; i += 3) {
            later(1 + i, () -> {
                float a = (float) Math.toRadians((rng.nextFloat() - 0.5f) * 24);
                Quaternionf wob = new Quaternionf(rot).rotateX(a).rotateZ(-a * 0.6f);
                animate(cup, pose(CUP_SCALE, wob, new Vector3f((rng.nextFloat() - 0.5f) * 0.05f, 0.02f + rng.nextFloat() * 0.08f,
                        (rng.nextFloat() - 0.5f) * 0.05f)), 3);
                sound(at(s, 0.1), Sound.BLOCK_BAMBOO_WOOD_HIT, 0.6f, 1.1f + rng.nextFloat() * 0.7f);
            });
        }
        later(SHAKE_TICKS + 1, () -> animate(cup, pose(CUP_SCALE, rot, null), 2));   // slammed down
        later(SHAKE_TICKS + 3, () -> sound(at(s, 0), Sound.BLOCK_WOOD_HIT, 0.9f, 0.9f));
        int n = roll.length;
        showDice(c, roll, n, SHAKE_TICKS + 6);
        setAside(c, SHAKE_TICKS + 6);
    }

    /** The dice under a cup, on their rolled faces, appearing as it lifts (`delay` ticks from now). */
    private void showDice(Cup c, int[] roll, int n, int delay) {
        for (int k = 0; k < roll.length && k < n; k++) {
            TableArt.Spot s = dieSpot(c.seat, k, n);
            Quaternionf face = TableArt.dieFace(roll[k], rng.nextFloat() * 360f);
            if (delay <= 0) {
                c.dice.add(item(at(s, DIE_HALF), "economy:gamble_die", DIE_SCALE, face, null, true));
                continue;
            }
            int k0 = k;
            later(delay, () -> {
                ItemDisplay d = item(at(s, DIE_HALF), "economy:gamble_die", DIE_SCALE, randomTurn(), new Vector3f(0, 0.08f, 0), true);
                c.dice.add(d);
                later(1, () -> animate(d, pose(DIE_SCALE, face, new Vector3f(0, 0.03f, 0)), 3));
                later(4, () -> animate(d, pose(DIE_SCALE, face, null), 2));
                if (k0 == 0) later(5, () -> sound(at(s, 0), Sound.BLOCK_WOOD_HIT, 0.7f, 1.6f));
            });
        }
    }

    /**
     * The cup lifts off the dice and is put down mouth up beside them, to its player's right and clear of every die
     * ({@link TableArt#cupAside}: before 2026-11-26 it landed between the player and the dice and hid them); on a table
     * too small for that it is lifted up and away (gone until the next hand).
     */
    private void setAside(Cup c, int delay) {
        if (c.cup == null) return;
        TableArt.Spot from = cupSpot(c.seat), to = asideSpot(c.seat);
        Quaternionf rot = TableArt.facing(from.yaw());
        if (to == null) {
            ItemDisplay cup = c.cup;
            c.cup = null;
            if (delay <= 0) {
                kill(cup);
                return;
            }
            later(delay - 3, () -> animate(cup, pose(CUP_SCALE, new Quaternionf(rot).rotateX((float) Math.toRadians(-20)), new Vector3f(0, 0.5f, 0)), 4));
            later(delay + 2, () -> animate(cup, pose(0.01f, rot, new Vector3f(0, 1.1f, 0)), 7));
            later(delay + 10, () -> kill(cup));
            return;
        }
        Vector3f lifted = new Vector3f(0, 0.45f, 0);
        Vector3f aside = new Vector3f((float) (to.x() - from.x()), 0, (float) (to.z() - from.z()));
        // mouth up: turned over (half round its own left-right axis), its rim then on the table at the model's height
        Quaternionf over = new Quaternionf(rot).rotateX((float) Math.PI);
        Vector3f down = new Vector3f(aside).add(0, (float) (TableArt.CUP_HEIGHT * CUP_SCALE), 0);
        ItemDisplay cup = c.cup;
        if (delay <= 0) {
            cup.setTransformation(pose(CUP_SCALE, over, down));
            return;
        }
        later(delay - 3, () -> animate(cup, pose(CUP_SCALE, new Quaternionf(rot).rotateX((float) Math.toRadians(-35)), lifted), 4));
        later(delay + 2, () -> animate(cup, pose(CUP_SCALE, over, new Vector3f(aside).add(0, 0.5f, 0)), 5));
        later(delay + 7, () -> {
            animate(cup, pose(CUP_SCALE, over, down), 3);
            sound(at(to, 0), Sound.BLOCK_BAMBOO_WOOD_PLACE, 0.6f, 0.9f);
        });
    }

    // ------------------------------------------------------------------------------------------ the bowl (홀짝)

    private void drawBowl(Table t) {
        boolean on = t.game() == Table.Game.HOLJJAK;
        if (!on) return;
        boolean fresh = t.phase() == Table.Phase.WAITING || (t.phase() == Table.Phase.PLAYING && (t.step() == Table.Step.PICK || t.step() == Table.Step.ROLL));
        boolean missing = bowl == null || !bowl.isValid();
        if (fresh && (bowlDie != null || bowlUp || missing)) {   // a new hand: the bowl comes (back) down over the middle, the die goes
            ItemDisplay d = bowlDie;
            bowlDie = null;
            bowlUp = false;
            shakeUntil = -1;
            if (missing) {
                bowl = item(middle(0.001), "economy:gamble_bowl", BOWL_SCALE, new Quaternionf(), new Vector3f(0, 0.6f, 0), true);
                ItemDisplay b = bowl;
                later(1, () -> animate(b, pose(BOWL_SCALE, new Quaternionf(), null), 6));
            } else animate(bowl, pose(BOWL_SCALE, new Quaternionf(), null), 6);
            later(6, () -> kill(d));
            later(6, () -> sound(middle(0), Sound.BLOCK_DECORATED_POT_PLACE, 0.7f, 1f));
        }
        // drawn again mid-reveal (a reload): the die is already out
        if (t.phase() != Table.Phase.WAITING && t.die() > 0 && !bowlUp && shakeUntil < 0) liftBowl();
        // the call (홀 / 짝) floats before the caller's seat
        Boolean odd = t.phase() == Table.Phase.WAITING ? null : t.calledOdd();
        if (odd == null) {
            kill(call);
            call = null;
        } else if (call == null || !call.isValid()) {
            Integer seat = t.players().isEmpty() ? null : host.seatOf(t.players().getFirst());
            TableArt.Spot s = TableArt.along(center, seat(seat == null ? 0 : seat), reach.timer(), 0, true);
            call = text(at(s, 0.5), Lang.mm("hwatu.called", "call", Lang.raw(odd ? "hwatu.hand.odd" : "hwatu.hand.even")), 0.5f, true);
            sound(at(s, 0.5), Sound.ENTITY_VILLAGER_YES, 0.5f, odd ? 1.3f : 0.9f);
        }
    }

    /** The bowl shaken on the table while the die rolls under it. */
    private void shake() {
        if (bowl == null) return;
        float a = (float) Math.toRadians((rng.nextFloat() - 0.5f) * 18);
        Quaternionf wob = new Quaternionf().rotationXYZ(a, rng.nextFloat() * 0.5f, -a * 0.7f);
        Vector3f slide = new Vector3f((rng.nextFloat() - 0.5f) * 0.05f, 0.01f + rng.nextFloat() * 0.06f, (rng.nextFloat() - 0.5f) * 0.05f);
        animate(bowl, pose(BOWL_SCALE, wob, slide), 3);
        sound(middle(0), Sound.BLOCK_DECORATED_POT_HIT, 0.5f, 1.1f + rng.nextFloat() * 0.4f);
    }

    /**
     * The shaking is over: the bowl is lifted off the die and put down beside it, across the line between the two players
     * ({@link TableArt#bowlAside}), so the die stays in full view of both until the next hand (before 2026-11-26 it hung
     * tipped over the die and hid it); on the duel crate, too small for that, it is lifted up and away (back next hand).
     */
    private void liftBowl() {
        int face = host.table().die();
        if (face <= 0 || bowlUp) return;
        bowlUp = true;
        kill(bowlDie);
        bowlDie = item(middle(DIE_HALF * 1.3), "economy:gamble_die", DIE_SCALE * 1.3f, TableArt.dieFace(face, rng.nextFloat() * 360f), null, true);
        later(11, () -> sound(middle(0), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, face % 2 == 1 ? 1.4f : 1.0f));
        if (bowl == null || !bowl.isValid()) return;
        animate(bowl, pose(BOWL_SCALE, new Quaternionf(), null), 2);
        ItemDisplay b = bowl;
        Vector3f up = new Vector3f(0, 0.5f, 0);
        TableArt.Aside a = TableArt.bowlAside(size);
        later(3, () -> {
            animate(b, pose(BOWL_SCALE, new Quaternionf().rotateX((float) Math.toRadians(20)), up), 5);
            sound(middle(0), Sound.BLOCK_DECORATED_POT_PLACE, 0.8f, 1.4f);
        });
        if (a == null) {   // lifted away
            later(8, () -> animate(b, pose(0.01f, new Quaternionf(), new Vector3f(0, 1.2f, 0)), 7));
            later(16, () -> {
                if (bowl == b) bowl = null;
                kill(b);
            });
            return;
        }
        List<UUID> ps = host.table().players();
        Integer seat0 = ps.isEmpty() ? null : host.seatOf(ps.getFirst());
        TableArt.Spot side = TableArt.along(center, seat(seat0 == null ? 0 : seat0), 0, a.side(), true);
        Vector3f aside = new Vector3f((float) (side.x() - center[0]), 0, (float) (side.z() - center[1]));
        later(8, () -> animate(b, pose(BOWL_SCALE, new Quaternionf(), new Vector3f(aside).add(0, 0.35f, 0)), 5));
        later(13, () -> {
            animate(b, pose(BOWL_SCALE, new Quaternionf(), aside), 3);
            sound(middle(0), Sound.BLOCK_DECORATED_POT_PLACE, 0.6f, 0.9f);
        });
    }

    // ------------------------------------------------------------------------------------------ 바카라

    private Location dealerSpot() {
        return at(dealerAt[0], floor, dealerAt[1]);
    }

    /** The dealer's yaw: facing the middle of the table (the players). */
    private float dealerYaw() {
        return TableArt.yawToward(center[0] - dealerAt[0], center[1] - dealerAt[1]);
    }

    private Location bacAt(double in, double side, double up) {
        double[] p = TableArt.bac(center, toDealer, in, side);
        return at(p[0], top + up, p[1]);
    }

    /** The cards face the players: their north side towards the curved side. */
    private Quaternionf bacCardTurn() {
        return TableArt.facing(TableArt.yawToward(-toDealer[0], -toDealer[1]));
    }

    /** 바카라: the dealer at their spot, the shoe, the coup, every seat's bets on the felt. */
    private void drawBaccarat(Table t) {
        if (t.game() != Table.Game.BACCARAT) return;
        keepDealer();
        drawBets(t);
        if (t.phase() == Table.Phase.PLAYING && t.step() == Table.Step.BETS && coupShown >= 0) clearCoup();
        if (t.coup() != null && t.coups() != coupShown && t.phase() == Table.Phase.PLAYING && t.step() == Table.Step.DEAL) dealCoup(t);
        if (t.coup() != null && t.coups() != coupShown && t.phase() == Table.Phase.RESULT) showCoup(t);   // drawn again mid-result (a reload)
    }

    private void keepDealer() {
        if (dealer != null && dealer.isValid()) return;
        Location l = dealerSpot();
        l.setYaw(dealerYaw());
        dealer = world.spawn(l, org.bukkit.entity.Mannequin.class, x -> {
            x.setInvulnerable(true);
            x.setSilent(true);
            x.setImmovable(true);
            x.setGravity(false);
            x.setCollidable(false);
            x.setRemoveWhenFarAway(false);
            x.customName(Lang.mm("hwatu.dealer-name"));
            x.setCustomNameVisible(true);
            x.setDescription(Lang.mm("hwatu.dealer-description"));
            String skin = host.dealerSkin();
            try {
                // the dealer's skin is slim (economy_bus_skins.py): " slim" may also be written after the key
                boolean slim = !skin.endsWith(" classic");
                String key = skin.replace(" slim", "").replace(" classic", "").trim();
                x.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile().name("dealer")
                        .skinPatch(sp -> sp.body(net.kyori.adventure.key.Key.key(key))
                                .model(slim ? org.bukkit.profile.PlayerTextures.SkinModel.SLIM : org.bukkit.profile.PlayerTextures.SkinModel.CLASSIC)).build());
            } catch (RuntimeException e) {
                plugin.getLogger().warning("[hwatu.yml] hall.dealer-skin 키가 잘못됨: " + skin);
            }
            tag(x);
        });
    }

    /** The dealer's arm deals (the swing) and their head follows the card. */
    private void gesture() {
        if (dealer != null && dealer.isValid()) dealer.swingMainHand();
    }

    /** A short polite line over the dealer's head for a few seconds. */
    void say(Component line) {
        Location l = dealerSpot().add(0, 2.35, 0);
        kill(speech);
        speech = text(l, line, 0.45f, true);
        speechUntil = tick + 50;
    }

    /** The cards of the coup in the order dealt: each slides face down from the shoe, then turns up; the totals follow. */
    private void dealCoup(Table t) {
        clearCoup();
        coupShown = t.coups();
        Baccarat.Coup c = t.coup();
        say(Lang.mm(t.shuffled() ? "hwatu.dealer.new-shoe" : "hwatu.dealer.no-more"));
        Location shoe = bacAt(TableArt.SHOE_IN, TableArt.SHOE_SIDE, 0.08);   // the model's shoe: its mouth
        Quaternionf flat = bacCardTurn();
        List<int[]> order = c.order();
        int base = (int) (Table.BAC_DEAL_BASE_MS / 50), step = (int) (Table.BAC_CARD_MS / 50);
        List<PCard> pSoFar = new ArrayList<>(), bSoFar = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            boolean player = order.get(i)[0] == 1;
            int k = order.get(i)[1];
            PCard card = (player ? c.player() : c.banker()).get(k);
            double[] p = TableArt.bacCard(center, toDealer, player, k);
            Location l = at(p[0], top + 0.002 + k * 0.002, p[1]);
            // the third card lies across (as dealt at a real table)
            Quaternionf rot = k == 2 ? new Quaternionf(flat).rotateY((float) Math.toRadians(90)) : new Quaternionf(flat);
            Vector3f from = new Vector3f((float) (shoe.getX() - p[0]), 0.06f, (float) (shoe.getZ() - p[1]));
            ItemDisplay d = item(l, PCard.BACK, TableArt.BAC_CARD_SCALE, rot, from, true);
            d.setInterpolationDuration(0);
            coupCards.add(d);
            int at = base + i * step;
            (player ? pSoFar : bSoFar).add(card);
            int pt = Baccarat.total(pSoFar), bt = Baccarat.total(bSoFar);
            later(at, () -> {
                gesture();
                animate(d, pose(TableArt.BAC_CARD_SCALE, rot, null), 7);
                sound(shoe, Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.2f);
            });
            Vector3f axis = new Vector3f((float) toDealer[0], 0, (float) toDealer[1]);
            Quaternionf edge = new Quaternionf().rotateAxis((float) Math.toRadians(90), axis.x, axis.y, axis.z).mul(rot);
            Quaternionf edgeBack = new Quaternionf().rotateAxis((float) Math.toRadians(-90), axis.x, axis.y, axis.z).mul(rot);
            Vector3f up = new Vector3f(0, 0.16f, 0);
            ItemStack face = model(card.item());
            later(at + 10, () -> animate(d, pose(TableArt.BAC_CARD_SCALE, edge, up), 3));
            later(at + 13, () -> {
                if (!d.isValid()) return;
                d.setItemStack(face);
                d.setTransformation(pose(TableArt.BAC_CARD_SCALE, edgeBack, up));
            });
            later(at + 14, () -> {
                animate(d, pose(TableArt.BAC_CARD_SCALE, rot, null), 3);
                sound(l, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, player ? 1.0f : 1.25f);
                totals(pt, bt, true);
            });
        }
    }

    /** Drawn again in the result (a reload): the coup lies face up at once. */
    private void showCoup(Table t) {
        clearCoup();
        coupShown = t.coups();
        Baccarat.Coup c = t.coup();
        Quaternionf flat = bacCardTurn();
        for (int[] o : c.order()) {
            boolean player = o[0] == 1;
            int k = o[1];
            double[] p = TableArt.bacCard(center, toDealer, player, k);
            Quaternionf rot = k == 2 ? new Quaternionf(flat).rotateY((float) Math.toRadians(90)) : new Quaternionf(flat);
            coupCards.add(item(at(p[0], top + 0.002 + k * 0.002, p[1]), (player ? c.player() : c.banker()).get(k).item(), TableArt.BAC_CARD_SCALE, rot, null, true));
        }
        totals(c.playerTotal(), c.bankerTotal(), true);
    }

    /** The totals over the two hands. */
    private void totals(int player, int banker, boolean any) {
        Location pl = bacAt(TableArt.HAND_IN, -TableArt.HAND_SIDE, 0.42), bl = bacAt(TableArt.HAND_IN, TableArt.HAND_SIDE, 0.42);
        Component pc = Lang.mm("hwatu.bac.total-player", "n", player), bc = Lang.mm("hwatu.bac.total-banker", "n", banker);
        if (playerTotal == null || !playerTotal.isValid()) playerTotal = text(pl, pc, 0.42f, true);
        else playerTotal.text(pc);
        if (bankerTotal == null || !bankerTotal.isValid()) bankerTotal = text(bl, bc, 0.42f, true);
        else bankerTotal.text(bc);
    }

    /** The coup's cards are swept to the discard (the dealer's right) and go, with the totals. */
    private void clearCoup() {
        coupShown = -1;
        Location discard = bacAt(TableArt.DISCARD_IN, TableArt.DISCARD_SIDE, 0.06);
        for (ItemDisplay d : coupCards) {
            if (!d.isValid()) continue;
            Vector3f to = new Vector3f((float) (discard.getX() - d.getLocation().getX()), 0.02f, (float) (discard.getZ() - d.getLocation().getZ()));
            animate(d, new Transformation(to, d.getTransformation().getLeftRotation(), new Vector3f(TableArt.BAC_CARD_SCALE * 0.9f), new Quaternionf()), 7);
            later(8, () -> kill(d));
        }
        coupCards.clear();
        kill(playerTotal);
        kill(bankerTotal);
        playerTotal = bankerTotal = null;
    }

    /** Every seat's bets: a little chip pile on 플레이어 · 뱅커 · 타이 before it, the amount over it; a new round clears them. */
    private void drawBets(Table t) {
        boolean live = t.phase() == Table.Phase.PLAYING;
        if (!live || t.hands() != betRound) {
            if (t.phase() != Table.Phase.RESULT) clearBets(); // the result slides them itself (finished)
            betRound = live ? t.hands() : betRound;
        }
        if (!live) return;
        long min = host.spec().baccarat().minBet();
        for (Map.Entry<UUID, long[]> e : t.allBets().entrySet()) {
            Integer seat = host.seatOf(e.getKey());
            if (seat == null) continue;
            for (Baccarat.Spot sp : Baccarat.Spot.values()) {
                long n = e.getValue()[sp.ordinal()];
                if (n <= 0) continue;
                String key = e.getKey() + ":" + sp.letter();
                if (betShown.getOrDefault(key, 0L) == n) continue;
                TableArt.Spot s = TableArt.betSpot(center, seat(seat), sp);
                ItemDisplay pile = betPiles.get(key);
                // the zone's chips (economy:baccarat_chip_<zone>), a little bigger the more is on it
                float sc = (float) (BET_SCALE * Math.min(1.4, 1 + 0.1 * Math.log((double) n / Math.max(1, min)) / Math.log(2)));
                if (pile == null || !pile.isValid()) {
                    Vector3f from = offsetTo(seat(seat), s.x(), s.z(), 0.1);
                    pile = item(at(s, 0.002), "economy:baccarat_chip_" + sp.key(), sc, new Quaternionf(), from, true);
                    betPiles.put(key, pile);
                    ItemDisplay p0 = pile;
                    later(1, () -> animate(p0, pose(sc, new Quaternionf(), null), 5));
                } else animate(pile, pose(sc * 1.1f, new Quaternionf(), null), 2);
                Component label = Lang.mm("hwatu.bac.bet-label", "spot", Lang.raw("hwatu.bac.spot-short." + sp.key()), "n", HallTable.fmt(n));
                TextDisplay lt = betLabels.get(key);
                if (lt == null || !lt.isValid()) betLabels.put(key, text(at(s, 0.3), label, 0.3f, true));
                else lt.text(label);
                betShown.put(key, n);
                sound(at(s, 0), Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.5f);
            }
        }
    }

    /** The chips on the felt: baccarat_chip_&lt;zone&gt; at this scale (about a ring's width across). */
    static final float BET_SCALE = 0.25f;

    private void clearBets() {
        for (ItemDisplay d : betPiles.values()) kill(d);
        for (TextDisplay d : betLabels.values()) kill(d);
        betPiles.clear();
        betLabels.clear();
        betShown.clear();
    }

    /** The bets window opened (again): the dealer asks for bets. */
    void betsOpen() {
        say(Lang.mm("hwatu.dealer.place-bets"));
        if (dealer != null && dealer.isValid()) sound(dealerSpot().add(0, 1.6, 0), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.5f);
    }

    /** 바카라 paid: the losing chips slide to the dealer, the house pays the winning ones, each seat's chips slide home. */
    private void payBaccarat(Table.Result r, int wait) {
        Baccarat.Spot won = r.coup().winner();
        say(Lang.mm("hwatu.dealer." + "won-" + won.key(), "player", r.coup().playerTotal(), "banker", r.coup().bankerTotal()));
        Map<String, ItemDisplay> piles = new HashMap<>(betPiles);
        Map<String, TextDisplay> labels = new HashMap<>(betLabels);
        betPiles.clear();
        betLabels.clear();
        betShown.clear();
        HwatuSpec.BaccaratSpec b = host.spec().baccarat();
        Location dl = dealerSpot();
        later(wait, () -> {
            for (Map.Entry<String, ItemDisplay> e : piles.entrySet()) {
                String[] k = e.getKey().split(":");
                UUID u = UUID.fromString(k[0]);
                Baccarat.Spot sp = Baccarat.Spot.of(k[1].charAt(0));
                ItemDisplay pile = e.getValue();
                TextDisplay label = labels.get(e.getKey());
                kill(label);
                if (pile == null || !pile.isValid() || sp == null) continue;
                long bet = r.detail().containsKey(u) ? betOf(r.detail().get(u), sp) : 0;
                long back = Baccarat.payout(sp, bet, won, b.commission(), b.tiePays());
                Location at = pile.getLocation();
                Integer seat = host.seatOf(u);
                if (back == 0 || seat == null) {   // the house takes it
                    animate(pile, pose(BET_SCALE * 0.8f, new Quaternionf(), new Vector3f((float) (dl.getX() - at.getX()), 0.05f, (float) (dl.getZ() - at.getZ())).mul(0.8f)), 10);
                    later(11, () -> kill(pile));
                    continue;
                }
                double[] home = seat(seat);
                Vector3f toSeat = new Vector3f((float) (home[0] - at.getX()), 0.02f, (float) (home[1] - at.getZ())).mul(0.45f);
                if (back > bet) {   // paid: the house's chips land beside it first
                    ItemDisplay paid = item(at.clone().add(0.1, 0, 0.04), "economy:baccarat_chip_" + sp.key(),
                            BET_SCALE, new Quaternionf(), new Vector3f((float) (dl.getX() - at.getX()), 0.1f, (float) (dl.getZ() - at.getZ())), true);
                    later(1, () -> animate(paid, pose(BET_SCALE, new Quaternionf(), null), 9));
                    later(22, () -> animate(paid, pose(BET_SCALE, new Quaternionf(), toSeat), 8));
                    later(31, () -> kill(paid));
                }
                later(22, () -> animate(pile, pose(BET_SCALE, new Quaternionf(), toSeat), 8));
                later(31, () -> kill(pile));
            }
            for (UUID u : r.players()) {
                long net = r.net(u);
                Integer seat = host.seatOf(u);
                if (seat == null) continue;
                TableArt.Spot s = TableArt.along(center, seat(seat), 0.7, 0, true);
                TextDisplay gain = text(at(s, 0.75), Lang.mm(net > 0 ? "hwatu.gain" : net == 0 ? "hwatu.bac.push" : "hwatu.bac.lose",
                        "n", HallTable.fmt(Math.abs(net)), "player", host.mini(u)), 0.4f, true);
                Transformation base = gain.getTransformation();
                later(2, () -> animate(gain, new Transformation(new Vector3f(0, 0.5f, 0), base.getLeftRotation(), base.getScale(), base.getRightRotation()), 40));
                later(44, () -> kill(gain));
            }
            sound(middle(0), won == Baccarat.Spot.TIE ? Sound.BLOCK_NOTE_BLOCK_BELL : Sound.BLOCK_CHAIN_FALL, 0.8f, 1.3f);
        });
    }

    /** A bet out of a result's detail ("P100,B0,T10"). */
    static long betOf(String detail, Baccarat.Spot sp) {
        if (detail == null) return 0;
        for (String part : detail.split(",")) {
            if (part.isEmpty() || part.charAt(0) != sp.letter()) continue;
            try {
                return Long.parseLong(part.substring(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private void drawBaccaratSign(Table t) {
        String state = switch (t.phase()) {
            case WAITING -> Lang.raw("hwatu.bac.sign-waiting");
            case PLAYING -> t.step() == Table.Step.BETS ? Lang.raw("hwatu.bac.sign-bets", "seconds", host.secondsLeft())
                    : Lang.raw("hwatu.bac.sign-dealing");
            case RESULT -> t.last() != null && t.last().coup() != null
                    ? Lang.raw("hwatu.bac.sign-result", "won", Lang.raw("hwatu.bac.won." + t.last().coup().winner().key()),
                    "player", t.last().coup().playerTotal(), "banker", t.last().coup().bankerTotal())
                    : Lang.raw("hwatu.sign-result", "seconds", host.secondsLeft());
        };
        HwatuSpec.BaccaratSpec b = host.spec().baccarat();
        sign.text(Lang.mm("hwatu.bac.sign", "game", host.gameName(), "room", host.roomName(), "min", HallTable.fmt(b.minBet()), "max", HallTable.fmt(b.maxBet()),
                "n", t.seats().size(), "max-seats", Math.min(t.max(), host.usableSeats().size()), "state", state, "road", road(t.road()),
                "count", roadCount(t.road())));
    }

    /** The road as coloured letters (oldest first), or a dash. */
    static String road(String road) {
        if (road == null || road.isEmpty()) return Lang.raw("hwatu.bac.road-empty");
        StringBuilder b = new StringBuilder();
        for (char c : road.toCharArray()) {
            Baccarat.Spot sp = Baccarat.Spot.of(c);
            if (sp != null) b.append(Lang.raw("hwatu.bac.road." + sp.key()));
        }
        return b.toString();
    }

    private static String roadCount(String road) {
        int p = 0, bk = 0, tie = 0;
        for (char c : (road == null ? "" : road).toCharArray()) {
            if (c == 'P') p++;
            else if (c == 'B') bk++;
            else if (c == 'T') tie++;
        }
        return Lang.raw("hwatu.bac.road-count", "p", p, "b", bk, "t", tie);
    }

    // ------------------------------------------------------------------------------------------ the turn timer

    private void drawTimer(Table t) {
        UUID turn = t.turn();
        Integer seat = turn == null ? null : host.seatOf(turn);
        if (seat == null && t.phase() == Table.Phase.PLAYING && (t.step() == Table.Step.OPEN || t.step() == Table.Step.BETS)) {
            // everyone at once (세븐 포커 picks the card to open, 바카라 bets): one timer over the middle
            long left = host.secondsLeft();
            int total = Math.max(1, t.moveSeconds());
            int full = (int) Math.round(8.0 * Math.min(left, total) / total);
            String bar = "▮".repeat(full) + "<dark_gray>" + "▮".repeat(8 - full) + "</dark_gray>";
            Component c = Lang.mm(t.step() == Table.Step.OPEN ? "hwatu.timer-open" : "hwatu.timer-bets", "seconds", left, "bar", bar);
            Location l = t.step() == Table.Step.BETS ? bacAt(0.1, 0, 0.9) : middle(0.95);
            if (timer == null || !timer.isValid() || timer.getLocation().distanceSquared(l) > 0.01) {
                kill(timer);
                timer = text(l, c, 0.4f, true);
            } else timer.text(c);
            return;
        }
        if (seat == null) {
            kill(timer);
            timer = null;
            return;
        }
        long left = host.secondsLeft();
        int total = Math.max(1, t.moveSeconds());
        int full = (int) Math.round(8.0 * Math.min(left, total) / total);
        String bar = "▮".repeat(full) + "<dark_gray>" + "▮".repeat(8 - full) + "</dark_gray>";
        Component c = Lang.mm(left <= 5 ? "hwatu.timer-hurry" : "hwatu.timer", "player", host.mini(turn), "seconds", left, "bar", bar);
        TableArt.Spot s = TableArt.along(center, seat(seat), reach.timer(), 0, true);
        Location l = at(s, 0.55);
        if (timer == null || !timer.isValid() || timer.getLocation().distanceSquared(l) > 0.01) {
            kill(timer);
            timer = text(l, c, 0.4f, true);
        } else timer.text(c);
    }

    // ------------------------------------------------------------------------------------------ buttons

    private void drawButtons(Table t) {
        Map<UUID, List<Controls.Button>> want = new HashMap<>();
        for (UUID u : host.seatedPlayers()) {
            if (Bukkit.getPlayer(u) == null) continue;
            List<Controls.Button> b = Controls.buttons(Controls.state(t, u, true, host.sittingOut(u), host.spec().baccarat().chips().size()));
            if (!b.isEmpty()) want.put(u, b);
        }
        for (Iterator<Map.Entry<UUID, Pad>> it = pads.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pad> e = it.next();
            List<Controls.Button> w = want.get(e.getKey());
            if (w == null || !key(t, e.getKey(), w).equals(e.getValue().key)) {
                for (Entity x : e.getValue().entities) kill(x);
                it.remove();
            }
        }
        for (Map.Entry<UUID, List<Controls.Button>> e : want.entrySet()) {
            if (pads.containsKey(e.getKey())) continue;
            Integer seat = host.seatOf(e.getKey());
            Player p = Bukkit.getPlayer(e.getKey());
            if (seat == null || p == null) continue;
            pads.put(e.getKey(), pad(p, seat, t, e.getValue()));
        }
    }

    /** What a viewer's buttons say (a change redraws them): the buttons and the amounts on them. */
    private String key(Table t, UUID u, List<Controls.Button> buttons) {
        StringBuilder b = new StringBuilder();
        for (Controls.Button x : buttons)
            b.append(x.key()).append(':').append(amount(t, u, x)).append(allIn(t, u, x) ? "!" : limit(t, u, x) ? "^" : "").append(';');
        if (t.game() == Table.Game.BACCARAT) b.append("chip").append(host.chipOf(u)).append(t.betsDone(u) ? "done" : "");
        return b.toString();
    }

    /** What a betting button would add for this viewer now (0 for the others). */
    private long amount(Table t, UUID u, Controls.Button b) {
        Betting bet = t.betting();
        int i = t.players().indexOf(u);
        Betting.Action a = b.action();
        if (bet == null || i < 0 || a == null || t.step() != Table.Step.BET) return 0;
        return bet.amount(i, a);
    }

    private boolean allIn(Table t, UUID u, Controls.Button b) {
        Betting bet = t.betting();
        int i = t.players().indexOf(u);
        Betting.Action a = b.action();
        return bet != null && i >= 0 && a != null && t.step() == Table.Step.BET && bet.allInBy(i, a);
    }

    /** A raise cut down to the hand limit (한도). */
    private boolean limit(Table t, UUID u, Controls.Button b) {
        Betting bet = t.betting();
        int i = t.players().indexOf(u);
        Betting.Action a = b.action();
        return bet != null && i >= 0 && a != null && t.step() == Table.Step.BET && bet.limitBy(i, a);
    }

    /** A button's words: its name, and on a betting button the chips it puts in (+120, 올인 +80, or +37 (한도)). */
    private Component label(Table t, UUID u, Controls.Button b) {
        if (b.open() >= 0) {   // 세븐 포커: 공개 + the card it opens
            List<PCard> mine = t.pcards(u);
            String card = b.open() < mine.size() ? HallTable.cardText(mine.get(b.open())) : "";
            return Lang.mm("hwatu.btn-open", "card", card);
        }
        if (b.chip() >= 0) {   // 바카라: a chip to pick (the picked one lit)
            List<Long> chips = host.spec().baccarat().chips();
            long v = b.chip() < chips.size() ? chips.get(b.chip()) : 0;
            return Lang.mm(host.chipOf(u) == b.chip() ? "hwatu.btn-chip-on" : "hwatu.btn-chip", "n", HallTable.fmt(v));
        }
        if (b.spot() != null) {   // 바카라: 플레이어 · 뱅커 · 타이 with the picked chip
            long[] mine = t.bets(u);
            return Lang.mm("hwatu.btn-spot", "name", Lang.raw("hwatu.btn." + b.key()), "n", HallTable.fmt(host.chipValue(u)),
                    "on", mine[b.spot().ordinal()] > 0 ? Lang.raw("hwatu.btn-spot-on", "n", HallTable.fmt(mine[b.spot().ordinal()])) : "");
        }
        if (b == Controls.Button.BAC_DONE) return Lang.mm(t.betsDone(u) ? "hwatu.btn.bet-done" : "hwatu.btn.bet");
        long n = amount(t, u, b);
        String name = Lang.raw("hwatu.btn." + b.key() + (b == Controls.Button.CALL && n == 0 ? "-free" : ""), "n", HallTable.fmt(n));
        if (b.action() == null || n == 0) return Text.mm(name);
        String chips = Lang.raw(allIn(t, u, b) ? "hwatu.btn-allin" : limit(t, u, b) ? "hwatu.btn-limit" : "hwatu.btn-amount", "n", HallTable.fmt(n));
        return Lang.mm("hwatu.btn-label", "name", name, "amount", chips);
    }

    private Pad pad(Player p, int seat, Table t, List<Controls.Button> buttons) {
        Pad pad = new Pad();
        pad.key = key(t, p.getUniqueId(), buttons);
        // 바카라's chips lie in a row of their own, nearer the player (flat piles: they hide nothing behind them)
        List<Controls.Button> chips = buttons.stream().filter(x -> x.chip() >= 0).toList();
        List<Controls.Button> row = buttons.stream().filter(x -> x.chip() < 0).toList();
        List<TableArt.Slot> slots = TableArt.pad(row.size(), reach);
        List<TableArt.Slot> chipSlots = TableArt.pad(chips.size(), reach);
        double[] at = seat(seat);
        for (int i = 0; i < buttons.size(); i++) {
            Controls.Button b = buttons.get(i);
            boolean chip = b.chip() >= 0;
            TableArt.Slot slot = chip ? chipSlots.get(chips.indexOf(b)) : slots.get(row.indexOf(b));
            float scale = (float) (BUTTON_SCALE * slot.scale());
            double half = BUTTON_HALF * slot.scale();
            double side = slot.side();
            if (b.open() >= 0) {   // 공개: right before the card it opens (the hidden row's slots)
                side = TableArt.row(3, TableArt.POKER_HIDDEN_GAP).get(b.open());
                scale = (float) (BUTTON_SCALE * TableArt.POKER_HIDDEN_GAP / TableArt.BUTTON_GAP);
                half = BUTTON_HALF * TableArt.POKER_HIDDEN_GAP / TableArt.BUTTON_GAP;
            }
            double out = chip ? TableArt.CHIP_RADIUS / Math.max(1e-6, Math.hypot(at[0] - center[0], at[1] - center[1])) : reach.button();
            TableArt.Spot s = TableArt.along(center, at, out, side, true);
            double lift = kind == TableKind.POKER ? TableArt.POKER_BUTTON_LIFT : 0;   // over the hidden row's sight line
            Location l = at(s, chip ? 0.002 : half + 0.03 + lift);
            Quaternionf rot = chip ? new Quaternionf() : TableArt.facing(s.yaw()).rotateX((float) Math.toRadians(BUTTON_TILT));
            if (chip) {
                scale = (b.chip() == host.chipOf(p.getUniqueId()) ? TableArt.CHIP_PICKED_SCALE : TableArt.CHIP_SCALE) * (float) slot.scale();
                half = 0.0;   // a flat pile: its amount just over it
            }
            ItemDisplay d = item(l, b.model(), scale * 0.2f, rot, null, false);
            p.showEntity(plugin, d);
            int delay = 1 + i;
            float sc = scale;
            double hf = half;
            later(delay, () -> animate(d, pose(sc, rot, null), 4));
            Interaction hit = world.spawn(at(s, lift), Interaction.class, x -> {
                x.setInteractionWidth((float) (TableArt.BUTTON_GAP * (b.open() >= 0 ? TableArt.POKER_HIDDEN_GAP / TableArt.BUTTON_GAP : slot.scale())) - 0.03f);
                x.setInteractionHeight((float) (2 * hf + 0.12));
                x.setResponsive(true);
                x.setVisibleByDefault(false);
                x.getPersistentDataContainer().set(BUTTON, PersistentDataType.STRING, p.getUniqueId() + ";" + b.key());
                tag(x);
            });
            p.showEntity(plugin, hit);
            TextDisplay label = text(at(s, lift + 2 * half + (chip ? 0.07 : 0.16)), label(t, p.getUniqueId(), b), (float) Math.max(0.16, 0.2 * slot.scale()), false);
            p.showEntity(plugin, label);
            pad.entities.add(d);
            pad.entities.add(hit);
            pad.entities.add(label);
            pad.displays.put(b, d);
        }
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.6f);
        return pad;
    }

    // ------------------------------------------------------------------------------------------ a bet

    /** A 섯다 bet: what it was floats up over the seat (○○ 하프! +120칩, 올인) with its sound. */
    void bet(Table.Bet b, Component what) {
        Integer seat = host.seatOf(b.player());
        if (seat == null) return;
        TableArt.Spot s = TableArt.along(center, seat(seat), reach.timer(), 0, true);
        TextDisplay pop = text(at(s, 0.85), what, b.allIn() ? 0.5f : 0.4f, true);
        Transformation base = pop.getTransformation();
        later(2, () -> animate(pop, new Transformation(new Vector3f(0, 0.35f, 0), base.getLeftRotation(), base.getScale(), base.getRightRotation()), 30));
        later(40, () -> kill(pop));
        Location l = at(s, 0.3);
        switch (b.action()) {
            case DIE -> sound(l, Sound.BLOCK_WOOL_PLACE, 0.8f, 0.7f);
            case CHECK -> {
                sound(l, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.8f, 1.0f);
                later(3, () -> sound(l, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.8f, 1.1f));
            }
            case PING, CALL -> sound(l, Sound.BLOCK_CHAIN_PLACE, 0.8f, b.action() == Betting.Action.PING ? 1.7f : 1.3f);
            case DDADANG -> {
                sound(l, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
                later(3, () -> sound(l, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.6f));
            }
            case HALF -> {
                sound(l, Sound.BLOCK_CHAIN_PLACE, 0.9f, 1.0f);
                later(2, () -> sound(l, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.9f));
            }
        }
        if (b.allIn()) {
            later(4, () -> sound(l, Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.4f));
            world.spawnParticle(Particle.CRIT, at(s, 0.6), 18, 0.2, 0.2, 0.2, 0.15);
        }
    }

    /** A button pressed: it dips and springs back. */
    void pressed(UUID viewer, Controls.Button b) {
        Pad pad = pads.get(viewer);
        ItemDisplay d = pad == null ? null : pad.displays.get(b);
        if (d == null || !d.isValid()) return;
        Transformation now = d.getTransformation();
        animate(d, new Transformation(new Vector3f(now.getTranslation()).add(0, -0.03f, 0), now.getLeftRotation(),
                new Vector3f(now.getScale()).mul(0.85f), now.getRightRotation()), 2);
        later(2, () -> animate(d, now, 3));
    }

    // ------------------------------------------------------------------------------------------ the result

    /** A hand ended: dice land / the bowl lifts, then the pile slides to the winner(s) with what each won. */
    void finished(Table.Result r, boolean big) {
        int wait = 0;
        if (r.game() == Table.Game.BACCARAT) {
            payBaccarat(r, 6);
            if (big) later(10, this::bigWin);
            return;
        }
        if (r.game() == Table.Game.SUTDA && !r.uncontested()) {   // the 섯다 cards flip first (sync after this), after the last deal
            long ready = tick;
            for (Hand h : hands.values()) ready = Math.max(ready, h.ready);
            wait = 14 + (int) (ready - tick);
        } else if (r.game() == Table.Game.POKER && !r.uncontested()) {   // the hidden cards flip first, seat by seat
            long ready = tick;
            for (PHand h : phands.values()) ready = Math.max(ready, h.ready);
            wait = 22 + (int) (ready - tick);
        } else if (r.game() == Table.Game.HOLJJAK && !r.uncontested()) {
            liftBowl();   // already up after the shake; at once if the result came first
            wait = 4;
        }
        ItemDisplay from = pile;
        pile = null;
        pileLevel = 0;
        kill(potLabel);
        potLabel = null;
        int level = Math.max(1, TableArt.chipLevel(r.pot(), Math.max(1, host.table().ante())));
        List<UUID> winners = r.winners();
        later(wait, () -> {
            kill(from);
            for (UUID w : winners) {
                Integer seat = host.seatOf(w);
                if (seat == null) continue;
                TableArt.Spot s = TableArt.along(center, seat(seat), 0.78, 0, true);
                ItemDisplay chips = item(at(s, 0.002), "economy:gamble_chips_" + Math.max(1, level - (winners.size() > 1 ? 1 : 0)),
                        pileScale(), new Quaternionf(), offsetTo(center, s.x(), s.z(), 0.06), true);
                later(1, () -> animate(chips, pose(pileScale(), new Quaternionf(), null), 8));
                later(36, () -> kill(chips));
                long won = r.payout().getOrDefault(w, 0L) - r.put().getOrDefault(w, 0L);
                TextDisplay gain = text(at(s, 0.75), Lang.mm("hwatu.gain", "n", HallTable.fmt(Math.max(0, won)), "player", host.mini(w)), 0.4f, true);
                Transformation base = gain.getTransformation();
                later(2, () -> animate(gain, new Transformation(new Vector3f(0, 0.5f, 0), base.getLeftRotation(), base.getScale(), base.getRightRotation()), 40));
                later(44, () -> kill(gain));
            }
            sound(middle(0), Sound.BLOCK_CHAIN_FALL, 0.8f, 1.3f);
            later(6, () -> sound(middle(0), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.1f));
            if (big) bigWin();
        });
    }

    /** A big pot: sparks over the table and a fanfare. */
    private void bigWin() {
        Location l = middle(1.2);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, l, 80, 0.6, 0.6, 0.6, 0.35);
        world.spawnParticle(Particle.FIREWORK, l, 40, 0.8, 0.4, 0.8, 0.08);
        sound(l, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        later(10, () -> world.spawnParticle(Particle.FIREWORK, middle(1.6), 30, 1.0, 0.5, 1.0, 0.05));
    }

    // ------------------------------------------------------------------------------------------ viewers

    /** A player came (back) online: shows them their own cards and buttons again (per-viewer hiding does not last). */
    void showTo(Player p) {
        UUID u = p.getUniqueId();
        Hand h = hands.get(u);
        if (h != null) {
            for (int k = 0; k < 2; k++) {
                if (h.face[k] != null && h.face[k].isValid()) p.showEntity(plugin, h.face[k]);
                if (h.back[k] != null && h.back[k].isValid()) p.hideEntity(plugin, h.back[k]);
            }
        }
        PHand ph = phands.get(u);
        if (ph != null) {
            for (int k = 0; k < 7; k++) {
                if (ph.back[k] == null) continue;   // open: one display everyone sees
                if (ph.face[k] != null && ph.face[k].isValid()) p.showEntity(plugin, ph.face[k]);
                if (ph.back[k].isValid()) p.hideEntity(plugin, ph.back[k]);
            }
        }
        Pad pad = pads.get(u);
        if (pad != null) for (Entity e : pad.entities) if (e.isValid()) p.showEntity(plugin, e);
    }

    /** A viewer's buttons go (they stood up or left). */
    void dropPad(UUID u) {
        Pad pad = pads.remove(u);
        if (pad != null) for (Entity e : pad.entities) kill(e);
    }

    /** Whether this scene owns the entity (a stray of an old scene does not). */
    boolean owns(Entity e) {
        return owned.contains(e);
    }

    /** Text over a seat (a 시험 bot without a mannequin). */
    TextDisplay seatLabel(int seat, Component c) {
        double[] s = seat(seat);
        return text(at(s[0], top + 1.1, s[1]), c, 0.45f, true);
    }
}
