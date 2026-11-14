package dev.stsv.economy.dock;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.economy.EconomyStore;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.vehicle.CargoWeights;
import dev.stsv.core.vehicle.Hauled;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.fishing.FishingModule;
import dev.stsv.economy.fishing.NetSpec;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 위판 (a harbour's fish auction): a boat moored in a 항구 하역장's zone sells the crates of catch in its hold
 * (fish.yml net:, {@link NetSpec}). What a boat's net hauled far out is only worth something here, so the boat has to sail
 * back; the way a truck's sealed load is only worth something at its dock.
 *
 * <ul>
 *   <li>Only crates <b>this boat</b> hauled (each is marked with the boat's number when the net brings it up,
 *       {@link FishingModule#VESSEL}): carried here by hand, or brought in another boat, they are not bought. A boat with
 *       anything in its hold cannot be picked up as an item, so the crates come the way they went: over the water.</li>
 *   <li>Fixed prices (fish.yml), like a dock's: they do not move the shop's prices, no sales tax; paid to the boat's
 *       owner, written to the dock's sales (stsv_eco_dock_sale) and the log.</li>
 *   <li>Asked again at the moment of sale: the same hold, still in the harbour, still standing still.</li>
 * </ul>
 */
final class FishAuction implements Listener {

    private final EcoPlugin eco;
    private final DockModule docks;

    FishAuction(EcoPlugin eco, DockModule docks) {
        this.eco = eco;
        this.docks = docks;
    }

    private NetSpec net() {
        return eco.fishing() == null ? NetSpec.NONE : eco.fishing().net();
    }

    /** Is this load a boat (a vehicle that floats)? A 지게 (a negative id) never is. */
    static boolean boat(Hauled h) {
        if (h.id() < 0) return false;
        NamespacedKey k = NamespacedKey.fromString(h.item());
        return k != null && STSVPlugin.get().content().item(k).map(i -> i.isVehicle() && i.vehicle().floats()).orElse(false);
    }

    /**
     * What a hold has to sell.
     *
     * @param slots   the slots that go (crates this boat hauled)
     * @param crates  crate item id -> how many
     * @param foreign crates in the hold that another boat hauled, or nobody (not bought)
     */
    record Lot(Set<Integer> slots, Map<String, Integer> crates, int foreign) {
        boolean empty() {
            return crates.isEmpty();
        }
    }

    /** The crates in a hold: (item id, count, the boat that hauled it) slot by slot -> what this boat may sell. */
    static Lot lot(NetSpec net, long boat, List<Object[]> hold) {
        Set<Integer> slots = new HashSet<>();
        Map<String, Integer> crates = new LinkedHashMap<>();
        int foreign = 0;
        for (Object[] s : hold) {
            String id = (String) s[1];
            if (net.ofItem(id) == null) continue;
            int count = (Integer) s[2];
            if ((Long) s[3] != boat) {
                foreign += count;
                continue;
            }
            slots.add((Integer) s[0]);
            crates.merge(id, count, Integer::sum);
        }
        return new Lot(slots, crates, foreign);
    }

    /** Has this hold crates of catch (hauled before nets brought up fish again)? Only then is the auction a boat's menu. */
    boolean has(Hauled h) {
        Lot l = lot(h);
        return !l.empty() || l.foreign() > 0;
    }

    private Lot lot(Hauled h) {
        List<Object[]> hold = new ArrayList<>();
        ItemStack[] c = h.contents();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == null || c[i].isEmpty()) continue;
            hold.add(new Object[]{i, Items.key(c[i]), c[i].getAmount(), FishingModule.vessel(c[i])});
        }
        return lot(net(), h.id(), hold);
    }

    // ------------------------------------------------------------------------------------------
    // The menu
    // ------------------------------------------------------------------------------------------

    final class Menu implements InventoryHolder {
        final Dock dock;
        final long vehicle, version;
        final boolean ok;
        Inventory inventory;

        Menu(Dock dock, Hauled h, boolean ok) {
            this.dock = dock;
            this.vehicle = h.id();
            this.version = h.version();
            this.ok = ok;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    static final int INFO = 18, CANCEL = 20, BED = 22, CONFIRM = 26;

    /** A boat's hold opened in a harbour: the auction's screen (what goes, for how much, a confirm button or why not). */
    void open(Player p, Dock d, Hauled h) {
        if (!docks.mayUseLoad(p, h)) {
            HudModule.flash(p, Lang.mm("dock.not-yours", "owner", h.ownerName()));
            return;
        }
        NetSpec net = net();
        Lot lot = lot(h);
        boolean ok = !lot.empty() && !h.moving();
        Menu m = new Menu(d, h, ok);
        Inventory inv = Bukkit.createInventory(m, 27, Lang.mm("fishauction.title"));
        m.inventory = inv;
        int slot = 0;
        for (Map.Entry<String, Integer> e : lot.crates().entrySet()) {
            NetSpec.Zone z = net.ofItem(e.getKey());
            ItemStack icon = Items.create(e.getKey(), 1).orElse(new ItemStack(Material.PAPER));
            icon.setAmount(Math.clamp(e.getValue(), 1, icon.getMaxStackSize()));
            List<Component> lore = List.of(Lang.item("fishauction.line", "zone", z.name(), "count", e.getValue(),
                    "unit", EconomyModule.format((long) z.value()), "total", EconomyModule.format((long) Math.floor(z.value() * e.getValue()))));
            icon.editMeta(meta -> meta.lore(lore));
            if (slot < 18) inv.setItem(slot++, icon);
        }
        for (int i = 18; i < 27; i++) inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE, " "));
        long total = net.worth(lot.crates());
        ItemStack info = pane(Material.MAP, Lang.raw("fishauction.info-title", "vehicle", h.name(), "owner", h.ownerName()));
        List<Component> infoLore = new ArrayList<>(List.of(Lang.item("fishauction.info-harbour", "dock", d.name()),
                Lang.item("fishauction.info-weight", "weight", CargoWeights.format(h.weight()), "payload", CargoWeights.format(h.payload())),
                Lang.item("fishauction.info-how")));
        if (lot.foreign() > 0) infoLore.add(Lang.item("fishauction.info-foreign", "count", lot.foreign()));
        info.editMeta(meta -> meta.lore(infoLore));
        inv.setItem(INFO, info);
        inv.setItem(CANCEL, pane(Material.BARRIER, Lang.raw("dock.cancel")));
        inv.setItem(BED, pane(Material.CHEST, Lang.raw("dock.open-bed")));
        if (ok) {
            ItemStack confirm = pane(Material.EMERALD_BLOCK, Lang.raw("fishauction.confirm", "total", EconomyModule.format(total)));
            confirm.editMeta(meta -> meta.lore(List.of(Lang.item("dock.confirm-to", "owner", h.ownerName()))));
            inv.setItem(CONFIRM, confirm);
        } else {
            ItemStack no = pane(Material.GRAY_DYE, Lang.raw("dock.cannot"));
            no.editMeta(meta -> meta.lore(List.of(Lang.item(h.moving() ? "dock.why-moving" : lot.foreign() > 0 ? "fishauction.why-foreign" : "fishauction.why-nothing"))));
            inv.setItem(CONFIRM, no);
        }
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.6f, 0.8f);
    }

    private static ItemStack pane(Material m, String name) {
        ItemStack s = new ItemStack(m);
        s.editMeta(meta -> {
            meta.itemName(dev.stsv.core.util.Text.item(name));
            meta.setMaxStackSize(1);
        });
        return s;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        switch (e.getSlot()) {
            case CANCEL -> p.closeInventory();
            case BED -> {
                p.closeInventory();
                Bukkit.getScheduler().runTask(eco, () -> {
                    if (STSVPlugin.get().vehicles() != null) STSVPlugin.get().vehicles().openBed(p, m.vehicle);
                });
            }
            case CONFIRM -> {
                if (!m.ok) return;
                p.closeInventory();
                Bukkit.getScheduler().runTask(eco, () -> confirm(p, m));
            }
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
    }

    /** The sale, checked again against the hold as it is now: the same hold, still moored in this harbour, standing still. */
    private void confirm(Player p, Menu m) {
        var vehicles = STSVPlugin.get().vehicles();
        Hauled h = vehicles == null ? null : vehicles.hauled(m.vehicle);
        if (h == null || h.version() != m.version || !docks.mayUseLoad(p, h) || h.moving() || !boat(h)) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        Dock here = docks.dockOf(h);
        if (here == null || !here.name().equals(m.dock.name()) || here.kind() != DockLayout.Kind.PORT) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        NetSpec net = net();
        Lot lot = lot(h);
        if (lot.empty()) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        List<ItemStack> taken = vehicles.unload(h.id(), h.version(), lot.slots()::contains);
        if (taken == null) {
            p.sendMessage(Lang.msg("dock.changed"));
            return;
        }
        // what was taken is what the menu showed (the version matched): pay the boat's owner
        long paid = net.worth(lot.crates());
        UUID owner = h.owner();
        StringBuilder items = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Integer> e : lot.crates().entrySet()) {
            if (!items.isEmpty()) items.append(", ");
            items.append(e.getKey()).append(" x").append(e.getValue());
            count += e.getValue();
        }
        long now = System.currentTimeMillis();
        if (paid > 0) STSVPlugin.get().economy().move(null, owner, paid, EconomyStore.Kind.FAUCET, "fish_auction", m.dock.name() + ": " + items);
        docks.logSale(new DockStore.Sale(now, m.dock.name(), h.id(), owner, p.getUniqueId(), items.toString(), paid, 0, h.trip().odometer()));
        eco.getLogger().info("[위판] " + m.dock.name() + ": " + p.getName() + " 님이 " + h.name() + " #" + h.id() + " (주인 " + h.ownerName()
                + ") 의 어획 상자를 " + paid + " G 에 팜: " + items);
        p.sendMessage(Lang.msg("fishauction.sold", "count", count, "amount", EconomyModule.format(paid), "owner", h.ownerName()));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        Player o = Bukkit.getPlayer(owner);
        if (o != null && !o.equals(p)) o.sendMessage(Lang.msg("fishauction.sold-owner", "player", p.getName(), "dock", m.dock.name(),
                "amount", EconomyModule.format(paid)));
    }
}
