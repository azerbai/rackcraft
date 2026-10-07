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
			case "uranium_mill", "gas_centrifuge", "fuel_fabricator", "cask_sealer" -> {
				NuclearProcessing.Recipe recipe = NuclearProcessing.recipe(machine.blockId());
				stock(machine, 0, ItemKey.of(recipe.inputA()), Math.max(PROCESS_STOCK, recipe.countA() * 4), items);
				if (recipe.inputB() != null) stock(machine, 1, ItemKey.of(recipe.inputB()), Math.max(PROCESS_STOCK, recipe.countB() * 4), items);
				store(machine, NuclearProcessing.OUTPUT_SLOT, items);
				store(machine, NuclearProcessing.BYPRODUCT_SLOT, items);
			}
			default -> {}
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
