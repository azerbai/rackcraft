package dev.rackcraft.client;

import dev.rackcraft.entity.RocketEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * The ground shaking under a launch: the camera jitters while a rocket near you lights and climbs, hardest for a
 * Saturn V, strongest close to the pad and in the first seconds after liftoff, and fading as it climbs away. It
 * follows the Distortion Effects accessibility slider like any other screen shake.
 */
public final class RocketShake {
	private static final double REACH = 192;
	private static float strength;
	private static float previousStrength;

	private RocketShake() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			previousStrength = strength;
			float target = 0;
			if (client.player != null && client.world != null) {
				for (RocketEntity rocket : client.world.getEntitiesByClass(RocketEntity.class, new Box(client.player.getBlockPos()).expand(REACH, 512, REACH),
						rocket -> true)) {
					int flight = rocket.flightTicks();
					if (flight < -30) continue;
					double distance = Math.sqrt(client.player.squaredDistanceTo(rocket.getX(), client.player.getY(), rocket.getZ()));
					double near = Math.max(0, 1 - distance / REACH);
					double size = rocket.stages() == 3 ? 1.0 : rocket.stages() == 2 ? 0.55 : 0.3;
					// Builds through ignition, peaks as it clears the pad, then dies away over about ten seconds.
					double phase = flight < 0 ? (flight + 30) / 30.0 * 0.5 : flight < 40 ? 1 : Math.max(0, 1 - (flight - 40) / 200.0);
					target = Math.max(target, (float) (near * near * size * phase));
				}
			}
			strength += MathHelper.clamp(target - strength, -0.05f, 0.1f);
		});
	}

	/** Called from the camera transform: a fast, small, random-feeling jolt. */
	public static void shake(MatrixStack matrices, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		float amount = MathHelper.lerp(tickDelta, previousStrength, strength) * client.options.getDistortionEffectScale().getValue().floatValue();
		if (amount <= 0.001f || client.player == null) return;
		float time = client.player.age + tickDelta;
		float roll = (MathHelper.sin(time * 2.3f) + MathHelper.sin(time * 3.7f + 1.1f) * 0.6f) * 0.9f * amount;
		float nod = (MathHelper.sin(time * 2.9f + 0.4f) + MathHelper.sin(time * 4.3f) * 0.5f) * 0.7f * amount;
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll));
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(nod));
	}
}
