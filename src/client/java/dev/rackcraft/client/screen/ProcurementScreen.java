package dev.rackcraft.client.screen;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.screen.ProcurementScreenHandler;
import dev.rackcraft.world.ProcurementWall;
import dev.rackcraft.world.SitePlanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * The Procurement Wall: for each Site Planner on its grid, where the job stands and any quote waiting for a yes, then the
 * ledger, every purchase the drones made, newest first. Buttons are drawn and hit-tested by hand because the rows come and
 * go with every snapshot.
 */
public final class ProcurementScreen extends HandledScreen<ProcurementScreenHandler> {
	private static final int WIDTH = 320;
	private static final int HEIGHT = 226;
	private static final int BODY_TOP = 36;
	private static final int BODY_BOTTOM = 206;
	private static final int TEXT = 0xFFE5ECEB;
	private static final int GOOD = 0xFF62C5A0;
	private static final int WARN = 0xFFE7A45D;
	private static final int BAD = 0xFFE0645A;
	private static final int MUTED = 0xFFAAB9BA;
	private static final int ACCENT = 0xFFC9983C;

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mouseX, double mouseY) { return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h; }
	}

	private final List<Hit> hits = new ArrayList<>();
	private final Map<String, String> names = new HashMap<>();
	private int scroll;
	private int contentHeight;

	public ProcurementScreen(ProcurementScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		backgroundWidth = WIDTH;
		backgroundHeight = HEIGHT;
		playerInventoryTitleY = -10_000;
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		context.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF1D1A14);
		context.fill(x, y, x + WIDTH, y + 15, 0xFF4A3D22);
		context.fill(x + 4, y + BODY_TOP - 2, x + WIDTH - 4, y + BODY_BOTTOM + 2, 0xFF262218);
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		context.drawText(textRenderer, title, 8, 4, TEXT, false);
		String balance = String.format(Locale.ROOT, "%,d RC", handler.snapshot().credits());
		context.drawText(textRenderer, balance, WIDTH - 8 - textRenderer.getWidth(balance), 4, GOOD, false);
	}

	private String name(String id) {
		return names.computeIfAbsent(id, key -> new ItemStack(Registries.ITEM.get(new Identifier(key))).getName().getString());
	}

	private static String age(long ticks) {
		long seconds = ticks / 20;
		return seconds < 60 ? seconds + "s ago" : seconds < 3600 ? seconds / 60 + "m ago" : seconds / 3600 + "h ago";
	}

	private static String status(int ordinal) {
		SitePlanner.Status[] all = SitePlanner.Status.values();
		return switch (all[Math.max(0, Math.min(all.length - 1, ordinal))]) {
			case NO_AREA -> "no site";
			case NOT_LOADED -> "site not loaded";
			case PAUSED -> "paused";
			case NO_POWER -> "no power";
			case WORKING -> "working";
			case NO_DRONES, NO_TERRAFORMERS -> "needs drones";
			case NO_FUEL -> "out of hydrogen";
			case NEEDS_MATERIALS -> "waiting for materials";
			case BLOCKED -> "stuck";
			case DONE -> "done";
			case TOO_SMALL -> "site too small";
			case AWAITING_APPROVAL -> "waiting for your yes";
			case NOTHING_HERE -> "nothing to do yet";
		};
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		ProcurementWall.Snapshot snapshot = handler.snapshot();
		boolean hud = RackcraftConfig.values.hud.procurement;
		button(context, x + 6, y + 19, 72, 13, hud ? "HUD: On" : "HUD: Off", true, hud ? ACCENT : 0xFF5A5446, mouseX, mouseY, () -> {
			RackcraftConfig.values.hud.procurement = !RackcraftConfig.values.hud.procurement;
			RackcraftConfig.save();
		});
		String hint = "On screen within " + ProcurementWall.HUD_RANGE + " blocks of a powered wall";
		context.drawText(textRenderer, textRenderer.trimToWidth(hint, WIDTH - 94), x + 84, y + 22, MUTED, false);
		context.enableScissor(x + 4, y + BODY_TOP - 2, x + WIDTH - 4, y + BODY_BOTTOM + 2);
		int top = y + BODY_TOP - scroll;
		int end = body(context, snapshot, top, mouseX, mouseY);
		context.disableScissor();
		contentHeight = end - top;
		drawMouseoverTooltip(context, mouseX, mouseY);
	}

	private int body(DrawContext context, ProcurementWall.Snapshot snapshot, int top, int mouseX, int mouseY) {
		int left = x + 10;
		int cursor = top + 2;
		if (!snapshot.powered()) {
			text(context, "No power: the wall is dark. Connect it to the grid.", left, cursor, BAD);
			cursor += 12;
		}
		if (snapshot.planners().isEmpty()) {
			text(context, "No Site Planner on this power grid.", left, cursor, WARN);
			text(context, "Plug the wall into the same grid as a planner and it will keep that planner's books.", left, cursor + 10, MUTED);
			return cursor + 26;
		}
		long totalSpent = 0;
		for (int index = 0; index < snapshot.planners().size(); index++) {
			ProcurementWall.PlannerView planner = snapshot.planners().get(index);
			totalSpent += planner.spent();
			SitePlanner.Layout[] layouts = SitePlanner.Layout.values();
			String layout = layouts[Math.max(0, Math.min(layouts.length - 1, planner.layout()))].label;
			context.fill(x + 6, cursor - 1, x + WIDTH - 6, cursor + 10, 0xFF3A3322);
			text(context, "Planner " + (index + 1) + " at " + planner.pos().getX() + ", " + planner.pos().getY() + ", " + planner.pos().getZ()
					+ "  -  " + layout + ", " + status(planner.status()), left, cursor + 1, TEXT);
			cursor += 13;
			text(context, String.format(Locale.ROOT, "Spent %,d RC on %,d items", planner.spent(), planner.bought()), left, cursor, MUTED);
			cursor += 10;
			if (planner.awaiting()) {
				text(context, String.format(Locale.ROOT, "Quote waiting: %,d RC (the drones buy nothing until you approve)", planner.quoteTotal()), left, cursor, WARN);
				button(context, x + WIDTH - 66, cursor - 2, 54, 12, "Approve", true, 0xFF3F7F5F, mouseX, mouseY, () -> sendApprove(planner.pos()));
				cursor += 11;
				for (ProcurementWall.QuoteView line : planner.quote()) {
					String row = String.format(Locale.ROOT, "%,d x %s @ %,d", line.count(), name(line.item()), line.price());
					String cost = String.format(Locale.ROOT, "%,d RC", line.count() * line.price());
					text(context, textRenderer.trimToWidth(row, 200), left + 6, cursor, TEXT);
					text(context, cost, x + WIDTH - 12 - textRenderer.getWidth(cost), cursor, WARN);
					cursor += 10;
				}
			} else if (planner.quoteTotal() > 0) {
				text(context, String.format(Locale.ROOT, "Quoted %,d RC for this job, approved", planner.quoteTotal()), left, cursor, MUTED);
				cursor += 10;
			}
			cursor += 4;
		}
		long rowsCost = 0;
		for (ProcurementWall.Row row : snapshot.rows()) rowsCost += row.cost();
		context.fill(x + 6, cursor - 1, x + WIDTH - 6, cursor + 10, 0xFF3A3322);
		text(context, String.format(Locale.ROOT, "Ledger: %,d RC spent in all", totalSpent), left, cursor + 1, ACCENT);
		cursor += 13;
		if (snapshot.rows().isEmpty()) {
			text(context, "Nothing bought yet. Every Exchange purchase a drone makes is listed here.", left, cursor, MUTED);
			return cursor + 14;
		}
		text(context, "When", left, cursor, MUTED);
		text(context, "For", left + 34, cursor, MUTED);
		text(context, "Item", left + 80, cursor, MUTED);
		text(context, "Each", x + 250 - textRenderer.getWidth("Each"), cursor, MUTED);
		text(context, "Cost", x + WIDTH - 12 - textRenderer.getWidth("Cost"), cursor, MUTED);
		cursor += 10;
		boolean several = snapshot.planners().size() > 1;
		for (ProcurementWall.Row row : snapshot.rows()) {
			if (cursor + 9 >= y + BODY_TOP - 2 && cursor <= y + BODY_BOTTOM) {
				text(context, age(row.age()), left, cursor, MUTED);
				text(context, textRenderer.trimToWidth(row.purpose(), 44), left + 34, cursor, MUTED);
				String item = (several ? "P" + (row.planner() + 1) + " " : "") + String.format(Locale.ROOT, "%,d x ", row.count()) + name(row.item());
				text(context, textRenderer.trimToWidth(item, 118), left + 80, cursor, TEXT);
				String each = String.format(Locale.ROOT, "%,d", row.price());
				text(context, each, x + 250 - textRenderer.getWidth(each), cursor, MUTED);
				String cost = String.format(Locale.ROOT, "%,d", row.cost());
				text(context, cost, x + WIDTH - 12 - textRenderer.getWidth(cost), cursor, WARN);
			}
			cursor += 10;
		}
		if (rowsCost < totalSpent) {
			text(context, "Older purchases have rolled off the ledger; the total above still counts them.", left, cursor + 2, MUTED);
			cursor += 14;
		}
		return cursor + 4;
	}

	private void sendApprove(net.minecraft.util.math.BlockPos planner) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(handler.syncId);
		buf.writeBlockPos(planner);
		ClientPlayNetworking.send(ProcurementScreenHandler.ACTION, buf);
	}

	private void button(DrawContext context, int bx, int by, int bw, int bh, String label, boolean enabled, int color, int mouseX, int mouseY, Runnable action) {
		boolean hover = enabled && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
		context.fill(bx, by, bx + bw, by + bh, hover ? lighten(color) : color);
		context.drawBorder(bx, by, bw, bh, 0xFF0F0D08);
		String shown = textRenderer.trimToWidth(label, bw - 4);
		context.drawText(textRenderer, shown, bx + (bw - textRenderer.getWidth(shown)) / 2, by + (bh - 8) / 2, 0xFFFFFFFF, true);
		if (enabled) hits.add(new Hit(bx, by, bw, bh, action));
	}

	private static int lighten(int color) {
		int r = Math.min(255, ((color >> 16) & 0xFF) + 30);
		int g = Math.min(255, ((color >> 8) & 0xFF) + 30);
		int b = Math.min(255, (color & 0xFF) + 30);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private void text(DrawContext context, String value, int tx, int ty, int color) {
		context.drawText(textRenderer, value, tx, ty, color, false);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			for (int index = hits.size() - 1; index >= 0; index--) {
				Hit hit = hits.get(index);
				// A button scrolled out of the body can't be pressed through the header.
				if (!hit.contains(mouseX, mouseY) || hit.y() >= y + 33 && (mouseY < y + BODY_TOP - 2 || mouseY > y + BODY_BOTTOM + 2)) continue;
				hit.action().run();
				if (client != null) client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		scroll = Math.max(0, Math.min(Math.max(0, contentHeight - (BODY_BOTTOM - BODY_TOP)), scroll - (int) Math.signum(amount) * 20));
		return true;
	}
}
