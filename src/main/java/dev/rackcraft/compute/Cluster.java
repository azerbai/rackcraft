package dev.rackcraft.compute;

import dev.rackcraft.CreativeSettings;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.sim.ServerModel;
import dev.rackcraft.world.NetworkManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Server racks that share one fiber network. Clusters lend their racks' compute to autocrafting, AI
 * contract generation and model training; whatever is left mines. A cluster is "online" when its network
 * has router bandwidth, which remote work (contracts, borrowed autocrafting) needs.
 *
 * The id is the lowest rack position on the network, so it stays put while that rack does.
 */
public final class Cluster {
	public enum Policy {
		/** Lend racks to any work that needs them; mine with the rest. */
		AUTO,
		/** Never lend racks: mine only. */
		MINING;

		public Policy next() { return values()[(ordinal() + 1) % values().length]; }
	}

	/**
	 * GENERAL: autocrafting and general research. AI: training, AI research and frontier runs. INFERENCE: contracts and
	 * leases, which also take NPU Inference Cards' AI compute.
	 */
	public enum Kind { GENERAL, AI, INFERENCE }

	private final long id;
	private final Set<BlockPos> network;
	private final List<MachineBlockEntity> racks;
	private final double bandwidth;
	private final Policy policy;

	Cluster(long id, Set<BlockPos> network, List<MachineBlockEntity> racks, double bandwidth, Policy policy) {
		this.id = id;
		this.network = network;
		this.racks = racks;
		this.bandwidth = bandwidth;
		this.policy = policy;
	}

	public long id() { return id; }
	public BlockPos anchor() { return BlockPos.fromLong(id); }
	public List<MachineBlockEntity> racks() { return racks; }
	public boolean contains(BlockPos pos) { return network.contains(pos); }
	public boolean online() { return bandwidth > 0; }
	public double bandwidth() { return bandwidth; }
	public Policy policy() { return policy; }

	public double capacity(Kind kind) {
		return racks.stream().mapToDouble(rack -> compute(rack, kind)).sum();
	}

	/** Compute a rack lends right now: scaled by its load, so unpowered, tripped or overheated racks lend none. */
	public static double compute(MachineBlockEntity rack, Kind kind) {
		if (rack.powerSatisfaction() < 0.5 || rack.isTripped() || rack.thermalFactor() <= 0) return 0;
		double total = 0;
		for (ServerModel.Module module : rack.modules()) {
			total += switch (kind) {
				case GENERAL -> module.compute();
				case AI -> module.inferenceOnly() ? 0 : module.aiCompute();
				case INFERENCE -> module.aiCompute();
			};
		}
		if (total <= 0) return 0;
		total *= dev.rackcraft.block.Racks.tier(rack).bonus();
		// Research makes every rack lend more.
		Research.Effects effects = rack.getWorld() instanceof net.minecraft.server.world.ServerWorld world
				? ResearchLab.effects(world) : Research.Effects.NONE;
		// A Compute Pod's fabric lends more AI compute, never general compute.
		double fabric = kind == Kind.GENERAL ? 1 : rack.podBonus();
		return total * rack.load() * fabric * (kind == Kind.GENERAL ? effects.generalCompute() : effects.aiCompute());
	}

	/**
	 * Takes free racks from these clusters, biggest first, until {@code wanted} compute is reached.
	 * Marks them busy with {@code status} and returns the compute taken.
	 */
	public static double take(List<Cluster> clusters, Kind kind, double wanted, Map<MachineBlockEntity, dev.rackcraft.block.RackStatus> busy,
			dev.rackcraft.block.RackStatus status, List<MachineBlockEntity> taken) {
		List<MachineBlockEntity> free = new ArrayList<>();
		for (Cluster cluster : clusters) {
			for (MachineBlockEntity rack : cluster.racks) if (!busy.containsKey(rack) && compute(rack, kind) > 0) free.add(rack);
		}
		free.sort(Comparator.comparingDouble((MachineBlockEntity rack) -> compute(rack, kind)).reversed());
		double total = 0;
		for (MachineBlockEntity rack : free) {
			if (total >= wanted) break;
			busy.put(rack, status);
			taken.add(rack);
			total += compute(rack, kind);
		}
		return total;
	}

	/** Groups racks by fiber network. Policies are looked up by any rack position the player set them on. */
	public static List<Cluster> find(ServerWorld world, List<MachineBlockEntity> racks, ComputeMarket market) {
		NetworkManager networks = NetworkManager.get(world);
		Map<Set<BlockPos>, List<MachineBlockEntity>> byNetwork = new HashMap<>();
		for (MachineBlockEntity rack : racks) {
			Set<BlockPos> network = networks.component(rack.getPos(), NetKind.DATA);
			if (network.isEmpty()) network = Set.of(rack.getPos());
			byNetwork.computeIfAbsent(network, ignored -> new ArrayList<>()).add(rack);
		}
		List<Cluster> clusters = new ArrayList<>();
		byNetwork.forEach((network, members) -> {
			long id = members.stream().mapToLong(rack -> rack.getPos().asLong()).min().orElse(0);
			double bandwidth = 0;
			for (BlockPos pos : network) {
				if (!(world.getBlockEntity(pos) instanceof MachineBlockEntity endpoint)) continue;
				switch (endpoint.blockId()) {
					case "uplink_router" -> bandwidth += 100;
					case "core_router" -> bandwidth += 1000;
					case "creative_router" -> bandwidth += endpoint.creativeValue(CreativeSettings.BANDWIDTH);
					default -> {}
				}
			}
			members.sort(Comparator.comparingLong(rack -> rack.getPos().asLong()));
			clusters.add(new Cluster(id, network, List.copyOf(members), bandwidth, market.policyFor(id, members)));
		});
		clusters.sort(Comparator.comparingLong(Cluster::id));
		return clusters;
	}
}
