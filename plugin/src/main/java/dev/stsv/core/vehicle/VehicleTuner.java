package dev.stsv.core.vehicle;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.ModelRef;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.vehicle.VehicleTuning.Custom;
import dev.stsv.core.vehicle.VehicleTuning.Kind;
import dev.stsv.core.vehicle.VehicleTuning.Option;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The tuning shop (튜닝샵): what a vehicle looks like after it ({@link VehicleTuning}), and the shop itself.
 *
 * <p><b>The look.</b> Every vehicle shown carries its look ({@code LiveVehicle.custom}): what it was given and paid
 * for (the store, by its number), or what its owner is trying on. The rest of the vehicle code asks here for what
 * to draw: {@link #body}, {@link #wheel}, {@link #part}, {@link #headlamp}, {@link #skid}, {@link #pops},
 * {@link #flame}, {@link #smoke}, {@link #plateInk}; the glow under the car is this class's own part
 * ({@link #spawn}, {@link #teleport}, {@link #remove}). A look changes by drawing the vehicle's parts again.
 *
 * <p><b>The shop.</b> Places an administrator marks (/관리자 튜닝샵: a spot and a radius, kept in
 * data/tuning-shops.yml). Inside one, /튜닝 opens the shop for the vehicle the player drives, or their own nearest
 * one: the pack's own menu (contents/stsv/menus/tuning.yml), a tab for every kind of tuning (dimmed where this
 * vehicle cannot take it) over that kind's options as colour tiles. Clicking a tile puts it on the real car at
 * once, as a try-on; the screen stays, and when it is closed to look at the car a line in the chat says how to go on
 * (more, pay, undo). Paying takes the price of what is new ({@link VehicleTuning.Catalogue#price}) from the
 * player's money and writes the look down; anything else (undo, leaving the shop, the time running out, the
 * vehicle going away) puts the paid look back.
 */
final class VehicleTuner {

    /** How far a player may stand from the vehicle they tune (blocks). */
    static final double REACH = 8;
    /** A try-on ends by itself after this long (ticks). */
    static final int TRY_TICKS = 20 * 60 * 5;
    /** The glow lies this far over the ground, and reaches this far past the car's sides (blocks). */
    static final float GLOW_LIFT = 0.03f, GLOW_MARGIN = 0.9f;
    private static final String ADMIN_OWNER = "STSV 튜닝샵";

    /** A shop: everything within radius of a spot. */
    record Shop(String world, double x, double y, double z, double radius) {
        boolean holds(Location l) {
            if (!l.getWorld().getName().equals(world)) return false;
            double dx = l.getX() - x, dy = l.getY() - y, dz = l.getZ() - z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }
    }

    /** A player trying looks on a vehicle. */
    private static final class Trying {
        final long vehicle;
        long until;

        Trying(long vehicle, long until) {
            this.vehicle = vehicle;
            this.until = until;
        }
    }

    private final VehicleModule m;
    private STSVPlugin plugin;
    private VehicleTuningStore store;
    private VehicleTuning.Catalogue catalogue = VehicleTuning.Catalogue.EMPTY;
    /** every model there is (by name), to fall back to the stock part where a painted one was not drawn */
    private Set<String> models = Set.of();
    private final Map<Long, Custom> saved = new HashMap<>();
    private final Map<UUID, Trying> trying = new HashMap<>();
    private final List<Shop> shops = new ArrayList<>();
    /** shops that are buildings: a content plugin's (the economy server's), null without one */
    private TuningShops built;

    VehicleTuner(VehicleModule m) {
        this.m = m;
    }

    // ------------------------------------------------------------------------------------------
    // Starting, the files
    // ------------------------------------------------------------------------------------------

    void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        store = new VehicleTuningStore(plugin.db());
        store.init();
        saved.putAll(store.load());
        loadShops();
        plugin.admin().add(ADMIN_OWNER, "튜닝샵", "설치 <이름> | 목록 | 제거 <이름|번호> | 다시꾸미기 <이름> | 추가 [반경]",
                "튜닝샵 건물을 지금 자리에 짓기 (보는 쪽이 안쪽, 경제 플러그인이 지음) / 목록 / 없애기 / 건물만 다시 놓기 / 건물 없이 지금 자리 둘레를 튜닝샵으로", this::admin,
                (s, a) -> a.length == 1 ? List.of("설치", "목록", "제거", "다시꾸미기", "추가")
                        : a.length == 2 && a[0].equals("추가") ? List.of("8", "12", "20")
                        : a.length == 2 && (a[0].equals("제거") || a[0].equals("다시꾸미기")) && built != null ? built.names() : List.of());
    }

    void disable() {
        for (UUID id : List.copyOf(trying.keySet())) end(id, null);
        if (plugin != null) plugin.admin().removeAll(ADMIN_OWNER);
    }

    /** Reads every pack's vehicle_tuning.yml (a later pack's vehicles and options over an earlier one's). */
    void load() {
        Set<String> names = new HashSet<>();
        YamlConfiguration merged = new YamlConfiguration();
        for (ContentPack pack : plugin.content().packs()) {
            if (Files.isDirectory(pack.models())) {
                try (Stream<Path> s = Files.list(pack.models())) {
                    s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".bbmodel")).forEach(n -> names.add(n.substring(0, n.length() - 8)));
                } catch (IOException e) {
                    plugin.getLogger().warning("[튜닝] " + pack.id() + " 의 모델 목록을 읽지 못함: " + e.getMessage());
                }
            }
            Path f = pack.dir().resolve("vehicle_tuning.yml");
            if (!Files.isRegularFile(f)) continue;
            YamlConfiguration one = YamlConfiguration.loadConfiguration(f.toFile());
            for (String top : one.getKeys(false)) {
                if (!one.isConfigurationSection(top)) continue;
                for (String key : one.getConfigurationSection(top).getKeys(false)) merged.set(top + "." + key, one.get(top + "." + key));
            }
        }
        models = names;
        catalogue = VehicleTuning.parse(merged, new VehicleTuning.Content() {
            @Override
            public VehicleTuning.Facts vehicle(String item) {
                return facts(item);
            }

            @Override
            public boolean model(String name) {
                return names.contains(name);
            }

            @Override
            public boolean skid(String id) {
                return m.feel.skidStyle(id) != null;
            }
        }, problem -> plugin.getLogger().warning("[튜닝] vehicle_tuning.yml: " + problem));
    }

    private static String modelName(NamespacedKey key) {
        if (key == null) return null;
        String path = key.getKey();
        return path.startsWith("model/") ? path.substring(6) : path;
    }

    private static String bodyModel(CustomItem item) {
        return item.model() != null && item.model().kind() == ModelRef.Kind.BBMODEL ? item.model().value() : null;
    }

    private VehicleTuning.Facts facts(String itemId) {
        for (CustomItem item : plugin.content().items()) {
            if (!item.isVehicle() || !item.key().getKey().equals(itemId) || bodyModel(item) == null) continue;
            VehicleSpec spec = item.vehicle();
            VehicleFeel.Spec feel = m.feel.spec(item.key().asString(), spec.pulled() ? VehicleFeel.Power.MUSCLE : VehicleFeel.Power.ENGINE);
            SportFeel.Spec sport = feel.sport();
            return new VehicleTuning.Facts(bodyModel(item), modelName(spec.wheelModel()), modelName(feel.lamps().head()),
                    sport != null && sport.mark() != null, sport != null, !spec.extras().dress().plates().isEmpty());
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // The look of a vehicle
    // ------------------------------------------------------------------------------------------

    private static String id(LiveVehicle v) {
        return v.item.key().getKey();
    }

    /** A vehicle that appears (or whose item file was read again) takes the look it was given. */
    void adopt(LiveVehicle v) {
        boolean tried = false;
        for (Trying t : trying.values()) tried |= t.vehicle == v.id;
        if (!tried) v.custom = catalogue.valid(id(v), saved.getOrDefault(v.id, Custom.NONE));
        else v.custom = catalogue.valid(id(v), v.custom);
    }

    private Option option(LiveVehicle v, Kind kind) {
        return catalogue.option(kind, v.custom.get(kind));
    }

    private NamespacedKey variant(LiveVehicle v, String name, NamespacedKey stock) {
        return name != null && models.contains(name) ? new NamespacedKey(v.item.key().getNamespace(), "model/" + name) : stock;
    }

    /** What the body's display shows: the item itself, or its body in the chosen paint. */
    ItemStack body(LiveVehicle v) {
        String paint = v.custom.get(Kind.PAINT), body = bodyModel(v.item);
        NamespacedKey key = paint == null || body == null ? null : variant(v, VehicleTuning.painted(body, paint), null);
        return key == null ? v.item.create(1) : VehicleModule.model(key);
    }

    /** A moving part's model: in the chosen paint when that was drawn, else as it is. */
    NamespacedKey part(LiveVehicle v, NamespacedKey stock) {
        String paint = v.custom.get(Kind.PAINT), body = bodyModel(v.item), name = modelName(stock);
        return paint == null || body == null || name == null ? stock : variant(v, VehicleTuning.part(name, body, paint), stock);
    }

    NamespacedKey wheel(LiveVehicle v) {
        NamespacedKey stock = v.spec.wheelModel();
        String design = v.custom.get(Kind.WHEEL);
        return design == null || stock == null ? stock : variant(v, VehicleTuning.wheel(modelName(stock), design), stock);
    }

    NamespacedKey headlamp(LiveVehicle v, NamespacedKey stock) {
        String colour = v.custom.get(Kind.HEADLAMP);
        return colour == null || stock == null ? stock : variant(v, VehicleTuning.headlamp(modelName(stock), colour), stock);
    }

    SkidMarks.Style skid(LiveVehicle v, SkidMarks.Style stock) {
        String id = v.custom.get(Kind.SKID);
        SkidMarks.Style style = id == null || stock == null ? null : m.feel.skidStyle(id);
        return style == null ? stock : style;
    }

    SportFeel.Pops pops(LiveVehicle v, SportFeel.Pops stock) {
        Option o = option(v, Kind.CRACKLE);
        return o == null || o.pops() < 1 ? stock : new SportFeel.Pops(o.pops(), o.gapMin(), o.gapMax());
    }

    TextColor plateInk(LiveVehicle v) {
        Option o = option(v, Kind.PLATE);
        return o == null || o.color() < 0 ? VehicleDress.PLATE_INK : TextColor.color(o.color());
    }

    /** A flame out of an exhaust tip at (x, y, z), shot along (vx, vy, vz). */
    void flame(LiveVehicle v, double x, double y, double z, double vx, double vy, double vz) {
        Option o = option(v, Kind.FLAME);
        String kind = o == null ? "flame" : o.particle();
        if (kind.equals("dust")) {
            // a coloured puff: dust has no velocity of its own, so a few along the way the flame would go
            Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(o.color()), 1.1f);
            for (int i = 0; i < 3; i++) v.world.spawnParticle(Particle.DUST, x + vx * i * 1.6, y + vy * i * 1.6, z + vz * i * 1.6, 1, 0.02, 0.02, 0.02, 0, dust);
            return;
        }
        Particle p = kind.equals("soul") ? Particle.SOUL_FIRE_FLAME : kind.equals("copper") ? Particle.COPPER_FIRE_FLAME : Particle.FLAME;
        v.world.spawnParticle(p, x, y, z, 0, vx, vy, vz, 0.12);
    }

    /** Smoke off a tyre at (x, y, z): the game's white cloud, or puffs of the chosen colour. */
    void smoke(LiveVehicle v, double x, double y, double z, double spread, double speed) {
        Option o = option(v, Kind.SMOKE);
        if (o == null || o.color() < 0) {
            v.world.spawnParticle(Particle.CLOUD, x, y, z, 2, spread, 0.05, spread, speed);
            return;
        }
        v.world.spawnParticle(Particle.DUST, x, y + 0.1, z, 3, spread, 0.1, spread, 0, new Particle.DustOptions(Color.fromRGB(o.color()), 1.7f));
    }

    private static Transformation glow(LiveVehicle v) {
        return new Transformation(new Vector3f(0, GLOW_LIFT, 0), new Quaternionf(),
                new Vector3f((float) v.spec.width() + GLOW_MARGIN, 1, (float) v.spec.length() + GLOW_MARGIN), new Quaternionf());
    }

    /** The parts of the look that are this class's own: the glow under the car (always fully lit). */
    void spawn(LiveVehicle v) {
        String colour = v.custom.get(Kind.UNDERGLOW);
        NamespacedKey key = colour == null ? null : variant(v, VehicleTuning.underglow(colour), null);
        if (key == null) return;
        v.underglow = m.display(v, VehicleModule.model(key), glow(v));
        v.underglow.setBrightness(new Display.Brightness(15, 15));
    }

    void remove(LiveVehicle v) {
        if (v.underglow != null) v.underglow.remove();
        v.underglow = null;
    }

    void teleport(LiveVehicle v, Location at) {
        if (v.underglow != null) v.underglow.teleport(at);
    }

    // ------------------------------------------------------------------------------------------
    // Shops (/관리자 튜닝샵)
    // ------------------------------------------------------------------------------------------

    private File shopFile() {
        return new File(plugin.getDataFolder(), "data/tuning-shops.yml");
    }

    private void loadShops() {
        shops.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(shopFile());
        for (Map<?, ?> s : y.getMapList("shops")) {
            try {
                shops.add(new Shop(String.valueOf(s.get("world")), ((Number) s.get("x")).doubleValue(), ((Number) s.get("y")).doubleValue(),
                        ((Number) s.get("z")).doubleValue(), ((Number) s.get("radius")).doubleValue()));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("[튜닝] data/tuning-shops.yml 의 한 줄을 읽지 못함: " + s);
            }
        }
    }

    private void saveShops() {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Shop s : shops) {
            Map<String, Object> one = new java.util.LinkedHashMap<>();
            one.put("world", s.world());
            one.put("x", s.x());
            one.put("y", s.y());
            one.put("z", s.z());
            one.put("radius", s.radius());
            out.add(one);
        }
        y.set("shops", out);
        try {
            shopFile().getParentFile().mkdirs();
            y.save(shopFile());
        } catch (IOException e) {
            plugin.getLogger().warning("[튜닝] data/tuning-shops.yml 을 쓰지 못함: " + e.getMessage());
        }
    }

    boolean inShop(Location l) {
        for (Shop s : shops) if (s.holds(l)) return true;
        return built != null && built.inside(l);
    }

    /** A content plugin that builds tuning shops (null: none any more). */
    void built(TuningShops shops) {
        this.built = shops;
    }

    private boolean anyShop() {
        return !shops.isEmpty() || (built != null && !built.names().isEmpty());
    }

    private void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "목록" : a[0];
        switch (sub) {
            case "설치", "install", "다시꾸미기" -> {
                if (built == null) {
                    s.sendMessage(Lang.msg("tuning.shop-no-builder"));
                } else if (a.length < 2) {
                    s.sendMessage(Lang.msg("tuning.shop-usage"));
                } else if (sub.equals("다시꾸미기")) {
                    if (!built.redecorate(s, a[1])) s.sendMessage(Lang.msg("tuning.shop-unknown", "name", a[1]));
                } else {
                    built.install(s, a[1]);
                }
            }
            case "추가", "add" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(Lang.msg("common.players-only"));
                    return;
                }
                double radius = 12;
                if (a.length > 1) {
                    try {
                        radius = Math.clamp(Double.parseDouble(a[1]), 2, 64);
                    } catch (NumberFormatException e) {
                        s.sendMessage(Lang.msg("tuning.shop-usage"));
                        return;
                    }
                }
                Location l = p.getLocation();
                shops.add(new Shop(l.getWorld().getName(), Math.floor(l.getX()) + 0.5, Math.floor(l.getY()), Math.floor(l.getZ()) + 0.5, radius));
                saveShops();
                s.sendMessage(Lang.msg("tuning.shop-added", "number", shops.size(), "radius", (int) radius));
            }
            case "제거", "remove" -> {
                // a building by its name, a plain place by its number
                if (a.length > 1 && built != null && built.remove(s, a[1])) return;
                int n;
                try {
                    n = a.length > 1 ? Integer.parseInt(a[1]) : 0;
                } catch (NumberFormatException e) {
                    n = 0;
                }
                if (n < 1 || n > shops.size()) {
                    s.sendMessage(Lang.msg(a.length > 1 && n == 0 ? "tuning.shop-unknown" : "tuning.shop-usage", "name", a.length > 1 ? a[1] : ""));
                    return;
                }
                shops.remove(n - 1);
                saveShops();
                s.sendMessage(Lang.msg("tuning.shop-removed", "number", n));
            }
            default -> {
                if (built != null) built.list(s);
                if (!anyShop()) s.sendMessage(Lang.msg("tuning.shop-none"));
                for (int i = 0; i < shops.size(); i++) {
                    Shop shop = shops.get(i);
                    s.sendMessage(Lang.msg("tuning.shop-entry", "number", i + 1, "world", shop.world(), "x", (int) Math.floor(shop.x()),
                            "y", (int) shop.y(), "z", (int) Math.floor(shop.z()), "radius", (int) shop.radius()));
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // /튜닝
    // ------------------------------------------------------------------------------------------

    /** The vehicle a player tunes: the one they drive, else their own nearest within reach. */
    private LiveVehicle vehicleOf(Player p) {
        LiveVehicle best = null;
        double bestD = REACH * REACH;
        Location l = p.getLocation();
        for (LiveVehicle v : m.shown()) {
            if (!v.world.equals(p.getWorld()) || !p.getUniqueId().equals(v.owner)) continue;
            if (v.seatOf(p) == 0) return v;
            double dx = v.st.x - l.getX(), dy = v.st.y - l.getY(), dz = v.st.z - l.getZ(), d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) {
                best = v;
                bestD = d;
            }
        }
        return best;
    }

    private static String price(long amount) {
        return amount <= 0 ? Lang.raw("tuning.free") : EconomyModule.format(amount);
    }

    private static String kindName(Kind kind) {
        return Lang.raw("tuning.kind." + kind.word());
    }

    private String optionName(Kind kind, String id) {
        Option o = catalogue.option(kind, id);
        return o == null ? Lang.raw("tuning.stock") : o.name();
    }

    /** The vehicle to tune, or null after telling the player why not. */
    private LiveVehicle ready(Player p) {
        LiveVehicle v = vehicleOf(p);
        if (v == null) {
            p.sendMessage(Lang.msg("tuning.no-vehicle", "reach", (int) REACH));
            return null;
        }
        if (catalogue.kinds(id(v)).isEmpty()) {
            p.sendMessage(Lang.msg("tuning.nothing", "vehicle", VehicleModule.label(v.item)));
            return null;
        }
        if (!p.hasPermission("stsv.admin") && !inShop(new Location(v.world, v.st.x, v.st.y, v.st.z))) {
            p.sendMessage(Lang.msg(anyShop() ? "tuning.no-shop" : "tuning.no-shops"));
            return null;
        }
        for (Map.Entry<UUID, Trying> e : trying.entrySet()) {
            if (e.getValue().vehicle == v.id && !e.getKey().equals(p.getUniqueId())) {
                p.sendMessage(Lang.msg("tuning.busy"));
                return null;
            }
        }
        return v;
    }

    void command(Player p, String[] a) {
        String sub = a.length == 0 ? "" : a[0];
        switch (sub) {
            case "결제", "pay" -> pay(p);
            case "되돌리기", "undo" -> {
                if (trying.containsKey(p.getUniqueId())) end(p.getUniqueId(), "tuning.reverted");
                else p.sendMessage(Lang.msg("tuning.nothing-tried"));
            }
            case "종류", "kind" -> {
                LiveVehicle v = ready(p);
                Kind kind = a.length > 1 ? Kind.of(a[1]) : null;
                if (v != null) openMenu(p, v, kind);
            }
            case "고르기", "pick" -> {
                LiveVehicle v = ready(p);
                Kind kind = a.length > 1 ? Kind.of(a[1]) : null;
                if (v == null) return;
                String id = a.length > 2 && !a[2].equals("순정") && !a[2].equals("stock") ? a[2] : null;
                if (kind == null || (id != null && !catalogue.offers(id(v), kind, id))) {
                    p.sendMessage(Lang.msg("tuning.unknown"));
                    return;
                }
                tryOn(p, v, kind, id, true);
            }
            default -> {
                LiveVehicle v = ready(p);
                if (v != null) openMenu(p, v, null);
            }
        }
    }

    private Custom paid(LiveVehicle v) {
        return catalogue.valid(id(v), saved.getOrDefault(v.id, Custom.NONE));
    }

    /** chat: say in the chat how to go on (typed commands; the screen shows it itself and says it when it closes) */
    private void tryOn(Player p, LiveVehicle v, Kind kind, String id, boolean chat) {
        Trying t = trying.get(p.getUniqueId());
        if (t != null && t.vehicle != v.id) end(p.getUniqueId(), null);     // another car of theirs: the first one's try-on is over
        trying.put(p.getUniqueId(), new Trying(v.id, m.ticks() + TRY_TICKS));
        v.custom = v.custom.with(kind, id);
        m.redraw(v);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.25f, 1.6f);
        long due = catalogue.price(paid(v), v.custom);
        HudModule.flash(p, Lang.mm("tuning.tried", "kind", kindName(kind), "option", optionName(kind, id)));
        if (chat) p.sendMessage(Lang.msg("tuning.actions", "price", price(due)));
    }

    private void pay(Player p) {
        Trying t = trying.get(p.getUniqueId());
        LiveVehicle v = t == null ? null : m.shownById(t.vehicle);
        if (v == null) {
            p.sendMessage(Lang.msg("tuning.nothing-tried"));
            return;
        }
        Custom from = paid(v), to = catalogue.valid(id(v), v.custom);
        long due = catalogue.price(from, to);
        EconomyModule economy = plugin.economy();
        if (due > 0 && economy != null && !economy.withdraw(p.getUniqueId(), due, "튜닝 #" + v.id)) {
            p.sendMessage(Lang.msg("tuning.poor", "price", price(due), "balance", EconomyModule.format(economy.balance(p.getUniqueId()))));
            return;
        }
        if (to.chosen().isEmpty()) saved.remove(v.id);
        else saved.put(v.id, to);
        store.save(v.id, to, System.currentTimeMillis()).exceptionally(err -> {
            plugin.getLogger().warning("[튜닝] 탈것 #" + v.id + " 의 튜닝을 저장하지 못함 (" + to.encode() + "): " + err.getMessage());
            return null;
        });
        trying.remove(p.getUniqueId());
        v.custom = to;
        p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 0.7f, 1.1f);
        p.sendMessage(Lang.msg("tuning.paid", "price", price(due), "vehicle", VehicleModule.label(v.item)));
    }

    /** Ends a player's try-on: the vehicle shows what was paid for again. key: what they are told (null: nothing). */
    private void end(UUID player, String key) {
        Trying t = trying.remove(player);
        if (t == null) return;
        LiveVehicle v = m.shownById(t.vehicle);
        if (v != null) {
            Custom back = paid(v);
            if (!back.equals(v.custom)) {
                v.custom = back;
                m.redraw(v);
            }
        }
        Player p = Bukkit.getPlayer(player);
        if (key != null && p != null) p.sendMessage(Lang.msg(key));
    }

    /** Now and then: a try-on ends when its player is gone or far, its vehicle is gone or out of the shop, or its time is up. */
    void tick(long ticks) {
        if (trying.isEmpty() || ticks % 20 != 0) return;
        for (Map.Entry<UUID, Trying> e : List.copyOf(trying.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            LiveVehicle v = m.shownById(e.getValue().vehicle);
            boolean gone = p == null || v == null || !p.getWorld().equals(v.world)
                    || p.getLocation().distanceSquared(new Location(v.world, v.st.x, v.st.y, v.st.z)) > 4 * REACH * REACH
                    || (!p.hasPermission("stsv.admin") && !inShop(new Location(v.world, v.st.x, v.st.y, v.st.z)));
            if (gone) end(e.getKey(), "tuning.left");
            else if (ticks >= e.getValue().until) end(e.getKey(), "tuning.expired");
        }
    }

    /** A vehicle is going away (picked up, removed, its chunk unloaded): whoever tries looks on it stops. */
    void gone(LiveVehicle v) {
        for (Map.Entry<UUID, Trying> e : List.copyOf(trying.entrySet())) {
            if (e.getValue().vehicle != v.id) continue;
            trying.remove(e.getKey());
            v.custom = paid(v);
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.sendMessage(Lang.msg("tuning.left"));
        }
    }

    void quit(Player p) {
        end(p.getUniqueId(), null);
    }

    // ------------------------------------------------------------------------------------------
    // The screen (contents/stsv/menus/tuning.yml: the pack's own menu)
    // ------------------------------------------------------------------------------------------

    private static final String MENU = "stsv:tuning";
    private static final Key SWATCH = Key.key("stsv", "gui/swatch");

    /** The shop's screen for a vehicle, on a kind's tab (null: the first kind the vehicle takes). */
    private void openMenu(Player p, LiveVehicle v, Kind kind) {
        List<Kind> kinds = catalogue.kinds(id(v));
        Kind tab = kind != null && kinds.contains(kind) ? kind : kinds.getFirst();
        plugin.menus().open(p, MENU, new Screen(v.id, tab));
    }

    /** An option's tile: a swatch of its colour (grey where it has none), its name, its price, and where it stands. */
    @SuppressWarnings("UnstableApiUsage")
    private static ItemStack tile(int color, net.kyori.adventure.text.Component name, List<net.kyori.adventure.text.Component> lore, boolean on) {
        ItemStack it = ItemStack.of(org.bukkit.Material.PAPER);
        Color c = Color.fromRGB(color < 0 ? 0x8a8f98 : color);
        it.setData(DataComponentTypes.ITEM_MODEL, SWATCH);
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addColor(c).addColor(c).build());
        it.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        it.editMeta(m -> {
            m.itemName(name);
            m.lore(lore);
            if (on) m.setEnchantmentGlintOverride(true);
        });
        return it;
    }

    /** One player's open screen: which vehicle, which kind's tab. */
    private final class Screen implements MenuHandler {
        private final long vehicle;
        private final Kind tab;
        /** the option ids of the list, in order (null: the stock look) */
        private List<String> shown = List.of();

        Screen(long vehicle, Kind tab) {
            this.vehicle = vehicle;
            this.tab = tab;
        }

        private LiveVehicle car() {
            return m.shownById(vehicle);
        }

        @Override
        public Map<String, String> placeholders(MenuView view) {
            Map<String, String> out = new HashMap<>();
            LiveVehicle v = car();
            Custom look = v == null ? Custom.NONE : v.custom, paid = v == null ? Custom.NONE : paid(v);
            out.put("vehicle", v == null ? "" : VehicleModule.label(v.item));
            out.put("kind", kindName(tab));
            out.put("due", price(catalogue.price(paid, look)));
            int tried = 0;
            for (Kind k : Kind.values()) {
                out.put("on_" + k.word(), optionName(k, look.get(k)));
                if (!java.util.Objects.equals(look.get(k), paid.get(k))) tried++;
            }
            out.put("tried", String.valueOf(tried));
            EconomyModule economy = plugin.economy();
            out.put("balance", economy == null ? "-" : EconomyModule.format(economy.balance(view.player().getUniqueId())));
            return out;
        }

        @Override
        public List<ItemStack> list(MenuView view) {
            LiveVehicle v = car();
            if (v == null) return List.of();
            String on = v.custom.get(tab), owned = paid(v).get(tab);
            List<ItemStack> items = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            ids.add(null);
            items.add(tile(0xe8e4da, Lang.item("tuning.tile-stock"), Lang.items(on == null ? "tuning.tile-lore-on" : "tuning.tile-lore-stock"), on == null));
            for (Option o : catalogue.offered(id(v), tab)) {
                String key = o.id().equals(on) ? "tuning.tile-lore-on" : o.id().equals(owned) ? "tuning.tile-lore-owned" : "tuning.tile-lore";
                ids.add(o.id());
                items.add(tile(o.color(), Lang.item("tuning.tile-name", "name", o.name()), Lang.items(key, "price", price(o.price())), o.id().equals(on)));
            }
            shown = ids;
            return items;
        }

        @Override
        public void onListClick(MenuView view, int index, ClickType click) {
            Player p = view.player();
            LiveVehicle v = stillThere(view);
            if (v == null || index < 0 || index >= shown.size()) return;
            String id = shown.get(index);
            if (java.util.Objects.equals(id, v.custom.get(tab))) return;
            tryOn(p, v, tab, id, false);
            view.refresh();
        }

        /** The vehicle, if this screen may still act on it; else the screen closes (the player was told why). */
        private LiveVehicle stillThere(MenuView view) {
            LiveVehicle v = ready(view.player());
            if (v == null || v.id != vehicle) {
                view.close();
                return null;
            }
            return v;
        }

        @Override
        public void onAction(MenuView view, String action, ClickType click) {
            Player p = view.player();
            if (action.startsWith("tuning:kind:")) {
                Kind kind = Kind.of(action.substring("tuning:kind:".length()));
                LiveVehicle v = stillThere(view);
                if (v == null || kind == null || kind == tab) return;
                if (!catalogue.kinds(id(v)).contains(kind)) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
                    return;
                }
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
                openMenu(p, v, kind);      // a new screen: its title names the tab
            } else if (action.equals("tuning:pay")) {
                if (stillThere(view) == null) return;
                if (!trying.containsKey(p.getUniqueId())) return;
                pay(p);
                if (p.getOpenInventory().getTopInventory().getHolder() == view) view.refresh();
            } else if (action.equals("tuning:undo")) {
                if (!trying.containsKey(p.getUniqueId())) return;
                end(p.getUniqueId(), "tuning.reverted");
                view.refresh();
            }
        }

        @Override
        public ItemStack button(MenuView view, MenuSpec.Button button, ItemStack base) {
            if (base == null) return null;
            String action = button.action();
            LiveVehicle v = car();
            if (action.startsWith("tuning:kind:")) {
                Kind kind = Kind.of(action.substring("tuning:kind:".length()));
                if (kind == null) return base;
                if (v == null || !catalogue.kinds(id(v)).contains(kind)) {
                    // a kind this vehicle cannot take: its icon dimmed, and why
                    base.editMeta(meta -> {
                        meta.setItemModel(new NamespacedKey("stsv", "gui/tune_" + kind.word() + "_off"));
                        meta.itemName(Lang.item("tuning.tab-off", "kind", kindName(kind)));
                        meta.lore(Lang.items("tuning.tab-off-lore"));
                    });
                } else if (kind == tab) {
                    base.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
                }
                return base;
            }
            boolean tried = v != null && !paid(v).equals(v.custom);
            if (action.equals("tuning:pay") && !tried) {
                base.editMeta(meta -> {
                    meta.setItemModel(new NamespacedKey("stsv", "gui/coins_off"));
                    meta.itemName(Lang.item("tuning.pay-off"));
                    meta.lore(Lang.items("tuning.pay-off-lore"));
                });
            } else if (action.equals("tuning:undo") && !tried) {
                base.editMeta(meta -> {
                    meta.setItemModel(new NamespacedKey("stsv", "gui/clear_off"));
                    meta.itemName(Lang.item("tuning.undo-off"));
                    meta.lore(List.of());
                });
            }
            return base;
        }

        @Override
        public void onClose(MenuView view) {
            // closed to look at the car with something tried on: how to go on, once, a tick later (not when only the tab changed)
            Player p = view.player();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline() || !trying.containsKey(p.getUniqueId())) return;
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof MenuView) return;
                LiveVehicle v = car();
                if (v != null) p.sendMessage(Lang.msg("tuning.actions", "price", price(catalogue.price(paid(v), v.custom))));
            });
        }
    }

    /** For a report: how many vehicles are tuned, how many shops there are. */
    int[] counts() {
        return new int[]{saved.size(), shops.size()};
    }

    Optional<Custom> savedLook(long vehicle) {
        return Optional.ofNullable(saved.get(vehicle));
    }
}
