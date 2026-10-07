package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * The port line on a cube's screen. On the port core: how much the cube holds and a Collect button that hands over
 * all of it. On any other core: where the port is.
 */
final class CubePort {
	private static final int BUTTON_X = 116;
	private static final int BUTTON_WIDTH = 52;
	private static int buttonY = -100;

	private CubePort() {}

	static void draw(RackcraftHandledScreen screen, DrawContext context, int x, int y) {
		buttonY = -100;
		if (screen.stat(Stat.ARRAY_EDGE) < 2) return;
		var text = MinecraftClient.getInstance().textRenderer;
		if (screen.stat(Stat.CUBE_PORT) == 0) {
			screen.wrappedClamped(context, Text.literal("Products gather in the port: the marked bottom corner"), x, y, 160, 2, RackcraftHandledScreen.MUTED);
			return;
		}
		int held = screen.stat(Stat.CUBE_OUTPUT) + screen.stat(Stat.CUBE_BYPRODUCT);
		context.drawText(text, "Port: " + held + " made", x, y + 2, held > 0 ? RackcraftHandledScreen.GOOD : RackcraftHandledScreen.MUTED, false);
		buttonY = y;
		context.fill(BUTTON_X, y, BUTTON_X + BUTTON_WIDTH, y + 12, held > 0 ? 0xFF2F6E55 : 0xFF3A525C);
		context.drawBorder(BUTTON_X, y, BUTTON_WIDTH, 12, 0xFF0F171C);
		String label = "Collect all";
		context.drawText(text, label, BUTTON_X + (BUTTON_WIDTH - text.getWidth(label)) / 2, y + 2, 0xFFFFFFFF, true);
	}

	/** {@code mouseX} and {@code mouseY} relative to the panel. */
	static boolean click(RackcraftHandledScreen screen, double mouseX, double mouseY) {
		if (mouseX < BUTTON_X || mouseX >= BUTTON_X + BUTTON_WIDTH || mouseY < buttonY || mouseY >= buttonY + 12) return false;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.interactionManager == null) return false;
		client.interactionManager.clickButton(screen.getScreenHandler().syncId, MachineScreenHandler.COLLECT_BUTTON);
		client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
		return true;
	}
}
