package dev.rackcraft.client.screen;

import dev.rackcraft.block.RackStatus;
import dev.rackcraft.client.ClientNet;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

public final class RackScreen extends RackcraftHandledScreen {
	public RackScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 256, 252);
	}

	@Override
	protected void init() {
		super.init();
		int left = (width - backgroundWidth) / 2;
		int top = (height - backgroundHeight) / 2;
		int[] limits = {25, 50, 75, 100};
		for (int index = 0; index < limits.length; index++) {
			int limit = limits[index];
			addDrawableChild(ButtonWidget.builder(Text.literal(limit + "%"), button ->
					ClientNet.setLoadLimit(handler.pos(), limit))
					.dimensions(left + 102 + index * 36, top + 155, 34, 20).build());
		}
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		RackStatus status = RackStatus.byOrdinal(stat(Stat.RACK_STATUS));
		int color = status == RackStatus.MINING ? GOOD : status.mining() ? WARN : BAD;
		Text headline = Text.translatable(status.translationKey(), coins(stat(Stat.MINING_RATE)));
		context.drawText(textRenderer, headline, 102, 31, color, false);
		wrapped(context, Text.translatable(status.hintKey()), 102, 43, 146, MUTED);
		line(context, "Inlet " + celsius(stat(Stat.INLET)), 102, 82, stat(Stat.INLET) > 270 ? WARN : GOOD);
		line(context, "Exhaust " + celsius(stat(Stat.EXHAUST)), 102, 94, WARN);
		line(context, "Power " + kw(stat(Stat.POWER)), 102, 106, TEXT);
		line(context, "Load " + stat(Stat.LOAD) + "%", 102, 118, TEXT);
		line(context, "Thermal factor " + stat(Stat.THERMAL) + "%", 102, 130, TEXT);
		line(context, "Power satisfied " + stat(Stat.SATISFACTION) + "%", 102, 142, TEXT);
	}
}
