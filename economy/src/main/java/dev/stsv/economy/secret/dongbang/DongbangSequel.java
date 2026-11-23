package dev.stsv.economy.secret.dongbang;

import dev.stsv.core.util.Text;
import dev.stsv.economy.bus.BusModule;
import dev.stsv.economy.bus.BusStop;
import dev.stsv.economy.secret.Sequel;
import dev.stsv.economy.secret.SequelState.State;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 청산인의 업무: the 후속 임무 of 동방해운 (docs/admin/숨은_비밀/동방해운.md "후속 임무"). The company is dissolved and the
 * solver is its liquidator; eleven shareholders went down with the ship and each is still owed a dividend. Once a week a
 * 미지급 배당금 명세서 for one of them comes by mail. It says, without an address, where the family lives: by a real bus stop
 * (its traits, the first sounds of its name, which way from the world's first place). Near that stop a door stands that
 * only the liquidator sees; the statement is handed over there and someone says a few words. Eleven weeks in a fixed
 * order, then a last letter; after that the visits come round again as anniversaries, shorter.
 * <p>
 * Notes of the week: {@code slot} (whose), {@code again}, {@code stop} (its name, for the status line), {@code at}
 * ("world x y z": the ground behind the stop the door is looked for around), {@code door} (the door's block, once found),
 * {@code handed} (the statement was taken: the reward is owed), {@code reissued}. Kept: {@code holders} (the eleven names,
 * fixed the first time). The statement is a mission paper (the notice's item with the week's mark): it is the only thing
 * taken, and only at the door; old ones do nothing and are thrown away when shown at a door.
 */
public final class DongbangSequel extends Sequel {

    /** On a statement: {@link Liquidation#mark}. */
    static final NamespacedKey STATEMENT = new NamespacedKey("economy", "dongbang_statement");
    /** On a door's click box: its viewer. */
    static final NamespacedKey DOOR = new NamespacedKey("economy", "dongbang_door");

    private record Shown(Location at, List<Entity> parts, BlockDisplay lower, Interaction hit) {}

    private final Dongbang s;
    private final Map<UUID, Shown> doors = new HashMap<>();
    private final Set<UUID> playing = new HashSet<>();

    DongbangSequel(Dongbang s) {
        super(s);
        this.s = s;
    }

    @Override
    public String id() {
        return "청산인의 업무";
    }

    // ------------------------------------------------------------------------------------------
    // the eleven
    // ------------------------------------------------------------------------------------------

    private static long seed(UUID u) {
        return u.getMostSignificantBits() * 31 ^ u.getLeastSignificantBits();
    }

    /** The eleven names, fixed the first time they are asked for (the chairs of their meeting without the one not aboard). */
    private List<String> holders(UUID u) {
        String saved = kept(u, "holders", null);
        if (saved != null) {
            String[] w = saved.split("\n", -1);
            if (w.length == Liquidation.HOLDERS) return List.of(w);
        }
        List<String> out = Liquidation.holders(s.names(u, false), s.inquest(u).liar);
        keep(u, "holders", String.join("\n", out));
        return out;
    }

    private Liquidation.Visit visit(UUID u) {
        return Liquidation.visit(Liquidation.order(seed(u)), count(u));
    }

    /** The family of a slot: sequel.families[slot] (relation, memo, lines, again). */
    private ConfigurationSection family(int slot) {
        List<Map<?, ?>> all = cfg().getMapList("families");
        if (all.isEmpty()) return null;
        org.bukkit.configuration.MemoryConfiguration c = new org.bukkit.configuration.MemoryConfiguration();
        all.get(Math.floorMod(slot, all.size())).forEach((k, v) -> c.set(String.valueOf(k), v));
        return c;
    }

    private static String relation(ConfigurationSection f) {
        return f == null ? "유족" : f.getString("relation", "유족");
    }

    // ------------------------------------------------------------------------------------------
    // the statement comes
    // ------------------------------------------------------------------------------------------

    @Override
    protected boolean offer(Player p) {
        UUID u = p.getUniqueId();
        long online = (System.currentTimeMillis() - m.memory().sessionStart(u)) / 1000L;
        if (online < seconds(u, "after-online", 180)) return false;
        start(p);
        return true;
    }

    @Override
    protected void start(Player p) {
        UUID u = p.getUniqueId();
        hide(u);
        playing.remove(u);
        Liquidation.Visit v = visit(u);
        String name = holders(u).get(v.slot());
        set(u, "slot", v.slot());
        if (v.again()) set(u, "again", 1);
        Map<String, String> clue = address(u, v.slot());
        ItemStack paper = statement(u, name, v, clue);
        if (eco.mail() != null) eco.mail().store().send(u, Items.bytes(paper), "dongbang", System.currentTimeMillis());
        else give(p, paper);
        tell(p, "statement.arrived", "<gray>우편함에서 젖은 종이 냄새가 난다. <white>/우편함");
        m.haunt().behind(p, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 0.6f);
    }

    private List<BusStop> stops() {
        BusModule b = eco.bus();
        List<BusStop> out = new ArrayList<>();
        if (b == null) return out;
        for (BusStop st : b.stops()) if (Bukkit.getWorld(st.world()) != null) out.add(st);
        out.sort(Comparator.comparingInt(BusStop::id));
        return out;
    }

    /**
     * Chooses where this slot's family lives and saves it ({@code at}, {@code stop}); returns the clue lines' values
     * ({tag}, {initials}, {where}; a missing one is null and its line is left out).
     */
    private Map<String, String> address(UUID u, int slot) {
        Map<String, String> clue = new HashMap<>();
        List<BusStop> all = stops();
        int i = Liquidation.stopFor(seed(u), slot, all.size());
        World w;
        double x, z;
        int y;
        if (i >= 0) {
            BusStop st = all.get(i);
            w = Bukkit.getWorld(st.world());
            int width = eco.bus().spec().stops().bayWidth();
            double[] a = st.at(0, st.shelterOut(width) + cfg().getDouble("door.behind-blocks", 3.5));
            x = a[0];
            z = a[1];
            y = st.y() + 1;
            set(u, "stop", st.name() + " (#" + st.id() + ")");
            String tag = null;
            for (String t : List.of("port", "sea", "mountain", "forest", "village", "field")) {
                if (!eco.bus().stopTags(st).contains(t)) continue;
                tag = line("clues.tags." + t, "");
                if (!tag.isEmpty()) break;
            }
            clue.put("tag", tag == null || tag.isEmpty() ? line("clues.tags.none", "길가") : tag);
            clue.put("initials", Liquidation.chosung(Liquidation.stopWord(st.name())));
        } else {
            w = s.world();
            Location sp = w.getSpawnLocation();
            double ang = Math.floorMod(seed(u) + slot * 37L, 360L) * Math.PI / 180;
            double far = cfg().getDouble("door.spawn-blocks", 10);
            x = sp.getX() + Math.cos(ang) * far;
            z = sp.getZ() + Math.sin(ang) * far;
            y = sp.getBlockY();
            set(u, "stop", "정류장 없음: 스폰 근처");
            clue.put("tag", line("clues.spawn", "마을 어귀, 처음 발 딛는 자리"));
            clue.put("initials", null);
        }
        set(u, "at", w.getName() + " " + (int) Math.floor(x) + " " + y + " " + (int) Math.floor(z));
        Location sp = w.getSpawnLocation();
        double dx = x - sp.getX(), dz = z - sp.getZ(), d = Math.hypot(dx, dz);
        String world = m.core().worlds() != null ? m.core().worlds().displayName(w) : w.getName();
        clue.put("where", i < 0 || d < 24 ? null : line("clues.where", "{world} 첫 자리에서 {dir} {paces}")
                .replace("{world}", world).replace("{dir}", String.valueOf(Liquidation.direction(dx, dz))).replace("{paces}", Liquidation.paces(d)));
        return clue;
    }

    private ItemStack statement(UUID u, String name, Liquidation.Visit v, Map<String, String> clue) {
        ConfigurationSection f = family(v.slot());
        List<String> template = cfg().getStringList(v.again() ? "statement.lore-again" : "statement.lore");
        if (template.isEmpty()) template = List.of("<gray>동방해운 주식회사 <dark_gray>(청산 중)", "<gray>주주 <white>{name}</white> 귀하 <dark_gray>· 명부 {no}",
                "<gray>수령인: <white>{relation}", "<#6f8a96>송달지: {tag}의 정류장 뒤", "<#6f8a96>정류장 이름: 「{initials}」", "<#6f8a96>{where}",
                "<dark_gray>청산인이 직접 전달할 것 (문 앞에서 들고 우클릭)");
        List<String> lore = new ArrayList<>();
        for (String l : template) {
            String out = fill(l, "tag", clue.get("tag"));
            out = out == null ? null : fill(out, "initials", clue.get("initials"));
            out = out == null ? null : fill(out, "where", clue.get("where"));
            out = out == null ? null : fill(out, "memo", f == null ? null : f.getString("memo"));
            if (out == null) continue;
            lore.add(out.replace("{name}", MiniMessage.miniMessage().escapeTags(name)).replace("{no}", Liquidation.number(v.slot()))
                    .replace("{relation}", relation(f)).replace("{amount}", f == null ? "-" : f.getString("amount", "-"))
                    .replace("{round}", String.valueOf(v.round())));
        }
        ItemStack it = s.paper(Dongbang.NOTICE, line(v.again() ? "statement.name-again" : "statement.name", "<#b9c4c9>미지급 배당금 명세서"), lore);
        it.editMeta(meta -> meta.getPersistentDataContainer().set(STATEMENT, PersistentDataType.STRING, Liquidation.mark(week(u), v.slot())));
        return it;
    }

    /** Fills one slot; null (the line is left out) when the line has the slot and there is no value. */
    private static String fill(String l, String key, String value) {
        String slot = "{" + key + "}";
        if (!l.contains(slot)) return l;
        return value == null || value.isEmpty() ? null : l.replace(slot, value);
    }

    private static String mark(ItemStack it) {
        return it == null || it.isEmpty() || !it.hasItemMeta() ? null : it.getItemMeta().getPersistentDataContainer().get(STATEMENT, PersistentDataType.STRING);
    }

    /** Every statement in their bag goes (mission papers: this week's after it was handed over, or old ones). */
    private static void purge(Player p) {
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length; i++) if (mark(inv[i]) != null) p.getInventory().setItem(i, null);
    }

    private static boolean carries(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (mark(it) != null) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // the door
    // ------------------------------------------------------------------------------------------

    private static Location place(String saved) {
        if (saved == null) return null;
        String[] w = saved.split(" ");
        try {
            World world = Bukkit.getWorld(w[0]);
            return world == null || w.length < 4 ? null : new Location(world, Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    protected void tick(Player p) {
        UUID u = p.getUniqueId();
        if (get(u, "slot") == null) return;
        if (get(u, "handed") != null) {
            if (!playing.contains(u)) settle(p);   // they left while the family was speaking: the reward is still theirs
            return;
        }
        Location at = place(get(u, "at"));
        if (at == null) return;
        boolean same = at.getWorld() == p.getWorld();
        double d = same ? Math.hypot(p.getX() - at.getX(), p.getZ() - at.getZ()) : Double.MAX_VALUE;
        double r = cfg().getDouble("door.show-radius", 40);
        Shown sh = doors.get(u);
        if (sh == null && d <= r && at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            show(p, at);
            accept(p);
        } else if (sh != null && d > r + 16) {
            hide(u);
        }
    }

    /** The door for this player, on the ground by {@code around} (found once, then always there). */
    private void show(Player p, Location around) {
        UUID u = p.getUniqueId();
        hide(u);
        Location at = place(get(u, "door"));
        if (at == null) {
            at = ground(around);
            set(u, "door", at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ());
        }
        doors.put(u, build(p, at, around, plate(u)));
        m.haunt().sound(p, at, Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.5f, 0.6f);
        m.haunt().whisper(p, line("door.near", "<#6f8a96>…정류장 뒤에, 아까는 없던 문이 하나 서 있다."));
    }

    private String plate(UUID u) {
        int slot = (int) getLong(u, "slot", 0);
        return line("door.plate", "<gray>{name}").replace("{name}", MiniMessage.miniMessage().escapeTags(holders(u).get(Math.floorMod(slot, Liquidation.HOLDERS))))
                .replace("{relation}", relation(family(slot)));
    }

    /** A door standing alone (two halves), a mat before it, a name plate over it, a click box: only the viewer sees them. */
    private Shown build(Player p, Location at, Location toward, String plate) {
        double dx = toward.getX() - at.getX(), dz = toward.getZ() - at.getZ();
        if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) {
            dx = p.getX() - at.getX();
            dz = p.getZ() - at.getZ();
        }
        // the door's front looks away from the road: one walks round the shelter and finds it facing them
        String facing = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "west" : "east") : (dz > 0 ? "north" : "south");
        String wood = cfg().getString("door.block", "minecraft:spruce_door");
        List<Entity> parts = new ArrayList<>();
        BlockDisplay lower = m.haunt().block(p, at, data(wood + "[half=lower,facing=" + facing + "]", "minecraft:spruce_door[half=lower]"));
        parts.add(lower);
        parts.add(m.haunt().block(p, at.clone().add(0, 1, 0), data(wood + "[half=upper,facing=" + facing + "]", "minecraft:spruce_door[half=upper]")));
        TextDisplay name = m.haunt().text(p, at.clone().add(0.5, 2.3, 0.5), plate);
        name.setViewRange(0.25f);
        parts.add(name);
        Interaction hit = at.getWorld().spawn(at.clone().add(0.5, 0, 0.5), Interaction.class, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setInteractionWidth(1.2f);
            e.setInteractionHeight(2.1f);
            e.setResponsive(true);
            e.getPersistentDataContainer().set(DOOR, PersistentDataType.STRING, p.getUniqueId().toString());
        });
        p.showEntity(eco, hit);
        return new Shown(at, parts, lower, hit);
    }

    private static BlockData data(String wanted, String fallback) {
        try {
            return Bukkit.createBlockData(wanted);
        } catch (IllegalArgumentException e) {
            return Bukkit.createBlockData(fallback);
        }
    }

    /** Dry ground with two blocks of air near a spot (a few blocks around, a few up and down); else the highest block there. */
    private static Location ground(Location around) {
        World w = around.getWorld();
        int ax = around.getBlockX(), ay = around.getBlockY(), az = around.getBlockZ();
        for (int r = 0; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5}) {
                        Block b = w.getBlockAt(ax + dx, ay + dy, az + dz);
                        if (b.isPassable() && !b.isLiquid() && b.getRelative(0, 1, 0).isPassable() && !b.getRelative(0, 1, 0).isLiquid()
                                && b.getRelative(0, -1, 0).getType().isSolid()) return b.getLocation();
                    }
                }
            }
        }
        return new Location(w, ax, w.getHighestBlockYAt(ax, az) + 1, az);
    }

    private void hide(UUID u) {
        Shown sh = doors.remove(u);
        if (sh == null) return;
        Player p = Bukkit.getPlayer(u);
        for (Entity e : sh.parts) {
            if (p != null) m.haunt().remove(p, e);
            else if (e.isValid()) e.remove();
        }
        if (sh.hit.isValid()) sh.hit.remove();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDoor(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction x)) return;
        String owner = x.getPersistentDataContainer().get(DOOR, PersistentDataType.STRING);
        if (owner == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (e.getHand() != EquipmentSlot.HAND || !owner.equals(p.getUniqueId().toString())) return;
        Shown sh = doors.get(p.getUniqueId());
        if (sh == null || !sh.hit.equals(x)) return;
        if (!live(p.getUniqueId())) {   // an admin's look (연출 문앞)
            m.haunt().sound(p, sh.at, Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.5f, 0.7f);
            return;
        }
        if (may(p)) knock(p, sh);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDoorHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Interaction x && x.getPersistentDataContainer().has(DOOR)) e.setCancelled(true);
    }

    /** At the door: with this week's statement in hand it is handed over; anything else is told so and nothing is taken. */
    private void knock(Player p, Shown sh) {
        UUID u = p.getUniqueId();
        if (get(u, "handed") != null || playing.contains(u)) return;
        int slot = (int) getLong(u, "slot", 0);
        ItemStack hand = p.getInventory().getItemInMainHand();
        String mark = mark(hand);
        if (mark == null) {
            m.haunt().sound(p, sh.at, Sound.BLOCK_WOOD_HIT, 0.7f, 0.7f);
            if (!carries(p) && get(u, "reissued") == null && cfg().getBoolean("statement.reissue", true)) {
                // lost on the way (or still in the mailbox: then there are two, and both go when one is handed over)
                set(u, "reissued", 1);
                give(p, statement(u, holders(u).get(slot), new Liquidation.Visit(slot, get(u, "again") != null, false, 0), clueAgain(u)));
                tell(p, "statement.reissued", "<#6f8a96>…문틈으로 종이 한 장이 밀려 나온다. 명세서의 사본이다.");
            } else {
                m.haunt().whisper(p, line("door.need", "<#6f8a96>…문은 열리지 않는다. 명세서를 손에 들어야 한다."));
            }
            return;
        }
        if (!mark.equals(Liquidation.mark(week(u), slot))) {
            p.getInventory().setItemInMainHand(null);   // an old week's paper: worth nothing, in the way
            m.haunt().whisper(p, line("door.stale", "<#6f8a96>…지난 명세서다. 종이가 손에서 젖어 풀어진다."));
            return;
        }
        purge(p);
        set(u, "handed", System.currentTimeMillis());
        accept(p);
        step(u, 2);
        scene(p, sh, slot, get(u, "again") != null, true);
    }

    /** The clue values again for a copy (the stop may be gone by now: then only what is still known). */
    private Map<String, String> clueAgain(UUID u) {
        Map<String, String> clue = new HashMap<>();
        clue.put("tag", line("clues.copy", "(사본) 처음 받은 곳과 같음"));
        clue.put("initials", null);
        clue.put("where", null);
        return clue;
    }

    /** The knock, the door opening a hand's width, the family's lines one after the other; then (real) the week is settled. */
    private void scene(Player p, Shown sh, int slot, boolean again, boolean real) {
        UUID u = p.getUniqueId();
        playing.add(u);
        ConfigurationSection f = family(slot);
        List<String> lines = f == null ? List.of() : f.getStringList(again ? "again" : "lines");
        if (lines.isEmpty()) lines = List.of(again ? "올해도 오셨군요. 고맙습니다." : "…이걸 이제야 받네요. 고맙습니다.");
        String say = line("door.say", "<gray>{relation}<dark_gray>: <#b9c4c9>«{line}»");
        Location c = sh.at.clone().add(0.5, 1, 0.5);
        m.haunt().sound(p, c, Sound.BLOCK_WOOD_HIT, 0.9f, 0.8f);
        later(p, 8, () -> m.haunt().sound(p, c, Sound.BLOCK_WOOD_HIT, 0.9f, 0.8f));
        later(p, 40, () -> {
            m.haunt().sound(p, c, Sound.BLOCK_WOODEN_DOOR_OPEN, 0.7f, 0.8f);
            if (sh.lower.isValid()) {
                for (Entity e : sh.parts) {
                    if (e instanceof BlockDisplay d && d.getBlock() instanceof org.bukkit.block.data.type.Door door) {
                        door.setOpen(true);
                        d.setBlock(door);
                    }
                }
            }
        });
        long t = 60, gap = Math.max(20, cfg().getLong("door.line-ticks", 70));
        for (String l : lines) {
            later(p, t, () -> p.sendMessage(Text.mm(say.replace("{relation}", relation(f)).replace("{line}", l))));
            t += gap;
        }
        later(p, t, () -> {
            m.haunt().sound(p, c, Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.7f, 0.7f);
            p.spawnParticle(Particle.DRIPPING_WATER, c, 12, 0.3, 0.6, 0.3, 0);
            playing.remove(u);
            if (real) settle(p);
            else hide(u);
        });
    }

    private void later(Player p, long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (p.isOnline() && playing.contains(p.getUniqueId())) r.run();
        }, ticks);
    }

    /** The statement was handed over: the week is done, the door goes, and after the eleventh the last letter comes. */
    private void settle(Player p) {
        UUID u = p.getUniqueId();
        if (!live(u)) return;
        Liquidation.Visit v = visit(u);
        hide(u);
        complete(p);
        if (!v.last()) return;
        ItemStack letter = finalLetter(p);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (eco.mail() != null) eco.mail().deliver(u, letter, "dongbang");
            else if (p.isOnline()) give(p, letter);
            if (p.isOnline()) {
                tell(p, "final.arrived", "<#6f8a96>…열한 번째 문이 닫혔다. 우편함에 마지막 편지가 와 있다.");
                m.haunt().sound(p, p.getLocation(), Sound.EVENT_RAID_HORN, 0.3f, 0.5f);
            }
        }, 20L * Math.max(1, cfg().getLong("final.after-seconds", 5)));
    }

    private ItemStack finalLetter(Player p) {
        List<String> lore = new ArrayList<>();
        List<String> template = cfg().getStringList("final.lore");
        if (template.isEmpty()) template = List.of("<gray>미지급 배당금 11건, 전부 전달됨.", "<gray>청산인 <white>{player}</white> 귀하, 남은 업무 없음.", "<#6f8a96><i>모두 무사히 받았습니다");
        for (String l : template) lore.add(l.replace("{player}", p.getName()));
        return s.paper(Dongbang.NOTICE, line("final.name", "<#b9c4c9>청산 종결 보고"), lore);
    }

    // ------------------------------------------------------------------------------------------
    // the framework's side
    // ------------------------------------------------------------------------------------------

    @Override
    protected void cleanup(UUID u) {
        hide(u);
        playing.remove(u);
    }

    @Override
    protected void expired(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) purge(p);   // offline: the paper stays where it is and is worth nothing (an old mark)
    }

    @Override
    public String status(UUID u) {
        Liquidation.Visit v = visit(u);
        String progress = "전달 " + Math.min(count(u), Liquidation.HOLDERS) + "/" + Liquidation.HOLDERS + (count(u) >= Liquidation.HOLDERS ? " (기일 방문 " + v.round() + "바퀴째)" : "");
        if (!live(u) || get(u, "slot") == null) return progress + " · 받은 명세서 없음 (접속 " + cfg().getLong("after-online", 180) + "초 뒤에 옴) · 다음 주주 " + holders(u).get(v.slot());
        int slot = (int) getLong(u, "slot", 0);
        String next = get(u, "handed") != null ? "명세서를 건넴: 유족의 말이 끝나면 완료"
                : (state(u) == State.OFFERED ? "우편함에서 명세서를 꺼내 " : "") + "그 자리 근처의 문 앞에서 명세서를 손에 들고 우클릭";
        return progress + " · 이번 주 주주 " + holders(u).get(Math.floorMod(slot, Liquidation.HOLDERS)) + " (" + relation(family(slot)) + ")"
                + " · 정류장 " + get(u, "stop", "-") + " · 문 " + get(u, "door", "아직 안 생김: " + get(u, "at", "-") + " 의 " + cfg().getInt("door.show-radius", 40) + "칸 안에 오면 생김")
                + " · 다음: " + next;
    }

    @Override
    public List<String> scenes() {
        return List.of("명세서", "문앞", "유족", "마지막편지");
    }

    @Override
    public boolean scene(CommandSender admin, Player p, String word) {
        UUID u = p.getUniqueId();
        Liquidation.Visit v = visit(u);
        switch (word) {
            case "명세서" -> {
                // a sample without a mark: it opens no door
                Map<String, String> clue = new HashMap<>();
                clue.put("tag", line("clues.tags.sea", "바닷가"));
                clue.put("initials", "ㄷㄷ ㅇ");
                clue.put("where", null);
                ItemStack it = statement(u, holders(u).get(v.slot()), v, clue);
                it.editMeta(meta -> meta.getPersistentDataContainer().remove(STATEMENT));
                give(p, it);
            }
            case "문앞", "유족" -> {
                if (live(u) && get(u, "slot") != null) {
                    admin.sendMessage(Text.msg("<gray>이번 주 임무가 진행 중이에요. 진짜 문은 상태 줄의 자리에 있어요 (연출은 임무가 없을 때만)"));
                    return true;
                }
                hide(u);
                Location l = p.getLocation();
                Location at = ground(l.clone().add(l.getDirection().setY(0).normalize().multiply(3)));
                Shown sh = build(p, at, l, line("door.plate", "<gray>{name}").replace("{name}", MiniMessage.miniMessage().escapeTags(holders(u).get(v.slot())))
                        .replace("{relation}", relation(family(v.slot()))));
                doors.put(u, sh);
                if (word.equals("유족")) scene(p, sh, v.slot(), v.again(), false);
                else Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (doors.get(u) == sh && !live(u)) hide(u);
                }, 20L * 30);
            }
            case "마지막편지" -> give(p, finalLetter(p));
            default -> {
                return false;
            }
        }
        return true;
    }
}
