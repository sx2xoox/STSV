package dev.stsv.economy.job;

import dev.stsv.core.nickname.Names;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 직업: one job at a time (change any time; the cost grows with the level of the job changed to), Lv1-30 with the XP kept per job, XP only from
 * the job's own work (placed blocks give nothing), effects that grow with the level, finds (gems in ores,
 * rare catches; 1% for everyone else) and the bonuses a job gets on its own machine (MachineModule).
 */
public final class JobModule extends EcoModule {

    private static final NamespacedKey PLACED = new NamespacedKey("economy", "placed");

    private JobSpec spec;
    private JobStore store;
    private final Map<Location, Boolean> natural = new HashMap<>();
    private final Map<Location, UUID> brewers = new ConcurrentHashMap<>();
    /** farmers in the middle of a 3x3 harvest (the neighbours must not spread again) */
    private final Set<UUID> spreading = ConcurrentHashMap.newKeySet();

    public JobModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-jobs";
    }

    @Override
    protected void start() {
        store = new JobStore(core.db());
        store.init();
        load();
        every(200L, this::growCrops);
        every(1200L, store::flush);
        core.hud().sidebar("eco-job", 20, p -> {
            if (!eco.getConfig().getBoolean("hud.job")) return null;
            String job = job(p.getUniqueId());
            if (job == null) return Lang.mm("job.hud-none");
            JobSpec.Job j = spec.jobs().get(job);
            int lv = level(p.getUniqueId());
            return Lang.mm("job.hud", "color", j == null ? "white" : j.color(), "job", j == null ? job : j.name(), "level", lv,
                    "pct", lv >= spec.cap() ? "MAX" : gated(p.getUniqueId(), job) ? Lang.raw("job.hud-gate") : progress(p.getUniqueId(), lv) + "%");
        });
        eco.onReset(store::reset);
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    protected void stop() {
        store.flush();
        core.hud().removeSidebar("eco-job");
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = JobSpec.parse(eco.data("jobs.yml"), problems);
        for (JobSpec.Job j : spec.jobs().values()) {
            if (j.finds() == null) continue;
            j.finds().drops().values().forEach(l -> l.forEach(id -> { if (Items.create(id, 1).isEmpty()) problems.add(j.id() + ".finds: 없는 아이템 " + id); }));
        }
        problems.forEach(p -> eco.getLogger().warning("[jobs.yml] " + p));
    }

    public JobSpec spec() {
        return spec;
    }

    public JobStore store() {
        return store;
    }

    // ------------------------------------------------------------------------------------------
    // Levels, XP and effects
    // ------------------------------------------------------------------------------------------

    public String job(UUID player) {
        return store.job(player);
    }

    /** Level of the job the player has now (0 = no job). */
    public int level(UUID player) {
        String job = job(player);
        return job == null ? 0 : level(player, job);
    }

    /** Level in a job, whether or not they have it now (Lv1 if never worked; levels stay with the job). */
    public int level(UUID player, String job) {
        return spec.levelFor(store.xp(player, job));
    }

    /** An event that names jobs (풍년: farmer, fisher; 광산 호황: miner, blacksmith) multiplies their effects while it runs. */
    private double boost(String job) {
        double v = 1;
        for (dev.stsv.economy.event.ActiveEvent e : eco.events().active()) {
            if (e.runningAt(now()) && dev.stsv.economy.event.EventSpec.list(e.param("jobs")).contains(job)) v *= e.number("multiplier", 2);
        }
        return v;
    }

    /** A growing effect ("extra", "save") of a job for a player who has it now; 0 otherwise. */
    public double effect(UUID player, String job, String key) {
        if (!job.equals(job(player))) return 0;
        return spec.jobs().get(job).range(key, level(player), spec.maxLevel()) * boost(job);
    }

    /**
     * A machine bonus for a player who has that job now (see JobSpec.Job#bonus): chances are 0
     * and the time factors "fast" / "aging" are 1 for anyone else.
     */
    public double bonus(UUID player, String job, String key) {
        JobSpec.Job j = job == null ? null : spec.jobs().get(job);
        boolean factor = key.equals("fast") || key.equals("aging");
        if (j == null || player == null || !job.equals(job(player))) return factor ? 1 : 0;
        int lv = level(player);
        // the [Lv1, Lv30] part stops at Lv30 ("speed" is per level); a job's own milestone may replace a value (대장장이 Lv40 fast 0.4)
        double v = j.bonus(key, Math.min(lv, spec.maxLevel()));
        if (lv > spec.maxLevel() && (factor ? v < 1 : v > 0)) v = spec.milestone(job, lv, key, v);
        // 승급 milestones for every job: yield adds (Lv35 +10%, Lv50 +5%)
        if (key.equals("yield")) v += spec.milestoneSum(job, lv, "yield");
        return v;
    }

    /**
     * 승급 effects on a job's machines for a player who has that job now: "time" (a factor on the work time, Lv40 0.9) and
     * "resave" (a second chance to save an input, Lv45 0.15). 1 / 0 for anyone else.
     */
    public double milestone(UUID player, String job, String key) {
        boolean factor = key.equals("time");
        if (job == null || player == null || !job.equals(job(player))) return factor ? 1 : 0;
        int lv = level(player);
        return factor ? spec.milestoneFactor(job, lv, key) : spec.milestoneSum(job, lv, key);
    }

    /** A job's own milestone value for a player who has that job now (광부 Lv40 special-boost 1.2, 어부 trap-hold 1.5), else fallback. */
    public double jobMilestone(UUID player, String job, String key, double fallback) {
        if (job == null || player == null || !job.equals(job(player))) return fallback;
        return spec.milestone(job, level(player), key, fallback);
    }

    // ------------------------------------------------------------------------------------------
    // 승급 (promotion gates)
    // ------------------------------------------------------------------------------------------

    /** Gates passed in a job. */
    public int promoted(UUID player, String job) {
        return store.promoted(player, job);
    }

    /** Held at a gate: the level XP is full up to the next unpaid gate. */
    public boolean gated(UUID player, String job) {
        if (job == null) return false;
        JobSpec.Gate g = spec.gate(promoted(player, job));
        return g != null && store.xp(player, job) >= spec.ceiling(promoted(player, job)) - 1e-6;
    }

    /** The gate this player can pay for now in their job, or null. */
    public JobSpec.Gate promotable(UUID player) {
        String job = job(player);
        return gated(player, job) ? spec.gate(promoted(player, job)) : null;
    }

    /** What a gate asks of this job: its 4세대 material and (some gates) its special material. */
    public String gateItem(String job) {
        return spec.promotion().item().get(job);
    }

    public String gateSpecial(String job) {
        return spec.promotion().special().get(job);
    }

    /** Pays a gate (money and materials from the inventory) and opens its level. Null on success, else the refusal's message key. */
    public String promote(Player p) {
        UUID u = p.getUniqueId();
        String job = job(u);
        if (job == null) return "job.promote-no-job";
        JobSpec.Gate g = spec.gate(promoted(u, job));
        if (g == null) return "job.promote-max";
        if (!gated(u, job)) return "job.promote-not-yet";
        String item = gateItem(job), special = gateSpecial(job);
        java.util.function.Predicate<ItemStack> isItem = it -> item != null && item.equals(Items.key(it));
        java.util.function.Predicate<ItemStack> isSpecial = it -> special != null && special.equals(Items.key(it));
        if (g.items() > 0 && Items.count(p.getInventory(), isItem) < g.items()) return "job.promote-items";
        if (g.specials() > 0 && Items.count(p.getInventory(), isSpecial) < g.specials()) return "job.promote-items";
        if (!core.economy().withdraw(u, g.cost(), "job_promotion")) return "job.promote-money";
        if (g.items() > 0) Items.remove(p.getInventory(), isItem, g.items());
        if (g.specials() > 0) Items.remove(p.getInventory(), isSpecial, g.specials());
        JobStore.Row r = store.get(u);
        int before = level(u);
        store.setPromoted(r, job, promoted(u, job) + 1);
        store.setXp(r, job, Math.max(store.xp(u, job), spec.xpFor(g.level())));
        JobSpec.Job j = spec.jobs().get(job);
        Bukkit.broadcast(Lang.msg("job.promoted-broadcast", "player", Names.mini(p), "color", j.color(), "job", j.name(), "level", g.level()));
        if (g.level() >= spec.cap()) core.profiles().grantTitle(u, "economy:grandmaster_" + job);
        leveled(u, job, before, level(u));
        return null;
    }

    /** Adds XP to a job, only if it is the player's job now (counted in the job_xp stat for 미션). */
    public void addXp(UUID player, String job, double xp) {
        addXp(player, job, xp, true);
    }

    /** counted: false for XP that is itself a mission reward, so it does not count towards another mission. */
    public void addXp(UUID player, String job, double xp, boolean counted) {
        if (xp <= 0 || job == null || !job.equals(job(player))) return;
        if (counted && eco.accessories() != null) {
            eco.accessories().activity(player, "jobxp", xp);
            xp *= 1 + eco.accessories().value(player, "craft_learning");   // 장신구 장인의 배움
        }
        JobStore.Row r = store.get(player);
        int before = level(player);
        boolean wasGated = gated(player, job);
        // level XP stops just under the next unpaid gate (and at the last level); the rest is 숙련 only
        double room = Math.max(0, spec.ceiling(r.promoted(job)) - r.xp(job));
        double toLevel = Math.min(xp, room);
        if (toLevel > 0) store.addXp(r, job, toLevel);
        store.addMastery(r, job, xp - toLevel);
        if (counted) core.profiles().add(player, "job_xp", xp);
        leveled(player, job, before, level(player));
        if (!wasGated && gated(player, job)) {
            Player p = Bukkit.getPlayer(player);
            JobSpec.Gate g = spec.gate(r.promoted(job));
            if (p != null && g != null) p.sendMessage(Lang.msg("job.promote-ready", "level", g.level()));
        }
    }

    /**
     * A level up: the title and sound for the player, and a broadcast when a tens level (10, 20, 30) was
     * reached or passed (a big jump of XP can skip over it).
     */
    private void leveled(UUID player, String job, int before, int after) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || after <= before) return;
        JobSpec.Job j = spec.jobs().get(job);
        if (j == null) return;
        p.showTitle(net.kyori.adventure.title.Title.title(Lang.mm("job.levelup-title", "level", after),
                Lang.mm("job.levelup-subtitle", "color", j.color(), "job", j.name(), "level", after)));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.1f);
        int tens = after / 10 * 10;
        if (tens > before && tens >= 10) {
            Bukkit.broadcast(Lang.msg("job.levelup-broadcast", "player", Names.mini(p), "color", j.color(), "job", j.name(), "level", tens));
        }
    }

    /** XP for doing something, by the action table of the player's job. */
    public void rewardAction(Player p, String action, String thing, double times) {
        reward(p, action, thing, times);
    }

    private void reward(Player p, String action, String thing, double times) {
        // 길라잡이: what is done in the tutorial counts there only (no XP, stats, missions or achievements)
        if (eco.guide() != null && eco.guide().jobAction(p, action, thing, times)) return;
        // 업적 stats: counted for everyone, job or not
        String stat = switch (action) {
            case "break" -> "ores_mined";
            case "harvest" -> "crops_harvested";
            case "catch" -> "fish_caught";
            case "find" -> "rare_finds";
            case "breed" -> "animals_bred";
            default -> null;
        };
        if (stat != null) core.profiles().add(p.getUniqueId(), stat, times);
        String job = job(p.getUniqueId());
        if (job == null) return;
        double xp = spec.jobs().get(job).xp(action, thing) * times;
        if (xp > 0) addXp(p.getUniqueId(), job, xp);
    }

    // ------------------------------------------------------------------------------------------
    // Choosing a job
    // ------------------------------------------------------------------------------------------

    /** How long until this player may change jobs again (0 or less: now). */
    public long changeWait(UUID u) {
        JobStore.Row r = store.get(u);
        return r == null ? 0 : r.changed + spec.changeDays() * 86_400_000L - now();
    }

    /**
     * What changing to this job costs this player now: by their level in that job (jobs.yml change-cost), 0 for the first
     * pick and for the job they already have.
     */
    public long changeCost(UUID u, String jobId) {
        JobStore.Row r = store.get(u);
        if (r == null || r.job.equals(jobId)) return 0;
        return spec.changeCost().at(level(u, jobId));
    }

    /** Returns null on success, else the message key of the refusal. */
    public String choose(Player p, String jobId) {
        JobSpec.Job j = spec.jobs().get(jobId);
        if (j == null) return "job.no-such";
        JobStore.Row r = store.get(p.getUniqueId());
        if (r != null && r.job.equals(jobId)) return "job.same";
        if (r != null) {
            long wait = r.changed + spec.changeDays() * 86_400_000L - now();
            if (wait > 0 && !p.hasPermission("stsv.economy.admin")) return "job.too-soon";
            if (!core.economy().withdraw(p.getUniqueId(), changeCost(p.getUniqueId(), jobId), "job_change")) return "job.not-enough";
        }
        store.choose(p.getUniqueId(), jobId, now());
        p.sendMessage(Lang.msg("job.chosen", "color", j.color(), "job", j.name(), "level", level(p.getUniqueId(), jobId)));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
        return null;
    }

    public void open(Player p) {
        core.menus().open(p, "economy:jobs", new JobsMenu());
    }

    /** "Lv12 · 3,780 / 4,520" for one job of a player. */
    private Map<String, String> progress(UUID u, String job) {
        double xp = store.xp(u, job);
        int lv = spec.levelFor(xp);
        return Map.of("level", String.valueOf(lv), "xp", String.format("%,.0f", xp),
                "next", lv >= spec.cap() ? Lang.raw("job.max") : gated(u, job) ? Lang.raw("job.gate") : String.format("%,.0f", spec.xpFor(lv + 1)),
                "mastery", String.format("%,.0f", store.mastery(u, job)));
    }

    private static final java.util.regex.Pattern RANGE = java.util.regex.Pattern.compile("\\{([0-9.]+)~([0-9.]+)}");

    /**
     * One line of a job's description as the menu shows it, for the viewer's level in that job: "# 제목" is a section title in
     * the job's colour, "@10 효과" an unlock (✔ once reached, dimmed with its level before), anything else an effect line.
     * "{5~35.5}" is the value at the viewer's level (Lv1 to the top level, evenly between).
     */
    static String describe(String line, String color, int level, int maxLevel) {
        if (line.startsWith("# ")) return "<" + color + "><b>" + line.substring(2);
        java.util.regex.Matcher m = RANGE.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            double a = Double.parseDouble(m.group(1)), b = Double.parseDouble(m.group(2));
            double t = maxLevel <= 1 ? 1 : Math.max(0, Math.min(1, (Math.max(1, level) - 1) / (double) (maxLevel - 1)));
            double v = Math.round((a + (b - a) * t) * 10) / 10.0;
            String n = v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("<white>" + n + "</white>"));
        }
        m.appendTail(sb);
        String text = sb.toString();
        if (text.startsWith("@")) {
            int sp = text.indexOf(' ');
            int need = Integer.parseInt(text.substring(1, sp));
            String rest = text.substring(sp + 1);
            return level >= need ? "<green>✔</green> <gray>" + rest : "<dark_gray>Lv" + need + " " + rest.replace("<white>", "").replace("</white>", "");
        }
        return "<gray>· " + text;
    }

    /** "보석 가루 20 · 운철 10" for a gate of a job. */
    public String gateText(String job, JobSpec.Gate g) {
        List<String> parts = new ArrayList<>();
        String item = gateItem(job), special = gateSpecial(job);
        if (g.items() > 0 && item != null) parts.add(Items.mmName(item) + " " + g.items());
        if (g.specials() > 0 && special != null) parts.add(Items.mmName(special) + " " + g.specials());
        return parts.isEmpty() ? "-" : String.join(" · ", parts);
    }

    private final class JobsMenu implements MenuHandler {
        private final List<String> ids = new ArrayList<>(spec.jobs().keySet());
        private String armed;
        private long armedAt;

        @Override
        public Map<String, String> placeholders(MenuView v) {
            UUID u = v.player().getUniqueId();
            String job = job(u);
            JobSpec.Job j = job == null ? null : spec.jobs().get(job);
            Map<String, String> out = new HashMap<>(job == null ? Map.of("level", "0", "xp", "0", "next", "-") : progress(u, job));
            out.put("job", j == null ? Lang.raw("job.none") : "<" + j.color() + ">" + j.name());
            out.put("cap", String.valueOf(spec.cap()));
            out.put("cost", EconomyModule.format(spec.changeCost().min()) + " ~ " + EconomyModule.format(spec.changeCost().max()));
            return out;
        }

        @Override
        public List<ItemStack> list(MenuView v) {
            List<ItemStack> out = new ArrayList<>();
            UUID u = v.player().getUniqueId();
            String mine = job(u);
            for (String id : ids) {
                JobSpec.Job j = spec.jobs().get(id);
                ItemStack it = Items.create(j.icon(), 1).orElse(new ItemStack(Material.PAPER));
                boolean isArmed = id.equals(armed) && now() - armedAt < 5000;
                Map<String, String> pr = progress(u, id);
                it.editMeta(meta -> {
                    meta.itemName(Text.item("<" + j.color() + "><b>" + j.name() + "</b> <gray>Lv" + pr.get("level")));
                    List<Component> lore = new ArrayList<>();
                    lore.add(Lang.item("job.lore-xp", "xp", pr.get("xp"), "next", pr.get("next")));
                    if (store.mastery(u, id) > 0) lore.add(Lang.item("job.lore-mastery", "mastery", pr.get("mastery")));
                    for (String line : j.description()) {
                        if (line.startsWith("# ")) lore.add(Component.empty());
                        lore.add(Text.item(describe(line, j.color(), Integer.parseInt(pr.get("level")), spec.maxLevel())));
                    }
                    lore.add(Component.empty());
                    JobSpec.Gate g = id.equals(mine) ? promotable(u) : null;
                    if (g != null) {
                        lore.addAll(Lang.items(isArmed ? "job.lore-promote-confirm" : "job.lore-promote", "level", g.level(),
                                "cost", EconomyModule.format(g.cost()), "items", gateText(id, g)));
                    } else {
                        lore.addAll(Lang.items(id.equals(mine) ? "job.lore-current" : isArmed ? "job.lore-confirm" : mine == null ? "job.lore-pick" : "job.lore-change",
                                "cost", EconomyModule.format(changeCost(u, id))));
                    }
                    meta.lore(lore);
                    meta.setEnchantmentGlintOverride(id.equals(mine) || isArmed);
                    meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView v, int index, ClickType click) {
            if (index >= ids.size()) return;
            String id = ids.get(index);
            Player p = v.player();
            boolean promoting = id.equals(job(p.getUniqueId()));
            if (promoting && promotable(p.getUniqueId()) == null) return;
            if (!id.equals(armed) || now() - armedAt > 5000) {
                armed = id;
                armedAt = now();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                v.refresh();
                return;
            }
            armed = null;
            if (promoting) {
                JobSpec.Gate g = promotable(p.getUniqueId());
                String no = promote(p);
                if (no != null && g != null) HudModule.flash(p, Lang.mm(no, "cost", EconomyModule.format(g.cost()), "items", gateText(id, g)));
                v.refresh();
                return;
            }
            String refused = choose(p, id);
            if (refused != null) {
                HudModule.flash(p, Lang.mm(refused, "cost", EconomyModule.format(changeCost(p.getUniqueId(), id)), "left",
                        dev.stsv.economy.event.EventModule.hoursText(changeWait(p.getUniqueId()))));
            }
            v.refresh();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Placed blocks (no XP or bonus for blocks a player put down)
    // ------------------------------------------------------------------------------------------

    private static boolean tracked(Material m) {
        return m.name().endsWith("_ORE") || m == Material.MELON || m == Material.PUMPKIN
                || m == Material.ANCIENT_DEBRIS || m == Material.AMETHYST_CLUSTER || rock(m);
    }

    /** Bare rock a miner tunnels through (광부 "rock" XP: jobs.yml xp.rock); no ore bonus, gem or special from it. */
    private static boolean rock(Material m) {
        return m == Material.STONE || m == Material.DEEPSLATE;
    }

    private static int pack(Block b) {
        return (b.getX() & 15) | ((b.getZ() & 15) << 4) | ((b.getY() + 2048) << 8);
    }

    private static boolean placed(Block b) {
        int[] arr = b.getChunk().getPersistentDataContainer().get(PLACED, PersistentDataType.INTEGER_ARRAY);
        if (arr == null) return false;
        int k = pack(b);
        for (int v : arr) if (v == k) return true;
        return false;
    }

    private static void mark(Block b, boolean on) {
        Chunk c = b.getChunk();
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        int[] arr = pdc.getOrDefault(PLACED, PersistentDataType.INTEGER_ARRAY, new int[0]);
        int k = pack(b);
        int[] next = on ? (Arrays.stream(arr).anyMatch(v -> v == k) ? arr : Arrays.copyOf(arr, arr.length + 1))
                : Arrays.stream(arr).filter(v -> v != k).toArray();
        if (on && next != arr) next[next.length - 1] = k;
        if (next.length == 0) pdc.remove(PLACED);
        else pdc.set(PLACED, PersistentDataType.INTEGER_ARRAY, next);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (tracked(e.getBlockPlaced().getType())) mark(e.getBlockPlaced(), true);
    }

    /** A pushed placed ore stays "placed" at its new spot (no XP loop by piston). */
    private void movePlaced(List<Block> moved, org.bukkit.block.BlockFace dir) {
        List<Block> marked = new ArrayList<>();
        for (Block b : moved) if (tracked(b.getType()) && placed(b)) marked.add(b);
        if (marked.isEmpty()) return;
        for (Block b : marked) mark(b, false);
        for (Block b : marked) mark(b.getRelative(dir), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPush(org.bukkit.event.block.BlockPistonExtendEvent e) {
        movePlaced(e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPull(org.bukkit.event.block.BlockPistonRetractEvent e) {
        movePlaced(e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlast(org.bukkit.event.entity.EntityExplodeEvent e) {
        for (Block b : e.blockList()) if (tracked(b.getType()) && placed(b)) mark(b, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBlast(org.bukkit.event.block.BlockExplodeEvent e) {
        for (Block b : e.blockList()) if (tracked(b.getType()) && placed(b)) mark(b, false);
    }

    // ------------------------------------------------------------------------------------------
    // Miner and farmer: breaking
    // ------------------------------------------------------------------------------------------

    private static boolean mature(BlockState s) {
        return s.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge() && Tag.CROPS.isTagged(s.getType())
                || s.getType() == Material.NETHER_WART && s.getBlockData() instanceof Ageable w && w.getAge() >= w.getMaximumAge()
                || s.getType() == Material.COCOA && s.getBlockData() instanceof Ageable c && c.getAge() >= c.getMaximumAge();
    }

    private static Material produce(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            case NETHER_WART -> Material.NETHER_WART;
            case COCOA -> Material.COCOA_BEANS;
            case MELON -> Material.MELON_SLICE;
            case PUMPKIN -> Material.PUMPKIN;
            default -> null;
        };
    }

    private static boolean ore(Material m) {
        return tracked(m) && m != Material.MELON && m != Material.PUMPKIN && !rock(m);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material m = b.getType();
        boolean wasPlaced = tracked(m) && placed(b);
        if (wasPlaced) mark(b, false);
        Player p = e.getPlayer();
        String key = m.getKey().asString();
        boolean crop = mature(b.getState());
        if (!wasPlaced) {
            if (ore(m)) reward(p, "break", key, 1);
            else if (rock(m)) reward(p, "rock", key, 1);
            else if (crop || m == Material.MELON || m == Material.PUMPKIN) reward(p, "harvest", key, 1);
            accessories(p, b, m, crop);
        }
        if (crop && Tag.CROPS.isTagged(m)) spread(p, b);
        natural.put(b.getLocation(), !wasPlaced);
        Bukkit.getScheduler().runTask(eco, () -> natural.remove(b.getLocation()));
    }

    /** Extra produce for a farmer on a vanilla crop: harvest-bonus at the level, the fraction as a chance. */
    private int farmerBonus(UUID u) {
        if (!"farmer".equals(job(u))) return 0;
        double b = spec.jobs().get("farmer").harvestBonus(level(u), spec.maxLevel()) * boost("farmer");
        int n = (int) Math.floor(b);
        if (ThreadLocalRandom.current().nextDouble() < b - n) n++;
        return n;
    }

    /** Bone meal bought from the admin shop does not work on vanilla crops either (it is no longer sold; old stock). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(org.bukkit.event.block.BlockFertilizeEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;
        ItemStack main = p.getInventory().getItemInMainHand(), off = p.getInventory().getItemInOffHand();
        ItemStack used = main.getType() == Material.BONE_MEAL ? main : off;
        if (used.getType() == Material.BONE_MEAL && Items.shopBought(used)) {
            e.setCancelled(true);
            HudModule.flash(p, Lang.mm("job.bought-meal"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenseMeal(org.bukkit.event.block.BlockDispenseEvent e) {
        if (e.getItem().getType() == Material.BONE_MEAL && Items.shopBought(e.getItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        BlockState s = e.getBlockState();
        Material m = s.getType();
        if (!Boolean.TRUE.equals(natural.get(e.getBlock().getLocation())) || e.getItems().isEmpty()) return;
        UUID u = p.getUniqueId();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String key = m.getKey().asString();
        if (ore(m)) {
            boolean silk = p.getInventory().getItemInMainHand().containsEnchantment(Enchantment.SILK_TOUCH);
            if (!silk && r.nextDouble() < effect(u, "miner", "extra")) {
                Item first = e.getItems().getFirst();
                ItemStack it = first.getItemStack();
                // 장신구 풍부한 광맥: the +1 rolls once more
                it.setAmount(it.getAmount() + 1 + (eco.accessories().roll(u, "rich_vein") ? 1 : 0));
                first.setItemStack(it);
                p.spawnParticle(Particle.HAPPY_VILLAGER, e.getBlock().getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3);
            }
            JobSpec.Job miner = spec.jobs().get("miner");
            List<String> gems = miner == null || miner.finds() == null ? List.of() : miner.finds().drops(key);
            if (!silk && !gems.isEmpty() && r.nextDouble() < findChance(u, miner) * miner.finds().weight(key)) {
                Items.create(gems.get(r.nextInt(gems.size())), 1).ifPresent(g -> {
                    e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), g);
                    HudModule.flash(p, Lang.mm("job.found", "item", Items.mmName(g)));
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
                    reward(p, "find", Items.key(g), 1);
                });
            }
            if (!silk) rollSpecials(p, "break", e.getBlock().getLocation().add(0.5, 0.5, 0.5));
        }
        if (mature(s) || m == Material.MELON || m == Material.PUMPKIN) {
            Material crop = produce(m);
            int bonus = farmerBonus(u);
            if (bonus > 0) bonus += MachineSpec.binomial(bonus, Math.min(1, eco.accessories().value(u, "abundance")), r);   // 장신구 풍작
            if (crop != null && bonus > 0) {
                e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.3, 0.5), new ItemStack(crop, bonus));
            }
            rollSpecials(p, "harvest", e.getBlock().getLocation().add(0.5, 0.3, 0.5));
        }
    }

    /**
     * Special materials (jobs.yml specials) for one event: each one of that kind rolls once, the job's chance at the player's level
     * in it (events' boosts too) or the outsider chance. A find drops at the spot (or goes to the bag for a catch: at == null).
     */
    public void rollSpecials(Player p, String from, org.bukkit.Location at) {
        UUID u = p.getUniqueId();
        String job = job(u);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (JobSpec.Special s : spec.specials(from)) {
            double chance = s.job().equals(job)
                    ? s.chance().at(level(u), spec.maxLevel()) * boost(job) * jobMilestone(u, job, "special-boost", 1) : s.outsider();
            chance *= 1 + eco.accessories().value(u, "rare_find");   // 장신구 귀한 발견
            if (chance <= 0 || r.nextDouble() >= chance) continue;
            Items.create(s.item(), 1).ifPresent(g -> {
                if (eco.guide() != null && eco.guide().active(p)) Items.markTrial(g);   // 길라잡이: found in the tutorial
                if (at == null) {
                    for (ItemStack left : p.getInventory().addItem(g).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                } else {
                    at.getWorld().dropItemNaturally(at, g);
                }
                HudModule.flash(p, Lang.mm("job.found-special", "item", Items.mmName(g)));
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
                reward(p, "find", s.item(), 1);
            });
        }
    }

    /** Find chance of a job for a player (a rare bite for the fisher): the job's growing chance, else the outsider chance (1%). */
    public double findChance(UUID u, String job) {
        return findChance(u, spec.jobs().get(job));
    }

    private double findChance(UUID u, JobSpec.Job j) {
        if (j == null || j.finds() == null) return 0;
        if (j.id().equals(job(u))) return j.finds().chance().at(level(u), spec.maxLevel()) * boost(j.id());
        return j.finds().outsider();
    }

    /**
     * 농부 row-harvest (Lv10): a ripe crop harvested by hand takes the ripe ones left and right of it (1x3, across the way the
     * player faces) along; area-harvest (Lv20): the ripe ones all around it (3x3).
     */
    private void spread(Player p, Block center) {
        UUID u = p.getUniqueId();
        // 장신구 풍년 바람: the ripe crops of the 5x5 around come along (anyone, any job)
        if (!spreading.contains(u) && (core.crops() == null || !core.crops().collecting()) && eco.accessories().roll(u, "harvest_wind")) {
            Bukkit.getScheduler().runTask(eco, () -> {
                if (!p.isOnline() || !spreading.add(u)) return;
                try {
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        Block nb = center.getRelative(dx, 0, dz);
                        if (eco.land() != null && !eco.land().allowed(p, nb.getLocation())) continue;
                        core.crops().harvestAt(p, nb);
                    }
                    center.getWorld().spawnParticle(Particle.CHERRY_LEAVES, center.getLocation().add(0.5, 0.8, 0.5), 40, 2, 0.4, 2, 0);
                    p.playSound(center.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.6f, 1.2f);
                } finally {
                    spreading.remove(u);
                }
            });
            return;
        }
        JobSpec.Job farmer = spec.jobs().get("farmer");
        if (farmer == null || !"farmer".equals(job(u))) return;
        int span = farmer.harvestSpan(level(u));
        if (span <= 1 || spreading.contains(u)) return;
        // a machine harvesting (the tractor's harvester, its drops into its bed: the core's 작업기) does its own rows
        if (core.crops() != null && core.crops().collecting()) return;
        // across the facing: facing mostly along x -> the row runs along z, and the other way round
        boolean alongX = Math.abs(p.getLocation().getDirection().getX()) >= Math.abs(p.getLocation().getDirection().getZ());
        Bukkit.getScheduler().runTask(eco, () -> {
            if (!p.isOnline() || !spreading.add(u)) return;
            try {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (span == 3 && (alongX ? dx != 0 : dz != 0)) continue;
                    Block nb = center.getRelative(dx, 0, dz);
                    // someone else's land stays theirs: the same check as the player's own harvest there (silent here)
                    if (eco.land() != null && !eco.land().allowed(p, nb.getLocation())) continue;
                    core.crops().harvestAt(p, nb);
                }
            } finally {
                spreading.remove(u);
            }
        });
    }

    /** Blocks broken by an accessory's chain (광맥 폭발 · 벌목 회오리 · 땅울림): their own breaks do not start another chain. */
    private final Set<UUID> chaining = ConcurrentHashMap.newKeySet();

    private static boolean diggable(Material m) {
        return switch (m) {
            case DIRT, COARSE_DIRT, ROOTED_DIRT, GRASS_BLOCK, PODZOL, MYCELIUM, SAND, RED_SAND, GRAVEL, CLAY, MUD, SOUL_SAND, SOUL_SOIL,
                 SNOW_BLOCK -> true;
            default -> false;
        };
    }

    private static boolean stone(Material m) {
        return switch (m) {
            case STONE, DEEPSLATE, COBBLED_DEEPSLATE, TUFF, ANDESITE, DIORITE, GRANITE, CALCITE, NETHERRACK, BLACKSTONE, BASALT, END_STONE -> true;
            default -> false;
        };
    }

    /**
     * 장신구 on a natural block broken by hand: accessory XP and drop chances (mine · harvest · wood · dig), and the chains:
     * 광맥 폭발 (the connected ore of the same kind, up to 32), 벌목 회오리 (a natural tree, up to 128 logs), 땅울림 (the 3x3 across
     * the face looked at). The chained blocks are broken as the player (protection, tools, job rewards see normal breaks).
     */
    private void accessories(Player p, Block b, Material m, boolean crop) {
        UUID u = p.getUniqueId();
        var acc = eco.accessories();
        if (acc == null) return;
        boolean chained = chaining.contains(u);
        if (ore(m) || stone(m)) {
            acc.activity(u, "mine", 1);
            if (ore(m)) acc.drop(p, "mine");
            if (ore(m) && !chained && acc.roll(u, "vein_burst")) chain(p, collect(b, x -> x.getType() == m, 32, false), true);
        } else if (crop) {
            acc.activity(u, "harvest", 1);
            acc.drop(p, "harvest");
        } else if (Tag.LOGS.isTagged(m)) {
            acc.activity(u, "wood", 1);
            if (!chained && acc.roll(u, "timber")) {
                List<Block> tree = collect(b, x -> Tag.LOGS.isTagged(x.getType()), 128, true);
                boolean leafy = false;
                for (Block t : tree) for (org.bukkit.block.BlockFace f : List.of(org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.NORTH,
                        org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST)) {
                    if (Tag.LEAVES.isTagged(t.getRelative(f).getType())) leafy = true;
                }
                if (leafy) chain(p, tree, false);
            }
        } else if (diggable(m)) {
            acc.activity(u, "dig", 1);
            if (!chained && acc.roll(u, "quake")) {
                List<Block> plane = new ArrayList<>();
                float pitch = p.getLocation().getPitch();
                boolean flat = Math.abs(pitch) > 45;
                boolean alongX = Math.abs(p.getLocation().getDirection().getX()) < Math.abs(p.getLocation().getDirection().getZ());
                for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
                    if (i == 0 && j == 0) continue;
                    Block nb = flat ? b.getRelative(i, 0, j) : alongX ? b.getRelative(i, j, 0) : b.getRelative(0, j, i);
                    if (diggable(nb.getType()) && !placed(nb)) plane.add(nb);
                }
                chain(p, plane, false);
            }
        }
    }

    /** Natural blocks connected to a start (26 neighbours), matching, up to max (the start itself not included). */
    private List<Block> collect(Block start, java.util.function.Predicate<Block> match, int max, boolean diagonal) {
        List<Block> out = new ArrayList<>();
        java.util.ArrayDeque<Block> q = new java.util.ArrayDeque<>(List.of(start));
        Set<Block> seen = new java.util.HashSet<>(List.of(start));
        while (!q.isEmpty() && out.size() < max) {
            Block c = q.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                if (!diagonal && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1) continue;
                Block n = c.getRelative(dx, dy, dz);
                if (!seen.add(n) || !match.test(n) || placed(n)) continue;
                out.add(n);
                q.add(n);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    /** Breaks the blocks as the player, next tick (each a normal break: land, tool wear, drops). */
    private void chain(Player p, List<Block> blocks, boolean burst) {
        if (blocks.isEmpty()) return;
        UUID u = p.getUniqueId();
        Bukkit.getScheduler().runTask(eco, () -> {
            if (!p.isOnline() || !chaining.add(u)) return;
            try {
                for (Block nb : blocks) {
                    if (nb.isEmpty() || eco.land() != null && !eco.land().allowed(p, nb.getLocation())) continue;
                    if (burst) nb.getWorld().spawnParticle(Particle.EXPLOSION, nb.getLocation().add(0.5, 0.5, 0.5), 1);
                    p.breakBlock(nb);
                }
                p.playSound(p.getLocation(), burst ? Sound.ENTITY_GENERIC_EXPLODE : Sound.BLOCK_WOOD_BREAK, burst ? 0.5f : 0.8f, burst ? 1.4f : 0.8f);
            } finally {
                chaining.remove(u);
            }
        });
    }

    /**
     * 성장 가속 (농부 Lv10): crops (vanilla and custom) around online farmers grow a step now and then. Two-block crops
     * (the pitcher plant) are left out: raising one half's age by hand does not grow the other half and breaks the plant.
     */
    private void growCrops() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        JobSpec.Job farmer = spec.jobs().get("farmer");
        if (farmer == null || farmer.growth() == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (!"farmer".equals(job(u)) || level(u) < farmer.growth().level()) continue;
            double chance = jobMilestone(u, "farmer", "growth", farmer.growth().value()) * boost("farmer");
            int rad = (int) jobMilestone(u, "farmer", "growth-radius", farmer.growthRadius());
            Block c = p.getLocation().getBlock();
            for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) for (int dy = -2; dy <= 1; dy++) {
                Block b = c.getRelative(dx, dy, dz);
                if (!(b.getBlockData() instanceof Ageable a) || a instanceof Bisected || b.getType() == Material.PITCHER_CROP
                        || !Tag.CROPS.isTagged(b.getType()) || a.getAge() >= a.getMaximumAge()) continue;
                if (r.nextDouble() >= chance) continue;
                a.setAge(a.getAge() + 1);
                b.setBlockData(a, false);
                if (r.nextInt(4) == 0) b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.4, 0.5), 2, 0.2, 0.1, 0.2);
            }
            core.crops().growAround(c.getWorld(), c.getX(), c.getY(), c.getZ(), rad, 2, 1, chance);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (e.getBreeder() instanceof Player p) reward(p, "breed", e.getEntityType().getKey().asString(), 1);
    }

    // ------------------------------------------------------------------------------------------
    // Custom crops: who may plant them, and the farmer's touch at harvest
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCrop(dev.stsv.core.crop.CropEvent e) {
        Player p = e.player();
        UUID u = p.getUniqueId();
        switch (e.action()) {
            case PLANT -> {
                String job = e.crop().extra().get("job");
                if (job == null || p.hasPermission("stsv.economy.admin") || eco.guide() != null && eco.guide().active(p)) return;
                int need = Integer.parseInt(e.crop().extra().getOrDefault("level", "1"));
                if (!job.equals(job(u)) || level(u) < need) {
                    e.setCancelled(true);
                    JobSpec.Job j = spec.jobs().get(job);
                    HudModule.flash(p, Lang.mm("job.locked-plant", "color", j == null ? "white" : j.color(), "job", j == null ? job : j.name(), "level", need));
                }
            }
            case USE -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType() == Material.BONE_MEAL && Items.shopBought(hand)) {
                    e.setCancelled(true);
                    HudModule.flash(p, Lang.mm("job.bought-meal"));
                }
            }
            case HARVEST -> {
                reward(p, "harvest", e.crop().id(), 1);
                // the farmer's harvest bonus: a share of the produce (+2% a level), so every crop gains alike
                double pct = "farmer".equals(job(u)) ? spec.jobs().get("farmer").harvestPct(level(u), spec.maxLevel()) * boost("farmer") : 0;
                rollSpecials(p, "harvest", e.location().clone().add(0.5, 0.3, 0.5));
                for (ItemStack it : e.drops()) {
                    if (pct > 0 && e.crop().produce().equals(Items.key(it))) {
                        int n = it.getAmount(), whole = (int) Math.floor(pct);
                        int extra = n * whole + MachineSpec.binomial(n, pct - whole, ThreadLocalRandom.current());
                        // 장신구 풍작: every +1 rolls once more
                        extra += MachineSpec.binomial(extra, Math.min(1, eco.accessories().value(u, "abundance")), ThreadLocalRandom.current());
                        it.setAmount(n + extra);
                        break;
                    }
                }
                if (!core.crops().collecting()) {
                    eco.accessories().activity(u, "harvest", 1);
                    eco.accessories().drop(p, "harvest");
                }
                spread(p, e.location().getBlock());
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fishing: the fish themselves are the fishing module's (the ring); treasure and junk stay vanilla
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item caught)) return;
        reward(e.getPlayer(), "catch", Items.key(caught.getItemStack()), 1);
    }

    // ------------------------------------------------------------------------------------------
    // Crafting: XP, the blacksmith's savings on tools and armour
    // ------------------------------------------------------------------------------------------

    private static boolean gear(Material m) {
        String n = m.name();
        return n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_SWORD")
                || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || m == Material.FISHING_ROD || m == Material.SHIELD || m == Material.BOW || m == Material.CROSSBOW;
    }

    /**
     * How many times a click on a crafting or smithing result really makes it. The server fires the event for every click
     * on the result slot, also when nothing is made (another item on the cursor: NOTHING; a full inventory on a shift-click),
     * so only the actions that take the result count. A shift-click makes as many as both the ingredients (batches) and the
     * free room (in results of perCraft each) allow; a drop only works with an empty cursor.
     */
    static int crafted(InventoryAction action, boolean cursorEmpty, int batches, int room, int perCraft) {
        return switch (action) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_SOME, PICKUP_ONE, HOTBAR_SWAP -> 1;
            case DROP_ONE_SLOT, DROP_ALL_SLOT -> cursorEmpty ? 1 : 0;
            case MOVE_TO_OTHER_INVENTORY -> Math.max(0, Math.min(batches, room / Math.max(1, perCraft)));
            default -> 0;
        };
    }

    private static boolean cursorEmpty(org.bukkit.event.inventory.InventoryClickEvent e) {
        return e.getCursor() == null || e.getCursor().isEmpty();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getRecipe().getResult();
        ItemStack made = e.getInventory().getResult() == null || e.getInventory().getResult().isEmpty() ? result : e.getInventory().getResult();
        String key = Items.key(made);
        int batches = Integer.MAX_VALUE;
        for (ItemStack it : e.getInventory().getMatrix()) if (it != null && !it.isEmpty()) batches = Math.min(batches, it.getAmount());
        if (batches == Integer.MAX_VALUE) batches = 1;
        int times = crafted(e.getAction(), cursorEmpty(e), batches,
                e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY ? Items.room(p.getInventory(), made) : 0, made.getAmount());
        if (times <= 0) return;
        reward(p, "craft", key, times);
        // 대장장이: sometimes one ingredient comes back (a roll for each piece really made)
        if (gear(result.getType())) {
            double chance = effect(p.getUniqueId(), "blacksmith", "save");
            List<ItemStack> used = new ArrayList<>();
            for (ItemStack it : e.getInventory().getMatrix()) if (it != null && !it.isEmpty()) used.add(it.asOne());
            if (used.isEmpty()) return;
            for (int i = 0; i < times; i++) {
                if (ThreadLocalRandom.current().nextDouble() >= chance) continue;
                ItemStack back = used.get(ThreadLocalRandom.current().nextInt(used.size()));
                Bukkit.getScheduler().runTask(eco, () -> {
                    for (ItemStack left : p.getInventory().addItem(back).values()) p.getWorld().dropItem(p.getLocation(), left);
                    HudModule.flash(p, Lang.mm("job.saved", "item", Items.mmName(back)));
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmith(SmithItemEvent e) {
        ItemStack made = e.getInventory().getResult();
        if (!(e.getWhoClicked() instanceof Player p) || made == null || made.isEmpty()) return;
        int times = crafted(e.getAction(), cursorEmpty(e), 1,
                e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY ? Items.room(p.getInventory(), made) : 0, made.getAmount());
        if (times > 0) reward(p, "smith", Items.key(made), 1);
    }

    @EventHandler
    public void onSmelt(FurnaceExtractEvent e) {
        reward(e.getPlayer(), "smelt", e.getItemType().getKey().asString(), e.getItemAmount());
    }

    // ------------------------------------------------------------------------------------------
    // Alchemist: brewing XP
    // ------------------------------------------------------------------------------------------

    @EventHandler
    public void onOpenStand(InventoryOpenEvent e) {
        if (e.getInventory().getType() == InventoryType.BREWING && e.getInventory().getLocation() != null) {
            brewers.put(e.getInventory().getLocation().toBlockLocation(), e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent e) {
        UUID u = brewers.get(e.getBlock().getLocation().toBlockLocation());
        if (u == null || !"alchemist".equals(job(u))) return;
        int count = 0;
        for (ItemStack it : e.getResults()) if (it != null && !it.isEmpty() && it.getItemMeta() instanceof PotionMeta) count++;
        if (count > 0) addXp(u, "alchemist", spec.jobs().get("alchemist").xp("brew", "*") * count);
    }

    // ------------------------------------------------------------------------------------------

    public List<Component> rankingLines(String job) {
        List<Component> out = new ArrayList<>();
        JobSpec.Job j = job == null ? null : spec.jobs().get(job);
        out.add(Lang.msg("job.ranking-header", "job", j == null ? Lang.raw("job.all") : "<" + j.color() + ">" + j.name()));
        int rank = 1;
        for (JobStore.Entry r : store.ranking(job, 10)) {
            JobSpec.Job rj = spec.jobs().get(r.job());
            out.add(Lang.mm("job.ranking-line", "rank", rank++, "player", Players.name(r.player()), "color", rj == null ? "white" : rj.color(),
                    "job", rj == null ? r.job() : rj.name(), "level", spec.levelFor(r.xp()),
                    "mastery", String.format("%,.0f", r.mastery()), "xp", String.format("%,.0f", r.xp())));
        }
        return out;
    }

    /** "광부 Lv12 · 농부 Lv3" for /직업 정보: every job this person worked. */
    public String levelsText(UUID player) {
        JobStore.Row r = store.get(player);
        if (r == null) return "-";
        List<String> parts = new ArrayList<>();
        for (JobSpec.Job j : spec.jobs().values()) {
            if (r.xp(j.id()) <= 0 && !j.id().equals(r.job)) continue;
            parts.add("<" + j.color() + ">" + j.name() + "<gray> Lv" + level(player, j.id()));
        }
        return String.join("<dark_gray> · ", parts);
    }

    /** How far into the level (0..99). */
    private int progress(UUID player, int lv) {
        String job = job(player);
        double xp = store.xp(player, job), lo = spec.xpFor(lv), hi = spec.xpFor(lv + 1);
        return hi <= lo ? 100 : (int) Math.clamp((xp - lo) / (hi - lo) * 100, 0, 99);
    }

    /** Admin: set the XP of the player's current job (testing). */
    public void setXp(UUID player, double xp) {
        JobStore.Row r = store.get(player);
        if (r == null) return;
        int before = level(player);
        int lv = spec.levelFor(xp);
        store.setPromoted(r, r.job, spec.gatesBelow(lv));
        store.setXp(r, r.job, Math.min(xp, spec.ceiling(spec.gatesBelow(lv))));
        leveled(player, r.job, before, level(player));
    }

    /** Admin: set the gates passed in the player's current job (the level XP is kept under the next gate). */
    public void setPromoted(UUID player, int gates) {
        JobStore.Row r = store.get(player);
        if (r == null) return;
        int g = Math.clamp(gates, 0, spec.promotion().gates().size());
        store.setPromoted(r, r.job, g);
        if (r.xp(r.job) > spec.ceiling(g)) store.setXp(r, r.job, spec.ceiling(g));
    }
}
