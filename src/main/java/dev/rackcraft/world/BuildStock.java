package dev.rackcraft.world;

import dev.rackcraft.RcItems;
import dev.rackcraft.storage.ItemKey;
import dev.rackcraft.storage.StorageNetwork;
import dev.rackcraft.storage.StorageService;
import dev.rackcraft.storage.WirelessTerminalItem;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Where a hand tool gets its materials and puts what it takes back: the player's inventory first, then the storage
 * network of a Wireless Terminal they carry (when its transmitter is online and in reach). Creative players need
 * nothing and lose nothing.
 */
public final class BuildStock {
	private final ServerPlayerEntity player;
	private final StorageNetwork storage;

	private BuildStock(ServerPlayerEntity player, StorageNetwork storage) {
		this.player = player;
		this.storage = storage;
	}

	public static BuildStock of(ServerPlayerEntity player) {
		StorageNetwork network = null;
		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.size() && network == null; slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (!stack.isOf(RcItems.ITEMS.get("wireless_terminal"))) continue;
			StorageService.Access access = WirelessTerminalItem.link(stack);
			if (access != null && WirelessTerminalItem.reach(player, access) == null) network = access.resolve(player.getServer());
		}
		return new BuildStock(player, network);
	}

	public boolean hasStorage() { return storage != null; }

	public boolean creative() { return player.isCreative(); }

	/** How many of an item the player can draw on. */
	public long count(Item item) {
		if (creative()) return Long.MAX_VALUE / 4;
		long total = 0;
		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (stack.isOf(item) && !stack.hasNbt()) total += stack.getCount();
		}
		if (storage != null) total += storage.count(ItemKey.of(item), false);
		return total;
	}

	/** Takes up to {@code amount}; returns how many it got. */
	public long take(Item item, long amount) {
		if (creative()) return amount;
		long taken = 0;
		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.size() && taken < amount; slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (!stack.isOf(item) || stack.hasNbt()) continue;
			int grab = (int) Math.min(stack.getCount(), amount - taken);
			stack.decrement(grab);
			taken += grab;
		}
		if (taken < amount && storage != null) taken += storage.extract(ItemKey.of(item), amount - taken, false, false);
		return taken;
	}

	/** Stores a stack in the network, or hands it to the player; whatever won't fit drops at their feet. */
	public void give(ItemStack stack) {
		if (stack.isEmpty() || creative()) return;
		if (storage != null && !stack.hasNbt()) {
			long left = stack.getCount() - storage.insert(ItemKey.of(stack), stack.getCount(), false);
			stack = left <= 0 ? ItemStack.EMPTY : stack.copyWithCount((int) left);
		}
		if (!stack.isEmpty() && !player.getInventory().insertStack(stack)) player.dropItem(stack, false);
	}
}
