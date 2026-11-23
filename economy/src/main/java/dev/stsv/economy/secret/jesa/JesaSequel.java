package dev.stsv.economy.secret.jesa;

import dev.stsv.core.util.Text;
import dev.stsv.economy.bus.BusModule;
import dev.stsv.economy.bus.BusStop;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SequelState;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 후속 임무 「기일」 (docs/admin/숨은_비밀/주인_없는_제사.md "후속 임무"; jesa.yml {@code sequel}). Word has got round among
 * those who ride the bus: about once a week, when the solver pulls into a stop with an old passenger aboard, that passenger
 * asks, for the driver's ears only: "…우리 집도 한 번만." Where they get off, that family's house stands (the secret's own
 * house, vessels and judging: {@link House}, {@link Vessel}, {@link Table}). Each week's family has one circumstance
 * ({@link Variant}) that changes what a right table is, in a way families really differ — and says it only as a remark
 * ("…아버님은 생전에 비린 걸 못 드셨소."). Two bows at night; a wrong table shakes off what is wrong (it comes back to them)
 * and the incense is out for a while; a right one is eaten in a short meal, the family says a word, the reward is paid and
 * the solver's 족보 gains a line for that family.
 * <p>
 * Item safety is the secret's own: one of a thing leaves the hand onto a vessel and is saved at once; it comes back when
 * it is taken off, shaken off, when the week turns, on an admin's reset, when the secret (or the sequel) is switched off —
 * through the very code of the secret ({@code Jesa.put/take/takeOff/returnAll}), only under other keys.
 * <p>
 * Steps ({@link Giil}): 0 asked (the passenger rides on; the house comes where they get off), 1 the house stands, 2 the
 * table was accepted (the meal). Notes of the going mission ({@code secret.jesa.<uuid>.sequel.x.}): family, variant, heard
 * (the stop), house ("world x y z"), stop, plate.&lt;0..14&gt; (what lies on a vessel, as the secret keeps it), retry (ms),
 * tries. Kept ({@code sequel.keep.}): families, variants (the recent ones), jokbo ("family@date;…": the lines the book has
 * earned). The secret's own keys ({@code secret.jesa.<uuid>.plate.N}, house, stop …) are never touched: the two houses use
 * different keys and never stand at once (the weekly one only when the secret's stage is past its last, or 0).
 */
public final class JesaSequel extends Sequel {

    /** The prefix of the weekly table's saved keys under secret.jesa.&lt;uuid&gt;. (the mission's own notes). */
    static final String PRE = "sequel.x.";
    private static final NamespacedKey BOOK_LINES = new NamespacedKey("economy", "jesa_jokbo_lines"), BOOK_BASE = new NamespacedKey("economy", "jesa_jokbo_base");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy년 M월 d일");
    private static final List<String> ASKS = List.of("…기사 양반. 우리 집도 한 번만.");

    private final Jesa j;
    /** solver -> the passenger who asked (while they ride the solver's bus; only while the server runs) */
    private final Map<UUID, UUID> riding = new HashMap<>();
    /** solvers whose shown house is the weekly one; those whose meal has ended (the house is going) */
    private final Set<UUID> showing = new HashSet<>(), ending = new HashSet<>();
    /** house places already looked at for water this run; when the family's remark was last said at the table (ms) */
    private final Map<UUID, String> dryChecked = new HashMap<>();
    private final Map<UUID, Long> remarked = new HashMap<>();
    private List<Variant> variants = List.of();
    private List<Giil.Family> families = List.of();
    /** player -> when {@link #idle} last looked at them (ms) */
    private final Map<UUID, Long> idled = new HashMap<>();
    private long readAt;

    JesaSequel(Jesa j) {
        super(j);
        this.j = j;
    }

    @Override
    public String id() {
        return "기일";
    }

    @Override
    protected void loaded() {
        readAt = 0;
    }

    // ------------------------------------------------------------------------------------------
    // the pools
    // ------------------------------------------------------------------------------------------

    /** The families and the circumstances of jesa.yml that can be played on this server (read again every minute). */
    private void read() {
        long now = System.currentTimeMillis();
        if (readAt != 0 && now - readAt < 60_000L) return;
        readAt = now;
        families = Giil.families(cfg().getMapList("families"));
        Table.Rules base = j.rules();
        Map<String, Boolean> known = new HashMap<>();
        List<Variant> ok = new ArrayList<>();
        for (Variant v : Variant.parse(cfg().getMapList("variants"))) {
            Variant here = v.existing(id -> known.computeIfAbsent(id, k -> Items.create(k, 1).isPresent()));
            if (here.usable(base, id -> known.computeIfAbsent(id, k -> Items.create(k, 1).isPresent()))) ok.add(here);
        }
        variants = ok;
    }

    private Giil.Family family(UUID u) {
        read();
        String id = get(u, "family", "");
        for (Giil.Family f : families) if (f.id().equals(id)) return f;
        return families.isEmpty() ? null : families.getFirst();
    }

    private Variant variant(UUID u) {
        read();
        String id = get(u, "variant", "");
        for (Variant v : variants) if (v.id().equals(id)) return v;
        return null;
    }

    private List<String> types() {
        List<String> l = cfg().getStringList("types");
        return l.isEmpty() ? List.of(j.cfg().getString("start.type", "grandma")) : l;
    }

    /** The old passengers on the bus this player drives. */
    private List<Entity> elders(Player p) {
        BusModule b = eco.bus();
        List<Entity> out = new ArrayList<>();
        if (b != null) for (String t : types()) out.addAll(b.ridersOf(p, t));
        return out;
    }

    private String text(String key, String def) {
        return line("lines." + key, def);
    }

    private void whisper(Player p, String key, String def) {
        String v = text(key, def);
        if (!v.isEmpty()) m.haunt().whisper(p, v);
    }

    /** The family's remark about this week's table, as a faint line to read again. */
    private void remark(Player p, Location at) {
        UUID u = p.getUniqueId();
        Variant v = variant(u);
        if (v == null || v.say().isEmpty()) return;
        remarked.put(u, System.currentTimeMillis());
        j.say(p, null, at, v.say().getFirst());
        for (int i = 1; i < v.say().size(); i++) p.sendMessage(Text.mm("<dark_gray><i>" + v.say().get(i)));
    }

    // ------------------------------------------------------------------------------------------
    // whose table
    // ------------------------------------------------------------------------------------------

    /** Whether the secret's own house cannot stand for them now (solved, or never begun): only then is there a weekly one. */
    private boolean free(UUID u) {
        return Giil.free(m.stage(secret, u), secret.stages());
    }

    /**
     * Whether the table this player touches and bows before is the weekly house's: the mission is going on, its house
     * stands (step 1 or 2), it is switched on, and the secret's own house is not theirs.
     */
    boolean table(UUID u) {
        return on() && live(u) && step(u) >= Giil.HOUSE && free(u);
    }

    private Location home(UUID u) {
        String saved = get(u, "house");
        int[] at = Place.decode(saved);
        World w = at == null ? null : Bukkit.getWorld(Place.world(saved));
        return w == null ? null : new Location(w, at[0], at[1], at[2]);
    }

    private long waiting(UUID u) {
        return Math.max(0, getLong(u, "retry", 0) - System.currentTimeMillis());
    }

    private String[] tablets(UUID u) {
        Giil.Family f = family(u);
        Variant v = variant(u);
        List<String> out = new ArrayList<>();
        if (f != null && (v == null || v.tablet("father"))) out.add(f.father());
        if (f != null && (v == null || v.tablet("mother"))) out.add(f.mother());
        if (out.isEmpty()) out.add(j.text("tablets.father"));
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------------------------------
    // beginning: on the bus
    // ------------------------------------------------------------------------------------------

    /** It begins from a bus stop, never from the clock. */
    @Override
    protected boolean offer(Player p) {
        return false;
    }

    /** Called by the secret when this player's bus pulls into a stop's bay. */
    void stopped(Player p, BusStop here) {
        UUID u = p.getUniqueId();
        if (!may(p) || !free(u)) return;
        if (live(u)) {
            // the one who asked was lost on the way (a restart, another bus): this stop will do
            if (step(u) == Giil.ASKED && !riding.containsKey(u)) arrive(p, here, null);
            return;
        }
        if (!due(p)) return;
        read();
        if (families.isEmpty() || variants.isEmpty()) return;
        List<Entity> aboard = elders(p);
        if (aboard.isEmpty() || !chance(p, "chance", 0.25)) return;
        if (!offered(p)) return;
        begin(p, aboard.get(ThreadLocalRandom.current().nextInt(aboard.size())), here);
    }

    /** This week's family and its circumstance (fixed by the week; not the recent ones), and the passenger says it. */
    private void begin(Player p, Entity rider, BusStop here) {
        UUID u = p.getUniqueId();
        read();
        long seed = Giil.seed(week(u), u);
        List<String> fam = families.stream().map(Giil.Family::id).toList(), var = variants.stream().map(Variant::id).toList();
        String f = Giil.pick(seed, fam, Giil.list(kept(u, "families", "")));
        String v = Giil.pick(Long.rotateLeft(seed, 21) ^ 0x9E3779B97F4A7C15L, var, Giil.list(kept(u, "variants", "")));
        if (f == null || v == null) return;
        set(u, "family", f);
        set(u, "variant", v);
        if (here != null) set(u, "heard", here.id());
        keep(u, "families", Giil.remember(kept(u, "families", ""), f, Math.min(fam.size() - 1, Math.max(0, cfg().getInt("recent-families", 5)))));
        keep(u, "variants", Giil.remember(kept(u, "variants", ""), v, Math.min(var.size() - 1, Math.max(0, cfg().getInt("recent-variants", 6)))));
        if (rider != null) riding.put(u, rider.getUniqueId());
        else riding.remove(u);
        Giil.Family family = family(u);
        List<String> asks = cfg().getStringList("asks");
        if (asks.isEmpty()) asks = ASKS;
        String ask = family != null && !family.ask().isEmpty() ? family.ask() : asks.get((int) Math.floorMod(seed >>> 9, (long) asks.size()));
        j.later(p, 30, () -> j.say(p, rider, Jesa.ahead(p), ask));
    }

    /** Admin 시작: on a bus, the first old passenger asks (none: a voice, and the house comes at the next stop); on foot, the house stands beside them at once. */
    @Override
    protected void start(Player p) {
        UUID u = p.getUniqueId();
        read();
        if (!free(u)) {
            p.sendMessage(Text.mm("<yellow>[기일] 이 비밀의 본편이 진행 중인 사람에게는 주간 집이 서지 않아요 (본편을 끝내거나 초기화한 뒤 다시)"));
            return;
        }
        if (families.isEmpty() || variants.isEmpty()) {
            p.sendMessage(Text.mm("<red>[기일] jesa.yml sequel.families / sequel.variants 에 쓸 수 있는 것이 없어요"));
            return;
        }
        BusModule b = eco.bus();
        List<Entity> aboard = elders(p);
        Integer at = b == null ? null : b.stoppedAt(p);
        begin(p, aboard.isEmpty() ? null : aboard.getFirst(), at == null ? null : j.stop(at));
        if (b != null && b.ridersOf(p) >= 0) return;   // driving: the house comes where they get off (or at the next stop)
        Location beside = j.beside(p);
        if (beside == null) {
            j.noDryGround(p, null);
            return;
        }
        j.later(p, 100, () -> {
            if (live(u) && step(u) == Giil.ASKED) settle(p, beside, null, null, Jesa.ahead(p));
        });
    }

    /** Each second at step 0: is the one who asked still on their bus? */
    private void watchRider(Player p) {
        UUID u = p.getUniqueId();
        UUID who = riding.get(u);
        if (who == null) return;
        BusModule b = eco.bus();
        if (b != null) for (Entity e : elders(p)) if (e.getUniqueId().equals(who)) return;
        riding.remove(u);
        if (b == null || b.ridersOf(p) < 0) return;   // the bus itself is gone: wherever they stop next
        Integer at = b.stoppedAt(p);
        if (at == null) at = j.lastStop(u);
        BusStop s = at == null ? null : j.stop(at);
        if (s != null) arrive(p, s, Bukkit.getEntity(who));
    }

    /** They got off here: their house is near (step 1). No ground for it here: the next stop. */
    private void arrive(Player p, BusStop here, Entity who) {
        Location at = j.pick(p, here);
        if (at == null) return;
        World w = at.getWorld();
        settle(p, at, here, who, new Location(w, here.x(), here.y() + 2.6, here.z()));
    }

    private void settle(Player p, Location at, BusStop here, Entity who, Location voice) {
        UUID u = p.getUniqueId();
        long said = System.currentTimeMillis() - since(u);
        long wait = said >= 0 && said < 7000 ? (7000 - said) / 50 + 20 : 20;   // their first line is read before the second
        set(u, "house", Place.encode(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()));
        if (here != null) set(u, "stop", here.id());
        else remove(u, "stop");
        remove(u, "retry");
        dryChecked.remove(u);
        accept(p);
        step(u, Giil.HOUSE);
        Giil.Family f = family(u);
        j.later(p, wait, () -> j.say(p, who, voice, f == null ? "…저기가 우리 집이오." : f.house()));
        j.later(p, wait + 50, () -> m.haunt().sound(p, at.clone().add(0.5, 1, 0.5), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 2f, 0.6f));
        j.later(p, wait + 100, () -> {
            if (live(u)) remark(p, voice);
        });
    }

    // ------------------------------------------------------------------------------------------
    // each second
    // ------------------------------------------------------------------------------------------

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        if (!free(u)) return;   // (a tester in the middle of the secret itself: the weekly house waits)
        if (step(u) == Giil.ASKED) {
            watchRider(p);
            return;
        }
        House house = j.house();
        House.Shown s = house.get(u);
        if (s != null && !s.valid()) {
            house.hide(u);
            s = null;
        }
        double show = j.cfg().getDouble("house.show-range", 48);
        if (s != null && s.preview) {   // an admin's look stands there until they walk off
            showing.remove(u);
            if (!Jesa.within(p, s.base, show + 8)) house.hide(u);
            return;
        }
        if (s == null) showing.remove(u);
        if (ending.contains(u)) return;
        Location h = home(u);
        if (h == null) {   // its place is lost (the world is gone): the next stop they pull into
            if (get(u, "house") == null) step(u, Giil.ASKED);
            return;
        }
        if (s == null || !showing.contains(u)) {
            if (!Jesa.within(p, h, show) || !h.isChunkLoaded()) return;
            Location at = Jesa.settle(h);
            String where = get(u, "house");
            if (!where.equals(dryChecked.get(u))) {   // never on water: looked at once per place and run
                Boolean dry = Jesa.dry(at);
                if (dry == null) return;
                if (!dry) {
                    resite(p, where);
                    return;
                }
                dryChecked.put(u, where);
            }
            s = house.show(p, at, false, j.plates(u, PRE), tablets(u));
            showing.add(u);
            house.light(s, j.night(u, h.getWorld()));
            house.smoke(s, waiting(u) == 0);
            if (System.currentTimeMillis() - remarked.getOrDefault(u, 0L) > Math.max(10, cfg().getLong("remark-gap-seconds", 240)) * 1000L)
                remark(p, s.at(0.5, 1.9, -0.3));
            return;
        }
        if (!Jesa.within(p, s.base, show + 8)) {
            house.hide(u);
            showing.remove(u);
            return;
        }
        if (s.busy) return;
        house.light(s, j.night(u, s.base.getWorld()));
        boolean burning = waiting(u) == 0;
        if (burning && !s.smoke) {
            j.whisper(p, "lines.relit");
            m.haunt().sound(p, s.table(), Sound.ITEM_FLINTANDSTEEL_USE, 0.2f, 1.7f);
        }
        house.smoke(s, burning);
    }

    /** The saved place turned out to stand on water: chosen again by its stop, else the next stop they pull into. */
    private void resite(Player p, String where) {
        UUID u = p.getUniqueId();
        BusStop st = null;
        try {
            String stop = get(u, "stop");
            st = stop == null ? null : j.stop(Integer.parseInt(stop));
        } catch (NumberFormatException ignored) {
            // an admin's house
        }
        Location again = st == null ? j.beside(p) : j.pick(p, st);
        if (again != null) {
            set(u, "house", Place.encode(again.getWorld().getName(), again.getBlockX(), again.getBlockY(), again.getBlockZ()));
            eco.getLogger().info("[비밀] 후속 기일: " + p.getName() + " 의 집이 물 위였음 (" + where + ") → 다시 고름: " + get(u, "house"));
            return;
        }
        remove(u, "house");
        remove(u, "stop");
        step(u, Giil.ASKED);   // what lies on its vessels stays saved and comes with the house
        eco.getLogger().info("[비밀] 후속 기일: " + p.getName() + " 의 집이 물 위였음 (" + where + "), 근처에 마른 땅이 없어 다음 정류장에서 다시");
    }

    /**
     * Called by the secret each second for everyone online (cheap: it looks every 15 seconds, and only at the solver): the 족보 they carry gains the lines it has earned, and anything still saved on a weekly table that is no
     * longer theirs to touch (the sequel switched off in the files, a tester gone back into the secret) goes back to them.
     */
    void idle(Player p) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = idled.get(u);
        if (last != null && now - last < 15_000L) return;
        idled.put(u, now);
        if (!member(u)) return;
        if (!(on() && live(u) && free(u)) && !j.saved(u, PRE).isEmpty()) giveBackAll(u, true);
        if (on() && syncBook(p)) whisper(p, "book", "<gray>…족보의 끝 장에 먹 냄새가 난다.");
    }

    // ------------------------------------------------------------------------------------------
    // the bows, and the judging (called by the secret when the table is the weekly one)
    // ------------------------------------------------------------------------------------------

    /** Two bows before the weekly table: what lies on the vessels is judged by this family's table. */
    void rite(Player p, House.Shown s) {
        UUID u = p.getUniqueId();
        if (!may(p) || !table(u) || ending.contains(u)) return;
        if (!j.night(u, s.base.getWorld())) {
            j.whisper(p, "lines.day");
            return;
        }
        if (waiting(u) > 0) {
            j.whisper(p, "lines.incense-out");
            return;
        }
        Map<Integer, ItemStack> food = j.plates(u, PRE);
        if (food.isEmpty()) {
            j.whisper(p, "lines.empty");
            return;
        }
        Variant v = variant(u);
        if (v == null) {   // the file no longer has this week's circumstance: nothing can be judged, nothing is taken
            whisper(p, "no-variant", "<gray>…향이 흔들리기만 한다.");
            eco.getLogger().warning("[비밀] 후속 기일: " + p.getName() + " 의 이번 주 사정 '" + get(u, "variant", "") + "' 이 jesa.yml 에 없음 (초기화하거나 되돌릴 것)");
            return;
        }
        j.house().food(p, food);
        Map<Integer, String> layout = Jesa.ids(food);
        Table.Verdict verdict = v.judge(layout, j.rules());
        set(u, "tries", getLong(u, "tries", 0) + 1);
        if (verdict.ok()) {
            step(u, Giil.ACCEPTED);
            supper(p, s, layout, v);
        } else refuse(p, s, verdict);
    }

    private long retryAt(UUID u) {
        return System.currentTimeMillis() + seconds(u, "retry", 180) * 1000L;
    }

    /** Wrong: as in the secret — what is wrong rolls off and comes back to them, what is owed is tapped, the incense is out. */
    private void refuse(Player p, House.Shown s, Table.Verdict verdict) {
        UUID u = p.getUniqueId();
        Map<Integer, String> judged = j.saved(u, PRE);
        set(u, "retry", retryAt(u));
        if (step(u) == Giil.ACCEPTED) step(u, Giil.HOUSE);
        j.house().refuse(p, s, verdict, slot -> j.takeOff(u, PRE, slot, judged.get(slot)), () -> {
            j.whisper(p, "lines.out");
            j.house().food(p, j.plates(u, PRE));
            j.later(p, 40, () -> {
                if (live(u) && j.house().get(u) == s) remark(p, s.at(0.5, 1.9, -0.3));   // the family says it once more
            });
        });
    }

    /** Right: they come and eat, briefly. */
    private void supper(Player p, House.Shown s, Map<Integer, String> layout, Variant v) {
        UUID u = p.getUniqueId();
        Giil.Family f = family(u);
        j.house().supper(p, s, layout, v.apply(j.rules()), at -> {
            if (f != null && !f.meal().isBlank()) j.say(p, null, at, f.meal());
        }, () -> {
            ending.add(u);
            j.house().dissolve(p, s);
            j.later(p, 60, () -> conclude(p));
        });
    }

    /** The meal is over: the mission is done and what lay on the table is eaten, in one go. */
    private void conclude(Player p) {
        UUID u = p.getUniqueId();
        // only the end of a meal that is still theirs (a quit or a reload in the last seconds leaves step 2 to be bowed for again)
        if (!ending.remove(u) || Bukkit.getPlayer(u) != p) return;
        showing.remove(u);
        if (!may(p) || !live(u) || step(u) != Giil.ACCEPTED || !free(u)) return;
        Map<Integer, ItemStack> food = j.plates(u, PRE);
        Variant v = variant(u);
        // the table is no longer the one that was accepted (things were taken off by an admin meanwhile): nothing was eaten
        if (v == null || food.isEmpty() || j.saved(u, PRE).size() != food.size() || !v.judge(Jesa.ids(food), j.rules()).ok()) {
            step(u, Giil.HOUSE);
            set(u, "retry", retryAt(u));
            j.whisper(p, "lines.gone");
            return;
        }
        Giil.Family f = family(u);
        if (f != null) {
            String line = "<dark_gray><i>" + f.thanks();
            p.sendMessage(Text.mm(line));
            m.haunt().whisper(p, "<#d8d2c4><i>" + f.thanks());
            keep(u, "jokbo", Giil.add(kept(u, "jokbo", ""), new Giil.Entry(f.id(), DAY.format(ZonedDateTime.now(SecretModule.KST))),
                    Math.max(1, cfg().getInt("jokbo.max", 40))));
        }
        // done first (the reward), eaten right after in the same tick: nothing goes for an end that did not come
        complete(p);
        if (state(u) != SequelState.State.DONE) return;
        for (int slot : food.keySet()) j.dropPlate(u, PRE, slot);   // out of their bag since it was put down: eaten now
        if (syncBook(p)) p.sendMessage(Text.mm(text("book", "<gray>…족보의 끝 장에 먹 냄새가 난다.")));
        forget(u);
    }

    // ------------------------------------------------------------------------------------------
    // the 족보: one line a family
    // ------------------------------------------------------------------------------------------

    /**
     * Writes the lines it has earned ({@code sequel.keep.jokbo}) into every 족보 this solver carries, after the book's own
     * pages (how many those are is remembered on the book the first time). True when a book changed. A book that is not
     * in their bag now simply gains them the next time it is.
     */
    private boolean syncBook(Player p) {
        UUID u = p.getUniqueId();
        List<Giil.Entry> entries = Giil.entries(kept(u, "jokbo", ""));
        if (entries.isEmpty()) return false;
        read();
        Map<String, Giil.Family> byId = new LinkedHashMap<>();
        for (Giil.Family f : families) byId.put(f.id(), f);
        PlayerInventory inv = p.getInventory();
        boolean any = false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.isEmpty() || !Jesa.JOKBO.equals(Items.key(it)) || !(it.getItemMeta() instanceof BookMeta bm)) continue;
            PersistentDataContainer pdc = bm.getPersistentDataContainer();
            if (pdc.getOrDefault(BOOK_LINES, PersistentDataType.INTEGER, 0) == entries.size()) continue;
            int base = Math.min(bm.getPageCount(), pdc.getOrDefault(BOOK_BASE, PersistentDataType.INTEGER, bm.getPageCount()));
            List<Component> pages = new ArrayList<>(bm.pages().subList(0, base));
            List<String> header = cfg().getStringList("jokbo.header");
            if (header.isEmpty()) header = List.of("<black><b>…그 뒤로 상을 받은 집:</b>");
            for (List<String> page : Giil.pages(entries, byId, header, cfg().getInt("jokbo.per-page", 4))) pages.add(Text.mm(String.join("\n", page)));
            bm.pages(pages);
            pdc.set(BOOK_BASE, PersistentDataType.INTEGER, base);
            pdc.set(BOOK_LINES, PersistentDataType.INTEGER, entries.size());
            it.setItemMeta(bm);
            inv.setItem(i, it);
            any = true;
        }
        return any;
    }

    // ------------------------------------------------------------------------------------------
    // join, quit, the week's end
    // ------------------------------------------------------------------------------------------

    @Override
    protected void resume(Player p) {
        UUID u = p.getUniqueId();
        if (step(u) < Giil.HOUSE || !free(u)) return;   // (step 0: the one who asked is gone with the restart — the next stop will do)
        j.later(p, 80, () -> {
            if (!live(u)) return;
            p.sendMessage(Text.mm(text("resume", "<dark_gray>…그 집 상이 아직 비어 있다.")));
        });
    }

    private void forget(UUID u) {
        idled.remove(u);
        riding.remove(u);
        ending.remove(u);
        dryChecked.remove(u);
        remarked.remove(u);
    }

    @Override
    protected void cleanup(UUID u) {
        if (showing.remove(u)) j.house().hide(u);
        forget(u);
        // the secret switched off (끄기): what lies on the weekly table goes back now rather than wait for it to be on again
        if (!m.enabled(secret) && !j.saved(u, PRE).isEmpty()) giveBackAll(u, Bukkit.getPlayer(u) != null);
    }

    /** The week turned, an admin's reset, no longer the solver: everything on the weekly table goes back to them. */
    @Override
    protected void expired(UUID u) {
        giveBackAll(u, Bukkit.getPlayer(u) != null);
    }

    /**
     * Everything saved on their weekly table back to them, by the secret's own way (pockets, else the mailbox). What that
     * could not give (no mailbox) is handed over directly when they are online; what cannot be given at all is logged
     * with its saved value, to restore by hand.
     */
    private void giveBackAll(UUID u, boolean tell) {
        int n = j.returnAll(u, PRE);
        Player p = Bukkit.getPlayer(u);
        for (Map.Entry<Integer, String> e : new TreeMap<>(j.saved(u, PRE)).entrySet()) {
            ItemStack it = j.unpack(e.getValue());
            if (it != null && p != null) {
                j.dropPlate(u, PRE, e.getKey());
                give(p, it);
                n++;
            } else {
                eco.getLogger().severe("[비밀] 후속 기일: 제기 " + e.getKey() + " 의 것을 돌려주지 못함 (" + u + "), 복구용 값 (serializeAsBytes, Base64): " + e.getValue());
            }
        }
        if (n > 0) {
            eco.getLogger().info("[비밀] 후속 기일: 제기 위의 것 " + n + "개를 돌려줌 (" + u + ")");
            if (tell && p != null) p.sendMessage(Text.msg(j.text("lines.returned")));
        }
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    private static String row(Map<Integer, String> layout, Set<Integer> wrong) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < Table.ROWS; row++) {
            List<String> cells = new ArrayList<>();
            for (int col = 0; col < Table.COLS; col++) {
                int slot = Table.slot(row, col);
                String id = layout.get(slot);
                cells.add(Vessel.side(slot) + " " + (id == null ? "-" : id.replace("economy:", "")) + (wrong.contains(slot) ? "✗" : ""));
            }
            rows.add((row + 1) + "열[" + String.join(", ", cells) + "]");
        }
        return String.join(" · ", rows);
    }

    @Override
    public String status(UUID u) {
        read();
        SequelState.State st = state(u);
        if (!st.live()) {
            int lines = Giil.entries(kept(u, "jokbo", "")).size();
            return "노인 손님 (" + String.join(", ", types()) + ") 을 태우고 정류장에 설 때마다 확률 " + cfg().getDouble("chance", 0.25) + " · 집안 " + families.size()
                    + " · 사정 " + variants.size() + " · 족보에 적힌 집 " + lines;
        }
        List<String> out = new ArrayList<>();
        if (!free(u)) out.add("<yellow>본편 진행 중이라 멈춤</yellow>");
        Giil.Family f = family(u);
        Variant v = variant(u);
        out.add("집안 " + (f == null ? "-" : f.name()) + " · 사정 " + (v == null ? "<red>" + get(u, "variant", "-") + " (파일에 없음)</red>" : v.id() + " “" + String.join(" ", v.say()) + "”"));
        int step = step(u);
        if (step == Giil.ASKED) {
            out.add(riding.containsKey(u) ? "부탁한 손님이 타고 있음 → 할 일: 그 손님이 내리는 정류장까지 운행" : "손님을 놓침 → 할 일: 버스로 아무 정류장에나 서기 (거기에 집)");
            return String.join(" · ", out);
        }
        String stop = get(u, "stop");
        out.add("집 " + get(u, "house", "-") + (stop == null ? "" : " (" + j.stopName(stop) + " 정류장)") + (showing.contains(u) ? " · 보이는 중" : ""));
        Map<Integer, String> laid = Jesa.ids(j.plates(u, PRE));
        if (v != null) {
            Table.Verdict verdict = v.judge(laid, j.rules());
            out.add("제기 " + laid.size() + "/" + Vessel.COUNT + (laid.isEmpty() ? "" : verdict.ok() ? " (맞음)" : " (틀린 것 " + verdict.wrong().size() + " · 빠진 자리 "
                    + verdict.missing().size() + ")"));
            if (!laid.isEmpty()) out.add(row(laid, verdict.wrong()));
            out.add("맞는 상의 예: " + row(v.sample(j.rules()), Set.of()));
        }
        long wait = waiting(u);
        if (wait > 0) out.add("향 꺼짐 " + (wait + 999) / 1000 + "초 남음");
        out.add(step == Giil.ACCEPTED ? "상이 받아들여짐 (음복 중이거나, 끊겼으면 다시 두 번 절)" : "할 일: 밤에 상을 차리고 상 앞에서 웅크리기 두 번");
        return String.join(" · ", out);
    }

    @Override
    public List<String> scenes() {
        return List.of("부탁", "집", "음복", "족보");
    }

    /** An admin's look: a house beside them with one family's tablets and a table that family accepts (its vessels take nothing). */
    private House.Shown sceneHouse(Player p, CommandSender admin, Giil.Family f, Variant v) {
        Location at = j.beside(p);
        if (at == null) {
            j.noDryGround(p, admin);
            return null;
        }
        List<String> tablets = new ArrayList<>();
        if (v.tablet("father")) tablets.add(f.father());
        if (v.tablet("mother")) tablets.add(f.mother());
        House.Shown s = j.house().show(p, at, true, Jesa.made(v.sample(j.rules())), tablets.toArray(new String[0]));
        showing.remove(p.getUniqueId());
        j.house().light(s, true);
        j.house().smoke(s, true);
        return s;
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        read();
        if (families.isEmpty() || variants.isEmpty()) {
            admin.sendMessage(Text.mm("<red>jesa.yml sequel.families / sequel.variants 에 쓸 수 있는 것이 없어요"));
            return true;
        }
        // this week's family and circumstance when a mission is going on, else any
        Giil.Family f = live(u) && family(u) != null ? family(u) : families.get(ThreadLocalRandom.current().nextInt(families.size()));
        Variant v = live(u) && variant(u) != null ? variant(u) : variants.get(ThreadLocalRandom.current().nextInt(variants.size()));
        switch (word) {
            case "부탁" -> {
                List<Entity> aboard = elders(p);
                List<String> asks = cfg().getStringList("asks");
                if (asks.isEmpty()) asks = ASKS;
                j.say(p, aboard.isEmpty() ? null : aboard.getFirst(), Jesa.ahead(p), f.ask().isEmpty() ? asks.get(ThreadLocalRandom.current().nextInt(asks.size())) : f.ask());
            }
            case "집" -> {
                House.Shown s = sceneHouse(p, admin, f, v);
                if (s == null) return true;
                j.say(p, null, Jesa.ahead(p), f.house());
                j.later(p, 80, () -> {
                    if (!v.say().isEmpty()) j.say(p, null, Jesa.ahead(p), v.say().getFirst());
                });
            }
            case "음복" -> {
                House.Shown s = sceneHouse(p, admin, f, v);
                if (s == null) return true;
                j.house().supper(p, s, v.sample(j.rules()), v.apply(j.rules()), at -> j.say(p, null, at, f.meal()), () -> {
                    j.house().dissolve(p, s);
                    p.sendMessage(Text.mm("<dark_gray><i>" + f.thanks()));
                    p.sendMessage(Text.mm("<yellow>[연출] 음복 끝 (아무것도 바뀌지 않음)"));
                });
            }
            case "족보" -> {   // what the book's last pages read like now, with this family added (nothing is written)
                Map<String, Giil.Family> byId = new LinkedHashMap<>();
                for (Giil.Family x : families) byId.put(x.id(), x);
                List<Giil.Entry> entries = new ArrayList<>(Giil.entries(kept(u, "jokbo", "")));
                entries.add(new Giil.Entry(f.id(), DAY.format(ZonedDateTime.now(SecretModule.KST))));
                List<String> header = cfg().getStringList("jokbo.header");
                for (List<String> page : Giil.pages(entries, byId, header, cfg().getInt("jokbo.per-page", 4))) {
                    admin.sendMessage(Text.mm("<gray>── 족보의 덧붙는 장 ──"));
                    for (String l : page) admin.sendMessage(Text.mm(l.isEmpty() ? " " : l));
                }
            }
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] 후속 " + word + " → " + p.getName() + " <dark_gray>(" + f.name() + " · " + v.id() + ")"));
        return true;
    }
}
