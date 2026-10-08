package dev.rackcraft.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/** A Tower Section: a slim column that a Wind Tower stands on and its power runs down. Air passes it. */
public final class TowerSectionBlock extends MachineBlock {
	private static final VoxelShape SHAPE = Block.createCuboidShape(3, 0, 3, 13, 16, 13);

	public TowerSectionBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}
}
