package dev.rackcraft.world;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.RackcraftNetworking;
import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.RackStatus;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.sim.PowerSolver;
import dev.rackcraft.sim.ServerModel;
import dev.rackcraft.sim.ThermalGrid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;

public final class SimTicker {
	private static final TagKey<net.minecraft.block.Block> AIRFLOW_BLOCKING = TagKey.of(
			RegistryKeys.BLOCK, Rackcraft.id("airflow_blocking"));
	private static final Map<ServerWorld, Set<MachineBlockEntity>> LOADED = new WeakHashMap<>();
	private static final Map<ServerWorld, ThermalGrid> THERMAL_GRIDS = new WeakHashMap<>();
	private static final Map<ServerWorld, Long> LAST_ERRORS = new WeakHashMap<>();
	private static boolean registered;

	private SimTicker() {}

	public static void register() {
		if (registered) return;
		registered = true;
		ServerTickEvents.END_WORLD_TICK.register(world -> {
			int stepTicks = Math.max(1, RackcraftConfig.values.sim.stepTicks);
			if (world.getTime() % stepTicks == 0) safeStep(world);
		});
	}

	public static void registerBlockEntity(ServerWorld world, MachineBlockEntity entity) {
		LOADED.computeIfAbsent(world, ignored -> new HashSet<>()).add(entity);
	}

	public static void unregisterBlockEntity(ServerWorld world, MachineBlockEntity entity) {
		Set<MachineBlockEntity> entities = LOADED.get(world);
		if (entities != null) entities.remove(entity);
	}

	public static void stepNow(ServerWorld world) {
		safeStep(world);
	}

	private static void safeStep(ServerWorld world) {
		try {
			step(world);
		} catch (RuntimeException exception) {
			long now = world.getTime();
			if (now - LAST_ERRORS.getOrDefault(world, Long.MIN_VALUE / 2) >= 1200) {
				LAST_ERRORS.put(world, now);
				Rackcraft.LOGGER.error("[Rackcraft] Simulation step failed; continuing next step", exception);
			}
		}
	}

	private static void step(ServerWorld world) {
		final double dt = Math.max(1, RackcraftConfig.values.sim.stepTicks) / 20.0;
		Set<MachineBlockEntity> loaded = LOADED.getOrDefault(world, Set.of());
		List<MachineBlockEntity> machines = loaded.stream()
				.filter(entity -> !entity.isRemoved() && entity.getWorld() == world).toList();
		NetworkManager networks = NetworkManager.get(world);
		Map<MachineBlockEntity, Double> satisfaction = new HashMap<>();
		Map<MachineBlockEntity, PowerSolver.Source> powerSources = new HashMap<>();
		Map<MachineBlockEntity, Double> sourceOutput = new HashMap<>();
		Set<MachineBlockEntity> energized = new HashSet<>();
		for (MachineBlockEntity machine : machines) {
			satisfaction.put(machine, 0.0);
			machine.setNetworkStats(0, 0);
			if (!machine.blockId().equals("server_rack")) machine.setPowerKw(0);
		}

		for (Set<BlockPos> component : networks.components(NetKind.POWER)) {
			List<MachineBlockEntity> members = machines.stream()
					.filter(machine -> component.contains(machine.getPos())).toList();
			List<PowerSolver.Source> sources = new ArrayList<>();
			List<PowerSolver.Sink> sinks = new ArrayList<>();
			Map<String, MachineBlockEntity> sinkOwners = new HashMap<>();
			for (MachineBlockEntity machine : members) {
				PowerSolver.Source source = sourceFor(world, machine);
				if (source != null) {
					sources.add(source);
					powerSources.put(machine, source);
				}
				double demand = demandFor(machine);
				if (demand > 0) {
					String sinkId = Long.toString(machine.getPos().asLong());
					sinks.add(new PowerSolver.Sink(sinkId, priority(machine.blockId()), demand));
					sinkOwners.put(sinkId, machine);
				}
			}
			PowerSolver.Result result = PowerSolver.solve(sinks, sources, dt);
			if (result.suppliedKw() > 0) energized.addAll(members);
			double delivered = sinks.stream()
					.mapToDouble(sink -> sink.demandKw() * result.satisfaction().getOrDefault(sink.id(), 0.0)).sum();
			double capacity = sources.stream().filter(source -> source.kind() != PowerSolver.SourceKind.BATTERY)
					.mapToDouble(PowerSolver.Source::capacityKw).sum();
			for (MachineBlockEntity machine : members) {
				machine.setNetworkStats(delivered, result.demandKw());
				machine.setNetworkCapacity(capacity);
				Double output = result.sourceOutputKw().get(id(machine));
				if (output != null) sourceOutput.put(machine, output);
			}
			result.satisfaction().forEach((id, ratio) -> {
				MachineBlockEntity owner = sinkOwners.get(id);
				if (owner != null) {
					satisfaction.put(owner, ratio);
					if (!owner.blockId().equals("server_rack")) owner.setPowerKw(demandFor(owner) * ratio);
				}
			});
		}

		for (Map.Entry<MachineBlockEntity, PowerSolver.Source> entry : powerSources.entrySet()) {
			MachineBlockEntity machine = entry.getKey();
			PowerSolver.Source source = entry.getValue();
			double output = sourceOutput.getOrDefault(machine, 0.0);
			if (machine.blockId().equals("battery_bank")) {
				// Signed: positive while charging, negative while discharging.
				machine.setPowerKw((source.chargeKws() - machine.chargeKws()) / dt);
				machine.setChargeKws(source.chargeKws());
			} else {
				machine.setPowerKw(output);
			}
			if (machine.blockId().equals("diesel_generator")) machine.setDieselSpinupSteps(source.spinupSteps());
			// Burn fuel only while actually delivering power, not while idling on standby.
			if (source.kind() == PowerSolver.SourceKind.DIESEL && output > 0) {
				machine.setFuelBurnTicks(Math.max(0, machine.fuelBurnTicks() - RackcraftConfig.values.sim.stepTicks));
			}
		}
		for (Map.Entry<MachineBlockEntity, Double> entry : satisfaction.entrySet()) {
			entry.getKey().setPowerSatisfaction(entry.getValue());
		}

		ThermalGrid heat = thermalGrid(world);
		applyCooling(world, machines, heat, networks, satisfaction, dt);
		Map<MachineBlockEntity, ServerModel.RackStep> rackSteps = new HashMap<>();
		for (MachineBlockEntity rack : machines) {
			if (!rack.blockId().equals("server_rack")) continue;
			Direction facing = rack.getCachedState().get(MachineBlock.FACING);
			List<BlockPos> intakeCells = airflowCells(world, rack.getPos().offset(facing), facing, heat);
			List<BlockPos> exhaustCells = airflowCells(world, rack.getPos().offset(facing.getOpposite()), facing, heat);
			double inlet = averageTemperature(heat, intakeCells, rack.inletCelsius() + 1);
			boolean hasCdu = adjacentMachine(machines, rack.getPos(), "cdu");
			ServerModel.RackStep result = ServerModel.calculate(rack.modules(), rack.loadLimitPercent(),
					satisfaction.getOrDefault(rack, 0.0), inlet, hasCdu);
			if (satisfaction.getOrDefault(rack, 0.0) < 0.5) rack.setTripped(true);
			if (rack.isTripped() && inlet < 32 && result.thermalFactor() > 0) rack.setTripped(false);
			if (rack.isTripped()) result = new ServerModel.RackStep(result.usedUnits(), result.demandKw(),
					0, 0, result.thermalFactor(), result.quantumBlocked(), result.tripped());
			rackSteps.put(rack, result);
			depositAcross(heat, exhaustCells, result.demandKw(), dt);
			rack.setRackStats(inlet, averageTemperature(heat, exhaustCells, inlet), result.demandKw(),
					result.load(), result.thermalFactor());
		}

		updateLitStates(world, machines, satisfaction, sourceOutput, energized, networks);
		applyMachineHeat(world, machines, heat, dt);
		heat.step(dt, false);
		RackcraftNetworking.sendHeatCells(world, heat);
		awardCredits(world, machines, rackSteps, networks, satisfaction, dt);
		FacilityManager facility = FacilityManager.get(world);
		long previousEventTick = facility.eventTicks();
		facility.advanceEventClock(RackcraftConfig.values.sim.stepTicks);
		if (facility.eventTicks() / 1200 > previousEventTick / 1200) {
			scheduleEvent(world, facility, machines, rackSteps.isEmpty());
		}
	}

	private static PowerSolver.Source sourceFor(ServerWorld world, MachineBlockEntity machine) {
		return switch (machine.blockId()) {
			case "solar_panel" -> new PowerSolver.Source(id(machine), PowerSolver.SourceKind.SOLAR,
					world.isDay() && world.isSkyVisible(machine.getPos().up())
							? 4 * world.getLightLevel(LightType.SKY, machine.getPos()) / 15.0 : 0);
			case "wind_turbine" -> new PowerSolver.Source(id(machine), PowerSolver.SourceKind.WIND,
					8 * Math.max(0.25, Math.min(1, (machine.getPos().getY() - 50) / 80.0))
							* (world.isThundering() ? 1.5 : 1));
			case "utility_intake" -> new PowerSolver.Source(id(machine), PowerSolver.SourceKind.UTILITY,
					FacilityManager.get(world).activeEvent().equals("utility_outage") ? 0 : 100);
			case "diesel_generator" -> dieselSource(machine);
		case "modular_reactor" -> reactorSource(machine);
		case "battery_bank" -> new PowerSolver.Source(id(machine), PowerSolver.SourceKind.BATTERY,
				0, 3000, machine.chargeKws(), 15, 60);
		default -> null;
		};
	}

	private static PowerSolver.Source dieselSource(MachineBlockEntity machine) {
		if (machine.fuelBurnTicks() <= 0) {
			ItemStack fuel = machine.getStack(0);
			int burnTicks = FuelRegistry.INSTANCE.get(fuel.getItem());
			if (burnTicks <= 0) return null;
			machine.startFuel(burnTicks);
			net.minecraft.item.Item remainder = fuel.getItem().getRecipeRemainder();
			fuel.decrement(1);
			// Lava buckets and similar fuels leave their container behind, as in a furnace.
			if (remainder != null && fuel.isEmpty()) machine.setStack(0, new ItemStack(remainder));
		}
		return new PowerSolver.Source(id(machine), PowerSolver.SourceKind.DIESEL, 40,
				0, 0, 0, 0, machine.dieselSpinupSteps());
	}

	private static PowerSolver.Source reactorSource(MachineBlockEntity machine) {
		if (machine.getStack(0).isEmpty() || machine.isTripped()) return null;
		return new PowerSolver.Source(id(machine), PowerSolver.SourceKind.REACTOR, 500);
	}

	/** Drives the LIT block state so machine fronts show their powered texture. */
	private static void updateLitStates(ServerWorld world, List<MachineBlockEntity> machines,
			Map<MachineBlockEntity, Double> satisfaction, Map<MachineBlockEntity, Double> sourceOutput,
			Set<MachineBlockEntity> energized, NetworkManager networks) {
		for (MachineBlockEntity machine : machines) {
			boolean active = switch (machine.blockId()) {
				case "server_rack" -> satisfaction.getOrDefault(machine, 0.0) > 0 && !machine.isTripped();
				case "solar_panel", "wind_turbine", "utility_intake", "diesel_generator", "modular_reactor",
						"battery_bank" -> sourceOutput.getOrDefault(machine, 0.0) > 0;
				case "pdu" -> energized.contains(machine) && !machine.isTripped();
				case "uplink_router", "core_router", "monitoring_wall" ->
						networks.component(machine.getPos(), NetKind.DATA).size() > 1;
				case "fire_suppression_tank" -> !machine.getStack(0).isEmpty();
				case "crypto_exchange" -> FacilityManager.get(world).miningRacks() > 0;
				default -> satisfaction.getOrDefault(machine, 0.0) > 0;
			};
			BlockState state = machine.getCachedState();
			if (state.contains(MachineBlock.LIT) && state.get(MachineBlock.LIT) != active) {
				// NOTIFY_LISTENERS only: a neighbour update would needlessly rebuild every network.
				world.setBlockState(machine.getPos(), state.with(MachineBlock.LIT, active),
						net.minecraft.block.Block.NOTIFY_LISTENERS);
			}
		}
	}

	private static double demandFor(MachineBlockEntity machine) {
		return switch (machine.blockId()) {
			case "server_rack" -> ServerModel.calculate(machine.modules(), machine.loadLimitPercent(), 1,
					machine.inletCelsius(), true).demandKw();
			case "exhaust_fan" -> 0.2;
			case "cooling_tower" -> 4;
			case "crac_unit" -> 30 / 3.5;
			case "cdu" -> 0.5;
			case "facility_controller" -> 0.5;
			default -> 0;
		};
	}

	private static int priority(String blockId) {
		return blockId.equals("server_rack") ? 1 : 0;
	}

	private static String id(MachineBlockEntity machine) {
		return Long.toString(machine.getPos().asLong());
	}

	private static void applyCooling(ServerWorld world, List<MachineBlockEntity> machines,
			ThermalGrid heat, NetworkManager networks, Map<MachineBlockEntity, Double> satisfaction, double dt) {
		if (FacilityManager.get(world).activeEvent().equals("cooling_failure")) return;
		for (MachineBlockEntity unit : machines) {
			if (satisfaction.getOrDefault(unit, 0.0) <= 0) continue;
			Direction facing = unit.getCachedState().get(MachineBlock.FACING);
			if (unit.blockId().equals("exhaust_fan")) {
				BlockPos intake = unit.getPos().offset(facing);
				if (isAirCell(world, intake)) heat.removeKw(intake(intake), Math.min(3,
						0.5 * Math.max(0, heat.temperatureCelsius(intake(intake)) - heat.ambientCelsius())), dt);
			} else if (unit.blockId().equals("crac_unit")) {
				Set<BlockPos> coolant = networks.component(unit.getPos(), NetKind.COOLANT);
				boolean supplied = coolant.stream().map(world::getBlockEntity).filter(MachineBlockEntity.class::isInstance)
						.map(MachineBlockEntity.class::cast).anyMatch(tower -> tower.blockId().equals("cooling_tower")
								&& satisfaction.getOrDefault(tower, 0.0) > 0);
				if (!supplied) continue;
				for (BlockPos side : List.of(unit.getPos().offset(facing), unit.getPos().offset(facing.getOpposite()))) {
					if (!isAirCell(world, side)) continue;
					double requested = Math.min(15, RackcraftConfig.values.thermal.cracFlowKwPerK
							* Math.max(0, heat.temperatureCelsius(intake(side)) - 16));
					heat.removeKw(intake(side), requested * satisfaction.getOrDefault(unit, 0.0), dt);
				}
			}
		}
	}

	private static void applyMachineHeat(ServerWorld world, List<MachineBlockEntity> machines,
			ThermalGrid heat, double dt) {
		for (MachineBlockEntity machine : machines) {
			if (!machine.blockId().equals("diesel_generator") && !machine.blockId().equals("modular_reactor")) continue;
			Direction facing = machine.getCachedState().get(MachineBlock.FACING);
			BlockPos cell = machine.getPos().offset(facing.getOpposite());
			if (!isAirCell(world, cell)) continue;
			heat.depositKw(intake(cell), machine.blockId().equals("diesel_generator") ? 4 : 150, dt);
		}
	}

	private static void awardCredits(ServerWorld world, List<MachineBlockEntity> machines,
			Map<MachineBlockEntity, ServerModel.RackStep> rackSteps, NetworkManager networks,
			Map<MachineBlockEntity, Double> satisfaction, double dt) {
		FacilityManager facility = FacilityManager.get(world);
		double facilityRate = 0;
		int miningRacks = 0;
		for (Map.Entry<MachineBlockEntity, ServerModel.RackStep> entry : rackSteps.entrySet()) {
			MachineBlockEntity rack = entry.getKey();
			ServerModel.RackStep result = entry.getValue();
			Set<BlockPos> dataNetwork = networks.component(rack.getPos(), NetKind.DATA);
			double bandwidth = 0;
			double demand = 0;
			for (BlockPos pos : dataNetwork) {
				MachineBlockEntity endpoint = world.getBlockEntity(pos) instanceof MachineBlockEntity value ? value : null;
				if (endpoint == null) continue;
				if (endpoint.blockId().equals("uplink_router")) bandwidth += 100;
				if (endpoint.blockId().equals("core_router")) bandwidth += 1000;
				if (endpoint.blockId().equals("server_rack")) {
					demand += endpoint.modules().stream().mapToDouble(ServerModel.Module::creditsPerSecond).sum();
				}
			}
			double rate = bandwidth > 0 && demand > 0
					? result.creditsPerSecond() * Math.min(1, bandwidth / demand) : 0;
			RackStatus status;
			if (rack.modules().isEmpty()) status = RackStatus.EMPTY;
			else if (rack.isTripped()) status = RackStatus.TRIPPED;
			else if (satisfaction.getOrDefault(rack, 0.0) < 0.5) status = RackStatus.NO_POWER;
			else if (result.quantumBlocked()) status = RackStatus.NEEDS_CDU;
			else if (result.thermalFactor() <= 0) status = RackStatus.OVERHEATED;
			else if (bandwidth <= 0) status = RackStatus.NO_NETWORK;
			else if (bandwidth < demand) status = RackStatus.NETWORK_LIMITED;
			else if (result.thermalFactor() < 1) status = RackStatus.THROTTLED;
			else status = RackStatus.MINING;
			rack.setMining(status, rate);
			if (rate > 0) {
				facilityRate += rate;
				miningRacks++;
				facility.addCredits(rack.accrueCredits(rate, dt));
			}
		}
		facility.setMiningStats(facilityRate, miningRacks, rackSteps.size());
		facility.addAvailabilitySample(rackSteps.isEmpty() ? 1 : rackSteps.values().stream()
				.mapToDouble(step -> step.load() > 0 && step.thermalFactor() >= 0.6 ? 1 : 0).average().orElse(1));
	}

	private static List<BlockPos> airflowCells(ServerWorld world, BlockPos target, Direction facing, ThermalGrid heat) {
		if (isAirCell(world, target)) {
			heat.ensureCell(intake(target));
			return List.of(target);
		}
		List<BlockPos> fallback = new ArrayList<>();
		for (Direction direction : Direction.values()) {
			if (direction == facing || direction == facing.getOpposite()) continue;
			BlockPos candidate = target.offset(direction);
			if (isAirCell(world, candidate)) {
				heat.ensureCell(intake(candidate));
				fallback.add(candidate);
			}
		}
		return fallback;
	}

	private static boolean isAirCell(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return !state.isFullCube(world, pos) && !state.isIn(AIRFLOW_BLOCKING);
	}

	private static double averageTemperature(ThermalGrid heat, List<BlockPos> cells, double fallback) {
		return cells.isEmpty() ? Math.min(60, fallback) : cells.stream()
				.mapToDouble(pos -> heat.temperatureCelsius(intake(pos))).average().orElse(fallback);
	}

	private static void depositAcross(ThermalGrid heat, List<BlockPos> cells, double heatKw, double dt) {
		if (cells.isEmpty()) return;
		for (BlockPos cell : cells) heat.depositKw(intake(cell), heatKw / cells.size(), dt);
	}

	private static boolean adjacentMachine(List<MachineBlockEntity> machines, BlockPos pos, String id) {
		return machines.stream().anyMatch(machine -> machine.blockId().equals(id)
				&& machine.getPos().getManhattanDistance(pos) == 1);
	}

	private static ThermalGrid thermalGrid(ServerWorld world) {
		return THERMAL_GRIDS.computeIfAbsent(world, ignored -> {
			RackcraftConfig.Thermal config = RackcraftConfig.values.thermal;
			return new ThermalGrid(config.ambientC, config.cellCapacityKjPerK,
					config.faceConductanceKwPerK, config.upwardMultiplier, config.leakKwPerK,
					config.settleEpsilonK, config.maxActiveCells);
		});
	}

	public static void setHeat(ServerWorld world, BlockPos pos, double celsius) {
		thermalGrid(world).setTemperatureCelsius(intake(pos), celsius);
	}

	private static void scheduleEvent(ServerWorld world, FacilityManager facility,
			List<MachineBlockEntity> machines, boolean noRacks) {
		if (!RackcraftConfig.values.events.enabled || noRacks || facility.eventTicks() < 24000
				|| !facility.activeEvent().equals("none") || facility.eventTicks() - facility.lastEventTick() < 3600) return;
		if (facility.nextRandomInt(60) >= Math.max(0, Math.min(60, (int) RackcraftConfig.values.events.perHour))) return;
		int roll = facility.nextRandomInt(14);
		String[] ids = {"utility_outage", "cooling_failure", "hardware_failure", "cable_cut", "heat_wave", "surge"};
		int[] weights = {3, 3, 4, 2, 1, 1};
		String event = ids[0];
		for (int index = 0; index < weights.length; index++) {
			roll -= weights[index];
			if (roll < 0) {
				event = ids[index];
				break;
			}
		}
		long duration = switch (event) {
			case "utility_outage" -> 1800 + facility.nextRandomInt(1801);
			case "cooling_failure" -> 2400;
			case "heat_wave" -> 6000;
			default -> 1_200_000;
		};
		facility.triggerEvent(event, duration);
		if (event.equals("cable_cut")) cutRandomCable(world, networksFor(world));
		String alert = "Rackcraft event: " + event.replace('_', ' ');
		for (ServerPlayerEntity player : world.getPlayers()) {
			boolean nearby = machines.stream().filter(machine -> machine.blockId().equals("facility_controller"))
					.anyMatch(controller -> controller.getPos().getSquaredDistance(player.getBlockPos()) <= 4096);
			if (nearby) player.sendMessage(Text.literal(alert), true);
		}
	}

	private static NetworkManager networksFor(ServerWorld world) {
		return NetworkManager.get(world);
	}

	private static void cutRandomCable(ServerWorld world, NetworkManager networks) {
		List<BlockPos> cables = new ArrayList<>();
		for (NetKind kind : List.of(NetKind.POWER, NetKind.DATA)) {
			for (BlockPos pos : networks.endpoints(kind)) {
				if (world.getBlockState(pos).getBlock() instanceof CableBlock
						&& !world.getBlockState(pos).get(CableBlock.CUT)) cables.add(pos);
			}
		}
		if (!cables.isEmpty()) CableBlock.setCut(world, cables.get(FacilityManager.get(world).nextRandomInt(cables.size())), true);
	}

	private static ThermalGrid.CellPos intake(BlockPos pos) {
		return new ThermalGrid.CellPos(pos.getX(), pos.getY(), pos.getZ());
	}
}