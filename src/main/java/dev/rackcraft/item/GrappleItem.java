package dev.rackcraft.item;

import dev.rackcraft.world.Grapples;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Use to fire a hook at the block you are looking at and be pulled to it. Sneak to let go. Burns a Battery Cell a shot. */
public final class GrappleItem extends Item {
	public GrappleItem(Settings settings) {
		super(settings.maxCount(1));
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);
		if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer)) return TypedActionResult.success(stack);
		String problem = Grapples.fire(serverPlayer);
		if (problem != null && !problem.isEmpty()) player.sendMessage(Text.literal(problem).formatted(Formatting.RED), true);
		return TypedActionResult.success(stack);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.literal("Use: hook the block you look at, up to " + Grapples.RANGE + " blocks. Sneak: let go.").formatted(Formatting.GRAY));
		tooltip.add(Text.literal("One Battery Cell a shot.").formatted(Formatting.DARK_GRAY));
	}
}
