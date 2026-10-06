package dev.rackcraft.client.screen;

import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/** Kids' Art Table and Scriptorium Desk: paper and tool in, training data out, and who is working. */
public final class WorkstationScreen extends RackcraftHandledScreen {
	public WorkstationScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 176, 186);
	}

	@Override
	protected void init() {
		super.init();
		playerInventoryTitleY = backgroundHeight - 94;
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		boolean table = blockId().equals("art_table");
		line(context, "Paper", 22, 30, MUTED);
		line(context, table ? "Crayons" : "Ink", 48, 30, MUTED);
		line(context, "Output", 128, 30, MUTED);
		context.fill(74, 47, 126, 49, 0xFF3A525C);
		int workers = stat(Stat.WORKERS);
		bar(context, 76, 52, 48, stat(Stat.WORK_PROGRESS) / 100.0, GOOD);
		String status;
		int color;
		if (table) {
			status = workers == 0 ? "No kids: baby villagers come to a stocked table"
					: workers + (workers == 1 ? " kid is" : " kids are") + " drawing";
			color = workers > 0 ? GOOD : WARN;
			line(context, "Drawings toward next aggregate: " + stat(Stat.ITEMS_MADE) + "/4", 8, 82, TEXT);
		} else {
			boolean bound = stat(Stat.BOUND) == 1;
			status = !bound ? "No librarian: use Shackles on one nearby" : workers > 0 ? "The librarian is writing, under protest"
					: "The shackled librarian is out of reach";
			color = workers > 0 ? GOOD : WARN;
			line(context, "Corpora on this ink sac: " + stat(Stat.TOOL_USES) + "/4", 8, 82, TEXT);
		}
		wrapped(context, Text.literal(status), 8, 62, 160, color);
	}
}
