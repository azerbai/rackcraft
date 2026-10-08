package dev.rackcraft.item;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.world.SitePlanner;
import java.util.List;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Marks out a site for a Site Planner. Use it on the ground at one corner, then at the other; sneak-use to start over.
 * Use it on a Site Planner to hand the site over. While it is held, the marked area is outlined in the world.
 */
public final class SurveyStakeItem extends Item {
	public static final String FIRST = "CornerA";
	public static final String SECOND = "CornerB";

	public SurveyStakeItem(Settings settings) {
		super(settings);
	}

	public static BlockPos corner(ItemStack stack, String key) {
		NbtCompound nbt = stack.getNbt();
		return nbt != null && nbt.contains(key) ? BlockPos.fromLong(nbt.getLong(key)) : null;
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		PlayerEntity player = context.getPlayer();
		ItemStack stack = context.getStack();
		BlockPos pos = context.getBlockPos();
		if (world.isClient) return ActionResult.SUCCESS;
		if (world.getBlockEntity(pos) instanceof MachineBlockEntity planner && planner.blockId().equals("site_planner")) {
			BlockPos a = corner(stack, FIRST);
			BlockPos b = corner(stack, SECOND);
			tell(player, a == null || b == null ? "Mark both corners of the site first" : SitePlanner.setArea(planner, a, b));
			return ActionResult.SUCCESS;
		}
		NbtCompound nbt = stack.getOrCreateNbt();
		if (player != null && player.isSneaking()) {
			nbt.remove(FIRST);
			nbt.remove(SECOND);
			tell(player, "Survey cleared");
			return ActionResult.SUCCESS;
		}
		if (!nbt.contains(FIRST) || nbt.contains(SECOND)) {
			nbt.putLong(FIRST, pos.asLong());
			nbt.remove(SECOND);
			tell(player, "First corner at " + pos.getX() + ", " + pos.getZ() + ": now mark the opposite one");
		} else {
			nbt.putLong(SECOND, pos.asLong());
			BlockPos a = BlockPos.fromLong(nbt.getLong(FIRST));
			int width = Math.abs(a.getX() - pos.getX()) + 1;
			int depth = Math.abs(a.getZ() - pos.getZ()) + 1;
			tell(player, "Site marked: " + width + " x " + depth + ". Use the stake on a Site Planner to hand it over.");
		}
		return ActionResult.SUCCESS;
	}

	private static void tell(PlayerEntity player, String message) {
		if (player != null) player.sendMessage(Text.literal(message), true);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		BlockPos a = corner(stack, FIRST);
		BlockPos b = corner(stack, SECOND);
		if (a == null) tooltip.add(Text.literal("No site marked").formatted(Formatting.GRAY));
		else if (b == null) tooltip.add(Text.literal("First corner " + a.getX() + ", " + a.getZ()).formatted(Formatting.GRAY));
		else tooltip.add(Text.literal("Site " + (Math.abs(a.getX() - b.getX()) + 1) + " x " + (Math.abs(a.getZ() - b.getZ()) + 1)
				+ " from " + a.getX() + ", " + a.getZ()).formatted(Formatting.GRAY));
	}
}
