package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import dev.rackcraft.world.LaunchPads;
import dev.rackcraft.world.OrbitState;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

/** Launch Control: stages, payload and hydrogen in, what is stopping a launch, the countdown, and what is in orbit. */
public final class LaunchScreen extends RackcraftHandledScreen {
	private static final String[] STATES = {"Ready for launch", "Not beside a whole 3x3 Launch Pad",
			"Something is over the pad: it needs open sky", "Load a payload", "Not enough Rocket Stages", "Not enough hydrogen in the tank",
			"No power", "Countdown", "In flight"};
	private static final int WIDTH = 230;
	private static final int TEXT_WIDTH = WIDTH - 24;
	private ButtonWidget launch;

	public LaunchScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, WIDTH, 240);
	}

	@Override
	protected void init() {
		super.init();
		launch = addDrawableChild(ButtonWidget.builder(Text.literal("Launch"), button -> {
			if (client != null && client.interactionManager != null) {
				client.interactionManager.clickButton(handler.syncId, MachineScreenHandler.LAUNCH_BUTTON);
			}
		}).dimensions(x + WIDTH - 72, y + 126, 60, 16).build());
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		int state = Math.max(0, Math.min(STATES.length - 1, stat(Stat.PROCESS_STATUS)));
		LaunchPads.Status status = LaunchPads.Status.values()[state];
		launch.active = status == LaunchPads.Status.READY;
		line(context, "Rocket Stages", 12, 31, MUTED);
		line(context, "Payload", 98, 31, MUTED);
		line(context, "Hydrogen", 172, 31, MUTED);

		String headline = switch (status) {
			case COUNTDOWN -> String.format(Locale.ROOT, "T-%d s", (stat(Stat.LAUNCH_COUNTDOWN) + 19) / 20);
			case IN_FLIGHT -> String.format(Locale.ROOT, "In flight: %d s to orbit", (stat(Stat.LAUNCH_FLIGHT) + 19) / 20);
			default -> STATES[state];
		};
		int color = switch (status) {
			case READY, COUNTDOWN, IN_FLIGHT -> GOOD;
			case NO_PAYLOAD -> MUTED;
			case NO_PAD, NO_SKY, NO_POWER -> BAD;
			default -> WARN;
		};
		lineFit(context, headline, 12, 64, TEXT_WIDTH, color);

		ItemStack payload = handler.getSlot(LaunchPads.PAYLOAD_SLOT).getStack();
		int needed = LaunchPads.stagesFor(payload);
		int stages = 0;
		for (int slot = 0; slot < LaunchPads.PAYLOAD_SLOT; slot++) stages += handler.getSlot(slot).getStack().getCount();
		if (payload.isOf(Items.FILLED_MAP)) lineFit(context, "Survey results are in: take the map", 12, 76, TEXT_WIDTH, GOOD);
		else if (needed > 0) lineFit(context, payload.getName().getString() + ": " + needed + (needed == 1 ? " stage" : " stages"), 12, 76, TEXT_WIDTH, TEXT);
		else lineFit(context, "Payloads are built on the Assembly Line", 12, 76, TEXT_WIDTH, MUTED);
		int tank = stat(Stat.LAUNCH_TANK);
		int fuel = Math.max(1, needed) * LaunchPads.CANISTERS_PER_STAGE;
		lineFit(context, String.format(Locale.ROOT, "Stages %d of %d   Tank %,d of %,d canisters", stages, Math.max(needed, 1), tank, fuel), 12, 87, TEXT_WIDTH,
				stages >= needed && tank >= fuel ? TEXT : WARN);
		bar(context, 12, 99, TEXT_WIDTH, tank / (double) LaunchPads.TANK_CAPACITY, 0xFF7FB8E8);

		int comms = stat(Stat.ORBIT_COMMS);
		int datacenters = stat(Stat.ORBIT_DATACENTERS);
		int mirrors = stat(Stat.ORBIT_MIRRORS);
		lineFit(context, String.format(Locale.ROOT, "In orbit: %d of %d comms, %d data centers (+%.0f%% compute)", Math.min(comms, OrbitState.MAX_COMMS),
				OrbitState.MAX_COMMS, datacenters, datacenters * OrbitState.COMPUTE_PER_MODULE * 100), 12, 108, TEXT_WIDTH, MUTED);
		lineFit(context, String.format(Locale.ROOT, "%d Dyson Mirrors beaming %s", mirrors, kw((int) Math.round(mirrors * OrbitState.MIRROR_KW * 10))),
				12, 118, TEXT_WIDTH, MUTED);
		lineFit(context, stat(Stat.ORBIT_LAUNCHES) + (stat(Stat.ORBIT_LAUNCHES) == 1 ? " launch" : " launches"), 12, 130, 120, MUTED);
	}
}
