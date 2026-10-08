package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

public final class MonitorWallScreen extends RackcraftHandledScreen {
	public MonitorWallScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 252, 176);
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		line(context, "Facility monitor", 12, 30, 0xFF62C5A0);
		line(context, String.format(java.util.Locale.ROOT, "RackCoin %,d", handler.balance()), 12, 58, 0xFF62C5A0);
		line(context, "Mining " + coins(stat(MachineScreenHandler.Stat.MINING_RATE)) + " RC/s from "
				+ stat(MachineScreenHandler.Stat.MINING_RACKS) + " of " + stat(MachineScreenHandler.Stat.TOTAL_RACKS) + " racks",
				12, 77, 0xFFE5ECEB);
		line(context, "Availability " + stat(MachineScreenHandler.Stat.AVAILABILITY) + "%", 12, 96, 0xFFE5ECEB);
	}
}