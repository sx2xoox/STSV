package dev.stsv.core.minigame.circuit;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.minigame.MinigameModule;
import dev.stsv.core.minigame.ReturnPoint;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.core.vehicle.VehicleModule;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 서킷: kart races on the tracks of contents/&lt;pack&gt;/circuit/tracks (built in the minigame world by {@link TrackBuilder}),
 * in the karts of circuit/karts.yml. Holds the tracks and their builds, the races ({@link Race}), the waiting room's menu
 * and the records ({@link CircuitStore}). {@link MinigameModule} owns the world, its protection and the way back.
 */
public final class Circuit {

    /** Bump when TrackLayout draws differently: every track is built again on the next start. */
    static final int LAYOUT_VERSION = 1;
    public static final String LOBBY_MENU = "stsv:circuit_lobby";
    public static final NamespacedKey ITEM = new NamespacedKey("stsv", "circuit_item");
    public static final NamespacedKey CHOICE = new NamespacedKey("stsv", "circuit_kart");
    public static final NamespacedKey LABEL = new NamespacedKey("stsv", "circuit_label");
    public static final NamespacedKey SAFE_FIREWORK = new NamespacedKey("stsv", "circuit_firework");

    private final STSVPlugin plugin;
    private final MinigameModule module;
    private final CircuitStore store;
    private final Map<String, TrackSpec> tracks = new LinkedHashMap<>();
    private final Map<String, TrackLayout> layouts = new HashMap<>();
    private final Map<String, String> hashes = new HashMap<>();
    private final List<KartSpec> karts = new ArrayList<>();
    private final Map<String, Race> races = new LinkedHashMap<>();
    private final Map<String, Integer> building = new LinkedHashMap<>();
    private final Deque<String> buildQueue = new ArrayDeque<>();
    private TrackBuilder builder;
    private final Map<UUID, Long> leaveAsk = new HashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();
    private final List<String> problems = new ArrayList<>();

    private int lobbySeconds, maxRacers, finishGrace, maxRaceMinutes, blocksPerTick;
    private boolean bumping, announce;

    public Circuit(STSVPlugin plugin, MinigameModule module) {
        this.plugin = plugin;
        this.module = module;
        this.store = new CircuitStore(plugin.db());
    }

    // ------------------------------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------------------------------

    public void enable() {
        store.init();
        load();
    }

    public void load() {
        var c = plugin.getConfig();
        lobbySeconds = Math.clamp(c.getInt("minigames.circuit.lobby-seconds"), 5, 120);
        maxRacers = Math.clamp(c.getInt("minigames.circuit.max-racers"), 1, 12);
        finishGrace = Math.clamp(c.getInt("minigames.circuit.finish-grace-seconds"), 5, 120);
        maxRaceMinutes = Math.clamp(c.getInt("minigames.circuit.max-race-minutes"), 2, 30);
        blocksPerTick = Math.clamp(c.getInt("minigames.build-blocks-per-tick"), 500, 50_000);
        bumping = c.getBoolean("minigames.circuit.kart-bump");
        announce = c.getBoolean("minigames.circuit.announce");
        problems.clear();
        Map<String, TrackSpec> read = new LinkedHashMap<>();
        Map<String, String> sums = new HashMap<>();
        List<KartSpec> kartsRead = new ArrayList<>();
        for (ContentPack pack : plugin.content().packs()) {
            Path dir = pack.dir().resolve("circuit");
            Path kf = dir.resolve("karts.yml");
            if (Files.isRegularFile(kf)) {
                try {
                    for (KartSpec k : KartSpec.parseAll(YamlConfiguration.loadConfiguration(kf.toFile()))) {
                        kartsRead.removeIf(o -> o.id().equals(k.id()));
                        kartsRead.add(k);
                    }
                } catch (IllegalArgumentException e) {
                    problems.add(pack.id() + "/circuit/karts.yml: " + e.getMessage());
                }
            }
            Path td = dir.resolve("tracks");
            if (!Files.isDirectory(td)) continue;
            try (var files = Files.list(td)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                    String id = f.getFileName().toString().replace(".yml", "");
                    try {
                        String text = Files.readString(f, StandardCharsets.UTF_8);
                        YamlConfiguration y = new YamlConfiguration();
                        y.loadFromString(text);
                        read.put(id, TrackSpec.parse(id, y));
                        sums.put(id, sha1(LAYOUT_VERSION + "\n" + text));
                    } catch (Exception e) {
                        problems.add(pack.id() + "/circuit/tracks/" + id + ".yml: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                problems.add(pack.id() + "/circuit/tracks: " + e.getMessage());
            }
        }
        Set<Integer> slots = new HashSet<>();
        for (TrackSpec t : List.copyOf(read.values())) {
            if (!slots.add(t.slot())) {
                problems.add(t.id() + ": slot " + t.slot() + " 이(가) 다른 트랙과 겹침 (빠짐)");
                read.remove(t.id());
            }
        }
        for (KartSpec k : List.copyOf(kartsRead)) {
            CustomItem item = kartItem(k);
            if (item == null || item.vehicle() == null) {
                problems.add("카트 " + k.id() + ": 탈것 아이템 " + k.item() + " 이(가) 없음 (빠짐)");
                kartsRead.remove(k);
            }
        }
        problems.forEach(p -> plugin.getLogger().warning("[서킷] " + p));
        karts.clear();
        karts.addAll(kartsRead);
        // tracks whose layout changed are drawn again (and built again, below); races on them go on as they were
        for (Map.Entry<String, TrackSpec> e : read.entrySet()) {
            if (!sums.get(e.getKey()).equals(hashes.get(e.getKey()))) layouts.remove(e.getKey());
        }
        tracks.clear();
        tracks.putAll(read);
        hashes.clear();
        hashes.putAll(sums);
        plugin.getLogger().info("[서킷] 트랙 " + tracks.size() + "개, 카트 " + karts.size() + "종");
    }

    /** Builds the tracks never built here, or changed since (one at a time, in the background). */
    public void buildChanged(World world) {
        YamlConfiguration built = builtFile();
        for (TrackSpec t : tracks.values()) {
            if (!hashes.get(t.id()).equals(built.getString(t.id() + ".hash"))) queueBuild(t.id(), world);
            else spawnLabels(world, t.id());
        }
    }

    public void queueBuild(String id, World world) {
        if (!tracks.containsKey(id) || buildQueue.contains(id) || building.containsKey(id)) return;
        buildQueue.add(id);
        building.put(id, 0);
        nextBuild(world);
    }

    private void nextBuild(World world) {
        if (builder != null && builder.running()) return;
        String id = buildQueue.poll();
        if (id == null) {
            builder = null;
            return;
        }
        TrackLayout layout = layout(id);
        YamlConfiguration built = builtFile();
        TrackBuilder.Old old = built.contains(id + ".bounds")
                ? new TrackBuilder.Old(built.getInt(id + ".bounds.min-x"), built.getInt(id + ".bounds.max-x"), built.getInt(id + ".bounds.min-z"),
                built.getInt(id + ".bounds.max-z"), built.getInt(id + ".bounds.max-y")) : null;
        removeLabels(world, id);
        plugin.getLogger().info("[서킷] '" + layout.spec().name() + "' 짓는 중 (" + layout.blockCount() + "블록 + 바닥)");
        long started = System.currentTimeMillis();
        builder = new TrackBuilder(plugin, world, layout, old, blocksPerTick, pct -> building.put(id, pct), () -> {
            building.remove(id);
            YamlConfiguration b = builtFile();
            b.set(id + ".hash", hashes.get(id));
            b.set(id + ".bounds.min-x", layout.minX());
            b.set(id + ".bounds.max-x", layout.maxX());
            b.set(id + ".bounds.min-z", layout.minZ());
            b.set(id + ".bounds.max-z", layout.maxZ());
            b.set(id + ".bounds.max-y", layout.maxY());
            b.set(id + ".built", System.currentTimeMillis());
            saveBuilt(b);
            builtNow.add(id);
            spawnLabels(world, id);
            plugin.getLogger().info("[서킷] '" + layout.spec().name() + "' 완성 (" + (System.currentTimeMillis() - started) / 1000 + "초)");
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("stsv.admin")) p.sendMessage(Lang.msg("circuit.built", "track", layout.spec().name()));
            builder = null;
            nextBuild(world);
        });
        builder.start();
    }

    /** the tracks standing in the world now (built at least once: data/circuit_built.yml), kept in memory */
    private final Set<String> builtNow = new HashSet<>();

    private File builtPath() {
        return new File(plugin.getDataFolder(), "data/circuit_built.yml");
    }

    private YamlConfiguration builtFile() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(builtPath());
        for (String id : y.getKeys(false)) if (y.contains(id + ".hash")) builtNow.add(id);
        return y;
    }

    private void saveBuilt(YamlConfiguration y) {
        try {
            builtPath().getParentFile().mkdirs();
            y.save(builtPath());
        } catch (IOException e) {
            plugin.getLogger().warning("[서킷] 건설 기록 저장 실패: " + e.getMessage());
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    public TrackLayout layout(String id) {
        return layouts.computeIfAbsent(id, k -> new TrackLayout(tracks.get(k)));
    }

    public boolean ready(String id) {
        return tracks.containsKey(id) && !building.containsKey(id) && builtNow.contains(id);
    }

    public void disable() {
        for (Race r : List.copyOf(races.values())) r.close(false);
        races.clear();
        if (builder != null) builder.cancel();
        builder = null;
        buildQueue.clear();
        building.clear();
        World w = module.world();
        if (w != null) for (String id : tracks.keySet()) removeLabels(w, id);
    }

    // ------------------------------------------------------------------------------------------
    // The floating names (non-persistent: drawn again whenever their chunk loads)
    // ------------------------------------------------------------------------------------------

    private void spawnLabels(World w, String id) {
        TrackLayout l = layout(id);
        int ox = l.spec().originX();
        for (TrackLayout.Label lab : l.labels()) {
            int cx = Math.floorDiv((int) Math.floor(lab.x()) + ox, 16), cz = Math.floorDiv((int) Math.floor(lab.z()), 16);
            if (!w.isChunkLoaded(cx, cz)) continue;
            Location at = new Location(w, lab.x() + ox, TrackLayout.BASE_Y + lab.y(), lab.z(), lab.yaw(), 0);
            for (Entity e : w.getNearbyEntities(at, 0.6, 0.6, 0.6)) if (id.equals(e.getPersistentDataContainer().get(LABEL, PersistentDataType.STRING))) e.remove();
            w.spawn(at, TextDisplay.class, t -> {
                t.setPersistent(false);
                t.text(Text.mm(lab.text()));
                t.setBillboard(Display.Billboard.FIXED);
                t.setRotation(lab.yaw(), 0);
                t.setShadowed(true);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                t.setBrightness(new Display.Brightness(15, 15));
                t.setViewRange(4f);
                t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(lab.scale()), new AxisAngle4f()));
                t.getPersistentDataContainer().set(LABEL, PersistentDataType.STRING, id);
            });
        }
    }

    private void removeLabels(World w, String id) {
        for (TextDisplay t : w.getEntitiesByClass(TextDisplay.class))
            if (id.equals(t.getPersistentDataContainer().get(LABEL, PersistentDataType.STRING))) t.remove();
    }

    /** A chunk of the minigame world loaded: the names that stand in it appear. */
    public void chunkLoaded(Chunk c) {
        for (TrackSpec t : tracks.values()) {
            if (!ready(t.id())) continue;
            TrackLayout l = layout(t.id());
            for (TrackLayout.Label lab : l.labels()) {
                int cx = Math.floorDiv((int) Math.floor(lab.x()) + t.originX(), 16), cz = Math.floorDiv((int) Math.floor(lab.z()), 16);
                if (cx == c.getX() && cz == c.getZ()) {
                    Bukkit.getScheduler().runTask(plugin, () -> spawnLabels(c.getWorld(), t.id()));
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Joining, leaving
    // ------------------------------------------------------------------------------------------

    public void tick() {
        for (Race r : List.copyOf(races.values())) {
            try {
                r.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "[서킷] 레이스 오류 (" + r.track.id() + "): 레이스를 끝냄", e);
                r.close(false);
            }
        }
    }

    /** The race a player is in (waiting or racing), or null. */
    public Race raceOf(Player p) {
        for (Race r : races.values()) if (r.racers.containsKey(p.getUniqueId())) return r;
        return null;
    }

    /** Is the player driving in a race now (out in the minigame world)? */
    public boolean racing(Player p) {
        Race r = raceOf(p);
        if (r == null || !r.running()) return false;
        Race.Racer x = r.racer(p.getUniqueId());
        return x != null && x.racing && !x.left;
    }

    /** Why a player cannot race now (a message key), or null. */
    public String cannotRace(Player p) {
        if (!p.isOnline() || p.isDead()) return "circuit.not-now";
        if (plugin.cosmetics() != null && plugin.cosmetics().inWardrobe(p)) return "circuit.in-wardrobe";
        if (plugin.emotes() != null && plugin.emotes().emoting(p)) return "circuit.not-now";
        if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return "circuit.not-now";
        if (ReturnPoint.has(p)) return "circuit.not-now";
        return null;
    }

    /** /서킷 참가 [track]: into a waiting room (an open one, or a new race on that track or a random free one). */
    public void join(Player p, String trackId) {
        Race mine = raceOf(p);
        if (mine != null) {
            if (mine.lobby()) openLobby(p, mine);
            else p.sendMessage(Lang.msg("circuit.already"));
            return;
        }
        String why = cannotRace(p);
        if (why != null) {
            p.sendMessage(Lang.msg(why));
            return;
        }
        if (trackId != null && !tracks.containsKey(trackId)) {
            TrackSpec byName = byName(trackId);
            if (byName == null) {
                p.sendMessage(Lang.msg("circuit.no-track", "track", trackId));
                return;
            }
            trackId = byName.id();
        }
        Race race = null;
        for (Race r : races.values()) {
            if (!r.lobby() || r.racers.size() >= maxRacers) continue;
            if (trackId == null || r.track.id().equals(trackId)) {
                race = r;
                break;
            }
        }
        if (race == null) {
            List<String> free = new ArrayList<>();
            for (String id : tracks.keySet()) if (ready(id) && !races.containsKey(id) && (trackId == null || id.equals(trackId))) free.add(id);
            if (free.isEmpty()) {
                p.sendMessage(Lang.msg(trackId != null && races.containsKey(trackId) ? "circuit.track-busy"
                        : trackId != null && building.containsKey(trackId) ? "circuit.building" : "circuit.no-free"));
                return;
            }
            World w = module.world();
            if (w == null) {
                p.sendMessage(Lang.msg("circuit.not-now"));
                return;
            }
            String id = free.get(ThreadLocalRandom.current().nextInt(free.size()));
            race = new Race(this, tracks.get(id), layout(id), w, lobbySeconds);
            races.put(id, race);
            if (announce) {
                Component line = Lang.msg("circuit.opened", "player", Names.mini(p), "track", race.track.name(), "seconds", lobbySeconds)
                        .append(Component.space()).append(Lang.mm("circuit.join-button")
                                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/서킷 참가 " + id))
                                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Lang.mm("circuit.join-hover"))));
                for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p) && raceOf(o) == null) o.sendMessage(line);
            }
        }
        String[] choice = choice(p);
        KartSpec kart = kart(choice[0]);
        race.join(p, kart, choice[1] != null && kart.colours().contains(choice[1]) ? choice[1] : kart.own());
        for (Race.Racer o : race.racers.values()) {
            Player op = o.player();
            if (op != null && !op.equals(p)) op.sendMessage(Lang.msg("circuit.joined-other", "player", Names.mini(p), "count", race.racers.size()));
        }
        p.sendMessage(Lang.msg("circuit.joined", "track", race.track.name(), "laps", race.track.laps(), "seconds", race.lobbySeconds()));
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.6f, 1.6f);
        openLobby(p, race);
    }

    /** /서킷 나가기: out of the waiting room, or out of the race (put back as they were). */
    public void leave(Player p) {
        Race r = raceOf(p);
        if (r == null) {
            p.sendMessage(Lang.msg("circuit.not-in"));
            return;
        }
        if (r.lobby()) {
            r.leaveLobby(p);
            closeLobbyMenu(p);
            p.sendMessage(Lang.msg("circuit.left-lobby"));
            if (r.racers.isEmpty()) r.close(false);
            return;
        }
        Race.Racer x = r.racer(p.getUniqueId());
        if (x != null && x.racing) r.out(x, "circuit.left");
        else {
            r.racers.remove(p.getUniqueId());
            p.sendMessage(Lang.msg("circuit.left-lobby"));
        }
    }

    /** A player quits: out of their race; anyone mid-race is put back now (their file is saved with their things). */
    public void quit(Player p) {
        Race r = raceOf(p);
        if (r == null) return;
        if (r.lobby()) {
            r.leaveLobby(p);
            if (r.racers.isEmpty()) r.close(false);
            return;
        }
        Race.Racer x = r.racer(p.getUniqueId());
        if (x != null && x.racing) r.out(x, null);
        else r.racers.remove(p.getUniqueId());
    }

    void closed(Race r) {
        races.remove(r.track.id(), r);
    }

    /** Back where they were, with everything (the race's teleport is allowed through the world's guard). */
    public void putBack(Player p) {
        teleporting.add(p.getUniqueId());
        try {
            ReturnPoint.restore(p, module.fallback());
        } finally {
            teleporting.remove(p.getUniqueId());
        }
    }

    public void teleporting(Player p, boolean on) {
        if (on) teleporting.add(p.getUniqueId());
        else teleporting.remove(p.getUniqueId());
    }

    /** Is this teleport the race's own (to the grid, back home, a respawn)? */
    public boolean ownTeleport(Player p) {
        return teleporting.contains(p.getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // The racers' two items: back to the last gate, leave the race
    // ------------------------------------------------------------------------------------------

    void giveRaceItems(Player p) {
        ItemStack back = new ItemStack(Material.RECOVERY_COMPASS);
        back.editMeta(m -> {
            m.itemName(Lang.item("circuit.item-respawn"));
            m.lore(Lang.items("circuit.item-respawn-lore"));
            m.getPersistentDataContainer().set(ITEM, PersistentDataType.STRING, "respawn");
        });
        ItemStack leave = new ItemStack(Material.BARRIER);
        leave.editMeta(m -> {
            m.itemName(Lang.item("circuit.item-leave"));
            m.lore(Lang.items("circuit.item-leave-lore"));
            m.getPersistentDataContainer().set(ITEM, PersistentDataType.STRING, "leave");
        });
        p.getInventory().setItem(0, back);
        p.getInventory().setItem(8, leave);
        p.getInventory().setHeldItemSlot(4);
    }

    /** A racer used one of their items (right click). */
    public void useItem(Player p, String what) {
        Race r = raceOf(p);
        if (r == null) return;
        if (what.equals("respawn")) r.askRespawn(p);
        else if (what.equals("leave")) {
            long now = System.currentTimeMillis();
            Long asked = leaveAsk.get(p.getUniqueId());
            if (asked != null && now - asked < 3000) {
                leaveAsk.remove(p.getUniqueId());
                leave(p);
            } else {
                leaveAsk.put(p.getUniqueId(), now);
                HudModule.flash(p, Lang.mm("circuit.leave-confirm"), 3000);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Karts and the player's choice (kept on the player: kart:colour)
    // ------------------------------------------------------------------------------------------

    public List<KartSpec> karts() {
        return karts;
    }

    KartSpec kart(String id) {
        for (KartSpec k : karts) if (k.id().equals(id)) return k;
        return karts.getFirst();
    }

    CustomItem kartItem(KartSpec k) {
        return plugin.content().item(k.item()).filter(CustomItem::isVehicle).orElse(null);
    }

    private String[] choice(Player p) {
        String s = p.getPersistentDataContainer().get(CHOICE, PersistentDataType.STRING);
        if (s == null || !s.contains(":")) return new String[]{karts.getFirst().id(), null};
        String[] a = s.split(":", 2);
        return new String[]{a[0], a[1]};
    }

    private void remember(Player p, Race.Racer r) {
        p.getPersistentDataContainer().set(CHOICE, PersistentDataType.STRING, r.kart.id() + ":" + r.colour);
    }

    /** A paint's name and colour (vehicle_tuning.yml paints), for the menu. */
    private String colourName(String id) {
        return Lang.raw("circuit.colour." + id);
    }

    private int colourRgb(String id) {
        return switch (id) {
            case "red" -> 0xe21806;
            case "orange" -> 0xf08020;
            case "yellow" -> 0xf9c228;
            case "green" -> 0x2fa84f;
            case "blue" -> 0x3b82e0;
            case "navy" -> 0x24468c;
            case "purple" -> 0x7a3fc8;
            case "pink" -> 0xec869f;
            case "white" -> 0xf1f3f6;
            case "black" -> 0x2d333b;
            case "silver" -> 0x7e8890;
            case "wine" -> 0x8a1423;
            case "pearl" -> 0xf3e9d6;
            default -> 0xaaaaaa;
        };
    }

    // ------------------------------------------------------------------------------------------
    // The waiting room's menu (menus/circuit.yml: circuit_lobby)
    // ------------------------------------------------------------------------------------------

    private final Map<UUID, MenuView> lobbyViews = new HashMap<>();

    void openLobby(Player p, Race race) {
        plugin.menus().open(p, LOBBY_MENU, new Lobby(race)).ifPresent(v -> lobbyViews.put(p.getUniqueId(), v));
    }

    void closeLobbyMenu(Player p) {
        MenuView v = lobbyViews.remove(p.getUniqueId());
        if (v != null && p.getOpenInventory().getTopInventory().getHolder(false) == v) p.closeInventory();
    }

    void refreshLobbyMenus(Race race) {
        for (Race.Racer r : race.racers.values()) {
            Player p = r.player();
            MenuView v = p == null ? null : lobbyViews.get(p.getUniqueId());
            if (v == null) continue;
            if (p.getOpenInventory().getTopInventory().getHolder(false) != v) {
                lobbyViews.remove(p.getUniqueId());
                continue;
            }
            v.refresh();
        }
    }

    private static final List<Character> KART_KEYS = List.of('A', 'B', 'C', 'D', 'E');
    private static final List<Character> COLOUR_KEYS = List.of('a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i');

    private final class Lobby implements MenuHandler {
        final Race race;

        Lobby(Race race) {
            this.race = race;
        }

        @Override
        public Map<String, String> placeholders(MenuView v) {
            Map<String, String> m = new HashMap<>();
            Race.Racer me = race.racer(v.player().getUniqueId());
            m.put("track", race.track.name());
            m.put("subtitle", race.track.subtitle());
            m.put("laps", String.valueOf(race.track.laps()));
            m.put("seconds", String.valueOf(race.lobbySeconds()));
            m.put("count", String.valueOf(race.racers.size()));
            m.put("max", String.valueOf(maxRacers));
            m.put("stars", "★".repeat(race.track.difficulty()) + "☆".repeat(5 - race.track.difficulty()));
            long lap = store.best(race.track.id(), true), rc = store.best(race.track.id(), false);
            m.put("record_lap", lap == 0 ? "-" : RaceProgress.format(lap / 1000.0));
            m.put("record_race", rc == 0 ? "-" : RaceProgress.format(rc / 1000.0));
            CircuitStore.Row mine = store.get(race.track.id(), v.player().getUniqueId());
            m.put("my_lap", mine == null || mine.bestLap() == 0 ? "-" : RaceProgress.format(mine.bestLap() / 1000.0));
            m.put("my_race", mine == null || mine.bestRace() == 0 ? "-" : RaceProgress.format(mine.bestRace() / 1000.0));
            m.put("kart", me == null ? "-" : me.kart.name());
            m.put("colour", me == null ? "-" : colourName(me.colour));
            m.put("ready", Lang.raw(me != null && me.ready ? "circuit.ready-on" : "circuit.ready-off"));
            m.put("host", race.host == null ? "-" : Names.mini(race.host));
            return m;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            for (Race.Racer r : race.racers.values()) {
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                head.editMeta(org.bukkit.inventory.meta.SkullMeta.class, m -> {
                    m.setOwningPlayer(Bukkit.getOfflinePlayer(r.id));
                    m.itemName(Text.item(Names.mini(r.id) + (r.id.equals(race.host) ? Lang.raw("circuit.host-mark") : "")));
                    m.lore(Text.itemLines(List.of(Lang.raw("circuit.racer-kart", "kart", r.kart.name(), "colour", colourName(r.colour)),
                            Lang.raw(r.ready ? "circuit.racer-ready" : "circuit.racer-waiting"))));
                });
                out.add(head);
            }
            return out;
        }

        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            Race.Racer me = race.racer(v.player().getUniqueId());
            int k = KART_KEYS.indexOf(b.key());
            if (k >= 0) {
                if (k >= karts.size()) return null;
                KartSpec kart = karts.get(k);
                CustomItem item = kartItem(kart);
                ItemStack it = item == null ? new ItemStack(Material.MINECART) : item.create(1);
                boolean on = me != null && me.kart.id().equals(kart.id());
                it.editMeta(m -> {
                    m.itemName(Text.item(Lang.raw("circuit.kart-name", "name", kart.name(), "role", kart.role())));
                    List<String> lore = new ArrayList<>();
                    lore.add(Lang.raw("circuit.kart-blurb", "blurb", kart.blurb()));
                    for (String bar : KartSpec.BARS) lore.add(Lang.raw("circuit.kart-bar", "name", Lang.raw("circuit.bar." + bar),
                            "bar", "■".repeat(kart.bars().get(bar)) + "<dark_gray>" + "■".repeat(5 - kart.bars().get(bar))));
                    lore.add("");
                    lore.add(Lang.raw(on ? "circuit.kart-chosen" : "circuit.kart-choose"));
                    m.lore(Text.itemLines(lore));
                    m.setEnchantmentGlintOverride(on);
                    m.setMaxStackSize(1);
                });
                return it;
            }
            int c = COLOUR_KEYS.indexOf(b.key());
            if (c >= 0) {
                if (me == null || c >= me.kart.colours().size()) return null;
                String colour = me.kart.colours().get(c);
                boolean on = colour.equals(me.colour);
                ItemStack it = swatch(colourRgb(colour));
                it.editMeta(m -> {
                    m.itemName(Text.item(Lang.raw("circuit.colour-name", "name", colourName(colour))));
                    m.lore(Text.itemLines(List.of(Lang.raw(colour.equals(me.kart.own()) ? "circuit.colour-own" : "circuit.colour-paint"), "",
                            Lang.raw(on ? "circuit.colour-chosen" : "circuit.colour-choose"))));
                    m.setEnchantmentGlintOverride(on);
                });
                return it;
            }
            if (b.action().equals("circuit:start") && !(race.racers.size() == 1 || v.player().getUniqueId().equals(race.host))) {
                base.editMeta(m -> m.lore(Text.itemLines(List.of(Lang.raw("circuit.start-host-only")))));
            }
            return base;
        }

        @Override
        @SuppressWarnings("UnstableApiUsage")
        public void onAction(MenuView v, String action, ClickType click) {
            Player p = v.player();
            Race.Racer me = race.racer(p.getUniqueId());
            if (me == null || !race.lobby()) {
                v.close();
                return;
            }
            if (action.startsWith("circuit:kart:")) {
                int k = Integer.parseInt(action.substring(13));
                if (k < karts.size()) {
                    KartSpec kart = karts.get(k);
                    // the same colour when the new kart comes in it, else its own
                    me.colour = kart.colours().contains(me.colour) ? me.colour : kart.own();
                    me.kart = kart;
                    remember(p, me);
                    p.playSound(p, Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.5f, 1.2f);
                }
            } else if (action.startsWith("circuit:colour:")) {
                int c = Integer.parseInt(action.substring(15));
                if (c < me.kart.colours().size()) {
                    me.colour = me.kart.colours().get(c);
                    remember(p, me);
                    p.playSound(p, Sound.ITEM_DYE_USE, SoundCategory.MASTER, 0.6f, 1.2f);
                }
            } else if (action.equals("circuit:ready")) {
                me.ready = !me.ready;
                p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.5f, me.ready ? 1.8f : 1.0f);
                race.readyChanged();
            } else if (action.equals("circuit:start")) {
                if (race.racers.size() == 1 || p.getUniqueId().equals(race.host)) {
                    race.startNow();
                    p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.6f, 2f);
                } else {
                    HudModule.flash(p, Lang.mm("circuit.start-host-only"), 2000);
                    return;
                }
            } else if (action.equals("circuit:leave")) {
                Bukkit.getScheduler().runTask(plugin, () -> leave(p));
                return;
            }
            refreshLobbyMenus(race);
        }

        @Override
        public void onClose(MenuView v) {
            lobbyViews.remove(v.player().getUniqueId(), v);
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private static ItemStack swatch(int rgb) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("stsv", "gui/swatch"));
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData()
                .addColor(Color.fromRGB(rgb)).addColor(Color.fromRGB(rgb)).build());
        it.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        return it;
    }

    // ------------------------------------------------------------------------------------------
    // Records, track list
    // ------------------------------------------------------------------------------------------

    public void showRecords(CommandSender s, String trackId) {
        List<TrackSpec> shown = new ArrayList<>();
        if (trackId != null) {
            TrackSpec t = tracks.containsKey(trackId) ? tracks.get(trackId) : byName(trackId);
            if (t == null) {
                s.sendMessage(Lang.msg("circuit.no-track", "track", trackId));
                return;
            }
            shown.add(t);
        } else shown.addAll(tracks.values());
        UUID me = s instanceof Player p ? p.getUniqueId() : null;
        for (TrackSpec t : shown) {
            s.sendMessage(Lang.mm("circuit.records-head", "track", t.name(), "stars", "★".repeat(t.difficulty())));
            List<CircuitStore.Row> race = store.top(t.id(), false, trackId != null ? 10 : 3);
            if (race.isEmpty()) s.sendMessage(Lang.mm("circuit.records-none"));
            int k = 0;
            for (CircuitStore.Row r : race) s.sendMessage(Lang.mm("circuit.records-line", "rank", ++k, "player", Names.mini(r.player()),
                    "time", RaceProgress.format(r.bestRace() / 1000.0), "kart", kart(r.raceKart()).name()));
            long lap = store.best(t.id(), true);
            if (lap > 0) {
                CircuitStore.Row best = store.top(t.id(), true, 1).getFirst();
                s.sendMessage(Lang.mm("circuit.records-lap", "player", Names.mini(best.player()), "time", RaceProgress.format(lap / 1000.0)));
            }
            if (me != null) {
                CircuitStore.Row mine = store.get(t.id(), me);
                if (mine != null) s.sendMessage(Lang.mm("circuit.records-mine", "race", mine.bestRace() == 0 ? "-" : RaceProgress.format(mine.bestRace() / 1000.0),
                        "lap", mine.bestLap() == 0 ? "-" : RaceProgress.format(mine.bestLap() / 1000.0), "rank", store.rank(t.id(), me, false),
                        "races", mine.races(), "wins", mine.wins()));
            }
        }
        if (trackId == null) s.sendMessage(Lang.mm("circuit.records-more"));
    }

    public void showTracks(CommandSender s) {
        s.sendMessage(Lang.mm("circuit.tracks-head"));
        for (TrackSpec t : tracks.values()) {
            Race r = races.get(t.id());
            String state = building.containsKey(t.id()) ? Lang.raw("circuit.state-building", "pct", building.get(t.id()))
                    : !ready(t.id()) ? Lang.raw("circuit.state-unbuilt")
                    : r == null ? Lang.raw("circuit.state-free") : r.lobby() ? Lang.raw("circuit.state-lobby", "count", r.racers.size())
                    : Lang.raw("circuit.state-racing", "count", r.active().size());
            s.sendMessage(Lang.mm("circuit.tracks-line", "track", t.name(), "id", t.id(), "stars", "★".repeat(t.difficulty()),
                    "laps", t.laps(), "length", Math.round(t.path().length()), "state", state));
        }
    }

    TrackSpec byName(String name) {
        for (TrackSpec t : tracks.values()) if (t.name().replace(" ", "").equals(name.replace(" ", "")) || t.id().equalsIgnoreCase(name)) return t;
        return null;
    }

    /** For the hub: {circuit_status} and per track {track_<id>_best}. */
    public Map<String, String> hubValues(Player p) {
        Map<String, String> m = new HashMap<>();
        int waiting = 0, racing = 0;
        for (Race r : races.values()) {
            if (r.lobby()) waiting += r.racers.size();
            else racing += r.active().size();
        }
        m.put("circuit_status", waiting + racing == 0 ? Lang.raw("circuit.hub-quiet")
                : Lang.raw("circuit.hub-busy", "waiting", waiting, "racing", racing));
        for (TrackSpec t : tracks.values()) {
            long best = store.best(t.id(), false);
            m.put("track_" + t.id() + "_best", best == 0 ? "-" : RaceProgress.format(best / 1000.0));
            CircuitStore.Row mine = store.get(t.id(), p.getUniqueId());
            m.put("track_" + t.id() + "_mine", mine == null || mine.bestRace() == 0 ? "-" : RaceProgress.format(mine.bestRace() / 1000.0));
            Race r = races.get(t.id());
            m.put("track_" + t.id() + "_state", r == null ? (ready(t.id()) ? Lang.raw("circuit.state-free") : Lang.raw("circuit.state-unbuilt"))
                    : r.lobby() ? Lang.raw("circuit.state-lobby", "count", r.racers.size()) : Lang.raw("circuit.state-racing", "count", r.active().size()));
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // Admin
    // ------------------------------------------------------------------------------------------

    public void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "목록" : a[0];
        World w = module.world();
        switch (sub) {
            case "목록" -> {
                showTracks(s);
                for (Race r : races.values()) {
                    StringBuilder names = new StringBuilder();
                    for (Race.Racer x : r.racers.values()) names.append(x.name).append(x.left ? "(나감) " : " ");
                    s.sendMessage(Lang.mm("circuit.admin-race", "track", r.track.name(), "state", r.state.name(), "racers", names.toString().trim()));
                }
                if (!problems.isEmpty()) for (String p : problems) s.sendMessage(Text.mm("<red>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(p)));
            }
            case "재건설" -> {
                if (w == null) {
                    s.sendMessage(Lang.msg("circuit.no-world"));
                    return;
                }
                List<String> ids = a.length < 2 || a[1].equals("전체") ? List.copyOf(tracks.keySet()) : List.of(a[1]);
                for (String id : ids) {
                    if (!tracks.containsKey(id)) {
                        s.sendMessage(Lang.msg("circuit.no-track", "track", id));
                        continue;
                    }
                    Race r = races.get(id);
                    if (r != null) r.close(false);
                    layouts.remove(id);
                    queueBuild(id, w);
                }
                s.sendMessage(Lang.msg("circuit.admin-rebuild", "count", ids.size()));
            }
            case "시작" -> {
                Race r = a.length > 1 ? races.get(a[1]) : races.values().stream().filter(Race::lobby).findFirst().orElse(null);
                if (r == null || !r.lobby()) {
                    s.sendMessage(Lang.msg("circuit.admin-no-lobby"));
                    return;
                }
                r.lobbyTicks = 1;
                s.sendMessage(Lang.msg("circuit.admin-started", "track", r.track.name()));
            }
            case "중지" -> {
                List<Race> stop = new ArrayList<>();
                if (a.length < 2 || a[1].equals("전체")) stop.addAll(races.values());
                else if (races.containsKey(a[1])) stop.add(races.get(a[1]));
                for (Race r : stop) r.close(false);
                s.sendMessage(Lang.msg("circuit.admin-stopped", "count", stop.size()));
            }
            case "기록초기화" -> {
                if (a.length < 2) {
                    s.sendMessage(Text.mm("<gray>/관리자 서킷 기록초기화 <트랙|전체> [플레이어] 확인"));
                    return;
                }
                String track = a[1].equals("전체") ? null : a[1];
                if (track != null && !tracks.containsKey(track)) {
                    s.sendMessage(Lang.msg("circuit.no-track", "track", track));
                    return;
                }
                UUID who = null;
                boolean confirm = a[a.length - 1].equals("확인");
                if (a.length >= 3 && !a[2].equals("확인")) {
                    var off = Bukkit.getOfflinePlayerIfCached(a[2]);
                    if (off == null) {
                        s.sendMessage(Text.mm("<red>모르는 플레이어: " + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(a[2])));
                        return;
                    }
                    who = off.getUniqueId();
                }
                if (!confirm) {
                    s.sendMessage(Text.mm("<yellow>정말 지우려면 끝에 '확인' 을 붙여 다시: /관리자 서킷 기록초기화 " + String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) + " 확인"));
                    return;
                }
                store.reset(track, who).thenAccept(n -> Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(Lang.msg("circuit.admin-reset", "count", n))));
            }
            case "이동" -> {
                if (!(s instanceof Player p) || a.length < 2 || !tracks.containsKey(a[1]) || w == null) {
                    s.sendMessage(Text.mm("<gray>/관리자 서킷 이동 <트랙>"));
                    return;
                }
                TrackLayout l = layout(a[1]);
                TrackLayout.Slot g = l.grid().getFirst();
                teleporting(p, true);
                p.teleport(new Location(w, g.x() + l.spec().originX(), TrackLayout.BASE_Y + g.y() + 6, g.z(), g.yaw(), 20));
                teleporting(p, false);
            }
            default -> s.sendMessage(Text.mm("<gray>/관리자 서킷 목록 | 재건설 [트랙|전체] | 시작 [트랙] | 중지 [트랙|전체] | 기록초기화 <트랙|전체> [플레이어] 확인 | 이동 <트랙>"));
        }
    }

    public List<String> adminComplete(String[] a) {
        if (a.length == 1) return List.of("목록", "재건설", "시작", "중지", "기록초기화", "이동");
        if (a.length == 2 && !a[0].equals("목록")) {
            List<String> out = new ArrayList<>(tracks.keySet());
            if (!a[0].equals("이동") && !a[0].equals("시작")) out.add("전체");
            return out;
        }
        return List.of();
    }

    public List<String> trackIds() {
        return List.copyOf(tracks.keySet());
    }

    public void report(CommandSender s) {
        int racers = 0;
        for (Race r : races.values()) racers += r.racers.size();
        s.sendMessage(Text.mm("<gray>서킷: 트랙 <white>" + tracks.size() + "</white> · 레이스 <white>" + races.size() + "</white> · 참가 <white>" + racers
                + "</white> · 짓는 중 <white>" + building.keySet() + "</white>"));
    }

    // ------------------------------------------------------------------------------------------

    public STSVPlugin plugin() {
        return plugin;
    }

    public VehicleModule vehicles() {
        return plugin.vehicles();
    }

    public CircuitStore store() {
        return store;
    }

    int lobbySeconds() {
        return lobbySeconds;
    }

    int maxRacers() {
        return maxRacers;
    }

    int finishGraceTicks() {
        return finishGrace * 20;
    }

    int maxRaceTicks() {
        return maxRaceMinutes * 60 * 20;
    }

    boolean bumping() {
        return bumping;
    }

    public Map<String, TrackSpec> tracks() {
        return tracks;
    }

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
