package dev.stsv.economy.secret.garage;

import dev.stsv.core.util.Text;
import dev.stsv.core.vehicle.VehicleModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 폐차고의 주인 (docs/admin/숨은_비밀/폐차고의_주인.md; contents/economy/secrets/garage.yml). A street racer died one night on
 * the sharpest bend of a hard 국도, driving home to his garage at the end of that road. His car was towed back and has
 * stood under a tarp behind a shut shutter since. Put back exactly as it was, he comes for one last drive — and needs
 * someone to drive it home beside him.
 * <p>
 * Players here own cargo vehicles only, so nobody tunes a car of their own: it is <b>his</b> car that is restored, in his
 * garage, and then driven. Its true look (paint, wheels, underglow: {@link Combo#answer}) is one per server.
 * <ul>
 *   <li>0: by the bend's outer roadside a small memorial (flowers, a burnt-down candle, a framed photo) for everyone who
 *       comes near. Taking the photo starts it (1): the photo's lore tells the wheels.</li>
 *   <li>1: clues at the bend — holding the photo, a paint chip on the guardrail (its lore tells the paint, fairly but
 *       not exactly); at night the asphalt still glows where the car slid (the underglow's colour). At the road's end
 *       his shut garage; coming to it with the photo, the shutter rattles up (2).</li>
 *   <li>2: the workbench ({@link Bench}): choose paint, wheels and glow (the car inside shows them), let the shutter
 *       down, and he says only how many of the three are his. All three: 3.</li>
 *   <li>3: at night, at the bend, his car waits with its lights on. Getting in is 4: the last drive ({@link Drive}).</li>
 *   <li>Home together: solved. A real 튜닝카 bound to the solver, the photo now shows two cars, a plaque at the memorial
 *       for good, the garage shut with its sign lit.</li>
 * </ul>
 * Saved (eco.kv "secret.garage.&lt;uuid&gt;."): stage, since, photo (the serial of the photo that works), chip, glow (clues
 * found), look (what the car in the garage shows), try (the last look checked), tries (the notebook:
 * {@link Combo#notebook}), check-at, retry-at (waits, ms), arrived (the drive ended at the garage), ground (where to
 * stand them on their next join), late. Server: "secret.garage.place" ({@link Places}), "secret.garage.plaque" (the
 * solver's name). Everything shown is per viewer and in memory ({@link Props}).
 */
public final class Garage extends Secret {

    static final NamespacedKey PHOTO_OWNER = new NamespacedKey("economy", "garage_photo_owner"),
            PHOTO_SERIAL = new NamespacedKey("economy", "garage_photo_serial"), PHOTO_DONE = new NamespacedKey("economy", "garage_photo_done"),
            CHIP_OWNER = new NamespacedKey("economy", "garage_chip_owner");
    static final String PHOTO = "economy:racer_photo", CHIP = "economy:paint_chip", PLAQUE = "secret.garage.plaque";
    private static final String MEMORIAL = "memorial", CHIP_GROUP = "chip";
    private static final int CLUES = 1, RESTORE = 2, NIGHT = 3, DRIVE = 4;

    private final Props props;
    private final CarLook cars;
    private final Shed shed;
    private final Places places;
    private final Drive drive;
    /** viewer -> how their memorial was built (rebuilt when that changes) */
    private final Map<UUID, String> memorials = new HashMap<>();
    /** viewer -> the shutter held at this height (a check, the end of the drive), whatever their stage says */
    private final Map<UUID, Double> held = new HashMap<>();
    /** viewer -> until when an admin's 추모 / 차고 stays */
    private final Map<UUID, Long> demoMemorial = new HashMap<>(), demoShed = new HashMap<>();
    private final Map<String, Long> cooled = new HashMap<>();
    private final Map<UUID, Integer> epoch = new HashMap<>();
    private final Set<UUID> checking = new HashSet<>();
    private Combo answer;
    private long beats;

    public Garage(SecretModule m) {
        super(m);
        props = new Props(eco);
        cars = new CarLook(m, props);
        shed = new Shed(props, cars);
        places = new Places(eco);
        drive = new Drive(this);
    }

    @Override
    public String id() {
        return "garage";
    }

    @Override
    public String name() {
        return "폐차고";
    }

    @Override
    public String stoneTitle() {
        return "폐차고의 주인";
    }

    /** 1: the photo (clues), 2: restoring, 3: restored (a night at the bend), 4: the last drive. */
    @Override
    public int stages() {
        return DRIVE;
    }

    @Override
    protected void loaded() {
        answer = null;
        cars.settings(cfg);
        shed.settings(cfg);
        places.settings(cfg);
    }

    // ------------------------------------------------------------------------------------------
    // what the other classes of this secret ask
    // ------------------------------------------------------------------------------------------

    YamlConfiguration cfg() {
        return cfg;
    }

    EcoPlugin eco() {
        return eco;
    }

    Props props() {
        return props;
    }

    CarLook cars() {
        return cars;
    }

    Places places() {
        return places;
    }

    /** The core's vehicle module (null when it is off: no drive then). */
    VehicleModule vm() {
        return m.core().vehicles();
    }

    private int stage(UUID u) {
        return m.stage(this, u);
    }

    private boolean guide(Player p) {
        return eco.guide() != null && eco.guide().inGuide(p);
    }

    /** Whether this player may still go on with it: on, not solved by someone else, not in the 길라잡이. */
    boolean mayGoOn(Player p) {
        return open(p.getUniqueId()) && !guide(p);
    }

    /** Night in their world (always in test mode). */
    boolean night(Player p) {
        if (m.test(p.getUniqueId())) return true;
        long t = p.getWorld().getTime();
        return t >= 13000 && t < 23000;
    }

    private String line(String key, String def) {
        return cfg.getString("lines." + key, def);
    }

    void say(Player p, String key, String def) {
        String s = line(key, def);
        if (!s.isEmpty()) p.sendMessage(Text.mm(s));
    }

    void whisper(Player p, String key, String def) {
        String s = line(key, def);
        if (!s.isEmpty()) m.haunt().whisper(p, s);
    }

    /** True once every {@code seconds} for this player and thing. */
    private boolean cool(UUID u, String what, long seconds) {
        long now = System.currentTimeMillis();
        Long until = cooled.get(u + what);
        if (until != null && now < until) return false;
        cooled.put(u + what, now + seconds * 1000L);
        return true;
    }

    /** True once every {@code seconds} for this player and thing (for the drive's own lines). */
    boolean once(UUID u, String what, long seconds) {
        return cool(u, what, seconds);
    }

    /** Later, if they are still here and nothing took their scenes away meanwhile ({@link #cleanup}). */
    void later(Player p, long ticks, Runnable r) {
        int e = epoch.getOrDefault(p.getUniqueId(), 0);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline() && epoch.getOrDefault(p.getUniqueId(), 0) == e) r.run();
        }, Math.max(1, ticks));
    }

    private static double flat(Player p, Location at) {
        if (at == null || at.getWorld() == null || !at.getWorld().equals(p.getWorld())) return Double.MAX_VALUE;
        return Math.hypot(at.getX() - p.getLocation().getX(), at.getZ() - p.getLocation().getZ());
    }

    private static boolean loaded(Location at) {
        return at != null && at.getWorld() != null && at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4);
    }

    /** Whether all the ground under a frame's box (x0..x1 to its left, z0..z1 the way it faces) is loaded: nothing is ever put into a chunk that is not. */
    private static boolean loaded(Location frame, double x0, double x1, double z0, double z1) {
        return loaded(frame) && loaded(Props.local(frame, x0, 0, z0)) && loaded(Props.local(frame, x1, 0, z0))
                && loaded(Props.local(frame, x0, 0, z1)) && loaded(Props.local(frame, x1, 0, z1));
    }

    // ------------------------------------------------------------------------------------------
    // the car's true look
    // ------------------------------------------------------------------------------------------

    private List<String> ids(String section) {
        return new ArrayList<>(Bench.options(cfg, section).keySet());
    }

    /**
     * The look his car had on this server: garage.yml {@code answer} when it names three listed options, else worked out
     * from the main world's id. Null when an option list is empty (the secret cannot be played then).
     */
    Combo answer() {
        if (answer != null) return answer;
        List<String> paints = ids("paints"), wheels = ids("wheels"), glows = ids("underglows");
        Combo fixed = new Combo(cfg.getString("answer.paint"), cfg.getString("answer.wheel"), cfg.getString("answer.underglow"));
        if (fixed.whole() && paints.contains(fixed.paint()) && wheels.contains(fixed.wheel()) && glows.contains(fixed.glow())) answer = fixed;
        else if (!Bukkit.getWorlds().isEmpty()) answer = Combo.answer(Bukkit.getWorlds().getFirst().getUID(), paints, wheels, glows);
        return answer;
    }

    private Bench.Option option(String section, String id) {
        return id == null ? null : Bench.options(cfg, section).get(id);
    }

    // ------------------------------------------------------------------------------------------
    // the photo and the paint chip
    // ------------------------------------------------------------------------------------------

    private boolean ownPhoto(ItemStack it, UUID u) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return false;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        if (!u.toString().equals(pdc.get(PHOTO_OWNER, PersistentDataType.STRING)) || pdc.has(PHOTO_DONE)) return false;
        String want = m.get(this, u, "photo");
        return want != null && want.equals(pdc.get(PHOTO_SERIAL, PersistentDataType.STRING));
    }

    /** Whether they carry their photo (the one given last: an older copy left in a chest is only a picture). */
    private boolean hasPhoto(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (ownPhoto(it, p.getUniqueId())) return true;
        return ownPhoto(p.getItemOnCursor(), p.getUniqueId());
    }

    private boolean holdingPhoto(Player p) {
        return ownPhoto(p.getInventory().getItemInMainHand(), p.getUniqueId()) || ownPhoto(p.getInventory().getItemInOffHand(), p.getUniqueId());
    }

    private static String date(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy.MM.dd");
        f.setTimeZone(TimeZone.getTimeZone(SecretModule.KST));
        return f.format(new Date(ms));
    }

    private List<Component> lore(String key, Map<String, String> fill, String... def) {
        List<Component> out = new ArrayList<>();
        List<String> lines = cfg.getStringList(key);
        for (String raw : lines.isEmpty() ? List.of(def) : lines) {
            String s = raw;
            for (Map.Entry<String, String> e : fill.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
            out.add(Text.item(s));
        }
        return out;
    }

    private Map<String, String> words(Player p) {
        Combo a = answer();
        Bench.Option wheel = a == null ? null : option("wheels", a.wheel()), paint = a == null ? null : option("paints", a.paint());
        Map<String, String> v = new HashMap<>();
        v.put("wheel", wheel == null ? "" : wheel.clue());
        v.put("paint", paint == null ? "" : paint.clue());
        v.put("racer", cfg.getString("racer", "한도윤"));
        v.put("player", p.getName());
        v.put("date", date(System.currentTimeMillis()));
        return v;
    }

    /** The photo as it lies at the memorial (shown there, and what a new one is made from). */
    private ItemStack photoItem() {
        ItemStack it = Items.create(PHOTO, 1).orElseGet(() -> new ItemStack(Material.PAPER));
        it.editMeta(meta -> meta.itemName(Text.item(cfg.getString("photo.name", "<#cfc7b8>빛바랜 사진"))));
        return it;
    }

    private ItemStack newPhoto(Player p) {
        UUID u = p.getUniqueId();
        String serial = Long.toHexString(System.currentTimeMillis()) + Long.toHexString(ThreadLocalRandom.current().nextLong());
        m.set(this, u, "photo", serial);   // the copies given before are only pictures now
        ItemStack it = photoItem();
        List<Component> lore = lore("photo.lore", words(p), "<gray>빛바랜 흑백 사진. 차 한 대가 액자 밖으로 반쯤 잘려 있다.", "<gray>{wheel}");
        it.editMeta(meta -> {
            meta.setMaxStackSize(1);
            meta.lore(lore);
            meta.getPersistentDataContainer().set(PHOTO_OWNER, PersistentDataType.STRING, u.toString());
            meta.getPersistentDataContainer().set(PHOTO_SERIAL, PersistentDataType.STRING, serial);
        });
        return Items.markBought(it);
    }

    private static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    private void givePhoto(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] all = inv.getContents();
        for (int i = 0; i < all.length; i++) if (ownPhoto(all[i], p.getUniqueId())) inv.setItem(i, null);   // one at a time
        give(p, newPhoto(p));
    }

    private boolean ownChip(ItemStack it, UUID u) {
        return it != null && !it.isEmpty() && it.hasItemMeta()
                && u.toString().equals(it.getItemMeta().getPersistentDataContainer().get(CHIP_OWNER, PersistentDataType.STRING));
    }

    private boolean hasChip(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (ownChip(it, p.getUniqueId())) return true;
        return false;
    }

    private ItemStack chipItem(Player p) {
        ItemStack it = Items.create(CHIP, 1).orElseGet(() -> new ItemStack(Material.PAPER));
        List<Component> lore = lore("chip.lore", words(p), "<gray>난간에 긁혀 묻어 있던 마른 페인트.", "<gray>{paint}");
        it.editMeta(meta -> {
            meta.itemName(Text.item(cfg.getString("chip.name", "<#b9b2a8>페인트 조각")));
            meta.setMaxStackSize(1);
            meta.lore(lore);
            meta.getPersistentDataContainer().set(CHIP_OWNER, PersistentDataType.STRING, p.getUniqueId().toString());
        });
        return Items.markBought(it);
    }

    /** Solved: their photo now has two cars in it (a keepsake). Without the photo on them, a new one. */
    private void turnPhoto(Player p) {
        UUID u = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        ItemStack[] all = inv.getContents();
        ItemStack photo = null;
        for (int i = 0; i < all.length; i++) {
            if (!ownPhoto(all[i], u)) continue;
            if (photo == null) photo = all[i];
            inv.setItem(i, null);
        }
        if (photo == null) photo = newPhoto(p);
        List<Component> lore = lore("photo.done-lore", words(p), "<gray>이제 액자 안에 차가 두 대 있다. 나란히.", "<dark_gray>{date}");
        photo.editMeta(meta -> {
            meta.itemName(Text.item(cfg.getString("photo.done-name", "<gradient:#e8e8e8:#9fb2c8>사진 — 두 대")));
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(PHOTO_DONE, PersistentDataType.BYTE, (byte) 1);
        });
        give(p, photo);
    }

    // ------------------------------------------------------------------------------------------
    // start, jump
    // ------------------------------------------------------------------------------------------

    /** The photo is theirs (stage 1), wherever they are. */
    @Override
    public void start(Player p) {
        UUID u = p.getUniqueId();
        cleanup(u);
        for (String k : List.of("chip", "glow", "look", "try", "tries", "check-at", "retry-at", "arrived", "late")) m.remove(this, u, k);
        givePhoto(p);
        m.stage(this, u, CLUES);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 0.8f);
        say(p, "photo-1", "<dark_gray>…꽃 사이에 기대어 있던 사진을 집어 들었다.");
        later(p, 50, () -> say(p, "photo-2", "<dark_gray>…차가 액자 밖으로 반쯤 잘려 있다. 뒷면에 연필 글씨가 있다."));
    }

    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        if (stage <= 0) {
            m.stage(this, u, 0);
            return;
        }
        m.remove(this, u, "late");
        m.remove(this, u, "arrived");
        if (!hasPhoto(p)) givePhoto(p);
        Combo a = answer();
        if (stage >= NIGHT && a != null) {
            m.set(this, u, "look", a.encode());
            m.set(this, u, "try", a.encode());
            m.remove(this, u, "retry-at");
            m.remove(this, u, "check-at");
        }
        if (stage >= DRIVE) {   // the drive itself: his car before the bend, them at the wheel
            m.stage(this, u, NIGHT);
            Places.Site site = site();
            String why = site == null ? "커브가 없어요 (" + places.why() + ")" : drive.now(p, site, false);
            if (why != null) p.sendMessage(Text.mm("<red>[폐차고] 주행을 시작하지 못함: " + why + " <gray>(3단계에 둠)"));
            return;
        }
        m.stage(this, u, stage);
    }

    private Places.Site site() {
        if (places.site() == null && places.check()) placeChanged();
        return places.site();
    }

    // ------------------------------------------------------------------------------------------
    // every second
    // ------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        beats++;
        if (places.check()) placeChanged();
        Places.Site site = places.site();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) beat(p, site, now);
        if (beats % 300 == 0) cooled.values().removeIf(until -> until < now);
    }

    /** The bend or the garage moved (their road changed or went): nothing stays at the old place, no drive goes on. */
    private void placeChanged() {
        drive.endAll();
        props.clearAll();
        memorials.clear();
        held.clear();
        for (Player p : Bukkit.getOnlinePlayers()) shed.hide(p.getUniqueId());
    }

    private String plaqueName(Player p) {
        String name = eco.kv().get(PLAQUE);
        return name != null ? name : stage(p.getUniqueId()) > stages() ? p.getName() : null;
    }

    private void beat(Player p, Places.Site site, long now) {
        UUID u = p.getUniqueId();
        Long ground = groundDue.remove(u);
        if (ground != null) landing(p);
        int st = stage(u);
        if (site == null || guide(p) || !p.getWorld().getName().equals(site.world())) {
            // nothing of it is here for them (no place, the 길라잡이, another world): what they were shown elsewhere goes
            demos(p, now);
            if (!demoMemorial.containsKey(u) && props.any(u, MEMORIAL)) {
                props.drop(u, MEMORIAL);
                memorials.remove(u);
            }
            props.drop(u, CHIP_GROUP);
            if (!demoShed.containsKey(u) && props.any(u, Shed.GROUP)) shed.hide(u);
            if (st == DRIVE && !drive.has(u) && !guide(p)) settle(p);
            return;
        }
        // the story is over for this viewer: they finished it, or someone else did (a tester keeps their own story)
        boolean after = st > stages() || (m.closed(this) && !m.test(u));
        if (after && st >= CLUES && st <= stages() && m.get(this, u, "late") == null) tooLate(p);
        demos(p, now);
        memorial(p, site, st, after);
        garage(p, st, after);
        if (after || !mayGoOn(p)) return;
        if (st >= CLUES) clues(p, site, now);
        if (st == NIGHT) nightAtTheBend(p, site, now);
        if (st == DRIVE && !drive.has(u)) settle(p);
    }

    /** Stage 4 without a drive going on (a quit, a restart): the garage was reached — the end; else back to waiting for a night. */
    private void settle(Player p) {
        UUID u = p.getUniqueId();
        if (m.get(this, u, "arrived") != null) finish(p);
        else m.stage(this, u, NIGHT);
    }

    /** Someone else drove him home first: said once, and what they were shown goes. */
    private void tooLate(Player p) {
        UUID u = p.getUniqueId();
        m.set(this, u, "late", 1);
        drive.end(u);
        props.drop(u, CHIP_GROUP);
        checking.remove(u);
        held.remove(u);
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Bench) p.closeInventory();
        p.sendMessage(Text.mm(cfg.getString("too-late", "<dark_gray>…셔터가 내려가 있다. 누군가 먼저 그를 집에 데려다주었다.")));
    }

    // ------------------------------------------------------------------------------------------
    // the memorial at the bend
    // ------------------------------------------------------------------------------------------

    private void memorial(Player p, Places.Site site, int st, boolean after) {
        UUID u = p.getUniqueId();
        if (demoMemorial.containsKey(u)) return;
        double d = flat(p, places.bend()), range = cfg.getDouble("memorial.range", 48);
        if (d > range + 12) {
            if (props.any(u, MEMORIAL)) {
                props.drop(u, MEMORIAL);
                memorials.remove(u);
            }
            props.drop(u, CHIP_GROUP);
            return;
        }
        if (d > range) return;
        String plaque = after ? plaqueName(p) : null;
        boolean frame = after || st == 0 || !hasPhoto(p);
        String how = (after ? "after:" + plaque : "open") + (frame ? "+photo" : "");
        if (how.equals(memorials.get(u)) && props.has(u, MEMORIAL)) return;
        Places.Edge edge = places.edge(0, cfg.getDouble("memorial.side", 4.6));
        if (edge == null || edge.at() == null || !loaded(edge.at(), -1.2, 3.6, -1, 1)) return;   // its chunk is not there yet (or no dry ground at the bend's edge: none)
        props.drop(u, MEMORIAL);
        buildMemorial(p, edge.at(), frame, plaque, "memorial");
        memorials.put(u, how);
    }

    /** Flowers, a burnt-down candle, a framed photo leaning there (while it has not been taken), a click box; a plaque once it is over. */
    private void buildMemorial(Player p, Location at, boolean frame, String plaque, String what) {
        String[][] flowers = {{"POPPY", "-0.95", "-0.15", "0.6"}, {"AZURE_BLUET", "-0.5", "0.25", "0.55"}, {"OXEYE_DAISY", "0.35", "0.2", "0.6"},
                {"WHITE_TULIP", "0.1", "-0.45", "0.55"}, {"CORNFLOWER", "0.75", "-0.25", "0.5"}};
        List<String> own = cfg.getStringList("memorial.flowers");
        for (int i = 0; i < flowers.length; i++) {
            Material mat = Material.matchMaterial(i < own.size() ? own.get(i) : flowers[i][0]);
            if (mat == null || !mat.isBlock()) mat = Material.POPPY;
            float s = Float.parseFloat(flowers[i][3]);
            props.box(p, MEMORIAL, at, mat.createBlockData(), Float.parseFloat(flowers[i][1]) - s / 2, 0, Float.parseFloat(flowers[i][2]) - s / 2, s, s, s);
        }
        props.box(p, MEMORIAL, at, Material.WHITE_CANDLE.createBlockData(), -0.2f, 0, 0.05f, 0.8f, 0.4f, 0.8f);   // burnt down to a stub
        if (frame) {
            Quaternionf lean = new Quaternionf().rotateX((float) Math.toRadians(-14));
            props.box(p, MEMORIAL, at, Material.DARK_OAK_PLANKS.createBlockData(), -0.36f, 0.02f, -0.52f, 0.72f, 0.72f, 0.05f)
                    .setTransformation(new org.bukkit.util.Transformation(new Vector3f(-0.36f, 0.02f, -0.52f), lean, new Vector3f(0.72f, 0.72f, 0.05f), new Quaternionf()));
            props.item(p, MEMORIAL, at, photoItem(), new Vector3f(0, 0.38f, -0.42f), new Vector3f(0.56f, 0.56f, 0.56f), lean);
        }
        if (plaque != null) {
            // a small stone with a name beside the flowers, its face to the road (not behind them: the guardrail is there)
            props.box(p, MEMORIAL, at, Material.POLISHED_DEEPSLATE.createBlockData(), 1.15f, 0, -0.5f, 2.3f, 0.5f, 0.14f);
            org.bukkit.entity.TextDisplay name = props.text(p, MEMORIAL, Props.local(at, 2.3, 0.18, -0.34),
                    line("plaque", "<#e8d9a0>끝까지 같이 달린 사람: <white>{player}").replace("{player}", plaque), false, 0.4f);
            name.setLineWidth(400);   // one line, whatever the name
            name.setBrightness(new org.bukkit.entity.Display.Brightness(12, 12));
        }
        props.hit(p, MEMORIAL, at, 1.9f, 1.1f, what);
    }

    private void clickMemorial(Player p) {
        UUID u = p.getUniqueId();
        int st = stage(u);
        boolean after = st > stages() || (m.closed(this) && !m.test(u));
        if (after) {
            whisper(p, "memorial-after", "<dark_gray>…꽃이 새것으로 바뀌어 있다.");
            return;
        }
        if (!mayGoOn(p)) return;
        if (st == 0) {
            start(p);
            props.drop(u, MEMORIAL);   // shown again without the photo
            memorials.remove(u);
        } else if (!hasPhoto(p)) {
            givePhoto(p);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 0.8f);
            whisper(p, "photo-back", "<dark_gray>…사진이 다시 그 자리에 놓여 있다.");
        } else whisper(p, "memorial-again", "<dark_gray>…누군가 꽃을 갈아 두었다.");
    }

    // ------------------------------------------------------------------------------------------
    // the clues at the bend
    // ------------------------------------------------------------------------------------------

    private void clues(Player p, Places.Site site, long now) {
        UUID u = p.getUniqueId();
        double d = flat(p, places.bend());
        // the paint: a chip on the guardrail, there only while the photo is in their hand
        boolean chip = d <= 20 && holdingPhoto(p) && !hasChip(p);
        if (!chip) props.drop(u, CHIP_GROUP);
        else if (!props.has(u, CHIP_GROUP)) {
            props.drop(u, CHIP_GROUP);
            Places.Edge edge = places.edge(cfg.getDouble("chip.along", 3), cfg.getDouble("memorial.side", 4.6));
            // lying on the rail's top (on the ground where the bend has no rail; nowhere where neither is there)
            Location at = edge == null ? null : edge.rail() != null ? edge.rail() : edge.at();
            if (at != null) {
                props.item(p, CHIP_GROUP, at, chipItem(p), new Vector3f(0, 0.04f, 0), new Vector3f(0.3f, 0.3f, 0.3f),
                        new Quaternionf().rotateX((float) Math.toRadians(-90)));
                props.hit(p, CHIP_GROUP, at.clone().add(0, -0.25, 0), 0.8f, 0.6f, "chip");
                p.spawnParticle(Particle.WAX_OFF, at.clone().add(0, 0.2, 0), 4, 0.15, 0.1, 0.15, 0);
                if (cool(u, "chip", 60)) whisper(p, "chip-seen", "<dark_gray>…난간에 뭔가 긁혀 묻어 있다.");
            }
        }
        // the glow: at night the asphalt keeps a faint light where the car slid
        Combo a = answer();
        Bench.Option glow = a == null ? null : option("underglows", a.glow());
        if (glow != null && d <= cfg.getDouble("glow.range", 14) && night(p)) {
            Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(glow.color() & 0xffffff), 0.9f);
            for (int k = 0; k < 9; k++) {
                double[] at = Roadway.beside(site.pts(), site.bend() - 5 + k * 1.3, site.outside() * (0.2 + k * 0.42), 4);
                p.spawnParticle(Particle.DUST, at[0], Roadway.height(site.halves(), site.bend() - 5 + k * 1.3) + 0.08, at[1], 2, 0.12, 0.01, 0.12, 0, dust);
            }
            if (cool(u, "glow", 45)) {
                whisper(p, "glow", "<dark_gray>…아스팔트에 빛이 남아 있다.");
                if (m.get(this, u, "glow") == null) m.set(this, u, "glow", 1);
            }
        }
    }

    private void clickChip(Player p) {
        UUID u = p.getUniqueId();
        if (!mayGoOn(p) || stage(u) < CLUES || stage(u) > stages() || hasChip(p)) return;
        props.drop(u, CHIP_GROUP);
        give(p, chipItem(p));
        m.set(this, u, "chip", 1);
        p.playSound(p.getLocation(), Sound.BLOCK_STONE_HIT, 0.6f, 1.6f);
        say(p, "chip", "<dark_gray>…난간에서 마른 페인트 조각이 떨어져 나왔다.");
    }

    // ------------------------------------------------------------------------------------------
    // the garage
    // ------------------------------------------------------------------------------------------

    private double openTo() {
        return Math.clamp(cfg.getDouble("garage.open", 0.62), 0.2, 1);
    }

    private void garage(Player p, int st, boolean after) {
        UUID u = p.getUniqueId();
        if (demoShed.containsKey(u)) return;
        boolean preview = drive.previewing(u);
        boolean want = after || st >= CLUES || preview;
        double d = flat(p, places.garageNear()), range = cfg.getDouble("garage.range", 64);
        if (!want || d > range + 16) {
            if (shed.showing(u) || props.any(u, Shed.GROUP)) shed.hide(u);
            return;
        }
        if (d > range) return;
        places.fit();
        Location o = places.garage();
        if (o == null || !loaded(o, -3.8, 3.8, -7.3, 2.6)) return;   // the ground there cannot be read yet (or no dry ground: none)
        if (!places.stillDry()) {   // its ground is not dry (any more): fitted again elsewhere, nothing left standing here
            for (Player q : Bukkit.getOnlinePlayers()) if (!demoShed.containsKey(q.getUniqueId())) shed.hide(q.getUniqueId());
            return;
        }
        Double hold = held.get(u);
        if (after) {
            shed.show(p, o, true, hold != null ? hold : 0, Shed.EMPTY);
            return;
        }
        if (preview || st == DRIVE) {
            shed.show(p, o, false, hold != null ? hold : openTo(), Shed.EMPTY);
            return;
        }
        if (!mayGoOn(p)) return;
        Combo a = answer();
        switch (st) {
            case CLUES -> {
                shed.show(p, o, false, 0, Shed.TARP);
                if (flat(p, o) <= cfg.getDouble("garage.near", 9) && Math.abs(p.getLocation().getY() - o.getY()) < 6 && hasPhoto(p)) arrive(p);
            }
            case RESTORE -> {
                Combo look = Combo.decode(m.get(this, u, "look"));
                shed.show(p, o, false, hold != null ? hold : openTo(), look.empty() ? Shed.TARP : look.encode());
            }
            default -> {
                // (his car is out on the road while it waits before the bend: not in here too)
                shed.show(p, o, false, hold != null ? hold : openTo(), drive.has(u) ? Shed.EMPTY : a == null ? Shed.TARP : a.encode());
                // (not while the shutter is still down on the look that was right: he has not said so yet)
                if (flat(p, o) <= 12 && !drive.has(u) && !checking.contains(u) && cool(u, "come", 90))
                    whisper(p, "come-at-night", "<#9fb2c8>…밤에, 그 커브로 와.");
            }
        }
    }

    /** They came with the photo: the shutter rattles up halfway (stage 2). */
    private void arrive(Player p) {
        UUID u = p.getUniqueId();
        m.stage(this, u, RESTORE);
        shed.shutter(p, openTo(), 34);
        Location o = shed.origin(u);
        if (o != null) {
            later(p, 12, () -> p.playSound(o, Sound.BLOCK_CHAIN_PLACE, SoundCategory.AMBIENT, 0.8f, 0.5f));
            later(p, 24, () -> p.playSound(o, Sound.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.AMBIENT, 0.7f, 0.5f));
        }
        later(p, 50, () -> say(p, "garage-1", "<#9fb2c8>…내 차."));
        later(p, 100, () -> say(p, "garage-2", "<#9fb2c8>…그날 그대로 해 줘."));
        later(p, 150, () -> whisper(p, "garage-bench", "<dark_gray>…안쪽에 작업대가 있다."));
    }

    /** The shutter of this viewer's garage goes to this height and stays there until {@link #release}. */
    void shutter(Player p, double open, int ticks) {
        held.put(p.getUniqueId(), open);
        if (shed.showing(p.getUniqueId())) shed.shutter(p, open, ticks);
    }

    void release(UUID u) {
        held.remove(u);
    }

    // ------------------------------------------------------------------------------------------
    // the workbench
    // ------------------------------------------------------------------------------------------

    private long checkWait(UUID u) {
        return Math.max(0, (m.getLong(this, u, "check-at", 0) - System.currentTimeMillis() + 999) / 1000);
    }

    private void openBench(Player p, boolean preview) {
        UUID u = p.getUniqueId();
        if (Bench.options(cfg, "paints").isEmpty() || answer() == null) {
            p.sendMessage(Text.mm("<red>[폐차고] garage.yml options 가 비어 있어요"));
            return;
        }
        Combo look = preview ? Combo.NONE : Combo.decode(m.get(this, u, "look"));
        Bench b = Bench.open(cfg, u, preview, look, preview ? List.of() : Combo.notebook(m.get(this, u, "tries")), preview ? 0 : checkWait(u));
        p.openInventory(b.getInventory());
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.25f, 1.4f);
    }

    private void clickBench(Player p) {
        UUID u = p.getUniqueId();
        if (demoShed.containsKey(u)) {
            openBench(p, true);
            return;
        }
        int st = stage(u);
        if (st > stages() || (m.closed(this) && !m.test(u)) || !mayGoOn(p)) return;
        if (checking.contains(u)) return;
        if (st == RESTORE) openBench(p, false);
        else if (st >= NIGHT) whisper(p, "bench-done", "<dark_gray>…더 손댈 데가 없다.");
    }

    @EventHandler
    public void onBenchClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Bench b)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(b.viewer())) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        UUID u = p.getUniqueId();
        String[] pick = b.slots.get(e.getSlot());
        if (pick != null) {
            b.look = b.look.with(pick[0], pick[1]);
            if (!b.preview) m.set(this, u, "look", b.look.encode());
            b.draw(cfg, b.preview ? List.of() : Combo.notebook(m.get(this, u, "tries")), b.preview ? 0 : checkWait(u));
            if (shed.showing(u)) shed.inside(p, b.look.encode());   // the tarp comes off: the car as chosen
            p.playSound(p.getLocation(), Sound.ITEM_SPYGLASS_USE, 0.6f, 1.2f);
        } else if (e.getSlot() == b.button) check(p, b);
    }

    @EventHandler
    public void onBenchDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Bench) e.setCancelled(true);
    }

    /** The shutter comes down on a look; a few seconds later he says how many of the three are his. */
    private void check(Player p, Bench b) {
        UUID u = p.getUniqueId();
        Combo guess = b.look, a = answer();
        if (!guess.whole() || a == null) {
            whisper(p, "pick-all", "<dark_gray>…아직 다 고르지 않았다. 도색, 휠, 그리고 밑의 불빛.");
            return;
        }
        if (b.preview) {
            p.closeInventory();
            p.sendMessage(Text.mm("<yellow>[연출] 맞은 개수 " + Combo.right(guess, a) + "/3 <gray>(아무것도 저장하지 않음)"));
            return;
        }
        if (stage(u) != RESTORE || !mayGoOn(p) || checking.contains(u)) {
            p.closeInventory();
            return;
        }
        if (checkWait(u) > 0) {
            whisper(p, "check-wait", "<dark_gray>…셔터 너머가 조용하다. 조금 기다리자.");
            b.draw(cfg, Combo.notebook(m.get(this, u, "tries")), checkWait(u));
            return;
        }
        // decided and written down at once: leaving while the shutter is down changes nothing
        int n = Combo.right(guess, a);
        long wait = (m.test(u) ? cfg.getLong("test-check-seconds", 5) : cfg.getLong("check-seconds", 300)) * 1000L;
        List<Combo.Try> tries = Combo.notebook(m.get(this, u, "tries"));
        tries.add(new Combo.Try(guess, n));
        m.set(this, u, "tries", Combo.notebook(tries, 24));
        m.set(this, u, "try", guess.encode());
        m.set(this, u, "check-at", System.currentTimeMillis() + wait);
        if (n == 3) m.stage(this, u, NIGHT);
        checking.add(u);
        p.closeInventory();
        shutter(p, 0, 18);
        long shut = Math.max(1, cfg.getLong("shut-seconds", 4)) * 20L;
        later(p, shut / 2, () -> {
            Location o = shed.origin(u);
            if (o != null) p.playSound(o, Sound.BLOCK_CHAIN_STEP, SoundCategory.AMBIENT, 0.5f, 0.5f);
        });
        later(p, shut, () -> {
            checking.remove(u);
            release(u);
            if (shed.showing(u)) shed.shutter(p, openTo(), 26);
            switch (n) {
                case 0 -> say(p, "right-0", "<#9fb2c8>…아니야. 하나도.");
                case 1 -> say(p, "right-1", "<#9fb2c8>…하나는 내 거야.");
                case 2 -> say(p, "right-2", "<#9fb2c8>…거의 다 왔어. 하나가 달라.");
                default -> {
                    say(p, "right-3", "<#9fb2c8>…그래. 이 차야.");
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.5f, 0.6f);
                    cool(u, "come", 90);   // said below: not whispered again right after
                    later(p, 60, () -> say(p, "come-at-night", "<#9fb2c8>…밤에, 그 커브로 와."));
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // the last drive
    // ------------------------------------------------------------------------------------------

    /** Stage 3: at night near the bend his car appears before it and waits (after a failed night: not before retry-at). */
    private void nightAtTheBend(Player p, Places.Site site, long now) {
        UUID u = p.getUniqueId();
        // (a garage close to the bend: nothing before he has said the look is right)
        if (drive.has(u) || checking.contains(u) || flat(p, places.bend()) > cfg.getDouble("drive.range", 40) || !night(p)) return;
        if (now < m.getLong(this, u, "retry-at", 0)) {
            if (cool(u, "not-tonight", 120)) whisper(p, "not-tonight", "<#9fb2c8>…오늘은 아니야. 다음 밤에.");
            return;
        }
        if (vm() == null || cars.item() == null) return;
        drive.await(p, site);
    }

    /** They got in (stage 4). */
    void driving(Player p) {
        m.stage(this, p.getUniqueId(), DRIVE);
        m.remove(this, p.getUniqueId(), "arrived");
    }

    /** The ghost reached the garage with them beside it: it counts from here, whatever happens while he rolls in. */
    void home(Player p) {
        m.set(this, p.getUniqueId(), "arrived", System.currentTimeMillis());
    }

    /** The drive is over at the garage (the temporary car is gone). */
    void arrived(Player p, boolean preview) {
        if (preview) {
            p.sendMessage(Text.mm("<yellow>[연출] 마지막 드라이브 끝 (아무것도 바뀌지 않음)"));
            return;
        }
        finish(p);
    }

    /** A rule was broken (the temporary car is gone): back to waiting for another night. */
    void failed(Player p, boolean preview) {
        UUID u = p.getUniqueId();
        p.playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.AMBIENT, 0.3f, 0.5f);
        say(p, "fail", "<#9fb2c8>…괜찮아. 다음 밤에.");
        if (preview) {
            p.sendMessage(Text.mm("<yellow>[연출] 주행 실패 (아무것도 바뀌지 않음)"));
            return;
        }
        if (stage(u) != DRIVE) return;
        m.stage(this, u, NIGHT);
        m.remove(this, u, "arrived");
        m.set(this, u, "retry-at", System.currentTimeMillis() + retryMs(u));
    }

    private long retryMs(UUID u) {
        return (m.test(u) ? cfg.getLong("test-retry-seconds", 10) : cfg.getLong("retry-seconds", 300)) * 1000L;
    }

    /** Home together: solved (unless someone else was first): the car, the photo, the plaque. */
    private void finish(Player p) {
        UUID u = p.getUniqueId();
        m.remove(this, u, "arrived");
        if (stage(u) != DRIVE) return;
        if (guide(p) || p.isDead()) {
            // in the 길라잡이 (cannot be: it has no 국도), or dead on the respawn screen (what is put in their pockets now is
            // lost): it stays reached, and ends the next second they are back
            m.set(this, u, "arrived", System.currentTimeMillis());
            return;
        }
        boolean test = m.test(u);
        m.solve(this, p);
        if (stage(u) <= stages()) {   // someone else was first (solve said so)
            m.set(this, u, "late", 1);
            return;
        }
        // a tester's end closes nothing and can be played again and again: no real car for it (the title and the photo only)
        if (test) p.sendMessage(Text.mm("<yellow>[테스트] 보상 튜닝카는 테스트 모드에서는 주지 않아요 <gray>(진짜로 풀었을 때 한 대, 귀속)"));
        else giveCar(p);
        turnPhoto(p);
        if (!test) eco.kv().set(PLAQUE, p.getName());
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 0.6f);
        // everyone near sees the end from now on: the plaque, the lit sign
        memorials.clear();
        for (Player o : Bukkit.getOnlinePlayers()) props.drop(o.getUniqueId(), MEMORIAL);
    }

    /** A real 튜닝카, bound to them (as the bus secret's sedan is); into the mailbox when their pockets are full. Never in test mode. */
    private void giveCar(Player p) {
        UUID u = p.getUniqueId();
        ItemStack it = Items.create(cars.itemId(), 1).orElse(null);
        if (it == null) {
            eco.getLogger().warning("[비밀] 폐차고: 보상 탈것 " + cars.itemId() + " 이(가) 없어 " + p.getName() + " 에게 주지 못함");
            return;
        }
        if (vm() != null) vm().bind(it, u, p.getName());
        String name = Items.mmName(it);
        if (eco.mail() != null) eco.mail().deliver(u, it, "secret_garage");   // into their pockets, or the mailbox when there is no room
        else give(p, it);
        p.sendMessage(Text.mm(line("car-given", "<gray>받은 것: {item} <dark_gray>— 열쇠가 조수석에 놓여 있었다.").replace("{item}", name)));
    }

    // ------------------------------------------------------------------------------------------
    // clicks, joining and leaving
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        Entity hit = e.getRightClicked();
        Player p = e.getPlayer();
        if (hit instanceof Interaction && hit.getPersistentDataContainer().has(Props.TAG)) {
            e.setCancelled(true);
            if (e.getHand() != EquipmentSlot.HAND || !on()) return;
            String what = Props.what(hit, p);
            if (what == null || guide(p)) return;
            switch (what) {
                case "memorial" -> clickMemorial(p);
                case "chip" -> clickChip(p);
                case "bench" -> clickBench(p);
                default -> whisper(p, "demo", "<yellow>[연출]");
            }
            return;
        }
        // his car waiting before the bend lets its own player in (the core lets nobody into a temporary vehicle by a click)
        if (e.getHand() != EquipmentSlot.HAND || !drive.has(p.getUniqueId())) return;
        Long id = hit.getPersistentDataContainer().get(VehicleModule.PART, PersistentDataType.LONG);
        if (id != null && !p.isSneaking() && !p.isInsideVehicle()) drive.board(p, id);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Interaction hit && hit.getPersistentDataContainer().has(Props.TAG)) e.setCancelled(true);
    }

    /** Players to put on solid ground on the next beat (they left in the middle of a drive). */
    private final Map<UUID, Long> groundDue = new HashMap<>();

    /** Leaving in the middle of the drive is a broken rule (the wait applies); where they stand next time is kept. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        Drive.Run r = drive.run(u);
        if (r == null) return;
        if (!r.preview && r.phase >= Drive.READY && r.phase <= Drive.HOME) {
            Location safe = drive.roadside(r, p.getLocation().getX(), p.getLocation().getZ());
            if (safe != null) m.set(this, u, "ground", safe.getWorld().getName() + " " + safe.getX() + " " + safe.getY() + " " + safe.getZ());
            if (r.phase < Drive.HOME && stage(u) == DRIVE) {
                m.stage(this, u, NIGHT);
                m.set(this, u, "retry-at", System.currentTimeMillis() + retryMs(u));
            }
        }
        drive.end(u);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (m.get(this, e.getPlayer().getUniqueId(), "ground") != null) groundDue.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /** They left from the wheel of his car: now they stand at the roadside. */
    private void landing(Player p) {
        UUID u = p.getUniqueId();
        String g = m.get(this, u, "ground");
        m.remove(this, u, "ground");
        if (g == null || p.isDead() || p.isInsideVehicle()) return;
        String[] w = g.split(" ");
        World world = w.length < 4 ? null : Bukkit.getWorld(w[0]);
        if (world == null || !world.equals(p.getWorld())) return;
        try {
            Location at = new Location(world, Double.parseDouble(w[1]), Double.parseDouble(w[2]), Double.parseDouble(w[3]), p.getLocation().getYaw(), p.getLocation().getPitch());
            // (the core put them beside the car as they left; the roadside only if it is still ground to stand on)
            if (at.distanceSquared(p.getLocation()) < 48 * 48 && Drive.standable(at)) p.teleport(at);
        } catch (NumberFormatException ignored) {
            // a broken line: they stay where they are
        }
    }

    @Override
    public void cleanup(UUID u) {
        epoch.merge(u, 1, Integer::sum);
        drive.end(u);
        shed.hide(u);
        props.clear(u);
        memorials.remove(u);
        held.remove(u);
        checking.remove(u);
        demoMemorial.remove(u);
        demoShed.remove(u);
        Player p = Bukkit.getPlayer(u);
        if (p != null && p.getOpenInventory().getTopInventory().getHolder(false) instanceof Bench) p.closeInventory();
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    /** An admin's 추모 / 차고 goes when its time is up. */
    private void demos(Player p, long now) {
        UUID u = p.getUniqueId();
        Long mem = demoMemorial.get(u), sh = demoShed.get(u);
        if (mem != null && now >= mem) {
            demoMemorial.remove(u);
            props.drop(u, MEMORIAL);
            memorials.remove(u);
        }
        if (sh != null && now >= sh) {
            demoShed.remove(u);
            shed.hide(u);
        }
    }

    @Override
    public String status(UUID u) {
        List<String> out = new ArrayList<>();
        Places.Site s = places.site();
        if (s == null) out.add("잠듦: " + (places.why().isEmpty() ? "아직 국도를 보지 않음" : places.why()));
        else out.add("커브 " + s.world() + " " + Places.coords(s, s.bend()) + (s.hard() ? "" : " (어려운길 아님)") + " · 차고 " + Places.coords(s, s.garage())
                + (s.fitted() ? "" : places.dryWhy().isEmpty() ? " (자리 맞추기 전)" : " · 잠듦: " + places.dryWhy()));
        Combo a = answer();
        out.add("정답 " + (a == null ? "(옵션 없음)" : Bench.words(cfg, a)));
        int st = stage(u);
        long now = System.currentTimeMillis();
        if (st >= CLUES && st <= stages()) {
            out.add("단서: 사진" + (m.get(this, u, "chip") != null ? " · 페인트 조각" : "") + (m.get(this, u, "glow") != null ? " · 밤의 빛" : ""));
            List<Combo.Try> tries = Combo.notebook(m.get(this, u, "tries"));
            if (!tries.isEmpty()) out.add("확인 " + tries.size() + "번, 마지막 " + Bench.words(cfg, tries.getLast().look()) + " → " + tries.getLast().right());
            long check = m.getLong(this, u, "check-at", 0) - now, retry = m.getLong(this, u, "retry-at", 0) - now;
            if (st == RESTORE && check > 0) out.add("다음 확인까지 " + (check + 999) / 1000 + "초");
            if (st == NIGHT) out.add(retry > 0 ? "다음 밤까지 " + (retry + 999) / 1000 + "초" : "밤에 커브로 오면 차가 기다림" + (vm() == null ? " (탈것 모듈 꺼짐!)" : ""));
            if (m.get(this, u, "late") != null) out.add("늦음 (다른 사람이 먼저)");
        }
        Drive.Run r = drive.run(u);
        if (r != null) out.add("주행 " + switch (r.phase) {
            case Drive.WAITING -> "차가 기다리는 중";
            case Drive.READY -> "출발 직전";
            case Drive.GO -> "유령 " + Math.round(r.s) + "/" + r.end + " · 간격 " + Math.round(r.along - r.s) + " · 속도 " + String.format("%.1f", r.v);
            case Drive.HOME -> "차고 도착";
            default -> "연출";
        } + (r.preview ? " (연출)" : ""));
        String plaque = eco.kv().get(PLAQUE);
        if (plaque != null) out.add("명판 " + plaque);
        return String.join(" · ", out);
    }

    @Override
    public List<String> scenes() {
        return List.of("추모", "차고", "정답", "복원", "유령", "주행", "위치다시", "커브여기", "차고여기");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        switch (word) {
            case "추모" -> {
                Location at = ahead(p, 2.5);
                props.drop(u, MEMORIAL);
                memorials.remove(u);
                demoMemorial.put(u, now + 30_000L);
                buildMemorial(p, at, true, eco.kv().get(PLAQUE) != null ? eco.kv().get(PLAQUE) : p.getName(), "demo");
            }
            case "차고" -> {
                Location at = ahead(p, 7);
                shed.hide(u);
                demoShed.put(u, now + 90_000L);
                shed.show(p, at, false, 0, Shed.TARP);
                later(p, 50, () -> {
                    if (demoShed.containsKey(u)) shed.shutter(p, openTo(), 34);
                });
                later(p, 100, () -> say(p, "garage-1", "<#9fb2c8>…내 차."));
            }
            case "정답" -> {
                Combo a = answer();
                admin.sendMessage(Text.mm("<gold>폐차고 <gray>정답: <white>" + (a == null ? "(garage.yml options 가 비어 있음)" : Bench.words(cfg, a) + " <dark_gray>(" + a.encode() + ")")));
                if (a != null) admin.sendMessage(Text.mm("<gray>모델: <white>" + String.join("<gray>, <white>", cars.keys(a))));
                Places.Site s = site();
                admin.sendMessage(Text.mm(s == null ? "<gray>장소: <red>없음 <gray>(" + places.why() + ")" : "<gray>커브 <white>" + s.world() + " " + Places.coords(s, s.bend())
                        + " <gray>(" + Math.round(s.degrees()) + "도" + (s.hard() ? ", 어려운길" : ", 어려운길 아님") + ", 바깥쪽 " + (s.outside() > 0 ? "오른쪽" : "왼쪽")
                        + ") · 차고 <white>" + Places.coords(s, s.garage()) + " <gray>(" + (s.fitted() ? (s.side() > 0 ? "오른쪽" : "왼쪽") + " " + Math.round(places.off(s)) + "칸, 바닥 y " + s.floor()
                        : places.dryWhy().isEmpty() ? "자리 맞추기 전" : "<red>" + places.dryWhy() + "<gray>")
                        + ") · 길 <white>" + s.dock() + " <gray>" + s.pts().size() + "칸"));
                return true;
            }
            case "복원" -> openBench(p, true);
            case "유령" -> drive.demo(p, site(), 20);
            case "주행" -> {
                Places.Site s = site();
                String why = s == null ? "커브가 없어요 (" + places.why() + ")" : drive.now(p, s, true);
                if (why != null) {
                    admin.sendMessage(Text.mm("<red>" + why));
                    return true;
                }
            }
            case "커브여기", "차고여기" -> {
                String why = word.equals("커브여기") ? places.setBend(p.getLocation()) : places.setGarage(p.getLocation());
                if (why != null) {
                    admin.sendMessage(Text.mm("<red>[폐차고] " + why));
                    return true;
                }
                placeChanged();
                Places.Site s = places.site();
                admin.sendMessage(Text.mm("<green>[폐차고] 옮김: 커브 " + s.world() + " " + Places.coords(s, s.bend()) + " · 차고 " + Places.coords(s, s.garage())
                        + (s.fitted() ? " <gray>(" + (s.side() > 0 ? "길 오른쪽" : "길 왼쪽") + ", 바닥 y " + s.floor() + ")" : " <gray>(차고는 길 끝: 누가 가까이 가면 땅에 맞춤. 직접 정하려면 차고여기)")
                        + " <gray>· 길이 바뀌지 않는 한 그대로 남아요. 진행 중이던 드라이브는 끝남"));
                return true;
            }
            case "위치다시" -> {
                places.refresh(true);
                placeChanged();
                Places.Site s = places.site();
                admin.sendMessage(Text.mm(s == null ? "<red>장소를 정하지 못함: " + places.why() : "<green>다시 정함: 커브 " + s.world() + " " + Places.coords(s, s.bend())
                        + " · 차고 " + Places.coords(s, s.garage()) + " <gray>(차고의 정확한 자리는 누가 가까이 가면 땅에 맞춤)"));
                return true;
            }
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] " + word + " → " + p.getName()));
        return true;
    }

    /** A frame this far ahead of the player on their own level, facing them. */
    private static Location ahead(Player p, double blocks) {
        Location me = p.getLocation();
        org.bukkit.util.Vector d = me.getDirection().setY(0);
        if (d.lengthSquared() < 1e-4) d = new org.bukkit.util.Vector(0, 0, 1);
        d.normalize();
        Location at = me.clone().add(d.clone().multiply(blocks));
        at.setY(Math.floor(me.getY() + 0.01));
        at.setYaw(Roadway.yaw(-d.getX(), -d.getZ()));
        at.setPitch(0);
        return at;
    }
}
