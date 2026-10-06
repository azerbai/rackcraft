package dev.rackcraft.world;

import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.PersistentState;

/**
 * Smog. Exhaust fans dump a data center's waste heat straight outside, and they pollute heavily: each
 * running fan adds smog to its chunk (more the more heat it moves), smog drifts into neighbouring chunks
 * and clears over a few minutes. Levels run 0 to 100. From {@link #HAZY} players get hungry, from
 * {@link #CHOKING} they also feel sick and villagers weaken, and smog dims solar panels by up to 60%.
 */
public final class AirQuality extends PersistentState {
	private static final String STATE_KEY = "rackcraft_air";
	public static final float HAZY = 35;
	public static final float CHOKING = 70;
	private static final double BASE_PER_FAN = 1.2;
	private static final double PER_KW_REMOVED = 0.6;
	private static final double CLEAR_SECONDS = 240;
	private static final double DRIFT_PER_SECOND = 0.035;

	private final Map<Long, Float> smog = new HashMap<>();
	private long effectTick;

	public static AirQuality get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(AirQuality::fromNbt, AirQuality::new, STATE_KEY);
	}

	public float smogAt(BlockPos pos) {
		return smog.getOrDefault(new ChunkPos(pos).toLong(), 0f);
	}

	public Map<Long, Float> levels() { return smog; }

	/** Solar output multiplier under this much smog. */
	public static double solarFactor(float level) {
		return 1 - Math.min(0.6, level / 160.0);
	}

	public void set(BlockPos pos, float level) {
		smog.put(new ChunkPos(pos).toLong(), Math.max(0, Math.min(100, level)));
		markDirty();
	}

	/** One simulation step: fans pollute, smog drifts and clears, and the air affects whoever breathes it. */
	public void step(ServerWorld world, Map<MachineBlockEntity, Double> fanHeatRemoved, double dt) {
		fanHeatRemoved.forEach((fan, removed) -> {
			add(new ChunkPos(fan.getPos()).toLong(), (BASE_PER_FAN + PER_KW_REMOVED * removed) * dt);
			// A thick column of smoke out of the back of every running fan.
			Direction back = fan.getCachedState().get(MachineBlock.FACING).getOpposite();
			BlockPos out = fan.getPos().offset(back);
			world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, out.getX() + 0.5, out.getY() + 0.6, out.getZ() + 0.5,
					1 + (int) Math.round(removed), 0.15, 0.1, 0.15, 0.01);
			world.spawnParticles(ParticleTypes.LARGE_SMOKE, out.getX() + 0.5, out.getY() + 0.5, out.getZ() + 0.5,
					2, 0.2, 0.2, 0.2, 0.02);
		});
		if (!smog.isEmpty()) {
			Map<Long, Float> next = new HashMap<>();
			double keep = Math.exp(-dt / CLEAR_SECONDS);
			double drift = Math.min(0.2, DRIFT_PER_SECOND * dt);
			smog.forEach((key, level) -> {
				double remaining = level * keep;
				double share = remaining * drift;
				ChunkPos chunk = new ChunkPos(key);
				for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
					next.merge(ChunkPos.toLong(chunk.x + offset[0], chunk.z + offset[1]), (float) share, Float::sum);
				}
				next.merge(key, (float) (remaining - 4 * share), Float::sum);
			});
			smog.clear();
			next.forEach((key, level) -> {
				if (level >= 0.25f) smog.put(key, Math.min(100, level));
			});
			markDirty();
		}
		breathe(world);
	}

	private void add(long chunk, double amount) {
		smog.merge(chunk, (float) amount, (a, b) -> Math.min(100, a + b));
	}

	private void breathe(ServerWorld world) {
		long now = world.getTime();
		boolean apply = now - effectTick >= 40;
		if (apply) effectTick = now;
		for (ServerPlayerEntity player : world.getPlayers()) {
			float level = smogAt(player.getBlockPos());
			if (level < 15) continue;
			int haze = (int) (level / 12);
			world.spawnParticles(player, ParticleTypes.WHITE_ASH, true, player.getX(), player.getY() + 1.5, player.getZ(),
					haze * 3, 6, 3, 6, 0.01);
			if (level >= 45) world.spawnParticles(player, ParticleTypes.SMOKE, true, player.getX(), player.getY() + 1.5,
					player.getZ(), haze, 5, 2, 5, 0.005);
			if (!apply || player.isCreative() || player.isSpectator()) continue;
			if (level >= HAZY) player.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 80, level >= CHOKING ? 1 : 0, true, false));
			if (level >= CHOKING) player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 100, 0, true, false));
		}
		if (!apply) return;
		smog.forEach((key, level) -> {
			if (level < CHOKING) return;
			ChunkPos chunk = new ChunkPos(key);
			if (!world.isChunkLoaded(chunk.x, chunk.z)) return;
			var box = new net.minecraft.util.math.Box(chunk.getStartX(), world.getBottomY(), chunk.getStartZ(),
					chunk.getEndX() + 1, world.getTopY(), chunk.getEndZ() + 1);
			for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
				villager.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 80, 0, true, false));
			}
		});
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList list = new NbtList();
		smog.forEach((key, level) -> {
			NbtCompound entry = new NbtCompound();
			entry.putLong("Chunk", key);
			entry.putFloat("Level", level);
			list.add(entry);
		});
		nbt.put("Smog", list);
		return nbt;
	}

	private static AirQuality fromNbt(NbtCompound nbt) {
		AirQuality air = new AirQuality();
		NbtList list = nbt.getList("Smog", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < list.size(); index++) {
			NbtCompound entry = list.getCompound(index);
			air.smog.put(entry.getLong("Chunk"), Math.max(0, Math.min(100, entry.getFloat("Level"))));
		}
		return air;
	}
}
