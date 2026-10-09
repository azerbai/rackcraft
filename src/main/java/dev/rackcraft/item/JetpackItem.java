package dev.rackcraft.item;

import dev.rackcraft.world.Jetpacks;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

/** The Hydrogen Jetpack: hold jump to climb, sneak in the air to hover, sprint to boost. It burns Hydrogen Canisters. */
public final class JetpackItem extends ArmorItem {
	public JetpackItem(Settings settings) {
		super(JetpackMaterial.INSTANCE, ArmorItem.Type.CHESTPLATE, settings.maxCount(1));
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		int seconds = Jetpacks.fuelSeconds(stack);
		tooltip.add(Text.literal(seconds + " s of thrust in the tank").formatted(Formatting.GRAY));
		tooltip.add(Text.literal("Hold jump: climb. Sneak in the air: hover (half the fuel). Sprint: boost.").formatted(Formatting.DARK_GRAY));
		tooltip.add(Text.literal("Feeds from the Hydrogen Canisters you carry, or in a Wireless Terminal's storage.").formatted(Formatting.DARK_GRAY));
		tooltip.add(Text.literal("Out of gas in a long fall: it vents the last of it once to slow you down.").formatted(Formatting.DARK_GRAY));
	}
}
