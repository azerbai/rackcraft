package dev.rackcraft;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * Rackcraft's own sounds, synthesised by tools/sounds.py. Each has a fixed reach, so the server sends a launch to
 * everyone in earshot of it rather than the usual 16 blocks per unit of volume.
 */
public final class RcSounds {
	public static SoundEvent ROCKET_IGNITION;
	public static SoundEvent ROCKET_LIFTOFF;
	public static SoundEvent ROCKET_THRUST;
	public static SoundEvent ROCKET_STAGING;
	public static SoundEvent ROCKET_EXPLOSION;
	public static SoundEvent ROCKET_DISTANT;
	public static SoundEvent COUNTDOWN_BEEP;
	public static SoundEvent COUNTDOWN_FINAL;

	private RcSounds() {}

	public static void register() {
		ROCKET_IGNITION = register("rocket.ignition", 192);
		ROCKET_LIFTOFF = register("rocket.liftoff", 384);
		ROCKET_THRUST = register("rocket.thrust", 384);
		ROCKET_STAGING = register("rocket.staging", 512);
		ROCKET_EXPLOSION = register("rocket.explosion", 512);
		ROCKET_DISTANT = register("rocket.distant", 16);
		COUNTDOWN_BEEP = register("rocket.beep", 16);
		COUNTDOWN_FINAL = register("rocket.beep_final", 16);
	}

	private static SoundEvent register(String name, float reach) {
		Identifier id = Rackcraft.id(name);
		return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id, reach));
	}
}
