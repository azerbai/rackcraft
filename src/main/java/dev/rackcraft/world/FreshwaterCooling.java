package dev.rackcraft.world;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.sim.ServerModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Freshwater cooling for tier 3+ rack modules. A Freshwater Pump beside a lake or river supplies water
 * to racks on its coolant network: one unit per three water source blocks around it, up to 16. Oceans
 * and beaches are salt water and don't count. Water isn't free: every {@link #UNIT_SECONDS_PER_BLOCK}
 * unit-seconds of cooling drains one source block, from the shoreline first, so lakes visibly shrink.
 */
public final class FreshwaterCooling {
	public static final int MAX_UNITS = 16;
	public static final int MIN_SOURCES = 12;
	public static final double UNIT_SECONDS_PER_BLOCK = 120;
	private static final int RADIUS = 6;
	private static final int SCAN_STEPS = 20;

	public enum PumpStatus { PUMPING, NO_POWER, NO_WATER, SALT_WATER, TOO_LITTLE_WATER }

	private static final Map<MachineBlockEntity, List<BlockPos>> SOURCES = new java.util.WeakHashMap<>();
	private static final Map<MachineBlockEntity, Long> LAST_SCAN = new java.util.WeakHashMap<>();

	private FreshwaterCooling() {}

	/** Allocates pump water to racks, coolant network by coolant network. Returns the racks that are supplied. */
	public static Set<MachineBlockEntity> supply(ServerWorld world, List<MachineBlockEntity> machines,
			Map<MachineBlockEntity, Double> satisfaction, double dt) {
		NetworkManager networks = NetworkManager.get(world);
		Set<MachineBlockEntity> supplied = new HashSet<>();
		Map<Set<BlockPos>, List<MachineBlockEntity>> pumpsByNetwork = new HashMap<>();
		for (MachineBlockEntity pump : machines) {
			if (!pump.blockId().equals("freshwater_pump")) continue;
			scan(world, pump, satisfaction.getOrDefault(pump, 0.0));
			pump.setPumpUsed(0);
			pumpsByNetwork.computeIfAbsent(networks.component(pump.getPos(), NetKind.COOLANT), ignored -> new ArrayList<>()).add(pump);
		}
		Map<Set<BlockPos>, List<MachineBlockEntity>> racksByNetwork = new HashMap<>();
		for (MachineBlockEntity rack : machines) {
			if (!rack.blockId().equals("server_rack")) continue;
			if (ServerModel.waterUnits(rack.modules()) == 0) {
				supplied.add(rack);
				continue;
			}
			Set<BlockPos> network = networks.component(rack.getPos(), NetKind.COOLANT);
			racksByNetwork.computeIfAbsent(network, ignored -> new ArrayList<>()).add(rack);
		}
		racksByNetwork.forEach((network, racks) -> {
			List<MachineBlockEntity> pumps = pumpsByNetwork.getOrDefault(network, List.of());
			double available = pumps.stream().filter(pump -> pump.pumpStatus() == PumpStatus.PUMPING.ordinal())
					.mapToDouble(MachineBlockEntity::pumpUnits).sum();
			double used = 0;
			racks.sort(Comparator.comparingLong(rack -> rack.getPos().asLong()));
			for (MachineBlockEntity rack : racks) {
				int demand = ServerModel.waterUnits(rack.modules());
				if (used + demand > available) continue;
				used += demand;
				supplied.add(rack);
			}
			// Share the draw across the network's pumps by their capacity.
			for (MachineBlockEntity pump : pumps) {
				if (pump.pumpStatus() != PumpStatus.PUMPING.ordinal() || available <= 0) continue;
				double share = used * pump.pumpUnits() / available;
				pump.setPumpUsed(share);
				drain(world, pump, share * dt);
			}
		});
		return supplied;
	}

	/** Re-reads the water around a pump every few seconds, or right away after it drained a block. */
	private static void scan(ServerWorld world, MachineBlockEntity pump, double powered) {
		long now = world.getTime();
		Long last = LAST_SCAN.get(pump);
		boolean due = last == null || now - last >= SCAN_STEPS * 10L || !SOURCES.containsKey(pump);
		if (due) {
			LAST_SCAN.put(pump, now);
			BlockPos origin = pump.getPos();
			boolean adjacent = false;
			boolean salt = isSalt(world, origin);
			for (Direction direction : Direction.values()) {
				BlockPos side = origin.offset(direction);
				if (isWaterSource(world.getFluidState(side))) {
					adjacent = true;
					salt |= isSalt(world, side);
				}
			}
			List<BlockPos> sources = new ArrayList<>();
			if (adjacent && !salt) {
				for (BlockPos pos : BlockPos.iterate(origin.add(-RADIUS, -4, -RADIUS), origin.add(RADIUS, 1, RADIUS))) {
					if (isWaterSource(world.getFluidState(pos))) sources.add(pos.toImmutable());
				}
			}
			SOURCES.put(pump, sources);
			PumpStatus status = !adjacent ? PumpStatus.NO_WATER : salt ? PumpStatus.SALT_WATER
					: sources.size() < MIN_SOURCES ? PumpStatus.TOO_LITTLE_WATER : PumpStatus.PUMPING;
			pump.setPumpReadings(sources.size(), status == PumpStatus.PUMPING ? Math.min(MAX_UNITS, sources.size() / 3) : 0,
					status.ordinal());
		}
		if (powered < 0.5 && pump.pumpStatus() == PumpStatus.PUMPING.ordinal()) {
			pump.setPumpReadings(pump.pumpSources(), pump.pumpUnits(), PumpStatus.NO_POWER.ordinal());
		} else if (powered >= 0.5 && pump.pumpStatus() == PumpStatus.NO_POWER.ordinal()) {
			LAST_SCAN.remove(pump);
		}
	}

	/** Takes water from the lake: the highest shoreline source block first, so the lake shrinks from its edge. */
	private static void drain(ServerWorld world, MachineBlockEntity pump, double unitSeconds) {
		pump.addWaterDrawn(unitSeconds);
		while (pump.waterDrawn() >= UNIT_SECONDS_PER_BLOCK) {
			pump.addWaterDrawn(-UNIT_SECONDS_PER_BLOCK);
			List<BlockPos> sources = SOURCES.getOrDefault(pump, List.of()).stream()
					.filter(pos -> isWaterSource(world.getFluidState(pos))).toList();
			if (sources.isEmpty()) return;
			BlockPos target = sources.stream().max(Comparator.comparingInt((BlockPos pos) -> pos.getY())
					.thenComparingInt(pos -> -sourceNeighbours(world, pos))
					.thenComparingDouble(pos -> pos.getSquaredDistance(pump.getPos()))).orElseThrow();
			if (world.getBlockState(target).isOf(Blocks.WATER)) world.setBlockState(target, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
			else if (world.getBlockState(target).contains(net.minecraft.state.property.Properties.WATERLOGGED)) {
				world.setBlockState(target, world.getBlockState(target).with(net.minecraft.state.property.Properties.WATERLOGGED, false),
						Block.NOTIFY_ALL);
			}
			world.spawnParticles(ParticleTypes.SPLASH, target.getX() + 0.5, target.getY() + 0.9, target.getZ() + 0.5, 8, 0.3, 0.1, 0.3, 0.1);
			world.playSound(null, pump.getPos(), SoundEvents.ITEM_BUCKET_FILL, SoundCategory.BLOCKS, 0.4f, 0.8f);
			LAST_SCAN.remove(pump);
		}
	}

	private static int sourceNeighbours(ServerWorld world, BlockPos pos) {
		int count = 0;
		for (Direction direction : Direction.Type.HORIZONTAL) if (isWaterSource(world.getFluidState(pos.offset(direction)))) count++;
		return count;
	}

	private static boolean isWaterSource(FluidState fluid) {
		return fluid.isIn(FluidTags.WATER) && fluid.isStill();
	}

	private static boolean isSalt(ServerWorld world, BlockPos pos) {
		var biome = world.getBiome(pos);
		return biome.isIn(BiomeTags.IS_OCEAN) || biome.isIn(BiomeTags.IS_DEEP_OCEAN) || biome.isIn(BiomeTags.IS_BEACH);
	}
}
