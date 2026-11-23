package dev.stsv.economy.guide;

import dev.stsv.economy.util.Items;
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
import java.util.function.BiConsumer;

/**
 * Where a player was and what they had before the 길라잡이 took them (like the core's minigame ReturnPoint): their place,
 * game mode, inventory (armour and off hand), health, food, experience, effects and flight, written onto the player (a
 * persistent data tag) before anything of theirs is touched. The player's file holds the tag and the tutorial's inventory
 * together or neither, so a crash or a stopped server never loses their things: whoever logs in with the tag is put back.
 * <p>
 * Putting back: every trial thing they hold is dropped, their own inventory comes back as it was, and anything else they got
 * meanwhile that is not a trial thing (a mail they took out, a real accessory they took off) is added to it (the mailbox
 * when there is no room): a real thing is never lost.
 */
final class GuideReturn {

    static final NamespacedKey KEY = new NamespacedKey("economy", "guide_return");

    private GuideReturn() {}

    static boolean has(Player p) {
        return p.getPersistentDataContainer().has(KEY, PersistentDataType.STRING);
    }

    /**
     * Whether their own things are still put away (the record holds them). False once they were given back and only the
     * trip is left (a teleport back that did not happen): what they hold then is REAL and must never be treated as the
     * tutorial's.
     */
    static boolean holding(Player p) {
        String raw = p.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (raw == null) return false;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(raw);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            return true;   // unreadable: stay on the careful side (nothing is marked real by this)
        }
        return !y.getBoolean("place-only");
    }

    static void save(Player p) {
        YamlConfiguration y = new YamlConfiguration();
        Location l = p.getLocation();
        y.set("world", l.getWorld().getName());
        y.set("x", l.getX());
        y.set("y", l.getY());
        y.set("z", l.getZ());
        y.set("yaw", (double) l.getYaw());
        y.set("pitch", (double) l.getPitch());
        y.set("mode", p.getGameMode().name());
        y.set("health", p.getHealth());
        y.set("food", p.getFoodLevel());
        y.set("saturation", (double) p.getSaturation());
        y.set("level", p.getLevel());
        y.set("exp", (double) p.getExp());
        y.set("fly", p.getAllowFlight());
        y.set("flying", p.isFlying());
        y.set("items", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(p.getInventory().getContents())));
        y.set("effects", new ArrayList<>(p.getActivePotionEffects()));
        p.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, y.saveToString());
    }

    /**
     * Puts the player back. back = go to where they were too (not when they already left the tutorial world on their own).
     * mailbox gets what does not fit. Returns false when there was nothing to put back or the record could not be read
     * (then nothing is touched and the tag stays for an admin to look at).
     */
    /** Players being put back right now: a restore that starts again inside it (an event during its teleport) does nothing. */
    private static final java.util.Set<java.util.UUID> RESTORING = new java.util.HashSet<>();

    static boolean restore(Player p, boolean back, Location fallback, BiConsumer<Player, ItemStack> mailbox) {
        if (!RESTORING.add(p.getUniqueId())) return false;
        try {
            return restoreOnce(p, back, fallback, mailbox);
        } finally {
            RESTORING.remove(p.getUniqueId());
        }
    }

    private static boolean restoreOnce(Player p, boolean back, Location fallback, BiConsumer<Player, ItemStack> mailbox) {
        String raw = p.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (raw == null) return false;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(raw);
        } catch (InvalidConfigurationException e) {
            Bukkit.getLogger().severe("[길라잡이] " + p.getName() + " 의 돌아갈 정보를 읽지 못함 (태그는 남겨 둠): " + e.getMessage());
            return false;
        }
        if (p.isInsideVehicle()) p.leaveVehicle();
        p.closeInventory();
        if (!y.getBoolean("place-only")) {
            ItemStack[] items;
            try {
                items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(y.getString("items", "")));
            } catch (RuntimeException e) {
                Bukkit.getLogger().severe("[길라잡이] " + p.getName() + " 의 인벤토리를 되돌리지 못함 (태그는 남겨 둠): " + e);
                return false;
            }
            // what they got meanwhile that is not a trial thing stays theirs
            List<ItemStack> extra = new ArrayList<>();
            ItemStack cursor = p.getItemOnCursor();
            if (keep(cursor)) extra.add(cursor.clone());
            p.setItemOnCursor(null);
            for (ItemStack it : p.getInventory().getContents()) if (keep(it)) extra.add(it.clone());
            int size = p.getInventory().getContents().length;
            p.getInventory().setContents(items.length == size ? items : java.util.Arrays.copyOf(items, size));
            for (ItemStack it : extra) for (ItemStack left : p.getInventory().addItem(it).values()) mailbox.accept(p, left);
            p.updateInventory();
            try {
                p.setGameMode(GameMode.valueOf(y.getString("mode", "SURVIVAL")));
            } catch (IllegalArgumentException e) {
                p.setGameMode(GameMode.SURVIVAL);
            }
            var max = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(Math.clamp(y.getDouble("health", 20), 0.5, max == null ? 20 : max.getValue()));
            p.setFoodLevel(y.getInt("food", 20));
            p.setSaturation((float) y.getDouble("saturation", 5));
            p.setLevel(y.getInt("level", 0));
            p.setExp((float) Math.clamp(y.getDouble("exp", 0), 0, 0.9999));
            for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
            for (Object o : y.getList("effects", List.of())) if (o instanceof PotionEffect e) p.addPotionEffect(e);
            // the things are back NOW: from here on the record holds only the trip, so nothing (an event during the teleport
            // below, a crash, a second call) can ever put the things back a second time
            y.set("items", null);
            y.set("effects", null);
            y.set("place-only", true);
            p.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, y.saveToString());
        }
        if (!back) {
            p.getPersistentDataContainer().remove(KEY);
            flight(p, y);
            return true;
        }
        World w = Bukkit.getWorld(y.getString("world", ""));
        Location to = w == null ? fallback : new Location(w, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"),
                (float) y.getDouble("yaw"), (float) y.getDouble("pitch"));
        p.setFallDistance(0);
        boolean there = to != null && p.teleport(to, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        flight(p, y);
        if (there || to == null) p.getPersistentDataContainer().remove(KEY);
        else {
            // the things are back: only the trip is left (never the things twice)
            y.set("items", null);
            y.set("place-only", true);
            p.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, y.saveToString());
        }
        return true;
    }

    private static void flight(Player p, YamlConfiguration y) {
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
            p.setAllowFlight(y.getBoolean("fly"));
            p.setFlying(y.getBoolean("fly") && y.getBoolean("flying"));
        }
    }

    /** Kept on the way back: anything but a trial thing and the guide's own helpers. */
    private static boolean keep(ItemStack it) {
        return it != null && !it.isEmpty() && !Items.trial(it);
    }
}
