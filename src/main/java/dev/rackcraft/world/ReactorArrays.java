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
 * Modular Reactors on their own make {@link #CORE_KW} each and burn a Fuel Cell every
 * {@link #FUEL_CELL_TICKS} ticks at full output (less at part load). Build them into a solid cube, 2x2x2 up to
 * 5x5x5, and the cube becomes one reactor array: the output of every core in it, one shared fuel supply (any
 * core's slot feeds the array), and better fuel economy the bigger it is: 5% less fuel per core for a 2-cube,
 * up to 20% less for a 5-cube. Anything that isn't a whole cube runs as separate reactors.
 */
public final class ReactorArrays {
	public static final double CORE_KW = 500;
	public static final int FUEL_CELL_TICKS = 36000;
	public static final int MAX_EDGE = 5;
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
		Map<BlockPos, MachineBlockEntity> reactors = new HashMap<>();
		for (MachineBlockEntity machine : machines) {
			if (machine.blockId().equals("modular_reactor")) reactors.put(machine.getPos(), machine);
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

	/** Lights the next Fuel Cell if the array's current one is spent. Returns whether it has fuel to run on. */
	public static boolean refuel(Array array) {
		MachineBlockEntity controller = array.controller();
		if (controller.fuelBurnTicks() > 0) return true;
		for (MachineBlockEntity member : array.members()) {
			ItemStack stack = member.getStack(0);
			if (!isFuel(stack)) continue;
			stack.decrement(1);
			member.markDirty();
			controller.startFuel(FUEL_CELL_TICKS);
			return true;
		}
		return false;
	}

	/** Burns fuel for one step at this output. */
	public static void burn(Array array, double outputKw, int stepTicks) {
		if (outputKw <= 0) return;
		MachineBlockEntity controller = array.controller();
		double ticks = stepTicks * array.cores() * Math.min(1, outputKw / array.capacityKw()) * array.fuelUse()
				+ BURN_REMAINDER.getOrDefault(controller, 0.0);
		int whole = (int) ticks;
		BURN_REMAINDER.put(controller, ticks - whole);
		controller.setFuelBurnTicks(controller.fuelBurnTicks() - whole);
		controller.markDirty();
	}
}
