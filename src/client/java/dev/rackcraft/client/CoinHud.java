package dev.rackcraft.client;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RackcraftNetworking;
import dev.rackcraft.RcBlocks;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Util;

/** Top-left RackCoin readout: balance and live mining rate. Toggle with hud.enabled in rackcraft.json. */
public final class CoinHud {
	/** Where the balance box ended last frame (0 when hidden), so the fault finder can keep clear of it. */
	static int boxRight;
	static int boxBottom;

	private static final long STALE_MS = 3000;
	private static boolean visible;
	private static long balance;
	private static float rate;
	private static int miningRacks;
	private static long receivedAt;

	private CoinHud() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RackcraftNetworking.HUD, (client, handler, buf, responseSender) -> {
			boolean show = buf.readBoolean();
			long coins = buf.readVarLong();
			float coinsPerSecond = buf.readFloat();
			int racks = buf.readVarInt();
			client.execute(() -> {
				visible = show;
				balance = coins;
				rate = coinsPerSecond;
				miningRacks = racks;
				receivedAt = Util.getMeasuringTimeMs();
			});
		});
		HudRenderCallback.EVENT.register((context, tickDelta) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			boxRight = 0;
			boxBottom = 0;
			if (!RackcraftConfig.values.hud.enabled || !visible || client.options.hudHidden
					|| client.options.debugEnabled || Util.getMeasuringTimeMs() - receivedAt > STALE_MS) return;
			// Interpolate between server updates so the balance ticks up smoothly.
			double elapsed = (Util.getMeasuringTimeMs() - receivedAt) / 1000.0;
			long shown = balance + (long) Math.floor(rate * Math.min(elapsed, 1.5));
			String amount = String.format(Locale.ROOT, "%,d RC", shown);
			String detail = rate > 0
					? String.format(Locale.ROOT, "+%,.1f/s from %,d rack%s", rate, miningRacks, miningRacks == 1 ? "" : "s")
					: "not mining";
			int width = Math.max(client.textRenderer.getWidth(amount), client.textRenderer.getWidth(detail)) + 26;
			boxRight = 4 + width;
			boxBottom = 28;
			context.fill(4, 4, 4 + width, 28, 0x9017212A);
			context.drawItem(new ItemStack(RcBlocks.get("crypto_exchange")), 7, 8);
			context.drawText(client.textRenderer, amount, 26, 7, 0xFF62C5A0, true);
			context.drawText(client.textRenderer, detail, 26, 17, rate > 0 ? 0xFFAAB9BA : 0xFFE7A45D, false);
		});
	}
}
