package dev.stsv.core.vehicle;

import dev.stsv.core.furniture.DisplayView;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The towed units of a shown articulated vehicle ({@link TrainSpec}) and the moving parts of its driven unit: every
 * trailer's body, wheels, load and click boxes, the draft animal's legs and head, spinning parts (a flywheel, a tractor's
 * big rear wheels). {@link VehicleModule} draws the driven unit itself (body, front wheels, seats) as for any vehicle and
 * asks this for the rest: moving it every tick ({@link TrainPhysics}), where the seats on the trailers are, which unit
 * was clicked, each trailer's load. Like every vehicle entity none of these is ever saved with a chunk. Main thread only.
 */
final class TrainView {

    final TrainSpec spec;
    /** the towed units, front to back */
    final List<VehiclePhysics.State> units;
    final Gait gait = new Gait();

    private final List<ItemDisplay> bodies = new ArrayList<>(), loads = new ArrayList<>();
    private final List<List<ItemDisplay>> wheels = new ArrayList<>();
    private final List<List<Interaction>> hits = new ArrayList<>();
    private final List<NamespacedKey> loadShown = new ArrayList<>();
    private final List<ItemDisplay> legs = new ArrayList<>(), parts = new ArrayList<>();
    private final double[] spin;
    /** per towed unit: the brightness its parts are drawn with (VehicleLight); the animal and parts go with the driven unit's */
    private final List<VehicleLight.Shown> unitLight = new ArrayList<>();
    private ItemDisplay head;
    /** click box -> unit (1 = the first trailer) */
    private final Map<UUID, Integer> unitOf = new HashMap<>();
    /** blocks walked since the last hoof step */
    private double walked;
    /** the gait or a part changed since last drawn */
    private boolean animating = true;

    TrainView(TrainSpec spec, List<VehiclePhysics.State> units) {
        this.spec = spec;
        this.units = units;
        this.spin = new double[spec.parts().size()];
    }

    // ------------------------------------------------------------------------------------------
    // Entities
    // ------------------------------------------------------------------------------------------

    /** Spawns every entity of the train (the driven unit's own parts are the module's). */
    void spawn(LiveVehicle v, VehicleModule.Settings s, ItemStack[] cargo) {
        WorldLight world = new WorldLight(v.world);
        for (int i = 0; i < units.size(); i++) {
            TrainSpec.Trailer tr = spec.trailers().get(i);
            VehiclePhysics.State st = units.get(i);
            VehicleSpec u = tr.unit();
            VehicleLight.Shown l = unitLight(i);
            l.jump(VehicleLight.sample(st.x, st.y, st.z, st.yaw, VehicleLight.probes(u), world));
            bodies.add(display(v, st, s, model(tr.model()), scaled(tr.bodyScale()), u, l));
            List<ItemDisplay> ws = new ArrayList<>();
            for (int w = 0; w < u.wheels().size(); w++) ws.add(display(v, st, s, model(u.wheelModel()), wheel(u, w, st.roll), u, l));
            wheels.add(ws);
            NamespacedKey stage = spec.stage(cargo, i + 1);
            loadShown.add(stage);
            loads.add(tr.cargo() == null ? null : display(v, st, s, model(stage), part(tr.cargo().at()), u, l));
            List<Interaction> hs = new ArrayList<>();
            for (int q = 0; q < u.squares(); q++) {
                double[] c = VehiclePhysics.world(st.x, st.y, st.z, st.yaw, new VehicleSpec.Vec(0, 0, u.squareOffset(q)));
                Interaction h = v.world.spawn(new Location(v.world, c[0], c[1], c[2]), Interaction.class, e -> {
                    e.setPersistent(false);
                    e.setInteractionWidth((float) u.width());
                    e.setInteractionHeight((float) u.clickHeight());
                    e.setResponsive(true);
                    e.getPersistentDataContainer().set(VehicleModule.PART, PersistentDataType.LONG, v.id);
                });
                unitOf.put(h.getUniqueId(), i + 1);
                hs.add(h);
            }
            hits.add(hs);
        }
        TrainSpec.Animal a = spec.animal();
        if (a != null) {
            for (TrainSpec.Leg l : a.legs()) legs.add(display(v, v.st, s, model(l.model()), leg(l, 0), v.spec, v.light));
            if (a.head() != null) head = display(v, v.st, s, model(a.head()), headPose(0), v.spec, v.light);
        }
        for (int i = 0; i < spec.parts().size(); i++) parts.add(display(v, v.st, s, model(spec.parts().get(i).model()), spun(i), v.spec, v.light));
        animating = true;
        for (Map.Entry<Integer, Integer> h : hidden.entrySet()) shown(h.getKey(), h.getValue(), cargo);
    }

    // ------------------------------------------------------------------------------------------
    // 작업기 (VehicleTools): a tool in a trailer's place
    // ------------------------------------------------------------------------------------------

    /** Hidden parts of towed units (unit 1.. -> bits: 1 body, 2 wheels, 4 load): a tool took their place. */
    private final Map<Integer, Integer> hidden = new HashMap<>();

    static final int BODY = 1, WHEELS = 2, LOAD = 4;

    /** Hides these parts of a towed unit (0 = shows them all again), now and whenever it is drawn again. */
    void hide(int unit, int parts, ItemStack[] cargo) {
        if (unit < 1) return;
        int was = hidden.getOrDefault(unit, 0);
        if (parts == 0) hidden.remove(unit);
        else hidden.put(unit, parts);
        if (was != parts) shown(unit, parts, cargo);
    }

    private void shown(int unit, int parts, ItemStack[] cargo) {
        int i = unit - 1;
        if (i < 0 || i >= bodies.size()) return;
        TrainSpec.Trailer tr = spec.trailers().get(i);
        bodies.get(i).setItemStack((parts & BODY) != 0 ? ItemStack.empty() : model(tr.model()));
        for (ItemDisplay w : wheels.get(i)) w.setItemStack((parts & WHEELS) != 0 ? ItemStack.empty() : model(tr.unit().wheelModel()));
        if (loads.get(i) != null) {
            NamespacedKey stage = (parts & LOAD) != 0 ? null : spec.stage(cargo, unit);
            loadShown.set(i, stage);
            loads.get(i).setItemStack(model(stage));
        }
    }

    /** Every entity of the train. */
    List<Entity> entities() {
        List<Entity> out = new ArrayList<>(bodies);
        for (ItemDisplay l : loads) if (l != null) out.add(l);
        wheels.forEach(out::addAll);
        hits.forEach(out::addAll);
        out.addAll(legs);
        out.addAll(parts);
        if (head != null) out.add(head);
        return out;
    }

    /** Whether anything was taken apart (then the module spawns it all again). */
    boolean broken() {
        for (Entity e : entities()) if (!e.isValid()) return true;
        return false;
    }

    void remove() {
        entities().forEach(Entity::remove);
        bodies.clear();
        loads.clear();
        loadShown.clear();
        wheels.clear();
        hits.clear();
        legs.clear();
        parts.clear();
        head = null;
        unitOf.clear();
    }

    private ItemDisplay display(LiveVehicle v, VehiclePhysics.State st, VehicleModule.Settings s, ItemStack shown, Transformation t, VehicleSpec u,
                                VehicleLight.Shown light) {
        return v.world.spawn(at(v.world, st, s), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(t);
            d.setTeleportDuration(s.smoothing());
            d.setInterpolationDuration(s.smoothing());
            d.setBrightness(VehicleModule.brightness(light));
            DisplayView.apply(d, s.viewRange(), new float[]{(float) (Math.hypot(u.length(), u.width()) + 1), (float) (u.height() + 1)}, s.culling());
            d.getPersistentDataContainer().set(VehicleModule.PART, PersistentDataType.LONG, v.id);
        });
    }

    private static Location at(World w, VehiclePhysics.State st, VehicleModule.Settings s) {
        return new Location(w, st.x, st.y, st.z, (float) (st.yaw + s.yawOffset()), 0);
    }

    private static ItemStack model(NamespacedKey key) {
        if (key == null) return ItemStack.empty();
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> m.setItemModel(key));
        return it;
    }

    /** Display translation of a unit point: the display's own frame has +z forward and +x to the unit's left. */
    private static Vector3f local(VehicleSpec.Vec p) {
        return new Vector3f((float) -p.right(), (float) p.up(), (float) p.forward());
    }

    private static Transformation scaled(float scale) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static Transformation part(VehicleSpec.Vec at) {
        return new Transformation(local(at), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf());
    }

    private static Transformation wheel(VehicleSpec u, int i, double roll) {
        return new Transformation(local(u.wheels().get(i).at()), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf().rotateX((float) roll));
    }

    /** A leg swung forward by the gait about its hip (display +x rotation swings the foot back). */
    private Transformation leg(TrainSpec.Leg l, double degrees) {
        return new Transformation(local(l.at()), new Quaternionf().rotateX((float) Math.toRadians(-degrees)), new Vector3f(1, 1, 1), new Quaternionf());
    }

    /** The head lowered by the bob (and nodding a little with it). */
    private Transformation headPose(double down) {
        TrainSpec.Animal a = spec.animal();
        Vector3f t = local(a.headAt()).sub(0, (float) down, 0);
        float nod = a.bob() <= 0 ? 0 : (float) Math.toRadians(6 * down / a.bob());
        return new Transformation(t, new Quaternionf().rotateX(nod), new Vector3f(1, 1, 1), new Quaternionf());
    }

    private Transformation spun(int i) {
        TrainSpec.Part p = spec.parts().get(i);
        float r = (float) Math.toRadians(spin[i]);
        Quaternionf q = p.axis() == 'z' ? new Quaternionf().rotateZ(r) : new Quaternionf().rotateX(r);
        return new Transformation(local(p.at()), new Quaternionf(), new Vector3f(1, 1, 1), q);
    }

    // ------------------------------------------------------------------------------------------
    // Every tick
    // ------------------------------------------------------------------------------------------

    /** Puts every trailer's entities where its unit is now (the client glides there). */
    void move(LiveVehicle v, VehicleModule.Settings s) {
        for (int i = 0; i < units.size() && i < bodies.size(); i++) {
            VehiclePhysics.State st = units.get(i);
            VehicleSpec u = spec.trailers().get(i).unit();
            Location at = at(v.world, st, s);
            bodies.get(i).teleport(at);
            if (loads.get(i) != null) loads.get(i).teleport(at);
            List<ItemDisplay> ws = wheels.get(i);
            for (int w = 0; w < ws.size(); w++) {
                ItemDisplay d = ws.get(w);
                d.teleport(at);
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(s.smoothing());
                d.setTransformation(wheel(u, w, st.roll));
            }
            List<Interaction> hs = hits.get(i);
            for (int q = 0; q < hs.size(); q++) {
                double[] c = VehiclePhysics.world(st.x, st.y, st.z, st.yaw, new VehicleSpec.Vec(0, 0, u.squareOffset(q)));
                hs.get(q).teleport(new Location(v.world, c[0], c[1], c[2]));
            }
        }
        Location at = at(v.world, v.st, s);
        for (ItemDisplay l : legs) l.teleport(at);
        if (head != null) head.teleport(at);
        for (ItemDisplay p : parts) p.teleport(at);
    }

    /**
     * The driven unit's moving parts this tick: it moved `along` blocks along its facing. The animal walks (legs, head,
     * hoof steps), a flywheel spins while someone drives (faster with the speed), a rolling part rolls.
     */
    void animate(LiveVehicle v, VehicleModule.Settings s, double along, boolean driving, double topSpeed) {
        TrainSpec.Animal a = spec.animal();
        if (a != null) {
            boolean was = gait.resting();
            gait.step(along, v.st.speed, a.stride());
            if (!gait.resting() || !was || animating) {
                for (int i = 0; i < legs.size(); i++) pose(legs.get(i), leg(a.legs().get(i), gait.leg(a.legs().get(i).phase(), a.swing())), s);
                if (head != null) pose(head, headPose(gait.bob(a.bob())), s);
            }
            if (a.steps() != null) {
                walked += Math.abs(along);
                if (walked >= a.steps().every()) {
                    walked %= a.steps().every();
                    float pitch = a.steps().pitch() * (0.92f + 0.16f * (float) Math.random());
                    v.world.playSound(at(v.world, v.st, s), a.steps().sound(), SoundCategory.NEUTRAL, a.steps().volume(),
                            Math.clamp(pitch, VehicleSounds.MIN_PITCH, VehicleSounds.MAX_PITCH));
                }
            }
        }
        for (int i = 0; i < parts.size(); i++) {
            TrainSpec.Part p = spec.parts().get(i);
            double turn = p.spin() == TrainSpec.Spin.ROLL ? Math.toDegrees(along / p.radius())
                    : driving ? p.idle() + (p.top() - p.idle()) * Math.clamp(Math.abs(v.st.speed) / Math.max(0.1, topSpeed), 0, 1) : 0;
            if (Math.abs(turn) < 1e-6 && !animating) continue;
            spin[i] = (spin[i] + turn) % 360.0;
            pose(parts.get(i), spun(i), s);
        }
        animating = false;
    }

    // ------------------------------------------------------------------------------------------
    // Light (VehicleLight)
    // ------------------------------------------------------------------------------------------

    private VehicleLight.Shown unitLight(int i) {
        while (unitLight.size() <= i) unitLight.add(new VehicleLight.Shown());
        return unitLight.get(i);
    }

    /** Each towed unit's light: felt around the unit itself (a trailer may stand in a tunnel the front left) and faded to. */
    void light(boolean feel, boolean fade, VehicleLight.Lookup world) {
        for (int i = 0; i < units.size() && i < bodies.size(); i++) {
            VehiclePhysics.State st = units.get(i);
            VehicleLight.Shown l = unitLight(i);
            boolean changed = feel && l.felt(VehicleLight.sample(st.x, st.y, st.z, st.yaw, VehicleLight.probes(spec.trailers().get(i).unit()), world));
            if (fade) changed |= l.fade();
            if (!changed) continue;
            org.bukkit.entity.Display.Brightness b = VehicleModule.brightness(l);
            bodies.get(i).setBrightness(b);
            if (loads.get(i) != null) loads.get(i).setBrightness(b);
            for (ItemDisplay w : wheels.get(i)) w.setBrightness(b);
        }
    }

    /** The driven unit's moving parts (the animal, flywheels) take the driven unit's light. */
    void brighten(org.bukkit.entity.Display.Brightness b) {
        for (ItemDisplay l : legs) l.setBrightness(b);
        if (head != null) head.setBrightness(b);
        for (ItemDisplay p : parts) p.setBrightness(b);
    }

    private static void pose(ItemDisplay d, Transformation t, VehicleModule.Settings s) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(s.smoothing());
        d.setTransformation(t);
    }

    /** Each trailer shows the load of its own pages of the storage. */
    void loads(ItemStack[] cargo) {
        Object[] used = cargo == null ? null : new Object[cargo.length];
        if (cargo != null) for (int i = 0; i < cargo.length; i++) used[i] = cargo[i] == null || cargo[i].isEmpty() ? null : cargo[i];
        for (int i = 0; i < loads.size(); i++) {
            NamespacedKey want = (hidden.getOrDefault(i + 1, 0) & LOAD) != 0 ? null : spec.stage(used, i + 1);
            if (loads.get(i) == null || java.util.Objects.equals(want, loadShown.get(i))) continue;
            loadShown.set(i, want);
            loads.get(i).setItemStack(model(want));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Seats, clicks, reach
    // ------------------------------------------------------------------------------------------

    /** The unit a click box belongs to: 1.. = a trailer, 0 = the driven unit (or not this train's). */
    int unit(Entity clicked) {
        return clicked == null ? 0 : unitOf.getOrDefault(clicked.getUniqueId(), 0);
    }

    private VehiclePhysics.State state(LiveVehicle v, int unit) {
        return unit <= 0 ? v.st : units.get(Math.min(unit, units.size()) - 1);
    }

    private VehicleSpec unitSpec(LiveVehicle v, int unit) {
        return unit <= 0 ? v.spec : spec.trailers().get(Math.min(unit, units.size()) - 1).unit();
    }

    /** Where a seat is, facing its unit's way. */
    Location seat(LiveVehicle v, int seat) {
        VehiclePhysics.State st = state(v, spec.seatUnit(seat));
        double[] c = VehiclePhysics.world(st.x, st.y, st.z, st.yaw, v.spec.seats().get(seat));
        return new Location(v.world, c[0], c[1], c[2], st.yaw, 0);
    }

    /** Where a rider of a seat tries to step out, in order (world points), beside the seat's own unit. */
    List<double[]> exits(LiveVehicle v, int seat) {
        int unit = spec.seatUnit(seat);
        VehiclePhysics.State st = state(v, unit);
        List<double[]> out = new ArrayList<>();
        for (VehicleSpec.Vec e : VehicleSeats.exits(unitSpec(v, unit), v.spec.seats().get(seat))) out.add(VehiclePhysics.world(st.x, st.y, st.z, st.yaw, e));
        return out;
    }

    /** The squared distance from a point to the nearest unit's centre (the driven unit's included). */
    double distanceSquared(LiveVehicle v, Location p) {
        double best = sq(v.st, p);
        for (VehiclePhysics.State u : units) best = Math.min(best, sq(u, p));
        return best;
    }

    private static double sq(VehiclePhysics.State s, Location p) {
        double dx = s.x - p.getX(), dy = s.y - p.getY(), dz = s.z - p.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** The trailer page group a storage page is in: {unit, its first page, its pages}. */
    int[] group(int page) {
        int unit = spec.unitOfPage(page);
        if (unit < 1) return new int[]{0, 0, 1};
        return new int[]{unit, spec.firstPage(unit), spec.trailers().get(unit - 1).cargo().pages()};
    }
}
