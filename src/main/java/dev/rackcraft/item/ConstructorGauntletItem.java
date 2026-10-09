package dev.rackcraft.item;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RcItems;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.world.BuildStock;
import dev.rackcraft.world.Earthworks;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The Constructor's Gauntlet. Sneak-use in the air cycles the mode. Use on a block to mark the first corner and again
 * for the second, which runs the job; sneak-use on a block clears the corner. The block to place is whatever block
 * item is in the off hand, and every block comes out of the player's inventory or a Wireless Terminal's storage.
 * A Battery Cell is spent every {@code building.gauntletUsesPerCell} jobs.
 */
public final class ConstructorGauntletItem extends Item {
	private static final String MODE = "Mode";
	private static final String FIRST = "First";
	private static final String CHARGE = "Charge";
	private static final Earthworks.Mode[] MODES = Earthworks.Mode.values();

	public ConstructorGauntletItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static Earthworks.Mode mode(ItemStack stack) {
		return MODES[stack.getNbt() == null ? 0 : Math.floorMod(stack.getNbt().getInt(MODE), MODES.length)];
	}

	public static int reach(ServerWorld world) {
		return ResearchLab.get(world).done("long_reach") ? RackcraftConfig.values.building.gauntletLongReach : RackcraftConfig.values.building.gauntletReach;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
		ItemStack stack = player.getStackInHand(hand);
		if (world.isClient) return TypedActionResult.success(stack);
		if (player.isSneaking()) {
			NbtCompound nbt = stack.getOrCreateNbt();
			nbt.putInt(MODE, Math.floorMod(nbt.getInt(MODE) + 1, MODES.length));
			nbt.remove(FIRST);
			player.sendMessage(Text.literal("Mode: " + label(mode(stack))), true);
		}
		return TypedActionResult.success(stack);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		if (world.isClient) return ActionResult.SUCCESS;
		if (!(context.getPlayer() instanceof ServerPlayerEntity player) || !(world instanceof ServerWorld serverWorld)) return ActionResult.PASS;
		ItemStack stack = context.getStack();
		NbtCompound nbt = stack.getOrCreateNbt();
		if (player.isSneaking()) {
			nbt.remove(FIRST);
			player.sendMessage(Text.literal("Selection cleared"), true);
			return ActionResult.SUCCESS;
		}
		Earthworks.Mode mode = mode(stack);
		boolean placing = mode == Earthworks.Mode.LINE || mode == Earthworks.Mode.PLANE || mode == Earthworks.Mode.BOX;
		// Placing starts on the open cell beside the block; swapping and deleting work on the blocks themselves.
		BlockPos pos = placing ? context.getBlockPos().offset(context.getSide()) : context.getBlockPos();
		int reach = reach(serverWorld);
		if (Math.sqrt(player.squaredDistanceTo(pos.toCenterPos())) > reach) {
			player.sendMessage(Text.literal("Out of reach: " + reach + " blocks"), true);
			return ActionResult.SUCCESS;
		}
		if (!nbt.contains(FIRST)) {
			nbt.putLong(FIRST, pos.asLong());
			player.sendMessage(Text.literal(label(mode) + ": first corner " + pos.toShortString() + ", now the other"), true);
			return ActionResult.SUCCESS;
		}
		BlockPos first = BlockPos.fromLong(nbt.getLong(FIRST));
		nbt.remove(FIRST);
		Block block = null;
		if (mode != Earthworks.Mode.DELETE) {
			ItemStack offhand = player.getOffHandStack();
			if (!(offhand.getItem() instanceof BlockItem blockItem) || blockItem.getBlock() == Blocks.AIR) {
				player.sendMessage(Text.literal("Hold the block to " + (mode == Earthworks.Mode.SWAP ? "swap in" : "place") + " in your off hand"), true);
				return ActionResult.SUCCESS;
			}
			block = blockItem.getBlock();
		}
		if (Math.sqrt(player.squaredDistanceTo(first.toCenterPos())) > reach) {
			player.sendMessage(Text.literal("The first corner is out of reach now"), true);
			return ActionResult.SUCCESS;
		}
		if (!spendCharge(player, stack)) {
			player.sendMessage(Text.literal("Out of Battery Cells").formatted(Formatting.RED), true);
			return ActionResult.SUCCESS;
		}
		Earthworks.Result result = Earthworks.gauntlet(serverWorld, player, BuildStock.of(player), mode, first, pos, block);
		world.playSound(null, pos, SoundEvents.BLOCK_ANVIL_PLACE, SoundCategory.PLAYERS, 0.4f, 1.6f);
		String summary = label(mode) + ": " + result.changed() + " changed" + (result.skipped() > 0 ? ", " + result.skipped() + " skipped" : "")
				+ (result.note().isEmpty() ? "" : ". " + result.note());
		player.sendMessage(Text.literal(summary), true);
		return ActionResult.SUCCESS;
	}

	/** Takes a use from the glove's charge, replacing the cell when it runs flat. Creative players pay nothing. */
	private static boolean spendCharge(ServerPlayerEntity player, ItemStack stack) {
		if (player.isCreative()) return true;
		NbtCompound nbt = stack.getOrCreateNbt();
		if (nbt.getInt(CHARGE) <= 0) {
			if (BuildStock.of(player).take(RcItems.ITEMS.get("battery_cell"), 1) < 1) return false;
			nbt.putInt(CHARGE, RackcraftConfig.values.building.gauntletUsesPerCell);
		}
		nbt.putInt(CHARGE, nbt.getInt(CHARGE) - 1);
		return true;
	}

	private static String label(Earthworks.Mode mode) {
		return switch (mode) {
			case LINE -> "Line";
			case PLANE -> "Plane";
			case BOX -> "Box fill";
			case SWAP -> "Swap";
			case DELETE -> "Delete";
		};
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		int charge = stack.getNbt() == null ? 0 : stack.getNbt().getInt(CHARGE);
		tooltip.add(Text.literal("Mode: " + label(mode(stack)) + ", " + charge + " uses charged").formatted(Formatting.GRAY));
		tooltip.add(Text.literal("Sneak-use in the air: mode. Block in off hand is placed.").formatted(Formatting.DARK_GRAY));
	}
}
