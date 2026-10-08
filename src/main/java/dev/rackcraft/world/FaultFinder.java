package dev.rackcraft.world;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.RackBlock;
import dev.rackcraft.block.RackStatus;
import dev.rackcraft.sim.NetKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * The fault finder: while a player holds a Multimeter, every machine with a problem (and every cut cable)
 * within {@link #RANGE} blocks is sent to them once a second, and their client outlines each one through walls
 * and points to the nearest. Red stops things, amber slows them down.
 */
public final class FaultFinder {
	public static final Identifier FAULTS = Rackcraft.id("faults");
	public static final int RANGE = 128;
	public static final int MAX_FAULTS = 512;

	/** A problem at a position: severity 2 (stopped) or 1 (slowed, or about to be), and what's wrong. */
	public record Fault(BlockPos pos, int severity, String label) {}

	private FaultFinder() {}

	public static void send(ServerWorld world, List<MachineBlockEntity> machines) {
		if (world.getTime() % 20 >= Math.max(1, dev.rackcraft.RackcraftConfig.values.sim.stepTicks)) return;
		List<ServerPlayerEntity> players = world.getPlayers().stream().filter(FaultFinder::holdsMeter).toList();
		if (players.isEmpty()) return;
		List<Fault> faults = faults(world, machines);
		for (ServerPlayerEntity player : players) {
			List<Fault> near = faults.stream().filter(fault -> fault.pos().isWithinDistance(player.getPos(), RANGE))
					.sorted(Comparator.comparingDouble(fault -> fault.pos().getSquaredDistance(player.getPos())))
					.limit(MAX_FAULTS).toList();
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeVarInt(near.size());
			for (Fault fault : near) {
				buf.writeLong(fault.pos().asLong());
				buf.writeByte(fault.severity());
				buf.writeString(fault.label(), 64);
			}
			ServerPlayNetworking.send(player, FAULTS, buf);
		}
	}

	public static boolean holdsMeter(ServerPlayerEntity player) {
		return player.getMainHandStack().isOf(RcItems.ITEMS.get("multimeter")) || player.getOffHandStack().isOf(RcItems.ITEMS.get("multimeter"));
	}

	/** Everything wrong in this world's loaded machines, one entry per machine (one per multiblock). */
	public static List<Fault> faults(ServerWorld world, List<MachineBlockEntity> machines) {
		List<Fault> faults = new ArrayList<>();
		java.util.Set<String> reported = new java.util.HashSet<>();
		for (MachineBlockEntity machine : machines) {
			BlockPos pos = machine.getPos();
			// Any cube machine (reactors and batteries too) in a cube bigger than the research allows.
			if (machine.lockedCube() > 0) {
				faults.add(new Fault(pos, 1, notWhole(machine)));
				continue;
			}
			switch (machine.blockId()) {
				case "server_rack" -> {
					RackBlock.Health health = RackBlock.Health.of(machine.rackStatus());
					if (health != RackBlock.Health.OK) {
						faults.add(new Fault(pos, health == RackBlock.Health.FAULT ? 2 : 1, "Rack: " + describe(machine.rackStatus())));
					}
				}
				case "storage_array", "tape_library" -> {
					if (machine.driveCount() > 0 && !machine.storageOnline()) faults.add(new Fault(pos, 2, "Storage offline"));
				}
				case "freshwater_pump" -> {
					if (machine.pumpStatus() != FreshwaterCooling.PumpStatus.PUMPING.ordinal()) {
						faults.add(new Fault(pos, 2, "Pump: " + FreshwaterCooling.PumpStatus.values()[
								Math.max(0, Math.min(4, machine.pumpStatus()))].name().toLowerCase(Locale.ROOT).replace('_', ' ')));
					}
				}
				case "cooling_tower", "dry_cooler", "chiller", "water_heat_exchanger" -> {
					if (machine.loopHeatKw() > machine.loopCapacityKw() + 0.5) faults.add(new Fault(pos, 1, "Coolant loop overloaded"));
				}
				case "diesel_generator" -> {
					if (machine.fuelBurnTicks() <= 0 && machine.getStack(0).isEmpty()) faults.add(new Fault(pos, 1, "Generator out of fuel"));
				}
				case "modular_reactor" -> {
					int status = machine.processStatus();
					String key = "reactor " + machine.reactorArraySize() + " " + machine.arrayFuelTicks() + " " + machine.arrayFuelCells();
					if (status == ReactorArrays.ReactorStatus.WASTE_FULL.ordinal() && reported.add(key)) {
						faults.add(new Fault(pos, 2, "Reactor: waste slots full"));
					} else if (status == ReactorArrays.ReactorStatus.NO_FUEL.ordinal() && reported.add(key)) {
						faults.add(new Fault(pos, 1, "Reactor: out of Fuel Cells"));
					}
				}
				case "uranium_mill", "gas_centrifuge", "fuel_fabricator", "cask_sealer", "wafer_fab", "silicon_foundry", "ewaste_recycler",
						"electrolyser" -> {
					int status = machine.processStatus();
					if (status == NuclearProcessing.Status.NOT_FORMED.ordinal()) faults.add(new Fault(pos, 1, notWhole(machine)));
					else if (status == NuclearProcessing.Status.NO_POWER.ordinal()) faults.add(new Fault(pos, 2, "Multiblock: no power"));
					else if (status == NuclearProcessing.Status.OUTPUT_FULL.ordinal()) faults.add(new Fault(pos, 1, "Multiblock: output full"));
					else if (status == NuclearProcessing.Status.LOCKED.ordinal()) faults.add(new Fault(pos, 1, "Wafer Fab: needs EUV Lithography research"));
					else if (status == NuclearProcessing.Status.LOW_POWER.ordinal()) faults.add(new Fault(pos, 1, "Multiblock: short of power, running slowly"));
					else if (status == NuclearProcessing.Status.NO_WATER.ordinal()) faults.add(new Fault(pos, 2, "Electrolyser: not touching water"));
				}
				case "welding_arm", "riveting_arm", "assembly_arm" -> {
					int status = machine.processStatus();
					if (status == AssemblyLine.Status.NO_BELT.ordinal()) faults.add(new Fault(pos, 1, "Robot: not facing a Conveyor Belt"));
					else if (status == AssemblyLine.Status.NO_PARTS.ordinal()) faults.add(new Fault(pos, 2, "Robot: no parts loaded"));
					else if (status == AssemblyLine.Status.NO_POWER.ordinal()) faults.add(new Fault(pos, 2, "Robot: no power"));
					else if (status == AssemblyLine.Status.LOW_POWER.ordinal()) faults.add(new Fault(pos, 1, "Robot: short of power, working slowly"));
				}
				case "wind_nacelle" -> {
					int status = machine.processStatus();
					if (status == Renewables.TowerStatus.TOO_SHORT.ordinal()) faults.add(new Fault(pos, 1, "Wind Tower: too few Tower Sections"));
					else if (status == Renewables.TowerStatus.BLOCKED.ordinal()) faults.add(new Fault(pos, 1, "Wind Tower: something is in the blades' way"));
					else if (machine.wear() >= 0.5) faults.add(new Fault(pos, 1, worn(machine)));
				}
				case "solar_array", "solar_array_tracking" -> {
					if (Renewables.wearsOut(machine) && machine.wear() >= 0.5) faults.add(new Fault(pos, 1, worn(machine)));
				}
				case "launch_control" -> {
					int status = machine.processStatus();
					if (status == LaunchPads.Status.NO_PAD.ordinal()) faults.add(new Fault(pos, 1, "Launch Control: not beside a 3x3 Launch Pad"));
					else if (status == LaunchPads.Status.NO_SKY.ordinal()) faults.add(new Fault(pos, 1, "Launch Control: the pad can't see the sky"));
					else if (status == LaunchPads.Status.NO_POWER.ordinal()) faults.add(new Fault(pos, 2, "Launch Control: no power"));
				}
				case "drone_dock" -> {
					int status = machine.processStatus();
					if (status == DroneDocks.Status.NO_POWER.ordinal()) faults.add(new Fault(pos, 2, "Drone Dock: no power"));
					else if (status == DroneDocks.Status.NO_FUEL.ordinal()) faults.add(new Fault(pos, 2, "Drone Dock: out of hydrogen"));
					else if (status == DroneDocks.Status.NO_DRONES.ordinal()) faults.add(new Fault(pos, 1, "Drone Dock: every drone is out"));
					else if (status == DroneDocks.Status.NO_SPARES.ordinal()) faults.add(new Fault(pos, 1, "Drone Dock: needs spares (in it or in its storage)"));
				}
				case "site_planner" -> {
					int status = machine.processStatus();
					if (status == SitePlanner.Status.NO_POWER.ordinal()) faults.add(new Fault(pos, 2, "Site Planner: no power"));
					else if (status == SitePlanner.Status.NO_FUEL.ordinal()) faults.add(new Fault(pos, 2, "Site Planner: out of hydrogen"));
					else if (status == SitePlanner.Status.NO_DRONES.ordinal()) faults.add(new Fault(pos, 1, "Site Planner: needs Construction Drones"));
					else if (status == SitePlanner.Status.NO_TERRAFORMERS.ordinal()) faults.add(new Fault(pos, 1, "Site Planner: needs Terraforming Drones"));
					else if (status == SitePlanner.Status.NEEDS_MATERIALS.ordinal()) faults.add(new Fault(pos, 1, "Site Planner: waiting for materials"));
					else if (status == SitePlanner.Status.BLOCKED.ordinal()) faults.add(new Fault(pos, 1, "Site Planner: something on the site is in the way"));
				}
				case "desalination_plant" -> {
					int status = machine.pumpStatus();
					if (machine.reactorArraySize() < 2) faults.add(new Fault(pos, 1, notWhole(machine)));
					else if (status == FreshwaterCooling.PumpStatus.NO_POWER.ordinal()) faults.add(new Fault(pos, 2, "Desalination: no power"));
					else if (status == FreshwaterCooling.PumpStatus.NO_WATER.ordinal()) faults.add(new Fault(pos, 2, "Desalination: not touching water"));
				}
				case "heat_recovery_plant" -> {
					if (machine.reactorArraySize() < 2) faults.add(new Fault(pos, 1, notWhole(machine)));
					else if (machine.coolingDetail() == 0) faults.add(new Fault(pos, 1, "Heat recovery: no villagers nearby"));
				}
				case "grid_substation" -> {
					if (machine.reactorArraySize() < 2) faults.add(new Fault(pos, 1, notWhole(machine)));
				}
				default -> {}
			}
		}
		NetworkManager networks = NetworkManager.get(world);
		for (NetKind kind : NetKind.values()) {
			for (BlockPos cut : networks.cutCables(kind)) {
				faults.add(new Fault(cut, 2, "Cut " + kind.name().toLowerCase(Locale.ROOT) + " cable"));
			}
		}
		return faults;
	}

	private static String worn(MachineBlockEntity machine) {
		return String.format(Locale.ROOT, "Needs servicing: %.0f%% less output (Drone Dock)",
				(1 - Renewables.wearFactor(machine)) * 100);
	}

	/** A lone cube machine: not part of a whole cube, or part of one bigger than the research allows. */
	private static String notWhole(MachineBlockEntity machine) {
		int locked = machine.lockedCube();
		return locked > 0 ? "Cube too big: " + locked + "x" + locked + "x" + locked + " needs " + ReactorArrays.researchFor(locked) : "Not a whole cube";
	}

	public static String describe(RackStatus status) {
		return switch (status) {
			case TRIPPED -> "breaker tripped";
			case NO_POWER -> "no power";
			case NEEDS_CDU -> "needs a CDU";
			case NEEDS_WATER -> "needs liquid cooling";
			case OVERHEATED -> "overheated";
			case NO_NETWORK -> "offline";
			case THROTTLED -> "throttled (hot)";
			case NETWORK_LIMITED -> "bandwidth-limited";
			default -> status.name().toLowerCase(Locale.ROOT);
		};
	}
}
