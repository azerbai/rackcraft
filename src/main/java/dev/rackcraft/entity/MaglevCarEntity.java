package dev.rackcraft.entity;

import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.world.MaglevNetwork;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A Mag-Lev car: one seat, gliding along a line of rails and stations from the Station it started at to its destination.
 * It slows with the share of its load (800 kW) the starting Station's network can supply, stalls under 10%, and stops
 * where a rail is missing or cut. Cars are never saved: one that outlives its server is gone at reload.
 */
public final class MaglevCarEntity extends Entity {
	public static final double MIN_POWER = 0.1;
	/** How high above the top of a track block the car runs. */
	private static final double RIDE_HEIGHT = 1.05;

	private List<BlockPos> path;
	private BlockPos origin = BlockPos.ORIGIN;
	private int index;
	private int stalled;
	private boolean released;

	public MaglevCarEntity(EntityType<? extends MaglevCarEntity> type, World world) {
		super(type, world);
		noClip = true;
		setNoGravity(true);
	}

	public static MaglevCarEntity launch(ServerWorld world, List<BlockPos> route, BlockPos origin) {
		MaglevCarEntity car = new MaglevCarEntity(RcEntities.MAGLEV_CAR, world);
		car.path = route;
		car.origin = origin;
		Vec3d start = rideAt(route.get(0));
		car.refreshPositionAndAngles(start.x, start.y, start.z, car.headingTo(route.get(1)), 0);
		world.spawnEntity(car);
		MaglevNetwork.carStarted(world, origin);
		return car;
	}

	private static Vec3d rideAt(BlockPos pos) {
		return new Vec3d(pos.getX() + 0.5, pos.getY() + RIDE_HEIGHT - 0.5, pos.getZ() + 0.5);
	}

	private float headingTo(BlockPos next) {
		BlockPos from = path.get(Math.max(0, Math.min(index, path.size() - 1)));
		int dx = next.getX() - from.getX();
		int dz = next.getZ() - from.getZ();
		return dx == 0 && dz == 0 ? getYaw() : (float) (MathHelper.atan2(dz, dx) * 180 / Math.PI) - 90;
	}

	@Override
	protected void initDataTracker() {}

	@Override
	public void tick() {
		super.tick();
		if (getWorld().isClient) return;
		serverTick();
	}

	/** One tick of the run. The self-test calls it directly. */
	public void serverTick() {
		if (!(getWorld() instanceof ServerWorld world) || isRemoved()) return;
		Entity rider = getFirstPassenger();
		if (path == null || rider == null) {
			discard();
			return;
		}
		if (index >= path.size() - 1) {
			arrive(world, rider);
			return;
		}
		BlockPos next = path.get(index + 1);
		if (!MaglevNetwork.track(world, next)) {
			tell(rider, "The line is broken ahead: the car has stopped", Formatting.RED);
			stopAndRelease(rider);
			return;
		}
		double supplied = world.getBlockEntity(origin) instanceof MachineBlockEntity station ? Math.min(1, station.powerSatisfaction()) : 0;
		if (supplied < MIN_POWER) {
			if (stalled++ % 100 == 0) tell(rider, "The train has stopped. This is fine.", Formatting.GOLD);
			setVelocity(Vec3d.ZERO);
			return;
		}
		stalled = 0;
		double remaining = MaglevNetwork.SPEED * supplied;
		Vec3d here = getPos();
		Vec3d moved = Vec3d.ZERO;
		while (remaining > 1e-6 && index < path.size() - 1) {
			Vec3d target = rideAt(path.get(index + 1));
			double distance = target.distanceTo(here);
			if (distance <= remaining) {
				moved = moved.add(target.subtract(here));
				here = target;
				remaining -= distance;
				index++;
				if (index < path.size() - 1 && !MaglevNetwork.track(world, path.get(index + 1))) break;
			} else {
				Vec3d step = target.subtract(here).normalize().multiply(remaining);
				moved = moved.add(step);
				here = here.add(step);
				remaining = 0;
			}
		}
		setPosition(here);
		setVelocity(moved);
		velocityDirty = true;
		if (index < path.size() - 1) setYaw(headingTo(path.get(index + 1)));
	}

	private void arrive(ServerWorld world, Entity rider) {
		BlockPos end = path.get(path.size() - 1);
		rider.stopRiding();
		rider.requestTeleport(end.getX() + 0.5, end.getY() + 1.0, end.getZ() + 0.5);
		if (rider instanceof PlayerEntity player) player.fallDistance = 0;
		discard();
	}

	private void stopAndRelease(Entity rider) {
		setVelocity(Vec3d.ZERO);
		rider.stopRiding();
		discard();
	}

	private static void tell(Entity rider, String message, Formatting color) {
		if (rider instanceof PlayerEntity player) player.sendMessage(Text.literal(message).formatted(color), true);
	}

	@Override
	public void remove(RemovalReason reason) {
		if (!released && getWorld() instanceof ServerWorld world && path != null) {
			released = true;
			MaglevNetwork.carEnded(world, origin);
		}
		super.remove(reason);
	}

	public int progress() { return index; }

	public BlockPos origin() { return origin; }

	@Override
	protected boolean canAddPassenger(Entity passenger) { return getPassengerList().isEmpty(); }

	@Override
	public double getMountedHeightOffset() { return 0.3; }

	@Override
	public boolean isPushable() { return false; }

	@Override
	public boolean canHit() { return false; }

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {}

	@Override
	public Packet<ClientPlayPacketListener> createSpawnPacket() {
		return new EntitySpawnS2CPacket(this);
	}
}
