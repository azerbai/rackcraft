package dev.rackcraft.client.screen;

import dev.rackcraft.client.ClientNet;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import dev.rackcraft.storage.StorageService;
import dev.rackcraft.storage.TransmitterUpgrades;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/** Shows the transmitter's reach and sells the next range level for RackCoin or resources. */
public final class TransmitterScreen extends RackcraftHandledScreen {
	private ButtonWidget rackCoinButton;
	private ButtonWidget itemsButton;

	public TransmitterScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 236, 176);
	}

	@Override
	protected void init() {
		super.init();
		playerInventoryTitleY = -10_000;
		rackCoinButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> ClientNet.upgradeTransmitter(handler.pos(), true))
				.dimensions(x + 10, y + 96, 104, 20).build());
		itemsButton = addDrawableChild(ButtonWidget.builder(Text.translatable("transmitter.rackcraft.upgrade_items"),
				button -> ClientNet.upgradeTransmitter(handler.pos(), false)).dimensions(x + 122, y + 96, 104, 20).build());
	}

	static Text range(int level) {
		if (level >= StorageService.MULTIDIMENSIONAL_LEVEL) return Text.translatable("transmitter.rackcraft.range_multidimensional");
		if (level >= StorageService.INFINITE_LEVEL) return Text.translatable("transmitter.rackcraft.range_infinite");
		return Text.translatable("transmitter.rackcraft.range_blocks", StorageService.RANGES[level]);
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		int level = stat(Stat.TRANSMITTER_LEVEL);
		int supplied = stat(Stat.SATISFACTION);
		context.drawText(textRenderer, Text.translatable("transmitter.rackcraft.level", level, range(level)), 10, 30, GOOD, false);
		line(context, (supplied >= 50 ? "Online" : "Offline: no power") + ", drawing " + kw(stat(Stat.POWER)),
				10, 44, supplied >= 50 ? TEXT : BAD);
		TransmitterUpgrades.Cost cost = TransmitterUpgrades.next(level);
		rackCoinButton.visible = itemsButton.visible = cost != null;
		if (cost == null) {
			context.drawText(textRenderer, Text.translatable("transmitter.rackcraft.max"), 10, 70, GOOD, false);
		} else {
			context.drawText(textRenderer, Text.literal("Next: ").append(range(level + 1)).append(String.format(Locale.ROOT,
					", %.1f kW", TransmitterUpgrades.drawKw(level + 1))), 10, 62, TEXT, false);
			rackCoinButton.setMessage(Text.translatable("transmitter.rackcraft.upgrade_rc", String.format(Locale.ROOT, "%,d", cost.rackCoin())));
			int itemX = 122;
			for (Map.Entry<Item, Integer> entry : cost.items().entrySet()) {
				ItemStack stack = new ItemStack(entry.getKey(), entry.getValue());
				context.drawItem(stack, itemX, 76);
				context.drawItemInSlot(textRenderer, stack, itemX, 76);
				itemX += 20;
			}
		}
		wrapped(context, Text.translatable("transmitter.rackcraft.link_hint"), 10, 126, backgroundWidth - 20, MUTED);
	}
}
