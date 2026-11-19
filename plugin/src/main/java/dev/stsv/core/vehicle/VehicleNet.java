package dev.stsv.core.vehicle;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Less felt lag on a far server (docs/plans/핑_완화_조사.md):
 * <ul>
 *   <li><b>Seats without the teleport chain (A-3)</b>: a seat is moved by setting its position on the server entity
 *       instead of a Bukkit teleport. A teleport also teleports every rider (the chair, then the player): a position
 *       packet to the player every tick, two answers back, a PlayerTeleportEvent, and the server ignoring the
 *       player's own look until the answer comes. Set directly, the riders follow the seat in their ride tick of the
 *       same server tick, and the clients get the seat's move from the entity tracker like any other part.</li>
 *   <li><b>A quicker glide for the riders only (A-2)</b>: every part of a vehicle glides to where the server put it
 *       over vehicles.smoothing-ticks (2: smooth to watch, but 1 tick behind). Whoever rides it sees it (and their
 *       own camera, which rides its seat) glide over vehicles.rider-smoothing-ticks (1) instead: the entity data the
 *       server sends them for that vehicle's parts is rewritten on its way out. Everyone else still sees it glide
 *       smoothly.</li>
 * </ul>
 * Paper has no API for either: both read the server's own classes (Mojang names) by reflection. When something is
 * missing, that part switches itself off (one warning) and the vehicle works as before.
 */
final class VehicleNet {

    private static final String HANDLER = "stsv_vehicles";

    private final Logger log;
    private final AtomicBoolean warnedMove = new AtomicBoolean(), warnedNet = new AtomicBoolean();

    // A-3: moving a server entity without a teleport
    private Method getHandle, setPos, setYRot;
    private volatile boolean moveOk;
    /** debug: seats moved directly, and by a teleport (fallback) */
    final AtomicLong directMoves = new AtomicLong(), teleportMoves = new AtomicLong();

    // A-2: the riders' own glide
    private Class<?> dataCls, bundleCls, connectionCls;
    private Constructor<?> dataNew, valueNew, bundleNew;
    private Method dataId, dataItems, valueId, valueSerializer, valueValue, bundleSub;
    private int idPosRot = -1, idTransform = -1;
    private volatile boolean netOk;
    private final Map<UUID, Handler> handlers = new ConcurrentHashMap<>();
    /** debug: entity data packets rewritten */
    final AtomicLong rewritten = new AtomicLong();

    VehicleNet(Logger log) {
        this.log = log;
        ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
        try {
            Class<?> craft = Class.forName(Bukkit.getServer().getClass().getPackageName() + ".entity.CraftEntity", false, cl);
            getHandle = craft.getMethod("getHandle");
            Class<?> nms = Class.forName("net.minecraft.world.entity.Entity", false, cl);
            setPos = nms.getMethod("setPos", double.class, double.class, double.class);
            setYRot = nms.getMethod("setYRot", float.class);
            moveOk = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("[탈것] 좌석을 텔레포트 없이 옮길 수 없어 예전 방식(텔레포트)으로 옮깁니다: " + e);
        }
        try {
            String game = "net.minecraft.network.protocol.game.";
            connectionCls = Class.forName("net.minecraft.network.Connection", false, cl);
            dataCls = Class.forName(game + "ClientboundSetEntityDataPacket", false, cl);
            dataId = dataCls.getMethod("id");
            dataItems = dataCls.getMethod("packedItems");
            dataNew = dataCls.getConstructor(int.class, List.class);
            Class<?> value = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue", false, cl);
            valueId = value.getMethod("id");
            valueSerializer = value.getMethod("serializer");
            valueValue = value.getMethod("value");
            valueNew = value.getConstructor(int.class, Class.forName("net.minecraft.network.syncher.EntityDataSerializer", false, cl), Object.class);
            bundleCls = Class.forName(game + "ClientboundBundlePacket", false, cl);
            bundleNew = bundleCls.getConstructor(Iterable.class);
            bundleSub = bundleCls.getMethod("subPackets");
            Class<?> display = Class.forName("net.minecraft.world.entity.Display", false, cl);
            Method accId = Class.forName("net.minecraft.network.syncher.EntityDataAccessor", false, cl).getMethod("id");
            idPosRot = (int) accId.invoke(staticField(display, "DATA_POS_ROT_INTERPOLATION_DURATION_ID"));
            idTransform = (int) accId.invoke(staticField(display, "DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID"));
            netOk = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("[탈것] 탄 사람만 빠르게 따라오는 보간을 쓸 수 없어 모두 같은 보간으로 보입니다: " + e);
        }
    }

    private static Object staticField(Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    // ------------------------------------------------------------------------------------------
    // A-3: seats
    // ------------------------------------------------------------------------------------------

    /**
     * Puts a seat (an entity with riders) at `to` without teleporting its riders; false when it cannot (the caller
     * teleports it instead). Same world only.
     */
    boolean place(Entity seat, Location to) {
        if (!moveOk || to.getWorld() == null || !to.getWorld().equals(seat.getWorld())) return false;
        try {
            Object h = getHandle.invoke(seat);
            setPos.invoke(h, to.getX(), to.getY(), to.getZ());
            setYRot.invoke(h, to.getYaw());
            directMoves.incrementAndGet();
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            moveOk = false;
            if (warnedMove.compareAndSet(false, true))
                log.log(Level.WARNING, "[탈것] 좌석을 텔레포트 없이 옮기지 못해 예전 방식으로 돌아갑니다", e);
            return false;
        }
    }

    boolean directSeats() {
        return moveOk;
    }

    // ------------------------------------------------------------------------------------------
    // A-2: the riders' own glide
    // ------------------------------------------------------------------------------------------

    boolean riderGlide() {
        return netOk;
    }

    /**
     * From now on this player sees the parts with these entity ids glide over `ticks` (where the server gave them
     * `normal`); an empty set: as everyone. Returns whether the set changed (the caller then has the parts' entity
     * data sent again, so the new glide applies at once).
     */
    boolean riderParts(Player p, Set<Integer> ids, int normal, int ticks) {
        if (!netOk) return false;
        Handler h = handlers.get(p.getUniqueId());
        if (h == null) {
            if (ids.isEmpty()) return false;
            h = inject(p);
            if (h == null) return false;
        }
        h.normal = normal;
        h.ticks = ticks;
        if (h.parts.equals(ids)) return false;
        h.parts = Set.copyOf(ids);
        return true;
    }

    private Handler inject(Player p) {
        try {
            Channel ch = channel(p);
            Handler h = new Handler();
            ch.eventLoop().execute(() -> {
                if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
                if (ch.pipeline().get("packet_handler") != null) ch.pipeline().addBefore("packet_handler", HANDLER, h);
            });
            handlers.put(p.getUniqueId(), h);
            return h;
        } catch (Throwable t) {
            netOk = false;
            warnNet(t);
            return null;
        }
    }

    void forget(Player p) {
        Handler h = handlers.remove(p.getUniqueId());
        if (h == null) return;
        try {
            Channel ch = channel(p);
            ch.eventLoop().execute(() -> {
                if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
            });
        } catch (Throwable ignored) {
            // disconnected: the pipeline is gone anyway
        }
    }

    void forgetAll() {
        for (Player p : Bukkit.getOnlinePlayers()) forget(p);
        handlers.clear();
    }

    /** CraftPlayer -> ServerPlayer.connection -> its Connection -> the netty channel (found by type). */
    private Channel channel(Player p) throws ReflectiveOperationException {
        Object handle = p.getClass().getMethod("getHandle").invoke(p);
        Object listener = handle.getClass().getField("connection").get(handle);
        Object connection = fieldOfType(listener, connectionCls);
        Object channel = fieldOfType(connection, Channel.class);
        if (!(channel instanceof Channel c)) throw new NoSuchFieldException("channel");
        return c;
    }

    private static Object fieldOfType(Object owner, Class<?> type) throws ReflectiveOperationException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(owner);
                }
            }
        }
        throw new NoSuchFieldException(type.getName() + " in " + owner.getClass().getName());
    }

    private void warnNet(Throwable t) {
        if (warnedNet.compareAndSet(false, true)) log.log(Level.WARNING, "[탈것] 탄 사람 보간 패킷 처리 실패 (한 번만 표시)", t);
    }

    /** One player's outgoing packets: the entity data of the parts of the vehicle they ride, with the quicker glide. */
    private final class Handler extends ChannelOutboundHandlerAdapter {
        volatile Set<Integer> parts = Set.of();
        volatile int normal = 2, ticks = 1;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Object out = msg;
            if (!parts.isEmpty()) {
                try {
                    out = rewrite(msg, this);
                } catch (Throwable t) {
                    warnNet(t);
                }
            }
            super.write(ctx, out, promise);
        }
    }

    private Object rewrite(Object msg, Handler h) throws ReflectiveOperationException {
        if (dataCls.isInstance(msg)) return data(msg, h);
        if (bundleCls.isInstance(msg)) {
            List<Object> subs = null;
            int i = 0;
            Iterable<?> all = (Iterable<?>) bundleSub.invoke(msg);
            List<Object> seen = new ArrayList<>();
            for (Object sub : all) seen.add(sub);
            for (Object sub : seen) {
                Object to = dataCls.isInstance(sub) ? data(sub, h) : sub;
                if (to != sub && subs == null) subs = new ArrayList<>(seen.subList(0, i));
                if (subs != null) subs.add(to);
                i++;
            }
            return subs == null ? msg : bundleNew.newInstance(subs);
        }
        return msg;
    }

    /** An entity data packet of one of the parts: its glide lengths (the server's usual one only) set to the rider's. */
    private Object data(Object packet, Handler h) throws ReflectiveOperationException {
        int id = (int) dataId.invoke(packet);
        if (!h.parts.contains(id)) return packet;
        List<?> items = (List<?>) dataItems.invoke(packet);
        List<Object> copy = null;
        for (int i = 0; i < items.size(); i++) {
            Object v = items.get(i);
            int key = (int) valueId.invoke(v);
            if (key != idPosRot && key != idTransform) continue;
            // only the usual glide: a longer one on purpose (a door swinging open ...) keeps its length
            if (!(valueValue.invoke(v) instanceof Integer n) || n != h.normal) continue;
            if (copy == null) copy = new ArrayList<>(items);
            copy.set(i, valueNew.newInstance(key, valueSerializer.invoke(v), h.ticks));
        }
        if (copy == null) return packet;
        rewritten.incrementAndGet();
        return dataNew.newInstance(id, copy);
    }
}
