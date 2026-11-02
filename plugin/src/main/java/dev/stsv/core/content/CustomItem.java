package dev.stsv.core.content;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.UseCooldown;
import io.papermc.paper.datacomponent.item.UseRemainder;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A custom item defined in a content pack's items/*.yml.
 */
public record CustomItem(
        NamespacedKey key,
        ContentPack pack,
        Material material,
        String name,
        List<String> lore,
        ModelRef model,
        Integer maxStack,
        Boolean glint,
        boolean unbreakable,
        FurnitureSpec furniture,
        Integer fuel,
        Equipment equipment,
        Food food,
        Skin skin,
        Cosmetic cosmetic,
        Effects effects
) {

    /**
     * Visual weapon effects.
     *
     * @param swingModel  item model of the slash shown when swinging, or null
     * @param chargeTicks hold right-click this long for a charged release, 0 = no charge
     */
    public record Effects(NamespacedKey swingModel, float swingScale, Key swingSound,
                          int chargeTicks, Key chargeSound) {}

    /**
     * How the item looks when worn (armor). {@code asset} points at assets/&lt;ns&gt;/equipment/&lt;id&gt;.json.
     */
    public record Equipment(org.bukkit.inventory.EquipmentSlot slot, NamespacedKey asset) {}

    /**
     * Makes the item edible.
     *
     * @param remainder item given back after eating (e.g. "minecraft:bowl"), or null
     * @param cooldown  seconds before it can be eaten again, 0 = none
     * @param heal      extra health restored on eating (half hearts), 0 = none (handled by the plugin)
     */
    public record Food(int nutrition, float saturation, boolean alwaysEat, float seconds, ItemUseAnimation animation,
                       Key sound, String remainder, float cooldown, List<FoodEffect> effects, boolean clearEffects,
                       double heal) {}

    /** A potion effect applied on eating, with a chance 0..1. */
    public record FoodEffect(PotionEffect effect, float chance) {}

    /**
     * A skin book: changes how matching items look, nothing else.
     *
     * @param model     item model to show (e.g. stsv:model/test_sword_skin)
     * @param appliesTo "#minecraft:swords", "minecraft:bow", "stsv:test_sword"
     * @param asset     optional equipment asset for armor skins
     */
    public record Skin(NamespacedKey model, List<String> appliesTo, NamespacedKey asset, boolean returnOnRemove) {}

    /** A cosmetic shown on the player in the given slot (only "head" for now). */
    public record Cosmetic(org.bukkit.inventory.EquipmentSlot slot) {}

    /** Persistent tag that marks an ItemStack as this custom item. */
    public static NamespacedKey idKey() {
        return new NamespacedKey("stsv", "id");
    }

    /** Key of the item model definition (assets/&lt;ns&gt;/items/&lt;id&gt;.json) the client renders. */
    public NamespacedKey itemModelKey() {
        if (model == null) return null;
        if (model.kind() == ModelRef.Kind.RAW) return NamespacedKey.fromString(model.value());
        return key;
    }

    public boolean isFurniture() {
        return furniture != null;
    }

    @SuppressWarnings("UnstableApiUsage")
    public ItemStack create(int amount) {
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (name != null) meta.itemName(Text.item(name));
        if (lore != null && !lore.isEmpty()) meta.lore(Text.itemLines(lore));
        NamespacedKey modelKey = itemModelKey();
        if (modelKey != null) meta.setItemModel(modelKey);
        if (maxStack != null) meta.setMaxStackSize(maxStack);
        if (glint != null) meta.setEnchantmentGlintOverride(glint);
        if (unbreakable) meta.setUnbreakable(true);
        if (equipment != null) {
            org.bukkit.inventory.meta.components.EquippableComponent eq = meta.getEquippable();
            eq.setSlot(equipment.slot());
            eq.setModel(equipment.asset());
            meta.setEquippable(eq);
        }
        meta.getPersistentDataContainer().set(idKey(), PersistentDataType.STRING, key.asString());
        stack.setItemMeta(meta);

        if (food != null) applyFood(stack);
        else if (effects != null && effects.chargeTicks() > 0) {
            // usable without being eaten: raised like a trident while held, never finishes
            stack.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                    .consumeSeconds(3600)
                    .animation(ItemUseAnimation.TRIDENT)
                    .sound(Key.key("minecraft:intentionally_empty"))
                    .hasConsumeParticles(false)
                    .build());
        }
        return stack;
    }

    @SuppressWarnings("UnstableApiUsage")
    private void applyFood(ItemStack stack) {
        stack.setData(DataComponentTypes.FOOD, FoodProperties.food()
                .nutrition(food.nutrition())
                .saturation(food.saturation())
                .canAlwaysEat(food.alwaysEat())
                .build());
        Consumable.Builder consumable = Consumable.consumable()
                .consumeSeconds(food.seconds())
                .animation(food.animation())
                .hasConsumeParticles(food.animation() == ItemUseAnimation.EAT);
        if (food.sound() != null) consumable.sound(food.sound());
        // group effects by chance: one apply_effects entry per probability
        Map<Float, List<PotionEffect>> byChance = new TreeMap<>();
        for (FoodEffect fe : food.effects()) byChance.computeIfAbsent(fe.chance(), k -> new ArrayList<>()).add(fe.effect());
        byChance.forEach((chance, effects) -> consumable.addEffect(ConsumeEffect.applyStatusEffects(effects, chance)));
        if (food.clearEffects()) consumable.addEffect(ConsumeEffect.clearAllStatusEffects());
        stack.setData(DataComponentTypes.CONSUMABLE, consumable.build());
        if (food.remainder() != null && STSVPlugin.get() != null) {
            stack.setData(DataComponentTypes.USE_REMAINDER, UseRemainder.useRemainder(STSVPlugin.get().recipes().stack(food.remainder(), 1)));
        }
        if (food.cooldown() > 0) {
            stack.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(food.cooldown()).cooldownGroup(key).build());
        }
    }
}
