package dev.rackcraft.entity;

import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.world.HydrogenTanks;
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
 * A Tanker Drone on a delivery: it leaves its Hydrogen Tank with up to a stack of canisters, flies high and fast to a
 * Drone Dock, Site Planner or Launch Control that is short of hydrogen, tops up its hydrogen slot, and flies home, where
 * anything it couldn't deliver goes back into the tank. Hit it and it drops, with its load.
 */
public final class TankerDroneEntity extends Entity {
	public static final double SPEED = 0.9;
	private static final int UNLOAD_TICKS = 30;
	private static final int MAX_AGE = 20 * 600;

	public enum Phase { OUTBOUND, UNLOADING, RETURNING }

	private static final net.minecraft.entity.data.TrackedData<Boolean> WORKING = net.minecraft.entity.data.DataTracker.registerData(
			TankerDroneEntity.class, net.minecraft.entity.data.TrackedDataHandlerRegistry.BOOLEAN);

	private BlockPos home = BlockPos.ORIGIN;
	private BlockPos target = BlockPos.ORIGIN;
	private ItemStack cargo = ItemStack.EMPTY;
	private Phase phase = Phase.OUTBOUND;
	private int workTicks;
	private int flightTicks;
	private double cruise = Double.NaN;
	private int lerpSteps;
	private double lerpX;
	private double lerpY;
	private double lerpZ;
	private float lerpYaw;

	public TankerDroneEntity(EntityType<? extends TankerDroneEntity> type, World world) {
		super(type, world);
		noClip = true;
		setNoGravity(true);
	}

	public static TankerDroneEntity launch(ServerWorld world, BlockPos home, BlockPos target, ItemStack cargo) {
		TankerDroneEntity drone = new TankerDroneEntity(RcEntities.TANKER_DRONE, world);
		drone.home = home;
		drone.target = target;
		drone.cargo = cargo;
		drone.refreshPositionAndAngles(home.getX() + 0.5, home.getY() + 1.1, home.getZ() + 0.5, 0, 0);
		world.spawnEntity(drone);
		return drone;
	}

	public BlockPos home() { return home; }
	public BlockPos target() { return target; }
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

	/** One tick of flight and delivery. The self-test calls it directly. */
	public void serverTick() {
		if (!(getWorld() instanceof ServerWorld world)) return;
		if (++flightTicks > MAX_AGE) {
			land();
			return;
		}
		switch (phase) {
			case OUTBOUND -> {
				if (fly(Vec3d.ofCenter(target).add(0, 1.0, 0))) {
					phase = Phase.UNLOADING;
					workTicks = 0;
					dataTracker.set(WORKING, true);
					world.playSound(null, target, SoundEvents.BLOCK_BREWING_STAND_BREW, SoundCategory.NEUTRAL, 0.6f, 1.6f);
				}
			}
			case UNLOADING -> {
				setVelocity(Vec3d.ZERO);
				if (++workTicks % 5 == 0) {
					world.spawnParticles(ParticleTypes.CLOUD, target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5, 2, 0.2, 0.1, 0.2, 0.01);
				}
				if (workTicks >= UNLOAD_TICKS) {
					cargo = HydrogenTanks.deliver(world, target, cargo);
					phase = Phase.RETURNING;
					cruise = Double.NaN;
					dataTracker.set(WORKING, false);
				}
			}
			case RETURNING -> {
				if (!fly(Vec3d.ofCenter(home).add(0, 1.0, 0))) return;
				if (world.getBlockEntity(home) instanceof MachineBlockEntity tank && tank.blockId().equals("hydrogen_tank")) {
					HydrogenTanks.dockTanker(world, tank, cargo);
					discard();
				} else {
					land();
				}
			}
		}
	}

	/** High and fast: climbs well clear of anything, crosses, and drops onto the destination. */
	private boolean fly(Vec3d destination) {
		Vec3d here = getPos();
		if (destination.distanceTo(here) <= SPEED) {
			setPosition(destination.x, destination.y, destination.z);
			setVelocity(Vec3d.ZERO);
			return true;
		}
		double horizontal = Math.hypot(destination.x - here.x, destination.z - here.z);
		if (Double.isNaN(cruise)) cruise = Math.max(here.y, destination.y) + 12;
		Vec3d aim = horizontal <= 0.1 ? destination
				: here.y < cruise - 0.1 ? new Vec3d(here.x, cruise, here.z) : new Vec3d(destination.x, cruise, destination.z);
		Vec3d delta = aim.subtract(here);
		double distance = delta.length();
		Vec3d step = distance <= SPEED ? delta : delta.multiply(SPEED / distance);
		setVelocity(step);
		setPosition(here.x + step.x, here.y + step.y, here.z + step.z);
		if (step.horizontalLengthSquared() > 1e-4) setYaw((float) (MathHelper.atan2(step.z, step.x) * 180 / Math.PI) - 90);
		velocityDirty = true;
		return false;
	}

	private void land() {
		dropStack(new ItemStack(RcItems.ITEMS.get("tanker_drone")));
		if (!cargo.isEmpty()) dropStack(cargo);
		cargo = ItemStack.EMPTY;
		discard();
	}

	private void clientTick() {
		if (lerpSteps > 0) {
			setYaw(getYaw() + MathHelper.wrapDegrees(lerpYaw - getYaw()) / lerpSteps);
			setPosition(getX() + (lerpX - getX()) / lerpSteps, getY() + (lerpY - getY()) / lerpSteps, getZ() + (lerpZ - getZ()) / lerpSteps);
			lerpSteps--;
		}
	}

	@Override
	public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int interpolationSteps, boolean interpolate) {
		lerpX = x;
		lerpY = y;
		lerpZ = z;
		lerpYaw = yaw;
		lerpSteps = Math.max(3, interpolationSteps);
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		if (isInvulnerableTo(source)) return false;
		if (!getWorld().isClient && source.getAttacker() instanceof PlayerEntity) land();
		return true;
	}

	@Override
	public boolean canHit() { return isAlive(); }

	@Override
	public boolean isCollidable() { return false; }

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		home = BlockPos.fromLong(nbt.getLong("Home"));
		target = BlockPos.fromLong(nbt.getLong("Target"));
		cargo = nbt.contains("Cargo") ? ItemStack.fromNbt(nbt.getCompound("Cargo")) : ItemStack.EMPTY;
		phase = Phase.values()[MathHelper.clamp(nbt.getInt("Phase"), 0, Phase.values().length - 1)];
		workTicks = nbt.getInt("WorkTicks");
		flightTicks = nbt.getInt("FlightTicks");
		dataTracker.set(WORKING, phase == Phase.UNLOADING);
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putLong("Home", home.asLong());
		nbt.putLong("Target", target.asLong());
		if (!cargo.isEmpty()) nbt.put("Cargo", cargo.writeNbt(new NbtCompound()));
		nbt.putInt("Phase", phase.ordinal());
		nbt.putInt("WorkTicks", workTicks);
		nbt.putInt("FlightTicks", flightTicks);
	}

	@Override
	public Packet<ClientPlayPacketListener> createSpawnPacket() {
		return new EntitySpawnS2CPacket(this);
	}
}
