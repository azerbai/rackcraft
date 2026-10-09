package dev.rackcraft.world;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.sim.NetKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Power Beacons. A beacon on one power network beams to the Beacon Receivers within range, which feed whatever network
 * they touch. Each step the power solver asks a beacon for what its receivers' networks want (up to its limit, plus the
 * loss), and what it actually got is offered by the receivers on the next step: a lag of one step, like the other
 * machines that trade through the solver.
 */
public final class WirelessPower {
	public static final String GATE = "wireless_power";

	private static final class State {
		/** What a beacon asks its network for this step, in kW. */
		final Map<BlockPos, Double> beaconDemand = new HashMap<>();
		/** The share of that ask the beacon's network gave it, last step. */
		final Map<BlockPos, Double> beaconSatisfaction = new HashMap<>();
		/** What a receiver may supply this step, in kW. */
		final Map<BlockPos, Double> receiverOffer = new HashMap<>();
		/** What the network around each receiver wanted last step (machines' stats are cleared at the start of a step). */
		final Map<BlockPos, Double> lastDemand = new HashMap<>();
		/** Which beacon each receiver is paired with. */
		final Map<BlockPos, BlockPos> pairing = new HashMap<>();
		int tick;
	}

	private static final Map<ServerWorld, State> STATES = new WeakHashMap<>();

	private WirelessPower() {}

	private static State state(ServerWorld world) {
		return STATES.computeIfAbsent(world, ignored -> new State());
	}

	public static double range() { return RackcraftConfig.values.building.beaconRange; }

	/** What the beacon asks of its network. */
	public static double beaconDemandKw(ServerWorld world, MachineBlockEntity beacon) {
		return state(world).beaconDemand.getOrDefault(beacon.getPos(), 0.0);
	}

	/** What a receiver can supply right now. */
	public static double receiverOfferKw(ServerWorld world, MachineBlockEntity receiver) {
		return state(world).receiverOffer.getOrDefault(receiver.getPos(), 0.0);
	}

	/** The beacon a receiver is paired with, or null. */
	public static BlockPos beaconOf(ServerWorld world, BlockPos receiver) {
		return state(world).pairing.get(receiver);
	}

	/** Forgets everything (the self-test uses it). */
	public static void reset(ServerWorld world) {
		STATES.remove(world);
	}

	/** Works out pairings, what each beacon asks for and what each receiver offers. Call at the start of a power step. */
	public static void prepare(ServerWorld world, List<MachineBlockEntity> machines) {
		State state = state(world);
		state.beaconDemand.clear();
		state.receiverOffer.clear();
		state.pairing.clear();
		if (!ResearchLab.get(world).done(GATE)) return;
		List<MachineBlockEntity> beacons = new ArrayList<>();
		List<MachineBlockEntity> receivers = new ArrayList<>();
		for (MachineBlockEntity machine : machines) {
			switch (machine.blockId()) {
				case "power_beacon" -> beacons.add(machine);
				case "beacon_receiver" -> receivers.add(machine);
				default -> {}
			}
		}
		if (beacons.isEmpty() || receivers.isEmpty()) return;
		NetworkManager networks = NetworkManager.get(world);
		double rangeSquared = range() * range();
		Map<BlockPos, List<MachineBlockEntity>> served = new HashMap<>();
		for (MachineBlockEntity receiver : receivers) {
			MachineBlockEntity best = null;
			double bestDistance = Double.MAX_VALUE;
			for (MachineBlockEntity beacon : beacons) {
				double distance = beacon.getPos().getSquaredDistance(receiver.getPos());
				if (distance > rangeSquared || distance >= bestDistance) continue;
				// A beam from a network back into itself is a loop, not a link.
				if (networks.component(beacon.getPos(), NetKind.POWER).contains(receiver.getPos())) continue;
				best = beacon;
				bestDistance = distance;
			}
			if (best == null) continue;
			state.pairing.put(receiver.getPos(), best.getPos());
			served.computeIfAbsent(best.getPos(), key -> new ArrayList<>()).add(receiver);
		}
		double max = RackcraftConfig.values.building.beaconMaxKw;
		double loss = Math.min(0.9, Math.max(0, RackcraftConfig.values.building.beaconLoss));
		for (MachineBlockEntity beacon : beacons) {
			List<MachineBlockEntity> mine = served.get(beacon.getPos());
			if (mine == null) continue;
			double share = max / mine.size();
			// Receivers on one network ask for that network's load between them, not once each.
			Map<Set<BlockPos>, List<MachineBlockEntity>> byNetwork = new IdentityHashMap<>();
			for (MachineBlockEntity receiver : mine) {
				byNetwork.computeIfAbsent(networks.component(receiver.getPos(), NetKind.POWER), key -> new ArrayList<>()).add(receiver);
			}
			double wanted = 0;
			for (List<MachineBlockEntity> group : byNetwork.values()) {
				double ask = Math.min(share * group.size(), state.lastDemand.getOrDefault(group.get(0).getPos(), 0.0));
				for (MachineBlockEntity receiver : group) state.receiverOffer.put(receiver.getPos(), ask / group.size());
				wanted += ask;
			}
			state.beaconDemand.put(beacon.getPos(), wanted / (1 - loss));
		}
		// What the beacon's network actually gave it limits what the receivers can hand on.
		for (Map.Entry<BlockPos, Double> offer : state.receiverOffer.entrySet()) {
			BlockPos beacon = state.pairing.get(offer.getKey());
			double satisfaction = state.beaconSatisfaction.getOrDefault(beacon, 1.0);
			offer.setValue(offer.getValue() * satisfaction);
		}
	}

	/** After the solve: remember how much of its ask each beacon got, and draw the beams. */
	public static void settle(ServerWorld world, Map<MachineBlockEntity, Double> satisfaction) {
		State state = state(world);
		state.lastDemand.clear();
		for (MachineBlockEntity machine : satisfaction.keySet()) {
			if (machine.blockId().equals("beacon_receiver")) state.lastDemand.put(machine.getPos(), machine.networkDemandKw());
		}
		state.beaconSatisfaction.clear();
		for (BlockPos beaconPos : state.beaconDemand.keySet()) {
			if (!(world.getBlockEntity(beaconPos) instanceof MachineBlockEntity beacon)) continue;
			state.beaconSatisfaction.put(beaconPos, satisfaction.getOrDefault(beacon, 0.0));
		}
		state.tick++;
		if (state.tick % 2 != 0) return;
		for (Map.Entry<BlockPos, BlockPos> pair : state.pairing.entrySet()) {
			double offer = state.receiverOffer.getOrDefault(pair.getKey(), 0.0);
			if (offer <= 0.01) continue;
			Vec3d from = Vec3d.ofCenter(pair.getValue()).add(0, 0.6, 0);
			Vec3d to = Vec3d.ofCenter(pair.getKey()).add(0, 0.6, 0);
			int dots = (int) Math.max(2, from.distanceTo(to) / 1.5);
			for (int dot = 0; dot <= dots; dot++) {
				Vec3d at = from.lerp(to, (dot + state.tick % 4 * 0.25) / (dots + 1));
				world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0.02, 0.02, 0.02, 0);
			}
		}
	}

	/**
	 * Hook for powered player gear (the Hydrogen Jetpack, later): a player standing within a Power Beacon's range of a
	 * powered beacon can be topped up. Nothing in Batch A draws on it yet; it exists so the gear can call one place.
	 */
	public static boolean inBeaconRange(ServerWorld world, ServerPlayerEntity player) {
		State state = state(world);
		for (BlockPos beacon : state.beaconSatisfaction.keySet()) {
			if (state.beaconSatisfaction.get(beacon) > 0.5 && beacon.getSquaredDistance(player.getBlockPos()) <= range() * range()) return true;
		}
		return false;
	}
}
