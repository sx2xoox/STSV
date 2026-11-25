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
 * else the back: two displays a card, hidden per viewer), the chip pile in the middle, the dice and the 홀짝 bowl, the turn
 * timer and the buttons of whoever's turn it is (seen and clicked by them alone). Every entity here is not saved
 * (setPersistent(false)) and tagged {@link #TAG}: a chunk unloading or the server stopping takes them away, and
 * {@link HallTable} draws the table again when its furniture is back. Animations are interpolated transformations, stepped
 * by {@link #tick()} every tick. Where things go: {@link TableArt}.
 */
final class TableScene {

    /** Every entity of a scene: the furniture entity it belongs to (purged when found without its scene). */
    static final NamespacedKey TAG = new NamespacedKey("economy", "hwatu_scene");
    /** A button's click box: "viewer;button". */
    static final NamespacedKey BUTTON = new NamespacedKey("economy", "hwatu_button");

    /** Sizes of the models on the table (scale of a display; the models are drawn at their own size, 16 px a block). */
    static final float CARD_SCALE = 0.75f, BUTTON_SCALE = 0.4f, DIE_SCALE = 0.6f, CHIPS_SCALE = 0.45f, BOWL_SCALE = 0.8f;
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
    private final double top;
    private final TableArt.Reach reach;
    private final Random rng = new Random();
    private final List<Entity> owned = new ArrayList<>();

    private TextDisplay sign, potLabel, timer;
    private ItemDisplay pile;
    private int pileLevel;
    private final Map<UUID, Hand> hands = new LinkedHashMap<>();
    private final Map<UUID, List<ItemDisplay>> dice = new HashMap<>();
    private ItemDisplay bowl, bowlDie;
    private TextDisplay call;
    private final Map<UUID, Pad> pads = new HashMap<>();
    /** queued animation steps: tick → what */
    private final List<Object[]> queue = new ArrayList<>();
    private long tick;
    private boolean rolling, shaking;

    /** One player's two cards: faces (seen by the owner) and backs (seen by everyone else), as dealt. */
    private static final class Hand {
        Card[] cards;
        int seat;
        final ItemDisplay[] face = new ItemDisplay[2], back = new ItemDisplay[2];
        boolean folded, revealed;
    }

    /** A viewer's buttons: what they are (to see a change) and their entities. */
    private static final class Pad {
        String key = "";
        final List<Entity> entities = new ArrayList<>();
        final Map<Controls.Button, ItemDisplay> displays = new HashMap<>();
    }

    TableScene(HallTable host, Plugin plugin, World world, List<Location> seatSpots, double top, TableKind kind) {
        this.host = host;
        this.plugin = plugin;
        this.world = world;
        for (Location l : seatSpots) seats.add(new double[]{l.getX(), l.getZ()});
        this.center = TableArt.center(seats);
        this.top = top;
        this.reach = TableArt.reach(kind);
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

    private void sound(Location l, Sound s, float volume, float pitch) {
        world.playSound(l, s, volume, pitch);
    }

    // ------------------------------------------------------------------------------------------ the life of a scene

    /** Draws the table as it is now (spawned with its furniture, or again after a reload). */
    void spawn() {
        sign = text(middle(1.75), Component.empty(), 0.6f, true);
        sync();
    }

    /** Takes everything away (the furniture went, or the hall stops). */
    void remove() {
        for (Entity e : new ArrayList<>(owned)) if (e.isValid()) e.remove();
        owned.clear();
        hands.clear();
        dice.clear();
        pads.clear();
        queue.clear();
        sign = potLabel = timer = call = null;
        pile = bowl = bowlDie = null;
        pileLevel = 0;
    }

    /** Every tick: due animation steps, the rolling dice and the shaken bowl. */
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
        if (rolling && tick % 3 == 0) tumble();
        if (shaking && tick % 3 == 0) shake();
    }

    /** Brings the displays in line with the table (after every change, and once a second for the timer). */
    void sync() {
        Table t = host.table();
        drawSign(t);
        drawCards(t);
        drawPile(t);
        drawDice(t);
        drawBowl(t);
        drawTimer(t);
        drawButtons(t);
    }

    // ------------------------------------------------------------------------------------------ the sign

    private void drawSign(Table t) {
        if (sign == null || !sign.isValid()) sign = text(middle(1.75), Component.empty(), 0.6f, true);
        String state = switch (t.phase()) {
            case WAITING -> t.seats().size() < 2 ? Lang.raw("hwatu.sign-alone")
                    : Lang.raw("hwatu.sign-starting", "seconds", host.secondsLeft());
            case PLAYING -> t.step() == Table.Step.ROLL ? Lang.raw("hwatu.sign-rolling")
                    : t.lastBet() != null ? Lang.raw("hwatu.sign-bet", "player", host.mini(t.lastBet().player()), "act", HallTable.act(t.lastBet()))
                    : Lang.raw("hwatu.sign-playing");
            case RESULT -> Lang.raw("hwatu.sign-result", "seconds", host.secondsLeft());
        };
        sign.text(Lang.mm("hwatu.sign", "game", host.gameName(), "room", host.roomName(), "ante", HallTable.fmt(t.ante()),
                "n", t.seats().size(), "max", Math.min(t.max(), host.usableSeats().size()), "state", state));
    }

    // ------------------------------------------------------------------------------------------ cards (섯다)

    private void drawCards(Table t) {
        boolean show = t.game() == Table.Game.SUTDA && t.phase() != Table.Phase.WAITING;
        for (Iterator<Map.Entry<UUID, Hand>> it = hands.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Hand> e = it.next();
            Card[] now = show ? t.cards(e.getKey()) : null;
            if (now != e.getValue().cards) {
                sweep(e.getValue());
                it.remove();
            }
        }
        if (!show) return;
        int order = 0;
        for (UUID u : t.players()) {
            Card[] c = t.cards(u);
            Integer seat = host.seatOf(u);
            if (c == null || seat == null) continue;
            Hand h = hands.get(u);
            if (h == null) {
                h = deal(u, seat, c, order++);
                hands.put(u, h);
            }
            if (t.folded(u) && !h.folded) fold(h);
            if (TableArt.revealed(t, u) && !h.revealed) reveal(h);
        }
    }

    private TableArt.Spot cardSpot(int seat, int k) {
        double side = TableArt.row(2, TableArt.CARD_GAP).get(k);
        return TableArt.along(center, seat(seat), reach.card(), side, true);
    }

    private Hand deal(UUID owner, int seat, Card[] cards, int order) {
        Hand h = new Hand();
        h.cards = cards;
        h.seat = seat;
        Player p = Bukkit.getPlayer(owner);
        for (int k = 0; k < 2; k++) {
            TableArt.Spot s = cardSpot(seat, k);
            Location l = at(s, 0.002 + k * 0.004);
            Quaternionf rot = TableArt.facing(s.yaw());
            Vector3f from = offsetTo(center, s.x(), s.z(), 0.05);
            String faceModel = "economy:gamble_card_" + cards[k].item().substring("economy:hwatu_".length());
            h.face[k] = item(l, faceModel, CARD_SCALE, rot, from, false);
            h.back[k] = item(l, "economy:gamble_card_back", CARD_SCALE, rot, from, true);
            if (p != null) {
                p.showEntity(plugin, h.face[k]);
                p.hideEntity(plugin, h.back[k]);
            }
            ItemDisplay f = h.face[k], b = h.back[k];
            int delay = 2 + order * 4 + k * 2;
            later(delay, () -> {
                animate(f, pose(CARD_SCALE, rot, null), 5);
                animate(b, pose(CARD_SCALE, rot, null), 5);
                sound(l, Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 1.3f + rng.nextFloat() * 0.3f);
            });
        }
        return h;
    }

    /** 다이: the cards slide a little towards the middle, still face down. */
    private void fold(Hand h) {
        h.folded = true;
        for (int k = 0; k < 2; k++) {
            TableArt.Spot s = cardSpot(h.seat, k);
            Quaternionf rot = TableArt.facing(s.yaw()).rotateY((float) Math.toRadians(k == 0 ? 12 : -9));
            Vector3f in = offsetTo(center, s.x(), s.z(), 0).mul(0.25f);
            animate(h.face[k], pose(CARD_SCALE, rot, in), 6);
            animate(h.back[k], pose(CARD_SCALE, rot, in), 6);
        }
        sound(middle(0), Sound.ITEM_BOOK_PUT, 0.5f, 0.8f);
    }

    /** The showdown: everyone's back flips over to the face (lifted, edge on, swapped, down again). */
    private void reveal(Hand h) {
        h.revealed = true;
        for (int k = 0; k < 2; k++) {
            TableArt.Spot s = cardSpot(h.seat, k);
            Quaternionf flat = TableArt.facing(s.yaw());
            Vector3f axis = new Vector3f((float) (center[0] - s.x()), 0, (float) (center[1] - s.z())).normalize();
            Quaternionf edge = new Quaternionf().rotateAxis((float) Math.toRadians(90), axis.x, axis.y, axis.z).mul(flat);
            Quaternionf edgeBack = new Quaternionf().rotateAxis((float) Math.toRadians(-90), axis.x, axis.y, axis.z).mul(flat);
            ItemDisplay back = h.back[k], face = h.face[k];
            ItemStack faceItem = face == null ? null : face.getItemStack();
            int delay = 1 + h.seat * 2 + k * 3;
            float pitch = 1.0f + k * 0.2f;
            Vector3f up = new Vector3f(0, 0.18f, 0);
            later(delay, () -> {
                animate(back, pose(CARD_SCALE, edge, up), 3);
                animate(face, pose(CARD_SCALE, edge, up), 3);
            });
            later(delay + 3, () -> {
                if (back != null && back.isValid() && faceItem != null) back.setItemStack(faceItem);
                if (back != null && back.isValid()) back.setTransformation(pose(CARD_SCALE, edgeBack, up));
                if (face != null && face.isValid()) face.setTransformation(pose(CARD_SCALE, edgeBack, up));
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

    // ------------------------------------------------------------------------------------------ the chip pile

    private void drawPile(Table t) {
        long pot = t.phase() == Table.Phase.PLAYING ? t.pot() : 0;
        int level = TableArt.chipLevel(pot, t.ante());   // 0 in the result: it slid to the winners (finished)
        if (level != pileLevel || (level > 0 && (pile == null || !pile.isValid()))) {
            if (level == 0) kill(pile);
            if (level == 0) pile = null;
            else if (pile == null || !pile.isValid()) {
                pile = item(middle(0.001), "economy:gamble_chips_" + level, CHIPS_SCALE, new Quaternionf(), new Vector3f(0, 0.25f, 0), true);
                ItemDisplay p = pile;
                float sc = CHIPS_SCALE * TableArt.chipScale(pot, t.ante());
                later(2, () -> animate(p, pose(sc, new Quaternionf(), null), 5));
                sound(middle(0), Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.6f);
            } else {
                pile.setItemStack(model("economy:gamble_chips_" + level));
                ItemDisplay p = pile;
                float sc = CHIPS_SCALE * TableArt.chipScale(pot, t.ante());
                p.setTransformation(pose(sc * 1.12f, new Quaternionf(), null));
                later(1, () -> animate(p, pose(sc, new Quaternionf(), null), 4));
                sound(middle(0), Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.2f + level * 0.1f);
            }
            pileLevel = level;
        } else if (pile != null && level > 0) {
            animate(pile, pose(CHIPS_SCALE * TableArt.chipScale(pot, t.ante()), new Quaternionf(), null), 4);
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

    private void drawDice(Table t) {
        boolean on = t.game() == Table.Game.DICE && t.phase() != Table.Phase.WAITING;
        if (!on) {
            rolling = false;
            for (List<ItemDisplay> l : dice.values()) for (ItemDisplay d : l) kill(d);
            dice.clear();
            return;
        }
        if (t.phase() == Table.Phase.PLAYING && t.step() == Table.Step.ROLL) {
            int n = host.spec().game(Table.Game.DICE).dice();
            for (UUID u : t.players()) {
                Integer seat = host.seatOf(u);
                if (seat == null || dice.containsKey(u) || !t.inHand(u)) continue;
                List<ItemDisplay> mine = new ArrayList<>();
                for (int k = 0; k < n; k++) {
                    TableArt.Spot s = dieSpot(seat, k, n);
                    Vector3f from = offsetTo(seat(seat), s.x(), s.z(), 0.35).mul(0.6f);
                    mine.add(item(at(s, DIE_HALF), "economy:gamble_die", DIE_SCALE, randomTurn(), from, true));
                }
                dice.put(u, mine);
                sound(middle(0), Sound.BLOCK_BAMBOO_WOOD_HIT, 0.7f, 1.4f);
            }
            rolling = true;
        }
    }

    private Quaternionf randomTurn() {
        return new Quaternionf().rotationXYZ(rng.nextFloat() * 6.283f, rng.nextFloat() * 6.283f, rng.nextFloat() * 6.283f);
    }

    /** Rolling: every die tumbles and hops a little (3 ticks a step). */
    private void tumble() {
        for (List<ItemDisplay> l : dice.values()) {
            for (ItemDisplay d : l) {
                float hop = 0.02f + rng.nextFloat() * 0.08f;
                Vector3f jitter = new Vector3f((rng.nextFloat() - 0.5f) * 0.06f, hop, (rng.nextFloat() - 0.5f) * 0.06f);
                animate(d, pose(DIE_SCALE, randomTurn(), jitter), 3);
            }
        }
        if (!dice.isEmpty() && rng.nextInt(3) == 0) sound(middle(0), Sound.BLOCK_BAMBOO_WOOD_HIT, 0.4f, 1.2f + rng.nextFloat() * 0.6f);
    }

    /** The result: each die lands on its rolled face. */
    private void landDice(Table.Result r) {
        rolling = false;
        Table t = host.table();
        for (Map.Entry<UUID, List<ItemDisplay>> e : dice.entrySet()) {
            int[] roll = t.rolls(e.getKey());
            if (roll == null) continue;
            List<ItemDisplay> l = e.getValue();
            for (int k = 0; k < l.size() && k < roll.length; k++) {
                ItemDisplay d = l.get(k);
                Quaternionf face = TableArt.dieFace(roll[k], rng.nextFloat() * 360f);
                animate(d, pose(DIE_SCALE, face, new Vector3f(0, 0.05f, 0)), 3);
                later(3, () -> animate(d, pose(DIE_SCALE, face, null), 2));
            }
        }
        later(4, () -> sound(middle(0), Sound.BLOCK_WOOD_HIT, 0.8f, 1.5f));
    }

    // ------------------------------------------------------------------------------------------ the bowl (홀짝)

    private void drawBowl(Table t) {
        boolean on = t.game() == Table.Game.HOLJJAK;
        if (!on) return;
        if (bowl == null || !bowl.isValid()) bowl = item(middle(0.001), "economy:gamble_bowl", BOWL_SCALE, new Quaternionf(), null, true);
        boolean roll = t.phase() == Table.Phase.PLAYING && t.step() == Table.Step.ROLL;
        if (roll && !shaking) {
            shaking = true;
            sound(middle(0), Sound.BLOCK_DECORATED_POT_HIT, 0.8f, 1.2f);
        }
        if (!roll) shaking = false;
        if (t.phase() == Table.Phase.WAITING || (t.phase() == Table.Phase.PLAYING && t.step() == Table.Step.PICK)) {
            if (bowlDie != null) {   // a new hand: the bowl comes down over the die, which goes
                ItemDisplay d = bowlDie;
                bowlDie = null;
                animate(bowl, pose(BOWL_SCALE, new Quaternionf(), null), 6);
                later(6, () -> kill(d));
                later(6, () -> sound(middle(0), Sound.BLOCK_DECORATED_POT_PLACE, 0.7f, 1f));
            }
        }
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
        Vector3f slide = new Vector3f((rng.nextFloat() - 0.5f) * 0.05f, 0.01f, (rng.nextFloat() - 0.5f) * 0.05f);
        animate(bowl, pose(BOWL_SCALE, wob, slide), 3);
        if (rng.nextInt(2) == 0) sound(middle(0), Sound.BLOCK_DECORATED_POT_HIT, 0.5f, 1.1f + rng.nextFloat() * 0.4f);
    }

    /** The result: the bowl is lifted and tipped, the die shows its face. */
    private void liftBowl(Table.Result r) {
        shaking = false;
        if (bowl == null || r.die() <= 0) return;
        kill(bowlDie);
        bowlDie = item(middle(DIE_HALF * 1.3), "economy:gamble_die", DIE_SCALE * 1.3f, TableArt.dieFace(r.die(), rng.nextFloat() * 360f), null, true);
        animate(bowl, pose(BOWL_SCALE, new Quaternionf(), null), 2);
        ItemDisplay b = bowl;
        Vector3f axis = new Vector3f(1, 0, 0);
        later(3, () -> {
            animate(b, pose(BOWL_SCALE, new Quaternionf().rotateAxis((float) Math.toRadians(35), axis.x, axis.y, axis.z),
                    new Vector3f(0, 0.55f, 0.18f)), 8);
            sound(middle(0), Sound.BLOCK_DECORATED_POT_PLACE, 0.8f, 1.4f);
        });
    }

    // ------------------------------------------------------------------------------------------ the turn timer

    private void drawTimer(Table t) {
        UUID turn = t.turn();
        Integer seat = turn == null ? null : host.seatOf(turn);
        if (seat == null) {
            kill(timer);
            timer = null;
            return;
        }
        long left = host.secondsLeft();
        int total = Math.max(1, host.spec().turnSeconds());
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
            List<Controls.Button> b = Controls.buttons(Controls.state(t, u, true, host.sittingOut(u)));
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
        for (Controls.Button x : buttons) b.append(x.key()).append(':').append(amount(t, u, x)).append(allIn(t, u, x) ? "!" : "").append(';');
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

    /** A button's words: its name, and on a betting button the chips it puts in (+120, or 올인 +80). */
    private Component label(Table t, UUID u, Controls.Button b) {
        long n = amount(t, u, b);
        String name = Lang.raw("hwatu.btn." + b.key() + (b == Controls.Button.CALL && n == 0 ? "-free" : ""), "n", HallTable.fmt(n));
        if (b.action() == null || n == 0) return Text.mm(name);
        String chips = Lang.raw(allIn(t, u, b) ? "hwatu.btn-allin" : "hwatu.btn-amount", "n", HallTable.fmt(n));
        return Lang.mm("hwatu.btn-label", "name", name, "amount", chips);
    }

    private Pad pad(Player p, int seat, Table t, List<Controls.Button> buttons) {
        Pad pad = new Pad();
        pad.key = key(t, p.getUniqueId(), buttons);
        List<TableArt.Slot> slots = TableArt.pad(buttons.size(), reach);
        double[] at = seat(seat);
        for (int i = 0; i < buttons.size(); i++) {
            Controls.Button b = buttons.get(i);
            TableArt.Slot slot = slots.get(i);
            float scale = (float) (BUTTON_SCALE * slot.scale());
            double half = BUTTON_HALF * slot.scale();
            TableArt.Spot s = TableArt.along(center, at, reach.button(), slot.side(), true);
            Location l = at(s, half + 0.03);
            Quaternionf rot = TableArt.facing(s.yaw()).rotateX((float) Math.toRadians(BUTTON_TILT));
            ItemDisplay d = item(l, b.model(), scale * 0.2f, rot, null, false);
            p.showEntity(plugin, d);
            int delay = 1 + i;
            later(delay, () -> animate(d, pose(scale, rot, null), 4));
            Interaction hit = world.spawn(at(s, 0.0), Interaction.class, x -> {
                x.setInteractionWidth((float) (TableArt.BUTTON_GAP * slot.scale()) - 0.03f);
                x.setInteractionHeight((float) (2 * half + 0.12));
                x.setResponsive(true);
                x.setVisibleByDefault(false);
                x.getPersistentDataContainer().set(BUTTON, PersistentDataType.STRING, p.getUniqueId() + ";" + b.key());
                tag(x);
            });
            p.showEntity(plugin, hit);
            TextDisplay label = text(at(s, 2 * half + 0.16), label(t, p.getUniqueId(), b), (float) Math.max(0.16, 0.2 * slot.scale()), false);
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
        if (r.game() == Table.Game.DICE) {
            landDice(r);
            wait = 8;
        } else if (r.game() == Table.Game.HOLJJAK) {
            liftBowl(r);
            wait = 12;
        } else if (!r.uncontested()) wait = 14;   // the 섯다 cards flip first (sync after this)
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
                        CHIPS_SCALE, new Quaternionf(), offsetTo(center, s.x(), s.z(), 0.06), true);
                later(1, () -> animate(chips, pose(CHIPS_SCALE, new Quaternionf(), null), 8));
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
