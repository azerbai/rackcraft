package dev.rackcraft.item;

import dev.rackcraft.block.Racks;
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

/** An FPGA Module: right-click it to reflash it for mining, AI or autocrafting. The mode stays with the stack. */
public final class FpgaItem extends Item {
	public static final String[] MODES = {"Mining", "AI", "Autocrafting"};

	public FpgaItem(Settings settings) {
		super(settings);
	}

	public static int mode(ItemStack stack) {
		return stack.hasNbt() ? Math.floorMod(stack.getNbt().getInt(Racks.FPGA_MODE), MODES.length) : 0;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);
		if (!world.isClient) {
			int next = (mode(stack) + 1) % MODES.length;
			stack.getOrCreateNbt().putInt(Racks.FPGA_MODE, next);
			player.sendMessage(Text.literal("FPGA reflashed for " + MODES[next]), true);
		}
		return TypedActionResult.success(stack, world.isClient);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.literal("Mode: " + MODES[mode(stack)]).formatted(Formatting.AQUA));
		tooltip.add(Text.literal("Right-click to reflash").formatted(Formatting.GRAY));
	}
}
