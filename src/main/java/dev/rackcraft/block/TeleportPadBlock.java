package dev.rackcraft.block;

import dev.rackcraft.world.TeleportPads;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * A Quantum Teleport Pad. Stand on it for two seconds to be sent to the pad it is entangled with. Right-click opens its
 * screen (the Annealer slot); a Linked Shard in hand entangles it instead, so the click passes to the item.
 */
public final class TeleportPadBlock extends MachineBlock {
	public TeleportPadBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public void onSteppedOn(World world, BlockPos pos, BlockState state, Entity entity) {
		if (!world.isClient && world instanceof ServerWorld serverWorld && entity instanceof ServerPlayerEntity player) {
			TeleportPads.stand(serverWorld, pos, player);
		}
		super.onSteppedOn(world, pos, state, entity);
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (TeleportPads.isShard(player.getStackInHand(hand))) return ActionResult.PASS;
		return super.onUse(state, world, pos, player, hand, hit);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock()) && world instanceof ServerWorld serverWorld) {
			TeleportPads.get(serverWorld.getServer()).unlink(TeleportPads.key(serverWorld, pos));
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}
}
