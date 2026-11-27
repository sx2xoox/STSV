package dev.stsv.economy.store;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The worker's actions shown in the world (기획안 §17): the furniture's model states (the empty hanger, the microwave's door
 * and light, the kettle's water, the 발주 tablet's pages), the shelf filling from its open box, the mop's strokes and the wet
 * sign, the oven's food turning inside, the 폐기 on the shelf with its red outline, the till's things sliding into the bag.
 * Every extra display is a shift's (non-persistent, tagged {@link StoreModule#GUEST}, gone at clock-out, a chunk load, a
 * restart) and there are at most {@link #CAP} of them at once over all stores; past that the action happens without its
 * show. The motion is the client's (interpolated transformations and teleports), never a teleport every tick.
 */
final class StoreShow {

    /** Short-lived displays alive at once (all stores). */
    static final int CAP = 40;
    /** The furniture whose model shows a state (role -> what decides it). */
    static final List<String> STATEFUL = List.of("hanger", "microwave", "kettle", "order");

    private final StoreModule m;
    private final List<Entity> all = new ArrayList<>();

    StoreShow(StoreModule m) {
        this.m = m;
    }

    // ------------------------------------------------------------------------------------------ the pieces' models

    /** The piece's own model (as built), null when this store has no such piece. */
    String base(StoreModule.Shop shop, String role) {
        StoreLayout.Piece p = shop.layout().piece(role);
        return p == null ? null : p.item();
    }

    /** A piece shows this model (the pack lacks it: its own); nothing done when it already does. */
    void furnish(StoreModule.Shop shop, String role, String item) {
        String want = item != null && m.has(item) ? item : base(shop, role);
        if (want == null) return;
        String key = "role:" + role;
        if (want.equals(shop.shown.get(key))) return;
        ItemDisplay d = piece(shop, role);
        if (d == null) return;
        d.setItemStack(StoreBuilder.shown(m.core(), want));
        shop.shown.put(key, want);
    }

    /** The piece's display (its uuid kept; looked for again when gone). */
    ItemDisplay piece(StoreModule.Shop shop, String role) {
        UUID u = shop.pieces.get(role);
        if (u != null && Bukkit.getEntity(u) instanceof ItemDisplay d && d.isValid()) return d;
        World w = shop.world();
        if (w == null) return null;
        shop.pieces.clear();
        shop.pieces.putAll(StoreBuilder.pieces(w, shop.name, shop.site));
        u = shop.pieces.get(role);
        return u != null && Bukkit.getEntity(u) instanceof ItemDisplay d ? d : null;
    }

    /** What a stateful piece should show now. */
    String wanted(StoreModule.Shop shop, String role) {
        return switch (role) {
            case "hanger" -> shop.shift != null ? StoreMotion.HANGER_EMPTY : null;
            case "microwave" -> StoreMotion.ovenModel(shop.chores.oven(), false);
            case "kettle" -> {
                int cap = m.rules().mess().water();
                yield StoreMotion.kettleModel(StoreMotion.level(shop.chores.water(cap), cap));
            }
            case "order" -> StoreMotion.tabletModel(null, m.orderedTomorrow(shop));
            default -> null;
        };
    }

    /** Every stateful piece and shelf of a loaded store shows its state (a start, a chunk loaded, a shift begins or ends). */
    void restyle(StoreModule.Shop shop) {
        if (!shop.loaded()) return;
        for (String role : STATEFUL) {
            shop.shown.remove("role:" + role);
            furnish(shop, role, wanted(shop, role));
        }
        for (String place : m.spec().shelves().keySet()) {
            shop.shown.remove(place);
            m.refreshShelf(shop, place);
        }
    }

    void kettle(StoreModule.Shop shop) {
        furnish(shop, "kettle", wanted(shop, "kettle"));
    }

    // ------------------------------------------------------------------------------------------ short-lived displays

    /** A display for a while (`life` ticks; 0: until the shift takes it away), or null past the cap. */
    ItemDisplay temp(StoreModule.Shift sh, Location at, String item, float scale, int life) {
        all.removeIf(e -> !e.isValid());
        sh.fx.removeIf(e -> !e.isValid());
        if (all.size() >= CAP || at.getWorld() == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return null;
        ItemDisplay d = m.display(at, item, scale);
        sh.fx.add(d);
        all.add(d);
        if (life > 0) later(life, d::remove);
        return d;
    }

    void later(long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(m.eco(), r, Math.max(1, ticks));
    }

    /** The display moves (its translation, in world axes) and scales, smoothly over `ticks`. */
    static void move(Display d, int ticks, Vector3f translation, float scale) {
        if (d == null || !d.isValid()) return;
        Transformation t = d.getTransformation();
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(Math.max(0, ticks));
        d.setTransformation(new Transformation(translation, t.getLeftRotation(), new Vector3f(scale, scale, scale), t.getRightRotation()));
    }

    /** Where a world point is in the store's own frame (right, forward). */
    static double[] local(StoreLayout.Site site, double x, double z) {
        double[] o = site.at(0, 0, 0), r = site.at(1, 0, 0), f = site.at(0, 0, 1);
        double dx = x - o[0], dz = z - o[2];
        return new double[]{dx * (r[0] - o[0]) + dz * (r[2] - o[2]), dx * (f[0] - o[0]) + dz * (f[2] - o[2])};
    }

    private static Vector3f between(Location from, Location to) {
        return new Vector3f((float) (from.getX() - to.getX()), (float) (from.getY() - to.getY()), (float) (from.getZ() - to.getZ()));
    }

    /** Clock-out: every short-lived display of the shift away. */
    void clear(StoreModule.Shift sh) {
        for (Entity e : sh.fx) if (e.isValid()) e.remove();
        sh.fx.clear();
        for (Entity e : sh.wasteItems) if (e.isValid()) e.remove();
        sh.wasteItems.clear();
        if (sh.ovenFood != null) sh.ovenFood.remove();
        sh.ovenFood = null;
        sh.filling.clear();
    }

    // ------------------------------------------------------------------------------------------ 진열: the box opened, the shelf fills

    /**
     * The carried box is opened on the floor by the shelf, its things hop onto the shelf one by one (the shelf's model
     * empty → half → full), then the box folds away (about two seconds). The stock is already full (the chores did it).
     */
    void fill(StoreModule.Shift sh, Player p, String place, int from) {
        StoreSpec.Shelf shelf = m.spec().shelves().get(place);
        StoreModule.Shop shop = sh.shop;
        if (shelf == null || sh.filling.contains(place)) {
            m.refreshShelf(shop, place);
            return;
        }
        double[] me = local(shop.site, p.getLocation().getX(), p.getLocation().getZ());
        List<Location> slots = new ArrayList<>();
        Location boxAt;
        if (place.equals("fridge")) {
            double cx = me[0] < 1 ? 0 : me[0] < 3 ? 2 : 4;
            boxAt = shop.at(cx, 0, 13.2, 0);
            double[] ys = {0.59, 1.0, 1.4};
            for (int i = 0; i < StoreMotion.POPS; i++) slots.add(shop.at(cx + (i % 2 == 0 ? -0.45 : 0.45), ys[Math.min(2, i / 2)], 14.3, 0));
        } else {
            StoreLayout.Piece pc = shop.layout().piece("shelf_" + place);
            if (pc == null) {
                m.refreshShelf(shop, place);
                return;
            }
            boolean west = me[0] < pc.x0() + 0.5;
            double mid = (pc.z0() + pc.z1() + 1) / 2.0;
            float yaw = west ? 270 : 90;
            boxAt = shop.at(west ? pc.x0() - 0.45 : pc.x0() + 1.45, 0, mid, yaw);
            double[] ys = {0.625, 1.0625, 1.5};
            for (int i = 0; i < StoreMotion.POPS; i++) slots.add(shop.at(west ? pc.x0() + 0.22 : pc.x0() + 0.78, ys[Math.min(2, i / 2)],
                    mid + (i % 2 == 0 ? -0.55 : 0.55), yaw));
        }
        List<String> goods = new ArrayList<>();
        for (StoreSpec.Product pr : m.spec().products().values()) if (pr.place().equals(place) && shop.sells(pr.id())) goods.add(pr.item());
        List<String> items = StoreMotion.popItems(goods, StoreMotion.POPS);
        List<String> states = StoreMotion.fillStates(shelf, from, StoreMotion.POPS);
        String box = shelf.box();
        sh.filling.add(place);
        m.showShelf(shop, place, shelf.state(from));
        ItemDisplay open = temp(sh, boxAt, m.has(StoreMotion.openBox(box)) ? StoreMotion.openBox(box) : "economy:store_box_" + box, 0.9f, 0);
        World w = boxAt.getWorld();
        w.playSound(boxAt, Sound.BLOCK_BARREL_OPEN, 0.5f, 1.4f);
        List<ItemDisplay> popped = new ArrayList<>();
        for (int i = 0; i < StoreMotion.POPS; i++) {
            int k = i;
            later(6 + i * 5L, () -> {
                if (shop.shift != sh) return;
                Location slot = slots.get(k);
                if (k < items.size()) {
                    ItemDisplay d = temp(sh, slot, items.get(k), 0.42f, 0);
                    if (d != null) {
                        popped.add(d);
                        Vector3f from0 = between(boxAt.clone().add(0, 0.35, 0), slot);
                        Transformation t = d.getTransformation();
                        d.setTransformation(new Transformation(from0, t.getLeftRotation(), new Vector3f(0.2f, 0.2f, 0.2f), t.getRightRotation()));
                        later(1, () -> move(d, 3, new Vector3f(from0).mul(0.5f).add(0, 0.4f, 0), 0.42f));
                        later(4, () -> move(d, 3, new Vector3f(), 0.42f));
                    }
                }
                w.playSound(slot, Sound.BLOCK_CHISELED_BOOKSHELF_INSERT, 0.35f, 1.3f + 0.05f * k);
                if (k < states.size()) m.showShelf(shop, place, states.get(k));
            });
        }
        long end = 6 + StoreMotion.POPS * 5L + 6;
        later(end, () -> {
            for (ItemDisplay d : popped) move(d, 3, new Vector3f(), 0.0f);
            if (open != null && open.isValid()) {
                Transformation t = open.getTransformation();
                open.setInterpolationDelay(0);
                open.setInterpolationDuration(4);
                open.setTransformation(new Transformation(t.getTranslation(), t.getLeftRotation(), new Vector3f(0.9f, 0.05f, 0.9f), t.getRightRotation()));
                w.playSound(boxAt, Sound.BLOCK_BARREL_CLOSE, 0.45f, 1.5f);
            }
        });
        later(end + 5, () -> {
            for (ItemDisplay d : popped) d.remove();
            if (open != null) open.remove();
            sh.filling.remove(place);
            if (shop.shift == sh) {
                shop.shown.remove(place);
                m.refreshShelf(shop, place);
            }
        });
    }

    // ------------------------------------------------------------------------------------------ 대걸레

    /** Where a spill lies. */
    Location spillAt(StoreModule.Shift sh, String node) {
        StoreLayout.Node n = StoreLayout.of().nodes().get(node);
        return n == null ? null : sh.shop.at(n.x(), 0.01, n.z(), 0);
    }

    /** A stroke of the mop: the arm, the splash, the spill a size smaller (or gone, the wet sign a few seconds more). */
    void stroke(StoreModule.Shift sh, Player p, String node, int after) {
        Location at = spillAt(sh, node);
        if (at == null) return;
        p.swingMainHand();
        World w = at.getWorld();
        w.spawnParticle(Particle.SPLASH, at.clone().add(0, 0.1, 0), 18, 0.35, 0.05, 0.35, 0.05);
        w.playSound(at, Sound.BLOCK_WET_SPONGE_HIT, 0.7f, 1.2f);
        w.playSound(at, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.6f, 0.9f);
        if (!sh.messes.containsKey("sign:" + node) && m.has(StoreMotion.WET_SIGN)) {   // 미끄럼 주의, while it is wet
            ItemDisplay sign = m.display(sh.shop.at(StoreLayout.of().nodes().get(node).x() + 0.75, 0, StoreLayout.of().nodes().get(node).z() - 0.5, 180),
                    StoreMotion.WET_SIGN, 1f);
            sh.messes.put("sign:" + node, sign);
            w.playSound(at, Sound.BLOCK_SCAFFOLDING_PLACE, 0.6f, 1.3f);
        }
        Entity e = sh.messes.get("spill:" + node);
        if (after <= 0) {
            sh.messes.remove("spill:" + node);
            if (e instanceof ItemDisplay d) {
                move(d, 4, new Vector3f(), 0f);
                later(5, d::remove);
            } else if (e != null) e.remove();
            later(80, () -> {
                Entity s = sh.messes.remove("sign:" + node);
                if (s != null) s.remove();
            });
            return;
        }
        if (e instanceof ItemDisplay d) spillStage(d, after);
    }

    /** A spill display at a stage: the smaller model, else the first one drawn smaller. */
    void spillStage(ItemDisplay d, int stage) {
        String model = StoreMotion.spillModel(stage);
        if (model != null && stage > 1 && m.has(model)) {
            d.setItemStack(StoreBuilder.shown(m.core(), model));
            move(d, 0, new Vector3f(), 1f);
        } else move(d, 3, new Vector3f(), StoreMotion.spillScale(stage));
    }

    // ------------------------------------------------------------------------------------------ 온수기

    /** The jug poured into the kettle in steps (the arm, the water, the level rising), then the jug goes back. */
    void pour(StoreModule.Shift sh, Player p, int from, int cap) {
        StoreModule.Shop shop = sh.shop;
        List<Integer> steps = StoreMotion.refillSteps(from, cap, 3);
        Location top = shop.at(-2.5, 1.85, 5.5, 0);
        sh.pouring = true;
        for (int i = 0; i < steps.size(); i++) {
            int k = i;
            later(4 + i * 8L, () -> {
                if (shop.shift != sh) return;
                if (p.isOnline()) p.swingMainHand();
                top.getWorld().spawnParticle(Particle.FALLING_WATER, top, 14, 0.12, 0.05, 0.12, 0);
                top.getWorld().spawnParticle(Particle.SPLASH, top, 6, 0.15, 0.02, 0.15, 0.02);
                top.getWorld().playSound(top, Sound.ITEM_BUCKET_EMPTY, 0.5f, 1.0f + 0.15f * k);
                int cups = steps.get(k);
                furnish(shop, "kettle", StoreMotion.kettleModel(StoreMotion.level(cups, cap)));
            });
        }
        later(4 + steps.size() * 8L, () -> {
            sh.pouring = false;
            kettle(shop);
            if (m.hands().holds(p, StoreMotion.JUG)) m.handsBack(p);
        });
    }

    // ------------------------------------------------------------------------------------------ 전자레인지

    private Location ovenInside(StoreModule.Shop shop) {
        return shop.at(StoreLayout.OVEN_X, StoreLayout.OVEN_Y, StoreLayout.OVEN_Z, 270);   // (its door is the worker's)
    }

    /** Whoever the food was for has gone: the food away, the door shut. */
    void ovenAbort(StoreModule.Shift sh) {
        if (sh.ovenFood != null) sh.ovenFood.remove();
        sh.ovenFood = null;
        furnish(sh.shop, "microwave", StoreMotion.ovenModel(Chores.Oven.IDLE, false));
    }

    /** Food in: the door open, the food inside, the door shut and the light on. */
    void ovenIn(StoreModule.Shift sh, String food) {
        StoreModule.Shop shop = sh.shop;
        furnish(shop, "microwave", StoreMotion.ovenModel(Chores.Oven.IDLE, true));
        if (sh.ovenFood != null) sh.ovenFood.remove();
        sh.ovenFood = food == null ? null : temp(sh, ovenInside(shop), food, 0.3f, 0);
        later(8, () -> {
            if (shop.shift == sh && shop.chores.oven() == Chores.Oven.WARMING) furnish(shop, "microwave", StoreMotion.ovenModel(Chores.Oven.WARMING, false));
        });
    }

    /** Every second it runs: the food turns a third round (smoothly, the client's). */
    void ovenSpin(StoreModule.Shift sh, long ticks) {
        ItemDisplay d = sh.ovenFood;
        if (d == null || !d.isValid()) return;
        Transformation t = d.getTransformation();
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(20);
        d.setTransformation(new Transformation(t.getTranslation(), new Quaternionf(t.getLeftRotation()).rotateY((float) (Math.PI * 2 / 3)), t.getScale(),
                t.getRightRotation()));
    }

    /** 띵: the done model, steam. */
    void ding(StoreModule.Shift sh) {
        furnish(sh.shop, "microwave", StoreMotion.ovenModel(Chores.Oven.DONE, false));
        Location at = ovenInside(sh.shop).add(0, 0.35, 0);
        at.getWorld().spawnParticle(Particle.CLOUD, at, 6, 0.15, 0.1, 0.15, 0.01);
    }

    /** While it waits done: a little steam now and then. */
    void steam(StoreModule.Shift sh) {
        Location at = ovenInside(sh.shop).add(0, 0.4, 0);
        at.getWorld().spawnParticle(Particle.WHITE_SMOKE, at, 2, 0.1, 0.05, 0.1, 0.005);
    }

    /** Food out: the door open, the food hops out toward the counter, the door shut. */
    void ovenOut(StoreModule.Shift sh) {
        StoreModule.Shop shop = sh.shop;
        furnish(shop, "microwave", StoreMotion.ovenModel(Chores.Oven.IDLE, true));
        ItemDisplay d = sh.ovenFood;
        sh.ovenFood = null;
        if (d != null && d.isValid()) {
            Location to = shop.at(-2.6, StoreLayout.TOP + 0.1, 8.75, 90);
            Vector3f by = between(to, d.getLocation());
            move(d, 5, by.add(0, 0.15f, 0), 0.4f);
            later(8, d::remove);
        }
        later(12, () -> {
            if (shop.chores.oven() == Chores.Oven.IDLE) furnish(shop, "microwave", StoreMotion.ovenModel(Chores.Oven.IDLE, false));
        });
    }

    /** Burnt: smoke, the food gone, the door shut. */
    void burnt(StoreModule.Shift sh) {
        Location at = ovenInside(sh.shop).add(0, 0.3, 0);
        at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at, 10, 0.2, 0.2, 0.2, 0.01);
        at.getWorld().spawnParticle(Particle.SMOKE, at, 16, 0.25, 0.25, 0.25, 0.02);
        at.getWorld().playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.2f);
        if (sh.ovenFood != null) sh.ovenFood.remove();
        sh.ovenFood = null;
        furnish(sh.shop, "microwave", StoreMotion.ovenModel(Chores.Oven.IDLE, false));
    }

    // ------------------------------------------------------------------------------------------ 폐기

    /** The expired things on the 폐기 shelf, outlined red (as many as there are). */
    void waste(StoreModule.Shift sh, String place, int n) {
        sh.wasteItems.removeIf(e -> !e.isValid());
        while (sh.wasteItems.size() > n) sh.wasteItems.removeLast().remove();
        StoreLayout.Piece pc = sh.shop.layout().piece("shelf_" + place);
        if (pc == null || sh.wasteItems.size() >= n) return;
        List<String> goods = new ArrayList<>();
        for (StoreSpec.Product pr : m.spec().products().values()) if (pr.place().equals(place) && sh.shop.sells(pr.id())) goods.add(pr.item());
        if (goods.isEmpty()) return;
        double mid = (pc.z0() + pc.z1() + 1) / 2.0;
        while (sh.wasteItems.size() < n) {
            int i = sh.wasteItems.size();
            Location at = sh.shop.at(pc.x0() + 0.2, 1.0625, mid - 0.6 + 0.6 * (i % 3), 270);
            ItemDisplay d = m.display(at, goods.get(i % goods.size()), 0.42f);
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(0xE02020));
            sh.wasteItems.add(d);
        }
    }

    /** The red ones hop off the shelf into the worker's bag. */
    void takeWaste(StoreModule.Shift sh, Player p) {
        Location to = p.getLocation().add(0, 1.0, 0);
        for (ItemDisplay d : sh.wasteItems) {
            if (!d.isValid()) continue;
            Vector3f by = between(to, d.getLocation());
            sh.fx.add(d);
            move(d, 5, by.mul(0.6f).add(0, 0.3f, 0), 0.1f);
            later(6, d::remove);
        }
        sh.wasteItems.clear();
    }

    /** Something dropped into the 폐기 바구니 with a thud (the bag, the tray's cups). */
    void dump(StoreModule.Shift sh, String what) {
        Location at = sh.shop.at(-2.5, 1.3, 10.5, 270);
        ItemDisplay d = what == null ? null : temp(sh, at, what, 0.5f, 10);
        if (d != null) later(1, () -> move(d, 6, new Vector3f(0, -0.85f, 0), 0.35f));
        later(6, () -> {
            at.getWorld().playSound(at, Sound.BLOCK_MUD_FALL, 0.9f, 0.8f);
            at.getWorld().playSound(at, Sound.BLOCK_COMPOSTER_FILL, 0.6f, 0.9f);
        });
    }

    /** A cup hops off the eat bar onto the worker's tray. */
    void cupOff(Entity cup, Player p) {
        if (!(cup instanceof ItemDisplay d) || !d.isValid()) {
            if (cup != null) cup.remove();
            return;
        }
        Vector3f by = between(p.getLocation().add(0, 1.1, 0), d.getLocation());
        move(d, 4, by.mul(0.5f).add(0, 0.3f, 0), 0.2f);
        later(5, d::remove);
    }

    // ------------------------------------------------------------------------------------------ the till

    /** Paid: the scanned things slide into a bag on the counter, then the customer holds the bag. */
    void bag(StoreModule.Shift sh, StoreModule.Till t, StoreModule.Guest g, Runnable handOver) {
        List<ItemDisplay> things = new ArrayList<>(t.items);
        t.items.clear();
        Location at = sh.shop.at(-2.72, StoreLayout.TOP, 8.95, 90);
        ItemDisplay bag = temp(sh, at, "economy:store_bag", 0.8f, 14);
        for (ItemDisplay d : things) {
            if (!d.isValid()) continue;
            sh.fx.add(d);
            if (bag == null) {
                d.remove();
                continue;
            }
            Location over = at.clone().add(0, 0.3, 0);
            over.setYaw(0);
            d.teleport(over);
            later(5, () -> move(d, 4, new Vector3f(0, -0.25f, 0), 0.05f));
            later(10, d::remove);
        }
        if (bag != null) later(6, () -> at.getWorld().playSound(at, Sound.ITEM_BUNDLE_INSERT, 0.6f, 1.1f));
        later(bag == null ? 1 : 11, handOver);
    }

    // ------------------------------------------------------------------------------------------ the receipt

    /** A strip standing in the printer's slot: what becomes of it, when it began to print, what it is when taken (or null). */
    static final class Strip {
        final ItemDisplay d;
        final Receipt.Fate fate;
        final long began;
        final ItemStack prize;
        boolean done;

        Strip(ItemDisplay d, Receipt.Fate fate, long began, ItemStack prize) {
            this.d = d;
            this.fate = fate;
            this.began = began;
            this.prize = prize;
        }
    }

    private void lengthen(ItemDisplay d, int ticks, float length, Vector3f translation) {
        if (d == null || !d.isValid()) return;
        Transformation t = d.getTransformation();
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(Math.max(0, ticks));
        d.setTransformation(new Transformation(translation, t.getLeftRotation(), new Vector3f(1, length, 1), t.getRightRotation()));
    }

    /**
     * A sale's receipt ({@link Receipt}): the printer buzzes and the strip feeds out of its slot, is torn, and slides after
     * the customer; a strip that stays (declined, or kept for the worker: `item` its model) is the shift's {@code receipt}
     * until it is clicked or draws back. Nothing when the pack has no such model, the cap is reached, or a strip still waits.
     */
    Strip receipt(StoreModule.Shift sh, StoreModule.Guest g, Receipt.Fate fate, String item, ItemStack prize) {
        if (!Receipt.prints(m.has(item), sh.receipt != null && !sh.receipt.done)) return null;
        Location at = sh.shop.at(Receipt.X, Receipt.Y, Receipt.Z, Receipt.YAW);
        all.removeIf(e -> !e.isValid());
        if (all.size() >= CAP || at.getWorld() == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return null;
        ItemDisplay d = m.tipped(at, item, 1f, Receipt.TILT);
        sh.fx.add(d);
        all.add(d);
        Transformation t0 = d.getTransformation();
        d.setTransformation(new Transformation(new Vector3f(), t0.getLeftRotation(), new Vector3f(1, Receipt.SLIVER, 1), t0.getRightRotation()));
        Strip strip = new Strip(d, fate, m.tick(), prize);
        later(1, () -> lengthen(d, Receipt.FEED, 1f, new Vector3f()));   // one interpolation feeds it out
        for (int b : Receipt.buzz()) later(1 + b, () -> {
            if (d.isValid()) at.getWorld().playSound(at, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.12f, 2.0f);
        });
        later(Receipt.TEAR, () -> {
            if (d.isValid()) at.getWorld().playSound(at, Sound.ITEM_BOOK_PAGE_TURN, 0.45f, 1.7f);
        });
        if (fate == Receipt.Fate.BAG) {
            later(Receipt.AWAY, () -> {
                if (!d.isValid()) return;
                // after the customer (their bag is in their hand by now); nobody there: to where the bag stood
                Location to = g != null && g.m.isValid() ? g.m.getLocation().add(0, 0.9, 0) : sh.shop.at(-2.72, StoreLayout.TOP + 0.2, 8.95, 90);
                Vector3f by = new Vector3f((float) (to.getX() - at.getX()), (float) (to.getY() - at.getY()), (float) (to.getZ() - at.getZ()));
                if (by.length() > 4f) by.normalize(4f);
                lengthen(d, Receipt.GONE - Receipt.AWAY, 0.25f, by);
            });
            later(Receipt.GONE, () -> {
                strip.done = true;
                d.remove();
            });
            return strip;
        }
        sh.receipt = strip;
        if (fate == Receipt.Fate.DECLINED) {
            later(Receipt.LEFT, () -> {
                if (strip.done || !d.isValid()) return;
                strip.done = true;
                lengthen(d, Receipt.RETRACT, Receipt.SLIVER, new Vector3f());   // back into the slot
                later(Receipt.RETRACT, d::remove);
            });
        }
        return strip;
    }

    /** The worker's click on a strip that waits: into the bin under the counter (or, a kept one, into their hand). */
    void takeReceipt(StoreModule.Shift sh, Player p) {
        Strip s = sh.receipt;
        if (s == null || s.done) return;
        s.done = true;
        sh.receipt = null;
        Location at = s.d.getLocation();
        if (s.prize != null) {
            p.playSound(at, Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 1.3f);
            if (s.d.isValid()) s.d.remove();
            return;
        }
        at.getWorld().playSound(at, Sound.ITEM_BUNDLE_DROP_CONTENTS, 0.4f, 1.4f);
        lengthen(s.d, 5, 0.3f, new Vector3f(0, -0.7f, 0));
        later(5, s.d::remove);
    }

    // ------------------------------------------------------------------------------------------ 신분증

    /** The customer holds out their ID card (in their hand). */
    void idOut(StoreModule.Guest g) {
        if (!m.has(StoreMotion.ID_CARD)) return;
        EntityEquipment eq = g.m.getEquipment();
        if (eq == null) return;
        ItemStack it = StoreBuilder.shown(m.core(), StoreMotion.ID_CARD);
        it.editMeta(x -> x.getPersistentDataContainer().set(StoreModule.GUEST, PersistentDataType.BYTE, (byte) 1));
        eq.setItemInMainHand(it);
    }

    /** The card off the customer's hand onto the counter (null: the pack has no card). */
    ItemDisplay idOnCounter(StoreModule.Shift sh, StoreModule.Guest g) {
        EntityEquipment eq = g.m.getEquipment();
        if (eq != null && eq.getItemInMainHand().getPersistentDataContainer().has(StoreModule.GUEST)) eq.setItemInMainHand(null);
        if (!m.has(StoreMotion.ID_CARD)) return null;
        // on the worker's edge of the counter, its far side raised to them
        return m.tipped(sh.shop.at(StoreLayout.ID_X, StoreLayout.TOP + 0.03, StoreLayout.ID_Z, 270), StoreMotion.ID_CARD, 1f, StoreLayout.ID_TILT);
    }

    /**
     * The photo on the ID: a face (a head pressed flat, in the customer's own skin; on a sibling's card another person's),
     * beside the card's writing. The worker compares it with who stands there. Null when it cannot be shown.
     */
    ItemDisplay idPhoto(StoreModule.Shift sh, StoreModule.Guest g, boolean sibling) {
        String skin = sibling ? otherFace(g) : g.skin;
        if (skin == null) return null;
        try {
            boolean slim = skin.endsWith(" slim");
            String key = slim ? skin.substring(0, skin.length() - 5).trim() : skin.trim();
            ItemStack head = new ItemStack(org.bukkit.Material.PLAYER_HEAD);
            head.setData(io.papermc.paper.datacomponent.DataComponentTypes.PROFILE, io.papermc.paper.datacomponent.item.ResolvableProfile
                    .resolvableProfile().name("id_photo").skinPatch(x -> x.body(net.kyori.adventure.key.Key.key(key))).build());
            Location at = sh.shop.at(StoreLayout.ID_X, StoreLayout.TOP + 0.065, StoreLayout.ID_Z - 0.12, 270);   // (over the card's printed photo)
            float yaw = at.getYaw();
            at.setYaw(0);
            return at.getWorld().spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(head);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotationY((float) Math.toRadians(-yaw))
                        .rotateX((float) Math.toRadians(-(90 - StoreLayout.ID_TILT))), new Vector3f(0.2f, 0.2f, 0.02f), new Quaternionf()));
                d.setViewRange(0.25f);
                d.getPersistentDataContainer().set(StoreModule.GUEST, PersistentDataType.BYTE, (byte) 1);
            });
        } catch (RuntimeException | LinkageError e) {   // (a server without the profile component on heads: no photo)
            return null;
        }
    }

    /** Somebody else's face for a sibling's card: another look of their kind, else of an older kind. */
    private String otherFace(StoreModule.Guest g) {
        for (String look : g.type.looks()) if (!look.equals(g.skin)) return look;
        dev.stsv.economy.bus.BusModule bus = m.eco().bus();
        if (bus != null && bus.spec() != null) for (String kind : List.of("office", "resident", "uncle")) {
            dev.stsv.economy.bus.BusSpec.Type t = bus.spec().types().get(kind);
            if (t != null && !t.id().equals(g.type.id()) && !t.looks().isEmpty()) return t.looks().getFirst();
        }
        return null;
    }
}
