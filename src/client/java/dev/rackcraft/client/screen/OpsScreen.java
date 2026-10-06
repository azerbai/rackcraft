package dev.rackcraft.client.screen;

import dev.rackcraft.client.ClientNet;
import dev.rackcraft.compute.Contract;
import dev.rackcraft.compute.OpsScreenHandler;
import dev.rackcraft.compute.OpsScreenHandler.Action;
import dev.rackcraft.compute.OpsSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

/**
 * The Operations Terminal. Five tabs over one snapshot from the server: an overview, the contract board,
 * clusters, AI models and alerts. Buttons are drawn and hit-tested here rather than as widgets, because
 * the rows they belong to come and go with every snapshot.
 */
public final class OpsScreen extends HandledScreen<OpsScreenHandler> {
	private static final int WIDTH = 320;
	private static final int HEIGHT = 226;
	private static final int BODY_TOP = 36;
	private static final int BODY_BOTTOM = 206;
	private static final int TEXT = 0xFFE5ECEB;
	private static final int GOOD = 0xFF62C5A0;
	private static final int WARN = 0xFFE7A45D;
	private static final int BAD = 0xFFE0645A;
	private static final int MUTED = 0xFFAAB9BA;
	private static final int ACCENT = 0xFF5BA7E0;
	private static Tab rememberedTab = Tab.OVERVIEW;

	private enum Tab { OVERVIEW("Overview"), CONTRACTS("Contracts"), CLUSTERS("Clusters"), MODELS("Models"), ALERTS("Alerts");
		final String label;
		Tab(String label) { this.label = label; }
	}

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mouseX, double mouseY) { return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h; }
	}

	private final List<Hit> hits = new ArrayList<>();
	private final Map<Integer, Long> chosenCluster = new HashMap<>();
	private Tab tab = rememberedTab;
	private int scroll;
	private int contentHeight;

	public OpsScreen(OpsScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title);
		backgroundWidth = WIDTH;
		backgroundHeight = HEIGHT;
		playerInventoryTitleY = -10_000;
	}

	@Override
	protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
		context.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF17212A);
		context.fill(x, y, x + WIDTH, y + 15, 0xFF2C414A);
		context.fill(x + 4, y + BODY_TOP - 2, x + WIDTH - 4, y + BODY_BOTTOM + 2, 0xFF202D34);
	}

	@Override
	protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
		context.drawText(textRenderer, title, 8, 4, TEXT, false);
		OpsSnapshot snapshot = handler.snapshot();
		String balance = String.format(Locale.ROOT, "%,d RC", snapshot.credits());
		context.drawText(textRenderer, balance, WIDTH - 8 - textRenderer.getWidth(balance), 4, GOOD, false);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		OpsSnapshot snapshot = handler.snapshot();
		// Tabs size to their labels, so the counts fit.
		int tabX = x + 6;
		for (Tab option : Tab.values()) {
			String label = option.label;
			if (option == Tab.ALERTS && !snapshot.alerts().isEmpty()) label += " (" + snapshot.alerts().size() + ")";
			if (option == Tab.CONTRACTS) {
				long offers = snapshot.contracts().stream().filter(c -> c.state() == Contract.State.OFFERED.ordinal()).count();
				if (offers > 0) label += " (" + offers + ")";
			}
			int tabWidth = Math.min(textRenderer.getWidth(label) + 10, x + WIDTH - 6 - tabX);
			boolean selected = option == tab;
			button(context, tabX, y + 19, tabWidth, 13, label, true, selected ? ACCENT : 0xFF3A525C, mouseX, mouseY, () -> {
				tab = option;
				rememberedTab = option;
				scroll = 0;
			});
			tabX += tabWidth + 2;
		}
		int bodyHits = hits.size();
		context.enableScissor(x + 4, y + BODY_TOP - 2, x + WIDTH - 4, y + BODY_BOTTOM + 2);
		int top = y + BODY_TOP - scroll;
		int end = switch (tab) {
			case OVERVIEW -> overview(context, snapshot, top, mouseX, mouseY);
			case CONTRACTS -> contracts(context, snapshot, top, mouseX, mouseY);
			case CLUSTERS -> clusters(context, snapshot, top, mouseX, mouseY);
			case MODELS -> models(context, snapshot, top, mouseX, mouseY);
			case ALERTS -> alerts(context, snapshot, top);
		};
		context.disableScissor();
		contentHeight = end - top;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - (BODY_BOTTOM - BODY_TOP))));
		// Buttons scrolled out of the body can't be clicked.
		List<Hit> body = hits.subList(bodyHits, hits.size());
		body.removeIf(hit -> hit.y() < y + BODY_TOP - 2 || hit.y() + hit.h() > y + BODY_BOTTOM + 2);
		footer(context, snapshot, mouseX, mouseY);
		drawMouseoverTooltip(context, mouseX, mouseY);
	}

	// ---------------------------------------------------------------- tabs

	private int overview(DrawContext context, OpsSnapshot snapshot, int top, int mouseX, int mouseY) {
		int left = x + 10;
		int row = top + 2;
		int racks = snapshot.clusters().stream().mapToInt(OpsSnapshot.ClusterView::racks).sum();
		long online = snapshot.clusters().stream().filter(OpsSnapshot.ClusterView::online).count();
		long offers = snapshot.contracts().stream().filter(c -> c.state() == Contract.State.OFFERED.ordinal()).count();
		long active = snapshot.contracts().stream().filter(c -> c.state() == Contract.State.ACCEPTED.ordinal()).count();
		String[][] lines = {
				{String.format(Locale.ROOT, "Mining %.1f RC/s", snapshot.miningRate()), "GOOD"},
				{String.format(Locale.ROOT, "Contracts earned %,d RC in the last hour, %,d RC in all", snapshot.earnedHour(),
						snapshot.totalEarned()), "TEXT"},
				{snapshot.clusters().size() + " clusters, " + racks + " racks, " + online + " online", "TEXT"},
				{offers + " offers waiting, " + active + " contracts accepted", offers > 0 ? "WARN" : "TEXT"},
				{"Event: " + snapshot.event().replace('_', ' '), snapshot.event().equals("none") ? "MUTED" : "BAD"},
				{String.format(Locale.ROOT, "Air here: %s (smog %.0f)", smogWord(snapshot.smog()), snapshot.smog()),
						snapshot.smog() >= 70 ? "BAD" : snapshot.smog() >= 35 ? "WARN" : "MUTED"}};
		for (String[] line : lines) {
			int color = switch (line[1]) {
				case "GOOD" -> GOOD;
				case "WARN" -> WARN;
				case "BAD" -> BAD;
				case "MUTED" -> MUTED;
				default -> TEXT;
			};
			text(context, textRenderer.trimToWidth(line[0], WIDTH - 20), left, row, color);
			row += 11;
		}
		for (OpsSnapshot.ModelView model : snapshot.models()) {
			text(context, model.name() + ": quality cap " + model.cap() + "%, " + model.queued() + " queued", left, row, TEXT);
			row += 11;
		}
		row += 4;
		text(context, "Needs attention", left, row, MUTED);
		row += 12;
		int shown = 0;
		for (OpsSnapshot.Alert alert : snapshot.alerts()) {
			if (shown++ >= 6) break;
			row = alertLine(context, alert, left, row);
		}
		if (snapshot.alerts().isEmpty()) {
			text(context, "Nothing. Everything is fine. Suspiciously fine.", left, row, GOOD);
			row += 12;
		}
		return row;
	}

	private int contracts(DrawContext context, OpsSnapshot snapshot, int top, int mouseX, int mouseY) {
		int left = x + 10;
		int right = x + WIDTH - 10;
		int row = top + 2;
		if (snapshot.contracts().isEmpty()) {
			text(context, "No offers yet. Clients post new work every minute or two.", left, row, MUTED);
			return row + 12;
		}
		for (OpsSnapshot.ContractView contract : snapshot.contracts()) {
			Contract.State state = Contract.State.values()[Math.max(0, Math.min(Contract.State.values().length - 1, contract.state()))];
			int kindColor = contract.kind() == Contract.Kind.IMAGE.ordinal() ? 0xFFE0A3F0 : 0xFF9FD3FF;
			context.fill(left - 4, row - 2, right + 4, row + 37, state == Contract.State.OFFERED ? 0xFF243540 : 0xFF1B272E);
			String pay = state == Contract.State.DONE || state == Contract.State.FAILED
					? String.format(Locale.ROOT, "earned %,d RC", contract.earned())
					: String.format(Locale.ROOT, "%,d RC", contract.payout());
			int payWidth = textRenderer.getWidth(pay);
			text(context, textRenderer.trimToWidth(contract.title(), right - left - payWidth - 8), left, row, kindColor);
			text(context, pay, right - payWidth, row, state == Contract.State.FAILED ? BAD : GOOD);
			String when = switch (state) {
				case OFFERED -> "offer lapses in " + time(contract.ticksLeft());
				case ACCEPTED -> contract.ticksLeft() >= 0 ? "due in " + time(contract.ticksLeft()) : "LATE: half pay";
				case STOCK -> "for your stock";
				case DONE -> "done";
				case FAILED -> "failed";
			};
			String details = contract.client() + "  |  " + contract.quantity() + " x, " + contract.quality() + "% quality  |  " + when;
			text(context, textRenderer.trimToWidth(details, right - left), left, row + 11,
					state == Contract.State.ACCEPTED && contract.ticksLeft() < 0 ? BAD : MUTED);
			int buttonsLeft = contractButtons(context, snapshot, contract, state, right, row + 22, mouseX, mouseY);
			if (state == Contract.State.ACCEPTED || state == Contract.State.STOCK) {
				int barWidth = Math.max(20, buttonsLeft - left - 70);
				double fraction = contract.quality() <= 0 ? 0 : Math.min(1, contract.qualityNow() / (double) contract.quality());
				bar(context, left, row + 25, barWidth, fraction, contract.generating() ? GOOD : 0xFF3A525C);
				String progress = contract.delivered() + "/" + contract.quantity() + " done";
				if (contract.generating() && contract.rate() > 0) progress += String.format(Locale.ROOT, ", %.0f AI/s", contract.rate());
				text(context, progress, left + barWidth + 4, row + 24, TEXT);
				if (!contract.status().isEmpty() && !contract.status().equals("Generating")) {
					int warnColor = contract.modelCap() <= contract.quality() ? BAD : WARN;
					text(context, textRenderer.trimToWidth(contract.status(), buttonsLeft - left - 4), left, row + 33, warnColor);
				}
			} else if (state == Contract.State.OFFERED && contract.modelCap() <= contract.quality()) {
				text(context, "Your model tops out at " + contract.modelCap() + "%: train it first", left, row + 24, WARN);
			}
			row += state == Contract.State.ACCEPTED || state == Contract.State.STOCK ? 46 : 40;
		}
		return row;
	}

	/** Draws a contract's buttons right-aligned at {@code right}; returns the left edge they reached. */
	private int contractButtons(DrawContext context, OpsSnapshot snapshot, OpsSnapshot.ContractView contract, Contract.State state,
			int right, int top, int mouseX, int mouseY) {
		List<Object[]> buttons = new ArrayList<>();
		int id = contract.id();
		switch (state) {
			case OFFERED -> {
				buttons.add(new Object[] {"Accept", GOOD, (Runnable) () -> send(Action.ACCEPT, id, 0)});
				buttons.add(new Object[] {"Decline", BAD, (Runnable) () -> send(Action.DECLINE, id, 0)});
			}
			case ACCEPTED, STOCK -> {
				if (contract.generating()) {
					buttons.add(new Object[] {"Stop", WARN, (Runnable) () -> send(Action.STOP, id, contract.cluster())});
				} else {
					long cluster = chosenCluster.getOrDefault(id, contract.cluster());
					buttons.add(new Object[] {"On: " + clusterName(snapshot, cluster), 0xFF3A525C, (Runnable) () ->
							chosenCluster.put(id, nextCluster(snapshot, cluster))});
					buttons.add(new Object[] {"Generate", GOOD, (Runnable) () -> send(Action.GENERATE, id, cluster)});
				}
				if (state == Contract.State.ACCEPTED) {
					buttons.add(new Object[] {"Deliver", ACCENT, (Runnable) () -> send(Action.DELIVER, id, 0)});
					buttons.add(new Object[] {"Abandon", BAD, (Runnable) () -> send(Action.ABANDON, id, 0)});
				} else {
					buttons.add(new Object[] {"Cancel", BAD, (Runnable) () -> send(Action.ABANDON, id, 0)});
				}
			}
			case DONE -> buttons.add(new Object[] {"Make a spare", ACCENT, (Runnable) () -> send(Action.PRINT, id, 0)});
			default -> {}
		}
		int cursor = right;
		for (int index = buttons.size() - 1; index >= 0; index--) {
			Object[] button = buttons.get(index);
			String label = (String) button[0];
			int width = textRenderer.getWidth(label) + 8;
			cursor -= width;
			button(context, cursor, top, width, 12, label, true, (Integer) button[1], mouseX, mouseY, (Runnable) button[2]);
			cursor -= 3;
		}
		return cursor;
	}

	private int clusters(DrawContext context, OpsSnapshot snapshot, int top, int mouseX, int mouseY) {
		int left = x + 10;
		int right = x + WIDTH - 10;
		int row = top + 2;
		if (snapshot.clusters().isEmpty()) {
			text(context, "No server racks in this dimension yet.", left, row, MUTED);
			return row + 12;
		}
		for (int index = 0; index < snapshot.clusters().size(); index++) {
			OpsSnapshot.ClusterView cluster = snapshot.clusters().get(index);
			context.fill(left - 4, row - 2, right + 4, row + 33, 0xFF1B272E);
			text(context, "#" + (index + 1) + "  at " + cluster.x() + ", " + cluster.y() + ", " + cluster.z() + "  |  "
					+ cluster.racks() + (cluster.racks() == 1 ? " rack" : " racks"), left, row, TEXT);
			String online = cluster.online() ? "online" : "OFFLINE: no router";
			text(context, online, right - textRenderer.getWidth(online), row, cluster.online() ? GOOD : BAD);
			text(context, textRenderer.trimToWidth(String.format(Locale.ROOT, "Compute %.0f  |  AI %.0f  |  Mining %.1f RC/s%s",
					cluster.compute(), cluster.aiCompute(), cluster.miningRate(),
					cluster.problems() > 0 ? "  |  " + cluster.problems() + " with problems" : ""), right - left),
					left, row + 11, cluster.problems() > 0 ? WARN : MUTED);
			String policy = cluster.policy() == 0 ? "Policy: Auto" : "Policy: Mining only";
			int width = textRenderer.getWidth(policy) + 8;
			button(context, right - width, row + 20, width, 12, policy, true, cluster.policy() == 0 ? ACCENT : WARN, mouseX, mouseY,
					() -> send(Action.POLICY, 0, cluster.id()));
			text(context, textRenderer.trimToWidth(cluster.activity().isEmpty() ? "idle" : cluster.activity(), right - left - width - 6),
					left, row + 22, TEXT);
			row += 40;
		}
		text(context, "Auto lends racks to autocrafting, contracts and training, and mines with the rest.", left, row, MUTED);
		return row + 12;
	}

	private int models(DrawContext context, OpsSnapshot snapshot, int top, int mouseX, int mouseY) {
		int left = x + 10;
		int right = x + WIDTH - 10;
		int row = top + 2;
		String[] data = {"Art Aggregates", "Text Corpora"};
		for (int index = 0; index < snapshot.models().size(); index++) {
			OpsSnapshot.ModelView model = snapshot.models().get(index);
			context.fill(left - 4, row - 2, right + 4, row + 47, 0xFF1B272E);
			text(context, model.name(), left, row, index == 0 ? 0xFFE0A3F0 : 0xFF9FD3FF);
			String cap = "Quality cap " + model.cap() + "%";
			text(context, cap, right - textRenderer.getWidth(cap), row, model.cap() >= 60 ? GOOD : WARN);
			bar(context, left, row + 12, right - left, model.cap() / 100.0, GOOD);
			text(context, "Trained on " + model.trained() + " " + data[index] + ", " + model.queued() + " queued", left, row + 21, MUTED);
			if (model.queued() > 0) {
				bar(context, left, row + 33, 120, model.progress() / 100.0, ACCENT);
				text(context, model.training() ? String.format(Locale.ROOT, "%.0f AI/s", model.rate()) : "paused", left + 126, row + 32,
						model.training() ? TEXT : WARN);
			}
			int kind = index;
			String toggle = model.training() ? "Pause training" : "Resume training";
			int width = textRenderer.getWidth(toggle) + 8;
			button(context, right - width, row + 31, width, 12, toggle, true, model.training() ? 0xFF3A525C : GOOD, mouseX, mouseY,
					() -> send(Action.TRAINING, kind, 0));
			row += 54;
		}
		button(context, left, row, right - left, 14, "Upload training data from your inventory", true, GOOD, mouseX, mouseY,
				() -> send(Action.UPLOAD, 0, 0));
		row += 20;
		return wrappedText(context, "Idle racks on Auto clusters train models while data is queued; each item takes "
				+ "1,500 AI-compute-seconds. Better models reach higher quality on less compute.", left, row, right - left, MUTED);
	}

	private int alerts(DrawContext context, OpsSnapshot snapshot, int top) {
		int left = x + 10;
		int row = top + 2;
		if (snapshot.alerts().isEmpty()) {
			text(context, "No problems: every rack, drive, pump and cable is happy.", left, row, GOOD);
			return row + 12;
		}
		for (OpsSnapshot.Alert alert : snapshot.alerts()) row = alertLine(context, alert, left, row);
		return row;
	}

	private void footer(DrawContext context, OpsSnapshot snapshot, int mouseX, int mouseY) {
		int row = y + HEIGHT - 14;
		String outbox = snapshot.outbox() == 0 ? "Outbox empty" : "Outbox: " + snapshot.outbox() + " finished, undelivered";
		text(context, outbox, x + 10, row + 2, snapshot.outbox() > 0 ? WARN : MUTED);
		if (snapshot.outbox() > 0) {
			button(context, x + WIDTH - 70, row, 60, 12, "Collect", true, ACCENT, mouseX, mouseY, () -> send(Action.COLLECT, 0, 0));
		}
	}

	// ---------------------------------------------------------------- helpers

	private int alertLine(DrawContext context, OpsSnapshot.Alert alert, int left, int row) {
		int color = alert.severity() >= 2 ? BAD : alert.severity() == 1 ? WARN : MUTED;
		context.fill(left, row + 2, left + 4, row + 6, color);
		return wrappedText(context, alert.text(), left + 8, row, WIDTH - 30, color == MUTED ? MUTED : TEXT);
	}

	private void button(DrawContext context, int bx, int by, int bw, int bh, String label, boolean enabled, int color,
			int mouseX, int mouseY, Runnable action) {
		boolean hover = enabled && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
		context.fill(bx, by, bx + bw, by + bh, hover ? lighten(color) : color);
		context.drawBorder(bx, by, bw, bh, 0xFF0F171C);
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

	private void bar(DrawContext context, int bx, int by, int bw, double fraction, int color) {
		context.fill(bx, by, bx + bw, by + 5, 0xFF0F171C);
		context.fill(bx, by, bx + (int) Math.round(bw * Math.max(0, Math.min(1, fraction))), by + 5, color);
	}

	private void text(DrawContext context, String value, int tx, int ty, int color) {
		context.drawText(textRenderer, value, tx, ty, color, false);
	}

	private int wrappedText(DrawContext context, String value, int tx, int ty, int width, int color) {
		for (OrderedText line : textRenderer.wrapLines(Text.literal(value), width)) {
			context.drawText(textRenderer, line, tx, ty, color, false);
			ty += 10;
		}
		return ty + 2;
	}

	private static String time(long ticks) {
		long seconds = Math.max(0, ticks / 20);
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	private static String smogWord(float smog) {
		return smog >= 70 ? "choking" : smog >= 35 ? "smoggy" : smog >= 15 ? "hazy" : "clear";
	}

	private static String clusterName(OpsSnapshot snapshot, long id) {
		if (id == Contract.ANY_CLUSTER) return "any";
		for (int index = 0; index < snapshot.clusters().size(); index++) {
			if (snapshot.clusters().get(index).id() == id) return "#" + (index + 1);
		}
		return "gone";
	}

	private static long nextCluster(OpsSnapshot snapshot, long current) {
		List<Long> options = new ArrayList<>();
		options.add(Contract.ANY_CLUSTER);
		snapshot.clusters().forEach(cluster -> options.add(cluster.id()));
		int index = options.indexOf(current);
		return options.get((index + 1) % options.size());
	}

	private void send(Action action, int id, long cluster) {
		ClientNet.opsAction(handler.syncId, action, id, cluster);
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
