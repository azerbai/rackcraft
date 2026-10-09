package dev.rackcraft.client.screen;

import dev.rackcraft.block.BeltBlockEntity;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import dev.rackcraft.world.AssemblyLine;
import dev.rackcraft.world.DroneDocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * The Assembly Line's robots and the Drone Dock: what the machine is doing and why, what is on the belt in front of
 * an arm, and for the Assembly Robot and the dock, a row of nine slots for parts, drones, hydrogen and spares.
 */
public final class WorkcellScreen extends RackcraftHandledScreen {
	private static final String[] ARM_STATES = {"Working", "Waiting for a workpiece that needs it",
			"No parts loaded: stock the slots below", "Stopped: not enough power (under 10%)", "Not facing a Conveyor Belt",
			"Working slowly: short of power", "Locked: needs research"};
	private static final String[] CRYOSTAT_STATES = {"Cooling", "Standing by: cold, no Quantum Annealers running beside it",
			"Out of hydrogen: load Hydrogen Canisters", "Stopped: no power"};
	private static final String[] DOCK_STATES = {"Drones out on jobs", "Standing by: nothing to fix in range",
			"Stopped: no power", "Jobs waiting, but no drones are home", "Out of hydrogen: load Hydrogen Canisters",
			"Jobs waiting that need spares: add rack modules or Repair Kits"};

	public WorkcellScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 176, 234);
	}

	@Override
	protected void init() {
		super.init();
		if (!blockId().equals("pod_port")) return;
		int left = (width - backgroundWidth) / 2;
		int top = (height - backgroundHeight) / 2;
		addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget.builder(Text.literal("Fill pod"), button -> click(MachineScreenHandler.POD_FILL_BUTTON))
				.dimensions(left + 8, top + 86, 76, 14).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
						"Fill every empty bay in every rack of the pod, from the storage on this port's fiber network, then from your inventory."))).build());
		addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget.builder(Text.literal("Empty pod"), button -> click(MachineScreenHandler.POD_EMPTY_BUTTON))
				.dimensions(left + 92, top + 86, 76, 14).tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
						"Take every module out of every rack in the pod, into storage if there is room, else your inventory."))).build());
	}

	private void click(int button) {
		if (client != null && client.interactionManager != null) client.interactionManager.clickButton(handler.syncId, button);
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		switch (blockId()) {
			case "pod_port" -> drawPod(context);
			case "teleport_pad" -> drawPad(context);
			case "drone_dock" -> drawDock(context);
			case "belt_loader" -> drawLoader(context);
			case "belt_unloader" -> drawUnloader(context);
			case "storage_exporter" -> drawExporter(context);
			case "hydrogen_tank" -> drawTank(context);
			case "cryostat" -> drawCryostat(context);
			default -> drawArm(context);
		}
	}

	private void drawArm(DrawContext context) {
		AssemblyLine.Kind kind = AssemblyLine.kind(blockId());
		int state = Math.max(0, Math.min(ARM_STATES.length - 1, stat(Stat.PROCESS_STATUS)));
		int color = switch (AssemblyLine.Status.values()[state]) {
			case WORKING -> GOOD;
			case WAITING -> MUTED;
			case LOW_POWER, NO_BELT -> WARN;
			default -> BAD;
		};
		String headline = ARM_STATES[state];
		if (AssemblyLine.Status.values()[state] == AssemblyLine.Status.LOCKED) {
			String needs = AssemblyLine.candidates(workpiece()).stream().map(AssemblyLine.Recipe::research)
					.filter(java.util.Objects::nonNull).findFirst().orElse(null);
			if (needs != null) headline = "Locked: needs " + dev.rackcraft.compute.Research.get(needs).name() + " research";
		}
		wrappedClamped(context, Text.literal(headline), 8, 30, 160, 2, color);
		if (kind != null) {
			lineFit(context, kind.verb + "s in " + (int) kind.seconds + " s at " + kw((int) Math.round(kind.kw * 10)) + " while working",
					8, 52, 160, MUTED);
		}
		lineFit(context, "Drawing " + kw(stat(Stat.POWER)) + ", grid covers " + stat(Stat.SATISFACTION) + "%", 8, 63, 160, TEXT);
		bar(context, 8, 76, 160, stat(Stat.WORK_PROGRESS) / 100.0, GOOD);
		ItemStack piece = workpiece();
		if (piece.isEmpty()) {
			lineFit(context, "Belt in front: empty", 8, 88, 160, MUTED);
		} else {
			lineFit(context, "On the belt: " + piece.getName().getString(), 8, 88, 160, TEXT);
			String detail = AssemblyLine.nextDescription(piece);
			lineFit(context, detail, 8, 99, 160, MUTED);
		}
		if (blockId().equals("assembly_arm")) line(context, "Parts (" + stat(Stat.ITEMS_MADE) + " steps done)", 8, 105, MUTED);
		else line(context, "Steps done: " + stat(Stat.ITEMS_MADE), 8, 110, MUTED);
	}

	private void drawDock(DrawContext context) {
		int state = Math.max(0, Math.min(DOCK_STATES.length - 1, stat(Stat.PROCESS_STATUS)));
		int color = switch (DroneDocks.Status.values()[state]) {
			case WORKING -> GOOD;
			case IDLE -> MUTED;
			case NO_DRONES, NO_SPARES -> WARN;
			default -> BAD;
		};
		wrappedClamped(context, Text.literal(DOCK_STATES[state]), 8, 30, 160, 2, color);
		int home = handler.getSlot(DroneDocks.DRONE_SLOT).getStack().getCount();
		int canisters = handler.getSlot(DroneDocks.FUEL_SLOT).getStack().getCount();
		line(context, "Drones home " + home + ", out " + stat(Stat.WORKERS), 8, 52, TEXT);
		int trips = stat(Stat.TOOL_USES) + canisters * DroneDocks.TRIPS_PER_CANISTER;
		line(context, "Fuel for " + trips + (trips == 1 ? " trip" : " trips"), 8, 63, trips > 0 ? TEXT : BAD);
		line(context, "Jobs in range: " + stat(Stat.DOCK_JOBS) + "   Fixed: " + stat(Stat.ITEMS_MADE), 8, 74, TEXT);
		int wearing = stat(Stat.SITE + DroneDocks.R_WEARING);
		lineFit(context, wearing == 0 ? "Reaches " + DroneDocks.RANGE + " blocks in every direction"
				: "Most worn: " + stat(Stat.SITE + DroneDocks.R_MOST_WORN) + "% (drone at "
						+ Math.round(dev.rackcraft.world.Renewables.SERVICE_AT * 100) + "%)", 8, 85, 160, MUTED);
		// The slots are too narrow to label one by one.
		lineFit(context, "Slots: drones, Hydrogen Canisters,", 8, 96, 160, MUTED);
		lineFit(context, "then spare modules and Repair Kits", 8, 105, 160, MUTED);
	}

	private void drawLoader(DrawContext context) {
		ItemStack sample = handler.getSlot(0).getStack();
		boolean belt = !(workpieceBelt() == null);
		String state = !belt ? "Not facing a Conveyor Belt" : sample.isEmpty() ? "Put a sample of what to load in the slot below"
				: "Loading " + sample.getName().getString() + " from storage";
		wrappedClamped(context, Text.literal(state), 8, 30, 160, 2, !belt ? WARN : sample.isEmpty() ? MUTED : GOOD);
		lineFit(context, "Loaded so far: " + stat(Stat.ITEMS_MADE), 8, 56, 160, TEXT);
		wrapped(context, Text.literal("Needs an Item Pipe to a Storage Array. It puts one on the belt whenever the belt is empty; the sample stays."),
				8, 70, 160, MUTED);
		line(context, "Sample", 8, 105, MUTED);
	}

	private void drawUnloader(DrawContext context) {
		boolean waiting = false;
		for (int slot = 0; slot < 9; slot++) waiting |= !handler.getSlot(slot).getStack().isEmpty();
		wrappedClamped(context, Text.literal(waiting ? "Holding items: is it on an Item Pipe that reaches storage?"
				: "Ready: filing everything the belt brings into storage"), 8, 30, 160, 2, waiting ? WARN : GOOD);
		wrapped(context, Text.literal("Point the last Conveyor Belt into it. Everything goes into storage once a second; hoppers can empty it too."),
				8, 56, 160, MUTED);
		line(context, "Buffer", 8, 105, MUTED);
	}

	private void drawTank(DrawContext context) {
		if (stat(Stat.SITE + dev.rackcraft.world.HydrogenTanks.R_LOCKED) != 0) {
			wrappedClamped(context, Text.literal("Locked: research Cryogenic Hydrogen Storage at the Operations Terminal"), 8, 30, 160, 2, BAD);
			wrapped(context, Text.literal("Until then it holds nothing, and Electrolysers make canisters instead."), 8, 56, 160, MUTED);
			return;
		}
		int stored = stat(Stat.SITE + dev.rackcraft.world.HydrogenTanks.R_STORED);
		int capacity = Math.max(1, stat(Stat.SITE + dev.rackcraft.world.HydrogenTanks.R_CAPACITY));
		lineFit(context, String.format(java.util.Locale.ROOT, "Hydrogen: %,d of %,d canisters", stored, capacity), 8, 30, 160, stored > 0 ? GOOD : MUTED);
		bar(context, 8, 42, 160, stored / (double) capacity, 0xFF7FB8E8);
		int edge = stat(Stat.ARRAY_EDGE);
		lineFit(context, edge >= 2 ? "A " + edge + "x" + edge + "x" + edge + " tank" : "One block: a cube holds more per block", 8, 54, 160, MUTED);
		int home = handler.getSlot(0).getStack().getCount();
		lineFit(context, "Tankers home " + home + ", out " + stat(Stat.SITE + dev.rackcraft.world.HydrogenTanks.R_TANKERS_OUT), 8, 66, 160, TEXT);
		wrapped(context, Text.literal("Tankers top up docks, planners and Launch Controls within "
				+ dev.rackcraft.world.HydrogenTanks.TANKER_RANGE + " blocks."), 8, 78, 160, MUTED);
		line(context, "Tanker Drones", 8, 105, MUTED);
	}

	private void drawPad(DrawContext context) {
		var states = dev.rackcraft.world.TeleportPads.State.values();
		var shown = states[Math.max(0, Math.min(states.length - 1, stat(Stat.SITE + dev.rackcraft.world.TeleportPads.R_STATE)))];
		String headline = switch (shown) {
			case LOCKED -> "Locked: research Quantum Entanglement at the Operations Terminal";
			case UNLINKED -> "Not entangled: use a Linked Shard on this pad and then another";
			case NO_ANNEALER -> "Needs a Quantum Annealer in the slot below";
			case NO_CRYOSTAT -> "Needs a cold Cryostat touching it, with hydrogen";
			case NO_POWER -> "Not enough power on the grid";
			case PARTNER_GONE -> "The other pad is gone";
			case READY -> "Ready: stand on it for two seconds";
		};
		wrappedClamped(context, Text.literal(headline), 8, 30, 160, 2, shown == dev.rackcraft.world.TeleportPads.State.READY ? GOOD
				: shown == dev.rackcraft.world.TeleportPads.State.UNLINKED ? MUTED : WARN);
		int distance = stat(Stat.SITE + dev.rackcraft.world.TeleportPads.R_DISTANCE);
		if (distance > 0) {
			boolean cross = stat(Stat.SITE + dev.rackcraft.world.TeleportPads.R_CROSS) != 0;
			lineFit(context, "Other pad at " + stat(Stat.SITE + dev.rackcraft.world.TeleportPads.R_PARTNER_X) + ", "
					+ stat(Stat.SITE + dev.rackcraft.world.TeleportPads.R_PARTNER_Y) + ", " + stat(Stat.SITE + dev.rackcraft.world.TeleportPads.R_PARTNER_Z)
					+ (cross ? " (other dimension)" : ""), 8, 56, 160, TEXT);
			lineFit(context, String.format(java.util.Locale.ROOT, "A jump costs %,d kW for two seconds", stat(Stat.SITE + dev.rackcraft.world.TeleportPads.R_KW)),
					8, 67, 160, MUTED);
		}
		lineFit(context, "Draws " + kw(stat(Stat.POWER)) + ", grid covers " + stat(Stat.SATISFACTION) + "%", 8, 78, 160, MUTED);
		line(context, "Quantum Annealer", 8, 105, MUTED);
	}

	private void drawPod(DrawContext context) {
		int state = stat(Stat.SITE + dev.rackcraft.world.ComputePods.R_STATE);
		var states = dev.rackcraft.world.ComputePods.State.values();
		var shown = states[Math.max(0, Math.min(states.length - 1, state))];
		String headline = switch (shown) {
			case LOCKED -> "Locked: research Compute Pods at the Operations Terminal";
			case NO_POD -> "No pod: touch a solid block of at least four High-Density Racks (or better) with this port";
			case FABRIC_OFF -> "Fused, fabric off: needs " + stat(Stat.SITE + dev.rackcraft.world.ComputePods.R_NEED)
					+ " Photonic Interconnects below (has " + stat(Stat.SITE + dev.rackcraft.world.ComputePods.R_HAVE) + ")";
			case ACTIVE -> "Pod running: +" + stat(Stat.SITE + dev.rackcraft.world.ComputePods.R_BONUS) + "% AI compute";
			case TRIPPED -> "Breaker tripped: a rack overheated. It resets when every rack is under "
					+ (int) dev.rackcraft.world.ComputePods.RESET_C + " C";
		};
		wrappedClamped(context, Text.literal(headline), 8, 30, 160, 3, shown == dev.rackcraft.world.ComputePods.State.ACTIVE ? GOOD
				: shown == dev.rackcraft.world.ComputePods.State.TRIPPED ? BAD : shown == dev.rackcraft.world.ComputePods.State.FABRIC_OFF ? WARN : MUTED);
		int racks = stat(Stat.SITE + dev.rackcraft.world.ComputePods.R_RACKS);
		if (racks > 0) {
			lineFit(context, racks + " racks, " + stat(Stat.SITE + dev.rackcraft.world.ComputePods.R_BAYS_USED) + " of "
					+ stat(Stat.SITE + dev.rackcraft.world.ComputePods.R_BAYS) + " bays used", 8, 62, 160, TEXT);
		}
		lineFit(context, "Draws " + kw(stat(Stat.POWER)) + ", grid covers " + stat(Stat.SATISFACTION) + "%", 8, 74, 160, MUTED);
		line(context, "Interconnects (one per four racks)", 8, 105, MUTED);
	}

	private void drawCryostat(DrawContext context) {
		int state = Math.max(0, Math.min(CRYOSTAT_STATES.length - 1, stat(Stat.PROCESS_STATUS)));
		int color = state == 0 ? GOOD : state == 1 ? MUTED : BAD;
		String headline = state == 0 ? "Cooling " + stat(Stat.WORKERS) + (stat(Stat.WORKERS) == 1 ? " Quantum Annealer" : " Quantum Annealers")
				: CRYOSTAT_STATES[state];
		wrappedClamped(context, Text.literal(headline), 8, 30, 160, 2, color);
		int canisters = handler.getSlot(0).getStack().getCount();
		lineFit(context, "Hydrogen Canisters: " + canisters + "   Boiled off: " + stat(Stat.ITEMS_MADE), 8, 52, 160, canisters > 0 ? TEXT : BAD);
		bar(context, 8, 64, 160, 1 - stat(Stat.WORK_PROGRESS) / 100.0, 0xFF7FB8E8);
		wrappedClamped(context, Text.literal("Touch a rack of Quantum Annealers. Each running one uses a canister per "
				+ (int) (dev.rackcraft.world.Cryostats.SECONDS_PER_CANISTER / 60) + " min."), 8, 74, 160, 3, MUTED);
		line(context, "Hydrogen", 8, 105, MUTED);
	}

	private void drawExporter(DrawContext context) {
		boolean samples = false;
		for (int slot = 0; slot < 9; slot++) samples |= !handler.getSlot(slot).getStack().isEmpty();
		boolean target = false;
		if (client != null && client.world != null) {
			var state = client.world.getBlockState(handler.pos());
			if (state.contains(MachineBlock.FACING)) target = client.world.getBlockEntity(handler.pos().offset(state.get(MachineBlock.FACING)))
					instanceof net.minecraft.inventory.Inventory;
		}
		String headline = !target ? "Not facing a machine or container" : !samples ? "Put samples of what to send in the slots below"
				: "Keeping the block in front stocked from storage";
		wrappedClamped(context, Text.literal(headline), 8, 30, 160, 2, !target ? WARN : !samples ? MUTED : GOOD);
		lineFit(context, "Sent so far: " + stat(Stat.ITEMS_MADE), 8, 56, 160, TEXT);
		wrapped(context, Text.literal("On an Item Pipe to storage, it tops the block it faces up to a stack of each sample, once a second. Samples stay."),
				8, 68, 160, MUTED);
		line(context, "Samples", 8, 105, MUTED);
	}

	/** The belt a loader or arm faces, or null. */
	private BlockPos workpieceBelt() {
		if (client == null || client.world == null) return null;
		var state = client.world.getBlockState(handler.pos());
		if (!state.contains(MachineBlock.FACING)) return null;
		BlockPos belt = handler.pos().offset(state.get(MachineBlock.FACING));
		return client.world.getBlockEntity(belt) instanceof BeltBlockEntity ? belt : null;
	}

	/** The item on the belt this arm faces, read from the client's copy of the world. */
	private ItemStack workpiece() {
		if (client == null || client.world == null) return ItemStack.EMPTY;
		var state = client.world.getBlockState(handler.pos());
		if (!state.contains(MachineBlock.FACING)) return ItemStack.EMPTY;
		BlockPos belt = handler.pos().offset(state.get(MachineBlock.FACING));
		return client.world.getBlockEntity(belt) instanceof BeltBlockEntity entity ? entity.stack() : ItemStack.EMPTY;
	}
}
