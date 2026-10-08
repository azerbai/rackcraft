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
		if (id.equals("solar_array") || id.equals("solar_array_tracking")) {
			boolean tracking = id.equals("solar_array_tracking");
			line(context, power > 0 ? "Generating " + kw(power) + " (whole array)" : "Idle: no sun, no sky above, or nothing drawing power",
					12, 30, power > 0 ? GOOD : MUTED);
			line(context, "Can make " + kw(stat(Stat.SOURCE_CAPACITY)) + " right now" + (tracking ? ", tracking the sun" : ""), 12, 44, TEXT);
			wearLine(context, 56);
		} else if (id.equals("wind_nacelle")) {
			int status = stat(Stat.PROCESS_STATUS);
			String state = status == dev.rackcraft.world.Renewables.TowerStatus.TOO_SHORT.ordinal()
					? "Too short: needs " + dev.rackcraft.RackcraftConfig.values.renewables.windTowerMinSections + " Tower Sections under it"
					: status == dev.rackcraft.world.Renewables.TowerStatus.BLOCKED.ordinal() ? "Stopped: something is in the blades' 5x5"
					: power > 0 ? "Generating " + kw(power) : "Idle: nothing drawing power";
			line(context, state, 12, 30, status != 0 ? WARN : power > 0 ? GOOD : MUTED);
			line(context, "Tower of " + stat(Stat.WORKERS) + " sections; wind here can give " + kw(stat(Stat.SOURCE_CAPACITY)), 12, 44, TEXT);
			wearLine(context, 56);
		} else if (id.equals("auto_buyer")) {
			boolean on = stat(Stat.SATISFACTION) >= 50;
			line(context, on ? "Buying whatever its machines' storage runs out of" : "Offline: needs power", 12, 30, on ? GOOD : BAD);
			line(context, String.format(java.util.Locale.ROOT, "Bought %,d items for %,dk RC", stat(Stat.SITE), stat(Stat.SITE + 1)), 12, 44, TEXT);
			line(context, "Works for every machine that restocks from", 12, 58, MUTED);
			line(context, "this storage; not drones, hydrogen or line parts", 12, 70, MUTED);
		} else if (id.equals("storage_link")) {
			int links = stat(Stat.SITE);
			line(context, links > 0 ? "Linked: " + links + (links == 1 ? " Storage Link" : " Storage Links") + " in this dimension"
					: "Offline: needs power", 12, 30, links > 0 ? GOOD : BAD);
			line(context, stat(Stat.SITE + 1) + " drives and tapes on the joined storage", 12, 44, TEXT);
			line(context, "Machines, pipes and terminals touching any link share it all", 12, 58, MUTED);
		} else if (id.equals("tower_section")) {
			line(context, "Part of a Wind Tower", 12, 30, MUTED);
			line(context, "It carries the nacelle's power down to the ground", 12, 44, MUTED);
		} else if (id.equals("rectenna")) {
			int capacity = stat(Stat.NETWORK_CAPACITY);
			line(context, power > 0 ? "Receiving " + kw(power) + " from the swarm" : "Idle: no Dyson Mirrors in orbit, or nothing drawing power",
					12, 30, power > 0 ? GOOD : MUTED);
			line(context, "Each mirror beams 2 MW, shared by every Rectenna (up to 20 MW each)", 12, 44, MUTED);
		} else if (SOURCES.contains(id)) {
			line(context, power > 0 ? "Generating" : "Idle: nothing is drawing power", 12, 30, power > 0 ? GOOD : MUTED);
			line(context, "Output " + kw(power), 12, 44, TEXT);
		} else if (id.equals("battery_bank")) {
			int permille = stat(Stat.BATTERY_PERMILLE);
			// Batteries don't count as generating capacity, so none on the network means nothing can charge this one.
			boolean fed = stat(Stat.NETWORK_CAPACITY) > 0;
			String trend = power > 0 ? "Charging +" + kw(power) : power < 0 ? "Discharging " + kw(-power)
					: !fed ? "Not charging: no generator on its power network" : permille == 0 ? "Empty: no spare power to charge it"
					: permille >= 1000 ? "Full" : "Holding charge";
			line(context, trend, 12, 30, power < 0 ? WARN : !fed ? BAD : permille == 0 ? MUTED : GOOD);
			int edge = Math.max(1, stat(Stat.ARRAY_EDGE));
			line(context, "Charge " + permille / 10 + "%", 12, 44, TEXT);
			bar(context, 12, 56, 206, permille / 1000.0, GOOD);
			line(context, edge > 1 ? String.format(java.util.Locale.ROOT, "Grid-Scale Battery %dx%dx%d: %.1f MJ, %d%% efficient",
					edge, edge, edge, stat(Stat.SOURCE_CAPACITY) / 10000.0, Math.round(dev.rackcraft.world.SimTicker.batteryEfficiency(edge) * 100))
					: stat(Stat.LOCKED_CUBE) > 0 ? notFormed() : "A lone bank: build a cube of them for a Grid-Scale Battery", 12, 68,
					stat(Stat.LOCKED_CUBE) > 0 ? WARN : MUTED);
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
		} else if (id.equals("heat_recovery_plant")) {
			int edge = stat(Stat.ARRAY_EDGE);
			int moved = stat(Stat.COOLING_KW);
			int villagers = stat(Stat.COOLING_DETAIL);
			String state = edge < 2 ? notFormed()
					: moved > 0 ? "Selling " + kw(moved) + " of heat to the village"
					: villagers == 0 ? "No customers: no villagers within 64 blocks" : "Idle: no heat on its coolant loop";
			line(context, state, 12, 30, edge < 2 || villagers == 0 ? WARN : moved > 0 ? GOOD : MUTED);
			line(context, villagers + (villagers == 1 ? " villager takes " : " villagers take ") + kw(stat(Stat.SOURCE_CAPACITY)),
					12, 44, TEXT);
			line(context, String.format(java.util.Locale.ROOT, "Earning %,.1f RC/s. Needs no power or water", stat(Stat.INCOME) / 10.0),
					12, 56, stat(Stat.INCOME) > 0 ? GOOD : MUTED);
			loopLine(context, 68);
			return;
		} else if (id.equals("desalination_plant")) {
			int edge = stat(Stat.ARRAY_EDGE);
			int state = stat(Stat.PUMP_STATUS);
			boolean running = state == dev.rackcraft.world.FreshwaterCooling.PumpStatus.PUMPING.ordinal();
			String text = edge < 2 ? notFormed()
					: running ? "Making fresh water" : state == dev.rackcraft.world.FreshwaterCooling.PumpStatus.NO_POWER.ordinal()
					? "No power" : "Not touching water: any water works, the sea too";
			line(context, text, 12, 30, running ? GOOD : edge < 2 ? WARN : BAD);
			line(context, String.format(java.util.Locale.ROOT, "Supplies %d units to towers on its loop (4 each)",
					Math.max(edge, 0) * Math.max(edge, 0) * Math.max(edge, 0) * dev.rackcraft.world.UtilityPlants.DESAL_UNITS_PER_CORE), 12, 44, TEXT);
			line(context, stat(Stat.PUMP_SOURCES) + " water blocks touching it; it never drains them", 12, 56, MUTED);
			loopLine(context, 68);
		} else if (id.equals("grid_substation")) {
			int edge = stat(Stat.ARRAY_EDGE);
			int price = stat(Stat.COOLING_DETAIL);
			boolean fed = stat(Stat.NETWORK_CAPACITY) > 0;
			line(context, edge < 2 ? notFormed() : power > 0 ? "Exporting " + kw(power)
					: !fed ? "Idle: no generator on its power network" : "Idle: no spare solar, wind or nuclear",
					12, 30, edge < 2 ? WARN : power > 0 ? GOOD : !fed ? BAD : MUTED);
			line(context, String.format(java.util.Locale.ROOT, "Sells up to %s. Price x%.1f (%s)", kw(stat(Stat.SOURCE_CAPACITY)),
					price / 100.0, dev.rackcraft.world.UtilityPlants.priceName(price / 100.0)), 12, 44, TEXT);
			line(context, String.format(java.util.Locale.ROOT, "Earning %,.1f RC/s", stat(Stat.INCOME) / 10.0), 12, 56,
					stat(Stat.INCOME) > 0 ? GOOD : MUTED);
		} else if (!dev.rackcraft.block.MachineBlockEntity.networkKinds(id).contains(dev.rackcraft.sim.NetKind.POWER)) {
			// Routers run on fiber alone: show what the network carries instead of a power readout.
			int bandwidth = stat(Stat.DATA_BANDWIDTH);
			double demand = stat(Stat.DATA_DEMAND) / 10.0;
			int racks = stat(Stat.DATA_RACKS);
			boolean short_ = bandwidth < demand;
			line(context, racks == 0 ? "No racks on this fiber network yet" : short_ ? "Bandwidth-limited: add routers"
					: "Online: carrying every rack", 12, 30, racks == 0 ? MUTED : short_ ? WARN : GOOD);
			line(context, String.format(java.util.Locale.ROOT, "%d racks need %.1f RC/s; routers carry %,d", racks, demand, bandwidth),
					12, 44, TEXT);
			line(context, "Needs no power: Uplink 100 RC/s, Core 1,000 RC/s", 12, 56, MUTED);
			return;
		} else if (id.equals("pdu")) {
			boolean live = stat(Stat.NETWORK_CAPACITY) > 0;
			line(context, live ? "Energised" : "Dead: no power source on this network", 12, 30, live ? GOOD : BAD);
		} else {
			int satisfaction = stat(Stat.SATISFACTION);
			line(context, satisfaction >= 100 ? "Fully powered" : satisfaction > 0 ? "Underpowered" : "No power",
					12, 30, satisfaction >= 100 ? GOOD : satisfaction > 0 ? WARN : BAD);
			line(context, "Draw " + kw(power) + "  (" + satisfaction + "% supplied)", 12, 44, TEXT);
		}
		if (id.equals("cdu")) {
			int caught = stat(Stat.COOLING_KW);
			line(context, caught > 0 ? "Catching " + kw(caught) + " of rack exhaust" : "Against a rack's back it catches exhaust",
					12, 44 + 12, caught > 0 ? GOOD : MUTED);
			loopLine(context, 68);
		}
		int top = id.equals("cdu") || id.equals("desalination_plant") || id.equals("battery_bank") ? 84 : 72;
		line(context, "Power network", 12, top, MUTED);
		line(context, "Delivering " + kw(stat(Stat.NETWORK_DELIVERED)) + " of " + kw(stat(Stat.NETWORK_DEMAND)) + " demand", 12, top + 12, TEXT);
		line(context, "Generating capacity " + kw(stat(Stat.NETWORK_CAPACITY)), 12, top + 24, TEXT);
	}

	/** How worn an array or tower is, and that a Maintenance Drone fixes it. */
	private void wearLine(DrawContext context, int y) {
		int wear = stat(Stat.WEAR);
		double loss = wear / 100.0 * dev.rackcraft.RackcraftConfig.values.renewables.wearLoss * 100;
		line(context, wear < 1 ? "Freshly serviced" : String.format(java.util.Locale.ROOT, "Wear %d%%: %.1f%% less output", wear, loss),
				12, y, wear >= 50 ? WARN : MUTED);
		int serviceAt = (int) Math.round(dev.rackcraft.world.Renewables.SERVICE_AT * 100);
		line(context, wear >= serviceAt ? "Due for service: a Drone Dock within " + dev.rackcraft.world.DroneDocks.RANGE + " blocks will send a drone"
				: "Serviced at " + serviceAt + "% wear by a Drone Dock within " + dev.rackcraft.world.DroneDocks.RANGE + " blocks", 12, y + 12, MUTED);
	}

	private String notFormed() {
		return dev.rackcraft.world.ReactorArrays.notFormed(stat(Stat.LOCKED_CUBE));
	}

	/** Every line on this screen is cut to the panel, so a big grid's figures can't run off the edge. */
	@Override
	protected void line(DrawContext context, String text, int x, int y, int color) {
		lineFit(context, text, x, y, backgroundWidth - x - 12, color);
	}

	/** The coolant loop's budget: heat put in against what its sinks can take. */
	private void loopLine(DrawContext context, int y) {
		int heat = stat(Stat.LOOP_HEAT);
		int capacity = stat(Stat.LOOP_CAPACITY);
		lineFit(context, "Loop: " + kw(heat) + " in, sinks can take " + kw(capacity), 12, y, 206,
				heat > capacity ? BAD : heat > capacity * 0.8 ? WARN : GOOD);
	}
}
