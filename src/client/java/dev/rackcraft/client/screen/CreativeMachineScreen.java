package dev.rackcraft.client.screen;

import dev.rackcraft.CreativeSettings;
import dev.rackcraft.client.ClientNet;
import dev.rackcraft.screen.MachineScreenHandler;
import dev.rackcraft.screen.MachineScreenHandler.Stat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Edit a creative machine's values in-game. Enter or Apply saves; the server clamps to each setting's range. */
public final class CreativeMachineScreen extends RackcraftHandledScreen {
	private static final int ROW_TOP = 44;
	private static final int ROW_HEIGHT = 42;

	private final List<TextFieldWidget> fields = new ArrayList<>();
	private List<CreativeSettings.Setting> settings = List.of();
	private Text feedback = Text.empty();
	private int feedbackColor = MUTED;

	public CreativeMachineScreen(MachineScreenHandler handler, PlayerInventory inventory, Text title) {
		super(handler, inventory, title, 230, 190);
	}

	@Override
	protected void init() {
		super.init();
		playerInventoryTitleY = -10_000;
		settings = CreativeSettings.forBlock(blockId());
		fields.clear();
		double[] values = handler.creativeValues();
		for (int index = 0; index < settings.size(); index++) {
			TextFieldWidget field = new TextFieldWidget(textRenderer, x + 12, y + ROW_TOP + index * ROW_HEIGHT + 11, 120, 16,
					Text.translatable("creative.rackcraft." + settings.get(index).key()));
			field.setMaxLength(16);
			field.setText(index < values.length ? number(values[index]) : number(settings.get(index).defaultValue()));
			field.setEditable(handler.creativeEditable());
			fields.add(addDrawableChild(field));
		}
		ButtonWidget apply = addDrawableChild(ButtonWidget.builder(Text.translatable("creative.rackcraft.apply"), button -> apply())
				.dimensions(x + 140, y + ROW_TOP + 10, 78, 18).build());
		apply.active = handler.creativeEditable();
		if (!fields.isEmpty() && handler.creativeEditable()) setFocused(fields.get(0));
	}

	private void apply() {
		if (!handler.creativeEditable()) return;
		for (int index = 0; index < settings.size(); index++) {
			CreativeSettings.Setting setting = settings.get(index);
			String raw = fields.get(index).getText().replace(",", "").trim();
			double value;
			try {
				value = Double.parseDouble(raw);
			} catch (NumberFormatException exception) {
				feedback = Text.translatable("creative.rackcraft.invalid", fields.get(index).getText());
				feedbackColor = BAD;
				return;
			}
			double clamped = setting.clamp(value);
			fields.get(index).setText(number(clamped));
			ClientNet.setCreative(handler.pos(), setting.key(), clamped);
		}
		feedback = Text.translatable("creative.rackcraft.saved");
		feedbackColor = GOOD;
	}

	@Override
	protected void drawDashboard(DrawContext context) {
		for (int index = 0; index < settings.size(); index++) {
			CreativeSettings.Setting setting = settings.get(index);
			Text label = Text.translatable("creative.rackcraft." + setting.key()).append(setting.unit().isEmpty() ? ""
					: " (" + setting.unit() + ")");
			context.drawText(textRenderer, label, 12, ROW_TOP + index * ROW_HEIGHT, TEXT, false);
			String range = number(setting.min()) + " to " + number(setting.max());
			context.drawText(textRenderer, range, 12, ROW_TOP + index * ROW_HEIGHT + 29, MUTED, false);
		}
		int liveY = ROW_TOP + settings.size() * ROW_HEIGHT + 6;
		Text live = switch (blockId()) {
			case "creative_power" -> Text.translatable("creative.rackcraft.live_power", kw(stat(Stat.POWER)),
					kw(stat(Stat.NETWORK_DEMAND)));
			case "creative_rack" -> Text.translatable("creative.rackcraft.live_rack", coins(stat(Stat.MINING_RATE)),
					kw(stat(Stat.POWER)));
			case "creative_cooler" -> Text.translatable("creative.rackcraft.live_cooler",
					String.format(Locale.ROOT, "%.1f", dev.rackcraft.RackcraftConfig.values.thermal.ambientC));
			default -> Text.translatable("creative.rackcraft.live_router");
		};
		wrapped(context, live, 12, liveY, backgroundWidth - 24, GOOD);
		if (!handler.creativeEditable()) {
			wrapped(context, Text.translatable("creative.rackcraft.locked"), 12, backgroundHeight - 26, backgroundWidth - 24, WARN);
		} else {
			context.drawText(textRenderer, feedback, 12, backgroundHeight - 16, feedbackColor, false);
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			apply();
			return true;
		}
		// Keep typed digits in the field instead of triggering hotbar or inventory keys.
		for (TextFieldWidget field : fields) {
			if (field.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE) return field.keyPressed(keyCode, scanCode, modifiers) || true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private static String number(double value) {
		return value == Math.rint(value) ? String.format(Locale.ROOT, "%d", (long) value)
				: String.format(Locale.ROOT, "%.2f", value);
	}
}
