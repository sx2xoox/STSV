package dev.stsv.core.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.content.CustomItem;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * How the wearer sees their own cosmetic without it taking the helmet slot.
 *
 * The client draws its own head from the inventory helmet slot, so the slot and the head cannot hold
 * different items. But one item can LOOK different in the two places: an item model definition can
 * branch on the display context. For each item that can be worn on the head we generate a wrapper
 * definition: in the "head" context it shows the cosmetic named by custom_model_data, everywhere else
 * it is the item's own definition, unchanged (trims, dye...). The wearer's helmet slot is sent as
 * {@link #selfView}: a copy of the real helmet (same name, enchantments, durability, tooltip) pointed
 * at the wrapper. An empty slot is sent as an item that looks like the empty helmet slot and has no
 * tooltip. The server never sees these items: they are swapped in on the way out (CosmeticPackets).
 */
public final class CosmeticViews {

    /** Wrapper definitions live under assets/stsv/items/cosmetic_view/. */
    static final String PREFIX = "cosmetic_view/";
    static final Key EMPTY = Key.key("stsv", PREFIX + "empty");
    static final String EMPTY_SLOT_SPRITE = "stsv:item/cosmetic_view/empty_helmet_slot";
    /** Marks a self-view item; holds the real item (bytes, empty = the slot was empty). */
    static final NamespacedKey MARKER = new NamespacedKey("stsv", "cosmetic_view");

    private static final Set<Material> SKULLS = EnumSet.of(Material.SKELETON_SKULL, Material.WITHER_SKELETON_SKULL,
            Material.ZOMBIE_HEAD, Material.CREEPER_HEAD, Material.DRAGON_HEAD, Material.PIGLIN_HEAD, Material.PLAYER_HEAD);

    private static Map<String, JsonObject> vanilla;

    private CosmeticViews() {
    }

    // ------------------------------------------------------------------------------------------
    // Resource pack
    // ------------------------------------------------------------------------------------------

    /** Vanilla definitions of the items that can be worn on the head (tools/assetgen/vanilla_head_items.py). */
    static synchronized Map<String, JsonObject> vanillaHeadItems() {
        if (vanilla != null) return vanilla;
        try (InputStream in = CosmeticViews.class.getResourceAsStream("/pack/vanilla_head_items.json")) {
            if (in == null) throw new IOException("pack/vanilla_head_items.json 없음");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, JsonObject> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("items").entrySet()) {
                out.put(e.getKey(), e.getValue().getAsJsonObject());
            }
            vanilla = Map.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("바닐라 머리 아이템 정의를 읽지 못함", e);
        }
        return vanilla;
    }

    /**
     * Item model keys that get a wrapper: every vanilla head item, custom items worn on the head or
     * made from a head item, and skin models (a skin can be applied to a helmet).
     */
    public static Set<String> targets(Collection<CustomItem> items) {
        Set<String> out = new TreeSet<>(vanillaHeadItems().keySet());
        Set<String> headMaterials = new TreeSet<>();
        for (String k : vanillaHeadItems().keySet()) headMaterials.add(k.substring("minecraft:".length()));
        for (CustomItem i : items) {
            if (i.cosmetic() != null || i.itemModelKey() == null) continue;
            boolean head = (i.equipment() != null && i.equipment().slot() == EquipmentSlot.HEAD)
                    || headMaterials.contains(i.material().getKey().getKey());
            if (head) out.add(i.itemModelKey().asString());
            if (i.skin() != null && i.skin().model() != null) out.add(i.skin().model().asString());
        }
        return out;
    }

    static String wrapperKey(String modelKey) {
        Key k = Key.key(modelKey);
        return "stsv:" + PREFIX + k.namespace() + "/" + k.value();
    }

    /**
     * Writes the wrapper definitions, the empty-slot item and the atlas entry for its sprite.
     *
     * @param read reads an already generated pack file (assets/...) as JSON, or null if absent
     * @param write writes a pack file
     * @return the item model keys that got a wrapper (empty: no cosmetics, nothing written)
     */
    public static Set<String> write(Collection<CustomItem> items, Function<String, JsonObject> read,
                             BiConsumer<String, JsonObject> write, List<String> warnings) {
        // head branch: custom_model_data string = cosmetic id -> the cosmetic's own model
        JsonArray cases = new JsonArray();
        for (CustomItem c : items) {
            if (c.cosmetic() == null || c.itemModelKey() == null) continue;
            JsonObject def = read.apply(definitionPath(c.itemModelKey().asString()));
            if (def == null || !def.has("model")) {
                warnings.add("치장 " + c.key() + ": 모델 정의가 없어 본인 화면용 머리 모습을 만들지 못함");
                continue;
            }
            JsonObject when = new JsonObject();
            when.addProperty("when", c.key().asString());
            when.add("model", def.get("model").deepCopy());
            cases.add(when);
        }
        if (cases.isEmpty()) return Set.of();

        Set<String> written = new TreeSet<>();
        for (String target : targets(items)) {
            JsonObject original = vanillaHeadItems().containsKey(target) ? vanillaHeadItems().get(target)
                    : read.apply(definitionPath(target));
            if (original == null || !original.has("model")) continue; // not generated (e.g. a raw model missing)
            write.accept(definitionPath(wrapperKey(target)), wrap(original, cases));
            written.add(target);
        }

        // empty helmet slot: looks like the vanilla empty slot icon, no tooltip
        JsonObject emptyModel = new JsonObject();
        emptyModel.addProperty("parent", "minecraft:item/generated");
        JsonObject tex = new JsonObject();
        tex.addProperty("layer0", EMPTY_SLOT_SPRITE);
        emptyModel.add("textures", tex);
        write.accept("assets/stsv/models/item/cosmetic_view/empty_helmet_slot.json", emptyModel);
        JsonObject emptyRef = new JsonObject();
        emptyRef.addProperty("type", "minecraft:model");
        emptyRef.addProperty("model", "stsv:item/cosmetic_view/empty_helmet_slot");
        JsonObject emptyDef = new JsonObject();
        emptyDef.add("model", emptyRef);
        write.accept(definitionPath(EMPTY.asString()), wrap(emptyDef, cases));

        // the sprite is the vanilla GUI texture itself, added to the item atlas (atlas files of all packs merge)
        String atlasPath = "assets/minecraft/atlases/items.json";
        JsonObject atlas = read.apply(atlasPath);
        if (atlas == null) atlas = new JsonObject();
        JsonArray sources = atlas.has("sources") ? atlas.getAsJsonArray("sources") : new JsonArray();
        JsonObject single = new JsonObject();
        single.addProperty("type", "minecraft:single");
        single.addProperty("resource", "minecraft:gui/sprites/container/slot/helmet");
        single.addProperty("sprite", EMPTY_SLOT_SPRITE);
        sources.add(single);
        atlas.add("sources", sources);
        write.accept(atlasPath, atlas);
        return Set.copyOf(written);
    }

    static String definitionPath(String modelKey) {
        Key k = Key.key(modelKey);
        return "assets/" + k.namespace() + "/items/" + k.value() + ".json";
    }

    /** head -> cosmetic by custom_model_data (unknown id: the original); every other context -> the original. */
    static JsonObject wrap(JsonObject original, JsonArray cosmeticCases) {
        JsonObject originalModel = original.getAsJsonObject("model");
        JsonObject byCosmetic = new JsonObject();
        byCosmetic.addProperty("type", "minecraft:select");
        byCosmetic.addProperty("property", "minecraft:custom_model_data");
        byCosmetic.addProperty("index", 0);
        byCosmetic.add("cases", cosmeticCases.deepCopy());
        byCosmetic.add("fallback", originalModel.deepCopy());

        JsonObject headCase = new JsonObject();
        headCase.addProperty("when", "head");
        headCase.add("model", byCosmetic);
        JsonArray contexts = new JsonArray();
        contexts.add(headCase);
        JsonObject byContext = new JsonObject();
        byContext.addProperty("type", "minecraft:select");
        byContext.addProperty("property", "minecraft:display_context");
        byContext.add("cases", contexts);
        byContext.add("fallback", originalModel.deepCopy());

        JsonObject out = original.deepCopy(); // keeps hand_animation_on_swap, oversized_in_gui, ...
        out.add("model", byContext);
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Items (thread-safe: built on the network thread from the packet's own item)
    // ------------------------------------------------------------------------------------------

    /**
     * What the wearer's own helmet slot is shown as. Returns null when the real item cannot be wrapped
     * (no wrapper for its model, or it uses custom_model_data itself): then the slot is left as it is.
     */
    @SuppressWarnings("UnstableApiUsage")
    static ItemStack selfView(ItemStack real, String cosmeticId, Set<String> targets) {
        if (targets.isEmpty()) return null; // the resource pack has no self-view models
        CustomModelData cmd = CustomModelData.customModelData().addString(cosmeticId).build();
        if (real == null || real.isEmpty()) {
            ItemStack f = ItemStack.of(Material.PAPER);
            f.setData(DataComponentTypes.ITEM_MODEL, EMPTY);
            f.setData(DataComponentTypes.CUSTOM_MODEL_DATA, cmd);
            f.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
            f.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
            f.editPersistentDataContainer(pdc -> pdc.set(MARKER, PersistentDataType.BYTE_ARRAY, new byte[0]));
            return f;
        }
        if (real.isDataOverridden(DataComponentTypes.CUSTOM_MODEL_DATA)) return null;
        Key model = real.getData(DataComponentTypes.ITEM_MODEL);
        if (model == null || !targets.contains(model.asString())) return null;

        // skulls are drawn by the skull renderer whatever their model: use a plain item with the same data
        ItemStack f = SKULLS.contains(real.getType()) ? sameDataAs(real) : real.clone();
        f.setData(DataComponentTypes.ITEM_MODEL, Key.key(wrapperKey(model.asString())));
        f.setData(DataComponentTypes.CUSTOM_MODEL_DATA, cmd);
        // an equipment asset makes the client draw armor instead of the head item: drop only the asset
        // (keeps camera overlays such as the carved pumpkin's, sounds and swapping)
        Equippable eq = real.getData(DataComponentTypes.EQUIPPABLE);
        if (eq != null && eq.assetId() != null) {
            f.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(eq.slot())
                    .equipSound(eq.equipSound())
                    .assetId(null)
                    .cameraOverlay(eq.cameraOverlay())
                    .allowedEntities(eq.allowedEntities())
                    .dispensable(eq.dispensable())
                    .swappable(eq.swappable())
                    .damageOnHurt(eq.damageOnHurt())
                    .equipOnInteract(eq.equipOnInteract())
                    .canBeSheared(eq.canBeSheared())
                    .shearSound(eq.shearSound())
                    .build());
        }
        byte[] original = real.serializeAsBytes();
        f.editPersistentDataContainer(pdc -> pdc.set(MARKER, PersistentDataType.BYTE_ARRAY, original));
        return f;
    }

    /** A paper item carrying exactly the data of {@code real} (all components, defaults included). */
    @SuppressWarnings("UnstableApiUsage")
    private static ItemStack sameDataAs(ItemStack real) {
        ItemStack f = ItemStack.of(Material.PAPER, real.getAmount());
        f.copyDataFrom(real, t -> true);
        for (DataComponentType t : f.getDataTypes()) if (!real.hasData(t)) f.unsetData(t);
        // a player head's name comes from its profile, not from a component
        if (real.getType() == Material.PLAYER_HEAD && !real.hasData(DataComponentTypes.CUSTOM_NAME)) {
            ResolvableProfile profile = real.getData(DataComponentTypes.PROFILE);
            if (profile != null && profile.name() != null) {
                f.setData(DataComponentTypes.ITEM_NAME,
                        Component.translatable("block.minecraft.player_head.named", Component.text(profile.name())));
            }
        }
        return f;
    }

    /**
     * A creative-mode client can send a self-view item back as if it were real; turn it back into what
     * it stands for. Returns the item unchanged if it is not a self-view item.
     */
    static ItemStack restore(ItemStack item) {
        if (item == null || item.isEmpty()) return item;
        byte[] original = item.getPersistentDataContainer().get(MARKER, PersistentDataType.BYTE_ARRAY);
        if (original == null) return item;
        return original.length == 0 ? ItemStack.empty() : ItemStack.deserializeBytes(original);
    }
}
