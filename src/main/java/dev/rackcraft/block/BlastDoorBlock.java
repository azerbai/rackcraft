package dev.rackcraft.block;

import dev.rackcraft.sim.NetKind;
import dev.rackcraft.world.Emp;
import dev.rackcraft.world.NetworkManager;
import dev.rackcraft.world.SimTicker;
import java.util.Set;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * A vault or gate door that cannot be mined. It stays shut while a live Power Cable touches it that runs back to a power
 * source, and slides open for good the moment that cable is cut or broken, the source is switched off or destroyed, or an
 * EMP goes off nearby. Nothing is destroyed: it just retracts, with a sulk. Once shut it re-checks every second.
 */
public final class BlastDoorBlock extends Block {
	/** Machines that count as a live source at the far end of the cable. */
	private static final Set<String> SOURCES = Set.of("utility_intake", "creative_power", "diesel_generator", "solar_panel",
			"wind_turbine", "battery_bank", "modular_reactor", "beacon_receiver", "solar_array", "wind_nacelle", "rectenna");

	public BlastDoorBlock(AbstractBlock.Settings settings) {
		super(settings.strength(-1.0f, 3_600_000.0f).dropsNothing().ticksRandomly());
	}

	@Override
	public void neighborUpdate(BlockState state, World world, BlockPos pos, Block block, BlockPos fromPos, boolean notify) {
		if (world instanceof ServerWorld serverWorld) schedule(serverWorld, pos, 2);
	}

	/** A door that has just generated has no tick queued; the first random tick after a chunk loads starts its watch. */
	@Override
	public void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		schedule(world, pos, 20);
	}

	private void schedule(ServerWorld world, BlockPos pos, int delay) {
		if (!world.getBlockTickScheduler().isQueued(pos, this)) world.scheduleBlockTick(pos, this, delay);
	}

	@Override
	public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (!held(world, pos)) unlock(world, pos);
		else schedule(world, pos, 20);
	}

	/** True while an uncut Power Cable touches the door and its network has a source on it. */
	public static boolean held(ServerWorld world, BlockPos pos) {
		NetworkManager networks = NetworkManager.get(world);
		for (Direction direction : Direction.values()) {
			BlockPos at = pos.offset(direction);
			BlockState neighbour = world.getBlockState(at);
			if (!(neighbour.getBlock() instanceof CableBlock cable) || !cable.kinds().contains(NetKind.POWER) || neighbour.get(CableBlock.CUT)) continue;
			Set<BlockPos> network = networks.component(at, NetKind.POWER);
			// A cable the network has not met yet gets the benefit of the doubt.
			if (network.isEmpty()) return true;
			for (MachineBlockEntity machine : SimTicker.machines(world)) {
				if (SOURCES.contains(machine.blockId()) && network.contains(machine.getPos()) && !Emp.isDisabled(machine)) return true;
			}
		}
		return false;
	}

	public void unlock(ServerWorld world, BlockPos pos) {
		world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
		world.spawnParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.02);
		world.playSound(null, pos, SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.BLOCKS, 0.8f, 0.6f);
	}
}
