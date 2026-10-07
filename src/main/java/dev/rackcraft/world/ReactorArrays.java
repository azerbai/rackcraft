package dev.rackcraft.world;

import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Cube multiblocks. Any machine in {@code ContentIds.ARRAY_IDS} built into a solid cube of the same machine,
 * 2x2x2 up to 5x5x5, works as one: every block is a core, items in their slots are shared evenly across the
 * cube, and bigger cubes are more economical (5% less per core for a 2-cube, up to 20% for a 5-cube).
 *
 * <p>Modular Reactors make {@link #CORE_KW} each and burn a Fuel Cell every {@link #FUEL_CELL_TICKS} ticks at
 * full output (less at part load). Every burnt-out cell becomes Spent Fuel in slot 1; when the array's waste
 * slots are full it can't load the next cell. A lone reactor runs on its own; the nuclear processing machines
 * only work as a cube.
 */
public final class ReactorArrays {
	public static final double CORE_KW = 500;
	public static final int FUEL_CELL_TICKS = 36000;
	public static final int MAX_EDGE = 5;
	/** Spent Fuel each core's waste slot holds. */
	public static final int WASTE_PER_CORE = 16;
	public static final int FUEL_SLOT = 0;
	public static final int WASTE_SLOT = 1;

	/** What a reactor is doing, for its screen and the Ops Terminal. */
	public enum ReactorStatus { RUNNING, NO_FUEL, WASTE_FULL, STANDBY }
	private static final Map<MachineBlockEntity, Double> BURN_REMAINDER = new WeakHashMap<>();

	private ReactorArrays() {}

	/** A reactor array (or a lone reactor, {@code edge} 1). The controller holds the shared fuel clock. */
	public record Array(MachineBlockEntity controller, List<MachineBlockEntity> members, int edge) {
		public int cores() { return members.size(); }
		public double capacityKw() { return cores() * CORE_KW; }
		public double fuelUse() { return efficiency(edge); }

		/** Fuel Cells waiting in the cores' slots. */
		public int fuelCells() {
			int cells = 0;
			for (MachineBlockEntity member : members) {
				ItemStack stack = member.getStack(0);
				if (isFuel(stack)) cells += stack.getCount();
			}
			return cells;
		}
	}

	/** Fuel each core of an array this size burns, relative to a lone reactor. */
	public static double efficiency(int edge) {
		return edge <= 1 ? 1 : 1 - 0.05 * (Math.min(MAX_EDGE, edge) - 1);
	}

	/** Groups this step's reactors into arrays; every reactor maps to the array it runs in. */
	public static Map<MachineBlockEntity, Array> scan(List<MachineBlockEntity> machines) {
		return scan(machines, "modular_reactor");
	}

	/** Every array machine, by kind, grouped into its cubes (or left on its own). */
	public static Map<MachineBlockEntity, Array> scanAll(List<MachineBlockEntity> machines) {
		Map<MachineBlockEntity, Array> all = new HashMap<>();
		for (String id : dev.rackcraft.generated.ContentIds.ARRAY_IDS) all.putAll(scan(machines, id));
		return all;
	}

	/** Groups this step's machines of one kind into cubes; every one maps to the array it runs in. */
	public static Map<MachineBlockEntity, Array> scan(List<MachineBlockEntity> machines, String id) {
		Map<BlockPos, MachineBlockEntity> reactors = new HashMap<>();
		for (MachineBlockEntity machine : machines) {
			if (machine.blockId().equals(id)) reactors.put(machine.getPos(), machine);
		}
		Map<MachineBlockEntity, Array> arrays = new HashMap<>();
		for (MachineBlockEntity start : reactors.values()) {
			if (arrays.containsKey(start)) continue;
			List<MachineBlockEntity> group = new ArrayList<>();
			ArrayDeque<MachineBlockEntity> queue = new ArrayDeque<>(List.of(start));
			java.util.Set<MachineBlockEntity> seen = new java.util.HashSet<>(List.of(start));
			while (!queue.isEmpty() && group.size() <= MAX_EDGE * MAX_EDGE * MAX_EDGE) {
				MachineBlockEntity current = queue.poll();
				group.add(current);
				for (Direction direction : Direction.values()) {
					MachineBlockEntity next = reactors.get(current.getPos().offset(direction));
					if (next != null && seen.add(next)) queue.add(next);
				}
			}
			int edge = cubeEdge(group);
			if (edge >= 2 && queue.isEmpty()) {
				group.sort(Comparator.comparingInt((MachineBlockEntity member) -> member.getPos().getY())
						.thenComparingInt(member -> member.getPos().getZ()).thenComparingInt(member -> member.getPos().getX()));
				Array array = new Array(group.get(0), List.copyOf(group), edge);
				for (MachineBlockEntity member : group) arrays.put(member, array);
			} else {
				for (MachineBlockEntity member : seen) arrays.put(member, new Array(member, List.of(member), 1));
			}
		}
		return arrays;
	}

	/** The edge length if these reactors fill a cube of 2 to 5 exactly, otherwise 0. */
	private static int cubeEdge(List<MachineBlockEntity> group) {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (MachineBlockEntity member : group) {
			BlockPos pos = member.getPos();
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX());
			maxY = Math.max(maxY, pos.getY());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		int edge = maxX - minX + 1;
		boolean cube = maxY - minY + 1 == edge && maxZ - minZ + 1 == edge && group.size() == edge * edge * edge;
		return cube && edge >= 2 && edge <= MAX_EDGE ? edge : 0;
	}

	public static boolean isFuel(ItemStack stack) {
		return !stack.isEmpty() && stack.isOf(RcItems.ITEMS.get("fuel_cell"));
	}

	/**
	 * Lights the next Fuel Cell if the array's current one is spent. Returns whether it has fuel to run on; it
	 * doesn't when there's no cell, or when the last cell's Spent Fuel has nowhere to go.
	 */
	public static boolean refuel(Array array) {
		MachineBlockEntity controller = array.controller();
		if (controller.fuelBurnTicks() > 0) return true;
		while (controller.pendingWaste() > 0 && put(array.members(), WASTE_SLOT, spentFuel(), 1, WASTE_PER_CORE) == 1) {
			controller.setPendingWaste(controller.pendingWaste() - 1);
		}
		if (controller.pendingWaste() > 0) return false;
		for (MachineBlockEntity member : array.members()) {
			ItemStack stack = member.getStack(FUEL_SLOT);
			if (!isFuel(stack)) continue;
			stack.decrement(1);
			member.markDirty();
			controller.startFuel(FUEL_CELL_TICKS);
			return true;
		}
		return false;
	}

	public static net.minecraft.item.Item spentFuel() {
		return RcItems.ITEMS.get("spent_fuel");
	}

	/** Why a reactor array isn't running, or that it is. */
	public static ReactorStatus status(Array array, double outputKw) {
		MachineBlockEntity controller = array.controller();
		if (controller.pendingWaste() > 0) return ReactorStatus.WASTE_FULL;
		if (outputKw > 0) return ReactorStatus.RUNNING;
		if (controller.fuelBurnTicks() <= 0 && array.fuelCells() == 0) return ReactorStatus.NO_FUEL;
		return ReactorStatus.STANDBY;
	}

	/**
	 * Spreads whatever is in this slot evenly over the array's cores (only when every core holds the same item or
	 * nothing). Returns whether anything moved.
	 */
	public static boolean pool(List<MachineBlockEntity> members, int slot) {
		if (members.size() < 2) return false;
		ItemStack sample = ItemStack.EMPTY;
		int total = 0;
		int most = 0;
		int least = Integer.MAX_VALUE;
		for (MachineBlockEntity member : members) {
			ItemStack stack = member.getStack(slot);
			if (!stack.isEmpty()) {
				if (sample.isEmpty()) sample = stack;
				else if (!ItemStack.canCombine(sample, stack)) return false;
			}
			total += stack.getCount();
			most = Math.max(most, stack.getCount());
			least = Math.min(least, stack.getCount());
		}
		if (sample.isEmpty() || most - least <= 1) return false;
		ItemStack template = sample.copyWithCount(1);
		int share = total / members.size();
		int extra = total % members.size();
		for (int index = 0; index < members.size(); index++) {
			int count = share + (index < extra ? 1 : 0);
			members.get(index).setStack(slot, count == 0 ? ItemStack.EMPTY : template.copyWithCount(count));
		}
		return true;
	}

	/** How many of this item the array's cores hold in this slot. */
	public static int count(List<MachineBlockEntity> members, int slot, net.minecraft.item.Item item) {
		int total = 0;
		for (MachineBlockEntity member : members) {
			ItemStack stack = member.getStack(slot);
			if (stack.isOf(item)) total += stack.getCount();
		}
		return total;
	}

	/** Takes up to {@code amount} from the fullest cores first. Returns how many it took. */
	public static int take(List<MachineBlockEntity> members, int slot, net.minecraft.item.Item item, int amount) {
		int taken = 0;
		List<MachineBlockEntity> order = new ArrayList<>(members);
		order.sort(Comparator.comparingInt((MachineBlockEntity member) -> -member.getStack(slot).getCount()));
		for (MachineBlockEntity member : order) {
			ItemStack stack = member.getStack(slot);
			if (!stack.isOf(item) || taken >= amount) continue;
			int moved = Math.min(stack.getCount(), amount - taken);
			stack.decrement(moved);
			member.markDirty();
			taken += moved;
		}
		return taken;
	}

	/** Room for this item in this slot across the cores, at most {@code limit} per core. */
	public static int room(List<MachineBlockEntity> members, int slot, net.minecraft.item.Item item, int limit) {
		int room = 0;
		for (MachineBlockEntity member : members) {
			ItemStack stack = member.getStack(slot);
			int cap = Math.min(limit, item.getMaxCount());
			if (stack.isEmpty()) room += cap;
			else if (stack.isOf(item)) room += Math.max(0, cap - stack.getCount());
		}
		return room;
	}

	/** Puts up to {@code amount} into the emptiest cores first, at most {@code limit} per core. Returns how many fit. */
	public static int put(List<MachineBlockEntity> members, int slot, net.minecraft.item.Item item, int amount, int limit) {
		int placed = 0;
		while (placed < amount) {
			MachineBlockEntity target = null;
			for (MachineBlockEntity member : members) {
				ItemStack stack = member.getStack(slot);
				if (!stack.isEmpty() && !stack.isOf(item)) continue;
				if (stack.getCount() >= Math.min(limit, item.getMaxCount())) continue;
				if (target == null || stack.getCount() < target.getStack(slot).getCount()) target = member;
			}
			if (target == null) break;
			ItemStack stack = target.getStack(slot);
			if (stack.isEmpty()) target.setStack(slot, new ItemStack(item));
			else {
				stack.increment(1);
				target.markDirty();
			}
			placed++;
		}
		return placed;
	}

	/** Burns fuel for one step at this output. */
	public static void burn(Array array, double outputKw, int stepTicks) {
		if (outputKw <= 0) return;
		MachineBlockEntity controller = array.controller();
		double ticks = stepTicks * array.cores() * Math.min(1, outputKw / array.capacityKw()) * array.fuelUse()
				+ BURN_REMAINDER.getOrDefault(controller, 0.0);
		int whole = (int) ticks;
		BURN_REMAINDER.put(controller, ticks - whole);
		boolean burning = controller.fuelBurnTicks() > 0;
		controller.setFuelBurnTicks(controller.fuelBurnTicks() - whole);
		// The cell is spent: it comes out as Spent Fuel, or waits until there's room for it.
		if (burning && controller.fuelBurnTicks() <= 0 && put(array.members(), WASTE_SLOT, spentFuel(), 1, WASTE_PER_CORE) == 0) {
			controller.setPendingWaste(controller.pendingWaste() + 1);
		}
		controller.markDirty();
	}
}
