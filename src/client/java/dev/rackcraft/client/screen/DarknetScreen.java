package dev.rackcraft.client.screen;

import dev.rackcraft.client.ClientNet;
import dev.rackcraft.darknet.DarknetScreenHandler;
import dev.rackcraft.darknet.DarknetScreenHandler.Action;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * The Darknet Terminal: open auctions with their item, current bid, leader and countdown, buttons to bid, the dead
 * drop where won lots arrive, recent results, and the (steep) price of another listing slot.
 */
public final class DarknetScreen extends HandledScreen<DarknetScreenHandler> {
	private static final int WIDTH = 320;
	private static final int HEIGHT = 230;
	private static final int BODY_TOP = 20;
	private static final int BODY_BOTTOM = 224;
	private static final int TEXT = 0xFFD8F5DC;
	private static final int GOOD = 0xFF55E07A;
	private static final int WARN = 0xFFE7C35D;
	private static final int BAD = 0xFFE0645A;
	private static final int MUTED = 0xFF7FA58A;
	private static final int BUTTON = 0xFF1F4A2E;

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mouseX, double mouseY) { return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h; }
	}

	private record Icon(int x, int y, ItemStack stack) {}

	private final List<Hit> hits = new ArrayList<>();
	private final List<Icon> icons = new ArrayList<>();
	private int scroll;
	private int contentHeight;

	public DarknetScreen(DarknetScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		backgroundWidth = WIDTH;
		backgroundHeight = HEIGHT;
		playerInventoryTitleY = -10_000;
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		context.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF050A07);
		context.fill(x, y, x + WIDTH, y + 15, 0xFF0E2416);
		context.fill(x + 4, y + BODY_TOP - 2, x + WIDTH - 4, y + BODY_BOTTOM + 2, 0xFF08130C);
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		context.drawText(textRenderer, "> darknet_terminal --auctions", 8, 4, GOOD, false);
		String balance = String.format(Locale.ROOT, "%,d RC", handler.snapshot().credits());
		context.drawText(textRenderer, balance, WIDTH - 8 - textRenderer.getWidth(balance), 4, GOOD, false);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		icons.clear();
		DarknetScreenHandler.Snapshot snapshot = handler.snapshot();
		context.enableScissor(x + 4, y + BODY_TOP - 2, x + WIDTH - 4, y + BODY_BOTTOM + 2);
		int top = y + BODY_TOP + 2 - scroll;
		int row = top;
		int left = x + 10;
		int right = x + WIDTH - 10;
		if (snapshot.listings().isEmpty()) {
			text(context, "Connecting through seven proxies...", left, row, MUTED);
			row += 14;
		}
		for (DarknetScreenHandler.ListingView listing : snapshot.listings()) row = listingCard(context, listing, left, right, row, mouseX, mouseY);
		row = deadDrop(context, snapshot, left, right, row + 2, mouseX, mouseY);
		context.disableScissor();
		contentHeight = row - top;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - (BODY_BOTTOM - BODY_TOP))));
		hits.removeIf(hit -> hit.y() < y + BODY_TOP - 2 || hit.y() + hit.h() > y + BODY_BOTTOM + 2);
		for (Icon icon : icons) {
			if (icon.y() < y + BODY_TOP - 2 || icon.y() + 16 > y + BODY_BOTTOM + 2) continue;
			if (mouseX >= icon.x() && mouseX < icon.x() + 16 && mouseY >= icon.y() && mouseY < icon.y() + 16) {
				context.drawItemTooltip(textRenderer, icon.stack(), mouseX, mouseY);
			}
		}
		drawMouseoverTooltip(context, mouseX, mouseY);
	}

	private int listingCard(DrawContext context, DarknetScreenHandler.ListingView listing, int left, int right, int row,
			int mouseX, int mouseY) {
		int height = 42;
		context.fill(left - 4, row - 2, right + 4, row + height - 4, listing.youLead() ? 0xFF0F2A18 : 0xFF0B1A10);
		context.drawItem(listing.stack(), left, row + 2);
		context.drawItemInSlot(textRenderer, listing.stack(), left, row + 2);
		icons.add(new Icon(left, row + 2, listing.stack()));
		int textLeft = left + 22;
		String clock = time(listing.ticksLeft()) + (listing.playerBid() ? "" : " (no bids yet)");
		int clockColor = listing.ticksLeft() < 20 * 15 ? BAD : listing.playerBid() ? WARN : MUTED;
		int clockWidth = textRenderer.getWidth(clock);
		text(context, textRenderer.trimToWidth(listing.stack().getName().getString(), right - textLeft - clockWidth - 6), textLeft, row,
				listing.stack().getRarity().formatting.getColorValue() != null ? 0xFF000000 | listing.stack().getRarity().formatting.getColorValue() : TEXT);
		text(context, clock, right - clockWidth, row, clockColor);
		String status;
		int statusColor;
		if (listing.bid() <= 0) {
			status = String.format(Locale.ROOT, "Opening bid %,d RC", listing.minimumBid());
			statusColor = TEXT;
		} else if (listing.youLead()) {
			status = String.format(Locale.ROOT, "You lead at %,d RC (%d bids)", listing.bid(), listing.bids());
			statusColor = GOOD;
		} else {
			status = String.format(Locale.ROOT, "%,d RC by %s (%d bids)", listing.bid(), listing.bidder(), listing.bids());
			statusColor = listing.playerBid() ? BAD : TEXT;
		}
		text(context, textRenderer.trimToWidth(status, right - textLeft), textLeft, row + 10, statusColor);
		long minimum = listing.minimumBid();
		long base = Math.max(listing.bid(), minimum);
		List<long[]> options = new ArrayList<>();
		options.add(new long[] {minimum});
		long ten = nice(Math.round(base * 1.1));
		long half = nice(Math.round(base * 1.5));
		if (ten > minimum) options.add(new long[] {ten});
		if (half > ten) options.add(new long[] {half});
		int cursor = right;
		int buttonY = row + 21;
		for (int index = options.size() - 1; index >= 0; index--) {
			long amount = options.get(index)[0];
			String label = index == 0 ? "Bid " + compact(amount) : "Bid " + compact(amount) + (index == 1 && ten > minimum ? " +10%" : " +50%");
			int width = textRenderer.getWidth(label) + 8;
			cursor -= width;
			boolean affordable = handler.snapshot().credits() >= amount - (listing.youLead() ? listing.bid() : 0);
			button(context, cursor, buttonY, width, 12, label, affordable ? BUTTON : 0xFF2A2A2A, mouseX, mouseY,
					() -> ClientNet.darknetAction(handler.syncId, Action.BID, listing.id(), amount));
			cursor -= 3;
		}
		text(context, textRenderer.trimToWidth("sold by " + listing.seller(), cursor - textLeft - 4), textLeft, buttonY + 2, MUTED);
		return row + height;
	}

	private int deadDrop(DrawContext context, DarknetScreenHandler.Snapshot snapshot, int left, int right, int row, int mouseX, int mouseY) {
		text(context, "Dead drop", left, row, MUTED);
		long arrived = snapshot.parcels().stream().filter(parcel -> parcel.ticksLeft() <= 0).count();
		if (arrived > 0) {
			String label = "Collect " + arrived;
			int width = textRenderer.getWidth(label) + 10;
			button(context, right - width, row - 2, width, 12, label, BUTTON, mouseX, mouseY,
					() -> ClientNet.darknetAction(handler.syncId, Action.COLLECT, 0, 0));
		}
		row += 13;
		if (snapshot.parcels().isEmpty()) {
			text(context, "Empty. Win an auction and it ships here in two to six minutes.", left, row, MUTED);
			row += 11;
		}
		for (DarknetScreenHandler.ParcelView parcel : snapshot.parcels()) {
			context.drawItem(parcel.stack(), left, row - 4);
			icons.add(new Icon(left, row - 4, parcel.stack()));
			String line = parcel.ticksLeft() <= 0 ? parcel.stack().getName().getString() + ": arrived"
					: parcel.stack().getName().getString() + ": arrives in " + time(parcel.ticksLeft()) + " by " + parcel.courier();
			text(context, textRenderer.trimToWidth(line, right - left - 22), left + 22, row, parcel.ticksLeft() <= 0 ? GOOD : TEXT);
			row += 18;
		}
		row += 4;
		if (snapshot.nextSlotPrice() > 0) {
			String label = String.format(Locale.ROOT, "More listings (%d -> %d): %,d RC", snapshot.slots(), snapshot.slots() + 1,
					snapshot.nextSlotPrice());
			int width = textRenderer.getWidth(label) + 10;
			button(context, left, row, width, 12, label, snapshot.credits() >= snapshot.nextSlotPrice() ? BUTTON : 0xFF2A2A2A,
					mouseX, mouseY, () -> ClientNet.darknetAction(handler.syncId, Action.BUY_SLOT, 0, 0));
			row += 17;
		} else {
			text(context, "Showing the maximum of eight auctions.", left, row, MUTED);
			row += 12;
		}
		if (!snapshot.history().isEmpty()) {
			text(context, "Recently closed", left, row, MUTED);
			row += 11;
			for (String line : snapshot.history()) {
				text(context, textRenderer.trimToWidth(line, right - left), left, row, line.contains(" won ") ? GOOD : MUTED);
				row += 10;
			}
		}
		row += 2;
		text(context, textRenderer.trimToWidth("Bids are paid when placed and refunded if you're outbid. One minute after", right - left),
				left, row, MUTED);
		text(context, textRenderer.trimToWidth("the first bid, the highest bidder wins. Rivals bid back.", right - left), left, row + 10, MUTED);
		return row + 22;
	}

	/** Matches the server's rounding: three significant figures past 100. */
	private static long nice(long value) {
		if (value < 100) return Math.max(1, value);
		long step = (long) Math.pow(10, Math.floor(Math.log10(value)) - 2);
		return Math.max(step, Math.round(value / (double) step) * step);
	}

	private static String compact(long value) {
		if (value >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", value / 1e6);
		if (value >= 10_000) return String.format(Locale.ROOT, "%.1fk", value / 1e3);
		return String.format(Locale.ROOT, "%,d", value);
	}

	private static String time(long ticks) {
		long seconds = Math.max(0, ticks / 20);
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	private void text(DrawContext context, String value, int tx, int ty, int color) {
		context.drawText(textRenderer, value, tx, ty, color, false);
	}

	private void button(DrawContext context, int bx, int by, int bw, int bh, String label, int color, int mouseX, int mouseY,
			Runnable action) {
		boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
		context.fill(bx, by, bx + bw, by + bh, hover ? 0xFF2F6E45 : color);
		context.drawBorder(bx, by, bw, bh, 0xFF55E07A);
		String shown = textRenderer.trimToWidth(label, bw - 4);
		context.drawText(textRenderer, shown, bx + (bw - textRenderer.getWidth(shown)) / 2, by + (bh - 8) / 2, 0xFFFFFFFF, false);
		hits.add(new Hit(bx, by, bw, bh, action));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			for (int index = hits.size() - 1; index >= 0; index--) {
				Hit hit = hits.get(index);
				if (!hit.contains(mouseX, mouseY)) continue;
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
