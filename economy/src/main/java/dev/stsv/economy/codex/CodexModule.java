package dev.stsv.economy.codex;

import dev.stsv.core.data.Db;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.fishing.FishSpec;
import dev.stsv.economy.fishing.FishStore;
import dev.stsv.economy.shop.ShopCatalog;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /도감: every item the shop buys (its 판매 categories are the tabs), all shown from the start. An entry
 * is registered by giving one of that item (click the entry, or the item in your own inventory, while
 * the codex is open); a finished tab gives a title, and so do the fish habitats (by registered fish).
 * Fish caught before the codex took items were registered once, when it first started.
 */
public final class CodexModule extends EcoModule {

    static final String MENU = "economy:codex";
    private static final String FISH_TAB = "fish";

    private Db db;
    /** player -> registered item -> when */
    private final Map<UUID, Map<String, Long>> registered = new ConcurrentHashMap<>();

    public CodexModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-codex";
    }

    @Override
    protected void start() {
        db = core.db();
        db.migrate("eco_codex", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_codex (player CHAR(36) NOT NULL, item VARCHAR(128) NOT NULL, at BIGINT NOT NULL, PRIMARY KEY (player, item))")));
        load();
        importCaughtFish();
        eco.onReset(() -> {
            registered.clear();
            db.execute("DELETE FROM eco_codex");
        });
    }

    private void load() {
        registered.clear();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT player, item, at FROM eco_codex",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3)}))) {
            registered.computeIfAbsent(UUID.fromString((String) r[0]), k -> new ConcurrentHashMap<>()).put((String) r[1], (Long) r[2]);
        }
    }

    /** Once: the fish everyone had caught when the codex was by catches become registered. */
    private void importCaughtFish() {
        if (eco.kv().getLong("codex.fish-imported", 0) == 1) return;
        FishSpec spec = eco.fishing().spec();
        FishStore fish = eco.fishing().store();
        int n = 0;
        for (UUID u : fish.players()) {
            for (Map.Entry<String, FishStore.Entry> e : fish.of(u).entrySet()) {
                FishSpec.Species s = spec.species().get(e.getKey());
                if (s == null) continue;
                if (put(u, ShopCatalog.norm(s.item()), e.getValue().first)) n++;
            }
        }
        eco.kv().set("codex.fish-imported", 1);
        if (n > 0) eco.getLogger().info("도감: 예전에 낚은 물고기 " + n + "건을 등록했습니다.");
    }

    private boolean put(UUID player, String item, long at) {
        Map<String, Long> m = registered.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        if (m.putIfAbsent(item, at) != null) return false;
        db.execute("INSERT INTO eco_codex (player, item, at) VALUES (?, ?, ?)", player.toString(), item, at);
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // Entries
    // ------------------------------------------------------------------------------------------

    /** The tabs: the shop's 판매 categories. */
    public List<ShopCatalog.Category> tabs() {
        return eco.shop().catalog().sellCategories();
    }

    private ShopCatalog.Category tab(String id) {
        for (ShopCatalog.Category c : tabs()) if (c.id().equals(id)) return c;
        return tabs().isEmpty() ? null : tabs().getFirst();
    }

    /** Is the item in the codex at all (its tab, or null). */
    public ShopCatalog.Category tabOf(String item) {
        for (ShopCatalog.Category c : tabs()) if (c.items().contains(item)) return c;
        return null;
    }

    public Set<String> registered(UUID player) {
        return registered.getOrDefault(player, Map.of()).keySet();
    }

    /** How many are registered: in one tab, or (null) all of them. */
    public int count(UUID player, String tabId) {
        Set<String> mine = registered(player);
        int n = 0;
        for (ShopCatalog.Category c : tabs()) {
            if (tabId != null && !c.id().equals(tabId)) continue;
            for (String i : c.items()) if (mine.contains(i)) n++;
        }
        return n;
    }

    public int total(String tabId) {
        int n = 0;
        for (ShopCatalog.Category c : tabs()) if (tabId == null || c.id().equals(tabId)) n += c.items().size();
        return n;
    }

    /** Fish species registered (for the fishing achievements and the habitat titles). */
    public int fishSpecies(UUID player) {
        Set<String> mine = registered(player);
        int n = 0;
        for (FishSpec.Species s : eco.fishing().spec().species().values()) if (mine.contains(ShopCatalog.norm(s.item()))) n++;
        return n;
    }

    // ------------------------------------------------------------------------------------------
    // Registering
    // ------------------------------------------------------------------------------------------

    /**
     * Gives one of this item from the player's inventory (the given slot if it is that item, else the smallest of a sized
     * fish) and registers it. Returns the message key of why not, or null.
     */
    String register(Player p, String item, int slot) {
        ShopCatalog.Category c = tabOf(item);
        if (c == null) return "codex.not-in-codex";
        if (registered(p.getUniqueId()).contains(item)) return "codex.already";
        ItemStack taken = null;
        if (slot >= 0) {
            ItemStack at = p.getInventory().getItem(slot);
            if (at != null && item.equals(Items.key(at))) taken = at;
        }
        if (taken == null) {
            // the cheapest of them: a sized fish only when there is no smaller one (a trophy is never given before a small one)
            for (ItemStack it : p.getInventory().getStorageContents()) {
                if (it != null && !it.isEmpty() && item.equals(Items.key(it)) && (taken == null || Items.sizeFactor(it) < Items.sizeFactor(taken))) taken = it;
            }
        }
        if (taken == null) return "codex.missing";
        taken.subtract(1);
        put(p.getUniqueId(), item, System.currentTimeMillis());
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        HudModule.flash(p, Lang.mm("codex.registered", "item", Items.mmName(item), "tab", c.name(),
                "have", count(p.getUniqueId(), c.id()), "all", c.items().size()));
        completed(p, c, item);
        return null;
    }

    /** A tab or a fish habitat just became complete: its title, and the news. */
    private void completed(Player p, ShopCatalog.Category c, String item) {
        UUID u = p.getUniqueId();
        Set<String> mine = registered(u);
        if (mine.containsAll(c.items())) {
            core.profiles().grantTitle(u, "economy:codex_" + c.id());
            Bukkit.broadcast(Lang.msg("codex.tab-done", "player", p.getName(), "tab", c.name()));
        }
        if (tabs().stream().allMatch(t -> mine.containsAll(t.items()))) {
            core.profiles().grantTitle(u, "economy:codex_all");
            Bukkit.broadcast(Lang.msg("codex.all-done", "player", p.getName()));
        }
        FishSpec spec = eco.fishing().spec();
        FishSpec.Species species = spec.species().values().stream().filter(s -> ShopCatalog.norm(s.item()).equals(item)).findFirst().orElse(null);
        if (species == null) return;
        FishSpec.Group grp = spec.groups().get(species.group());
        if (grp != null && spec.species().values().stream().filter(x -> x.group().equals(grp.id()))
                .allMatch(x -> mine.contains(ShopCatalog.norm(x.item())))) {
            if (grp.title() != null) core.profiles().grantTitle(u, grp.title());
            Bukkit.broadcast(Lang.msg("fishing.group-done", "player", p.getName(), "group", grp.name()));
        }
        if (spec.species().values().stream().allMatch(x -> mine.contains(ShopCatalog.norm(x.item())))) {
            core.profiles().grantTitle(u, "economy:master_angler");
            Bukkit.broadcast(Lang.msg("fishing.all-done", "player", p.getName()));
        }
    }

    // ------------------------------------------------------------------------------------------
    // /도감
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        open(p, tabs().isEmpty() ? null : tabs().getFirst().id());
    }

    private void open(Player p, String tabId) {
        core.menus().open(p, MENU, new CodexMenu(tabId));
    }

    private final class CodexMenu implements MenuHandler {
        private final String tabId;

        CodexMenu(String tabId) {
            this.tabId = tabId;
        }

        private List<String> items() {
            ShopCatalog.Category c = tab(tabId);
            return c == null ? List.of() : c.items();
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            ShopCatalog.Category c = tab(tabId);
            Map<String, String> m = new HashMap<>();
            m.put("tab", c == null ? "-" : c.name());
            m.put("have", String.valueOf(c == null ? 0 : count(u, c.id())));
            m.put("all", String.valueOf(c == null ? 0 : c.items().size()));
            m.put("total_have", String.valueOf(count(u, null)));
            m.put("total", String.valueOf(total(null)));
            int ready = 0;
            Set<String> mine = registered(u);
            for (String i : items()) if (!mine.contains(i) && Items.count(v.player().getInventory(), it -> i.equals(Items.key(it))) > 0) ready++;
            m.put("ready", String.valueOf(ready));
            return m;
        }

        /** The open tab glows; the info button lists every tab. */
        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (base == null) return null;
            if (b.action().startsWith("codex:tab:")) {
                String id = b.action().substring("codex:tab:".length());
                UUID u = v.player().getUniqueId();
                if (id.equals(tabId)) base.editMeta(m -> m.setEnchantmentGlintOverride(true));
                ShopCatalog.Category c = tab(id);
                if (c != null && c.id().equals(id)) {
                    base.editMeta(m -> m.lore(List.of(Lang.item("codex.tab-lore", "have", count(u, id), "all", c.items().size()))));
                }
            } else if (b.key() == 'I') {
                UUID u = v.player().getUniqueId();
                List<Component> lore = new ArrayList<>();
                for (ShopCatalog.Category c : tabs()) {
                    int have = count(u, c.id());
                    lore.add(Lang.item(have >= c.items().size() ? "codex.info-tab-done" : "codex.info-tab", "tab", c.name(), "have", have, "all", c.items().size()));
                }
                lore.add(Component.empty());
                lore.addAll(Lang.items("codex.info-lore"));
                base.editMeta(m -> m.lore(lore));
            }
            return base;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            Player p = v.player();
            Map<String, Long> mine = registered.getOrDefault(p.getUniqueId(), Map.of());
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd");
            boolean fishTab = FISH_TAB.equals(tabId);
            List<ItemStack> out = new ArrayList<>();
            for (String id : items()) {
                ItemStack it = Items.create(id, 1).orElse(null);
                if (it == null) {
                    out.add(null);
                    continue;
                }
                Long at = mine.get(id);
                int have = at != null ? 0 : Items.count(p.getInventory(), x -> id.equals(Items.key(x)));
                it.editMeta(meta -> {
                    List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
                    if (!lore.isEmpty()) lore.add(Component.empty());
                    if (fishTab) lore.addAll(fishLines(p.getUniqueId(), id));
                    if (at != null) {
                        lore.add(Lang.item("codex.lore-registered", "date", day.format(new Date(at))));
                    } else {
                        lore.add(Lang.item("codex.lore-missing"));
                        lore.add(have > 0 ? Lang.item("codex.lore-click", "have", have) : Lang.item("codex.lore-get"));
                    }
                    meta.lore(lore);
                    meta.setEnchantmentGlintOverride(at == null && have > 0);
                    if (at == null) meta.itemName(Text.item("<gray>" + Items.mmName(id)));
                });
                out.add(it);
            }
            return out;
        }

        /** A fish: where and when it bites, how big it gets, and your catches. */
        private List<Component> fishLines(UUID u, String item) {
            FishSpec spec = eco.fishing().spec();
            FishSpec.Species s = spec.species().values().stream().filter(x -> ShopCatalog.norm(x.item()).equals(item)).findFirst().orElse(null);
            if (s == null) return List.of();
            FishSpec.Rarity r = spec.rarities().get(s.rarity());
            FishSpec.Group g = spec.groups().get(s.habitat());
            String where = s.habitat().equals("fresh") ? Lang.raw("codex.fresh") : s.habitat().equals("salt") ? Lang.raw("codex.salt")
                    : g == null ? s.habitat() : g.name();
            String when = s.when() == null ? Lang.raw("codex.anytime") : Lang.raw("codex.when." + s.when());
            FishStore.Entry e = eco.fishing().store().of(u).get(s.id());
            return Lang.items(e == null ? "codex.fish-lore" : "codex.fish-lore-caught", "where", where, "when", when,
                    "color", r.color(), "rarity", r.name(), "min", fmt(s.min()), "max", fmt(s.max()),
                    "count", e == null ? 0 : e.count, "best", e == null ? "-" : String.format("%.1f", e.best));
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            List<String> l = items();
            if (index >= l.size()) return;
            String why = register(v.player(), l.get(index), -1);
            if (why != null && !why.equals("codex.already")) feedback(v.player(), why);
            v.refresh();
        }

        /** Clicking one of your own items registers it. */
        @Override
        public void onPlayerInventoryClick(MenuView v, int slot, ClickType click) {
            ItemStack it = v.player().getInventory().getItem(slot);
            if (it == null || it.isEmpty()) return;
            String id = Items.key(it);
            String why = register(v.player(), id, slot);
            if (why != null) feedback(v.player(), why);
            ShopCatalog.Category c = tabOf(id);
            if (why == null && c != null && !c.id().equals(tabId)) {
                Player p = v.player();
                Bukkit.getScheduler().runTask(eco, () -> open(p, c.id()));   // show where it went
            } else {
                v.refresh();
            }
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            if (action.startsWith("codex:tab:")) {
                String id = action.substring("codex:tab:".length());
                if (!id.equals(tabId)) Bukkit.getScheduler().runTask(eco, () -> open(p, id));
            } else if (action.equals("codex:register_all")) {
                int n = 0;
                Set<String> mine = registered(p.getUniqueId());
                for (String id : items()) {
                    if (mine.contains(id)) continue;
                    if (register(p, id, -1) == null) n++;
                }
                HudModule.flash(p, n == 0 ? Lang.mm("codex.nothing-to-register") : Lang.mm("codex.registered-many", "count", n));
                v.refresh();
            }
        }
    }

    private static void feedback(Player p, String key) {
        HudModule.flash(p, Lang.mm(key));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
    }

    private static String fmt(double cm) {
        return cm == Math.floor(cm) ? String.valueOf((long) cm) : String.format("%.1f", cm);
    }
}
