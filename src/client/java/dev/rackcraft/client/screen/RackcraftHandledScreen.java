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

	/**
	 * Puts the title in the header bar and the "Inventory" label just above the player's inventory, wherever
	 * this screen put it (vanilla assumes a 166-pixel panel). Screens without one get no label.
	 */
	@Override
	protected void init() {
		super.init();
		titleX = 8;
		titleY = 7;
		int top = Integer.MAX_VALUE;
		int left = 8;
		for (net.minecraft.screen.slot.Slot slot : handler.slots) {
			if (slot.inventory instanceof net.minecraft.entity.player.PlayerInventory && slot.y < top) {
				top = slot.y;
				left = slot.x;
			}
		}
		playerInventoryTitleX = left;
		playerInventoryTitleY = top == Integer.MAX_VALUE ? -10_000 : top - 11;
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
		// Vanilla draws both labels in dark grey, which vanishes on these dark panels.
		context.drawText(textRenderer, title, titleX, titleY, TEXT, false);
		context.drawText(textRenderer, playerInventoryTitle, playerInventoryTitleX, playerInventoryTitleY, MUTED, false);
		cutLines.clear();
		drawDashboard(context);
	}

	/** Lines {@link #lineFit} had to cut this frame, so hovering one shows it whole. */
	private record CutLine(int x, int y, int width, String text) {}
	private final java.util.List<CutLine> cutLines = new java.util.ArrayList<>();

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		super.render(context, mouseX, mouseY, delta);
		// Hovering an item in any slot names it, as in vanilla containers.
		drawMouseoverTooltip(context, mouseX, mouseY);
		if (focusedSlot != null && focusedSlot.hasStack()) return;
		int localX = mouseX - x;
		int localY = mouseY - y;
		for (CutLine cut : cutLines) {
			if (localX >= cut.x() && localX < cut.x() + cut.width() && localY >= cut.y() - 1 && localY < cut.y() + 9) {
				context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(Text.literal(cut.text()), 220), mouseX, mouseY);
				break;
			}
		}
	}

	protected abstract void drawDashboard(DrawContext context);

	protected void line(DrawContext context, String text, int x, int y, int color) {
		context.drawText(textRenderer, Text.literal(text), x, y, color, false);
	}

	protected String celsius(int tenths) { return String.format(java.util.Locale.ROOT, "%.1f C", tenths / 10.0); }

	/** Power from tenths of a kilowatt, scaled so big grids stay readable: 12.5 kW, 4,800 kW, 41.1 MW, 1.20 GW. */
	protected static String kw(int tenths) {
		double kw = tenths / 10.0;
		double size = Math.abs(kw);
		if (size >= 1_000_000) return String.format(java.util.Locale.ROOT, "%.2f GW", kw / 1_000_000);
		if (size >= 10_000) return String.format(java.util.Locale.ROOT, "%.1f MW", kw / 1_000);
		if (size >= 1_000) return String.format(java.util.Locale.ROOT, "%,.0f kW", kw);
		return String.format(java.util.Locale.ROOT, "%.1f kW", kw);
	}

	/** A single line cut to fit {@code width}, ending in "..." when it doesn't. */
	protected void lineFit(DrawContext context, String text, int x, int y, int width, int color) {
		boolean fits = textRenderer.getWidth(text) <= width;
		String shown = fits ? text : textRenderer.trimToWidth(text, width - textRenderer.getWidth("...")).trim() + "...";
		if (!fits) cutLines.add(new CutLine(x, y, width, text));
		context.drawText(textRenderer, Text.literal(shown), x, y, color, false);
	}

	protected static String coins(int hundredthsPerSecond) {
		return String.format(java.util.Locale.ROOT, "%.1f", hundredthsPerSecond / 100.0);
	}

	int stat(int stat) { return handler.stat(stat); }

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

	/**
	 * Wrapped text cut to {@code maxLines}; a cut-off last line ends in "...". Returns whether anything was cut,
	 * so the caller can offer the whole text as a tooltip.
	 */
	boolean wrappedClamped(DrawContext context, Text text, int x, int y, int width, int maxLines, int color) {
		java.util.List<net.minecraft.text.OrderedText> rows = textRenderer.wrapLines(text, width);
		boolean cut = rows.size() > maxLines;
		if (cut) {
			// Find where the visible lines end in the text itself (the wrap drops the spaces it breaks at, so counting
			// characters would repeat a word), then squeeze the rest into the last line.
			String plain = text.getString();
			java.util.List<net.minecraft.text.StringVisitable> parts = textRenderer.getTextHandler()
					.wrapLines(plain, width, net.minecraft.text.Style.EMPTY);
			int consumed = 0;
			for (int index = 0; index < maxLines - 1; index++) {
				String part = parts.get(index).getString().trim();
				int at = plain.indexOf(part, consumed);
				consumed = at < 0 ? consumed + part.length() : at + part.length();
			}
			String rest = plain.substring(Math.min(plain.length(), consumed)).trim();
			String last = textRenderer.trimToWidth(rest, width - textRenderer.getWidth("...")).trim() + "...";
			for (int index = 0; index < maxLines - 1; index++) {
				context.drawText(textRenderer, rows.get(index), x, y + index * 10, color, false);
			}
			context.drawText(textRenderer, last, x, y + (maxLines - 1) * 10, color, false);
		} else {
			for (int index = 0; index < rows.size(); index++) context.drawText(textRenderer, rows.get(index), x, y + index * 10, color, false);
		}
		return cut;
	}

	static final int TEXT = 0xFFE5ECEB;
	static final int GOOD = 0xFF62C5A0;
	static final int WARN = 0xFFE7A45D;
	static final int BAD = 0xFFE0645A;
	static final int MUTED = 0xFFAAB9BA;
}