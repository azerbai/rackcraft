package dev.rackcraft.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Air temperature, one cell per block of air. Only cells warmer than ambient are tracked. Heat dropped into
 * a cell spreads to the air around it (faster upward when the lower cell is warmer: hot air rises), and every
 * cell loses heat to the outside: slowly through walls, quickly where it can see the sky. So heat pools in a
 * closed room and blows away outdoors.
 *
 * <p>With an {@link AirMap} the grid grows into neighbouring air as it warms; without one (the unit tests) it
 * only diffuses between cells that already exist.
 */
public final class ThermalGrid {
	/** What the world looks like to the air: which blocks air can be in, and which are open to the sky. */
	public interface AirMap {
		boolean passable(CellPos position);
		boolean outdoors(CellPos position);
	}

	/** Cells warmer than ambient by this much spread into the air next to them. */
	private static final double SPREAD_K = 0.05;
	/** New cells per step at most, so a sudden heat source can't stall a tick. */
	private static final int MAX_NEW_CELLS_PER_STEP = 4096;

	private final Map<CellPos, Cell> cells = new HashMap<>();
	private final double ambientCelsius;
	private final double capacityKjPerK;
	private final double faceConductanceKwPerK;
	private final double upwardMultiplier;
	private final double leakKwPerK;
	private final double outdoorLeakKwPerK;
	private final double settleEpsilonK;
	private final int maxCells;
	private boolean capacityWarningIssued;

	private static final class Cell {
		double temperature;
		double energyDelta;
		boolean outdoors;

		Cell(double temperature, boolean outdoors) {
			this.temperature = temperature;
			this.outdoors = outdoors;
		}
	}

	public ThermalGrid(double ambientCelsius, double capacityKjPerK, double faceConductanceKwPerK,
			double upwardMultiplier, double leakKwPerK, double settleEpsilonK, int maxCells) {
		this(ambientCelsius, capacityKjPerK, faceConductanceKwPerK, upwardMultiplier, leakKwPerK, leakKwPerK * 5,
				settleEpsilonK, maxCells);
	}

	public ThermalGrid(double ambientCelsius, double capacityKjPerK, double faceConductanceKwPerK,
			double upwardMultiplier, double leakKwPerK, double outdoorLeakKwPerK, double settleEpsilonK, int maxCells) {
		this.ambientCelsius = ambientCelsius;
		this.capacityKjPerK = Math.max(0.001, capacityKjPerK);
		this.faceConductanceKwPerK = Math.max(0, faceConductanceKwPerK);
		this.upwardMultiplier = Math.max(1, upwardMultiplier);
		this.leakKwPerK = Math.max(0, leakKwPerK);
		this.outdoorLeakKwPerK = Math.max(this.leakKwPerK, outdoorLeakKwPerK);
		this.settleEpsilonK = Math.max(0, settleEpsilonK);
		this.maxCells = Math.max(1, maxCells);
	}

	public boolean ensureCell(CellPos position) {
		return ensureCell(position, false);
	}

	public boolean ensureCell(CellPos position, boolean outdoors) {
		if (cells.containsKey(position)) return true;
		if (cells.size() >= maxCells) {
			capacityWarningIssued = true;
			return false;
		}
		cells.put(position, new Cell(ambientCelsius, outdoors));
		return true;
	}

	public void depositKw(CellPos position, double heatKw, double dtSeconds) {
		if (heatKw <= 0 || dtSeconds <= 0 || !ensureCell(position)) return;
		cells.get(position).temperature += heatKw * dtSeconds / capacityKjPerK;
	}

	public double removeKw(CellPos position, double requestedKw, double dtSeconds) {
		if (requestedKw <= 0 || dtSeconds <= 0) return 0;
		Cell cell = cells.get(position);
		if (cell == null || cell.temperature <= ambientCelsius) return 0;
		double availableKw = (cell.temperature - ambientCelsius) * capacityKjPerK / dtSeconds;
		double removedKw = Math.min(requestedKw, availableKw);
		cell.temperature -= removedKw * dtSeconds / capacityKjPerK;
		return removedKw;
	}

	/** Diffuses heat between existing cells only; {@code skyLeak} treats every cell as outdoors. */
	public void step(double dtSeconds, boolean skyLeak) {
		if (skyLeak) cells.values().forEach(cell -> cell.outdoors = true);
		step(dtSeconds, null);
	}

	/** One step: grow into warm air's neighbours, drop cells that are no longer air, diffuse, leak, settle. */
	public void step(double dtSeconds, AirMap air) {
		if (dtSeconds <= 0 || cells.isEmpty()) return;
		if (air != null) {
			cells.entrySet().removeIf(entry -> !air.passable(entry.getKey()));
			spread(air);
		}
		double maxConductance = faceConductanceKwPerK * (5 + upwardMultiplier) + outdoorLeakKwPerK;
		int substeps = Math.max(2, (int) Math.ceil(maxConductance * dtSeconds / capacityKjPerK / 0.45));
		double substepSeconds = dtSeconds / substeps;
		for (int step = 0; step < substeps; step++) diffuse(substepSeconds);
		settle();
	}

	/** Forgets cells back at ambient, except at the edge of warm air, where they are still warming up. */
	private void settle() {
		cells.entrySet().removeIf(entry -> Math.abs(entry.getValue().temperature - ambientCelsius) < settleEpsilonK
				&& !warmNeighbour(entry.getKey()));
	}

	private boolean warmNeighbour(CellPos position) {
		for (Direction direction : Direction.values()) {
			Cell neighbour = cells.get(position.offset(direction));
			if (neighbour != null && neighbour.temperature >= ambientCelsius + SPREAD_K) return true;
		}
		return false;
	}

	private void spread(AirMap air) {
		List<CellPos> fresh = new ArrayList<>();
		for (Map.Entry<CellPos, Cell> entry : cells.entrySet()) {
			if (entry.getValue().temperature < ambientCelsius + SPREAD_K) continue;
			for (Direction direction : Direction.values()) {
				CellPos neighbour = entry.getKey().offset(direction);
				if (!cells.containsKey(neighbour)) fresh.add(neighbour);
			}
			if (fresh.size() >= MAX_NEW_CELLS_PER_STEP) break;
		}
		for (CellPos position : fresh) {
			if (cells.containsKey(position) || !air.passable(position)) continue;
			if (!ensureCell(position, air.outdoors(position))) return;
		}
	}

	private void diffuse(double dtSeconds) {
		for (Map.Entry<CellPos, Cell> entry : cells.entrySet()) {
			CellPos position = entry.getKey();
			Cell cell = entry.getValue();
			// Each pair once: look east, south and up only.
			for (Direction direction : Direction.POSITIVE) {
				Cell neighbour = cells.get(position.offset(direction));
				if (neighbour == null) continue;
				double conductance = faceConductanceKwPerK;
				if (direction == Direction.UP && cell.temperature > neighbour.temperature) conductance *= upwardMultiplier;
				double energyKj = conductance * (neighbour.temperature - cell.temperature) * dtSeconds;
				cell.energyDelta += energyKj;
				neighbour.energyDelta -= energyKj;
			}
			double leak = cell.outdoors ? outdoorLeakKwPerK : leakKwPerK;
			cell.energyDelta -= leak * (cell.temperature - ambientCelsius) * dtSeconds;
		}
		for (Cell cell : cells.values()) {
			cell.temperature += cell.energyDelta / capacityKjPerK;
			cell.energyDelta = 0;
		}
	}

	public double temperatureCelsius(CellPos position) {
		Cell cell = cells.get(position);
		return cell == null ? ambientCelsius : cell.temperature;
	}

	public boolean setTemperatureCelsius(CellPos position, double temperatureCelsius) {
		if (!ensureCell(position)) return false;
		cells.get(position).temperature = Math.max(ambientCelsius, temperatureCelsius);
		return true;
	}

	public Map<CellPos, Double> snapshot() {
		Map<CellPos, Double> copy = new HashMap<>();
		cells.forEach((position, cell) -> copy.put(position, cell.temperature));
		return copy;
	}

	public int activeCells() { return cells.size(); }
	public boolean capacityWarningIssued() { return capacityWarningIssued; }
	public double ambientCelsius() { return ambientCelsius; }

	public record CellPos(int x, int y, int z) {
		public CellPos offset(Direction direction) {
			return new CellPos(x + direction.dx, y + direction.dy, z + direction.dz);
		}
	}

	public enum Direction {
		DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);
		private static final Direction[] POSITIVE = {UP, SOUTH, EAST};
		private final int dx;
		private final int dy;
		private final int dz;
		Direction(int dx, int dy, int dz) { this.dx = dx; this.dy = dy; this.dz = dz; }
	}
}
