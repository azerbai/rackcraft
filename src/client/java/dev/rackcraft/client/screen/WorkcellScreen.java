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
			"Working slowly: short of power"};
	private static final String[] DOCK_STATES = {"Drones out on jobs", "Standing by: nothing to fix in range",
			"Stopped: no power", "Jobs waiting, but no drones are home", "Out of hydrogen: load Hydrogen Canisters",
			"Jobs waiting that need spares: add rack modules or Repair Kits"};

	public WorkcellScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 176, 234);
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		switch (blockId()) {
			case "drone_dock" -> drawDock(context);
			case "belt_loader" -> drawLoader(context);
			case "belt_unloader" -> drawUnloader(context);
			case "storage_exporter" -> drawExporter(context);
			case "hydrogen_tank" -> drawTank(context);
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
		wrappedClamped(context, Text.literal(ARM_STATES[state]), 8, 30, 160, 2, color);
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
