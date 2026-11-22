package dev.stsv.economy.secret;

import dev.stsv.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 숨은 비밀 기념비: one stone per secret (the bus secret first, then the module's secrets), set where an admin stands
 * (/관리자 비밀 비석 세우기), in a gentle arc facing the way they look. An unsolved stone shows only "???"; a solved one
 * the secret's name, who solved it first and the date. Displays are saved with the chunk (tag economy:secret_stone);
 * once a minute they are checked and rebuilt if missing or out of date. Place: eco.kv "secret.monument".
 */
final class Monument {

    static final NamespacedKey TAG = new NamespacedKey("economy", "secret_stone");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(SecretModule.KST);
    private final SecretModule m;

    Monument(SecretModule m) {
        this.m = m;
    }

    /** One stone: its id (kv tag), the name when solved, the solver and when (ms, 0 unknown). */
    record Stone(String id, String name, Optional<UUID> solver, long at) {}

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
        if (p.isEmpty() || !p.get().isChunkLoaded()) return;
        Location base = p.get();
        List<Stone> list = stones();
        List<Entity> mine = new ArrayList<>();
        for (Entity e : base.getWorld().getNearbyEntities(base, 16, 8, 16)) {
            if (e.getPersistentDataContainer().has(TAG, PersistentDataType.STRING)) mine.add(e);
        }
        for (int i = 0; i < list.size(); i++) {
            Stone s = list.get(i);
            String text = text(s);
            String key = s.id() + "|" + text.hashCode();
            boolean ok = false;
            for (Entity e : mine) if (e instanceof TextDisplay && key.equals(e.getPersistentDataContainer().get(TAG, PersistentDataType.STRING))) ok = true;
            if (ok) continue;
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

    private String text(Stone s) {
        if (s.solver().isEmpty()) return "<dark_gray>???";
        String who = Optional.ofNullable(Bukkit.getOfflinePlayer(s.solver().get()).getName()).orElse("—");
        String when = s.at() > 0 ? DAY.format(Instant.ofEpochMilli(s.at())) : "";
        return "<gold>" + s.name() + "\n<white>" + who + (when.isEmpty() ? "" : "\n<gray>" + when);
    }

    private void spawn(Location at, Stone s, String key, String text) {
        World w = at.getWorld();
        boolean solved = s.solver().isPresent();
        Material stone = solved ? Material.POLISHED_BLACKSTONE : Material.COBBLED_DEEPSLATE;
        Location body = at.clone();
        body.setPitch(0);
        w.spawn(body, BlockDisplay.class, b -> {
            b.setBlock(stone.createBlockData());
            b.setPersistent(true);
            b.setTransformation(new Transformation(new Vector3f(-0.4f, 0.25f, -0.15f), new AxisAngle4f(), new Vector3f(0.8f, 1.5f, 0.3f), new AxisAngle4f()));
            b.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, key);
        });
        w.spawn(body, BlockDisplay.class, b -> {
            b.setBlock(Material.POLISHED_DEEPSLATE.createBlockData());
            b.setPersistent(true);
            b.setTransformation(new Transformation(new Vector3f(-0.5f, 0f, -0.25f), new AxisAngle4f(), new Vector3f(1f, 0.25f, 0.5f), new AxisAngle4f()));
            b.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, key);
        });
        w.spawn(at.clone().add(0, 2.05, 0), TextDisplay.class, t -> {
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
