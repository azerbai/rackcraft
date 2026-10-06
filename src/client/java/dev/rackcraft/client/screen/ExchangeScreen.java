package dev.rackcraft.client.screen;

import dev.rackcraft.ExchangeOffers;
import dev.rackcraft.ExchangeOffers.Category;
import dev.rackcraft.ExchangeOffers.Offer;
import dev.rackcraft.client.ClientNet;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Spend mined RackCoin on resources and parts. Shift-click buys ten at once. */
public final class ExchangeScreen extends RackcraftHandledScreen {
	private static final int CARD_WIDTH = 130;
	private static final int CARD_HEIGHT = 22;
	private static final int GRID_TOP = 62;
	private static final int BULK = 10;
	private static Category rememberedCategory = Category.RARE;

	private Category category = rememberedCategory;
	private final List<ButtonWidget> tabs = new ArrayList<>();

	public ExchangeScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 284, 238);
	}

	@Override
	protected void init() {
		super.init();
		playerInventoryTitleY = -10_000;
		tabs.clear();
		int index = 0;
		for (Category tab : Category.values()) {
			ButtonWidget button = ButtonWidget.builder(Text.translatable("exchange.rackcraft.category." + tab.name().toLowerCase(Locale.ROOT)),
					pressed -> select(tab)).dimensions(x + 8 + index * 90, y + 36, 86, 18).build();
			tabs.add(addDrawableChild(button));
			index++;
		}
		select(category);
	}

	private void select(Category selected) {
		category = selected;
		rememberedCategory = selected;
		for (int index = 0; index < tabs.size(); index++) tabs.get(index).active = Category.values()[index] != selected;
	}

	private List<Offer> visibleOffers() {
		return ExchangeOffers.all().values().stream().filter(offer -> offer.category() == category).toList();
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		int balance = stat(Stat.BALANCE);
		int rate = stat(Stat.MINING_RATE);
		context.drawText(textRenderer, Text.translatable("exchange.rackcraft.balance", String.format(Locale.ROOT, "%,d", balance)),
				8, 24, GOOD, false);
		Text mining = rate > 0
				? Text.translatable("exchange.rackcraft.mining", coins(rate), stat(Stat.MINING_RACKS), stat(Stat.TOTAL_RACKS))
				: Text.translatable("exchange.rackcraft.not_mining");
		context.drawText(textRenderer, mining, backgroundWidth - 8 - textRenderer.getWidth(mining), 24, rate > 0 ? TEXT : WARN, false);

		List<Offer> offers = visibleOffers();
		for (int index = 0; index < offers.size(); index++) {
			Offer offer = offers.get(index);
			int cardX = 8 + (index % 2) * (CARD_WIDTH + 8);
			int cardY = GRID_TOP + (index / 2) * (CARD_HEIGHT + 2);
			boolean affordable = balance >= offer.price();
			context.fill(cardX, cardY, cardX + CARD_WIDTH, cardY + CARD_HEIGHT, affordable ? 0xFF263A42 : 0xFF1B262C);
			context.drawItem(offer.stack(), cardX + 3, cardY + 3);
			context.drawItemInSlot(textRenderer, offer.stack(), cardX + 3, cardY + 3);
			String name = textRenderer.trimToWidth(offer.stack().getName().getString(), CARD_WIDTH - 66);
			context.drawText(textRenderer, name, cardX + 23, cardY + 7, affordable ? TEXT : MUTED, false);
			String price = String.format(Locale.ROOT, "%,d RC", offer.price());
			context.drawText(textRenderer, price, cardX + CARD_WIDTH - 4 - textRenderer.getWidth(price), cardY + 7,
					affordable ? GOOD : BAD, false);
		}
		wrapped(context, Text.translatable("exchange.rackcraft.footer"), 8, backgroundHeight - 26, backgroundWidth - 16, MUTED);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		super.render(context, mouseX, mouseY, delta);
		Offer hovered = offerAt(mouseX, mouseY);
		if (hovered == null) return;
		List<Text> tooltip = new ArrayList<>(Screen.getTooltipFromItem(client, hovered.stack()));
		tooltip.add(Text.translatable("exchange.rackcraft.price", String.format(Locale.ROOT, "%,d", hovered.price()))
				.formatted(Formatting.GREEN));
		int balance = stat(Stat.BALANCE);
		int rate = stat(Stat.MINING_RATE);
		if (balance < hovered.price() && rate > 0) {
			long seconds = (long) Math.ceil((hovered.price() - balance) / (rate / 100.0));
			tooltip.add(Text.translatable("exchange.rackcraft.eta", duration(seconds)).formatted(Formatting.GRAY));
		}
		tooltip.add(Text.translatable("exchange.rackcraft.bulk", BULK).formatted(Formatting.DARK_GRAY));
		context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		Offer offer = offerAt(mouseX, mouseY);
		if (offer != null && button == 0) {
			ClientNet.buyItem(handler.pos(), offer.id(), hasShiftDown() ? BULK : 1);
			client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private Offer offerAt(double mouseX, double mouseY) {
		List<Offer> offers = visibleOffers();
		for (int index = 0; index < offers.size(); index++) {
			int cardX = x + 8 + (index % 2) * (CARD_WIDTH + 8);
			int cardY = y + GRID_TOP + (index / 2) * (CARD_HEIGHT + 2);
			if (mouseX >= cardX && mouseX < cardX + CARD_WIDTH && mouseY >= cardY && mouseY < cardY + CARD_HEIGHT) {
				return offers.get(index);
			}
		}
		return null;
	}

	private static String duration(long seconds) {
		if (seconds < 60) return seconds + " s";
		if (seconds < 3600) return seconds / 60 + " min " + seconds % 60 + " s";
		return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
	}
}
