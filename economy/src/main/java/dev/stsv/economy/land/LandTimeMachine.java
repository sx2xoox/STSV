package dev.stsv.economy.land;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.backup.TimeMachine;
import dev.stsv.core.backup.TimeMachine.Mode;
import dev.stsv.core.backup.TimeMachine.Plan;
import dev.stsv.core.backup.TimeMachinePlan;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Players;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 땅 타임머신 (/땅 타임머신): a land's owner, by themselves, sees their land as it was in a backup (a ghost only they
 * see) and, if they say so, turns just their land's chunks back to then. An administrator does the same for any
 * land with /관리자 타임머신, and gets everything back (what was inside chests, item frames, armor stands).
 *
 * <p>So that turning back time can never make anything: the owner's own time machine brings back blocks only (signs'
 * words, banners' patterns; containers come back empty, and a container with things in it now is left as it is),
 * and what it brings back that is worth money ({@link ValueGraph}: the shop's price of the block or of what it drops
 * or turns into) is paid for, less what it replaces. Before anything is put back the world is backed up (so "원래대로"
 * undoes it), and a land may be turned back once every so often (config time-machine.cooldown-hours).
 */
public final class LandTimeMachine extends EcoModule {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String LAST = "land.tm.last.", UNDO = "land.tm.undo.";
    private static final ItemStack PICK = new ItemStack(Material.NETHERITE_PICKAXE);

    private ValueGraph values;
    private final Map<String, Double> blockValues = new HashMap<>();
    /** player -> what their open session costs and which land it is */
    private final Map<UUID, Pending> pending = new HashMap<>();
    /** players whose turning back is under way (backup, then the blocks) */
    private final Set<UUID> working = new HashSet<>();

    /** undo: putting back the backup taken just before the last turning back (no waiting, no new cooldown) */
    private record Pending(long land, long cost, boolean admin, boolean undo) {}

    public LandTimeMachine(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-land-time-machine";
    }

    @Override
    protected void start() {
        STSVPlugin.get().admin().add(EcoPlugin.ADMIN_OWNER, "타임머신", "<플레이어|여기> [백업] [확인]",
                "그 사람의 땅(또는 서 있는 땅)을 백업 시각으로 통째로 되돌리기 (상자 안 물건 · 액자 · 갑옷 거치대까지, 비용 없음)", this::admin,
                (s, a) -> a.length == 1 ? withHere(dev.stsv.core.nickname.Names.complete(s, a[0]))
                        : a.length == 2 ? machine() == null ? List.of() : machine().choices().stream().map(TimeMachine.Choice::name).limit(20).toList()
                        : a.length == 3 ? List.of("확인") : List.of());
    }

    private static List<String> withHere(List<String> names) {
        List<String> out = new ArrayList<>(names);
        out.add(0, "여기");
        return out;
    }

    private TimeMachine machine() {
        return core.backups() == null ? null : core.backups().timeMachine();
    }

    private boolean on() {
        return eco.getConfig().getBoolean("time-machine.enabled");
    }

    // ------------------------------------------------------------------------------------------
    // /땅 타임머신
    // ------------------------------------------------------------------------------------------

    /** /땅 타임머신 [보기 <백업>|확인|취소|원래대로]. */
    public void command(Player p, String[] a) {
        if (!on() || machine() == null) {
            p.sendMessage(Lang.msg("timemachine.disabled"));
            return;
        }
        String sub = a.length < 2 ? "목록" : a[1];
        Land land = eco.land().store().index().ownedBy(p.getUniqueId());
        if (land == null) {
            p.sendMessage(Lang.msg("land.not-owner"));
            return;
        }
        switch (sub) {
            case "보기" -> {
                if (a.length < 3) list(p, land, false);
                else view(p, land, a[2], false, false);
            }
            case "확인" -> confirm(p);
            case "취소" -> cancel(p, true);
            case "원래대로" -> {
                String undo = eco.kv().get(UNDO + land.id);
                if (undo == null) p.sendMessage(Lang.msg("timemachine.no-undo"));
                else view(p, land, undo, false, true);
            }
            default -> list(p, land, false);
        }
    }

    /** byAdmin: listed for an administrator (their clicks open it with everything, for that land). */
    private void list(CommandSender to, Land land, boolean byAdmin) {
        List<TimeMachine.Choice> all = machine().choices().stream().filter(TimeMachine.Choice::checked).limit(12).toList();
        to.sendMessage(Lang.msg("timemachine.list-head", "count", all.size()));
        if (all.isEmpty()) {
            to.sendMessage(Lang.msg("timemachine.list-none"));
            return;
        }
        long wait = waitMinutes(land);
        if (wait > 0 && !byAdmin) to.sendMessage(Lang.msg("timemachine.cooldown", "minutes", wait));
        for (TimeMachine.Choice c : all) {
            String cmd = byAdmin ? "/관리자 타임머신 " + land.id + " " + c.name() : "/땅 타임머신 보기 " + c.name();
            to.sendMessage(Lang.mm("timemachine.list-entry", "when", c.time().format(WHEN), "name", c.name())
                    .clickEvent(ClickEvent.runCommand(cmd)));
        }
        String undo = eco.kv().get(UNDO + land.id);
        if (undo != null && !byAdmin) to.sendMessage(Lang.mm("timemachine.list-undo", "name", undo).clickEvent(ClickEvent.runCommand("/땅 타임머신 원래대로")));
    }

    private long waitMinutes(Land land) {
        long last = eco.kv().getLong(LAST + land.id, 0);
        long every = eco.getConfig().getLong("time-machine.cooldown-hours") * 3_600_000L;
        long left = last + every - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 59_999) / 60_000;
    }

    private static List<TimeMachinePlan.Chunk> chunks(Land land) {
        List<TimeMachinePlan.Chunk> out = new ArrayList<>();
        for (ChunkPos c : land.chunks()) out.add(new TimeMachinePlan.Chunk(c.x(), c.z()));
        return out;
    }

    private static World world(Land land) {
        List<ChunkPos> cs = land.chunks();
        return cs.isEmpty() ? null : Bukkit.getWorld(cs.getFirst().world());
    }

    /** Opens the backup and shows the owner (or the admin) their land as it was. */
    private void view(Player p, Land land, String backup, boolean full, boolean undo) {
        UUID u = p.getUniqueId();
        if (working.contains(u)) {
            p.sendMessage(Lang.msg("timemachine.working"));
            return;
        }
        if (!full && !undo && waitMinutes(land) > 0) {
            p.sendMessage(Lang.msg("timemachine.cooldown", "minutes", waitMinutes(land)));
            return;
        }
        World w = world(land);
        if (w == null) {
            p.sendMessage(Lang.msg("land.not-owner"));
            return;
        }
        Plan mine = machine().sessionOf(u);
        if (mine != null) cancel(p, false);
        if (land.chunks().stream().anyMatch(c -> !c.world().equals(w.getName()))) {
            p.sendMessage(Lang.msg("timemachine.one-world"));
            return;
        }
        p.sendMessage(Lang.msg("timemachine.loading", "name", backup));
        machine().plan(backup, w, chunks(land), full ? Mode.FULL : Mode.BUILDING, rules(), u).whenComplete((plan, err) -> sync(() -> {
            if (err != null) {
                refused(p, err);
                return;
            }
            if (!p.isOnline()) {
                machine().close(plan);
                return;
            }
            long cost = full ? 0 : cost(plan);
            pending.put(u, new Pending(land.id, cost, full, undo));
            int shown = machine().preview(p, plan);
            p.sendMessage(Lang.msg("timemachine.preview", "name", backup, "blocks", plan.changes.size(), "shown", shown,
                    "seconds", STSVPlugin.get().getConfig().getLong("backup.time-machine.preview-seconds")));
            if (plan.skippedFurniture + plan.skippedCrops + plan.skippedKept > 0) {
                p.sendMessage(Lang.msg("timemachine.kept", "furniture", plan.skippedFurniture, "crops", plan.skippedCrops, "containers", plan.skippedKept));
            }
            if (!plan.missing.isEmpty()) p.sendMessage(Lang.msg("timemachine.missing", "chunks", plan.missing.size()));
            if (full) p.sendMessage(Lang.msg("timemachine.full", "entities", plan.entities()));
            else if (plan.containersEmptied > 0) p.sendMessage(Lang.msg("timemachine.empty-containers", "count", plan.containersEmptied));
            Component ok = Lang.mm(cost > 0 ? "timemachine.confirm-paid" : "timemachine.confirm-free", "cost", EconomyModule.format(cost))
                    .clickEvent(ClickEvent.runCommand(full ? "/관리자 타임머신 " + land.id + " " + backup + " 확인" : "/땅 타임머신 확인"));
            Component no = Lang.mm("timemachine.cancel-button").clickEvent(ClickEvent.runCommand(full ? "/관리자 타임머신 취소" : "/땅 타임머신 취소"));
            p.sendMessage(ok.append(Component.text("  ")).append(no));
        }));
    }

    private void refused(CommandSender to, Throwable err) {
        Throwable t = err;
        while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) t = t.getCause();
        String key = t instanceof TimeMachine.Refused r ? "timemachine.refused-" + r.problem.name().toLowerCase(java.util.Locale.ROOT) : "timemachine.error";
        if (!(t instanceof TimeMachine.Refused)) eco.getLogger().log(Level.WARNING, "[타임머신] 실패", t);
        to.sendMessage(Lang.msg(key));
    }

    private void cancel(Player p, boolean tell) {
        pending.remove(p.getUniqueId());
        machine().endPreview(p);
        Plan plan = machine().sessionOf(p.getUniqueId());
        if (plan != null) machine().close(plan);
        if (tell) p.sendMessage(Lang.msg("timemachine.cancelled"));
    }

    /** 확인: back up the world as it is, take the price, put the land back. */
    private void confirm(Player p) {
        UUID u = p.getUniqueId();
        Plan plan = machine().sessionOf(u);
        Pending what = pending.get(u);
        if (plan == null || what == null) {
            p.sendMessage(Lang.msg("timemachine.no-session"));
            return;
        }
        Land land = eco.land().store().index().get(what.land());
        if (land == null || (!what.admin() && !land.isOwner(u))) {
            cancel(p, false);
            p.sendMessage(Lang.msg("land.not-owner"));
            return;
        }
        if (what.cost() > 0 && !core.economy().has(u, what.cost())) {
            p.sendMessage(Lang.msg("timemachine.not-enough", "cost", EconomyModule.format(what.cost())));
            return;
        }
        if (!working.add(u)) return;
        machine().hold(plan);
        machine().endPreview(p);
        p.sendMessage(Lang.msg("timemachine.backing-up"));
        core.backups().backupNow((undo, error) -> {
            if (undo == null) {
                working.remove(u);
                cancel(p, false);
                p.sendMessage(Lang.msg("timemachine.backup-failed", "reason", String.valueOf(error)));
                return;
            }
            if (what.cost() > 0 && !core.economy().withdraw(u, what.cost(), "land_time_machine")) {
                working.remove(u);
                cancel(p, false);
                p.sendMessage(Lang.msg("timemachine.not-enough", "cost", EconomyModule.format(what.cost())));
                return;
            }
            pending.remove(u);
            String by = p.getName();
            machine().apply(plan).whenComplete((n, err) -> sync(() -> {
                working.remove(u);
                if (err != null) {
                    eco.getLogger().log(Level.SEVERE, "[타임머신] 땅 #" + land.id + " 되돌리기 실패 (" + plan.backup + "), 되돌리기 전 백업: " + undo, err);
                    if (p.isOnline()) p.sendMessage(Lang.msg("timemachine.apply-failed", "undo", undo));
                    return;
                }
                eco.kv().set(UNDO + land.id, undo);
                if (!what.admin() && !what.undo()) eco.kv().set(LAST + land.id, System.currentTimeMillis());
                eco.getLogger().info("[타임머신] " + by + (what.admin() ? " (관리자)" : "") + ": 땅 #" + land.id + " (" + Players.name(land.owner)
                        + ") 을 " + plan.backup + " 으로 되돌림, 블록 " + n + "개, 비용 " + what.cost() + " G, 되돌리기 전 백업 " + undo);
                for (UUID person : land.people()) {
                    Player q = Bukkit.getPlayer(person);
                    if (q != null) q.sendMessage(Lang.msg("timemachine.done", "by", by, "name", plan.backup, "blocks", n));
                }
                if (p.isOnline()) {
                    if (!land.people().contains(u)) p.sendMessage(Lang.msg("timemachine.done", "by", by, "name", plan.backup, "blocks", n));
                    p.sendMessage(Lang.mm("timemachine.undo-hint", "name", undo).clickEvent(ClickEvent.runCommand(
                            what.admin() ? "/관리자 타임머신 " + land.id + " " + undo : "/땅 타임머신 원래대로")));
                }
            }));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (machine() == null || working.contains(e.getPlayer().getUniqueId())) return;
        if (pending.remove(e.getPlayer().getUniqueId()) != null) {
            Plan plan = machine().sessionOf(e.getPlayer().getUniqueId());
            if (plan != null) machine().close(plan);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Administrators
    // ------------------------------------------------------------------------------------------

    private void admin(CommandSender s, String[] a) {
        if (machine() == null) {
            s.sendMessage(Lang.msg("timemachine.disabled"));
            return;
        }
        if (a.length >= 1 && a[0].equals("취소") && s instanceof Player p) {
            cancel(p, true);
            return;
        }
        if (a.length == 0) {
            s.sendMessage(Lang.msg("timemachine.admin-usage"));
            return;
        }
        findLand(s, a[0], land -> {
            if (land == null) {
                s.sendMessage(Lang.msg("timemachine.admin-no-land", "name", a[0]));
                return;
            }
            if (a.length < 2) {
                list(s, land, true);
                return;
            }
            boolean go = a.length > 2 && a[2].equals("확인");
            if (s instanceof Player p) {
                if (go && machine().sessionOf(p.getUniqueId()) != null) confirm(p);
                else view(p, land, a[1], true, false);
                return;
            }
            // the console: no ghost to look at; 확인 opens and puts back at once
            if (!go) {
                s.sendMessage(Lang.msg("timemachine.admin-console-confirm"));
                return;
            }
            consoleApply(s, land, a[1]);
        });
    }

    private void findLand(CommandSender s, String word, java.util.function.Consumer<Land> then) {
        if (word.equals("여기") && s instanceof Player p) {
            then.accept(eco.land().at(p.getLocation()));
            return;
        }
        try {
            Land byId = eco.land().store().index().get(Long.parseLong(word));
            if (byId != null) {
                then.accept(byId);
                return;
            }
        } catch (NumberFormatException ignored) {
        }
        Players.find(word, found -> then.accept(found.map(u -> eco.land().store().index().ownedBy(u)).orElse(null)));
    }

    private void consoleApply(CommandSender s, Land land, String backup) {
        World w = world(land);
        if (w == null) return;
        machine().plan(backup, w, chunks(land), Mode.FULL, rules(), null).whenComplete((plan, err) -> sync(() -> {
            if (err != null) {
                refused(s, err);
                return;
            }
            machine().hold(plan);
            core.backups().backupNow((undo, error) -> {
                if (undo == null) {
                    machine().close(plan);
                    s.sendMessage(Lang.msg("timemachine.backup-failed", "reason", String.valueOf(error)));
                    return;
                }
                machine().apply(plan).whenComplete((n, e2) -> sync(() -> {
                    if (e2 != null) {
                        s.sendMessage(Lang.msg("timemachine.apply-failed", "undo", undo));
                        return;
                    }
                    eco.kv().set(UNDO + land.id, undo);
                    eco.getLogger().info("[타임머신] 콘솔: 땅 #" + land.id + " 을 " + backup + " 으로 되돌림, 블록 " + n + "개, 되돌리기 전 백업 " + undo);
                    s.sendMessage(Lang.msg("timemachine.done", "by", "console", "name", backup, "blocks", n));
                }));
            });
        }));
    }

    // ------------------------------------------------------------------------------------------
    // What may come back, and what it costs
    // ------------------------------------------------------------------------------------------

    private TimeMachine.Rules rules() {
        return new TimeMachine.Rules() {
            @Override
            public boolean refuseThen(Block then) {
                return false;   // anything may come back: what is worth money is paid for (cost)
            }

            @Override
            public boolean keepNow(Block now) {
                // a container with things in it now: putting back what stood there would destroy them
                BlockState s = now.getState(false);
                return s instanceof InventoryHolder h && !h.getInventory().isEmpty();
            }
        };
    }

    /** What the owner pays: the money's worth of what comes back, less that of what it replaces (never below 0). */
    private long cost(Plan plan) {
        double then = 0, now = 0;
        for (TimeMachine.Change c : plan.changes) {
            then += blockValue(plan.then(c.pos()));
            now += blockValue(plan.now(c.pos()));
        }
        double m = eco.getConfig().getDouble("time-machine.cost-multiplier");
        return (long) Math.ceil(Math.max(0, then - now) * m);
    }

    private double blockValue(Block b) {
        if (b.getType().isAir()) return 0;
        String key = b.getBlockData().getAsString();
        Double known = blockValues.get(key);
        if (known != null) return known;
        double v = 0;
        var drops = b.getDrops(PICK);
        if (drops.isEmpty()) {
            if (b.getType().isItem()) v = graph().value(id(b.getType()));
        } else {
            for (ItemStack d : drops) v += d.getAmount() * graph().value(id(d.getType()));
            if (b.getType().isItem()) v = Math.max(v, graph().value(id(b.getType())));
        }
        if (blockValues.size() > 20_000) blockValues.clear();
        blockValues.put(key, v);
        return v;
    }

    private static String id(Material m) {
        return m.getKey().getKey();
    }

    /** The shop's prices and every recipe that turns one kind of thing into another (built once). */
    private ValueGraph graph() {
        if (values != null) return values;
        ValueGraph g = new ValueGraph(item -> eco.shop() == null ? 0 : eco.shop().marketPrice(item));
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            ItemStack result = r.getResult();
            if (result == null || result.getType().isAir()) continue;
            String to = id(result.getType());
            if (r instanceof CookingRecipe<?> cook) {
                for (Material m : materials(cook.getInputChoice())) g.edge(id(m), to, result.getAmount());
            } else if (r instanceof StonecuttingRecipe cut) {
                for (Material m : materials(cut.getInputChoice())) g.edge(id(m), to, result.getAmount());
            } else if (r instanceof ShapedRecipe shaped) {
                Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
                Map<Material, Integer> count = new HashMap<>();
                boolean single = true;
                for (String row : shaped.getShape()) {
                    for (char ch : row.toCharArray()) {
                        RecipeChoice c = choices.get(ch);
                        if (c == null) continue;
                        List<Material> ms = materials(c);
                        if (ms.size() != 1) single = false;
                        else count.merge(ms.getFirst(), 1, Integer::sum);
                    }
                }
                if (single && count.size() == 1) {
                    var e = count.entrySet().iterator().next();
                    g.edge(id(e.getKey()), to, result.getAmount() / (double) e.getValue());
                }
            } else if (r instanceof ShapelessRecipe shapeless) {
                Map<Material, Integer> count = new HashMap<>();
                boolean single = true;
                for (RecipeChoice c : shapeless.getChoiceList()) {
                    List<Material> ms = materials(c);
                    if (ms.size() != 1) single = false;
                    else count.merge(ms.getFirst(), 1, Integer::sum);
                }
                if (single && count.size() == 1) {
                    var e = count.entrySet().iterator().next();
                    g.edge(id(e.getKey()), to, result.getAmount() / (double) e.getValue());
                }
            }
        }
        values = g;
        return g;
    }

    private static List<Material> materials(RecipeChoice c) {
        if (c instanceof RecipeChoice.MaterialChoice mc) return mc.getChoices();
        if (c instanceof RecipeChoice.ExactChoice ec) return ec.getChoices().stream().map(ItemStack::getType).distinct().toList();
        return List.of();
    }

    @Override
    public void reload() {
        values = null;
        blockValues.clear();
    }
}
