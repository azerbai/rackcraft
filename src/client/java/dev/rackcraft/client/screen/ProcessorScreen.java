package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/**
 * The processing cubes (nuclear machines, Wafer Fab, Silicon Foundry, E-Waste Recycler, Electrolyser): inputs, outputs, what the
 * cube is doing and what it needs, and on the port core, a button that empties every product in the cube.
 */
public final class ProcessorScreen extends RackcraftHandledScreen {
	private static final String[] STATES = {"Running", "Not formed: build a solid cube of 2x2x2 to 5x5x5",
			"Idle: waiting for input", "Stopped: output full, empty the port", "Stopped: not enough power (under 10%)",
			"Locked: needs EUV Lithography research", "Running slowly: short of power",
			"Stopped: needs water against the cube"};

	public ProcessorScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 176, 252);
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		int state = Math.max(0, Math.min(STATES.length - 1, stat(Stat.PROCESS_STATUS)));
		int color = switch (state) {
			case 0 -> GOOD;
			case 2 -> MUTED;
			case 1, 5, 6 -> WARN;
			default -> BAD;
		};
		wrappedClamped(context, Text.literal(STATES[state]), 8, 30, 160, 1, color);
		int edge = Math.max(1, stat(Stat.ARRAY_EDGE));
		line(context, edge > 1 ? "Array " + edge + "x" + edge + "x" + edge + ": " + edge * edge * edge + " cores, " + kw(stat(Stat.POWER))
				: "Single block: not a multiblock yet", 8, 41, edge > 1 ? TEXT : MUTED);
		line(context, "In", 17, 52, MUTED);
		line(context, "Out", 117, 52, MUTED);
		bar(context, 64, 67, 46, stat(Stat.WORK_PROGRESS) % 100 / 100.0, GOOD);
		if (edge > 1) {
			String power = String.format(Locale.ROOT, "Needs %s, grid covers %d%%", kw(stat(Stat.CUBE_DEMAND)), stat(Stat.SATISFACTION));
			lineFit(context, power, 8, 84, 160, stat(Stat.SATISFACTION) >= 100 ? MUTED : WARN);
		}
		wrappedClamped(context, Text.literal(recipe(blockId())), 8, 96, 160, 3, MUTED);
		CubePort.draw(this, context, 8, 128);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (CubePort.click(this, mouseX - x, mouseY - y)) return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private static String recipe(String id) {
		return switch (id) {
			case "uranium_mill" -> "2 Raw Uranium -> Yellowcake. 30 s per batch per core, 20 kW.";
			case "gas_centrifuge" -> "6 Yellowcake -> 1 Enriched + 4 Depleted Uranium. 120 s per batch per core, 60 kW.";
			case "fuel_fabricator" -> "Enriched Uranium + 2 Steel Ingots -> Fuel Cell. 60 s per batch per core, 30 kW.";
			case "cask_sealer" -> "4 Spent Fuel + 4 Depleted Uranium -> Sealed Waste Cask. 60 s per batch per core, 15 kW.";
			case "wafer_fab" -> "16 Silicon + 4 GPU Chips -> Wafer-Scale Engine. 5 min per batch per core, 400 kW.";
			case "silicon_foundry" -> "2 Quartz + 4 Sand -> 8 Silicon. 20 s per batch per core, 40 kW.";
			case "ewaste_recycler" -> "Failed Module -> 3 Silicon + Copper Wire. 15 s per batch per core, 10 kW.";
			case "electrolyser" -> "Aluminium Ingot + water -> Hydrogen Canister. 30 s per batch per core, 4,000 kW.";
			default -> "";
		};
	}
}
