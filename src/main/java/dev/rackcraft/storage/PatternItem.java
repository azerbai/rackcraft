package dev.rackcraft.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

/**
 * An encoded crafting pattern: the exact 3x3 inputs and the expected output. Store it anywhere in the network
 * and its output becomes autocraftable.
 */
public final class PatternItem extends Item {
	public PatternItem(Item.Settings settings) {
		super(settings.maxCount(1));
	}

	public record Pattern(List<ItemKey> grid, ItemKey output, int outputCount) {
		/** Total of each input across the grid. */
		public Map<ItemKey, Long> inputs() {
			Map<ItemKey, Long> totals = new LinkedHashMap<>();
			for (ItemKey key : grid) if (key != null) totals.merge(key, 1L, Long::sum);
			return totals;
		}
	}

	public static void encode(ItemStack stack, List<ItemStack> grid, ItemStack output) {
		NbtCompound tag = stack.getOrCreateNbt();
		NbtList inputs = new NbtList();
		for (ItemStack input : grid) inputs.add(input.isEmpty() ? new NbtCompound() : ItemKey.of(input).toNbt());
		tag.put("Inputs", inputs);
		NbtCompound result = ItemKey.of(output).toNbt();
		result.putInt("Count", output.getCount());
		tag.put("Output", result);
	}

	public static Pattern decode(ItemStack stack) {
		return decode(stack.getNbt());
	}

	public static Pattern decode(NbtCompound tag) {
		if (tag == null || !tag.contains("Output")) return null;
		List<ItemKey> grid = new ArrayList<>(9);
		NbtList inputs = tag.getList("Inputs", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < 9; index++) {
			NbtCompound entry = index < inputs.size() ? inputs.getCompound(index) : new NbtCompound();
			grid.add(entry.contains("id") ? ItemKey.fromNbt(entry) : null);
		}
		NbtCompound output = tag.getCompound("Output");
		ItemKey key = ItemKey.fromNbt(output);
		if (key.isEmpty()) return null;
		return new Pattern(grid, key, Math.max(1, output.getInt("Count")));
	}

	@Override
	public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
		Pattern pattern = decode(stack);
		if (pattern == null) return;
		tooltip.add(Text.literal(pattern.outputCount() + " x ").append(pattern.output().toStack(1).getName())
				.formatted(Formatting.GREEN));
		pattern.inputs().forEach((key, count) -> tooltip.add(Text.literal("  " + count + " x ")
				.append(key.toStack(1).getName()).formatted(Formatting.GRAY)));
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		return true;
	}
}
