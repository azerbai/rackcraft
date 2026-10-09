package dev.rackcraft.client;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RcBlocks;
import dev.rackcraft.world.ProcurementWall;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/**
 * Top-right ledger from the nearest powered Procurement Wall: what the drones have spent, any quote waiting for a yes, and
 * the latest purchases. Turn it off from the wall's screen (hud.procurement in rackcraft.json).
 */
public final class ProcurementHud {
	private static final long STALE_MS = 3000;
	private static ProcurementWall.Hud view = ProcurementWall.Hud.HIDDEN;
	private static long receivedAt;

	private ProcurementHud() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ProcurementWall.HUD, (client, handler, buf, responseSender) -> {
			ProcurementWall.Hud received = ProcurementWall.Hud.read(buf);
			client.execute(() -> {
				view = received;
				receivedAt = Util.getMeasuringTimeMs();
			});
		});
		HudRenderCallback.EVENT.register((context, tickDelta) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (!RackcraftConfig.values.hud.procurement || !view.show() || client.options.hudHidden || client.options.debugEnabled
					|| Util.getMeasuringTimeMs() - receivedAt > STALE_MS) return;
			var font = client.textRenderer;
			List<String> lines = new ArrayList<>();
			List<Integer> colors = new ArrayList<>();
			lines.add(String.format(Locale.ROOT, "Procurement: %,d RC spent", view.spent()));
			colors.add(0xFFE5C07A);
			String fleet = view.planners() == 0 ? "no Site Planner on this grid"
					: view.planners() + (view.planners() == 1 ? " planner, " : " planners, ") + view.working() + " working";
			lines.add(fleet);
			colors.add(0xFFAAB9BA);
			if (view.awaiting() > 0) {
				boolean flash = (Util.getMeasuringTimeMs() / 500) % 2 == 0;
				lines.add(String.format(Locale.ROOT, "QUOTE WAITING: %,d RC - approve it", view.pending()));
				colors.add(flash ? 0xFFFFD27A : 0xFFE7A45D);
			}
			for (ProcurementWall.Row row : view.recent()) {
				String name = new ItemStack(Registries.ITEM.get(new Identifier(row.item()))).getName().getString();
				long seconds = row.age() / 20;
				String when = seconds < 60 ? seconds + "s" : seconds < 3600 ? seconds / 60 + "m" : seconds / 3600 + "h";
				lines.add(String.format(Locale.ROOT, "%s  %,d x %s  -%,d", when, row.count(), name, row.cost()));
				colors.add(0xFFE5ECEB);
			}
			if (view.recent().isEmpty()) {
				lines.add("nothing bought yet");
				colors.add(0xFF7F8C8D);
			}
			int width = 0;
			for (String line : lines) width = Math.max(width, font.getWidth(line));
			width += 26;
			int height = 4 + lines.size() * 10 + 2;
			int left = client.getWindow().getScaledWidth() - width - 4;
			context.fill(left, 4, left + width, 4 + height, 0x9017212A);
			context.drawItem(new ItemStack(RcBlocks.get("procurement_wall")), left + 3, 7);
			for (int index = 0; index < lines.size(); index++) {
				context.drawText(font, lines.get(index), left + 22, 7 + index * 10, colors.get(index), index == 0);
			}
		});
	}
}
