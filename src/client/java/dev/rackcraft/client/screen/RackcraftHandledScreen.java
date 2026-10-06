package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

abstract class RackcraftHandledScreen extends HandledScreen<MachineScreenHandler> {
	RackcraftHandledScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title,
			int panelWidth, int panelHeight) {
		super(handler, inventory, title);
		backgroundWidth = panelWidth;
		backgroundHeight = panelHeight;
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		int left = (width - backgroundWidth) / 2;
		int top = (height - backgroundHeight) / 2;
		context.fill(left, top, left + backgroundWidth, top + backgroundHeight, 0xFF17212A);
		context.fill(left, top, left + backgroundWidth, top + 22, 0xFF2C414A);
		context.fill(left + 5, top + 28, left + backgroundWidth - 5, top + backgroundHeight - 5, 0xFF202D34);
		// Every slot gets a frame, so empty machine slots and the inventory are visible.
		for (net.minecraft.screen.slot.Slot slot : handler.slots) {
			int slotX = left + slot.x - 1;
			int slotY = top + slot.y - 1;
			context.fill(slotX, slotY, slotX + 18, slotY + 18, 0xFF3A525C);
			context.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0xFF0F171C);
		}
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		super.drawForeground(context, mouseX, mouseY);
		drawDashboard(context);
	}

	protected abstract void drawDashboard(DrawContext context);

	protected void line(DrawContext context, String text, int x, int y, int color) {
		context.drawText(textRenderer, Text.literal(text), x, y, color, false);
	}

	protected String celsius(int tenths) { return String.format(java.util.Locale.ROOT, "%.1f C", tenths / 10.0); }

	protected static String kw(int tenths) { return String.format(java.util.Locale.ROOT, "%.1f kW", tenths / 10.0); }

	protected static String coins(int hundredthsPerSecond) {
		return String.format(java.util.Locale.ROOT, "%.1f", hundredthsPerSecond / 100.0);
	}

	protected int stat(int stat) { return handler.stat(stat); }

	/** The machine's block id, read from the client world, e.g. "diesel_generator". */
	protected String blockId() {
		if (client == null || client.world == null) return "";
		return net.minecraft.registry.Registries.BLOCK.getId(client.world.getBlockState(handler.pos()).getBlock()).getPath();
	}

	/** A labelled progress bar in panel-local coordinates. */
	protected void bar(DrawContext context, int x, int y, int width, double fraction, int color) {
		double clamped = Math.max(0, Math.min(1, fraction));
		context.fill(x, y, x + width, y + 6, 0xFF0F171C);
		context.fill(x, y, x + (int) Math.round(width * clamped), y + 6, color);
		context.drawBorder(x - 1, y - 1, width + 2, 8, 0xFF3A525C);
	}

	protected int wrapped(DrawContext context, Text text, int x, int y, int width, int color) {
		for (net.minecraft.text.OrderedText row : textRenderer.wrapLines(text, width)) {
			context.drawText(textRenderer, row, x, y, color, false);
			y += 10;
		}
		return y;
	}

	protected static final int TEXT = 0xFFE5ECEB;
	protected static final int GOOD = 0xFF62C5A0;
	protected static final int WARN = 0xFFE7A45D;
	protected static final int BAD = 0xFFE0645A;
	protected static final int MUTED = 0xFFAAB9BA;
}