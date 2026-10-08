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
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

/**
 * The Site Planner: what it is doing or waiting for, the site and layout (with Start/Pause and Layout buttons), how far
 * the current phase has got, the drones out, and a row of slots for drones, hydrogen and materials.
 */
public final class SiteScreen extends RackcraftHandledScreen {
	private static final int WIDTH = 176;
	private static final String[] UNITS = {"arrays", "tracking arrays", "towers"};
	private ButtonWidget start;
	private ButtonWidget layout;

	public SiteScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, WIDTH, 252);
	}

	@Override
	protected void init() {
		super.init();
		start = addDrawableChild(ButtonWidget.builder(Text.literal("Start"), button -> click(MachineScreenHandler.START_BUTTON))
				.dimensions(x + WIDTH - 60, y + 27, 52, 14).build());
		layout = addDrawableChild(ButtonWidget.builder(Text.literal("Layout"), button -> click(MachineScreenHandler.LAYOUT_BUTTON))
				.dimensions(x + WIDTH - 60, y + 43, 52, 14).build());
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
		start.setMessage(Text.literal(running ? "Pause" : "Start"));
		start.active = hasSite && phase != SitePlanner.Phase.DONE || running;
		layout.active = !running;

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
				default -> "Working";
			};
			case NO_DRONES -> "Needs Construction Drones (first slot or storage)";
			case NO_TERRAFORMERS -> "Needs Terraforming Drones (second slot or storage)";
			case NO_FUEL -> "Out of hydrogen: load Hydrogen Canisters";
			case NEEDS_MATERIALS -> "Waiting for materials";
			case BLOCKED -> "Stuck: something on the site is in the way";
			case DONE -> "Done: the site is built and wired in";
		};
		int color = switch (status) {
			case WORKING, DONE -> GOOD;
			case PAUSED, NO_AREA -> MUTED;
			case NO_POWER, NO_FUEL -> BAD;
			default -> WARN;
		};
		wrappedClamped(context, Text.literal(headline), 8, 30, 104, 2, color);

		int total = site(SitePlanner.R_TOTAL);
		if (hasSite) {
			String level = site(SitePlanner.R_LEVEL) != 0 || phase.ordinal() >= SitePlanner.Phase.LEVEL.ordinal()
					? ", level Y " + site(SitePlanner.R_LEVEL) : "";
			lineFit(context, "Site " + site(SitePlanner.R_WIDTH) + " x " + site(SitePlanner.R_DEPTH) + level, 8, 62, 160, TEXT);
		} else {
			lineFit(context, "Up to " + RackcraftConfig.values.construction.maxSide + " x " + RackcraftConfig.values.construction.maxSide
					+ ", within " + RackcraftConfig.values.construction.maxDistance + " blocks", 8, 62, 160, MUTED);
		}
		lineFit(context, "Layout: " + chosen.label + (total > 0 ? " (" + total + " " + UNITS[chosen.ordinal()] + ")" : ""), 8, 73, 160, TEXT);

		int left = site(SitePlanner.R_LEFT);
		int built = site(SitePlanner.R_BUILT);
		String progress = switch (phase) {
			case CLEAR -> "1 Clear: " + left + " soft blocks to go";
			case LEVEL -> "2 Level: " + left + (left == 1 ? " block" : " blocks") + " to move";
			case BUILD -> "3 Build: " + built + " of " + total + " " + UNITS[chosen.ordinal()] + " up";
			case WIRE -> "4 Wire: " + left + " cable to lay";
			case DONE -> "All " + total + " " + UNITS[chosen.ordinal()] + " built and wired";
			case NONE -> "";
		};
		lineFit(context, progress, 8, 84, 160, MUTED);
		if (phase == SitePlanner.Phase.BUILD || phase == SitePlanner.Phase.DONE) bar(context, 8, 95, 160, total > 0 ? built / (double) total : 0, GOOD);

		int need = site(SitePlanner.R_NEED_ITEM);
		if (need > 0) {
			String name = new ItemStack(Registries.ITEM.get(need - 1)).getName().getString();
			lineFit(context, "Needs " + String.format(Locale.ROOT, "%,d", site(SitePlanner.R_NEED_COUNT)) + " more " + name, 8, 104, 160, WARN);
		} else if (site(SitePlanner.R_HAS_BLOCKED) != 0) {
			lineFit(context, "In the way at " + site(SitePlanner.R_BLOCKED_X) + ", " + site(SitePlanner.R_BLOCKED_Y) + ", "
					+ site(SitePlanner.R_BLOCKED_Z), 8, 104, 160, WARN);
		}
		int canisters = handler.getSlot(SitePlanner.FUEL_SLOT).getStack().getCount();
		int trips = stat(Stat.TOOL_USES) + canisters * RackcraftConfig.values.construction.tripsPerCanister;
		lineFit(context, "Out: " + stat(Stat.WORKERS) + " building, " + site(SitePlanner.R_TERRAFORMERS_OUT) + " levelling.  Fuel: "
				+ trips + (trips == 1 ? " trip" : " trips"), 8, 114, 160, TEXT);
		lineFit(context, "Drones, terraformers, hydrogen, materials", 8, 124, 160, MUTED);
	}

	private static <E> E enumAt(E[] values, int index) {
		return values[Math.max(0, Math.min(values.length - 1, index))];
	}
}
