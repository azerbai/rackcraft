package dev.rackcraft.world;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.sim.NetKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Coolant loops: every connected run of Coolant Pipe is one loop with a heat budget, and the whole system fits
 * in one sentence: <b>heat goes into a loop, and heat sinks on the loop take it out</b>.
 *
 * <ul>
 *   <li>In: racks with liquid-cooled modules (85% of their heat), Rear-Door Coolers (the warm air off the back of
 *       one rack), CRAC units (hot room air) and Modular Reactors (30% of their output).</li>
 *   <li>Out: Cooling Towers (120 kW with fresh water from a pump on the loop, 20 kW without), Dry Coolers (40 kW,
 *       more in the cold and less in the heat), Chillers (250 kW anywhere, but power-hungry) and Water Heat
 *       Exchangers (3 kW per block of water around them, up to 120 kW; sea water is fine).</li>
 * </ul>
 *
 * If more heat goes in than the sinks can take, every machine putting heat in gets the same share of what the
 * loop can take, and the rest stays in the air: racks run hot, which you can see on their screens.
 */
public final class CoolingLoops {
	public static final double TOWER_DRY_KW = 20;
	public static final double TOWER_WET_KW = 120;
	public static final int TOWER_WATER_UNITS = 4;
	public static final double DRY_COOLER_KW = 40;
	public static final double CHILLER_KW = 250;
	/** A chiller draws this much plus {@link #CHILLER_KW_PER_KW} of the heat it moves. */
	public static final double CHILLER_BASE_KW = 5;
	public static final double CHILLER_KW_PER_KW = 0.2;
	public static final double EXCHANGER_KW_PER_BLOCK = 3;
	public static final double EXCHANGER_MAX_KW = 120;
	public static final double REAR_DOOR_KW = 40;
	/** Share of a dry cooler's or tower's capacity a heat wave takes away. */
	public static final double HEAT_WAVE_LOSS = 0.3;
	public static final double CRAC_KW_PER_SIDE = 40;
	public static final double CRAC_FLOW_KW_PER_K = 4;
	public static final double REACTOR_HEAT_SHARE = 0.3;
	private static final int EXCHANGER_RADIUS = 4;
	private static final Map<MachineBlockEntity, long[]> EXCHANGER_SCANS = new WeakHashMap<>();

	public static boolean isSink(String id) {
		return switch (id) {
			case "cooling_tower", "dry_cooler", "chiller", "water_heat_exchanger", "heat_recovery_plant" -> true;
			default -> false;
		};
	}

	/** One coolant network: its sinks, its pumps, what it can take and what was put in this step. */
	public static final class Loop {
		final List<MachineBlockEntity> members = new ArrayList<>();
		final List<MachineBlockEntity> sinks = new ArrayList<>();
		final List<MachineBlockEntity> pumps = new ArrayList<>();
		final Map<MachineBlockEntity, Double> sinkCapacity = new HashMap<>();
		final Map<MachineBlockEntity, Integer> towerUnits = new HashMap<>();
		double capacityKw;
		double heatKw;

		/** Share of the heat put in that the loop can take this step. */
		public double ratio() {
			return heatKw <= capacityKw || heatKw <= 0 ? 1 : capacityKw / heatKw;
		}

		public double capacityKw() { return capacityKw; }
		public double heatKw() { return heatKw; }
		public boolean hasSinks() { return !sinks.isEmpty(); }
	}

	private final Map<BlockPos, Loop> loopAt = new HashMap<>();
	private final List<Loop> loops = new ArrayList<>();

	/**
	 * Builds this step's loops and works out what each sink can take: power, water and climate. With
	 * {@code coolingFailure} (the event) every sink is down.
	 */
	public static CoolingLoops build(ServerWorld world, List<MachineBlockEntity> machines,
			Map<MachineBlockEntity, Double> satisfaction, boolean coolingFailure) {
		return build(world, machines, satisfaction, coolingFailure, false, 1, Map.of());
	}

	/**
	 * {@code heatWave}: dry coolers and towers, which reject heat to the outside air, lose
	 * {@link #HEAT_WAVE_LOSS}. {@code sinkScale}: research's multiplier on every sink. {@code heatRecovery}: what each Heat
	 * Recovery Plant core's village will take (see {@link UtilityPlants#heatRecovery}).
	 */
	public static CoolingLoops build(ServerWorld world, List<MachineBlockEntity> machines,
			Map<MachineBlockEntity, Double> satisfaction, boolean coolingFailure, boolean heatWave, double sinkScale,
			Map<MachineBlockEntity, Double> heatRecovery) {
		CoolingLoops result = new CoolingLoops();
		NetworkManager networks = NetworkManager.get(world);
		for (MachineBlockEntity machine : machines) {
			if (!MachineBlockEntity.COOLANT_MACHINES.contains(machine.blockId())) continue;
			Loop loop = result.loopAt.get(machine.getPos());
			if (loop == null) {
				loop = new Loop();
				result.loops.add(loop);
				for (BlockPos member : networks.component(machine.getPos(), NetKind.COOLANT)) result.loopAt.put(member, loop);
				// A machine on no network at all is a loop of its own.
				result.loopAt.putIfAbsent(machine.getPos(), loop);
			}
			loop.members.add(machine);
			if (isSink(machine.blockId())) loop.sinks.add(machine);
			if (machine.blockId().equals("freshwater_pump") || machine.blockId().equals("desalination_plant")) loop.pumps.add(machine);
		}
		for (Loop loop : result.loops) {
			int water = loop.pumps.stream().filter(pump -> pump.pumpStatus() == FreshwaterCooling.PumpStatus.PUMPING.ordinal())
					.mapToInt(MachineBlockEntity::pumpUnits).sum();
			loop.sinks.sort(Comparator.comparingLong(sink -> sink.getPos().asLong()));
			for (MachineBlockEntity sink : loop.sinks) {
				double power = satisfaction.getOrDefault(sink, 0.0);
				double capacity = 0;
				int detail = 0;
				switch (sink.blockId()) {
					case "cooling_tower" -> {
						int units = power >= 0.5 ? Math.min(TOWER_WATER_UNITS, water) : 0;
						water -= units;
						loop.towerUnits.put(sink, units);
						capacity = TOWER_DRY_KW + (TOWER_WET_KW - TOWER_DRY_KW) * units / TOWER_WATER_UNITS;
						detail = units;
					}
					case "dry_cooler" -> {
						double climate = climateFactor(world, sink.getPos());
						capacity = DRY_COOLER_KW * climate;
						detail = (int) Math.round(climate * 100);
					}
					case "chiller" -> capacity = CHILLER_KW;
					case "heat_recovery_plant" -> {
						// Sold to the village as district heating: no power needed, just customers.
						capacity = heatRecovery.getOrDefault(sink, 0.0);
						power = 1;
						detail = sink.coolingDetail();
					}
					case "water_heat_exchanger" -> {
						int blocks = waterAround(world, sink);
						capacity = Math.min(EXCHANGER_MAX_KW, EXCHANGER_KW_PER_BLOCK * blocks);
						detail = blocks;
					}
					default -> {}
				}
				capacity *= coolingFailure ? 0 : Math.min(1, power) * sinkScale;
				if (heatWave && (sink.blockId().equals("dry_cooler") || sink.blockId().equals("cooling_tower"))) capacity *= 1 - HEAT_WAVE_LOSS;
				sink.setCooling(0, detail);
				loop.sinkCapacity.put(sink, capacity);
				loop.capacityKw += capacity;
			}
		}
		return result;
	}

	public Loop loopAt(BlockPos pos) {
		return loopAt.get(pos);
	}

	/** Whether a machine here sits on a loop with at least one heat sink (working or not). */
	public boolean cooled(BlockPos pos) {
		Loop loop = loopAt.get(pos);
		return loop != null && loop.hasSinks();
	}

	/** Puts a request for this much heat into the loop at {@code pos}; call {@link #ratio} once every request is in. */
	public void request(BlockPos pos, double heatKw) {
		Loop loop = loopAt.get(pos);
		if (loop != null && heatKw > 0) loop.heatKw += heatKw;
	}

	public double ratio(BlockPos pos) {
		Loop loop = loopAt.get(pos);
		return loop == null ? 0 : loop.ratio();
	}

	/** Shares the heat out among each loop's sinks, runs the towers' water, and posts the loop figures on every member. */
	public void finish(ServerWorld world, double dt) {
		for (Loop loop : loops) {
			double rejected = Math.min(loop.heatKw, loop.capacityKw);
			double used = loop.capacityKw > 0 ? rejected / loop.capacityKw : 0;
			double waterUsed = 0;
			for (MachineBlockEntity sink : loop.sinks) {
				double moved = loop.sinkCapacity.getOrDefault(sink, 0.0) * used;
				sink.setCooling(moved, sink.coolingDetail());
				waterUsed += loop.towerUnits.getOrDefault(sink, 0) * used;
			}
			FreshwaterCooling.draw(world, loop.pumps, waterUsed, dt);
			for (MachineBlockEntity member : loop.members) member.setLoop(loop.heatKw, loop.capacityKw);
		}
	}

	/** Dry coolers like the cold: 150% in snowy biomes, 60% in deserts, savannas and badlands. */
	public static double climateFactor(ServerWorld world, BlockPos pos) {
		float temperature = world.getBiome(pos).value().getTemperature();
		return temperature < 0.3f ? 1.5 : temperature >= 1.0f ? 0.6 : 1.0;
	}

	/** Water blocks (sources, fresh or salt) within four blocks of a heat exchanger that touches water; rescanned every ten seconds. */
	private static int waterAround(ServerWorld world, MachineBlockEntity exchanger) {
		long[] scan = EXCHANGER_SCANS.get(exchanger);
		long now = world.getTime();
		if (scan != null && now - scan[1] < 200) return (int) scan[0];
		BlockPos origin = exchanger.getPos();
		boolean touching = false;
		for (Direction direction : Direction.values()) touching |= isWater(world.getFluidState(origin.offset(direction)));
		int count = 0;
		if (touching) {
			for (BlockPos pos : BlockPos.iterate(origin.add(-EXCHANGER_RADIUS, -EXCHANGER_RADIUS, -EXCHANGER_RADIUS),
					origin.add(EXCHANGER_RADIUS, EXCHANGER_RADIUS, EXCHANGER_RADIUS))) {
				if (isWater(world.getFluidState(pos))) count++;
			}
		}
		EXCHANGER_SCANS.put(exchanger, new long[] {count, now});
		return count;
	}

	/** Forgets cached water scans, so the next step looks again; for the self-test. */
	public static void rescan() {
		EXCHANGER_SCANS.clear();
	}

	private static boolean isWater(FluidState fluid) {
		return fluid.isIn(FluidTags.WATER) && fluid.isStill();
	}

	/** Every loop's members, for readouts. */
	public List<Loop> loops() { return loops; }
}
