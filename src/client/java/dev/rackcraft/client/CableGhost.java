package dev.rackcraft.client;

import dev.rackcraft.RcItems;
import dev.rackcraft.world.CablePlanner;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * The Cable Planner's ghost: the route the server last sent, drawn as outlines through the world while the planner is
 * held. Cells are tinted for the cable kind, red where something is in the way and dim green where the cable is
 * already there.
 */
final class CableGhost {
	private static final float[][] COLORS = {
			{0.90f, 0.30f, 0.25f}, {0.35f, 0.65f, 0.95f}, {0.80f, 0.45f, 0.70f}, {0.95f, 0.70f, 0.30f}, {0.85f, 0.90f, 0.95f}};
	private static List<long[]> cells = new ArrayList<>();
	private static int kind;

	private CableGhost() {}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(CablePlanner.GHOST, (client, handler, buf, responseSender) -> {
			int newKind = buf.readVarInt();
			int count = Math.min(buf.readVarInt(), CablePlanner.MAX_LENGTH);
			List<long[]> next = new ArrayList<>(count);
			for (int index = 0; index < count; index++) next.add(new long[] {buf.readLong(), buf.readByte()});
			client.execute(() -> {
				cells = next;
				kind = newKind;
			});
		});
		WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player == null || context.consumers() == null || cells.isEmpty()) return;
			boolean holding = client.player.getMainHandStack().isOf(RcItems.ITEMS.get("cable_planner"))
					|| client.player.getOffHandStack().isOf(RcItems.ITEMS.get("cable_planner"));
			if (!holding) return;
			Vec3d camera = context.camera().getPos();
			MatrixStack matrices = context.matrixStack();
			matrices.push();
			matrices.translate(-camera.x, -camera.y, -camera.z);
			VertexConsumer lines = context.consumers().getBuffer(RenderLayer.getLines());
			float[] tint = COLORS[Math.floorMod(kind, COLORS.length)];
			for (long[] cell : cells) {
				BlockPos pos = BlockPos.fromLong(cell[0]);
				if (pos.getSquaredDistance(camera) > 160 * 160) continue;
				Box box = new Box(pos).contract(0.3);
				switch ((int) cell[1]) {
					case CablePlanner.BLOCKED -> WorldRenderer.drawBox(matrices, lines, new Box(pos).contract(0.05), 1.0f, 0.1f, 0.1f, 1.0f);
					case CablePlanner.EXISTING -> WorldRenderer.drawBox(matrices, lines, box, 0.3f, 0.7f, 0.3f, 0.7f);
					default -> WorldRenderer.drawBox(matrices, lines, box, tint[0], tint[1], tint[2], 1.0f);
				}
			}
			matrices.pop();
		});
	}
}
