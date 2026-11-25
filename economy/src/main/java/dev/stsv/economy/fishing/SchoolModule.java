package dev.stsv.economy.fishing;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.core.vehicle.Hauled;
import dev.stsv.core.vehicle.NetHaul;
import dev.stsv.core.vehicle.VehicleModule;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.dock.Dock;
import dev.stsv.economy.dock.DockLayout;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 어군 조업 (README "어군 조업", fish.yml schools): fish schools on the sea. A school is a place (cached x, z and its water's
 * surface), one 떼물고기 species, how many are left and until when; never an entity. Once a second ({@link #tick}) the schools
 * that ran out of time scatter, a world short of schools gets a new one (in loaded chunks only, near someone sitting in a boat
 * or near a 항구 하역장 someone is at: never on land, never loading a chunk), who sees which school is worked out, every boat
 * driver with a 어군 탐지기 gets the nearest school in its range on the action bar, and every few seconds the harbours' 어황판
 * are written again. Four times a second the seen schools are drawn for those who see them only (spray on the water, fish
 * jumping, gulls circling: particles, no entities).
 * <p>
 * A boat's net (the core's 작업기, {@link FishingModule}'s net hook) that comes up inside a school takes {@link SchoolSpec#haulCount}
 * of its fish into the hold (what fits by weight and slots) instead of the one fish of the water: the school shrinks, and
 * scatters when empty ("떼가 흩어졌어요"). Any number of boats can work one school. A net catch never counts in a 낚시대회.
 */
public final class SchoolModule extends EcoModule {

    /** A school: where (its water's surface y), what, how many left of how many, which sea, until when (ms). */
    static final class School {
        final int id;
        final String world, species, band;
        final double x, z, price;
        final int y, full;
        final long until;
        int left;
        List<Player> seenBy = List.of();
        double phase;

        School(int id, String world, double x, int y, double z, String species, String band, double price, int full, long until) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.species = species;
            this.band = band;
            this.price = price;
            this.full = full;
            this.left = full;
            this.until = until;
        }
    }

    /** Where a school scattered (emptied), kept for the core's spot rest: no new school comes up near it meanwhile. */
    private record Gone(String world, double x, double z, long until) {}

    /** The core's net spot rest (ToolRules.Spots.REST, 10 minutes): a place a boat emptied stays spent that long. */
    static final long SPOT_REST_MS = 10 * 60 * 1000L;
    /** How long the detector keeps off the action bar after a haul (the core shows the catch there). */
    static final long QUIET_MS = 3000;
    /** How often the 어황판 is written again (seconds). */
    static final int BOARD_EVERY = 5;

    private SchoolSpec spec = SchoolSpec.NONE;
    private final Map<String, List<School>> schools = new HashMap<>();
    private final Map<String, Long> lastSpawn = new HashMap<>();
    private final List<Gone> gone = new ArrayList<>();
    private final Map<UUID, Long> quiet = new HashMap<>();
    /** dock name -> its 어황판 (a text display that is not saved: written again while its chunk is loaded) */
    private final Map<String, UUID> boards = new HashMap<>();
    private final Map<String, double[]> boardAt = new HashMap<>();
    private final Random rnd = new Random();
    private int seq, ticks;

    public SchoolModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-fish-schools";
    }

    @Override
    protected void start() {
        load();
        every(20L, this::tick);
        every(5L, this::draw);
        eco.onReset(() -> {
            schools.clear();
            gone.clear();
        });
    }

    @Override
    public void reload() {
        load();
        boardAt.clear();
    }

    @Override
    protected void stop() {
        for (UUID u : boards.values()) {
            Entity e = Bukkit.getEntity(u);
            if (e != null) e.remove();
        }
        boards.clear();
        schools.clear();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        FishSpec fish = eco.fishing() == null ? null : eco.fishing().spec();
        spec = SchoolSpec.parse(eco.data("fish.yml").getConfigurationSection("schools"), fish, problems);
        problems.forEach(p -> eco.getLogger().warning("[fish.yml] " + p));
    }

    public SchoolSpec spec() {
        return spec;
    }

    // ------------------------------------------------------------------------------------------
    // Once a second
    // ------------------------------------------------------------------------------------------

    private void tick() {
        long now = now();
        ticks++;
        gone.removeIf(g -> g.until() < now);
        quiet.values().removeIf(t -> t < now);
        for (List<School> l : schools.values()) l.removeIf(s -> s.until < now || s.left <= 0);
        if (!spec.enabled()) {
            schools.clear();
            return;
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getPlayers().isEmpty()) continue;
            List<School> here = schools.computeIfAbsent(w.getName(), k -> new ArrayList<>());
            // 태풍 (사건): no new schools while it blows (SeaEvents.stormy); the ones out there stay till they end
            if (!SeaEvents.stormy() && here.size() < spec.maxPerWorld() && now - lastSpawn.getOrDefault(w.getName(), 0L) >= spec.spawnEvery() * 1000L) {
                lastSpawn.put(w.getName(), now);
                spawn(w, null);
            }
            double see = spec.see(w.getTime());
            for (School s : here) {
                List<Player> by = new ArrayList<>();
                for (Player p : w.getPlayers()) {
                    Location l = p.getLocation();
                    if (sq(l.getX() - s.x, l.getZ() - s.z) <= see * see) by.add(p);
                }
                s.seenBy = by;
            }
        }
        detectors(now);
        if (ticks % BOARD_EVERY == 0) boards();
    }

    private static double sq(double dx, double dz) {
        return dx * dx + dz * dz;
    }

    /** The school whose circle (x, z) is in, or null. */
    School at(String world, double x, double z) {
        List<School> l = schools.get(world);
        if (l == null) return null;
        double r2 = spec.radius() * spec.radius();
        School best = null;
        double bd = Double.MAX_VALUE;
        for (School s : l) {
            double d = sq(s.x - x, s.z - z);
            if (s.left > 0 && d <= r2 && d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /** The distance (x, z) to the nearest 항구 하역장 of that world, or -1 (none). */
    private double harbourDistance(String world, double x, double z) {
        double best = -1;
        if (eco.docks() == null) return best;
        for (Dock d : eco.docks().docks()) {
            if (d.kind() != DockLayout.Kind.PORT || !d.world().equals(world)) continue;
            double dist = Math.hypot(d.x() - x, d.z() - z);
            if (best < 0 || dist < best) best = dist;
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------
    // A new school
    // ------------------------------------------------------------------------------------------

    /**
     * Tries to make a school in this world: near someone sitting in a boat (three times as likely) or a harbour someone is near, on
     * open water of a sea biome in a loaded chunk. species: that 떼물고기 (an admin's), else the sea's pick. The school, or null.
     */
    School spawn(World w, String species) {
        List<double[]> around = new ArrayList<>();   // x, z, min, max
        VehicleModule v = core.vehicles();
        for (Player p : w.getPlayers()) {
            if (v != null && v.ridingBoat(p)) {
                double[] c = {p.getLocation().getX(), p.getLocation().getZ(), spec.spawnMin(), spec.spawnMax()};
                around.add(c);
                around.add(c);
                around.add(c);
            }
        }
        if (eco.docks() != null) {
            for (Dock d : eco.docks().docks()) {
                if (d.kind() != DockLayout.Kind.PORT || !d.world().equals(w.getName())) continue;
                for (Player p : w.getPlayers()) {
                    if (sq(p.getLocation().getX() - d.x(), p.getLocation().getZ() - d.z()) <= sq(spec.harbourMax() + 128, 0)) {
                        around.add(new double[]{d.x(), d.z(), spec.harbourMin(), spec.harbourMax()});
                        break;
                    }
                }
            }
        }
        if (around.isEmpty()) return null;
        for (int t = 0; t < 8; t++) {
            double[] c = around.get(rnd.nextInt(around.size()));
            double a = rnd.nextDouble() * Math.PI * 2, d = c[2] + rnd.nextDouble() * (c[3] - c[2]);
            School s = spawnAt(w, c[0] + Math.cos(a) * d, c[1] + Math.sin(a) * d, species, true);
            if (s != null) return s;
        }
        return null;
    }

    /** A school at (x, z) if that is open water (a sea biome unless `biome` is false), apart from the others; null if not. */
    School spawnAt(World w, double x, double z, String species, boolean biome) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int y = surface(w, bx, bz);
        if (y == Integer.MIN_VALUE) return null;
        if (biome && !spec.biome(w.getBiome(bx, y, bz).getKey().asString())) return null;
        // open water all round the circle (no school on a beach or in a pond)
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int px = (int) Math.floor(x + Math.cos(a) * spec.radius()), pz = (int) Math.floor(z + Math.sin(a) * spec.radius());
            if (surface(w, px, pz) != y) return null;
        }
        for (School o : schools.getOrDefault(w.getName(), List.of())) if (sq(o.x - x, o.z - z) < spec.apart() * spec.apart()) return null;
        for (Gone g : gone) if (g.world().equals(w.getName()) && sq(g.x() - x, g.z() - z) < spec.apart() * spec.apart()) return null;
        SchoolSpec.Band band = spec.band(harbourDistance(w.getName(), x, z));
        if (band == null) return null;
        String fish = species != null ? species : SchoolSpec.pick(band, rnd);
        FishSpec.Species s = fish == null || eco.fishing() == null ? null : eco.fishing().spec().species().get(fish);
        if (s == null) return null;
        double value = band.minValue() + rnd.nextDouble() * (band.maxValue() - band.minValue());
        School school = new School(++seq, w.getName(), bx + 0.5, y, bz + 0.5, s.id(), band.id(), s.price(), SchoolSpec.amount(value, s.price()),
                now() + spec.lifetime() * 1000L);
        schools.computeIfAbsent(w.getName(), k -> new ArrayList<>()).add(school);
        return school;
    }

    /**
     * The y of the water surface at a column in a loaded chunk, if it is open sea water (water at least two deep, at sea level), else
     * Integer.MIN_VALUE. Never loads a chunk.
     */
    private static int surface(World w, int x, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return Integer.MIN_VALUE;
        int y = w.getHighestBlockYAt(x, z);
        if (Math.abs(y - (w.getSeaLevel() - 1)) > 1) return Integer.MIN_VALUE;
        if (w.getBlockAt(x, y, z).getType() != Material.WATER || w.getBlockAt(x, y - 1, z).getType() != Material.WATER) return Integer.MIN_VALUE;
        return y;
    }

    // ------------------------------------------------------------------------------------------
    // The net in a school
    // ------------------------------------------------------------------------------------------

    /**
     * What a boat's net brings up from a school (FishingModule's net hook asks first): null = no school here (or the boat does not
     * fish schools): the net's usual one fish. The catch is one stack of the school's fish, as many as fit in the hold.
     */
    ItemStack haul(Player driver, Block water, NetHaul.Boat boat) {
        if (!spec.enabled()) return null;
        SchoolSpec.Boat b = spec.boats().get(boat.item());
        if (b == null || b.haul() <= 0) return null;
        School s = at(water.getWorld().getName(), water.getX() + 0.5, water.getZ() + 0.5);
        if (s == null) return null;
        FishSpec.Species sp = eco.fishing().spec().species().get(s.species);
        if (sp == null) return null;
        SchoolSpec.Band band = spec.band(s.band);
        int n = SchoolSpec.haulCount(b.haul(), band.bonus(), s.price, s.left);
        ItemStack one = Items.create(sp.item(), 1).orElse(null);
        if (one == null || n <= 0) return null;
        n = Math.min(n, room(boat.id(), one));
        quiet.put(driver.getUniqueId(), now() + QUIET_MS);
        if (n <= 0) return one;   // the hold is full: the core says so (nothing goes in), the school keeps its fish
        s.left -= n;
        eco.jobs().rewardAction(driver, "trap", sp.item(), n * s.price / spec.xpValue());
        if (s.left <= 0) scatter(s);
        one.setAmount(n);
        return one;
    }

    /** How many of this item fit in the boat's hold now, by weight and by slots (the core puts in what fits). */
    private int room(long vehicle, ItemStack one) {
        VehicleModule v = core.vehicles();
        Hauled h = v == null ? null : v.hauled(vehicle);
        if (h == null) return SchoolSpec.MAX_HAUL;
        int slots = 0;
        int max = one.getMaxStackSize();
        for (ItemStack c : h.contents()) {
            if (c == null || c.isEmpty()) slots += max;
            else if (c.isSimilar(one)) slots += Math.max(0, max - c.getAmount());
            if (slots >= SchoolSpec.MAX_HAUL) break;
        }
        int n = Math.min(slots, SchoolSpec.MAX_HAUL);
        if (v.weighs()) {
            double w = v.weightOf(one);
            if (w > 0) n = Math.min(n, (int) Math.floor((h.payload() - h.weight()) / w + 1e-9));
        }
        return Math.max(0, n);
    }

    /** A net came up with something for this driver (the core shows it on the action bar): the detector keeps off it a moment. */
    void hauled(Player driver) {
        quiet.put(driver.getUniqueId(), now() + QUIET_MS);
    }

    /** A school emptied: gone, its place kept spent, everyone near told. */
    private void scatter(School s) {
        List<School> l = schools.get(s.world);
        if (l != null) l.remove(s);
        gone.add(new Gone(s.world, s.x, s.z, now() + SPOT_REST_MS));
        World w = Bukkit.getWorld(s.world);
        if (w == null) return;
        FishSpec.Species sp = eco.fishing().spec().species().get(s.species);
        Component msg = Lang.mm("school.gone", "fish", sp == null ? s.species : sp.name());
        double r = spec.radius() + 24;
        for (Player p : w.getPlayers()) {
            if (sq(p.getLocation().getX() - s.x, p.getLocation().getZ() - s.z) > r * r) continue;
            p.sendMessage(Lang.msg("school.gone", "fish", sp == null ? s.species : sp.name()));
            HudModule.flash(p, msg, 2500);
        }
        w.spawnParticle(Particle.SPLASH, s.x, s.y + 1, s.z, 40, spec.radius() / 2, 0.2, spec.radius() / 2, 0.2);
    }

    // ------------------------------------------------------------------------------------------
    // The 어군 탐지기
    // ------------------------------------------------------------------------------------------

    private void detectors(long now) {
        VehicleModule v = core.vehicles();
        if (v == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (quiet.containsKey(p.getUniqueId())) continue;
            VehicleModule.Driven d = v.drivenBy(p);
            if (d == null) continue;
            SchoolSpec.Boat b = spec.boats().get(d.itemKey());
            if (b == null || b.show() == SchoolSpec.Show.NONE) continue;
            HudModule.flash(p, detector(d, b), 1500);
        }
    }

    /** The detector's line for a boat: the nearest school in its range (its arrow, and by the boat: distance, size, fish). */
    private Component detector(VehicleModule.Driven d, SchoolSpec.Boat b) {
        School best = null;
        double bd = b.detect() * b.detect();
        for (School s : schools.getOrDefault(d.world().getName(), List.of())) {
            double q = sq(s.x - d.x(), s.z - d.z());
            if (q <= bd) {
                bd = q;
                best = s;
            }
        }
        if (best == null) return Lang.mm("school.detect-none", "range", (int) b.detect());
        FishSpec.Species sp = eco.fishing().spec().species().get(best.species);
        String fish = sp == null ? best.species : sp.name();
        String size = spec.sizeWord(best.left * best.price);
        if (Math.sqrt(bd) <= spec.radius()) return Lang.mm("school.detect-on", "fish", fish, "size", size, "left", best.left);
        String arrow = SchoolSpec.ARROWS.get(SchoolSpec.arrow(d.yaw(), best.x - d.x(), best.z - d.z()));
        int dist = (int) Math.round(Math.sqrt(bd));
        return switch (b.show()) {
            case FULL -> Lang.mm("school.detect-full", "arrow", arrow, "distance", dist, "fish", fish, "size", size, "left", best.left);
            case DISTANCE -> Lang.mm("school.detect-distance", "arrow", arrow, "distance", dist);
            default -> Lang.mm("school.detect-direction", "arrow", arrow);
        };
    }

    // ------------------------------------------------------------------------------------------
    // Seen from afar
    // ------------------------------------------------------------------------------------------

    private static final Particle.DustOptions GULL = new Particle.DustOptions(Color.fromRGB(0xF4F4F0), 1.1f);
    private static final Particle.DustOptions GULL_WING = new Particle.DustOptions(Color.fromRGB(0xBFC4C8), 0.8f);

    /** Four times a second: each school, for those who see it only (spray, fish jumping, gulls). */
    private void draw() {
        for (List<School> l : schools.values()) {
            for (School s : l) {
                if (s.seenBy.isEmpty()) continue;
                s.phase += 0.12;
                double r = spec.radius();
                double top = s.y + 1.05;
                FishSpec.Species sp = eco.fishing() == null ? null : eco.fishing().spec().species().get(s.species);
                ItemStack jumper = sp == null ? null : Items.create(sp.item(), 1).orElse(null);
                boolean jump = jumper != null && rnd.nextDouble() < 0.6;
                double jx = s.x + (rnd.nextDouble() * 2 - 1) * r * 0.7, jz = s.z + (rnd.nextDouble() * 2 - 1) * r * 0.7;
                boolean sound = rnd.nextDouble() < 0.12;
                for (Player p : s.seenBy) {
                    if (!p.isOnline() || !p.getWorld().getName().equals(s.world)) continue;
                    // the water boils: spray and bubbles all over the school
                    p.spawnParticle(Particle.SPLASH, s.x, top, s.z, 10, r * 0.55, 0.05, r * 0.55, 0.1);
                    p.spawnParticle(Particle.BUBBLE_POP, s.x, top, s.z, 6, r * 0.5, 0.02, r * 0.5, 0.02);
                    p.spawnParticle(Particle.FISHING, s.x, top, s.z, 3, r * 0.5, 0, r * 0.5, 0.01);
                    // a fish jumps out and falls back (its item flies up and drops)
                    if (jump) {
                        p.spawnParticle(Particle.ITEM, jx, top, jz, 0, (rnd.nextDouble() - 0.5) * 0.3, 1, (rnd.nextDouble() - 0.5) * 0.3, 0.45, jumper);
                        p.spawnParticle(Particle.SPLASH, jx, top, jz, 8, 0.3, 0.05, 0.3, 0.1);
                    }
                    // gulls circling above
                    for (int g = 0; g < 3; g++) {
                        double a = s.phase * (g % 2 == 0 ? 1 : -0.8) + g * 2.1;
                        double gr = r * (0.5 + 0.2 * g);
                        double gx = s.x + Math.cos(a) * gr, gz = s.z + Math.sin(a) * gr, gy = s.y + 7 + g * 1.5 + Math.sin(s.phase * 2 + g) * 0.4;
                        double flap = Math.sin(s.phase * 9 + g) * 0.25;
                        double wx = -Math.sin(a) * 0.45, wz = Math.cos(a) * 0.45;   // across the way it flies
                        p.spawnParticle(Particle.DUST, gx, gy, gz, 1, 0, 0, 0, 0, GULL);
                        p.spawnParticle(Particle.DUST, gx + wx, gy + flap, gz + wz, 1, 0, 0, 0, 0, GULL_WING);
                        p.spawnParticle(Particle.DUST, gx - wx, gy + flap, gz - wz, 1, 0, 0, 0, 0, GULL_WING);
                    }
                    if (sound && sq(p.getLocation().getX() - s.x, p.getLocation().getZ() - s.z) <= 32 * 32) {
                        p.playSound(new Location(p.getWorld(), s.x, top, s.z), Sound.ENTITY_FISH_SWIM, SoundCategory.AMBIENT, 0.6f, 1.2f);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // The harbours' 어황판
    // ------------------------------------------------------------------------------------------

    /** Every 항구 하역장 whose chunk is loaded: its board written again (made again if it is gone). */
    private void boards() {
        if (eco.docks() == null) return;
        List<String> seen = new ArrayList<>();
        for (Dock d : eco.docks().docks()) {
            if (d.kind() != DockLayout.Kind.PORT) continue;
            World w = Bukkit.getWorld(d.world());
            seen.add(d.name());
            if (w == null) continue;
            double[] at = boardAt.computeIfAbsent(d.name(), k -> {
                DockLayout.Block c = DockLayout.of(d.kind(), d.name()).counter();
                return d.at(c.x() + 0.5, c.y() + 3.2, c.z() + 0.5);
            });
            if (!w.isChunkLoaded((int) Math.floor(at[0]) >> 4, (int) Math.floor(at[2]) >> 4)) continue;
            Component text = boardText(d);
            UUID u = boards.get(d.name());
            Entity e = u == null ? null : Bukkit.getEntity(u);
            if (e instanceof TextDisplay t && t.isValid()) {
                t.text(text);
                continue;
            }
            TextDisplay t = w.spawn(new Location(w, at[0], at[1], at[2]), TextDisplay.class, x -> {
                x.setPersistent(false);
                x.setBillboard(Display.Billboard.CENTER);
                x.setAlignment(TextDisplay.TextAlignment.LEFT);
                x.setBackgroundColor(Color.fromARGB(150, 18, 34, 46));
                x.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.2f, 1.2f, 1.2f), new Quaternionf()));
                x.setViewRange(1.0f);
                x.text(text);
            });
            boards.put(d.name(), t.getUniqueId());
        }
        // a dock removed since: its board goes
        for (Iterator<Map.Entry<String, UUID>> it = boards.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, UUID> en = it.next();
            if (seen.contains(en.getKey())) continue;
            Entity e = Bukkit.getEntity(en.getValue());
            if (e != null) e.remove();
            it.remove();
        }
    }

    /** The board's text: the schools near this harbour, nearest first, in compass and sea words (never where exactly). */
    Component boardText(Dock d) {
        StringBuilder sb = new StringBuilder(Lang.raw("school.board-title", "name", d.name()));
        List<School> near = new ArrayList<>();
        double r2 = spec.boardRange() * spec.boardRange();
        for (School s : schools.getOrDefault(d.world(), List.of())) if (sq(s.x - d.x(), s.z - d.z()) <= r2) near.add(s);
        near.sort(Comparator.comparingDouble(s -> sq(s.x - d.x(), s.z - d.z())));
        int n = 0;
        for (School s : near) {
            if (n++ >= spec.boardLines()) break;
            FishSpec.Species sp = eco.fishing().spec().species().get(s.species);
            double dist = Math.hypot(s.x - d.x(), s.z - d.z());
            SchoolSpec.Band sea = spec.band(dist);
            sb.append('\n').append(Lang.raw("school.board-line", "way", spec.compass().get(SchoolSpec.compass(s.x - d.x(), s.z - d.z())),
                    "sea", sea == null ? "" : sea.name(), "fish", sp == null ? s.species : sp.name(), "size", spec.sizeWord(s.left * s.price)));
        }
        if (near.isEmpty()) sb.append('\n').append(Lang.raw("school.board-empty"));
        return Text.mm(sb.toString());
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 어군
    // ------------------------------------------------------------------------------------------

    public void admin(CommandSender s, String[] args) {
        String sub = args.length > 0 ? args[0] : "목록";
        switch (sub) {
            case "생성" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(Lang.msg("school.admin-player"));
                    return;
                }
                String fish = null;
                if (args.length > 1) {
                    for (FishSpec.Species x : eco.fishing().spec().species().values()) {
                        if (x.boatOnly() && (x.name().equals(args[1].replace('_', ' ')) || x.id().equalsIgnoreCase(args[1]))) fish = x.id();
                    }
                    if (fish == null) {
                        s.sendMessage(Lang.msg("school.admin-no-fish", "fish", args[1]));
                        return;
                    }
                }
                Location l = p.getLocation();
                School made = spawnAt(p.getWorld(), l.getX(), l.getZ(), fish, false);
                if (made == null) {
                    s.sendMessage(Lang.msg("school.admin-not-water"));
                    return;
                }
                s.sendMessage(Lang.msg("school.admin-made", "fish", name(made), "left", made.left, "sea", spec.band(made.band).name()));
            }
            case "지우기" -> {
                int n = 0;
                for (List<School> l : schools.values()) {
                    n += l.size();
                    l.clear();
                }
                gone.clear();
                s.sendMessage(Lang.msg("school.admin-cleared", "count", n));
            }
            case "어황판" -> {
                boardAt.clear();
                boards();
                s.sendMessage(Lang.msg("school.admin-boards", "count", boards.size()));
            }
            default -> {
                int n = 0;
                for (List<School> l : schools.values()) {
                    for (School x : l) {
                        n++;
                        s.sendMessage(Lang.msg("school.admin-line", "id", x.id, "world", x.world, "x", (int) x.x, "z", (int) x.z, "fish", name(x),
                                "left", x.left, "full", x.full, "sea", spec.band(x.band).name(), "seconds", Math.max(0, (x.until - now()) / 1000)));
                    }
                }
                if (n == 0) s.sendMessage(Lang.msg("school.admin-none"));
            }
        }
    }

    public List<String> adminComplete(String[] args) {
        if (args.length == 1) return List.of("목록", "생성", "지우기", "어황판");
        if (args.length == 2 && args[0].equals("생성")) {
            return eco.fishing().spec().species().values().stream().filter(FishSpec.Species::boatOnly).map(x -> x.name().replace(' ', '_')).toList();
        }
        return List.of();
    }

    private String name(School s) {
        FishSpec.Species sp = eco.fishing().spec().species().get(s.species);
        return sp == null ? s.species : sp.name();
    }
}
