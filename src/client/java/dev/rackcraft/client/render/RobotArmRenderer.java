package dev.rackcraft.client.render;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.RobotArmBlock;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/**
 * Draws the moving parts of machines: a Wind Tower's rotor (see {@link RotorRenderer}), and a robot arm: a turret on the pedestal, an upper arm and forearm solved with two-link
 * inverse kinematics so the wrist reaches the middle of the belt in front, and a tool hanging from the wrist. Idle,
 * the arm folds up over its pedestal; working (the block is lit), it reaches over the belt and does its job: the
 * welder sweeps sideways throwing sparks, the riveter hammers, the assembler dips and closes its gripper.
 *
 * Coordinates below are in pixels, in the arm's own frame: it faces north (-z), the shoulder sits on top of the column.
 */
public final class RobotArmRenderer implements BlockEntityRenderer<MachineBlockEntity> {
	private static final float SHOULDER_Y = 11.5f;
	private static final float UPPER = 10;
	private static final float FORE = 10;
	private static final int TILES = 4;
	private static final int SEGMENT = 0;
	private static final int JOINT = 1;
	private static final int TOOL = 2;
	private static final int ACCENT = 3;
	/** How far each arm has moved toward its working pose (0 folded, 1 reaching), and the last tick sparks flew. */
	private final Map<MachineBlockEntity, float[]> poses = new WeakHashMap<>();

	public RobotArmRenderer(BlockEntityRendererFactory.Context context) {}

	@Override
	public boolean rendersOutsideBoundingBox(MachineBlockEntity machine) {
		return machine.getCachedState().getBlock() instanceof RobotArmBlock || isNacelle(machine);
	}

	/** Wind Tower rotors are big and high up: draw them from much further away than the arms. */
	@Override
	public boolean isInRenderDistance(MachineBlockEntity machine, net.minecraft.util.math.Vec3d camera) {
		double range = isNacelle(machine) ? 256 : getRenderDistance();
		return net.minecraft.util.math.Vec3d.ofCenter(machine.getPos()).isInRange(camera, range);
	}

	private static boolean isNacelle(MachineBlockEntity machine) {
		return machine.getCachedState().isOf(dev.rackcraft.RcBlocks.get("wind_nacelle"));
	}

	@Override
	public void render(MachineBlockEntity machine, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
			int light, int overlay) {
		if (isNacelle(machine)) {
			RotorRenderer.render(machine, tickDelta, matrices, vertexConsumers, light, overlay);
			return;
		}
		if (!(machine.getCachedState().getBlock() instanceof RobotArmBlock) || machine.getWorld() == null) return;
		String id = Registries.BLOCK.getId(machine.getCachedState().getBlock()).getPath();
		World world = machine.getWorld();
		boolean working = machine.getCachedState().get(MachineBlock.LIT);
		Direction facing = machine.getCachedState().get(MachineBlock.FACING);
		float time = world.getTime() + tickDelta;

		float[] pose = poses.computeIfAbsent(machine, ignored -> new float[] {working ? 1 : 0, time, -1});
		float elapsed = Math.max(0, Math.min(5, time - pose[1]));
		pose[1] = time;
		pose[0] = working ? Math.min(1, pose[0] + elapsed * 0.08f) : Math.max(0, pose[0] - elapsed * 0.05f);
		float reach = smooth(pose[0]);

		// Where the wrist wants to be, relative to the shoulder: forward along the arm's facing, sideways, and up.
		float forward = 16;
		float side = 0;
		float up = -1.5f;
		float grip = 1.6f;
		if (working) {
			switch (id) {
				case "welding_arm" -> {
					side = MathHelper.sin(time * 0.22f) * 3.5f;
					forward += MathHelper.sin(time * 0.37f) * 1.2f;
				}
				case "riveting_arm" -> up += Math.abs(MathHelper.sin(time * 0.9f)) * 2.5f;
				default -> {
					float dip = 0.5f + 0.5f * MathHelper.sin(time * 0.18f);
					up += dip * 4;
					grip = 0.6f + dip * 1.4f;
				}
			}
		}
		forward = MathHelper.lerp(reach, 2.5f, forward);
		up = MathHelper.lerp(reach, 13, up);
		side *= reach;
		float turretYaw = (float) Math.atan2(side, forward);
		float distance = (float) Math.hypot(forward, side);

		// Two-link inverse kinematics in the arm's vertical plane, elbow up.
		float span = MathHelper.clamp((float) Math.hypot(distance, up), Math.abs(UPPER - FORE) + 0.01f, UPPER + FORE - 0.01f);
		float elbow = (float) Math.acos(MathHelper.clamp((span * span - UPPER * UPPER - FORE * FORE) / (2 * UPPER * FORE), -1, 1));
		float shoulder = (float) (Math.atan2(up, distance) + Math.atan2(FORE * Math.sin(elbow), UPPER + FORE * Math.cos(elbow)));
		float foreAngle = shoulder - elbow;

		VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texture(id)));
		matrices.push();
		matrices.translate(0.5, 0, 0.5);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yawOf(facing)));
		matrices.scale(1 / 16f, 1 / 16f, 1 / 16f);
		matrices.translate(0, SHOULDER_Y, 0);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotation(-turretYaw));
		Boxes.box(matrices, buffer, -3, -1.5f, -3, 3, 0, 3, ACCENT, TILES, light, overlay);
		Boxes.box(matrices, buffer, -2, 0, -2, 2, 2.5f, 2, JOINT, TILES, light, overlay);
		matrices.translate(0, 1.2f, 0);
		matrices.push();
		matrices.multiply(RotationAxis.POSITIVE_X.rotation(shoulder));
		Boxes.box(matrices, buffer, -1.5f, -1.5f, -UPPER, 1.5f, 1.5f, 0, SEGMENT, TILES, light, overlay);
		matrices.translate(0, 0, -UPPER);
		Boxes.box(matrices, buffer, -2, -2, -2, 2, 2, 2, JOINT, TILES, light, overlay);
		matrices.multiply(RotationAxis.POSITIVE_X.rotation(foreAngle - shoulder));
		Boxes.box(matrices, buffer, -1.25f, -1.25f, -FORE, 1.25f, 1.25f, 0, SEGMENT, TILES, light, overlay);
		matrices.translate(0, 0, -FORE);
		// Back to level at the wrist, so the tool always hangs straight down at the belt.
		matrices.multiply(RotationAxis.POSITIVE_X.rotation(-foreAngle));
		Boxes.box(matrices, buffer, -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, JOINT, TILES, light, overlay);
		switch (id) {
			case "welding_arm" -> Boxes.box(matrices, buffer, -0.75f, -5, -0.75f, 0.75f, -1.5f, 0.75f, TOOL, TILES, light, overlay);
			case "riveting_arm" -> {
				Boxes.box(matrices, buffer, -1.5f, -3.5f, -1.5f, 1.5f, -1.5f, 1.5f, TOOL, TILES, light, overlay);
				Boxes.box(matrices, buffer, -0.5f, -5, -0.5f, 0.5f, -3.5f, 0.5f, TOOL, TILES, light, overlay);
			}
			default -> {
				Boxes.box(matrices, buffer, -2, -2.5f, -1, 2, -1.5f, 1, TOOL, TILES, light, overlay);
				Boxes.box(matrices, buffer, -grip - 0.5f, -5, -0.6f, -grip + 0.5f, -2.5f, 0.6f, TOOL, TILES, light, overlay);
				Boxes.box(matrices, buffer, grip - 0.5f, -5, -0.6f, grip + 0.5f, -2.5f, 0.6f, TOOL, TILES, light, overlay);
			}
		}
		matrices.pop();
		matrices.pop();

		if (working && reach > 0.85f && id.equals("welding_arm") && (long) time != (long) pose[2]) {
			pose[2] = (long) time;
			sparks(world, machine.getPos(), facing, side, world.random.nextFloat());
		}
	}

	/** Sparks off the torch tip, which is over the middle of the belt, shifted by the sweep. */
	private static void sparks(World world, BlockPos pos, Direction facing, float side, float roll) {
		Direction right = facing.rotateYClockwise();
		double x = pos.getX() + 0.5 + facing.getOffsetX() + right.getOffsetX() * side / 16;
		double z = pos.getZ() + 0.5 + facing.getOffsetZ() + right.getOffsetZ() * side / 16;
		double y = pos.getY() + 0.42;
		for (int index = 0; index < 2; index++) {
			world.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, (world.random.nextDouble() - 0.5) * 0.2, 0.08,
					(world.random.nextDouble() - 0.5) * 0.2);
		}
		if (roll < 0.2f) world.addParticle(ParticleTypes.SMOKE, x, y + 0.05, z, 0, 0.03, 0);
		if (roll < 0.1f) world.addParticle(ParticleTypes.LAVA, x, y, z, 0, 0, 0);
	}

	/** Rotation that turns the arm's north-facing frame to face the way the block does. */
	private static float yawOf(Direction facing) {
		return switch (facing) {
			case EAST -> -90;
			case SOUTH -> 180;
			case WEST -> 90;
			default -> 0;
		};
	}

	private static float smooth(float value) {
		return value * value * (3 - 2 * value);
	}

	private static Identifier texture(String id) {
		return Rackcraft.id("textures/entity/" + id + ".png");
	}
}
