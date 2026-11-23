package dev.stsv.economy.secret.pager;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.bus.BusModule;
import dev.stsv.economy.bus.BusStop;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 울리는 삐삐 (docs/admin/숨은_비밀/울리는_삐삐.md; contents/economy/secrets/pager.yml). Deep underground something beeps
 * inside the rock: a pager from 1997 that still receives. The messages are a little girl's, to her father, a miner who
 * did not come home on 1997-11-29 — digits only, the way pagers spoke (045, 10288, 8282, 505, 100, 1004, 20000). The game
 * never says what a number means; the player does what it asks.
 * <p>
 * Stage 0: mining rock at depth may, very rarely, set a buried block beeping for the miner alone; breaking it (or one
 * touching it) frees the pager ({@link Device}) — stage 1. Its tape names a bus stop by its initial consonants; near that
 * stop stands a house front only they see ({@link House}). Arriving there with the pager: stage 2, and the messages come.
 * 2: 045 — bread in the gate's basket. 3: 10288 — something warm. 4: 8282 — it comes while they are far, and they must be
 * at the gate in time. Each done errand is answered 1004. 5: 505, a knocking from below, then 100, once for every year
 * since, and at last 20000. 6: nothing more arrives; the pager itself goes into the red mailbox — the answer to 100 is
 * to come back. The house answers once and goes dark; solved. A while later a letter comes from the daughter, with the
 * pager, alive: its owner can page anyone ({@link Paging}).
 * <p>
 * Saved (eco.kv "secret.pager.&lt;uuid&gt;."): stage, since, day (the day it last began to beep), found ("world x y z"),
 * serial (the pager copy that works), stop / stop-name / tape (the stop chosen and what the tape says), anchor ("world x
 * y z nx nz": where to look for the house), house ("world x y z yaw", once found), msgs (the screen), clock (minutes of
 * that evening), ph (wait | ask | reply | knock | scroll), at (when the next thing happens), deadline (8282; left: what
 * was left of it when they went away), until (knocking), idx (scrolling), dark, refused, force, fixed (an admin put the
 * house where it is), ending, late, letter-at, letter-sent, perk, live (the live pager copy that works), done.
 */
public final class Pager extends Secret {

    static final int LAST = 6;
    /** The 직업 module's mark on a chunk: the stone, deepslate and ore blocks a player put there (read only). */
    private static final NamespacedKey PLACED = new NamespacedKey("economy", "placed");
    private static final BlockFace[] SIDES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private static final class Beep {
        World w;
        int x, y, z;
        long until;
        /** preview: an admin's look (digging it out changes nothing); anyDepth: begun by an admin, above the usual depth too */
        boolean preview, anyDepth;

        boolean touches(Block b) {
            return b.getWorld().equals(w) && Math.abs(b.getX() - x) <= 1 && Math.abs(b.getY() - y) <= 1 && Math.abs(b.getZ() - z) <= 1;
        }

        Location center() {
            return new Location(w, x + 0.5, y + 0.5, z + 0.5);
        }
    }

    private final Device device = new Device(this);
    private final House house;
    private final Paging paging = new Paging(this);
    /** 후속 임무 「호출」: after the end, a number from the grown daughter about once a week ({@link PagerSequel}). */
    private final PagerSequel sequel = new PagerSequel(this);
    private final Map<UUID, Beep> beeps = new HashMap<>();
    /** player -> since when the pager has not been on them */
    private final Map<UUID, Long> without = new HashMap<>();
    /** player -> when 8282 comes again */
    private final Map<UUID, Long> nextRush = new HashMap<>();
    /** player -> where their house is (read from "house" once) */
    private final Map<UUID, Location> gates = new HashMap<>();
    /** admin previews: knocking until; the scrolling years (how far, and what the screen shows) */
    private final Map<UUID, Long> knockPreview = new HashMap<>();
    private final Map<UUID, List<Codes.Msg>> scrollPreview = new HashMap<>();
    /** players in test mode (kept each second, so that breaking a block reads nothing saved until the roll hits) */
    private final Set<UUID> testers = new HashSet<>();
    /** player -> until when nothing happens to them (just joined: their screen is still loading) */
    private final Map<UUID, Long> settling = new HashMap<>();
    /** player -> when their house was last looked for again because its place was built over */
    private final Map<UUID, Long> repicked = new HashMap<>();
    /** player -> when no dry ground was found anywhere around the spawn (looked for again ten minutes later) */
    private final Map<UUID, Long> noDry = new HashMap<>();
    /** players whose admin-placed house stands on ground that is not dry (said once on the console) */
    private final Set<UUID> warnedWet = new HashSet<>();
    private long beats;
    private double chance = 0.00005;
    private int maxY;

    public Pager(SecretModule m) {
        super(m);
        house = new House(this, eco);
    }

    @Override
    public String id() {
        return "pager";
    }

    @Override
    public String name() {
        return "삐삐";
    }

    @Override
    public String stoneTitle() {
        return "울리는 삐삐";
    }

    /** 1: the pager is theirs; 2..4: the errands; 5: the turn; 6: the mailbox. */
    @Override
    public int stages() {
        return LAST;
    }

    @Override
    protected void loaded() {
        chance = cfg.getDouble("start.chance", 0.00005);
        maxY = cfg.getInt("start.max-y", 0);
        YamlConfiguration lang = new YamlConfiguration();
        lang.set("mail.reason.pager", cfg.getString("letter.mail-reason", "발신: 파란 대문 집"));
        Lang.useExtra("STSV-Economy-secret-pager", lang);
    }

    // ------------------------------------------------------------------------------------------
    // small things the other classes use
    // ------------------------------------------------------------------------------------------

    YamlConfiguration cfg() {
        return cfg;
    }

    @Override
    public dev.stsv.economy.secret.Sequel sequel() {
        return sequel;
    }

    Device device() {
        return device;
    }

    /** The live pager paged a name that is nobody online: hers, while one of her calls is open ({@link PagerSequel#paged}). */
    boolean herPage(Player p, String name, String digits) {
        return sequel.paged(p, name, digits);
    }

    org.bukkit.plugin.Plugin plugin() {
        return eco;
    }

    String text(String key, String def) {
        return cfg.getString(key, def);
    }

    String kv(UUID u, String k) {
        return m.get(this, u, k);
    }

    void kvSet(UUID u, String k, Object v) {
        m.set(this, u, k, v);
    }

    private void kvRemove(UUID u, String... keys) {
        for (String k : keys) m.remove(this, u, k);
    }

    private int stage(UUID u) {
        return m.stage(this, u);
    }

    private boolean guide(Player p) {
        return eco.guide() != null && eco.guide().inGuide(p);
    }

    private boolean playing(UUID u) {
        int st = stage(u);
        return st >= 1 && st <= LAST && on() && open(u) && kv(u, "late") == null;
    }

    /** A sound only they hear, from a spot. */
    void sound(Player p, Location at, String key, float volume, float pitch) {
        p.playSound(at, key, SoundCategory.MASTER, volume, pitch);
    }

    /** The pager's triple beep, from a spot, for them alone. */
    void chirp(Player p, Location at, float volume) {
        String key = text("beep.sound", "block.note_block.bit");
        float pitch = (float) cfg.getDouble("beep.pitch", 1.9);
        int gap = Math.max(1, cfg.getInt("beep.gap-ticks", 3));
        sound(p, at, key, volume, pitch);
        later(p, gap, () -> sound(p, at, key, volume, pitch));
        later(p, gap * 2L, () -> sound(p, at, key, volume, pitch));
    }

    private void flash(Player p, String mm) {
        if (mm != null && !mm.isBlank()) HudModule.flash(p, Text.mm(mm), 2500);
    }

    private void later(Player p, long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) r.run();
        }, ticks);
    }

    private static boolean within(Player p, Location at, double r) {
        return at != null && at.getWorld() != null && at.getWorld().equals(p.getWorld()) && at.distanceSquared(p.getLocation()) <= r * r;
    }

    private long seconds(UUID u, String key, long def, String testKey, long testDef) {
        return Math.max(1, m.test(u) ? cfg.getLong(testKey, testDef) : cfg.getLong(key, def));
    }

    // ------------------------------------------------------------------------------------------
    // stage 0: the beeping in the rock
    // ------------------------------------------------------------------------------------------

    /** Natural rock a miner tunnels through, and the ores in it. */
    static boolean rock(Material t) {
        return t == Material.STONE || t == Material.DEEPSLATE || t == Material.TUFF || t == Material.ANDESITE || t == Material.DIORITE
                || t == Material.GRANITE || t == Material.CALCITE || t == Material.SMOOTH_BASALT || t.name().endsWith("_ORE");
    }

    /** Whether a player put this block there (as far as the 직업 module marks it: stone, deepslate, ores). */
    private static boolean placed(Block b) {
        int[] arr = b.getChunk().getPersistentDataContainer().get(PLACED, PersistentDataType.INTEGER_ARRAY);
        if (arr == null) return false;
        int k = (b.getX() & 15) | ((b.getZ() & 15) << 4) | ((b.getY() + 2048) << 8);
        for (int v : arr) if (v == k) return true;
        return false;
    }

    /** Rock all around: no face of it is open to air, water or a cave. */
    private static boolean buried(Block b) {
        World w = b.getWorld();
        for (BlockFace f : SIDES) {
            int x = b.getX() + f.getModX(), y = b.getY() + f.getModY(), z = b.getZ() + f.getModZ();
            if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) return false;
            if (!w.getBlockAt(x, y, z).getType().isOccluding()) return false;
        }
        return true;
    }

    /** A buried natural block 6 to 10 away from where they dig, about as deep; null when there is none. */
    private Block pick(Block from, boolean anyDepth) {
        World w = from.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int lo = Math.max(3, cfg.getInt("start.distance.min", 6)), hi = Math.max(lo, cfg.getInt("start.distance.max", 10));
        for (int i = 0; i < 60; i++) {
            double a = r.nextDouble(Math.PI * 2), d = lo + r.nextDouble() * (hi - lo);
            int x = from.getX() + (int) Math.round(Math.cos(a) * d), z = from.getZ() + (int) Math.round(Math.sin(a) * d);
            int y = from.getY() + r.nextInt(-2, 3);
            if (y <= w.getMinHeight() + 1 || y >= w.getMaxHeight() - 1 || (!anyDepth && y > maxY + 2)) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = w.getBlockAt(x, y, z);
            if (!rock(b.getType()) || !buried(b) || placed(b)) continue;
            return b;
        }
        return null;
    }

    private void beep(Player p, Block at, boolean preview, boolean anyDepth, long seconds) {
        Beep b = new Beep();
        b.anyDepth = anyDepth;
        b.w = at.getWorld();
        b.x = at.getX();
        b.y = at.getY();
        b.z = at.getZ();
        b.until = System.currentTimeMillis() + seconds * 1000L;
        b.preview = preview;
        beeps.put(p.getUniqueId(), b);
        chirp(p, b.center(), (float) cfg.getDouble("start.beep-volume", 1.5));
    }

    /**
     * Every block broken on the server comes through here, so: a map lookup (only while something beeps at all), the
     * height, the game mode, the block's kind and the roll. Nothing saved is read and nothing is made before the roll hits.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (!beeps.isEmpty()) {
            Beep beep = beeps.get(p.getUniqueId());
            if (beep != null) {   // while one beeps nothing else begins
                if (beep.touches(b)) pop(p, beep, b);
                return;
            }
        }
        if (b.getY() > maxY || p.getGameMode() != GameMode.SURVIVAL || !rock(b.getType())) return;
        if (ThreadLocalRandom.current().nextDouble() >= chance && (testers.isEmpty() || !testers.contains(p.getUniqueId()))) return;
        UUID u = p.getUniqueId();
        if (!on() || !open(u) || stage(u) != 0 || guide(p)) return;
        boolean test = m.test(u);
        if (!test && m.today(u).equals(kv(u, "day"))) return;   // once a day at most
        if (placed(b)) return;
        Block at = pick(b, false);
        if (at == null) return;
        kvSet(u, "day", m.today(u));
        beep(p, at, false, false, seconds(u, "start.beep-seconds", 180, "start.beep-seconds", 180));
    }

    /** Every third second: the triple beep from the block; it moves if the block is gone, and stops when its time is up. */
    private void beepTick(Player p, Beep b, long now) {
        UUID u = p.getUniqueId();
        if (now >= b.until || (!b.preview && stage(u) != 0)) {
            beeps.remove(u);
            return;
        }
        if (beats % 3 != 0 || !p.getWorld().equals(b.w) || !b.w.isChunkLoaded(b.x >> 4, b.z >> 4)) return;
        if (!rock(b.w.getBlockAt(b.x, b.y, b.z).getType())) {   // dug out by someone else, blown up, pushed away
            Block next = pick(p.getLocation().getBlock(), b.anyDepth);
            if (next == null) {
                beeps.remove(u);
                return;
            }
            b.x = next.getX();
            b.y = next.getY();
            b.z = next.getZ();
        }
        chirp(p, b.center(), (float) cfg.getDouble("start.beep-volume", 1.5));
    }

    /** They dug it out: it lies where the block they broke was (the beeping one, or one beside it). */
    private void pop(Player p, Beep b, Block broken) {
        UUID u = p.getUniqueId();
        beeps.remove(u);
        if (b.preview) {
            p.sendMessage(Text.mm("<yellow>[연출] 삐삐가 나올 자리 (아무것도 바뀌지 않음)"));
            return;
        }
        if (!on() || !open(u) || stage(u) != 0 || guide(p)) return;
        begin(p, b.center(), broken.getLocation().add(0.5, 0.3, 0.5));
    }

    /** The pager is theirs (stage 1), found at {@code at}: out of the rock at {@code drop}, or (null) straight into the pocket. */
    private void begin(Player p, Location at, Location drop) {
        UUID u = p.getUniqueId();
        forget(u);
        kvSet(u, "found", at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ());
        decide(p, at);
        m.stage(this, u, 1);
        device.take(p, true);
        if (drop != null) {
            device.pop(p, drop);
            chirp(p, drop, 1f);
        } else device.give(p);
    }

    /** Forgets how far they were (not that they are too late, nor the day it last beeped). */
    private void forget(UUID u) {
        kvRemove(u, "found", "stop", "stop-name", "tape", "anchor", "house", "fixed", "msgs", "clock", "ph", "at", "deadline", "left", "until",
                "idx", "dark", "refused", "force", "ending", "skip", "nodry");
        gates.remove(u);
        nextRush.remove(u);
        repicked.remove(u);
        noDry.remove(u);
        warnedWet.remove(u);
    }

    @Override
    public void start(Player p) {
        UUID u = p.getUniqueId();
        cleanup(u);
        device.take(p, true);
        forget(u);
        kvRemove(u, "late", "letter-at");
        m.stage(this, u, 0);
        Block at = pick(p.getLocation().getBlock(), true);
        if (at != null) beep(p, at, false, true, cfg.getLong("start.beep-seconds", 180));
        else begin(p, p.getLocation(), null);   // no rock around them: it is simply in their pocket
    }

    // ------------------------------------------------------------------------------------------
    // the stop and the house
    // ------------------------------------------------------------------------------------------

    private List<BusStop> stops() {
        BusModule b = eco.bus();
        List<BusStop> out = new ArrayList<>();
        if (b == null) return out;
        for (BusStop s : b.stops()) if (Bukkit.getWorld(s.world()) != null) out.add(s);
        out.sort(Comparator.comparingInt(BusStop::id));
        return out;
    }

    private String tape(BusStop s) {
        return text("pager.tape", "주우신 분은 {stop} 정류장 앞 파란 대문 집으로").replace("{stop}", Codes.chosung(Codes.stopWord(s.name())));
    }

    /** Where behind a stop the house is looked for: {x, z, nx, nz} ((nx, nz) points away from the road). */
    private double[] behind(BusStop s) {
        int width = eco.bus() == null ? 4 : eco.bus().spec().stops().bayWidth();
        double[] a = s.at(0, s.shelterOut(width) + 1.5);
        return new double[] {a[0], a[1], -s.hz() * s.side(), s.hx() * s.side()};
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /**
     * Chooses this player's stop (always the same one for them: among those far enough from where the pager lay) and
     * writes the tape. Stops with no dry ground around them (looked at once, "skip") are passed over: the next one is
     * chosen the same way among the rest. No stops at all (or none left): the main world's spawn, "마을 어귀". The house's
     * own spot is found when they come near.
     */
    private void decide(Player p, Location found) {
        UUID u = p.getUniqueId();
        Set<String> skip = skipped(u);
        List<BusStop> all = new ArrayList<>(), far = new ArrayList<>();
        for (BusStop s : stops()) if (!skip.contains(String.valueOf(s.id()))) all.add(s);
        double min = cfg.getDouble("house.min-from-found", 200);
        for (BusStop s : all)
            if (found == null || !s.world().equals(found.getWorld().getName()) || Math.hypot(s.x() - found.getX(), s.z() - found.getZ()) >= min) far.add(s);
        List<BusStop> pool = far.isEmpty() ? all : far;
        if (pool.isEmpty()) {
            Location sp = Bukkit.getWorlds().getFirst().getSpawnLocation();
            double a = Math.toRadians(Math.floorMod(u.getLeastSignificantBits(), 360L));
            kvSet(u, "anchor", sp.getWorld().getName() + " " + fmt(sp.getX()) + " " + sp.getBlockY() + " " + fmt(sp.getZ()) + " " + fmt(Math.cos(a)) + " " + fmt(Math.sin(a)));
            kvSet(u, "stop", "-");
            kvRemove(u, "stop-name");
            kvSet(u, "tape", text("pager.tape-no-stop", "주우신 분은 마을 어귀 파란 대문 집으로"));
        } else {
            BusStop s = pool.get(Codes.pick(u.getMostSignificantBits(), u.getLeastSignificantBits(), pool.size()));
            double[] a = behind(s);
            kvSet(u, "anchor", s.world() + " " + fmt(a[0]) + " " + (s.y() + 1) + " " + fmt(a[1]) + " " + fmt(a[2]) + " " + fmt(a[3]));
            kvSet(u, "stop", s.id());
            kvSet(u, "stop-name", s.name());
            kvSet(u, "tape", tape(s));
        }
        kvRemove(u, "house");
        gates.remove(u);
    }

    /** The stops passed over for this player: no dry ground anywhere around them. */
    private Set<String> skipped(UUID u) {
        String v = kv(u, "skip");
        Set<String> out = new HashSet<>();
        if (v != null) for (String x : v.split(",")) if (!x.isBlank()) out.add(x.trim());
        return out;
    }

    private Location found(UUID u) {
        return place(kv(u, "found"));
    }

    /** "world x y z [yaw]" */
    private static Location place(String v) {
        if (v == null) return null;
        String[] w = v.split(" ");
        World world = w.length < 4 ? null : Bukkit.getWorld(w[0]);
        if (world == null) return null;
        try {
            return new Location(world, Double.parseDouble(w[1]), Double.parseDouble(w[2]), Double.parseDouble(w[3]), w.length > 4 ? Float.parseFloat(w[4]) : 0, 0);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The middle of their gate, on the ground (yaw: the way the house faces); null until it was found. */
    private Location gate(UUID u) {
        Location g = gates.get(u);
        if (g != null) return g;
        Location at = place(kv(u, "house"));
        if (at == null) return null;
        at.add(0.5, 0, 0.5);
        gates.put(u, at);
        return at;
    }

    /** fixed: an admin put it there (it stays, whatever is built around it). */
    private void setGate(UUID u, Location at, boolean fixed) {
        kvSet(u, "house", at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ() + " " + Math.round(at.getYaw()));
        if (fixed) kvSet(u, "fixed", 1);
        else kvRemove(u, "fixed");
        gates.remove(u);
    }

    /**
     * Before the house was found: the stop on the tape must still be there under that name (else another is chosen and
     * the tape rewritten), and once they are near it (anywhere: however far they are) the house's spot is looked for and
     * kept. Nothing is loaded for it: while the land behind the stop is not loaded, it waits.
     */
    private void locate(Player p, boolean check, boolean anywhere) {
        UUID u = p.getUniqueId();
        String stop = kv(u, "stop"), anchor = kv(u, "anchor");
        if (anchor == null) check = true;
        if (check) {
            boolean again = anchor == null || place(anchor) == null;
            if (!again && stop != null && !stop.equals("-")) {
                BusStop s = stops().stream().filter(x -> String.valueOf(x.id()).equals(stop)).findFirst().orElse(null);
                Location a = place(anchor);
                again = s == null || skipped(u).contains(stop) || !tape(s).equals(kv(u, "tape")) || Math.hypot(behind(s)[0] - a.getX(), behind(s)[1] - a.getZ()) > 8;
            }
            if (again) {
                decide(p, found(u));
                device.refresh(p, null);
                anchor = kv(u, "anchor");
            }
        }
        String[] w = anchor == null ? new String[0] : anchor.split(" ");
        Location a = place(anchor);
        if (a == null || w.length < 6 || !a.getWorld().equals(p.getWorld())) return;
        if (!anywhere && Math.hypot(a.getX() - p.getLocation().getX(), a.getZ() - p.getLocation().getZ()) > cfg.getDouble("house.find-range", 64)) return;
        double nx, nz;
        try {
            nx = Double.parseDouble(w[4]);
            nz = Double.parseDouble(w[5]);
        } catch (NumberFormatException e) {
            return;
        }
        boolean spawn = stop == null || stop.equals("-");
        Long dryAt = noDry.get(u);
        if (spawn && dryAt != null && System.currentTimeMillis() - dryAt < 600_000L) return;   // nothing dry there ten minutes ago
        Location at = house.locate(a.getWorld(), a.getX(), a.getBlockY(), a.getZ(), nx, nz, u.getMostSignificantBits() ^ u.getLeastSignificantBits(), spawn);
        if (at == null) return;
        if (at != House.NOWHERE) {
            setGate(u, at, false);
            noDry.remove(u);
            kvRemove(u, "nodry");
            return;
        }
        // nowhere dry around it: never on the water. Another stop (the tape is written again), at last the spawn
        if (!spawn) {
            String skip = kv(u, "skip");
            kvSet(u, "skip", skip == null || skip.isBlank() ? stop : skip + "," + stop);
            eco.getLogger().info("[비밀] 삐삐: " + p.getName() + " 의 정류장 " + kv(u, "stop-name") + " 뒤에 마른 땅이 없어 다른 정류장으로 정함");
            decide(p, found(u));
            device.refresh(p, null);
            return;
        }
        noDry.put(u, System.currentTimeMillis());
        if (kv(u, "nodry") == null) eco.getLogger().warning("[비밀] 삐삐: " + p.getName() + " 의 집을 세울 마른 땅이 스폰 둘레(48칸)에도 없음. 10분마다 다시 찾음 (`연출 삐삐 집` 으로 직접 세울 수 있음)");
        kvSet(u, "nodry", 1);
    }

    /**
     * Their house's place was built over since it was chosen (the gate, the basket or the mailbox is inside a block), or
     * its ground is no longer dry: it is looked for again, at most once in ten minutes (if nowhere is better, it stands
     * where it stood). True when the house is not to be shown at the old place now.
     */
    private boolean repick(Player p, Location old) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = repicked.get(u);
        if (kv(u, "fixed") != null || (last != null && now - last < 600_000L)) return false;
        repicked.put(u, now);
        String anchor = kv(u, "anchor");
        kvRemove(u, "house");
        gates.remove(u);
        locate(p, true, true);
        if (gate(u) != null || !java.util.Objects.equals(anchor, kv(u, "anchor"))) return true;   // (another stop now: found when they get there)
        setGate(u, old.clone().subtract(0.5, 0, 0.5), false);   // the land behind the stop is not loaded now: as it was, until another time
        return false;
    }

    private House.Light light(UUID u, int st) {
        return st >= 5 || kv(u, "dark") != null ? House.Light.DARK : House.Light.LIT;
    }

    /** Each second: the house is there for them while they are near it. */
    private void houseTick(Player p, int st) {
        UUID u = p.getUniqueId();
        Location g = gate(u);
        if (g == null) {
            if (house.showing(u) && !house.ending(u)) stray(p);
            if (beats % 3 == 0) locate(p, beats % 30 == 0, false);
            return;
        }
        double show = cfg.getDouble("house.show-range", 48);
        if (house.showing(u) && !house.ending(u) && house.gone(u)) house.hide(u);   // its chunk was unloaded under it
        if (house.showing(u)) {
            if (house.ending(u)) return;
            Location at = house.where(u);
            boolean moved = at == null || !at.getWorld().equals(g.getWorld()) || at.distanceSquared(g) > 0.01;
            if (house.preview(u) || moved) {
                if (ready(p, g, show) && standsHere(p, g)) house.show(p, g, g.getYaw(), light(u, st), false);
                else stray(p);
            } else if (!within(p, g, show + 8)) house.hide(u);
            else house.light(u, light(u, st));
        } else if (ready(p, g, show) && standsHere(p, g)) {
            house.show(p, g, g.getYaw(), light(u, st), false);
        }
    }

    /**
     * Whether the house may be shown at its kept place now (the land there is loaded). No column of its ground may be
     * wet (else it is looked for again, and until then not shown: never over water); an admin's own place stays, with a
     * word on the console. Built over since: looked for again (or, at most once in ten minutes, it stays).
     */
    private boolean standsHere(Player p, Location g) {
        UUID u = p.getUniqueId();
        Dry.Check under = House.under(g, g.getYaw());
        String wet = under.wet() ? Dry.why(under) : null;
        if (wet != null) {
            if (kv(u, "fixed") != null) {
                if (warnedWet.add(u)) eco.getLogger().warning("[비밀] 삐삐: 관리자가 정한 " + p.getName() + " 의 집 자리(" + kv(u, "house")
                        + ")가 마른 땅이 아님: " + wet + ". 그대로 둠 (`연출 삐삐 집` 으로 옮길 것)");
                return true;
            }
            repick(p, g);
            return false;
        }
        return !(House.blocked(g, g.getYaw()) && repick(p, g));   // built over: it stands somewhere else from the next beat
    }

    /** Near enough to see the house, and the land it stands on is loaded (showing it loads nothing). */
    private static boolean ready(Player p, Location g, double show) {
        return within(p, g, show) && House.loaded(g.getWorld(), g.getX(), g.getZ(), 6);
    }

    /** A house left showing where it should not be (an admin's preview, or a stage that no longer has one): gone once they walk off. */
    private void stray(Player p) {
        UUID u = p.getUniqueId();
        if (!house.showing(u) || house.ending(u)) return;
        if (!house.preview(u) || !within(p, house.where(u), 56) || house.gone(u)) house.hide(u);
    }

    // ------------------------------------------------------------------------------------------
    // the screen
    // ------------------------------------------------------------------------------------------

    /** A message arrives: on the screen, a beep at them and the pager shaking in the pocket (quiet: one small blip). */
    private void push(Player p, Codes.Msg msg, boolean loud) {
        UUID u = p.getUniqueId();
        List<Codes.Msg> msgs = Codes.push(Codes.decode(kv(u, "msgs")), msg, Math.max(1, cfg.getInt("pager.screen.keep", 5)));
        kvSet(u, "msgs", Codes.encode(msgs));
        device.refresh(p, null);
        buzz(p, loud);
    }

    private void buzz(Player p, boolean loud) {
        if (loud) {
            chirp(p, p.getLocation(), 1f);
            flash(p, text("lines.vibrate", "<#9fd8a0>삐삐삐— 삐삐삐—"));
        } else sound(p, p.getLocation(), text("beep.sound", "block.note_block.bit"), 0.35f, (float) cfg.getDouble("beep.pitch", 1.9));
    }

    /** A message of that evening: the clock walks on a little (lo..hi minutes) each time. */
    private void message(Player p, String code, int lo, int hi) {
        UUID u = p.getUniqueId();
        long saved = m.getLong(this, u, "clock", -1);
        int clock = saved < 0 ? Codes.EVENING : Codes.advance((int) saved, ThreadLocalRandom.current().nextInt(lo, hi + 1));
        kvSet(u, "clock", clock);
        push(p, new Codes.Msg(Codes.stamp(clock), code), true);
    }

    private String code(String key, String def) {
        return cfg.getString("codes." + key, def);
    }

    private long gap(UUID u) {
        if (m.test(u)) return Math.max(1, cfg.getLong("test-gap-seconds", 8)) * 1000L;
        List<Integer> r = cfg.getIntegerList("gap-seconds");
        int a = r.isEmpty() ? 240 : r.getFirst(), b = r.size() > 1 ? r.get(1) : a;
        return ThreadLocalRandom.current().nextLong(Math.max(1, a), Math.max(a, b) + 1L) * 1000L;
    }

    private void waitFor(UUID u, long ms) {
        kvSet(u, "ph", "wait");
        kvSet(u, "at", System.currentTimeMillis() + ms);
    }

    private String ph(UUID u) {
        return m.get(this, u, "ph", "wait");
    }

    private long at(UUID u) {
        return m.getLong(this, u, "at", 0);
    }

    // ------------------------------------------------------------------------------------------
    // stages 1..4: arriving, the errands
    // ------------------------------------------------------------------------------------------

    private boolean atGate(Player p) {
        UUID u = p.getUniqueId();
        return house.showing(u) && !house.preview(u) && within(p, gate(u), cfg.getDouble("house.arrive-range", 6)) && device.has(p);
    }

    /** Stage 1: at the gate with the pager for the first time. The window flickers; the first message is on its way. */
    private void arrive(Player p) {
        if (!atGate(p)) return;
        UUID u = p.getUniqueId();
        house.flicker(p);
        m.stage(this, u, 2);
        waitFor(u, seconds(u, "house.first-seconds", 20, "house.test-first-seconds", 5) * 1000L);
    }

    /** Stages 2 and 3: the message, then (once the basket has what it asked for) her answer and the next stage. */
    private void errand(Player p, int st, long now) {
        UUID u = p.getUniqueId();
        switch (ph(u)) {
            case "wait" -> {
                if (now < at(u) || !device.has(p)) return;
                kvRemove(u, "force");
                message(p, st == 2 ? code("bread", "045") : code("fever", "10288"), 9, 40);
                kvSet(u, "ph", "ask");
            }
            case "reply" -> reply(p, st, now);
            default -> {}
        }
    }

    /** Her answer to a done errand (1004), and on to the next stage after a while. */
    private void reply(Player p, int st, long now) {
        UUID u = p.getUniqueId();
        if (now < at(u) || !device.has(p)) return;
        house.empty(u);
        message(p, code("thanks", "1004"), 2, 5);
        kvRemove(u, "dark", "deadline", "left");
        nextRush.remove(u);
        m.stage(this, u, st + 1);
        waitFor(u, gap(u));
    }

    private boolean wanted(ItemStack hand, String list) {
        if (hand == null || hand.isEmpty() || Items.trial(hand)) return false;
        String key = Items.key(hand);
        for (String id : cfg.getStringList(list)) if (Items.normalize(id).equals(key)) return true;
        return false;
    }

    /** Right-clicked the basket on the gate. */
    private void basket(Player p) {
        UUID u = p.getUniqueId();
        int st = stage(u);
        if (house.preview(u) || house.ending(u) || !playing(u) || guide(p)) {
            house.swing(p);
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if ((st == 2 || st == 3) && ph(u).equals("ask") && wanted(hand, st == 2 ? "errands.bread" : "errands.warm")) {
            // the outcome is saved before the item goes: one is taken, her answer is on its way
            kvSet(u, "ph", "reply");
            kvSet(u, "at", System.currentTimeMillis() + seconds(u, "reply-seconds", 6, "test-reply-seconds", 3) * 1000L);
            house.fill(p, hand);
            hand.setAmount(hand.getAmount() - 1);
            p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
            later(p, 12, () -> house.latch(p));
            return;
        }
        house.swing(p);
        if (st >= 5 && kv(u, "refused") == null) {   // said once: it wants nothing any more
            kvSet(u, "refused", 1);
            later(p, 15, () -> p.sendMessage(Text.mm(text("lines.refuse", "<dark_gray>…그게 아니야."))));
        }
    }

    /** Stage 4: 8282 comes while they are far away; they must stand at the gate before the time is up. */
    private void rush(Player p, long now) {
        UUID u = p.getUniqueId();
        boolean test = m.test(u);
        switch (ph(u)) {
            case "wait" -> {
                if (now < at(u) || !device.has(p)) return;
                Location g = gate(u);
                double min = test ? cfg.getDouble("rush.test-min-distance", 30) : cfg.getDouble("rush.min-distance", 300);
                double d = g == null || !g.getWorld().equals(p.getWorld()) ? Double.POSITIVE_INFINITY
                        : Math.hypot(g.getX() - p.getLocation().getX(), g.getZ() - p.getLocation().getZ());
                boolean force = kv(u, "force") != null;
                if (d < min && !force) return;
                kvRemove(u, "force", "dark", "left");
                int secs = Codes.rushSeconds((int) seconds(u, "rush.seconds", 240, "rush.test-seconds", 40), min, d, cfg.getInt("rush.max-seconds", 900));
                kvSet(u, "deadline", now + secs * 1000L);
                kvSet(u, "ph", "ask");
                nextRush.put(u, now + Codes.repeatGap(secs) * 1000L);
                message(p, code("hurry", "8282"), 9, 40);
            }
            case "ask" -> {
                long saved = m.getLong(this, u, "deadline", 0), left = m.getLong(this, u, "left", -1);
                if (saved <= 0 && left >= 0) {   // they were away (left the game, the server stopped): the time did not run, and it beeps again
                    saved = now + left;
                    kvSet(u, "deadline", saved);
                    kvRemove(u, "left");
                    nextRush.put(u, now);
                }
                long deadline = saved;
                if (atGate(p)) {   // in time
                    kvSet(u, "ph", "reply");
                    kvSet(u, "at", now + seconds(u, "reply-seconds", 6, "test-reply-seconds", 3) * 1000L);
                    nextRush.remove(u);
                    house.latch(p);
                } else if (now > deadline) {   // too late: the window is dark, and it comes again another time
                    kvSet(u, "dark", 1);
                    kvRemove(u, "deadline", "left");
                    nextRush.remove(u);
                    if (device.has(p)) {
                        List<Codes.Msg> msgs = Codes.push(Codes.decode(kv(u, "msgs")), new Codes.Msg("", code("late", "…")), Math.max(1, cfg.getInt("pager.screen.keep", 5)));
                        kvSet(u, "msgs", Codes.encode(msgs));
                        device.refresh(p, null);
                        sound(p, p.getLocation(), text("beep.sound", "block.note_block.bit"), 0.3f, 0.6f);
                    }
                    waitFor(u, gap(u));
                } else {
                    long next = nextRush.computeIfAbsent(u, x -> now + Codes.repeatGap((deadline - now) / 1000) * 1000L);
                    if (now < next || !device.has(p)) return;
                    nextRush.put(u, now + Codes.repeatGap((deadline - now) / 1000) * 1000L);
                    chirp(p, p.getLocation(), 1f);
                    flash(p, text("lines.again", "<#9fd8a0>삐삐삐— <white>{code}").replace("{code}", code("hurry", "8282")));
                }
            }
            case "reply" -> reply(p, 4, now);
            default -> {}
        }
    }

    // ------------------------------------------------------------------------------------------
    // stage 5: the turn
    // ------------------------------------------------------------------------------------------

    private static boolean night(World w) {
        long t = Math.floorMod(w.getTime(), 24000L);
        return w.getEnvironment() == World.Environment.NORMAL && t >= 13000 && t < 23000;
    }

    /** Where a knock comes from: under their feet; underground, from inside the nearest wall. */
    private static Location knockAt(Player p) {
        Location l = p.getLocation();
        World w = l.getWorld();
        if (w.getHighestBlockYAt(l) > l.getBlockY() + 2) {
            Block eye = p.getEyeLocation().getBlock();
            for (int d = 1; d <= 4; d++)
                for (BlockFace f : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
                    Block b = eye.getRelative(f, d);
                    if (b.getType().isOccluding()) return b.getRelative(f).getLocation().add(0.5, 0.5, 0.5);
                }
        }
        return l.subtract(0, 2.2, 0);
    }

    /** Three knocks. */
    private void knocks(Player p) {
        Location at = knockAt(p);
        String key = text("turn.knock-sound", "block.deepslate.hit");
        for (int i = 0; i < 3; i++) {
            float pitch = 0.5f + i * 0.03f;
            later(p, i * 7L, () -> {
                sound(p, at, key, 1f, pitch);
                sound(p, at, "block.wood.hit", 0.5f, 0.5f);
            });
        }
    }

    private void turn(Player p, long now) {
        UUID u = p.getUniqueId();
        switch (ph(u)) {
            case "wait" -> {
                if (now < at(u) || !device.has(p)) return;
                boolean force = kv(u, "force") != null || m.test(u);
                long max = Math.max(0, cfg.getLong("turn.max-wait-seconds", 900)) * 1000L;
                if (!force && !night(p.getWorld()) && now < at(u) + max) return;   // it comes at night (or, at last, anyway)
                kvRemove(u, "force");
                message(p, code("sos", "505"), 20, 60);
                kvSet(u, "ph", "knock");
                kvSet(u, "until", now + seconds(u, "turn.knock-seconds", 30, "turn.test-knock-seconds", 12) * 1000L);
            }
            case "knock" -> {
                if (now >= m.getLong(this, u, "until", 0)) {
                    kvSet(u, "ph", "scroll");
                    kvSet(u, "idx", 0);
                    kvSet(u, "at", now + 3000L);
                    kvRemove(u, "until");
                } else if (beats % 6 == 0 || beats % 6 == 2) knocks(p);
            }
            case "scroll" -> {
                if (now < at(u) || !device.has(p)) return;
                List<Codes.Msg> list = Codes.scroll(LocalDate.now(SecretModule.KST));
                int i = (int) m.getLong(this, u, "idx", 0);
                if (i < list.size()) {
                    push(p, list.get(i), i == 0 || i == list.size() - 1);
                    i++;
                    kvSet(u, "idx", i);
                    kvSet(u, "at", now + seconds(u, "turn.scroll-seconds", 3, "turn.test-scroll-seconds", 1) * 1000L);
                }
                if (i >= list.size()) {   // 20000. Nothing comes after this
                    m.stage(this, u, LAST);
                    kvRemove(u, "ph", "at", "idx");
                }
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------------------------------
    // stage 6: the mailbox, the end, the letter
    // ------------------------------------------------------------------------------------------

    /** Right-clicked the red mailbox. Only one thing goes in: the pager, once nothing more arrives. */
    private void mailbox(Player p) {
        UUID u = p.getUniqueId();
        if (house.preview(u) || house.ending(u) || stage(u) != LAST || !playing(u) || guide(p) || !device.holding(p) || kv(u, "ending") != null) {
            house.tap(p);
            return;
        }
        kvSet(u, "ending", System.currentTimeMillis());   // saved first: if they leave now, it ends when they are back
        device.take(p, true);
        house.ending(p, text("lines.dad", "<#e8dcc0>…아빠?"), () -> finish(p));
    }

    /** The house has answered: solved (unless someone was first in that half minute). */
    private void finish(Player p) {
        UUID u = p.getUniqueId();
        if (kv(u, "ending") == null) return;
        kvRemove(u, "ending", "ph", "at");
        m.solve(this, p);
        if (stage(u) > LAST) kvSet(u, "letter-at", System.currentTimeMillis() + seconds(u, "after.letter-seconds", 600, "after.test-letter-seconds", 15) * 1000L);
        else kvSet(u, "late", 1);
        // the house is gone with the next beat (stray / tooLate): where the gate was there is nothing
    }

    /** After it was solved: the letter is due. */
    private void after(Player p, long now) {
        UUID u = p.getUniqueId();
        long due = m.getLong(this, u, "letter-at", 0);
        if (due <= 0 || now < due || p.isDead() || guide(p)) return;
        kvRemove(u, "letter-at");
        kvSet(u, "letter-sent", now);
        kvSet(u, "perk", 1);
        ItemStack letter = letter(p);
        if (letter != null) eco.mail().deliver(u, letter, "pager");
        eco.mail().deliver(u, device.liveItem(p), "pager");
        sound(p, p.getLocation(), "item.book.page_turn", 0.7f, 0.8f);
        flash(p, text("lines.letter", "<dark_gray>…편지가 와 있다."));
    }

    /** The daughter's letter: a written book, dated today. */
    private ItemStack letter(Player p) {
        ItemStack it = Items.create("economy:pager_letter", 1).orElseGet(() -> new ItemStack(Material.WRITTEN_BOOK));
        if (!(it.getItemMeta() instanceof BookMeta bm)) return it;
        LocalDate today = LocalDate.now(SecretModule.KST);
        String date = today.getYear() + "년 " + today.getMonthValue() + "월 " + today.getDayOfMonth() + "일";
        String age = String.valueOf(today.getYear() - cfg.getInt("letter.born-year", 1991));
        List<Component> pages = new ArrayList<>();
        for (String page : cfg.getStringList("letter.pages")) pages.add(Text.mm(page.replace("{date}", date).replace("{age}", age).replace("{player}", p.getName())));
        String title = text("letter.title", "삐삐를 넣어 주신 분께");
        bm.setAuthor(text("letter.author", "파란 대문 집"));
        bm.setTitle(title.length() > 32 ? title.substring(0, 32) : title);
        bm.setGeneration(BookMeta.Generation.ORIGINAL);
        bm.pages(pages);
        List<String> lore = new ArrayList<>();
        for (String l : cfg.getStringList("letter.lore")) lore.add(l.replace("{date}", date));
        bm.lore(Text.itemLines(lore));
        it.setItemMeta(bm);
        return it;
    }

    /** Someone else was first: said once, and the pager's screen goes out for good. */
    private void tooLate(Player p) {
        UUID u = p.getUniqueId();
        if (kv(u, "late") != null) {
            if (beats % 5 == 0) device.take(p, true);
            stray(p);
            return;
        }
        kvSet(u, "late", 1);
        kvRemove(u, "ending");
        beeps.remove(u);
        house.hide(u);
        p.sendMessage(Text.mm(text("too-late", "<dark_gray>…파란 대문은 이제 어디에도 없다. 누군가 먼저 돌아갔다.")));
        if (device.take(p, true) > 0) p.sendMessage(Text.mm(text("lines.crumble", "<dark_gray>…삐삐의 화면이 꺼졌다. 손안에서 부스러진다.")));
    }

    /**
     * They were too late, and the secret is open again (서버초기화, or they are a tester now): they go on where they were,
     * and the pager that crumbled is back in the pocket.
     */
    private void reopen(Player p) {
        UUID u = p.getUniqueId();
        kvRemove(u, "late");
        without.remove(u);
        device.take(p, false);
        if (device.has(p)) {
            device.refresh(p, null);
            return;
        }
        device.give(p);
        flash(p, text("lines.back", "<dark_gray>…주머니에서 진동이 온다. 삐삐가 돌아와 있다."));
        sound(p, p.getLocation(), text("beep.sound", "block.note_block.bit"), 0.3f, 1.4f);
    }

    // ------------------------------------------------------------------------------------------
    // every second
    // ------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        beats++;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (m.test(u)) testers.add(u);
            else testers.remove(u);
            Beep b = beeps.get(u);
            if (b != null) beepTick(p, b, now);
            if (!knockPreview.isEmpty() || !scrollPreview.isEmpty()) previews(p, now);
            Long settle = settling.get(u);
            if (settle != null) {   // just joined: nothing arrives while their screen is still loading
                if (now < settle) continue;
                settling.remove(u);
            }
            int st = stage(u);
            if (st <= 0) {
                stray(p);
                continue;
            }
            if (st > LAST) {
                after(p, now);
                stray(p);
                continue;
            }
            if (!open(u)) {
                tooLate(p);
                continue;
            }
            if (guide(p)) continue;
            if (kv(u, "late") != null) {
                if (p.isDead()) continue;
                reopen(p);
            }
            if (kv(u, "ending") != null) {   // the end was playing when they left (or the server stopped): it is done
                if (!house.ending(u)) {
                    p.sendMessage(Text.mm(text("lines.dad", "<#e8dcc0>…아빠?")));
                    finish(p);
                }
                continue;
            }
            if (beats % 5 == 0) keep(p, now);
            houseTick(p, st);
            switch (st) {
                case 1 -> arrive(p);
                case 2, 3 -> errand(p, st, now);
                case 4 -> rush(p, now);
                case 5 -> turn(p, now);
                default -> {}
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // the pager stays with them
    // ------------------------------------------------------------------------------------------

    /** Dead copies and spares go; some time without the pager on them, and it is back in the pocket. */
    private void keep(Player p, long now) {
        UUID u = p.getUniqueId();
        if (p.isDead()) return;   // (what they keep through a death is handed back as they come to)
        device.take(p, false);
        if (device.has(p)) {
            without.remove(u);
            return;
        }
        long since = without.computeIfAbsent(u, x -> now);
        if (now - since < seconds(u, "pager.return-after", 120, "pager.test-return-after", 20) * 1000L) return;
        without.remove(u);
        device.give(p);
        flash(p, text("lines.back", "<dark_gray>…주머니에서 진동이 온다. 삐삐가 돌아와 있다."));
        sound(p, p.getLocation(), text("beep.sound", "block.note_block.bit"), 0.3f, 1.4f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (!device.own(e.getItemDrop().getItemStack(), u) || !playing(u)) return;
        e.setCancelled(true);
        flash(p, text("lines.drop", "<dark_gray>…내려놓을 수가 없다. 아직 울리고 있다."));
    }

    /** It never goes to anyone else, and a copy that stopped working is gone when its owner touches it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        ItemStack it = e.getItem().getItemStack();
        UUID owner = Device.ownerOf(it);
        if (owner == null) return;
        if (!(e.getEntity() instanceof Player p) || !p.getUniqueId().equals(owner)) {
            e.setCancelled(true);
            return;
        }
        if (device.dead(it, owner)) {
            e.setCancelled(true);
            e.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent e) {
        if (Device.ownerOf(e.getItem().getItemStack()) != null) e.setCancelled(true);
    }

    /** Dying does not lose it (nor the live one); shells and other people's pagers do not fall out either: they are gone. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        if (e.getKeepInventory()) return;
        UUID u = e.getPlayer().getUniqueId();
        boolean kept = false;
        for (var it = e.getDrops().iterator(); it.hasNext(); ) {
            ItemStack d = it.next();
            if (device.live(d, u)) {
                it.remove();
                e.getItemsToKeep().add(d);
                continue;
            }
            if (Device.ownerOf(d) == null) continue;
            it.remove();
            if (!kept && device.own(d, u) && playing(u)) {
                e.getItemsToKeep().add(d);
                kept = true;
            }
        }
    }

    /**
     * A shell (a copy that stopped working, or someone else's pager: out of a chest, a bundle, a trade) is gone the moment
     * it is touched in an inventory. Its owner's working pager is never touched by this.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventory(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        UUID u = p.getUniqueId();
        boolean gone = false;
        if (device.shell(e.getCurrentItem(), u)) {
            e.setCurrentItem(null);
            gone = true;
        }
        if (device.shell(e.getCursor(), u)) {
            p.setItemOnCursor(null);
            gone = true;
        }
        int swap = e.getClick() == ClickType.NUMBER_KEY ? e.getHotbarButton() : e.getClick() == ClickType.SWAP_OFFHAND ? 40 : -1;
        if (swap >= 0 && device.shell(p.getInventory().getItem(swap), u)) {
            p.getInventory().setItem(swap, null);
            gone = true;
        }
        if (gone) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        settling.put(p.getUniqueId(), System.currentTimeMillis() + 5000L);
        later(p, 60, () -> {
            UUID u = p.getUniqueId();
            if (!playing(u) || guide(p) || kv(u, "ending") != null || p.isDead()) return;
            device.take(p, false);
            if (device.has(p)) device.refresh(p, null);
            else device.give(p);
            if (gate(u) == null) locate(p, true, false);
        });
    }

    // ------------------------------------------------------------------------------------------
    // clicks
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof ItemFrame) {   // the pager is not hung on a wall (a shell would stay there)
            if (Device.ownerOf(e.getPlayer().getInventory().getItem(e.getHand())) != null) e.setCancelled(true);
            return;
        }
        if (!(e.getRightClicked() instanceof Interaction hit) || !hit.getPersistentDataContainer().has(House.TAG)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        House.Click c = house.click(e.getPlayer(), hit);
        if (c == House.Click.BASKET) basket(e.getPlayer());
        else if (c == House.Click.MAIL) mailbox(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Interaction hit && hit.getPersistentDataContainer().has(House.TAG)) e.setCancelled(true);
    }

    /** Whether this player's live pager works (the solver's, once the letter came). */
    boolean mayPage(Player p) {
        return on() && kv(p.getUniqueId(), "perk") != null;
    }

    @SuppressWarnings("deprecation") // isInteractable is approximate but has no replacement (the core's furniture uses it too)
    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        Player p = e.getPlayer();
        if (it == null || !device.live(it, p.getUniqueId()) || !paging.enabled() || !mayPage(p)) return;
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable() && !p.isSneaking()) return;
        paging.prompt(p);
    }

    /**
     * The chat thread: only the prompt's own map is read here (a concurrent one). While the prompt is open the line is
     * taken out of the chat (nobody sees it) and judged on the main thread.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        if (!paging.capturing(u)) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(eco, () -> {
            Player p = Bukkit.getPlayer(u);
            if (p != null) paging.typed(p, text);
        });
    }

    @Override
    public void cleanup(UUID u) {
        long deadline = m.getLong(this, u, "deadline", 0);
        if (deadline > 0) {   // an 8282 run is on: its time does not run while they are away
            kvSet(u, "left", Codes.pause(deadline, System.currentTimeMillis()));
            kvRemove(u, "deadline");
        }
        testers.remove(u);
        settling.remove(u);
        repicked.remove(u);
        noDry.remove(u);
        warnedWet.remove(u);
        beeps.remove(u);
        house.hide(u);
        without.remove(u);
        nextRush.remove(u);
        gates.remove(u);
        knockPreview.remove(u);
        scrollPreview.remove(u);
        paging.forget(u);
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    /** The screen as it stands when a stage begins (what came before it). */
    private List<Codes.Msg> screenAt(int stage, int[] clock, int keep) {
        List<Codes.Msg> msgs = new ArrayList<>();
        int c = Codes.EVENING;
        String thanks = code("thanks", "1004");
        String[][] done = {{code("bread", "045"), thanks}, {code("fever", "10288"), thanks}, {code("hurry", "8282"), thanks}};
        for (int i = 0; i < done.length && stage >= i + 3; i++) {
            if (i > 0) c = Codes.advance(c, 35);
            msgs = Codes.push(msgs, new Codes.Msg(Codes.stamp(c), done[i][0]), keep);
            c = Codes.advance(c, 4);
            msgs = Codes.push(msgs, new Codes.Msg(Codes.stamp(c), done[i][1]), keep);
        }
        if (stage >= LAST) {
            c = Codes.advance(c, 40);
            msgs = Codes.push(msgs, new Codes.Msg(Codes.stamp(c), code("sos", "505")), keep);
            for (Codes.Msg x : Codes.scroll(LocalDate.now(SecretModule.KST))) msgs = Codes.push(msgs, x, keep);
        }
        clock[0] = stage >= 3 ? c : -1;
        return msgs;
    }

    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        kvRemove(u, "late", "ending", "dark", "refused", "idx", "until", "deadline", "left", "ph", "at", "force", "letter-at");
        nextRush.remove(u);
        if (stage <= 0) {
            device.take(p, true);
            forget(u);
            m.stage(this, u, 0);
            return;
        }
        if (kv(u, "found") == null) {
            Location l = p.getLocation();
            kvSet(u, "found", l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
        }
        if (kv(u, "anchor") == null) decide(p, found(u));
        int[] clock = new int[1];
        kvSet(u, "msgs", Codes.encode(screenAt(stage, clock, Math.max(1, cfg.getInt("pager.screen.keep", 5)))));
        if (clock[0] < 0) kvRemove(u, "clock");
        else kvSet(u, "clock", clock[0]);
        m.stage(this, u, stage);
        if (stage >= 2 && stage <= 5) {   // its message comes in a moment, whatever the distance or the hour
            waitFor(u, 5000L);
            kvSet(u, "force", 1);
        }
        if (device.has(p)) device.refresh(p, null);
        else device.give(p);
        if (gate(u) == null) locate(p, true, false);
    }

    private static String ago(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 3600 ? s / 3600 + "시간 " + s % 3600 / 60 + "분" : s >= 60 ? s / 60 + "분 " + s % 60 + "초" : s + "초";
    }

    @Override
    public String status(UUID u) {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        int st = stage(u);
        Beep b = beeps.get(u);
        if (b != null) out.add((b.preview ? "[연출] " : "") + "삐삐 소리 " + b.x + " " + b.y + " " + b.z + " (남은 " + ago(b.until - now) + ")");
        if (st == 0) {
            String day = kv(u, "day");
            if (day != null) out.add("마지막으로 울린 날 " + day);
            if (kv(u, "late") != null) out.add("늦음 (누군가 먼저 풀었음)");
            return String.join(" · ", out);
        }
        String f = kv(u, "found");
        if (f != null) out.add("주운 곳 " + f);
        String stop = kv(u, "stop-name"), tape = kv(u, "tape");
        out.add("정류장 " + (stop == null ? "(없음: 스폰)" : stop) + (tape == null ? "" : " “" + tape + "”"));
        String h = kv(u, "house"), a = kv(u, "anchor");
        out.add(h != null ? "집 " + h + (house.showing(u) ? " (보이는 중)" : "") + (kv(u, "fixed") != null ? " (관리자가 정함)" : "")
                : kv(u, "nodry") != null ? "집 미정: 스폰 둘레에도 집을 세울 마른 땅이 없음 (10분마다 다시 찾음)"
                : "집 미정 (정류장 뒤 " + (a == null ? "?" : a.split(" ").length > 3 ? a.split(" ")[1] + " " + a.split(" ")[3] : a) + " 근처에 오면 정해짐)");
        Set<String> skip = skipped(u);
        if (!skip.isEmpty()) out.add("마른 땅이 없어 건너뛴 정류장 " + skip.size() + "곳");
        if (st >= 2 && st <= 5) {
            String ph = ph(u);
            long at = at(u);
            switch (ph) {
                case "wait" -> out.add(at > now ? "다음 호출까지 " + ago(at - now) : st == 4 ? "8282 대기: 집에서 " + (int) (m.test(u)
                        ? cfg.getDouble("rush.test-min-distance", 30) : cfg.getDouble("rush.min-distance", 300)) + "칸 이상 떨어지면 옴" : st == 5 ? "505 대기: 밤이 되면 옴" : "호출 대기 (삐삐를 지니면 옴)");
                case "ask" -> out.add(st == 2 ? "045: 바구니에 빵" : st == 3 ? "10288: 바구니에 따뜻한 것" : "8282: 대문까지 남은 "
                        + (m.getLong(this, u, "left", -1) >= 0 ? ago(m.getLong(this, u, "left", 0)) + " (자리에 없어 멈춤)" : ago(m.getLong(this, u, "deadline", 0) - now)));
                case "reply" -> out.add("답장(1004)까지 " + ago(at - now));
                case "knock" -> out.add("두드림 남은 " + ago(m.getLong(this, u, "until", 0) - now));
                case "scroll" -> out.add("100 이 지나가는 중 " + m.getLong(this, u, "idx", 0) + "/" + Codes.scroll(LocalDate.now(SecretModule.KST)).size());
                default -> {}
            }
        } else if (st == 1) out.add("삐삐를 지니고 집 앞에 가면 2단계");
        else if (st == LAST) out.add(kv(u, "ending") != null ? "엔딩 중" : "우체통에 삐삐를 넣으면 끝");
        if (kv(u, "late") != null) out.add("늦음 (누군가 먼저 풀었음)");
        long due = m.getLong(this, u, "letter-at", 0);
        if (due > 0) out.add("편지까지 " + ago(due - now));
        if (kv(u, "letter-sent") != null) out.add("편지 보냄");
        if (kv(u, "perk") != null) out.add("살아 있는 삐삐 (호출 가능)");
        return String.join(" · ", out);
    }

    @Override
    public List<String> scenes() {
        return List.of("삐삐", "집", "빵", "열", "빨리", "구조", "두드림", "백번", "엔딩", "편지", "살아있음");
    }

    /** A house front a few blocks ahead of them, facing them. */
    private Location ahead(Player p) {
        Location l = p.getLocation();
        org.bukkit.util.Vector d = l.getDirection().setY(0);
        if (d.lengthSquared() < 1e-4) d = new org.bukkit.util.Vector(0, 0, 1);
        d.normalize();
        float yaw = House.quarter(-d.getX(), -d.getZ());
        Location at = l.clone().add(d.multiply(6));
        int gy = House.ground(at.getWorld(), at.getBlockX(), at.getBlockZ(), l.getBlockY());
        return new Location(at.getWorld(), at.getBlockX() + 0.5, gy == Integer.MIN_VALUE ? l.getBlockY() : gy, at.getBlockZ() + 0.5, yaw, 0);
    }

    /** Why a house front cannot stand at {@link #ahead} (null: it can): its ground must be loaded and dry, every column. */
    private static String unfit(Location at) {
        if (!House.loaded(at.getWorld(), at.getX(), at.getZ(), 6)) return "그 자리 땅이 아직 불러와지지 않았어요";
        return House.dry(at, at.getYaw());
    }

    /** A message as a look only: on the screen of the pager they carry (not saved), the beep, the shaking. */
    private void preview(Player p, List<Codes.Msg> shown, boolean loud) {
        if (device.has(p)) device.refresh(p, shown);
        else flash(p, "<#9fd8a0>" + shown.getLast().text());
        buzz(p, loud);
    }

    private void previews(Player p, long now) {
        UUID u = p.getUniqueId();
        Long knock = knockPreview.get(u);
        if (knock != null) {
            if (now >= knock) knockPreview.remove(u);
            else if (beats % 6 == 0 || beats % 6 == 2) knocks(p);
        }
        List<Codes.Msg> sc = scrollPreview.get(u);
        if (sc != null) {
            List<Codes.Msg> list = Codes.scroll(LocalDate.now(SecretModule.KST));
            int i = sc.size();
            if (i >= list.size()) {
                scrollPreview.remove(u);
                later(p, 100, () -> device.refresh(p, null));
                return;
            }
            sc.add(list.get(i));
            int keep = Math.max(1, cfg.getInt("pager.screen.keep", 5));
            List<Codes.Msg> shown = new ArrayList<>(Codes.decode(kv(u, "msgs")));
            for (Codes.Msg x : sc) shown = Codes.push(shown, x, keep);
            preview(p, shown, i == 0 || i == list.size() - 1);
        }
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        String note = "";
        switch (word) {
            case "삐삐" -> {
                Block at = pick(p.getLocation().getBlock(), true);
                if (at == null) {
                    admin.sendMessage(Text.mm("<red>6~10칸 안에 묻힌 돌이 없어요 (땅속에서 해 보세요)"));
                    return true;
                }
                beep(p, at, true, true, 90);
                note = " (" + at.getX() + " " + at.getY() + " " + at.getZ() + ", 90초; 캐도 아무것도 안 바뀜)";
            }
            case "집" -> {
                Location at = ahead(p);
                String bad = unfit(at);
                if (bad != null) {
                    admin.sendMessage(Text.mm("<red>[삐삐] 여기에는 집을 세울 수 없어요: " + bad + " <gray>(보는 쪽 6칸 앞이 집 대문 자리)"));
                    return true;
                }
                int st = stage(u);
                if (st >= 1 && st <= LAST) {   // their own house, moved here for good
                    setGate(u, at.clone().subtract(0.5, 0, 0.5), true);
                    warnedWet.remove(u);
                    noDry.remove(u);
                    kvRemove(u, "nodry");
                    house.hide(u);
                    note = " (이 사람의 집을 여기로 옮김)";
                } else {
                    house.show(p, at, at.getYaw(), House.Light.LIT, true);
                    note = " (미리보기: 56칸 멀어지면 사라짐)";
                }
            }
            case "빵", "열", "빨리", "구조" -> {
                String code = switch (word) {
                    case "빵" -> code("bread", "045");
                    case "열" -> code("fever", "10288");
                    case "빨리" -> code("hurry", "8282");
                    default -> code("sos", "505");
                };
                int keep = Math.max(1, cfg.getInt("pager.screen.keep", 5));
                preview(p, Codes.push(Codes.decode(kv(u, "msgs")), new Codes.Msg(Codes.stamp(Codes.EVENING), code), keep), true);
                later(p, 300, () -> device.refresh(p, null));
                note = " " + code + " (화면은 15초 뒤 원래대로)";
            }
            case "두드림" -> knockPreview.put(u, now + cfg.getLong("turn.knock-seconds", 30) * 1000L);
            case "백번" -> scrollPreview.put(u, new ArrayList<>());
            case "엔딩" -> {
                if (house.ending(u)) return true;
                if (!house.showing(u)) {
                    Location at = ahead(p);
                    String bad = unfit(at);
                    if (bad != null) {
                        admin.sendMessage(Text.mm("<red>[삐삐] 여기에는 집을 세울 수 없어요: " + bad + " <gray>(보는 쪽 6칸 앞이 집 대문 자리)"));
                        return true;
                    }
                    house.show(p, at, at.getYaw(), House.Light.DARK, true);
                }
                house.ending(p, text("lines.dad", "<#e8dcc0>…아빠?"), () -> {
                    p.sendMessage(Text.mm("<yellow>[연출] 엔딩 끝 (아무것도 바뀌지 않음)"));
                    later(p, 60, () -> {
                        if (kv(u, "ending") == null) house.hide(u);
                    });
                });
            }
            case "편지" -> {
                ItemStack letter = letter(p);
                if (letter != null) eco.mail().deliver(u, letter, "pager");
            }
            case "살아있음" -> {
                kvSet(u, "perk", 1);
                eco.mail().deliver(u, device.liveItem(p), "pager");
                note = " (호출 가능: 우클릭)";
            }
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] " + word + " → " + p.getName() + note));
        return true;
    }
}
