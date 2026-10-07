package dev.rackcraft.compute;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.RcBlocks;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.RackStatus;
import dev.rackcraft.screen.RcScreenHandlers;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.storage.DriveItem;
import dev.rackcraft.storage.StorageState;
import dev.rackcraft.world.AirQuality;
import dev.rackcraft.world.FacilityManager;
import dev.rackcraft.world.FreshwaterCooling;
import dev.rackcraft.world.NetworkManager;
import dev.rackcraft.world.SimTicker;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * The Operations Terminal: one screen for the whole facility in this dimension. Incoming and accepted
 * contracts, what every cluster is doing, the AI models, RackCoin income and every rack, storage, pump,
 * cable and generator problem. Like the Storage Terminal it has no real slots: the server sends a snapshot
 * twice a second and the client sends {@link Action}s back.
 */
public final class OpsScreenHandler extends ScreenHandler {
	public static final Identifier SYNC = Rackcraft.id("ops_sync");
	public static final Identifier ACTION = Rackcraft.id("ops_action");
	private static final int SYNC_INTERVAL = 10;
	private static final int MAX_ALERTS = 60;

	/** UPLOAD and TRAINING take a model index in {@code id}; MODEL cycles a contract's model choice. */
	public enum Action { ACCEPT, DECLINE, ABANDON, GENERATE, STOP, DELIVER, PRINT, POLICY, UPLOAD, COLLECT, TRAINING, MODEL }

	private final BlockPos pos;
	private final PlayerEntity player;
	private int syncCountdown;
	private OpsSnapshot snapshot = OpsSnapshot.EMPTY;
	private int revision;

	public OpsScreenHandler(int syncId, PlayerInventory inventory, BlockPos pos) {
		super(RcScreenHandlers.OPERATIONS, syncId);
		this.pos = pos;
		this.player = inventory.player;
	}

	public BlockPos pos() { return pos; }
	public OpsSnapshot snapshot() { return snapshot; }
	public int revision() { return revision; }

	@Override
	public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }

	@Override
	public boolean canUse(PlayerEntity player) {
		return player.getWorld().getBlockState(pos).isOf(RcBlocks.get("operations_terminal"))
				&& player.squaredDistanceTo(pos.toCenterPos()) <= 64;
	}

	@Override
	public void sendContentUpdates() {
		super.sendContentUpdates();
		if (!(player instanceof ServerPlayerEntity serverPlayer) || --syncCountdown > 0) return;
		syncCountdown = SYNC_INTERVAL;
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(syncId);
		build(serverPlayer.getServerWorld(), player.getBlockPos()).write(buf);
		ServerPlayNetworking.send(serverPlayer, SYNC, buf);
	}

	public void applySync(PacketByteBuf buf) {
		snapshot = OpsSnapshot.read(buf);
		revision++;
	}

	// ---------------------------------------------------------------- actions

	public void handle(ServerPlayerEntity player, Action action, int id, long cluster) {
		ServerWorld world = player.getServerWorld();
		ComputeMarket market = ComputeMarket.get(world);
		long now = world.getTime();
		switch (action) {
			case ACCEPT -> {
				String problem = market.accept(id, now);
				if (problem != null) player.sendMessage(Text.literal(problem).formatted(Formatting.RED), true);
			}
			case DECLINE -> market.decline(id);
			case ABANDON -> market.abandon(id);
			case GENERATE -> market.setGenerating(id, true, cluster);
			case STOP -> market.setGenerating(id, false, cluster);
			case DELIVER -> {
				int delivered = market.deliverFromInventory(world, player, id, now);
				player.sendMessage(Text.literal(delivered > 0 ? "Delivered " + delivered + " from your inventory"
						: "Nothing in your inventory matches this contract (same prompt, enough quality)")
						.formatted(delivered > 0 ? Formatting.GREEN : Formatting.RED), true);
			}
			case PRINT -> market.printCopy(id);
			case POLICY -> {
				for (Cluster entry : ComputeScheduler.clusters(world)) {
					if (entry.id() == cluster) market.setPolicy(cluster, entry.policy().next());
				}
			}
			case UPLOAD -> {
				AiModel model = modelAt(market, id);
				if (model == null) break;
				int uploaded = market.uploadTrainingData(player, model.id());
				String data = model.kind() == Contract.Kind.IMAGE ? "Art Aggregates" : "Text Corpora";
				player.sendMessage(Text.literal(uploaded > 0 ? "Uploaded " + uploaded + " " + data + " to " + model.versionName()
						: "No " + data + " in your inventory").formatted(uploaded > 0 ? Formatting.GREEN : Formatting.RED), true);
			}
			case COLLECT -> market.collectOutbox(player);
			case TRAINING -> {
				AiModel model = modelAt(market, id);
				if (model != null) market.toggleTraining(model.id());
			}
			case MODEL -> market.cycleModel(id);
		}
		syncCountdown = 0;
		sendContentUpdates();
	}

	private static AiModel modelAt(ComputeMarket market, int index) {
		List<AiModel> models = market.models();
		return index >= 0 && index < models.size() ? models.get(index) : null;
	}

	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, responseSender) -> {
			int syncId = buf.readVarInt();
			Action action = Action.values()[Math.max(0, Math.min(Action.values().length - 1, buf.readVarInt()))];
			int id = buf.readVarInt();
			long cluster = buf.readLong();
			server.execute(() -> {
				if (player.currentScreenHandler instanceof OpsScreenHandler ops && ops.syncId == syncId && ops.canUse(player)) {
					ops.handle(player, action, id, cluster);
				}
			});
		});
	}

	// ---------------------------------------------------------------- the snapshot

	public static OpsSnapshot build(ServerWorld world, BlockPos viewer) {
		FacilityManager facility = FacilityManager.get(world);
		ComputeMarket market = ComputeMarket.get(world);
		long now = world.getTime();
		List<MachineBlockEntity> machines = SimTicker.machines(world);
		List<OpsSnapshot.Alert> alerts = new ArrayList<>();

		List<OpsSnapshot.ClusterView> clusters = new ArrayList<>();
		for (Cluster cluster : ComputeScheduler.clusters(world)) {
			Map<RackStatus, Integer> counts = new EnumMap<>(RackStatus.class);
			double mining = 0;
			int problems = 0;
			for (MachineBlockEntity rack : cluster.racks()) {
				if (rack.isRemoved()) continue;
				counts.merge(rack.rackStatus(), 1, Integer::sum);
				mining += rack.miningRate();
				if (problem(rack.rackStatus())) problems++;
			}
			List<String> parts = new ArrayList<>();
			counts.forEach((status, count) -> parts.add(count + " " + STATUS_WORDS.getOrDefault(status, status.name().toLowerCase(Locale.ROOT))));
			BlockPos anchor = cluster.anchor();
			clusters.add(new OpsSnapshot.ClusterView(cluster.id(), anchor.getX(), anchor.getY(), anchor.getZ(), cluster.racks().size(),
					cluster.online(), cluster.policy().ordinal(), (float) cluster.capacity(Cluster.Kind.GENERAL),
					(float) cluster.capacity(Cluster.Kind.AI), (float) mining, String.join(", ", parts), problems));
		}

		List<OpsSnapshot.ContractView> contracts = new ArrayList<>();
		List<Contract> ordered = new ArrayList<>(market.active());
		market.contracts().stream().filter(contract -> contract.state == Contract.State.OFFERED).forEach(ordered::add);
		market.contracts().stream().filter(contract -> contract.state == Contract.State.DONE || contract.state == Contract.State.FAILED)
				.forEach(ordered::add);
		for (Contract contract : ordered) {
			AiModel model = market.modelFor(contract);
			long left = switch (contract.state) {
				case OFFERED -> contract.offerExpires - now;
				case ACCEPTED -> contract.deadline - now;
				default -> 0;
			};
			contracts.add(new OpsSnapshot.ContractView(contract.id, contract.state.ordinal(), contract.kind.ordinal(), contract.title(),
					contract.client, contract.quantity, contract.delivered, contract.quality, contract.qualityNow(model),
					(int) Math.floor(model.cap() * 100), contract.payout, contract.earned, left, contract.durationTicks,
					contract.generating, contract.cluster, (float) contract.computeRate, contract.status, model.versionName(),
					market.model(contract.model) == null));
			if (contract.state == Contract.State.ACCEPTED) {
				if (left < 0) alerts.add(new OpsSnapshot.Alert(2, "Late: " + contract.title() + " (half pay now)"));
				else if (left < 20 * 120) alerts.add(new OpsSnapshot.Alert(1, "Due in " + left / 20 + " s: " + contract.title()));
				if (contract.quality >= model.cap() * 100) {
					alerts.add(new OpsSnapshot.Alert(2, model.versionName() + " is too weak for " + contract.title() + ": train it"));
				}
			}
		}

		List<OpsSnapshot.ModelView> models = new ArrayList<>();
		for (AiModel model : market.models()) {
			AiModel.Spec spec = model.spec;
			models.add(new OpsSnapshot.ModelView(spec.id(), spec.kind().ordinal(), model.versionName(), spec.tier(),
					(int) Math.floor(model.cap() * 100), (int) Math.floor(spec.maxCap() * 100), model.trained, model.queued,
					(int) Math.floor(model.progress / spec.trainWork() * 100), model.training, (float) model.computeRate,
					(float) spec.speed(), (int) spec.trainWork(), spec.blurb()));
		}

		machineAlerts(world, machines, alerts);
		if (!facility.activeEvent().equals("none")) {
			alerts.add(0, new OpsSnapshot.Alert(2, "Event: " + facility.activeEvent().replace('_', ' ')));
		}
		alerts.sort((a, b) -> Integer.compare(b.severity(), a.severity()));
		if (alerts.size() > MAX_ALERTS) {
			int hidden = alerts.size() - MAX_ALERTS;
			alerts = new ArrayList<>(alerts.subList(0, MAX_ALERTS));
			alerts.add(new OpsSnapshot.Alert(0, "...and " + hidden + " more"));
		}
		return new OpsSnapshot(facility.credits(), (float) facility.miningRate(), market.earnedSince(now - 20 * 60 * 60),
				market.totalEarned(), facility.activeEvent(), AirQuality.get(world).smogAt(viewer), market.outbox().size(),
				clusters, contracts, models, alerts);
	}

	private static final Map<RackStatus, String> STATUS_WORDS = Map.ofEntries(
			Map.entry(RackStatus.MINING, "mining"), Map.entry(RackStatus.THROTTLED, "throttled"),
			Map.entry(RackStatus.NETWORK_LIMITED, "bandwidth-limited"), Map.entry(RackStatus.CRAFTING, "autocrafting"),
			Map.entry(RackStatus.GENERATING, "generating"), Map.entry(RackStatus.TRAINING, "training"),
			Map.entry(RackStatus.EMPTY, "empty"), Map.entry(RackStatus.TRIPPED, "tripped"), Map.entry(RackStatus.NO_POWER, "unpowered"),
			Map.entry(RackStatus.NEEDS_CDU, "need a CDU"), Map.entry(RackStatus.NEEDS_WATER, "need liquid cooling"),
			Map.entry(RackStatus.OVERHEATED, "overheated"), Map.entry(RackStatus.NO_NETWORK, "offline"));

	private static boolean problem(RackStatus status) {
		return switch (status) {
			case TRIPPED, NO_POWER, NEEDS_CDU, NEEDS_WATER, OVERHEATED, NO_NETWORK -> true;
			default -> false;
		};
	}

	private static void machineAlerts(ServerWorld world, List<MachineBlockEntity> machines, List<OpsSnapshot.Alert> alerts) {
		// Machines on one loop, or cores of one reactor array, report the same figures: one alert each is enough.
		java.util.Set<String> reported = new java.util.HashSet<>();
		for (MachineBlockEntity machine : machines) {
			String at = " at " + machine.getPos().toShortString();
			switch (machine.blockId()) {
				case "server_rack" -> {
					RackStatus status = machine.rackStatus();
					if (problem(status)) alerts.add(new OpsSnapshot.Alert(2, "Rack" + at + ": " + STATUS_WORDS.get(status)));
					else if (status == RackStatus.THROTTLED || status == RackStatus.NETWORK_LIMITED) {
						alerts.add(new OpsSnapshot.Alert(1, "Rack" + at + ": " + STATUS_WORDS.get(status)));
					}
				}
				case "storage_array", "tape_library" -> {
					String name = machine.blockId().equals("storage_array") ? "Storage Array" : "Tape Library";
					if (machine.driveCount() > 0 && !machine.storageOnline()) {
						alerts.add(new OpsSnapshot.Alert(2, name + at + ": offline (power or heat)"));
					}
					for (int slot = 0; slot < machine.size(); slot++) {
						ItemStack stack = machine.getStack(slot);
						if (!(stack.getItem() instanceof DriveItem drive)) continue;
						var data = StorageState.get(world.getServer()).drive(DriveItem.idFor(stack), drive.capacity());
						double full = data.capacity() > 0 ? (double) data.used() / data.capacity() : 0;
						if (full >= 0.9) {
							alerts.add(new OpsSnapshot.Alert(full >= 0.99 ? 2 : 1, stack.getName().getString() + " in " + name + at
									+ " is " + Math.round(full * 100) + "% full"));
						}
					}
				}
				case "freshwater_pump" -> {
					FreshwaterCooling.PumpStatus status = FreshwaterCooling.PumpStatus.values()[
							Math.max(0, Math.min(FreshwaterCooling.PumpStatus.values().length - 1, machine.pumpStatus()))];
					String why = switch (status) {
						case PUMPING -> null;
						case NO_POWER -> "no power";
						case NO_WATER -> "not touching water";
						case SALT_WATER -> "salt water: needs a lake or river, not the ocean";
						case TOO_LITTLE_WATER -> "the lake has run dry (" + machine.pumpSources() + " source blocks left)";
					};
					if (why != null) alerts.add(new OpsSnapshot.Alert(2, "Freshwater Pump" + at + ": " + why));
				}
				case "cooling_tower", "dry_cooler", "chiller", "water_heat_exchanger", "crac_unit", "rear_door_cooler" -> {
					if (machine.loopHeatKw() > machine.loopCapacityKw() + 0.5
							&& reported.add("loop " + machine.loopHeatKw() + " " + machine.loopCapacityKw())) {
						alerts.add(new OpsSnapshot.Alert(1, "Coolant loop" + at + " overloaded: " + Math.round(machine.loopHeatKw())
								+ " kW in, sinks take " + Math.round(machine.loopCapacityKw()) + " kW"));
					}
				}
				case "modular_reactor" -> {
					if (machine.arrayFuelTicks() <= 0 && machine.arrayFuelCells() == 0
							&& (machine.reactorArraySize() == 1 || reported.add("reactor " + machine.reactorCapacityKw() + " " + machine.reactorArraySize()))) {
						alerts.add(new OpsSnapshot.Alert(1, "Modular Reactor" + at + ": out of Fuel Cells"));
					}
				}
				case "diesel_generator" -> {
					if (machine.fuelBurnTicks() <= 0 && machine.getStack(0).isEmpty()) {
						alerts.add(new OpsSnapshot.Alert(1, "Diesel Generator" + at + ": out of fuel"));
					}
				}
				case "art_table", "writing_desk" -> {
					String name = machine.blockId().equals("art_table") ? "Kids' Art Table" : "Scriptorium Desk";
					if (machine.workers() > 0 && (machine.getStack(0).isEmpty() || machine.getStack(1).isEmpty())) {
						alerts.add(new OpsSnapshot.Alert(1, name + at + ": out of " + (machine.getStack(0).isEmpty() ? "paper"
								: machine.blockId().equals("art_table") ? "crayons" : "ink")));
					}
				}
				default -> {}
			}
		}
		NetworkManager networks = NetworkManager.get(world);
		for (NetKind kind : NetKind.values()) {
			for (BlockPos cut : networks.cutCables(kind)) {
				alerts.add(new OpsSnapshot.Alert(2, "Cut " + kind.name().toLowerCase(Locale.ROOT) + " cable at " + cut.toShortString()));
			}
		}
		AirQuality air = AirQuality.get(world);
		air.levels().forEach((chunk, level) -> {
			if (level < AirQuality.HAZY) return;
			var pos = new net.minecraft.util.math.ChunkPos(chunk);
			alerts.add(new OpsSnapshot.Alert(level >= AirQuality.CHOKING ? 2 : 1, String.format(Locale.ROOT,
					"Smog %.0f around %d, %d", level, pos.getCenterX(), pos.getCenterZ())));
		});
	}
}
