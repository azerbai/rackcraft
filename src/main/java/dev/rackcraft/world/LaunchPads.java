package dev.rackcraft.world;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.RcBlocks;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.entity.RocketEntity;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapIcon;
import net.minecraft.item.map.MapState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.gen.structure.Structure;

/**
 * The launch programme. A Launch Control beside a 3x3 Launch Pad fills its tank from Hydrogen Canisters, and on
 * Launch spends the stages its payload needs and {@link #CANISTERS_PER_STAGE} canisters per stage, counts down for
 * {@link #COUNTDOWN_TICKS}, and sends the rocket up. {@link #FLIGHT_TICKS} later the payload is in orbit
 * ({@link OrbitState}) — or, one launch in {@link #FAILURE_ODDS}, it isn't, and the payload comes back.
 *
 * Slots: 0 to 2 Rocket Stages, 3 the payload (and a Survey Satellite's map afterwards), 4 Hydrogen Canisters in.
 */
public final class LaunchPads {
	public static final int CANISTERS_PER_STAGE = 128;
	public static final int TANK_CAPACITY = 4096;
	public static final int COUNTDOWN_TICKS = 200;
	public static final int FLIGHT_TICKS = 400;
	public static final int FAILURE_ODDS = 50;
	public static final double CONTROL_KW = 5;
	public static final int PAYLOAD_SLOT = 3;
	public static final int FUEL_SLOT = 4;
	/** The self-test sets this to make launches always succeed (0) or always fail (1). */
	public static double failureChance = 1.0 / FAILURE_ODDS;
	private static final TagKey<Structure> CAMPUS = TagKey.of(RegistryKeys.STRUCTURE, Rackcraft.id("campus"));
	private static final TagKey<Structure> DATA_CENTERS = TagKey.of(RegistryKeys.STRUCTURE, Rackcraft.id("data_centers"));

	/** READY to launch, or what is stopping it. */
	public enum Status { READY, NO_PAD, NO_SKY, NO_PAYLOAD, NO_STAGES, NO_FUEL, NO_POWER, COUNTDOWN, IN_FLIGHT }

	private LaunchPads() {}

	/** Stages a payload needs, or 0 if this isn't a payload. */
	public static int stagesFor(ItemStack payload) {
		if (payload.isEmpty()) return 0;
		return switch (Registries.ITEM.getId(payload.getItem()).getPath()) {
			case "comms_satellite", "survey_satellite" -> 1;
			case "orbital_datacenter" -> 2;
			case "dyson_mirror" -> 3;
			default -> 0;
		};
	}

	public static boolean accepts(int slot, ItemStack stack) {
		if (slot < PAYLOAD_SLOT) return stack.isOf(RcItems.ITEMS.get("rocket_stage"));
		if (slot == PAYLOAD_SLOT) return stagesFor(stack) > 0;
		return slot == FUEL_SLOT && stack.isOf(RcItems.ITEMS.get("hydrogen_canister"));
	}

	public static int stagesLoaded(MachineBlockEntity control) {
		int stages = 0;
		for (int slot = 0; slot < PAYLOAD_SLOT; slot++) {
			if (control.getStack(slot).isOf(RcItems.ITEMS.get("rocket_stage"))) stages += control.getStack(slot).getCount();
		}
		return stages;
	}

	/** The centre of the 3x3 Launch Pad this control touches, or null if it touches no whole one. */
	public static BlockPos padCentre(ServerWorld world, BlockPos control) {
		for (Direction side : Direction.Type.HORIZONTAL) {
			BlockPos start = control.offset(side);
			if (!isPad(world, start)) continue;
			Set<BlockPos> pad = new HashSet<>();
			ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(start));
			while (!queue.isEmpty() && pad.size() <= 9) {
				BlockPos pos = queue.poll();
				if (!pad.add(pos)) continue;
				for (Direction direction : Direction.Type.HORIZONTAL) {
					BlockPos next = pos.offset(direction);
					if (!pad.contains(next) && isPad(world, next)) queue.add(next);
				}
			}
			if (pad.size() != 9) continue;
			int minX = pad.stream().mapToInt(BlockPos::getX).min().orElse(0);
			int maxX = pad.stream().mapToInt(BlockPos::getX).max().orElse(0);
			int minZ = pad.stream().mapToInt(BlockPos::getZ).min().orElse(0);
			int maxZ = pad.stream().mapToInt(BlockPos::getZ).max().orElse(0);
			if (maxX - minX == 2 && maxZ - minZ == 2) return new BlockPos(minX + 1, start.getY(), minZ + 1);
		}
		return null;
	}

	private static boolean isPad(ServerWorld world, BlockPos pos) {
		return world.getBlockState(pos).isOf(RcBlocks.get("launch_pad"));
	}

	public static Status status(ServerWorld world, MachineBlockEntity control, double power) {
		if (control.launchCountdown() > 0) return Status.COUNTDOWN;
		if (control.launchFlight() > 0) return Status.IN_FLIGHT;
		BlockPos centre = padCentre(world, control.getPos());
		if (centre == null) return Status.NO_PAD;
		if (!world.isSkyVisible(centre.up())) return Status.NO_SKY;
		int needed = stagesFor(control.getStack(PAYLOAD_SLOT));
		if (needed == 0) return Status.NO_PAYLOAD;
		if (stagesLoaded(control) < needed) return Status.NO_STAGES;
		if (control.launchTank() < needed * CANISTERS_PER_STAGE) return Status.NO_FUEL;
		if (power < 0.5) return Status.NO_POWER;
		return Status.READY;
	}

	/** One simulation step for every Launch Control: fuel, countdown, flight, and what it says it's doing. */
	public static void step(ServerWorld world, List<MachineBlockEntity> machines, Map<MachineBlockEntity, Double> satisfaction) {
		int steps = Math.max(1, dev.rackcraft.RackcraftConfig.values.sim.stepTicks);
		for (MachineBlockEntity control : machines) {
			if (!control.blockId().equals("launch_control")) continue;
			ItemStack fuel = control.getStack(FUEL_SLOT);
			if (fuel.isOf(RcItems.ITEMS.get("hydrogen_canister"))) {
				int moved = Math.min(fuel.getCount(), TANK_CAPACITY - control.launchTank());
				if (moved > 0) {
					fuel.decrement(moved);
					control.setLaunchTank(control.launchTank() + moved);
				}
			}
			if (control.launchCountdown() > 0) {
				control.setLaunchCountdown(control.launchCountdown() - steps);
				if (control.launchCountdown() <= 0) control.setLaunchFlight(FLIGHT_TICKS);
			} else if (control.launchFlight() > 0) {
				control.setLaunchFlight(control.launchFlight() - steps);
				if (control.launchFlight() <= 0) arrive(world, control);
			}
			Status status = status(world, control, satisfaction.getOrDefault(control, 0.0));
			control.setProcess(status.ordinal(), status == Status.COUNTDOWN || status == Status.IN_FLIGHT);
		}
	}

	/** Pressing Launch: spends the stages, payload and fuel and starts the countdown. Returns a message for the player. */
	public static String launch(ServerWorld world, MachineBlockEntity control) {
		Status status = status(world, control, control.powerSatisfaction());
		if (status != Status.READY) return "Can't launch: " + describe(status);
		ItemStack payload = control.getStack(PAYLOAD_SLOT).split(1);
		int stages = stagesFor(payload);
		int left = stages;
		for (int slot = 0; slot < PAYLOAD_SLOT && left > 0; slot++) {
			ItemStack stack = control.getStack(slot);
			if (!stack.isOf(RcItems.ITEMS.get("rocket_stage"))) continue;
			int used = Math.min(left, stack.getCount());
			stack.decrement(used);
			left -= used;
		}
		control.setLaunchTank(control.launchTank() - stages * CANISTERS_PER_STAGE);
		boolean fails = world.random.nextDouble() < failureChance;
		control.startLaunch(Registries.ITEM.getId(payload.getItem()).getPath(), stages, fails, COUNTDOWN_TICKS);
		control.markDirty();
		BlockPos centre = padCentre(world, control.getPos());
		RocketEntity.launch(world, centre.up(), stages, control.launchPayload(), COUNTDOWN_TICKS, fails);
		announce(world, control.getPos(), Text.literal("Launch Control: T-10 seconds. " + new ItemStack(payload.getItem()).getName().getString()
				+ " on " + stages + (stages == 1 ? " stage" : " stages") + ". Stand clear of the pad.").formatted(Formatting.GOLD));
		return "Countdown started";
	}

	/** The flight is over: the payload is in orbit, or it came back after a rapid unscheduled disassembly. */
	private static void arrive(ServerWorld world, MachineBlockEntity control) {
		String payload = control.launchPayload();
		OrbitState orbit = OrbitState.get(world);
		if (control.launchFailed()) {
			orbit.failed();
			giveBack(world, control, new ItemStack(Registries.ITEM.get(Rackcraft.id(payload))));
			announce(world, control.getPos(), Text.literal("Launch Control: rapid unscheduled disassembly. The stages and fuel are gone; "
					+ "the payload's abort motor fired and it's back at the pad, slightly singed.").formatted(Formatting.RED));
		} else {
			orbit.add(payload);
			String result = switch (payload) {
				case "comms_satellite" -> orbit.comms() <= OrbitState.MAX_COMMS
						? "Comms Satellite " + orbit.comms() + " of " + OrbitState.MAX_COMMS + " online: parcels faster, one more lease slot"
						: "Comms Satellite in orbit, but the network is already full (" + OrbitState.MAX_COMMS + ")";
				case "orbital_datacenter" -> String.format(java.util.Locale.ROOT,
						"Orbital Data Center %d online: every rack now has +%.0f%% compute", orbit.datacenters(),
						orbit.datacenters() * OrbitState.COMPUTE_PER_MODULE * 100);
				case "dyson_mirror" -> String.format(java.util.Locale.ROOT, "Dyson Mirror %d deployed: the swarm beams %.0f MW to your Rectennas",
						orbit.mirrors(), orbit.mirrors() * OrbitState.MIRROR_KW / 1000);
				case "survey_satellite" -> survey(world, control);
				default -> "Payload in orbit";
			};
			announce(world, control.getPos(), Text.literal("Launch Control: orbit reached. " + result).formatted(Formatting.GREEN));
		}
		control.finishLaunch();
	}

	/** A Survey Satellite maps the nearest Hyperscale Campus nobody has found (or a data center), into the payload slot. */
	private static String survey(ServerWorld world, MachineBlockEntity control) {
		boolean campus = true;
		BlockPos found = world.locateStructure(CAMPUS, control.getPos(), 12, true);
		if (found == null) {
			campus = false;
			found = world.locateStructure(DATA_CENTERS, control.getPos(), 24, true);
		}
		if (found == null) return "Survey complete: nothing new within reach of the satellite";
		ItemStack map = FilledMapItem.createMap(world, found.getX(), found.getZ(), (byte) 2, true, true);
		FilledMapItem.fillExplorationMap(world, map);
		MapState.addDecorationsNbt(map, found, "+", MapIcon.Type.TARGET_X);
		map.setCustomName(Text.literal(campus ? "Survey: Hyperscale Campus" : "Survey: Abandoned Data Center"));
		giveBack(world, control, map);
		return String.format(java.util.Locale.ROOT, "Survey found %s at %d, %d; the map is in the Launch Control",
				campus ? "a Hyperscale Campus" : "an abandoned data center", found.getX(), found.getZ());
	}

	private static void giveBack(ServerWorld world, MachineBlockEntity control, ItemStack stack) {
		if (control.getStack(PAYLOAD_SLOT).isEmpty()) control.setStack(PAYLOAD_SLOT, stack);
		else ItemScatterer.spawn(world, control.getPos().getX() + 0.5, control.getPos().getY() + 1.2, control.getPos().getZ() + 0.5, stack);
	}

	private static void announce(ServerWorld world, BlockPos pos, Text message) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.getBlockPos().isWithinDistance(pos, 256)) player.sendMessage(message, false);
		}
	}

	public static String describe(Status status) {
		return switch (status) {
			case READY -> "ready";
			case NO_PAD -> "it isn't touching a whole 3x3 Launch Pad";
			case NO_SKY -> "the pad's centre can't see the sky";
			case NO_PAYLOAD -> "no payload loaded";
			case NO_STAGES -> "not enough Rocket Stages";
			case NO_FUEL -> "not enough hydrogen in the tank";
			case NO_POWER -> "no power";
			case COUNTDOWN -> "countdown under way";
			case IN_FLIGHT -> "a rocket is still in flight";
		};
	}

	/** Every Rectenna in the dimension that can see the sky, for sharing out the swarm's power. */
	public static int rectennas(ServerWorld world, List<MachineBlockEntity> machines) {
		int count = 0;
		for (MachineBlockEntity machine : machines) {
			if (machine.blockId().equals("rectenna") && world.isSkyVisible(machine.getPos().up())) count++;
		}
		return count;
	}
}
