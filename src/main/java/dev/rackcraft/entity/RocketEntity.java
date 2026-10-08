package dev.rackcraft.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * A rocket on its way up: it stands on the pad through the countdown venting vapour, lights its engines and climbs,
 * faster and faster, until it is out of sight. A doomed one comes apart in a fireball a few seconds after liftoff
 * (only particles: the pad survives). It is scenery: the Launch Control keeps the mission's books, so the rocket is
 * never saved and an unloaded launch still reaches orbit.
 */
public final class RocketEntity extends Entity {
	private static final TrackedData<Integer> STAGES = DataTracker.registerData(RocketEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> LIFTOFF = DataTracker.registerData(RocketEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<String> PAYLOAD = DataTracker.registerData(RocketEntity.class, TrackedDataHandlerRegistry.STRING);
	/** Ticks after liftoff when a doomed rocket comes apart. */
	private static final int FAILS_AFTER = 70;
	private static final int GONE_AFTER = 320;
	private boolean doomed;
	private double climb;
	/** Client only: where the rocket stood, so the exhaust can still hit the pad once it has climbed away. */
	private double padY = Double.NaN;
	private int lerpSteps;
	private double lerpY;

	public RocketEntity(EntityType<? extends RocketEntity> type, World world) {
		super(type, world);
		noClip = true;
		setNoGravity(true);
	}

	public static RocketEntity launch(ServerWorld world, BlockPos base, int stages, String payload, int countdown, boolean doomed) {
		RocketEntity rocket = new RocketEntity(RcEntities.ROCKET, world);
		rocket.dataTracker.set(STAGES, stages);
		rocket.dataTracker.set(LIFTOFF, countdown);
		rocket.dataTracker.set(PAYLOAD, payload);
		rocket.doomed = doomed;
		rocket.refreshPositionAndAngles(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0, 0);
		world.spawnEntity(rocket);
		return rocket;
	}

	public int stages() { return dataTracker.get(STAGES); }
	public String payload() { return dataTracker.get(PAYLOAD); }
	/** Ticks since the engines lit; negative during the countdown. */
	public int flightTicks() { return age - dataTracker.get(LIFTOFF); }

	@Override
	protected void initDataTracker() {
		dataTracker.startTracking(STAGES, 1);
		dataTracker.startTracking(LIFTOFF, 200);
		dataTracker.startTracking(PAYLOAD, "");
	}

	@Override
	public void tick() {
		super.tick();
		int flight = flightTicks();
		if (getWorld().isClient) {
			clientTick(flight);
			return;
		}
		ServerWorld world = (ServerWorld) getWorld();
		if (flight == 0) {
			world.playSound(null, getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 6, 0.5f);
			world.playSound(null, getBlockPos(), SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.BLOCKS, 6, 0.4f);
		}
		if (flight > 0) {
			if (flight % 15 == 0) world.playSound(null, getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.BLOCKS, 5, 0.35f);
			// Gentle at first, then hard: about 40 blocks in the first three seconds, out of sight by ten.
			climb = Math.min(2.5, 0.0008 * flight * flight);
			setPosition(getX(), getY() + climb, getZ());
			velocityDirty = true;
			if (doomed && flight == FAILS_AFTER) {
				world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + 3, getZ(), 3, 1.5, 2, 1.5, 0);
				world.spawnParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 3, getZ(), 80, 2, 3, 2, 0.1);
				world.spawnParticles(ParticleTypes.FLAME, getX(), getY() + 3, getZ(), 120, 2, 3, 2, 0.2);
				world.playSound(null, getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 10, 0.6f);
				discard();
				return;
			}
		}
		if (flight > GONE_AFTER || getY() > world.getTopY() + 160) discard();
	}

	/** Vapour off the tanks during the countdown, then fire and smoke under the engines. */
	private void clientTick(int flight) {
		if (lerpSteps > 0) {
			setPosition(getX(), getY() + (lerpY - getY()) / lerpSteps, getZ());
			lerpSteps--;
		}
		World world = getWorld();
		if (Double.isNaN(padY)) padY = getY();
		if (flight < 0) {
			if (age % 3 == 0) {
				double side = world.random.nextBoolean() ? 0.5 : -0.5;
				world.addParticle(ParticleTypes.CLOUD, getX() + side, getY() + 2 + world.random.nextDouble() * stages() * 1.5,
						getZ() + (world.random.nextDouble() - 0.5), side * 0.05, 0, 0);
			}
			if (flight > -30) {
				world.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX() + (world.random.nextDouble() - 0.5) * 2, getY() + 0.2,
						getZ() + (world.random.nextDouble() - 0.5) * 2, 0, 0.05, 0);
			}
			return;
		}
		int plume = flight < 60 ? 10 : 4;
		for (int index = 0; index < plume; index++) {
			double spread = flight < 40 ? 1.6 : 0.4;
			world.addParticle(ParticleTypes.FLAME, getX() + (world.random.nextDouble() - 0.5) * 0.5, getY() - 0.3,
					getZ() + (world.random.nextDouble() - 0.5) * 0.5, (world.random.nextDouble() - 0.5) * 0.1, -0.6, (world.random.nextDouble() - 0.5) * 0.1);
			world.addParticle(ParticleTypes.LARGE_SMOKE, getX() + (world.random.nextDouble() - 0.5) * spread, getY() - 0.6,
					getZ() + (world.random.nextDouble() - 0.5) * spread, (world.random.nextDouble() - 0.5) * 0.3, -0.05, (world.random.nextDouble() - 0.5) * 0.3);
		}
		if (flight < 40) {
			// The flame trench throws the exhaust out sideways at liftoff.
			for (int index = 0; index < 6; index++) {
				double angle = world.random.nextDouble() * Math.PI * 2;
				world.addParticle(ParticleTypes.CLOUD, getX(), padY + 0.3, getZ(),
						Math.cos(angle) * 0.6, 0.02, Math.sin(angle) * 0.6);
			}
		}
	}

	@Override
	public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int interpolationSteps,
			boolean interpolate) {
		setPosition(x, getY(), z);
		lerpY = y;
		lerpSteps = Math.max(2, interpolationSteps);
	}

	@Override
	public boolean shouldSave() { return false; }

	@Override
	public boolean shouldRender(double distance) { return true; }

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {}

	@Override
	public Packet<ClientPlayPacketListener> createSpawnPacket() {
		return new EntitySpawnS2CPacket(this);
	}
}
