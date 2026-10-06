package dev.rackcraft.client;

import dev.rackcraft.RcEffects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * Smog dizziness on the client: a slow, gentle sway of the view and a brownish haze over the screen. It is
 * deliberately much lighter than vanilla Nausea's spinning warp, and fades in and out instead of snapping.
 * Respects the Distortion Effects accessibility slider.
 */
public final class DizzyView {
	private static float strength;
	private static float previousStrength;

	private DizzyView() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			previousStrength = strength;
			float target = 0;
			if (client.player != null) {
				StatusEffectInstance dizzy = client.player.getStatusEffect(RcEffects.DIZZY);
				if (dizzy != null) target = dizzy.getAmplifier() > 0 ? 1 : 0.5f;
			}
			strength += MathHelper.clamp(target - strength, -0.02f, 0.02f);
		});
		HudRenderCallback.EVENT.register((context, tickDelta) -> {
			float haze = strength(tickDelta);
			if (haze <= 0) return;
			int alpha = Math.round(haze * 46);
			context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), alpha << 24 | 0x6B5E3E);
		});
	}

	private static float strength(float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		float scale = client.options.getDistortionEffectScale().getValue().floatValue();
		return MathHelper.lerp(tickDelta, previousStrength, strength) * scale;
	}

	/** Called from the camera transform: a few degrees of slow roll and nod, two waves out of step. */
	public static void sway(MatrixStack matrices, float tickDelta) {
		float amount = strength(tickDelta);
		MinecraftClient client = MinecraftClient.getInstance();
		if (amount <= 0 || client.player == null) return;
		float time = client.player.age + tickDelta;
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.sin(time * 0.045f) * 2.2f * amount));
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.sin(time * 0.031f + 1.3f) * 1.2f * amount));
	}
}
