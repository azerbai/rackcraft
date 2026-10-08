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
 * A rocket: an engine bell, one to three white stages with dark interstages, fins on the first stage, and a payload
 * fairing tapering to a point. Pixels, with the bottom of the engine bell at the entity's feet.
 */
public final class RocketRenderer extends EntityRenderer<RocketEntity> {
	private static final Identifier TEXTURE = Rackcraft.id("textures/entity/rocket.png");
	private static final int TILES = 5;
	private static final int SKIN = 0;
	private static final int INTERSTAGE = 1;
	private static final int ENGINE = 2;
	private static final int FAIRING = 3;
	private static final int FIN = 4;
	private static final float BELL = 6;
	private static final float STAGE = 28;
	private static final float GAP = 4;

	public RocketRenderer(EntityRendererFactory.Context context) {
		super(context);
		shadowRadius = 0.8f;
	}

	@Override
	public void render(RocketEntity rocket, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
			int light) {
		VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
		int overlay = OverlayTexture.DEFAULT_UV;
		int stages = Math.max(1, Math.min(3, rocket.stages()));
		matrices.push();
		matrices.scale(1 / 16f, 1 / 16f, 1 / 16f);
		// Lit engines glow at full brightness, whatever the light around the pad.
		int engineLight = rocket.flightTicks() >= 0 ? LightmapTextureManager.MAX_LIGHT_COORDINATE : light;
		Boxes.box(matrices, buffer, -4, 0, -4, 4, BELL, 4, ENGINE, TILES, engineLight, overlay);
		float top = BELL;
		for (int stage = 0; stage < stages; stage++) {
			Boxes.box(matrices, buffer, -6, top, -6, 6, top + STAGE, 6, SKIN, TILES, light, overlay);
			top += STAGE;
			if (stage < stages - 1) {
				Boxes.box(matrices, buffer, -6, top, -6, 6, top + GAP, 6, INTERSTAGE, TILES, light, overlay);
				top += GAP;
			}
		}
		for (int fin = 0; fin < 4; fin++) {
			matrices.push();
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45 + fin * 90));
			Boxes.box(matrices, buffer, -0.5f, BELL - 2, 5, 0.5f, BELL + 10, 10, FIN, TILES, light, overlay);
			matrices.pop();
		}
		// A Dyson Mirror's fairing is wider: the petals fold up inside it.
		float width = rocket.payload().equals("dyson_mirror") ? 7.5f : 6;
		Boxes.box(matrices, buffer, -width, top, -width, width, top + 8, width, FAIRING, TILES, light, overlay);
		Boxes.box(matrices, buffer, -width * 0.7f, top + 8, -width * 0.7f, width * 0.7f, top + 14, width * 0.7f, FAIRING, TILES, light, overlay);
		Boxes.box(matrices, buffer, -width * 0.35f, top + 14, -width * 0.35f, width * 0.35f, top + 18, width * 0.35f, FAIRING, TILES, light, overlay);
		matrices.pop();
		super.render(rocket, yaw, tickDelta, matrices, vertexConsumers, light);
	}

	@Override
	public Identifier getTexture(RocketEntity rocket) {
		return TEXTURE;
	}
}
