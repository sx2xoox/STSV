package dev.stsv.core.minigame;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Where a player was and what they had before a minigame took them: their place, game mode, inventory (armour and the
 * off hand too), health, food, experience, effects and flight. Written onto the player (a persistent data tag) before
 * anything of theirs is touched, and taken off only once they are back: the player's file holds the tag and the race's
 * empty inventory together, or neither (a crash before the next save leaves them as they were, with their things), so
 * a crash, a kick or a stopped server can never lose an inventory: whoever logs in with the tag is put back
 * ({@link #restore}). Putting back sets everything (it never adds), so doing it twice is harmless.
 */
public final class ReturnPoint {

    public static final NamespacedKey KEY = new NamespacedKey("stsv", "minigame_return");

    private ReturnPoint() {}

    /** Writes the player's state onto them. Call before changing anything. */
    public static void save(Player p) {
        YamlConfiguration y = new YamlConfiguration();
        Location l = p.getLocation();
        y.set("world", l.getWorld().getName());
        y.set("world-id", l.getWorld().getUID().toString());
        y.set("x", l.getX());
        y.set("y", l.getY());
        y.set("z", l.getZ());
        y.set("yaw", (double) l.getYaw());
        y.set("pitch", (double) l.getPitch());
        y.set("mode", p.getGameMode().name());
        y.set("health", p.getHealth());
        y.set("food", p.getFoodLevel());
        y.set("saturation", (double) p.getSaturation());
        y.set("exhaustion", (double) p.getExhaustion());
        y.set("level", p.getLevel());
        y.set("exp", (double) p.getExp());
        y.set("fire", p.getFireTicks());
        y.set("air", p.getRemainingAir());
        y.set("fly", p.getAllowFlight());
        y.set("flying", p.isFlying());
        y.set("items", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(p.getInventory().getContents())));
        List<PotionEffect> effects = new ArrayList<>(p.getActivePotionEffects());
        y.set("effects", effects);
        p.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, y.saveToString());
    }

    public static boolean has(Player p) {
        return p.getPersistentDataContainer().has(KEY, PersistentDataType.STRING);
    }

    /**
     * Puts the player back as they were (leaving whatever they ride first) and takes the tag off once they stand where
     * they were. fallback: where to go when their world is no more. Returns false when there was nothing to put back.
     */
    public static boolean restore(Player p, Location fallback) {
        String raw = p.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (raw == null) return false;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(raw);
        } catch (InvalidConfigurationException e) {
            Bukkit.getLogger().warning("[미니게임] " + p.getName() + " 의 돌아갈 정보를 읽지 못함: " + e.getMessage());
            p.getPersistentDataContainer().remove(KEY);
            return false;
        }
        if (p.isInsideVehicle()) p.leaveVehicle();
        p.closeInventory();
        boolean placeOnly = y.getBoolean("place-only");
        if (!placeOnly) {
            ItemStack[] items;
            try {
                String b64 = y.getString("items");
                if (b64 == null || b64.isEmpty()) throw new IllegalStateException("no items recorded");
                items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(b64));
            } catch (RuntimeException e) {
                // never wipe what they have now on a bad record: keep the tag, tell the log (an admin can look at it)
                Bukkit.getLogger().severe("[미니게임] " + p.getName() + " 의 인벤토리를 되돌리지 못함 (기록은 남겨 둠): " + e);
                return false;
            }
            int size = p.getInventory().getContents().length;
            p.getInventory().setContents(items.length == size ? items : java.util.Arrays.copyOf(items, size));
            p.updateInventory();
        }
        try {
            p.setGameMode(GameMode.valueOf(y.getString("mode", "SURVIVAL")));
        } catch (IllegalArgumentException e) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        if (!placeOnly) stats(p, y);
        return back(p, y, fallback);
    }

    private static void stats(Player p, YamlConfiguration y) {
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        double health = y.getDouble("health", 20);
        p.setHealth(Math.clamp(health, 0.5, max == null ? 20 : max.getValue()));
        p.setFoodLevel(y.getInt("food", 20));
        p.setSaturation((float) y.getDouble("saturation", 5));
        p.setExhaustion((float) y.getDouble("exhaustion", 0));
        p.setLevel(y.getInt("level", 0));
        p.setExp((float) Math.clamp(y.getDouble("exp", 0), 0, 0.9999));
        p.setFireTicks(y.getInt("fire", 0));
        p.setRemainingAir(y.getInt("air", p.getMaximumAir()));
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        for (Object o : y.getList("effects", List.of())) if (o instanceof PotionEffect e) p.addPotionEffect(e);
    }

    private static boolean back(Player p, YamlConfiguration y, Location fallback) {
        p.resetPlayerTime();
        p.resetPlayerWeather();
        p.setFallDistance(0);
        World w = Bukkit.getWorld(y.getString("world", ""));
        if (w == null) {
            try {
                w = Bukkit.getWorld(java.util.UUID.fromString(y.getString("world-id", "")));
            } catch (IllegalArgumentException ignored) {
                w = null;
            }
        }
        Location to = w == null ? fallback : new Location(w, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"), (float) y.getDouble("yaw"),
                (float) y.getDouble("pitch"));
        boolean fly = y.getBoolean("fly"), flying = y.getBoolean("flying");
        boolean there = to != null && p.teleport(to, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
            p.setAllowFlight(fly);
            p.setFlying(fly && flying);
        }
        // the things are back either way; the tag stays only while they still stand in the wrong place
        if (there || to == null) p.getPersistentDataContainer().remove(KEY);
        else p.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, onlyPlace(y));
        return true;
    }

    /** What is left to do when only the trip back failed: the place (the things are back already: never twice). */
    private static String onlyPlace(YamlConfiguration y) {
        YamlConfiguration out = new YamlConfiguration();
        for (String k : List.of("world", "world-id", "x", "y", "z", "yaw", "pitch", "mode", "fly", "flying")) out.set(k, y.get(k));
        out.set("place-only", true);
        return out.saveToString();
    }

    /** A tag left only for the place (the trip back failed once): true when it is that kind. */
    public static boolean placeOnly(Player p) {
        String raw = p.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        return raw != null && raw.contains("place-only: true");
    }
}
