package dev.rackcraft.item;

import dev.rackcraft.block.TeleportPadBlock;
import dev.rackcraft.compute.Research;
import dev.rackcraft.world.TeleportPads;
import java.util.List;
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

/**
 * A Linked Shard entangles two Teleport Pads: use it on one, then on the other, and it is used up. A Dimensional Shard
 * does the same for pads in different dimensions. Sneak-use on a pad cuts its pair (the shard is not given back).
 */
public final class LinkedShardItem extends Item {
	private final boolean dimensional;

	public LinkedShardItem(Settings settings, boolean dimensional) {
		super(settings.maxCount(1));
		this.dimensional = dimensional;
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		BlockPos pos = context.getBlockPos();
		PlayerEntity player = context.getPlayer();
		if (!(world.getBlockState(pos).getBlock() instanceof TeleportPadBlock)) return ActionResult.PASS;
		if (world.isClient || player == null || !(world instanceof ServerWorld serverWorld)) return ActionResult.SUCCESS;
		player.sendMessage(Text.literal(use(serverWorld, context.getStack(), pos, player.isSneaking(), player.isCreative())), true);
		return ActionResult.SUCCESS;
	}

	/** Marks the first pad, then links to the second. Returns what to tell the player. */
	public String use(ServerWorld world, ItemStack stack, BlockPos pos, boolean sneaking, boolean creative) {
		if (!TeleportPads.researched(world)) return "Inert until " + Research.get(TeleportPads.GATE).name() + " is researched";
		TeleportPads pairs = TeleportPads.get(world.getServer());
		String here = TeleportPads.key(world, pos);
		if (sneaking) {
			return pairs.unlink(here) == null ? "This pad isn't entangled with anything" : "Entanglement cut";
		}
		if (pairs.linked(here)) return "This pad is already entangled: sneak-use a shard on it to cut the pair";
		NbtCompound nbt = stack.getOrCreateNbt();
		if (!nbt.contains("From")) {
			nbt.putString("From", here);
			return "First pad marked: use the shard on the other one";
		}
		String first = nbt.getString("From");
		nbt.remove("From");
		if (first.equals(here)) return "That's the same pad";
		if (pairs.linked(first)) return "The first pad was entangled in the meantime";
		boolean cross = !TeleportPads.dimensionOf(first).equals(world.getRegistryKey().getValue());
		if (cross && !dimensional) return "Pads in different dimensions need a Dimensional Shard";
		pairs.link(first, here, cross);
		if (!creative) stack.decrement(1);
		return cross ? "Pads entangled across dimensions" : "Pads entangled";
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return stack.getNbt() != null && stack.getNbt().contains("From") || dimensional;
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		tooltip.add(Text.literal(stack.getNbt() != null && stack.getNbt().contains("From") ? "First pad marked"
				: "Use on a Teleport Pad, then another").formatted(Formatting.GRAY));
		if (dimensional) tooltip.add(Text.literal("Pairs pads in different dimensions").formatted(Formatting.DARK_GRAY));
	}
}
