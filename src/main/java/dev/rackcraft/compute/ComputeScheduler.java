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
 *   <li>a frontier training run, which takes every AI rack of the biggest Auto cluster,</li>
 *   <li>autocrafting jobs (their own storage network's racks first, then any online cluster),</li>
 *   <li>Compute Leases that are running,</li>
 *   <li>AI contracts that are generating, earliest deadline first,</li>
 *   <li>the research project under way, up to its share of what's free,</li>
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
		ResearchLab lab = ResearchLab.get(world);
		Map<MachineBlockEntity, RackStatus> busy = new HashMap<>();
		runFrontier(world, lab, clusters, busy, dt);
		Autocrafter.tick(world, clusters, busy, dt);
		runLeases(world, market, lab, clusters, busy, dt);
		runContracts(world, market, clusters, busy, dt);
		runResearch(world, lab, clusters, busy, dt);
		runTraining(market, lab, clusters, busy, dt);
		lab.tick(world);
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
			double perItem = Contract.workFor(contract.scale(), contract.quality, model.cap())
					* ResearchLab.effects(world).contractWork();
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
	private static void runTraining(ComputeMarket market, ResearchLab lab, List<Cluster> clusters,
			Map<MachineBlockEntity, RackStatus> busy, double dt) {
		double perItem = lab.effects().trainingWork();
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
			while (model.progress >= model.spec.trainWork() * perItem && model.queued > 0) {
				model.progress -= model.spec.trainWork() * perItem;
				model.queued--;
				model.trained++;
			}
			if (model.queued == 0) model.progress = 0;
		}
		market.markDirty();
	}

	/**
	 * The frontier run trains on the biggest Auto cluster, which must keep up the run's minimum AI compute. It takes
	 * every rack there that lends AI compute. If the cluster falls short mid-run (an outage, trips, an overheated
	 * hall) the run rolls back to its last checkpoint; pausing it first saves a checkpoint where it stands.
	 */
	private static void runFrontier(ServerWorld world, ResearchLab lab, List<Cluster> clusters,
			Map<MachineBlockEntity, RackStatus> busy, double dt) {
		lab.frontierRate = 0;
		Cluster best = null;
		double bestAi = 0;
		for (Cluster cluster : clusters) {
			if (cluster.policy() != Cluster.Policy.AUTO) continue;
			double ai = cluster.capacity(Cluster.Kind.AI);
			if (ai > bestAi) {
				best = cluster;
				bestAi = ai;
			}
		}
		lab.bestClusterAi = bestAi;
		if (!lab.frontierRunning()) {
			lab.frontierStatus = lab.frontier().isEmpty() ? "" : "Paused: checkpoint saved where it stands";
			return;
		}
		Research.Project run = Research.get(lab.frontier());
		long now = world.getTime();
		if (best == null || bestAi < run.minCluster()) {
			double lost = lab.rollBack(run.id());
			if (lost > 0) {
				lab.lastRollback = now;
				lab.lastLost = lost / run.work(0);
				for (var player : world.getPlayers()) {
					player.sendMessage(net.minecraft.text.Text.literal(String.format(java.util.Locale.ROOT,
							"Loss spike! %s lost its compute and rolled back to the last checkpoint (%.1f%% lost)",
							run.name(), lab.lastLost * 100)).formatted(net.minecraft.util.Formatting.RED), false);
				}
			}
			lab.frontierStatus = String.format(java.util.Locale.ROOT,
					"Stalled: needs one Auto cluster lending %,.0f AI compute; the biggest lends %,.0f", run.minCluster(), bestAi);
			return;
		}
		double rate = Cluster.take(List.of(best), Cluster.Kind.AI, Double.POSITIVE_INFINITY, busy, RackStatus.RESEARCHING,
				new ArrayList<>());
		lab.frontierRate = rate;
		var anchor = best.anchor();
		lab.frontierStatus = "Training on the cluster at " + anchor.getX() + ", " + anchor.getY() + ", " + anchor.getZ();
		lab.addWork(world, run, rate * dt);
	}

	/** Running leases borrow AI racks from online Auto clusters; each step counts how much of the promise was kept. */
	private static void runLeases(ServerWorld world, ComputeMarket market, ResearchLab lab, List<Cluster> clusters,
			Map<MachineBlockEntity, RackStatus> busy, double dt) {
		long now = world.getTime();
		double facilityAi = clusters.stream().filter(cluster -> cluster.policy() == Cluster.Policy.AUTO)
				.mapToDouble(cluster -> cluster.capacity(Cluster.Kind.AI)).sum();
		market.refreshLeases(now, facilityAi, lab.effects().leases());
		List<Cluster> eligible = clusters.stream()
				.filter(cluster -> cluster.policy() == Cluster.Policy.AUTO && cluster.online()).toList();
		long ticks = Math.max(1, Math.round(dt * 20));
		for (Lease lease : market.runningLeases()) {
			double rate = eligible.isEmpty() ? 0
					: Cluster.take(eligible, Cluster.Kind.AI, lease.compute, busy, RackStatus.LEASED, new ArrayList<>());
			lease.rate = rate;
			lease.record(rate / lease.compute, ticks);
			lease.status = rate >= lease.compute ? "Serving"
					: eligible.isEmpty() ? "Down: no online Auto cluster (racks need an uplink router)"
					: String.format(java.util.Locale.ROOT, "Short: delivering %,.0f of %,.0f AI", rate, lease.compute);
			if (lease.finished() || lease.hopeless()) {
				market.settleLease(world, lease, now);
				String message = lease.state == Lease.State.DONE
						? String.format(java.util.Locale.ROOT, "Lease for %s finished at %.2f%% uptime: paid %,d RC", lease.client,
								lease.uptime() * 100, lease.earned)
						: String.format(java.util.Locale.ROOT, "Lease for %s breached: uptime %.2f%% against a %.1f%% guarantee. No pay.",
								lease.client, lease.uptime() * 100, lease.sla * 100);
				for (var player : world.getPlayers()) {
					player.sendMessage(net.minecraft.text.Text.literal(message).formatted(lease.state == Lease.State.DONE
							? net.minecraft.util.Formatting.GREEN : net.minecraft.util.Formatting.RED), false);
				}
			}
		}
		if (!market.runningLeases().isEmpty()) market.markDirty();
	}

	/** The research project under way takes its share of the free compute of its kind on Auto clusters. */
	private static void runResearch(ServerWorld world, ResearchLab lab, List<Cluster> clusters,
			Map<MachineBlockEntity, RackStatus> busy, double dt) {
		lab.researchRate = 0;
		Research.Project project = Research.get(lab.active());
		if (project == null) {
			lab.researchStatus = "";
			return;
		}
		Cluster.Kind kind = project.kind() == Research.Kind.AI ? Cluster.Kind.AI : Cluster.Kind.GENERAL;
		List<Cluster> eligible = clusters.stream().filter(cluster -> cluster.policy() == Cluster.Policy.AUTO).toList();
		double free = 0;
		for (Cluster cluster : eligible) {
			for (MachineBlockEntity rack : cluster.racks()) if (!busy.containsKey(rack)) free += Cluster.compute(rack, kind);
		}
		if (free <= 0) {
			lab.researchStatus = "Waiting: no free racks lend " + (kind == Cluster.Kind.AI ? "AI" : "general") + " compute";
			return;
		}
		double rate = Cluster.take(eligible, kind, free * lab.share() / 100.0, busy, RackStatus.RESEARCHING, new ArrayList<>());
		lab.researchRate = rate;
		lab.researchStatus = "Researching";
		lab.addWork(world, project, rate * dt);
	}
}
