package dev.rackcraft.world;

import dev.rackcraft.Rackcraft;
import dev.rackcraft.RcBlocks;
import dev.rackcraft.block.CableBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Routes for the Cable Planner. A route is a list of cells between two points, each flagged free, blocked (something
 * solid is there) or already holding the same cable. The server works it out and sends it to the player's client to
 * draw as a ghost; only the server ever places anything.
 */
public final class CablePlanner {
	public static final Identifier GHOST = Rackcraft.id("cable_ghost");
	public static final String[] STYLES = {"Straight", "Zig-zag", "Shortest", "Hug the walls"};
	/** The cables a plan can lay, in the order the planner cycles them. */
	public static final String[] KINDS = {"power_cable", "coolant_pipe", "fiber_cable", "item_pipe", "trunk_bundle"};
	public static final int FREE = 0;
	public static final int BLOCKED = 1;
	public static final int EXISTING = 2;
	/** Routes longer than this are refused. */
	public static final int MAX_LENGTH = 512;
	private static final int SEARCH_MARGIN = 8;
	private static final int MAX_EXPANSIONS = 40_000;

	public record Cell(BlockPos pos, int flag) {}

	private CablePlanner() {}

	/** The route from {@code a} to {@code b} (both inclusive), or an empty list if there isn't a sensible one. */
	public static List<Cell> route(ServerWorld world, BlockPos a, BlockPos b, int style, Block cable) {
		int span = Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
		if (span >= MAX_LENGTH) return List.of();
		List<BlockPos> path = null;
		if (style >= 2) path = search(world, a, b, cable, style == 3);
		if (path == null) {
			// Straight runs go X, Z then Y; zig-zags go up or down first. A failed search falls back to a straight run too.
			path = axes(a, b, style == 1 ? new Direction.Axis[] {Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z}
					: new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z, Direction.Axis.Y});
		}
		List<Cell> cells = new ArrayList<>(path.size());
		for (BlockPos pos : path) cells.add(new Cell(pos, flag(world, pos, cable)));
		return cells;
	}

	public static int flag(ServerWorld world, BlockPos pos, Block cable) {
		BlockState state = world.getBlockState(pos);
		if (state.isOf(cable)) return EXISTING;
		return state.isReplaceable() ? FREE : BLOCKED;
	}

	private static List<BlockPos> axes(BlockPos a, BlockPos b, Direction.Axis[] order) {
		List<BlockPos> path = new ArrayList<>();
		BlockPos.Mutable cursor = a.mutableCopy();
		path.add(cursor.toImmutable());
		for (Direction.Axis axis : order) {
			int target = b.getComponentAlongAxis(axis);
			while (cursor.getComponentAlongAxis(axis) != target) {
				int step = Integer.signum(target - cursor.getComponentAlongAxis(axis));
				cursor.move(Direction.from(axis, step > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE));
				path.add(cursor.toImmutable());
			}
		}
		return path;
	}

	private record Node(long pos, int cost, int estimate) {}

	/** A* through free cells (and cells that already hold this cable). Hugging walls makes open air cost four times as much. */
	private static List<BlockPos> search(ServerWorld world, BlockPos a, BlockPos b, Block cable, boolean hug) {
		if (flag(world, a, cable) == BLOCKED || flag(world, b, cable) == BLOCKED) return null;
		int minX = Math.min(a.getX(), b.getX()) - SEARCH_MARGIN, maxX = Math.max(a.getX(), b.getX()) + SEARCH_MARGIN;
		int minY = Math.max(world.getBottomY(), Math.min(a.getY(), b.getY()) - SEARCH_MARGIN);
		int maxY = Math.min(world.getTopY() - 1, Math.max(a.getY(), b.getY()) + SEARCH_MARGIN);
		int minZ = Math.min(a.getZ(), b.getZ()) - SEARCH_MARGIN, maxZ = Math.max(a.getZ(), b.getZ()) + SEARCH_MARGIN;
		PriorityQueue<Node> open = new PriorityQueue<>((x, y) -> Integer.compare(x.cost + x.estimate, y.cost + y.estimate));
		Map<Long, Long> from = new HashMap<>();
		Map<Long, Integer> best = new HashMap<>();
		open.add(new Node(a.asLong(), 0, distance(a, b)));
		best.put(a.asLong(), 0);
		int expansions = 0;
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
			Node node = open.poll();
			if (node.cost > best.getOrDefault(node.pos, Integer.MAX_VALUE)) continue;
			BlockPos here = BlockPos.fromLong(node.pos);
			if (here.equals(b)) {
				List<BlockPos> path = new ArrayList<>();
				for (Long step = node.pos; step != null; step = from.get(step)) path.add(BlockPos.fromLong(step));
				Collections.reverse(path);
				return path;
			}
			for (Direction direction : Direction.values()) {
				BlockPos next = here.offset(direction);
				if (next.getX() < minX || next.getX() > maxX || next.getY() < minY || next.getY() > maxY
						|| next.getZ() < minZ || next.getZ() > maxZ) continue;
				if (flag(world, next, cable) == BLOCKED) continue;
				int step = 1;
				if (hug && !touchesSolid(world, next, cursor)) step = 4;
				int cost = node.cost + step;
				if (cost >= best.getOrDefault(next.asLong(), Integer.MAX_VALUE)) continue;
				best.put(next.asLong(), cost);
				from.put(next.asLong(), node.pos);
				open.add(new Node(next.asLong(), cost, distance(next, b)));
			}
		}
		return null;
	}

	private static boolean touchesSolid(ServerWorld world, BlockPos pos, BlockPos.Mutable cursor) {
		for (Direction direction : Direction.values()) {
			cursor.set(pos, direction);
			if (!world.getBlockState(cursor).isReplaceable()) return true;
		}
		return false;
	}

	private static int distance(BlockPos a, BlockPos b) {
		return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
	}

	public static Block cableBlock(int kind) {
		return RcBlocks.get(KINDS[Math.floorMod(kind, KINDS.length)]);
	}

	/** Lays the route: free cells only, in order, stopping at the first blocked cell or when the cables run out. */
	public record Laid(int placed, int skipped, boolean blocked, boolean outOfCables) {}

	public static Laid lay(ServerWorld world, List<Cell> cells, Block cable, BuildStock stock) {
		int placed = 0;
		int skipped = 0;
		for (Cell cell : cells) {
			if (cell.flag() == EXISTING) {
				skipped++;
				continue;
			}
			// Re-check: the world may have changed since the route was drawn.
			int flag = flag(world, cell.pos(), cable);
			if (flag == BLOCKED) return new Laid(placed, skipped, true, false);
			if (flag == EXISTING) {
				skipped++;
				continue;
			}
			if (stock.take(cable.asItem(), 1) < 1) return new Laid(placed, skipped, false, true);
			world.setBlockState(cell.pos(), ((CableBlock) cable).withConnections(cable.getDefaultState(), world, cell.pos()), Block.NOTIFY_ALL);
			placed++;
		}
		return new Laid(placed, skipped, false, false);
	}

	/** Sends the ghost to a player's client. An empty list clears it. */
	public static void sendGhost(ServerPlayerEntity player, int kind, List<Cell> cells) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(Math.floorMod(kind, KINDS.length));
		int count = Math.min(cells.size(), MAX_LENGTH);
		buf.writeVarInt(count);
		for (int index = 0; index < count; index++) {
			buf.writeLong(cells.get(index).pos().asLong());
			buf.writeByte(cells.get(index).flag());
		}
		ServerPlayNetworking.send(player, GHOST, buf);
	}
}
