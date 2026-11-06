package dev.stsv.economy.job;

import dev.stsv.core.data.Db;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.furniture.Furniture;
import dev.stsv.core.furniture.FurnitureBehavior;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.mail.MailStore;
import dev.stsv.economy.shop.ShopCatalog;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;

/**
 * Job machines (세공대, 가공대, 손질대, 대장간, 조리대, 증류기): multi-block furniture that turns inputs into
 * job goods over time. Anyone can use one; a person of the machine's job works it faster and gets XP and
 * the job's machine bonuses. Up to four lanes (재료 3칸 + 결과 1칸 each) work side by side, each for
 * whoever last filled it (the operator). The one who placed it upgrades it (job level + gold): more lanes,
 * more speed, new recipes and a new look; picked up, it keeps its tier. Wheels turn and it smokes, steams
 * or sparks only while something is being made. Machines keep working while nobody looks.
 */
public final class MachineModule extends EcoModule {

    static final int SIZE = 54, LANES = 4;
    static final int BOOK = 0, INFO = 4, UPGRADE = 8, COLLECT = 48, CLOSE = 50;
    public static final NamespacedKey TIER = new NamespacedKey("economy", "machine_tier");
    public static final NamespacedKey MAKER = new NamespacedKey("economy", "maker");

    static int input(int lane, int i) {
        return 9 * (lane + 1) + 1 + i;
    }

    static int status(int lane) {
        return 9 * (lane + 1) + 5;
    }

    static int output(int lane) {
        return 9 * (lane + 1) + 7;
    }

    /** Lane of an input slot, or -1. */
    static int inputLane(int slot) {
        int row = slot / 9, col = slot % 9;
        return row >= 1 && row <= LANES && col >= 1 && col <= 3 ? row - 1 : -1;
    }

    static int outputLane(int slot) {
        int row = slot / 9;
        return row >= 1 && row <= LANES && slot % 9 == 7 ? row - 1 : -1;
    }

    static int statusLane(int slot) {
        int row = slot / 9;
        return row >= 1 && row <= LANES && slot % 9 == 5 ? row - 1 : -1;
    }

    /** "#tag" inputs: a machines.yml group (fish kinds) or a vanilla item tag */
    private final BiPredicate<String, String> TAGGED = this::tagged;
    private Map<String, MachineSpec.Group> groups = Map.of();

    private Map<String, MachineSpec> specs = Map.of();
    private MachineSpec.Settings settings = new MachineSpec.Settings(List.of(), List.of(), List.of(1), List.of(0.0), 0.2);
    private Map<String, String> madeOn = Map.of();
    private final Map<UUID, Machine> machines = new ConcurrentHashMap<>();
    private Db db;
    private long ticks;

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
        db.migrate("eco_machines", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_machines (id CHAR(36) NOT NULL PRIMARY KEY, type VARCHAR(64) NOT NULL, tier INT NOT NULL, "
                        + "owner CHAR(36), contents " + MailStore.blob(db) + ", state VARCHAR(1024), world VARCHAR(64), x INT, y INT, z INT)",
                // the old one-block machines are gone with their items
                "DROP TABLE IF EXISTS eco_machine")));
        load();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT id, type, tier, owner, contents, state FROM eco_machines",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getBytes(5), rs.getString(6)}))) {
            MachineSpec spec = specs.get((String) r[1]);
            if (spec == null) continue;
            Machine m = new Machine(UUID.fromString((String) r[0]), spec);
            m.tier = Math.clamp((Integer) r[2], 0, 3);
            m.owner = r[3] == null ? null : UUID.fromString((String) r[3]);
            if (r[4] != null) {
                ItemStack[] items = ItemStack.deserializeItemsFromBytes((byte[]) r[4]);
                for (int k = 0; k < LANES; k++) {
                    for (int i = 0; i < 3; i++) m.inv.setItem(input(k, i), at(items, k * 4 + i));
                    m.inv.setItem(output(k), at(items, k * 4 + 3));
                }
            }
            m.readState((String) r[5]);
            machines.put(m.id, m);
        }
        register();
        every(20L, this::tick);
        every(600L, this::saveDirty);
        eco.onReset(() -> {
            machines.values().forEach(m -> m.inv.clear());
            machines.clear();
            db.execute("DELETE FROM eco_machines");
        });
    }

    private static ItemStack at(ItemStack[] items, int i) {
        return i < items.length && items[i] != null && !items[i].isEmpty() ? items[i] : null;
    }

    private void register() {
        for (MachineSpec s : specs.values()) core.furniture().registerBehavior("economy:machine/" + s.id(), new Behavior(s));
    }

    @Override
    public void reload() {
        load();
        register();
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
        var data = eco.data("machines.yml");
        specs = MachineSpec.parse(data, problems);
        groups = MachineSpec.groups(data, problems);
        for (MachineSpec.Group g : groups.values()) {
            for (String id : g.items()) if (Items.create(id, 1).isEmpty()) problems.add("groups." + g.id() + ": 없는 아이템 " + id);
        }
        settings = MachineSpec.Settings.parse(data);
        Map<String, String> made = new HashMap<>();
        for (MachineSpec s : specs.values()) {
            if (Items.create(s.item(), 1).isEmpty()) problems.add(s.id() + ": 없는 아이템 " + s.item());
            if (s.job() != null && !eco.jobs().spec().jobs().containsKey(s.job())) problems.add(s.id() + ": 없는 직업 " + s.job());
            for (MachineSpec.Recipe r : s.recipes()) {
                if (Items.create(r.output(), 1).isEmpty()) problems.add(s.id() + ": 없는 결과물 " + r.output());
                for (String in : r.inputs().keySet()) {
                    boolean known = in.startsWith("#") ? groups.containsKey(in.substring(1)) || tag(in.substring(1)) != null : Items.create(in, 1).isPresent();
                    if (!known) problems.add(s.id() + ": 없는 재료 " + in);
                }
                made.putIfAbsent(r.output(), s.id());
            }
        }
        madeOn = made;
        problems.forEach(p -> eco.getLogger().warning("[machines.yml] " + p));
        for (Machine m : machines.values()) {
            MachineSpec s = specs.get(m.spec.id());
            if (s != null) m.spec = s;
        }
    }

    public Map<String, MachineSpec> specs() {
        return specs;
    }

    public MachineSpec.Settings settings() {
        return settings;
    }

    /** The machine an item is made on (its first recipe), or null. */
    public String madeOn(String item) {
        return madeOn.get(item);
    }

    private static Tag<Material> tag(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.contains(":") ? name : "minecraft:" + name);
        return key == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
    }

    private boolean tagged(String tag, String item) {
        MachineSpec.Group g = groups.get(tag);
        if (g != null) return g.items().contains(item);
        if (!item.startsWith("minecraft:")) return false;
        Material m = Material.matchMaterial(item);
        Tag<Material> t = tag(tag);
        return m != null && t != null && t.isTagged(m);
    }

    // ------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------

    static final class Lane {
        int recipe = -1;          // chosen recipe index, -1 = 자동
        UUID operator;
        double progress;          // seconds worked on the current batch
        int making = -1;          // recipe index being made (to notice a change)
        boolean blocked;          // the output slot is full
    }

    final class Machine implements InventoryHolder {
        final UUID id;
        MachineSpec spec;
        int tier;
        UUID owner;
        final Inventory inv;
        final Lane[] lanes = new Lane[LANES];
        boolean dirty, working, shownWorking;
        long armedUpgrade;

        Machine(UUID id, MachineSpec spec) {
            this.id = id;
            this.spec = spec;
            for (int k = 0; k < LANES; k++) lanes[k] = new Lane();
            String title = core.menus().menu("economy:machine").map(m -> m.backgroundPrefix() + m.title()).orElse("<dark_gray>{machine}")
                    .replace("{machine}", spec.name());
            inv = Bukkit.createInventory(this, SIZE, Text.mm(title));
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }

        int open() {
            return Math.min(LANES, settings.lanes(tier));
        }

        String state() {
            StringBuilder sb = new StringBuilder();
            for (Lane l : lanes) {
                sb.append(l.recipe).append(',').append(l.operator == null ? "" : l.operator).append(',')
                        .append(String.format(Locale.ROOT, "%.1f", l.progress)).append(';');
            }
            return sb.toString();
        }

        void readState(String s) {
            if (s == null) return;
            String[] ls = s.split(";");
            for (int k = 0; k < LANES && k < ls.length; k++) {
                String[] f = ls[k].split(",", -1);
                if (f.length < 3) continue;
                try {
                    lanes[k].recipe = Integer.parseInt(f[0]);
                    lanes[k].operator = f[1].isEmpty() ? null : UUID.fromString(f[1]);
                    lanes[k].progress = Double.parseDouble(f[2]);
                } catch (IllegalArgumentException ignored) {
                    // a broken lane starts over
                }
            }
        }
    }

    private Map<String, Integer> have(Machine m, int lane) {
        Map<String, Integer> have = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            ItemStack it = m.inv.getItem(input(lane, i));
            if (it != null && !it.isEmpty()) have.merge(Items.key(it), it.getAmount(), Integer::sum);
        }
        return have;
    }

    /** What a lane makes now: its chosen recipe if the inputs are there, else (자동) what the inputs make. */
    private MachineSpec.Recipe recipeFor(Machine m, int lane) {
        Map<String, Integer> have = have(m, lane);
        Lane l = m.lanes[lane];
        if (l.recipe >= 0 && l.recipe < m.spec.recipes().size()) {
            MachineSpec.Recipe r = m.spec.recipes().get(l.recipe);
            return r.tier() <= m.tier && MachineSpec.enough(r, have, TAGGED) ? r : null;
        }
        return m.spec.auto(have, m.tier, TAGGED);
    }

    private UUID operator(Machine m, int lane) {
        UUID op = m.lanes[lane].operator;
        return op != null ? op : m.owner;
    }

    /** Seconds one batch takes: the tier's speed, then the operator's job bonuses (if it is their job). */
    double seconds(Machine m, MachineSpec.Recipe r, UUID op) {
        String job = m.spec.job();
        double t = r.seconds() / (1 + settings.speed(m.tier));
        t *= Math.max(0.1, 1 - eco.jobs().bonus(op, job, "speed"));
        t *= eco.jobs().bonus(op, job, "fast");
        if (r.aging()) t *= eco.jobs().bonus(op, job, "aging");
        return Math.max(1, t);
    }

    private int maxAmount(Machine m, MachineSpec.Recipe r, UUID op) {
        String job = m.spec.job();
        int n = r.amount();
        if (eco.jobs().bonus(op, job, "double") > 0) n *= 2;
        if (eco.jobs().bonus(op, job, "extra") > 0) n += 1;
        return n;
    }

    /**
     * The product with its maker tag ("제작: 이름"). Only the name: a level in the tag changed with every
     * level up, and the same goods from the same maker then no longer stacked.
     */
    private ItemStack product(Machine m, MachineSpec.Recipe r, int amount, UUID op) {
        ItemStack it = Items.create(r.output(), amount).orElse(null);
        if (it == null || op == null) return it;
        String name = Players.name(op);
        it.editMeta(meta -> {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Lang.item("machine.maker", "player", name));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(MAKER, PersistentDataType.STRING, op.toString());
        });
        return it;
    }

    private static final String MAKER_PREFIX = "제작: ";

    /**
     * Goods made before the tag lost its level ("제작: 이름 (광부 Lv12)") get the name-only tag, so they stack
     * with new ones. Returns true if the item was changed.
     */
    static boolean normalizeMaker(ItemStack it) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return false;
        var meta = it.getItemMeta();
        if (!meta.getPersistentDataContainer().has(MAKER, PersistentDataType.STRING) || !meta.hasLore()) return false;
        List<Component> lore = new ArrayList<>(meta.lore());
        boolean changed = false;
        for (int i = 0; i < lore.size(); i++) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(lore.get(i));
            if (!plain.startsWith(MAKER_PREFIX) || !plain.endsWith(")") || !plain.contains(" Lv")) continue;
            int cut = plain.lastIndexOf(" (");
            if (cut <= MAKER_PREFIX.length()) continue;
            lore.set(i, Lang.item("machine.maker", "player", plain.substring(MAKER_PREFIX.length(), cut)));
            changed = true;
        }
        if (!changed) return false;
        meta.lore(lore);
        it.setItemMeta(meta);
        return true;
    }

    private static void normalizeMakers(org.bukkit.inventory.Inventory inv) {
        for (ItemStack it : inv.getContents()) normalizeMaker(it);
    }

    @org.bukkit.event.EventHandler
    public void onJoinMakers(org.bukkit.event.player.PlayerJoinEvent e) {
        normalizeMakers(e.getPlayer().getInventory());
        normalizeMakers(e.getPlayer().getEnderChest());
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onOpenMakers(org.bukkit.event.inventory.InventoryOpenEvent e) {
        normalizeMakers(e.getInventory());
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onPickupMakers(org.bukkit.event.entity.EntityPickupItemEvent e) {
        ItemStack it = e.getItem().getItemStack();
        if (normalizeMaker(it)) e.getItem().setItemStack(it);
    }

    /** Shop base price of an item (0 if the shop does not buy it): what machine XP is counted from. */
    private double value(String item) {
        ShopCatalog.SellEntry e = eco.shop().catalog().sell().get(item);
        return e == null ? 0 : e.base();
    }

    // ------------------------------------------------------------------------------------------
    // Working
    // ------------------------------------------------------------------------------------------

    private void tick() {
        ticks++;
        for (Machine m : machines.values()) {
            boolean any = false;
            for (int k = 0; k < LANES; k++) {
                Lane l = m.lanes[k];
                MachineSpec.Recipe r = k < m.open() ? recipeFor(m, k) : null;
                int idx = r == null ? -1 : r.index();
                if (idx != l.making) {
                    l.making = idx;
                    if (l.progress != 0) {
                        l.progress = 0;
                        m.dirty = true;
                    }
                }
                if (r == null) {
                    l.blocked = false;
                    continue;
                }
                UUID op = operator(m, k);
                ItemStack proto = product(m, r, 1, op);
                if (proto == null) continue;
                ItemStack slot = m.inv.getItem(output(k));
                l.blocked = !(slot == null || slot.isEmpty()
                        || (slot.isSimilar(proto) && slot.getAmount() + maxAmount(m, r, op) <= slot.getMaxStackSize()));
                if (l.blocked) continue;
                any = true;
                l.progress += 1;
                m.dirty = true;
                if (l.progress >= seconds(m, r, op)) {
                    l.progress = 0;
                    complete(m, k, r, op);
                }
            }
            m.working = any;
            if (!m.inv.getViewers().isEmpty()) draw(m);
            animate(m);
        }
    }

    private void complete(Machine m, int lane, MachineSpec.Recipe r, UUID op) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String job = m.spec.job();
        List<String> bonuses = new ArrayList<>();
        // 재료 절약: one unit of one input stays
        String saved = null;
        double save = eco.jobs().bonus(op, job, "save") + (op == null || job == null ? 0 : eco.jobs().effect(op, job, "save"));
        if (save > 0 && rnd.nextDouble() < save) {
            List<String> keys = new ArrayList<>(r.inputs().keySet());
            saved = keys.get(rnd.nextInt(keys.size()));
            bonuses.add(Lang.raw("machine.bonus-save"));
        }
        for (Map.Entry<String, Integer> in : r.inputs().entrySet()) take(m, lane, in.getKey(), in.getValue() - (in.getKey().equals(saved) ? 1 : 0));
        int amount = r.amount();
        if (rnd.nextDouble() < eco.jobs().bonus(op, job, "double")) {
            amount *= 2;
            bonuses.add(Lang.raw("machine.bonus-double"));
        }
        if (rnd.nextDouble() < eco.jobs().bonus(op, job, "extra")) {
            amount += 1;
            bonuses.add(Lang.raw("machine.bonus-extra"));
        }
        ItemStack product = product(m, r, amount, op);
        if (product == null) return;
        ItemStack slot = m.inv.getItem(output(lane));
        if (slot == null || slot.isEmpty()) m.inv.setItem(output(lane), product);
        else slot.setAmount(Math.min(slot.getMaxStackSize(), slot.getAmount() + product.getAmount()));
        if (op != null && job != null) eco.jobs().addXp(op, job, value(r.output()) * amount * settings.xpPerGold());
        if (op != null) core.profiles().add(op, "machine_made", amount);
        m.dirty = true;
        for (var v : m.inv.getViewers()) if (v instanceof Player p) p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
        Player p = op == null ? null : Bukkit.getPlayer(op);
        if (p != null && !bonuses.isEmpty()) HudModule.flash(p, Lang.mm("machine.bonus", "item", Items.mmName(product), "what", String.join(", ", bonuses)));
    }

    private void take(Machine m, int lane, String key, int count) {
        int left = count;
        for (int i = 0; i < 3 && left > 0; i++) {
            ItemStack it = m.inv.getItem(input(lane, i));
            if (it == null || it.isEmpty() || !MachineSpec.fits(key, Items.key(it), TAGGED)) continue;
            int t = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - t);
            m.inv.setItem(input(lane, i), it.getAmount() <= 0 ? null : it);
            left -= t;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Looks: tier models, spinning parts, smoke and sound while working
    // ------------------------------------------------------------------------------------------

    private NamespacedKey model(String name, int tier) {
        return new NamespacedKey("economy", "model/" + name + "_" + tier);
    }

    private Furniture furniture(Machine m) {
        Entity e = Bukkit.getEntity(m.id);
        return e instanceof ItemDisplay d ? core.furniture().fromDisplay(d).orElse(null) : null;
    }

    /** Shows the machine's tier on the model and its parts (loaded machines only). */
    private void applyLooks(Machine m) {
        Furniture f = furniture(m);
        if (f == null) return;
        core.furniture().setModel(f, model(m.spec.model(), m.tier));
        List<ItemDisplay> parts = core.furniture().parts(f);
        for (int i = 0; i < parts.size() && i < m.spec.parts().size(); i++) {
            core.furniture().setPartModel(parts.get(i), model(m.spec.parts().get(i), m.tier));
        }
    }

    private void animate(Machine m) {
        boolean changed = m.working != m.shownWorking;
        boolean effect = m.working && m.spec.effect() != null && ticks % 2 == 0;
        if (!changed && !effect && ticks % 10 != 0) return;
        Furniture f = furniture(m);
        if (f == null) return;
        if (changed || ticks % 10 == 0) {
            for (ItemDisplay part : core.furniture().parts(f)) core.furniture().setSpinning(part, m.working);
            m.shownWorking = m.working;
        }
        if (!effect) return;
        MachineSpec.Effect fx = m.spec.effect();
        Location at = core.furniture().modelPoint(f, new Vector3f((float) fx.at()[0], (float) fx.at()[1], (float) fx.at()[2]));
        if (fx.particle() != null) {
            try {
                Particle particle = Particle.valueOf(fx.particle().toUpperCase(Locale.ROOT));
                at.getWorld().spawnParticle(particle, at, 3, 0.12, 0.08, 0.12, 0.01);
            } catch (IllegalArgumentException ignored) {
                // a particle that needs data or does not exist: no effect
            }
        }
        if (fx.sound() != null && ticks % 4 == 0) at.getWorld().playSound(at, fx.sound(), SoundCategory.BLOCKS, fx.volume(), fx.pitch());
    }

    // ------------------------------------------------------------------------------------------
    // The window
    // ------------------------------------------------------------------------------------------

    private static ItemStack icon(Material material, String model, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(material);
        it.editMeta(meta -> {
            meta.itemName(name);
            if (lore != null) meta.lore(lore);
            if (model != null) meta.setItemModel(NamespacedKey.fromString(model));
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        });
        return it;
    }

    private String tierName(int tier) {
        return Lang.raw("machine.tier." + tier);
    }

    private static String bar(int pct) {
        int full = Math.clamp(pct / 10, 0, 10);
        return "<green>" + "|".repeat(full) + "<dark_gray>" + "|".repeat(10 - full);
    }

    static String time(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        if (s >= 3600) return String.format("%d시간 %d분", s / 3600, s % 3600 / 60);
        if (s >= 60) return s % 60 == 0 ? (s / 60) + "분" : String.format("%d분 %d초", s / 60, s % 60);
        return s + "초";
    }

    private String inputsText(MachineSpec.Recipe r) {
        List<String> ins = new ArrayList<>();
        for (Map.Entry<String, Integer> in : r.inputs().entrySet()) {
            String key = in.getKey();
            MachineSpec.Group g = key.startsWith("#") ? groups.get(key.substring(1)) : null;
            String name = g != null ? Lang.raw("machine.group", "group", g.name())
                    : key.startsWith("#") ? Lang.raw("machine.tag." + key.substring(1).replace(':', '.')) : Items.mmName(key);
            ins.add(name + "<gray> ×" + in.getValue());
        }
        return String.join("<gray> + ", ins);
    }

    /** For each group input of a recipe: which items count, a few names per line. */
    private List<Component> groupLines(MachineSpec.Recipe r) {
        List<Component> out = new ArrayList<>();
        for (String key : r.inputs().keySet()) {
            MachineSpec.Group g = key.startsWith("#") ? groups.get(key.substring(1)) : null;
            if (g == null) continue;
            List<String> names = g.items().stream().map(Items::mmName).toList();
            for (int i = 0; i < names.size(); i += 5) {
                String chunk = String.join("<dark_gray>, ", names.subList(i, Math.min(names.size(), i + 5)));
                out.add(Lang.item(i == 0 ? "machine.group-items" : "machine.group-items-more", "group", g.name(), "items", chunk));
            }
        }
        return out;
    }

    public Map<String, MachineSpec.Group> groups() {
        return groups;
    }

    private JobSpec.Job jobOf(Machine m) {
        return m.spec.job() == null ? null : eco.jobs().spec().jobs().get(m.spec.job());
    }

    private void draw(Machine m) {
        JobSpec.Job j = jobOf(m);
        String jc = j == null ? "white" : j.color(), jn = j == null ? "-" : j.name();
        m.inv.setItem(BOOK, icon(Material.BOOK, "economy:gui/ledger", Lang.item("machine.book"), Lang.items("machine.book-lore")));
        m.inv.setItem(INFO, icon(Material.CRAFTING_TABLE, "stsv:gui/info",
                Lang.item("machine.info", "machine", m.spec.name(), "tier", tierName(m.tier)),
                Lang.items("machine.info-lore", "owner", m.owner == null ? "-" : Players.name(m.owner), "lanes", m.open(),
                        "speed", Math.round(settings.speed(m.tier) * 100), "color", jc, "job", jn)));
        int next = m.tier + 1;
        if (next > settings.maxTier()) {
            m.inv.setItem(UPGRADE, icon(Material.NETHER_STAR, "economy:gui/crown", Lang.item("machine.upgrade-max"), null));
        } else {
            long recipes = m.spec.recipes().stream().filter(r -> r.tier() == next).count();
            List<Component> lore = new ArrayList<>(Lang.items("machine.upgrade-lore", "cost", EconomyModule.format(settings.cost(next)),
                    "color", jc, "job", jn, "level", settings.level(next), "lanes", settings.lanes(next),
                    "speed", Math.round(settings.speed(next) * 100), "recipes", recipes));
            boolean armed = System.currentTimeMillis() - m.armedUpgrade < 5000;
            lore.addAll(Lang.items(armed ? "machine.upgrade-confirm" : "machine.upgrade-click"));
            ItemStack up = icon(Material.ANVIL, "economy:gui/hammer", Lang.item("machine.upgrade", "tier", tierName(next)), lore);
            if (armed) up.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(UPGRADE, up);
        }
        m.inv.setItem(COLLECT, icon(Material.CHEST, "economy:gui/chest", Lang.item("machine.collect"), null));
        m.inv.setItem(CLOSE, icon(Material.OAK_DOOR, "stsv:gui/exit", Lang.item("machine.close"), null));
        for (int k = 0; k < LANES; k++) m.inv.setItem(status(k), laneIcon(m, k));
    }

    private ItemStack laneIcon(Machine m, int k) {
        if (k >= m.open()) {
            int need = 0;
            while (need < 3 && settings.lanes(need) <= k) need++;
            return icon(Material.BARRIER, null, Lang.item("machine.lane-locked", "n", k + 1, "tier", tierName(need)), null);
        }
        Lane l = m.lanes[k];
        UUID op = operator(m, k);
        List<Component> lore = new ArrayList<>();
        MachineSpec.Recipe chosen = l.recipe >= 0 && l.recipe < m.spec.recipes().size() ? m.spec.recipes().get(l.recipe) : null;
        lore.add(chosen == null ? Lang.item("machine.lane-auto")
                : Lang.item("machine.lane-fixed", "item", Items.mmName(chosen.output())));
        MachineSpec.Recipe r = l.making >= 0 && l.making < m.spec.recipes().size() ? m.spec.recipes().get(l.making) : null;
        ItemStack it;
        if (r != null) {
            double need = seconds(m, r, op);
            int pct = (int) Math.round(l.progress / need * 100);
            it = Items.create(r.output(), r.amount()).orElse(new ItemStack(Material.LIME_DYE));
            Component name = l.blocked ? Lang.item("machine.lane-blocked", "n", k + 1)
                    : Lang.item("machine.lane-working", "n", k + 1, "item", Items.mmName(r.output()), "bar", bar(pct), "pct", pct);
            lore.add(Lang.item("machine.lane-left", "time", time(need - l.progress), "total", time(need)));
            if (op != null) lore.add(Lang.item("machine.lane-operator", "player", Players.name(op)));
            lore.addAll(Lang.items("machine.lane-click"));
            it.editMeta(meta -> {
                meta.itemName(name);
                meta.lore(lore);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            });
            return it;
        }
        if (chosen != null) {
            lore.add(Lang.item("machine.lane-needs", "in", inputsText(chosen)));
            if (chosen.tier() > m.tier) lore.add(Lang.item("machine.recipe-locked", "tier", tierName(chosen.tier())));
        } else {
            lore.add(Lang.item("machine.lane-auto-help"));
        }
        lore.addAll(Lang.items("machine.lane-click"));
        return icon(Material.HOPPER, null, Lang.item("machine.lane-idle", "n", k + 1), lore);
    }

    private void open(Player p, Machine m) {
        draw(m);
        p.openInventory(m.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.6f, 1.2f);
    }

    private void collect(Player p, Machine m) {
        int moved = 0;
        for (int k = 0; k < m.open(); k++) {
            ItemStack out = m.inv.getItem(output(k));
            if (out == null || out.isEmpty()) continue;
            int before = out.getAmount();
            Map<Integer, ItemStack> left = p.getInventory().addItem(out.clone());
            int rest = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            moved += before - rest;
            if (rest <= 0) m.inv.setItem(output(k), null);
            else out.setAmount(rest);
        }
        m.dirty = true;
        if (moved > 0) p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1f);
        HudModule.flash(p, Lang.mm(moved > 0 ? "machine.collected" : "machine.nothing", "count", moved));
    }

    private void upgrade(Player p, Machine m) {
        int next = m.tier + 1;
        if (next > settings.maxTier()) {
            HudModule.flash(p, Lang.mm("machine.upgrade-max"));
            return;
        }
        boolean admin = p.hasPermission("stsv.economy.admin");
        if (!p.getUniqueId().equals(m.owner) && !admin) {
            HudModule.flash(p, Lang.mm("machine.owner-only", "player", m.owner == null ? "-" : Players.name(m.owner)));
            return;
        }
        JobSpec.Job j = jobOf(m);
        int need = settings.level(next);
        if (j != null && eco.jobs().level(p.getUniqueId(), j.id()) < need && !admin) {
            HudModule.flash(p, Lang.mm("machine.need-level", "color", j.color(), "job", j.name(), "level", need, "tier", tierName(next)));
            return;
        }
        long cost = settings.cost(next);
        long nowMs = System.currentTimeMillis();
        if (nowMs - m.armedUpgrade > 5000) {
            m.armedUpgrade = nowMs;
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            draw(m);
            return;
        }
        m.armedUpgrade = 0;
        if (!core.economy().withdraw(p.getUniqueId(), cost, "machine_upgrade")) {
            HudModule.flash(p, Lang.mm("machine.not-enough", "cost", EconomyModule.format(cost)));
            return;
        }
        m.tier = next;
        m.dirty = true;
        applyLooks(m);
        p.sendMessage(Lang.msg("machine.upgraded", "machine", m.spec.name(), "tier", tierName(next), "lanes", m.open(),
                "speed", Math.round(settings.speed(next) * 100)));
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.1f);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        Furniture f = furniture(m);
        if (f != null) f.location().getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, f.location().add(0, 0.8, 0), 40, 0.6, 0.6, 0.6, 0.2);
        draw(m);
    }

    // ------------------------------------------------------------------------------------------
    // Recipe list (pick what a lane makes)
    // ------------------------------------------------------------------------------------------

    private void openRecipes(Player p, Machine m, int lane) {
        core.menus().open(p, "economy:machine_recipes", new RecipeMenu(m, lane));
    }

    private final class RecipeMenu implements MenuHandler {
        private final Machine m;
        private final int lane;

        RecipeMenu(Machine m, int lane) {
            this.m = m;
            this.lane = lane;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            return Map.of("machine", m.spec.name(), "lane", lane < 0 ? "-" : String.valueOf(lane + 1), "tier", tierName(m.tier));
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            int current = lane >= 0 ? m.lanes[lane].recipe : -2;
            if (lane >= 0) {
                List<Component> lore = new ArrayList<>(Lang.items("machine.auto-lore"));
                if (current < 0) lore.add(Lang.item("machine.recipe-current"));
                ItemStack auto = icon(Material.HOPPER, null, Lang.item("machine.auto"), lore);
                if (current < 0) auto.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
                out.add(auto);
            }
            UUID u = v.player().getUniqueId();
            for (MachineSpec.Recipe r : m.spec.recipes()) {
                ItemStack it = Items.create(r.output(), r.amount()).orElse(new ItemStack(Material.PAPER));
                boolean locked = r.tier() > m.tier;
                List<Component> lore = new ArrayList<>();
                lore.add(Lang.item("machine.recipe-in", "in", inputsText(r)));
                lore.addAll(groupLines(r));
                lore.add(Lang.item(r.aging() ? "machine.recipe-time-aging" : "machine.recipe-time", "time", time(seconds(m, r, u))));
                double val = value(r.output());
                if (val > 0) lore.add(Lang.item("machine.recipe-value", "value", String.format("%,.0f", val)));
                if (locked) lore.add(Lang.item("machine.recipe-locked", "tier", tierName(r.tier())));
                else if (r.index() == current) lore.add(Lang.item("machine.recipe-current"));
                it.editMeta(meta -> {
                    meta.lore(lore);
                    meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
                    if (r.index() == current) meta.setEnchantmentGlintOverride(true);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            Player p = v.player();
            if (lane < 0 || !machines.containsKey(m.id) || lane >= m.open()) return;
            int ri = index - 1;
            if (ri >= m.spec.recipes().size()) return;
            if (ri >= 0 && m.spec.recipes().get(ri).tier() > m.tier) {
                HudModule.flash(p, Lang.mm("machine.recipe-locked", "tier", tierName(m.spec.recipes().get(ri).tier())));
                return;
            }
            m.lanes[lane].recipe = Math.max(-1, ri);
            m.lanes[lane].operator = p.getUniqueId();
            m.dirty = true;
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            back(p);
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (action.equals("machine:back")) back(v.player());
        }

        private void back(Player p) {
            Bukkit.getScheduler().runTask(eco, () -> {
                if (machines.containsKey(m.id) && p.isOnline()) open(p, m);
                else p.closeInventory();
            });
        }
    }

    // ------------------------------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------------------------------

    private void saveDirty() {
        for (Machine m : machines.values()) {
            if (!m.dirty) continue;
            m.dirty = false;
            ItemStack[] items = new ItemStack[LANES * 4];
            for (int k = 0; k < LANES; k++) {
                for (int i = 0; i < 3; i++) items[k * 4 + i] = orAir(m.inv.getItem(input(k, i)));
                items[k * 4 + 3] = orAir(m.inv.getItem(output(k)));
            }
            db.execute(db.upsert("eco_machines", List.of("id"), List.of("type", "tier", "owner", "contents", "state")),
                    m.id.toString(), m.spec.id(), m.tier, m.owner == null ? null : m.owner.toString(),
                    ItemStack.serializeItemsAsBytes(items), m.state());
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

        private Machine of(Furniture furniture) {
            return machines.computeIfAbsent(furniture.display().getUniqueId(), id -> {
                Machine m = new Machine(id, specs.getOrDefault(spec.id(), spec));
                m.dirty = true;
                return m;
            });
        }

        @Override
        public void onPlace(Player player, Furniture furniture, ItemStack from) {
            Machine m = of(furniture);
            m.owner = player.getUniqueId();
            Integer tier = from == null || !from.hasItemMeta() ? null
                    : from.getItemMeta().getPersistentDataContainer().get(TIER, PersistentDataType.INTEGER);
            m.tier = tier == null ? 0 : Math.clamp(tier, 0, settings.maxTier());
            m.dirty = true;
            applyLooks(m);
            for (ItemDisplay part : core.furniture().parts(furniture)) core.furniture().setSpinning(part, false);
        }

        @Override
        public void onInteract(Player player, Furniture furniture) {
            open(player, of(furniture));
        }

        /** Picked up: the item keeps the tier (and shows its look). */
        @Override
        public ItemStack drop(Furniture furniture, ItemStack item) {
            Machine m = machines.get(furniture.display().getUniqueId());
            if (m == null || m.tier <= 0 || item == null) return item;
            int tier = m.tier;
            item.editMeta(meta -> {
                meta.getPersistentDataContainer().set(TIER, PersistentDataType.INTEGER, tier);
                List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Lang.item("machine.tier-lore", "tier", tierName(tier)));
                meta.lore(lore);
                meta.setItemModel(model(m.spec.model(), tier));
            });
            return item;
        }

        @Override
        public void onBreak(Player player, Furniture furniture) {
            Machine m = machines.remove(furniture.display().getUniqueId());
            if (m == null) return;
            for (var v : List.copyOf(m.inv.getViewers())) v.closeInventory();
            Location at = furniture.location();
            for (int k = 0; k < LANES; k++) {
                for (int i = 0; i < 3; i++) drop(at, m.inv.getItem(input(k, i)));
                drop(at, m.inv.getItem(output(k)));
            }
            m.inv.clear();
            db.execute("DELETE FROM eco_machines WHERE id = ?", m.id.toString());
        }

        private void drop(Location at, ItemStack it) {
            if (it != null && !it.isEmpty()) at.getWorld().dropItemNaturally(at, it);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Clicks: inputs of open lanes take anything, outputs only give, the rest are buttons
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Machine m) || !(e.getWhoClicked() instanceof Player p)) return;
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        m.dirty = true;
        if (e.getClickedInventory() != e.getInventory()) {
            if (e.isShiftClick()) {
                e.setCancelled(true);
                shiftIn(p, m, e);
            }
            return;
        }
        int slot = e.getSlot();
        int in = inputLane(slot);
        if (in >= 0) {
            if (in >= m.open()) e.setCancelled(true);
            else m.lanes[in].operator = p.getUniqueId();
            return;
        }
        int out = outputLane(slot);
        if (out >= 0) {
            InventoryAction a = e.getAction();
            boolean taking = a == InventoryAction.PICKUP_ALL || a == InventoryAction.PICKUP_HALF || a == InventoryAction.PICKUP_ONE
                    || a == InventoryAction.PICKUP_SOME || a == InventoryAction.MOVE_TO_OTHER_INVENTORY || a == InventoryAction.DROP_ALL_SLOT
                    || a == InventoryAction.DROP_ONE_SLOT || a == InventoryAction.HOTBAR_SWAP && (p.getInventory().getItem(e.getHotbarButton()) == null);
            if (!taking || out >= m.open()) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        int st = statusLane(slot);
        if (st >= 0) {
            if (st >= m.open()) return;
            if (e.isRightClick()) {
                m.lanes[st].recipe = -1;
                HudModule.flash(p, Lang.mm("machine.set-auto", "n", st + 1));
                draw(m);
            } else {
                openRecipes(p, m, st);
            }
            return;
        }
        switch (slot) {
            case BOOK -> openRecipes(p, m, -1);
            case UPGRADE -> upgrade(p, m);
            case COLLECT -> collect(p, m);
            case CLOSE -> Bukkit.getScheduler().runTask(eco, () -> p.closeInventory());
            default -> { }
        }
    }

    /** Shift-click from the player's inventory: onto the same item in an open lane, else into the first free input slot. */
    private void shiftIn(Player p, Machine m, InventoryClickEvent e) {
        ItemStack it = e.getCurrentItem();
        if (it == null || it.isEmpty()) return;
        for (int pass = 0; pass < 2 && !it.isEmpty(); pass++) {
            for (int k = 0; k < m.open() && !it.isEmpty(); k++) {
                for (int i = 0; i < 3 && !it.isEmpty(); i++) {
                    int slot = input(k, i);
                    ItemStack in = m.inv.getItem(slot);
                    if (pass == 0 && in != null && !in.isEmpty() && in.isSimilar(it) && in.getAmount() < in.getMaxStackSize()) {
                        int move = Math.min(it.getAmount(), in.getMaxStackSize() - in.getAmount());
                        in.setAmount(in.getAmount() + move);
                        it.setAmount(it.getAmount() - move);
                        m.lanes[k].operator = p.getUniqueId();
                    } else if (pass == 1 && (in == null || in.isEmpty())) {
                        m.inv.setItem(slot, it.clone());
                        it.setAmount(0);
                        m.lanes[k].operator = p.getUniqueId();
                    }
                }
            }
        }
        e.setCurrentItem(it.isEmpty() ? null : it);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Machine m)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= SIZE) continue;
            int lane = inputLane(raw);
            if (lane < 0 || lane >= m.open()) {
                e.setCancelled(true);
                return;
            }
        }
        for (int raw : e.getRawSlots()) {
            int lane = raw < SIZE ? inputLane(raw) : -1;
            if (lane >= 0) m.lanes[lane].operator = e.getWhoClicked().getUniqueId();
        }
        m.dirty = true;
    }
}
