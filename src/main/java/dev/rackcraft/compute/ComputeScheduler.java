package dev.rackcraft.compute;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.RackStatus;
import dev.rackcraft.storage.Autocrafter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.world.ServerWorld;

/**
 * Hands out rack compute once per simulation step, in priority order:
 * <ol>
 *   <li>autocrafting jobs (their own storage network's racks first, then any online cluster),</li>
 *   <li>AI contracts that are generating, earliest deadline first,</li>
 *   <li>model training, while there is uploaded data.</li>
 * </ol>
 * Racks are lent whole, and a lent rack does not mine that step. Racks nothing needs keep mining.
 */
public final class ComputeScheduler {
	private static final Map<ServerWorld, List<Cluster>> LAST_CLUSTERS = new WeakHashMap<>();
	private static final Map<ServerWorld, Map<MachineBlockEntity, RackStatus>> LAST_BUSY = new WeakHashMap<>();
	private static final java.util.Random RANDOM = new java.util.Random();

	private ComputeScheduler() {}

	/** Runs the step. Returns each lent rack and what it is doing instead of mining. */
	public static Map<MachineBlockEntity, RackStatus> tick(ServerWorld world, List<MachineBlockEntity> racks, double dt) {
		ComputeMarket market = ComputeMarket.get(world);
		List<Cluster> clusters = Cluster.find(world, racks, market);
		Map<MachineBlockEntity, RackStatus> busy = new HashMap<>();
		Autocrafter.tick(world, clusters, busy, dt);
		runContracts(world, market, clusters, busy, dt);
		runTraining(market, clusters, busy, dt);
		LAST_CLUSTERS.put(world, clusters);
		LAST_BUSY.put(world, busy);
		return busy;
	}

	public static List<Cluster> clusters(ServerWorld world) {
		return LAST_CLUSTERS.getOrDefault(world, List.of());
	}

	public static RackStatus assignment(ServerWorld world, MachineBlockEntity rack) {
		return LAST_BUSY.getOrDefault(world, Map.of()).get(rack);
	}

	private static void runContracts(ServerWorld world, ComputeMarket market, List<Cluster> clusters,
			Map<MachineBlockEntity, RackStatus> busy, double dt) {
		long now = world.getTime();
		market.refreshOffers(now);
		market.expire(now);
		market.deliverFromOutbox(world, now);
		for (Contract contract : market.active()) {
			contract.computeRate = 0;
			if (contract.state == Contract.State.ACCEPTED && contract.delivered >= contract.quantity) continue;
			if (!contract.generating) {
				contract.status = "Waiting: press Generate, or deliver finished work";
				continue;
			}
			AiModel model = market.modelFor(contract);
			// Work is counted in reference units (a cost-1 model's AI-compute-seconds), so progress carries
			// over if the contract switches models.
			double perItem = Contract.workFor(contract.scale(), contract.quality, model.cap());
			if (Double.isInfinite(perItem)) {
				contract.status = "Model too weak: " + model.versionName() + " tops out at " + (int) (model.cap() * 100)
						+ "%. Train it, or pick a bigger model";
				continue;
			}
			List<Cluster> eligible = new ArrayList<>();
			for (Cluster cluster : clusters) {
				boolean chosen = contract.cluster == Contract.ANY_CLUSTER
						? cluster.policy() == Cluster.Policy.AUTO : cluster.id() == contract.cluster;
				if (chosen && cluster.online()) eligible.add(cluster);
			}
			if (eligible.isEmpty()) {
				contract.status = contract.cluster == Contract.ANY_CLUSTER
						? "No online cluster: racks need an uplink router" : "Assigned cluster is offline or gone";
				continue;
			}
			double rate = Cluster.take(eligible, Cluster.Kind.AI, Double.POSITIVE_INFINITY, busy, RackStatus.GENERATING, new ArrayList<>());
			if (rate <= 0) {
				contract.status = "No free AI compute (GPUs, Tensor Accelerators)";
				continue;
			}
			contract.computeRate = rate;
			contract.work += rate * dt / model.spec.cost();
			contract.status = "Generating";
			int wanted = contract.state == Contract.State.STOCK ? 1 : contract.quantity - contract.delivered - pending(market, contract);
			while (contract.work >= perItem && wanted > 0) {
				contract.work -= perItem;
				market.produce(contract, model.finishedQuality(contract.quality, RANDOM));
				wanted--;
			}
			if (wanted <= 0) {
				contract.generating = false;
				contract.work = 0;
			}
			market.markDirty();
		}
	}

	/** Finished items for this contract still sitting in the outbox. */
	private static int pending(ComputeMarket market, Contract contract) {
		return (int) market.outbox().stream().filter(contract::matches).count();
	}

	/** Models with queued data share the free racks evenly; each trains on its own queue at its own cost per item. */
	private static void runTraining(ComputeMarket market, List<Cluster> clusters, Map<MachineBlockEntity, RackStatus> busy, double dt) {
		List<Cluster> eligible = clusters.stream().filter(cluster -> cluster.policy() == Cluster.Policy.AUTO).toList();
		List<AiModel> learning = new ArrayList<>();
		for (AiModel model : market.models()) {
			model.computeRate = 0;
			if (model.training && model.queued > 0) learning.add(model);
		}
		if (learning.isEmpty()) return;
		double free = 0;
		for (Cluster cluster : eligible) {
			for (MachineBlockEntity rack : cluster.racks()) if (!busy.containsKey(rack)) free += Cluster.compute(rack, Cluster.Kind.AI);
		}
		for (int index = 0; index < learning.size(); index++) {
			AiModel model = learning.get(index);
			// The last model takes whatever is left, so no rack sits idle over rounding.
			double wanted = index == learning.size() - 1 ? Double.POSITIVE_INFINITY : free / learning.size();
			double rate = Cluster.take(eligible, Cluster.Kind.AI, wanted, busy, RackStatus.TRAINING, new ArrayList<>());
			model.computeRate = rate;
			model.progress += rate * dt;
			while (model.progress >= model.spec.trainWork() && model.queued > 0) {
				model.progress -= model.spec.trainWork();
				model.queued--;
				model.trained++;
			}
			if (model.queued == 0) model.progress = 0;
		}
		market.markDirty();
	}
}
