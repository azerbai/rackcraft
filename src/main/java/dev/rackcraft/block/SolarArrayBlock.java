package dev.rackcraft.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * A Solar Array: one item that places a fixed 3x2 of parts, like a bed. Part 0 is where it was placed; the others run
 * three blocks to the placer's right and two ahead. Every part is a machine on the power network (so arrays touching
 * each other share power with no cables), and breaking any part breaks the lot, dropping the one item.
 */
public final class SolarArrayBlock extends MachineBlock {
	public static final IntProperty PART = IntProperty.of("part", 0, 5);
	public static final int WIDTH = 3;
	public static final int DEPTH = 2;
	private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 8, 16);

	public SolarArrayBlock(AbstractBlock.Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(PART, 0));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		super.appendProperties(builder);
		builder.add(PART);
	}

	/** Where a part sits relative to part 0, for an array placed facing this way. */
	public static BlockPos offset(Direction facing, int part) {
		Direction right = facing.rotateYClockwise();
		int column = part % WIDTH;
		int row = part / WIDTH;
		return new BlockPos(right.getOffsetX() * column + facing.getOffsetX() * row, 0, right.getOffsetZ() * column + facing.getOffsetZ() * row);
	}

	/** Part 0 of the array this part belongs to. */
	public static BlockPos origin(BlockPos pos, BlockState state) {
		return pos.subtract(offset(state.get(FACING), state.get(PART)));
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext context) {
		Direction facing = context.getHorizontalPlayerFacing();
		World world = context.getWorld();
		for (int part = 1; part < WIDTH * DEPTH; part++) {
			BlockPos pos = context.getBlockPos().add(offset(facing, part));
			if (!world.getWorldBorder().contains(pos) || !world.getBlockState(pos).canReplace(context)) return null;
		}
		return getDefaultState().with(FACING, facing).with(PART, 0);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.onPlaced(world, pos, state, placer, stack);
		if (world.isClient) return;
		for (int part = 1; part < WIDTH * DEPTH; part++) {
			world.setBlockState(pos.add(offset(state.get(FACING), part)), state.with(PART, part), Block.NOTIFY_ALL);
		}
	}

	/** In creative, breaking a part takes the array away without dropping it, as for any block. */
	@Override
	public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
		if (!world.isClient && player.isCreative()) {
			BlockPos origin = origin(pos, state);
			for (int part = 0; part < WIDTH * DEPTH; part++) {
				BlockPos other = origin.add(offset(state.get(FACING), part));
				if (!other.equals(pos) && belongs(world.getBlockState(other), state, part)) {
					world.setBlockState(other, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
				}
			}
		}
		super.onBreak(world, pos, state, player);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		super.onStateReplaced(state, world, pos, newState, moved);
		if (world.isClient || state.isOf(newState.getBlock())) return;
		// One part gone: the rest of the array goes too, and part 0 drops the item (unless it was the one broken).
		BlockPos origin = origin(pos, state);
		for (int part = 0; part < WIDTH * DEPTH; part++) {
			BlockPos other = origin.add(offset(state.get(FACING), part));
			if (!other.equals(pos) && belongs(world.getBlockState(other), state, part)) world.breakBlock(other, part == 0);
		}
	}

	private boolean belongs(BlockState other, BlockState state, int part) {
		return other.isOf(this) && other.get(FACING) == state.get(FACING) && other.get(PART) == part;
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}
}
