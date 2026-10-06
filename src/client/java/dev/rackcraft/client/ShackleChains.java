package dev.rackcraft.client;

import dev.rackcraft.compute.TrainingStations;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Draws the chain from every shackled librarian's wrists to their Scriptorium Desk, sagging like a lead,
 * using the vanilla chain texture as two crossed ribbons (the way the Chain block is built).
 */
public final class ShackleChains {
	private static final Identifier CHAIN = new Identifier("minecraft", "textures/block/chain.png");
	private static final RenderLayer LAYER = RenderLayer.getEntityCutoutNoCull(CHAIN);
	/** Half the ribbon width, and the length one repeat of the texture covers. */
	private static final float HALF_WIDTH = 0.06f;
	private static final float TEXTURE_LENGTH = 0.65f;
	private static final int SEGMENTS = 16;

	private record Chain(int villager, BlockPos desk, Direction facing) {}

	private static List<Chain> chains = List.of();
	private static long receivedTick;

	private ShackleChains() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(TrainingStations.SHACKLES, (client, handler, buf, responseSender) -> {
			List<Chain> received = new ArrayList<>();
			for (int count = buf.readVarInt(); count > 0; count--) {
				received.add(new Chain(buf.readVarInt(), buf.readBlockPos(), buf.readEnumConstant(Direction.class)));
			}
			client.execute(() -> {
				chains = received;
				receivedTick = client.world == null ? 0 : client.world.getTime();
			});
		});
		WorldRenderEvents.AFTER_ENTITIES.register(ShackleChains::render);
	}

	private static void render(WorldRenderContext context) {
		ClientWorld world = context.world();
		if (chains.isEmpty() || world == null || context.consumers() == null) return;
		// The server refreshes chains twice a second; stale ones (left the area, server gone) disappear.
		if (world.getTime() - receivedTick > 60) return;
		Vec3d camera = context.camera().getPos();
		MatrixStack matrices = context.matrixStack();
		VertexConsumerProvider consumers = context.consumers();
		float tickDelta = context.tickDelta();
		boolean drew = false;
		for (Chain chain : chains) {
			Entity entity = world.getEntityById(chain.villager());
			if (!(entity instanceof VillagerEntity villager) || villager.isRemoved()) continue;
			// Wrists: the crossed arms in front of the villager's chest.
			float yaw = MathHelper.lerp(tickDelta, villager.prevBodyYaw, villager.bodyYaw) * MathHelper.RADIANS_PER_DEGREE;
			Vec3d body = villager.getLerpedPos(tickDelta);
			Vec3d wrists = body.add(-MathHelper.sin(yaw) * 0.32, villager.isBaby() ? 0.5 : 1.0, MathHelper.cos(yaw) * 0.32);
			// The desk end is bolted to the middle of its front face.
			Vec3d desk = Vec3d.ofCenter(chain.desk()).add(chain.facing().getOffsetX() * 0.51, -0.05, chain.facing().getOffsetZ() * 0.51);
			if (wrists.squaredDistanceTo(desk) > 16 * 16) continue;
			matrices.push();
			matrices.translate(desk.x - camera.x, desk.y - camera.y, desk.z - camera.z);
			drawChain(matrices, consumers.getBuffer(LAYER), wrists.subtract(desk), light(world, chain.desk().offset(chain.facing())),
					light(world, villager.getBlockPos().up()));
			matrices.pop();
			drew = true;
		}
		if (drew && consumers instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(LAYER);
	}

	private static int light(ClientWorld world, BlockPos pos) {
		return LightmapTextureManager.pack(world.getLightLevel(LightType.BLOCK, pos), world.getLightLevel(LightType.SKY, pos));
	}

	/** A sagging chain from the origin to {@code end}: two crossed ribbons, one segment at a time. */
	private static void drawChain(MatrixStack matrices, VertexConsumer buffer, Vec3d end, int startLight, int endLight) {
		Matrix4f position = matrices.peek().getPositionMatrix();
		Matrix3f normal = matrices.peek().getNormalMatrix();
		double length = end.length();
		double sag = 0.2 + 0.12 * length;
		Vec3d[] points = new Vec3d[SEGMENTS + 1];
		for (int index = 0; index <= SEGMENTS; index++) {
			double t = index / (double) SEGMENTS;
			points[index] = end.multiply(t).add(0, -sag * 4 * t * (1 - t), 0);
		}
		float travelled = 0;
		for (int index = 0; index < SEGMENTS; index++) {
			Vec3d from = points[index];
			Vec3d to = points[index + 1];
			Vec3d along = to.subtract(from);
			float segment = (float) along.length();
			if (segment < 1e-4) continue;
			Vec3d direction = along.multiply(1 / segment);
			// One ribbon lies sideways to the chain, the other across it, like the Chain block's two planes.
			Vec3d side = direction.crossProduct(new Vec3d(0, 1, 0));
			if (side.lengthSquared() < 1e-4) side = new Vec3d(1, 0, 0);
			side = side.normalize();
			Vec3d across = direction.crossProduct(side).normalize();
			float v0 = travelled / TEXTURE_LENGTH;
			float v1 = (travelled + segment) / TEXTURE_LENGTH;
			travelled += segment;
			int light0 = lerpLight(startLight, endLight, index / (float) SEGMENTS);
			int light1 = lerpLight(startLight, endLight, (index + 1) / (float) SEGMENTS);
			ribbon(buffer, position, normal, from, to, side, 0, 3 / 16f, v0, v1, light0, light1);
			ribbon(buffer, position, normal, from, to, across, 3 / 16f, 6 / 16f, v0, v1, light0, light1);
		}
	}

	private static void ribbon(VertexConsumer buffer, Matrix4f position, Matrix3f normal, Vec3d from, Vec3d to, Vec3d offset,
			float u0, float u1, float v0, float v1, int light0, int light1) {
		Vec3d half = offset.multiply(HALF_WIDTH);
		vertex(buffer, position, normal, from.subtract(half), u0, v0, light0);
		vertex(buffer, position, normal, from.add(half), u1, v0, light0);
		vertex(buffer, position, normal, to.add(half), u1, v1, light1);
		vertex(buffer, position, normal, to.subtract(half), u0, v1, light1);
	}

	private static void vertex(VertexConsumer buffer, Matrix4f position, Matrix3f normal, Vec3d point, float u, float v, int light) {
		buffer.vertex(position, (float) point.x, (float) point.y, (float) point.z).color(255, 255, 255, 255).texture(u, v)
				.overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normal, 0, 1, 0).next();
	}

	private static int lerpLight(int a, int b, float t) {
		int block = Math.round(MathHelper.lerp(t, LightmapTextureManager.getBlockLightCoordinates(a),
				LightmapTextureManager.getBlockLightCoordinates(b)));
		int sky = Math.round(MathHelper.lerp(t, LightmapTextureManager.getSkyLightCoordinates(a),
				LightmapTextureManager.getSkyLightCoordinates(b)));
		return LightmapTextureManager.pack(block, sky);
	}
}
