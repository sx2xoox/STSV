package dev.stsv.economy.secret.pager;

import dev.stsv.core.util.Text;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The 삐삐 itself (economy:pager). One for each player who found it: it knows its owner and which copy it is (only the
 * latest one given works, so a copy left in a chest goes dead once the pocket has its pager back: an empty shell that
 * vanishes when anyone touches it). Its lore is its screen (the last messages) and the tape on its back. After the end
 * the solver has the live one, which is only a keepsake that can call people ({@link Paging}); of those, too, only the
 * latest one made works.
 */
final class Device {

    /** On the pager: whose it is. */
    static final NamespacedKey OWNER = new NamespacedKey("economy", "pager_owner");
    /** On the pager: which copy it is (eco.kv "serial"). */
    static final NamespacedKey SERIAL = new NamespacedKey("economy", "pager_serial");
    /** On the live pager (the reward): whose it is. */
    static final NamespacedKey LIVE = new NamespacedKey("economy", "pager_live");
    static final String ITEM = "economy:pager";

    private final Pager s;

    Device(Pager s) {
        this.s = s;
    }

    /** The stack's own data, read without copying its meta (this is asked on every pickup). */
    private static PersistentDataContainerView pdc(ItemStack it) {
        return it == null || it.isEmpty() ? null : it.getPersistentDataContainer();
    }

    /** Whose pager (of the story, not the live one) this is, or null. */
    static UUID ownerOf(ItemStack it) {
        PersistentDataContainerView c = pdc(it);
        String o = c == null ? null : c.get(OWNER, PersistentDataType.STRING);
        if (o == null || c.has(LIVE)) return null;   // (nearly every stack ends here: one lookup)
        try {
            return UUID.fromString(o);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Their pager, and the copy that works. */
    boolean own(ItemStack it, UUID u) {
        if (!u.equals(ownerOf(it))) return false;
        String want = s.kv(u, "serial");
        return want != null && want.equals(pdc(it).get(SERIAL, PersistentDataType.STRING));
    }

    /** Their pager, but a copy that stopped working (or one from before a reset). */
    boolean dead(ItemStack it, UUID u) {
        return u.equals(ownerOf(it)) && !own(it, u);
    }

    /**
     * A pager of the story that is not this holder's own working one: someone else's, or a copy of theirs that stopped
     * working (left in a chest while the pocket got its pager back, or from before a reset). An empty shell: it does
     * nothing, and it is gone the moment it is touched.
     */
    boolean shell(ItemStack it, UUID holder) {
        return ownerOf(it) != null && !own(it, holder);
    }

    /** Whose live pager (the reward) this is, or null. */
    static UUID liveOf(ItemStack it) {
        PersistentDataContainerView c = pdc(it);
        String o = c == null ? null : c.get(LIVE, PersistentDataType.STRING);
        try {
            return o == null ? null : UUID.fromString(o);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Their live pager, and the copy that works (the latest one made for them: there is only ever one). */
    boolean live(ItemStack it, UUID u) {
        if (!u.equals(liveOf(it))) return false;
        String want = s.kv(u, "live");
        return want != null && want.equals(pdc(it).get(SERIAL, PersistentDataType.STRING));
    }

    boolean has(Player p) {
        UUID u = p.getUniqueId();
        for (ItemStack it : p.getInventory().getContents()) if (own(it, u)) return true;
        return own(p.getItemOnCursor(), u);
    }

    /** In a hand (either one). */
    boolean holding(Player p) {
        return own(p.getInventory().getItemInMainHand(), p.getUniqueId()) || own(p.getInventory().getItemInOffHand(), p.getUniqueId());
    }

    private static String serial() {
        return Long.toHexString(System.currentTimeMillis()) + Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    private ItemStack base() {
        // (the plain paper is only for a server whose items/secrets.yml lost the pager: it still plays)
        ItemStack it = Items.create(ITEM, 1).orElseGet(() -> new ItemStack(Material.PAPER));
        it.editMeta(meta -> meta.setMaxStackSize(1));
        return Items.markBought(it);
    }

    /** A new pager for them: the copies given before stop working. */
    ItemStack create(Player p) {
        UUID u = p.getUniqueId();
        String serial = serial();
        s.kvSet(u, "serial", serial);
        ItemStack it = base();
        it.editMeta(meta -> {
            meta.itemName(Text.item(s.text("pager.name", "<#c8d2c0>삐삐")));
            meta.getPersistentDataContainer().set(OWNER, PersistentDataType.STRING, u.toString());
            meta.getPersistentDataContainer().set(SERIAL, PersistentDataType.STRING, serial);
            meta.lore(lore(u, null));
        });
        return it;
    }

    /** Into their pocket; what does not fit lies at their feet, and only they can pick it up. */
    void give(Player p) {
        drop(p, p.getInventory().addItem(create(p)).values());
    }

    private static void drop(Player p, java.util.Collection<ItemStack> left) {
        for (ItemStack it : left) p.getWorld().dropItem(p.getLocation(), it, e -> {
            e.setOwner(p.getUniqueId());
            e.setCanMobPickup(false);
        });
    }

    /** Out of the rock: it lies where the block was, for them alone. */
    void pop(Player p, Location at) {
        at.getWorld().dropItem(at, create(p), e -> {
            e.setOwner(p.getUniqueId());
            e.setCanMobPickup(false);
            e.setPickupDelay(12);
            e.setVelocity(new org.bukkit.util.Vector(0, 0.18, 0));
        });
    }

    /** Its lore: the screen, then the tape on its back. shown: what the screen shows instead of the saved messages (a preview). */
    List<Component> lore(UUID u, List<Codes.Msg> shown) {
        List<Codes.Msg> msgs = shown != null ? shown : Codes.decode(s.kv(u, "msgs"));
        List<String> colors = s.cfg().getStringList("pager.screen.colors");
        List<String> lines = new ArrayList<>(Codes.screen(msgs, s.cfg().getInt("pager.screen.keep", 5), colors,
                s.text("pager.screen.empty", "<#4c6e53>— — — —")));
        String tape = s.kv(u, "tape");
        if (tape != null) for (String l : s.cfg().getStringList("pager.back")) lines.add(l.replace("{tape}", MiniMessage.miniMessage().escapeTags(tape)));
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(Text.item(l));
        return out;
    }

    /** The pagers they carry, written again (a message came). */
    void refresh(Player p, List<Codes.Msg> shown) {
        UUID u = p.getUniqueId();
        List<Component> lore = null;
        PlayerInventory inv = p.getInventory();
        ItemStack[] all = inv.getContents();
        for (int i = 0; i < all.length; i++) {
            if (!own(all[i], u)) continue;
            if (lore == null) lore = lore(u, shown);
            List<Component> l = lore;
            all[i].editMeta(meta -> meta.lore(l));
            inv.setItem(i, all[i]);
        }
        ItemStack cur = p.getItemOnCursor();
        if (own(cur, u)) {
            List<Component> l = lore == null ? lore(u, shown) : lore;
            cur.editMeta(meta -> meta.lore(l));
            p.setItemOnCursor(cur);
        }
    }

    /**
     * Every pager of theirs on them goes (the working one too when {@code all}; else only dead copies and spares). How
     * many working ones were taken.
     */
    int take(Player p, boolean all) {
        UUID u = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        ItemStack[] items = inv.getContents();
        int taken = 0;
        boolean kept = false;
        for (int i = 0; i < items.length; i++) {
            if (!u.equals(ownerOf(items[i]))) continue;
            boolean works = own(items[i], u);
            if (works && !all && !kept) {
                kept = true;
                continue;
            }
            if (works) taken++;
            inv.setItem(i, null);
        }
        ItemStack cur = p.getItemOnCursor();
        if (u.equals(ownerOf(cur)) && (all || !own(cur, u) || kept)) {
            if (own(cur, u)) taken++;
            p.setItemOnCursor(null);
        }
        return taken;
    }

    /**
     * The reward: the same pager, alive. Its lore has the solver's name and the day. A live pager made for them before
     * (an admin's, a tester's second run) stops working: only one calls.
     */
    ItemStack liveItem(Player p) {
        String serial = serial();
        s.kvSet(p.getUniqueId(), "live", serial);
        ItemStack it = base();
        String date = LocalDate.now(dev.stsv.economy.secret.SecretModule.KST).toString().replace('-', '.');
        it.editMeta(meta -> {
            meta.itemName(Text.item(s.text("pager.live-name", "<#b6f2a6>삐삐 <gray>(살아 있음)")));
            List<Component> lore = new ArrayList<>();
            for (String l : s.cfg().getStringList("pager.live-lore")) lore.add(Text.item(l.replace("{player}", p.getName()).replace("{date}", date)));
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(LIVE, PersistentDataType.STRING, p.getUniqueId().toString());
            meta.getPersistentDataContainer().set(SERIAL, PersistentDataType.STRING, serial);
        });
        return it;
    }
}
