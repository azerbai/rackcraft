package dev.rackcraft.client.screen;

import dev.rackcraft.block.RackStatus;
import dev.rackcraft.client.ClientNet;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/**
 * Rack telemetry. The module bays on the left (two columns of four on a Server Rack, up to four columns of six on an
 * Exascale Cabinet, which widens the panel); on the right the status headline, live readings, what to do about the
 * status, and the load-limit buttons. Everything wraps inside the panel, and a hint too long for its box is cut with
 * "..." and shown whole on hover.
 */
public final class RackScreen extends RackcraftHandledScreen {
	private static final int RIGHT_WIDTH = 188;
	/** Where the right-hand column starts: past however many columns of bays this rack has. */
	private final int right;
	private static final int HINT_TOP = 103;
	private static final int HINT_LINES = 5;
	private static final int[] LIMITS = {25, 50, 75, 100, 125, 150};
	private final List<ButtonWidget> limitButtons = new ArrayList<>();
	private boolean hintCut;
	/** 0 fills with the best module on hand (or what the rack already holds); n fills with the nth kind the rack accepts. */
	private int fillChoice;
	private ButtonWidget moduleButton;

	/** A reading with an explanation shown on hover. */
	private record Reading(String label, String value, int color, String help) {}

	public RackScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 256 + extraWidth(handler), 272);
		right = 60 + extraWidth(handler);
	}

	private static int extraWidth(MachineScreenHandler handler) {
		return Math.max(0, handler.bayColumns() - 2) * handler.baySpacing();
	}

	@Override
	protected void init() {
		super.init();
		limitButtons.clear();
		int left = (width - backgroundWidth) / 2;
		int top = (height - backgroundHeight) / 2;
		for (int index = 0; index < LIMITS.length; index++) {
			int limit = LIMITS[index];
			limitButtons.add(addDrawableChild(ButtonWidget.builder(Text.literal(Integer.toString(limit)), button ->
					ClientNet.setLoadLimit(handler.pos(), limit))
					.dimensions(left + right + 32 + index * 26, top + 157, 25, 16)
					.tooltip(Tooltip.of(Text.literal(limit <= 100 ? "Load limit " + limit + "%."
							: "Overclock to " + limit + "%: " + limit + "% of the work for " + Math.round(limit * limit / 100.0)
							+ "% of the power and heat, and modules can burn out. Needs Overclocking"
							+ (limit > 125 ? " and Liquid Hydrogen Cooling" : "") + " researched."))).build()));
		}
		addDrawableChild(ButtonWidget.builder(Text.literal("Fill"), button -> click(MachineScreenHandler.FILL_BUTTON + fillChoice))
				.dimensions(left + right, top + 174, 36, 14).tooltip(Tooltip.of(Text.literal(
						"Fill every empty bay from the storage on this rack's fiber network, then from your inventory."
								+ " Use the module button to pick what goes in."))).build());
		moduleButton = addDrawableChild(ButtonWidget.builder(Text.literal("Best"), button -> {
			fillChoice = (fillChoice + 1) % (handler.fillChoices().size() + 1);
		}).dimensions(left + right + 38, top + 174, 112, 14).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Empty"), button -> click(MachineScreenHandler.EMPTY_BUTTON))
				.dimensions(left + right + 152, top + 174, 36, 14).tooltip(Tooltip.of(Text.literal(
						"Take every module out, into storage if the rack's network has room, else into your inventory."))).build());
	}

	private void click(int button) {
		if (client != null && client.interactionManager != null) client.interactionManager.clickButton(handler.syncId, button);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// The current limit's button is shown pressed (inactive), so you can see which one is set.
		int current = stat(Stat.LOAD_LIMIT);
		for (int index = 0; index < limitButtons.size(); index++) limitButtons.get(index).active = LIMITS[index] != current;
		List<String> kinds = handler.fillChoices();
		if (fillChoice > kinds.size()) fillChoice = 0;
		moduleButton.setMessage(Text.literal(textRenderer.trimToWidth(
				fillChoice == 0 ? "Best on hand" : shortName(kinds.get(fillChoice - 1)), 104)));
		moduleButton.setTooltip(Tooltip.of(Text.literal(fillChoice == 0
				? "Fills with whatever the rack already holds, else the best module on hand. Click to pick a kind."
				: "Fills with " + shortName(kinds.get(fillChoice - 1)) + " only. Click for the next kind.")));
		super.render(context, mouseX, mouseY, delta);
		int left = (width - backgroundWidth) / 2;
		int top = (height - backgroundHeight) / 2;
		int localX = mouseX - left;
		int localY = mouseY - top;
		RackStatus status = RackStatus.byOrdinal(stat(Stat.RACK_STATUS));
		if (hintCut && localX >= right && localX < right + RIGHT_WIDTH && localY >= HINT_TOP && localY < HINT_TOP + HINT_LINES * 10) {
			context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(Text.translatable(status.hintKey()), 220), mouseX, mouseY);
			return;
		}
		List<Reading> readings = readings();
		for (int index = 0; index < readings.size(); index++) {
			int x = right + (index % 2) * 96;
			int y = 56 + (index / 2) * 11;
			Reading reading = readings.get(index);
			int labelWidth = textRenderer.getWidth(reading.label() + " " + reading.value());
			if (localX >= x && localX < x + labelWidth && localY >= y - 1 && localY < y + 9) {
				context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(Text.literal(reading.help()), 200), mouseX, mouseY);
				return;
			}
		}
	}

	private String shortName(String itemId) {
		return Registries.ITEM.get(new Identifier("rackcraft", itemId)).getName().getString();
	}

	private List<Reading> readings() {
		int inlet = stat(Stat.INLET);
		int thermal = stat(Stat.THERMAL);
		int supplied = stat(Stat.SATISFACTION);
		return List.of(
				new Reading("Inlet", celsius(inlet), inlet >= 400 ? BAD : inlet > 270 ? WARN : GOOD,
						"Air temperature at the front of the rack. Above 27 C it slows down; at 40 C it stops."),
				new Reading("Exhaust", celsius(stat(Stat.EXHAUST)), WARN,
						"Hot air out of the back. Keep it away from other racks' intakes."),
				new Reading("Power", kw(stat(Stat.POWER)), TEXT, "What the rack draws right now."),
				new Reading("Load", stat(Stat.LOAD) + "%", TEXT,
						"How hard the rack is working: the load limit, scaled down by heat and missing power."),
				new Reading("Thermal", thermal + "%", thermal >= 100 ? GOOD : thermal > 0 ? WARN : BAD,
						"Performance left after heat: 100% at 27 C or cooler, nothing at 40 C."),
				new Reading("Supplied", supplied + "%", supplied >= 100 ? GOOD : supplied >= 50 ? WARN : BAD,
						"Share of the rack's demand the power network delivers. Under 50% trips the breaker."),
				new Reading("To loop", kw(stat(Stat.HEAT_TO_LOOP)), stat(Stat.HEAT_TO_LOOP) > 0 ? GOOD : MUTED,
						"Heat carried off by Coolant Pipe: 85% of what liquid-cooled modules make, plus what a Rear-Door Cooler"
								+ " or CDU on the back catches. Sinks on the loop (towers, coolers, chillers) take it away."),
				new Reading("To air", kw(stat(Stat.HEAT_TO_AIR)), stat(Stat.HEAT_TO_AIR) > 50 ? WARN : TEXT,
						"Heat blown out of the back into the room. It spreads and slowly leaks away; if it reaches the intakes,"
								+ " racks slow down. Catch it with a Rear-Door Cooler or CDU on the back, a CRAC unit or an exhaust fan."));
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		int used = 0;
		int bays = handler.bays();
		for (int slot = 0; slot < bays; slot++) if (!handler.getSlot(slot).getStack().isEmpty()) used++;
		// Under the bays, so it can't run into the status headline.
		line(context, "Bays " + used + "/" + bays, 13, 48 + handler.bayRows() * handler.baySpacing(), used == bays ? WARN : MUTED);

		RackStatus status = RackStatus.byOrdinal(stat(Stat.RACK_STATUS));
		int color = status == RackStatus.MINING || status == RackStatus.GENERATING || status == RackStatus.LEASED ? GOOD
				: status.mining() || status == RackStatus.CRAFTING || status == RackStatus.TRAINING || status == RackStatus.RESEARCHING ? WARN : BAD;
		Text headline = status == RackStatus.BOOTING
				? Text.translatable(status.translationKey(), stat(Stat.BOOT), coins(stat(Stat.MINING_RATE)))
				: Text.translatable(status.translationKey(), coins(stat(Stat.MINING_RATE)));
		wrappedClamped(context, headline, right, 32, RIGHT_WIDTH, 2, color);

		List<Reading> readings = readings();
		for (int index = 0; index < readings.size(); index++) {
			Reading reading = readings.get(index);
			int x = right + (index % 2) * 96;
			int y = 56 + (index / 2) * 11;
			line(context, reading.label(), x, y, MUTED);
			line(context, reading.value(), x + textRenderer.getWidth(reading.label() + " "), y, reading.color());
		}

		context.fill(right, HINT_TOP - 5, right + RIGHT_WIDTH, HINT_TOP - 4, 0xFF3A525C);
		hintCut = wrappedClamped(context, Text.translatable(status.hintKey()), right, HINT_TOP, RIGHT_WIDTH, HINT_LINES, MUTED);
		line(context, "Limit", right, 161, MUTED);
	}
}
