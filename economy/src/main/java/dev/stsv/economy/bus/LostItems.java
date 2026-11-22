package dev.stsv.economy.bus;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 분실물 (README "마을버스", bus.yml events.lost): what passengers leave on the bus, as items of their own (economy:lost_&lt;kind&gt;,
 * items/bus.yml), the kind by who left it. The driver who found it takes it back to the stop its owner got on at, where the
 * owner waits (a mannequin of their kind saying "제 지갑이요!", waving at the bus) while that driver is near. A phone rings
 * now and then, a lunchbox goes off, an umbrella pays double in the rain, a quick return pays more; past its time a thing
 * is nobody's (handed in at the end of a line: the lost and found) and a signed ball stays the driver's as a keepsake.
 * Everything here is in memory but the item itself (its tag) and how many each driver gave back (eco.kv).
 */
final class LostItems {

    /** what the item shows now (its state and minutes left), so its name and lore are set again only when that changes */
    static final NamespacedKey SHOWN = new NamespacedKey("economy", "bus_lost_shown");

    private final BusModule bus;
    private final EcoPlugin eco;
    private final STSVPlugin core;

    /** An owner waiting at a stop for their thing (a mannequin, not saved); thanking: going away after their thanks. */
    private static final class Owner {
        final Mannequin m;
        final int stop;
        boolean called, thanking;
        int beat;

        Owner(Mannequin m, int stop) {
            this.m = m;
            this.stop = stop;
        }
    }

    /** a lost item's id -> its owner shown at its stop */
    private final Map<UUID, Owner> owners = new HashMap<>();
    /** player -> the lost items in their inventory (looked at every few seconds, and when one is found or given back) */
    private final Map<UUID, List<BusEvents.LostTag>> held = new HashMap<>();
    /** player -> when their phone rings next; until when the ring shows on their driver line */
    private final Map<UUID, Long> ringAt = new HashMap<>(), ringing = new HashMap<>();
    /** stop id -> when someone got their thing back there (those waiting then board happier) */
    private final Map<Integer, Long> cheer = new HashMap<>();
    private long beats;

    LostItems(BusModule bus, EcoPlugin eco, STSVPlugin core) {
        this.bus = bus;
        this.eco = eco;
        this.core = core;
    }

    private BusEvents.Lost cfg() {
        return bus.spec().events().lost();
    }

    void stop() {
        for (Owner o : owners.values()) o.m.remove();
        owners.clear();
        held.clear();
    }

    // ------------------------------------------------------------------------------------------
    // The item
    // ------------------------------------------------------------------------------------------

    /** The name of what a tag is (its kind's name; an older tag's own word). */
    String thingName(BusEvents.LostTag t) {
        BusEvents.LostKind k = cfg().kind(t.what());
        return k == null ? t.what() : k.name();
    }

    /** "할머니가" / "손님이": who left it, for the lore. */
    private String who(BusEvents.LostTag t) {
        BusSpec.Type type = bus.spec().types().get(t.owner());
        return BusEvents.josa(type == null ? "손님" : type.name(), "이", "가");
    }

    /** A new lost item of this kind with this tag (its own item; paper if the pack lacks it), marked like the shop's goods. */
    private ItemStack make(BusEvents.LostKind k, BusEvents.LostTag tag, String id) {
        ItemStack item = Items.create(id, 1).orElseGet(() -> new ItemStack(org.bukkit.Material.PAPER));
        item.editMeta(m -> {
            m.setMaxStackSize(1);
            m.getPersistentDataContainer().set(BusModule.LOST, PersistentDataType.STRING, tag.encode());
        });
        Items.markBought(item);
        look(item, tag, System.currentTimeMillis());
        return item;
    }

    /** Sets the item's name and lore for its state now (only when what it shows changed: then true). */
    private boolean look(ItemStack item, BusEvents.LostTag t, long now) {
        BusEvents.Lost lo = cfg();
        BusEvents.LostKind k = lo.kind(t.what());
        BusEvents.LostState st = lo.state(t, now);
        long min = t.minutesLeft(now);
        boolean fastNow = k != null && t.found() > 0 && t.minutes(now) < lo.fastMinutes() && lo.fastBonus() > 1;
        String key = st + ":" + (st == BusEvents.LostState.ORPHAN ? 0 : min) + ":" + fastNow;
        if (key.equals(item.getItemMeta().getPersistentDataContainer().get(SHOWN, PersistentDataType.STRING))) return false;
        String thing = k == null ? t.what() : k.name();
        BusStop home = bus.stopMap().get(t.stop());
        String stop = home == null ? "?" : home.name();
        List<Component> lore = new ArrayList<>();
        Component name;
        if (st == BusEvents.LostState.ORPHAN) {
            name = Lang.item("bus.lost-name-orphan", "thing", thing);
            lore.add(Lang.item("bus.lost-lore-orphan-1", "who", who(t), "thing", thing));
            lore.add(Lang.item("bus.lost-lore-orphan-2", "g", BusModule.g(lo.orphanPay(k))));
        } else {
            boolean spoiled = st == BusEvents.LostState.SPOILED;
            String shown = spoiled && k != null ? "쉰 " + thing : thing;
            name = Lang.item(spoiled ? "bus.lost-name-spoiled" : "bus.lost-name", "thing", shown);
            lore.add(Lang.item("bus.lost-lore-1", "who", who(t), "thing", shown));
            lore.add(Lang.item("bus.lost-lore-2", "stop", stop));
            lore.add(Lang.item("bus.lost-lore-3", "min", min));
            if (spoiled) lore.add(Lang.item("bus.lost-lore-spoiled"));
            if (fastNow) lore.add(Lang.item("bus.lost-hint-fast", "min", Math.max(1, (long) Math.ceil(lo.fastMinutes() - t.minutes(now))),
                    "x", x(lo.fastBonus())));
            if (k != null) {
                if (k.rain() > 1) lore.add(Lang.item("bus.lost-hint-rain", "x", x(k.rain())));
                if (k.ring()) lore.add(Lang.item("bus.lost-hint-ring"));
                if (k.spoil() > 0 && !spoiled) lore.add(Lang.item("bus.lost-hint-spoil", "min", (long) Math.ceil(k.spoil())));
                if (k.keepsake()) lore.add(Lang.item("bus.lost-hint-keepsake"));
            }
        }
        lore.add(Lang.item("bus.lost-lore-end"));
        item.editMeta(m -> {
            m.itemName(name);
            m.lore(lore);
            m.getPersistentDataContainer().set(SHOWN, PersistentDataType.STRING, key);
        });
        return true;
    }

    static String x(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    /**
     * A player's lost items brought up to date: food gone off takes its spoiled look, a thing past its time becomes nobody's
     * (a signed ball the finder's keepsake), the lore's minutes count down. Remembers what they hold.
     */
    void refresh(Player p, long now) {
        BusEvents.Lost lo = cfg();
        var inv = p.getInventory();
        ItemStack[] items = inv.getStorageContents();
        List<BusEvents.LostTag> mine = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            BusEvents.LostTag t = BusModule.lostTag(items[i]);
            if (t == null) continue;
            BusEvents.LostKind k = lo.kind(t.what());
            BusEvents.LostState st = lo.state(t, now);
            String was = items[i].getItemMeta().getPersistentDataContainer().get(SHOWN, PersistentDataType.STRING);
            if (st == BusEvents.LostState.KEEPSAKE && k != null) {
                ItemStack keep = Items.create(k.keepsakeItem(), 1).orElse(null);
                if (keep != null) {
                    Items.markBought(keep);
                    inv.setItem(i, keep);
                    p.sendMessage(Lang.mm("bus.lost-keepsake"));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.4f);
                    continue;
                }
                st = BusEvents.LostState.ORPHAN;
            }
            if (st == BusEvents.LostState.SPOILED && k != null && !Items.key(items[i]).equals(k.item(true))) {
                ItemStack off = make(k, t, k.item(true));    // its spoiled look
                inv.setItem(i, off);
                BusStop home = bus.stopMap().get(t.stop());
                if (t.finder().equals(p.getUniqueId())) p.sendMessage(Lang.mm("bus.lost-spoiled", "stop", home == null ? "?" : home.name()));
                mine.add(t);
                continue;
            }
            if (st == BusEvents.LostState.ORPHAN && (was == null || !was.startsWith("ORPHAN")) && t.finder().equals(p.getUniqueId()))
                p.sendMessage(Lang.mm("bus.lost-orphan", "thing", thingName(t), "g", BusModule.g(lo.orphanPay(k))));
            if (look(items[i], t, now)) inv.setItem(i, items[i]);   // (the array may hold copies)
            mine.add(t);
        }
        if (mine.isEmpty()) held.remove(p.getUniqueId());
        else held.put(p.getUniqueId(), mine);
    }

    private List<BusEvents.LostTag> held(Player p) {
        return held.getOrDefault(p.getUniqueId(), List.of());
    }

    // ------------------------------------------------------------------------------------------
    // Left behind, given back
    // ------------------------------------------------------------------------------------------

    /**
     * Someone got off: with events.lost's chance (one a stop visit, max-held a driver at once) they left something, its kind
     * by who they are: it is the driver's to take back to the stop they got on at. The 선글라스 남자 and ghosts leave nothing.
     */
    void drop(Player p, BusModule.Run run, BusModule.Rider r, BusStop here) {
        BusEvents.Lost lo = cfg();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (!lo.enabled() || run.lostHere || r.secret || r.type.x().ghost() || r.from == here.id() || rnd.nextDouble() >= lo.chance()) return;
        BusStop home = bus.stopMap().get(r.from);
        if (home == null) return;
        long now = System.currentTimeMillis();
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            BusEvents.LostTag t = BusModule.lostTag(it);
            if (t != null && !t.expired(now) && t.finder().equals(p.getUniqueId())) n++;
        }
        if (n >= lo.maxHeld()) return;
        if (bus.watcherLeft(p, r, home, here)) {   // something else was left instead (a hidden secret: BusModule.Watcher)
            run.lostHere = true;
            return;
        }
        BusEvents.LostKind k = lo.pick(r.type.id(), rnd.nextDouble());
        if (k == null) return;
        run.lostHere = true;
        BusEvents.LostTag tag = new BusEvents.LostTag(home.id(), now + lo.expire() * 60_000L, p.getUniqueId(), k.id(), UUID.randomUUID(), now, r.type.id());
        ItemStack item = make(k, tag, k.item(false));
        for (ItemStack left : p.getInventory().addItem(item).values()) p.getWorld().dropItem(p.getLocation(), left);
        p.sendMessage(Lang.mm("bus.lost-got", "type", bus.typeTag(r.type), "thing", k.name(), "stop", home.name(), "min", lo.expire()));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.9f);
        if (k.ring()) ringAt.put(p.getUniqueId(), now + rnd.nextLong(lo.ringFrom(), lo.ringTo() + 1L) * 1000L);
        refresh(p, now);
    }

    /** A stop at either end of a line: its lost and found (분실물 센터) takes the things nobody came for. */
    boolean terminal(BusStop s) {
        BusModule.Net n = bus.net(s.world());
        if (n == null) return false;
        for (BusLine l : n.linesOf(s.id())) {
            List<Integer> st = l.stops();
            if (!st.isEmpty() && (st.getFirst() == s.id() || st.getLast() == s.id())) return true;
        }
        return false;
    }

    /**
     * The bus stopped in a stop's bay: the driver's lost items for it go back to their owners (the reward, the owner's
     * thanks, everyone waiting cheers); at the end of a line the things nobody came for go to the lost and found.
     */
    void returnAt(Player p, BusModule.Run run, BusStop here) {
        BusEvents.Lost lo = cfg();
        long now = System.currentTimeMillis();
        refresh(p, now);
        var inv = p.getInventory();
        ItemStack[] items = inv.getStorageContents();
        boolean center = terminal(here), raining = p.getWorld().hasStorm();
        int orphans = 0;
        long orphanPay = 0;
        boolean otherSaid = false, changed = false;
        for (int i = 0; i < items.length; i++) {
            BusEvents.LostTag t = BusModule.lostTag(items[i]);
            if (t == null) continue;
            BusEvents.LostKind k = lo.kind(t.what());
            boolean mine = t.finder().equals(p.getUniqueId());
            if (t.expired(now)) {   // nobody's any more: to the lost and found at the end of a line
                if (!center || !mine) continue;
                inv.setItem(i, null);
                orphans++;
                orphanPay += lo.orphanPay(k);
                changed = true;
                continue;
            }
            if (t.stop() != here.id()) continue;
            if (!mine) {
                if (!otherSaid) p.sendMessage(Lang.mm("bus.lost-other"));
                otherSaid = true;
                continue;
            }
            inv.setItem(i, null);
            changed = true;
            giveBack(p, run, here, t, k, now, raining);
        }
        if (orphans > 0) {
            long[] paid = bus.bonus(p, run, orphanPay, "bus_event");
            p.sendMessage(Lang.mm("bus.lost-center", "n", orphans, "g", BusModule.g(paid[0]), "fee", fee(paid[1])));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.2f);
        }
        if (changed) refresh(p, now);
    }

    /** eco.kv key: how many lost items this driver gave back. */
    static String backKey(UUID driver) {
        return "bus.lost.back." + driver;
    }

    private static String fee(long fee) {
        return fee > 0 ? Lang.raw("bus.bonus-fee", "fee", BusModule.g(fee)) : "";
    }

    private void giveBack(Player p, BusModule.Run run, BusStop here, BusEvents.LostTag t, BusEvents.LostKind k, long now, boolean raining) {
        BusEvents.Lost lo = cfg();
        String thing = k == null ? t.what() : k.name();
        BusEvents.LostPay pay = k == null ? new BusEvents.LostPay(0, false, false, false) : lo.pay(k, t, now, raining);
        long[] paid = bus.bonus(p, run, pay.amount(), "bus_event");
        StringBuilder why = new StringBuilder();
        if (pay.fast()) why.append(Lang.raw("bus.lost-why-fast", "x", x(lo.fastBonus())));
        if (pay.rain()) why.append(Lang.raw("bus.lost-why-rain", "x", x(k.rain())));
        if (pay.spoiled()) why.append(Lang.raw("bus.lost-why-spoiled"));
        String key = backKey(p.getUniqueId());
        long before = eco.kv().getLong(key, 0), count = before + 1;
        eco.kv().set(key, String.valueOf(count));
        p.sendMessage(Lang.mm("bus.lost-back", "thing", thing, "g", BusModule.g(paid[0]), "why", why.toString(), "fee", fee(paid[1]), "n", count));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 2f);
        BusSpec.Type ownerType = bus.spec().types().get(t.owner());
        bus.watcherGaveBack(p, thing, ownerType == null ? "" : ownerType.name(), here);   // a hidden secret keeps the first one
        for (String title : lo.titlesReached(before, count)) {
            if (core.profiles() == null || !core.profiles().grantTitle(p.getUniqueId(), title)) continue;
            String name = core.profiles().title(title).map(x -> x.name()).orElse(title);
            Bukkit.broadcast(Lang.msg("bus.lost-title", "player", p.getName(), "n", count, "title", name));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.1f);
        }
        // the owner: thanks, hearts, then off home; everyone waiting there cheers (and boards happier)
        Owner o = owners.remove(t.id());
        if (o == null || !o.m.isValid()) o = spawnOwner(t, here);
        if (o != null) {
            Owner owner = o;
            owner.thanking = true;
            List<String> lines = k != null && !k.thanks().isEmpty() ? k.thanks() : lo.thanks();
            if (!lines.isEmpty()) bus.say(owner.m, lines.get(ThreadLocalRandom.current().nextInt(lines.size())));
            owner.m.setDescription(null);
            owner.m.setRotation(BusModule.yawTo(owner.m.getLocation(), p.getX(), p.getZ()), 0);
            owner.m.swingMainHand();
            owner.m.getWorld().spawnParticle(Particle.HEART, owner.m.getLocation().add(0, 2.1, 0), 4, 0.3, 0.2, 0.3);
            if (k != null && k.id().equals("leash")) owner.m.getWorld().playSound(owner.m.getLocation(), Sound.ENTITY_WOLF_AMBIENT, 0.8f, 1.5f);
            Bukkit.getScheduler().runTaskLater(eco, owner.m::remove, 70L);
        }
        if (lo.mood() > 0) {
            cheer.put(here.id(), now);
            World w = p.getWorld();
            int n = 0;
            for (Mannequin m : w.getNearbyEntitiesByType(Mannequin.class, new Location(w, here.x(), here.y() + 1, here.z()), 8)) {
                if (!m.getPersistentDataContainer().has(BusModule.GUEST) || m.isInsideVehicle() || (o != null && m.equals(o.m))) continue;
                m.getWorld().spawnParticle(Particle.HEART, m.getLocation().add(0, 2.1, 0), 1, 0.2, 0.1, 0.2);
                m.swingOffHand();
                n++;
            }
            if (n > 0) p.sendMessage(Lang.mm("bus.lost-cheer", "n", Math.round(lo.mood())));
        }
    }

    /** The mood more a passenger who came to wait at this stop at `since` boards with (they saw a lost thing come back). */
    double cheer(int stop, long since) {
        Long at = cheer.get(stop);
        if (at == null) return 0;
        if (System.currentTimeMillis() - at > 15 * 60_000L) {
            cheer.remove(stop);
            return 0;
        }
        return since <= at ? cfg().mood() : 0;
    }

    // ------------------------------------------------------------------------------------------
    // Every second: owners waiting, the phone, the items' looks
    // ------------------------------------------------------------------------------------------

    void second(long now) {
        beats++;
        BusEvents.Lost lo = cfg();
        if (beats % 5 == 0) {
            for (Player p : Bukkit.getOnlinePlayers()) refresh(p, now);
            held.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
            ringing.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
        }
        if (!lo.enabled() && held.isEmpty()) return;
        phones(now);
        owners(now);
    }

    /** A phone carried rings every ring seconds: a ring tone and its owner asking for it (on the driver line while driving). */
    private void phones(long now) {
        BusEvents.Lost lo = cfg();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean phone = false;
            for (BusEvents.LostTag t : held(p)) {
                BusEvents.LostKind k = lo.kind(t.what());
                if (k != null && k.ring() && !t.expired(now)) phone = true;
            }
            UUID u = p.getUniqueId();
            if (!phone) {
                ringAt.remove(u);
                continue;
            }
            long at = ringAt.computeIfAbsent(u, x -> now + ThreadLocalRandom.current().nextLong(lo.ringFrom(), lo.ringTo() + 1L) * 1000L);
            if (now < at) continue;
            ringAt.put(u, now + ThreadLocalRandom.current().nextLong(lo.ringFrom(), lo.ringTo() + 1L) * 1000L);
            ringing.put(u, now + 4000);
            for (int i = 0; i < 3; i++) {
                long d = i * 8L;
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.6f);
                    Bukkit.getScheduler().runTaskLater(eco, () -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.3f), 3L);
                }, d);
            }
            if (!driving(u)) HudModule.flash(p, Lang.mm("bus.lost-ring"), 4000);
        }
    }

    private boolean driving(UUID u) {
        for (BusModule.Run r : bus.runMap().values()) if (u.equals(r.driver)) return true;
        return false;
    }

    /**
     * The owners: one at its stop for every lost item whose finder drives within the stops' show range of it, standing at
     * the front of the line by the bus's door with "제 지갑이요!" under their name; once the bus is within queue.notice they
     * call out once and keep looking at it and waving. Gone again when the driver goes away.
     */
    private void owners(long now) {
        BusSpec spec = bus.spec();
        Set<UUID> want = new HashSet<>();
        for (BusModule.Run r : bus.runMap().values()) {
            Player p = Bukkit.getPlayer(r.driver);
            if (p == null) continue;
            for (BusEvents.LostTag t : held(p)) {
                if (t.expired(now) || !t.finder().equals(p.getUniqueId())) continue;
                BusStop s = bus.stopMap().get(t.stop());
                if (s == null || !s.world().equals(p.getWorld().getName())) continue;
                double d = Math.hypot(p.getX() - s.x(), p.getZ() - s.z());
                if (d > spec.stops().showRange()) continue;
                want.add(t.id());
                Owner o = owners.get(t.id());
                if (o == null || !o.m.isValid()) {
                    if (o != null) owners.remove(t.id());
                    o = spawnOwner(t, s);
                    if (o == null) continue;
                    owners.put(t.id(), o);
                }
                if (d <= spec.queue().notice()) {
                    o.m.setRotation(BusModule.yawTo(o.m.getLocation(), p.getX(), p.getZ()), 0);
                    if (!o.called) {
                        o.called = true;
                        BusEvents.LostKind k = spec.events().lost().kind(t.what());
                        bus.say(o.m, Lang.raw("bus.lost-owner-call", "thing", BusEvents.josa(k == null ? t.what() : k.name(), "이요", "요")));
                    }
                    if (o.beat++ % 2 == 0) o.m.swingMainHand();
                    else o.m.swingOffHand();
                }
            }
        }
        owners.entrySet().removeIf(e -> {
            Owner o = e.getValue();
            if (o.thanking) return true;   // removed by its own timer
            if (want.contains(e.getKey()) && o.m.isValid()) return false;
            o.m.remove();
            return true;
        });
    }

    /** The owner of a lost item, waiting at its stop (at the front of the line, by the bus's door), or null (not loaded). */
    private Owner spawnOwner(BusEvents.LostTag t, BusStop s) {
        World w = Bukkit.getWorld(s.world());
        if (w == null) return null;
        BusSpec spec = bus.spec();
        BusSpec.Type type = spec.types().get(t.owner());
        if (type == null) type = spec.types().get("resident");
        if (type == null && !spec.types().isEmpty()) type = spec.types().values().iterator().next();
        if (type == null) return null;
        int f = s.side() >= 0 ? 1 : -1;
        double out0 = s.shelterOut(spec.stops().bayWidth());
        double[] at = s.bay() ? s.at(f * 3.6, out0 - 0.5) : s.at(f * 2.2, out0 + 0.2);
        Location loc = new Location(w, at[0], s.y() + 1, at[1], s.facingRoad(), 0);
        if (!w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return null;
        BusEvents.LostKind k = spec.events().lost().kind(t.what());
        String thing = BusEvents.josa(k == null ? t.what() : k.name(), "이요", "요");
        BusSpec.Type ty = type;
        Mannequin m = bus.spawnGuest(loc, type, BusModule.pickSkin(type, false), x -> {
            x.customName(dev.stsv.core.util.Text.mm(bus.typeTag(ty)));
            x.setCustomNameVisible(true);
            x.setDescription(Lang.mm("bus.lost-owner-tag", "thing", thing));
        });
        return new Owner(m, s.id());
    }

    // ------------------------------------------------------------------------------------------
    // The driver line
    // ------------------------------------------------------------------------------------------

    /**
     * What the driver line adds: the phone ringing (for a few seconds), else the lost item to take back first (the one
     * with the least time left) and where: "👜지갑 → 느티나무 아래 1.2km" (straight distance), just "👜→느티나무 아래" while
     * the navigator's line shows (it has the road distance), "+n" for more.
     */
    String hud(Player p, boolean nav) {
        long now = System.currentTimeMillis();
        Long ring = ringing.get(p.getUniqueId());
        if (ring != null) {
            if (now < ring) return Lang.raw("bus.lost-ring-hud");
            ringing.remove(p.getUniqueId());
        }
        BusEvents.LostTag first = null;
        int n = 0;
        for (BusEvents.LostTag t : held(p)) {
            if (t.expired(now) || !t.finder().equals(p.getUniqueId()) || !bus.stopMap().containsKey(t.stop())) continue;
            n++;
            if (first == null || t.expires() < first.expires()) first = t;
        }
        if (first == null) return "";
        BusStop s = bus.stopMap().get(first.stop());
        String more = n > 1 ? Lang.raw("bus.lost-hud-more", "n", n - 1) : "";
        if (nav) return Lang.raw("bus.lost-hud-nav", "stop", s.name(), "more", more);
        double d = s.world().equals(p.getWorld().getName()) ? Math.hypot(p.getX() - s.x(), p.getZ() - s.z()) : Double.POSITIVE_INFINITY;
        return Lang.raw("bus.lost-hud", "thing", thingName(first), "stop", s.name(), "km", BusModule.km(d), "more", more);
    }
}
