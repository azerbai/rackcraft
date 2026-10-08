package dev.rackcraft.client.render;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.entity.MaintenanceDroneEntity;
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

/**
 * A Maintenance Drone: an orange body on four booms, a rotor spinning at the end of each, and a camera and gripper
 * underneath. It bobs as it hovers and leans into its direction of travel. Units are pixels, the body at the origin.
 */
public final class DroneRenderer extends EntityRenderer<MaintenanceDroneEntity> {
	private static final Identifier TEXTURE = Rackcraft.id("textures/entity/maintenance_drone.png");
	private static final int TILES = 4;

	public DroneRenderer(EntityRendererFactory.Context context) {
		super(context);
		shadowRadius = 0.3f;
		shadowOpacity = 0.5f;
	}

	@Override
	public void render(MaintenanceDroneEntity drone, float yaw, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		float age = drone.age + tickDelta;
		float speed = (float) drone.getVelocity().horizontalLength();
		VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(TEXTURE));
		int overlay = OverlayTexture.DEFAULT_UV;
		matrices.push();
		matrices.translate(0, 0.18 + MathHelper.sin(age * 0.15f) * 0.03, 0);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-MathHelper.lerp(tickDelta, drone.prevYaw, drone.getYaw())));
		// Lean forward into the flight, up to about 15 degrees at full speed; wobble a little while working.
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(Math.min(15, speed * 35)
				+ (drone.working() ? MathHelper.sin(age * 0.6f) * 3 : 0)));
		matrices.scale(1 / 16f, 1 / 16f, 1 / 16f);
		Boxes.box(matrices, buffer, -3, -1.5f, -3, 3, 1.5f, 3, 0, TILES, light, overlay);
		Boxes.box(matrices, buffer, -1, -3, -1, 1, -1.5f, 1, 3, TILES, light, overlay);
		float grip = drone.working() ? 0.6f + 0.4f * MathHelper.sin(age * 0.5f) : 1;
		Boxes.box(matrices, buffer, -grip - 0.4f, -4.5f, -0.4f, -grip + 0.4f, -3, 0.4f, 1, TILES, light, overlay);
		Boxes.box(matrices, buffer, grip - 0.4f, -4.5f, -0.4f, grip + 0.4f, -3, 0.4f, 1, TILES, light, overlay);
		for (int boom = 0; boom < 4; boom++) {
			matrices.push();
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(45 + boom * 90));
			Boxes.box(matrices, buffer, -0.5f, -0.5f, -7, 0.5f, 0.5f, -2.5f, 1, TILES, light, overlay);
			matrices.translate(0, 0, -7);
			Boxes.box(matrices, buffer, -0.75f, -0.5f, -0.75f, 0.75f, 1.6f, 0.75f, 1, TILES, light, overlay);
			matrices.translate(0, 1.7f, 0);
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(age * 75 * (boom % 2 == 0 ? 1 : -1)));
			Boxes.box(matrices, buffer, -3.5f, 0, -3.5f, 3.5f, 0.15f, 3.5f, 2, TILES, light, overlay);
			matrices.pop();
		}
		matrices.pop();
		super.render(drone, yaw, tickDelta, matrices, vertexConsumers, light);
	}

	@Override
	public Identifier getTexture(MaintenanceDroneEntity drone) {
		return TEXTURE;
	}
}
