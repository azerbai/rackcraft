package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/** Uranium Mill, Gas Centrifuge, Fuel Fabricator and Cask Sealer: inputs, outputs and what the cube is doing. */
public final class ProcessorScreen extends RackcraftHandledScreen {
	private static final String[] STATES = {"Running", "Not formed: build a solid cube of 2x2x2 to 5x5x5",
			"Idle: waiting for input", "Stopped: output full, empty it", "Stopped: needs power"};

	public ProcessorScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 176, 214);
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		int state = Math.max(0, Math.min(STATES.length - 1, stat(Stat.PROCESS_STATUS)));
		wrappedClamped(context, Text.literal(STATES[state]), 8, 30, 160, 1, state == 0 ? GOOD : state == 2 ? MUTED : state == 1 ? WARN : BAD);
		int edge = Math.max(1, stat(Stat.ARRAY_EDGE));
		line(context, edge > 1 ? "Array " + edge + "x" + edge + "x" + edge + ": " + edge * edge * edge + " cores, " + kw(stat(Stat.POWER))
				: "Single block: not a multiblock yet", 8, 41, edge > 1 ? TEXT : MUTED);
		line(context, "In", 17, 52, MUTED);
		line(context, "Out", 117, 52, MUTED);
		bar(context, 64, 67, 46, stat(Stat.WORK_PROGRESS) % 100 / 100.0, GOOD);
		wrapped(context, Text.literal(recipe(blockId())), 8, 86, 160, MUTED);
	}

	private static String recipe(String id) {
		return switch (id) {
			case "uranium_mill" -> "Raw Uranium -> Yellowcake. 10 s per batch per core.";
			case "gas_centrifuge" -> "4 Yellowcake -> 1 Enriched + 3 Depleted Uranium. 30 s per batch per core.";
			case "fuel_fabricator" -> "Enriched Uranium + Steel Ingot -> 2 Fuel Cells. 20 s per batch per core.";
			case "cask_sealer" -> "4 Spent Fuel + 4 Depleted Uranium -> Sealed Waste Cask. 30 s per batch per core.";
			default -> "";
		};
	}
}
