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
				case "uranium_mill", "gas_centrifuge", "fuel_fabricator", "cask_sealer" -> {
					int status = machine.processStatus();
					if (status == NuclearProcessing.Status.NOT_FORMED.ordinal()) faults.add(new Fault(pos, 1, "Not a whole cube"));
					else if (status == NuclearProcessing.Status.NO_POWER.ordinal()) faults.add(new Fault(pos, 2, "Multiblock: no power"));
					else if (status == NuclearProcessing.Status.OUTPUT_FULL.ordinal()) faults.add(new Fault(pos, 1, "Multiblock: output full"));
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
