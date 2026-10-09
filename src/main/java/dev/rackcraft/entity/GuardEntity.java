package dev.rackcraft.entity;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RcItems;
import dev.rackcraft.world.Emp;
import java.util.UUID;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.ProjectileAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.UniversalAngerGoal;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.TimeHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.intprovider.UniformIntProvider;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * A person (or robot) who works at a military base or a hostile data centre. One class, three types: the Soldier and the
 * Security Robot shoot bolts, the Scavenger hits things with a pipe. They follow the zombified piglin rules by difficulty:
 * on Peaceful they ignore you until you hurt one (then it and its neighbours turn on you for a few minutes), on Easy and
 * Normal they act when you come close with a line of sight, and on Hard they open fire on sight from further away. They
 * stay at their posts, never spawn on their own, and do not count towards mob caps.
 */
public final class GuardEntity extends PathAwareEntity implements Angerable, RangedAttackMob {
	public enum Kind { SOLDIER, ROBOT, SCAVENGER }

	private static final TrackedData<Boolean> ELITE = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final UniformIntProvider ANGER_TIME = TimeHelper.betweenSeconds(120, 180);
	/** How long without a target before a guard forgets the fight and heals up. */
	public static final int RESET_TICKS = 600;
	private int angerTime;
	@Nullable
	private UUID angryAt;
	private BlockPos home;
	private int idleTicks;

	public GuardEntity(EntityType<? extends GuardEntity> type, World world) {
		super(type, world);
		setPersistent();
		experiencePoints = 5;
	}

	public Kind kind() {
		return getType() == RcEntities.SECURITY_ROBOT ? Kind.ROBOT : getType() == RcEntities.SCAVENGER ? Kind.SCAVENGER : Kind.SOLDIER;
	}

	/** Robots are the ones an EMP can freeze and the Arc Coil hurts more. */
	public boolean isMechanical() { return kind() == Kind.ROBOT; }

	public boolean isElite() { return dataTracker.get(ELITE); }

	public BlockPos home() { return home; }

	public static DefaultAttributeContainer.Builder createGuardAttributes() {
		return PathAwareEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 30)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.27)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 28)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 3)
				.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.3);
	}

	/** Dresses and equips this guard for its kind, and ties it to a post. Elites get more of everything and a name. */
	public void equipFor(BlockPos post, @Nullable String eliteName) {
		RackcraftConfig.Weapons c = RackcraftConfig.values.weapons;
		home = post.toImmutable();
		setPositionTarget(home, 32);
		double health = switch (kind()) {
			case SOLDIER -> c.soldierHealth;
			case ROBOT -> c.robotHealth;
			case SCAVENGER -> c.scavengerHealth;
		};
		if (eliteName != null) {
			dataTracker.set(ELITE, true);
			health *= 2.5;
			setCustomName(Text.literal(eliteName));
			setCustomNameVisible(true);
			EntityAttributeInstance damage = getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
			if (damage != null) damage.setBaseValue(damage.getBaseValue() * 1.5);
		}
		EntityAttributeInstance max = getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
		if (max != null) max.setBaseValue(health);
		setHealth((float) health);
		ItemStack held = switch (kind()) {
			case SOLDIER -> new ItemStack(RcItems.ITEMS.get("plasma_rifle"));
			case ROBOT -> new ItemStack(RcItems.ITEMS.get("lance_laser"));
			case SCAVENGER -> new ItemStack(Items.LIGHTNING_ROD);
		};
		equipStack(EquipmentSlot.MAINHAND, held);
		setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
	}

	@Override
	protected void initDataTracker() {
		super.initDataTracker();
		dataTracker.startTracking(ELITE, false);
	}

	@Override
	protected void initGoals() {
		goalSelector.add(0, new SwimGoal(this));
		if (kind() == Kind.SCAVENGER) goalSelector.add(2, new MeleeAttackGoal(this, 1.15, false));
		else goalSelector.add(2, new ProjectileAttackGoal(this, 1.0, kind() == Kind.ROBOT ? 22 : 30, 22f));
		goalSelector.add(4, new ReturnPostGoal());
		goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 10f));
		goalSelector.add(7, new LookAroundGoal(this));
		targetSelector.add(1, new RevengeGoal(this).setGroupRevenge());
		targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, true, false, this::wantsToFight));
		targetSelector.add(3, new UniversalAngerGoal<>(this, true));
	}

	/** How close a player must come before this guard acts, by difficulty; 0 means never, until provoked. */
	public int engageRange(Difficulty difficulty) {
		RackcraftConfig.Weapons c = RackcraftConfig.values.weapons;
		if (difficulty == Difficulty.PEACEFUL) return 0;
		boolean hard = difficulty == Difficulty.HARD;
		return kind() == Kind.SCAVENGER ? (hard ? c.scavengerEngageHard : c.scavengerEngageNormal) : (hard ? c.guardEngageHard : c.guardEngageNormal);
	}

	public boolean wantsToFight(LivingEntity target) {
		if (isFrozen() || !(target instanceof PlayerEntity player) || player.isCreative() || player.isSpectator()) return false;
		if (shouldAngerAt(target)) return true;
		int range = engageRange(getWorld().getDifficulty());
		return range > 0 && squaredDistanceTo(target) <= (double) range * range && canSee(target);
	}

	/**
	 * Vanilla mobs may not target players on Peaceful at all. Guards may, once provoked: whether they actually do is down to
	 * {@link #wantsToFight}, which on Peaceful only ever says yes to someone they are angry at.
	 */
	@Override
	public boolean canTarget(LivingEntity target) {
		if (target instanceof PlayerEntity player) return player.isAlive() && !player.isSpectator() && !player.isCreative();
		return super.canTarget(target);
	}

	public boolean isFrozen() { return Emp.isFrozen(this); }

	@Override
	public boolean isAiDisabled() { return super.isAiDisabled() || isFrozen(); }

	@Override
	protected void mobTick() {
		if (getWorld() instanceof ServerWorld world) tickAngerLogic(world, true);
		super.mobTick();
		if (getTarget() == null && !hasAngerTime()) {
			idleTicks++;
			if (idleTicks == RESET_TICKS && getHealth() < getMaxHealth()) setHealth(getMaxHealth());
		} else {
			idleTicks = 0;
			// A guard does not chase you out of its structure.
			if (home != null && age % 20 == 0 && squaredDistanceTo(Vec3d.ofCenter(home)) > 48.0 * 48.0) {
				setTarget(null);
				stopAnger();
			}
		}
	}

	/** Ticks since this guard last had anyone to worry about (for the self-test). */
	public int idleTicks() { return idleTicks; }

	public void setIdleTicks(int ticks) { idleTicks = ticks; }

	/** Back to its post when there is nothing else to do. */
	private final class ReturnPostGoal extends Goal {
		@Override
		public boolean canStart() {
			return home != null && getTarget() == null && getNavigation().isIdle() && squaredDistanceTo(Vec3d.ofCenter(home)) > 9;
		}

		@Override
		public void start() {
			getNavigation().startMovingTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
		}
	}

	@Override
	public void attack(LivingEntity target, float pullProgress) {
		RackcraftConfig.Weapons c = RackcraftConfig.values.weapons;
		BoltEntity bolt = new BoltEntity(getWorld(), this, c.guardDamage * (isElite() ? 1.5 : 1));
		bolt.setPosition(getX(), getEyeY() - 0.2, getZ());
		double dx = target.getX() - getX();
		double dy = target.getBodyY(0.5) - bolt.getY();
		double dz = target.getZ() - getZ();
		float spread = 14 - getWorld().getDifficulty().getId() * 4;
		bolt.setVelocity(dx, dy, dz, 1.3f, spread);
		getWorld().spawnEntity(bolt);
		playSound(isMechanical() ? SoundEvents.ENTITY_BLAZE_SHOOT : SoundEvents.ENTITY_SKELETON_SHOOT, 0.8f, 1.6f);
	}

	@Override
	public boolean tryAttack(net.minecraft.entity.Entity target) {
		if (!(getWorld() instanceof ServerWorld world)) return false;
		float amount = (float) (RackcraftConfig.values.weapons.scavengerDamage * (isElite() ? 1.5 : 1));
		boolean hurt = target.damage(BoltEntity.source(world, this), amount);
		if (hurt) swingHand(net.minecraft.util.Hand.MAIN_HAND);
		return hurt;
	}

	@Override
	public void onDeath(DamageSource source) {
		super.onDeath(source);
		if (isElite() && getWorld() instanceof ServerWorld world) dev.rackcraft.world.Bounties.complete(world, this, source.getAttacker());
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) { return false; }

	@Override
	public boolean cannotDespawn() { return true; }

	@Override
	protected SoundEvent getHurtSound(DamageSource source) { return isMechanical() ? SoundEvents.ENTITY_IRON_GOLEM_HURT : SoundEvents.ENTITY_PLAYER_HURT; }

	@Override
	protected SoundEvent getDeathSound() { return isMechanical() ? SoundEvents.ENTITY_IRON_GOLEM_DEATH : SoundEvents.ENTITY_PLAYER_DEATH; }

	// ---------------------------------------------------------------- Angerable

	@Override
	public int getAngerTime() { return angerTime; }

	@Override
	public void setAngerTime(int time) { angerTime = time; }

	@Nullable
	@Override
	public UUID getAngryAt() { return angryAt; }

	@Override
	public void setAngryAt(@Nullable UUID uuid) { angryAt = uuid; }

	@Override
	public void chooseRandomAngerTime() { setAngerTime(ANGER_TIME.get(random)); }

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		writeAngerToNbt(nbt);
		nbt.putBoolean("Elite", isElite());
		if (home != null) nbt.putLong("Post", home.asLong());
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (getWorld() instanceof ServerWorld world) readAngerFromNbt(world, nbt);
		dataTracker.set(ELITE, nbt.getBoolean("Elite"));
		if (nbt.contains("Post")) {
			home = BlockPos.fromLong(nbt.getLong("Post"));
			setPositionTarget(home, 32);
		}
	}
}
