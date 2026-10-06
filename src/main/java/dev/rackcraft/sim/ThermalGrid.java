package dev.rackcraft.sim;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ThermalGrid {
	private final Map<CellPos, Double> temperatures = new HashMap<>();
	private final double ambientCelsius;
	private final double capacityKjPerK;
	private final double faceConductanceKwPerK;
	private final double upwardMultiplier;
	private final double leakKwPerK;
	private final double settleEpsilonK;
	private final int maxCells;
	private boolean capacityWarningIssued;

	public ThermalGrid(double ambientCelsius, double capacityKjPerK, double faceConductanceKwPerK,
			double upwardMultiplier, double leakKwPerK, double settleEpsilonK, int maxCells) {
		this.ambientCelsius = ambientCelsius;
		this.capacityKjPerK = Math.max(0.001, capacityKjPerK);
		this.faceConductanceKwPerK = Math.max(0, faceConductanceKwPerK);
		this.upwardMultiplier = Math.max(1, upwardMultiplier);
		this.leakKwPerK = Math.max(0, leakKwPerK);
		this.settleEpsilonK = Math.max(0, settleEpsilonK);
		this.maxCells = Math.max(1, maxCells);
	}

	public boolean ensureCell(CellPos position) {
		if (temperatures.containsKey(position)) return true;
		if (temperatures.size() >= maxCells) {
			capacityWarningIssued = true;
			return false;
		}
		temperatures.put(position, ambientCelsius);
		return true;
	}

	public void depositKw(CellPos position, double heatKw, double dtSeconds) {
		if (heatKw <= 0 || dtSeconds <= 0 || !ensureCell(position)) return;
		double deltaK = heatKw * dtSeconds / capacityKjPerK;
		temperatures.merge(position, deltaK, Double::sum);
	}

	public double removeKw(CellPos position, double requestedKw, double dtSeconds) {
		if (requestedKw <= 0 || dtSeconds <= 0) return 0;
		Double temperature = temperatures.get(position);
		if (temperature == null || temperature <= ambientCelsius) return 0;
		double availableKw = (temperature - ambientCelsius) * capacityKjPerK / dtSeconds;
		double removedKw = Math.min(requestedKw, availableKw);
		temperatures.put(position, temperature - removedKw * dtSeconds / capacityKjPerK);
		return removedKw;
	}

	public void step(double dtSeconds, boolean skyLeak) {
		if (dtSeconds <= 0 || temperatures.isEmpty()) return;
		double maxConductance = faceConductanceKwPerK * 6 + leakKwPerK * (skyLeak ? 5 : 1);
		int substeps = Math.max(2, (int) Math.ceil(maxConductance * dtSeconds / capacityKjPerK / 0.45));
		double substepSeconds = dtSeconds / substeps;
		for (int step = 0; step < substeps; step++) diffuse(substepSeconds, skyLeak);
		temperatures.entrySet().removeIf(entry -> Math.abs(entry.getValue() - ambientCelsius) < settleEpsilonK);
	}

	private void diffuse(double dtSeconds, boolean skyLeak) {
		Map<CellPos, Double> energyDelta = new HashMap<>();
		Set<Pair> visitedPairs = new HashSet<>();
		for (Map.Entry<CellPos, Double> entry : temperatures.entrySet()) {
			CellPos position = entry.getKey();
			double temperature = entry.getValue();
			for (Direction direction : Direction.values()) {
				CellPos neighbour = position.offset(direction);
				Double neighbourTemperature = temperatures.get(neighbour);
				if (neighbourTemperature == null) continue;
				Pair pair = Pair.of(position, neighbour);
				if (!visitedPairs.add(pair)) continue;
				double conductance = faceConductanceKwPerK
						* (direction == Direction.UP ? upwardMultiplier : 1);
				double energyKj = conductance * (neighbourTemperature - temperature) * dtSeconds;
				energyDelta.merge(position, energyKj, Double::sum);
				energyDelta.merge(neighbour, -energyKj, Double::sum);
			}
			double leak = leakKwPerK * (skyLeak ? 5 : 1) * (temperature - ambientCelsius) * dtSeconds;
			energyDelta.merge(position, -leak, Double::sum);
		}
		energyDelta.forEach((position, energy) -> temperatures.computeIfPresent(position,
				(ignored, temperature) -> temperature + energy / capacityKjPerK));
	}

	public double temperatureCelsius(CellPos position) {
		return temperatures.getOrDefault(position, ambientCelsius);
	}

	public boolean setTemperatureCelsius(CellPos position, double temperatureCelsius) {
		if (!ensureCell(position)) return false;
		temperatures.put(position, Math.max(ambientCelsius, temperatureCelsius));
		return true;
	}

	public Map<CellPos, Double> snapshot() {
		return Map.copyOf(temperatures);
	}

	public int activeCells() { return temperatures.size(); }
	public boolean capacityWarningIssued() { return capacityWarningIssued; }
	public double ambientCelsius() { return ambientCelsius; }

	public record CellPos(int x, int y, int z) {
		CellPos offset(Direction direction) {
			return new CellPos(x + direction.dx, y + direction.dy, z + direction.dz);
		}
	}

	private enum Direction {
		DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);
		private final int dx;
		private final int dy;
		private final int dz;
		Direction(int dx, int dy, int dz) { this.dx = dx; this.dy = dy; this.dz = dz; }
	}

	private record Pair(CellPos first, CellPos second) {
		private static Pair of(CellPos first, CellPos second) {
			return first.hashCode() <= second.hashCode() ? new Pair(first, second) : new Pair(second, first);
		}
	}
}