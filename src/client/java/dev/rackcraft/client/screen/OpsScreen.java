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
						snapshot.smog() >= 55 ? "BAD" : snapshot.smog() > 30 ? "WARN" : "MUTED"}};
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
		for (Contract.Kind kind : Contract.Kind.values()) {
			OpsSnapshot.ModelView best = null;
			int queued = 0;
			for (OpsSnapshot.ModelView model : snapshot.models()) {
				if (model.kind() != kind.ordinal()) continue;
				queued += model.queued();
				if (best == null || model.cap() > best.cap()) best = model;
			}
			if (best == null) continue;
			String line = (kind == Contract.Kind.IMAGE ? "Images: " : "Writing: ") + "best is " + best.name() + " at " + best.cap() + "%"
					+ (queued > 0 ? ", " + queued + " queued for training" : "");
			text(context, textRenderer.trimToWidth(line, WIDTH - 20), left, row, kindColor(kind.ordinal()));
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
		int width = right - left;
		int row = top + 2;
		if (snapshot.contracts().isEmpty()) {
			text(context, "No offers yet. Clients post new work every minute or two.", left, row, MUTED);
			return row + 12;
		}
		for (OpsSnapshot.ContractView contract : snapshot.contracts()) {
			Contract.State state = Contract.State.values()[Math.max(0, Math.min(Contract.State.values().length - 1, contract.state()))];
			boolean working = state == Contract.State.ACCEPTED || state == Contract.State.STOCK;
			String pay = state == Contract.State.DONE || state == Contract.State.FAILED
					? String.format(Locale.ROOT, "earned %,d RC", contract.earned())
					: String.format(Locale.ROOT, "%,d RC", contract.payout());
			int payWidth = textRenderer.getWidth(pay);
			// Long prompts wrap instead of pushing the pay, timer and buttons off the card.
			List<OrderedText> title = textRenderer.wrapLines(Text.literal(contract.title()), width - payWidth - 8);
			String when = switch (state) {
				case OFFERED -> "offer lapses in " + time(contract.ticksLeft());
				case ACCEPTED -> contract.ticksLeft() >= 0 ? "due in " + time(contract.ticksLeft()) : "LATE: half pay";
				case STOCK -> "for your stock";
				case DONE -> "done";
				case FAILED -> "failed";
			};
			int whenColor = state == Contract.State.ACCEPTED && contract.ticksLeft() < 0 ? BAD
					: state == Contract.State.ACCEPTED && contract.ticksLeft() < 20 * 120 ? WARN : MUTED;
			String model = (contract.autoModel() ? "Auto: " : "") + contract.model() + " (cap " + contract.modelCap() + "%)";
			String needs = contract.quantity() + " x at " + contract.quality() + "% quality  |  " + model;
			String warning = null;
			if (working && !contract.status().isEmpty() && !contract.status().equals("Generating")) warning = contract.status();
			else if (state == Contract.State.OFFERED && contract.modelCap() <= contract.quality()) {
				warning = "No model reaches " + contract.quality() + "% yet: train one first";
			}
			List<OrderedText> warningLines = warning == null ? List.of() : textRenderer.wrapLines(Text.literal(warning), width);
			List<Button> buttons = contractButtons(snapshot, contract, state);
			int height = title.size() * 10 + 11 + 11 + (working ? 11 : 0) + warningLines.size() * 10
					+ (buttons.isEmpty() ? 0 : buttonRows(buttons, width) * 15) + 2;

			context.fill(left - 4, row - 2, right + 4, row + height, state == Contract.State.OFFERED ? 0xFF243540 : 0xFF1B272E);
			int line = row;
			for (int index = 0; index < title.size(); index++) {
				context.drawText(textRenderer, title.get(index), left, line, kindColor(contract.kind()), false);
				line += 10;
			}
			text(context, pay, right - payWidth, row, state == Contract.State.FAILED ? BAD : GOOD);
			int whenWidth = textRenderer.getWidth(when);
			text(context, textRenderer.trimToWidth(contract.client(), width - whenWidth - 8), left, line + 1, MUTED);
			text(context, when, right - whenWidth, line + 1, whenColor);
			line += 11;
			text(context, textRenderer.trimToWidth(needs, width), left, line + 1, TEXT);
			line += 11;
			if (working) {
				double fraction = contract.quality() <= 0 ? 0 : Math.min(1, contract.qualityNow() / (double) contract.quality());
				String progress = contract.delivered() + "/" + contract.quantity() + " done";
				if (contract.generating() && contract.rate() > 0) progress += String.format(Locale.ROOT, ", %.0f AI/s", contract.rate());
				int progressWidth = textRenderer.getWidth(progress);
				bar(context, left, line + 3, width - progressWidth - 6, fraction, contract.generating() ? GOOD : 0xFF3A525C);
				text(context, progress, right - progressWidth, line + 1, TEXT);
				line += 11;
			}
			for (OrderedText warningLine : warningLines) {
				context.drawText(textRenderer, warningLine, left, line + 1, contract.modelCap() <= contract.quality() ? BAD : WARN, false);
				line += 10;
			}
			flowButtons(context, buttons, left, right, line + 2, mouseX, mouseY);
			row += height + 6;
		}
		return row;
	}

	private record Button(String label, int color, Runnable action) {}

	private List<Button> contractButtons(OpsSnapshot snapshot, OpsSnapshot.ContractView contract, Contract.State state) {
		List<Button> buttons = new ArrayList<>();
		int id = contract.id();
		switch (state) {
			case OFFERED -> {
				buttons.add(new Button("Accept", GOOD, () -> send(Action.ACCEPT, id, 0)));
				buttons.add(new Button("Decline", BAD, () -> send(Action.DECLINE, id, 0)));
				buttons.add(new Button("Model: " + modelChoice(contract), 0xFF3A525C, () -> send(Action.MODEL, id, 0)));
			}
			case ACCEPTED, STOCK -> {
				if (contract.generating()) {
					buttons.add(new Button("Stop", WARN, () -> send(Action.STOP, id, contract.cluster())));
				} else {
					long cluster = chosenCluster.getOrDefault(id, contract.cluster());
					buttons.add(new Button("Generate", GOOD, () -> send(Action.GENERATE, id, cluster)));
					buttons.add(new Button("On: " + clusterName(snapshot, cluster), 0xFF3A525C, () ->
							chosenCluster.put(id, nextCluster(snapshot, cluster))));
				}
				buttons.add(new Button("Model: " + modelChoice(contract), 0xFF3A525C, () -> send(Action.MODEL, id, 0)));
				if (state == Contract.State.ACCEPTED) {
					buttons.add(new Button("Deliver", ACCENT, () -> send(Action.DELIVER, id, 0)));
					buttons.add(new Button("Abandon", BAD, () -> send(Action.ABANDON, id, 0)));
				} else {
					buttons.add(new Button("Cancel", BAD, () -> send(Action.ABANDON, id, 0)));
				}
			}
			case DONE -> buttons.add(new Button("Make a spare", ACCENT, () -> send(Action.PRINT, id, 0)));
			default -> {}
		}
		return buttons;
	}

	/** "Auto" or the chosen model's tier, short enough for a button. */
	private static String modelChoice(OpsSnapshot.ContractView contract) {
		if (contract.autoModel()) return "Auto";
		String name = contract.model();
		for (String tier : new String[] {"Flash-Lite", "Flash", "Pro"}) if (name.endsWith(" " + tier)) return tier;
		int space = name.lastIndexOf(' ');
		return space > 0 ? name.substring(0, space) : name;
	}

	private int buttonWidth(Button button) {
		return textRenderer.getWidth(button.label()) + 10;
	}

	/** How many rows these buttons need when laid out left to right in {@code width}. */
	private int buttonRows(List<Button> buttons, int width) {
		int rows = 1;
		int cursor = 0;
		for (Button button : buttons) {
			int buttonWidth = buttonWidth(button);
			if (cursor > 0 && cursor + buttonWidth > width) {
				rows++;
				cursor = 0;
			}
			cursor += buttonWidth + 3;
		}
		return rows;
	}

	/** Lays buttons out left to right, wrapping onto a new row when one would run past {@code right}. */
	private int flowButtons(DrawContext context, List<Button> buttons, int left, int right, int top, int mouseX, int mouseY) {
		int cursor = left;
		int row = top;
		for (Button button : buttons) {
			int buttonWidth = buttonWidth(button);
			if (cursor > left && cursor + buttonWidth > right) {
				cursor = left;
				row += 15;
			}
			button(context, cursor, row, buttonWidth, 12, button.label(), true, button.color(), mouseX, mouseY, button.action());
			cursor += buttonWidth + 3;
		}
		return row + 15;
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
		int width = right - left;
		int row = top + 2;
		row = wrappedText(context, "Each model trains on its own queue. Lite models learn from a handful of examples but top out "
				+ "early; Pro models need far more data and compute, then beat everything. Contracts on Auto use the fastest "
				+ "model that can reach their quality.", left, row, width, MUTED) + 4;
		for (Contract.Kind kind : Contract.Kind.values()) {
			String data = kind == Contract.Kind.IMAGE ? "Art Aggregates" : "Text Corpora";
			text(context, (kind == Contract.Kind.IMAGE ? "Image models" : "Language models") + ": trained on " + data, left, row,
					kindColor(kind.ordinal()));
			row += 13;
			for (int index = 0; index < snapshot.models().size(); index++) {
				OpsSnapshot.ModelView model = snapshot.models().get(index);
				if (model.kind() != kind.ordinal()) continue;
				row = modelCard(context, model, index, data, left, right, row, mouseX, mouseY);
			}
			row += 4;
		}
		return row;
	}

	private int modelCard(DrawContext context, OpsSnapshot.ModelView model, int index, String data, int left, int right, int row,
			int mouseX, int mouseY) {
		int width = right - left;
		List<OrderedText> blurb = textRenderer.wrapLines(Text.literal(model.blurb()), width);
		boolean learning = model.queued() > 0;
		int height = 10 + 9 + 11 + blurb.size() * 10 + 11 + 15;
		context.fill(left - 4, row - 2, right + 4, row + height, 0xFF1B272E);
		int tierColor = switch (model.tier()) {
			case "Pro" -> 0xFFF0C674;
			case "Flash" -> ACCENT;
			default -> MUTED;
		};
		String tier = " " + model.tier();
		text(context, model.name(), left, row, kindColor(model.kind()));
		String cap = "cap " + model.cap() + "% of " + model.maxCap() + "%";
		text(context, cap, right - textRenderer.getWidth(cap), row, model.cap() >= 60 ? GOOD : WARN);
		int nameWidth = textRenderer.getWidth(model.name());
		if (left + nameWidth + textRenderer.getWidth(tier) < right - textRenderer.getWidth(cap) - 6) {
			text(context, tier, left + nameWidth, row, tierColor);
		}
		row += 10;
		// The bar is the model's current cap; the tick marks the best it can ever reach.
		bar(context, left, row + 1, width, model.cap() / 100.0, GOOD);
		int ceiling = left + (int) Math.round(width * model.maxCap() / 100.0);
		context.fill(ceiling - 1, row - 1, ceiling + 1, row + 8, 0xFFE5ECEB);
		row += 9;
		text(context, textRenderer.trimToWidth(String.format(Locale.ROOT, "Speed %.1fx  |  %,d AI-s per item trained  |  %d trained",
				model.speed(), model.dataWork(), model.trained()), width), left, row + 1, TEXT);
		row += 11;
		for (OrderedText line : blurb) {
			context.drawText(textRenderer, line, left, row, MUTED, false);
			row += 10;
		}
		if (learning) {
			String rate = model.training() ? String.format(Locale.ROOT, "%d queued, %.0f AI/s", model.queued(), model.rate())
					: model.queued() + " queued, paused";
			int rateWidth = textRenderer.getWidth(rate);
			bar(context, left, row + 3, width - rateWidth - 6, model.progress() / 100.0, ACCENT);
			text(context, rate, right - rateWidth, row + 1, model.training() ? TEXT : WARN);
			row += 11;
		} else {
			text(context, "Nothing queued", left, row + 1, MUTED);
			row += 11;
		}
		List<Button> buttons = new ArrayList<>();
		buttons.add(new Button("Upload " + data, GOOD, () -> send(Action.UPLOAD, index, 0)));
		buttons.add(new Button(model.training() ? "Pause training" : "Resume training", model.training() ? 0xFF3A525C : GOOD,
				() -> send(Action.TRAINING, index, 0)));
		flowButtons(context, buttons, left, right, row + 1, mouseX, mouseY);
		return row + 15 + 6;
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

	private static int kindColor(int kind) {
		return kind == Contract.Kind.IMAGE.ordinal() ? 0xFFE0A3F0 : 0xFF9FD3FF;
	}

	private static String time(long ticks) {
		long seconds = Math.max(0, ticks / 20);
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	private static String smogWord(float smog) {
		return smog >= 88 ? "toxic" : smog >= 70 ? "choking" : smog >= 45 ? "smoggy" : smog > 30 ? "dizzying" : smog >= 15 ? "hazy" : "clear";
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
