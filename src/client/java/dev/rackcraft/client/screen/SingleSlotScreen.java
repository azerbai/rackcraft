package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/** Diesel generator, modular reactor and fire suppression tank: one input slot plus a live dashboard. */
public final class SingleSlotScreen extends RackcraftHandledScreen {
	public SingleSlotScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 176, 200);
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		String id = blockId();
		if (id.equals("fire_suppression_tank")) {
			line(context, "Canister", 66, 34, MUTED);
			wrapped(context, Text.translatable("screen.rackcraft.suppression_hint"), 8, 68, 160, MUTED);
			return;
		}
		boolean diesel = id.equals("diesel_generator");
		int capacityTenths = diesel ? 400 : Math.max(5000, stat(Stat.SOURCE_CAPACITY));
		int output = stat(Stat.POWER);
		int fuel = stat(Stat.FUEL);
		int spinup = stat(Stat.SPINUP);
		boolean fuelled = fuel > 0 || !handler.getSlot(0).getStack().isEmpty() || !diesel && stat(Stat.FUEL_CELLS) > 0;

		Text state;
		int color;
		if (output > 0) {
			state = Text.translatable("generator.rackcraft.running");
			color = GOOD;
		} else if (!fuelled) {
			state = Text.translatable(diesel ? "generator.rackcraft.no_fuel" : "generator.rackcraft.no_fuel_cell");
			color = BAD;
		} else if (diesel && spinup > 0 && spinup < 20) {
			state = Text.translatable("generator.rackcraft.spinning_up", spinup * 5);
			color = WARN;
		} else {
			state = Text.translatable("generator.rackcraft.standby");
			color = MUTED;
		}
		context.drawText(textRenderer, state, 8, 30, color, false);

		line(context, "Output", 8, 44, MUTED);
		bar(context, 8, 54, 64, output / (double) capacityTenths, GOOD);
		line(context, kw(output), 8, 63, TEXT);
		line(context, "Fuel", 104, 44, MUTED);
		int total = Math.max(1, stat(Stat.FUEL_TOTAL));
		bar(context, 104, 54, 64, fuel / (double) total, WARN);
		line(context, diesel ? fuel / 20 + " s left" : stat(Stat.FUEL_CELLS) + " cells spare", 104, 63, TEXT);
		if (diesel) {
			line(context, "Spin-up", 104, 76, MUTED);
			bar(context, 104, 86, 64, spinup / 20.0, 0xFF5BA7E0);
		} else {
			// A lone reactor; a solid 2x2x2 to 5x5x5 cube of them runs as one array.
			int edge = Math.max(1, stat(Stat.ARRAY_EDGE));
			line(context, edge > 1 ? "Array " + edge + "x" + edge + "x" + edge : "Single core", 104, 76, edge > 1 ? GOOD : MUTED);
			if (edge > 1) line(context, (100 - Math.round(5 * (edge - 1))) + "% fuel/core", 104, 87, MUTED);
		}
		int delivered = stat(Stat.NETWORK_DELIVERED);
		int demand = stat(Stat.NETWORK_DEMAND);
		line(context, "Grid " + kw(delivered) + " / " + kw(demand), 8, 76, TEXT);
		line(context, "Capacity " + kw(stat(Stat.NETWORK_CAPACITY)), 8, 87, MUTED);
	}
}
