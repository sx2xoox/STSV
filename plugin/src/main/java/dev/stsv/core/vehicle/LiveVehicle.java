package dev.stsv.core.vehicle;

import dev.stsv.core.content.CustomItem;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A vehicle being shown (its chunk is loaded): its motion and the entities that draw it, none of which is ever saved
 * with the chunk (VehicleStore holds the vehicle). All parts stand at the vehicle's ground centre and turn with its yaw;
 * the wheels, light bar and load sit where their display transformation puts them. Click boxes and seats are at their
 * own places along it. Main thread only.
 */
final class LiveVehicle {

    final long id;
    CustomItem item;
    VehicleSpec spec;
    UUID owner;
    String ownerName;
    final World world;
    final VehiclePhysics.State st;

    ItemDisplay body, lights, load;
    final List<ItemDisplay> wheels = new ArrayList<>();
    /** parts turned with the wheels (VehicleSpec.Extras#spinners: a pedal crank), in the spec's order */
    final List<ItemDisplay> spinners = new ArrayList<>();
    /** degrees the body leans to the left now (two-wheelers in a turn: VehicleSpec.Extras#lean); how far the wheels rolled (radians, unbounded) */
    float lean;
    double spin;
    /** the brightness its parts are drawn with: the light around it, faded towards (VehicleLight) */
    final VehicleLight.Shown light = new VehicleLight.Shown();
    final List<Interaction> hits = new ArrayList<>();
    /**
     * An occupied seat: the carrier (an item display without an item, teleported with the vehicle every tick: it glides
     * like the body) and on it the chair the rider sits on: a marker armor stand facing the vehicle. The chair being a
     * living entity is what keeps the rider's body facing forward (see {@link VehicleSeats#riderBodyYaw}).
     */
    record Seat(ItemDisplay carrier, ArmorStand chair) {
        boolean valid() {
            return carrier.isValid() && chair.isValid() && carrier.equals(chair.getVehicle());
        }
    }

    /** per seat (null = empty) */
    Seat[] seats;

    boolean siren;
    int flash;
    /** what the light bar shows: -1 = dark, else the flash look */
    int lightsShown = -1;
    /** the horn (held space) and who was sent its sound (they are sent the stop) */
    final VehicleSounds.HornState horn = new VehicleSounds.HornState();
    final Set<UUID> hornHeard = new HashSet<>();
    /** the engine sound: when the next segment is due (plugin ticks), whether it runs, who was sent a segment */
    long engineAt;
    boolean engineOn;
    final Set<UUID> engineHeard = new HashSet<>();

    /**
     * A hand cart: the click box at its handles' end (null for driven vehicles), who holds the handles (null = nobody),
     * whether the puller was sprinting last tick (their speed modifier follows it), the distance rolled since its last
     * rolling sound and the tilt shown (degrees forward: resting on its handles, or level while pulled; NaN = not set).
     */
    Interaction handle;
    UUID puller;
    boolean pullerSprinting;
    double rolled;
    float tiltShown = Float.NaN;

    /** an articulated vehicle's towed units and moving parts (TrainView), or null */
    TrainView train;
    /** a boat: afloat, on ice or aground (BoatPhysics), and how far it turned last tick (degrees: it leans out of turns) */
    BoatPhysics.Mode boatMode = BoatPhysics.Mode.FLOAT;
    float boatTurn;

    /** the storage bed (null without one), the page open and who has it open */
    ItemStack[] cargo;
    int stage;
    /** moves on whenever the bed changes (a dock sale takes out only what it showed: VehicleModule#unload) */
    long cargoVersion;
    UUID cargoViewer;
    Inventory cargoInv;
    int cargoPage;

    /** moved since its position was last saved; when that was (plugin ticks) */
    boolean dirty;
    long savedAt;

    LiveVehicle(long id, CustomItem item, UUID owner, String ownerName, World world, VehiclePhysics.State st) {
        this.id = id;
        this.item = item;
        this.spec = item.vehicle();
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.st = st;
        this.seats = new Seat[spec.seats().size()];
    }

    /** The player in a seat, or null. */
    Player rider(int seat) {
        Seat s = seat < seats.length ? seats[seat] : null;
        if (s == null || !s.chair().isValid()) return null;
        for (Entity e : s.chair().getPassengers()) if (e instanceof Player p) return p;
        return null;
    }

    boolean[] taken() {
        boolean[] t = new boolean[seats.length];
        for (int i = 0; i < seats.length; i++) t[i] = rider(i) != null;
        return t;
    }

    boolean hasRiders() {
        for (int i = 0; i < seats.length; i++) if (rider(i) != null) return true;
        return false;
    }

    int seatOf(Player p) {
        for (int i = 0; i < seats.length; i++) if (p.equals(rider(i))) return i;
        return -1;
    }

    boolean cargoEmpty() {
        if (cargo == null) return true;
        for (ItemStack it : cargo) if (it != null && !it.isEmpty()) return false;
        return true;
    }

    int cargoUsed() {
        if (cargo == null) return 0;
        int n = 0;
        for (ItemStack it : cargo) if (it != null && !it.isEmpty()) n++;
        return n;
    }

    /** Every entity of the vehicle (seats included). */
    List<Entity> entities() {
        List<Entity> out = new ArrayList<>();
        if (body != null) out.add(body);
        if (lights != null) out.add(lights);
        if (load != null) out.add(load);
        if (handle != null) out.add(handle);
        out.addAll(wheels);
        out.addAll(spinners);
        out.addAll(hits);
        if (train != null) out.addAll(train.entities());
        for (Seat s : seats) {
            if (s == null) continue;
            out.add(s.carrier());
            out.add(s.chair());
        }
        return out;
    }

    VehicleStore.Pos pos() {
        return new VehicleStore.Pos(world.getUID(), world.getName(), st.x, st.y, st.z, st.yaw);
    }
}
