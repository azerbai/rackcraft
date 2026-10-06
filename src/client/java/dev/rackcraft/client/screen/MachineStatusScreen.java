package dev.rackcraft.client.screen;

import dev.rackcraft.client.ClientNet;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import java.util.Set;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/** Live readout for every machine without a dedicated screen: sources, batteries, PDUs and consumers. */
public final class MachineStatusScreen extends RackcraftHandledScreen {
	private static final Set<String> SOURCES = Set.of("solar_panel", "wind_turbine", "utility_intake");
	private ButtonWidget breaker;

	public MachineStatusScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 230, 152);
	}

	@Override
	protected void init() {
		super.init();
		playerInventoryTitleY = -10_000;
		int left = (width - backgroundWidth) / 2;
		int top = (height - backgroundHeight) / 2;
		breaker = addDrawableChild(ButtonWidget.builder(Text.literal("Reset breaker"), button ->
				ClientNet.resetBreaker(handler.pos()))
				.dimensions(left + 12, top + 122, 108, 20).build());
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		String id = blockId();
		breaker.visible = id.equals("pdu");
		int power = stat(Stat.POWER);
		if (SOURCES.contains(id)) {
			line(context, power > 0 ? "Generating" : "Idle: nothing is drawing power", 12, 30, power > 0 ? GOOD : MUTED);
			line(context, "Output " + kw(power), 12, 44, TEXT);
		} else if (id.equals("battery_bank")) {
			int permille = stat(Stat.BATTERY_PERMILLE);
			String trend = power > 0 ? "Charging +" + kw(power) : power < 0 ? "Discharging " + kw(-power) : "Holding charge";
			line(context, trend, 12, 30, power < 0 ? WARN : GOOD);
			line(context, "Charge " + permille / 10 + "%", 12, 44, TEXT);
			bar(context, 12, 56, 206, permille / 1000.0, GOOD);
		} else if (id.equals("freshwater_pump")) {
			String[] states = {"Pumping fresh water", "No power", "Not touching water: place it beside a lake or river",
					"Salt water: oceans and beaches don't count", "Too little water: needs 12 source blocks nearby"};
			int state = Math.max(0, Math.min(states.length - 1, stat(Stat.PUMP_STATUS)));
			line(context, states[state], 12, 30, state == 0 ? GOOD : BAD);
			line(context, String.format(java.util.Locale.ROOT, "Cooling %.1f of %d units in use", stat(Stat.PUMP_USED) / 10.0,
					stat(Stat.PUMP_UNITS)), 12, 44, TEXT);
			line(context, stat(Stat.PUMP_SOURCES) + " water source blocks within reach", 12, 56, MUTED);
		} else if (id.equals("smog_scrubber")) {
			int satisfaction = stat(Stat.SATISFACTION);
			double rate = stat(Stat.SCRUB_RATE) / 100.0;
			double smog = stat(Stat.SMOG) / 10.0;
			line(context, satisfaction < 50 ? "Off: needs power" : rate > 0 ? "Scrubbing" : "Idle: the air here is clean",
					12, 30, satisfaction < 50 ? BAD : rate > 0 ? GOOD : MUTED);
			line(context, String.format(java.util.Locale.ROOT, "Removing %.2f smog/s, draw %s", rate, kw(power)), 12, 44, TEXT);
			line(context, String.format(java.util.Locale.ROOT, "Smog in this chunk: %.1f", smog),
					12, 56, smog >= 55 ? BAD : smog > 30 ? WARN : MUTED);
		} else if (id.equals("pdu")) {
			boolean live = stat(Stat.NETWORK_CAPACITY) > 0;
			line(context, live ? "Energised" : "Dead: no power source on this network", 12, 30, live ? GOOD : BAD);
		} else {
			int satisfaction = stat(Stat.SATISFACTION);
			line(context, satisfaction >= 100 ? "Fully powered" : satisfaction > 0 ? "Underpowered" : "No power",
					12, 30, satisfaction >= 100 ? GOOD : satisfaction > 0 ? WARN : BAD);
			line(context, "Draw " + kw(power) + "  (" + satisfaction + "% supplied)", 12, 44, TEXT);
		}
		line(context, "Power network", 12, 72, MUTED);
		line(context, "Delivering " + kw(stat(Stat.NETWORK_DELIVERED)) + " of " + kw(stat(Stat.NETWORK_DEMAND)) + " demand",
				12, 84, TEXT);
		line(context, "Generating capacity " + kw(stat(Stat.NETWORK_CAPACITY)), 12, 96, TEXT);
	}
}
