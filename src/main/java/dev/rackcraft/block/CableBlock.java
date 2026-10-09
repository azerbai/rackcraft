package dev.rackcraft.block;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.world.NetworkManager;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.ConnectingBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.Hand;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.server.world.ServerWorld;

public final class CableBlock extends Block implements BlockEntityProvider {
	public static final BooleanProperty CUT = BooleanProperty.of("cut");
	private final NetKind kind;
	private final Set<NetKind> kinds;
	private final double halfWidth;
	/** The research project a trunk waits for; ordinary cables need none. */
	private final String gate;
	private final Map<BlockState, VoxelShape> shapes = new HashMap<>();

	public CableBlock(AbstractBlock.Settings settings, NetKind kind) {
		this(settings, EnumSet.of(kind), switch (kind) {
			case POWER -> 2;
			case COOLANT -> 3;
			case DATA -> 1;
			case ITEM -> 2.5;
		}, null);
	}

	/** A trunk: one block carrying several networks at once. Half width must match CABLE_HALF_WIDTH in tools/gen_assets.py. */
	public CableBlock(AbstractBlock.Settings settings, Set<NetKind> kinds, double halfWidth, String gate) {
		super(settings);
		this.kinds = Set.copyOf(kinds);
		this.kind = kinds.iterator().next();
		this.halfWidth = halfWidth;
		this.gate = gate;
		BlockState state = getStateManager().getDefaultState().with(CUT, false);
		for (BooleanProperty property : ConnectingBlock.FACING_PROPERTIES.values()) state = state.with(property, false);
		setDefaultState(state);
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(CUT);
		ConnectingBlock.FACING_PROPERTIES.values().forEach(builder::add);
	}

	/** The first network this block carries; ordinary cables carry exactly one. */
	public NetKind kind() { return kind; }

	public Set<NetKind> kinds() { return kinds; }

	public boolean isTrunk() { return kinds.size() > 1; }

	/** The research project this block is inert without, or null. */
	public String gate() { return gate; }

	/** Cables join cables of the same kind and any machine that sits on that kind of network. */
	private boolean connectsTo(BlockState neighbour) {
		if (neighbour.getBlock() instanceof CableBlock cable) return !Collections.disjoint(cable.kinds, kinds);
		if (neighbour.getBlock() instanceof PatchPanelBlock) return true;
		if (neighbour.getBlock() instanceof MachineBlock) {
			Set<NetKind> theirs = MachineBlockEntity.networkKinds(Registries.BLOCK.getId(neighbour.getBlock()).getPath());
			return !Collections.disjoint(theirs, kinds);
		}
		return false;
	}

	public BlockState withConnections(BlockState state, BlockView world, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			state = state.with(ConnectingBlock.FACING_PROPERTIES.get(direction),
					connectsTo(world.getBlockState(pos.offset(direction))));
		}
		return state;
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext context) {
		return withConnections(getDefaultState(), context.getWorld(), context.getBlockPos());
	}

	@Override
	public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbourState,
			WorldAccess world, BlockPos pos, BlockPos neighbourPos) {
		return state.with(ConnectingBlock.FACING_PROPERTIES.get(direction), connectsTo(neighbourState));
	}

	@Override
	public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos,
			net.minecraft.block.ShapeContext context) {
		return shapes.computeIfAbsent(state, this::createShape);
	}

	private VoxelShape createShape(BlockState state) {
		double lo = 8 - halfWidth;
		double hi = 8 + halfWidth;
		VoxelShape shape = Block.createCuboidShape(lo, lo, lo, hi, hi, hi);
		for (Direction direction : Direction.values()) {
			if (!state.get(ConnectingBlock.FACING_PROPERTIES.get(direction))) continue;
			shape = VoxelShapes.union(shape, switch (direction) {
				case DOWN -> Block.createCuboidShape(lo, 0, lo, hi, lo, hi);
				case UP -> Block.createCuboidShape(lo, hi, lo, hi, 16, hi);
				case NORTH -> Block.createCuboidShape(lo, lo, 0, hi, hi, lo);
				case SOUTH -> Block.createCuboidShape(lo, lo, hi, hi, hi, 16);
				case WEST -> Block.createCuboidShape(0, lo, lo, lo, hi, hi);
				case EAST -> Block.createCuboidShape(hi, lo, lo, 16, hi, hi);
			});
		}
		return shape;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new CableBlockEntity(pos, state);
	}

	@Override
	public void neighborUpdate(BlockState state, World world, BlockPos pos, Block block,
			BlockPos fromPos, boolean notify) {
		if (!world.isClient && world instanceof ServerWorld serverWorld) NetworkManager.get(serverWorld).markDirty();
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock()) && world instanceof ServerWorld serverWorld) {
			NetworkManager.get(serverWorld).unregister(pos);
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}

	/** Cut cables spark so breaks are easy to spot. */
	@Override
	public void randomDisplayTick(BlockState state, World world, BlockPos pos, net.minecraft.util.math.random.Random random) {
		if (!state.get(CUT)) return;
		world.addParticle(net.minecraft.particle.ParticleTypes.ELECTRIC_SPARK,
				pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.3, pos.getY() + 0.5,
				pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.3, 0, 0.05, 0);
		if (random.nextInt(4) == 0) {
			world.addParticle(net.minecraft.particle.ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.6,
					pos.getZ() + 0.5, 0, 0.02, 0);
		}
		if (random.nextInt(10) == 0) {
			world.playSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
					net.minecraft.sound.SoundEvents.BLOCK_REDSTONE_TORCH_BURNOUT, net.minecraft.sound.SoundCategory.BLOCKS,
					0.15f, 1.8f, false);
		}
	}

	public static void setCut(ServerWorld world, BlockPos pos, boolean cut) {
		BlockState state = world.getBlockState(pos);
		if (!(state.getBlock() instanceof CableBlock) || state.get(CUT) == cut) return;
		world.setBlockState(pos, state.with(CUT, cut));
		NetworkManager.get(world).markDirty();
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand,
			BlockHitResult hit) {
		if (!state.get(CUT)) return ActionResult.PASS;
		ItemStack held = player.getStackInHand(hand);
		if (!held.isOf(net.minecraft.item.Items.AIR)
				&& net.minecraft.registry.Registries.ITEM.getId(held.getItem()).equals(Rackcraft.id("repair_kit"))) {
			if (!world.isClient) {
				if (world instanceof ServerWorld serverWorld) setCut(serverWorld, pos, false);
				held.damage(1, player, entity -> entity.sendToolBreakStatus(player.getActiveHand()));
			}
			return ActionResult.success(world.isClient);
		}
		return ActionResult.PASS;
	}
}
