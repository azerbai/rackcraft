package dev.rackcraft.compute;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.PacketByteBuf;

/** Everything the Operations Terminal shows, sent from the server twice a second while it is open. */
public record OpsSnapshot(long credits, float miningRate, long earnedHour, long totalEarned, String event, float smog,
		int outbox, List<ClusterView> clusters, List<ContractView> contracts, List<ModelView> models, List<Alert> alerts,
		ResearchView research, List<LeaseView> leases) {

	public static final OpsSnapshot EMPTY = new OpsSnapshot(0, 0, 0, 0, "none", 0, 0, List.of(), List.of(), List.of(), List.of(),
			new ResearchView(50, 0, "", 0, "", 0, 0, -1, false, 0, false, List.of()), List.of());

	/**
	 * The R&D tab. {@code rollbackAgo}: seconds since the frontier run last rolled back, or -1.
	 * {@code leaseSlots}: how many leases may run at once (0 until Enterprise Sales is done).
	 */
	public record ResearchView(int share, float bestClusterAi, String researchStatus, float researchRate, String frontierStatus,
			float frontierRate, int rollbacks, int rollbackAgo, boolean agi, int leaseSlots, boolean leasesUnlocked,
			List<ProjectView> projects) {}

	/**
	 * One project. {@code state}: 0 locked, 1 available, 2 running, 3 paused part-way, 4 done.
	 * {@code type} and {@code kind}: {@link Research.Type} and {@link Research.Kind} ordinals. {@code progress} and
	 * {@code checkpoint}: shares of the work, 0 to 1. {@code credits} and {@code work}: the next level's price.
	 */
	public record ProjectView(int index, String name, int type, int kind, int level, int state, long credits, boolean paid,
			double work, float progress, float checkpoint, float minCluster, String requires, String effect, String blurb) {}

	/** {@code state}: a {@link Lease.State} ordinal. {@code ticksLeft}: until an offer lapses, or until a running lease ends. */
	public record LeaseView(int id, int state, String client, String purpose, float compute, long duration, float sla, long pay,
			long ticksLeft, float uptime, float rate, String status, long earned) {}

	public record ClusterView(long id, int x, int y, int z, int racks, boolean online, int policy, float compute, float aiCompute,
			float miningRate, String activity, int problems) {}

	/** {@code ticksLeft}: until the deadline for accepted work, until the offer lapses for offers. */
	public record ContractView(int id, int state, int kind, String title, String client, int quantity, int delivered,
			int quality, int qualityNow, int modelCap, long payout, long earned, long ticksLeft, long duration,
			boolean generating, long cluster, float rate, String status, String model, boolean autoModel) {}

	/** {@code kind}: a {@link Contract.Kind} ordinal. {@code speed}: items per unit of compute against a cost-1 model. */
	public record ModelView(String id, int kind, String name, String tier, int cap, int maxCap, int trained, int queued,
			int progress, boolean training, float rate, float speed, int dataWork, String blurb) {}

	/** Severity 0 info, 1 warning, 2 problem. */
	public record Alert(int severity, String text) {}

	public void write(PacketByteBuf buf) {
		buf.writeVarLong(credits);
		buf.writeFloat(miningRate);
		buf.writeVarLong(earnedHour);
		buf.writeVarLong(totalEarned);
		buf.writeString(event);
		buf.writeFloat(smog);
		buf.writeVarInt(outbox);
		buf.writeVarInt(clusters.size());
		for (ClusterView cluster : clusters) {
			buf.writeLong(cluster.id());
			buf.writeVarInt(cluster.x());
			buf.writeVarInt(cluster.y());
			buf.writeVarInt(cluster.z());
			buf.writeVarInt(cluster.racks());
			buf.writeBoolean(cluster.online());
			buf.writeVarInt(cluster.policy());
			buf.writeFloat(cluster.compute());
			buf.writeFloat(cluster.aiCompute());
			buf.writeFloat(cluster.miningRate());
			buf.writeString(cluster.activity());
			buf.writeVarInt(cluster.problems());
		}
		buf.writeVarInt(contracts.size());
		for (ContractView contract : contracts) {
			buf.writeVarInt(contract.id());
			buf.writeVarInt(contract.state());
			buf.writeVarInt(contract.kind());
			buf.writeString(contract.title());
			buf.writeString(contract.client());
			buf.writeVarInt(contract.quantity());
			buf.writeVarInt(contract.delivered());
			buf.writeVarInt(contract.quality());
			buf.writeVarInt(contract.qualityNow());
			buf.writeVarInt(contract.modelCap());
			buf.writeVarLong(contract.payout());
			buf.writeVarLong(contract.earned());
			buf.writeLong(contract.ticksLeft());
			buf.writeVarLong(contract.duration());
			buf.writeBoolean(contract.generating());
			buf.writeLong(contract.cluster());
			buf.writeFloat(contract.rate());
			buf.writeString(contract.status());
			buf.writeString(contract.model());
			buf.writeBoolean(contract.autoModel());
		}
		buf.writeVarInt(models.size());
		for (ModelView model : models) {
			buf.writeString(model.id());
			buf.writeVarInt(model.kind());
			buf.writeString(model.name());
			buf.writeString(model.tier());
			buf.writeVarInt(model.cap());
			buf.writeVarInt(model.maxCap());
			buf.writeVarInt(model.trained());
			buf.writeVarInt(model.queued());
			buf.writeVarInt(model.progress());
			buf.writeBoolean(model.training());
			buf.writeFloat(model.rate());
			buf.writeFloat(model.speed());
			buf.writeVarInt(model.dataWork());
			buf.writeString(model.blurb());
		}
		buf.writeVarInt(alerts.size());
		for (Alert alert : alerts) {
			buf.writeVarInt(alert.severity());
			buf.writeString(alert.text());
		}
		buf.writeVarInt(research.share());
		buf.writeFloat(research.bestClusterAi());
		buf.writeString(research.researchStatus());
		buf.writeFloat(research.researchRate());
		buf.writeString(research.frontierStatus());
		buf.writeFloat(research.frontierRate());
		buf.writeVarInt(research.rollbacks());
		buf.writeVarInt(research.rollbackAgo());
		buf.writeBoolean(research.agi());
		buf.writeVarInt(research.leaseSlots());
		buf.writeBoolean(research.leasesUnlocked());
		buf.writeVarInt(research.projects().size());
		for (ProjectView project : research.projects()) {
			buf.writeVarInt(project.index());
			buf.writeString(project.name());
			buf.writeVarInt(project.type());
			buf.writeVarInt(project.kind());
			buf.writeVarInt(project.level());
			buf.writeVarInt(project.state());
			buf.writeVarLong(project.credits());
			buf.writeBoolean(project.paid());
			buf.writeDouble(project.work());
			buf.writeFloat(project.progress());
			buf.writeFloat(project.checkpoint());
			buf.writeFloat(project.minCluster());
			buf.writeString(project.requires());
			buf.writeString(project.effect());
			buf.writeString(project.blurb());
		}
		buf.writeVarInt(leases.size());
		for (LeaseView lease : leases) {
			buf.writeVarInt(lease.id());
			buf.writeVarInt(lease.state());
			buf.writeString(lease.client());
			buf.writeString(lease.purpose());
			buf.writeFloat(lease.compute());
			buf.writeVarLong(lease.duration());
			buf.writeFloat(lease.sla());
			buf.writeVarLong(lease.pay());
			buf.writeLong(lease.ticksLeft());
			buf.writeFloat(lease.uptime());
			buf.writeFloat(lease.rate());
			buf.writeString(lease.status());
			buf.writeVarLong(lease.earned());
		}
	}

	public static OpsSnapshot read(PacketByteBuf buf) {
		long credits = buf.readVarLong();
		float miningRate = buf.readFloat();
		long earnedHour = buf.readVarLong();
		long totalEarned = buf.readVarLong();
		String event = buf.readString();
		float smog = buf.readFloat();
		int outbox = buf.readVarInt();
		List<ClusterView> clusters = new ArrayList<>();
		for (int count = buf.readVarInt(); count > 0; count--) {
			clusters.add(new ClusterView(buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
					buf.readBoolean(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readString(),
					buf.readVarInt()));
		}
		List<ContractView> contracts = new ArrayList<>();
		for (int count = buf.readVarInt(); count > 0; count--) {
			contracts.add(new ContractView(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readString(), buf.readString(),
					buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong(),
					buf.readVarLong(), buf.readLong(), buf.readVarLong(), buf.readBoolean(), buf.readLong(), buf.readFloat(),
					buf.readString(), buf.readString(), buf.readBoolean()));
		}
		List<ModelView> models = new ArrayList<>();
		for (int count = buf.readVarInt(); count > 0; count--) {
			models.add(new ModelView(buf.readString(), buf.readVarInt(), buf.readString(), buf.readString(), buf.readVarInt(),
					buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readFloat(),
					buf.readFloat(), buf.readVarInt(), buf.readString()));
		}
		List<Alert> alerts = new ArrayList<>();
		for (int count = buf.readVarInt(); count > 0; count--) alerts.add(new Alert(buf.readVarInt(), buf.readString()));
		int share = buf.readVarInt();
		float bestClusterAi = buf.readFloat();
		String researchStatus = buf.readString();
		float researchRate = buf.readFloat();
		String frontierStatus = buf.readString();
		float frontierRate = buf.readFloat();
		int rollbacks = buf.readVarInt();
		int rollbackAgo = buf.readVarInt();
		boolean agi = buf.readBoolean();
		int leaseSlots = buf.readVarInt();
		boolean leasesUnlocked = buf.readBoolean();
		List<ProjectView> projects = new ArrayList<>();
		for (int count = buf.readVarInt(); count > 0; count--) {
			projects.add(new ProjectView(buf.readVarInt(), buf.readString(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
					buf.readVarInt(), buf.readVarLong(), buf.readBoolean(), buf.readDouble(), buf.readFloat(), buf.readFloat(),
					buf.readFloat(), buf.readString(), buf.readString(), buf.readString()));
		}
		ResearchView research = new ResearchView(share, bestClusterAi, researchStatus, researchRate, frontierStatus, frontierRate,
				rollbacks, rollbackAgo, agi, leaseSlots, leasesUnlocked, projects);
		List<LeaseView> leases = new ArrayList<>();
		for (int count = buf.readVarInt(); count > 0; count--) {
			leases.add(new LeaseView(buf.readVarInt(), buf.readVarInt(), buf.readString(), buf.readString(), buf.readFloat(),
					buf.readVarLong(), buf.readFloat(), buf.readVarLong(), buf.readLong(), buf.readFloat(), buf.readFloat(),
					buf.readString(), buf.readVarLong()));
		}
		return new OpsSnapshot(credits, miningRate, earnedHour, totalEarned, event, smog, outbox, clusters, contracts, models, alerts,
				research, leases);
	}
}
