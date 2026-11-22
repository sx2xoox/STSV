package dev.stsv.economy.secret;

import dev.stsv.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 숨은 비밀 기념비: one stone per secret (the bus secret first, then the module's secrets), set where an admin stands
 * (/관리자 비밀 비석 세우기), in a gentle arc facing the way they look. An unsolved stone shows only "???"; a solved one
 * the secret's name, who solved it first and the date. Each stone is one item display of a 3D gravestone model
 * (tools/assetgen/models/monument.py: economy:model/secret_stone, a weathered mossy tablet with a faint "?" groove;
 * economy:model/secret_stone_solved_&lt;id&gt;, polished black stone with a gold inlay and that secret's own gold emblem;
 * economy:model/secret_stone_solved, the same with a star, for a secret without one) and one text display just above
 * its arched top. Displays are saved with the chunk (tag economy:secret_stone = id|text hash|model, so a stone
 * still showing an older model, such as the generic star, is set again);
 * once a minute they are checked and rebuilt if missing or out of date. Place: eco.kv "secret.monument".
 */
final class Monument {

    static final NamespacedKey TAG = new NamespacedKey("economy", "secret_stone");
    /** The stone models (economy pack): unsolved and solved. */
    static final NamespacedKey MODEL = new NamespacedKey("economy", "model/secret_stone"),
            MODEL_SOLVED = new NamespacedKey("economy", "model/secret_stone_solved");
    /** Stones with their own solved model (economy:model/secret_stone_solved_&lt;id&gt;, each its own gold emblem). */
    static final Set<String> EMBLEMS = Set.of("bus", "dongbang", "letter", "designer", "usher", "fish",
            "lostkey", "doll", "scarecrow");
    /** How many stones stand: the secrets, then blank stones kept for secrets yet to come ("blank1" …: no text). */
    static final int SLOTS = 10;
    /** The model's top over the ground (blocks: plinth 0.25 + tablet 1.5) and where the text display stands. */
    static final double TOP = 1.75, TEXT_Y = TOP + 0.2;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(SecretModule.KST);
    private final SecretModule m;

    Monument(SecretModule m) {
        this.m = m;
    }

    /** One stone: its id (kv tag), the name when solved, the solver and when (ms, 0 unknown). */
    record Stone(String id, String name, Optional<UUID> solver, long at) {}

    /** The model a stone shows: unsolved, its own solved emblem, or the generic solved star. */
    static NamespacedKey model(String id, boolean solved) {
        if (!solved) return MODEL;
        return EMBLEMS.contains(id) ? new NamespacedKey("economy", "model/secret_stone_solved_" + id) : MODEL_SOLVED;
    }

    /** A stone's tag: its id, its text and its model, so a change to either sets the stone again. */
    static String key(String id, String text, NamespacedKey model) {
        return id + "|" + text.hashCode() + "|" + model.getKey();
    }

    List<Stone> stones() {
        List<Stone> out = new ArrayList<>();
        String bus = m.eco().kv().get("bus.secret.first");
        Optional<UUID> bu = Optional.empty();
        try {
            if (bus != null) bu = Optional.of(UUID.fromString(bus));
        } catch (IllegalArgumentException ignored) {
            // hand-edited
        }
        long busAt = m.eco().kv().getLong("bus.secret.first-at", 0);
        if (busAt == 0 && bu.isPresent()) busAt = m.eco().kv().getLong("bus.secret." + bu.get() + ".agent", 0);   // solved before the stones: the day they got the case open
        out.add(new Stone("bus", "버스의 비밀", bu, busAt));
        for (Secret s : m.all()) {
            out.add(new Stone(s.id(), s.cfg.getString("stone-name", s.name()), m.solver(s), m.eco().kv().getLong("secret." + s.id() + ".first-at", 0)));
        }
        for (int n = 1; out.size() < SLOTS; n++) out.add(new Stone("blank" + n, "", Optional.empty(), 0));
        return out;
    }

    Optional<Location> place() {
        String v = m.eco().kv().get("secret.monument");
        if (v == null) return Optional.empty();
        String[] w = v.split(" ");
        World world = w.length < 5 ? null : Bukkit.getWorld(w[0]);
        if (world == null) return Optional.empty();
        try {
            return Optional.of(new Location(world, Double.parseDouble(w[1]), Double.parseDouble(w[2]), Double.parseDouble(w[3]), Float.parseFloat(w[4]), 0));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    void set(Location at) {
        clear();
        Location l = at.getBlock().getLocation().add(0.5, 0, 0.5);
        float yaw = Math.round(at.getYaw() / 45f) * 45f;
        m.eco().kv().set("secret.monument", l.getWorld().getName() + " " + l.getX() + " " + l.getY() + " " + l.getZ() + " " + yaw);
        build();
    }

    void remove() {
        clear();
        m.eco().kv().remove("secret.monument");
    }

    /** Where stone i stands: an arc in front of the place, the middle stone farthest, all facing back to the place. */
    private Location stoneAt(Location base, int i, int n) {
        double spread = Math.toRadians(100);
        double a = n == 1 ? 0 : -spread / 2 + spread * i / (n - 1);
        double r = 6;
        double yaw = Math.toRadians(base.getYaw());
        // forward = (-sin yaw, cos yaw) in Minecraft
        double fx = -Math.sin(yaw + a), fz = Math.cos(yaw + a);
        Location l = base.clone().add(fx * r, 0, fz * r);
        l.setYaw((float) Math.toDegrees(Math.atan2(fx, -fz)));   // face the centre
        return l;
    }

    private void clear() {
        place().ifPresent(p -> {
            for (Entity e : p.getWorld().getNearbyEntities(p, 16, 8, 16)) {
                if (e.getPersistentDataContainer().has(TAG, PersistentDataType.STRING)) e.remove();
            }
        });
    }

    /** (Re)builds every stone whose displays are missing or show an old text. */
    void build() {
        Optional<Location> p = place();
        if (p.isEmpty()) return;
        Location base = p.get();
        List<Stone> list = stones();
        // every chunk a stone stands in must be loaded with its entities, else its saved displays are not seen and get doubled
        if (!ready(base)) return;
        for (int i = 0; i < list.size(); i++) if (!ready(stoneAt(base, i, list.size()))) return;
        List<Entity> mine = new ArrayList<>();
        for (Entity e : base.getWorld().getNearbyEntities(base, 16, 8, 16)) {
            if (e.getPersistentDataContainer().has(TAG, PersistentDataType.STRING)) mine.add(e);
        }
        for (int i = 0; i < list.size(); i++) {
            Stone s = list.get(i);
            String text = text(s);
            String key = key(s.id(), text, model(s.id(), s.solver().isPresent()));
            int texts = 0, models = 0, others = 0;
            for (Entity e : mine) {
                if (!key.equals(e.getPersistentDataContainer().get(TAG, PersistentDataType.STRING))) continue;
                if (e instanceof TextDisplay) texts++;
                else if (e instanceof ItemDisplay) models++;
                else others++;   // the two block displays of a stone from before the models: it is set again
            }
            if (whole(texts, models, others)) continue;
            for (Entity e : new ArrayList<>(mine)) {
                String t = e.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
                if (t != null && t.startsWith(s.id() + "|")) {
                    e.remove();
                    mine.remove(e);
                }
            }
            spawn(stoneAt(base, i, list.size()), s, key, text);
        }
    }

    /**
     * One stone is exactly its text and its model, nothing else (a part missing, one too many, or a block display left
     * from the old two-block stones, and it is set again).
     */
    static boolean whole(int texts, int models, int others) {
        return texts == 1 && models == 1 && others == 0;
    }

    private static boolean ready(Location l) {
        World w = l.getWorld();
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        return w.isChunkLoaded(cx, cz) && w.getChunkAt(cx, cz).isEntitiesLoaded();
    }

    /**
     * Stones found in a chunk as its entities load: those not at the current place (it moved, or was taken away, while
     * that chunk was not loaded, so {@link #clear} could not see them) are removed.
     */
    void orphans(List<Entity> loaded) {
        String v = null;
        Optional<Location> p = Optional.empty();
        for (Entity e : loaded) {
            if (!e.getPersistentDataContainer().has(TAG, PersistentDataType.STRING)) continue;
            if (v == null) {
                v = m.eco().kv().get("secret.monument");
                p = place();
                if (v == null) v = "";
            }
            Location at = e.getLocation(), l = p.orElse(null);
            if (v.isEmpty() || (l != null && stray(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ()))) {
                e.remove();
            }
            // a place that cannot be read now (its world not loaded) keeps them
        }
    }

    /** Whether a stone at (world, x, y, z) is away from the monument's place: another world, or beyond the arc. */
    static boolean stray(String placeWorld, double px, double py, double pz, String world, double x, double y, double z) {
        if (!placeWorld.equals(world)) return true;
        double dx = px - x, dz = pz - z;
        return dx * dx + dz * dz > 12 * 12 || Math.abs(py - y) > 8;
    }

    private String text(Stone s) {
        if (s.id().startsWith("blank")) return "";   // a stone kept for a secret yet to come
        if (s.solver().isEmpty()) return "<dark_gray>???";
        String who = Optional.ofNullable(Bukkit.getOfflinePlayer(s.solver().get()).getName()).orElse("—");
        String when = s.at() > 0 ? DAY.format(Instant.ofEpochMilli(s.at())) : "";
        return "<gold>" + s.name() + "\n<white>" + who + (when.isEmpty() ? "" : "\n<gray>" + when);
    }

    private void spawn(Location at, Stone s, String key, String text) {
        World w = at.getWorld();
        NamespacedKey model = model(s.id(), s.solver().isPresent());
        ItemStack shown = new ItemStack(Material.PAPER);
        shown.editMeta(meta -> meta.setItemModel(model));
        Location body = at.clone();
        body.setPitch(0);
        // FIXED shows the model's face (drawn to the north) towards the way the entity looks: the arc's centre (stoneAt);
        // it draws model (8, 8, 8) on the entity, so lift it half a block to stand the plinth on the ground
        w.spawn(body, ItemDisplay.class, d -> {
            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setPersistent(true);
            d.setTransformation(new Transformation(new Vector3f(0, 0.5f, 0), new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f()));
            d.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, key);
        });
        w.spawn(at.clone().add(0, TEXT_Y, 0), TextDisplay.class, t -> {
            t.text(Text.mm(text));
            t.setPersistent(true);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setLineWidth(120);
            t.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(), new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
            t.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, key);
        });
    }
}
