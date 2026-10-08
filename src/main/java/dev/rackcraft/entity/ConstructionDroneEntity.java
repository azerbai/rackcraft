package dev.rackcraft.entity;

import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.world.SitePlanner;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A Construction Drone or a Terraforming Drone on one trip from its Site Planner: a list of stops (clear this, dig that,
 * fill here, place a tower section there), done in order, then home. Between stops far apart it climbs, crosses and
 * drops like a Maintenance Drone; between neighbouring stops (up a tower, along a cable run) it just moves across. It
 * carries its cargo in a hold of 27 stacks; whatever it digs up goes in the hold too and comes home to storage. Hit it
 * and it lands, dropping itself and its hold.
 */
public final class ConstructionDroneEntity extends Entity {
	public static final double SPEED = 0.45;
	private static final int MAX_AGE = 20 * 900;

	public enum Phase { FLYING, WORKING, RETURNING }

	private static final TrackedData<Boolean> TERRAFORMER = DataTracker.registerData(ConstructionDroneEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> WORKING = DataTracker.registerData(ConstructionDroneEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	/** The block it is carrying, for the renderer to hang under it. */
	private static final TrackedData<ItemStack> SHOWN = DataTracker.registerData(ConstructionDroneEntity.class, TrackedDataHandlerRegistry.ITEM_STACK);

	private BlockPos home = BlockPos.ORIGIN;
	private final List<SitePlanner.Step> steps = new ArrayList<>();
	private int stepIndex;
	private final SimpleInventory hold = new SimpleInventory(27);
	private Phase phase = Phase.FLYING;
	private int workTicks;
	private int flightTicks;
	/** The height this leg of the flight crosses at, or NaN for a short hop straight across. */
	private double cruise = Double.NaN;
	private int lerpSteps;
	private double lerpX;
	private double lerpY;
	private double lerpZ;
	private float lerpYaw;

	public ConstructionDroneEntity(EntityType<? extends ConstructionDroneEntity> type, World world) {
		super(type, world);
		noClip = true;
		setNoGravity(true);
	}

	public static ConstructionDroneEntity launch(ServerWorld world, BlockPos home, boolean terraformer, List<SitePlanner.Step> steps,
			List<ItemStack> cargo) {
		ConstructionDroneEntity drone = new ConstructionDroneEntity(RcEntities.CONSTRUCTION_DRONE, world);
		drone.home = home;
		drone.dataTracker.set(TERRAFORMER, terraformer);
		drone.steps.addAll(steps);
		for (ItemStack stack : cargo) drone.hold.addStack(stack);
		drone.refreshShown();
		drone.refreshPositionAndAngles(home.getX() + 0.5, home.getY() + 1.1, home.getZ() + 0.5, 0, 0);
		world.spawnEntity(drone);
		return drone;
	}

	public BlockPos home() { return home; }
	public boolean terraformer() { return dataTracker.get(TERRAFORMER); }
	public boolean working() { return dataTracker.get(WORKING); }
	public ItemStack shown() { return dataTracker.get(SHOWN); }
	/** The stops still to come, this one included (what other drones mustn't be sent to). */
	public List<SitePlanner.Step> steps() { return steps.subList(Math.min(stepIndex, steps.size()), steps.size()); }

	@Override
	protected void initDataTracker() {
		dataTracker.startTracking(TERRAFORMER, false);
		dataTracker.startTracking(WORKING, false);
		dataTracker.startTracking(SHOWN, ItemStack.EMPTY);
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
			case FLYING -> {
				if (stepIndex >= steps.size()) {
					startLeg(Vec3d.ofCenter(home).add(0, 1.0, 0));
					phase = Phase.RETURNING;
					return;
				}
				if (fly(hover(steps.get(stepIndex)))) {
					phase = Phase.WORKING;
					workTicks = 0;
					dataTracker.set(WORKING, true);
				}
			}
			case WORKING -> {
				setVelocity(Vec3d.ZERO);
				SitePlanner.Step step = steps.get(stepIndex);
				workTicks++;
				if (workTicks == 1) {
					world.playSound(null, step.pos(), terraformer() ? SoundEvents.BLOCK_GRAVEL_HIT : SoundEvents.BLOCK_BEEHIVE_WORK,
							SoundCategory.NEUTRAL, 0.7f, 1.4f);
				}
				if (workTicks % 3 == 0) {
					var state = world.getBlockState(step.pos());
					if (!state.isAir()) {
						world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), step.pos().getX() + 0.5, step.pos().getY() + 0.9,
								step.pos().getZ() + 0.5, 3, 0.25, 0.1, 0.25, 0.05);
					} else {
						world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, step.pos().getX() + 0.5, step.pos().getY() + 0.3, step.pos().getZ() + 0.5,
								2, 0.2, 0.1, 0.2, 0.02);
					}
				}
				if (workTicks >= workTicks(step.action())) {
					SitePlanner.doStep(world, step, hold);
					refreshShown();
					stepIndex++;
					dataTracker.set(WORKING, false);
					phase = Phase.FLYING;
					if (stepIndex < steps.size()) startLeg(hover(steps.get(stepIndex)));
				}
			}
			case RETURNING -> {
				Vec3d dock = Vec3d.ofCenter(home).add(0, 1.0, 0);
				if (!fly(dock)) return;
				if (world.getBlockEntity(home) instanceof MachineBlockEntity planner && planner.blockId().equals("site_planner")) {
					SitePlanner.dockDrone(world, planner, terraformer(), hold);
					discard();
				} else {
					land(world);
				}
			}
		}
	}

	private static int workTicks(SitePlanner.Action action) {
		return switch (action) {
			case CLEAR -> 6;
			case DIG -> 12;
			case FILL -> 8;
			case PLACE -> 5;
			case ARRAY -> 30;
			case CABLE -> 4;
		};
	}

	/** Where the drone hovers to work on a block: just above it. */
	private static Vec3d hover(SitePlanner.Step step) {
		return Vec3d.ofCenter(step.pos()).add(0, 1.0, 0);
	}

	/** Plans a leg: hop straight across to a stop nearby, or climb, cross and drop to one further off. */
	private void startLeg(Vec3d destination) {
		Vec3d here = getPos();
		double horizontal = Math.hypot(destination.x - here.x, destination.z - here.z);
		cruise = horizontal <= 3 ? Double.NaN : Math.max(here.y, destination.y) + 3.5;
	}

	/** Moves toward a point along the current leg. Returns true on arrival. */
	private boolean fly(Vec3d destination) {
		Vec3d here = getPos();
		if (destination.distanceTo(here) <= SPEED) {
			setPosition(destination.x, destination.y, destination.z);
			setVelocity(Vec3d.ZERO);
			return true;
		}
		if (flightTicks == 1 && Double.isNaN(cruise)) startLeg(destination);
		double horizontal = Math.hypot(destination.x - here.x, destination.z - here.z);
		Vec3d aim = Double.isNaN(cruise) || horizontal <= 0.05 ? destination
				: here.y < cruise - 0.1 && horizontal > 0.5 ? new Vec3d(here.x, cruise, here.z)
				: new Vec3d(destination.x, cruise, destination.z);
		if (!Double.isNaN(cruise) && horizontal <= 0.05) cruise = Double.NaN;
		Vec3d delta = aim.subtract(here);
		double distance = delta.length();
		Vec3d step = distance <= SPEED ? delta : delta.multiply(SPEED / distance);
		setVelocity(step);
		setPosition(here.x + step.x, here.y + step.y, here.z + step.z);
		if (step.horizontalLengthSquared() > 1e-4) setYaw((float) (MathHelper.atan2(step.z, step.x) * 180 / Math.PI) - 90);
		velocityDirty = true;
		return false;
	}

	/** Shows the first block in the hold hanging under the drone. */
	private void refreshShown() {
		ItemStack shown = ItemStack.EMPTY;
		for (int slot = 0; slot < hold.size(); slot++) {
			ItemStack stack = hold.getStack(slot);
			if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
				shown = stack.copyWithCount(1);
				break;
			}
		}
		dataTracker.set(SHOWN, shown);
	}

	/** Drops the drone and its hold where it is. */
	private void land(ServerWorld world) {
		dropStack(new ItemStack(RcItems.ITEMS.get(terraformer() ? "terraforming_drone" : "construction_drone")));
		for (int slot = 0; slot < hold.size(); slot++) {
			ItemStack stack = hold.removeStack(slot);
			if (!stack.isEmpty()) dropStack(stack);
		}
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
		if (!getWorld().isClient && getWorld() instanceof ServerWorld world && source.getAttacker() instanceof PlayerEntity) land(world);
		return true;
	}

	@Override
	public boolean canHit() { return isAlive(); }

	@Override
	public boolean isCollidable() { return false; }

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		home = BlockPos.fromLong(nbt.getLong("Home"));
		dataTracker.set(TERRAFORMER, nbt.getBoolean("Terraformer"));
		steps.clear();
		NbtList list = nbt.getList("Steps", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < list.size(); index++) steps.add(SitePlanner.Step.read(list.getCompound(index)));
		stepIndex = nbt.getInt("StepIndex");
		net.minecraft.util.collection.DefaultedList<ItemStack> stacks = net.minecraft.util.collection.DefaultedList.ofSize(hold.size(), ItemStack.EMPTY);
		Inventories.readNbt(nbt.getCompound("Hold"), stacks);
		for (int slot = 0; slot < stacks.size(); slot++) hold.setStack(slot, stacks.get(slot));
		phase = Phase.values()[MathHelper.clamp(nbt.getInt("Phase"), 0, Phase.values().length - 1)];
		workTicks = nbt.getInt("WorkTicks");
		flightTicks = nbt.getInt("FlightTicks");
		cruise = nbt.contains("Cruise") ? nbt.getDouble("Cruise") : Double.NaN;
		dataTracker.set(WORKING, phase == Phase.WORKING);
		refreshShown();
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putLong("Home", home.asLong());
		nbt.putBoolean("Terraformer", terraformer());
		NbtList list = new NbtList();
		for (SitePlanner.Step step : steps) list.add(step.write());
		nbt.put("Steps", list);
		nbt.putInt("StepIndex", stepIndex);
		net.minecraft.util.collection.DefaultedList<ItemStack> stacks = net.minecraft.util.collection.DefaultedList.ofSize(hold.size(), ItemStack.EMPTY);
		for (int slot = 0; slot < hold.size(); slot++) stacks.set(slot, hold.getStack(slot));
		nbt.put("Hold", Inventories.writeNbt(new NbtCompound(), stacks));
		nbt.putInt("Phase", phase.ordinal());
		nbt.putInt("WorkTicks", workTicks);
		nbt.putInt("FlightTicks", flightTicks);
		if (!Double.isNaN(cruise)) nbt.putDouble("Cruise", cruise);
	}

	@Override
	public Packet<ClientPlayPacketListener> createSpawnPacket() {
		return new EntitySpawnS2CPacket(this);
	}
}
