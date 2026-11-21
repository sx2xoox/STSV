package dev.stsv.economy.secret.letter;

import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.land.Land;
import dev.stsv.economy.secret.Memory;
import dev.stsv.economy.secret.SecretModule;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Writes the letters of 내일의 나 (letter.yml {@code letters}): gathers what the player did from {@link Memory}, picks
 * the place or item the day's ritual asks for, and fills the pages. A letter is a written book signed by the player
 * themselves and dated tomorrow, marked with its number so reading it can be noticed.
 */
final class LetterBook {

    /** Which letter a book is: 1..5, {@link #BROKEN}, {@link #EPILOGUE}. */
    static final NamespacedKey NUMBER = new NamespacedKey("economy", "letter_no");
    static final int BROKEN = 0;
    static final int EPILOGUE = 9;
    static final String ITEM = "economy:letter_tomorrow";

    /** A written letter and the target its ritual asks for (see {@link Target}). */
    record Written(ItemStack item, Target target) {}

    private final Letters s;
    private final SecretModule m;
    private final EcoPlugin eco;

    LetterBook(Letters s, SecretModule m, EcoPlugin eco) {
        this.s = s;
        this.m = m;
        this.eco = eco;
    }

    private YamlConfiguration cfg() {
        return s.cfg();
    }

    /** Writes letter n (1..5) for this player, then hands it over on the main thread. */
    void write(Player p, int n, boolean repeat, Consumer<Written> then) {
        UUID u = p.getUniqueId();
        Map<String, String> v = base(p, n, repeat);
        ConfigurationSection l = cfg().getConfigurationSection("letters." + n);
        if (l == null) {
            eco.getLogger().warning("[비밀] letter.yml 에 letters." + n + " 이 없습니다");
            return;
        }
        switch (n) {
            case 1 -> m.memory().sales(u, sales -> m.memory().firstLand(u, land -> {
                Memory.Place quit = m.memory().lastQuit(u).orElse(null);
                if (quit != null) {
                    long at = m.memory().lastQuitAt(u);
                    boolean recent = at > 0 && System.currentTimeMillis() - at < 36L * 3_600_000L;
                    v.put("quit", describe(u, quit));
                    v.put("quitline", LetterText.fill(l.getString(recent ? "quit-recent" : "quit-old", ""), v));
                } else v.put("quitline", null);
                v.put("sold", sales.isEmpty() ? null : Items.mmName(sales.getFirst().item()));
                v.put("friend", m.memory().friends(u).stream().filter(f -> !f.equalsIgnoreCase(p.getName())).findFirst().orElse(null));
                Target t = landTarget(p, land);
                if (t.kind() == Target.Kind.CHUNK || t.kind() == Target.Kind.LAND) {
                    v.put("x", String.valueOf(LetterText.round(t.x())));
                    v.put("z", String.valueOf(LetterText.round(t.z())));
                }
                String which = t.kind() == Target.Kind.CHUNK ? "ritual-first" : t.kind() == Target.Kind.LAND ? "ritual-home" : "ritual-none";
                v.put("ritual", LetterText.fill(l.getString(which, ""), v));
                then.accept(new Written(book(p, n, l, v), t));
            }));
            case 2 -> m.memory().mostSold(u, most -> {
                Target t = most.map(Target::item).orElse(Target.anyItem());
                most.ifPresent(id -> v.put("item", Items.mmName(id)));
                v.put("ritual", LetterText.fill(l.getString(most.isPresent() ? "ritual-most" : "ritual-none", ""), v));
                then.accept(new Written(book(p, n, l, v), t));
            });
            case 3 -> {
                v.put("silence", l.getString(m.test(u) ? "silence-test" : "silence-real", "30분"));
                then.accept(new Written(book(p, n, l, v), Target.none()));
            }
            case 4 -> {
                Memory.Place first = m.memory().firstPlace(u).orElseGet(() -> {
                    Location sp = p.getWorld().getSpawnLocation();
                    return new Memory.Place(sp.getWorld().getName(), sp.getBlockX(), sp.getBlockY(), sp.getBlockZ());
                });
                v.put("x", String.valueOf(first.x()));
                v.put("z", String.valueOf(first.z()));
                v.put("guess", m.memory().firstIsGuess(u) ? l.getString("guess", "") : null);
                Target t = Target.near(first.world(), first.x(), first.y(), first.z(), cfg().getInt("rituals.place.radius", 4));
                then.accept(new Written(book(p, n, l, v), t));
            }
            default -> {
                v.put("home", l.getString(s.hasHome(u) ? "home-land" : "home-none", "내 집"));
                v.put("chase", l.getString(m.test(u) ? "chase-test" : "chase-real", "3분"));
                then.accept(new Written(book(p, n, l, v), Target.none()));
            }
        }
    }

    /** The last letter of a broken chain. */
    ItemStack broken(Player p) {
        ConfigurationSection l = cfg().getConfigurationSection("letters.broken");
        return l == null ? null : book(p, BROKEN, l, base(p, 1, false));
    }

    /** The letter the solver writes back (dated today, signed straight). */
    ItemStack epilogue(Player p) {
        ConfigurationSection l = cfg().getConfigurationSection("letters.epilogue");
        if (l == null) return null;
        Map<String, String> v = base(p, 1, false);
        ItemStack it = book(p, EPILOGUE, l, v);
        if (it != null && it.getItemMeta() instanceof BookMeta bm) {
            bm.setTitle(cut(l.getString("title", "어제의 나에게")));
            it.setItemMeta(bm);
        }
        return it;
    }

    private Map<String, String> base(Player p, int n, boolean repeat) {
        Map<String, String> v = new HashMap<>();
        DateTimeFormatter f;
        try {
            f = DateTimeFormatter.ofPattern(cfg().getString("delivery.date-format", "yyyy년 M월 d일"));
        } catch (IllegalArgumentException e) {
            f = DateTimeFormatter.ISO_LOCAL_DATE;
        }
        LocalDate today = LocalDate.now(SecretModule.KST);
        String date = today.plusDays(1).format(f);
        if (repeat) date = date + "\n" + cfg().getString("letters.repeat", "");
        v.put("date", date);
        v.put("today", today.format(f));
        v.put("name", p.getName());
        v.put("sig", signature(p, n));
        return v;
    }

    /** The signature under letter n: their name, a little more wrong each time, and a line that comes apart. */
    String signature(Player p, int n) {
        String line = cfg().getString("signature.lines." + n, "");
        Map<String, String> sv = new HashMap<>();
        sv.put("signame", LetterText.drift(p.getName(), Math.max(0, n - 1), s.seed(p.getUniqueId())));
        sv.put("sigline", line == null || line.isBlank() ? null : line);
        return LetterText.fill(cfg().getString("signature.format", "— {signame}\n{sigline}"), sv);
    }

    private ItemStack book(Player p, int n, ConfigurationSection l, Map<String, String> v) {
        Optional<ItemStack> made = Items.create(ITEM, 1);
        if (made.isEmpty()) {
            eco.getLogger().warning("[비밀] 아이템 " + ITEM + " 이 없습니다");
            return null;
        }
        ItemStack it = made.get();
        if (!(it.getItemMeta() instanceof BookMeta bm)) return it;
        List<Component> pages = new ArrayList<>();
        for (String page : l.getStringList("pages")) pages.add(Text.mm(LetterText.fill(page, v)));
        bm.setAuthor(p.getName());
        bm.setTitle(cut(cfg().getString("delivery.title", "내일")));
        bm.setGeneration(BookMeta.Generation.ORIGINAL);
        bm.pages(pages);
        List<String> lore = new ArrayList<>();
        for (String line : cfg().getStringList("delivery.lore")) lore.add(LetterText.fill(line, v).replace("\n", " "));
        bm.lore(Text.itemLines(lore));
        bm.getPersistentDataContainer().set(NUMBER, PersistentDataType.INTEGER, n);
        it.setItemMeta(bm);
        return it;
    }

    private static String cut(String t) {
        return t.length() > 32 ? t.substring(0, 32) : t;
    }

    /** "Near where": their own land, someone's land, the town, or a world and rounded coordinates. */
    String describe(UUID u, Memory.Place at) {
        World w = Bukkit.getWorld(at.world());
        if (w != null && eco.land() != null) {
            Land land = eco.land().at(new Location(w, at.x(), at.y(), at.z()));
            if (land != null) {
                if (land.allows(u)) return "내 땅";
                if (land.server()) return "마을";
                String owner = Bukkit.getOfflinePlayer(land.owner).getName();
                if (owner != null) return owner + "네 땅";
            }
        }
        String world = w == null ? at.world() : m.core().worlds() != null ? m.core().worlds().displayName(w) : w.getName();
        return world + " " + LetterText.round(at.x()) + ", " + LetterText.round(at.z());
    }

    /** Letter 1: their first land (a chunk), else the land they live on, else the town (or the spawn). */
    private Target landTarget(Player p, Optional<Memory.Place> first) {
        if (first.isPresent()) {
            Memory.Place f = first.get();
            return Target.chunk(f.world(), Math.floorDiv(f.x(), 16), Math.floorDiv(f.z(), 16));
        }
        if (eco.land() != null) {
            Land home = eco.land().store().index().home(p.getUniqueId());
            if (home != null && home.size() > 0) {
                var c = home.chunks().getFirst();
                return Target.land(home.id, c.world(), c.x() * 16 + 8, c.z() * 16 + 8);
            }
            Land town = eco.land().store().index().server();
            if (town != null && town.size() > 0) {
                var c = town.chunks().getFirst();
                return Target.land(town.id, c.world(), c.x() * 16 + 8, c.z() * 16 + 8);
            }
        }
        Location sp = Bukkit.getWorlds().getFirst().getSpawnLocation();
        return Target.near(sp.getWorld().getName(), sp.getBlockX(), sp.getBlockY(), sp.getBlockZ(), 16);
    }
}
