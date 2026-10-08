package dev.rackcraft.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

/**
 * A robot arm on the Assembly Line: a pedestal block whose arm (drawn by the client's block entity renderer) reaches
 * over the Conveyor Belt it faces. Unlike other machines it faces away from whoever places it, toward the belt.
 */
public final class RobotArmBlock extends MachineBlock {
	private static final VoxelShape SHAPE = VoxelShapes.union(Block.createCuboidShape(2, 0, 2, 14, 3, 14),
			Block.createCuboidShape(4, 3, 4, 12, 12, 12));

	public RobotArmBlock(AbstractBlock.Settings settings) {
		super(settings);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext context) {
		return getDefaultState().with(FACING, context.getHorizontalPlayerFacing());
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}
}
