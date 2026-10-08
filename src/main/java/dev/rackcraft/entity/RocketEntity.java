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
import dev.rackcraft.RcSounds;
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

	/** Ticks of ignition sound before liftoff (it starts at T-6). */
	private static final int IGNITION_LEAD = 120;
	/** Ticks between thrust roars: each lasts five seconds and fades at both ends, so they overlap into one. */
	private static final int THRUST_EVERY = 80;
	/** How far away the rest of the world hears a launch as a distant rumble. */
	public static final int RUMBLE_RANGE = 768;

	/** When stages separate, in ticks after liftoff: twice for a Saturn V, once for a Saturn IB. */
	public static int[] separations(int stages) {
		return switch (stages) {
			case 3 -> new int[] {150, 250};
			case 2 -> new int[] {190};
			default -> new int[0];
		};
	}

	/**
	 * A thunder of a launch for everyone further off than the rocket's own sounds reach: played where each player
	 * stands, quieter with distance, and never for someone already close enough to hear the engines.
	 */
	private void rumble(ServerWorld world, float strength) {
		for (net.minecraft.server.network.ServerPlayerEntity player : world.getPlayers()) {
			double distance = Math.sqrt(player.squaredDistanceTo(getX(), player.getY(), getZ()));
			if (distance < 160 || distance > RUMBLE_RANGE) continue;
			float volume = (float) (strength * (stages() == 3 ? 1.0 : 0.6) * (1 - (distance - 160) / (RUMBLE_RANGE - 160)));
			player.playSound(RcSounds.ROCKET_DISTANT, SoundCategory.BLOCKS, Math.max(0.05f, volume), 0.9f);
		}
	}

	/** A stage separating: a flash and a crack, a ring of smoke, and the spent stage's last fire. */
	private void separate(ServerWorld world) {
		double y = getY() + (stages() == 3 ? 13 : 8);
		world.playSound(null, BlockPos.ofFloored(getX(), y, getZ()), RcSounds.ROCKET_STAGING, SoundCategory.BLOCKS, 6, stages() == 3 ? 0.9f : 1.0f);
		world.spawnParticles(ParticleTypes.FLASH, getX(), y, getZ(), 2, 0.5, 0.5, 0.5, 0);
		world.spawnParticles(ParticleTypes.EXPLOSION, getX(), y, getZ(), 12, 2.5, 1, 2.5, 0);
		world.spawnParticles(ParticleTypes.CLOUD, getX(), y, getZ(), 160, 4, 0.6, 4, 0.25);
		world.spawnParticles(ParticleTypes.FIREWORK, getX(), y, getZ(), 80, 1.5, 1.5, 1.5, 0.35);
		world.spawnParticles(ParticleTypes.FLAME, getX(), y - 3, getZ(), 60, 1.5, 2, 1.5, 0.1);
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
		int stages = stages();
		// Bigger rockets are louder: the Saturn V's sounds reach the furthest.
		float loud = stages == 3 ? 5 : stages == 2 ? 3.5f : 2.5f;
		float pitch = stages == 3 ? 0.85f : stages == 2 ? 0.95f : 1.1f;
		if (flight == -IGNITION_LEAD) world.playSound(null, getBlockPos(), RcSounds.ROCKET_IGNITION, SoundCategory.BLOCKS, loud * 0.8f, pitch);
		if (flight == 0) {
			world.playSound(null, getBlockPos(), RcSounds.ROCKET_LIFTOFF, SoundCategory.BLOCKS, loud, pitch);
			rumble(world, 1);
			if (stages == 3) {
				// The Saturn V clears the tower in a flash and a ground-shaking wall of exhaust.
				world.spawnParticles(ParticleTypes.FLASH, getX(), getY() + 1, getZ(), 3, 1.5, 0.5, 1.5, 0);
				world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + 0.5, getZ(), 4, 4, 0.3, 4, 0);
			}
		}
		if (flight > 0) {
			// Overlapping roars follow it up, a little higher-pitched as it draws away.
			if (flight % THRUST_EVERY == 0) {
				world.playSound(null, getBlockPos(), RcSounds.ROCKET_THRUST, SoundCategory.BLOCKS, loud, pitch * (1 + flight / 2000f));
			}
			if (stages == 3 && flight == 100) rumble(world, 0.6f);
			climb = speed(flight);
			setPosition(getX(), getY() + climb, getZ());
			velocityDirty = true;
			for (int separation : separations(stages)) if (flight == separation && !doomed) separate(world);
			if (doomed && flight == FAILS_AFTER) {
				double middle = getY() + height(stages) * 0.4;
				world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), middle, getZ(), 6 + stages * 4, 2 + stages, height(stages) * 0.3, 2 + stages, 0);
				world.spawnParticles(ParticleTypes.FLASH, getX(), middle, getZ(), 4, 2, height(stages) * 0.2, 2, 0);
				world.spawnParticles(ParticleTypes.LARGE_SMOKE, getX(), middle, getZ(), 200 * stages, 3 + stages, height(stages) * 0.3, 3 + stages, 0.2);
				world.spawnParticles(ParticleTypes.FLAME, getX(), middle, getZ(), 250 * stages, 3 + stages, height(stages) * 0.3, 3 + stages, 0.35);
				world.spawnParticles(ParticleTypes.LAVA, getX(), middle, getZ(), 40 * stages, 3, height(stages) * 0.3, 3, 0.5);
				world.playSound(null, getBlockPos(), RcSounds.ROCKET_EXPLOSION, SoundCategory.BLOCKS, loud * 1.4f, pitch * 0.9f);
				rumble(world, 1.3f);
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
		// The Saturn V's five F-1s: twice the fire and a column of smoke that hangs in the sky behind it.
		int burn = stages == 3 ? (flight < 100 ? 8 : 4) : (flight < 100 ? 4 : 2);
		if (stages == 3) {
			for (int index = 0; index < 3; index++) {
				world.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, getX() + (random.nextDouble() - 0.5) * 3, getY() - 4 - random.nextDouble() * 6,
						getZ() + (random.nextDouble() - 0.5) * 3, (random.nextDouble() - 0.5) * 0.04, 0.02, (random.nextDouble() - 0.5) * 0.04);
			}
			if (flight < 40) {
				world.addParticle(ParticleTypes.FLASH, getX(), padY + 1, getZ(), 0, 0, 0);
				// The water deluge flashing to steam around the pad.
				for (int index = 0; index < 6; index++) {
					double angle = random.nextDouble() * Math.PI * 2;
					world.addParticle(ParticleTypes.CLOUD, getX() + Math.cos(angle) * 3, padY + 0.5, getZ() + Math.sin(angle) * 3,
							Math.cos(angle) * 0.15, 0.35 + random.nextDouble() * 0.3, Math.sin(angle) * 0.15);
				}
			}
		}
		for (double[] engine : engines(stages)) {
			for (int index = 0; index < burn; index++) {
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
			int ring = (int) ((stages == 3 ? 30 : 14) * scale * (1 - flight / 120.0)) + 2;
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
