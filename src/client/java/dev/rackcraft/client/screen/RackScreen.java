package dev.rackcraft.client.screen;

import dev.rackcraft.block.RackStatus;
import dev.rackcraft.client.ClientNet;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/**
 * Rack telemetry. Eight module bays in two columns on the left; on the right the status headline, live
 * readings, what to do about the status, and the load-limit buttons. Everything wraps inside the panel, and
 * a hint too long for its box is cut with "..." and shown whole on hover.
 */
public final class RackScreen extends RackcraftHandledScreen {
	private static final int RIGHT = 60;
	private static final int RIGHT_WIDTH = 188;
	private static final int HINT_TOP = 103;
	private static final int HINT_LINES = 4;
	private static final int[] LIMITS = {25, 50, 75, 100};
	private final List<ButtonWidget> limitButtons = new ArrayList<>();
	private boolean hintCut;

	/** A reading with an explanation shown on hover. */
	private record Reading(String label, String value, int color, String help) {}

	public RackScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 256, 262);
	}

	@Override
	protected void init() {
		super.init();
		limitButtons.clear();
		int left = (width - backgroundWidth) / 2;
		int top = (height - backgroundHeight) / 2;
		for (int index = 0; index < LIMITS.length; index++) {
			int limit = LIMITS[index];
			limitButtons.add(addDrawableChild(ButtonWidget.builder(Text.literal(limit + "%"), button ->
					ClientNet.setLoadLimit(handler.pos(), limit))
					.dimensions(left + 92 + index * 39, top + 146, 36, 16).build()));
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// The current limit's button is shown pressed (inactive), so you can see which one is set.
		int current = stat(Stat.LOAD_LIMIT);
		for (int index = 0; index < limitButtons.size(); index++) limitButtons.get(index).active = LIMITS[index] != current;
		super.render(context, mouseX, mouseY, delta);
		int left = (width - backgroundWidth) / 2;
		int top = (height - backgroundHeight) / 2;
		int localX = mouseX - left;
		int localY = mouseY - top;
		RackStatus status = RackStatus.byOrdinal(stat(Stat.RACK_STATUS));
		if (hintCut && localX >= RIGHT && localX < RIGHT + RIGHT_WIDTH && localY >= HINT_TOP && localY < HINT_TOP + HINT_LINES * 10) {
			context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(Text.translatable(status.hintKey()), 220), mouseX, mouseY);
			return;
		}
		List<Reading> readings = readings();
		for (int index = 0; index < readings.size(); index++) {
			int x = RIGHT + (index % 2) * 96;
			int y = 56 + (index / 2) * 11;
			Reading reading = readings.get(index);
			int labelWidth = textRenderer.getWidth(reading.label() + " " + reading.value());
			if (localX >= x && localX < x + labelWidth && localY >= y - 1 && localY < y + 9) {
				context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(Text.literal(reading.help()), 200), mouseX, mouseY);
				return;
			}
		}
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
								+ " on the back catches. Sinks on the loop (towers, coolers, chillers) take it away."),
				new Reading("To air", kw(stat(Stat.HEAT_TO_AIR)), stat(Stat.HEAT_TO_AIR) > 50 ? WARN : TEXT,
						"Heat blown out of the back into the room. It spreads and slowly leaks away; if it reaches the intakes,"
								+ " racks slow down. Catch it with a Rear-Door Cooler, CRAC unit or exhaust fan."));
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		int used = 0;
		for (int slot = 0; slot < 8; slot++) if (!handler.getSlot(slot).getStack().isEmpty()) used++;
		line(context, "Bays " + used + "/8", 12, 32, used == 8 ? WARN : MUTED);

		RackStatus status = RackStatus.byOrdinal(stat(Stat.RACK_STATUS));
		int color = status == RackStatus.MINING || status == RackStatus.GENERATING || status == RackStatus.LEASED ? GOOD
				: status.mining() || status == RackStatus.CRAFTING || status == RackStatus.TRAINING || status == RackStatus.RESEARCHING ? WARN : BAD;
		Text headline = status == RackStatus.BOOTING
				? Text.translatable(status.translationKey(), stat(Stat.BOOT), coins(stat(Stat.MINING_RATE)))
				: Text.translatable(status.translationKey(), coins(stat(Stat.MINING_RATE)));
		wrappedClamped(context, headline, RIGHT, 32, RIGHT_WIDTH, 2, color);

		List<Reading> readings = readings();
		for (int index = 0; index < readings.size(); index++) {
			Reading reading = readings.get(index);
			int x = RIGHT + (index % 2) * 96;
			int y = 56 + (index / 2) * 11;
			line(context, reading.label(), x, y, MUTED);
			line(context, reading.value(), x + textRenderer.getWidth(reading.label() + " "), y, reading.color());
		}

		context.fill(RIGHT, HINT_TOP - 5, RIGHT + RIGHT_WIDTH, HINT_TOP - 4, 0xFF3A525C);
		hintCut = wrappedClamped(context, Text.translatable(status.hintKey()), RIGHT, HINT_TOP, RIGHT_WIDTH, HINT_LINES, MUTED);
		line(context, "Limit", RIGHT, 150, MUTED);
	}
}
