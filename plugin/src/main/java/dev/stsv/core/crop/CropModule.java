package dev.stsv.core.crop;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.furniture.DisplayView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom crops: planted from seed items on farmland, shown with a model per growth stage, and grown exactly like
 * vanilla crops: random ticks while the chunk is near a player, light 9
 * or more, faster on wet farmland (water within 4 blocks, rain) and in rows, bone meal. Defined in
 * contents/&lt;pack&gt;/crops/*.yml.
 *
 * Right-clicking a ripe crop harvests it and plants it again with one of its seeds, for these crops
 * and for vanilla wheat, carrots, potatoes and beetroots alike. Crops on farmland are never trampled.
 *
 * <p>How they are drawn (config crops.render, read at start): {@code blocks} (default) = a real block, a tripwire state
 * the resource pack draws as the stage's model (CropBlocks; string itself is kept from being placed: CropTripwire);
 * {@code entities} = an item display and a click box per crop, as before. The database is the truth either way: on
 * chunk load the crops of the chunk get their blocks (or entities), so switching moves every crop over.
 */
public final class CropModule implements StsvModule, Listener {

    /** on a crop's display and click box: the crop's id */
    public static final NamespacedKey TAG = new NamespacedKey("stsv", "crop");
    /** vanilla farmland crops harvested by right-click -> what replants them */
    private static final Map<Material, Material> VANILLA = Map.of(Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO, Material.BEETROOTS, Material.BEETROOT_SEEDS);

    private record Shown(ItemDisplay display, Interaction hit) {}

    /** How crops are drawn (config crops.render). */
    public enum Render {
        BLOCKS, ENTITIES;

        public static Render of(String s) {
            return s != null && s.trim().equalsIgnoreCase("entities") ? ENTITIES : BLOCKS;
        }
    }

    private record Replant(Block block, Material seed) {}

    private STSVPlugin plugin;
    private CropStore store;
    private final Map<String, CropSpec> specs = new LinkedHashMap<>();
    private final Map<String, String> seeds = new HashMap<>();          // seed item -> crop id
    private final Map<Long, CropState> crops = new HashMap<>();
    private final Map<String, Long> byBlock = new HashMap<>();
    private final Map<String, Set<Long>> byChunk = new HashMap<>();
    private final Map<Long, Shown> shown = new HashMap<>();
    /** crops whose chunk is loaded (shown as blocks or entities): these grow */
    private final Set<Long> active = new HashSet<>();
    private Render render;
    private CropBlocks.Allocation states = CropBlocks.Allocation.empty();
    private final BlockData[] stateData = new BlockData[CropBlocks.STATES];
    private CropTripwire tripwire;
    /** player -> tick of their last hit on a crop block (a held button sweeps: see hit) */
    private final Map<UUID, Integer> lastHit = new HashMap<>();
    private final Set<String> warnedNoState = new HashSet<>();
    private final Map<UUID, Long> byHit = new HashMap<>();
    private final Set<String> warnedUnknown = new HashSet<>();
    private long nextId = 1;
    private final Set<Long> dirty = new HashSet<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private int lastTick = -1;
    private Replant replanting;
    private boolean seedTaken;
    private boolean running;
    /** view distance and culling box of the crop displays (config display.*) */
    private DisplayView.Settings view;

    @Override
    public String id() {
        return "crops";
    }

    /**
     * Has enable() run to the end (definitions and planted crops loaded)? False before, after disable() and after a start that
     * failed part-way: the definitions may be loaded then while the planted crops are not.
     */
    public boolean running() {
        return running;
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        view = DisplayView.Settings.of(plugin.getConfig());
        render = Render.of(plugin.getConfig().getString("crops.render"));
        store = new CropStore(plugin.db());
        store.init();
        load();
        long started = System.currentTimeMillis();
        for (CropState s : store.loadCrops()) {
            index(s);
            nextId = Math.max(nextId, s.id + 1);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (render == Render.BLOCKS) {
            tripwire = new CropTripwire(this, plugin, CropBlocks.Stray.of(plugin.getConfig().getString("crops.stray-tripwire")));
            tripwire.start();
        }
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) {
                showChunk(w.getName(), c.getX(), c.getZ());
                if (tripwire != null) {
                    removeCropEntities(List.of(c.getEntities()));
                    tripwire.scan(c);
                }
            }
        }
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::grow, 100L, 100L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 600L, 600L));
        plugin.getLogger().info("작물 " + specs.size() + "종, 심어진 작물 " + crops.size() + "개, " + (render == Render.BLOCKS ? "블록" : "엔티티")
                + "로 표시 (" + (System.currentTimeMillis() - started) + "ms)");
        running = true;
    }

    @Override
    public void reload() {
        view = DisplayView.Settings.of(plugin.getConfig());
        load();
        warnedUnknown.clear();
        warnedNoState.clear();
        if (Render.of(plugin.getConfig().getString("crops.render")) != render) {
            plugin.getLogger().warning("[작물] crops.render 는 서버를 다시 시작해야 바뀝니다 (지금: " + render.name().toLowerCase(java.util.Locale.ROOT) + ")");
        }
        if (tripwire != null) tripwire.stray(CropBlocks.Stray.of(plugin.getConfig().getString("crops.stray-tripwire")));
        for (Long id : new ArrayList<>(active)) refresh(crops.get(id));
    }

    @Override
    public void disable() {
        running = false;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        flush();
        for (Long id : new ArrayList<>(shown.keySet())) hide(id);
        active.clear();   // crop blocks stay in the world (saved with their chunks)
        if (tripwire != null) tripwire.stop();
        tripwire = null;
        HandlerList.unregisterAll(this);
    }

    /** Crops are drawn as blocks (the setting this start went with; before the start, the config's). For the resource pack. */
    public boolean blocks() {
        if (render != null) return render == Render.BLOCKS;
        return plugin == null || Render.of(plugin.getConfig().getString("crops.render")) == Render.BLOCKS;
    }

    private void load() {
        specs.clear();
        seeds.clear();
        for (CropSpec spec : CropSpec.loadAll(plugin.content().packs(), w -> plugin.getLogger().warning("[작물] " + w))) {
            specs.put(spec.id(), spec);
            if (seeds.put(spec.seed(), spec.id()) != null) plugin.getLogger().warning("[작물] 씨앗이 겹침: " + spec.seed());
        }
        for (CropSpec s : specs.values()) {
            if (item(s.seed(), 1).isEmpty()) plugin.getLogger().warning("[작물] " + s.id() + ": 없는 씨앗 " + s.seed());
            for (CropSpec.Drop d : s.harvest()) if (item(d.item(), 1).isEmpty()) plugin.getLogger().warning("[작물] " + s.id() + ": 없는 수확물 " + d.item());
        }
        // the tripwire state of every stage: the same the resource pack was built with (both read crop-blocks.yml)
        try {
            states = CropBlocks.load(plugin.content().packs(), specs.values());
        } catch (CropBlocks.Full e) {
            plugin.getLogger().severe("[작물] " + e.getMessage());
            List<Map<String, Integer>> files = new ArrayList<>();
            for (ContentPack p : plugin.content().packs()) files.add(CropBlocks.read(p.dir().resolve(CropBlocks.FILE)));
            try {
                states = CropBlocks.allocate(files, List.of());
            } catch (CropBlocks.Full never) {
                states = CropBlocks.Allocation.empty();
            }
        }
    }

    public Map<String, CropSpec> specs() {
        return specs;
    }

    // ------------------------------------------------------------------------------------------
    // Index
    // ------------------------------------------------------------------------------------------

    private static String blockKey(String w, int x, int y, int z) {
        return w + "|" + x + "|" + y + "|" + z;
    }

    private static String chunkKey(String w, int cx, int cz) {
        return w + "|" + cx + "|" + cz;
    }

    private void index(CropState s) {
        crops.put(s.id, s);
        byBlock.put(blockKey(s.world, s.x, s.y, s.z), s.id);
        byChunk.computeIfAbsent(chunkKey(s.world, s.x >> 4, s.z >> 4), k -> new HashSet<>()).add(s.id);
    }

    private void unindex(CropState s) {
        crops.remove(s.id);
        active.remove(s.id);
        byBlock.remove(blockKey(s.world, s.x, s.y, s.z));
        Set<Long> c = byChunk.get(chunkKey(s.world, s.x >> 4, s.z >> 4));
        if (c != null) c.remove(s.id);
        dirty.remove(s.id);
    }

    public Optional<CropState> at(Block b) {
        Long id = byBlock.get(blockKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        return Optional.ofNullable(id == null ? null : crops.get(id));
    }

    private CropState at(String w, int x, int y, int z) {
        Long id = byBlock.get(blockKey(w, x, y, z));
        return id == null ? null : crops.get(id);
    }

    public int count() {
        return crops.size();
    }

    /** The planted crop at this block, or null (for CropTripwire). */
    CropState at(World w, int x, int y, int z) {
        return at(w.getName(), x, y, z);
    }

    /** How many planted crops are within this distance of a spot (for /관리자 보고서 주변). */
    public int countNear(Location at, double radius) {
        if (at.getWorld() == null) return 0;
        String w = at.getWorld().getName();
        int n = 0, r = (int) Math.ceil(radius);
        for (int cx = (at.getBlockX() - r) >> 4; cx <= (at.getBlockX() + r) >> 4; cx++) {
            for (int cz = (at.getBlockZ() - r) >> 4; cz <= (at.getBlockZ() + r) >> 4; cz++) {
                for (Long id : byChunk.getOrDefault(chunkKey(w, cx, cz), Set.of())) {
                    CropState s = crops.get(id);
                    if (s == null) continue;
                    double dx = s.x + 0.5 - at.getX(), dy = s.y + 0.5 - at.getY(), dz = s.z + 0.5 - at.getZ();
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) n++;
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------
    // Growth (vanilla rules)
    // ------------------------------------------------------------------------------------------

    private static int light(Block b) {
        return Math.max(b.getLightFromSky(), b.getLightFromBlocks());
    }

    private boolean same(CropState s, int dx, int dz) {
        CropState o = at(s.world, s.x + dx, s.y, s.z + dz);
        return o != null && o.crop.equals(s.crop);
    }

    /** 0 = not farmland, 1 = dry, 2 = wet. */
    private static int soil(Block b) {
        return b.getBlockData() instanceof Farmland f ? (f.getMoisture() > 0 ? 2 : 1) : 0;
    }

    private double speed(CropState s, World w) {
        int[][] soil = new int[3][3];
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) soil[dx + 1][dz + 1] = soil(w.getBlockAt(s.x + dx, s.y - 1, s.z + dz));
        return CropGrowth.speed(soil, same(s, 0, -1) || same(s, 0, 1), same(s, -1, 0) || same(s, 1, 0),
                same(s, -1, -1) || same(s, 1, -1) || same(s, 1, 1) || same(s, -1, 1));
    }

    private static int randomTickSpeed(World w) {
        try {
            Integer v = w.getGameRuleValue(GameRules.RANDOM_TICK_SPEED);
            return v == null ? 3 : v;
        } catch (RuntimeException e) {
            return 3;
        }
    }

    private void grow() {
        int tick = Bukkit.getCurrentTick();
        long ticks = lastTick < 0 ? 100 : Math.max(0, Math.min(400, tick - lastTick));
        lastTick = tick;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Map<String, Integer> speeds = new HashMap<>();
        for (Long id : new ArrayList<>(active)) {
            CropState s = crops.get(id);
            if (s == null) continue;
            CropSpec spec = specs.get(s.crop);
            World w = Bukkit.getWorld(s.world);
            if (spec == null || w == null) continue;
            // a crop block knocked out of its state (or taken away) by something the protections missed: put back
            if (render == Render.BLOCKS && !placeBlock(s, spec, false)) continue;
            if (!soilOk(s, spec, w)) {
                pop(s);
                continue;
            }
            if (spec.ripe(s.age)) continue;
            // random ticks only reach chunks within the simulation distance of a player
            if (w.getChunkAt(s.x >> 4, s.z >> 4).getLoadLevel() != Chunk.LoadLevel.ENTITY_TICKING) continue;
            int n = CropGrowth.randomTicks(ticks, speeds.computeIfAbsent(s.world, k -> randomTickSpeed(w)), r);
            if (n == 0 || light(w.getBlockAt(s.x, s.y, s.z)) < 9) continue;
            int before = s.age;
            s.age = CropGrowth.grow(s.age, spec.maxAge(), n, CropGrowth.chance(speed(s, w)), r);
            if (s.age == before) continue;
            s.grown += s.age - before;
            dirty.add(s.id);
            if (spec.stageOf(s.age) != spec.stageOf(before)) refresh(s);
        }
    }

    private boolean soilOk(CropState s, CropSpec spec, World w) {
        Block crop = w.getBlockAt(s.x, s.y, s.z);
        if (render == Render.ENTITIES && !crop.isEmpty() && !crop.isPassable()) return false;
        return spec.soil().contains(w.getBlockAt(s.x, s.y - 1, s.z).getType().getKey().asString());
    }

    private void flush() {
        List<CropState> list = new ArrayList<>();
        for (Long id : dirty) {
            CropState s = crops.get(id);
            if (s != null) list.add(s);
        }
        dirty.clear();
        store.save(list);
    }

    // ------------------------------------------------------------------------------------------
    // Showing (only while the chunk is loaded; entities are never saved)
    // ------------------------------------------------------------------------------------------

    private void showChunk(String world, int cx, int cz) {
        Set<Long> ids = byChunk.get(chunkKey(world, cx, cz));
        if (ids == null) return;
        for (Long id : new ArrayList<>(ids)) {
            CropState s = crops.get(id);
            if (s != null) show(s);
        }
    }

    private Location place(CropState s) {
        return new Location(Bukkit.getWorld(s.world), s.x + 0.5, s.y + 0.5, s.z + 0.5);
    }

    private float height(CropState s, CropSpec spec) {
        return (float) Math.max(0.3, spec.height() * (spec.stageOf(s.age) + 1) / spec.stages());
    }

    private ItemStack look(CropSpec spec, CropState s) {
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> m.setItemModel(NamespacedKey.fromString(spec.models().get(spec.stageOf(s.age)))));
        return it;
    }

    private void show(CropState s) {
        active.add(s.id);
        hide(s.id);
        CropSpec spec = specs.get(s.crop);
        if (spec == null) {
            // its definition is missing (a YAML error, a removed crop): kept, not shown, until the file is fixed
            if (warnedUnknown.add(s.crop)) plugin.getLogger().warning("[작물] 정의가 없는 작물이 심어져 있음: " + s.crop + " (그대로 둠)");
            return;
        }
        if (render == Render.BLOCKS) {
            placeBlock(s, spec, true);
            return;
        }
        // entities: a crop block left from crops.render: blocks goes first
        clearBlock(s);
        Location at = place(s);
        if (at.getWorld() == null) return;
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(TAG, PersistentDataType.LONG, s.id);
            e.setItemStack(look(spec, s));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            // the many small models of a farm: a shorter distance, and a box so off-screen ones are skipped (DisplayView)
            DisplayView.apply(e, view.viewRange(DisplayView.Kind.CROP), DisplayView.cropBox(spec.height()), view.culling());
            e.setBillboard(Display.Billboard.FIXED);
        });
        Interaction hit = at.getWorld().spawn(at.clone().add(0, -0.5, 0), Interaction.class, i -> {
            i.setPersistent(false);
            i.getPersistentDataContainer().set(TAG, PersistentDataType.LONG, s.id);
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(height(s, spec));
            i.setResponsive(true);
        });
        byHit.put(hit.getUniqueId(), s.id);
        shown.put(s.id, new Shown(d, hit));
    }

    private void hide(long id) {
        Shown sh = shown.remove(id);
        if (sh == null) return;
        sh.display().remove();
        byHit.remove(sh.hit().getUniqueId());
        sh.hit().remove();
    }

    private void refresh(CropState s) {
        if (s == null) return;
        CropSpec spec = specs.get(s.crop);
        if (render == Render.BLOCKS) {
            World w = Bukkit.getWorld(s.world);
            if (spec != null && w != null && w.isChunkLoaded(s.x >> 4, s.z >> 4)) {
                active.add(s.id);
                placeBlock(s, spec, false);
            }
            return;
        }
        Shown sh = shown.get(s.id);
        if (spec == null || sh == null || !sh.display().isValid() || !sh.hit().isValid()) {
            if (sh != null || spec != null && Bukkit.getWorld(s.world) != null && Bukkit.getWorld(s.world).isChunkLoaded(s.x >> 4, s.z >> 4)) show(s);
            return;
        }
        sh.display().setItemStack(look(spec, s));
        DisplayView.apply(sh.display(), view.viewRange(DisplayView.Kind.CROP), DisplayView.cropBox(spec.height()), view.culling());
        sh.hit().setInteractionHeight(height(s, spec));
    }

    /** The tripwire block of a state code (made once). */
    BlockData stateData(int code) {
        BlockData d = stateData[code];
        if (d == null) stateData[code] = d = Bukkit.createBlockData("minecraft:tripwire[" + CropBlocks.properties(code) + "]");
        return d;
    }

    /** The block a crop shows now; null if its definition is missing or its stage has no state (the states ran out: see the log). */
    BlockData blockOf(CropState s) {
        CropSpec spec = specs.get(s.crop);
        if (spec == null) return null;
        Integer code = states.code(s.crop, spec.stageOf(s.age));
        if (code == null) {
            if (warnedNoState.add(s.crop)) plugin.getLogger().warning("[작물] " + s.crop + ": 블록 상태가 없는 단계라 보이지 않음 (" + CropBlocks.FILE + ")");
            return null;
        }
        return stateData(code);
    }

    /**
     * Puts a crop's block in its state (its chunk is loaded), without block updates so no neighbour reacts. Something
     * else standing there (CropBlocks.placement) pops the crop out as its seed: now, or on the next tick when called
     * during a chunk load. False if the crop is gone (or going).
     */
    private boolean placeBlock(CropState s, CropSpec spec, boolean loading) {
        World w = Bukkit.getWorld(s.world);
        BlockData want = blockOf(s);
        if (w == null || want == null) return true;
        Block b = w.getBlockAt(s.x, s.y, s.z);
        BlockData now = b.getBlockData();
        boolean empty = b.isEmpty() || (b.isReplaceable() && !b.isLiquid());
        switch (CropBlocks.placement(now.getMaterial() == Material.TRIPWIRE, now.equals(want), empty)) {
            case OK -> {
                return true;
            }
            case SET -> {
                b.setBlockData(want, false);
                return true;
            }
            default -> {
                plugin.getLogger().info("[작물] " + s.crop + " 자리(" + s.world + " " + s.x + ", " + s.y + ", " + s.z + ")에 "
                        + now.getMaterial().getKey().getKey() + " 이(가) 있어 씨앗으로 돌려줌");
                if (loading) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (crops.get(s.id) == s) pop(s);
                    });
                } else {
                    pop(s);
                }
                return false;
            }
        }
    }

    /** Takes a crop's block away (only while it is still a tripwire), without block updates. */
    private void clearBlock(CropState s) {
        World w = Bukkit.getWorld(s.world);
        if (w == null || !w.isChunkLoaded(s.x >> 4, s.z >> 4)) return;
        Block b = w.getBlockAt(s.x, s.y, s.z);
        if (b.getType() == Material.TRIPWIRE) b.setType(Material.AIR, false);
    }

    private static void removeCropEntities(java.util.Collection<? extends org.bukkit.entity.Entity> entities) {
        for (var ent : entities) {
            if (ent.getPersistentDataContainer().has(TAG, PersistentDataType.LONG)) ent.remove();
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        showChunk(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ());
        if (tripwire != null) tripwire.scan(e.getChunk());
    }

    /** Blocks: crop entities (of a start with crops.render: entities) never stay. They are not saved, but to be sure. */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        if (render == Render.BLOCKS) removeCropEntities(e.getEntities());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Set<Long> ids = byChunk.get(chunkKey(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ()));
        if (ids != null) {
            for (Long id : new ArrayList<>(ids)) {
                hide(id);
                active.remove(id);
            }
        }
        // entities of crops removed while unloaded, left over from a crash: tidy up
        removeCropEntities(List.of(e.getChunk().getEntities()));
    }

    // ------------------------------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------------------------------

    private String key(ItemStack it) {
        if (it == null || it.isEmpty()) return null;
        return plugin.content().itemOf(it).map(c -> c.key().asString()).orElse(it.getType().getKey().asString());
    }

    private Optional<ItemStack> item(String id, int amount) {
        if (amount <= 0) return Optional.empty();
        Optional<ItemStack> c = plugin.content().item(id).map(i -> i.create(amount));
        if (c.isPresent()) return c;
        Material m = Material.matchMaterial(id);
        return m == null || !m.isItem() ? Optional.empty() : Optional.of(new ItemStack(m, amount));
    }

    /** Takes one item of this id from the player's inventory. */
    /** Takes one of an item from the bag (or the off hand); returns a copy of what was taken, null if there was none. */
    private ItemStack takeOne(Player p, String id) {
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && id.equals(key(it))) {
                ItemStack one = it.asOne();
                it.subtract(1);
                return one;
            }
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (id.equals(key(off))) {
            ItemStack one = off.asOne();
            off.subtract(1);
            return one;
        }
        return null;
    }

    /**
     * Marks a content plugin puts on items (the economy's "bought from the admin shop") that a plant keeps from its
     * seed: a young plant broken out gives its seed back with the mark, so planting cannot wash it off. A ripe
     * harvest is unmarked (growing it is real work).
     */
    public void carryMark(org.bukkit.NamespacedKey key) {
        carryMarks.add(key);
    }

    private final Set<org.bukkit.NamespacedKey> carryMarks = new java.util.concurrent.CopyOnWriteArraySet<>();

    private String markOf(ItemStack it) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return null;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        for (org.bukkit.NamespacedKey k : carryMarks) if (pdc.has(k)) return k.asString();
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Planting
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseItem(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        String k = key(hand);
        // plant a seed on farmland (other right-clicks - chests, doors - work normally while holding seeds)
        String cropId = k == null ? null : seeds.get(k);
        if (cropId == null || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (e.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        CropSpec spec = specs.get(cropId);
        Block soil = e.getClickedBlock();
        if (VANILLA.containsKey(soil.getType())) return;   // right-clicking a crop: the harvest handler decides
        if (!spec.soil().contains(soil.getType().getKey().asString())) {
            if (soil.getType() == Material.DIRT || soil.getType() == Material.GRASS_BLOCK) HudModule.flash(p, Lang.mm("crop.wrong-soil"));
            return;
        }
        e.setCancelled(true);
        Block above = soil.getRelative(BlockFace.UP);
        if (e.getBlockFace() != BlockFace.UP || !above.isEmpty() || at(above).isPresent()) return;
        plant(p, spec, above, hand);
    }

    private void plant(Player p, CropSpec spec, Block at, ItemStack hand) {
        CropEvent ev = new CropEvent(CropEvent.Action.PLANT, p, spec, at.getLocation(), List.of());
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return;
        CropState s = new CropState(nextId++, spec.id(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), p.getUniqueId(), 0);
        s.carry = markOf(hand);
        store.insert(s);
        index(s);
        show(s);
        if (p.getGameMode() != GameMode.CREATIVE) hand.subtract(1);
        p.swingMainHand();
        p.playSound(at.getLocation(), Sound.ITEM_CROP_PLANT, 1f, 1f);
    }

    // ------------------------------------------------------------------------------------------
    // Using a crop: bone meal, harvest, a look at it
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClickCrop(PlayerInteractEntityEvent e) {
        Long id = byHit.get(e.getRightClicked().getUniqueId());
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        CropState s = crops.get(id);
        if (s != null) use(e.getPlayer(), s);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHitCrop(PrePlayerAttackEntityEvent e) {
        Long id = byHit.get(e.getAttacked().getUniqueId());
        if (id == null) return;
        e.setCancelled(true);
        CropState s = crops.get(id);
        if (s != null) breakCrop(e.getPlayer(), s);
    }

    /**
     * Crops as blocks: a click (either button, on a block or in the air) that aims at a crop is that crop's, as on its
     * old click box: the crop's block itself, or a crop whose box the look ray meets first (CropAim) - the farmland under
     * it, the ground or a wall behind it. First of all, so protections and the vanilla use of what is behind never see it;
     * the crop's own event (CropEvent) asks the protections as before.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAimCrop(PlayerInteractEvent e) {
        if (render != Render.BLOCKS || e.getAction() == Action.PHYSICAL) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        CropState s = aimed(p, e.getClickedBlock(), e.getBlockFace(), e.getInteractionPoint());
        if (s == null) return;
        e.setCancelled(true);
        boolean left = e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.LEFT_CLICK_AIR;
        if (!left && e.getHand() != EquipmentSlot.HAND) return;
        if (left) hit(p, s);
        else use(p, s);
    }

    /** Anything else breaking a crop block (another plugin's area tool): the crop is broken as by a hit. Never drops string. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreakCropBlock(BlockBreakEvent e) {
        if (render != Render.BLOCKS) return;
        CropState s = at(e.getBlock().getWorld(), e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ());
        if (s == null) return;
        e.setCancelled(true);
        e.setDropItems(false);
        breakCrop(e.getPlayer(), s);
    }

    /**
     * A hit on a crop block. Holding the button down hits the block in front again every tick (blocks break at once);
     * an entity was hit once per click. So while the button is held (a hit within 4 ticks of the last), ripe crops are
     * harvested as the player sweeps across the field but young ones are left alone - the one just harvested and
     * replanted included. A new click takes a young plant out as before.
     */
    private void hit(Player p, CropState s) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastHit.put(p.getUniqueId(), now);
        boolean held = last != null && now - last >= 0 && now - last <= 4;
        CropSpec spec = specs.get(s.crop);
        if (held && p.getGameMode() != GameMode.CREATIVE && (spec == null || !spec.ripe(s.age))) return;
        breakCrop(p, s);
    }

    /** The crop a player's click aims at (crops as blocks), or null. */
    private CropState aimed(Player p, Block clicked, BlockFace face, Location point) {
        if (clicked != null) {
            CropState s = at(clicked.getWorld(), clicked.getX(), clicked.getY(), clicked.getZ());
            if (s != null) return s;
            // the top of the soil under a crop is the crop (its thin outline leaves most of the plant to the soil)
            if (face == BlockFace.UP && (s = at(clicked.getWorld(), clicked.getX(), clicked.getY() + 1, clicked.getZ())) != null) return s;
        }
        Location eye = p.getEyeLocation();
        org.bukkit.util.Vector d = eye.getDirection();
        var range = p.getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE);
        double limit = range == null ? 4.5 : range.getValue();
        double[] o = {eye.getX(), eye.getY(), eye.getZ()}, dir = {d.getX(), d.getY(), d.getZ()};
        if (clicked != null) {
            // only crops in front of what the game hit
            if (point != null && point.getWorld() == eye.getWorld()) {
                limit = Math.min(limit, point.distance(eye));
            } else {
                org.bukkit.util.BoundingBox bb = clicked.getBoundingBox();
                double t = bb.getVolume() <= 0 ? Double.NaN : CropAim.hit(o, dir,
                        new double[]{bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ()}, limit + 1);
                limit = Math.min(limit, Double.isNaN(t) ? clicked.getLocation().add(0.5, 0.5, 0.5).distance(eye) - 0.5 : t);
            }
        }
        String w = eye.getWorld().getName();
        CropState best = null;
        double bestT = limit;
        for (int[] c : CropAim.cells(o[0], o[1], o[2], dir[0], dir[1], dir[2], limit)) {
            for (int dy = 0; dy <= CropAim.REACH_UP; dy++) {
                CropState s = at(w, c[0], c[1] - dy, c[2]);
                if (s == null) continue;
                CropSpec spec = specs.get(s.crop);
                double h = spec == null ? 0.9 : height(s, spec);
                if (h <= dy) continue;
                double t = CropAim.hit(o, dir, CropAim.box(s.x, s.y, s.z, h), bestT);
                if (!Double.isNaN(t) && t <= bestT) {
                    best = s;
                    bestT = t;
                }
            }
        }
        return best;
    }

    private void use(Player p, CropState s) {
        CropSpec spec = specs.get(s.crop);
        Location loc = place(s);
        if (spec == null) {
            HudModule.flash(p, Lang.mm("crop.unknown", "crop", s.crop));
            return;
        }
        if (spec.ripe(s.age)) {
            harvest(p, s, spec, true);
            return;
        }
        CropEvent ev = new CropEvent(CropEvent.Action.USE, p, spec, loc, List.of());
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.BONE_MEAL) {
            // one age, and one meal per 7 ages the plant grew by itself (so meal speeds every crop up alike, by 1/7)
            // at most one meal banked: a plant that went long without meal does not take several in a row
            s.meals = CropGrowth.mealsBanked(s.grown, s.meals);
            if (p.getGameMode() != GameMode.CREATIVE && !CropGrowth.mealAllowed(s.grown, s.meals)) {
                HudModule.flash(p, Lang.mm("crop.meal-wait", "ages", CropGrowth.agesToNextMeal(s.grown, s.meals)));
                return;
            }
            int before = s.age;
            s.age = CropGrowth.boneMeal(s.age, spec.maxAge());
            s.meals++;
            if (p.getGameMode() != GameMode.CREATIVE) hand.subtract(1);
            p.swingMainHand();
            loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc, 15, 0.3, 0.3, 0.3);
            loc.getWorld().playSound(loc, Sound.ITEM_BONE_MEAL_USE, 1f, 1f);
            store.save(List.of(s));
            if (s.age != before) refresh(s);
            return;
        }
        status(p, s, spec);
    }

    private void status(Player p, CropState s, CropSpec spec) {
        World w = Bukkit.getWorld(s.world);
        String notes = "";
        if (w != null) {
            if (light(w.getBlockAt(s.x, s.y, s.z)) < 9) notes = Lang.raw("crop.note-dark");
            else notes = Lang.raw(soil(w.getBlockAt(s.x, s.y - 1, s.z)) == 2 ? "crop.note-wet" : "crop.note-dry");
        }
        HudModule.flash(p, Lang.mm("crop.status", "crop", spec.name(), "stage", spec.stageOf(s.age) + 1, "stages", spec.stages(),
                "pct", s.age * 100 / spec.maxAge(), "notes", notes));
    }

    /**
     * Harvests a ripe crop; replant = it starts again (from one of its seeds, or its regrow age). False if
     * cancelled. Judged like breaking a vanilla crop: Fortune on the tool in hand adds to every drop the way
     * it does for wheat seeds, carrots and potatoes, and the harvest costs the same bit of hunger.
     */
    private boolean harvest(Player p, CropState s, CropSpec spec, boolean replant) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int fortune = p.getInventory().getItemInMainHand().getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FORTUNE);
        List<ItemStack> drops = new ArrayList<>();
        for (CropSpec.Drop d : spec.harvest()) {
            int n = d.max() > d.min() ? r.nextInt(d.min(), d.max() + 1) : d.min();
            if (d == spec.harvest().getFirst()) n += CropGrowth.fortune(fortune, n, r);   // the produce only, a share of it
            item(d.item(), n).ifPresent(drops::add);
        }
        Location loc = place(s);
        CropEvent ev = new CropEvent(CropEvent.Action.HARVEST, p, spec, loc, drops);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return false;
        boolean again = replant;
        if (replant && spec.regrow() < 0) {
            // replanted with one of the seeds it just dropped, else one from the bag; with neither (a harvest
            // that happened to drop no seed) it is replanted all the same: a harvest never leaves the soil empty
            boolean seeded = false;
            for (ItemStack it : ev.drops()) {
                if (it != null && !it.isEmpty() && spec.seed().equals(key(it))) {
                    it.subtract(1);
                    seeded = true;
                    s.carry = null;
                    break;
                }
            }
            if (!seeded && p.getGameMode() != GameMode.CREATIVE) {
                ItemStack taken = takeOne(p, spec.seed());
                s.carry = taken == null ? null : markOf(taken);
            }
        }
        for (ItemStack it : ev.drops()) if (it != null && !it.isEmpty()) loc.getWorld().dropItemNaturally(loc, it);
        if (p.getGameMode() != GameMode.CREATIVE) p.setExhaustion(p.getExhaustion() + 0.005f);   // as breaking a block
        p.swingMainHand();
        loc.getWorld().playSound(loc, Sound.BLOCK_CROP_BREAK, 1f, 1f);
        if (again) {
            s.age = spec.regrow() >= 0 ? spec.regrow() : 0;
            store.save(List.of(s));   // at once: a crash must not make it ripe again
            refresh(s);
            if (spec.regrow() < 0) loc.getWorld().playSound(loc, Sound.ITEM_CROP_PLANT, 0.8f, 1f);
        } else {
            remove(s);
        }
        return true;
    }

    private void breakCrop(Player p, CropState s) {
        CropSpec spec = specs.get(s.crop);
        if (spec == null) {
            HudModule.flash(p, Lang.mm("crop.unknown", "crop", s.crop));
            return;
        }
        CropEvent ev = new CropEvent(CropEvent.Action.BREAK, p, spec, place(s), List.of());
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return;
        if (p.getGameMode() == GameMode.CREATIVE) {
            // like breaking a vanilla crop in creative: it is gone, nothing drops
            place(s).getWorld().playSound(place(s), Sound.BLOCK_CROP_BREAK, 1f, 1f);
            remove(s);
            return;
        }
        if (spec.ripe(s.age)) {
            harvest(p, s, spec, true);   // hitting a ripe crop harvests it like a right-click (and replants)
            return;
        }
        pop(s);   // a young plant comes out as its seed
    }

    /** Takes a crop out, giving its seed back. */
    private void pop(CropState s) {
        CropSpec spec = specs.get(s.crop);
        Location loc = place(s);
        if (spec != null && loc.getWorld() != null) item(spec.seed(), 1).ifPresent(seed -> {
            org.bukkit.NamespacedKey mark = s.carry == null ? null : org.bukkit.NamespacedKey.fromString(s.carry);
            if (mark != null) seed.editMeta(m -> m.getPersistentDataContainer().set(mark, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1));
            loc.getWorld().dropItemNaturally(loc, seed);
        });
        if (loc.getWorld() != null) loc.getWorld().playSound(loc, Sound.BLOCK_CROP_BREAK, 1f, 1f);
        remove(s);
    }

    private void remove(CropState s) {
        hide(s.id);
        if (render == Render.BLOCKS) clearBlock(s);
        unindex(s);
        store.delete(s.id);
    }

    // ------------------------------------------------------------------------------------------
    // Vanilla crops: right-click to harvest and replant
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onVanillaHarvest(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
        Block b = e.getClickedBlock();
        Material type = b.getType();
        Material seed = VANILLA.get(type);
        if (seed == null || !(b.getBlockData() instanceof Ageable a) || a.getAge() < a.getMaximumAge()) return;
        if (b.getRelative(BlockFace.DOWN).getType() != Material.FARMLAND) return;
        Player p = e.getPlayer();
        if (p.isSneaking()) return;
        e.setCancelled(true);
        harvestVanilla(p, b, seed);
    }

    /**
     * Harvests a ripe crop at this block for a player and replants it, like a right-click (ours or vanilla,
     * protection and job rules see a normal harvest). False if there is no ripe crop or it was refused.
     */
    public boolean harvestAt(Player p, Block b) {
        Optional<CropState> ours = at(b);
        if (ours.isPresent()) {
            CropSpec spec = specs.get(ours.get().crop);
            return spec != null && spec.ripe(ours.get().age) && harvest(p, ours.get(), spec, true);
        }
        Material seed = VANILLA.get(b.getType());
        if (seed == null || !(b.getBlockData() instanceof Ageable a) || a.getAge() < a.getMaximumAge()) return false;
        if (b.getRelative(BlockFace.DOWN).getType() != Material.FARMLAND) return false;
        return harvestVanilla(p, b, seed);
    }

    private boolean harvestVanilla(Player p, Block b, Material seed) {
        Material type = b.getType();
        // broken the normal way (protection, job rewards, fortune all see a normal harvest); one seed of the drops replants it
        replanting = new Replant(b, seed);
        seedTaken = false;
        // breaking in creative drops nothing (vanilla); harvesting by hand is ours, and gives the harvest in every mode
        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        List<ItemStack> creativeDrops = creative ? new ArrayList<>(b.getDrops(p.getInventory().getItemInMainHand(), p)) : List.of();
        boolean broken;
        try {
            broken = p.breakBlock(b);
        } finally {
            replanting = null;
        }
        if (broken && creative) {
            Location at = b.getLocation().add(0.5, 0.5, 0.5);
            boolean seedUsed = false;
            for (ItemStack it : creativeDrops) {
                if (!seedUsed && it.getType() == seed) {
                    it.subtract(1);
                    seedUsed = true;
                }
                if (!it.isEmpty()) b.getWorld().dropItemNaturally(at, it);
            }
        }
        if (!broken || !b.isEmpty() || b.getRelative(BlockFace.DOWN).getType() != Material.FARMLAND) return broken;
        // a seed from the drops (taken above), else from the bag; with neither it is replanted all the same
        if (!seedTaken && p.getGameMode() != GameMode.CREATIVE) takeOne(p, seed.getKey().asString());
        b.setType(type);
        p.swingMainHand();
        b.getWorld().playSound(b.getLocation().add(0.5, 0.5, 0.5), Sound.ITEM_CROP_PLANT, 0.8f, 1f);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHarvestDrops(BlockDropItemEvent e) {
        Replant r = replanting;
        if (r == null || seedTaken || !e.getBlock().equals(r.block())) return;
        for (Iterator<Item> it = e.getItems().iterator(); it.hasNext(); ) {
            Item item = it.next();
            ItemStack st = item.getItemStack();
            if (st.getType() != r.seed()) continue;
            if (st.getAmount() > 1) {
                st.setAmount(st.getAmount() - 1);
                item.setItemStack(st);
            } else {
                it.remove();
            }
            seedTaken = true;
            return;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Keeping the soil under crops
    // ------------------------------------------------------------------------------------------

    private boolean cropAbove(Block soil) {
        return at(soil.getRelative(BlockFace.UP)).isPresent();
    }

    /** Any crop, ours or vanilla, on this farmland. */
    private boolean anyCropAbove(Block soil) {
        return cropAbove(soil) || Tag.CROPS.isTagged(soil.getRelative(BlockFace.UP).getType());
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        // a vanilla crop keeps dry farmland from turning to dirt; so does ours (the moisture itself is vanilla)
        if (e.getBlock().getType() == Material.FARMLAND && cropAbove(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL && e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.FARMLAND
                && anyCropAbove(e.getClickedBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobTrample(EntityInteractEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && anyCropAbove(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceIntoCrop(BlockPlaceEvent e) {
        if (at(e.getBlockPlaced()).isPresent()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (at(e.getToBlock()).isPresent()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSoilBreak(BlockBreakEvent e) {
        at(e.getBlock().getRelative(BlockFace.UP)).ifPresent(this::pop);
    }

    private boolean touchesCrops(List<Block> moved, BlockFace dir) {
        for (Block b : moved) {
            // its soil, a crop block in the way (pistons break tripwire), or pushed into a crop
            if (cropAbove(b) || at(b).isPresent() || at(b.getRelative(dir)).isPresent()) return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent e) {
        if (touchesCrops(e.getBlocks(), e.getDirection()) || at(e.getBlock().getRelative(e.getDirection())).isPresent()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent e) {
        if (touchesCrops(e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    /**
     * Grows the custom crops around a spot one age each with this chance (for contents that speed up
     * growth near someone, like a farmer's job, the way they would vanilla crops). Returns how many grew.
     */
    public int growAround(World w, int x, int y, int z, int radius, int below, int above, double chance) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int n = 0;
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                for (Long id : byChunk.getOrDefault(chunkKey(w.getName(), cx, cz), Set.of())) {
                    CropState s = crops.get(id);
                    CropSpec spec = s == null ? null : specs.get(s.crop);
                    if (spec == null || spec.ripe(s.age) || Math.abs(s.x - x) > radius || Math.abs(s.z - z) > radius
                            || s.y < y - below || s.y > y + above || r.nextDouble() >= chance) continue;
                    int before = s.age++;
                    s.grown++;
                    dirty.add(s.id);
                    if (spec.stageOf(s.age) != spec.stageOf(before)) refresh(s);
                    if (r.nextInt(4) == 0) w.spawnParticle(Particle.HAPPY_VILLAGER, s.x + 0.5, s.y + 0.4, s.z + 0.5, 2, 0.2, 0.1, 0.2);
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * After a crop's max-age changed: ripe plants stay ripe, the rest keep their share of the way (never ripe by it).
     * Returns how many planted crops changed age. The new ages are queued for saving at once, so a caller's "done" mark
     * written to the database afterwards (the one database thread keeps the order) can never be stored without them.
     */
    public int rescale(String crop, int oldMaxAge) {
        CropSpec spec = specs.get(crop);
        if (spec == null || oldMaxAge <= 0 || spec.maxAge() == oldMaxAge) return 0;
        List<CropState> changed = new ArrayList<>();
        for (CropState s : crops.values()) {
            if (!s.crop.equals(crop)) continue;
            int before = s.age;
            s.age = s.age >= oldMaxAge ? spec.maxAge() : Math.min(spec.maxAge() - 1, s.age * spec.maxAge() / oldMaxAge);
            if (s.age != before) {
                changed.add(s);
                refresh(s);
            }
        }
        store.save(changed);   // at once, not at the next flush: a crash must not keep the old ages under a done mark
        return changed.size();
    }

    /** For contents: is this item a seed of a crop? */
    public Optional<CropSpec> seedOf(ItemStack it) {
        String k = key(it);
        return Optional.ofNullable(k == null ? null : specs.get(seeds.get(k)));
    }
}
