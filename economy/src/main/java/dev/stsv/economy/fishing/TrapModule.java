package dev.stsv.economy.fishing;

import dev.stsv.core.content.CustomItem;
import dev.stsv.core.furniture.Furniture;
import dev.stsv.core.furniture.FurnitureBehavior;
import dev.stsv.core.furniture.FurnitureBreakEvent;
import dev.stsv.core.furniture.FurniturePlaceEvent;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.world.WorldRules;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.land.Land;
import dev.stsv.economy.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 통발 and 가두리: furniture that fishes by itself, placed in the water (every cell a water block) on the owner's own land or in the
 * wild world, at most fish.yml trap.per-chunk of them a chunk (pens: pen.per-chunk). Anyone can own them; a fisher's (the
 * owner's job and level when someone looks in) cycle faster and catch better (jobs.yml trap-speed, trap-uncommon, trap-rare).
 *
 * <p>Nothing ticks: a trap's catch and a pen's growth are worked out when someone clicks it or picks it up ({@link TrapSpec}
 * caps the catch-up at the storage / the feed), so placed traps cost nothing while nobody comes by and nothing is lost while
 * their chunk is unloaded (it simply stands full). Only its owner (or someone of the land it is on) may look in or pick it up.
 */
public final class TrapModule extends EcoModule {

    public static final String TRAP = "economy:fish_trap";
    public static final String PEN = "economy:fish_pen";
    static final String FEED = "economy:fish_feed";

    private TrapSpec spec;
    private TrapStore store;
    private final Random rnd = new Random();

    public TrapModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-traps";
    }

    @Override
    protected void start() {
        store = new TrapStore(core.db());
        store.init();
        load();
        register();
        for (World w : Bukkit.getWorlds()) dropReset(w);
        eco.onReset(() -> store.reset());
    }

    @Override
    public void reload() {
        load();
        register();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = TrapSpec.parse(eco.data("fish.yml"), problems);
        problems.forEach(p -> eco.getLogger().warning("[fish.yml] " + p));
    }

    private void register() {
        core.furniture().registerBehavior(TRAP, new Behavior(TrapStore.Kind.TRAP));
        core.furniture().registerBehavior(PEN, new Behavior(TrapStore.Kind.PEN));
    }

    public TrapSpec spec() {
        return spec;
    }

    public TrapStore store() {
        return store;
    }

    private FishSpec fish() {
        return eco.fishing().spec();
    }

    private static String behaviorOf(CustomItem item) {
        return item.furniture() == null ? null : item.furniture().behavior();
    }

    // ------------------------------------------------------------------------------------------
    // Where they may go
    // ------------------------------------------------------------------------------------------

    /** Own land (owner or member) or the wild world (and its nether / end); an operator anywhere. Nowhere else. */
    static boolean mayPlace(WorldRules.Kind world, Land land, UUID player, boolean bypass) {
        if (bypass || WorldRules.wildSet(world)) return true;
        return land != null && land.allows(player);
    }

    /** May this player look in, empty, feed or pick up the trap? Its owner, the people of the land it stands on, an operator. */
    private boolean mayUse(Player p, TrapStore.Trap t, Location at) {
        if (t == null || p.getUniqueId().equals(t.owner) || p.hasPermission("stsv.economy.land.bypass")) return true;
        Land land = eco.land().at(at);
        return land != null && land.allows(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(FurniturePlaceEvent e) {
        String behavior = behaviorOf(e.getFurniture().item());
        if (!TRAP.equals(behavior) && !PEN.equals(behavior)) return;
        Player p = e.getPlayer();
        Block anchor = e.getFurniture().location().getBlock();
        for (Block b : core.furniture().plan(p, e.getFurniture().item(), anchor).cells()) {
            if (b.getType() != Material.WATER) {
                refuse(e, p, "fishing.trap-water");
                return;
            }
        }
        WorldRules.Kind kind = core.worlds() == null ? WorldRules.Kind.OTHER : core.worlds().kind(anchor.getWorld());
        if (!mayPlace(kind, eco.land().at(anchor.getLocation()), p.getUniqueId(), p.hasPermission("stsv.economy.land.bypass"))) {
            refuse(e, p, "fishing.trap-land");
            return;
        }
        int[] here = store.inChunk(anchor.getWorld().getName(), anchor.getX() >> 4, anchor.getZ() >> 4);
        if (here[0] >= spec.trap().perChunk()) {
            refuse(e, p, "fishing.trap-chunk", "max", spec.trap().perChunk());
        } else if (PEN.equals(behavior) && here[1] >= spec.pen().perChunk()) {
            refuse(e, p, "fishing.pen-chunk", "max", spec.pen().perChunk());
        }
    }

    private static void refuse(FurniturePlaceEvent e, Player p, String key, Object... pairs) {
        e.setCancelled(true);
        HudModule.flash(p, Lang.mm(key, pairs));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent e) {
        String behavior = behaviorOf(e.getFurniture().item());
        if (!TRAP.equals(behavior) && !PEN.equals(behavior)) return;
        TrapStore.Trap t = store.get(e.getFurniture().display().getUniqueId());
        if (!mayUse(e.getPlayer(), t, e.getFurniture().location())) {
            e.setCancelled(true);
            HudModule.flash(e.getPlayer(), Lang.mm("fishing.trap-not-yours", "owner", dev.stsv.economy.util.Players.name(t.owner)));
        }
    }

    /** A world that was made again (the wild world's reset) has none of its old traps: their owners get the items back by mail. */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        dropReset(e.getWorld());
    }

    private void dropReset(World w) {
        for (TrapStore.Trap t : store.all()) {
            if (!t.world.equals(w.getName()) || t.worldId.equals(w.getUID())) continue;
            store.remove(t.id);
            Items.create(t.kind == TrapStore.Kind.TRAP ? TRAP : PEN, 1).ifPresent(it -> eco.mail().deliver(t.owner, it, "fish_trap_reset"));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Catch-up (on a click or a pick-up)
    // ------------------------------------------------------------------------------------------

    private double effect(UUID owner, String key) {
        return eco.jobs().effect(owner, "fisher", key);
    }

    /** What a trap caught since it was last looked into, added to its storage. */
    private void catchUp(TrapStore.Trap t, long now) {
        long cycle = TrapSpec.cycleMs(spec.trap().minutes(), effect(t.owner, "trap-speed"));
        TrapSpec.Step step = TrapSpec.accumulate(t.last, now, cycle, t.storedCount(), spec.trap().storage());
        t.last = step.last();
        if (step.catches() <= 0) return;
        TrapSpec.Odds odds = spec.odds(effect(t.owner, "trap-uncommon"), effect(t.owner, "trap-rare"));
        List<FishSpec.Rarity> rarities = new ArrayList<>(fish().rarities().values());
        for (int i = 0; i < step.catches(); i++) {
            FishSpec.Rarity r = rarities.get(TrapSpec.roll(odds, rnd));
            FishSpec.Species s = fish().pick(t.habitat, TrapSpec.ANY_TIME, r, rnd);
            if (s == null) continue;
            ItemStack it = Items.create(s.item(), 1).orElse(null);
            if (it == null) continue;
            if (fish().rarities().get(s.rarity()).index() >= 2) {
                double size = FishSpec.size(s, rnd.nextDouble(), 0);
                FishingModule.markSize(it, size, FishSpec.priceFactor(s, size));
            }
            stack(t.stored, it);
        }
    }

    private static void stack(List<ItemStack> into, ItemStack it) {
        for (ItemStack have : into) {
            if (have.isSimilar(it) && have.getAmount() < have.getMaxStackSize()) {
                have.setAmount(have.getAmount() + 1);
                return;
            }
        }
        into.add(it);
    }

    /** A pen's growth since it was last looked into. */
    private void growUp(TrapStore.Trap t, long now) {
        long cycle = TrapSpec.cycleMs(spec.pen().minutes(), effect(t.owner, "trap-speed"));
        TrapSpec.Growth g = TrapSpec.grow(t.count, t.feed, t.last, now, cycle, spec.pen());
        t.count = g.count();
        t.feed = g.feed();
        t.last = g.last();
    }

    // ------------------------------------------------------------------------------------------
    // Clicks
    // ------------------------------------------------------------------------------------------

    private void openTrap(Player p, TrapStore.Trap t) {
        long now = now();
        catchUp(t, now);
        int took = 0;
        Map<String, Integer> xp = new HashMap<>();
        for (ItemStack it : List.copyOf(t.stored)) {
            int want = it.getAmount();
            int left = 0;
            for (ItemStack l : p.getInventory().addItem(it.clone()).values()) left += l.getAmount();
            took += want - left;
            if (want - left > 0) xp.merge(Items.key(it), want - left, Integer::sum);
            if (left <= 0) t.stored.remove(it);
            else it.setAmount(left);
        }
        store.save(t);   // a trap that stood full moved on to now in catchUp: its next catch is a whole cycle away
        xp.forEach((item, n) -> eco.jobs().rewardAction(p, "trap", item, n));
        long cycle = TrapSpec.cycleMs(spec.trap().minutes(), effect(t.owner, "trap-speed"));
        int stored = t.storedCount();
        long next = TrapSpec.untilNext(t.last, now, cycle, stored, spec.trap().storage());
        if (took > 0) {
            p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1.1f);
            p.sendMessage(Lang.msg("fishing.trap-took", "count", took, "left", stored, "storage", spec.trap().storage(), "next", minutes(next)));
        } else if (stored > 0) {
            p.sendMessage(Lang.msg("mail.no-room"));
        } else {
            p.sendMessage(Lang.msg("fishing.trap-empty", "storage", spec.trap().storage(), "next", minutes(next),
                    "every", minutes(cycle)));
        }
    }

    private static String minutes(long ms) {
        long m = Math.max(1, Math.round(ms / 60_000.0));
        return String.valueOf(m);
    }

    /** The fish kind (fish.yml species) a stack is, if it may go in a pen (a common or uncommon fish of any time). */
    private FishSpec.Species penSpecies(ItemStack hand) {
        if (hand == null || hand.isEmpty() || Items.sized(hand)) return null;
        String key = Items.key(hand);
        for (FishSpec.Species s : fish().species().values()) {
            if (s.item().equals(key) && TrapSpec.penFish(fish().rarities().get(s.rarity()))) return s;
        }
        return null;
    }

    private void openPen(Player p, TrapStore.Trap t) {
        long now = now();
        growUp(t, now);
        TrapSpec.Pen pen = spec.pen();
        ItemStack hand = p.getInventory().getItemInMainHand();
        FishSpec.Species kind = penSpecies(hand);
        if (FEED.equals(Items.key(hand))) {
            int n = Math.min(hand.getAmount(), pen.feed() - t.feed);
            if (n <= 0) {
                p.sendMessage(Lang.msg("fishing.pen-feed-full", "feed", t.feed));
            } else {
                hand.subtract(n);
                t.feed += n;
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 0.5f, 1.4f);
                p.sendMessage(Lang.msg("fishing.pen-fed", "count", n, "feed", t.feed, "max", pen.feed()));
            }
        } else if (kind != null) {
            if (t.count > 0 && !kind.id().equals(t.species)) {
                p.sendMessage(Lang.msg("fishing.pen-other-kind", "fish", speciesName(t.species)));
            } else {
                int n = Math.min(hand.getAmount(), pen.capacity() - t.count);
                if (n <= 0) {
                    p.sendMessage(Lang.msg("fishing.pen-full", "count", t.count));
                } else {
                    hand.subtract(n);
                    t.species = kind.id();
                    t.count += n;
                    p.playSound(p.getLocation(), Sound.ENTITY_FISH_SWIM, 0.8f, 1.0f);
                    p.sendMessage(Lang.msg(t.count < pen.seed() ? "fishing.pen-seed-more" : "fishing.pen-seeded",
                            "fish", kind.name(), "count", t.count, "max", pen.capacity(), "seed", pen.seed()));
                }
            }
        } else if (t.count > 0 && t.species != null) {
            int take = TrapSpec.harvest(t.count, p.isSneaking(), pen);
            FishSpec.Species s = fish().species().get(t.species);
            int took = 0;
            if (take > 0 && s != null) {
                ItemStack it = Items.create(s.item(), take).orElse(null);
                if (it != null) {
                    int left = 0;
                    for (ItemStack l : p.getInventory().addItem(it).values()) left += l.getAmount();
                    took = take - left;
                }
            }
            if (took > 0) {
                t.count -= took;   // a pen that stood full moved on to now in growUp: it grows again a cycle from now
                eco.jobs().rewardAction(p, "trap", s.item(), took);
                p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1.0f);
                p.sendMessage(Lang.msg("fishing.pen-took", "fish", s.name(), "count", took, "left", t.count, "feed", t.feed));
            } else {
                status(p, t);
            }
        } else {
            status(p, t);
        }
        if (t.count <= 0) t.species = null;
        store.save(t);
    }

    private void status(Player p, TrapStore.Trap t) {
        TrapSpec.Pen pen = spec.pen();
        long cycle = TrapSpec.cycleMs(pen.minutes(), effect(t.owner, "trap-speed"));
        if (t.count <= 0) p.sendMessage(Lang.msg("fishing.pen-empty", "seed", pen.seed()));
        else p.sendMessage(Lang.msg("fishing.pen-status", "fish", speciesName(t.species), "count", t.count, "max", pen.capacity(),
                "feed", t.feed, "keep", pen.keep(), "every", minutes(cycle)));
    }

    private String speciesName(String id) {
        FishSpec.Species s = id == null ? null : fish().species().get(id);
        return s == null ? "?" : s.name();
    }

    // ------------------------------------------------------------------------------------------
    // Furniture
    // ------------------------------------------------------------------------------------------

    /** The water a trap at this block fishes: the biome here, underground when the water's surface above it sees no sky. */
    static String habitatAt(Block b) {
        Block top = b;
        for (int i = 0; i < 64 && top.getRelative(BlockFace.UP).getType() == Material.WATER; i++) top = top.getRelative(BlockFace.UP);
        World w = b.getWorld();
        boolean underground = top.getLightFromSky() == 0 && top.getY() < w.getSeaLevel() - 8;
        return FishSpec.habitat(b.getBiome().getKey().asString(), underground);
    }

    private final class Behavior implements FurnitureBehavior {
        private final TrapStore.Kind kind;

        Behavior(TrapStore.Kind kind) {
            this.kind = kind;
        }

        @Override
        public void onPlace(Player player, Furniture furniture, ItemStack from) {
            Location at = furniture.location();
            Block b = at.getBlock();
            TrapStore.Trap t = new TrapStore.Trap(furniture.display().getUniqueId(), kind, player.getUniqueId(), b.getWorld().getName(),
                    b.getWorld().getUID(), b.getX(), b.getY(), b.getZ(), habitatAt(b), now());
            store.save(t);
            player.playSound(at, Sound.ENTITY_GENERIC_SPLASH, 0.6f, 1.2f);
            if (kind == TrapStore.Kind.TRAP) {
                long cycle = TrapSpec.cycleMs(spec.trap().minutes(), effect(player.getUniqueId(), "trap-speed"));
                player.sendMessage(Lang.msg("fishing.trap-placed", "water", habitatName(t.habitat), "every", minutes(cycle),
                        "storage", spec.trap().storage()));
            } else {
                player.sendMessage(Lang.msg("fishing.pen-placed", "seed", spec.pen().seed()));
            }
        }

        @Override
        public void onInteract(Player player, Furniture furniture) {
            TrapStore.Trap t = store.get(furniture.display().getUniqueId());
            if (t == null) {
                // placed before this module knew it (or its row is gone): it starts now
                Block b = furniture.location().getBlock();
                t = new TrapStore.Trap(furniture.display().getUniqueId(), kind, player.getUniqueId(), b.getWorld().getName(),
                        b.getWorld().getUID(), b.getX(), b.getY(), b.getZ(), habitatAt(b), now());
                store.save(t);
            }
            if (!mayUse(player, t, furniture.location())) {
                HudModule.flash(player, Lang.mm("fishing.trap-not-yours", "owner", dev.stsv.economy.util.Players.name(t.owner)));
                return;
            }
            if (kind == TrapStore.Kind.TRAP) openTrap(player, t);
            else openPen(player, t);
        }

        @Override
        public void onBreak(Player player, Furniture furniture) {
            TrapStore.Trap t = store.remove(furniture.display().getUniqueId());
            if (t == null) return;
            Location at = furniture.location();
            List<ItemStack> drops = new ArrayList<>();
            if (kind == TrapStore.Kind.TRAP) {
                catchUp(t, now());
                drops.addAll(t.stored);
            } else {
                growUp(t, now());
                FishSpec.Species s = t.species == null ? null : fish().species().get(t.species);
                if (s != null && t.count > 0) Items.create(s.item(), t.count).ifPresent(drops::add);
                if (t.feed > 0) Items.create(FEED, t.feed).ifPresent(drops::add);
            }
            for (ItemStack it : drops) at.getWorld().dropItemNaturally(at, it);
        }
    }

    /** A water's name: its codex group's (fish.yml groups: 바다, 깊은 바다, 강...). */
    private String habitatName(String habitat) {
        FishSpec.Group g = fish().groups().get(habitat);
        return g == null ? habitat : g.name();
    }
}
