package dev.rackcraft.client.screen;

import dev.rackcraft.client.ClientNet;
import dev.rackcraft.storage.Autocrafter;
import dev.rackcraft.storage.ItemKey;
import dev.rackcraft.storage.TerminalScreenHandler;
import dev.rackcraft.storage.TerminalScreenHandler.Action;
import dev.rackcraft.storage.TerminalScreenHandler.Entry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/**
 * Storage Terminal. Left-click takes a stack (or deposits the held one), right-click takes half (or deposits
 * one), Shift-click sends a stack to your inventory, and middle-click or Ctrl/Cmd-click a craftable item (marked +) to
 * autocraft more of it, even when some is in stock (a plain click does it when there is none).
 * Items only on tape are tinted blue and arrive in your inventory after the tape mounts.
 */
public final class TerminalScreen extends HandledScreen<TerminalScreenHandler> {
	private static final int GRID_X = 8;
	private static final int GRID_Y = 18;
	private static final int COLUMNS = 9;
	private static final int ROWS = 5;
	private static final int TEXT = 0xFFE5ECEB;
	private static final int GOOD = 0xFF62C5A0;
	private static final int WARN = 0xFFE7A45D;
	private static final int BAD = 0xFFE0645A;
	private static final int MUTED = 0xFFAAB9BA;
	/** The job row ends just short of the Cancel jobs button. */
	private static final int JOB_ROW_RIGHT = 124;
	private static String rememberedSearch = "";

	private TextFieldWidget search;
	private TextFieldWidget amount;
	private ButtonWidget craftButton;
	private ButtonWidget cancelButton;
	private List<Entry> visible = List.of();
	private int seenRevision = -1;
	private String seenQuery = "";
	private int scrollRow;
	private ItemKey selected;

	public TerminalScreen(TerminalScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		backgroundWidth = TerminalScreenHandler.WIDTH;
		backgroundHeight = TerminalScreenHandler.HEIGHT;
		// The autocraft row sits where the inventory label would go.
		playerInventoryTitleY = -10_000;
	}

	@Override
	protected void init() {
		super.init();
		search = addDrawableChild(new TextFieldWidget(textRenderer, x + 92, y + 4, 94, 11, Text.translatable("exchange.rackcraft.search")));
		search.setText(rememberedSearch);
		search.setPlaceholder(Text.translatable("exchange.rackcraft.search").formatted(Formatting.DARK_GRAY));
		addDrawableChild(ButtonWidget.builder(Text.literal("Clear grid"), button -> ClientNet.terminalAction(handler.syncId, Action.CLEAR))
				.dimensions(x + 116, y + 120, 70, 14).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Encode"), button -> ClientNet.terminalAction(handler.syncId, Action.ENCODE))
				.dimensions(x + 116, y + 160, 70, 14).build());
		amount = addDrawableChild(new TextFieldWidget(textRenderer, x + 26, y + 177, 34, 12, Text.literal("Amount")));
		amount.setText("1");
		amount.setMaxLength(5);
		craftButton = addDrawableChild(ButtonWidget.builder(Text.literal("Craft"), button -> requestCraft())
				.dimensions(x + 64, y + 176, 38, 14).build());
		cancelButton = addDrawableChild(ButtonWidget.builder(Text.literal("Cancel jobs"), button -> {
			ClientNet.terminalAction(handler.syncId, Action.CANCEL);
			selected = null;
		}).dimensions(x + 128, y + 176, 58, 14).build());
	}

	private void requestCraft() {
		if (selected == null) return;
		long count;
		try {
			count = Long.parseLong(amount.getText().trim());
		} catch (NumberFormatException exception) {
			return;
		}
		if (count > 0) ClientNet.terminalCraft(handler.syncId, selected, count);
		selected = null;
	}

	private void refresh() {
		String query = search.getText().trim().toLowerCase(Locale.ROOT);
		if (handler.revision() == seenRevision && query.equals(seenQuery)) return;
		seenRevision = handler.revision();
		if (!query.equals(seenQuery)) scrollRow = 0;
		seenQuery = query;
		rememberedSearch = search.getText();
		List<Entry> list = new ArrayList<>();
		for (Entry entry : handler.entries()) {
			if (query.isEmpty() || entry.key().toStack(1).getName().getString().toLowerCase(Locale.ROOT).contains(query)
					|| Registries.ITEM.getId(entry.key().item()).getPath().contains(query.replace(' ', '_'))) list.add(entry);
		}
		list.sort(Comparator.comparingLong(Entry::total).reversed()
				.thenComparing(entry -> entry.key().toStack(1).getName().getString()));
		visible = list;
		scrollRow = Math.min(scrollRow, maxScroll());
	}

	private int maxScroll() {
		return Math.max(0, (visible.size() + COLUMNS - 1) / COLUMNS - ROWS);
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		context.fill(x, y, x + backgroundWidth, y + backgroundHeight, 0xFF17212A);
		context.fill(x, y, x + backgroundWidth, y + 16, 0xFF2C414A);
		context.fill(x + 4, y + 116, x + backgroundWidth - 4, y + 192, 0xFF202D34);
		for (Slot slot : handler.slots) {
			context.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, 0xFF3A525C);
			context.fill(x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16, 0xFF0F171C);
		}
		for (int cell = 0; cell < COLUMNS * ROWS; cell++) {
			int cellX = x + GRID_X + (cell % COLUMNS) * 18;
			int cellY = y + GRID_Y + (cell / COLUMNS) * 18;
			context.fill(cellX, cellY, cellX + 18, cellY + 18, 0xFF3A525C);
			context.fill(cellX + 1, cellY + 1, cellX + 17, cellY + 17, 0xFF0F171C);
		}
		// Arrows: grid to result, blank pattern to encoded pattern.
		context.fill(x + 66, y + 147, x + 84, y + 149, 0xFF3A525C);
		context.fill(x + 140, y + 147, x + 162, y + 149, 0xFF3A525C);
		// Drawn with the background so the held item and tooltips render on top.
		drawGrid(context);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		refresh();
		renderBackground(context);
		boolean craftMode = selected != null;
		amount.visible = craftButton.visible = craftMode;
		cancelButton.visible = !craftMode && !handler.jobs().isEmpty();
		super.render(context, mouseX, mouseY, delta);
		Entry hovered = entryAt(mouseX, mouseY);
		if (hovered != null && handler.getCursorStack().isEmpty()) {
			List<Text> tooltip = new ArrayList<>(Screen.getTooltipFromItem(client, hovered.key().toStack(1)));
			tooltip.add(Text.literal(String.format(Locale.ROOT, "Stored: %,d", hovered.hot())).formatted(Formatting.GREEN));
			if (hovered.cold() > 0) {
				tooltip.add(Text.literal(String.format(Locale.ROOT, "On tape: %,d (2 s to mount)", hovered.cold())).formatted(Formatting.AQUA));
			}
			if (hovered.craftable()) tooltip.add(Text.literal("Craftable: middle-click (or Ctrl/Cmd-click) to autocraft more").formatted(Formatting.YELLOW));
			context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
		} else if (selected == null && !handler.jobs().isEmpty() && mouseX >= x + 4 && mouseX < x + JOB_ROW_RIGHT
				&& mouseY >= y + 174 && mouseY < y + 192) {
			context.drawOrderedTooltip(textRenderer, jobTooltip(), mouseX, mouseY);
		} else {
			drawMouseoverTooltip(context, mouseX, mouseY);
		}
	}

	private List<OrderedText> jobTooltip() {
		List<TerminalScreenHandler.JobView> jobs = handler.jobs();
		TerminalScreenHandler.JobView job = jobs.get(0);
		List<Text> tooltip = new ArrayList<>();
		tooltip.add(Text.literal(job.amount() + " x ").append(job.target().toStack(1).getName()));
		tooltip.add(Text.literal(String.format(Locale.ROOT, "%d of %d crafts done", job.done(), job.total()))
				.formatted(Formatting.GRAY));
		String status = "storage.rackcraft.status." + job.status();
		tooltip.add(Text.translatable(status).append(": ").append(Text.translatable(status + ".hint"))
				.styled(style -> style.withColor(statusColor(job) & 0xFFFFFF)));
		if (job.compute() > 0) {
			tooltip.add(Text.literal(String.format(Locale.ROOT, "Compute: %d (%.2f crafts/s)", job.compute(),
					job.compute() * Autocrafter.CRAFTS_PER_COMPUTE_SECOND)).formatted(Formatting.GRAY));
		}
		if (jobs.size() > 1) {
			tooltip.add(Text.literal("+" + (jobs.size() - 1) + " more job" + (jobs.size() == 2 ? "" : "s") + " queued")
					.formatted(Formatting.DARK_GRAY));
		}
		List<OrderedText> wrapped = new ArrayList<>();
		for (Text line : tooltip) wrapped.addAll(textRenderer.wrapLines(line, 200));
		return wrapped;
	}

	private static int statusColor(TerminalScreenHandler.JobView job) {
		return job.status().equals("crafting") ? GOOD : job.status().equals("queued") ? MUTED : WARN;
	}

	private void drawGrid(DrawContext context) {
		for (int cell = 0; cell < COLUMNS * ROWS; cell++) {
			int index = scrollRow * COLUMNS + cell;
			if (index >= visible.size()) break;
			Entry entry = visible.get(index);
			int cellX = x + GRID_X + (cell % COLUMNS) * 18 + 1;
			int cellY = y + GRID_Y + (cell / COLUMNS) * 18 + 1;
			context.drawItem(entry.key().toStack(1), cellX, cellY);
			if (entry.hot() == 0 && entry.cold() > 0) context.fill(cellX, cellY, cellX + 16, cellY + 16, 0x605BA7E0);
			if (entry.total() == 0) context.fill(cellX, cellY, cellX + 16, cellY + 16, 0x90101418);
			var matrices = context.getMatrices();
			matrices.push();
			matrices.translate(0, 0, 200);
			if (entry.craftable()) context.drawText(textRenderer, "+", cellX + 11, cellY - 1, 0xFFFFE066, true);
			if (entry.total() > 0) {
				String count = abbreviate(entry.total());
				matrices.push();
				matrices.scale(0.5f, 0.5f, 1);
				context.drawText(textRenderer, count, (cellX + 16) * 2 - textRenderer.getWidth(count), (cellY + 12) * 2,
						entry.hot() > 0 ? 0xFFFFFFFF : 0xFF9FD3FF, true);
				matrices.pop();
			}
			matrices.pop();
		}
		int track = ROWS * 18;
		int barX = x + GRID_X + COLUMNS * 18 + 2;
		context.fill(barX, y + GRID_Y, barX + 4, y + GRID_Y + track, 0xFF0F171C);
		int rows = Math.max(1, (visible.size() + COLUMNS - 1) / COLUMNS);
		int thumb = maxScroll() == 0 ? track : Math.max(8, track * ROWS / rows);
		int thumbY = maxScroll() == 0 ? 0 : (track - thumb) * scrollRow / maxScroll();
		context.fill(barX, y + GRID_Y + thumbY, barX + 4, y + GRID_Y + thumbY + thumb, GOOD);
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		context.drawText(textRenderer, title, 8, 4, TEXT, false);
		TerminalScreenHandler.Stats stats = handler.stats();
		String summary = !stats.online() ? "Storage offline: check power, fiber and heat"
				: String.format(Locale.ROOT, "%s / %s on %d drive%s", abbreviate(stats.hotUsed()), abbreviate(stats.hotCapacity()),
						stats.drives(), stats.drives() == 1 ? "" : "s")
						+ (stats.tapes() > 0 ? String.format(Locale.ROOT, ", %s / %s on tape", abbreviate(stats.coldUsed()),
						abbreviate(stats.coldCapacity())) : "");
		context.drawText(textRenderer, textRenderer.trimToWidth(summary, backgroundWidth - 16), 8, 110, stats.online() ? MUTED : BAD, false);
		if (selected != null) {
			context.drawItem(selected.toStack(1), 7, 175);
		} else if (!handler.jobs().isEmpty()) {
			// Icon, short status and a progress bar; the details are in the row's tooltip.
			TerminalScreenHandler.JobView job = handler.jobs().get(0);
			int color = statusColor(job);
			context.drawItem(job.target().toStack(1), 7, 175);
			String line = Text.translatable("storage.rackcraft.status." + job.status()).getString()
					+ String.format(Locale.ROOT, " %d/%d", job.done(), job.total());
			context.drawText(textRenderer, textRenderer.trimToWidth(line, JOB_ROW_RIGHT - 26), 26, 177, color, false);
			int filled = job.total() <= 0 ? 0 : (int) ((JOB_ROW_RIGHT - 26) * Math.min(1, (double) job.done() / job.total()));
			context.fill(26, 187, JOB_ROW_RIGHT, 189, 0xFF3A525C);
			context.fill(26, 187, 26 + filled, 189, color);
		} else {
			context.drawText(textRenderer, "Middle-click + items to autocraft", 8, 179, MUTED, false);
		}
	}

	private Entry entryAt(double mouseX, double mouseY) {
		int column = (int) Math.floor((mouseX - x - GRID_X) / 18);
		int row = (int) Math.floor((mouseY - y - GRID_Y) / 18);
		if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) return null;
		int index = (scrollRow + row) * COLUMNS + column;
		return index < visible.size() ? visible.get(index) : null;
	}

	private boolean inGrid(double mouseX, double mouseY) {
		return mouseX >= x + GRID_X && mouseX < x + GRID_X + COLUMNS * 18 && mouseY >= y + GRID_Y && mouseY < y + GRID_Y + ROWS * 18;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (inGrid(mouseX, mouseY) && (button == 0 || button == 1 || button == 2)) {
			Entry entry = entryAt(mouseX, mouseY);
			boolean craftClick = button == 2 || craftModifier() || entry != null && entry.total() == 0;
			if (entry != null && entry.craftable() && handler.getCursorStack().isEmpty() && craftClick) {
				selected = entry.key();
				amount.setText("1");
				setFocused(amount);
				return true;
			}
			if (button == 2) return true;
			ClientNet.terminalClick(handler.syncId, entry == null ? null : entry.key(), button, hasShiftDown());
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	/** Ctrl or, on a Mac, Cmd: Minecraft's own check only knows Cmd there, and Ctrl-click is a right-click to macOS. */
	private boolean craftModifier() {
		long window = net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle();
		return hasControlDown() || net.minecraft.client.util.InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_CONTROL)
				|| net.minecraft.client.util.InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scroll) {
		if (inGrid(mouseX, mouseY) || mouseY < y + 116) {
			scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(scroll)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scroll);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE && selected != null) {
			selected = null;
			return true;
		}
		if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && selected != null) {
			requestCraft();
			return true;
		}
		for (TextFieldWidget field : List.of(search, amount)) {
			if (field.isFocused() && field.isVisible() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
				return field.keyPressed(keyCode, scanCode, modifiers) || true;
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private static String abbreviate(long value) {
		if (value < 1_000) return Long.toString(value);
		if (value < 1_000_000) return trim(value / 1_000.0) + "k";
		if (value < 1_000_000_000) return trim(value / 1_000_000.0) + "M";
		return trim(value / 1_000_000_000.0) + "G";
	}

	private static String trim(double value) {
		return value >= 100 ? String.format(Locale.ROOT, "%.0f", value) : String.format(Locale.ROOT, "%.1f", value);
	}
}
