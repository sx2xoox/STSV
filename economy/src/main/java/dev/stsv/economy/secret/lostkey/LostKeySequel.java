package dev.stsv.economy.secret.lostkey;

import dev.stsv.core.util.Text;
import dev.stsv.economy.bus.BusModule;
import dev.stsv.economy.bus.BusSpec;
import dev.stsv.economy.bus.BusStop;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SequelState.State;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 후속 임무 「주인 찾기」 (docs/admin/숨은_비밀/분실물센터.md "후속 임무"; lostkey.yml {@code sequel:}). 어려움.
 * <p>
 * Once a week, as the one who found the key's owner pulls their bus into a stop, a lost thing turns up on the bus with its
 * owner's name rubbed off (one of {@code sequel.things}). The passengers waiting there murmur whose it is and two or three
 * clues to the stop where the owner waits ({@link Owners#clues}: a trait, the name's initial, a line's end — the kinds of
 * the key's own clues). Pulling into a stop that fits every clue with the thing aboard, someone only the driver sees
 * steps up, takes it and thanks them in the words of that thing: the week's reward, and now and then a small gift from
 * the bus's own gift lists (bus.yml types.*.gifts). A stop that does not fit: the thing stays and a clue is murmured
 * again; the third wrong stop, or the week ending, and it goes to the 분실물센터's shelf (failed, nothing lost).
 * <p>
 * The thing is the mission's own item (not for sale, bound to the driver, one live copy: {@link #SERIAL}); it cannot be
 * dropped, comes back to the pocket when put away, and is taken at every ending.
 * <p>
 * Active step 1: carrying it. Step 2: being handed over (finished by {@link #tick} if the scene was cut short).
 * Notes: thing (its id), found (the stop it turned up at), target (the stop the clues were made from), clues
 * ({@link Owners#encode}), wrong (stop ids, ","), serial. Kept: recent (the last weeks' things).
 */
public final class LostKeySequel extends Sequel {

    /** On the thing: whose mission it is, and which copy (only the latest one given counts). */
    static final NamespacedKey THING = new NamespacedKey("economy", "lostkey_thing");
    static final NamespacedKey SERIAL = new NamespacedKey("economy", "lostkey_thing_serial");

    /** One lost thing of the pool. */
    record Thing(String id, String name, String item, String type, String owner, String murmur, String thanks, List<String> lore) {}

    private final LostKey key;
    private final Map<UUID, Mannequin> owners = new ConcurrentHashMap<>();
    /** player -> since when the thing is not on them */
    private final Map<UUID, Long> without = new ConcurrentHashMap<>();
    /** players whose pockets were full when the thing was to be handed to them: tried again until it fits (never dropped) */
    private final Set<UUID> waiting = ConcurrentHashMap.newKeySet();
    private long beats;

    LostKeySequel(LostKey key) {
        super(key);
        this.key = key;
    }

    @Override
    public String id() {
        return "주인 찾기";
    }

    // ------------------------------------------------------------------------------------------
    // the pool, the network
    // ------------------------------------------------------------------------------------------

    private static String str(Map<?, ?> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    List<Thing> things() {
        List<Thing> out = new ArrayList<>();
        for (Map<?, ?> t : cfg().getMapList("things")) {
            if (t.get("id") == null) continue;
            List<String> lore = t.get("lore") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            out.add(new Thing(String.valueOf(t.get("id")), str(t, "name", "물건"), str(t, "item", LostKey.ITEM), str(t, "type", ""),
                    str(t, "owner", "주인"), str(t, "murmur", "…누가 두고 내린 거야."), str(t, "thanks", "…고맙습니다."), lore));
        }
        if (out.isEmpty()) out.add(new Thing("thing", "물건", LostKey.ITEM, "", "주인", "…누가 두고 내린 거야.", "…고맙습니다.", List.of()));
        return out;
    }

    private Thing thing(UUID u) {
        String id = get(u, "thing", "");
        List<Thing> all = things();
        for (Thing t : all) if (t.id().equals(id)) return t;
        return all.getFirst();
    }

    /** The stops of one world as the clues see them. */
    private List<Clues.Stop> net(String world) {
        List<Clues.Stop> out = new ArrayList<>();
        for (Clues.Stop s : key.net()) {
            BusStop b = key.stop(s.id());
            if (b != null && b.world().equals(world)) out.add(s);
        }
        return out;
    }

    private static int parse(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private List<Integer> wrongs(UUID u) {
        return Owners.recent(get(u, "wrong", ""));
    }

    /** The murmured line that tells a clue. */
    private String clueLine(Clues.Clue c) {
        return switch (c.kind()) {
            case TAG -> line("clues.tag." + c.word(), line("clues.plain", "…표지판뿐인 정류장에서 기다린대."));
            case PLAIN -> line("clues.plain", "…표지판뿐인 정류장에서 기다린대.");
            case INITIAL -> line("clues.initial", "…{jamo}{ro} 시작하는 정류장이래.", "jamo", c.word(), "ro", Clues.ro(c.word()));
            case FIRST -> line("clues.first", "…노선 맨 처음 정류장이래.");
            case END -> line("clues.end", "…종점이래. 분실물센터가 있는 데.");
            default -> line("clues.any", "…어디든. 어느 정류장에나 서 있대.");
        };
    }

    // ------------------------------------------------------------------------------------------
    // the thing
    // ------------------------------------------------------------------------------------------

    private static String pdc(ItemStack it, NamespacedKey k) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(k, PersistentDataType.STRING);
    }

    /** Any copy of a mission thing (whoever's, whichever week). */
    private static boolean isThing(ItemStack it) {
        return pdc(it, THING) != null;
    }

    /** The live copy of this player's thing. */
    private boolean mine(ItemStack it, UUID u) {
        if (!u.toString().equals(pdc(it, THING))) return false;
        String serial = get(u, "serial");
        return serial != null && serial.equals(pdc(it, SERIAL)) && live(u);
    }

    private boolean hasThing(Player p) {
        UUID u = p.getUniqueId();
        for (ItemStack it : p.getInventory().getContents()) if (mine(it, u)) return true;
        return mine(p.getItemOnCursor(), u);
    }

    /** Takes the mission things a player carries: every copy, or only the dead ones (another week's, an older copy). */
    private void take(Player p, boolean all) {
        UUID u = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isThing(it) && (all || !mine(it, u))) inv.setItem(i, null);
        }
        ItemStack cur = p.getItemOnCursor();
        if (isThing(cur) && (all || !mine(cur, u))) p.setItemOnCursor(null);
    }

    private ItemStack make(Player p, Thing t) {
        UUID u = p.getUniqueId();
        String serial = Long.toHexString(System.currentTimeMillis()) + Long.toHexString(ThreadLocalRandom.current().nextLong());
        set(u, "serial", serial);   // the copies given before are dead
        // A secret's own item (items/secrets.yml: the 거래소 and the auction refuse it) wearing the look of the lost thing.
        // Never the bus's own economy:lost_* item itself: that one is ordinary goods (it can be listed and sold).
        ItemStack it = Items.create(LostKey.ITEM, 1).orElseGet(() -> new ItemStack(Material.PAPER));
        NamespacedKey look = Items.create(t.item(), 1).filter(ItemStack::hasItemMeta).map(x -> x.getItemMeta().hasItemModel() ? x.getItemMeta().getItemModel() : null).orElse(null);
        List<Component> lore = new ArrayList<>();
        for (String l : t.lore()) lore.add(Text.item(l));
        for (String l : cfg().getStringList("item.lore")) lore.add(Text.item(l));
        String heard = line("item.heard", "<dark_gray>“{line}”");
        for (Clues.Clue c : Owners.decode(get(u, "clues"))) lore.add(Text.item(heard.replace("{line}", Text.plain(clueLine(c)))));
        it.editMeta(meta -> {
            meta.setMaxStackSize(1);
            if (look != null) meta.setItemModel(look);
            meta.itemName(Text.item(line("item.name", "<#c9a26b>이름이 지워진 {thing}", "thing", t.name())));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(THING, PersistentDataType.STRING, u.toString());
            meta.getPersistentDataContainer().set(SERIAL, PersistentDataType.STRING, serial);
        });
        return Items.markBought(it);   // the shop, requests and machines refuse it
    }

    /** Into their pockets, never onto the ground (a thing lying about could be picked up by anyone). False: no room now. */
    private boolean hand(Player p, ItemStack it) {
        UUID u = p.getUniqueId();
        if (p.getInventory().addItem(it).isEmpty()) {
            waiting.remove(u);
            return true;
        }
        if (waiting.add(u)) tell(p, "no-room", "<dark_gray>…주머니에 자리가 없다. 그것은 아직 뒷자리에 있다. <gray>(자리를 비우면 들어온다)");
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // the bus pulls into a stop
    // ------------------------------------------------------------------------------------------

    @Override
    protected boolean offer(Player p) {
        if (++beats % 30 == 0) take(p, false);   // a thing left over from a week that ended while they were away
        return false;   // it begins at a stop: stopped()
    }

    /** From the secret's bus watcher: this driver's bus pulled into a stop's bay. */
    void stopped(Player p, BusStop here) {
        if (here == null || !may(p)) return;
        UUID u = p.getUniqueId();
        State st = state(u);
        if (st == State.NONE) {
            if (!due(p) || net(here.world()).size() < 2 || !chance(p, "chance", 0.5) || !offered(p)) return;
            begin(p, here);
        } else if (st == State.ACTIVE && step(u) < 2) {
            arrive(p, here);
        }
    }

    /** The thing turns up: this week's thing, the stop its owner waits at, the clues; the passengers murmur them. */
    private void begin(Player p, BusStop here) {
        UUID u = p.getUniqueId();
        List<Clues.Stop> net = net(here.world());
        List<Thing> pool = things();
        String week = week(u);
        int v = Owners.variant(week, u, pool.size(), Owners.recent(kept(u, "recent", "")));
        keep(u, "recent", Owners.remember(kept(u, "recent", ""), v, cfg().getInt("no-repeat", 4), pool.size()));
        Thing t = pool.get(v);
        Clues.Stop target = Owners.target(net, here.id(), Owners.hash(week, u));
        if (target == null) {   // one stop only (an admin's start): nowhere to take it
            p.sendMessage(Text.mm("<yellow>[후속] 정류장이 두 곳 이상 있어야 주인을 찾으러 갈 수 있어요."));
            fail(p, "no-stops");
            return;
        }
        List<Clues.Clue> clues = Owners.clues(target, net, key.tagOrder());
        set(u, "thing", t.id());
        set(u, "found", here.id());
        set(u, "target", target.id());
        set(u, "clues", Owners.encode(clues));
        remove(u, "wrong");
        accept(p);
        step(u, 1);
        take(p, true);
        without.remove(u);
        waiting.remove(u);
        tell(p, "got", "<gray>뒷자리에 <white>{thing}</white> 하나가 남아 있다. 이름표의 이름이 문질러 지워져 있다.", "thing", t.name());
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.9f);
        hand(p, make(p, t));
        List<String> lines = new ArrayList<>();
        lines.add(t.murmur());
        for (Clues.Clue c : clues) lines.add(clueLine(c));
        key.speak(p, List.of(), here, lines, 30);
        key.later(p, 30 + lines.size() * 50L + 30, () -> tell(p, "got-later", "<dark_gray>…주인이 기다리는 정류장에, 이걸 싣고 서면 된다."));
    }

    /** With the thing aboard at a stop: the owner, or not here. */
    private void arrive(Player p, BusStop here) {
        UUID u = p.getUniqueId();
        if (!hasThing(p)) return;
        List<Clues.Stop> net = net(here.world());
        Clues.Stop s = net.stream().filter(x -> x.id() == here.id()).findFirst().orElse(null);
        int oldest = net.stream().mapToInt(Clues.Stop::id).min().orElse(-1);
        List<Clues.Clue> clues = Owners.decode(get(u, "clues"));
        List<Integer> wrong = wrongs(u);
        int max = cfg().getInt("wrong-stops", 3);
        switch (Owners.arrive(here.id(), parse(get(u, "found"), -1), Owners.fits(clues, s, oldest), wrong, max)) {
            case RIGHT -> handOver(p, here);
            case AGAIN -> m.haunt().whisper(p, line("again", "<dark_gray>…여기는 아까도 아니었다."));
            case WRONG -> {
                wrong.add(here.id());
                set(u, "wrong", String.join(",", wrong.stream().map(String::valueOf).toList()));
                m.haunt().sound(p, p.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.4f, 0.6f);
                m.haunt().whisper(p, line("wrong", "<dark_gray>…아무도 나서지 않는다. 여기가 아니다. <gray>({n}/{max})", "n", String.valueOf(wrong.size()), "max", String.valueOf(max)));
                // the people waiting here say one of the clues again
                if (!clues.isEmpty())
                    key.speak(p, List.of(), here, List.of(line("wrong-murmur", "…그 사람은 여기서 안 기다려."), clueLine(clues.get((wrong.size() - 1) % clues.size()))), 30);
            }
            case SHELF -> {
                take(p, true);
                m.haunt().sound(p, p.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.5f, 0.7f);
                fail(p, "shelf");
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------------------------------
    // the owner
    // ------------------------------------------------------------------------------------------

    /** Somewhere to stand by the bus on the stop's side: out from the road past the driver, on the ground. */
    private static Location beside(Player p, BusStop here) {
        Location l = p.getLocation();
        double nx = -here.hz() * here.side(), nz = here.hx() * here.side();
        for (double out : new double[]{3.5, 4.5, 2.5, 5.5}) {
            Location at = l.clone().add(nx * out, 0, nz * out);
            for (int dy = 2; dy >= -3; dy--) {
                Block feet = at.getWorld().getBlockAt(at.getBlockX(), l.getBlockY() + dy, at.getBlockZ());
                if (!feet.isPassable() || feet.isLiquid() || !feet.getRelative(0, 1, 0).isPassable() || feet.getRelative(0, -1, 0).isPassable()) continue;
                Location spot = new Location(at.getWorld(), at.getX(), feet.getY(), at.getZ());
                spot.setYaw((float) Math.toDegrees(Math.atan2(nx, -nz)));   // facing the bus
                return spot;
            }
        }
        Location at = l.clone().add(nx * 3.5, 0, nz * 3.5);
        at.setYaw((float) Math.toDegrees(Math.atan2(nx, -nz)));
        return at;
    }

    private String skin(Thing t) {
        String own = cfg().getString("skins." + t.id(), "");
        if (own != null && !own.isBlank()) return own;
        BusModule b = key.busModule();
        BusSpec.Type type = b == null ? null : b.spec().types().get(t.type());
        return type == null || type.skins().isEmpty() ? null : type.skins().get(Math.floorMod(t.id().hashCode(), type.skins().size()));
    }

    /** The owner steps up (only the driver sees them), takes the thing and thanks them; then the week is done. */
    private void handOver(Player p, BusStop here) {
        UUID u = p.getUniqueId();
        Thing t = thing(u);
        step(u, 2);
        ownerScene(p, here, t, () -> finish(p));
    }

    private void ownerScene(Player p, BusStop here, Thing t, Runnable then) {
        UUID u = p.getUniqueId();
        hideOwner(u);
        Location at = beside(p, here);
        Mannequin f = m.haunt().figure(p, at, skin(t), line("owner-name", "<gray>{owner}", "owner", t.owner()));
        owners.put(u, f);
        p.spawnParticle(Particle.WHITE_ASH, at.clone().add(0, 1, 0), 14, 0.25, 0.5, 0.25, 0);
        m.haunt().sound(p, at, Sound.BLOCK_WOOL_STEP, 0.6f, 0.8f);
        key.later(p, 20, () -> {
            if (f.isValid()) f.swingMainHand();
            m.haunt().sound(p, at, Sound.ENTITY_ITEM_PICKUP, 0.7f, 0.8f);
            tell(p, "taken", "<gray>{owner} — 말없이 다가와 그것을 받아 든다.", "owner", t.owner(), "thing", t.name());
        });
        key.speak(p, List.of(f), here, List.of(t.thanks()), 45);
        key.later(p, 135, () -> {
            p.spawnParticle(Particle.WHITE_ASH, at.clone().add(0, 1, 0), 10, 0.25, 0.5, 0.25, 0);
            hideOwner(u);
            if (then != null) then.run();
        });
    }

    private void hideOwner(UUID u) {
        Mannequin f = owners.remove(u);
        if (f == null) return;
        Player p = Bukkit.getPlayer(u);
        if (p != null) m.haunt().remove(p, f);
        else if (f.isValid()) f.remove();
    }

    /** Handed over: the thing is gone, the reward, and now and then a small gift of the kind such a passenger gives. */
    private void finish(Player p) {
        UUID u = p.getUniqueId();
        if (!live(u) || step(u) < 2) return;
        Thing t = thing(u);
        take(p, true);
        if (complete(p).isEmpty() && state(u) != State.DONE) return;
        if (!chance(p, "keepsake.chance", 0.25)) return;
        ItemStack gift = gift(t);
        if (gift == null) return;
        String name = Items.mmName(gift);
        give(p, gift);
        tell(p, "keepsake.line", "<dark_gray>…자리에 무언가 놓여 있다. <gray>({item})", "item", name);
    }

    /** One of the bus's own gifts (bus.yml types.&lt;type&gt;.gifts): the owner's kind, else keepsake.types; null when there is none. */
    private ItemStack gift(Thing t) {
        BusModule b = key.busModule();
        if (b == null) return null;
        List<String> types = new ArrayList<>(List.of(t.type()));
        types.addAll(cfg().getStringList("keepsake.types"));
        for (String id : types) {
            BusSpec.Type type = b.spec().types().get(id);
            if (type == null || type.gifts().isEmpty()) continue;
            BusSpec.Gift g = type.gifts().get(ThreadLocalRandom.current().nextInt(type.gifts().size()));
            ItemStack it = Items.create(g.item(), 1).orElse(null);
            if (it != null) return it;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // every second, the item
    // ------------------------------------------------------------------------------------------

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        beats++;
        if (state(u) == State.OFFERED) {   // begun without its things (should not stay so): from the nearest stop
            if (elapsed(u) >= 5) start(p);
            return;
        }
        if (step(u) >= 2) {   // the hand-over was cut short (a quit, a reload): it is done all the same
            if (elapsed(u) >= 10) finish(p);
            return;
        }
        if (beats % 5 != 0) return;
        take(p, false);
        if (hasThing(p)) {
            without.remove(u);
            waiting.remove(u);
            return;
        }
        if (waiting.contains(u)) {   // their pockets were full: as soon as it fits
            if (hand(p, make(p, thing(u)))) without.remove(u);
            return;
        }
        long now = System.currentTimeMillis();
        long since = without.computeIfAbsent(u, x -> now);
        if (now - since < seconds(u, "return-after", 60) * 1000L) return;
        without.remove(u);
        if (!hand(p, make(p, thing(u)))) return;
        m.haunt().whisper(p, line("back", "<dark_gray>…두고 온 줄 알았던 것이 조수석에 놓여 있다."));
        m.haunt().sound(p, p.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.4f, 0.7f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        ItemStack it = e.getItemDrop().getItemStack();
        if (!isThing(it)) return;
        if (mine(it, p.getUniqueId())) {
            e.setCancelled(true);
            m.haunt().whisper(p, line("drop", "<dark_gray>…남의 것이다. 버릴 수는 없다."));
        } else {
            e.getItemDrop().remove();   // a dead copy: gone
        }
    }

    // The thing is in its driver's pockets and nowhere else: never on the ground, in a chest, a bundle, a frame, a pot,
    // another player's hands or a trade window. So there is one of it, it cannot be passed on for money, and nothing of
    // it is left anywhere once the week is over.

    /** A thing never lies on the ground (a death, a full inventory somewhere, a dead copy thrown out of a window). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent e) {
        if (isThing(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!isThing(e.getItem().getItemStack())) return;
        e.setCancelled(true);
        e.getItem().remove();
    }

    private static boolean bundle(ItemStack it) {
        return it != null && it.getType().name().endsWith("BUNDLE");
    }

    private static boolean own(InventoryType top) {
        return top == InventoryType.CRAFTING || top == InventoryType.CREATIVE;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onStore(InventoryClickEvent e) {
        ItemStack cur = e.getCurrentItem(), cursor = e.getCursor();
        ItemStack swap = e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0 ? e.getWhoClicked().getInventory().getItem(e.getHotbarButton())
                : e.getClick() == ClickType.SWAP_OFFHAND ? e.getWhoClicked().getInventory().getItemInOffHand() : null;
        boolean a = isThing(cur), b = isThing(cursor), c = isThing(swap);
        if (!a && !b && !c) return;
        Inventory top = e.getView().getTopInventory();
        if (own(top.getType())) {
            if (a && bundle(cursor) || b && bundle(cur)) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        // a window that moves things by itself whatever was cancelled (the trade window's shift-click): out of it again
        if (e.getWhoClicked() instanceof Player p) Bukkit.getScheduler().runTask(eco, () -> pullBack(p, top));
    }

    /** Takes every thing out of a window; the live one goes back to its driver's pockets (else it comes back by itself). */
    private void pullBack(Player p, Inventory top) {
        UUID u = p.getUniqueId();
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack it = top.getItem(i);
            if (!isThing(it)) continue;
            top.setItem(i, null);
            if (p.isOnline() && mine(it, u) && !hasThing(p)) p.getInventory().addItem(it);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStore(InventoryDragEvent e) {
        if (isThing(e.getOldCursor()) && !own(e.getView().getTopInventory().getType())) e.setCancelled(true);
    }

    /** Not into a frame or an allay's hands (boarding the bus with it in hand is as ever). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPut(PlayerInteractEntityEvent e) {
        Entity c = e.getRightClicked();
        if (!(c instanceof org.bukkit.entity.ItemFrame) && !(c instanceof org.bukkit.entity.Allay)) return;
        if (isThing(e.getPlayer().getInventory().getItem(e.getHand()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (isThing(e.getPlayerItem())) e.setCancelled(true);
    }

    /** Not into a pot or onto a shelf. */
    @EventHandler(priority = EventPriority.LOW)
    public void onBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || !isThing(e.getItem())) return;
        String type = e.getClickedBlock().getType().name();
        if (type.equals("DECORATED_POT") || type.endsWith("_SHELF")) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline()) take(p, false);
        }, 60L);
    }

    @Override
    protected void start(Player p) {
        UUID u = p.getUniqueId();
        cleanup(u);
        BusStop near = key.nearestStop(p.getLocation());
        if (near == null) {
            p.sendMessage(Text.mm("<yellow>[후속] 이 월드에는 정류장이 없어요."));
            fail(p, "no-stops");
            return;
        }
        begin(p, near);
    }

    @Override
    protected void cleanup(UUID u) {
        hideOwner(u);
        without.remove(u);
        waiting.remove(u);
    }

    @Override
    protected void expired(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p == null) return;   // a copy left with someone offline is dead (another week's): taken when next seen
        take(p, true);
        if (get(u, "thing") != null) m.haunt().whisper(p, line("shelf-quiet", "<dark_gray>…그 물건은 분실물센터 선반으로 갔다."));
    }

    // ------------------------------------------------------------------------------------------
    // admin
    // ------------------------------------------------------------------------------------------

    @Override
    public String status(UUID u) {
        if (!live(u)) return "물건 " + things().size() + "가지 · 다음: 다음 주에 버스를 몰고 정류장 베이에 서기";
        Thing t = thing(u);
        List<Clues.Clue> clues = Owners.decode(get(u, "clues"));
        BusStop found = key.stop(parse(get(u, "found"), -1));
        List<String> out = new ArrayList<>();
        out.add("물건 <white>" + t.name() + "</white> (" + t.id() + ", 주인 " + t.owner() + ")");
        out.add("나온 곳 " + (found == null ? "?" : found.name()));
        if (found != null) {
            List<Clues.Stop> net = net(found.world());
            List<String> names = new ArrayList<>();
            for (Integer id : Owners.fitting(clues, net)) {
                if (id == found.id()) continue;
                BusStop s = key.stop(id);
                names.add((s == null ? "?" : s.name()) + "#" + id);
            }
            List<String> kinds = new ArrayList<>();
            for (Clues.Clue c : clues) kinds.add(c.kind() + (c.word().isEmpty() ? "" : " " + c.word()));
            out.add("단서 " + String.join(" + ", kinds) + " → 맞는 정류장 " + names.size() + "곳: " + String.join(", ", names.subList(0, Math.min(6, names.size()))));
        }
        out.add("틀린 곳 " + wrongs(u).size() + "/" + cfg().getInt("wrong-stops", 3));
        out.add(step(u) >= 2 ? "다음: 건네는 중 (곧 보상)" : "다음: 물건을 지닌 채 버스를 몰고 맞는 정류장 베이에 서기");
        return String.join(" · ", out);
    }

    @Override
    public List<String> scenes() {
        return List.of("물건", "수군거림", "틀림", "주인", "선반");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        List<Thing> pool = things();
        Thing t = get(u, "thing") != null ? thing(u) : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        BusStop near = key.nearestStop(p.getLocation());
        boolean close = near != null && Math.hypot(near.x() - p.getX(), near.z() - p.getZ()) < 24;
        switch (word) {
            case "물건" -> tell(p, "got", "<gray>뒷자리에 <white>{thing}</white> 하나가 남아 있다. 이름표의 이름이 문질러 지워져 있다.", "thing", t.name());
            case "수군거림" -> {
                List<String> lines = new ArrayList<>(List.of(t.murmur()));
                List<Clues.Clue> clues = Owners.decode(get(u, "clues"));
                if (clues.isEmpty() && near != null) {
                    List<Clues.Stop> net = net(near.world());
                    Clues.Stop target = Owners.target(net, near.id(), ThreadLocalRandom.current().nextInt());
                    if (target != null) clues = Owners.clues(target, net, key.tagOrder());
                }
                for (Clues.Clue c : clues) lines.add(clueLine(c));
                key.speak(p, List.of(), close ? near : null, lines, 5);
            }
            case "틀림" -> {
                m.haunt().whisper(p, line("wrong", "<dark_gray>…아무도 나서지 않는다. 여기가 아니다. <gray>({n}/{max})", "n", "1", "max", String.valueOf(cfg().getInt("wrong-stops", 3))));
                key.speak(p, List.of(), close ? near : null, List.of(line("wrong-murmur", "…그 사람은 여기서 안 기다려.")), 30);
            }
            case "주인" -> {
                if (near == null) return false;
                ownerScene(p, near, t, null);
            }
            case "선반" -> p.sendMessage(Text.msg(line("shelf", "<dark_gray>…세 번을 헛걸음했다. 그 물건은 분실물센터 선반으로 갔다.")));
            default -> {
                return false;
            }
        }
        admin.sendMessage(Text.mm("<gray>[연출] " + word + " (" + t.name() + ") → " + p.getName()));
        return true;
    }
}
