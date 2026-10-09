package dev.rackcraft.entity;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RcItems;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;

/**
 * A bolt from a guard's rifle or a turret: slow enough to see and sidestep, and it hurts on every difficulty (its damage
 * type does not scale with difficulty, so a guard you shot first is not a harmless guard on Peaceful).
 */
public final class BoltEntity extends ThrownItemEntity {
	public static EntityType<BoltEntity> TYPE;
	public static final RegistryKey<DamageType> GUARD_BEAM = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, dev.rackcraft.Rackcraft.id("guard_beam"));
	private double damage = RackcraftConfig.values.weapons.guardDamage;
	private boolean hurtsPlayers = true;

	public BoltEntity(EntityType<? extends BoltEntity> type, World world) {
		super(type, world);
	}

	public BoltEntity(World world, LivingEntity owner, double damage) {
		super(TYPE, owner, world);
		this.damage = damage;
	}

	/** A bolt with no shooter, for turrets: fired from this spot. */
	public BoltEntity(World world, double x, double y, double z, double damage) {
		super(TYPE, x, y, z, world);
		this.damage = damage;
	}

	/** Turrets owned by a player must not shoot the player; the base's turrets do. */
	public BoltEntity hurtsPlayers(boolean value) {
		hurtsPlayers = value;
		return this;
	}

	@Override
	protected Item getDefaultItem() { return RcItems.ITEMS.get("steel_slug"); }

	@Override
	protected float getGravity() { return 0; }

	public static DamageSource source(ServerWorld world, Entity attacker) {
		return new DamageSource(world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(GUARD_BEAM), attacker);
	}

	@Override
	public void tick() {
		super.tick();
		if (getWorld() instanceof ServerWorld world) {
			world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getY(), getZ(), 2, 0.05, 0.05, 0.05, 0);
			world.spawnParticles(ParticleTypes.END_ROD, getX(), getY(), getZ(), 1, 0, 0, 0, 0);
			if (age > 80) discard();
		}
	}

	@Override
	protected void onEntityHit(EntityHitResult hit) {
		if (!(getWorld() instanceof ServerWorld world)) return;
		Entity target = hit.getEntity();
		Entity owner = getOwner();
		// Guards do not shoot each other, and a turret's bolt does not shoot the guard beside it.
		if (target instanceof net.minecraft.entity.player.PlayerEntity player && (player.isCreative() || player.isSpectator())) return;
		if (target instanceof GuardEntity && owner instanceof GuardEntity) return;
		if (target instanceof net.minecraft.entity.player.PlayerEntity && !hurtsPlayers) return;
		if (owner == null && (target instanceof net.minecraft.entity.passive.PassiveEntity
				|| target instanceof net.minecraft.entity.passive.TameableEntity tamed && tamed.isTamed())) return;
		target.damage(source(world, owner == null ? this : owner), (float) damage);
		discard();
	}

	@Override
	protected void onCollision(HitResult hit) {
		if (hit.getType() == HitResult.Type.ENTITY) {
			onEntityHit((EntityHitResult) hit);
			return;
		}
		if (!getWorld().isClient) discard();
	}
}
