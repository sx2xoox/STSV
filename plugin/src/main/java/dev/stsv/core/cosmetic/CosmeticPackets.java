package dev.stsv.core.cosmetic;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rewrites outgoing packets so the cosmetic is shown without touching the real equipment:
 * <ul>
 *   <li>equipment of a wearer, sent to anyone else: the head item becomes the cosmetic;</li>
 *   <li>the wearer's own helmet slot (inventory menu slot 5, player inventory slot 39): the self-view
 *       item from {@link CosmeticViews#selfView};</li>
 *   <li>the models worn above the head, on the shoulder, back and tail (item displays, see
 *       CosmeticAttachments) ride the wearer on the clients only: whenever a client is shown the wearer
 *       or one of the displays, and whenever the server tells it who rides the wearer, the displays are
 *       added to the wearer's passengers. The client then carries them along with the wearer every frame
 *       (no lag behind a running player). On the server they are not passengers, so teleporting the
 *       player, which fails for players with passengers, is not affected.</li>
 * </ul>
 * Rewriting the packets themselves (instead of re-sending a look after the server sent the real one)
 * means there is never a frame where the real helmet shows, and nothing to re-send on clicks, damage,
 * respawn or new viewers.
 *
 * Paper has no packet API, so this adds a handler to each player's network pipeline and reads the
 * vanilla packet classes by reflection (the server runs with Mojang's names). If anything is missing,
 * the constructor throws and the cosmetic module falls back to sending the look to other players.
 */
final class CosmeticPackets {

    private static final String HANDLER = "stsv_cosmetics";
    private static final int MENU_HEAD_SLOT = 5;       // InventoryMenu.ARMOR_SLOT_START (head)
    private static final int INVENTORY_HEAD_SLOT = 39; // Inventory.EQUIPMENT_SLOT_MAPPING -> HEAD

    private final Logger log;
    private final AtomicBoolean warned = new AtomicBoolean();

    private final Class<?> equipCls, setSlotCls, contentCls, invSlotCls, bundleCls, addEntityCls, connectionCls, passengersCls;
    private final Constructor<?> passengersNew, bufNew;
    private final Method passengersVehicle, passengersIds, bufVarInt, bufVarIntArray;
    private final Object headSlot;
    private final Method addEntityId;
    private final Constructor<?> equipNew, setSlotNew, contentNew, invSlotNew, bundleNew;
    private final Method equipEntity, equipSlots;
    private final Method setSlotContainer, setSlotState, setSlotSlot, setSlotItem;
    private final Method contentContainer, contentState, contentItems, contentCarried;
    private final Method invSlotSlot, invSlotContents, bundleSub;
    private final Method pairOf, pairFirst, pairSecond;
    private final Method asNms, asBukkit, nmsCopy;

    /** entity id of a wearer -> the cosmetic (NMS stack) other players see on their head. */
    private final Map<Integer, Object> othersView = new ConcurrentHashMap<>();
    /** per online player: their handler. */
    private final Map<UUID, Handler> handlers = new ConcurrentHashMap<>();
    /** entity id of a wearer -> the ids of the displays riding them on the clients. */
    private final Map<Integer, int[]> links = new ConcurrentHashMap<>();
    /** display id -> its wearer */
    private final Map<Integer, Integer> wearerOf = new ConcurrentHashMap<>();
    /** wearer -> their real passengers (as last seen on the main thread) */
    private final Map<Integer, int[]> riders = new ConcurrentHashMap<>();
    CosmeticPackets(Logger log) throws ReflectiveOperationException {
        this.log = log;
        ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
        String game = "net.minecraft.network.protocol.game.";
        Class<?> nmsItem = Class.forName("net.minecraft.world.item.ItemStack", false, cl);
        Class<?> pair = Class.forName("com.mojang.datafixers.util.Pair", false, cl);
        connectionCls = Class.forName("net.minecraft.network.Connection", false, cl);

        equipCls = Class.forName(game + "ClientboundSetEquipmentPacket", false, cl);
        equipNew = equipCls.getConstructor(int.class, List.class);
        equipEntity = equipCls.getMethod("getEntity");
        equipSlots = equipCls.getMethod("getSlots");

        setSlotCls = Class.forName(game + "ClientboundContainerSetSlotPacket", false, cl);
        setSlotNew = setSlotCls.getConstructor(int.class, int.class, int.class, nmsItem);
        setSlotContainer = setSlotCls.getMethod("getContainerId");
        setSlotState = setSlotCls.getMethod("getStateId");
        setSlotSlot = setSlotCls.getMethod("getSlot");
        setSlotItem = setSlotCls.getMethod("getItem");

        contentCls = Class.forName(game + "ClientboundContainerSetContentPacket", false, cl);
        contentNew = contentCls.getConstructor(int.class, int.class, List.class, nmsItem);
        contentContainer = contentCls.getMethod("containerId");
        contentState = contentCls.getMethod("stateId");
        contentItems = contentCls.getMethod("items");
        contentCarried = contentCls.getMethod("carriedItem");

        invSlotCls = Class.forName(game + "ClientboundSetPlayerInventoryPacket", false, cl);
        invSlotNew = invSlotCls.getConstructor(int.class, nmsItem);
        invSlotSlot = invSlotCls.getMethod("slot");
        invSlotContents = invSlotCls.getMethod("contents");

        addEntityCls = Class.forName(game + "ClientboundAddEntityPacket", false, cl);
        addEntityId = addEntityCls.getMethod("getId");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object head = Enum.valueOf((Class) Class.forName("net.minecraft.world.entity.EquipmentSlot", false, cl), "HEAD");
        headSlot = head;

        passengersCls = Class.forName(game + "ClientboundSetPassengersPacket", false, cl);
        passengersVehicle = passengersCls.getMethod("getVehicle");
        passengersIds = passengersCls.getMethod("getPassengers");
        Class<?> buf = Class.forName("net.minecraft.network.FriendlyByteBuf", false, cl);
        bufNew = buf.getConstructor(io.netty.buffer.ByteBuf.class);
        bufVarInt = buf.getMethod("writeVarInt", int.class);
        bufVarIntArray = buf.getMethod("writeVarIntArray", int[].class);
        passengersNew = passengersCls.getDeclaredConstructor(buf);   // the decoding constructor (no public one takes ids)
        passengersNew.setAccessible(true);
        passengers(1, new int[]{2});                                  // fail now, not on the network thread

        bundleCls = Class.forName(game + "ClientboundBundlePacket", false, cl);
        bundleNew = bundleCls.getConstructor(Iterable.class);
        bundleSub = bundleCls.getMethod("subPackets");

        pairOf = pair.getMethod("of", Object.class, Object.class);
        pairFirst = pair.getMethod("getFirst");
        pairSecond = pair.getMethod("getSecond");

        Class<?> craftItem = Class.forName(Bukkit.getServer().getClass().getPackageName() + ".inventory.CraftItemStack", false, cl);
        asNms = craftItem.getMethod("asNMSCopy", ItemStack.class);
        // Paper takes the NMS item's interface (ItemInstance), vanilla code the class: match by assignability
        Method bukkit = null;
        for (Method m : craftItem.getMethods()) {
            if (m.getName().equals("asBukkitCopy") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(nmsItem)
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())) bukkit = m;
        }
        if (bukkit == null) throw new NoSuchMethodException("CraftItemStack.asBukkitCopy");
        asBukkit = bukkit;
        nmsCopy = nmsItem.getMethod("copy");
    }

    // ------------------------------------------------------------------------------------------
    // State (main thread writes, network threads read)
    // ------------------------------------------------------------------------------------------

    /** What others see on this player's head; null = their real helmet. */
    void othersView(int entityId, ItemStack cosmetic) {
        if (cosmetic == null) othersView.remove(entityId);
        else {
            try {
                othersView.put(entityId, asNms.invoke(null, cosmetic));
            } catch (ReflectiveOperationException e) {
                warn(e);
            }
        }
    }

    /**
     * What the player sees on their own head; null = their real helmet.
     *
     * @param views item models with a self-view wrapper in the resource pack this client has loaded
     */
    void selfView(Player p, String cosmeticId, Set<String> views) {
        Handler h = handlers.get(p.getUniqueId());
        if (h != null) {
            h.views = views;
            h.cosmetic = cosmeticId;
            h.entityId = p.getEntityId();
        }
    }

    void clear() {
        othersView.clear();
        links.clear();
        wearerOf.clear();
        riders.clear();
    }

    /**
     * The displays that ride the wearer on the clients (empty: none). Takes effect for what is sent from
     * now on: displays spawned after this call arrive linked.
     *
     * @param realPassengers what really rides the wearer on the server (kept in the list)
     */
    void link(int wearer, int[] displays, int[] realPassengers) {
        int[] old = links.remove(wearer);
        if (old != null) for (int d : old) wearerOf.remove(d, wearer);
        if (displays.length == 0) {
            riders.remove(wearer);
            return;
        }
        for (int d : displays) wearerOf.put(d, wearer);
        riders.put(wearer, realPassengers);
        links.put(wearer, displays.clone());
    }

    /**
     * Sends the wearer's link again to these players (each must already know the wearer: they track it
     * or are the wearer). Safety net: the spawn packets already carry it.
     */
    void resendLink(Player wearer, java.util.Collection<? extends Player> viewers) {
        int[] ids = linked(wearer.getEntityId(), riders.getOrDefault(wearer.getEntityId(), new int[0]));
        if (ids == null || viewers.isEmpty()) return;
        try {
            Object packet = passengers(wearer.getEntityId(), ids);
            for (Player v : viewers) send(v, packet);
        } catch (Throwable t) {
            warn(t);
        }
    }

    private volatile Method listenerSend;

    /** ServerPlayer.connection.send(packet): through the pipeline like any other packet. */
    private void send(Player p, Object packet) throws ReflectiveOperationException {
        Object handle = p.getClass().getMethod("getHandle").invoke(p);
        Object listener = handle.getClass().getField("connection").get(handle);
        if (listener == null) return;
        Method m = listenerSend;
        if (m == null) {
            Class<?> packetType = Class.forName("net.minecraft.network.protocol.Packet", false, passengersCls.getClassLoader());
            m = listener.getClass().getMethod("send", packetType);
            listenerSend = m;
        }
        m.invoke(listener, packet);
    }

    /** Builds the "these ride that" packet. */
    private Object passengers(int vehicle, int[] ids) throws ReflectiveOperationException {
        io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer(16);
        try {
            Object buf = bufNew.newInstance(raw);
            bufVarInt.invoke(buf, vehicle);
            bufVarIntArray.invoke(buf, (Object) ids);
            return passengersNew.newInstance(buf);
        } finally {
            raw.release();
        }
    }

    /** Real passengers + displays, without duplicates; null if the wearer has no displays. */
    private int[] linked(int wearer, int[] real) {
        int[] displays = links.get(wearer);
        if (displays == null) return null;
        java.util.LinkedHashSet<Integer> all = new java.util.LinkedHashSet<>();
        for (int r : real) all.add(r);
        for (int d : displays) all.add(d);
        return all.stream().mapToInt(Integer::intValue).toArray();
    }

    boolean installed(Player p) {
        return handlers.containsKey(p.getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // Pipeline
    // ------------------------------------------------------------------------------------------

    void inject(Player p) {
        try {
            Channel ch = channel(p);
            Handler h = new Handler(p.getEntityId());
            ch.eventLoop().execute(() -> {
                if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
                if (ch.pipeline().get("packet_handler") != null) ch.pipeline().addBefore("packet_handler", HANDLER, h);
            });
            handlers.put(p.getUniqueId(), h);
        } catch (Throwable t) {
            warn(t);
        }
    }

    void uninject(Player p) {
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

    void forget(Player p, int entityId) {
        handlers.remove(p.getUniqueId());
        othersView.remove(entityId);
        link(entityId, new int[0], new int[0]);
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

    private void warn(Throwable t) {
        if (warned.compareAndSet(false, true)) {
            log.log(Level.WARNING, "치장 패킷 처리 실패 (이 메시지는 한 번만 표시됩니다)", t);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rewriting
    // ------------------------------------------------------------------------------------------

    private final class Handler extends ChannelOutboundHandlerAdapter {
        volatile String cosmetic;
        volatile Set<String> views = Set.of();
        volatile int entityId;

        Handler(int entityId) {
            this.entityId = entityId;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Object out = msg;
            try {
                out = rewrite(msg, this);
            } catch (Throwable t) {
                warn(t);
            }
            super.write(ctx, out, promise);
        }
    }

    /** Returns the packet to send instead, or the same object if nothing changes. */
    private Object rewrite(Object packet, Handler h) throws ReflectiveOperationException {
        Class<?> c = packet.getClass();
        if (c == equipCls) return equipment(packet, h);
        if (c == passengersCls) {
            int vehicle = (int) passengersVehicle.invoke(packet);
            int[] ids = linked(vehicle, (int[]) passengersIds.invoke(packet));
            return ids == null ? packet : passengers(vehicle, ids);
        }
        if (c == bundleCls) {
            List<Object> sub = new ArrayList<>();
            boolean changed = false;
            List<Integer> spawned = new ArrayList<>();
            Set<Integer> equipped = new java.util.HashSet<>();
            for (Object p : (Iterable<?>) bundleSub.invoke(packet)) {
                Object r = rewrite(p, h);
                changed |= r != p;
                sub.add(r);
                if (p.getClass() == addEntityCls) spawned.add((int) addEntityId.invoke(p));
                else if (p.getClass() == equipCls) equipped.add((int) equipEntity.invoke(p));
            }
            // the spawn packets leave out empty slots: a wearer without a helmet still needs a head item
            for (int id : spawned) {
                Object look = othersView.get(id);
                if (look != null && id != h.entityId && !equipped.contains(id)) {
                    sub.add(equipNew.newInstance(id, List.of(pairOf.invoke(null, headSlot, nmsCopy.invoke(look)))));
                    changed = true;
                }
            }
            // a wearer or one of their displays appears: (re)link them (a client that does not know one of
            // the two yet ignores the unknown ids; the other one's spawn links them again)
            Set<Integer> relinked = new java.util.HashSet<>();
            for (int id : spawned) {
                Integer wearer = links.containsKey(id) ? Integer.valueOf(id) : wearerOf.get(id);
                if (wearer == null || !relinked.add(wearer)) continue;
                int[] ids = linked(wearer, riders.getOrDefault(wearer, new int[0]));
                if (ids != null) {
                    sub.add(passengers(wearer, ids));
                    changed = true;
                }
            }
            return changed ? bundleNew.newInstance(sub) : packet;
        }
        String cosmetic = h.cosmetic;
        if (cosmetic == null) return packet;
        if (c == setSlotCls) {
            if ((int) setSlotContainer.invoke(packet) != 0 || (int) setSlotSlot.invoke(packet) != MENU_HEAD_SLOT) return packet;
            Object view = self(setSlotItem.invoke(packet), cosmetic, h);
            return view == null ? packet : setSlotNew.newInstance(0, setSlotState.invoke(packet), MENU_HEAD_SLOT, view);
        }
        if (c == contentCls) {
            if ((int) contentContainer.invoke(packet) != 0) return packet;
            List<?> items = (List<?>) contentItems.invoke(packet);
            if (items.size() <= MENU_HEAD_SLOT) return packet;
            Object view = self(items.get(MENU_HEAD_SLOT), cosmetic, h);
            if (view == null) return packet;
            List<Object> copy = new ArrayList<>(items);
            copy.set(MENU_HEAD_SLOT, view);
            return contentNew.newInstance(0, contentState.invoke(packet), copy, contentCarried.invoke(packet));
        }
        if (c == invSlotCls) {
            if ((int) invSlotSlot.invoke(packet) != INVENTORY_HEAD_SLOT) return packet;
            Object view = self(invSlotContents.invoke(packet), cosmetic, h);
            return view == null ? packet : invSlotNew.newInstance(INVENTORY_HEAD_SLOT, view);
        }
        return packet;
    }

    private Object equipment(Object packet, Handler h) throws ReflectiveOperationException {
        int entity = (int) equipEntity.invoke(packet);
        if (entity == h.entityId) return packet; // a player's own equipment is the inventory, handled above
        Object look = othersView.get(entity);
        if (look == null) return packet;
        List<?> slots = (List<?>) equipSlots.invoke(packet);
        List<Object> copy = new ArrayList<>(slots.size() + 1);
        for (Object pair : slots) {
            if (pairFirst.invoke(pair) != headSlot) copy.add(pair);
        }
        // always carry the head: packets that leave out an empty helmet slot still need the cosmetic
        copy.add(pairOf.invoke(null, headSlot, nmsCopy.invoke(look)));
        return equipNew.newInstance(entity, copy);
    }

    /** NMS helmet stack -> NMS self-view stack, or null to leave it. */
    private Object self(Object nmsReal, String cosmetic, Handler h) throws ReflectiveOperationException {
        ItemStack real = (ItemStack) asBukkit.invoke(null, nmsReal);
        ItemStack view = CosmeticViews.selfView(real, cosmetic, h.views);
        return view == null ? null : asNms.invoke(null, view);
    }
}
