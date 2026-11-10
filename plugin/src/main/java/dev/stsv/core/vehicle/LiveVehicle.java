package dev.stsv.core.vehicle;

import dev.stsv.core.content.CustomItem;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
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
    final List<Interaction> hits = new ArrayList<>();
    /** seat entity per seat (null = empty), the rider being its passenger */
    ItemDisplay[] seats;

    boolean siren;
    int flash;
    /** what the light bar shows: -1 = dark, else the flash look */
    int lightsShown = -1;
    long lastHorn = Long.MIN_VALUE / 2;

    /** the storage bed (null without one), the page open and who has it open */
    ItemStack[] cargo;
    int stage;
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
        this.seats = new ItemDisplay[spec.seats().size()];
    }

    /** The player in a seat, or null. */
    Player rider(int seat) {
        ItemDisplay s = seat < seats.length ? seats[seat] : null;
        if (s == null || !s.isValid()) return null;
        for (Entity e : s.getPassengers()) if (e instanceof Player p) return p;
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
        out.addAll(wheels);
        out.addAll(hits);
        for (ItemDisplay s : seats) if (s != null) out.add(s);
        return out;
    }

    VehicleStore.Pos pos() {
        return new VehicleStore.Pos(world.getUID(), world.getName(), st.x, st.y, st.z, st.yaw);
    }
}
