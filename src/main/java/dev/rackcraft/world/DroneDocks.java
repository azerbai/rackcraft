package dev.rackcraft.world;

import dev.rackcraft.RcItems;
import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.entity.MaintenanceDroneEntity;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.sim.ServerModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Drone Docks and their Maintenance Drones. Every {@link #SCAN_TICKS} ticks a powered dock looks for work within
 * {@link #RANGE} blocks and sends one drone per job, while it has drones home and hydrogen:
 * <ul>
 *   <li>{@link Job#SWAP}: a rack with a Failed Module gets a spare module from the dock; the dead one comes back.</li>
 *   <li>{@link Job#SPLICE}: a cut cable is spliced, using one of a Repair Kit's eight repairs.</li>
 *   <li>{@link Job#BREAKER}: a PDU's tripped breaker is reset.</li>
 * </ul>
 * Slots: 0 drones, 1 Hydrogen Canisters, 2 to 8 spares (rack modules and Repair Kits; dead modules come back here,
 * and a hopper under the dock can take them away). Each trip burns 1/{@link #TRIPS_PER_CANISTER} of a canister.
 */
public final class DroneDocks {
	public static final int RANGE = 32;
	public static final int TRIPS_PER_CANISTER = 8;
	public static final double DOCK_KW = 2;
	public static final int DRONE_SLOT = 0;
	public static final int FUEL_SLOT = 1;
	public static final int FIRST_SPARE = 2;
	private static final int SCAN_TICKS = 40;

	public enum Job { SWAP, SPLICE, BREAKER }

	/** WORKING: drones are out. The rest say why nothing is: no jobs, or jobs it can't do yet. */
	public enum Status { WORKING, IDLE, NO_POWER, NO_DRONES, NO_FUEL, NO_SPARES }

	private record Task(Job job, BlockPos target, int slot) {}

	private DroneDocks() {}

	public static boolean accepts(int slot, ItemStack stack) {
		if (slot == DRONE_SLOT) return stack.isOf(RcItems.ITEMS.get("maintenance_drone"));
		if (slot == FUEL_SLOT) return stack.isOf(RcItems.ITEMS.get("hydrogen_canister"));
		return slot < 9 && (isModule(stack) || stack.isOf(RcItems.ITEMS.get("repair_kit")));
	}

	private static boolean isModule(ItemStack stack) {
		return !stack.isEmpty() && ServerModel.Module.byItemId(Registries.ITEM.getId(stack.getItem()).getPath()) != null;
	}

	public static void step(ServerWorld world, List<MachineBlockEntity> machines, Map<MachineBlockEntity, Double> satisfaction) {
		if (world.getTime() % SCAN_TICKS >= Math.max(1, dev.rackcraft.RackcraftConfig.values.sim.stepTicks)) return;
		scan(world, machines, satisfaction);
	}

	/** Looks for work for every dock now, whatever the time; the self-test calls it directly. */
	public static void scanNow(ServerWorld world) {
		List<MachineBlockEntity> machines = SimTicker.machines(world);
		Map<MachineBlockEntity, Double> satisfaction = new java.util.HashMap<>();
		for (MachineBlockEntity machine : machines) satisfaction.put(machine, machine.powerSatisfaction());
		scan(world, machines, satisfaction);
	}

	private static void scan(ServerWorld world, List<MachineBlockEntity> machines, Map<MachineBlockEntity, Double> satisfaction) {
		List<MachineBlockEntity> docks = machines.stream().filter(machine -> machine.blockId().equals("drone_dock")).toList();
		for (MachineBlockEntity dock : docks) {
			List<MaintenanceDroneEntity> drones = drones(world, dock.getPos());
			int out = (int) drones.stream().filter(drone -> drone.dock().equals(dock.getPos())).count();
			dock.setWorkers(out);
			Set<BlockPos> claimed = new HashSet<>();
			for (MaintenanceDroneEntity drone : drones) claimed.add(drone.target());
			List<Task> tasks = tasks(world, machines, dock.getPos(), claimed);
			dock.setDockJobs(tasks.size());
			Status status = dispatch(world, dock, tasks, satisfaction.getOrDefault(dock, 0.0));
			if (status == Status.IDLE && out > 0) status = Status.WORKING;
			dock.setProcess(status.ordinal(), out > 0);
		}
	}

	/** Drones in flight near this dock (from any dock: two docks never send drones to the same job). */
	private static List<MaintenanceDroneEntity> drones(ServerWorld world, BlockPos dock) {
		return world.getEntitiesByClass(MaintenanceDroneEntity.class, new Box(dock).expand(RANGE * 2), MaintenanceDroneEntity::isAlive);
	}

	private static boolean inRange(BlockPos dock, BlockPos pos) {
		return Math.abs(pos.getX() - dock.getX()) <= RANGE && Math.abs(pos.getY() - dock.getY()) <= RANGE
				&& Math.abs(pos.getZ() - dock.getZ()) <= RANGE;
	}

	/** Everything within range that a drone could fix and none is already on its way to, nearest first. */
	private static List<Task> tasks(ServerWorld world, List<MachineBlockEntity> machines, BlockPos dock, Set<BlockPos> claimed) {
		List<Task> tasks = new ArrayList<>();
		Item failed = RcItems.ITEMS.get("failed_module");
		for (MachineBlockEntity machine : machines) {
			BlockPos pos = machine.getPos();
			if (!inRange(dock, pos) || claimed.contains(pos)) continue;
			if (machine.blockId().equals("server_rack")) {
				for (int slot = 0; slot < ServerModel.BAYS; slot++) {
					if (machine.getStack(slot).isOf(failed)) {
						tasks.add(new Task(Job.SWAP, pos, slot));
						break;
					}
				}
			} else if (machine.blockId().equals("pdu") && machine.isTripped()) {
				tasks.add(new Task(Job.BREAKER, pos, 0));
			}
		}
		NetworkManager networks = NetworkManager.get(world);
		for (NetKind kind : NetKind.values()) {
			for (BlockPos cut : networks.cutCables(kind)) {
				if (inRange(dock, cut) && !claimed.contains(cut)) tasks.add(new Task(Job.SPLICE, cut, 0));
			}
		}
		tasks.sort(Comparator.comparingDouble(task -> task.target().getSquaredDistance(dock)));
		return tasks;
	}

	/** Sends a drone to every job it can, and says what is holding it back. */
	private static Status dispatch(ServerWorld world, MachineBlockEntity dock, List<Task> tasks, double power) {
		if (power < 0.5) return Status.NO_POWER;
		if (tasks.isEmpty()) return Status.IDLE;
		Status blocked = null;
		for (Task task : tasks) {
			if (dock.getStack(DRONE_SLOT).isEmpty()) return Status.NO_DRONES;
			if (dock.toolUses() <= 0) {
				ItemStack fuel = dock.getStack(FUEL_SLOT);
				if (!fuel.isOf(RcItems.ITEMS.get("hydrogen_canister"))) return Status.NO_FUEL;
				fuel.decrement(1);
				dock.setToolUses(TRIPS_PER_CANISTER);
			}
			ItemStack carried = ItemStack.EMPTY;
			if (task.job() == Job.SWAP) {
				int spare = findSpare(dock, DroneDocks::isModule);
				if (spare < 0) {
					blocked = Status.NO_SPARES;
					continue;
				}
				carried = dock.getStack(spare).split(1);
			} else if (task.job() == Job.SPLICE) {
				int kit = findSpare(dock, stack -> stack.isOf(RcItems.ITEMS.get("repair_kit")));
				if (kit < 0) {
					blocked = Status.NO_SPARES;
					continue;
				}
				// The drone takes the kit's next repair with it.
				ItemStack repairKit = dock.getStack(kit);
				repairKit.setDamage(repairKit.getDamage() + 1);
				if (repairKit.getDamage() >= repairKit.getMaxDamage()) dock.setStack(kit, ItemStack.EMPTY);
			}
			dock.getStack(DRONE_SLOT).decrement(1);
			dock.setToolUses(dock.toolUses() - 1);
			dock.markDirty();
			MaintenanceDroneEntity.launch(world, dock.getPos(), task.target(), task.job(), task.slot(), carried);
		}
		return blocked != null ? blocked : Status.WORKING;
	}

	private static int findSpare(MachineBlockEntity dock, java.util.function.Predicate<ItemStack> wanted) {
		for (int slot = FIRST_SPARE; slot < dock.size(); slot++) if (wanted.test(dock.getStack(slot))) return slot;
		return -1;
	}

	/** Does a drone's job where it hovers; returns what it carries home (a dead module, or an unused spare). */
	public static ItemStack doJob(ServerWorld world, MaintenanceDroneEntity drone, ItemStack carried) {
		BlockPos target = drone.target();
		boolean done = false;
		switch (drone.job()) {
			case SWAP -> {
				if (world.getBlockEntity(target) instanceof MachineBlockEntity rack && rack.blockId().equals("server_rack") && isModule(carried)) {
					Item failed = RcItems.ITEMS.get("failed_module");
					int slot = rack.getStack(drone.slot()).isOf(failed) ? drone.slot() : -1;
					for (int index = 0; slot < 0 && index < ServerModel.BAYS; index++) if (rack.getStack(index).isOf(failed)) slot = index;
					if (slot >= 0) {
						ItemStack dead = rack.getStack(slot);
						rack.setStack(slot, carried);
						rack.markDirty();
						carried = dead;
						done = true;
					}
				}
			}
			case SPLICE -> {
				if (world.getBlockState(target).getBlock() instanceof CableBlock && world.getBlockState(target).get(CableBlock.CUT)) {
					CableBlock.setCut(world, target, false);
					done = true;
				}
			}
			case BREAKER -> {
				if (world.getBlockEntity(target) instanceof MachineBlockEntity pdu && pdu.blockId().equals("pdu") && pdu.isTripped()) {
					pdu.setTripped(false);
					done = true;
				}
			}
		}
		if (done && world.getBlockEntity(drone.dock()) instanceof MachineBlockEntity dock && dock.blockId().equals("drone_dock")) {
			dock.setItemsMade(dock.itemsMade() + 1);
		}
		return carried;
	}

	/** A drone is home: it goes back in the drone slot, and what it carried into the spares (or onto the floor). */
	public static void dockDrone(ServerWorld world, MachineBlockEntity dock, ItemStack carried) {
		ItemStack drone = new ItemStack(RcItems.ITEMS.get("maintenance_drone"));
		ItemStack slot = dock.getStack(DRONE_SLOT);
		if (slot.isEmpty()) dock.setStack(DRONE_SLOT, drone);
		else if (slot.isOf(drone.getItem()) && slot.getCount() < slot.getMaxCount()) slot.increment(1);
		else drop(world, dock, drone);
		if (!carried.isEmpty()) {
			for (int index = FIRST_SPARE; index < dock.size() && !carried.isEmpty(); index++) {
				ItemStack spare = dock.getStack(index);
				if (spare.isEmpty()) {
					dock.setStack(index, carried);
					carried = ItemStack.EMPTY;
				} else if (ItemStack.canCombine(spare, carried) && spare.getCount() < spare.getMaxCount()) {
					int moved = Math.min(carried.getCount(), spare.getMaxCount() - spare.getCount());
					spare.increment(moved);
					carried.decrement(moved);
				}
			}
			if (!carried.isEmpty()) drop(world, dock, carried);
		}
		dock.markDirty();
	}

	private static void drop(ServerWorld world, MachineBlockEntity dock, ItemStack stack) {
		BlockPos pos = dock.getPos();
		ItemScatterer.spawn(world, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, stack);
	}
}
