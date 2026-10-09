package dev.rackcraft.world;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * Draws Pylon spans as a sagging line of grey sparks, once a second, for players within sight of one. Server-side
 * particles, so there is nothing to render on the client.
 */
public final class PylonLines {
	private static final DustParticleEffect LINE = new DustParticleEffect(new Vector3f(0.78f, 0.82f, 0.88f), 0.8f);
	private static final double VIEW_DISTANCE = 72;

	private PylonLines() {}

	public static void register() {
		ServerTickEvents.END_WORLD_TICK.register(world -> {
			if (world.getTime() % 20 != 0 || world.getPlayers().isEmpty()) return;
			for (PylonLinks.Span span : PylonLinks.get(world).spans()) draw(world, span);
		});
	}

	private static void draw(ServerWorld world, PylonLinks.Span span) {
		Vec3d from = Vec3d.ofCenter(span.a()).add(0, 0.45, 0);
		Vec3d to = Vec3d.ofCenter(span.b()).add(0, 0.45, 0);
		Vec3d middle = from.lerp(to, 0.5);
		boolean watched = false;
		for (var player : world.getPlayers()) {
			if (player.squaredDistanceTo(middle) < (VIEW_DISTANCE + span.length() / 2) * (VIEW_DISTANCE + span.length() / 2)) {
				watched = true;
				break;
			}
		}
		if (!watched || !world.isChunkLoaded(span.a()) || !world.isChunkLoaded(span.b())) return;
		double length = from.distanceTo(to);
		int dots = (int) Math.max(2, length / 1.5);
		for (int dot = 0; dot <= dots; dot++) {
			double t = dot / (double) dots;
			Vec3d at = from.lerp(to, t).add(0, -0.06 * length * 4 * t * (1 - t) / 4, 0);
			world.spawnParticles(LINE, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
	}
}
