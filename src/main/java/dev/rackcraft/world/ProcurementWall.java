package dev.rackcraft.world;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.sim.NetKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * The Procurement Wall: a Monitoring Wall that keeps the books for Site Planners. Plugged into a planner's power grid it
 * sees that planner's price quote and its ledger (every purchase its drones made at the Crypto Exchange), on its screen
 * and, within {@link #HUD_RANGE} blocks, as a HUD on the player's own.
 */
public final class ProcurementWall {
	public static final double WALL_KW = 0.5;
	public static final Identifier HUD = Rackcraft.id("procurement_hud");
	public static final int HUD_RANGE = 48;
	public static final int MAX_ROWS = 150;
	public static final int HUD_ROWS = 6;
	public static final int MAX_QUOTE_LINES = 10;

	private ProcurementWall() {}

	/** One line of a planner's quote. */
	public record QuoteView(String item, long count, long price) {}

	/** One planner the wall can see, and where its job stands. */
	public record PlannerView(BlockPos pos, int layout, int status, int phase, boolean running, boolean awaiting, long spent, long bought,
			long quoteTotal, List<QuoteView> quote) {}

	/** One purchase: which planner (an index into the snapshot's planners), what, how many, the price each, how long ago, and what for. */
	public record Row(int planner, String item, long count, long price, long age, String purpose) {
		public long cost() { return count * price; }
	}

	public record Snapshot(long credits, boolean powered, List<PlannerView> planners, List<Row> rows) {
		public static final Snapshot EMPTY = new Snapshot(0, false, List.of(), List.of());

		public void write(PacketByteBuf buf) {
			buf.writeVarLong(credits);
			buf.writeBoolean(powered);
			buf.writeVarInt(planners.size());
			for (PlannerView planner : planners) {
				buf.writeBlockPos(planner.pos());
				buf.writeVarInt(planner.layout());
				buf.writeVarInt(planner.status());
				buf.writeVarInt(planner.phase());
				buf.writeBoolean(planner.running());
				buf.writeBoolean(planner.awaiting());
				buf.writeVarLong(planner.spent());
				buf.writeVarLong(planner.bought());
				buf.writeVarLong(planner.quoteTotal());
				buf.writeVarInt(planner.quote().size());
				for (QuoteView line : planner.quote()) {
					buf.writeString(line.item(), 96);
					buf.writeVarLong(line.count());
					buf.writeVarLong(line.price());
				}
			}
			buf.writeVarInt(rows.size());
			for (Row row : rows) writeRow(buf, row);
		}

		public static Snapshot read(PacketByteBuf buf) {
			long credits = buf.readVarLong();
			boolean powered = buf.readBoolean();
			List<PlannerView> planners = new ArrayList<>();
			for (int count = buf.readVarInt(), index = 0; index < count; index++) {
				BlockPos pos = buf.readBlockPos();
				int layout = buf.readVarInt();
				int status = buf.readVarInt();
				int phase = buf.readVarInt();
				boolean running = buf.readBoolean();
				boolean awaiting = buf.readBoolean();
				long spent = buf.readVarLong();
				long bought = buf.readVarLong();
				long quoteTotal = buf.readVarLong();
				List<QuoteView> quote = new ArrayList<>();
				for (int lines = buf.readVarInt(), line = 0; line < lines; line++) quote.add(new QuoteView(buf.readString(96), buf.readVarLong(), buf.readVarLong()));
				planners.add(new PlannerView(pos, layout, status, phase, running, awaiting, spent, bought, quoteTotal, quote));
			}
			List<Row> rows = new ArrayList<>();
			for (int count = buf.readVarInt(), index = 0; index < count; index++) rows.add(readRow(buf));
			return new Snapshot(credits, powered, planners, rows);
		}
	}

	private static void writeRow(PacketByteBuf buf, Row row) {
		buf.writeVarInt(row.planner());
		buf.writeString(row.item(), 96);
		buf.writeVarLong(row.count());
		buf.writeVarLong(row.price());
		buf.writeVarLong(row.age());
		buf.writeString(row.purpose(), 24);
	}

	private static Row readRow(PacketByteBuf buf) {
		return new Row(buf.readVarInt(), buf.readString(96), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readString(24));
	}

	public static boolean online(MachineBlockEntity wall) {
		return wall.powerSatisfaction() >= 0.5;
	}

	/** The Site Planners on the wall's power grid, in a stable order. */
	public static List<MachineBlockEntity> planners(ServerWorld world, List<MachineBlockEntity> machines, BlockPos wall) {
		Set<BlockPos> grid = NetworkManager.get(world).component(wall, NetKind.POWER);
		List<MachineBlockEntity> planners = new ArrayList<>();
		for (MachineBlockEntity machine : machines) {
			if (machine.blockId().equals("site_planner") && grid.contains(machine.getPos())) planners.add(machine);
		}
		planners.sort(Comparator.comparingLong(machine -> machine.getPos().asLong()));
		return planners;
	}

	public static Snapshot snapshot(ServerWorld world, BlockPos wall) {
		List<MachineBlockEntity> machines = SimTicker.machines(world);
		boolean powered = machines.stream().anyMatch(machine -> machine.getPos().equals(wall) && online(machine));
		List<MachineBlockEntity> planners = planners(world, machines, wall);
		long now = world.getTime();
		List<PlannerView> views = new ArrayList<>();
		List<Row> rows = new ArrayList<>();
		for (int index = 0; index < planners.size(); index++) {
			MachineBlockEntity planner = planners.get(index);
			List<QuoteView> quote = new ArrayList<>();
			for (SitePlanner.QuoteLine line : SitePlanner.quoteLines(planner)) {
				if (quote.size() < MAX_QUOTE_LINES) quote.add(new QuoteView(Registries.ITEM.getId(line.item()).toString(), line.count(), line.price()));
			}
			views.add(new PlannerView(planner.getPos(), SitePlanner.layout(planner).ordinal(), planner.processStatus(),
					planner.siteReading(SitePlanner.R_PHASE), SitePlanner.running(planner), SitePlanner.awaiting(planner),
					planner.site().getLong("Spent"), planner.site().getLong("Bought"), SitePlanner.quoteTotal(planner), quote));
			for (SitePlanner.Purchase purchase : SitePlanner.ledger(planner)) {
				rows.add(new Row(index, purchase.item(), purchase.count(), purchase.price(), Math.max(0, now - purchase.tick()), purchase.purpose()));
			}
		}
		rows.sort(Comparator.comparingLong(Row::age));
		if (rows.size() > MAX_ROWS) rows = new ArrayList<>(rows.subList(0, MAX_ROWS));
		return new Snapshot(FacilityManager.get(world).credits(), powered, views, rows);
	}

	// ---------------------------------------------------------------- the HUD

	/** What the HUD shows: the fleet's spending, any quote waiting on a yes, and the latest few purchases. */
	public record Hud(boolean show, long spent, long pending, int planners, int working, int awaiting, List<Row> recent) {
		public static final Hud HIDDEN = new Hud(false, 0, 0, 0, 0, 0, List.of());

		public void write(PacketByteBuf buf) {
			buf.writeBoolean(show);
			if (!show) return;
			buf.writeVarLong(spent);
			buf.writeVarLong(pending);
			buf.writeVarInt(planners);
			buf.writeVarInt(working);
			buf.writeVarInt(awaiting);
			buf.writeVarInt(recent.size());
			for (Row row : recent) writeRow(buf, row);
		}

		public static Hud read(PacketByteBuf buf) {
			if (!buf.readBoolean()) return HIDDEN;
			long spent = buf.readVarLong();
			long pending = buf.readVarLong();
			int planners = buf.readVarInt();
			int working = buf.readVarInt();
			int awaiting = buf.readVarInt();
			List<Row> recent = new ArrayList<>();
			for (int count = buf.readVarInt(), index = 0; index < count; index++) recent.add(readRow(buf));
			return new Hud(true, spent, pending, planners, working, awaiting, recent);
		}
	}

	public static Hud hud(ServerWorld world, List<MachineBlockEntity> machines, BlockPos wall) {
		List<MachineBlockEntity> planners = planners(world, machines, wall);
		long now = world.getTime();
		long spent = 0;
		long pending = 0;
		int working = 0;
		int awaiting = 0;
		List<Row> recent = new ArrayList<>();
		for (MachineBlockEntity planner : planners) {
			spent += planner.site().getLong("Spent");
			if (SitePlanner.awaiting(planner)) {
				awaiting++;
				pending += SitePlanner.quoteTotal(planner);
			}
			if (SitePlanner.running(planner) && planner.processStatus() == SitePlanner.Status.WORKING.ordinal()) working++;
			for (SitePlanner.Purchase purchase : SitePlanner.ledger(planner)) {
				recent.add(new Row(0, purchase.item(), purchase.count(), purchase.price(), Math.max(0, now - purchase.tick()), purchase.purpose()));
			}
		}
		recent.sort(Comparator.comparingLong(Row::age));
		if (recent.size() > HUD_ROWS) recent = new ArrayList<>(recent.subList(0, HUD_ROWS));
		return new Hud(true, spent, pending, planners.size(), working, awaiting, recent);
	}

	/** Once a second: tell each player what the nearest powered Procurement Wall (within 48 blocks) has on its books. */
	public static void sendHud(ServerWorld world, List<MachineBlockEntity> machines) {
		List<MachineBlockEntity> walls = machines.stream().filter(machine -> machine.blockId().equals("procurement_wall") && online(machine)).toList();
		Map<BlockPos, Hud> views = new HashMap<>();
		for (ServerPlayerEntity player : world.getPlayers()) {
			MachineBlockEntity nearest = null;
			double best = HUD_RANGE * HUD_RANGE;
			for (MachineBlockEntity wall : walls) {
				double distance = wall.getPos().getSquaredDistance(player.getPos());
				if (distance <= best) {
					best = distance;
					nearest = wall;
				}
			}
			if (nearest == null && !hadHud(player)) continue;
			Hud view = nearest == null ? Hud.HIDDEN : views.computeIfAbsent(nearest.getPos(), pos -> hud(world, machines, pos));
			remember(player, view.show());
			PacketByteBuf buf = PacketByteBufs.create();
			view.write(buf);
			ServerPlayNetworking.send(player, HUD, buf);
		}
	}

	// Players who were last sent a visible HUD, so a hide is sent exactly once when they walk away.
	private static final Set<java.util.UUID> SHOWING = new java.util.HashSet<>();

	private static boolean hadHud(ServerPlayerEntity player) {
		return SHOWING.contains(player.getUuid());
	}

	private static void remember(ServerPlayerEntity player, boolean showing) {
		if (showing) SHOWING.add(player.getUuid());
		else SHOWING.remove(player.getUuid());
	}
}
