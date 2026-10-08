package dev.rackcraft.world;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.RackcraftNetworking;
import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.CreativeSettings;
import dev.rackcraft.compute.ComputeScheduler;
import dev.rackcraft.compute.Research;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.compute.TrainingStations;
import dev.rackcraft.storage.StorageService;
import dev.rackcraft.storage.TransmitterUpgrades;
import dev.rackcraft.block.ArrayMachineBlock;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.RackBlock;
import dev.rackcraft.block.Racks;
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
	private static int failedSteps;

	/** Simulation steps that threw since startup; the log only shows one a minute, so the self-test checks this. */
	public static int failedSteps() { return failedSteps; }

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

	/** Every loaded Rackcraft machine in this world. */
	public static List<MachineBlockEntity> machines(ServerWorld world) {
		return LOADED.getOrDefault(world, Set.of()).stream()
				.filter(entity -> !entity.isRemoved() && entity.getWorld() == world).toList();
	}

	public static void stepNow(ServerWorld world) {
		safeStep(world);
	}

	private static void safeStep(ServerWorld world) {
		try {
			step(world);
		} catch (RuntimeException exception) {
			failedSteps++;
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
			if (!Racks.isRack(machine)) machine.setPowerKw(0);
		}
		RECTENNAS.put(world, LaunchPads.rectennas(world, machines));
		int maxEdge = ReactorArrays.maxEdge(world);
		Map<MachineBlockEntity, ReactorArrays.Array> reactors = ReactorArrays.scan(machines, maxEdge);
		Map<MachineBlockEntity, ReactorArrays.Array> arrays = ReactorArrays.scanAll(machines, maxEdge);
		Map<String, ReactorArrays.Array> exportSinks = new HashMap<>();
		Map<ReactorArrays.Array, Double> exported = new HashMap<>();

		for (Set<BlockPos> component : networks.components(NetKind.POWER)) {
			List<MachineBlockEntity> members = machines.stream()
					.filter(machine -> component.contains(machine.getPos())).toList();
			List<PowerSolver.Source> sources = new ArrayList<>();
			List<PowerSolver.Sink> sinks = new ArrayList<>();
			Map<String, MachineBlockEntity> sinkOwners = new HashMap<>();
			for (MachineBlockEntity machine : members) {
				PowerSolver.Source source = machine.blockId().equals("modular_reactor")
						? reactorSource(machine, reactors.get(machine))
						: machine.blockId().equals("battery_bank") ? batterySource(machine, arrays.get(machine)) : sourceFor(world, machine);
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
				// A Grid-Tie Substation is one export sink on its controller: it only takes spare solar, wind and reactor power.
				ReactorArrays.Array substation = machine.blockId().equals("grid_substation") ? arrays.get(machine) : null;
				if (substation != null && substation.controller() == machine && UtilityPlants.exportCapacityKw(substation) > 0) {
					String sinkId = Long.toString(machine.getPos().asLong());
					sinks.add(new PowerSolver.Sink(sinkId, 3, UtilityPlants.exportCapacityKw(substation), true));
					exportSinks.put(sinkId, substation);
				}
			}
			PowerSolver.Result result = PowerSolver.solve(sinks, sources, dt);
			if (result.suppliedKw() > 0) energized.addAll(members);
			double delivered = sinks.stream().filter(sink -> !sink.export())
					.mapToDouble(sink -> sink.demandKw() * result.satisfaction().getOrDefault(sink.id(), 0.0)).sum();
			for (PowerSolver.Sink sink : sinks) {
				ReactorArrays.Array substation = exportSinks.get(sink.id());
				if (substation != null) exported.put(substation, sink.demandKw() * result.satisfaction().getOrDefault(sink.id(), 0.0));
			}
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
					if (!Racks.isRack(owner)) owner.setPowerKw(demandFor(owner) * ratio);
				}
			});
		}

		for (Map.Entry<MachineBlockEntity, PowerSolver.Source> entry : powerSources.entrySet()) {
			MachineBlockEntity machine = entry.getKey();
			PowerSolver.Source source = entry.getValue();
			double output = sourceOutput.getOrDefault(machine, 0.0);
			if (machine.blockId().equals("battery_bank")) {
				settleBattery(arrays.get(machine), source, dt);
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
		Renewables.step(world, machines, powerSources, sourceOutput, dt);
		Research.Effects research = ResearchLab.effects(world);
		for (ReactorArrays.Array array : new HashSet<>(reactors.values())) {
			double output = sourceOutput.getOrDefault(array.controller(), 0.0);
			// An uprated reactor makes more from the same fuel: it burns as if it made its old output.
			ReactorArrays.burn(array, output / research.reactorOutput(), RackcraftConfig.values.sim.stepTicks);
			// Fuel is shared evenly across the cores, wherever it went in; Spent Fuel gathers in the port.
			ReactorArrays.pool(array.members(), ReactorArrays.FUEL_SLOT);
			ReactorArrays.gather(array, ReactorArrays.WASTE_SLOT);
			MachineBlockEntity controller = array.controller();
			int cells = array.fuelCells();
			ReactorArrays.ReactorStatus status = ReactorArrays.status(array, output);
			for (MachineBlockEntity member : array.members()) {
				member.setPowerKw(output);
				member.setReactorArray(array.edge(), array.capacityKw() * research.reactorOutput(), controller.fuelBurnTicks(),
						controller.fuelBurnTotal(), cells);
				member.setProcess(status.ordinal(), output > 0);
				member.setCube(array.edge() >= 2 && member == controller,
						ReactorArrays.count(array.members(), ReactorArrays.WASTE_SLOT, ReactorArrays.spentFuel()), 0, 0);
			}
		}
		HydrogenTanks.step(world, arrays);
		NuclearProcessing.step(world, arrays, satisfaction, dt, research);
		AssemblyLine.step(world, machines, satisfaction, dt);
		DroneDocks.step(world, machines, satisfaction);
		SitePlanner.step(world, machines, satisfaction);
		LaunchPads.step(world, machines, satisfaction);
		for (ReactorArrays.Array array : new HashSet<>(arrays.values())) {
			if (array.controller().blockId().equals("grid_substation")) UtilityPlants.sellPower(world, array, exported.getOrDefault(array, 0.0), dt);
		}

		ThermalGrid heat = thermalGrid(world);
		boolean coolingFailure = FacilityManager.get(world).activeEvent().equals("cooling_failure");
		boolean heatWave = FacilityManager.get(world).activeEvent().equals("heat_wave");
		Map<BlockPos, MachineBlockEntity> byPos = new HashMap<>();
		for (MachineBlockEntity machine : machines) byPos.put(machine.getPos(), machine);
		Map<MachineBlockEntity, Double> fanHeat = runFans(world, machines, heat, satisfaction, coolingFailure, dt);
		FreshwaterCooling.scanPumps(world, machines, satisfaction);
		UtilityPlants.scanDesalination(world, arrays, satisfaction);
		CoolingLoops loops = CoolingLoops.build(world, machines, satisfaction, coolingFailure, heatWave, research.sinkCapacity(),
				UtilityPlants.heatRecovery(world, arrays));
		pinCreativeCoolers(world, machines, heat);
		Map<MachineBlockEntity, ServerModel.RackStep> rackSteps = new HashMap<>();
		List<RackHeat> rackHeat = new ArrayList<>();
		for (MachineBlockEntity rack : machines) {
			if (!Racks.isRack(rack)) continue;
			ServerModel.Tier tier = Racks.tier(rack);
			Direction facing = rack.getCachedState().get(MachineBlock.FACING);
			BlockPos back = rack.getPos().offset(facing.getOpposite());
			MachineBlockEntity door = byPos.get(back);
			// A Rear-Door Cooler, or a CDU (which Quantum Cores need beside them anyway, and which would otherwise block
			// the exhaust), on the rack's back catches its exhaust into the coolant loop.
			if (door != null && !door.blockId().equals("rear_door_cooler") && !door.blockId().equals("cdu")) door = null;
			List<BlockPos> intakeCells = airflowCells(world, rack.getPos().offset(facing), facing, heat);
			// With a Rear-Door Cooler on the back, whatever it can't catch comes out of the far side of it.
			List<BlockPos> exhaustCells = airflowCells(world, door == null ? back : back.offset(facing.getOpposite()), facing, heat);
			double supplied = satisfaction.getOrDefault(rack, 0.0);
			double inlet = (intakeCells.isEmpty() ? chokedInlet(heat, rack) : averageTemperature(heat, intakeCells, heat.ambientCelsius()))
					+ (heatWave ? HEAT_WAVE_K : 0);
			// Research can widen the envelope: the rack is rated for hotter air, so it throttles and trips later.
			double rated = inlet - research.thermalOffset();
			boolean hasCdu = adjacentMachine(machines, rack.getPos(), "cdu");
			boolean cryostat = !Cryostats.beside(rack.getPos(), byPos, satisfaction).isEmpty();
			if (supplied < 0.5) rack.setTripped(true);
			// Racks boot slowly once they have power, and go cold again when they lose it.
			double bootSeconds = ServerModel.bootSeconds(rack.modules()) * RackcraftConfig.values.sim.rackBootScale * research.bootScale();
			if (supplied < 0.5 || rack.modules().isEmpty()) rack.setBootProgress(0);
			else rack.setBootProgress(bootSeconds <= 0 ? 1 : rack.bootProgress() + dt / bootSeconds);
			ServerModel.RackStep result = scalePower(ServerModel.calculate(tier, rack.modules(), rack.loadLimitPercent(),
					supplied, rated, hasCdu, cryostat, loops.cooled(rack.getPos()), rack.bootProgress()), research.rackPower());
			if (rack.isTripped() && rated < 32 && result.thermalFactor() > 0) rack.setTripped(false);
			if (rack.isTripped()) result = new ServerModel.RackStep(result.usedBays(), result.demandKw(),
					0, 0, result.thermalFactor(), result.quantumBlocked(), result.tripped(), result.waterBlocked(), result.cryoBlocked());
			rackSteps.put(rack, result);
			// Every kilowatt a rack actually draws comes back out as heat; an unpowered rack makes none.
			double total = result.demandKw() * supplied;
			double liquid = 0;
			if (loops.cooled(rack.getPos())) {
				// An immersion rack's fluid takes it all. Otherwise cold plates take their share, and a High-Density
				// Rack's built-in door catches some of what's left.
				liquid = tier.immersed() ? total
						: Math.min(total, ServerModel.liquidHeatKw(rack.modules(), result.load()) * research.rackPower() * supplied);
				liquid += Math.min(tier.doorKw(), total - liquid);
			}
			loops.request(rack.getPos(), liquid);
			double doorKw = door != null && satisfaction.getOrDefault(door, 0.0) >= 0.5 && loops.cooled(door.getPos())
					? Math.min(CoolingLoops.REAR_DOOR_KW, total - liquid) : 0;
			if (door != null) loops.request(door.getPos(), doorKw);
			rackHeat.add(new RackHeat(rack, result, inlet, total, liquid, door, doorKw, intakeCells, exhaustCells));
		}
		Cryostats.step(machines, rackSteps, byPos, satisfaction, dt);
		List<CracSide> cracSides = new ArrayList<>();
		for (MachineBlockEntity crac : machines) {
			if (!crac.blockId().equals("crac_unit")) continue;
			crac.setCooling(0, 0);
			double power = Math.min(1, satisfaction.getOrDefault(crac, 0.0));
			if (power <= 0 || coolingFailure || !loops.cooled(crac.getPos())) continue;
			Direction facing = crac.getCachedState().get(MachineBlock.FACING);
			for (BlockPos side : List.of(crac.getPos().offset(facing), crac.getPos().offset(facing.getOpposite()))) {
				if (!isAirCell(world, side)) continue;
				double requested = Math.min(CoolingLoops.CRAC_KW_PER_SIDE, CoolingLoops.CRAC_FLOW_KW_PER_K
						* Math.max(0, heat.temperatureCelsius(intake(side)) - heat.ambientCelsius())) * power;
				if (requested <= 0) continue;
				loops.request(crac.getPos(), requested);
				cracSides.add(new CracSide(crac, side, requested));
			}
		}
		Map<ReactorArrays.Array, Double> reactorAirHeat = new HashMap<>();
		for (ReactorArrays.Array array : new HashSet<>(reactors.values())) {
			double reactorHeat = array.controller().powerKw() * CoolingLoops.REACTOR_HEAT_SHARE;
			if (reactorHeat <= 0) continue;
			MachineBlockEntity piped = array.members().stream().filter(member -> loops.cooled(member.getPos())).findFirst().orElse(null);
			if (piped == null) {
				reactorAirHeat.put(array, reactorHeat);
				continue;
			}
			loops.request(piped.getPos(), reactorHeat);
			reactorAirHeat.put(array, -reactorHeat);
		}
		// Every request is in: each loop now knows what share it can take. Doors (and CDUs) report what they caught this
		// step, summed when one sits behind two racks; a door behind no running rack reports nothing.
		for (MachineBlockEntity machine : machines) {
			if (machine.blockId().equals("rear_door_cooler") || machine.blockId().equals("cdu")) machine.setCooling(0, 0);
		}
		for (RackHeat entry : rackHeat) {
			MachineBlockEntity rack = entry.rack();
			double toLoop = entry.liquidKw() * loops.ratio(rack.getPos());
			double caught = entry.door() == null ? 0 : entry.doorKw() * loops.ratio(entry.door().getPos());
			if (entry.door() != null) entry.door().setCooling(entry.door().coolingKw() + caught, 0);
			double toAir = Math.max(0, entry.totalKw() - toLoop - caught);
			// Exhaust with nowhere to go blows back into the intake.
			depositAcross(heat, entry.exhaustCells().isEmpty() ? entry.intakeCells() : entry.exhaustCells(), toAir, dt);
			rack.setHeatSplit(toLoop + caught, toAir);
			ServerModel.RackStep result = entry.step();
			rack.setRackStats(entry.inlet(), averageTemperature(heat, entry.exhaustCells(), entry.inlet()), entry.totalKw(),
					result.load(), result.thermalFactor());
		}
		for (CracSide side : cracSides) {
			double removed = heat.removeKw(intake(side.cell()), side.requestedKw() * loops.ratio(side.crac().getPos()), dt);
			side.crac().setCooling(side.crac().coolingKw() + removed, 0);
		}
		reactorAirHeat.forEach((array, reactorHeat) -> {
			double toAir = reactorHeat;
			if (reactorHeat < 0) {
				MachineBlockEntity piped = array.members().stream().filter(member -> loops.cooled(member.getPos())).findFirst().orElseThrow();
				toAir = -reactorHeat * (1 - loops.ratio(piped.getPos()));
			}
			depositAcross(heat, aroundArray(world, array, heat), toAir, dt);
		});
		loops.finish(world, dt);
		UtilityPlants.sellHeat(world, arrays, dt);

		updateLitStates(world, machines, satisfaction, sourceOutput, energized, networks, fanHeat);
		updateFormedStates(world, arrays);
		stepStorage(world, machines, heat, satisfaction, dt);
		if (world.getTime() % 20 < Math.max(1, RackcraftConfig.values.sim.stepTicks)) ItemPipes.step(world, machines);
		TrainingStations.step(world, machines, dt);
		if (world.getTime() % 10 < Math.max(1, RackcraftConfig.values.sim.stepTicks)) TrainingStations.syncShackles(world, machines);
		Map<MachineBlockEntity, Double> scrubbers = new HashMap<>();
		for (MachineBlockEntity machine : machines) {
			if (machine.blockId().equals("smog_scrubber")) scrubbers.put(machine, satisfaction.getOrDefault(machine, 0.0));
		}
		AirQuality.get(world).step(world, fanHeat, scrubbers, dt);
		if (world.getTime() % 40 < Math.max(1, RackcraftConfig.values.sim.stepTicks)) Radiation.step(world);
		applyMachineHeat(world, machines, heat, dt);
		heat.step(dt, airMap(world));
		pinCreativeCoolers(world, machines, heat);
		RackcraftNetworking.sendHeatCells(world, heat);
		if (world.getTime() % 20 < Math.max(1, RackcraftConfig.values.sim.stepTicks)) RackcraftNetworking.sendHud(world, machines);
		List<MachineBlockEntity> racks = machines.stream().filter(Racks::isRack).toList();
		Map<MachineBlockEntity, RackStatus> lent = ComputeScheduler.tick(world, racks, dt);
		awardCredits(world, machines, rackSteps, networks, satisfaction, lent, dt);
		updateRackHealth(world, racks);
		updateRouters(world, machines, networks);
		FaultFinder.send(world, machines);
		// The darknet runs wherever a terminal is loaded, or once it has been used: auctions and parcels keep their clocks.
		dev.rackcraft.darknet.DarknetMarket darknet = machines.stream().anyMatch(machine -> machine.blockId().equals("darknet_terminal"))
				? dev.rackcraft.darknet.DarknetMarket.get(world) : dev.rackcraft.darknet.DarknetMarket.existing(world);
		if (darknet != null) darknet.tick(world, world.getTime());
		FacilityManager facility = FacilityManager.get(world);
		long previousEventTick = facility.eventTicks();
		facility.advanceEventClock(RackcraftConfig.values.sim.stepTicks);
		if (facility.eventTicks() / 1200 > previousEventTick / 1200) {
			scheduleEvent(world, facility, machines, rackSteps.isEmpty());
		}
	}

	private static PowerSolver.Source sourceFor(ServerWorld world, MachineBlockEntity machine) {
		return switch (machine.blockId()) {
			// Sky light is measured above the panel: the panel itself is a solid block, where light is always 0.
			case "solar_panel" -> new PowerSolver.Source(id(machine), PowerSolver.SourceKind.SOLAR,
					world.isDay() && world.isSkyVisible(machine.getPos().up())
							? 4 * world.getLightLevel(LightType.SKY, machine.getPos().up()) / 15.0
								* AirQuality.solarFactor(AirQuality.get(world).smogAt(machine.getPos())) : 0);
			case "wind_turbine" -> new PowerSolver.Source(id(machine), PowerSolver.SourceKind.WIND, Renewables.turbineKw(world, machine.getPos()));
			// The Assembly Line tier: each part of a Solar Array is a sixth of it; a Wind Tower's nacelle is the whole tower.
			case "solar_array", "solar_array_tracking" -> new PowerSolver.Source(id(machine), PowerSolver.SourceKind.SOLAR,
					Renewables.arrayPartKw(world, machine));
			case "wind_nacelle" -> new PowerSolver.Source(id(machine), PowerSolver.SourceKind.WIND, Renewables.towerKw(world, machine));
			case "utility_intake" -> new PowerSolver.Source(id(machine), PowerSolver.SourceKind.UTILITY,
					FacilityManager.get(world).activeEvent().equals("utility_outage") ? 0 : 100);
			case "diesel_generator" -> dieselSource(machine);
			// Beamed down from the Dyson swarm: steady, day and night, drawn first and never resold.
			case "rectenna" -> world.isSkyVisible(machine.getPos().up())
					? new PowerSolver.Source(id(machine), PowerSolver.SourceKind.BEAMED, OrbitState.rectennaKw(world, rectennaCount(world))) : null;
			case "creative_power" -> new PowerSolver.Source(id(machine), PowerSolver.SourceKind.UTILITY,
					machine.creativeValue(CreativeSettings.OUTPUT_KW));
		default -> null;
		};
	}

	/** Rectennas that can see the sky, counted at the start of each step so every one gets its share of the swarm. */
	private static final Map<ServerWorld, Integer> RECTENNAS = new WeakHashMap<>();

	private static int rectennaCount(ServerWorld world) {
		return RECTENNAS.getOrDefault(world, 0);
	}

	private static PowerSolver.Source dieselSource(MachineBlockEntity machine) {
		if (machine.fuelBurnTicks() <= 0) {
			ItemStack fuel = machine.getStack(0);
			// FuelRegistry returns null for anything that isn't fuel, including an empty slot.
			Integer burnTicks = fuel.isEmpty() ? null : FuelRegistry.INSTANCE.get(fuel.getItem());
			if (burnTicks == null || burnTicks <= 0) return null;
			machine.startFuel(burnTicks);
			net.minecraft.item.Item remainder = fuel.getItem().getRecipeRemainder();
			fuel.decrement(1);
			// Lava buckets and similar fuels leave their container behind, as in a furnace.
			if (remainder != null && fuel.isEmpty()) machine.setStack(0, new ItemStack(remainder));
		}
		return new PowerSolver.Source(id(machine), PowerSolver.SourceKind.DIESEL, 40,
				0, 0, 0, 0, machine.dieselSpinupSteps());
	}

	public static final double BATTERY_KWS = 3000;
	private static final double BATTERY_IN_KW = 15;
	private static final double BATTERY_OUT_KW = 60;

	/** Capacity per bank in a Grid-Scale Battery of this edge: 10% more per step up to a 5-cube, then 5% more per step (65% at 10). */
	public static double batteryCapacityPerBank(int edge) {
		int clamped = Math.max(1, Math.min(ReactorArrays.MAX_EDGE, edge));
		double bonus = clamped <= ReactorArrays.BASE_EDGE ? 0.1 * (clamped - 1) : 0.4 + 0.05 * (clamped - ReactorArrays.BASE_EDGE);
		return BATTERY_KWS * (1 + bonus);
	}

	/** One-way efficiency (charging, and again discharging): 90% for a lone bank, 98% for a 5-cube, 99% for a 10-cube. */
	public static double batteryEfficiency(int edge) {
		int clamped = Math.max(1, Math.min(ReactorArrays.MAX_EDGE, edge));
		return clamped <= ReactorArrays.BASE_EDGE ? 0.9 + 0.02 * (clamped - 1) : 0.98 + 0.002 * (clamped - ReactorArrays.BASE_EDGE);
	}

	/**
	 * A Grid-Scale Battery (or a lone bank) is one source on its controller, holding every bank's charge at once.
	 * The other banks in the cube supply nothing themselves.
	 */
	private static PowerSolver.Source batterySource(MachineBlockEntity machine, ReactorArrays.Array array) {
		if (array == null || array.controller() != machine) return null;
		int banks = array.cores();
		double capacity = batteryCapacityPerBank(array.edge()) * banks;
		double charge = array.members().stream().mapToDouble(MachineBlockEntity::chargeKws).sum();
		return new PowerSolver.Source(id(machine), PowerSolver.SourceKind.BATTERY, 0, capacity, Math.min(capacity, charge),
				BATTERY_IN_KW * banks, BATTERY_OUT_KW * banks).efficiency(batteryEfficiency(array.edge()));
	}

	/** Shares a battery's new charge evenly over its banks; each reports the whole battery's signed rate and size. */
	private static void settleBattery(ReactorArrays.Array array, PowerSolver.Source source, double dt) {
		if (array == null) return;
		double before = array.members().stream().mapToDouble(MachineBlockEntity::chargeKws).sum();
		double rate = (source.chargeKws() - before) / dt;
		double capacity = batteryCapacityPerBank(array.edge()) * array.cores();
		for (MachineBlockEntity member : array.members()) {
			member.setChargeKws(source.chargeKws() / array.cores());
			// Signed: positive while charging, negative while discharging.
			member.setPowerKw(rate);
			member.setReactorArray(array.edge(), capacity, 0, 0, 0);
		}
	}

	/** A reactor array is one source, on its controller; the other cores in it supply nothing themselves. */
	private static PowerSolver.Source reactorSource(MachineBlockEntity machine, ReactorArrays.Array array) {
		if (array == null || array.controller() != machine) return null;
		if (array.members().stream().anyMatch(MachineBlockEntity::isTripped) || !ReactorArrays.refuel(array)) return null;
		return new PowerSolver.Source(id(machine), PowerSolver.SourceKind.REACTOR, array.capacityKw() * effects(machine).reactorOutput());
	}

	/** Drives the LIT block state so machine fronts show their powered texture. */
	private static void updateLitStates(ServerWorld world, List<MachineBlockEntity> machines,
			Map<MachineBlockEntity, Double> satisfaction, Map<MachineBlockEntity, Double> sourceOutput,
			Set<MachineBlockEntity> energized, NetworkManager networks, Map<MachineBlockEntity, Double> fanHeat) {
		for (MachineBlockEntity machine : machines) {
			boolean active = switch (machine.blockId()) {
				case "server_rack", "high_density_rack", "immersion_rack", "exascale_cabinet" ->
						satisfaction.getOrDefault(machine, 0.0) > 0 && !machine.isTripped();
				case "cryostat" -> machine.processActive();
				case "solar_panel", "wind_turbine", "utility_intake", "diesel_generator", "creative_power", "rectenna", "solar_array",
						"solar_array_tracking", "wind_nacelle" ->
						sourceOutput.getOrDefault(machine, 0.0) > 0;
				case "battery_bank" -> machine.powerKw() < -0.01;
				case "modular_reactor" -> machine.powerKw() > 0;
				case "uranium_mill", "gas_centrifuge", "fuel_fabricator", "cask_sealer", "wafer_fab", "silicon_foundry", "ewaste_recycler",
						"electrolyser" -> machine.processStatus() == NuclearProcessing.Status.RUNNING.ordinal()
								|| machine.processStatus() == NuclearProcessing.Status.LOW_POWER.ordinal();
				// Fans only spin while they have heat to move; cooling gear shows when it is actually working.
				case "exhaust_fan" -> fanHeat.containsKey(machine);
				case "cooling_tower", "dry_cooler", "chiller", "water_heat_exchanger", "rear_door_cooler", "crac_unit" ->
						satisfaction.getOrDefault(machine, 0.0) > 0 && machine.coolingKw() > 0.05;
				case "creative_rack" -> machine.creativeValue(CreativeSettings.MINING_RATE) > 0;
				case "creative_cooler" -> true;
				case "storage_array", "tape_library" -> machine.storageOnline();
				case "wireless_transmitter", "storage_link", "auto_buyer" -> satisfaction.getOrDefault(machine, 0.0) >= 0.5;
				case "hydrogen_tank" -> machine.hydrogen() > 0;
				case "storage_terminal" -> networks.component(machine.getPos(), NetKind.DATA).stream()
						.map(world::getBlockEntity).anyMatch(entity -> entity instanceof MachineBlockEntity member
								&& (member.blockId().equals("storage_array") || member.blockId().equals("tape_library"))
								&& member.storageOnline());
				case "creative_router" -> machine.creativeValue(CreativeSettings.BANDWIDTH) > 0;
				case "pdu" -> energized.contains(machine) && !machine.isTripped();
				case "uplink_router", "core_router", "monitoring_wall" ->
						networks.component(machine.getPos(), NetKind.DATA).size() > 1;
				case "fire_suppression_tank" -> !machine.getStack(0).isEmpty();
				case "crypto_exchange" -> FacilityManager.get(world).miningRacks() > 0;
				case "freshwater_pump", "desalination_plant" -> machine.pumpStatus() == FreshwaterCooling.PumpStatus.PUMPING.ordinal();
				case "grid_substation" -> machine.powerKw() > 0.05;
				case "heat_recovery_plant" -> machine.coolingKw() > 0.05;
				// Robot arms light up (and their renderer animates them) only while they work.
				case "welding_arm", "riveting_arm", "assembly_arm" -> machine.processActive();
				case "art_table", "writing_desk" -> TrainingStations.active(machine);
				case "operations_terminal", "darknet_terminal" -> true;
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

	/** Routers report their fiber network: bandwidth, what its racks need, and how many racks it carries. */
	private static void updateRouters(ServerWorld world, List<MachineBlockEntity> machines, NetworkManager networks) {
		Map<Set<BlockPos>, double[]> byNetwork = new java.util.IdentityHashMap<>();
		for (MachineBlockEntity router : machines) {
			String id = router.blockId();
			if (!id.equals("uplink_router") && !id.equals("core_router") && !id.equals("creative_router")) continue;
			double[] stats = byNetwork.computeIfAbsent(networks.component(router.getPos(), NetKind.DATA), network -> {
				double bandwidth = 0;
				double demand = 0;
				int racks = 0;
				for (BlockPos pos : network) {
					if (!(world.getBlockEntity(pos) instanceof MachineBlockEntity member)) continue;
					switch (member.blockId()) {
						case "uplink_router" -> bandwidth += 100;
						case "core_router" -> bandwidth += 1000;
						case "creative_router" -> bandwidth += member.creativeValue(CreativeSettings.BANDWIDTH);
						default -> {
							if (Racks.isRack(member)) {
								racks++;
								demand += Racks.bandwidthNeed(member);
							}
						}
					}
				}
				return new double[] {bandwidth, demand, racks};
			});
			router.setDataNetwork(stats[0], stats[1], (int) stats[2]);
		}
	}

	/** How far a rack will have booted after this step if it gets its power, which is what it asks the grid for. */
	private static double nextBoot(MachineBlockEntity rack) {
		double seconds = ServerModel.bootSeconds(rack.modules()) * RackcraftConfig.values.sim.rackBootScale * effects(rack).bootScale();
		double dt = Math.max(1, RackcraftConfig.values.sim.stepTicks) / 20.0;
		return seconds <= 0 ? 1 : Math.min(1, rack.bootProgress() + dt / seconds);
	}

	/**
	 * Multiblock cores switch to their array casing while they are part of a whole cube: one casing up to 5x5x5,
	 * another for 6x6x6 to 9x9x9, and its own for a 10x10x10. A cube too big for the research done remembers its size.
	 */
	private static void updateFormedStates(ServerWorld world, Map<MachineBlockEntity, ReactorArrays.Array> arrays) {
		arrays.forEach((machine, array) -> {
			machine.setLockedCube(array.locked());
			BlockState state = machine.getCachedState();
			boolean formed = array.edge() >= 2;
			boolean port = formed && machine == array.controller() && ReactorArrays.hasPort(machine.blockId());
			int scale = formed ? ArrayMachineBlock.scaleFor(array.edge()) : 0;
			if (state.contains(ArrayMachineBlock.FORMED) && (state.get(ArrayMachineBlock.FORMED) != formed
					|| state.get(ArrayMachineBlock.PORT) != port || state.get(ArrayMachineBlock.SCALE) != scale)) {
				world.setBlockState(machine.getPos(), state.with(ArrayMachineBlock.FORMED, formed).with(ArrayMachineBlock.PORT, port)
						.with(ArrayMachineBlock.SCALE, scale), net.minecraft.block.Block.NOTIFY_LISTENERS);
			}
		});
	}

	/** Racks show their health on their front: amber when slowed, red when stopped. */
	private static void updateRackHealth(ServerWorld world, List<MachineBlockEntity> racks) {
		for (MachineBlockEntity rack : racks) {
			BlockState state = rack.getCachedState();
			RackBlock.Health health = RackBlock.Health.of(rack.rackStatus());
			if (state.contains(RackBlock.HEALTH) && state.get(RackBlock.HEALTH) != health) {
				world.setBlockState(rack.getPos(), state.with(RackBlock.HEALTH, health), net.minecraft.block.Block.NOTIFY_LISTENERS);
			}
		}
	}

	private static double demandFor(MachineBlockEntity machine) {
		if (Racks.isRack(machine)) {
			return ServerModel.calculate(Racks.tier(machine), machine.modules(), machine.loadLimitPercent(), 1,
					machine.inletCelsius() - effects(machine).thermalOffset(), true, true, true, nextBoot(machine)).demandKw()
					* effects(machine).rackPower();
		}
		return switch (machine.blockId()) {
			case "cryostat" -> Cryostats.KW;
			case "exhaust_fan" -> 0.2;
			case "cooling_tower" -> 4;
			case "crac_unit" -> 3;
			case "rear_door_cooler" -> 0.3;
			case "dry_cooler" -> 2;
			case "chiller" -> CoolingLoops.CHILLER_BASE_KW + CoolingLoops.CHILLER_KW_PER_KW * machine.coolingKw();
			case "water_heat_exchanger" -> 0.5;
			case "uranium_mill", "gas_centrifuge", "fuel_fabricator", "cask_sealer", "wafer_fab", "silicon_foundry", "ewaste_recycler",
					"electrolyser", "cvd_furnace", "epitaxy_reactor" -> NuclearProcessing.demandKw(machine);
			case "welding_arm", "riveting_arm", "assembly_arm" -> AssemblyLine.demandKw(machine);
			case "drone_dock" -> DroneDocks.DOCK_KW;
			case "storage_link" -> 4;
			case "auto_buyer" -> 2;
			case "site_planner" -> SitePlanner.PLANNER_KW;
			case "launch_control" -> LaunchPads.CONTROL_KW;
			case "cdu" -> 0.5;
			case "desalination_plant" -> UtilityPlants.desalinationKw(machine);
			case "facility_controller" -> 0.5;
			case "creative_rack" -> machine.creativeValue(CreativeSettings.DRAW_KW);
			case "storage_array" -> 0.4 + 0.15 * machine.driveCount();
			case "tape_library" -> 0.3;
			case "wireless_transmitter" -> TransmitterUpgrades.drawKw(machine.transmitterLevel());
			case "freshwater_pump" -> 1.5;
			case "smog_scrubber" -> 6;
			default -> 0;
		};
	}

	private static Research.Effects effects(MachineBlockEntity machine) {
		return machine.getWorld() instanceof ServerWorld world ? ResearchLab.effects(world) : Research.Effects.NONE;
	}

	/** Research that cuts rack power cuts its heat too: every kilowatt drawn comes back out as heat. */
	private static ServerModel.RackStep scalePower(ServerModel.RackStep step, double scale) {
		if (scale == 1) return step;
		return new ServerModel.RackStep(step.usedBays(), step.demandKw() * scale, step.creditsPerSecond(), step.load(),
				step.thermalFactor(), step.quantumBlocked(), step.tripped(), step.waterBlocked(), step.cryoBlocked());
	}

	/** A heat wave makes the air every rack breathes this much hotter. */
	public static final double HEAT_WAVE_K = 4;

	private static int priority(String blockId) {
		return Racks.isRack(blockId) || blockId.equals("creative_rack") ? 1 : 0;
	}

	private static String id(MachineBlockEntity machine) {
		return Long.toString(machine.getPos().asLong());
	}

	private record RackHeat(MachineBlockEntity rack, ServerModel.RackStep step, double inlet, double totalKw, double liquidKw,
			MachineBlockEntity door, double doorKw, List<BlockPos> intakeCells, List<BlockPos> exhaustCells) {}

	private record CracSide(MachineBlockEntity crac, BlockPos cell, double requestedKw) {}

	/** An exhaust fan only spins when the air in front of it is warmer than ambient by this much. */
	private static final double FAN_START_K = 1;
	public static final double FAN_MAX_KW = 10;
	private static final double FAN_FLOW_KW_PER_K = 1.5;

	/**
	 * Exhaust fans pull hot air from the cell in front of them and dump it outside. They only run when there is
	 * heat to move: a fan in cool air idles and makes no smog. Returns each running fan and the heat it moved.
	 */
	private static Map<MachineBlockEntity, Double> runFans(ServerWorld world, List<MachineBlockEntity> machines, ThermalGrid heat,
			Map<MachineBlockEntity, Double> satisfaction, boolean coolingFailure, double dt) {
		Map<MachineBlockEntity, Double> fanHeat = new HashMap<>();
		for (MachineBlockEntity fan : machines) {
			if (!fan.blockId().equals("exhaust_fan")) continue;
			fan.setCooling(0, 0);
			double power = Math.min(1, satisfaction.getOrDefault(fan, 0.0));
			if (power <= 0 || coolingFailure) continue;
			BlockPos front = fan.getPos().offset(fan.getCachedState().get(MachineBlock.FACING));
			if (!isAirCell(world, front)) continue;
			double excess = heat.temperatureCelsius(intake(front)) - heat.ambientCelsius();
			if (excess < FAN_START_K) continue;
			double removed = heat.removeKw(intake(front), Math.min(FAN_MAX_KW, FAN_FLOW_KW_PER_K * excess) * power, dt);
			if (removed <= 0) continue;
			fan.setCooling(removed, 0);
			fanHeat.put(fan, removed);
		}
		return fanHeat;
	}

	/** A rack with its intake blocked breathes its own heat: its inlet creeps toward what it puts out, and cools off when idle. */
	private static double chokedInlet(ThermalGrid heat, MachineBlockEntity rack) {
		double target = heat.ambientCelsius() + 1.5 * rack.powerKw();
		return Math.min(60, rack.inletCelsius() + (target - rack.inletCelsius()) * 0.25);
	}

	/** The air around a reactor array (or behind a lone reactor), where its heat goes when it isn't piped to a loop. */
	private static List<BlockPos> aroundArray(ServerWorld world, ReactorArrays.Array array, ThermalGrid heat) {
		if (array.cores() == 1) {
			MachineBlockEntity reactor = array.controller();
			Direction facing = reactor.getCachedState().get(MachineBlock.FACING);
			return airflowCells(world, reactor.getPos().offset(facing.getOpposite()), facing, heat);
		}
		Set<BlockPos> members = new HashSet<>();
		for (MachineBlockEntity member : array.members()) members.add(member.getPos());
		List<BlockPos> cells = new ArrayList<>();
		for (BlockPos pos : members) {
			for (Direction direction : Direction.values()) {
				BlockPos side = pos.offset(direction);
				if (!members.contains(side) && isAirCell(world, side) && !cells.contains(side)) {
					track(world, heat, side);
					cells.add(side);
				}
			}
		}
		return cells;
	}

	/** What the thermal grid sees: air it can spread into (in loaded chunks only), and which of it is outdoors. */
	private static ThermalGrid.AirMap airMap(ServerWorld world) {
		return new ThermalGrid.AirMap() {
			@Override
			public boolean passable(ThermalGrid.CellPos cell) {
				BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
				if (world.isOutOfHeightLimit(pos) || !world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;
				return isAirCell(world, pos);
			}

			@Override
			public boolean outdoors(ThermalGrid.CellPos cell) {
				return world.isSkyVisible(new BlockPos(cell.x(), cell.y(), cell.z()));
			}
		};
	}

	private static void track(ServerWorld world, ThermalGrid heat, BlockPos pos) {
		heat.ensureCell(intake(pos), world.isSkyVisible(pos));
	}

	/**
	 * Storage arrays behave like racks: they take air in the front, exhaust their draw as heat out the back,
	 * and go offline when unpowered or at 40 C. Tape libraries only need power. Transmitters refresh their
	 * cached drive list, and every 30 seconds idle items are archived from drives to tape.
	 */
	private static void stepStorage(ServerWorld world, List<MachineBlockEntity> machines, ThermalGrid heat,
			Map<MachineBlockEntity, Double> satisfaction, double dt) {
		for (MachineBlockEntity machine : machines) {
			double supplied = satisfaction.getOrDefault(machine, 0.0);
			switch (machine.blockId()) {
				case "tape_library" -> machine.setStorageOnline(supplied >= 0.5);
				case "storage_array" -> {
					Direction facing = machine.getCachedState().get(MachineBlock.FACING);
					List<BlockPos> intakeCells = airflowCells(world, machine.getPos().offset(facing), facing, heat);
					List<BlockPos> exhaustCells = airflowCells(world, machine.getPos().offset(facing.getOpposite()), facing, heat);
					double inlet = averageTemperature(heat, intakeCells, machine.inletCelsius());
					double thermal = ServerModel.thermalFactor(inlet);
					double draw = demandFor(machine) * supplied;
					depositAcross(heat, exhaustCells, draw, dt);
					machine.setRackStats(inlet, averageTemperature(heat, exhaustCells, inlet), draw, supplied, thermal);
					machine.setStorageOnline(supplied >= 0.5 && thermal > 0);
				}
				case "wireless_transmitter" -> StorageService.updateTransmitter(world, machine);
				case "storage_link", "auto_buyer" -> machine.setStorageOnline(supplied >= 0.5);
				default -> {}
			}
		}
		// Auto-Buyers report what they have bought, all time.
		for (MachineBlockEntity machine : machines) {
			if (!machine.blockId().equals("auto_buyer")) continue;
			machine.setSiteReading(0, (int) Math.min(Integer.MAX_VALUE, machine.site().getLong("Bought")));
			machine.setSiteReading(1, (int) Math.min(Integer.MAX_VALUE, machine.site().getLong("Spent") / 1000));
		}
		// Storage Links report how many links are up and how many drives and tapes they join.
		for (MachineBlockEntity machine : machines) {
			if (!machine.blockId().equals("storage_link")) continue;
			var joined = StorageService.networkOf(world, List.of(machine.getPos()));
			machine.setSiteReading(0, machine.storageOnline() ? StorageService.links(world).size() : 0);
			machine.setSiteReading(1, machine.storageOnline() ? joined.hotDrives() + joined.tapes() : 0);
		}
		if (world.getTime() % 600 >= Math.max(1, RackcraftConfig.values.sim.stepTicks)) return;
		Set<BlockPos> visited = new HashSet<>();
		for (MachineBlockEntity library : machines) {
			if (!library.blockId().equals("tape_library") || !library.storageOnline() || visited.contains(library.getPos())) continue;
			visited.addAll(NetworkManager.get(world).component(library.getPos(), NetKind.DATA));
			StorageService.networkAt(world, library.getPos()).archive(0.85, 0.7);
		}
	}

	/** Creative coolers hold the air in front of and behind them at their set temperature. */
	private static void pinCreativeCoolers(ServerWorld world, List<MachineBlockEntity> machines, ThermalGrid heat) {
		for (MachineBlockEntity cooler : machines) {
			if (!cooler.blockId().equals("creative_cooler")) continue;
			Direction facing = cooler.getCachedState().get(MachineBlock.FACING);
			double target = cooler.creativeValue(CreativeSettings.TARGET_C);
			for (BlockPos side : List.of(cooler.getPos().offset(facing), cooler.getPos().offset(facing.getOpposite()))) {
				if (!isAirCell(world, side)) continue;
				track(world, heat, side);
				heat.setTemperatureCelsius(intake(side), target);
			}
		}
	}

	/** Diesel generators vent 4 kW at full output out of their radiator at the back. */
	private static void applyMachineHeat(ServerWorld world, List<MachineBlockEntity> machines,
			ThermalGrid heat, double dt) {
		for (MachineBlockEntity machine : machines) {
			if (!machine.blockId().equals("diesel_generator") || machine.powerKw() <= 0) continue;
			Direction facing = machine.getCachedState().get(MachineBlock.FACING);
			BlockPos cell = machine.getPos().offset(facing.getOpposite());
			if (!isAirCell(world, cell)) continue;
			track(world, heat, cell);
			heat.depositKw(intake(cell), 4 * Math.min(1, machine.powerKw() / 40), dt);
		}
	}

	private static void awardCredits(ServerWorld world, List<MachineBlockEntity> machines,
			Map<MachineBlockEntity, ServerModel.RackStep> rackSteps, NetworkManager networks,
			Map<MachineBlockEntity, Double> satisfaction, Map<MachineBlockEntity, RackStatus> lent, double dt) {
		FacilityManager facility = FacilityManager.get(world);
		double mining = ResearchLab.effects(world).mining();
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
				if (endpoint.blockId().equals("creative_router")) bandwidth += endpoint.creativeValue(CreativeSettings.BANDWIDTH);
				if (Racks.isRack(endpoint)) demand += Racks.bandwidthNeed(endpoint);
			}
			double rate = bandwidth > 0 && demand > 0
					? result.creditsPerSecond() * Math.min(1, bandwidth / demand) * mining : 0;
			RackStatus status;
			RackStatus lentAs = lent.get(rack);
			if (lentAs != null) {
				rack.setMining(lentAs, 0);
				continue;
			}
			if (rack.modules().isEmpty()) status = RackStatus.EMPTY;
			else if (rack.isTripped()) status = RackStatus.TRIPPED;
			else if (satisfaction.getOrDefault(rack, 0.0) < 0.5) status = RackStatus.NO_POWER;
			else if (result.quantumBlocked()) status = RackStatus.NEEDS_CDU;
			else if (result.cryoBlocked()) status = RackStatus.NEEDS_CRYOSTAT;
			else if (result.waterBlocked()) status = RackStatus.NEEDS_WATER;
			else if (result.thermalFactor() <= 0) status = RackStatus.OVERHEATED;
			else if (bandwidth <= 0) status = RackStatus.NO_NETWORK;
			else if (rack.bootProgress() < 1) status = RackStatus.BOOTING;
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
		// Creative racks mine at their set rate with no requirements.
		int creativeRacks = 0;
		for (MachineBlockEntity rack : machines) {
			if (!rack.blockId().equals("creative_rack")) continue;
			creativeRacks++;
			double rate = rack.creativeValue(CreativeSettings.MINING_RATE);
			rack.setMining(rate > 0 ? RackStatus.MINING : RackStatus.EMPTY, rate);
			if (rate <= 0) continue;
			facilityRate += rate;
			miningRacks++;
			facility.addCredits(rack.accrueCredits(rate, dt));
		}
		facility.setMiningStats(facilityRate, miningRacks, rackSteps.size() + creativeRacks);
		facility.addAvailabilitySample(rackSteps.isEmpty() ? 1 : rackSteps.values().stream()
				.mapToDouble(step -> step.load() > 0 && step.thermalFactor() >= 0.6 ? 1 : 0).average().orElse(1));
	}

	private static List<BlockPos> airflowCells(ServerWorld world, BlockPos target, Direction facing, ThermalGrid heat) {
		if (isAirCell(world, target)) {
			track(world, heat, target);
			return List.of(target);
		}
		List<BlockPos> fallback = new ArrayList<>();
		for (Direction direction : Direction.values()) {
			if (direction == facing || direction == facing.getOpposite()) continue;
			BlockPos candidate = target.offset(direction);
			if (isAirCell(world, candidate)) {
				track(world, heat, candidate);
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
					config.faceConductanceKwPerK, config.upwardMultiplier, config.leakKwPerK, config.outdoorLeakKwPerK,
					config.settleEpsilonK, config.maxActiveCells);
		});
	}

	public static double temperatureAt(ServerWorld world, BlockPos pos) {
		return thermalGrid(world).temperatureCelsius(intake(pos));
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
		String alert = "Rackcraft event: " + startEvent(world, event);
		if (facility.activeEvent().equals("none")) return;
		for (ServerPlayerEntity player : world.getPlayers()) {
			// Burned-out hardware is worth knowing about wherever you are: it says where.
			if (event.equals("hardware_failure")) {
				player.sendMessage(Text.literal(alert).formatted(net.minecraft.util.Formatting.GOLD), false);
				continue;
			}
			boolean nearby = machines.stream().filter(machine -> machine.blockId().equals("facility_controller"))
					.anyMatch(controller -> controller.getPos().getSquaredDistance(player.getBlockPos()) <= 4096);
			if (nearby) player.sendMessage(Text.literal(alert), true);
		}
	}

	/** How long each event stays active. One-off events (cuts, failures, surges) show for 30 seconds. */
	public static long eventDuration(FacilityManager facility, String event) {
		return switch (event) {
			case "utility_outage" -> 1800 + facility.nextRandomInt(1801);
			case "cooling_failure" -> 2400;
			case "heat_wave" -> 6000;
			default -> 600;
		};
	}

	/**
	 * Starts an event and does what it does, returning a description for the alert.
	 * <ul>
	 *   <li>utility_outage: Utility Intakes supply nothing until it ends.</li>
	 *   <li>cooling_failure: every heat sink, CRAC and fan stops until it ends.</li>
	 *   <li>heat_wave: racks breathe 4 C hotter air, and dry coolers and towers lose 30% for five minutes.</li>
	 *   <li>hardware_failure: one module per 64 racks (one to three) burns out into a Failed Module. Tier 3 and 4
	 *       hardware (Quantum Cores, Wafer-Scale Engines and up) is spared. Predictive Maintenance research catches them in time.</li>
	 *   <li>cable_cut: a random power or fiber cable is cut.</li>
	 *   <li>surge: every rack on a power network without a battery reboots from cold.</li>
	 * </ul>
	 */
	public static String startEvent(ServerWorld world, String event) {
		FacilityManager facility = FacilityManager.get(world);
		facility.triggerEvent(event, eventDuration(facility, event));
		List<MachineBlockEntity> machines = machines(world);
		List<MachineBlockEntity> racks = machines.stream()
				.filter(machine -> Racks.isRack(machine) && !machine.modules().isEmpty()).toList();
		String detail = switch (event) {
			case "cable_cut" -> {
				BlockPos cutAt = cutRandomCable(world, networksFor(world));
				if (cutAt == null) {
					// Nothing worth cutting: no event at all, rather than one that says a cable was cut when none was.
					facility.triggerEvent("none", 0);
					yield "cable cut: no cable feeds a rack, so nothing happened";
				}
				yield "cable cut at " + cutAt.getX() + " " + cutAt.getY() + " " + cutAt.getZ();
			}
			case "hardware_failure" -> "hardware failure: " + failHardware(world, facility, racks);
			case "surge" -> {
				int rebooted = surge(world, machines, racks);
				yield "power surge: " + rebooted + (rebooted == 1 ? " rack" : " racks") + " rebooted (batteries on a power network protect it)";
			}
			case "heat_wave" -> "heat wave: intakes 4 C hotter, dry coolers and towers lose 30%";
			default -> event.replace('_', ' ');
		};
		facility.setEventDetail(detail);
		return detail;
	}

	private static String failHardware(ServerWorld world, FacilityManager facility, List<MachineBlockEntity> racks) {
		if (racks.isEmpty()) return "nothing to break";
		int failures = Math.max(1, Math.min(3, racks.size() / 64));
		boolean caught = ResearchLab.effects(world).safeHardware();
		List<String> lost = new ArrayList<>();
		for (int attempt = 0; attempt < failures * 16 && lost.size() < failures; attempt++) {
			MachineBlockEntity rack = racks.get(facility.nextRandomInt(racks.size()));
			List<Integer> slots = new ArrayList<>();
			for (int slot = 0; slot < Racks.bays(rack) && slot < rack.size(); slot++) {
				ServerModel.Module module = Racks.module(rack.getStack(slot));
				if (module != null && !module.hardened()) {
					slots.add(slot);
				}
			}
			if (slots.isEmpty()) continue;
			int slot = slots.get(facility.nextRandomInt(slots.size()));
			String name = rack.getStack(slot).getName().getString();
			if (!caught) {
				// The dead module remembers what it was, so a Drone Dock can fetch the same kind to replace it.
				ItemStack dead = new ItemStack(dev.rackcraft.RcItems.ITEMS.get("failed_module"));
				dead.getOrCreateNbt().putString(DroneDocks.FAILED_KEY, net.minecraft.registry.Registries.ITEM.getId(rack.getStack(slot).getItem()).toString());
				rack.setStack(slot, dead);
				rack.markDirty();
			}
			lost.add(name + " at " + rack.getPos().toShortString());
		}
		if (lost.isEmpty()) return "nothing failed";
		return (caught ? "Predictive Maintenance caught a failing " : "burned out: ") + String.join(", ", lost);
	}

	/** Reboots every rack whose power network has no battery to smooth the surge. Returns how many. */
	private static int surge(ServerWorld world, List<MachineBlockEntity> machines, List<MachineBlockEntity> racks) {
		NetworkManager networks = NetworkManager.get(world);
		Set<BlockPos> batteries = new HashSet<>();
		for (MachineBlockEntity machine : machines) if (machine.blockId().equals("battery_bank")) batteries.add(machine.getPos());
		int rebooted = 0;
		for (MachineBlockEntity rack : racks) {
			Set<BlockPos> network = networks.component(rack.getPos(), NetKind.POWER);
			if (network.stream().anyMatch(batteries::contains)) continue;
			rack.setBootProgress(0);
			rebooted++;
		}
		return rebooted;
	}

	private static NetworkManager networksFor(ServerWorld world) {
		return NetworkManager.get(world);
	}

	/**
	 * Cuts a random power or fiber cable on a network that carries a server rack, so the cut always hurts something
	 * (not a forgotten line in a ruin). Returns where, or null if there is no such cable.
	 */
	private static BlockPos cutRandomCable(ServerWorld world, NetworkManager networks) {
		List<BlockPos> cables = new ArrayList<>();
		for (NetKind kind : List.of(NetKind.POWER, NetKind.DATA)) {
			for (Set<BlockPos> network : networks.components(kind)) {
				boolean feedsRack = network.stream().anyMatch(pos -> world.getBlockEntity(pos) instanceof MachineBlockEntity machine
						&& Racks.isRack(machine));
				if (!feedsRack) continue;
				for (BlockPos pos : network) {
					BlockState state = world.getBlockState(pos);
					if (state.getBlock() instanceof CableBlock && !state.get(CableBlock.CUT)) cables.add(pos);
				}
			}
		}
		cables.sort(java.util.Comparator.comparingLong(BlockPos::asLong));
		if (cables.isEmpty()) return null;
		BlockPos target = cables.get(FacilityManager.get(world).nextRandomInt(cables.size()));
		CableBlock.setCut(world, target, true);
		return target;
	}

	private static ThermalGrid.CellPos intake(BlockPos pos) {
		return new ThermalGrid.CellPos(pos.getX(), pos.getY(), pos.getZ());
	}
}