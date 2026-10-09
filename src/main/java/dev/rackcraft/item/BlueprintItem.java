package dev.rackcraft.item;

import dev.rackcraft.world.Blueprints;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

/** A Blueprint: blank until a Blueprint Scanner writes a box of Rackcraft blocks into it. Put one in a Site Planner to print it. */
public final class BlueprintItem extends Item {
	public BlueprintItem(Settings settings) {
		super(settings.maxCount(1));
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return Blueprints.written(stack);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		Blueprints.Blueprint blueprint = Blueprints.read(stack);
		if (blueprint == null) {
			tooltip.add(Text.literal(Blueprints.written(stack) ? "Damaged: the printer will not read it" : "Blank").formatted(Formatting.GRAY));
			return;
		}
		tooltip.add(Text.literal(blueprint.width() + " x " + blueprint.height() + " x " + blueprint.depth() + ", " + blueprint.blocks() + " blocks")
				.formatted(Formatting.GRAY));
		if (blueprint.skipped() > 0) tooltip.add(Text.literal(blueprint.skipped() + " blocks skipped when scanned").formatted(Formatting.DARK_GRAY));
		tooltip.add(Text.literal("Put it in a Site Planner and pick the Blueprint layout").formatted(Formatting.DARK_GRAY));
	}
}
