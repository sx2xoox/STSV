package dev.stsv.economy.secret.lostkey;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.bus.BusLine;
import dev.stsv.economy.bus.BusModule;
import dev.stsv.economy.bus.BusStop;
import dev.stsv.economy.secret.Memory;
import dev.stsv.economy.secret.Secret;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 분실물센터 (docs/admin/숨은_비밀/분실물센터.md; contents/economy/secrets/lostkey.yml). The lost and found has one thing
 * nobody ever came for: a key whose owner's name was scratched off. Its owner is the driver — the one they were at first.
 * <p>
 * A driver who gave back many lost items may, very rarely, be left this key instead of a lost item (stage 1). It looks
 * like one, but its stop is "???". Carried on the bus it rattles at exactly the stops a clue points at ({@link Clues}),
 * and there the waiting passengers whisper the next clue and a little more of whose key it is (stages 1..5): 첫차 → a bus
 * driver → their name's initial → nobody came for them → the same seat every day → "지금도 운전 중이야. 집에 가 봐."
 * While it is carried, now and then: the stop bell rings on an empty bus, a nameless passenger is on the list, a seat is
 * taken for a moment. At home a locker waits behind them (stage 6, {@link Locker}); the key opens it: the paper of their
 * own first records on this server ({@link Paper}), the locker fades, solved. The key keeps its owner's name for good and
 * the 분실물 센터 where it rattled 4th bears a gold plate; on the solver's own bus a small key charm hangs at the mirror
 * ({@link Charm}). The paper also names the first lost item they ever gave back, and to whom (kept from the bus's
 * {@link BusModule.Watcher#gaveBack} as it happens: "secret.lostkey.first-back.&lt;uuid&gt;").
 * <p>
 * Saved (eco.kv "secret.lostkey.&lt;uuid&gt;."): stage, since, given (the key was left them once), found (the stop it was
 * left at), clue (the clue being looked for: {@link Clues.Clue#encode}), heard (the last whisper, for the key's lore),
 * center (the stop it rattled at 4th), home ("world x z"). The plate: "secret.lostkey.plaque" ("stopId name").
 * The bus calls in through {@link BusModule.Watcher}.
 */
public final class LostKey extends Secret implements BusModule.Watcher {

    /** On the key: whose it is (it works only for them). */
    static final NamespacedKey OWNER = new NamespacedKey("economy", "lostkey_owner");
    /** On the key once its owner was found (its name is back). */
    static final NamespacedKey DONE = new NamespacedKey("economy", "lostkey_done");
    /** On the key: which copy it is (eco.kv "key"); only the latest one given works, so a copy left in a chest or dropped
     *  on death goes dead once the pocket gets its key back (no second key). */
    static final NamespacedKey SERIAL = new NamespacedKey("economy", "lostkey_serial");
    static final String ITEM = "economy:lost_key_nameless";
    private static final String PLAQUE = "secret.lostkey.plaque";
    /** eco.kv: the first lost item a driver gave back (outside the per-player keys, so a reset keeps it) */
    static final String FIRST_BACK = "secret.lostkey.first-back.";
    private static final int HOME = 6;

    /** 후속 임무 「주인 찾기」 */
    private final LostKeySequel sequel = new LostKeySequel(this);
    private final Locker locker;
    private final Charm charm;
    private BusModule watched;
    /** player -> {stop id, since (ms)}: standing at a line's end for the 4th clue */
    private final Map<UUID, long[]> standing = new HashMap<>();
    /** player -> when the next omen comes; until when the nameless passenger is on their list */
    private final Map<UUID, Long> omenAt = new HashMap<>(), ghostUntil = new HashMap<>();
    /** player -> since when they have not had the key on them */
    private final Map<UUID, Long> without = new HashMap<>();
    private long beats;

    public LostKey(SecretModule m) {
        super(m);
        locker = new Locker(this, m, eco);
        charm = new Charm(this);
        BusModule b = eco.bus();
        if (b != null) {   // at once, so the first lost item given back is kept even while this secret is off
            b.watch(id(), this);
            watched = b;
        }
    }

    @Override
    public String id() {
        return "lostkey";
    }

    @Override
    public String name() {
        return "분실물센터";
    }

    @Override
    public Sequel sequel() {
        return sequel;
    }

    /** 1..5: looking for the stop the n-th clue points at; 6: the locker at home. */
    @Override
    public int stages() {
        return HOME;
    }

    org.bukkit.configuration.file.YamlConfiguration cfg() {
        return cfg;
    }

    String text(String key) {
        return cfg.getString(key, "");
    }

    private int stage(UUID u) {
        return m.stage(this, u);
    }

    private BusModule bus() {
        return eco.bus();
    }

    BusModule busModule() {
        return bus();
    }

    dev.stsv.core.STSVPlugin core() {
        return m.core();
    }

    org.bukkit.plugin.Plugin plugin() {
        return eco;
    }

    // ------------------------------------------------------------------------------------------
    // the key
    // ------------------------------------------------------------------------------------------

    private boolean ownKey(ItemStack it, UUID u) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return false;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        if (!u.toString().equals(pdc.get(OWNER, PersistentDataType.STRING)) || pdc.has(DONE)) return false;
        String want = m.get(this, u, "key");
        return want == null || want.equals(pdc.get(SERIAL, PersistentDataType.STRING));
    }

    /** Whether they have their (nameless) key on them. */
    boolean hasKey(Player p) {
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : inv.getContents()) if (ownKey(it, p.getUniqueId())) return true;
        return ownKey(p.getItemOnCursor(), p.getUniqueId());
    }

    boolean holdingKey(Player p) {
        return ownKey(p.getInventory().getItemInMainHand(), p.getUniqueId()) || ownKey(p.getInventory().getItemInOffHand(), p.getUniqueId());
    }

    private ItemStack newKey(Player p) {
        String serial = Long.toHexString(System.currentTimeMillis()) + Long.toHexString(ThreadLocalRandom.current().nextLong());
        m.set(this, p.getUniqueId(), "key", serial);   // the copies given before stop working
        ItemStack it = Items.create(ITEM, 1).orElseGet(() -> new ItemStack(Material.TRIPWIRE_HOOK));
        it.editMeta(meta -> {
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(OWNER, PersistentDataType.STRING, p.getUniqueId().toString());
            meta.getPersistentDataContainer().set(SERIAL, PersistentDataType.STRING, serial);
        });
        Items.markBought(it);
        dress(it, p);
        return it;
    }

    /** The key's lore as it is now (where it was found, the last thing heard). */
    private void dress(ItemStack it, Player p) {
        UUID u = p.getUniqueId();
        Map<String, String> v = new HashMap<>();
        v.put("found", stopName(m.get(this, u, "found")));
        String heard = m.get(this, u, "heard");
        v.put("heard", heard == null || heard.isBlank() ? null : heard);
        List<Component> lore = new ArrayList<>();
        for (String line : Paper.fill(cfg.getStringList("key.lore"), v)) lore.add(Text.item(line));
        it.editMeta(meta -> meta.lore(lore));
    }

    private void giveKey(Player p) {
        for (ItemStack left : p.getInventory().addItem(newKey(p)).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    /** The keys they carry, written again (after a stage changed). */
    private void refreshKeys(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] all = inv.getContents();
        for (int i = 0; i < all.length; i++) {
            if (!ownKey(all[i], p.getUniqueId())) continue;
            dress(all[i], p);
            inv.setItem(i, all[i]);
        }
    }

    /** Its owner found: the key keeps their name for good (a keepsake, not for sale). */
    private void nameKeys(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] all = inv.getContents();
        boolean any = false;
        for (int i = 0; i < all.length; i++) {
            if (!ownKey(all[i], p.getUniqueId())) continue;
            if (any) {   // one keepsake; a spare key the pocket gave back goes
                inv.setItem(i, null);
                continue;
            }
            any = true;
            ItemStack it = all[i];
            it.editMeta(meta -> {
                meta.itemName(Text.item(text("key.done-name").replace("{player}", p.getName())));
                List<Component> lore = new ArrayList<>();
                for (String l : cfg.getStringList("key.done-lore"))
                    lore.add(Text.item(l.replace("{date}", Paper.date(System.currentTimeMillis())).replace("{player}", p.getName())));
                meta.lore(lore);
                meta.setEnchantmentGlintOverride(true);
                meta.getPersistentDataContainer().set(DONE, PersistentDataType.BYTE, (byte) 1);
            });
            inv.setItem(i, it);
        }
    }

    // ------------------------------------------------------------------------------------------
    // the network as the clues see it
    // ------------------------------------------------------------------------------------------

    List<Clues.Stop> net() {
        BusModule b = bus();
        if (b == null) return List.of();
        Map<String, List<BusLine>> lines = new HashMap<>();
        List<Clues.Stop> out = new ArrayList<>();
        for (BusStop s : b.stops()) out.add(stopOf(b, s, lines.computeIfAbsent(s.world(), b::linesIn)));
        return out;
    }

    private static Clues.Stop stopOf(BusModule b, BusStop s, List<BusLine> lines) {
        boolean first = false, last = false;
        for (BusLine l : lines) {
            List<Integer> st = l.stops();
            if (st.isEmpty()) continue;
            if (st.getFirst() == s.id()) first = true;
            if (st.getLast() == s.id()) last = true;
        }
        return new Clues.Stop(s.id(), s.name(), b.stopTags(s), first, last);
    }

    BusStop stop(int id) {
        BusModule b = bus();
        if (b == null) return null;
        for (BusStop s : b.stops()) if (s.id() == id) return s;
        return null;
    }

    private String stopName(String id) {
        if (id == null) return null;
        try {
            BusStop s = stop(Integer.parseInt(id));
            return s == null ? "?" : s.name();
        } catch (NumberFormatException e) {
            return "?";
        }
    }

    private Integer found(UUID u) {
        String f = m.get(this, u, "found");
        try {
            return f == null ? null : Integer.valueOf(f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    List<String> tagOrder() {
        ConfigurationSection t = cfg.getConfigurationSection("tags");
        return t == null ? List.of("sea", "mountain", "forest", "field", "village", "port") : new ArrayList<>(t.getKeys(false));
    }

    /** The clue for stage n worked out now and saved (what the whispers are about to tell). */
    private Clues.Clue decide(Player p, int stage) {
        Clues.Clue c = Clues.pick(stage, net(), p.getUniqueId(), p.getName(), found(p.getUniqueId()), tagOrder());
        m.set(this, p.getUniqueId(), "clue", c.encode());
        return c;
    }

    /** The clue being looked for (saved; worked out when missing). */
    private Clues.Clue clue(Player p) {
        Clues.Clue c = Clues.Clue.decode(m.get(this, p.getUniqueId(), "clue"));
        int st = stage(p.getUniqueId());
        return c != null ? c : st >= 1 && st <= 5 ? decide(p, st) : null;
    }

    /** The whispered line that says where a clue points. */
    private String clueLine(Clues.Clue c) {
        return switch (c.kind()) {
            case TAG -> cfg.getString("tags." + c.word(), text("plain"));
            case PLAIN -> text("plain");
            case FOUND -> text("found");
            case OLDEST -> text("oldest");
            case ANY -> text("any");
            default -> "";
        };
    }

    /**
     * What is whispered after the n-th rattle (0: as the key is left): the set's lines with the next clue filled in; when
     * nothing fits that clue any more (ANY) its own lines go and "anywhere" is said instead.
     */
    private List<String> whispers(int n, Clues.Clue next, Player p) {
        List<String> lines = new ArrayList<>(cfg.getStringList("whispers." + n));
        if (next != null && next.kind() == Clues.Kind.ANY) {
            lines.removeIf(l -> l.contains("{jamo}") || l.contains("{clue}"));
            lines.add(text("any"));
        }
        String jamo = next != null && next.kind() == Clues.Kind.INITIAL ? next.word() : "";
        String clue = next == null ? "" : clueLine(next);
        List<String> out = new ArrayList<>();
        for (String l : lines)
            out.add(l.replace("{clue}", clue).replace("{jamo}", jamo).replace("{ro}", jamo.isEmpty() ? "" : Clues.ro(jamo)).replace("{player}", p.getName()));
        return out;
    }

    /** The line of a whisper set worth keeping on the key: the one that tells the clue, else the last. */
    private static String telling(List<String> raw, List<String> filled) {
        for (int i = 0; i < raw.size() && i < filled.size(); i++) if (raw.get(i).contains("{clue}")) return filled.get(i);
        for (int i = Math.min(raw.size(), filled.size()) - 1; i >= 0; i--) if (raw.get(i).contains("{jamo}")) return filled.get(i);
        return filled.isEmpty() ? "" : filled.getLast();
    }

    private void heard(Player p, int n, List<String> filled) {
        List<String> raw = cfg.getStringList("whispers." + n);
        m.set(this, p.getUniqueId(), "heard", Text.plain(telling(raw, filled)));
        refreshKeys(p);
    }

    // ------------------------------------------------------------------------------------------
    // start: the key instead of a lost item
    // ------------------------------------------------------------------------------------------

    /** BusModule: a passenger is leaving a lost item on this driver's bus. Very rarely, it is the key. */
    @Override
    public boolean leftBehind(Player driver, Entity passenger, String passengerTag, BusStop gotOn, BusStop here) {
        UUID u = driver.getUniqueId();
        if (!on() || !open(u) || stage(u) != 0) return false;
        boolean test = m.test(u);
        if (!test && m.get(this, u, "given") != null) return false;   // once a player, ever (until 초기화)
        if (!test && bus().lostReturned(u) < cfg.getLong("start.returns", 30)) return false;
        if (!m.roll(u, cfg.getDouble("start.chance", 0.002))) return false;
        begin(driver, passenger, passengerTag, here);
        return true;
    }

    /** The key is theirs now (stage 1): the lost-item message with ??? for its stop, and the passenger's whisper. */
    private void begin(Player p, Entity passenger, String passengerTag, BusStop here) {
        UUID u = p.getUniqueId();
        m.set(this, u, "given", System.currentTimeMillis());
        if (here != null) m.set(this, u, "found", here.id());
        else m.remove(this, u, "found");
        m.stage(this, u, 1);
        Clues.Clue c = decide(p, 1);
        List<String> lines = whispers(0, c, p);
        m.set(this, u, "heard", Text.plain(telling(cfg.getStringList("whispers.0"), lines)));
        PlayerInventory inv = p.getInventory();   // a key left over from before a reset: only one at a time
        ItemStack[] all = inv.getContents();
        for (int i = 0; i < all.length; i++) if (ownKey(all[i], u)) inv.setItem(i, null);
        giveKey(p);
        p.sendMessage(Text.mm(text("lines.got").replace("{type}", passengerTag == null ? "<gray>손님" : passengerTag)));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.9f);
        later(p, 60, () -> m.haunt().sound(p, p.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.3f, 0.6f));
        speak(p, passenger == null ? List.of() : List.of(passenger), here, lines, 25);
        later(p, 25 + lines.size() * 50L + 40, () -> p.sendMessage(Text.mm(text("lines.got-later"))));
    }

    @Override
    public void start(Player p) {
        cleanup(p.getUniqueId());
        BusStop near = nearestStop(p.getLocation());
        begin(p, null, null, near);
    }

    @Override
    public void jump(Player p, int stage) {
        UUID u = p.getUniqueId();
        if (stage <= 0) {
            m.stage(this, u, 0);
            return;
        }
        m.set(this, u, "given", System.currentTimeMillis());
        if (found(u) == null) {
            BusStop near = nearestStop(p.getLocation());
            if (near != null) m.set(this, u, "found", near.id());
        }
        m.stage(this, u, stage);
        if (stage >= 4 && m.get(this, u, "center") == null) {
            BusStop c = nearestEnd(p.getLocation());
            if (c != null) m.set(this, u, "center", c.id());
        }
        if (stage <= 5) {
            Clues.Clue c = decide(p, stage);
            List<String> lines = whispers(stage - 1, c, p);
            m.set(this, u, "heard", Text.plain(telling(cfg.getStringList("whispers." + (stage - 1)), lines)));
        } else {
            m.set(this, u, "heard", Text.plain(text("lines.home")));
            findHome(p, () -> m.haunt().whisper(p, text("lines.home")));
        }
        if (!hasKey(p)) giveKey(p);
        else refreshKeys(p);
    }

    BusStop nearestStop(Location at) {
        BusModule b = bus();
        if (b == null) return null;
        BusStop best = null;
        double bd = Double.MAX_VALUE;
        for (BusStop s : b.stops()) {
            if (!s.world().equals(at.getWorld().getName())) continue;
            double d = Math.hypot(s.x() - at.getX(), s.z() - at.getZ());
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /** The nearest 분실물 센터 (a line's end), else the nearest stop. */
    private BusStop nearestEnd(Location at) {
        BusModule b = bus();
        if (b == null) return null;
        List<Clues.Stop> net = net();
        BusStop best = null;
        double bd = Double.MAX_VALUE;
        for (BusStop s : b.stops()) {
            if (!s.world().equals(at.getWorld().getName())) continue;
            boolean end = net.stream().anyMatch(x -> x.id() == s.id() && (x.first() || x.last()));
            double d = Math.hypot(s.x() - at.getX(), s.z() - at.getZ());
            if (end && d < bd) {
                bd = d;
                best = s;
            }
        }
        return best != null ? best : nearestStop(at);
    }

    // ------------------------------------------------------------------------------------------
    // the stops: rattling and whispers
    // ------------------------------------------------------------------------------------------

    /** BusModule: their bus pulled into a stop's bay. */
    @Override
    public void stopped(Player p, BusStop here) {
        sequel.stopped(p, here);   // 후속 임무 「주인 찾기」 (only for the one who solved it)
        UUID u = p.getUniqueId();
        int st = stage(u);
        if (!on() || !open(u) || st < 1 || st > 5 || !hasKey(p)) return;
        Clues.Clue c = clue(p);
        if (c == null) return;
        List<Clues.Stop> net = net();
        Clues.Stop s = net.stream().filter(x -> x.id() == here.id()).findFirst().orElse(null);
        int oldest = net.stream().mapToInt(Clues.Stop::id).min().orElse(-1);
        if (s == null || !Clues.matches(c, s, oldest)) return;
        if (st == 4) {   // "종점에서 한참 서 있었대": only after standing there a while
            standing.put(u, new long[] {here.id(), System.currentTimeMillis()});
            return;
        }
        rattle(p, here, st);
    }

    /** The key rattles at the stop of stage st: on to the next stage, the passengers there whisper. */
    private void rattle(Player p, BusStop here, int st) {
        UUID u = p.getUniqueId();
        standing.remove(u);
        if (st == 4 && here != null) m.set(this, u, "center", here.id());
        m.stage(this, u, st + 1);
        Clues.Clue next = st < 5 ? decide(p, st + 1) : null;
        if (st >= 5) m.remove(this, u, "clue");
        rattleSound(p);
        List<String> lines = whispers(st, next, p);
        heard(p, st, lines);
        speak(p, List.of(), here, lines, 30);
        if (st >= 5) findHome(p, null);
    }

    private void rattleSound(Player p) {
        Location l = p.getLocation();
        m.haunt().sound(p, l, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 1.4f);
        m.haunt().sound(p, l, Sound.BLOCK_NOTE_BLOCK_BELL, 0.25f, 2f);
        later(p, 4, () -> m.haunt().sound(p, p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.18f, 1.8f));
        m.haunt().whisper(p, text("lines.rattle"));
    }

    /**
     * Whispers only this player hears, one every 2.5 s: as dark bubbles over the given speakers, else over the passengers
     * waiting at the stop, else floating at the stop itself; each also as a dim chat line (to read again).
     */
    void speak(Player p, List<Entity> speakers, BusStop here, List<String> lines, long delay) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int n = i;
            later(p, delay + i * 50L, () -> {
                List<Entity> who = new ArrayList<>();
                for (Entity e : speakers) if (e.isValid()) who.add(e);
                if (who.isEmpty() && here != null && bus() != null) who.addAll(bus().waitingAt(here.id()));
                Entity e = who.isEmpty() ? null : who.get(n % who.size());
                bubble(p, e, here, line);
                p.sendMessage(Text.mm("<dark_gray><i>" + line));
                m.haunt().sound(p, e != null ? e.getLocation() : p.getLocation(), Sound.ENTITY_VEX_AMBIENT, 0.15f, 0.5f);
            });
        }
    }

    private void bubble(Player p, Entity over, BusStop here, String line) {
        Location at;
        if (over != null) at = over.getLocation().add(0, 2.3, 0);
        else if (here != null && p.getWorld().getName().equals(here.world())) at = new Location(p.getWorld(), here.x(), here.y() + 2.6, here.z());
        else at = p.getLocation().add(p.getLocation().getDirection().setY(0).multiply(3)).add(0, 1.8, 0);
        TextDisplay d = m.haunt().text(p, at, "<#c8c8d0><i>" + line);
        d.setBackgroundColor(Color.fromARGB(170, 16, 16, 22));
        d.setShadowed(false);
        d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, 0.55f, 0), new org.joml.AxisAngle4f(),
                new org.joml.Vector3f(0.7f, 0.7f, 0.7f), new org.joml.AxisAngle4f()));
        if (over != null && !over.addPassenger(d)) d.teleport(at);
        later(p, 70, () -> m.haunt().remove(p, d));
    }

    // ------------------------------------------------------------------------------------------
    // while it is carried: omens
    // ------------------------------------------------------------------------------------------

    private long nextOmen(UUID u, long now) {
        List<Integer> r = cfg.getIntegerList(m.test(u) ? "dread.test-every" : "dread.every");
        int a = r.isEmpty() ? 480 : r.getFirst(), b = r.size() > 1 ? r.get(1) : a * 2;
        return now + ThreadLocalRandom.current().nextLong(Math.max(5, a), Math.max(a, b) + 1L) * 1000L;
    }

    private void omens(Player p, long now) {
        UUID u = p.getUniqueId();
        BusModule b = bus();
        int riders = b == null ? -1 : b.ridersOf(p);
        if (riders < 0) return;
        long at = omenAt.computeIfAbsent(u, x -> nextOmen(u, now));
        if (now < at) return;
        omenAt.put(u, nextOmen(u, now));
        List<String> can = new ArrayList<>(List.of("빈손님"));
        if (riders == 0) can.add("벨");
        if (b.stoppedAt(p) != null) can.add("자리");
        omen(p, can.get(ThreadLocalRandom.current().nextInt(can.size())));
    }

    private void omen(Player p, String which) {
        switch (which) {
            case "벨" -> bell(p);
            case "자리" -> seat(p);
            default -> ghost(p);
        }
    }

    /** The stop bell on an empty bus: 띵동, "하차 0명" for a stop called ???. */
    private void bell(Player p) {
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
        later(p, 4, () -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.95f));
        HudModule.flash(p, Lang.mm("bus.bell", "stop", "???", "n", 0), 3000);
    }

    /** Someone sat down behind: for a while the /버스 list has a passenger with no name. */
    private void ghost(Player p) {
        ghostUntil.put(p.getUniqueId(), System.currentTimeMillis() + cfg.getLong("dread.ghost-window", 40) * 1000L);
        m.haunt().behind(p, Sound.BLOCK_WOOD_STEP, 0.5f, 0.6f);
        later(p, 6, () -> m.haunt().behind(p, Sound.BLOCK_WOOD_STEP, 0.4f, 0.55f));
        later(p, 30, () -> m.haunt().whisper(p, text("lines.ghost-hint")));
    }

    /** A seat behind the driver holds a faint figure for a moment, then nobody. */
    private void seat(Player p) {
        Location me = p.getLocation();
        org.bukkit.util.Vector back = me.getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) return;
        Location at = me.clone().subtract(back.normalize().multiply(1.8));
        at.setYaw(me.getYaw());
        at.setPitch(25);
        Mannequin f = m.haunt().figure(p, at, null, null);
        f.setDescription(null);
        p.spawnParticle(Particle.WHITE_ASH, at.clone().add(0, 1, 0), 12, 0.25, 0.5, 0.25, 0);
        later(p, 14, () -> {
            m.haunt().remove(p, f);
            p.spawnParticle(Particle.WHITE_ASH, at.clone().add(0, 1, 0), 8, 0.25, 0.5, 0.25, 0);
            m.haunt().sound(p, at, Sound.BLOCK_WOOL_STEP, 0.4f, 0.5f);
        });
        later(p, 34, () -> m.haunt().whisper(p, text("lines.seat-hint")));
    }

    /** BusModule: the /버스 list. Now and then — always just after someone sat down behind — a passenger with no name. */
    @Override
    public List<Component> riderRows(Player p, int n) {
        UUID u = p.getUniqueId();
        int st = stage(u);
        if (!on() || st < 1 || st > 5 || !hasKey(p)) return List.of();
        Long until = ghostUntil.get(u);
        boolean now = until != null && until > System.currentTimeMillis();
        if (!now && ThreadLocalRandom.current().nextDouble() >= cfg.getDouble("dread.ghost-row", 0.06)) return List.of();
        return List.of(ghostRow(n));
    }

    private static Component ghostRow(int n) {
        return Lang.mm("bus.list-row", "i", n + 1, "type", "<dark_gray>???", "dest", "???", "km", "?", "left", "??:??", "mood", "?");
    }

    // ------------------------------------------------------------------------------------------
    // home: the locker
    // ------------------------------------------------------------------------------------------

    /** Where home is (first land, else the bed or respawn point, else where they first came in, else spawn), saved. */
    private void findHome(Player p, Runnable then) {
        UUID u = p.getUniqueId();
        m.memory().firstLand(u, land -> {
            Location at = land.flatMap(Memory.Place::location).orElse(null);
            if (at == null && p.isOnline()) at = p.getRespawnLocation();
            if (at == null) at = m.memory().firstPlace(u).flatMap(Memory.Place::location).orElse(null);
            if (at == null) at = Bukkit.getWorlds().getFirst().getSpawnLocation();
            m.set(this, u, "home", at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockZ());
            if (then != null && p.isOnline()) then.run();
        });
    }

    private Location home(UUID u) {
        String h = m.get(this, u, "home");
        if (h == null) return null;
        String[] w = h.split(" ");
        World world = w.length < 3 ? null : Bukkit.getWorld(w[0]);
        if (world == null) return null;
        try {
            return new Location(world, Integer.parseInt(w[1]) + 0.5, 0, Integer.parseInt(w[2]) + 0.5);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double flat(Location a, Location b) {
        return a.getWorld().equals(b.getWorld()) ? Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()) : Double.MAX_VALUE;
    }

    /** Stage 6, each second: a faint rattle from home as they come near; at home, the locker behind them. */
    private void homeBeat(Player p) {
        UUID u = p.getUniqueId();
        Location h = home(u);
        if (h == null) {
            if (beats % 10 == 0 && m.get(this, u, "home-asked") == null) {
                m.set(this, u, "home-asked", 1);
                findHome(p, null);
            }
            return;
        }
        if (locker.opening(u)) return;
        double d = flat(p.getLocation(), h);
        if (locker.showing(u)) {
            Location at = locker.where(u);
            if (at == null || !Locker.near(p, at, 40)) locker.hide(u);
            else if (beats % 11 == 0) locker.ambient(p);
            return;
        }
        double show = cfg.getDouble("locker.show-range", 48);
        if (d <= 10 && ((Entity) p).isOnGround() && !p.isInsideVehicle()) locker.show(p, false);
        else if (d <= show && beats % 12 == 0) {
            Location from = h.clone();
            from.setY(p.getLocation().getY());
            m.haunt().sound(p, from, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.5f, 0.6f);
        }
    }

    /** The paper in the locker: their first records on this server, as a book screen (built on the main thread). */
    void paper(Player p, Consumer<Book> then) {
        UUID u = p.getUniqueId();
        m.memory().sales(u, sales -> {
            Memory.Sale first = Paper.oldest(sales);
            Map<String, String> v = new HashMap<>();
            v.put("player", p.getName());
            long joined = p.getFirstPlayed();
            v.put("joined", joined > 0 ? Paper.date(joined) : null);
            v.put("joined-time", joined > 0 ? Paper.time(joined) : null);
            Memory.Place pl = m.memory().firstPlace(u).orElse(null);
            v.put("place", pl == null ? null : cfg.getString("worlds." + pl.world(), pl.world()) + " X " + pl.x() + ", Z " + pl.z());
            v.put("guess", m.memory().firstIsGuess(u) ? text("paper.guess") : "");
            v.put("sold", first == null ? text("paper.never-sold") : text("paper.sold").replace("{item}", Items.mmName(first.item()) + "<black>")
                    .replace("{count}", String.format("%,d", first.count())));
            v.put("sold-date", first == null ? null : Paper.date(first.at()) + " " + Paper.time(first.at()));
            v.put("first-back", Paper.firstBack(eco.kv().get(FIRST_BACK + u), text("paper.first-back"), text("paper.first-back-plain")));
            List<Component> pages = new ArrayList<>();
            for (Object page : cfg.getList("paper.pages", List.of())) {
                if (!(page instanceof List<?> raw)) continue;
                List<String> lines = new ArrayList<>();
                for (Object o : raw) lines.add(String.valueOf(o));
                pages.add(Text.mm(String.join("\n", Paper.fill(lines, v))));
            }
            then.accept(Book.book(Text.mm(text("paper.title")), Text.mm(""), pages));
        });
    }

    /** The locker faded: solved (a preview only says so). */
    void finish(Player p, boolean preview) {
        if (preview) {
            p.sendMessage(Text.mm("<yellow>[연출] 사물함 끝 (아무것도 바뀌지 않음)"));
            return;
        }
        UUID u = p.getUniqueId();
        if (stage(u) != HOME) return;
        boolean test = m.test(u);
        m.solve(this, p);
        if (stage(u) <= HOME) return;   // someone else was first (the locker was empty)
        nameKeys(p);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.4f, 1.2f);
        if (!test) {   // the first: their name in gold at the 분실물 센터 where the key rattled 4th
            String c = m.get(this, u, "center");
            BusStop s = c == null ? null : stop(parse(c));
            if (s == null) s = nearestEnd(p.getLocation());
            if (s != null) eco.kv().set(PLAQUE, s.id() + " " + p.getName());
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** BusModule: a lost item went back to its owner. The first one a driver ever gave back is kept (for the paper). */
    @Override
    public void gaveBack(Player driver, String thing, String owner, BusStop here) {
        String k = FIRST_BACK + driver.getUniqueId();
        if (!eco.kv().has(k)) eco.kv().set(k, Paper.encodeBack(System.currentTimeMillis(), thing, owner));
    }

    /** BusModule: the plate on the 분실물 센터's sign. */
    @Override
    public String sign(BusStop s) {
        String v = eco.kv().get(PLAQUE);
        if (v == null) return "";
        String[] w = v.split(" ", 2);
        if (w.length < 2 || !w[0].equals(String.valueOf(s.id()))) return "";
        return "\n" + text("plaque").replace("{player}", w[1]);
    }

    // ------------------------------------------------------------------------------------------
    // every second
    // ------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        beats++;
        BusModule b = bus();
        if (b != null && b != watched) {
            b.watch(id(), this);
            watched = b;
        }
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            int st = stage(u);
            if (st == HOME && on() && !open(u)) {   // someone else was first: the locker still comes, empty
                homeBeat(p);
                continue;
            }
            if (st < 1 || st > HOME || !open(u)) {
                if (locker.showing(u) && !locker.opening(u)) locker.hideFar(p);   // an admin's preview left behind
                continue;
            }
            if (st == HOME) homeBeat(p);
            else if (locker.showing(u) && !locker.opening(u)) locker.hideFar(p);
            if (beats % 5 == 0) keepKey(p, now);
            if (st > 5) continue;
            long[] stand = standing.get(u);
            if (stand != null) {
                Integer at = b == null ? null : b.stoppedAt(p);
                if (st != 4 || at == null || at != stand[0]) standing.remove(u);
                else if (now - stand[1] >= cfg.getLong("stand-seconds", 15) * 1000L) rattle(p, stop((int) stand[0]), 4);
            }
            if (hasKey(p)) omens(p, now);
        }
        // the solver's charm (a tester who solved it sees theirs too)
        java.util.Optional<UUID> first = m.solver(this);
        List<Player> owners = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (stage(u) > HOME && (first.isPresent() && first.get().equals(u) || m.test(u))) owners.add(p);
        }
        charm.beat(owners);
    }

    /** The key does not stay away: some time without it on them, and it is back in their pocket. */
    private void keepKey(Player p, long now) {
        UUID u = p.getUniqueId();
        if (hasKey(p)) {
            without.remove(u);
            return;
        }
        long since = without.computeIfAbsent(u, x -> now);
        if (now - since < cfg.getLong("return-after", 120) * 1000L) return;
        without.remove(u);
        giveKey(p);
        m.haunt().whisper(p, text("lines.back"));
        m.haunt().sound(p, p.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.5f, 0.7f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        int st = stage(p.getUniqueId());
        if (st < 1 || st > HOME || !ownKey(e.getItemDrop().getItemStack(), p.getUniqueId())) return;
        e.setCancelled(true);
        m.haunt().whisper(p, text("lines.drop"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction hit) || !hit.getPersistentDataContainer().has(Locker.TAG)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        locker.click(e.getPlayer(), hit);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Interaction hit && hit.getPersistentDataContainer().has(Locker.TAG)) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        int st = stage(p.getUniqueId());
        if (st >= 1 && st <= HOME) later(p, 40, () -> refreshKeys(p));
    }

    void later(Player p, long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) r.run();
        }, ticks);
    }

    @Override
    public void cleanup(UUID u) {
        locker.hide(u);
        charm.take(u);
        standing.remove(u);
        omenAt.remove(u);
        ghostUntil.remove(u);
        without.remove(u);
    }

    /** Whether the secret is closed for this player (someone else solved it first). */
    boolean taken(UUID u) {
        return on() && !open(u);
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    @Override
    public String status(UUID u) {
        List<String> out = new ArrayList<>();
        int st = stage(u);
        BusModule b = bus();
        if (b != null) out.add("돌려준 분실물 " + b.lostReturned(u) + "/" + cfg.getLong("start.returns", 30));
        if (m.get(this, u, "given") != null) out.add("열쇠 받음");
        String f = m.get(this, u, "found");
        if (f != null) out.add("주운 곳 " + stopName(f));
        if (st >= 1 && st <= 5) {
            Clues.Clue c = Clues.Clue.decode(m.get(this, u, "clue"));
            if (c != null) {
                List<Clues.Stop> net = net();
                List<Integer> ids = Clues.matching(c, net);
                List<String> names = new ArrayList<>();
                for (Integer id : ids.subList(0, Math.min(6, ids.size()))) names.add(stopName(String.valueOf(id)));
                out.add("단서 " + c.kind() + (c.word().isEmpty() ? "" : " " + c.word()) + " · 맞는 정류장 " + ids.size() + "곳: "
                        + String.join(", ", names) + (ids.size() > 6 ? " …" : ""));
            }
            String h = m.get(this, u, "heard");
            if (h != null) out.add("“" + h + "”");
            long[] stand = standing.get(u);
            if (stand != null) out.add("종점에 서 있는 중 " + (System.currentTimeMillis() - stand[1]) / 1000 + "초");
        }
        if (st == HOME) {
            String h = m.get(this, u, "home");
            out.add("사물함 " + (h == null ? "(집 찾는 중)" : h) + (locker.showing(u) ? " · 보이는 중" : ""));
        }
        String plaque = eco.kv().get(PLAQUE);
        if (plaque != null) out.add("명판 " + plaque);
        return String.join(" · ", out);
    }

    @Override
    public List<String> scenes() {
        return List.of("열쇠", "딸랑", "수군거림", "벨", "빈손님", "자리", "사물함", "끝");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        int st = Math.max(1, Math.min(5, stage(p.getUniqueId())));
        BusStop near = nearestStop(p.getLocation());
        switch (word) {
            case "열쇠" -> {
                p.sendMessage(Text.mm(text("lines.got").replace("{type}", "<gray>손님")));
                speak(p, List.of(), null, whispers(0, new Clues.Clue(Clues.Kind.FIRST, ""), p), 25);
            }
            case "딸랑" -> rattleSound(p);
            case "수군거림" -> {
                Clues.Clue next = st < 5 ? Clues.pick(st + 1, net(), p.getUniqueId(), p.getName(), found(p.getUniqueId()), tagOrder()) : null;
                Location at = p.getLocation();
                boolean close = near != null && Math.hypot(near.x() - at.getX(), near.z() - at.getZ()) < 24;
                speak(p, List.of(), close ? near : null, whispers(st, next, p), 5);
            }
            case "벨" -> bell(p);
            case "빈손님" -> {
                ghost(p);
                later(p, 40, () -> p.sendMessage(ghostRow(0)));
            }
            case "자리" -> seat(p);
            case "사물함" -> locker.show(p, true);
            case "끝" -> locker.open(p, true);
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] " + word + " → " + p.getName()));
        return true;
    }
}
