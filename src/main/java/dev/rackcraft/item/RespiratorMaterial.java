package dev.rackcraft.item;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/**
 * The Respirator, worn in the helmet slot: it keeps smog out until its filter clogs. Smog wears it down
 * (see AirQuality), not combat, so it barely protects. Its worn texture lives under the minecraft
 * namespace, as armor materials in 1.20.1 can't name a mod's.
 */
public final class RespiratorMaterial implements ArmorMaterial {
	public static final RespiratorMaterial INSTANCE = new RespiratorMaterial();
	/** Every two seconds in smog costs a point, two when it's choking: about 40 minutes of filter. */
	public static final int DURABILITY = 1200;

	private RespiratorMaterial() {}

	@Override public int getDurability(ArmorItem.Type type) { return DURABILITY; }
	@Override public int getProtection(ArmorItem.Type type) { return 1; }
	@Override public int getEnchantability() { return 5; }
	@Override public SoundEvent getEquipSound() { return SoundEvents.ITEM_ARMOR_EQUIP_LEATHER; }
	/** Charcoal: a fresh filter. */
	@Override public Ingredient getRepairIngredient() { return Ingredient.ofItems(Items.CHARCOAL); }
	@Override public String getName() { return "rackcraft_respirator"; }
	@Override public float getToughness() { return 0; }
	@Override public float getKnockbackResistance() { return 0; }
}
