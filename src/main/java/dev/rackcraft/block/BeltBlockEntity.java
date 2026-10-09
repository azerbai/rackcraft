package dev.rackcraft.block;

import dev.rackcraft.RcBlocks;
import dev.rackcraft.world.AssemblyLine;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * One Conveyor Belt: it carries a single item from its back edge (progress 0) to its front edge (1), a block a second,
 * and then hands it on. A Robot Arm facing the belt can claim the item as it reaches the middle; the belt holds it
 * there until no arm wants it any more. The client runs the same motion between syncs so items glide.
 */
public final class BeltBlockEntity extends BlockEntity implements net.minecraft.inventory.SidedInventory {
	/** Progress per tick: one block a second. */
	public static final double SPEED = 0.05;
	public static final double MIDDLE = 0.5;
	private ItemStack stack = ItemStack.EMPTY;
	private double progress;
	private double previousProgress;
	/** The arm holding the item in the middle, or null while it moves. */
	private BlockPos heldBy;
	/** Whether the item has already been offered to the arms on this belt (so it isn't claimed twice). */
	private boolean offered;
	/** Progress per tick right now: {@link #SPEED} times the line speed research. Synced so the client glides at the same rate. */
	private double speed = SPEED;

	public BeltBlockEntity(BlockPos pos, BlockState state) {
		super(RcBlocks.BELT_ENTITY, pos, state);
	}

	public ItemStack stack() { return stack; }
	public double progress() { return progress; }
	public double speed() { return speed; }
	public double progress(float tickDelta) { return previousProgress + (progress - previousProgress) * tickDelta; }
	public BlockPos heldBy() { return heldBy; }
	public Direction facing() { return getCachedState().get(ConveyorBeltBlock.FACING); }

	/** Puts an item on the belt at this point along it. Only one item rides a belt at a time. */
	public boolean accept(ItemStack item, double at) {
		return acceptWhole(item.copyWithCount(1), at);
	}

	/**
	 * Puts this whole stack on the belt: what a robot made comes off as one stack (a wafer's chiplets) and travels on
	 * together, belt to belt.
	 */
	private boolean acceptWhole(ItemStack item, double at) {
		if (!stack.isEmpty() || item.isEmpty()) return false;
		stack = item.copy();
		progress = Math.max(0, Math.min(1, at));
		previousProgress = progress;
		heldBy = null;
		offered = progress > MIDDLE;
		changed();
		return true;
	}

	/** Swaps the item in place (an arm finishing a step), keeping it held where it is. */
	public void replace(ItemStack item) {
		stack = item;
		changed();
	}

	public ItemStack take() {
		ItemStack taken = stack;
		stack = ItemStack.EMPTY;
		heldBy = null;
		progress = 0;
		previousProgress = 0;
		changed();
		return taken;
	}

	public static void tick(World world, BlockPos pos, BlockState state, BeltBlockEntity belt) {
		belt.previousProgress = belt.progress;
		if (world instanceof ServerWorld server) belt.serverTick(server);
		else if (!belt.stack.isEmpty() && belt.heldBy == null) belt.progress = Math.min(1, belt.progress + belt.speed);
	}

	/** One tick on the server: pick up a dropped item, move, let an arm claim the item, or hand it on. */
	public void serverTick(ServerWorld world) {
		if (world.getTime() % 20 == 0) {
			double now = SPEED * AssemblyLine.lineSpeed(world);
			if (now != speed) {
				speed = now;
				changed();
			}
		}
		if (stack.isEmpty()) {
			if (world.getTime() % 4 == 0) pickUp(world);
			return;
		}
		if (heldBy != null) {
			// Held while the arm can still work on it; another arm on the same belt may take over for the next step.
			BlockPos arm = AssemblyLine.claimant(world, pos, stack, heldBy);
			if (arm == null) {
				heldBy = null;
				changed();
			} else if (!arm.equals(heldBy)) {
				heldBy = arm;
				changed();
			}
			return;
		}
		double next = Math.min(1, progress + speed);
		if (!offered && progress <= MIDDLE && next >= MIDDLE) {
			offered = true;
			BlockPos arm = AssemblyLine.claimant(world, pos, stack, null);
			if (arm != null) {
				progress = MIDDLE;
				heldBy = arm;
				changed();
				return;
			}
		}
		progress = next;
		if (progress >= 1) handOn(world);
	}

	private void handOn(ServerWorld world) {
		Direction facing = facing();
		BlockPos target = pos.offset(facing);
		if (world.getBlockEntity(target) instanceof BeltBlockEntity next) {
			if (next.facing() == facing.getOpposite()) return;
			// Straight on enters at the back edge; onto a belt that turns, it lands in the middle, as if fed from the side.
			if (next.acceptWhole(stack, next.facing() == facing ? 0 : MIDDLE)) take();
			return;
		}
		Inventory inventory = HopperBlockEntity.getInventoryAt(world, target);
		if (inventory != null) {
			ItemStack left = HopperBlockEntity.transfer(null, inventory, stack.copy(), facing.getOpposite());
			if (left.isEmpty()) take();
			return;
		}
		if (world.getBlockState(target).getCollisionShape(world, target).isEmpty()) {
			ItemEntity dropped = new ItemEntity(world, pos.getX() + 0.5 + facing.getOffsetX() * 0.7, pos.getY() + 0.3,
					pos.getZ() + 0.5 + facing.getOffsetZ() * 0.7, take(), facing.getOffsetX() * 0.1, 0.05, facing.getOffsetZ() * 0.1);
			world.spawnEntity(dropped);
		}
	}

	/** Items dropped onto the belt ride it, one at a time. */
	private void pickUp(ServerWorld world) {
		Box above = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 0.75, pos.getZ() + 1);
		List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class, above, item -> item.isAlive() && !item.getStack().isEmpty());
		if (items.isEmpty()) return;
		ItemEntity item = items.get(0);
		Direction facing = facing();
		double along = (item.getX() - pos.getX() - 0.5) * facing.getOffsetX() + (item.getZ() - pos.getZ() - 0.5) * facing.getOffsetZ();
		if (accept(item.getStack(), along + 0.5)) {
			item.getStack().decrement(1);
			if (item.getStack().isEmpty()) item.discard();
		}
	}

	private void changed() {
		markDirty();
		if (world != null && !world.isClient) world.updateListeners(pos, getCachedState(), getCachedState(), 3);
	}

	@Override
	public void readNbt(NbtCompound nbt) {
		super.readNbt(nbt);
		stack = nbt.contains("Item") ? ItemStack.fromNbt(nbt.getCompound("Item")) : ItemStack.EMPTY;
		progress = nbt.getDouble("Progress");
		previousProgress = progress;
		offered = nbt.getBoolean("Offered");
		speed = nbt.contains("Speed") ? nbt.getDouble("Speed") : SPEED;
		heldBy = nbt.contains("HeldBy") ? BlockPos.fromLong(nbt.getLong("HeldBy")) : null;
	}

	@Override
	protected void writeNbt(NbtCompound nbt) {
		super.writeNbt(nbt);
		if (!stack.isEmpty()) nbt.put("Item", stack.writeNbt(new NbtCompound()));
		nbt.putDouble("Progress", progress);
		nbt.putBoolean("Offered", offered);
		nbt.putDouble("Speed", speed);
		if (heldBy != null) nbt.putLong("HeldBy", heldBy.asLong());
	}

	@Override
	public Packet<ClientPlayPacketListener> toUpdatePacket() {
		return BlockEntityUpdateS2CPacket.create(this);
	}

	@Override
	public NbtCompound toInitialChunkDataNbt() {
		return createNbt();
	}

	// One slot, so hoppers and Item Pipes can load a belt. Nothing comes out the bottom: items leave by the front.
	private static final int[] SLOTS = {0};

	@Override public int[] getAvailableSlots(Direction side) { return SLOTS; }
	@Override public boolean canInsert(int slot, ItemStack item, Direction side) { return stack.isEmpty(); }
	@Override public boolean canExtract(int slot, ItemStack item, Direction side) { return false; }
	@Override public int size() { return 1; }
	@Override public boolean isEmpty() { return stack.isEmpty(); }
	@Override public ItemStack getStack(int slot) { return stack; }
	@Override public ItemStack removeStack(int slot, int amount) { return stack.isEmpty() ? ItemStack.EMPTY : take(); }
	@Override public ItemStack removeStack(int slot) { return stack.isEmpty() ? ItemStack.EMPTY : take(); }
	@Override public int getMaxCountPerStack() { return 1; }

	@Override
	public void setStack(int slot, ItemStack item) {
		if (item.isEmpty()) {
			take();
			return;
		}
		if (stack.isEmpty()) accept(item, 0);
		else replace(item.copyWithCount(1));
	}

	@Override public boolean canPlayerUse(net.minecraft.entity.player.PlayerEntity player) { return false; }
	@Override public void clear() { take(); }
}
