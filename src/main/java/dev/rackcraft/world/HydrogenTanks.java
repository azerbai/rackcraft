package dev.rackcraft.world;

import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.entity.TankerDroneEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Hydrogen Tanks: a cube multiblock (2x2x2 up to 5x5x5, or 10x10x10 with research) that holds hydrogen as gas instead
 * of in canisters. Every block holds {@link #PER_BLOCK} canisters' worth, and bigger cubes hold more per block, the way
 * Grid-Scale Batteries do. An Electrolyser that reaches a tank (touching it, or on the same Item Pipe or Storage Link
 * network) pipes its hydrogen in with no Aluminium Ingots, and storage counts the tank's contents as Hydrogen
 * Canisters, so everything that takes canisters from storage draws on the tanks.
 *
 * Tanks only work once Cryogenic Hydrogen Storage is researched. That also puts the Tanker Drone on sale at the
 * Exchange: kept in a tank's slot, it flies a stack of canisters at a time to any Drone Dock, Site Planner or Launch
 * Control within {@link #TANKER_RANGE} blocks whose hydrogen slot isn't full, and comes back for more.
 */
public final class HydrogenTanks {
	public static final long PER_BLOCK = 256;
	public static final int TANKER_RANGE = 256;
	public static final int TANKER_SLOT = 0;
	public static final int CANISTERS_PER_TRIP = 16;
	private static final int SCAN_TICKS = 40;
	// Readings (MachineBlockEntity.siteReading) on every block of a tank.
	public static final int R_STORED = 0;
	public static final int R_CAPACITY = 1;
	public static final int R_LOCKED = 2;
	public static final int R_TANKERS_OUT = 3;

	private HydrogenTanks() {}

	/** What one block of a tank this size holds: 10% more per step up to 5, then 5% more per step. */
	public static long capacityPerBlock(int edge) {
		int clamped = Math.max(1, Math.min(ReactorArrays.MAX_EDGE, edge));
		double bonus = clamped <= ReactorArrays.BASE_EDGE ? 0.1 * (clamped - 1) : 0.4 + 0.05 * (clamped - ReactorArrays.BASE_EDGE);
		return Math.round(PER_BLOCK * (1 + bonus));
	}

	/** Each step: a cube's hydrogen is shared evenly over its blocks, and every block reports the whole tank. */
	public static void step(ServerWorld world, Map<MachineBlockEntity, ReactorArrays.Array> arrays) {
		boolean unlocked = unlocked(world);
		for (ReactorArrays.Array array : new HashSet<>(arrays.values())) {
			if (!array.controller().blockId().equals("hydrogen_tank")) continue;
			long total = array.members().stream().mapToLong(MachineBlockEntity::hydrogen).sum();
			int blocks = array.members().size();
			long capacity = capacityPerBlock(array.edge()) * blocks;
			total = Math.min(total, capacity);
			long each = total / blocks;
			long extra = total % blocks;
			for (int index = 0; index < blocks; index++) {
				MachineBlockEntity member = array.members().get(index);
				long share = each + (index < extra ? 1 : 0);
				if (member.hydrogen() != share) member.setHydrogen(share);
				member.setReactorArray(array.edge(), 0, 0, 0, 0);
				member.setSiteReading(0, (int) Math.min(Integer.MAX_VALUE, total));
				member.setSiteReading(1, (int) Math.min(Integer.MAX_VALUE, capacity));
				member.setSiteReading(R_LOCKED, unlocked ? 0 : 1);
				member.setProcess(0, unlocked && total > 0);
			}
		}
		if (world.getTime() % SCAN_TICKS < Math.max(1, dev.rackcraft.RackcraftConfig.values.sim.stepTicks)) dispatch(world, arrays);
	}

	public static boolean unlocked(ServerWorld world) {
		return dev.rackcraft.compute.ResearchLab.effects(world).hydrogenStorage();
	}

	/** Sends tankers now, whatever the time; the self-test calls it directly. */
	public static void scanNow(ServerWorld world) {
		dispatch(world, ReactorArrays.scan(SimTicker.machines(world), "hydrogen_tank", ReactorArrays.maxEdge(world)));
	}

	/** Which slot of a depot holds its hydrogen, or -1 if it isn't one. */
	public static int depotSlot(String id) {
		return switch (id) {
			case "drone_dock" -> DroneDocks.FUEL_SLOT;
			case "site_planner" -> SitePlanner.FUEL_SLOT;
			case "launch_control" -> LaunchPads.FUEL_SLOT;
			default -> -1;
		};
	}

	/** Canisters a depot's hydrogen slot has room for (0 if it holds something else). */
	private static int room(MachineBlockEntity depot) {
		ItemStack slot = depot.getStack(depotSlot(depot.blockId()));
		if (slot.isEmpty()) return CANISTERS_PER_TRIP;
		if (!slot.isOf(RcItems.ITEMS.get("hydrogen_canister"))) return 0;
		return Math.max(0, slot.getMaxCount() - slot.getCount());
	}

	private static void dispatch(ServerWorld world, Map<MachineBlockEntity, ReactorArrays.Array> arrays) {
		if (!unlocked(world)) return;
		Item tankerItem = RcItems.ITEMS.get("tanker_drone");
		List<MachineBlockEntity> machines = SimTicker.machines(world);
		for (ReactorArrays.Array array : new HashSet<>(arrays.values())) {
			if (!array.controller().blockId().equals("hydrogen_tank")) continue;
			List<MachineBlockEntity> members = array.members();
			BlockPos home = array.controller().getPos();
			Set<BlockPos> memberPositions = new HashSet<>();
			for (MachineBlockEntity member : members) memberPositions.add(member.getPos());
			List<TankerDroneEntity> flying = world.getEntitiesByClass(TankerDroneEntity.class,
					new Box(home).expand(TANKER_RANGE + 32, 320, TANKER_RANGE + 32), TankerDroneEntity::isAlive);
			Set<BlockPos> claimed = new HashSet<>();
			int out = 0;
			for (TankerDroneEntity tanker : flying) {
				claimed.add(tanker.target());
				if (memberPositions.contains(tanker.home())) out++;
			}
			for (MachineBlockEntity member : members) member.setSiteReading(R_TANKERS_OUT, out);
			long gas = members.stream().mapToLong(MachineBlockEntity::hydrogen).sum();
			int home_ = members.stream().mapToInt(member -> member.getStack(TANKER_SLOT).isOf(tankerItem) ? member.getStack(TANKER_SLOT).getCount() : 0).sum();
			if (gas <= 0 || home_ <= 0) continue;
			// Depots on this tank's own pipes already draw from it through storage; tankers go to the rest.
			Set<BlockPos> local = NetworkManager.get(world).component(home, dev.rackcraft.sim.NetKind.ITEM);
			List<MachineBlockEntity> depots = new ArrayList<>();
			for (MachineBlockEntity machine : machines) {
				BlockPos pos = machine.getPos();
				if (depotSlot(machine.blockId()) < 0 || claimed.contains(pos) || local.contains(pos)) continue;
				if (Math.abs(pos.getX() - home.getX()) > TANKER_RANGE || Math.abs(pos.getZ() - home.getZ()) > TANKER_RANGE) continue;
				if (room(machine) > 0) depots.add(machine);
			}
			depots.sort(Comparator.comparingInt((MachineBlockEntity depot) -> -room(depot))
					.thenComparingDouble(depot -> depot.getPos().getSquaredDistance(home)));
			for (MachineBlockEntity depot : depots) {
				if (gas <= 0 || home_ <= 0) break;
				int carry = (int) Math.min(Math.min(room(depot), CANISTERS_PER_TRIP), gas);
				drain(members, carry);
				for (MachineBlockEntity member : members) {
					if (!member.getStack(TANKER_SLOT).isOf(tankerItem)) continue;
					member.getStack(TANKER_SLOT).decrement(1);
					member.markDirty();
					break;
				}
				TankerDroneEntity.launch(world, home, depot.getPos(), new ItemStack(RcItems.ITEMS.get("hydrogen_canister"), carry));
				gas -= carry;
				home_--;
			}
		}
	}

	private static void drain(List<MachineBlockEntity> members, long amount) {
		for (MachineBlockEntity member : members) {
			if (amount <= 0) return;
			long taken = Math.min(amount, member.hydrogen());
			member.setHydrogen(member.hydrogen() - taken);
			amount -= taken;
		}
	}

	/** A tanker at its depot: tops up the hydrogen slot. Returns whatever didn't fit. */
	public static ItemStack deliver(ServerWorld world, BlockPos target, ItemStack cargo) {
		if (cargo.isEmpty() || !(world.getBlockEntity(target) instanceof MachineBlockEntity depot)) return cargo;
		int slot = depotSlot(depot.blockId());
		if (slot < 0) return cargo;
		ItemStack there = depot.getStack(slot);
		if (there.isEmpty()) {
			depot.setStack(slot, cargo.split(Math.min(cargo.getCount(), cargo.getMaxCount())));
		} else if (there.isOf(cargo.getItem())) {
			int moved = Math.min(cargo.getCount(), there.getMaxCount() - there.getCount());
			there.increment(moved);
			cargo.decrement(moved);
		}
		depot.markDirty();
		return cargo;
	}

	/** A tanker home: back in the tank's slot (or dropped), and any canisters it brought back go back into the tank. */
	public static void dockTanker(ServerWorld world, MachineBlockEntity tank, ItemStack cargo) {
		ItemStack drone = new ItemStack(RcItems.ITEMS.get("tanker_drone"));
		ItemStack slot = tank.getStack(TANKER_SLOT);
		if (slot.isEmpty()) tank.setStack(TANKER_SLOT, drone);
		else if (slot.isOf(drone.getItem()) && slot.getCount() < slot.getMaxCount()) slot.increment(1);
		else net.minecraft.util.ItemScatterer.spawn(world, tank.getPos().getX() + 0.5, tank.getPos().getY() + 1.2, tank.getPos().getZ() + 0.5, drone);
		if (!cargo.isEmpty()) tank.setHydrogen(tank.hydrogen() + cargo.getCount());
		tank.markDirty();
	}
}
