package dev.rackcraft.mixin.client;

import dev.rackcraft.client.DizzyView;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the smog sway where vanilla tilts the camera when you're hurt. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "tiltViewWhenHurt", at = @At("HEAD"))
	private void rackcraft$smogSway(MatrixStack matrices, float tickDelta, CallbackInfo info) {
		DizzyView.sway(matrices, tickDelta);
		dev.rackcraft.client.RocketShake.shake(matrices, tickDelta);
	}
}
