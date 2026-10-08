package dev.rackcraft.world;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RcBlocks;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.SolarArrayBlock;
import dev.rackcraft.sim.PowerSolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;

/**
 * Wind and solar beyond the basic panel and turbine. Every number is in {@link RackcraftConfig.Renewables}.
 *
 * <ul>
 *   <li><b>Wind</b>: one world-wide wind, the same for every turbine and tower, that swings by up to
 *       {@code gust} either side of normal over a few minutes, so a battery has something to smooth.</li>
 *   <li><b>Solar Arrays</b>: a placed 3x2; each of the six parts is a solar source of a sixth of the array. A
 *       Tracking Solar Array makes more, and keeps going at 60% through dawn and dusk.</li>
 *   <li><b>Wind Towers</b>: a nacelle on a column of Tower Sections. Output follows the nacelle's height, up to
 *       {@code windTowerMaxFactor} above Y 150; it needs enough sections and a clear 5x5 for its blades.</li>
 *   <li><b>Wear</b>: arrays and towers (the Assembly Line tier, not the basic panel and turbine) lose up to
 *       {@code wearLoss} of their output over {@code wearDays} days of running, until a Maintenance Drone services them.</li>
 * </ul>
 */
public final class Renewables {
	/** Seconds in a Minecraft day. */
	private static final double DAY_SECONDS = 1200;
	/** Wear at which a Drone Dock sends a drone to service an array or tower. */
	public static final double SERVICE_AT = 0.25;

	public enum TowerStatus { RUNNING, TOO_SHORT, BLOCKED }

	private Renewables() {}

	private static RackcraftConfig.Renewables config() {
		return RackcraftConfig.values.renewables;
	}

	/** The world's wind right now, around 1: two slow waves, phased by the seed so every world blows differently. */
	public static double gust(ServerWorld world) {
		long seed = world.getSeed();
		double time = world.getTime();
		double slow = Math.sin(2 * Math.PI * time / 4800 + (seed & 0xFF) / 40.0);
		double quick = Math.sin(2 * Math.PI * time / 1700 + ((seed >> 8) & 0xFF) / 40.0);
		return 1 + config().gust * (0.65 * slow + 0.35 * quick);
	}

	/** How strong the wind is at this height: 25% at Y 70 or below, full at Y 130, and (for towers) more above. */
	public static double windFactor(int y, double max) {
		return Math.max(0.25, Math.min(max, (y - 50) / 80.0));
	}

	private static double storm(ServerWorld world) {
		return world.isThundering() ? 1.5 : 1;
	}

	public static double turbineKw(ServerWorld world, BlockPos pos) {
		return config().turbineKw * windFactor(pos.getY(), 1) * storm(world) * gust(world);
	}

	// ---------------------------------------------------------------- solar arrays

	public static boolean isArray(String id) {
		return id.equals("solar_array") || id.equals("solar_array_tracking");
	}

	/** The part 0 holding an array part's wear, or the part itself if the array is somehow incomplete. */
	public static MachineBlockEntity arrayController(ServerWorld world, MachineBlockEntity part) {
		BlockState state = part.getCachedState();
		if (!state.contains(SolarArrayBlock.PART)) return part;
		BlockPos origin = SolarArrayBlock.origin(part.getPos(), state);
		return world.getBlockEntity(origin) instanceof MachineBlockEntity controller && isArray(controller.blockId()) ? controller : part;
	}

	/** The sun an array sees: 1 by day, 0 at night; a tracking array also gets 0.6 through dawn and dusk. */
	public static double daylight(ServerWorld world, boolean tracking) {
		if (world.isDay()) return 1;
		if (!tracking) return 0;
		long time = world.getTimeOfDay() % 24000;
		return time >= 11500 && time < 14000 || time >= 22000 ? 0.6 : 0;
	}

	/** One part's output: a sixth of the array, by sun, sky light and smog, less its wear. */
	public static double arrayPartKw(ServerWorld world, MachineBlockEntity part) {
		BlockPos above = part.getPos().up();
		if (!world.isSkyVisible(above)) return 0;
		boolean tracking = part.blockId().equals("solar_array_tracking");
		double sun = daylight(world, tracking);
		if (sun <= 0) return 0;
		double light = world.getLightLevel(LightType.SKY, above) / 15.0;
		double smog = AirQuality.solarFactor(AirQuality.get(world).smogAt(part.getPos()));
		return config().solarArrayKw / 6 * (tracking ? config().trackingBonus : 1) * sun * light * smog * wearFactor(arrayController(world, part));
	}

	// ---------------------------------------------------------------- wind towers

	/** Tower Sections stacked straight down from the nacelle. */
	public static int sections(ServerWorld world, BlockPos nacelle) {
		int count = 0;
		BlockPos pos = nacelle.down();
		while (count < 320 && world.getBlockState(pos).isOf(RcBlocks.get("tower_section"))) {
			count++;
			pos = pos.down();
		}
		return count;
	}

	/** Whether the tower can turn: tall enough, and with nothing solid in the 5x5 its blades sweep in front of it. */
	public static TowerStatus towerStatus(ServerWorld world, MachineBlockEntity nacelle) {
		if (sections(world, nacelle.getPos()) < config().windTowerMinSections) return TowerStatus.TOO_SHORT;
		Direction facing = nacelle.getCachedState().get(MachineBlock.FACING);
		Direction right = facing.rotateYClockwise();
		BlockPos centre = nacelle.getPos().offset(facing);
		for (int across = -2; across <= 2; across++) {
			for (int up = -2; up <= 2; up++) {
				BlockPos pos = centre.offset(right, across).up(up);
				if (world.getBlockState(pos).isFullCube(world, pos)) return TowerStatus.BLOCKED;
			}
		}
		return TowerStatus.RUNNING;
	}

	public static double towerKw(ServerWorld world, MachineBlockEntity nacelle) {
		if (towerStatus(world, nacelle) != TowerStatus.RUNNING) return 0;
		return config().windTowerKw * windFactor(nacelle.getPos().getY(), config().windTowerMaxFactor) * storm(world) * gust(world)
				* wearFactor(nacelle);
	}

	// ---------------------------------------------------------------- wear

	/** Whether this block keeps the wear for its machine: an array's part 0, or a nacelle. */
	public static boolean wearsOut(MachineBlockEntity machine) {
		if (machine.blockId().equals("wind_nacelle")) return true;
		return isArray(machine.blockId()) && machine.getCachedState().contains(SolarArrayBlock.PART)
				&& machine.getCachedState().get(SolarArrayBlock.PART) == 0;
	}

	public static double wearFactor(MachineBlockEntity machine) {
		return 1 - config().wearLoss * Math.max(0, Math.min(1, machine.wear()));
	}

	/**
	 * After the power solve: arrays and towers that worked this step wear a little, and every part of an array reports
	 * the whole array's output, capacity and wear; a nacelle reports its tower.
	 */
	public static void step(ServerWorld world, List<MachineBlockEntity> machines, Map<MachineBlockEntity, PowerSolver.Source> sources,
			Map<MachineBlockEntity, Double> outputs, double dt) {
		double wearPerSecond = 1 / Math.max(1e-6, config().wearDays * DAY_SECONDS);
		Map<MachineBlockEntity, double[]> arrays = new HashMap<>();
		for (MachineBlockEntity machine : machines) {
			String id = machine.blockId();
			if (isArray(id)) {
				double[] totals = arrays.computeIfAbsent(arrayController(world, machine), ignored -> new double[2]);
				totals[0] += outputs.getOrDefault(machine, 0.0);
				PowerSolver.Source source = sources.get(machine);
				totals[1] += source == null ? 0 : source.capacityKw();
			} else if (id.equals("wind_nacelle")) {
				TowerStatus status = towerStatus(world, machine);
				PowerSolver.Source source = sources.get(machine);
				machine.setProcess(status.ordinal(), status == TowerStatus.RUNNING);
				machine.setWorkers(sections(world, machine.getPos()));
				machine.setReactorArray(1, source == null ? 0 : source.capacityKw(), 0, 0, 0);
				if (outputs.getOrDefault(machine, 0.0) > 0) machine.setWear(machine.wear() + wearPerSecond * dt);
			}
		}
		for (MachineBlockEntity machine : machines) {
			if (!isArray(machine.blockId())) continue;
			MachineBlockEntity controller = arrayController(world, machine);
			double[] totals = arrays.get(controller);
			if (controller == machine && totals != null && totals[0] > 0) controller.setWear(controller.wear() + wearPerSecond * dt);
		}
		for (MachineBlockEntity machine : machines) {
			if (!isArray(machine.blockId())) continue;
			MachineBlockEntity controller = arrayController(world, machine);
			double[] totals = arrays.getOrDefault(controller, new double[2]);
			machine.setPowerKw(totals[0]);
			machine.setReactorArray(1, totals[1], 0, 0, 0);
			if (machine != controller) machine.setWearShown(controller.wear());
		}
	}
}
