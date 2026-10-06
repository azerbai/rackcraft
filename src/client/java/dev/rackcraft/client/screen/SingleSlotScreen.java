package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/** Diesel generator, modular reactor and fire suppression tank: one input slot plus a live dashboard. */
public final class SingleSlotScreen extends RackcraftHandledScreen {
	public SingleSlotScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 176, 184);
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		String id = blockId();
		if (id.equals("fire_suppression_tank")) {
			line(context, "Canister", 76, 30, MUTED);
			wrapped(context, Text.translatable("screen.rackcraft.suppression_hint"), 8, 64, 160, MUTED);
			return;
		}
		boolean diesel = id.equals("diesel_generator");
		int capacityTenths = diesel ? 400 : 5000;
		int output = stat(Stat.POWER);
		int fuel = stat(Stat.FUEL);
		int spinup = stat(Stat.SPINUP);
		boolean fuelled = diesel ? fuel > 0 || !handler.getSlot(0).getStack().isEmpty() : !handler.getSlot(0).getStack().isEmpty();

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
		context.drawText(textRenderer, state, 8, 20, color, false);

		line(context, "Output", 8, 32, MUTED);
		bar(context, 8, 42, 64, output / (double) capacityTenths, GOOD);
		line(context, kw(output), 8, 51, TEXT);
		if (diesel) {
			line(context, "Fuel", 104, 32, MUTED);
			int total = Math.max(1, stat(Stat.FUEL_TOTAL));
			bar(context, 104, 42, 64, fuel / (double) total, WARN);
			line(context, fuel / 20 + " s left", 104, 51, TEXT);
			line(context, "Spin-up", 104, 62, MUTED);
			bar(context, 104, 72, 64, spinup / 20.0, 0xFF5BA7E0);
		}
		int delivered = stat(Stat.NETWORK_DELIVERED);
		int demand = stat(Stat.NETWORK_DEMAND);
		line(context, "Grid " + kw(delivered) + " / " + kw(demand), 8, 66, TEXT);
		line(context, "Capacity " + kw(stat(Stat.NETWORK_CAPACITY)), 8, 77, MUTED);
	}
}
