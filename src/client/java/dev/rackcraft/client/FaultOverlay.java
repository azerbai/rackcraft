package dev.rackcraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.rackcraft.RcItems;
import dev.rackcraft.world.FaultFinder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * The fault finder's client half. Holding a Multimeter, every machine with a problem within 128 blocks is
 * outlined through walls (red: stopped, amber: slowed), and a line at the top of the screen counts them and
 * points to the nearest.
 */
public final class FaultOverlay {
	private record Fault(BlockPos pos, int severity, String label) {}

	private static final List<Fault> FAULTS = new ArrayList<>();

	private FaultOverlay() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(FaultFinder.FAULTS, (client, handler, buf, responseSender) -> {
			int count = Math.max(0, Math.min(FaultFinder.MAX_FAULTS, buf.readVarInt()));
			List<Fault> received = new ArrayList<>(count);
			for (int index = 0; index < count; index++) {
				received.add(new Fault(BlockPos.fromLong(buf.readLong()), buf.readByte(), buf.readString(64)));
			}
			client.execute(() -> {
				FAULTS.clear();
				FAULTS.addAll(received);
			});
		});
		WorldRenderEvents.LAST.register(context -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (!active(client) || FAULTS.isEmpty()) return;
			Vec3d camera = context.camera().getPos();
			Matrix4f matrix = context.matrixStack().peek().getPositionMatrix();
			float pulse = 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 180.0);
			RenderSystem.disableDepthTest();
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShader(GameRenderer::getPositionColorProgram);
			RenderSystem.lineWidth(2.5f);
			BufferBuilder buffer = Tessellator.getInstance().getBuffer();
			buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
			for (Fault fault : FAULTS) {
				float red = 1;
				float green = fault.severity() >= 2 ? 0.2f : 0.7f;
				float blue = 0.1f;
				float alpha = fault.severity() >= 2 ? pulse : 0.8f;
				box(buffer, matrix, fault.pos(), camera, red, green, blue, alpha);
			}
			Tessellator.getInstance().draw();
			RenderSystem.lineWidth(1);
			RenderSystem.enableDepthTest();
		});
		HudRenderCallback.EVENT.register(FaultOverlay::hud);
	}

	private static boolean active(MinecraftClient client) {
		if (client.player == null) return false;
		boolean holding = client.player.getMainHandStack().isOf(RcItems.ITEMS.get("multimeter"))
				|| client.player.getOffHandStack().isOf(RcItems.ITEMS.get("multimeter"));
		if (!holding) FAULTS.clear();
		return holding;
	}

	private static void box(BufferBuilder buffer, Matrix4f matrix, BlockPos pos, Vec3d camera, float r, float g, float b, float a) {
		float x0 = (float) (pos.getX() - camera.x) - 0.02f;
		float y0 = (float) (pos.getY() - camera.y) - 0.02f;
		float z0 = (float) (pos.getZ() - camera.z) - 0.02f;
		float x1 = x0 + 1.04f;
		float y1 = y0 + 1.04f;
		float z1 = z0 + 1.04f;
		float[][] edges = {
				{x0, y0, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z0, x1, y1, z0}, {x0, y1, z1, x1, y1, z1},
				{x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
				{x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
		for (float[] edge : edges) {
			buffer.vertex(matrix, edge[0], edge[1], edge[2]).color(r, g, b, a).next();
			buffer.vertex(matrix, edge[3], edge[4], edge[5]).color(r, g, b, a).next();
		}
	}

	private static void hud(DrawContext context, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!active(client) || client.options.hudHidden) return;
		int width = client.getWindow().getScaledWidth();
		if (FAULTS.isEmpty()) {
			String clear = "Fault finder: no faults within " + FaultFinder.RANGE + " blocks";
			int x = (width - client.textRenderer.getWidth(clear)) / 2;
			context.drawTextWithShadow(client.textRenderer, clear, x, top(x), 0x62C5A0);
			return;
		}
		long red = FAULTS.stream().filter(fault -> fault.severity() >= 2).count();
		String summary = String.format(Locale.ROOT, "Fault finder: %d stopped, %d slowed within %d blocks",
				red, FAULTS.size() - red, FaultFinder.RANGE);
		Fault nearest = FAULTS.get(0);
		Vec3d eye = client.player.getPos();
		double dx = nearest.pos().getX() + 0.5 - eye.x;
		double dy = nearest.pos().getY() - Math.floor(eye.y);
		double dz = nearest.pos().getZ() + 0.5 - eye.z;
		String where = String.format(Locale.ROOT, "Nearest: %s, %d m %s%s", nearest.label(), Math.round(Math.sqrt(dx * dx + dz * dz)),
				compass(dx, dz), dy > 1 ? ", " + Math.round(dy) + " up" : dy < -1 ? ", " + Math.round(-dy) + " down" : "");
		int left = Math.min((width - client.textRenderer.getWidth(summary)) / 2, (width - client.textRenderer.getWidth(where)) / 2);
		int top = top(left);
		context.drawTextWithShadow(client.textRenderer, summary, (width - client.textRenderer.getWidth(summary)) / 2, top,
				red > 0 ? 0xE0645A : 0xE7A45D);
		context.drawTextWithShadow(client.textRenderer, where, (width - client.textRenderer.getWidth(where)) / 2, top + 11, 0xE5ECEB);
	}

	/** The top line's y: 4, or below the RackCoin balance box when centred text starting at {@code left} would overlap it. */
	private static int top(int left) {
		return left < CoinHud.boxRight + 4 && CoinHud.boxBottom > 0 ? CoinHud.boxBottom + 4 : 4;
	}

	/** Eight-point direction in world terms: north is -z. */
	private static String compass(double dx, double dz) {
		if (Math.abs(dx) < 1.5 && Math.abs(dz) < 1.5) return "here";
		String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
		double angle = Math.toDegrees(Math.atan2(-dx, dz));
		int index = (int) Math.round(((angle % 360) + 360) % 360 / 45) % 8;
		return names[index];
	}
}
