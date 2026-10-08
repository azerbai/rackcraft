package dev.rackcraft.client.render;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;

/**
 * A Wind Tower's rotor: a hub cone and three long white blades with red tips, turning in front of the nacelle. It
 * spins up while the tower generates (the block is lit) and coasts down when it stops. Pixels, nacelle-centred.
 */
final class RotorRenderer {
	private static final Identifier TEXTURE = Rackcraft.id("textures/entity/wind_rotor.png");
	private static final int TILES = 3;
	// Inside the 5x5 the tower keeps clear in front of it.
	private static final float BLADE = 38;
	/** Each rotor's angle and speed, so it spins up and coasts down smoothly instead of snapping. */
	private static final Map<MachineBlockEntity, float[]> SPIN = new WeakHashMap<>();

	private RotorRenderer() {}

	static void render(MachineBlockEntity nacelle, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light,
			int overlay) {
		if (nacelle.getWorld() == null) return;
		boolean running = nacelle.getCachedState().get(MachineBlock.LIT);
		float time = nacelle.getWorld().getTime() + tickDelta;
		float[] spin = SPIN.computeIfAbsent(nacelle, ignored -> new float[] {0, running ? 4 : 0, time});
		float elapsed = Math.max(0, Math.min(5, time - spin[2]));
		spin[2] = time;
		spin[1] = running ? Math.min(4, spin[1] + elapsed * 0.04f) : Math.max(0, spin[1] - elapsed * 0.02f);
		spin[0] = (spin[0] + spin[1] * elapsed) % 360;

		Direction facing = nacelle.getCachedState().get(MachineBlock.FACING);
		VertexConsumer buffer = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
		matrices.push();
		matrices.translate(0.5, 0.5, 0.5);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation() + 180));
		matrices.scale(1 / 16f, 1 / 16f, 1 / 16f);
		// The rotor sits just proud of the nacelle's front face.
		matrices.translate(0, 0, -10);
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(spin[0]));
		Boxes.box(matrices, buffer, -3, -3, -3, 3, 3, 2, 2, TILES, light, overlay);
		Boxes.box(matrices, buffer, -1.5f, -1.5f, -5, 1.5f, 1.5f, -3, 2, TILES, light, overlay);
		for (int blade = 0; blade < 3; blade++) {
			matrices.push();
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(blade * 120));
			Boxes.box(matrices, buffer, -2.5f, 2, -0.75f, 2.5f, 18, 0.75f, 0, TILES, light, overlay);
			Boxes.box(matrices, buffer, -1.75f, 18, -0.5f, 1.75f, BLADE - 6, 0.5f, 0, TILES, light, overlay);
			Boxes.box(matrices, buffer, -1.25f, BLADE - 6, -0.5f, 1.25f, BLADE, 0.5f, 1, TILES, light, overlay);
			matrices.pop();
		}
		matrices.pop();
	}
}
