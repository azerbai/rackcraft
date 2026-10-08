package dev.rackcraft.block;

import dev.rackcraft.RcBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import dev.rackcraft.world.NetworkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.ItemScatterer;
import net.minecraft.block.AbstractBlock;
import net.minecraft.world.World;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.random.Random;

public class MachineBlock extends BlockWithEntity {
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	public static final BooleanProperty LIT = Properties.LIT;

	public MachineBlock(AbstractBlock.Settings settings) {
		super(settings.luminance(state -> state.get(LIT) ? 5 : 0));
		setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(LIT, false));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, LIT);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext context) {
		return getDefaultState().with(FACING, context.getHorizontalPlayerFacing().getOpposite());
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new MachineBlockEntity(pos, state);
	}

	@Override
	public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
		super.onBlockAdded(state, world, pos, oldState, notify);
		if (oldState.isOf(state.getBlock())) return;
		if (!world.isClient && world instanceof ServerWorld serverWorld) NetworkManager.get(serverWorld).markDirty();
	}

	@Override
	public void neighborUpdate(BlockState state, World world, BlockPos pos, Block block,
			BlockPos fromPos, boolean notify) {
		if (!world.isClient && world instanceof ServerWorld serverWorld) NetworkManager.get(serverWorld).markDirty();
	}

	/** Running machines show it: generator exhaust smoke, reactor glow, cooling tower steam. */
	@Override
	public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
		if (!state.get(LIT)) return;
		String id = Registries.BLOCK.getId(this).getPath();
		double x = pos.getX() + 0.5;
		double y = pos.getY() + 1.02;
		double z = pos.getZ() + 0.5;
		switch (id) {
			case "diesel_generator" -> {
				// The exhaust stack is drawn at (11.5, 5) on the top texture: toward the front, offset to
				// the model's +x side, which is clockwise from the facing direction.
				Direction facing = state.get(FACING);
				Direction side = facing.rotateYClockwise();
				double stackX = x + side.getOffsetX() * 0.22 + facing.getOffsetX() * 0.19;
				double stackZ = z + side.getOffsetZ() * 0.22 + facing.getOffsetZ() * 0.19;
				world.addParticle(ParticleTypes.LARGE_SMOKE, stackX, y, stackZ, 0, 0.07, 0);
				if (random.nextInt(3) == 0) world.addParticle(ParticleTypes.SMOKE, stackX, y, stackZ, 0, 0.05, 0);
				if (random.nextInt(8) == 0) {
					world.playSound(x, pos.getY() + 0.5, z, SoundEvents.BLOCK_FURNACE_FIRE_CRACKLE, SoundCategory.BLOCKS,
							0.6f, 0.7f + random.nextFloat() * 0.2f, false);
				}
			}
			case "modular_reactor" -> {
				for (int index = 0; index < 2; index++) {
					world.addParticle(ParticleTypes.ELECTRIC_SPARK, pos.getX() + random.nextDouble(), y,
							pos.getZ() + random.nextDouble(), 0, 0.02, 0);
				}
			}
			case "cooling_tower", "dry_cooler" -> world.addParticle(ParticleTypes.CLOUD,
					x + (random.nextDouble() - 0.5) * 0.5, y, z + (random.nextDouble() - 0.5) * 0.5, 0, 0.06, 0);
			default -> {}
		}
	}

	@Override
	public BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand,
			BlockHitResult hit) {
		// A Survey Stake hands its site to a Site Planner (SurveyStakeItem) rather than opening the screen.
		if (player.getStackInHand(hand).isOf(dev.rackcraft.RcItems.ITEMS.get("survey_stake"))) return ActionResult.PASS;
		if (!world.isClient && world.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
			if (Racks.isRack(machine)
					&& player.getStackInHand(hand).isOf(dev.rackcraft.RcItems.ITEMS.get("thermal_scanner"))) {
				player.sendMessage(net.minecraft.text.Text.literal(String.format(java.util.Locale.ROOT,
						"Rack inlet %.1f C, exhaust %.1f C. Heat: %.1f kW to the coolant loop, %.1f kW to the air",
						machine.inletCelsius(), machine.exhaustCelsius(), machine.heatToLoopKw(), machine.heatToAirKw())), false);
				return ActionResult.SUCCESS;
			}
			player.openHandledScreen(machine);
		}
		return ActionResult.success(world.isClient);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!world.isClient && !state.isOf(newState.getBlock())) {
			BlockEntity entity = world.getBlockEntity(pos);
			if (entity instanceof Inventory inventory) ItemScatterer.spawn(world, pos, inventory);
			// Breaking a Scriptorium Desk frees its librarian and drops the shackles.
			if (entity instanceof MachineBlockEntity machine && machine.boundVillager() != null
					&& world instanceof ServerWorld serverWorld
					&& serverWorld.getEntity(machine.boundVillager()) instanceof net.minecraft.entity.passive.VillagerEntity villager) {
				dev.rackcraft.compute.TrainingStations.release(serverWorld, villager, java.util.List.of(machine));
				ItemScatterer.spawn(world, pos.getX(), pos.getY(), pos.getZ(),
						new net.minecraft.item.ItemStack(dev.rackcraft.RcItems.ITEMS.get("shackles")));
			}
			if (world.getServer() != null && Registries.BLOCK.getId(this).getPath().equals("wireless_transmitter")) {
				dev.rackcraft.storage.StorageState.get(world.getServer())
						.removeTransmitter(dev.rackcraft.storage.StorageState.transmitterKey(world.getRegistryKey(), pos));
			}
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}

}