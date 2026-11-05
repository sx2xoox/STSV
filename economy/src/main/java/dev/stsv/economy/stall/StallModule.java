package dev.stsv.economy.stall;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.calendar.DailyListener;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 노점: the market street's numbered stall chests. Rent one for 200G a day (paid at 05:00; not paid =
 * the stall is freed and its goods come back by mail), stock the chest, set a price per kind of item
 * (/노점 가격). Others right-click the chest to buy, even while the owner is offline. A stall also
 * raises the 거래소 listing limit.
 */
public final class StallModule extends EcoModule implements DailyListener {

    private StallStore store;
    private final Map<Integer, TextDisplay> signs = new HashMap<>();

    public StallModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-stall";
    }

    @Override
    protected void start() {
        store = new StallStore(core.db());
        store.init();
        eco.calendar().onDaily(this);
        every(200L, this::signs);
        eco.onReset(() -> {
            store.reset();
            signs();
        });
    }

    @Override
    protected void stop() {
        signs.values().forEach(TextDisplay::remove);
        signs.clear();
    }

    public StallStore store() {
        return store;
    }

    public boolean owns(UUID player) {
        return store.of(player) != null;
    }

    private Block block(StallStore.Stall s) {
        World w = Bukkit.getWorld(s.world);
        return w == null ? null : w.getBlockAt(s.x, s.y, s.z);
    }

    private Inventory chest(StallStore.Stall s) {
        Block b = block(s);
        return b != null && b.getState(false) instanceof Chest c ? c.getBlockInventory() : null;
    }

    // ------------------------------------------------------------------------------------------
    // Renting
    // ------------------------------------------------------------------------------------------

    public void rent(Player p, int number) {
        StallStore.Stall s = store.all().get(number);
        if (s == null) {
            p.sendMessage(Lang.msg("stall.no-such", "number", number));
            return;
        }
        if (s.owner != null) {
            p.sendMessage(Lang.msg("stall.taken", "number", number, "player", Players.name(s.owner)));
            return;
        }
        if (owns(p.getUniqueId())) {
            p.sendMessage(Lang.msg("stall.one-each"));
            return;
        }
        long rent = eco.rules().stallRent();
        if (!core.economy().withdraw(p.getUniqueId(), rent, "stall_rent")) {
            p.sendMessage(Lang.msg("stall.not-enough", "rent", EconomyModule.format(rent)));
            return;
        }
        store.setOwner(s, p.getUniqueId(), now());
        p.sendMessage(Lang.msg("stall.rented", "number", number, "rent", EconomyModule.format(rent)));
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.2f);
        signs();
    }

    public void release(Player p) {
        StallStore.Stall s = store.of(p.getUniqueId());
        if (s == null) {
            p.sendMessage(Lang.msg("stall.none"));
            return;
        }
        evict(s, "stall_release");
        p.sendMessage(Lang.msg("stall.released", "number", s.number));
    }

    /** Frees a stall; whatever is in the chest goes to the owner's mailbox. */
    private void evict(StallStore.Stall s, String reason) {
        UUID owner = s.owner;
        Inventory inv = chest(s);
        if (inv != null && owner != null) {
            for (ItemStack it : inv.getContents()) if (it != null && !it.isEmpty()) eco.mail().deliver(owner, it, reason);
            inv.clear();
        }
        store.setOwner(s, null, now());
        signs();
    }

    /** Sets (price > 0) or removes (price 0) the price of the held kind of item. */
    public void price(Player p, long price) {
        StallStore.Stall s = store.of(p.getUniqueId());
        if (s == null) {
            p.sendMessage(Lang.msg("stall.none"));
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            p.sendMessage(Lang.msg("stall.hold-item"));
            return;
        }
        ItemStack one = hand.asOne();
        s.prices.removeIf(x -> Items.fromBytes(x.template()).isSimilar(one));
        if (price > 0) s.prices.add(new StallStore.Price(Items.bytes(one), price));
        store.savePrices(s);
        p.sendMessage(price > 0 ? Lang.msg("stall.priced", "item", Items.plainName(one), "price", EconomyModule.format(price))
                : Lang.msg("stall.unpriced", "item", Items.plainName(one)));
    }

    @Override
    public void newDay(int day, long dayStart) {
        long rent = eco.rules().stallRent();
        for (StallStore.Stall s : List.copyOf(store.all().values())) {
            if (s.owner == null || s.since >= dayStart) continue;   // rented today: already paid
            if (core.economy().withdraw(s.owner, rent, "stall_rent")) continue;
            UUID owner = s.owner;
            evict(s, "stall_evicted");
            Player p = Bukkit.getPlayer(owner);
            if (p != null) p.sendMessage(Lang.msg("stall.evicted", "number", s.number, "rent", EconomyModule.format(rent)));
            eco.getLogger().info("노점 " + s.number + " 임대료 미납으로 반납: " + Players.name(owner));
        }
    }

    // ------------------------------------------------------------------------------------------
    // The chest
    // ------------------------------------------------------------------------------------------

    private StallStore.Stall at(Block b) {
        StallStore.Stall s = store.at(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (s != null) return s;
        // the other half of a double chest opens the stall's goods too
        if (b.getState(false) instanceof Chest c && c.getInventory().getHolder(false) instanceof org.bukkit.block.DoubleChest dc) {
            for (var half : new org.bukkit.inventory.InventoryHolder[]{dc.getLeftSide(false), dc.getRightSide(false)}) {
                if (half instanceof Chest hc) {
                    Block hb = hc.getBlock();
                    StallStore.Stall x = store.at(hb.getWorld().getName(), hb.getX(), hb.getY(), hb.getZ());
                    if (x != null) return x;
                }
            }
        }
        return null;
    }

    /** No chest next to a stall chest: it would join it into a double chest. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceNext(org.bukkit.event.block.BlockPlaceEvent e) {
        org.bukkit.Material m = e.getBlockPlaced().getType();
        if (m != org.bukkit.Material.CHEST && m != org.bukkit.Material.TRAPPED_CHEST) return;
        for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            Block n = e.getBlockPlaced().getRelative(f);
            if (store.at(n.getWorld().getName(), n.getX(), n.getY(), n.getZ()) != null) {
                e.setCancelled(true);
                HudModule.flash(e.getPlayer(), Lang.mm("stall.protected"));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onOpen(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        StallStore.Stall s = at(e.getClickedBlock());
        if (s == null) return;
        Player p = e.getPlayer();
        if (p.getUniqueId().equals(s.owner) || (p.isSneaking() && p.hasPermission("stsv.economy.admin"))) return;   // the chest opens
        e.setCancelled(true);
        if (s.owner == null) {
            p.sendMessage(Lang.msg("stall.vacant", "number", s.number, "rent", EconomyModule.format(eco.rules().stallRent()))
                    .append(Component.text(" ")).append(Lang.mm("stall.rent-button").clickEvent(ClickEvent.runCommand("/노점 임대 " + s.number))));
            return;
        }
        core.menus().open(p, "economy:stall", new Shop(s));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        StallStore.Stall s = at(e.getBlock());
        if (s == null) return;
        if (!e.getPlayer().hasPermission("stsv.economy.admin") || !e.getPlayer().isSneaking()) {
            e.setCancelled(true);
            HudModule.flash(e.getPlayer(), Lang.mm("stall.protected"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHopper(InventoryMoveItemEvent e) {
        if (stallInventory(e.getSource()) || stallInventory(e.getDestination())) e.setCancelled(true);
    }

    private boolean stallInventory(Inventory inv) {
        if (inv.getHolder(false) instanceof org.bukkit.block.DoubleChest dc) {
            for (var half : new org.bukkit.inventory.InventoryHolder[]{dc.getLeftSide(false), dc.getRightSide(false)}) {
                if (half instanceof Chest hc && at(hc.getBlock()) != null) return true;
            }
            return false;
        }
        Location l = inv.getLocation();
        return l != null && l.getWorld() != null && at(l.getBlock()) != null;
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> at(b) != null);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> at(b) != null);
    }

    private final class Shop implements MenuHandler {
        private final StallStore.Stall stall;

        Shop(StallStore.Stall stall) {
            this.stall = stall;
        }

        private int stock(ItemStack template) {
            Inventory inv = chest(stall);
            if (inv == null) return 0;
            int n = 0;
            for (ItemStack it : inv.getContents()) if (it != null && it.isSimilar(template)) n += it.getAmount();
            return n;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("number", String.valueOf(stall.number), "owner", Players.name(stall.owner),
                    "balance", EconomyModule.format(core.economy().balance(v.player().getUniqueId())));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            for (StallStore.Price pr : stall.prices) {
                ItemStack it = Items.fromBytes(pr.template());
                int stock = stock(it);
                it.editMeta(meta -> {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.addAll(Lang.items(stock > 0 ? "stall.lore" : "stall.lore-empty", "price", EconomyModule.format(pr.price()), "stock", stock));
                    meta.lore(lore);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= stall.prices.size() || stall.owner == null) return;
            Player p = v.player();
            StallStore.Price pr = stall.prices.get(index);
            ItemStack template = Items.fromBytes(pr.template());
            int want = click.isShiftClick() ? template.getMaxStackSize() : 1;
            int n = Math.min(want, stock(template));
            if (n <= 0) {
                HudModule.flash(p, Lang.mm("stall.sold-out"));
                return;
            }
            n = (int) Math.min(n, core.economy().balance(p.getUniqueId()) / Math.max(1, pr.price()));
            if (n <= 0) {
                HudModule.flash(p, Lang.mm("stall.not-enough-buy", "price", EconomyModule.format(pr.price())));
                return;
            }
            n = Math.min(n, Items.room(p.getInventory(), template));
            if (n <= 0) {
                HudModule.flash(p, Lang.mm("shop.no-room"));
                return;
            }
            long total = pr.price() * n;
            if (!core.economy().transfer(p.getUniqueId(), stall.owner, total, "stall_sale")) return;
            // take from the chest
            Inventory inv = chest(stall);
            int left = n;
            for (int i = 0; i < inv.getSize() && left > 0; i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || !it.isSimilar(template)) continue;
                int take = Math.min(left, it.getAmount());
                it.setAmount(it.getAmount() - take);
                inv.setItem(i, it.getAmount() <= 0 ? null : it);
                left -= take;
            }
            ItemStack bought = template.clone();
            bought.setAmount(n - left);
            p.getInventory().addItem(bought);
            HudModule.flash(p, Lang.mm("stall.bought", "item", Items.plainName(template), "count", n - left, "amount", EconomyModule.format(total)));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
            Player owner = Bukkit.getPlayer(stall.owner);
            if (owner != null) owner.sendMessage(Lang.msg("stall.sale", "player", p.getName(), "item", Items.plainName(template), "count", n - left,
                    "amount", EconomyModule.format(total)));
            v.refresh();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Signs above the chests
    // ------------------------------------------------------------------------------------------

    private void signs() {
        for (StallStore.Stall s : store.all().values()) {
            Block b = block(s);
            if (b == null || !b.getWorld().isChunkLoaded(s.x >> 4, s.z >> 4)) continue;
            TextDisplay t = signs.get(s.number);
            if (t == null || !t.isValid()) {
                Location at = b.getLocation().add(0.5, 1.45, 0.5);
                for (var old : at.getWorld().getNearbyEntitiesByType(TextDisplay.class, at, 0.6)) {
                    if (old.getScoreboardTags().contains("stsv-stall")) old.remove();
                }
                t = at.getWorld().spawn(at, TextDisplay.class, d -> {
                    d.addScoreboardTag("stsv-stall");
                    d.setPersistent(false);
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setBackgroundColor(org.bukkit.Color.fromARGB(140, 10, 10, 14));
                });
                signs.put(s.number, t);
            }
            t.text(Text.mm(s.owner == null
                    ? Lang.raw("stall.sign-vacant", "number", s.number, "rent", EconomyModule.format(eco.rules().stallRent()))
                    : Lang.raw("stall.sign", "number", s.number, "owner", Players.name(s.owner))));
        }
        for (Integer n : List.copyOf(signs.keySet())) {
            if (!store.all().containsKey(n)) signs.remove(n).remove();
        }
    }

    // admin
    public String create(Player admin, int number) {
        Block b = admin.getTargetBlockExact(6);
        if (b == null || !(b.getState(false) instanceof Chest chest)) return "<red>상자를 바라보고 입력하세요.";
        if (chest.getInventory().getHolder(false) instanceof org.bukkit.block.DoubleChest) return "<red>큰 상자는 안 됩니다. 한 칸짜리 상자를 쓰세요.";
        if (store.all().containsKey(number)) return "<red>이미 있는 번호: " + number;
        store.create(number, b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        signs();
        return "<green>노점 " + number + " 생성";
    }

    public String delete(int number) {
        StallStore.Stall s = store.all().get(number);
        if (s == null) return "<red>없는 노점";
        if (s.owner != null) evict(s, "stall_evicted");
        store.delete(number);
        TextDisplay t = signs.remove(number);
        if (t != null) t.remove();
        return "<green>노점 " + number + " 삭제";
    }
}
