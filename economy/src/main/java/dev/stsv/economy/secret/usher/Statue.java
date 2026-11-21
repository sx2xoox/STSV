package dev.stsv.economy.secret.usher;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.guide.GuideLayout;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * The solver's statue on the 체험관 plaza: a still figure in their skin on a stone block, with a plaque. Saved as eco.kv
 * "secret.usher.statue" (uuid, name, skin texture and signature, tab-separated); the figure and plaque are saved entities
 * marked {@link #KEY}, and are put back whenever they are missing (a rebuild of the world, a removed entity).
 */
final class Statue {

    static final NamespacedKey KEY = new NamespacedKey("economy", "usher_statue");
    private static final String KV = "secret.usher.statue";
    /** The pedestal block (the figure stands on it); the plaza's north-west, facing the middle. */
    static final int BX = -7, BZ = -8;

    private final EcoPlugin eco;

    Statue(EcoPlugin eco) {
        this.eco = eco;
    }

    /** Remembers the solver as they look now. */
    void save(org.bukkit.entity.Player p) {
        String value = "", sig = "";
        for (ProfileProperty pp : p.getPlayerProfile().getProperties()) {
            if (!pp.getName().equals("textures")) continue;
            value = pp.getValue();
            sig = pp.getSignature() == null ? "" : pp.getSignature();
        }
        eco.kv().set(KV, p.getUniqueId() + "\t" + p.getName() + "\t" + value + "\t" + sig);
    }

    boolean saved() {
        return eco.kv().has(KV);
    }

    void forget(World w) {
        eco.kv().remove(KV);
        if (w != null) for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(KEY)) e.remove();
    }

    static Location figureAt(World w) {
        return new Location(w, BX + 0.5, GuideLayout.FLOOR_Y + 1, BZ + 0.5, 0, 0);
    }

    /** Puts back what is missing (only while its chunk is loaded). */
    void ensure(World w) {
        String raw = eco.kv().get(KV);
        if (raw == null || w == null || !w.isChunkLoaded(BX >> 4, BZ >> 4)) return;
        String[] f = raw.split("\t", -1);
        if (f.length < 4) return;
        Block ped = w.getBlockAt(BX, GuideLayout.FLOOR_Y, BZ);
        if (ped.getType() != Material.CHISELED_STONE_BRICKS) ped.setType(Material.CHISELED_STONE_BRICKS, false);
        boolean figure = false, plaque = false;
        for (Entity e : w.getChunkAt(BX >> 4, BZ >> 4).getEntities()) {
            String what = e.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
            if (what == null) continue;
            if (what.equals("figure") && !figure) figure = true;
            else if (what.equals("plaque") && !plaque) plaque = true;
            else e.remove();   // a stray copy
        }
        if (!figure) spawnFigure(w, f);
        if (!plaque) spawnPlaque(w, f[1]);
    }

    private void spawnFigure(World w, String[] f) {
        UUID id;
        try {
            id = UUID.fromString(f[0]);
        } catch (IllegalArgumentException e) {
            return;
        }
        PlayerProfile prof = Bukkit.createProfile(id, f[1]);
        if (!f[2].isEmpty()) prof.setProperty(new ProfileProperty("textures", f[2], f[3].isEmpty() ? null : f[3]));
        w.spawn(figureAt(w), Mannequin.class, m -> {
            m.setPersistent(true);
            m.setInvulnerable(true);
            m.setGravity(false);
            m.setSilent(true);
            m.setImmovable(true);
            m.setRemoveWhenFarAway(false);
            m.setProfile(ResolvableProfile.resolvableProfile(prof));
            m.setCustomNameVisible(false);
            m.setDescription(null);
            m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, "figure");
        });
    }

    private void spawnPlaque(World w, String name) {
        Location at = new Location(w, BX + 0.5, GuideLayout.FLOOR_Y + 0.42, BZ + 1.03, 0, 0);
        w.spawn(at, TextDisplay.class, t -> {
            t.setPersistent(true);
            t.text(Text.mm("<#d9e8b0>길잡이의 길잡이\n<white><b>" + MiniMessage.miniMessage().escapeTags(name) + "</b>"));
            t.setBillboard(Display.Billboard.FIXED);
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.45f, 0.45f, 0.45f), new Quaternionf()));
            t.setViewRange(0.5f);
            t.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, "plaque");
        });
    }
}
