package dev.rackcraft.world;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.Racks;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.sim.ServerModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Compute Pods. A solid block of High-Density Racks or better (at least four, up to 64, no more than 2 x 4 x 8) with a
 * powered Pod Port touching it is one machine: its racks share a breaker, and the Port's Photonic Interconnects knit
 * them into a fabric that lends more AI compute.
 *
 * <ul>
 *   <li><b>Interconnect:</b> one Photonic Interconnect in the Port for every four racks, or the fabric is off. Then AI
 *       compute (contracts, training, research) is 10% higher from 4 racks, 20% from 8 and 30% from 16.</li>
 *   <li><b>One breaker:</b> if any rack in the pod overheats, the whole pod trips and lends nothing until every rack
 *       has cooled below {@link #RESET_C}. It resets by itself.</li>
 * </ul>
 * The racks stay racks: the scheduler, cooling and power see the same machines as before, since touching racks already
 * share one power, coolant and fiber network.
 */
public final class ComputePods {
	public static final String RESEARCH = "compute_pods";
	public static final int MIN_RACKS = 4;
	public static final int MAX_RACKS = 64;
	/** A tripped pod resets once every rack's intake is under this. */
	public static final double RESET_C = 30;
	/** Port readings (MachineBlockEntity.siteReading). */
	public static final int R_STATE = 0;
	public static final int R_RACKS = 1;
	public static final int R_BAYS = 2;
	public static final int R_BAYS_USED = 3;
	public static final int R_BONUS = 4;
	public static final int R_NEED = 5;
	public static final int R_HAVE = 6;

	/** What a Port is doing: the screen words these. */
	public enum State { LOCKED, NO_POD, FABRIC_OFF, ACTIVE, TRIPPED }

	/** A fused pod: its racks, its Port, and what its fabric does. */
	public record Pod(List<MachineBlockEntity> racks, MachineBlockEntity port, int interconnects, boolean fabric, double aiBonus) {
		public int needed() { return interconnectsFor(racks.size()); }
	}

	private ComputePods() {}

	public static int interconnectsFor(int racks) { return (racks + 3) / 4; }

	/** The AI compute multiplier a pod of this size gets from its fabric. */
	public static double bonusFor(int racks) {
		return racks >= 16 ? 1.3 : racks >= 8 ? 1.2 : racks >= MIN_RACKS ? 1.1 : 1;
	}

	public static int minTier() { return RackcraftConfig.values.building.podMinTier; }

	/** Every pod in the world, each with the Port that makes it one. A pod with no Port (or an unpowered one) isn't active, and isn't listed. */
	public static List<Pod> scan(ServerWorld world, List<MachineBlockEntity> machines) {
		if (!ResearchLab.get(world).done(RESEARCH)) return List.of();
		Map<BlockPos, MachineBlockEntity> racks = new HashMap<>();
		Map<BlockPos, MachineBlockEntity> ports = new HashMap<>();
		for (MachineBlockEntity machine : machines) {
			if (Racks.isRack(machine) && Racks.tier(machine).ordinal() >= minTier()) racks.put(machine.getPos(), machine);
			else if (machine.blockId().equals("pod_port")) ports.put(machine.getPos(), machine);
		}
		if (ports.isEmpty() || racks.size() < MIN_RACKS) return List.of();
		List<Pod> pods = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		Set<BlockPos> usedPorts = new HashSet<>();
		List<BlockPos> order = new ArrayList<>(racks.keySet());
		order.sort(Comparator.comparingLong(BlockPos::asLong));
		for (BlockPos start : order) {
			if (!seen.add(start)) continue;
			List<BlockPos> group = new ArrayList<>();
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(start);
			while (!queue.isEmpty()) {
				BlockPos pos = queue.poll();
				group.add(pos);
				for (Direction direction : Direction.values()) {
					BlockPos next = pos.offset(direction);
					if (racks.containsKey(next) && seen.add(next)) queue.add(next);
				}
			}
			if (!solid(group)) continue;
			BlockPos portPos = null;
			for (BlockPos pos : group) {
				for (Direction direction : Direction.values()) {
					BlockPos next = pos.offset(direction);
					if (ports.containsKey(next) && !usedPorts.contains(next) && (portPos == null || next.asLong() < portPos.asLong())) portPos = next;
				}
			}
			if (portPos == null) continue;
			MachineBlockEntity port = ports.get(portPos);
			if (port.powerSatisfaction() < 0.5) continue;
			usedPorts.add(portPos);
			List<MachineBlockEntity> members = group.stream().sorted(Comparator.comparingLong(BlockPos::asLong)).map(racks::get).toList();
			int have = port.getStack(0).isOf(RcItems.ITEMS.get("photonic_chip")) ? port.getStack(0).getCount() : 0;
			boolean fabric = have >= interconnectsFor(members.size());
			pods.add(new Pod(members, port, have, fabric, fabric ? bonusFor(members.size()) : 1));
		}
		return pods;
	}

	/** A solid cuboid: the racks fill their bounding box exactly, within the size limits. */
	private static boolean solid(List<BlockPos> group) {
		if (group.size() < MIN_RACKS || group.size() > Math.min(MAX_RACKS, RackcraftConfig.values.building.podMaxRacks)) return false;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos pos : group) {
			minX = Math.min(minX, pos.getX());
			maxX = Math.max(maxX, pos.getX());
			minY = Math.min(minY, pos.getY());
			maxY = Math.max(maxY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		int[] dims = {maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1};
		java.util.Arrays.sort(dims);
		return dims[0] * dims[1] * dims[2] == group.size() && dims[0] <= 2 && dims[1] <= 4 && dims[2] <= 8;
	}

	/**
	 * Applies the pods to this step: the fabric bonus on each rack, and each Port's breaker (tripping the pod when any rack
	 * has overheated, resetting it once they have all cooled). Returns the racks held tripped by a pod breaker.
	 */
	public static Set<MachineBlockEntity> apply(List<MachineBlockEntity> machines, List<Pod> pods) {
		for (MachineBlockEntity machine : machines) if (Racks.isRack(machine)) machine.setPodBonus(1);
		Set<MachineBlockEntity> held = new HashSet<>();
		for (Pod pod : pods) {
			boolean overheated = false;
			boolean cool = true;
			for (MachineBlockEntity rack : pod.racks()) {
				boolean hot = !rack.modules().isEmpty() && rack.powerSatisfaction() >= 0.5 && rack.thermalFactor() <= 0 && rack.inletCelsius() > 0;
				overheated |= hot;
				if (rack.inletCelsius() >= RESET_C) cool = false;
				rack.setPodBonus(pod.aiBonus());
			}
			MachineBlockEntity port = pod.port();
			if (!port.isTripped() && overheated) port.setTripped(true);
			else if (port.isTripped() && cool) port.setTripped(false);
			if (port.isTripped()) held.addAll(pod.racks());
			// What the Port's screen shows.
			State state = port.isTripped() ? State.TRIPPED : pod.fabric() ? State.ACTIVE : State.FABRIC_OFF;
			int bays = 0;
			int used = 0;
			for (MachineBlockEntity rack : pod.racks()) {
				bays += Racks.bays(rack);
				used += rack.modules().size();
			}
			readings(port, state.ordinal(), pod.racks().size(), bays, used, (int) Math.round((pod.aiBonus() - 1) * 100), pod.needed(), pod.interconnects());
		}
		return held;
	}

	/** Sets a Port's readings. {@code state} is a {@link State} ordinal. */
	public static void readings(MachineBlockEntity port, int state, int racks, int bays, int used, int bonus, int need, int have) {
		port.setSiteReading(R_STATE, state);
		port.setSiteReading(R_RACKS, racks);
		port.setSiteReading(R_BAYS, bays);
		port.setSiteReading(R_BAYS_USED, used);
		port.setSiteReading(R_BONUS, bonus);
		port.setSiteReading(R_NEED, need);
		port.setSiteReading(R_HAVE, have);
	}

	/** A Port that belongs to no pod says why: it needs the research, or a pod to touch. */
	public static void idle(ServerWorld world, List<MachineBlockEntity> machines, List<Pod> pods) {
		Set<MachineBlockEntity> active = new HashSet<>();
		for (Pod pod : pods) active.add(pod.port());
		boolean researched = ResearchLab.get(world).done(RESEARCH);
		for (MachineBlockEntity machine : machines) {
			if (!machine.blockId().equals("pod_port") || active.contains(machine)) continue;
			// A tripped breaker on a Port nobody touches any more is cleared.
			if (machine.isTripped()) machine.setTripped(false);
			readings(machine, researched ? State.NO_POD.ordinal() : State.LOCKED.ordinal(), 0, 0, 0, 0, 0,
					machine.getStack(0).isOf(RcItems.ITEMS.get("photonic_chip")) ? machine.getStack(0).getCount() : 0);
		}
	}

	/** Fills (or empties) every rack of a pod from the Port's storage network and then the player. Returns how many bays or modules moved. */
	public static int fillAll(ServerWorld world, Pod pod, net.minecraft.entity.player.PlayerEntity player, boolean empty) {
		var network = dev.rackcraft.storage.StorageService.networkAt(world, pod.port().getPos());
		int moved = 0;
		for (MachineBlockEntity rack : pod.racks()) {
			moved += empty ? Racks.empty(rack, network, player) : Racks.fill(rack, network, player, 0);
		}
		return moved;
	}

	/** The pod a Port belongs to right now, or null. */
	public static Pod podOf(ServerWorld world, MachineBlockEntity port) {
		for (Pod pod : scan(world, SimTicker.machines(world))) if (pod.port() == port) return pod;
		return null;
	}

	public static boolean isBay(ServerModel.Tier tier) { return tier != null; }
}
