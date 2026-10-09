package dev.rackcraft.world;

import dev.rackcraft.RcItems;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * The Grapple. Use it to fire a hook up to {@link #RANGE} blocks at a block; the server then pulls the player toward the
 * anchor, faster as it goes, until they arrive (or sneak to let go). Everything is server-side velocity, and the rope
 * is a line of grey sparks. Each shot burns a Battery Cell.
 */
public final class Grapples {
	public static final int RANGE = 40;
	public static final int MAX_TICKS = 80;
	public static final int COOLDOWN = 10;
	private static final DustParticleEffect ROPE = new DustParticleEffect(new Vector3f(0.7f, 0.72f, 0.75f), 0.7f);

	private record Pull(Vec3d anchor, int started) {}

	private static final Map<UUID, Pull> PULLS = new HashMap<>();
	private static final Map<UUID, Long> LAST = new HashMap<>();

	private Grapples() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (PULLS.isEmpty()) return;
			Iterator<Map.Entry<UUID, Pull>> iterator = PULLS.entrySet().iterator();
			while (iterator.hasNext()) {
				var entry = iterator.next();
				ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
				if (player == null || player.isRemoved() || !pull(player, entry.getValue())) iterator.remove();
			}
		});
	}

	public static boolean pulling(ServerPlayerEntity player) { return PULLS.containsKey(player.getUuid()); }

	/** Fires a hook. Returns what to tell the player, or null if it flew. */
	public static String fire(ServerPlayerEntity player) {
		long now = player.getWorld().getTime();
		if (now - LAST.getOrDefault(player.getUuid(), -100L) < COOLDOWN) return "";
		HitResult hit = player.raycast(RANGE, 1.0f, false);
		if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return "Nothing to hook within " + RANGE + " blocks";
		if (BuildStock.of(player).take(RcItems.ITEMS.get("battery_cell"), 1) < 1) return "Out of Battery Cells";
		LAST.put(player.getUuid(), now);
		Vec3d anchor = Vec3d.ofCenter(block.getBlockPos()).add(Vec3d.of(block.getSide().getVector()).multiply(0.5));
		PULLS.put(player.getUuid(), new Pull(anchor, player.age));
		player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_FISHING_BOBBER_THROW, SoundCategory.PLAYERS, 0.8f, 1.5f);
		return null;
	}

	/** One tick of a pull. Returns false when it is over. */
	private static boolean pull(ServerPlayerEntity player, Pull pull) {
		int age = player.age - pull.started();
		Vec3d toAnchor = pull.anchor().subtract(player.getEyePos());
		double distance = toAnchor.length();
		if (distance < 2.2 || age > MAX_TICKS || (player.isSneaking() && age > 4)) {
			// Arriving with speed still on: the fall at the end is cushioned.
			player.fallDistance = 0;
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 30, 0, false, false, false));
			return false;
		}
		double speed = Math.min(1.1, 0.35 + age * 0.06);
		player.setVelocity(toAnchor.normalize().multiply(speed));
		player.velocityModified = true;
		player.fallDistance = 0;
		if (player.age % 2 == 0 && player.getWorld() instanceof ServerWorld world) {
			Vec3d from = player.getPos().add(0, 1.2, 0);
			int dots = (int) Math.max(2, distance / 1.2);
			for (int dot = 0; dot <= dots; dot++) {
				Vec3d at = from.lerp(pull.anchor(), dot / (double) dots);
				world.spawnParticles(ROPE, at.x, at.y, at.z, 1, 0, 0, 0, 0);
			}
		}
		return true;
	}

	/** Test hook: runs the pull for a player directly. */
	public static boolean step(ServerPlayerEntity player) {
		Pull pull = PULLS.get(player.getUuid());
		if (pull == null) return false;
		boolean alive = pull(player, pull);
		if (!alive) PULLS.remove(player.getUuid());
		return alive;
	}

	public static void reset() {
		PULLS.clear();
		LAST.clear();
	}
}
