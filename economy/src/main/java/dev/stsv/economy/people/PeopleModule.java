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
 * 인물 도감 (README "인물 도감"; people.yml): one entry a person (a bus passenger kind), per player how often they met them
 * (carried to where they were going), when first, their normal and 별난 looks registered, and the places met (stamps).
 * Unmet people are a "???" silhouette with a hint of where to meet them; intro lines open at people.yml milestones.
 * Completing every normal look or every shiny look gives a title (the shiny one is told to the server). Kept in memory,
 * written to eco_people in batches (every 30 seconds and on stop).
 */
public final class PeopleModule extends EcoModule {

    static final String MENU = "economy:people";
    /** /도감 (the item codex) and back */
    static final String TO_ITEMS = "people:items", TOGGLE = "people:shiny";
    private static final String UNKNOWN_SKIN = "economy:entity/people/unknown";

    private Db db;
    private PeopleSpec spec;
    /** player -> person -> their record */
    private final Map<UUID, Map<String, PeopleSpec.Entry>> data = new ConcurrentHashMap<>();
    /** "player|person" changed since the last write */
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

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
                        + "PRIMARY KEY (player, person))")));
        load();
        readData();
        every(600L, this::flush);
        eco.onReset(() -> {
            data.clear();
            dirty.clear();
            db.execute("DELETE FROM eco_people");
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
        for (Object[] r : db.sync(c -> Db.query(c, "SELECT player, person, met, first_at, normal_at, shiny_at, places FROM eco_people",
                rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getString(7)}))) {
            PeopleSpec.Entry e = new PeopleSpec.Entry();
            e.met = (Integer) r[2];
            e.first = (Long) r[3];
            e.normal = (Long) r[4];
            e.shiny = (Long) r[5];
            e.places.addAll(PeopleSpec.Entry.places((String) r[6]));
            data.computeIfAbsent(UUID.fromString((String) r[0]), k -> new ConcurrentHashMap<>()).put((String) r[1], e);
        }
    }

    /** Writes every changed record (one batch on the database thread). */
    private void flush() {
        if (dirty.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>();
        for (String k : List.copyOf(dirty)) {
            dirty.remove(k);
            int bar = k.indexOf('|');
            UUID u = UUID.fromString(k.substring(0, bar));
            String person = k.substring(bar + 1);
            PeopleSpec.Entry e = data.getOrDefault(u, Map.of()).get(person);
            if (e == null) continue;
            String places = e.placesText();
            rows.add(new Object[]{u.toString(), person, e.met, e.first, e.normal, e.shiny, places.length() > 255 ? places.substring(0, 255) : places});
        }
        String sql = db.upsert("eco_people", List.of("player", "person"), List.of("met", "first_at", "normal_at", "shiny_at", "places"));
        db.async(c -> {
            for (Object[] r : rows) Db.update(c, sql, r);
            return null;
        });
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

    public Map<String, PeopleSpec.Entry> of(UUID player) {
        return data.getOrDefault(player, Map.of());
    }

    // ------------------------------------------------------------------------------------------
    // Meeting someone
    // ------------------------------------------------------------------------------------------

    /**
     * The driver carried this person (a bus kind) where they were going: one more meeting at `place`; the first meeting, a
     * first shiny one, an intro line opening and the completions are told to them.
     */
    public void met(Player p, String person, boolean shiny, String place) {
        BusSpec bus = busSpec();
        BusSpec.Type t = bus == null ? null : bus.types().get(person);
        if (t == null || !spec.listed(t)) return;
        if (eco.guide() != null && eco.guide().active(p)) return;   // 길라잡이: the trial bus registers nothing
        UUID u = p.getUniqueId();
        Map<String, PeopleSpec.Entry> mine = data.computeIfAbsent(u, k -> new ConcurrentHashMap<>());
        PeopleSpec.Entry e = mine.computeIfAbsent(person, k -> new PeopleSpec.Entry());
        boolean hadNormal = e.normal > 0;
        PeopleSpec.Meet m = e.meet(spec, System.currentTimeMillis(), shiny, place);
        dirty.add(u + "|" + person);
        // missions (README "미션"): passengers carried where they were going, and new people in the codex
        core.profiles().add(u, "people_met", 1);
        if (m.first()) core.profiles().add(u, "people_new", 1);
        List<String> all = people();
        String name = nameOf(t);
        if (m.firstShiny()) {
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.6f);
            p.sendMessage(Lang.mm("people.new-shiny", "name", name, "have", PeopleSpec.count(all, mine, true), "all", all.size()));
            HudModule.flash(p, Lang.mm("people.new-shiny-flash", "name", name), 3500);
        } else if (!hadNormal && e.normal > 0) {
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
            p.sendMessage(Lang.mm(m.first() ? "people.new" : "people.new-normal", "name", name,
                    "have", PeopleSpec.count(all, mine, false), "all", all.size()));
        }
        if (m.opened() >= 0 && !m.first()) {
            p.sendMessage(Lang.mm("people.intro-open", "name", name, "met", e.met));
        }
        if (m.newPlace() && !m.first()) {
            p.sendMessage(Lang.mm("people.new-place", "name", name, "place", spec.places().getOrDefault(place, place)));
        }
        if (!hadNormal && e.normal > 0 && PeopleSpec.complete(all, mine, false)) {
            core.profiles().grantTitle(u, spec.titleAll());
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            p.sendMessage(Lang.mm("people.all-done", "n", all.size()));
        }
        if (m.firstShiny() && PeopleSpec.complete(all, mine, true)) {
            core.profiles().grantTitle(u, spec.titleShiny());
            Bukkit.broadcast(Lang.msg("people.shiny-all-done", "player", Names.mini(p)));
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

    private void open(Player p, boolean shinyLook) {
        core.menus().open(p, MENU, new PeopleMenu(shinyLook));
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
        /** show the 별난 look of those registered shiny */
        private boolean shinyLook;

        PeopleMenu(boolean shinyLook) {
            this.shinyLook = shinyLook;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            List<String> all = people();
            Map<String, PeopleSpec.Entry> mine = of(v.player().getUniqueId());
            Map<String, String> m = new HashMap<>();
            m.put("have", String.valueOf(PeopleSpec.count(all, mine, false)));
            m.put("all", String.valueOf(all.size()));
            m.put("shiny", String.valueOf(PeopleSpec.count(all, mine, true)));
            return m;
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (base == null) return null;
            if (b.action().equals(TOGGLE) && shinyLook) base.editMeta(m -> m.setEnchantmentGlintOverride(true));
            if (b.key() == 'I') {
                List<String> all = people();
                Map<String, PeopleSpec.Entry> mine = of(v.player().getUniqueId());
                List<Component> lore = new ArrayList<>();
                lore.add(Lang.item("people.info-count", "have", PeopleSpec.count(all, mine, false), "shiny", PeopleSpec.count(all, mine, true),
                        "all", all.size()));
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
            Map<String, PeopleSpec.Entry> mine = of(p.getUniqueId());
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd");
            List<ItemStack> out = new ArrayList<>();
            for (String id : people()) {
                BusSpec.Type t = bus.types().get(id);
                PeopleSpec.Entry e = mine.get(id);
                boolean known = e != null && e.registered();
                boolean showShiny = known && e.shiny > 0 && (shinyLook || e.normal == 0);
                String skin = !known ? UNKNOWN_SKIN : showShiny ? bus.shiny().skin(t) : t.skins().isEmpty() ? null : t.skins().getFirst();
                ItemStack it = head(skin);
                List<Component> lore = new ArrayList<>();
                if (known) {
                    lore.add(Lang.item("people.lore-met", "met", e.met, "date", day.format(new Date(e.first))));
                    lore.add(Lang.item("people.lore-looks", "normal", Lang.raw(e.normal > 0 ? "people.seen" : "people.unseen"),
                            "shiny", Lang.raw(e.shiny > 0 ? "people.seen-shiny" : "people.unseen")));
                    List<String> stamps = new ArrayList<>();
                    for (String pl : e.places) stamps.add(Lang.raw("people.stamp", "place", spec.places().getOrDefault(pl, pl)));
                    lore.add(Lang.item("people.lore-places", "stamps", String.join(" ", stamps)));
                    lore.add(Component.empty());
                    List<String> intro = spec.intros().getOrDefault(id, List.of());
                    int open = Math.min(intro.size(), spec.introsOpen(e.met));
                    for (int i = 0; i < open; i++) lore.add(Lang.item("people.lore-intro", "line", intro.get(i)));
                    int next = spec.nextMilestone(e.met);
                    if (open < intro.size() && next > 0) lore.add(Lang.item("people.lore-intro-locked", "n", next, "left", next - e.met));
                } else {
                    lore.add(Lang.item("people.lore-unknown"));
                    lore.add(Lang.item("people.lore-hint", "hint", hint(bus, t)));
                    lore.add(Lang.item("people.lore-how"));
                }
                // 주민의 부탁 (README "주민의 부탁"): 친밀도, and the 명인 의뢰's regulars
                if (eco.requests() != null) lore.addAll(eco.requests().peopleLines(p.getUniqueId(), id));
                boolean glint = known && showShiny;
                String name = known ? (e.shiny > 0 ? Lang.raw("people.name-shiny", "name", nameOf(t)) : nameOf(t)) : Lang.raw("people.unknown-name");
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
            } else if (action.equals(TOGGLE)) {
                shinyLook = !shinyLook;
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, shinyLook ? 1.6f : 1.0f);
                v.refresh();
            }
        }
    }

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

    /** For tests and the admin: how many distinct people a player registered (normal, shiny). */
    public int[] counts(UUID player) {
        List<String> all = people();
        Map<String, PeopleSpec.Entry> mine = of(player);
        return new int[]{PeopleSpec.count(all, mine, false), PeopleSpec.count(all, mine, true), all.size()};
    }
}
