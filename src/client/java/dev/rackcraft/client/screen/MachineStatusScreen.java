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
	private static final Set<String> SINKS = Set.of("cooling_tower", "dry_cooler", "chiller", "water_heat_exchanger");
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
			int edge = Math.max(1, stat(Stat.ARRAY_EDGE));
			line(context, "Charge " + permille / 10 + "%" + (edge > 1 ? String.format(java.util.Locale.ROOT,
					"  Grid-Scale Battery %dx%dx%d, %.1f MJ, %d%% efficient", edge, edge, edge, stat(Stat.SOURCE_CAPACITY) / 10000.0,
					90 + 2 * (edge - 1)) : ""), 12, 44, TEXT);
			bar(context, 12, 56, 206, permille / 1000.0, GOOD);
		} else if (id.equals("freshwater_pump")) {
			String[] states = {"Pumping fresh water", "No power", "Not touching water: place it beside a lake or river",
					"Salt water: oceans and beaches don't count", "Too little water: needs 12 source blocks nearby"};
			int state = Math.max(0, Math.min(states.length - 1, stat(Stat.PUMP_STATUS)));
			line(context, states[state], 12, 30, state == 0 ? GOOD : BAD);
			line(context, String.format(java.util.Locale.ROOT, "Cooling towers using %.1f of %d units", stat(Stat.PUMP_USED) / 10.0,
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
		} else if (SINKS.contains(id)) {
			int satisfaction = stat(Stat.SATISFACTION);
			int moved = stat(Stat.COOLING_KW);
			int detail = stat(Stat.COOLING_DETAIL);
			boolean onLoop = stat(Stat.LOOP_CAPACITY) > 0 || stat(Stat.LOOP_HEAT) > 0;
			String state = satisfaction < 50 ? "Off: needs power" : moved > 0 ? "Taking " + kw(moved) + " out of its loop"
					: onLoop ? "Idle: no heat on its loop" : "Idle: connect it to a loop with Coolant Pipe";
			line(context, state, 12, 30, satisfaction < 50 ? BAD : moved > 0 ? GOOD : MUTED);
			String info = switch (id) {
				case "cooling_tower" -> detail >= 4 ? "Water: 4 of 4 units, so it can take 120 kW"
						: "Water: " + detail + " of 4 units (" + Math.round(20 + 25 * detail) + " kW); add a Freshwater Pump";
				case "dry_cooler" -> "Climate " + detail + "%: it can take " + Math.round(40 * detail / 100.0) + " kW";
				case "chiller" -> "Can take 250 kW; drawing " + kw(power) + " to do it";
				default -> detail == 0 ? "Not touching water" : detail + " water blocks nearby: it can take "
						+ Math.min(120, detail * 3) + " kW";
			};
			line(context, info, 12, 44, detail == 0 && !id.equals("chiller") ? WARN : TEXT);
			loopLine(context, 56);
		} else if (id.equals("rear_door_cooler") || id.equals("crac_unit")) {
			int satisfaction = stat(Stat.SATISFACTION);
			int moved = stat(Stat.COOLING_KW);
			boolean door = id.equals("rear_door_cooler");
			String state = satisfaction < 50 ? "Off: needs power" : moved > 0
					? (door ? "Catching " + kw(moved) + " off the rack's exhaust" : "Pulling " + kw(moved) + " out of the room air")
					: door ? "Idle: place it against the back of a running rack" : "Idle: the air on its faces is cool";
			line(context, state, 12, 30, satisfaction < 50 ? BAD : moved > 0 ? GOOD : MUTED);
			line(context, door ? "Takes up to 40 kW into its coolant loop" : "Takes up to 40 kW per face into its loop", 12, 44, TEXT);
			loopLine(context, 56);
		} else if (id.equals("exhaust_fan")) {
			int moved = stat(Stat.COOLING_KW);
			double smog = stat(Stat.SMOG) / 10.0;
			line(context, stat(Stat.SATISFACTION) < 50 ? "Off: needs power" : moved > 0 ? "Venting " + kw(moved) + " outside"
					: "Idle: the air in front of it is cool", 12, 30, moved > 0 ? GOOD : MUTED);
			line(context, String.format(java.util.Locale.ROOT, "Smog in this chunk: %.1f", smog), 12, 44,
					smog >= 55 ? BAD : smog > 30 ? WARN : MUTED);
		} else if (id.equals("pdu")) {
			boolean live = stat(Stat.NETWORK_CAPACITY) > 0;
			line(context, live ? "Energised" : "Dead: no power source on this network", 12, 30, live ? GOOD : BAD);
		} else {
			int satisfaction = stat(Stat.SATISFACTION);
			line(context, satisfaction >= 100 ? "Fully powered" : satisfaction > 0 ? "Underpowered" : "No power",
					12, 30, satisfaction >= 100 ? GOOD : satisfaction > 0 ? WARN : BAD);
			line(context, "Draw " + kw(power) + "  (" + satisfaction + "% supplied)", 12, 44, TEXT);
		}
		if (id.equals("cdu")) loopLine(context, 56);
		line(context, "Power network", 12, 72, MUTED);
		line(context, "Delivering " + kw(stat(Stat.NETWORK_DELIVERED)) + " of " + kw(stat(Stat.NETWORK_DEMAND)) + " demand",
				12, 84, TEXT);
		line(context, "Generating capacity " + kw(stat(Stat.NETWORK_CAPACITY)), 12, 96, TEXT);
	}

	/** The coolant loop's budget: heat put in against what its sinks can take. */
	private void loopLine(DrawContext context, int y) {
		int heat = stat(Stat.LOOP_HEAT);
		int capacity = stat(Stat.LOOP_CAPACITY);
		line(context, "Loop: " + kw(heat) + " in, sinks can take " + kw(capacity), 12, y,
				heat > capacity ? BAD : heat > capacity * 0.8 ? WARN : GOOD);
	}
}
