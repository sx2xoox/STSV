package dev.stsv.economy.secret;

import dev.stsv.core.util.Text;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.profile.PlayerTextures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Things only one player sees or hears: figures (mannequins, also of themselves), floating text, block displays, fake
 * blocks, whispers, sounds at a spot, darkness, fake chat lines and fake join/leave lines. Everything a player was shown
 * is tracked by them, and {@link #clear} takes it all away (quit, admin, secret's cleanup). Entities are not saved.
 */
public final class Haunt {

    private final SecretModule m;
    private final Map<UUID, List<Entity>> shown = new ConcurrentHashMap<>();
    private final Map<UUID, List<Location>> fakes = new ConcurrentHashMap<>();

    Haunt(SecretModule m) {
        this.m = m;
    }

    private <T extends Entity> T track(Player viewer, T e) {
        e.setPersistent(false);
        e.setVisibleByDefault(false);
        viewer.showEntity(m.eco(), e);
        shown.computeIfAbsent(viewer.getUniqueId(), k -> new ArrayList<>()).add(e);
        return e;
    }

    /** A still figure only the viewer sees. skin: "economy:entity/..." (" slim" at the end for slim arms), or null for the default. */
    public Mannequin figure(Player viewer, Location at, String skin, String nameMm) {
        Mannequin x = at.getWorld().spawn(at, Mannequin.class, e -> {
            base(e, viewer);
            if (skin != null) {
                boolean slim = skin.endsWith(" slim");
                String k = slim ? skin.substring(0, skin.length() - 5).trim() : skin.trim();
                try {
                    e.setProfile(ResolvableProfile.resolvableProfile().name("secret").skinPatch(sp -> sp.body(Key.key(k))
                            .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC)).build());
                } catch (RuntimeException ex) {
                    m.eco().getLogger().warning("[비밀] 스킨 키가 잘못됨: " + skin);
                }
            }
            name(e, nameMm);
        });
        return track(viewer, x);
    }

    /** A figure wearing someone's own skin (and their armour and hands as they are now). */
    public Mannequin likeness(Player viewer, Player of, Location at, String nameMm) {
        Mannequin x = at.getWorld().spawn(at, Mannequin.class, e -> {
            base(e, viewer);
            e.setProfile(ResolvableProfile.resolvableProfile(of.getPlayerProfile()));
            var eq = of.getEquipment();
            var me = e.getEquipment();
            me.setHelmet(eq.getHelmet());
            me.setChestplate(eq.getChestplate());
            me.setLeggings(eq.getLeggings());
            me.setBoots(eq.getBoots());
            me.setItemInMainHand(eq.getItemInMainHand());
            me.setItemInOffHand(eq.getItemInOffHand());
            name(e, nameMm);
        });
        return track(viewer, x);
    }

    /** Hidden from everyone before it enters the world (set later, others would see it for a moment). */
    private static void hidden(Entity e) {
        e.setPersistent(false);
        e.setVisibleByDefault(false);
    }

    private void base(Mannequin e, Player viewer) {
        hidden(e);
        e.setInvulnerable(true);
        e.setGravity(false);
        e.setSilent(true);
        e.setImmovable(true);
        e.setCollidable(false);
        e.setRemoveWhenFarAway(false);
    }

    private static void name(Mannequin e, String nameMm) {
        if (nameMm == null) {
            e.setCustomNameVisible(false);
            e.setDescription(null);
            return;
        }
        e.customName(Text.mm(nameMm));
        e.setCustomNameVisible(true);
        e.setDescription(null);
    }

    /** Floating text only the viewer sees (faces them). */
    public TextDisplay text(Player viewer, Location at, String mm) {
        TextDisplay t = at.getWorld().spawn(at, TextDisplay.class, e -> {
            hidden(e);
            e.text(Text.mm(mm));
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            e.setShadowed(true);
        });
        return track(viewer, t);
    }

    /** A block only the viewer sees, as a display (it does not stop anyone). */
    public BlockDisplay block(Player viewer, Location at, BlockData data) {
        Location l = at.getBlock().getLocation();
        BlockDisplay b = l.getWorld().spawn(l, BlockDisplay.class, e -> {
            hidden(e);
            e.setBlock(data);
        });
        return track(viewer, b);
    }

    /** A real-looking block change only the viewer sees (put back by {@link #clear}). */
    public void fake(Player viewer, Location at, BlockData data) {
        viewer.sendBlockChange(at, data);
        fakes.computeIfAbsent(viewer.getUniqueId(), k -> new ArrayList<>()).add(at.getBlock().getLocation());
    }

    /** One of their shown things gone (and no longer tracked). */
    public void remove(Player viewer, Entity e) {
        List<Entity> l = shown.get(viewer.getUniqueId());
        if (l != null) l.remove(e);
        if (e.isValid()) e.remove();
    }

    /** Small text above the hotbar. */
    public void whisper(Player viewer, String mm) {
        viewer.sendActionBar(Text.mm(mm));
    }

    /** A sound only they hear, coming from a spot. */
    public void sound(Player viewer, Location at, Sound s, float volume, float pitch) {
        viewer.playSound(at, s, SoundCategory.AMBIENT, volume, pitch);
    }

    /** A sound only they hear, just behind them. */
    public void behind(Player viewer, Sound s, float volume, float pitch) {
        Location l = viewer.getLocation();
        l.subtract(l.getDirection().setY(0).normalize().multiply(2));
        sound(viewer, l, s, volume, pitch);
    }

    /** The darkness pulse for a few seconds. */
    public void dark(Player viewer, int seconds) {
        viewer.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, seconds * 20, 0, false, false, false));
    }

    /** Blindness for a few seconds (the screen goes black). */
    public void black(Player viewer, int seconds) {
        viewer.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, seconds * 20, 0, false, false, false));
    }

    /** A chat line that looks like someone said it (the server's chat format, no title) — only the viewer sees it. */
    public void chat(Player viewer, String speaker, String message) {
        String f = m.core().getConfig().getString("profiles.chat-format", "{name} » {message}");
        if (f == null || f.isBlank()) f = "{name} » {message}";
        String esc = MiniMessage.miniMessage().escapeTags(message);
        String line = f.replace("{title}", "").replace("{name}", MiniMessage.miniMessage().escapeTags(speaker)).replace("{message}", esc);
        viewer.sendMessage(Text.mm(line));
    }

    /** "<name>님이 나갔습니다" / "들어왔습니다" as the server says it — only the viewer sees it. */
    public void leaveLine(Player viewer, String name, boolean join) {
        Component c = Component.translatable(join ? "multiplayer.player.joined" : "multiplayer.player.left", Component.text(name))
                .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW);
        viewer.sendMessage(c);
    }

    /** Takes away all a player was shown. */
    public void clear(UUID viewer) {
        List<Entity> l = shown.remove(viewer);
        if (l != null) for (Entity e : l) if (e.isValid()) e.remove();
        List<Location> f = fakes.remove(viewer);
        Player p = Bukkit.getPlayer(viewer);
        if (f != null && p != null) for (Location at : f) p.sendBlockChange(at, at.getBlock().getBlockData());
    }

    void clearAll() {
        for (UUID u : new ArrayList<>(shown.keySet())) clear(u);
        for (UUID u : new ArrayList<>(fakes.keySet())) clear(u);
    }
}
