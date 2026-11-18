package dev.stsv.economy.accessory;

import dev.stsv.core.data.Db;
import dev.stsv.economy.mail.MailStore;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What everyone wears (player -> the six slots, the accessory items themselves) and what the bags hold (by the bag's serial, so a
 * bag keeps its contents whoever wears it). All in memory, written when changed.
 */
public final class AccessoryStore {

    public static final int SLOTS = 6;

    private final Db db;
    private final Map<UUID, ItemStack[]> worn = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> bags = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyWorn = ConcurrentHashMap.newKeySet();

    public AccessoryStore(Db db) {
        this.db = db;
    }

    public void init() {
        String blob = MailStore.blob(db);
        db.migrate("eco_accessory", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_accessory_worn (player CHAR(36) NOT NULL PRIMARY KEY, items " + blob + ")",
                "CREATE TABLE IF NOT EXISTS eco_accessory_bag (serial CHAR(36) NOT NULL PRIMARY KEY, items " + blob + ")")));
        worn.clear();
        bags.clear();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT player, items FROM eco_accessory_worn",
                rs -> new Object[]{rs.getString(1), rs.getBytes(2)}))) {
            if (r[1] == null) continue;
            ItemStack[] items = ItemStack.deserializeItemsFromBytes((byte[]) r[1]);
            ItemStack[] slots = new ItemStack[SLOTS];
            for (int i = 0; i < SLOTS && i < items.length; i++) slots[i] = items[i] == null || items[i].isEmpty() ? null : items[i];
            worn.put(UUID.fromString((String) r[0]), slots);
        }
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT serial, items FROM eco_accessory_bag",
                rs -> new Object[]{rs.getString(1), rs.getBytes(2)}))) {
            if (r[1] != null) bags.put(UUID.fromString((String) r[0]), ItemStack.deserializeItemsFromBytes((byte[]) r[1]));
        }
    }

    /** The six slots of a player (never null; empty slots are null). */
    public ItemStack[] worn(UUID player) {
        return worn.computeIfAbsent(player, u -> new ItemStack[SLOTS]);
    }

    public void setWorn(UUID player, int slot, ItemStack item) {
        worn(player)[slot] = item == null || item.isEmpty() ? null : item;
        dirtyWorn.add(player);
    }

    /** The slot changed in place (its XP): written with the next flush. */
    public void touched(UUID player) {
        dirtyWorn.add(player);
    }

    public Map<UUID, ItemStack[]> allWorn() {
        return worn;
    }

    /** A bag's contents (size = rows x 9 at least; grows when the bag grew). */
    public ItemStack[] bag(UUID serial, int size) {
        ItemStack[] b = bags.get(serial);
        if (b == null || b.length < size) {
            ItemStack[] n = new ItemStack[size];
            if (b != null) System.arraycopy(b, 0, n, 0, b.length);
            bags.put(serial, n);
            return n;
        }
        return b;
    }

    public boolean bagEmpty(UUID serial) {
        ItemStack[] b = bags.get(serial);
        if (b == null) return true;
        for (ItemStack it : b) if (it != null && !it.isEmpty()) return false;
        return true;
    }

    public void saveBag(UUID serial) {
        ItemStack[] b = bags.get(serial);
        if (b == null) return;
        ItemStack[] out = new ItemStack[b.length];
        for (int i = 0; i < b.length; i++) out[i] = b[i] == null ? ItemStack.empty() : b[i];
        db.execute(db.upsert("eco_accessory_bag", List.of("serial"), List.of("items")), serial.toString(), ItemStack.serializeItemsAsBytes(out));
    }

    public void flush() {
        for (UUID u : List.copyOf(dirtyWorn)) {
            dirtyWorn.remove(u);
            ItemStack[] w = worn.get(u);
            if (w == null) continue;
            ItemStack[] out = new ItemStack[SLOTS];
            for (int i = 0; i < SLOTS; i++) out[i] = w[i] == null ? ItemStack.empty() : w[i];
            db.execute(db.upsert("eco_accessory_worn", List.of("player"), List.of("items")), u.toString(), ItemStack.serializeItemsAsBytes(out));
        }
    }

    public void reset() {
        worn.clear();
        bags.clear();
        dirtyWorn.clear();
        db.execute("DELETE FROM eco_accessory_worn");
        db.execute("DELETE FROM eco_accessory_bag");
    }
}
