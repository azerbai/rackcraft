package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import dev.rackcraft.storage.DriveItem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

/** Storage Array (8 drive bays) and Tape Library (4 tape bays): status plus a fill bar under every bay. */
public final class StorageScreen extends RackcraftHandledScreen {
	public StorageScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 176, 186);
	}

	@Override
	protected void init() {
		super.init();
		playerInventoryTitleY = backgroundHeight - 94;
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		boolean array = blockId().equals("storage_array");
		int supplied = stat(Stat.SATISFACTION);
		boolean overheated = array && stat(Stat.THERMAL) <= 0 && supplied >= 50;
		boolean online = supplied >= 50 && !overheated;
		Text state = Text.literal(online ? "Online" : overheated ? "Offline: overheated" : "Offline: no power");
		context.drawText(textRenderer, state, 100, 24, online ? GOOD : BAD, false);
		line(context, "Draw " + kw(stat(Stat.POWER)), 100, 36, TEXT);
		if (array) {
			line(context, "Inlet " + celsius(stat(Stat.INLET)), 100, 48, stat(Stat.INLET) > 270 ? WARN : TEXT);
			line(context, "Exhaust " + celsius(stat(Stat.EXHAUST)), 100, 60, MUTED);
		} else {
			line(context, "Cold storage: 2 s reads", 100, 48, MUTED);
		}
		long used = 0;
		long capacity = 0;
		for (Slot slot : handler.slots) {
			if (slot.inventory instanceof PlayerInventory) continue;
			ItemStack stack = slot.getStack();
			if (!(stack.getItem() instanceof DriveItem drive)) continue;
			double fill = DriveItem.fill(stack);
			used += stack.hasNbt() ? stack.getNbt().getLong("Used") : 0;
			capacity += drive.capacity();
			bar(context, slot.x - 1, slot.y + 18, 18, fill, fill > 0.9 ? BAD : fill > 0.7 ? WARN : drive.cold() ? 0xFF5BA7E0 : GOOD);
		}
		line(context, String.format(java.util.Locale.ROOT, "%,d / %,d items", used, capacity), 8, 78, TEXT);
	}
}
