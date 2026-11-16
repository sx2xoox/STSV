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
 *   <li>distance LOD (CosmeticLod): the item of a worn display or pet goes out as its light model to a viewer
 *       who is far from the wearer;</li>
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
    /** head, chest, legs, feet (NMS enum values) */
    private final Set<Object> armorSlots;
    private final Method addEntityId;
    private final Constructor<?> equipNew, setSlotNew, contentNew, invSlotNew, bundleNew;
    private final Method equipEntity, equipSlots;
    private final Method setSlotContainer, setSlotState, setSlotSlot, setSlotItem;
    private final Method contentContainer, contentState, contentItems, contentCarried;
    private final Method invSlotSlot, invSlotContents, bundleSub;
    private final Method pairOf, pairFirst, pairSecond;
    private final Method asNms, asBukkit, nmsCopy;
    /**
     * entity data: near-fade (the wearer's own displays get their own band) and distance LOD (far viewers get the light
     * model); null when the server's classes differ
     */
    private final Class<?> dataCls, nmsItemCls;
    private final Constructor<?> dataNew, valueNew;
    private final Method dataId, dataItems, valueId, valueSerializer, valueValue, valueCreate;
    /** Display.ItemDisplay.DATA_ITEM_STACK_ID (re-sending a display's item to one viewer); null when unknown */
    private final Object itemAccessor;

    /** entity id of a wearer -> the cosmetic (NMS stack) other players see on their head. */
    private final Map<Integer, Object> othersView = new ConcurrentHashMap<>();
    /** players whose armor others do not see (/투명갑옷) */
    private final Set<Integer> armorHidden = ConcurrentHashMap.newKeySet();
    /** per online player: their handler. */
    private final Map<UUID, Handler> handlers = new ConcurrentHashMap<>();
    /** entity id of a wearer -> the ids of the displays riding them on the clients. */
    private final Map<Integer, int[]> links = new ConcurrentHashMap<>();
    /** display id -> its wearer */
    private final Map<Integer, Integer> wearerOf = new ConcurrentHashMap<>();
    /** wearer -> their real passengers (as last seen on the main thread) */
    private final Map<Integer, int[]> riders = new ConcurrentHashMap<>();
    /** "ns:model/x" (or a cosmetic's own item id) -> its light model, see CosmeticLod#table */
    private volatile Map<String, String> lodTable = Map.of();
    /** debug counters: items sent as their light model */
    final java.util.concurrent.atomic.AtomicLong lodSwapped = new java.util.concurrent.atomic.AtomicLong();
    /** debug counters: links put into spawn bundles, passenger packets extended, links re-sent */
    final java.util.concurrent.atomic.AtomicLong linkedSpawns = new java.util.concurrent.atomic.AtomicLong(),
            extendedPassengers = new java.util.concurrent.atomic.AtomicLong(), resent = new java.util.concurrent.atomic.AtomicLong();
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
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Enum> slotEnum = (Class) Class.forName("net.minecraft.world.entity.EquipmentSlot", false, cl);
        armorSlots = Set.of(head, Enum.valueOf(slotEnum, "CHEST"), Enum.valueOf(slotEnum, "LEGS"), Enum.valueOf(slotEnum, "FEET"));

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
        nmsItemCls = nmsItem;

        // optional: without it everyone (the wearer too) sees the displays with the others' near-fade band and full detail
        Class<?> dc = null, vc;
        Constructor<?> dn = null, vn = null;
        Method di = null, ds = null, vi = null, vs = null, vv = null, vcr = null;
        Object acc = null;
        try {
            dc = Class.forName(game + "ClientboundSetEntityDataPacket", false, cl);
            vc = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue", false, cl);
            di = dc.getMethod("id");
            ds = dc.getMethod("packedItems");
            dn = dc.getConstructor(int.class, List.class);
            vi = vc.getMethod("id");
            vs = vc.getMethod("serializer");
            vv = vc.getMethod("value");
            vn = vc.getConstructor(int.class, Class.forName("net.minecraft.network.syncher.EntityDataSerializer", false, cl), Object.class);
            Class<?> accessor = Class.forName("net.minecraft.network.syncher.EntityDataAccessor", false, cl);
            vcr = vc.getMethod("create", accessor, Object.class);
            Field f = Class.forName("net.minecraft.world.entity.Display$ItemDisplay", false, cl).getDeclaredField("DATA_ITEM_STACK_ID");
            f.setAccessible(true);
            acc = f.get(null);
        } catch (ReflectiveOperationException e) {
            dc = null;
            acc = null;
            log.warning("치장 근접 페이드 · 거리별 단순화: 엔티티 데이터 패킷을 쓸 수 없어 내 치장도 남의 것과 같은 거리에서 사라지고, "
                    + "먼 치장도 원래 모델로 보입니다: " + e);
        }
        dataCls = dc;
        dataNew = dn;
        dataId = di;
        dataItems = ds;
        valueNew = vn;
        valueId = vi;
        valueSerializer = vs;
        valueValue = vv;
        valueCreate = vcr;
        itemAccessor = acc;
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

    /** pet display id -> its owner's entity id (the owner sees it with the near-fade PET band) */
    private final Map<Integer, Integer> petOf = new ConcurrentHashMap<>();

    /** A pet display and its owner (owner -1: forget the display). Before the display is shown. */
    void pet(int display, int owner) {
        if (owner < 0) petOf.remove(display);
        else petOf.put(display, owner);
    }

    void clear() {
        petOf.clear();
        othersView.clear();
        armorHidden.clear();
        links.clear();
        wearerOf.clear();
        riders.clear();
    }

    // ------------------------------------------------------------------------------------------
    // Distance LOD (CosmeticLod): main thread decides, network threads swap
    // ------------------------------------------------------------------------------------------

    void lodTable(Map<String, String> table) {
        lodTable = Map.copyOf(table);
    }

    /** Whether this viewer is in the light band of this wearer. */
    boolean light(Player viewer, int wearer) {
        Handler h = handlers.get(viewer.getUniqueId());
        return h != null && h.lightOwners.contains(wearer);
    }

    /** Sets the viewer's band for this wearer (true: light); the caller re-sends the items. */
    void light(Player viewer, int wearer, boolean light) {
        Handler h = handlers.get(viewer.getUniqueId());
        if (h == null || wearer == h.entityId) return;
        if (light) h.lightOwners.add(wearer);
        else h.lightOwners.remove(wearer);
    }

    /** Forgets the bands of wearers this viewer was not evaluated against (no longer tracked, gone). */
    void retainLight(Player viewer, Set<Integer> wearers) {
        Handler h = handlers.get(viewer.getUniqueId());
        if (h != null && !h.lightOwners.isEmpty()) h.lightOwners.retainAll(wearers);
    }

    /** Sends one display's item again to one viewer (the rewrite below picks the full or light model). */
    void resendItem(Player viewer, int display, ItemStack item) {
        if (dataCls == null) return;
        try {
            Object value = valueCreate.invoke(null, itemAccessor, asNms.invoke(null, item == null ? ItemStack.empty() : item));
            send(viewer, dataNew.newInstance(display, List.of(value)));
        } catch (Throwable t) {
            warn(t);
        }
    }

    /** Whether others see a cosmetic on this player's head (its look follows the band too). */
    boolean hasHead(int wearer) {
        return othersView.containsKey(wearer);
    }

    /** Sends the wearer's head cosmetic again to one viewer (the rewrite picks the full or light model). */
    void resendHead(Player viewer, int wearer) {
        Object look = othersView.get(wearer);
        if (look == null) return;
        try {
            send(viewer, equipNew.newInstance(wearer, List.of(pairOf.invoke(null, headSlot, nmsCopy.invoke(look)))));
        } catch (Throwable t) {
            warn(t);
        }
    }

    /** NMS stack -> the same stack drawn as its light model (a copy), or null when it has none. */
    private Object lightLook(Object nms) throws ReflectiveOperationException {
        ItemStack it = (ItemStack) asBukkit.invoke(null, nms);
        if (it == null || !it.hasItemMeta()) return null;
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (!meta.hasItemModel()) return null;
        String model = meta.getItemModel().asString();
        String light = CosmeticLod.view(model, null, true, lodTable);
        if (light.equals(model)) return null;
        meta.setItemModel(org.bukkit.NamespacedKey.fromString(light));
        it.setItemMeta(meta);
        lodSwapped.incrementAndGet();
        return asNms.invoke(null, it);
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
            resent.addAndGet(viewers.size());
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

    /** Hides (or shows again) this player's armor from everyone else. */
    void hideArmor(int entityId, boolean hide) {
        if (hide) armorHidden.add(entityId);
        else armorHidden.remove(entityId);
    }

    void forget(Player p, int entityId) {
        handlers.remove(p.getUniqueId());
        for (Handler h : handlers.values()) h.lightOwners.remove(entityId);
        othersView.remove(entityId);
        armorHidden.remove(entityId);
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
        /** /투명갑옷: the player's own armor drawn without its model (on their own screen) */
        volatile boolean hideSelf;
        volatile Set<String> views = Set.of();
        volatile int entityId;
        /** wearers this viewer sees in the light band (CosmeticLod; main thread writes, this channel reads) */
        final Set<Integer> lightOwners = ConcurrentHashMap.newKeySet();

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
        if (c == dataCls) return displayData(packet, h);
        if (c == passengersCls) {
            int vehicle = (int) passengersVehicle.invoke(packet);
            int[] ids = linked(vehicle, (int[]) passengersIds.invoke(packet));
            if (ids == null) return packet;
            extendedPassengers.incrementAndGet();
            return passengers(vehicle, ids);
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
                    linkedSpawns.incrementAndGet();
                    changed = true;
                }
            }
            return changed ? bundleNew.newInstance(sub) : packet;
        }
        if (h.cosmetic == null && !h.hideSelf) return packet;
        // the player's own armor slots: the head may show the cosmetic; any piece may be drawn without its model
        if (c == setSlotCls) {
            int slot = (int) setSlotSlot.invoke(packet);
            if ((int) setSlotContainer.invoke(packet) != 0 || slot < MENU_HEAD_SLOT || slot > MENU_HEAD_SLOT + 3) return packet;
            Object view = ownArmor(setSlotItem.invoke(packet), slot == MENU_HEAD_SLOT, h);
            return view == null ? packet : setSlotNew.newInstance(0, setSlotState.invoke(packet), slot, view);
        }
        if (c == contentCls) {
            if ((int) contentContainer.invoke(packet) != 0) return packet;
            List<?> items = (List<?>) contentItems.invoke(packet);
            if (items.size() <= MENU_HEAD_SLOT + 3) return packet;
            List<Object> copy = null;
            for (int slot = MENU_HEAD_SLOT; slot <= MENU_HEAD_SLOT + 3; slot++) {
                Object view = ownArmor(items.get(slot), slot == MENU_HEAD_SLOT, h);
                if (view == null) continue;
                if (copy == null) copy = new ArrayList<>(items);
                copy.set(slot, view);
            }
            return copy == null ? packet : contentNew.newInstance(0, contentState.invoke(packet), copy, contentCarried.invoke(packet));
        }
        if (c == invSlotCls) {
            int slot = (int) invSlotSlot.invoke(packet);
            if (slot < INVENTORY_HEAD_SLOT - 3 || slot > INVENTORY_HEAD_SLOT) return packet;   // 36 feet .. 39 head
            Object view = ownArmor(invSlotContents.invoke(packet), slot == INVENTORY_HEAD_SLOT, h);
            return view == null ? packet : invSlotNew.newInstance(slot, view);
        }
        return packet;
    }

    private Object equipment(Object packet, Handler h) throws ReflectiveOperationException {
        int entity = (int) equipEntity.invoke(packet);
        if (entity == h.entityId) return packet; // a player's own equipment is the inventory, handled above
        Object look = othersView.get(entity);
        boolean hide = armorHidden.contains(entity);
        if (look == null && !hide) return packet;
        List<?> slots = (List<?>) equipSlots.invoke(packet);
        List<Object> copy = new ArrayList<>(slots.size() + 1);
        for (Object pair : slots) {
            Object slot = pairFirst.invoke(pair);
            if (look != null && slot == headSlot) continue;
            // hidden armor goes out as nothing (an elytra stays: it is how the player flies, not armor)
            if (hide && armorSlots.contains(slot) && !elytra(pairSecond.invoke(pair))) copy.add(pairOf.invoke(null, slot, empty()));
            else copy.add(pair);
        }
        // always carry the head: packets that leave out an empty helmet slot still need the cosmetic
        if (look != null) {
            Object light = CosmeticLod.lightFor(h.entityId, entity, h.lightOwners) ? lightLook(look) : null;
            copy.add(pairOf.invoke(null, headSlot, light != null ? light : nmsCopy.invoke(look)));
        }
        return equipNew.newInstance(entity, copy);
    }

    /**
     * Entity data of a cosmetic display (worn piece, part, aura, set ring, pet) going to one viewer: its item model as
     * that viewer is to see it, near-fade band and detail picked together ({@link CosmeticLod#view}). Everyone is sent
     * the others' band at full detail; the owner gets their own band (SELF worn, PET for the pet: they fade further from
     * the camera, NearFade) and always the full model; a viewer in the owner's light band gets the light model
     * ("&lt;model&gt;_lod", CosmeticLod) in the others' band.
     */
    private Object displayData(Object packet, Handler h) throws ReflectiveOperationException {
        int id = (int) dataId.invoke(packet);
        Integer wearer = wearerOf.get(id), petOwner = petOf.get(id);
        Integer owner = wearer != null ? wearer : petOwner;
        if (owner == null) return packet;
        boolean light = CosmeticLod.lightFor(h.entityId, owner, h.lightOwners);
        if (owner != h.entityId && !light) return packet;   // as sent to everyone: others' band, full detail
        Map<String, String> table = lodTable;
        List<?> values = (List<?>) dataItems.invoke(packet);
        List<Object> copy = null;
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            Object item = valueValue.invoke(v);
            if (!nmsItemCls.isInstance(item)) continue;
            ItemStack it = (ItemStack) asBukkit.invoke(null, item);
            if (it == null || it.isEmpty()) continue;
            org.bukkit.NamespacedKey model = it.getItemMeta() == null ? null : it.getItemMeta().getItemModel();
            if (model == null) continue;
            String to = CosmeticLod.forViewer(model.asString(), h.entityId, wearer, petOwner, h.lightOwners, table);
            if (to.equals(model.asString())) continue;
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(to);
            it.editMeta(m -> m.setItemModel(key));
            if (light) lodSwapped.incrementAndGet();
            if (copy == null) copy = new ArrayList<>(values);
            copy.set(i, valueNew.newInstance(valueId.invoke(v), valueSerializer.invoke(v), asNms.invoke(null, it)));
        }
        return copy == null ? packet : dataNew.newInstance(id, copy);
    }

    private Object empty;

    private Object empty() throws ReflectiveOperationException {
        if (empty == null) empty = asNms.invoke(null, ItemStack.empty());
        return empty;
    }

    private boolean elytra(Object nms) throws ReflectiveOperationException {
        ItemStack it = (ItemStack) asBukkit.invoke(null, nms);
        return it != null && it.getType() == org.bukkit.Material.ELYTRA;
    }

    /** One of the player's own armor stacks (NMS) -> what their client is shown instead, or null to leave it. */
    private Object ownArmor(Object nmsReal, boolean head, Handler h) throws ReflectiveOperationException {
        if (head && h.cosmetic != null) {
            Object view = self(nmsReal, h.cosmetic, h);
            if (view != null) return view;
        }
        if (!h.hideSelf) return null;
        ItemStack real = (ItemStack) asBukkit.invoke(null, nmsReal);
        // the head: without its equipment model a helmet would float on the head as an item, so it goes through
        // the head wrapper and is drawn as nothing there
        ItemStack hidden = head ? CosmeticViews.hiddenHelmet(real, h.views) : CosmeticViews.hiddenArmor(real);
        return hidden == null ? null : asNms.invoke(null, hidden);
    }

    /** /투명갑옷 on this player's own screen too. */
    void hideSelf(Player p, boolean hide) {
        Handler h = handlers.get(p.getUniqueId());
        if (h != null) h.hideSelf = hide;
    }

    /** NMS helmet stack -> NMS self-view stack, or null to leave it. */
    private Object self(Object nmsReal, String cosmetic, Handler h) throws ReflectiveOperationException {
        ItemStack real = (ItemStack) asBukkit.invoke(null, nmsReal);
        ItemStack view = CosmeticViews.selfView(real, cosmetic, h.views);
        return view == null ? null : asNms.invoke(null, view);
    }
}
