package dev.stsv.economy.secret.lostkey;

import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.secret.SecretModule;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Barrel;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The last thing (분실물센터 stage 6): a locker that is there only for its owner, at their home. It turns up behind them as
 * they come home (a barrel standing on its side like a locker door, a name plate whose name will not read, a click box).
 * Opened with the key in hand: the lid swings open, the paper of their first records shows (a book screen), the last
 * lines are said and the locker fades — then the secret is solved ({@link LostKey#finish}). Nothing here is saved.
 */
final class Locker {

    /** On the click box: the owner's id. */
    static final NamespacedKey TAG = new NamespacedKey("economy", "lostkey_locker");

    private record Shown(Location at, BlockDisplay box, TextDisplay label, Interaction hit, boolean preview) {}

    private final LostKey key;
    private final SecretModule m;
    private final EcoPlugin eco;
    private final Map<UUID, Shown> shown = new HashMap<>();
    private final Set<UUID> opening = new HashSet<>();

    Locker(LostKey key, SecretModule m, EcoPlugin eco) {
        this.key = key;
        this.m = m;
        this.eco = eco;
    }

    boolean showing(UUID u) {
        return shown.containsKey(u);
    }

    boolean opening(UUID u) {
        return opening.contains(u);
    }

    Location where(UUID u) {
        Shown s = shown.get(u);
        return s == null ? null : s.at.clone();
    }

    /**
     * The locker for this player, just behind them on the ground (else beside, else in front): turning around, there it
     * is. preview: an admin's look (opening it plays the end but solves nothing).
     */
    void show(Player p, boolean preview) {
        hide(p.getUniqueId());
        Location at = spot(p);
        World w = at.getWorld();
        Vector toPlayer = p.getLocation().toVector().subtract(at.clone().add(0.5, 0, 0.5).toVector()).setY(0);
        BlockFace face = facing(toPlayer);
        Barrel shut = (Barrel) Material.BARREL.createBlockData();
        shut.setFacing(face);
        shut.setOpen(false);
        BlockDisplay box = m.haunt().block(p, at, shut);
        box.setBrightness(new org.bukkit.entity.Display.Brightness(4, 4));
        TextDisplay label = m.haunt().text(p, at.clone().add(0.5, 1.45, 0.5), key.text("lines.locker-tag").replace("{name}", p.getName()));
        label.setViewRange(0.3f);
        Interaction hit = w.spawn(at.clone().add(0.5, 0, 0.5), Interaction.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setInteractionWidth(1.1f);
            e.setInteractionHeight(1.1f);
            e.setResponsive(true);
            e.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, p.getUniqueId() + (preview ? " preview" : ""));
        });
        p.showEntity(eco, hit);
        shown.put(p.getUniqueId(), new Shown(at, box, label, hit, preview));
        // it is behind you
        m.haunt().sound(p, at, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.6f, 0.7f);
        m.haunt().sound(p, at, Sound.BLOCK_WOOD_PLACE, 0.4f, 0.6f);
        m.haunt().whisper(p, key.text("lines.rattle"));
    }

    /** Ground beside the player: behind, then left, right, ahead; the first air block over something solid. */
    private static Location spot(Player p) {
        Location me = p.getLocation();
        Vector back = me.getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) back = new Vector(0, 0, 1);
        back.normalize().multiply(-2.5);
        Vector side = new Vector(-back.getZ(), 0, back.getX());
        for (Vector v : List.of(back, side, side.clone().multiply(-1), back.clone().multiply(-1))) {
            Block b = me.clone().add(v).getBlock();
            for (int dy : new int[] {0, 1, -1}) {
                Block at = b.getRelative(0, dy, 0);
                if (at.isPassable() && !at.isLiquid() && at.getRelative(0, 1, 0).isPassable() && at.getRelative(0, -1, 0).isSolid()) return at.getLocation();
            }
        }
        return me.clone().add(back).getBlock().getLocation();
    }

    private static BlockFace facing(Vector v) {
        if (Math.abs(v.getX()) > Math.abs(v.getZ())) return v.getX() > 0 ? BlockFace.EAST : BlockFace.WEST;
        return v.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** Takes the locker away (no fading). */
    void hide(UUID u) {
        opening.remove(u);
        Shown s = shown.remove(u);
        if (s == null) return;
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            m.haunt().remove(p, s.box);
            m.haunt().remove(p, s.label);
        } else {
            s.box.remove();
            s.label.remove();
        }
        s.hit.remove();
    }

    /** A soft rattle from it now and then while they are near (it wants to be found). */
    void ambient(Player p) {
        Shown s = shown.get(p.getUniqueId());
        if (s == null || opening(p.getUniqueId())) return;
        m.haunt().sound(p, s.at.clone().add(0.5, 0.5, 0.5), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.35f, 0.6f);
    }

    /** Right-clicked its click box. */
    void click(Player p, Interaction hit) {
        String tag = hit.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
        if (tag == null || !tag.startsWith(p.getUniqueId().toString())) return;
        Shown s = shown.get(p.getUniqueId());
        if (s == null || !s.hit.equals(hit) || opening.contains(p.getUniqueId())) return;
        if (!s.preview && !key.holdingKey(p)) {
            m.haunt().whisper(p, key.text("lines.locked"));
            m.haunt().sound(p, s.at, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5f, 0.6f);
            return;
        }
        open(p, s);
    }

    /** The end: the key turns, the lid opens, the paper, the last lines, the fading. */
    void open(Player p, boolean preview) {
        Shown s = shown.get(p.getUniqueId());
        if (s == null) {
            show(p, preview);
            s = shown.get(p.getUniqueId());
        }
        open(p, s);
    }

    private void open(Player p, Shown s) {
        UUID u = p.getUniqueId();
        opening.add(u);
        Location c = s.at.clone().add(0.5, 0.5, 0.5);
        m.haunt().sound(p, c, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.7f, 0.55f);
        later(8, p, () -> {
            Barrel open = (Barrel) s.box.getBlock().clone();
            open.setOpen(true);
            s.box.setBlock(open);
            m.haunt().sound(p, c, Sound.BLOCK_BARREL_OPEN, 0.8f, 0.7f);
            p.spawnParticle(Particle.WHITE_ASH, c, 30, 0.3, 0.3, 0.3, 0.01);
        });
        later(30, p, () -> key.paper(p, book -> {
            if (!p.isOnline() || !opening.contains(u)) return;
            m.haunt().sound(p, c, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 0.8f);
            p.openBook(book);
        }));
        List<String> end = key.cfg().getStringList("lines.end");
        long t = 30 + 160;
        for (String line : end) {
            later(t, p, () -> p.sendMessage(Text.mm(line)));
            t += 40;
        }
        later(t + 20, p, () -> fade(p, s));
    }

    /** It fades: ash rising off it for two seconds, gone, then the end. */
    private void fade(Player p, Shown s) {
        UUID u = p.getUniqueId();
        Location c = s.at.clone().add(0.5, 0.5, 0.5);
        m.haunt().sound(p, c, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.5f, 0.5f);
        for (int i = 0; i < 4; i++) {
            float shrink = 1f - (i + 1) * 0.22f;
            later(i * 10L, p, () -> {
                p.spawnParticle(Particle.WHITE_ASH, c, 25, 0.35, 0.4, 0.35, 0.02);
                p.spawnParticle(Particle.DUST, c, 6, 0.3, 0.3, 0.3, new Particle.DustOptions(Color.fromRGB(0xc9a26b), 0.8f));
                if (s.box.isValid()) {
                    float off = (1 - shrink) / 2;
                    s.box.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(off, 0, off), new org.joml.AxisAngle4f(),
                            new org.joml.Vector3f(shrink, shrink, shrink), new org.joml.AxisAngle4f()));
                    s.box.setInterpolationDuration(10);
                    s.box.setInterpolationDelay(0);
                }
            });
        }
        later(42, p, () -> {
            boolean preview = s.preview;
            hide(u);
            p.sendMessage(Text.mm(key.text("lines.fade")));
            key.finish(p, preview);
        });
    }

    private void later(long ticks, Player p, Runnable r) {
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline() && opening.contains(p.getUniqueId())) r.run();
        }, ticks);
    }

    void clearAll() {
        for (UUID u : new HashSet<>(shown.keySet())) hide(u);
    }

    static boolean near(Player p, Location at, double r) {
        return at != null && at.getWorld() != null && at.getWorld().equals(p.getWorld()) && at.distanceSquared(p.getLocation()) <= r * r;
    }
}
