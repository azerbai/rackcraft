package dev.rackcraft.client.render;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.entity.MaglevCarEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** A Mag-Lev car: a low teal pod with a windscreen, hovering over a dark skid. Block units, the car's centre at the origin. */
public final class MaglevCarRenderer extends EntityRenderer<MaglevCarEntity> {
	private static final Identifier TEXTURE = Rackcraft.id("textures/entity/maglev_car.png");
	private static final int TILES = 3;

	public MaglevCarRenderer(EntityRendererFactory.Context context) {
		super(context);
		shadowRadius = 0.5f;
		shadowOpacity = 0.4f;
	}

	@Override
	public void render(MaglevCarEntity car, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
		VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(TEXTURE));
		int overlay = OverlayTexture.DEFAULT_UV;
		matrices.push();
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-MathHelper.lerp(tickDelta, car.prevYaw, car.getYaw())));
		// The car faces its +z after the yaw above; the skid, the body, the windscreen and a nose.
		Boxes.box(matrices, buffer, -0.42f, 0.0f, -0.85f, 0.42f, 0.12f, 0.85f, 2, TILES, light, overlay);
		Boxes.box(matrices, buffer, -0.45f, 0.12f, -0.9f, 0.45f, 0.42f, 0.9f, 0, TILES, light, overlay);
		Boxes.box(matrices, buffer, -0.36f, 0.42f, -0.2f, 0.36f, 0.62f, 0.7f, 1, TILES, light, overlay);
		Boxes.box(matrices, buffer, -0.3f, 0.2f, 0.9f, 0.3f, 0.38f, 1.0f, 0, TILES, light, overlay);
		matrices.pop();
	}

	@Override
	public Identifier getTexture(MaglevCarEntity car) {
		return TEXTURE;
	}
}
