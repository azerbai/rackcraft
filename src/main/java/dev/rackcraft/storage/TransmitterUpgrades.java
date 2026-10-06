package dev.rackcraft.storage;

import dev.rackcraft.RcItems;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Each transmitter level can be bought with RackCoin or built from resources. Index i is the cost of going
 * from level i to level i + 1; see {@link StorageService#RANGES} for what each level reaches.
 */
public final class TransmitterUpgrades {
	public record Cost(long rackCoin, Map<Item, Integer> items) {}

	private static List<Cost> costs;

	private TransmitterUpgrades() {}

	public static List<Cost> costs() {
		if (costs == null) {
			costs = List.of(
					new Cost(500, Map.of(RcItems.ITEMS.get("circuit_board"), 4, RcItems.ITEMS.get("copper_wire"), 8)),
					new Cost(1_500, Map.of(Items.GOLD_INGOT, 8, Items.ENDER_PEARL, 2)),
					new Cost(4_000, Map.of(Items.ENDER_PEARL, 8, Items.DIAMOND, 2)),
					new Cost(10_000, Map.of(Items.ENDER_EYE, 4, Items.DIAMOND, 4)),
					new Cost(25_000, Map.of(Items.ENDER_EYE, 16, RcItems.ITEMS.get("cryo_coil"), 2)),
					new Cost(60_000, Map.of(Items.NETHER_STAR, 1, Items.ENDER_EYE, 8)),
					new Cost(150_000, Map.of(Items.NETHERITE_INGOT, 2, Items.SHULKER_SHELL, 8)));
		}
		return costs;
	}

	public static Cost next(int level) {
		return level >= 0 && level < costs().size() ? costs().get(level) : null;
	}

	/** Power draw doubles with every level: 0.5 kW at level 0, 64 kW at the multidimensional level. */
	public static double drawKw(int level) {
		return 0.5 * (1 << Math.max(0, Math.min(StorageService.MAX_LEVEL, level)));
	}

	public static boolean hasItems(PlayerEntity player, Map<Item, Integer> items) {
		if (player.isCreative()) return true;
		return items.entrySet().stream().allMatch(entry -> player.getInventory().count(entry.getKey()) >= entry.getValue());
	}

	public static void takeItems(PlayerEntity player, Map<Item, Integer> items) {
		if (player.isCreative()) return;
		PlayerInventory inventory = player.getInventory();
		items.forEach((item, needed) -> {
			int remaining = needed;
			for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
				ItemStack stack = inventory.getStack(slot);
				if (!stack.isOf(item)) continue;
				int taken = Math.min(remaining, stack.getCount());
				stack.decrement(taken);
				remaining -= taken;
			}
		});
	}
}
