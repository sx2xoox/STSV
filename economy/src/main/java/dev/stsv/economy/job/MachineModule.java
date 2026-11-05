package dev.stsv.economy.job;

import dev.stsv.core.data.Db;
import dev.stsv.core.furniture.Furniture;
import dev.stsv.core.furniture.FurnitureBehavior;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.mail.MailStore;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Job machines: furniture that turns inputs into job products over time (분쇄기, 비료통, 수조, 강화대,
 * 조리대, 훈연 화덕, 증류기, 자동 수확 화분). Three input slots, one output; the machine works for the
 * last person of its job who opened it (their level decides the recipes, they get the XP and their
 * quality goes on the products). It keeps working while nobody looks, and survives restarts.
 */
public final class MachineModule extends EcoModule {

    static final int[] INPUTS = {10, 11, 12};
    static final int STATUS = 14, OUTPUT = 16;

    private Map<String, MachineSpec> specs = Map.of();
    private final Map<UUID, Machine> machines = new ConcurrentHashMap<>();
    private Db db;

    public MachineModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-machines";
    }

    @Override
    protected void start() {
        db = core.db();
        db.migrate("eco_machine", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_machine (id CHAR(36) NOT NULL PRIMARY KEY, type VARCHAR(64) NOT NULL, operator CHAR(36), "
                        + "contents " + MailStore.blob(db) + ", progress DOUBLE NOT NULL, world VARCHAR(64), x INT, y INT, z INT)")));
        load();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT id, type, operator, contents, progress FROM eco_machine",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getBytes(4), rs.getDouble(5)}))) {
            MachineSpec spec = specs.get((String) r[1]);
            if (spec == null) continue;
            Machine m = new Machine(UUID.fromString((String) r[0]), spec);
            m.operator = r[2] == null ? null : UUID.fromString((String) r[2]);
            m.progress = (Double) r[4];
            if (r[3] != null) {
                ItemStack[] items = ItemStack.deserializeItemsFromBytes((byte[]) r[3]);
                for (int i = 0; i < INPUTS.length && i < items.length; i++) m.inv.setItem(INPUTS[i], empty(items[i]));
                if (items.length > 3) m.inv.setItem(OUTPUT, empty(items[3]));
            }
            machines.put(m.id, m);
        }
        for (MachineSpec s : specs.values()) {
            core.furniture().registerBehavior("economy:machine/" + s.id(), new Behavior(s));
        }
        every(20L, this::tick);
        every(600L, this::saveDirty);
        eco.onReset(() -> {
            machines.values().forEach(m -> m.inv.clear());
            machines.clear();
            db.execute("DELETE FROM eco_machine");
        });
    }

    private static ItemStack empty(ItemStack it) {
        return it == null || it.isEmpty() ? null : it;
    }

    @Override
    public void reload() {
        load();
        for (MachineSpec s : specs.values()) core.furniture().registerBehavior("economy:machine/" + s.id(), new Behavior(s));
    }

    @Override
    protected void stop() {
        for (Machine m : machines.values()) {
            m.dirty = true;
            for (var v : List.copyOf(m.inv.getViewers())) v.closeInventory();
        }
        saveDirty();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        specs = MachineSpec.parse(eco.data("machines.yml"), problems);
        for (MachineSpec s : specs.values()) {
            if (Items.create(s.item(), 1).isEmpty()) problems.add(s.id() + ": 없는 아이템 " + s.item());
            if (s.job() != null && !eco.jobs().spec().jobs().containsKey(s.job())) problems.add(s.id() + ": 없는 직업 " + s.job());
            for (MachineSpec.Recipe r : s.recipes()) {
                if (Items.create(r.output(), 1).isEmpty()) problems.add(s.id() + ": 없는 결과물 " + r.output());
                for (String in : r.inputs().keySet()) if (Items.create(in, 1).isEmpty()) problems.add(s.id() + ": 없는 재료 " + in);
            }
        }
        problems.forEach(p -> eco.getLogger().warning("[machines.yml] " + p));
        // machines already loaded follow the new recipes
        Map<UUID, Machine> next = new HashMap<>();
        for (Machine m : machines.values()) {
            MachineSpec s = specs.get(m.spec.id());
            if (s != null) next.put(m.id, m.respec(s));
        }
        machines.clear();
        machines.putAll(next);
    }

    public Map<String, MachineSpec> specs() {
        return specs;
    }

    // ------------------------------------------------------------------------------------------

    final class Machine implements InventoryHolder {
        final UUID id;
        MachineSpec spec;
        final Inventory inv;
        UUID operator;
        double progress;
        boolean dirty;
        Location where;

        Machine(UUID id, MachineSpec spec) {
            this.id = id;
            this.spec = spec;
            String title = core.menus().menu("economy:machine").map(m -> m.backgroundPrefix() + m.title()).orElse("<dark_gray>{machine}")
                    .replace("{machine}", spec.name());
            inv = Bukkit.createInventory(this, 27, Text.mm(title));
        }

        Machine respec(MachineSpec s) {
            this.spec = s;
            return this;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }

        Map<String, Integer> have() {
            Map<String, Integer> have = new HashMap<>();
            for (int slot : INPUTS) {
                ItemStack it = inv.getItem(slot);
                if (it != null && !it.isEmpty()) have.merge(Items.key(it), it.getAmount(), Integer::sum);
            }
            return have;
        }

        int operatorLevel() {
            if (operator == null) return 0;
            return spec.job() == null || spec.job().equals(eco.jobs().job(operator)) ? Math.max(1, eco.jobs().level(operator)) : 0;
        }

        MachineSpec.Recipe current() {
            int lv = operatorLevel();
            return lv <= 0 ? null : spec.match(have(), lv);
        }
    }

    private void tick() {
        for (Machine m : machines.values()) {
            MachineSpec.Recipe r = m.current();
            if (r == null) {
                if (m.progress != 0) {
                    m.progress = 0;
                    m.dirty = true;
                }
                drawStatus(m, null);
                continue;
            }
            Items.create(r.output(), r.amount()).ifPresent(out -> {
                ItemStack product = eco.jobs().decorate(out, m.operator);
                ItemStack slot = m.inv.getItem(OUTPUT);
                boolean fits = slot == null || slot.isEmpty() || (slot.isSimilar(product) && slot.getAmount() + product.getAmount() <= slot.getMaxStackSize());
                if (!fits) {
                    drawStatus(m, r);
                    return;
                }
                m.progress += 1;
                m.dirty = true;
                if (m.progress >= r.seconds()) {
                    m.progress = 0;
                    for (Map.Entry<String, Integer> in : r.inputs().entrySet()) take(m, in.getKey(), in.getValue());
                    if (slot == null || slot.isEmpty()) m.inv.setItem(OUTPUT, product);
                    else slot.setAmount(slot.getAmount() + product.getAmount());
                    JobSpec.Product pr = eco.jobs().spec().products().get(r.output());
                    double xp = r.xp() > 0 ? r.xp() : pr == null ? 0 : pr.xp();
                    if (m.operator != null && spec(m).job() != null) eco.jobs().addXp(m.operator, spec(m).job(), xp);
                    for (var v : m.inv.getViewers()) if (v instanceof Player p) p.playSound(p.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.5f, 1.3f);
                }
                drawStatus(m, r);
            });
        }
    }

    private static MachineSpec spec(Machine m) {
        return m.spec;
    }

    private static void take(Machine m, String key, int count) {
        int left = count;
        for (int slot : INPUTS) {
            ItemStack it = m.inv.getItem(slot);
            if (it == null || it.isEmpty() || !Items.key(it).equals(key)) continue;
            int t = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - t);
            m.inv.setItem(slot, it.getAmount() <= 0 ? null : it);
            left -= t;
            if (left <= 0) return;
        }
    }

    private void drawStatus(Machine m, MachineSpec.Recipe r) {
        if (m.inv.getViewers().isEmpty()) return;
        ItemStack it = new ItemStack(r == null ? Material.GRAY_DYE : Material.LIME_DYE);
        int lv = m.operatorLevel();
        it.editMeta(meta -> {
            if (r == null) {
                meta.itemName(Lang.item(m.operator == null || lv == 0 ? "machine.idle-operator" : "machine.idle", "machine", m.spec.name()));
            } else {
                int pct = (int) Math.round(m.progress / r.seconds() * 100);
                meta.itemName(Lang.item("machine.working", "item", Items.mmName(r.output()), "pct", pct,
                        "bar", bar(pct)));
            }
            List<Component> lore = new ArrayList<>(Lang.items("machine.operator", "player", m.operator == null ? "-" : dev.stsv.economy.util.Players.name(m.operator),
                    "level", lv));
            lore.add(Lang.item("machine.recipes-header"));
            for (MachineSpec.Recipe rec : m.spec.recipes()) {
                List<String> ins = new ArrayList<>();
                for (Map.Entry<String, Integer> in : rec.inputs().entrySet()) ins.add(name(in.getKey()) + " ×" + in.getValue());
                lore.add(Lang.item(rec.level() <= Math.max(lv, 0) && lv > 0 ? "machine.recipe" : "machine.recipe-locked",
                        "in", String.join(" + ", ins), "out", name(rec.output()) + (rec.amount() > 1 ? " ×" + rec.amount() : ""),
                        "seconds", (int) rec.seconds(), "level", rec.level()));
            }
            meta.lore(lore);
        });
        m.inv.setItem(STATUS, it);
    }

    private static String bar(int pct) {
        int full = Math.clamp(pct / 10, 0, 10);
        return "<green>" + "|".repeat(full) + "<dark_gray>" + "|".repeat(10 - full);
    }

    private static String name(String id) {
        return Items.mmName(id);
    }

    private void saveDirty() {
        for (Machine m : machines.values()) {
            if (!m.dirty) continue;
            m.dirty = false;
            ItemStack[] items = new ItemStack[4];
            for (int i = 0; i < 3; i++) items[i] = orAir(m.inv.getItem(INPUTS[i]));
            items[3] = orAir(m.inv.getItem(OUTPUT));
            byte[] data = ItemStack.serializeItemsAsBytes(items);
            db.execute(db.upsert("eco_machine", List.of("id"), List.of("type", "operator", "contents", "progress")),
                    m.id.toString(), m.spec.id(), m.operator == null ? null : m.operator.toString(), data, m.progress);
        }
    }

    private static ItemStack orAir(ItemStack it) {
        return it == null ? ItemStack.empty() : it;
    }

    // ------------------------------------------------------------------------------------------
    // Furniture hooks
    // ------------------------------------------------------------------------------------------

    private final class Behavior implements FurnitureBehavior {
        private final MachineSpec spec;

        Behavior(MachineSpec spec) {
            this.spec = spec;
        }

        private Machine of(Furniture f) {
            return machines.computeIfAbsent(f.display().getUniqueId(), id -> {
                Machine m = new Machine(id, specs.getOrDefault(spec.id(), spec));
                m.dirty = true;
                return m;
            });
        }

        @Override
        public void onPlace(Player player, Furniture furniture) {
            Machine m = of(furniture);
            m.operator = spec.job() == null || spec.job().equals(eco.jobs().job(player.getUniqueId())) ? player.getUniqueId() : null;
            m.dirty = true;
        }

        @Override
        public void onInteract(Player player, Furniture furniture) {
            Machine m = of(furniture);
            m.where = furniture.location();
            if (spec.job() != null && !spec.job().equals(eco.jobs().job(player.getUniqueId()))) {
                JobSpec.Job j = eco.jobs().spec().jobs().get(spec.job());
                HudModule.flash(player, Lang.mm("machine.job-only", "color", j == null ? "white" : j.color(), "job", j == null ? spec.job() : j.name()));
                return;
            }
            if (!player.getUniqueId().equals(m.operator)) {
                m.operator = player.getUniqueId();
                m.dirty = true;
            }
            player.openInventory(m.inv);
            drawStatus(m, m.current());
            player.playSound(player.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.6f, 1.2f);
        }

        @Override
        public void onBreak(Player player, Furniture furniture) {
            Machine m = machines.remove(furniture.display().getUniqueId());
            if (m == null) return;
            for (var v : List.copyOf(m.inv.getViewers())) v.closeInventory();
            Location at = furniture.location();
            for (int slot : INPUTS) drop(at, m.inv.getItem(slot));
            drop(at, m.inv.getItem(OUTPUT));
            m.inv.clear();
            db.execute("DELETE FROM eco_machine WHERE id = ?", m.id.toString());
        }

        private void drop(Location at, ItemStack it) {
            if (it != null && !it.isEmpty()) at.getWorld().dropItemNaturally(at, it);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The window: only the input and output slots can be used; nothing can be put into the output
    // ------------------------------------------------------------------------------------------

    private static boolean input(int slot) {
        return slot == INPUTS[0] || slot == INPUTS[1] || slot == INPUTS[2];
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Machine m)) return;
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        m.dirty = true;
        if (e.getClickedInventory() != e.getInventory()) {
            if (e.isShiftClick()) {
                // shift from the player's inventory: only into the input slots
                e.setCancelled(true);
                ItemStack it = e.getCurrentItem();
                if (it == null || it.isEmpty()) return;
                for (int slot : INPUTS) {
                    ItemStack in = m.inv.getItem(slot);
                    if (in == null || in.isEmpty()) {
                        m.inv.setItem(slot, it.clone());
                        e.setCurrentItem(null);
                        return;
                    }
                    if (in.isSimilar(it) && in.getAmount() < in.getMaxStackSize()) {
                        int move = Math.min(it.getAmount(), in.getMaxStackSize() - in.getAmount());
                        in.setAmount(in.getAmount() + move);
                        it.setAmount(it.getAmount() - move);
                        e.setCurrentItem(it.getAmount() <= 0 ? null : it);
                        if (it.getAmount() <= 0) return;
                    }
                }
            }
            return;
        }
        int slot = e.getSlot();
        if (input(slot)) return;
        if (slot == OUTPUT) {
            // take only
            InventoryAction a = e.getAction();
            boolean taking = a == InventoryAction.PICKUP_ALL || a == InventoryAction.PICKUP_HALF || a == InventoryAction.PICKUP_ONE
                    || a == InventoryAction.PICKUP_SOME || a == InventoryAction.MOVE_TO_OTHER_INVENTORY || a == InventoryAction.DROP_ALL_SLOT
                    || a == InventoryAction.DROP_ONE_SLOT || a == InventoryAction.HOTBAR_SWAP && (e.getWhoClicked().getInventory().getItem(e.getHotbarButton()) == null);
            if (!taking) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Machine m)) return;
        for (int raw : e.getRawSlots()) {
            if (raw < 27 && !input(raw)) {
                e.setCancelled(true);
                return;
            }
        }
        m.dirty = true;
    }
}
