package dev.rackcraft.client;

import dev.rackcraft.RcItems;
import dev.rackcraft.item.SurveyStakeItem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * While a Survey Stake is in either hand, outlines the site it has marked in orange (from the lower corner's height to
 * a few blocks over the higher one), or just the first corner while the second isn't marked yet.
 */
final class SurveyOutline {
	private SurveyOutline() {}

	static void register() {
		WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player == null || context.consumers() == null) return;
			ItemStack stake = client.player.getMainHandStack();
			boolean scanner = stake.isOf(RcItems.ITEMS.get("blueprint_scanner"));
			if (!scanner && !stake.isOf(RcItems.ITEMS.get("survey_stake"))) {
				stake = client.player.getOffHandStack();
				scanner = stake.isOf(RcItems.ITEMS.get("blueprint_scanner"));
			}
			if (!scanner && !stake.isOf(RcItems.ITEMS.get("survey_stake"))) return;
			BlockPos a = SurveyStakeItem.corner(stake, SurveyStakeItem.FIRST);
			if (a == null) return;
			BlockPos b = SurveyStakeItem.corner(stake, SurveyStakeItem.SECOND);
			Box box = b == null ? new Box(a)
					: new Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
							Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + (scanner ? 1 : 4), Math.max(a.getZ(), b.getZ()) + 1);
			Vec3d camera = context.camera().getPos();
			MatrixStack matrices = context.matrixStack();
			matrices.push();
			matrices.translate(-camera.x, -camera.y, -camera.z);
			VertexConsumer lines = context.consumers().getBuffer(RenderLayer.getLines());
			if (scanner) WorldRenderer.drawBox(matrices, lines, box.expand(0.002), 0.3f, 0.75f, 1.0f, 1.0f);
			else WorldRenderer.drawBox(matrices, lines, box.expand(0.002), 1.0f, 0.55f, 0.1f, 1.0f);
			matrices.pop();
		});
	}
}
