package dev.rackcraft.block;

import dev.rackcraft.RcBlocks;
import dev.rackcraft.world.NetworkManager;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Holds no data. It exists so cables re-register with their network whenever their chunk loads;
 * the network graph is not saved, and Block#onBlockAdded only runs when a cable is placed.
 */
public final class CableBlockEntity extends BlockEntity {
	public CableBlockEntity(BlockPos pos, BlockState state) {
		super(RcBlocks.CABLE_ENTITY, pos, state);
	}

	@Override
	public void setWorld(World world) {
		super.setWorld(world);
		if (world instanceof ServerWorld serverWorld && getCachedState().getBlock() instanceof CableBlock cable) {
			NetworkManager.get(serverWorld).register(pos, Set.of(cable.kind()));
		}
	}

	@Override
	public void markRemoved() {
		if (world instanceof ServerWorld serverWorld) NetworkManager.get(serverWorld).unregister(pos);
		super.markRemoved();
	}
}
