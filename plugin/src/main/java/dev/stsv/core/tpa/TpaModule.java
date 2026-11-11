package dev.stsv.core.tpa;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.nickname.Names;
import dev.stsv.core.util.Lang;
import dev.stsv.core.world.SafeSpot;
import dev.stsv.core.world.WorldsModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 텔레포트 요청 (tpa): /텔포 &lt;플레이어&gt; asks to go to them, /텔포여기 &lt;플레이어&gt; asks them to come; the target
 * answers with /수락 [이름] or /거절 [이름] (or the [수락] [거절] buttons of the request), the sender can withdraw it
 * with /텔포취소, and /텔포차단 refuses every request (kept on the player). The requests ({@link TpaBook}) expire after
 * tpa.timeout-seconds. On accept, the player who moves stands still for the same warm-up as world travel
 * ({@link WorldsModule#warmup}: moving or damage cancels it), then teleports to where the other player is, in any
 * world. The requester then waits tpa.cooldown-seconds before the next request. Admins: no warm-up, no cooldown.
 */
public final class TpaModule implements StsvModule, Listener, TabExecutor {

    /** The plugin.yml commands this module answers. */
    public static final List<String> COMMANDS = List.of("tpa", "tpahere", "tpaccept", "tpdeny", "tpacancel", "tpatoggle");

    private static final NamespacedKey BLOCKING = new NamespacedKey("stsv", "tpa_block");

    private STSVPlugin plugin;
    private final TpaBook book = new TpaBook(60_000, 30_000);
    private BukkitTask expiry;

    @Override
    public String id() {
        return "tpa";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        load();
        for (Player p : Bukkit.getOnlinePlayers()) readBlocking(p);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        expiry = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 20L, 20L);
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    public void disable() {
        if (expiry != null) expiry.cancel();
        HandlerList.unregisterAll(this);
    }

    private void load() {
        book.settings(plugin.getConfig().getInt("tpa.timeout-seconds") * 1000L, plugin.getConfig().getInt("tpa.cooldown-seconds") * 1000L);
        book.gap(plugin.getConfig().getInt("tpa.request-gap-seconds") * 1000L);
    }

    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        String arg = args.length > 0 ? args[0] : null;
        switch (command.getName()) {
            case "tpa" -> request(p, arg, TpaBook.Kind.TO);
            case "tpahere" -> request(p, arg, TpaBook.Kind.HERE);
            case "tpaccept" -> answer(p, arg, true);
            case "tpdeny" -> answer(p, arg, false);
            case "tpacancel" -> withdraw(p);
            case "tpatoggle" -> toggle(p);
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p) || args.length != 1) return List.of();
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        switch (command.getName()) {
            case "tpa", "tpahere" -> {
                return Names.completeOthers(p, args[0]);
            }
            case "tpaccept", "tpdeny" -> {
                for (TpaBook.Request r : book.incoming(p.getUniqueId(), System.currentTimeMillis())) {
                    names.add(r.senderName());
                    Names.nickname(r.sender()).ifPresent(names::add);
                }
            }
            default -> { }
        }
        return names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    // ------------------------------------------------------------------------------------------
    // /텔포, /텔포여기
    // ------------------------------------------------------------------------------------------

    private void request(Player p, String name, TpaBook.Kind kind) {
        if (name == null || name.isBlank()) {
            p.sendMessage(Lang.msg("tpa.usage"));
            return;
        }
        // account name or nickname, else a unique start of either
        Player target = Names.online(name).orElse(null);
        if (target == null || !p.canSee(target)) {
            p.sendMessage(Lang.msg("tpa.offline", "player", safe(name)));
            return;
        }
        if (target.equals(p)) {
            p.sendMessage(Lang.msg("tpa.self"));
            return;
        }
        long now = System.currentTimeMillis();
        long wait = admin(p) ? 0 : book.cooldownLeft(p.getUniqueId(), now);
        if (wait > 0) {
            p.sendMessage(Lang.msg("tpa.cooldown", "seconds", seconds(wait)));
            return;
        }
        if (book.blocking(target.getUniqueId())) {
            p.sendMessage(Lang.msg("tpa.blocked", "player", Names.mini(target)));
            return;
        }
        TpaBook.Added added = book.add(new TpaBook.Request(p.getUniqueId(), p.getName(), target.getUniqueId(), target.getName(), kind, now), admin(p));
        if (added.status() == TpaBook.Status.TOO_SOON) {
            p.sendMessage(Lang.msg("tpa.too-soon", "seconds", seconds(book.gapLeft(p.getUniqueId(), now))));
            return;
        }
        if (added.status() == TpaBook.Status.SAME) {
            p.sendMessage(Lang.msg("tpa.already", "player", Names.mini(target), "seconds", seconds(book.expiresIn(added.previous(), now))));
            return;
        }
        if (added.status() == TpaBook.Status.REPLACED) {
            TpaBook.Request old = added.previous();
            p.sendMessage(Lang.msg("tpa.replaced", "player", Names.mini(old.target())));
            Player oldTarget = Bukkit.getPlayer(old.target());
            if (oldTarget != null && !oldTarget.equals(target)) oldTarget.sendMessage(Lang.msg("tpa.cancelled-target", "player", Names.mini(p)));
        }
        long timeout = seconds(book.expiresIn(book.outgoing(p.getUniqueId(), now), now));
        boolean here = kind == TpaBook.Kind.HERE;
        p.sendMessage(Lang.msg(here ? "tpa.sent-here" : "tpa.sent", "player", Names.mini(target), "seconds", timeout));
        target.sendMessage(Lang.msg(here ? "tpa.request-here" : "tpa.request", "player", Names.mini(p), "seconds", timeout,
                "buttons", Lang.raw("tpa.buttons", "player", Names.mini(p), "name", p.getName())));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
    }

    // ------------------------------------------------------------------------------------------
    // /수락, /거절
    // ------------------------------------------------------------------------------------------

    private void answer(Player p, String name, boolean accept) {
        long now = System.currentTimeMillis();
        // a nickname (or the start of one) finds the account name the requests are filed under
        String from = name == null || name.isBlank() ? name : Names.online(name).map(Player::getName).orElse(name);
        TpaBook.Request r = book.take(p.getUniqueId(), from, now);
        if (r == null) {
            p.sendMessage(name == null || name.isBlank() ? Lang.msg("tpa.none") : Lang.msg("tpa.none-from", "player", safe(name)));
            return;
        }
        Player sender = Bukkit.getPlayer(r.sender());
        if (sender == null) {
            p.sendMessage(Lang.msg("tpa.offline", "player", Names.mini(r.sender())));
            return;
        }
        if (!accept) {
            p.sendMessage(Lang.msg("tpa.denied", "player", Names.mini(sender)));
            sender.sendMessage(Lang.msg("tpa.denied-sender", "player", Names.mini(p)));
            return;
        }
        boolean to = r.kind() == TpaBook.Kind.TO;
        Player mover = to ? sender : p;
        Player dest = to ? p : sender;
        if (inWardrobe(mover) || inWardrobe(dest)) {
            Player busy = inWardrobe(mover) ? mover : dest;
            p.sendMessage(Lang.msg("tpa.busy", "player", Names.mini(busy)));
            sender.sendMessage(Lang.msg("tpa.busy", "player", Names.mini(busy)));
            return;
        }
        p.sendMessage(Lang.msg("tpa.accepted", "player", Names.mini(sender)));
        sender.sendMessage(Lang.msg("tpa.accepted-sender", "player", Names.mini(p)));
        int more = book.incoming(p.getUniqueId(), now).size();
        if (more > 0) p.sendMessage(Lang.msg("tpa.more", "count", more));
        start(mover, dest, r.sender());
    }

    /** The mover stands still (world travel's warm-up), then goes to where dest is by then. */
    private void start(Player mover, Player dest, UUID requester) {
        WorldsModule worlds = plugin.worlds();
        UUID destId = dest.getUniqueId();
        String destName = Names.mini(dest), moverName = Names.mini(mover);
        Runnable go = () -> go(mover, destId, destName, requester);
        if (worlds == null) {
            go.run();
            return;
        }
        int seconds = worlds.travelWarmup(mover);
        if (seconds > 0) {
            mover.sendMessage(Lang.msg("tpa.warmup", "player", destName, "seconds", seconds));
            dest.sendMessage(Lang.msg("tpa.warmup-other", "player", moverName, "seconds", seconds));
        }
        worlds.warmup(mover, seconds, left -> Lang.mm("tpa.countdown", "player", destName, "seconds", left), go, why -> {
            Player d = Bukkit.getPlayer(destId);
            if (d != null) d.sendMessage(Lang.msg("tpa.cancelled-other", "player", moverName));
        });
    }

    private void go(Player mover, UUID destId, String destName, UUID requester) {
        if (!mover.isOnline()) return;
        Player dest = Bukkit.getPlayer(destId);
        if (dest == null) {
            mover.sendMessage(Lang.msg("tpa.gone", "player", destName));
            return;
        }
        if (inWardrobe(mover) || inWardrobe(dest)) {
            mover.sendMessage(Lang.msg("tpa.busy", "player", inWardrobe(mover) ? Names.mini(mover) : destName));
            return;
        }
        // dest sits in a vehicle (their spot is a seat above the ground): the safe spot beside it a rider steps out to
        Location beside = plugin.vehicles() == null ? null : plugin.vehicles().besideRider(dest);
        if (beside == null && !safeArrival(dest)) {
            mover.sendMessage(Lang.msg("tpa.unsafe", "player", destName));
            dest.sendMessage(Lang.msg("tpa.unsafe-other", "player", Names.mini(mover)));
            return;
        }
        Location to = beside != null ? beside : dest.getLocation();
        mover.teleportAsync(to, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
            if (!ok) {
                mover.sendMessage(Lang.msg("tpa.failed"));
                return;
            }
            book.tripDone(requester, System.currentTimeMillis());
            mover.playSound(mover.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
            HudModule.flash(mover, Lang.mm("tpa.arrived", "player", destName));
            if (dest.isOnline()) HudModule.flash(dest, Lang.mm("tpa.arrived-other", "player", Names.mini(mover)));
        });
    }

    /**
     * Is where dest stands a safe place to bring someone ({@link SafeSpot#arrival}): checked when the warm-up ends, so
     * a player who stepped into lava (with fire resistance) or took off with an elytra meanwhile cannot pull the
     * other one into it.
     */
    private static boolean safeArrival(Player dest) {
        org.bukkit.entity.Entity root = dest.isInsideVehicle() && dest.getVehicle() != null ? dest.getVehicle() : dest;
        boolean climbing = root instanceof org.bukkit.entity.LivingEntity le && le.isClimbing();
        boolean airborne = dest.isFlying() || dest.isGliding() || (!root.isOnGround() && !root.isInWater() && !climbing);
        org.bukkit.block.Block feet = dest.getLocation().getBlock();
        org.bukkit.block.Block head = feet.getRelative(org.bukkit.block.BlockFace.UP), below = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
        return SafeSpot.arrival(feet.getType(), feet.getType().isOccluding(), head.getType(), head.getType().isOccluding(), below.getType(),
                airborne, dest.isInLava());
    }

    // ------------------------------------------------------------------------------------------
    // /텔포취소, /텔포차단
    // ------------------------------------------------------------------------------------------

    private void withdraw(Player p) {
        TpaBook.Request r = book.cancel(p.getUniqueId(), System.currentTimeMillis());
        if (r == null) {
            p.sendMessage(Lang.msg("tpa.none-out"));
            return;
        }
        p.sendMessage(Lang.msg("tpa.cancelled", "player", Names.mini(r.target())));
        Player target = Bukkit.getPlayer(r.target());
        if (target != null) target.sendMessage(Lang.msg("tpa.cancelled-target", "player", Names.mini(p)));
    }

    private void toggle(Player p) {
        boolean on = book.toggleBlocking(p.getUniqueId());
        if (on) p.getPersistentDataContainer().set(BLOCKING, PersistentDataType.BYTE, (byte) 1);
        else p.getPersistentDataContainer().remove(BLOCKING);
        p.sendMessage(Lang.msg(on ? "tpa.block-on" : "tpa.block-off"));
    }

    // ------------------------------------------------------------------------------------------

    /** Every second: expired requests are dropped, both players told. */
    private void expire() {
        for (TpaBook.Request r : book.expire(System.currentTimeMillis())) {
            Player sender = Bukkit.getPlayer(r.sender());
            if (sender != null) sender.sendMessage(Lang.msg("tpa.expired", "player", Names.mini(r.target())));
            Player target = Bukkit.getPlayer(r.target());
            if (target != null) target.sendMessage(Lang.msg("tpa.expired-target", "player", Names.mini(r.sender())));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        readBlocking(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        for (TpaBook.Request r : book.forget(p.getUniqueId(), System.currentTimeMillis())) {
            boolean sent = r.sender().equals(p.getUniqueId());
            Player other = Bukkit.getPlayer(sent ? r.target() : r.sender());
            if (other != null && !other.equals(p)) other.sendMessage(Lang.msg("tpa.left", "player", Names.mini(p)));
        }
    }

    private void readBlocking(Player p) {
        book.setBlocking(p.getUniqueId(), p.getPersistentDataContainer().has(BLOCKING));
    }

    private boolean inWardrobe(Player p) {
        return plugin.cosmetics() != null && plugin.cosmetics().inWardrobe(p);
    }

    private static boolean admin(Player p) {
        return p.hasPermission("stsv.admin");
    }

    /** A typed name as shown back in a message (no MiniMessage tags from chat). */
    private static String safe(String typed) {
        String s = typed.replaceAll("[^A-Za-z0-9_]", "");
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    private static long seconds(long ms) {
        return (ms + 999) / 1000;
    }
}
