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
			"The pad's centre can't see the sky", "Load a payload", "Not enough Rocket Stages", "Not enough hydrogen in the tank",
			"No power", "Countdown", "In flight"};
	private ButtonWidget launch;

	public LaunchScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 176, 240);
	}

	@Override
	protected void init() {
		super.init();
		launch = addDrawableChild(ButtonWidget.builder(Text.literal("Launch"), button -> {
			if (client != null && client.interactionManager != null) {
				client.interactionManager.clickButton(handler.syncId, MachineScreenHandler.LAUNCH_BUTTON);
			}
		}).dimensions(x + 112, y + 126, 56, 16).build());
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		int state = Math.max(0, Math.min(STATES.length - 1, stat(Stat.PROCESS_STATUS)));
		LaunchPads.Status status = LaunchPads.Status.values()[state];
		launch.active = status == LaunchPads.Status.READY;
		line(context, "Stages", 8, 31, MUTED);
		line(context, "Payload", 74, 31, MUTED);
		line(context, "H2 in", 142, 31, MUTED);

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
		lineFit(context, headline, 8, 64, 160, color);

		ItemStack payload = handler.getSlot(LaunchPads.PAYLOAD_SLOT).getStack();
		int needed = LaunchPads.stagesFor(payload);
		int stages = 0;
		for (int slot = 0; slot < LaunchPads.PAYLOAD_SLOT; slot++) stages += handler.getSlot(slot).getStack().getCount();
		if (payload.isOf(Items.FILLED_MAP)) lineFit(context, "Survey results are in: take the map", 8, 76, 160, GOOD);
		else if (needed > 0) lineFit(context, payload.getName().getString() + ": " + needed + (needed == 1 ? " stage" : " stages"), 8, 76, 160, TEXT);
		else lineFit(context, "Payloads are built on the Assembly Line", 8, 76, 160, MUTED);
		int tank = stat(Stat.LAUNCH_TANK);
		int fuel = Math.max(1, needed) * LaunchPads.CANISTERS_PER_STAGE;
		lineFit(context, String.format(Locale.ROOT, "Stages %d/%d   Hydrogen %,d/%,d", stages, Math.max(needed, 1), tank, fuel), 8, 87, 160,
				stages >= needed && tank >= fuel ? TEXT : WARN);
		bar(context, 8, 99, 160, tank / (double) LaunchPads.TANK_CAPACITY, 0xFF7FB8E8);

		int comms = stat(Stat.ORBIT_COMMS);
		int datacenters = stat(Stat.ORBIT_DATACENTERS);
		int mirrors = stat(Stat.ORBIT_MIRRORS);
		lineFit(context, String.format(Locale.ROOT, "Orbit: %d comms (max %d), %d data centers", comms, OrbitState.MAX_COMMS, datacenters),
				8, 108, 160, MUTED);
		lineFit(context, String.format(Locale.ROOT, "+%.0f%% compute, %d mirrors beaming %s", datacenters * OrbitState.COMPUTE_PER_MODULE * 100,
				mirrors, kw((int) Math.round(mirrors * OrbitState.MIRROR_KW * 10))), 8, 118, 160, MUTED);
		lineFit(context, stat(Stat.ORBIT_LAUNCHES) + " launches", 8, 130, 100, MUTED);
	}
}
