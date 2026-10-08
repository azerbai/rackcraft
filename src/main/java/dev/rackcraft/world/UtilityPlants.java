package dev.rackcraft.world;

import dev.rackcraft.block.MachineBlockEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/**
 * The utility plants: cube multiblocks (2x2x2 to 5x5x5, see {@link ReactorArrays}) that each do something nothing
 * else in the mod does.
 *
 * <ul>
 *   <li><b>Desalination Plant</b>: turns any water it touches, sea water included, into fresh water for the Cooling
 *       Towers on its coolant loop: {@link #DESAL_UNITS_PER_CORE} units per core, for {@link #DESAL_KW_PER_CORE} kW
 *       per core. It never drains anything, so towers can run on the coast.</li>
 *   <li><b>Grid-Tie Substation</b>: sells spare solar, wind and reactor output to the outside grid for RackCoin,
 *       up to {@link #EXPORT_KW_PER_CORE} kW per core. The price follows the time of day: low at noon when every
 *       solar farm is selling, high in the evening peak.</li>
 *   <li><b>Heat Recovery Plant</b>: a heat sink on a coolant loop that sells the heat to the villages around it as
 *       district heating. Each villager within {@link #VILLAGE_RADIUS} blocks takes {@link #KW_PER_VILLAGER} kW
 *       (more in cold biomes), and pays for it. It needs no power and no water.</li>
 * </ul>
 */
public final class UtilityPlants {
	public static final int DESAL_UNITS_PER_CORE = 2;
	public static final double DESAL_KW_PER_CORE = 20;
	public static final double EXPORT_KW_PER_CORE = 1000;
	/** RackCoin per kJ exported at the normal (night) price. */
	public static final double EXPORT_RC_PER_KJ = 0.05;
	public static final double KW_PER_VILLAGER = 50;
	public static final double HEAT_KW_PER_CORE = 400;
	/** RackCoin per kJ of heat sold. */
	public static final double HEAT_RC_PER_KJ = 0.4;
	public static final int VILLAGE_RADIUS = 64;
	private static final long SCAN_TICKS = 200;

	private static final Map<MachineBlockEntity, long[]> WATER_SCANS = new java.util.WeakHashMap<>();
	private static final Map<MachineBlockEntity, long[]> VILLAGER_SCANS = new java.util.WeakHashMap<>();

	private UtilityPlants() {}

	// ---------------------------------------------------------------- desalination

	/** What a desalination core draws: its share of a formed plant's load, or nothing on its own. */
	public static double desalinationKw(MachineBlockEntity machine) {
		int edge = machine.reactorArraySize();
		return edge >= 2 ? DESAL_KW_PER_CORE * ReactorArrays.efficiency(edge) : 0;
	}

	/**
	 * Sets every Desalination Plant core's pump readings, as a Freshwater Pump's: the controller supplies the whole
	 * plant's units, the other cores none. Run before the coolant loops are built.
	 */
	public static void scanDesalination(ServerWorld world, Map<MachineBlockEntity, ReactorArrays.Array> arrays,
			Map<MachineBlockEntity, Double> satisfaction) {
		Set<ReactorArrays.Array> seen = new HashSet<>();
		for (Map.Entry<MachineBlockEntity, ReactorArrays.Array> entry : arrays.entrySet()) {
			if (!entry.getKey().blockId().equals("desalination_plant") || !seen.add(entry.getValue())) continue;
			ReactorArrays.Array array = entry.getValue();
			int water = array.edge() >= 2 ? waterTouching(world, array) : 0;
			double power = array.members().stream().mapToDouble(member -> satisfaction.getOrDefault(member, 0.0)).average().orElse(0);
			FreshwaterCooling.PumpStatus status = array.edge() < 2 || water == 0 ? FreshwaterCooling.PumpStatus.NO_WATER
					: power < 0.5 ? FreshwaterCooling.PumpStatus.NO_POWER : FreshwaterCooling.PumpStatus.PUMPING;
			int units = status == FreshwaterCooling.PumpStatus.PUMPING ? array.cores() * DESAL_UNITS_PER_CORE : 0;
			for (MachineBlockEntity member : array.members()) {
				member.setPumpReadings(water, member == array.controller() ? units : 0, status.ordinal());
				member.setPumpUsed(0);
				member.setReactorArray(array.edge(), 0, 0, 0, 0);
			}
		}
	}

	/** Water source blocks touching the outside of the cube, rescanned every ten seconds. */
	static int waterTouching(ServerWorld world, ReactorArrays.Array array) {
		MachineBlockEntity controller = array.controller();
		long now = world.getTime();
		long[] scan = WATER_SCANS.get(controller);
		if (scan != null && now - scan[1] < SCAN_TICKS && scan[2] == array.cores()) return (int) scan[0];
		Set<BlockPos> members = new HashSet<>();
		for (MachineBlockEntity member : array.members()) members.add(member.getPos());
		Set<BlockPos> water = new HashSet<>();
		for (BlockPos pos : members) {
			for (Direction direction : Direction.values()) {
				BlockPos side = pos.offset(direction);
				if (!members.contains(side) && isWaterSource(world.getFluidState(side))) water.add(side);
			}
		}
		WATER_SCANS.put(controller, new long[] {water.size(), now, array.cores()});
		return water.size();
	}

	private static boolean isWaterSource(FluidState fluid) {
		return fluid.isIn(FluidTags.WATER) && fluid.isStill();
	}

	// ---------------------------------------------------------------- heat recovery

	/**
	 * Each Heat Recovery Plant core's share of what its village will take: villagers within range times
	 * {@link #KW_PER_VILLAGER}, scaled by the climate (half again in the cold, less in the heat), at most
	 * {@link #HEAT_KW_PER_CORE} per core. Lone blocks take nothing.
	 */
	public static Map<MachineBlockEntity, Double> heatRecovery(ServerWorld world, Map<MachineBlockEntity, ReactorArrays.Array> arrays) {
		Map<MachineBlockEntity, Double> capacity = new HashMap<>();
		Set<ReactorArrays.Array> seen = new HashSet<>();
		for (Map.Entry<MachineBlockEntity, ReactorArrays.Array> entry : arrays.entrySet()) {
			if (!entry.getKey().blockId().equals("heat_recovery_plant") || !seen.add(entry.getValue())) continue;
			ReactorArrays.Array array = entry.getValue();
			int villagers = array.edge() >= 2 ? villagers(world, array.controller()) : 0;
			double climate = CoolingLoops.climateFactor(world, array.controller().getPos());
			double total = Math.min(array.cores() * HEAT_KW_PER_CORE, villagers * KW_PER_VILLAGER * climate);
			for (MachineBlockEntity member : array.members()) {
				capacity.put(member, total / array.cores());
				member.setReactorArray(array.edge(), total, 0, 0, 0);
				member.setCooling(0, villagers);
			}
		}
		return capacity;
	}

	/** Villagers, children included, within range of the plant, counted every ten seconds. */
	private static int villagers(ServerWorld world, MachineBlockEntity controller) {
		long now = world.getTime();
		long[] scan = VILLAGER_SCANS.get(controller);
		if (scan != null && now - scan[1] < SCAN_TICKS) return (int) scan[0];
		int count = world.getEntitiesByClass(VillagerEntity.class, new Box(controller.getPos()).expand(VILLAGE_RADIUS),
				VillagerEntity::isAlive).size();
		VILLAGER_SCANS.put(controller, new long[] {count, now});
		return count;
	}

	/**
	 * Pays for the heat each plant moved this step, and posts the whole cube's figures on every core (each core is a
	 * sink of its own on the loop). Run after the coolant loops finish.
	 */
	public static void sellHeat(ServerWorld world, Map<MachineBlockEntity, ReactorArrays.Array> arrays, double dt) {
		FacilityManager facility = FacilityManager.get(world);
		Set<ReactorArrays.Array> seen = new HashSet<>();
		for (Map.Entry<MachineBlockEntity, ReactorArrays.Array> entry : arrays.entrySet()) {
			if (!entry.getKey().blockId().equals("heat_recovery_plant") || !seen.add(entry.getValue())) continue;
			ReactorArrays.Array array = entry.getValue();
			double moved = array.members().stream().mapToDouble(MachineBlockEntity::coolingKw).sum();
			double rate = moved * HEAT_RC_PER_KJ;
			if (rate > 0) facility.addCredits(array.controller().accrueCredits(rate, dt));
			for (MachineBlockEntity member : array.members()) {
				member.setCooling(moved, member.coolingDetail());
				member.setIncome(rate);
			}
		}
	}

	// ---------------------------------------------------------------- grid export

	/** What one Grid-Tie Substation can sell: every core's export capacity, on the controller only. */
	public static double exportCapacityKw(ReactorArrays.Array array) {
		return array.edge() >= 2 ? array.cores() * EXPORT_KW_PER_CORE : 0;
	}

	/** The grid's price right now, as a multiple of the base: cheap at midday, dear in the evening peak. */
	public static double priceFactor(ServerWorld world) {
		long time = world.getTimeOfDay() % 24000;
		if (time < 12000) return 0.6;
		if (time < 14000) return 2.5;
		return 1.0;
	}

	public static String priceName(double factor) {
		return factor >= 2 ? "evening peak" : factor < 1 ? "midday glut" : "night";
	}

	/** Pays a substation for what it exported this step and posts the figures on every core of its cube. */
	public static void sellPower(ServerWorld world, ReactorArrays.Array array, double exportedKw, double dt) {
		double factor = priceFactor(world);
		double rate = exportedKw * EXPORT_RC_PER_KJ * factor;
		MachineBlockEntity controller = array.controller();
		if (rate > 0) FacilityManager.get(world).addCredits(controller.accrueCredits(rate, dt));
		for (MachineBlockEntity member : array.members()) {
			member.setPowerKw(exportedKw);
			member.setIncome(rate);
			member.setReactorArray(array.edge(), exportCapacityKw(array), 0, 0, 0);
			member.setCooling(0, (int) Math.round(factor * 100));
		}
	}
}
