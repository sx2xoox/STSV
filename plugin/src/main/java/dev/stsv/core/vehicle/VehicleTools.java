package dev.stsv.core.vehicle;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.crop.CropModule;
import dev.stsv.core.furniture.DisplayView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * 작업기 (README "작업기"): the farm vehicles work the fields like the real machines, the boat trawls, the 1톤 트럭 has a
 * tarp and the 탑차 a rear roller door (content/stsv/vehicle_tools.yml, {@link VehicleToolSpec}; the rules in
 * {@link ToolRules}).
 *
 * <ul>
 *   <li>The driver switches with <b>Ctrl</b> (the sprint key), standing still: the vehicle as it comes -> each tool -> back.
 *       The tool is drawn on the vehicle (its own item displays with moving parts: rotary tines, a harvester's reel, a
 *       seed drill's press wheel, the net's winch); one that takes a trailer's place (the ox cart's plough, the tiller's
 *       rotary) hides that trailer's body and load and shuts its bed (what it holds stays). Kept per vehicle
 *       ({@link VehicleToolStore}); picked up into an item, it comes back as it was made.</li>
 *   <li>Working: while someone drives forward, the strip under the tool (its width across, every block once) is worked a
 *       few blocks a tick: tilled into farmland, sown from the seeds in the bed (the first seed in it), or its ripe crops
 *       harvested as a right-click harvests them ({@link CropModule#harvestInto}: protection, job rewards, replanting) into
 *       the bed (its slots and payload; what does not fit falls by the trailer). Only blocks the driver may change: loaded
 *       chunks, outside spawn protection and the content plugin's guarded places (roads, docks, terminals:
 *       {@link #guard}), and where they may build (the land's and the build world's rules, asked as for a block placed
 *       there). Crops are never trampled: a tiller leaves anything standing on the ground alone. All vehicles together
 *       change at most vehicles.tools.blocks-per-tick blocks a tick, half when the server is busy and none at
 *       vehicles.tools.max-mspt (as the economy's road builder).</li>
 *   <li>The net: trawling slowly on the sea, now and then one fish of that water into the hold ({@link #net}, the economy's
 *       fish).</li>
 * </ul>
 */
final class VehicleTools {

    private final VehicleModule module;
    private final STSVPlugin plugin;
    private final VehicleToolStore store;
    /** the tool each vehicle has on (vehicle id -> tool id) */
    private final Map<Long, String> saved = new HashMap<>();
    private final Map<NamespacedKey, VehicleToolSpec> specs = new HashMap<>();
    private BiPredicate<Player, Block> guard;
    private BiFunction<Player, Block, ItemStack> netHook;
    /** asked instead of {@link #netHook} when set: it is told which boat hauls and how many are aboard */
    private NetHaul haulHook;
    private int perTick;
    private double maxMspt;
    /** blocks the tools may still change this tick (all vehicles), and the tick it is for */
    private int left;
    private long leftTick = -1;
    private static final ItemStackStacks STACKS = new ItemStackStacks();

    VehicleTools(VehicleModule module, STSVPlugin plugin) {
        this.module = module;
        this.plugin = plugin;
        this.store = new VehicleToolStore(plugin.db());
        store.init();
        saved.putAll(store.all());
        load();
    }

    /** Reads vehicle_tools.yml of every pack and the settings (again: /관리자 리로드). */
    void load() {
        perTick = Math.clamp(plugin.getConfig().getInt("vehicles.tools.blocks-per-tick"), 0, 1000);
        maxMspt = plugin.getConfig().getDouble("vehicles.tools.max-mspt");
        specs.clear();
        for (ContentPack pack : plugin.content().packs()) {
            Path file = pack.dir().resolve("vehicle_tools.yml");
            if (!Files.isRegularFile(file)) continue;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file.toFile());
            for (String id : y.getKeys(false)) {
                ConfigurationSection s = y.getConfigurationSection(id);
                if (s == null) continue;
                String key = id.contains(":") ? id : pack.namespace() + ":" + id;
                CustomItem item = plugin.content().item(key).filter(CustomItem::isVehicle).orElse(null);
                if (item == null) {
                    plugin.getLogger().warning("[작업기] " + pack.id() + "/vehicle_tools.yml: " + key + " 는 탈것이 아님");
                    continue;
                }
                try {
                    VehicleToolSpec spec = VehicleToolSpec.parse(s, pack.namespace(), m -> Files.exists(pack.models().resolve(m + ".bbmodel")));
                    List<String> problems = spec.problems(item.vehicle());
                    if (!problems.isEmpty()) {
                        plugin.getLogger().warning("[작업기] " + key + ": " + String.join(", ", problems));
                        continue;
                    }
                    specs.put(item.key(), spec);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("[작업기] " + key + ": " + ex.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Hooks of a content plugin (VehicleModule's API)
    // ------------------------------------------------------------------------------------------

    /** Places the tools never change (roads, docks, terminals): (driver, block) -> true = keep off. null = none. */
    void guard(BiPredicate<Player, Block> guard) {
        this.guard = guard;
    }

    /** One fish of the water at this block for the trawling driver (null = nothing this time). null = no fish at all. */
    void haul(NetHaul haul) {
        this.haulHook = haul;
    }

    /** One fish of the water at this block for the trawling driver (null = nothing this time). null = no fish at all. */
    void net(BiFunction<Player, Block, ItemStack> net) {
        this.netHook = net;
    }

    // ------------------------------------------------------------------------------------------
    // A vehicle's tools
    // ------------------------------------------------------------------------------------------

    /** What one shown vehicle has on, and the entities drawing it. Main thread only. */
    static final class State {
        final VehicleToolSpec spec;
        int mode;
        /** the tool's body (null: no tool on, or a cover, drawn by `cover`) and its moving parts */
        ItemDisplay body;
        final List<ItemDisplay> parts = new ArrayList<>();
        double[] spin = new double[0];
        ItemDisplay cover, slats, inside;
        /** the cover is on (unfolded) / the door is open, and since when (ticks: its load shows again after it unfolded) */
        boolean covered, open;
        long coverAt;
        boolean loadHidden;
        /** the unit the tool hangs on, where it was last tick (its moving parts roll by how far it went) */
        double lastX = Double.NaN, lastZ, lastY;
        float lastYaw;
        /** columns worked lately (each once while the tool passes over it) */
        final ArrayDeque<Long> recent = new ArrayDeque<>();
        final Set<Long> recentSet = new HashSet<>();
        /** seeds the land or a job refused lately (by kind): the next kind in the bed is sown instead */
        final Set<Object> refused = new HashSet<>();
        long refusedAt;
        /** a boat's net: how long the boat has been slow enough for it (ticks) */
        int slowTicks;
        /** the last notice and its key (a notice is not repeated for a while) */
        String said;
        long saidAt = -1000;
        /** the boat's rocking applied to the tool's parts too (null = none) */
        Quaternionf rockQ;
        Vector3f rockPivot;
        float rockBob;
        Display.Brightness bright;

        State(VehicleToolSpec spec, int mode) {
            this.spec = spec;
            this.mode = mode;
        }

        VehicleToolSpec.Tool tool() {
            return spec.tool(mode);
        }

        List<Entity> entities() {
            List<Entity> out = new ArrayList<>(parts);
            if (body != null) out.add(body);
            if (cover != null) out.add(cover);
            if (slats != null) out.add(slats);
            if (inside != null) out.add(inside);
            return out;
        }

        boolean broken() {
            for (Entity e : entities()) if (!e.isValid()) return true;
            return false;
        }

        void remember(long key) {
            if (!recentSet.add(key)) return;
            recent.addLast(key);
            while (recent.size() > 32) recentSet.remove(recent.removeFirst());
        }

        void forgetWork() {
            recent.clear();
            recentSet.clear();
            refused.clear();
        }
    }

    /** Gives a vehicle that has tools its state (the tool it had on); at spawn and after a reload. */
    void attach(LiveVehicle v) {
        VehicleToolSpec spec = specs.get(v.item.key());
        if (spec == null) {
            v.tool = null;
            return;
        }
        v.tool = new State(spec, spec.mode(saved.get(v.id)));
        v.tool.covered = spec.tool(v.tool.mode) != null && spec.tool(v.tool.mode).kind() == VehicleToolSpec.Kind.COVER;
        v.tool.loadHidden = v.tool.covered;
    }

    /** The tool on now (null = none). */
    static VehicleToolSpec.Tool tool(LiveVehicle v) {
        return v.tool == null ? null : v.tool.tool();
    }

    /** Is the load of the vehicle's own bed hidden now (under a cover)? */
    static boolean loadHidden(LiveVehicle v) {
        return v.tool != null && v.tool.loadHidden;
    }

    /** The top speed while the tool is on (MAX_VALUE: no limit of its own). */
    static double speedCap(LiveVehicle v) {
        VehicleToolSpec.Tool t = tool(v);
        // (a net does not hold the boat back: it comes up by itself when the boat speeds off)
        return t == null || t.speed() <= 0 || t.kind() == VehicleToolSpec.Kind.NET ? Double.MAX_VALUE : t.speed();
    }

    /** May the bed of this unit (1.. = a trailer, 0 = the vehicle's own) be opened with the tool on? */
    static boolean bedOpen(LiveVehicle v, int unit) {
        return ToolRules.bedOpen(tool(v), unit);
    }

    /** Taken into an item (or gone for good): it comes back as it was made. */
    void forget(long id) {
        fished.remove(id);
        if (saved.remove(id) != null) store.delete(id);
    }

    /** " · Ctrl: 작업기 (쟁기)" for the driver's key hint, or "" for a vehicle without tools. */
    String keysHint(LiveVehicle v) {
        if (v.tool == null || v.tool.spec.tools().isEmpty()) return "";
        if (v.tool.spec.auto()) return Lang.raw("vehicle-tool.net-keys");
        return Lang.raw("vehicle-tool.keys", "tool", name(v.tool, v.tool.mode));
    }

    private static String name(State s, int mode) {
        VehicleToolSpec.Tool t = s.spec.tool(mode);
        return t == null ? (s.spec.base().isEmpty() ? Lang.raw("vehicle-tool.none") : s.spec.base()) : t.name();
    }

    // ------------------------------------------------------------------------------------------
    // Switching (Ctrl)
    // ------------------------------------------------------------------------------------------

    /** The driver pressed Ctrl: the next tool, if it stands still and nobody has its bed open. */
    void ctrl(LiveVehicle v, Player p) {
        State s = v.tool;
        if (s == null || s.spec.tools().isEmpty()) return;
        if (s.spec.auto()) {   // a net goes down and comes up by itself
            HudModule.flash(p, Lang.mm("vehicle-tool.net-auto"));
            return;
        }
        switch (ToolRules.maySwitch(v.st.speed, v.cargoViewer != null)) {
            case MOVING -> {
                refuse(p, Lang.mm("vehicle-tool.switch-moving"));
                return;
            }
            case BED_OPEN -> {
                refuse(p, Lang.mm("vehicle-tool.switch-bed"));
                return;
            }
            case OK -> { }
        }
        switchTo(v, ToolRules.next(s.mode, s.spec.modes()));
        StringBuilder list = new StringBuilder();
        for (int m = 0; m < s.spec.modes(); m++) {
            if (m > 0) list.append(" → ");
            list.append(m == s.mode ? "<white>" + name(s, m) + "</white>" : name(s, m));
        }
        HudModule.flash(p, Lang.mm("vehicle-tool.switched", "tool", name(s, s.mode), "list", list.toString()));
    }

    void switchTo(LiveVehicle v, int mode) {
        State s = v.tool;
        removeTool(s);
        VehicleToolSpec.Tool before = s.tool();
        s.mode = mode;
        s.forgetWork();
        VehicleToolSpec.Tool now = s.tool();
        boolean cover = now != null && now.kind() == VehicleToolSpec.Kind.COVER;
        if (cover != s.covered) {
            s.covered = cover;
            s.coverAt = module.ticks();
            if (!cover) showLoad(v, s, false);
        }
        String id = now == null ? null : now.id();
        if (id == null) saved.remove(v.id);
        else saved.put(v.id, id);
        store.save(v.id, id, System.currentTimeMillis());
        spawnTool(v);   // (the cover and the door stay: they fold and roll, see tick)
        // a clank of the hitch (a tool going on or off), a rustle for the tarp
        Location at = new Location(v.world, v.st.x, v.st.y + 1, v.st.z);
        if (cover || (before != null && before.kind() == VehicleToolSpec.Kind.COVER)) {
            v.world.playSound(at, Sound.ITEM_ARMOR_EQUIP_LEATHER, SoundCategory.NEUTRAL, 0.9f, 0.8f);
        } else {
            v.world.playSound(at, Sound.BLOCK_CHAIN_PLACE, SoundCategory.NEUTRAL, 0.9f, 0.7f);
            v.world.playSound(at, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.NEUTRAL, 0.6f, 0.8f);
        }
    }

    /** A boat's net goes down or comes up by itself (nothing is remembered: it follows the boat). */
    private void net(LiveVehicle v, State s, boolean down) {
        removeTool(s);
        s.mode = down ? 1 : 0;
        spawnTool(v);
        Location at = new Location(v.world, v.st.x, v.st.y + 0.5, v.st.z);
        v.world.playSound(at, down ? Sound.ENTITY_FISHING_BOBBER_SPLASH : Sound.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.NEUTRAL, 0.7f, down ? 0.7f : 0.8f);
        if (down) for (Player p : aboard(v)) HudModule.flash(p, Lang.mm("vehicle-tool.net-down", "tool", s.tool().name()));
    }

    private static List<Player> aboard(LiveVehicle v) {
        List<Player> out = new ArrayList<>();
        for (int i = 0; i < v.seats.length; i++) {
            Player p = v.rider(i);
            if (p != null) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------

    /** The vehicle's entities were spawned: the tool's too, and a trailer it replaces hidden. */
    void spawned(LiveVehicle v) {
        State s = v.tool;
        if (s == null) return;
        s.bright = VehicleModule.brightness(v.light);
        spawnTool(v);
        VehicleToolSpec.Tool cover = s.spec.cover();
        if (cover != null) {
            s.cover = display(v, v.st, cover.model(), coverPose(cover, s.covered));
            if (s.covered && module.ticks() - s.coverAt >= cover.ticks()) showLoad(v, s, true);
        }
        VehicleToolSpec.Shutter sh = s.spec.shutter();
        if (sh != null) {
            s.slats = display(v, v.st, sh.model(), shutterPose(sh, s.open));
            s.inside = display(v, v.st, s.open ? sh.inside() : null, shutterPose(sh, false));
        }
    }

    /** The tool on now drawn (and the trailer it replaces hidden, the others shown). */
    private void spawnTool(LiveVehicle v) {
        State s = v.tool;
        s.lastX = Double.NaN;
        s.rockQ = null;
        VehicleToolSpec.Tool t = s.tool();
        // trailers: shown, but the one this tool replaces
        if (v.train != null) {
            for (int u = 1; u <= v.train.units.size(); u++) {
                int parts = 0;
                if (t != null && t.replaces() && t.unit() == u) parts = TrainView.BODY | TrainView.LOAD | (t.keepWheels() ? 0 : TrainView.WHEELS);
                v.train.hide(u, parts, v.cargo);
            }
        }
        VehiclePhysics.State st = t == null ? v.st : unit(v, t.unit());
        if (t != null && t.kind() != VehicleToolSpec.Kind.COVER) {
            s.body = display(v, st, t.model(), new Transformation(local(t.at()), new Quaternionf(), new Vector3f(t.scale(), t.scale(), t.scale()),
                    new Quaternionf()));
            s.spin = new double[t.parts().size()];
            for (int i = 0; i < t.parts().size(); i++) s.parts.add(display(v, st, t.parts().get(i).model(), spun(t.parts().get(i), 0)));
        }
    }

    private static void removeTool(State s) {
        if (s.body != null) s.body.remove();
        s.parts.forEach(Entity::remove);
        s.parts.clear();
        s.body = null;
    }

    /** The vehicle's drawn entities go (they are spawned again: spawned). */
    void removed(LiveVehicle v) {
        State s = v.tool;
        if (s == null) return;
        s.entities().forEach(Entity::remove);
        s.parts.clear();
        s.body = s.cover = s.slats = s.inside = null;
    }

    private ItemDisplay display(LiveVehicle v, VehiclePhysics.State st, NamespacedKey model, Transformation t) {
        VehicleModule.Settings set = module.shownSettings();
        return v.world.spawn(new Location(v.world, st.x, st.y, st.z, (float) (st.yaw + set.yawOffset()), 0), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(model(model));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(t);
            d.setTeleportDuration(set.smoothing());
            d.setInterpolationDuration(set.smoothing());
            d.setBrightness(v.tool == null ? null : v.tool.bright);
            DisplayView.apply(d, set.viewRange(), new float[]{(float) (Math.hypot(v.spec.length(), v.spec.width()) + 2), (float) (v.spec.height() + 1)},
                    set.culling());
            d.getPersistentDataContainer().set(VehicleModule.PART, PersistentDataType.LONG, v.id);
        });
    }

    private static ItemStack model(NamespacedKey key) {
        if (key == null) return ItemStack.empty();
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> m.setItemModel(key));
        return it;
    }

    private static Vector3f local(VehicleSpec.Vec p) {
        return new Vector3f((float) -p.right(), (float) p.up(), (float) p.forward());
    }

    private static Transformation spun(VehicleToolSpec.Part p, double degrees) {
        float r = (float) Math.toRadians(degrees);
        Quaternionf q = p.axis() == 'z' ? new Quaternionf().rotateZ(r) : new Quaternionf().rotateX(r);
        return new Transformation(local(p.at()), new Quaternionf(), new Vector3f(p.scale(), p.scale(), p.scale()), q);
    }

    /** The cover: its whole length on, folded up at the front (its origin) off. */
    private static Transformation coverPose(VehicleToolSpec.Tool c, boolean on) {
        float k = c.scale(), f = on ? 1f : (float) c.fold();
        return new Transformation(local(c.at()), new Quaternionf(), new Vector3f(k, k, k * f), new Quaternionf());
    }

    /** The roller door: hanging down closed, rolled up into its top (its origin) open. */
    private static Transformation shutterPose(VehicleToolSpec.Shutter sh, boolean open) {
        float k = sh.scale(), f = open ? (float) sh.rolled() : 1f;
        return new Transformation(local(sh.at()), new Quaternionf(), new Vector3f(k, k * f, k), new Quaternionf());
    }

    /** The unit a tool hangs on (0 = the driven one). */
    private static VehiclePhysics.State unit(LiveVehicle v, int unit) {
        if (unit <= 0 || v.train == null || v.train.units.isEmpty()) return v.st;
        return v.train.units.get(Math.min(unit, v.train.units.size()) - 1);
    }

    private void showLoad(LiveVehicle v, State s, boolean hidden) {
        s.loadHidden = hidden;
        module.loadShown(v);
    }

    /** The boat's parts rocked this way (VehicleModule#boat): the tool's go with them. */
    void rock(LiveVehicle v, Quaternionf q, Vector3f pivot, float bob) {
        State s = v.tool;
        if (s == null) return;
        s.rockQ = q;
        s.rockPivot = pivot;
        s.rockBob = bob;
        VehicleToolSpec.Tool t = s.tool();
        if (t == null || s.body == null) return;
        VehicleModule.rock(s.body, new Transformation(local(t.at()), new Quaternionf(), new Vector3f(t.scale(), t.scale(), t.scale()), new Quaternionf()),
                q, pivot, bob);
        for (int i = 0; i < s.parts.size(); i++) VehicleModule.rock(s.parts.get(i), spun(t.parts().get(i), s.spin[i]), q, pivot, bob);
    }

    private void pose(State s, ItemDisplay d, Transformation t) {
        if (s.rockQ != null) {
            VehicleModule.rock(d, t, s.rockQ, s.rockPivot, s.rockBob);
            return;
        }
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(module.shownSettings().smoothing());
        d.setTransformation(t);
    }

    // ------------------------------------------------------------------------------------------
    // Every tick
    // ------------------------------------------------------------------------------------------

    /** After the vehicle moved this tick: the tool follows, its parts turn, it works; the door and the cover move. */
    void tick(LiveVehicle v, Player driver) {
        State s = v.tool;
        if (s == null) return;
        if (s.broken()) {
            removed(v);
            spawned(v);
        }
        if (s.spec.auto()) {   // a boat's net: down while it stands or goes slowly with someone at the helm, up when it speeds off
            VehicleToolSpec.Tool net = s.spec.tools().getFirst();
            boolean afloat = v.boatMode == BoatPhysics.Mode.FLOAT;
            s.slowTicks = driver != null && afloat && Math.abs(v.st.speed) <= ToolRules.netSpeed(net) ? Math.min(s.slowTicks + 1, 1_000_000) : 0;
            boolean down = ToolRules.netDown(s.mode != 0, driver != null, afloat, v.st.speed, ToolRules.netSpeed(net), s.slowTicks);
            if (down != (s.mode != 0)) net(v, s, down);
        }
        VehicleToolSpec.Tool t = s.tool();
        Display.Brightness b = VehicleModule.brightness(v.light);
        if (!java.util.Objects.equals(b, s.bright)) {
            s.bright = b;
            for (Entity e : s.entities()) ((ItemDisplay) e).setBrightness(b);
        }
        // the cover and the door are on the driven unit, moved with it (VehicleModule#move does its own parts)
        if (s.cover != null || s.slats != null) {
            Location at = new Location(v.world, v.st.x, v.st.y, v.st.z, (float) (v.st.yaw + module.shownSettings().yawOffset()), 0);
            if (s.cover != null && !s.cover.getLocation().equals(at)) s.cover.teleport(at);
            if (s.slats != null && !s.slats.getLocation().equals(at)) {
                s.slats.teleport(at);
                s.inside.teleport(at);
            }
        }
        VehicleToolSpec.Tool cover = s.spec.cover();
        if (cover != null && s.cover != null) {
            boolean shown = Math.abs(s.cover.getTransformation().getScale().z - cover.scale()) < 1e-3;
            if (shown != s.covered) {
                s.cover.setInterpolationDelay(0);
                s.cover.setInterpolationDuration(cover.ticks());
                s.cover.setTransformation(coverPose(cover, s.covered));
            }
            if (s.covered && !s.loadHidden && module.ticks() - s.coverAt >= cover.ticks()) showLoad(v, s, true);
        }
        if (s.spec.shutter() != null && s.open && Math.abs(v.st.speed) > 1e-3) door(v, false);   // driving off: it rolls down
        if (t == null || s.body == null) return;
        VehiclePhysics.State u = unit(v, t.unit());
        double along = 0;
        boolean moved = Double.isNaN(s.lastX) || u.x != s.lastX || u.z != s.lastZ || u.y != s.lastY || u.yaw != s.lastYaw;
        if (!Double.isNaN(s.lastX)) {
            double[] fw = VehiclePhysics.forward(u.yaw);
            along = (u.x - s.lastX) * fw[0] + (u.z - s.lastZ) * fw[1];
        }
        if (moved) {
            Location at = new Location(v.world, u.x, u.y, u.z, (float) (u.yaw + module.shownSettings().yawOffset()), 0);
            s.body.teleport(at);
            for (ItemDisplay d : s.parts) d.teleport(at);
            s.lastX = u.x;
            s.lastY = u.y;
            s.lastZ = u.z;
            s.lastYaw = u.yaw;
        }
        for (int i = 0; i < s.parts.size(); i++) {
            VehicleToolSpec.Part p = t.parts().get(i);
            double turn = p.spin() == VehicleToolSpec.Spin.ROLL ? Math.toDegrees(along / p.radius()) : driver != null ? p.rate() : 0;
            if (Math.abs(turn) < 1e-6) continue;
            s.spin[i] = (s.spin[i] + turn) % 360.0;
            pose(s, s.parts.get(i), spun(p, s.spin[i]));
        }
        if (driver == null) return;
        if (t.kind() == VehicleToolSpec.Kind.NET) trawl(v, s, t, driver);
        else if (ToolRules.working(t, true, v.st.speed) && v.cargoViewer == null) work(v, s, t, u, driver);
    }

    /** Opens or closes the 탑차's roller door (it rolls up into its top over a moment). */
    void door(LiveVehicle v, boolean open) {
        State s = v.tool;
        if (s == null || s.spec.shutter() == null || s.open == open || s.slats == null) return;
        s.open = open;
        VehicleToolSpec.Shutter sh = s.spec.shutter();
        s.slats.setInterpolationDelay(0);
        s.slats.setInterpolationDuration(sh.ticks());
        s.slats.setTransformation(shutterPose(sh, open));
        if (open) s.inside.setItemStack(model(sh.inside()));
        else Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!s.open && s.inside != null && s.inside.isValid()) s.inside.setItemStack(ItemStack.empty());
        }, sh.ticks());
        double[] c = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, sh.at());
        v.world.playSound(new Location(v.world, c[0], c[1] - 1, c[2]), open ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_IRON_DOOR_CLOSE,
                SoundCategory.NEUTRAL, 0.8f, 0.6f);
        v.world.playSound(new Location(v.world, c[0], c[1] - 1, c[2]), Sound.BLOCK_CHAIN_STEP, SoundCategory.NEUTRAL, 0.8f, 0.7f);
    }

    /** Someone opened the bed (or a dock's sale menu on it): the door rolls up. */
    void bedOpened(LiveVehicle v) {
        door(v, true);
    }

    // ------------------------------------------------------------------------------------------
    // Working the ground
    // ------------------------------------------------------------------------------------------

    /** May the tools change one more block this tick (all vehicles share it; the server's load decides how many)? */
    private boolean budget() {
        long now = module.ticks();
        if (leftTick != now) {
            leftTick = now;
            left = ToolRules.budget(Bukkit.getAverageTickTime(), maxMspt, perTick);
        }
        if (left <= 0) return false;
        left--;
        return true;
    }

    private void work(LiveVehicle v, State s, VehicleToolSpec.Tool t, VehiclePhysics.State u, Player driver) {
        if (t.kind() != VehicleToolSpec.Kind.TILL && (plugin.crops() == null || !plugin.crops().running())) return;
        if (module.ticks() - s.refusedAt > 200) s.refused.clear();
        VehicleToolSpec.Work w = t.work();
        int y0 = (int) Math.floor(u.y + w.at().up() - 0.2);
        boolean changed = false, sound = false;
        for (int[] c : ToolRules.strip(u.x, u.z, u.yaw, w.at(), w.width())) {
            long key = ((long) c[0] << 32) ^ (c[1] & 0xffffffffL);
            if (s.recentSet.contains(key)) continue;
            if (!budget()) break;
            s.remember(key);
            if (!v.world.isChunkLoaded(c[0] >> 4, c[1] >> 4)) continue;
            Block ground = find(v.world, c[0], y0, c[1], t.kind());
            if (ground == null) continue;
            Block target = t.kind() == VehicleToolSpec.Kind.TILL ? ground : ground.getRelative(BlockFace.UP);
            // the harvester's target holds a crop, and placing into a crop cell is always refused (CropModule): its land is
            // asked through the soil under it instead (same column, same claim); the harvest itself goes through the real
            // harvest event, which the land rules check again
            ToolRules.Verdict verdict = mayChange(driver, t.kind() == VehicleToolSpec.Kind.HARVEST ? ground : target);
            if (verdict != ToolRules.Verdict.OK) {
                if (verdict != ToolRules.Verdict.UNLOADED) notice(driver, s,"vehicle-tool.denied-" + verdict.name().toLowerCase(java.util.Locale.ROOT), t);
                continue;
            }
            switch (t.kind()) {
                case TILL -> {
                    Block above = ground.getRelative(BlockFace.UP);
                    if (above(above) == ToolRules.Above.PLANT) above.setType(Material.AIR);
                    ground.setType(Material.FARMLAND);
                    v.world.spawnParticle(Particle.BLOCK, ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5, 6, 0.3, 0.05, 0.3, 0,
                            Material.DIRT.createBlockData());
                    if (!sound) v.world.playSound(ground.getLocation().add(0.5, 1, 0.5), Sound.ITEM_HOE_TILL, SoundCategory.BLOCKS, 0.6f, 0.9f);
                    sound = true;
                }
                case SOW -> {
                    int slot = ToolRules.seedSlot(v.cargo, it -> plugin.crops().sowable(it) && !s.refused.contains(VehicleModule.cargoKey(it)));
                    if (slot < 0) {
                        notice(driver, s, "vehicle-tool.no-seed", t);
                        continue;
                    }
                    ItemStack seed = v.cargo[slot];
                    if (plugin.crops().sowFrom(driver, target, seed)) {
                        if (seed.isEmpty() || seed.getAmount() <= 0) v.cargo[slot] = null;
                        changed = true;
                        if (!sound) v.world.playSound(target.getLocation().add(0.5, 0.2, 0.5), Sound.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 0.7f, 1f);
                        sound = true;
                    } else if (target.isEmpty() && plugin.crops().at(target).isEmpty()) {
                        s.refused.add(VehicleModule.cargoKey(seed));   // not this seed (a job's level): the next kind in the bed
                        s.refusedAt = module.ticks();
                    }
                }
                case HARVEST -> {
                    double used = module.loadWeight(v);
                    if (!ToolRules.roomToHarvest(v.cargo, used, v.spec.cargo().payload())) {
                        notice(driver, s, "vehicle-tool.full", t);
                        s.recentSet.remove(key);   // tried again once there is room
                        s.recent.remove(key);
                        continue;
                    }
                    List<ItemStack> got = new ArrayList<>();
                    if (plugin.crops().harvestInto(driver, target, got::add)) {
                        for (ItemStack it : got) changed |= into(v, it, target.getLocation().add(0.5, 0.5, 0.5));
                    }
                }
                default -> { }
            }
        }
        if (changed) module.toolCargo(v);
    }

    /** The ground block a tool works in this column (the ground the unit stands on, or a step up or down). */
    private Block find(World w, int x, int y0, int z, VehicleToolSpec.Kind kind) {
        for (int dy : new int[]{0, -1, 1}) {
            Block g = w.getBlockAt(x, y0 + dy, z);
            Block up = g.getRelative(BlockFace.UP);
            boolean hit = switch (kind) {
                case TILL -> ToolRules.tills(ground(g), above(up));
                case SOW -> ToolRules.sows(ground(g), above(up));
                case HARVEST -> ground(g) == ToolRules.Ground.FARMLAND && plugin.crops().ripeAt(up);
                default -> false;
            };
            if (hit) return g;
        }
        return null;
    }

    private static ToolRules.Ground ground(Block b) {
        return switch (b.getType()) {
            case GRASS_BLOCK -> ToolRules.Ground.GRASS;
            case DIRT -> ToolRules.Ground.DIRT;
            case DIRT_PATH -> ToolRules.Ground.PATH;
            case FARMLAND -> ToolRules.Ground.FARMLAND;
            default -> ToolRules.Ground.OTHER;
        };
    }

    private ToolRules.Above above(Block b) {
        Material m = b.getType();
        if (plugin.crops() != null && plugin.crops().at(b).isPresent()) return ToolRules.Above.CROP;
        if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return ToolRules.Above.AIR;
        if (m == Material.SHORT_GRASS || m == Material.FERN) return ToolRules.Above.PLANT;
        if (Tag.CROPS.isTagged(m)) return ToolRules.Above.CROP;
        return ToolRules.Above.OTHER;
    }

    /** May the tool change this block for its driver ({@link ToolRules#verdict}, asked in that order, the costly last)? */
    private ToolRules.Verdict mayChange(Player driver, Block b) {
        if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) return ToolRules.Verdict.UNLOADED;
        World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (b.getWorld().equals(main)) {
            Location spawn = main.getSpawnLocation();
            if (ToolRules.spawnProtected(b.getX(), b.getZ(), spawn.getBlockX(), spawn.getBlockZ(), Bukkit.getSpawnRadius(), driver.isOp(),
                    !Bukkit.getOperators().isEmpty())) return ToolRules.Verdict.SPAWN;
        }
        BiPredicate<Player, Block> g = guard;
        try {
            if (g != null && g.test(driver, b)) return ToolRules.Verdict.GUARDED;
        } catch (RuntimeException ex) {
            return ToolRules.Verdict.GUARDED;
        }
        return ToolRules.verdict(true, false, false,
                plugin.furniture() == null || plugin.furniture().mayBuild(driver, List.of(b), b.getRelative(BlockFace.DOWN), EquipmentSlot.HAND));
    }

    /** Puts a harvest or a catch into the bed (its slots and payload); what does not fit falls at `spill`. True if any went in. */
    private boolean into(LiveVehicle v, ItemStack it, Location spill) {
        if (it == null || it.isEmpty()) return false;
        if (v.cargo == null) v.cargo = new ItemStack[v.spec.cargo().slots()];
        int n = ToolRules.insert(v.cargo, it, module.weightOf(it.asOne()), module.loadWeight(v), v.spec.cargo().payload(), STACKS);
        if (n < it.getAmount()) {
            ItemStack rest = it.clone();
            rest.setAmount(it.getAmount() - n);
            v.world.dropItemNaturally(spill, rest);
        }
        return n > 0;
    }

    /** A notice to the driver (in the action bar), at most every few seconds for the same one. */
    private void notice(Player p, State s, String key, VehicleToolSpec.Tool t) {
        long now = module.ticks();
        if (key.equals(s.said) && now - s.saidAt < 100) return;
        s.said = key;
        s.saidAt = now;
        refuse(p, Lang.mm(key, "tool", t.name()));
    }

    private static void refuse(Player p, net.kyori.adventure.text.Component why) {
        HudModule.flash(p, why);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
    }

    // ------------------------------------------------------------------------------------------
    // The net (every fishing boat)
    // ------------------------------------------------------------------------------------------

    /** boat -> the places its net fished lately (kept while the server runs) */
    private final Map<Long, ToolRules.Spots> fished = new java.util.HashMap<>();

    /** How often a trawling net is looked into (ticks). */
    static final int NET_EVERY = 20;

    private void trawl(LiveVehicle v, State s, VehicleToolSpec.Tool t, Player driver) {
        if ((module.ticks() + v.id) % NET_EVERY != 0) return;
        BiFunction<Player, Block, ItemStack> n = netHook;
        NetHaul hauling = haulHook;
        if ((n == null && hauling == null) || v.cargoViewer != null) return;
        if (!ToolRules.trawling(t, true, v.boatMode == BoatPhysics.Mode.FLOAT, v.st.speed)) return;
        if (ThreadLocalRandom.current().nextDouble() >= ToolRules.catchChance(t.seconds(), NET_EVERY)) return;
        int wy = (int) Math.floor(v.st.y + v.spec.water().draft() - 0.2);
        double[] stern = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, new VehicleSpec.Vec(0, 0, -v.spec.length() / 2 - 1));
        Block water = v.world.getBlockAt((int) Math.floor(stern[0]), wy, (int) Math.floor(stern[2]));
        if (water.getType() != Material.WATER) water = v.world.getBlockAt((int) Math.floor(v.st.x), wy, (int) Math.floor(v.st.z));
        if (water.getType() != Material.WATER) return;
        ToolRules.Spots spots = fished.computeIfAbsent(v.id, k -> new ToolRules.Spots());
        if (spots.spent(v.st.x, v.st.z, t.spot(), module.ticks())) {   // this place gave all it had: sail on
            if (!"vehicle-tool.net-spent".equals(s.said) || module.ticks() - s.saidAt >= 20 * 20) {
                s.said = "vehicle-tool.net-spent";
                s.saidAt = module.ticks();
                for (Player p : aboard(v)) {
                    HudModule.flash(p, Lang.mm("vehicle-tool.net-spent", "blocks", String.valueOf((int) ToolRules.Spots.RADIUS)));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
                }
            }
            return;
        }
        ItemStack fish;
        try {
            int aboard = 0;
            for (boolean taken : v.taken()) if (taken) aboard++;
            fish = hauling != null ? hauling.haul(driver, water, new NetHaul.Boat(v.id, v.item.key().asString(), aboard)) : n.apply(driver, water);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[작업기] 그물", ex);
            return;
        }
        if (fish == null || fish.isEmpty()) return;
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(fish.effectiveName());
        if (v.cargo == null) v.cargo = new ItemStack[v.spec.cargo().slots()];
        int in = ToolRules.insert(v.cargo, fish, module.weightOf(fish.asOne()), module.loadWeight(v), v.spec.cargo().payload(), STACKS);
        Location at = new Location(v.world, stern[0], wy + 1, stern[2]);
        if (in <= 0) {
            notice(driver, s, "vehicle-tool.net-full", t);   // let go again: nothing falls into the sea
            return;
        }
        module.toolCargo(v);
        int here = spots.caught(v.st.x, v.st.z, module.ticks());
        v.world.spawnParticle(Particle.SPLASH, at, 24, 0.5, 0.15, 0.5, 0.1);
        v.world.spawnParticle(Particle.FISHING, at, 8, 0.4, 0.05, 0.4, 0.02);
        v.world.playSound(at, Sound.ENTITY_FISH_SWIM, SoundCategory.NEUTRAL, 0.9f, 1.1f);
        v.world.playSound(at, Sound.ENTITY_ITEM_PICKUP, SoundCategory.NEUTRAL, 0.5f, 0.9f);
        for (Player p : aboard(v)) {   // everyone aboard sees it come up, and how much this place has left
            HudModule.flash(p, Lang.mm("vehicle-tool.net-catch", "fish", name, "count", in > 1 ? " ×" + in : "",
                    "here", String.valueOf(Math.min(here, t.spot())), "cap", String.valueOf(t.spot())));
        }
    }

    /** ItemStacks for {@link ToolRules#insert}. */
    private static final class ItemStackStacks implements ToolRules.Stacks<ItemStack> {
        @Override
        public boolean similar(ItemStack a, ItemStack b) {
            return a.isSimilar(b);
        }

        @Override
        public int amount(ItemStack t) {
            return t.getAmount();
        }

        @Override
        public int max(ItemStack t) {
            return t.getMaxStackSize();
        }

        @Override
        public ItemStack withAmount(ItemStack t, int n) {
            ItemStack c = t.clone();
            c.setAmount(n);
            return c;
        }
    }
}
