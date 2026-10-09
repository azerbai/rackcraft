package dev.rackcraft.world;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.block.BlastDoorBlock;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.entity.GuardEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Electromagnetic pulses. A pulse switches off every Rackcraft machine, drone, turret and robot guard in range for a
 * while (three minutes by default): machines stop drawing and giving power, PDUs trip, robots freeze where they stand,
 * and Blast Doors unlock. Transient on purpose: a pulse that outlives a restart is a pulse nobody can explain.
 */
public final class Emp {
	private static final Map<ServerWorld, Map<Long, Long>> MACHINES = new WeakHashMap<>();
	private static final Map<UUID, Long> ENTITIES = new HashMap<>();

	private Emp() {}

	/** Pulses everything within {@code radius} of the centre for {@code ticks}. Returns how many things it hit. */
	public static int zap(ServerWorld world, Vec3d centre, double radius, long ticks) {
		long until = world.getTime() + ticks;
		int hit = 0;
		BlockPos centreBlock = BlockPos.ofFloored(centre);
		Map<Long, Long> machines = MACHINES.computeIfAbsent(world, key -> new HashMap<>());
		for (MachineBlockEntity machine : new ArrayList<>(SimTicker.machines(world))) {
			if (machine.isRemoved() || machine.getPos().getSquaredDistance(centre) > radius * radius) continue;
			machines.put(machine.getPos().asLong(), until);
			if (machine.blockId().equals("pdu")) machine.setTripped(true);
			hit++;
		}
		int reach = (int) Math.ceil(radius);
		for (BlockPos pos : BlockPos.iterate(centreBlock.add(-reach, -reach, -reach), centreBlock.add(reach, reach, reach))) {
			if (pos.getSquaredDistance(centre) > radius * radius) continue;
			if (world.getBlockState(pos).getBlock() instanceof BlastDoorBlock door) {
				door.unlock(world, pos.toImmutable());
				hit++;
			}
		}
		for (Entity entity : world.getOtherEntities(null, new Box(centre.x - radius, centre.y - radius, centre.z - radius,
				centre.x + radius, centre.y + radius, centre.z + radius), Emp::vulnerable)) {
			if (entity.squaredDistanceTo(centre) > radius * radius) continue;
			ENTITIES.put(entity.getUuid(), until);
			hit++;
		}
		world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, centre.x, centre.y + 0.5, centre.z, 120, radius * 0.25, radius * 0.1, radius * 0.25, 0.4);
		world.spawnParticles(ParticleTypes.FLASH, centre.x, centre.y + 0.5, centre.z, 1, 0, 0, 0, 0);
		world.playSound(null, BlockPos.ofFloored(centre), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.6f, 2.0f);
		return hit;
	}

	public static int zap(ServerWorld world, Vec3d centre) {
		return zap(world, centre, RackcraftConfig.values.weapons.empRadius, RackcraftConfig.values.weapons.empSeconds * 20L);
	}

	/** Things an EMP freezes: Rackcraft's own robots and drones and the guards that are made of them. */
	public static boolean vulnerable(Entity entity) {
		if (entity instanceof GuardEntity guard) return guard.isMechanical();
		return Registries.ENTITY_TYPE.getId(entity.getType()).getNamespace().equals("rackcraft")
				&& Registries.ENTITY_TYPE.getId(entity.getType()).getPath().endsWith("drone");
	}

	/** Pulses one entity for a while (the Arc Coil's touch). */
	public static void freeze(ServerWorld world, Entity entity, long ticks) {
		if (vulnerable(entity)) ENTITIES.put(entity.getUuid(), world.getTime() + ticks);
	}

	public static boolean isDisabled(MachineBlockEntity machine) {
		if (!(machine.getWorld() instanceof ServerWorld world)) return false;
		Map<Long, Long> machines = MACHINES.get(world);
		if (machines == null) return false;
		Long until = machines.get(machine.getPos().asLong());
		if (until == null) return false;
		if (world.getTime() >= until) {
			machines.remove(machine.getPos().asLong());
			return false;
		}
		return true;
	}

	public static boolean isFrozen(Entity entity) {
		if (entity.getWorld().isClient) return false;
		Long until = ENTITIES.get(entity.getUuid());
		if (until == null) return false;
		if (entity.getWorld().getTime() >= until) {
			ENTITIES.remove(entity.getUuid());
			return false;
		}
		return true;
	}

	/** Test hook: machines pulsed in this world. */
	public static List<BlockPos> disabledMachines(ServerWorld world) {
		List<BlockPos> result = new ArrayList<>();
		Map<Long, Long> machines = MACHINES.get(world);
		if (machines != null) machines.forEach((pos, until) -> { if (until > world.getTime()) result.add(BlockPos.fromLong(pos)); });
		return result;
	}

	public static void reset() {
		MACHINES.clear();
		ENTITIES.clear();
	}
}
