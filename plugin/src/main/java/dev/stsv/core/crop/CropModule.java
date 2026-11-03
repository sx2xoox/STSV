package dev.stsv.core.crop;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.furniture.Furniture;
import dev.stsv.core.furniture.FurnitureBehavior;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Custom crops: planted from seed items on farmland (or in pot furniture), shown as display entities
 * with a model per growth stage, grown by watered time (watering cans, sprinklers), sped up and improved
 * by fertilizers, threatened by drought and pests (pesticide cures), harvested with quality stars.
 * Defined in contents/&lt;pack&gt;/crops/*.yml; growth is computed from time, so crops in unloaded
 * chunks keep growing exactly as if someone stood next to them.
 */
public final class CropModule implements StsvModule, Listener {

    public static final NamespacedKey QUALITY = new NamespacedKey("stsv", "quality");
    private static final NamespacedKey WATER = new NamespacedKey("stsv", "water");
    private static final NamespacedKey TAG = new NamespacedKey("stsv", "crop");
    private static final double POT_TOP = 0.625;   // plants sit on a 10-pixel pot

    private record Shown(ItemDisplay display, Interaction hit) {}

    private STSVPlugin plugin;
    private CropStore store;
    private final Map<String, CropSpec> specs = new LinkedHashMap<>();
    private final Map<String, String> seeds = new HashMap<>();          // seed item -> crop id
    private CropTools tools = new CropTools();
    private final Map<Long, CropState> crops = new HashMap<>();
    private final Map<String, Long> byBlock = new HashMap<>();
    private final Map<String, Set<Long>> byChunk = new HashMap<>();
    private final Map<Long, Shown> shown = new HashMap<>();
    private final Map<UUID, Long> byHit = new HashMap<>();
    private final Map<UUID, Long> byPot = new HashMap<>();
    private final List<CropStore.Sprinkler> sprinklers = new ArrayList<>();
    private final Map<String, List<CropStore.Sprinkler>> sprinklersByChunk = new HashMap<>();
    private final Map<Long, Integer> potMissing = new HashMap<>();
    private long nextId = 1;
    private final Set<Long> dirty = new HashSet<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public String id() {
        return "crops";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        store = new CropStore(plugin.db());
        store.init();
        load();
        long now = System.currentTimeMillis();
        // time stood still while the server was off: no withering from a long maintenance
        long alive = store.lastAlive();
        long downtime = alive > 0 ? Math.max(0, now - alive) : 0;
        for (CropState s : store.loadCrops()) {
            if (downtime > 60_000) {
                CropMath.shift(s, downtime);
                dirty.add(s.id);
            }
            index(s);
            nextId = Math.max(nextId, s.id + 1);
        }
        for (CropStore.Sprinkler sp : store.loadSprinklers()) addSprinkler(sp);
        store.alive(now);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.furniture().registerBehavior("stsv:sprinkler", new SprinklerBehavior());
        plugin.furniture().registerBehavior("stsv:crop_pot", new PotBehavior());
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) showChunk(w.getName(), c.getX(), c.getZ());
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 100L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            flush();
            store.alive(System.currentTimeMillis());
        }, 600L, 600L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::sprinkle, 40L, 40L));
        plugin.getLogger().info("작물 " + specs.size() + "종, 심어진 작물 " + crops.size() + "개 (" + (System.currentTimeMillis() - now) + "ms)");
    }

    @Override
    public void reload() {
        load();
        for (Long id : new ArrayList<>(shown.keySet())) refresh(crops.get(id));
    }

    @Override
    public void disable() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        long now = System.currentTimeMillis();
        for (CropState s : crops.values()) settle(s, now);
        dirty.addAll(crops.keySet());
        flush();
        store.alive(now);
        for (Long id : new ArrayList<>(shown.keySet())) hide(id);
        HandlerList.unregisterAll(this);
    }

    private void load() {
        specs.clear();
        seeds.clear();
        CropTools t = new CropTools();
        for (ContentPack pack : plugin.content().packs()) {
            Path dir = pack.dir().resolve("crops");
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                    YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                    for (String key : y.getKeys(false)) {
                        ConfigurationSection sec = y.getConfigurationSection(key);
                        if (sec == null) continue;
                        if (key.equals("tools")) {
                            t.read(sec);
                            continue;
                        }
                        try {
                            CropSpec spec = CropSpec.parse(pack.namespace(), key, sec);
                            specs.put(spec.id(), spec);
                            if (seeds.put(spec.seed(), spec.id()) != null) plugin.getLogger().warning("[작물] 씨앗이 겹침: " + spec.seed());
                        } catch (IllegalArgumentException e) {
                            plugin.getLogger().warning("[작물] " + pack.namespace() + ":" + key + ": " + e.getMessage());
                        }
                    }
                }
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("[작물] " + dir + ": " + e.getMessage());
            }
        }
        tools = t;
        for (CropSpec s : specs.values()) {
            if (item(s.seed(), 1).isEmpty()) plugin.getLogger().warning("[작물] " + s.id() + ": 없는 씨앗 " + s.seed());
            for (CropSpec.Drop d : s.harvest()) if (item(d.item(), 1).isEmpty()) plugin.getLogger().warning("[작물] " + s.id() + ": 없는 수확물 " + d.item());
        }
        for (String id : tools.allItems()) if (item(id, 1).isEmpty()) plugin.getLogger().warning("[작물] 없는 도구 아이템 " + id);
    }

    public Map<String, CropSpec> specs() {
        return specs;
    }

    public CropTools tools() {
        return tools;
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
        if (s.pot != null) byPot.put(s.pot, s.id);
    }

    private void unindex(CropState s) {
        crops.remove(s.id);
        byBlock.remove(blockKey(s.world, s.x, s.y, s.z));
        Set<Long> c = byChunk.get(chunkKey(s.world, s.x >> 4, s.z >> 4));
        if (c != null) c.remove(s.id);
        if (s.pot != null) byPot.remove(s.pot);
        dirty.remove(s.id);
    }

    public Optional<CropState> at(Block b) {
        Long id = byBlock.get(blockKey(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        return Optional.ofNullable(id == null ? null : crops.get(id));
    }

    public int count() {
        return crops.size();
    }

    // ------------------------------------------------------------------------------------------
    // Growth
    // ------------------------------------------------------------------------------------------

    private void addSprinkler(CropStore.Sprinkler sp) {
        sprinklers.add(sp);
        sprinklersByChunk.computeIfAbsent(chunkKey(sp.world(), sp.x() >> 4, sp.z() >> 4), k -> new ArrayList<>()).add(sp);
    }

    private void removeSprinkler(String world, int x, int y, int z) {
        sprinklers.removeIf(sp -> sp.world().equals(world) && sp.x() == x && sp.y() == y && sp.z() == z);
        List<CropStore.Sprinkler> l = sprinklersByChunk.get(chunkKey(world, x >> 4, z >> 4));
        if (l != null) l.removeIf(sp -> sp.x() == x && sp.y() == y && sp.z() == z);
    }

    /** Sprinklers reach at most 16 blocks: only the crop's chunk and its neighbours are looked at. */
    private boolean sprinkled(CropState s) {
        int cx = s.x >> 4, cz = s.z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (CropStore.Sprinkler sp : sprinklersByChunk.getOrDefault(chunkKey(s.world, cx + dx, cz + dz), List.of())) {
                    if (Math.abs(sp.x() - s.x) <= sp.radius() && Math.abs(sp.z() - s.z) <= sp.radius() && Math.abs(sp.y() - s.y) <= 1) return true;
                }
            }
        }
        return false;
    }

    /** Catches a crop up to now; true if something visible changed. */
    private boolean settle(CropState s, long now) {
        CropSpec spec = specs.get(s.crop);
        if (spec == null) return false;
        boolean changed = CropMath.update(s, spec, tools.fertilizers.get(s.fertilizer), sprinkled(s), now, ThreadLocalRandom.current());
        if (changed) dirty.add(s.id);   // in between, growth is recomputed exactly from the saved state
        return changed;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Long id : new ArrayList<>(shown.keySet())) {
            CropState s = crops.get(id);
            if (s == null) continue;
            World w = Bukkit.getWorld(s.world);
            if (w == null) continue;
            if (s.pot == null && !soilOk(s, w)) {
                pop(s, null);
                continue;
            }
            if (s.pot != null) {
                // the pot was removed some other way (/kill, another plugin): after 15 s give the seed back
                var pot = Bukkit.getEntity(s.pot);
                if (pot == null || !pot.isValid()) {
                    if (potMissing.merge(s.id, 1, Integer::sum) >= 3) {
                        potMissing.remove(s.id);
                        pop(s, null);
                        continue;
                    }
                } else {
                    potMissing.remove(s.id);
                }
            }
            if (settle(s, now)) refresh(s);
            if (s.pests() && !s.withered) {
                w.spawnParticle(Particle.MYCELIUM, s.x + 0.5, s.y + 0.4 + (s.pot == null ? 0 : POT_TOP), s.z + 0.5, 6, 0.25, 0.25, 0.25, 0);
            }
        }
    }

    private boolean soilOk(CropState s, World w) {
        CropSpec spec = specs.get(s.crop);
        Block crop = w.getBlockAt(s.x, s.y, s.z);
        if (!crop.isEmpty() && !crop.isPassable()) return false;
        return spec == null || spec.soil().contains(w.getBlockAt(s.x, s.y - 1, s.z).getType().getKey().asString());
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
        long now = System.currentTimeMillis();
        for (Long id : new ArrayList<>(ids)) {
            CropState s = crops.get(id);
            if (s == null) continue;
            settle(s, now);
            show(s);
        }
    }

    private Location place(CropState s) {
        World w = Bukkit.getWorld(s.world);
        return new Location(w, s.x + 0.5, s.y + 0.5 + (s.pot == null ? 0 : POT_TOP), s.z + 0.5);
    }

    private NamespacedKey model(CropState s) {
        CropSpec spec = specs.get(s.crop);
        if (spec == null || s.withered) return NamespacedKey.fromString(spec == null ? "stsv:model/withered_crop" : spec.witheredModel());
        return NamespacedKey.fromString(spec.models().get(spec.stageOf(s.progress)));
    }

    private float height(CropState s) {
        CropSpec spec = specs.get(s.crop);
        if (spec == null) return 0.4f;
        return (float) Math.max(0.3, spec.height() * (spec.stageOf(s.progress) + 1) / spec.stages());
    }

    private void show(CropState s) {
        hide(s.id);
        Location at = place(s);
        if (at.getWorld() == null) return;
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setPersistent(false);
            e.getPersistentDataContainer().set(TAG, PersistentDataType.LONG, s.id);
            ItemStack it = new ItemStack(Material.PAPER);
            it.editMeta(m -> m.setItemModel(model(s)));
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setViewRange((float) plugin.getConfig().getDouble("furniture.view-range"));
            e.setBillboard(Display.Billboard.FIXED);
        });
        Interaction hit;
        {
            // in a pot the box starts above the pot's own (which still takes clicks on the pot)
            Location base = s.pot == null ? at.clone().add(0, -0.5, 0) : new Location(at.getWorld(), s.x + 0.5, s.y + 1.03, s.z + 0.5);
            hit = at.getWorld().spawn(base, Interaction.class, i -> {
                i.setPersistent(false);
                i.getPersistentDataContainer().set(TAG, PersistentDataType.LONG, s.id);
                i.setInteractionWidth(0.9f);
                i.setInteractionHeight(s.pot == null ? height(s) : Math.max(0.2f, (float) (height(s) - (1.03 - POT_TOP))));
                i.setResponsive(true);
            });
            byHit.put(hit.getUniqueId(), s.id);
        }
        shown.put(s.id, new Shown(d, hit));
        moisture(s);
    }

    private void hide(long id) {
        Shown sh = shown.remove(id);
        if (sh == null) return;
        sh.display().remove();
        if (sh.hit() != null) {
            byHit.remove(sh.hit().getUniqueId());
            sh.hit().remove();
        }
    }

    private void refresh(CropState s) {
        if (s == null) return;
        Shown sh = shown.get(s.id);
        if (sh == null || !sh.display().isValid()) {
            if (sh != null) show(s);
            return;
        }
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> m.setItemModel(model(s)));
        sh.display().setItemStack(it);
        if (sh.hit() != null) sh.hit().setInteractionHeight(s.pot == null ? height(s) : Math.max(0.2f, (float) (height(s) - (1.03 - POT_TOP))));
        moisture(s);
    }

    /** Wet soil looks wet: the farmland under a field crop shows whether it is watered. */
    private void moisture(CropState s) {
        if (s.pot != null) return;
        World w = Bukkit.getWorld(s.world);
        if (w == null) return;
        Block soil = w.getBlockAt(s.x, s.y - 1, s.z);
        if (!(soil.getBlockData() instanceof Farmland f)) return;
        boolean wet = !s.withered && (sprinkled(s) || s.waterUntil > System.currentTimeMillis());
        int m = wet ? f.getMaximumMoisture() : 0;
        if (f.getMoisture() != m) {
            f.setMoisture(m);
            soil.setBlockData(f, false);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        showChunk(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Set<Long> ids = byChunk.get(chunkKey(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ()));
        if (ids != null) for (Long id : new ArrayList<>(ids)) hide(id);
        // entities of crops removed while unloaded, left over from a crash: tidy up
        for (var ent : e.getChunk().getEntities()) {
            if (ent.getPersistentDataContainer().has(TAG, PersistentDataType.LONG)) ent.remove();
        }
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

    /** Quality stars on a harvested item (0 = none). */
    public static int stars(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Integer v = it.getItemMeta().getPersistentDataContainer().get(QUALITY, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    public static ItemStack withStars(ItemStack it, int stars) {
        it.editMeta(m -> {
            m.getPersistentDataContainer().set(QUALITY, PersistentDataType.INTEGER, stars);
            List<Component> lore = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
            lore.addFirst(Lang.item("crop.quality-lore", "stars", "★".repeat(stars) + "<dark_gray>" + "★".repeat(3 - stars)));
            m.lore(lore);
        });
        return it;
    }

    private int water(ItemStack can) {
        if (!can.hasItemMeta()) return 0;
        return can.getItemMeta().getPersistentDataContainer().getOrDefault(WATER, PersistentDataType.INTEGER, 0);
    }

    private void setWater(ItemStack can, CropTools.Can spec, int water) {
        can.editMeta(m -> {
            m.getPersistentDataContainer().set(WATER, PersistentDataType.INTEGER, water);
            if (m instanceof Damageable d) {
                d.setMaxDamage(spec.capacity() + 1);
                d.setDamage(spec.capacity() - water);
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Planting, filling cans
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseItem(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        String k = key(hand);
        if (k == null) return;
        // fill a watering can at water
        CropTools.Can can = tools.cans.get(k);
        if (can != null && (!hand.hasItemMeta() || !hand.getItemMeta().getPersistentDataContainer().has(WATER, PersistentDataType.INTEGER))) {
            setWater(hand, can, 0);
        }
        if (can != null && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            RayTraceResult r = p.rayTraceBlocks(5, FluidCollisionMode.SOURCE_ONLY);
            Block b = r == null ? null : r.getHitBlock();
            boolean source = b != null && (b.getType() == Material.WATER && b.getBlockData() instanceof Levelled l && l.getLevel() == 0
                    || b.getType() == Material.WATER_CAULDRON);
            if (source) {
                e.setCancelled(true);
                setWater(hand, can, can.capacity());
                HudModule.flash(p, Lang.mm("crop.can-filled", "water", can.capacity(), "capacity", can.capacity()));
                p.playSound(p.getLocation(), Sound.ITEM_BUCKET_FILL, 0.8f, 1.2f);
            }
            return;
        }
        // plant a seed on farmland (other right-clicks - chests, doors - work normally while holding seeds)
        String cropId = seeds.get(k);
        if (cropId == null || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (e.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        CropSpec spec = specs.get(cropId);
        Block soil = e.getClickedBlock();
        if (!spec.soil().contains(soil.getType().getKey().asString())) {
            if (soil.getType() == Material.DIRT || soil.getType() == Material.GRASS_BLOCK) HudModule.flash(p, Lang.mm("crop.wrong-soil"));
            return;
        }
        e.setCancelled(true);
        Block above = soil.getRelative(BlockFace.UP);
        if (e.getBlockFace() != BlockFace.UP || !above.isEmpty() || at(above).isPresent()) return;
        plant(p, spec, above, null, hand);
    }

    private void plant(Player p, CropSpec spec, Block at, UUID pot, ItemStack hand) {
        CropEvent ev = new CropEvent(CropEvent.Action.PLANT, p, spec, at.getLocation(), List.of());
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return;
        long now = System.currentTimeMillis();
        CropState s = new CropState(nextId++, spec.id(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), pot, p.getUniqueId(), now);
        store.insert(s);
        index(s);
        show(s);
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) hand.subtract(1);
        p.swingMainHand();
        p.playSound(at.getLocation(), Sound.ITEM_CROP_PLANT, 1f, 1f);
    }

    // ------------------------------------------------------------------------------------------
    // Using a crop
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

    private void use(Player p, CropState s) {
        CropSpec spec = specs.get(s.crop);
        Location loc = place(s);
        if (spec == null) {
            // its definition is missing (a YAML error, a renamed crop): keep it until the file is fixed
            HudModule.flash(p, Lang.mm("crop.unknown", "crop", s.crop));
            return;
        }
        CropEvent ev = new CropEvent(CropEvent.Action.USE, p, spec, loc, List.of());
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return;
        long now = System.currentTimeMillis();
        settle(s, now);
        ItemStack hand = p.getInventory().getItemInMainHand();
        String k = key(hand);
        CropTools.Can can = k == null ? null : tools.cans.get(k);
        if (can != null) {
            int left = water(hand);
            if (left <= 0) {
                HudModule.flash(p, Lang.mm("crop.can-empty"));
                return;
            }
            int n = 0;
            List<CropState> watered = new ArrayList<>();
            for (CropState o : near(s, can.radius())) {
                CropSpec os = specs.get(o.crop);
                if (os == null || o.withered) continue;
                if (o != s) {
                    CropEvent oe = new CropEvent(CropEvent.Action.USE, p, os, place(o), List.of());
                    Bukkit.getPluginManager().callEvent(oe);
                    if (oe.isCancelled()) continue;   // someone else's crop next door
                }
                settle(o, now);
                watered.add(o);
                CropMath.water(o, os, now);
                refresh(o);
                n++;
            }
            store.save(watered);
            setWater(hand, can, left - 1);
            loc.getWorld().spawnParticle(Particle.SPLASH, loc, 20 + 10 * n, 0.4 + can.radius(), 0.2, 0.4 + can.radius(), 0);
            p.playSound(loc, Sound.ENTITY_GENERIC_SPLASH, 0.5f, 1.4f);
            HudModule.flash(p, Lang.mm("crop.watered", "count", n, "water", left - 1, "capacity", can.capacity()));
            return;
        }
        CropTools.Fertilizer fert = k == null ? null : tools.fertilizers.get(k);
        if (fert != null && !s.withered && !spec.ripe(s.progress)) {
            if (k.equals(s.fertilizer)) {
                HudModule.flash(p, Lang.mm("crop.already-fertilized"));
                return;
            }
            s.fertilizer = k;
            hand.subtract(1);
            store.save(List.of(s));
            loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc, 12, 0.3, 0.3, 0.3);
            p.playSound(loc, Sound.ITEM_BONE_MEAL_USE, 1f, 1f);
            HudModule.flash(p, Lang.mm("crop.fertilized", "item", name(k)));
            return;
        }
        if (k != null && tools.pesticides.contains(k)) {
            if (!s.pests() || s.withered) {
                HudModule.flash(p, Lang.mm("crop.no-pests"));
                return;
            }
            s.pestSince = 0;
            hand.subtract(1);
            store.save(List.of(s));
            refresh(s);
            loc.getWorld().spawnParticle(Particle.CLOUD, loc, 10, 0.3, 0.3, 0.3, 0.01);
            p.playSound(loc, Sound.BLOCK_BREWING_STAND_BREW, 0.6f, 1.5f);
            HudModule.flash(p, Lang.mm("crop.cured"));
            return;
        }
        if (s.withered) {
            remove(s);
            p.playSound(loc, Sound.BLOCK_GRASS_BREAK, 1f, 0.8f);
            HudModule.flash(p, Lang.mm("crop.cleared"));
            return;
        }
        if (spec.ripe(s.progress)) {
            harvest(p, s, spec);
            return;
        }
        status(p, s, spec, now);
    }

    /** Crops within a square radius of a block (looked up by chunk). */
    private List<CropState> inRange(Block b, int radius) {
        List<CropState> out = new ArrayList<>();
        String w = b.getWorld().getName();
        for (int cx = (b.getX() - radius) >> 4; cx <= (b.getX() + radius) >> 4; cx++) {
            for (int cz = (b.getZ() - radius) >> 4; cz <= (b.getZ() + radius) >> 4; cz++) {
                for (Long id : byChunk.getOrDefault(chunkKey(w, cx, cz), Set.of())) {
                    CropState s = crops.get(id);
                    if (s != null && Math.abs(s.x - b.getX()) <= radius && Math.abs(s.z - b.getZ()) <= radius && Math.abs(s.y - b.getY()) <= 1) out.add(s);
                }
            }
        }
        return out;
    }

    private List<CropState> near(CropState s, int radius) {
        List<CropState> out = new ArrayList<>();
        if (radius <= 0) {
            out.add(s);
            return out;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Long id = byBlock.get(blockKey(s.world, s.x + dx, s.y + dy, s.z + dz));
                    if (id != null && crops.containsKey(id)) out.add(crops.get(id));
                }
            }
        }
        return out;
    }

    private String name(String itemId) {
        return item(itemId, 1).map(i -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(i.effectiveName()))
                .orElse(itemId);
    }

    private void status(Player p, CropState s, CropSpec spec, long now) {
        int stage = spec.stageOf(s.progress);
        int pct = (int) Math.min(100, s.progress * 100 / Math.max(1, spec.growMillis()));
        String water;
        if (sprinkled(s)) water = Lang.raw("crop.water-sprinkler");
        else if (s.waterUntil > now) water = Lang.raw("crop.water-left", "hours", String.format("%.1f", (s.waterUntil - now) / 3_600_000.0));
        else water = Lang.raw("crop.water-dry", "hours", String.format("%.1f", Math.max(0, spec.witherHours() - (now - s.drySince) / 3_600_000.0)));
        HudModule.flash(p, Lang.mm(s.pests() ? "crop.status-pests" : "crop.status", "crop", spec.name(), "stage", stage + 1, "stages", spec.stages(),
                "pct", pct, "water", water, "fertilizer", s.fertilizer == null ? Lang.raw("crop.no-fertilizer") : name(s.fertilizer)));
    }

    /** True if it was harvested (false: a content cancelled it). */
    private boolean harvest(Player p, CropState s, CropSpec spec) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        CropTools.Fertilizer fert = tools.fertilizers.get(s.fertilizer);
        List<ItemStack> drops = new ArrayList<>();
        for (CropSpec.Drop d : spec.harvest()) {
            int n = d.max() > d.min() ? r.nextInt(d.min(), d.max() + 1) : d.min();
            if (d.quality() && fert != null) n += fert.yield();
            item(d.item(), n).ifPresent(drops::add);
        }
        Location loc = place(s);
        CropEvent ev = new CropEvent(CropEvent.Action.HARVEST, p, spec, loc, drops);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return false;
        int stars = CropMath.stars(s, fert, ev.qualityBonus(), r);
        Set<String> quality = new HashSet<>();
        for (CropSpec.Drop d : spec.harvest()) if (d.quality()) quality.add(d.item());
        for (ItemStack it : ev.drops()) {
            if (it == null || it.isEmpty()) continue;
            if (quality.contains(key(it))) withStars(it, stars);
            loc.getWorld().dropItemNaturally(loc, it);
        }
        p.playSound(loc, Sound.BLOCK_CROP_BREAK, 1f, 1f);
        if (stars == 3) p.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 1.8f);
        HudModule.flash(p, Lang.mm("crop.harvested", "crop", spec.name(), "stars", "★".repeat(stars) + "<dark_gray>" + "★".repeat(3 - stars)));
        if (CropMath.harvested(s, spec)) {
            store.save(List.of(s));   // at once: a crash must not make it ripe again
            refresh(s);
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
        settle(s, System.currentTimeMillis());
        if (!s.withered && spec.ripe(s.progress)) {
            if (harvest(p, s, spec) && crops.containsKey(s.id)) remove(s);   // breaking a regrowing plant removes it
            return;
        }
        pop(s, p);
    }

    /** Takes a crop out, giving its seed back unless it withered. */
    private void pop(CropState s, Player by) {
        CropSpec spec = specs.get(s.crop);
        Location loc = place(s);
        if (spec != null && !s.withered && loc.getWorld() != null) item(spec.seed(), 1).ifPresent(seed -> loc.getWorld().dropItemNaturally(loc, seed));
        if (loc.getWorld() != null) loc.getWorld().playSound(loc, Sound.BLOCK_CROP_BREAK, 1f, 1f);
        remove(s);
    }

    private void remove(CropState s) {
        hide(s.id);
        unindex(s);
        store.delete(s.id);
    }

    // ------------------------------------------------------------------------------------------
    // Pots and sprinklers (furniture behaviours "stsv:crop_pot", "stsv:sprinkler")
    // ------------------------------------------------------------------------------------------

    private final class PotBehavior implements FurnitureBehavior {
        @Override
        public void onInteract(Player p, Furniture f) {
            Long id = byPot.get(f.display().getUniqueId());
            if (id != null && crops.containsKey(id)) {
                use(p, crops.get(id));
                return;
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            String cropId = seeds.get(key(hand));
            if (cropId == null) {
                HudModule.flash(p, Lang.mm("crop.pot-empty"));
                return;
            }
            if (at(f.location().getBlock()).isPresent()) return;
            plant(p, specs.get(cropId), f.location().getBlock(), f.display().getUniqueId(), hand);
        }

        @Override
        public void onBreak(Player p, Furniture f) {
            Long id = byPot.get(f.display().getUniqueId());
            if (id == null || !crops.containsKey(id)) return;
            CropState s = crops.get(id);
            CropSpec spec = specs.get(s.crop);
            settle(s, System.currentTimeMillis());
            if (spec != null && !s.withered && spec.ripe(s.progress) && harvest(p, s, spec) && crops.containsKey(id)) remove(s);
            else if (crops.containsKey(id)) pop(s, p);
        }
    }

    private final class SprinklerBehavior implements FurnitureBehavior {
        private void settleAround(Block b, int radius) {
            long now = System.currentTimeMillis();
            for (CropState s : inRange(b, radius)) settle(s, now);
        }

        @Override
        public void onPlace(Player p, Furniture f) {
            Integer radius = tools.sprinklers.get(f.item().key().asString());
            if (radius == null) return;
            Block b = f.location().getBlock();
            settleAround(b, radius);   // growth so far was without it
            CropStore.Sprinkler sp = new CropStore.Sprinkler(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), radius);
            addSprinkler(sp);
            store.putSprinkler(sp);
            for (CropState s : inRange(b, radius)) refresh(s);
            HudModule.flash(p, Lang.mm("crop.sprinkler-placed", "size", radius * 2 + 1));
        }

        @Override
        public void onBreak(Player p, Furniture f) {
            Block b = f.location().getBlock();
            Integer radius = tools.sprinklers.get(f.item().key().asString());
            settleAround(b, radius == null ? 3 : radius);   // growth until now was with it
            removeSprinkler(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
            store.deleteSprinkler(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
            long now = System.currentTimeMillis();
            // what it watered stays wet for one watering's time
            List<CropState> reached = radius == null ? List.of() : inRange(b, radius);
            for (CropState s : reached) {
                CropSpec spec = specs.get(s.crop);
                if (spec != null && !sprinkled(s)) CropMath.water(s, spec, now);
                refresh(s);
            }
            store.save(reached);
        }

        @Override
        public void onInteract(Player p, Furniture f) {
            Integer radius = tools.sprinklers.get(f.item().key().asString());
            HudModule.flash(p, Lang.mm("crop.sprinkler-info", "size", radius == null ? 0 : radius * 2 + 1));
        }
    }

    private void sprinkle() {
        for (CropStore.Sprinkler sp : sprinklers) {
            World w = Bukkit.getWorld(sp.world());
            if (w == null || !w.isChunkLoaded(sp.x() >> 4, sp.z() >> 4)) continue;
            w.spawnParticle(Particle.SPLASH, sp.x() + 0.5, sp.y() + 0.9, sp.z() + 0.5, 12, sp.radius() * 0.6, 0.1, sp.radius() * 0.6, 0);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Keeping the soil under crops
    // ------------------------------------------------------------------------------------------

    private boolean cropAbove(Block soil) {
        return at(soil.getRelative(BlockFace.UP)).filter(s -> s.pot == null).isPresent();
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && cropAbove(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMoisture(MoistureChangeEvent e) {
        if (cropAbove(e.getBlock())) e.setCancelled(true);   // the crop decides how wet it looks
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL && e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.FARMLAND
                && cropAbove(e.getClickedBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobTrample(EntityInteractEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && cropAbove(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceIntoCrop(BlockPlaceEvent e) {
        if (at(e.getBlockPlaced()).filter(s -> s.pot == null).isPresent()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (at(e.getToBlock()).filter(s -> s.pot == null).isPresent()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSoilBreak(BlockBreakEvent e) {
        at(e.getBlock().getRelative(BlockFace.UP)).filter(s -> s.pot == null).ifPresent(s -> pop(s, e.getPlayer()));
    }

    private boolean touchesCrops(List<Block> moved, BlockFace dir) {
        for (Block b : moved) {
            if (cropAbove(b) || at(b.getRelative(dir)).filter(s -> s.pot == null).isPresent()) return true;
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

    /** For contents: is this item a seed of a crop? */
    public Optional<CropSpec> seedOf(ItemStack it) {
        String k = key(it);
        return Optional.ofNullable(k == null ? null : specs.get(seeds.get(k)));
    }
}
