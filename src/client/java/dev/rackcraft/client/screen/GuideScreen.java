package dev.rackcraft.client.screen;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.generated.ContentIds;
import dev.rackcraft.generated.ContentIds.GuideChapter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.BlastingRecipe;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/** The Rackcraft Field Manual: chapters and entries from ContentIds, recipes from the live recipe manager. */
public final class GuideScreen extends Screen {
	private static final int PANEL_WIDTH = 252;
	private static final int PANEL_HEIGHT = 226;
	private static final int TEXT_WIDTH = PANEL_WIDTH - 24;
	private static final int COLOR_PANEL = 0xFF17212A;
	private static final int COLOR_HEADER = 0xFF2C414A;
	private static final int COLOR_INNER = 0xFF202D34;
	private static final int COLOR_SLOT = 0xFF0F171C;
	private static final int COLOR_SLOT_EDGE = 0xFF3A525C;
	private static final int COLOR_TEXT = 0xFFE5ECEB;
	private static final int COLOR_ACCENT = 0xFF62C5A0;
	private static final int COLOR_MUTED = 0xFFAAB9BA;
	private static final int COLOR_WARM = 0xFFE7A45D;
	private static int rememberedPage;

	private final List<Page> pages = new ArrayList<>();
	private final Map<String, Integer> entryPages = new HashMap<>();
	private final Map<Item, List<Recipe<?>>> recipesByOutput = new HashMap<>();
	private final List<Hotspot> hotspots = new ArrayList<>();
	private final Deque<Integer> history = new ArrayDeque<>();
	private int page;
	private int left;
	private int top;
	private ButtonWidget previousButton;
	private ButtonWidget nextButton;
	private ButtonWidget backButton;

	public GuideScreen() {
		super(Text.translatable("guide.rackcraft.title"));
	}

	@Override
	protected void init() {
		left = (width - PANEL_WIDTH) / 2;
		top = (height - PANEL_HEIGHT) / 2;
		indexRecipes();
		buildPages();
		page = Math.min(rememberedPage, pages.size() - 1);
		int buttonY = top + PANEL_HEIGHT - 24;
		previousButton = addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> turn(-1))
				.dimensions(left + 8, buttonY, 20, 18).build());
		backButton = addDrawableChild(ButtonWidget.builder(Text.translatable("guide.rackcraft.back"), button -> back())
				.dimensions(left + 32, buttonY, 50, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("guide.rackcraft.contents"), button -> open(0))
				.dimensions(left + PANEL_WIDTH / 2 - 30, buttonY, 60, 18).build());
		nextButton = addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> turn(1))
				.dimensions(left + PANEL_WIDTH - 28, buttonY, 20, 18).build());
		updateButtons();
	}

	private void indexRecipes() {
		recipesByOutput.clear();
		if (client == null || client.world == null) return;
		var registries = client.world.getRegistryManager();
		for (Recipe<?> recipe : client.world.getRecipeManager().values()) {
			if (!recipe.getId().getNamespace().equals(Rackcraft.MOD_ID)) continue;
			ItemStack output = recipe.getOutput(registries);
			if (!output.isEmpty()) recipesByOutput.computeIfAbsent(output.getItem(), item -> new ArrayList<>()).add(recipe);
		}
		// Crafting first, then furnace recipes; blast furnace before the slower plain furnace.
		recipesByOutput.values().forEach(list -> list.sort(Comparator
				.comparing((Recipe<?> recipe) -> recipe instanceof AbstractCookingRecipe)
				.thenComparing(recipe -> !(recipe instanceof BlastingRecipe))
				.thenComparing(recipe -> recipe.getId().toString())));
	}

	/**
	 * One page per chapter and entry. Entries whose text leaves too little room for their recipes
	 * continue onto extra pages, so a long description never hides a crafting grid.
	 */
	private void buildPages() {
		pages.clear();
		entryPages.clear();
		pages.add(new Page(null, null, false, 0, 0));
		int available = PANEL_HEIGHT - 32 - 32;
		for (GuideChapter chapter : ContentIds.GUIDE_CHAPTERS) {
			pages.add(new Page(chapter, null, false, 0, 0));
			for (String entry : chapter.entries()) {
				entryPages.put(entry, pages.size());
				List<Recipe<?>> recipes = recipesByOutput.getOrDefault(stackOf(entry).getItem(), List.of());
				int used = entryTextHeight(entry);
				int first = 0;
				int count = 0;
				boolean continuation = false;
				for (int index = 0; index < recipes.size(); index++) {
					int height = recipeHeight(recipes.get(index));
					if (used + height > available) {
						// Close this page (possibly with no recipes if the text filled it) and continue.
						pages.add(new Page(chapter, entry, continuation, first, count));
						continuation = true;
						first = index;
						count = 0;
						used = ENTRY_HEADER_HEIGHT + RECIPE_HEADER_HEIGHT;
					}
					used += height;
					count++;
				}
				pages.add(new Page(chapter, entry, continuation, first, count));
			}
		}
	}

	private static final int ENTRY_HEADER_HEIGHT = 26;
	private static final int RECIPE_HEADER_HEIGHT = 18;

	private int entryTextHeight(String entry) {
		int lines = textRenderer.wrapLines(Text.translatable("guide.rackcraft.entry." + entry), TEXT_WIDTH).size();
		return ENTRY_HEADER_HEIGHT + lines * 10 + (ContentIds.FUEL_TICKS.containsKey(entry) ? 12 : 0) + RECIPE_HEADER_HEIGHT;
	}

	private static int recipeHeight(Recipe<?> recipe) {
		return (recipe instanceof AbstractCookingRecipe ? 20 : 56) + 4;
	}

	private void turn(int delta) {
		open(Math.max(0, Math.min(pages.size() - 1, page + delta)), false);
	}

	private void open(int target) {
		open(target, true);
	}

	private void open(int target, boolean remember) {
		if (target == page) return;
		if (remember) history.push(page);
		page = target;
		rememberedPage = target;
		updateButtons();
	}

	private void back() {
		if (!history.isEmpty()) {
			page = history.pop();
			rememberedPage = page;
			updateButtons();
		}
	}

	private void updateButtons() {
		if (previousButton == null) return;
		previousButton.active = page > 0;
		nextButton.active = page < pages.size() - 1;
		backButton.active = !history.isEmpty();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		context.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, COLOR_PANEL);
		context.fill(left, top, left + PANEL_WIDTH, top + 22, COLOR_HEADER);
		context.fill(left + 5, top + 27, left + PANEL_WIDTH - 5, top + PANEL_HEIGHT - 29, COLOR_INNER);
		context.drawText(textRenderer, title, left + 8, top + 7, COLOR_TEXT, false);
		Text pageNumber = Text.translatable("guide.rackcraft.page", page + 1, pages.size());
		context.drawText(textRenderer, pageNumber, left + PANEL_WIDTH - 8 - textRenderer.getWidth(pageNumber),
				top + 7, COLOR_MUTED, false);

		hotspots.clear();
		Page current = pages.get(page);
		if (current.chapter() == null) renderContents(context);
		else if (current.entry() == null) renderChapter(context, current.chapter());
		else renderEntry(context, current);
		super.render(context, mouseX, mouseY, delta);

		for (Hotspot hotspot : hotspots) {
			if (!hotspot.slot() || !hotspot.contains(mouseX, mouseY)) continue;
			List<Text> tooltip = new ArrayList<>(getTooltipFromItem(client, hotspot.stack()));
			if (hotspot.target() >= 0 && hotspot.target() != page) {
				tooltip.add(Text.translatable("guide.rackcraft.click_to_open").styled(style -> style.withColor(COLOR_ACCENT & 0xFFFFFF)));
			}
			context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
			break;
		}
	}

	private void renderContents(DrawContext context) {
		int x = left + 12;
		int y = top + 30;
		context.drawText(textRenderer, Text.translatable("guide.rackcraft.contents"), x, y, COLOR_ACCENT, false);
		y += 12;
		// Rows shrink to fit every chapter above the buttons; below 15 px the icons shrink with them.
		int chapters = ContentIds.GUIDE_CHAPTERS.size();
		int available = top + PANEL_HEIGHT - 31 - y;
		int rowHeight = Math.max(10, Math.min(15, available / Math.max(1, chapters)));
		float iconScale = rowHeight >= 15 ? 1 : (rowHeight - 1) / 16f;
		for (int index = 0; index < chapters; index++) {
			GuideChapter chapter = ContentIds.GUIDE_CHAPTERS.get(index);
			int target = pageOfChapter(chapter);
			context.getMatrices().push();
			context.getMatrices().translate(x, y, 0);
			context.getMatrices().scale(iconScale, iconScale, 1);
			context.drawItem(stackOf(chapter.icon()), 0, 0);
			context.getMatrices().pop();
			int textY = y + Math.max(1, (rowHeight - 8) / 2);
			context.drawText(textRenderer, Text.translatable("guide.rackcraft.chapter." + chapter.id()),
					x + 22, textY, COLOR_TEXT, false);
			context.drawText(textRenderer, Text.literal(chapter.entries().size() + ""),
					left + PANEL_WIDTH - 22, textY, COLOR_MUTED, false);
			hotspots.add(new Hotspot(x, y, TEXT_WIDTH, rowHeight, stackOf(chapter.icon()), target, false));
			y += rowHeight;
		}
	}

	private void renderChapter(DrawContext context, GuideChapter chapter) {
		int x = left + 12;
		int y = top + 32;
		context.drawItem(stackOf(chapter.icon()), x, y - 4);
		context.drawText(textRenderer, Text.translatable("guide.rackcraft.chapter." + chapter.id()),
				x + 20, y, COLOR_ACCENT, false);
		y += 18;
		y = paragraph(context, Text.translatable("guide.rackcraft.chapter." + chapter.id() + ".intro"), x, y, COLOR_TEXT);
		if (chapter.fuelPage()) y = paragraph(context, Text.translatable("guide.rackcraft.fuel_notes"), x, y + 4, COLOR_MUTED);
		y += 6;
		int column = 0;
		for (String entry : chapter.entries()) {
			int slotX = x + column * 22;
			slot(context, slotX, y, stackOf(entry), entryPages.get(entry));
			if (++column == TEXT_WIDTH / 22) {
				column = 0;
				y += 22;
			}
		}
	}

	private void renderEntry(DrawContext context, Page current) {
		String entry = current.entry();
		ItemStack stack = stackOf(entry);
		int x = left + 12;
		int y = top + 32;
		var matrices = context.getMatrices();
		matrices.push();
		matrices.translate(x, y - 2, 0);
		matrices.scale(1.5f, 1.5f, 1);
		context.drawItem(stack, 0, 0);
		matrices.pop();
		Text name = current.continuation()
				? Text.translatable("guide.rackcraft.continued", stack.getName()) : stack.getName();
		context.drawText(textRenderer, name, x + 30, y + 4, COLOR_ACCENT, false);
		y += ENTRY_HEADER_HEIGHT;
		if (!current.continuation()) {
			y = paragraph(context, Text.translatable("guide.rackcraft.entry." + entry), x, y, COLOR_TEXT);
			Integer burnTicks = ContentIds.FUEL_TICKS.get(entry);
			if (burnTicks != null) {
				context.drawText(textRenderer, Text.translatable("guide.rackcraft.burn_time", burnTicks / 20),
						x, y + 2, COLOR_WARM, false);
				y += 12;
			}
		}

		List<Recipe<?>> recipes = recipesByOutput.getOrDefault(stack.getItem(), List.of());
		if (current.recipeCount() == 0 && !recipes.isEmpty()) {
			// The text filled this page; recipes follow on the next one.
			context.drawText(textRenderer, Text.translatable("guide.rackcraft.recipes_next"), x, y + 6, COLOR_MUTED, false);
			return;
		}
		y += 6;
		context.drawText(textRenderer, Text.translatable("guide.rackcraft.recipes"), x, y, COLOR_MUTED, false);
		y += 12;
		if (recipes.isEmpty()) {
			paragraph(context, Text.translatable("guide.rackcraft.no_recipe"), x, y, COLOR_MUTED);
			return;
		}
		for (Recipe<?> recipe : recipes.subList(current.firstRecipe(), current.firstRecipe() + current.recipeCount())) {
			if (recipe instanceof AbstractCookingRecipe cooking) renderCooking(context, cooking, x, y);
			else if (recipe instanceof CraftingRecipe crafting) renderCrafting(context, crafting, x, y);
			y += recipeHeight(recipe);
		}
	}

	private void renderCrafting(DrawContext context, CraftingRecipe recipe, int x, int y) {
		List<Ingredient> ingredients = recipe.getIngredients();
		int gridWidth = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				int index = row * gridWidth + column;
				ItemStack shown = column < gridWidth && index < ingredients.size()
						? cycle(ingredients.get(index)) : ItemStack.EMPTY;
				slot(context, x + column * 18, y + row * 18, shown, targetOf(shown));
			}
		}
		arrow(context, x + 60, y + 22, Text.translatable("guide.rackcraft.crafting"));
		ItemStack output = recipe.getOutput(client.world.getRegistryManager());
		slot(context, x + 132, y + 18, output, targetOf(output));
	}

	private void renderCooking(DrawContext context, AbstractCookingRecipe recipe, int x, int y) {
		ItemStack input = cycle(recipe.getIngredients().get(0));
		slot(context, x, y, input, targetOf(input));
		String key = recipe instanceof BlastingRecipe ? "guide.rackcraft.blasting" : "guide.rackcraft.smelting";
		arrow(context, x + 24, y + 4, Text.translatable(key, recipe.getCookTime() / 20));
		ItemStack output = recipe.getOutput(client.world.getRegistryManager());
		slot(context, x + 132, y, output, targetOf(output));
	}

	private void arrow(DrawContext context, int x, int y, Text label) {
		int labelWidth = Math.min(100, textRenderer.getWidth(label));
		context.drawText(textRenderer, label, x + (100 - labelWidth) / 2, y - 1, COLOR_MUTED, false);
		context.fill(x, y + 10, x + 96, y + 11, COLOR_SLOT_EDGE);
		for (int step = 0; step < 4; step++) {
			context.fill(x + 96 + step, y + 7 + step, x + 97 + step, y + 14 - step, COLOR_SLOT_EDGE);
		}
	}

	private void slot(DrawContext context, int x, int y, ItemStack stack, int target) {
		context.fill(x, y, x + 18, y + 18, COLOR_SLOT_EDGE);
		context.fill(x + 1, y + 1, x + 17, y + 17, COLOR_SLOT);
		if (stack.isEmpty()) return;
		context.drawItem(stack, x + 1, y + 1);
		context.drawItemInSlot(textRenderer, stack, x + 1, y + 1);
		if (target >= 0) context.fill(x + 15, y + 15, x + 17, y + 17, COLOR_ACCENT);
		hotspots.add(new Hotspot(x, y, 18, 18, stack, target, true));
	}

	private int paragraph(DrawContext context, Text text, int x, int y, int color) {
		for (OrderedText line : textRenderer.wrapLines(text, TEXT_WIDTH)) {
			context.drawText(textRenderer, line, x, y, color, false);
			y += 10;
		}
		return y;
	}

	private static ItemStack cycle(Ingredient ingredient) {
		ItemStack[] options = ingredient.getMatchingStacks();
		if (options.length == 0) return ItemStack.EMPTY;
		return options[(int) (Util.getMeasuringTimeMs() / 1000 % options.length)];
	}

	private int targetOf(ItemStack stack) {
		if (stack.isEmpty()) return -1;
		var id = Registries.ITEM.getId(stack.getItem());
		if (!id.getNamespace().equals(Rackcraft.MOD_ID)) return -1;
		return entryPages.getOrDefault(id.getPath(), -1);
	}

	private int pageOfChapter(GuideChapter chapter) {
		for (int index = 0; index < pages.size(); index++) {
			if (pages.get(index).chapter() == chapter && pages.get(index).entry() == null) return index;
		}
		return 0;
	}

	private static ItemStack stackOf(String id) {
		return new ItemStack(Registries.ITEM.get(Rackcraft.id(id)));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			for (Hotspot hotspot : hotspots) {
				if (hotspot.contains(mouseX, mouseY) && hotspot.target() >= 0) {
					open(hotspot.target());
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		turn(amount > 0 ? -1 : 1);
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		switch (keyCode) {
			case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_PAGE_UP -> turn(-1);
			case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_PAGE_DOWN -> turn(1);
			case GLFW.GLFW_KEY_BACKSPACE -> back();
			case GLFW.GLFW_KEY_HOME -> open(0);
			default -> {
				return super.keyPressed(keyCode, scanCode, modifiers);
			}
		}
		return true;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private record Page(GuideChapter chapter, String entry, boolean continuation, int firstRecipe, int recipeCount) {}

	private record Hotspot(int x, int y, int width, int height, ItemStack stack, int target, boolean slot) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}
	}
}
