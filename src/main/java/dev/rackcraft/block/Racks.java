package dev.rackcraft.block;

import dev.rackcraft.sim.ServerModel;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

/** Racks of every tier, and the modules in them, as the world sees them. */
public final class Racks {
	/** Which of its three modes an FPGA stack is in: 0 mining, 1 AI, 2 autocrafting. */
	public static final String FPGA_MODE = "FpgaMode";

	private Racks() {}

	public static boolean isRack(String blockId) { return ServerModel.Tier.isRack(blockId); }

	public static boolean isRack(MachineBlockEntity machine) { return isRack(machine.blockId()); }

	/** The rack's tier; a Server Rack's for anything else. */
	public static ServerModel.Tier tier(MachineBlockEntity machine) {
		ServerModel.Tier tier = ServerModel.Tier.of(machine.blockId());
		return tier == null ? ServerModel.Tier.SERVER : tier;
	}

	public static int bays(MachineBlockEntity machine) { return tier(machine).bays(); }

	/** The module this stack is, FPGAs in the mode they are set to; null if it isn't one. */
	public static ServerModel.Module module(ItemStack stack) {
		if (stack.isEmpty()) return null;
		return ServerModel.Module.of(Registries.ITEM.getId(stack.getItem()).getPath(),
				stack.hasNbt() ? stack.getNbt().getInt(FPGA_MODE) : 0);
	}

	/**
	 * The module kinds a rack of this tier can be told to fill with, as item ids in Module order (an FPGA once, whatever
	 * mode its stack is in). The rack screen's Module button and the server's fill both walk this list.
	 */
	public static java.util.List<String> fillChoices(ServerModel.Tier tier) {
		java.util.List<String> ids = new java.util.ArrayList<>();
		for (ServerModel.Module module : ServerModel.Module.values()) {
			if (tier.accepts(module) && !ids.contains(module.itemId())) ids.add(module.itemId());
		}
		return ids;
	}

	/** Module kinds a Data Hall blueprint can fill its racks with: fillChoices without what needs extras beside the rack. */
	public static java.util.List<String> hallChoices(ServerModel.Tier tier) {
		java.util.List<String> ids = new java.util.ArrayList<>(fillChoices(tier));
		// Annealers need a Cryostat beside the rack, and an FPGA's mode lives on its stack; neither fits a bulk blueprint.
		ids.remove(ServerModel.Module.QUANTUM_ANNEALER.itemId());
		ids.remove(ServerModel.Module.FPGA_MINING.itemId());
		return ids;
	}

	/**
	 * Fills the empty bays of a rack with modules: from the rack's storage network first, then from the player's
	 * inventory (when there is a player). {@code choice} 0 is automatic (whatever is already in the rack, else the best
	 * module on hand); n is the nth of {@link #fillChoices}. Returns how many went in.
	 */
	public static int fill(MachineBlockEntity rack, dev.rackcraft.storage.StorageNetwork network,
			net.minecraft.entity.player.PlayerEntity player, int choice) {
		ServerModel.Tier tier = tier(rack);
		java.util.List<String> choices = fillChoices(tier);
		String wanted = choice > 0 && choice <= choices.size() ? choices.get(choice - 1) : null;
		ItemStack template = ItemStack.EMPTY;
		if (wanted == null) {
			for (int bay = 0; bay < tier.bays() && template.isEmpty(); bay++) if (module(rack.getStack(bay)) != null) template = rack.getStack(bay);
		}
		int moved = 0;
		for (int bay = 0; bay < tier.bays(); bay++) {
			if (!rack.getStack(bay).isEmpty()) continue;
			ItemStack picked = pick(rack, network, player, wanted, template);
			if (picked.isEmpty()) break;
			if (template.isEmpty() && wanted == null) template = picked;
			rack.setStack(bay, picked);
			moved++;
		}
		if (moved > 0) rack.markDirty();
		return moved;
	}

	/** One module for a bay: matching the item id (or the template stack), else the best available. Empty if there is none. */
	private static ItemStack pick(MachineBlockEntity rack, dev.rackcraft.storage.StorageNetwork network,
			net.minecraft.entity.player.PlayerEntity player, String itemId, ItemStack template) {
		ServerModel.Tier tier = tier(rack);
		java.util.function.Predicate<ItemStack> fits = stack -> {
			ServerModel.Module module = module(stack);
			if (module == null || !tier.accepts(module)) return false;
			if (!template.isEmpty()) return ItemStack.canCombine(stack, template);
			return itemId == null || Registries.ITEM.getId(stack.getItem()).getPath().equals(itemId);
		};
		// Most useful first: the highest tier, then the one that mines the most.
		java.util.Comparator<ItemStack> best = java.util.Comparator
				.comparingInt((ItemStack stack) -> module(stack).tier())
				.thenComparingDouble(stack -> module(stack).creditsPerSecond() + module(stack).aiCompute());
		if (network != null) {
			dev.rackcraft.storage.ItemKey chosen = null;
			ItemStack chosenStack = ItemStack.EMPTY;
			for (var entry : network.totals().entrySet()) {
				ItemStack sample = entry.getKey().toStack(1);
				if (!fits.test(sample)) continue;
				if (chosen == null || best.compare(sample, chosenStack) > 0) {
					chosen = entry.getKey();
					chosenStack = sample;
				}
			}
			if (chosen != null && network.extract(chosen, 1, true, false) == 1) return chosen.toStack(1);
		}
		if (player != null) {
			ItemStack top = ItemStack.EMPTY;
			for (ItemStack stack : player.getInventory().main) {
				if (!stack.isEmpty() && fits.test(stack) && (top.isEmpty() || best.compare(stack, top) > 0)) top = stack;
			}
			if (!top.isEmpty()) return top.split(1);
		}
		return ItemStack.EMPTY;
	}

	/** Takes every module out of a rack, into its storage network and then the player's inventory. Returns how many left. */
	public static int empty(MachineBlockEntity rack, dev.rackcraft.storage.StorageNetwork network,
			net.minecraft.entity.player.PlayerEntity player) {
		int moved = 0;
		for (int bay = 0; bay < bays(rack); bay++) {
			ItemStack stack = rack.getStack(bay);
			if (stack.isEmpty()) continue;
			if (network != null && network.insert(dev.rackcraft.storage.ItemKey.of(stack), stack.getCount(), false) == stack.getCount()) stack = ItemStack.EMPTY;
			else if (player != null) {
				ItemStack rest = stack.copy();
				player.getInventory().insertStack(rest);
				if (!rest.isEmpty()) continue;
				stack = ItemStack.EMPTY;
			}
			if (stack.isEmpty()) {
				rack.setStack(bay, ItemStack.EMPTY);
				moved++;
			}
		}
		if (moved > 0) rack.markDirty();
		return moved;
	}

	/** Fiber bandwidth the rack's mining needs: what its modules mine, with its tier's bonus and fabric. */
	public static double bandwidthNeed(MachineBlockEntity rack) {
		ServerModel.Tier tier = tier(rack);
		return rack.modules().stream().mapToDouble(ServerModel.Module::creditsPerSecond).sum() * tier.bonus() * tier.bandwidth();
	}
}
