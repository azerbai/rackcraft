package dev.rackcraft.item;

import dev.rackcraft.block.PylonBlock;
import dev.rackcraft.compute.Research;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.world.NetworkManager;
import dev.rackcraft.world.PylonLinks;
import java.util.List;
import java.util.Locale;
import net.minecraft.block.BlockState;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Use on a Pylon, then on another, to string a span between them. Sneak-use on a Pylon cuts its spans. */
public final class PylonLinkerItem extends Item {
	private static final String FROM = "From";
	private static final String DIMENSION = "Dimension";

	public PylonLinkerItem(Settings settings) {
		super(settings.maxCount(1));
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		BlockPos pos = context.getBlockPos();
		BlockState state = world.getBlockState(pos);
		PlayerEntity player = context.getPlayer();
		if (!PylonBlock.isPylon(state)) return ActionResult.PASS;
		if (world.isClient || player == null || !(world instanceof ServerWorld serverWorld)) return ActionResult.SUCCESS;
		ItemStack stack = context.getStack();
		if (player.isSneaking()) {
			int cut = PylonLinks.get(serverWorld).unlink(pos);
			NetworkManager.get(serverWorld).markDirty();
			tell(player, cut == 0 ? "Nothing strung to this pylon" : "Cut " + cut + (cut == 1 ? " span" : " spans"));
			return ActionResult.SUCCESS;
		}
		String problem = link(serverWorld, stack, pos);
		if (problem != null) tell(player, problem);
		return ActionResult.SUCCESS;
	}

	/** Remembers the first Pylon, then links to the second. Returns what to tell the player. */
	public static String link(ServerWorld world, ItemStack stack, BlockPos pos) {
		if (!ResearchLab.get(world).done("structured_cabling")) return "Inert until " + Research.get("structured_cabling").name() + " is researched";
		NbtCompound nbt = stack.getOrCreateNbt();
		String dimension = world.getRegistryKey().getValue().toString();
		if (!nbt.contains(FROM) || !nbt.getString(DIMENSION).equals(dimension)) {
			nbt.putLong(FROM, pos.asLong());
			nbt.putString(DIMENSION, dimension);
			return "Pylon at " + pos.toShortString() + " marked: now use another";
		}
		BlockPos first = BlockPos.fromLong(nbt.getLong(FROM));
		nbt.remove(FROM);
		BlockState firstState = world.getBlockState(first);
		if (first.equals(pos)) return "That's the same pylon";
		if (!PylonBlock.isPylon(firstState)) return "The first pylon is gone: start again";
		PylonLinks links = PylonLinks.get(world);
		if (links.linked(first, pos)) return "Already strung together";
		if (links.count(first) >= PylonLinks.MAX_SPANS || links.count(pos) >= PylonLinks.MAX_SPANS) {
			return "A pylon holds at most " + PylonLinks.MAX_SPANS + " spans";
		}
		boolean superconducting = PylonBlock.superconducting(firstState) && PylonBlock.superconducting(world.getBlockState(pos));
		int range = superconducting ? PylonLinks.SUPERCONDUCTING_RANGE : PylonLinks.RANGE;
		double length = Math.sqrt(first.getSquaredDistance(pos));
		if (length > range) return String.format(Locale.ROOT, "Too far: %.0f blocks, the limit is %d", length, range);
		links.add(first, pos);
		NetworkManager.get(world).markDirty();
		double loss = PylonLinks.lossFraction(length, superconducting) * 100;
		return String.format(Locale.ROOT, "Span strung, %.0f blocks: %s", length, loss == 0 ? "no loss" : String.format(Locale.ROOT, "%.1f%% of the load lost", loss));
	}

	private static void tell(PlayerEntity player, String message) {
		player.sendMessage(Text.literal(message), true);
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		boolean held = stack.getNbt() != null && stack.getNbt().contains(FROM);
		tooltip.add(Text.literal(held ? "First pylon marked" : "Use on a Pylon, then another").formatted(Formatting.GRAY));
	}
}
