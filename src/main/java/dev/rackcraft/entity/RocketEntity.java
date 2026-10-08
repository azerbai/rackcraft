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
	private static final int FAILS_AFTER = 100;
	private static final int GONE_AFTER = 400;
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

	/** Where each engine sits, in blocks from the centre line: five for a Saturn V, four for a IB, one otherwise. */
	public static double[][] engines(int stages) {
		return switch (stages) {
			case 3 -> new double[][] {{0, 0}, {0.875, 0.875}, {-0.875, 0.875}, {0.875, -0.875}, {-0.875, -0.875}};
			case 2 -> new double[][] {{0.625, 0.625}, {-0.625, 0.625}, {0.625, -0.625}, {-0.625, -0.625}};
			default -> new double[][] {{0, 0}};
		};
	}

	/** How tall the rocket is, in blocks, to the tip of the escape tower or fairing. */
	public static double height(int stages) {
		return switch (stages) {
			case 3 -> 37.7;
			case 2 -> 19.5;
			default -> 14.3;
		};
	}

	/** Its speed this many ticks after liftoff: a slow, heavy start (it takes a few seconds to clear its own height). */
	public static double speed(int flight) {
		return Math.min(2.0, 0.00025 * flight * flight);
	}

	@Override
	public net.minecraft.util.math.Box getVisibilityBoundingBox() {
		// Seen from anywhere it could be: the collision box is only a stub at its feet.
		return new net.minecraft.util.math.Box(getX() - 3, getY(), getZ() - 3, getX() + 3, getY() + height(stages()), getZ() + 3);
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
		if (flight == -40) world.playSound(null, getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 4, 0.5f);
		if (flight == 0) {
			world.playSound(null, getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 8, 0.4f);
			world.playSound(null, getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.BLOCKS, 8, 0.5f);
		}
		if (flight > 0) {
			// A low roar that follows it up.
			if (flight % 12 == 0) world.playSound(null, getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.BLOCKS, 8, 0.3f);
			if (flight % 40 == 20 && flight < 200) {
				world.playSound(null, getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.BLOCKS, 6, 0.4f);
			}
			climb = speed(flight);
			setPosition(getX(), getY() + climb, getZ());
			velocityDirty = true;
			if (doomed && flight == FAILS_AFTER) {
				double middle = getY() + height(stages()) * 0.4;
				world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), middle, getZ(), 6, 2, height(stages()) * 0.3, 2, 0);
				world.spawnParticles(ParticleTypes.LARGE_SMOKE, getX(), middle, getZ(), 200, 3, height(stages()) * 0.3, 3, 0.15);
				world.spawnParticles(ParticleTypes.FLAME, getX(), middle, getZ(), 250, 3, height(stages()) * 0.3, 3, 0.3);
				world.playSound(null, getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 12, 0.5f);
				world.playSound(null, getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.BLOCKS, 12, 0.6f);
				discard();
				return;
			}
		}
		if (flight > GONE_AFTER || getY() > world.getTopY() + 200) discard();
	}

	/**
	 * Vapour boiling off the tanks during the countdown, ignition smoke in the last seconds, then a column of fire under
	 * every engine, and at liftoff the flame trench throwing a ring of exhaust out across the pad.
	 */
	private void clientTick(int flight) {
		if (lerpSteps > 0) {
			setPosition(getX(), getY() + (lerpY - getY()) / lerpSteps, getZ());
			lerpSteps--;
		}
		World world = getWorld();
		var random = world.random;
		if (Double.isNaN(padY)) padY = getY();
		int stages = stages();
		double height = height(stages);
		double radius = stages == 3 ? 1.6 : stages == 2 ? 1.35 : 0.75;
		if (flight < 0) {
			if (age % 2 == 0) {
				double angle = random.nextDouble() * Math.PI * 2;
				world.addParticle(ParticleTypes.CLOUD, getX() + Math.cos(angle) * radius, getY() + height * (0.3 + random.nextDouble() * 0.6),
						getZ() + Math.sin(angle) * radius, Math.cos(angle) * 0.04, -0.02, Math.sin(angle) * 0.04);
			}
			if (flight > -40) {
				for (double[] engine : engines(stages)) {
					world.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX() + engine[0] + (random.nextDouble() - 0.5), padY + 0.2,
							getZ() + engine[1] + (random.nextDouble() - 0.5), 0, 0.04, 0);
				}
			}
			return;
		}
		double scale = stages == 3 ? 1.6 : stages == 2 ? 1.2 : 0.8;
		for (double[] engine : engines(stages)) {
			for (int index = 0; index < (flight < 100 ? 4 : 2); index++) {
				double x = getX() + engine[0] + (random.nextDouble() - 0.5) * 0.4 * scale;
				double z = getZ() + engine[1] + (random.nextDouble() - 0.5) * 0.4 * scale;
				world.addParticle(ParticleTypes.FLAME, x, getY() - 0.2, z, (random.nextDouble() - 0.5) * 0.08, -0.9 - climb, (random.nextDouble() - 0.5) * 0.08);
				world.addParticle(ParticleTypes.LARGE_SMOKE, x, getY() - 1.2 - random.nextDouble() * 2, z,
						(random.nextDouble() - 0.5) * 0.25 * scale, -0.1, (random.nextDouble() - 0.5) * 0.25 * scale);
			}
			if (random.nextInt(3) == 0) {
				world.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, getX() + engine[0], getY() - 2, getZ() + engine[1],
						(random.nextDouble() - 0.5) * 0.05, 0.01, (random.nextDouble() - 0.5) * 0.05);
			}
		}
		if (flight < 120) {
			// The flame trench throws the exhaust out sideways in a great ring across the pad.
			int ring = (int) (14 * scale * (1 - flight / 120.0)) + 2;
			for (int index = 0; index < ring; index++) {
				double angle = random.nextDouble() * Math.PI * 2;
				double speed = 0.4 + random.nextDouble() * 0.5 * scale;
				world.addParticle(index % 3 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.CLOUD, getX(), padY + 0.4 + random.nextDouble(), getZ(),
						Math.cos(angle) * speed, 0.03, Math.sin(angle) * speed);
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
