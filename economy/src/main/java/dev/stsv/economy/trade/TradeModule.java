package dev.stsv.economy.trade;

import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 직거래: /거래 &lt;플레이어&gt; → the other accepts → one shared window. Each side puts items in their
 * half (left = the one who asked), sets money with the gold buttons, presses 준비 then 확정.
 * Any change resets both sides. Closing, leaving, dying or walking away cancels and returns everything.
 */
public final class TradeModule extends EcoModule {

    // the window: rows 0-4 cols 0-3 = A's offer, cols 5-8 = B's offer; bottom row = controls
    static final int A_MONEY = 45, A_BIG = 46, A_BUTTON = 47, CANCEL = 49, B_BUTTON = 51, B_BIG = 52, B_MONEY = 53;

    /** A name for the window title: two long names would run past the panel (16 characters each), so 7 + "…". */
    static String shortName(String name) {
        return name.length() > 8 ? name.substring(0, 7) + "…" : name;
    }

    private final Map<UUID, UUID> requests = new ConcurrentHashMap<>();   // target -> asker
    private final Map<UUID, Long> requestTime = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public TradeModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-trade";
    }

    @Override
    protected void start() {
        every(20L, this::checkDistance);
    }

    @Override
    protected void stop() {
        for (Session s : List.copyOf(sessions.values())) {
            try {
                s.cancel("trade.cancelled-reload");
            } catch (RuntimeException e) {
                eco.getLogger().warning("직거래 정리 실패: " + e);
            }
        }
    }

    static boolean isA(int slot) {
        return slot < 45 && slot % 9 < 4;
    }

    static boolean isB(int slot) {
        return slot < 45 && slot % 9 > 4;
    }

    // ------------------------------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------------------------------

    public void request(Player from, Player to) {
        if (from.equals(to)) {
            from.sendMessage(Lang.msg("trade.self"));
            return;
        }
        if (!near(from, to)) {
            from.sendMessage(Lang.msg("trade.too-far", "distance", (int) eco.rules().tradeDistance()));
            return;
        }
        if (sessions.containsKey(from.getUniqueId()) || sessions.containsKey(to.getUniqueId())) {
            from.sendMessage(Lang.msg("trade.busy"));
            return;
        }
        // they asked us first: accept instead
        if (from.getUniqueId().equals(requests.get(to.getUniqueId()))) {
            from.sendMessage(Lang.msg("trade.already-asked"));
            return;
        }
        if (to.getUniqueId().equals(requests.get(from.getUniqueId()))) {
            accept(from);
            return;
        }
        requests.put(to.getUniqueId(), from.getUniqueId());
        requestTime.put(to.getUniqueId(), now());
        from.sendMessage(Lang.msg("trade.asked", "player", to.getName()));
        to.sendMessage(Lang.msg("trade.request", "player", from.getName())
                .append(Component.text(" "))
                .append(Lang.mm("trade.accept-button").clickEvent(ClickEvent.runCommand("/거래 수락")))
                .append(Component.text(" "))
                .append(Lang.mm("trade.deny-button").clickEvent(ClickEvent.runCommand("/거래 거절"))));
        to.playSound(to.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.4f);
    }

    public void accept(Player to) {
        UUID askerId = requests.remove(to.getUniqueId());
        Long at = requestTime.remove(to.getUniqueId());
        Player asker = askerId == null ? null : Bukkit.getPlayer(askerId);
        if (asker == null || at == null || now() - at > 60_000L) {
            to.sendMessage(Lang.msg("trade.no-request"));
            return;
        }
        if (!near(asker, to)) {
            to.sendMessage(Lang.msg("trade.too-far", "distance", (int) eco.rules().tradeDistance()));
            return;
        }
        if (sessions.containsKey(asker.getUniqueId()) || sessions.containsKey(to.getUniqueId())) {
            to.sendMessage(Lang.msg("trade.busy"));
            return;
        }
        Session s = new Session(asker, to);
        sessions.put(asker.getUniqueId(), s);
        sessions.put(to.getUniqueId(), s);
        s.open();
    }

    public void deny(Player to) {
        UUID askerId = requests.remove(to.getUniqueId());
        requestTime.remove(to.getUniqueId());
        if (askerId == null) {
            to.sendMessage(Lang.msg("trade.no-request"));
            return;
        }
        to.sendMessage(Lang.msg("trade.denied-you"));
        Player asker = Bukkit.getPlayer(askerId);
        if (asker != null) asker.sendMessage(Lang.msg("trade.denied", "player", to.getName()));
    }

    private boolean near(Player a, Player b) {
        return a.getWorld().equals(b.getWorld()) && a.getLocation().distance(b.getLocation()) <= eco.rules().tradeDistance();
    }

    private void checkDistance() {
        for (Session s : List.copyOf(sessions.values())) {
            if (!s.a.isOnline() || !s.b.isOnline()) s.cancel("trade.cancelled-left");
            else if (!near(s.a, s.b)) s.cancel("trade.cancelled-far");
        }
    }

    // ------------------------------------------------------------------------------------------
    // The window
    // ------------------------------------------------------------------------------------------

    final class Session implements InventoryHolder {
        final Player a, b;
        final TradeState state = new TradeState();
        final Inventory inv;
        boolean finished;

        Session(Player a, Player b) {
            this.a = a;
            this.b = b;
            String title = core.menus().menu("economy:trade").map(m -> m.backgroundPrefix() + m.title()).orElse("<dark_gray>직거래")
                    .replace("{a}", shortName(a.getName())).replace("{b}", shortName(b.getName()));
            inv = Bukkit.createInventory(this, 54, Text.mm(title));
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }

        TradeState.Side side(Player p) {
            return p.equals(a) ? TradeState.Side.A : TradeState.Side.B;
        }

        boolean mine(Player p, int slot) {
            return side(p) == TradeState.Side.A ? isA(slot) : isB(slot);
        }

        void open() {
            // the middle column and the cells beside 취소 stay empty: the background draws the divide, and clicks there
            // are refused like any cell that is not the clicker's own
            draw();
            a.openInventory(inv);
            b.openInventory(inv);
            for (Player p : List.of(a, b)) p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
        }

        void draw() {
            inv.setItem(A_MONEY, money(a, TradeState.Side.A));
            inv.setItem(B_MONEY, money(b, TradeState.Side.B));
            inv.setItem(A_BIG, big(TradeState.Side.A));
            inv.setItem(B_BIG, big(TradeState.Side.B));
            inv.setItem(A_BUTTON, button(a, TradeState.Side.A));
            inv.setItem(B_BUTTON, button(b, TradeState.Side.B));
            ItemStack cancel = new ItemStack(Material.BARRIER);
            cancel.editMeta(m -> m.itemName(Lang.item("trade.cancel-button")));
            inv.setItem(CANCEL, cancel);
        }

        private ItemStack money(Player owner, TradeState.Side s) {
            ItemStack it = new ItemStack(Material.GOLD_INGOT);
            it.editMeta(m -> {
                m.itemName(Lang.item("trade.money-name", "player", owner.getName(), "amount", EconomyModule.format(state.money(s))));
                m.lore(Lang.items("trade.money-lore"));
            });
            return it;
        }

        private ItemStack big(TradeState.Side s) {
            ItemStack it = new ItemStack(Material.GOLD_BLOCK);
            it.editMeta(m -> {
                m.itemName(Lang.item("trade.big-name"));
                m.lore(Lang.items("trade.big-lore"));
            });
            return it;
        }

        private ItemStack button(Player owner, TradeState.Side s) {
            boolean ready = state.ready(s), conf = state.confirmed(s);
            ItemStack it = new ItemStack(conf ? Material.LIME_CONCRETE : ready ? Material.YELLOW_CONCRETE : Material.RED_CONCRETE);
            String key = conf ? "trade.state-confirmed" : ready ? (state.bothReady() ? "trade.state-press-confirm" : "trade.state-ready") : "trade.state-not-ready";
            it.editMeta(m -> {
                m.itemName(Lang.item(key, "player", owner.getName()));
                m.lore(Lang.items("trade.button-lore"));
            });
            return it;
        }

        void changed() {
            state.changed();
            draw();
        }

        List<ItemStack> offer(TradeState.Side s) {
            List<ItemStack> out = new ArrayList<>();
            for (int i = 0; i < 45; i++) {
                if ((s == TradeState.Side.A ? isA(i) : isB(i)) && inv.getItem(i) != null && !inv.getItem(i).isEmpty()) out.add(inv.getItem(i).clone());
            }
            return out;
        }

        void complete() {
            long ma = state.money(TradeState.Side.A), mb = state.money(TradeState.Side.B);
            if (!core.economy().has(a.getUniqueId(), ma) || !core.economy().has(b.getUniqueId(), mb)) {
                changed();
                for (Player p : List.of(a, b)) HudModule.flash(p, Lang.mm("trade.money-changed"));
                return;
            }
            finished = true;
            List<ItemStack> fromA = offer(TradeState.Side.A), fromB = offer(TradeState.Side.B);
            inv.clear();
            if (ma > 0) core.economy().transfer(a.getUniqueId(), b.getUniqueId(), ma, "trade");
            if (mb > 0) core.economy().transfer(b.getUniqueId(), a.getUniqueId(), mb, "trade");
            for (ItemStack it : fromA) eco.mail().deliver(b.getUniqueId(), it, "trade");
            for (ItemStack it : fromB) eco.mail().deliver(a.getUniqueId(), it, "trade");
            end();
            for (Player p : List.of(a, b)) {
                p.sendMessage(Lang.msg("trade.done", "player", (p.equals(a) ? b : a).getName()));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
            }
            eco.getLogger().info("직거래 " + a.getName() + " ⇄ " + b.getName() + ": " + ma + "G + " + fromA.size() + "묶음 / " + mb + "G + " + fromB.size() + "묶음");
        }

        void cancel(String reason) {
            if (finished) return;
            finished = true;
            List<ItemStack> fromA = offer(TradeState.Side.A), fromB = offer(TradeState.Side.B);
            inv.clear();
            for (ItemStack it : fromA) eco.mail().deliver(a.getUniqueId(), it, "trade_cancel");
            for (ItemStack it : fromB) eco.mail().deliver(b.getUniqueId(), it, "trade_cancel");
            end();
            for (Player p : List.of(a, b)) if (p.isOnline()) p.sendMessage(Lang.msg(reason));
        }

        private void end() {
            sessions.remove(a.getUniqueId());
            sessions.remove(b.getUniqueId());
            Runnable close = () -> {
                for (Player p : List.of(a, b)) {
                    if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder(false) == this) p.closeInventory();
                }
            };
            if (eco.isEnabled()) Bukkit.getScheduler().runTask(eco, close);
            else close.run();   // shutting down: no scheduler
        }
    }

    // ------------------------------------------------------------------------------------------
    // Clicks
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Session s) || !(e.getWhoClicked() instanceof Player p)) return;
        if (s.finished) {
            e.setCancelled(true);
            return;
        }
        InventoryAction action = e.getAction();
        // never: collect-to-cursor (would pull from the other side), hotbar swaps into the window
        if (action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.UNKNOWN) {
            e.setCancelled(true);
            return;
        }
        boolean top = e.getClickedInventory() == s.inv;
        if (!top) {
            // own inventory: normal clicks are fine; shift-click moves into the player's own half
            if (e.isShiftClick()) {
                e.setCancelled(true);
                ItemStack it = e.getCurrentItem();
                if (it == null || it.isEmpty()) return;
                for (int i = 0; i < 45; i++) {
                    if (s.mine(p, i) && (s.inv.getItem(i) == null || s.inv.getItem(i).isEmpty())) {
                        s.inv.setItem(i, it.clone());
                        e.setCurrentItem(null);
                        s.changed();
                        return;
                    }
                }
            }
            return;
        }
        int slot = e.getSlot();
        if (s.mine(p, slot)) {
            // item moves in my half: allowed, and both must agree again - reset now (a confirm click in the
            // same tick must not go through), redraw after the move happened
            s.state.changed();
            Bukkit.getScheduler().runTask(eco, s::draw);
            return;
        }
        e.setCancelled(true);
        TradeState.Side side = s.side(p);
        boolean mineControl = side == TradeState.Side.A ? (slot == A_MONEY || slot == A_BIG || slot == A_BUTTON) : (slot == B_MONEY || slot == B_BIG || slot == B_BUTTON);
        if (slot == CANCEL) {
            s.cancel("trade.cancelled");
            return;
        }
        if (!mineControl) return;
        if (slot == A_BUTTON || slot == B_BUTTON) {
            if (s.state.press(side)) {
                s.complete();
                return;
            }
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            s.draw();
            return;
        }
        long step = slot == A_BIG || slot == B_BIG ? 10_000 : (e.isShiftClick() ? 1_000 : 100);
        long delta = e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT ? -step : step;
        s.state.setMoney(side, s.state.money(side) + delta, core.economy().balance(p.getUniqueId()));
        s.draw();
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.5f, 1.5f);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Session s) || !(e.getWhoClicked() instanceof Player p)) return;
        if (s.finished) {
            e.setCancelled(true);
            return;
        }
        for (int raw : e.getRawSlots()) {
            if (raw < 54 && !s.mine(p, raw)) {
                e.setCancelled(true);
                return;
            }
        }
        s.state.changed();
        Bukkit.getScheduler().runTask(eco, s::draw);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Session s) || s.finished) return;
        // the window closes for both
        Bukkit.getScheduler().runTask(eco, () -> s.cancel("trade.cancelled"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        requests.remove(e.getPlayer().getUniqueId());
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) s.cancel("trade.cancelled-left");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Session s = sessions.get(e.getEntity().getUniqueId());
        if (s != null) s.cancel("trade.cancelled-left");
    }
}
