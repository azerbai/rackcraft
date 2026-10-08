package dev.rackcraft.world;

import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.sim.NetKind;
import dev.rackcraft.storage.ItemKey;
import dev.rackcraft.storage.StorageNetwork;
import dev.rackcraft.storage.StorageService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Item Pipes connect Storage Arrays and Tape Libraries to the machines that eat items, and keep them stocked
 * from storage once a second:
 *
 * <ul>
 *   <li>Kids' Art Tables get paper and a box of Crayons, Scriptorium Desks paper and ink sacs; their finished
 *       Art Aggregates and Text Corpora go back into storage.</li>
 *   <li>Diesel Generators get fuel (the best on hand first) and hand back empty buckets; Modular Reactors get
 *       Fuel Cells and hand back Spent Fuel.</li>
 *   <li>The nuclear processing cubes get their inputs and hand back what they make, so storage can run the whole
 *       fuel cycle: ore to Fuel Cells, and Spent Fuel to sealed casks.</li>
 * </ul>
 */
public final class ItemPipes {
	public static final int PAPER_STOCK = 32;
	public static final int INK_STOCK = 8;
	public static final int FUEL_STOCK = 32;
	public static final int FUEL_CELL_STOCK = 4;
	/** Input kept in each core of a nuclear processing cube. */
	public static final int PROCESS_STOCK = 8;
	public static final int PART_STOCK = 16;

	private ItemPipes() {}

	public static void step(ServerWorld world, List<MachineBlockEntity> machines) {
		Map<BlockPos, MachineBlockEntity> byPos = new HashMap<>();
		for (MachineBlockEntity machine : machines) {
			if (MachineBlockEntity.ITEM_MACHINES.contains(machine.blockId())) byPos.put(machine.getPos(), machine);
		}
		if (byPos.isEmpty()) return;
		for (Set<BlockPos> network : NetworkManager.get(world).components(NetKind.ITEM)) {
			List<MachineBlockEntity> users = new ArrayList<>();
			boolean storage = false;
			for (BlockPos pos : network) {
				MachineBlockEntity machine = byPos.get(pos);
				if (machine == null) continue;
				String id = machine.blockId();
				if (id.equals("storage_array") || id.equals("tape_library")) storage |= machine.storageOnline();
				else users.add(machine);
			}
			if (!storage || users.isEmpty()) continue;
			StorageNetwork items = StorageService.networkOf(world, network);
			if (items.isEmpty()) continue;
			for (MachineBlockEntity machine : users) serve(machine, items);
		}
	}

	private static void serve(MachineBlockEntity machine, StorageNetwork items) {
		switch (machine.blockId()) {
			case "art_table" -> {
				stock(machine, 0, ItemKey.of(Items.PAPER), PAPER_STOCK, items);
				if (machine.getStack(1).isEmpty()) stock(machine, 1, ItemKey.of(RcItems.ITEMS.get("crayons")), 1, items);
				store(machine, 2, items);
			}
			case "writing_desk" -> {
				stock(machine, 0, ItemKey.of(Items.PAPER), PAPER_STOCK, items);
				stock(machine, 1, ItemKey.of(Items.INK_SAC), INK_STOCK, items);
				store(machine, 2, items);
			}
			case "diesel_generator" -> {
				ItemStack slot = machine.getStack(0);
				if (slot.isOf(Items.BUCKET)) store(machine, 0, items);
				slot = machine.getStack(0);
				if (!slot.isEmpty()) {
					stock(machine, 0, ItemKey.of(slot), FUEL_STOCK, items);
					return;
				}
				for (Item fuel : dieselFuels()) {
					if (items.count(ItemKey.of(fuel), true) <= 0) continue;
					stock(machine, 0, ItemKey.of(fuel), FUEL_STOCK, items);
					return;
				}
			}
			case "modular_reactor" -> {
				stock(machine, ReactorArrays.FUEL_SLOT, ItemKey.of(RcItems.ITEMS.get("fuel_cell")), FUEL_CELL_STOCK, items);
				store(machine, ReactorArrays.WASTE_SLOT, items);
			}
			// An Assembly Robot keeps each of its slots topped up with whatever part is already in it.
			case "assembly_arm" -> {
				for (int slot = 0; slot < machine.size(); slot++) {
					if (!machine.getStack(slot).isEmpty()) stock(machine, slot, ItemKey.of(machine.getStack(slot)), PART_STOCK, items);
				}
			}
			// A Drone Dock takes hydrogen from storage and sends the dead modules its drones bring home back to it.
			case "drone_dock" -> {
				// Room is left for the drones still out, so they have somewhere to land.
				stock(machine, DroneDocks.DRONE_SLOT, ItemKey.of(RcItems.ITEMS.get("maintenance_drone")), 8 - machine.workers(), items);
				stock(machine, DroneDocks.FUEL_SLOT, ItemKey.of(RcItems.ITEMS.get("hydrogen_canister")), FUEL_CELL_STOCK, items);
				for (int slot = DroneDocks.FIRST_SPARE; slot < machine.size(); slot++) {
					if (machine.getStack(slot).isOf(RcItems.ITEMS.get("failed_module"))) store(machine, slot, items);
				}
			}
			// A Belt Loader puts one more of its sample item on the belt in front whenever that belt is empty.
			case "belt_loader" -> {
				ItemStack sample = machine.getStack(0);
				if (sample.isEmpty() || machine.getWorld() == null) return;
				BlockPos front = machine.getPos().offset(machine.getCachedState().get(dev.rackcraft.block.MachineBlock.FACING));
				if (!(machine.getWorld().getBlockEntity(front) instanceof dev.rackcraft.block.BeltBlockEntity belt) || !belt.stack().isEmpty()) return;
				ItemKey key = ItemKey.of(sample);
				if (items.extract(key, 1, true, false) > 0) {
					belt.accept(key.toStack(1), 0);
					machine.setItemsMade(machine.itemsMade() + 1);
				}
			}
			// A Belt Unloader files everything the line hands it.
			case "belt_unloader" -> {
				for (int slot = 0; slot < machine.size(); slot++) store(machine, slot, items);
			}
			// A Launch Control fills its tank from storage and files Survey maps away.
			case "launch_control" -> {
				stock(machine, LaunchPads.FUEL_SLOT, ItemKey.of(RcItems.ITEMS.get("hydrogen_canister")), 16, items);
				if (machine.getStack(LaunchPads.PAYLOAD_SLOT).isOf(Items.FILLED_MAP)) store(machine, LaunchPads.PAYLOAD_SLOT, items);
			}
			default -> {
				NuclearProcessing.Recipe recipe = NuclearProcessing.recipe(machine.blockId());
				if (recipe == null) return;
				stock(machine, 0, ItemKey.of(recipe.inputA()), Math.max(PROCESS_STOCK, recipe.countA() * 4), items);
				if (recipe.inputB() != null) stock(machine, 1, ItemKey.of(recipe.inputB()), Math.max(PROCESS_STOCK, recipe.countB() * 4), items);
				store(machine, NuclearProcessing.OUTPUT_SLOT, items);
				store(machine, NuclearProcessing.BYPRODUCT_SLOT, items);
			}
		}
	}

	/** Best first. Lava buckets are left out: the empty buckets would clog the slot between deliveries. */
	private static List<Item> dieselFuels() {
		return List.of(RcItems.ITEMS.get("biodiesel_canister"), dev.rackcraft.RcBlocks.get("coke_block").asItem(),
				Items.COAL_BLOCK, RcItems.ITEMS.get("coke"), Items.COAL, Items.CHARCOAL, Items.BLAZE_ROD,
				RcItems.ITEMS.get("biomass_pellet"));
	}

	/** Tops a slot up to {@code target} of this item from storage, if the slot is empty or already holds it. */
	private static void stock(MachineBlockEntity machine, int slot, ItemKey key, int target, StorageNetwork items) {
		ItemStack stack = machine.getStack(slot);
		if (!stack.isEmpty() && !key.matches(stack)) return;
		int want = Math.min(target, key.maxStackSize()) - stack.getCount();
		if (want <= 0) return;
		long got = items.extract(key, want, true, false);
		if (got <= 0) return;
		if (stack.isEmpty()) machine.setStack(slot, key.toStack(got));
		else {
			stack.increment((int) got);
			machine.markDirty();
		}
	}

	/** Moves whatever is in this slot into storage, as much as fits. */
	private static void store(MachineBlockEntity machine, int slot, StorageNetwork items) {
		ItemStack stack = machine.getStack(slot);
		if (stack.isEmpty()) return;
		long stored = items.insert(ItemKey.of(stack), stack.getCount(), false);
		if (stored <= 0) return;
		stack.decrement((int) stored);
		machine.markDirty();
	}
}
