package dev.rackcraft.item;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.recipe.Ingredient;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/** The Hydrogen Jetpack, worn on the chest. It never wears out; its worn texture is under the minecraft namespace like the Respirator's. */
public final class JetpackMaterial implements ArmorMaterial {
	public static final JetpackMaterial INSTANCE = new JetpackMaterial();

	private JetpackMaterial() {}

	@Override public int getDurability(ArmorItem.Type type) { return 400; }
	@Override public int getProtection(ArmorItem.Type type) { return 3; }
	@Override public int getEnchantability() { return 0; }
	@Override public SoundEvent getEquipSound() { return SoundEvents.ITEM_ARMOR_EQUIP_IRON; }
	@Override public Ingredient getRepairIngredient() { return Ingredient.EMPTY; }
	@Override public String getName() { return "rackcraft_jetpack"; }
	@Override public float getToughness() { return 0; }
	@Override public float getKnockbackResistance() { return 0; }
}
