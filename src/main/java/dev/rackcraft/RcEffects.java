package dev.rackcraft;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

/**
 * Smog's own status effects. Dizziness does nothing on the server: the client sways the camera gently
 * (a lighter cousin of Nausea, see the client's DizzyView). Smoker's Cough makes whoever has it cough every
 * so often: a hacking sound, a puff of smoke, a little lost stamina, and no sprinting through it.
 */
public final class RcEffects {
	public static final StatusEffect DIZZY = new StatusEffect(StatusEffectCategory.HARMFUL, 0x8C8466) {};
	public static final StatusEffect COUGHING = new StatusEffect(StatusEffectCategory.HARMFUL, 0x5E5A52) {
		@Override
		public boolean canApplyUpdateEffect(int duration, int amplifier) {
			return duration % 20 == 0;
		}

		@Override
		public void applyUpdateEffect(LivingEntity entity, int amplifier) {
			if (!(entity.getWorld() instanceof ServerWorld world)) return;
			// Roughly one cough every three seconds, more often when it's bad.
			if (world.random.nextInt(amplifier > 0 ? 2 : 3) != 0) return;
			cough(world, entity);
		}
	};

	private RcEffects() {}

	public static void register() {
		Registry.register(Registries.STATUS_EFFECT, Rackcraft.id("dizzy"), DIZZY);
		Registry.register(Registries.STATUS_EFFECT, Rackcraft.id("coughing"), COUGHING);
	}

	public static void cough(ServerWorld world, LivingEntity entity) {
		float pitch = (entity.isBaby() ? 1.1f : 0.55f) + world.random.nextFloat() * 0.15f;
		world.playSound(null, entity.getX(), entity.getEyeY(), entity.getZ(), SoundEvents.ENTITY_PANDA_SNEEZE,
				entity instanceof PlayerEntity ? SoundCategory.PLAYERS : SoundCategory.NEUTRAL, 0.6f, pitch);
		Vec3d mouth = entity.getEyePos().add(entity.getRotationVector().multiply(0.4)).subtract(0, 0.15, 0);
		world.spawnParticles(ParticleTypes.SMOKE, mouth.x, mouth.y, mouth.z, 6, 0.08, 0.05, 0.08, 0.02);
		if (entity instanceof PlayerEntity player) {
			player.addExhaustion(0.3f);
			player.setSprinting(false);
		}
	}
}
