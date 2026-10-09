package dev.rackcraft.world;

import dev.rackcraft.RackcraftConfig;
import dev.rackcraft.RcItems;
import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.CableBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * The hand tools that change a lot of blocks at once: the Constructor's Gauntlet (lines, planes, boxes, swaps and
 * deletes at range) and the Terraformer Cannon (flatten, fill, hollow, smooth). Both draw on a {@link BuildStock},
 * never touch a block that holds items or belongs to Rackcraft's machines (cables excepted), and refuse a job bigger
 * than {@code building.maxBlocksPerUse}.
 */
public final class Earthworks {
	public enum Mode { LINE, PLANE, BOX, SWAP, DELETE }

	public enum Terrain { FLATTEN, FILL, HOLLOW, SMOOTH }

	public static final int MIN_RADIUS = 3;
	public static final int MAX_RADIUS = 16;

	private Earthworks() {}

	// ------------------------------------------------------------------ what may be touched

	/** Whether a tool may remove or replace this block. Machines, containers and anything unbreakable are off limits. */
	public static boolean editable(ServerWorld world, BlockPos pos, BlockState state) {
		if (state.isAir()) return true;
		if (state.getHardness(world, pos) < 0) return false;
		BlockEntity entity = world.getBlockEntity(pos);
		if (entity != null && !(entity instanceof CableBlockEntity)) return false;
		// Rackcraft's own blocks are protected except the cables, which are fair game for rebuilding a run.
		if (Registries.BLOCK.getId(state.getBlock()).getNamespace().equals("rackcraft") && !(state.getBlock() instanceof CableBlock)) return false;
		return true;
	}

	// ------------------------------------------------------------------ Constructor's Gauntlet

	public record Result(int changed, int skipped, String note) {}

	/** The cells a mode covers between two corners, capped at the per-use limit (null if over it). */
	public static List<BlockPos> cells(Mode mode, BlockPos a, BlockPos b) {
		int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
		int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
		int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
		int dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
		List<BlockPos> cells = new ArrayList<>();
		int limit = RackcraftConfig.values.building.maxBlocksPerUse;
		if ((long) (dx + 1) * (dy + 1) * (dz + 1) > limit * 64L) return null;
		switch (mode) {
			case LINE -> {
				// Along whichever axis the corners are furthest apart; the other two stay at the first corner.
				Direction.Axis axis = dx >= dy && dx >= dz ? Direction.Axis.X : dy >= dz ? Direction.Axis.Y : Direction.Axis.Z;
				int length = Math.max(dx, Math.max(dy, dz));
				Direction direction = Direction.from(axis, b.getComponentAlongAxis(axis) >= a.getComponentAlongAxis(axis)
						? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
				for (int step = 0; step <= length; step++) cells.add(a.offset(direction, step));
			}
			case PLANE -> {
				// Flat in whichever direction the corners are closest, level (Y) if they tie.
				boolean flatY = dy <= dx && dy <= dz;
				boolean flatX = !flatY && dx <= dz;
				for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
					if (flatY && y != a.getY() || flatX && x != a.getX() || !flatY && !flatX && z != a.getZ()) continue;
					cells.add(new BlockPos(x, y, z));
				}
			}
			default -> {
				for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
					cells.add(new BlockPos(x, y, z));
				}
			}
		}
		return cells.size() > limit ? null : cells;
	}

	/** Runs a Gauntlet job. {@code block} is the block to place (PLACE modes and SWAP); null for DELETE. */
	public static Result gauntlet(ServerWorld world, ServerPlayerEntity player, BuildStock stock, Mode mode, BlockPos a, BlockPos b, Block block) {
		List<BlockPos> cells = cells(mode, a, b);
		if (cells == null) return new Result(0, 0, "Too big: the limit is " + RackcraftConfig.values.building.maxBlocksPerUse + " blocks a use");
		int changed = 0;
		int skipped = 0;
		Block target = mode == Mode.SWAP ? world.getBlockState(a).getBlock() : null;
		for (BlockPos pos : cells) {
			BlockState state = world.getBlockState(pos);
			switch (mode) {
				case LINE, PLANE, BOX -> {
					if (!state.isReplaceable() || !editable(world, pos, state)) { skipped++; continue; }
					if (stock.take(block.asItem(), 1) < 1) return new Result(changed, skipped, "Out of " + block.getName().getString());
					place(world, player, pos, block);
					changed++;
				}
				case SWAP -> {
					if (state.isAir() || !state.isOf(target) || state.isOf(block) || !editable(world, pos, state)) { skipped++; continue; }
					if (stock.take(block.asItem(), 1) < 1) return new Result(changed, skipped, "Out of " + block.getName().getString());
					collect(world, player, stock, pos, state);
					place(world, player, pos, block);
					changed++;
				}
				case DELETE -> {
					if (state.isAir() || !editable(world, pos, state)) { skipped++; continue; }
					collect(world, player, stock, pos, state);
					world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
					changed++;
				}
			}
		}
		return new Result(changed, skipped, "");
	}

	private static void place(ServerWorld world, ServerPlayerEntity player, BlockPos pos, Block block) {
		BlockState state = block instanceof CableBlock cable ? cable.withConnections(block.getDefaultState(), world, pos) : block.getDefaultState();
		if (state.contains(Properties.HORIZONTAL_FACING)) state = state.with(Properties.HORIZONTAL_FACING, player.getHorizontalFacing().getOpposite());
		world.setBlockState(pos, state, Block.NOTIFY_ALL);
	}

	private static void collect(ServerWorld world, ServerPlayerEntity player, BuildStock stock, BlockPos pos, BlockState state) {
		BlockEntity entity = world.getBlockEntity(pos);
		for (ItemStack drop : Block.getDroppedStacks(state, world, pos, entity, player, new ItemStack(Items.NETHERITE_PICKAXE))) stock.give(drop);
	}

	// ------------------------------------------------------------------ Terraformer Cannon

	public record Plan(List<Edit> edits, int columns) {
		public int moved() { return edits.size(); }
	}

	/** A block to set (a state) at a position. */
	public record Edit(BlockPos pos, BlockState state) {}

	/** Where the ground is in a column: the highest solid block at or below {@code fromY}, ignoring plants and foliage. */
	private static int surface(ServerWorld world, int x, int z, int fromY, int lowest) {
		BlockPos.Mutable cursor = new BlockPos.Mutable(x, fromY, z);
		for (int y = fromY; y >= lowest; y--) {
			cursor.setY(y);
			BlockState state = world.getBlockState(cursor);
			if (state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) continue;
			if (!state.getFluidState().isEmpty()) continue;
			return y;
		}
		return Integer.MIN_VALUE;
	}

	/** Works out what the cannon would do, or returns null if the job is too big. Nothing is changed. */
	public static Plan terraform(ServerWorld world, Terrain mode, BlockPos target, int radius) {
		int limit = RackcraftConfig.values.building.maxBlocksPerUse;
		int top = Math.min(world.getTopY() - 1, target.getY() + radius);
		int bottom = Math.max(world.getBottomY(), target.getY() - radius);
		BlockState filler = fillerFor(world, target);
		List<Edit> edits = new ArrayList<>();
		int columns = 0;
		if (mode == Terrain.HOLLOW) {
			for (int dx = -radius; dx <= radius; dx++) for (int dy = -radius; dy <= radius; dy++) for (int dz = -radius; dz <= radius; dz++) {
				if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
				BlockPos pos = target.add(dx, dy, dz);
				if (pos.getY() < world.getBottomY() || pos.getY() >= world.getTopY()) continue;
				BlockState state = world.getBlockState(pos);
				if (state.isAir() || !editable(world, pos, state)) continue;
				edits.add(new Edit(pos, Blocks.AIR.getDefaultState()));
				if (edits.size() > limit) return null;
			}
			return new Plan(edits, 0);
		}
		for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
			if (dx * dx + dz * dz > radius * radius) continue;
			int x = target.getX() + dx;
			int z = target.getZ() + dz;
			int height = surface(world, x, z, top, bottom);
			if (height == Integer.MIN_VALUE) continue;
			int wanted = switch (mode) {
				case FLATTEN -> target.getY();
				case FILL -> Math.max(height, target.getY());
				default -> {
					// Smooth: the average of this column and its four neighbours.
					int sum = height;
					int count = 1;
					for (Direction side : Direction.Type.HORIZONTAL) {
						int neighbour = surface(world, x + side.getOffsetX(), z + side.getOffsetZ(), top, bottom);
						if (neighbour == Integer.MIN_VALUE) continue;
						sum += neighbour;
						count++;
					}
					yield Math.round(sum / (float) count);
				}
			};
			if (wanted == height) continue;
			BlockPos.Mutable cursor = new BlockPos.Mutable();
			// A column with a machine or a chest anywhere in it is left alone.
			boolean safe = true;
			for (int y = Math.min(height, wanted); y <= Math.max(height, wanted) + 1 && safe; y++) {
				cursor.set(x, y, z);
				BlockState state = world.getBlockState(cursor);
				if (!state.isAir() && !state.isReplaceable() && !editable(world, cursor, state)) safe = false;
			}
			if (!safe) continue;
			columns++;
			BlockState original = world.getBlockState(new BlockPos(x, height, z));
			if (wanted < height) {
				for (int y = wanted + 1; y <= Math.min(height + 8, top); y++) {
					cursor.set(x, y, z);
					BlockState state = world.getBlockState(cursor);
					if (!state.isAir() && editable(world, cursor, state)) edits.add(new Edit(cursor.toImmutable(), Blocks.AIR.getDefaultState()));
				}
				if (original.isOf(Blocks.GRASS_BLOCK) && wanted >= bottom) edits.add(new Edit(new BlockPos(x, wanted, z), Blocks.GRASS_BLOCK.getDefaultState()));
			} else {
				for (int y = height + 1; y <= wanted; y++) {
					edits.add(new Edit(new BlockPos(x, y, z), y == wanted && original.isOf(Blocks.GRASS_BLOCK) ? Blocks.GRASS_BLOCK.getDefaultState() : filler));
				}
				if (original.isOf(Blocks.GRASS_BLOCK)) edits.add(new Edit(new BlockPos(x, height, z), Blocks.DIRT.getDefaultState()));
			}
			if (edits.size() > limit) return null;
		}
		return new Plan(edits, columns);
	}

	/** Hydrogen Canisters a plan burns. */
	public static int canisters(Plan plan) {
		return (plan.moved() + RackcraftConfig.values.building.cannonBlocksPerCanister - 1) / RackcraftConfig.values.building.cannonBlocksPerCanister;
	}

	public static void apply(ServerWorld world, Plan plan) {
		for (Edit edit : plan.edits()) world.setBlockState(edit.pos(), edit.state(), Block.NOTIFY_ALL);
	}

	private static BlockState fillerFor(ServerWorld world, BlockPos target) {
		BlockState state = world.getBlockState(target);
		if (state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.DIRT) || state.isOf(Blocks.PODZOL) || state.isOf(Blocks.MYCELIUM)
				|| state.isOf(Blocks.COARSE_DIRT)) return Blocks.DIRT.getDefaultState();
		if (state.isOf(Blocks.SAND) || state.isOf(Blocks.RED_SAND) || state.isOf(Blocks.SANDSTONE)) return Blocks.SAND.getDefaultState();
		if (state.isOf(Blocks.SNOW_BLOCK)) return Blocks.SNOW_BLOCK.getDefaultState();
		if (state.isOf(Blocks.STONE) || state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.ANDESITE) || state.isOf(Blocks.GRANITE) || state.isOf(Blocks.DIORITE)) {
			return Blocks.COBBLESTONE.getDefaultState();
		}
		return Blocks.DIRT.getDefaultState();
	}

	public static Item hydrogen() { return RcItems.ITEMS.get("hydrogen_canister"); }
}
