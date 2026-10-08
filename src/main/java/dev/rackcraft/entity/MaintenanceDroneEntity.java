package dev.rackcraft.entity;

import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.world.DroneDocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A Maintenance Drone in flight. It leaves its Drone Dock for one job, climbs to cruise height, flies over the target,
 * works on it for two seconds, and flies home, where it goes back into the dock's drone slot. It flies through
 * anything (it is small and determined). Whatever it carries (a spare module out, a dead one back) travels with it,
 * and if it is knocked down or its dock is gone, the drone and its cargo drop as items.
 */
public final class MaintenanceDroneEntity extends Entity {
	public static final double SPEED = 0.45;
	public static final int WORK_TICKS = 40;
	/** A drone that somehow hasn't made it home after this long gives up and lands where it is. */
	private static final int MAX_AGE = 20 * 300;

	public enum Phase { OUTBOUND, WORKING, RETURNING }

	/** Synced so the client can animate a drone that is working (gripper, wobble). */
	private static final net.minecraft.entity.data.TrackedData<Boolean> WORKING = net.minecraft.entity.data.DataTracker.registerData(
			MaintenanceDroneEntity.class, net.minecraft.entity.data.TrackedDataHandlerRegistry.BOOLEAN);

	private BlockPos dock = BlockPos.ORIGIN;
	private BlockPos target = BlockPos.ORIGIN;
	private DroneDocks.Job job = DroneDocks.Job.BREAKER;
	private int slot;
	private ItemStack carried = ItemStack.EMPTY;
	private Phase phase = Phase.OUTBOUND;
	private int workTicks;
	private int flightTicks;
	// Client-side smoothing between position updates, as boats do.
	private int lerpSteps;
	private double lerpX;
	private double lerpY;
	private double lerpZ;
	private float lerpYaw;

	public MaintenanceDroneEntity(EntityType<? extends MaintenanceDroneEntity> type, World world) {
		super(type, world);
		noClip = true;
		setNoGravity(true);
	}

	public static MaintenanceDroneEntity launch(ServerWorld world, BlockPos dock, BlockPos target, DroneDocks.Job job, int slot,
			ItemStack carried) {
		MaintenanceDroneEntity drone = new MaintenanceDroneEntity(RcEntities.MAINTENANCE_DRONE, world);
		drone.dock = dock;
		drone.target = target;
		drone.job = job;
		drone.slot = slot;
		drone.carried = carried;
		drone.refreshPositionAndAngles(dock.getX() + 0.5, dock.getY() + 1.1, dock.getZ() + 0.5, 0, 0);
		world.spawnEntity(drone);
		return drone;
	}

	public BlockPos dock() { return dock; }
	public BlockPos target() { return target; }
	public DroneDocks.Job job() { return job; }
	public Phase phase() { return phase; }
	public boolean working() { return dataTracker.get(WORKING); }

	@Override
	protected void initDataTracker() {
		dataTracker.startTracking(WORKING, false);
	}

	@Override
	public void tick() {
		super.tick();
		if (getWorld().isClient) {
			clientTick();
			return;
		}
		serverTick();
	}

	/** One tick of flight and work. The self-test calls it directly. */
	public void serverTick() {
		if (!(getWorld() instanceof ServerWorld world)) return;
		flightTicks++;
		if (flightTicks > MAX_AGE) {
			land(world);
			return;
		}
		switch (phase) {
			case OUTBOUND -> {
				if (fly(Vec3d.ofCenter(target).add(0, 1.0, 0))) {
					phase = Phase.WORKING;
					workTicks = 0;
					dataTracker.set(WORKING, true);
				}
			}
			case WORKING -> {
				setVelocity(Vec3d.ZERO);
				workTicks++;
				if (workTicks % 4 == 0) {
					world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5,
							3, 0.25, 0.1, 0.25, 0.02);
				}
				if (workTicks == 1) world.playSound(null, target, SoundEvents.BLOCK_BEEHIVE_WORK, SoundCategory.NEUTRAL, 0.8f, 1.6f);
				if (workTicks >= WORK_TICKS) {
					carried = DroneDocks.doJob(world, this, carried);
					phase = Phase.RETURNING;
					dataTracker.set(WORKING, false);
				}
			}
			case RETURNING -> {
				if (!(world.getBlockEntity(dock) instanceof MachineBlockEntity home) || !home.blockId().equals("drone_dock")) {
					if (fly(Vec3d.ofCenter(dock).add(0, 1.0, 0))) land(world);
					return;
				}
				if (fly(Vec3d.ofCenter(dock).add(0, 1.0, 0))) {
					DroneDocks.dockDrone(world, home, carried);
					discard();
				}
			}
		}
	}

	/** Moves toward a point, climbing to cruise height first when far away. Returns true on arrival. */
	private boolean fly(Vec3d destination) {
		Vec3d here = getPos();
		if (destination.distanceTo(here) <= SPEED) {
			setPosition(destination.x, destination.y, destination.z);
			setVelocity(Vec3d.ZERO);
			return true;
		}
		// Climb straight up to cruise height, cross over the top of the hall, then drop onto the target, so drones
		// don't cut through the racks they are flying past.
		double horizontal = Math.hypot(destination.x - here.x, destination.z - here.z);
		double cruise = Math.max(Math.max(dock.getY(), target.getY()) + 3.5, destination.y);
		Vec3d aim = horizontal <= 0.05 ? destination
				: here.y < cruise - 0.1 ? new Vec3d(here.x, cruise, here.z)
				: new Vec3d(destination.x, cruise, destination.z);
		Vec3d delta = aim.subtract(here);
		double distance = delta.length();
		Vec3d step = distance <= SPEED ? delta : delta.multiply(SPEED / distance);
		setVelocity(step);
		setPosition(here.x + step.x, here.y + step.y, here.z + step.z);
		if (step.horizontalLengthSquared() > 1e-4) setYaw((float) (MathHelper.atan2(step.z, step.x) * 180 / Math.PI) - 90);
		velocityDirty = true;
		return false;
	}

	/** Drops the drone and whatever it carries where it is. */
	private void land(ServerWorld world) {
		dropStack(new ItemStack(RcItems.ITEMS.get("maintenance_drone")));
		if (!carried.isEmpty()) dropStack(carried);
		carried = ItemStack.EMPTY;
		discard();
	}

	private void clientTick() {
		if (lerpSteps > 0) {
			double x = getX() + (lerpX - getX()) / lerpSteps;
			double y = getY() + (lerpY - getY()) / lerpSteps;
			double z = getZ() + (lerpZ - getZ()) / lerpSteps;
			setYaw(getYaw() + MathHelper.wrapDegrees(lerpYaw - getYaw()) / lerpSteps);
			lerpSteps--;
			setPosition(x, y, z);
		}
	}

	@Override
	public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int interpolationSteps,
			boolean interpolate) {
		lerpX = x;
		lerpY = y;
		lerpZ = z;
		lerpYaw = yaw;
		lerpSteps = Math.max(3, interpolationSteps);
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		if (isInvulnerableTo(source)) return false;
		if (!getWorld().isClient && getWorld() instanceof ServerWorld world && source.getAttacker() instanceof PlayerEntity) {
			land(world);
		}
		return true;
	}

	@Override
	public boolean canHit() { return isAlive(); }

	@Override
	public boolean isCollidable() { return false; }

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		dock = BlockPos.fromLong(nbt.getLong("Dock"));
		target = BlockPos.fromLong(nbt.getLong("Target"));
		job = DroneDocks.Job.values()[MathHelper.clamp(nbt.getInt("Job"), 0, DroneDocks.Job.values().length - 1)];
		slot = nbt.getInt("Slot");
		carried = nbt.contains("Carried") ? ItemStack.fromNbt(nbt.getCompound("Carried")) : ItemStack.EMPTY;
		phase = Phase.values()[MathHelper.clamp(nbt.getInt("Phase"), 0, Phase.values().length - 1)];
		dataTracker.set(WORKING, phase == Phase.WORKING);
		workTicks = nbt.getInt("WorkTicks");
		flightTicks = nbt.getInt("FlightTicks");
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putLong("Dock", dock.asLong());
		nbt.putLong("Target", target.asLong());
		nbt.putInt("Job", job.ordinal());
		nbt.putInt("Slot", slot);
		if (!carried.isEmpty()) nbt.put("Carried", carried.writeNbt(new NbtCompound()));
		nbt.putInt("Phase", phase.ordinal());
		nbt.putInt("WorkTicks", workTicks);
		nbt.putInt("FlightTicks", flightTicks);
	}

	public int slot() { return slot; }

	@Override
	public Packet<ClientPlayPacketListener> createSpawnPacket() {
		return new EntitySpawnS2CPacket(this);
	}
}
