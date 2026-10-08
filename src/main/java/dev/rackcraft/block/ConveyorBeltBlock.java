package dev.rackcraft.block;

import dev.rackcraft.RcBlocks;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/** A Conveyor Belt: a low slab that carries items (and anyone standing on it) the way it faces. */
public final class ConveyorBeltBlock extends BlockWithEntity {
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 5, 16);

	public ConveyorBeltBlock(AbstractBlock.Settings settings) {
		super(settings);
		setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/** A belt runs away from whoever places it. */
	@Override
	public BlockState getPlacementState(ItemPlacementContext context) {
		return getDefaultState().with(FACING, context.getHorizontalPlayerFacing());
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	public BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new BeltBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return checkType(type, RcBlocks.BELT_ENTITY, BeltBlockEntity::tick);
	}

	/** Right-click with an item to put one on the belt; with an empty hand to take it off. */
	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if (!(world.getBlockEntity(pos) instanceof BeltBlockEntity belt)) return ActionResult.PASS;
		ItemStack held = player.getStackInHand(hand);
		if (held.isEmpty()) {
			if (belt.stack().isEmpty()) return ActionResult.PASS;
			if (!world.isClient) player.giveItemStack(belt.take());
			return ActionResult.success(world.isClient);
		}
		if (!belt.stack().isEmpty()) return ActionResult.PASS;
		if (!world.isClient && belt.accept(held, 0)) {
			if (!player.getAbilities().creativeMode) held.decrement(1);
		}
		return ActionResult.success(world.isClient);
	}

	/** Anyone standing on a belt drifts along it, like on Create's belts (sneak to stand still). */
	@Override
	public void onSteppedOn(World world, BlockPos pos, BlockState state, Entity entity) {
		if (entity instanceof ItemEntity || entity.isSneaking()) return;
		Direction facing = state.get(FACING);
		Vec3d velocity = entity.getVelocity();
		double push = BeltBlockEntity.SPEED * 1.6;
		double x = facing.getOffsetX() != 0 ? approach(velocity.x, facing.getOffsetX() * push) : velocity.x;
		double z = facing.getOffsetZ() != 0 ? approach(velocity.z, facing.getOffsetZ() * push) : velocity.z;
		entity.setVelocity(x, velocity.y, z);
		super.onSteppedOn(world, pos, state, entity);
	}

	/** Brings a velocity up to the belt's speed in its direction, without slowing anyone already going faster. */
	private static double approach(double current, double target) {
		return target > 0 ? Math.max(current, target) : Math.min(current, target);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock()) && world.getBlockEntity(pos) instanceof BeltBlockEntity belt && !belt.stack().isEmpty()) {
			ItemScatterer.spawn(world, pos.getX(), pos.getY(), pos.getZ(), belt.stack());
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}
}
