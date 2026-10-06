package dev.rackcraft.compute;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.PacketByteBuf;

/** Everything the Operations Terminal shows, sent from the server twice a second while it is open. */
public record OpsSnapshot(long credits, float miningRate, long earnedHour, long totalEarned, String event, float smog,
		int outbox, List<ClusterView> clusters, List<ContractView> contracts, List<ModelView> models, List<Alert> alerts) {

	public static final OpsSnapshot EMPTY = new OpsSnapshot(0, 0, 0, 0, "none", 0, 0, List.of(), List.of(), List.of(), List.of());

	public record ClusterView(long id, int x, int y, int z, int racks, boolean online, int policy, float compute, float aiCompute,
			float miningRate, String activity, int problems) {}

	/** {@code ticksLeft}: until the deadline for accepted work, until the offer lapses for offers. */
	public record ContractView(int id, int state, int kind, String title, String client, int quantity, int delivered,
			int quality, int qualityNow, int modelCap, long payout, long earned, long ticksLeft, long duration,
			boolean generating, long cluster, float rate, String status) {}

	public record ModelView(String name, int cap, int trained, int queued, int progress, boolean training, float rate) {}

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
		}
		buf.writeVarInt(models.size());
		for (ModelView model : models) {
			buf.writeString(model.name());
			buf.writeVarInt(model.cap());
			buf.writeVarInt(model.trained());
			buf.writeVarInt(model.queued());
			buf.writeVarInt(model.progress());
			buf.writeBoolean(model.training());
			buf.writeFloat(model.rate());
		}
		buf.writeVarInt(alerts.size());
		for (Alert alert : alerts) {
			buf.writeVarInt(alert.severity());
			buf.writeString(alert.text());
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
					buf.readString()));
		}
		List<ModelView> models = new ArrayList<>();
		for (int count = buf.readVarInt(); count > 0; count--) {
			models.add(new ModelView(buf.readString(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
					buf.readBoolean(), buf.readFloat()));
		}
		List<Alert> alerts = new ArrayList<>();
		for (int count = buf.readVarInt(); count > 0; count--) alerts.add(new Alert(buf.readVarInt(), buf.readString()));
		return new OpsSnapshot(credits, miningRate, earnedHour, totalEarned, event, smog, outbox, clusters, contracts, models, alerts);
	}
}
