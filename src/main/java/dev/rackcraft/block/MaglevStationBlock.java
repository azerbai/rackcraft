package dev.rackcraft.block;

import dev.rackcraft.world.MaglevNetwork;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** A Mag-Lev Station: right-click to ride to its destination, sneak-right-click with an empty hand to pick the next one. */
public final class MaglevStationBlock extends MachineBlock {
	public MaglevStationBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (world.isClient || !(world instanceof ServerWorld serverWorld) || !(player instanceof ServerPlayerEntity rider)) return ActionResult.SUCCESS;
		if (player.getStackInHand(hand).isOf(dev.rackcraft.RcItems.ITEMS.get("multimeter"))) return ActionResult.PASS;
		if (!(world.getBlockEntity(pos) instanceof MachineBlockEntity station)) return ActionResult.SUCCESS;
		String result = player.isSneaking() ? MaglevNetwork.cycle(serverWorld, station) : MaglevNetwork.ride(serverWorld, station, rider);
		if (result != null) {
			boolean failed = !result.startsWith("Destination");
			player.sendMessage(Text.literal(result).formatted(failed ? Formatting.RED : Formatting.AQUA), true);
		}
		return ActionResult.SUCCESS;
	}
}
