package dev.rackcraft.world;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.entity.BoltEntity;
import dev.rackcraft.entity.GuardEntity;
import dev.rackcraft.sim.NetKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RaycastContext;

/**
 * Sentry Turrets: a laser, an arc and a railgun variant. A powered turret scans for a target once per simulation step
 * (twice a second), never loads a chunk, and shoots with the same heat model as the hand weapons, cooled by a coolant
 * loop with a tower or chiller on it (without one it overheats quickly, which is the point). A turret a player builds
 * shoots hostile mobs and angry guards, never players, pets, villagers or drones; the base's turrets follow the guards'
 * rules instead.
 */
public final class Sentries {
	public static final Set<String> IDS = Set.of("laser_sentry", "arc_sentry", "railgun_sentry");
	private static final Set<String> COOLERS = Set.of("cooling_tower", "dry_cooler", "chiller", "water_heat_exchanger", "crac_unit");
	public static final int MODE_ALL = 0;
	public static final int MODE_GUARDS = 1;
	public static final int MODE_OFF = 2;
	public static final int MODE_BASE = 3;

	private static final class State {
		double heat;
		int cooldown;
		boolean locked;
	}

	private static final Map<ServerWorld, Map<Long, State>> STATES = new WeakHashMap<>();

	private Sentries() {}

	public static boolean isSentry(String id) { return IDS.contains(id); }

	public static double heat(ServerWorld world, BlockPos pos) {
		Map<Long, State> states = STATES.get(world);
		State state = states == null ? null : states.get(pos.asLong());
		return state == null ? 0 : state.heat;
	}

	public static void reset() { STATES.clear(); }

	public static void step(ServerWorld world, List<MachineBlockEntity> machines) {
		Map<BlockPos, MachineBlockEntity> byPos = null;
		double dt = Math.max(1, RackcraftConfig.values.sim.stepTicks) / 20.0;
		for (MachineBlockEntity machine : machines) {
			if (!IDS.contains(machine.blockId())) continue;
			State state = STATES.computeIfAbsent(world, key -> new HashMap<>()).computeIfAbsent(machine.getPos().asLong(), key -> new State());
			if (byPos == null) {
				byPos = new HashMap<>();
				for (MachineBlockEntity other : machines) byPos.put(other.getPos(), other);
			}
			boolean cooled = cooled(world, machine, byPos);
			state.heat = Math.max(0, state.heat - (cooled ? 40 : 6) * dt);
			if (state.cooldown > 0) state.cooldown--;
			if (state.locked && state.heat < 50) state.locked = false;
			boolean base = machine.sentryMode() == MODE_BASE;
			boolean powered = machine.powerSatisfaction() >= 0.5 && !Emp.isDisabled(machine) && !machine.isTripped();
			if (!powered || state.locked || state.cooldown > 0 || machine.sentryMode() == MODE_OFF) continue;
			if (!base && !ResearchLab.get(world).done("directed_energy")) continue;
			LivingEntity target = pick(world, machine);
			if (target == null) continue;
			shoot(world, machine, target, state, base);
		}
	}

	private static boolean cooled(ServerWorld world, MachineBlockEntity machine, Map<BlockPos, MachineBlockEntity> byPos) {
		for (BlockPos pos : NetworkManager.get(world).component(machine.getPos(), NetKind.COOLANT)) {
			MachineBlockEntity other = byPos.get(pos);
			if (other != null && other != machine && COOLERS.contains(other.blockId()) && other.powerSatisfaction() > 0.1) return true;
		}
		return false;
	}

	private static Vec3d muzzle(MachineBlockEntity machine) {
		return Vec3d.ofCenter(machine.getPos()).add(0, 0.6, 0);
	}

	private static boolean sees(ServerWorld world, Vec3d from, Entity target) {
		HitResult hit = world.raycast(new RaycastContext(from, target.getBoundingBox().getCenter(), RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, target));
		return hit.getType() == HitResult.Type.MISS;
	}

	/** The nearest legitimate target in range with a clear line, or null. */
	public static LivingEntity pick(ServerWorld world, MachineBlockEntity machine) {
		int range = RackcraftConfig.values.weapons.sentryRange;
		Vec3d from = muzzle(machine);
		int mode = machine.sentryMode();
		Box box = new Box(machine.getPos()).expand(mode == MODE_BASE ? Math.max(range, RackcraftConfig.values.weapons.guardEngageHard) : range);
		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, box, living -> living.isAlive() && !living.isSpectator())) {
			if (!legitimate(world, machine, entity, mode)) continue;
			double distance = entity.squaredDistanceTo(from);
			if (distance >= bestDistance || !sees(world, from, entity)) continue;
			best = entity;
			bestDistance = distance;
		}
		return best;
	}

	/** Would this turret pick that entity as a target (ignoring line of sight)? For tests and the Multimeter. */
	public static boolean wouldTarget(ServerWorld world, MachineBlockEntity machine, LivingEntity entity) {
		return legitimate(world, machine, entity, machine.sentryMode());
	}

	private static boolean legitimate(ServerWorld world, MachineBlockEntity machine, LivingEntity entity, int mode) {
		Vec3d from = muzzle(machine);
		double distance = entity.getPos().distanceTo(from);
		RackcraftConfig.Weapons c = RackcraftConfig.values.weapons;
		if (mode == MODE_BASE) {
			if (!(entity instanceof PlayerEntity player) || player.isCreative()) return false;
			Difficulty difficulty = world.getDifficulty();
			if (difficulty == Difficulty.PEACEFUL) {
				// Calm until a guard nearby has been attacked by this very player.
				for (GuardEntity guard : world.getEntitiesByClass(GuardEntity.class, new Box(machine.getPos()).expand(40), g -> true)) {
					if (player.getUuid().equals(guard.getAngryAt())) return distance <= c.guardEngageHard;
				}
				return false;
			}
			return distance <= (difficulty == Difficulty.HARD ? c.guardEngageHard : c.guardEngageNormal);
		}
		if (distance > c.sentryRange) return false;
		if (entity instanceof GuardEntity guard) return guard.hasAngerTime() || guard.getTarget() != null;
		return mode == MODE_ALL && entity instanceof HostileEntity;
	}

	private static void shoot(ServerWorld world, MachineBlockEntity machine, LivingEntity target, State state, boolean base) {
		RackcraftConfig.Weapons c = RackcraftConfig.values.weapons;
		Vec3d from = muzzle(machine);
		Vec3d aim = target.getBoundingBox().getCenter();
		var source = BoltEntity.source(world, null);
		switch (machine.blockId()) {
			case "arc_sentry" -> {
				state.cooldown = 2;
				state.heat += 18;
				LivingEntity current = target;
				Vec3d previous = from;
				List<LivingEntity> struck = new ArrayList<>();
				for (int link = 0; link < 3 && current != null; link++) {
					struck.add(current);
					Vec3d at = current.getBoundingBox().getCenter();
					beam(world, previous, at);
					boolean robot = current instanceof GuardEntity guard && guard.isMechanical();
					current.damage(source, (float) (c.sentryDamage * (robot ? 2.5 : 1.5)));
					previous = at;
					LivingEntity next = null;
					for (Entity near : world.getOtherEntities(current, current.getBoundingBox().expand(5))) {
						if (near instanceof LivingEntity living && !struck.contains(living) && legitimate(world, machine, living, base ? machine.sentryMode() : MODE_ALL)) {
							next = living;
							break;
						}
					}
					current = next;
				}
				world.playSound(null, machine.getPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.BLOCKS, 0.4f, 2.0f);
			}
			case "railgun_sentry" -> {
				state.cooldown = 4;
				state.heat += 50;
				Vec3d end = aim.add(aim.subtract(from).normalize().multiply(6));
				beam(world, from, end);
				for (LivingEntity hit : Weapons.along(world, null, from, end, 0.35)) {
					if (legitimate(world, machine, hit, base ? machine.sentryMode() : MODE_ALL) || hit == target) hit.damage(source, (float) (c.sentryDamage * 3.5));
				}
				world.playSound(null, machine.getPos(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 0.4f, 1.8f);
			}
			default -> {
				state.cooldown = 1;
				state.heat += 10;
				BoltEntity bolt = new BoltEntity(world, from.x, from.y, from.z, c.sentryDamage).hurtsPlayers(base);
				Vec3d velocity = aim.subtract(from);
				bolt.setVelocity(velocity.x, velocity.y, velocity.z, 1.6f, 2);
				world.spawnEntity(bolt);
				world.playSound(null, machine.getPos(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.BLOCKS, 0.4f, 1.8f);
			}
		}
		if (state.heat >= 100) {
			state.locked = true;
			world.spawnParticles(ParticleTypes.LARGE_SMOKE, from.x, from.y, from.z, 8, 0.2, 0.2, 0.2, 0.02);
			world.playSound(null, machine.getPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.7f, 0.7f);
		}
	}

	private static void beam(ServerWorld world, Vec3d from, Vec3d to) {
		Vec3d step = to.subtract(from).normalize().multiply(0.8);
		Vec3d at = from;
		for (double travelled = 0; travelled < from.distanceTo(to); travelled += 0.8) {
			world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0, 0, 0, 0);
			at = at.add(step);
		}
	}
}
