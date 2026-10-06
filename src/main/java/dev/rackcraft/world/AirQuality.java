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
 * and clears over a few minutes. Smog Scrubbers pull it back out. Levels run 0 to 100, and breathing it
 * gets worse in steps:
 *
 * <ul>
 *   <li>over {@link #HAZY} (30): dizziness, a gentle sway of the view (stronger from 60);</li>
 *   <li>{@link #BLINDING} (45): spells of blindness, more often the thicker it gets;</li>
 *   <li>{@link #COUGHING} (55): Smoker's Cough, for villagers too;</li>
 *   <li>{@link #CHOKING} (70): poison and hunger; villagers weaken and are poisoned too;</li>
 *   <li>{@link #TOXIC} (88): poison II.</li>
 * </ul>
 *
 * A Respirator keeps all of it out while its filter lasts. Smog also dims solar panels by up to 60%.
 */
public final class AirQuality extends PersistentState {
	private static final String STATE_KEY = "rackcraft_air";
	public static final float HAZY = 30;
	public static final float BLINDING = 45;
	public static final float COUGHING = 55;
	public static final float CHOKING = 70;
	public static final float TOXIC = 88;
	/** Smog a fully powered scrubber removes from its own chunk per second; half that from each neighbour. */
	public static final double SCRUB_PER_SECOND = 1.5;
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

	/**
	 * One simulation step: fans pollute, scrubbers clean, smog drifts and clears, and the air affects whoever
	 * breathes it. {@code scrubbers} maps each Smog Scrubber to the share of its power it is getting.
	 */
	public void step(ServerWorld world, Map<MachineBlockEntity, Double> fanHeatRemoved, Map<MachineBlockEntity, Double> scrubbers,
			double dt) {
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
		scrubbers.forEach((scrubber, power) -> {
			double strength = power >= 0.5 ? power : 0;
			ChunkPos chunk = new ChunkPos(scrubber.getPos());
			double removed = take(chunk.toLong(), SCRUB_PER_SECOND * strength * dt);
			for (int[] offset : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				removed += take(ChunkPos.toLong(chunk.x + offset[0], chunk.z + offset[1]), SCRUB_PER_SECOND * 0.5 * strength * dt);
			}
			scrubber.setScrubRate(removed / dt);
			if (removed > 0) {
				BlockPos top = scrubber.getPos().up();
				world.spawnParticles(ParticleTypes.CLOUD, top.getX() + 0.5, top.getY() + 0.1, top.getZ() + 0.5, 1, 0.15, 0.05, 0.15, 0.02);
			}
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

	/** Removes up to {@code amount} smog from a chunk; returns how much there was to remove. */
	private double take(long chunk, double amount) {
		float level = smog.getOrDefault(chunk, 0f);
		if (level <= 0 || amount <= 0) return 0;
		double removed = Math.min(level, amount);
		if (level - removed < 0.25) smog.remove(chunk);
		else smog.put(chunk, (float) (level - removed));
		markDirty();
		return removed;
	}

	/** Makes the next step apply effects even if it's been under two seconds; for the self-test. */
	public void applyEffectsNextStep() {
		effectTick = Long.MIN_VALUE / 2;
	}

	/** Whether this entity's Respirator is keeping the smog out. */
	public static boolean filtered(net.minecraft.entity.LivingEntity entity) {
		return entity.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).isOf(dev.rackcraft.RcItems.ITEMS.get("respirator"));
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
			if (level >= BLINDING) world.spawnParticles(player, ParticleTypes.SMOKE, true, player.getX(), player.getY() + 1.5,
					player.getZ(), haze, 5, 2, 5, 0.005);
			if (!apply || player.isCreative() || player.isSpectator() || level <= HAZY) continue;
			if (filtered(player)) {
				// The filter clogs a little every two seconds in smog, faster the thicker it is.
				net.minecraft.item.ItemStack mask = player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD);
				mask.damage(level >= CHOKING ? 2 : 1, player, broken -> broken.sendEquipmentBreakStatus(net.minecraft.entity.EquipmentSlot.HEAD));
				continue;
			}
			breathe(player, level, world.random);
		}
		if (!apply) return;
		smog.forEach((key, level) -> {
			if (level < COUGHING) return;
			ChunkPos chunk = new ChunkPos(key);
			if (!world.isChunkLoaded(chunk.x, chunk.z)) return;
			var box = new net.minecraft.util.math.Box(chunk.getStartX(), world.getBottomY(), chunk.getStartZ(),
					chunk.getEndX() + 1, world.getTopY(), chunk.getEndZ() + 1);
			for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
				villager.addStatusEffect(new StatusEffectInstance(dev.rackcraft.RcEffects.COUGHING, 100, 0, true, true));
				if (level < CHOKING) continue;
				villager.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 80, 0, true, false));
				villager.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 60, 0, true, false));
			}
		});
	}

	/** What a player breathing this much smog gets, refreshed every two seconds. */
	static void breathe(ServerPlayerEntity player, float level, net.minecraft.util.math.random.Random random) {
		player.addStatusEffect(new StatusEffectInstance(dev.rackcraft.RcEffects.DIZZY, 100, level >= 60 ? 1 : 0, true, true));
		if (level >= BLINDING && random.nextFloat() < 0.08f + (level - BLINDING) / 80f) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 50, 0, true, false));
		}
		if (level >= COUGHING) {
			player.addStatusEffect(new StatusEffectInstance(dev.rackcraft.RcEffects.COUGHING, 100, level >= CHOKING ? 1 : 0, true, true));
		}
		if (level >= CHOKING) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 80, 0, true, false));
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 100, level >= TOXIC ? 1 : 0, true, true));
		}
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
