package dev.stsv.economy.fishing;

import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.calendar.WeeklyListener;
import dev.stsv.economy.job.JobSpec;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 낚시: until the float dips it is vanilla. When a fish bites and the player reels in, a ring appears in the
 * middle of the screen (a title made of font glyphs, redrawn every tick) with a needle going round; right
 * click when it is on a green cell. The rarity (common, uncommon, rare, legend) sets how many successes,
 * how fast, how narrow. 98 fish by habitat, time and weather; rare and legend fish carry their size (the
 * shop pays 0.7 - 1.5x by it). Every catch goes in the /도감 codex; a full habitat gives a title, all
 * fish [강태공]. The week's biggest catch is announced. Treasure and junk stay vanilla.
 */
public final class FishingModule extends EcoModule implements WeeklyListener {

    public static final NamespacedKey SIZE = new NamespacedKey("economy", "fish_size");
    public static final NamespacedKey FACTOR = new NamespacedKey("economy", "fish_factor");
    private static final Set<Material> FISH = Set.of(Material.COD, Material.SALMON, Material.PUFFERFISH, Material.TROPICAL_FISH);

    private FishSpec spec;
    private FishStore store;
    private List<String> order = List.of();
    private final Map<UUID, Game> games = new HashMap<>();
    /** testing (/관리자 낚시): the next bites of a player, by rarity id ("pearl" too) or by fish */
    private final Map<UUID, Forced> forced = new HashMap<>();

    /** rarity: a rarity id or "pearl" (null when a fish is set); species: a fish id; left: bites to go. */
    public record Forced(String rarity, String species, int left) {}
    private final Random rnd = new Random();

    private static final class Game {
        final Ring ring;
        final FishSpec.Species species;
        final int level;
        final EquipmentSlot hand;
        String pop;
        int popTicks;
        long lastPress;

        Game(Ring ring, FishSpec.Species species, int level, EquipmentSlot hand) {
            this.ring = ring;
            this.species = species;
            this.level = level;
            this.hand = hand;
        }
    }

    public FishingModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-fishing";
    }

    @Override
    protected void start() {
        store = new FishStore(core.db());
        store.init();
        load();
        every(1L, this::tick);
        eco.calendar().onWeekly(this);
        eco.onReset(() -> {
            store.reset();
            eco.kv().remove("fish.week");
        });
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    protected void stop() {
        games.clear();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = FishSpec.parse(eco.data("fish.yml"), problems);
        for (FishSpec.Species s : spec.species().values()) if (Items.create(s.item(), 1).isEmpty()) problems.add(s.id() + ": 없는 아이템 " + s.item());
        problems.forEach(p -> eco.getLogger().warning("[fish.yml] " + p));
        order = new ArrayList<>(spec.species().keySet());
    }

    public FishSpec spec() {
        return spec;
    }

    public FishStore store() {
        return store;
    }

    // ------------------------------------------------------------------------------------------
    // The bite
    // ------------------------------------------------------------------------------------------

    private static boolean holdsRod(Player p, EquipmentSlot hand) {
        return p.getInventory().getItem(hand).getType() == Material.FISHING_ROD;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (games.containsKey(u)) {
            e.setCancelled(true);   // no new cast while a fish is on
            return;
        }
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item caught)) return;
        Forced force = forced.get(u);
        if (force == null && !FISH.contains(caught.getItemStack().getType())) return;   // treasure and junk: vanilla
        Location at = e.getHook().getLocation();
        Block b = at.getBlock();
        World w = at.getWorld();
        boolean underground = b.getLightFromSky() == 0 && b.getY() < w.getSeaLevel() - 8;
        String habitat = FishSpec.habitat(b.getBiome().getKey().asString(), underground);
        FishSpec.Moment m = new FishSpec.Moment(w.getTime(), w.hasStorm(), w.isThundering(), (int) ((w.getFullTime() / 24000) % 8));
        double rare = eco.jobs().findChance(u, "fisher");
        FishSpec.Rarity tier = spec.tier(rare, rnd);
        FishSpec.Species s;
        if (force != null) {
            // a test bite: that fish wherever and whenever, or that rarity from this water
            if (force.left() <= 1) forced.remove(u);
            else forced.put(u, new Forced(force.rarity(), force.species(), force.left() - 1));
            if (force.species() != null) s = spec.species().get(force.species());
            else if (force.rarity().equals("pearl")) s = pearl();
            else s = spec.pick(habitat, m, spec.rarities().get(force.rarity()), rnd);
        } else if (tier.index() == 2 && FishSpec.SALT.contains(habitat) && rnd.nextDouble() < spec.pearl()) {
            s = pearl();
        } else {
            s = spec.pick(habitat, m, tier, rnd);
        }
        if (s == null) return;
        FishSpec.Rarity real = spec.rarities().get(s.rarity());
        e.setCancelled(true);
        caught.remove();
        e.getHook().remove();
        boolean fisher = "fisher".equals(eco.jobs().job(u));
        int lv = fisher ? eco.jobs().level(u) : 0;
        Ring ring = new Ring(real, lv * 0.01, 3 + (fisher && lv >= 10 ? 1 : 0), rnd);
        EquipmentSlot hand = e.getHand() == EquipmentSlot.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        Game game = new Game(ring, s, lv, hand);
        game.pop = Lang.raw("fishing.hint");
        game.popTicks = 40;
        games.put(u, game);
        p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1f, 0.8f);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 0.6f + real.index() * 0.2f);
    }

    /** A pearl comes up as a rare bite of the sea, its ring a fish's (a mystery until it is up). */
    private FishSpec.Species pearl() {
        String rare = new ArrayList<>(spec.rarities().keySet()).get(2);
        return new FishSpec.Species("pearl", Lang.raw("fishing.pearl"), "salt", rare, 0, 0, 80, "f", "economy:pearl", null);
    }

    /** Testing: the player's next bites are of that rarity ("pearl" too) or that fish; times = how many. */
    public void force(UUID player, String rarity, String species, int times) {
        forced.put(player, new Forced(rarity, species, Math.max(1, times)));
    }

    public Forced forced(UUID player) {
        return forced.get(player);
    }

    public boolean unforce(UUID player) {
        return forced.remove(player) != null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        Game g = games.get(e.getPlayer().getUniqueId());
        if (g == null || e.getHand() != g.hand) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        // one click at a block can arrive twice (at the block, then in the air): count it once
        long tick = Bukkit.getCurrentTick();
        if (tick - g.lastPress < 2) return;
        g.lastPress = tick;
        press(e.getPlayer(), g);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        games.remove(e.getPlayer().getUniqueId());
        forced.remove(e.getPlayer().getUniqueId());
    }

    private void press(Player p, Game g) {
        Ring.Result r = g.ring.press();
        switch (r) {
            case HIT -> feedback(p, g, "fishing.good", Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f + g.ring.hits * 0.12f);
            case PERFECT -> feedback(p, g, "fishing.perfect", Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f + g.ring.hits * 0.12f);
            case MISS -> feedback(p, g, "fishing.miss", Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
            case TRAP -> feedback(p, g, "fishing.trap", Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f);
            case WON -> win(p, g);
            case ESCAPED -> escaped(p);
            default -> { }
        }
    }

    private void feedback(Player p, Game g, String key, Sound sound, float pitch) {
        g.pop = Lang.raw(key);
        g.popTicks = 12;
        p.playSound(p.getLocation(), sound, 0.8f, pitch);
    }

    private void tick() {
        for (UUID u : List.copyOf(games.keySet())) {
            Game g = games.get(u);
            Player p = Bukkit.getPlayer(u);
            if (p == null || g == null) {
                games.remove(u);
                continue;
            }
            if (!holdsRod(p, g.hand)) {
                escaped(p);
                continue;
            }
            if (g.ring.tick() == Ring.Result.ESCAPED) {
                escaped(p);
                continue;
            }
            if (g.ring.flash == 6 && g.ring.flashKind == 3) feedback(p, g, "fishing.miss", Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
            if (g.popTicks > 0) g.popTicks--;
            String sub = g.popTicks > 0 ? g.pop : "";
            p.showTitle(Title.title(Text.mm(RingGlyphs.title(g.ring, g.species.shape())), Text.mm(sub),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(300), Duration.ZERO)));
            p.sendActionBar(Text.mm(status(g)));
        }
    }

    private String status(Game g) {
        FishSpec.Rarity r = g.ring.rarity();
        StringBuilder sb = new StringBuilder("<" + r.color() + ">" + r.name() + "  ");
        for (int i = 0; i < r.hits(); i++) sb.append(i < g.ring.hits ? "<gold>■" : "<dark_gray>■");
        sb.append("  ");
        for (int i = 0; i < g.ring.maxHearts; i++) sb.append(i < g.ring.hearts ? "<red>❤" : "<dark_gray>❤");
        return sb.toString();
    }

    private void escaped(Player p) {
        games.remove(p.getUniqueId());
        p.clearTitle();
        p.sendActionBar(Lang.mm("fishing.escaped"));
        p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.6f);
    }

    // ------------------------------------------------------------------------------------------
    // The catch
    // ------------------------------------------------------------------------------------------

    private void win(Player p, Game g) {
        UUID u = p.getUniqueId();
        games.remove(u);
        FishSpec.Species s = g.species;
        FishSpec.Rarity r = g.ring.rarity();
        boolean fisher = "fisher".equals(eco.jobs().job(u));
        boolean pearl = s.id().equals("pearl");
        double size = pearl ? 0 : FishSpec.size(s, rnd.nextDouble(), g.ring.perfect, g.ring.allPerfect(), fisher && g.level >= 20 ? 0.10 : 0);
        JobSpec.Job fj = eco.jobs().spec().jobs().get("fisher");
        int count = fisher && fj != null && fj.finds() != null && g.level >= fj.finds().doubleLevel() && fj.finds().doubleItems().contains(s.item()) ? 2 : 1;
        ItemStack it = Items.create(s.item(), count).orElse(new ItemStack(Material.COD, count));
        double factor = FishSpec.priceFactor(s, size);
        if (!pearl && r.index() >= 2) {
            // rare and legend fish keep their size (they do not stack, the shop pays by it)
            it.editMeta(meta -> {
                List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Lang.item("fishing.size-lore", "size", String.format("%.1f", size), "pct", Math.round(factor * 100)));
                meta.lore(lore);
                meta.getPersistentDataContainer().set(SIZE, PersistentDataType.DOUBLE, size);
                meta.getPersistentDataContainer().set(FACTOR, PersistentDataType.DOUBLE, factor);
            });
        }
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        // what vanilla would have given: experience, rod wear, the statistic
        p.giveExp(1 + rnd.nextInt(6));
        ItemStack rod = p.getInventory().getItem(g.hand);
        if (rod.getType() == Material.FISHING_ROD) p.getInventory().setItem(g.hand, rod.damage(1, p));
        p.incrementStatistic(Statistic.FISH_CAUGHT);
        eco.jobs().rewardAction(p, "catch", s.item(), 1);
        if (r.index() >= 2) eco.jobs().rewardAction(p, "find", s.item(), 1);
        // the title: the fish's picture and name, its size below
        int idx = order.indexOf(s.id());
        String head = (idx >= 0 ? RingGlyphs.icon(idx) + " " : "") + "<" + r.color() + ">" + s.name();
        String sub = pearl ? "" : Lang.raw(g.ring.allPerfect() ? "fishing.result-perfect" : "fishing.result", "size", String.format("%.1f", size));
        p.showTitle(Title.title(Text.mm(head), Text.mm(sub), Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2500), Duration.ofMillis(600))));
        p.sendActionBar(Component.empty());
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f + r.index() * 0.1f);
        p.sendMessage(Lang.msg(pearl ? "fishing.caught-pearl" : g.ring.allPerfect() ? "fishing.caught-perfect" : "fishing.caught",
                "color", r.color(), "fish", s.name(), "size", String.format("%.1f", size), "count", count > 1 ? " ×" + count : ""));
        if (pearl) return;
        boolean first = store.record(u, s.id(), size, now());
        if (first) {
            p.sendMessage(Lang.msg("fishing.new-species", "fish", s.name(), "caught", store.of(u).size(), "total", spec.species().size()));
            checkCodex(p, s);
        }
        weeklyRecord(p, s, size);
    }

    private void checkCodex(Player p, FishSpec.Species s) {
        UUID u = p.getUniqueId();
        Map<String, FishStore.Entry> mine = store.of(u);
        FishSpec.Group grp = spec.groups().get(s.group());
        if (grp != null && spec.species().values().stream().filter(x -> x.group().equals(grp.id())).allMatch(x -> mine.containsKey(x.id()))) {
            if (grp.title() != null) core.profiles().grantTitle(u, grp.title());
            Bukkit.broadcast(Lang.msg("fishing.group-done", "player", p.getName(), "group", grp.name()));
        }
        if (spec.species().keySet().stream().allMatch(mine::containsKey)) {
            core.profiles().grantTitle(u, "economy:master_angler");
            Bukkit.broadcast(Lang.msg("fishing.all-done", "player", p.getName()));
        }
    }

    /** kv fish.week = "week;player;species;size" of the week's biggest catch (by length). */
    private void weeklyRecord(Player p, FishSpec.Species s, double size) {
        int week = eco.calendar().week();
        String[] cur = eco.kv().get("fish.week", "").split(";");
        double best = cur.length == 4 && cur[0].equals(String.valueOf(week)) ? Double.parseDouble(cur[3]) : 0;
        if (size <= best) return;
        eco.kv().set("fish.week", week + ";" + p.getUniqueId() + ";" + s.id() + ";" + size);
        if (best > 0) Bukkit.broadcast(Lang.msg("fishing.week-record", "player", p.getName(), "fish", s.name(), "size", String.format("%.1f", size)));
    }

    @Override
    public void newWeek(int week, long weekStart) {
        String[] cur = eco.kv().get("fish.week", "").split(";");
        if (cur.length != 4 || !cur[0].equals(String.valueOf(week - 1))) return;
        FishSpec.Species s = spec.species().get(cur[2]);
        Bukkit.broadcast(Lang.msg("fishing.week-winner", "player", Players.name(UUID.fromString(cur[1])),
                "fish", s == null ? cur[2] : s.name(), "size", cur[3]));
    }

    // ------------------------------------------------------------------------------------------
    // /도감 (a tab per kind of collection; fishing for now)
    // ------------------------------------------------------------------------------------------

    public void openCodex(Player p) {
        openCodex(p, null);
    }

    private void openCodex(Player p, String group) {
        core.menus().open(p, "economy:codex", new CodexMenu(group));
    }

    private final class CodexMenu implements MenuHandler {
        /** the habitat group shown, null = all */
        private final String group;

        CodexMenu(String group) {
            this.group = group;
        }

        private List<FishSpec.Species> shown() {
            return spec.species().values().stream().filter(s -> group == null || s.group().equals(group)).toList();
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            Map<String, FishStore.Entry> mine = store.of(v.player().getUniqueId());
            List<FishSpec.Species> l = shown();
            FishSpec.Group g = group == null ? null : spec.groups().get(group);
            return Map.of("caught", String.valueOf(mine.size()), "total", String.valueOf(spec.species().size()),
                    "group", g == null ? Lang.raw("codex.all") : g.name(),
                    "have", String.valueOf(l.stream().filter(s -> mine.containsKey(s.id())).count()), "all", String.valueOf(l.size()));
        }

        @Override
        public ItemStack button(MenuView v, dev.stsv.core.gui.menu.MenuSpec.Button b, ItemStack base) {
            if (b.key() != 'I') return base;
            // every group with its count, and the title it gives
            Map<String, FishStore.Entry> mine = store.of(v.player().getUniqueId());
            List<Component> lore = new ArrayList<>();
            for (FishSpec.Group g : spec.groups().values()) {
                List<FishSpec.Species> in = spec.species().values().stream().filter(x -> x.group().equals(g.id())).toList();
                long have = in.stream().filter(x -> mine.containsKey(x.id())).count();
                lore.add(Lang.item(have >= in.size() ? "codex.group-done" : "codex.group", "group", g.name(), "have", have, "all", in.size()));
            }
            lore.add(Component.empty());
            lore.addAll(Lang.items("codex.info-lore"));
            base.editMeta(meta -> meta.lore(lore));
            return base;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            Map<String, FishStore.Entry> mine = store.of(v.player().getUniqueId());
            List<ItemStack> out = new ArrayList<>();
            for (FishSpec.Species s : shown()) {
                FishSpec.Rarity r = spec.rarities().get(s.rarity());
                FishSpec.Group g = spec.groups().get(s.habitat());
                String where = s.habitat().equals("fresh") ? Lang.raw("codex.fresh") : s.habitat().equals("salt") ? Lang.raw("codex.salt")
                        : g == null ? s.habitat() : g.name();
                String when = s.when() == null ? Lang.raw("codex.anytime") : Lang.raw("codex.when." + s.when());
                FishStore.Entry e = mine.get(s.id());
                ItemStack it;
                if (e == null) {
                    it = new ItemStack(Material.PAPER);
                    it.editMeta(meta -> {
                        meta.setItemModel(NamespacedKey.fromString("economy:gui/unknown"));
                        meta.itemName(Lang.item("codex.unknown"));
                        meta.lore(Lang.items("codex.unknown-lore", "where", where, "when", when, "color", r.color(), "rarity", r.name()));
                    });
                } else {
                    it = Items.create(s.item(), 1).orElse(new ItemStack(Material.COD));
                    it.editMeta(meta -> {
                        meta.itemName(Text.item("<" + r.color() + ">" + s.name()));
                        meta.lore(Lang.items("codex.known-lore", "where", where, "when", when, "color", r.color(), "rarity", r.name(),
                                "count", e.count, "best", String.format("%.1f", e.best), "min", fmt(s.min()), "max", fmt(s.max())));
                    });
                }
                out.add(it);
            }
            return out;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            List<String> ids = new ArrayList<>(spec.groups().keySet());
            int at = group == null ? -1 : ids.indexOf(group);
            switch (action) {
                case "codex:all" -> openCodex(v.player(), null);
                case "codex:next" -> openCodex(v.player(), ids.get((at + 1) % ids.size()));
                case "codex:prev" -> openCodex(v.player(), ids.get(at <= 0 ? ids.size() - 1 : at - 1));
                default -> { }
            }
        }
    }

    private static String fmt(double cm) {
        return cm == Math.floor(cm) ? String.valueOf((long) cm) : String.format("%.1f", cm);
    }
}
