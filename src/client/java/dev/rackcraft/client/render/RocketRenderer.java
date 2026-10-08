package dev.rackcraft.client.render;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.entity.RocketEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/**
 * The rocket, sized to the job. One stage is a slim Redstone-style rocket about 12 blocks tall; two stages a stubby
 * Saturn IB about 19; three stages a full Saturn V, three blocks across and about 37 tall: five engines under
 * flared fairings and fins, the black-banded first stage with USA down its side, the second and third stages, the
 * instrument ring, and a capsule under its orange escape tower.
 *
 * Units are pixels (16 to a block) with the bottom of the engines at the entity's feet. Each big stage has its own
 * painted texture; the small parts share a strip of tiles.
 */
public final class RocketRenderer extends EntityRenderer<RocketEntity> {
	private static final Identifier PARTS = Rackcraft.id("textures/entity/rocket.png");
	private static final Identifier FIRST = Rackcraft.id("textures/entity/rocket_first_stage.png");
	private static final Identifier SECOND = Rackcraft.id("textures/entity/rocket_second_stage.png");
	private static final Identifier THIRD = Rackcraft.id("textures/entity/rocket_third_stage.png");
	private static final int TILES = 7;
	private static final int INTERSTAGE = 0;
	private static final int ENGINE = 1;
	private static final int RING = 2;
	private static final int CAPSULE = 3;
	private static final int FIN = 4;
	private static final int TOWER = 5;
	private static final int ENGINE_FAIRING = 6;

	public RocketRenderer(EntityRendererFactory.Context context) {
		super(context);
		shadowRadius = 1.6f;
	}

	@Override
	public void render(RocketEntity rocket, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
			int light) {
		int overlay = OverlayTexture.DEFAULT_UV;
		// Lit engines glow at full brightness, whatever the light around the pad.
		int engineLight = rocket.flightTicks() >= 0 ? LightmapTextureManager.MAX_LIGHT_COORDINATE : light;
		Parts parts = new Parts(matrices, vertexConsumers, overlay);
		matrices.push();
		matrices.scale(1 / 16f, 1 / 16f, 1 / 16f);
		switch (Math.max(1, Math.min(3, rocket.stages()))) {
			case 1 -> redstone(matrices, vertexConsumers, parts, engineLight, light, overlay);
			case 2 -> saturnIB(matrices, vertexConsumers, parts, engineLight, light, overlay);
			default -> saturnV(matrices, vertexConsumers, parts, engineLight, light, overlay);
		}
		matrices.pop();
		super.render(rocket, yaw, tickDelta, matrices, vertexConsumers, light);
	}

	private static void saturnV(MatrixStack matrices, VertexConsumerProvider consumers, Parts parts, int engineLight, int light, int overlay) {
		for (double[] engine : RocketEntity.engines(3)) {
			float x = (float) engine[0] * 16;
			float z = (float) engine[1] * 16;
			parts.box(x - 5, 0, z - 5, x + 5, 14, z + 5, ENGINE, engineLight);
		}
		// Engine fairings flare out over the four outer engines, each with a fin.
		for (int corner = 0; corner < 4; corner++) {
			matrices.push();
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45 + corner * 90));
			parts.box(-6, 10, 20, 6, 44, 32, ENGINE_FAIRING, light);
			parts.box(-1, 12, 32, 1, 46, 44, FIN, light);
			matrices.pop();
		}
		body(matrices, consumers, FIRST, 24, 14, 206, light, overlay);
		parts.box(-24, 206, -24, 24, 222, 24, INTERSTAGE, light);
		body(matrices, consumers, SECOND, 24, 222, 366, light, overlay);
		parts.box(-21, 366, -21, 21, 378, 21, INTERSTAGE, light);
		body(matrices, consumers, THIRD, 17, 378, 474, light, overlay);
		parts.box(-17, 474, -17, 17, 480, 17, RING, light);
		// The spacecraft: the adapter tapering in, the service module, the capsule and the escape tower on top.
		parts.box(-15, 480, -15, 15, 496, 15, CAPSULE, light);
		parts.box(-13, 496, -13, 13, 506, 13, CAPSULE, light);
		parts.box(-12, 506, -12, 12, 532, 12, RING, light);
		parts.box(-10, 532, -10, 10, 540, 10, CAPSULE, light);
		parts.box(-7, 540, -7, 7, 546, 7, CAPSULE, light);
		parts.box(-4, 546, -4, 4, 550, 4, CAPSULE, light);
		parts.box(-2, 550, -2, 2, 586, 2, TOWER, light);
		parts.box(-1.5f, 586, -1.5f, 1.5f, 596, 1.5f, RING, light);
		parts.box(-0.75f, 596, -0.75f, 0.75f, 602, 0.75f, TOWER, light);
	}

	private static void saturnIB(MatrixStack matrices, VertexConsumerProvider consumers, Parts parts, int engineLight, int light, int overlay) {
		for (double[] engine : RocketEntity.engines(2)) {
			float x = (float) engine[0] * 16;
			float z = (float) engine[1] * 16;
			parts.box(x - 4, 0, z - 4, x + 4, 10, z + 4, ENGINE, engineLight);
		}
		for (int fin = 0; fin < 4; fin++) {
			matrices.push();
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45 + fin * 90));
			parts.box(-1, 10, 22, 1, 36, 32, FIN, light);
			matrices.pop();
		}
		body(matrices, consumers, SECOND, 20, 10, 138, light, overlay);
		parts.box(-20, 138, -20, 20, 150, 20, INTERSTAGE, light);
		body(matrices, consumers, THIRD, 17, 150, 246, light, overlay);
		parts.box(-17, 246, -17, 17, 252, 17, RING, light);
		fairing(parts, 17, 252, light);
	}

	private static void redstone(MatrixStack matrices, VertexConsumerProvider consumers, Parts parts, int engineLight, int light, int overlay) {
		parts.box(-5, 0, -5, 5, 10, 5, ENGINE, engineLight);
		for (int fin = 0; fin < 4; fin++) {
			matrices.push();
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(fin * 90));
			parts.box(-0.75f, 10, 9, 0.75f, 42, 22, FIN, light);
			matrices.pop();
		}
		body(matrices, consumers, THIRD, 10, 10, 170, light, overlay);
		fairing(parts, 10, 170, light);
	}

	/** A payload fairing tapering to a point. */
	private static void fairing(Parts parts, float radius, float bottom, int light) {
		parts.box(-radius, bottom, -radius, radius, bottom + 28, radius, CAPSULE, light);
		parts.box(-radius * 0.75f, bottom + 28, -radius * 0.75f, radius * 0.75f, bottom + 42, radius * 0.75f, CAPSULE, light);
		parts.box(-radius * 0.45f, bottom + 42, -radius * 0.45f, radius * 0.45f, bottom + 52, radius * 0.45f, CAPSULE, light);
		parts.box(-radius * 0.18f, bottom + 52, -radius * 0.18f, radius * 0.18f, bottom + 58, radius * 0.18f, CAPSULE, light);
	}

	/** A big stage: a square-section body with its own painted texture on every side. */
	private static void body(MatrixStack matrices, VertexConsumerProvider consumers, Identifier texture, float radius, float bottom, float top,
			int light, int overlay) {
		Boxes.box(matrices, consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texture)), -radius, bottom, -radius, radius, top, radius,
				0, 1, light, overlay);
	}

	/**
	 * The small parts, which all share one strip texture. The buffer is fetched for every box: the stages use other
	 * textures, and an immediate-mode provider hands the same builder to whichever layer asked last.
	 */
	private record Parts(MatrixStack matrices, VertexConsumerProvider consumers, int overlay) {
		void box(float x0, float y0, float z0, float x1, float y1, float z1, int tile, int light) {
			VertexConsumer buffer = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(PARTS));
			Boxes.box(matrices, buffer, x0, y0, z0, x1, y1, z1, tile, TILES, light, overlay);
		}
	}

	@Override
	public Identifier getTexture(RocketEntity rocket) {
		return PARTS;
	}
}
