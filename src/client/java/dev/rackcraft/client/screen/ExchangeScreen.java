package dev.rackcraft.client.screen;

import dev.rackcraft.ExchangeCatalog;
import dev.rackcraft.ExchangeOffers;
import dev.rackcraft.ExchangeOffers.Category;
import dev.rackcraft.ExchangeOffers.Offer;
import dev.rackcraft.RackcraftNetworking;
import dev.rackcraft.client.ClientNet;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/**
 * Spend mined RackCoin. Three curated tabs of bundles, plus "All Items": every survival item, searchable.
 * Click buys one, Shift-click ten, Ctrl-click a full stack (catalog only).
 */
public final class ExchangeScreen extends RackcraftHandledScreen {
	private static final int CARD_WIDTH = 130;
	private static final int CARD_HEIGHT = 22;
	private static final int GRID_TOP = 62;
	private static final int CATALOG_TOP = 78;
	private static final int COLUMNS = 14;
	private static final int ROWS = 6;
	private static final int BULK = 10;
	/** null means the "All Items" tab. */
	private static Category rememberedTab = null;
	private static boolean rememberedSet;

	private Category tab;
	private final List<ButtonWidget> tabs = new ArrayList<>();
	private TextFieldWidget search;
	private List<ExchangeCatalog.Entry> filtered = List.of();
	private int scrollRow;

	public ExchangeScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 284, 238);
		tab = rememberedSet ? rememberedTab : Category.RARE;
	}

	@Override
	protected void init() {
		super.init();
		playerInventoryTitleY = -10_000;
		tabs.clear();
		Category[] categories = Category.values();
		for (int index = 0; index <= categories.length; index++) {
			Category category = index < categories.length ? categories[index] : null;
			Text label = category == null ? Text.translatable("exchange.rackcraft.category.all")
					: Text.translatable("exchange.rackcraft.category." + category.name().toLowerCase(Locale.ROOT));
			tabs.add(addDrawableChild(ButtonWidget.builder(label, pressed -> select(category))
					.dimensions(x + 8 + index * 67, y + 36, 64, 18).build()));
		}
		search = addDrawableChild(new TextFieldWidget(textRenderer, x + 9, y + 60, 150, 14,
				Text.translatable("exchange.rackcraft.search")));
		search.setPlaceholder(Text.translatable("exchange.rackcraft.search").formatted(Formatting.DARK_GRAY));
		search.setChangedListener(text -> refilter());
		refilter();
		select(tab);
	}

	private void select(Category selected) {
		tab = selected;
		rememberedTab = selected;
		rememberedSet = true;
		Category[] categories = Category.values();
		for (int index = 0; index < tabs.size(); index++) {
			tabs.get(index).active = (index < categories.length ? categories[index] : null) != selected;
		}
		search.visible = selected == null;
		if (selected == null) setFocused(search);
	}

	private void refilter() {
		String query = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
		filtered = handler.catalog().stream().filter(entry -> query.isEmpty()
				|| entry.item().getName().getString().toLowerCase(Locale.ROOT).contains(query)
				|| Registries.ITEM.getId(entry.item()).getPath().contains(query.replace(' ', '_'))).toList();
		scrollRow = 0;
	}

	private List<Offer> curatedOffers() {
		return ExchangeOffers.all().values().stream().filter(offer -> offer.category() == tab).toList();
	}

	private int maxScroll() {
		return Math.max(0, (filtered.size() + COLUMNS - 1) / COLUMNS - ROWS);
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		long balance = handler.balance();
		int rate = stat(Stat.MINING_RATE);
		Text balanceText = Text.translatable("exchange.rackcraft.balance", String.format(Locale.ROOT, "%,d", balance));
		context.drawText(textRenderer, balanceText, 8, 24, GOOD, false);
		// The mining rate takes whatever room the balance leaves, so a big balance can't run into it.
		String mining = rate > 0
				? Text.translatable("exchange.rackcraft.mining", compactCoins(rate / 100.0), stat(Stat.MINING_RACKS), stat(Stat.TOTAL_RACKS)).getString()
				: Text.translatable("exchange.rackcraft.not_mining").getString();
		int room = backgroundWidth - 16 - textRenderer.getWidth(balanceText) - 10;
		if (textRenderer.getWidth(mining) > room) mining = textRenderer.trimToWidth(mining, room - textRenderer.getWidth("...")).trim() + "...";
		context.drawText(textRenderer, mining, backgroundWidth - 8 - textRenderer.getWidth(mining), 24, rate > 0 ? TEXT : WARN, false);

		if (tab == null) drawCatalog(context, balance);
		else drawCurated(context, balance);
		wrapped(context, Text.translatable(tab == null ? "exchange.rackcraft.footer_all" : "exchange.rackcraft.footer"),
				8, backgroundHeight - 26, backgroundWidth - 16, MUTED);
	}

	/** 155150.3 as "155k"; small rates keep a decimal. */
	private static String compactCoins(double perSecond) {
		if (perSecond >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", perSecond / 1e6);
		if (perSecond >= 10_000) return String.format(Locale.ROOT, "%.0fk", perSecond / 1e3);
		return String.format(Locale.ROOT, "%,.1f", perSecond);
	}

	private void drawCurated(DrawContext context, long balance) {
		List<Offer> offers = curatedOffers();
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
	}

	private void drawCatalog(DrawContext context, long balance) {
		String count = String.format(Locale.ROOT, "%,d items", filtered.size());
		context.drawText(textRenderer, count, backgroundWidth - 8 - textRenderer.getWidth(count), 63, MUTED, false);
		for (int slot = 0; slot < COLUMNS * ROWS; slot++) {
			int index = scrollRow * COLUMNS + slot;
			int slotX = 8 + (slot % COLUMNS) * 18;
			int slotY = CATALOG_TOP + (slot / COLUMNS) * 18;
			context.fill(slotX, slotY, slotX + 18, slotY + 18, 0xFF3A525C);
			context.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0xFF0F171C);
			if (index >= filtered.size()) continue;
			ExchangeCatalog.Entry entry = filtered.get(index);
			context.drawItem(new ItemStack(entry.item()), slotX + 1, slotY + 1);
			if (balance < entry.price()) context.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0x90101418);
		}
		int track = ROWS * 18;
		int thumb = maxScroll() == 0 ? track : Math.max(10, track * ROWS / ((filtered.size() + COLUMNS - 1) / COLUMNS));
		int thumbY = maxScroll() == 0 ? 0 : (track - thumb) * scrollRow / maxScroll();
		int barX = 8 + COLUMNS * 18 + 4;
		context.fill(barX, CATALOG_TOP, barX + 4, CATALOG_TOP + track, 0xFF0F171C);
		context.fill(barX, CATALOG_TOP + thumbY, barX + 4, CATALOG_TOP + thumbY + thumb, 0xFF62C5A0);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		super.render(context, mouseX, mouseY, delta);
		ItemStack stack;
		long price;
		if (tab == null) {
			ExchangeCatalog.Entry entry = catalogAt(mouseX, mouseY);
			if (entry == null) return;
			stack = new ItemStack(entry.item());
			price = entry.price();
		} else {
			Offer offer = curatedAt(mouseX, mouseY);
			if (offer == null) return;
			stack = offer.stack();
			price = offer.price();
		}
		List<Text> tooltip = new ArrayList<>(Screen.getTooltipFromItem(client, stack));
		tooltip.add(Text.translatable(tab == null ? "exchange.rackcraft.price_each" : "exchange.rackcraft.price",
				String.format(Locale.ROOT, "%,d", price)).formatted(Formatting.GREEN));
		long balance = handler.balance();
		int rate = stat(Stat.MINING_RATE);
		if (balance < price && rate > 0) {
			long seconds = (long) Math.ceil((price - balance) / (rate / 100.0));
			tooltip.add(Text.translatable("exchange.rackcraft.eta", duration(seconds)).formatted(Formatting.GRAY));
		}
		tooltip.add(Text.translatable(tab == null ? "exchange.rackcraft.bulk_all" : "exchange.rackcraft.bulk", BULK)
				.formatted(Formatting.DARK_GRAY));
		context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			if (tab == null) {
				ExchangeCatalog.Entry entry = catalogAt(mouseX, mouseY);
				if (entry != null) {
					int times = hasControlDown() ? Math.min(64, entry.item().getMaxCount()) : hasShiftDown() ? BULK : 1;
					purchase(RackcraftNetworking.CATALOG_PREFIX + Registries.ITEM.getId(entry.item()), times);
					return true;
				}
			} else {
				Offer offer = curatedAt(mouseX, mouseY);
				if (offer != null) {
					purchase(offer.id(), hasShiftDown() ? BULK : 1);
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private void purchase(String id, int times) {
		ClientNet.buyItem(handler.pos(), id, times);
		client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (tab == null) {
			scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(amount)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// Typing in the search box must not close the screen on the inventory key.
		if (search.isFocused() && search.isVisible() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
			return search.keyPressed(keyCode, scanCode, modifiers) || true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private Offer curatedAt(double mouseX, double mouseY) {
		List<Offer> offers = curatedOffers();
		for (int index = 0; index < offers.size(); index++) {
			int cardX = x + 8 + (index % 2) * (CARD_WIDTH + 8);
			int cardY = y + GRID_TOP + (index / 2) * (CARD_HEIGHT + 2);
			if (mouseX >= cardX && mouseX < cardX + CARD_WIDTH && mouseY >= cardY && mouseY < cardY + CARD_HEIGHT) {
				return offers.get(index);
			}
		}
		return null;
	}

	private ExchangeCatalog.Entry catalogAt(double mouseX, double mouseY) {
		int column = (int) Math.floor((mouseX - x - 8) / 18);
		int row = (int) Math.floor((mouseY - y - CATALOG_TOP) / 18);
		if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) return null;
		int index = (scrollRow + row) * COLUMNS + column;
		return index < filtered.size() ? filtered.get(index) : null;
	}

	private static String duration(long seconds) {
		if (seconds < 60) return seconds + " s";
		if (seconds < 3600) return seconds / 60 + " min " + seconds % 60 + " s";
		return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
	}
}
