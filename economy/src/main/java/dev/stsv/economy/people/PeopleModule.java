package dev.stsv.economy.people;

import dev.stsv.core.data.Db;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.bus.BusSpec;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.profile.PlayerTextures;

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
 * 인물 도감 (README "인물 도감"; people.yml): two separate collections, 일반 인물 and 별난 인물 (the shiny look), one entry a
 * person (a bus passenger kind) in each: per player how often they met them (carried to where they were going), when first,
 * and the places met (stamps). A shiny meeting counts only for the 별난 entry. Unmet people are a "???" silhouette with a
 * hint of where to meet them; intro lines open at people.yml milestones. Completing a collection gives its title (the
 * 별난 one is told to the server). Kept in memory, written in batches (every 30 seconds and on stop) to eco_people (일반)
 * and eco_people_shiny (별난; the old shiny flags were split into it once: {@link PeopleSpec#splitShinyMigration()}).
 */
public final class PeopleModule extends EcoModule {

    static final String MENU = "economy:people";
    /** /도감 (the item codex) and back */
    static final String TO_ITEMS = "people:items", TOGGLE = "people:shiny";   // TOGGLE: the 일반 / 별난 tab switch
    private static final String UNKNOWN_SKIN = "economy:entity/people/unknown";

    private Db db;
    private PeopleSpec spec;
    /** player -> person -> their 일반 인물 entry */
    private final Map<UUID, Map<String, PeopleSpec.Entry>> data = new ConcurrentHashMap<>();
    /** player -> person -> their 별난 인물 entry (a separate collection) */
    private final Map<UUID, Map<String, PeopleSpec.Entry>> shinyData = new ConcurrentHashMap<>();
    /** "player|person|n" (일반) or "player|person|s" (별난) changed since the last write */
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    /** 장면 (합승 대화, bus_scenes.yml): player -> scene id -> when first seen (millis); and "player|scene" not yet written */
    private final Map<UUID, Map<String, Long>> sceneData = new ConcurrentHashMap<>();
    private final Set<String> sceneDirty = ConcurrentHashMap.newKeySet();

    public PeopleModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-people";
    }

    @Override
    protected void start() {
        db = core.db();
        db.migrate("eco_people", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_people (player CHAR(36) NOT NULL, person VARCHAR(64) NOT NULL, met INT NOT NULL, "
                        + "first_at BIGINT NOT NULL, normal_at BIGINT NOT NULL, shiny_at BIGINT NOT NULL, places VARCHAR(255) NOT NULL, "
                        + "PRIMARY KEY (player, person))"),
                // 2: 일반 and 별난 become separate entries (the shiny flag of a row becomes its own row in eco_people_shiny)
                PeopleSpec.splitShinyMigration(),
                // 3: 장면 (합승 대화) a player has seen, shown in the codex under the people in them
                List.of(PeopleSpec.SCENES_TABLE)));
        load();
        readData();
        every(600L, this::flush);
        eco.onReset(() -> {
            data.clear();
            shinyData.clear();
            dirty.clear();
            db.execute("DELETE FROM eco_people");
            db.execute("DELETE FROM eco_people_shiny");
            sceneData.clear();
            sceneDirty.clear();
            db.execute("DELETE FROM eco_people_scenes");
        });
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    protected void stop() {
        flush();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = PeopleSpec.parse(eco.data("people.yml"), problems);
        BusSpec bus = busSpec();
        if (bus != null) problems.addAll(spec.check(bus));
        problems.forEach(p -> eco.getLogger().warning("[people.yml] " + p));
    }

    private void readData() {
        data.clear();
        shinyData.clear();
        read("eco_people", data);
        read("eco_people_shiny", shinyData);
        sceneData.clear();
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT player, scene, first_at FROM eco_people_scenes",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3)}))) {
            sceneData.computeIfAbsent(UUID.fromString((String) r[0]), k -> new ConcurrentHashMap<>()).put((String) r[1], (Long) r[2]);
        }
    }

    private void read(String table, Map<UUID, Map<String, PeopleSpec.Entry>> into) {
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT player, person, met, first_at, places FROM " + table,
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getString(5)}))) {
            PeopleSpec.Entry e = new PeopleSpec.Entry();
            e.met = (Integer) r[2];
            e.first = (Long) r[3];
            e.places.addAll(PeopleSpec.Entry.places((String) r[4]));
            if (e.met > 0) into.computeIfAbsent(UUID.fromString((String) r[0]), k -> new ConcurrentHashMap<>()).put((String) r[1], e);
        }
    }

    /** Writes every changed record (one batch on the database thread). */
    private void flush() {
        flushScenes();
        if (dirty.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>(), shinyRows = new ArrayList<>();
        for (String k : List.copyOf(dirty)) {
            dirty.remove(k);
            String[] parts = k.split("\\|", 3);
            UUID u = UUID.fromString(parts[0]);
            String person = parts[1];
            boolean shiny = parts.length > 2 && parts[2].equals("s");
            PeopleSpec.Entry e = (shiny ? shinyData : data).getOrDefault(u, Map.of()).get(person);
            if (e == null) continue;
            String places = e.placesText();
            if (places.length() > 255) places = places.substring(0, 255);
            // eco_people keeps its old columns: normal_at = first met, shiny_at unused (0)
            if (shiny) shinyRows.add(new Object[]{u.toString(), person, e.met, e.first, places});
            else rows.add(new Object[]{u.toString(), person, e.met, e.first, e.first, 0L, places});
        }
        String sql = db.upsert("eco_people", List.of("player", "person"), List.of("met", "first_at", "normal_at", "shiny_at", "places"));
        String shinySql = db.upsert("eco_people_shiny", List.of("player", "person"), List.of("met", "first_at", "places"));
        db.async(c -> {
            for (Object[] r : rows) Db.update(c, sql, r);
            for (Object[] r : shinyRows) Db.update(c, shinySql, r);
            return null;
        });
    }

    private void flushScenes() {
        if (sceneDirty.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>();
        for (String k : List.copyOf(sceneDirty)) {
            sceneDirty.remove(k);
            String[] parts = k.split("\\|", 2);
            UUID u = UUID.fromString(parts[0]);
            Long at = sceneData.getOrDefault(u, Map.of()).get(parts[1]);
            if (at != null) rows.add(new Object[]{u.toString(), parts[1], at});
        }
        String sql = db.upsert("eco_people_scenes", List.of("player", "scene"), List.of("first_at"));
        db.async(c -> {
            for (Object[] r : rows) Db.update(c, sql, r);
            return null;
        });
    }

    /** The 장면 (합승 대화) a player has seen. */
    public Set<String> scenes(UUID player) {
        return sceneData.getOrDefault(player, Map.of()).keySet();
    }

    /** A driver saw a scene to its end: kept in the codex. True when it is new to them (not on the 길라잡이's trial bus). */
    public boolean sawScene(Player p, String scene) {
        if (eco.guide() != null && eco.guide().active(p)) return false;
        Map<String, Long> mine = sceneData.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
        if (mine.putIfAbsent(scene, System.currentTimeMillis()) != null) return false;
        sceneDirty.add(p.getUniqueId() + "|" + scene);
        return true;
    }

    public PeopleSpec spec() {
        return spec;
    }

    private BusSpec busSpec() {
        return eco.bus() == null ? null : eco.bus().spec();
    }

    /** Everyone in the codex, in bus.yml's order. */
    public List<String> people() {
        BusSpec bus = busSpec();
        return bus == null ? List.of() : spec.people(bus);
    }

    /** A player's 일반 인물 entries. */
    public Map<String, PeopleSpec.Entry> of(UUID player) {
        return data.getOrDefault(player, Map.of());
    }

    /** A player's entries in one collection (별난 인물 if `shiny`). */
    public Map<String, PeopleSpec.Entry> of(UUID player, boolean shiny) {
        return (shiny ? shinyData : data).getOrDefault(player, Map.of());
    }

    // ------------------------------------------------------------------------------------------
    // Meeting someone
    // ------------------------------------------------------------------------------------------

    /**
     * The driver carried this person (a bus kind) where they were going: one more meeting at `place` in the 일반 인물 entry,
     * or, when the passenger was 별난, in the 별난 인물 entry only (separate collections). The first meeting, an intro line
     * opening, a new place and a completed collection are told to them.
     */
    public void met(Player p, String person, boolean shiny, String place) {
        BusSpec bus = busSpec();
        BusSpec.Type t = bus == null ? null : bus.types().get(person);
        if (t == null || !spec.listed(t)) return;
        if (eco.guide() != null && eco.guide().active(p)) return;   // 길라잡이: the trial bus registers nothing
        UUID u = p.getUniqueId();
        Map<String, PeopleSpec.Entry> mine = (shiny ? shinyData : data).computeIfAbsent(u, k -> new ConcurrentHashMap<>());
        PeopleSpec.Entry e = mine.computeIfAbsent(person, k -> new PeopleSpec.Entry());
        PeopleSpec.Meet m = e.meet(spec, System.currentTimeMillis(), shiny, place);
        dirty.add(u + "|" + person + (shiny ? "|s" : "|n"));
        // missions (README "미션"): passengers carried where they were going, and new people (일반 인물) in the codex
        core.profiles().add(u, "people_met", 1);
        if (m.first() && !shiny) core.profiles().add(u, "people_new", 1);
        List<String> all = people();
        String name = shiny ? Lang.raw("people.name-shiny", "name", nameOf(t)) : nameOf(t);
        if (m.first() && shiny) {
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.6f);
            p.sendMessage(Lang.mm("people.new-shiny", "name", nameOf(t), "have", PeopleSpec.count(all, mine), "all", all.size()));
            HudModule.flash(p, Lang.mm("people.new-shiny-flash", "name", nameOf(t)), 3500);
        } else if (m.first()) {
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
            p.sendMessage(Lang.mm("people.new", "name", name, "have", PeopleSpec.count(all, mine), "all", all.size()));
        }
        if (m.opened() >= 0 && !m.first()) {
            p.sendMessage(Lang.mm("people.intro-open", "name", name, "met", e.met));
        }
        if (m.newPlace() && !m.first()) {
            p.sendMessage(Lang.mm("people.new-place", "name", name, "place", spec.places().getOrDefault(place, place)));
        }
        if (m.first() && PeopleSpec.complete(all, mine)) {
            if (shiny) {
                core.profiles().grantTitle(u, spec.titleShiny());
                Bukkit.broadcast(Lang.msg("people.shiny-all-done", "player", Names.mini(p)));
            } else {
                core.profiles().grantTitle(u, spec.titleAll());
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
                p.sendMessage(Lang.mm("people.all-done", "n", all.size()));
            }
        }
    }

    private static String nameOf(BusSpec.Type t) {
        return "<" + t.color() + ">" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(t.name()) + "</" + t.color() + ">";
    }

    // ------------------------------------------------------------------------------------------
    // /도감 인물
    // ------------------------------------------------------------------------------------------

    public void open(Player p) {
        open(p, false);
    }

    /** Opens the 인물 도감 on a tab: 일반 인물, or 별난 인물. */
    private void open(Player p, boolean shiny) {
        core.menus().open(p, MENU, new PeopleMenu(shiny));
    }

    /** A head wearing this skin (a bus.yml skin key, " slim" allowed). */
    static ItemStack head(String skin) {
        ItemStack it = ItemStack.of(Material.PLAYER_HEAD);
        if (skin == null) return it;
        boolean slim = skin.endsWith(" slim");
        String key = slim ? skin.substring(0, skin.length() - 5).trim() : skin.trim();
        try {
            it.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile().skinPatch(sp -> sp.body(Key.key(key))
                    .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC)).build());
        } catch (RuntimeException ignored) {   // a bad key: a plain head
        }
        return it;
    }

    private final class PeopleMenu implements MenuHandler {
        /** the 별난 인물 tab (else 일반 인물) */
        private final boolean shiny;

        PeopleMenu(boolean shiny) {
            this.shiny = shiny;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            List<String> all = people();
            UUID u = v.player().getUniqueId();
            Map<String, String> m = new HashMap<>();
            int normal = PeopleSpec.count(all, of(u, false)), shinyHave = PeopleSpec.count(all, of(u, true));
            m.put("have", String.valueOf(normal));
            m.put("all", String.valueOf(all.size()));
            m.put("shiny", String.valueOf(shinyHave));
            m.put("tab", Lang.raw(shiny ? "people.tab-shiny" : "people.tab-normal"));
            m.put("tab_have", String.valueOf(shiny ? shinyHave : normal));
            return m;
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (base == null) return null;
            List<String> all = people();
            UUID u = v.player().getUniqueId();
            int normal = PeopleSpec.count(all, of(u, false)), shinyHave = PeopleSpec.count(all, of(u, true));
            if (b.action().equals(TOGGLE)) {   // the tab switch: shows both counts, names the other tab
                base.editMeta(m -> {
                    m.itemName(Lang.item(shiny ? "people.switch-to-normal" : "people.switch-to-shiny", "have", shiny ? normal : shinyHave, "all", all.size()));
                    m.lore(Lang.items("people.switch-lore", "normal", normal, "shiny", shinyHave, "all", all.size(),
                            "now", Lang.raw(shiny ? "people.tab-shiny" : "people.tab-normal")));
                    if (shiny) m.setEnchantmentGlintOverride(true);
                });
            }
            if (b.key() == 'I') {
                List<Component> lore = new ArrayList<>();
                lore.add(Lang.item("people.info-count", "have", normal, "shiny", shinyHave, "all", all.size()));
                lore.add(Component.empty());
                lore.addAll(Lang.items("people.info-lore"));
                base.editMeta(m -> m.lore(lore));
            }
            return base;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            Player p = v.player();
            BusSpec bus = busSpec();
            if (bus == null) return List.of();
            Map<String, PeopleSpec.Entry> mine = of(p.getUniqueId(), shiny), other = of(p.getUniqueId(), !shiny);
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd");
            List<ItemStack> out = new ArrayList<>();
            for (String id : people()) {
                BusSpec.Type t = bus.types().get(id);
                PeopleSpec.Entry e = mine.get(id);
                boolean known = e != null && e.registered();
                String skin = !known ? UNKNOWN_SKIN : shiny ? bus.shiny().skin(t) : t.skins().isEmpty() ? null : t.skins().getFirst();
                ItemStack it = head(skin);
                List<Component> lore = new ArrayList<>();
                if (known) {
                    lore.add(Lang.item("people.lore-met", "met", e.met, "date", day.format(new Date(e.first))));
                    PeopleSpec.Entry o = other.get(id);
                    boolean otherKnown = o != null && o.registered();
                    // the other collection's entry, just a mark (it is its own entry there)
                    lore.add(Lang.item(shiny ? "people.lore-other-normal" : "people.lore-other-shiny",
                            "mark", Lang.raw(otherKnown ? (shiny ? "people.seen" : "people.seen-shiny") : "people.unseen")));
                    List<String> stamps = new ArrayList<>();
                    for (String pl : e.places) stamps.add(Lang.raw("people.stamp", "place", spec.places().getOrDefault(pl, pl)));
                    lore.add(Lang.item("people.lore-places", "stamps", String.join(" ", stamps)));
                    lore.add(Component.empty());
                    List<String> intro = spec.intro(id, shiny);
                    int open = Math.min(intro.size(), spec.introsOpen(e.met, shiny));
                    for (int i = 0; i < open; i++) lore.add(Lang.item(shiny && i == 0 ? "people.lore-intro-shiny" : "people.lore-intro", "line", intro.get(i)));
                    int next = spec.nextMilestone(e.met);
                    if (open < intro.size() && next > 0) lore.add(Lang.item("people.lore-intro-locked", "n", next, "left", next - e.met));
                    sceneLore(lore, id, p.getUniqueId());
                } else {
                    lore.add(Lang.item(shiny ? "people.lore-unknown-shiny" : "people.lore-unknown"));
                    lore.add(Lang.item("people.lore-hint", "hint", hint(bus, t)));
                    lore.add(Lang.item(shiny ? "people.lore-how-shiny" : "people.lore-how"));
                }
                // 주민의 부탁 (README "주민의 부탁"): 친밀도, and the 명인 의뢰's regulars
                if (eco.requests() != null) lore.addAll(eco.requests().peopleLines(p.getUniqueId(), id));
                if (eco.store() != null) lore.addAll(eco.store().peopleLines(p.getUniqueId(), id));   // 편의점 단골 메뉴
                String name = known ? (shiny ? Lang.raw("people.name-shiny", "name", nameOf(t)) : nameOf(t))
                        : Lang.raw(shiny ? "people.unknown-name-shiny" : "people.unknown-name");
                boolean glint = known && shiny;
                it.editMeta(meta -> {
                    meta.itemName(Text.item(name));
                    meta.lore(lore);
                    if (glint) meta.setEnchantmentGlintOverride(true);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            if (action.equals(TO_ITEMS)) {
                Bukkit.getScheduler().runTask(eco, () -> eco.codex().open(p));
            } else if (action.equals(TOGGLE)) {   // the other tab (its own title and count)
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, shiny ? 1.0f : 1.6f);
                Bukkit.getScheduler().runTask(eco, () -> open(p, !shiny));
            }
        }
    }

    /** 장면: "함께 본 장면 n/m" and the titles of those seen (up to SCENE_ROWS, then "외 n개"); nothing for one in no scene. */
    private void sceneLore(List<Component> lore, String person, UUID player) {
        if (eco.bus() == null) return;
        PeopleSpec.SceneRows rows = PeopleSpec.sceneRows(eco.bus().scenes(), person, scenes(player), SCENE_ROWS);
        if (rows.all() == 0) return;
        lore.add(Component.empty());
        lore.add(Lang.item("people.lore-scenes", "n", rows.seen(), "all", rows.all()));
        for (String t : rows.titles()) lore.add(Lang.item("people.lore-scene", "title", net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(t)));
        if (rows.more() > 0) lore.add(Lang.item("people.lore-scene-more", "n", rows.more()));
    }

    static final int SCENE_ROWS = 5;

    /** Where to meet them (people.yml hint, else from bus.yml: the stops, hours, months …). */
    private String hint(BusSpec bus, BusSpec.Type t) {
        String own = spec.hints().get(t.id());
        if (own != null) return own;
        List<String> parts = new ArrayList<>();
        for (String[] h : PeopleSpec.hint(t)) {
            String v = h[1];
            if (h[0].equals("tags")) {
                List<String> words = new ArrayList<>();
                for (String tag : v.split(",")) words.add(bus.extra().tags().getOrDefault(tag, tag));
                v = String.join(" · ", words);
            }
            String key = "people.hint-" + h[0];   // people.hint-tags, -hours, -months, -weekend, -market, -pair, -anywhere
            parts.add(Lang.raw(key, "v", v));
        }
        return String.join(Lang.raw("people.hint-and"), parts);
    }

    /** For the item codex's button and the admin: how many people a player registered in each collection (일반, 별난, all). */
    public int[] counts(UUID player) {
        List<String> all = people();
        return new int[]{PeopleSpec.count(all, of(player, false)), PeopleSpec.count(all, of(player, true)), all.size()};
    }
}
