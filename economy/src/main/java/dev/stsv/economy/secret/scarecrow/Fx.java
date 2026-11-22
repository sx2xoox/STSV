package dev.stsv.economy.secret.scarecrow;

import dev.stsv.core.util.Text;
import dev.stsv.economy.util.Items;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The scarecrow's sounds and small sights, for one player at a time: crows, straw rustling behind them, its eating, the
 * knocking, the door handle (the door seems to open and shut for them only), the straw hand on the window, the
 * withered field, and the straw left at their feet. Sound keys come from scarecrow.yml {@code sounds}.
 */
final class Fx {

    private final Supplier<YamlConfiguration> cfg;
    private final Props props;

    Fx(Supplier<YamlConfiguration> cfg, Props props) {
        this.cfg = cfg;
        this.props = props;
    }

    private String key(String k, String def) {
        return cfg.get().getString("sounds." + k, def);
    }

    void sound(Player p, Location at, String k, String def, float vol, float pitch) {
        p.playSound(at, key(k, def), SoundCategory.AMBIENT, vol, pitch);
    }

    /** n crows somewhere above and around a spot, cawing (and sometimes wings). */
    void crows(Player p, Location around, int n) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Double> pitch = cfg.get().getDoubleList("sounds.crow-pitch");
        double lo = pitch.size() > 0 ? pitch.get(0) : 0.5, hi = pitch.size() > 1 ? pitch.get(1) : 0.62;
        for (int i = 0; i < Math.max(1, n); i++) {
            double a = r.nextDouble(Math.PI * 2), d = 5 + r.nextDouble(6);
            Location at = around.clone().add(Math.cos(a) * d, 4 + r.nextDouble(4), Math.sin(a) * d);
            long delay = 1 + i * (6L + r.nextInt(14));
            props.later(p, delay, () -> {
                sound(p, at, "crow", "entity.parrot.ambient", 0.7f, (float) (lo + r.nextDouble() * Math.max(0.01, hi - lo)));
                if (r.nextInt(3) == 0) sound(p, at, "wings", "entity.parrot.fly", 0.5f, 0.6f);
            });
        }
    }

    /** Straw rustling just behind them, two or three soft steps. */
    void rustleBehind(Player p) {
        Location l = p.getLocation();
        Location at = l.clone().subtract(flat(l).multiply(2.2));
        for (int i = 0; i < 3; i++) {
            float v = 0.35f + i * 0.12f;
            props.later(p, 1 + i * 7L, () -> sound(p, at, "rustle", "block.azalea_leaves.step", v, 0.6f));
        }
    }

    /** Its eating, at its head. */
    void eat(Player p, Location head, ItemStack what) {
        for (int i = 0; i < 4; i++) props.later(p, 1 + i * 4L, () -> sound(p, head, "eat", "entity.generic.eat", 0.7f, 0.55f));
        if (what != null && !what.isEmpty()) {
            try {
                p.spawnParticle(org.bukkit.Particle.ITEM, head, 14, 0.15, 0.1, 0.15, 0.04, what.asOne());
            } catch (RuntimeException ignored) {
                // a particle this item cannot make
            }
        }
    }

    /** One knock (or a heavy bang) on a spot. */
    void knock(Player p, Location at, float vol, boolean heavy) {
        sound(p, at, "knock", "entity.zombie.attack_wooden_door", heavy ? 1.0f : vol * 0.6f,
                heavy ? 0.65f : 1.5f + ThreadLocalRandom.current().nextFloat() * 0.15f);
    }

    /** Three knocks over half a second. */
    void knocks(Player p, Location at, float vol, int count, boolean heavy) {
        for (int i = 0; i < count; i++) props.later(p, 1 + i * 7L, () -> knock(p, at, vol, heavy));
    }

    /** The handle: the door seems to swing a hair open and shut, several times, for them only (the real one stays shut). */
    void rattle(Player p, Block lower) {
        if (lower == null || !(lower.getBlockData() instanceof Openable)) return;
        Block upper = lower.getRelative(BlockFace.UP);
        Location at = lower.getLocation().add(0.5, 1, 0.5);
        for (int i = 0; i < 5; i++) {
            long t = 1 + i * 5L + ThreadLocalRandom.current().nextInt(3);
            props.later(p, t, () -> {
                open(p, lower, upper, true);
                sound(p, at, "handle", "block.wooden_door.close", 0.45f, 1.6f);
            });
            props.later(p, t + 2, () -> {
                props.restore(p, lower);
                if (upper.getBlockData() instanceof Openable) props.restore(p, upper);
            });
        }
    }

    /** Shows the door open (or shut) to them only. */
    void open(Player p, Block lower, Block upper, boolean open) {
        for (Block b : new Block[]{lower, upper}) {
            if (!(b.getBlockData() instanceof Openable)) continue;
            BlockData d = b.getBlockData().clone();
            ((Openable) d).setOpen(open);
            props.fake(p, b, d);
        }
    }

    /** A straw hand pressed on the outside of a window for a while (with a scratch). */
    void hand(Player p, Block window, long ticks) {
        if (window == null) return;
        BlockFace out = Field.outsideOf(window);
        Location at = window.getLocation().add(0.5, 0.5, 0.5);
        if (out != null) at.add(out.getModX() * 0.58, 0, out.getModZ() * 0.58);
        at.setYaw(out == null ? 0 : Geometry.yawTo(-out.getModX(), -out.getModZ()));
        ItemStack straw = Items.create("economy:scarecrow_straw", 1).orElse(new ItemStack(Material.WHEAT));
        ItemDisplay d = props.item(p, at, straw, 0.85f);
        sound(p, at, "scratch", "block.sweet_berry_bush.break", 0.5f, 0.5f);
        props.later(p, 30, () -> sound(p, at, "scratch", "block.sweet_berry_bush.break", 0.35f, 0.45f));
        props.later(p, ticks, () -> props.remove(p, d));
    }

    /** The field around it withers for them: dead bushes where the crops were. Returns the blocks to put back. */
    List<Block> wither(Player p, List<Block> crops) {
        List<Block> out = new ArrayList<>();
        BlockData dead = Material.DEAD_BUSH.createBlockData();
        for (Block b : crops) {
            props.fake(p, b, dead);
            out.add(b);
        }
        return out;
    }

    /** The straw at their feet, theirs only to pick up. */
    void straw(Player p) {
        ItemStack it = Items.create("economy:scarecrow_straw", 1).orElse(new ItemStack(Material.WHEAT));
        YamlConfiguration c = cfg.get();
        it.editMeta(meta -> {
            meta.displayName(Text.item(c.getString("lines.straw-name", "<#d9c27a>허수아비의 지푸라기")));
            meta.lore(Text.itemLines(c.getStringList("lines.straw-lore")));
        });
        Location at = p.getLocation().add(flat(p.getLocation()).multiply(0.6));
        Item drop = p.getWorld().dropItem(at, it);
        drop.setOwner(p.getUniqueId());
        drop.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        sound(p, at, "straw", "block.grass.break", 0.6f, 0.5f);
        p.sendActionBar(Text.mm(c.getString("lines.straw-whisper", "<dark_gray>…")));
    }

    /** The way they face, flat (looking straight up or down — pitch ±90 — has none: then along their yaw). */
    private static org.bukkit.util.Vector flat(Location l) {
        org.bukkit.util.Vector v = l.getDirection().setY(0);
        if (v.lengthSquared() < 1e-6) {
            double r = Math.toRadians(l.getYaw());
            v = new org.bukkit.util.Vector(-Math.sin(r), 0, Math.cos(r));
        }
        return v.normalize();
    }

    /** The scare: a sting, and a second later black. */
    void scare(Player p, Location at) {
        sound(p, at, "scare", "entity.elder_guardian.curse", 1.0f, 0.8f);
        sound(p, at, "straw", "block.grass.break", 1.0f, 0.4f);
    }
}
