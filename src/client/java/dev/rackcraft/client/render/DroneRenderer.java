package dev.rackcraft.client.render;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.entity.ConstructionDroneEntity;
import dev.rackcraft.entity.MaintenanceDroneEntity;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
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
 * A drone: a body on four booms, a rotor spinning at the end of each, and a camera and gripper underneath. It bobs as
 * it hovers and leans into its direction of travel. Maintenance Drones are small and orange; Construction and
 * Terraforming Drones half as big again, striped, with the block they are carrying hanging underneath. Units are
 * pixels, the body at the origin.
 */
public final class DroneRenderer<T extends Entity> extends EntityRenderer<T> {
	private static final Identifier MAINTENANCE = Rackcraft.id("textures/entity/maintenance_drone.png");
	private static final Identifier CONSTRUCTION = Rackcraft.id("textures/entity/construction_drone.png");
	private static final Identifier TERRAFORMING = Rackcraft.id("textures/entity/terraforming_drone.png");
	private static final Identifier TANKER = Rackcraft.id("textures/entity/tanker_drone.png");
	private static final int TILES = 4;
	private final Function<T, Identifier> texture;
	private final Predicate<T> working;
	private final Function<T, ItemStack> carried;
	private final float scale;

	private DroneRenderer(EntityRendererFactory.Context context, Function<T, Identifier> texture, Predicate<T> working,
			Function<T, ItemStack> carried, float scale) {
		super(context);
		this.texture = texture;
		this.working = working;
		this.carried = carried;
		this.scale = scale;
		shadowRadius = 0.3f * scale;
		shadowOpacity = 0.5f;
	}

	public static DroneRenderer<MaintenanceDroneEntity> maintenance(EntityRendererFactory.Context context) {
		return new DroneRenderer<>(context, drone -> MAINTENANCE, MaintenanceDroneEntity::working, drone -> ItemStack.EMPTY, 1);
	}

	public static DroneRenderer<ConstructionDroneEntity> construction(EntityRendererFactory.Context context) {
		return new DroneRenderer<>(context, drone -> drone.terraformer() ? TERRAFORMING : CONSTRUCTION, ConstructionDroneEntity::working,
				ConstructionDroneEntity::shown, 1.5f);
	}

	public static DroneRenderer<dev.rackcraft.entity.TankerDroneEntity> tanker(EntityRendererFactory.Context context) {
		return new DroneRenderer<>(context, drone -> TANKER, dev.rackcraft.entity.TankerDroneEntity::working, drone -> ItemStack.EMPTY, 1.75f);
	}

	@Override
	public void render(T drone, float yaw, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		float age = drone.age + tickDelta;
		float speed = (float) drone.getVelocity().horizontalLength();
		boolean busy = working.test(drone);
		VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(texture.apply(drone)));
		int overlay = OverlayTexture.DEFAULT_UV;
		matrices.push();
		matrices.translate(0, 0.18 * scale + MathHelper.sin(age * 0.15f) * 0.03, 0);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-MathHelper.lerp(tickDelta, drone.prevYaw, drone.getYaw())));
		// Lean forward into the flight, up to about 15 degrees at full speed; wobble a little while working.
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(Math.min(15, speed * 35)
				+ (busy ? MathHelper.sin(age * 0.6f) * 3 : 0)));
		ItemStack cargo = carried.apply(drone);
		if (cargo.getItem() instanceof BlockItem block) {
			// The load hangs under the gripper, a little under half a block across.
			matrices.push();
			matrices.translate(-0.2, -0.32 * scale - 0.4, -0.2);
			matrices.scale(0.4f, 0.4f, 0.4f);
			MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(block.getBlock().getDefaultState(), matrices,
					vertexConsumers, light, overlay);
			matrices.pop();
			buffer = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(texture.apply(drone)));
		}
		matrices.scale(scale / 16f, scale / 16f, scale / 16f);
		Boxes.box(matrices, buffer, -3, -1.5f, -3, 3, 1.5f, 3, 0, TILES, light, overlay);
		Boxes.box(matrices, buffer, -1, -3, -1, 1, -1.5f, 1, 3, TILES, light, overlay);
		float grip = busy ? 0.6f + 0.4f * MathHelper.sin(age * 0.5f) : 1;
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
	public Identifier getTexture(T drone) {
		return texture.apply(drone);
	}
}
