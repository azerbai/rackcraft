package dev.rackcraft.client.screen;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import dev.rackcraft.world.SitePlanner;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import dev.rackcraft.sim.ServerModel;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;

/**
 * The Site Planner: what it is doing or waiting for, the site and layout (with Start/Pause and Layout buttons), how far
 * the current phase has got, the drones out, and a row of slots for drones, hydrogen and materials.
 */
public final class SiteScreen extends RackcraftHandledScreen {
	private static final int WIDTH = 176;
	private static final String[] UNITS = {"arrays", "tracking arrays", "towers", "rack columns", "reactor layers", "print trips", "racks"};
	private ButtonWidget start;
	private ButtonWidget layout;
	private ButtonWidget buy;
	private ButtonWidget docks;
	private ButtonWidget hallRack;
	private ButtonWidget hallModule;
	private ButtonWidget cube;

	public SiteScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, WIDTH, 284);
	}

	@Override
	protected void init() {
		super.init();
		start = addDrawableChild(ButtonWidget.builder(Text.literal("Start"), button -> click(MachineScreenHandler.START_BUTTON))
				.dimensions(x + WIDTH - 60, y + 27, 52, 12).build());
		layout = addDrawableChild(ButtonWidget.builder(Text.literal("Layout"), button -> click(MachineScreenHandler.LAYOUT_BUTTON))
				.dimensions(x + WIDTH - 60, y + 40, 52, 12).build());
		buy = addDrawableChild(ButtonWidget.builder(Text.literal("Buy"), button -> click(MachineScreenHandler.BUY_BUTTON))
				.dimensions(x + WIDTH - 60, y + 53, 52, 12).build());
		docks = addDrawableChild(ButtonWidget.builder(Text.literal("Docks"), button -> click(MachineScreenHandler.DOCK_BUTTON))
				.dimensions(x + WIDTH - 60, y + 66, 52, 12).build());
		hallRack = addDrawableChild(ButtonWidget.builder(Text.literal("Rack"), button -> click(MachineScreenHandler.HALL_RACK_BUTTON))
				.dimensions(x + 8, y + 146, 76, 14).build());
		hallModule = addDrawableChild(ButtonWidget.builder(Text.literal("Module"), button -> click(MachineScreenHandler.HALL_MODULE_BUTTON))
				.dimensions(x + 88, y + 146, 80, 14).build());
		cube = addDrawableChild(ButtonWidget.builder(Text.literal("Cube"), button -> click(MachineScreenHandler.REACTOR_EDGE_BUTTON))
				.dimensions(x + 8, y + 146, 160, 14).build());
	}

	private void click(int button) {
		if (client != null && client.interactionManager != null) client.interactionManager.clickButton(handler.syncId, button);
	}

	private int site(int reading) {
		return stat(Stat.SITE + reading);
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		SitePlanner.Phase phase = enumAt(SitePlanner.Phase.values(), site(SitePlanner.R_PHASE));
		SitePlanner.Status status = enumAt(SitePlanner.Status.values(), stat(Stat.PROCESS_STATUS));
		SitePlanner.Layout chosen = enumAt(SitePlanner.Layout.values(), site(SitePlanner.R_LAYOUT));
		boolean running = site(SitePlanner.R_RUNNING) != 0;
		boolean hasSite = status != SitePlanner.Status.NO_AREA;
		boolean awaiting = status == SitePlanner.Status.AWAITING_APPROVAL;
		start.setMessage(Text.literal(awaiting ? "Approve" : running ? "Pause" : "Start"));
		start.active = hasSite && phase != SitePlanner.Phase.DONE || running || awaiting;
		layout.active = !running;
		boolean hall = chosen == SitePlanner.Layout.HALL;
		boolean reactor = chosen == SitePlanner.Layout.REACTOR;
		boolean retrofit = chosen == SitePlanner.Layout.RETROFIT;
		hallRack.active = (hall || retrofit) && !running;
		hallModule.active = hall && !running;
		hallRack.visible = !reactor;
		hallModule.visible = !reactor;
		cube.visible = reactor;
		cube.active = reactor && !running;
		int edge = site(SitePlanner.R_EDGE);
		cube.setMessage(Text.literal(edge < 2 ? "Cube: site too small" : "Cube: " + edge + " x " + edge + " x " + edge + " (" + edge * edge * edge + " reactors)"));
		cube.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
				"Reactor Cube layout: the size of each cube of Modular Reactors, 2 up to 10 a side. Bigger cubes are more economical per reactor, but anything over 5 needs research (Structural Engineering for 6 and 7, Space Frame Design for 8 and 9, Arcology for 10) and a site at least that big. Cubes stand a block apart.")));
		ServerModel.Tier[] tiers = ServerModel.Tier.values();
		String rackName = new ItemStack(Registries.ITEM.get(new Identifier("rackcraft",
				tiers[Math.max(0, Math.min(tiers.length - 1, site(SitePlanner.R_HALL_RACK)))].blockId()))).getName().getString();
		String moduleName = new ItemStack(Registries.ITEM.get(site(SitePlanner.R_HALL_MODULE))).getName().getString();
		hallRack.setMessage(Text.literal(textRenderer.trimToWidth(rackName, 68)));
		hallModule.setMessage(Text.literal(textRenderer.trimToWidth(moduleName, 72)));
		String tip = retrofit ? "Retrofit layout: " : "Data Hall blueprint (Data Hall layout only): ";
		hallRack.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(retrofit
				? tip + "every rack on the site below " + rackName + " is upgraded to it in place, keeping its modules. A rack whose modules the tier won't take is left alone, and so is any tier whose research isn't done."
				: tip + "the rack tier every rack in the hall is built from: " + rackName
				+ ". Higher tiers take only better modules; the hall adds Chillers as the racks get hotter.")));
		hallModule.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(tip + "the module that fills every bay: " + moduleName + ".")));
		boolean docking = site(SitePlanner.R_DOCKING) != 0;
		docks.setMessage(Text.literal(docking ? "Docks: On" : "Docks: Off"));
		docks.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
				"When on, the finished site gets Drone Docks (from storage, or bought at the Exchange) stocked with drones and hydrogen from storage, and kept topped up.")));
		boolean buying = site(SitePlanner.R_BUYING) != 0;
		buy.setMessage(Text.literal(buying ? "Buy: On" : "Buy: Off"));
		buy.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
				"When on, whatever the slots and storage can't supply is bought from the Crypto Exchange with RackCoin.")));

		String headline = switch (status) {
			case NO_AREA -> "No site yet: mark two corners with a Survey Stake, then use it on this";
			case NOT_LOADED -> "The site isn't loaded: stay nearer to it";
			case PAUSED -> "Ready: press Start";
			case NO_POWER -> "Stopped: no power";
			case WORKING -> switch (phase) {
				case CLEAR -> "Clearing the site";
				case LEVEL -> "Levelling the site";
				case BUILD -> "Building";
				case WIRE -> "Laying cable";
				case DOCK -> "Placing Drone Docks";
				default -> "Working";
			};
			case NO_DRONES -> "Needs Construction Drones (first slot or storage)";
			case NO_TERRAFORMERS -> "Needs Terraforming Drones (second slot or storage)";
			case NO_FUEL -> "Out of hydrogen: load Hydrogen Canisters";
			case NEEDS_MATERIALS -> "Waiting for materials";
			case AWAITING_APPROVAL -> String.format(Locale.ROOT, "Quote: %,d RC for %d %s. Press Approve to let the drones buy",
					site(SitePlanner.R_QUOTE), site(SitePlanner.R_QUOTE_LINES), site(SitePlanner.R_QUOTE_LINES) == 1 ? "item" : "items");
			case BLOCKED -> "Stuck: something on the site is in the way";
			case DONE -> "Done: the site is built and wired in";
			case NOTHING_HERE -> chosen == SitePlanner.Layout.RETROFIT ? "No racks on this site to upgrade"
					: site(SitePlanner.R_BP_STATE) == 1 ? "Locked: research Digital Twin at the Operations Terminal"
					: site(SitePlanner.R_BP_STATE) == 3 ? "Too big: the Blueprint is " + site(SitePlanner.R_BP_WIDTH) + " x " + site(SitePlanner.R_BP_DEPTH)
							+ ", the site only " + site(SitePlanner.R_WIDTH) + " x " + site(SitePlanner.R_DEPTH)
					: "Put a written Blueprint in one of the material slots";
			case TOO_SMALL -> chosen == SitePlanner.Layout.HALL ? "Too small: a Data Hall needs a site at least 5 deep"
					: chosen == SitePlanner.Layout.REACTOR ? "Too small: a Reactor Cube needs a site at least 2 x 2"
					: "Too small: nothing in this layout fits the site";
		};
		int color = switch (status) {
			case WORKING, DONE -> GOOD;
			case PAUSED, NO_AREA -> MUTED;
			case AWAITING_APPROVAL -> WARN;
			case TOO_SMALL -> BAD;
			case NO_POWER, NO_FUEL -> BAD;
			default -> WARN;
		};
		wrappedClamped(context, Text.literal(headline), 8, 30, 104, 2, color);

		int total = site(SitePlanner.R_TOTAL);
		if (hasSite) {
			String level = site(SitePlanner.R_LEVEL) != 0 || phase.ordinal() >= SitePlanner.Phase.LEVEL.ordinal()
					? ", level Y " + site(SitePlanner.R_LEVEL) : "";
			lineFit(context, "Site " + site(SitePlanner.R_WIDTH) + " x " + site(SitePlanner.R_DEPTH) + level, 8, 79, 160, TEXT);
		} else {
			lineFit(context, "Up to " + RackcraftConfig.values.construction.maxSide + " x " + RackcraftConfig.values.construction.maxSide
					+ ", within " + RackcraftConfig.values.construction.maxDistance + " blocks", 8, 79, 160, MUTED);
		}
		int cubes = site(SitePlanner.R_CUBES);
		String size = chosen == SitePlanner.Layout.HALL && total > 0
				? " (" + total * SitePlanner.HALL_RACKS_PER_COLUMN + " racks, " + kw(site(SitePlanner.R_DRAW_KW) * 10) + ")"
				: chosen == SitePlanner.Layout.REACTOR && cubes > 0
				? String.format(Locale.ROOT, " (%d x %d-cube, %,d MW)", cubes, edge, (long) cubes * edge * edge * edge * 500 / 1000)
				: total > 0 ? " (" + total + " " + UNITS[chosen.ordinal()] + ")" : "";
		lineFit(context, size.isEmpty() ? "Layout: " + chosen.label : chosen.label + ":" + size.substring(2, size.length() - 1), 8, 89, 160, TEXT);

		int left = site(SitePlanner.R_LEFT);
		int built = site(SitePlanner.R_BUILT);
		String progress = switch (phase) {
			case CLEAR -> "1 Clear: " + left + " soft blocks to go";
			case LEVEL -> "2 Level: " + left + (left == 1 ? " block" : " blocks") + " to move";
			case BUILD -> chosen == SitePlanner.Layout.RETROFIT ? "Retrofit: " + built + " of " + total + " racks up to tier, " + left + " to go"
					: "3 Build: " + built + " of " + total + " " + UNITS[chosen.ordinal()] + " up";
			case WIRE -> "4 Wire: " + left + " cable to lay";
			case DOCK -> "5 Dock: " + site(SitePlanner.R_DOCKS_PLACED) + " of " + site(SitePlanner.R_DOCKS) + " Drone Docks placed";
			case DONE -> "All " + total + " " + UNITS[chosen.ordinal()] + " built and wired"
					+ (site(SitePlanner.R_DOCKS) > 0 ? ", " + site(SitePlanner.R_DOCKS) + (site(SitePlanner.R_DOCKS) == 1 ? " dock" : " docks") : "");
			case NONE -> "";
		};
		lineFit(context, progress, 8, 99, 160, MUTED);
		if (phase == SitePlanner.Phase.BUILD || phase == SitePlanner.Phase.DONE) bar(context, 8, 109, 160, total > 0 ? built / (double) total : 0, GOOD);

		int need = site(SitePlanner.R_NEED_ITEM);
		if (need > 0) {
			String name = new ItemStack(Registries.ITEM.get(need - 1)).getName().getString();
			lineFit(context, "Needs " + String.format(Locale.ROOT, "%,d", site(SitePlanner.R_NEED_COUNT)) + " more " + name, 8, 118, 160, WARN);
		} else if (site(SitePlanner.R_HAS_BLOCKED) != 0) {
			lineFit(context, "In the way at " + site(SitePlanner.R_BLOCKED_X) + ", " + site(SitePlanner.R_BLOCKED_Y) + ", "
					+ site(SitePlanner.R_BLOCKED_Z), 8, 118, 160, WARN);
		} else if (site(SitePlanner.R_SPENT) > 0) {
			lineFit(context, "Bought from the Exchange: " + String.format(Locale.ROOT, "%,d", site(SitePlanner.R_SPENT)) + " RC", 8, 118, 160, MUTED);
		}
		int canisters = handler.getSlot(SitePlanner.FUEL_SLOT).getStack().getCount();
		int trips = stat(Stat.TOOL_USES) + canisters * RackcraftConfig.values.construction.tripsPerCanister;
		lineFit(context, "Out: " + stat(Stat.WORKERS) + " building, " + site(SitePlanner.R_TERRAFORMERS_OUT) + " levelling", 8, 127, 160, TEXT);
		lineFit(context, "Fuel for " + trips + (trips == 1 ? " trip" : " trips") + "; slots below:", 8, 136, 160, MUTED);
	}

	private static <E> E enumAt(E[] values, int index) {
		return values[Math.max(0, Math.min(values.length - 1, index))];
	}
}
