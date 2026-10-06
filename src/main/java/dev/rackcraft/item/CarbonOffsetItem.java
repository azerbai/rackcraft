package dev.rackcraft.item;

import dev.rackcraft.world.AirQuality;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** A Carbon Offset Certificate. Right-click to retire it. It removes one point of smog, which is one more than most. */
public final class CarbonOffsetItem extends Item {
	private static final String[] RECEIPTS = {
			"Offset retired. Somewhere, a tree was promised.",
			"Offset retired. The smog is still here, but it is now carbon neutral.",
			"Offset retired. Your facility is 0.1% greener on paper.",
			"Offset retired. A consultant somewhere just bought a boat."};

	public CarbonOffsetItem(Item.Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);
		if (world instanceof ServerWorld serverWorld) {
			AirQuality air = AirQuality.get(serverWorld);
			air.set(player.getBlockPos(), air.smogAt(player.getBlockPos()) - 1);
			serverWorld.playSound(null, player.getBlockPos(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundCategory.PLAYERS, 0.8f, 1.2f);
			player.sendMessage(Text.literal(RECEIPTS[serverWorld.random.nextInt(RECEIPTS.length)]).formatted(Formatting.GREEN), true);
			if (!player.getAbilities().creativeMode) stack.decrement(1);
		}
		return TypedActionResult.success(stack, world.isClient());
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.literal("Removes 1 smog where you stand. Removes a lot of guilt.").formatted(Formatting.GRAY));
	}
}
