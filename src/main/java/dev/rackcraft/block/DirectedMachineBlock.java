package dev.rackcraft.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;

/** A machine that works on the block in front of it (a Belt Loader), so it faces away from whoever places it. */
public final class DirectedMachineBlock extends MachineBlock {
	public DirectedMachineBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext context) {
		return getDefaultState().with(FACING, context.getHorizontalPlayerFacing());
	}
}
