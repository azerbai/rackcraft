package dev.rackcraft.world;

import dev.rackcraft.block.CableBlock;
import dev.rackcraft.block.MachineBlockEntity;
import dev.rackcraft.compute.Research;
import dev.rackcraft.compute.ResearchLab;
import dev.rackcraft.entity.MaglevCarEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Mag-Lev. Rails and Stations that touch form a line; a car runs along it from one Station to another, carrying one rider.
 * A rail is an ordinary power-network block, so it carries power from whatever cable it touches, and a cut rail breaks the
 * line. Right-click a Station to ride to the destination it is set to, sneak-right-click to pick the next destination
 * (nearest first). A car in motion draws {@link #CAR_KW} from its starting Station's network and slows with the share the
 * grid can give, stopping under 10%.
 */
public final class MaglevNetwork {
	public static final String GATE = "maglev";
	public static final double CAR_KW = 800;
	public static final double SPEED = 0.9;
	private static final int MAX_NODES = 6000;
	private static final String DEST = "MaglevDest";

	public record Stop(BlockPos pos, int distance) {}

	/** Cars running from each Station (by world and position), so its network sees the load. */
	private static final Map<String, Integer> CARS = new HashMap<>();

	private MaglevNetwork() {}

	public static String key(ServerWorld world, BlockPos pos) { return TeleportPads.key(world, pos); }

	/** A block a car can run on: a rail that isn't cut, or a Station. */
	public static boolean track(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		String id = Registries.BLOCK.getId(state.getBlock()).getPath();
		if (id.equals("maglev_station")) return true;
		return id.equals("maglev_rail") && !state.get(CableBlock.CUT);
	}

	private static boolean station(ServerWorld world, BlockPos pos) {
		return world.getBlockState(pos).getBlock() == dev.rackcraft.RcBlocks.get("maglev_station");
	}

	/** Every Station reachable along the line from this one, nearest first, with how many blocks away. */
	public static List<Stop> stations(ServerWorld world, BlockPos from) {
		List<Stop> found = new ArrayList<>();
		Map<BlockPos, Integer> seen = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.put(from, 0);
		queue.add(from);
		while (!queue.isEmpty() && seen.size() < MAX_NODES) {
			BlockPos pos = queue.poll();
			if (!pos.equals(from) && station(world, pos)) found.add(new Stop(pos, seen.get(pos)));
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.offset(direction);
				if (!seen.containsKey(next) && track(world, next)) {
					seen.put(next, seen.get(pos) + 1);
					queue.add(next);
				}
			}
		}
		found.sort(Comparator.comparingInt(Stop::distance).thenComparingLong(stop -> stop.pos().asLong()));
		return found;
	}

	/** The blocks to run along from one Station to another (both included), or empty if the line doesn't connect them. */
	public static List<BlockPos> route(ServerWorld world, BlockPos from, BlockPos to) {
		Map<BlockPos, BlockPos> came = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		came.put(from, from);
		queue.add(from);
		while (!queue.isEmpty() && came.size() < MAX_NODES) {
			BlockPos pos = queue.poll();
			if (pos.equals(to)) break;
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.offset(direction);
				if (!came.containsKey(next) && track(world, next)) {
					came.put(next, pos);
					queue.add(next);
				}
			}
		}
		if (!came.containsKey(to)) return List.of();
		List<BlockPos> path = new ArrayList<>();
		for (BlockPos at = to; !at.equals(from); at = came.get(at)) path.add(at);
		path.add(from);
		Collections.reverse(path);
		return path;
	}

	public static BlockPos destination(MachineBlockEntity station) {
		return station.site().contains(DEST) ? BlockPos.fromLong(station.site().getLong(DEST)) : null;
	}

	/** Moves a Station's destination to the next one down the line. Returns what to tell the player. */
	public static String cycle(ServerWorld world, MachineBlockEntity station) {
		if (!ResearchLab.get(world).done(GATE)) return "Inert until " + Research.get(GATE).name() + " is researched";
		List<Stop> stops = stations(world, station.getPos());
		if (stops.isEmpty()) return "No other station on this line";
		BlockPos current = destination(station);
		int index = -1;
		for (int i = 0; i < stops.size(); i++) if (stops.get(i).pos().equals(current)) index = i;
		Stop next = stops.get((index + 1) % stops.size());
		station.site().putLong(DEST, next.pos().asLong());
		station.markDirty();
		return "Destination: station at " + next.pos().toShortString() + ", " + next.distance() + " blocks down the line";
	}

	/** Sends the player to the Station's destination (the nearest, if none is set). Returns what is wrong, or null. */
	public static String ride(ServerWorld world, MachineBlockEntity station, net.minecraft.entity.Entity player) {
		if (!ResearchLab.get(world).done(GATE)) return "Inert until " + Research.get(GATE).name() + " is researched";
		if (station.powerSatisfaction() < MaglevCarEntity.MIN_POWER) return "The station has no power";
		if (player.hasVehicle()) return "You are already riding something";
		List<Stop> stops = stations(world, station.getPos());
		if (stops.isEmpty()) return "No other station on this line: is a rail cut?";
		BlockPos wanted = destination(station);
		BlockPos target = stops.stream().map(Stop::pos).filter(pos -> pos.equals(wanted)).findFirst().orElse(stops.get(0).pos());
		List<BlockPos> route = route(world, station.getPos(), target);
		if (route.size() < 2) return "The line to that station is broken";
		MaglevCarEntity car = MaglevCarEntity.launch(world, route, station.getPos());
		if (!player.startRiding(car, true)) {
			car.discard();
			return "Couldn't board the car";
		}
		return null;
	}

	public static void carStarted(ServerWorld world, BlockPos origin) { CARS.merge(key(world, origin), 1, Integer::sum); }

	public static void carEnded(ServerWorld world, BlockPos origin) {
		CARS.computeIfPresent(key(world, origin), (ignored, count) -> count <= 1 ? null : count - 1);
	}

	/** What a Station draws: a little, and a car's load for each car it has sent out that is still running. */
	public static double demandKw(ServerWorld world, MachineBlockEntity station) {
		return 0.5 + CAR_KW * CARS.getOrDefault(key(world, station.getPos()), 0);
	}

	public static void reset() { CARS.clear(); }
}
