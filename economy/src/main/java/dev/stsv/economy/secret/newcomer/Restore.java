package dev.stsv.economy.secret.newcomer;

import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.bus.BusModule;
import dev.stsv.economy.bus.BusStop;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Putting his record back (docs/admin/숨은_비밀/새로_온_사람.md "복구"): the shard items and where they turn up (a bus stop,
 * a shift at the store, the mailbox, a fishing float — each a moment only that player sees), and what happens at his
 * stone: a shard held to it goes in and its glyphs come back for everyone, a name tag with his name engraves the name
 * line, a wrong one bounces off. The stone itself is a model with no hit box, so an unseen interaction entity stands in it
 * (tag economy:newcomer_touch; not saved with the chunk: it is set again whenever it is missing).
 * <p>
 * Traces and shards are only for someone the gate lets through ({@link Clues}), one shard a day each and a few a day for
 * the server; a tester's go into their own record and nobody else sees a thing.
 */
final class Restore implements BusModule.Watcher {

    static final NamespacedKey SHARD = new NamespacedKey("economy", "record_shard"), TOUCH = new NamespacedKey("economy", "newcomer_touch");
    private static final String MAIL = "newcomer";

    private final Newcomer n;
    private Interaction touch;
    /** who has a bottle on the line (the float twitched: it comes up with the catch) */
    private final Set<UUID> bites = ConcurrentHashMap.newKeySet();
    private BusModule watched;
    private int ticks;

    Restore(Newcomer n) {
        this.n = n;
    }

    // ------------------------------------------------------------------------------------------
    // the shard items
    // ------------------------------------------------------------------------------------------

    /** A shard as an item: that look's item (plain paper when the pack has none) with what is left of the line on it. */
    ItemStack shard(Shards.Look look, Shards.Shard s, Record.Truth truth) {
        ItemStack it = Items.create(look.item, 1).orElseGet(() -> {
            ItemStack plain = new ItemStack(Material.PAPER);
            plain.editMeta(meta -> meta.itemName(Text.item("<#cfc8b8>글자가 번진 종이")));
            return plain;
        });
        String written = Shards.written(truth.of(s.line()), s.kind());
        it.editMeta(meta -> {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Text.item("<gray>" + written));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(SHARD, PersistentDataType.STRING, s.tag());
        });
        return it;
    }

    static Optional<Shards.Shard> read(ItemStack it) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return Optional.empty();
        return Shards.parse(it.getItemMeta().getPersistentDataContainer().get(SHARD, PersistentDataType.STRING));
    }

    static Optional<Shards.Look> look(ItemStack it) {
        return it == null || it.isEmpty() ? Optional.empty() : Shards.Look.ofItem(Items.key(it));
    }

    /**
     * A shard for a trace of this look: of one of that look's lines (not the day while the server does not know it), and
     * mostly of a kind that record still lacks. Empty when that look has nothing to give now.
     */
    Optional<ItemStack> make(Shards.Look look, Record r, Shards.Line want, int kind) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Shards.Line> lines = new ArrayList<>();
        for (Shards.Line l : look.lines) if (!r.truth().of(l).isEmpty() && (want == null || want == l)) lines.add(l);
        if (lines.isEmpty()) return Optional.empty();
        List<Shards.Line> open = lines.stream().filter(l -> !r.whole(l)).toList();
        Shards.Line line = (open.isEmpty() ? lines : open).get(rnd.nextInt(open.isEmpty() ? lines.size() : open.size()));
        int k = kind;
        if (k < 0) {
            List<Integer> useful = Shards.useful(r.have(line));
            k = !useful.isEmpty() && rnd.nextDouble() < 0.7 ? useful.get(rnd.nextInt(useful.size())) : rnd.nextInt(Shards.KINDS);
        }
        return Optional.of(shard(look, new Shards.Shard(line, k), r.truth()));
    }

    // ------------------------------------------------------------------------------------------
    // who finds traces, and how many
    // ------------------------------------------------------------------------------------------

    private boolean test(UUID u) {
        return n.module().test(u);
    }

    /** Whether traces show themselves to this player at all. */
    boolean gate(Player p) {
        UUID u = p.getUniqueId();
        if (!n.openFor(u)) return false;
        Clues.Gate g = Clues.Gate.of(n.cfg().getString("clues.gate", "player"));
        return Clues.open(g, n.module().get(n, u, "atstone") != null, n.record(u).found());
    }

    private boolean room(UUID u) {
        if (test(u)) return true;
        String day = LocalDate.now(dev.stsv.economy.secret.SecretModule.KST).toString();
        var kv = n.kv();
        return Clues.room(day, n.module().get(n, u, "clue.day"), n.module().getLong(n, u, "clue.n", 0), n.cfg().getInt("clues.per-player-day", 1),
                kv.get(Newcomer.K + "clue.day"), kv.getLong(Newcomer.K + "clue.n", 0), n.cfg().getInt("clues.per-server-day", 4));
    }

    private void counted(UUID u) {
        if (test(u)) return;
        String day = LocalDate.now(dev.stsv.economy.secret.SecretModule.KST).toString();
        var kv = n.kv();
        long mine = day.equals(n.module().get(n, u, "clue.day")) ? n.module().getLong(n, u, "clue.n", 0) : 0;
        n.module().set(n, u, "clue.day", day);
        n.module().set(n, u, "clue.n", mine + 1);
        long all = day.equals(kv.get(Newcomer.K + "clue.day")) ? kv.getLong(Newcomer.K + "clue.n", 0) : 0;
        kv.set(Newcomer.K + "clue.day", day);
        kv.set(Newcomer.K + "clue.n", all + 1);
    }

    /**
     * Whether a trace of this look shows itself to the player now (the gate, the day's counts, that place's chance), and
     * if so its shard, already counted. force: an admin's 흔적 (no chance, no counts).
     */
    Optional<ItemStack> trace(Player p, Shards.Look look, boolean force) {
        UUID u = p.getUniqueId();
        if (!force) {
            if (!gate(p) || !room(u)) return Optional.empty();
            String key = switch (look) {
                case TICKET -> "clues.bus.chance";
                case RECEIPT -> "clues.store.chance";
                case LETTER -> "clues.mail.chance-per-hour";
                case BOTTLE -> "clues.fishing.chance";
            };
            double def = look == Shards.Look.LETTER ? 0.15 : 0.03;
            double chance = n.cfg().getDouble(key, def);
            if (look == Shards.Look.LETTER) chance /= 60;   // asked once a minute
            if (!n.module().roll(u, chance)) return Optional.empty();
        }
        Optional<ItemStack> it = make(look, n.record(u), null, -1);
        if (it.isPresent() && !force) counted(u);
        if (it.isPresent()) n.plugin().getLogger().info("[비밀] 새로 온 사람: " + p.getName() + " 에게 깨진 흔적 (" + look.place + ")"
                + (force ? " · 관리자" : "") + (test(u) ? " · 테스트" : ""));
        return it;
    }

    private void give(Player p, ItemStack it) {
        if (n.eco().mail() != null) n.eco().mail().deliver(p.getUniqueId(), it, MAIL);
        else for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    // ------------------------------------------------------------------------------------------
    // the four places
    // ------------------------------------------------------------------------------------------

    /** (Re)registers with the bus and the store (they may have been reloaded). */
    void hook() {
        BusModule b = n.eco().bus();
        if (b != null && b != watched) {
            b.watch(n.id(), this);
            watched = b;
        }
        if (n.eco().store() != null) n.eco().store().receiptGlitch(this::storeShard);
    }

    void unhook() {
        if (watched != null) watched.watch(n.id(), null);
        watched = null;
        if (n.eco().store() != null) n.eco().store().receiptGlitch(null);
        if (touch != null && touch.isValid()) touch.remove();
        touch = null;
    }

    /** The bus: the stop's sign reads broken for a few seconds (to the driver alone), and a ticket was left on a seat. */
    @Override
    public void stopped(Player driver, BusStop here) {
        bus(driver, here, false);
    }

    boolean bus(Player driver, BusStop here, boolean force) {
        Optional<ItemStack> it = trace(driver, Shards.Look.TICKET, force);
        if (it.isEmpty()) return false;
        String name = here == null ? "정류장" : here.name();
        Location at = driver.getEyeLocation().add(driver.getLocation().getDirection().setY(0).normalize().multiply(3)).add(0, 0.6, 0);
        if (here != null) {
            World w = Bukkit.getWorld(here.world());
            double[] xz = here.at(0, BusStop.ROAD_HALF + 2.5);
            if (w != null && w.equals(driver.getWorld())) at = new Location(w, xz[0], here.top() + 2.6, xz[1]);
        }
        glitch(driver, at, name, 90L);
        driver.playSound(driver.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.5f, 0.5f);
        give(driver, it.get());
        return true;
    }

    /** A word with most of its glyphs broken, shown to one player for a while. */
    private void glitch(Player p, Location at, String word, long ticks) {
        StringBuilder b = new StringBuilder();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < word.length(); i++) b.append(word.charAt(i) == ' ' ? ' ' : r.nextInt(3) == 0 ? word.charAt(i) : Shards.GONE);
        try {
            TextDisplay t = n.module().haunt().text(p, at, "<gray>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(b.toString()));
            Bukkit.getScheduler().runTaskLater(n.plugin(), () -> n.module().haunt().remove(p, t), ticks);
        } catch (RuntimeException ignored) {
            // no sign is no loss: the ticket is there all the same
        }
    }

    /**
     * The store: whether the receipt of the sale this worker just rang up comes out broken (the store prints it and the
     * worker picks the strip up: that is the shard). Null: an ordinary receipt.
     */
    ItemStack storeShard(Player worker) {
        return trace(worker, Shards.Look.RECEIPT, false).orElse(null);
    }

    /** The mailbox: a letter come back, "no such addressee", part of it rubbed out. Asked once a minute for everyone online. */
    private void mail() {
        for (Player p : Bukkit.getOnlinePlayers()) mail(p, false);
    }

    boolean mail(Player p, boolean force) {
        if (n.eco().mail() == null) return false;
        if (!force && n.module().getLong(n, p.getUniqueId(), "whispers", 0) <= 0) return false;
        Optional<ItemStack> it = trace(p, Shards.Look.LETTER, force);
        if (it.isEmpty()) return false;
        n.eco().mail().store().send(p.getUniqueId(), Items.bytes(it.get()), MAIL, System.currentTimeMillis());
        p.sendMessage(Lang.msg("mail.arrived", "item", Items.mmName(it.get())));
        return true;
    }

    /** Fishing: the float twitches the wrong way once; what comes up with the catch is a bottle. */
    void bite(Player p, Location hook, boolean force) {
        UUID u = p.getUniqueId();
        if (bites.contains(u)) return;
        if (!force && (!gate(p) || !room(u) || !n.module().roll(u, n.cfg().getDouble("clues.fishing.chance", 0.03)))) return;
        bites.add(u);
        p.spawnParticle(Particle.FISHING, hook.clone().add(0, 0.2, 0), 14, 0.5, 0.02, 0.5, 0.0);
        p.playSound(hook, Sound.BLOCK_GLASS_HIT, 0.5f, 0.6f);
        Bukkit.getScheduler().runTaskLater(n.plugin(), () -> p.playSound(hook, Sound.BLOCK_GLASS_HIT, 0.4f, 0.5f), 6L);
    }

    void caught(Player p) {
        if (!bites.remove(p.getUniqueId())) return;
        Optional<ItemStack> it = make(Shards.Look.BOTTLE, n.record(p.getUniqueId()), null, -1);
        if (it.isEmpty()) return;
        counted(p.getUniqueId());
        n.plugin().getLogger().info("[비밀] 새로 온 사람: " + p.getName() + " 에게 깨진 흔적 (낚시)");
        give(p, it.get());
    }

    /** The line was cast anew, or they left: the bottle that was not reeled in is gone. */
    void slack(UUID u) {
        bites.remove(u);
    }

    // ------------------------------------------------------------------------------------------
    // at the stone
    // ------------------------------------------------------------------------------------------

    /** Each second: the unseen thing to click stands in his stone while there is anything left to do there. */
    void tick() {
        if (++ticks % 60 == 0) mail();
        if (ticks % 3 != 0) return;
        Optional<Location> st = n.stone();
        boolean want = st.isPresent() && n.isOn() && st.get().getWorld().isChunkLoaded(st.get().getBlockX() >> 4, st.get().getBlockZ() >> 4);
        if (!want) {
            if (touch != null && touch.isValid()) touch.remove();
            touch = null;
            return;
        }
        Location at = st.get();
        if (touch != null && touch.isValid() && touch.getWorld().equals(at.getWorld()) && touch.getLocation().distanceSquared(at) < 0.01) return;
        if (touch != null && touch.isValid()) touch.remove();
        for (Entity e : at.getWorld().getNearbyEntities(at, 20, 8, 20)) {   // one left by a reload, or where the monument used to stand
            if (e instanceof Interaction && e.getPersistentDataContainer().has(TOUCH, PersistentDataType.BYTE)) e.remove();
        }
        touch = at.getWorld().spawn(at, Interaction.class, i -> {
            i.setInteractionWidth(1.3f);
            i.setInteractionHeight(1.9f);
            i.setResponsive(false);
            i.setPersistent(false);
            i.getPersistentDataContainer().set(TOUCH, PersistentDataType.BYTE, (byte) 1);
        });
    }

    static boolean touch(Entity e) {
        return e instanceof Interaction && e.getPersistentDataContainer().has(TOUCH, PersistentDataType.BYTE);
    }

    /** His stone was clicked: a shard goes in, a name tag is read, a bare hand may sign (once he stands there). */
    void touched(Player p) {
        UUID u = p.getUniqueId();
        if (!n.openFor(u) || n.busyWith(p)) return;
        Record r = n.record(u);
        if (!r.found()) return;   // nobody has found it yet: it is a bare stone
        ItemStack hand = p.getInventory().getItemInMainHand();
        Optional<Shards.Shard> s = read(hand);
        if (s.isPresent()) {
            insert(p, r, hand, s.get());
            return;
        }
        if (hand.getType() == Material.NAME_TAG) {
            engrave(p, r, hand);
            return;
        }
        if (hand.isEmpty()) n.vigil().sign(p);
    }

    private Location top(Player p) {
        return n.stone().filter(l -> l.getWorld().equals(p.getWorld())).map(l -> l.clone().add(0, 1.5, 0)).orElse(p.getEyeLocation());
    }

    /** Dust off the stone and a low bell: for everyone near, or (a tester) for them alone. */
    private void sign(Player p, boolean good) {
        Location at = top(p);
        var grit = Material.GRAVEL.createBlockData();
        if (test(p.getUniqueId())) {
            p.spawnParticle(Particle.FALLING_DUST, at, good ? 24 : 6, 0.3, 0.15, 0.3, 0, grit);
            p.playSound(at, good ? Sound.BLOCK_BELL_RESONATE : Sound.BLOCK_STONE_HIT, good ? 0.6f : 0.5f, 0.5f);
            return;
        }
        at.getWorld().spawnParticle(Particle.FALLING_DUST, at, good ? 24 : 6, 0.3, 0.15, 0.3, 0, grit);
        at.getWorld().playSound(at, good ? Sound.BLOCK_BELL_RESONATE : Sound.BLOCK_STONE_HIT, good ? 0.6f : 0.5f, 0.5f);
    }

    private void insert(Player p, Record r, ItemStack hand, Shards.Shard s) {
        UUID u = p.getUniqueId();
        int fresh = r.add(s, u);
        if (fresh <= 0) {   // nothing it could bring: it is handed back
            p.sendMessage(n.incoming("…"));
            sign(p, false);
            return;
        }
        hand.subtract(1);
        sign(p, true);
        String name = n.nameOf(p);
        String detail = s.line() == Shards.Line.DATE ? switch (s.kind()) {
            case 3 -> "날짜 앞자리가";
            case 5 -> "날짜 끝자리가";
            default -> "날짜 몇 글자가";
        } : "글자 몇 개가";
        n.module().set(n, u, "shard.detail", detail);
        n.memories().update(u, name, note -> note.plusShard().note("조각을 넣어 줌 (" + s.line().word + ")"));
        n.plugin().getLogger().info("[비밀] 새로 온 사람: " + p.getName() + " 이(가) 조각을 넣음 (" + s.tag() + ", 새 글자 " + fresh + ")"
                + (test(u) ? " · 테스트" : ""));
        n.restored(p, s.line(), r);
    }

    private void engrave(Player p, Record r, ItemStack tag) {
        UUID u = p.getUniqueId();
        Component c = tag.hasItemMeta() ? tag.getItemMeta().customName() : null;
        if (c == null) return;   // a name tag that says nothing
        if (r.named()) {
            p.sendMessage(n.incoming("…"));
            return;
        }
        String night = n.nightKey();
        boolean test = test(u);
        if (!test && !Shards.mayTry(n.module().get(n, u, "tag.night"), n.module().getLong(n, u, "tag.n", 0), night, n.cfg().getInt("stone.name-tries", 3))) {
            sign(p, false);
            return;   // no more tries tonight: it only bounces
        }
        String typed = PlainTextComponentSerializer.plainText().serialize(c);
        if (!Shards.names(typed, r.truth().name(), r.truth().alias())) {
            long used = night.equals(n.module().get(n, u, "tag.night")) ? n.module().getLong(n, u, "tag.n", 0) : 0;
            n.module().set(n, u, "tag.night", night);
            n.module().set(n, u, "tag.n", used + 1);
            sign(p, false);
            p.setVelocity(p.getLocation().getDirection().setY(0).normalize().multiply(-0.35).setY(0.15));
            return;
        }
        r.name(u);
        tag.subtract(1);
        sign(p, true);
        n.memories().update(u, n.nameOf(p), note -> note.engraved().note("이름을 새겨 줌"));
        n.plugin().getLogger().info("[비밀] 새로 온 사람: " + p.getName() + " 이(가) 이름을 새김" + (test ? " · 테스트" : ""));
        n.restored(p, null, r);
    }
}
