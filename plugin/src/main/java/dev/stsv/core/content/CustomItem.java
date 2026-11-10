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
        Effects effects,
        String voucher,
        dev.stsv.core.vehicle.VehicleSpec vehicle
) {

    /** Without a vehicle section (everything but vehicles). */
    public CustomItem(NamespacedKey key, ContentPack pack, Material material, String name, List<String> lore, ModelRef model,
                      Integer maxStack, Boolean glint, boolean unbreakable, FurnitureSpec furniture, Integer fuel, Equipment equipment,
                      Food food, Skin skin, Cosmetic cosmetic, Effects effects, String voucher) {
        this(key, pack, material, name, lore, model, maxStack, glint, unbreakable, furniture, fuel, equipment, food, skin, cosmetic,
                effects, voucher, null);
    }

    /** A drivable vehicle (dev.stsv.core.vehicle): placed on the ground, ridden, picked up again. */
    public boolean isVehicle() {
        return vehicle != null;
    }

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

    /**
     * A cosmetic shown on the player in one of the cosmetic slots. One cosmetic per slot is worn at a time.
     *
     * @param rarity common / rare / epic / legendary
     * @param obtain how to get it, shown on the locked entry in the wardrobe
     * @param offset extra offset from the slot's anchor, in pixels (1/16 block): x right, y up, z back
     * @param scale  size on the player (1 = as modelled)
     * @param bob    floats gently up and down (above the head)
     */
    public record Cosmetic(CosmeticSlot slot, Rarity rarity, String obtain, org.bukkit.util.Vector offset, float scale, boolean bob, Fx fx) {
        public Cosmetic(CosmeticSlot slot, Rarity rarity, String obtain) {
            this(slot, rarity, obtain, new org.bukkit.util.Vector(), 1f, false, null);
        }

        public Cosmetic(CosmeticSlot slot, Rarity rarity, String obtain, org.bukkit.util.Vector offset, float scale, boolean bob) {
            this(slot, rarity, obtain, offset, scale, bob, null);
        }
    }

    /**
     * What a trail, kill effect or pet does: particle (a Bukkit particle name) with count, spread (blocks)
     * and speed; color = a hex colour for DUST, or "rainbow"; sound (a sound key, kill effects);
     * lightning = a harmless lightning strike (kill effects); floating = a pet that hovers; height = how
     * far above the feet a trail mark appears (blocks).
     */
    public record Fx(String particle, int count, double spread, double speed, String color, String sound, boolean lightning, boolean floating,
                     double height) {}

    /**
     * Where a cosmetic is worn. HEAD replaces what others see on the head (the real helmet stays);
     * ABOVE..TAIL are models carried along with the player; PET walks beside them; TRAIL leaves particles
     * where they walk; KILL plays where they kill something.
     */
    public enum CosmeticSlot {
        HEAD("head", "머리"), ABOVE("above", "머리 위"), SHOULDER("shoulder", "어깨"), BACK("back", "등"), TAIL("tail", "꼬리"),
        PET("pet", "펫"), TRAIL("trail", "발자취"), KILL("kill", "처치 효과");

        public final String id, label;

        CosmeticSlot(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** "back" or "등" (also "머리위"); null if unknown. */
        public static CosmeticSlot parse(String s) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            for (CosmeticSlot c : values()) if (c.id.equals(t) || c.label.replace(" ", "").equals(t)) return c;
            return null;
        }
    }

    public enum Rarity {
        COMMON("일반", "<gray>"), RARE("희귀", "<#5ab0ff>"), EPIC("영웅", "<#c58cff>"), LEGENDARY("전설", "<gradient:#ffd36b:#ff8a3d>");

        public final String label, color;

        Rarity(String label, String color) {
            this.label = label;
            this.color = color;
        }
    }

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
