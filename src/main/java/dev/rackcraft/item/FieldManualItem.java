package dev.rackcraft.item;

import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

public final class FieldManualItem extends Item {
	/** Set by the client entrypoint; the guide screen only exists on the client. */
	public static Runnable openScreen = () -> {};

	public FieldManualItem(Item.Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		if (world.isClient) openScreen.run();
		return TypedActionResult.success(player.getStackInHand(hand), world.isClient);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.translatable("tooltip.rackcraft.field_manual").formatted(Formatting.GRAY));
	}
}
