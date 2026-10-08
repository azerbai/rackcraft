package dev.rackcraft.client.screen;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.generated.ContentIds;
import dev.rackcraft.generated.ContentIds.GuideChapter;
import dev.rackcraft.world.AssemblyLine;
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
	private final Map<Item, AssemblyLine.Recipe> lineRecipes = new HashMap<>();
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
		indexAssemblyLines();
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

	/** Every Assembly Line product by item; a diced wafer makes all three chiplet bins, so each gets the wafer's line. */
	private void indexAssemblyLines() {
		lineRecipes.clear();
		for (AssemblyLine.Recipe recipe : AssemblyLine.recipes()) {
			lineRecipes.putIfAbsent(recipe.product(), recipe);
			if (recipe.binned()) {
				lineRecipes.putIfAbsent(stackOf("chiplet_silver").getItem(), recipe);
				lineRecipes.putIfAbsent(stackOf("chiplet_gold").getItem(), recipe);
			}
		}
	}

	/**
	 * Pages are laid out by lines of text, so nothing ever runs under the buttons: a chapter's introduction or an
	 * entry's description that doesn't fit continues on the next page, followed by the chapter's item grid or the
	 * entry's recipes wherever there is room.
	 */
	private void buildPages() {
		pages.clear();
		entryPages.clear();
		// The contents run over as many pages as the chapters need, a full-size row each.
		int chapters = ContentIds.GUIDE_CHAPTERS.size();
		for (int first = 0; first < chapters; first += CONTENTS_ROWS) {
			pages.add(new Page(null, null, first > 0, first, Math.min(CONTENTS_ROWS, chapters - first), 0, 0, false, false));
		}
		for (GuideChapter chapter : ContentIds.GUIDE_CHAPTERS) {
			int body = bodyHeight(CHAPTER_HEADER_HEIGHT);
			Layout layout = new Layout(chapter, null);
			for (int line = 0; line < chapterLines(chapter).size(); line++) layout.line(body);
			int rows = (chapter.entries().size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
			layout.reserve(6 + rows * 22, body);
			layout.close(0, 0, true);
			for (String entry : chapter.entries()) {
				entryPages.put(entry, pages.size());
				body = bodyHeight(ENTRY_HEADER_HEIGHT);
				Layout entryLayout = new Layout(chapter, entry);
				for (int line = 0; line < entryLines(entry).size(); line++) entryLayout.line(body);
				List<Recipe<?>> recipes = recipesByOutput.getOrDefault(stackOf(entry).getItem(), List.of());
				AssemblyLine.Recipe line = lineRecipes.get(stackOf(entry).getItem());
				// An Assembly Line product has no crafting recipe to speak of, so its diagram stands in for the recipes.
				boolean lineOnly = line != null && recipes.isEmpty();
				if (lineOnly) {
					entryLayout.reserve(lineHeight(line), body);
					entryLayout.close(0, 0, true, true);
					continue;
				}
				entryLayout.reserve(RECIPE_HEADER_HEIGHT + (recipes.isEmpty() ? 10 : recipeHeight(recipes.get(0))), body);
				entryLayout.used += RECIPE_HEADER_HEIGHT;
				int first = 0;
				int count = 0;
				for (int index = 0; index < recipes.size(); index++) {
					int height = recipeHeight(recipes.get(index));
					if (entryLayout.used + height > body && count > 0) {
						entryLayout.close(first, count, true);
						entryLayout.used = RECIPE_HEADER_HEIGHT;
						first = index;
						count = 0;
					}
					entryLayout.used += height;
					count++;
				}
				// Crafting recipes first; the line it is also built on follows them, on a page of its own if need be.
				if (line != null && entryLayout.used + lineHeight(line) > body) {
					entryLayout.close(first, count, true);
					first = 0;
					count = 0;
				}
				entryLayout.close(first, count, true, line != null);
			}
		}
	}

	/** Builds one chapter's or entry's pages: lines go on until the page is full, then a continuation page starts. */
	private final class Layout {
		private final GuideChapter chapter;
		private final String entry;
		private boolean continuation;
		private int firstLine;
		private int lineCount;
		private int used;

		Layout(GuideChapter chapter, String entry) {
			this.chapter = chapter;
			this.entry = entry;
		}

		void line(int body) {
			if (used + 10 > body) close(0, 0, false);
			used += 10;
			lineCount++;
		}

		/** Starts a new page unless this much more fits on the current one. */
		void reserve(int height, int body) {
			if (used + height > body && (lineCount > 0 || continuation)) close(0, 0, false);
		}

		void close(int firstRecipe, int recipeCount, boolean extras) {
			close(firstRecipe, recipeCount, extras, false);
		}

		void close(int firstRecipe, int recipeCount, boolean extras, boolean diagram) {
			pages.add(new Page(chapter, entry, continuation, firstLine, lineCount, firstRecipe, recipeCount, extras, diagram));
			continuation = true;
			firstLine += lineCount;
			lineCount = 0;
			used = 0;
		}
	}

	private static final int CHAPTER_HEADER_HEIGHT = 18;
	private static final int ENTRY_HEADER_HEIGHT = 26;
	private static final int RECIPE_HEADER_HEIGHT = 18;
	private static final int GRID_COLUMNS = TEXT_WIDTH / 22;
	/** Chapters per contents page: rows of 15 between the "Contents" heading and the buttons. */
	private static final int CONTENTS_ROW_HEIGHT = 15;
	private static final int CONTENTS_ROWS = (PANEL_HEIGHT - 31 - 42) / CONTENTS_ROW_HEIGHT;

	/** Room for text, grids and recipes below a page's header, above the buttons. */
	private static int bodyHeight(int header) {
		return PANEL_HEIGHT - 31 - 32 - header;
	}

	private record Line(OrderedText text, int color) {}

	private List<Line> chapterLines(GuideChapter chapter) {
		List<Line> lines = new ArrayList<>();
		for (OrderedText text : textRenderer.wrapLines(Text.translatable("guide.rackcraft.chapter." + chapter.id() + ".intro"), TEXT_WIDTH)) {
			lines.add(new Line(text, COLOR_TEXT));
		}
		if (chapter.fuelPage()) {
			lines.add(new Line(OrderedText.EMPTY, COLOR_TEXT));
			for (OrderedText text : textRenderer.wrapLines(Text.translatable("guide.rackcraft.fuel_notes"), TEXT_WIDTH)) {
				lines.add(new Line(text, COLOR_MUTED));
			}
		}
		return lines;
	}

	private List<Line> entryLines(String entry) {
		List<Line> lines = new ArrayList<>();
		for (OrderedText text : textRenderer.wrapLines(Text.translatable("guide.rackcraft.entry." + entry), TEXT_WIDTH)) {
			lines.add(new Line(text, COLOR_TEXT));
		}
		Integer burnTicks = ContentIds.FUEL_TICKS.get(entry);
		if (burnTicks != null) lines.add(new Line(Text.translatable("guide.rackcraft.burn_time", burnTicks / 20).asOrderedText(), COLOR_WARM));
		return lines;
	}

	private int drawLines(DrawContext context, List<Line> lines, Page page, int x, int y) {
		for (Line line : lines.subList(Math.min(page.firstLine(), lines.size()), Math.min(lines.size(), page.firstLine() + page.lineCount()))) {
			context.drawText(textRenderer, line.text(), x, y, line.color(), false);
			y += 10;
		}
		return y;
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
		if (current.chapter() == null) renderContents(context, current);
		else if (current.entry() == null) renderChapter(context, current);
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

	private void renderContents(DrawContext context, Page current) {
		int x = left + 12;
		int y = top + 30;
		int contentsPages = (ContentIds.GUIDE_CHAPTERS.size() + CONTENTS_ROWS - 1) / CONTENTS_ROWS;
		Text heading = contentsPages > 1
				? Text.translatable("guide.rackcraft.contents").append(" (" + (current.firstLine() / CONTENTS_ROWS + 1) + " of " + contentsPages + ")")
				: Text.translatable("guide.rackcraft.contents");
		context.drawText(textRenderer, heading, x, y, COLOR_ACCENT, false);
		y += 12;
		int rowHeight = CONTENTS_ROW_HEIGHT;
		float iconScale = 1;
		for (int index = current.firstLine(); index < current.firstLine() + current.lineCount(); index++) {
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

	private void renderChapter(DrawContext context, Page current) {
		GuideChapter chapter = current.chapter();
		int x = left + 12;
		int y = top + 32;
		context.drawItem(stackOf(chapter.icon()), x, y - 4);
		Text title = Text.translatable("guide.rackcraft.chapter." + chapter.id());
		context.drawText(textRenderer, current.continuation() ? Text.translatable("guide.rackcraft.continued", title) : title,
				x + 20, y, COLOR_ACCENT, false);
		y += CHAPTER_HEADER_HEIGHT;
		y = drawLines(context, chapterLines(chapter), current, x, y);
		if (!current.extras()) return;
		y += 6;
		int column = 0;
		for (String entry : chapter.entries()) {
			int slotX = x + column * 22;
			slot(context, slotX, y, stackOf(entry), entryPages.get(entry));
			if (++column == GRID_COLUMNS) {
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
		y = drawLines(context, entryLines(entry), current, x, y);
		if (!current.extras()) {
			// The text fills this page; recipes follow, and say so if there's a line to spare.
			boolean lineNext = lineRecipes.containsKey(stack.getItem()) && !recipesByOutput.containsKey(stack.getItem());
			if (y + 16 <= top + PANEL_HEIGHT - 31) {
				context.drawText(textRenderer, Text.translatable(lineNext ? "guide.rackcraft.line_next" : "guide.rackcraft.recipes_next"),
						x, y + 6, COLOR_MUTED, false);
			}
			return;
		}
		List<Recipe<?>> recipes = recipesByOutput.getOrDefault(stack.getItem(), List.of());
		AssemblyLine.Recipe line = lineRecipes.get(stack.getItem());
		y += 6;
		// Recipes are listed unless this page only carries the assembly line (a product with no crafting recipe, or
		// the line that follows the recipes on a page of its own).
		if (recipes.isEmpty() ? line == null : current.recipeCount() > 0) {
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
			y += 2;
		}
		if (current.diagram() && line != null) renderLine(context, line, stack, x, y);
	}

	/** One robot on an example line, with the parts it fits (none for a Welding or Riveting Robot). */
	private record Station(AssemblyLine.Kind kind, List<AssemblyLine.Step> steps) {}

	/**
	 * The robots an Assembly Line recipe needs, in belt order. Consecutive installs share one Assembly Robot, which
	 * holds all their parts at once; welds and rivets each get their own robot.
	 */
	private static List<Station> stations(AssemblyLine.Recipe recipe) {
		List<Station> stations = new ArrayList<>();
		for (AssemblyLine.Step step : recipe.steps()) {
			Station last = stations.isEmpty() ? null : stations.get(stations.size() - 1);
			if (step.kind() == AssemblyLine.Kind.INSTALL && last != null && last.kind() == AssemblyLine.Kind.INSTALL) last.steps().add(step);
			else stations.add(new Station(step.kind(), new ArrayList<>(List.of(step))));
		}
		return stations;
	}

	private static int partColumns(int stations) {
		return (TEXT_WIDTH - LINE_MARGIN * 2) / stations >= 38 ? 2 : 1;
	}

	private static int partRows(List<Station> stations) {
		int columns = partColumns(stations.size());
		int most = 0;
		for (Station station : stations) {
			if (station.kind() == AssemblyLine.Kind.INSTALL) most = Math.max(most, station.steps().size());
		}
		return (most + columns - 1) / columns;
	}

	/** The diagram: its heading, the rows of parts, the arms, the belt and the verbs under it. */
	private static int lineHeight(AssemblyLine.Recipe recipe) {
		return RECIPE_HEADER_HEIGHT + partRows(stations(recipe)) * 18 + LINE_BODY_HEIGHT;
	}

	private static final int LINE_MARGIN = 22;
	/** Arm (18), reach (3), belt (18) and verb row (11), after the parts and a 3 pixel gap. */
	private static final int LINE_BODY_HEIGHT = 3 + 18 + 3 + 18 + 2 + 9;

	/**
	 * A top-down example of the line for a product: the base item enters at the left, a Conveyor Belt carries it past
	 * the robots in order (each sits above the belt facing it, with its parts above it), and the product leaves at the
	 * right. Parts and robots are slots, so they can be hovered and clicked like anywhere else in the manual.
	 */
	private void renderLine(DrawContext context, AssemblyLine.Recipe recipe, ItemStack product, int x, int y) {
		context.drawText(textRenderer, Text.translatable("guide.rackcraft.assembly_line"), x, y, COLOR_MUTED, false);
		y += 12;
		List<Station> stations = stations(recipe);
		int columnWidth = (TEXT_WIDTH - LINE_MARGIN * 2) / stations.size();
		int partColumns = partColumns(stations.size());
		int partRows = partRows(stations);
		int armY = y + partRows * 18 + 3;
		int beltY = armY + 18 + 3;
		ItemStack base = new ItemStack(recipe.base());

		// The belt runs between the two end slots, with dashes to show which way it goes.
		int beltLeft = x + 18;
		int beltRight = x + TEXT_WIDTH - 18;
		context.fill(beltLeft, beltY + 1, beltRight, beltY + 17, COLOR_SLOT_EDGE);
		context.fill(beltLeft, beltY + 2, beltRight, beltY + 16, COLOR_SLOT);
		for (int dash = beltLeft + 3; dash + 4 < beltRight - 6; dash += 8) {
			context.fill(dash, beltY + 8, dash + 4, beltY + 10, COLOR_SLOT_EDGE);
		}
		for (int step = 0; step < 4; step++) {
			context.fill(beltRight - 6 + step, beltY + 5 + step, beltRight - 5 + step, beltY + 13 - step, COLOR_ACCENT);
		}
		slot(context, x, beltY, base, targetOf(base));
		slot(context, x + TEXT_WIDTH - 18, beltY, new ItemStack(product.getItem(), recipe.count()), targetOf(product));

		for (int index = 0; index < stations.size(); index++) {
			Station station = stations.get(index);
			int centre = x + LINE_MARGIN + index * columnWidth + columnWidth / 2;
			// The workpiece waits in the middle of the belt in front of its robot.
			context.drawItem(base, centre - 8, beltY + 1);
			context.fill(centre - 1, armY + 18, centre + 1, beltY + 1, COLOR_ACCENT);
			ItemStack arm = stackOf(station.kind().blockId);
			slot(context, centre - 9, armY, arm, targetOf(arm));
			if (station.kind() == AssemblyLine.Kind.INSTALL) {
				int rows = (station.steps().size() + partColumns - 1) / partColumns;
				// Parts sit against the robot, filling from the bottom row up.
				int partsLeft = centre - partColumns * 9;
				int partsTop = armY - 3 - rows * 18;
				for (int part = 0; part < station.steps().size(); part++) {
					AssemblyLine.Step step = station.steps().get(part);
					ItemStack stack = new ItemStack(step.part(), Math.min(step.count(), 64));
					slot(context, partsLeft + part % partColumns * 18, partsTop + part / partColumns * 18, stack, targetOf(stack));
				}
			}
			Text verb = Text.literal(station.kind().verb);
			context.drawText(textRenderer, verb, centre - textRenderer.getWidth(verb) / 2, beltY + 20, COLOR_MUTED, false);
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

	/**
	 * A page: the lines of its chapter's or entry's text it shows, and whether it carries the extras (the chapter's
	 * item grid, or the entry's recipes from {@code firstRecipe}), and whether it ends with the entry's example
	 * assembly line.
	 */
	private record Page(GuideChapter chapter, String entry, boolean continuation, int firstLine, int lineCount,
			int firstRecipe, int recipeCount, boolean extras, boolean diagram) {}

	private record Hotspot(int x, int y, int width, int height, ItemStack stack, int target, boolean slot) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}
	}
}
