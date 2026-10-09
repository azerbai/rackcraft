package dev.rackcraft.world;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RcBlocks;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.MachineBlock;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.block.Racks;
import dev.rackcraft.block.SolarArrayBlock;
import dev.rackcraft.generated.ContentIds;
import dev.rackcraft.sim.ServerModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Blueprints: a region of Rackcraft blocks recorded in an item. A Blueprint Scanner reads a box into a blank
 * Blueprint, and a Site Planner prints it back with drones, from storage and the Exchange, through the usual price
 * quote. It stores each block's kind and facing, and for a rack the module that filled most of its bays; it stores
 * nothing else about a machine (drives, settings, contents). Vanilla blocks are skipped, because drones carry
 * Rackcraft's blocks.
 *
 * <p>The cells are saved as a palette of {@code block|facing|module} strings and run-length pairs of palette index and
 * count, walking x, then z, then y. Index 0 is air. Reading is defensive: anything that isn't a well-formed blueprint is
 * refused rather than trusted.
 */
public final class Blueprints {
	public static final int MAX_SIDE = 64;
	private static final int MAX_PALETTE = 4096;
	public static final String KEY = "Blueprint";

	/** One block to print: where it goes in the box, what it is, which way it faces and a rack's module (or null). */
	public record Cell(int x, int y, int z, String block, Direction facing, String module) {}

	/** A blueprint's size and blocks. */
	public record Blueprint(int width, int height, int depth, List<Cell> cells, int skipped) {
		public int blocks() { return cells.size(); }
	}

	private Blueprints() {}

	/** Whether this stack is a Blueprint item (blank or not). */
	public static boolean isBlueprint(ItemStack stack) {
		return stack.isOf(RcItems.ITEMS.get("blueprint"));
	}

	public static boolean written(ItemStack stack) {
		return isBlueprint(stack) && stack.getSubNbt(KEY) != null;
	}

	/** The block a world block would be recorded as, or null if it isn't printable. */
	private static boolean printable(BlockState state) {
		if (state.isAir()) return false;
		var id = Registries.BLOCK.getId(state.getBlock());
		if (!id.getNamespace().equals("rackcraft") || !RcBlocks.BLOCKS.containsKey(id.getPath())) return false;
		if (ContentIds.CREATIVE_IDS.contains(id.getPath())) return false;
		// A Solar Array is six blocks that one placement makes: only its first part is recorded.
		if (state.contains(SolarArrayBlock.PART)) return state.get(SolarArrayBlock.PART) == 0 && state.get(MachineBlock.FACING) == Direction.NORTH;
		return true;
	}

	/**
	 * Reads the box between two corners into the blueprint item. Returns the number of blocks recorded, or a negative
	 * value: -1 too big, -2 the item isn't a Blueprint.
	 */
	public static int scan(ServerWorld world, BlockPos a, BlockPos b, ItemStack target) {
		if (!isBlueprint(target)) return -2;
		int minX = Math.min(a.getX(), b.getX()), minY = Math.min(a.getY(), b.getY()), minZ = Math.min(a.getZ(), b.getZ());
		int width = Math.abs(a.getX() - b.getX()) + 1, height = Math.abs(a.getY() - b.getY()) + 1, depth = Math.abs(a.getZ() - b.getZ()) + 1;
		if (width > MAX_SIDE || height > MAX_SIDE || depth > MAX_SIDE
				|| (long) width * height * depth > RackcraftConfig.values.building.blueprintMaxVolume) return -1;
		Map<String, Integer> palette = new LinkedHashMap<>();
		palette.put("", 0);
		List<Integer> runs = new ArrayList<>();
		int last = -1;
		int count = 0;
		int blocks = 0;
		int skipped = 0;
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
			cursor.set(minX + x, minY + y, minZ + z);
			BlockState state = world.getBlockState(cursor);
			int index = 0;
			if (printable(state)) {
				String id = Registries.BLOCK.getId(state.getBlock()).getPath();
				int facing = state.contains(MachineBlock.FACING) ? state.get(MachineBlock.FACING).getHorizontal() : -1;
				String module = world.getBlockEntity(cursor) instanceof MachineBlockEntity rack && Racks.isRack(rack) ? dominantModule(rack) : "";
				String key = id + "|" + facing + "|" + module;
				Integer known = palette.get(key);
				if (known == null) {
					known = palette.size();
					palette.put(key, known);
				}
				index = known;
				blocks++;
			} else if (!state.isAir() && !state.getFluidState().isStill() && state.getBlock() != net.minecraft.block.Blocks.AIR) {
				skipped++;
			}
			if (index == last) {
				count++;
			} else {
				if (count > 0) {
					runs.add(last);
					runs.add(count);
				}
				last = index;
				count = 1;
			}
		}
		runs.add(last);
		runs.add(count);
		NbtCompound nbt = new NbtCompound();
		nbt.putIntArray("Size", new int[] {width, height, depth});
		NbtList list = new NbtList();
		palette.keySet().forEach(key -> list.add(net.minecraft.nbt.NbtString.of(key)));
		nbt.put("Palette", list);
		nbt.putIntArray("Runs", runs.stream().mapToInt(Integer::intValue).toArray());
		nbt.putInt("Blocks", blocks);
		nbt.putInt("Skipped", skipped);
		target.setSubNbt(KEY, nbt);
		return blocks;
	}

	private static String dominantModule(MachineBlockEntity rack) {
		Map<String, Integer> counts = new HashMap<>();
		for (int slot = 0; slot < Racks.bays(rack) && slot < rack.size(); slot++) {
			ItemStack stack = rack.getStack(slot);
			if (Racks.module(stack) != null) counts.merge(Registries.ITEM.getId(stack.getItem()).getPath(), 1, Integer::sum);
		}
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
	}

	/** The blueprint a written Blueprint item holds, or null if it is blank or malformed. */
	public static Blueprint read(ItemStack stack) {
		if (!written(stack)) return null;
		NbtCompound nbt = stack.getSubNbt(KEY);
		int[] size = nbt.getIntArray("Size");
		if (size.length != 3) return null;
		int width = size[0], height = size[1], depth = size[2];
		if (width < 1 || height < 1 || depth < 1 || width > MAX_SIDE || height > MAX_SIDE || depth > MAX_SIDE) return null;
		long volume = (long) width * height * depth;
		if (volume > RackcraftConfig.values.building.blueprintMaxVolume) return null;
		NbtList paletteList = nbt.getList("Palette", NbtElement.STRING_TYPE);
		if (paletteList.isEmpty() || paletteList.size() > MAX_PALETTE) return null;
		String[] block = new String[paletteList.size()];
		int[] facing = new int[block.length];
		String[] module = new String[block.length];
		for (int index = 1; index < block.length; index++) {
			String[] parts = paletteList.getString(index).split("\\|", -1);
			if (parts.length != 3 || !RcBlocks.BLOCKS.containsKey(parts[0]) || ContentIds.CREATIVE_IDS.contains(parts[0])) return null;
			int face;
			try {
				face = Integer.parseInt(parts[1]);
			} catch (NumberFormatException exception) {
				return null;
			}
			if (face < -1 || face > 3) return null;
			if (!parts[2].isEmpty() && (!RcItems.ITEMS.containsKey(parts[2]) || Racks.module(new ItemStack(RcItems.ITEMS.get(parts[2]))) == null
					|| !Racks.isRack(parts[0]))) return null;
			block[index] = parts[0];
			facing[index] = face;
			module[index] = parts[2].isEmpty() ? null : parts[2];
		}
		int[] runs = nbt.getIntArray("Runs");
		if (runs.length == 0 || runs.length % 2 != 0) return null;
		List<Cell> cells = new ArrayList<>();
		long at = 0;
		for (int run = 0; run < runs.length; run += 2) {
			int index = runs[run];
			int length = runs[run + 1];
			if (index < 0 || index >= block.length || length < 1 || at + length > volume) return null;
			if (index > 0) {
				for (long cell = at; cell < at + length; cell++) {
					int x = (int) (cell % width);
					int z = (int) (cell / width % depth);
					int y = (int) (cell / ((long) width * depth));
					cells.add(new Cell(x, y, z, block[index], facing[index] < 0 ? Direction.NORTH : Direction.fromHorizontal(facing[index]), module[index]));
				}
			}
			at += length;
		}
		if (at != volume) return null;
		return new Blueprint(width, height, depth, cells, nbt.getInt("Skipped"));
	}

	/** What a blueprint would take to print, by item: blocks and rack modules. */
	public static Map<net.minecraft.item.Item, Long> parts(Blueprint blueprint) {
		Map<net.minecraft.item.Item, Long> parts = new LinkedHashMap<>();
		for (Cell cell : blueprint.cells()) {
			parts.merge(RcBlocks.get(cell.block()).asItem(), 1L, Long::sum);
			if (cell.module() != null) {
				parts.merge(RcItems.ITEMS.get(cell.module()), (long) ServerModel.Tier.of(cell.block()).bays(), Long::sum);
			}
		}
		return parts;
	}
}
