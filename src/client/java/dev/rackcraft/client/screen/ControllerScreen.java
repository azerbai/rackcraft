package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.client.ClientNet;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

public final class ControllerScreen extends RackcraftHandledScreen {
	public ControllerScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 268, 178);
	}

	@Override
	protected void init() {
		super.init();
		int left = (width - backgroundWidth) / 2;
		int top = (height - backgroundHeight) / 2;
		String[] contracts = {"bronze", "silver", "gold"};
		for (int index = 0; index < contracts.length; index++) {
			String contract = contracts[index];
			addDrawableChild(ButtonWidget.builder(Text.literal(contract.substring(0, 1).toUpperCase()
					+ contract.substring(1)), button -> ClientNet.setContract(handler.pos(), contract))
					.dimensions(left + 12 + index * 82, top + 126, 76, 18).build());
		}
		String[] purchases = {"coal", "copper_ingot", "silicon", "steel_ingot",
				"repair_kit", "suppression_canister", "pi_node", "server_1u", "asic_miner", "gpu_blade"};
		String[] labels = {"Coal", "Copper", "Silicon", "Steel", "Repair", "Suppress", "Pi", "1U", "ASIC", "GPU"};
		for (int index = 0; index < purchases.length; index++) {
			int row = index / 5;
			int column = index % 5;
			String itemId = purchases[index];
			addDrawableChild(ButtonWidget.builder(Text.literal(labels[index]), button ->
					ClientNet.buyItem(handler.pos(), itemId))
					.dimensions(left + 8 + column * 51, top + 148 + row * 20, 48, 18).build());
		}
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		line(context, "Overview     Contracts     Procurement", 12, 29, 0xFF62C5A0);
		line(context, String.format(java.util.Locale.ROOT, "RackCoin %,d", handler.balance()), 12, 55, 0xFF62C5A0);
		line(context, "Mining " + coins(stat(MachineScreenHandler.Stat.MINING_RATE)) + " RC/s", 140, 55, 0xFFE5ECEB);
		line(context, "Availability " + stat(MachineScreenHandler.Stat.AVAILABILITY) + "%", 12, 73, 0xFFE5ECEB);
		line(context, "Contract " + handler.activeContract(), 12, 91, 0xFFE5ECEB);
		line(context, "Active event " + handler.activeEvent(), 12, 109, 0xFFE5ECEB);
	}
}