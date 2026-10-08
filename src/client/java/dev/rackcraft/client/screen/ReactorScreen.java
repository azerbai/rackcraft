package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/** Modular Reactor: fuel in, Spent Fuel out, and what the whole array is doing. */
public final class ReactorScreen extends RackcraftHandledScreen {
	private static final String[] STATES = {"Running: supplying the grid", "No fuel: load Fuel Cells",
			"Stopped: waste full, empty the port", "Standby: no demand on its grid"};

	public ReactorScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 176, 252);
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		int state = Math.max(0, Math.min(STATES.length - 1, stat(Stat.PROCESS_STATUS)));
		lineFit(context, STATES[state], 8, 30, 160, state == 0 ? GOOD : state == 2 ? BAD : state == 1 ? WARN : MUTED);
		line(context, "Fuel", 15, 45, MUTED);
		line(context, "Waste", 40, 45, MUTED);

		int capacity = Math.max(5000, stat(Stat.SOURCE_CAPACITY));
		int output = stat(Stat.POWER);
		line(context, "Output", 74, 45, MUTED);
		bar(context, 74, 55, 94, output / (double) capacity, GOOD);
		line(context, kw(output) + " / " + kw(capacity), 74, 64, TEXT);
		int fuel = stat(Stat.FUEL);
		line(context, "Current cell", 74, 78, MUTED);
		bar(context, 74, 88, 94, fuel / (double) Math.max(1, stat(Stat.FUEL_TOTAL)), WARN);
		lineFit(context, String.format(Locale.ROOT, "%d:%02d left at full", fuel / 1200, fuel / 20 % 60), 74, 97, 94, TEXT);
		lineFit(context, String.format(Locale.ROOT, "%,d cells spare", stat(Stat.FUEL_CELLS)), 74, 108, 94, MUTED);

		int edge = Math.max(1, stat(Stat.ARRAY_EDGE));
		line(context, edge > 1 ? "Array " + edge + "x" + edge + "x" + edge : "Single core", 8, 80, edge > 1 ? GOOD : MUTED);
		line(context, edge > 1 ? "-" + 5 * (edge - 1) + "% fuel" : "Cube: 2 to 5", 8, 91, MUTED);
		CubePort.draw(this, context, 8, 128);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (CubePort.click(this, mouseX - x, mouseY - y)) return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}
}
